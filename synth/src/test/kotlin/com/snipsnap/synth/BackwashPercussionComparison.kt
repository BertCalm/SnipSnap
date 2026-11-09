package com.snipsnap.synth

import com.snipsnap.audio.*
import java.io.File
import kotlin.math.abs

/** Same source strikes, three stage choices, one peak-safe loudness target per instrument. */
object BackwashPercussionComparison {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/backwash-percussion-comparison").apply { mkdirs() }
        val report = StringBuilder("voice,stage,target,measured,gain,peak\n")
        for (patch in BackwashPresets.kitOrder().takeLast(4)) {
            val id = patch.voice.name.lowercase()
            val gentler = patch.copy(macros = patch.macros + ("DRIVE" to .15f))
            check(patch.macros.filterKeys { it != "DRIVE" } == gentler.macros.filterKeys { it != "DRIVE" })
            val raw = linkedMapOf("dry" to Backwash.source(patch.voice, patch.macros),
                "lower-drive" to gentler.render(), "previous" to patch.render())
            check(gentler.render().samples.contentEquals(BackwashPatch.fromJsonText(gentler.toJsonText()).render().samples))
            val levels = raw.mapValues { Loudness.of(it.value) }
            val target = minOf(Dsp.MELODIC_LOUDNESS_TARGET, raw.minOf { (stage, s) -> .85f * levels.getValue(stage) / s.peak() })
            for ((stage, s) in raw) {
                val gain = target / levels.getValue(stage)
                val matched = Snip(FloatArray(s.samples.size) { s.samples[it] * gain }, 1, Dsp.RATE)
                check(matched.samples.all { it.isFinite() && abs(it) <= .85001f })
                check(abs(Loudness.of(matched) - target) < 1e-5)
                WavWriter.write(File(root, "$id-$stage.wav"), matched, WavWriter.BitDepth.PCM_24)
                report.append("$id,$stage,$target,${Loudness.of(matched)},$gain,${matched.peak()}\n")
            }
            File(root, "$id-lower-drive.json").writeText(gentler.toJsonText())
        }
        File(root, "matching.csv").writeText(report.toString())
        println("PASS: 12 finite, peak-safe, loudness-matched clips; four lower-drive recipes replay exactly.")
    }
}
