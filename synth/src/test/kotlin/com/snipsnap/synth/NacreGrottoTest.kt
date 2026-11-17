package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * NACRE GROTTO (saved model 8): the bowed bass string under glassy glints, and its held loop. The seams, retune passes and
 * cycle errors each held case measures are printed, so the record carries the numbers and not only the pass.
 */
class NacreGrottoTest {
    private val held = mapOf("HOLD" to 1f)

    private fun hash(samples: FloatArray): String {
        val b = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putFloat(s)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += x[i].toDouble() * x[i]
        return sqrt(e / (to - from))
    }

    private fun cents(hz: Double, want: Double) = 1200.0 * ln(hz / want) / ln(2.0)

    /** The biggest sample-to-sample step inside [x]'s span, and the step the wrap makes (its last sample back to its first). */
    private fun steps(loop: FloatArray): Pair<Float, Float> {
        var inside = 0f
        for (i in 1 until loop.size) inside = maxOf(inside, abs(loop[i] - loop[i - 1]))
        return inside to abs(loop[0] - loop[loop.lastIndex])
    }

    @Test
    fun `the held loop closes without a click at C2, C3 and C4, soft and hard, and at the TOUCH x DUET corners`() {
        val cases = listOf(36, 48, 60).flatMap { m -> listOf(Triple(m, .35f, emptyMap<String, Float>()), Triple(m, 1f, emptyMap())) } +
            listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f).map { (t, d) -> Triple(36, .8f, mapOf("TOUCH" to t, "DUET" to d)) }
        for ((midi, velocity, macros) in cases) {
            val r = NacreGrotto.played(macros + held, midi, velocity, 7L)
            val c = assertNotNull(r.closure)
            val d = r.probe.diagnostics
            println("GROTTO held MIDI $midi velocity $velocity $macros: seam before %.2e after %.2e cycle %.2e passes %d loop %.4f s".format(
                java.util.Locale.ROOT, c.before, c.after, c.cycleError, d.getValue("retunePasses").toInt(), d.getValue("loopSeconds")))
            assertTrue(c.worstSeam < Keys.MAX_SEAM_ERROR, "MIDI $midi $velocity $macros seam ${c.worstSeam}")
            assertTrue(c.cycleError < Keys.MAX_SEAM_ERROR, "MIDI $midi $velocity $macros cycle ${c.cycleError}")
            val loop = r.probe.snip.samples
            assertEquals(r.loopFrames, loop.size)
            assertEquals(DrumClass.LOOP, Nacre.drumClassFor(NacreVoice.GROTTO, macros + held))
            // No click: the wrap's step is no bigger than the biggest step the loop already takes inside itself.
            val (inside, wrap) = steps(loop)
            assertTrue(wrap <= inside, "MIDI $midi $velocity $macros: the wrap steps $wrap, the loop's own biggest step is $inside")
            // The level does not jump across the wrap (HeldLoop's 1.5 dB reading, used here as a measure only).
            val w = 2205
            val step = 20.0 * log10(rms(loop, 0, w) / rms(loop, loop.size - w, loop.size))
            assertTrue(abs(step) < HeldLoop.MAX_LEVEL_STEP_DB, "MIDI $midi level step $step dB across the wrap")
            assertTrue(loop.all { it.isFinite() } && loop.maxOf { abs(it) } <= NacreGrotto.PEAK_CEILING)
        }
    }

    @Test
    fun `the loop played eight times end to end is the real next lap`() {
        val r = NacreGrotto.played(held, 36, .8f, 7L)
        val loop = r.probe.snip.samples
        // The lap the render simulated after the kept loop is the loop again, to the house bar, sample for sample in shape.
        val next = r.native.copyOfRange(r.loopStart + r.loopFrames, r.loopStart + 2 * r.loopFrames)
        assertTrue(NacreHeld.cycleError(r.native, r.loopStart, r.loopFrames) < Keys.MAX_SEAM_ERROR)
        var diff = 0.0
        for (i in loop.indices) diff = maxOf(diff, abs(next[i] - loop[i]).toDouble())
        assertTrue(diff < .02 * loop.maxOf { abs(it) }, "the next lap departs from the loop by $diff")
        val eight = FloatArray(loop.size * 8)
        for (k in 0 until 8) loop.copyInto(eight, k * loop.size)
        val (inside, _) = steps(loop)
        for (k in 1 until 8) assertTrue(abs(eight[k * loop.size] - eight[k * loop.size - 1]) <= inside)
    }

    @Test
    fun `every note settles exactly in tune`() {
        for (midi in NacreGrotto.MIN_MIDI..NacreGrotto.MAX_MIDI) {
            val key = Keys.midiHz(midi).toDouble()
            val plan = NacreHeld.plan(Keys.midiHz(midi))
            val planned = plan.periods * Dsp.RATE.toDouble() / plan.frames
            // Whole periods in whole frames moves the note by at most half a frame over the loop.
            assertTrue(abs(cents(planned, key)) < .05, "MIDI $midi: the plan is ${cents(planned, key)} cents off")
            // The bow's played periods fill the loop: measured on the retuned bow itself.
            val (bow, _, p) = NacreGrotto.heldBow(midi, .3f, 1f)
            val wanted = p.loopFrames.toDouble() * Dsp.OVERSAMPLE
            val played = Bore.measureLoopSamples(bow, p.loopStart * Dsp.OVERSAMPLE, wanted, p.loop.periods)
            assertTrue(abs(cents(wanted / played, 1.0)) < .05, "MIDI $midi: the bow plays ${cents(wanted / played, 1.0)} cents from the loop")
        }
        for (midi in listOf(36, 48, 60)) {
            val key = Keys.midiHz(midi).toDouble()
            val loop = NacreGrotto.render(held, midi, 1f, 7L)
            val heldPitch = assertNotNull(Pitch.detect(loop, 0f, 1f))
            assertTrue(abs(cents(heldPitch.hz.toDouble(), key)) < 10.0 && heldPitch.confidence >= .3f, "held MIDI $midi: $heldPitch")
            val finite = NacreGrotto.render(emptyMap(), midi, 1f, 7L)
            val bowed = assertNotNull(Pitch.detect(finite, 1.5f, .8f))
            assertTrue(abs(cents(bowed.hz.toDouble(), key)) < 10.0 && bowed.confidence >= .3f, "finite MIDI $midi: $bowed")
        }
    }

    @Test
    fun `the bow speaks in Helmholtz motion on the loop`() {
        for (midi in listOf(36, 48, 60)) for (velocity in listOf(.35f, 1f)) {
            val (_, point, plan) = NacreGrotto.heldBow(midi, .3f, velocity)
            val period = Dsp.RATE * Dsp.OVERSAMPLE / plan.hz.toDouble()
            val end = (plan.loopStart + plan.loopFrames) * Dsp.OVERSAMPLE
            val slips = BowMeter.slipsPerPeriod(point, end, period, Arco.V_SUSTAIN)
            assertEquals(1.0, slips, "MIDI $midi velocity $velocity slips $slips times a period")
        }
    }

    @Test
    fun `renders are deterministic across cold caches and the seed is a real input`() {
        for (macros in listOf(emptyMap(), held)) {
            Nacre.clearCoreCache()
            val a = NacreGrotto.render(macros, 43, .8f, 7L).samples
            Nacre.clearCoreCache()
            val b = NacreGrotto.render(macros, 43, .8f, 7L).samples
            assertEquals(hash(a), hash(b), "GROTTO $macros is not deterministic")
            assertNotEquals(hash(a), hash(NacreGrotto.render(macros, 43, .8f, 8L).samples), "GROTTO $macros ignores its seed")
            assertTrue(a.all { it.isFinite() })
        }
    }

    @Test
    fun `a finite note is the held note until the bow begins to lift`() {
        for (hold in listOf(0f, .5f)) {
            val finite = NacreGrotto.played(mapOf("HOLD" to hold), 41, .7f, 7L)
            val loop = NacreGrotto.played(held, 41, .7f, 7L)
            val lift = (NacreGrotto.bowSeconds(hold) * Dsp.RATE).toInt()
            assertEquals(NacreGrotto.renderFrames(mapOf("HOLD" to hold), 41), finite.native.size)
            // The conditioning is local (a forward filter and a short decimating kernel), so the two match up to the lift's edge.
            val until = lift - 64
            for (stem in listOf("bass", "upper", "sympathetic", "venue")) {
                assertContentEquals(loop.probe.stems.getValue(stem).copyOf(until), finite.probe.stems.getValue(stem).copyOf(until), "$stem at HOLD $hold")
            }
            val after = finite.probe.stems.getValue("bass")
            assertFalse(after.copyOfRange(lift + 4410, lift + 8820).contentEquals(
                loop.probe.stems.getValue("bass").copyOfRange(lift + 4410, lift + 8820)), "the bow never lifted")
            assertEquals(DrumClass.TONAL, Nacre.drumClassFor(NacreVoice.GROTTO, mapOf("HOLD" to hold)))
        }
    }

    @Test
    fun `the held clock fits every water rate to whole cycles of the loop`() {
        for (midi in listOf(36, 47, 60)) {
            val frames = NacreHeld.plan(Keys.midiHz(midi)).frames
            val seconds = frames.toDouble() / Dsp.RATE
            val field = NacreHeld.field(NacreGrotto.MODEL, 7L, frames)
            assertEquals(NacreHeld.FIELD_CYCLES.toDouble(), field.hz * seconds, 1e-12)
            for (current in listOf(0f, .1f, .2f, .5f, 1f)) {
                val hz = NacreHeld.fittedHz(NacreHeld.apertureCycles(current, frames), frames)
                val cycles = hz * seconds
                assertEquals(Math.round(cycles).toDouble(), cycles, 1e-12)
                assertTrue(abs(hz - Nacre.apertureRate(current)) <= .5 / seconds + 1e-12)
            }
            // The loop-relative clock wraps exactly: a frame one loop on reads the same phase.
            for (i in listOf(0, 17, frames - 1)) assertEquals(NacreHeld.phase(i, frames, 7), NacreHeld.phase(i + frames, frames, 7))
            assertTrue(seconds >= NacreHeld.LOOP_TARGET_SECONDS && seconds < NacreHeld.LOOP_TARGET_SECONDS + .02)
        }
        assertEquals(7, NacreHeld.apertureCycles(.2f, NacreHeld.plan(Keys.midiHz(36)).frames))
    }

    @Test
    fun `DUET sets the glints, and DUET 0 has none`() {
        assertEquals(0, NacreGrotto.glintCount(0.0))
        assertEquals(2, NacreGrotto.glintCount(.15))
        assertEquals(5, NacreGrotto.glintCount(1.0))
        val none = NacreGrotto.played(mapOf("DUET" to 0f) + held, 36, 1f, 7L)
        assertTrue(none.probe.events.none { it.performer == "upper" })
        assertTrue(none.probe.stems.getValue("upper").all { it == 0f }, "DUET 0 still sounds the upper harp")
        val some = NacreGrotto.played(mapOf("DUET" to .55f) + held, 36, 1f, 7L)
        val glints = some.probe.events.filter { it.performer == "upper" }
        assertTrue(glints.isNotEmpty() && glints.all { it.direction == "glint" })
        // Quiet: the glints sit well under the bow on the loop.
        val s = some.loopStart
        val l = some.loopFrames
        val under = 20.0 * log10(rms(some.probe.stems.getValue("upper"), s, s + l) / rms(some.probe.stems.getValue("bass"), s, s + l))
        assertTrue(under < -10.0, "the glints are only $under dB under the bow")
        // A firmer bow is heard first: the first glint comes later.
        val soft = NacreGrotto.glints(0.0, .3, 1f, 7L, NacreGrotto.planFor(36, 0.0)).minOf { it.offsetRaw }
        val firm = NacreGrotto.glints(1.0, .3, 1f, 7L, NacreGrotto.planFor(36, 1.0)).minOf { it.offsetRaw }
        assertTrue(firm > soft)
    }

    @Test
    fun `the harp's sympathetic strings and water are still heard under a held bow`() {
        val r = NacreGrotto.played(held, 36, 1f, 7L)
        val s = r.loopStart
        val l = r.loopFrames
        val source = rms(r.probe.stems.getValue("bass"), s, s + l)
        val sym = 20.0 * log10(rms(r.probe.stems.getValue("sympathetic"), s, s + l) / source)
        val venue = 20.0 * log10(rms(r.probe.stems.getValue("venue"), s, s + l) / source)
        println("GROTTO held C2: sympathetic %.1f dB, venue %.1f dB against the bowed source".format(java.util.Locale.ROOT, sym, venue))
        assertTrue(sym > -18.0, "sympathetic $sym dB")
        assertTrue(venue > -18.0, "venue $venue dB")
        for ((name, value) in listOf("VENUE" to 0f, "VENUE" to 1f, "BLOOM" to .15f, "BLOOM" to .7f, "CURRENT" to 0f, "CURRENT" to .6f)) {
            val moved = NacreGrotto.played(held + (name to value), 36, 1f, 7L)
            assertNotEquals(hash(r.probe.snip.samples), hash(moved.probe.snip.samples), "$name $value does not move the loop")
            assertEquals(1.0, moved.probe.diagnostics.getValue("coreCacheHit"), "$name re-rendered the bow")
        }
    }

    @Test
    fun `velocity changes the bow's colour, not only its level`() {
        val soft = NacreGrotto.render(held, 36, .35f, 7L)
        val hard = NacreGrotto.render(held, 36, 1f, 7L)
        fun brightness(s: Snip): Double {
            val x = s.samples
            var d = 0.0
            for (i in 1 until x.size) d += (x[i] - x[i - 1]).toDouble() * (x[i] - x[i - 1])
            return sqrt(d / x.size) / rms(x, 0, x.size)
        }
        assertTrue(brightness(hard) > brightness(soft) * 1.03, "hard ${brightness(hard)} soft ${brightness(soft)}")
        assertTrue(rms(hard.samples, 0, hard.samples.size) > rms(soft.samples, 0, soft.samples.size) * 1.4)
        assertTrue(NacreGrotto.gripFor(.3, 1f) > NacreGrotto.gripFor(.3, .35f))
    }

    @Test
    fun `GROTTO plays C2 to C4 and its finite length is closed form`() {
        for (midi in listOf(35, 61, 28, 72)) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { NacreGrotto.render(emptyMap(), midi) }
            kotlin.test.assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.GROTTO, emptyMap(), midi = midi) }
        }
        assertEquals(36..60, Nacre.midiRange(NacreVoice.GROTTO))
        assertEquals(36, NacrePatch("x", NacreVoice.GROTTO, emptyMap()).midi)
        assertEquals(2.5f, NacreGrotto.bowSeconds(0f))
        assertEquals(8f, NacreGrotto.bowSeconds(.98f))
        for (hold in listOf(0f, .3f, .98f)) {
            assertEquals(NacreGrotto.renderFrames(mapOf("HOLD" to hold), 50), NacreGrotto.render(mapOf("HOLD" to hold), 50, .6f, 3L).samples.size)
        }
        assertEquals(NacreGrotto.renderFrames(held, 50), NacreGrotto.render(held, 50, .6f, 3L).samples.size)
    }
}
