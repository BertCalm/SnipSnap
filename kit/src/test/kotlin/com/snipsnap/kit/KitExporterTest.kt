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

    // ---- round robin: the MPC cycles take files --------------------------------

    private fun takeSnip(takes: Int, frames: Int = 1000, rate: Int = 44_100): Snip =
        Snip(FloatArray(takes * frames) { i -> ((i / frames + 1) * 0.1f) * (if (i % 2 == 0) 1f else -1f) }, 1, rate)

    private fun robinKit(takes: Int, name: String = "Robin Kit", depth: WavWriter.BitDepth = WavWriter.BitDepth.PCM_24, robin: RobinSettings? = RobinSettings(0.5f, takes)): Kit {
        kitDir.mkdirs()
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), takeSnip(takes), depth)
        val chain = ChainInfo((0 until takes).map { it * 1000L }, cycle = takes)
        return Kit(name, listOf(KitPad(1, "A01_Kick_01.wav", drumClass = DrumClass.KICK, chain = chain, robin = robin)))
    }

    private fun samplesOf(f: File) = com.snipsnap.audio.WavReader.read(f).samples

    @Test
    fun `a six-take robin pad exports four take files in order`() {
        val result = KitExporter.exportProgramFolder(robinKit(6), kitDir, sd)
        val dir = result.directory
        val source = samplesOf(File(kitDir, "A01_Kick_01.wav"))
        for (n in 1..4) {
            val take = File(dir, "A01_Kick_01_RR$n.wav")
            assertTrue(take.isFile, "RR$n written")
            assertEquals(1000L, WavInfo.read(take).frameCount)
            assertTrue(source.copyOfRange((n - 1) * 1000, n * 1000).contentEquals(samplesOf(take)), "RR$n is take $n")
        }
        assertTrue(!File(dir, "A01_Kick_01_RR5.wav").exists())
        assertTrue(!File(dir, "A01_Kick_01.wav").exists(), "the chain WAV itself is not copied")
        assertEquals(4, result.samples.size)
        val xml = result.program.readText()
        assertTrue("<ZonePlay>0</ZonePlay>" in xml)
        assertEquals(listOf("A01_Kick_01_RR1", "A01_Kick_01_RR2", "A01_Kick_01_RR3", "A01_Kick_01_RR4"),
            Regex("<SampleName>(.+?)</SampleName>").findAll(xml).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `a three-take pad exports three takes and keeps the bit depth and rate`() {
        val result = KitExporter.exportProgramFolder(robinKit(3, depth = WavWriter.BitDepth.PCM_16), kitDir, sd)
        assertEquals(3, result.samples.size)
        val info = WavInfo.read(File(result.directory, "A01_Kick_01_RR1.wav"))
        assertEquals(16, info.bitsPerSample)
        assertEquals(44_100, info.sampleRate)
        assertEquals(1, info.channels)
    }

    // Deterministic fixtures whose .xpm sha256 was taken from the code at BASE 228a27a09,
    // before round robin touched the exporters. The same builders ran there.
    private fun pinnedTone(hz: Double, frames: Int) =
        Snip(FloatArray(frames) { i -> (0.5 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() }, 1, 44_100)

    private fun sha(f: File) = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun plainPinnedKit(): Kit {
        kitDir.mkdirs()
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), pinnedTone(60.0, 3000))
        WavWriter.write(File(kitDir, "A02_Snare_01.wav"), pinnedTone(200.0, 2000))
        return Kit("Pinned Plain", listOf(
            KitPad(1, "A01_Kick_01.wav", drumClass = DrumClass.KICK, tuneCoarse = -2),
            KitPad(2, "A02_Snare_01.wav", drumClass = DrumClass.SNARE, muteGroup = 4),
        ))
    }

    private fun foreignPinnedKit(): Kit {
        kitDir.mkdirs()
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), pinnedTone(90.0, 4000))
        return Kit("Pinned Chain", listOf(
            KitPad(1, "A01_Kick_01.wav", drumClass = DrumClass.KICK, chain = ChainInfo(listOf(0L, 1000L, 2000L, 3000L), cycle = 4)),
        ))
    }

    @Test
    fun `a foreign chain exports exactly as before`() {
        val result = KitExporter.exportProgramFolder(foreignPinnedKit(), kitDir, sd)
        assertTrue(File(result.directory, "A01_Kick_01.wav").isFile)
        assertTrue(result.directory.listFiles().orEmpty().none { "_RR" in it.name })
        assertEquals(FOREIGN_CHAIN_XPM_SHA, sha(result.program))
    }

    @Test
    fun `a kit without robin pads exports byte-identical`() {
        val result = KitExporter.exportProgramFolder(plainPinnedKit(), kitDir, sd)
        assertEquals(PLAIN_XPM_SHA, sha(result.program))
        assertEquals(2, result.samples.size)
    }

    private companion object {
        const val PLAIN_XPM_SHA = "30ff857ad411e8b3980ab9df932b367afa50c71d01c88c1048cce8f110306ef3"
        const val FOREIGN_CHAIN_XPM_SHA = "2f496c6fc1b9b785e6e7ab89ad096681b04af25a097024f185bd4353f03b8ea7"
    }

    private fun ByteArray.indexOfFirst3(tag: String): Int =
        (0..size - tag.length).first { i -> tag.indices.all { this[i + it] == tag[it].code.toByte() } }

    @Test
    fun `take one is the original take's PCM bytes, 16-bit minimum sample included`() {
        kitDir.mkdirs()
        val frames = 500
        val samples = FloatArray(3 * frames) { i -> if (i == 3) -1f else ((i % 97) - 48) / 100f }
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), Snip(samples, 1, 44_100), WavWriter.BitDepth.PCM_16)
        // The writer never emits -32768 (it scales by 32767); poke one in, as a real recorder's file can carry.
        File(kitDir, "A01_Kick_01.wav").let { f ->
            val b = f.readBytes()
            val at = b.indexOfFirst3("data") + 8 + 6
            b[at] = 0x00; b[at + 1] = 0x80.toByte()
            f.writeBytes(b)
        }
        val kit = Kit("Bytes", listOf(KitPad(1, "A01_Kick_01.wav", chain = ChainInfo(listOf(0L, 500L, 1000L), cycle = 3), robin = RobinSettings(0.5f, 3))))
        val result = KitExporter.exportProgramFolder(kit, kitDir, sd)
        fun data(f: File): ByteArray {
            val b = f.readBytes()
            var pos = 12
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII)
                val size = java.nio.ByteBuffer.wrap(b, pos + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") return b.copyOfRange(pos + 8, pos + 8 + size)
                pos += 8 + size + (size and 1)
            }
            error("no data chunk")
        }
        val whole = data(File(kitDir, "A01_Kick_01.wav"))
        for (n in 1..3) {
            assertTrue(whole.copyOfRange((n - 1) * 1000, n * 1000).contentEquals(data(File(result.directory, "A01_Kick_01_RR$n.wav"))), "RR$n data bytes")
        }
        assertEquals(-32768, java.nio.ByteBuffer.wrap(data(File(result.directory, "A01_Kick_01_RR1.wav"))).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(6).toInt())
    }

    @Test
    fun `a pad named like a take file is a preflight failure, not a late crash`() {
        val kit = robinKit(4)
        WavWriter.write(File(kitDir, "A01_Kick_01_RR1.wav"), takeSnip(1))
        val clash = kit.copy(pads = kit.pads + KitPad(2, "A01_Kick_01_RR1.wav"))
        val findings = Preflight.check(clash, kitDir)
        assertTrue(findings.any { it.severity == Severity.FAIL && "collide" in it.message }, findings.joinToString { it.message })
        assertFailsWith<ExportBlockedException> { KitExporter.exportProgramFolder(clash, kitDir, sd) }
    }

    @Test
    fun `the other program writers keep the whole chain`() {
        val kit = robinKit(4)
        val (slots, written) = KitExporter.buildSlots(kit, kitDir, File(temp, "out"))
        assertEquals(1, written.size)
        assertEquals("A01_Kick_01", slots[0]!!.sampleName)
        assertEquals(null, slots[0]!!.cycleLayers)
    }
}
