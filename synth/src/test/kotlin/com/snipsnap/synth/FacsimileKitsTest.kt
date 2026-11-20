package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.FacsimileKits.Kit
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FACSIMILE's four kits: the layout as heard, velocity as a different strike, round robin as different strikes. */
class FacsimileKitsTest {
    private val rate = Dsp.RATE

    private fun rendered(kit: Kit, role: Int, velocity: Float, take: Int): Snip =
        renders.getOrPut(listOf(kit, role, velocity, take)) { FacsimileKits.render(kit, role, velocity, take) }

    private companion object {
        val renders = java.util.concurrent.ConcurrentHashMap<List<Any>, Snip>()
    }

    @Test fun `every kit lays out its sixteen heard pads, grooves last, then its roll`() {
        for (kit in Kit.entries) {
            val names = FacsimileKits.roles(kit)
            assertEquals(FacsimileKits.PADS, names.size, kit.name)
            assertEquals(names.size, names.toSet().size, "${kit.name}: repeated pad names")
            for (name in names + kit.title) assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
            val grooves = (0 until FacsimileKits.PADS).filter { FacsimileKits.isGroove(kit, it) }
            assertTrue(grooves.isNotEmpty(), kit.name)
            assertEquals((FacsimileKits.HEARD - grooves.size until FacsimileKits.HEARD).toList(), grooves, "${kit.name}: grooves sit last of the heard pads")
            assertEquals(FacsimileKits.ROLL, FacsimileKits.HEARD, "the roll is the pad after them")
            assertEquals(DrumClass.LOOP, FacsimileKits.classOf(kit, FacsimileKits.ROLL))
            for (g in grooves) assertEquals(DrumClass.LOOP, FacsimileKits.classOf(kit, g))
        }
        assertEquals(listOf(90, 113, 127, 134), Kit.entries.map { it.bpm })
    }

    @Test fun `a groove is its reference loop's length to the frame and loops`() {
        val kit = Kit.KNOCK
        val role = (0 until FacsimileKits.PADS).first { FacsimileKits.isGroove(kit, it) }
        val x = rendered(kit, role, 1f, 0).samples
        assertEquals(FacsimileKits.grooveFrames("113"), x.size)
        assertEquals(Math.round(4 * 4 * 60.0 / 113 * rate).toInt(), x.size, "four bars at 113 BPM")
        assertTrue(x.size / rate.toFloat() > 1.5f, "the classifier files it as a loop")
        // the wrap: the step from the last frame to the first is no larger than the steps inside the loop
        val inside = (1 until x.size).maxOf { abs(x[it] - x[it - 1]) }
        assertTrue(abs(x[0] - x.last()) <= inside, "loop seam ${abs(x[0] - x.last())} against $inside")
    }

    @Test fun `the roll speeds up as it swells and lands on the kick and snare`() {
        for (kit in Kit.entries) {
            val notes = FacsimileKits.rollNotes(kit)
            val gaps = notes.zipWithNext { a, b -> b.beat - a.beat }.dropLast(1)
            assertTrue(gaps.zipWithNext().all { (a, b) -> b < a }, "${kit.name}: every gap tighter than the last")
            assertEquals(.5, gaps.first(), 1e-9)
            assertTrue(gaps.last() < .15, "${kit.name}: it ends near thirty-seconds (${gaps.last()} beats)")
            assertTrue(notes.zipWithNext().all { (a, b) -> b.velocity >= a.velocity }, "${kit.name}: it swells")
            val landing = notes.last()
            assertEquals(FacsimileKits.ROLL_BEATS, landing.beat)
            assertTrue(FacsimileKits.kickPad(kit).layers.all { it in landing.layers }, "${kit.name}: the kick lands with it")
            val x = rendered(kit, FacsimileKits.ROLL, 1f, 0).samples
            assertTrue(x.size / rate.toFloat() > 1.5f, "${kit.name}: filed as a phrase")
        }
    }

    @Test fun `every pad renders the same bytes twice`() {
        for (kit in listOf(Kit.KNOCK)) for (role in listOf(0, 5, 9)) {
            assertContentEquals(FacsimileKits.render(kit, role, .65f, 2).samples, FacsimileKits.render(kit, role, .65f, 2).samples, "${kit.name} $role")
        }
    }

