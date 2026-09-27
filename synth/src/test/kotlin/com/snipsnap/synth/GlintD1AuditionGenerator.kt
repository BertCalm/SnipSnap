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
 *  F. the harmonic staircase — a formant parked in place (FOLLOW 0) against
 *     one that mostly tracks the note (FOLLOW 0.8, the shipped default)
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

        // F. THE HARMONIC STAIRCASE. REED, BLOOM 0, four ascending TUNE
        // values per group, chosen from a probe of ratioFor across TUNE
        // 0.0-1.0 (step 0.02) at FOLLOW 0 and FOLLOW 0.8, PEAK 0.2/0.3/0.45/
        // 0.6 — see the probe numbers below. The original spec for this
        // section (FOLLOW 0.8, PEAK 0.3, TUNE 0.30/0.40/0.50/0.60) was a bad
        // parameter choice, not an implementation bug: all four TUNE values
        // land on kBase=5.0 (unsnapped 5.205/5.028/4.913/4.801) — no step is
        // crossed, so the clips demonstrated nothing about the staircase.
        //
        // At FOLLOW 0 the formant is parked at an absolute Hz and does not
        // track the note, so k = peakHz / f0 falls fastest as TUNE rises —
        // this is where the staircase is most pronounced. Of the four
        // probed PEAK values, 0.45 reaches the widest span of *snapped*
        // integers (SNAP_FLOOR..SNAP_CEILING = 3..12) inside TUNE 0-1: kBase
        // runs from 12 (the ceiling, at TUNE 0.20) down to 4 (at TUNE 1.00).
        // Sampling every third integer (12, 9, 6, 4) gives four widely,
        // evenly spaced steps, each a full musical interval apart
        // (12/9=9/6=1.33, a fourth; 6/4=1.5, a fifth):
        //   TUNE 0.20 -> unsnapped 11.537 -> k=12
        //   TUNE 0.40 -> unsnapped  8.643 -> k=9
        //   TUNE 0.66 -> unsnapped  6.112 -> k=6
        //   TUNE 1.00 -> unsnapped  3.850 -> k=4
        //
        // At FOLLOW 0.8 (the shipped default) the formant mostly tracks the
        // note, so kBase moves far more slowly across the same TUNE range —
        // none of the four probed PEAK values crossed more than two snapped
        // boundaries (three distinct integers) end to end. A finer PEAK
        // search (0.50-0.58, step 0.01) around where kBase(TUNE=0) sits just
        // under SNAP_CEILING found PEAK 0.55 crosses three boundaries (four
        // distinct integers) across the full TUNE range:
        //   TUNE 0.00 -> unsnapped 11.935 -> k=12
        //   TUNE 0.30 -> unsnapped 11.007 -> k=11
        //   TUNE 0.60 -> unsnapped 10.152 -> k=10
        //   TUNE 0.90 -> unsnapped  9.256 -> k=9
        // This is the FOLLOW 0.8 staircase at its widest — a semitone-by-
        // semitone crawl next to FOLLOW 0's four-clip leap, which is the
        // contrast worth hearing. Both render.
        println("--- section F: ratioFor(k) across TUNE, FOLLOW 0 vs FOLLOW 0.8 ---")
        data class FGroup(val follow: Float, val peak: Float, val clips: List<Pair<String, Float>>)
        val fGroups = listOf(
            FGroup(
                0.0f, 0.45f,
                listOf(
                    "f26_reed_follow0_tune020" to 0.20f,
                    "f27_reed_follow0_tune040" to 0.40f,
                    "f28_reed_follow0_tune066" to 0.66f,
                    "f29_reed_follow0_tune100" to 1.00f,
                ),
            ),
            FGroup(
                0.8f, 0.55f,
                listOf(
                    "f30_reed_follow08_tune000" to 0.00f,
                    "f31_reed_follow08_tune030" to 0.30f,
                    "f32_reed_follow08_tune060" to 0.60f,
                    "f33_reed_follow08_tune090" to 0.90f,
                ),
            ),
        )
        for (group in fGroups) {
            println("--- FOLLOW=${group.follow} PEAK=${group.peak} ---")
            val ks = mutableListOf<Float>()
            for ((name, tune) in group.clips) {
                val macros = mapOf("FOLLOW" to group.follow, "PEAK" to group.peak, "BLOOM" to 0f, "TUNE" to tune)
                val k = Glint.ratioFor(GlintVoice.REED, tune, group.peak, group.follow)
                // The unsnapped ratio, computed the same way ratioFor does
                // internally minus the final snapRatio() call — printed
                // alongside k so the staircase step is legible rather than a
                // mystery, and a flattened run (like the original spec's)
                // would be visible in the console output too.
                val reference = Glint.referenceHz(GlintVoice.REED)
                val f0 = Glint.frequencyFor(GlintVoice.REED, tune)
                val peakHzAtReference = Glint.ratioAtReference(group.peak) * reference
                val peakHz = Dsp.keyTrack(peakHzAtReference, f0, reference, group.follow)
                val unsnapped = (peakHz / f0).coerceIn(Glint.K_MIN, Glint.K_MAX)
                println("$name: TUNE=$tune -> unsnapped=$unsnapped k=$k")
                ks.add(k)
                write(GlintVoice.REED, name, macros)
            }
            check(ks.toSet().size == 4) {
                "section F group FOLLOW=${group.follow} PEAK=${group.peak} did not land on four distinct k values: $ks"
            }
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
