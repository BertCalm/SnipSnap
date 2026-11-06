package com.snipsnap.synth

import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2: the LOOP's vibrato ([Arco.loopVibrato]), put in after the owner heard ENDLESS DRAW and ENDLESS CRY on 2026-11-04 ("Sounds a bit cheap, try the
 * vibrato fix. Mechanical sounding.").
 *
 *  - **It repeats with the loop.** Every frequency in [Arco.loopVibratoUnit] is a whole number of cycles per loop, so the swing one loop later is the
 *    same swing, to 1e-9, for both variants; the control is the one-shot's own 6.1 Hz sine, which does not.
 *  - **The loop still closes.** R1b's own grid of 225 loops (every TUNE step of both voices at the defaults, GRIP 0, GRIP 1, BODY 0 and BODY 1), once
 *    with the plain swing snapped to the loop ([Arco.LoopVibrato.SNAPPED]) and once with the humanised one ([Arco.LoopVibrato.HUMAN]), each loop under
 *    the Organ's bar (`Keys.MAX_SEAM_ERROR`), read as the engine reads it (the kept stretch against itself one loop later). The retune and the seam bar
 *    decide; nothing is crossfaded. The humanised one closes at all 225 and is the one that ships ([Arco.LOOP_VIBRATO]).
 *  - **Its mean pitch is the note's.** The power-weighted centre of the fundamental's sidebands over one loop sits on the loop's own fundamental bin
 *    (the K whole periods), as it does with no vibrato: the vibrato moves the pitch around the note, not off it.
 */
class ArcoLoopVibratoTest {

    private fun e(v: Double) = "%.2e".format(Locale.ROOT, v)
    private fun f(v: Double, d: Int = 4) = "%.${d}f".format(Locale.ROOT, v)

    @Test
    fun `the loop's swing is the same one loop later, both variants, and the one-shot's is not`() {
        for (loopSeconds in listOf(2.0, 2.0029024943310656, 2.0005895691609976, 2.006)) {
            var worst = 0.0
            for (kind in listOf(Arco.LoopVibrato.SNAPPED, Arco.LoopVibrato.HUMAN)) for (k in 0 until 200) {
                val u = 0.37 + k * 0.0731
                worst = maxOf(worst, abs(Arco.loopVibratoUnit(u + loopSeconds, loopSeconds, kind) - Arco.loopVibratoUnit(u, loopSeconds, kind)))
            }
            val oneShot = (0 until 200).maxOf { k -> val u = 0.37 + k * 0.0731; abs(Arco.vibratoUnit(u + loopSeconds, Arco.VIBRATO_PLAIN) - Arco.vibratoUnit(u, Arco.VIBRATO_PLAIN)) }
            println("ARCO loop vibrato periodicity, a ${f(loopSeconds)} s loop: ${Arco.loopVibratoCycles(loopSeconds)} cycles at ${f(Arco.loopVibratoHz(loopSeconds))} Hz, the swing one loop later differs by at most ${e(worst)}; the one-shot's 6.1 Hz by ${f(oneShot)}")
            assertTrue(worst < 1e-9, "a ${loopSeconds} s loop: the loop's swing does not repeat ($worst)")
            assertTrue(oneShot > 0.1, "control: the one-shot's swing repeats with a ${loopSeconds} s loop ($oneShot), so the check cannot fail")
        }
        assertEquals(12, Arco.loopVibratoCycles(2.0029))
        assertTrue(Arco.LOOP_VIBRATO == Arco.LoopVibrato.HUMAN, "the shipped loop vibrato is not the humanised one")
        assertEquals(Arco.VIBRATO_MAX_CENTS, Arco.LOOP_VIBRATO_CENTS, "a LOOP is at the one-shot's full depth")
    }

    private class Case(val voice: ArcoVoice, val step: Int, val corner: String, val macros: Map<String, Float>)

    private class Outcome(val case: Case, val kind: Arco.LoopVibrato, val seam: Double, val finite: Boolean, val millis: Long)

