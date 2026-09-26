package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The drone render against recipes nobody chose by hand: a seeded sweep (so
 * a failure reproduces) over voice, every sounding macro including its
 * exact ends, MOTION, BREATHS, any MIDI note a loop file could hold, any
 * tempo and bar count the grid accepts, and the device rates a phone
 * reports. Whatever comes in, what comes out is finite, under the peak
 * ceiling, at the melodic level (or just under, where the limiter caught a
 * resonant peak), and repeats exactly.
 */
class ResinDroneFuzzTest {

    private data class Case(
        val spec: ResinDrone.Spec,
        val root: Int,
        val frames: Long,
        val rate: Int,
        val label: String,
    )

    /** A macro value that lands on the exact ends a third of the time, where edge bugs live. */
    private fun knob(r: Random): Float = when (r.nextInt(6)) {
        0 -> 0f
        1 -> 1f
        else -> r.nextFloat()
    }

    private fun cases(seed: Int, count: Int): List<Case> {
        val r = Random(seed)
        val rates = listOf(22_050, 32_000, 44_100, 48_000, 96_000)
        return List(count) { n ->
            val voice = ResinVoice.entries[r.nextInt(ResinVoice.entries.size)]
            val macros = buildMap {
                for (k in listOf("STACK", "CUTOFF", "CREAM", "CONTOUR", "DECAY", "TUNE")) if (r.nextInt(5) > 0) put(k, knob(r))
                // A key no engine knows rides along now and then: it must be ignored, not trip anything.
                if (r.nextInt(8) == 0) put("WOBBLE", r.nextFloat())
            }
            val spec = ResinDrone.Spec(voice, macros, knob(r), ResinDrone.RATES[r.nextInt(ResinDrone.RATES.size)])
            // RESIN's register mostly, anywhere in MIDI sometimes: a loop file can hold any root.
            val root = if (r.nextInt(4) == 0) r.nextInt(128) else 33 + r.nextInt(49)
            val rate = rates[r.nextInt(rates.size)]
            val bpm = 40 + r.nextInt(181)
            val bars = listOf(1, 1, 1, 2, 4, 8)[r.nextInt(6)]
            val span = listOf(1, 1, 2, 4, 8)[r.nextInt(5)]
            val interval = (bars * 4 * (60.0 / bpm) * rate).roundToInt().toLong()
            // Kept under ~6 s of loop so the sweep stays a test, not a benchmark;
            // length itself is not what this is probing (ResinDroneTest owns that).
            var frames = interval * span
            while (frames > 6L * rate) frames /= 2
            Case(spec, root, frames, rate, "#$n $voice root $root ${rate}Hz ${frames}f $macros motion ${spec.motion} x${spec.rate}")
        }
    }

    @Test
    fun `any recipe renders finite, under the ceiling, and repeats exactly`() {
        val results = cases(seed = 20260925, count = 32).parallelStream().map { c ->
            val two = ResinDrone.synthesize(c.spec, c.root, c.frames, c.rate, periods = 2)
            val f = c.frames.toInt()
            var d = 0.0
            var e = 0.0
            var finite = true
            for (i in 0 until f) {
                val a = two[i].toDouble()
                val b = two[f + i].toDouble()
                if (!a.isFinite() || !b.isFinite()) finite = false
                d += (b - a) * (b - a)
                e += a * a
            }
            val out = ResinDrone.render(c.spec, c.root, c.frames, c.rate)
            val peak = out.maxOf { kotlin.math.abs(it) }
            val loud = Loudness.of(Snip(out, 1, c.rate))
            val db = 20 * log10(loud.toDouble() / Dsp.MELODIC_LOUDNESS_TARGET)
            Triple(c, doubleArrayOf(if (e > 0) d / e else 0.0, peak.toDouble(), db, e), finite && out.all { it.isFinite() })
        }.toList()

        val spread = results.map { it.second[2] }.sorted()
        println("FUZZ loudness dB vs target: min %.2f median %.2f max %.2f".format(spread.first(), spread[spread.size / 2], spread.last()))
        println("FUZZ worst period diff: %.1e".format(results.maxOf { it.second[0] }))

        val failures = results.mapNotNull { (c, m, finite) ->
            when {
                !finite -> "not finite: ${c.label}"
                m[3] <= 0.0 -> "silent: ${c.label}"
                m[0] >= 1e-12 -> "period diff ${m[0]}: ${c.label}"
                m[1] > 0.99 + 1e-6 -> "peak ${m[1]}: ${c.label}"
                // Levelled to the target, and only ever pulled under it, by
                // the peak limiter on a resonant patch: 48 cases measured
                // -1.69..0.00 dB when this was written; -3 dB is that plus margin.
                m[2] > 0.1 || m[2] < -3.0 -> "loudness ${"%.2f".format(m[2])} dB: ${c.label}"
                else -> null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
