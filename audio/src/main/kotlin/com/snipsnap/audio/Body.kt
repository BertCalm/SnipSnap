package com.snipsnap.audio

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * BODY — a bank of tuned resonators struck by the hit, tuned to the
 * kit's key. Modal synthesis in its plainest form: each mode is a
 * two-pole resonator (a sine that rings out at one frequency with one
 * decay), the hit is the mallet, and the modes are the key's chord
 * tones — root, fifth, third — over [OCTAVES] octaves from [LOW_MIDI],
 * the root loudest so a click through BODY rings at the root. DECAY is
 * the modes' T60; AMOUNT crossfades the dry hit into the ringing body.
 * Peak-matched to the hit; all measurement, no seed.
 *
 * With no key the body rings at the hit's own note when it has one,
 * else at C — never a refusal: a drum with a body is the point.
 */
object Body {

    const val DECAY_MIN = 0.05f
    const val DECAY_MAX = 4f
    const val DECAY_DEFAULT = 0.6f

    /** The lowest mode: C2 (65 Hz) — a body, not a sub. */
    const val LOW_MIDI = 36
    const val OCTAVES = 3

    /** Mode weights: the root leads, the fifth supports, the third colours. */
    private const val ROOT = 1f
    private const val FIFTH = 0.5f
    private const val THIRD = 0.3f

    /** Each octave up rings a little softer, as a real body does. */
    private const val OCTAVE_FALL = 0.7f

    private const val MAKEUP_MAX = 4f

    /** One mode of the body: where it rings and how loud it is struck. */
    data class Mode(val midi: Int, val weight: Float) {
        val name: String get() = Scales.nameOf(midi)
    }

    /**
     * The root the body rings at, as semitones above C: the key's own
     * root, else the hit's own pitch class when the detector is sure,
     * else C.
     */
    fun rootFor(snip: Snip, key: KeySpec?): Int {
        key?.let { return it.rootSemitone }
        val est = Pitch.detect(snip)
        if (est != null && est.confidence >= Tuner.MIN_CONFIDENCE) return KeyGuess.pitchClass(est.hz)
        return 0
    }

    /** The modes for [rootSemitone] in [scale]: chord tones over [OCTAVES] octaves from [LOW_MIDI], ascending. */
    fun modes(rootSemitone: Int, scale: Scale): List<Mode> {
        require(rootSemitone in 0..11) { "root is 0..11 semitones above C, got $rootSemitone" }
        val intervals = scale.intervals.toSet()
        val degrees = mutableListOf(0 to ROOT)
        if (7 in intervals) degrees.add(7 to FIFTH)
        when {
            4 in intervals && scale != Scale.CHROMATIC -> degrees.add(4 to THIRD)
            3 in intervals && scale != Scale.CHROMATIC -> degrees.add(3 to THIRD)
        }
        val lowRoot = LOW_MIDI + rootSemitone
        val out = mutableListOf<Mode>()
        for (octave in 0 until OCTAVES) {
            val fall = Math.pow(OCTAVE_FALL.toDouble(), octave.toDouble()).toFloat()
            for ((interval, weight) in degrees) out.add(Mode(lowRoot + 12 * octave + interval, weight * fall))
        }
        return out.sortedBy { it.midi }
    }

    /** [snip] struck against the body of [rootSemitone] in [scale]: [amount] dry→body, the modes ringing for [decaySec] (T60). */
    fun ring(snip: Snip, rootSemitone: Int, scale: Scale, amount: Float = 1f, decaySec: Float = DECAY_DEFAULT): Snip {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        require(decaySec in DECAY_MIN..DECAY_MAX) { "decay wants $DECAY_MIN..$DECAY_MAX s, got $decaySec" }
        require(snip.frameCount > 0) { "the source is empty" }
        if (amount <= 0f) return snip
        val rate = snip.sampleRate
        val channels = snip.channels
        val frames = snip.frameCount
        // The body rings past the hit: room for the modes to fall 60 dB.
        val outFrames = frames + (decaySec * rate).roundToInt()
        val wet = FloatArray(outFrames * channels)
        val r = exp(-6.9078 / (decaySec * rate)).toFloat()
        for (mode in modes(rootSemitone, scale)) {
            val hz = Scales.midiToHz(mode.midi)
            if (hz >= rate / 2f) continue
            val theta = 2.0 * Math.PI * hz / rate
            val a1 = (2.0 * r * cos(theta)).toFloat()
            val a2 = -(r * r)
            // (1 − r) keeps every mode's ring at about the same level whatever its decay.
            val gain = mode.weight * (1f - r)
            for (ch in 0 until channels) {
                var y1 = 0f
                var y2 = 0f
                for (i in 0 until outFrames) {
                    val x = if (i < frames) snip.samples[i * channels + ch] else 0f
                    val y = gain * x + a1 * y1 + a2 * y2
                    y2 = y1
                    y1 = y
                    wet[i * channels + ch] += y
                }
            }
        }
        val inPeak = snip.peak()
        var wetPeak = 0f
        for (v in wet) if (v.isFinite() && Math.abs(v) > wetPeak) wetPeak = Math.abs(v)
        // The body at the hit's own peak: a click is a poor mallet by
        // level and a fine one by spectrum, so the ring is brought up to
        // the hit before the crossfade, uncapped, like the room's wet.
        if (inPeak > 0f && wetPeak > 0f) {
            val makeup = inPeak / wetPeak
            for (i in wet.indices) wet[i] *= makeup
        }
        val out = FloatArray(outFrames * channels) { i ->
            val dry = if (i < snip.samples.size) snip.samples[i] else 0f
            dry * (1f - amount) + wet[i] * amount
        }
        val result = Snip(out, channels, rate)
        val outPeak = result.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val makeup = (inPeak / outPeak).coerceAtMost(MAKEUP_MAX)
            for (i in out.indices) out[i] *= makeup
        }
        return result
    }
}
