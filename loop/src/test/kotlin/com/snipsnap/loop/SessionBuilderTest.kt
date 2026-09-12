package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionBuilderTest {

    private fun tempDir(): File =
        File.createTempFile("snipsnap-builder", "").let {
            it.delete(); it.mkdirs(); it.deleteOnExit(); it
        }

    private val rate = 44_100

    /** A tone, so a piece read back off disk can be told apart from padding. */
    private fun tone(frames: Int, channels: Int = 1): Snip {
        val samples = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (sin(f * 0.05) * 0.5).toFloat()
            for (c in 0 until channels) samples[f * channels + c] = v
        }
        return Snip(samples, channels, rate)
    }

    private fun fresh() = SessionBuilder.empty(rate)

    @Test
    fun `a fresh session is six empty tracks`() {
        val session = fresh()
        assertEquals(Session.TRACK_COUNT, session.tracks.size)
        assertEquals(0, SessionBuilder.filled(session))
        assertEquals(0, SessionBuilder.nextEmpty(session))
        for (track in session.tracks) {
            assertTrue(SessionBuilder.isEmpty(track), "a fresh track holds nothing")
            assertFalse(track.engaged, "an empty track is not engaged")
            assertEquals(listOf(SilenceBlock), track.chain)
        }
    }

    @Test
    fun `a snip two intervals long becomes a two block chain`() {
        val session = fresh()
        val frames = SessionBuilder.chunkFrames(session, rate)
        val dir = tempDir()

        val sent = assertNotNull(SessionBuilder.send(session, 0, "BREAK", "snip_1_break", tone(frames * 2), dir))

        assertEquals(2, sent.blocks)
        assertFalse(sent.truncated)
        val track = sent.session.tracks[0]
        assertEquals("BREAK", track.name)
        assertTrue(track.engaged)
        assertEquals(listOf(LoopBlock("snip_1_break_1.wav"), LoopBlock("snip_1_break_2.wav")), track.chain)
        for (block in track.chain.filterIsInstance<LoopBlock>()) {
            assertTrue(File(dir, block.sampleFile).isFile, "${block.sampleFile} should be on disk")
        }
    }

    @Test
    fun `every written piece is exactly one interval long`() {
        val session = fresh()
        val frames = SessionBuilder.chunkFrames(session, rate)
        val dir = tempDir()

        // Two and a half intervals: the last piece is the one that has to be
        // padded, and it must come back the same length as the other two.
        val sent = assertNotNull(SessionBuilder.send(session, 0, "TAKE", "snip_2_take", tone(frames * 5 / 2), dir))

        assertEquals(3, sent.blocks)
        for (block in sent.session.tracks[0].chain.filterIsInstance<LoopBlock>()) {
            assertEquals(frames, WavReader.read(File(dir, block.sampleFile)).frameCount, block.sampleFile)
        }
    }

    @Test
    fun `the tail of a short last piece is silence, not a repeat`() {
        val session = fresh()
        val frames = SessionBuilder.chunkFrames(session, rate)
        val dir = tempDir()

        val sent = assertNotNull(SessionBuilder.send(session, 0, "TAKE", "snip_3_take", tone(frames + 100), dir))
        assertEquals(2, sent.blocks)

        val last = WavReader.read(File(dir, (sent.session.tracks[0].chain[1] as LoopBlock).sampleFile))
        assertTrue(last.samples.take(100).any { it != 0f }, "the 100 real frames should carry audio")
        assertTrue(last.samples.drop(100).all { it == 0f }, "everything past the snip should be silence")
    }

    @Test
    fun `a snip shorter than one interval is a single padded block`() {
        val session = fresh()
        val dir = tempDir()

        val sent = assertNotNull(SessionBuilder.send(session, 0, "HIT", "snip_4_hit", tone(500), dir))

        assertEquals(1, sent.blocks)
        assertFalse(sent.truncated)
        assertEquals(
            SessionBuilder.chunkFrames(session, rate),
            WavReader.read(File(dir, "snip_4_hit_1.wav")).frameCount,
        )
    }

    @Test
    fun `a snip longer than the chain cap is truncated and says so`() {
        val session = fresh()
        val frames = SessionBuilder.chunkFrames(session, rate)
        val dir = tempDir()

        val sent = assertNotNull(
            SessionBuilder.send(session, 0, "LONG", "snip_5_long", tone(frames * (Session.MAX_CHAIN + 3)), dir),
        )

        assertEquals(Session.MAX_CHAIN, sent.blocks)
        assertTrue(sent.truncated, "a dropped tail must be reported, never silently lost")
        assertFalse(File(dir, "snip_5_long_${Session.MAX_CHAIN + 1}.wav").exists())
    }

    @Test
    fun `stereo survives the cut`() {
        val session = fresh()
        val frames = SessionBuilder.chunkFrames(session, rate)
        val dir = tempDir()

        assertNotNull(SessionBuilder.send(session, 0, "WIDE", "snip_6_wide", tone(frames, channels = 2), dir))

        val piece = WavReader.read(File(dir, "snip_6_wide_1.wav"))
        assertEquals(2, piece.channels)
        assertEquals(frames, piece.frameCount)
    }

    @Test
    fun `nothing to send is null, never an empty track pretending to be full`() {
        val session = fresh()
        val dir = tempDir()

        assertNull(SessionBuilder.send(session, 0, "NOTHING", "snip_7", Snip(FloatArray(0), 1, rate), dir))
        assertEquals(0, dir.listFiles()?.size ?: 0, "a refused send writes no files")
    }

    @Test
    fun `sends land on the next empty track and stop when all six are full`() {
        val dir = tempDir()
        var session = fresh()
        for (i in 0 until Session.TRACK_COUNT) {
            val at = SessionBuilder.nextEmpty(session)
            assertEquals(i, at)
            session = assertNotNull(SessionBuilder.send(session, at, "T$i", "snip_8_$i", tone(1_000), dir)).session
            assertEquals(i + 1, SessionBuilder.filled(session))
        }
        assertEquals(-1, SessionBuilder.nextEmpty(session), "a full grid has no next empty track")
    }

    @Test
    fun `sending the same snip twice writes the same files`() {
        val session = fresh()
        val dir = tempDir()

        val first = assertNotNull(SessionBuilder.send(session, 0, "TWICE", "snip_9_twice", tone(3_000), dir))
        val names = dir.list()!!.toSet()
        val second = assertNotNull(SessionBuilder.send(first.session, 1, "TWICE", "snip_9_twice", tone(3_000), dir))

        assertEquals(names, dir.list()!!.toSet(), "a re-send costs no extra files")
        assertEquals(first.session.tracks[0].chain, second.session.tracks[1].chain)
    }

    @Test
    fun `clearing a track empties it without deleting the audio`() {
        val session = fresh()
        val dir = tempDir()
        val sent = assertNotNull(SessionBuilder.send(session, 2, "GONE", "snip_10_gone", tone(3_000), dir))

        val cleared = SessionBuilder.clear(sent.session, 2)

        assertTrue(SessionBuilder.isEmpty(cleared.tracks[2]))
        assertFalse(cleared.tracks[2].engaged)
        assertEquals(SessionBuilder.EMPTY_TRACK, cleared.tracks[2].name)
        assertEquals(0, SessionBuilder.filled(cleared))
        assertTrue(File(dir, "snip_10_gone_1.wav").isFile, "clearing drops the arrangement, not the audio")
    }

    @Test
    fun `snips of different lengths give a cycle longer than one interval`() {
        val dir = tempDir()
        val session = fresh()
        val frames = SessionBuilder.chunkFrames(session, rate)

        // Two intervals and three intervals: the whole point of the grid — the
        // two chains only line up again every sixth interval.
        var built = assertNotNull(SessionBuilder.send(session, 0, "TWO", "snip_11_two", tone(frames * 2), dir)).session
        built = assertNotNull(SessionBuilder.send(built, 1, "THREE", "snip_11_three", tone(frames * 3), dir)).session

        assertEquals(6, Arrangement.cycleIntervals(built))
    }

    @Test
    fun `a built session round trips through disk`() {
        val dir = tempDir()
        val sent = assertNotNull(SessionBuilder.send(fresh(), 0, "ROUND", "snip_12_round", tone(3_000), dir))

        SessionStore.save(sent.session, dir)

        assertEquals(sent.session, SessionStore.load(dir))
    }

    @Test
    fun `an empty track bakes to exactly one interval of silence`() {
        val session = fresh()
        val baked = BlockBaker.bake(SilenceBlock, session, KitSampleSource(tempDir()))

        assertEquals(session.intervalFrames, baked.frameCount)
        assertEquals(2, baked.channels)
        assertTrue(baked.samples.all { it == 0f })
    }

    @Test
    fun `a piece written at the snip's rate bakes to the interval at the device's rate`() {
        val dir = tempDir()
        // Written at 44.1k, played back by a session running at 48k: the piece
        // is resampled, and it has to land inside BlockBaker's fit tolerance
        // rather than being sliced and re-placed.
        val session = fresh()
        val frames = SessionBuilder.chunkFrames(session, rate)
        val sent = assertNotNull(SessionBuilder.send(session, 0, "RATE", "snip_13_rate", tone(frames), dir))

        val atDevice = sent.session.copy(sampleRate = 48_000)
        val baked = BlockBaker.bake(atDevice.tracks[0].chain[0], atDevice, KitSampleSource(dir))

        assertEquals(atDevice.intervalFrames, baked.frameCount)
        assertTrue(baked.samples.any { it != 0f }, "the tone should survive the rate change")
    }
}
