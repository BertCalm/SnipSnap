package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
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
 * handling, the patch's refusals and round trip, the landing chain, and the velocity registration.
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
    private fun amplitudeAt(y: FloatArray, hz: Double, rate: Int): Double = MagnetMeasure.amplitudeAt(y, hz, rate)

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
    fun `velocity re-renders MAGNET at PICK, softer is darker`() {
        // PICK is registered in Velocity.brightnessOverride because its sweep at the defaults moves the
        // centroid at least 1 percent per tenth on both voices (smallest 1.77 percent on JANGLE, 2.03
        // percent on CHUG: the numbers `PICK never lowers the centroid across its eleven steps on both
        // voices` prints), so a soft hit is a re-render with a lower PICK. This reaches a bare patch only:
        // a landed pad's velocity layers still fall back to soften, because the layer builder requires
        // its fx to be null.
        for (voice in voices) {
            val patch = MagnetPatch("Vel Canary", voice, Magnet.defaults(voice))
            assertEquals("PICK", Velocity.brightnessSpec(patch)?.name, "$voice did not resolve PICK as its brightness macro")
            val soft = FeatureExtractor.extract(Velocity.atVelocity(patch, 0.25f)).centroidHz
            val hard = FeatureExtractor.extract(Velocity.atVelocity(patch, 1f)).centroidHz
            assertTrue(soft < hard, "$voice: soft centroid $soft should be below hard centroid $hard")
        }
    }

    // ---------- the measured claims on the dry string ----------

    @Test
    fun `every note of both voices is within five cents on the dry string`() {
        // Read on the raw 176.4 kHz buffer with FineTuning, before the output chain: both voices, all 25
        // TUNE steps, MUTE and BLEND each at 0, 0.5 and 1 (450 cells). The string does not read BLEND, so
        // one string serves the three blends of a cell. PICK stays at its default.
        val grid = listOf(0f, 0.5f, 1f)
        var cells = 0
        var worst = 0.0
        var worstCell = ""
        val lowest = mutableMapOf<MagnetVoice, Double>()
        val highest = mutableMapOf<MagnetVoice, Double>()
        val over = ArrayList<String>()
        for (v in voices) {
            for (step in 0..Magnet.TUNE_SEMITONES) {
                val tune = step / Magnet.TUNE_SEMITONES.toFloat()
                val f0 = Magnet.frequencyFor(v, tune)
                for (mute in grid) {
                    val string = Magnet.string(v, Magnet.defaults(v) + mapOf("TUNE" to tune, "MUTE" to mute))
                    for (blend in grid) {
                        val dry = Magnet.pickup(v, string, f0, blend)
                        val cents = FineTuning.cents(FineTuning.measuredHz(dry, Magnet.RENDER_RATE, f0), f0.toDouble())
                        val cell = "$v TUNE step $step MUTE $mute BLEND $blend"
                        cells++
                        lowest[v] = min(lowest[v] ?: cents, cents)
                        highest[v] = maxOf(highest[v] ?: cents, cents)
                        if (abs(cents) > worst) { worst = abs(cents); worstCell = "$cell at $cents cents" }
                        if (abs(cents) > 5.0) over.add("$cell at $cents cents")
                    }
                }
            }
        }
        val ranges = voices.joinToString("; ") { "$it ${MagnetMeasure.round(lowest.getValue(it), 2)} to ${MagnetMeasure.round(highest.getValue(it), 2)}" }
        println("MAGNET in tune: $cells cells, cents $ranges, worst |cents| ${MagnetMeasure.round(worst, 2)} at $worstCell (spike: -0.62 to 1.11 over 36 cells)")
        assertEquals(450, cells)
        assertTrue(over.isEmpty(), "${over.size} cells are over 5 cents, the first: ${over.take(5)}")
    }

    @Test
    fun `the single coil comb notches h2 and h4 at half way and h4 at a quarter`() {
        // JANGLE at TUNE 0.5 and the default MUTE, no resonance, neck coil alone at 0.5 of the string
        // (kills h2 and h4) and then at 0.25 (kills h4). A notch must sit 20 dB under the quieter of its
        // two neighbours. The string's own exciter comb sits near h11.8 (1 / 0.085), outside h2 to h8.
        val v = MagnetVoice.JANGLE
        val f0 = Magnet.frequencyFor(v, 0.5f)
        val string = Magnet.string(v, Magnet.defaults(v) + ("TUNE" to 0.5f))
        fun read(neck: Float) = MagnetMeasure.relH1(Magnet.pickup(v, string, f0, 0f, resonance = false, neck = neck), f0, 0.05f, 0.35f, 8)
        val half = read(0.5f)
        val quarter = read(0.25f)
        // h[i] is harmonic i + 1: harmonic j sits at index j - 1 and its neighbours at j - 2 and j.
        fun gap(h: List<Double>, j: Int) = min(h[j - 2], h[j]) - h[j - 1]
        val halfH2 = gap(half, 2)
        val halfH4 = gap(half, 4)
        val quarterH4 = gap(quarter, 4)
        println(
            "MAGNET comb: neck 0.5 h2 ${MagnetMeasure.round(halfH2)} dB and h4 ${MagnetMeasure.round(halfH4)} dB under their neighbours " +
                "(h1..h8 ${MagnetMeasure.round(half)}); neck 0.25 h4 ${MagnetMeasure.round(quarterH4)} dB (h1..h8 ${MagnetMeasure.round(quarter)}); " +
                "spike: 52, 54 and 47 dB",
        )
        assertTrue(halfH2 >= 20.0, "neck 0.5: h2 is only $halfH2 dB under its neighbours")
        assertTrue(halfH4 >= 20.0, "neck 0.5: h4 is only $halfH4 dB under its neighbours")
        assertTrue(quarterH4 >= 20.0, "neck 0.25: h4 is only $quarterH4 dB under its neighbours")
    }

    @Test
    fun `the humbucker notches at the coil spacing on the real string and a naive sum does not`() {
        // CHUG at TUNE 0.5, bridge group alone (BLEND 1), no resonance. The coil spacing dp puts the
        // pair's first spacing notch near harmonic k = round(1 / dp). The reference is the single bridge
        // coil; the naive sum is the two coils as two unaligned single-position calls. Harmonic levels
        // are against h1 of their own render, so the gap is a ratio of ratios.
        val v = MagnetVoice.CHUG
        val rate = Magnet.RENDER_RATE
        val f0 = Magnet.frequencyFor(v, 0.5f)
        val dp = 0.0278f * f0 / Keys.midiHz(Magnet.rootMidi(v))
        val second = Magnet.BRIDGE + dp
        val d1 = Strings.combDelay(Magnet.BRIDGE, f0, rate)
        val d2 = Strings.combDelay(second, f0, rate)
        val parity = if ((d2 - d1) % 2 == 0) "even" else "odd"
        val k = (1f / dp).roundToInt()
        val string = Magnet.string(v, Magnet.defaults(v) + ("TUNE" to 0.5f))
        val single = Strings.pickup(string, floatArrayOf(Magnet.BRIDGE), floatArrayOf(1f), f0, rate)
        val aligned = Magnet.pickup(v, string, f0, 1f, resonance = false)
        val coilA = Strings.pickup(string, floatArrayOf(Magnet.BRIDGE), floatArrayOf(0.707f), f0, rate)
        val coilB = Strings.pickup(string, floatArrayOf(second), floatArrayOf(0.707f), f0, rate)
        val naive = FloatArray(string.size) { coilA[it] + coilB[it] }
        fun read(x: FloatArray) = MagnetMeasure.relH1(x, f0, 0.05f, 0.35f, k + 1)
        val hSingle = read(single)
        // The notch's depth under the single coil, h1 to h[k]: the first term is the single coil's own.
        fun gap(x: List<Double>) = (hSingle[k - 1] - hSingle[0]) - (x[k - 1] - x[0])
        val gapAligned = gap(read(aligned))
        val gapNaive = gap(read(naive))
        println(
            "MAGNET humbucker: dp ${MagnetMeasure.round(dp.toDouble(), 4)}, k $k, D1 $d1, D2 $d2, D2 - D1 $parity (${d2 - d1}), " +
                "string ${MagnetMeasure.round(string.size.toDouble() / rate, 2)} s, " +
                "h$k aligned ${MagnetMeasure.round(gapAligned)} dB under the single coil, naive ${MagnetMeasure.round(gapNaive)} dB (spike: 34 dB aligned)",
        )
        assertTrue(gapAligned >= 20.0, "the aligned humbucker is only $gapAligned dB under the single coil at h$k")
        assertTrue(gapNaive < 20.0, "the unaligned sum notches too ($gapNaive dB at h$k): this test cannot tell alignment from none")
    }

    @Test
    fun `BLEND moves the spectrum by at least 6 dB at some harmonic on both voices`() {
        // The full pickup with its resonance, TUNE 0.5 and the default MUTE and PICK, neck alone against
        // bridge alone. relH1 is dB against each render's own h1, so entry j is harmonic j + 1 and entries
        // 1 to 7 are h2 to h8.
        for (v in voices) {
            val f0 = Magnet.frequencyFor(v, 0.5f)
            val string = Magnet.string(v, Magnet.defaults(v) + ("TUNE" to 0.5f))
            val neck = MagnetMeasure.relH1(Magnet.pickup(v, string, f0, 0f), f0, 0.05f, 0.35f, 8)
            val bridge = MagnetMeasure.relH1(Magnet.pickup(v, string, f0, 1f), f0, 0.05f, 0.35f, 8)
            val swings = (1..7).map { abs(bridge[it] - neck[it]) }
            val swing = swings.max()
            val at = swings.indexOf(swing) + 2
            println("MAGNET BLEND $v: swing ${MagnetMeasure.round(swing)} dB, peaks at h$at (h2..h8 ${MagnetMeasure.round(swings)}) (spike: 18 dB at JANGLE's h5)")
            assertTrue(swing >= 6.0, "$v: BLEND moves no harmonic in h2 to h8 by 6 dB, the most is $swing dB at h$at")
        }
    }

    @Test
    fun `MUTE shortens and darkens both voices`() {
        // MUTE 1 against MUTE 0 at TUNE 0.5 on the rendered note: under half the length, and a lower
        // centroid. The spike read 0.26 to 1.33 s at MUTE 1 against the 4 s ceiling.
        for (v in voices) {
            val open = Magnet.render(v, Magnet.defaults(v) + mapOf("TUNE" to 0.5f, "MUTE" to 0f))
            val damped = Magnet.render(v, Magnet.defaults(v) + mapOf("TUNE" to 0.5f, "MUTE" to 1f))
            val openHz = FeatureExtractor.extract(open).centroidHz
            val dampedHz = FeatureExtractor.extract(damped).centroidHz
            println(
                "MAGNET MUTE $v: MUTE 0 ${open.samples.size} samples (${open.durationSeconds} s), MUTE 1 ${damped.samples.size} samples " +
                    "(${damped.durationSeconds} s), ratio ${MagnetMeasure.round(damped.samples.size.toDouble() / open.samples.size, 3)}; " +
                    "centroid ${openHz} Hz at MUTE 0 and ${dampedHz} Hz at MUTE 1 (spike: 0.26 to 1.33 s at MUTE 1)",
            )
            assertTrue(damped.samples.size < 0.5 * open.samples.size, "$v: MUTE 1 is ${damped.samples.size} samples against MUTE 0's ${open.samples.size}")
            assertTrue(dampedHz < openHz, "$v: MUTE 1's centroid $dampedHz Hz is not under MUTE 0's $openHz Hz")
        }
    }

    @Test
    fun `PICK never lowers the centroid across its eleven steps on both voices`() {
        // Each voice at PICK 0 to 1 in tenths, the other macros at their defaults, the centroid read on the
        // rendered note. The monotonic clause is asserted at every step. The specification's second clause,
        // each tenth of travel at least 1 percent, is printed with its count and not asserted: it is the
        // evidence behind registering PICK as velocity's brightness macro (every tenth passes on both
        // voices at the defaults). The corner is the exciter's low-pass corner, Dsp.expMap over Magnet's
        // 600 to 16000 Hz.
        val tenths = (0..10).map { it / 10f }
        val lines = ArrayList<String>()
        val falls = ArrayList<String>()
        for (v in voices) {
            val hz = tenths.map { p -> FeatureExtractor.extract(Magnet.render(v, mapOf("PICK" to p))).centroidHz }
            var passing = 0
            val steps = ArrayList<String>()
            for (i in 1..10) {
                val pct = 100.0 * (hz[i] - hz[i - 1]) / hz[i - 1]
                val atLeastOne = pct >= 1.0
                if (atLeastOne) passing++
                if (!(hz[i] >= hz[i - 1])) falls.add("$v PICK ${tenths[i - 1]} to ${tenths[i]}: ${hz[i - 1]} Hz to ${hz[i]} Hz")
                steps.add(
                    "${tenths[i]} corner ${MagnetMeasure.round(Dsp.expMap(tenths[i], 600f, 16_000f).toDouble())} Hz " +
                        "centroid ${hz[i]} Hz ${MagnetMeasure.round(pct, 2)} percent ${if (atLeastOne) "at least 1" else "under 1"}",
                )
            }
            lines.add(
                "$v PICK 0.0 corner ${Dsp.expMap(0f, 600f, 16_000f)} Hz centroid ${hz[0]} Hz; ${steps.joinToString("; ")}; " +
                    "$passing of 10 tenths at least 1 percent",
            )
        }
        println("MAGNET PICK: ${lines.joinToString(" || ")}")
        assertTrue(falls.isEmpty(), "PICK lowers the centroid: $falls")
    }

    /**
     * Cents of [x] against [f0] by the interpolated autocorrelation, over 0.05 to 0.25 s at the rack's
     * rate. A note shorter than the window is read to its own end, never past it.
     */
    private fun centsOf(x: Snip, f0: Float): Double {
        val to = min(0.25f, x.durationSeconds)
        require(to - 0.05f >= 0.1f) { "a ${x.durationSeconds} s note is too short to read for pitch" }
        return BoreMeasure.cents(x.samples, f0, 0.05f, to, Dsp.RATE)
    }

    @Test
    fun `the pitch through VALVE stays within ten cents at the landing and is printed at gain 1000`() {
        // Both voices at TUNE 0, 0.5 and 1 with the other macros at their defaults: the dry note, then the
        // same note through each voice's landing amp (asserted, the specification's 10 cents) and through
        // DRIVE 1 with no SAG, flat TONE and no cabinet (VALVE's gain 1000, the case the specification's bar
        // was written against; printed only). The pitch is the interpolated autocorrelation, never
        // Pitch.detect: its integer lag steps up to 12.9 cents at JANGLE's E4 and a hot amp can hand it an
        // octave or nothing.
        val hot = mapOf("DRIVE" to 1f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f)
        val cells = ArrayList<String>()
        val over = ArrayList<String>()
        var worstLanding = 0.0
        var worstHot = 0.0
        for (v in voices) {
            val landing = Magnet.LANDING_VALVE.getValue(v)
            for (tune in listOf(0f, 0.5f, 1f)) {
                val f0 = Magnet.frequencyFor(v, tune)
                val dry = Magnet.render(v, Magnet.defaults(v) + ("TUNE" to tune))
                val dryCents = centsOf(dry, f0)
                val landedCents = centsOf(Valve.process(dry, landing), f0)
                val hotCents = centsOf(Valve.process(dry, hot), f0)
                val landedShift = landedCents - dryCents
                val hotShift = hotCents - dryCents
                worstLanding = maxOf(worstLanding, abs(landedShift))
                worstHot = maxOf(worstHot, abs(hotShift))
                val cell = "$v TUNE $tune (${MagnetMeasure.round(f0.toDouble(), 2)} Hz)"
                if (abs(landedShift) > 10.0) over.add("$cell at ${MagnetMeasure.round(landedShift, 2)} cents")
                cells.add(
                    "$cell dry ${MagnetMeasure.round(dryCents, 2)} cents, landing ${MagnetMeasure.round(landedCents, 2)} cents " +
                        "(wet minus dry ${MagnetMeasure.round(landedShift, 2)}), gain ${Valve.gainFor(hot.getValue("DRIVE"))} " +
                        "${MagnetMeasure.round(hotCents, 2)} cents (wet minus dry ${MagnetMeasure.round(hotShift, 2)})",
                )
            }
        }
        println(
            "MAGNET pitch through VALVE: ${cells.joinToString("; ")}; worst |wet minus dry| " +
                "${MagnetMeasure.round(worstLanding, 2)} cents at the landing, ${MagnetMeasure.round(worstHot, 2)} cents at gain 1000",
        )
        assertTrue(over.isEmpty(), "the landing amp moves the pitch by more than 10 cents: $over")
    }

    @Test
    fun `a landed pad regenerates bit for bit from its recipe`() {
        // The recipe a kit pad stores: the voice's default patch and its landing chain. Written to JSON and
        // read back, it renders the same samples, so the pad's WAV is always reproducible.
        val lines = ArrayList<String>()
        val renders = ArrayList<Pair<MagnetVoice, Pair<Snip, Snip>>>()
        for (v in voices) {
            val recipe = PadRecipe(MagnetPatch("Landing ${v.name}", v, Magnet.defaults(v)), Magnet.landingChain(v))
            val first = recipe.render()
            val back = PadRecipe.fromJsonValue(recipe.toJsonValue()).render()
            lines.add("$v ${first.samples.size} samples, regenerated ${back.samples.size} samples, identical ${first.samples.contentEquals(back.samples)}")
            renders.add(v to (first to back))
        }
        println("MAGNET landing: ${lines.joinToString("; ")}")
        for ((v, pair) in renders) {
            assertContentEquals(pair.first.samples, pair.second.samples, "$v")
            assertEquals(pair.first.sampleRate, pair.second.sampleRate, "$v")
            assertEquals(pair.first.channels, pair.second.channels, "$v")
        }
    }

    @Test
    fun `cost of a four second JANGLE render and its trip through VALVE is printed`() {
        // Print only: timing on a shared runner is not a property of the code. The owner's phone number
        // is recorded at the audition gate.
        val macros = mapOf("MUTE" to 0f)
        val valve = checkNotNull(Magnet.landingChain(MagnetVoice.JANGLE).valve)
        Valve.process(Magnet.render(MagnetVoice.JANGLE, macros), valve) // warm-up of both stages
        val start = System.nanoTime()
        val dry = Magnet.render(MagnetVoice.JANGLE, macros)
        val rendered = System.nanoTime()
        val landed = Valve.process(dry, valve)
        val processed = System.nanoTime()
        val seconds = dry.durationSeconds.toDouble()
        val renderMs = (rendered - start) / 1e6
        val valveMs = (processed - rendered) / 1e6
        println("MAGNET cost: dry render ${MagnetMeasure.round(renderMs / seconds)} ms per rendered second (${MagnetMeasure.round(seconds, 2)} s in ${MagnetMeasure.round(renderMs)} ms)")
        println("MAGNET cost: VALVE ${MagnetMeasure.round(valveMs / seconds)} ms per rendered second (${landed.samples.size} samples in ${MagnetMeasure.round(valveMs)} ms)")
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
