package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs

/**
 * THROWAWAY SPIKE — not committed, testkit/ is gitignored. Modelled on
 * GlintAuditionGenerator, but tests one hypothesis instead of auditioning
 * the whole engine: Phase 1 came back "sounds like a bleep" / "I don't get
 * a sense of movement", and the theory is that BODY and BLOOM finish before
 * the ear can register them, and that BLOOM's amount and speed are welded
 * together so "big and slow" is unreachable.
 *
 * Renders 11 flat clips under testkit/glint-probe/ (no voice subfolders).
 * Takes one argument, `shipped` or `slow`, selecting which subset to
 * render — that is how two different values of BLOOM_FAST_T60 (a `const
 * val` in Glint.kt) make it into one clip set: run `shipped` first against
 * the committed source, then hand-edit BLOOM_FAST_T60 to 0.45f, run `slow`,
 * then revert the edit. See the task write-up for the exact sequence.
 *
 * Run via `./gradlew :synth:generateGlintProbe -Pprobe=shipped` (then
 * `-Pprobe=slow`).
 */
object GlintProbeGenerator {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_LEVEL = 0.03f

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrNull(0) ?: error("pass 'shipped' or 'slow' as the first argument")
        val root = File(args.getOrNull(1) ?: "../testkit/glint-probe")
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
            val durationS = leveled.frameCount.toFloat() / leveled.sampleRate
            println(
                "$name: voice=$voice frames=${leveled.frameCount} duration=${"%.3f".format(durationS)}s " +
                    "loudness=$loudness peak=$peak"
            )
        }

        when (mode) {
            "shipped" -> {
                // 1. DOES LENGTH FIX THE BLIP? Same sound twice, only DECAY changes.
                write(GlintVoice.REED, "1_reed_short", emptyMap())
                write(GlintVoice.REED, "1_reed_long", mapOf("DECAY" to 0.9f))
                write(GlintVoice.BOTTLE, "1_bottle_short", emptyMap())
                write(GlintVoice.BOTTLE, "1_bottle_long", mapOf("DECAY" to 0.9f))
                write(GlintVoice.KAZOO, "1_kazoo_short", emptyMap())
                write(GlintVoice.KAZOO, "1_kazoo_long", mapOf("DECAY" to 0.9f))

                // 2. DOES BODY APPEAR WHEN THERE IS ROOM? Long DECAY throughout.
                write(GlintVoice.REED, "2_body_0", mapOf("DECAY" to 0.9f, "BODY" to 0f))
                write(GlintVoice.REED, "2_body_1", mapOf("DECAY" to 0.9f, "BODY" to 1f))

                // 3. DOES BLOOM MOVE WHEN IT IS SLOW? The fast (shipped) half of the pair.
                write(GlintVoice.REED, "3_bloom_fast", mapOf("DECAY" to 0.9f, "BLOOM" to 1f))
            }
            "slow" -> {
                // 3 (cont'd). Rendered under BLOOM_FAST_T60 = 0.45f, hand-edited in
                // Glint.kt for this run only, then reverted. Same macros as
                // 3_bloom_fast — only the constant differs.
                write(GlintVoice.REED, "3_bloom_slow", mapOf("DECAY" to 0.9f, "BLOOM" to 1f))
                write(GlintVoice.BOTTLE, "3_bloom_slow_bottle", mapOf("DECAY" to 0.9f, "BLOOM" to 1f))
            }
            else -> error("unknown mode '$mode' — pass 'shipped' or 'slow'")
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
