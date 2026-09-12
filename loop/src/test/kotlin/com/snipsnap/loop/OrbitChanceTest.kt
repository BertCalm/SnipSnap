package com.snipsnap.loop

import com.snipsnap.mpc3.Mpc3Clip
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Probability, conditionals and ratchets.
 *
 * `OrbitHit` said when a hit happens and how hard, and nothing about
 * whether it happens at all — so a ring played the same sixteen hits on
 * every one of its laps for as long as it turned. These three fields are
 * what every modern step sequencer has for that, and rings are an
 * unusually good home for them: the laps already drift against each
 * other, so a hit on one lap in three lands somewhere new each time it
 * comes round rather than merely thinning the same bar.
 *
 * The claim that has to hold above all the others is that **the engine
 * and the export agree lap for lap**. A roll that came out differently in
 * the two domains would mean a bounce that did not match what was heard,
 * which is the one thing this feature must not do quietly.
 */
class OrbitChanceTest {

    private val rate = 48_000
    private val bpm = 120f
    private val step = 6_000 // frames per 16th at 120 BPM, 48 kHz
    private val s16 = Mpc3Clip.PULSES_PER_16TH

    private fun ring(vararg hits: OrbitHit) = Orbit("R", 16, PatternOrbit("kit", hits.toList()))
    private fun set(vararg orbits: Orbit, seed: Int = OrbitSet.DEFAULT_SEED) =
        OrbitSet(orbits.toList(), bpm, rate, seed = seed)

    /** The laps of [orbit] on which its one hit sounds, out of the first [laps]. */
    private fun lapsHeard(s: OrbitSet, orbit: Orbit, laps: Int): List<Int> {
        val period = OrbitClock.periodFrames(s, orbit)
        return OrbitClock.firings(s, orbit, 0L, period * laps).map { (it.frame / period).toInt() }
    }

    // ---- nothing changes for a hit that says nothing ----

    @Test
    fun `a plain hit still fires on every lap, on its step`() {
        val r = ring(OrbitHit(4, 1))
        val s = set(r)
        val at = OrbitClock.firings(s, r, 0L, 16L * step * 3).map { it.frame }
        assertEquals(listOf(4L * step, 20L * step, 36L * step), at)
    }

    @Test
    fun `a hit is certain by default and says so`() {
        val h = OrbitHit(0, 1)
        assertTrue(h.certain)
        assertTrue(!h.ratcheted)
        assertEquals(OrbitHit.ALWAYS, h.chance)
    }

    // ---- the headline: one take, twice, and the same take on export ----

    @Test
    fun `the same seed gives the same laps twice`() {
        val r = ring(OrbitHit(0, 1, chance = 25))
        val s = set(r)
        assertEquals(lapsHeard(s, r, 64), lapsHeard(s, r, 64))
    }

    @Test
    fun `a rolled hit is not the same on every lap`() {
        val r = ring(OrbitHit(0, 1, chance = 25))
        val heard = lapsHeard(set(r), r, 64)
        assertTrue(heard.isNotEmpty(), "a 25% hit never fired in 64 laps")
        assertTrue(heard.size < 64, "a 25% hit fired on every one of 64 laps")
    }

    @Test
    fun `a new seed is a new take of the same hits`() {
        val r = ring(OrbitHit(0, 1, chance = 50))
        assertNotEquals(lapsHeard(set(r, seed = 1), r, 64), lapsHeard(set(r, seed = 2), r, 64))
    }

    @Test
    fun `a one-in-four hit fires about a quarter of the time`() {
        // Wide bounds: this is here to catch a seed mixer that has
        // collapsed, not to pin a distribution. A hash that returned the
        // same number for every lap gives 0 or 400, both far outside.
        val r = ring(OrbitHit(0, 1, chance = 25))
        val heard = lapsHeard(set(r), r, 400).size
        assertTrue(heard in 70..130, "a 25% hit fired on $heard of 400 laps")
    }

