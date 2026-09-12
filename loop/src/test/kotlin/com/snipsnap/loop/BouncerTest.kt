package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BouncerTest {

    private class FakeSource(private val loops: Map<String, Snip>) : SampleSource {
        override fun loop(sampleFile: String): Snip? = loops[sampleFile]
        override fun pad(kit: String, slot: Int): Snip? = null
    }

    /** A short session so a full cycle is quick to render. */
    private fun session(vararg sizes: Int) = Session(
        tracks = sizes.mapIndexed { i, n ->
            Track("t$i", (1..n).map { LoopBlock("t$i-$it.wav") })
        },
        bpm = 200f,
        barsPerInterval = 1,
        sampleRate = 48_000,
    )

    /**
     * Left/right channels differ by default (right is left's negative half),
     * so a fixture built from this always carries a discriminating signal —
     * a per-channel bug (e.g. dropping the right channel, or a sink that
     * retains the caller's reused buffer) cannot hide behind a test that
     * only bothers to check one index. Pass `right` explicitly to opt out.
     */
    private fun dc(frames: Int, level: Float, right: Float = -level / 2f) =
        Snip(FloatArray(frames * 2) { if (it % 2 == 0) level else right }, 2, 48_000)

    private fun sourceFor(session: Session, level: Float): SampleSource {
        val map = HashMap<String, Snip>()
        for (t in session.tracks) {
            for (b in t.chain) map[(b as LoopBlock).sampleFile] = dc(session.intervalFrames, level)
        }
        return FakeSource(map)
    }

    @Test
    fun `renders one full cycle by default`() {
        val s = session(2, 3, 1, 1, 1, 1) // LCM(2,3) = 6
        val out = Bouncer.render(s, sourceFor(s, 0.1f))
        assertEquals(6 * s.intervalFrames, out.frameCount)
        assertEquals(48_000, out.sampleRate)
        assertEquals(2, out.channels)
    }

    @Test
    fun `renders an explicit number of intervals`() {
        val s = session(2, 3, 1, 1, 1, 1)
        val out = Bouncer.render(s, sourceFor(s, 0.1f), intervals = 2)
        assertEquals(2 * s.intervalFrames, out.frameCount)
    }

    @Test
    fun `sums all six tracks`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val out = Bouncer.render(s, sourceFor(s, 0.1f))
        // Six tracks at 0.1 each, centre-panned, is 0.6.
        assertTrue(abs(out.samples[100] - 0.6f) < 1e-5f, "got ${out.samples[100]}")
        // Right channel is dc()'s asymmetric default, -0.05 per track: six
        // tracks summed is -0.3. A sink or mixer that dropped or aliased the
        // right channel would pass the left-only check above and fail here.
        assertTrue(abs(out.samples[101] - -0.3f) < 1e-5f, "got ${out.samples[101]}")
    }

    @Test
    fun `writes a wav a reader can decode`() {
        // Track 0's two blocks must differ, or a sink that retains the
        // caller's reused buffer (the exact hazard AudioSink's contract warns
        // about) would pass every assertion here just as easily as a correct
        // one: same rate, same channel count, same frame count, and — with a
        // uniform-level fixture — identical samples too, since both intervals
        // would render the same thing regardless of aliasing.
        val s = session(2, 1, 1, 1, 1, 1)
        val map = HashMap<String, Snip>()
        map["t0-1.wav"] = dc(s.intervalFrames, 0.5f)
        map["t0-2.wav"] = dc(s.intervalFrames, 0.1f)
        for (t in s.tracks.drop(1)) {
            for (b in t.chain) map[(b as LoopBlock).sampleFile] = dc(s.intervalFrames, 0f)
        }

        val file = File.createTempFile("snipsnap-bounce", ".wav")
        file.deleteOnExit()

        WavSink(file, s.sampleRate).use { sink ->
            Bouncer.toSink(s, FakeSource(map), sink, intervals = 2)
        }

        val decoded = WavReader.read(file)
        assertEquals(48_000, decoded.sampleRate)
        assertEquals(2, decoded.channels)
        assertEquals(2 * s.intervalFrames, decoded.frameCount)

        // Interval 0 is track 0's first block (0.5/-0.25); interval 1 is its
        // second (0.1/-0.05). Under aliasing, WavSink.write would retain the
        // caller's reused buffer, so both written chunks would read back as
        // whatever was mixed last — interval 1's values — and the two
        // first-interval assertions below would fail.
        assertTrue(abs(decoded.samples[100] - 0.5f) < 1e-5f, "got ${decoded.samples[100]}")
        assertTrue(abs(decoded.samples[101] - -0.25f) < 1e-5f, "got ${decoded.samples[101]}")
        assertTrue(
            abs(decoded.samples[(s.intervalFrames + 100) * 2] - 0.1f) < 1e-5f,
            "got ${decoded.samples[(s.intervalFrames + 100) * 2]}",
        )
        assertTrue(
            abs(decoded.samples[(s.intervalFrames + 100) * 2 + 1] - -0.05f) < 1e-5f,
            "got ${decoded.samples[(s.intervalFrames + 100) * 2 + 1]}",
        )
    }

    @Test
    fun `a muted track contributes nothing`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val muted = s.copy(tracks = s.tracks.mapIndexed { i, t -> t.copy(engaged = i == 0) })
        val out = Bouncer.render(muted, sourceFor(s, 0.1f))
        assertTrue(abs(out.samples[100] - 0.1f) < 1e-5f, "got ${out.samples[100]}")
        // Right channel from the one surviving track: dc()'s default is -0.05.
        assertTrue(abs(out.samples[101] - -0.05f) < 1e-5f, "got ${out.samples[101]}")
    }

    @Test
    fun `the second interval differs from the first when a chain advances`() {
        // Track 0 has two blocks with different levels: interval 0 and interval
        // 1 must not be identical, or the chain never advanced.
        val s = session(2, 1, 1, 1, 1, 1)
        val map = HashMap<String, Snip>()
        map["t0-1.wav"] = dc(s.intervalFrames, 0.5f)
        map["t0-2.wav"] = dc(s.intervalFrames, 0.1f)
        for (t in s.tracks.drop(1)) {
            for (b in t.chain) map[(b as LoopBlock).sampleFile] = dc(s.intervalFrames, 0f)
        }
        val out = Bouncer.render(s, FakeSource(map), intervals = 2)

        val first = out.samples[100]
        val second = out.samples[(s.intervalFrames + 100) * 2]
        assertTrue(abs(first - 0.5f) < 1e-5f, "first interval was $first")
        assertTrue(abs(second - 0.1f) < 1e-5f, "second interval was $second")

        // Right channel of the same two frames: dc(0.5f)'s default is -0.25,
        // dc(0.1f)'s is -0.05. Only track 0 is non-silent, so these are the
        // whole mix.
        val firstRight = out.samples[101]
        val secondRight = out.samples[(s.intervalFrames + 100) * 2 + 1]
        assertTrue(abs(firstRight - -0.25f) < 1e-5f, "first interval right was $firstRight")
        assertTrue(abs(secondRight - -0.05f) < 1e-5f, "second interval right was $secondRight")
    }

    @Test
    fun `refuses to render a pathological cycle that would OOM`() {
        // Chain lengths of 5, 7, 8 and 3 (each within Session.MAX_CHAIN) give
        // an LCM of 840. At 8 bars and 40 BPM (both legal extremes) that is
        // gigabytes of interleaved float audio buffered at once — a render
        // this large must fail with a clear message, not an OOM.
        val tracks = listOf(5, 7, 8, 3, 1, 1).mapIndexed { i, n ->
            Track("t$i", (1..n).map { LoopBlock("t$i-$it.wav") })
        }
        val s = Session(tracks = tracks, bpm = 40f, barsPerInterval = 8, sampleRate = 48_000)
        assertEquals(840, Arrangement.cycleIntervals(s))

        val ex = assertFailsWith<IllegalArgumentException> {
            Bouncer.render(s, FakeSource(emptyMap()))
        }
        assertTrue(ex.message!!.contains("840"), "message should name the cycle length: ${ex.message}")
        assertTrue(ex.message!!.contains("intervals"), "message should name the interval count: ${ex.message}")
    }

    @Test
    fun `a second close does not overwrite the file`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val map = HashMap<String, Snip>()
        for (t in s.tracks) for (b in t.chain) map[(b as LoopBlock).sampleFile] = dc(s.intervalFrames, 0.5f)

        val file = File.createTempFile("snipsnap-bounce-idempotent", ".wav")
        file.deleteOnExit()

        val sink = WavSink(file, s.sampleRate)
        Bouncer.toSink(s, FakeSource(map), sink, intervals = 1)
        sink.close()
        val sizeAfterFirstClose = file.length()
        val decodedAfterFirstClose = WavReader.read(file)
        assertEquals(s.intervalFrames, decodedAfterFirstClose.frameCount)

        // Closeable.use{} calls close() on the way out even if the caller
        // also closed explicitly; a second close must be a no-op.
        sink.close()

        assertEquals(sizeAfterFirstClose, file.length(), "second close must not rewrite the file")
        val decoded = WavReader.read(file)
        assertEquals(
            s.intervalFrames,
            decoded.frameCount,
            "second close must not truncate the file to a 0-sample WAV",
        )
    }

    // ---- intervalsWithin: what a bounce is allowed to be ----

    @Test
    fun `a cycle that fits is bounced whole`() {
        // 2 and 3 blocks meet every 6 intervals; at 200 BPM and one bar each
        // that is 7.2 seconds, well inside any sane ceiling.
        val s = session(2, 3, 1, 1, 1, 1)
        assertEquals(6, Arrangement.cycleIntervals(s))
        assertEquals(6, Bouncer.intervalsWithin(s, maxSeconds = 180f))
    }

    @Test
    fun `a cycle too long for the ceiling is cut to whole intervals`() {
        val s = session(5, 7, 8, 3, 1, 1)
        val cycle = Arrangement.cycleIntervals(s)
        assertEquals(840, cycle, "the KDoc's own example, so this test is about the case it describes")

        val intervalSeconds = s.intervalFrames.toDouble() / s.sampleRate
        val n = Bouncer.intervalsWithin(s, maxSeconds = 180f)

        assertTrue(n < cycle, "840 intervals is far past three minutes")
        assertTrue(n * intervalSeconds <= 180.0, "$n intervals is ${n * intervalSeconds}s, over the ceiling")
        assertTrue((n + 1) * intervalSeconds > 180.0, "one more interval should not have fitted")
    }

    @Test
    fun `never fewer than one interval, even under a ceiling shorter than one`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val intervalSeconds = s.intervalFrames.toDouble() / s.sampleRate
        assertTrue(intervalSeconds > 0.1, "this test needs an interval longer than the ceiling it passes")

        // A bounce of nothing is not an answer: the caller gets one whole
        // interval and the snip store's own cap decides the rest.
        assertEquals(1, Bouncer.intervalsWithin(s, maxSeconds = 0.1f))
    }

    @Test
    fun `a ceiling must be a real length`() {
        assertFailsWith<IllegalArgumentException> { Bouncer.intervalsWithin(session(1, 1, 1, 1, 1, 1), 0f) }
    }

    @Test
    fun `what intervalsWithin allows is what render will actually take`() {
        val s = session(2, 3, 1, 1, 1, 1)
        val n = Bouncer.intervalsWithin(s, maxSeconds = 180f)

        val rendered = Bouncer.render(s, sourceFor(s, 0.5f), n)

        // The bounce is exactly the intervals it said, to the frame — the
        // number the screen prints as bars is this same n.
        assertEquals(s.intervalFrames * n, rendered.frameCount)
    }
}
