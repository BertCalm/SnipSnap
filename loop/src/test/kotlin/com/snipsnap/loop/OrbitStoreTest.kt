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
                name = "BASS",
                steps = 16,
                content = PatternOrbit("Break Kit", listOf(OrbitHit(0, 9, 0.9f), OrbitHit(4, 11, 0.8f))),
                voice = listOf(9, 10, 11, 12),
            ),
            Orbit(
                name = "THREE",
                steps = 3,
                lockToBar = true,
                content = PatternOrbit("Break Kit", listOf(OrbitHit(0, 4), OrbitHit(1, 4), OrbitHit(2, 4))),
                voice = listOf(4),
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
    fun `a version 1 file still loads - the mode becomes the lock and the hits become the voice`() {
        val dir = tempDir()
        File(dir, OrbitStore.FILE_NAME).writeText(
            """{"version": 1, "bpm": 92, "sampleRate": 48000, "lapSteps": 16, "orbits": [
               {"name": "FLOOR", "steps": 16, "mode": "SAME_SPEED", "engaged": true, "level": 1, "pan": 0,
                "content": {"type": "pattern", "kit": "Break Kit",
                            "hits": [{"step": 0, "slot": 2, "velocity": 0.9}, {"step": 4, "slot": 1, "velocity": 0.8}]}},
               {"name": "THREE", "steps": 3, "mode": "SAME_LAP", "engaged": true, "level": 1, "pan": 0,
                "content": {"type": "pattern", "kit": "Break Kit", "hits": [{"step": 0, "slot": 4, "velocity": 0.5}]}},
               {"name": "TAPE", "steps": 20, "mode": "SAME_SPEED", "engaged": true, "level": 1, "pan": 0,
                "content": {"type": "snip", "sampleFile": "a.wav"}}
            ]}""",
        )
        val loaded = OrbitStore.load(dir)
        assertEquals(listOf(false, true, false), loaded.orbits.map { it.lockToBar })
        assertEquals(listOf(1, 2), loaded.orbits[0].pads)
        assertEquals(listOf(4), loaded.orbits[1].pads)
        assertTrue(loaded.orbits[2].pads.isEmpty())
        // Saved again, it is a version 2 file with the voice written out.
        val text = OrbitStore.save(loaded, dir).readText()
        assertTrue(text.contains("\"version\":2") || text.contains("\"version\": 2"), text.take(80))
        assertTrue(text.contains("\"voice\""))
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
