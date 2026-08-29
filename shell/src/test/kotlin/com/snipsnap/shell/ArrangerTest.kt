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

    private fun model(name: String, withSnare: Boolean = true): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        if (withSnare) m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.save()
        GrooveStore.save(m.kitDir, listOf(grooveClip()))
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

        // Repeats stretch short clips to section length: 1-bar base, 4-bar body.
        assertEquals(4, plan.sections[1].repeats)
        assertEquals(4, plan.sections[1].bars)

        assertEquals(plan, Arranger.arrange(m.kit, m.kitDir, seed = 0), "deterministic per seed")
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
}
