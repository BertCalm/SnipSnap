package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders SILK Phase 4's own listening pass
 * (docs/superpowers/plans/2026-09-29-silk-phase-4.md) under
 * testkit/silk-audition/ (gitignored): every voice at its defaults, the
 * shared shape parameters (BODY/DAMP/PICK/STRIKE/TUNE/INFLECT) every voice
 * carries, and each phase's own named gate extremes (OUD's COURSE/SLIDE,
 * GUZHENG's STIFF/PRESS, SANTUR's COURSE/WASH, SHAMISEN's SAWARI/SLAP) - no
 * voice's gate from phases 1b/2/3 was actually closed before now, and this
 * is the first page that does it. 16-bit clips at one loudness (see
 * [AuditionLevel]), plus the listening page copied from the test resources.
 * Run via `./gradlew :synth:generateSilkAudition`.
 *
 * No candidate presets render here - the plan's own point is that presets
 * authored before this verdict comes back would be exactly the "authored
 * blind" case the spec warns about.
 */
object SilkAuditionGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/silk-audition")
        root.mkdirs()
        var count = 0

        for (voice in SilkVoice.entries) {
            val dir = File(root, voice.name)
            fun write(name: String, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f) {
                val snip: Snip = Silk.render(voice, macros, velocity)
                WavWriter.write(File(dir, "$name.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
                count++
            }

            // The shared shape every voice carries.
            write("default")
            write("body_0", mapOf("BODY" to 0f))
            write("body_1", mapOf("BODY" to 1f))
            write("damp_0", mapOf("DAMP" to 0f))
            write("damp_1", mapOf("DAMP" to 1f))
            write("pick_0", mapOf("PICK" to 0f))
            write("pick_1", mapOf("PICK" to 1f))
            write("strike_bridge", mapOf("STRIKE" to 0f))
            write("strike_centre", mapOf("STRIKE" to 1f))
            write("tune_root", mapOf("TUNE" to 0f))
            write("tune_top", mapOf("TUNE" to 1f))
            write("inflect_low", mapOf("INFLECT" to 0f))
            write("inflect_high", mapOf("INFLECT" to 1f))

            // Each voice's own named gate extremes (spec, "Phasing and gates").
            when (voice) {
                SilkVoice.OUD -> {
                    write("course_0", mapOf("COURSE" to 0f))
                    write("course_1", mapOf("COURSE" to 1f))
                    write("slide_0", mapOf("SLIDE" to 0f))
                    write("slide_1", mapOf("SLIDE" to 1f))
                    // The risha tick (RISHA_GAIN) is only meant to be
                    // audible near the bridge - both together, since
                    // slide_1 alone at the default STRIKE may bury it.
                    write("slide_1_strike_bridge", mapOf("SLIDE" to 1f, "STRIKE" to 0f))
                }
                SilkVoice.GUZHENG -> {
                    write("stiff_0", mapOf("STIFF" to 0f))
                    write("stiff_1", mapOf("STIFF" to 1f))
                    write("press_0", mapOf("PRESS" to 0f))
                    write("press_1", mapOf("PRESS" to 1f))
                }
                SilkVoice.SANTUR -> {
                    write("course_0", mapOf("COURSE" to 0f))
                    write("course_1", mapOf("COURSE" to 1f))
                    write("wash_0", mapOf("WASH" to 0f))
                    write("wash_1", mapOf("WASH" to 1f))
                }
                SilkVoice.SHAMISEN -> {
                    write("sawari_0", mapOf("SAWARI" to 0f))
                    write("sawari_1", mapOf("SAWARI" to 1f))
                    write("slap_0", mapOf("SLAP" to 0f))
                    write("slap_1", mapOf("SLAP" to 1f))
                }
            }
            println("${voice.name}: defaults ${Silk.defaults(voice)}")
        }

        val page = SilkAuditionGenerator::class.java.getResourceAsStream("/audition/silk-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + index.html under ${root.absolutePath}")
    }
}
