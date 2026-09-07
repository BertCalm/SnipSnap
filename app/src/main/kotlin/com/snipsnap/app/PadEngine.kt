package com.snipsnap.app

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

    private var sampleIndex: Map<String, Int> = emptyMap()
    private var framesOf: Map<String, Long> = emptyMap()
    private val hits = HashMap<Int, Int>()

    @Synchronized
    fun start(): Boolean = open && NativePads.start(handle)

    @Synchronized
    fun needsRestart(): Boolean = open && NativePads.needsRestart(handle)

    /** The device refused an exclusive stream; the shared fallback is playing. */
    @Synchronized
    fun isShared(): Boolean = open && NativePads.isShared(handle)

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

    /** The loaded sample's length in frames, or null when it never loaded. */
    @Synchronized
    fun frames(sampleFile: String): Long? = framesOf[sampleFile]

    /**
     * Play [pad] at [velocity] as voice [voiceId]. False when the pad has
     * nothing loaded to play (or the command ring was full), so the caller
     * can tell its allocator the voice never sounded.
     */
    @Synchronized
    fun hit(pad: KitPad, velocity: Float, voiceId: Int): Boolean {
        if (!open) return false
        val n = hits[pad.slot] ?: 0
        hits[pad.slot] = n + 1
        val hit = PadHit.resolve(pad, velocity, n) { framesOf[it] } ?: return false
        val sample = sampleIndex[hit.sampleFile] ?: return false
        return NativePads.noteOn(
            handle, voiceId, sample,
            hit.startFrame, hit.endFrameExclusive, hit.gainLeft, hit.gainRight, hit.pitchRatio,
        )
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
        NativePads.stop(handle)
        NativePads.destroy(handle)
        handle = 0L
    }

    companion object {
        /** The engine's polyphony; the VoiceAllocator in PLAY is built at the same number. */
        const val MAX_VOICES = 32

        /** A choke is a short fade, not a cut: long enough to spare the click, short enough to read as a cut. */
        const val CHOKE_FADE_MS = 5f
        const val PANIC_FADE_MS = 20f
    }
}
