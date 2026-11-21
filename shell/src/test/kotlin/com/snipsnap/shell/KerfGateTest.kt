package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.RobinSettings
import com.snipsnap.kit.isRobinChain
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.SfzImporter
import com.snipsnap.kit.SfzWriter
import com.snipsnap.kit.blocked
import com.snipsnap.synth.KerfBank
import com.snipsnap.synth.KerfRender
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KERF against the owner's velocity and round-robin gate (docs/ENGINE_ACCEPTANCE_GATES.md): working velocity levels
 * and round robin through rendering, saving, playback and export, with velocity a change of strike character and the
 * takes distinct strikes. Through the app's own doors: SOFT HITS (`addGhostLayers`), the velocity × round-robin grid
 * (`Robin.apply` with zones) and the kit's round robin (`Robin.apply` with settings).
 *
 * The "captures" are renders of the fitted bank's kit B, written as WAVs: real KERF material, and no pack audio.
 */
class KerfGateTest {
    private val temp: File = java.nio.file.Files.createTempDirectory("kerf-gate").toFile()

    @AfterTest fun cleanUp() { temp.deleteRecursively() }

    /** Kit B's kick, snare, ghost and hat as a four-pad kit, every pad made a twin, so each has both neighbours. */
    private fun twins(name: String): KitBuilderModel {
        val dir = File(temp, name).apply { mkdirs() }
        val bank = KerfBank.kits.getValue("B")
        val picks = listOf(1, 5, 9, 11)
        val pads = picks.mapIndexed { i, n ->
            val p = bank.first { it.pad == n }
            WavWriter.write(File(dir, "hit${i + 1}.wav"), KerfRender.toSnip(KerfRender.render(p.patch)))
            KitPad(slot = i + 1, sampleFile = "hit${i + 1}.wav", drumClass = DrumClass.PERC)
        }
        KitStore.save(Kit(name = name, pads = pads, tempoBpm = 92f), dir)
        return KitBuilderModel.open(dir).apply { kerfKit(); save() }
    }

    private fun centroid(x: FloatArray): Double {
        val e = KerfRender.bandEnergies(x); var w = 0.0; var t = 1e-30
        for (b in 0 until KerfRender.BAND_COUNT) { w += KerfRender.bandCentreHz(b) * e[b + 1]; t += e[b + 1] }
        return w / t
    }
    private fun slices(m: KitBuilderModel, slot: Int): List<FloatArray> {
        val pad = m.pad(slot)!!; val chain = pad.chain!!
        val wav = WavReader.read(File(m.kitDir, pad.sampleFile))
        return (0 until chain.sliceCount).map { i -> chain.window(i, wav.frameCount.toLong()).let { wav.samples.copyOfRange(it.first.toInt(), (it.last + 1).toInt()) } }
    }

    @Test fun `SOFT HITS on a twin are the twin struck softer from its numbers, not the main take filtered`() {
        val m = twins("soft")
        val snare = 2
        val main = WavReader.read(File(m.kitDir, m.pad(snare)!!.sampleFile))
        val kerf = Breed.recipeOf(m.pad(snare)!!)!!.kerf!!
        m.addGhostLayers(snare, softZones = 2); m.save()
        val pad = KitStore.load(m.kitDir).pad(snare)!!
        assertEquals(3, pad.velocityLayers.size)
        val (softest, mid) = pad.velocityLayers.take(2).map { WavReader.read(File(m.kitDir, it.sampleFile)) }
        // the KERF door, byte for byte: the twin re-struck at 0.3 and 0.6, peak-matched to the main take
        val expected = com.snipsnap.synth.Velocity.kerfLayerAt(main, kerf, null, 0.3f).samples
        assertEquals(expected.size, softest.samples.size)
        assertTrue(expected.indices.all { abs(expected[it] - softest.samples[it]) <= 2e-4f }, "the softest layer is the twin struck at 0.3, as written to the WAV")
        assertFalse(com.snipsnap.synth.Velocity.soften(main, 0.7f).samples.contentEquals(softest.samples), "and not the main take filtered")
        val c = listOf(softest, mid, main).map { centroid(it.samples) }
        println("KERF SOFT HITS centroid soft, mid, main: ${c.map { it.toInt() }} Hz")
        // softer is duller on this pad; over the whole bank the median is (KerfVelocityTest), and a pad among bright
        // neighbours may not be, since the wash it sits deeper in is theirs
        assertTrue(c[0] < c[2], "the softest layer is duller than the main take: $c")
        assertTrue(PadSheet.isKerfTwin(pad), "the twin is still the pad's source")
        assertEquals(Copy.UNDO_LAYERED, PadSheet.kerfUndoRefusal(pad, binned = true), "the twin's undo waits for SOFT HITS off")
    }

