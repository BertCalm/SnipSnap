package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved-sound compatibility and the heard periodic-pulse reference form separate contracts. */
class RelayModelsTest {
    @TempDir lateinit var temp: File

    private val references by lazy {
        val text = javaClass.getResourceAsStream("/relay-model2-audition-reference.json")!!
            .bufferedReader().use { it.readText() }
        Json.parse(text).obj().getValue("clips").arr().map { it.obj() }
    }

    @Test
    fun `missing and explicit legacy model keep native and normalized saved audio`() {
        for (voice in RelayVoice.entries) {
            val original = RelayPresets.forVoice(voice).first()
            val json = original.toJsonValue()
            val absent = RelayPatch.fromJsonValue(JsonValue.Obj(json.entries - "model")) as RelayPatch
            val nullModel = RelayPatch.fromJsonValue(JsonValue.Obj(json.entries + ("model" to JsonValue.Null))) as RelayPatch
            assertEquals(RelayPatch.LEGACY_MODEL_VERSION, absent.model)
            assertEquals(original, absent)
            assertEquals(original, nullModel)
            for (normalized in listOf(false, true)) {
                val expected = Relay.render(voice, original.macros, original.velocity, normalized, original.phrase)
                val actual = RelayModels.inspect(voice, absent.macros, absent.velocity, normalized, absent.phrase, absent.model)
                assertContentEquals(expected.samples, actual.snip.samples, "$voice legacy normalized=$normalized")
                assertNull(actual.pulse)
                assertEquals(actual.base.accents, actual.accents)
            }
        }
        assertTrue(RelayPresets.all().all { it.model == RelayPatch.LEGACY_MODEL_VERSION })
        assertTrue(SynthKits.relay().filterNotNull().all { pad ->
            (PadRecipe.fromJsonValue(assertNotNull(pad.recipe)).patch as RelayPatch).model == RelayPatch.LEGACY_MODEL_VERSION
        })
    }

