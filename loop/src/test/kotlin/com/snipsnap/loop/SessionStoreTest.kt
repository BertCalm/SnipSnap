package com.snipsnap.loop

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionStoreTest {

    private fun tempDir(): File =
        File.createTempFile("snipsnap-session", "").let {
            it.delete(); it.mkdirs(); it.deleteOnExit(); it
        }

    private fun session() = Session(
        tracks = listOf(
            Track("Drums", listOf(LoopBlock("kick.wav"), LoopBlock("break.wav"))),
            Track("Percussion", listOf(LoopBlock("shaker.wav")), engaged = false),
            Track("Bass", listOf(LoopBlock("sub.wav")), level = 0.8f, pan = -0.25f),
            Track("Lead", listOf(PatternBlock("Keys", listOf(Step(0, 1), Step(8, 3, 0.6f, 120))))),
            Track("Pads", listOf(LoopBlock("wash.wav"))),
            Track("Vocal Samples", listOf(LoopBlock("adlib.wav"))),
        ),
        bpm = 92f,
        barsPerInterval = 4,
        sampleRate = 48_000,
    )

    @Test
    fun `round trips a session through disk`() {
        val dir = tempDir()
        SessionStore.save(session(), dir)
        val loaded = SessionStore.load(dir)
        assertEquals(session(), loaded)
    }

    @Test
    fun `writes the sidecar next to the audio`() {
        val dir = tempDir()
        val file = SessionStore.save(session(), dir)
        assertEquals(SessionStore.FILE_NAME, file.name)
        assertEquals(dir, file.parentFile)
        assertTrue(file.readText().contains("\"version\""), "sidecar should carry a version")
    }

    @Test
    fun `preserves every step field`() {
        val dir = tempDir()
        SessionStore.save(session(), dir)
        val block = SessionStore.load(dir).tracks[3].chain[0] as PatternBlock

        assertEquals("Keys", block.kit)
        assertEquals(2, block.steps.size)
        assertEquals(Step(8, 3, 0.6f, 120), block.steps[1])
    }

    @Test
    fun `preserves engaged level and pan`() {
        val dir = tempDir()
        SessionStore.save(session(), dir)
        val loaded = SessionStore.load(dir)

        assertTrue(!loaded.tracks[1].engaged, "mute state lost")
        assertEquals(0.8f, loaded.tracks[2].level)
        assertEquals(-0.25f, loaded.tracks[2].pan)
    }

    @Test
    fun `fails clearly when there is no sidecar`() {
        assertFailsWith<IOException> { SessionStore.load(tempDir()) }
    }

    @Test
    fun `refuses a version it does not know`() {
        // Hand-written rather than a string replacement on saved output: the
        // exact spacing Json.write emits is not this test's business, and a
        // test that breaks when the formatter changes is a trap.
        val dir = tempDir()
        File(dir, SessionStore.FILE_NAME).writeText(
            """{"version":99,"bpm":90,"barsPerInterval":4,"sampleRate":48000,"tracks":[]}""",
        )

        val e = assertFailsWith<IllegalStateException> { SessionStore.load(dir) }
        assertTrue(e.message!!.contains("99"), "message should name the version: ${e.message}")
    }
}
