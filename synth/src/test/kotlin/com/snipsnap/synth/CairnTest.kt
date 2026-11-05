package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

/** Independent mechanics, acoustic and recipe gates for the two-voice reconstruction. */
class CairnTest {
    private val middle = mapOf("TUNE" to .5f, "WEIGHT" to .5f, "SLOPE" to .4f,
        "GROOVE" to .35f, "FRICTION" to .3f, "BODY" to .4f, "HOLD" to 0f)

    @Test
    fun `ANSWER wakes higher stones throughout the middle control cube`() {
        var minimum = Int.MAX_VALUE
        for (weight in listOf(.25f, .5f, .75f)) for (slope in listOf(.25f, .5f, .75f))
            for (groove in listOf(.25f, .5f, .75f)) for (friction in listOf(.25f, .5f, .75f))
                for (body in listOf(.25f, .5f, .75f)) {
                    val m = middle + mapOf("WEIGHT" to weight, "SLOPE" to slope, "GROOVE" to groove,
                        "FRICTION" to friction, "BODY" to body)
                    val motion = Cairn.motion(CairnVoice.ANSWER, m)
                    val replies = motion.events.count { it.stone > 0 }
                    minimum = min(minimum, replies)
                    assertTrue(replies >= 3, "$m: only $replies higher contacts")
                    assertTrue(motion.events.first().stone == 0, "$m: no primary lead")
                }
        println("CAIRN middle cube: 243 points, minimum $minimum higher contacts")
    }

    @Test
    fun `the voices differ mechanically and removing the mount stops every answer`() {
        for (m in listOf(middle, Cairn.defaults(CairnVoice.ANSWER))) {
            val knock = Cairn.motion(CairnVoice.KNOCK, m)
            val answer = Cairn.motion(CairnVoice.ANSWER, m)
            val isolated = Cairn.motion(CairnVoice.ANSWER, m, disableCoupling = true)
            assertEquals(0, knock.events.count { it.stone > 0 })
            assertTrue(answer.events.count { it.stone > 0 } >= 6)
            assertEquals(0, isolated.events.count { it.stone > 0 })
            assertTrue(isolated.events.any { it.stone == 0 })
            val firstReply = answer.events.first { it.stone > 0 }.seconds
            assertTrue(firstReply > answer.events.first().seconds + .15)
        }
    }

    private fun energyIsPassive(motion: Cairn.Motion): Boolean =
        motion.largestEnergyIncrease < 1e-9 &&
            motion.states.zipWithNext().all { (a, b) -> b.energy + b.withdrawnWork <= a.energy + a.withdrawnWork + 1e-8 } &&
            motion.finalEnergy + motion.withdrawnWork <= motion.initialEnergy + 1e-8

    @Test
    fun `motion spends one launch and contacts remove the recorded kinetic work`() {
        for (voice in CairnVoice.entries) for (m in listOf(middle,
            middle.mapValues { if (it.key == "HOLD") 0f else 0f },
            middle.mapValues { if (it.key == "HOLD") 0f else 1f })) {
            val motion = Cairn.motion(voice, m)
            assertTrue(energyIsPassive(motion), "$voice $m: energy increased")
            assertTrue(motion.dissipatedWork >= -1e-8)
            assertTrue(motion.finalEnergy < motion.initialEnergy * .001)
            assertTrue(motion.events.size <= 256)
            assertEquals(motion.withdrawnWork, motion.events.sumOf { it.work }, 1e-10)
            val mass = .55 + 1.3 * m.getValue("WEIGHT")
            for (e in motion.events) assertEquals(e.work,
                .5 * mass * (e.speedBefore.pow(2) - e.speedAfter.pow(2)), 1e-10)
            // A sign mutation must fail the actual independent-window predicate.
            val growing = motion.copy(states = motion.states.mapIndexed { i, s ->
                s.copy(energy = motion.initialEnergy + i * .1, withdrawnWork = 0.0)
            })
            assertFalse(energyIsPassive(growing), "the passivity guard did not reject injected growth")
        }
    }

