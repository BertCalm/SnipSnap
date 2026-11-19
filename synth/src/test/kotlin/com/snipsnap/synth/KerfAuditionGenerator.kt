package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * KERF round 1's listening pack: the four fitted kits rendered by the Kotlin port, clean and chopped, so the owner can
 * set them beside the Python renders heard on 2026-11-18. Run with `./gradlew :synth:generateKerfAudition`.
 */
object KerfAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "testkit/kerf-audition"); var count = 0; var ms = 0.0
        for ((kit, pads) in KerfBank.kits) for (p in pads) {
            val name = "Kit${kit}_%02d_%s".format(p.pad, p.drumClass)
            val t0 = System.nanoTime()
            val chop = KerfRender.chopped(p.patch, p.prev, p.next, p.bleed, p.slop, draw = p.draw)
            ms += (System.nanoTime() - t0) / 1e6
            write(File(out, "chopped/Kit $kit/$name.wav"), chop.samples)
            write(File(out, "clean/Kit $kit/$name.wav"), KerfRender.render(p.patch))
            count++
        }
        println("KERF audition: $count pads to ${out.absolutePath}; chopped render ${"%.1f".format(ms / count)} ms per pad (three patches each, JVM, includes warm-up)")
    }

    private fun write(file: File, samples: FloatArray) { WavWriter.write(file, KerfRender.toSnip(samples)) }
}
