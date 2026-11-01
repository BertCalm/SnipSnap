package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Causal behaviour, continuous radiation and an explicit opt-in to the unheard model. */
class ObbligatoInteractionTest {
    private val dt = 64.0 / (44_100 * 4)
    private val controls get() = ObbligatoPresets.all().single { it.name == "Fight" }.macros

    private data class Run(val controller: ObbligatoInteraction, val trajectory: FloatArray)

    private fun run(
        duration: Double = 5.75,
        hold: Double = 5.0,
        mischief: Float = .2f,
        seed: Int = 0,
        responses: Boolean = true,
        activity: (Double) -> Pair<Double, Double> = { .8 to .7 },
    ): Run {
        val controller = ObbligatoInteraction(hold, mischief, seed)
        val steps = ceil(duration / dt).toInt()
        val trajectory = FloatArray(steps * 4)
        var previousAir = controller.airPresence
        var previousReed = controller.reedPresence
        repeat(steps) { index ->
            val (air, reed) = activity(index * dt)
            controller.step(dt, air, reed, responses)
            assertTrue(controller.airPresence.isFinite() && controller.airPresence in .18..1.0)
            assertTrue(controller.reedPresence.isFinite() && controller.reedPresence in .18..1.0)
            assertTrue(controller.airReadiness.isFinite() && controller.airReadiness in 0.0..1.0)
            assertTrue(controller.reedReadiness.isFinite() && controller.reedReadiness in 0.0..1.0)
            assertTrue(controller.airPitchPush.isFinite() && controller.airPitchPush in 0.0..5.0)
            assertTrue(controller.reedPitchPush.isFinite() && controller.reedPitchPush in -5.0..0.0)
            // At the actual control rate a raised-cosine handoff must not introduce a
            // radiation step resembling the earlier audible pause between players.
            assertTrue(abs(controller.airPresence - previousAir) < .01)
            assertTrue(abs(controller.reedPresence - previousReed) < .01)
            previousAir = controller.airPresence
            previousReed = controller.reedPresence
            trajectory[index * 4] = controller.airPresence.toFloat()
            trajectory[index * 4 + 1] = controller.reedPresence.toFloat()
            trajectory[index * 4 + 2] = controller.airPitchPush.toFloat()
            trajectory[index * 4 + 3] = controller.reedPitchPush.toFloat()
        }
        val events = controller.events
        assertTrue(events.size <= ceil(hold / controller.minimumDwellSeconds).toInt(),
            "controller entry count escaped its finite dwell budget")
        for (event in events) {
            assertTrue(event.seconds < hold - controller.crossoverSeconds + dt)
            assertTrue(event.readinessAtEntry in 0.0..1.0)
            assertTrue(event.heardAir in 0.0..1.0 && event.heardReed in 0.0..1.0)
            assertTrue(event.previousLeader != event.leader)
        }
        for ((a, b) in events.zipWithNext()) {
            assertTrue(b.seconds - a.seconds >= controller.minimumDwellSeconds - dt,
                "a new entry began before the previous player had a material turn")
            assertEquals(a.leader, b.previousLeader)
        }
        return Run(controller, trajectory)
    }

    @Test
    fun `a silent missing or disabled opponent never invents an answering entry`() {
        for (activity in listOf(0.0 to 0.0, .8 to 0.0, 0.0 to .7,
            Double.NaN to .7, Double.POSITIVE_INFINITY to .7, -.5 to .7)) {
            val result = run(activity = { activity })
            assertTrue(result.controller.events.isEmpty(), "an absent player caused an entry: $activity")
        }
        val disabled = run(responses = false)
        assertTrue(disabled.controller.events.isEmpty())
        assertEquals(ObbligatoInteraction.Player.AIR, disabled.controller.leader)
        assertEquals(1.0, disabled.controller.airPresence)
        assertEquals(.18, disabled.controller.reedPresence)
        assertTrue(run().controller.events.size >= 3,
            "the absent-source negative controls are meaningless if the active duet cannot trade turns")
    }

    @Test
    fun `temporarily losing actual opponent activity delays the challengers first entry`() {
        val original = run()
        val interrupted = run(activity = { t -> (if (t in 0.2..0.8) .001 else .8) to .7 })
        val firstOriginal = original.controller.events.first()
        val firstInterrupted = interrupted.controller.events.first()
        assertEquals(ObbligatoInteraction.Player.REED, firstOriginal.leader)
        assertEquals(ObbligatoInteraction.Player.REED, firstInterrupted.leader)
        assertTrue(firstInterrupted.seconds > firstOriginal.seconds + .2,
            "the entry stayed on a fixed schedule despite losing the opponent's activity")
    }

    @Test
    fun `seeded readiness and crossover trajectories reproduce without scheduled entries`() {
        val original = run(mischief = 1f, seed = -319)
        val repeated = run(mischief = 1f, seed = -319)
        assertEquals(original.controller.events, repeated.controller.events)
        assertContentEquals(original.trajectory, repeated.trajectory)
        val stronger = run(mischief = 1f, seed = -319, activity = { .95 to .9 })
        assertTrue(original.controller.events.first().seconds != stronger.controller.events.first().seconds,
            "entry time ignores actual activity even though the personality and seed are unchanged")
    }

    private fun rms(x: FloatArray, from: Double, until: Double, rate: Int): Double {
        val a = (from * rate).toInt().coerceAtLeast(0)
        val b = minOf(x.size, (until * rate).toInt())
        require(b > a)
        var energy = 0.0
        for (i in a until b) energy += x[i].toDouble() * x[i]
        return sqrt(energy / (b - a))
    }

