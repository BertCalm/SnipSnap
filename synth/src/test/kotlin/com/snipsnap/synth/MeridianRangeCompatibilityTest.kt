package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The original factory waveforms were captured from the unchanged pre-promotion jars. */
class MeridianRangeCompatibilityTest {
    private data class Golden(val frames: Int, val hash: String, val loopStart: Int? = null)

    // IEEE754 Float raw bits, little-endian. Captured before promotion against
    // 97a5d6309's identical Meridian sources, independently of the new renderer.
    private val oldBank = linkedMapOf(
        "FIRST ARC" to Golden(129813, "19b1867f12b6ae9c1ed3b21ae6fbe0ff5e769e9e4369eb9502581fda2d0174a5"),
        "BACK ARC" to Golden(129813, "22ce8ceb057a46f419b986f5c267fba355486bad91e3d012e6164fd976e1572e"),
        "CLOSE GRAIN" to Golden(120165, "a49592ab1cec36706514220afd37b2c2773f366570ceedbc7fc2a390458bdb07"),
        "DEEP KNOCK" to Golden(123605, "129a85514251126f3a75539eee2f1b15cb3408bb9e5dd6c0542865a1e518f443"),
        "STILL PAIR" to Golden(133329, "a1d59fa0d16a01960d8680c0fc207c3081077ce843c63606bb5cf54c971b245a"),
        "LONG TRAIL" to Golden(257771, "a8f98be54dc5a16cf2723657de24a3ae6d434aaccd27e04717d6a6981e85b41c"),
        "ROUND THREAD" to Golden(93812, "2ccc1b8a63bf68c0316901eb5b29f2f1da07e3178d2d9070cdfb25941dc8b3d6", 46906),
        "FINE STRAND" to Golden(79780, "bb45c0315b21a929fb3dbd0d1c608f4c37b718a44604432702d01e158f6d5de2", 39890),
    )
    private val added = listOf(MeridianVoice.CLACK, MeridianVoice.CHIME, MeridianVoice.RASP)

    @Test
    fun `all eight existing factory recipes retain independent model two float goldens`() {
        val patches = MeridianPresets.forVoice(MeridianVoice.SWEEP).associateBy { it.name }
        assertEquals(oldBank.keys, patches.keys, "the original SWEEP bank must stay available")
        for ((name, golden) in oldBank) {
            val patch = patches.getValue(name)
            assertEquals(2, patch.model, "$name must retain its saved acoustic version")
            val samples = patch.render().samples
            assertEquals(golden.frames, samples.size, name)
            assertEquals(golden.hash, rawHash(samples), "$name changed its accepted waveform")
            if (golden.loopStart != null) {
                val held = Meridian.renderHeld(patch.voice, patch.macros, patch.midi,
                    patch.velocity, patch.seedContext, model = 2)
                assertEquals(golden.loopStart, held.loopStartFrame, name)
                assertEquals(golden.frames, held.loopEndFrameExclusive, name)
                assertContentEquals(samples, held.snip.samples, "$name saved-model dispatch differs")
            }
        }
    }

    @Test
    fun `voice defaults and compatibility guard distinguish old and new recipes`() {
        assertEquals(2, Meridian.defaultModelFor(MeridianVoice.SWEEP))
        assertEquals(2, MeridianPatch("Old default", MeridianVoice.SWEEP, emptyMap()).model)
        for (model in 1..3) assertTrue(Meridian.supportsVoice(MeridianVoice.SWEEP, model))
        for (voice in added) {
            assertEquals(3, Meridian.defaultModelFor(voice))
            assertEquals(3, MeridianPatch("New default", voice, emptyMap()).model)
            assertTrue(Meridian.supportsVoice(voice, 3))
            for (model in listOf(0, 1, 2, 4)) {
                assertFalse(Meridian.supportsVoice(voice, model), "$voice accepted model $model")
                val error = assertFailsWith<IllegalArgumentException> {
                    MeridianPatch("Invalid voice version", voice, emptyMap(), model = model)
                }
                assertTrue(error.message.orEmpty().contains(voice.name) &&
                    error.message.orEmpty().contains(model.toString()), "voice/version guard lost its reason")
            }
            val error = assertFailsWith<IllegalArgumentException> {
                Meridian.render(voice, model = 2)
            }
            assertTrue(error.message.orEmpty().contains(voice.name))
        }
    }

    @Test
    fun `missing or null model means legacy one and never invents a new voice version`() {
        val sweep = MeridianPatch("Legacy JSON", MeridianVoice.SWEEP, mapOf("HOLD" to 1f),
            midi = 48, velocity = .65f, model = 2, seedContext = 41)
        for (nullField in listOf(false, true)) {
            val json = JsonValue.Obj(LinkedHashMap(sweep.toJsonValue().entries).apply {
                if (nullField) put("model", JsonValue.Null) else remove("model")
            })
            val decoded = assertIs<MeridianPatch>(MeridianPatch.fromJsonValue(json))
            assertEquals(sweep.copy(model = 1), decoded)
            assertFalse(Meridian.isLoop(decoded.macros, decoded.model))
            for (voice in added) {
                val newVoice = JsonValue.Obj(LinkedHashMap(json.entries).apply {
                    put("voice", JsonValue.Str(voice.name))
                })
                val error = assertFailsWith<JsonException> { MeridianPatch.fromJsonValue(newVoice) }
                assertTrue(error.message.orEmpty().contains(voice.name) &&
                    error.message.orEmpty().contains("1"), "missing model did not fail as legacy voice/version mismatch")
            }
        }
    }

    @Test
    fun `new saved voice versions survive JSON and macro edits without metadata migration`() {
        for (voice in added) {
            val patch = MeridianPatch("Complete material", voice, Meridian.defaults(voice),
                midi = 48, velocity = .65f, model = 3, seedContext = -41)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val edited = assertIs<MeridianPatch>(Patches.edited(patch, "Edited material",
                patch.macros + ("REACH" to .4f)))
            assertEquals(patch.voice, edited.voice)
            assertEquals(patch.model, edited.model)
            assertEquals(patch.midi, edited.midi)
            assertEquals(patch.velocity, edited.velocity)
            assertEquals(patch.seedContext, edited.seedContext)
            assertEquals(.4f, edited.macros.getValue("REACH"))
            val unsupported = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).apply {
                put("model", JsonValue.Num(2.0))
            })
            val error = assertFailsWith<JsonException> { MeridianPatch.fromJsonValue(unsupported) }
            assertTrue(error.message.orEmpty().contains(voice.name))
        }
    }

    @Test
    fun `explicit newer SWEEP capability retains model two finite and held audio`() {
        val bank = MeridianPresets.forVoice(MeridianVoice.SWEEP)
        for (name in listOf("FIRST ARC", "ROUND THREAD")) {
            val patch = bank.single { it.name == name }
            val newer = patch.copy(model = 3)
            assertEquals(newer, Patches.fromJsonText(newer.toJsonText()))
            assertContentEquals(patch.render().samples, newer.render().samples,
                "$name acquired a different SWEEP path under explicit version 3")
        }
    }

    private fun rawHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
}
