package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore

/**
 * The keygroup voice, pure JVM: a small sample-playback engine for the
 * instruments the shop makes (MAKE INSTRUMENT, MAKE PAD, `keys`, `pad`).
 * The phone now plays keys through the native engine (`InstrumentPlayer`
 * over `NativePads`), but the *map* - which zone, what speed, which loop,
 * what release - is this engine's, lifted into [KeyHit] and tested
 * against both; this class stays the reference rendering. ORIGAMI's maker
 * uses it for a finite hold-and-release preview; KEYS itself uses the
 * native player.
 *
 * A note picks the zone that covers it, reads the zone's sample at the
 * ratio between the note and the zone's root (times the sample rate
 * ratio to the device), read between frames with a four-point cubic
 * (Catmull-Rom) as the phone's native voice reads; a zone with a loop start
 * loops the file's `[loopStart, end)` - the last frame is played and is
 * followed by the first of the loop - and sustains while held; a note
 * that lets go fades over the instrument's release; a zone without a loop
 * plays to its end and stops. [MAX_VOICES] at once, the oldest stolen.
 * Output is interleaved stereo, the same mono in both ears, summed at
 * [VOICE_LEVEL] and soft-clipped above 0.9 so eight notes never crack
 * here. The phone's native bus does not have this stage: it hard-clamps
 * at full scale (see [KeyHit]).
 */
class InstrumentEngine(
    private val instrument: Loaded,
    private val outRate: Int,
    private val maxVoices: Int = MAX_VOICES,
) {

    init {
        // A step is the sample's rate over this one: at 0 it is infinite and a loop's wrap never ends; below 0 the read walks
        // off the front of the sample.
        require(outRate > 0) { "output rate must be positive, got $outRate" }
    }

    /** An instrument with its samples in memory at [sampleRate]. */
    class Loaded(
        val instrument: InstrumentStore.Instrument,
        val sampleRate: Int,
        /** Per zone, in the instrument's zone order. */
        val samples: List<FloatArray>,
        /** All velocity samples by sidecar path. The legacy list remains the per-zone hardest samples. */
        val layerSamples: Map<String, FloatArray> = emptyMap(),
    ) {
        constructor(instrument: InstrumentStore.Instrument, sampleRate: Int, samplesByFile: Map<String, FloatArray>) :
            this(instrument, sampleRate, instrument.zones.map { samplesByFile[it.sample] ?: FloatArray(0) }, samplesByFile)

        init {
            require(samples.size == instrument.zones.size) { "one sample per zone" }
            require(sampleRate > 0)
        }

        fun sampleFor(file: String, zoneIndex: Int): FloatArray? = layerSamples[file]
            ?: samples[zoneIndex].takeIf { file == instrument.zones[zoneIndex].sample }
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
        val hit = KeyHit.resolve(instrument.instrument, note, velocity.coerceIn(0f, 1f)) { file ->
            instrument.sampleFor(file, zoneIndex)?.size?.toLong()
        } ?: return false
        val sample = instrument.sampleFor(hit.sampleFile, zoneIndex) ?: return false
        val step = hit.pitchRatio * instrument.sampleRate / outRate
        if (voices.count { !it.dead } >= maxVoices) {
            // Steal the oldest.
            voices.filter { !it.dead }.minByOrNull { it.born }?.dead = true
        }
        voices.add(Voice(note, zoneIndex, sample, hit.loopStartFrame.toInt(), step, hit.gain, clock++))
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
        val releaseFrames = (instrument.instrument.playableRelease * outRate).coerceAtLeast(1f)
        val releaseStep = 1f / releaseFrames
        for (v in voices) {
            if (v.dead) continue
            val s = v.sample
            val end = s.size
            val last = end - 1
            // The cubic's taps past the last frame: inside a loop (the same test as the wrap below) the loop's own
            // next frames, end -> loopStart and end + 1 -> loopStart + 1; otherwise the last frame, held.
            val wrap = if (v.loopStart >= 0 && end - 1 > v.loopStart) end - v.loopStart else 0
            for (i in 0 until frames) {
                if (v.loopStart >= 0 && end - 1 > v.loopStart) {
                    // The loop is the file's [loopStart, end), the MPC's own and the one a file that closes
                    // exactly (Keys.seamError) is cut for: the read wraps when it reaches `end`, so the loop's
                    // period is end - loopStart and the last frame is played. A fast note over a short loop
                    // can cross `end` more than once in a frame, so wrap until the read is inside.
                    val period = (end - v.loopStart).toDouble()
                    while (v.pos >= end) v.pos -= period
                } else if (v.pos >= end - 1) {
                    v.dead = true
                    break
                }
                val i0 = v.pos.toInt()
                val t = (v.pos - i0).toFloat()
                // A four-point cubic (Catmull-Rom) through the frames either side of the read, PadEngine's own.
                // Inside a loop the read can sit between the last frame and `end`, and the frames after the last
                // are the loop's first and second, which is what makes the seam a step the loop makes everywhere
                // else. The frame before is the file's, held at frame 0. At a whole-frame position the weights are
                // exactly (0, 1, 0, 0): the read is the sample itself.
                val im1 = if (i0 > 0) i0 - 1 else i0
                val i1 = if (i0 + 1 <= last) i0 + 1 else if (wrap > 0) i0 + 1 - wrap else last
                val i2 = if (i0 + 2 <= last) i0 + 2 else if (wrap > 0) i0 + 2 - wrap else last
                val t2 = t * t
                val t3 = t2 * t
                val wm1 = 0.5f * (2f * t2 - t3 - t)
                val w0 = 1f - 2.5f * t2 + 1.5f * t3
                val w1 = 0.5f * t + 2f * t2 - 1.5f * t3
                val w2 = 0.5f * (t3 - t2)
                val x = wm1 * s[im1] + w0 * s[i0] + w1 * s[i1] + w2 * s[i2]
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

        /**
         * Per-voice level: eight full-scale notes sum inside this engine's soft clip. [KeyHit]
         * hands the same gain to the phone's native bus, which hard-clamps at full scale instead.
         */
        const val VOICE_LEVEL = 0.35f
    }
}
