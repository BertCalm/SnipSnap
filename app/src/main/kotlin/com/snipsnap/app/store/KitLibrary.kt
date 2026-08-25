package com.snipsnap.app.store

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.ThumpKits
import java.io.File

/** One tape on the shelf: the folder, its name, and how full it is. */
data class KitEntry(
    val dir: File,
    val name: String,
    val padCount: Int,
)

/**
 * The shelf: kit folders under one root.
 *
 * Takes a [File], never a `Context` — the Activity resolves
 * `filesDir/kits` and hands it over, which is what lets the whole shelf be
 * tested on a temp directory with no emulator in sight.
 */
class KitLibrary(val root: File) {

    fun list(): List<KitEntry> = KitStore.list(root).map { dir ->
        val kit = KitStore.load(dir)
        KitEntry(dir = dir, name = kit.name, padCount = kit.pads.size)
    }.sortedBy { it.name.lowercase() }

    fun open(entry: KitEntry): KitBuilderModel = KitBuilderModel.open(entry.dir)

    /** FRESH TAPE. Rejects a name the MPC's browser could not show. */
    fun create(name: String): KitBuilderModel {
        require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
        return KitBuilderModel.create(name, File(root, Names.sanitizeStem(name)))
    }

    /**
     * First run: render the factory kit rather than ship WAVs. Sixteen pads
     * of synthesized audio cost a second of CPU, weigh nothing in the APK,
     * and prove the engines run on the device. Returns false — and touches
     * nothing — if the shelf already has tapes on it.
     */
    fun seedIfEmpty(): Boolean {
        root.mkdirs()
        if (KitStore.list(root).isNotEmpty()) return false
        val dir = File(root, Names.sanitizeStem(SEED_NAME))
        KitAssembler.assembleArranged(SEED_NAME, ThumpKits.classic(), dir)
        return true
    }

    companion object {
        const val SEED_NAME = "SNIPSNAP KIT 01"
    }
}
