package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs

/**
 * Renders the GLINT depth pass (D1) audition set under
 * testkit/glint-d1-audition/ (gitignored) — WAVs only, flat, no listening
 * page: a later step reads the directory and builds the page from what it
 * finds there. Run via `./gradlew :synth:generateGlintD1Audition`.
 *
 * Six sections answer the depth pass's own questions rather than repeating
 * Phase 1's broad survey:
 *  A. the three voices' real defaults
 *  B. does BOTTLE's second formant ring or burn off (the headline question)
 *  C. is BODY audible at all, and what interval does the PEAK clamp produce
 *  D. BLOOM now that its depth is free of its rate
 *  E. the velocity fix — SNAP_FLOOR broke the byte-identical soft/hard pair
 *  F. the harmonic staircase at the default FOLLOW (0.8, not 1)
 *
 * See docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md and
 * `.superpowers/sdd/2026-09-26-glint-depth-d1/`.
 */
object GlintD1AuditionGenerator {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_LEVEL = 0.03f

    /** All clips hold FOLLOW 1 and TUNE 0.5 unless a section overrides them. */
    private val BASE = mapOf("TUNE" to 0.5f, "FOLLOW" to 1f)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/glint-d1-audition")
        root.mkdirs()
        var count = 0

        fun write(voice: GlintVoice, name: String, macros: Map<String, Float>) {
            val snip = Glint.render(voice, macros)
            val leveled = level(snip)
            WavWriter.write(File(root, "$name.wav"), leveled, WavWriter.BitDepth.PCM_16)
            count++
            val loudness = Loudness.of(leveled)
            var peak = 0f
            for (v in leveled.samples) peak = maxOf(peak, abs(v))
            println("$name: frames=${leveled.frameCount} loudness=$loudness peak=$peak")
        }

        fun writeRendered(name: String, snip: Snip) {
            val leveled = level(snip)
            WavWriter.write(File(root, "$name.wav"), leveled, WavWriter.BitDepth.PCM_16)
            count++
            val loudness = Loudness.of(leveled)
            var peak = 0f
            for (v in leveled.samples) peak = maxOf(peak, abs(v))
            println("$name: frames=${leveled.frameCount} loudness=$loudness peak=$peak")
        }

        // A. THE DEFAULTS — each voice at its real defaults, no overrides.
        write(GlintVoice.REED, "a01_reed_default", emptyMap())
        write(GlintVoice.BOTTLE, "a02_bottle_default", emptyMap())
        write(GlintVoice.KAZOO, "a03_kazoo_default", emptyMap())

        // B. DOES BOTTLE'S SECOND FORMANT RING OR BURN OFF? All at DECAY 0.9
        // so the tail is long enough to judge.
        write(GlintVoice.BOTTLE, "b04_bottle_body0", BASE + mapOf("DECAY" to 0.9f, "BODY" to 0f, "BLOOM" to 0f))
        write(GlintVoice.BOTTLE, "b05_bottle_body05", BASE + mapOf("DECAY" to 0.9f, "BODY" to 0.5f, "BLOOM" to 0f))
        write(GlintVoice.BOTTLE, "b06_bottle_body1", BASE + mapOf("DECAY" to 0.9f, "BODY" to 1f, "BLOOM" to 0f))
        write(GlintVoice.REED, "b07_reed_body1", BASE + mapOf("DECAY" to 0.9f, "BODY" to 1f, "BLOOM" to 0f))
        write(GlintVoice.KAZOO, "b08_kazoo_body1", BASE + mapOf("DECAY" to 0.9f, "BODY" to 1f, "BLOOM" to 0f))
        write(GlintVoice.BOTTLE, "b09_bottle_body1_short", BASE + mapOf("DECAY" to 0.25f, "BODY" to 1f, "BLOOM" to 0f))

        // C. IS BODY AUDIBLE AT ALL NOW? Default PEAK for the first six.
        write(GlintVoice.REED, "c10_reed_body0", BASE + mapOf("BODY" to 0f, "BLOOM" to 0f))
        write(GlintVoice.REED, "c11_reed_body1", BASE + mapOf("BODY" to 1f, "BLOOM" to 0f))
        write(GlintVoice.BOTTLE, "c12_bottle_body0", BASE + mapOf("BODY" to 0f, "BLOOM" to 0f))
        write(GlintVoice.BOTTLE, "c13_bottle_body1", BASE + mapOf("BODY" to 1f, "BLOOM" to 0f))
        write(GlintVoice.KAZOO, "c14_kazoo_body0", BASE + mapOf("BODY" to 0f, "BLOOM" to 0f))
        write(GlintVoice.KAZOO, "c15_kazoo_body1", BASE + mapOf("BODY" to 1f, "BLOOM" to 0f))

