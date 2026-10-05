package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/** Compare factory recipes and common-pitch character without changing the preferred DSP. */
object AerostatPresetComparisonGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0]).apply { mkdirs() }
        val presets = AerostatPresets.all()
        val cases = mutableListOf<String>()
        val sequence = mutableListOf<Snip>()
        for (common in listOf(false, true)) {
            for (patch in presets) {
                val id = patch.name.lowercase().replace(' ', '_') + if (common) "_c3" else "_factory"
                val macros = if (common) patch.macros + ("TUNE" to 0f) else patch.macros
                val note = if (common) "C3" else "factory note"
                val group = if (common) "Same C3 note" else "Factory presets"
                val snip = Aerostat.render(patch.voice, macros)
                WavWriter.write(File(root, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
                if (common) sequence += snip
                cases += """{"id":"$id","title":"${patch.name} · $note","group":"$group","preset":"${patch.name}","macros":{${macros.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}"""
            }
        }
        val gap = (0.12 * Dsp.RATE).toInt()
        val joined = FloatArray(sequence.sumOf { it.samples.size } + gap * (sequence.size - 1))
        var at = 0
        for ((i, snip) in sequence.withIndex()) {
            snip.samples.copyInto(joined, at)
            at += snip.samples.size + if (i < sequence.lastIndex) gap else 0
        }
        WavWriter.write(File(root, "lineup.wav"), AuditionLevel.level(Snip(joined, channels = 1, sampleRate = Dsp.RATE)), WavWriter.BitDepth.PCM_16)
        cases += """{"id":"lineup","title":"All five · same C3 note","group":"Start here","order":[${presets.joinToString(",") { "\"${it.name}\"" }}]}"""
        File(root, "recipes.json").writeText(cases.joinToString(",\n", "[", "]\n"))
        println("Rendered ${cases.size} Aerostat preset comparison clips to $root")
    }
}