    @Test
    fun `R1b's 225 loops close with the snapped vibrato and with the humanised one`() {
        val cases = ArcoVoice.entries.flatMap { voice ->
            (0..Arco.tuneSemitones(voice)).flatMap { step ->
                val tune = step / Arco.tuneSemitones(voice).toFloat()
                listOf(
                    "defaults" to emptyMap(),
                    "GRIP 0" to mapOf("GRIP" to 0f),
                    "GRIP 1" to mapOf("GRIP" to 1f),
                    "BODY 0" to mapOf("BODY" to 0f),
                    "BODY 1" to mapOf("BODY" to 1f),
                ).map { (corner, knobs) -> Case(voice, step, corner, Arco.defaults(voice) + knobs + mapOf("TUNE" to tune, "HOLD" to 1f)) }
            }
        }
        val kinds = listOf(Arco.LoopVibrato.SNAPPED, Arco.LoopVibrato.HUMAN)
        val outcomes = kinds.flatMap { kind -> cases.map { it to kind } }.parallelStream().map { (c, kind) ->
            val started = System.nanoTime()
            val r = Arco.renderLoopMeasured(c.voice, c.macros, loopVibrato = kind)
            Outcome(c, kind, r.seam, r.loop.all { it.isFinite() && it in -1f..1f }, (System.nanoTime() - started) / 1_000_000L)
        }.toList()
        assertEquals(450, outcomes.size)
        for (kind in kinds) for (voice in ArcoVoice.entries) {
            val mine = outcomes.filter { it.kind == kind && it.case.voice == voice }
            val worst = mine.maxBy { it.seam }
            val rows = listOf("defaults", "GRIP 0", "GRIP 1", "BODY 0", "BODY 1").joinToString(", ") { corner ->
                "$corner ${e(mine.filter { it.case.corner == corner }.maxOf { it.seam })}"
            }
            println("ARCO loop vibrato $kind $voice: ${mine.size} loops, worst seam ${e(worst.seam)} at step ${worst.case.step} ${worst.case.corner} (bar ${e(Keys.MAX_SEAM_ERROR)}); worst per row: $rows; ${mine.sumOf { it.millis }} ms summed")
        }
        // the highest seams beside the same loops with no vibrato: what the string does on its own.
        for (o in outcomes.sortedByDescending { it.seam }.take(4)) {
            val plain = Arco.renderLoopMeasured(o.case.voice, o.case.macros, loopVibrato = Arco.LoopVibrato.OFF).seam
            println("ARCO loop vibrato, a high seam: ${o.kind} ${o.case.voice} step ${o.case.step} ${o.case.corner}: ${e(o.seam)}, with no vibrato ${e(plain)}")
        }
        val failed = outcomes.filter { it.seam >= Keys.MAX_SEAM_ERROR || !it.finite }
        assertTrue(failed.isEmpty(), failed.joinToString("\n") { "${it.kind} ${it.case.voice} step ${it.case.step} ${it.case.corner}: seam ${it.seam}, finite ${it.finite}" })
    }

