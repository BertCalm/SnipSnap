package com.snipsnap.shell

import com.snipsnap.audio.Dust
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitPad
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DUST's shell side: the print cached beside its tape, the tape a pad dusts from, and the pad door (docs/DUST.md). */
class DustPrintsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("dust").toFile()
    private val snips = File(temp, SnipStore.DIR).apply { mkdirs() }
    private val rate = 44_100

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    /** A tape with a room after every hit over a steady floor — the same shape DustTest measures. */
    private fun tapeSnip(seconds: Float = 5f, clicks: Int = 0): Snip {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val r = java.util.Random(7L)
        val r2 = java.util.Random(11L)
        for (i in 0 until n) out[i] = (r.nextGaussian().toFloat() * 0.5f).coerceIn(-1f, 1f) * 0.00316f
        for (h in 0 until 8) {
            val at = (0.4f * rate).toInt() + h * (rate / 2)
            for (i in 0 until (0.08f * rate).toInt()) if (at + i < n) out[at + i] += 0.8f * sin(2.0 * Math.PI * 60.0 * i / rate).toFloat() * exp(-i / (0.02f * rate))
            for (i in 0 until (0.4f * rate).toInt()) if (at + i < n) out[at + i] += 0.25f * (r2.nextGaussian().toFloat() * 0.5f).coerceIn(-1f, 1f) * exp(-i / (0.12f * rate))
        }
        for (c in 0 until clicks) {
            val at = (0.4f * rate).toInt() + c * (rate / 2) + (0.3f * rate).toInt()
            if (at < n) out[at] = 0.6f
        }
        return Snip(out, 1, rate)
    }

    private fun tapeOnShelf(name: String = "snip_1000_ROOM 5.wav", clicks: Int = 0): File =
        File(snips, name).also { WavWriter.write(it, tapeSnip(clicks = clicks)) }

    private fun rms(a: FloatArray) = sqrt(a.fold(0.0) { acc, v -> acc + v.toDouble() * v } / a.size).toFloat()

    @Test
    fun `a print is made once, kept beside the tape, and read back to the same contract`() {
        val tape = tapeOnShelf(clicks = 4)
        val first = assertNotNull(DustPrints.forTape(tape))
        val dir = File(snips, DustPrints.DIR)
        val crackleFile = File(dir, "${tape.name}.crackle.wav")
        assertTrue(File(dir, "${tape.name}.hiss.wav").isFile && File(dir, "${tape.name}.room.wav").isFile && crackleFile.isFile, "cached beside the tape")
        val again = assertNotNull(DustPrints.forTape(tape))
        // Off disk, re-levelled: unit RMS hiss, unit-L1 room, unit-peak grains, and the same shape as the fresh print.
        assertEquals(1f, rms(again.hiss.samples), 0.02f)
        assertEquals(1f, again.room.samples.fold(0.0) { a, v -> a + abs(v) }.toFloat(), 0.02f)
        assertEquals(first.room.frameCount, again.room.frameCount)
        var diff = 0.0
        for (i in first.room.samples.indices) diff += abs(first.room.samples[i] - again.room.samples[i])
        assertTrue(diff / first.room.frameCount < 1e-4, "24-bit round trip keeps the room: $diff")
        assertEquals(4, first.grains, "the planted clicks are the crackle")
        assertEquals(first.grains, again.grains)
        var cdiff = 0.0
        for (i in first.crackle.samples.indices) cdiff += abs(first.crackle.samples[i] - again.crackle.samples[i])
        assertTrue(cdiff / first.crackle.frameCount < 1e-4, "24-bit round trip keeps the crackle: $cdiff")
        // A cache from before CRACKLE — two files, no third — is remade, not trusted.
        assertTrue(crackleFile.delete())
        assertEquals(4, assertNotNull(DustPrints.forTape(tape)).grains)
        assertTrue(crackleFile.isFile, "the third file is back")
        // A clean tape's crackle file is a placeholder that reads back as no grains.
        val clean = tapeOnShelf("snip_1500_CLEAN.wav")
        assertEquals(0, assertNotNull(DustPrints.forTape(clean)).grains)
        assertEquals(0, assertNotNull(DustPrints.forTape(clean)).grains, "and again off the cache")
        assertNull(DustPrints.forTape(File(snips, "not_there.wav")))
        // The print follows its tape off the shelf, and off a rename.
        val renamed = assertNotNull(SnipStore.rename(tape, "ROOM SIX"))
        assertTrue(!File(dir, "${tape.name}.room.wav").exists(), "a renamed tape's print is forgotten")
        assertNotNull(DustPrints.forTape(renamed))
        assertTrue(File(dir, "${renamed.name}.room.wav").isFile)
        assertTrue(SnipStore.delete(renamed))
        assertTrue(!File(dir, "${renamed.name}.room.wav").exists() && !File(dir, "${renamed.name}.hiss.wav").exists() && !File(dir, "${renamed.name}.crackle.wav").exists(), "a binned tape's print is forgotten")
        // A silent tape has no hits and no dust, and leaves no cache behind.
        val silent = File(snips, "snip_2000_SILENT.wav").also { WavWriter.write(it, Snip(FloatArray(rate * 2), 1, rate)) }
        assertNull(DustPrints.forTape(silent))
        assertTrue(!File(dir, "${silent.name}.room.wav").exists())
    }

    @Test
    fun `a pad dusts from its own tape, else the kit's, else nowhere`() {
        val own = KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", source = Retrim.tag("mine.wav", 0, 10))
        val other = KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", source = Retrim.tag("theirs.wav", 0, 10))
        val also = KitPad(slot = 3, sampleFile = "A03_Hat_01.wav", source = Retrim.tag("theirs.wav", 20, 30))
        val mic = KitPad(slot = 4, sampleFile = "A04_Perc_01.wav")
        val kit = com.snipsnap.kit.Kit("K", listOf(own, other, also, mic))
        assertEquals("theirs.wav", DustPrints.kitTape(kit), "the tape most of the kit came off")
        assertEquals("mine.wav", DustPrints.tapeFor(kit, own))
        assertEquals("theirs.wav", DustPrints.tapeFor(kit, mic), "a capture borrows the kit's tape")
        assertNull(DustPrints.kitTape(com.snipsnap.kit.Kit("M", listOf(mic))))
        assertNull(DustPrints.tapeFor(com.snipsnap.kit.Kit("M", listOf(mic)), mic))
        // A hand-edited kit.json naming a path, not a tape: no tape at all,
        // so nothing is read or cached outside the shelf.
        val forged = KitPad(slot = 5, sampleFile = "A05_Perc_01.wav", source = mapOf(Retrim.FILE_KEY to "../../secret.wav"))
        assertNull(DustPrints.tapeFor(com.snipsnap.kit.Kit("F", listOf(forged)), forged))
        assertNull(DustPrints.kitTape(com.snipsnap.kit.Kit("F", listOf(forged, forged.copy(slot = 6)))))
        assertEquals("theirs.wav", DustPrints.tapeFor(com.snipsnap.kit.Kit("F", listOf(forged, other)), forged), "a forged own tape falls back to the kit's")
        // And a pasted recipe naming a path refuses as gone before any file is opened by it.
        val recipe = com.snipsnap.json.JsonValue.Obj(mapOf(
            "verb" to com.snipsnap.json.JsonValue.Str("dust"),
            "amount" to com.snipsnap.json.JsonValue.Num(0.5),
            "tape" to com.snipsnap.json.JsonValue.Str("../../secret.wav"),
        ))
        assertEquals(RecipeReplay.Plan.Refused(Copy.dustTapeGone("../../secret.wav")), RecipeReplay.plan(recipe))
    }

    @Test
    fun `dustPad is bin-backed, restores first, comes off at zero, and replays through DO IT AGAIN`() {
        val tape = tapeOnShelf()
        val print = assertNotNull(DustPrints.forTape(tape))
        val dir = File(temp, "Dusty")
        val m = KitBuilderModel.create("Dusty", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        val before = File(dir, m.pad(1)!!.sampleFile).readBytes()

        val dusted = m.dustPad(1, 0.6f, tape.name, print)
        assertEquals(PadSheet.Dusted(0.6f, tape.name), PadSheet.readDust(dusted.recipe))
        assertEquals("DUST", Retrim.treatmentLeft(dusted))
        val once = File(dir, dusted.sampleFile).readBytes()
        assertTrue(!once.contentEquals(before), "the file changed")
        assertEquals(PadSheet.UnTreat.READY, PadSheet.unTreatState(dusted, m.binContents().map { it.originalName }.toSet()), "the original sleeps in the bin")

        // Dusting again restores first: the same bytes as dusting the original once, never a stack.
        m.dustPad(1, 0.6f, tape.name, print)
        assertTrue(File(dir, m.pad(1)!!.sampleFile).readBytes().contentEquals(once), "restore-first, byte for byte")

        // AMT 0 takes it off; AMT 0 on an undusted pad touches nothing.
        val off = m.dustPad(1, 0f, tape.name, print)
        assertNull(PadSheet.readDust(off.recipe))
        assertTrue(File(dir, off.sampleFile).readBytes().contentEquals(before), "back to the original")
        val still = m.dustPad(1, 0f, tape.name, print)
        assertNull(still.recipe)
        assertTrue(File(dir, still.sampleFile).readBytes().contentEquals(before))

        // DO IT AGAIN: the recipe names the tape, and replays when the shelf can find it.
        val again = m.dustPad(1, 0.6f, tape.name, print)
        assertEquals(RecipeReplay.Plan.Dust(0.6f, tape.name), RecipeReplay.plan(again.recipe))
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        val done = RecipeReplay.apply(m, 2, again.recipe!!, "A02") { name -> DustPrints.forTape(File(snips, name)) }
        assertEquals(PadSheet.Dusted(0.6f, tape.name), PadSheet.readDust(done.pad.recipe))
        val gone = kotlin.test.assertFailsWith<IllegalArgumentException> { RecipeReplay.apply(m, 2, again.recipe!!, "A02") { null } }
        assertEquals(Copy.dustTapeGone(tape.name), gone.message)
        m.save()
        assertEquals(m.kit, com.snipsnap.kit.KitStore.load(dir))
    }
}
