package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Renders the CALABASH round-1 Kotlin clips under testkit/calabash-audition/kotlin/ (gitignored), so
 * `prototype/calabash/compare_kotlin.py` can measure the port against the approved round-0 prototype pad by pad:
 * every pad of the four kits at the three zones and four takes (`<kit>/<pad>-<zone>-t<take>.wav`, zone s, m, h),
 * every voice at its defaults soft and hard (`voices/<voice>-<soft|hard>.wav`), and every pad hard from
 * [LAW_DRAWS] further seeds (`law/<kit>/<pad>-s<i>.wav`, through [Calabash.renderSeeded]) so the A/B can compare the
 * port's take law with the prototype's on more than four takes. The clips are the engine's own
 * peak match, not levelled again. Render times go to `timings.csv` (ms, one machine, four threads, warm after the
 * first few). Run via `./gradlew :synth:generateCalabashAudition`.
 */
object CalabashAuditionGenerator {

    const val LAW_DRAWS = 16

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/calabash-audition/kotlin")
        root.mkdirs()
        val pool = Executors.newFixedThreadPool(4)
        val jobs = ArrayList<Future<String>>()
        fun job(dir: String, name: String, voice: CalabashVoice, macros: Map<String, Float>, velocity: Float, take: Int) {
            jobs += pool.submit<String> {
                val t0 = System.nanoTime()
                val snip = Calabash.render(voice, macros, velocity, take)
                val ms = (System.nanoTime() - t0) / 1e6
                val d = File(root, dir).also { it.mkdirs() }
                WavWriter.write(File(d, "$name.wav"), snip, WavWriter.BitDepth.PCM_16)
                "$dir/$name,${voice.name},${"%.1f".format(ms)}"
            }
        }
        val zones = listOf("s" to .35f, "m" to .65f, "h" to 1f)
        for (kit in CalabashKits.Kit.entries) {
            for ((i, pad) in CalabashKits.pads(kit).withIndex()) {
                val slot = "a%02d".format(i + 1)
                for ((z, v) in zones) for (t in 0 until CalabashKits.TAKES) job(kit.id, "$slot-$z-t$t", pad.voice, pad.macros, v, t)
            }
        }
        for (kit in CalabashKits.Kit.entries) {
            for ((i, pad) in CalabashKits.pads(kit).withIndex()) {
                val slot = "a%02d".format(i + 1)
                for (s in 0 until LAW_DRAWS) {
                    jobs += pool.submit<String> {
                        val t0 = System.nanoTime()
                        val snip = Calabash.renderSeeded(pad.voice, pad.macros, 1f, Dsp.seedFor("CALABASH-law", kit.id, slot, s))
                        val ms = (System.nanoTime() - t0) / 1e6
                        val d = File(root, "law/${kit.id}").also { it.mkdirs() }
                        WavWriter.write(File(d, "$slot-s$s.wav"), snip, WavWriter.BitDepth.PCM_16)
                        "law/${kit.id}/$slot-s$s,${pad.voice.name},${"%.1f".format(ms)}"
                    }
                }
            }
        }
        for (voice in CalabashVoice.entries) {
            job("voices", "${voice.name.lowercase()}-soft", voice, emptyMap(), .35f, 0)
            job("voices", "${voice.name.lowercase()}-hard", voice, emptyMap(), 1f, 0)
        }
        val rows = jobs.map { it.get() }
        pool.shutdown()
        File(root, "timings.csv").writeText("clip,voice,ms\n" + rows.joinToString("\n") + "\n")
        println("CALABASH: wrote ${rows.size} clips to ${root.path}")
    }
}
