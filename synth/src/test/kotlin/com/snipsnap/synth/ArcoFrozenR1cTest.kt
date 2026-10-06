package com.snipsnap.synth

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The control. Before the engine step ARCO's BODY above the middle of the knob (0.5) climbed the box to 1.75 times the string (R1c), so a copy of that pipeline equalled the
 * engine at BODY 0.75 and 1.0, and this constant was `false`, which made the test assert they are EQUAL. The engine step ("lift on the plain", R1g) changes
 * everything above the knee, and when it landed this constant was flipped to `true`, and the test now asserts every compared cell DIFFERS. That one word is the
 * only edit the frozen pipeline's own comparison was given. R1g saw it `false` with 0 differing samples in every above-the-knee cell before the engine step, and `true` after it, with every one of those cells differing: it is `true` now.
 */
private const val ABOVE_KNEE_DIFFERS = true

/**
 * A frozen, never-edited, self-contained copy of how ARCO renders TODAY (R1c's box, the tree at 316bfe9 plus PR 437), compared sample for sample, bit for bit,
 * with the engine ([Arco.render] for one-shots, [Arco.renderLoopMeasured] for loops). Nothing here may be "fixed" or re-pointed: the frozen pipeline (the `Frozen` object), its comparison helpers and the four tests that first stood on it are never edited; the only
 * edits they were given are [ABOVE_KNEE_DIFFERS], and, since R1g's review, new test methods at the end of the class that call the frozen copy for every macro and not only TUNE and BODY.
 *
 * **What it freezes.** The box (CELLO's two modes at 104 Hz and 220 Hz, ERHU's membrane table anchored at 293.66 Hz with decays scaled by 0.25), the knob's curve
 * (the box is the knob's own value up to BODY 0.5 and climbs to 1.75 times the string at BODY 1), the cut to the string's length, the output chain (the band limit, the
 * decimation to 44.1 kHz, the mean taken off, the 20 Hz high-pass, [Dsp.levelTo] to [Dsp.MELODIC_LOUDNESS_TARGET] with its 0.99 ceiling, the 4 ms [Dsp.fadeTail]) and the
 * loop lines (the stretch's passes, the seam read, the cut, the rotation, one [Dsp.levelTo] and no fade). It is built only from pieces the engine step does not change:
 * [Arco.bow] and [Arco.stretch] (the bow itself, called and not copied: BODY is never read there), [Arco.planLoop], [Arco.settled], [Arco.frequencyFor], [Strings.bodyRing],
 * [Modes], [Tide.bandLimit], [Dsp], [Keys.seamError], [Siren.bestCut] and [Bore.measureLoopSamples]. It calls none of `Arco.withBody`, `Arco.boxAmountFor`, `Arco.finish`, `Arco.bodyFor`
 * or the BODY constants, which are the names the engine step changes or retires; every constant it needs is copied here as a literal.
 *
 * **Why.** The owner approved the sound of ARCO at and under the middle of the knob (the default, BODY 0.5, is "THE PLAIN ONE" the later pages are judged against): the
 * default, kit pads A01 to A08, A11 and A16, and the seven presets at or under 0.5 must come out of the engine step to the very sample. A test written against the new engine
 * could drift with it and say nothing, so this one reads a copy that cannot move. At BODY 0, 0.25 and 0.5 the engine must equal it (every TUNE step of both voices, 25 CELLO plus
 * 20 ERHU, for one-shots; seventeen steps for loops, with HOLD at its top step). Above the knee the engine now differs from the frozen copy (ABOVE_KNEE_DIFFERS is true; before the engine step the two were equal).
 *
 * **What it cannot see.** A later change to the bow itself ([Arco.bow], [Arco.stretch]) moves both sides together; `ArcoProductTest`'s pins on the bow are what watch that. This file also
 * says nothing about how anything sounds: nobody has listened to this build, and a sample-for-sample match is a statement about the numbers only.
 *
 * It has controls that can fail: the comparison counts one flipped bit and a changed length as differences, BODY below the knee is not ignored by it
 * (the frozen render at 0.5 differs from the engine's at 0.25 and at 0), and the frozen box curve is R1c's (0.5 at 0.5, 1.125 at 0.75, 1.75 at 1) so a frozen copy that had quietly
 * lost the climb would be caught before it could vouch for the engine.
 *
 * R1g saw, on the untouched checkout (before any engine edit), 0 differing samples in every cell at all five BODY values: one-shots 1468000 samples over the 25 CELLO notes and 994977 over the 20 ERHU notes per BODY value,
 * loops 794674 samples over nine CELLO steps and 705904 over eight ERHU steps per BODY value, every seam reading the same double. The bound is 0 differing samples (a bit pattern comparison, so a signed zero
 * or a last-place rounding difference counts). The ARCO lines of the class print one row per kind, voice and BODY.
 */
