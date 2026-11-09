package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Listening candidates and raw stage taps, not an approved factory bank. */
object BackwashAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/backwash-audition").apply { mkdirs() }
        val rows = StringBuilder("voice,case,milliseconds,seconds,peak,mean,classifier,tailRms\n")
        val html = StringBuilder("<!doctype html><meta charset=utf-8><title>Backwash first audition</title><style>body{max-width:900px;margin:40px auto;background:#171b20;color:#eee;font:17px system-ui}article{margin:20px 0}audio{width:100%}small{color:#aaa}</style><h1>Backwash</h1><p>Dry hit → moving double → compressed swell → dark amp. First-build candidates, not approved presets. Stage comparisons are loudness matched; output clips retain engine level. No shared bus pumping.</p>")
        for (voice in BackwashVoice.entries) {
            html.append("<h2>$voice</h2>")
            val cases = linkedMapOf("default" to Backwash.defaults(voice))
            for (key in Backwash.defaults(voice).keys) for (v in listOf(.1f, .5f, .9f))
                cases["${key.lowercase()}-$v"] = Backwash.defaults(voice) + (key to v)
            for ((a, b) in listOf("SWAY" to "TRAIL", "TRAIL" to "PRESS", "DAMP" to "PRESS", "PRESS" to "DRIVE"))
                for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f))
                    cases["$a-$x-$b-$y"] = Backwash.defaults(voice) + mapOf(a to x, b to y)
            repeat(3) { Backwash.render(voice) }
            for ((name, macros) in cases) {
                val start = System.nanoTime()
                val s = Backwash.render(voice, macros)
                val ms = (System.nanoTime() - start) / 1e6
                val id = "${voice.name.lowercase()}-$name"
                WavWriter.write(File(root, "$id.wav"), s, WavWriter.BitDepth.PCM_24)
                File(root, "$id.json").writeText(PadRecipe(BackwashPatch(name, voice, macros)).toJsonText())
                val tail = s.samples.takeLast(441)
                val tailRms = sqrt(tail.sumOf { it.toDouble() * it } / tail.size)
                rows.append("$voice,$name,$ms,${s.samples.size / 44100.0},${s.peak()},${s.samples.average()},${Classifier.classify(s).drumClass},$tailRms\n")
                html.append("<article><b>$name</b><audio controls preload=none src='$id.wav'></audio><small><a href='$id.json'>Saved recipe</a></small></article>")
            }
            val s = Backwash.inspect(voice)
            for ((name, raw) in listOf("dry" to s.dry, "chorus" to s.moving, "compressed" to s.pressed, "amp" to s.amp)) {
                val id = "${voice.name.lowercase()}-stage-$name"
                val matched = Snip(raw.samples.copyOf(), 1, 44100)
                Dsp.levelTo(matched.samples, 44100, Dsp.MELODIC_LOUDNESS_TARGET)
                WavWriter.write(File(root, "$id.wav"), matched, WavWriter.BitDepth.PCM_24)
                html.append("<article>Stage: $name (matched)<audio controls preload=none src='$id.wav'></audio></article>")
            }
        }
        BackwashGroove.write(root)
        File(root, "metrics.csv").writeText(rows.toString())
        File(root, "index.html").writeText(html.toString())
        println("BACKWASH audition: ${root.absolutePath}")
    }
}
