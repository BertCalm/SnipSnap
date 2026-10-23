package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChamberTimpaniTest {
    @Test
    fun `published model one and two recipes retain their exact raw PCM`() {
        // Captured published WAVs, independent of the current defaults.
        val goldens = listOf(
            Triple(1, "hybrid-default", "637eafc74386232080ca84e50484ae130fae9e897e05550f007b2a5deba99fef"),
            Triple(1, "aquarium-default", "3da82fcf4a2c31369c367bcc63bc7b4f699c464ce309379c7611c2c6017720ae"),
            Triple(2, "hybrid-timpani-r2-c3-full", "1115f933685f3bef8dec047b55a05284d8d612fbf5958e269d621c03f454a262"),
            Triple(2, "aquarium-timpani-r2-c3-full", "2eac9d70ada930c97af4599bc26b3027012178bf73f15549048dd79fa1d48025"),
        )
        for ((model, id, expected) in goldens) {
            val text = javaClass.getResource("/chamber-model-$model/$id.json")!!.readText()
            val recipe = PadRecipe.fromJsonText(text)
            val p = recipe.patch as ChamberPatch
            assertEquals(model, p.model)
            assertEquals(p, PadRecipe.fromJsonText(recipe.toJsonText()).patch)
            assertEquals(model, (p.withMacros(p.macros + ("STRIKE" to .6f)) as ChamberPatch).model)
            val raw = Chamber.probe(p.voice, p.macros, p.midi, p.velocity, p.configuration, p.seed, p.model,
                Chamber.ProbeOptions(recordStems = false, recordDiagnostics = false)).rawMix
            val bytes = ByteArrayOutputStream().also {
                WavWriter.write(it, Snip(raw, 1, Chamber.RATE), WavWriter.BitDepth.PCM_16)
            }.toByteArray()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, sha, "$id changed the previously published performance")
        }
    }

    @Test
    fun `mix lift is three dB on membrane pickup with identical voicing and room state`() {
        for (voice in ChamberVoice.entries) {
            val options = Chamber.ProbeOptions(seconds = .6f)
            val accepted = Chamber.probe(voice, model = 2, options = options)
            val lifted = Chamber.probe(voice, model = 3, options = options)
            val a = accepted.instrumentStems[0]
            val b = lifted.instrumentStems[0]
            val ratio = rms(b, .02, .55) / rms(a, .02, .55)
            assertTrue(abs(20 * log10(ratio) - 3.0) < .001, "$voice lift was ${20 * log10(ratio)} dB")
            var error = 0.0
            var energy = 0.0
            for (i in a.indices) {
                error += (b[i] - a[i] * ratio).let { it * it }
                energy += b[i].toDouble() * b[i]
            }
            assertTrue(sqrt(error / energy) < 1e-5, "$voice membrane shape changed")
            for (i in 1..3) assertContentEquals(accepted.instrumentStems[i], lifted.instrumentStems[i])
            assertContentEquals(accepted.surface, lifted.surface)
            assertContentEquals(accepted.late, lifted.late)
            assertEquals(accepted.diagnostics.energy, lifted.diagnostics.energy)
            assertEquals(accepted.diagnostics.primaryWork, lifted.diagnostics.primaryWork)
            assertEquals(accepted.geometry, lifted.geometry)
            assertEquals(accepted.diagnostics.sharedStrikeTimes, lifted.diagnostics.sharedStrikeTimes)
        }
    }

    @Test
    fun `revised timpani has an audible pitched body beside the bar`() {
        for (voice in ChamberVoice.entries) for (midi in listOf(48, 60)) {
            val p = Chamber.probe(voice, midi = midi, model = 2,
                options = Chamber.ProbeOptions(seconds = .8f, roomEnabled = false))
            val membrane = p.instrumentStems[0]
            val bar = p.instrumentStems[1]
            val listenerRatio = p.geometry.directPaths[0].gain / p.geometry.directPaths[1].gain
            val bodyRatio = listenerRatio * rms(membrane, .05, .5) / rms(bar, .05, .5)
            val remainingBody = rms(membrane, .35, .7) / rms(membrane, .02, .12)
            println("CHAMBER timpani $voice MIDI$midi body/bar=$bodyRatio later/attack=$remainingBody")
            // Numerical masking guard, followed by shared-gain listening pairs.
            // This catches the old near-inaudible, overdamped membrane without
            // claiming that RMS alone establishes a convincing timpani timbre.
            assertTrue(bodyRatio in .5..2.0, "$voice MIDI$midi timpani body/bar=$bodyRatio")
            assertTrue(remainingBody > .025, "$voice MIDI$midi lost its pitched decay: $remainingBody")
        }
    }

    @Test
    fun `membrane revision preserves the other three dry sources and shared strike`() {
        val options = Chamber.ProbeOptions(seconds = .3f, roomEnabled = false)
        val old = Chamber.probe(ChamberVoice.HYBRID, seed = 17, model = 1, options = options)
        val revised = Chamber.probe(ChamberVoice.HYBRID, seed = 17, model = 2, options = options)
        for (i in 1..3) assertContentEquals(old.instrumentStems[i], revised.instrumentStems[i],
            "model 2 changed dry source $i")
        assertEquals(old.geometry, revised.geometry)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), revised.diagnostics.sharedStrikeTimes)
    }

    private fun rms(samples: FloatArray, from: Double, to: Double): Double {
        val start = (from * Chamber.RATE).toInt()
        val end = (to * Chamber.RATE).toInt().coerceAtMost(samples.size)
        var sum = 0.0
        for (i in start until end) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (end - start))
    }
}
