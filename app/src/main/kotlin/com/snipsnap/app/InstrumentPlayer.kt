package com.snipsnap.app

import android.content.Context
import com.snipsnap.app.ui.TAPE_LOAD_MAX_SEC
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.InstrumentEngine
import com.snipsnap.shell.KeyHit
import com.snipsnap.xpm.VoiceMode
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

    /** A sounding voice: its key, and the gain its zone and velocity gave it before [volume]. */
    private class Voice(val note: Int, val gain: Float)

    /** Sounding voices by id, in the order they started - the oldest first. */
    private val voices = LinkedHashMap<Int, Voice>()
    private var nextVoice = 1

    /** The player's master gain, 1 = the instrument as made; see [setVolume]. */
    private var volume = 1f

    /**
     * Load the instrument the sidecar describes: every zone's WAV read
     * once (a file that will not read leaves its zone silent) and handed
     * to the engine as one bank. Blocking disk IO: call from IO.
     */
    fun open(sidecar: File, instrument: InstrumentStore.Instrument) {
        val dir = sidecar.parentFile ?: File(".")
        load(instrument) { file ->
            runCatching { WavReader.readCapped(File(dir, file), TAPE_LOAD_MAX_SEC).snip }.getOrNull()
        }
    }

    /**
     * Load an offline-rendered preview without writing a temporary package.
     * Sample copying belongs on a worker dispatcher, just like [open]'s WAV reads.
     * Note-on still uses the same zone, loop and release map as a shelf instrument.
     */
    fun open(instrument: InstrumentStore.Instrument, samples: Map<String, Snip>) {
        load(instrument) { samples[it] }
    }

    private fun load(instrument: InstrumentStore.Instrument, sample: (String) -> Snip?) {
        val index = HashMap<String, Int>()
        val frames = HashMap<String, Long>()
        synchronized(this) {
            if (!open) return
            if (!running) running = NativePads.start(handle)
            NativePads.beginBank(handle)
        }
        for (file in instrument.sampleFiles) {
            // Load every velocity layer once; a missing soft sample must not silently play the hard one.
            val snip = sample(file) ?: continue
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
            this.instrument = instrument
            sampleIndex = index
            framesOf = frames
            voices.clear()
        }
    }

    /** Change a captured instrument's note-off fade without loading or rendering its samples again. */
    @Synchronized
    fun setReleaseSeconds(seconds: Float) {
        require(seconds.isFinite() && seconds in 0f..InstrumentStore.MAX_RELEASE_SECONDS)
        instrument = instrument?.copy(release = seconds)
    }

    /** Change how a captured instrument's keys share voices (see [VoiceMode]) without loading or rendering anything again. */
    @Synchronized
    fun setVoiceMode(mode: VoiceMode) {
        instrument = instrument?.copy(voiceMode = mode)
    }

    /**
     * Every voice's gain times [gain] (0..1, already through the screen's
     * volume law): new notes start at it, and held ones glide to it over a
     * fader's glide, so a knob turned under a chord moves the chord without
     * a step.
     */
    @Synchronized
    fun setVolume(gain: Float) {
        require(gain.isFinite() && gain in 0f..1f)
        volume = gain
        if (!open) return
        for ((id, v) in voices) NativePads.setVoiceGain(handle, id, v.gain * gain, v.gain * gain, PadEngine.FADER_GLIDE_MS)
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
        // LEAD and GLIDE are one voice: a new note takes every sounding one with it.
        // Released voices leave `voices` once noteOff ran, so a release tail is not choked by a new
        // LEAD/GLIDE note on the phone (the reference engine chokes it); a native choke is the owner's follow-up.
        if (inst.voiceMode.mono) {
            for (id in voices.keys.toList()) NativePads.stopVoice(handle, id, PadEngine.CHOKE_FADE_MS)
            voices.clear()
        }
        while (voices.size >= InstrumentEngine.MAX_VOICES) {
            val oldest = voices.keys.first()
            NativePads.stopVoice(handle, oldest, PadEngine.CHOKE_FADE_MS)
            voices.remove(oldest)
        }
        val id = nextVoice++
        val queued = NativePads.noteOn(
            handle, id, sample,
            0L, hit.frames, hit.loopStartFrame, hit.gain * volume, hit.gain * volume, hit.pitchRatio,
            reverse = false,
        )
        if (queued) voices[id] = Voice(note, hit.gain)
        return queued
    }

    /** Let [note] go: its voices fade over the instrument's release. */
    @Synchronized
    fun noteOff(note: Int) {
        if (!open) return
        val release = instrument?.let { KeyHit.releaseMs(it) } ?: 1f
        for (id in voices.filterValues { it.note == note }.keys.toList()) {
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
        for (id in NativePads.drainEnded(handle)) voices.remove(id)
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
