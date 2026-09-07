package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Y1: the folder is the kit — grooves and origins included. */
class GrooveRecallTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("recall").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun clip(name: String = "Test Groove") = Mpc3Clip(
        name, 2,
        listOf(
            Mpc3Note(36, 0, 1f, 240),
            Mpc3Note(38, 960, 0.6f, 120),
            Mpc3Note(42, 4000, 0.31f, 60),
        ),
    )

    private fun makeKit(name: String): Pair<Kit, File> {
        val dir = File(temp, name)
        dir.mkdirs()
        WavWriter.write(
            File(dir, "A01_Kick_01.wav"),
            Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / 12.0)).toFloat() }, 1, 44_100)),
        )
        val kit = Kit(name, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)))
        KitStore.save(kit, dir)
        return kit to dir
    }

    @Test
    fun `grooves round-trip through the sidecar exactly`() {
        val dir = File(temp, "gs")
        assertEquals(emptyList(), GrooveStore.load(dir), "no sidecar, no grooves")

        val clips = listOf(clip("A"), clip("B"))
        GrooveStore.save(dir, clips)
        assertEquals(clips, GrooveStore.load(dir))

        assertTrue(GrooveStore.delete(dir))
        assertEquals(emptyList(), GrooveStore.load(dir))
        assertFailsWith<IllegalArgumentException> { GrooveStore.save(dir, emptyList()) }
    }

    @Test
    fun `an export tomorrow still carries today's groove`() {
        val (kit, dir) = makeKit("Tomorrow")
        GrooveStore.save(dir, listOf(clip("Tomorrow Groove")))

        // No clip passed at the call site — the sidecar backs it up.
        val xtd = Exporters.export(ExportFormat.MPC3_TRACK, kit, dir, File(temp, "card"))
        assertTrue("Tomorrow Groove" in Acvs.read(xtd.primary).payloadText)
        val xpj = Exporters.export(ExportFormat.MPC3_PROJECT, kit, dir, File(temp, "card2"))
        assertTrue("Tomorrow Groove" in Acvs.read(xpj.primary).payloadText)

        // An explicit call-site clip still wins.
        val override = Exporters.export(
            ExportFormat.MPC3_TRACK, kit, dir, File(temp, "card3"), clip = clip("Explicit"),
        )
        val payload = Acvs.read(override.primary).payloadText
        assertTrue("Explicit" in payload)
        assertTrue("Tomorrow Groove" !in payload)
    }

    @Test
    fun `imports bring the clips home`() {
        val (kit, dir) = makeKit("Clipful")
        val original = clip("Clipful Groove")
        val card = File(temp, "clip-card")
        Mpc3Exporter.exportTrack(kit, dir, card, clip = original)

        val result = Mpc3Importer.import(File(card, "Clipful.xtd"), File(temp, "in"))
        assertEquals(1, result.grooveCount)
        val recovered = GrooveStore.load(result.directory)
        assertEquals(1, recovered.size)
        assertEquals(original.name, recovered[0].name)
        assertEquals(original.bars, recovered[0].bars)
        assertEquals(original.notes.map { it.note }, recovered[0].notes.map { it.note })
        assertEquals(original.notes.map { it.timePulses }, recovered[0].notes.map { it.timePulses })

        // And the re-export of the imported kit carries it again: full circle.
        val re = Exporters.export(
            ExportFormat.MPC3_TRACK, recoveredKit(result), result.directory, File(temp, "re-card"),
        )
        assertTrue("Clipful Groove" in Acvs.read(re.primary).payloadText)
    }

    private fun recoveredKit(r: Mpc3Importer.ImportResult): Kit = KitStore.load(r.directory)

    @Test
    fun `commercial clips parse into grooves`() {
        val golden = File("../reference/golden/mpc3-track/Acoustic-Kit-BFD Funk Kit 95.xtd")
        if (!golden.isFile) {
            println("golden file absent - skipping")
            return
        }
        // Import refuses on missing samples, but the clip parse is what we
        // exercise here: read the track map directly.
        val project = com.snipsnap.mpc3.Mpc3Project.read(golden)
        val track = project.tracks.first()
        val clips = ((track["sharedClipMap"] as? com.snipsnap.json.JsonValue.Arr))?.items.orEmpty()
        assertEquals(4, clips.size, "the corpus kit ships four named clips")
    }
}