    @Test
    fun `the engine and the export agree on which laps sound`() {
        val r = ring(OrbitHit(0, 1, chance = 40), OrbitHit(7, 2, chance = 40, offset = -13))
        val s = set(r)
        val laps = 64
        val heard = OrbitClock.firings(s, r, 0L, OrbitClock.periodFrames(s, r) * laps)
            .map { it.hit.slot to it.frame / OrbitClock.periodFrames(s, r) }
        val written = OrbitClock.pulseFirings(s, r, OrbitClock.periodPulses(s, r) * laps)
            .map { it.hit.slot to it.pulses / OrbitClock.periodPulses(s, r) }
        // The leaning hit's pickup lands a lap earlier in both domains, so
        // compare the sets of (pad, lap) rather than the raw lists.
        assertEquals(heard.toSet(), written.toSet())
    }

    /**
     * A leaning hit rolls the same in both domains on every turn the two
     * of them both hold.
     *
     * They do not both hold turn 0, and that is older than this feature.
     * At 700 Hz a pulse is finer than a frame, so a one-pulse lean is
     * −1 pulse but rounds to frame 0 — verified: `firingPulses` is −1
     * and `firingOffset` is 0. The clip starts at pulse 0 and has nowhere
     * to write −1, so the export drops that first pickup; the engine's
     * copy rounded onto frame 0 and is played. Both walks label it turn 0
     * and roll it identically — the disagreement is about whether it fits
     * in the window at all, which is the same answer the merged base gave
     * before any of this was written.
     */
    @Test
    fun `a hair-early hit rolls the same in both domains at a coarse rate`() {
        val r = ring(OrbitHit(0, 1, chance = 50, offset = -1))
        val s = OrbitSet(listOf(r), bpm, sampleRate = 700)
        val laps = 40
        fun turns(from: Int) = (from until laps).filter { OrbitClock.sounds(s, r, (r.content as PatternOrbit).hits[0], it.toLong()) }
        val heard = OrbitClock.firings(s, r, 0L, OrbitClock.periodFrames(s, r) * laps)
            .map { (it.frame / OrbitClock.periodFrames(s, r)).toInt() }
        val written = OrbitClock.pulseFirings(s, r, OrbitClock.periodPulses(s, r) * laps)
            .map { ((it.pulses + 1) / OrbitClock.periodPulses(s, r)).toInt() }
        assertEquals(turns(0), heard)
        assertEquals(turns(1), written, "the export holds every turn but the truncated first pickup")
    }

    // ---- conditionals ----

    @Test
    fun `one lap in four, and which one`() {
        val r = ring(OrbitHit(0, 1, everyLaps = 4, onLap = 1))
        assertEquals(listOf(1, 5, 9, 13), lapsHeard(set(r), r, 16))
    }

    @Test
    fun `a conditional and a chance compose`() {
        val plain = ring(OrbitHit(0, 1, everyLaps = 2, onLap = 0))
        val rolled = ring(OrbitHit(0, 1, everyLaps = 2, onLap = 0, chance = 50))
        val certain = lapsHeard(set(plain), plain, 64)
        val chancy = lapsHeard(set(rolled), rolled, 64)
        assertTrue(chancy.all { it in certain }, "the roll fired on a lap the condition excludes")
        assertTrue(chancy.size < certain.size, "a 50% chance on top of the condition changed nothing")
    }

    @Test
    fun `a conditional stretches the cycle, because the set does not repeat until it does`() {
        val plain = ring(OrbitHit(0, 1))
        assertEquals(16L, OrbitClock.cycleSteps(set(plain)))
        val every3 = ring(OrbitHit(0, 1, everyLaps = 3, onLap = 0))
        assertEquals(48L, OrbitClock.cycleSteps(set(every3)))
        // A chance is not periodic at all, so it must not pretend to be.
        val rolled = ring(OrbitHit(0, 1, chance = 25))
        assertEquals(16L, OrbitClock.cycleSteps(set(rolled)))
    }

    // ---- ratchets ----

