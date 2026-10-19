package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phrase roles must reach the circuit, rather than remain labels on a random event train. */
class SkitterPhrasingTest {
    @Test
    fun `finite rendered departures form quick pairs or triples followed by breathing room`() {
        for (voice in SkitterVoice.entries) {
            val rendered = Skitter.renderProbe(voice, macros(voice, false), seed = 917, seconds = 3.2f,
                probe = SkitterProbe(memoryFrozen = true))
            val events = departures(rendered)
            assertEquals(rendered.diagnostics.gestureCount, events.size, "$voice missing a rendered phrase event")
            val groups = mutableListOf(mutableListOf(events.first()))
            val replies = mutableListOf<Double>()
            val breaths = mutableListOf<Double>()
            for ((a, b) in events.zipWithNext()) {
                val gap = b.seconds - a.seconds
                if (gap > .180) {
                    breaths += gap
                    groups += mutableListOf(b)
                } else {
                    replies += gap
                    groups.last() += b
                }
            }
            // The final group may be cut short by the note's release or the requested probe length.
            val complete = groups.dropLast(1)
            assertTrue(complete.size >= 3, "$voice does not expose enough complete phrases")
            assertTrue(complete.all { it.size in 2..3 }, "$voice still has an ungrouped train: ${groups.map { it.size }}")
            assertTrue(replies.isNotEmpty() && breaths.isNotEmpty(), "$voice has no reply/breath contrast")
            assertTrue(replies.max() <= .170, "$voice replies are slow: $replies")
            assertTrue(breaths.average() > replies.average() * 1.25, "$voice phrases have no breathing room: $breaths vs $replies")
            assertTrue(breaths.min() > replies.max() + .015, "$voice breaths are indistinguishable from replies")
            println("$voice finite phrases: sizes ${complete.map { it.size }}, reply mean ${replies.average()}, breath mean ${breaths.average()}, contrast ${breaths.average() / replies.average()}")
        }
    }

    @Test
    fun `held replies produce softer real agitation and the third answer weakens further`() {
        for (voice in SkitterVoice.entries) {
            val rendered = Skitter.renderProbe(voice, macros(voice, true), midi = 57, seed = 917,
                probe = SkitterProbe(memoryFrozen = true))
            val schedule = Skitter.heldGestureSchedule(.9f.toDouble(), Dsp.seedFor("SKITTER", voice.name, 57, 917), rendered.loopStart, voice)
            val events = departures(rendered)
            assertEquals(schedule.size, events.size, "$voice planned phrases do not reach the pitch trace")
            assertTrue(schedule.first().primary, "$voice loop starts with an orphan reply")
            val groups = heldGroups(schedule)
            assertTrue(groups.all { it.size in 2..3 }, "$voice held phrase sizes ${groups.map { it.size }}")
            val ratios = mutableListOf<Double>()
            for ((planned, observed) in schedule.zip(events)) {
                assertTrue(abs(observed.seconds - planned.frame.toDouble() / Dsp.RATE) <= .005001,
                    "$voice phrase marker does not match the actual departure")
            }
            for (group in groups) {
                val peaks = group.map { event ->
                    val at = event.frame.toDouble() / Dsp.RATE
                    rendered.trace.filter { it.seconds >= at && it.seconds < at + .110 }.maxOf { abs(it.agitation) }
                }
                assertTrue(peaks.first() > 1e-4, "$voice primary has no circuit response")
                for (i in 1 until group.size) {
                    val kickRatio = abs(group[i].kick / group.first().kick)
                    assertTrue(kickRatio in .10.. .80, "$voice reply is not bounded against its own primary ($kickRatio)")
                    val actualRatio = peaks[i] / peaks.first()
                    assertTrue(actualRatio in .10.. .85, "$voice rendered reply is absent or louder than its primary ($actualRatio)")
                    ratios += actualRatio
                }
                if (peaks.size == 3) assertTrue(peaks[2] < peaks[1] * .90, "$voice third answer does not soften further: $peaks")
            }
            val gaps = schedule.zipWithNext { a, b -> b.frame - a.frame } + (rendered.loopStart - schedule.last().frame)
            assertTrue(gaps.all { it >= MINIMUM_GAP_FRAMES }, "$voice phrase wrap breaks the eight hertz cap: $gaps")
            println("$voice held phrases: sizes ${groups.map { it.size }}, actual reply ratios ${ratios.min()}..${ratios.max()}, wrap ${gaps.last()} frames")
        }
    }