class ArcoFrozenR1cTest {

    // ---- R1c's pipeline, frozen ----------------------------------------------------------------------------

    private object Frozen {
        // CELLO's box: an air resonance at 104 Hz and a plate resonance at 220 Hz. Listening values, copied as they were.
        private val CELLO_BODY = listOf(
            Modes.fixed(104f, 1.0f, 0.254f),
            Modes.fixed(220f, 0.8f, 0.180f),
        )

        // ERHU's box: the house membrane's mode ratios on 293.66 Hz, the table's decays scaled by 0.25. Listening values, copied as they were.
        private const val ERHU_BODY_ANCHOR_HZ = 293.66f
        private const val ERHU_BODY_T60_SCALE = 0.25f
        private val ERHU_BODY: List<Modes.Mode> by lazy {
            Modes.tableFor(Modes.Material.MEMBRANE).map {
                Modes.fixed(ERHU_BODY_ANCHOR_HZ * it.ratio, it.gain, it.t60 * ERHU_BODY_T60_SCALE)
            }
        }

        private fun bodyFor(voice: ArcoVoice): List<Modes.Mode> = when (voice) {
            ArcoVoice.CELLO -> CELLO_BODY
            ArcoVoice.ERHU -> ERHU_BODY
        }

        // R1c's knob: the box is the knob's own value up to the knee and climbs to BODY_TOP times the string at BODY 1.
        const val BODY_KNEE = 0.5f
        const val BODY_TOP = 1.75f
        private const val BODY_CEILING_SECONDS = 8f

        fun boxAmountFor(body: Float): Float =
            if (body <= BODY_KNEE) body else BODY_KNEE + (body - BODY_KNEE) * (BODY_TOP - BODY_KNEE) / (1f - BODY_KNEE)

        // The string through the box at the oversampled rate, cut to the string's own length.
        fun withBody(raw: FloatArray, voice: ArcoVoice, amount: Float, rate: Int): FloatArray {
            val rung = Strings.bodyRing(raw, bodyFor(voice), boxAmountFor(amount), rate, BODY_CEILING_SECONDS)
            return if (rung.size == raw.size) rung else rung.copyOf(raw.size)
        }

        private const val OUTPUT_DC_HZ = 20f

        // The band limit, the decimation to 44.1 kHz, the mean off, the 20 Hz high-pass. In place on [raw].
        fun condition(raw: FloatArray, rate: Int): FloatArray {
            Tide.bandLimit(raw, rate)
            val out = Dsp.decimate(raw, Dsp.RATE)
            var mean = 0.0
            for (v in out) mean += v
            val m = (mean / out.size.coerceAtLeast(1)).toFloat()
            val hp = Dsp.OnePole(Dsp.RATE)
            for (i in out.indices) {
                val x = out[i] - m
                out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
            }
            return out
        }

        // The finish of a one-shot: condition, the level, the 4 ms fade.
        fun finish(raw: FloatArray, rate: Int): FloatArray {
            val out = condition(raw, rate)
            Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(out)
            return out
        }

        // The one-shot: the bow, the box, the finish.
        fun render(voice: ArcoVoice, macros: Map<String, Float>): FloatArray {
            val m = Arco.settled(macros, voice)
            val hz = Arco.frequencyFor(voice, m.getValue("TUNE"))
            val rate = Dsp.RATE * Dsp.OVERSAMPLE
            val raw = Arco.bow(voice, hz, m, rate)
            return finish(withBody(raw, voice, m.getValue("BODY"), rate), rate)
        }

