package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The modal bank. Its whole reason to exist is that each mode decays at its
 * OWN rate — `audio/Body.kt` has the right two-pole form but one shared pole
 * radius, so everything it rings falls silent together. Highs dying before
 * lows is what makes a sound read as struck rather than played.
 */
class ModesTest {

    /** RMS of a window, for comparing how much energy is left at a given time. */
    private fun rmsAt(buf: FloatArray, fromSec: Float, windowSec: Float, rate: Int): Float {
        val from = (fromSec * rate).toInt().coerceIn(0, buf.size)
        val to = (from + windowSec * rate).toInt().coerceIn(from, buf.size)
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += buf[i].toDouble() * buf[i]
        return kotlin.math.sqrt(sum / (to - from)).toFloat()
    }

    private fun click(rate: Int, seconds: Float): FloatArray =
        FloatArray((seconds * rate).toInt()).also { it[0] = 1f }

    @Test
    fun `each mode decays at its own rate, not a shared one`() {
        val rate = Dsp.RATE
        // Two modes an octave apart. The high one is told to die four times
        // faster. If the bank shares one pole radius, both survive equally
        // and this fails.
        val modes = listOf(
            Modes.Mode(ratio = 1f, gain = 1f, t60 = 1.2f),
            Modes.Mode(ratio = 2f, gain = 1f, t60 = 0.3f),
        )
        val out = Modes.ring(click(rate, 1.5f), fundamentalHz = 220f, modes = modes, rate = rate)

        val lowOnly = Modes.ring(click(rate, 1.5f), 220f, listOf(modes[0]), rate)
        val highOnly = Modes.ring(click(rate, 1.5f), 220f, listOf(modes[1]), rate)

        // Early on both ring; by 0.8s the fast mode should be far quieter.
        val earlyRatio = rmsAt(highOnly, 0.02f, 0.05f, rate) / rmsAt(lowOnly, 0.02f, 0.05f, rate)
        val lateRatio = rmsAt(highOnly, 0.8f, 0.05f, rate) / rmsAt(lowOnly, 0.8f, 0.05f, rate)
        assertTrue(
            lateRatio < earlyRatio * 0.25f,
            "the fast mode must fall away relative to the slow one: early=$earlyRatio late=$lateRatio",
        )
        assertTrue(out.all { it.isFinite() }, "bank rendered NaN or Inf")
    }

    @Test
    fun `a mode rings at its own frequency`() {
        val rate = Dsp.RATE
        val out = Modes.ring(
            click(rate, 0.5f),
            fundamentalHz = 300f,
            modes = listOf(Modes.Mode(ratio = 2.756f, gain = 1f, t60 = 0.4f)),
            rate = rate,
        )
        // 300 * 2.756 = 826.8 Hz. Measured by zero crossings over a settled
        // window — crude but independent of any FFT windowing choice.
        var crossings = 0
        val from = (0.05f * rate).toInt()
        val to = (0.25f * rate).toInt()
        for (i in from + 1 until to) if ((out[i - 1] < 0f) != (out[i] < 0f)) crossings++
        val hz = crossings / 2f / ((to - from).toFloat() / rate)
        assertTrue(abs(hz - 826.8f) < 826.8f * 0.05f, "expected ~826.8 Hz, measured $hz")
    }

    @Test
    fun `modes with different decays ring at comparable levels`() {
        val rate = Dsp.RATE
        // Onset level must not depend on t60. A gain term borrowed from
        // Body.ring's shared-r (1-r) compensation — or even the tempting
        // energy-preserving sqrt(1-r^2) — swings this by 8-80x across the
        // decay range (see the reachability test below); a bug, not a timbre.
        val slow = Modes.ring(click(rate, 1f), 220f, listOf(Modes.Mode(1f, 1f, 1.5f)), rate)
        val fast = Modes.ring(click(rate, 1f), 220f, listOf(Modes.Mode(1f, 1f, 0.2f)), rate)
        val slowPeak = slow.maxOf { abs(it) }
        val fastPeak = fast.maxOf { abs(it) }
        assertTrue(
            slowPeak < fastPeak * 4f && fastPeak < slowPeak * 4f,
            "onset levels should be comparable regardless of decay: slow=$slowPeak fast=$fastPeak",
        )
    }

