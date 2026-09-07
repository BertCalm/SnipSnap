package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import kotlin.math.pow

/**
 * The phone's keygroup voice: a small sample-playback engine for the
 * instruments the shop makes (MAKE INSTRUMENT, MAKE PAD, `keys`, `pad`),
 * pure JVM so it is tested here and merely wrapped in an `AudioTrack` on
 * the device.
 *
 * A note picks the zone that covers it, reads the zone's sample at the
 * ratio between the note and the zone's root (times the sample rate
 * ratio to the device), linearly interpolated; a zone with a loop start
 * wraps from the sample's end back to it and sustains while held; a note
 * that lets go fades over the instrument's release; a zone without a loop
 * plays to its end and stops. [MAX_VOICES] at once, the oldest stolen.
 * Output is interleaved stereo, the same mono in both ears, summed at
 * [VOICE_LEVEL] and soft-clipped so eight notes never crack.
 */
class InstrumentEngine(
    private val instrument: Loaded,
    private val outRate: Int,
    private val maxVoices: Int = MAX_VOICES,
) {

    /** An instrument with its samples in memory: every zone's mono floats at [sampleRate]. */
    class Loaded(
        val instrument: InstrumentStore.Instrument,
        val sampleRate: Int,
        /** Per zone, in the instrument's zone order. */
        val samples: List<FloatArray>,
    ) {
        init {
            require(samples.size == instrument.zones.size) { "one sample per zone" }
            require(sampleRate > 0)
        }
    }

    private class Voice(
        val note: Int,
        val zoneIndex: Int,
        val sample: FloatArray,
        val loopStart: Int,
        val step: Double,
        val gain: Float,
        val born: Long,
    ) {
        var pos = 0.0
        var releasing = false
        var level = 1f
        var dead = false
    }

    private val voices = ArrayList<Voice>()
    private var clock = 0L

    /** Notes sounding now, released ones included until they fade. */
    val activeNotes: List<Int> get() = voices.filter { !it.dead }.map { it.note }

    /** Start [note] at [velocity] 0..1; silently nothing when no zone covers it. Returns whether a voice started. */
    fun noteOn(note: Int, velocity: Float = 1f): Boolean {
        val zone = instrument.instrument.zoneFor(note) ?: return false
        val zoneIndex = instrument.instrument.zones.indexOf(zone)
        val sample = instrument.samples[zoneIndex]
        if (sample.isEmpty()) return false
        val semis = note - zone.rootNote
        val step = 2.0.pow(semis / 12.0) * instrument.sampleRate / outRate
        val loopStart = zone.loopStartFrame.toInt().coerceIn(0, sample.size - 1)
        if (voices.count { !it.dead } >= maxVoices) {
            // Steal the oldest.
            voices.filter { !it.dead }.minByOrNull { it.born }?.dead = true
        }
        voices.add(Voice(note, zoneIndex, sample, if (zone.loopStartFrame > 0) loopStart else -1, step, velocity.coerceIn(0f, 1f) * VOICE_LEVEL, clock++))
        return true
    }

    /** Let [note] go: its voices fade over the instrument's release. */
    fun noteOff(note: Int) {
        for (v in voices) if (v.note == note && !v.dead) v.releasing = true
    }

    fun allOff() {
        for (v in voices) v.releasing = true
    }

    /**
     * Render [frames] frames of interleaved stereo into [out] (size ≥
     * frames × 2), replacing what was there.
     */
    fun render(out: FloatArray, frames: Int) {
        require(out.size >= frames * 2) { "out holds ${out.size / 2} frames, asked $frames" }
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        val releaseFrames = (instrument.instrument.release * outRate).coerceAtLeast(1f)
        val releaseStep = 1f / releaseFrames
        for (v in voices) {
            if (v.dead) continue
            val s = v.sample
            val end = s.size
            for (i in 0 until frames) {
                if (v.pos >= end - 1) {
                    if (v.loopStart >= 0 && end - 1 > v.loopStart) {
                        v.pos -= (end - 1 - v.loopStart)
                    } else {
                        v.dead = true
                        break
                    }
                }
                val i0 = v.pos.toInt()
                val frac = (v.pos - i0).toFloat()
                val x = s[i0] + (s[i0 + 1] - s[i0]) * frac
                if (v.releasing) {
                    v.level -= releaseStep
                    if (v.level <= 0f) {
                        v.dead = true
                        break
                    }
                }
                val y = x * v.gain * v.level
                out[i * 2] += y
                out[i * 2 + 1] += y
                v.pos += v.step
            }
        }
        voices.removeAll { it.dead }
        // Soft clip: eight notes at once never crack.
        for (i in 0 until frames * 2) {
            val y = out[i]
            if (y > 0.9f || y < -0.9f) out[i] = softClip(y)
        }
    }

    private fun softClip(y: Float): Float {
        val a = if (y < 0f) -y else y
        val c = 0.9f + (1f - 0.9f) * (1f - 1f / (1f + (a - 0.9f) * 10f))
        return if (y < 0f) -c else c
    }

    companion object {
        const val MAX_VOICES = 8

        /** Per-voice level: eight full-scale notes sum inside the clip. */
        const val VOICE_LEVEL = 0.35f
    }
}
