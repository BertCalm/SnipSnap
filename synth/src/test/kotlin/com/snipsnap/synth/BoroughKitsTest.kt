package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.BoroughKits.Kit
import com.snipsnap.synth.BoroughKits.Stack
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The BOROUGH grids: the prototype's own checks (prototype/borough/verify.py) on the Kotlin kits, plus determinism,
 * layout and the kit-plus-stack contract. Each kit's drums and each stack are built once (their pads render in
 * parallel); every kit with every stack is then put together from them, and one is built whole to prove that the
 * whole build is the same bytes.
 */
class BoroughKitsTest {

    private companion object {
        val drums: Map<Kit, List<ArrangedPad>> by lazy { Kit.entries.associateWith { BoroughKits.drumPads(it) } }
        /** Each stack built in the kit that plays it by default (a stack's level and tails depend on its kit). */
        val home = mapOf(Stack.TAMBOURINE to Kit.STOOP, Stack.HANDS to Kit.CELLAR, Stack.TOPS to Kit.CRANKED, Stack.BELLS to Kit.WAX)
        val stacks: Map<Stack, List<ArrangedPad>> by lazy { Stack.entries.associateWith { BoroughKits.stackPads(home.getValue(it), it) } }
        fun all(): List<Pair<String, ArrangedPad>> =
            drums.flatMap { (k, pads) -> pads.mapIndexed { i, p -> "${k.name} A%02d".format(i + 1) to p } } +
                stacks.flatMap { (s, pads) -> pads.mapIndexed { i, p -> "${s.name} A%02d".format(13 + i) to p } }
    }

    private fun d(x: FloatArray) = DoubleArray(x.size) { x[it].toDouble() }

    /** verify.resid_db: the gain-matched residual of [b] against [a], dB under [a]. Copies of one strike measure far under -100. */
    private fun residDb(aF: FloatArray, bF: FloatArray): Double {
        val n = min(aF.size, bF.size)
        val a = d(aF).copyOf(n); val b = d(bF).copyOf(n)
        val g = a.indices.sumOf { a[it] * b[it] } / max(b.sumOf { it * it }, 1e-20)
        val r = sqrt(a.indices.sumOf { (a[it] - g * b[it]).let { e -> e * e } })
        return 20 * log10(max(r, 1e-20) / max(sqrt(a.sumOf { it * it }), 1e-20))
    }

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    @Test fun `a built pad is the same bytes as its strikes rendered one by one, twice`() {
        for ((kit, role) in listOf(Kit.STOOP to 0, Kit.CELLAR to 1, Kit.WAX to 2, Kit.DRY to 8, Kit.RIMS to 3, Kit.CRANKED to 11)) {
            for (z in 0 until 3) for (t in 0 until BoroughKits.TAKES) {
                val one = BoroughKits.renderDrum(kit, role, z, t).samples
                assertContentEquals(one, drums.getValue(kit)[role].velocityTakes[z][t].samples, "${kit.name} role $role zone $z take $t")
                if (z == 2) assertContentEquals(one, BoroughKits.renderDrum(kit, role, z, t).samples, "${kit.name} role $role zone $z take $t again")
            }
        }
        for ((st, i) in listOf(Stack.HANDS to 0, Stack.BELLS to 3)) for (t in 0 until BoroughKits.TAKES) {
            assertContentEquals(BoroughKits.renderStack(home.getValue(st), st, i, 2, t).samples, stacks.getValue(st)[i].velocityTakes[2][t].samples, "${st.name} pad $i take $t")
        }
    }

    /** verify.py: finite, peak at most 0.9, |DC| under 0.005, the last 10 ms quiet (rms under 0.003). */
    @Test fun `every take is finite bounded centred and ends in silence`() {
        var worstTail = 0.0
        for ((what, pad) in all()) for ((z, takes) in pad.velocityTakes.withIndex()) for ((t, s) in takes.withIndex()) {
            val x = s.samples
            val w = "$what zone $z take $t"
            assertTrue(x.all { it.isFinite() }, "$w finite")
            assertTrue(peak(x) <= .9f, "$w peak ${peak(x)}")
            assertTrue(abs(x.average()) < .005, "$w DC ${x.average()}")
            val tail = sqrt(x.takeLast(441).sumOf { it.toDouble() * it } / 441)
            worstTail = max(worstTail, tail)
            assertTrue(tail < .003, "$w tail rms $tail")
            assertEquals(1, s.channels); assertEquals(44_100, s.sampleRate)
        }
        println("BOROUGH worst final-10 ms rms: $worstTail")
    }

