package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MAGNET's contract (docs/superpowers/plans/2026-09-30-magnet-r1.md): the macros, the notes,
 * a deterministic clean render at every corner and every TUNE step, the output chain's DC
 * handling, the patch's refusals and round trip, the landing chain, and the velocity fallback.
 */
class MagnetTest {

    private val voices = MagnetVoice.entries

    @Test
    fun `the four macros and their defaults`() {
        for (v in voices) assertEquals(listOf("TUNE", "MUTE", "PICK", "BLEND"), Magnet.macrosFor(v).map { it.name })
        assertEquals(mapOf("TUNE" to 0.5f, "MUTE" to 0.15f, "PICK" to 0.6f, "BLEND" to 0.5f), Magnet.defaults(MagnetVoice.JANGLE))
        assertEquals(mapOf("TUNE" to 0.5f, "MUTE" to 0.35f, "PICK" to 0.55f, "BLEND" to 1.0f), Magnet.defaults(MagnetVoice.CHUG))
    }

    @Test
    fun `TUNE snaps over two octaves from each voice's open string`() {
        // E2 and B1 as Hz, and as the exact Keys.midiHz of the root MIDI note: the render, the seed and
        // every tuning test read one function. TUNE 0.5 is 12 semitones; 0.5 minus 0.4 of a step is 11.6
        // (rounds to 12, floors to 11), 0.5 plus 0.4 is 12.4 (the same note either way) and 0.5 plus 0.6
        // is 12.6 (rounds to 13, floors to 12).
        val e2 = 82.4069f
        val b1 = 61.7354f
        for ((voice, root) in listOf(MagnetVoice.JANGLE to e2, MagnetVoice.CHUG to b1)) {
            assertEquals(root, Magnet.frequencyFor(voice, 0f), 0.01f, "$voice open string")
            assertEquals(2 * root, Magnet.frequencyFor(voice, 0.5f), 0.02f, "$voice one octave up")
            assertEquals(4 * root, Magnet.frequencyFor(voice, 1f), 0.04f, "$voice two octaves up")
            val rootMidi = Magnet.rootMidi(voice)
            assertEquals(Keys.midiHz(rootMidi), Magnet.frequencyFor(voice, 0f), "$voice open string is Keys.midiHz of the root")
            assertEquals(Keys.midiHz(rootMidi + 12), Magnet.frequencyFor(voice, 0.5f), "$voice one octave is Keys.midiHz of the root plus 12")
            assertEquals(Keys.midiHz(rootMidi + 24), Magnet.frequencyFor(voice, 1f), "$voice two octaves is Keys.midiHz of the root plus 24")
            assertEquals(Magnet.frequencyFor(voice, 0.5f), Magnet.frequencyFor(voice, 0.5f - 0.4f / 24f), "$voice 11.6 semitones rounds up to 12")
            assertEquals(Magnet.frequencyFor(voice, 0.5f), Magnet.frequencyFor(voice, 0.5f + 0.4f / 24f), "$voice 12.4 semitones rounds down to 12")
            assertEquals(Keys.midiHz(rootMidi + 13), Magnet.frequencyFor(voice, 0.5f + 0.6f / 24f), "$voice 12.6 semitones rounds up to 13")
        }
        assertEquals(40, Magnet.rootMidi(MagnetVoice.JANGLE))
        assertEquals(35, Magnet.rootMidi(MagnetVoice.CHUG))
        assertEquals(24, Magnet.TUNE_SEMITONES)
    }

    @Test
    fun `a render is deterministic and mono at the rack's rate`() {
        for (v in voices) {
            val a = Magnet.render(v)
            val b = Magnet.render(v)
            assertContentEquals(a.samples, b.samples, "$v")
            assertEquals(1, a.channels)
            assertEquals(Dsp.RATE, a.sampleRate)
        }
    }

