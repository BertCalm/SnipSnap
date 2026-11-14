package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders the CLUTTER round-1 Kotlin clips under testkit/clutter-audition/kotlin/ (gitignored): every object at
 * its defaults, soft (0.35) and hard (1.0), and its four takes, so `prototype/clutter/compare_kotlin.py` can
 * measure the port against the accepted round-0c prototype object by object. The clips are written at the
 * engine's own peak match, not levelled again, so the comparison sees what the engine renders.
 * Run via `./gradlew :synth:generateClutterAudition`.
 */
object ClutterAuditionGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/clutter-audition/kotlin")
        root.mkdirs()
        var count = 0
        fun write(section: String, id: String, voice: ClutterVoice, velocity: Float, take: Int) {
            val dir = File(root, section).also { it.mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), Clutter.render(voice, emptyMap(), velocity, take), WavWriter.BitDepth.PCM_16)
            count++
        }
        for (v in ClutterVoice.entries) {
            val tag = v.name.lowercase()
            write("hard", tag, v, 1f, 0)
            write("soft", tag, v, 0.35f, 0)
            for (t in 0..Clutter.MAX_TAKE) write("takes", "$tag-take$t", v, 1f, t)
        }
        println("CLUTTER: wrote $count clips to ${root.path}")
    }
}
