package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Palette regressions protect the published anchors and a few deliberately contrasting
 * musical roles. These are level-normalized magnitude/envelope checks, not PCM-distance
 * rankings or a claim that all 48 presets are perceptually distinct. Twelve engine renders.
 */
class StoopPresetPaletteTest {
    @Test
    fun `the six published anchors retain their complete saved recipes`() {
        val anchors = reference.getValue("anchors").arr()
        assertEquals(anchorNames, anchors.map { it.obj().getValue("name").str() }.toSet())
        assertEquals(6, anchors.size)
        for (recipe in anchors) {
            val expected = assertIs<StoopPatch>(StoopPatch.fromJsonValue(recipe))
            val actual = preset(expected.name)
            assertEquals(expected, actual, "${expected.name}: the published anchor recipe changed")
            assertEquals(recipe, actual.toJsonValue(), "${expected.name}: preserve every serialized field")
        }
    }

    @Test
    fun `all published names family counts roots and velocities remain comparable`() {
        val expected = reference.getValue("roster").arr().map { row ->
            val fields = row.obj()
            Identity(fields.getValue("name").str(), fields.getValue("voice").str(),
                fields.getValue("midi").int(), fields.getValue("velocity").num().toFloat())
        }
        val actual = StoopPresets.all().map { Identity(it.name, it.voiceName, it.midi, it.velocity) }
        assertEquals(48, actual.size)
        assertEquals(48, actual.map { it.name }.toSet().size)
        assertEquals(expected, actual, "keep the published roster order, roles, requested notes and strike energy")
        assertEquals(expected.groupingBy { it.voice }.eachCount(), actual.groupingBy { it.voice }.eachCount())
    }

    @Test
    fun `the harmonic probe ignores gain and phase while retaining known harmonic proportions`() {
        val rootHz = 200.0
        fun signal(gain: Double, phase: Double) = Snip(FloatArray((.4 * Stoop.RATE).toInt()) { frame ->
            (gain * (1..8).sumOf { harmonic ->
                sin(2 * PI * rootHz * harmonic * frame / Stoop.RATE + phase * harmonic * harmonic) / harmonic
            }).toFloat()
        }, channels = 1, sampleRate = Stoop.RATE)
        val expectedRootShare = 1.0 / (1..8).sumOf { 1.0 / (it * it) }
        val random = Random(127)
        val noise = Snip(FloatArray((.4 * Stoop.RATE).toInt()) { random.nextFloat() * 2 - 1 }, channels = 1, sampleRate = Stoop.RATE)
        for ((from, until) in listOf(0.0 to .06, .15 to .35)) {
            val original = harmonicProfile(signal(1.0, 0.0), rootHz, from, until)
            val changedPhaseAndGain = harmonicProfile(signal(.07, .73), rootHz, from, until)
            assertEquals(expectedRootShare, original.rootShare, .005)
            assertEquals(original.upperShare, changedPhaseAndGain.upperShare, .002)
            assertEquals(original.evenOverOdd, changedPhaseAndGain.evenOverOdd, .002)
            assertTrue(original.capture > .90 && changedPhaseAndGain.capture > .90,
                "a stationary eight-harmonic source should be captured by the root-aligned bands")
            assertTrue(harmonicProfile(noise, rootHz, from, until).capture < .5,
                "broadband noise must not qualify as a pitched harmonic registration")
        }
    }

