package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfluenceTest {
    private fun vowel(seconds: Float = .65f): ConfluenceSource {
        val samples = FloatArray((seconds * Confluence.RATE).toInt()) { i ->
            val phase = 2 * PI * 130.81278265 * i / Confluence.RATE
            (.28 * sin(phase) + .12 * sin(3 * phase) + .06 * sin(7 * phase)).toFloat()
        }
        return ConfluenceSource.fromSnip(Snip(samples, 1, Confluence.RATE))
    }

    private fun recipe(source: ConfluenceSource, count: Int = 4, profile: ConfluenceProfile = ConfluenceProfile.UNISON) =
        ConfluenceRecipe("Test gathering", profile,
            listOf(ConfluenceRegion(source.hash, 0, source.frameCount, referenceMidi = 48f,
                sustainStartFrame = 2205, sustainEndFrame = source.frameCount - 2205)),
            macros = mapOf("CROWD" to Confluence.crowdForCount(count), "SPACE" to 0f), seed = 7, seconds = 3.5f)

    @Test
    fun `same resolved assets recipe and options regenerate identical finite samples and traces`() {
        val source = vowel()
        val input = recipe(source)
        val a = Confluence.render(input, mapOf(source.hash to source))
        val b = Confluence.render(ConfluenceRecipe.fromJsonText(input.toJsonText()), mapOf(source.hash to source))
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertEquals(a.events, b.events)
        assertEquals(a.performers, b.performers)
        assertEquals(Confluence.RATE, a.snip.sampleRate)
        assertEquals(1, a.snip.channels)
        assertEquals((input.seconds * Confluence.RATE).toInt(), a.snip.frameCount)
        assertTrue(a.snip.samples.all { it.isFinite() })
        assertTrue(a.snip.peak() in .01f..1f)
        assertTrue(abs(a.diagnostics.dc) < .001f)
        assertTrue(a.diagnostics.voicedShare > .7f, "clean periodic test fixture should take the pitched path")
    }

    @Test
    fun `growth retains every existing identity and complete phrase event`() {
        val source = vowel()
        val a = Confluence.render(recipe(source, 2), mapOf(source.hash to source))
        val b = Confluence.render(recipe(source, 8), mapOf(source.hash to source))
        assertEquals(a.performers, b.performers.take(2))
        assertEquals(a.events.filter { it.performerId == 0 && it.state == "ENTER" }.map { it.pitchSemitones to it.durationRatio },
            b.events.filter { it.performerId == 0 && it.state == "ENTER" }.map { it.pitchSemitones to it.durationRatio })
        assertEquals(8, b.diagnostics.activePerformers)
        assertTrue(b.events.all { it.startFrame >= 0 && it.endFrame <= b.snip.frameCount })
        assertTrue(b.events.filter { it.state == "SING" }.all { it.endFrame > it.startFrame })
        assertTrue(b.events.count { it.performerId == 0 && it.state == "ENTER" } >= 2)
        assertTrue(b.events.any { it.performerId == 0 && it.state == "REST" && it.startFrame > 0 })
        assertEquals(8, b.performers.map { it.id }.toSet().size)
    }

    @Test
    fun `source snapshots and verified hashes reject changed relinks and caller mutations`() {
        val source = vowel()
        val changed = FloatArray(source.frameCount) { .1f }
        assertFailsWith<IllegalArgumentException> { ConfluenceSource(source.hash, changed) }
        assertFailsWith<IllegalArgumentException> { Confluence.render(recipe(source), emptyMap()) }
        val original = FloatArray(22_050) { sin(2 * PI * 130.8128 * it / Confluence.RATE).toFloat() * .2f }
        val frozen = ConfluenceSource.fromSnip(Snip(original, 1, Confluence.RATE))
        val before = Confluence.render(recipe(frozen), mapOf(frozen.hash to frozen))
        original.fill(0f)
        val after = Confluence.render(recipe(frozen), mapOf(frozen.hash to frozen))
        assertContentEquals(before.snip.samples, after.snip.samples)
        val mutable = mutableMapOf("CROWD" to .5f)
        val input = recipe(source).copy(macros = mutable)
        mutable["CROWD"] = Float.NaN
        assertFailsWith<IllegalArgumentException> { Confluence.render(input, mapOf(source.hash to source)) }
    }

    @Test
    fun `unsupported holds missing sustains silent corrupt and incomplete phrase assignments refuse clearly`() {
        val source = vowel()
        val input = recipe(source)
        assertFailsWith<IllegalArgumentException> { input.copy(hold = true) }
        assertFailsWith<IllegalArgumentException> { input.copy(profile = ConfluenceProfile.DRONE,
            regions = listOf(input.regions.single().copy(sustainStartFrame = null, sustainEndFrame = null))) }
        assertFailsWith<IllegalArgumentException> { input.regions.single().copy(referenceMidi = null) }
        assertFailsWith<IllegalArgumentException> { ConfluenceSource.fromSnip(Snip(FloatArray(22_050) { Float.NaN }, 1, Confluence.RATE)) }
        val silence = ConfluenceSource.fromSnip(Snip(FloatArray(22_050), 1, Confluence.RATE))
        assertFailsWith<IllegalArgumentException> { Confluence.render(recipe(silence), mapOf(silence.hash to silence)) }
        val long = vowel(2.5f)
        assertFailsWith<IllegalArgumentException> { Confluence.render(recipe(long).copy(seconds = 2f), mapOf(long.hash to long)) }
    }

    @Test
    fun `small pitch transformation moves the pulse clock while preserving phrase duration`() {
        val source = vowel(1.2f)
        val analysis = ConfluenceVoice.analyze(source.pcm, 48f)
        val original = ConfluenceVoice.render(analysis, ConfluenceSourceMode.PITCHED, 0f, 1f, 1f, 0f, 5f, 0f)
        val raised = ConfluenceVoice.render(analysis, ConfluenceSourceMode.PITCHED, 2f, 1f, 1f, 0f, 5f, 0f)
        val stretched = ConfluenceVoice.render(analysis, ConfluenceSourceMode.PITCHED, 0f, 1.03f, 1f, 0f, 5f, 0f)
        assertEquals(original.size, raised.size)
        assertTrue(abs((original.size * 1.03f).toInt() - stretched.size) <= 1, "whole-phrase time control is separate")
        val low = requireNotNull(Pitch.detect(Snip(original, 1, Confluence.RATE)))
        val high = requireNotNull(Pitch.detect(Snip(raised, 1, Confluence.RATE)))
        assertTrue(abs(high.hz / low.hz - 1.122462) < .025, "two semitones should change the measured fundamental: ${low.hz} -> ${high.hz}")
        assertTrue(original.indices.any { abs(original[it] - raised[it]) > .01f })
    }

    @Test
    fun `texture never acquires the requested pitched mapping and dry bypass removes the room`() {
        val random = Random(72)
        val noise = ConfluenceSource.fromSnip(Snip(FloatArray(22_050) { random.nextFloat() * .2f - .1f }, 1, Confluence.RATE))
        val input = ConfluenceRecipe("Air", ConfluenceProfile.UNISON,
            listOf(ConfluenceRegion(noise.hash, 0, noise.frameCount, ConfluenceSourceMode.TEXTURE)),
            mapOf("CROWD" to .4f, "SPACE" to 1f), seconds = 3f)
        val sourceMap = mapOf(noise.hash to noise)
        val a = Confluence.render(input.copy(midi = 12), sourceMap, ConfluenceOptions(spaceBypassed = true, normalize = false))
        val b = Confluence.render(input.copy(midi = 100), sourceMap, ConfluenceOptions(spaceBypassed = true, normalize = false))
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertContentEquals(a.raw, a.dry.samples)
        val room = Confluence.render(input, sourceMap, ConfluenceOptions(normalize = false))
        assertTrue(room.raw.indices.any { abs(room.raw[it] - a.raw[it]) > .001f })
        assertEquals(a.events, room.events, "SPACE and its bypass must preserve the exact arrangement")
    }

    @Test
    fun `response sections and marked drone breaths preserve persistent identities`() {
        val source = vowel()
        for (profile in listOf(ConfluenceProfile.RESPONSE, ConfluenceProfile.DRONE)) {
            val report = Confluence.render(recipe(source, 4, profile).copy(seconds = 7f), mapOf(source.hash to source))
            val entrances = report.events.filter { it.state == "ENTER" }
            assertTrue(entrances.groupBy { it.performerId }.values.all { it.size >= 2 })
            assertTrue(entrances.map { it.startFrame }.toSet().size >= 4)
            assertTrue(report.events.any { it.state == "PAUSE" })
            assertEquals(4, report.performers.size)
            assertTrue(report.diagnostics.maximumSimultaneousPerformers <= 4)
            for (slot in report.performers) {
                val states = report.events.filter { it.performerId == slot.id }.sortedBy { it.startFrame }
                for ((before, after) in states.zipWithNext()) {
                    assertTrue(before.endFrame <= after.startFrame, "$profile performer${slot.id} ${before.state} overlaps ${after.state}")
                }
                assertTrue(states.filter { it.state == "ENTER" }.all { it.formantScale == slot.formantScale })
            }
        }
        val input = recipe(source, 4).copy(seconds = 5f, macros = recipe(source, 4).macros + ("DIVERGENCE" to .8f))
        val coordinated = Confluence.render(input, mapOf(source.hash to source))
        val independent = Confluence.render(input, mapOf(source.hash to source), ConfluenceOptions(coordination = false))
        assertTrue(coordinated.events.any { it.state == "ENTER" && abs(it.neighborPullSeconds) > .0001f })
        assertTrue(independent.events.all { it.neighborPullSeconds == 0f })
        assertNotEquals(coordinated.events, independent.events)
        val isolated = Confluence.render(input, mapOf(source.hash to source), ConfluenceOptions(isolatedPerformer = 0))
        assertEquals(1, isolated.diagnostics.activePerformers)
        assertEquals(4, isolated.diagnostics.rosterPerformers)
    }

    @Test
    fun `endpoint controls admit every roster slot and responses follow completed calls`() {
        val source = vowel()
        for (profile in ConfluenceProfile.entries) for (count in listOf(1, 8)) for (value in listOf(0f, 1f)) {
            val base = recipe(source, count, profile)
            val input = base.copy(seconds = 6f, macros = Confluence.MACRO_NAMES.associateWith { value } +
                ("CROWD" to Confluence.crowdForCount(count)))
            val report = Confluence.render(input, mapOf(source.hash to source))
            assertEquals((0 until count).toSet(), report.events.filter { it.state == "ENTER" }.map { it.performerId }.toSet(),
                "$profile count$count endpoint$value")
            assertTrue(report.snip.samples.all { it.isFinite() })
            assertTrue(report.snip.peak() <= .991f)
            if (profile == ConfluenceProfile.RESPONSE && count > 1) {
                for (round in report.events.filter { it.state == "ENTER" && it.section == 1 }.map { it.eventIndex }.toSet()) {
                    val callEnd = report.events.filter { it.eventIndex == round && it.section == 0 && it.state == "RELEASE" }.maxOf { it.endFrame }
                    val answerStart = report.events.filter { it.eventIndex == round && it.section == 1 && it.state == "ENTER" }.minOf { it.startFrame }
                    assertTrue(answerStart > callEnd, "answer must wait for the complete call at endpoint$value")
                }
            }
        }
    }
}
