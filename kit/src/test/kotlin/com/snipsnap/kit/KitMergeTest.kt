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
import kotlin.test.assertFalse
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

    @Test
    fun `a source backed board refuses before destination writes without its importer`() {
        val aDir = File(temp, "board-a")
        val bDir = File(temp, "board-b")
        kitA(aDir)
        val b = kitB(bDir)
        KitStore.save(b.copy(pads = b.pads.map { pad ->
            if (pad.slot == 1) pad.copy(recipe = JsonValue.Obj(mapOf("boardPad" to JsonValue.Num(1.0)))) else pad
        }), bDir)
        val dest = File(temp, "board-refused")
        assertFailsWith<IllegalArgumentException> { KitMerge.merge(aDir, bDir, dest) }
        assertFalse(dest.exists(), "a missing source importer must refuse before creating the destination")
    }

    @Test
    fun `Aquarium wrappers in either kept bank require an importer before destination writes`() {
        for (fromA in listOf(false, true)) {
            val label = if (fromA) "kept-a" else "moved-b"
            val aDir = File(temp, "aquarium-$label-a")
            val bDir = File(temp, "aquarium-$label-b")
            kitA(aDir)
            kitB(bDir)
            val sourceDir = if (fromA) aDir else bDir
            val kit = KitStore.load(sourceDir)
            val wrapper = JsonValue.Obj(mapOf("aquariumPad" to JsonValue.Num(1.0)))
            KitStore.save(kit.copy(pads = kit.pads.map { pad ->
                if (pad.slot == 1) pad.copy(recipe = wrapper) else pad
            }), sourceDir)
            val sourceFiles = sourceDir.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(sourceDir).path to it.readBytes().toList() }
            val dest = File(temp, "aquarium-$label-refused")

            assertFailsWith<IllegalArgumentException> { KitMerge.merge(aDir, bDir, dest) }
            assertFalse(dest.exists(), "missing Aquarium sources must refuse before any destination write")
            assertEquals(sourceFiles, sourceDir.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(sourceDir).path to it.readBytes().toList() })
        }
    }

    @Test
    fun `an Aquarium importer receives original and remapped layer identities before save`() {
        val aDir = File(temp, "aquarium-handler-a")
        val bDir = File(temp, "aquarium-handler-b")
        kitA(aDir)
        val b = kitB(bDir)
        val wrapper = JsonValue.Obj(mapOf("aquariumPad" to JsonValue.Num(1.0)))
        val sourcePad = b.pad(1)!!.copy(recipe = wrapper)
        KitStore.save(b.copy(pads = b.pads.map { if (it.slot == 1) sourcePad else it }), bDir)
        val dest = File(temp, "aquarium-handled")
        val imported = mutableListOf<Int>()

        val merged = KitMerge.merge(aDir, bDir, dest, importPad = { source, target, original, copied ->
            if (original.recipe != wrapper) copied else {
                imported += copied.slot
                assertEquals(bDir, source)
                assertEquals(dest, target)
                assertEquals(sourcePad, original)
                assertEquals(17, copied.slot)
                assertEquals(original.velocityLayers.map { it.velStart to it.velEnd },
                    copied.velocityLayers.map { it.velStart to it.velEnd })
                for ((old, next) in original.velocityLayers.zip(copied.velocityLayers)) {
                    assertTrue(File(target, next.sampleFile).readBytes()
                        .contentEquals(File(source, old.sampleFile).readBytes()))
                }
                // The higher layer owns source-store transfer; :kit adopts
                // exactly the descriptor returned by that handler.
                copied.copy(recipe = JsonValue.Obj(wrapper.entries + ("sourceImported" to JsonValue.Bool(true))))
            }
        })

        assertEquals(listOf(17), imported)
        assertEquals(JsonValue.Bool(true), merged.pad(17)!!.recipe!!.entries["sourceImported"])
        assertEquals(merged, KitStore.load(dest))
        assertEquals(wrapper, KitStore.load(bDir).pad(1)!!.recipe)
    }

    @Test
    fun `source preflight visits selected A and B pads before any destination write`() {
        val aDir = File(temp, "preflight-a")
        val bDir = File(temp, "preflight-b")
        val a = kitA(aDir)
        val b = kitB(bDir)
        val dest = File(temp, "preflight-refused")
        val checked = mutableListOf<Pair<File, Int>>()
        var imported = false

        assertFailsWith<IllegalArgumentException> {
            KitMerge.merge(aDir, bDir, dest, preflightPad = { source, pad ->
                checked += source to pad.slot
                assertFalse(dest.exists())
                if (source == bDir && pad.slot == 3) throw IllegalArgumentException("saved source cannot travel")
            }, importPad = { _, _, _, copied -> imported = true; copied })
        }
        assertEquals(a.pads.map { aDir to it.slot } + b.pads.map { bDir to it.slot }, checked)
        assertFalse(imported, "all source preflight finishes before sample copying or import handlers")
        assertFalse(dest.exists())
        assertEquals(a, KitStore.load(aDir))
        assertEquals(b, KitStore.load(bDir))
    }
}
