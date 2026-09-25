package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Renders the Phase 1 audition set of
 * docs/superpowers/specs/2026-09-25-pluck-depth-design.md under
 * testkit/pluck-audition/ (gitignored): 16-bit clips at one RMS, plus the
 * listening page copied from the test resources. Run via
 * `./gradlew :synth:generatePluckAudition`.
 *
 * The folder is then published as the listening artifact the spec names.
 * The artifact keeps the `VOICE/00_shipped.wav` clips from the spike
 * publish — the pre-Phase-1 renders — because a republish keeps files it
 * is not handed; nothing here can render the old engine.
 */
object PluckAuditionGenerator {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_RMS = 0.03f

    /** The melodic kit's five kalimba notes as semitones above A3 (A07..A11 in SynthKits.melodic). */
    private val KIT_NOTES = listOf("C4" to 3, "D4" to 5, "E4" to 7, "G4" to 10, "A4" to 12)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/pluck-audition")
        root.mkdirs()
        var count = 0

        for (voice in listOf(PluckVoice.NYLON, PluckVoice.KOTO, PluckVoice.HARP)) {
            val dir = File(root, voice.name)
            val patch = PluckPatch("AUDITION", voice, Pluck.defaults(voice))
            fun write(name: String, snip: Snip) {
                WavWriter.write(File(dir, "$name.wav"), level(snip), WavWriter.BitDepth.PCM_16)
                count++
            }
            write("p1_default", Pluck.render(voice))
            write("p1_strike_bridge", Pluck.render(voice, mapOf("STRIKE" to 0f)))
            write("p1_strike_centre", Pluck.render(voice, mapOf("STRIKE" to 1f)))
            write("p1_ring", Pluck.render(voice, mapOf("DAMP" to 0f)))
            write("p1_thud", Pluck.render(voice, mapOf("DAMP" to 1f)))
            write("p1_soft", Velocity.atVelocity(patch, 0.3f))
            write("p1_hard", Velocity.atVelocity(patch, 1f))
        }

        val ab = File(root, "KALIMBA_AB")
        fun writeAb(name: String, snip: Snip) {
            WavWriter.write(File(ab, "$name.wav"), level(snip), WavWriter.BitDepth.PCM_16)
            count++
        }
        for ((note, semi) in KIT_NOTES) {
            // Same root (A3) and span (24) on both engines, so one macro value is the same note.
            val tune = semi / Pluck.TUNE_SEMITONES.toFloat()
            writeAb("pluck_$note", Pluck.render(PluckVoice.KALIMBA, mapOf("TUNE" to tune)))
            writeAb("tines_$note", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to tune)))
        }
        val a4 = 12 / Tines.KALIMBA_TUNE_SEMITONES.toFloat()
        writeAb("tines_buzz_0", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to a4, "BUZZ" to 0f)))
        writeAb("tines_buzz_1", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to a4, "BUZZ" to 1f)))
        writeAb("tines_bright_0", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to a4, "BRIGHT" to 0f)))
        writeAb("tines_bright_1", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to a4, "BRIGHT" to 1f)))

        val page = PluckAuditionGenerator::class.java.getResourceAsStream("/audition/pluck-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + index.html under ${root.absolutePath}")
    }

    /**
     * One RMS for every clip, for a fair A/B: the spike measured shipped
     * PLUCK as peak-limited, so loudness would decide the comparison
     * otherwise. A peak guard keeps the file in range.
     */
    private fun level(snip: Snip): Snip {
        val out = snip.samples.copyOf()
        var acc = 0.0
        for (v in out) acc += v.toDouble() * v
        val rms = sqrt(acc / out.size.coerceAtLeast(1)).toFloat()
        var g = AUDITION_RMS / rms.coerceAtLeast(1e-9f)
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        if (peak * g > 0.99f) g = 0.99f / peak
        for (i in out.indices) out[i] *= g
        return Snip(out, channels = 1, sampleRate = snip.sampleRate)
    }
}
