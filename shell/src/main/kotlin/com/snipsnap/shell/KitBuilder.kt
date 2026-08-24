package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.util.Locale

/**
 * The KIT screen: the 4×4 grid over a kit folder, every edit non-
 * destructive and every assignment trivially draggable — the pledge
 * `AutoPlace` makes, kept at the editing layer.
 *
 * A kit *is* its folder (`kit.json` + WAVs); this model edits the
 * in-memory [Kit] and writes WAVs eagerly (a pad you can hear is a file
 * that exists), but `kit.json` only on [save] — [dirty] tells the UI when
 * the two disagree.
 */
class KitBuilderModel private constructor(
    val kitDir: File,
    kit: Kit,
) {

    var kit: Kit = kit
        private set

    var dirty: Boolean = false
        private set

    val name: String get() = kit.name

    fun pad(slot: Int): KitPad? = kit.pad(slot)

    /** The visible bank (16 pads) as slots [first..first+15]; A = 1. */
    fun bank(bankIndex: Int = 0): List<KitPad?> {
        require(bankIndex in 0..7) { "bank 0..7 (A..H), got $bankIndex" }
        val first = bankIndex * 16 + 1
        return (first until first + 16).map { kit.pad(it) }
    }

    /** The empty-kit LCD line, or null once anything is placed. */
    val emptyStateLine: String? get() = if (kit.pads.isEmpty()) Copy.EMPTY_KIT else null

    /**
     * Drop a snip on a pad: the WAV is written into the kit folder named
     * by pad and class (`A03_HatClosed_01.wav`), the class brings its
     * colour and mute group, and any previous occupant's file is removed
     * if nothing else references it.
     */
    fun assign(
        slot: Int,
        snip: Snip,
        drumClass: DrumClass = DrumClass.UNKNOWN,
        displayName: String? = null,
    ): KitPad {
        require(snip.frameCount > 0) { "won't assign an empty snip" }
        val stem = nextStem(slot, drumClass)
        WavWriter.write(File(kitDir, "$stem.wav"), snip)

        val previous = kit.pad(slot)
        val pad = KitPad(
            slot = slot,
            sampleFile = "$stem.wav",
            displayName = displayName
                ?: String.format(Locale.ROOT, "%s %02d", AutoPlace.nameFor(drumClass), classCount(drumClass) + 1),
            drumClass = drumClass,
            colorHex = AutoPlace.colorFor(drumClass),
            muteGroup = AutoPlace.muteGroupFor(drumClass),
        )
        kit = kit.copy(pads = kit.pads.filter { it.slot != slot } + pad)
        previous?.let { deleteIfUnreferenced(it) }
        dirty = true
        return pad
    }

    /** Long-press clear: the pad empties; its file goes if nothing shares it. */
    fun clear(slot: Int) {
        val pad = kit.pad(slot) ?: return
        kit = kit.copy(pads = kit.pads.filter { it.slot != slot })
        deleteIfUnreferenced(pad)
        dirty = true
    }

    /**
     * Drag a pad to another slot. An occupied target swaps — dragging is
     * how layouts get fixed, and losing a pad to a move would be theft.
     */
    fun move(fromSlot: Int, toSlot: Int) {
        if (fromSlot == toSlot) return
        val from = kit.pad(fromSlot) ?: return
        val to = kit.pad(toSlot)
        val moved = kit.pads.filter { it.slot != fromSlot && it.slot != toSlot }.toMutableList()
        moved += from.copy(slot = toSlot)
        to?.let { moved += it.copy(slot = fromSlot) }
        kit = kit.copy(pads = moved)
        dirty = true
    }

    /**
     * Per-pad edits — name, colour, level, pan, tune, mute group,
     * one-shot. [KitPad]'s own validation guards the ranges; the slot and
     * sample stay what they were.
     */
    fun update(slot: Int, transform: (KitPad) -> KitPad): KitPad {
        val pad = kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        val edited = transform(pad)
        require(edited.slot == pad.slot) { "update can't move pads — use move()" }
        require(edited.sampleFile == pad.sampleFile) { "update can't swap samples — use assign()" }
        kit = kit.copy(pads = kit.pads.map { if (it.slot == slot) edited else it })
        dirty = true
        return edited
    }

    /** Write `kit.json`. The moment the folder and the model agree again. */
    fun save(): File {
        val file = KitStore.save(kit, kitDir)
        dirty = false
        return file
    }

    /** The kit-name easter egg, for the rename dialog to surface. */
    fun nameResponse(proposed: String): String? = Copy.kitNameResponse(proposed)

    private fun classCount(dc: DrumClass): Int = kit.pads.count { it.drumClass == dc }

    private fun nextStem(slot: Int, dc: DrumClass): String {
        val base = "%s_%s".format(PadNoteMap.labelForPad(slot), AutoPlace.nameFor(dc))
        var n = 1
        while (true) {
            val stem = Names.sanitizeStem(String.format(Locale.ROOT, "%s_%02d", base, n))
            val taken = kit.pads.any { it.sampleFile.equals("$stem.wav", ignoreCase = true) } ||
                File(kitDir, "$stem.wav").exists()
            if (!taken) return stem
            n++
        }
    }

    private fun deleteIfUnreferenced(pad: KitPad) {
        val files = (listOf(pad.sampleFile) + pad.velocityLayers.map { it.sampleFile }).toSet()
        val stillUsed = kit.pads.flatMap { listOf(it.sampleFile) + it.velocityLayers.map { l -> l.sampleFile } }
        for (f in files) {
            if (f !in stillUsed) File(kitDir, f).delete()
        }
    }

    companion object {

        /** Open an existing kit folder. */
        fun open(kitDir: File): KitBuilderModel =
            KitBuilderModel(kitDir, KitStore.load(kitDir))

        /** FRESH TAPE: a new, empty kit. */
        fun create(name: String, kitDir: File): KitBuilderModel {
            require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
            kitDir.mkdirs()
            val model = KitBuilderModel(kitDir, Kit(name, emptyList()))
            model.save()
            return model
        }

        /**
         * SEND TO GRID lands here: the chop screen's arrangement becomes a
         * whole kit folder in one step, via the same [KitAssembler] the CLI
         * and the generators use.
         */
        fun fromChop(name: String, arranged: List<ArrangedPad?>, kitDir: File): KitBuilderModel {
            require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
            val kit = KitAssembler.assembleArranged(name, arranged, kitDir)
            return KitBuilderModel(kitDir, kit)
        }
    }
}
