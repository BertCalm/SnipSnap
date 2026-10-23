package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import java.io.File

/** Source-backed printed pads share copy/guard rules, while retaining their own recipe formats. */
object PrintedPads {
    const val AQUARIUM_RENDERER_REQUIRED = "AQUARIUM OFFLINE RENDERER IS NOT AVAILABLE ON THIS HOST."

    /** Recognize the marker even when its contents are damaged; mutation guards must still hold. */
    fun has(recipe: JsonValue.Obj?): Boolean = recipe?.entries?.let {
        "boardPad" in it || "aquariumPad" in it
    } ?: false

    /** Existing board labels remain unchanged; Aquarium never invents an AMT readout. */
    fun label(recipe: JsonValue.Obj?): String =
        if (recipe?.entries?.containsKey("aquariumPad") == true) "AQUARIUM"
        else BoardSheet.read(recipe)?.type?.name ?: "BOARD"

    /** Source-only validation, before a child kit or any destination samples are created. */
    fun requireImportable(sourceDir: File, original: KitPad) {
        when {
            original.recipe?.entries?.containsKey("aquariumPad") == true ->
                AquariumPadSheet.requireImportable(sourceDir, original)
            original.recipe?.entries?.containsKey("boardPad") == true ->
                BoardSheet.requireImportable(sourceDir, original)
        }
    }

    /** Individual-pad copying carries the immutable editing source, rather than only the wet WAVs. */
    fun importPad(sourceDir: File, targetDir: File, original: KitPad, copied: KitPad): KitPad =
        when {
            original.recipe?.entries?.containsKey("aquariumPad") == true ->
                AquariumPadSheet.importPad(sourceDir, targetDir, original, copied)
            original.recipe?.entries?.containsKey("boardPad") == true ->
                BoardSheet.importPad(sourceDir, targetDir, original, copied)
            else -> copied
        }
}
