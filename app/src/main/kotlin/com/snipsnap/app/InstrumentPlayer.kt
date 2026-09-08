package com.snipsnap.app

import android.content.Context
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.InstrumentEngine
import com.snipsnap.shell.KeyHit
import java.io.File

/**
 * The KEYS screen's voice on the native engine: the same `NativePads`
 * the pads play through, a keygroup being a looping voice with the
 * instrument's release as its fade. What a key press *means* is resolved
 * on the JVM by `KeyHit` (the zone, the loop, the speed from the root,
 * the gain), the map `:shell`'s tested [InstrumentEngine] defines; the
 * engine only ever hears "this sample, looping back to there, at this
 * gain and speed".
 *
 * Polyphony is the instrument's own [InstrumentEngine.MAX_VOICES], the
 * oldest stolen, kept here rather than in the engine (which can hold
 * more) so a released tail never counts against a fresh note.
 *
 * Any thread but the audio thread; calls are serialised. [open] reads
 * WAVs and belongs on an IO dispatcher. [close] is idempotent.
 */
class InstrumentPlayer(context: Context) {

    private var handle: Long = NativePads.create(deviceSampleRate(context))
    private val open get() = handle != 0L
    private var running = false

    private var instrument: InstrumentStore.Instrument? = null
    private var sampleIndex: Map<String, Int> = emptyMap()
    private var framesOf: Map<String, Long> = emptyMap()

    /** Sounding voices by id, in the order they started - the oldest first. */
    private val voices = LinkedHashMap<Int, Int>() // voice id -> note
    private var nextVoice = 1

    /**
     * Load the instrument the sidecar describes: every zone's WAV read
     * once (a file that will not read leaves its zone silent) and handed
     * to the engine as one bank. Blocking disk IO: call from IO.
     */
    fun open(sidecar: File, instrument: InstrumentStore.Instrument) {
        val dir = sidecar.parentFile ?: File(".")
        val index = HashMap<String, Int>()
        val frames = HashMap<String, Long>()
        synchronized(this) {
            if (!open) return
            if (!running) running = NativePads.start(handle)
            NativePads.beginBank(handle)
        }
        for (zone in instrument.zones) {
            if (zone.sample in index) continue
            val snip = runCatching { WavReader.read(File(dir, zone.sample)) }.getOrNull() ?: continue
            val i = synchronized(this) {
                if (!open) return
                NativePads.addSample(handle, snip.samples, snip.channels, snip.sampleRate)
            }
            index[zone.sample] = i
            frames[zone.sample] = snip.frameCount.toLong()
        }
        synchronized(this) {
            if (!open) return
            NativePads.commitBank(handle)
            this.instrument = instrument
            sampleIndex = index
            framesOf = frames
            voices.clear()
        }
    }

    /** Start [note] at [velocity] 0..1; false when no zone covers it, nothing loaded, or no stream. */
    @Synchronized
    fun noteOn(note: Int, velocity: Float = 1f): Boolean {
        if (!open) return false
        // No stream - a failed open, or a route change that closed it -
        // and the next key tries again (KEYS has no frame loop to do it
        // sooner); a refusal now is not a refusal for good.
        if (!running || NativePads.needsRestart(handle)) running = NativePads.start(handle)
        if (!running) return false
        reap()
        val inst = instrument ?: return false
        val hit = KeyHit.resolve(inst, note, velocity.coerceIn(0f, 1f)) { framesOf[it] } ?: return false
        val sample = sampleIndex[hit.sampleFile] ?: return false
        while (voices.size >= InstrumentEngine.MAX_VOICES) {
            val oldest = voices.keys.first()
            NativePads.stopVoice(handle, oldest, PadEngine.CHOKE_FADE_MS)
            voices.remove(oldest)
        }
        val id = nextVoice++
        val queued = NativePads.noteOn(
            handle, id, sample,
            0L, hit.frames, hit.loopStartFrame, hit.gain, hit.gain, hit.pitchRatio,
            reverse = false,
        )
        if (queued) voices[id] = note
        return queued
    }

    /** Let [note] go: its voices fade over the instrument's release. */
    @Synchronized
    fun noteOff(note: Int) {
        if (!open) return
        val release = instrument?.let { KeyHit.releaseMs(it) } ?: 1f
        for (id in voices.filterValues { it == note }.keys.toList()) {
            NativePads.stopVoice(handle, id, release)
            voices.remove(id)
        }
    }

    @Synchronized
    fun allOff() {
        if (!open) return
        NativePads.allOff(handle, instrument?.let { KeyHit.releaseMs(it) } ?: 1f)
        voices.clear()
    }

    /** Voices the engine reports ended (an unlooped zone playing out) leave the count. */
    private fun reap() {
        NativePads.drainEnded(handle)?.forEach { voices.remove(it) }
    }

    @Synchronized
    fun close() {
        if (!open) return
        running = false
        NativePads.stop(handle)
        NativePads.destroy(handle)
        handle = 0L
    }
}
