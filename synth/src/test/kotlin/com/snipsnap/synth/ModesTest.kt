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
    fun `morphing between different bodies actually changes the spectrum`() {
        // Reachability: MATERIAL must do something across its travel, or it
        // is a knob that does nothing — the exact defect Phase 0 shipped once.
        val bar = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.MEMBRANE, 0f, 6)
        val membrane = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.MEMBRANE, 1f, 6)
        val spread = bar.indices.maxOf { abs(bar[it].ratio - membrane[it].ratio) / bar[it].ratio }
        assertTrue(spread > 0.2f, "endpoints should differ meaningfully, max slot change was $spread")
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
}