        // The clamp pair — REED only, BODY 1 both — an interval comparison,
        // not a loudness one: bodyRatio floors at K_MIN, so PEAK 0.45 (the
        // default) sits the body at 2*f0 while PEAK 0.90 sits it at k/5.6.
        println("--- section C: bodyRatio interval at the PEAK clamp ---")
        run {
            val tune = BASE.getValue("TUNE")
            val follow = BASE.getValue("FOLLOW")

            val macrosClamped = BASE + mapOf("BODY" to 1f, "BLOOM" to 0f, "PEAK" to 0.45f)
            val kClamped = Glint.ratioFor(GlintVoice.REED, tune, 0.45f, follow)
            val bodyRatioClamped = Glint.bodyRatio(kClamped)
            println("c16_reed_body1_clamped: PEAK=0.45 -> kBase=$kClamped bodyRatio=$bodyRatioClamped")
            write(GlintVoice.REED, "c16_reed_body1_clamped", macrosClamped)

            val macrosReleased = BASE + mapOf("BODY" to 1f, "BLOOM" to 0f, "PEAK" to 0.90f)
            val kReleased = Glint.ratioFor(GlintVoice.REED, tune, 0.90f, follow)
            val bodyRatioReleased = Glint.bodyRatio(kReleased)
            println("c17_reed_body1_released: PEAK=0.90 -> kBase=$kReleased bodyRatio=$bodyRatioReleased")
            write(GlintVoice.REED, "c17_reed_body1_released", macrosReleased)
        }

        // D. BLOOM, now that depth is free of rate. REED only, default PEAK.
        write(GlintVoice.REED, "d18_reed_bloom0", BASE + mapOf("BLOOM" to 0f))
        write(GlintVoice.REED, "d19_reed_bloom033", BASE + mapOf("BLOOM" to 0.33f))
        write(GlintVoice.REED, "d20_reed_bloom066", BASE + mapOf("BLOOM" to 0.66f))
        write(GlintVoice.REED, "d21_reed_bloom1", BASE + mapOf("BLOOM" to 1f))

        // E. THE VELOCITY FIX, BY EAR. Before SNAP_FLOOR, e22 and e23 were
        // byte-identical: PEAK 0.03's kBase used to snap flat onto 2 for
        // both velocity layers. Assert they now differ.
        println("--- section E: velocity fix regression check ---")
        run {
            val macrosSoft = BASE + mapOf("PEAK" to 0.03f, "BLOOM" to 0f)
            val patchSoft = GlintPatch("audition", GlintVoice.REED, macrosSoft)
            val e22 = Velocity.atVelocity(patchSoft, 0.2f)
            val e23 = Velocity.atVelocity(patchSoft, 1.0f)

            var maxDiff = 0f
            val n = minOf(e22.samples.size, e23.samples.size)
            for (i in 0 until n) {
                val d = abs(e22.samples[i] - e23.samples[i])
                if (d > maxDiff) maxDiff = d
            }
            if (e22.samples.size != e23.samples.size) {
                maxDiff = Float.MAX_VALUE
            }
            println("e22 vs e23 (PEAK 0.03, velocity 0.2 vs 1.0): maxAbsDiff=$maxDiff frameCounts=${e22.samples.size}/${e23.samples.size}")
            check(!e22.samples.contentEquals(e23.samples)) {
                "e22 and e23 are byte-identical — the SNAP_FLOOR velocity fix has regressed"
            }

            writeRendered("e22_reed_peak003_soft", e22)
            writeRendered("e23_reed_peak003_hard", e23)

            val macrosHardPeak = BASE + mapOf("PEAK" to 0.45f, "BLOOM" to 0f)
            val patchHardPeak = GlintPatch("audition", GlintVoice.REED, macrosHardPeak)
            val e24 = Velocity.atVelocity(patchHardPeak, 0.2f)
            val e25 = Velocity.atVelocity(patchHardPeak, 1.0f)
            writeRendered("e24_reed_peak045_soft", e24)
            writeRendered("e25_reed_peak045_hard", e25)
        }

        // F. THE HARMONIC STAIRCASE AT THE DEFAULT FOLLOW (0.8, not 1). REED,
        // PEAK 0.3, BLOOM 0, four ascending TUNE values. Print the resulting
        // k so the console output is the evidence that these land on (or
        // very near) distinct integers.
        println("--- section F: ratioFor(k) across TUNE at FOLLOW 0.8 ---")
        val fMacrosBase = mapOf("FOLLOW" to 0.8f, "PEAK" to 0.3f, "BLOOM" to 0f)
        val fTunes = listOf("f26_reed_tune030" to 0.30f, "f27_reed_tune040" to 0.40f, "f28_reed_tune050" to 0.50f, "f29_reed_tune060" to 0.60f)
        for ((name, tune) in fTunes) {
            val macros = fMacrosBase + mapOf("TUNE" to tune)
            val k = Glint.ratioFor(GlintVoice.REED, tune, 0.3f, 0.8f)
            // The unsnapped ratio, computed the same way ratioFor does
            // internally minus the final snapRatio() call — printed
            // alongside k so "k=5.0" four times in a row is legible as a
            // staircase step rather than a mystery.
            val reference = Glint.referenceHz(GlintVoice.REED)
            val f0 = Glint.frequencyFor(GlintVoice.REED, tune)
            val peakHzAtReference = Glint.ratioAtReference(0.3f) * reference
            val peakHz = Dsp.keyTrack(peakHzAtReference, f0, reference, 0.8f)
            val unsnapped = (peakHz / f0).coerceIn(Glint.K_MIN, Glint.K_MAX)
            println("$name: TUNE=$tune -> unsnapped=$unsnapped k=$k")
            write(GlintVoice.REED, name, macros)
        }

        println("wrote $count clips under ${root.absolutePath}")
    }

    /**
     * One loudness for every clip, for a fair listen: the measure is
     * [Loudness.of] — the RMS of the loudest 200 ms window, the same
     * measure `Dsp.levelTo` uses — not a whole-file RMS, so clip length
     * doesn't decide who sounds louder. A peak guard keeps the file in
     * range.
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