    @Test
    fun `fresh cache reordered controls and tuning preserve causal motion and deterministic audio`() {
        for (voice in CairnVoice.entries) {
            Cairn.clearMotionCache()
            val a = Cairn.inspect(voice, middle)
            assertFalse(a.diagnostics.mechanicsCacheHit)
            val reordered = middle.entries.reversed().associate { it.key to it.value }
            val b = Cairn.inspect(voice, reordered)
            assertTrue(b.diagnostics.mechanicsCacheHit)
            assertContentEquals(a.raw, b.raw)
            assertContentEquals(a.samples, b.samples)
            val tuned = Cairn.inspect(voice, middle + ("TUNE" to .75f))
            assertTrue(tuned.diagnostics.mechanicsCacheHit)
            assertEquals(a.diagnostics.motion, tuned.diagnostics.motion)
            Cairn.clearMotionCache()
            assertContentEquals(a.samples, Cairn.inspect(voice, middle).samples)
        }
    }

    @Test
    fun `every control moves its intended quantity and pairs change the motion together`() {
        val mechanical = listOf("WEIGHT", "SLOPE", "GROOVE", "FRICTION", "BODY")
        for (name in mechanical) {
            val low = Cairn.motion(CairnVoice.ANSWER, middle + (name to .25f))
            val high = Cairn.motion(CairnVoice.ANSWER, middle + (name to .75f))
            assertNotEquals(low.events, high.events, "$name has no contact effect")
        }
        val effects = mutableListOf<Double>()
        for (body in listOf(.25f, .75f)) {
            val low = Cairn.motion(CairnVoice.ANSWER, middle + mapOf("BODY" to body, "FRICTION" to .25f))
            val high = Cairn.motion(CairnVoice.ANSWER, middle + mapOf("BODY" to body, "FRICTION" to .75f))
            effects += high.events.filter { it.stone > 0 }.sumOf { it.work } - low.events.filter { it.stone > 0 }.sumOf { it.work }
        }
        assertTrue(abs(effects[1] - effects[0]) > .0001, "BODY × FRICTION is separable")
        val a = Cairn.inspect(CairnVoice.KNOCK, middle)
        val held = Cairn.inspect(CairnVoice.KNOCK, middle + ("HOLD" to .8f))
        assertEquals(a.diagnostics.motion, held.diagnostics.motion)
        assertTrue(held.samples.size > a.samples.size)
    }

    @Test
    fun `raw and conditioned audio stay finite passive quiet in DC and out of drum classes`() {
        for (voice in CairnVoice.entries) for (midi in listOf(Cairn.MIN_MIDI, 60, Cairn.MAX_MIDI)) {
            val r = Cairn.inspect(voice, middle, midi)
            assertTrue(r.raw.all { it.isFinite() } && r.samples.all { it.isFinite() })
            assertTrue(r.diagnostics.rawPeak in .001..2.0)
            assertTrue(r.diagnostics.tailEnergyIncrease < 1e-12)
            assertTrue(r.samples.maxOf { abs(it) } in .01f.. .991f)
            assertTrue(abs(r.samples.average()) < .002)
            val snip = Snip(r.samples, 1, Cairn.RATE)
            val filed = Cairn.drumClassFor(voice, middle)
            assertEquals(snip.frameCount > (1.5 * Cairn.RATE).toInt(), filed == DrumClass.LOOP)
            val classification = Classifier.classify(snip)
            val classified = classification.drumClass
            if (voice == CairnVoice.TUMBLE) println("TUMBLE MIDI$midi classifier $classification")
            assertTrue(classified in setOf(DrumClass.PERC, DrumClass.TONAL, DrumClass.LOOP), "$voice MIDI$midi filed $classified")
        }
    }

    @Test
    fun `root is anchored at three registers without BODY or WEIGHT detuning it`() {
        for (voice in CairnVoice.entries) for (midi in listOf(48, 60, 72)) for (body in listOf(.25f, .75f)) {
            val r = Cairn.inspect(voice, middle + mapOf("BODY" to body, "WEIGHT" to body), midi)
            // Measure the ring after the direct contact cluster. Correlating
            // across three new attacks biases a low-register pitch estimate.
            val from = if (voice == CairnVoice.TUMBLE)
                (r.diagnostics.motion.events.last().seconds - r.diagnostics.outputStartFrame.toDouble()/Cairn.RATE + .012).toFloat() else .20f
            val estimate = assertNotNull(Pitch.detect(Snip(r.samples, 1, Cairn.RATE), fromSec = from,
                windowSec = if (voice == CairnVoice.TUMBLE) .12f else .25f))
            val cents = 1200 * log2(estimate.hz / Cairn.frequencyFor(midi))
            assertTrue(abs(cents) < 12.0, "$voice MIDI$midi BODY$body: $cents cents")
            assertTrue(estimate.confidence >= .3f)
        }
    }

