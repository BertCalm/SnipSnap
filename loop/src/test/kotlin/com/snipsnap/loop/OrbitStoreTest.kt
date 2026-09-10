package com.snipsnap.loop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrbitStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("orbits").toFile()

    private val sample = OrbitSet(
        orbits = listOf(
            Orbit(
                name = "FLOOR",
                steps = 16,
                content = PatternOrbit("Break Kit", listOf(OrbitHit(0, 1, 0.9f), OrbitHit(4, 2, 0.8f))),
            ),
            Orbit(
                name = "THREE",
                steps = 3,
                mode = OrbitMode.SAME_LAP,
                content = PatternOrbit("Break Kit", listOf(OrbitHit(0, 4), OrbitHit(1, 4), OrbitHit(2, 4))),
                engaged = false,
                level = 0.5f,
                pan = -0.25f,
            ),
            Orbit(name = "TAPE", steps = 20, content = SnipOrbit("snip_1_bass.wav")),
        ),
        bpm = 96f,
        sampleRate = 44_100,
        lapSteps = 16,
    )

    @Test
    fun `round-trips byte-stable`() {
        val dir = tempDir()
        val file = OrbitStore.save(sample, dir)
        val loaded = OrbitStore.load(dir)
        assertEquals(sample, loaded)

        val first = file.readText()
        OrbitStore.save(loaded, dir)
        assertEquals(first, file.readText())
    }

    @Test
    fun `exists says whether a folder has rings`() {
        val dir = tempDir()
        assertFalse(OrbitStore.exists(dir))
        OrbitStore.save(sample, dir)
        assertTrue(OrbitStore.exists(dir))
    }

    @Test
    fun `refuses a version it does not read`() {
        val dir = tempDir()
        File(dir, OrbitStore.FILE_NAME).writeText("""{"version": 99, "orbits": []}""")
        val e = runCatching { OrbitStore.load(dir) }.exceptionOrNull()
        assertTrue(e is IllegalStateException, "expected a version refusal, got $e")
    }

    @Test
    fun `refuses an unknown mode rather than guessing`() {
        val dir = tempDir()
        File(dir, OrbitStore.FILE_NAME).writeText(
            """{"version": 1, "bpm": 90, "sampleRate": 48000, "orbits": [
               {"name": "x", "steps": 4, "mode": "SIDEWAYS", "content": {"type": "snip", "sampleFile": "a.wav"}}
            ]}""",
        )
        val e = runCatching { OrbitStore.load(dir) }.exceptionOrNull()
        assertTrue(e is IllegalStateException, "expected a mode refusal, got $e")
    }
}
