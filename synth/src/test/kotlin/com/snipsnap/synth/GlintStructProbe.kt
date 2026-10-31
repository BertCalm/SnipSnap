package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * THROWAWAY STRUCTURAL PROBE — not committed, testkit/ is gitignored, and
 * Glint.kt is never touched. GLINT was reviewed by a council of synth
 * designers who converged on two structural diagnoses after hearing it
 * described as "bleep", "8 bit instrument", "boing", "blip", "still
 * jawharpy":
 *
 *   1. There is only one source of harmonic content in the whole engine -
 *      the window's edge. BODY mixes the bare window back in, which is the
 *      SAME harmonic series again at baseband, not a second voice.
 *   2. BLOOM's depth and rate are inversely coupled (`Dsp.expMap(bloom,
 *      BLOOM_SLOW_T60, BLOOM_FAST_T60)`), so a big sweep is always a fast
 *      one — a wide, unhurried sweep is unreachable.
 *
 * Five variants, each adding one missing ingredient on top of a shared
 * baseline (V0), so their proposer can be identified by ear:
 *
 *   V0  BASELINE               - the shipped engine, BLOOM's sweep t60
 *                                 forced to 0.45s at BLOOM 1 (the slow
 *                                 setting already preferred).
 *   V1  SECOND FORMANT (Tomita) - BODY's mean-removed-window mix replaced
 *                                 by a second windowed burst ~2.5 octaves
 *                                 below the main one, its own envelope.
 *   V2  TWO CLOCKS (Buchla)     - the carrier's own phase wraps and
 *                                 restarts 3x inside every outer cycle.
 *   V3  RADIO DIAL (Kakehashi)  - PEAK hard-steps through snapped
 *                                 harmonics, no glide, BLOOM off.
 *   V4  LOUDNESS-DRIVEN (Vangelis) - k(t) is driven by the amp envelope
 *                                 directly; no independent bloom timer.
 *
 * Every variant is REED and BOTTLE only, at TUNE 0.5, FOLLOW 1, DECAY 0.85,
 * PEAK 0.45 unless the variant overrides one of those. Each is a *local*
 * copy of `Glint.synthesize`'s loop, modified in place - `Glint.kt` itself
 * is read-only for this probe, which is the whole point: these are
 * variants the shipped engine cannot produce.
 *
 * Run via `./gradlew :synth:generateGlintStruct`.
 */
object GlintStructProbe {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_LEVEL = 0.03f

    /** V0's fix for BLOOM's coupling: the sweep's t60 at full BLOOM, forced
     *  slow instead of `Glint.BLOOM_FAST_T60` (0.06s). About 7x slower -
     *  the change the owner said they liked. Carried into every variant
     *  below ("everything else as V0") except V3, which turns BLOOM off. */
    private const val BLOOM_SLOW_FIX_T60 = 0.45f

    private val HELD = mapOf("TUNE" to 0.5f, "FOLLOW" to 1f, "DECAY" to 0.85f, "PEAK" to 0.45f)

    /** Every variant is REED and BOTTLE only. */
    private val VOICES = listOf(GlintVoice.REED, GlintVoice.BOTTLE)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/glint-struct")
        root.mkdirs()
        var count = 0

        fun write(voice: GlintVoice, name: String, raw: FloatArray) {
            val snip = finish(raw)
            val leveled = level(snip)
            WavWriter.write(File(root, "$name.wav"), leveled, WavWriter.BitDepth.PCM_16)
            count++
            val loudness = Loudness.of(leveled)
            var peak = 0f
            for (v in leveled.samples) peak = maxOf(peak, abs(v))
            val durationS = leveled.frameCount.toFloat() / leveled.sampleRate
            val problem = when {
                leveled.samples.any { it.isNaN() } -> " *** NaN ***"
                peak <= 1e-6f -> " *** SILENT ***"
                peak > 0.999f -> " *** CLIPPED ***"
                else -> ""
            }
            println(
                "$name: voice=$voice frames=${leveled.frameCount} duration=${"%.3f".format(durationS)}s " +
                    "loudness=$loudness peak=$peak$problem"
            )
            check(problem.isEmpty()) { "$name rendered broken:$problem" }
        }

        val renderRate = Dsp.RATE * Dsp.OVERSAMPLE

        for (voice in VOICES) {
            write(voice, "v0_${voice.name.lowercase()}", synthV0(voice, HELD + mapOf("BLOOM" to 1f), renderRate))
        }
        for (voice in VOICES) {
            write(voice, "v1_${voice.name.lowercase()}", synthV1(voice, HELD, renderRate))
        }
        for (voice in VOICES) {
            write(voice, "v2_${voice.name.lowercase()}", synthV2(voice, HELD, renderRate))
        }
        for (voice in VOICES) {
            write(voice, "v3_${voice.name.lowercase()}", synthV3(voice, HELD, renderRate))
        }
        for (voice in VOICES) {
            write(voice, "v4_${voice.name.lowercase()}", synthV4(voice, HELD, renderRate))
        }

        println("wrote $count clips under ${root.absolutePath}")
    }

    // ---------- shared bits, copied out of Glint.kt rather than reused ----------

    /** V0's fix applied to the shipped expMap shape: only the fast end moves. */
    private fun bloomT60For(bloom: Float): Float = Dsp.expMap(bloom, Glint.BLOOM_SLOW_T60, BLOOM_SLOW_FIX_T60)

    private fun kBaseFor(voice: GlintVoice, m: Map<String, Float>): Float =
        Glint.ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))

    // ---------- V0 — BASELINE ----------

    private fun synthV0(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = Glint.defaults(voice) + macros
        val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)

        val kBase = kBaseFor(voice, m)
        val bloom = m.getValue("BLOOM")
        val bloomAmount = Dsp.lin(bloom, 0f, Glint.BLOOM_MAX)
        val bloomT60 = bloomT60For(bloom)

        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val bodyMix = m.getValue("BODY")
        val bodyT60 = t60 * Glint.BODY_DECAY_RATIO
        val mean = Glint.windowMean(voice)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = Glint.windowAt(voice, phase)
            val k = (kBase * (1f + bloomAmount * Dsp.envAt(t, bloomT60))).coerceIn(Glint.K_MIN, Glint.K_MAX)
            val burst = w * sin(2.0 * PI * k * phase).toFloat()
            val body = bodyMix * Dsp.envAt(t, bodyT60) * (w - mean)
            out[i] = amp.at(t) * (burst + body)
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
    }

    // ---------- V1 — BODY AS A SECOND FORMANT (Tomita) ----------

    private fun synthV1(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = Glint.defaults(voice) + macros
        val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)

        val kBase = kBaseFor(voice, m)
        val bloom = m.getValue("BLOOM")
        val bloomAmount = Dsp.lin(bloom, 0f, Glint.BLOOM_MAX)
        val bloomT60 = bloomT60For(bloom)

        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        // The second formant, roughly 2.5 octaves below the main one
        // (k/5.6, 2^2.5 ~= 5.66), floored at K_MIN so a low PEAK setting
        // can't push it below the audible-peak threshold Glint itself
        // enforces. Its own, slower decay (0.8x the amp t60) is a SINGLE
        // envelope — unlike the shipped BODY term, it is not also
        // multiplied by `amp.at(t)` a second time.
        val k2 = (kBase / 5.6f).coerceAtLeast(Glint.K_MIN)
        val formant2T60 = t60 * 0.8f
        val amp2 = Dsp.Env(attackSeconds = 0.002f, decay2T60 = formant2T60)
        val mix2 = 0.7f

        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = Glint.windowAt(voice, phase)
            val k = (kBase * (1f + bloomAmount * Dsp.envAt(t, bloomT60))).coerceIn(Glint.K_MIN, Glint.K_MAX)
            val burst = w * sin(2.0 * PI * k * phase).toFloat()
            // Same window, same phase, a different ratio - a genuinely
            // second source of harmonic content instead of the same
            // series folded back in at baseband.
            val burst2 = w * sin(2.0 * PI * k2 * phase).toFloat()
            out[i] = amp.at(t) * burst + mix2 * amp2.at(t) * burst2
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
    }

    // ---------- V2 — TWO CLOCKS (Buchla) ----------

    private const val SUB_CYCLES = 3

    private fun synthV2(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = Glint.defaults(voice) + macros
        val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)

        val kBase = kBaseFor(voice, m)
        val bloom = m.getValue("BLOOM")
        val bloomAmount = Dsp.lin(bloom, 0f, Glint.BLOOM_MAX)
        val bloomT60 = bloomT60For(bloom)

        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val bodyMix = m.getValue("BODY")
        val bodyT60 = t60 * Glint.BODY_DECAY_RATIO
        val mean = Glint.windowMean(voice)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = Glint.windowAt(voice, phase)
            val k = (kBase * (1f + bloomAmount * Dsp.envAt(t, bloomT60))).coerceIn(Glint.K_MIN, Glint.K_MAX)
            // The outer window still wraps once per f0 cycle (w above is
            // unchanged); the carrier gets its own clock, wrapping and
            // restarting SUB_CYCLES times inside that same outer cycle.
            // Because k/SUB_CYCLES is generally not an integer, the
            // carrier's value - not just its slope - jumps at each of
            // those inner restarts: a lattice of edges instead of one.
            val subPhase = (phase * SUB_CYCLES) % 1f
            val burst = w * sin(2.0 * PI * k * subPhase).toFloat()
            val body = bodyMix * Dsp.envAt(t, bodyT60) * (w - mean)
            out[i] = amp.at(t) * (burst + body)
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
    }

    // ---------- V3 — THE RADIO DIAL (Kakehashi) ----------

    /** The snapped harmonics PEAK steps through, in order, ~150ms apart. Deliberately toylike - do not smooth. */
    private val RADIO_STEPS = floatArrayOf(2f, 3f, 4f, 5f, 6f, 8f, 10f, 12f)
    private const val RADIO_STEP_SECONDS = 0.15f

    private fun synthV3(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        // DECAY at maximum so all eight steps fit; BLOOM off - the steps
        // ARE the movement, nothing should slide independently of them.
        val m = Glint.defaults(voice) + macros + mapOf("DECAY" to 1f, "BLOOM" to 0f)
        val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val stepsSpan = RADIO_STEPS.size * RADIO_STEP_SECONDS
        val frames = maxOf((t60 * 1.35f * rate).toInt(), (stepsSpan * 1.1f * rate).toInt()).coerceAtLeast(64)

        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val bodyMix = m.getValue("BODY")
        val bodyT60 = t60 * Glint.BODY_DECAY_RATIO
        val mean = Glint.windowMean(voice)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = Glint.windowAt(voice, phase)
            // Hard step, no glide: floor(t / 150ms), held at the last
            // step once the sequence runs out.
            val stepIndex = (t / RADIO_STEP_SECONDS).toInt().coerceIn(0, RADIO_STEPS.lastIndex)
            val k = RADIO_STEPS[stepIndex]
            val burst = w * sin(2.0 * PI * k * phase).toFloat()
            val body = bodyMix * Dsp.envAt(t, bodyT60) * (w - mean)
            out[i] = amp.at(t) * (burst + body)
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
    }

    // ---------- V4 — COLOUR FOLLOWS LOUDNESS (Vangelis) ----------

    private const val FOLLOW_DEPTH = 3f

    private fun synthV4(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = Glint.defaults(voice) + macros
        val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)

        val kBase = kBaseFor(voice, m)
        // No independent bloom timer at all: the formant's fall is driven
        // by the SAME envelope instance that shapes loudness.
        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val bodyMix = m.getValue("BODY")
        val bodyT60 = t60 * Glint.BODY_DECAY_RATIO
        val mean = Glint.windowMean(voice)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = Glint.windowAt(voice, phase)
            val ampNow = amp.at(t)
            val k = (kBase * (1f + FOLLOW_DEPTH * ampNow)).coerceIn(Glint.K_MIN, Glint.K_MAX)
            val burst = w * sin(2.0 * PI * k * phase).toFloat()
            val body = bodyMix * Dsp.envAt(t, bodyT60) * (w - mean)
            out[i] = ampNow * (burst + body)
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
    }

    // ---------- finishing (matches Glint.render exactly) ----------

    /** Same oversample-and-decimate finishing `Glint.render` uses, so these are comparable to the shipped engine. */
    private fun finish(raw: FloatArray): Snip {
        val out = Dsp.decimate(raw, Dsp.RATE)
        Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }

    /**
     * One loudness for every clip, for a fair listen: the measure is
     * [Loudness.of] - the RMS of the loudest 200 ms window, the same
     * measure `Dsp.levelTo` uses - not a whole-file RMS, so clip length
     * doesn't decide who sounds louder. A peak guard keeps the file in
     * range. Copied verbatim from GlintAuditionGenerator.
     */
    private fun level(snip: Snip): Snip {
        val out = snip.samples.copyOf()
        val loudness = Loudness.of(Snip(out, channels = 1, sampleRate = snip.sampleRate))
        var g = AUDITION_LEVEL / loudness.coerceAtLeast(1e-9f)
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        if (peak * g > 0.99f) g = 0.99f / peak
        for (i in out.indices) out[i] *= g
        return Snip(out, channels = 1, sampleRate = snip.sampleRate)
    }
}
