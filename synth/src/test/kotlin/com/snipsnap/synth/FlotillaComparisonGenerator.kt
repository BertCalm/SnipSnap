package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File

/** Matched recipes for the static/voice listening fixes; capture `before` before changing DSP. */
object FlotillaComparisonGenerator {
    private data class Clip(val id: String, val name: String, val voice: FlotillaVoice, val macros: Map<String, Float>, val purpose: String)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/flotilla-comparison/candidate")
        root.mkdirs()
        val clips = FlotillaVoice.entries.map { voice ->
            Clip(voice.name.lowercase() + "_default", voice.name, voice, Flotilla.defaults(voice), "Compare the voice at C4; listen for water sitting beneath the pitched hull.")
        } + listOf("FLOTILLA", "VESSEL", "CROSSING", "SURFACE").flatMap { macro ->
            listOf(0f, 1f).map { value ->
                val end = if (value == 0f) "low" else "high"
                Clip("ripple_${macro.lowercase()}_$end", "RIPPLE · $macro $end", FlotillaVoice.RIPPLE,
                    Flotilla.defaults(FlotillaVoice.RIPPLE) + (macro to value), "Compare this control's two ends after comparing previous/refined at the same setting.")
            }
        } + listOf(
            Triple("49", 0.49f, "49%"), Triple("50", 0.50f, "50%"), Triple("51", 0.51f, "51%"),
            Triple("4999", 0.4999f, "49.99%"), Triple("5001", 0.5001f, "50.01%"),
        ).map { (id, value, label) ->
            Clip("ripple_flotilla_local_$id", "RIPPLE · FLOTILLA $label", FlotillaVoice.RIPPLE,
                Flotilla.defaults(FlotillaVoice.RIPPLE) + ("FLOTILLA" to value),
                "Compare nearby population settings in the same version. The 49%, 50% and 51% sequence should keep the same scene; 49.99%, 50% and 50.01% cross the entry of one vessel.")
        } + listOf(FlotillaVoice.DRIFT to "Held Sparse", FlotillaVoice.GATHER to "Held Dense").map { (voice, name) ->
            Clip(voice.name.lowercase() + "_held", name, voice,
                FlotillaPresets.forVoice(voice).first { it.name == name }.macros,
                "Two copies of the stationary held period: compare water texture and hull clarity.")
        }
        val rows = clips.map { clip ->
            val rendered = Flotilla.renderInternal(clip.voice, clip.macros, 60, 1f)
            val snip = AuditionLevel.level(rendered.snip)
            WavWriter.write(File(root, clip.id + ".wav"), snip, WavWriter.BitDepth.PCM_16)
            JsonValue.Obj(linkedMapOf(
                "id" to JsonValue.Str(clip.id), "name" to JsonValue.Str(clip.name), "voice" to JsonValue.Str(clip.voice.name),
                "file" to JsonValue.Str(clip.id + ".wav"), "midi" to JsonValue.Num(60.0), "velocity" to JsonValue.Num(1.0),
                "macros" to JsonValue.Obj(clip.macros.mapValues { JsonValue.Num(it.value.toDouble()) }),
                "purpose" to JsonValue.Str(clip.purpose), "loop" to JsonValue.Bool(rendered.loop != null),
                "seconds" to JsonValue.Num(snip.durationSeconds.toDouble()), "loudness" to JsonValue.Num(Loudness.of(snip).toDouble()),
                "contacts" to JsonValue.Num(rendered.scene.contacts.size.toDouble()), "splashes" to JsonValue.Num(rendered.scene.splashes.size.toDouble()),
            ))
        }
        File(root, "metadata.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "engine" to JsonValue.Str("FLOTILLA"), "version" to JsonValue.Str(root.name),
            "loudnessRule" to JsonValue.Str("Each clip targets 0.03 RMS in its loudest 200 ms. Reduced splash energy can reveal more note at this matched level; attack, decay and movement remain audible."),
            "clips" to JsonValue.Arr(rows),
        ))))
        File(root, "recipes.json").writeText(Json.write(JsonValue.Arr(rows.map { row ->
            JsonValue.Obj(row.entries + mapOf(
                "title" to row.entries.getValue("name"),
                "group" to JsonValue.Str(when {
                    row.entries.getValue("id").str().contains("_local_") -> "Nearby population settings"
                    row.entries.getValue("loop").bool() -> "Held textures"
                    row.entries.getValue("id").str().endsWith("_default") -> "Six voices"
                    else -> "RIPPLE controls"
                }),
                "role" to row.entries.getValue("purpose"),
            ))
        })))
        println("Rendered ${rows.size} matched FLOTILLA clips to ${root.absolutePath}")
    }
}
