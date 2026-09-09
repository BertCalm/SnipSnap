package com.snipsnap.app

import com.snipsnap.app.ui.TAPE_LOAD_MAX_SEC
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.PadHit
import java.io.File

/**
 * M4's pad voice: the Kotlin owner of one native [NativePads] engine,
 * replacing the interim SoundPool player for PLAY. A kit's samples are
 * read once ([load], off the main thread - it reads WAVs) into a bank
 * the callback adopts whole; a hit resolves through `PadHit` (`:shell`,
 * tested: the layer, the chain slice, level, pan, tune) and crosses the
 * bridge as one command keyed by the allocator's voice id; the engine
 * reports each voice's end back through [drainEnded], so the allocator
 * learns of an ending when it happens.
 *
 * Any thread but the audio thread; calls are serialised. [close] is
 * idempotent and every call after it is a no-op.
 */
class PadEngine(preferredSampleRate: Int) {

    private var handle: Long = NativePads.create(preferredSampleRate)
    private val open get() = handle != 0L

    /** True between a successful [start] and [close]. See [isUp] for whether a callback is actually running. */
    @Volatile
    var running: Boolean = false
        private set

    /**
     * A callback is running right now: started, not closed, and the native
     * side has not flagged a dead stream. The flag flips on the audio
     * thread's own error callback, so this is the truth even in the window
     * before the screen's frame loop gets round to the restart - a hit in
     * that window is refused rather than counted by nothing.
     */
    @Synchronized
    fun isUp(): Boolean = open && running && !NativePads.needsRestart(handle)

    /**
     * One bank build at a time. The per-call `synchronized(this)` below
     * keeps each native call safe on its own, but a *build* is a sequence —
     * begin, add, add, …, commit — and the native builder is one slot.
     * Two overlapping builds would have the second `beginBank` throw away
     * the first's half-filled bank, leaving its sample indices pointing
     * into the other's, and whichever committed last would win with a
     * mixture of two kits.
     *
     * Deliberately *not* `this`: [close] and the command calls synchronize
     * on the instance, and holding those across megabytes of copying is
     * what makes a close on the main thread wait. This lock serialises
     * builders against each other and nothing else.
     */
    private val bankLock = Any()

    private var sampleIndex: Map<String, Int> = emptyMap()
    private var framesOf: Map<String, Long> = emptyMap()
    private val hits = HashMap<Int, Int>()

    /**
     * The count-in click's reserved bank index, valid only after [load];
     * -1 before then or if the engine has no stream. This is a native bank
     * index — the same numbering [NativePads.addSample] hands back for
     * every kit sample — not a [KitPad.slot] (1..128); the two are
     * different spaces and never compared. [load] appends the click as
     * the last two samples of the bank it rebuilds every time (accent,
     * then this one, so this index is always the accent's plus one), and
     * neither can ever collide with a kit sample's index: every kit file
     * is indexed first, in file order, before either click is added, so
     * this index is always past every one of them.
     */
    var clickSampleIndex: Int = -1
        private set

    /** The click's accented (downbeat) sibling — one bank slot before [clickSampleIndex], same reasoning. */
    private var clickAccentIndex: Int = -1
    private var clickFrames: Long = 0L
    private var clickAccentFrames: Long = 0L

    @Synchronized
    fun start(): Boolean {
        running = open && NativePads.start(handle)
        return running
    }

    @Synchronized
    fun needsRestart(): Boolean = open && NativePads.needsRestart(handle)

    /** The device refused an exclusive stream; the shared fallback is playing. */
    @Synchronized
    fun isShared(): Boolean = open && NativePads.isShared(handle)

    /**
     * The stream's round-trip latency in ms, or null when there is none or
     * the device will not say. Cheap but not free (it reads a timestamp),
     * so poll it about once a second, not per frame.
     */
    @Synchronized
    fun latencyMillis(): Double? =
        if (open && running) NativePads.latencyMillis(handle).takeIf { it > 0.0 } else null

    /**
     * Read every sample the kit's pads and layers name and hand the bank to
     * the engine. Blocking disk IO: call it from an IO dispatcher. A WAV
     * that will not read is left out, and its pad plays nothing (a hit
     * resolves to null) rather than something else.
     */
    fun load(entry: KitShelf.Entry) = synchronized(bankLock) { loadLocked(entry) }

