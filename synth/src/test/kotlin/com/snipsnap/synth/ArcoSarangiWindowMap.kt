package com.snipsnap.synth

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SARANGI's GRIP window, drawn the way R1b drew CELLO's and ERHU's (the design record's "R1b, as built": "the dense map (3,330 cells) was read as
 * lock times"). R1b's own harness was never committed; ArcoTest's speaks test (every TUNE step by GRIP in tenths, 495 cells) is what stayed. This is
 * the map for the third voice's span, D3 to D5, and it is kept so the next person can redraw it: `./gradlew :synth:mapArcoSarangiWindow`.
 *
 * Three tables, all on the bare bow (BOW 0.5, so no bite; BODY 0; no vibrato; a 3.5 s bow-on, as the speaks test plays):
 *  1. **The island**: at every semitone from D3 to D5, the lock time of the string at a grid of pressures and bridge corners that GRIP could travel
 *     between ([PRESSURES] by [CORNERS], 60 cells a semitone, 1,500 in all). A cell speaks when it locks into one slip a period inside the bar
 *     ([LOCK_BAR]), its last slip is within 1.5 periods of the end of the bow-on, and its wave repeats (autocorrelation peak over 0.9): ArcoTest's
 *     judgement, the same three clauses.
 *  2. **The laws along GRIP**: each candidate law walked the way GRIP walks it (0 to 1 in tenths), the same judgement at each point.
 *  3. **GRIP as a pitch knob**: for each law, the note at GRIP 1 minus the note at GRIP 0, by FFT on the finished render (ArcoTest's read: a 3.2 s
 *     bow-on, the window from 2.0 s for 1.0 s), at every semitone. The bar is the record's 5 cents.
 *
 * At BOW 0.5 the bow has no bite, and with the pressure and the corner handed in, [Arco.bow] depends on the voice only for the stop after the bow-on,
 * which no table reads: so the map is the string's at SARANGI's pitches whichever voice carries the call. It is carried by SARANGI itself.
 */
internal object ArcoSarangiWindowMap {

    private val voice = ArcoVoice.SARANGI
    private val rate = ArcoMeasure.RATE

    val PRESSURES = listOf(0.85f, 0.88f, 0.91f, 0.94f, 0.97f, 1.0f)
    val CORNERS = listOf(1000f, 1150f, 1300f, 1500f, 1750f, 2000f, 2250f, 2500f, 2750f, Strings.Bow.BRIDGE_HZ)

    /** The bow-on of the island and the laws: ArcoTest's speaks table's. */
    const val BOW_ON = 3.5f

    /** The lock bar the map judges by: CELLO's is 1.2 s and ERHU's 0.5 s; SARANGI's span runs from CELLO's upper octave into ERHU's lower one. */
    const val LOCK_BAR = 0.8

    private fun f(v: Double, d: Int = 2) = "%.${d}f".format(Locale.ROOT, v)

