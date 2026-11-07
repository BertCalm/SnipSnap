package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Range and playback are saved contracts, independent of the currently selected factory sound. */
class ObbligatoPerformanceTest {
    private val relationships = listOf(ObbligatoRelationship.HARMONY, ObbligatoRelationship.PLAY,
        ObbligatoRelationship.FIGHT, ObbligatoRelationship.PROTECT)

    private fun rms(samples: FloatArray, from: Double, until: Double): Double {
        val first = (from * Dsp.RATE).toInt().coerceAtLeast(0)
        val last = (until * Dsp.RATE).toInt().coerceAtMost(samples.size)
        require(last > first)
        return sqrt((first until last).sumOf { samples[it].toDouble() * samples[it] } / (last - first))
    }

    private fun onset(samples: FloatArray, body: Double): Double {
        var time = 0.0
        while (time + .05 <= 2.0) {
            if (rms(samples, time, time + .05) >= maxOf(1e-5, body * .10)) return time
            time += .025
        }
        return Double.POSITIVE_INFINITY
    }

    @Test
    fun `old finite JSON bytes and note limits survive beside the expanded renderer`() {
        val resources = listOf("models1-5-sha256.json", "model6-sha256.json", "model7-sha256.json",
            "model8-sha256.json", "model9-sha256.json", "model10-sha256.json")
        var recipes = 0
        for (resource in resources) {
            val fixture = assertNotNull(javaClass.getResourceAsStream("/obbligato/$resource"))
                .bufferedReader().use { Json.parse(it.readText()).obj() }
            for (value in fixture.getValue("cases").arr()) {
                val recorded = value.obj().getValue("recipe")
                val patch = ObbligatoPatch.fromJsonValue(recorded)
                assertEquals(Json.write(recorded), patch.toJsonText(), "${patch.name}: old finite JSON changed")
                assertEquals(ObbligatoPlayback.FINITE, patch.playback)
                assertFalse("playback" in patch.toJsonValue().entries)
                recipes++
            }
        }
        assertEquals(53, recipes)
        assertEquals(60..72, Obbligato.MIDI_MIN..Obbligato.MIDI_MAX)
        for (model in 1..10) {
            assertEquals(60..72, Obbligato.midiRange(model))
            for (midi in listOf(48, 59, 73, 84)) {
                val error = assertFailsWith<IllegalArgumentException> {
                    ObbligatoPatch("Old range", Obbligato.defaults(), midi = midi, model = model)
                }
                assertTrue(error.message.orEmpty().contains("60..72"), "old range refusal is not explained")
            }
        }
        assertEquals(48..84, Obbligato.midiRange(Obbligato.PERFORMANCE_MODEL))
        for (midi in listOf(48, 84))
            assertEquals(midi, ObbligatoPatch("Expanded endpoint", Obbligato.defaults(), midi = midi).midi)
        for (midi in listOf(47, 85)) assertFailsWith<IllegalArgumentException> {
            ObbligatoPatch("Outside expanded range", Obbligato.defaults(), midi = midi)
        }
    }

