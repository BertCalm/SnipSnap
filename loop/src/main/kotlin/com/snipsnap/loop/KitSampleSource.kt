package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a session's sample references against its own folder.
 *
 * A session is a directory: loop.json plus the WAVs it names plus the kit
 * folders it plays. Everything is a bare filename resolved from here, the same
 * rule KitPad.sampleFile already enforces, so a session can be copied or
 * shared as one unit and still play.
 *
 * Decoded audio is cached. Baking asks for the same block repeatedly across a
 * long cycle, and a prefetch that re-reads the file every time is a prefetch
 * that misses its deadline.
 */
class KitSampleSource(private val dir: File) : SampleSource {

    private val loops = ConcurrentHashMap<String, Snip>()
    private val pads = ConcurrentHashMap<Pair<String, Int>, Snip>()
    private val kitIndex = ConcurrentHashMap<String, Map<Int, Indexed>>()
    private val groups = ConcurrentHashMap<String, Groups>()

    /** What the index keeps off a pad: where its audio is. */
    private data class Indexed(val sampleFile: String)

    /**
     * Choke groups as of one `kit.json` write, so a later edit is re-read.
     * [settled] records that the file had already stopped being written
     * when this was taken; an unsettled entry is re-read next time.
     */
    private class Groups(val stamp: Long, val settled: Boolean, val bySlot: Map<Int, Int>)

    override fun loop(sampleFile: String): Snip? {
        if (!isBareName(sampleFile)) return null
        loops[sampleFile]?.let { return it }
        val file = File(dir, sampleFile)
        if (!file.isFile) return null
        val snip = runCatching { WavReader.read(file) }.getOrNull() ?: return null
        return loops.putIfAbsent(sampleFile, snip) ?: snip
    }

    override fun pad(kit: String, slot: Int): Snip? {
        if (!isBareName(kit)) return null
        val key = kit to slot
        pads[key]?.let { return it }

        val slots = slotsFor(kit)
        val name = slots[slot]?.sampleFile ?: return null
        val file = File(File(dir, kit), name)
        if (!file.isFile) return null
        val snip = runCatching { WavReader.read(file) }.getOrNull() ?: return null
        return pads.putIfAbsent(key, snip) ?: snip
    }

    /**
     * Unlike the audio and the filename index, this one notices an edit.
     *
     * Those two are cached for the whole life of the source because they
     * feed the bake path, where re-reading a WAV is a missed deadline. A
     * choke group is asked for only by `OrbitBank.prepare`, which is
     * off-thread by definition, so it can afford to check whether
     * `kit.json` has moved under it — and it has to, or toggling choke on
     * a kit's hats would not be heard until the app was restarted.
     */
    override fun muteGroup(kit: String, slot: Int): Int {
        if (!isBareName(kit)) return 0
        val stamp = File(File(dir, kit), KitStore.FILE_NAME).lastModified()
        // A stamp alone cannot separate two writes inside the filesystem's
        // timestamp resolution, so an entry read while the file was still
        // that fresh is not trusted again - the re-reads land only in the
        // second after an edit, which is exactly when they are affordable
        // and exactly when a second edit is likely.
        val settled = stamp != 0L && System.currentTimeMillis() - stamp > SETTLE_MS
        groups[kit]?.let { if (it.stamp == stamp && it.settled) return it.bySlot[slot] ?: 0 }
        val kitDir = File(dir, kit)
        if (!kitDir.isDirectory) return 0
        val loaded = runCatching { KitStore.load(kitDir) }.getOrNull() ?: return 0
        val bySlot = loaded.pads.associate { it.slot to it.muteGroup }
        groups[kit] = Groups(stamp, settled, bySlot)
        return bySlot[slot] ?: 0
    }

    /**
     * slot -> pad for [kit], cached only once the kit has actually
     * loaded. A kit that doesn't exist yet, or whose kit.json fails to parse,
     * must not poison the index forever: the kit can be written (or fixed)
     * later in the session, and the next lookup has to see it.
     */
    private fun slotsFor(kit: String): Map<Int, Indexed> {
        kitIndex[kit]?.let { return it }
        val indexed = indexKit(kit) ?: return emptyMap()
        return kitIndex.putIfAbsent(kit, indexed) ?: indexed
    }

    /** slot -> pad for [kit], or null if the kit can't be loaded right now. */
    private fun indexKit(kit: String): Map<Int, Indexed>? {
        val kitDir = File(dir, kit)
        if (!kitDir.isDirectory) return null
        val loaded = runCatching { KitStore.load(kitDir) }.getOrNull() ?: return null
        return loaded.pads.associate { it.slot to Indexed(it.sampleFile) }
    }

    /**
     * A reference must name something inside this folder and nothing else.
     * loop.json is a file on a phone; it can be edited, synced or corrupted,
     * and a traversal should read nothing rather than something.
     */
    private companion object {
        /** How long after a write a stamp is treated as able to hide a second one. */
        const val SETTLE_MS = 2_000L
    }

    private fun isBareName(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name && name != ".." && name != "."
}
