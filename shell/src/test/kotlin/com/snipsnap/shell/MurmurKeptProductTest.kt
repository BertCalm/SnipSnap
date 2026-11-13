package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.json.Json
import com.snipsnap.synth.Murmur
import com.snipsnap.synth.MurmurBody
import com.snipsnap.synth.MurmurKeptBank
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurVoice
import com.snipsnap.synth.Presets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MurmurKeptProductTest {
    @Test
    fun `kept starter is separately discoverable exactly once without replacing historical starters`() {
        assertNotNull(StarterKits.byId("murmur"))
        assertNotNull(StarterKits.byId("murmur-selected"))
        val kept = assertNotNull(StarterKits.byId("murmur-kept"))
        assertEquals("MURMUR KEPT", kept.displayName)
        assertFalse(kept.seeded)
        assertEquals(1, StarterShelf.GROUPS.flatMap { it.ids }.count { it == "murmur-kept" })
        assertEquals("VOICES", StarterShelf.GROUPS.single { "murmur-kept" in it.ids }.heading)
        for (name in listOf("HOLLOW WIRE NOTE", "HOLLOW WIRE HOLD", "STEADY ROOT C2")) {
            assertEquals(1, Presets.forVoice("MURMUR", "PLUCK").count { it.name == name })
        }
    }

    @Test
    fun `revised bodies refuse generic forced hold paths while every legacy model retains its eligibility`() {
        for (source in MurmurKeptSources.all()) {
            val patch = source.factoryPatch()
            assertFalse(LoopPadMaker.supports(patch), source.id)
            val error = assertFailsWith<IllegalArgumentException> { LoopPadMaker.held(patch) }
            assertTrue(error.message.orEmpty().contains("bounded source-aware"))
            val direct = assertFailsWith<IllegalArgumentException> { LoopPadMaker.zoneMidis(LoopPadMaker.spec(patch, .5f)) }
            assertTrue(direct.message.orEmpty().contains("bounded source-aware"))
            assertEquals(patch.macros, source.factoryPatch().macros)
        }
        for (model in 1..3) for (voice in listOf(MurmurVoice.BASS, MurmurVoice.KEY, MurmurVoice.PLUCK, MurmurVoice.METAL)) {
            val legacy = MurmurPatch("OLD", voice, Murmur.defaults(voice), config = Murmur.configurationFor(voice, model))
            assertEquals(MurmurBody.LEGACY, legacy.config.body)
            assertTrue(LoopPadMaker.supports(legacy), "$model $voice")
            assertEquals(legacy.copy(macros = legacy.macros + ("HOLD" to 1f)), LoopPadMaker.held(legacy))
        }
    }

    @Test
    fun `synthetic assembled kit retains profiles and IN KEY uses derived authored notes without pitch detection`() {
        val dir = Files.createTempDirectory("murmur-kept-arranged").toFile()
        try {
            val raw = Snip(FloatArray(1024) { .2f }.also { it[it.lastIndex] = 0f }, 1, WavWriter.MPC_SAMPLE_RATE)
            val pads = MurmurKeptBank.render { it.process(raw) }
            MurmurKeptBankFiles.write(dir)
            val kit = KitAssembler.assembleArranged("MURMUR KEPT", pads, dir, KeySpec.parse("Cminpent"))
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            assertEquals(16, saved.pads.size)
            for ((i, pad) in saved.pads.withIndex()) {
                assertEquals(pads[i]!!.source, pad.source)
                assertEquals(MurmurKeptBank.entries()[i].recipe().patch!!.toJsonValue(),
                    com.snipsnap.synth.PadRecipe.fromJsonValue(pad.recipe!!).patch!!.toJsonValue())
            }
            assertEquals(3, saved.pads.count { "selectedSourceRecord" in it.source })
            // A detector fallback would need these files and fail. Authored-root placement does not.
            saved.pads.forEach { dir.resolve(it.sampleFile).delete() }
            val model = KitBuilderModel.open(dir)
            model.setKey(KeySpec.parse("C"))
            assertTrue(model.retuneTonalPads().isNotEmpty(), "the arranged Eb/Bb notes must enter C major")
            assertTrue(model.kit.pads.filter { it.drumClass == DrumClass.TONAL }.all { it.tuneFine == 0 })
            assertEquals(emptyList(), model.retuneTonalPads(), "authored-note placement is stable")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `source bank writes full immutable profiles and refuses to overwrite unrelated metadata`() {
        val dir = Files.createTempDirectory("murmur-kept-metadata").toFile()
        try {
            MurmurKeptBankFiles.write(dir)
            val file = dir.resolve(MurmurKeptBank.SOURCE_FILE)
            assertEquals(MurmurKeptBank.sourceBankJson(), file.readText())
            MurmurKeptBankFiles.write(dir)
            val record = Json.parse(file.readText()).obj()
            assertEquals(3, record.getValue("selectedSources").arr().size)
            assertEquals(16, record.getValue("pads").arr().size)
            file.writeText("existing source metadata")
            val error = assertFailsWith<IllegalArgumentException> { MurmurKeptBankFiles.write(dir) }
            assertTrue(error.message.orEmpty().contains("refusing to replace"))
            assertEquals("existing source metadata", file.readText())
        } finally { dir.deleteRecursively() }
    }
}