    @Test
    fun `contrasting bass keys clav and organ registrations separate in rendered harmonic balance`() {
        val bass = dryPair("Round Corner", "Nose Bass")
        val bassProfiles = bass.map { bodyProfile(it) }
        assertUpperContrast("rounded bass / nasal bass", bassProfiles, .10)

        val tines = dryPair("Dust Tines", "Clean Contact")
        val tineProfiles = tines.map { bodyProfile(it) }
        assertUpperContrast("warm tine / hard contact", tineProfiles, .10)

        val clavs = dryPair("Soft Clav", "Short Wire")
        // Clav's contact lives in the onset; its later body intentionally becomes rounded.
        val clavProfiles = clavs.map { harmonicProfile(it.snip, Keys.midiHz(it.patch.midi).toDouble(), 0.0, .06) }
        // The calibrated first-60-ms contrast is about 9.9 percentage points;
        // eight leaves a useful regression margin without tuning a recipe to a decimal threshold.
        assertUpperContrast("muted clav / bright short pick", clavProfiles, .08)

        val organs = dryPair("Small Pipes", "Bright Loft")
        val organProfiles = organs.map { bodyProfile(it) }
        val hollow = organProfiles[0]
        val bright = organProfiles[1]
        println("STOOP organ odd/even roles: hollow $hollow; bright $bright")
        assertTrue(bright.evenOverOdd >= hollow.evenOverOdd * 2,
            "the bright registration must have a stronger even/odd balance than the hollow pipes: $organProfiles")
        assertTrue(bright.evenShare - hollow.evenShare >= .08,
            "hollow pipes and bright organ need a meaningful even-harmonic balance difference: $organProfiles")

        for (rendered in bass + tines + clavs + organs) {
            val profile = bodyProfile(rendered)
            assertTrue(profile.rootShare >= .08, "${rendered.patch.name}: upper color must retain a meaningful root: $profile")
            assertTrue(profile.capture >= .85, "${rendered.patch.name}: broadband/in-between energy cannot substitute for pitched harmonics: $profile")
            val root = Keys.midiHz(rendered.patch.midi)
            val cents = FineTuning.cents(FineTuning.measuredHz(rendered.snip, root, .06f, .18f), root.toDouble())
            assertTrue(abs(cents) <= 15, "${rendered.patch.name}: requested root drifted $cents cents")
        }
    }

    @Test
    fun `the whistle sustains and the cinema chord clips without relying on gain or replies`() {
        val leads = dryPair("Nose Talk", "Pocket Whistle")
        val leadEnvelopes = leads.map { envelope(it.snip) }
        println("STOOP lead role envelopes: $leadEnvelopes")
        assertTrue(leadEnvelopes[1].energyMedianSeconds - leadEnvelopes[0].energyMedianSeconds >= .10,
            "the sustained whistle must carry energy later than the conversational accent: $leadEnvelopes")
        assertTrue(leadEnvelopes[1].activeEndSeconds - leadEnvelopes[0].activeEndSeconds >= .20,
            "the whistle needs an audible longer tail, independent of exported buffer length: $leadEnvelopes")

        val chords = dryPair("Velvet Stack", "Small Cinema")
        val chordEnvelopes = chords.map { envelope(it.snip) }
        println("STOOP chord role envelopes: $chordEnvelopes")
        assertTrue(chordEnvelopes[0].energyMedianSeconds - chordEnvelopes[1].energyMedianSeconds >= .07,
            "the soft block must retain energy later than the clipped cinema stab: $chordEnvelopes")
        assertTrue(chordEnvelopes[0].activeEndSeconds - chordEnvelopes[1].activeEndSeconds >= .20,
            "the clipped chord must end audibly earlier, rather than just use a shorter silent buffer: $chordEnvelopes")
    }

    private fun dryPair(first: String, second: String): List<Rendered> {
        val patches = listOf(preset(first), preset(second))
        assertEquals(patches[0].voice, patches[1].voice)
        assertEquals(patches[0].midi, patches[1].midi, "compare roles at the same authored root")
        assertEquals(patches[0].velocity, patches[1].velocity, "compare roles at the same strike energy")
        return patches.map { patch ->
            // Isolate the authored main gesture. A shadow or extra wear must not prove that
            // two main registrations differ, and a settled HOLD buffer has no attack/decay.
            val dry = patch.copy(macros = patch.macros + mapOf("SHADOW" to 0f, "WEAR" to 0f, "HOLD" to 0f))
            Rendered(dry, dry.render())
        }
    }

    private fun bodyProfile(rendered: Rendered) =
        harmonicProfile(rendered.snip, Keys.midiHz(rendered.patch.midi).toDouble(), .15, .35)

    private fun assertUpperContrast(role: String, pair: List<HarmonicProfile>, minimumDifference: Double) {
        println("STOOP $role harmonic balance: $pair")
        assertTrue(pair[1].upperShare - pair[0].upperShare >= minimumDifference,
            "$role needs at least ${minimumDifference * 100} percentage points more H2–H8 power in the bright role: $pair")
    }

