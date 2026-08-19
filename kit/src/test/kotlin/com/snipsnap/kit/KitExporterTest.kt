package com.snipsnap.kit

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KitExporterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("exporter").toFile()
    private val kitDir: File get() = File(temp, "kit")
    private val sd: File get() = File(temp, "sd")

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun buildKit(): Kit {
        kitDir.mkdirs()
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), DrumSynth.kick())
        WavWriter.write(File(kitDir, "A02_Snare_01.wav"), DrumSynth.snare())
        return Kit(
            "SnipSnap Kit 01",
            listOf(
                KitPad(1, "A01_Kick_01.wav", drumClass = DrumClass.KICK, tuneCoarse = -2),
                KitPad(2, "A02_Snare_01.wav", drumClass = DrumClass.SNARE, muteGroup = 4),
            ),
        )
    }

    @Test
    fun `exports a loadable program folder`() {
        val result = KitExporter.exportProgramFolder(buildKit(), kitDir, sd)

        assertEquals(File(sd, "SnipSnap Kit 01"), result.directory)
        assertEquals("SnipSnap Kit 01.xpm", result.program.name)
        assertEquals(2, result.samples.size)

        val xml = result.program.readText()
        assertTrue("<SampleName>A01_Kick_01</SampleName>" in xml)
        assertTrue("<SampleName>A02_Snare_01</SampleName>" in xml)
        assertTrue("<TuneCoarse>-2</TuneCoarse>" in xml)
        assertTrue("<MuteGroup>4</MuteGroup>" in xml)

        // The program's frame counts must match the copied files, not the originals.
        val kickFrames = WavInfo.read(File(result.directory, "A01_Kick_01.wav")).frameCount
        assertTrue("<SliceEnd>$kickFrames</SliceEnd>" in xml)
    }

    @Test
    fun `refuses to clobber an existing export`() {
        KitExporter.exportProgramFolder(buildKit(), kitDir, sd)
        assertFailsWith<IOException> { KitExporter.exportProgramFolder(buildKit(), kitDir, sd) }
        KitExporter.exportProgramFolder(buildKit(), kitDir, sd, overwrite = true)
    }

    @Test
    fun `a blocked kit never touches the destination`() {
        val kit = Kit("Broken", listOf(KitPad(1, "missing.wav")))
        kitDir.mkdirs()
        val e = assertFailsWith<ExportBlockedException> {
            KitExporter.exportProgramFolder(kit, kitDir, sd)
        }
        assertTrue(e.findings.blocked())
        assertTrue(!sd.exists(), "a blocked export must not create the destination")
    }

    @Test
    fun `sanitized names are consistent between folder and program`() {
        kitDir.mkdirs()
        WavWriter.write(File(kitDir, "Kick face.wav"), DrumSynth.kick())
        // '#' is FAT-safe ASCII but the space+question pattern isn't; use a name needing work
        WavWriter.write(File(kitDir, "Snare(2).wav"), DrumSynth.snare())
        val kit = Kit(
            "Clean Kit",
            listOf(KitPad(1, "Kick face.wav"), KitPad(2, "Snare(2).wav")),
        )

        val result = KitExporter.exportProgramFolder(kit, kitDir, sd)
        val xml = result.program.readText()
        for (wav in result.samples) {
            assertTrue(
                "<SampleName>${wav.nameWithoutExtension}</SampleName>" in xml,
                "program must reference ${wav.name}",
            )
        }
    }

    @Test
    fun `the whole pipeline - break to sd card`() {
        // Synth a break, chop it, classify, place, assemble a kit folder,
        // preflight, export: the app's core loop with no app.
        val loop = DrumSynth.loop(seconds = 2.4f)
        val slices = Chopper.byTransients(loop, maxSlices = 16, cleanup = Chopper.SLICE_CLEANUP)
        assertTrue(slices.isNotEmpty(), "the break should chop")

        val classified = slices.map { it.snip to Classifier.classify(it.snip).drumClass }
        val arranged = AutoPlace.arrange(classified, padCount = 16) { it.second }

        val kit = KitAssembler.assemble("Break Kit", arranged, File(temp, "break"))
        assertTrue(kit.pads.isNotEmpty())

        // The assembled folder must reload identically.
        assertEquals(kit, KitStore.load(File(temp, "break")))

        val findings = Preflight.check(kit, File(temp, "break"))
        assertTrue(!findings.blocked(), "assembled kit must pass its own preflight: $findings")

        val result = KitExporter.exportProgramFolder(kit, File(temp, "break"), sd)
        assertTrue(result.program.isFile)
        assertEquals(kit.pads.size, result.samples.size)
        for (wav in result.samples) {
            val info = WavInfo.read(wav)
            assertEquals(44_100, info.sampleRate)
            assertEquals(24, info.bitsPerSample)
        }

        // A kick that got placed must have landed on A01 with its class colour.
        val kick = kit.pads.firstOrNull { it.drumClass == DrumClass.KICK }
        if (kick != null) {
            assertEquals(1, kick.slot)
            assertEquals(AutoPlace.colorFor(DrumClass.KICK), kick.colorHex)
        }
    }

    @Test
    fun `assembler skips empty slots and silent snips`() {
        val arranged = listOf<Pair<Snip, DrumClass>?>(
            DrumSynth.kick() to DrumClass.KICK,
            null,
            Snip(FloatArray(0), 1, 44_100) to DrumClass.PERC,
        )
        val kit = KitAssembler.assemble("Sparse", arranged, File(temp, "sparse"))
        assertEquals(1, kit.pads.size)
        assertEquals(1, kit.pads.single().slot)
    }

    @Test
    fun `assembler numbers same-class pads`() {
        val arranged = listOf<Pair<Snip, DrumClass>?>(
            DrumSynth.kick() to DrumClass.KICK,
            DrumSynth.kick(decay = 30.0) to DrumClass.KICK,
        )
        val kit = KitAssembler.assemble("Two Kicks", arranged, File(temp, "kicks"))
        assertEquals("A01_Kick_01.wav", kit.pad(1)?.sampleFile)
        assertEquals("A02_Kick_02.wav", kit.pad(2)?.sampleFile)
    }
}