    @Test
    fun `a ratchet strikes evenly across its own step`() {
        val r = ring(OrbitHit(0, 1, ratchet = 4))
        val at = OrbitClock.firings(set(r), r, 0L, 16L * step).map { it.frame }
        assertEquals(listOf(0L, step / 4L, step / 2L, step * 3L / 4), at)
    }

    @Test
    fun `a ratcheted hit exports as its sub-16th notes`() {
        val r = ring(OrbitHit(0, 1, ratchet = 4))
        val at = OrbitClock.pulseFirings(set(r), r, 16L * s16).map { it.pulses }
        assertEquals(listOf(0L, s16 / 4, s16 / 2, s16 * 3 / 4), at)
    }

    @Test
    fun `a ratchet subdivides a spanned ring's step, not a sixteenth`() {
        // Three steps across one bar: each step is a third of a bar, so a
        // ratchet of two strikes every sixth of a bar.
        val r = Orbit("T", 3, PatternOrbit("kit", listOf(OrbitHit(0, 1, ratchet = 2))), span = OrbitSpan.ONE)
        val at = OrbitClock.pulseFirings(set(r), r, 16L * s16).map { it.pulses }
        val bar = Mpc3Clip.PULSES_PER_BAR
        assertEquals(listOf(0L, bar / 6), at)
    }

    @Test
    fun `the roll decides the hit, so a ratchet is all of it or none of it`() {
        val r = ring(OrbitHit(0, 1, chance = 50, ratchet = 3))
        val s = set(r)
        val period = OrbitClock.periodFrames(s, r)
        val perLap = OrbitClock.firings(s, r, 0L, period * 40).groupBy { it.frame / period }
        assertTrue(perLap.isNotEmpty(), "nothing fired at all")
        assertTrue(perLap.values.all { it.size == 3 }, "a lap struck ${perLap.values.map { it.size }}")
    }

    // ---- the property the widened lap walk has to keep ----

    @Test
    fun `every strike lands in exactly one block`() {
        // Ratchets and leans reach outside the lap they belong to, so the
        // walk is widened and then filtered. Widening without the filter
        // emits a strike from both of two neighbouring blocks; filtering
        // without the widening loses one. Block-by-block must equal the
        // whole, with nothing doubled and nothing missing.
        val r = ring(
            OrbitHit(0, 1, ratchet = 3, offset = -200),
            OrbitHit(5, 2, ratchet = 4, offset = 200, chance = 60),
            OrbitHit(11, 3, everyLaps = 3, onLap = 2),
        )
        val s = set(r)
        val end = OrbitClock.periodFrames(s, r) * 7
        val whole = OrbitClock.firings(s, r, 0L, end).map { it.hit.slot to it.frame }
        val blocks = ArrayList<Pair<Int, Long>>()
        var at = 0L
        while (at < end) {
            val to = minOf(at + 1024, end)
            OrbitClock.firings(s, r, at, to).forEach { blocks.add(it.hit.slot to it.frame) }
            at = to
        }
        assertEquals(whole, blocks)
        assertEquals(whole.size, whole.toSet().size, "a strike was emitted twice")
    }

    // ---- the file ----

