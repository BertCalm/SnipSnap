package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KitMergeTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("merge").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun tone(hz: Double, n: Int = 3_000) = Snip(
        FloatArray(n) { i -> (0.5 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() },
        1, 44_100,
    )

    private fun kitA(dir: File): Kit {
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(90.0))
        val kit = Kit(
            "Alpha",
            listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)),
            tempoBpm = 92f,
        )
        KitStore.save(kit, dir)
        GrooveStore.save(dir, listOf(Mpc3Clip("Alpha Groove", 1, listOf(Mpc3Note(36, 0, 0.9f)))))
        return kit
    }

    private fun kitB(dir: File): Kit {
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Snare_01.wav"), tone(400.0))
        WavWriter.write(File(dir, "A01_Snare_01_soft.wav"), tone(400.0, 2_000))
        WavWriter.write(File(dir, "A03_Hat_01.wav"), tone(4_000.0))
        val kit = Kit(
            "Bravo",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Snare_01.wav", drumClass = DrumClass.SNARE,
                    colorHex = "#ffc41f", muteGroup = 2,
                    recipe = JsonValue.Obj(mapOf("fx" to JsonValue.Str("crushed"))),
                    velocityLayers = listOf(
                        KitLayer("A01_Snare_01_soft.wav", 0, 63),
                        KitLayer("A01_Snare_01.wav", 64, 127),
                    ),
                ),
                KitPad(slot = 3, sampleFile = "A03_Hat_01.wav", drumClass = DrumClass.HAT_CLOSED),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `bank B arrives with everything carried and the sources untouched`() {
        val aDir = File(temp, "a")
        val bDir = File(temp, "b")
        kitA(aDir)
        kitB(bDir)
        val aJson = File(aDir, "kit.json").readBytes()
        val bJson = File(bDir, "kit.json").readBytes()

        val dest = File(temp, "Alpha AB")
        val merged = KitMerge.merge(aDir, bDir, dest)

        assertEquals(listOf(1, 17, 19), merged.pads.map { it.slot })
        assertEquals(92f, merged.tempoBpm, "A brings its identity")
        assertTrue(File(dest, GrooveStore.FILE_NAME).isFile, "A's grooves follow the folder")
        assertEquals(1, GrooveStore.load(dest).size)

        val snare = merged.pad(17)!!
        assertEquals("B01_Snare_01.wav", snare.sampleFile, "re-prefixed for its new slot")
        assertEquals(DrumClass.SNARE, snare.drumClass)
        assertEquals("#ffc41f", snare.colorHex)
        assertEquals(2, snare.muteGroup)
        assertEquals("crushed", snare.recipe!!.entries.getValue("fx").str())
        assertEquals(
            listOf("B01_Snare_01_soft.wav", "B01_Snare_01.wav"),
            snare.velocityLayers.map { it.sampleFile },
            "layers re-prefixed with the pad",
        )
        assertEquals("B03_Hat_01.wav", merged.pad(19)!!.sampleFile)

        assertTrue(
            File(dest, "B01_Snare_01.wav").readBytes()
                .contentEquals(File(bDir, "A01_Snare_01.wav").readBytes()),
            "byte-identical under the new stem",
        )
        assertTrue(File(aDir, "kit.json").readBytes().contentEquals(aJson), "source A untouched")
        assertTrue(File(bDir, "kit.json").readBytes().contentEquals(bJson), "source B untouched")

        // The merged folder stands on its own.
        assertTrue(Preflight.check(KitStore.load(dest), dest).none { it.severity == Severity.FAIL })
    }

    @Test
    fun `an occupied bank B refuses unless replacing`() {
        val aDir = File(temp, "occ-a")
        val bDir = File(temp, "occ-b")
        kitA(aDir)
        kitB(bDir)
        // Give A a bank-B squatter.
        WavWriter.write(File(aDir, "B01_Old_01.wav"), tone(200.0))
        val a = KitStore.load(aDir)
        KitStore.save(
            a.copy(pads = a.pads + KitPad(slot = 17, sampleFile = "B01_Old_01.wav")),
            aDir,
        )

        assertFailsWith<IllegalArgumentException> {
            KitMerge.merge(aDir, bDir, File(temp, "refused"))
        }.also { assertTrue("occupied" in it.message!!) }

        val merged = KitMerge.merge(aDir, bDir, File(temp, "swapped"), replace = true)
        assertEquals("B01_Snare_01.wav", merged.pad(17)!!.sampleFile, "the squatter swapped out")
        assertEquals(listOf(1, 17, 19), merged.pads.map { it.slot })
    }
}
