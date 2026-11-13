package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Policy v2 (strike shaping) beside the unchanged v1. Hard stays the accepted v1 source bit for bit;
 * Soft and Medium carry a broader, darker contact. Measured floors sit slightly under the numbers
 * recorded in docs/NACRE_CONTACT_NATIVE_VELOCITY.md; listening acceptance is a separate gate.
 */
class NacreContactStrikeRecipeTest {
    private fun hard(contact: Float = .35f) = NacrePresets.all().single { it.model == 4 && it.macros["CONTACT"] == contact }
    private fun bank(variant: NacreStrikeVariant = NacreStrikeVariant.MODERATE, hard: NacrePatch = hard()) =
        NacreContactStrikeRecipes.recipeFor(hard, variant)
    private fun bytes(samples: FloatArray): ByteArray = ByteBuffer.allocate(samples.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putInt(it.toRawBits()) } }.array()
    private fun hash(samples: FloatArray): String = MessageDigest.getInstance("SHA-256").digest(bytes(samples))
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    private fun changedLayer(value: JsonValue, index: Int, edit: (Map<String, JsonValue>) -> Map<String, JsonValue>): JsonValue {
        val obj = value.obj()
        return JsonValue.Obj(obj + ("layers" to JsonValue.Arr(obj.getValue("layers").arr().mapIndexed { n, layer ->
            if (n == index) JsonValue.Obj(edit(layer.obj())) else layer
        })))
    }
    private fun changedStrike(value: JsonValue, index: Int, edit: (Map<String, JsonValue>) -> Map<String, JsonValue>) =
        changedLayer(value, index) { it + ("strike" to JsonValue.Obj(edit(it.getValue("strike").obj()))) }

    /** Accepted v1 Hard witnesses (NacreContactVelocityRecipeTest) and v1 Soft/Medium Float hashes read at f3df719d. */
    private val v1Hashes = mapOf(
        .35f to listOf(V1_SOFT_035, V1_MEDIUM_035, "65403955e4250f620f05a2b33ea56093a58e685743da3680001bd8ae38ccb018"),
        .8f to listOf(V1_SOFT_080, V1_MEDIUM_080, "48304c74c17949b3ea375803d2cec3bd11220f7edc93ed9ed167554ca01ef73c"),
    )

    @Test
    fun variantsAreFixedOrderedTablesWithADefaultHard() {
        val default = NacreStrikeShape.DEFAULT
        assertEquals(NacreStrikeShape(0.0, 1.0, 0.0), default)
        for (variant in NacreStrikeVariant.entries) {
            assertEquals(default, variant.shapes.last())
            // Softer layers press longer, pick darker and carry more pitched tone than the layer above.
            for ((soft, louder) in variant.shapes.zipWithNext()) {
                assertTrue(soft.widthMs > louder.widthMs && soft.pickBrightness < louder.pickBrightness &&
                    soft.toneShift > louder.toneShift, "$variant is not ordered soft to hard")
            }
        }
        val a = NacreStrikeVariant.MODERATE; val b = NacreStrikeVariant.STRONG
        for ((sa, sb) in a.shapes.zip(b.shapes).dropLast(1)) {
            assertTrue(sa.widthMs < sb.widthMs && sa.pickBrightness > sb.pickBrightness && sa.toneShift < sb.toneShift)
        }
        assertEquals(listOf("A", "B"), NacreStrikeVariant.entries.map { it.id })
        for (bad in listOf(Double.NaN, -.1, NacreStrikeShape.MAX_WIDTH_MS + .1)) assertFails { NacreStrikeShape(bad, 1.0, 0.0) }
        for (bad in listOf(Double.NaN, 0.0, 1.01)) assertFails { NacreStrikeShape(0.0, bad, 0.0) }
        for (bad in listOf(Double.NaN, -.01, NacreStrikeShape.MAX_TONE_SHIFT + .01)) assertFails { NacreStrikeShape(0.0, 1.0, bad) }
    }

    @Test
    fun v2RoundTripsAndStaysSeparateFromV1() {
        for (variant in NacreStrikeVariant.entries) {
            val source = hard().copy(name = "Strike saved", midi = 60, seed = Long.MIN_VALUE + 5)
            val bank = bank(variant, source)
            val restored = NacreContactStrikeRecipe.fromJsonText(bank.toJsonText())
            assertEquals(bank, restored); assertEquals(source, restored.hardPatch); assertEquals(variant, restored.variant)
            assertEquals(variant.shapes, restored.layers.map { it.strike })
            // v2 keeps v1's sources, windows and gains exactly.
            assertEquals(NacreContactVelocityRecipes.recipeFor(source), restored.unshaped)
            assertFails { NacreContactVelocityRecipe.fromJsonText(bank.toJsonText()) }
            assertFails { NacreContactStrikeRecipe.fromJsonText(NacreContactVelocityRecipes.recipeFor(source).toJsonText()) }
        }
        assertEquals("native-velocity-touch-contact-model4-v2", NacreContactStrikeRecipe.POLICY)
        assertEquals(2, NacreContactStrikeRecipe.POLICY_VERSION)
        assertEquals("nacreContactStrikeLayers", NacreContactStrikeRecipe.SOURCE_KEY)
        assertEquals("native-velocity-touch-contact-model4-v1", NacreContactVelocityRecipe.POLICY)
        assertEquals(1, NacreContactVelocityRecipe.POLICY_VERSION)
        // A fixed saved v2 document catches decoder/serializer drift independently of writer round trip.
        val saved = NacreContactStrikeRecipe.fromJsonText(V2_FIXTURE)
        assertEquals(hard().copy(name = "Strike fixture"), saved.hardPatch)
        assertEquals(NacreStrikeVariant.STRONG, saved.variant)
        assertEquals(Json.parse(V2_FIXTURE), saved.toJsonValue())
    }

    @Test
    fun jsonRefusesUnknownFieldsMixedVariantsAndOffTableShapes() {
        val value = bank().toJsonValue()
        val strong = bank(NacreStrikeVariant.STRONG).toJsonValue()
        val bad = listOf(
            JsonValue.Obj(value.entries + ("unknown" to JsonValue.Bool(true))),
            JsonValue.Obj(value.entries + ("policyVersion" to JsonValue.Num(1.0))),
            JsonValue.Obj(value.entries + ("policy" to JsonValue.Str(NacreContactVelocityRecipe.POLICY))),
            JsonValue.Obj(value.entries + ("variant" to JsonValue.Str("A"))),
            changedLayer(value, 0) { it + ("unknown" to JsonValue.Num(0.0)) },
            changedLayer(value, 0) { it - "strike" },
            changedLayer(value, 0) { it - "variant" },
            changedLayer(value, 0) { it + ("variant" to JsonValue.Str("C")) },
            changedLayer(value, 2) { it + ("variant" to JsonValue.Str("B")) },
            // Mixed variants: Strong's own Soft layer inside a Moderate bank.
            JsonValue.Obj(value.entries + ("layers" to JsonValue.Arr(listOf(strong.obj().getValue("layers").arr()[0]) +
                value.obj().getValue("layers").arr().drop(1)))),
            changedLayer(value, 0) { it + ("samplingGain" to JsonValue.Num(Math.nextUp(it.getValue("samplingGain").num()))) },
            changedStrike(value, 0) { it + ("unknown" to JsonValue.Num(0.0)) },
            changedStrike(value, 0) { it - "toneShift" },
            changedStrike(value, 0) { it + ("widthMs" to JsonValue.Num(Math.nextUp(it.getValue("widthMs").num()))) },
            changedStrike(value, 1) { it + ("pickBrightness" to JsonValue.Num(1.0)) },
            changedStrike(value, 2) { it + ("toneShift" to JsonValue.Num(.01)) },
            changedStrike(value, 0) { it + ("widthMs" to JsonValue.Str("0.8")) },
        )
        for (candidate in bad) assertFails { NacreContactStrikeRecipe.fromJsonValue(candidate) }
        assertEquals(bank(), NacreContactStrikeRecipe.fromJsonValue(value))
    }

    @Test
    fun constructorsRefuseMixedVariantsShapesAndV1Violations() {
        val a = bank(); val b = bank(NacreStrikeVariant.STRONG)
        assertFails { NacreContactStrikeRecipe(listOf(b.layers[0]) + a.layers.drop(1)) }
        assertFails { NacreContactStrikeRecipe(a.layers.dropLast(1) + NacreContactStrikeLayerRecipe(
            NacreStrikeVariant.MODERATE, NacreStrikeVariant.MODERATE.medium, a.layers.last().base)) }
        assertFails { NacreContactStrikeRecipe(a.layers.reversed()) }
        assertFails { NacreContactStrikeRecipe(emptyList()) }
        // A v1 rule still holds: a layer from another source note refuses.
        val other = NacreContactVelocityRecipes.recipeFor(hard().copy(midi = 49)).layers.first()
        assertFails { NacreContactStrikeRecipe(listOf(NacreContactStrikeLayerRecipe(NacreStrikeVariant.MODERATE,
            NacreStrikeVariant.MODERATE.soft, other)) + a.layers.drop(1)) }
        assertFails { NacreContactStrikeRecipes.recipeFor(hard().copy(velocity = .65f), NacreStrikeVariant.MODERATE) }
        assertFailsWithModel3()
    }

    private fun assertFailsWithModel3() {
        val model3 = NacrePresets.all().first { it.model == 3 }
        assertFails { NacreModels.renderStruck(model3, NacreStrikeShape.DEFAULT) }
    }

    @Test
    fun replayKeepsTheV1DeliveryContractAroundAShapedRender() {
        val bank = bank(NacreStrikeVariant.STRONG)
        val native = Snip(FloatArray(NacreContactVelocityRecipes.FRAME_COUNT) { .05f }, 1, Dsp.RATE)
        val calls = mutableListOf<Pair<NacrePatch, NacreStrikeShape>>()
        val result = bank.renderAllLayers { patch, shape -> calls += patch to shape; native }
        assertEquals(bank.layers.map { it.patch to it.strike }, calls)
        for (index in 0..1) assertEquals(.05f * bank.layers[index].samplingGain, result[index].samples[0])
        assertTrue(result.none { it.samples === native.samples })
        val hot = Snip(FloatArray(NacreContactVelocityRecipes.FRAME_COUNT) { .2f }, 1, Dsp.RATE)
        assertFails { bank.layers.first().render { _, _ -> hot } }
    }

    @Test
    fun v1DecodesAndRendersUnchangedAndV2HardIsV1HardBitForBit() {
        for ((contact, expected) in v1Hashes) {
            val v1 = NacreContactVelocityRecipe.fromJsonText(NacreContactVelocityRecipes.recipeFor(hard(contact)).toJsonText())
            val v1Layers = v1.renderAllLayers()
            assertEquals(expected, v1Layers.map { hash(it.samples) }, "v1 delivery changed at CONTACT $contact")
            for (variant in NacreStrikeVariant.entries) {
                val v2Hard = bank(variant, hard(contact)).layers.last().render()
                assertEquals(expected.last(), hash(v2Hard.samples), "v2 Hard is not v1 Hard at CONTACT $contact")
            }
            // DEFAULT through the strike path is the ordinary model-4 render, for a non-reference source too.
            val soft = v1.layers.first().patch
            assertContentEquals(soft.render().samples, NacreModels.renderStruck(soft, NacreStrikeShape.DEFAULT).samples)
        }
    }

    @Test
    fun strikeShapingDarkensSoftAndMediumWithoutMovingPitch() {
        // Floors are a little under the measured values (docs/NACRE_CONTACT_NATIVE_VELOCITY.md).
        val floors = mapOf(
            NacreStrikeVariant.MODERATE to (-8.7 to -4.6),
            NacreStrikeVariant.STRONG to (-18.5 to -7.0),
        )
        for (contact in listOf(.35f, .8f)) {
            val v1 = NacreContactVelocityRecipes.recipeFor(hard(contact)).renderAllLayers()
            val hardSnip = v1.last()
            val h = NacreStrikeMeasure.measure(hardSnip, hardSnip)
            val v1Soft = NacreStrikeMeasure.measure(v1.first(), hardSnip)
            var previousSoft = 0.0
            for (variant in NacreStrikeVariant.entries) {
                val layers = bank(variant, hard(contact)).renderAllLayers()
                assertContentEquals(hardSnip.samples, layers.last().samples)
                val (softFloor, mediumFloor) = floors.getValue(variant)
                val soft = NacreStrikeMeasure.measure(layers[0], hardSnip)
                val medium = NacreStrikeMeasure.measure(layers[1], hardSnip)
                val softDrop = soft.highBandDb - h.highBandDb
                val mediumDrop = medium.highBandDb - h.highBandDb
                assertTrue(softDrop <= softFloor, "$variant Soft high band fell only $softDrop dB at CONTACT $contact")
                assertTrue(mediumDrop <= mediumFloor, "$variant Medium high band fell only $mediumDrop dB at CONTACT $contact")
                assertTrue(softDrop < mediumDrop && mediumDrop < 0.0, "$variant Medium is not between Soft and Hard")
                assertTrue(softDrop < previousSoft, "B must be darker than A")
                previousSoft = softDrop
                assertTrue(soft.centroidHz < medium.centroidHz && medium.centroidHz < h.centroidHz)
                // Disclosed: the gain-removed residual barely moves (the body below 1 kHz is unchanged).
                assertTrue(soft.residualVsHardDb > v1Soft.residualVsHardDb, "$variant Soft became more like Hard")
                for (m in listOf(soft, medium)) {
                    assertTrue(abs(NacreStrikeMeasure.cents(m.pitchHz, h.pitchHz)) < 3.0, "$variant moved pitch")
                    assertTrue(m.peak in .3.. .950001)
                }
                assertNotEquals(hash(v1.first().samples), hash(layers.first().samples))
            }
        }
    }

    @Test
    fun shapedLayersAreDeterministicAcrossColdCaches() {
        val layer = bank(NacreStrikeVariant.MODERATE, hard(.8f)).layers[1]
        Nacre.clearCoreCache()
        val first = layer.render().samples
        Nacre.clearCoreCache()
        assertContentEquals(first, layer.render().samples)
    }

    private companion object {
        const val V1_SOFT_035 = "018b614abf14cbdc89b1713fc093055a59d01dc49b6746a64dd348fec412ca02"
        const val V1_MEDIUM_035 = "b08f1aaf038ae981f9060015724ff1bbc35d9fea89c583b71cc6952c0a3a29fe"
        const val V1_SOFT_080 = "612bc06a518ebedbdb85c3ee4dd2fdbe8a6df70e30cc132be0a2c475fbeed260"
        const val V1_MEDIUM_080 = "75bfa7e633f8199eb01bf632e878c3e84e8bc6a01a0c3f19368f95c2e80530bf"
        const val V2_FIXTURE = """{"schemaVersion":1,"engine":"NACRE","policy":"native-velocity-touch-contact-model4-v2","policyVersion":2,"layers":[{"name":"Soft","velocityStart":1,"velocityEnd":41,"samplingGain":5.802050590515137,"variant":"B","strike":{"widthMs":1.4,"pickBrightness":0.33,"toneShift":0.18},"recipe":{"recipe":2,"patch":{"engine":"NACRE","version":1,"name":"Strike fixture","voice":"PEARL","macros":{"TOUCH":0.05000000074505806,"DUET":0.15000000596046448,"CURRENT":0.20000000298023224,"VENUE":0,"BLOOM":0.15000000596046448,"HOLD":0,"CONTACT":0.3499999940395355},"model":4,"midi":48,"velocity":0.30000001192092896,"seed":"7"},"alias":false}},{"name":"Medium","velocityStart":42,"velocityEnd":84,"samplingGain":1.8642224073410034,"variant":"B","strike":{"widthMs":0.7,"pickBrightness":0.6,"toneShift":0.1},"recipe":{"recipe":2,"patch":{"engine":"NACRE","version":1,"name":"Strike fixture","voice":"PEARL","macros":{"TOUCH":0.18000000715255737,"DUET":0.15000000596046448,"CURRENT":0.20000000298023224,"VENUE":0,"BLOOM":0.15000000596046448,"HOLD":0,"CONTACT":0.3499999940395355},"model":4,"midi":48,"velocity":0.6499999761581421,"seed":"7"},"alias":false}},{"name":"Hard","velocityStart":85,"velocityEnd":127,"samplingGain":1,"variant":"B","strike":{"widthMs":0,"pickBrightness":1,"toneShift":0},"recipe":{"recipe":2,"patch":{"engine":"NACRE","version":1,"name":"Strike fixture","voice":"PEARL","macros":{"TOUCH":0.30000001192092896,"DUET":0.15000000596046448,"CURRENT":0.20000000298023224,"VENUE":0,"BLOOM":0.15000000596046448,"HOLD":0,"CONTACT":0.3499999940395355},"model":4,"midi":48,"velocity":1,"seed":"7"},"alias":false}}]}"""
    }
}
