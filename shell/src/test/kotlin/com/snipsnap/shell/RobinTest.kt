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
    fun `the grid - zones x takes graded soft to hard, undo byte-identical`() {
        val m = model("Grid")
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val originalBytes = padFile.readBytes()
        val original = WavReader.read(padFile)

        val pad = Robin.apply(m, 1, takes = 2, seed = 5, zones = 3)
        m.save()
        val chain = pad.chain!!
        assertEquals(6, chain.sliceCount, "3 zones x 2 takes")
        val zones = chain.zones!!
        assertEquals(3, zones.size)
        assertEquals(0, zones[0].velStart)
        assertEquals(127, zones[2].velEnd)
        assertEquals(0, zones[0].baseSlice)
        assertEquals(4, zones[2].baseSlice, "zone anchors step by takes")
        assertEquals(2, zones[1].cycle)

        val chained = WavReader.read(padFile)
        fun slice(i: Int): FloatArray {
            val w = chain.window(i, chained.frameCount.toLong())
            return chained.samples.copyOfRange(w.first.toInt(), (w.last + 1).toInt())
        }
        // The top zone's anchor (slice 4) is the pristine original.
        val top = slice(4)
        assertEquals(original.frameCount, top.size)
        for (i in top.indices) {
            assertTrue(
                abs(top[i] - original.samples[i]) <= 2f / 32767f,
                "the hard anchor strays from the original at $i",
            )
        }
        // The soft zone's anchor is quieter AND darker than the hard one.
        fun rms(x: FloatArray): Double {
            var acc = 0.0
            for (v in x) acc += v * v.toDouble()
            return Math.sqrt(acc / x.size.coerceAtLeast(1))
        }
        fun roughness(x: FloatArray): Double {
            var acc = 0.0
            for (i in 1 until x.size) acc += Math.abs(x[i] - x[i - 1]).toDouble()
            return acc / (x.size - 1).coerceAtLeast(1) / rms(x).coerceAtLeast(1e-9)
        }
        val soft = slice(0)
        assertTrue(rms(soft) < rms(top) * 0.75, "the soft anchor plays quieter: ${rms(soft)} vs ${rms(top)}")
        assertTrue(
            roughness(soft) < roughness(top) * 0.9,
            "the soft anchor plays darker: ${roughness(soft)} vs ${roughness(top)}",
        )

        // A grid kit still exports natively, then undo is byte-identical.
        com.snipsnap.kit.Mpc3Exporter.exportTrack(m.kit, m.kitDir, File(temp, "grid-card"))
        val undone = Robin.undo(m, 1)
        assertEquals(null, undone.chain)
        assertTrue(padFile.readBytes().contentEquals(originalBytes), "undo restores the single take")

        assertFailsWith<IllegalArgumentException>("zones range") { Robin.apply(m, 1, zones = 5) }
        assertFailsWith<IllegalArgumentException>("zones range") { Robin.apply(m, 1, zones = 1) }
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