        // The loop's own constants, copied as they were.
        private const val LOOP_WARMUP_SECONDS = 2.0f
        private const val LOOP_WARMUP_PERIODS = 200
        private const val LOOP_PASSES = 5
        private const val LOOP_CONVERGED = 3e-7
        private const val SEAM_FRAMES = 256

        class Loop(val loop: FloatArray, val seam: Double)

        // The loop: the steady stretch, corrected until its periods fill its frames, then boxed, conditioned, read for its seam, cut, rotated and levelled once (no fade).
        fun renderLoopMeasured(voice: ArcoVoice, macros: Map<String, Float>): Loop {
            val m = Arco.settled(macros, voice)
            val target = Arco.frequencyFor(voice, m.getValue("TUNE"))
            val plan = Arco.planLoop(target)
            val over = Dsp.OVERSAMPLE
            val rate = Dsp.RATE * over
            val warm = Math.round(max(LOOP_WARMUP_SECONDS, LOOP_WARMUP_PERIODS / target) * Dsp.RATE)
            val wanted = plan.frames.toDouble() * over
            var tuned = target.toDouble()
            var raw = Arco.stretch(voice, m, tuned.toFloat(), warm, plan.frames)
            for (pass in 1..LOOP_PASSES) {
                val ratio = Bore.measureLoopSamples(raw, warm * over, wanted, plan.periods) / wanted
                if (abs(ratio - 1.0) < LOOP_CONVERGED) break
                tuned *= ratio
                raw = Arco.stretch(voice, m, tuned.toFloat(), warm, plan.frames)
            }
            val conditioned = condition(withBody(raw, voice, m.getValue("BODY"), rate), rate)
            val seam = Keys.seamError(conditioned.copyOfRange(warm, warm + plan.frames + SEAM_FRAMES), SEAM_FRAMES)
            val one = conditioned.copyOfRange(warm, warm + plan.frames)
            val cut = Siren.bestCut(one, plan.frames)
            val loop = one.copyOfRange(cut, plan.frames) + one.copyOfRange(0, cut)
            Dsp.levelTo(loop, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            return Loop(loop, seam)
        }
    }

    // ---- the comparison ------------------------------------------------------------------------------------

    private class Cell(val voice: ArcoVoice, val step: Int, val body: Float)

    /** One compared cell: how many samples were looked at, how many differ (a length difference counts every sample it adds or takes), the largest difference, and for a loop whether the seam readings are the same double. */
    private class Row(val cell: Cell, val samples: Int, val differing: Int, val maxAbs: Float, val seamSame: Boolean) {
        val exact get() = differing == 0 && seamSame
    }

    // The bit patterns are compared, not the values: -0.0 against 0.0 and a NaN count as differences, so a "match" is a match to the last bit.
    private fun diff(a: FloatArray, b: FloatArray): Triple<Int, Int, Float> {
        val n = minOf(a.size, b.size)
        var differing = abs(a.size - b.size)
        var maxAbs = 0f
        for (i in 0 until n) {
            if (a[i].toRawBits() != b[i].toRawBits()) {
                differing++
                val d = abs(a[i] - b[i])
                if (d > maxAbs) maxAbs = d
            }
        }
        return Triple(max(a.size, b.size), differing, maxAbs)
    }

    private fun macrosFor(c: Cell, loop: Boolean): Map<String, Float> {
        val n = Arco.tuneSemitones(c.voice)
        val base = Arco.defaults(c.voice) + mapOf("TUNE" to c.step / n.toFloat(), "BODY" to c.body)
        return if (loop) base + mapOf("HOLD" to 1f) else base
    }

    private fun oneShotRow(c: Cell): Row {
        val macros = macrosFor(c, loop = false)
        val engine = Arco.render(c.voice, macros)
        assertEquals(1, engine.channels, "${c.voice} step ${c.step} BODY ${c.body}: not mono")
        assertEquals(Dsp.RATE, engine.sampleRate, "${c.voice} step ${c.step} BODY ${c.body}: not at the house rate")
        val (samples, differing, maxAbs) = diff(engine.samples, Frozen.render(c.voice, macros))
        return Row(c, samples, differing, maxAbs, seamSame = true)
    }

