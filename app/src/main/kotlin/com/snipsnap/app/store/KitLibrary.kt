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

/** A kit folder the shelf could not read, and why. */
data class UnreadableKit(
    val dir: File,
    val reason: String,
)

/**
 * The shelf: kit folders under one root.
 *
 * Takes a [File], never a `Context` — the Activity resolves
 * `filesDir/kits` and hands it over, which is what lets the whole shelf be
 * tested on a temp directory with no emulator in sight.
 */
class KitLibrary(val root: File) {

    /**
     * Folders the last [list] call could not read. Not silent: the shelf
     * skips them so one bad tape cannot take the app down, and the UI can
     * say so.
     */
    var unreadable: List<UnreadableKit> = emptyList()
        private set

    /**
     * The shelf, readable tapes only.
     *
     * `KitStore.load` throws on a malformed `kit.json` and *deliberately*
     * refuses one written by a newer build — which is the right call for a
     * single kit and the wrong one for the whole shelf. Letting that
     * propagate means one bad folder crashes the app on launch, with no
     * way back in to delete it. A tape that cannot be read is hidden, not
     * fatal.
     */
    fun list(): List<KitEntry> {
        val skipped = mutableListOf<UnreadableKit>()
        val kits = KitStore.list(root).mapNotNull { dir ->
            try {
                val kit = KitStore.load(dir)
                KitEntry(dir = dir, name = kit.name, padCount = kit.pads.size)
            } catch (e: Exception) {
                skipped += UnreadableKit(dir, e.message ?: e.javaClass.simpleName)
                null
            }
        }.sortedBy { it.name.lowercase() }
        unreadable = skipped
        return kits
    }

    fun open(entry: KitEntry): KitBuilderModel = KitBuilderModel.open(entry.dir)

    /** FRESH TAPE. Rejects a name the MPC's browser could not show. */
    fun create(name: String): KitBuilderModel {
        require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
        val dir = File(root, Names.sanitizeStem(name))
        // Validate the *folder*, not just the name. sanitizeStem collapses
        // underscore runs, so "A_B" and "A__B" are two different MPC-safe
        // names that land on one directory — and creating into an occupied
        // folder overwrites someone else's kit.json without a word.
        require(!dir.exists()) { "a kit folder named '${dir.name}' already exists" }
        return KitBuilderModel.create(name, dir)
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
