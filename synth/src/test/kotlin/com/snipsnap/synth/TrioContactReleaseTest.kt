package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Promotion fidelity and saved-sound compatibility, independent of a listening verdict. */
class TrioContactReleaseTest {
    private val old = TrioConfig(seed = 47010, model = 6)
    private val current = old.copy(model = 7)
    private val companions = setOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)
    private fun controls(touch: Float, drama: Float) = Trio.defaults(TrioVoice.ENSEMBLE) + mapOf(
        "TOUCH" to touch, "DRAMA" to drama, "SIDES" to (1f / 6f),
    )

    private fun assertProbeEquals(expected: TrioProbe, actual: TrioProbe, label: String) {
        assertEquals(expected.events, actual.events, "$label gestures")
        assertEquals(expected.actors.keys, actual.actors.keys, "$label actors")
        assertContentEquals(expected.samples, actual.samples, "$label raw mix")
        for (actor in expected.actors.keys)
            assertContentEquals(expected.actors.getValue(actor), actual.actors.getValue(actor), "$label $actor")
    }

    private fun finish(probe: TrioProbe, velocity: Float): FloatArray = probe.samples.copyOf().also { x ->
        Dsp.levelTo(x, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        for (i in x.indices) x[i] *= velocity
        Dsp.fadeTail(x)
    }

    @Test
    fun `model seven exactly promotes every heard upper touch scene and actor contribution`() {
        for (drama in listOf(.5f, 1f)) for (touch in listOf(.8f, .9f, 1f)) {
            val macros = controls(touch, drama)
            val prototype = TrioContactProfile.render(macros, config = old)
            val promoted = Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = current)
            assertProbeEquals(prototype, promoted, "TOUCH $touch / DRAMA $drama")
            assertEquals(TrioActor.entries.toSet(), promoted.actors.keys)
            for (actor in companions) assertEquals(1, promoted.events.count { it.actor == actor }, actor.name)
        }
    }

    @Test
    fun `saved model six high touch audio retains the published PCM24 canaries`() {
        for (case in TrioContactReleaseFixtures.modelSix) {
            val output = Trio.render(TrioVoice.ENSEMBLE, controls(case.touch, case.drama), config = old)
            val monitor = Snip(FloatArray(output.samples.size) { output.samples[it] * .25f }, 1, Dsp.RATE)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, monitor) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(case.sha256, hash,
                "${TrioContactReleaseFixtures.ROUND}: saved model6 TOUCH ${case.touch}/DRAMA ${case.drama}")
        }
    }

    @Test
    fun `new native recipes retain the promoted contact root velocity and seeded performance`() {
        assertEquals(7, Trio.MODEL_VERSION)
        assertEquals(7, TrioConfig().model)
        val rich = current.copy(seed = -91827, tempoBpm = 140f, beats = 3, press = .7f,
            tremoloHz = 17f, strumMs = 23f)
        val macros = controls(.95f, .5f) + mapOf("SIDES" to .42f, "MOTION" to .7f,
            "SPACE" to .8f, "DAMP" to .3f)
        val patch = TrioPatch("Promoted Contact", TrioVoice.ENSEMBLE, macros, 57, .63f, rich)
        val restored = assertIs<TrioPatch>(Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch, restored)
        assertEquals(patch.toJsonText(), restored.toJsonText())
        assertEquals(1, restored.toJsonValue().entries.getValue("version").int(), "JSON schema is independent of synthesis model")
        val recipe = PadRecipe(patch)
        val saved = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, saved)
        assertEquals(7, assertIs<TrioPatch>(saved.patch).config.model)
        val reference = TrioContactProfile.render(macros, 57, .63f, rich.copy(model = 6))
        val native = saved.render()
        assertContentEquals(finish(reference, .63f), native.samples,
            "native model7 finishing/velocity must preserve the approved source contact")
        assertEquals(1, native.channels)
        assertEquals(Dsp.RATE, native.sampleRate)
        assertTrue(native.samples.all { it.isFinite() } && native.peak() <= .99001f)
    }

    @Test
    fun `missing or null synthesis model remains legacy even in a high touch ensemble recipe`() {
        val patch = TrioPatch("Legacy Default", TrioVoice.ENSEMBLE, controls(1f, 1f), config = current)
        val variants = listOf<JsonValue?>(null, JsonValue.Null, JsonValue.Obj(emptyMap()),
            JsonValue.Obj(mapOf("seed" to JsonValue.Num(47010.0))),
            JsonValue.Obj(mapOf("model" to JsonValue.Null, "seed" to JsonValue.Num(47010.0))))
        for (configValue in variants) {
            val fields = LinkedHashMap(patch.toJsonValue().entries)
            if (configValue == null) fields.remove("config") else fields["config"] = configValue
            val restored = assertIs<TrioPatch>(TrioPatch.fromJsonValue(JsonValue.Obj(fields)))
            assertEquals(1, restored.config.model, "a missing model must not opt an old recipe into contact")
            val seed = if (configValue is JsonValue.Obj && "seed" in configValue.entries) 47010 else TrioConfig(model = 1).seed
            assertEquals(TrioConfig(seed = seed, model = 1), restored.config)
            assertEquals(restored, assertIs<TrioPatch>(PadRecipe.fromJsonText(PadRecipe(restored).toJsonText()).patch))
        }
    }

    @Test
    fun `lower touch renders are unchanged and models cannot share mutable audio through the score cache`() {
        for (touch in listOf(0f, .55f)) {
            val macros = controls(touch, 1f)
            assertProbeEquals(Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = old),
                Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = current), "TOUCH $touch bypass")
        }
        val macros = controls(1f, 1f) + mapOf("SIDES" to .42f, "MOTION" to .7f)
        val earlier = Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = old)
        val promoted = Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = current)
        assertEquals(earlier.events, promoted.events)
        assertFalse(earlier.samples.contentEquals(promoted.samples), "high touch still selects the old renderer")
        val expected = TrioProbe(promoted.samples.copyOf(), promoted.actors.mapValues { it.value.copyOf() }, promoted.events.toList())
        promoted.samples.fill(123f)
        promoted.actors.values.forEach { it.fill(-123f) }
        // Evict the single cached score with a different model, root and playing intensity.
        Trio.score(TrioVoice.ENSEMBLE, controls(.9f, .5f), 72, .4f, old.copy(seed = 31))
        assertProbeEquals(earlier, Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = old), "old replay after7")
        assertProbeEquals(expected, Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = current), "new replay after6")
    }

    @Test
    fun `contact approaches the old renderer continuously as drama tends to zero`() {
        assertEquals(0f, TrioContact.amount(1f, 0f))
        assertEquals(.4f, TrioContact.amount(1f, .25f), 1e-7f)
        assertEquals(.8f, TrioContact.amount(1f, .5f))
        assertEquals(.8f, TrioContact.amount(1f, 1f))
        assertEquals(0f, TrioContact.amount(.8f, 1f))
        assertFailsWith<IllegalArgumentException> { TrioContact.amount(Float.NaN, .5f) }
        assertFailsWith<IllegalArgumentException> { TrioContact.amount(1f, Float.NaN) }
        val macros = controls(1f, .001f)
        val before = Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = old)
        val after = Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = current)
        assertEquals(before.events, after.events)
        val referencePower = before.samples.sumOf { it.toDouble() * it }
        val differencePower = before.samples.indices.sumOf {
            val delta = before.samples[it].toDouble() - after.samples[it]
            delta * delta
        }
        assertTrue(referencePower > 0.0)
        // Float string/body processing measured a 6.3e-5 relative residual here.
        // A 1e-3 bound leaves margin above that measured residual; the untapered reference measured .819.
        val relativeDifference = sqrt(differencePower / referencePower)
        assertTrue(relativeDifference < 1e-3,
            "tiny DRAMA must not switch on full contact: relative difference $relativeDifference")
    }

    @Test
    fun `active production contact retains finite headroom and single hit companions at register corners`() {
        val dense = current.copy(tempoBpm = 120f, beats = 4, press = 1f, tremoloHz = 5f, strumMs = 30f)
        for (midi in listOf(48, 84)) for (damp in listOf(0f, 1f)) {
            val macros = controls(1f, 1f) + mapOf("DAMP" to damp, "SIDES" to (2f / 3f),
                "MOTION" to 1f, "SPACE" to 1f)
            val probe = Trio.renderProbe(TrioVoice.ENSEMBLE, macros, midi, config = dense)
            assertEquals((3.5f * Dsp.RATE).toInt(), probe.samples.size)
            assertTrue(probe.samples.all { it.isFinite() && abs(it) < .99f }, "MIDI $midi / DAMP $damp headroom")
            assertTrue(abs(probe.samples.average()) < 1e-3, "MIDI $midi / DAMP $damp DC")
            assertTrue(probe.samples.any { abs(it) > 1e-5f }, "MIDI $midi / DAMP $damp silence")
            for (actor in companions) assertEquals(1, probe.events.count { it.actor == actor }, actor.name)
            for ((actor, tap) in probe.actors) {
                assertEquals(probe.samples.size, tap.size, actor.name)
                assertTrue(tap.all { it.isFinite() }, actor.name)
            }
        }
    }
}