    /** The level law (0.89 v^0.8, the SOUL ROCK law) and the kit's balance: every take peaks exactly where the prototype put it. */
    @Test fun `every take peaks at the zone's level times the kit's trim`() {
        for ((kit, pads) in drums) for ((role, pad) in pads.withIndex()) for ((z, takes) in pad.velocityTakes.withIndex()) {
            val want = 0.89 * BoroughKits.velocities[z].toDouble().pow(0.8) * BoroughKits.trimOf(kit, role)
            for (s in takes) assertEquals(want, peak(s.samples).toDouble(), 1e-4, "${kit.name} role $role zone $z")
        }
        for ((st, pads) in stacks) for ((i, pad) in pads.withIndex()) for ((z, takes) in pad.velocityTakes.withIndex()) {
            val want = 0.89 * BoroughKits.velocities[z].toDouble().pow(0.8) * BoroughKits.stackTrimOf(home.getValue(st))
            for (s in takes) assertEquals(want, peak(s.samples).toDouble(), 1e-4, "${st.name} pad $i zone $z")
        }
        assertEquals(0.0, Kit.STOOP.recipe.kickTrimDb); assertEquals(-4.29, Kit.STOOP.recipe.restTrimDb)
        assertEquals(-10.16, Kit.WAX.recipe.restTrimDb)
    }

    /**
     * Round 3.1 (owner: "C for all except rides and rims", 2026-11-21; prototype/borough/fuller.py). Round 3's stacks
     * were untrimmed (each peaked at 0.89, up to 10 dB over its kit's snare) and rang 0.5 to 1.4 s, filling the space
     * between hits. Now a stack's hard hit peaks exactly where its kit's snare does, its strike is the recipe's own for
     * the hold, and it decays to -30 dB by its tail and ends by -60 dB. The drums do not change.
     */
    @Test fun `a stack sits at its kit's level and stops sooner`() {
        val tail = Borough.recipes.stackTail
        assertEquals(30.0, tail.holdMs); assertEquals(250.0, tail.tailMs); assertEquals(600.0, tail.longTailMs)
        assertEquals(setOf("BELL HI", "BELL LO", "RIDE", "RIDE BELL"), tail.longLabels)
        for ((kit, st, i) in listOf(Triple(Kit.WAX, Stack.BELLS, 3), Triple(Kit.STOOP, Stack.TOPS, 1), Triple(Kit.DRY, Stack.HANDS, 1),
            Triple(Kit.RIMS, Stack.BELLS, 1), Triple(Kit.CELLAR, Stack.TAMBOURINE, 2))) {
            val label = BoroughKits.stackName(st, i)
            val tailMs = tail.tailMsOf(label)
            val w = "${kit.name} ${st.name} $label"
            for (z in 0 until 3) {
                val raw = BoroughKits.renderStack(st, i, z, 0).samples
                val y = BoroughKits.renderStack(kit, st, i, z, 0).samples
                val g = BoroughKits.stackTrimOf(kit)
                val hold = (tail.holdMs * 44.1).toInt()
                for (n in 0 until hold) assertEquals(raw[n] * g, y[n].toDouble(), 1e-6, "$w zone $z: the strike is the recipe's own")
                assertTrue(y.size <= ((tail.holdMs + 2 * (tailMs - tail.holdMs)) * 44.1).toInt() + 1, "$w zone $z ends by -60 dB: ${y.size}")
                val after = (tailMs * 44.1).toInt()
                if (after < y.size) assertTrue(peak(y.copyOfRange(after, y.size)) <= peak(y) * 10.0.pow(-30.0 / 20) + 1e-6, "$w zone $z: -30 dB by $tailMs ms")
            }
            val snare = BoroughKits.renderDrum(kit, 1, 2, 0).samples
            assertEquals(peak(snare).toDouble(), peak(BoroughKits.renderStack(kit, st, i, 2, 0).samples).toDouble(), 1e-4, "$w: as loud as the kit's snare")
        }
    }

