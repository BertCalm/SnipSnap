package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs

/**
 * Renders the GLINT Phase 1 audition set under testkit/glint-audition/
 * (gitignored): 32 clips across six listening sections — the three voices at
 * their defaults, PEAK's full travel, REED's harmonic walk, BODY and the
 * glass tail, BLOOM's behaviour at high PEAK, and BOTTLE's parked-versus-
 * tracking formant — plus the listening page copied from the test
 * resources. Run via `./gradlew :synth:generateGlintAudition`.
 *
 * GLINT emulates nothing (it is a synthesis technique, not an instrument
 * model), so the page's verdict vocabulary is KEEP / OK / DROP rather than
 * PLUCK's CLOSER / SAME / WORSE.
 *
 * Nobody has heard GLINT yet — this is the gate before Phase 2 and Phase 3
 * are built. See docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md.
 */
object GlintAuditionGenerator {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_LEVEL = 0.03f

    /** All clips hold FOLLOW 1 and TUNE 0.5 unless a section overrides them. */
    private val BASE = mapOf("TUNE" to 0.5f, "FOLLOW" to 1f)

    /**
     * This generator is a Phase 1 artifact, captured for three voices — the
     * class doc's "32 clips across six listening sections" is that
     * three-voice count. `GlintVoice` has since grown to six (D2); pinned
     * explicitly here rather than looping `GlintVoice.entries` so CICADA,
     * RATCHET and PLATE don't spill into a set this page was never built to
     * show. `GlintD1AuditionGenerator` is D1's own three-voice tool, built
     * for the depth pass's own questions rather than Phase 1's; a listening
     * set for the D2 voices, if one is wanted, is a new generator, not a
     * loop added here.
     */
    private val PHASE_1_VOICES = listOf(GlintVoice.REED, GlintVoice.BOTTLE, GlintVoice.KAZOO)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/glint-audition")
        root.mkdirs()
        var count = 0

        fun write(voice: GlintVoice, name: String, macros: Map<String, Float>) {
            val dir = File(root, voice.name)
            val snip = Glint.render(voice, macros)
            val leveled = level(snip)
            WavWriter.write(File(dir, "$name.wav"), leveled, WavWriter.BitDepth.PCM_16)
            count++
            val loudness = Loudness.of(leveled)
            var peak = 0f
            for (v in leveled.samples) peak = maxOf(peak, abs(v))
            println("${voice.name}/$name: frames=${leveled.frameCount} loudness=$loudness peak=$peak")
        }

        // A. THE THREE VOICES — each voice at its real defaults, no overrides.
        for (voice in PHASE_1_VOICES) {
            write(voice, "a_default", emptyMap())
        }

        // B. PEAK, THE STAR KNOB — all three voices. BLOOM held at 0 so the
        // sweep doesn't confuse the reading.
        for (voice in PHASE_1_VOICES) {
            write(voice, "b_peak_low", BASE + mapOf("PEAK" to 0.05f, "BLOOM" to 0f))
            write(voice, "b_peak_default", BASE + mapOf("BLOOM" to 0f))
            write(voice, "b_peak_high", BASE + mapOf("PEAK" to 0.95f, "BLOOM" to 0f))
        }

        // C. THE HARMONIC WALK — REED only. PEAK values chosen so k snaps to
        // exact integers in the snapped zone (k <= SNAP_CEILING). Verified
        // below against Glint.ratioFor before rendering.
        val cTargets = listOf(
            "c_k2" to (2 to 0.0f),
            "c_k3" to (3 to 0.135f),
            "c_k5" to (5 to 0.306f),
            "c_k8" to (8 to 0.463f),
        )
        println("--- section C: verifying k lands on integers ---")
        for ((name, target) in cTargets) {
            val (expectedK, peak) = target
            val macros = BASE + mapOf("PEAK" to peak, "BLOOM" to 0f)
            val k = Glint.ratioFor(GlintVoice.REED, macros.getValue("TUNE"), peak, macros.getValue("FOLLOW"))
            println("$name: PEAK=$peak -> k=$k (expected $expectedK)")
            check(k == expectedK.toFloat()) { "$name: PEAK $peak produced k=$k, expected $expectedK" }
            write(GlintVoice.REED, name, macros)
        }

        // D. BODY AND THE GLASS TAIL — all three voices. BLOOM held at 0.
        for (voice in PHASE_1_VOICES) {
            write(voice, "d_body_0", BASE + mapOf("BODY" to 0f, "BLOOM" to 0f))
            write(voice, "d_body_1", BASE + mapOf("BODY" to 1f, "BLOOM" to 0f))
            write(voice, "d_glass", BASE + mapOf("BODY" to 0.9f, "DECAY" to 0.85f, "BLOOM" to 0f))
        }

        // E. BLOOM — REED only.
        write(GlintVoice.REED, "e_bloom_0", BASE + mapOf("BLOOM" to 0f))
        write(GlintVoice.REED, "e_bloom_1", BASE + mapOf("BLOOM" to 1f))
        write(GlintVoice.REED, "e_bloom_1_high_peak", BASE + mapOf("BLOOM" to 1f, "PEAK" to 0.9f))

        // F. FOLLOW — BOTTLE only. BLOOM held at 0, PEAK held at 0.5.
        write(GlintVoice.BOTTLE, "f_follow0_low", mapOf("TUNE" to 0.1f, "FOLLOW" to 0f, "PEAK" to 0.5f, "BLOOM" to 0f))
        write(GlintVoice.BOTTLE, "f_follow0_high", mapOf("TUNE" to 0.9f, "FOLLOW" to 0f, "PEAK" to 0.5f, "BLOOM" to 0f))
        write(GlintVoice.BOTTLE, "f_follow1_low", mapOf("TUNE" to 0.1f, "FOLLOW" to 1f, "PEAK" to 0.5f, "BLOOM" to 0f))
        write(GlintVoice.BOTTLE, "f_follow1_high", mapOf("TUNE" to 0.9f, "FOLLOW" to 1f, "PEAK" to 0.5f, "BLOOM" to 0f))

        val page = GlintAuditionGenerator::class.java.getResourceAsStream("/audition/glint-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + index.html under ${root.absolutePath}")
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
