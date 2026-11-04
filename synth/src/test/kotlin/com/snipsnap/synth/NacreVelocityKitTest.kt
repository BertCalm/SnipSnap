package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NacreVelocityKitTest {
    @Test
    fun `sixteen layered notes retain the hard source and replay all native performances with stated gains`() {
        val pads = SynthKits.nacreVelocity().map { requireNotNull(it) }
        assertEquals(16, pads.size)
        assertEquals(16, pads.map { it.displayName }.distinct().size)
        val starts = NacrePresets.all().associateBy { it.name }
        val names = List(8) { "Pearl Close" } + List(4) { "Coral Reply" } + List(4) { "Kelp Passage" }
        val notes = listOf(48, 51, 53, 55, 58, 60, 63, 65, 48, 51, 55, 60, 48, 51, 55, 60)
        val velocities = listOf(.30f, .65f, 1f)
        val touches = listOf(.05f, .18f, .30f)
        val windows = listOf(1..41, 42..84, 85..127)
        for ((index, pad) in pads.withIndex()) {
            val hard = assertIs<NacrePatch>(PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch)
            val start = starts.getValue(names[index])
            assertEquals(start.copy(name = requireNotNull(pad.displayName), midi = notes[index], velocity = 1f), hard,
                "pad ${index + 1} changed the preferred hard strike")
            assertEquals(DrumClass.TONAL, pad.drumClass)
            assertTrue(pad.oneShot)
            assertEquals(0, pad.muteGroup)
            assertEquals(0, pad.tuneCoarse)
            assertEquals(0, pad.tuneFine)
            assertTrue(pad.takes.isEmpty())
            assertNull(pad.level, "all layers retain the house's uniform mixer headroom")
            assertEquals(listOf("Soft", "Medium", "Hard"), pad.layerNames)
            assertEquals(2, pad.softVariants.size)
            val bank = NacreVelocityRecipe.fromJsonText(pad.source.getValue(NacreVelocityRecipe.SOURCE_KEY))
            assertEquals(NacreVelocityKits.recipeFor(hard), bank)
            assertEquals(bank, NacreVelocityRecipe.fromJsonText(bank.toJsonText()))
            assertEquals(hard, bank.hardPatch)
            assertEquals(names[index], pad.source["preset"])
            assertEquals(notes[index].toString(), pad.source["pitchMidi"])
            assertEquals("7", pad.source["seed"])
            assertEquals("3", pad.source["model"])
            val actual = pad.softVariants + pad.snip
            for ((layerIndex, layer) in bank.layers.withIndex()) {
                val patch = layer.patch
                assertEquals(hard.copy(velocity = velocities[layerIndex],
                    macros = hard.macros + ("TOUCH" to touches[layerIndex])), patch)
                assertEquals(windows[layerIndex].first, layer.velocityStart)
                assertEquals(windows[layerIndex].last, layer.velocityEnd)
                assertEquals(7L, patch.seed)
                assertEquals(3, patch.model)
                assertFalse(layer.recipe.alias)
                assertNull(layer.recipe.fx)
                assertNull(layer.recipe.layers)
                assertNull(layer.recipe.board)
                assertNull(layer.recipe.pedals)
                assertNull(layer.recipe.terminalFadeMs)
                fun effort(t: Float) = .22 + .78 * t.toDouble().pow(.65)
                val expectedGain = if (layerIndex == 2) 1f else
                    (effort(.30f) / (velocities[layerIndex] * effort(touches[layerIndex]))).toFloat()
                assertEquals(expectedGain, layer.samplingGain)
                val native = PadRecipe.fromJsonText(layer.recipe.toJsonText()).render()
                val expected = FloatArray(native.samples.size) { native.samples[it] * expectedGain }
                val sample = actual[layerIndex]
                assertEquals(1, sample.channels)
                assertEquals(44_100, sample.sampleRate)
                assertEquals(264_600, sample.frameCount)
                assertTrue(sample.samples.all { it.isFinite() })
                assertTrue(sample.peak() in .001f.. .950001f)
                assertEquals(0f, sample.samples.last())
                assertContentEquals(expected, sample.samples,
                    "pad ${index + 1} layer $layerIndex did not replay its native source and explicit input gain")
                if (layerIndex == 2) assertContentEquals(native.samples, pad.snip.samples,
                    "the hard anchor must stay byte-identical to its ordinary saved patch")
            }
            assertEquals(0, bank.layers.count { 0 in it.velocityStart..it.velocityEnd })
            for (velocity in 1..127) assertEquals(1,
                bank.layers.count { velocity in it.velocityStart..it.velocityEnd },
                "MIDI velocity $velocity must select exactly one source")
            // Gain fitting deliberately removes level. Native velocity alone was
            // too close to a volume-scaled source; these contact articulations must
            // retain a substantial waveform difference at every canonical venue.
            if (index in listOf(0, 8, 12)) {
                assertTrue(bestScalarResidual(actual[0], actual[2]) > .08,
                    "${names[index]} soft articulation collapsed to a gain change")
                assertTrue(bestScalarResidual(actual[1], actual[2]) > .04,
                    "${names[index]} medium articulation collapsed to a gain change")
            }
        }
    }

    @Test
    fun `complete layer metadata keeps the exact long seed and owns its control maps`() {
        val controls = NacrePresets.all().first().macros.toMutableMap()
        val hard = NacrePresets.all().first().copy(name = "Long seed", midi = 60,
            seed = Long.MAX_VALUE, macros = controls)
        val bank = NacreVelocityKits.recipeFor(hard)
        controls["VENUE"] = 1f
        val text = bank.toJsonText()
        assertTrue("9223372036854775807" in text)
        val restored = NacreVelocityRecipe.fromJsonText(text)
        assertEquals(bank, restored)
        for (layer in restored.layers) {
            assertEquals(Long.MAX_VALUE, layer.patch.seed)
            assertEquals(60, layer.patch.midi)
            assertEquals(0f, layer.patch.macros.getValue("VENUE"))
        }
        val mutableLayers = bank.layers.toMutableList()
        val owned = NacreVelocityRecipe(mutableLayers)
        mutableLayers.clear()
        assertEquals(3, owned.layers.size)
    }

    @Test
    fun `native layer replay owns all returned audio instead of changing cached renderer arrays`() {
        val shared = FloatArray(264_600) { .001f }.also { it[it.lastIndex] = 0f }
        val renderer: (NacrePatch) -> Snip = { Snip(shared, 1, 44_100) }
        val pads = NacreVelocityKits.build(renderer).map { requireNotNull(it) }
        assertTrue(shared.first() == .001f)
        val originalOther = pads[1].softVariants[0].samples.first()
        pads[0].softVariants[0].samples[0] = 0f
        assertEquals(originalOther, pads[1].softVariants[0].samples.first())
        assertEquals(.001f, shared.first())
        assertEquals(.001f, pads[0].snip.samples.first())
        val bank = NacreVelocityKits.recipeFor(NacreVelocityKits.patches().first())
        val one = bank.renderAllLayers(renderer)
        val two = bank.renderAllLayers(renderer)
        one[0].samples[0] = 0f
        assertTrue(two[0].samples.first() > 0f)
        assertEquals(.001f, shared.first())
    }

    @Test
    fun `malformed layer history refuses gaps incompatible sources and unrecorded gain policies`() {
        val bank = NacreVelocityKits.recipeFor(NacreVelocityKits.patches().first())
        val value = bank.toJsonValue()
        fun modifiedLayer(index: Int, transform: (JsonValue.Obj) -> JsonValue.Obj): JsonValue.Obj {
            val layers = value.entries.getValue("layers").arr().mapIndexed { at, item ->
                if (at == index) transform(item as JsonValue.Obj) else item
            }
            return JsonValue.Obj(value.entries + ("layers" to JsonValue.Arr(layers)))
        }
        fun refuses(value: JsonValue) = assertFailsWith<IllegalArgumentException> {
            NacreVelocityRecipe.fromJsonValue(value)
        }
        refuses(modifiedLayer(0) { JsonValue.Obj(it.entries + ("velocityStart" to JsonValue.Num(0.0))) })
        refuses(modifiedLayer(0) { JsonValue.Obj(it.entries + ("velocityEnd" to JsonValue.Num(40.0))) })
        refuses(modifiedLayer(0) { JsonValue.Obj(it.entries + ("samplingGain" to JsonValue.Num(2.0))) })
        refuses(modifiedLayer(0) { JsonValue.Obj(it.entries + ("samplingGain" to JsonValue.Num(Double.NaN))) })
        refuses(JsonValue.Obj(value.entries + ("schemaVersion" to JsonValue.Num(2.0))))
        refuses(modifiedLayer(0) { layer ->
            val recipe = layer.entries.getValue("recipe") as JsonValue.Obj
            val patch = recipe.entries.getValue("patch") as JsonValue.Obj
            val changed = JsonValue.Obj(patch.entries + ("seed" to JsonValue.Str("8")))
            JsonValue.Obj(layer.entries + ("recipe" to JsonValue.Obj(recipe.entries + ("patch" to changed))))
        })
        refuses(modifiedLayer(0) { layer ->
            val recipe = layer.entries.getValue("recipe") as JsonValue.Obj
            JsonValue.Obj(layer.entries + ("recipe" to JsonValue.Obj(recipe.entries + ("terminalFadeMs" to JsonValue.Num(5.0)))))
        })
        assertFailsWith<com.snipsnap.json.JsonException> {
            NacreVelocityRecipe.fromJsonValue(modifiedLayer(0) {
                JsonValue.Obj(it.entries + ("velocityEnd" to JsonValue.Num(41.5)))
            })
        }
        assertFailsWith<com.snipsnap.json.JsonException> {
            NacreVelocityRecipe.fromJsonValue(JsonValue.Obj(value.entries + ("schemaVersion" to JsonValue.Num(1.5))))
        }
    }

    private fun bestScalarResidual(candidate: Snip, reference: Snip): Double {
        var dot = 0.0
        var referenceEnergy = 0.0
        var candidateEnergy = 0.0
        for (i in candidate.samples.indices) {
            val a = candidate.samples[i].toDouble()
            val b = reference.samples[i].toDouble()
            dot += a * b
            referenceEnergy += b * b
            candidateEnergy += a * a
        }
        val gain = dot / referenceEnergy
        var residual = 0.0
        for (i in candidate.samples.indices) {
            val error = candidate.samples[i] - gain * reference.samples[i]
            residual += error * error
        }
        return sqrt(residual / candidateEnergy)
    }
}
