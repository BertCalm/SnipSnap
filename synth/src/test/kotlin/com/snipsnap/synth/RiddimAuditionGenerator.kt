package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders RIDDIM's Kotlin clips for the A/B against the approved Python prototype: every voice at its defaults
 * soft (0.4) and hard (1.0), takes 0 and 1, named as `prototype/riddim/riddim_proto.py` names its own
 * (`voices/<VOICE>_v040_t0.wav`), so `prototype/riddim/check_voices.py` and `compare_kotlin.py` read both trees the
 * same way. Render times go to `timings.csv` (ms, one machine, one thread). Run via
 * `./gradlew :synth:generateRiddimAudition`.
 */
object RiddimAuditionGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/riddim-audition/kotlin")
        val voices = File(root, "voices").also { it.mkdirs() }
        val timings = StringBuilder("clip,ms\n")
        for (voice in RiddimVoice.entries) {
            for (velocity in listOf(0.4f, 1f)) {
                for (take in 0..1) {
                    val t0 = System.nanoTime()
                    val snip = Riddim.render(voice, Riddim.defaults(voice), velocity, take)
                    val ms = (System.nanoTime() - t0) / 1e6
                    val name = "${voice.name}_v%03d_t$take".format((velocity * 100).toInt())
                    WavWriter.write(File(voices, "$name.wav"), snip, WavWriter.BitDepth.PCM_16)
                    timings.append("$name,${"%.1f".format(ms)}\n")
                }
            }
        }
        File(root, "timings.csv").writeText(timings.toString())
        println("wrote $root")
    }
}
