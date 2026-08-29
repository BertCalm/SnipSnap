package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate has to mean something: a kit that passes [Preflight.check] with
 * no FAIL must export to *every* format without throwing. Preflight is the
 * promise the UI shows the user ("this will write") — if a preflight-clean
 * kit could still blow up in a writer, the promise is a lie.
 *
 * Fuzz random valid kits, keep the preflight-clean ones (nearly all), and
 * assert each exports to each format. A writer that surprise-fails on a
 * shape preflight allows shows up as a thrown exception on some seed.
 */
class PreflightExportInvariantTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("pfexport").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun randomKit(dir: File, rnd: Random): Kit {
        dir.mkdirs()
        val slots = (1..16).shuffled(rnd).take(1 + rnd.nextInt(6)).sorted()
        val pads = slots.map { slot ->
            val stem = "P%02d_Snip".format(slot)
            WavWriter.write(
                File(dir, "$stem.wav"),
                Snip(FloatArray(1_500 + rnd.nextInt(3_000)) { (rnd.nextFloat() * 2 - 1) * 0.4f }, 1, 44_100),
            )
            KitPad(
                slot = slot,
                sampleFile = "$stem.wav",
                drumClass = DrumClass.entries[rnd.nextInt(DrumClass.entries.size)],
                level = rnd.nextDouble(0.0, 1.0).toFloat(),
                pan = rnd.nextDouble(0.0, 1.0).toFloat(),
                tuneCoarse = rnd.nextInt(-36, 37),
                muteGroup = rnd.nextInt(0, 33),
            )
        }
        val kit = Kit("Fuzz Export ${dir.name}", pads)
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `a preflight-clean kit exports to every format without throwing`() {
        var exercised = 0
        repeat(50) { seed ->
            val rnd = Random(seed)
            val dir = File(temp, "k$seed")
            val kit = randomKit(dir, rnd)
            if (Preflight.check(kit, dir).blocked()) return@repeat // not our claim

            for (format in ExportFormat.entries) {
                val dest = File(temp, "out-$seed-${format.id}")
                try {
                    Exporters.export(format, kit, dir, dest)
                    exercised++
                } catch (e: Exception) {
                    throw AssertionError("seed $seed: preflight-clean kit threw on ${format.id}: $e", e)
                }
            }
        }
        assertTrue(exercised > 100, "the invariant was actually exercised ($exercised exports)")
    }
}