    @Test
    fun `the contact cue preserves the full recording and starts near the first physical impact`() {
        for (voice in CairnVoice.entries) {
            val r = Cairn.inspect(voice)
            val d = r.diagnostics
            assertTrue(d.firstContactFrame > .03 * Cairn.RATE)
            assertContentEquals(r.full.copyOfRange(d.outputStartFrame, r.full.size), r.samples)
            val peak = r.samples.maxOf { abs(it) }
            val onset = r.samples.indexOfFirst { abs(it) >= .01f * peak }
            assertTrue(onset >= 0 && onset < .006 * Cairn.RATE, "$voice contact onset ${onset.toDouble() / Cairn.RATE}")
            assertContentEquals(r.full, Cairn.render(voice, startAtContact = false).samples)
        }
    }

    @Test
    fun `soft velocity changes contacts and pulse shape and zero velocity stays silent`() {
        for (voice in CairnVoice.entries) {
            val silent = Cairn.inspect(voice, velocity = 0f)
            assertTrue(silent.raw.all { it == 0f } && silent.samples.all { it == 0f })
            assertTrue(silent.diagnostics.motion.events.isEmpty())
            val soft = Cairn.inspect(voice, velocity = .5f)
            val hard = Cairn.inspect(voice)
            assertNotEquals(soft.diagnostics.motion.events, hard.diagnostics.motion.events)
            assertTrue(soft.diagnostics.motion.initialEnergy < hard.diagnostics.motion.initialEnergy)
            assertFalse(soft.raw.contentEquals(hard.raw))
            val patch = CairnPatch("Velocity", voice, middle)
            assertContentEquals(Cairn.render(voice, middle, velocity = .5f).samples,
                Velocity.atVelocity(patch, .5f).samples)
        }
    }

    @Test
    fun `conditioning agrees with an independently rendered 8x reference`() {
        for (voice in CairnVoice.entries) {
            val a = Cairn.inspect(voice, middle, midi = 72, startAtContact = false)
            val b = Cairn.inspect(voice, middle, midi = 72, startAtContact = false, options = Cairn.Options(oversample = 8))
            val n = min(a.samples.size, b.samples.size)
            val error = sqrt((0 until n).sumOf { (a.samples[it] - b.samples[it]).toDouble().pow(2) } /
                a.samples.take(n).sumOf { it.toDouble().pow(2) })
            println("CAIRN $voice 4x/8x relative error $error")
            assertTrue(error < .08, "$voice high-register rate error $error")
        }
    }

    @Test
    fun `recipes pin model note velocity cue and the legacy HOLD refusal`() {
        val patch = CairnPatch("A physical reply", CairnVoice.ANSWER, middle, 57, .6f, false)
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        assertFailsWith<IllegalArgumentException> { patch.copy(model = Cairn.MODEL_VERSION + 1) }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 1, macros = middle + ("HOLD" to 1f)) }
        assertFailsWith<IllegalArgumentException> { Cairn.render(CairnVoice.ANSWER, middle + ("HOLD" to 1f), model = 1) }
        assertFailsWith<IllegalArgumentException> { patch.copy(velocity = Float.NaN) }
        for (voice in CairnVoice.entries) {
            assertContentEquals(Cairn.render(voice).samples,
                Cairn.render(voice, mapOf("UNKNOWN" to 1f, "BODY" to Float.NaN)).samples)
            repeat(20) { i ->
                val m = Cairn.scramble(voice, Random(i), temperature = 1f)
                assertTrue(m.values.all { it in 0f..1f })
                assertTrue(m.getValue("HOLD") < Cairn.HOLD_RESERVED)
            }
        }
    }
}