    /**
     * Over the zone's four takes: a single take can come out brighter, because some models carry noise in the same
     * band as a partial and the two add or cancel with each take's noise (the HALF-TIME Punch Kick's take 0 measured
     * 934 Hz soft against 892 Hz hard; the Python prototype's own take 0 measured 687 against 998, the same model with
     * other noise). Across the takes a sampler deals in turn, a softer zone is never brighter.
     */
    @Test fun `a softer zone is a different strike and never brighter`() {
        for (kit in Kit.entries) for (role in 0 until FacsimileKits.PADS) {
            if (FacsimileKits.isGroove(kit, role)) continue
            val hard = rendered(kit, role, 1f, 0).samples
            val soft = rendered(kit, role, .3f, 0).samples
            val cs = (0 until FacsimileKits.TAKES).map { onsetCentroid(rendered(kit, role, .3f, it).samples) }.average()
            val ch = (0 until FacsimileKits.TAKES).map { onsetCentroid(rendered(kit, role, 1f, it).samples) }.average()
            assertTrue(cs <= ch * 1.005, "${kit.name} ${FacsimileKits.roles(kit)[role]}: soft onset centroid $cs Hz against hard $ch Hz, over the takes")
            assertTrue(shapeDistance(hard, soft) > .02, "${kit.name} ${FacsimileKits.roles(kit)[role]}: the soft zone is the hard one turned down")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (kit in Kit.entries) for (role in 0 until FacsimileKits.PADS) for (v in FacsimileKits.velocities) {
            if (FacsimileKits.isGroove(kit, role) && v != 1f) continue   // a groove's other zones: the next test
            val takes = List(FacsimileKits.TAKES) { rendered(kit, role, v, it).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                assertTrue(shapeDistance(takes[a], takes[b]) > .005, "${kit.name} ${FacsimileKits.roles(kit)[role]} v$v: takes $a and $b alike")
            }
        }
    }

    @Test fun `a groove's soft zone plays softer strikes, not a quieter copy`() {
        val kit = Kit.SHUFFLE
        val role = (0 until FacsimileKits.PADS).first { FacsimileKits.isGroove(kit, it) }
        val hard = rendered(kit, role, 1f, 0).samples
        val soft = rendered(kit, role, .3f, 0).samples
        assertTrue(shapeDistance(hard, soft) > .02, "groove zones alike")
        assertTrue(brightness(soft) < brightness(hard), "the soft groove is not duller")
    }

    /** Set FACSIMILE_DUMP to a folder to write every kit's hard first take per pad and every groove, with timings. */
    @Test fun `dump kits for listening and the Python harness`() {
        val dir = System.getenv("FACSIMILE_DUMP") ?: return
        for (kit in Kit.entries) {
            val out = File(dir, kit.id).apply { mkdirs() }
            val began = System.nanoTime()
            for (role in 0 until FacsimileKits.PADS) for (v in FacsimileKits.velocities) for (t in 0 until FacsimileKits.TAKES) {
                val s = rendered(kit, role, v, t)
                if (t == 0) WavWriter.write(File(out, "pad-%02d-v%03d.wav".format(role + 1, (v * 100).toInt())), s)
            }
            println("${kit.name}: 16 pads x 12 renders in ${(System.nanoTime() - began) / 1_000_000} ms")
        }
    }

    // ---------------------------------------------------------------- probes (LooseCrateKitsTest's)

    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        var ea = 0.0; var eb = 0.0
        for (i in 0 until n) { ea += a[i].toDouble() * a[i]; eb += b[i].toDouble() * b[i] }
        ea = sqrt(max(ea, 1e-18)); eb = sqrt(max(eb, 1e-18))
        var d = 0.0
        for (i in 0 until n) { val v = a[i] / ea - b[i] / eb; d += v * v }
        return sqrt(d)
    }

    private fun brightness(x: FloatArray, hz: Float = 2_000f): Double {
        val lp = Dsp.FixedOnePole(hz)
        var high = 0.0; var all = 0.0
        for (v in x) { val h = v - lp.lp(v); high += h.toDouble() * h; all += v.toDouble() * v }
        return high / max(all, 1e-18)
    }

    private fun onsetCentroid(x: FloatArray): Double {
        val peak = x.maxOf { abs(it) }
        val on = x.indexOfFirst { abs(it) > peak * .05f }
        val n = min((.05f * rate).toInt(), x.size - on)
        var num = 0.0; var den = 0.0
        for (k in 1 until n / 2) {
            var re = 0.0; var im = 0.0
            val w = 2 * Math.PI * k / n
            for (i in 0 until n) { val hann = .5 - .5 * Math.cos(2 * Math.PI * i / n); re += x[on + i] * hann * Math.cos(w * i); im -= x[on + i] * hann * Math.sin(w * i) }
            val mag = sqrt(re * re + im * im)
            num += mag * k * rate / n
            den += mag
        }
        return num / max(den, 1e-18)
    }
}
