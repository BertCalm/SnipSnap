package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** These metadata/synthetic tests deliberately do not render any native MURMUR source. */
class MurmurKeptDeliveryTest {
    @Test
    fun `old forty two canonical recipes retain historical hashes and new aliases append uniquely`() {
        val baseline = Json.parse(resource("legacy42-hashes.json")).obj()
        val old = MurmurPresets.original() + MurmurPresets.selected()
        assertEquals(42, old.size)
        for ((i, patch) in old.withIndex()) {
            assertEquals(baseline.getValue("$i-${patch.name}").obj().getValue("recipe_sha256").str(),
                sha256(patch.toJsonText()), patch.name)
        }
        assertEquals(old, MurmurPresets.all().take(42))
        assertEquals(listOf("HOLLOW WIRE NOTE", "HOLLOW WIRE HOLD", "STEADY ROOT C2"), MurmurPresets.kept().map { it.name })
        assertEquals(45, MurmurPresets.all().map { it.name }.toSet().size)
        assertEquals(MurmurPresets.kept(), MurmurPresets.all().drop(42))
        assertEquals(listOf(MurmurSelectedPlucks.shortAnswerHollowWire(), MurmurSelectedPlucks.openStringHollowWire(),
            MurmurSelectedPlucks.shortAnswerRootSupported()).mapIndexed { i, patch -> patch.copy(name = MurmurPresets.kept()[i].name) },
            MurmurPresets.kept())
        assertEquals(MurmurPresets.all().filter { it.voice == MurmurVoice.PLUCK }, Presets.forVoice("MURMUR", "PLUCK"))
    }

