package com.snipsnap.synth

import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * SARANGI (R2), the third voice: ARCO's bow in a sarangi's register (D3 to D5), into ERHU's skin held at BODY's default, then the tarab, a bank of
 * 37 sympathetic strings tuned chromatically from D3 ([Arco.withTarab]), which WASH sets in BODY's place. The claims, each with a control:
 *
 *  - **The voice is what the owner decided.** D3 to D5, the knobs TUNE, BOW, GRIP, WASH, HOLD and BUZZ, no BODY (a BODY key is dropped and moves
 *    nothing), ERHU's box at BODY's default, and CELLO's and ERHU's knobs as they were.
 *  - **The tarab is SANTUR's bank on the chromatic row.** 37 strings, D3 to D6 every semitone, at WASH's t60, the same whatever TUNE says.
 *  - **WASH 0 is exactly no tarab**: the same buffer handed back, and a one-shot at WASH 0 the string through the box and the finish with no tarab
 *    call at all, to the bit.
 *  - **WASH rises**: the tarab's share of the note, how far WASH moves a one-shot, and the ring it leaves after the bow lifts all grow at every step.
 *  - **The tarab settles and the LOOP closes** at WASH 0, 0.5 and 1 at every TUNE step; and on a loop made exactly periodic, the bank at WASH's top
 *    t60 settles inside the LOOP's warm-up where a 5 s one would not (the research pass's Python stand-in, re-read in Kotlin on a real loop).
 *  - **WASH never moves a note's length**: the bank is cut where the string ends, as the box is.
 *
 * Nothing here has been heard.
 */
class ArcoSarangiTest {

    private val voice = ArcoVoice.SARANGI
    private val rawRate = Dsp.RATE * Dsp.OVERSAMPLE

    private fun e(v: Double) = "%.2e".format(Locale.ROOT, v)
    private fun f(v: Double, d: Int = 3) = "%.${d}f".format(Locale.ROOT, v)
    private fun db(v: Double) = 20 * log10(max(v, 1e-12))

