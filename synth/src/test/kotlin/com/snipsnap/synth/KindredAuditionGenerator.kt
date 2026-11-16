package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders KINDRED's round-K1 Kotlin clips for the A/B against the accepted Python renders
 * (`prototype/kindred/compare_kotlin.py`) and the listening page: every kit recipe at full velocity as take 0
 * (the reference strike, comparable to the prototype's take 0), at .3 and .6, and as takes 1 to 3.
 * Files are levelled by the engine (house loudness), 16-bit.
 */
object KindredAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "testkit/kindred-audition/kotlin")
        fun write(section: String, id: String, snip: Snip) {
            val dir = File(out, section).apply { mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), snip, WavWriter.BitDepth.PCM_16)
        }
        for (r in KindredRecipes.ALL) {
            val id = r.id.lowercase()
            write("pads", id, KindredPatch.of(r).render())
            for (v in listOf(0.3f, 0.6f)) write("velocity", "$id-${"%.1f".format(v)}", KindredPatch.of(r, velocity = v).render())
            for (t in 1..3) write("takes", "$id-take$t", KindredPatch.of(r, take = t).render())
        }
        // Render time, warm: every recipe once more, timed (the house "tens of ms" playability rule).
        val t0 = System.nanoTime()
        for (r in KindredRecipes.ALL) KindredPatch.of(r).render()
        val ms = (System.nanoTime() - t0) / 1e6 / KindredRecipes.ALL.size
        val slowest = KindredRecipes.ALL.maxBy { r -> val s = System.nanoTime(); KindredPatch.of(r).render(); System.nanoTime() - s }
        println("KINDRED clips under $out; warm render %.1f ms per pad on average; slowest ${slowest.id}".format(ms))
    }
}
