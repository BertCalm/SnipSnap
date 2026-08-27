package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionBuilderTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("session").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** Two kits with deliberately colliding sample names. */
    private fun makeKit(name: String, seed: Int, tempo: Float? = null, groove: String? = null): File {
        val dir = File(temp, name)
        dir.mkdirs()
        WavWriter.write(
            File(dir, "A01_Kick_01.wav"),
            Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / (10.0 + seed))).toFloat() }, 1, 44_100)),
        )
        KitStore.save(
            Kit(
                name,
                listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)),
                tempoBpm = tempo,
            ),
            dir,
        )
        groove?.let {
            GrooveStore.save(dir, listOf(Mpc3Clip(it, 1, listOf(Mpc3Note(36, 0, 0.9f, 240)))))
        }
        return dir
    }

    private fun heldNote(): Snip {
        val n = 44_100 * 2
        return Snip(
            FloatArray(n) { i ->
                val t = i.toDouble() / 44_100
                ((0.5 * sin(2 * PI * 220 * t)).toFloat() *
                    (if (t < 0.01) (t / 0.01).toFloat() else Math.exp(-0.25 * t).toFloat()))
            },
            1, 44_100,
        )
    }

    @Test
    fun `two colliding kits and an instrument become one session`() {
        val kitA = makeKit("Drums A", 1, tempo = 96.5f, groove = "A Groove")
        val kitB = makeKit("Drums B", 5, groove = "B Groove")
        val keys = OneNote.multiProgram("Session Keys", listOf("note.wav" to heldNote()))

        val result = SessionBuilder.build(
            "Test Session",
            listOf(kitA, kitB),
            File(temp, "card"),
            instruments = listOf(keys.program to keys.samples),
        )
        assertEquals(listOf("Drums A", "Drums B"), result.kitTracks)
        assertEquals(listOf("Session Keys"), result.instrumentTracks)
        assertEquals(96.5f, result.tempoBpm)

        // Colliding stems survived via per-kit prefixes, bytes distinct.
        val wavs = result.dataDir.listFiles { f: File -> f.extension == "wav" }!!.map { it.name }.sorted()
        assertTrue(wavs.any { it.startsWith("K1_") } && wavs.any { it.startsWith("K2_") }, "$wavs")
        val a = File(result.dataDir, wavs.first { it.startsWith("K1_") }).readBytes()
        val b = File(result.dataDir, wavs.first { it.startsWith("K2_") }).readBytes()
        assertTrue(!a.contentEquals(b), "the two kicks must stay different audio")

        // The reader accepts it: project, right tracks + infra.
        val read = Mpc3Project.read(result.xpj)
        assertTrue(read.isProject)
        assertTrue(read.trackNames.containsAll(listOf("Drums A", "Drums B", "Session Keys", "Submix 1")), "tracks: ${read.trackNames}")

        // Every kit's grooves ride the timeline — the corpus rule for
        // projects: hoisted tracks carry no embedded clips, the sequences
        // do. Both kits' patterns are in the project, not just the first's.
        val payload = Acvs.read(result.xpj).payloadText
        assertTrue("A Groove" in payload)
        assertTrue("B Groove" in payload, "the second kit's groove rides its own track's clip map")
        assertTrue("\"masterTempo\": 96.5" in payload)

        // GG3.1: song slot 1 wears the session's name; the other 31 stay
        // the corpus's own empty slots (steps wait on the bench capture).
        assertTrue("\"name\": \"Test Session\"" in payload, "song slot 1 named after the session")
        assertEquals(31, Regex("\\(unnamed\\)").findAll(payload).count())
    }

    @Test
    fun `a kit's four variations arrive as four switchable sequences`() {
        val dir = makeKit("Vari Kit", 1, tempo = 92f, groove = "Vari Groove")
        val base = GrooveStore.load(dir).first()
        GrooveStore.save(dir, GrooveVariations.standard(base, swingPercent = 62))

        val result = SessionBuilder.build("Vari Session", listOf(dir), File(temp, "vari-out"))
        val payload = Acvs.read(result.xpj).payloadText
        for (name in listOf("Vari Groove", "Vari Swing 62", "Vari Half", "Vari Sparse")) {
            assertTrue("\"name\": \"$name\"" in payload, name)
        }
        // Keyed 0..3, the corpus list idiom - currentSequence picks by key.
        for (k in 0..3) assertTrue("\"key\": $k" in payload, "sequence key $k")
    }

    @Test
    fun `sessions refuse broken kits and duplicate destinations`() {
        val ok = makeKit("Fine", 2)
        val broken = makeKit("Broken", 3)
        File(broken, "A01_Kick_01.wav").delete()
        kotlin.test.assertFailsWith<ExportBlockedException> {
            SessionBuilder.build("S", listOf(ok, broken), File(temp, "c1"))
        }

        SessionBuilder.build("Dup", listOf(ok), File(temp, "c2"))
        kotlin.test.assertFailsWith<java.io.IOException> {
            SessionBuilder.build("Dup", listOf(ok), File(temp, "c2"))
        }
        SessionBuilder.build("Dup", listOf(ok), File(temp, "c2"), overwrite = true)
    }
}
