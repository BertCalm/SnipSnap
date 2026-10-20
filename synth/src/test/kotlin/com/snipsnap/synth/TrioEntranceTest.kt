package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Entrance, saved audio and source preservation; numerical changes are not listening acceptance. */
class TrioEntranceTest {
    private val wives = setOf(TrioActor.TRES, TrioActor.BERIMBAU, TrioActor.CHARANGO)
    private val companions = setOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)
    private val baseline = mapOf("TOUCH" to .55f, "SIDES" to (1f / 6f), "MOTION" to 0f,
        "DRAMA" to 1f, "SPACE" to .35f, "DAMP" to .48f)
    private val published = TrioConfig(seed = 47010, model = 3)
    private val entrance = published.copy(model = 4)

    private fun scene(drama: Float, config: TrioConfig = entrance,
        macros: Map<String, Float> = baseline, midi: Int = 60, velocity: Float = 1f) =
        Trio.renderProbe(TrioVoice.ENSEMBLE, macros + ("DRAMA" to drama), midi, velocity, config)

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun power(samples: FloatArray) = samples.sumOf { it.toDouble() * it }
    private fun companionPower(probe: TrioProbe) =
        probe.actors.filterKeys { it in companions }.values.sumOf(::power)

    private fun normalizedDifference(a: FloatArray, b: FloatArray): Double {
        val an = sqrt(power(a)); val bn = sqrt(power(b))
        return sqrt(a.indices.sumOf { val d = a[it] / an - b[it] / bn; d * d })
    }

    private fun meanSquare(samples: FloatArray, fromSeconds: Float, toSeconds: Float): Double {
        val from = (fromSeconds * Dsp.RATE).toInt().coerceIn(0, samples.lastIndex)
        val to = (toSeconds * Dsp.RATE).toInt().coerceIn(from + 1, samples.size)
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sum / (to - from)
    }

    private fun rmsEnvelope(samples: FloatArray): FloatArray {
        val block = (Dsp.RATE * .01f).toInt()
        return FloatArray((samples.size + block - 1) / block) { window ->
            val from = window * block
            val to = minOf(from + block, samples.size)
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            sqrt(sum / (to - from)).toFloat()
        }
    }

    private fun lateRetention(probe: TrioProbe, actor: TrioActor): Double {
        val onset = probe.events.first { it.actor == actor }.timeSeconds
        val tap = probe.actors.getValue(actor)
        val attack = meanSquare(tap, onset + .03f, onset + .18f)
        assertTrue(attack > 0.0 && attack.isFinite(), "$actor has no finite attack energy")
        return meanSquare(tap, 2.2f, 2.8f) / attack
    }

    @Test
    fun `saved model three recipes retain the published single hit audio`() {
        // Native float hashes from the published model-3 pack, round
        // trio-f0d2587f35ac-ae1b99f9d860, with the complete baseline controls.
        val canaries = mapOf(
            .25f to "7e4ffd5a4c2d0119cfb40d163702c8d3f9afdda2cec6aba3bd10ed72e4f5f087",
            .5f to "725ea6b3a59759da661bbdeb2936f86804c2829c05343240692d1eddc4a58b11",
            .85f to "ee67391479d39370b2785e88e1585fb3cbd43916f1cdce3e66099bfcffd885b4",
            1f to "e3391a097e6279decd005948e0cb66ca5f2cb425085001091e0d1c37a3588c7d",
        )
        for ((drama, expected) in canaries) {
            val patch = TrioPatch("Published Single Hit", TrioVoice.ENSEMBLE,
                baseline + ("DRAMA" to drama), config = published)
            val restored = assertIs<TrioPatch>(PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            assertEquals(published, restored.config)
            assertEquals(expected, hash(Trio.renderProbe(restored.voice, restored.macros,
                restored.midi, restored.velocity, restored.config).samples),
                "saved model 3 at DRAMA $drama changed")
        }
    }

    @Test
    fun `moving wife sources and gestures stay exact throughout the earlier entrances`() {
        val rich = baseline + mapOf("TOUCH" to .72f, "SIDES" to .42f,
            "MOTION" to .7f, "SPACE" to .8f, "DAMP" to .3f)
        val moving = published.copy(seed = -91827, tempoBpm = 140f, beats = 3,
            press = .8f, tremoloHz = 17f, strumMs = 23f)
        for ((macros, config) in listOf(baseline to published, rich to moving)) {
            for (drama in listOf(.25f, .5f, .7f, .85f, 1f)) {
                val old = scene(drama, config, macros, 57, .7f)
                val revised = scene(drama, config.copy(model = 4), macros, 57, .7f)
                for (actor in wives) {
                    assertContentEquals(old.actors.getValue(actor), revised.actors.getValue(actor),
                        "$actor source changed at DRAMA $drama, config $config")
                    assertEquals(old.events.filter { it.actor == actor }, revised.events.filter { it.actor == actor },
                        "$actor gesture changed at DRAMA $drama, config $config")
                }
            }
        }
    }

    @Test
    fun `zero remains the original baseline and full drama remains the exact liked single hit scene`() {
        val controls = baseline + mapOf("SIDES" to .42f, "MOTION" to .6f)
        for (voice in TrioVoice.entries) {
            val macros = controls + ("DRAMA" to 0f)
            val old = Trio.renderProbe(voice, macros, 57, .7f, published)
            val revised = Trio.renderProbe(voice, macros, 57, .7f, entrance)
            assertContentEquals(old.samples, revised.samples, "$voice zero DRAMA baseline changed")
            assertEquals(old.events, revised.events)
        }
        for (macros in listOf(baseline, controls)) {
            val old = scene(1f, published, macros)
            val revised = scene(1f, entrance, macros)
            assertContentEquals(old.samples, revised.samples, "full DRAMA source mix changed")
            assertEquals(old.events, revised.events, "full DRAMA score changed")
            for (actor in old.actors.keys) {
                assertContentEquals(old.actors.getValue(actor), revised.actors.getValue(actor),
                    "$actor at full DRAMA changed")
            }
            assertContentEquals(Trio.render(TrioVoice.ENSEMBLE, macros, config = published).samples,
                Trio.render(TrioVoice.ENSEMBLE, macros, config = entrance).samples,
                "full DRAMA final output changed")
        }
    }

    @Test
    fun `quarter and half drama add companion source power and change the gain normalized mix`() {
        val fullPower = companionPower(scene(1f))
        assertTrue(fullPower > 0.0)
        for (drama in listOf(.25f, .5f)) {
            val old = scene(drama, published)
            val revised = scene(drama)
            assertEquals(wives, old.actors.keys, "model 3 lower range unexpectedly has companions")
            assertEquals(wives + companions, revised.actors.keys, "DRAMA $drama is missing companions")
            for (actor in companions) {
                assertEquals(1, revised.events.count { it.actor == actor }, "$actor must strike once at DRAMA $drama")
                assertTrue(power(revised.actors.getValue(actor)) > 0.0, "$actor has no source energy at DRAMA $drama")
            }
            val relativePower = companionPower(revised) / fullPower
            val difference = normalizedDifference(old.samples, revised.samples)
            val spectral = BandDistance.whole(old.samples, revised.samples, Dsp.RATE)
            println("TRIO entrance DRAMA=$drama relativeCompanionPower=$relativePower normalizedDifference=$difference bandDistance=$spectral")
            // This scene measures roughly .163/.403 relative companion power,
            // .593/.798 waveform difference and .488/.752 band distance at .25/.5.
            // Conservative regression floors do not establish audible masking,
            // instrumental identity or musical quality.
            assertTrue(relativePower > (if (drama == .25f) .05 else .15),
                "DRAMA $drama has negligible companion source power relative to full: $relativePower")
            assertTrue(difference > .1, "DRAMA $drama source change collapses after removing gain: $difference")
            assertTrue(spectral > .02, "DRAMA $drama has no material band-energy change: $spectral")
            assertTrue(revised.samples.all { it.isFinite() } && revised.samples.maxOf { abs(it) } < .99f)
        }
    }

    @Test
    fun `companion excitation and source power rise through the whole control without retriggers`() {
        val previousEnergy = companions.associateWith { 0f }.toMutableMap()
        var previousPower = 0.0
        for (drama in listOf(0f, .01f, .1f, .25f, .5f, .7f, .85f, 1f)) {
            val probe = scene(drama)
            if (drama == 0f) {
                assertEquals(wives, probe.actors.keys)
                assertTrue(probe.events.none { it.actor in companions })
                assertEquals(0.0, companionPower(probe))
                continue
            }
            for (actor in companions) {
                val events = probe.events.filter { it.actor == actor }
                assertEquals(1, events.size, "$actor retriggers at DRAMA $drama")
                val energy = events.single().energy
                assertTrue(energy.isFinite() && energy > previousEnergy.getValue(actor),
                    "$actor entrance fails to increase at DRAMA $drama")
                previousEnergy[actor] = energy
                val retained = lateRetention(probe, actor)
                assertTrue(retained.isFinite() && retained > 0.0 && retained < 1.0,
                    "$actor does not ring and decay after its single hit at DRAMA $drama: $retained")
            }
            val sourcePower = companionPower(probe)
            assertTrue(sourcePower > previousPower, "companion source power fails to increase at DRAMA $drama")
            previousPower = sourcePower
        }
    }

    @Test
    fun `the new companions approach silence continuously and have no former threshold jump`() {
        val zero = scene(0f)
        val nearZero = scene(.00001f)
        assertTrue(normalizedDifference(zero.samples, nearZero.samples) < .02,
            "an arbitrarily small DRAMA value jumps the source performance")
        assertTrue(companionPower(nearZero) > 0.0, "near-zero DRAMA still gates companions")
        val below = scene(.69999f)
        val above = scene(.70001f)
        assertEquals(wives + companions, below.actors.keys, "companions are still gated below .7")
        assertEquals(below.actors.keys, above.actors.keys)
        for (actor in companions) {
            val lowerEvents = below.events.filter { it.actor == actor }
            val upperEvents = above.events.filter { it.actor == actor }
            assertEquals(1, lowerEvents.size)
            assertEquals(1, upperEvents.size)
            val lowerEvent = lowerEvents.single()
            val upperEvent = upperEvents.single()
            assertTrue(abs(upperEvent.energy / lowerEvent.energy - 1f) < .001f,
                "$actor excitation jumps at the former .7 threshold")
            assertTrue(abs(upperEvent.timeSeconds - lowerEvent.timeSeconds) < 1f / (Dsp.RATE * Dsp.OVERSAMPLE),
                "$actor entrance timing jumps at the former .7 threshold")
            val lowerTap = below.actors.getValue(actor)
            val upperTap = above.actors.getValue(actor)
            val powerRatio = power(upperTap) / power(lowerTap)
            assertTrue(abs(powerRatio - 1.0) < .001,
                "$actor source power jumps at the former .7 threshold: $powerRatio")
            assertTrue(BandDistance.whole(lowerTap, upperTap, Dsp.RATE) < .001,
                "$actor spectrum jumps at the former .7 threshold")
            assertTrue(normalizedDifference(rmsEnvelope(lowerTap), rmsEnvelope(upperTap)) < .01,
                "$actor energy envelope jumps at the former .7 threshold")
        }
        // An onset crossing one 4x sample changes the phase of a bright ringing
        // course: this fixed scene's waveform difference is .106 even though
        // its band/envelope differences are .00312/.00226 and power changes .002.
        // Check source energy and its envelope rather than conflating phase with a gate.
        assertTrue(BandDistance.whole(below.samples, above.samples, Dsp.RATE) < .01,
            "the former .7 threshold jumps the mix spectrum")
        assertTrue(normalizedDifference(rmsEnvelope(below.samples), rmsEnvelope(above.samples)) < .01,
            "the former .7 threshold jumps the mix energy envelope")
    }

    @Test
    fun `the lower range single hits keep DAMP controlled natural decay`() {
        val open = scene(.25f, macros = baseline + ("DAMP" to 0f))
        val damped = scene(.25f, macros = baseline + ("DAMP" to 1f))
        for (actor in companions) {
            assertEquals(open.events.filter { it.actor == actor }, damped.events.filter { it.actor == actor },
                "$actor DAMP changes the lower-range excitation score")
            assertEquals(1, damped.events.count { it.actor == actor })
            val openTail = lateRetention(open, actor)
            val dampedTail = lateRetention(damped, actor)
            assertTrue(openTail.isFinite() && dampedTail.isFinite() && openTail > dampedTail,
                "$actor DAMP does not shorten the lower-range ring: $openTail versus $dampedTail")
        }
    }
}
