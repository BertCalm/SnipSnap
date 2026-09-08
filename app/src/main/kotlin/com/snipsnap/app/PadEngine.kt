package com.snipsnap.app

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

    private var sampleIndex: Map<String, Int> = emptyMap()
    private var framesOf: Map<String, Long> = emptyMap()
    private val hits = HashMap<Int, Int>()

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
    fun load(entry: KitShelf.Entry) {
        val files = LinkedHashSet<String>()
        for (pad in entry.kit.pads) {
            files += pad.sampleFile
            pad.velocityLayers.forEach { files += it.sampleFile }
        }
        val index = HashMap<String, Int>()
        val frames = HashMap<String, Long>()
        synchronized(this) {
            if (!open) return
            NativePads.beginBank(handle)
        }
        for (file in files) {
            val snip = runCatching { WavReader.read(File(entry.dir, file)) }.getOrNull() ?: continue
            val i = synchronized(this) {
                if (!open) return
                NativePads.addSample(handle, snip.samples, snip.channels, snip.sampleRate)
            }
            index[file] = i
            frames[file] = snip.frameCount.toLong()
        }
        synchronized(this) {
            if (!open) return
            NativePads.commitBank(handle)
            sampleIndex = index
            framesOf = frames
            hits.clear()
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
    fun loadSnips(snips: List<Snip>): List<Int> {
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
    }
}