    private fun loadLocked(entry: KitShelf.Entry) {
        val files = LinkedHashSet<String>()
        for (pad in entry.kit.pads) {
            files += pad.sampleFile
            pad.velocityLayers.forEach { files += it.sampleFile }
        }
        val index = HashMap<String, Int>()
        val frames = HashMap<String, Long>()
        var engineRate = 44_100
        synchronized(this) {
            if (!open) return
            NativePads.beginBank(handle)
            engineRate = NativePads.sampleRate(handle)
        }
        for (file in files) {
            // `file` is a kit pad sample, produced only by KitBuilderModel.assign
            // from an already-bounded Snip — readCapped's 600s ceiling is defense
            // in depth, not expected to ever bind.
            val snip = runCatching { WavReader.readCapped(File(entry.dir, file), TAPE_LOAD_MAX_SEC).snip }.getOrNull() ?: continue
            val i = synchronized(this) {
                if (!open) return
                NativePads.addSample(handle, snip.samples, snip.channels, snip.sampleRate)
            }
            index[file] = i
            frames[file] = snip.frameCount.toLong()
        }
        // The count-in click, synthesized (never a bundled asset) and
        // appended last, inside this same bank build — not a second
        // beginBank/commitBank pair, and never loadSnips, which replaces
        // the whole bank and would silence every pad just added above.
        val accentClick = DrumSynth.click(engineRate, accent = true)
        val regularClick = DrumSynth.click(engineRate, accent = false)
        val accentIdx = synchronized(this) {
            if (!open) return
            NativePads.addSample(handle, accentClick.samples, accentClick.channels, accentClick.sampleRate)
        }
        val regularIdx = synchronized(this) {
            if (!open) return
            NativePads.addSample(handle, regularClick.samples, regularClick.channels, regularClick.sampleRate)
        }
        synchronized(this) {
            if (!open) return
            NativePads.commitBank(handle)
            sampleIndex = index
            framesOf = frames
            hits.clear()
            clickAccentIndex = accentIdx
            clickSampleIndex = regularIdx
            clickAccentFrames = accentClick.frameCount.toLong()
            clickFrames = regularClick.frameCount.toLong()
        }
    }

    /**
     * Bank audio already in hand rather than files on the shelf — what
     * SPLIT has once a separation has run. Returns each snip's bank index
     * in the order given, or an empty list when there is no engine.
     *
     * A bank holds one thing at a time, so the kit index [load] built is
     * cleared with it: after this the engine plays these snips and nothing
     * else. Copies every sample across the bridge, so keep it off the main
     * thread.
     *
     * Locked per native call rather than for the whole run, exactly as
     * [load] is: a split's three buffers are megabytes, and holding the
     * monitor across all of them would make a [close] on the main thread —
     * leaving the screen mid-load — wait for every copy to finish.
     */
    fun loadSnips(snips: List<Snip>): List<Int> = synchronized(bankLock) { loadSnipsLocked(snips) }

    private fun loadSnipsLocked(snips: List<Snip>): List<Int> {
        synchronized(this) {
            if (!open) return emptyList()
            NativePads.beginBank(handle)
        }
        val indices = ArrayList<Int>(snips.size)
        for (snip in snips) {
            indices += synchronized(this) {
                if (!open) return emptyList()
                NativePads.addSample(handle, snip.samples, snip.channels, snip.sampleRate)
            }
        }
        synchronized(this) {
            if (!open) return emptyList()
            NativePads.commitBank(handle)
            sampleIndex = emptyMap()
            framesOf = emptyMap()
            hits.clear()
            // This bank replaces the one `load` built (see the KDoc above) -
            // any click indices it recorded now point into a bank that no
            // longer exists. Clearing them keeps `clickHit`'s -1 guard
            // truthful instead of leaving a third, undocumented state where
            // it addresses a stale or wrong sample.
            clickSampleIndex = -1
            clickAccentIndex = -1
            clickFrames = 0L
            clickAccentFrames = 0L
        }
        return indices
    }

    /** The loaded sample's length in frames, or null when it never loaded. */
    @Synchronized
    fun frames(sampleFile: String): Long? = framesOf[sampleFile]

    /**
     * Play [pad] at [velocity] as voice [voiceId]. False when no stream is
     * running, the pad has nothing loaded to play, or the command ring was
     * full - so the caller can tell its allocator the voice never sounded
     * and nothing counts a voice that no callback will ever end.
     */
    @Synchronized
    fun hit(pad: KitPad, velocity: Float, voiceId: Int): Boolean {
        if (!isUp()) return false
        val n = hits[pad.slot] ?: 0
        hits[pad.slot] = n + 1
        val hit = PadHit.resolve(pad, velocity, n) { framesOf[it] } ?: return false
        val sample = sampleIndex[hit.sampleFile] ?: return false
        return NativePads.noteOn(
            handle, voiceId, sample,
            hit.startFrame, hit.endFrameExclusive, -1L, hit.gainLeft, hit.gainRight, hit.pitchRatio,
            reverse = false,
        )
    }

