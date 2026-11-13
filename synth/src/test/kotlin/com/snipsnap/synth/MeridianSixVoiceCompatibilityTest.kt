package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real saved-source/registry boundaries; native regression is a separately scheduled gate. */
class MeridianSixVoiceCompatibilityTest {
    private val originals = listOf(MeridianVoice.SWEEP, MeridianVoice.CLACK, MeridianVoice.CHIME, MeridianVoice.RASP)
    private val gestures = listOf(MeridianVoice.DRAW, MeridianVoice.LIFT)

    private fun resource(name: String): JsonValue {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/meridian-gestures/$name.json")).use { it.readBytes() }
        val expected = requireNotNull(javaClass.getResourceAsStream("/meridian-gestures/$name.sha256"))
            .bufferedReader().use { it.readText().trim() }
        assertEquals(expected, hash(bytes), "Tracked source fixture changed: $name")
        return Json.parse(bytes.toString(Charsets.UTF_8))
    }

    @Test fun `each explicit voice model pair is admitted or refused through the real registry`() {
        assertEquals(1, Patches.VERSION, "Adding voices must not migrate the stored patch schema")
        assertEquals(originals + gestures, MeridianVoice.entries)
        assertEquals(listOf(0, 1, 2, 3), originals.map { it.ordinal })
        assertEquals(listOf(2, 3, 3, 3, 4, 4), MeridianVoice.entries.map { Meridian.defaultModelFor(it) })
        for (voice in MeridianVoice.entries) for (model in 0..5) {
            val allowed = when (voice) {
                MeridianVoice.SWEEP -> model in 1..3
                MeridianVoice.CLACK, MeridianVoice.CHIME, MeridianVoice.RASP -> model == 3
                MeridianVoice.DRAW, MeridianVoice.LIFT -> model == 4
            }
            assertEquals(allowed, Meridian.supportsVoice(voice, model), "$voice/$model")
            val valid = MeridianPresets.forVoice(voice).first().copy(midi = 69, velocity = .37f, seedContext = -73)
            val raw = JsonValue.Obj(LinkedHashMap(valid.toJsonValue().entries).apply { put("model", JsonValue.Num(model.toDouble())) })
            if (allowed) {
                val restored = assertIs<MeridianPatch>(Patches.fromJsonValue(raw))
                assertEquals(valid.copy(model = model), restored)
                assertEquals(restored, Patches.fromJsonText(restored.toJsonText()))
            } else {
                assertFailsWith<IllegalArgumentException> { valid.copy(model = model) }
                assertFailsWith<JsonException> { Patches.fromJsonValue(raw) }
            }
        }
    }

    @Test fun `missing and null model retain historical SWEEP one and cannot upgrade another voice`() {
        for (voice in MeridianVoice.entries) for (missing in listOf(true, false)) {
            val source = MeridianPresets.forVoice(voice).first().copy(midi = 69, velocity = .37f,
                seedContext = -73, macros = MeridianPresets.forVoice(voice).first().macros + ("HOLD" to 1f))
            val raw = JsonValue.Obj(LinkedHashMap(source.toJsonValue().entries).apply {
                if (missing) remove("model") else put("model", JsonValue.Null)
            })
            if (voice == MeridianVoice.SWEEP) {
                val old = assertIs<MeridianPatch>(Patches.fromJsonValue(raw))
                assertEquals(source.copy(model = 1), old)
                assertFalse(Meridian.isLoop(old.macros, old.model))
            } else assertFailsWith<JsonException> { Patches.fromJsonValue(raw) }
        }
    }