    /**
     * What the vibrato costs a LOOP's render: R1's loop and the shipped one at CELLO C3 and ERHU C5 on this thread, taken in turn five times and the
     * fastest of each kept. The time is this thread's CPU time, not the wall clock. The build runs as many test JVMs as there are CPUs, and their
     * parallel renders take the CPU from this thread at moments of their own: the wall clock counts that wait and the thread's CPU time does not. On
     * CI on 2026-11-06 the wall clock read R1's loop at 188 ms and the shipped one at 545, where an idle machine reads them alike. Taking the two in
     * turn puts what slowing is left (a busy neighbouring core, a clock step) on both alike, and noise only ever adds time, so the fastest run is the
     * truest. The humanised swing is a handful of sines a raw sample, at 176.4 kHz over the whole stretch; the bound (the shipped loop in at most 2.5
     * times R1's) is there to catch a cost that runs away, not to set one.
     */
    @Test
    fun `what the loop vibrato costs a render`() {
        val threads = ManagementFactory.getThreadMXBean()
        val cpu = threads.isCurrentThreadCpuTimeSupported && threads.isThreadCpuTimeEnabled
        fun now(): Long = if (cpu) threads.currentThreadCpuTime else System.nanoTime()
        for ((voice, tune) in listOf(ArcoVoice.CELLO to 0.5f, ArcoVoice.ERHU to 0.5f)) {
            val m = Arco.defaults(voice) + mapOf("TUNE" to tune, "HOLD" to 1f)
            fun millis(kind: Arco.LoopVibrato): Long {
                val t0 = now()
                Arco.renderLoopMeasured(voice, m, loopVibrato = kind)
                return (now() - t0) / 1_000_000L
            }
            Arco.renderLoopMeasured(voice, m)
            val runs = (0 until 5).map { millis(Arco.LoopVibrato.OFF) to millis(Arco.LoopVibrato.HUMAN) }
            val off = runs.minOf { it.first }
            val human = runs.minOf { it.second }
            val clock = if (cpu) "this thread's CPU time" else "the wall clock, no thread CPU time here"
            println("ARCO loop vibrato cost $voice midi ${Arco.midiFor(voice, tune)}: R1's loop $off ms, the shipped loop $human ms (the fastest of five taken in turn, $clock); every run $runs")
            assertTrue(human <= 2.5 * off + 50, "$voice: the shipped loop takes $human ms against R1's $off")
        }
    }

    /** Goertzel's power at DFT bin [k] of [x]. */
    private fun binPower(x: FloatArray, k: Int): Double {
        val c = 2.0 * cos(2.0 * PI * k / x.size)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s = v + c * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }

    /**
     * The loop's mean pitch, in cents from its planned fundamental: the power-weighted centre of the DFT bins within four vibrato spacings of the
     * fundamental's bin (the loop holds K whole periods, so with no vibrato the fundamental is bin K alone; the vibrato spreads it into sidebands
     * around K, and their power-weighted centre is the mean of the swing). One loop is one period of a periodic wave, so the bins are exact.
     */
    private fun meanPitchCents(loop: FloatArray, periods: Int, cycles: Int): Double {
        var weighted = 0.0
        var power = 0.0
        for (b in maxOf(1, periods - 4 * cycles)..(periods + 4 * cycles)) {
            val p = binPower(loop, b)
            weighted += b * p
            power += p
        }
        return 1200.0 * ln(weighted / power / periods) / ln(2.0)
    }

    @Test
    fun `the loop's mean pitch is its note, with and without the vibrato`() {
        for (voice in ArcoVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val hz = Arco.frequencyFor(voice, tune)
            val plan = Arco.planLoop(hz)
            val cycles = Arco.loopVibratoCycles(plan.frames.toDouble() / Dsp.RATE)
            val m = Arco.defaults(voice) + mapOf("TUNE" to tune, "HOLD" to 1f)
            val cents = listOf(Arco.LoopVibrato.OFF, Arco.LoopVibrato.SNAPPED, Arco.LoopVibrato.HUMAN).associateWith { kind ->
                meanPitchCents(Arco.renderLoopMeasured(voice, m, loopVibrato = kind).loop, plan.periods, cycles)
            }
            val planned = 1200.0 * ln(plan.periods * Dsp.RATE.toDouble() / (plan.frames * hz)) / ln(2.0)
            println("ARCO loop vibrato mean pitch $voice midi ${Arco.midiFor(voice, tune)}: the plan is ${f(planned)} c from the note; the loop's mean against the plan, " + cents.entries.joinToString(", ") { "${it.key} ${f(it.value)} c" })
            for ((kind, c) in cents) assertTrue(abs(c) < 1.0, "$voice tune $tune $kind: the loop's mean pitch is $c cents from its plan")
        }
    }
}
