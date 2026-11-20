package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TERRA's stick voices ([TerraSticks], docs/TERRA_STICKS.md): the claims that are theirs alone. Determinism, the
 * patch round trip, the presets' contracts and the onset-brightness law are held with every other TERRA voice in
 * DeterminismTest, TerraPatchTest, TerraPresetsTest and StrikeVelocityTest.
 */
class TerraSticksTest {
    private val voices = TerraSticks.VOICES.toList()

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until minOf(to, x.size)) e += x[i].toDouble() * x[i]
        return sqrt(e / (minOf(to, x.size) - from).coerceAtLeast(1))
    }

    /** How far two renders are apart: the RMS of their difference against the RMS of the first. */
    private fun distance(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var e = 0.0
        for (i in a.indices) { val x = a[i] - (if (i < b.size) b[i] else 0f); d += x.toDouble() * x; e += a[i].toDouble() * a[i] }
        return sqrt(d / e)
    }

    @Test fun `the picker offers all nine and each has four presets`() {
        assertEquals(9, voices.size)
        assertTrue(TerraPicker.voices.containsAll(voices))
        for (voice in voices) assertEquals(4, TerraPresets.forVoice(voice).size, "$voice")
    }

    @Test fun `every voice shares TUNE DECAY FORCE and ATTACK and stays within six macros`() {
        for (voice in voices) {
            val names = Terra.macrosFor(voice).map { it.name }
            assertEquals(listOf("TUNE", "DECAY", "FORCE", "ATTACK"), names.take(4), "$voice")
            assertTrue(names.size in 4..6, "$voice has ${names.size} macros")
            assertEquals(names.size, names.toSet().size, "$voice repeats a macro")
        }
    }

    @Test fun `every render is finite, bounded, a hit and not a phrase`() {
        for (voice in voices) {
            val snip = Terra.render(voice)
            assertEquals(1, snip.channels); assertEquals(RATE, snip.sampleRate)
            assertTrue(snip.samples.all { it.isFinite() }, "$voice")
            val peak = snip.samples.maxOf { abs(it) }
            assertTrue(peak in 0.5f..1f, "$voice peaks at $peak")
            assertTrue(snip.samples.size < 1.5 * RATE, "$voice is ${snip.samples.size} frames: the classifier files 1.5 s and over as a phrase")
            assertTrue(abs(snip.samples.average()) < 0.005, "$voice has a mean of ${snip.samples.average()}")
        }
    }

    /**
     * The energy claim, against an independent window so it can fail: the last tenth of a render is under a fifth of
     * its loudest tenth. COWBELL's loudest strike is its third, at 345 ms, so the loudest window is found, not assumed.
     */
    @Test fun `every voice dies away`() {
        for (voice in voices) {
            val x = Terra.render(voice).samples
            val tenth = x.size / 10
            val loudest = (0 until 9).maxOf { rms(x, it * tenth, (it + 1) * tenth) }
            val last = rms(x, 9 * tenth, x.size)
            assertTrue(last < 0.2 * loudest, "$voice ends at ${last / loudest} of its loudest tenth")
        }
    }

    @Test fun `every macro changes the sound and an unknown one does not`() {
        for (voice in voices) {
            val base = Terra.render(voice).samples
            for (spec in Terra.macrosFor(voice)) {
                val lo = Terra.render(voice, mapOf(spec.name to 0f)).samples
                val hi = Terra.render(voice, mapOf(spec.name to 1f)).samples
                assertTrue(distance(lo, hi) > 0.05, "$voice ${spec.name} moves the render by ${distance(lo, hi)}")
            }
            assertContentEquals(base, Terra.render(voice, mapOf("NOT A MACRO" to 1f)).samples, "$voice")
        }
    }

    @Test fun `TUNE moves the lowest note by five semitones either way`() {
        for (voice in listOf(TerraVoice.NAGADO, TerraVoice.SHIME, TerraVoice.KANE)) {
            val (_, mid) = TerraSticks.bodyModes(voice, Terra.defaults(voice))
            val (_, low) = TerraSticks.bodyModes(voice, Terra.defaults(voice) + ("TUNE" to 0f))
            val (_, high) = TerraSticks.bodyModes(voice, Terra.defaults(voice) + ("TUNE" to 1f))
            assertEquals(-5.0, 12 * ln(low / mid.toDouble()) / ln(2.0), 0.01, "$voice down")
            assertEquals(5.0, 12 * ln(high / mid.toDouble()) / ln(2.0), 0.01, "$voice up")
            // and the render follows: the strongest peak near the lowest note moves with it
            val x = Terra.render(voice, mapOf("TUNE" to 1f)).samples
            // a narrow search: SHIME's next notes sit 18% and 29% above its lowest, and are louder
            val heard = TerraMeasure.peakHz(x, 0, minOf(x.size, RATE / 2), high * 0.94f, high * 1.06f, RATE)
            assertTrue(abs(TerraMeasure.cents(heard, high)) < 60, "$voice at TUNE 1 rings $heard Hz, its table says $high")
        }
    }

    /** Velocity is the strike, not the level: the output stage levels every render, so a soft hit must differ in shape. */
    @Test fun `a soft hit is a different strike on every voice`() {
        for (voice in voices) {
            val hard = Terra.render(voice).samples
            val soft = Terra.render(voice, velocity = 0.3f).samples
            assertTrue(distance(hard, soft) > 0.1, "$voice at velocity 0.3 is ${distance(hard, soft)} from its full hit")
        }
    }

    /** Round robin's raw material: four takes, none a copy of another, and take 0 the render itself. */
    @Test fun `four takes are four landings and take 0 is the stroke as picked`() {
        for (voice in voices) {
            val takes = List(4) { Terra.render(voice, take = it).samples }
            assertContentEquals(Terra.render(voice).samples, takes[0], "$voice take 0")
            for (a in 0 until 4) for (b in a + 1 until 4) {
                assertFalse(takes[a].contentEquals(takes[b]), "$voice takes $a and $b are the same samples")
                assertTrue(distance(takes[a], takes[b]) > 0.05, "$voice takes $a and $b are ${distance(takes[a], takes[b])} apart")
            }
            assertContentEquals(takes[2], Terra.render(voice, take = 2).samples, "$voice take 2 twice")
        }
    }

    @Test fun `the blends reach both strokes and their middle is neither`() {
        for ((voice, macro) in listOf(TerraVoice.ODAIKO to "DRUM", TerraVoice.SHIME to "STROKE", TerraVoice.KANE to "EDGE")) {
            val a = Terra.render(voice, mapOf(macro to 0f)).samples
            val b = Terra.render(voice, mapOf(macro to 1f)).samples
            val mid = Terra.render(voice, mapOf(macro to 0.5f)).samples
            assertTrue(distance(a, b) > 0.3, "$voice $macro ends are ${distance(a, b)} apart")
            assertTrue(distance(mid, a) > 0.1 && distance(mid, b) > 0.1, "$voice $macro 0.5 is one of its ends")
            assertEquals(TerraSticks.bodyModes(voice, Terra.defaults(voice) + (macro to 0.5f)).first.size,
                TerraSticks.bodyModes(voice, Terra.defaults(voice) + (macro to 0f)).first.size + TerraSticks.bodyModes(voice, Terra.defaults(voice) + (macro to 1f)).first.size)
        }
    }

    /** The cowbell is a train of strikes: at SHAKE 1 its loudest moment is the third strike (345 ms), at SHAKE 0 it is the start. */
    @Test fun `the cowbell's SHAKE is a train of strikes and SHAKE 0 is one`() {
        fun loudestAt(x: FloatArray): Double { var at = 0; for (i in x.indices) if (abs(x[i]) > abs(x[at])) at = i; return at.toDouble() / RATE }
        val shaken = loudestAt(Terra.render(TerraVoice.COWBELL, mapOf("SHAKE" to 1f)).samples)
        val single = loudestAt(Terra.render(TerraVoice.COWBELL, mapOf("SHAKE" to 0f)).samples)
        assertTrue(shaken in 0.33..0.45, "the shaken bell is loudest at $shaken s")
        assertTrue(single < 0.05, "the single strike is loudest at $single s")
        // DAMP is a hand on the bell: the last half second holds less of the render
        fun tailShare(x: FloatArray) = rms(x, x.size - RATE / 2, x.size) / rms(x, 0, x.size)
        val open = tailShare(Terra.render(TerraVoice.COWBELL, mapOf("SHAKE" to 0f, "DAMP" to 0f)).samples)
        val damped = tailShare(Terra.render(TerraVoice.COWBELL, mapOf("SHAKE" to 0f, "DAMP" to 1f)).samples)
        assertTrue(damped < 0.5 * open, "DAMP leaves the tail at $damped against $open")
    }

    /** The binzasara is many slats: SPREAD stretches when the energy arrives without changing what a slat is. */
    @Test fun `the binzasara's SPREAD moves its energy later`() {
        fun centreOfEnergy(x: FloatArray): Double { var e = 0.0; var m = 0.0; for (i in x.indices) { val p = x[i].toDouble() * x[i]; e += p; m += p * i }; return m / e / RATE }
        val tight = centreOfEnergy(Terra.render(TerraVoice.BINZASARA, mapOf("SPREAD" to 0f)).samples)
        val wide = centreOfEnergy(Terra.render(TerraVoice.BINZASARA, mapOf("SPREAD" to 1f)).samples)
        assertTrue(wide > 2.0 * tight, "SPREAD 1 centres at $wide s against $tight s")
    }

    /** HIT's contract: a curve of ones per mode is the plain render, the wrong count is refused, and a pitch curve has nothing to bend. */
    @Test fun `HIT sees one mode per note and a flat curve changes nothing`() {
        for (voice in voices) {
            val plain = Terra.render(voice).samples
            var seen = 0
            val struck = Terra.renderWith(voice, emptyMap()) { body ->
                seen = body.modes.size
                Terra.BankInputs(level = Array(body.modes.size) { floatArrayOf(1f) })
            }.samples
            assertTrue(seen > 0, "$voice gave HIT no modes")
            assertContentEquals(plain, struck, "$voice under a flat HIT curve")
        }
    }
}
