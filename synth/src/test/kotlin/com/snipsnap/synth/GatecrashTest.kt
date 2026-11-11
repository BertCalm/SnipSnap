package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.synth.GatecrashKits.Kit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GATECRASH's gesture and the kits' contracts, measured on real renders. Each gesture test also runs the
 * stage with the mechanism under test taken away, so the test fails if the mechanism stops doing the work.
 */
class GatecrashTest {
    private val rate = Dsp.RATE

    private fun db(x: Double) = 20 * log10(max(x, 1e-9))

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        var n = 0
        for (i in max(from, 0) until min(to, x.size)) { sum += x[i].toDouble() * x[i]; n++ }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    private fun frames(ms: Float) = (ms * 0.001f * rate).toInt()

    /** Level of the return early in the hold and at its end, dB. */
    private fun plateau(stages: Gatecrash.Stages, space: Gatecrash.Space): Pair<Double, Double> {
        val a = stages.attacks.first()
        // 50 ms: a 20 ms window holds barely two cycles of a 115 Hz tom, and read its beating as level.
        val window = frames(50f)
        return db(rms(stages.ret, a + frames(30f), a + frames(30f) + window)) to
            db(rms(stages.ret, a + frames(space.holdMs) - window, a + frames(space.holdMs)))
    }

    /** How unlike two renders are once level is taken out: 0 for the same shape, about 1.4 for unrelated ones. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        val ea = sqrt(a.sumOf { it.toDouble() * it })
        val eb = sqrt(b.sumOf { it.toDouble() * it })
        var d = 0.0
        for (i in 0 until n) { val v = a[i] / ea - b[i] / eb; d += v * v }
        return sqrt(d)
    }

    /** Share of a render's energy above [hz], by a one-pole split: a plain brightness measure. */
    private fun brightness(x: FloatArray, hz: Float = 2_000f): Double {
        val lp = Dsp.FixedOnePole(hz)
        var high = 0.0
        var all = 0.0
        for (v in x) { val h = v - lp.lp(v); high += h.toDouble() * h; all += v.toDouble() * v }
        return high / max(all, 1e-18)
    }

    private val toms = 6..11

    /** Renders are pure, so the tests share them: each (kit, pad, velocity, take) is rendered once. */
    private fun rendered(kit: Kit, role: Int, velocity: Float, take: Int): com.snipsnap.audio.Snip =
        renders.getOrPut(listOf(kit, role, velocity, take)) { GatecrashKits.render(kit, role, velocity, take) }

    private companion object {
        val renders = java.util.concurrent.ConcurrentHashMap<List<Any>, com.snipsnap.audio.Snip>()
    }

