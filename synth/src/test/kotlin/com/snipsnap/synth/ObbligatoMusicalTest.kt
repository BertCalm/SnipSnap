package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Objective voicing and gesture checks; a listening verdict still belongs to the owner. */
class ObbligatoMusicalTest {
    private val controls get() = ObbligatoPresets.all().single { it.name == "Fight" }.macros

    private fun probe(macros: Map<String, Float> = controls, midi: Int = 60, model: Int = Obbligato.MUSICAL_MODEL) =
        Obbligato.probe(macros, midi = midi, model = model)

    private fun rms(samples: FloatArray, from: Double, until: Double): Double {
        val a = (from * Dsp.RATE).toInt().coerceAtLeast(0)
        val b = minOf(samples.size, (until * Dsp.RATE).toInt())
        require(b > a)
        var energy = 0.0
        for (i in a until b) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (b - a))
    }

    private fun cents(hz: Float, target: Float): Double = 1200.0 * ln(hz / target) / ln(2.0)

    /** Several body windows avoid judging one player's changing prominence as a spectrum. */
    private fun upperReedShare(samples: FloatArray): Double {
        val size = 8192
        var total = 0.0
        var upper = 0.0
        for (seconds in listOf(.35, .75, 1.15, 1.55, 1.95, 2.35, 2.75)) {
            val from = (seconds * Dsp.RATE).toInt()
            if (from + size > samples.size) continue
            val spectrum = Fft.magnitudeSpectrum(samples.copyOfRange(from, from + size), size)
            for (bin in 1 until spectrum.size) {
                val hz = Fft.binToHz(bin, size, Dsp.RATE)
                val power = spectrum[bin].toDouble() * spectrum[bin]
                total += power
                if (hz in 2000f..8000f) upper += power
            }
        }
        require(total > 1e-12) { "the reed fixture is silent" }
        return upper / total
    }

    @Test
    fun `the previous responsive version retains its original float audio and complete PCM exports`() {
        val stream = assertNotNull(javaClass.getResourceAsStream("/obbligato/model6-sha256.json"))
        val fixture = stream.bufferedReader().use { Json.parse(it.readText()).obj() }
        assertEquals("42f12776ebeb45b26ce670aa129604e110001085", fixture.getValue("originalCommit").str())
        val cases = fixture.getValue("cases").arr()
        assertEquals(3, cases.size)
        for (value in cases) {
            val case = value.obj()
            val patch = ObbligatoPatch.fromJsonValue(case.getValue("recipe"))
            assertEquals(6, patch.model)
            val report = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = patch.model)
            assertEquals(case.getValue("rawFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(report.snip.samples), "${patch.name}: prior duet changed")
            assertEquals(case.getValue("airFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(report.air), "${patch.name}: prior air changed")
            assertEquals(case.getValue("reedFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(report.reed), "${patch.name}: prior reed changed")
            assertEquals(case.getValue("finalPcm24WavSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.pcmHash(patch), "${patch.name}: prior MPC export changed")
        }
    }

    @Test
    fun `the gentler version is explicit and renders its saved recipe reproducibly`() {
        assertEquals(10, Obbligato.MODEL)
        assertEquals(6, Obbligato.EXPERIMENTAL_MODEL)
        assertTrue(ObbligatoPresets.all().all { it.model == 10 })
        assertTrue(ObbligatoKits.patches().all { it.model == 10 })
        val patch = ObbligatoPatch("Gentler Fight", controls, midi = 67, velocity = .47f, seed = -719,
            model = Obbligato.MUSICAL_MODEL)
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch, ObbligatoPatch.fromJsonText(patch.toJsonText()))
        val legacy = JsonValue.Obj(patch.toJsonValue().entries.toMutableMap().apply { remove("model") })
        assertEquals(5, ObbligatoPatch.fromJsonValue(legacy).model)
        assertEquals(patch.copy(macros = controls + ("HOLD" to 0f)), patch.withMacros(controls + ("HOLD" to 0f)))

        val raw = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = patch.model)
        val repeated = Obbligato.probe(patch.macros.entries.reversed().associate { it.key to it.value },
            patch.midi, patch.velocity, patch.seed, model = patch.model)
        assertContentEquals(raw.snip.samples, repeated.snip.samples, "recipe or map order changed the duet")
        assertContentEquals(raw.air, repeated.air, "the air player did not reproduce")
        assertContentEquals(raw.reed, repeated.reed, "the reed player did not reproduce")
        assertEquals(raw.interactionEvents, repeated.interactionEvents)
        val expectedProduction = raw.snip.samples.copyOf()
        Dsp.levelTo(expectedProduction, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        assertContentEquals(expectedProduction, patch.render().samples,
            "production rendering does not match the actual auditioned duet")
    }

    @Test
    fun `gentler notes keep both sources and continuous complete gestures across the playable octave`() {
        for (midi in listOf(60, 67, 72)) for (hold in listOf(0f, 1f)) {
            val settings = controls + mapOf("HOLD" to hold, "MISCHIEF" to if (hold == 0f) 0f else 1f)
            val report = probe(settings, midi)
            val output = Obbligato.render(settings, midi = midi, model = Obbligato.MUSICAL_MODEL)
            val label = "MIDI $midi HOLD $hold"
            assertEquals(1, output.channels)
            assertEquals(44_100, output.sampleRate)
            assertEquals(report.air.size, output.samples.size)
            assertEquals(report.reed.size, output.samples.size)
            assertTrue(output.samples.all { it.isFinite() && it in -1f..1f }, "$label output escaped its finite ceiling")
            assertTrue(abs(output.samples.average()) < .015, "$label output has excessive DC")
            assertEquals(0f, output.samples.last(), "$label finite note does not finish")
            assertTrue(report.air.all { it.isFinite() } && report.reed.all { it.isFinite() })
            assertTrue(report.interactionEvents.size >= 2, "$label no longer has an answering reed and an air counter")
            assertTrue(report.air.indices.all { abs(report.air[it] + report.reed[it] - report.snip.samples[it]) < 1e-6f },
                "$label source taps no longer represent the real duet")

            val held = output.durationSeconds.toDouble() - .75
            val air = ArrayList<Double>()
            val reed = ArrayList<Double>()
            val sum = ArrayList<Double>()
            var from = .4
            while (from + .1 <= held - .1) {
                air += rms(report.air, from, from + .1)
                reed += rms(report.reed, from, from + .1)
                sum += rms(report.snip.samples, from, from + .1)
                from += .05
            }
            for ((name, values) in listOf("air" to air, "reed" to reed)) {
                assertTrue(values.min() > 1e-4, "$label lost the $name source before leveling")
                assertTrue(values.min() >= values.max() * .12, "$label $name stopped speaking between turns")
            }
            // The controller's ledger cannot prove an audible turn. Require the actual
            // unnormalized source taps to exchange prominence by at least three dB.
            val dominance = sqrt(2.0)
            assertTrue(air.indices.any { air[it] >= reed[it] * dominance },
                "$label air never takes a materially louder turn in the actual duet")
            assertTrue(reed.indices.any { reed[it] >= air[it] * dominance },
                "$label reed never takes a materially louder turn in the actual duet")
            val separateEnergy = air.indices.map { sqrt(air[it] * air[it] + reed[it] * reed[it]) }
            assertTrue(separateEnergy.min() >= separateEnergy.max() * .50,
                "$label the two sources leave a gap between turns")
            assertTrue(sum.min() >= sum.max() * .20, "$label the summed duet has a pronounced cancellation gap")
        }
    }

    @Test
    fun `reed edge is reduced after level independent comparison while both roots remain clear`() {
        for (midi in listOf(60, 72)) {
            val previous = probe(midi = midi, model = Obbligato.EXPERIMENTAL_MODEL)
            val gentler = probe(midi = midi)
            val oldShare = upperReedShare(previous.reed)
            val newShare = upperReedShare(gentler.reed)
            assertTrue(oldShare > .01, "MIDI $midi reference no longer exposes the original reed edge")
            assertTrue(newShare < oldShare * .55,
                "MIDI $midi reed 2–8kHz power share did not materially decrease: $oldShare -> $newShare")

            fun estimate(samples: FloatArray) = assertNotNull(Pitch.detect(Snip(samples, 1, Dsp.RATE), .85f, .35f),
                "MIDI $midi player lost a periodic root")
            val oldAir = estimate(previous.air)
            val oldReed = estimate(previous.reed)
            val newAir = estimate(gentler.air)
            val newReed = estimate(gentler.reed)
            for ((name, pitch) in listOf("air" to newAir, "reed" to newReed)) {
                assertTrue(pitch.confidence >= .75f, "MIDI $midi $name root is ambiguous: $pitch")
                assertTrue(abs(cents(pitch.hz, Keys.midiHz(midi))) < 20.0,
                    "MIDI $midi $name no longer centres the requested note: $pitch")
            }
            val oldSeparation = abs(cents(oldAir.hz, oldReed.hz))
            val newSeparation = abs(cents(newAir.hz, newReed.hz))
            assertTrue(newSeparation < oldSeparation * .60,
                "MIDI $midi players still carry the original beating spread: $oldSeparation -> $newSeparation cents")
        }
    }

    @Test
    fun `gentler voicing retains causal answers to the physical other player`() {
        val original = probe()
        val firstReed = assertNotNull(original.interactionEvents.firstOrNull {
            it.leader == ObbligatoInteraction.Player.REED
        })
        val weakening = Obbligato.SourceWeakening(air = true,
            startSeconds = (firstReed.seconds - .18).coerceAtLeast(.2),
            endSeconds = firstReed.seconds + .22, strength = .05f)
        val changed = Obbligato.probeWithWeakening(controls, model = Obbligato.MUSICAL_MODEL, weakening = weakening)
        val changedEntry = changed.interactionEvents.firstOrNull { it.leader == ObbligatoInteraction.Player.REED }
        assertTrue(changedEntry == null || abs(changedEntry.seconds - firstReed.seconds) > .025,
            "the reed stayed on a fixed schedule after losing physical air activity")
        assertTrue(!original.reed.contentEquals(changed.reed), "the unweakened reed did not answer the changed player")

        val disabled = Obbligato.probe(controls, model = Obbligato.MUSICAL_MODEL, responses = false)
        val disabledChanged = Obbligato.probeWithWeakening(controls, model = Obbligato.MUSICAL_MODEL,
            responses = false, weakening = weakening)
        assertTrue(!disabled.air.contentEquals(disabledChanged.air), "the physical pressure intervention did nothing")
        assertContentEquals(disabled.reed, disabledChanged.reed,
            "the response-disabled survivor changed when only its opponent was physically weakened")
        assertTrue(disabled.interactionEvents.isEmpty() && disabledChanged.interactionEvents.isEmpty())
    }
}
