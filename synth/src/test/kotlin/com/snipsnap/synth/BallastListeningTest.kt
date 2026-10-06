package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression checks for the controls called out in the first listening verdict. */
class BallastListeningTest {
    private fun run(voice: BallastVoice, changes: Map<String, Float>, velocity: Double = 1.0): Ballast.Run {
        val macros = Ballast.defaults(voice) + mapOf("TUNE" to 12 / 36f, "HOLD" to 0f) + changes
        val plan = Ballast.oneShotPlan(voice, Ballast.frequencyFor(voice, macros.getValue("TUNE")).toDouble(), macros)
        return Ballast.simulate(voice, plan, macros, velocity, Ballast.Probe(record = true))
    }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    @Test
    fun `PRISM keeps the stored GLINT identity and its deterministic seed`() {
        val patch = BallastPresets.forVoice(BallastVoice.GLINT).first()
        assertEquals("PRISM", patch.voice.displayName)
        assertEquals("GLINT", patch.voiceName)
        assertEquals("GLINT", patch.voice.toString())
        val alias = patch.toJsonText().replace("\"voice\": \"GLINT\"", "\"voice\": \"PRISM\"")
        assertTrue(alias.contains("\"PRISM\""), "alias fixture did not replace the voice")
        val restored = BallastPatch.fromJsonText(alias)
        assertEquals(patch, restored)
        assertContentEquals(patch.render().samples, restored.render().samples)
        assertEquals(BallastPresets.forVoice(BallastVoice.GLINT), Presets.forVoice("BALLAST", "PRISM"))
    }

    @Test
    fun `structural controls preserve the direct bass waveform`() {
        for (voice in listOf(BallastVoice.ROOT, BallastVoice.DEEP)) {
            val macros = Ballast.defaults(voice) + mapOf("TUNE" to 12 / 36f, "HOLD" to 0f)
            val plan = Ballast.oneShotPlan(voice, Ballast.frequencyFor(voice, macros.getValue("TUNE")).toDouble(), macros)
            val quiet = Ballast.simulate(voice, plan, macros + mapOf("GLASS" to 0f, "SPAN" to 0f, "FRAME" to 0f), probe = Ballast.Probe(record = true))
            val open = Ballast.simulate(voice, plan, macros + mapOf("GLASS" to 1f, "SPAN" to 1f, "FRAME" to 1f), probe = Ballast.Probe(record = true))
            assertContentEquals(quiet.parts!!.direct, open.parts!!.direct, "$voice: the bass oscillator or filter changed")
        }
    }

    @Test
    fun `middle GLASS produces a quiet audible contact before the dense setting`() {
        val root = listOf(.15f, .4f, .75f).map { glass ->
            rms(run(BallastVoice.ROOT, mapOf("GLASS" to glass, "FRAME" to .6f)).parts!!.glass)
        }
        assertTrue(root[1] > .0003, "middle GLASS is still silent: $root")
        assertTrue(root[1] > root[0] * 2 && root[2] > root[1] * 2, "GLASS does not graduate: $root")
        val prism = listOf(.25f, .5f, .85f).map { glass ->
            rms(run(BallastVoice.GLINT, mapOf("GLASS" to glass, "FRAME" to .6f)).parts!!.glass)
        }
        assertTrue(prism[0] > .0003 && prism[1] > prism[0] * 2 && prism[2] > prism[1] * 2, "PRISM glass collapses to one intensity: $prism")
    }

    private fun aboveOctaveShare(samples: FloatArray): Double {
        val spectrum = Fft.magnitudeSpectrum(samples.copyOfRange(4410, 4410 + 16384), 16384)
        val start = (130.0 * 16384 / Dsp.RATE).toInt()
        val total = spectrum.sumOf { it.toDouble() * it }
        return spectrum.drop(start).sumOf { it.toDouble() * it } / total
    }

    @Test
    fun `SPAN exposes upper wire octaves in the full bass mix`() {
        val narrow = Ballast.condition(run(BallastVoice.WIRE, mapOf("SPAN" to 0f, "GLASS" to 0f)).raw)
        val wide = Ballast.condition(run(BallastVoice.WIRE, mapOf("SPAN" to 1f, "GLASS" to 0f)).raw)
        val lo = aboveOctaveShare(narrow)
        val hi = aboveOctaveShare(wide)
        assertTrue(hi > lo * 2 && hi > .04, "upper octave share barely changes: $lo -> $hi")
    }

    @Test
    fun `FRAME changes the full mix after matching levels`() {
        val tight = Ballast.render(BallastVoice.ROOT, mapOf("TUNE" to 12 / 36f, "FRAME" to 0f, "GLASS" to 0f)).samples
        val loose = Ballast.render(BallastVoice.ROOT, mapOf("TUNE" to 12 / 36f, "FRAME" to 1f, "GLASS" to 0f)).samples
        val n = minOf(tight.size, loose.size)
        val a = rms(tight.copyOf(n))
        val b = rms(loose.copyOf(n))
        val difference = sqrt((0 until n).sumOf { val d = tight[it] / a - loose[it] / b; d * d } / n)
        assertTrue(difference > .15, "FRAME is buried under the bass: $difference")
    }

    @Test
    fun `soft velocity changes bass brightness and structural drive without extra gain matching`() {
        val soft = run(BallastVoice.ROOT, emptyMap(), .3)
        val hard = run(BallastVoice.ROOT, emptyMap(), 1.0)
        val softDirect = Ballast.condition(soft.parts!!.direct)
        val hardDirect = Ballast.condition(hard.parts!!.direct)
        assertTrue(aboveOctaveShare(hardDirect) > aboveOctaveShare(softDirect) * 1.5, "a soft note's bass filter remains almost as bright")
        assertTrue(rms(hard.parts.strings) > rms(soft.parts.strings) * 2, "soft touch still shakes the structure as hard")
        assertTrue(soft.raw.all { abs(it) < 2f } && hard.raw.all { abs(it) < 2f })
    }

    @Test
    fun `extreme structural pickup stays bounded and its recorded stems sum to the mix`() {
        val extreme = run(BallastVoice.WIRE, mapOf("TUNE" to 1f, "DRIVE" to 1f, "SYMPATHY" to 1f,
            "SPAN" to 1f, "GLASS" to 1f, "FRAME" to 1f))
        val parts = extreme.parts!!
        for (i in extreme.raw.indices) {
            assertTrue(abs(parts.frame[i] + parts.strings[i]) <= Ballast.STRUCTURE_KNEE + 1e-6, "unbounded structural pickup")
            val sum = parts.direct[i] + parts.frame[i] + parts.strings[i] + parts.glass[i]
            assertEquals(extreme.raw[i], sum, 2e-7f, "recorded stems lost the pickup's common gain")
        }
        assertTrue(extreme.raw.all { it.isFinite() && abs(it) < 2f })
    }
}