    /**
     * Reachability test for t60: the loosely-toleranced test above only
     * samples 1.5s vs 0.2s (a 7.5x spread). `sqrt(1-r^2)` — the
     * energy-preserving gain that looked correct in isolation — squeaks
     * under that test's 4x bar at 8.7x, then blows through it once t60's
     * full plausible range (0.05s..4s, an 80x spread) is swept. This is the
     * "silently overrides across its entire range while every test stays
     * green" failure the standing policy calls out by name — so the extremes
     * are checked directly, not just one comfortable midpoint.
     */
    @Test
    fun `onset level stays flat across the full decay range`() {
        val rate = Dsp.RATE
        val peaks = listOf(0.05f, 0.2f, 0.5f, 1.5f, 4f).map { t60 ->
            val out = Modes.ring(click(rate, (t60 * 1.2f).coerceAtLeast(1f)), 220f, listOf(Modes.Mode(1f, 1f, t60)), rate)
            out.maxOf { abs(it) }
        }
        val maxPeak = peaks.max()
        val minPeak = peaks.min()
        assertTrue(
            maxPeak < minPeak * 2f,
            "onset level must stay flat across t60 0.05s..4s, not just at one sampled pair: peaks=$peaks",
        )
    }

    @Test
    fun `the bank is deterministic`() {
        val rate = Dsp.RATE
        val modes = listOf(Modes.Mode(1f, 1f, 0.5f), Modes.Mode(2.756f, 0.6f, 0.3f))
        val a = Modes.ring(click(rate, 0.5f), 220f, modes, rate)
        val b = Modes.ring(click(rate, 0.5f), 220f, modes, rate)
        assertTrue(a.contentEquals(b), "same inputs must give byte-identical output")
    }

    @Test
    fun `every material's ratios ascend and start at or below the fundamental`() {
        for (material in Modes.Material.entries) {
            val table = Modes.tableFor(material)
            assertTrue(table.isNotEmpty(), "$material has no modes")
            val ratios = table.map { it.ratio }
            assertTrue(
                ratios.zipWithNext().all { (a, b) -> b > a },
                "$material ratios must strictly ascend: $ratios",
            )
            assertTrue(ratios.first() > 0f, "$material has a non-positive first ratio")
        }
    }

    @Test
    fun `the metal bar is genuinely inharmonic`() {
        // 2.756 is the whole point: it is not 2, 3, or any integer, and that
        // is why a glockenspiel sounds like metal rather than a filtered saw.
        val ratios = Modes.tableFor(Modes.Material.METAL_BAR).map { it.ratio }
        assertTrue(abs(ratios[1] - 2.756f) < 0.001f, "second mode should be 2.756, got ${ratios[1]}")
        for (r in ratios.drop(1)) {
            val nearestInt = kotlin.math.round(r)
            assertTrue(abs(r - nearestInt) > 0.05f, "$r is suspiciously close to a harmonic")
        }
    }

    @Test
    fun `the bell's hum mode sits below its prime`() {
        val ratios = Modes.tableFor(Modes.Material.BELL).map { it.ratio }
        assertTrue(ratios.first() < 1f, "a bell's hum mode is an octave below the prime: ${ratios.first()}")
    }

    @Test
    fun `stiff string inharmonicity grows with mode index and with B`() {
        val low = Modes.stiffString(partials = 6, b = 0.0003f)
        val high = Modes.stiffString(partials = 6, b = 0.025f)
        // Mode n sits at n*sqrt(1+B*n^2) — stretched upward, more so for
        // higher n and higher B. Mode 1 barely moves; mode 6 moves a lot.
        assertTrue(low[0].ratio < low[5].ratio, "ratios must ascend")
        val lowStretch = low[5].ratio / 6f
        val highStretch = high[5].ratio / 6f
        assertTrue(highStretch > lowStretch, "larger B must stretch further: $lowStretch vs $highStretch")
        assertTrue(lowStretch > 1f, "any positive B stretches above the harmonic series")
    }

