package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the two lift test classes share ([ArcoBodyLiftTest], [ArcoBodyLiftPeakTest]): the ten grid notes, one render of a note at a BODY (cached: the arrays are never changed), the engine's own measured finish
 * ([Arco.finishedMeasured]: the samples and what the lift did), the plain's reading (BODY 0.5, [ArcoWarmthMeasure.Base]) and the same note's numbers against it, and the lift with the cap switched off (the control the cap tests
 * hold the engine to). Everything here goes through the engine's functions, never a copy of them: [uncapped] is the engine's own conditioned note, gain and [Arco.lifted] shape, minus only the cap.
 */
internal object ArcoLiftRig {

    /** The raw rate [Arco.bow] runs at. */
    val RAW_RATE = Dsp.RATE * Dsp.OVERSAMPLE

    /** The ten grid notes of [ArcoBodyMeasure]: CELLO C2 F#2 C3 F#3 C4 (steps 0 6 12 18 24) and ERHU D4 G4 C5 F5 A5 (steps 0 5 10 15 19). */
    val NOTES: List<Pair<ArcoVoice, Int>> = ArcoBodyMeasure.GRID.flatMap { (voice, steps) -> steps.map { voice to it } }

    fun name(voice: ArcoVoice, step: Int): String = ArcoBodyMeasure.noteName(voice, step)

    /** The note at [step] with every knob at its default but BODY. */
    fun macros(voice: ArcoVoice, step: Int, body: Float): Map<String, Float> =
        Arco.defaults(voice) + mapOf("TUNE" to ArcoBodyMeasure.tuneOf(voice, step), "BODY" to body)

    /** The raw bowed string of [macros]: what [Arco.render] bows (settled knobs, the note's own pitch, the engine's rate), a fresh array every call. */
    fun rawOf(voice: ArcoVoice, macros: Map<String, Float>): FloatArray {
        val m = Arco.settled(macros, voice)
        return Arco.bow(voice, Arco.frequencyFor(voice, m.getValue("TUNE")), m, RAW_RATE)
    }

    private val renders = ConcurrentHashMap<String, FloatArray>()
    private val measures = ConcurrentHashMap<String, Arco.Finished>()
    private val bases = ConcurrentHashMap<String, ArcoWarmthMeasure.Base>()

    /** [Arco.render] of the note at [body], cached. Never changed by a caller. */
    fun render(voice: ArcoVoice, step: Int, body: Float): FloatArray =
        renders.computeIfAbsent("$voice/$step/$body") { ArcoBodyMeasure.viaArco(voice, step, body) }

    /** The engine's measured finish of the note's own raw string at [body] (samples and [Arco.Lift]), cached. */
    fun measured(voice: ArcoVoice, step: Int, body: Float): Arco.Finished =
        measures.computeIfAbsent("$voice/$step/$body") { Arco.finishedMeasured(rawOf(voice, macros(voice, step, body)), voice, body, RAW_RATE) }

    /** THE PLAIN ONE of the note (BODY 0.5), measured once. */
    fun base(voice: ArcoVoice, step: Int): ArcoWarmthMeasure.Base =
        bases.computeIfAbsent("$voice/$step") { ArcoWarmthMeasure.Base(render(voice, step, Arco.DEFAULT_BODY), ArcoBodyMeasure.f0Of(voice, step)) }

    /** The ruler's numbers for the note's render at [body] against its plain. */
    fun numbers(voice: ArcoVoice, step: Int, body: Float): ArcoWarmthMeasure.Numbers =
        ArcoWarmthMeasure.compare(render(voice, step, body), base(voice, step))

    /**
     * The finish with the cap switched off: the engine's conditioned note ([Arco.conditionedWithBody]), the engine's own gain ([Arco.plainGain]), the lift [body] asks for in full ([Arco.liftDbFor] through
     * [Arco.lifted]) and the 4 ms fade. It is [Arco.finished] with nothing but the cap taken away, so where the cap is idle the two are the same samples (the cap tests hold it to that).
     */
    fun uncapped(raw: FloatArray, voice: ArcoVoice, body: Float): FloatArray {
        val conditioned = Arco.conditionedWithBody(raw.copyOf(), voice, body, RAW_RATE)
        val gain = Arco.plainGain(conditioned)
        val out = Arco.lifted(conditioned, voice, Arco.liftDbFor(body))
        for (i in out.indices) out[i] *= gain
        Dsp.fadeTail(out)
        return out
    }

    fun peakOf(x: FloatArray): Float {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        return peak
    }

    /** The whole clip's RMS in dB (a floor keeps a silent clip finite). */
    fun rmsDb(x: FloatArray): Double = 20.0 * log10(max(ArcoWarmthMeasure.rmsOf(x), 1e-30))

    /** [Loudness.of] of a finished mono clip, copied first so the caller's array is certainly untouched. */
    fun loudnessOf(x: FloatArray): Float = Loudness.of(Snip(x.copyOf(), channels = 1, sampleRate = Dsp.RATE))

    fun <T, R> List<T>.pmap(op: (T) -> R): List<R> = parallelStream().map { op(it) }.toList()
}

/**
 * ARCO's BODY above the middle of the knob is a lift on the plain note (R1g's engine step, R1f's design): [Arco.finished] is the one entry point, the plain's gain ([Arco.plainGain]) is [Dsp.levelTo]'s own arithmetic,
 * the top of the note is never turned down, and the knob only ever adds. These tests are the new ones of that step; [ArcoFrozenR1cTest] holds the sound at and under the knee. The cap and peak tests are in [ArcoBodyLiftPeakTest],
 * and the PREDICTIONS table the design asked to see printed before any page is built is on `ARCO lift` lines in both classes. Nobody has listened to any of this: every claim here is a number.
 */
class ArcoBodyLiftTest {

    private val rate = Dsp.RATE
    private val knee = Arco.DEFAULT_BODY

    private fun f(v: Double, digits: Int = 2) = "%.${digits}f".format(java.util.Locale.ROOT, v)

    private fun s2(v: Double) = ArcoWarmthMeasure.s2(v)

    // ---- the gain tie ----------------------------------------------------------------------------------------

