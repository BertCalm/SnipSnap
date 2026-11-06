package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackBuilderTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("pack").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun buildKit(name: String, hz: Double): File {
        val dir = File(temp, "kits/$name").apply { mkdirs() }
        val tone = Snip(
            FloatArray(3_000) { i -> (0.5 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() },
            1, 44_100,
        )
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone)
        KitStore.save(
            Kit(name, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK))),
            dir,
        )
        return dir
    }

    private val meta = ExpansionMeta(
        title = "Crate Vol 1",
        identifier = "app.snipsnap.cratevol1",
        description = "Made with SnipSnap.",
    )

    @Test
    fun `three kits land under one tile, colliding stems kept apart`() {
        val kits = listOf(buildKit("Alpha", 90.0), buildKit("Bravo", 200.0), buildKit("Charlie", 400.0))
        val result = PackBuilder.build(kits, File(temp, "card"), meta, artworkPng = byteArrayOf(1, 2, 3))

        assertEquals(listOf("Alpha", "Bravo", "Charlie"), result.packed)
        val dest = result.directory
        assertEquals("Crate Vol 1", dest.name)
        assertEquals(
            setOf("Alpha.xpm", "Bravo.xpm", "Charlie.xpm"),
            File(dest, "Programs").list()!!.toSet(),
        )
        // Every kit's A01_Kick_01 survives in its own subfolder.
        for (name in listOf("Alpha", "Bravo", "Charlie")) {
            assertTrue(File(dest, "Samples/$name/A01_Kick_01.wav").isFile, name)
            assertTrue(File(dest, "[Previews]/$name.xpm.wav").length() > 1_000, "$name preview")
        }
        assertTrue(File(dest, ExpansionWriter.XML_NAME).isFile)
        assertTrue(File(dest, ExpansionWriter.MANIFEST_NAME).isFile)
        assertTrue(File(dest, meta.artworkFileName).isFile)
    }

    @Test
    fun `a blocked kit is skipped and named, an all-blocked pack refuses`() {
        val ok = buildKit("Fine", 200.0)
        val broken = buildKit("Broken", 300.0)
        File(broken, "A01_Kick_01.wav").delete()

        val result = PackBuilder.build(listOf(ok, broken), File(temp, "card2"), meta)
        assertEquals(listOf("Fine"), result.packed)
        assertEquals(1, result.skipped.size)
        assertEquals("Broken", result.skipped[0].first)

        assertFailsWith<java.io.IOException> {
            PackBuilder.build(listOf(broken), File(temp, "card3"), meta)
        }
    }

    @Test
    fun `the xpn twin is deterministic, root-invariant, manifest-free, and round-trips`() {
        val kits = listOf(buildKit("Delta", 150.0), buildKit("Echo", 500.0))
        val a = PackBuilder.build(kits, File(temp, "z1"), meta, asXpn = true).xpn!!
        val b = PackBuilder.build(kits, File(temp, "z2"), meta, asXpn = true).xpn!!
        assertTrue(a.readBytes().contentEquals(b.readBytes()), "same kits, same archive bytes")

        ZipFile(a).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(ExpansionWriter.XML_NAME in names, "the one structural invariant")
            assertTrue(names.none { it.endsWith(ExpansionWriter.MANIFEST_NAME) }, "no archive carries a manifest")
        }

        // The receive half: every program becomes its own kit again.
        val back = XpnImporter.importAll(a, File(temp, "back"))
        assertEquals(setOf("Delta", "Echo"), back.kits.map { it.kit.name }.toSet())
        assertTrue(back.skipped.isEmpty())
        assertTrue(
            File(back.kits.first { it.kit.name == "Delta" }.directory, "A01_Kick_01.wav").readBytes()
                .contentEquals(File(kits[0], "A01_Kick_01.wav").readBytes()),
            "audio byte-identical through the round-trip",
        )
    }

    @Test
    fun `an xpn pack of a robin kit with source metadata imports`() {
        val dir = File(temp, "kits/Robin").apply { mkdirs() }
        WavWriter.write(File(dir, "A01_Kick_01.wav"), Snip(FloatArray(5_000) { i -> ((i % 50) - 25) / 100f }, 1, 44_100))
        KitStore.save(Kit("Robin", listOf(KitPad(
            slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK,
            chain = ChainInfo((0 until 5).map { it * 1000L }, cycle = 5), robin = RobinSettings(0.5f, 5),
            source = mapOf("recordedBy" to "me"),
        ))), dir)
        val xpn = PackBuilder.build(listOf(dir), File(temp, "rz"), meta, asXpn = true).xpn!!
        val back = XpnImporter.importAll(xpn, File(temp, "rback"))
        assertTrue(back.skipped.isEmpty(), back.skipped.toString())
        val pad = back.kits.single().kit.pad(1)!!
        assertEquals(4, pad.chain!!.cycle)
        assertEquals("me", pad.source["recordedBy"])
    }

    /** A three-zone held instrument as MAKE INSTRUMENT writes one: each zone a tone with a sustain loop. */
    private fun buildInstrument(name: String, root: File = File(temp, "Instruments")): File {
        val midis = listOf(48, 60, 72)
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { i, midi ->
            val hz = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            val stem = "${name.filter { !it.isWhitespace() }}_$midi"
            samples[stem] = Snip(FloatArray(8_000) { f -> (0.4 * Math.sin(2.0 * Math.PI * hz * f / 44_100)).toFloat() }, 1, 44_100)
            Keygroup(
                lowNote = if (i == 0) midi - 9 else midi - 6, highNote = if (i == midis.lastIndex) midi + 9 else midi + 5,
                rootNote = midi, layers = listOf(VelocityLayer(stem, 8_000, 0, 127, loopStartFrame = 4_000)),
            )
        }
        OneNote.writePackage(name, KeygroupProgram(name, keygroups, volumeRelease = .6f), samples, root, overwrite = false)
        return InstrumentStore.sidecar(root, name)
    }

    @Test
    fun `an instrument lands as a keygroup program beside the kits, its loops intact`() {
        val sidecar = buildInstrument("Glass Keys")
        val result = PackBuilder.build(listOf(buildKit("Alpha", 90.0)), File(temp, "card"), meta,
            asXpn = true, instruments = listOf(sidecar))

        assertEquals(listOf("Alpha"), result.packed)
        assertEquals(listOf("Glass Keys"), result.instruments)
        assertTrue(result.skipped.isEmpty(), "${result.skipped}")
        val dest = result.directory
        assertEquals(setOf("Alpha.xpm", "Glass Keys.xpm"), File(dest, "Programs").list()!!.toSet())
        val packageXpm = File(sidecar.parentFile, "Glass Keys_[TrackData]/Glass Keys.xpm")
        assertEquals(packageXpm.readText(), File(dest, "Programs/Glass Keys.xpm").readText(), "the package's own program, as made")
        for (midi in listOf(48, 60, 72)) {
            val wav = File(dest, "Samples/Glass Keys/GlassKeys_$midi.wav")
            assertTrue(wav.isFile, "zone $midi")
            val smpl = assertNotNull(WavReader.readSmpl(wav), "zone $midi keeps its sampler sheet")
            assertEquals(midi, smpl.rootNote)
            assertEquals(4_000L, smpl.loop?.startFrame, "zone $midi keeps its sustain loop")
        }
        val preview = WavReader.read(File(dest, "[Previews]/Glass Keys.xpm.wav"))
        assertEquals(4 * 44_100, preview.frameCount, "the preview holds the middle zone past its 8,000 frames")
        ZipFile(assertNotNull(result.xpn)).use { zip ->
            assertNotNull(zip.getEntry("Programs/Glass Keys.xpm"))
            assertNotNull(zip.getEntry("Samples/Glass Keys/GlassKeys_60.wav"))
        }
    }

    @Test
    fun `instruments alone make a pack, and a clash or a broken package is named and left out`() {
        val root = File(temp, "Instruments")
        val good = buildInstrument("Low Bass", root)
        val clash = buildInstrument("Alpha", File(temp, "Other"))
        val broken = InstrumentStore.sidecar(root, "Broken").apply { writeText("{ not json") }
        val missing = buildInstrument("No Program", root)
        File(root, "No Program_[TrackData]/No Program.xpm").delete()

        val withKit = PackBuilder.build(listOf(buildKit("Alpha", 90.0)), File(temp, "card"), meta,
            instruments = listOf(good, clash, broken, missing))
        assertEquals(listOf("Low Bass"), withKit.instruments)
        val reasons = withKit.skipped.toMap()
        assertEquals(setOf("Alpha", "Broken", "No Program"), reasons.keys)
        assertTrue("already in this pack" in reasons.getValue("Alpha"), reasons.getValue("Alpha"))
        assertTrue("could not be read" in reasons.getValue("Broken"), reasons.getValue("Broken"))
        assertTrue("missing" in reasons.getValue("No Program"), reasons.getValue("No Program"))

        val alone = PackBuilder.build(emptyList(), File(temp, "card2"), meta, instruments = listOf(good))
        assertEquals(emptyList(), alone.packed)
        assertEquals(listOf("Low Bass"), alone.instruments)

        assertFailsWith<java.io.IOException> {
            PackBuilder.build(emptyList(), File(temp, "card3"), meta, instruments = listOf(broken))
        }
    }
}
