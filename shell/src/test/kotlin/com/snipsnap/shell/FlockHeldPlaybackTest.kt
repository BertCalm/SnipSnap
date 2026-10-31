package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.FlockTouchKeys
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Note-off acts on the voice's current phase, including an arrival that has not reached its loop. */
class FlockHeldPlaybackTest {
    private val rate = 44_100
    private val root = 60

    private fun loaded(samples: FloatArray, loopStart: Int, release: Float): InstrumentEngine.Loaded {
        val zone = InstrumentStore.Zone(root, root, root, "ribbon.wav", samples.size.toLong(), loopStart.toLong())
        return InstrumentEngine.Loaded(InstrumentStore.Instrument("RIBBON", release, listOf(zone)), rate, listOf(samples))
    }

    private fun render(engine: InstrumentEngine, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        engine.render(out, frames)
        return out
    }

    @Test
    fun `release follows the declared time before the loop inside it and across its next wrap`() {
        val intro = rate / 5
        val end = 3 * rate / 5
        // A positive carrier makes its envelope observable at every phase. Its loop
        // contains whole periods; the reference voice supplies the actual sample path.
        val samples = FloatArray(end) { (.125 + .0625 * sin(2 * PI * 100 * it / rate)).toFloat() }
        val phases = listOf("arrival" to intro / 2, "loop middle" to (intro + end) / 2, "before wrap" to end - 1)
        for (release in listOf(.1f, .6f, 1.5f)) for ((phase, heldFrames) in phases) {
            val source = loaded(samples, intro, release)
            val releasing = InstrumentEngine(source, rate)
            val held = InstrumentEngine(source, rate)
            assertTrue(releasing.noteOn(root))
            assertTrue(held.noteOn(root))
            render(releasing, heldFrames)
            render(held, heldFrames)
            releasing.noteOff(root)
            val releaseFrames = (release * rate).roundToInt()
            val frames = releaseFrames + rate / 50
            val released = render(releasing, frames)
            val reference = render(held, frames)
            assertTrue(released.all(Float::isFinite), "$release s / $phase produced non-finite playback")
            for (fraction in listOf(.25, .75)) {
                val frame = (fraction * releaseFrames).roundToInt()
                val observed = released[frame * 2] / reference[frame * 2]
                assertEquals((1.0 - fraction).toFloat(), observed, .005f,
                    "$release s / $phase did not release from the current sample phase")
            }
            assertTrue(released.take(2 * (releaseFrames / 10)).any { it != 0f }, "$release s / $phase stopped at note-off")
            val silenceFrom = releaseFrames + rate / 100
            assertTrue(released.copyOfRange(silenceFrom * 2, released.size).all { it == 0f },
                "$release s / $phase continued beyond its release")
            assertTrue(releasing.activeNotes.isEmpty(), "$release s / $phase left a voice active")
            assertEquals(listOf(root), held.activeNotes, "the un-released reference must still be looping")
        }
    }

    @Test
    fun `a real RIBBON zone preserves the captured wrap and releases without replaying its arrival`() {
        val patch = FlockPatch("HELD RIBBON", FlockVoice.RIBBON, Flock.defaults(FlockVoice.RIBBON),
            midi = root, seed = 42001L, playback = FlockPlayback.HELD, releaseSeconds = .6f)
        val spec = FlockPadMaker.Spec(patch)
        val note = FlockPadMaker.renderZone(spec, root)
        val source = loaded(note.snip.samples, note.loopStartFrame.toInt(), spec.releaseSeconds)
        val engine = InstrumentEngine(source, rate)
        assertTrue(engine.noteOn(root))
        // Include the first actual wrap. This compares the player's output to the
        // captured file and its marker; it does not assert that repeating a file proves a seam.
        val heldFrames = note.snip.frameCount + 257
        val held = render(engine, heldFrames)
        for (frame in note.snip.frameCount - 2 until heldFrames) {
            val sampleFrame = if (frame < note.snip.frameCount) frame
                else note.loopStartFrame.toInt() + frame - note.snip.frameCount
            assertEquals(note.snip.samples[sampleFrame] * InstrumentEngine.VOICE_LEVEL, held[frame * 2], 1e-7f,
                "frame $frame did not follow the declared [loopStart, end) region")
        }
        assertEquals(listOf(root), engine.activeNotes)
        engine.noteOff(root)
        val released = render(engine, ((spec.releaseSeconds + .03f) * rate).roundToInt())
        assertTrue(released.take(rate / 10).any { abs(it) > 1e-5f }, "the real captured note stopped abruptly")
        assertTrue(released.takeLast(rate / 50).all { it == 0f }, "the captured voice outlived its native release")
        assertTrue(engine.activeNotes.isEmpty())
    }

    @Test
    fun `three real touch performances each use their own captured loop and release from the played phase`() {
        val patch = FlockPatch("RIBBON TOUCH", FlockVoice.RIBBON, Flock.defaults(FlockVoice.RIBBON),
            midi = root, seed = 42001L, playback = FlockPlayback.HELD, releaseSeconds = .3f)
        val spec = FlockPadMaker.Spec(patch, touchLayers = true)
        val notes = FlockPadMaker.renderLayers(spec, root)
        val layers = notes.mapIndexed { i, note ->
            val window = FlockTouchKeys.VELOCITY_WINDOWS[i]
            InstrumentStore.Layer("touch$i.wav", note.snip.frameCount.toLong(), note.loopStartFrame,
                window.first, window.last)
        }
        val hard = layers.last()
        val instrument = InstrumentStore.Instrument(patch.name, patch.releaseSeconds,
            listOf(InstrumentStore.Zone(root, root, root, hard.sample, hard.frameCount, hard.loopStartFrame, layers)))
        val pcm = layers.mapIndexed { i, layer -> layer.sample to notes[i].snip.samples }.toMap()
        for ((i, layer) in layers.withIndex()) {
            val velocity = ((layer.velStart + layer.velEnd) / 2) / 127f
            val hit = requireNotNull(KeyHit.resolve(instrument, root, velocity) { pcm[it]?.size?.toLong() })
            assertEquals(layer.sample, hit.sampleFile)
            val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, pcm), rate)
            assertTrue(player.noteOn(root, velocity))
            val heldFrames = notes[i].snip.frameCount + 257
            val held = render(player, heldFrames)
            for (frame in notes[i].snip.frameCount - 2 until heldFrames) {
                val sourceFrame = if (frame < notes[i].snip.frameCount) frame
                    else notes[i].loopStartFrame.toInt() + frame - notes[i].snip.frameCount
                assertEquals(notes[i].snip.samples[sourceFrame] * hit.gain, held[frame * 2], 1e-7f,
                    "layer $i must wrap at its own source endpoint")
            }
            player.noteOff(root)
            val released = render(player, ((patch.releaseSeconds + .03f) * rate).roundToInt())
            assertTrue(released.take(rate / 10).any { abs(it) > 1e-6f })
            assertTrue(released.takeLast(rate / 50).all { it == 0f })
            assertTrue(player.activeNotes.isEmpty())
        }
        val preview = FlockPadMaker.preview(spec)
        assertEquals(2, preview.channels, "touch preview uses the actual stereo reference player")
        assertTrue(preview.samples.all { it.isFinite() })
        assertTrue(preview.samples.takeLast(rate / 50).all { it == 0f })
    }

}