    private fun noteName(midi: Int) = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12] + (midi / 12 - 1)

    private fun tuneOf(step: Int) = step / Arco.tuneSemitones(voice).toFloat()

    private fun rms(x: FloatArray, from: Int = 0, until: Int = x.size): Double {
        var acc = 0.0
        for (i in from until until) acc += x[i].toDouble() * x[i]
        return sqrt(acc / (until - from).coerceAtLeast(1))
    }

    /** RMS of the difference over RMS of [a] (the longer one's tail counts as difference): ArcoProductTest's measure of a macro moving a note. */
    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        var diff = 0.0
        var energy = 0.0
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0f }.toDouble()
            val y = b.getOrElse(i) { 0f }.toDouble()
            diff += (x - y) * (x - y)
            energy += x * x
        }
        return sqrt(diff / energy)
    }

    // ---- the voice ------------------------------------------------------------------------------------------

    @Test
    fun `SARANGI is D3 to D5 with WASH in BODY's place, its box is ERHU's at BODY's default, and the other voices' knobs did not move`() {
        assertEquals(50, Arco.rootMidi(voice), "SARANGI's root is D3")
        assertEquals(24, Arco.tuneSemitones(voice), "SARANGI's span is two octaves")
        assertEquals(50, Arco.midiFor(voice, 0f))
        assertEquals(62, Arco.midiFor(voice, 0.5f), "the knob's centre is D4")
        assertEquals(74, Arco.midiFor(voice, 1f), "the top is D5")
        assertEquals(146.83f, Arco.frequencyFor(voice, 0f), 0.01f)
        assertEquals(587.33f, Arco.frequencyFor(voice, 1f), 0.01f)
        assertEquals(listOf("TUNE", "BOW", "GRIP", "WASH", "HOLD", "BUZZ"), Arco.macrosFor(voice).map { it.name })
        for (v in listOf(ArcoVoice.CELLO, ArcoVoice.ERHU)) assertEquals(listOf("TUNE", "BOW", "GRIP", "BODY", "HOLD", "BUZZ"), Arco.macrosFor(v).map { it.name }, "$v's knobs moved")
        assertEquals(Arco.DEFAULT_WASH, Arco.defaults(voice).getValue("WASH"))
        assertEquals(0f, Arco.defaults(voice).getValue("BUZZ"))
        assertEquals("WASH", Arco.boxKnob(voice))
        assertEquals("BODY", Arco.boxKnob(ArcoVoice.ERHU))
        assertTrue(!Arco.settled(mapOf("BODY" to 1f), voice).containsKey("BODY"), "a BODY key reached SARANGI's macros")
        assertEquals(Arco.DEFAULT_BODY, Arco.bodyOf(voice, Arco.settled(mapOf("BODY" to 1f), voice)), "SARANGI's box is not at BODY's default")
        assertTrue(Arco.DEFAULT_BODY <= Arco.BODY_KNEE, "SARANGI's fixed BODY is above the knee, so its lift would play")
        assertSame(Arco.bodyFor(ArcoVoice.ERHU), Arco.bodyFor(voice), "SARANGI's box is not ERHU's skin")
        val plain = Arco.render(voice, Arco.defaults(voice)).samples
        assertContentEquals(plain, Arco.render(voice, Arco.defaults(voice) + ("BODY" to 1f)).samples, "a BODY key moved a SARANGI note")
        assertContentEquals(plain, Arco.render(voice, Arco.defaults(voice) + ("BODY" to 0f)).samples, "a BODY key moved a SARANGI note")
        assertTrue(!plain.contentEquals(Arco.render(voice, Arco.defaults(voice) + ("WASH" to 0.9f)).samples), "control: WASH 0.9 renders the default note")
        assertEquals(0f, Arco.washOf(ArcoVoice.CELLO, Arco.settled(mapOf("WASH" to 1f), ArcoVoice.CELLO)), "CELLO has a tarab")
    }

    @Test
    fun `the tarab is 37 strings, D3 to D6 every semitone, at WASH's t60, and TUNE never moves it`() {
        for (w in listOf(0.1f, 0.5f, 1f)) {
            val modes = Arco.tarabModes(w)
            assertEquals(37, modes.size, "the bank is not 37 strings")
            for ((k, m) in modes.withIndex()) {
                val want = Keys.midiHz(Arco.SARANGI_ROOT_MIDI + k).toDouble()
                val cents = 1200 * kotlin.math.ln(m.ratio / want) / kotlin.math.ln(2.0)
                assertTrue(abs(cents) < 0.01, "tarab string $k is ${f(cents, 4)} cents from ${noteName(Arco.SARANGI_ROOT_MIDI + k)}")
                assertEquals(Arco.washT60(w), m.t60, "tarab string $k rings ${m.t60} s, not WASH's ${Arco.washT60(w)}")
                assertEquals(1f, m.gain)
            }
        }
        assertEquals(Arco.WASH_T60_LOW_SECONDS, Arco.washT60(0f))
        assertEquals(Arco.WASH_T60_HIGH_SECONDS, Arco.washT60(1f))
        assertTrue(Arco.WASH_T60_HIGH_SECONDS <= 2.0f, "the tarab rings ${Arco.WASH_T60_HIGH_SECONDS} s at the top, over the owner's 2 s")
        assertEquals(0f, Arco.washAmount(0f))
        assertEquals(Arco.WASH_AMOUNT_HIGH, Arco.washAmount(1f))
        assertEquals(Keys.midiHz(Arco.SARANGI_ROOT_MIDI), Arco.TARAB_ROOT_HZ, "the bank is not rooted on SARANGI's root")
    }

    // ---- WASH 0 ---------------------------------------------------------------------------------------------

    @Test
    fun `WASH 0 is exactly no tarab, and anything above it does not touch its input`() {
        val buf = FloatArray(4410) { kotlin.math.sin(it * 0.05).toFloat() }
        val copy = buf.copyOf()
        assertSame(buf, Arco.withTarab(buf, 0f), "WASH 0 must hand back the same array")
        assertSame(buf, Arco.withTarab(buf, -0.3f), "a WASH under 0 is off")
        assertSame(buf, Arco.withTarab(buf, Float.NaN), "a NaN WASH is off")
        val rung = Arco.withTarab(buf, 0.5f)
        assertContentEquals(copy, buf, "the tarab changed its input")
        assertEquals(buf.size, rung.size, "the tarab moved the length")
        assertTrue(!rung.contentEquals(buf), "control: WASH 0.5 rang nothing")
        // a one-shot at WASH 0 is the string, the box and the finish with no tarab call, to the bit, at the root, the middle and the top
        for (step in listOf(0, 12, 24)) {
            val m = Arco.settled(mapOf("TUNE" to tuneOf(step), "WASH" to 0f), voice)
            val hz = Arco.frequencyFor(voice, m.getValue("TUNE"))
            val noTarab = Arco.finished(Arco.bow(voice, hz, m), voice, Arco.DEFAULT_BODY, rawRate)
            val engine = Arco.render(voice, m).samples
            assertContentEquals(noTarab, engine, "SARANGI ${noteName(Arco.midiFor(voice, m.getValue("TUNE")))} at WASH 0 is not the note with no tarab")
            val faint = Arco.render(voice, m + ("WASH" to 0.01f)).samples
            assertTrue(!faint.contentEquals(engine), "control: WASH 0.01 is WASH 0")
        }
    }

    // ---- WASH rises -----------------------------------------------------------------------------------------

    private val washSteps = listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)

    /**
     * Three measures of the tarab at each WASH, at D3, A3, D4, A4 and D5 (a held note: HOLD 0.6, 1.4 s of bow), each against WASH 0:
     *  - **share**: the bank's RMS over the note's on the conditioned note, before the level (`withTarab(c) - c` against `c`);
     *  - **move**: the house's "a macro changes the sound" measure on the finished render;
     *  - **tail**: the finished render's RMS from the bow's lift to the end against its RMS while the bow is on, in dB: the ring the tarab leaves
     *    as the stop takes the string away, which is what a sarangi's halo is in a one-shot.
     * The share and the move must rise at every step and be exactly 0 at WASH 0; the tail must rise at every step from WASH 0.1, and WASH 1 must leave
     * at least 5 dB more of it than WASH 0. R2 measured the share at exactly WASH's amount (0.1 to 1.0: [Strings.bodyRing] RMS-matches the bank), the
     * move 0.037 to 0.061 at WASH 0.1 and 0.255 to 0.454 at WASH 1, and the tail rising 7.0 (D5) to 12.4 dB (D4) over the knob.
     */
    @Test
    fun `the tarab rises with WASH, on the note and in its tail`() {
        val problems = ArrayList<String>()
        for (step in listOf(0, 7, 12, 19, 24)) {
            val base = Arco.settled(mapOf("TUNE" to tuneOf(step), "HOLD" to 0.6f), voice)
            val hz = Arco.frequencyFor(voice, base.getValue("TUNE"))
            val raw = Arco.bow(voice, hz, base)
            val conditioned = Arco.conditionedWithBody(raw.copyOf(), voice, Arco.DEFAULT_BODY, rawRate)
            val bowOn = (Arco.holdSeconds(base.getValue("HOLD")) * Dsp.RATE).toInt()
            val plain = Arco.render(voice, base + ("WASH" to 0f)).samples
            val rows = washSteps.map { w ->
                val rung = Arco.withTarab(conditioned, w)
                val bank = FloatArray(rung.size) { rung[it] - conditioned[it] }
                val share = rms(bank) / rms(conditioned)
                val shot = Arco.render(voice, base + ("WASH" to w)).samples
                val move = relativeDifference(plain, shot)
                val lift = bowOn + (Arco.RELEASE_RAMP_SECONDS * Dsp.RATE).toInt()
                val tail = db(rms(shot, lift, shot.size) / rms(shot, 0, bowOn))
                Triple(share, move, tail)
            }
            println("ARCO SARANGI WASH ${noteName(Arco.midiFor(voice, base.getValue("TUNE")))}: " + washSteps.zip(rows).joinToString("; ") { (w, r) ->
                "WASH $w share ${f(r.first)} (${f(db(r.first), 1)} dB) move ${f(r.second)} tail ${f(r.third, 1)} dB"
            })
            if (rows[0].first != 0.0 || rows[0].second != 0.0) problems += "step $step: WASH 0 is not exactly no tarab (share ${rows[0].first}, move ${rows[0].second})"
            for (i in 1 until rows.size) {
                if (!(rows[i].first > rows[i - 1].first)) problems += "step $step: the share at WASH ${washSteps[i]} is not over WASH ${washSteps[i - 1]}'s"
                if (!(rows[i].second > rows[i - 1].second)) problems += "step $step: the move at WASH ${washSteps[i]} is not over WASH ${washSteps[i - 1]}'s"
                // The tail from WASH 0.1 up: the first tenth adds under a tenth of a dB to the ring after the lift (A3 read 0.1 dB less), inside this measure's own noise.
                if (i >= 2 && !(rows[i].third > rows[i - 1].third)) problems += "step $step: the tail at WASH ${washSteps[i]} is not over WASH ${washSteps[i - 1]}'s"
            }
            if (rows.last().third < rows.first().third + 5.0) problems += "step $step: WASH 1 leaves only ${f(rows.last().third - rows.first().third, 1)} dB more ring after the lift than WASH 0"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ---- the LOOP ---------------------------------------------------------------------------------------------

    /** Every TUNE step at WASH 0, 0.5 and 1, the other knobs at their defaults: 75 loops, each under the Organ's bar, finite, at least 2 s. */
    @Test
    fun `every SARANGI loop closes at WASH 0, 0 point 5 and 1, at every TUNE step`() {
        val threads = ManagementFactory.getThreadMXBean()
        val cpu = threads.isCurrentThreadCpuTimeSupported && threads.isThreadCpuTimeEnabled
        class Row(val step: Int, val wash: Float, val seam: Double, val finite: Boolean, val frames: Int, val ms: Long)
        val cases = (0..Arco.tuneSemitones(voice)).flatMap { s -> listOf(0f, 0.5f, 1f).map { s to it } }
        val rows = cases.parallelStream().map { (s, w) ->
            val t0 = if (cpu) threads.currentThreadCpuTime else System.nanoTime()
            val r = Arco.renderLoopMeasured(voice, Arco.defaults(voice) + mapOf("TUNE" to tuneOf(s), "WASH" to w, "HOLD" to 1f))
            val ms = ((if (cpu) threads.currentThreadCpuTime else System.nanoTime()) - t0) / 1_000_000L
            Row(s, w, r.seam, r.loop.all { it.isFinite() && it in -1f..1f }, r.loop.size, ms)
        }.toList()
        for (w in listOf(0f, 0.5f, 1f)) {
            val mine = rows.filter { it.wash == w }
            val worst = mine.maxBy { it.seam }
            println("ARCO SARANGI loops at WASH $w: ${mine.size} loops, worst seam ${e(worst.seam)} at ${noteName(Arco.SARANGI_ROOT_MIDI + worst.step)} (bar ${e(Keys.MAX_SEAM_ERROR)}); " +
                "render ${mine.minOf { it.ms }} to ${mine.maxOf { it.ms }} ms a loop (this thread's CPU time)")
        }
        val bad = rows.filter { !(it.seam < Keys.MAX_SEAM_ERROR) || !it.finite || it.frames < 2 * Dsp.RATE }
        assertTrue(bad.isEmpty(), bad.joinToString("\n") { "SARANGI ${noteName(Arco.SARANGI_ROOT_MIDI + it.step)} WASH ${it.wash}: seam ${it.seam}, finite ${it.finite}, ${it.frames} frames" })
    }

    /**
     * How the tarab settles, apart from the bow: SARANGI's own loop at WASH 0 (which closes) tiled into a stretch as long as the LOOP's (the warm-up,
     * the loop and the pad), so the stretch is exactly periodic, then rung through the tarab at WASH's top amount and a t60 of WASH's top, 2, 3 and 5 s,
     * and its seam read where the engine reads it (after the warm-up, against one loop later). What is left is the bank's own unsettled ring. The engine's
     * t60 must leave it far under the bar; 5 s (the research pass's failing case) is the control that the reading can see a bank that has not settled.
     */
    @Test
    fun `on an exactly periodic loop the tarab at WASH's top settles inside the warm-up, and a 5 s tarab does not`() {
        val t60s = listOf(Arco.WASH_T60_HIGH_SECONDS, 2f, 3f, 5f)
        val worst = DoubleArray(t60s.size)
        for (step in listOf(0, 12, 24)) {
            val m = Arco.defaults(voice) + mapOf("TUNE" to tuneOf(step), "WASH" to 0f, "HOLD" to 1f)
            val loop = Arco.renderLoopMeasured(voice, m).loop
            val hz = Arco.frequencyFor(voice, tuneOf(step))
            val warm = Arco.warmFramesFor(hz)
            val total = warm + loop.size + 2048
            val tiled = FloatArray(total) { loop[it % loop.size] }
            val seams = t60s.map { t60 ->
                val modes = Silk.washModesFor(Arco.TARAB_ROOT_HZ, SilkScales.CHROMATIC, 1f, t60)
                val rung = Strings.bodyRing(tiled, modes, Arco.washAmount(1f), Dsp.RATE, total.toFloat() / Dsp.RATE + 1f).copyOf(total)
                Keys.seamError(rung.copyOfRange(warm, warm + loop.size + 256), 256)
            }
            val bare = Keys.seamError(tiled.copyOfRange(warm, warm + loop.size + 256), 256)
            for (i in seams.indices) worst[i] = max(worst[i], seams[i])
            println("ARCO SARANGI tarab settling ${noteName(Arco.midiFor(voice, tuneOf(step)))} (warm-up ${f(warm.toDouble() / Dsp.RATE, 2)} s, the tiled loop alone ${e(bare)}): " +
                t60s.zip(seams).joinToString(", ") { (t, s) -> "t60 ${f(t.toDouble(), 1)} s ${e(s)}" })
        }
        assertTrue(worst[0] < 1e-5, "the tarab at WASH's top t60 leaves ${e(worst[0])} at the seam, not far under the bar")
        assertTrue(worst[3] > 100 * worst[0], "control: a 5 s tarab leaves ${e(worst[3])}, no more than the engine's ${e(worst[0])}, so the reading cannot see a bank that has not settled")
    }

    // ---- the length ---------------------------------------------------------------------------------------------

    /**
     * WASH never moves a note's length (the bank is cut where the string ends, as the box is), and the cut does not click: the bank alone fades out
     * over the note's last [Arco.TARAB_END_FADE_SECONDS]. Read on the 5 ms just before the house's 4 ms [Dsp.fadeTail], against the note's RMS, at D3,
     * D4 and D5, a stab (HOLD 0) and the default HOLD, WASH 0.5 and 1. R2 measured (a probe, then this test): with the end fade -28.6 dB (D3, the stab,
     * WASH 1) to -57.3 dB, and the same notes built without it (the control: the bank cut by the 4 ms fade alone) -5.5 dB (D4, the stab, WASH 1) to
     * -32.2 dB, so without the end fade a stab's bank is still ringing at full strength when the 4 ms fade takes it away. The bars: under -25 dB with
     * the fade, and at least 15 dB under the control in every cell.
     */
    @Test
    fun `WASH never moves a note's length, and the tarab fades out where the string ends instead of being chopped`() {
        val problems = ArrayList<String>()
        for (step in listOf(0, 12, 24)) for (hold in listOf(0f, 0.4f, 0.95f)) {
            val m = Arco.settled(mapOf("TUNE" to tuneOf(step), "HOLD" to hold), voice)
            val frames = Arco.renderFrames(voice, m)
            val cls = Arco.drumClassFor(voice, m)
            val lengths = listOf(0f, 0.5f, 1f).map { w -> Arco.render(voice, m + ("WASH" to w)).frameCount }
            for ((i, n) in lengths.withIndex()) assertEquals(frames, n, "SARANGI step $step HOLD $hold WASH ${listOf(0f, 0.5f, 1f)[i]}: the note is $n frames, not the string's $frames")
            for (w in listOf(0f, 0.5f, 1f)) assertEquals(cls, Arco.drumClassFor(voice, m + ("WASH" to w)))
            if (hold > 0.5f) continue
            for (wash in listOf(0.5f, 1f)) {
                val mw = m + ("WASH" to wash)
                val shot = Arco.render(voice, mw).samples
                val hz = Arco.frequencyFor(voice, mw.getValue("TUNE"))
                val chopped = Arco.withTarab(Arco.conditionedWithBody(Arco.bow(voice, hz, mw), voice, Arco.DEFAULT_BODY, rawRate), wash)
                Dsp.levelTo(chopped, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
                Dsp.fadeTail(chopped)
                val a = shot.size - (0.009 * Dsp.RATE).toInt()
                val b = shot.size - (0.004 * Dsp.RATE).toInt()
                val faded = db(rms(shot, a, b) / rms(shot))
                val control = db(rms(chopped, a, b) / rms(chopped))
                val label = "SARANGI ${noteName(Arco.midiFor(voice, mw.getValue("TUNE")))} HOLD $hold WASH $wash"
                println("ARCO SARANGI cut $label: $frames frames at every WASH; the last 5 ms before the 4 ms fade sit ${f(faded, 1)} dB under the note's RMS, ${f(control, 1)} dB without the tarab's end fade")
                if (faded > -25.0) problems += "$label: the last 5 ms before the fade sit ${f(faded, 1)} dB under the note, over -25 dB"
                if (faded > control - 15.0) problems += "$label: the end fade takes only ${f(control - faded, 1)} dB off the cut"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ---- SCRAMBLE ---------------------------------------------------------------------------------------------------

    /**
     * SCRAMBLE on SARANGI rolls the five knobs it rolls on the others, WASH in BODY's place, around SARANGI's own presets: every roll names exactly
     * SARANGI's six knobs, stays in 0..1, never reaches the LOOP step, and hands BUZZ back as the seed has it (0 around a preset or at temperature 1,
     * the near patch's own otherwise), as [Arco.scramble] does for every voice. WASH is rolled: 400 rolls land on more than 100 values of it.
     */
    @Test
    fun `SCRAMBLE rolls WASH with the other knobs, never rolls BUZZ and never lands on the LOOP step`() {
        val near = ArcoPatch("Near", voice, Arco.defaults(voice) + mapOf("WASH" to 0.2f, "BUZZ" to 0.7f))
        val washes = HashSet<Float>()
        for (temperature in listOf(0.35f, 1f)) for (n in listOf(null, near)) for (seed in 0 until 100) {
            val r = Arco.scramble(voice, kotlin.random.Random(seed), temperature, n)
            assertEquals(Arco.macrosFor(voice).map { it.name }.toSet(), r.keys, "seed $seed: the roll's knobs")
            assertEquals(if (n == null) 0f else 0.7f, r.getValue("BUZZ"), "seed $seed temperature $temperature: BUZZ was rolled")
            assertTrue(r.getValue("HOLD") <= Arco.SCRAMBLE_HOLD_CEILING && !Arco.isLoop(r.getValue("HOLD")), "seed $seed: the roll reached the LOOP step")
            assertTrue(r.values.all { it in 0f..1f }, "seed $seed: a knob out of range: $r")
            washes += r.getValue("WASH")
        }
        println("ARCO SARANGI scramble: 400 rolls, ${washes.size} values of WASH from ${f(washes.min().toDouble(), 2)} to ${f(washes.max().toDouble(), 2)}")
        assertTrue(washes.size > 100, "WASH is hardly rolled: ${washes.size} values in 400 rolls")
    }

    // ---- beside ERHU -----------------------------------------------------------------------------------------------

    /** From D4 up, SARANGI's GRIP window is ERHU's at the same note (the corner floor is ERHU's line, the pressure floor ERHU's 0.94): one law across the two skins. */
    @Test
    fun `from D4 up SARANGI's grip window is ERHU's at the same note`() {
        for (s in 12..24) {
            val sarangi = Arco.gripFor(voice, s)
            val erhu = Arco.gripFor(ArcoVoice.ERHU, s - 12)
            assertEquals(erhu.pressureLow, sarangi.pressureLow, "${noteName(50 + s)}: the pressure floor differs from ERHU's")
            assertEquals(erhu.pressureHigh, sarangi.pressureHigh)
            assertEquals(erhu.cornerLow, sarangi.cornerLow, 0.05f, "${noteName(50 + s)}: the dark corner differs from ERHU's")
            assertEquals(erhu.cornerHigh, sarangi.cornerHigh, 0.05f)
        }
        assertEquals(1005.3f, Arco.gripFor(voice, 0).cornerLow, 0.5f, "SARANGI's dark corner at D3")
    }
}
