package com.snipsnap.app

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.MutateSheet
import com.snipsnap.shell.StarterKits
import com.snipsnap.shell.TextureKits
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

    /**
     * KEY: set or clear [source]'s key - metadata in `kit.json`, nothing
     * retuned by itself - and hand back the entry with the kit re-read.
     */
    fun setKey(source: Entry, key: com.snipsnap.audio.KeySpec?): Entry {
        val model = com.snipsnap.shell.KitBuilderModel.open(source.dir)
        model.setKey(key)
        model.save()
        return Entry(source.dir, model.kit)
    }

    /**
     * IN KEY: every tonal pad of [source] retuned into its key through the
     * tune fields, the slots that moved returned with the re-read entry.
     */
    fun inKey(source: Entry): Pair<Entry, List<Int>> {
        val model = com.snipsnap.shell.KitBuilderModel.open(source.dir)
        val moved = model.retuneTonalPads()
        if (moved.isNotEmpty()) model.save()
        return Entry(source.dir, model.kit) to moved
    }

    /**
     * Grow a texture kit from one pad of [source] onto the shelf — the KIT
     * screen's SCULPT / STRETCH panel. Same shape as [render]: seconds-long,
     * the caller shows a busy line; the new kit is a tape of its own, named
     * "<Pad> Sculpt" (or Stretched / Frozen), provenance pointing back at
     * `Kit:A03`, through the same `TextureKits` door the CLI verbs use.
     */
    fun texture(source: Entry, slot: Int, spec: TextureKits.Spec): Entry {
        val pad = source.kit.pad(slot) ?: throw IllegalArgumentException("no pad on ${MutateSheet.padTag(slot)}")
        val snip = WavReader.read(File(source.dir, pad.sampleFile))
        root.mkdirs()
        val name = freshName(TextureKits.kitName(pad.displayName, spec))
        val dir = File(root, name)
        val kit = TextureKits.render(name, dir, snip, "${source.kit.name}:${MutateSheet.padTag(slot)}", spec)
        return Entry(dir, kit)
    }
}