    @Test
    fun `fresh patches save the periodic model while copies velocity and replay retain their model`() {
        val fresh = RelayPatch("Periodic", RelayVoice.CHIME, Relay.defaults(RelayVoice.CHIME))
        assertEquals(2, fresh.model)
        for (model in 1..2) {
            val patch = fresh.copy(model = model)
            val replay = RelayPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, replay)
            assertEquals(model, patch.withMacros(patch.macros + ("PULSE" to .75f)).let { it as RelayPatch }.model)
            val expected = RelayModels.render(patch.voice, patch.macros, .35f, phrase = patch.phrase, model = model)
            assertContentEquals(expected.samples, Velocity.atVelocity(patch, .35f).samples, "velocity model$model")
        }
        val root = fresh.toJsonValue()
        val error = assertFailsWith<JsonException> {
            RelayPatch.fromJsonValue(JsonValue.Obj(root.entries + ("model" to JsonValue.Num(99.0))))
        }
        assertTrue(error.message.orEmpty().contains("unsupported relay model 99"))
    }

    @Test
    fun `periodic model native renders reproduce the published depth audition exactly`() {
        for (reference in references) {
            val voice = RelayVoice.valueOf(reference.getValue("voice").str())
            val source = RelayPresets.all().single { it.name == reference.getValue("preset").str() }
            val pulse = reference.getValue("pulse").num().toFloat()
            val report = RelayModels.inspect(voice, source.macros + ("PULSE" to pulse), source.velocity,
                normalize = false, phrase = source.phrase, model = 2)
            val file = WavWriter.write(File(temp, "${voice.name}_$pulse.wav"), report.snip, WavWriter.BitDepth.PCM_24)
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(reference.getValue("rawSha256").str(), actual, "$voice PULSE$pulse changed after audition")
            assertEquals(emptyList(), report.accents, "serialized sparse accents must not masquerade as the periodic pulse")
            assertEquals(3.0, assertNotNull(report.pulse).rateHz)
        }
    }

    @Test
    fun `zero depth is exact and middle depth changes shape without moving source histories`() {
        for (voice in RelayVoice.entries) {
            val source = RelayPresets.forVoice(voice).first()
            for (normalized in listOf(false, true)) {
                val macros = source.macros + ("PULSE" to 0f)
                assertContentEquals(Relay.render(voice, macros, source.velocity, normalized, source.phrase).samples,
                    RelayModels.render(voice, macros, source.velocity, normalized, source.phrase, 2).samples,
                    "$voice zero depth normalized$normalized")
            }
            val flat = RelayModels.inspect(voice, source.macros + ("PULSE" to 0f), normalize = false, phrase = source.phrase, model = 2)
            val middle = RelayModels.inspect(voice, source.macros + ("PULSE" to .5f), normalize = false, phrase = source.phrase, model = 2)
            val full = RelayModels.inspect(voice, source.macros + ("PULSE" to 1f), normalize = false, phrase = source.phrase, model = 2)
            assertEquals(flat.weights, middle.weights)
            assertEquals(flat.weights, full.weights)
            assertContentEquals(flat.snip.samples, middle.base.snip.samples, "middle must use the identical steady source")
            assertContentEquals(flat.snip.samples, full.base.snip.samples, "full must use the identical steady source")
            assertEquals(flat.actualHz, full.actualHz)
            assertEquals(flat.snip.frameCount, full.snip.frameCount)
            // Matching whole-note RMS removes a scalar gain; a static attenuation fails this assertion.
            val a = shapeDifference(flat.snip.samples, middle.snip.samples)
            val b = shapeDifference(flat.snip.samples, full.snip.samples)
            assertTrue(a > .08 && b > a + .06, "$voice expected ordered gain-independent movement, got $a / $b")
        }
    }

    @Test
    fun `periodic note keeps excitation velocity and deterministic output at both ends of the keyboard`() {
        for (voice in RelayVoice.entries) for (tune in listOf(0f, 1f)) {
            val macros = Relay.defaults(voice) + mapOf("PULSE" to 1f, "TUNE" to tune, "COLOR" to 1f)
            val first = RelayModels.inspect(voice, macros, .3f, false, model = 2)
            val second = RelayModels.inspect(voice, macros, .3f, false, model = 2)
            val hard = RelayModels.inspect(voice, macros, 1f, false, model = 2)
            assertContentEquals(first.snip.samples, second.snip.samples)
            assertTrue(hard.snip.peak() > first.snip.peak(), "$voice velocity should still reach excitation energy")
            assertTrue(RelayModels.render(voice, macros, 0f, false, model = 2).samples.all { it == 0f })
            for (report in listOf(first, hard)) {
                assertTrue(report.snip.samples.all { it.isFinite() })
                assertTrue(report.snip.peak() <= report.base.snip.peak() + 1e-7f)
                assertTrue(abs(report.snip.samples.sumOf { it.toDouble() } / report.snip.frameCount) < .005)
                // A completed fade may retain IEEE negative zero; both signs are silent.
                assertTrue(report.snip.samples.last() == 0f, "the finite release must end at zero")
            }
        }
    }

    @Test
    fun `held periodic source closes against a real preceding cycle at compact and broad phrase boundaries`() {
        for (voice in RelayVoice.entries) for (compact in listOf(false, true)) {
            val source = Relay.phraseFor(voice)
            val phrase = if (compact) source.copy(tempoBpm = 180f,
                slots = source.slots.map { it.copy(beats = .25f, transitionBeats = 2f) },
                accents = listOf(RelayAccent(0f, 1f), RelayAccent(.5f, .5f))) else source
            val macros = Relay.defaults(voice) + mapOf("PULSE" to 1f, "HOLD" to 1f, "BLEND" to 1f)
            val report = RelayModels.inspect(voice, macros, normalize = true, phrase = phrase, model = 2)
            val pulse = assertNotNull(report.pulse)
            assertTrue(report.loop && pulse.cycles != null)
            assertEquals(pulse.cycles!!.toDouble(), pulse.rateHz * report.phraseSeconds, 1e-10)
            assertEquals(report.snip.frameCount, report.previousCycle.size)
            assertTrue(report.seamError < Keys.MAX_SEAM_ERROR, "$voice compact$compact seam${report.seamError}")
            assertEquals(report.seamError, Keys.seamError(report.previousCycle + report.snip.samples, report.previousCycle.size))
            assertTrue(report.snip.samples.all { it.isFinite() } && report.snip.peak() <= 1f)
            assertTrue(abs(report.snip.samples.sumOf { it.toDouble() } / report.snip.frameCount) < .005)
        }
    }

    @Test
    fun `short periodic notes remain pitched material rather than drum families`() {
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        for (voice in RelayVoice.entries) for (tune in listOf(0f, 1f)) {
            val source = Relay.phraseFor(voice)
            val minimum = source.copy(tempoBpm = 180f,
                slots = source.slots.map { it.copy(beats = .25f, transitionBeats = .12f) },
                accents = listOf(RelayAccent(0f, 1f), RelayAccent(.5f, .5f)))
            val macros = Relay.defaults(voice) + mapOf("PULSE" to 1f, "TUNE" to tune,
                "LENGTH" to 0f, "COLOR" to .8f, "HOLD" to 0f)
            val snip = RelayModels.render(voice, macros, phrase = minimum, model = 2)
            // At about 1.15 s this cannot pass by falling into the classifier's >1.5 s LOOP bucket.
            assertTrue(snip.durationSeconds < 1.5, "$voice fixture must exercise the pitched classifier")
            val classified = Classifier.classify(snip)
            assertTrue(classified.drumClass !in drums,
                "$voice TUNE$tune periodic minimum phrase classified as $classified")
        }
    }

    private fun shapeDifference(a: FloatArray, b: FloatArray): Double {
        fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
        val ar = rms(a); val br = rms(b)
        return sqrt(a.indices.sumOf { val d = a[it] / ar - b[it] / br; d * d } / a.size)
    }
}
