package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders the SERVO Kotlin clips under testkit/servo-audition/kotlin/ (gitignored): every voice at
 * its defaults, soft (0.35) and hard (1.0), and its four takes, so `prototype/servo/compare_kotlin.py` can
 * measure the port against the round-0 prototype voice by voice. The clips are written at the
 * engine's own peak match, not levelled again, so the comparison sees what the engine renders.
 * Run via `./gradlew :synth:generateClutterAudition`.
 */
object ServoAuditionGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/servo-audition/kotlin")
        root.mkdirs()
        var count = 0
        fun write(section: String, id: String, voice: ServoVoice, velocity: Float, take: Int) {
            val dir = File(root, section).also { it.mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), Servo.render(voice, emptyMap(), velocity, take), WavWriter.BitDepth.PCM_16)
            count++
        }
        for (v in ServoVoice.entries) {
            val tag = v.name.lowercase()
            write("hard", tag, v, 1f, 0)
            write("soft", tag, v, 0.35f, 0)
            for (t in 0..Servo.MAX_TAKE) write("takes", "$tag-take$t", v, 1f, t)
        }
        println("SERVO: wrote $count clips to ${root.path}")
    }
}
