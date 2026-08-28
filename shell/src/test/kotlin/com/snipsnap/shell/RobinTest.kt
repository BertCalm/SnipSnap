package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RobinTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("robin").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(2, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        return m
    }

    @Test
    fun `robin renders a chain - take one untouched, the rest subtle`() {
        val m = model("Robin")
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val original = WavReader.read(padFile)

        val pad = Robin.apply(m, 1, takes = 3, seed = 7)
        m.save()

        val chain = pad.chain!!
        assertEquals(3, chain.sliceCount)
        assertEquals(3, chain.cycle)
        assertEquals(0L, chain.boundaries[0])
        assertEquals(original.frameCount.toLong(), chain.boundaries[1], "take one is the original, whole")

        val chained = WavReader.read(padFile)
        assertTrue(chained.frameCount > original.frameCount * 2, "three takes ride in one WAV")
        // Take one is the original verbatim - the fallback both hardware
        // generations play until chains carry their slice map. (Within the
        // 16-bit container's own requantization: the floats are copied
        // untouched, the WAV write is what rounds.)
        for (i in 0 until original.samples.size) {
            assertTrue(
                abs(original.samples[i] - chained.samples[i]) <= 2f / 32767f,
                "take one differs at sample $i: ${original.samples[i]} vs ${chained.samples[i]}",
            )
        }
        // The other takes are near the original in length and level, but
        // not identical to it: subtle is the contract, identical is a bug.
        for (slice in 1 until 3) {
            val w = chain.window(slice, chained.frameCount.toLong())
            val len = (w.last - w.first + 1).toInt()
            assertTrue(
                abs(len - original.frameCount) < original.frameCount * 0.02 + 1,
                "take ${slice + 1} length $len strays from ${original.frameCount}",
            )
            var differs = false
            for (i in 0 until minOf(len, original.frameCount)) {
                if (chained.samples[(w.first.toInt() + i)] != original.samples[i]) {
                    differs = true
                    break
                }
            }
            assertTrue(differs, "take ${slice + 1} must not be a plain copy")
        }
    }

    @Test
    fun `the same seed renders the same chain and undo is byte-identical`() {
        val a = model("SeedA")
        val b = model("SeedB")
        val fileA = File(a.kitDir, a.pad(1)!!.sampleFile)
        val fileB = File(b.kitDir, b.pad(1)!!.sampleFile)
        val originalBytes = fileA.readBytes()

        Robin.apply(a, 1, takes = 4, seed = 42)
        Robin.apply(b, 1, takes = 4, seed = 42)
        assertTrue(fileA.readBytes().contentEquals(fileB.readBytes()), "seeded means reproducible")
        assertEquals(a.pad(1)!!.chain, b.pad(1)!!.chain)

        val undone = Robin.undo(a, 1)
        assertEquals(null, undone.chain)
        assertEquals(null, undone.recipe)
        assertTrue(fileA.readBytes().contentEquals(originalBytes), "undo restores the single take byte-identical")
    }

    @Test
    fun `the guards hold - no door rewrites a chain and robin refuses bad shapes`() {
        val m = model("Guards")
        Robin.apply(m, 1, takes = 2, seed = 1)

        assertFailsWith<IllegalArgumentException>("re-robin refused") { Robin.apply(m, 1) }
        assertFailsWith<IllegalArgumentException>("treat refused") { m.treatPad(1, "crushed") }
        assertFailsWith<IllegalArgumentException>("era refused") { m.eraPad(1, "sp1200") }
        assertFailsWith<IllegalArgumentException>("untreat refused") { m.untreatPad(1) }
        assertFailsWith<IllegalArgumentException>("takes range") { Robin.apply(m, 2, takes = 1) }
        assertFailsWith<IllegalArgumentException>("takes range") { Robin.apply(m, 2, takes = 9) }
        assertFailsWith<IllegalArgumentException>("no such pad") { Robin.apply(m, 5) }
        assertFailsWith<IllegalArgumentException>("nothing to undo") { Robin.undo(m, 2) }

        // The un-chained pad's doors still work.
        m.treatPad(2, "crushed")
    }

    @Test
    fun `a robin'd kit still saves, previews and exports`() {
        val m = model("Ship")
        Robin.apply(m, 1, takes = 3, seed = 3)
        m.save()

        val loaded = com.snipsnap.kit.KitStore.load(m.kitDir)
        assertEquals(m.kit, loaded, "the chain survives the sidecar")

        val clip = com.snipsnap.mpc3.Mpc3Clip(
            "Hits", 1,
            (0 until 3).map { com.snipsnap.mpc3.Mpc3Note(36, it * 960L, 0.9f) },
        )
        val preview = com.snipsnap.kit.KitPreview.render(loaded, m.kitDir, clip = clip)
        assertTrue(preview.frameCount > 0)

        val card = File(temp, "ship-card")
        com.snipsnap.kit.Mpc3Exporter.exportTrack(loaded, m.kitDir, card)
        assertTrue(card.walkTopDown().any { it.extension == "xtd" }, "a chained kit exports")
    }
}