    private fun loopRow(c: Cell): Row {
        val macros = macrosFor(c, loop = true)
        val engine = Arco.renderLoopMeasured(c.voice, macros)
        val frozen = Frozen.renderLoopMeasured(c.voice, macros)
        val (samples, differing, maxAbs) = diff(engine.loop, frozen.loop)
        return Row(c, samples, differing, maxAbs, seamSame = engine.seam.toRawBits() == frozen.seam.toRawBits())
    }

    /** All 45 TUNE steps: 25 of CELLO and 20 of ERHU. */
    private fun oneShotCells(bodies: List<Float>): List<Cell> =
        ArcoVoice.entries.flatMap { v -> (0..Arco.tuneSemitones(v)).flatMap { s -> bodies.map { Cell(v, s, it) } } }

    /** Every third TUNE step and the top one: 9 of CELLO and 8 of ERHU (at least four a voice), from the lowest note, where a loop is hardest to close, to the highest. */
    private fun loopSteps(v: ArcoVoice): List<Int> = ((0..Arco.tuneSemitones(v) step 3) + Arco.tuneSemitones(v)).distinct()

    private fun loopCells(bodies: List<Float>): List<Cell> =
        ArcoVoice.entries.flatMap { v -> loopSteps(v).flatMap { s -> bodies.map { Cell(v, s, it) } } }

    private fun List<Cell>.parRows(row: (Cell) -> Row): List<Row> = parallelStream().map { row(it) }.toList()

    private fun f(x: Float): String = "%.3e".format(Locale.ROOT, x)

    private fun print(kind: String, rows: List<Row>) {
        for (voice in ArcoVoice.entries) for (body in rows.map { it.cell.body }.distinct()) {
            val mine = rows.filter { it.cell.voice == voice && it.cell.body == body }
            if (mine.isEmpty()) continue
            println(
                "ARCO frozen R1c $kind $voice BODY ${"%.2f".format(Locale.ROOT, body)}: ${mine.size} notes, ${mine.sumOf { it.samples.toLong() }} samples compared, " +
                    "${mine.sumOf { it.differing.toLong() }} differ in ${mine.count { !it.exact }} notes, largest difference ${f(mine.maxOf { it.maxAbs })}",
            )
        }
    }

    private fun assertAllExact(kind: String, rows: List<Row>) {
        for (r in rows) {
            val where = "$kind ${r.cell.voice} TUNE step ${r.cell.step} BODY ${r.cell.body}"
            assertTrue(r.samples > 0, "$where: nothing was compared")
            assertEquals(0, r.differing, "$where: ${r.differing} of ${r.samples} samples differ from the frozen R1c pipeline (largest ${f(r.maxAbs)}): the sound at or under the middle of the knob moved")
            assertTrue(r.seamSame, "$where: the seam reading differs from the frozen R1c pipeline's")
        }
    }

    // ---- the tests -------------------------------------------------------------------------------------------

    /**
     * Today's finished one-shot at BODY 0, 0.25 and 0.5 is the engine's, to the bit: all 25 CELLO and 20 ERHU TUNE steps, at the default BOW, GRIP and HOLD. R1g saw 0 differing samples in all 135 cells (1468000 CELLO and 994977 ERHU samples a BODY value); the bound is 0.
     */
    @Test
    fun `one-shots at or under the knee are the frozen copy's, sample for sample, at every TUNE step`() {
        val rows = oneShotCells(listOf(0f, 0.25f, 0.5f)).parRows(::oneShotRow)
        print("one-shot", rows)
        assertEquals(3 * (25 + 20), rows.size, "the grid is every TUNE step of both voices at three BODY values")
        assertAllExact("one-shot", rows)
    }

