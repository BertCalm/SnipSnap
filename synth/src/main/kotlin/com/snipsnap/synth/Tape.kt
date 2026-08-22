package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.sin

/**
 * TAPE — the effect this app was always going to need.
 *
 * A physics-lite cassette: WOBBLE is wow and flutter (a slow ~1.3 Hz pitch
 * drift plus a fast ~7.4 Hz shimmer, done as a modulated fractional delay —
 * exactly what a warped capstan does to time); DRIVE is hysteresis-flavored
 * saturation (tanh with a touch of even-harmonic asymmetry, the sound of
 * magnetic particles giving up); AGE is the head wearing down — high end
 * rolling off. Inspired by the ChowDSP AnalogTapeModel's physical modeling,
 * scaled to the one-knob-per-idea discipline of this rack.
 *
 * Same contract as every pass here: macros 0..1, peak-matched, identical on
 * captured and synthesized audio. All-zeros is close to transparent (a
 * constant few-ms delay is the only trace).
 */
object Tape {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("WOBBLE", 0.35f),  // wow + flutter depth
        MacroSpec("DRIVE", 0.3f),    // hysteresis-ish saturation
        MacroSpec("AGE", 0.4f),      // the head wearing down: HF loss
    )

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    private const val WOW_HZ = 1.3f
    private const val FLUTTER_HZ = 7.4f
    private const val BASE_DELAY_S = 0.005f

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val wobble = m.getValue("WOBBLE")
        val drive = m.getValue("DRIVE")
        val age = m.getValue("AGE")

        val rate = snip.sampleRate
        val baseDelay = BASE_DELAY_S * rate
        // Depths in fractions of the base delay: wow up to ~0.35%, flutter
        // up to ~0.08% of pitch — cassette territory, not broken-deck.
        val wowDepth = Dsp.lin(wobble, 0f, 0.55f) * baseDelay
        val flutterDepth = Dsp.lin(wobble, 0f, 0.12f) * baseDelay
        val headHz = Dsp.expMap(1f - age, 3_200f, 16_000f)

        var inPeak = 0f
        for (v in snip.samples) { val a = if (v < 0) -v else v; if (a > inPeak) inPeak = a }

        val frames = snip.frameCount
        val out = FloatArray(snip.samples.size)
        for (ch in 0 until snip.channels) {
            val lp = Dsp.OnePole(rate)
            for (f in 0 until frames) {
                val t = f.toFloat() / rate
                val sway = wowDepth * sin(2.0 * PI * WOW_HZ * t).toFloat() +
                    flutterDepth * sin(2.0 * PI * FLUTTER_HZ * t + 1.1).toFloat()
                val readPos = f - baseDelay + sway
                val i0 = kotlin.math.floor(readPos).toInt()
                val frac = readPos - i0
                val s0 = if (i0 in 0 until frames) snip.samples[i0 * snip.channels + ch] else 0f
                val s1 = if (i0 + 1 in 0 until frames) snip.samples[(i0 + 1) * snip.channels + ch] else 0f
                var s = s0 + (s1 - s0) * frac

                if (drive > 0.001f) {
                    // Hysteresis flavor: odd harmonics from the tanh, a
                    // whisper of even from the asymmetry.
                    s = Dsp.drive(s + 0.06f * drive * s * s, drive * 0.8f)
                }
                out[f * snip.channels + ch] = lp.lp(s, headHz)
            }
        }

        var outPeak = 0f
        for (v in out) { val a = if (v < 0) -v else v; if (a > outPeak) outPeak = a }
        if (outPeak > 1e-9f && inPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
