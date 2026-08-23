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
    private val kitIndex = ConcurrentHashMap<String, Map<Int, String>>()

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

        val slots = kitIndex.getOrPut(kit) { indexKit(kit) }
        val name = slots[slot] ?: return null
        val file = File(File(dir, kit), name)
        if (!file.isFile) return null
        val snip = runCatching { WavReader.read(file) }.getOrNull() ?: return null
        return pads.putIfAbsent(key, snip) ?: snip
    }

    /** slot -> bare filename, read once per kit. */
    private fun indexKit(kit: String): Map<Int, String> {
        val kitDir = File(dir, kit)
        if (!kitDir.isDirectory) return emptyMap()
        val loaded = runCatching { KitStore.load(kitDir) }.getOrNull() ?: return emptyMap()
        return loaded.pads.associate { it.slot to it.sampleFile }
    }

    /**
     * A reference must name something inside this folder and nothing else.
     * loop.json is a file on a phone; it can be edited, synced or corrupted,
     * and a traversal should read nothing rather than something.
     */
    private fun isBareName(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name && name != ".." && name != "."
}
