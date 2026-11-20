package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.File

/**
 * KERF round 2's workbench: fit real audio from disk and write what the renderer makes of the fit.
 * Run with `./gradlew :synth:generateKerfFit -PkerfInput=... -PkerfOutput=...`.
 *
 * A folder of WAVs is fitted hit by hit (each file is one chop; `<name>.wav` and `<name>.json` come out).
 * A single WAV is treated as a loop: chopped, fitted and written as a kit, clean and chopped.
 * The source audio is the caller's; nothing is read from or written to the repository.
 */
object KerfFitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = File(args[0]); val out = File(args[1])
        if (input.isDirectory) {
            var ms = 0.0; var count = 0
            for (f in input.listFiles { f -> f.extension == "wav" }!!.sortedBy { it.name }) {
                val mono = Cleanup.toMono(WavReader.read(f))
                if (mono.frameCount >= KerfFit.MAX_SECONDS * KerfRender.RATE) { println("skipped ${f.name}: ${mono.durationSeconds} s is not a one-shot"); continue }
                val t0 = System.nanoTime()
                val patch = KerfFit.fit(mono.samples, Dsp.seedFor(f.name).toLong() and 0x7fffffffL)
                ms += (System.nanoTime() - t0) / 1e6; count++
                // written unscaled so a level comparison against the source means something
                WavWriter.write(File(out, f.name), com.snipsnap.audio.Snip(KerfRender.render(patch).map { it.coerceIn(-1f, 1f) }.toFloatArray(), 1, KerfRender.RATE))
                File(out, f.nameWithoutExtension + ".json").writeText(Json.write(patch.toJsonValue()))
            }
            println("KERF fit: $count hits, ${"%.0f".format(ms / maxOf(count, 1))} ms per hit (JVM, includes warm-up)")
        } else {
            val t0 = System.nanoTime()
            val loop = WavReader.read(input); val mono = Cleanup.toMono(loop)
            val kit = KerfFit.kitFromLoop(loop, input.nameWithoutExtension)
            val seconds = (System.nanoTime() - t0) / 1e9
            for (p in kit) {
                val name = "%02d_%s".format(p.pad.pad, p.pad.drumClass)
                // the caller's own chop beside its twin, for listening; it goes where the caller pointed, never into the repository
                val frames = (p.seconds * KerfRender.RATE).toInt()
                WavWriter.write(File(out, "source/$name.wav"), KerfRender.toSnip(mono.samples.copyOfRange(p.sourceFrame, minOf(mono.frameCount, p.sourceFrame + frames))))
                val chop = KerfRender.chopped(p.pad.patch, p.pad.prev, p.pad.next, p.pad.bleed, p.pad.slop)
                WavWriter.write(File(out, "chopped/$name.wav"), KerfRender.toSnip(chop.samples))
                WavWriter.write(File(out, "clean/$name.wav"), KerfRender.toSnip(KerfRender.render(p.pad.patch)))
            }
            println("KERF kit from ${input.name}: ${kit.size} pads in ${"%.1f".format(seconds)} s; " +
                kit.joinToString { "${it.pad.drumClass}@${"%.2f".format(it.sourceFrame.toDouble() / KerfRender.RATE)}s" })
        }
    }
}
