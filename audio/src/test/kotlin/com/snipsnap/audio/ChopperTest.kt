package com.snipsnap.audio

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.xpm.WavInfo
import com.snipsnap.xpm.XpmWriter
import java.io.File
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChopperTest {

    private val rate = 44_100
    private val temp: File = java.nio.file.Files.createTempDirectory("chopper").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun hit(into: FloatArray, atFrame: Int, amplitude: Float = 0.8f) {
        val length = (rate * 0.25).toInt()
        for (i in 0 until length) {
            val f = atFrame + i
            if (f >= into.size) break
            val t = i.toDouble() / rate
            into[f] += (amplitude * exp(-40.0 * t) * sin(2.0 * Math.PI * 180.0 * t)).toFloat()
        }
    }

    /** Four hits — a stand-in for a one-bar break. */
    private fun break4(): Snip {
        val buf = FloatArray(110_000)
        listOf(5_000, 30_000, 55_000, 80_000).forEach { hit(buf, it) }
        return Snip(buf, 1, rate)
    }

    @Test
    fun `auto slice count cuts at the knee between real hits and scraps`() {
        // Four loud hits, six faint ones: the knee sits after the four.
        val buf = FloatArray(rate * 5)
        listOf(5_000, 40_000, 75_000, 110_000).forEach { hit(buf, it, 0.9f) }
        listOf(145_000, 160_000, 175_000, 190_000, 205_000, 218_000).forEach { hit(buf, it, 0.18f) }
        assertEquals(4, Chopper.autoSliceCount(Snip(buf, 1, rate)))
    }

    @Test
    fun `auto slice count keeps hits that are all of a kind`() {
        // Eight equal hits, no knee: the audio asked for eight.
        val buf = FloatArray(rate * 5)
        (0 until 8).forEach { hit(buf, 5_000 + it * 26_000, 0.8f) }
        assertEquals(8, Chopper.autoSliceCount(Snip(buf, 1, rate)))

        assertEquals(0, Chopper.autoSliceCount(Snip(FloatArray(rate), 1, rate)), "silence wants nothing")
    }

    @Test
    fun `chops a break at its hits`() {
        val slices = Chopper.byTransients(break4())

        assertEquals(4, slices.size)
        assertTrue(slices.all { it.snip.frameCount > 0 })
        assertTrue(slices.all { it.onset != null })
    }

    @Test
    fun `slices run start to start, in order, covering to the end`() {
        val source = break4()
        val slices = Chopper.byTransients(source)

        for (i in 0 until slices.size - 1) {
            assertTrue(
                slices[i].sourceFrame < slices[i + 1].sourceFrame,
                "slices must be in time order",
            )
            assertEquals(
                slices[i + 1].sourceFrame - slices[i].sourceFrame,
                slices[i].snip.frameCount,
                "each slice should run to the next boundary",
            )
        }

        val last = slices.last()
        assertEquals(source.frameCount - last.sourceFrame, last.snip.frameCount)
    }

    @Test
    fun `each slice actually contains a hit`() {
        // The point of chopping on transients: every pad should have an attack
        // near its start, not silence followed by someone else's decay.
        val slices = Chopper.byTransients(break4())

        for (slice in slices) {
            val head = slice.snip.samples.take(2000).maxOf { kotlin.math.abs(it) }
            assertTrue(head > 0.05f, "slice at ${slice.sourceFrame} starts quiet — cut landed late")
        }
    }

    @Test
    fun `caps at the requested number of slices`() {
        val slices = Chopper.byTransients(break4(), maxSlices = 2)
        assertEquals(2, slices.size)
    }

    @Test
    fun `asking for no slices returns none`() {
        assertTrue(Chopper.byTransients(break4(), maxSlices = 0).isEmpty())
    }

    @Test
    fun `silence chops into nothing`() {
        assertTrue(Chopper.byTransients(Snip(FloatArray(50_000), 1, rate)).isEmpty())
    }

    @Test
    fun `equal parts divide evenly and reassemble`() {
        val source = break4()
        val slices = Chopper.intoEqualParts(source, 8)

        assertEquals(8, slices.size)
        assertEquals(source.frameCount, slices.sumOf { it.snip.frameCount })
        assertEquals(0, slices.first().sourceFrame)
    }

    @Test
    fun `equal parts gives the remainder to the last slice`() {
        // 1001 frames into 4 parts: 250, 250, 250, 251 — nothing dropped.
        val source = Snip(FloatArray(1001), 1, rate)
        val slices = Chopper.intoEqualParts(source, 4)

        assertEquals(1001, slices.sumOf { it.snip.frameCount })
        assertEquals(251, slices.last().snip.frameCount)
    }

    @Test
    fun `equal parts refuses nonsense`() {
        assertFailsWith<IllegalArgumentException> { Chopper.intoEqualParts(break4(), 0) }
        assertFailsWith<IllegalArgumentException> { Chopper.byTransients(break4(), maxSlices = -1) }
    }

    @Test
    fun `more parts than frames yields nothing rather than empty slices`() {
        assertTrue(Chopper.intoEqualParts(Snip(FloatArray(10), 1, rate), 100).isEmpty())
    }

    @Test
    fun `slice extraction is bounds safe`() {
        val source = break4()

        assertEquals(0, Chopper.slice(source, -100, -50).snip.frameCount)
        assertEquals(0, Chopper.slice(source, 500, 400).snip.frameCount)
        assertEquals(100, Chopper.slice(source, -100, 100).snip.frameCount)
        assertEquals(
            source.frameCount - 100,
            Chopper.slice(source, 100, source.frameCount + 9999).snip.frameCount,
        )
    }

    @Test
    fun `slice cleanup is optional and off by default`() {
        val source = break4()
        val raw = Chopper.slice(source, 0, 5000)
        assertEquals(5000, raw.snip.frameCount)

        // With trimming enabled the leading silence would go.
        val trimmed = Chopper.slice(source, 0, 5000, cleanup = CleanupConfig())
        assertTrue(trimmed.snip.frameCount < 5000)
    }

    @Test
    fun `slice cleanup preset fades but does not move the cut`() {
        val source = break4()
        val slice = Chopper.slice(source, 5_000, 15_000, cleanup = Chopper.SLICE_CLEANUP)

        assertEquals(10_000, slice.snip.frameCount, "trimming must not move a deliberate cut")
        assertTrue(
            kotlin.math.abs(slice.snip.samples.last()) < 0.05f,
            "boundary must be faded or the pad clicks",
        )
    }

    /**
     * A hit whose envelope is known to the sample: [peak] for 50 ms, then
     * [shoulder] until [dropAtSec], then [floor] to the end — alternating
     * sign so every 5 ms RMS is exactly the amplitude.
     */
    private fun envelope(seconds: Float, peak: Float, shoulder: Float, dropAtSec: Float, floor: Float): Snip {
        val n = (seconds * rate).toInt()
        val dropAt = (dropAtSec * rate).toInt()
        val peakEnd = (0.05f * rate).toInt()
        return Snip(FloatArray(n) { i -> (if (i < peakEnd) peak else if (i < dropAt) shoulder else floor) * (if (i % 2 == 0) 1f else -1f) }, 1, rate)
    }

    @Test
    fun `a ghost starts at the first 5 ms window under GHOST_DROP_DB of the hit's peak, and a window just above it is not one`() {
        val threshold = Math.pow(10.0, Chopper.GHOST_DROP_DB / 20.0).toFloat() // 0.126 of the peak
        val hit = Slice(Snip(FloatArray(1), 1, rate), sourceFrame = 0)
        // 0.13 of the peak is above the bar; 0.12 is under it: the ghost starts exactly where 0.12 begins.
        val snip = envelope(1f, 1f, 0.13f, 0.4f, 0.12f)
        val ghosts = Chopper.ghosts(snip, listOf(hit), cleanup = null)
        assertEquals(1, ghosts.size)
        assertEquals(0, ghosts[0].afterHit)
        // The first 5 ms window whose RMS is under the bar: it can begin a
        // few frames before the drop (a window straddling it already
        // averages under), never after it, and never back at the peak's 50 ms.
        val dropAt = (0.4f * rate).toInt()
        val window = (0.005f * rate).toInt()
        assertTrue(ghosts[0].slice.sourceFrame in (dropAt - window)..dropAt, "the first window under the bar: ${ghosts[0].slice.sourceFrame} vs the drop at $dropAt")
        assertEquals(snip.frameCount, ghosts[0].slice.sourceFrame + ghosts[0].slice.snip.frameCount, "and runs to the end")
        assertTrue(0.13f > threshold && 0.12f < threshold, "the fixture straddles the bar: $threshold")
        // Never under the bar: no ghost.
        assertTrue(Chopper.ghosts(envelope(1f, 1f, 0.13f, 0.4f, 0.13f), listOf(hit), cleanup = null).isEmpty(), "a shoulder that never drops is no ghost")
        // Under the bar too late to be GHOST_MIN_SEC long: no ghost.
        assertTrue(Chopper.ghosts(envelope(0.44f, 1f, 0.13f, 0.4f, 0.12f), listOf(hit), cleanup = null).isEmpty(), "40 ms of room is a scrap")
        // Two hits: the first's ghost ends where the second's cut begins.
        val second = Slice(Snip(FloatArray(1), 1, rate), sourceFrame = (0.7f * rate).toInt())
        val two = Chopper.ghosts(snip, listOf(hit, second), cleanup = null)
        assertEquals((0.7f * rate).toInt(), two[0].slice.sourceFrame + two[0].slice.snip.frameCount)
    }

    @Test
    fun `chopped break becomes a loadable kit`() {
        // The full auto-chop path: capture a break, chop it, write each piece as
        // a pad, and produce a program the MPC can load.
        val slices = Chopper.byTransients(break4(), maxSlices = 16, cleanup = Chopper.SLICE_CLEANUP)
        assertEquals(4, slices.size)

        val kitDir = File(temp, "Break Kit").apply { mkdirs() }

        val pads = slices.mapIndexed { i, slice ->
            val name = "SS_Chop_%02d".format(i + 1)
            val wav = WavWriter.write(File(kitDir, "$name.wav"), slice.snip)
            Pad(name, WavInfo.read(wav).frameCount)
        }

        XpmWriter().writeTo(kitDir, DrumProgram("Break Kit", pads))

        val xpm = File(kitDir, "Break Kit.xpm")
        assertTrue(xpm.exists())

        val xml = xpm.readText()
        pads.forEach { pad ->
            assertTrue("<SampleName>${pad.sampleName}</SampleName>" in xml)
            assertTrue("<SliceEnd>${pad.frameCount}</SliceEnd>" in xml)
        }

        // Four chops land on pads A01-A04.
        assertEquals(listOf("A01", "A02", "A03", "A04"), (1..4).map { PadNoteMap.labelForPad(it) })
        assertEquals(5, kitDir.listFiles()!!.size) // 4 WAVs + 1 program
    }
}
