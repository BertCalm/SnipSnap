package com.snipsnap.app.store

import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.StarterKits
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
 * One reading of the shelf: what was on it, and what could not be read.
 *
 * Returned as a value rather than left on the library as mutable state.
 * A side-channel property cannot distinguish "checked, all clean" from
 * "never checked" — both read as an empty list — and callers here fetch
 * the shelf across an `await`, which is exactly where a stale read hides.
 */
data class ShelfListing(
    val entries: List<KitEntry>,
    val unreadable: List<UnreadableKit>,
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
     * The shelf: readable tapes, plus whatever could not be read.
     *
     * `KitStore.load` throws on a malformed `kit.json` and *deliberately*
     * refuses one written by a newer build — which is the right call for a
     * single kit and the wrong one for the whole shelf. Letting that
     * propagate means one bad folder crashes the app on launch, with no
     * way back in to delete it. A tape that cannot be read is hidden, not
     * fatal.
     *
     * M0 does not yet show [ShelfListing.unreadable] anywhere; it is
     * returned so the information exists rather than being swallowed, and
     * so surfacing it later is a UI change and not an archaeology project.
     */
    fun list(): ShelfListing {
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
        return ShelfListing(kits, skipped)
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
     * NEW KIT: render [starter] straight into a fresh folder and open it.
     * Validates the derived folder exactly as [create] does — a starter's
     * [StarterKits.Starter.displayName] can collide on disk with an
     * existing kit the same way a typed name can, and `sanitizeStem`
     * collapsing underscore runs is the same trap either way.
     *
     * Rendering is compute (sixteen-ish pads of synthesized audio), not
     * IO — the caller is expected to run this off [name] on
     * `Dispatchers.Default`, not `Dispatchers.IO`, for the same reason the
     * old first-run seed did: `Dispatchers.IO`'s pool is sized for threads
     * parked on blocking calls, not CPU-bound rendering.
     */
    fun createFromStarter(starter: StarterKits.Starter, name: String, seed: Int): KitBuilderModel {
        require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
        val dir = File(root, Names.sanitizeStem(name))
        // Validate the *folder*, not just the name — see create()'s comment.
        require(!dir.exists()) { "a kit folder named '${dir.name}' already exists" }
        starter.render(name, dir, seed)
        return KitBuilderModel.open(dir)
    }
}
