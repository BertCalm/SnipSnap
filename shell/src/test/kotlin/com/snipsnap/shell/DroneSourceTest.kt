package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.loop.BlockBaker
import com.snipsnap.loop.DroneBlock
import com.snipsnap.loop.DroneFit
import com.snipsnap.loop.SampleSource
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.synth.ResinDrone
import com.snipsnap.synth.ResinVoice
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The grid's drone renderer (docs/superpowers/specs/2026-09-25-resin-drone-design.md). */
class DroneSourceTest {

    private object Nothing : SampleSource {
        override fun loop(sampleFile: String): Snip? = null
        override fun pad(kit: String, slot: Int): Snip? = null
    }

    private val session = SessionBuilder.empty(44_100)
    private val spec = ResinDrone.Spec(ResinVoice.BASS, emptyMap(), 0.5f, 1)

    /** A cheap stand-in render: a ramp, so slices are checkable by value, counted. */
    private class Counting(val sleepMs: Long = 0) {
        val calls = AtomicInteger(0)
        val render: (ResinDrone.Spec, Int, Long, Int) -> FloatArray = { _, _, frames, _ ->
            calls.incrementAndGet()
            if (sleepMs > 0) Thread.sleep(sleepMs)
            FloatArray(frames.toInt()) { it.toFloat() / frames }
        }
    }

    @Test
    fun `a drone's slices baked at once share one render`() {
        val counting = Counting(sleepMs = 200)
        val src = DroneSource(Nothing, counting.render)
        val blocks = DroneFit.slices(spec.toJson(), 33, 4)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val baked = pool.invokeAll(blocks.map { b -> Callable { BlockBaker.bake(b, session, src) } }).map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, counting.calls.get(), "four slices rendered the drone ${counting.calls.get()} times")
            val whole = 4 * session.intervalFrames
            val joined = baked.flatMap { it.samples.toList() }.toFloatArray()
            assertContentEquals(FloatArray(whole * 2) { (it / 2).toFloat() / whole }, joined, "slices end to end are one render")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a recipe it can't read goes to the source it wraps`() {
        val marker = Snip(FloatArray(8), 2, 44_100)
        val inner = object : SampleSource by Nothing {
            override fun drone(recipe: JsonValue, rootMidi: Int, frames: Long, sampleRate: Int): Snip = marker
        }
        val src = DroneSource(inner) { _, _, _, _ -> error("not a RESIN recipe") }
        assertSame(marker, src.drone(Json.parse("""{"engine":"VELVET"}"""), 33, 8, 44_100))
    }

    @Test
    fun `a render that throws is silence, and the next bake tries again`() {
        var fail = true
        val calls = AtomicInteger(0)
        val src = DroneSource(Nothing) { _, _, frames, _ ->
            calls.incrementAndGet()
            if (fail) error("render failed") else FloatArray(frames.toInt())
        }
        assertNull(src.drone(spec.toJson(), 33, 100, 44_100))
        val silent = BlockBaker.bake(DroneBlock(spec.toJson(), 33, 0, 1), session, DroneSource(Nothing) { _, _, _, _ -> error("x") })
        assertTrue(silent.samples.all { it == 0f })
        fail = false
        assertEquals(200, src.drone(spec.toJson(), 33, 100, 44_100)?.samples?.size)
        assertEquals(2, calls.get())
    }

    @Test
    fun `it holds one render per track, and forgets a drone's old length`() {
        val counting = Counting()
        val src = DroneSource(Nothing, counting.render)
        for (root in 33 until 33 + DroneSource.MAX_RESIDENT + 3) src.drone(spec.toJson(), root, 100, 44_100)
        assertEquals(DroneSource.MAX_RESIDENT, src.residentCount())

        val fresh = DroneSource(Nothing, counting.render)
        fresh.drone(spec.toJson(), 33, 100, 44_100)
        fresh.drone(spec.toJson(), 33, 200, 44_100) // the same drone after a tempo change
        assertEquals(1, fresh.residentCount())
    }

    @Test
    fun `a drone asked for again is kept over one nobody plays`() {
        val counting = Counting()
        val src = DroneSource(Nothing, counting.render)
        src.drone(spec.toJson(), 40, 100, 44_100)
        for (root in 41 until 41 + DroneSource.MAX_RESIDENT - 1) src.drone(spec.toJson(), root, 100, 44_100)
        src.drone(spec.toJson(), 40, 100, 44_100) // still playing
        src.drone(spec.toJson(), 60, 100, 44_100) // one more pushes out the oldest unasked
        val before = counting.calls.get()
        src.drone(spec.toJson(), 40, 100, 44_100)
        assertEquals(before, counting.calls.get(), "a drone still in use was re-rendered")
    }

    /** Magnitude of the component completing exactly [cycles] cycles in [s]. */
    private fun bin(s: FloatArray, cycles: Long): Double {
        var re = 0.0
        var im = 0.0
        for (i in s.indices) {
            val w = 2 * PI * cycles * i / s.size
            re += s[i] * cos(w)
            im -= s[i] * sin(w)
        }
        return hypot(re, im) / s.size
    }

    /** The grid and the renderer agree on the note: what DroneFit reads out is what plays. */
    @Test
    fun `the note the grid reads out is the note that plays`() {
        val root = 33
        val span = DroneFit.spanFor(root, session)
        val frames = span.toLong() * session.intervalFrames
        val snapped = DroneFit.snappedHz(DroneFit.hz(root), frames, session.sampleRate)
        val cycles = snapped * frames / session.sampleRate
        val whole = Math.round(cycles)
        assertEquals(whole.toDouble(), cycles, 1e-6)
        val s = ResinDrone.render(
            ResinDrone.Spec(ResinVoice.BASS, mapOf("STACK" to 0f, "CUTOFF" to 0.8f, "CREAM" to 0.2f), 0f, 1),
            root, frames, session.sampleRate,
        )
        val on = bin(s, whole)
        assertTrue(maxOf(bin(s, whole - 1), bin(s, whole + 1)) < on * 1e-4)
    }
}
