package com.snipsnap.synth

import com.snipsnap.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Independent, short sampler fixtures: no DSP render is needed to test the exported score contract. */
class ApsisMusicalScoreTest {
    private fun patch(name: String = "Fixture", velocity: Float = 1f, held: Boolean = false) =
        ApsisPatch(name, ApsisVoice.PASSAGE,
            Apsis.defaults(ApsisVoice.PASSAGE) + ("HOLD" to if (held) 1f else 0f),
            midi = 60, velocity = velocity, config = ApsisConfig(modelVersion = 2))

    private fun pad(slot: Int, sample: FloatArray, held: Boolean = false): ApsisScorePad {
        val p = patch("Fixture $slot", held = held)
        return ApsisScorePad(slot, "fixture_$slot", p.name, "TEST", p, !held, 1f, slot,
            listOf(ApsisScoreLayer(1, 127, p, "pads/$slot.wav", "fixture-$slot", sample)))
    }

    private fun event(pad: ApsisScorePad, at: Int, gate: Int? = null, velocity: Int = 127) =
        ApsisScoreEvent(at, pad.slot, pad.patch.midi, velocity, pad.preset,
            pad.patch.config.modelVersion, pad.layer(velocity).patch.velocity, gate)

    @Test
    fun `finite note-off is ignored different pad tails overlap and same pad retriggers choke`() {
        val a = pad(1, floatArrayOf(.5f, .25f, -.25f, -.125f))
        val b = pad(2, floatArrayOf(.25f, .125f))
        val score = ApsisMusicalScore("tails", "Tails", "Fixture", "test", 6, .5f,
            listOf(a, b), listOf(event(a, 0, gate = 1), event(b, 1), event(a, 2)))
        // Frame one proves note-off was ignored. Frame two retains B while the old A is choked.
        assertContentEquals(floatArrayOf(.25f, .25f, .3125f, .125f, -.125f, -.0625f),
            ApsisScorePlayer.render(score))
    }

    @Test
    fun `held playback wraps exact PCM restarts at zero and cuts at its declared gate`() {
        val p = pad(1, floatArrayOf(.5f, -.25f, .125f), held = true)
        val score = ApsisMusicalScore("held", "Held", "Fixture", "test", 10, .5f,
            listOf(p), listOf(event(p, 0, gate = 7), event(p, 5, gate = 3)))
        // The first voice wraps at frame three; a retrigger restarts at five; frame eight is silent.
        // Neither an unrecorded attack ramp nor a note-off release may change these samples.
        assertContentEquals(floatArrayOf(.25f, -.125f, .0625f, .25f, -.125f,
            .25f, -.125f, .0625f, 0f, 0f), ApsisScorePlayer.render(score))
    }

    @Test
    fun `score round trip preserves exact layer selection model pitch strength and frame timing`() {
        val hard = patch()
        val layers = listOf(
            ApsisScoreLayer(1, 41, hard.copy(velocity = .35f), "pads/soft.wav", "soft-hash", floatArrayOf(.125f, .0625f)),
            ApsisScoreLayer(42, 84, hard.copy(velocity = .65f), "pads/middle.wav", "middle-hash", floatArrayOf(.25f, .125f)),
            ApsisScoreLayer(85, 127, hard, "pads/hard.wav", "hard-hash", floatArrayOf(.5f, .25f)),
        )
        val p = ApsisScorePad(1, "layered", "Fixture", "TEST", hard, true, 1f, 1, layers)
        val score = ApsisMusicalScore("layers", "Layers", "Before hardware velocity scaling", "touch",
            8, .5f, listOf(p), listOf(event(p, 0, velocity = 32), event(p, 3, velocity = 80), event(p, 6)),
            ApsisScoreKind.LAYER_INSPECTION)
        val value = Json.parse(Json.write(score.toJsonValue()))
        val loaded = ApsisMusicalScore.fromJsonValue(value) { path, hash ->
            layers.single { it.sample == path && it.sha256 == hash }.samples.copyOf()
        }
        assertEquals(score.events, loaded.events)
        assertEquals(listOf(.35f, .65f, 1f), loaded.events.map { it.nativeVelocity })
        assertEquals(listOf(2, 2, 2), loaded.events.map { it.modelVersion })
        assertEquals(score.toJsonValue(), loaded.toJsonValue())
        assertContentEquals(floatArrayOf(.0625f, .03125f, 0f, .125f, .0625f, 0f, .25f, .125f),
            ApsisScorePlayer.render(loaded))
    }

    @Test
    fun `primary scores reject an invented velocity curve and replay rejects a truncated finite tail`() {
        val p = pad(1, floatArrayOf(.5f, .25f, -.25f, -.125f))
        assertFailsWith<IllegalArgumentException> {
            ApsisMusicalScore("velocity", "Velocity", "Fixture", "test", 4, .5f,
                listOf(p), listOf(event(p, 0, velocity = 80)))
        }
        val short = ApsisMusicalScore("cut", "Cut", "Fixture", "test", 3, .5f,
            listOf(p), listOf(event(p, 0)))
        assertFailsWith<IllegalArgumentException> { ApsisScorePlayer.render(short) }
        val mismatch = event(p, 0).copy(midi = 61)
        assertFailsWith<IllegalArgumentException> {
            ApsisMusicalScore("pitch", "Pitch", "Fixture", "test", 4, .5f, listOf(p), listOf(mismatch))
        }
    }
}
