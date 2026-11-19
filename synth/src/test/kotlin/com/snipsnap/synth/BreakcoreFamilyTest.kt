package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BreakcoreFamilyTest {
    private val kits = listOf<BreakcoreKit>(Shatter, Soundclash, Redline)

    @Test fun `every pad has timbral velocity, fresh takes, and is deterministic`() {
        for (kit in kits) for (p in kit.layout.indices) {
            val name = "${kit.id} ${kit.layout[p].name}"
            val hard = kit.renderPad(p); val medium = kit.renderPad(p, .65); val soft = kit.renderPad(p, .3)
            assertContentEquals(hard.samples, kit.renderPad(p).samples, "$name is not deterministic")
            assertTrue(soft.peak() < medium.peak() && medium.peak() < hard.peak(), "$name velocity peaks ${soft.peak()} ${medium.peak()} ${hard.peak()}")
            val n = min(soft.samples.size, hard.samples.size)
            val distance = (0 until n).sumOf { (soft.samples[it] / soft.peak() - hard.samples[it] / hard.peak()).toDouble().pow(2) }
            assertTrue(distance > 1e-4, "$name velocity changes only gain")
            for (t in 1..2) assertFalse(hard.samples.contentEquals(kit.renderPad(p, 1.0, t).samples), "$name cloned take $t")
            assertTrue(hard.peak() in .25f..1f, "$name full-force peak ${hard.peak()}")
            assertTrue(abs(hard.samples.average()) < .005, "$name DC")
            assertTrue(hard.samples.last() == 0f, "$name tail")
            assertTrue(kit.renderPad(p, 0.0).samples.all { it == 0f }, "$name sounds at zero force")
        }
    }

    @Test fun `the family has four kits with distinct ids and tempos and eighteen pads each`() {
        val all = kits + Splinter
        assertEquals(4, all.map { it.id }.toSet().size)
        assertEquals(4, all.map { it.bpm }.toSet().size)
        assertTrue(all.all { it.layout.size == 18 && it.layout[16].kind == com.snipsnap.audio.DrumClass.LOOP && it.layout[17].kind == com.snipsnap.audio.DrumClass.LOOP })
    }

    @Test fun `shatter slices are windows of one take, so they carry what was sounding`() {
        // Slice One of the break (Ghost, a single ghost note) still has the ride and kick tail under it:
        // its first millisecond is not silence, unlike an isolated hit.
        val ghost = Shatter.renderPad(1).samples
        val head = (0 until 44).maxOf { abs(ghost[it]) }
        assertTrue(head > 1e-3, "the ghost slice starts in silence: no bleed")
        // Pads tile the two bars: the first fifteen pads end where the next begins.
        val beat = Breakcore.beat(Shatter.bpm)
        assertEquals(beat * 3 / 4, Shatter.renderPad(0).frameCount.toDouble(), .006 * Dsp.RATE)
    }

    @Test fun `redline kicks sit on their notes`() {
        // The tuned kicks' bodies, after the dive, hold root, minor third, fifth and octave.
        for ((pad, semis) in listOf(0 to 0, 1 to 3, 2 to 7, 3 to 12)) {
            val x = Redline.renderPad(pad, .3).samples.copyOfRange(Breakcore.frames(.06), Breakcore.frames(.3))
            val crossings = x.toList().zipWithNext().count { (a, b) -> a <= 0f && b > 0f }
            val hz = crossings / (x.size.toDouble() / Dsp.RATE)
            assertEquals(Redline.ROOT_HZ * 2.0.pow(semis / 12.0), hz, 6.0, "pad $pad")
        }
    }

    @Test fun `the spin-back runs backwards`() {
        // Rewind: after the grab the read speed is negative, so the output holds material from
        // before the grab point played in reverse; it must not be silent after the grab.
        val x = Soundclash.renderPad(9).samples
        val after = x.copyOfRange(Breakcore.frames(.5), Breakcore.frames(.8))
        assertTrue(after.maxOf { abs(it) } > .05f)
    }
}