    @Test
    fun `factory aliases retain full native fixtures while immutable profiles retain exact original heard JSON`() {
        for (source in MurmurKeptSources.all()) {
            val nativeText = resource("${source.id}-native.json")
            val heardText = resource("${source.id}-heard.json")
            val alias = MurmurPatch.fromJsonText(resource("${source.id}-alias.json"))
            assertEquals(nativeText, source.originalNativePatchJson)
            assertEquals(sha256(nativeText), source.originalNativePatchSha256)
            assertEquals(heardText, source.reference.heardRecipeJson)
            assertEquals(sha256(heardText), source.reference.heardRecipeSha256)
            assertEquals(MurmurPatch.fromJsonText(nativeText), source.originalPatch())
            assertEquals(alias, source.factoryPatch())
            assertEquals(alias, Patches.fromJsonText(alias.toJsonText()))
            assertEquals(4, alias.config.model)
            assertEquals(MurmurVoice.PLUCK, alias.voice)
            assertEquals(source.supportedMidi, Murmur.rootRange(alias.config))
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(alias.name))
            val record = source.toJsonValue().entries
            assertEquals("HOUSE_NORMALIZED", record.getValue("factoryOutput").str())
            assertEquals("RAW_NATIVE_WITH_SAVED_GAIN", record.getValue("reference").obj().getValue("outputStage").str())
            assertFalse("output_gain" in PadRecipe(alias).toJsonValue().entries)
            assertNull(PadRecipe(alias).fx)
        }
    }

    @Test
    fun `saved raw gain and heard presentation remain distinct from house level factory playback`() {
        val finite = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE)
        val held = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE)
        val low = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2)
        assertEquals(2.000858783721924f, finite.reference.rawOutputGain)
        assertEquals(1.0885975360870361f, held.reference.rawOutputGain)
        assertEquals(3.3389108180999756f, low.reference.rawOutputGain)
        assertEquals("22e71cbd248a75c99aeba6521bdbf58553fa7911baefc1ee767ebf1841bb137d", finite.reference.heardWavSha256)
        assertEquals("b595fd3d5c3a7fde5e3f68244bd7fdce84ab2305de70ad485d067851abfc4ac9", held.reference.heardWavSha256)
        assertEquals("f444994044e6b2b5f4d2403ae8f7403aa927c5c9ae5154c2df94719d15074ca3", low.reference.heardWavSha256)
        assertEquals("3a8c07f472a5d0667bf38bd063524b7450f8bd3cb2b9ccb1d7670893d70b814b", held.reference.nativeReferenceWavSha256)
        assertEquals(MurmurKeptSources.Presentation(2, 3528, 11025), held.reference.presentation)
        assertNull(finite.reference.presentation)
        assertNull(low.reference.presentation)
        for (source in MurmurKeptSources.all()) {
            val heard = Json.parse(source.reference.heardRecipeJson).obj()
            val body = heard["body_recipe"]?.obj() ?: heard
            assertEquals(source.reference.rawOutputGain, body.getValue("output_gain").num().toFloat())
        }
    }

    @Test
    fun `caller mutation cannot corrupt later factory patches or reference snapshots`() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE)
        val first = source.factoryPatch()
        (first.macros as MutableMap<String, Float>)["HOLD"] = 0f
        (first.config.ratios as MutableList<Double>)[0] = 9.0
        assertEquals(1f, source.factoryPatch().macros.getValue("HOLD"))
        assertEquals(1.0, source.factoryPatch().config.ratios.first())
        val json = source.toJsonValue().entries as MutableMap<String, JsonValue>
        json.clear()
        assertEquals(source.id, source.toJsonValue().entries.getValue("id").str())
    }

    @Test
    fun `sixteen immutable source and derived recipes name only kept plucks and prior key choir choices`() {
        val entries = MurmurKeptBank.entries()
        assertEquals(16, entries.size)
        assertEquals((1..16).toList(), entries.map { it.number })
        val patches = entries.map { assertIs<MurmurPatch>(it.recipe().patch) }
        assertEquals(listOf(48, 51, 53, 55, 58, 60, 63), patches.take(7).map { it.midi })
        assertTrue(entries.take(7).all { MurmurPatch.fromJsonText(it.sourcePatchJson).midi == 60 })
        assertEquals(MurmurPresets.selected().filter { it.voice == MurmurVoice.KEY }, patches.subList(7, 11))
        assertEquals(MurmurPresets.kept(), patches.subList(11, 14))
        assertEquals(MurmurPresets.selected().filter { it.voice == MurmurVoice.CHOIR }, patches.takeLast(2))
        assertEquals(setOf(MurmurVoice.KEY, MurmurVoice.PLUCK, MurmurVoice.CHOIR), patches.map { it.voice }.toSet())
        assertEquals(listOf(MurmurKeptSources.FINITE_WIRE, MurmurKeptSources.HELD_WIRE, MurmurKeptSources.STEADY_C2),
            entries.subList(11, 14).map { it.selectedSourceId })
        for (row in entries) {
            val record = row.toJsonValue().entries
            assertEquals(sha256(row.sourcePatchJson), record.getValue("sourcePatchSha256").str())
            assertEquals(sha256(row.derivedRecipeJson), record.getValue("derivedRecipeSha256").str())
            assertEquals(row.recipe(), PadRecipe.fromJsonText(row.derivedRecipeJson))
            assertFalse(record.getValue("naturalReleaseClaimed").bool())
        }
    }

    @Test
    fun `synthetic bank execution retains complete source provenance and applies only saved pad endings`() {
        val raw = Snip(FloatArray(1024) { .2f }.also { it[it.lastIndex] = 0f }, 1, Dsp.RATE)
        val recipes = mutableListOf<PadRecipe>()
        val pads = MurmurKeptBank.render { recipe -> recipes += recipe; recipe.process(raw) }.map { assertNotNull(it) }
        assertEquals(16, pads.size)
        assertEquals(listOf(12, 14, 15), recipes.indices.filter { recipes[it].terminalFadeMs != null })
        for ((i, pad) in pads.withIndex()) {
            val recipe = recipes[i]
            assertEquals(recipe, PadRecipe.fromJsonValue(assertNotNull(pad.recipe)))
            assertEquals(0f, pad.snip.samples.last())
            assertTrue(pad.oneShot)
            assertEquals("HOUSE_NORMALIZED", pad.source.getValue("outputStage"))
            assertEquals("authored", pad.source.getValue("rootOrigin"))
            assertEquals((recipe.patch as MurmurPatch).midi.toString(), pad.source.getValue("rootMidi"))
            assertEquals(sha256(MurmurKeptBank.entries()[i].sourcePatchJson), pad.source.getValue("sourcePatchSha256"))
            if (recipe.terminalFadeMs != null) {
                assertEquals(8f, recipe.terminalFadeMs)
                assertContentEquals(raw.samples.copyOfRange(0, 600), pad.snip.samples.copyOfRange(0, 600))
            } else assertContentEquals(raw.samples, pad.snip.samples)
            if (i in 11..13) {
                val source = Json.parse(pad.source.getValue("selectedSourceRecord")).obj()
                assertEquals(MurmurKeptBank.entries()[i].selectedSourceId, source.getValue("id").str())
                assertTrue(source.getValue("reference").obj().getValue("heardRecipeText").str().isNotBlank())
            }
        }
        assertContentEquals(FloatArray(1024) { .2f }.also { it[it.lastIndex] = 0f }, raw.samples)
        val metadata = Json.parse(MurmurKeptBank.sourceBankJson()).obj()
        assertFalse(metadata.getValue("referenceGainsAppliedToFactoryPads").bool())
        assertFalse(metadata.getValue("wholeEngineApprovalClaimed").bool())
        assertEquals(16, metadata.getValue("pads").arr().size)
        assertEquals(3, metadata.getValue("selectedSources").arr().size)
    }

    private fun resource(name: String) = checkNotNull(javaClass.getResource("/murmur-kept/$name")).readText()
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