    /**
     * Today's finished loop (HOLD at its top step) at BODY 0, 0.25 and 0.5 is the engine's, to the bit, with the same seam reading: nine CELLO steps and eight ERHU steps. R1g saw 0 differing samples and the same seam reading in all 51 loops (794674 CELLO and 705904 ERHU samples a BODY value); the bound is 0.
     * One of them also goes through [Arco.render], the public route, so the loop's path from the macros to the samples is the one compared.
     */
    @Test
    fun `loops at or under the knee are the frozen copy's, sample for sample, with the same seam`() {
        val cells = loopCells(listOf(0f, 0.25f, 0.5f))
        val rows = cells.parRows(::loopRow)
        print("loop", rows)
        assertEquals(3 * (9 + 8), rows.size, "the grid is nine CELLO steps and eight ERHU steps at three BODY values")
        assertAllExact("loop", rows)
        for (voice in ArcoVoice.entries) for (body in listOf(0f, 0.25f, 0.5f)) {
            val c = Cell(voice, loopSteps(voice).first(), body)
            val macros = macrosFor(c, loop = true)
            val (samples, differing, _) = diff(Arco.render(voice, macros).samples, Frozen.renderLoopMeasured(voice, macros).loop)
            println("ARCO frozen R1c loop through Arco.render $voice BODY ${"%.2f".format(Locale.ROOT, body)} step ${c.step}: $samples samples compared, $differing differ")
            assertEquals(0, differing, "Arco.render of a loop, $voice BODY $body: $differing samples differ from the frozen loop")
        }
    }

    /**
     * Above the knee (BODY 0.75 and 1.0), one-shots at all 45 TUNE steps and loops at the same seventeen steps: before the engine step the frozen copy was the engine's ([ABOVE_KNEE_DIFFERS] was false, and the test asserted them equal); the engine step
     * flipped the constant to true and every one of these cells must now DIFFER, which is the proof that the engine step moved what it was meant to move and that this file can fail.
     * R1g saw, before the engine step, 0 differing samples in all 90 one-shots and all 34 loops at both BODY values, and after it the constant `true` with at least one differing sample in every cell; the bound is 0 differing while the constant is false and at least one differing sample in every cell while it is true (it is true).
     */
    @Test
    fun `above the knee the frozen copy equals the engine today, and must differ after the engine step`() {
        val bodies = listOf(0.75f, 1f)
        val shots = oneShotCells(bodies).parRows(::oneShotRow)
        val loops = loopCells(bodies).parRows(::loopRow)
        print("one-shot", shots)
        print("loop", loops)
        println("ARCO frozen R1c control: above-the-knee cells are expected to ${if (ABOVE_KNEE_DIFFERS) "DIFFER" else "EQUAL"} (ABOVE_KNEE_DIFFERS = $ABOVE_KNEE_DIFFERS)")
        if (ABOVE_KNEE_DIFFERS) {
            for ((kind, rows) in listOf("one-shot" to shots, "loop" to loops)) for (r in rows) {
                assertTrue(
                    r.differing > 0 || !r.seamSame,
                    "$kind ${r.cell.voice} TUNE step ${r.cell.step} BODY ${r.cell.body}: the engine still equals the frozen R1c pipeline above the knee, so the engine step did not reach this cell",
                )
            }
        } else {
            assertAllExact("one-shot above the knee", shots)
            assertAllExact("loop above the knee", loops)
        }
    }