    /**
     * The owner's velocity gate asks for a different strike, not a quieter one. verify.py's measure: the soft zone's
     * first take against the hard zone's, gain matched, leaves a residual above -20 dB. The prototype's 88 pads: all
     * above, the closest -17.1 dB.
     */
    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        var closest = Double.NEGATIVE_INFINITY to ""
        for ((what, pad) in all()) {
            val r = residDb(pad.velocityTakes.last()[0].samples, pad.velocityTakes.first()[0].samples)
            if (closest.second.isEmpty() || r < closest.first) closest = r to what
            assertTrue(r > -20.0, "$what: soft against hard leaves only $r dB once level is matched")
        }
        println("BOROUGH soft against hard, closest: ${closest.first} dB (${closest.second})")
    }

    /**
     * Takes are distinct strikes: every pair in every zone leaves a gain-matched residual. Copies of one strike measure
     * under -100 dB. The prototype's own closest pair, over all 88 pads, zones and pairs, is -34.5 dB (CRANKED's KICK 2,
     * whose boom keeps its phase from take to take); the bar sits under it at -40 dB.
     */
    @Test fun `every zone holds three different strikes`() {
        var closest = 0.0 to ""
        for ((what, pad) in all()) for ((z, takes) in pad.velocityTakes.withIndex()) {
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val r = residDb(takes[a].samples, takes[b].samples)
                if (closest.second.isEmpty() || r < closest.first) closest = r to "$what zone $z takes $a/$b"
                assertTrue(r > -40.0, "$what zone $z: takes $a and $b differ by only $r dB")
            }
        }
        println("BOROUGH closest takes: ${closest.first} dB (${closest.second})")
    }

    @Test fun `every kit goes with every stack and the drums never move`() {
        for (kit in Kit.entries) for (st in Stack.entries) {
            val bank = BoroughKits.combine(drums.getValue(kit), stacks.getValue(st))
            assertEquals(BoroughKits.PADS, bank.size)
            for (i in 0 until BoroughKits.DRUM_PADS) assertTrue(bank[i] === drums.getValue(kit)[i])
            for (i in 0 until BoroughKits.STACK_PADS) {
                assertTrue(bank[12 + i] === stacks.getValue(st)[i])
                assertEquals(BoroughKits.stackName(st, i).lowercase(), bank[12 + i].displayName!!.lowercase())
                assertEquals((13 + i).toString(), bank[12 + i].source["slot"])
            }
        }
        // A whole build, a kit with a stack it does not default to, is the same bytes as the parts.
        val whole = BoroughKits.build(Kit.RIMS, Stack.HANDS)
        val parts = BoroughKits.combine(drums.getValue(Kit.RIMS), BoroughKits.stackPads(Kit.RIMS, Stack.HANDS))
        for ((a, b) in whole.zip(parts)) {
            assertEquals(a.displayName, b.displayName)
            for ((za, zb) in a.velocityTakes.zip(b.velocityTakes)) for ((ta, tb) in za.zip(zb)) assertContentEquals(ta.samples, tb.samples)
        }
    }

    @Test fun `each kit's default stack is the one the owner heard`() {
        assertEquals(mapOf(Kit.STOOP to Stack.TAMBOURINE, Kit.CELLAR to Stack.HANDS, Kit.CRANKED to Stack.TOPS, Kit.WAX to Stack.BELLS,
            Kit.DRY to Stack.HANDS, Kit.RIMS to Stack.BELLS), Kit.entries.associateWith { it.defaultStack })
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for ((kit, pads) in drums) {
            assertEquals(BoroughKits.DRUM_PADS, pads.size)
            assertEquals(listOf(DrumClass.KICK, DrumClass.SNARE), listOf(pads[0].drumClass, pads[1].drumClass), kit.name)
            assertEquals(DrumClass.KICK, pads[4].drumClass); assertEquals(DrumClass.SNARE, pads[5].drumClass); assertEquals(DrumClass.SNARE, pads[8].drumClass)
            assertEquals(listOf(DrumClass.TOM, DrumClass.TOM), listOf(pads[9].drumClass, pads[10].drumClass), kit.name)
            assertEquals(DrumClass.HAT_CLOSED, pads[7].drumClass, "${kit.name}: A08 the pedal hat")
            if (kit == Kit.RIMS) {
                assertEquals(listOf("Ride", "Ride Bell"), listOf(pads[2].displayName, pads[3].displayName))
                assertEquals(listOf(0, 0), listOf(pads[2].muteGroup, pads[3].muteGroup), "the ride rings under the pedal hat")
            } else {
                assertEquals(listOf(DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN), listOf(pads[2].drumClass, pads[3].drumClass), kit.name)
                assertEquals(setOf(2, 3, 7), pads.indices.filter { pads[it].muteGroup == BoroughKits.HAT_GROUP }.toSet(), "${kit.name}: one hi-hat")
            }
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == BoroughKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals("BOROUGH", pad.source["engine"]); assertEquals(kit.name, pad.source["kit"])
                assertEquals(BoroughKits.VERSION.toString(), pad.source["kitVersion"]); assertEquals(Borough.MODEL_VERSION.toString(), pad.source["model"])
                assertEquals((role + 1).toString(), pad.source["slot"])
            }
        }
        for ((st, pads) in stacks) {
            assertEquals(BoroughKits.STACK_PADS, pads.size)
            for (pad in pads) {
                assertEquals("BOROUGH", pad.source["engine"]); assertEquals(st.name, pad.source["stack"]); assertEquals(0, pad.muteGroup)
                assertEquals(home.getValue(st).name, pad.source["kit"]); assertEquals(BoroughKits.VERSION.toString(), pad.source["kitVersion"])
            }
        }
        assertEquals(DrumClass.CLAP, stacks.getValue(Stack.TOPS)[0].drumClass)
    }

    /** The percussion gate: every kit carries its finite snare roll on A12 (EMBER's press roll, tuned to the kit's snare). */
    @Test fun `every kit carries a snare roll`() {
        for (kit in Kit.entries) {
            val pad = kit.recipe.pads[11]
            assertEquals(EmberVoice.PRESS_ROLL, pad.voice, kit.name)
            assertTrue(drums.getValue(kit)[11].snip.frameCount > 2 * 44_100, "${kit.name}: the roll runs past two seconds")
        }
    }

    /**
     * The same slot is never one sound in two kits, level matched (verify.resid_db). The accepted recipes' closest pair is
     * STOOP's and RIDE AND RIMS' low tom (both EMBER's floor tom at TUNE 1, through different chains): -10.3 dB in the
     * prototype. The designed drums all differ by more.
     */
    @Test fun `the six kits are six kits`() {
        var closest = 0.0 to ""
        for (a in Kit.entries.indices) for (b in a + 1 until Kit.entries.size) {
            val ka = Kit.entries[a]; val kb = Kit.entries[b]
            for (role in 0 until BoroughKits.DRUM_PADS) {
                val r = residDb(drums.getValue(ka)[role].snip.samples, drums.getValue(kb)[role].snip.samples)
                if (closest.second.isEmpty() || r < closest.first) closest = r to "${ka.name}/${kb.name} A%02d".format(role + 1)
                assertTrue(r > -20.0, "${ka.name} and ${kb.name} role $role are one sound ($r dB)")
                if (Kit.STOOP.recipe.pads[role].kind in setOf(Borough.Kind.KICK, Borough.Kind.SNARE, Borough.Kind.GHOST))
                    assertTrue(r > -8.0, "${ka.name} and ${kb.name} designed drum $role too alike ($r dB)")
            }
        }
        println("BOROUGH closest slot across kits: ${closest.first} dB (${closest.second})")
        assertEquals(6, Kit.entries.map { it.id }.toSet().size)
        assertEquals(Borough.recipes.kits.map { it.id }, Kit.entries.map { it.id }, "every recipe kit is a kit, in order")
        assertEquals(Borough.recipes.stacks.map { it.id }.toSet(), Stack.entries.map { it.id }.toSet())
    }

    @Test fun `the recipes are the exported prototype pad for pad`() {
        // Spot checks against recipes.json (prototype/borough/export_recipes.py).
        val stoop = Kit.STOOP.recipe
        assertEquals(Borough.Kind.KICK, stoop.pads[0].kind); assertEquals(55.9877, stoop.pads[0].params.getValue("f1"))
        assertEquals(Borough.Kind.GHOST, stoop.pads[8].kind); assertEquals(stoop.pads[1].params, stoop.pads[8].params)
        assertEquals(EmberVoice.HAT_OPEN, stoop.pads[3].voice); assertEquals(.981f, stoop.pads[3].macros.getValue("TUNE"))
        assertEquals(EmberVoice.RIDE_BELL, Kit.RIMS.recipe.pads[3].voice)
        val rim = Kit.CELLAR.recipe.pads[6]
        assertEquals(EmberVoice.CROSS_STICK, rim.voice); assertEquals(.2726f, rim.macros.getValue("DISTANCE")); assertEquals(6.0, rim.chain!!.tilt)
        val shaker = Stack.HANDS.recipe.pads[0]
        assertEquals(.875, shaker.tune); assertEquals(.94849, shaker.ratio); assertTrue(shaker.resample)
        assertFalse(Stack.TAMBOURINE.recipe.pads[3].resample)
        assertEquals(.25, Borough.recipes.stackChain.distance); assertEquals(listOf(.35f, .65f, 1f), BoroughKits.velocities); assertEquals(3, BoroughKits.TAKES)
    }

    /**
     * Round 3.1's live glue, as pinned in recipes.json by glue_recipe.py: the owner's "C for all except rides and
     * rims" (2026-11-21) is saturation on five kits and none on RIDE AND RIMS; every kit the same compressor and ceiling.
     */
    @Test fun `each kit carries the live glue the owner picked`() {
        val expected = mapOf(
            Kit.STOOP to Triple(0.79f, 2.7f, 7.13f), Kit.CELLAR to Triple(-1.29f, 0.0f, 7.67f), Kit.CRANKED to Triple(-1.81f, 6.0f, 7.0f),
            Kit.WAX to Triple(0.63f, 6.0f, 7.0f), Kit.DRY to Triple(-2.67f, 6.0f, 7.0f), Kit.RIMS to Triple(-2.37f, null, 7.0f),
        )
        for ((kit, e) in expected) {
            val g = BoroughKits.glueOf(kit)
            assertEquals(com.snipsnap.kit.KitGlue(e.first, e.second, -10f, 4f, 0f, e.third, -1f), g, kit.name)
        }
        assertEquals(listOf(Kit.RIMS), Kit.entries.filter { BoroughKits.glueOf(it).saturationDb == null }, "rides and rims: no saturator")
    }

    @Test fun `a recipe kit without its glue is refused`() {
        val text = javaClass.getResourceAsStream("/borough/v1/recipes.json")!!.bufferedReader().use { it.readText() }
        val stripped = com.snipsnap.json.Json.write(com.snipsnap.json.Json.parse(text).let { root ->
            val o = root.obj().toMutableMap()
            o["kits"] = com.snipsnap.json.JsonValue.Arr(o.getValue("kits").arr().map { k -> com.snipsnap.json.JsonValue.Obj(k.obj() - "glue") })
            com.snipsnap.json.JsonValue.Obj(o)
        })
        kotlin.test.assertFails { Borough.parse(stripped) }
        assertEquals(6, Borough.parse(text).kits.size)
    }

    @Test fun `no kit stack or pad is named after a maker`() {
        val names = Kit.entries.map { it.title } + Stack.entries.map { it.title } +
            Kit.entries.flatMap { k -> (0 until 12).map { BoroughKits.drumName(k, it) } } + Stack.entries.flatMap { s -> (0 until 4).map { BoroughKits.stackName(s, it) } }
        for (name in names) assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
    }
}
