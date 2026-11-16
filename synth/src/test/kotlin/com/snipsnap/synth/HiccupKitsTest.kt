package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.synth.HiccupKits.Kit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The HICCUP kits' grid: ScuffKitsTest's probes on kits rendered by the engine itself. */
class HiccupKitsTest {
    private val rate = Dsp.RATE

    private fun rendered(kit: Kit, role: Int, velocity: Float, take: Int) =
        renders.getOrPut(listOf(kit, role, velocity, take)) { HiccupKits.render(kit, role, velocity, take) }

    private companion object {
        val renders = java.util.concurrent.ConcurrentHashMap<List<Any>, com.snipsnap.audio.Snip>()
    }

    /** Distance between two renders once level is taken out: the same audio measures 0. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        val ea = sqrt(a.sumOf { it.toDouble() * it })
        val eb = sqrt(b.sumOf { it.toDouble() * it })
        var d = 0.0
        for (i in 0 until n) { val v = a[i] / ea - b[i] / eb; d += v * v }
        return sqrt(d)
    }

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    @Test fun `every pad renders the same bytes twice`() {
        for (kit in Kit.entries) for (role in 0 until HiccupKits.PADS) {
            assertContentEquals(rendered(kit, role, 1f, 1).samples, HiccupKits.render(kit, role, 1f, 1).samples, "${kit.name} role $role")
        }
    }

    @Test fun `every pad is finite bounded centred and ends in silence`() {
        var worstDc = 0.0
        for (kit in Kit.entries) for (role in 0 until HiccupKits.PADS) for (v in HiccupKits.velocities) {
            val x = rendered(kit, role, v, 0).samples
            val what = "${kit.name} role $role v $v"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val p = peak(x)
            assertTrue(p <= .98f + 1e-6f && p > .2f, "$what peak $p")
            worstDc = maxOf(worstDc, abs(x.average()))
            assertTrue(abs(x.average()) < .008, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-4f, "$what tail")
        }
        println("HICCUP kits measured: worst first-take DC offset over 3 kits x 16 pads x 3 zones = $worstDc")
    }

    @Test fun `every zone is levelled to one peak and no take runs far from it`() {
        for (kit in Kit.entries) for (role in 0 until HiccupKits.PADS) for (v in HiccupKits.velocities) {
            assertEquals(HiccupKits.PAD_PEAK, peak(rendered(kit, role, v, 0).samples), .005f, "${kit.name} role $role v $v")
            for (take in 1 until HiccupKits.TAKES) {
                val p = peak(rendered(kit, role, v, take).samples)
                assertTrue(p in .5f..(.98f + 1e-6f), "${kit.name} role $role v $v take $take peak $p")
            }
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        var smallest = Double.MAX_VALUE
        for (kit in Kit.entries) for (role in 0 until HiccupKits.PADS) {
            val d = shapeDistance(rendered(kit, role, 1f, 0).samples, rendered(kit, role, .3f, 0).samples)
            smallest = minOf(smallest, d)
            assertTrue(d > .05, "${kit.name} role $role: soft is a scaled copy of hard ($d)")
        }
        println("HICCUP kits measured: smallest peak-normalized soft-against-hard distance = $smallest")
    }

    @Test fun `every zone holds four different strikes`() {
        var smallest = Double.MAX_VALUE
        for (kit in Kit.entries) for (role in 0 until HiccupKits.PADS) for (v in HiccupKits.velocities) {
            val takes = List(HiccupKits.TAKES) { rendered(kit, role, v, it).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val d = shapeDistance(takes[a], takes[b])
                smallest = minOf(smallest, d)
                assertTrue(d > .005, "${kit.name} role $role v $v: takes $a and $b differ by only $d")
            }
        }
        println("HICCUP kits measured: smallest peak-normalized take-against-take distance in a zone = $smallest")
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        val expectedClasses = mapOf(
            Kit.HOP to listOf(HiccupVoice.KICK, HiccupVoice.SNARE, HiccupVoice.HAT_CLOSED, HiccupVoice.HAT_OPEN, HiccupVoice.CLAP, HiccupVoice.TOM, HiccupVoice.TOM,
                HiccupVoice.KICK, HiccupVoice.SNARE, HiccupVoice.SNARE, HiccupVoice.BLIP, HiccupVoice.SQUEAK, HiccupVoice.KICK, HiccupVoice.HAT_CLOSED),
            Kit.LAB to listOf(HiccupVoice.KICK, HiccupVoice.SNARE, HiccupVoice.HAT_CLOSED, HiccupVoice.HAT_OPEN, HiccupVoice.CLAP, HiccupVoice.TOM, HiccupVoice.TOM,
                HiccupVoice.KICK, HiccupVoice.SNARE, HiccupVoice.BLIP, HiccupVoice.BLIP, HiccupVoice.SQUEAK, HiccupVoice.KICK, HiccupVoice.HAT_OPEN),
            Kit.SUGAR to listOf(HiccupVoice.KICK, HiccupVoice.SNARE, HiccupVoice.HAT_CLOSED, HiccupVoice.HAT_OPEN, HiccupVoice.CLAP, HiccupVoice.TOM, HiccupVoice.TOM,
                HiccupVoice.KICK, HiccupVoice.SNARE, HiccupVoice.BLIP, HiccupVoice.BLIP, HiccupVoice.SQUEAK, HiccupVoice.SQUEAK, HiccupVoice.CLAP),
        )
        val expectedNames = mapOf(
            Kit.HOP to listOf("Lurch Kick", "Trip Snare", "Tick Hat", "Skid Hat", "Stagger Clap", "Sag Tom", "Hop Tom", "Clean Kick", "Clean Snare", "Rewind Snare", "Zip", "Squeeze Toy", "Buzz Kick", "Dust Hat", "Trip Roll", "Stagger Riser"),
            Kit.LAB to listOf("Relay Kick", "Shard Snare", "Pin Hat", "Spray Hat", "Packet Clap", "Null Tom", "Bit Tom", "Sub Thud", "Comb Snare", "Morse", "Scan", "Chirp", "Grain Kick", "Static", "Shard Roll", "Morse Riser"),
            Kit.SUGAR to listOf("Candy Kick", "Pop Snare", "Sprinkle Hat", "Fizz Hat", "Sticker Clap", "Bounce Tom", "Boing Tom", "Donk Kick", "Sparkle Snare", "Coin", "Laser", "Squeaky", "Giggle", "Glitter Clap", "Pop Roll", "Coin Riser"),
        )
        for (kit in Kit.entries) {
            val pads = HiccupKits.build(kit)
            assertEquals(HiccupKits.PADS, pads.size)
            assertEquals(expectedNames.getValue(kit), HiccupKits.roles(kit))
            assertEquals(expectedClasses.getValue(kit).map { Hiccup.drumClassFor(it) } + listOf(DrumClass.LOOP, DrumClass.LOOP), pads.map { it.drumClass })
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == HiccupKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(expectedNames.getValue(kit)[role], pad.displayName)
                assertEquals("HICCUP", pad.source["engine"])
                assertEquals(kit.name, pad.source["kit"])
                assertEquals(HiccupKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(Hiccup.MODEL_VERSION.toString(), pad.source["model"])
                assertEquals(role.toString(), pad.source["role"])
                assertEquals(kit.bpm.toString(), pad.source["bpm"])
                assertEquals(HiccupKits.velocities.joinToString(","), pad.source["velocities"])
                assertEquals(HiccupKits.TAKES.toString(), pad.source["takes"])
                assertEquals(if (role == 2 || role == 3) 1 else if (role >= HiccupKits.HITS) 2 else 0, pad.muteGroup, "${kit.name} role $role mute group")
                if (role >= HiccupKits.HITS) assertTrue(pad.snip.frameCount > 1.5 * rate, "${kit.name} role $role is a loop")
                else assertTrue(pad.snip.frameCount < 1.5 * rate, "${kit.name} role $role is ${pad.snip.frameCount} frames")
            }
        }
    }

    @Test fun `the phrases are one bar of fresh strikes, finite and as long as SCUFF's one-bar phrases`() {
        val seconds = StringBuilder()
        for (kit in Kit.entries) for (role in HiccupKits.HITS until HiccupKits.PADS) {
            val raw = HiccupKits.phraseRaw(kit, role, 1f, 0)
            assertTrue(raw.all { it.isFinite() })
            // ScuffKits sizes a phrase as its bars at the kit's tempo plus half a second.
            val bar = (Dsp.RATE * 60.0 / kit.bpm / 4 * 16).toInt() + Dsp.RATE / 2
            assertEquals(bar, raw.size, "${kit.name} role $role")
            val snip = rendered(kit, role, 1f, 0)
            assertTrue(snip.frameCount <= bar, "${kit.name} role $role")
            seconds.append(" ${kit.name}/$role=${"%.3f".format(snip.frameCount.toDouble() / rate)}")
        }
        println("HICCUP kits measured: phrase pad seconds (one bar plus half a second at most):$seconds")
    }

    @Test fun `the phrases get hotter across the bar`() {
        // The riser's CHOP rises, so its repeat count rises; the roll's FAULT rises from 0, so its first strike has no event.
        for (kit in Kit.entries) {
            val roll = HiccupKits.phrases(kit)[0]
            val part = HiccupKits.parts(kit)[roll.part]
            assertEquals(0f, roll.sweep(part.macros, 0f).getValue("FAULT"))
            assertEquals(part.macros.getValue("FAULT"), roll.sweep(part.macros, 1f).getValue("FAULT"))
            val riser = HiccupKits.phrases(kit)[1]
            val rp = HiccupKits.parts(kit)[riser.part]
            assertEquals(.15f, riser.sweep(rp.macros, 0f).getValue("CHOP"), 1e-6f)
            assertEquals(1f, riser.sweep(rp.macros, 1f).getValue("CHOP"), 1e-6f)
            assertEquals(minOf(1f, rp.macros.getValue("TUNE") + .2f), riser.sweep(rp.macros, 1f).getValue("TUNE"), 1e-6f)
        }
    }

    @Test fun `the three kits are different kits`() {
        val counts = StringBuilder()
        for (a in Kit.entries.indices) for (b in a + 1 until Kit.entries.size) {
            val x = Kit.entries[a]; val y = Kit.entries[b]
            val different = (0 until HiccupKits.PADS).count { role ->
                !rendered(x, role, 1f, 0).samples.contentEquals(rendered(y, role, 1f, 0).samples)
            }
            counts.append(" ${x.name}/${y.name}=$different")
            assertTrue(different >= 12, "${x.name} and ${y.name} share pads: only $different of 16 differ")
            assertFalse(rendered(x, 0, 1f, 0).samples.contentEquals(rendered(y, 0, 1f, 0).samples), "${x.name} and ${y.name} share a kick")
            assertFalse(rendered(x, 1, 1f, 0).samples.contentEquals(rendered(y, 1, 1f, 0).samples), "${x.name} and ${y.name} share a snare")
        }
        println("HICCUP kits measured: pads (of 16) whose hard first take differs between kit pairs:$counts")
        assertEquals(Kit.entries.size, Kit.entries.map { it.bpm }.toSet().size, "each kit has its own tempo")
        for (kit in Kit.entries) assertEquals(HiccupKits.PADS, HiccupKits.roles(kit).toSet().size)
    }

    /** Seconds from the start to the last sample above 30 dB under the pad's peak. */
    private fun secondsTo30Db(x: FloatArray): Double {
        val floor = peak(x) * Math.pow(10.0, -30.0 / 20.0).toFloat()
        var last = 0
        for (i in x.indices) if (abs(x[i]) > floor) last = i
        return last.toDouble() / rate
    }

