package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BALLAST's claims, on the engine itself. The structure is what the spec says it is, so each test reads it in the
 * simulation's own parts ([Ballast.simulate] with `record`): the bass is heard directly, the frame and the wires are
 * the bank, the tiles' knocks are counted and their energy is the loss that rings the glass. Every bar sits between
 * what was measured (printed by each test) and the failure it guards. Nothing here says the engine sounds good: the
 * audition page decides that.
 */
class BallastTest {

    private val rate = Dsp.RATE
    private val raw = Dsp.RATE * Dsp.OVERSAMPLE

    private fun f(x: Double, d: Int = 2) = "%.${d}f".format(java.util.Locale.ROOT, x)

    private fun db(x: Double) = 20.0 * log10(max(x, 1e-30))

    private fun render(voice: BallastVoice, vararg macros: Pair<String, Float>): Snip = Ballast.render(voice, Ballast.defaults(voice) + macros.toMap())

    private val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)

    /** A Hann-windowed single-bin DFT magnitude of [x] over `[from, from + n)` at [hz], for a stream at [sampleRate]. */
    private fun amp(x: FloatArray, from: Int, n: Int, hz: Double, sampleRate: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            val ph = 2.0 * PI * hz * i / sampleRate
            re += w * x[from + i] * cos(ph)
            im -= w * x[from + i] * sin(ph)
        }
        return hypot(re, im) / n
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun macrosAt(voice: BallastVoice, vararg macros: Pair<String, Float>) = Ballast.settled(macros.toMap(), voice)

    private fun run(voice: BallastVoice, m: Map<String, Float>, velocity: Double = 1.0, probe: Ballast.Probe = Ballast.Probe(record = true)): Ballast.Run {
        val hz = Ballast.frequencyFor(voice, m.getValue("TUNE")).toDouble()
        return Ballast.simulate(voice, Ballast.oneShotPlan(voice, hz, m), m, velocity, probe)
    }

    /** A steady (held) stretch, as a loop is rendered but longer, for reading the structure after its onset has gone. */
    private fun steady(voice: BallastVoice, m: Map<String, Float>, seconds: Double, probe: Ballast.Probe = Ballast.Probe(record = true)): Ballast.Run {
        val hz = Ballast.frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val loop = Ballast.planLoop(voice, hz)
        val plan = Ballast.Plan(loop.hz, loop.ratioB, loop.wobbleCycles / loop.seconds, true, (seconds * raw).toInt(), 0, 0)
        return Ballast.simulate(voice, plan, m, 1.0, probe)
    }

    // ---- the contract ----------------------------------------------------------------------------------------------

    @Test
    fun `every voice declares the same seven knobs in the same order, at the spec's defaults`() {
        val spec = mapOf(
            BallastVoice.ROOT to listOf(.40f, .25f, .30f, .10f, .35f),
            BallastVoice.WIRE to listOf(.45f, .70f, .45f, .20f, .50f),
            BallastVoice.GLINT to listOf(.40f, .45f, .60f, .65f, .45f),
            BallastVoice.DEEP to listOf(.45f, .45f, .70f, .15f, .65f),
            BallastVoice.BLOOM to listOf(.55f, .65f, .50f, .40f, .70f),
            BallastVoice.SWARM to listOf(.75f, .65f, .80f, .85f, .65f),
        )
        for (voice in BallastVoice.entries) {
            val knobs = Ballast.macrosFor(voice)
            assertEquals(listOf("TUNE", "DRIVE", "SYMPATHY", "SPAN", "GLASS", "FRAME", "HOLD"), knobs.map { it.name })
            assertEquals(spec.getValue(voice), knobs.drop(1).take(5).map { it.default }, "$voice's defaults")
            assertEquals(0.5f, knobs[0].default)
            assertEquals(0f, knobs[6].default, "HOLD 0 is the finite lifecycle")
            assertEquals(listOf(.5f, .45f, .35f, .40f, .25f, .45f, 0f), knobs.map { it.neutral }, "$voice's neutral column")
        }
    }

    @Test
    fun `an out of range macro is clamped and an unknown key is dropped`() {
        for (voice in BallastVoice.entries) {
            val s = Ballast.settled(mapOf("GLASS" to 1.7f, "DRIVE" to -0.4f, "SPARKLE" to 0.3f), voice)
            assertEquals(1f, s.getValue("GLASS"))
            assertEquals(0f, s.getValue("DRIVE"))
            assertTrue("SPARKLE" !in s)
            assertEquals(Ballast.macrosFor(voice).map { it.name }.toSet(), s.keys)
        }
    }

    @Test
    fun `a recipe with an unknown macro, a value out of range or a blank name is refused`() {
        assertFailsWith<IllegalArgumentException> { BallastPatch("Bad", BallastVoice.ROOT, mapOf("SPARKLE" to 0.5f)) }
        assertFailsWith<IllegalArgumentException> { BallastPatch("Bad", BallastVoice.WIRE, mapOf("GLASS" to 1.5f)) }
        assertFailsWith<IllegalArgumentException> { BallastPatch(" ", BallastVoice.DEEP, emptyMap()) }
        assertFailsWith<JsonException> { BallastPatch.fromJsonText("""{"engine":"BALLAST","version":1,"name":"x","voice":"SAW","macros":{}}""") }
    }

    @Test
    fun `a patch round-trips through json and is found by the dispatchers`() {
        for (voice in BallastVoice.entries) {
            val p = BallastPatch("Round Trip", voice, Ballast.defaults(voice) + mapOf("TUNE" to 0.4f, "GLASS" to 0.9f))
            assertEquals(p, BallastPatch.fromJsonText(p.toJsonText()))
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
        }
    }

    @Test
    fun `TUNE walks C1 to C4 in semitones`() {
        assertEquals(24, Ballast.midiFor(BallastVoice.ROOT, 0f))
        assertEquals(36, Ballast.midiFor(BallastVoice.ROOT, 1f / 3f))
        assertEquals(48, Ballast.midiFor(BallastVoice.ROOT, 2f / 3f))
        assertEquals(60, Ballast.midiFor(BallastVoice.ROOT, 1f))
    }

    // ---- the render ------------------------------------------------------------------------------------------------

    @Test
    fun `every voice renders finite, levelled audio of its predicted length at the bottom, middle and top of TUNE`() {
        val cases = BallastVoice.entries.flatMap { v -> listOf(0f, 0.5f, 1f).map { v to it } }
        val snips = cases.parallelStream().map { (v, t) -> Triple(v, t, render(v, "TUNE" to t)) }.toList()
        for ((voice, tune, snip) in snips) {
            val label = "$voice TUNE $tune"
            assertEquals(Ballast.renderFrames(voice, mapOf("TUNE" to tune)), snip.frameCount, "$label: the filed length is not the rendered length")
            assertTrue(snip.samples.all { it.isFinite() }, "$label: non-finite")
            assertTrue(snip.peak() <= 1f, "$label: clipped")
            val loud = Loudness.of(snip)
            assertTrue(loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f, "$label: loudness $loud")
            assertTrue(abs(snip.samples.average()) < 0.01, "$label: DC")
            assertEquals(0f, snip.samples.last(), 1e-6f, "$label: not faded out")
        }
    }

    @Test
    fun `identical input gives identical output`() {
        for (voice in BallastVoice.entries) {
            val m = Ballast.defaults(voice) + mapOf("TUNE" to 0.3f, "GLASS" to 0.8f, "DRIVE" to 0.7f)
            assertContentEquals(Ballast.render(voice, m).samples, Ballast.render(voice, m).samples, "$voice")
        }
        val a = Ballast.renderLoopMeasured(BallastVoice.BLOOM, mapOf("HOLD" to 1f, "GLASS" to 0.7f))
        val b = Ballast.renderLoopMeasured(BallastVoice.BLOOM, mapOf("HOLD" to 1f, "GLASS" to 0.7f))
        assertContentEquals(a.loop, b.loop, "a held loop")
    }

    @Test
    fun `every macro changes the sound, and an unknown key does not`() {
        for (voice in BallastVoice.entries) {
            val base = render(voice, "TUNE" to 0.35f, "DRIVE" to 0.6f, "GLASS" to 0.6f)
            for (spec in Ballast.macrosFor(voice)) {
                val lo = render(voice, "TUNE" to 0.35f, "DRIVE" to 0.6f, "GLASS" to 0.6f, spec.name to 0f)
                val hi = render(voice, "TUNE" to 0.35f, "DRIVE" to 0.6f, "GLASS" to 0.6f, spec.name to if (spec.name == "HOLD") 0.9f else 1f)
                assertTrue(!lo.samples.contentEquals(hi.samples), "${voice.name} ${spec.name}: the two ends sound the same")
            }
            assertContentEquals(base.samples, Ballast.render(voice, Ballast.defaults(voice) + mapOf("TUNE" to 0.35f, "DRIVE" to 0.6f, "GLASS" to 0.6f, "SPARKLE" to 1f)).samples, "$voice: an unknown key changed the sound")
        }
    }

    @Test
    fun `every corner of the knobs stays finite and bounded`() {
        val cases = BallastVoice.entries.flatMap { v -> (0 until 32).map { mask -> v to mask } }
        val results = cases.parallelStream().map { (voice, mask) ->
            val bit = { k: Int -> if (mask shr k and 1 == 1) 1f else 0f }
            val tune = if (mask % 2 == 0) 0f else 1f
            val m = Ballast.settled(mapOf("TUNE" to tune, "DRIVE" to bit(0), "SYMPATHY" to bit(1), "SPAN" to bit(2), "GLASS" to bit(3), "FRAME" to bit(4), "HOLD" to 0f), voice)
            val r = run(voice, m, probe = Ballast.Probe())
            Triple("$voice $m", r.raw.all { it.isFinite() }, Pair(r.raw.maxOf { abs(it) }, r.stats.travelStops))
        }.toList()
        val worst = results.maxBy { it.third.first }
        println("BALLAST corners: ${results.size} renders, worst raw peak ${f(worst.third.first.toDouble(), 3)} at ${worst.first}; safety stops ${results.sumOf { it.third.second }}")
        for ((label, finite, peak) in results) {
            assertTrue(finite, "$label: not finite")
            assertTrue(peak.first < 2f, "$label: raw peak ${peak.first}")
        }
    }

    @Test
    fun `the note TUNE names is the note the bass plays`() {
        for (voice in listOf(BallastVoice.ROOT, BallastVoice.WIRE, BallastVoice.DEEP)) for (tune in listOf(0f, 1f / 3f, 2f / 3f)) {
            val m = macrosAt(voice, "TUNE" to tune)
            val r = run(voice, m)
            val hz = Ballast.frequencyFor(voice, tune).toDouble()
            val from = (0.4 * raw).toInt()
            val n = (1.5 * raw).toInt()
            var bestHz = 0.0
            var best = 0.0
            var k = -3.0
            while (k <= 3.0) {
                val h = hz * (1 + k / 100.0)
                val a = amp(r.parts!!.direct, from, n, h, raw)
                if (a > best) { best = a; bestHz = h }
                k += 0.1
            }
            val cents = 1200.0 * kotlin.math.ln(bestHz / hz) / kotlin.math.ln(2.0)
            println("BALLAST $voice ${Ballast.midiFor(voice, tune)}: bass peak ${f(bestHz)} Hz, ${f(cents)} cents from ${f(hz)}")
            assertTrue(abs(cents) < 25.0, "$voice $tune: ${f(cents)} cents off")
        }
    }

    // ---- the lower strings (spec section 6) ------------------------------------------------------------------------

    /**
     * No sub oscillator, and no steady subharmonic from a steady tone: a held note's wires at 1/4 and 1/2 of the note
     * ring from the onset and fade, and are not driven afterwards. The onset's 1/4 is at least 40 dB over the steady
     * state's; the bass itself carries nothing at 1/4 or 1/2 of its pitch.
     */
    @Test
    fun `the lower strings are excited by the onset and fade, and nothing drives them steadily`() {
        for (voice in listOf(BallastVoice.WIRE, BallastVoice.DEEP)) {
            val m = macrosAt(voice, "TUNE" to 2f / 3f, "SYMPATHY" to 0.9f, "SPAN" to 1f, "GLASS" to 0f)
            val r = steady(voice, m, 6.0)
            val hz = Ballast.planLoop(voice, Ballast.frequencyFor(voice, 2f / 3f).toDouble()).hz
            val strings = r.parts!!.strings
            val early = (0.5 * raw).toInt()
            val lateFrom = (4.5 * raw).toInt()
            val lateN = (1.5 * raw).toInt()
            for (div in listOf(4, 2)) {
                val onset = amp(strings, 0, early, hz / div, raw)
                val late = amp(strings, lateFrom, lateN, hz / div, raw)
                val steadyNote = amp(strings, lateFrom, lateN, hz, raw)
                println("BALLAST $voice 1/$div string: onset ${f(db(onset), 1)} dB, steady ${f(db(late), 1)} dB, the note itself ${f(db(steadyNote), 1)} dB")
                assertTrue(db(onset) - db(late) > 40.0, "$voice 1/$div: onset ${db(onset)} dB against steady ${db(late)} dB")
                assertTrue(db(steadyNote) - db(late) > 40.0, "$voice 1/$div: the steady note is not over its subharmonic")
            }
            val direct = r.parts.direct
            val note = amp(direct, lateFrom, lateN, hz, raw)
            for (div in listOf(4, 2)) {
                val sub = amp(direct, lateFrom, lateN, hz / div, raw)
                assertTrue(db(note) - db(sub) > 40.0, "$voice: the bass has ${f(db(note) - db(sub), 1)} dB between its note and its 1/$div")
            }
        }
    }

    @Test
    fun `with the bass never on, the structure is silent`() {
        val m = macrosAt(BallastVoice.SWARM, "TUNE" to 1f / 3f)
        val r = run(BallastVoice.SWARM, m, probe = Ballast.Probe(record = true, sourceOffAt = 0.0))
        assertEquals(0, r.stats.knocks)
        assertEquals(0.0, r.stats.knockLoss)
        assertTrue(r.raw.all { abs(it) < 1e-6f }, "something sounded with no source: ${r.raw.maxOf { abs(it) }}")
    }

    // ---- passivity -------------------------------------------------------------------------------------------------

    /** Source-off structure dissipates: once the bass stops dead, the energy in the frame, wires, tiles and glass never grows and ends far below where it was. */
    @Test
    fun `once the bass stops, the structure's energy only falls`() {
        for (voice in BallastVoice.entries) {
            val m = macrosAt(voice, "TUNE" to 1f / 3f, "DRIVE" to 0.8f, "GLASS" to 0.7f, "SYMPATHY" to 0.4f, "FRAME" to 0.6f, "HOLD" to 0.9f)
            val off = 1.5
            val r = run(voice, m, probe = Ballast.Probe(record = true, sourceOffAt = off))
            val t = r.stats.energyTimes
            val e = r.stats.energies
            val after = t.indices.filter { t[it] >= off + 0.1 }
            val e0 = e[after.first()]
            var worstRise = 0.0
            for (k in 1 until after.size) worstRise = max(worstRise, e[after[k]] / e[after[k - 1]])
            val end = e[after.last()] / e0
            println("BALLAST $voice off at $off s: energy ${f(10 * log10(end), 1)} dB by ${f(t[after.last()], 1)} s, worst step ${f(worstRise, 3)}x")
            assertTrue(worstRise < 1.02, "$voice: energy rose by $worstRise in a step after the source stopped")
            assertTrue(end < 0.1, "$voice: only down to $end of its energy")
            assertTrue(e0 > 0.0)
        }
    }

    /** The glass cannot add mechanical energy: it is a sink fed only by the loss, and the structure is bit-identical with it switched off at the pickup. */
    @Test
    fun `glass ringing is paid for by the collisions' loss and never feeds the structure`() {
        for (voice in BallastVoice.entries) {
            val m = macrosAt(voice, "TUNE" to 1f / 3f, "DRIVE" to 0.8f, "GLASS" to 0.8f)
            val with = run(voice, m)
            val without = run(voice, m, probe = Ballast.Probe(record = true, glass = false))
            assertTrue(with.stats.knocks > 0, "$voice: no knocks to test with")
            assertContentEquals(with.parts!!.frame, without.parts!!.frame, "$voice: the frame moved when the glass pickup was off")
            assertContentEquals(with.parts.strings, without.parts!!.strings, "$voice: the wires moved when the glass pickup was off")
            assertEquals(with.stats.knocks, without.stats.knocks)
            assertEquals(with.stats.knockLoss, without.stats.knockLoss)
            assertTrue(without.parts.glass.all { it == 0f })
            val ratio = with.stats.ringEnergy / with.stats.knockLoss
            println("BALLAST $voice: ${with.stats.knocks} knocks, loss ${"%.3e".format(with.stats.knockLoss)}, ring ${"%.3e".format(with.stats.ringEnergy)} (${f(ratio, 4)} of it)")
            assertEquals(Ballast.RING_SHARE, ratio, 1e-9, "$voice: the ring is not RING_SHARE of the loss")
            assertTrue(with.stats.ringEnergy < with.stats.knockLoss)
        }
    }

    // ---- the contacts ----------------------------------------------------------------------------------------------

    @Test
    fun `knocks come from the source-driven frame motion`() {
        val m = macrosAt(BallastVoice.GLINT, "TUNE" to 1f / 3f, "DRIVE" to 0.7f, "GLASS" to 0.7f)
        val full = run(BallastVoice.GLINT, m)
        val noMotion = run(BallastVoice.GLINT, m, probe = Ballast.Probe(record = true, motionDrive = false))
        val soft = run(BallastVoice.GLINT, m, velocity = 0.2)
        println("BALLAST knocks: full ${full.stats.knocks}, no slow motion drive ${noMotion.stats.knocks}, velocity .2 ${soft.stats.knocks}")
        assertTrue(full.stats.knocks > 5)
        assertTrue(noMotion.stats.knocks < full.stats.knocks / 2, "the motion drive is not what the tiles ride on")
        assertTrue(soft.stats.knocks < full.stats.knocks, "a softer touch should knock less")
        assertTrue(full.stats.knockTimes.first() > 0.002, "a knock before the bass had started")
        assertTrue(full.stats.peakRock > 10 * noMotion.stats.peakRock, "the rocking mode is not what the bass moves")
    }

    @Test
    fun `GLASS closes the gaps, so no knocks boxed in and a rattle nearly touching`() {
        val knocks = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { g ->
            val m = macrosAt(BallastVoice.GLINT, "TUNE" to 1f / 3f, "DRIVE" to 0.6f, "GLASS" to g)
            run(BallastVoice.GLINT, m, probe = Ballast.Probe()).stats.knocks
        }
        println("BALLAST knocks by GLASS 0 .25 .5 .75 1: $knocks")
        assertEquals(0, knocks.first())
        assertTrue(knocks.last() > 50)
        assertTrue(knocks.zipWithNext().all { (a, b) -> b >= a }, "knocks fell as GLASS rose: $knocks")
    }

    @Test
    fun `the macros map the way the spec says`() {
        val v = BallastVoice.WIRE
        assertTrue(Ballast.stringT60(v, 1f) > Ballast.stringT60(v, 0f))
        assertTrue(Ballast.stringKappaTotal(1f, 0.5f) > Ballast.stringKappaTotal(0f, 0.5f))
        assertTrue(Ballast.rockHz(v, 1f) < Ballast.rockHz(v, 0f), "a softer FRAME rocks slower")
        assertTrue(Ballast.frameT60(1f) > Ballast.frameT60(0f))
        assertTrue(Ballast.gapFor(1f, v) < Ballast.gapFor(0f, v))
        assertTrue(Ballast.contactHz(1f) > Ballast.contactHz(0f))
        for (j in 0 until Ballast.STRINGS) {
            if (j == Ballast.ROOT_STRING) {
                assertEquals(1.0, Ballast.octaveWeights(v, 0f)[j])
                assertEquals(1.0, Ballast.octaveWeights(v, 1f)[j])
            } else {
                assertTrue(Ballast.octaveWeights(v, 1f)[j] > Ballast.octaveWeights(v, 0f)[j], "SPAN does not reach string $j")
            }
        }
        val released = (0 until Ballast.TILES).map { Ballast.freeness(v, 0.5f, it) }
        assertTrue(released.distinct().size > 1, "GLASS releases every tile at once")
        assertTrue(Ballast.freeness(v, 1f, 0) >= Ballast.freeness(v, 0f, 0))
    }

    // ---- the lifecycle ---------------------------------------------------------------------------------------------

    @Test
    fun `HOLD sets the gate and the render ends when the slowest decay is down`() {
        for (voice in BallastVoice.entries) {
            val short = render(voice, "HOLD" to 0f).frameCount
            val long = render(voice, "HOLD" to 0.95f).frameCount
            assertTrue(long > short || short.toDouble() / rate >= Ballast.MAX_SECONDS - 0.01, "$voice: a longer gate should not be shorter")
            val snip = render(voice, "HOLD" to 0.5f)
            val tail = snip.samples.copyOfRange(snip.frameCount - rate / 10, snip.frameCount)
            val peak = snip.peak()
            assertTrue(tail.maxOf { abs(it) } < 0.05f * peak, "$voice: still ringing ${tail.maxOf { abs(it) } / peak} of its peak when the render ends")
        }
    }

    @Test
    fun `the filed class follows the classifier's length line`() {
        for (voice in BallastVoice.entries) {
            for (macros in listOf(emptyMap(), mapOf("HOLD" to 0.9f), mapOf("SYMPATHY" to 0f, "FRAME" to 0f))) {
                val m = Ballast.defaults(voice) + macros
                val snip = Ballast.render(voice, m)
                val expected = if (snip.frameCount.toFloat() / rate > Ballast.LOOP_THRESHOLD_SECONDS) DrumClass.LOOP else DrumClass.PERC
                assertEquals(expected, Ballast.drumClassFor(voice, m), "$voice $macros")
                println("BALLAST $voice $macros: ${f(snip.frameCount.toDouble() / rate)} s filed ${Ballast.drumClassFor(voice, m)}")
            }
            assertEquals(DrumClass.LOOP, Ballast.drumClassFor(voice, mapOf("HOLD" to 1f)))
        }
    }

    @Test
    fun `the classifier never hears a drum in a standard render`() {
        val cases = BallastVoice.entries.flatMap { v -> listOf(0f, 1f / 3f, 2f / 3f, 1f).map { v to it } }
        val heard = cases.parallelStream().map { (v, t) -> Triple(v, t, Classifier.classify(render(v, "TUNE" to t)).drumClass) }.toList()
        for ((voice, tune, cls) in heard) assertTrue(cls !in choking, "$voice TUNE $tune classified as $cls")
    }

    // ---- the held loop ---------------------------------------------------------------------------------------------

    /** The gate: every final exported loop closes on itself to the Organ's bar (Keys.seamError), across voices, notes and the corners of DRIVE and GLASS. */
    @Test
    fun `held loops close on themselves across voices, notes and the corners of DRIVE and GLASS`() {
        val combos = listOf(0.45f to 0.25f, 0.8f to 0.8f, 1f to 1f, 0.3f to 0.5f)
        val cases = BallastVoice.entries.flatMap { v -> listOf(0f, 1f / 3f, 1f).flatMap { t -> combos.map { Triple(v, t, it) } } }
        val loops = cases.parallelStream().map { (v, t, dg) ->
            val macros = mapOf("TUNE" to t, "DRIVE" to dg.first, "GLASS" to dg.second, "HOLD" to 1f)
            Pair("$v midi ${Ballast.midiFor(v, t)} DRIVE ${dg.first} GLASS ${dg.second}", Ballast.renderLoopMeasured(v, macros))
        }.toList()
        val worst = loops.maxBy { it.second.seam }
        val calmed = loops.count { it.second.calm > 0 }
        println("BALLAST loops: ${loops.size} corners, worst seam ${"%.2e".format(worst.second.seam)} at ${worst.first}; ${calmed} needed calmer contacts")
        for ((label, l) in loops) {
            assertTrue(l.seam < Keys.MAX_SEAM_ERROR, "$label: the loop does not close (seam ${l.seam}, calm ${l.calm})")
            assertTrue(l.loop.all { it.isFinite() })
        }
        assertTrue(calmed <= loops.size / 6, "$calmed of ${loops.size} loops needed calmer contacts: the glass is not settling")
    }

    @Test
    fun `a held loop is whole periods of the note in whole frames and wraps without a step`() {
        for (voice in listOf(BallastVoice.ROOT, BallastVoice.SWARM)) for (tune in listOf(0f, 0.5f)) {
            val macros = Ballast.defaults(voice) + mapOf("TUNE" to tune, "HOLD" to 1f)
            val snip = Ballast.render(voice, macros)
            val hz = Ballast.frequencyFor(voice, tune).toDouble()
            val plan = Ballast.planLoop(voice, hz)
            assertEquals(plan.frames, snip.frameCount)
            assertEquals(0, plan.periods % 4, "the 1/4 string's harmonics repeat in the loop")
            assertTrue(abs(plan.hz - hz) / hz < 5e-4, "the loop moved the pitch by more than a cent: ${plan.hz} for $hz")
            assertEquals(DrumClass.LOOP, Ballast.drumClassFor(voice, macros))
            val x = snip.samples
            var biggest = 0f
            for (i in 1 until x.size) biggest = max(biggest, abs(x[i] - x[i - 1]))
            val wrap = abs(x[0] - x[x.size - 1])
            assertTrue(wrap <= biggest, "$voice: the wrap steps $wrap, more than the loop's own largest step $biggest")
            assertTrue(Loudness.of(snip) >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f)
        }
    }

    // ---- aliasing, speed, velocity -----------------------------------------------------------------------------------

    private fun spectrum(x: FloatArray, from: Int, n: Int): FloatArray {
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) re[i] = x[from + i] * (0.5f - 0.5f * cos(2 * PI * i / (n - 1)).toFloat())
        Fft.forward(re, im)
        return FloatArray(n / 2) { hypot(re[it].toDouble(), im[it].toDouble()).toFloat() }
    }

    /** Four-times oversampling, the band limit and the decimator keep the top of the hardest, highest, most rattling render clean. */
    @Test
    fun `the top of the spectrum stays clean at the highest note and hardest drive`() {
        val cases = listOf(BallastVoice.SWARM, BallastVoice.GLINT, BallastVoice.WIRE)
        for (voice in cases) {
            val snip = render(voice, "TUNE" to 1f, "DRIVE" to 1f, "GLASS" to 1f, "SPAN" to 1f, "HOLD" to 0.3f)
            val n = 1 shl 16
            val mag = spectrum(snip.samples, rate / 2, n)
            val binHz = rate.toDouble() / n
            fun band(lo: Double, hi: Double) = (lo / binHz).toInt().let { a -> (a until (hi / binHz).toInt()).sumOf { (mag[it] * mag[it]).toDouble() } }
            val total = band(20.0, rate / 2.0 - 1)
            val top = band(19_000.0, rate / 2.0 - 1)
            println("BALLAST $voice top note: 19 kHz and up is ${f(10 * log10(top / total), 1)} dB under the total")
            assertTrue(10 * log10(top / total) < -45.0, "$voice: ${10 * log10(top / total)} dB above 19 kHz")
        }
    }

    @Test
    fun `an eight second render takes well under ten seconds`() {
        val t0 = System.nanoTime()
        val snip = render(BallastVoice.WIRE, "SYMPATHY" to 1f, "FRAME" to 1f, "HOLD" to 0.95f, "GLASS" to 0.8f)
        val seconds = (System.nanoTime() - t0) / 1e9
        println("BALLAST: ${f(snip.frameCount.toDouble() / rate)} s of audio in ${f(seconds)} s")
        assertTrue(snip.frameCount.toDouble() / rate > 7.0)
        assertTrue(seconds < 10.0, "took $seconds s")
        val t1 = System.nanoTime()
        Ballast.render(BallastVoice.SWARM, Ballast.defaults(BallastVoice.SWARM) + mapOf("HOLD" to 1f, "TUNE" to 0f))
        val loop = (System.nanoTime() - t1) / 1e9
        println("BALLAST: a held loop in ${f(loop)} s")
        assertTrue(loop < 10.0, "a loop took $loop s")
    }

    @Test
    fun `velocity is a touch, so a softer note is darker and a pad plays the hard one`() {
        for (voice in BallastVoice.entries) {
            val hard = Ballast.render(voice, Ballast.defaults(voice), velocity = 1f)
            val soft = Ballast.render(voice, Ballast.defaults(voice), velocity = 0.2f)
            assertContentEquals(hard.samples, Ballast.render(voice, Ballast.defaults(voice)).samples, "$voice: the default is not a full touch")
            fun centroid(s: Snip): Double {
                val mag = spectrum(s.samples, 0, 1 shl 14)
                var num = 0.0
                var den = 0.0
                for (b in 1 until mag.size) { num += mag[b] * b; den += mag[b] }
                return num / den * rate / (1 shl 14)
            }
            println("BALLAST $voice onset centroid: soft ${f(centroid(soft), 0)} Hz, hard ${f(centroid(hard), 0)} Hz")
            assertTrue(centroid(soft) < centroid(hard), "$voice: a soft touch is not darker")
            val patch = BallastPatch("Touch", voice, Ballast.defaults(voice))
            assertContentEquals(soft.samples, Velocity.atVelocity(patch, 0.2f).samples, "$voice: Velocity.atVelocity is not the engine's touch")
            assertNotEquals(hard.samples.toList(), soft.samples.toList())
        }
    }

    /** The bass itself carries nothing under its note, and a held loop's wires carry nothing there either: the lower octaves are the onset's, and they have faded. */
    @Test
    fun `a held note has nothing sub-sonic under it`() {
        val macros = Ballast.defaults(BallastVoice.ROOT) + mapOf("TUNE" to 1f / 3f, "GLASS" to 0f, "HOLD" to 1f)
        val loop = Ballast.render(BallastVoice.ROOT, macros).samples
        val n = 1 shl 16
        val mag = spectrum(loop, 0, n)
        val binHz = rate.toDouble() / n
        val hz = Ballast.frequencyFor(BallastVoice.ROOT, 1f / 3f).toDouble()
        val below = (12.0 / binHz).toInt()..((hz * 0.8) / binHz).toInt()
        val note = ((hz * 0.9) / binHz).toInt()..((hz * 1.1) / binHz).toInt()
        val lowEnergy = below.sumOf { (mag[it] * mag[it]).toDouble() }
        val noteEnergy = note.sumOf { (mag[it] * mag[it]).toDouble() }
        println("BALLAST ROOT C2 held: ${f(10 * log10(lowEnergy / noteEnergy), 1)} dB of sub-note energy under the note")
        assertTrue(10 * log10(lowEnergy / noteEnergy) < -30.0, "energy below the note: ${10 * log10(lowEnergy / noteEnergy)} dB")
    }
}
