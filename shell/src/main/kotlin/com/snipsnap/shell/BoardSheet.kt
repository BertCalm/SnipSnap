package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.BoardRecipe
import com.snipsnap.synth.BoardType
import com.snipsnap.synth.ObliterateBoard
import java.io.File

/** Editable compound boards, backed by the shared immutable printed-pad store. */
object BoardSheet {
    const val DEFAULT_AMOUNT = .7f
    fun defaultAmount(type: BoardType): Float = if (type == BoardType.OBLITERATE) ObliterateBoard.DEFAULT_AMOUNT else DEFAULT_AMOUNT
    data class Applied(val amount: Float, val type: BoardType = BoardType.WIDE,
        val obliterate: ObliterateBoard.Settings? = if (type == BoardType.OBLITERATE) ObliterateBoard.Settings() else null) {
        val recipe: BoardRecipe get() = BoardRecipe(type, amount, obliterate)
    }

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
        store.descriptor(recipe)?.let { BoardRecipe.fromJsonValue(it) }?.let { Applied(it.amount, it.type, it.obliterate) }
    } catch (_: Exception) { null }

    /** AMT zero returns the unchanged pre-board sample, including its channel count. */
    fun preview(model: KitBuilderModel, slot: Int, amount: Float, type: BoardType = BoardType.WIDE): Snip {
        return preview(model, slot, BoardRecipe(type = type, amount = amount))
    }

    fun preview(model: KitBuilderModel, slot: Int, board: BoardRecipe): Snip = store.preview(model, slot, board::process)

    /** Every KEEP renders all velocity zones from the immutable pre-board baseline. */
    fun apply(model: KitBuilderModel, slot: Int, amount: Float, type: BoardType = BoardType.WIDE): KitPad =
        apply(model, slot, BoardRecipe(type = type, amount = amount))

    fun apply(model: KitBuilderModel, slot: Int, board: BoardRecipe): KitPad = synchronized(model) {
        if (board.type == BoardType.OBLITERATE && !board.isBypass) store.requireSources(model, slot, ObliterateBoard::requireInput)
        // Board's version-1 reader narrows saved amounts to Float. Preserve its
        // meaningful no-op even if an external writer used more JSON precision.
        val saved = store.descriptor(model.pad(slot)?.recipe)
        val descriptor = saved?.takeIf { BoardRecipe.fromJsonValue(it) == board } ?: board.toJsonValue()
        store.apply(model, slot, descriptor, board.isBypass, board.type.name, board::process)
    }

    fun remove(model: KitBuilderModel, slot: Int): KitPad = store.remove(model, slot)
    fun undo(model: KitBuilderModel, slot: Int): KitPad = store.undo(model, slot)
    fun canUndo(model: KitBuilderModel, slot: Int): Boolean = store.canUndo(model, slot)

    /** The pad REMOVE would bring back, read for the kit bin's sweep ([PrintedPads.sourcePad]). */
    internal fun sourcePad(kitDir: File, pad: KitPad): KitPad = store.sourcePad(kitDir, pad)

    /** Source-only check before a copy creates any destination files. */
    fun requireImportable(sourceDir: File, original: KitPad) = store.requireImportable(sourceDir, original)

    /** Nested editing sources require copying their complete kit folder. */
    fun importPad(sourceDir: File, targetDir: File, original: KitPad, copied: KitPad): KitPad {
        val saved = store.descriptor(original.recipe) ?: return copied
        val canonical = JsonValue.Obj(original.recipe!!.entries + ("board" to BoardRecipe.fromJsonValue(saved).toJsonValue()))
        return store.importPad(sourceDir, targetDir, original.copy(recipe = canonical), copied)
    }
}
