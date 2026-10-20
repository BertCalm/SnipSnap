package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Saved first-listen sound and audible scene changes are separate contracts. */
class TrioDramaTest {
    private val baseline = mapOf("TOUCH" to .55f, "SIDES" to (1f / 6f), "MOTION" to 0f,
        "DRAMA" to 0f, "SPACE" to .35f, "DAMP" to .48f)
    private val current = TrioConfig(seed = 47010)
    private val legacy = current.copy(model = Trio.LEGACY_MODEL_VERSION)
    private val companions = setOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun power(samples: FloatArray) = samples.sumOf { it.toDouble() * it }
    private fun normalizedDifference(a: FloatArray, b: FloatArray): Double {
        val an = sqrt(power(a)); val bn = sqrt(power(b))
        return sqrt(a.indices.sumOf { val d = a[it] / an - b[it] / bn; d * d })
    }
    private fun scene(drama: Float) = Trio.renderProbe(TrioVoice.ENSEMBLE,
        baseline + ("DRAMA" to drama), config = current)

    @Test
    fun `published model one audio remains byte identical to first-listen canaries`() {
        val canaries = mapOf(
            .7f to "68e28031ef5cb68d8af844646e7ed71413e3c209399e54576bd3ec432c3e8d0b",
            .85f to "0ce28585c9d3cee8098761135c5e42086c3860db9d09ba0f1fbe14c3284798ee",
            1f to "e0a213fcc304ce290eb8a9602f3ec1883c5a0cb1f18e7f4350e3c19044d61ef8",
        )
        for ((drama, expected) in canaries) {
            val patch = TrioPatch("Original", TrioVoice.ENSEMBLE,
                baseline + ("DRAMA" to drama), config = legacy)
            val restored = PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch as TrioPatch
            assertEquals(legacy, restored.config)
            assertEquals(expected, hash(Trio.renderProbe(restored.voice, restored.macros,
                restored.midi, restored.velocity, restored.config).samples), "Original DRAMA $drama changed")
        }
    }

    @Test
    fun `zero drama and solo identities preserve accepted sounds through the new model`() {
        for (voice in TrioVoice.entries) {
            val macros = baseline + mapOf("SIDES" to .42f, "MOTION" to .4f, "SPACE" to .6f)
            val old = Trio.renderProbe(voice, macros, 57, .6f, legacy)
            val new = Trio.renderProbe(voice, macros, 57, .6f, current)
            assertContentEquals(old.samples, new.samples, "$voice at zero DRAMA was needlessly changed")
            assertEquals(old.events, new.events)
        }
    }

    @Test
    fun `legacy missing model defaults and edits retain the original renderer`() {
        assertEquals(Trio.MODEL_VERSION, TrioConfig().model, "new scenes should use the revised renderer")
        val patch = TrioPatch("Saved", TrioVoice.ENSEMBLE, baseline + ("DRAMA" to 1f), config = legacy)
        for (value in listOf<JsonValue?>(null, JsonValue.Null)) {
            val fields = LinkedHashMap(patch.toJsonValue().entries.getValue("config").obj())
            if (value == null) fields.remove("model") else fields["model"] = value
            val obj = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).apply {
                this["config"] = JsonValue.Obj(fields)
            })
            val restored = Patches.fromJsonValue(obj) as TrioPatch
            assertEquals(legacy, restored.config)
            val edited = Patches.edited(restored, "Edited", restored.macros + ("SPACE" to .7f)) as TrioPatch
            assertEquals(1, edited.model)
            assertEquals(legacy, edited.config)
        }
    }

    @Test
    fun `the complete drama travel changes the performance after level matching`() {
        val values = listOf(0f, .25f, .5f, .75f, 1f)
        val scenes = values.map(::scene)
        for (i in 0 until scenes.lastIndex) {
            val a = scenes[i]; val b = scenes[i + 1]
            assertTrue(a.events != b.events, "DRAMA ${values[i]}..${values[i+1]} leaves the score unchanged")
            val difference = normalizedDifference(a.samples, b.samples)
            assertTrue(difference > .15, "DRAMA ${values[i]}..${values[i+1]} is only a fader: $difference")
        }
        val lowSpectra = BandDistance.whole(scenes[0].samples, scenes[2].samples, Dsp.RATE)
        val totalSpectra = BandDistance.whole(scenes[0].samples, scenes[4].samples, Dsp.RATE)
        assertTrue(lowSpectra > .02, "lower travel has no spectral consequence: $lowSpectra")
        assertTrue(totalSpectra > .15, "full DRAMA leaves the mix spectrum nearly unchanged: $totalSpectra")
    }

    @Test
    fun `model two retains its cast energy budget and current drama preserves the requested root`() {
        val full = Trio.renderProbe(TrioVoice.ENSEMBLE,
            baseline + ("DRAMA" to 1f), config = current.copy(model = 2))
        val share = full.actors.filterKeys { it in companions }.values.sumOf(::power) /
            full.actors.values.sumOf(::power)
        assertTrue(share > .25, "companion source-power share is below the design target: $share")
        for (drama in listOf(.25f, .5f, .75f, 1f)) {
            val probe = scene(drama)
            assertTrue(probe.samples.all { it.isFinite() } && probe.samples.maxOf { abs(it) } < .99f,
                "DRAMA $drama exceeds its raw budget")
            val wanted = Keys.midiHz(60)
            val measured = FineTuning.measuredHz(Snip(probe.samples, 1, Dsp.RATE), wanted,
                fromSec = 1.2f, bodySeconds = .6f)
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 25,
                "DRAMA $drama loses the requested root")
        }
    }

    @Test
    fun `saved model three gestures and entrances retain continuity at their thresholds`() {
        fun acceptedScene(drama: Float) = Trio.renderProbe(TrioVoice.ENSEMBLE,
            baseline + ("DRAMA" to drama), config = current.copy(model = 3))
        val zero = acceptedScene(0f)
        val almostZero = acceptedScene(.00001f)
        assertTrue(normalizedDifference(zero.samples, almostZero.samples) < .02,
            "an arbitrarily small DRAMA value changes accepted gestures discontinuously")
        val threshold = acceptedScene(.7f)
        val justAbove = acceptedScene(.70001f)
        assertTrue(normalizedDifference(threshold.samples, justAbove.samples) < .02,
            "the companion threshold causes a discontinuous entrance")
        assertEquals(setOf(TrioActor.TRES, TrioActor.BERIMBAU, TrioActor.CHARANGO), threshold.actors.keys)
    }

    @Test
    fun `dense full drama remains bounded at both register ends and extreme gestures`() {
        val dense = current.copy(tempoBpm = 200f, beats = 4, press = 1f,
            tremoloHz = 25f, strumMs = 30f)
        val macros = baseline + mapOf("TOUCH" to 1f, "DRAMA" to 1f,
            "MOTION" to 1f, "SPACE" to 1f, "DAMP" to 0f)
        for (midi in listOf(Trio.MIN_MIDI, Trio.MAX_MIDI)) {
            val probe = Trio.renderProbe(TrioVoice.ENSEMBLE, macros, midi, config = dense)
            assertTrue(probe.samples.all { it.isFinite() } && probe.samples.maxOf { abs(it) } < .99f,
                "dense DRAMA at MIDI $midi exceeded raw headroom")
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(Snip(probe.samples, 1, Dsp.RATE), wanted,
                fromSec = 1.8f, bodySeconds = .7f)
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 25,
                "dense DRAMA at MIDI $midi loses its settled root")
        }
    }
}
