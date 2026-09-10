package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AnswerStore
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import java.io.File

/**
 * Liner notes — the kit's story as prose, written from what it already
 * tracks: where the sounds were dug or chopped from, the resample
 * generation, the wear on the tape, key and tempo, the patterns it
 * plays, the B-side, and every pad with its class and treatments.
 * Deterministic: same kit, same words. The expansion export drops it
 * beside the J-card — the insert you read, next to the one you look at.
 */
object LinerNotes {

    const val FILE_NAME = "liner-notes.txt"

    fun render(
        kit: Kit,
        kitDir: File,
        /** The label's catalog number, leading the identity line when set. */
        catalog: String? = null,
    ): String = buildString {
        appendLine(kit.name.uppercase())
        appendLine("=".repeat(maxOf(kit.name.length, 8)))
        appendLine()

        val identity = mutableListOf<String>()
        catalog?.let { identity += it }
        kit.key?.let { identity += it.label }
        kit.tempoBpm?.let { identity += "%.0f bpm".format(java.util.Locale.ROOT, it) }
        identity += "${kit.pads.size} pad${if (kit.pads.size == 1) "" else "s"}"
        appendLine(identity.joinToString(" - "))
        appendLine()

        // Where it came from - dig beats chop beats import in specificity.
        val dug = kit.pads.mapNotNull { p ->
            p.source["song"]?.let { s -> s to (p.source["at"] ?: "?") }
        }.distinct()
        val resampled = kit.pads.mapNotNull { p ->
            p.source["resampledFrom"]?.let { it to (p.source["generation"] ?: "2") }
        }.distinct()
        val chopped = kit.pads.mapNotNull { it.source["file"] }.distinct()
        val imported = kit.pads.mapNotNull { it.source["importedFrom"] }.distinct()
        when {
            dug.isNotEmpty() -> dug.forEach { (song, at) ->
                appendLine("Dug from \"$song\" at $at.")
            }
            resampled.isNotEmpty() -> resampled.forEach { (from, gen) ->
                appendLine("Generation $gen - bounced from \"$from\" and chopped again.")
            }
            chopped.isNotEmpty() -> appendLine("Chopped from ${chopped.joinToString(", ") { "\"$it\"" }}.")
            imported.isNotEmpty() -> appendLine("Imported from ${imported.joinToString(", ") { "\"$it\"" }}.")
            else -> appendLine("Built by hand, pad by pad.")
        }

        kit.wear?.let { wear ->
            appendLine(
                if (wear.mileage <= 0.0) {
                    "A new tape."
                } else {
                    "%.0f mile%s on the tape (%.0f%% worn%s).".format(java.util.Locale.ROOT, 
                        wear.mileage, if (wear.mileage == 1.0) "" else "s", wear.w * 100,
                        if (wear.enabled) "" else ", aging paused",
                    )
                },
            )
        }
        appendLine()

        val grooves = GrooveStore.load(kitDir)
        if (grooves.isNotEmpty()) {
            appendLine("Plays: ${grooves.joinToString(", ") { it.name }}.")
        }
        AnswerStore.load(kitDir)?.let { answer ->
            val band = if (answer.band.isEmpty()) "" else
                " with ${answer.band.joinToString(" and ") { it.name.substringAfterLast(' ').lowercase() }}"
            appendLine("The B-side: ${answer.name}$band.")
        }
        if (grooves.isNotEmpty() || AnswerStore.load(kitDir) != null) appendLine()

        appendLine("THE SOUNDS")
        for (pad in kit.pads.sortedBy { it.slot }) {
            val label = "%s%02d".format(java.util.Locale.ROOT, 'A' + (pad.slot - 1) / 16, (pad.slot - 1) % 16 + 1)
            val extras = mutableListOf<String>()
            pad.recipe?.let { extras += recipeWord(it) }
            if (pad.decay != null || pad.attack != null || pad.cutoff != null || pad.resonance != null) {
                extras += "shaped"
            }
            pad.humanize?.let { extras += "humanized" }
            if (pad.velocityLayers.isNotEmpty()) extras += "${pad.velocityLayers.size} velocity zones"
            appendLine(
                "  %s  %-24s %s%s".format(
                    label, pad.displayName, pad.drumClass.name.lowercase(),
                    if (extras.isEmpty()) "" else "  (" + extras.joinToString(", ") + ")",
                ),
            )
        }
        appendLine()
        appendLine("Made with SnipSnap.")
    }

    fun writeTo(kit: Kit, kitDir: File, dest: File, catalog: String? = null): File {
        dest.parentFile?.mkdirs()
        dest.writeText(render(kit, kitDir, catalog), Charsets.UTF_8)
        return dest
    }

    /** One honest word per recipe kind - the detail lives in kit.json. */
    private fun recipeWord(recipe: JsonValue.Obj): String {
        (recipe.entries["era"] as? JsonValue.Str)?.let { return "through the ${it.value}" }
        (recipe.entries["doctor"] as? JsonValue.Str)?.let { return "doctored (${it.value})" }
        return "treated"
    }
}