    /**
     * The controls of the comparison itself: a single flipped last bit and a changed length are counted as differences; the frozen render at BODY 0.5 differs from the engine's at BODY 0.25 and at BODY 0 (BODY
     * below the knee is not ignored, so an "equal" above means something); and the frozen box curve is R1c's. R1g saw 1 differing sample for the flipped bit, 1 for the signed zero, 5 for the truncated length, and both lower BODY values differing from the frozen BODY 0.5
     * at CELLO C3 (step 12: 58719 samples, largest 0.119 at BODY 0.25 and 0.248 at BODY 0) and ERHU B4 (step 9: 48778 samples, largest 0.092 and 0.190); the bound is exactly the first three and at least one differing sample for the rest.
     */
    @Test
    fun `the comparison can fail, BODY below the knee is not ignored, and the frozen curve is R1c's`() {
        val a = Arco.render(ArcoVoice.CELLO, Arco.defaults(ArcoVoice.CELLO) + mapOf("TUNE" to 0.5f)).samples
        val flipped = a.copyOf()
        flipped[flipped.size / 2] = Float.fromBits(flipped[flipped.size / 2].toRawBits() xor 1)
        assertEquals(0, diff(a, a.copyOf()).second, "an array against its own copy")
        assertEquals(1, diff(a, flipped).second, "one flipped last bit")
        assertEquals(5, diff(a, a.copyOf(a.size - 5)).second, "five samples short")
        val negZero = floatArrayOf(0f)
        assertEquals(1, diff(negZero, floatArrayOf(-0f)).second, "a signed zero is a different bit pattern")

        for (voice in ArcoVoice.entries) {
            val step = Arco.tuneSemitones(voice) / 2
            val half = Cell(voice, step, 0.5f)
            val frozenHalf = Frozen.render(voice, macrosFor(half, loop = false))
            for (lower in listOf(0.25f, 0f)) {
                val engineLower = Arco.render(voice, macrosFor(Cell(voice, step, lower), loop = false)).samples
                val (_, differing, maxAbs) = diff(engineLower, frozenHalf)
                println("ARCO frozen R1c control $voice step $step: frozen BODY 0.5 against the engine's BODY ${"%.2f".format(Locale.ROOT, lower)}: $differing samples differ, largest ${f(maxAbs)}")
                assertTrue(differing > 0, "$voice: the frozen BODY 0.5 render equals the engine's BODY $lower render, so the comparison cannot see BODY below the knee")
            }
        }

        assertEquals(0f, Frozen.boxAmountFor(0f))
        assertEquals(0.25f, Frozen.boxAmountFor(0.25f))
        assertEquals(0.5f, Frozen.boxAmountFor(0.5f))
        assertEquals(1.125f, Frozen.boxAmountFor(0.75f), 1e-6f)
        assertEquals(1.75f, Frozen.boxAmountFor(1f), 1e-6f)
    }

    // ---- every macro, not only TUNE and BODY (R1g's review, P2) --------------------------------------------------
    // The four tests above vary TUNE and BODY at the default BOW, GRIP and HOLD. These vary the rest. They call the frozen copy as it is; none of them edits it.

    /** One macro set both sides are given, named for the failure message. HOLD at its top step makes it a loop, which [Arco.render] and the frozen copy both choose by. */
    private class MacroCase(val voice: ArcoVoice, val label: String, val macros: Map<String, Float>) {
        val loop: Boolean = Arco.isLoop(Arco.settled(macros, voice).getValue("HOLD"))
        val body: Float = Arco.settled(macros, voice).getValue("BODY")
    }

    private fun macroCase(voice: ArcoVoice, label: String, vararg set: Pair<String, Float>) = MacroCase(voice, "$voice $label", Arco.defaults(voice) + set.toMap())

    private class MacroRow(val case: MacroCase, val samples: Int, val differing: Int, val maxAbs: Float, val seamSame: Boolean) {
        val exact get() = differing == 0 && seamSame
    }

    /**
     * The engine against the frozen copy for one macro set. A one-shot goes through [Arco.render]. A loop goes through [Arco.renderLoopMeasured] (with the seam reading compared too), and, when [publicRoute] is true, also through [Arco.render],
     * which for HOLD at its top step is the loop (the differing samples of the two are added).
     */
    private fun macroRow(c: MacroCase, publicRoute: Boolean = false): MacroRow {
        if (!c.loop) {
            val (samples, differing, maxAbs) = diff(Arco.render(c.voice, c.macros).samples, Frozen.render(c.voice, c.macros))
            return MacroRow(c, samples, differing, maxAbs, seamSame = true)
        }
        val frozen = Frozen.renderLoopMeasured(c.voice, c.macros)
        val engine = Arco.renderLoopMeasured(c.voice, c.macros)
        val (samples, differing, maxAbs) = diff(engine.loop, frozen.loop)
        val viaRender = if (publicRoute) diff(Arco.render(c.voice, c.macros).samples, frozen.loop).second else 0
        return MacroRow(c, samples, differing + viaRender, maxAbs, seamSame = engine.seam.toRawBits() == frozen.seam.toRawBits())
    }

    private fun printMacroRows(kind: String, rows: List<MacroRow>) {
        for (voice in ArcoVoice.entries) for (loop in listOf(false, true)) for (body in rows.map { it.case.body }.distinct().sorted()) {
            val mine = rows.filter { it.case.voice == voice && it.case.loop == loop && it.case.body == body }
            if (mine.isEmpty()) continue
            println(
                "ARCO frozen R1c $kind ${if (loop) "loop" else "one-shot"} $voice BODY ${"%.2f".format(Locale.ROOT, body)}: ${mine.size} macro sets, ${mine.sumOf { it.samples.toLong() }} samples compared, " +
                    "${mine.sumOf { it.differing.toLong() }} differ in ${mine.count { !it.exact }} sets, largest difference ${f(mine.maxOf { it.maxAbs })}",
            )
        }
    }

