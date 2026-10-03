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
 * MAGNET's contract (docs/superpowers/specs/2026-09-29-magnet-valve-design.md, "Testing"): the
 * macros, the notes, a deterministic clean render at every corner and every TUNE step, the render
 * as the dry string through the pickup and the output chain and nothing else, the output chain's
 * DC handling, the patch's refusals and round trip, the landing chain, the end of a landed note,
 * and the velocity registration.
 * The measured claims: every note within 5 cents on the raw pickup buffer at the render rate, the comb and humbucker notches,
 * BLEND's spectral swing, MUTE's length and centroid, PICK's centroid sweep (at least 1 percent per
 * tenth at the defaults, and never falling from one tenth to the next at four of the eight corners of
 * MUTE, TUNE and BLEND on each voice), the pitch through VALVE (within 10 cents at each landing), a
 * landed pad regenerating bit for bit, the end of a landed note at three landings (at least 53 dB under
 * its peak, across MUTE and sampled TUNE steps), and JANGLE's pitch-compensated ring (the open string
 * unchanged, the fundamental's loss per second flat across the range, E4's t-20, a stable loop) with
 * CHUG's ring left as it was.
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

    @Test
    fun `the render is the dry string through the pickup and the output chain and nothing else`() {
        // Bit for bit, for both voices at the defaults and at an off-default set: the real call chain,
        // string, then pickup, then finish. The arrays are copied before finish, which works in place.
        val offDefault = mapOf("TUNE" to 0.3f, "MUTE" to 0.7f, "PICK" to 0.2f, "BLEND" to 0.8f)
        for (v in voices) {
            for ((name, macros) in listOf("defaults" to Magnet.defaults(v), "off default" to Magnet.defaults(v) + offDefault)) {
                val m = Magnet.settled(macros, v)
                val f0 = Magnet.frequencyFor(v, m.getValue("TUNE"))
                val string = Magnet.string(v, m).copyOf()
                val picked = Magnet.pickup(v, string, f0, m.getValue("BLEND")).copyOf()
                val expected = Magnet.finish(picked, Magnet.RENDER_RATE)
                assertContentEquals(expected, Magnet.render(v, macros).samples, "$v at $name")
            }
        }
    }

    @Test
    fun `finish refuses a buffer at any rate but the render rate`() {
        val e = assertFailsWith<IllegalArgumentException> { Magnet.finish(FloatArray(8), Dsp.RATE) }
        assertTrue(
            "${Magnet.RENDER_RATE}" in e.message.orEmpty() && "${Dsp.RATE}" in e.message.orEmpty(),
            "the refusal does not name both rates: ${e.message}",
        )
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
        // The owner's picks by ear (CHUG's at the gate, 2026-10-01; JANGLE's at the second listen, 2026-10-01/02),
        // each pinned as the four literals it is:
        // CHUG's DRIVE 0.85 is gain 106 on VALVE's law (the number Magnet.LANDING_VALVE's KDoc states), and
        // JANGLE's is VALVE's default amp written out in full, so a change to VALVE's defaults cannot move it.
        assertEquals(mapOf("DRIVE" to 0.85f, "SAG" to 0.4f, "TONE" to 0.3f, "CAB" to 0.95f), Magnet.LANDING_VALVE.getValue(MagnetVoice.CHUG))
        assertEquals(mapOf("DRIVE" to 0.70f, "SAG" to 0.35f, "TONE" to 0.50f, "CAB" to 0.60f), Magnet.LANDING_VALVE.getValue(MagnetVoice.JANGLE))
        assertEquals(106f, Valve.gainFor(Magnet.LANDING_VALVE.getValue(MagnetVoice.CHUG).getValue("DRIVE")), 1f)
    }

    @Test
    fun `a landed pad differs from the dry render`() {
        val patch = MagnetPatch("Dry", MagnetVoice.CHUG, Magnet.defaults(MagnetVoice.CHUG))
        val landed = PadRecipe(patch, Magnet.landingChain(MagnetVoice.CHUG)).render()
        assertFalse(patch.render().samples.contentEquals(landed.samples), "the landing chain changed nothing")
    }

    /**
     * The peak of [s]'s last 20 ms against the peak of the whole note, in dB (negative: under). The
     * peak of the window, not its RMS: it is the stricter of the two, and the one that read a landed
     * CHUG pad's end at 33.5 to 37.8 dB under before the string was faded ahead of the amp.
     */
    private fun endDb(s: Snip): Double {
        val n = (0.02f * s.sampleRate).toInt()
        var tail = 0f
        for (i in s.samples.size - n until s.samples.size) tail = maxOf(tail, abs(s.samples[i]))
        return 20 * log10(tail.coerceAtLeast(1e-9f) / s.peak().coerceAtLeast(1e-9f).toDouble())
    }

    /** One amp a landed note is read through: the voice, the macros it overrides on top of the defaults, and the VALVE macros. */
    private class EndLanding(val name: String, val voice: MagnetVoice, val macros: Map<String, Float>, val valve: Map<String, Float>)

    /**
     * The PICK values the end grid reads for [v]: the default only. A list, so that a change that moves
     * the exciter's corner (the PICK map) adds its own ends here and the grid reads them.
     */
    private fun endPicks(v: MagnetVoice): List<Float> = listOf(Magnet.defaults(v).getValue("PICK"))

    @Test
    fun `a landed note ends at least 53 dB under its peak at every landing, MUTE and sampled TUNE step`() {
        // The three amps a MAGNET note lands through: CHUG's landing (DRIVE 0.85, gain 106), the kit's lead
        // amp (DRIVE 0.78, CHUG at BLEND 0.35: a literal, as in SynthKitTest, because the kit's LEAD_VALVE
        // is private) and JANGLE's landing. The bar is the review's -50 dB on the peak of the last 20 ms with
        // 3 dB to spare (so -53), for MUTE 0, the voice's default and 1 at the default PICK. A string whose
        // trim ended it on its decay ends on Magnet's decay fade, one the ring ceiling cut on the trim's
        // 400 ms fade and Magnet's own on top of it; the amp lifts either end by its gain. The ten TUNE steps
        // are the whole grid's binding cells and the ends of the range (the full 25-step search found, at
        // gain 106: the ceiling cells at steps 0 to 4 and 12 at MUTE 0, the decay cells at MUTE 1 at steps
        // 3, 4, 18, 21 and 24), so the test runs in about nine seconds. Mutation: with the ceiling fade
        // deleted the worst cell is -28.1 dB (CHUG, step 2, MUTE 0), with the decay fade back at 150 ms
        // squared it is -47.5 dB (CHUG, step 3, MUTE 1).
        val leadValve = mapOf("DRIVE" to 0.78f, "SAG" to 0.4f, "TONE" to 0.5f, "CAB" to 0.95f)
        val landings = listOf(
            EndLanding("CHUG landing", MagnetVoice.CHUG, emptyMap(), Magnet.LANDING_VALVE.getValue(MagnetVoice.CHUG)),
            EndLanding("kit lead amp", MagnetVoice.CHUG, mapOf("BLEND" to 0.35f), leadValve),
            EndLanding("JANGLE landing", MagnetVoice.JANGLE, emptyMap(), Magnet.LANDING_VALVE.getValue(MagnetVoice.JANGLE)),
        )
        val steps = listOf(0, 1, 2, 3, 4, 7, 12, 18, 21, 24)
        val bar = -50.0
        val margin = 3.0
        val worst = linkedMapOf<String, Pair<Double, String>>()
        val short = ArrayList<String>()
        var cells = 0
        var ceilingCells = 0
        for (l in landings) {
            val v = l.voice
            for (step in steps) for (mute in listOf(0f, Magnet.defaults(v).getValue("MUTE"), 1f)) for (pick in endPicks(v)) {
                val macros = Magnet.defaults(v) + l.macros + mapOf("TUNE" to step / Magnet.TUNE_SEMITONES.toFloat(), "MUTE" to mute, "PICK" to pick)
                val ceiling = Magnet.string(v, macros).size >= (4f * Magnet.RENDER_RATE).toInt()
                val db = endDb(FxChain().withSection("valve", l.valve).process(Magnet.render(v, macros)))
                val cell = "${l.name} TUNE step $step MUTE $mute PICK $pick (${if (ceiling) "ring ceiling" else "decay"})"
                cells++
                if (ceiling) ceilingCells++
                if (worst[l.name]?.let { db > it.first } != false) worst[l.name] = db to cell
                if (!(db <= bar - margin)) short.add("$cell ends ${MagnetMeasure.round(db)} dB under its peak")
            }
        }
        println(
            "MAGNET landed end: $cells cells ($ceilingCells on the ring ceiling), worst " +
                worst.entries.joinToString("; ") { "${it.key} ${MagnetMeasure.round(it.value.first)} dB at ${it.value.second}" },
        )
        assertEquals(106f, Valve.gainFor(landings[0].valve.getValue("DRIVE")), 1f)
        assertTrue(short.isEmpty(), "a landed note ends within ${margin} dB of the ${-bar} dB bar: ${short.take(5)} (${short.size} of $cells cells)")
    }

    @Test
    fun `the dispatcher and the roster know MAGNET's state`() {
        // The dispatcher's round trip is above; MAGNET is registered with the patch system and has no preset roster.
        for (v in voices) assertTrue(Presets.forVoice("MAGNET", v.name).isEmpty(), "MAGNET has a roster: $v")
    }

    @Test
    fun `velocity re-renders MAGNET at PICK, softer is darker`() {
        // PICK is registered in Velocity.brightnessOverride because its sweep at the defaults moves the
        // centroid at least 1 percent per tenth on both voices (smallest 1.61 percent on JANGLE, 2.03
        // percent on CHUG: the numbers `PICK raises the centroid by at least 1 percent at every tenth on
        // both voices` asserts), so a soft hit is a re-render with a lower PICK. This reaches a bare patch
        // only: a landed pad's velocity layers still fall back to soften, because `layerAt` and
        // `canUseAtVelocity` require its fx to be null.
        for (voice in voices) {
            val patch = MagnetPatch("Vel Canary", voice, Magnet.defaults(voice))
            assertEquals("PICK", Velocity.brightnessSpec(patch)?.name, "$voice did not resolve PICK as its brightness macro")
            // The route is the re-render, not the soften fallback: the two give different samples.
            assertFalse(
                Velocity.atVelocity(patch, 0.3f).samples.contentEquals(Velocity.soften(patch.render(), 0.7f).samples),
                "$voice: atVelocity is the soften fallback",
            )
            val soft = FeatureExtractor.extract(Velocity.atVelocity(patch, 0.25f)).centroidHz
            val hard = FeatureExtractor.extract(Velocity.atVelocity(patch, 1f)).centroidHz
            assertTrue(soft < hard, "$voice: soft centroid $soft should be below hard centroid $hard")
        }
    }

    // ---------- the measured claims: the dry string, PICK's sweep, the pitch through VALVE, the landing ----------

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
                        if (!(abs(cents) <= 5.0)) over.add("$cell at $cents cents") // negated (<=): a NaN read fails too
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
                "h$k aligned ${MagnetMeasure.round(gapAligned)} dB under the single coil re h1, naive ${MagnetMeasure.round(gapNaive)} dB (spike: 34 dB aligned)",
        )
        // The hand-built pair is the engine's own: the constants repeated above (the 0.707 weight, the
        // spacing law) are pinned by the aligned call matching it to the bit.
        assertContentEquals(
            Strings.pickup(string, floatArrayOf(Magnet.BRIDGE, second), floatArrayOf(0.707f, 0.707f), f0, rate),
            aligned,
            "the engine's aligned humbucker is not the pair this test builds",
        )
        assertTrue(gapAligned >= 20.0, "the aligned humbucker is only $gapAligned dB under the single coil at h$k")
        assertTrue(gapNaive < 20.0, "the unaligned sum notches too ($gapNaive dB at h$k): this test cannot tell alignment from none")
    }

    @Test
    fun `BLEND moves the spectrum by at least 6 dB at some harmonic on both voices`() {
        // The full pickup with its resonance, TUNE 0.5 and the default MUTE and PICK, neck alone against
        // bridge alone. relH1 is dB against each render's own h1, so entry j is harmonic j + 1 and entries
        // 1 to 7 are h2 to h8. Both voices print before the assertion runs.
        val weak = ArrayList<String>()
        for (v in voices) {
            val f0 = Magnet.frequencyFor(v, 0.5f)
            val string = Magnet.string(v, Magnet.defaults(v) + ("TUNE" to 0.5f))
            val neck = MagnetMeasure.relH1(Magnet.pickup(v, string, f0, 0f), f0, 0.05f, 0.35f, 8)
            val bridge = MagnetMeasure.relH1(Magnet.pickup(v, string, f0, 1f), f0, 0.05f, 0.35f, 8)
            val swings = (1..7).map { abs(bridge[it] - neck[it]) }
            val swing = swings.max()
            val at = swings.indexOf(swing) + 2
            val spike = if (v == MagnetVoice.JANGLE) " (spike: 18 dB at JANGLE's h5)" else ""
            println("MAGNET BLEND $v: swing ${MagnetMeasure.round(swing)} dB, peaks at h$at (h2..h8 ${MagnetMeasure.round(swings)})$spike")
            if (!(swing >= 6.0)) weak.add("$v: BLEND moves no harmonic in h2 to h8 by 6 dB, the most is $swing dB at h$at")
        }
        assertTrue(weak.isEmpty(), weak.joinToString("; "))
    }

    @Test
    fun `MUTE shortens and darkens both voices`() {
        // MUTE 1 against MUTE 0 at TUNE 0.5 on the rendered note: under half the length, and a lower
        // centroid. The spike read 0.26 to 1.33 s at MUTE 1 against the 4 s ceiling. Both voices print
        // before the assertion runs.
        val wrong = ArrayList<String>()
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
            if (!(damped.samples.size < 0.5 * open.samples.size)) wrong.add("$v: MUTE 1 is ${damped.samples.size} samples against MUTE 0's ${open.samples.size}")
            if (!(dampedHz < openHz)) wrong.add("$v: MUTE 1's centroid $dampedHz Hz is not under MUTE 0's $openHz Hz")
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("; "))
    }

    @Test
    fun `PICK raises the centroid by at least 1 percent at every tenth on both voices`() {
        // Each voice at PICK 0 to 1 in tenths, the other macros at their defaults, the centroid read on the
        // rendered note. Asserted, the specification's two clauses: the centroid never falls between steps
        // and PICK 1 sits above PICK 0, and each tenth of travel moves it at least 1 percent (smallest read:
        // 1.61 percent on JANGLE, 2.03 percent on CHUG). The second clause is the one PICK is registered as
        // velocity's brightness macro on, and it is asserted at the defaults only: nothing here sweeps the
        // other macros. The whole table prints before the assertions run. The corner column mirrors
        // Magnet's private PICK_MIN_HZ and PICK_MAX_HZ (600 and 16000 Hz) and is print-only.
        val tenths = (0..10).map { it / 10f }
        val lines = ArrayList<String>()
        val falls = ArrayList<String>()
        val short = ArrayList<String>()
        val flat = ArrayList<String>()
        for (v in voices) {
            val hz = tenths.map { p -> FeatureExtractor.extract(Magnet.render(v, mapOf("PICK" to p))).centroidHz }
            var passing = 0
            val steps = ArrayList<String>()
            for (i in 1..10) {
                val pct = 100.0 * (hz[i] - hz[i - 1]) / hz[i - 1]
                val atLeastOne = pct >= 1.0
                if (atLeastOne) passing++
                val step = "$v PICK ${tenths[i - 1]} to ${tenths[i]}: ${hz[i - 1]} Hz to ${hz[i]} Hz"
                if (!(hz[i] >= hz[i - 1])) falls.add(step)
                if (!atLeastOne) short.add("$step, ${MagnetMeasure.round(pct, 2)} percent")
                steps.add(
                    "${tenths[i]} corner ${MagnetMeasure.round(Dsp.expMap(tenths[i], 600f, 16_000f).toDouble())} Hz " +
                        "centroid ${hz[i]} Hz ${MagnetMeasure.round(pct, 2)} percent ${if (atLeastOne) "at least 1" else "under 1"}",
                )
            }
            if (!(hz[10] > hz[0])) flat.add("$v: PICK 1 reads ${hz[10]} Hz, not above PICK 0's ${hz[0]} Hz")
            lines.add(
                "$v PICK 0.0 corner ${Dsp.expMap(0f, 600f, 16_000f)} Hz centroid ${hz[0]} Hz; ${steps.joinToString("; ")}; " +
                    "$passing of 10 tenths at least 1 percent",
            )
        }
        println("MAGNET PICK: ${lines.joinToString(" || ")}")
        assertTrue(falls.isEmpty(), "PICK lowers the centroid: $falls")
        assertTrue(flat.isEmpty(), "PICK does not raise the centroid end to end: $flat")
        assertTrue(short.isEmpty(), "PICK moves the centroid under 1 percent at a tenth: $short")
    }

    @Test
    fun `PICK never lowers the centroid at the corners of MUTE, TUNE and BLEND`() {
        // PICK is registered as velocity's brightness macro, so a softer hit must be darker wherever the
        // other three macros sit, not only at the defaults. Each voice at four of the eight corners of MUTE,
        // TUNE and BLEND (the long low note with the neck pickup, the short high note with the bridge, and
        // the two mixed corners, so each macro is read at both ends twice), PICK 0 to 1 in tenths, the
        // centroid read on the rendered note: it may not fall from one tenth to the next. The count of falls
        // prints before the assertion runs. The other four corners cost as many renders again.
        val tenths = (0..10).map { it / 10f }
        val corners = listOf(
            mapOf("MUTE" to 0f, "TUNE" to 0f, "BLEND" to 0f),
            mapOf("MUTE" to 1f, "TUNE" to 1f, "BLEND" to 1f),
            mapOf("MUTE" to 0f, "TUNE" to 1f, "BLEND" to 1f),
            mapOf("MUTE" to 1f, "TUNE" to 0f, "BLEND" to 0f),
        )
        val falls = ArrayList<String>()
        var worstFall = 0.0
        for (v in voices) {
            for (corner in corners) {
                val hz = tenths.map { p -> FeatureExtractor.extract(Magnet.render(v, Magnet.defaults(v) + corner + ("PICK" to p))).centroidHz }
                for (i in 1..10) {
                    if (!(hz[i] >= hz[i - 1])) {
                        falls.add("$v $corner PICK ${tenths[i - 1]} to ${tenths[i]}: ${hz[i - 1]} Hz to ${hz[i]} Hz")
                        worstFall = maxOf(worstFall, 100.0 * (hz[i - 1] - hz[i]) / hz[i - 1])
                    }
                }
                if (!(hz[10] > hz[0])) falls.add("$v $corner: PICK 1 reads ${hz[10]} Hz, not above PICK 0's ${hz[0]} Hz")
            }
        }
        println("MAGNET PICK corners: ${voices.size * corners.size} voice and corner sweeps, ${falls.size} falls, worst fall ${MagnetMeasure.round(worstFall, 2)} percent")
        assertTrue(falls.isEmpty(), "PICK lowers the centroid at a corner: ${falls.take(5)} (${falls.size} in all)")
    }

    // ---------- JANGLE's ring: the pitch-compensated loop (law L2), and CHUG left as it was ----------

    /** The render [v] gives with the ring law off: the string with `compensate = false`, then the pickup and the output chain. */
    private fun renderWithoutLaw(v: MagnetVoice, macros: Map<String, Float>): Snip {
        val m = Magnet.settled(macros, v)
        val f0 = Magnet.frequencyFor(v, m.getValue("TUNE"))
        val picked = Magnet.pickup(v, Magnet.string(v, m, compensate = false), f0, m.getValue("BLEND"))
        return Snip(Magnet.finish(picked, Magnet.RENDER_RATE), channels = 1, sampleRate = Dsp.RATE)
    }

    @Test
    fun `JANGLE's open string is the uncompensated string bit for bit and the law moves every other note`() {
        // TUNE 0 is r = 1, where every operation of the law is exact: the string and the finished render
        // equal the same chain with the law off, at four MUTEs and three PICKs. The other steps must differ
        // (else this test could not tell the law from no law). Mutation: reading the open string as the
        // first fret in `Magnet.string` (the reference one semitone up) fails the first loop.
        val v = MagnetVoice.JANGLE
        val unmoved = ArrayList<String>()
        val bad = ArrayList<String>()
        for (mute in listOf(0f, Magnet.defaults(v).getValue("MUTE"), 0.5f, 1f)) for (pick in listOf(0f, Magnet.defaults(v).getValue("PICK"), 1f)) {
            val macros = Magnet.defaults(v) + mapOf("TUNE" to 0f, "MUTE" to mute, "PICK" to pick)
            if (!Magnet.string(v, macros, compensate = true).contentEquals(Magnet.string(v, macros, compensate = false))) bad.add("string at MUTE $mute PICK $pick")
            if (!Magnet.render(v, macros).samples.contentEquals(renderWithoutLaw(v, macros).samples)) bad.add("render at MUTE $mute PICK $pick")
        }
        val moved = ArrayList<Int>()
        for (step in listOf(1, 7, 12, 19, 24)) {
            val macros = Magnet.defaults(v) + ("TUNE" to step / Magnet.TUNE_SEMITONES.toFloat())
            if (!Magnet.string(v, macros, compensate = true).contentEquals(Magnet.string(v, macros, compensate = false))) moved.add(step)
            else unmoved.add("TUNE step $step")
        }
        println("MAGNET ring law: the open string equals the uncompensated one at 12 cells (${bad.size} differences); the law moves the string at steps $moved")
        assertTrue(bad.isEmpty(), "the open string is not the uncompensated string: $bad")
        assertTrue(unmoved.isEmpty(), "the law leaves these notes as they were: $unmoved")
    }

    @Test
    fun `JANGLE's fundamental loses the same decibels per second at E2, E3 and E4`() {
        // The fundamental's loss between 0.1 and 0.4 s, on the finished note at the defaults (the sustain
        // spike's read and numbers: 7.7, 7.8 and 8.1 dB per second with the law, 7.7, 15.6 and 32.4 without).
        // The loop loses a fixed amount per round trip, so without the law a note twice as high loses twice
        // as much per second; asserted, E3 and E4 within 25 percent of E2's with the law, and, so that the
        // test can tell the law from none, E4's loss at least twice E2's without it. Mutation: the law's
        // exponent halved (fb^(1/sqrt r)) leaves E4 at about twice E2's.
        val v = MagnetVoice.JANGLE
        val tunes = listOf(0f, 0.5f, 1f)
        fun loss(law: Boolean, tune: Float): Double {
            val macros = Magnet.defaults(v) + ("TUNE" to tune)
            val note = if (law) Magnet.render(v, macros) else renderWithoutLaw(v, macros)
            return MagnetMeasure.h1LossDbPerSec(note.samples, Magnet.frequencyFor(v, tune).toDouble())
        }
        val on = tunes.map { loss(true, it) }
        val off = tunes.map { loss(false, it) }
        println("MAGNET ring law: the fundamental's loss at E2, E3 and E4 is ${MagnetMeasure.round(on)} dB per second with the law (spike: 7.7, 7.8, 8.1) and ${MagnetMeasure.round(off)} without (spike: 7.7, 15.6, 32.4)")
        assertTrue(on.all { it.isFinite() } && off.all { it.isFinite() }, "a loss could not be read: $on, $off")
        for ((name, i) in listOf("E3" to 1, "E4" to 2)) {
            assertTrue(abs(on[i] - on[0]) <= 0.25 * on[0], "$name loses ${on[i]} dB per second, E2 ${on[0]}: not within 25 percent")
        }
        assertTrue(off[2] >= 2.0 * off[0], "without the law E4 loses only ${off[2]} dB per second against E2's ${off[0]}: the premise is gone")
    }

    @Test
    fun `JANGLE's E4 rings to 20 dB down for at least 0 point 9 s and not much less than E3`() {
        // t-20: the seconds from the envelope's peak until a 40 ms RMS sits 20 dB under it, on the finished
        // dry note at the defaults (spike: E4 1.20 s and E3 1.34 s with the law, 0.32 and 0.71 s before it;
        // E2's is 1.10). Asserted: E4 at least 0.9 s, and E4 not shorter than E3 by more than a factor 1.3.
        val v = MagnetVoice.JANGLE
        fun t20(tune: Float, law: Boolean = true): Double {
            val macros = Magnet.defaults(v) + ("TUNE" to tune)
            return MagnetMeasure.fallSeconds((if (law) Magnet.render(v, macros) else renderWithoutLaw(v, macros)).samples, 20.0)
        }
        val e3 = t20(0.5f)
        val e4 = t20(1f)
        println(
            "MAGNET ring law: t-20 at E3 ${MagnetMeasure.round(e3, 2)} s and E4 ${MagnetMeasure.round(e4, 2)} s with the law " +
                "(spike: 1.34, 1.20), ${MagnetMeasure.round(t20(0.5f, law = false), 2)} and ${MagnetMeasure.round(t20(1f, law = false), 2)} s without (spike: 0.71, 0.32)",
        )
        assertTrue(e4 >= 0.9, "E4 rings only $e4 s to 20 dB down")
        assertTrue(e4 * 1.3 >= e3, "E4's t-20 ($e4 s) is more than a factor 1.3 under E3's ($e3 s)")
    }

    @Test
    fun `the compensated loop keeps its feedback under 1 and every open ring inside the ceiling`() {
        // Analytic over all 25 steps and four MUTEs: fb' = fb^(1/r) stays under 1 (the spike's largest was
        // 0.9995, at E4 and MUTE 0) and never falls below fb. Rendered, MUTE 0 (the longest ring) at all 25
        // steps: every string finite, inside -1..1, no longer than the 4 s budget, and decaying (the RMS over
        // 3.0 to 3.5 s under the RMS over 0.25 to 0.75 s; the spike's worst ratio was 0.44).
        val v = MagnetVoice.JANGLE
        val fRef = Keys.midiHz(Magnet.rootMidi(v)).toDouble()
        var largest = 0.0
        val bad = ArrayList<String>()
        for (step in 0..Magnet.TUNE_SEMITONES) {
            val f0 = Magnet.frequencyFor(v, step / Magnet.TUNE_SEMITONES.toFloat()).toDouble()
            for (mute in listOf(0f, 0.15f, 0.5f, 1f)) {
                val base = Strings.damping(mute, 7_000f)
                val law = Magnet.compensated(base, f0 / fRef)
                largest = maxOf(largest, law.fb.toDouble())
                if (!(law.fb < 1f && law.fb >= base.fb && law.loopHz >= base.loopHz)) bad.add("step $step MUTE $mute: fb ${base.fb} to ${law.fb}, loop ${base.loopHz} to ${law.loopHz} Hz")
            }
        }
        var worstRatio = 0.0
        val rate = Magnet.RENDER_RATE
        fun rms(x: FloatArray, from: Double, to: Double): Double {
            val a = (from * rate).toInt()
            val b = minOf(x.size, (to * rate).toInt())
            var sum = 0.0
            for (i in a until b) sum += x[i].toDouble() * x[i]
            return Math.sqrt(sum / (b - a))
        }
        for (step in 0..Magnet.TUNE_SEMITONES) {
            val string = Magnet.string(v, Magnet.defaults(v) + mapOf("TUNE" to step / Magnet.TUNE_SEMITONES.toFloat(), "MUTE" to 0f))
            if (!string.all { it.isFinite() && it in -1f..1f }) bad.add("step $step MUTE 0: a sample is not finite or is outside -1..1")
            if (string.size > (4f * rate).toInt()) bad.add("step $step MUTE 0: ${string.size} samples is past the 4 s budget")
            val ratio = rms(string, 3.0, 3.5) / rms(string, 0.25, 0.75)
            worstRatio = maxOf(worstRatio, ratio)
            if (!(ratio < 1.0)) bad.add("step $step MUTE 0: the ring does not decay (late over early RMS $ratio)")
        }
        println("MAGNET ring law: largest fb' ${MagnetMeasure.round(largest, 6)} (spike: 0.9995), worst late over early RMS ${MagnetMeasure.round(worstRatio, 3)} (spike: 0.436)")
        assertTrue(bad.isEmpty(), "the compensated loop is unstable or runs past its ceiling: ${bad.take(5)}")
    }

    @Test
    fun `CHUG's ring is not compensated`() {
        // The owner passed CHUG's voice, so the law is JANGLE's alone: at TUNE 0.5 and 1 and three MUTEs the
        // string and the render equal the same chain with the law off (the spec flag, off for CHUG), and the
        // string equals a hand-built one, Strings.damping read directly, everywhere but its last 650 ms
        // (the fades' reach: the trim's 400 ms and the ceiling fade's 250), so nothing but the law's choice is
        // tested to be old. Mutation: CHUG's compensate flag turned on fails the first two checks.
        val v = MagnetVoice.CHUG
        val rate = Magnet.RENDER_RATE
        val reach = (0.65f * rate).toInt()
        val bad = ArrayList<String>()
        var handBuilt = 0
        for (tune in listOf(0.5f, 1f)) for (mute in listOf(0f, Magnet.defaults(v).getValue("MUTE"), 1f)) {
            val macros = Magnet.defaults(v) + mapOf("TUNE" to tune, "MUTE" to mute)
            val cell = "TUNE $tune MUTE $mute"
            val string = Magnet.string(v, macros)
            if (!string.contentEquals(Magnet.string(v, macros, compensate = false))) bad.add("$cell: the string is not the uncompensated one")
            if (!Magnet.render(v, macros).samples.contentEquals(renderWithoutLaw(v, macros).samples)) bad.add("$cell: the render is not the uncompensated one")
            val f0 = Magnet.frequencyFor(v, tune)
            val raw = Strings.pluck(
                f0, 4.0f, Strings.damping(mute, 5_500f), Dsp.expMap(macros.getValue("PICK"), 600f, 16_000f),
                Dsp.seedFor("MAGNET", v.name, f0), rate, position = 0.085f,
            )
            val upTo = string.size - reach
            if (upTo > rate / 20) {
                handBuilt++
                for (i in 0 until upTo) if (string[i] != raw[i]) { bad.add("$cell: the string differs from the hand-built one at sample $i"); break }
            }
        }
        println("MAGNET ring law: CHUG's strings equal the uncompensated chain at 6 cells and the hand-built one before their last 650 ms at $handBuilt of them (${bad.size} differences)")
        assertTrue(handBuilt >= 4, "the hand-built comparison ran on only $handBuilt cells")
        assertTrue(bad.isEmpty(), "CHUG's ring changed: ${bad.take(5)}")
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
        // DRIVE 1 with no SAG, flat TONE and no cabinet (gain 1000 on VALVE's current law; the specification's
        // bar was written on V1's law, where DRIVE 1 was gain 35; printed only). The pitch is the
        // interpolated autocorrelation, never Pitch.detect: its integer lag steps up to 12.9 cents at
        // JANGLE's E4 and a hot amp can hand it an octave or nothing. A silent or NaN render reads as NaN
        // cents and fails the finite check, and a NaN shift fails the bar too.
        val hot = mapOf("DRIVE" to 1f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f)
        val cells = ArrayList<String>()
        val over = ArrayList<String>()
        val unread = ArrayList<String>()
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
                if (!(dryCents.isFinite() && landedCents.isFinite())) unread.add("$cell dry $dryCents cents, landing $landedCents cents")
                if (!(abs(landedShift) <= 10.0)) over.add("$cell at ${MagnetMeasure.round(landedShift, 2)} cents")
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
        assertTrue(unread.isEmpty(), "the pitch cannot be read (silent or NaN render): $unread")
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
         * corners read at most 8.6e-9 because the pickup comb has no DC gain and the output chain's
         * 20 Hz high-pass removes the rest. These checks do not prove the output chain's DC stage: that
         * is `finish removes a constant offset` and `finish attenuates a slow drift`, which read
         * [Magnet.finish] on an input that carries an offset.
         */
        const val DC_BOUND = 1e-4
    }
}
