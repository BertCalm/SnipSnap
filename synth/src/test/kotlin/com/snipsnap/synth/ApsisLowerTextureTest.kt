package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.*

class ApsisLowerTextureTest {
    private val options = Apsis.ProbeOptions(normalize = false, recordStems = false, recordTraces = false)
    private fun sha(samples: FloatArray): String {
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, Snip(samples, 1, Dsp.RATE)) }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test fun `native model four reproduces approved audition PCM and model three keeps its reference`() {
        val fixture = javaClass.getResource("/apsis-lower-texture/audition-references.json")!!.readText()
        val cases = Json.parse(fixture).obj().getValue("cases").arr()
        assertEquals(12, cases.size)
        for (entry in cases) {
            val row = entry.obj()
            val patch = ApsisPatch.fromJsonValue(row.getValue("patch"))
            val probe = Apsis.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, options)
            val gain = row.getValue("output_gain").num().toFloat()
            val played = FloatArray(probe.samples.size) { probe.samples[it] * gain }
            assertEquals(row.getValue("expected_wav_sha256").str(), sha(played), row.getValue("id").str())
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertTrue(probe.samples.all { it.isFinite() })
            assertTrue(probe.seamError < Keys.MAX_SEAM_ERROR)
            assertTrue(probe.stateError < 1e-8)
            assertTrue(probe.diagnostics.maxUnforcedEnergyRise < 1e-10)
        }
    }

    @Test fun `new grain defaults opt in while old and incomplete saved recipes stay on their recorded model`() {
        val fresh = ApsisPatch("Fresh grain", ApsisVoice.GRAIN, Apsis.defaults(ApsisVoice.GRAIN))
        assertEquals(4, fresh.config.modelVersion)
        assertEquals(4, Apsis.configurationFor(ApsisVoice.GRAIN).modelVersion)
        assertEquals(listOf(4, 4, 4), ApsisPresets.forVoice(ApsisVoice.GRAIN).map { it.config.modelVersion })
        for (voice in listOf(ApsisVoice.PASSAGE, ApsisVoice.ANCHOR)) {
            assertEquals(3, Apsis.configurationFor(voice).modelVersion)
            assertTrue(ApsisPresets.forVoice(voice).all { it.config.modelVersion == 3 })
            assertFailsWith<IllegalArgumentException> {
                ApsisPatch("Invalid $voice", voice, Apsis.defaults(voice), config = ApsisConfig(4))
            }
        }
        for (optional in listOf("", ",\"config\":null", ",\"config\":{}", ",\"config\":{\"modelVersion\":null}")) {
            val old = ApsisPatch.fromJsonText("""{"engine":"APSIS","version":1,"name":"Saved grain","voice":"GRAIN","macros":{}$optional}""")
            assertEquals(3, old.config.modelVersion)
            assertEquals(3, (Patches.edited(old, "Edited old grain", old.macros) as ApsisPatch).config.modelVersion)
        }
        val unknown = JsonValue.Obj(fresh.toJsonValue().entries + ("config" to JsonValue.Obj(mapOf(
            "modelVersion" to JsonValue.Num(5.0)))))
        val error = assertFailsWith<JsonException> { ApsisPatch.fromJsonValue(unknown) }
        assertEquals("unsupported APSIS model 5", error.message)
    }

    @Test fun `selected model retains native touch and full patch configuration through replay`() {
        val source = ApsisPresets.forVoice(ApsisVoice.GRAIN).first().copy(midi = 43, config = ApsisConfig(4, -91))
        for (velocity in listOf(.35f, .65f, 1f)) {
            val expected = source.copy(velocity = velocity).render()
            assertContentEquals(expected.samples, Velocity.atVelocity(source, velocity).samples)
            val edited = Patches.edited(source, "My lower grain", source.macros + ("ALIGN" to .2f)) as ApsisPatch
            assertEquals(source.config, edited.config)
            assertEquals(source.midi, edited.midi)
            assertContentEquals(edited.render().samples, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).render().samples)
        }
    }

    @Test fun `texture trim preserves contact timing and remains independent of geometry caching`() {
        val patch = ApsisPresets.forVoice(ApsisVoice.GRAIN)[1]
        val short = options.copy(seconds = 1.5f)
        ApsisPopulation.clearPreparationCache()
        val old = Apsis.probe(patch.voice, patch.macros, patch.midi, patch.velocity, ApsisConfig(3), short)
        val current = Apsis.probe(patch.voice, patch.macros, patch.midi, patch.velocity, ApsisConfig(4), short)
        val uncached = Apsis.probe(patch.voice, patch.macros, patch.midi, patch.velocity, ApsisConfig(4), short.copy(usePreparationCache = false))
        assertTrue(current.diagnostics.preparationCacheHit)
        assertContentEquals(current.samples, uncached.samples)
        assertContentEquals(old.diagnostics.transientCounts, current.diagnostics.transientCounts)
        assertEquals(old.diagnostics.sourceEndSeconds, current.diagnostics.sourceEndSeconds)
        assertFalse(old.samples.contentEquals(current.samples), "selected model must change material balance")
        for (midi in listOf(36, 60, 84)) {
            val result = Apsis.probe(patch.voice, patch.macros, midi, .65f, ApsisConfig(4), short.copy(normalize = true))
            assertTrue(result.samples.all { it.isFinite() && abs(it) <= 1.001f })
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(Snip(result.samples, 1, Dsp.RATE), wanted, .2f, .8f)
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 50, "GRAIN model 4 MIDI $midi root")
        }
    }
}
