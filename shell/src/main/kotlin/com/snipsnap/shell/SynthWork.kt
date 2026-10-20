package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.PadRecipe
import java.io.File

/** Complete reopened-pad work in SYNTH's string settings bag, between bench visits. */
object SynthWork {
    private const val PREFIX = "workingRecipe."
    private const val VERSION = 1

    /** Shelf-relative identity only: a working recipe never records a private absolute path. */
    data class Source(val folder: String, val slot: Int) {
        init {
            require(folder.isNotBlank() && folder != "." && folder != ".." &&
                '/' !in folder && '\\' !in folder && '\u0000' !in folder) { "source kit must be a bare folder name" }
            require(slot in 1..128) { "source pad is out of range" }
        }

        fun matches(shelfRoot: File, kitDir: File?): Boolean = kitDir != null &&
            kitDir.name == folder && kitDir.parentFile?.canonicalFile == shelfRoot.canonicalFile
    }

    data class Working(val recipe: PadRecipe, val source: Source?) {
        init { require(recipe.patch != null) { "SYNTH work needs a patch" } }
    }

    fun source(shelfRoot: File, kitDir: File, slot: Int): Source? =
        if (kitDir.parentFile?.canonicalFile == shelfRoot.canonicalFile) Source(kitDir.name, slot) else null

    private fun key(work: Working): String {
        val patch = requireNotNull(work.recipe.patch)
        return "$PREFIX${patch.engine}.${patch.voiceName}"
    }

    /** Unknown engines, corrupt JSON and mismatched keys are ignored without changing the bag. */
    fun read(settings: Map<String, String>): List<Working> = settings.mapNotNull { (settingsKey, value) ->
        if (!settingsKey.startsWith(PREFIX)) return@mapNotNull null
        runCatching {
            val obj = Json.parse(value).obj()
            require(obj.getValue("version").int() == VERSION)
            val recipe = PadRecipe.fromJsonValue(obj.getValue("recipe"))
            val source = obj["source"]?.obj()?.let {
                Source(it.getValue("folder").str(), it.getValue("slot").int())
            }
            Working(recipe, source).also { require(key(it) == settingsKey) }
        }.getOrNull()
    }

    /** Replace reopened work entries; unrelated navigation settings stay intact. */
    fun replace(settings: MutableMap<String, String>, work: List<Working>) {
        settings.keys.removeAll { it.startsWith(PREFIX) }
        for (entry in work) {
            val obj = linkedMapOf<String, JsonValue>(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "recipe" to entry.recipe.toJsonValue(),
            )
            entry.source?.let {
                obj["source"] = JsonValue.Obj(linkedMapOf(
                    "folder" to JsonValue.Str(it.folder), "slot" to JsonValue.Num(it.slot.toDouble()),
                ))
            }
            settings[key(entry)] = Json.write(JsonValue.Obj(obj))
        }
    }
}
