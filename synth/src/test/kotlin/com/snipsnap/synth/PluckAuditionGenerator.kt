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
 * string voice, BODY 1 again at the root note (where the body's modes sit
 * nearest the note), STRIKE and DAMP at their ends with BODY held at the
 * default, and the kit seam where the melodic kit hands from nylon to
 * kalimba - 16-bit clips at one loudness (see [level]), plus the listening
 * page copied from the test resources. Run via
 * `./gradlew :synth:generatePluckAudition`.
 *
 * The PLUCK-versus-TINES kalimba A/B was decided at the Phase 1 gate (TINES
 * won, recorded in the spec) and is no longer rendered; its clips stay on
 * the artifact.
 *
 * A p2c third-listen set renders on top of the above, for the gate's two
 * remaining open items after the banjo's body and the raised string
 * defaults landed: BANJO's brighter string (loop, pick band, bridge
 * formants, D4 default) pushed both ways from its new default, plus the
 * TINNY preset; and the kalimba tine's new thumbnail tick and longer
 * partials, at the kit's Kalimba 1 pad (TINES KALIMBA, semitone 3 = C4)
 * pushed for BRIGHT, DECAY and BUZZ. The earlier p2b follow-up set (the
 * raised string defaults at their usual note and the root for NYLON, KOTO
 * and HARP; BANJO across four notes; the kalimba pad five ways including an
 * octave up) is not re-rendered - the page no longer references those
 * clips except for `p2b_d4_default` and `p2b_c4_as_is`, kept as this
 * round's "last time" comparison, and the artifact keeps every file from
 * every earlier round untouched (a republish keeps files it is not
 * handed).
 *
 * A p2d fourth-listen set renders on top of that, for the gate's one
 * remaining open item: the kalimba tine's higher DECAY floor (a tine rings
 * a little even played softly) and its tick band-limited to where the ear
 * can still hear it past decimation. Rendered at the
 * kit's Kalimba 1 pad's C4 (the new default, the DECAY floor, the DECAY
 * ceiling, and brighter) and Kalimba 5's A4, the kit's highest kalimba
 * pad. BANJO's third-listen clips are kept as they are - the gate settled
 * its default there, and nothing about it moves in this round. The p2c
 * KALIMBA block above is no longer rendered; the page's "last time"
 * comparison instead keeps `p2c_c4_default`, referenced but not
 * regenerated, the same pattern p2b's `p2b_d4_default`/`p2b_c4_as_is`
 * already use.
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
            write("p2_body_1_root", Pluck.render(voice, mapOf("TUNE" to 0f, "BODY" to 1f)))
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

        // p2c third listen: BANJO's brighter string pushed both ways from
        // its new default, plus the TINNY preset; and the kit's Kalimba 1
        // pad through the tine's new tick and longer partials (this
        // round's brief, sections 1-2).
        val banjoDir = File(root, PluckVoice.BANJO.name)
        fun writeBanjo(name: String, macros: Map<String, Float>) {
            WavWriter.write(File(banjoDir, "$name.wav"), level(Pluck.render(PluckVoice.BANJO, macros)), WavWriter.BitDepth.PCM_16)
            count++
        }
        writeBanjo("p2c_d4_default", emptyMap())
        writeBanjo("p2c_d4_tin_more", mapOf("PICK" to 0.95f, "STRIKE" to 0.1f))
        writeBanjo("p2c_d4_tin_less", mapOf("PICK" to 0.5f, "STRIKE" to 0.45f))
        writeBanjo("p2c_d4_thud", mapOf("DAMP" to 1f))
        writeBanjo("p2c_g3_default", mapOf("TUNE" to 0f))
        writeBanjo("p2c_g4_default", mapOf("TUNE" to 0.5f))
        writeBanjo("p2c_preset_tinny", mapOf("TUNE" to 0.7f, "DAMP" to 0.6f, "PICK" to 0.95f, "STRIKE" to 0.15f, "DOUBLE" to 0.1f))

        // p2d fourth listen: the kalimba against last time, its DECAY floor
        // to ceiling, brighter, and the kit's highest kalimba pad. The kit's
        // Kalimba 1 pad is TINES KALIMBA at semitone 3 = C4, BUZZ at the
        // voice default unless stated; TUNE, BRIGHT and DECAY move here.
        val kalimbaDir = File(root, "KALIMBA")
        fun writeKalimba(name: String, macros: Map<String, Float>) {
            val full = mapOf(
                "TUNE" to 3f / Tines.KALIMBA_TUNE_SEMITONES.toFloat(),
                "BUZZ" to 0.15f, "DECAY" to 0.9f,
            ) + macros
            WavWriter.write(File(kalimbaDir, "$name.wav"), level(Tines.render(TinesVoice.KALIMBA, full)), WavWriter.BitDepth.PCM_16)
            count++
        }
        writeKalimba("p2d_c4_default", mapOf("BRIGHT" to 0.5f, "DECAY" to 0.9f))
        writeKalimba("p2d_c4_decay_0", mapOf("BRIGHT" to 0.5f, "DECAY" to 0.0f))
        writeKalimba("p2d_c4_decay_1", mapOf("BRIGHT" to 0.5f, "DECAY" to 1.0f))
        writeKalimba("p2d_c4_bright_75", mapOf("BRIGHT" to 0.75f, "DECAY" to 0.9f))
        writeKalimba(
            "p2d_a4_default",
            mapOf("TUNE" to 12f / Tines.KALIMBA_TUNE_SEMITONES.toFloat(), "BRIGHT" to 0.5f, "DECAY" to 0.9f),
        )

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
