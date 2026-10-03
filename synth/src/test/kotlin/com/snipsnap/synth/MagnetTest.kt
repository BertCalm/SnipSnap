package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
 * its peak, across MUTE, PICK and sampled TUNE steps), JANGLE's pitch-compensated ring (the open string
 * unchanged, the fundamental's loss per second flat across the range, E4's t-20, a stable loop) with
 * CHUG's ring left as it was, and PICK's map (the owner's pick of the ends: neutral at the default PICK,
 * every coupled value exactly its neutral number and the string and note equal to the path built without
 * the map, which is not a claim that the render is bit for bit the build before the map, since the
 * ring-ceiling fade was re-chosen with it; the ends octaves apart at the kit's notes, the loop corner the
 * ring law and PICK ask for together held to the toolkit's own clamp with every note still in tune).
 *
 * The three long sweeps (the end grid, the clean ends, the in-tune cells) and the pitch through VALVE
 * spread their cells over a small pool ([SWEEP_THREADS]) and read the results back in the cells' own
 * order, so what each prints, counts and asserts is what a plain loop gives; the renders that the end
 * grid and the clean ends both read are made once ([dryRender]).
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
        // Bit for bit, for both voices at the defaults, at an off-default set with PICK on the thumb side
        // and at one with PICK past the default (where the pickup resonance's scale is not 1): the real call
        // chain, string, then pickup with the macros' resonance scale, then finish. The arrays are copied
        // before finish, which works in place. Mutation: `render` not handing the pickup its resonance
        // scale fails the wire set (JANGLE, from sample 0), and `JANGLE's open string ...` too.
        val thumb = mapOf("TUNE" to 0.3f, "MUTE" to 0.7f, "PICK" to 0.2f, "BLEND" to 0.8f)
        val wire = mapOf("TUNE" to 0.8f, "MUTE" to 0.3f, "PICK" to 0.95f, "BLEND" to 0.4f)
        for (v in voices) {
            assertTrue(Magnet.resonanceScale(v, wire) > 1f, "$v: the wire set does not open the resonance, so this test cannot tell a missing scale")
            for ((name, macros) in listOf("defaults" to Magnet.defaults(v), "thumb" to Magnet.defaults(v) + thumb, "wire" to Magnet.defaults(v) + wire)) {
                val m = Magnet.settled(macros, v)
                val f0 = Magnet.frequencyFor(v, m.getValue("TUNE"))
                val string = Magnet.string(v, m).copyOf()
                val picked = Magnet.pickup(v, string, f0, m.getValue("BLEND"), resonanceScale = Magnet.resonanceScale(v, m)).copyOf()
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

    /** One end-grid read: the note through its landing's amp, and whether the trim left its string to the ring ceiling. */
    private class EndRead(val db: Double, val ceiling: Boolean)

    /**
     * [c]'s note at [macros] (the cell's own, or with BLEND moved) through the cell's landing amp. One
     * render serves both reads: a string the ring ceiling cut is the whole 4 s budget, so the finished
     * note is the whole 4 s at the rack's rate (decimation floors the length, so a string a sample or
     * two short of the budget reads as short); a string the trim ended on its decay is shorter.
     */
    private fun endRead(c: EndCell, macros: Map<String, Float>): EndRead {
        val rendered = dryRender(c.landing.voice, macros)
        val ceiling = rendered.samples.size >= (4f * Dsp.RATE).toInt()
        return EndRead(endDb(FxChain().withSection("valve", c.landing.valve).process(rendered)), ceiling)
    }

    @Test
    fun `a landed note ends at least 53 dB under its peak at every landing, MUTE, PICK and sampled TUNE step`() {
        // The three amps a MAGNET note lands through: CHUG's landing (DRIVE 0.85, gain 106), the kit's lead
        // amp (DRIVE 0.78, gain 37, CHUG at BLEND 0.35: a literal, as in SynthKitTest, because the kit's
        // LEAD_VALVE is private) and JANGLE's landing. The bar is the review's -50 dB on the peak of the last
        // 20 ms with 3 dB to spare (so -53), for MUTE 0, the voice's default and 1 at PICK 0, 0.15, the
        // default and 1. A string whose trim ended it on its decay ends on Magnet's decay fade, one the ring
        // ceiling cut on the trim's 400 ms fade and Magnet's own on top of it; the amp lifts either end by its
        // gain. The TUNE steps are, per landing, the binding cells of the full search (all 25 steps, 900
        // cells; at gain 106 the ceiling cells at steps 1, 2, 7, 8, 12 and 18 at MUTE 0, the decay cells at
        // MUTE 1 at steps 3, 4, 18, 21, 22 and 24) and the ends of the range (264 cells, 119 on the ring
        // ceiling), and each cell is one render and one pass through the amp, about 8 seconds for the test on
        // four threads and about 28 seconds on one. Asserted besides the bar: the grid is the 264 cells and
        // at least 119 of them take the ring-ceiling path (a refactor that sent every string down the decay
        // path, or shrank the grid, would otherwise leave the ceiling fade untested and the test green).
        // BLEND is held at each landing's own in the grid (CHUG's landing 1, the lead amp 0.35, JANGLE's
        // 0.5), so the 16 worst cells of each landing are read again at BLEND 0 and at BLEND 1, outside the
        // 264 and 119: the spectrum the amp lifts moves with BLEND by 14.7 to 18.0 dB at some harmonic, and
        // it moves the ranking (in a one-off sweep of the whole grid at BLEND 0, 0.25, 0.75 and 1, 1 056
        // cells, the worst read was CHUG's landing at step 4, MUTE 1 and PICK 0.55 at BLEND 0, -54.6 dB,
        // which is only the 16th worst of that landing's 144 cells at its own BLEND). Mutations: with the
        // ceiling fade deleted the worst cell is -20.1 dB (CHUG, step 3, MUTE 0, PICK 0) and 80 of the 264
        // cells fail; with the ceiling fade back at its first choice (squared, 250 ms) the worst is -50.1 dB
        // (CHUG, step 2, MUTE 0, PICK 0.15) and 3 cells fail; with it at fourth power 200 ms the worst is
        // -52.7 dB (the same cell) and 1 fails; with the decay fade back at 150 ms squared the worst is -47.5
        // dB (CHUG, step 3, MUTE 1, PICK 0.55) and 26 cells fail; with the PICK map as the first build had it
        // only 116 cells take the ring-ceiling path and the coverage check fails.
        val bar = -50.0
        val margin = 3.0
        val cells = endCells()
        val reads = sweep(cells) { endRead(it, it.macros) }
        val worst = linkedMapOf<String, Pair<Double, String>>()
        val short = ArrayList<String>()
        var ceilingCells = 0
        for ((c, r) in cells.zip(reads)) {
            val cell = "${c.landing.name} TUNE step ${c.step} MUTE ${c.mute} PICK ${c.pick} (${if (r.ceiling) "ring ceiling" else "decay"})"
            if (r.ceiling) ceilingCells++
            if (worst[c.landing.name]?.let { r.db > it.first } != false) worst[c.landing.name] = r.db to cell
            if (!(r.db <= bar - margin)) short.add("$cell ends ${MagnetMeasure.round(r.db)} dB under its peak")
        }
        println(
            "MAGNET landed end: ${cells.size} cells ($ceilingCells on the ring ceiling), worst " +
                worst.entries.joinToString("; ") { "${it.key} ${MagnetMeasure.round(it.value.first)} dB at ${it.value.second}" },
        )
        // The worst cells of each landing, at BLEND 0 and 1 (sortedByDescending is stable: a tie keeps the
        // first). A read at the cell's own BLEND (CHUG's landing is at 1) is the grid's read, not a new render.
        val worstCells = END_LANDINGS.flatMap { l -> cells.indices.filter { cells[it].landing === l }.sortedByDescending { reads[it].db }.take(BLEND_CELLS_PER_LANDING) }
        val blendPairs = worstCells.flatMap { i -> listOf(0f, 1f).map { b -> i to b } }
        val blendReads = sweep(blendPairs) { (i, b) -> if (cells[i].macros.getValue("BLEND") == b) reads[i].db else endRead(cells[i], cells[i].macros + ("BLEND" to b)).db }
        val blendShort = ArrayList<String>()
        val blendWorst = linkedMapOf<String, Pair<Double, String>>()
        var blendRenders = 0
        for ((k, pair) in blendPairs.withIndex()) {
            val c = cells[pair.first]
            val db = blendReads[k]
            if (c.macros.getValue("BLEND") != pair.second) blendRenders++
            val cell = "${c.landing.name} TUNE step ${c.step} MUTE ${c.mute} PICK ${c.pick} BLEND ${pair.second}"
            if (blendWorst[c.landing.name]?.let { db > it.first } != false) blendWorst[c.landing.name] = db to cell
            if (!(db <= bar - margin)) blendShort.add("$cell ends ${MagnetMeasure.round(db)} dB under its peak")
        }
        println(
            "MAGNET landed end BLEND: ${blendPairs.size} reads at BLEND 0 and 1 of the worst $BLEND_CELLS_PER_LANDING cells of each landing ($blendRenders of them new renders), worst " +
                blendWorst.entries.joinToString("; ") { "${it.key} ${MagnetMeasure.round(it.value.first)} dB at ${it.value.second}" } +
                "; ${sharedRenderCount()} renders of the grid are shared with the clean ends",
        )
        assertEquals(264, cells.size, "the end grid is not the 264 cells it is stated to be")
        assertTrue(ceilingCells >= 119, "only $ceilingCells of the ${cells.size} cells take the ring-ceiling path, the grid is stated to have 119: the ceiling fade is under-tested")
        assertEquals(106f, Valve.gainFor(END_LANDINGS[0].valve.getValue("DRIVE")), 1f)
        assertEquals(37f, Valve.gainFor(END_LANDINGS[1].valve.getValue("DRIVE")), 1f)
        assertTrue(short.isEmpty(), "a landed note ends within ${margin} dB of the ${-bar} dB bar: ${short.take(5)} (${short.size} of ${cells.size} cells)")
        assertTrue(blendShort.isEmpty(), "a landed note at BLEND 0 or 1 ends within ${margin} dB of the ${-bar} dB bar: ${blendShort.take(5)} (${blendShort.size} of ${blendPairs.size} reads)")
    }

    @Test
    fun `the dispatcher and the roster know MAGNET's state`() {
        // The dispatcher's round trip is above; MAGNET is registered with the patch system and has no preset roster.
        for (v in voices) assertTrue(Presets.forVoice("MAGNET", v.name).isEmpty(), "MAGNET has a roster: $v")
    }

    @Test
    fun `velocity re-renders MAGNET at PICK, softer is darker`() {
        // PICK is registered in Velocity.brightnessOverride because its sweep at the defaults moves the
        // centroid at least 1 percent per tenth on both voices (smallest 16.82 percent on JANGLE, 13.85
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
            println("MAGNET velocity $voice: onset centroid ${soft} Hz at velocity 0.25 and ${hard} Hz at velocity 1, a ratio of ${MagnetMeasure.round(hard.toDouble() / soft, 2)}")
            assertTrue(soft < hard, "$voice: soft centroid $soft should be below hard centroid $hard")
        }
    }

    // ---------- the measured claims: the dry string, PICK's sweep, the pitch through VALVE, the landing ----------

    @Test
    fun `every note of both voices is within five cents on the dry string at PICK 0, the default and 1`() {
        // Read on the raw 176.4 kHz buffer with FineTuning, before the output chain: both voices, all 25
        // TUNE steps, MUTE and BLEND each at 0, 0.5 and 1, and PICK at 0, its default and 1 (1 350 cells; the
        // first build read the default PICK only, 450). PICK 1 is where the loop's corner is widest (x4, and
        // on JANGLE x2 more from the ring law, past the toolkit's own clamp at E4 and MUTE 0) and PICK 0
        // where the exciter is two 150 Hz poles. The string does not read BLEND, so one string serves the
        // three blends of a cell, and the 450 strings are spread over the sweep's threads; the pickup takes the
        // macros' resonance scale, as the render does. Mutation:
        // the thumb's corner floor at 0 Hz instead of 150 reads NaN at every PICK 0 cell (450 of the 1 350
        // cells fail); the default-PICK reads alone could not see it.
        val grid = listOf(0f, 0.5f, 1f)
        var cells = 0
        var worst = 0.0
        var worstCell = ""
        val lowest = linkedMapOf<String, Double>()
        val highest = linkedMapOf<String, Double>()
        val over = ArrayList<String>()
        // One cell per string (voice, PICK, TUNE step, MUTE); its three BLENDs are read off the one string.
        class TuneCell(val v: MagnetVoice, val pick: Float, val step: Int, val mute: Float)
        val strings = ArrayList<TuneCell>()
        for (v in voices) for (pick in listOf(0f, Magnet.defaults(v).getValue("PICK"), 1f)) for (step in 0..Magnet.TUNE_SEMITONES) for (mute in grid) strings.add(TuneCell(v, pick, step, mute))
        val reads = sweep(strings) { c ->
            val tune = c.step / Magnet.TUNE_SEMITONES.toFloat()
            val f0 = Magnet.frequencyFor(c.v, tune)
            val macros = Magnet.defaults(c.v) + mapOf("TUNE" to tune, "MUTE" to c.mute, "PICK" to c.pick)
            val string = Magnet.string(c.v, macros)
            val scale = Magnet.resonanceScale(c.v, macros)
            DoubleArray(grid.size) { b ->
                val dry = Magnet.pickup(c.v, string, f0, grid[b], resonanceScale = scale)
                FineTuning.cents(FineTuning.measuredHz(dry, Magnet.RENDER_RATE, f0), f0.toDouble())
            }
        }
        for ((c, centsByBlend) in strings.zip(reads)) {
            val key = "${c.v} PICK ${c.pick}"
            for ((b, cents) in centsByBlend.withIndex()) {
                val cell = "${c.v} TUNE step ${c.step} MUTE ${c.mute} PICK ${c.pick} BLEND ${grid[b]}"
                cells++
                lowest[key] = min(lowest[key] ?: cents, cents)
                highest[key] = maxOf(highest[key] ?: cents, cents)
                if (abs(cents) > worst) { worst = abs(cents); worstCell = "$cell at $cents cents" }
                if (!(abs(cents) <= 5.0)) over.add("$cell at $cents cents") // negated (<=): a NaN read fails too
            }
        }
        val ranges = lowest.keys.joinToString("; ") { "$it ${MagnetMeasure.round(lowest.getValue(it), 2)} to ${MagnetMeasure.round(highest.getValue(it), 2)}" }
        println("MAGNET in tune: $cells cells, cents $ranges, worst |cents| ${MagnetMeasure.round(worst, 2)} at $worstCell (spike: -0.62 to 1.11 over 36 cells)")
        assertEquals(1350, cells)
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
        // 16.82 percent on JANGLE, 13.85 percent on CHUG; the map before ruling 23 read 1.61 on JANGLE, after
        // the ring law of ruling 20 (1.77 at the first build, which had no ring law), and 2.03 on CHUG). The
        // second clause is the one PICK is registered as velocity's brightness macro on, and it is asserted
        // at the defaults only: nothing here sweeps the other macros. The whole table prints before the
        // assertions run; its corner column is the map's own exciter corner, [Magnet.pickCornerHz] (print only).
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
                    "${tenths[i]} corner ${MagnetMeasure.round(Magnet.pickCornerHz(v, tenths[i]).toDouble())} Hz " +
                        "centroid ${hz[i]} Hz ${MagnetMeasure.round(pct, 2)} percent ${if (atLeastOne) "at least 1" else "under 1"}",
                )
            }
            if (!(hz[10] > hz[0])) flat.add("$v: PICK 1 reads ${hz[10]} Hz, not above PICK 0's ${hz[0]} Hz")
            lines.add(
                "$v PICK 0.0 corner ${Magnet.pickCornerHz(v, 0f)} Hz centroid ${hz[0]} Hz; ${steps.joinToString("; ")}; " +
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

    // ---------- PICK's map: the owner's pick of the ends (candidate "B" of the PICK-ends spike) ----------

    /**
     * The string the engine would give without the PICK map: the exciter's corner is
     * `expMap(PICK, 600, 16000)` through one pole (the first build's and 8a's map), the loop's body corner
     * is the voice's own, and the toolkit's own exciter and loop do the rest. Built from the toolkit,
     * [Magnet.compensated] and [Magnet.ended], so the ring law and the end fades are the engine's own and
     * this reference pins only the part the map changed (the exciter, the loop's corner and, in
     * [unmappedRender], the pickup resonance's corner): it proves the map is neutral at the default, and
     * it cannot see a change to the ring law or to the fades, which both sides share. It is not the build
     * before the map. The 8b run compared whole renders against a verbatim copy of 8a's `Magnet.kt` (both
     * voices, TUNE 25 x MUTE 6 x BLEND 4 = 1 200 cells, strings, dry notes and landed notes): with the end
     * fades as 8a had them all 1 200 were equal, and with the ceiling fade re-chosen (see `CEILING_FADE`)
     * the 884 decay-path cells are still equal and the 316 ring-ceiling cells differ from 3.75 s into the
     * 4 s note on.
     */
    private fun unmappedString(v: MagnetVoice, macros: Map<String, Float>): FloatArray {
        val m = Magnet.settled(macros, v)
        val f0 = Magnet.frequencyFor(v, m.getValue("TUNE"))
        val base = Strings.damping(m.getValue("MUTE"), if (v == MagnetVoice.JANGLE) 7_000f else 5_500f)
        val damping = if (v == MagnetVoice.JANGLE) Magnet.compensated(base, f0.toDouble() / Keys.midiHz(Magnet.rootMidi(v))) else base
        val raw = Strings.pluck(
            f0, 4.0f, damping, Dsp.expMap(m.getValue("PICK"), 600f, 16_000f),
            Dsp.seedFor("MAGNET", v.name, f0), Magnet.RENDER_RATE, position = 0.085f,
        )
        return Magnet.ended(raw)
    }

    /** The dry note the engine would give without the PICK map: [unmappedString] through the pickup with its resonance unscaled and the output chain. */
    private fun unmappedRender(v: MagnetVoice, macros: Map<String, Float>): Snip {
        val m = Magnet.settled(macros, v)
        val f0 = Magnet.frequencyFor(v, m.getValue("TUNE"))
        val picked = Magnet.pickup(v, unmappedString(v, m), f0, m.getValue("BLEND"))
        return Snip(Magnet.finish(picked, Magnet.RENDER_RATE), channels = 1, sampleRate = Dsp.RATE)
    }

    @Test
    fun `the PICK map is neutral at the default PICK, every coupled value exactly its neutral number`() {
        // The map is pinned at the default (Dsp.around): the exciter's corner, the second pole's weight, the
        // loop's multiplier and the resonance's multiplier are each exactly their neutral value there. That
        // is what this proves, and the string and the finished note equal the path built without the map
        // (both voices, four macro sets, dry), using the engine's own ring law and end fades. It does NOT
        // prove the render is bit for bit the build before the map: the ring-ceiling fade was re-chosen with
        // the map, and 316 of 1 200 ring-ceiling cells differ from 8a's engine in their last quarter second
        // (see [unmappedString]). Just either side of the default must differ (else this test could not tell
        // the map from none). The four neutral values are read first and reported with the cells. Mutations:
        // the corner's centre read from 15 999 instead of 16 000 Hz fails the corner (4302.454 against
        // 4302.615 Hz on JANGLE) and the string and note cells, and CHUG's hand-built string in the ring
        // test; a resonance multiplier of 1.0001 at the default fails the multiplier and the four finished
        // notes of JANGLE and none of its strings.
        val sets = listOf(
            "defaults" to emptyMap(),
            "TUNE 0.3, MUTE 0.7, BLEND 0.8" to mapOf("TUNE" to 0.3f, "MUTE" to 0.7f, "BLEND" to 0.8f),
            "E4 or the top note, open, neck" to mapOf("TUNE" to 1f, "MUTE" to 0f, "BLEND" to 0f),
            "open string, damped, lead blend" to mapOf("TUNE" to 0f, "MUTE" to 1f, "BLEND" to 0.35f),
        )
        val bad = ArrayList<String>()
        var cells = 0
        for (v in voices) {
            val d = Magnet.defaults(v).getValue("PICK")
            val neutral = listOf(
                Triple("the exciter's corner is the first build's expMap(PICK, 600, 16000)", Dsp.expMap(d, 600f, 16_000f), Magnet.pickCornerHz(v, d)),
                Triple("the second pole's weight is 0", 0f, Magnet.secondPoleWeight(v, d)),
                Triple("the loop's multiplier is 1", 1f, Magnet.loopScale(v, d)),
                Triple("the resonance's multiplier is 1", 1f, Magnet.resonanceScale(v, mapOf("PICK" to d))),
            )
            for ((what, want, got) in neutral) if (want != got) bad.add("$v at the default: $what, but it is $got (want $want)")
            for ((name, set) in sets) {
                val macros = Magnet.defaults(v) + set + ("PICK" to d)
                cells++
                if (!Magnet.string(v, macros).contentEquals(unmappedString(v, macros))) bad.add("$v ($name): the string")
                if (!Magnet.render(v, macros).samples.contentEquals(unmappedRender(v, macros).samples)) bad.add("$v ($name): the finished note")
            }
            for (p in listOf(d - 0.05f, d + 0.05f)) {
                val macros = Magnet.defaults(v) + ("PICK" to p)
                if (Magnet.string(v, macros).contentEquals(unmappedString(v, macros))) bad.add("$v at PICK $p: the string equals the unmapped one, the map does nothing there")
            }
        }
        println("MAGNET PICK map: the four coupled values are exactly neutral at the default PICK, and $cells cells (both voices, ${sets.size} macro sets) equal the path built without the map, string and note bit for bit, ${bad.size} differences")
        assertTrue(bad.isEmpty(), "the PICK map is not neutral at the default PICK: ${bad.take(5)}")
    }

    @Test
    fun `PICK's ends are octaves apart at the kit's notes`() {
        // JANGLE's E3 and CHUG's B2 (TUNE 0.5, the notes the kit plays), dry, the other macros at their
        // defaults: the onset centroid (the PICK tests' own measure) at PICK 0 and 1, in octaves under and over
        // the default's. The spike measured 2.82 under and 1.60 over at JANGLE E3 and 1.91 and 2.18 at CHUG B2
        // (JANGLE's before the ring law of ruling 20: with it 2.95 and 1.46); the first build's map read 1.11 and
        // 0.22 at E3 and 0.95 and 0.36 at B2. Asserted: at least 2.0 and 1.2 at E3, 1.5 and 1.2 at B2. Mutations:
        // the first build's map (PICK_THUMB_HZ 600, no second pole, the loop and resonance multipliers 1) fails
        // both voices on both sides (those four numbers); no second pole fails PICK 0 alone (1.73 on JANGLE, 1.35
        // on CHUG); no loop opening fails PICK 1 alone (0.77 and 1.10); no resonance opening fails PICK 1 alone
        // (0.43 on JANGLE, 1.10 on CHUG).
        val bars = mapOf(MagnetVoice.JANGLE to (2.0 to 1.2), MagnetVoice.CHUG to (1.5 to 1.2))
        val short = ArrayList<String>()
        val lines = ArrayList<String>()
        for ((v, bar) in bars) {
            fun hz(pick: Float) = FeatureExtractor.extract(Magnet.render(v, Magnet.defaults(v) + mapOf("TUNE" to 0.5f, "PICK" to pick))).centroidHz.toDouble()
            val dHz = hz(Magnet.defaults(v).getValue("PICK"))
            val down = Math.log(dHz / hz(0f)) / Math.log(2.0)
            val up = Math.log(hz(1f) / dHz) / Math.log(2.0)
            lines.add("$v ${Scales.nameOf(Magnet.rootMidi(v) + 12)} ${MagnetMeasure.round(down, 2)} octaves down and ${MagnetMeasure.round(up, 2)} up (centroid ${MagnetMeasure.round(hz(0f), 0)} Hz, ${MagnetMeasure.round(dHz, 0)} Hz, ${MagnetMeasure.round(hz(1f), 0)} Hz)")
            if (!(down >= bar.first)) short.add("$v: PICK 0 is only ${MagnetMeasure.round(down, 2)} octaves under the default, the bar is ${bar.first}")
            if (!(up >= bar.second)) short.add("$v: PICK 1 is only ${MagnetMeasure.round(up, 2)} octaves over the default, the bar is ${bar.second}")
        }
        println("MAGNET PICK ends, dry: ${lines.joinToString("; ")} (spike: JANGLE E3 2.82 and 1.60, CHUG B2 1.91 and 2.18)")
        assertTrue(short.isEmpty(), short.joinToString("; "))
    }

    @Test
    fun `the PICK map reaches 150 Hz and 16 kHz and each part of it moves the way it should`() {
        // Anchor points and shape: the exciter's corner is 150 Hz at PICK 0 and 16 kHz at PICK 1 and never
        // falls; the second pole's weight is 1 at PICK 0, (d - PICK) / d under the default d and 0 from the
        // default up; the loop's multiplier is 1 up to the default and 4 at PICK 1; the resonance's is 1 up to
        // the default and 2.5 at PICK 1; none of the three falls as PICK rises.
        for (v in voices) {
            val d = Magnet.defaults(v).getValue("PICK")
            assertEquals(150f, Magnet.pickCornerHz(v, 0f), 0.01f, "$v: the corner at PICK 0")
            assertEquals(16_000f, Magnet.pickCornerHz(v, 1f), 1f, "$v: the corner at PICK 1")
            assertEquals(1f, Magnet.secondPoleWeight(v, 0f), "$v: the second pole at PICK 0")
            assertEquals(0.5f, Magnet.secondPoleWeight(v, d / 2f), 1e-6f, "$v: the second pole half way down")
            assertEquals(4f, Magnet.loopScale(v, 1f), 1e-4f, "$v: the loop's multiplier at PICK 1")
            assertEquals(2.5f, Magnet.resonanceScale(v, mapOf("PICK" to 1f)), 1e-4f, "$v: the resonance's multiplier at PICK 1")
            var prevCorner = 0f
            var prevLoop = 0f
            var prevRes = 0f
            for (i in 0..100) {
                val p = i / 100f
                val corner = Magnet.pickCornerHz(v, p)
                val loop = Magnet.loopScale(v, p)
                val res = Magnet.resonanceScale(v, mapOf("PICK" to p))
                assertTrue(corner >= prevCorner && loop >= prevLoop && res >= prevRes, "$v at PICK $p: a part of the map falls (corner $corner, loop $loop, resonance $res)")
                if (p <= d) assertTrue(loop == 1f && res == 1f, "$v at PICK $p: the loop ($loop) or the resonance ($res) is moved under the default")
                if (p >= d) assertTrue(Magnet.secondPoleWeight(v, p) == 0f, "$v at PICK $p: the second pole is in above the default")
                prevCorner = corner
                prevLoop = loop
                prevRes = res
            }
        }
    }

    @Test
    fun `the second pole is the toolkit's exciter with one more low-pass`() {
        // Magnet.twoPoleExciter is a copy of Strings.pluckExciter's two private helpers plus a second pole
        // (the toolkit is frozen). At mix 0 it must be the toolkit's own exciter sample for sample, comb and
        // cut included; at mix 1 the burst is smoother by the second pole (position 0 reads the burst alone:
        // its first-difference energy at least 3 times lower); and the mix is linear (the half mix is the mean
        // of the two, to float rounding). Mutations: the second pole fed from the noise the first pole reads
        // (two identical poles) fails the smoothness check (1.0 times, not 3) and the wired-in check below;
        // the zero-mean subtraction dropped fails the copy's identity at mix 0 (index 0 differs).
        val rate = Magnet.RENDER_RATE
        for ((n, maxLen) in listOf(1_200 to 1_500, 536 to 560)) {
            val seed = Dsp.seedFor("MAGNET", "TEST", n.toFloat())
            val toolkit = Strings.pluckExciter(n, 147f, 3_000f, 0.085f, seed, rate, maxLen)
            val copy = Magnet.twoPoleExciter(0f)(n, 147f, 3_000f, 0.085f, seed, rate, maxLen)
            assertContentEquals(toolkit, copy, "n $n: the copy at mix 0 is not the toolkit's exciter")
        }
        val n = 1_200
        val seed = Dsp.seedFor("MAGNET", "TEST", 1f)
        fun burst(mix: Float) = Magnet.twoPoleExciter(mix)(n, 147f, 3_000f, 0f, seed, rate, n)
        fun roughness(x: FloatArray): Double { var e = 0.0; for (i in 1 until x.size) { val d = x[i] - x[i - 1]; e += d.toDouble() * d }; return e }
        val one = burst(0f)
        val half = burst(0.5f)
        val two = burst(1f)
        val ratio = roughness(one) / roughness(two)
        var linear = 0f
        for (i in one.indices) linear = maxOf(linear, abs(half[i] - 0.5f * (one[i] + two[i])))
        println("MAGNET PICK second pole: the two-pole burst is ${MagnetMeasure.round(ratio, 1)} times smoother than the one-pole (first-difference energy), the half mix is the mean to $linear")
        assertTrue(ratio >= 3.0, "the two-pole burst is only $ratio times smoother than the one-pole's")
        assertTrue(linear <= 1e-6f * one.maxOf { abs(it) }.coerceAtLeast(1f), "the half mix is not the mean of the two: off by $linear")
    }

    @Test
    fun `below the default PICK the second pole is in the string and the map is continuous at the default`() {
        // At PICK 0 the string differs from the one a single pole at the same 150 Hz corner gives, and the
        // note through it is darker (a second pole is a softer contact); and the onset centroid 0.001 under
        // and over the default is within 2 percent of the default's (a step at the pin would show: the sweep
        // moves CHUG's centroid 72 percent in the tenth around its default, 0.7 percent per 0.001, and a
        // jump at the pin would be tens of percent). Mutations: the second pole's weight forced to 0 fails the
        // first two checks on both voices; a weight that jumps by 0.3 at the default fails the continuity check
        // (JANGLE 2772 Hz at PICK 0.599 against 2983 Hz, CHUG 932 against 1026 Hz).
        val bad = ArrayList<String>()
        val lines = ArrayList<String>()
        for (v in voices) {
            val d = Magnet.defaults(v).getValue("PICK")
            val macros = Magnet.defaults(v) + ("PICK" to 0f)
            val m = Magnet.settled(macros, v)
            val f0 = Magnet.frequencyFor(v, m.getValue("TUNE"))
            val base = Strings.damping(m.getValue("MUTE"), if (v == MagnetVoice.JANGLE) 7_000f else 5_500f)
            val damping = if (v == MagnetVoice.JANGLE) Magnet.compensated(base, f0.toDouble() / Keys.midiHz(Magnet.rootMidi(v))) else base
            val onePole = Magnet.ended(Strings.pluck(f0, 4.0f, damping, 150f, Dsp.seedFor("MAGNET", v.name, f0), Magnet.RENDER_RATE, position = 0.085f))
            val engine = Magnet.string(v, macros)
            if (engine.contentEquals(onePole)) bad.add("$v: the string at PICK 0 is the single pole's, the second pole is not in it")
            fun note(string: FloatArray) = Snip(Magnet.finish(Magnet.pickup(v, string.copyOf(), f0, m.getValue("BLEND")), Magnet.RENDER_RATE), channels = 1, sampleRate = Dsp.RATE)
            val twoHz = FeatureExtractor.extract(note(engine)).centroidHz.toDouble()
            val oneHz = FeatureExtractor.extract(note(onePole)).centroidHz.toDouble()
            if (!(twoHz < oneHz)) bad.add("$v: the two-pole note's centroid $twoHz Hz is not under the single pole's $oneHz Hz")
            fun hz(p: Float) = FeatureExtractor.extract(Magnet.render(v, Magnet.defaults(v) + ("PICK" to p))).centroidHz.toDouble()
            val atD = hz(d)
            val near = listOf(d - 0.001f, d + 0.001f).map { hz(it) }
            for ((p, x) in listOf(d - 0.001f, d + 0.001f).zip(near)) if (!(abs(x - atD) <= 0.02 * atD)) bad.add("$v: the centroid at PICK $p is $x Hz against the default's $atD Hz")
            lines.add("$v PICK 0 centroid ${MagnetMeasure.round(twoHz, 0)} Hz against ${MagnetMeasure.round(oneHz, 0)} Hz through one pole; default ${MagnetMeasure.round(atD, 0)} Hz, 0.001 under ${MagnetMeasure.round(near[0], 0)} Hz, over ${MagnetMeasure.round(near[1], 0)} Hz")
        }
        println("MAGNET PICK second pole: ${lines.joinToString("; ")}")
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun `every TUNE step renders clean at both ends of PICK, at MUTE 0 and the default`() {
        // MagnetTest.assertClean's criteria at the ends the map moved: both voices, all 25 steps, MUTE 0 (the
        // longest ring, and where JANGLE's loop corner is asked past the toolkit's clamp at PICK 1) and the
        // voice's default MUTE, PICK 0 and PICK 1, BLEND at its default. The whole TUNE x MUTE x PICK x BLEND
        // grid (900 renders) was read in the 8b run: 0 problems. The renders the end grid takes too are
        // made once ([dryRender]). Mutation: the thumb's corner floor at 0 Hz fails the first PICK 0 cell (a
        // bad sample).
        val cells = cleanEndsCells()
        val dcs = sweep(cells) { c -> assertClean(dryRender(c.voice, c.macros), "${c.voice} TUNE step ${c.step} MUTE ${c.mute} PICK ${c.pick}") }
        println("MAGNET PICK ends: ${cells.size} renders clean, worst DC ${dcs.max()} (bound $DC_BOUND)")
    }

    // ---------- JANGLE's ring: the pitch-compensated loop (law L2), and CHUG left as it was ----------

    /** The render [v] gives with the ring law off: the string with `compensate = false`, then the pickup and the output chain. */
    private fun renderWithoutLaw(v: MagnetVoice, macros: Map<String, Float>): Snip {
        val m = Magnet.settled(macros, v)
        val f0 = Magnet.frequencyFor(v, m.getValue("TUNE"))
        val picked = Magnet.pickup(v, Magnet.string(v, m, compensate = false), f0, m.getValue("BLEND"), resonanceScale = Magnet.resonanceScale(v, m))
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
    fun `the compensated loop keeps its feedback under 1 and every open ring inside the ceiling, at every PICK`() {
        // Analytic over all 25 steps, four MUTEs and PICK 0, the default and 1: fb' = fb^(1/r) stays under 1
        // (the spike's largest was 0.9995, at E4 and MUTE 0; PICK does not enter it) and never falls below fb,
        // and the loop's corner, the voice's body corner times PICK's loop multiplier times the ring law's
        // sqrt r, is never under the corner PICK alone asks for (it is asked past the toolkit's 0.45 of the
        // render rate at PICK 1 where E4's x2 meets MUTE near 0: the count prints). Rendered, MUTE 0 (the
        // longest ring) at all 25 steps and at the default PICK and PICK 1: every string finite, inside
        // -1..1 at the default PICK (8a's bound; the raw string is before the output chain's levelling, its
        // peak reads 0.87 there) and inside -4..4 at PICK 1 (its peak reads 1.51, the exciter being 16 kHz
        // of burst; the bound is the stability proxy there, the decay check below the real one, and the
        // peaks print), no longer than the 4 s budget, and decaying (the RMS over 3.0 to
        // 3.5 s under the RMS over 0.25 to 0.75 s; the spike's worst ratio was 0.44 at the default PICK).
        // Mutation: the law's feedback plus 0.0007 fails the analytic check from TUNE step 19 up at MUTE 0 (fb' 1.00003
        // at step 19, more above), at every PICK.
        val v = MagnetVoice.JANGLE
        val fRef = Keys.midiHz(Magnet.rootMidi(v)).toDouble()
        val rate = Magnet.RENDER_RATE
        val dPick = Magnet.defaults(v).getValue("PICK")
        var largest = 0.0
        var largestCorner = 0.0
        var pastClamp = 0
        var asked = 0
        val bad = ArrayList<String>()
        for (pick in listOf(0f, dPick, 1f)) for (step in 0..Magnet.TUNE_SEMITONES) {
            val f0 = Magnet.frequencyFor(v, step / Magnet.TUNE_SEMITONES.toFloat()).toDouble()
            for (mute in listOf(0f, 0.15f, 0.5f, 1f)) {
                val base = Strings.damping(mute, 7_000f * Magnet.loopScale(v, pick))
                val law = Magnet.compensated(base, f0 / fRef)
                largest = maxOf(largest, law.fb.toDouble())
                largestCorner = maxOf(largestCorner, law.loopHz.toDouble())
                asked++
                if (law.loopHz > rate * 0.45f) pastClamp++
                if (!(law.fb < 1f && law.fb >= base.fb && law.loopHz >= base.loopHz)) bad.add("step $step MUTE $mute PICK $pick: fb ${base.fb} to ${law.fb}, loop ${base.loopHz} to ${law.loopHz} Hz")
            }
        }
        var worstRatio = 0.0
        var worstRatioAtWire = 0.0
        var peakAtDefault = 0f
        var peakAtWire = 0f
        fun rms(x: FloatArray, from: Double, to: Double): Double {
            val a = (from * rate).toInt()
            val b = minOf(x.size, (to * rate).toInt())
            var sum = 0.0
            for (i in a until b) sum += x[i].toDouble() * x[i]
            return Math.sqrt(sum / (b - a))
        }
        for (pick in listOf(dPick, 1f)) for (step in 0..Magnet.TUNE_SEMITONES) {
            val string = Magnet.string(v, Magnet.defaults(v) + mapOf("TUNE" to step / Magnet.TUNE_SEMITONES.toFloat(), "MUTE" to 0f, "PICK" to pick))
            val cell = "step $step MUTE 0 PICK $pick"
            // 8a's bound (-1..1) at the default PICK; PICK 1 is where the raw string is allowed more (-4..4).
            val bound = if (pick == dPick) 1f else 4f
            val peak = if (string.all { it.isFinite() }) string.maxOf { abs(it) } else Float.POSITIVE_INFINITY
            if (pick == dPick) peakAtDefault = maxOf(peakAtDefault, peak) else peakAtWire = maxOf(peakAtWire, peak)
            if (!(peak <= bound)) bad.add("$cell: the raw string's peak is $peak (a sample not finite reads infinite), outside -$bound..$bound")
            if (string.size > (4f * rate).toInt()) bad.add("$cell: ${string.size} samples is past the 4 s budget")
            val ratio = rms(string, 3.0, 3.5) / rms(string, 0.25, 0.75)
            if (pick == 1f) worstRatioAtWire = maxOf(worstRatioAtWire, ratio) else worstRatio = maxOf(worstRatio, ratio)
            if (!(ratio < 1.0)) bad.add("$cell: the ring does not decay (late over early RMS $ratio)")
        }
        println(
            "MAGNET ring law: largest fb' ${MagnetMeasure.round(largest, 6)} (spike: 0.9995), worst late over early RMS ${MagnetMeasure.round(worstRatio, 3)} " +
                "at the default PICK (spike: 0.436) and ${MagnetMeasure.round(worstRatioAtWire, 3)} at PICK 1, raw string peak ${MagnetMeasure.round(peakAtDefault.toDouble(), 2)} " +
                "at the default PICK (bound 1) and ${MagnetMeasure.round(peakAtWire.toDouble(), 2)} at PICK 1 (bound 4); the largest loop corner asked is " +
                "${MagnetMeasure.round(largestCorner, 0)} Hz (${MagnetMeasure.round(largestCorner / rate, 3)} of the render rate), $pastClamp of $asked cells are asked past the toolkit's 0.45",
        )
        assertTrue(pastClamp > 0, "no cell is asked past the toolkit's clamp: the test of that corner has nothing to read")
        assertTrue(bad.isEmpty(), "the compensated loop is unstable or runs past its ceiling: ${bad.take(5)}")
    }

    @Test
    fun `a loop corner asked past the toolkit's clamp is the clamp, in the budget and in the string`() {
        // The engine states no corner ceiling of its own: JANGLE at E4, MUTE 0 and PICK 1 asks the loop for
        // 89 600 Hz (the body's 7 000 x 1.6 at MUTE 0, x4 PICK, x2 the ring law), past the 0.45 of the render
        // rate (79 380 Hz) that Dsp.OnePole.lp and Strings.tune both clamp at, and that is the whole of the
        // ceiling. A lower ceiling of the engine's own (0.40 down to 0.25 of the rate) was measured, in the
        // PICK-ends task's report, to shift the cents by 0.00 at every cell it changed, to change no length
        // and to leave every landed end as it was (JANGLE's landing: -92.3 dB without a ceiling, -92.7 at
        // 0.40); and every cell of the in-tune test is within its bar (1.66 cents at the worst). Asserted:
        // the tuning budget at the asked corner is the budget at the clamp, and the string the engine builds
        // there is the string built by hand with the corner given as exactly the clamp. Mutations: the
        // engine handing the loop a corner of 0.4 of the rate fails the string check (the first differing
        // sample is 535); Strings.tune's own clamp removed (a budget computed at the asked corner while the
        // filter is clamped) fails the budget check (the allpass coefficient reads 0.2449 and not 0.2606),
        // and the in-tune test does not see it: its cells read the same cents to the printed digit, the
        // shift is about 0.05 cent.
        val v = MagnetVoice.JANGLE
        val rate = Magnet.RENDER_RATE
        val macros = Magnet.defaults(v) + mapOf("TUNE" to 1f, "MUTE" to 0f, "PICK" to 1f)
        val f0 = Magnet.frequencyFor(v, 1f)
        val base = Strings.damping(0f, 7_000f * Magnet.loopScale(v, 1f))
        val law = Magnet.compensated(base, f0.toDouble() / Keys.midiHz(Magnet.rootMidi(v)))
        val clamp = rate * 0.45f
        assertTrue(law.loopHz > clamp, "E4 at MUTE 0 and PICK 1 is asked for only ${law.loopHz} Hz, not past the clamp $clamp")
        val atAsked = Strings.tune(f0, law.loopHz, rate)
        val atClamp = Strings.tune(f0, clamp, rate)
        assertEquals(atClamp.n, atAsked.n, "the tuning budget's integer delay at the asked corner")
        assertEquals(atClamp.a, atAsked.a, "the tuning budget's allpass at the asked corner")
        val byHand = Magnet.ended(
            Strings.pluck(
                f0, 4.0f, Strings.Damping(clamp, law.fb), Magnet.pickCornerHz(v, 1f),
                Dsp.seedFor("MAGNET", v.name, f0), rate, position = 0.085f,
            ),
        )
        assertContentEquals(byHand, Magnet.string(v, macros), "the engine's string at the asked corner is not the string built at the clamp")
        println("MAGNET loop corner: E4 at MUTE 0 and PICK 1 asks ${MagnetMeasure.round(law.loopHz.toDouble(), 0)} Hz, the toolkit clamps at ${MagnetMeasure.round(clamp.toDouble(), 0)} Hz; the budget and the string are the clamp's")
    }

    @Test
    fun `CHUG's ring is not compensated`() {
        // The owner passed CHUG's voice, so the law is JANGLE's alone: at TUNE 0.5 and 1 and three MUTEs the
        // string and the render equal the same chain with the law off (the spec flag, off for CHUG), and the
        // string equals a hand-built one, Strings.damping read directly, everywhere but its last 600 ms
        // (the fades' reach: every fade is put on the last samples of the string, so they overlap and the
        // reach is the longest, the trim's 400 ms; the ceiling fade is 225 ms and the decay fade 150 ms;
        // 600 ms is that with margin), so nothing but the law's choice is tested to be old. At the default
        // PICK, where the map is neutral. Mutation: CHUG's compensate flag turned on fails the first two
        // checks.
        val v = MagnetVoice.CHUG
        val rate = Magnet.RENDER_RATE
        val reach = (0.6f * rate).toInt()
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
        println("MAGNET ring law: CHUG's strings equal the uncompensated chain at 6 cells and the hand-built one before their last 600 ms at $handBuilt of them (${bad.size} differences)")
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
        // Both voices at TUNE 0, 0.5 and 1, at PICK 0, the voice's default and 1 (a wire note has a loop at
        // x4 and a resonance at x2.5, a thumb note is a soft two-pole burst: the notes the amp could pull
        // off pitch), the other macros at their defaults: the dry note, then the same note through each
        // voice's landing amp (asserted, the specification's 10 cents) and through DRIVE 1 with no SAG, flat
        // TONE and no cabinet (gain 1000 on VALVE's current law; the specification's bar was written on V1's
        // law, where DRIVE 1 was gain 35; printed only). The pitch is the interpolated autocorrelation,
        // never Pitch.detect: its integer lag steps up to 12.9 cents at JANGLE's E4 and a hot amp can hand
        // it an octave or nothing. A silent or NaN render reads as NaN cents and fails the finite check, and
        // a NaN shift fails the bar too. Mutation: the thumb's corner floor at 0 Hz reads NaN at every PICK
        // 0 cell and fails the finite check there alone.
        val hot = mapOf("DRIVE" to 1f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f)
        class PitchCell(val v: MagnetVoice, val pick: Float, val tune: Float)
        class PitchRead(val dryCents: Double, val landedCents: Double, val hotCents: Double)
        val pitchCells = voices.flatMap { v -> listOf(0f, Magnet.defaults(v).getValue("PICK"), 1f).flatMap { pick -> listOf(0f, 0.5f, 1f).map { tune -> PitchCell(v, pick, tune) } } }
        val reads = sweep(pitchCells) { c ->
            val f0 = Magnet.frequencyFor(c.v, c.tune)
            val dry = Magnet.render(c.v, Magnet.defaults(c.v) + mapOf("TUNE" to c.tune, "PICK" to c.pick))
            PitchRead(centsOf(dry, f0), centsOf(Valve.process(dry, Magnet.LANDING_VALVE.getValue(c.v)), f0), centsOf(Valve.process(dry, hot), f0))
        }
        val cells = ArrayList<String>()
        val over = ArrayList<String>()
        val unread = ArrayList<String>()
        var worstLanding = 0.0
        var worstHot = 0.0
        val byPick = linkedMapOf<String, Pair<Double, Double>>()
        for ((c, r) in pitchCells.zip(reads)) {
            val f0 = Magnet.frequencyFor(c.v, c.tune)
            val landedShift = r.landedCents - r.dryCents
            val hotShift = r.hotCents - r.dryCents
            worstLanding = maxOf(worstLanding, abs(landedShift))
            worstHot = maxOf(worstHot, abs(hotShift))
            val pickName = when (c.pick) { 0f -> "PICK 0"; 1f -> "PICK 1"; else -> "the default PICK" }
            val soFar = byPick[pickName] ?: (0.0 to 0.0)
            byPick[pickName] = maxOf(soFar.first, abs(landedShift)) to maxOf(soFar.second, abs(hotShift))
            val cell = "${c.v} PICK ${c.pick} TUNE ${c.tune} (${MagnetMeasure.round(f0.toDouble(), 2)} Hz)"
            if (!(r.dryCents.isFinite() && r.landedCents.isFinite())) unread.add("$cell dry ${r.dryCents} cents, landing ${r.landedCents} cents")
            if (!(abs(landedShift) <= 10.0)) over.add("$cell at ${MagnetMeasure.round(landedShift, 2)} cents")
            cells.add(
                "$cell dry ${MagnetMeasure.round(r.dryCents, 2)} cents, landing ${MagnetMeasure.round(r.landedCents, 2)} cents " +
                    "(wet minus dry ${MagnetMeasure.round(landedShift, 2)}), gain ${Valve.gainFor(hot.getValue("DRIVE"))} " +
                    "${MagnetMeasure.round(r.hotCents, 2)} cents (wet minus dry ${MagnetMeasure.round(hotShift, 2)})",
            )
        }
        println(
            "MAGNET pitch through VALVE: ${cells.joinToString("; ")}; worst |wet minus dry| " +
                "${MagnetMeasure.round(worstLanding, 2)} cents at the landing, ${MagnetMeasure.round(worstHot, 2)} cents at gain 1000; by PICK (landing, gain 1000): " +
                byPick.entries.joinToString("; ") { "${it.key} ${MagnetMeasure.round(it.value.first, 2)}, ${MagnetMeasure.round(it.value.second, 2)}" },
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
         * corners read at most 8.4e-10 (the PICK-ends cells 8.6e-10) because the pickup comb has no DC
         * gain and the output chain's 20 Hz high-pass removes the rest. These checks do not prove the
         * output chain's DC stage: that is `finish removes a constant offset` and `finish attenuates a
         * slow drift`, which read [Magnet.finish] on an input that carries an offset.
         */
        const val DC_BOUND = 1e-4

        /**
         * The threads a long sweep's cells are spread over: four at most (the test JVM's heap is the
         * Gradle default of 512 MiB, and each cell holds a few 176.4 kHz buffers while it runs).
         */
        val SWEEP_THREADS = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors()))

        /**
         * [f] over [cells], on up to [threads] threads, the results in the cells' own order: a sweep reads
         * them back with a plain loop, so what it prints, counts and asserts (and which failing cell is
         * the first) is what the single-threaded loop gave. [f] must be a pure function of its cell. A
         * cell that throws throws here, when its turn in the order comes.
         */
        fun <T, R> sweep(cells: List<T>, threads: Int = SWEEP_THREADS, f: (T) -> R): List<R> {
            if (threads <= 1 || cells.size <= 1) return cells.map(f)
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val pending = cells.map { cell -> pool.submit(Callable { runCatching { f(cell) } }) }
                return pending.map { it.get().getOrThrow() }
            } finally {
                pool.shutdownNow()
            }
        }

        /**
         * One amp a landed note is read through: the voice, the macros it overrides on top of the
         * defaults, the VALVE macros, and the TUNE steps read at it (the cells that bound it in the
         * full search, and the ends of the range).
         */
        class EndLanding(val name: String, val voice: MagnetVoice, val macros: Map<String, Float>, val valve: Map<String, Float>, val steps: List<Int>)

        /**
         * The three amps the end grid reads: CHUG's landing, the kit's lead amp (a literal, as in
         * SynthKitTest, because the kit's LEAD_VALVE is private) and JANGLE's landing.
         */
        val END_LANDINGS: List<EndLanding> = listOf(
            EndLanding("CHUG landing", MagnetVoice.CHUG, emptyMap(), Magnet.LANDING_VALVE.getValue(MagnetVoice.CHUG), listOf(0, 1, 2, 3, 4, 7, 8, 12, 18, 21, 22, 24)),
            EndLanding("kit lead amp", MagnetVoice.CHUG, mapOf("BLEND" to 0.35f), mapOf("DRIVE" to 0.78f, "SAG" to 0.4f, "TONE" to 0.5f, "CAB" to 0.95f), listOf(0, 1, 2, 4, 6, 24)),
            EndLanding("JANGLE landing", MagnetVoice.JANGLE, emptyMap(), Magnet.LANDING_VALVE.getValue(MagnetVoice.JANGLE), listOf(0, 2, 5, 24)),
        )

        /**
         * The PICK values the end grid reads for [v]: both ends of the knob, the default, and 0.15. The
         * map moves the exciter's corner, the second pole, the loop's corner and the resonance with PICK
         * (the owner's pick of the ends), and each changes how long the string rings and what the amp
         * lifts at its end. The binding PICK is not an end: the search over PICK 0, 0.02, 0.05, 0.1, 0.15,
         * 0.2, 0.25, 0.3, 0.4 and the default found the worst cell of the ceiling path at 0.15 (CHUG at
         * gain 106, MUTE 0, TUNE step 2), so 0.15 is read here.
         */
        fun endPicks(v: MagnetVoice): List<Float> = listOf(0f, 0.15f, Magnet.defaults(v).getValue("PICK"), 1f)

        /** How many of the end grid's worst cells per landing are read again at BLEND 0 and 1. */
        const val BLEND_CELLS_PER_LANDING = 16

        /** One cell of the end grid: a landing, a TUNE step, a MUTE and a PICK (BLEND the landing's own). */
        class EndCell(val landing: EndLanding, val step: Int, val mute: Float, val pick: Float) {
            val macros: Map<String, Float> = Magnet.defaults(landing.voice) + landing.macros +
                mapOf("TUNE" to step / Magnet.TUNE_SEMITONES.toFloat(), "MUTE" to mute, "PICK" to pick)
        }

        /** The end grid's cells, landing by landing, then step, MUTE (0, the default, 1) and PICK. */
        fun endCells(): List<EndCell> = END_LANDINGS.flatMap { l ->
            val v = l.voice
            l.steps.flatMap { step ->
                listOf(0f, Magnet.defaults(v).getValue("MUTE"), 1f).flatMap { mute -> endPicks(v).map { pick -> EndCell(l, step, mute, pick) } }
            }
        }

        /** One cell of the clean-ends sweep: a voice, a TUNE step, a MUTE (0 or the default) and a PICK (0 or 1), BLEND the default. */
        class CleanCell(val voice: MagnetVoice, val step: Int, val mute: Float, val pick: Float) {
            val macros: Map<String, Float> = Magnet.defaults(voice) +
                mapOf("TUNE" to step / Magnet.TUNE_SEMITONES.toFloat(), "MUTE" to mute, "PICK" to pick)
        }

        /** The clean-ends cells: voice, then every TUNE step, then MUTE and PICK. */
        fun cleanEndsCells(): List<CleanCell> = MagnetVoice.entries.flatMap { v ->
            (0..Magnet.TUNE_SEMITONES).flatMap { step ->
                listOf(0f, Magnet.defaults(v).getValue("MUTE")).flatMap { mute -> listOf(0f, 1f).map { pick -> CleanCell(v, step, mute, pick) } }
            }
        }

        private fun renderKey(v: MagnetVoice, macros: Map<String, Float>): Pair<MagnetVoice, Map<String, Float>> = v to Magnet.settled(macros, v)

        /**
         * The renders the end grid and the clean ends both read: found by intersecting their two cell
         * lists, so a change to either grid moves it. The in-tune sweep reads strings and pickups, not
         * finished notes, so it shares none of them.
         */
        private val SHARED_RENDERS: Set<Pair<MagnetVoice, Map<String, Float>>> by lazy {
            endCells().map { renderKey(it.landing.voice, it.macros) }.toSet() intersect cleanEndsCells().map { renderKey(it.voice, it.macros) }.toSet()
        }

        /** The finished dry notes of [SHARED_RENDERS], by voice and settled macros; filled on first read, cleared nowhere. */
        private val sharedRenders = ConcurrentHashMap<Pair<MagnetVoice, Map<String, Float>>, Snip>()

        fun sharedRenderCount(): Int = SHARED_RENDERS.size

        /**
         * [Magnet.render] of [macros], made once if the end grid and the clean ends both read it. A
         * caller gets its own copy of the samples, so nothing a caller does to its note reaches the next.
         */
        fun dryRender(v: MagnetVoice, macros: Map<String, Float>): Snip {
            val key = renderKey(v, macros)
            if (key !in SHARED_RENDERS) return Magnet.render(v, macros)
            val cached = sharedRenders[key] ?: Magnet.render(v, macros).also { sharedRenders[key] = it }
            return Snip(cached.samples.copyOf(), cached.channels, cached.sampleRate)
        }
    }
}
