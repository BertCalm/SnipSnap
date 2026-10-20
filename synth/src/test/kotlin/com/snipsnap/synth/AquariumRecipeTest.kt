package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AquariumRecipeTest {
    private fun edited(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
        JsonValue.Obj(AquariumRecipe().toJsonValue().entries + fields)

    private fun withParameters(vararg fields: Pair<String, JsonValue>): JsonValue.Obj = edited(
        "parameters" to JsonValue.Obj(AquariumRecipe().toJsonValue().entries.getValue("parameters").obj() + fields),
    )

    @Test
    fun `packaged parameter order units and defaults match the native canonical source`() {
        val canonical = Json.parse(File("../app/src/main/cpp/aquarium/parameters.json").readText()).obj()
        assertEquals(canonical.keys.toList(), AquariumRecipe.PARAMETERS.map { it.id }, "native snapshots and Kotlin must share parameter order")
        for (spec in AquariumRecipe.PARAMETERS) {
            val native = canonical.getValue(spec.id).obj()
            assertEquals(native.getValue("min").num().toFloat(), spec.minimum, spec.id)
            assertEquals(native.getValue("max").num().toFloat(), spec.maximum, spec.id)
            assertEquals(native.getValue("default").num().toFloat(), spec.default, spec.id)
            assertEquals(native["step"]?.num()?.toFloat() ?: 0f, spec.step, spec.id)
        }
        assertEquals(52, canonical.size, "changing the accepted parameter surface requires an explicit recipe review")
    }

    @Test
    fun `default settings resolve the exact native bypass descriptor without touching the rack`() {
        val dry = AquariumRecipe()
        assertEquals(AquariumRecipe.DEFAULT_SEED, dry.seed)
        assertEquals(AquariumRecipe.MODEL, dry.model)
        assertTrue(dry.isBypass)
        assertEquals(AquariumRecipe.PARAMETERS.associate { it.id to it.default }, dry.parameters)
        assertEquals(6, dry.parameters.count { (id, value) -> id.endsWith("_mix") && value == 0f })
        assertEquals(dry.toJsonText(), AquariumRecipe(dry.parameters).toJsonText(), "explicit defaults and absent constructor overrides must save the same sound")
        assertEquals(dry, AquariumRecipe.fromJsonText(dry.toJsonText()))
        assertEquals(dry.toJsonText(), AquariumRecipe(mapOf("aqua_reef_mix" to -0f)).toJsonText(), "signed zero must save canonically")
    }

    @Test
    fun `all six stages accept their complete native units and survive canonical serialization`() {
        val overrides = AquariumRecipe.PARAMETERS.associate { spec -> spec.id to spec.maximum }.toSortedMap(reverseOrder())
        val recipe = AquariumRecipe(overrides, seed = AquariumRecipe.MAX_SEED)
        assertFalse(recipe.isBypass)
        assertEquals(AquariumRecipe.PARAMETERS.map { it.id }, recipe.parameters.keys.toList())
        assertEquals(recipe, AquariumRecipe.fromJsonText(recipe.toJsonText()))
        assertEquals(recipe.toJsonText(), AquariumRecipe(overrides.toSortedMap(), recipe.seed).toJsonText())
        assertEquals(recipe.toJsonText(), AquariumRecipe.fromJsonText(recipe.toJsonText()).toJsonText())
        assertTrue(recipe.toJsonText().contains("4294967295"), "unsigned seed must not narrow to a signed Int")
    }

    @Test
    fun `unsigned seed endpoints and default survive without coercion`() {
        for (seed in listOf(0L, 1337L, 0x8000_0000L, AquariumRecipe.MAX_SEED)) {
            assertEquals(seed, AquariumRecipe.fromJsonText(AquariumRecipe(seed = seed).toJsonText()).seed)
        }
        for (seed in listOf(-1L, AquariumRecipe.MAX_SEED + 1)) {
            assertFailsWith<IllegalArgumentException> { AquariumRecipe(seed = seed) }
            assertFailsWith<JsonException> { AquariumRecipe.fromJsonValue(edited("seed" to JsonValue.Num(seed.toDouble()))) }
        }
        for (seed in listOf("1.5", "4294967295.5", "1e100", "null", "\"1337\"")) {
            assertFailsWith<JsonException> { AquariumRecipe.fromJsonValue(edited("seed" to Json.parse(seed))) }
        }
    }

    @Test
    fun `unknown names fields versions and wrong value types refuse rather than disappear`() {
        for (value in listOf(
            withParameters("aqua_fathom_hiXover" to JsonValue.Num(3000.0)),
            edited("placement" to JsonValue.Str("master")),
            edited("preset" to JsonValue.Str("full")),
            edited("aquarium" to JsonValue.Num(2.0)),
            edited("aquarium" to JsonValue.Num(1.5)),
            edited("model" to JsonValue.Num(2.0)),
            edited("parameters" to JsonValue.Null),
            withParameters("aqua_reef_mix" to JsonValue.Str("0.5")),
        )) assertFailsWith<JsonException>(Json.write(value)) { AquariumRecipe.fromJsonValue(value) }
        for (field in listOf("aquarium", "model", "seed", "parameters")) {
            assertFailsWith<JsonException>("missing $field") {
                AquariumRecipe.fromJsonValue(JsonValue.Obj(AquariumRecipe().toJsonValue().entries - field))
            }
        }
        assertFailsWith<JsonException> {
            AquariumRecipe.fromJsonValue(edited("parameters" to JsonValue.Obj(AquariumRecipe().toJsonValue().entries.getValue("parameters").obj() - "aqua_reef_mix")))
        }
        assertFailsWith<IllegalArgumentException> { AquariumRecipe(mapOf("aqua_missing_mix" to 0f)) }
        assertFailsWith<IllegalArgumentException> { AquariumRecipe(model = 2) }
    }

    @Test
    fun `nonfinite and out of range controls fail before an adapter can render`() {
        for (spec in AquariumRecipe.PARAMETERS) {
            for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, spec.minimum - 1f, spec.maximum + 1f)) {
                assertFailsWith<IllegalArgumentException>("${spec.id}: $value") { AquariumRecipe(mapOf(spec.id to value)) }
                val json = withParameters(spec.id to JsonValue.Num(value.toDouble()))
                assertFailsWith<JsonException>("${spec.id}: $value") { AquariumRecipe.fromJsonValue(json) }
            }
        }
        assertFailsWith<JsonException> { AquariumRecipe.fromJsonValue(withParameters("aqua_reef_mix" to JsonValue.Num(1e100))) }
    }

    @Test
    fun `selectors and counts reject ambiguous fractional settings while continuous controls retain native precision`() {
        val discrete = AquariumRecipe.PARAMETERS.filter { it.isDiscrete }
        assertEquals(8, discrete.size)
        for (spec in discrete) {
            val fraction = spec.minimum + 0.5f
            assertFailsWith<IllegalArgumentException>(spec.id) { AquariumRecipe(mapOf(spec.id to fraction)) }
            assertFailsWith<JsonException>(spec.id) { AquariumRecipe.fromJsonValue(withParameters(spec.id to JsonValue.Num(fraction.toDouble()))) }
        }
        assertFailsWith<JsonException> {
            AquariumRecipe.fromJsonValue(withParameters("aqua_tide_mode" to JsonValue.Num(1.0000000000000002)))
        }
        val precise = AquariumRecipe(mapOf("aqua_surface_freq" to 3200.25f, "aqua_surface_gain" to -4.567f))
        assertEquals(precise, AquariumRecipe.fromJsonText(precise.toJsonText()))
    }

    @Test
    fun `JSON checks precise bounds before float narrowing without rejecting decimal or saved float endpoints`() {
        val canonical = Json.parse(File("../app/src/main/cpp/aquarium/parameters.json").readText()).obj()
        for (endpoint in listOf("min", "max")) {
            val physical = canonical.mapValues { (_, value) -> value.obj().getValue(endpoint) }
            val decoded = AquariumRecipe.fromJsonValue(edited("parameters" to JsonValue.Obj(physical)))
            assertEquals(physical.mapValues { it.value.num().toFloat() }, decoded.parameters)
            assertEquals(decoded, AquariumRecipe.fromJsonText(decoded.toJsonText()))
        }
        for (spec in AquariumRecipe.PARAMETERS) {
            val physical = canonical.getValue(spec.id).obj()
            val lower = minOf(physical.getValue("min").num(), spec.minimum.toDouble())
            val upper = maxOf(physical.getValue("max").num(), spec.maximum.toDouble())
            for (outside in listOf(Math.nextDown(lower), Math.nextUp(upper))) {
                assertFailsWith<JsonException>("${spec.id}: $outside") {
                    AquariumRecipe.fromJsonValue(withParameters(spec.id to JsonValue.Num(outside)))
                }
            }
        }
    }

    @Test
    fun `caller mutation cannot change a saved recipe or native snapshot`() {
        val caller = linkedMapOf("aqua_surface_gain" to -4.5f, "aqua_surface_mix" to 0.8f)
        val recipe = AquariumRecipe(caller)
        val json = recipe.toJsonText()
        caller["aqua_surface_gain"] = 12f
        caller.clear()
        assertEquals(json, recipe.toJsonText())
        assertEquals(-4.5f, recipe.parameters.getValue("aqua_surface_gain"))
        assertFailsWith<UnsupportedOperationException> { (recipe.parameters as MutableMap)["aqua_surface_gain"] = 12f }
    }
}