    @Test
    fun `experimental audio preserves both speaking sources through complete finite gestures`() {
        for (hold in listOf(0f, .45f, 1f)) for (midi in listOf(60, 67, 72)) {
            val settings = controls + ("HOLD" to hold)
            val raw = Obbligato.probe(settings, midi = midi, model = Obbligato.EXPERIMENTAL_MODEL)
            val rendered = Obbligato.render(settings, midi = midi, model = Obbligato.EXPERIMENTAL_MODEL)
            val label = "MIDI $midi HOLD $hold"
            assertEquals(1, rendered.channels)
            assertEquals(44_100, rendered.sampleRate)
            assertEquals(raw.snip.frameCount, rendered.frameCount)
            assertTrue(rendered.samples.all { it.isFinite() && it in -1f..1f }, "$label output is unsafe")
            assertTrue(abs(rendered.samples.average()) < .015, "$label output has excessive DC")
            assertEquals(0f, rendered.samples.last(), "$label has an unfinished finite ending")
            assertTrue(raw.air.all { it.isFinite() } && raw.reed.all { it.isFinite() })
            val held = raw.snip.durationSeconds.toDouble() - .75
            val a = ArrayList<Double>()
            val b = ArrayList<Double>()
            val sum = ArrayList<Double>()
            var from = .4
            while (from + .1 <= held - .1) {
                a += rms(raw.air, from, from + .1, rendered.sampleRate)
                b += rms(raw.reed, from, from + .1, rendered.sampleRate)
                sum += rms(raw.snip.samples, from, from + .1, rendered.sampleRate)
                from += .05
            }
            for ((name, values) in listOf("air" to a, "reed" to b)) {
                assertTrue(values.min() > 1e-4, "$label lost the $name source")
                assertTrue(values.min() >= values.max() * .15, "$label $name lost capture between turns")
            }
            val independentEnergy = a.indices.map { sqrt(a[it] * a[it] + b[it] * b[it]) }
            assertTrue(independentEnergy.min() >= independentEnergy.max() * .60,
                "$label source energy falls into a pause between turns")
            assertTrue(sum.min() >= sum.max() * .25, "$label complete duet has a pronounced gap")
        }
    }

    @Test
    fun `brief physical source weakening changes the survivors handoff only when responses are enabled`() {
        val original = Obbligato.probe(controls, model = Obbligato.EXPERIMENTAL_MODEL)
        val firstReed = assertNotNull(original.interactionEvents.firstOrNull {
            it.leader == ObbligatoInteraction.Player.REED
        }, "the unperturbed experimental duet never gives the reed an entry")
        assertTrue(original.interactionEvents.size >= 2, "the unperturbed players never counter one another")
        val weakening = Obbligato.SourceWeakening(
            air = true,
            startSeconds = (firstReed.seconds - .18).coerceAtLeast(.20),
            endSeconds = firstReed.seconds + .22,
            strength = .05f,
        )
        val changed = Obbligato.probeWithWeakening(controls,
            model = Obbligato.EXPERIMENTAL_MODEL, weakening = weakening)
        val changedFirst = changed.interactionEvents.firstOrNull {
            it.leader == ObbligatoInteraction.Player.REED
        }
        assertTrue(changedFirst == null || abs(changedFirst.seconds - firstReed.seconds) > .025,
            "the survivor kept its old entry time despite an actual change in its opponent")
        assertTrue(!original.reed.contentEquals(changed.reed), "the survivor's rendered gesture did not respond")

        val independent = Obbligato.probe(controls,
            model = Obbligato.EXPERIMENTAL_MODEL, responses = false)
        val independentChanged = Obbligato.probeWithWeakening(controls,
            model = Obbligato.EXPERIMENTAL_MODEL, responses = false, weakening = weakening)
        assertTrue(!independent.air.contentEquals(independentChanged.air), "the physical weakening probe was a no-op")
        assertContentEquals(independent.reed, independentChanged.reed,
            "the no-response survivor changed even though only the other source was weakened")
        assertTrue(independent.interactionEvents.isEmpty() && independentChanged.interactionEvents.isEmpty())
    }

    @Test
    fun `historical experimental recipes remain explicit beside the accepted factory sound`() {
        assertEquals(10, Obbligato.MODEL)
        assertEquals(6, Obbligato.EXPERIMENTAL_MODEL)
        assertTrue(ObbligatoPresets.all().all { it.model == 10 })
        assertTrue(ObbligatoKits.patches().all { it.model == 10 })
        val source = ObbligatoPatch("Responsive Fight", controls, midi = 67,
            velocity = .37f, seed = -931, model = Obbligato.EXPERIMENTAL_MODEL)
        assertEquals(source, ObbligatoPatch.fromJsonText(source.toJsonText()))
        val noModel = JsonValue.Obj(source.toJsonValue().entries.toMutableMap().apply { remove("model") })
        assertEquals(5, ObbligatoPatch.fromJsonValue(noModel).model)
        val edited = Patches.edited(source, "Edited Responsive Fight", source.macros + ("HOLD" to .2f))
        assertEquals(source.copy(name = "Edited Responsive Fight", macros = source.macros + ("HOLD" to .2f)), edited)

        for (preset in ObbligatoPresets.all().filter { it.name != "Fight" }) {
            assertContentEquals(preset.copy(model = 5).render().samples,
                preset.copy(model = Obbligato.EXPERIMENTAL_MODEL).render().samples,
                "${preset.name} was re-voiced even though this experiment concerns Fight")
        }
        assertContentEquals(source.render().samples, source.render().samples,
            "the experimental physical duet does not reproduce its saved recipe")
    }
}