    @Test
    fun `every new field round-trips through orbits json`() {
        val hits = listOf(
            OrbitHit(0, 1, chance = 25),
            OrbitHit(3, 2, everyLaps = 4, onLap = 3),
            OrbitHit(6, 3, ratchet = 5),
            OrbitHit(9, 4, chance = 60, everyLaps = 2, onLap = 1, ratchet = 2, offset = -40, length = s16 * 2),
            OrbitHit(12, 5),
        )
        val s = set(ring(*hits.toTypedArray()), seed = 4_242)
        val dir = Files.createTempDirectory("orbit-chance").toFile()
        OrbitStore.save(s, dir)
        val back = OrbitStore.load(dir)
        assertEquals(OrbitStore.VERSION, 6)
        assertEquals(s.seed, back.seed)
        assertEquals(hits, (back.orbits[0].content as PatternOrbit).hits)
        // A certain hit still writes exactly the fields it always did: the
        // file grows a key only where one is needed, which is what keeps a
        // set made before any of this readable as the same set.
        val text = java.io.File(dir, OrbitStore.FILE_NAME).readText()
        assertEquals(2, Regex("\"chance\"").findAll(text).count(), "chance written other than on the two hits that have one")
        assertEquals(2, Regex("\"everyLaps\"").findAll(text).count())
        assertEquals(2, Regex("\"onLap\"").findAll(text).count(), "onLap must travel with everyLaps, never alone")
        assertEquals(2, Regex("\"ratchet\"").findAll(text).count())
        // The last hit in the file is the plain one; split on the key
        // rather than on a formatted number, so this asks about the
        // fields written and not about how the writer spaces a colon.
        val plain = text.split("\"step\"").last()
        for (key in listOf("chance", "everyLaps", "onLap", "ratchet", "offset", "length")) {
            assertTrue(!plain.contains("\"$key\""), "a plain hit wrote \"$key\"")
        }
    }

    @Test
    fun `a version 5 file still loads, and every hit in it is certain`() {
        val dir = Files.createTempDirectory("orbit-v5").toFile()
        java.io.File(dir, OrbitStore.FILE_NAME).writeText(
            """
            {"version":5,"bpm":90.0,"lapSteps":16,"swing":50,"sampleRate":48000,"orbits":[
              {"name":"R","steps":16,"span":"FREE","voice":[1],"engaged":true,"level":1.0,"pan":0.0,
               "content":{"type":"pattern","kit":"kit","hits":[{"step":0,"slot":1,"velocity":1.0}]}}
            ]}
            """.trimIndent(),
        )
        val back = OrbitStore.load(dir)
        assertEquals(OrbitSet.DEFAULT_SEED, back.seed)
        assertTrue((back.orbits[0].content as PatternOrbit).hits.single().certain)
    }

    // ---- what a hit refuses to be ----

    @Test
    fun `a hit refuses a condition it could never meet`() {
        assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, everyLaps = 4, onLap = 4) }
        assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, everyLaps = 0) }
        assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, everyLaps = OrbitHit.MAX_EVERY + 1) }
        assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, chance = 101) }
        assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, chance = -1) }
        assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, ratchet = 0) }
        assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, ratchet = OrbitHit.MAX_RATCHET + 1) }
    }

    @Test
    fun `a chance of nothing never sounds and a chance of everything always does`() {
        val never = ring(OrbitHit(0, 1, chance = 0))
        assertEquals(emptyList(), lapsHeard(set(never), never, 32))
        val always = ring(OrbitHit(0, 1, chance = OrbitHit.ALWAYS))
        assertEquals((0 until 32).toList(), lapsHeard(set(always), always, 32))
    }

    // ---- the screen ----

    @Test
    fun `a hit that did not sound does not flare`() {
        val r = ring(OrbitHit(0, 1, everyLaps = 4, onLap = 0))
        val s = set(r)
        val period = OrbitClock.periodFrames(s, r)
        // Halfway through lap 0, which its condition names: a fresh strike.
        assertEquals(period / 2, OrbitClock.framesSinceFiring(s, r, (r.content as PatternOrbit).hits[0], period / 2))
        // Halfway through lap 2, which it does not: the last strike was
        // two laps back, which is far past any flare.
        val since = OrbitClock.framesSinceFiring(s, r, (r.content as PatternOrbit).hits[0], period * 2 + period / 2)
        assertEquals(period * 2 + period / 2, since)
    }

    @Test
    fun `a hit whose roll came up short reports never`() {
        val r = ring(OrbitHit(0, 1, chance = 0))
        val s = set(r)
        val period = OrbitClock.periodFrames(s, r)
        assertEquals(OrbitClock.NEVER, OrbitClock.framesSinceFiring(s, r, (r.content as PatternOrbit).hits[0], period * 3))
    }
}

