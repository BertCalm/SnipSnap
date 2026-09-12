package com.snipsnap.app

import java.io.File

/**
 * Orphaned staging from a crashed/killed import, and regenerable derived
 * output (share-sheet temp copies) — never a live kit or snip. Age is
 * judged by [File.lastModified], NOT any name-embedded timestamp:
 * `ShelfImport`'s ".landing-<nanoTime>" suffix is monotonic process time,
 * not wall-clock, so it cannot be parsed as an age.
 *
 * `ShelfImport.land` always cleans its own staging folder in a `finally`
 * block, success or refusal alike — the only way one of these survives to
 * be swept is the process dying mid-import (killed, crashed, OOM) before
 * that `finally` runs. A staging folder from an import still genuinely in
 * progress is, at most, a few seconds old; [ORPHAN_MAX_AGE_MS] gives it a
 * full day of headroom before it's considered abandoned.
 *
 * Exports (`Exports.dir`) are deliberately NOT swept
 * here: a user exports an `.xpn` specifically to keep or move it
 * elsewhere, so it is the user's file now, not app-internal derived
 * cruft — sweeping it would delete something they asked to KEEP, even
 * though nothing in-app currently surfaces or manages it.
 */
internal const val ORPHAN_MAX_AGE_MS = 24L * 60 * 60 * 1000 // 1 day — a crashed import, not an in-progress one

/**
 * Sweeps [shelfRoot] (where `ShelfImport` stages a landing kit, ".landing-*"
 * beside the shelf's own kit folders) and [cacheDir]'s [ShareInbox.DIR]
 * (ShareInbox's staging) and [ShareOut.DIR] (share-sheet temp copies)
 * subfolders of anything stale. Called once per launch, off the main
 * thread, beside `shelf.sweepRooms()` and the bin purge.
 */
internal fun sweepOrphanedStorage(shelfRoot: File, cacheDir: File) {
    val now = System.currentTimeMillis()
    fun deleteIfStale(entry: File) {
        if (entry.exists() && now - entry.lastModified() > ORPHAN_MAX_AGE_MS) {
            entry.deleteRecursively()
        }
    }
    // ShelfImport's crashed-mid-import staging dirs — under the shelf root,
    // beside the kit folders it stages into (see ShelfImport.land).
    shelfRoot.listFiles { f -> f.isDirectory && f.name.startsWith(".landing-") }?.forEach(::deleteIfStale)
    // ShareInbox's staging — a flat file per shared item (see
    // ShareInbox.copyToCache), not a directory, so no isDirectory filter here.
    File(cacheDir, ShareInbox.DIR).listFiles()?.forEach(::deleteIfStale)
    // Share-sheet temp copies — fully regenerable on next share.
    File(cacheDir, ShareOut.DIR).listFiles()?.forEach(::deleteIfStale)
}