    @Test fun `the backbone pads at top velocity ring no longer than six sixteenths`() {
        val line = StringBuilder()
        for (kit in Kit.entries) {
            val sixteenth = 60.0 / kit.bpm / 4
            var longest = 0.0
            for (role in listOf(0, 1, 2, 4)) for (take in 0 until HiccupKits.TAKES) {
                val steps = secondsTo30Db(rendered(kit, role, 1f, take).samples) / sixteenth
                longest = maxOf(longest, steps)
                assertTrue(steps <= 6.0, "${kit.name} ${HiccupKits.roles(kit)[role]} take $take rings ${"%.2f".format(steps)} sixteenths")
            }
            line.append(" ${kit.name}=${"%.2f".format(longest)}")
        }
        println("HICCUP kits measured: longest backbone pad (kick, snare, closed hat, clap; 4 takes) to -30 dB, in sixteenths:$line")
    }

    @Test fun `every pad's CHOP is a tempo subdivision of its kit`() {
        for (kit in Kit.entries) for ((i, part) in HiccupKits.parts(kit).withIndex()) {
            val slice = Hiccup.chopSlice(part.macros.getValue("CHOP").toDouble())
            val note = 240.0 / kit.bpm / slice
            val div = Math.rint(kotlin.math.ln(note) / kotlin.math.ln(2.0)).toInt()
            assertTrue(div in 2..9, "${kit.name} $i ${part.name} is 1/$note")
            // Inside the map's reach the slice is exactly a power-of-two note value; at the top of it, the nearest the map reaches.
            val exact = 240.0 / kit.bpm / (1 shl div)
            if (exact in 0.0016..0.110) assertEquals(exact, slice, exact * 1e-3, "${kit.name} ${part.name}")
        }
    }

    @Test fun `no pad or kit name borrows a maker's name`() {
        for (kit in Kit.entries) for (name in HiccupKits.roles(kit) + kit.title + kit.id) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
        }
    }
}
