package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Numeric scene contracts; musical identity remains a dry listening decision. */
class TrioTest {
    private val main = setOf(TrioActor.TRES, TrioActor.BERIMBAU, TrioActor.CHARANGO)
    private val companions = setOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)
    private val neutral = mapOf("TOUCH" to .6f, "DRAMA" to 0f, "SIDES" to 0f,
        "MOTION" to 0f, "SPACE" to .3f, "DAMP" to .4f)

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    @Test
    fun `one ensemble hit contains all three players immediately and no latent companions`() {
        val probe = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral)
        assertEquals(main, probe.actors.keys)
        for (actor in main) {
            val tap = probe.actors.getValue(actor)
            assertEquals(probe.samples.size, tap.size)
            assertTrue(rms(tap) > 1e-4, "$actor is missing from the hit")
            assertTrue(probe.events.any { it.actor == actor && it.timeSeconds < .1f && it.energy > 0f },
                "$actor waits for another note to exist")
        }
        assertTrue(probe.events.none { it.actor in companions })
        assertTrue(probe.samples.all { it.isFinite() })
    }

    @Test
    fun `solo scenes keep their player on repeated notes and control map order has no effect`() {
        val identities = mapOf(TrioVoice.TRES to TrioActor.TRES, TrioVoice.BERIMBAU to TrioActor.BERIMBAU,
            TrioVoice.CHARANGO to TrioActor.CHARANGO)
        for ((voice, actor) in identities) {
            val a = Trio.renderProbe(voice, neutral, midi = 60, velocity = .7f, config = TrioConfig(seed = -17))
            val b = Trio.renderProbe(voice, neutral.entries.reversed().associate { it.key to it.value },
                midi = 60, velocity = .7f, config = TrioConfig(seed = -17))
            assertEquals(setOf(actor), a.actors.keys, "$voice changed identity with note allocation")
            assertTrue(a.events.isNotEmpty() && a.events.all { it.actor == actor })
            assertContentEquals(a.samples, b.samples, "$voice deterministic audio")
            assertContentEquals(a.actors.getValue(actor), b.actors.getValue(actor), "$voice deterministic source")
            assertEquals(a.events, b.events, "$voice deterministic gesture")
        }
    }

    @Test
    fun `the three source identities remain spectrally different under identical controls`() {
        val scene = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral)
        val actors = main.toList()
        for (i in actors.indices) for (j in i + 1 until actors.size) {
            val distance = BandDistance.whole(scene.actors.getValue(actors[i]), scene.actors.getValue(actors[j]), Dsp.RATE)
            assertTrue(distance > .03, "${actors[i]} and ${actors[j]} differ only by level: band distance $distance")
        }
    }

    @Test
    fun `saved model three activates all contrasting companions only beyond its threshold`() {
        val accepted = TrioConfig(model = 3)
        for (drama in listOf(0f, .69f, .7f)) {
            val probe = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral + ("DRAMA" to drama), config = accepted)
            assertEquals(main, probe.actors.keys, "DRAMA $drama allocated an inaudible companion")
            assertTrue(probe.events.none { it.actor in companions }, "DRAMA $drama emitted a companion gesture")
        }
        val medium = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral + ("DRAMA" to .85f), config = accepted)
        val full = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral + ("DRAMA" to 1f), config = accepted)
        assertEquals(main + companions, medium.actors.keys)
        val dryTrio = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral, config = TrioConfig(model = Trio.LEGACY_MODEL_VERSION))
        val legacyFull = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral + ("DRAMA" to 1f),
            config = TrioConfig(model = Trio.LEGACY_MODEL_VERSION))
        for (actor in main) {
            assertContentEquals(dryTrio.actors.getValue(actor), legacyFull.actors.getValue(actor),
                "preserved legacy companions must not reseed $actor's saved performance")
        }
        for (actor in companions) {
            val middleEnergy = rms(medium.actors.getValue(actor))
            val fullEnergy = rms(full.actors.getValue(actor))
            assertTrue(middleEnergy > 1e-6 && fullEnergy > middleEnergy, "$actor fails the DRAMA fade: $middleEnergy → $fullEnergy")
            assertTrue(medium.events.any { it.actor == actor && it.energy > 0f }, "$actor has no distinct articulation")
        }
        val actors = companions.toList()
        for (i in actors.indices) for (j in i + 1 until actors.size) {
            val distance = BandDistance.whole(full.actors.getValue(actors[i]), full.actors.getValue(actors[j]), Dsp.RATE)
            assertTrue(distance > .03, "${actors[i]} and ${actors[j]} share one source at different gains: $distance")
        }
    }

    @Test
    fun `alliance and motion alter the performed gesture as well as the resulting mix`() {
        val first = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral)
        val second = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral + ("SIDES" to 1f / 3f))
        val third = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral + ("SIDES" to 2f / 3f))
        for ((a, b) in listOf(first to second, second to third, third to first)) {
            assertFalse(a.events == b.events, "alliance only changes a final output fader")
            assertTrue(BandDistance.whole(a.samples, b.samples, Dsp.RATE) > .005, "alliance is inaudible after removing gain")
        }
        val moving = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral + ("MOTION" to 1f))
        assertFalse(first.events == moving.events, "MOTION did not change performer timing or energy")
        assertTrue(BandDistance.path(first.samples, moving.samples, Dsp.RATE) > .005, "MOTION changes no audible trajectory")
        for (event in moving.events) {
            assertTrue(event.timeSeconds.isFinite() && event.timeSeconds >= 0f)
            assertTrue(event.energy.isFinite() && event.energy > 0f)
        }
    }

    @Test
    fun `root and body corners stay finite audible and inside the output ceiling`() {
        for (voice in TrioVoice.entries) for (midi in listOf(Trio.MIN_MIDI, 60, Trio.MAX_MIDI)) {
            val output = Trio.render(voice, neutral, midi)
            assertEquals(1, output.channels)
            assertEquals(Dsp.RATE, output.sampleRate)
            assertTrue(output.samples.all { it.isFinite() }, "$voice/$midi non-finite")
            assertTrue(rms(output.samples) > 1e-4, "$voice/$midi silence")
            assertTrue(output.peak() <= .99001f, "$voice/$midi clipped output")
            assertTrue(abs(output.samples.average()) < 1e-3, "$voice/$midi DC offset")
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(output, wanted, fromSec = .15f, bodySeconds = .35f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            assertTrue(abs(cents) < 25, "$voice/$midi requested root is $cents cents out")
        }
        for (space in listOf(0f, 1f)) for (damp in listOf(0f, 1f)) {
            val output = Trio.render(TrioVoice.ENSEMBLE, neutral + mapOf("SPACE" to space, "DAMP" to damp, "DRAMA" to 1f))
            assertTrue(output.samples.all { it.isFinite() } && output.peak() <= .99001f, "SPACE $space / DAMP $damp exceeded its budget")
            assertTrue(rms(output.samples) > 1e-4, "SPACE $space / DAMP $damp became silent")
        }
    }

    @Test
    fun `zero playing velocity is silent before and after leveling`() {
        val probe = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral + ("DRAMA" to 1f), velocity = 0f)
        assertTrue(probe.samples.all { it == 0f }, "zero velocity still drives the model")
        assertTrue(probe.actors.values.all { tap -> tap.all { it == 0f } })
        assertTrue(Trio.render(TrioVoice.ENSEMBLE, neutral, velocity = 0f).samples.all { it == 0f })
    }

    @Test
    fun `soft playing reduces source energy and changes the attack beyond a final fader`() {
        val quiet = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral, velocity = .25f)
        val strong = Trio.renderProbe(TrioVoice.ENSEMBLE, neutral, velocity = 1f)
        assertTrue(rms(quiet.samples) > 0 && rms(quiet.samples) < rms(strong.samples), "velocity did not scale playing energy")
        val attackFrames = minOf((.18f * Dsp.RATE).toInt(), quiet.samples.size, strong.samples.size)
        val distance = BandDistance.whole(quiet.samples.copyOfRange(0, attackFrames),
            strong.samples.copyOfRange(0, attackFrames), Dsp.RATE)
        assertTrue(distance > .005, "velocity only changed volume: normalized attack distance $distance")
        val quietPad = Trio.render(TrioVoice.ENSEMBLE, neutral, velocity = .25f)
        val strongPad = Trio.render(TrioVoice.ENSEMBLE, neutral, velocity = 1f)
        assertTrue(rms(strongPad.samples) > rms(quietPad.samples) * 1.5,
            "shared leveling flattened the playing velocity")
    }
}
