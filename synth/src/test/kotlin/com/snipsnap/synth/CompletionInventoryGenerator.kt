package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File

/** Runtime inventory, not an acceptance test: counts registered identities without rendering audio. */
object CompletionInventoryGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val destination = File(args.single())
        val all = Presets.all()
        val rows = PatchTypes.all.sortedBy(PatchTypes::engineOf).map { type ->
            val engine = PatchTypes.engineOf(type)
            val voices = PatchTypes.voicesOf(type)
            val presets = all.filter { it.engine == engine }
            val voiceRows = voices.map { voice ->
                val factory = Presets.forVoice(engine, voice)
                check(factory == presets.filter { it.voiceName == voice }) { "$engine/$voice registry mismatch" }
                JsonValue.Obj(linkedMapOf(
                    "voice" to JsonValue.Str(voice),
                    "factory_preset_names" to JsonValue.Arr(factory.map { JsonValue.Str(it.name) }),
                    // Macro presence is evidence to inspect; it proves neither a strum nor a musical hold.
                    "factory_control_names" to JsonValue.Arr(factory.flatMap { Velocity.macroSpecsFor(it) }
                        .map { it.name }.distinct().sorted().map { JsonValue.Str(it) }),
                ))
            }
            JsonValue.Obj(linkedMapOf(
                "engine" to JsonValue.Str(engine),
                "patch_class" to JsonValue.Str(type.simpleName),
                "registered_voice_count" to JsonValue.Num(voices.size.toDouble()),
                "factory_preset_count" to JsonValue.Num(presets.size.toDouble()),
                "factory_policy" to JsonValue.Str(when (engine) {
                    in PatchTypes.ROSTERLESS -> "rosterless"
                    in PatchTypes.PROTOTYPES -> "prototype"
                    else -> "factory"
                }),
                "voices" to JsonValue.Arr(voiceRows),
            ))
        }
        fun git(vararg command: String): String {
            val process = ProcessBuilder(listOf("git") + command).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "Cannot record source identity" }
            return output
        }
        val document = JsonValue.Obj(linkedMapOf(
            "schema" to JsonValue.Num(1.0),
            "base_commit" to JsonValue.Str(git("rev-parse", "HEAD")),
            "working_tree_status" to JsonValue.Str(git("status", "--short")),
            "evidence" to JsonValue.Str("Compiled Patch sealed implementations, voice identities and factory registry; no audio rendered or heard. Registered identities require a separate musical-role audit."),
            "engines" to JsonValue.Arr(rows),
        ))
        destination.parentFile?.mkdirs()
        destination.writeText(Json.write(document) + "\n")
        println("Inventoried ${rows.size} engines, ${all.size} factory recipes: ${destination.absolutePath}")
    }
}