    private fun assertMacroRowsExact(rows: List<MacroRow>) {
        for (r in rows) {
            assertTrue(r.samples > 0, "${r.case.label}: nothing was compared")
            assertEquals(0, r.differing, "${r.case.label}: ${r.differing} of ${r.samples} samples differ from the frozen R1c pipeline (largest ${f(r.maxAbs)}): the sound at or under the middle of the knob moved")
            assertTrue(r.seamSame, "${r.case.label}: the seam reading differs from the frozen R1c pipeline's")
        }
    }

    /**
     * The macros the four tests above leave at their defaults. TUNE at its lowest, its default and its highest step, with BOW, GRIP and HOLD each at 0 and 1 (the sixteen corners of TUNE, BOW, GRIP and HOLD are the lowest and the highest TUNE; the default note, the one a
     * preset or a pad most often sits near, adds its eight more), at BODY 0 and 0.5, both voices: 96 macro sets, the 48 with HOLD 0 as one-shots and the 48 with HOLD 1 as loops (HOLD at its top step is a loop). The review that asked for this (P2) probed 601 cells against a
     * copy of the untouched tree and saw 0 differing; R1g saw 0 differing samples here in every one of the 96 sets (264600 samples a voice and BODY value in the one-shots, 1059912 CELLO and 1058972 ERHU in the loops), every loop seam reading the same double. The bound is 0 differing samples and the same seam. It can fail: [the macro comparisons can fail] shows each macro is seen.
     */
    @Test
    fun `at the corners of TUNE, BOW, GRIP and HOLD, at BODY 0 and 0 point 5, both voices are the frozen copy's, one-shots and loops`() {
        val cases = ArcoVoice.entries.flatMap { v ->
            val tunes = listOf("lowest" to 0f, "default" to Arco.defaults(v).getValue("TUNE"), "highest" to 1f)
            tunes.flatMap { (tuneName, t) ->
                listOf(0f, 1f).flatMap { bow ->
                    listOf(0f, 1f).flatMap { grip ->
                        listOf(0f, 1f).flatMap { hold ->
                            listOf(0f, 0.5f).map { body ->
                                macroCase(v, "TUNE $tuneName BOW $bow GRIP $grip HOLD $hold BODY $body", "TUNE" to t, "BOW" to bow, "GRIP" to grip, "HOLD" to hold, "BODY" to body)
                            }
                        }
                    }
                }
            }
        }
        val rows = cases.parallelStream().map { macroRow(it) }.toList()
        printMacroRows("corners", rows)
        assertEquals(2 * 3 * 2 * 2 * 2 * 2, rows.size, "two voices, three TUNE steps, BOW, GRIP and HOLD at 0 and 1, two BODY values")
        assertEquals(48, rows.count { !it.case.loop }, "the HOLD 0 sets are one-shots")
        assertEquals(48, rows.count { it.case.loop }, "the HOLD 1 sets are loops")
        assertMacroRowsExact(rows)
    }

