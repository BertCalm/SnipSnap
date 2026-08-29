package com.snipsnap.app

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.shell.StarterKits
import java.io.File

/**
 * The tape shelf: kit folders under the app's private files directory.
 *
 * A kit *is* a directory (WAVs + `kit.json`) — the same shape the CLI,
 * the exporters and the desktop tools all speak, which is what makes the
 * app a thin shell. This class only walks the shelf and hands folders to
 * [KitStore]; it owns no format knowledge.
 *
 * All methods do file IO — call them off the main thread.
 */
class KitShelf(private val root: File) {

    /** A kit and the folder it lives in. */
    data class Entry(val dir: File, val kit: Kit)

    /**
     * Every readable kit on the shelf. A folder whose `kit.json` is broken
     * is skipped, not fatal — one damaged kit must never blank the shelf.
     */
    fun list(): List<Entry> = KitStore.list(root).mapNotNull { dir ->
        try {
            Entry(dir, KitStore.load(dir))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * An MPC-safe kit name from [base] that no shelf folder holds yet:
     * "FACTORY", then "FACTORY 2", "FACTORY 3"…
     */
    fun freshName(base: String): String {
        val stem = Names.sanitizeStem(base.uppercase())
        var name = stem
        var n = 2
        while (File(root, name).exists()) {
            name = "$stem $n"
            n++
        }
        return name
    }

    /**
     * Render a starter onto the shelf — the FRESH TAPE menu's action.
     * Synchronous and seconds-long (the synth engines render offline);
     * the caller shows DUBBING… while it runs.
     */
    fun render(starter: StarterKits.Starter, seed: Int): Entry {
        root.mkdirs()
        val name = freshName(starter.displayName)
        val dir = File(root, name)
        val kit = starter.render(name, dir, seed)
        return Entry(dir, kit)
    }
}