    @Test
    fun `phrase seeds retain pitch draws while calm and reactive voices choose different groupings`() {
        var calmTriples = 0
        var calmGroups = 0
        var reactiveTriples = 0
        var reactiveGroups = 0
        for (seed in 0 until 24) {
            val reference = Skitter.heldGestureSchedule(.9, seed, Dsp.RATE, SkitterVoice.FLUTTER)
            assertEquals(reference, Skitter.heldGestureSchedule(.9, seed, Dsp.RATE, SkitterVoice.FLUTTER), "phrase seed $seed does not repeat")
            for (voice in SkitterVoice.entries) {
                val schedule = Skitter.heldGestureSchedule(.9, seed, Dsp.RATE, voice)
                assertEquals(reference.map { it.a to it.b }, schedule.map { it.a to it.b }, "$voice phrasing consumes the pitch random stream")
                val sizes = heldGroups(schedule).map { it.size }
                assertTrue(sizes.all { it in 2..3 }, "$voice leaves a single event at a phrase boundary")
                if (voice in listOf(SkitterVoice.BASS, SkitterVoice.HOLLOW)) {
                    calmTriples += sizes.count { it == 3 }
                    calmGroups += sizes.size
                } else if (voice in listOf(SkitterVoice.FLUTTER, SkitterVoice.CHIRP, SkitterVoice.FRAY)) {
                    reactiveTriples += sizes.count { it == 3 }
                    reactiveGroups += sizes.size
                }
            }
        }
        val calm = calmTriples.toDouble() / calmGroups
        val reactive = reactiveTriples.toDouble() / reactiveGroups
        assertTrue(reactive > calm, "voice phrasing has no calm/reactive distinction: $calm vs $reactive")
        val first = Skitter.heldGestureSchedule(.9, 221, Dsp.RATE, SkitterVoice.FRAY)
        val other = Skitter.heldGestureSchedule(.9, 222, Dsp.RATE, SkitterVoice.FRAY)
        assertFalse(first.map { it.frame to it.kick } == other.map { it.frame to it.kick }, "phrase timing and accents ignore their seed")
        println("phrase voice contrast: calm triple fraction $calm, reactive triple fraction $reactive")
    }

    @Test
    fun `phrasing respects zero departure and the refractory cap even in short held periods`() {
        for (voice in listOf(SkitterVoice.BASS, SkitterVoice.FRAY)) {
            for (frames in listOf(MINIMUM_GAP_FRAMES * 2, MINIMUM_GAP_FRAMES * 3, Dsp.RATE))
                for (amount in listOf(.35, .9, 1.0)) for (seed in listOf(0, 1, 917, Int.MIN_VALUE)) {
                    val schedule = Skitter.heldGestureSchedule(amount, seed, frames, voice)
                    val gaps = schedule.zipWithNext { a, b -> b.frame - a.frame } + (frames - schedule.last().frame)
                    assertTrue(gaps.all { it >= MINIMUM_GAP_FRAMES }, "$voice $amount/$seed has a short phrase gap in $frames frames: $gaps")
                    assertTrue(heldGroups(schedule).all { it.size in 2..3 }, "$voice strands a reply in a short period")
                }
            assertTrue(Skitter.heldGestureSchedule(0.0, 917, Dsp.RATE, voice).isEmpty())
            for (held in listOf(false, true)) {
                val settings = macros(voice, held) + ("SKITTER" to 0f)
                val a = Skitter.renderProbe(voice, settings, seed = 221, seconds = .5f)
                val b = Skitter.renderProbe(voice, settings, seed = 222, seconds = .5f)
                assertEquals(0, a.diagnostics.gestureCount, "$voice held=$held SKITTER zero starts a phrase")
                assertTrue(a.trace.all { it.pitchOffsetA == 0.0 && it.pitchOffsetB == 0.0 }, "$voice held=$held SKITTER zero has a departure")
                assertContentEquals(a.raw, b.raw, "$voice held=$held silent phrase scheduler changes the powered source")
            }
        }
    }

    private data class Departure(val seconds: Double)

    /** Infer the untouched exponential intervals, then locate actual B-offset jumps. */
    private fun departures(rendered: SkitterRender): List<Departure> {
        val trace = rendered.trace
        val peak = trace.maxOf { abs(it.pitchOffsetB) }
        val slopes = trace.zipWithNext().mapNotNull { (a, b) ->
            if (abs(a.pitchOffsetB) > peak * 1e-6 && a.pitchOffsetB * b.pitchOffsetB > 0.0 && abs(b.pitchOffsetB) < abs(a.pitchOffsetB))
                ln(b.pitchOffsetB / a.pitchOffsetB) / (b.seconds - a.seconds)
            else null
        }.sorted()
        assertTrue(slopes.isNotEmpty(), "no rendered recovery intervals")
        val decay = slopes[slopes.size / 2]
        val threshold = maxOf(.01, peak * .08)
        val first = trace.first()
        val initial = if (rendered.loopStart < 0) first.pitchOffsetB else {
            val last = trace.last()
            first.pitchOffsetB - last.pitchOffsetB * exp(decay * (rendered.loopStart.toDouble() / Dsp.RATE - last.seconds))
        }
        val found = mutableListOf<Departure>()
        if (abs(initial) > threshold) found += Departure(first.seconds)
        for ((a, b) in trace.zipWithNext()) {
            if (abs(b.pitchOffsetB - a.pitchOffsetB * exp(decay * (b.seconds - a.seconds))) > threshold) found += Departure(b.seconds)
        }
        return found
    }

    private fun heldGroups(schedule: List<Skitter.GestureEvent>): List<List<Skitter.GestureEvent>> {
        val groups = mutableListOf<MutableList<Skitter.GestureEvent>>()
        for (event in schedule) {
            if (event.primary) groups += mutableListOf(event) else {
                assertTrue(groups.isNotEmpty(), "orphan held reply")
                groups.last() += event
            }
        }
        return groups
    }

    private fun macros(voice: SkitterVoice, held: Boolean): Map<String, Float> = Skitter.defaults(voice) +
        mapOf("SKITTER" to .9f, "AGITATE" to .75f, "STATIC" to 0f, "HOLD" to if (held) 1f else .9f)

    private companion object {
        val MINIMUM_GAP_FRAMES = ceil(Dsp.RATE * .125).toInt()
    }
}
