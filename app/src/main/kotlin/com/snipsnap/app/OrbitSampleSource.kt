package com.snipsnap.app

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.loop.KitSampleSource
import com.snipsnap.loop.SampleSource
import com.snipsnap.shell.SnipStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where ORBIT's rings get their audio on the phone.
 *
 * Pads come from the kit shelf exactly as the loop grid reads them — a kit
 * is a folder under [kitsRoot], a pad a bare filename inside it — so the
 * ring can name a kit by its folder and [KitSampleSource] does the rest.
 * Snips are the other half of the shelf: [SnipStore]'s `snips/` folder
 * under [filesDir], which the loop grid never had a reason to read. Both
 * are bare names; a name with a path in it reads nothing.
 *
 * Decoded audio is cached, as [KitSampleSource] caches, because a ring
 * edit prepares a fresh bank and must not re-read the WAV every time.
 */
class OrbitSampleSource(kitsRoot: File, filesDir: File) : SampleSource {

    private val kits = KitSampleSource(kitsRoot)
    private val snipsDir = File(filesDir, SnipStore.DIR)
    private val snips = ConcurrentHashMap<String, Snip>()

    override fun loop(sampleFile: String): Snip? {
        if (sampleFile.isBlank() || '/' in sampleFile || '\\' in sampleFile) return null
        snips[sampleFile]?.let { return it }
        val file = File(snipsDir, sampleFile)
        if (!file.isFile) return null
        // Capped, as every :app read is (ConventionTest): a ring is at most
        // 64 steps, which at the slowest tempo is 24 s, so anything past
        // the cap could only ever be squeezed away by the fit.
        val snip = runCatching { WavReader.readCapped(file, SNIP_MAX_SEC).snip }.getOrNull() ?: return null
        return snips.putIfAbsent(sampleFile, snip) ?: snip
    }

    override fun pad(kit: String, slot: Int): Snip? = kits.pad(kit, slot)

    private companion object {
        /** 64 steps × a 16th at 40 BPM (0.375 s) = 24 s; a little headroom on top. */
        const val SNIP_MAX_SEC = 30f
    }
}
