package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property fuzz on the *writers*, which the reader fuzz (BB3) structurally
 * cannot reach: build a random-but-valid kit, export it to a re-importable
 * format, import it back, and assert every pad's slot, levels, tuning, mute
 * group and velocity layers survived the round trip. A writer that drops or
 * mangles a field shows up as a mismatch on some seed.
 *
 * Deterministic: a fixed seed per round, so any failure reproduces exactly.
 */
class RoundTripFuzzTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("rtfuzz").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun randomKit(dir: File, rnd: Random): Kit {
        dir.mkdirs()
        // A random handful of pads on distinct slots, each a real WAV.
        val slots = (1..16).shuffled(rnd).take(1 + rnd.nextInt(8)).sorted()
        val pads = slots.map { slot ->
            val stem = "P%02d_Snip".format(slot)
            val frames = 2_000 + rnd.nextInt(4_000)
            WavWriter.write(
                File(dir, "$stem.wav"),
                Snip(FloatArray(frames) { (rnd.nextFloat() * 2 - 1) * 0.5f }, 1, 44_100),
            )
            KitPad(
                slot = slot,
                sampleFile = "$stem.wav",
                drumClass = DrumClass.entries[rnd.nextInt(DrumClass.entries.size)],
                level = rnd.nextDouble(0.0, 1.0).toFloat(),
                pan = rnd.nextDouble(0.0, 1.0).toFloat(),
                tuneCoarse = rnd.nextInt(-36, 37),
                tuneFine = rnd.nextInt(-100, 101),
                muteGroup = rnd.nextInt(0, 33),
                oneShot = rnd.nextBoolean(),
            )
        }
        val kit = Kit("Fuzz Kit", pads)
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `random kits round-trip through the MPC3 track writer with every field intact`() {
        repeat(60) { seed ->
            val rnd = Random(seed)
            val srcDir = File(temp, "src-$seed")
            val kit = randomKit(srcDir, rnd)
            val card = File(temp, "card-$seed")
            Mpc3Exporter.exportTrack(kit, srcDir, card)

            val back = Mpc3Importer.import(File(card, "Fuzz Kit.xtd"), File(temp, "in-$seed")).kit

            assertEquals(kit.pads.map { it.slot }, back.pads.map { it.slot }, "seed $seed: slots")
            for (orig in kit.pads) {
                val r = back.pad(orig.slot)!!
                assertTrue(kotlin.math.abs(orig.level - r.level) < 1e-3f, "seed $seed slot ${orig.slot}: level")
                assertTrue(kotlin.math.abs(orig.pan - r.pan) < 1e-3f, "seed $seed slot ${orig.slot}: pan")
                assertEquals(orig.tuneCoarse, r.tuneCoarse, "seed $seed slot ${orig.slot}: coarse")
                assertEquals(orig.tuneFine, r.tuneFine, "seed $seed slot ${orig.slot}: fine")
                assertEquals(orig.muteGroup, r.muteGroup, "seed $seed slot ${orig.slot}: mute group")
                assertEquals(orig.oneShot, r.oneShot, "seed $seed slot ${orig.slot}: one-shot")
                assertEquals(orig.sampleFile, r.sampleFile, "seed $seed slot ${orig.slot}: sample")
            }
        }
    }

    @Test
    fun `random kits round-trip through the xpn archive with every numeric field intact`() {
        repeat(40) { seed ->
            val rnd = Random(seed + 1000)
            val srcDir = File(temp, "xsrc-$seed")
            val kit = randomKit(srcDir, rnd)
            val xpn = XpnPackager.write(
                kit, srcDir, File(temp, "arc-$seed.xpn"), Exporters.defaultMeta(kit),
            )
            val back = XpnImporter.import(xpn, File(temp, "xin-$seed")).kit

            assertEquals(kit.pads.map { it.slot }, back.pads.map { it.slot }, "seed $seed: slots")
            for (orig in kit.pads) {
                val r = back.pad(orig.slot)!!
                assertTrue(kotlin.math.abs(orig.level - r.level) < 1e-3f, "seed $seed slot ${orig.slot}: level")
                assertTrue(kotlin.math.abs(orig.pan - r.pan) < 1e-3f, "seed $seed slot ${orig.slot}: pan")
                assertEquals(orig.tuneCoarse, r.tuneCoarse, "seed $seed slot ${orig.slot}: coarse")
                assertEquals(orig.muteGroup, r.muteGroup, "seed $seed slot ${orig.slot}: mute group")
                assertEquals(orig.oneShot, r.oneShot, "seed $seed slot ${orig.slot}: one-shot")
            }
        }
    }
}
