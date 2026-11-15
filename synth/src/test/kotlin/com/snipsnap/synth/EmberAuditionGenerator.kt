package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders the EMBER round-1 Kotlin clips under testkit/ember-audition/kotlin/ (gitignored): every voice at its
 * defaults, soft (0.35) and hard (1.0), and its four takes, so `prototype/ember/compare_kotlin.py` can measure
 * the port against the round-0e prototype voice by voice. The clips are written at the engine's own peak match.
 * It also prints each voice's render time on this machine. Run via `./gradlew :synth:generateEmberAudition`.
 */
object EmberAuditionGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/ember-audition/kotlin")
        root.mkdirs()
        var count = 0
        val times = ArrayList<Pair<String, Double>>()
        fun write(section: String, id: String, voice: EmberVoice, velocity: Float, take: Int) {
            val dir = File(root, section).also { it.mkdirs() }
            val t0 = System.nanoTime()
            val snip = Ember.render(voice, emptyMap(), velocity, take)
            times.add(voice.name to (System.nanoTime() - t0) / 1e6)
            WavWriter.write(File(dir, "$id.wav"), snip, WavWriter.BitDepth.PCM_16)
            count++
        }
        for (v in EmberVoice.entries) {
            val tag = v.name.lowercase()
            write("hard", tag, v, 1f, 0)
            write("soft", tag, v, 0.35f, 0)
            for (t in 0..Ember.MAX_TAKE) write("takes", "$tag-take$t", v, 1f, t)
        }
        for (v in EmberVoice.entries) {
            val ms = times.filter { it.first == v.name }.map { it.second }.sorted()
            println("%-13s median %6.0f ms, slowest %6.0f ms".format(v.name, ms[ms.size / 2], ms.last()))
        }
        val all = times.map { it.second }.sorted()
        println("EMBER: wrote $count clips to ${root.path}; median render %.0f ms".format(all[all.size / 2]))
    }
}
