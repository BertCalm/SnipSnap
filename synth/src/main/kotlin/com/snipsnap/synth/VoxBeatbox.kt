package com.snipsnap.synth

import kotlin.math.exp
import kotlin.math.pow

/**
 * VOX BEATBOX — vocal percussion, a mouth doing a drum kit (VOX round 2,
 * docs/SYNTH_ROADMAP.md S11).
 *
 * What makes a beatboxer's snare a mouth and not a drum machine's noise:
 * the air goes through a vocal tract, so it has a vowel's colour; the
 * tongue and lips move during the hit, so that colour moves; breath
 * flutters, swells after the release and crackles with spit; a
 * constriction (lips, teeth, tongue) sets the hiss; many hits carry a
 * trace of voice; and a beatboxer eats the mic, so the close-mic
 * proximity effect fattens everything under 250 Hz and the hit is pushed
 * into it. The audition's first try was filtered noise and "sounded
 * basically like the hat and snare in THUMP"; the second added the mouth
 * and was "better"; of three takes on it (real, punchy, big) the pick was
 * BIG, built in here.
 *
 * HIT snaps across [Hit]: KICK, three snares (PF, PSH, K), three hats (TS,
 * T, TSS open) and RIM, a tongue click off the palate.
 */
internal object VoxBeatbox {

    enum class Hit { KICK, PF, PSH, K, TS, T, TSS, RIM }

    fun hitFor(hit: Float): Hit = Hit.entries[Math.round(hit.coerceIn(0f, 1f) * (Hit.entries.size - 1))]

    /** The pitch KICK's hum sits on at TUNE's middle; TUNE moves every hit by the same semitones. */
    const val KICK_HZ = 60f

    /** How each hit is said: the mouth's vowel from and to, its constriction, voice, burst and lengths. */
    private class Say(
        val vowelFrom: Float, val vowelTo: Float, val move: Float,       // the tract: A..U, over this many seconds
        val tract: Float,                                                // noise through the tract
        val hissHz: Float, val hissQ: Float, val hiss: Float,            // the constriction: s ~7k, sh ~2.7k, f broad
        val hissToHz: Float = hissHz,                                    // the constriction moving (an open hat closing)
        val burstHz: Float, val burstQ: Float, val burstTau: Float, val burst: Float,
        val voice: Float = 0f, val voiceHz: Float = 150f, val voiceTau: Float = 0.05f,
        val t60Lo: Float, val t60Hi: Float,
        val flutter: Float = 0.45f,
    )

    private val SAY = mapOf(
        // "Pf": the lips pop, then air through them (labiodental, broad and soft), the mouth opening U toward A, an "uh" under it.
        Hit.PF to Say(1f, 0.05f, 0.08f, tract = 1.2f, hissHz = 4000f, hissQ = 0.5f, hiss = 0.4f,
            burstHz = 900f, burstQ = 0.8f, burstTau = 0.004f, burst = 1.2f, voice = 0.5f, voiceHz = 140f, voiceTau = 0.06f, t60Lo = 0.1f, t60Hi = 0.4f, flutter = 0.6f),
        // "Psh": lips pop into "sh", the tongue bunched behind the ridge, lips rounded O relaxing toward E.
        Hit.PSH to Say(0.9f, 0.2f, 0.12f, tract = 1.1f, hissHz = 2700f, hissQ = 1.6f, hiss = 0.8f, hissToHz = 3600f,
            burstHz = 900f, burstQ = 0.8f, burstTau = 0.004f, burst = 1f, voice = 0.45f, voiceHz = 130f, voiceTau = 0.06f, t60Lo = 0.15f, t60Hi = 0.55f, flutter = 0.6f),
        // "K": the back of the tongue releases, a raspy "kch" through an open A.
        Hit.K to Say(0.05f, 0.4f, 0.06f, tract = 1.2f, hissHz = 3000f, hissQ = 1.2f, hiss = 0.5f, hissToHz = 4200f,
            burstHz = 2000f, burstQ = 1.5f, burstTau = 0.006f, burst = 1.4f, voice = 0.2f, voiceHz = 160f, voiceTau = 0.03f, t60Lo = 0.06f, t60Hi = 0.25f, flutter = 0.7f),
        // "Ts": the tongue tip clicks off the ridge into "s", tongue high (I) settling.
        Hit.TS to Say(0.5f, 0.3f, 0.06f, tract = 0.45f, hissHz = 7800f, hissQ = 3f, hiss = 1.1f, hissToHz = 6600f,
            burstHz = 4500f, burstQ = 1.2f, burstTau = 0.002f, burst = 1f, t60Lo = 0.04f, t60Hi = 0.18f, flutter = 0.65f),
        // "T": the tongue-tip release and a breath of aspiration.
        Hit.T to Say(0.5f, 0.35f, 0.03f, tract = 0.6f, hissHz = 6000f, hissQ = 1.5f, hiss = 0.5f,
            burstHz = 4200f, burstQ = 1f, burstTau = 0.003f, burst = 1.3f, t60Lo = 0.02f, t60Hi = 0.08f, flutter = 0.6f),
        // "Tsss": an open hat, the "s" held and the tongue slowly settling (the hiss drifting down).
        Hit.TSS to Say(0.5f, 0.3f, 0.25f, tract = 0.4f, hissHz = 8500f, hissQ = 3f, hiss = 1.1f, hissToHz = 6200f,
            burstHz = 4500f, burstQ = 1.2f, burstTau = 0.002f, burst = 0.9f, t60Lo = 0.2f, t60Hi = 0.9f, flutter = 0.7f),
    )