    @Test fun `four kept finite recipes and exact held counterparts retain saved fields through edits`() {
        val fixture = resource("kept-model-4-source").obj()
        assertEquals("454e434841adac0dadbb746fcbfa294e9d991986", fixture.getValue("nativeSourceCommit").str())
        val rows = fixture.getValue("clips").arr()
        assertEquals(8, rows.size)
        assertEquals(listOf("OPEN LINE", "WIDE THREAD", "AFTER PRESS", "SOFT RETURN"), MeridianPresets.all().drop(14).map { it.name })
        for (row in rows) {
            val o = row.obj()
            val canonical = o.getValue("canonicalPatchJson").str()
            assertEquals(o.getValue("canonicalPatchSha256").str(), hash(canonical.toByteArray()))
            val patch = assertIs<MeridianPatch>(Patches.fromJsonText(canonical))
            assertEquals(canonical, patch.toJsonText())
            assertEquals(4, patch.model)
            assertEquals(57, patch.midi); assertEquals(1f, patch.velocity); assertEquals(4810, patch.seedContext)
            val factory = MeridianPresets.forVoice(patch.voice).single { it.name == patch.name }
            val held = o.getValue("playback").str() == "HELD"
            assertEquals(factory.copy(macros = factory.macros + ("HOLD" to if (held) 1f else 0f)), patch)
            val performed = patch.copy(midi = 69, velocity = .37f, seedContext = -73)
            val edited = assertIs<MeridianPatch>(Patches.edited(performed, "MY ${patch.voice.name}",
                performed.macros + ("MEMORY" to .25f)))
            assertEquals(performed.copy(name = edited.name, macros = edited.macros), edited)
            assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
        }
    }

    @Test fun `six voice registry growth does not reinterpret sixteen actual saved v1 material histories`() {
        assertEquals(18, MeridianPresets.all().size)
        val fixture = resource("materials-v1-saved-banks").obj()
        assertEquals("66dd7b433462a9966be76f9e458b9289577f7840", fixture.getValue("productionSourceCommit").str())
        val saved = fixture.getValue("canonicalSavedBanks").arr().map { it.str() }
        assertEquals(16, saved.size)
        assertEquals(14, MeridianMaterialsKits.factoriesV1().size)
        assertEquals(originals.toSet(), MeridianMaterialsKits.patches().map { it.voice }.toSet())
        assertEquals(listOf(57,57,57,57,57,57,57,57,57,45,69,57,57,57,57,57), MeridianMaterialsKits.patches().map { it.midi })
        for ((index, encoded) in saved.withIndex()) {
            val bank = MeridianMaterialsVelocityRecipe.fromJsonText(encoded)
            assertEquals(index + 1, bank.slot)
            assertEquals(encoded, bank.toJsonText(), "Saved v1 history changed at A${index + 1}")
            assertEquals(MeridianMaterialsKits.patches()[index], bank.hardPatch)
            assertEquals(listOf(.30f, .65f, 1f), bank.layers.map { it.nativeVelocity })
            assertTrue(bank.layers.all { (it.recipe.patch as MeridianPatch).voice in originals })
        }
    }

    @Test fun `native model four renders retain kept finite PCM16 and held raw cycle identities`() {
        val rows = resource("kept-model-4-source").obj().getValue("clips").arr().associate { row ->
            row.obj().getValue("id").str() to row.obj()
        }
        for (reading in MeridianGestureReadings.all) {
            val expected = rows.getValue(reading.id)
            assertEquals(expected.getValue("nativeFrames").int(), reading.snip.frameCount, reading.id)
            assertEquals(expected.getValue("sourceRawFloatSha256").str(), MeridianGestureReadings.rawHash(reading.snip.samples), reading.id)
            assertEquals(expected["loopStartFrame"]?.takeUnless { it == JsonValue.Null }?.int(), reading.held?.loopStartFrame)
            assertEquals(expected["loopEndFrameExclusive"]?.takeUnless { it == JsonValue.Null }?.int(), reading.held?.loopEndFrameExclusive)
            if (reading.held == null) {
                val wav = ByteArrayOutputStream().also { WavWriter.write(it, reading.snip, WavWriter.BitDepth.PCM_16) }.toByteArray()
                assertEquals(expected.getValue("finiteKeptWavSha256").str(), hash(wav), "${reading.id} kept finite WAV")
            }
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