    @Test fun `a twin's velocity by round-robin grid holds through save, playback selection and export`() {
        val m = twins("grid")
        val snare = 2
        val twinBytes = File(m.kitDir, m.pad(snare)!!.sampleFile).readBytes()
        Robin.apply(m, snare, takes = 4, seed = 2, zones = 3); m.save()
        val reopened = KitBuilderModel.open(m.kitDir)
        val pad = reopened.pad(snare)!!; val chain = assertNotNull(pad.chain)
        assertEquals(12, chain.sliceCount); assertEquals(3, chain.zones!!.size)

        // rendering: every slice a different strike, the soft zone duller than the loud one
        val s = slices(reopened, snare)
        for (i in s.indices) for (j in i + 1 until s.size) assertFalse(s[i].contentEquals(s[j]), "slices $i and $j are distinct")
        val zoneCentroid = (0 until 3).map { z -> (0 until 4).map { centroid(s[z * 4 + it]) }.average() }
        println("KERF grid zone centroids soft to hard: ${zoneCentroid.map { it.toInt() }} Hz")
        assertTrue(zoneCentroid[0] < zoneCentroid[1] && zoneCentroid[1] < zoneCentroid[2], "velocity is character: $zoneCentroid")
        assertTrue(twinBytes.size > 0)

        // playback: every velocity picks its zone and every hit steps through the zone's four takes
        val wav = WavReader.read(File(reopened.kitDir, pad.sampleFile))
        for (v in 1..127) for (hit in 0..8) {
            val h = assertNotNull(PadHit.resolve(pad, v / 127f, hit) { wav.frameCount.toLong() })
            val zone = assertNotNull(chain.zoneFor(v))
            val w = chain.window(zone.baseSlice + hit % 4, wav.frameCount.toLong())
            assertEquals(w.first, h.startFrame); assertEquals(w.last + 1, h.endFrameExclusive)
        }

        // export: MPC 2 and MPC 3 read back clean; SFZ round-trips the chain and the bytes
        for (format in listOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.MPC3_TRACK)) {
            val exported = Exporters.export(format, reopened.kit, reopened.kitDir, File(temp, "export-${format.id}"))
            assertFalse(ExportReadBack.verify(reopened.kit, exported).blocked(), "${format.id} reads back")
        }
        val sfz = SfzWriter.write(reopened.kit, reopened.kitDir, File(temp, "sfz"))
        val imported = SfzImporter.import(sfz, File(temp, "sfz-import"))
        val back = imported.kit.pads.single { it.slot == snare }
        assertEquals(chain, back.chain)
        assertContentEquals(File(reopened.kitDir, pad.sampleFile).readBytes(), File(imported.directory, back.sampleFile).readBytes())

        // and the grid comes off: the twin back, byte for byte
        Robin.undo(reopened, snare); reopened.save()
        assertContentEquals(twinBytes, File(reopened.kitDir, pad.sampleFile).readBytes())
    }

    @Test fun `the kit's round robin deals a twin's takes from its numbers and keeps it a twin, which BLEED, SLOP and its undo then respect`() {
        val m = twins("robin")
        val snare = 2
        val capture = WavReader.read(File(m.kitDir, m.pad(snare)!!.sampleFile)).let { m.priorTakes(snare) }   // the capture sits in the bin
        val captureBytes = KerfRender.toSnip(KerfRender.render(KerfBank.kits.getValue("B").first { it.pad == 5 }.patch)).samples
        Robin.apply(m, snare, RobinSettings(amount = 0.5f, takes = 4, seed = 1)); m.save()
        var pad = KitStore.load(m.kitDir).pad(snare)!!
        assertTrue(pad.isRobinChain)
        assertTrue(PadSheet.isKerfTwin(pad), "a twin under round robin is still a twin: recipe and stamp kept")
        val s = slices(m, snare)
        for (i in s.indices) for (j in i + 1 until s.size) assertFalse(s[i].contentEquals(s[j]), "takes $i and $j are distinct")

        // BLEED under round robin: the chain is lifted, the twin re-cut, the takes dealt again from the new cut
        val before = File(m.kitDir, pad.sampleFile).readBytes()
        KitBuilderModel.open(m.kitDir).apply { recutKerfPad(snare, bleed = 2.0, slop = 0.5); save() }
        pad = KitStore.load(m.kitDir).pad(snare)!!
        assertTrue(pad.isRobinChain, "still a round robin"); assertEquals(2.0, Breed.recipeOf(pad)!!.kerf!!.bleed)
        assertFalse(before.contentEquals(File(m.kitDir, pad.sampleFile).readBytes()), "the takes are the new cut's")

        // the twin's undo under round robin: the capture comes back as take one, the round robin dealt from it
        KitBuilderModel.open(m.kitDir).apply { unkerfPad(snare); save() }
        pad = KitStore.load(m.kitDir).pad(snare)!!
        assertNull(pad.recipe); assertFalse(KitBuilderModel.KERF_STAMP in pad.source)
        assertTrue(pad.isRobinChain, "the round robin stays on the pad")
        val takeOne = slices(KitBuilderModel.open(m.kitDir), snare)[0]
        assertEquals(captureBytes.size, takeOne.size)
        assertTrue(takeOne.indices.all { abs(takeOne[it] - captureBytes[it]) <= 2e-4f }, "take one is the capture")
        assertTrue(capture.isNotEmpty())
    }

    @Test fun `a round-robin capture becomes a twin measured from its first take, not from the chain`() {
        val dir = File(temp, "rr-capture").apply { mkdirs() }
        val p = KerfBank.kits.getValue("B").first { it.pad == 5 }
        val hit = KerfRender.toSnip(KerfRender.render(p.patch))
        WavWriter.write(File(dir, "hit1.wav"), hit)
        KitStore.save(Kit(name = "RR", pads = listOf(KitPad(slot = 1, sampleFile = "hit1.wav")), tempoBpm = 92f), dir)
        KitBuilderModel.open(dir).apply { Robin.apply(this, 1, RobinSettings(amount = 0.5f, takes = 3, seed = 0)); save() }
        KitBuilderModel.open(dir).apply { kerfPad(1); save() }
        val pad = KitStore.load(dir).pad(1)!!
        assertTrue(pad.isRobinChain && PadSheet.isKerfTwin(pad))
        val measured = Breed.recipeOf(pad)!!.kerf!!.patch
        assertEquals(hit.frameCount.toDouble() / KerfRender.RATE, measured.seconds, 1.0 / KerfRender.RATE, "measured one take, not three end to end")
    }
}
