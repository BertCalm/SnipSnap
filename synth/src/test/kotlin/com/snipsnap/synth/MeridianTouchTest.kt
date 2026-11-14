package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Touch shaping is opt-in. With no shaping, or at full velocity, every factor is
 * exactly 1.0, so every saved recipe, factory preset and hard layer renders the
 * same bytes as before it existed. The pinned renders in `MeridianGesturesTest`,
 * `MeridianSixVoiceCompatibilityTest` and the other Meridian tests run unchanged;
 * `MeridianSixLayerHashTest` checks the MERIDIAN SIX layers against the pre-shaping base.
 */
class MeridianTouchTest {
    private val velocities = (0..100).map { it / 100.0 } + listOf(.30f.toDouble(), .65f.toDouble())
    private val shapings = (0..20).map { it / 20.0 } + listOf(.5f.toDouble(), 1f.toDouble())

    @Test fun `every factor is exactly one with no shaping or at full velocity`() {
        for (v in velocities) for (k in 0 until Meridian.MODES_PER_SURFACE) {
            assertTrue(MeridianTouch.widthMul(v, 0.0) == 1.0 && MeridianTouch.cutoffMul(v, 0.0) == 1.0 &&
                MeridianTouch.modeTilt(v, 0.0, k) == 1.0 && MeridianTouch.harmonicMul(v, 0.0) == 1.0, "velocity $v")
        }
        for (s in shapings) for (k in 0 until Meridian.MODES_PER_SURFACE) {
            assertTrue(MeridianTouch.widthMul(1.0, s) == 1.0 && MeridianTouch.cutoffMul(1.0, s) == 1.0 &&
                MeridianTouch.modeTilt(1.0, s, k) == 1.0 && MeridianTouch.harmonicMul(1.0, s) == 1.0, "shaping $s")
        }
    }

    @Test fun `shaping makes a soft strike broader and darker and never runs out of range`() {
        for (s in shapings.filter { it > 0 }) for (v in velocities.filter { it < 1 }) {
            assertTrue(MeridianTouch.widthMul(v, s) > 1.0)
            assertTrue(MeridianTouch.cutoffMul(v, s) in .15..1.0 && MeridianTouch.cutoffMul(v, s) < 1.0)
            assertTrue(MeridianTouch.harmonicMul(v, s) in 0.0..1.0 && MeridianTouch.harmonicMul(v, s) < 1.0)
            assertEquals(1.0, MeridianTouch.modeTilt(v, s, 0))
            for (k in 1 until Meridian.MODES_PER_SURFACE)
                assertTrue(MeridianTouch.modeTilt(v, s, k) < MeridianTouch.modeTilt(v, s, k - 1))
        }
    }

    @Test fun `full velocity renders are byte identical whatever the shaping, for all six voices`() {
        val byVoice = MeridianSixKits.factoriesV2().groupBy { it.voice }.values.map { it.first() }
        assertEquals(6, byVoice.size)
        for (patch in byVoice) {
            val plain = Meridian.render(patch.voice, patch.macros, patch.midi, 1f, patch.seedContext, patch.model)
            for (s in listOf(.5f, 1f)) {
                val shaped = Meridian.render(patch.voice, patch.macros, patch.midi, 1f, patch.seedContext, patch.model, s)
                assertContentEquals(plain.samples, shaped.samples, "${patch.name} shaping $s")
            }
        }
    }

    @Test fun `an unshaped patch keeps its JSON and its render`() {
        val patch = MeridianSixKits.factoriesV2().first { it.voice == MeridianVoice.CHIME }.copy(velocity = .30f)
        assertEquals(0f, patch.touchShaping)
        assertFalse("touchShaping" in patch.toJsonText())
        for (preset in MeridianPresets.all()) assertFalse("touchShaping" in preset.toJsonText(), preset.name)
        assertContentEquals(Meridian.render(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seedContext, patch.model).samples,
            patch.render().samples)
        assertEquals(patch, MeridianPatch.fromJsonText(patch.toJsonText()))
    }

    @Test fun `a shaped patch saves its amount, renders it and changes the soft strike`() {
        val plain = MeridianSixKits.factoriesV2().first { it.voice == MeridianVoice.DRAW }.copy(velocity = .30f)
        val shaped = plain.copy(touchShaping = .5f)
        assertEquals(.5, shaped.toJsonValue().entries.getValue("touchShaping").num())
        assertEquals(shaped, MeridianPatch.fromJsonText(shaped.toJsonText()))
        val audio = shaped.render().samples
        assertContentEquals(Meridian.render(plain.voice, plain.macros, plain.midi, plain.velocity, plain.seedContext, plain.model, .5f).samples, audio)
        assertFalse(plain.render().samples.contentEquals(audio))
    }

    @Test fun `touch shaping is finite only and within zero to one`() {
        val finite = MeridianSixKits.factoriesV2().first()
        assertFailsWith<IllegalArgumentException> { finite.copy(touchShaping = 1.01f) }
        assertFailsWith<IllegalArgumentException> { finite.copy(touchShaping = -.1f) }
        assertFailsWith<IllegalArgumentException> { finite.copy(touchShaping = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { finite.copy(model = 2, touchShaping = .5f, macros = finite.macros + ("HOLD" to 1f)) }
        assertFailsWith<IllegalArgumentException> { Meridian.render(MeridianVoice.SWEEP, mapOf("HOLD" to 1f), model = 2, touchShaping = .5f) }
        fun withFields(patch: MeridianPatch, vararg fields: Pair<String, JsonValue>): JsonValue =
            JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also { it.putAll(fields) })
        assertFailsWith<JsonException> { MeridianPatch.fromJsonValue(withFields(finite, "touchShaping" to JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { MeridianPatch.fromJsonValue(withFields(finite, "touchShaping" to JsonValue.Num(-1.0))) }
        val held = finite.copy(model = 2, macros = finite.macros + ("HOLD" to 1f))
        assertEquals(held, MeridianPatch.fromJsonValue(withFields(held, "touchShaping" to JsonValue.Num(0.0))))
        assertFailsWith<JsonException> { MeridianPatch.fromJsonValue(withFields(held, "touchShaping" to JsonValue.Num(.5))) }
    }
}