/**
 * The vocabulary the screen edits with.
 *
 * It lives here rather than in `:app` because `:app` joins the Gradle
 * build only where an Android SDK exists — nothing in it is covered by a
 * JVM test, and "what does the second long-press do" is a question worth
 * an answer that runs.
 */
class OrbitBrushTest {

    private val hit = OrbitHit(0, 1)

    @Test
    fun `the weight brush is what a long-press always did`() {
        assertEquals(OrbitBrush.WEIGHT, OrbitBrush.entries.first())
        assertEquals(
            OrbitPatterns.nextVelocity(hit.velocity),
            OrbitBrushes.cycle(hit, OrbitBrush.WEIGHT).velocity,
        )
    }

    @Test
    fun `every ladder comes round to where it started`() {
        for (brush in OrbitBrush.entries) {
            var h = hit
            repeat(
                when (brush) {
                    OrbitBrush.WEIGHT -> 3
                    OrbitBrush.CHANCE -> OrbitBrushes.CHANCES.size
                    OrbitBrush.EVERY -> OrbitBrushes.CONDITIONS.size
                    OrbitBrush.RATCHET -> OrbitBrushes.RATCHETS.size
                },
            ) { h = OrbitBrushes.cycle(h, brush) }
            assertEquals(hit, h, "$brush did not come round")
        }
    }

    @Test
    fun `a value off the ladder climbs on rather than sticking`() {
        // 37% is reachable through the file and no rung offers it; a
        // long-press must still move it somewhere.
        val odd = OrbitHit(0, 1, chance = 37, everyLaps = 3, onLap = 2, ratchet = 7)
        assertEquals(OrbitBrushes.CHANCES.first(), OrbitBrushes.cycle(odd, OrbitBrush.CHANCE).chance)
        assertEquals(1, OrbitBrushes.cycle(odd, OrbitBrush.EVERY).everyLaps)
        assertEquals(OrbitBrushes.RATCHETS.first(), OrbitBrushes.cycle(odd, OrbitBrush.RATCHET).ratchet)
    }

    @Test
    fun `every rung is a hit a ring will accept`() {
        for (c in OrbitBrushes.CHANCES) OrbitHit(0, 1, chance = c)
        for ((every, on) in OrbitBrushes.CONDITIONS) OrbitHit(0, 1, everyLaps = every, onLap = on)
        for (r in OrbitBrushes.RATCHETS) OrbitHit(0, 1, ratchet = r)
    }

    @Test
    fun `a plain hit has nothing to say and a loaded one says all of it`() {
        assertEquals("", OrbitBrushes.mark(hit))
        assertEquals("25%", OrbitBrushes.mark(hit.copy(chance = 25)))
        assertEquals("2:4", OrbitBrushes.mark(hit.copy(everyLaps = 4, onLap = 1)))
        assertEquals("×3", OrbitBrushes.mark(hit.copy(ratchet = 3)))
        assertEquals(
            "50% 1:2 ×2",
            OrbitBrushes.mark(hit.copy(chance = 50, everyLaps = 2, onLap = 0, ratchet = 2)),
        )
    }

    @Test
    fun `a crowded square moves together, and only in the one thing`() {
        val loud = OrbitHit(0, 1, velocity = 1f, chance = 50, ratchet = 3)
        val quiet = OrbitHit(0, 1, velocity = 0.4f, offset = -60, length = 480)
        val took = OrbitBrushes.adopt(quiet, loud, OrbitBrush.CHANCE)
        assertEquals(50, took.chance)
        // Everything the brush does not name is the quiet hit's own.
        assertEquals(quiet.velocity, took.velocity)
        assertEquals(quiet.offset, took.offset)
        assertEquals(quiet.length, took.length)
        assertEquals(quiet.ratchet, took.ratchet)
    }

    @Test
    fun `the brush chip comes round`() {
        var b = OrbitBrush.WEIGHT
        repeat(OrbitBrush.entries.size) { b = b.next }
        assertEquals(OrbitBrush.WEIGHT, b)
    }
}