    /**
     * The seven presets at or under BODY 0.5 (CELLO's SLOW BOW, SHORT STAB, GRIT BOW, DRY SCRAPE and HORSEHAIR, ERHU's THIN SCRAPE and ENDLESS CRY, the last a loop) are the frozen copy's, to the sample, at their own macros: the roster's sound that must come out of the engine step untouched.
     * The roster is checked to be exactly those seven (the presets whose BODY is at or under 0.5), so a preset that moved above the knee fails here by name instead of dropping out of the comparison. Compared through [Arco.render], the route a pad takes, and the loop also through [Arco.renderLoopMeasured].
     * R1g saw 0 differing samples in all seven; the bound is 0 differing samples and the same seam.
     */
    @Test
    fun `the seven presets at or under BODY 0 point 5 are the frozen copy's, ENDLESS CRY as a loop`() {
        val named = setOf("SLOW BOW", "SHORT STAB", "GRIT BOW", "DRY SCRAPE", "HORSEHAIR", "THIN SCRAPE", "ENDLESS CRY")
        val presets = ArcoPresets.all().filter { it.macros.getValue("BODY") <= Arco.DEFAULT_BODY }
        assertEquals(named, presets.map { it.name }.toSet(), "the presets at or under BODY 0.5 are not the seven the roster names: the comparison would skip one")
        val rows = presets.map { MacroCase(it.voice, "${it.voice} ${it.name}", it.macros) }.parallelStream().map { macroRow(it, publicRoute = true) }.toList()
        printMacroRows("presets", rows)
        for (r in rows) println("ARCO frozen R1c preset ${r.case.label}: ${if (r.case.loop) "loop" else "one-shot"}, ${r.samples} samples compared, ${r.differing} differ")
        assertEquals(7, rows.size)
        assertEquals(setOf("ENDLESS CRY"), rows.filter { it.case.loop }.map { it.case.label.removePrefix("${it.case.voice} ") }.toSet(), "exactly ENDLESS CRY is a loop")
        assertMacroRowsExact(rows)
    }

    /**
     * The controls of the macro comparisons: each of TUNE, BOW, GRIP and HOLD, moved one notch, makes the engine's render differ from the frozen copy's render of the unmoved macros, so a comparison that did not look at that macro would be caught. At CELLO and
     * ERHU's default note (BOW 0, GRIP 0, HOLD 0, BODY 0.5), TUNE to its highest step, BOW to 1, GRIP to 1 and HOLD to 0.5 each give a one-shot that differs; for a loop (HOLD 1) TUNE and GRIP do (BOW is printed and not asserted: a loop discards its stroke, and HOLD at its top step
     * only chooses the loop). R1g saw each asserted one-shot move differ in 22049 of 22050 samples (HOLD, which changes the length: all of them) and each asserted loop move differ in every sample (88326 CELLO, 88242 ERHU), and BOW moved in a loop in 0 samples, as its printed line says. The bound is at least one differing sample.
     */
    @Test
    fun `the macro comparisons can fail, each of TUNE, BOW, GRIP and HOLD is seen by them`() {
        for (voice in ArcoVoice.entries) {
            val tune = Arco.defaults(voice).getValue("TUNE")
            val one = macroCase(voice, "one-shot", "TUNE" to tune, "BOW" to 0f, "GRIP" to 0f, "HOLD" to 0f, "BODY" to 0.5f)
            val frozenOne = Frozen.render(voice, one.macros)
            for ((name, moved) in listOf("TUNE" to 1f, "BOW" to 1f, "GRIP" to 1f, "HOLD" to 0.5f)) {
                val (samples, differing, maxAbs) = diff(Arco.render(voice, one.macros + (name to moved)).samples, frozenOne)
                println("ARCO frozen R1c macro control $voice one-shot $name moved to $moved: $differing of $samples samples differ from the unmoved frozen render, largest ${f(maxAbs)}")
                assertTrue(differing > 0, "$voice one-shot: $name moved to $moved still equals the frozen render of the unmoved macros, so the comparison cannot see $name")
            }
            val held = macroCase(voice, "loop", "TUNE" to tune, "BOW" to 0f, "GRIP" to 0f, "HOLD" to 1f, "BODY" to 0.5f)
            val frozenLoop = Frozen.renderLoopMeasured(voice, held.macros).loop
            for ((name, moved, asserted) in listOf(Triple("TUNE", 1f, true), Triple("GRIP", 1f, true), Triple("BOW", 1f, false))) {
                val (samples, differing, maxAbs) = diff(Arco.renderLoopMeasured(voice, held.macros + (name to moved)).loop, frozenLoop)
                println("ARCO frozen R1c macro control $voice loop $name moved to $moved: $differing of $samples samples differ from the unmoved frozen loop, largest ${f(maxAbs)}${if (asserted) "" else " (printed, not asserted)"}")
                if (asserted) assertTrue(differing > 0, "$voice loop: $name moved to $moved still equals the frozen loop of the unmoved macros, so the comparison cannot see $name")
            }
        }
    }
}
