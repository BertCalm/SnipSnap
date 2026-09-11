package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.GrooveStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArrangerTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("arr").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun grooveClip(): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        return Mpc3Clip(
            "Fixture Groove", 1,
            listOf(
                Mpc3Note(36, 0, 0.9f),
                Mpc3Note(37, 4 * s16, 0.85f),
                Mpc3Note(36, 8 * s16, 0.9f),
                Mpc3Note(37, 12 * s16, 0.9f),
                Mpc3Note(38, 2 * s16, 0.5f),
                Mpc3Note(38, 6 * s16, 0.5f),
            ),
        )
    }

    /** Same shape as [grooveClip], but one loud hit over five quiet ones — dynamic range 4.5x, well past the ghost threshold. */
    private fun dynamicGrooveClip(): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        return Mpc3Clip(
            "Dynamic Groove", 1,
            listOf(
                Mpc3Note(36, 0, 0.9f),
                Mpc3Note(37, 4 * s16, 0.2f),
                Mpc3Note(36, 8 * s16, 0.2f),
                Mpc3Note(37, 12 * s16, 0.2f),
                Mpc3Note(38, 2 * s16, 0.2f),
                Mpc3Note(38, 6 * s16, 0.2f),
            ),
        )
    }

    private fun model(name: String, withSnare: Boolean = true, clip: Mpc3Clip = grooveClip()): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        if (withSnare) m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.save()
        GrooveStore.save(m.kitDir, listOf(clip))
        return m
    }

    @Test
    fun `the grammar plans a song from the kit's own variations`() {
        val m = model("Arrange")
        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)

        assertEquals(
            listOf("intro", "theme", "variation", "the turn", "reprise", "outro"),
            plan.sections.map { it.name },
            "the full structure when the kit can play it",
        )
        assertEquals("Arrange Song", plan.name)
        assertEquals(plan.sections.sumOf { it.bars }, plan.totalBars)
        assertTrue(plan.totalBars >= 18, "a song, not a loop: ${plan.totalBars} bars")

        // Every section's clip is one of the kit's own variations.
        val intro = plan.sections[0]
        assertTrue(intro.clip.name.endsWith("Sparse"), intro.clip.name)
        assertEquals("Fixture Groove", plan.sections[1].clip.name)
        assertTrue(plan.sections[3].clip.name.endsWith("Fill"), plan.sections[3].clip.name)
        assertTrue(plan.sections[5].clip.name.endsWith("Half"), plan.sections[5].clip.name)

        // Every section states why it picked its clip.
        assertTrue(plan.sections.all { it.reason.isNotBlank() }, "every section explains itself")
        // The fixture's own dynamic range (0.9 loudest / 0.9 median = 1.0x)
        // sits under the ghost threshold, so the variation falls back to
        // tight and says so with the number that decided it.
        assertEquals(
            "tight — dynamic range 1.0x < 2x threshold",
            plan.sections[2].reason,
        )

        // Repeats stretch short clips to section length: 1-bar base, 4-bar body.
        assertEquals(4, plan.sections[1].repeats)
        assertEquals(4, plan.sections[1].bars)

        assertEquals(plan, Arranger.arrange(m.kit, m.kitDir, seed = 0), "deterministic per seed")
    }

    @Test
    fun `the variation section picks ghosted or tight by the groove's own dynamic range`() {
        val dynamic = model("Dynamic", clip = dynamicGrooveClip())
        val plan = Arranger.arrange(dynamic.kit, dynamic.kitDir, seed = 0)
        assertTrue(plan.sections[2].clip.name.endsWith("Ghosted"), plan.sections[2].clip.name)
        assertEquals(
            "ghosted — dynamic range 4.5x ≥ 2x threshold",
            plan.sections[2].reason,
        )

        val flat = model("Flat")
        val flatPlan = Arranger.arrange(flat.kit, flat.kitDir, seed = 0)
        assertTrue(flatPlan.sections[2].clip.name.endsWith("Tight"), flatPlan.sections[2].clip.name)
        assertEquals(
            "tight — dynamic range 1.0x < 2x threshold",
            flatPlan.sections[2].reason,
        )
    }

    @Test
    fun `the mixdown stitches the song - transitions, density, determinism`() {
        val m = model("Mix")
        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        val mix = Arranger.mixdown(m.kit, m.kitDir, plan)

        assertEquals(plan.sections.size, mix.sectionStarts.size)
        assertTrue(mix.sectionStarts.zipWithNext().all { (a, b) -> b > a }, "sections in order")
        val framesPerBar = (60.0 / com.snipsnap.kit.KitPreview.DEFAULT_BPM *
            com.snipsnap.kit.KitPreview.RATE * 4).toInt()
        assertTrue(
            mix.snip.frameCount > plan.totalBars * framesPerBar,
            "the song is at least its bars (ring-outs and transitions on top)",
        )

        // The fill section is measurably denser than the intro.
        fun rms(fromFrame: Int, frames: Int): Double {
            var acc = 0.0
            var n = 0
            for (f in fromFrame until minOf(fromFrame + frames, mix.snip.frameCount)) {
                val s = mix.snip.samples[f * 2]
                acc += s * s.toDouble()
                n++
            }
            return Math.sqrt(acc / n.coerceAtLeast(1))
        }
        val turnIx = plan.sections.indexOfFirst { it.name == "the turn" }
        val window = framesPerBar * 2
        val introRms = rms(mix.sectionStarts[0], window)
        val turnRms = rms(mix.sectionStarts[turnIx], window)
        assertTrue(turnRms > introRms, "the turn plays denser than the sparse intro: $introRms vs $turnRms")

        // Deterministic: same plan, same samples.
        val again = Arranger.mixdown(m.kit, m.kitDir, plan)
        assertTrue(mix.snip.samples.contentEquals(again.snip.samples), "same plan, same song")
    }

    @Test
    fun `honest refusals and skips - no groove, nothing to roll on`() {
        val bare = KitBuilderModel.create("Bare", File(temp, "Bare"))
        bare.assign(1, DrumSynth.kick(), DrumClass.KICK)
        bare.save()
        assertFailsWith<IllegalArgumentException>("no groove refuses") {
            Arranger.arrange(bare.kit, bare.kitDir)
        }

        // Kick and hat only: no snare/clap/perc - the turn is skipped and
        // the variation falls back to tight, whatever the seed prefers.
        val noSnare = model("NoSnare", withSnare = false)
        val plan = Arranger.arrange(noSnare.kit, noSnare.kitDir, seed = 0)
        assertTrue(plan.sections.none { it.name == "the turn" }, "nothing to roll on, no turn")
        assertTrue(
            plan.sections[2].clip.name.endsWith("Tight") || plan.sections[2].clip.name.endsWith("Swing"),
            "the variation falls back to a timing variant: ${plan.sections[2].clip.name}",
        )
    }

    @Test
    fun `the grammar plans and the chart draws any groove shape, or refuses by name`() {
        // SONG ▸ and CHART ▸ on the arrangement meet every groove the
        // store can hold: one hit, two hits, everything off the grid, the
        // longest clip with the fewest notes, a hit at the very last pulse
        // (half time drops it), a flat dense bar (median == loudest), a
        // groove entirely at velocity zero, and hits on pads the kit does
        // not have. A plan with at least a bar per section, charted with
        // one grid per section, or an IllegalArgumentException in words.
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val bar = Mpc3Clip.PULSES_PER_BAR
        val shapes: List<Pair<String, Mpc3Clip>> = listOf(
            "one hit" to Mpc3Clip("One", 1, listOf(Mpc3Note(36, 0, 0.9f))),
            "two hits" to Mpc3Clip("Two", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(37, 8 * s16, 0.3f))),
            "all off the grid" to Mpc3Clip("Loose", 2, (0 until 16).map { Mpc3Note(36 + it % 3, it * 2L * s16 + 37, 0.5f + (it % 4) * 0.1f) }),
            "64 bars, one hit a bar" to Mpc3Clip("Long", 64, (0 until 64).map { Mpc3Note(36, it * bar, 0.9f) }),
            "a hit at the last pulse" to Mpc3Clip("Edge", 4, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(37, 4 * bar - 1, 0.9f))),
            "flat and dense" to Mpc3Clip("Flat", 1, (0 until 16).flatMap { st -> listOf(36, 37, 38).map { Mpc3Note(it, st * s16, 0.7f) } }),
            "all at velocity zero" to Mpc3Clip("Silent", 1, (0 until 4).map { Mpc3Note(36, it * 4L * s16, 0f) }),
            "pads the kit has not got" to Mpc3Clip("Elsewhere", 1, listOf(Mpc3Note(100, 0, 0.9f), Mpc3Note(127, 8 * s16, 0.9f), Mpc3Note(0, 12 * s16, 0.9f))),
        )
        for ((name, clip) in shapes) {
            for (withSnare in listOf(true, false)) {
                val m = model("Shape-${name.hashCode()}-$withSnare", withSnare = withSnare, clip = clip)
                val plan = try {
                    Arranger.arrange(m.kit, m.kitDir, seed = 3)
                } catch (e: IllegalArgumentException) {
                    assertTrue(!e.message.isNullOrBlank(), "'$name' (snare=$withSnare) refused without saying why")
                    continue
                } catch (t: Throwable) {
                    throw AssertionError("'$name' (snare=$withSnare): arrange threw ${t::class.simpleName}: ${t.message}", t)
                }
                assertTrue(plan.sections.isNotEmpty() && plan.totalBars >= 1, "'$name': a plan with bars")
                assertTrue(plan.sections.all { it.bars >= 1 && it.reason.isNotBlank() }, "'$name': every section has bars and a reason")
                assertEquals(plan, Arranger.arrange(m.kit, m.kitDir, seed = 3), "'$name': deterministic per seed")

                val chart = try {
                    Chart.render(plan, m.kit, 92f, bpmIsDefault = true)
                } catch (t: Throwable) {
                    throw AssertionError("'$name' (snare=$withSnare): chart threw ${t::class.simpleName}: ${t.message}", t)
                }
                assertEquals(chart, Chart.render(plan, m.kit, 92f, bpmIsDefault = true), "'$name': same plan, same chart")
                for (section in plan.sections) {
                    assertTrue(chart.lineSequence().any { it.startsWith(section.name.uppercase() + " · ") }, "'$name': section '${section.name}' is charted\n$chart")
                }
                assertTrue(chart.lineSequence().first().startsWith(plan.name.uppercase()), "'$name': the song is named")
            }
        }
    }
}