    @Test
    fun `a morph endpoint reproduces its own table`() {
        val slots = 6
        val bar = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.MEMBRANE, amount = 0f, slots = slots)
        val sourced = Modes.tableFor(Modes.Material.METAL_BAR)
        for (i in sourced.indices) {
            assertTrue(
                abs(bar[i].ratio - sourced[i].ratio) < 0.001f,
                "at amount=0 slot $i should be the bar's own ratio: ${bar[i].ratio} vs ${sourced[i].ratio}",
            )
        }
    }

    @Test
    fun `every slot carries a real partial - no silent padding`() {
        // The bar has 4 sourced partials, wood has 3, but a 6-slot bank must
        // be dense for BOTH or the morph thins out halfway through.
        for (material in Modes.Material.entries) {
            val morphed = Modes.morph(material, material, amount = 0f, slots = 6)
            assertTrue(morphed.size == 6, "$material should fill all 6 slots, got ${morphed.size}")
            assertTrue(
                morphed.all { it.gain > 0f },
                "$material has a silent slot — extrapolate the trend, do not pad with silence",
            )
            assertTrue(
                morphed.map { it.ratio }.zipWithNext().all { (a, b) -> b > a },
                "$material extrapolated ratios must keep ascending: ${morphed.map { it.ratio }}",
            )
        }
    }

    @Test
    fun `the morph is continuous - no jump at any step`() {
        // Sweep MATERIAL and confirm no slot's ratio lurches. A discontinuity
        // here would be audible as a click or a sudden change of body.
        val slots = 6
        var previous = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.BELL, 0f, slots)
        for (step in 1..50) {
            val current = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.BELL, step / 50f, slots)
            for (i in 0 until slots) {
                val jump = abs(current[i].ratio - previous[i].ratio) / previous[i].ratio
                assertTrue(jump < 0.15f, "slot $i jumped ${jump * 100}% at step $step")
            }
            previous = current
        }
    }

    @Test
    fun `every resampled slot survives rendering through ring(), not just the abstract table`() {
        // The property that matters is not "the table says gain > 0" (that
        // survives even a mode extrapolated straight past Nyquist, which
        // ring() then silently drops) but "this slot actually makes sound
        // once rung at a real fundamental." Checked at two fundamentals
        // because a slot that fits under Nyquist at 220 Hz can still get
        // pushed over it at 440 Hz.
        val rate = Dsp.RATE
        for (material in Modes.Material.entries) {
            for (fundamental in listOf(220f, 440f)) {
                val modes = Modes.resample(material, slots = 6)
                for ((i, mode) in modes.withIndex()) {
                    val out = Modes.ring(click(rate, 0.3f), fundamental, listOf(mode), rate)
                    val peak = out.maxOf { abs(it) }
                    assertTrue(
                        peak > 1e-6f,
                        "$material slot ${i + 1} is silent at ${fundamental}Hz fundamental " +
                            "(ratio=${mode.ratio}, would ring at ${mode.ratio * fundamental}Hz) " +
                            "— extrapolation overshot past Nyquist instead of thinning the trend",
                    )
                }
            }
        }
    }

    @Test
    fun `WOOD_MARIMBA's Nyquist ceiling is the lowest of any material, and low enough to matter`() {
        // A real physical ceiling, not a bug: 1:4:10 grows faster than any
        // other table, so WOOD_MARIMBA's extrapolated top slot is the first
        // to cross Nyquist as the fundamental rises - documented in
        // resample()'s KDoc as ~619 Hz, which is D#5, an entirely ordinary
        // pitch for a struck one-shot. Pinned on DIRECTION (still the
        // outlier, still low enough to be reachable) rather than an exact
        // frequency, so a legitimate future tweak to the extrapolation
        // curve, the slot count, or the table doesn't require touching a
        // magic number here - but a change that quietly stops WOOD_MARIMBA
        // being the outlier, or pushes its ceiling comfortably out of
        // playable range, should fail this and force the KDoc table to be
        // re-checked instead of going stale.
        val nyquist = Dsp.RATE / 2f
        val ceilings = Modes.Material.entries.associateWith { material ->
            nyquist / Modes.resample(material, slots = 6).last().ratio
        }
        val marimba = ceilings.getValue(Modes.Material.WOOD_MARIMBA)
        val others = ceilings.filterKeys { it != Modes.Material.WOOD_MARIMBA }.values
        assertTrue(
            marimba < others.min(),
            "WOOD_MARIMBA should have the lowest Nyquist ceiling of any material: $ceilings",
        )
        assertTrue(
            marimba < 1000f,
            "WOOD_MARIMBA's ceiling should sit at an ordinary percussion pitch, not somewhere " +
                "safely out of reach: ${marimba}Hz",
        )
    }

    @Test
    fun `morphing between different bodies actually changes the spectrum`() {
        // Reachability, checked PER SLOT: a maxOf across all six slots would
        // pass if only one sourced slot moved and every extrapolated slot
        // stayed frozen to `from`'s values — exactly the defect Phase 0
        // shipped once, just relocated to slot granularity. METAL_BAR and
        // BELL are used because they share no fixed point (BELL's hum sits
        // below its own prime, unlike every other table's ratio-1
        // fundamental), so a frozen slot has nowhere to hide.
        val bar = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.BELL, 0f, 6)
        val bell = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.BELL, 1f, 6)
        for (k in bar.indices) {
            val change = abs(bar[k].ratio - bell[k].ratio) / bar[k].ratio
            assertTrue(change > 0.2f, "slot $k barely changed across the morph: $change")
        }
    }

    @Test
    fun `striking a node silences that mode`() {
        val modes = Modes.tableFor(Modes.Material.METAL_BAR)
        // Mode 2 has a node at position 0.5 — sin(2*pi*0.5) = 0.
        val atCentre = Modes.atPosition(modes, 0.5f)
        assertTrue(atCentre[1].gain < modes[1].gain * 0.05f, "mode 2 should be near-silenced at its node")
        // Mode 1 is at its maximum there.
        assertTrue(atCentre[0].gain > modes[0].gain * 0.9f, "mode 1 should be near-full at centre")
    }

    @Test
    fun `strike position changes the spectrum across its travel`() {
        // Reachability: STRIKE must do something everywhere it can be set.
        val modes = Modes.tableFor(Modes.Material.METAL_BAR)
        val positions = listOf(0.05f, 0.25f, 0.5f, 0.75f, 0.95f)
        val profiles = positions.map { p -> Modes.atPosition(modes, p).map { it.gain } }
        for (i in 0 until profiles.size - 1) {
            val diff = profiles[i].indices.maxOf { abs(profiles[i][it] - profiles[i + 1][it]) }
            assertTrue(diff > 0.01f, "positions ${positions[i]} and ${positions[i + 1]} give near-identical gains")
        }
    }

    @Test
    fun `strike position never produces negative or non-finite gain`() {
        val modes = Modes.tableFor(Modes.Material.BELL)
        for (step in 0..100) {
            for (m in Modes.atPosition(modes, step / 100f)) {
                assertTrue(m.gain >= 0f && m.gain.isFinite(), "gain ${m.gain} at position ${step / 100f}")
            }
        }
    }

    @Test
    fun `a mode above Nyquist is skipped, not aliased`() {
        val rate = Dsp.RATE
        val out = Modes.ring(
            click(rate, 0.2f),
            fundamentalHz = 15_000f,
            modes = listOf(Modes.Mode(ratio = 4f, gain = 1f, t60 = 0.2f)),  // 60 kHz
            rate = rate,
        )
        assertTrue(out.all { it.isFinite() }, "an over-Nyquist mode must not produce NaN")
        assertTrue(out.all { abs(it) < 1e-6f }, "an over-Nyquist mode must be silent, not folded down")
    }

    @Test
    fun `a mono fold-down of the stereo bank returns the mono bank exactly`() {
        val rate = Dsp.RATE
        val exc = FloatArray(rate / 4).also { it[0] = 1f }
        val modes = Modes.spread(Modes.tableFor(Modes.Material.METAL_BAR), width = 1f, seed = 7)
        val mono = Modes.ring(exc, 220f, modes, rate)
        val stereo = Modes.ringStereo(exc, 220f, modes, rate)
        // Linear panning sums to unity for every position, so L+R must be
        // the mono render sample for sample. This is the property that makes
        // the width safe on a club system, and it is worth asserting exactly
        // rather than approximately — but "exactly" has to be relative to the
        // signal's own scale, not an absolute constant: the resonator's
        // impulse response carries a 1/sin(theta) amplitude factor (~32 at
        // this pitch, mono peak ~41), and the stereo path sums the same
        // per-mode terms in a different order (per-mode L/R accumulation vs.
        // one shared mono accumulator), so float rounding alone can sit right
        // at a fixed 1e-5 threshold. Scale the tolerance to the peak instead.
        val peak = mono.maxOf { kotlin.math.abs(it) }
        val tol = 1e-5f * kotlin.math.max(1f, peak)
        for (f in mono.indices) {
            val summed = stereo[f * 2] + stereo[f * 2 + 1]
            assertTrue(
                kotlin.math.abs(summed - mono[f]) < tol,
                "fold-down differs at frame $f: $summed vs ${mono[f]} (tol=$tol, peak=$peak)",
            )
        }
    }

    @Test
    fun `width actually widens, swept across its travel`() {
        val rate = Dsp.RATE
        val exc = FloatArray(rate / 4).also { it[0] = 1f }
        val table = Modes.tableFor(Modes.Material.METAL_BAR)
        // Side energy over mid energy: 0 at width 0, rising monotonically.
        fun sideRatio(width: Float): Float {
            val s = Modes.ringStereo(exc, 220f, Modes.spread(table, width, seed = 7), rate)
            var mid = 0.0; var side = 0.0
            var f = 0
            while (f < s.size) {
                val m = (s[f] + s[f + 1]) * 0.5; val d = (s[f] - s[f + 1]) * 0.5
                mid += m * m; side += d * d; f += 2
            }
            return kotlin.math.sqrt(side / (mid + 1e-12)).toFloat()
        }
        val points = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { sideRatio(it) }
        assertTrue(points.first() < 1e-4f, "width 0 must be dead centre, got ${points.first()}")
        for (i in 0 until points.size - 1) {
            assertTrue(
                points[i + 1] > points[i] * 1.15f,
                "width did nothing between step $i and ${i + 1}: $points",
            )
        }
    }

    @Test
    fun `the stereo bank is deterministic`() {
        val rate = Dsp.RATE
        val exc = FloatArray(rate / 8).also { it[0] = 1f }
        val modes = Modes.spread(Modes.tableFor(Modes.Material.BELL), width = 0.7f, seed = 3)
        assertTrue(
            Modes.ringStereo(exc, 220f, modes, rate)
                .contentEquals(Modes.ringStereo(exc, 220f, modes, rate)),
            "same inputs must give byte-identical stereo output",
        )
    }
}