/**
 * What a conditional does to the two ends of the export.
 *
 * A cycle is "how long until the set says the same thing again", and a
 * conditional is the first thing that can make that longer than the rings
 * themselves. Both the clip's length and the refusal that caps it are
 * counted from it, so both had to learn.
 */
class OrbitConditionalClipTest {

    private fun ring(name: String, steps: Int, vararg hits: OrbitHit) =
        Orbit(name, steps, PatternOrbit("kit", hits.toList()))

    @Test
    fun `the clip is as long as the conditional makes it`() {
        val plain = OrbitSet(listOf(ring("R", 16, OrbitHit(0, 1))), 120f, 48_000)
        assertEquals(1, OrbitClip.bars(plain))
        val every4 = OrbitSet(listOf(ring("R", 16, OrbitHit(0, 1, everyLaps = 4, onLap = 2))), 120f, 48_000)
        assertEquals(4, OrbitClip.bars(every4))
        // And the clip actually holds the one note, on the third bar.
        val notes = OrbitClip.clip(every4).notes
        assertEquals(1, notes.size)
        assertEquals(2 * Mpc3Clip.PULSES_PER_BAR, notes[0].timePulses)
    }

    @Test
    fun `a cycle made long by a conditional is refused, and says so`() {
        // An 8-step ring on one lap in eight repeats every 64 steps, and
        // two coprime rings beside it push the meeting to 2,240 sixteenths
        // - 140 bars, well past the ceiling.
        val long = OrbitSet(
            listOf(
                ring("A", 8, OrbitHit(0, 1, everyLaps = 8, onLap = 0)),
                ring("B", 7, OrbitHit(0, 2)),
                ring("C", 5, OrbitHit(0, 3)),
            ),
            120f,
            48_000,
        )
        val why = OrbitClip.refusal(long)
        assertTrue(why != null && why.contains("CONDITIONAL"), "the refusal said: $why")
        // The same rings without the conditional fit easily, so the
        // conditional is what the message is talking about.
        val short = OrbitSet(
            listOf(ring("A", 8, OrbitHit(0, 1)), ring("B", 7, OrbitHit(0, 2)), ring("C", 5, OrbitHit(0, 3))),
            120f,
            48_000,
        )
        assertEquals(null, OrbitClip.refusal(short))
    }
}

/**
 * Round 1 of review, all eight findings reproduced before they were fixed.
 *
 * Six of them were one mistake in two shapes: `!certain` and `chance = 0`
 * were read as "the seed decides this" and "this hit counts", and neither
 * is true. `OrbitHit.rolled` and `OrbitHit.neverSounds` are those two
 * readings named, so the callers stop inventing them.
 */
class OrbitChanceReviewTest {

    private fun ring(steps: Int, vararg h: OrbitHit) = Orbit("R", steps, PatternOrbit("kit", h.toList()))
    private fun set(vararg o: Orbit) = OrbitSet(o.toList(), 120f, 48_000)

    @Test
    fun `uncertain is not the same as rolled`() {
        // A hit at 100% on one lap in two does not sound every lap, and no
        // seed in the world changes which laps those are.
        val conditional = OrbitHit(0, 1, everyLaps = 2, onLap = 0)
        assertTrue(!conditional.certain)
        assertTrue(!conditional.rolled, "a certain conditional is not something a seed decides")
        // Nor is a hit that never sounds.
        assertTrue(!OrbitHit(0, 1, chance = 0).rolled)
        assertTrue(OrbitHit(0, 1, chance = 0).neverSounds)
        // Only a chance strictly between the two.
        assertTrue(OrbitHit(0, 1, chance = 1).rolled)
        assertTrue(OrbitHit(0, 1, chance = 99).rolled)
        assertTrue(!OrbitHit(0, 1, chance = OrbitHit.ALWAYS).rolled)
    }