    /**
     * Start [layers] of one loaded sample together. Every layer reads the
     * same window at the same speed - they are one sound taken apart - so
     * only the gains and the direction differ, and they are published to
     * the callback as one group: three layers can never start a buffer
     * apart, which would be heard as a flam and chased for hours.
     *
     * False means *nothing* was queued: the caller still owns every id it
     * was given, and none of them is sounding.
     */
    @Synchronized
    fun hitLayers(layers: List<Layer>, startFrame: Long, endFrame: Long, loopStart: Long, pitch: Double): Boolean {
        if (!isUp() || layers.isEmpty()) return false
        // The native side refuses a group larger than the engine has voices;
        // saying so here means four arrays are never built for it.
        if (layers.size > MAX_VOICES) return false
        return NativePads.noteOnLayers(
            handle,
            IntArray(layers.size) { layers[it].voiceId },
            IntArray(layers.size) { layers[it].sample },
            startFrame, endFrame, loopStart,
            FloatArray(layers.size) { layers[it].gainLeft },
            FloatArray(layers.size) { layers[it].gainRight },
            pitch,
            BooleanArray(layers.size) { layers[it].reverse },
        )
    }

    /** One voice of a group: which bank sample, at what level, which way round. */
    data class Layer(
        val voiceId: Int,
        val sample: Int,
        val gainLeft: Float,
        val gainRight: Float,
        val reverse: Boolean,
    )

    /**
     * Play the count-in click at [voiceId], one-shot, full length —
     * [accent] for the downbeat, false for the other three beats. Goes
     * through [hitLayers] with a single raw bank index, exactly the
     * `SplitScreen` precedent for sounding a bank sample with no
     * [KitPad] involved: this is a timing cue, not a kit sound, so it
     * never touches a choke group, a pad slot, or `VoiceAllocator` — the
     * voice id is the caller's to give and own, same as every other
     * `hitLayers` call.
     *
     * False exactly when [hit] would be false: no stream running, or
     * (here) the click never loaded — [load] hasn't been called yet, or
     * the bank build never got as far as adding it.
     *
     * The caller owns [voiceId] and must supply one outside any
     * `VoiceAllocator`'s range on the same engine (its own ids start at 1
     * and only grow, so any id `<= 0` is safe forever): the native side
     * stamps whatever id it is given onto a voice with no dedup against
     * ids already sounding, so an id the allocator could also hand out
     * would let a click's own ending get reported as a pad's, or vice
     * versa, to whichever side is listening on [drainEnded].
     */
    @Synchronized
    fun clickHit(voiceId: Int, accent: Boolean): Boolean {
        val sample = if (accent) clickAccentIndex else clickSampleIndex
        val frames = if (accent) clickAccentFrames else clickFrames
        if (sample < 0 || frames <= 0L) return false
        return hitLayers(
            layers = listOf(Layer(voiceId = voiceId, sample = sample, gainLeft = CLICK_GAIN, gainRight = CLICK_GAIN, reverse = false)),
            startFrame = 0L,
            endFrame = frames,
            loopStart = -1L,
            pitch = 1.0,
        )
    }

    /**
     * Move a sounding voice's gains, gliding over [glideMs] rather than
     * stepping. This is what a fader is: retriggering the note on every
     * drag frame would be a click per frame, and a step per frame a
     * zipper. A voice that has already ended simply ignores it.
     */
    @Synchronized
    fun setGain(voiceId: Int, gainLeft: Float, gainRight: Float, glideMs: Float = FADER_GLIDE_MS) {
        if (open) NativePads.setVoiceGain(handle, voiceId, gainLeft, gainRight, glideMs)
    }

    /** Stop one voice with a short fade: a choke, a steal, a gate's release. */
    @Synchronized
    fun stop(voiceId: Int, fadeMs: Float = CHOKE_FADE_MS) {
        if (open) NativePads.stopVoice(handle, voiceId, fadeMs)
    }

    /** Everything, with a slightly longer fade (leaving PLAY, the panic). */
    @Synchronized
    fun allOff(fadeMs: Float = PANIC_FADE_MS) {
        if (open) NativePads.allOff(handle, fadeMs)
    }

    /** The ids of voices that ended since the last call; drain at screen rate. */
    @Synchronized
    fun drainEnded(): IntArray = if (open) NativePads.drainEnded(handle) else IntArray(0)

    @Synchronized
    fun close() {
        if (!open) return
        running = false
        NativePads.stop(handle)
        NativePads.destroy(handle)
        handle = 0L
    }

    companion object {
        /** The engine's polyphony; the VoiceAllocator in PLAY is built at the same number. */
        const val MAX_VOICES = 32

        /**
         * A fader's glide. Long enough that a drag is smooth, short enough
         * that letting go feels immediate - about two screen frames.
         */
        const val FADER_GLIDE_MS = 30f

        /** A choke is a short fade, not a cut: long enough to spare the click, short enough to read as a cut. */
        const val CHOKE_FADE_MS = 5f
        const val PANIC_FADE_MS = 20f

        /** The count-in click's fixed level — a cue meant to sit under the kit, not a mixed-in sound. */
        const val CLICK_GAIN = 0.6f
    }
}
