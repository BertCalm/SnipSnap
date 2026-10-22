package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChamberTimpaniTest {
    @Test
    fun `published model one recipes retain their exact raw PCM`() {
        // Captured from the two published 2026-10-18 first-listen WAVs. These
        // fixtures predate model 2 and deliberately do not use current defaults.
        val goldens = mapOf(
            "hybrid-default" to "637eafc74386232080ca84e50484ae130fae9e897e05550f007b2a5deba99fef",
            "aquarium-default" to "3da82fcf4a2c31369c367bcc63bc7b4f699c464ce309379c7611c2c6017720ae",
        )
        for ((id, expected) in goldens) {
            val text = javaClass.getResource("/chamber-model-1/$id.json")!!.readText()
            val recipe = PadRecipe.fromJsonText(text)
            val p = recipe.patch as ChamberPatch
            assertEquals(1, p.model)
            assertEquals(p, PadRecipe.fromJsonText(recipe.toJsonText()).patch)
            assertEquals(1, (p.withMacros(p.macros + ("STRIKE" to .6f)) as ChamberPatch).model)
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
