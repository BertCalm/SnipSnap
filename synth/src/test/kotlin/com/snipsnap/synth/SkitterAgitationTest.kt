package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Listening regressions: quick recoveries and seeded gestures rather than a regular siren. */
class SkitterAgitationTest {
    @Test
    fun `finite departures vary their spacing strength and direction in the rendered circuit`() {
        for (voice in SkitterVoice.entries) {
            val rendered = Skitter.renderProbe(voice, active(voice, held = false), seed = 917, seconds = 3.2f)
            val events = events(rendered)
            val repeated = events.drop(1) // The first key-down is intentionally a common provocation.
            assertEquals(rendered.diagnostics.gestureCount, events.size, "$voice missing rendered departures")
            assertTrue(repeated.size >= 6, "$voice has too few departures to hear a varied episode")
            val gaps = events.zipWithNext { a, b -> b.seconds - a.seconds }
            assertTrue(gaps.all { it >= MIN_GAP_SECONDS - TRACE_SECONDS - 1e-6 }, "$voice refractory gaps $gaps")
            assertTrue(gaps.max() - gaps.min() > .020, "$voice still has a regular gesture clock: $gaps")
            // Replies and breaths deliberately skew the median; preserve R2's overall reaction
            // rate with the same 200ms bar on mean spacing. Phrasing tests check the two scales.
            assertTrue(gaps.average() < .200, "$voice departures still move at the old slow overall cadence: $gaps")
            assertVariedDepartures(repeated, "$voice finite")
            println("$voice finite: ${events.size} gestures; gaps ${gaps.min()}..${gaps.max()}, mean ${gaps.average()}, median ${median(gaps)}, strength ratio ${strengthRatio(repeated)}")
        }
    }

    @Test
    fun `held irregular gestures reach the traced circuit and keep their gap across the wrap`() {
        for (voice in SkitterVoice.entries) {
            val rendered = Skitter.renderProbe(voice, active(voice, held = true), midi = 57, seed = 917)
            val period = rendered.loopStart.toDouble() / Dsp.RATE
            val events = events(rendered)
            val schedule = Skitter.heldGestureSchedule(.9f.toDouble(), Dsp.seedFor("SKITTER", voice.name, 57, 917), rendered.loopStart, voice)
            assertEquals(schedule.size, events.size, "$voice held schedule is absent from the rendered offsets")
            assertEquals(schedule.size * 2, rendered.diagnostics.gestureCount, "$voice held diagnostic count")
            for ((planned, observed) in schedule.zip(events)) {
                assertTrue(abs(observed.seconds - planned.frame.toDouble() / Dsp.RATE) <= TRACE_SECONDS + 1e-6,
                    "$voice departure at ${planned.frame} frames appeared at ${observed.seconds}s")
                assertTrue(observed.impulse * planned.b > 0.0, "$voice rendered departure lost its scheduled direction")
            }
            assertVariedDepartures(events, "$voice held")
            val gaps = events.zipWithNext { a, b -> b.seconds - a.seconds } + (period - events.last().seconds + events.first().seconds)
            assertTrue(gaps.all { it >= MIN_GAP_SECONDS - TRACE_SECONDS - 1e-6 }, "$voice held refractory gaps including wrap $gaps")
            val exactGaps = schedule.zipWithNext { a, b -> b.frame - a.frame } + (rendered.loopStart - schedule.last().frame)
            assertTrue(exactGaps.max() > exactGaps.min() + 1, "$voice held timing is still uniform")
            // Existing loop tests cover seam margins and preroll counts; this checks the new schedule's result.
            assertTrue(rendered.diagnostics.loopConverged, "$voice irregular held forcing does not converge")
            assertEquals(0, rendered.diagnostics.boundHits, "$voice irregular held forcing hit a state bound")
            println("$voice held: ${events.size} gestures; gaps $exactGaps frames, strength ratio ${strengthRatio(events)}")
        }
    }

    @Test
    fun `the seed changes structural gestures even with STATIC silent in finite and held notes`() {
        for (held in listOf(false, true)) {
            val voice = SkitterVoice.FLUTTER
            val macros = active(voice, held)
            val first = Skitter.renderProbe(voice, macros, seed = 221, seconds = 2f)
            val repeat = Skitter.renderProbe(voice, macros, seed = 221, seconds = 2f)
            val other = Skitter.renderProbe(voice, macros, seed = 222, seconds = 2f)
            assertContentEquals(first.raw, repeat.raw, "held=$held deterministic raw circuit")
            assertContentEquals(first.snip.samples, repeat.snip.samples, "held=$held deterministic export")
            assertEquals(first.trace, repeat.trace, "held=$held deterministic gestures")
            assertFalse(first.trace.map { it.pitchOffsetB } == other.trace.map { it.pitchOffsetB },
                "held=$held seed only changes STATIC noise")
            assertFalse(events(first) == events(other), "held=$held seed leaves the structural episode unchanged")
            assertFalse(first.raw.contentEquals(other.raw), "held=$held varied gestures never reach the audio")
        }
    }

