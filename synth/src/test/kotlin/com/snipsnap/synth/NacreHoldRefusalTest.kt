package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HOLD is real only on GROTTO's saved model 8. Every finite model, 1 to 7, still refuses a nonzero HOLD at every door:
 * the renderer, the saved-model router, the patch and the JSON decoder. Reverting any one guard fails this test.
 */
class NacreHoldRefusalTest {
    private fun voiceFor(model: Int): NacreVoice = NacreVoice.entries.first { NacreModels.supports(it, model) }

    @Test
    fun `every finite model refuses a nonzero HOLD everywhere`() {
        for (model in 1..7) {
            assertFalse(NacreModels.supportsHold(model))
            val voice = voiceFor(model)
            for (hold in listOf(.5f, .99f, 1f)) {
                val macros = mapOf("HOLD" to hold)
                assertFailsWith<IllegalArgumentException>("model $model render") { NacreModels.render(voice, macros, model = model) }
                assertFailsWith<IllegalArgumentException>("model $model patch") { NacrePatch("x", voice, macros, model = model) }
                val json = LinkedHashMap(NacrePatch("x", voice, emptyMap(), model = model).toJsonValue().entries)
                json["macros"] = JsonValue.Obj(linkedMapOf("HOLD" to JsonValue.Num(hold.toDouble())))
                assertFailsWith<JsonException>("model $model JSON") { NacrePatch.fromJsonValue(JsonValue.Obj(json)) }
                if (model == Nacre.defaultModel(voice)) {
                    assertFailsWith<IllegalArgumentException>("$voice render") { Nacre.render(voice, macros) }
                }
            }
            assertEquals(Nacre.drumClassFor(voice, mapOf("HOLD" to 1f)), com.snipsnap.audio.DrumClass.TONAL)
            assertFalse(Nacre.isLoop(voice, mapOf("HOLD" to 1f)))
        }
    }

    @Test
    fun `GROTTO saves HOLD on model 8 only, and its held recipe replays exactly`() {
        assertTrue(NacreModels.supportsHold(8))
        assertEquals(8, Nacre.defaultModel(NacreVoice.GROTTO))
        for (model in 1..8) assertEquals(model == 8, NacreModels.supports(NacreVoice.GROTTO, model), "GROTTO model $model")
        for (voice in NacreVoice.entries - NacreVoice.GROTTO) assertFalse(NacreModels.supports(voice, 8), "$voice model 8")
        assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.BUBBLE, emptyMap(), model = 8) }
        assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.GROTTO, emptyMap(), model = 7) }
        val patch = NacrePatch("Held", NacreVoice.GROTTO, NacreGrotto.defaults() + ("HOLD" to 1f), midi = 43, velocity = .7f, seed = -5L)
        assertTrue(Nacre.isLoop(patch.voice, patch.macros))
        val replayed = NacrePatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, replayed)
        val direct = NacreGrotto.render(patch.macros, 43, .7f, -5L).samples
        kotlin.test.assertContentEquals(direct, replayed.render().samples)
        kotlin.test.assertContentEquals(direct, NacreModels.render(NacreVoice.GROTTO, patch.macros, 43, .7f, -5L, 8).samples)
        // A GROTTO recipe outside C2 to C4 is refused in JSON as in code.
        val low = LinkedHashMap(patch.toJsonValue().entries).apply { put("midi", JsonValue.Num(30.0)) }
        assertFailsWith<IllegalArgumentException> { NacrePatch.fromJsonValue(JsonValue.Obj(low)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("CONTACT" to .3f)) }
    }
}