    /**
     * What every note must be: not empty, at least the trim's floor long, finite and inside -1..1,
     * centred to under [DC_BOUND], and either at the melodic level or at the ceiling. Returns the
     * snip's mean magnitude so a caller can print the worst one.
     */
    private fun assertClean(s: Snip, label: String): Double {
        assertTrue(s.samples.isNotEmpty(), label)
        assertTrue(s.samples.size >= (0.25f * Dsp.RATE).toInt(), "$label is shorter than the trim's floor: ${s.samples.size} samples")
        assertTrue(s.samples.all { it.isFinite() && it in -1f..1f }, "$label has a bad sample")
        var sum = 0.0
        var peak = 0f
        for (x in s.samples) { sum += x; peak = maxOf(peak, abs(x)) }
        val dc = abs(sum / s.samples.size)
        assertTrue(dc < DC_BOUND, "$label is off centre by $dc")
        assertTrue(Loudness.of(s) >= 0.9f * Dsp.MELODIC_LOUDNESS_TARGET || peak >= 0.95f, "$label is too quiet: loudness ${Loudness.of(s)}, peak $peak")
        return dc
    }

    @Test
    fun `every corner renders clean, centred, audible audio`() {
        // All 2^4 corners of the four macros, both voices.
        var worstDc = 0.0
        for (v in voices) {
            val specs = Magnet.macrosFor(v)
            for (mask in 0 until 16) {
                val macros = specs.mapIndexed { i, s -> s.name to (if ((mask and (1 shl i)) != 0) 1f else 0f) }.toMap()
                worstDc = maxOf(worstDc, assertClean(Magnet.render(v, macros), "$v $macros"))
            }
        }
        println("MAGNET corners: worst DC $worstDc (bound $DC_BOUND)")
    }

    @Test
    fun `every TUNE step of both voices renders clean at the defaults`() {
        // All 25 steps, so the notes at both ends of the range are rendered end to end.
        var worstDc = 0.0
        for (v in voices) {
            for (step in 0..Magnet.TUNE_SEMITONES) {
                val macros = Magnet.defaults(v) + ("TUNE" to step / Magnet.TUNE_SEMITONES.toFloat())
                assertEquals(step, Magnet.semitonesFor(macros.getValue("TUNE")), "$v step $step")
                worstDc = maxOf(worstDc, assertClean(Magnet.render(v, macros), "$v TUNE step $step"))
            }
        }
        println("MAGNET TUNE steps: worst DC $worstDc (bound $DC_BOUND)")
    }

    // ---------- the output chain's DC handling, read on Magnet.finish directly ----------

    /** A decaying 220 Hz tone at [Magnet.RENDER_RATE], 0.8 s long, plus whatever [extra] adds at time t seconds. */
    private fun toneAt(extra: (Double) -> Double): FloatArray {
        val rate = Magnet.RENDER_RATE
        return FloatArray((0.8 * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            (0.3 * exp(-30.0 * t) * sin(2 * PI * 220.0 * t) + extra(t)).toFloat()
        }
    }

    /** Amplitude of [y]'s component at [hz], by correlation over [y]'s whole length. */
    private fun amplitudeAt(y: FloatArray, hz: Double, rate: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in y.indices) {
            val ph = 2 * PI * hz * i / rate
            re += y[i] * cos(ph)
            im += y[i] * sin(ph)
        }
        return 2 * sqrt(re * re + im * im) / y.size
    }

    @Test
    fun `finish removes a constant offset`() {
        val out = Magnet.finish(toneAt { 0.1 }, Magnet.RENDER_RATE)
        val peak = out.maxOf { abs(it) }
        assertTrue(peak > 0f, "the render is silent")
        val from = (0.4 * Dsp.RATE).toInt()
        val to = (0.6 * Dsp.RATE).toInt()
        var sum = 0.0
        for (i in from until to) sum += out[i]
        val relative = abs(sum / (to - from)) / peak
        println("MAGNET finish: constant offset left at ${relative} of peak over 0.4 to 0.6 s")
        assertTrue(relative < 0.01, "a constant offset of 0.1 leaves $relative of the output's peak at 0.4 to 0.6 s")
    }