    private fun noteName(midi: Int) = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12] + (midi / 12 - 1)

    class Cell(val lock: Double, val tail: Double, val peak: Double) {
        fun speaks(bar: Double = LOCK_BAR) = lock >= 0 && lock <= bar && tail <= 1.5 && peak > 0.9
        fun mark(bar: Double = LOCK_BAR): String = when {
            lock < 0 -> "  --"
            !speaks(bar) -> ((lock * 1000).roundToInt().toString() + "x").padStart(5).takeLast(5)
            else -> (lock * 1000).roundToInt().toString().padStart(4) + " "
        }
    }

    /** The bare bow at SARANGI's [step] with [pressure] and [corner] handed in: ArcoMeasure's core and verdict. */
    fun cell(step: Int, pressure: Float, corner: Float, bowOn: Float = BOW_ON): Cell {
        val c = ArcoMeasure.core(voice, step, bow = 0.5f, grip = 0f, pressure = pressure, cornerHz = corner, gateSeconds = bowOn)
        val v = ArcoMeasure.verdict(c)
        return Cell(v.lock, v.tailPeriods, v.peak)
    }

    /** A law: the pressure and the corner at GRIP 0 and 1 for a semitone of SARANGI's. */
    class Law(val name: String, val grip: (Int) -> Arco.Grip)

    /** ERHU's corner floor in absolute pitch (1500 Hz at D4, the full corner 21 semitones up) carried on down: about 1005 Hz at D3. */
    fun erhuLine(step: Int): Float = Arco.ERHU_CORNER_LOW_ROOT_HZ *
        Math.pow((Strings.Bow.BRIDGE_HZ / Arco.ERHU_CORNER_LOW_ROOT_HZ).toDouble(), ((step - 12).toDouble() / Arco.ERHU_CORNER_RISE_SEMITONES).coerceAtMost(1.0)).toFloat()

    val LAWS = listOf(
        Law("floor 0.94, ERHU's corner line") { s -> Arco.Grip(Arco.ERHU_PRESSURE_LOW, Arco.PRESSURE_HIGH, erhuLine(s), Strings.Bow.BRIDGE_HZ) },
        Law("floor 0.85, ERHU's corner line") { s -> Arco.Grip(Arco.PRESSURE_LOW, Arco.PRESSURE_HIGH, erhuLine(s), Strings.Bow.BRIDGE_HZ) },
        Law("floor 0.85, CELLO's 1000 Hz corner") { _ -> Arco.Grip(Arco.PRESSURE_LOW, Arco.PRESSURE_HIGH, Arco.CELLO_CORNER_LOW_HZ, Strings.Bow.BRIDGE_HZ) },
    )

    private fun pressureAt(g: Arco.Grip, grip: Float) = Dsp.lin(grip, g.pressureLow, g.pressureHigh)
    private fun cornerAt(g: Arco.Grip, grip: Float) = Dsp.expMap(grip, g.cornerLow, g.cornerHigh)

    /** The settled pitch at [step] for [pressure] and [corner], in cents, by FFT on the finished render (BODY 0), ArcoTest's CELLO read. */
    fun cents(step: Int, pressure: Float, corner: Float): Double {
        val c = ArcoMeasure.core(voice, step, bow = 0.5f, grip = 0f, pressure = pressure, cornerHz = corner, gateSeconds = 3.2f)
        return ArcoMeasure.pitchOf(c, 2.0f, 1.0f).fftCents
    }

    private fun <T, R> List<T>.pmap(op: (T) -> R): List<R> = parallelStream().map { op(it) }.toList()

    @JvmStatic
    fun main(args: Array<String>) {
        val out = StringBuilder()
        fun line(s: String) {
            println(s)
            out.append(s).append('\n')
        }
        val steps = 0..Arco.tuneSemitones(voice)
        val started = System.nanoTime()

        line("SARANGI GRIP window map, D3 to D5, BOW 0.5, BODY 0, no vibrato, bow-on $BOW_ON s. Lock ms; x: locks but fails the judgement (bar ${f(LOCK_BAR)} s, tail 1.5 periods, peak 0.9); --: never locks.")
        line("1. The island: pressure rows by corner columns at each semitone.")
        val island = steps.toList().flatMap { s -> PRESSURES.flatMap { p -> CORNERS.map { c -> Triple(s, p, c) } } }.pmap { (s, p, c) -> Triple(s, p, c) to cell(s, p, c) }
        val bySpot = island.toMap()
        line("   corner Hz:      " + CORNERS.joinToString("") { f(it.toDouble(), 0).padStart(5) })
        var speaking = 0
        for (s in steps) {
            val midi = Arco.rootMidi(voice) + s
            for (p in PRESSURES) {
                val row = CORNERS.map { c -> bySpot.getValue(Triple(s, p, c)) }
                speaking += row.count { it.speaks() }
                line("   ${noteName(midi).padEnd(3)} ${s.toString().padStart(2)} p ${f(p.toDouble())}: " + row.joinToString("") { it.mark() })
            }
        }
        line("   ${island.size} cells, $speaking speak.")
        // where the corner must sit: the lowest corner that speaks at every pressure from 0.94, per semitone
        for (s in steps) {
            val lowest = CORNERS.firstOrNull { c -> PRESSURES.filter { it >= 0.94f }.all { p -> bySpot.getValue(Triple(s, p, c)).speaks() } }
            val lowest85 = CORNERS.firstOrNull { c -> PRESSURES.all { p -> bySpot.getValue(Triple(s, p, c)).speaks() } }
            line("   ${noteName(Arco.rootMidi(voice) + s)}: the lowest corner that speaks at every pressure from 0.94: ${lowest?.let { f(it.toDouble(), 0) } ?: "none"}; " +
                "from 0.85: ${lowest85?.let { f(it.toDouble(), 0) } ?: "none"}; ERHU's line here ${f(erhuLine(s).toDouble(), 0)} Hz")
        }

        line("2. The laws along GRIP (0 to 1 in tenths): lock ms.")
        val grips = (0..10).map { it / 10f }
        for (law in LAWS) {
            val rows = steps.toList().pmap { s -> grips.map { g -> val w = law.grip(s); cell(s, pressureAt(w, g), cornerAt(w, g)) } }
            line("   ${law.name}:")
            var fails = 0
            var worst = 0.0
            for (s in steps) {
                val row = rows[s]
                fails += row.count { !it.speaks() }
                worst = maxOf(worst, row.maxOf { it.lock })
                line("     ${noteName(Arco.rootMidi(voice) + s).padEnd(3)} ${s.toString().padStart(2)}: " + row.joinToString("") { it.mark() })
            }
            line("     ${law.name}: ${rows.sumOf { it.size }} cells, $fails fail; slowest lock ${f(worst)} s")
        }

        line("3. GRIP as a pitch knob: cents at GRIP 0, at GRIP 1, and the travel, by FFT on the finished render.")
        for (law in LAWS.take(2)) {
            val rows = steps.toList().pmap { s ->
                val w = law.grip(s)
                Triple(s, cents(s, w.pressureLow, w.cornerLow), cents(s, w.pressureHigh, w.cornerHigh))
            }
            var worst = 0.0
            var worstAt = ""
            for ((s, lo, hi) in rows) {
                val travel = hi - lo
                if (abs(travel) > abs(worst)) { worst = travel; worstAt = noteName(Arco.rootMidi(voice) + s) }
                line("   ${law.name} ${noteName(Arco.rootMidi(voice) + s).padEnd(3)}: GRIP 0 ${f(lo)} c, GRIP 1 ${f(hi)} c, travel ${f(travel)} c" + if (abs(travel) > 5.0) "  OVER 5" else "")
            }
            line("   ${law.name}: worst travel ${f(worst)} c at $worstAt")
        }
        line("The map took ${f((System.nanoTime() - started) / 1e9, 1)} s.")
        if (args.isNotEmpty()) File(args[0]).also { it.parentFile?.mkdirs() }.writeText(out.toString())
    }
}
