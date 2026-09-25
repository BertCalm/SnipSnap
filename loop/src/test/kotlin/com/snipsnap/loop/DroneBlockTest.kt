package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A drone on the grid: a recipe on the track, audio only at bake time. */
class DroneBlockTest {

    private val recipe = Json.parse("""{"engine":"TEST","macros":{"CUTOFF":0.35}}""")

    /** Hands back a mono ramp as the whole drone, so each slice's window is checkable by value. */
    private class RampSource(private val lengthFix: Int = 0, private val none: Boolean = false) : SampleSource {
        var calls = 0
        override fun loop(sampleFile: String): Snip? = null
        override fun pad(kit: String, slot: Int): Snip? = null
        override fun drone(recipe: JsonValue, rootMidi: Int, frames: Long, sampleRate: Int): Snip? {
            calls++
            if (none) return null
            val n = (frames + lengthFix).toInt()
            return Snip(FloatArray(n) { it.toFloat() / n }, 1, sampleRate)
        }
    }

    private val session = SessionBuilder.empty(44_100)

    @Test
    fun `each slice is its own window of the one render`() {
        val src = RampSource()
        val frames = session.intervalFrames
        val whole = 4 * frames
        for (slice in 0 until 4) {
            val baked = BlockBaker.bake(DroneBlock(recipe, 33, slice, 4), session, src)
            assertEquals(2, baked.channels)
            assertEquals(frames, baked.frameCount)
            val first = slice * frames
            assertEquals(first.toFloat() / whole, baked.samples[0])
            assertEquals(first.toFloat() / whole, baked.samples[1], "mono goes to both sides")
            assertEquals((first + frames - 1).toFloat() / whole, baked.samples[baked.samples.size - 1])
        }
    }

    @Test
    fun `no render, or the wrong length, is silence rather than a throw`() {
        for (src in listOf(RampSource(none = true), RampSource(lengthFix = -1), RampSource(lengthFix = 1))) {
            val baked = BlockBaker.bake(DroneBlock(recipe, 33, 0, 2), session, src)
            assertEquals(session.intervalFrames, baked.frameCount)
            assertTrue(baked.samples.all { it == 0f })
        }
    }

    @Test
    fun `a source with no renderer bakes a drone to silence`() {
        val plain = object : SampleSource {
            override fun loop(sampleFile: String): Snip? = null
            override fun pad(kit: String, slot: Int): Snip? = null
        }
        val baked = BlockBaker.bake(DroneBlock(recipe, 33, 0, 1), session, plain)
        assertTrue(baked.samples.all { it == 0f })
    }

    @Test
    fun `equal recipes parsed apart are equal blocks`() {
        val a = DroneBlock(Json.parse("""{"engine":"TEST","macros":{"CUTOFF":0.35}}"""), 33, 1, 2)
        val b = DroneBlock(Json.parse("""{"macros":{"CUTOFF":0.35},"engine":"TEST"}"""), 33, 1, 2)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `a drone block refuses a span, slice or root it cannot be`() {
        assertFailsWith<IllegalArgumentException> { DroneBlock(recipe, 33, 0, 3) }
        assertFailsWith<IllegalArgumentException> { DroneBlock(recipe, 33, 2, 2) }
        assertFailsWith<IllegalArgumentException> { DroneBlock(recipe, 128, 0, 1) }
    }

    @Test
    fun `sendDrone lands the span DroneFit asks for, engaged`() {
        val s = SessionBuilder.sendDrone(session, 3, "DRONE A1", recipe, 33)
        val t = s.tracks[3]
        assertEquals(DroneFit.spanFor(33, session), t.chain.size)
        assertEquals("DRONE A1", t.name)
        assertTrue(t.engaged)
        assertEquals(listOf(0, 1, 2, 3), t.chain.map { (it as DroneBlock).slice })
    }

    @Test
    fun `a drone survives the sidecar`() {
        val dir: File = Files.createTempDirectory("drone").toFile()
        try {
            val s = SessionBuilder.sendDrone(session, 1, "DRONE", recipe, 45)
            SessionStore.save(s, dir)
            assertEquals(s, SessionStore.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `slices laid end to end are the whole render`() {
        val src = RampSource()
        val out = (0 until 2).flatMap { BlockBaker.bake(DroneBlock(recipe, 33, it, 2), session, src).samples.toList() }
        val whole = 2 * session.intervalFrames
        val expected = FloatArray(whole * 2) { (it / 2).toFloat() / whole }
        assertContentEquals(expected, out.toFloatArray())
    }
}
