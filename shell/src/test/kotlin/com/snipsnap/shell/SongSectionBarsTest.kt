package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.ClipRef
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.PlanSection
import com.snipsnap.kit.SongPlan
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pass cut at a bar line is kept as a shorter section: KEEP THIS ORDER
 * ([Arranger.heardOrder]), the cursor that hears the cut ([SongCursor.heard])
 * and the mix that plays only the bars it keeps ([PlanSection.bars]).
 */
class SongSectionBarsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("secbars").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun plan(n: Int = 3, repeats: Int = 1) =
        SongPlan(0, (0 until n).map { PlanSection("s$it", ClipRef.CAPTURED, repeats, seed = it) })

    private val four = List(12) { 4 }

    private fun played(kept: SongPlan, vararg h: Arranger.Heard, clipBars: List<Int> = four) = Arranger.heardOrder(kept, clipBars, h.toList())!!

    // ---- heardOrder --------------------------------------------------------

    @Test
    fun `all full passes is what it always was`() {
        val kept = plan()
        val made = played(kept, Arranger.Heard(0), Arranger.Heard(0), Arranger.Heard(1))
        assertEquals(listOf(2, 1), made.plan.sections.map { it.repeats })
        assertEquals(listOf<Int?>(null, null), made.plan.sections.map { it.bars })
        // The old whole-pass entry point is the same song.
        assertEquals(made, Arranger.playedOrder(kept, four, listOf(0, 0, 1)))
        // A cut that is not shorter than the pass is a whole pass.
        assertEquals(made, Arranger.heardOrder(kept, four, listOf(Arranger.Heard(0, 4), Arranger.Heard(0, 9), Arranger.Heard(1))))
    }

    @Test
    fun `a cut pass at the end of a run splits off as its own shorter section`() {
        val made = played(plan(), Arranger.Heard(0), Arranger.Heard(0), Arranger.Heard(0, 2), Arranger.Heard(1))
        assertEquals(listOf(2, 1, 1), made.plan.sections.map { it.repeats })
        assertEquals(listOf<Int?>(null, 2, null), made.plan.sections.map { it.bars })
        assertEquals(listOf("s0", "s0", "s1"), made.plan.sections.map { it.name })
        assertEquals(0, made.left)
    }

    @Test
    fun `a cut mid-run ends the run before it and starts a new one after`() {
        val made = played(plan(), Arranger.Heard(0), Arranger.Heard(0, 1), Arranger.Heard(0), Arranger.Heard(0), Arranger.Heard(2))
        assertEquals(listOf(1, 1, 2, 1), made.plan.sections.map { it.repeats })
        assertEquals(listOf<Int?>(null, 1, null, null), made.plan.sections.map { it.bars })
    }

    @Test
    fun `a cut section copy keeps the section's own flags and seed`() {
        val kept = SongPlan(0, listOf(PlanSection("theme", ClipRef.TIGHT, 2, locked = true, seed = 9, bass = true, pullUp = false, drop = true, mutes = setOf("bed"))))
        val cut = played(kept, Arranger.Heard(0, 3), clipBars = listOf(4)).plan.sections.single()
        assertEquals(kept.sections[0].copy(repeats = 1, bars = 3), cut)
    }

    @Test
    fun `a pass of an already shortened section is cut against its own length`() {
        val kept = plan(2).withBars(0, 3)!!
        // 3 of 3 bars is the whole of it; 2 of 3 is a cut.
        val made = Arranger.heardOrder(kept, four, listOf(Arranger.Heard(0, 3), Arranger.Heard(0, 2), Arranger.Heard(0)))!!
        assertEquals(listOf(1, 1, 1), made.plan.sections.map { it.repeats })
        assertEquals(listOf<Int?>(3, 2, 3), made.plan.sections.map { it.bars })
    }

    @Test
    fun `a song past MAX_SECTIONS counts what did not fit, cut passes included`() {
        val kept = plan(2)
        // Alternating whole and cut passes: every one its own section.
        val heard = (0 until SongPlan.MAX_SECTIONS + 3).map { if (it % 2 == 0) Arranger.Heard(0) else Arranger.Heard(0, 1) }
        val made = Arranger.heardOrder(kept, four, heard)!!
        assertEquals(SongPlan.MAX_SECTIONS, made.plan.sections.size)
        assertEquals(3, made.left)
    }

    @Test
    fun `a long run of whole passes still splits at what one sequence holds`() {
        val kept = plan(1)
        val max = Arranger.maxRepeats(4)
        val made = Arranger.heardOrder(kept, listOf(4), List(max + 1) { Arranger.Heard(0) })!!
        assertEquals(listOf(max, 1), made.plan.sections.map { it.repeats })
    }

    // ---- the cursor hears the cut ----------------------------------------------

    private val passes = listOf(
        Arranger.Pass(0, 0, 1000, listOf(250, 500, 750)),
        Arranger.Pass(1, 1000, 2000, listOf(1250, 1500, 1750)),
    )
    private val mix = FloatArray(2400 * 2)

    private fun SongCursor.take(frames: Int) = read(FloatArray(frames * 2), frames)

    @Test
    fun `a pass cut at its second bar line is heard as two bars`() {
        val c = SongCursor(mix, passes, 0).also { it.queueMode = SongCursor.QueueMode.NEXT_BAR }
        c.take(300)
        c.queued = 1
        c.take(600)
        assertEquals(listOf(Arranger.Heard(0, 2), Arranger.Heard(1)), c.heard())
        assertEquals(listOf(0, 1), c.played())
    }

    @Test
    fun `a queue at the end of the pass hears it whole, and so does a pass cut on its last bar line`() {
        val end = SongCursor(mix, passes, 0).also { it.queued = 1 }
        end.take(1200)
        assertEquals(listOf(Arranger.Heard(0), Arranger.Heard(1)), end.heard())
        val last = SongCursor(mix, passes, 0).also { it.queueMode = SongCursor.QueueMode.NEXT_BAR }
        last.take(800) // past the last bar line, so the next one is the pass's end
        last.queued = 1
        last.take(600)
        assertEquals(listOf(Arranger.Heard(0), Arranger.Heard(1)), last.heard())
    }

    // ---- the mix plays only the bars it keeps ----------------------------------------

    private fun kit(): KitBuilderModel {
        val m = KitBuilderModel.create("Cut", File(temp, "Cut"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        return m
    }

    private val bar = Mpc3Clip.PULSES_PER_BAR

    /** A four-bar clip: a kick on every downbeat, and one a few ms before the end of bar two (index 1). */
    private fun clip(late: Long = bar * 2 - 24) =
        Mpc3Clip("Four", 4, (0 until 4).map { Mpc3Note(36, it * bar, 1f) } + Mpc3Note(36, late, 1f))

    private fun song(cut: Int?, repeats: Int = 1, c: Mpc3Clip = clip()) = Arranger.Arrangement(
        "cut", 0,
        listOf(
            Arranger.Section("a", c, repeats, "test", cutBars = cut, bass = false, pullUp = false),
            Arranger.Section("b", clip(0), 1, "test", bass = false, pullUp = false),
        ),
    )

    private fun Arranger.Arrangement.with(i: Int, change: (Arranger.Section) -> Arranger.Section) =
        copy(sections = sections.mapIndexed { at, s -> if (at == i) change(s) else s })

    private fun frameAt(m: KitBuilderModel, pulses: Long) =
        (pulses * 60.0 / KitPreview.playedBpm(m.kit.tempoBpm) * KitPreview.RATE / Mpc3Clip.PULSES_PER_BEAT).roundToInt()

    @Test
    fun `a cut section is its bars times the bar's frames, in length and in the next downbeat`() {
        val m = kit()
        val whole = Arranger.mixdown(m.kit, m.kitDir, song(null))
        val cut = Arranger.mixdown(m.kit, m.kitDir, song(2))
        assertEquals(frameAt(m, 4 * bar), whole.sectionStarts[1])
        assertEquals(frameAt(m, 2 * bar), cut.sectionStarts[1], "bars x bar frames")
        assertEquals(Arranger.Pass(0, 0, frameAt(m, 2 * bar), listOf(frameAt(m, bar))), cut.passes[0])
        assertEquals(4, song(null).sections[0].bars)
        assertEquals(2, song(2).sections[0].bars)
        assertEquals(6, song(2).totalBars, "2 and the 4 of b's clip")
    }

    @Test
    fun `a cut section repeated lays each pass cut, and a cut at or past the clip is the whole clip`() {
        val m = kit()
        val twice = Arranger.mixdown(m.kit, m.kitDir, song(3, repeats = 2))
        assertEquals(listOf(0, frameAt(m, 3 * bar)), twice.passes.take(2).map { it.start })
        assertEquals(frameAt(m, 6 * bar), twice.sectionStarts[1])
        val whole = Arranger.mixdown(m.kit, m.kitDir, song(null))
        val past = Arranger.mixdown(m.kit, m.kitDir, song(4))
        assertTrue(whole.snip.samples.contentEquals(past.snip.samples), "4 of 4 bars is the song as it was")
    }

    @Test
    fun `what the cut leaves out is not played, and what rings at the cut is ramped down`() {
        val m = kit()
        val cut = Arranger.mixdown(m.kit, m.kitDir, song(2))
        val two = Mpc3Clip("Two", 2, clip().notes.filter { it.timePulses < 2 * bar })
        val own = Arranger.mixdown(m.kit, m.kitDir, song(null, c = two))
        val at = cut.sectionStarts[1]
        assertEquals(own.sectionStarts[1], at)
        // The same cut over a clip that never had bars three and four is the same audio up to the cut: nothing of them plays.
        val bare = Arranger.mixdown(m.kit, m.kitDir, song(2, c = Mpc3Clip("Four", 4, clip().notes.filter { it.timePulses < 2 * bar })))
        for (f in 0 until at) assertEquals(bare.snip.samples[f * 2], cut.snip.samples[f * 2], "frame $f")
        // The kick struck just before the cut is still ringing at it when the clip ends there; the cut is ramped to nothing.
        fun tailPeak(snip: com.snipsnap.audio.Snip) = (at - 3 until at).maxOf { abs(snip.samples[it * 2]) }
        assertTrue(tailPeak(own.snip) > 0.01f, "a kick rings across the clip's own end: ${tailPeak(own.snip)}")
        assertTrue(tailPeak(cut.snip) < 0.03f * tailPeak(own.snip), "the cut stops it: ${tailPeak(cut.snip)}")
    }

    @Test
    fun `DROP and CLOSE act on the shortened last bar`() {
        val m = kit()
        val dropped = Arranger.mixdown(m.kit, m.kitDir, song(2).with(0) { it.copy(drop = true) })
        assertEquals("kick", dropped.drops.single().kept)
        val closed = Arranger.mixdown(m.kit, m.kitDir, song(2).with(0) { it.copy(close = true) })
        assertTrue(closed.transitions.single().applied, closed.transitions.single().reason)
        val plain = Arranger.mixdown(m.kit, m.kitDir, song(2))
        val from = frameAt(m, bar)
        val to = plain.sectionStarts[1]
        var differs = 0
        for (f in 0 until from) if (closed.snip.samples[f * 2] != plain.snip.samples[f * 2]) differs++
        assertEquals(0, differs, "CLOSE leaves bar one of two as it was")
        assertTrue((from until to).any { closed.snip.samples[it * 2] != plain.snip.samples[it * 2] }, "and sweeps bar two, the shortened last bar")
    }

    @Test
    fun `lane mutes still apply to a cut section`() {
        val m = kit()
        val out = Arranger.mixdown(m.kit, m.kitDir, song(2).with(0) { it.copy(mutes = setOf(PlanSection.padLane(1))) })
        val at = frameAt(m, 2 * bar)
        assertEquals(at, out.sectionStarts[1])
        assertTrue((0 until at).all { out.snip.samples[it * 2] == 0f }, "the kick lane is out of the cut section")
    }

    @Test
    fun `the MPC export writes the cut section's bars`() {
        val c = SongExport.sectionClips(song(2))
        assertEquals(listOf(2, 4), c.map { it.bars })
        assertEquals(listOf(0L, bar, 2 * bar - 24), c[0].notes.map { it.timePulses }.sorted())
    }

    @Test
    fun `resolve and toPlan carry the cut, and a clip that does not resolve drops it`() {
        val m = kit()
        GrooveStore.save(m.kitDir, listOf(clip()))
        val kept = SongPlan(0, listOf(PlanSection("a", ClipRef.CAPTURED, 1, bars = 1), PlanSection("b", "gone-clip", 1, bars = 1)))
        val r = Arranger.resolve(m.kit, m.kitDir, kept)
        assertEquals(1, r.arrangement.sections[0].cutBars)
        assertNull(r.arrangement.sections[1].cutBars)
        assertEquals(listOf<Int?>(1, null), Arranger.toPlan(r.arrangement).sections.map { it.bars })
    }
}