    @Test
    fun `held articulation is explicit and complete while an old HOLD one recipe remains finite`() {
        for (model in listOf(5, 10, 11)) {
            val phrase = ObbligatoPatch("Long finite phrase", Obbligato.defaults() + ("HOLD" to 1f), model = model)
            assertEquals(ObbligatoPlayback.FINITE, phrase.playback)
            assertEquals(phrase, Patches.fromJsonText(phrase.toJsonText()))
            assertFalse("playback" in phrase.toJsonValue().entries)
        }
        val held = ObbligatoPatch("Held low answer", Obbligato.defaults() + mapOf(
            "BOND" to ObbligatoRelationship.PLAY.bond, "HOLD" to 1f), midi = 48,
            velocity = .37f, seed = -731, playback = ObbligatoPlayback.HELD, releaseSeconds = .43f)
        assertEquals(11, held.model)
        assertEquals(1, held.heldVersion)
        assertEquals(held, Patches.fromJsonText(held.toJsonText()))
        assertEquals(held, PadRecipe.fromJsonText(PadRecipe(held).toJsonText()).patch)
        val edited = assertIs<ObbligatoPatch>(Patches.edited(held, "Edited held answer",
            held.macros + ("MISCHIEF" to .31f)))
        assertEquals(held.copy(name = "Edited held answer", macros = held.macros + ("MISCHIEF" to .31f)), edited)
        assertFailsWith<IllegalArgumentException> { held.copy(model = 10, midi = 60) }
        assertFailsWith<IllegalArgumentException> { held.copy(macros = held.macros + ("HOLD" to .8f)) }
        assertFailsWith<IllegalArgumentException> { held.copy(heldVersion = 2) }
        for (release in listOf(.09f, 1.51f, Float.NaN))
            assertFailsWith<IllegalArgumentException> { held.copy(releaseSeconds = release) }
        for (field in listOf("playback", "releaseSeconds", "heldVersion")) {
            val malformed = JsonValue.Obj(LinkedHashMap(held.toJsonValue().entries).apply { this[field] = JsonValue.Null })
            val error = assertFailsWith<JsonException> { ObbligatoPatch.fromJsonValue(malformed) }
            assertTrue(!error.message.isNullOrBlank(), "$field has no useful malformed-recipe diagnostic")
        }
    }

    @Test
    fun `expanded finite notes retain both captured roots prompt entries and complete endings`() {
        // Short phrases are the difficult low-register case: the reed must establish a
        // voiced body before the two-second held segment finishes, including in Protect.
        val cells = relationships.flatMap { relationship ->
            listOf(48, 60, 72, 84).map { Triple(relationship, it, .6f) } +
                listOf(48, 84).map { Triple(relationship, it, .3f) }
        }
        for ((relationship, midi, velocity) in cells) {
            val macros = Obbligato.defaults() + mapOf("BOND" to relationship.bond, "HOLD" to 0f)
            val report = Obbligato.probe(macros, midi, velocity, model = Obbligato.PERFORMANCE_MODEL)
            val label = "$relationship MIDI $midi velocity $velocity"
            assertEquals(1, report.snip.channels)
            assertEquals(44_100, report.snip.sampleRate)
            assertTrue(report.snip.samples.all { it.isFinite() }, "$label non-finite output")
            assertTrue(abs(report.snip.samples.average()) < .015, "$label excessive DC")
            assertEquals(0f, report.snip.samples.last(), "$label incomplete finite ending")
            assertTrue(report.air.indices.all {
                abs(report.air[it] + report.reed[it] - report.snip.samples[it]) < 1e-6f
            }, "$label actual sources no longer reconstruct the duet")
            for ((name, samples) in listOf("air" to report.air, "reed" to report.reed)) {
                val body = rms(samples, 1.35, 1.85)
                assertTrue(body > 1e-4, "$label $name never establishes a useful body")
                val pitch = assertNotNull(Pitch.detect(Snip(samples, 1, Dsp.RATE), 1.25f, .45f),
                    "$label $name has no captured root")
                val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(midi)) / ln(2.0)
                assertTrue(pitch.confidence >= .65f && abs(cents) < 35.0,
                    "$label $name loses root: $cents cents, confidence ${pitch.confidence}")
                val limit = if (name == "air") .9 else if (relationship == ObbligatoRelationship.PROTECT) 1.6 else 1.1
                assertTrue(onset(samples, body) <= limit, "$label $name arrives too late for the short phrase")
            }
            if (relationship == ObbligatoRelationship.PROTECT) {
                val air = rms(report.air, .25, .60)
                assertTrue(air > 1e-4 && rms(report.reed, .25, .60) < air * .01,
                    "$label the exposed flute opening has been obscured")
            }
        }
    }
}