    @Test
    fun `an isolated provocation makes a quick biphasic flutter and unequal pitches recover promptly`() {
        for (voice in SkitterVoice.entries) {
            val macros = Skitter.defaults(voice) + mapOf("SKITTER" to .2f, "AGITATE" to .8f, "STATIC" to 0f)
            // Frozen history removes the sustained energy bias, exposing the gesture's actual recovery.
            val rendered = Skitter.renderProbe(voice, macros, seconds = .4f, probe = SkitterProbe(memoryFrozen = true))
            assertEquals(1, rendered.diagnostics.gestureCount, "$voice isolated onset has extra provocations")
            val initial = rendered.trace.first()
            val at100ms = rendered.trace.first { it.seconds >= .100 }
            val at300ms = rendered.trace.first { it.seconds >= .300 }
            val recoveryA = abs(at100ms.pitchOffsetA / initial.pitchOffsetA)
            val recoveryB = abs(at300ms.pitchOffsetB / initial.pitchOffsetB)
            assertTrue(recoveryA < .13, "$voice fast pitch recovery leaves $recoveryA at 100ms")
            assertTrue(recoveryB < .17, "$voice slow pitch recovery leaves $recoveryB at 300ms")
            val transient = rendered.trace.filter { it.seconds <= .150 }
            val crest = transient.maxBy { it.agitation }
            val trough = transient.minOf { it.agitation }
            val peak = transient.maxOf { abs(it.agitation) }
            val late = rendered.trace.filter { it.seconds >= .250 }.maxOf { abs(it.agitation) }
            assertTrue(crest.agitation > 1e-4, "$voice provocation has no positive filter reaction")
            assertTrue(crest.seconds < .075, "$voice filter crest is still slow (${crest.seconds}s)")
            assertTrue(trough < -.10 * crest.agitation, "$voice filter kick has no meaningful opposite recovery ($trough vs ${crest.agitation})")
            assertTrue(late < peak * .10, "$voice isolated flutter keeps swinging ($late vs $peak)")
            println("$voice onset: pitch fractions $recoveryA / $recoveryB; crest ${crest.seconds}s, rebound ${trough / crest.agitation}, late fraction ${late / peak}")
        }
    }

    @Test
    fun `held schedules reserve the eight hertz refractory interval at every boundary and seed`() {
        val minimumFrames = ceil(Dsp.RATE * MIN_GAP_SECONDS).toInt()
        val periods = listOf(minimumFrames * 2, minimumFrames * 3, (Dsp.RATE * .85).roundToInt(), Dsp.RATE, (Dsp.RATE * 1.15).roundToInt())
        for (frames in periods) for (amount in listOf(.02, .3, .7, 1.0)) for (seed in listOf(0, 1, 19, 917, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val schedule = Skitter.heldGestureSchedule(amount, seed, frames, SkitterVoice.FLUTTER)
            val label = "$frames frames, SKITTER $amount, seed $seed"
            assertTrue(schedule.size >= 2, "$label lacks a repeating episode")
            assertEquals(0, schedule.first().frame, "$label missing the boundary gesture")
            assertTrue(schedule.all { it.frame in 0 until frames }, "$label event outside the period")
            val gaps = schedule.zipWithNext { a, b -> b.frame - a.frame } + (frames - schedule.last().frame)
            assertTrue(gaps.all { it >= minimumFrames }, "$label has a too-short gap, including wrap: $gaps")
        }
        assertTrue(Skitter.heldGestureSchedule(0.0, 917, Dsp.RATE, SkitterVoice.FLUTTER).isEmpty(), "SKITTER zero schedules departures")
    }

    private data class Event(val seconds: Double, val impulse: Double)

    /**
     * Between departures B decays smoothly. Infer that decay from the median untouched interval,
     * then subtract it to locate real impulses without depending on the engine's recovery constants.
     */
    private fun events(rendered: SkitterRender): List<Event> {
        val trace = rendered.trace
        val peak = trace.maxOf { abs(it.pitchOffsetB) }
        val slopes = trace.zipWithNext().mapNotNull { (a, b) ->
            if (abs(a.pitchOffsetB) > peak * 1e-6 && a.pitchOffsetB * b.pitchOffsetB > 0.0 && abs(b.pitchOffsetB) < abs(a.pitchOffsetB))
                ln(b.pitchOffsetB / a.pitchOffsetB) / (b.seconds - a.seconds)
            else null
        }
        assertTrue(slopes.isNotEmpty(), "trace has no pitch recovery intervals")
        val decay = median(slopes)
        val threshold = maxOf(.01, peak * .08)
        val found = mutableListOf<Event>()
        val first = trace.first()
        val initial = if (rendered.loopStart < 0) first.pitchOffsetB else {
            val last = trace.last()
            val wrap = rendered.loopStart.toDouble() / Dsp.RATE - last.seconds + first.seconds
            first.pitchOffsetB - last.pitchOffsetB * exp(decay * wrap)
        }
        if (abs(initial) > threshold) found += Event(first.seconds, initial)
        for ((a, b) in trace.zipWithNext()) {
            val impulse = b.pitchOffsetB - a.pitchOffsetB * exp(decay * (b.seconds - a.seconds))
            if (abs(impulse) > threshold) found += Event(b.seconds, impulse)
        }
        return found
    }

    private fun assertVariedDepartures(events: List<Event>, label: String) {
        assertTrue(events.zipWithNext().any { (a, b) -> a.impulse * b.impulse > 0.0 }, "$label is still a compulsory +/- pendulum")
        assertTrue(events.any { it.impulse > 0.0 } && events.any { it.impulse < 0.0 }, "$label only departs in one direction")
        assertTrue(strengthRatio(events) > 1.12, "$label departures all have the same strength (${strengthRatio(events)})")
    }

    private fun strengthRatio(events: List<Event>): Double = events.maxOf { abs(it.impulse) } / events.minOf { abs(it.impulse) }

    private fun active(voice: SkitterVoice, held: Boolean): Map<String, Float> = Skitter.defaults(voice) +
        mapOf("SKITTER" to .9f, "AGITATE" to .75f, "STATIC" to 0f, "HOLD" to if (held) 1f else .9f)

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private companion object {
        const val MIN_GAP_SECONDS = .125
        const val TRACE_SECONDS = .005
    }
}
