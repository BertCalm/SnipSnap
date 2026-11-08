package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/** Small editable neighborhoods around the owner-preferred model-3 reference. */
object BackwashNeighborhoodGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/backwash-neighborhood").apply { mkdirs() }
        val families = linkedMapOf(
            "reference" to emptyMap(),
            "tighter" to mapOf("DAMP" to .9f),
            "more-ring" to mapOf("DAMP" to .6f),
            "moving-double" to mapOf("SWAY" to .45f, "TRAIL" to .4f),
        )
        val report = StringBuilder("family,voice,milliseconds,peak,seconds\n")
        for ((name, overrides) in families) {
            val dir = File(root, name).apply { mkdirs() }
            BackwashGroove.write(dir, overrides)
            for (voice in BackwashVoice.entries) {
                val macros = Backwash.defaults(voice) + overrides
                val patch = BackwashPatch("${name.replace('-', ' ')} ${voice.name.lowercase()}", voice, macros)
                val recipe = PadRecipe(patch).toJsonText()
                val start = System.nanoTime()
                val output = patch.render()
                val ms = (System.nanoTime() - start) / 1e6
                val replay = PadRecipe.fromJsonText(recipe).render()
                check(output.samples.contentEquals(replay.samples)) { "Recipe mismatch: $name $voice" }
                check(output.samples.all { it.isFinite() && kotlin.math.abs(it) <= 1f })
                check(output.peak() > .01f && output.samples.size < Dsp.RATE * 1.5)
                val stem = voice.name.lowercase()
                File(dir, "$stem.json").writeText(recipe)
                WavWriter.write(File(dir, "$stem-processed.wav"), output, WavWriter.BitDepth.PCM_24)
                val dry = Backwash.source(voice, macros)
                val matched = Snip(dry.samples.copyOf(), 1, Dsp.RATE)
                Dsp.levelTo(matched.samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
                WavWriter.write(File(dir, "$stem-dry.wav"), matched, WavWriter.BitDepth.PCM_24)
                report.append("$name,$voice,$ms,${output.peak()},${output.samples.size.toDouble() / Dsp.RATE}\n")
            }
        }
        File(root, "metrics.csv").writeText(report.toString())
        File(root, "README.txt").writeText("BACKWASH model 3 remains unchanged. Reference is the owner-preferred direction. Tighter changes only DAMP .75 to .9; More ring changes only DAMP to .6; Moving double changes only SWAY .3 to .45 and TRAIL .25 to .4. All other defaults, groove events and playback balances are identical. JSON recipes reproduce processed one-shots; dry files are pre-effect taps. New variants are candidates, not owner-approved presets.\n")
        println("20 recipes replayed exactly; neighborhood written to ${root.absolutePath}")
    }
}
