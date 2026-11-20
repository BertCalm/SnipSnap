package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.BoroughKits.Kit
import com.snipsnap.synth.BoroughKits.Stack
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BOROUGH's Kotlin pads measured against the Python pads the owner accepted (round 2), as EMBER's port was: every one
 * of the 88 pads (six kits' twelve drums, four stacks' four), hardest zone, take 0, through `hits.measure_hit`
 * ([BoroughMeasure]) and compared with `borough-parity.json`.
 *
 * A Kotlin take is another realisation of the recipe (Kotlin's Random, not numpy's), so it is held to the spread of
 * Python's own realisations: `borough-parity-spread.json` (prototype/borough/parity_spread.py) re-renders each pad's
 * recipe with eight fresh seeds in Python and scores each against the same reference. Measured at round 3: Kotlin's
 * distance is inside that spread for 81 of 88 pads, and the seven over it are over by at most 0.45 dB (docs/BOROUGH.md).
 *
 * Tolerances, each from that measurement:
 * - grid (fit_designed's RMS over windows x bands, -40 dB floor, its kick and snare masks): at most Python's own worst
 *   realisation of the pad plus [MARGIN_DB];
 * - no bias: Kotlin's distance minus Python's median distance, averaged over the 88 pads, within [BIAS_DB];
 * - kick pitch at 30 ms within a semitone (or one 2.7 Hz bin of the measure, a semitone at 45 Hz) of the reference or of
 *   one of Python's own realisations: STOOP's KICK reads its punch (97 Hz) or its boom (52 Hz) at 30 ms from seed to
 *   seed in Python itself;
 * - snare body within a semitone of the reference;
 * - length within 15% or 150 ms.
 */
class BoroughParityTest {
    private companion object {
        const val MARGIN_DB = 1.0
        const val BIAS_DB = 0.5
        const val BIN_HZ = 44_100.0 / 16384

        class Row(val key: String, val kind: Borough.Kind?, val grid: Double, val pyGrid: List<Double>, val hit: BoroughMeasure.Hit,
                  val ref: BoroughMeasure.Hit, val pyKick30: List<Double>, val seconds: Double, val refSeconds: Double,
                  val rmsDb: Double, val refRmsDb: Double)

        private fun res(name: String) = Json.parse(checkNotNull(BoroughParityTest::class.java.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader().use { it.readText() }).obj()

        private fun keyOf(scope: String, slot: Int, label: String) = "$scope/A%02d-%s".format(Locale.ROOT, slot, label.lowercase().replace(' ', '-'))

        val rows: List<Row> by lazy {
            val parity = res("/borough-parity.json").getValue("pads").obj()
            val spread = res("/borough-parity-spread.json").getValue("pads").obj()
            class Job(val key: String, val kind: Borough.Kind?, val render: () -> FloatArray)
            val jobs = Kit.entries.flatMap { kit ->
                (0 until BoroughKits.DRUM_PADS).map { role ->
                    val pad = kit.recipe.pads[role]
                    Job(keyOf(kit.id, role + 1, pad.label), pad.kind) { BoroughKits.renderDrum(kit, role, 2, 0).samples }
                }
            } + Stack.entries.flatMap { st ->
                (0 until BoroughKits.STACK_PADS).map { i -> Job(keyOf(st.id, 13 + i, st.recipe.pads[i].label), null) { BoroughKits.renderStack(st, i, 2, 0).samples } }
            }
            jobs.parallelStream().map { job ->
                val x = job.render()
                val p = parity.getValue(job.key).obj()
                val ref = BoroughMeasure.fromJson(p)
                val hit = BoroughMeasure.measure(DoubleArray(x.size) { x[it].toDouble() })
                val snare = job.kind == Borough.Kind.SNARE || job.kind == Borough.Kind.GHOST
                val sp = spread.getValue(job.key).obj()
                val n = minOf(x.size, 13230)
                Row(job.key, job.kind, BoroughMeasure.gridDb(ref, hit, snare), sp.getValue("grid_db").arr().map { it.num() }, hit, ref,
                    sp.getValue("kick30_hz").arr().map { it.num() }, x.size / 44_100.0, p.getValue("seconds").num(),
                    10 * log10((0 until n).sumOf { x[it].toDouble() * x[it] } / 13230), p.getValue("rms_db_300ms").num())
            }.toList()
        }
    }

    private fun median(v: List<Double>) = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    @Test fun `every pad is measured and the Python spread reproduced its own reference`() {
        assertEquals(88, rows.size)
        val spread = res("/borough-parity-spread.json").getValue("pads").obj()
        for ((key, v) in spread) assertTrue(v.obj().getValue("reproduced_grid_db").num() < 0.1, "$key: the Python harness no longer reproduces its reference")
        // The report the docs quote: written where a reader can find it, every run.
        val sb = StringBuilder("pad,kotlin_grid_db,python_min,python_median,python_max,kick30_hz,ref_kick30_hz,body_hz,ref_body_hz,seconds,ref_seconds,rms300_db,ref_rms300_db\n")
        for (r in rows) sb.append(String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%.1f,%.1f,%.1f,%.1f,%.3f,%.3f,%.2f,%.2f\n", r.key, r.grid,
            r.pyGrid.min(), median(r.pyGrid), r.pyGrid.max(), r.hit.kick30, r.ref.kick30, r.hit.bodyHz, r.ref.bodyHz, r.seconds, r.refSeconds, r.rmsDb, r.refRmsDb))
        File("build/reports").mkdirs()
        File("build/reports/borough-parity.csv").writeText(sb.toString())
        val over = rows.filter { it.grid > it.pyGrid.max() }
        println(String.format(Locale.ROOT, "BOROUGH parity: grid worst %.2f dB (%s), median %.2f dB; Python's own median %.2f dB; %d of %d pads inside Python's spread; over it: %s",
            rows.maxOf { it.grid }, rows.maxBy { it.grid }.key, median(rows.map { it.grid }), median(rows.map { median(it.pyGrid) }),
            rows.size - over.size, rows.size, over.joinToString { String.format(Locale.ROOT, "%s +%.2f", it.key, it.grid - it.pyGrid.max()) }))
    }

    @Test fun `every pad sits inside Python's own spread of realisations`() {
        val bad = rows.filter { it.grid > it.pyGrid.max() + MARGIN_DB }
        assertTrue(bad.isEmpty(), bad.joinToString("\n") { String.format(Locale.ROOT, "%s: %.2f dB, Python's realisations %.2f to %.2f", it.key, it.grid, it.pyGrid.min(), it.pyGrid.max()) })
    }

    @Test fun `the port has no bias against the prototype`() {
        val bias = rows.map { it.grid - median(it.pyGrid) }.average()
        assertTrue(abs(bias) <= BIAS_DB, "Kotlin's distance minus Python's median, averaged: %.2f dB".format(Locale.ROOT, bias))
    }

    @Test fun `the designed kicks keep their pitch at 30 ms`() {
        val kicks = rows.filter { it.kind == Borough.Kind.KICK }
        assertEquals(12, kicks.size)
        for (r in kicks) {
            fun close(hz: Double) = BoroughMeasure.absSemis(r.hit.kick30, hz) <= 1.0 || abs(r.hit.kick30 - hz) <= BIN_HZ + 1e-6
            assertTrue(close(r.ref.kick30) || r.pyKick30.any(::close),
                "${r.key}: 30 ms pitch ${r.hit.kick30} Hz, Python ${r.ref.kick30} Hz (its own realisations ${r.pyKick30})")
        }
    }

    @Test fun `the designed snares keep their body pitch`() {
        val snares = rows.filter { it.kind == Borough.Kind.SNARE || it.kind == Borough.Kind.GHOST }
        assertEquals(18, snares.size)
        for (r in snares) assertTrue(BoroughMeasure.absSemis(r.hit.bodyHz, r.ref.bodyHz) <= 1.0, "${r.key}: body ${r.hit.bodyHz} Hz, Python ${r.ref.bodyHz} Hz")
    }

    @Test fun `every pad is the length the prototype printed`() {
        for (r in rows) assertTrue(abs(r.seconds - r.refSeconds) <= max(0.15, 0.15 * r.refSeconds), "${r.key}: ${r.seconds} s, Python ${r.refSeconds} s")
    }
}
