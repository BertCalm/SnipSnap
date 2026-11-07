package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Obbligato
import java.io.File
import org.junit.jupiter.api.TestInstance
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OBBLIGATO as a keys instrument: five held zones through both MPC generations and the phone's sidecar. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObbligatoPadMakerTest {

    private lateinit var dest: File

    @BeforeTest
    fun setUp() {
        dest = java.nio.file.Files.createTempDirectory("obbligatopad").toFile()
    }

    @AfterTest
    fun cleanUp() {
        dest.deleteRecursively()
    }

    private val spec = ObbligatoPadMaker.Spec(Obbligato.defaults(), releaseSeconds = 0.6f)

    private val notes: List<KeyNote> by lazy { ObbligatoPadMaker.zoneMidis(spec).map { ObbligatoPadMaker.renderZone(spec, it) } }

    @Test
    fun `five zones tile around C4 to C5 and root at the minor thirds`() {
        val (program, samples) = ObbligatoPadMaker.assemble("OBBLIGATO DUET", spec, notes)
        val zones = program.keygroups
        assertEquals(listOf(60, 63, 66, 69, 72), zones.map { it.rootNote })
        for (i in 1 until zones.size) assertEquals(zones[i - 1].highNote + 1, zones[i].lowNote, "zones tile without gaps")
        assertEquals(51, zones.first().lowNote)
        assertEquals(81, zones.last().highNote)
        assertEquals(0.6f, program.volumeRelease)
        assertEquals(5, samples.size)
        zones.forEachIndexed { i, kg ->
            val layer = kg.layers.single()
            assertEquals(notes[i].loopStartFrame, layer.loopStartFrame)
            assertEquals(notes[i].snip.frameCount.toLong(), layer.frameCount)
        }
    }

    @Test
    fun `every zone's loop survives into both generations and the phone's sidecar`() {
        val name = "OBBLIGATO DUET"
        val program = ObbligatoPadMaker.export(name, spec, notes, dest)
        val dataDir = File(dest, Mpc3TrackWriter.trackDataDirName(name))
        val xpm = dataDir.listFiles { f -> f.extension == "xpm" }!!.single().readText()
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm, "MPC 2 idiom: SliceLoop=1")
        for (n in notes) assertTrue("<SliceLoopStart>${n.loopStartFrame}</SliceLoopStart>" in xpm, "loop ${n.loopStartFrame} in the .xpm")
        val xty = Mpc3TrackWriter().keygroupPayloadText(program)
        assertTrue("\"LoopMode\": 1" in xty, "MPC 3 idiom: LoopMode=1")
        val (_, instrument) = InstrumentStore.list(dest).single()
        assertEquals(5, instrument.zones.size)
        assertTrue(instrument.zones.all { it.loopStartFrame > 0 }, "every zone holds")
    }
}
