package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/** Writes the dry BALLAST voice, macro and HOLD listening set. */
object BallastAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/ballast-audition")
        root.mkdirs()
        val clips = mutableListOf<String>()

        fun write(id: String, patch: BallastPatch) {
            val file = File(root, "$id.wav")
            WavWriter.write(file, patch.render(), WavWriter.BitDepth.PCM_16)
            clips += """{"id":"$id","name":"${patch.name}","voice":"${patch.voice}","midi":${patch.midi}}"""
        }

        for (preset in BallastPresets.all()) {
            val id = "${preset.voice.name.lowercase()}_${preset.name.lowercase().replace(' ', '_')}"
            write(id, preset)
        }
        for (voice in BallastVoice.entries) {
            for ((name, value) in listOf("low" to 0f, "high" to 1f)) {
                write(
                    "${voice.name.lowercase()}_glass_$name",
                    BallastPatch(
                        "GLASS ${name.uppercase()}", voice,
                        Ballast.defaults(voice) + ("GLASS" to value),
                    ),
                )
            }
            write(
                "${voice.name.lowercase()}_hold",
                BallastPatch("HELD", voice, Ballast.defaults(voice) + ("HOLD" to 1f)),
            )
        }
        File(root, "manifest.json").writeText("{\"engine\":\"BALLAST\",\"clips\":[${clips.joinToString(",")}]}\n")
        println("wrote ${clips.size} BALLAST clips and manifest.json under ${root.absolutePath}")
    }
}
