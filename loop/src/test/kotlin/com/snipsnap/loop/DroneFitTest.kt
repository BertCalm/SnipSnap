package com.snipsnap.loop

import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-09-25-resin-drone-design.md, "The one trade-off". */
class DroneFitTest {

    private val recipe = JsonValue.Obj(linkedMapOf("engine" to JsonValue.Str("TEST")))

    private fun fresh(bpm: Float = 90f, rate: Int = 44_100) =
        SessionBuilder.empty(rate).copy(bpm = bpm)

    @Test
    fun `the longest span is a whole chain`() {
        assertEquals(Session.MAX_CHAIN, DroneFit.SPANS.last())
    }

    @Test
    fun `a fresh session spans four bars for a low A, two for A2, one for A3`() {
        val s = fresh()
        assertEquals(1, s.barsPerInterval, "the spec's numbers are for a fresh 1-bar session")
        assertEquals(4, DroneFit.spanFor(33, s))
        assertEquals(2, DroneFit.spanFor(45, s))
        assertEquals(1, DroneFit.spanFor(57, s))
    }

    @Test
    fun `the nudge is the probe's number`() {
        // The probe rendered this drone and measured -1.97 cents.
        assertEquals(-1.97, DroneFit.nudgeCents(33, 4, fresh()), 0.01)
    }

    @Test
    fun `the snapped note completes whole sub-octave cycles in the loop`() {
        val s = fresh()
        val frames = 4L * s.intervalFrames
        val hz = DroneFit.snappedHz(DroneFit.hz(33), frames, s.sampleRate)
        val subCycles = hz / 2 * frames / s.sampleRate
        assertEquals(Math.round(subCycles).toDouble(), subCycles, 1e-9)
    }

    /**
     * Every note in RESIN's register at every whole BPM, at both rates:
     * inside the promise, except one case, computed when the plan was
     * written. A different list means the spec is wrong, not the bound.
     */
    @Test
    fun `the promise holds everywhere but A1 at 216 BPM`() {
        val misses = mutableListOf<String>()
        for (rate in listOf(44_100, 48_000)) for (bpm in 40..220) {
            val s = fresh(bpm.toFloat(), rate)
            for (midi in 33..84) {
                val span = DroneFit.spanFor(midi, s)
                val c = DroneFit.nudgeCents(midi, span, s)
                if (abs(c) > DroneFit.MAX_NUDGE_CENTS) {
                    assertEquals(8, span, "a miss must be at the longest span")
                    misses += "$midi@$bpm/$rate ${"%.2f".format(c)}"
                }
            }
        }
        assertEquals(listOf("33@216/44100 -3.15", "33@216/48000 -3.14"), misses)
    }

    @Test
    fun `refit re-slices only drone tracks and keeps what the player set`() {
        val base = SessionBuilder.sendDrone(fresh(), 2, "DRONE", recipe, 33)
        val placed = base.copy(
            tracks = base.tracks.mapIndexed { i, t -> if (i == 2) t.copy(level = 0.5f, pan = -0.3f, engaged = false) else t },
        )
        assertEquals(4, placed.tracks[2].chain.size)

        // Faster: a 1-bar interval is shorter, so A1 needs more of them.
        val fast = DroneFit.refit(placed.copy(bpm = 180f))
        val t = fast.tracks[2]
        assertEquals(DroneFit.spanFor(33, fast), t.chain.size)
        assertTrue(t.chain.size > 4, "180 BPM needs a longer span than 90")
        assertEquals(listOf("DRONE", "0.5", "-0.3", "false"), listOf(t.name, "${t.level}", "${t.pan}", "${t.engaged}"))
        t.chain.forEachIndexed { i, b ->
            b as DroneBlock
            assertEquals(i, b.slice)
            assertEquals(t.chain.size, b.of)
        }
        for (i in fast.tracks.indices) if (i != 2) assertSame(placed.tracks[i], fast.tracks[i])
    }

    @Test
    fun `refit leaves a session with nothing to change as the same instance`() {
        val s = SessionBuilder.sendDrone(fresh(), 0, "DRONE", recipe, 33)
        assertSame(s, DroneFit.refit(s))
        val empty = fresh()
        assertSame(empty, DroneFit.refit(empty))
    }

    @Test
    fun `a track mixing a drone with anything else is not a drone`() {
        val mixed = Track("x", listOf(DroneBlock(recipe, 33, 0, 2), LoopBlock("a.wav")))
        assertEquals(null, DroneFit.droneOf(mixed))
    }

    /**
     * Random grids: drones on random roots, loops and silence beside them,
     * random tempos, bars and rates. However a session arrives, one refit
     * puts every drone at its span and a second changes nothing.
     */
    @Test
    fun `refit settles any grid in one pass and never touches a non-drone track`() {
        val r = kotlin.random.Random(20260925)
        repeat(300) { n ->
            val rate = listOf(22_050, 44_100, 48_000, 96_000)[r.nextInt(4)]
            val bars = Session.VALID_BARS[r.nextInt(Session.VALID_BARS.size)]
            var s = SessionBuilder.empty(rate, bpm = (40 + r.nextInt(181)).toFloat(), barsPerInterval = bars)
            val tracks = s.tracks.mapIndexed { t, track ->
                when (r.nextInt(3)) {
                    // A drone sliced for some other tempo: any span, which refit must correct.
                    0 -> {
                        val of = DroneFit.SPANS[r.nextInt(DroneFit.SPANS.size)]
                        track.copy(name = "D$t", chain = DroneFit.slices(recipe, r.nextInt(128), of))
                    }
                    1 -> track.copy(name = "L$t", chain = listOf(LoopBlock("t$t.wav")))
                    else -> track
                }
            }
            s = s.copy(tracks = tracks)
            val once = DroneFit.refit(s)
            assertSame(once, DroneFit.refit(once), "case $n: a second refit changed something")
            for (t in s.tracks.indices) {
                val before = s.tracks[t]
                val after = once.tracks[t]
                val drone = DroneFit.droneOf(before)
                if (drone == null) {
                    assertSame(before, after, "case $n track $t: not a drone, but refit touched it")
                } else {
                    val span = DroneFit.spanFor(drone.rootMidi, once)
                    assertEquals(span, after.chain.size, "case $n track $t")
                    after.chain.forEachIndexed { i, b -> assertEquals(DroneBlock(recipe, drone.rootMidi, i, span), b) }
                }
            }
        }
    }
}