    @Test
    fun `finish attenuates a slow drift`() {
        // A 2 Hz drift under a short tone. The un-filtered reference is the same buffer decimated and
        // nothing else; the output's drift, relative to its own 220 Hz component, must sit at least 15 dB
        // under the reference's (a one-pole at 20 Hz takes 2 Hz down about 20 dB).
        fun drift(t: Double) = 0.05 * sin(2 * PI * 2.0 * t)
        val reference = Dsp.decimate(toneAt(::drift), Dsp.RATE)
        val out = Magnet.finish(toneAt(::drift), Magnet.RENDER_RATE)
        val window = (0.6 * Dsp.RATE).toInt()
        fun ratio(x: FloatArray): Double {
            val head = x.copyOfRange(0, window)
            return amplitudeAt(head, 2.0, Dsp.RATE) / amplitudeAt(head, 220.0, Dsp.RATE)
        }
        val under = 20 * log10(ratio(reference) / ratio(out))
        println("MAGNET finish: the 2 Hz drift sits ${under} dB under the un-filtered reference")
        assertTrue(under >= 15.0, "the output's 2 Hz drift is only $under dB under the un-filtered reference")
    }

    @Test
    fun `a patch refuses what it does not know and a render ignores it`() {
        assertFailsWith<IllegalArgumentException> { MagnetPatch("", MagnetVoice.JANGLE, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { MagnetPatch("x", MagnetVoice.JANGLE, mapOf("DRIVE" to 0.5f)) } // a VALVE macro is a stranger here
        assertFailsWith<IllegalArgumentException> { MagnetPatch("x", MagnetVoice.JANGLE, mapOf("TUNE" to 2f)) }
        assertFailsWith<JsonException> { MagnetPatch.fromJsonText("""{"engine":"VELVET"}""") }
        // A complete, valid MAGNET payload with only its engine tag changed is refused for the tag.
        val valid = MagnetPatch("x", MagnetVoice.JANGLE, Magnet.defaults(MagnetVoice.JANGLE)).toJsonText()
        val foreign = valid.replace("\"MAGNET\"", "\"VELVET\"")
        assertTrue(foreign != valid && "VELVET" in foreign, "the payload's engine tag was not changed")
        assertFailsWith<JsonException> { MagnetPatch.fromJsonText(foreign) }
        // At render level an unknown macro is ignored and a known one is clamped.
        assertContentEquals(Magnet.render(MagnetVoice.JANGLE).samples, Magnet.render(MagnetVoice.JANGLE, mapOf("DRIVE" to 0.9f)).samples)
        assertContentEquals(
            Magnet.render(MagnetVoice.JANGLE, mapOf("MUTE" to 1f)).samples,
            Magnet.render(MagnetVoice.JANGLE, mapOf("MUTE" to 7f)).samples,
        )
        for (v in voices) assertEquals(1f, Magnet.settled(mapOf("MUTE" to 7f), v).getValue("MUTE"), "$v clamps MUTE")
    }

    @Test
    fun `a patch round-trips through the dispatcher and renders the same`() {
        for (v in voices) {
            val patch = MagnetPatch("Round Trip", v, Magnet.defaults(v) + mapOf("MUTE" to 0.4f, "BLEND" to 0.25f))
            val back = Patches.fromJsonText(patch.toJsonText())
            assertTrue(back is MagnetPatch, "the dispatcher does not know MAGNET")
            assertEquals(patch, back)
            assertContentEquals(patch.render().samples, back.render().samples)
        }
    }

    @Test
    fun `scramble is reproducible and bounded`() {
        for (v in voices) {
            assertEquals(Magnet.scramble(v, Random(7)), Magnet.scramble(v, Random(7)))
            val random = Random(11)
            repeat(12) {
                val roll = Magnet.scramble(v, random, temperature = 1f)
                assertEquals(Magnet.macrosFor(v).map { it.name }.toSet(), roll.keys)
                assertTrue(roll.values.all { it in 0f..1f })
                assertTrue(Magnet.render(v, roll).samples.all { it.isFinite() && it in -1f..1f })
            }
            val near = MagnetPatch("Near", v, Magnet.defaults(v))
            assertEquals(Magnet.scramble(v, Random(3), 0.35f, near), Magnet.scramble(v, Random(3), 0.35f, near))
            // Temperature 0 returns the seed untouched, and the seed is the defaults moved to the patch's macros.
            val moved = mapOf("MUTE" to 0.8f, "PICK" to 0.2f)
            assertEquals(Magnet.defaults(v) + moved, Magnet.scramble(v, Random(3), 0f, MagnetPatch("Near", v, moved)), "$v near a patch")
        }
    }

    @Test
    fun `each voice lands through VALVE with its own chain`() {
        val valveNames = Valve.MACROS.map { it.name }.toSet()
        for (v in voices) {
            val chain = Magnet.landingChain(v)
            assertEquals(Magnet.LANDING_VALVE.getValue(v), chain.valve, "$v")
            assertTrue(Magnet.LANDING_VALVE.getValue(v).keys.all { it in valveNames }, "$v lands on a macro VALVE does not have")
            assertTrue(Magnet.LANDING_VALVE.getValue(v).values.all { it in 0f..1f }, "$v")
        }
        assertTrue(Magnet.LANDING_VALVE.getValue(MagnetVoice.JANGLE) != Magnet.LANDING_VALVE.getValue(MagnetVoice.CHUG))
        // CHUG's DRIVE 0.71 is gain 13 on VALVE's law (the number Magnet.LANDING_VALVE's KDoc states).
        assertEquals(13.2f, Valve.gainFor(Magnet.LANDING_VALVE.getValue(MagnetVoice.CHUG).getValue("DRIVE")), 0.3f)
    }

    @Test
    fun `a landed pad differs from the dry render`() {
        val patch = MagnetPatch("Dry", MagnetVoice.CHUG, Magnet.defaults(MagnetVoice.CHUG))
        val landed = PadRecipe(patch, Magnet.landingChain(MagnetVoice.CHUG)).render()
        assertFalse(patch.render().samples.contentEquals(landed.samples), "the landing chain changed nothing")
    }

    @Test
    fun `the dispatcher and the roster know MAGNET's state`() {
        // The dispatcher's round trip is above; MAGNET is registered with the patch system and has no preset roster.
        for (v in voices) assertTrue(Presets.forVoice("MAGNET", v.name).isEmpty(), "MAGNET has a roster: $v")
    }

    @Test
    fun `velocity softens through the fallback until PICK is registered`() {
        // No PICK override is registered (the 1 percent clause of PICK's sweep is unproven on the built
        // engine), and none of MAGNET's macro names is one of Velocity's brightness macros, so atVelocity
        // falls back to soften and a soft note is a dulled one. This test flips, on purpose, in the commit
        // that registers `patch is MagnetPatch -> "PICK"` in Velocity.brightnessOverride.
        for (voice in voices) {
            val patch = MagnetPatch("Vel Canary", voice, Magnet.defaults(voice))
            val viaFallback = Velocity.atVelocity(patch, 0.3f)
            val viaSoften = Velocity.soften(patch.render(), 0.7f)
            assertTrue(viaFallback.samples.contentEquals(viaSoften.samples), "$voice: atVelocity is not the soften fallback")
            val soft = FeatureExtractor.extract(Velocity.atVelocity(patch, 0.25f)).centroidHz
            val hard = FeatureExtractor.extract(Velocity.atVelocity(patch, 1f)).centroidHz
            assertTrue(soft < hard, "$voice: soft centroid $soft should be below hard centroid $hard")
        }
    }

    private companion object {
        /**
         * What the clean-render checks guard: an audible DC offset (1e-4 is about -80 dBFS). The
         * corners read at most 4.4e-7 because the pickup comb has no DC gain and the output chain's
         * 20 Hz high-pass removes the rest. These checks do not prove the output chain's DC stage: that
         * is `finish removes a constant offset` and `finish attenuates a slow drift`, which read
         * [Magnet.finish] on an input that carries an offset.
         */
        const val DC_BOUND = 1e-4
    }
}
