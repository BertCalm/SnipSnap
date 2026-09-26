package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs

/**
 * Renders the Phase 2 audition set of
 * docs/superpowers/specs/2026-09-25-pluck-depth-design.md under
 * testkit/pluck-audition/ (gitignored): BODY at 0, default and 1 for every
 * string voice, STRIKE and DAMP at their ends with BODY held at the
 * default, and the kit seam where the melodic kit hands from nylon to
 * kalimba - 16-bit clips at one loudness (see [level]), plus the listening
 * page copied from the test resources. Run via
 * `./gradlew :synth:generatePluckAudition`.
 *
 * The PLUCK-versus-TINES kalimba A/B was decided at the Phase 1 gate (TINES
 * won, recorded in the spec) and is no longer rendered; its clips stay on
 * the artifact.
 *
 * The folder is then published as the listening artifact the spec names.
 * The artifact keeps the `VOICE/00_shipped.wav` clips from the spike
 * publish — the pre-Phase-1 renders — because a republish keeps files it
 * is not handed; nothing here can render the old engine.
 */
object PluckAuditionGenerator {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_LEVEL = 0.03f

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/pluck-audition")
        root.mkdirs()
        var count = 0

        for (voice in PluckVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Pluck.defaults(voice)
            fun write(name: String, snip: Snip) {
                WavWriter.write(File(dir, "$name.wav"), level(snip), WavWriter.BitDepth.PCM_16)
                count++
            }
            write("p2_body_0", Pluck.render(voice, mapOf("BODY" to 0f)))
            write("p2_body_default", Pluck.render(voice))
            write("p2_body_1", Pluck.render(voice, mapOf("BODY" to 1f)))
            write("p2_body_default_strike_bridge", Pluck.render(voice, mapOf("STRIKE" to 0f)))
            write("p2_body_default_ring", Pluck.render(voice, mapOf("DAMP" to 0f)))
            write("p2_body_default_thud", Pluck.render(voice, mapOf("DAMP" to 1f)))
            println("${voice.name}: BODY default ${defaults.getValue("BODY")}")
        }

        // The kit seam: where the melodic kit hands from nylon to kalimba,
        // now across two engines. Pads 5-8 of SynthKits.melodic().
        val seam = File(root, "KIT_SEAM")
        val kit = SynthKits.melodic()
        for ((index, name) in listOf(4 to "a05_nylon_5", 5 to "a06_nylon_6", 6 to "a07_kalimba_1", 7 to "a08_kalimba_2")) {
            WavWriter.write(File(seam, "$name.wav"), level(kit[index]!!.snip), WavWriter.BitDepth.PCM_16)
            count++
        }

        val page = PluckAuditionGenerator::class.java.getResourceAsStream("/audition/pluck-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + index.html under ${root.absolutePath}")
    }

    /**
     * One loudness for every clip, for a fair A/B: the spike measured
     * shipped PLUCK as peak-limited, so loudness would decide the
     * comparison otherwise. The measure is [Loudness.of] - the RMS of the
     * loudest 200 ms window, the same measure `Dsp.levelTo` uses - not a
     * whole-file RMS: a whole-file measure divides a short thud's energy
     * over silence it doesn't have and a long ring's energy over tail it
     * does, so at equal loudest-moment level a longer clip reads quieter by
     * whole-file RMS and gets over-boosted here; clip length must not be
     * what decides the A/B. A peak guard keeps the file in range.
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
