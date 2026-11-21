package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * KERF round 4's engine pack: every preset as the SYNTH picker renders it (velocity 1, take 0), and each preset struck
 * at the kits' three touch zones (0.30, 0.65, 1), levelled as the engine levels it, so a soft hit plays softer. Run with
 * `./gradlew :synth:generateKerfEngineAudition`. The genre kits' pack is `:shell:generateKerfKits`.
 */
object KerfEngineAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "testkit/kerf-engine-audition").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val presets = KerfPresets.all()
        presets.parallelStream().forEach { p ->
            val stem = "${p.voice.name}_${p.name.replace(' ', '_')}"
            WavWriter.write(File(out, "presets/$stem.wav"), p.render())
            for (v in KerfKits.velocities) WavWriter.write(File(out, "zones/${stem}_v%03d.wav".format(java.util.Locale.ROOT, (v * 100).toInt())), p.render(v))
        }
        println("KERF engine audition: ${presets.size} presets to ${out.path}")
    }
}