    /**
     * Blackman-Harris magnitude-squared bins integrated around H1–H8, then normalized
     * by their combined power. Excluding the sub prevents sub-volume changes from proving
     * upper-source contrast. Capture checks their share of all power from .75 to 8.5 roots.
     */
    private fun harmonicProfile(snip: Snip, rootHz: Double, fromSeconds: Double, untilSeconds: Double): HarmonicProfile {
        val from = (fromSeconds * snip.sampleRate).toInt()
        val until = minOf(snip.samples.size, (untilSeconds * snip.sampleRate).toInt())
        val count = until - from
        require(count >= 128) { "insufficient audio for the requested harmonic window" }
        var fftSize = 32768
        while (fftSize < count) fftSize *= 2
        val real = FloatArray(fftSize)
        val imaginary = FloatArray(fftSize)
        for (i in 0 until count) {
            val angle = 2 * PI * i / (count - 1)
            val window = .35875 - .48829 * cos(angle) + .14128 * cos(2 * angle) - .01168 * cos(3 * angle)
            real[i] = (snip.samples[from + i] * window).toFloat()
        }
        Fft.forward(real, imaginary)
        val halfWidth = minOf(.35 * rootHz, maxOf(.10 * rootHz, 1.5 * snip.sampleRate / count))
        val harmonics = DoubleArray(8)
        var bandPower = 0.0
        for (bin in 1 until fftSize / 2) {
            val hz = bin.toDouble() * snip.sampleRate / fftSize
            if (hz !in .75 * rootHz..8.5 * rootHz) continue
            val power = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
            bandPower += power
            for (h in 1..8) if (abs(hz - h * rootHz) <= halfWidth) harmonics[h - 1] += power
        }
        val total = harmonics.sum()
        require(total > 1e-20 && bandPower > 1e-20) { "silent harmonic analysis window" }
        val even = harmonics.indices.filter { (it + 1) % 2 == 0 }.sumOf { harmonics[it] }
        val odd = total - even
        return HarmonicProfile(harmonics[0] / total, (total - harmonics[0]) / total,
            even / total, even / odd.coerceAtLeast(1e-30), total / bandPower)
    }

    /** Real-time 30-ms RMS/10-ms hops, peak-normalized; silence past the tail earns no duration. */
    private fun envelope(snip: Snip): Envelope {
        val cumulative = DoubleArray(snip.samples.size + 1)
        for (i in snip.samples.indices) cumulative[i + 1] = cumulative[i] + snip.samples[i].toDouble() * snip.samples[i]
        val total = cumulative.last()
        require(total > 1e-20) { "silent envelope" }
        val median = cumulative.indexOfFirst { it >= total * .5 }.toDouble() / snip.sampleRate
        val window = (snip.sampleRate * .03).toInt()
        val hop = (snip.sampleRate * .01).toInt()
        val windows = (0 until snip.samples.size step hop).map { start ->
            val end = minOf(start + window, snip.samples.size)
            (start + end).toDouble() / (2 * snip.sampleRate) to sqrt((cumulative[end] - cumulative[start]) / (end - start))
        }
        val peak = windows.maxOf { it.second }
        val lastActive = windows.last { it.second >= peak * .01 }.first
        return Envelope(median, lastActive)
    }

    private fun preset(name: String) = StoopPresets.all().single { it.name == name }

    private data class Identity(val name: String, val voice: String, val midi: Int, val velocity: Float)
    private data class Rendered(val patch: StoopPatch, val snip: Snip)
    private data class HarmonicProfile(
        val rootShare: Double, val upperShare: Double, val evenShare: Double,
        val evenOverOdd: Double, val capture: Double,
    )
    private data class Envelope(val energyMedianSeconds: Double, val activeEndSeconds: Double)

    private companion object {
        val anchorNames = setOf("Round Corner", "Dust Tines", "Soft Clav", "Amber Organ", "Nose Talk", "Velvet Stack")
        val reference: Map<String, JsonValue> by lazy {
            val text = requireNotNull(StoopPresetPaletteTest::class.java.getResourceAsStream("/stoop-palette-reference.json")) {
                "missing the published STOOP palette reference fixture"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
            Json.parse(text).obj()
        }
    }
}
