package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.GrainField
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhotoFieldTest {

    private fun peakOf(a: FloatArray): Float = a.maxOf { abs(it) }

    /** Left half red, right half blue; top half bright, bottom half dark; a little texture so no cell is refused. */
    private fun scene(w: Int = 160, h: Int = 120): Photo = Photo.of(w, h) { x, y ->
        val wobble = ((x + y) % 5) * 8 - 16
        val bright = y < h / 2
        val base = if (bright) 200 else 60
        if (x < w / 2) Photo.rgb(base + wobble, 30, 30) else Photo.rgb(30, 30, base + wobble)
    }

    @Test
    fun `a field has a grain for every cell, laid out where the cell is`() {
        val field = PhotoField.build(scene())
        assertEquals(PhotoField.COLUMNS * PhotoField.ROWS, field.cells.size)
        assertEquals(field.cells.size, field.map.grains.size)
        assertEquals(GrainField.GRAIN_FRAMES, field.grainFrames)
        assertEquals(field.cells.size * field.grainFrames, field.source.frameCount)
        assertTrue(field.source.samples.all { it.isFinite() && it in -1f..1f })
        for ((i, g) in field.map.grains.withIndex()) {
            assertEquals(i * field.grainFrames, g.startFrame)
            assertTrue(g.x in 0f..1f && g.y in 0f..1f, "grain $i at (${g.x}, ${g.y})")
        }
        // Top-left cell is at the top left, bottom-right at the bottom right, y down like the canvas.
        assertTrue(field.map.grains.first().x < 0.1f && field.map.grains.first().y < 0.1f)
        assertTrue(field.map.grains.last().x > 0.9f && field.map.grains.last().y > 0.9f)
        // Every cell sounds.
        for (cell in field.cells) assertTrue(peakOf(field.grainOf(cell.column, cell.row)) > 0.1f, "silent cell $cell")
        // DUET has nothing to project into: no PCA basis behind a photo.
        assertEquals(null, field.map.projector)
    }

    @Test
    fun `colour sets the note and light sets the level, cell by cell`() {
        val field = PhotoField.build(scene())
        val left = field.cells.filter { it.column < PhotoField.COLUMNS / 2 }
        val right = field.cells.filter { it.column >= PhotoField.COLUMNS / 2 }
        // Red cells low, blue cells high — SNAP's own mapping, per cell.
        assertTrue(left.all { it.macros.getValue("TUNE") < 0.2f }, "red cells: ${left.map { it.macros["TUNE"] }.distinct()}")
        assertTrue(right.all { it.macros.getValue("TUNE") > 0.6f }, "blue cells: ${right.map { it.macros["TUNE"] }.distinct()}")
        assertTrue(!field.grainOf(0, 0).contentEquals(field.grainOf(PhotoField.COLUMNS - 1, 0)), "two colours, one sound")

        // Bright rows louder than dark rows, and the dark ones still there.
        // Measured on grey, since a red at full saturation is dim to the
        // eye (and to Rec. 601) whatever its red channel says.
        val shade = PhotoField.build(Photo.grey(160, 120) { x, y -> (if (y < 60) 0.9f else 0.12f) + ((x + y) % 5) * 0.01f })
        val topPeak = shade.cells.filter { it.row < PhotoField.ROWS / 2 }.map { peakOf(shade.grainOf(it.column, it.row)) }.average()
        val bottomPeak = shade.cells.filter { it.row >= PhotoField.ROWS / 2 }.map { peakOf(shade.grainOf(it.column, it.row)) }.average()
        assertTrue(topPeak > bottomPeak * 2, "top $topPeak vs bottom $bottomPeak")
        assertTrue(bottomPeak > 0.15, "the dark half must still speak: $bottomPeak")
    }

    @Test
    fun `a flat cell is a pure tone, not a refusal`() {
        // Clear sky: every cell one colour. The field must sound everywhere.
        val sky = Photo.of(64, 48) { _, _ -> Photo.rgb(120, 170, 230) }
        val field = PhotoField.build(sky)
        assertTrue(field.cells.all { it.flat })
        for (cell in field.cells) assertTrue(peakOf(field.grainOf(cell.column, cell.row)) > 0.3f)
        // And a sine it is: the pitch is the cell's own note.
        val grain = field.grainOf(3, 3)
        val expected = Snap.frequencyFor(field.cells[3 * PhotoField.COLUMNS + 3].macros.getValue("TUNE"))
        val measured = TestPitch.estimate(com.snipsnap.audio.Snip(grain, 1, Dsp.RATE), fromSec = 0.01f, windowSec = 0.07f)
        assertTrue(measured > expected * 0.9f && measured < expected * 1.1f, "expected ~$expected, got $measured")
    }

    @Test
    fun `a photo smaller than the grid still builds, and the grid is the caller's`() {
        val tiny = PhotoField.build(Photo.grey(5, 4) { x, y -> (x + y) / 7f })
        assertEquals(PhotoField.COLUMNS * PhotoField.ROWS, tiny.cells.size)
        assertTrue(tiny.source.samples.all { it.isFinite() })
        val one = PhotoField.build(Photo.grey(1, 1) { _, _ -> 0.5f }, columns = 2, rows = 2, grainFrames = 512)
        assertEquals(4, one.cells.size)
        assertEquals(2048, one.source.frameCount)
        assertFailsWith<IllegalArgumentException> { PhotoField.build(scene(), columns = 0) }
        assertFailsWith<IllegalArgumentException> { one.grainOf(2, 0) }
    }

    @Test
    fun `a field asks the voice to jitter its triggers, and here is why`() {
        val field = PhotoField.build(scene(), columns = 2, rows = 2)
        assertTrue(field.map.jitterTriggers, "steady tones from phase zero need scattered triggers")

        // The voice overlap-adds one grain every hop under a Hann window.
        // A steady tone whose period divides a fixed 512-frame hop badly
        // cancels itself: at 311 Hz (TUNE 0.75) the sum is near silence.
        // Random hops between half and one-and-a-half of that scatter the
        // phases and the tone comes back.
        val grain = Snap.grain(Draw.wave(Draw.Wave.SINE), mapOf("TUNE" to 0.75f, "BRIGHT" to 1f, "GRIT" to 0f), 4096)
        fun rms(a: FloatArray, from: Int, to: Int): Double {
            var sum = 0.0
            for (i in from until to) sum += a[i].toDouble() * a[i]
            return kotlin.math.sqrt(sum / (to - from))
        }
        fun overlapAdd(hops: List<Int>): FloatArray {
            val out = FloatArray(hops.sum() + grain.size)
            var t = 0
            for (hop in hops) {
                for (i in grain.indices) {
                    val w = 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / grain.size).toFloat())
                    out[t + i] += grain[i] * w
                }
                t += hop
            }
            return out
        }
        val fixed = overlapAdd(List(64) { 512 })
        val rnd = java.util.Random(3)
        val jittered = overlapAdd(List(64) { 256 + rnd.nextInt(512) })
        // Measured in the steady middle, past the first eight overlaps.
        val fixedRms = rms(fixed, 8192, 24576)
        val jitteredRms = rms(jittered, 8192, 24576)
        assertTrue(fixedRms < jitteredRms * 0.15, "fixed-hop comb $fixedRms should be far under jittered $jitteredRms")
    }

    @Test
    fun `a nearly flat cell leans toward a sine instead of a full-scale staircase`() {
        val sine = Draw.wave(Draw.Wave.SINE)
        // Four levels of swing: over FLAT_SWING, well under SOFT_SWING.
        val faint = IntArray(Snap.TABLE_SIZE) { 128 + (it * 4 / Snap.TABLE_SIZE) }
        val (table, weight) = PhotoField.cellTable(faint)
        assertTrue(weight > 0f && weight < 0.25f, "weight $weight")
        // Mostly sine: the table tracks the sine far more than the stair.
        var offSine = 0L
        for (i in table.indices) offSine += kotlin.math.abs(table[i] - sine[i])
        assertTrue(offSine / table.size < 40, "mean distance from the sine ${offSine / table.size}")
        // A flat line is the sine outright; a full-swing line is itself.
        assertEquals(0f, PhotoField.cellTable(IntArray(Snap.TABLE_SIZE) { 100 }).second)
        val ramp = IntArray(Snap.TABLE_SIZE) { it }
        val (whole, w1) = PhotoField.cellTable(ramp)
        assertEquals(1f, w1)
        assertTrue(whole.contentEquals(ramp))
        // And the field records the weight per cell.
        val field = PhotoField.build(Photo.grey(64, 48) { x, _ -> 0.5f + x / 64f * 0.03f }, columns = 4, rows = 3)
        assertTrue(field.cells.all { it.lineWeight in 0f..1f })
    }

    @Test
    fun `a field builds fast enough to wait for`() {
        val photo = Photo.of(512, 384) { x, y -> Photo.rgb((x * 255) / 511, (y * 255) / 383, ((x + y) % 7) * 30) }
        PhotoField.build(photo, columns = 2, rows = 2) // warm the JIT
        val t0 = System.nanoTime()
        PhotoField.build(photo)
        val ms = (System.nanoTime() - t0) / 1_000_000
        // Four seconds here was ten on a phone; native-rate grains brought
        // it under one. Generous so a slow CI runner does not fail it.
        assertTrue(ms < 3000, "a 512 px field took ${ms}ms")
    }

    @Test
    fun `the same photo builds the same field`() {
        val a = PhotoField.build(scene(), columns = 4, rows = 3)
        val b = PhotoField.build(scene(), columns = 4, rows = 3)
        assertTrue(a.source.samples.contentEquals(b.source.samples))
        assertEquals(a.map.grains, b.map.grains)
    }

    @Test
    fun `a grain is exactly as long as asked, and steady to its end`() {
        val grain = Snap.grain(Draw.wave(Draw.Wave.TRIANGLE), Snap.defaults(SnapVoice.DRAWN), 4096)
        assertEquals(4096, grain.size)
        assertTrue(grain.all { it.isFinite() && it in -1f..1f })
        // HOLD-shaped: the last tenth is as loud as the whole, bar the 4 ms fade.
        val tail = grain.copyOfRange(3400, 3900)
        assertTrue(peakOf(tail) > peakOf(grain) * 0.6f, "tail ${peakOf(tail)} vs ${peakOf(grain)}")
        assertFailsWith<IllegalArgumentException> { Snap.grain(IntArray(10), emptyMap(), 100) }
        assertFailsWith<IllegalArgumentException> { Snap.grain(Draw.wave(Draw.Wave.SINE), emptyMap(), 0) }
    }

    @Test
    fun `the cloud is the picture as a texture, a LOOP by the classifier's measure`() {
        val field = PhotoField.build(scene(), columns = 4, rows = 3)
        val cloud = PhotoField.cloud(field, seconds = 2.5f, seed = 7)
        assertTrue(abs(cloud.durationSeconds - 2.5f) < 0.05f)
        assertTrue(cloud.peak() > 0.5f && cloud.samples.all { it.isFinite() })
        assertEquals(DrumClass.LOOP, Classifier.classify(cloud).drumClass)
        // Seeded: the same picture and seed give the same cloud.
        assertTrue(PhotoField.cloud(field, seconds = 2.5f, seed = 7).samples.contentEquals(cloud.samples))
    }
}