    @Test fun `every pad renders the same bytes twice`() {
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.PADS) for (v in listOf(.3f, 1f)) {
            // One side comes from this file's cache, the other is rendered now: a second, independent run.
            assertContentEquals(
                rendered(kit, role, v, 1).samples, GatecrashKits.render(kit, role, v, 1).samples,
                "${kit.name} role $role v $v")
        }
    }

    @Test fun `every pad is finite bounded centred and ends in silence`() {
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.PADS) for (v in GatecrashKits.velocities) {
            val x = rendered(kit, role, v, 0).samples
            val what = "${kit.name} role $role v $v"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val peak = x.maxOf { abs(it) }
            assertTrue(peak <= .98f + 1e-6f && peak > .2f, "$what peak $peak")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-4f, "$what tail")
        }
    }

    @Test fun `every zone is levelled to one peak and no take runs far from it`() {
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.PADS) for (v in GatecrashKits.velocities) {
            // Level is the sampler's job, so a soft zone is not also rendered quieter.
            assertEquals(GatecrashKits.PAD_PEAK, rendered(kit, role, v, 0).peak(), .005f, "${kit.name} role $role v $v")
            for (take in 1 until GatecrashKits.TAKES) {
                val peak = rendered(kit, role, v, take).peak()
                assertTrue(peak in .6f..(.98f + 1e-6f), "${kit.name} role $role v $v take $take peak $peak")
            }
        }
    }

    @Test fun `the crush holds the stone room flat where the bare room decays`() {
        val space = assertNotNull(GatecrashKits.spaceOf(Kit.STONE_ROOM, 6))
        // A 5 ms burst of noise, so the room is left alone after the hit. (A ringing tom keeps feeding its
        // room for the whole hold, which holds the level up with or without a compressor.)
        val noise = Dsp.Noise(Dsp.seedFor("GATECRASH", "test", "click"))
        val click = FloatArray(rate / 2) { if (it in 100 until 100 + frames(5f)) noise.next() else 0f }
        val (early, late) = plateau(Gatecrash.process(click, space), space)
        // The same room with the threshold lifted to 6 dB under the room's first level: the compressor lets
        // go almost at once and the room decays as rooms do.
        val (bareEarly, bareLate) = plateau(Gatecrash.process(click, space.copy(crush = 0f)), space)
        val held = abs(early - late)
        val fell = bareEarly - bareLate
        assertTrue(held < 3.0, "the crushed room moved $held dB across the hold")
        assertTrue(fell > held + 5.0, "the bare room fell $fell dB against the crushed room's $held dB")
        // And on the kit's own toms the held room stays within 4 dB from 30 ms to the end of the hold.
        for (role in toms) {
            val tomSpace = assertNotNull(GatecrashKits.spaceOf(Kit.STONE_ROOM, role))
            val (a, z) = plateau(assertNotNull(GatecrashKits.stages(Kit.STONE_ROOM, role, 1f, 0)), tomSpace)
            assertTrue(abs(a - z) < 4.0, "tom $role: held room moved ${abs(a - z)} dB")
        }
    }

    @Test fun `the gate cuts the stone room to silence`() {
        for (role in listOf(1) + toms) {
            val space = assertNotNull(GatecrashKits.spaceOf(Kit.STONE_ROOM, role))
            val stages = assertNotNull(GatecrashKits.stages(Kit.STONE_ROOM, role, 1f, 0))
            val a = stages.attacks.first()
            val shut = a + frames(space.holdMs + space.closeMs) + frames(5f)
            val held = rms(stages.ret, a + frames(space.holdMs) - frames(20f), a + frames(space.holdMs))
            val after = rms(stages.ret, shut, shut + frames(20f))
            assertTrue(db(after) < db(held) - 60.0, "role $role: ${db(held)} dB held, ${db(after)} dB after the gate")
            // The gate is the only thing between a held room and silence: with a long hold it is still there.
            val open = Gatecrash.process(GatecrashKits.dryRaw(Kit.STONE_ROOM, role, 1f, 0), space.copy(holdMs = 1000f))
            assertTrue(db(rms(open.ret, shut, shut + frames(20f))) > db(held) - 20.0, "role $role: the room outlasts the gate")
        }
    }

    private fun energy(x: FloatArray) = x.sumOf { it.toDouble() * it }

    @Test fun `every room carries its authored energy against the drum and none buries it`() {
        // Round 1 set the room against the drum's peak and it came out 14 to 20 dB over the drum in energy:
        // the owner heard static. The amount is now energy against energy, and capped.
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.PADS) {
            val space = GatecrashKits.spaceOf(kit, role) ?: continue
            val stages = assertNotNull(GatecrashKits.stages(kit, role, 1f, 0))
            val measured = 10 * log10(energy(stages.ret) / energy(GatecrashKits.dryRaw(kit, role, 1f, 0)))
            assertEquals(space.roomDb.toDouble(), measured, 0.3, "${kit.name} role $role room energy")
            assertTrue(space.roomDb <= 4f, "${kit.name} role $role is authored at ${space.roomDb} dB")
        }
        assertTrue(runCatching { Gatecrash.Space(Gatecrash.Mode.STONE, roomDb = 12f) }.isFailure)
    }

    @Test fun `no room is brighter than its drum`() {
        // Round 2 required every room to have under 0.6 of its drum's share above 4 kHz, to keep round 1's
        // static away. The owner then heard round 3 as missing brightness, so that ratio was too strict:
        // the static was mostly level, which `every room carries its authored energy` holds. What stays is
        // the direction: a far microphone never hears more top than the drum has.
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.HITS) {
            val space = GatecrashKits.spaceOf(kit, role) ?: continue
            // A room 7 dB or more under its drum cannot colour the pad, whatever its tone.
            if (space.roomDb <= -7f) continue
            val drum = brightness(GatecrashKits.dryRaw(kit, role, 1f, 0), 4_000f)
            if (drum < .05) continue
            val room = brightness(assertNotNull(GatecrashKits.stages(kit, role, 1f, 0)).ret, 4_000f)
            assertTrue(room < drum, "${kit.name} role $role: room $room against drum $drum above 4 kHz")
        }
    }

    @Test fun `every room rounds off rather than chops`() {
        // The owner on round 2: 80s drums round off at the end rather than cut abruptly. Round 2 closed in
        // 10 to 25 ms. No space may now close faster than 45 ms, and the fall is smooth: the return never
        // drops more than 3 dB in a millisecond on the way down.
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.HITS) {
            val space = GatecrashKits.spaceOf(kit, role) ?: continue
            assertTrue(space.closeMs >= 45f, "${kit.name} role $role closes in ${space.closeMs} ms")
            if (space.mode != Gatecrash.Mode.STONE) continue
            val gate = assertNotNull(GatecrashKits.stages(kit, role, 1f, 0)).gate
            val step = frames(1f)
            for (i in step until gate.size) if (gate[i] > .05f) {
                assertTrue(gate[i] >= gate[i - step] * .708f, "${kit.name} role $role: the gate fell ${gate[i - step]} to ${gate[i]} in 1 ms")
            }
        }
    }

    @Test fun `the drum is still most of what a pad starts with`() {
        // In the first 15 ms after the attack the dry drum must outweigh its room on every pad.
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.HITS) {
            val stages = GatecrashKits.stages(kit, role, 1f, 0) ?: continue
            val a = stages.attacks.first()
            val dry = GatecrashKits.dryRaw(kit, role, 1f, 0)
            assertTrue(rms(dry, a, a + frames(15f)) > rms(stages.ret, a, a + frames(15f)), "${kit.name} role $role")
        }
    }

    @Test fun `a ringing drum is one attack and a phrase is one attack per note`() {
        // The floor tom rings at about 100 Hz for 400 ms. Its own cycles must not re-open the gate.
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.HITS) {
            val stages = GatecrashKits.stages(kit, role, 1f, 0) ?: continue
            assertEquals(1, stages.attacks.size, "${kit.name} role $role attacks ${stages.attacks}")
        }
        // The stone fill has twelve notes at twelve distinct times; the builds have sixteen.
        assertEquals(12, assertNotNull(GatecrashKits.stages(Kit.STONE_ROOM, 14, 1f, 0)).attacks.size)
        for (kit in Kit.entries) assertEquals(16, assertNotNull(GatecrashKits.stages(kit, 15, 1f, 0)).attacks.size, kit.name)
    }

    @Test fun `a fill shares one room that stays open between notes and shuts after the last`() {
        val space = assertNotNull(GatecrashKits.spaceOf(Kit.STONE_ROOM, 14))
        val stages = assertNotNull(GatecrashKits.stages(Kit.STONE_ROOM, 14, 1f, 0))
        val first = stages.attacks.first()
        val last = stages.attacks.last()
        var lowest = 1f
        for (i in first + frames(5f) until last) lowest = min(lowest, stages.gate[i])
        assertEquals(1f, lowest, 1e-4f, "the gate dipped to $lowest inside the fill")
        val shut = last + frames(space.holdMs + space.closeMs) + frames(2f)
        assertEquals(0f, stages.gate[shut], 1e-6f)
        // A gap longer than the hold does close it: two hits a second apart are two rooms.
        val two = FloatArray(rate * 2).also { it[100] = 1f; it[100 + rate] = 1f }
        val gate = Gatecrash.process(two, space).gate
        assertEquals(0f, gate[100 + rate - 200], 1e-6f)
    }

    @Test fun `a burst does not decay and then stops`() {
        for (role in listOf(1, 4, 6, 7, 8, 9)) {
            val space = assertNotNull(GatecrashKits.spaceOf(Kit.MACHINE_POP, role))
            assertEquals(Gatecrash.Mode.BURST, space.mode)
            val stages = assertNotNull(GatecrashKits.stages(Kit.MACHINE_POP, role, 1f, 0))
            val a = stages.attacks.first()
            val third = frames(space.holdMs) / 3
            val start = db(rms(stages.ret, a + frames(15f), a + frames(15f) + third))
            val end = db(rms(stages.ret, a + frames(space.holdMs) - third, a + frames(space.holdMs)))
            assertTrue(end > start - 3.0, "role $role: burst fell from $start to $end dB")
            val shut = a + frames(space.holdMs + space.closeMs) + frames(70f)
            val after = db(rms(stages.ret, shut, shut + frames(20f)))
            assertTrue(after < end - 40.0, "role $role: $end dB at the end of the burst, $after dB after it")
        }
    }

    @Test fun `the return is bounded by the soft clip whatever goes in`() {
        // Not a peak taken from the render: the bound is the clip's own ceiling, 1 over tanh of the drive
        // gain, times the send. The band-limiter and the decimator ring on a clipped edge, so the ceiling is
        // allowed 1.6 times over (the worst measured is 1.43, the stone snare).
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.PADS) {
            val space = GatecrashKits.spaceOf(kit, role) ?: continue
            val bound = space.room / tanh(Gatecrash.driveGain(space.drive)) * 1.6f
            val dry = GatecrashKits.dryRaw(kit, role, 1f, 0)
            for (scale in listOf(1f, 8f)) {
                val loud = FloatArray(dry.size) { dry[it] * scale }
                val ret = Gatecrash.process(loud, space).ret
                assertTrue(ret.all { it.isFinite() && abs(it) <= bound }, "${kit.name} role $role x$scale: ${ret.maxOf { abs(it) }} > $bound")
            }
        }
    }

    @Test fun `the soft clip does not fold harmonics back into the audio band`() {
        // 5 kHz driven hard. Its 7th harmonic is 35 kHz, which a clip at 44.1 kHz would fold to 9.1 kHz.
        val n = rate / 2
        val x = FloatArray(n) { (2.0 * sin(2 * PI * 5_000.0 * it / rate)).toFloat() }
        fun level(y: FloatArray, hz: Double): Double {
            var re = 0.0
            var im = 0.0
            for (i in n / 4 until n) { re += y[i] * cos(2 * PI * hz * i / rate); im += y[i] * sin(2 * PI * hz * i / rate) }
            return sqrt(re * re + im * im)
        }
        val clean = Gatecrash.softClip(x, 1f)
        val g = Gatecrash.driveGain(1f)
        val naive = FloatArray(n) { tanh(x[it] * g) / tanh(g) }
        val folded = db(level(clean, 9_100.0) / level(clean, 5_000.0))
        val naiveFolded = db(level(naive, 9_100.0) / level(naive, 5_000.0))
        assertTrue(folded < -60.0, "folded 7th harmonic at $folded dB")
        assertTrue(naiveFolded > folded + 20.0, "the probe sees folding when there is some: $naiveFolded dB")
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.PADS) {
            val hard = rendered(kit, role, 1f, 0).samples
            val soft = rendered(kit, role, .3f, 0).samples
            // A quieter copy would measure 0 here once level is taken out.
            val distance = shapeDistance(hard, soft)
            assertTrue(distance > .05, "${kit.name} role $role: soft is a scaled copy of hard ($distance)")
        }
        // On single hits the soft strike is also duller, not just different.
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.HITS) {
            val hard = brightness(GatecrashKits.dry(kit, role, 1f, 0).samples)
            val soft = brightness(GatecrashKits.dry(kit, role, .3f, 0).samples)
            assertTrue(soft < hard, "${kit.name} role $role: soft $soft is not duller than hard $hard")
        }
    }

    @Test fun `a softer hit gets less room for its level`() {
        for (role in toms) {
            val space = assertNotNull(GatecrashKits.spaceOf(Kit.STONE_ROOM, role))
            fun roomOverDry(v: Float): Double {
                val s = assertNotNull(GatecrashKits.stages(Kit.STONE_ROOM, role, v, 0))
                val a = s.attacks.first()
                var room = 0f
                for (i in a + frames(30f) until a + frames(space.holdMs)) room = max(room, abs(s.ret[i]))
                return db(room.toDouble()) - db(GatecrashKits.touch(v).toDouble())
            }
            assertTrue(roomOverDry(.3f) < roomOverDry(1f) - 1.5, "tom $role: ${roomOverDry(.3f)} against ${roomOverDry(1f)} dB")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (kit in Kit.entries) for (role in 0 until GatecrashKits.PADS) for (v in GatecrashKits.velocities) {
            val takes = List(GatecrashKits.TAKES) { rendered(kit, role, v, it).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val distance = shapeDistance(takes[a], takes[b])
                // The same audio measures 0. The closest pair in the kits is the stone kick's soft takes at
                // 0.009: SKIN re-strikes it, but a soft kick's strikes are nearly alike, and round 2's
                // quieter room no longer adds to the difference. That pair is 41 dB down, disclosed as weak.
                assertTrue(distance > .005, "${kit.name} role $role v $v: takes $a and $b differ by only $distance")
            }
        }
    }

    @Test fun `the three kits are different kits`() {
        val names = Kit.entries.map { GatecrashKits.roles(it) }
        for (roles in names) { assertEquals(GatecrashKits.PADS, roles.size); assertEquals(roles.size, roles.toSet().size) }
        assertEquals(3, names.toSet().size)
        assertEquals(3, Kit.entries.map { it.bpm }.toSet().size)
        // Two mechanisms and a dry kick: the spaces are not one space at three strengths.
        // No cymbal has a space in any kit: a held room on a hat is hiss.
        for (kit in Kit.entries) for (role in 2..3) assertNull(GatecrashKits.spaceOf(kit, role), "${kit.name} hat $role")
        assertTrue((0 until GatecrashKits.PADS).mapNotNull { GatecrashKits.spaceOf(Kit.STONE_ROOM, it) }.all { it.mode == Gatecrash.Mode.STONE })
        assertTrue((0 until GatecrashKits.PADS).mapNotNull { GatecrashKits.spaceOf(Kit.MACHINE_POP, it) }.all { it.mode == Gatecrash.Mode.BURST })
        assertNull(GatecrashKits.spaceOf(Kit.ELECTRO_FUNK, 0))
        // The kick and the main snare are different drums in each kit, dry, before any space.
        for (role in 0..1) {
            val drums = Kit.entries.map { GatecrashKits.dry(it, role, 1f, 0).samples }
            for (a in drums.indices) for (b in a + 1 until drums.size) {
                assertTrue(shapeDistance(drums[a], drums[b]) > .5, "role $role: kits $a and $b share a drum")
            }
        }
        // Pad roles differ too: the stone kit has six toms, the others four and three.
        assertEquals(listOf(6, 4, 3), Kit.entries.map { kit -> GatecrashKits.build(kit).count { it.drumClass == DrumClass.TOM } })
    }

    @Test fun `the stone toms fall in pitch from the first to the floor tom`() {
        val hz = toms.map { role ->
            assertNotNull(com.snipsnap.audio.Pitch.detect(GatecrashKits.dry(Kit.STONE_ROOM, role, 1f, 0), fromSec = .03f, windowSec = .2f)).hz
        }
        for (i in 1 until hz.size) assertTrue(hz[i] < hz[i - 1], "tom pitches $hz")
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (kit in Kit.entries) {
            val pads = GatecrashKits.build(kit)
            assertEquals(GatecrashKits.PADS, pads.size)
            assertEquals(listOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN), pads.take(4).map { it.drumClass })
            assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), pads.takeLast(2).map { it.drumClass })
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == GatecrashKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(GatecrashKits.roles(kit)[role], pad.displayName)
                assertEquals("GATECRASH", pad.source["engine"])
                assertEquals(kit.name, pad.source["kit"])
                assertEquals(GatecrashKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(Gatecrash.VERSION.toString(), pad.source["stageVersion"])
                val hat = pad.drumClass == DrumClass.HAT_CLOSED || pad.drumClass == DrumClass.HAT_OPEN
                assertEquals(if (hat) 1 else if (role >= GatecrashKits.HITS) 2 else 0, pad.muteGroup)
                // A phrase is filed as a loop, and the classifier's own threshold agrees with its length.
                if (role >= GatecrashKits.HITS) assertTrue(pad.snip.frameCount > 1.5 * rate)
                else assertTrue(pad.snip.frameCount < 1.5 * rate, "${kit.name} role $role is ${pad.snip.frameCount} frames")
            }
        }
    }

    @Test fun `no pad or kit name borrows a maker's name`() {
        for (kit in Kit.entries) for (name in GatecrashKits.roles(kit) + kit.title + kit.id) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
            assertFalse(Regex("(?i)collins|genesis|gabriel|townhouse|ssl|ams|rmx|padgham|linn|simmons").containsMatchIn(name), name)
        }
    }

    @Test fun `a space refuses values outside its range`() {
        for (bad in listOf(Float.NaN, -0.1f, 2f)) {
            assertTrue(runCatching { Gatecrash.Space(Gatecrash.Mode.STONE, crush = bad) }.isFailure, "crush $bad")
        }
        assertTrue(runCatching { Gatecrash.Space(Gatecrash.Mode.STONE, roomDb = Float.NaN) }.isFailure)
        assertTrue(runCatching { Gatecrash.Space(Gatecrash.Mode.STONE, toneHz = 50f) }.isFailure)
        assertTrue(runCatching { Gatecrash.Space(Gatecrash.Mode.STONE, holdMs = Float.NaN) }.isFailure)
        assertTrue(runCatching { Gatecrash.process(FloatArray(100), Gatecrash.Space(Gatecrash.Mode.STONE), touch = 0f) }.isFailure)
        // Silence in, silence out, in both modes.
        for (mode in Gatecrash.Mode.entries) assertTrue(Gatecrash.process(FloatArray(4000), Gatecrash.Space(mode)).out.all { it == 0f })
    }
}
