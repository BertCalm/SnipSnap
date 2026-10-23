package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.BoardRecipe
import com.snipsnap.synth.BoardType
import java.io.File

/** Editable compound boards, backed by the shared immutable printed-pad store. */
object BoardSheet {
    const val DEFAULT_AMOUNT = .7f
    data class Applied(val amount: Float, val type: BoardType = BoardType.WIDE)

    private val store by lazy {
        PrintedPadStore(PrintedPadStore.Format(
            namespace = ".boards", markerKey = "boardPad", descriptorKey = "board",
            stateKey = "boardState", undoKey = "boardUndo", label = "Board",
            validateDescriptor = { require(BoardRecipe.fromJsonValue(it).amount > 0f) { "Invalid stored board amount" } },
            validateAudio = { snip ->
                require(snip.frameCount > 0 && snip.channels in 1..2 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.samples.all { it.isFinite() }) {
                    "Board needs valid 44.1 kHz mono or stereo audio"
                }
            },
        ))
    }

    /** Display readers tolerate damaged or future recipes; editing doors refuse them. */
    fun read(recipe: JsonValue.Obj?): Applied? = try {
        store.descriptor(recipe)?.let { BoardRecipe.fromJsonValue(it) }?.let { Applied(it.amount, it.type) }
    } catch (_: Exception) { null }

    /** AMT zero returns the unchanged pre-board sample, including its channel count. */
    fun preview(model: KitBuilderModel, slot: Int, amount: Float, type: BoardType = BoardType.WIDE): Snip {
        val board = BoardRecipe(type = type, amount = amount)
        return store.preview(model, slot, board::process)
    }

    /** Every KEEP renders all velocity zones from the immutable pre-board baseline. */
    fun apply(model: KitBuilderModel, slot: Int, amount: Float, type: BoardType = BoardType.WIDE): KitPad = synchronized(model) {
        val board = BoardRecipe(type = type, amount = amount)
        // Board's version-1 reader narrows saved amounts to Float. Preserve its
        // meaningful no-op even if an external writer used more JSON precision.
        val saved = store.descriptor(model.pad(slot)?.recipe)
        val descriptor = saved?.takeIf { BoardRecipe.fromJsonValue(it) == board } ?: board.toJsonValue()
        store.apply(model, slot, descriptor, board.isBypass, type.name, board::process)
    }

    fun remove(model: KitBuilderModel, slot: Int): KitPad = store.remove(model, slot)
    fun undo(model: KitBuilderModel, slot: Int): KitPad = store.undo(model, slot)
    fun canUndo(model: KitBuilderModel, slot: Int): Boolean = store.canUndo(model, slot)

    /** Source-only check before a copy creates any destination files. */
    fun requireImportable(sourceDir: File, original: KitPad) = store.requireImportable(sourceDir, original)

    /** Nested editing sources require copying their complete kit folder. */
    fun importPad(sourceDir: File, targetDir: File, original: KitPad, copied: KitPad): KitPad {
        val saved = store.descriptor(original.recipe) ?: return copied
        val canonical = JsonValue.Obj(original.recipe!!.entries + ("board" to BoardRecipe.fromJsonValue(saved).toJsonValue()))
        return store.importPad(sourceDir, targetDir, original.copy(recipe = canonical), copied)
    }
}
