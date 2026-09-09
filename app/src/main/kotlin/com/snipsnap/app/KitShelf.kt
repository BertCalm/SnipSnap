package com.snipsnap.app

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.app.ui.TAPE_LOAD_MAX_SEC
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.MutateSheet
import com.snipsnap.shell.OutsideSheet
import com.snipsnap.shell.Rooms
import com.snipsnap.shell.ShelfImport
import com.snipsnap.shell.SnipStore
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
class KitShelf(val root: File) {

    companion object {
        /** The instruments' folder name beside the kits — the PAD SHEET's export doors write here. */
        const val INSTRUMENTS_DIR = "Instruments"

        /**
         * The kits' bin, beside the kits: a direct child of [root] with no
         * `kit.json` of its own directly inside it (the deleted kits sit one
         * level deeper, at `.bin/<name>-<timestamp>/kit.json`), so
         * `KitStore.list(root)` never lists it or anything inside it as a
         * kit on the shelf, the same way `Rooms/` and `.landing-*` already
         * stay invisible to that scan. The same bin-under-a-folder mechanism
         * [Rooms.binDir] uses — but not its architectural sibling: that
         * `.bin` nests one level deeper, inside [Rooms.ROOMS_DIR] itself, so
         * this `.bin` sits beside `Rooms/`, not beside Rooms' own bin.
         */
        const val BIN_DIR = ".bin"

        /** How long the bin keeps a deleted kit — [deleteKit]'s promise, [sweepDeletedKits]'s job. */
        const val BIN_DAYS = 30.0

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }

    /** Where deleted kits sleep under [root], mirroring [Rooms.binDir]. */
    val binDir: File get() = File(root, BIN_DIR)

    /** A kit and the folder it lives in. */
    data class Entry(val dir: File, val kit: Kit)

    /** A kit asleep in the bin: where it lies, what it was called, when it went, when it goes for good. */
    data class BinnedKit(
        val dir: File,
        val name: String,
        val padCount: Int,
        val binnedAtMillis: Long,
        val daysLeft: Int,
    )

    /** An instrument the shop made: its sidecar and what it says. */
    data class InstrumentEntry(val sidecar: File, val instrument: com.snipsnap.kit.InstrumentStore.Instrument)

    /** Where MAKE INSTRUMENT and MAKE PAD write: beside the kits. */
    val instrumentsDir: File get() = File(root, INSTRUMENTS_DIR)

    /** Every readable instrument on the shelf, by name. */
    fun instruments(): List<InstrumentEntry> =
        com.snipsnap.kit.InstrumentStore.list(instrumentsDir).map { (f, i) -> InstrumentEntry(f, i) }

    /** Every room OUTSIDE measured and kept on the shelf, the latest first — MUTATE's third kind of parent. */
    fun rooms(): List<Rooms.Room> = Rooms.list(root)

    /** KEEP ROOM: the room a ROOM trip measured, onto the shelf named after [kitName]. */
    fun keepRoom(outcome: OutsideSheet.Outcome, kitName: String): Rooms.Room = OutsideSheet.keep(root, outcome, kitName)

    /** FORGET → BIN: the room into the bin for [Rooms.BIN_DAYS] days, like every other delete. */
    fun forgetRoom(room: Rooms.Room): Rooms.Binned = Rooms.forget(root, room)

    /** Empty the rooms' bin of what has slept past its days; returns how many went. */
    fun sweepRooms(): Int = Rooms.sweepBin(root)

    /** The rooms in the bin, the most recently forgotten first, with their days left. */
    fun binnedRooms(): List<Rooms.Binned> = Rooms.binned(root)

    /** RESTORE: a binned room back onto the shelf under a name nothing there holds. */
    fun restoreRoom(binned: Rooms.Binned): Rooms.Room = Rooms.unforget(root, binned)

    /** EMPTY THE BIN NOW: every forgotten room in the rooms' bin gone now — [Rooms.emptyBin]'s own promise, [emptyKitBin]'s own shape applied to rooms. */
    fun emptyRoomsBin(): Int = Rooms.emptyBin(root)

    /** How [list] orders the shelf. */
    enum class ShelfSort {
        /**
         * Most-recently-edited `kit.json` first (the shelf's own default,
         * name-and-find followups) — "what was I just working on" beats an
         * alphabetical wall that carries no memory of the user at all, the
         * same reasoning [SnipStore.list]'s own newest-first order already
         * follows for snips.
         */
        RECENT,

        /** A-Z by kit name — [KitStore.list]'s own base order, unchanged. */
        ALPHA,
    }

