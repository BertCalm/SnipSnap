package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfluencePersonalVoiceTest {
    private fun vowel(hz: Double = 130.81278265, seconds: Float = 1.05f): Snip {
        val length = (seconds * Confluence.RATE).toInt()
        return Snip(FloatArray(length) { i ->
            val phase = 2 * PI * hz * i / Confluence.RATE
            val edge = min(1.0, min(i, length - 1 - i) / (Confluence.RATE * .1))
            ((.28 * sin(phase) + .10 * sin(2 * phase) + .055 * sin(3 * phase)) * edge).toFloat()
        }, 1, Confluence.RATE)
    }

    private fun has(assessment: ConfluencePersonalVoice.Assessment, problem: ConfluencePersonalVoice.Problem) =
        assessment.issues.any { it.code == problem }

    @Test
    fun `steady vowel retains its measured native tuning and original PCM without treatment`() {
        val source = vowel(127.3)
        val prepared = ConfluencePersonalVoice.prepare(source)
        assertTrue(prepared.assessment.usable, prepared.assessment.message)
        assertTrue(abs(prepared.referenceHz - 127.3f) < 1f, "native tuning must not be rounded to concert C")
        assertTrue(abs(prepared.referenceMidi - prepared.rootMidi) > .1f, "native and nearest-note roots are distinct")
        assertContentEquals(source.samples, ConfluencePersonalVoice.original(prepared).samples)
        assertEquals(0, prepared.startFrame)
        assertEquals(source.frameCount, prepared.endFrame)
        val expected = ConfluencePersonalVoice.original(prepared).samples.copyOf()
        source.samples.fill(0f)
        ConfluencePersonalVoice.original(prepared).samples.fill(.2f)
        assertContentEquals(expected, ConfluencePersonalVoice.original(prepared).samples, "both caller and player buffers are independent")
        val json = Json.parse(Json.write(prepared.assessment.toJsonValue())).obj()
        assertTrue(json.getValue("usable").bool())
        assertEquals(prepared.sourceHash, json.getValue("sourceHash").str())
    }

    @Test
    fun `raw rail clipping is refused rather than fixed by normalization and excluded clipping is allowed`() {
        val source = vowel()
        source.samples[source.frameCount / 2] = 1f
        val rejected = ConfluencePersonalVoice.assess(source)
        assertTrue(has(rejected, ConfluencePersonalVoice.Problem.CLIPPED))
        assertEquals(1, rejected.clippedSamples)
        assertEquals(1f, source.samples[source.frameCount / 2])
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.prepare(source) }
        val clean = vowel(seconds = 1.5f)
        clean.samples[0] = -1f
        val selected = ConfluencePersonalVoice.assess(clean, 2205, clean.frameCount)
        assertTrue(selected.usable, selected.message)
        assertEquals(0, selected.clippedSamples)
        val stereo = Snip(floatArrayOf(1f, -1f, .2f, -.2f), 2, 48_000)
        assertEquals(2, ConfluencePersonalVoice.clippedSampleCount(stereo), "anti-phase downmix must not hide raw rail contacts")
    }

    @Test
    fun `silence quiet voice noise and nonfinite audio refuse with meaningful evidence`() {
        val silence = Snip(FloatArray(Confluence.RATE), 1, Confluence.RATE)
        assertTrue(has(ConfluencePersonalVoice.assess(silence), ConfluencePersonalVoice.Problem.SILENT))
        val quiet = vowel().let { it.copy(samples = FloatArray(it.samples.size) { i -> it.samples[i] * .001f }) }
        assertTrue(has(ConfluencePersonalVoice.assess(quiet), ConfluencePersonalVoice.Problem.TOO_QUIET))
        val random = Random(200)
        val noise = Snip(FloatArray(Confluence.RATE) { random.nextFloat() * .3f - .15f }, 1, Confluence.RATE)
        assertTrue(has(ConfluencePersonalVoice.assess(noise), ConfluencePersonalVoice.Problem.UNCERTAIN_PITCH))
        val corrupt = vowel().also { it.samples[200] = Float.NaN }
        assertTrue(has(ConfluencePersonalVoice.assess(corrupt), ConfluencePersonalVoice.Problem.NONFINITE))
        for (bad in listOf(silence, quiet, noise, corrupt)) {
            assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.prepare(bad) }
            // Refusal evidence must itself be serializable without NaN/Infinity entering JSON.
            Json.parse(Json.write(ConfluencePersonalVoice.assess(bad).toJsonValue()))
        }
    }

    @Test
    fun `broad qualification catches low and high periodic sources before octave aliases can appear safe`() {
        for (hz in listOf(60.0, 700.0, 1000.0)) {
            val input = vowel(hz)
            val assessment = ConfluencePersonalVoice.assess(input)
            assertTrue(has(assessment, ConfluencePersonalVoice.Problem.UNSUPPORTED_PITCH), "$hz Hz: ${assessment.issues}")
            assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.prepare(input) }
        }
        // A high pure tone can correlate after multiple periods inside the tracker's nominal
        // search range. Independent spectral support must catch that apparent safe subharmonic.
        for (hz in listOf(1500.0, 1800.0)) {
            val input = vowel(hz)
            val assessment = ConfluencePersonalVoice.assess(input)
            assertFalse(assessment.usable, "$hz Hz must not become a safe octave alias")
            assertTrue(has(assessment, ConfluencePersonalVoice.Problem.UNSUPPORTED_PITCH) ||
                has(assessment, ConfluencePersonalVoice.Problem.AMBIGUOUS_PITCH), assessment.issues.toString())
        }
    }

    @Test
    fun `dominant upper harmonic cannot silently relabel a weak fundamental by an octave`() {
        val samples = FloatArray(Confluence.RATE) { i ->
            val phase = 2 * PI * 110.0 * i / Confluence.RATE
            (.025 * sin(phase) + .36 * sin(2 * phase)).toFloat()
        }
        val input = Snip(samples, 1, Confluence.RATE)
        val assessment = ConfluencePersonalVoice.assess(input)
        assertFalse(assessment.usable)
        assertTrue(has(assessment, ConfluencePersonalVoice.Problem.AMBIGUOUS_PITCH), assessment.issues.toString())
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.prepare(input, referenceMidi = 57f) }
        for ((fundamental, upper, lowGain) in listOf(Triple(30.0, 90.0, .04), Triple(60.0, 120.0, .012))) {
            val lowRoot = Snip(FloatArray(Confluence.RATE) { i ->
                (lowGain * sin(2 * PI * fundamental * i / Confluence.RATE) +
                    .36 * sin(2 * PI * upper * i / Confluence.RATE)).toFloat()
            }, 1, Confluence.RATE)
            val lowAssessment = ConfluencePersonalVoice.assess(lowRoot)
            assertFalse(lowAssessment.usable, "$fundamental Hz must not hide behind $upper Hz")
            assertTrue(has(lowAssessment, ConfluencePersonalVoice.Problem.AMBIGUOUS_PITCH) ||
                has(lowAssessment, ConfluencePersonalVoice.Problem.UNSUPPORTED_PITCH), lowAssessment.issues.toString())
        }
    }

    @Test
    fun `a melodic sweep is not accepted as one held vowel`() {
        val length = Confluence.RATE
        var phase = 0.0
        val sweep = Snip(FloatArray(length) { i ->
            phase += 2 * PI * (110.0 + 90.0 * i / length) / Confluence.RATE
            (.3 * sin(phase)).toFloat()
        }, 1, Confluence.RATE)
        val assessment = ConfluencePersonalVoice.assess(sweep)
        assertFalse(assessment.usable)
        assertTrue(has(assessment, ConfluencePersonalVoice.Problem.UNSTABLE_PITCH) ||
            has(assessment, ConfluencePersonalVoice.Problem.UNCERTAIN_PITCH), assessment.issues.toString())
    }

    @Test
    fun `quiet padding cannot make a brief voiced syllable qualify as a held vowel`() {
        val samples = FloatArray(Confluence.RATE)
        val burstFrames = (Confluence.RATE * .15f).toInt()
        val burst = FloatArray(burstFrames) { i ->
            val edge = min(1.0, min(i, burstFrames - i - 1) / (Confluence.RATE * .005))
            (.3 * sin(2 * PI * 130.81278265 * i / Confluence.RATE) * edge).toFloat()
        }
        burst.copyInto(samples, destinationOffset = Confluence.RATE / 3)
        val input = Snip(samples, 1, Confluence.RATE)
        val assessment = ConfluencePersonalVoice.assess(input)
        assertFalse(assessment.usable)
        assertNotNull(assessment.referenceHz, "pitch evidence alone should have qualified; the held-body length is missing")
        assertTrue(has(assessment, ConfluencePersonalVoice.Problem.UNCERTAIN_PITCH), assessment.issues.toString())
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.prepare(input) }
    }

    @Test
    fun `selection and persisted root consistency cannot bypass qualification or silently trim audio`() {
        val input = vowel(seconds = 2f)
        val start = 4410; val end = input.frameCount - 4410
        val prepared = ConfluencePersonalVoice.prepare(input, start, end)
        assertContentEquals(input.samples.copyOfRange(start, end), ConfluencePersonalVoice.original(prepared).samples)
        val restored = ConfluencePersonalVoice.prepare(input, start, end, prepared.referenceMidi)
        assertEquals(prepared.analysisKey, restored.analysisKey)
        assertFailsWith<IllegalArgumentException> {
            ConfluencePersonalVoice.prepare(input, start, end, prepared.referenceMidi + 12f)
        }
        for ((from, to) in listOf(-1 to end, start to input.frameCount + 1, end to start, 0 to 500)) {
            assertFalse(ConfluencePersonalVoice.assess(input, from, to).usable)
            assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.prepare(input, from, to) }
        }
        val otherSelection = ConfluencePersonalVoice.prepare(input, start + 100, end)
        assertNotEquals(prepared.analysisKey, otherSelection.analysisKey)
        val changed = input.copy(samples = input.samples.copyOf().also { it[100] += .0001f })
        assertNotEquals(prepared.analysisKey, ConfluencePersonalVoice.prepare(changed, start, end).analysisKey)
    }

    @Test
    fun `suggested trim is bounded inside long recording and never silently replaces selected source`() {
        val input = vowel(seconds = 9f)
        val assessment = ConfluencePersonalVoice.assess(input)
        assertTrue(has(assessment, ConfluencePersonalVoice.Problem.TOO_LONG))
        val start = assertNotNull(assessment.suggestedStartFrame)
        val end = assertNotNull(assessment.suggestedEndFrame)
        assertTrue(start >= 0 && end <= input.frameCount)
        assertTrue(end - start <= (ConfluencePersonalVoice.MAX_SELECTION_SECONDS * Confluence.RATE).toInt())
        assertTrue(end > start)
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.prepare(input) }
    }

    @Test
    fun `cached performer renders are deterministic finite bounded and retain complete one two four participation`() {
        val prepared = ConfluencePersonalVoice.prepare(vowel())
        val analysis = prepared.analysis
        for (count in listOf(1, 2, 4)) {
            val a = ConfluencePersonalVoice.render(prepared, performers = count)
            val b = ConfluencePersonalVoice.render(prepared, performers = count)
            assertContentEquals(a.snip.samples, b.snip.samples)
            assertEquals(a.events, b.events)
            assertEquals(count, a.diagnostics.activePerformers)
            assertEquals((0 until count).toSet(), a.events.filter { it.state == "SING" }.map { it.performerId }.toSet())
            assertEquals(count, a.events.count { it.state == "ENTER" }, "one complete gesture per performer")
            assertTrue(a.snip.samples.all { it.isFinite() })
            assertTrue(a.snip.peak() in .01f..1f)
            assertTrue(abs(a.diagnostics.dc) < .001f)
            assertFalse(a.diagnostics.holdSupported)
            assertTrue(a.events.all { it.startFrame >= 0 && it.endFrame <= a.snip.frameCount })
            assertTrue(prepared.analysis === analysis)
        }
        val low = ConfluencePersonalVoice.render(prepared, semitones = -2)
        val high = ConfluencePersonalVoice.render(prepared, semitones = 2)
        val lowPitch = assertNotNull(Pitch.detect(low.dry, fromSec = .45f))
        val highPitch = assertNotNull(Pitch.detect(high.dry, fromSec = .45f))
        assertTrue(abs(highPitch.hz / lowPitch.hz - 2.0.pow(4.0 / 12).toFloat()) < .035f)
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.render(prepared, semitones = 3) }
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.render(prepared, performers = 8) }
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalVoice.render(prepared, individuality = Float.NaN) }
    }

    @Test
    fun `prepared analysis leaves ordinary Confluence renderer PCM and traces unchanged`() {
        val prepared = ConfluencePersonalVoice.prepare(vowel())
        val region = ConfluenceRegion(prepared.sourceHash, prepared.startFrame, prepared.endFrame,
            referenceMidi = prepared.referenceMidi)
        val recipe = ConfluenceRecipe("Factory path canary", ConfluenceProfile.UNISON, listOf(region),
            macros = mapOf("CROWD" to Confluence.crowdForCount(2), "SPACE" to .3f), seconds = 4f)
        val ordinary = Confluence.render(recipe, mapOf(prepared.sourceHash to prepared.source))
        val cached = Confluence.renderWithAnalysis(recipe, mapOf(prepared.sourceHash to prepared.source),
            ConfluenceOptions(), mapOf(prepared.key to prepared.analysis))
        assertContentEquals(ordinary.snip.samples, cached.snip.samples)
        assertContentEquals(ordinary.raw, cached.raw)
        assertEquals(ordinary.events, cached.events)
        assertEquals(ordinary.performers, cached.performers)
        assertEquals(ordinary.diagnostics, cached.diagnostics)
    }

    @Test
    fun `licensed real held vowel can prepare while a moving arpeggio must not pretend to be one note`() {
        val directory = listOf(File("../reference/fixtures/confluence"), File("reference/fixtures/confluence"))
            .firstOrNull { File(it, "vowel-male.wav").isFile } ?: error("real Confluence fixtures are required")
        val source = WavReader.read(File(directory, "vowel-male.wav"))
        val assessment = ConfluencePersonalVoice.assess(source)
        assertTrue(assessment.usable, assessment.issues.toString())
        val prepared = ConfluencePersonalVoice.prepare(source)
        assertTrue(prepared.referenceHz in 120f..135f)
        assertContentEquals(source.samples, ConfluencePersonalVoice.original(prepared).samples)
        // This actual library selection contains weak low-frequency room/breath energy. It must
        // not be relabeled as an unsupported lower root without corroborating period evidence.
        val selected = Snip(source.samples.copyOfRange(22050, 88200), 1, Confluence.RATE)
        val selectedAssessment = ConfluencePersonalVoice.assess(selected)
        assertTrue(selectedAssessment.usable, selectedAssessment.issues.toString())
        assertTrue(assertNotNull(selectedAssessment.referenceHz) in 120f..135f)
        val selectedPrepared = ConfluencePersonalVoice.prepare(selected)
        assertContentEquals(selected.samples, ConfluencePersonalVoice.original(selectedPrepared).samples)
        val arpeggio = WavReader.read(File(directory, "airy-phrase-female.wav"))
        assertFalse(ConfluencePersonalVoice.assess(arpeggio).usable)
    }
}