    @Test
    fun `a hit that never sounds stretches no cycle and explains no refusal`() {
        // Before: a silenced hit carrying a 1-in-8 made a one-bar ring
        // claim 128 steps, which is eight bars of clip.
        val dead = set(ring(16, OrbitHit(0, 1, chance = 0, everyLaps = 8, onLap = 0), OrbitHit(4, 1)))
        assertEquals(16L, OrbitClock.cycleSteps(dead))
        assertEquals(1, OrbitClip.bars(dead))
        // And the one it can be heard on still counts.
        val live = set(ring(16, OrbitHit(0, 1, chance = 25, everyLaps = 8, onLap = 0)))
        assertEquals(128L, OrbitClock.cycleSteps(live))
    }

    @Test
    fun `a refusal names a conditional only where one can be heard`() {
        // Rings long enough to refuse on their own, so the message is the
        // only thing under test.
        fun rings(hit: OrbitHit) = OrbitSet(
            listOf(
                Orbit("A", 64, PatternOrbit("kit", listOf(hit))),
                Orbit("B", 63, PatternOrbit("kit", listOf(OrbitHit(0, 2)))),
                Orbit("C", 61, PatternOrbit("kit", listOf(OrbitHit(0, 3)))),
            ),
            120f,
            48_000,
        )
        val heard = OrbitClip.refusal(rings(OrbitHit(0, 1, everyLaps = 2, onLap = 0)))
        assertTrue(heard != null && heard.contains("CONDITIONAL"), "said: $heard")
        val silent = OrbitClip.refusal(rings(OrbitHit(0, 1, chance = 0, everyLaps = 2, onLap = 0)))
        assertTrue(silent != null && !silent.contains("CONDITIONAL"), "said: $silent")
    }

    @Test
    fun `the flare fades from the latest strike, not the first`() {
        // A 4-step free ring at 120 BPM, 48 kHz: its step is one 16th,
        // 6,000 frames, so a ratchet of four strikes 1,500 apart and the
        // ring comes round every 24,000. Anchored to the first strike, the
        // fourth read as 4,500 frames old at the instant it was heard.
        val r = ring(4, OrbitHit(0, 1, ratchet = 4))
        val s = OrbitSet(listOf(r), 120f, 48_000)
        val hit = (r.content as PatternOrbit).hits[0]
        for (at in OrbitClock.firings(s, r, 0L, OrbitClock.periodFrames(s, r))) {
            assertEquals(0L, OrbitClock.framesSinceFiring(s, r, hit, at.frame), "stale flare at frame ${at.frame}")
        }
        // And it still counts up between strikes rather than resetting:
        // 100 frames past the fourth strike, and 1,600 past it again once
        // the ring has run out of strikes to offer.
        assertEquals(100L, OrbitClock.framesSinceFiring(s, r, hit, 4_600))
        assertEquals(1_600L, OrbitClock.framesSinceFiring(s, r, hit, 6_100))
    }

    @Test
    fun `a stray onLap with no everyLaps is ignored rather than refusing the file`() {
        val dir = Files.createTempDirectory("orbit-stray").toFile()
        java.io.File(dir, OrbitStore.FILE_NAME).writeText(
            """
            {"version":6,"bpm":90.0,"lapSteps":16,"swing":50,"sampleRate":48000,"orbits":[
              {"name":"R","steps":16,"span":"FREE","voice":[1],"engaged":true,"level":1.0,"pan":0.0,
               "content":{"type":"pattern","kit":"kit","hits":[{"step":0,"slot":1,"velocity":1.0,"onLap":3}]}}
            ]}
            """.trimIndent(),
        )
        // The writer never makes such a file; reading the stray field
        // anyway threw IllegalArgumentException and lost the whole set.
        val hit = (OrbitStore.load(dir).orbits[0].content as PatternOrbit).hits.single()
        assertEquals(OrbitHit.EVERY_LAP, hit.everyLaps)
        assertEquals(0, hit.onLap)
        assertTrue(hit.certain)
    }

    @Test
    fun `a long-press announces the brush it will move`() {
        assertEquals("CHANGE WEIGHT", OrbitBrush.WEIGHT.action)
        for (b in OrbitBrush.entries) assertTrue(b.action.contains(b.label), "${b.action} does not name $b")
    }
}