    /**
     * Every readable kit on the shelf, ordered by [sort]. A folder whose
     * `kit.json` is broken is skipped, not fatal — one damaged kit must
     * never blank the shelf.
     *
     * [ShelfSort.RECENT] sorts by `kit.json`'s OWN file `lastModified()`,
     * deliberately not the kit directory's: [KitStore.save] is the ONLY
     * thing that ever rewrites `kit.json`, and it's an unconditional
     * whole-file overwrite every time it runs (`KitBuilderModel.save()`
     * calls it regardless of `dirty`) — so its mtime is a precise "this
     * exact write happened" signal, not something a filesystem's own
     * directory-mtime semantics have to be trusted to propagate.
     *
     * "Recent" means *edited*, not merely *opened*: every `.save()` call
     * site in `:app`/`:shell` was read (setKey/evilTwins/inKey in this
     * file, PAD SHEET's treat/mutate/drift/outside/desample paths, pad
     * capture, SPLIT/SURFACE/SYNTH, TAKES + BIN's own `doRestoreTake`,
     * TextureKits/Breed/Crate/StarterKits) and every one sits behind a
     * genuine user mutation or kit creation — none fires from merely
     * opening a kit or playing a pad. `KitBuilderModel.open` itself never
     * calls `.save()`.
     *
     * It also survives the launch sweep untouched, which a directory
     * mtime might not have: `App`'s launch effect runs four sweeps beside
     * `shelf.list()` — `shelf.sweepRooms()`/`shelf.sweepDeletedKits()`/
     * `SnipStore.sweepBin()` each touch only `Rooms/`, the shelf's own
     * top-level `.bin/`, and `snips/.bin/` respectively, none of them a
     * live kit's own directory; `sweepOrphanedStorage` only touches
     * `.landing-*` staging dirs that sit BESIDE kit folders (never inside
     * one) and `cacheDir` subfolders. The one sweep that does open every
     * kit, `KitBuilderModel.open(kitDir).purgeBin()`, only deletes stale
     * files under `kitDir/.bin/` — a SUBdirectory, which bumps `.bin`'s
     * own mtime, never `kitDir`'s or `kit.json`'s — and never calls
     * `.save()` at all (see `ConventionTest`'s own `READ_ONLY` entry for
     * that exact call site). Without that guarantee, recency would
     * scramble on every cold start as the sweep silently re-touched every
     * kit it happened to purge.
     *
     * No second full shelf read either: this is one cheap `lastModified()`
     * stat per kit alongside the [KitStore.load] parse [list] already does
     * for each one, not a second pass over the shelf. Ties (two kits saved
     * in the same millisecond — CHOP ALL, a multi-kit backup import) fall
     * back to `KitStore.list`'s own A-Z order: `sortedByDescending` is a
     * stable sort, so entries that compare equal keep their incoming
     * (already-alphabetical) relative order rather than shuffling.
     */
    fun list(sort: ShelfSort = ShelfSort.RECENT): List<Entry> {
        val entries = KitStore.list(root).mapNotNull { dir ->
            try {
                Entry(dir, KitStore.load(dir))
            } catch (_: Exception) {
                null
            }
        }
        return when (sort) {
            // KitStore.list's own base order is already A-Z by folder name
            // — nothing left to do.
            ShelfSort.ALPHA -> entries
            ShelfSort.RECENT -> entries.sortedByDescending { File(it.dir, KitStore.FILE_NAME).lastModified() }
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
     * INSTANT KIT: [range] of [file] (a snip, or whatever TAPE was scrubbing)
     * chopped with the defaults and landed on the shelf as a kit named after
     * the file — CHOP's own result with nothing touched. Seconds-long; the
     * caller shows a busy line.
     *
     * [file] is `tapeData.sourceFile` from TAPE, so [range] is only ever
     * valid against the capped view TAPE actually showed — reading through
     * [WavReader.readCapped] with the same [TAPE_LOAD_MAX_SEC] reproduces
     * that exact frame count rather than [WavReader.read]'s unbounded
     * whole-file decode. Can throw [OutOfMemoryError]; the caller (`App.kt`'s
     * `instantKit`) catches it separately from `Exception`.
     */
    fun instantKit(file: File, range: IntRange): Pair<Entry, com.snipsnap.shell.InstantKit.Result> {
        val snip = com.snipsnap.shell.InstantKit.slice(WavReader.readCapped(file, TAPE_LOAD_MAX_SEC).snip, range)
        root.mkdirs()
        val name = freshName("${file.nameWithoutExtension} KIT")
        val result = com.snipsnap.shell.InstantKit.build(snip, name, File(root, name))
        return Entry(result.kitDir, result.kit) to result
    }

    /**
     * EVIL TWINS: bank B of [source] becomes seeded FX re-treatments of bank A
     * (`KitBuilderModel.remixBankB`), the slots lit returned with the re-read
     * entry. Seconds-long (every twin renders); the caller shows a busy line.
     */
    fun evilTwins(source: Entry, seed: Int): Pair<Entry, List<Int>> {
        val model = com.snipsnap.shell.KitBuilderModel.open(source.dir)
        val lit = model.remixBankB(seed)
        model.save()
        return Entry(source.dir, model.kit) to lit
    }

    /**
     * BREED (XX2 wired in): [a]'s recipes crossed with [b]'s into a brand
     * new child kit beside [a] on the shelf — `:shell`'s tested
     * `Breed.breed`, unmodified; this only picks the destination. The
     * default name `Breed.breed` itself would use (`"${a.name}_x_${b.name}"`,
     * `BreedCommand`'s own CLI default) run through [freshName] so a second
     * breed of the same pair never collides with the first (`Breed.breed`
     * refuses a destination that already holds a `kit.json`). Both parents
     * stay untouched — `Breed`'s own contract, not this method's. Seconds-
     * long (every crossed pad renders offline); the caller shows a busy line.
     */
    fun breed(a: Entry, b: Entry, seed: Int): Pair<Entry, com.snipsnap.shell.Breed.Report> {
        root.mkdirs()
        val name = freshName("${a.kit.name} X ${b.kit.name}")
        val dir = File(root, name)
        val report = com.snipsnap.shell.Breed.breed(a.dir, b.dir, dir, name, seed)
        return Entry(dir, report.kit) to report
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
     * SHARE: [source] packed as one `.xpn` under [outDir] (the share
     * cache), ready for the chooser. Preflight refuses a broken kit in
     * words, the same way EXPORT would. The file is named through
     * `Names.sanitizeStem`, so a kit name that preflight would refuse
     * anyway can never become a path - the file stays a bare name inside
     * the share cache whatever the name holds.
     */
    fun pack(source: Entry, outDir: File): File {
        val kit = KitStore.load(source.dir)
        outDir.mkdirs()
        val stem = com.snipsnap.kit.Names.sanitizeStem(kit.name)
        return com.snipsnap.kit.XpnPackager.write(
            kit, source.dir, File(outDir, "$stem.xpn"), com.snipsnap.kit.Exporters.defaultMeta(kit), overwrite = true,
        )
    }

    /**
     * BACKUP: every kit on the shelf as one file under [outDir], each its
     * own `.xpn` inside; kits preflight refuses are skipped and named.
     */
    fun backup(outDir: File, nowMillis: Long): com.snipsnap.kit.KitBackup.BackupResult {
        outDir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.ROOT).format(java.util.Date(nowMillis))
        return com.snipsnap.kit.KitBackup.backup(root, File(outDir, "SnipSnap Shelf $stamp.zip"), overwrite = true)
    }

    /**
     * A kit file shared in - a `.xpn`, a backup, an MPC track zipped with
     * its folder - landed on the shelf under names nothing here holds,
     * through `ShelfImport`'s staged door; the entries for what landed,
     * and what was skipped with the reason.
     */
    fun land(file: File, displayName: String): Pair<List<Entry>, List<String>> {
        root.mkdirs()
        val landed = com.snipsnap.shell.ShelfImport.land(file, displayName, root)
        return landed.kits.map { (_, dir) -> Entry(dir, KitStore.load(dir)) } to landed.skipped
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
        // pad.sampleFile is a kit pad sample, produced only by KitBuilderModel.assign
        // from an already-bounded Snip — readCapped's 600s ceiling is defense in
        // depth, not expected to ever bind.
        val snip = WavReader.readCapped(File(source.dir, pad.sampleFile), TAPE_LOAD_MAX_SEC).snip
        root.mkdirs()
        val name = freshName(TextureKits.kitName(pad.displayName, spec))
        val dir = File(root, name)
        val kit = TextureKits.render(name, dir, snip, "${source.kit.name}:${MutateSheet.padTag(slot)}", spec)
        return Entry(dir, kit)
    }

    /**
     * DELETE ▸ BIN: [entry]'s whole folder moves under `Kits/.bin/`, named
     * `<its folder name>-<timestamp>` (a counter inserted *before* the
     * timestamp if the bin somehow already holds that exact pairing) — the
     * same "into the bin, not gone" rule [Rooms.forget] applies to one WAV,
     * here applied to a whole kit directory. `false` when [entry]'s
     * directory is already gone (a stale row, a second delete racing this
     * one) or the move itself fails; the caller's own busy-lock is what
     * keeps two deletes of the *same* row from racing each other at all.
     */
    fun deleteKit(entry: Entry): Boolean {
        if (!entry.dir.isDirectory) return false
        val bin = binDir.apply { mkdirs() }
        val nowMillis = System.currentTimeMillis()
        val base = "${entry.dir.name}-$nowMillis"
        var name = base
        var n = 2
        while (File(bin, name).exists()) {
            name = "${entry.dir.name}-$n-$nowMillis"
            n++
        }
        return moveDir(entry.dir, File(bin, name))
    }

    /**
     * The wall-clock millis [dir] (a directory under [binDir]) was binned,
     * read off the trailing `-<millis>` [deleteKit] always stamps last in
     * the folder's name, NOT `File.lastModified()` — unlike
     * `StorageSweep.kt`'s `.landing-<nanoTime>` (monotonic process time the
     * app deliberately refuses to read as an age), [deleteKit]'s stamp is
     * wall-clock `System.currentTimeMillis()`, so parsing it here is
     * exactly as honest as reading a file's mtime would be. `lastModified()`
     * is only the fallback for a folder that somehow doesn't parse
     * (hand-edited, or from a build that named it differently). Both
     * [sweepDeletedKits] and [binnedKits] read this one helper, so the two
     * can never disagree about when a kit dies.
     */
    private fun binnedAt(dir: File): Long = dir.name.substringAfterLast('-').toLongOrNull() ?: dir.lastModified()

    /**
     * Empties [binDir] of whatever has slept there past [keepDays]; returns
     * how many went — [Rooms.sweepBin]'s own job, for kits.
     */
    fun sweepDeletedKits(keepDays: Double = BIN_DAYS, nowMillis: Long = System.currentTimeMillis()): Int {
        val dirs = binDir.listFiles { f: File -> f.isDirectory } ?: return 0
        val keepMs = (keepDays * DAY_MS).toLong()
        var gone = 0
        for (dir in dirs) {
            if (nowMillis - binnedAt(dir) >= keepMs && dir.deleteRecursively()) gone++
        }
        return gone
    }

    /**
     * Every kit asleep in the bin, the most recently binned first. A
     * directory whose `kit.json` is missing or unreadable is skipped
     * entirely — not shown with a guessed or placeholder name — the same
     * honesty rule [list] already follows for the shelf itself; [name] and
     * [BinnedKit.padCount] always come from that file, NEVER parsed off
     * the folder's name (which [deleteKit] stamps as `<folder>-<millis>`,
     * ambiguous for any kit whose own name holds a hyphen, and outright
     * wrong for the same-millisecond collision form
     * `<folder>-<n>-<millis>`). [BinnedKit.daysLeft] counts down to
     * [sweepDeletedKits], rounded UP so the readout agrees with the sweep
     * that acts on it — a partial day left still reads 1, and 0 only at the
     * boundary where the kit actually goes. This is [Rooms.Binned.daysLeft]'s
     * convention on purpose: a floored count would read "0 DAYS LEFT" for a
     * whole day on a kit you can still restore, and the app's two bins would
     * disagree about the same moment.
     */
    fun binnedKits(nowMillis: Long = System.currentTimeMillis()): List<BinnedKit> {
        val dirs = binDir.listFiles { f: File -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val kit = try {
                KitStore.load(dir)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val binnedAtMillis = binnedAt(dir)
            val left = (binnedAtMillis + (BIN_DAYS * DAY_MS).toLong() - nowMillis).coerceAtLeast(0L)
            val daysLeft = ((left + DAY_MS - 1) / DAY_MS).toInt()
            BinnedKit(dir, kit.name, kit.pads.size, binnedAtMillis, daysLeft)
        }.sortedByDescending { it.binnedAtMillis }
    }

    /**
     * RESTORE: [binned] back onto the shelf at `File(root, <name>)`, where
     * `<name>` is [BinnedKit.name] — read off `kit.json`, never the folder.
     * [BinnedKit.name] already lived on the shelf once, so it's used
     * verbatim ([renameKit]'s own posture toward a name it trusts) unless
     * it somehow isn't [Names.isMpcSafe] (a hand-edited `kit.json`, a name
     * off some other import path) — only then is it forced sane through
     * [Names.sanitizeStem], which would otherwise trim a leading `.`/`_`
     * off a name that was already safe and silently rename it on restore.
     * A collision with a kit already on the shelf falls back exactly like
     * [renameKit]: "NAME 2", "NAME 3"…, through the same [freshShelfName]
     * both share, and `kit.json`'s own name is rewritten to whatever name
     * actually landed so the two never disagree. `null` when [binned]'s
     * directory is already gone (a stale row, a second restore racing this
     * one) or the move itself fails.
     */
    fun restoreKit(binned: BinnedKit): Entry? {
        if (!binned.dir.isDirectory) return null
        root.mkdirs()
        val base = if (Names.isMpcSafe(binned.name)) binned.name else Names.sanitizeStem(binned.name)
        val name = freshShelfName(base)
        val dest = File(root, name)
        if (!moveDir(binned.dir, dest)) return null
        val kit = KitStore.load(dest)
        if (name != kit.name) KitStore.save(kit.copy(name = name), dest)
        return Entry(dest, KitStore.load(dest))
    }

    /**
     * EMPTY BIN: every child of [binDir] gone now, `deleteRecursively()`
     * each — an early sweep the user asked for, not [sweepDeletedKits]'s
     * age check. Returns how many went; 0 without throwing when the bin is
     * empty or was never created. Reads and deletes only inside [binDir] -
     * nothing outside it is ever touched.
     */
    fun emptyKitBin(): Int {
        val children = binDir.listFiles() ?: return 0
        return children.count { it.deleteRecursively() }
    }

    /**
     * RENAME: [entry] under [newName] on the shelf. `null` immediately when
     * [newName] isn't [Names.isMpcSafe] — no partial rename is ever
     * attempted on a name the card couldn't hold — or when [entry]'s
     * directory is already gone. A genuine collision with another kit
     * already on the shelf falls back exactly like
     * [ShelfImport.moveOntoShelf]: "NAME 2", "NAME 3"…, and `kit.json`'s own
     * name is rewritten to match whatever name actually landed, so the two
     * never disagree. Renaming to the name [entry.kit] already carries is a
     * no-op that hands back [entry] unchanged — compared against the kit's
     * OWN name, not the folder's, since the two can already have diverged
     * (the same import-collision-fallback naming that can leave a folder as
     * "NAME 2" while `kit.json` still says "NAME"); comparing against the
     * folder name here would let a rename dialog seeded from [entry.kit]'s
     * name silently perform a real directory move when the user believed
     * they typed back the name they already saw.
     */
    fun renameKit(entry: Entry, newName: String): Entry? {
        if (!Names.isMpcSafe(newName)) return null
        // A kit can't take over one of the shelf's own reserved folders —
        // `Names.isMpcSafe` alone would wave ".bin"/"Instruments"/"Rooms"/
        // "snips" through (only *trailing* dots/spaces are refused), and the
        // collision loop below only bumps a name that already exists, which
        // none of these do on a shelf that's never used them yet.
        if (newName == BIN_DIR || newName == INSTRUMENTS_DIR ||
            newName == Rooms.ROOMS_DIR || newName == SnipStore.DIR || newName.startsWith(ShelfImport.STAGING_DIR)
        ) {
            return null
        }
        if (!entry.dir.isDirectory) return null
        if (newName == entry.kit.name) return entry
        val name = freshShelfName(newName)
        val dest = File(root, name)
        if (!moveDir(entry.dir, dest)) return null
        val kit = KitStore.load(dest)
        if (name != kit.name) KitStore.save(kit.copy(name = name), dest)
        return Entry(dest, KitStore.load(dest))
    }

    /**
     * [base] itself if nothing directly under [root] holds that name yet,
     * else "[base] 2", "[base] 3"… — the collision fallback [renameKit] and
     * [restoreKit] both land a moved kit under, kept as one loop so the two
     * can never disagree about how a collision resolves.
     */
    private fun freshShelfName(base: String): String {
        var name = base
        var n = 2
        while (File(root, name).exists()) {
            name = "$base $n"
            n++
        }
        return name
    }

    /**
     * [from] to [to]: a rename, falling back to copy-then-delete when the
     * rename can't cross filesystems in one step — [ShelfImport.moveOntoShelf]'s
     * own move, generalized to whole directories rather than one landed kit.
     */
    private fun moveDir(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        if (!from.copyRecursively(to, overwrite = false)) {
            to.deleteRecursively()
            return false
        }
        return from.deleteRecursively()
    }
}