    /** BIG, the audition's pick: tails this much longer, the mouth moving this much further, the voice this much louder and longer. */
    private const val BIG_LENGTH = 1.3f
    private const val BIG_REACH = 1.5f
    private const val BIG_VOICE = 2.2f
    private const val BIG_VOICE_LENGTH = 1.8f
    private const val CHEST = 0.8f            // the voice drops into the chest

    /** Spit: a crackle on this fraction of samples, at this level. */
    private const val CRACKLE_ODDS = 0.0015f
    private const val CRACKLE = 2.2f

    /** The close mic on the snares and hats: this much low shelf at 250 Hz, then pushed (tanh drive) and punched. */
    private const val PROXIMITY_DB = 11f
    private const val DRIVE = 1.8f
    private const val PUNCH = 0.6f

    internal fun synthesize(hit: Hit, pitch: Float, decay: Float, scale: Float, rate: Int): FloatArray = when (hit) {
        Hit.KICK -> kick(pitch, decay, scale, rate)
        Hit.RIM -> rim(pitch, decay, scale, rate)
        else -> say(SAY.getValue(hit), pitch, decay, scale, rate)
    }

    /** After decimation: the snares and hats go through the close mic; every hit is levelled to the same peak. */
    internal fun finish(out: FloatArray, hit: Hit) {
        if (hit != Hit.KICK && hit != Hit.RIM) {
            val proximity = Dsp.Biquad().apply { lowShelf(250f, PROXIMITY_DB) }
            for (i in out.indices) out[i] = proximity.process(out[i])
            Dsp.normalize(out, 0.95f)
            for (i in out.indices) out[i] = kotlin.math.tanh(DRIVE * out[i]) / kotlin.math.tanh(DRIVE)
            Punch.apply(out, PUNCH)
        }
        Dsp.normalize(out, 0.95f)
        Dsp.fadeTail(out)
    }

