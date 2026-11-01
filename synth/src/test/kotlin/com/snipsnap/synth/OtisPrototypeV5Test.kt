package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OtisPrototypeV5Test {
    @Test
    fun `all twenty nine published model four source files keep their exact PCM`() {
        val fixtures = Json.parse(javaClass.getResource("/otis-model4-replay.json")!!.readText())
            .obj().getValue("fixtures").arr()
        assertEquals(29, fixtures.size)
        for (fixture in fixtures.map { it.obj() }) {
            val request = OtisPrototypeRecipe.fromJsonValue(fixture.getValue("recipe"))
            assertEquals(4, request.model)
            val source = request.render()
            val gain = fixture.getValue("rawPcmGain").num().toFloat()
            val exported = Snip(FloatArray(source.samples.size) { source.samples[it] * gain }, 1, Dsp.RATE)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, exported) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(fixture.getValue("wavSha256").str(), hash, fixture.getValue("id").str())
        }
    }

    @Test
    fun `model five leaves Organ exactly on its technical baseline`() {
        val request = OtisPrototypeRequest(OtisPrototypeVoice.ORGAN,
            listOf(OtisPrototypeNote(48, gateSeconds = .3), OtisPrototypeNote(67, .1, .25)),
            organ = OtisOrganSettings(rotary = true), model = 5)
        assertContentEquals(request.copy(model = 4).render().samples, request.render().samples)
    }

    @Test
    fun `pressure strength is a strict saved model five quantity and old schemas stay unchanged`() {
        for (strength in listOf(0f, .35f, .85f, 1f)) {
            val request = OtisPrototypeRequest(OtisPrototypeVoice.SQUEEZE, model = 5, sharedPressure = strength)
            assertEquals(request, OtisPrototypeRecipe.fromJsonText(OtisPrototypeRecipe.toJsonText(request)))
        }
        for (model in 1..4) {
            val value = OtisPrototypeRecipe.toJsonValue(OtisPrototypeRequest(OtisPrototypeVoice.STEAM, model = model))
            assertTrue("sharedPressure" !in value.entries)
            assertFailsWith<JsonException> {
                OtisPrototypeRecipe.fromJsonValue(JsonValue.Obj(value.entries + ("sharedPressure" to JsonValue.Num(.85))))
            }
        }
        val value = OtisPrototypeRecipe.toJsonValue(OtisPrototypeRequest(OtisPrototypeVoice.STEAM, model = 5))
        for (number in listOf(-.1, 1.00000001, Double.NaN)) assertFailsWith<JsonException> {
            OtisPrototypeRecipe.fromJsonValue(JsonValue.Obj(value.entries + ("sharedPressure" to JsonValue.Num(number))))
        }
        assertFailsWith<JsonException> { OtisPrototypeRecipe.fromJsonValue(JsonValue.Obj(value.entries - "sharedPressure")) }
    }

    @Test
    fun `a single reference key is an exact shared bypass and future keys cannot alter its past`() {
        for (voice in listOf(OtisPrototypeVoice.STEAM, OtisPrototypeVoice.HARP, OtisPrototypeVoice.SQUEEZE)) {
            val held = OtisPrototypeNote(60, gateSeconds = .9)
            val independent = OtisPrototypeRequest(voice, listOf(held), model = 5, sharedPressure = 0f)
            val shared = independent.copy(sharedPressure = .85f)
            assertContentEquals(independent.render().samples, shared.render().samples, "$voice single key changed")
            val phrase = shared.copy(notes = listOf(held, OtisPrototypeNote(63, .45, .2)))
            val first = shared.render().samples
            val second = phrase.render().samples
            assertContentEquals(first.copyOf((.44 * Dsp.RATE).toInt()), second.copyOf((.44 * Dsp.RATE).toInt()),
                "$voice looked ahead to the second note")
            assertTrue(second.indices.any { it < first.size && second[it] != first[it] }, "$voice had no interaction")
        }
    }

    @Test
    fun `controller draw and recovery are bounded and use all active keys`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val notes = OtisPhrasePressureStudy.notes()
        for (voice in listOf(OtisPrototypeVoice.STEAM, OtisPrototypeVoice.HARP, OtisPrototypeVoice.SQUEEZE)) {
            val settings = OtisAirSettings()
            val frames = OtisAirCharacterV5.frameCount(voice, notes, settings, rate)
            val trace = OtisAirCharacterV5.trace(voice, notes, settings, frames, .85f, rate)
            assertTrue(trace.pressure.all { it in .4..1.0 })
            assertEquals(1.0, trace.pressureAt(.4))
            assertTrue(trace.pressureAt(.9) < .78, "$voice did not share demand")
            assertTrue(trace.pressureAt(1.6) > .96, "$voice did not recover after the second key")
            val bypass = OtisAirCharacterV5.trace(voice, notes, settings, frames, 0f, rate)
            assertTrue(bypass.pressure.all { it == 1.0 })
            val many = OtisAirCharacterV5.trace(voice, List(32) { held -> OtisPrototypeNote(36 + held, gateSeconds = .5) },
                settings, rate, 1f, rate)
            assertTrue(many.pressure.all { it in .4..1.0 })
            assertTrue(many.pressure.min() < .401, "stress score did not reach the floor")
        }
    }
}