    /** A quiet, ordinary buffer: three sines at 220, 594 and 1166 Hz, 1.5 s at the house rate, peak about 0.35. Its loudness gain is about 1.1, so [Dsp.levelTo]'s 0.99 step never acts on it. */
    private fun normalBuffer(): FloatArray = FloatArray((1.5 * rate).toInt()) { i ->
        val t = i.toDouble() / rate
        (0.20 * sin(2.0 * PI * 220.0 * t) + 0.10 * sin(2.0 * PI * 594.0 * t) + 0.05 * sin(2.0 * PI * 1166.0 * t)).toFloat()
    }

    /** A hot buffer: a faint 220 Hz bed with a spike of 0.5 every 64 samples, so the loudness gain puts the peak well past 0.99 and [Dsp.levelTo]'s limiter step has to act. */
    private fun hotBuffer(): FloatArray {
        val buf = FloatArray((1.5 * rate).toInt()) { i -> (0.02 * sin(2.0 * PI * 220.0 * i / rate)).toFloat() }
        var i = 32
        while (i < buf.size) { buf[i] += 0.5f; i += 64 }
        return buf
    }

    private fun scaled(x: FloatArray, g: Float) = FloatArray(x.size) { x[it] * g }

    /** The largest difference between [a] and [b], over the largest magnitude in [b]. */
    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "sizes ${a.size} and ${b.size}" }
        var diff = 0.0
        var peak = 0.0
        for (i in a.indices) {
            diff = max(diff, abs(a[i].toDouble() - b[i]))
            peak = max(peak, abs(b[i].toDouble()))
        }
        return diff / max(peak, 1e-30)
    }

    /** What [Dsp.levelTo] does to [buf], on a copy (with [ceiling] if given). */
    private fun levelled(buf: FloatArray, ceiling: Float = 0.99f): FloatArray =
        buf.copyOf().also { Dsp.levelTo(it, rate, Dsp.MELODIC_LOUDNESS_TARGET, ceiling) }

    /**
     * [Arco.plainGain] is [Dsp.levelTo]'s own gain, copied because its 0.99 ceiling and 1e-6 silence line are default arguments and local numbers there that nothing else can read. The buffer times [Arco.plainGain] must equal
     * [Dsp.levelTo]'s output to 1e-6 of the peak, on a normal buffer (the loudness gain alone) and on a hot one (the 0.99 step acts: the unlimited peak is checked to be over 1.1, so the second half is really exercised), and
     * on a buffer just over and just under the silence line. Controls that must fail: [Dsp.levelTo] with a ceiling of 0.98 must differ from the gain by over 1e-3 on the hot buffer (a ceiling 0.01 off is seen), and the loudness gain alone
     * without the limiter step must differ from [Dsp.levelTo] by over 0.1 on the hot buffer (the hot buffer does need the step). A silent buffer takes a gain of 1 and [Arco.finished] of a silent string at BODY 0.5, 0.75 and 1 is exact zeros
     * (no NaN from nothing times a gain). R1f saw (designed, not measured) the gain agree with [Dsp.levelTo] to a last-place rounding (its two multiplies come out as one here); the bound is 1e-6. R1g saw a relative difference of 0 on the normal buffer, 6.0e-8 on the hot one (its unlimited peak 1.515, the normal buffer's 0.418), 0 on both sides of the silence line, 1.02e-2 for the ceiling-0.98 control and 0.530 for the no-limiter-step control.
     */
    @Test
    fun `the plain's gain is Dsp levelTo's, on a normal buffer, a hot buffer and the silence line, and a ceiling 0 point 01 off is seen`() {
        val target = Dsp.MELODIC_LOUDNESS_TARGET
        val normal = normalBuffer()
        val hot = hotBuffer()
        val normalNeeds = ArcoLiftRig.peakOf(scaled(normal, target / ArcoLiftRig.loudnessOf(normal)))
        val hotNeeds = ArcoLiftRig.peakOf(scaled(hot, target / ArcoLiftRig.loudnessOf(hot)))
        println("ARCO lift gain tie: the normal buffer needs a peak of ${f(normalNeeds.toDouble(), 3)} at the loudness gain (under 0.99: the limiter idle), the hot buffer ${f(hotNeeds.toDouble(), 3)} (over 0.99: the limiter acts)")
        assertTrue(normalNeeds < 0.95f, "the normal buffer is not normal: it needs a peak of $normalNeeds")
        assertTrue(hotNeeds > 1.1f, "the hot buffer does not exercise levelTo's 0.99 step: it needs only $hotNeeds")

        for ((label, buf) in listOf("normal" to normal, "hot" to hot)) {
            val gain = Arco.plainGain(buf)
            val rel = relativeDifference(scaled(buf, gain), levelled(buf))
            println("ARCO lift gain tie $label: gain ${f(gain.toDouble(), 5)}, relative difference from Dsp.levelTo ${"%.2e".format(java.util.Locale.ROOT, rel)}")
            assertTrue(rel <= 1e-6, "$label: the plain's gain is not Dsp.levelTo's (relative difference $rel)")
        }

        val relCeiling = relativeDifference(scaled(hot, Arco.plainGain(hot)), levelled(hot, ceiling = 0.98f))
        val loudnessOnly = scaled(hot, target / ArcoLiftRig.loudnessOf(hot))
        val relNoStep = relativeDifference(loudnessOnly, levelled(hot))
        println("ARCO lift gain tie controls: levelTo with a ceiling of 0.98 differs from the gain by ${"%.2e".format(java.util.Locale.ROOT, relCeiling)} (bar over 1e-3); the loudness gain without the limiter step by ${f(relNoStep, 3)} (bar over 0.1)")
        assertTrue(relCeiling > 1e-3, "control: a ceiling of 0.98 is not told apart from 0.99 ($relCeiling)")
        assertTrue(relNoStep > 0.1, "control: the hot buffer does not need the limiter step ($relNoStep)")

        // the silence line: a normal buffer scaled to a loudness just under 1e-6 is left alone by both, just over 1e-6 is lifted by both.
        for ((label, loudness) in listOf("under the line" to 5e-7f, "over the line" to 2e-6f)) {
            val quiet = scaled(normal, loudness / ArcoLiftRig.loudnessOf(normal))
            val rel = relativeDifference(scaled(quiet, Arco.plainGain(quiet)), levelled(quiet))
            println("ARCO lift gain tie silence line, $label (Loudness.of ${"%.1e".format(java.util.Locale.ROOT, ArcoLiftRig.loudnessOf(quiet))}): gain ${"%.3e".format(java.util.Locale.ROOT, Arco.plainGain(quiet))}, relative difference ${"%.2e".format(java.util.Locale.ROOT, rel)}")
            assertTrue(rel <= 1e-6, "silence line, $label: the plain's gain is not Dsp.levelTo's ($rel)")
        }
        assertTrue(Arco.plainGain(FloatArray(1000)) == 1f, "a silent buffer takes a gain of 1")
        assertTrue(Arco.plainGain(FloatArray(0)) == 1f, "an empty buffer takes a gain of 1")

        for (body in listOf(0.5f, 0.75f, 1f)) for (voice in ArcoVoice.entries) {
            val out = Arco.finished(FloatArray(ArcoLiftRig.RAW_RATE), voice, body, ArcoLiftRig.RAW_RATE)
            assertTrue(out.isNotEmpty() && out.all { it == 0f }, "$voice BODY $body: a silent string is not exact zeros")
        }
    }

    /**
     * The same tie on the notes the engine makes: at BODY 1 the conditioned note is the plain's to the sample (the box is frozen above the knee), and [Arco.plainGain] times it is [Dsp.levelTo]'s output on it to 1e-6 of the peak
     * (CELLO C2, C3 and ERHU C5 at default knobs); and the engine's finish at BODY 1 is that conditioned note through the lift [Arco.finishedMeasured] says it delivered, times that one gain, then the fade, sample for sample (what
     * [ArcoLiftRig.uncapped] relies on). Controls that must fail: the BODY 0 conditioned note (a different box) is not the plain's, and the same finish at a lift 0.5 dB off differs. R1f saw (designed) the plain's gain as the only gain above the knee; the bound is equality to the sample for the conditioned note and the finish, and 1e-6 for the gain. R1g saw both equalities hold on all three notes and a relative difference of 0 for the gain (gains 0.7558 at C2, 0.6569 at C3, 0.6026 at ERHU C5).
     */
    @Test
    fun `above the knee the conditioned note is the plain's and the engine's gain on it is Dsp levelTo's`() {
        for ((voice, step) in listOf(ArcoVoice.CELLO to 0, ArcoVoice.CELLO to 12, ArcoVoice.ERHU to 10)) {
            val tag = "$voice ${ArcoLiftRig.name(voice, step)}"
            val raw = ArcoLiftRig.rawOf(voice, ArcoLiftRig.macros(voice, step, knee))
            val plain = Arco.conditionedWithBody(raw.copyOf(), voice, knee, ArcoLiftRig.RAW_RATE)
            val high = Arco.conditionedWithBody(raw.copyOf(), voice, 1f, ArcoLiftRig.RAW_RATE)
            assertContentEquals(plain, high, "$tag: the conditioned note at BODY 1 is not the plain's: the box is not frozen above the knee")
            val dry = Arco.conditionedWithBody(raw.copyOf(), voice, 0f, ArcoLiftRig.RAW_RATE)
            assertTrue(!plain.contentEquals(dry), "control: $tag BODY 0 and BODY 0.5 condition to the same note, so equality above proves nothing")

            val gain = Arco.plainGain(plain)
            val rel = relativeDifference(scaled(plain, gain), levelled(plain))
            println("ARCO lift gain tie $tag: gain ${f(gain.toDouble(), 4)}, relative difference from Dsp.levelTo ${"%.2e".format(java.util.Locale.ROOT, rel)}")
            assertTrue(rel <= 1e-6, "$tag: the plain's gain is not Dsp.levelTo's on the real note ($rel)")

            val measured = Arco.finishedMeasured(raw.copyOf(), voice, 1f, ArcoLiftRig.RAW_RATE)
            fun rebuilt(db: Float): FloatArray {
                val out = Arco.lifted(plain, voice, db)
                for (i in out.indices) out[i] *= gain
                Dsp.fadeTail(out)
                return out
            }
            assertContentEquals(rebuilt(measured.lift.delivered), measured.samples, "$tag: the finish at BODY 1 is not the plain's conditioned note, the delivered lift, the plain's gain and the fade")
            assertTrue(!rebuilt(measured.lift.delivered + 0.5f).contentEquals(measured.samples), "control: $tag a lift 0.5 dB off gives the same samples")
        }
    }

    /**
     * [ArcoWarmthCandidates.ceilingFailures], the control that ties R1e's helper copy of the 0.99 ceiling to the engine, was generator-time only (`:synth:generateArcoWarmth`, not CI); it is a real test here. A hot buffer through
     * [Dsp.levelTo] must equal the same buffer through the gain and [Dsp.limitPeak] at 0.99; a hot raw buffer through [Arco.finished] must equal the helper's condition, gain, limit and fade; and a ceiling of 0.98 must be told
     * apart in both (its two "control failed to fail" messages). R1f saw it as a generator-time check that returned nothing wrong; the bound is an empty list. R1g saw 0 things wrong.
     */
    @Test
    fun `R1e's ceiling control is a real test, and the engine's ceiling is the helper's 0 point 99`() {
        val failures = ArcoWarmthCandidates.ceilingFailures()
        for (message in failures) println("ARCO lift ceiling control FAILED: $message")
        println("ARCO lift ceiling control: ${failures.size} things wrong")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    // ---- the top is never ducked --------------------------------------------------------------------------------

    /** The bar's rms rise at the notes where the cap is idle, in dB: R1f's own floor for the lift (the lift adds 3.5 dB per element at BODY 0.75). */
    private val riseBarDb = 2.8

    /** What is wrong with [n] against the top-band bar: 1-3k and 3-8k each within [ArcoWarmthMeasure.TOP_FLOOR_DB] of the plain, and (if [needRise]) the whole clip at least [riseBarDb] louder. Empty: nothing. */
    private fun barFailures(n: ArcoWarmthMeasure.Numbers, needRise: Boolean): List<String> {
        val out = ArrayList<String>()
        for (b in ArcoWarmthMeasure.TOP_BANDS) {
            val v = n.bands[b]
            if (v == null) out += "${ArcoWarmthMeasure.BAND_LABELS[b]} has no harmonic"
            else if (v < ArcoWarmthMeasure.TOP_FLOOR_DB) out += "${ArcoWarmthMeasure.BAND_LABELS[b]} is ${s2(v)} dB, under ${s2(ArcoWarmthMeasure.TOP_FLOOR_DB)}"
        }
        if (needRise && n.rmsRise < riseBarDb) out += "rms rise ${s2(n.rmsRise)} dB, under +${f(riseBarDb)}"
        return out
    }

    private fun row(tag: String, n: ArcoWarmthMeasure.Numbers) = "$tag | " + (0..3).joinToString(" ") { b -> n.bands[b]?.let { s2(it).padStart(6) } ?: "     ." } +
        " | rms ${s2(n.rmsRise)} loud ${s2(n.loudRise)} A ${s2(n.aRise)} | peak ${f(n.peak.toDouble(), 3)}"

    /**
     * The top-band bar, BODY 0.75 and 1.0 on the ten grid notes (CELLO C2 F#2 C3 F#3 C4, ERHU D4 G4 C5 F5 A5), measured with [ArcoWarmthMeasure] (absolute, not level-matched) on [Arco.render]: the 1-3k and 3-8k harmonic bands each
     * within -0.5 dB of the plain ([ArcoWarmthMeasure.TOP_FLOOR_DB]) on every note, and the whole clip's rms at least +2.8 dB over the plain at the notes where the cap is idle (the engine says which: [Arco.Lift.delivered] equals what was asked;
     * the set must hold CELLO F#3, C4 and all five ERHU notes at both BODY values, so the bar cannot quietly shrink). R1f saw, for the plain's gain, 3-8k at +0.02 to +0.07 dB (CELLO) and +0.17 to +0.37 (ERHU), 1-3k at +0.13 to +0.56 (CELLO)
     * and +1.35 to +3.91 (ERHU), rms +3.5 to +3.9 at BODY 0.75 and +6.2 to +6.9 at BODY 1 (predicted); the bound is -0.5 dB on both top bands and +2.8 dB rms. R1g saw, on the real engine: BODY 0.75 3-8k +0.03 to +0.04 (CELLO) and +0.15 to +0.20 (ERHU), 1-3k +0.20 to +0.31 and +0.76 to +2.19, rms +3.02 (C2, capped) to +3.72 and +3.48 to +3.89; BODY 1 3-8k +0.03 to +0.07 and +0.26 to +0.36, 1-3k +0.24 to +0.55 and +1.34 to +3.90, rms +3.02 (C2) to +6.48 and +6.15 to +6.87, so no band below the plain anywhere. Negative controls that must FAIL the bar, on the same signals: the BODY 1 and BODY 0.75 renders
     * re-levelled to the plain's [Loudness.of] (what R1c's page did: R1f saw CELLO C3 3-8k at -6.3 dB and ERHU C5 at -6.0) must fail the top-band bar on all ten notes (R1g saw them fail on all ten: 3-8k from -2.95 dB at C2 to -6.60 at F5 at BODY 1, CELLO C3 -6.30 and ERHU C5 -6.01);
     * and the BODY 0.6 render (R1f saw +1.5 dB rms; R1g saw +1.47 to +1.64 on all ten notes) must fail the rise bar, so the rise bar can say no. The two numeric readings of the re-levelled control (3-8k under -5.3 dB at C3 and
     * under -5.0 dB at C5) depend on the lift's size and are a listening-value pin of their own, in `the re-levelled top-band control reads R1c's numbers, a listening-value pin`, so a one-constant retune cannot turn this bar test red.
     */
    @Test
    fun `the top is never ducked, 1-3k and 3-8k within minus 0 point 5 dB of the plain and the idle notes at least 2 point 8 dB louder, and the re-levelled build fails it`() {
        val problems = ArrayList<String>()
        val lines = ArrayList<String>()
        for (body in listOf(0.75f, 1f)) {
            val idle = ArrayList<String>()
            for ((voice, step) in ArcoLiftRig.NOTES) {
                val tag = "${voice.name.take(1)} ${ArcoLiftRig.name(voice, step).padEnd(3)} BODY ${f(body.toDouble())}"
                val lift = ArcoLiftRig.measured(voice, step, body).lift
                val idleNote = lift.delivered >= lift.asked
                val n = ArcoLiftRig.numbers(voice, step, body)
                lines += row("ARCO lift top band $tag ${if (idleNote) "cap idle " else "cap acted"}", n)
                for (m in barFailures(n, needRise = idleNote)) problems += "$tag: $m"
                if (idleNote) idle += "$voice ${ArcoLiftRig.name(voice, step)}"

                // control 1: the same signal re-levelled to the plain's Loudness.of, R1c's reading, must fail the top-band bar.
                val plain = ArcoLiftRig.render(voice, step, knee)
                val x = ArcoLiftRig.render(voice, step, body)
                val g = ArcoLiftRig.loudnessOf(plain) / ArcoLiftRig.loudnessOf(x)
                val relevelled = ArcoWarmthMeasure.compare(scaled(x, g), ArcoLiftRig.base(voice, step))
                lines += row("ARCO lift top band RE-LEVELLED (R1c's reading) $tag", relevelled)
                if (barFailures(relevelled, needRise = false).isEmpty()) problems += "control: $tag re-levelled to the plain's Loudness.of still passes the top-band bar"
            }
            val need = listOf("CELLO F#3", "CELLO C4") + listOf("D4", "G4", "C5", "F5", "A5").map { "ERHU $it" }
            for (n in need) if (n !in idle) problems += "BODY $body: $n is not a cap-idle note, so the rise bar does not cover it (idle: $idle)"
        }
        // control 2: the rise bar can say no. BODY 0.6 is a lift of 1.52 dB per element, R1f saw +1.5 dB rms, well under 2.8.
        for ((voice, step) in ArcoLiftRig.NOTES) {
            val n = ArcoWarmthMeasure.compare(ArcoLiftRig.render(voice, step, 0.6f), ArcoLiftRig.base(voice, step))
            lines += row("ARCO lift top band CONTROL ${voice.name.take(1)} ${ArcoLiftRig.name(voice, step).padEnd(3)} BODY 0.60", n)
            if (barFailures(n, needRise = true).none { it.startsWith("rms rise") }) problems += "control: $voice ${ArcoLiftRig.name(voice, step)} at BODY 0.6 reads rms ${s2(n.rmsRise)} dB and passes the rise bar"
        }
        println("ARCO lift top band: absolute band deltas vs THE PLAIN ONE, dB (80-300 300-1k 1k-3k 3k-8k, a dot is a band with no harmonic), then rms, Loudness.of and A-weighted rise and the peak; bar: 1k-3k and 3k-8k at least -0.5 dB, rms at least +${f(riseBarDb)} at cap-idle notes")
        for (l in lines) println(l)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The listening-value pin of the top-band negative control, kept out of the bar test above on purpose. What the BODY 1 render reads in 3-8k once it is re-levelled to the plain's [Loudness.of] (R1c's reading) depends on
     * [Arco.LIFT_HALF_DB] and [Arco.LIFT_TOP_DB], the two constants the owner's listening may move (R1f's fix map, one constant each), so a retune turns THIS test red, by name, to be re-aimed on purpose together with the
     * constant; the bar test (the re-levelled build must fail the top-band bar on all ten notes) holds no listening value and stays green. The pins are written for 3.5 and 6.0 dB per element: the first two assertions say so.
     * R1f saw R1c's reading as CELLO C3 3-8k -6.3 dB and ERHU C5 -6.0; the bound is under -5.3 dB (C3) and under -5.0 dB (C5); R1g saw -6.30 and -6.01.
     */
    @Test
    fun `the re-levelled top-band control reads R1c's numbers, a listening-value pin`() {
        assertEquals(3.5f, Arco.LIFT_HALF_DB, "these pins are written for the lift's small stop of 3.5 dB per element: if it moved on purpose (the owner's listening), re-aim them with it")
        assertEquals(6.0f, Arco.LIFT_TOP_DB, "these pins are written for the lift's top stop of 6.0 dB per element: if it moved on purpose (the owner's listening), re-aim them with it")
        val problems = ArrayList<String>()
        for ((voice, step, limit) in listOf(Triple(ArcoVoice.CELLO, 12, -5.3), Triple(ArcoVoice.ERHU, 10, -5.0))) {
            val plain = ArcoLiftRig.render(voice, step, knee)
            val x = ArcoLiftRig.render(voice, step, 1f)
            val g = ArcoLiftRig.loudnessOf(plain) / ArcoLiftRig.loudnessOf(x)
            val top = ArcoWarmthMeasure.compare(scaled(x, g), ArcoLiftRig.base(voice, step)).bands[3]!!
            println("ARCO lift top band re-levelled pin ${voice.name.take(1)} ${ArcoLiftRig.name(voice, step)} BODY 1.00: 3-8k ${s2(top)} dB, bound under ${s2(limit)}")
            if (top > limit) problems += "control: $voice ${ArcoLiftRig.name(voice, step)} re-levelled reads 3-8k at ${s2(top)} dB, R1c's reading was about -6 (bound under ${s2(limit)})"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ---- the knob never goes backwards --------------------------------------------------------------------------

    /** BODY from 0.5 to 1.0 in steps of 0.05. */
    private val sweepBodies: List<Float> = (0..10).map { 0.5f + it / 20f }

    /** Every place a series (dB, one per BODY of [sweepBodies]) falls by more than [toleranceDb] from one value to the next: (index, fall). */
    private fun falls(series: DoubleArray, toleranceDb: Double): List<Pair<Int, Double>> =
        (1 until series.size).mapNotNull { i -> if (series[i] - series[i - 1] < -toleranceDb) i to series[i - 1] - series[i] else null }

    /**
     * The knob never goes backwards: the finished clip's rms (dB, whole clip, [Arco.render]) is non-decreasing in BODY from 0.5 to 1.0 in steps of 0.05, within 0.01 dB, on all 45 TUNE steps (25 CELLO, 20 ERHU) at default knobs. It holds because
     * the cap on the lift is a property of the note and not of BODY (the lift is the smaller of what BODY asks and the note's cap), so a larger BODY is never a smaller lift. R1f saw design C's version (a different curve) on the same 45 steps and
     * predicted +0.8 dB over the plain at BODY 0.55, +1.5 to +1.6 at 0.6, +3.5 to +3.7 at 0.75, +4.5 to +5.5 at 0.9 and +6.2 to +6.9 at 1.0 at the notes where the cap is idle; the bound is a fall of at most 0.01 dB from one BODY to the next
     * (the table of the rise over the plain, min / median / max over the steps, is printed). R1g saw a worst fall of 0.0000 dB on all 45 steps, and the rise over the plain (min / median / max over the steps of both voices) +0.74 to +0.87 dB at BODY 0.55, +1.47 to +1.71 at 0.6, +2.86 (CELLO C2 at its cap) to +4.03 at 0.75, +2.86 to +5.97 at 0.9 and +2.86 to +7.03 at 1.0. Controls that must fail: the sweep reversed (BODY 1 down to 0.5) must fall on every step, and the sweep with its last two values swapped must fall
     * on every step where those two differ by more than the tolerance (an engine whose top 0.05 turned over would be caught the same way).
     */
    @Test
    fun `the knob never goes backwards, finished rms is non-decreasing in BODY from 0 point 5 to 1 on all 45 TUNE steps`() {
        val tolerance = 0.01
        class Sweep(val voice: ArcoVoice, val step: Int, val rmsDb: DoubleArray)
        val cells = ArcoVoice.entries.flatMap { v -> (0..Arco.tuneSemitones(v)).map { v to it } }
        val sweeps = with(ArcoLiftRig) {
            cells.pmap { (voice, step) -> Sweep(voice, step, DoubleArray(sweepBodies.size) { rmsDb(Arco.render(voice, macros(voice, step, sweepBodies[it])).samples) }) }
        }
        assertEquals(45, sweeps.size, "the sweep is every TUNE step of both voices")
        val problems = ArrayList<String>()
        var worstFall = 0.0
        var worstWhere = ""
        for (s in sweeps) {
            val tag = "${s.voice} ${ArcoLiftRig.name(s.voice, s.step)}"
            for ((i, fall) in falls(s.rmsDb, tolerance)) {
                problems += "$tag: rms falls ${f(fall, 4)} dB from BODY ${f(sweepBodies[i - 1].toDouble())} to ${f(sweepBodies[i].toDouble())}"
                if (fall > worstFall) { worstFall = fall; worstWhere = "$tag at BODY ${f(sweepBodies[i].toDouble())}" }
            }
            // controls: the reversed sweep must fall, and so must a sweep whose last two values are swapped (where they differ by more than the tolerance).
            if (falls(s.rmsDb.reversedArray(), tolerance).isEmpty()) problems += "control: $tag reversed does not fall, so the check cannot see a knob that goes backwards"
            val swapped = s.rmsDb.copyOf().also { val t = it[it.size - 1]; it[it.size - 1] = it[it.size - 2]; it[it.size - 2] = t }
            if (s.rmsDb[s.rmsDb.size - 1] - s.rmsDb[s.rmsDb.size - 2] > 2 * tolerance && falls(swapped, tolerance).isEmpty()) problems += "control: $tag with its last two values swapped does not fall"
        }
        println("ARCO lift knob sweep: finished rms rise over the plain (BODY 0.5), dB, min / median / max over the TUNE steps of each voice, BODY 0.5 to 1.0 in steps of 0.05; worst fall from one BODY to the next: ${f(worstFall, 4)} dB ${worstWhere}")
        for (voice in ArcoVoice.entries) {
            val mine = sweeps.filter { it.voice == voice }
            val idle = mine.filter { ArcoLiftRig.measured(voice, it.step, 1f).lift.let { l -> l.delivered >= l.asked } }
            for (i in sweepBodies.indices) {
                fun trio(of: List<Sweep>): String {
                    val rises = of.map { it.rmsDb[i] - it.rmsDb[0] }
                    return if (rises.isEmpty()) "n/a" else "${s2(rises.min())} / ${s2(ArcoBodyMeasure.median(rises))} / ${s2(rises.max())}"
                }
                println("ARCO lift knob sweep $voice BODY ${f(sweepBodies[i].toDouble())}: all ${mine.size} steps ${trio(mine)}; the ${idle.size} cap-idle steps at BODY 1: ${trio(idle)}")
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** The largest difference between two renders over the peak of [a]. */
    private fun gap(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size, "the render's length moved with BODY")
        var d = 0f
        for (i in a.indices) d = max(d, abs(a[i] - b[i]))
        return d.toDouble() / ArcoLiftRig.peakOf(a)
    }

    /**
     * The knee is continuous: the render at BODY 0.5000001 is within 1e-5 of the peak of the render at BODY 0.5 (largest difference over the peak) on all 45 TUNE steps. Just above the knee the lift is about 0 dB, though the branch is not bit-identical across it (a filter at 0 dB is not a skipped filter). R1f predicted "about 1e-5 of the peak" and, in its risks, a float32 coefficient-rounding noise of -72 dB (2.5e-4 of the peak) for the 200 and 300 Hz stages: the two disagree, and this test holds the first, as the design states it; the bound is 1e-5. R1g first saw it MISSED, with the lift on Dsp.Biquad's float32 sections: 2.09e-4 of the peak at CELLO step 9 (A2) and 3.19e-5 at ERHU step 1, and the difference did not shrink with the lift (2.09e-4 at a lift of 1.9e-6 dB, 7.2e-5 at 1.6e-5 dB, 2.7e-4 at 1.6e-4 dB and 5.4e-4 at 1.6e-3 dB at CELLO step 9), so it was float32 coefficient and state rounding of the 200 and 300 Hz stages (the -72 dB R1f's own risks named), not a lift. The engine's lift now runs in Double ([Arco]'s private LiftSection, the same RBJ formulas, the same stages, rate and order, narrowed to Float once); R1g saw 3.64e-7 of the peak at CELLO (worst step 0) and 3.55e-7 at ERHU (worst step 1), about 28 times under the bar, and the difference now grows with the lift as a lift does (CELLO step 0: 3.6e-7 at 1.9e-6 dB, 2.3e-6 at 1.6e-5 dB, 2.2e-5 at 1.6e-4 dB, 2.2e-4 at 1.6e-3 dB), so the predicted 1e-5 holds. The bar is the original 1e-5, never loosened. Control that must fail: BODY 0.55 (a lift of 0.78 dB per element) must differ from BODY 0.5 by over 1e-2 of the peak on every step (R1g saw 9.41e-2 at CELLO and 8.97e-2 at ERHU at least), so the measure can see a
     * lift. Printed, not asserted, to tell a lift from noise: the same difference at BODY 0.5000001, 0.500001, 0.50001 and 0.5001 on the worst step of each voice (a lift grows with the BODY step, rounding noise does not).
     */
    @Test
    fun `the knee is continuous, the render at BODY 0 point 5000001 is within 1e-5 of the peak of the render at 0 point 5`() {
        class Knee(val voice: ArcoVoice, val step: Int, val above: Double, val control: Double)
        val cells = ArcoVoice.entries.flatMap { v -> (0..Arco.tuneSemitones(v)).map { v to it } }
        val knees = with(ArcoLiftRig) {
            cells.pmap { (voice, step) ->
                val plain = render(voice, step, 0.5f)
                Knee(voice, step, gap(plain, render(voice, step, 0.5000001f)), gap(plain, render(voice, step, 0.55f)))
            }
        }
        println("ARCO lift knee: largest difference over the peak, BODY 0.5000001 against 0.5 (bar 1e-5) and BODY 0.55 against 0.5 (control, bar over 1e-2): " +
            ArcoVoice.entries.joinToString("; ") { v -> "$v ${"%.2e".format(java.util.Locale.ROOT, knees.filter { it.voice == v }.maxOf { it.above })} (worst step ${knees.filter { it.voice == v }.maxBy { it.above }.step}) / ${"%.2e".format(java.util.Locale.ROOT, knees.filter { it.voice == v }.minOf { it.control })}" })
        for (v in ArcoVoice.entries) {
            val worst = knees.filter { it.voice == v }.maxBy { it.above }
            val plain = ArcoLiftRig.render(v, worst.step, 0.5f)
            println(
                "ARCO lift knee noise check $v step ${worst.step}: difference over the peak at BODY " + listOf(0.5000001f, 0.500001f, 0.50001f, 0.5001f).joinToString(", ") { b ->
                    "${"%.7f".format(java.util.Locale.ROOT, b)} ${"%.2e".format(java.util.Locale.ROOT, gap(plain, ArcoLiftRig.render(v, worst.step, b)))} (lift ${"%.1e".format(java.util.Locale.ROOT, Arco.liftDbFor(b))} dB per element)"
                },
            )
        }
        val problems = ArrayList<String>()
        for (k in knees) {
            if (k.above > 1e-5) problems += "${k.voice} step ${k.step}: the render at BODY 0.5000001 is ${"%.2e".format(java.util.Locale.ROOT, k.above)} of the peak from BODY 0.5 (bar 1e-5)"
            if (k.control <= 1e-2) problems += "control: ${k.voice} step ${k.step}: BODY 0.55 is only ${k.control} of the peak from BODY 0.5, so the measure cannot see a lift"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The capped notes are one sound: CELLO C2 at BODY 0.75, 0.8 and 0.9 equals C2 at BODY 1 to the sample (the cap there, about 2.9 dB per element, is under what BODY 0.7 and more asks, and it does not depend on BODY). R1f saw the C2 renders identical from BODY 0.70 up (predicted); the bound is 0 differing samples. R1g saw identical samples at 0.75, 0.8 and 0.9 and C2 at BODY 0.6 differing from BODY 1 by 1.72e-1 of the peak. Controls that must fail: C2 at BODY 0.6 (1.52 dB asked, under the cap) differs from BODY 1, and ERHU C5 at BODY 0.75 differs from C5 at BODY 1.
     */
    @Test
    fun `CELLO C2 at BODY 0 point 75 is C2 at BODY 1 to the sample, the cap does not depend on BODY`() {
        fun c2(body: Float) = ArcoLiftRig.render(ArcoVoice.CELLO, 0, body)
        val top = c2(1f)
        for (body in listOf(0.75f, 0.8f, 0.9f)) assertContentEquals(top, c2(body), "CELLO C2 at BODY $body is not the same samples as BODY 1: the cap depends on BODY")
        println("ARCO lift knee: CELLO C2 at BODY 0.75, 0.8 and 0.9 equals BODY 1 to the sample; at BODY 0.6 it differs from BODY 1 by ${"%.2e".format(java.util.Locale.ROOT, gap(top, c2(0.6f)))} of the peak")
        assertTrue(!top.contentEquals(c2(0.6f)), "control: CELLO C2 at BODY 0.6 equals BODY 1, so the equality above proves nothing")
        assertTrue(!ArcoLiftRig.render(ArcoVoice.ERHU, 10, 0.75f).contentEquals(ArcoLiftRig.render(ArcoVoice.ERHU, 10, 1f)), "control: ERHU C5 at BODY 0.75 equals BODY 1")
    }

    // ---- the CI equality -----------------------------------------------------------------------------------------

    /**
     * The engine's pipeline is built from the engine's pieces, above the knee as well as at it: for CELLO C2, F#2, C3 and ERHU C5, A5 at BODY 0.75 and 1.0, [Arco.finished] of the string [Arco.bow] makes (settled knobs, the note's pitch, the
     * raw rate) is [Arco.render]'s samples, sample for sample (mono, at the house rate). Before the lift the only such equality was at the default BODY (ArcoProductTest's finishedCore), so a test pipeline that finished above the knee some
     * other way could pass without testing the engine. Controls that must fail: the stale route (the box, the conditioning, [Dsp.levelTo] and the fade: the finish the lift replaced) equals the engine at BODY 0.5 and must differ from it at
     * every one of these cells above the knee, and an engine render of a different BODY must differ. R1f saw ArcoTest's 162-cell grid and the generators each carrying a copy of the finish as the defect this closes; the bound is 0 differing samples. R1g saw 0 differing samples in all ten cells (58720 samples at the CELLO notes, 48085 at ERHU C5, 46497 at A5) and the stale levelled finish differing in every sample but the last (the fade ends at 0).
     */
    @Test
    fun `Arco finished built from Arco bow equals Arco render sample for sample above the knee, and a stale levelled finish does not`() {
        val cells = listOf(ArcoVoice.CELLO to 0, ArcoVoice.CELLO to 6, ArcoVoice.CELLO to 12, ArcoVoice.ERHU to 10, ArcoVoice.ERHU to 19)
        fun stale(voice: ArcoVoice, macros: Map<String, Float>, body: Float): FloatArray {
            val out = Arco.conditionedWithBody(ArcoLiftRig.rawOf(voice, macros), voice, body, ArcoLiftRig.RAW_RATE)
            Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(out)
            return out
        }
        for ((voice, step) in cells) {
            val tag = "$voice ${ArcoLiftRig.name(voice, step)}"
            for (body in listOf(0.75f, 1f)) {
                val macros = ArcoLiftRig.macros(voice, step, body)
                val m = Arco.settled(macros, voice)
                val built = Arco.finished(ArcoLiftRig.rawOf(voice, macros), voice, m.getValue("BODY"), ArcoLiftRig.RAW_RATE)
                val engine = Arco.render(voice, macros)
                assertEquals(1, engine.channels, "$tag BODY $body: not mono")
                assertEquals(Dsp.RATE, engine.sampleRate, "$tag BODY $body: not at the house rate")
                assertContentEquals(engine.samples, built, "$tag BODY $body: Arco.finished of Arco.bow is not what Arco.render makes")
                val staleOut = stale(voice, macros, body)
                val differing = staleOut.indices.count { staleOut[it].toRawBits() != built[it].toRawBits() }
                println("ARCO lift CI equality $tag BODY ${f(body.toDouble())}: ${built.size} samples, 0 differ from Arco.render; the stale levelled finish differs in $differing")
                assertTrue(staleOut.size != built.size || differing > 0, "control: $tag BODY $body: the stale levelled finish equals the engine's, so this equality cannot see a pipeline that skips the lift")
            }
            val half = ArcoLiftRig.macros(voice, step, knee)
            assertContentEquals(Arco.render(voice, half).samples, stale(voice, half, knee), "$tag BODY 0.5: the stale route is not the plain's finish, so its difference above the knee says little")
            if (step != 0) {
                assertTrue(!Arco.render(voice, ArcoLiftRig.macros(voice, step, 0.75f)).samples.contentEquals(Arco.render(voice, ArcoLiftRig.macros(voice, step, 1f)).samples), "control: $tag BODY 0.75 and 1 render alike")
            }
        }
    }

    /** One LOOP rebuilt from the engine's published pieces, the lines of [Arco.renderLoopMeasured] as the design states them, with the lift [delivered] by the engine (the cap search is checked on its own in the peak tests). */
    private fun loopFromPieces(voice: ArcoVoice, macros: Map<String, Float>, delivered: Float): Pair<FloatArray, Double> {
        // copied from Arco's private constants: the pitch passes, their convergence, and the frames the seam reads. A drift in them fails the equality below by name.
        val passes = 5
        val converged = 3e-7
        val seamFrames = 256
        val m = Arco.settled(macros, voice)
        val target = Arco.frequencyFor(voice, m.getValue("TUNE"))
        val plan = Arco.planLoop(target)
        val over = Dsp.OVERSAMPLE
        val rawRate = Dsp.RATE * over
        val warm = Math.round(max(Arco.LOOP_WARMUP_SECONDS, Arco.LOOP_WARMUP_PERIODS / target) * Dsp.RATE)
        val wanted = plan.frames.toDouble() * over
        var tuned = target.toDouble()
        var raw = Arco.stretch(voice, m, tuned.toFloat(), warm, plan.frames)
        for (pass in 1..passes) {
            val ratio = Bore.measureLoopSamples(raw, warm * over, wanted, plan.periods) / wanted
            if (abs(ratio - 1.0) < converged) break
            tuned *= ratio
            raw = Arco.stretch(voice, m, tuned.toFloat(), warm, plan.frames)
        }
        val conditioned = Arco.conditionedWithBody(raw, voice, m.getValue("BODY"), rawRate)
        val lifted = Arco.lifted(conditioned, voice, delivered)
        val seam = Keys.seamError(lifted.copyOfRange(warm, warm + plan.frames + seamFrames), seamFrames)
        val one = lifted.copyOfRange(warm, warm + plan.frames)
        val cut = Siren.bestCut(one, plan.frames)
        val loop = one.copyOfRange(cut, plan.frames) + one.copyOfRange(0, cut)
        // the gain is the plain's own: the plain's kept window (the conditioned stretch, no lift) rotated at the same cut.
        val plainOne = conditioned.copyOfRange(warm, warm + plan.frames)
        val gain = Arco.plainGain(plainOne.copyOfRange(cut, plan.frames) + plainOne.copyOfRange(0, cut))
        for (i in loop.indices) loop[i] *= gain
        return loop to seam
    }

    /**
     * The same equality as a loop (HOLD at its top step): at BODY 0.75 and 1.0, CELLO C2, C3 and ERHU C5, A5, [Arco.render] is [Arco.renderLoopMeasured]'s loop sample for sample, and that loop is the one built here from the engine's
     * published pieces: [Arco.stretch] with the engine's pitch passes, [Arco.conditionedWithBody], [Arco.lifted] at the lift the engine delivered, the seam read on the lifted stretch ([Keys.seamError], the same double), [Siren.bestCut] on the
     * lifted window and [Arco.plainGain] of the plain's own window rotated at that cut, with no fade and no [Dsp.levelTo] after the lift. R1f saw nothing but the plain's loop pinned; the bound is 0 differing samples, the same seam reading, a seam under [Keys.MAX_SEAM_ERROR], a loop peak at most the cap ([Arco.PEAK_CAP], or the plain loop's own peak if that is higher) and a loop that carries the lift (rms over the BODY 0.5 loop by at least 2 dB). R1g saw 0 differing samples in all eight loops, seams 5e-9 to 1.4e-6, peaks 0.940 at CELLO C2 (the loop's cap there is 3.03 dB per element, the one-shot's 2.86) and 0.912 at C3 BODY 1, and a rise over the plain loop of +3.24 dB (C2) to +6.55 (C3 at BODY 1) for CELLO and +3.49 to +6.39 for ERHU. Controls that must fail: the
     * plain's loop (BODY 0.5) is not the BODY 0.75 loop, and the loop built with the lift left out is not the engine's.
     */
    @Test
    fun `a loop above the knee is Arco render's loop, built from the engine's pieces, with the same seam`() {
        val cells = listOf(ArcoVoice.CELLO to 0, ArcoVoice.CELLO to 12, ArcoVoice.ERHU to 10, ArcoVoice.ERHU to 19)
        class Row(val tag: String, val problems: List<String>, val line: String)
        val rows = with(ArcoLiftRig) {
            cells.flatMap { (voice, step) -> listOf(0.75f, 1f).map { voice to (step to it) } }.pmap { (voice, cell) ->
                val (step, body) = cell
                val tag = "$voice ${name(voice, step)} BODY ${f(body.toDouble())}"
                val macros = macros(voice, step, body) + mapOf("HOLD" to 1f)
                val engine = Arco.renderLoopMeasured(voice, macros)
                val plainLoop = Arco.renderLoopMeasured(voice, macros + mapOf("BODY" to knee))
                val bad = ArrayList<String>()
                if (!Arco.render(voice, macros).samples.contentEquals(engine.loop)) bad += "Arco.render of the loop is not renderLoopMeasured's loop"
                val (pieces, seam) = loopFromPieces(voice, macros, engine.lift.delivered)
                val differing = pieces.indices.count { pieces[it].toRawBits() != engine.loop[it].toRawBits() }.let { it + abs(pieces.size - engine.loop.size) }
                if (differing != 0) bad += "the loop built from the engine's pieces differs from the engine's in $differing samples"
                if (seam.toRawBits() != engine.seam.toRawBits()) bad += "the seam differs: ${engine.seam} against ${seam}"
                if (engine.seam >= Keys.MAX_SEAM_ERROR) bad += "the loop does not close: seam ${engine.seam}"
                val limit = max(Arco.PEAK_CAP, peakOf(plainLoop.loop))
                if (peakOf(engine.loop) > limit + 1e-6f) bad += "the loop peaks at ${peakOf(engine.loop)}, over the cap $limit"
                val rise = rmsDb(engine.loop) - rmsDb(plainLoop.loop)
                if (rise < 2.0) bad += "the loop is only ${s2(rise)} dB louder than the BODY 0.5 loop"
                if (engine.loop.contentEquals(plainLoop.loop)) bad += "control: the loop equals the BODY 0.5 loop"
                val unlifted = loopFromPieces(voice, macros, 0f).first
                if (unlifted.size == engine.loop.size && unlifted.contentEquals(engine.loop)) bad += "control: the loop built with no lift equals the engine's"
                Row(tag, bad, "ARCO lift CI equality loop $tag: ${engine.loop.size} samples, $differing differ from the pieces, seam ${"%.2e".format(java.util.Locale.ROOT, engine.seam)}, lift asked ${f(engine.lift.asked.toDouble())} cap ${f(engine.lift.cap.toDouble())} delivered ${f(engine.lift.delivered.toDouble())} dB per element, " +
                    "peak ${f(peakOf(engine.loop).toDouble(), 3)} (plain loop ${f(peakOf(plainLoop.loop).toDouble(), 3)}), rms ${s2(rise)} dB over the plain loop")
            }
        }
        for (r in rows) println(r.line)
        assertTrue(rows.all { it.problems.isEmpty() }, rows.filter { it.problems.isNotEmpty() }.joinToString("\n") { r -> r.tag + ": " + r.problems.joinToString("; ") })
    }
}