    /** The mouth: breath through a moving vocal tract and a constriction, a burst, spit, a trace of voice. */
    private fun say(s: Say, pitch: Float, decay: Float, scale: Float, rate: Int): FloatArray {
        val t60 = Dsp.expMap(decay, s.t60Lo, s.t60Hi) * BIG_LENGTH
        val voiceLevel = s.voice * BIG_VOICE
        val voiceHz = s.voiceHz * CHEST
        val voiceTau = s.voiceTau * BIG_VOICE_LENGTH
        val vowelTo = (s.vowelFrom + (s.vowelTo - s.vowelFrom) * BIG_REACH).coerceIn(0f, 1f)
        val out = FloatArray(((t60 * 1.2f + 0.02f) * rate).toInt())
        val noise = Dsp.Noise(53)
        val flutterNoise = Dsp.Noise(59)
        val crackle = Dsp.Noise(61)
        val jitterNoise = Dsp.Noise(67)
        val flutterLp = Dsp.OnePole(rate)
        val warm = Dsp.OnePole(rate)
        val jitter = Dsp.OnePole(rate)
        val tract = Array(5) { Dsp.Biquad() }
        val tractVoice = Array(5) { Dsp.Biquad() }
        val hiss = Dsp.Biquad()
        val burst = Dsp.Biquad().apply { bandpass(s.burstHz * scale, s.burstQ, rate) }
        val gains = floatArrayOf(1f, 0.7f, 0.5f, 0.4f, 0.3f)
        val widths = floatArrayOf(150f, 200f, 300f, 400f, 500f) // noise through the tract rings wider than a voice
        fun setTract(vowel: Float) {
            val f = Vox.formantsAt(vowel)
            for (k in 0 until 5) {
                val hz = ((if (k < 3) f[k] else floatArrayOf(3300f, 4200f)[k - 3]) * scale).coerceAtMost(rate * 0.45f)
                tract[k].bandpass(hz, (hz / widths[k]).coerceAtLeast(1.5f), rate)
                tractVoice[k].bandpass(hz, (hz / (widths[k] * 0.5f)).coerceAtLeast(2f), rate)
            }
        }
        setTract(s.vowelFrom)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / rate
            // The mouth never holds perfectly still.
            val wobble = (jitter.lp(jitterNoise.next(), 20f) * 8f).coerceIn(-1f, 1f) * 0.06f
            if (i % 32 == 0) {
                val x = (t / s.move).coerceIn(0f, 1f)
                setTract((s.vowelFrom + (vowelTo - s.vowelFrom) * x * x * (3f - 2f * x) + wobble).coerceIn(0f, 1f))
                val hz = (s.hissHz + (s.hissToHz - s.hissHz) * (t / t60).coerceIn(0f, 1f)) * pitch.pow(0.5f)
                hiss.bandpass(hz.coerceAtMost(rate * 0.45f), s.hissQ, rate)
            }
            // Breath flutters, is warmer than white noise, and swells after the release.
            val flutter = 1f + s.flutter * (flutterLp.lp(flutterNoise.next(), 90f) * 6f).coerceIn(-1f, 1f)
            val white = noise.next()
            val n = (0.55f * white + 1.6f * warm.lp(white, 1800f)) * flutter
            val swell = 1f + 0.8f * (t / 0.015f) * exp(1f - t / 0.015f)
            val e = minOf(t / 0.002f, 1f) * Dsp.envAt(t, t60) * swell
            var tr = 0f
            for (k in 0 until 5) tr += gains[k] * tract[k].process(n)
            var y = s.tract * tr * e
            y += s.hiss * 3f * hiss.process(n) * e
            y += s.burst * 3f * burst.process(noise.next()) * minOf(t / 0.001f, 1f) * exp(-t / s.burstTau)
            val c = crackle.next()
            if (kotlin.math.abs(c) > 1f - CRACKLE_ODDS) y += CRACKLE * c * e
            if (voiceLevel > 0f) {
                phase += voiceHz * pitch / rate
                val g = Vox.glottal(phase)
                var v = 0f
                for (k in 0 until 5) v += gains[k] * tractVoice[k].process(g)
                y += voiceLevel * 4f * v * minOf(t / 0.004f, 1f) * exp(-t / voiceTau)
            }
            out[i] = y
        }
        return out
    }

    /** "Boom": lip pop, then a hum dropping into a dark U through a big throat. */
    private fun kick(pitch: Float, decay: Float, scale: Float, rate: Int): FloatArray {
        val t60 = Dsp.expMap(decay, 0.15f, 0.7f)
        val out = FloatArray(((t60 * 1.2f + 0.02f) * rate).toInt())
        val noise = Dsp.Noise(41)
        val burst = Dsp.Biquad().apply { bandpass(400f * scale, 0.8f, rate) }
        val body = Array(3) { Dsp.Biquad() }
        val f = floatArrayOf(300f, 600f, 2400f)
        for (k in 0 until 3) body[k].bandpass((f[k] * scale).coerceAtMost(rate * 0.45f), f[k] * scale / 60f, rate)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val e = minOf(t / 0.002f, 1f) * Dsp.envAt(t, t60)
            var y = 3f * burst.process(noise.next()) * minOf(t / 0.001f, 1f) * exp(-t / 0.008f)
            phase += KICK_HZ * pitch * (1f + 1.5f * exp(-t / 0.025f)) / rate
            val src = Vox.glottal(phase)
            var v = 0f
            for (k in 0 until 3) v += floatArrayOf(1f, 0.4f, 0.1f)[k] * body[k].process(src)
            y += 2.5f * v
            out[i] = y * e
        }
        return out
    }

    /** A tongue click off the palate: a hollow "tock", the mouth cavity ringing briefly. */
    private fun rim(pitch: Float, decay: Float, scale: Float, rate: Int): FloatArray {
        val t60 = Dsp.expMap(decay, 0.03f, 0.12f)
        val out = FloatArray(((t60 * 1.2f + 0.02f) * rate).toInt())
        val noise = Dsp.Noise(47)
        val burst = Dsp.Biquad().apply { bandpass(2600f * scale, 2f, rate) }
        val cavity = Dsp.Biquad().apply { bandpass(1300f * pitch * scale, 25f, rate) }
        val cavity2 = Dsp.Biquad().apply { bandpass(2300f * pitch * scale, 20f, rate) }
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val n = noise.next() * minOf(t / 0.0005f, 1f) * exp(-t / 0.0015f)
            out[i] = (2f * burst.process(n) + 25f * cavity.process(n) + 12f * cavity2.process(n)) * Dsp.envAt(t, t60)
        }
        return out
    }
}
