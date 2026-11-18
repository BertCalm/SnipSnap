package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AnswerStore
import com.snipsnap.kit.ClipRef
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.PlanSection
import com.snipsnap.kit.SongPlan
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    // ---------- the user's own program in the song ----------

    /** A hand-edited program as [GrooveEdit] stores one: on the grid, named with its suffix. */
    private fun yoursClip(baseName: String = "Fixture Groove"): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        return Mpc3Clip(
            GrooveEdit.progEName(baseName), 1,
            listOf(
                Mpc3Note(36, 0, 0.9f),
                Mpc3Note(36, 6 * s16, 0.9f),
                Mpc3Note(37, 8 * s16, 0.9f),
                Mpc3Note(38, 14 * s16, 0.6f),
            ),
        )
    }

    @Test
    fun `a hand-edited program becomes the song's variation, and says so`() {
        // The gap this closes: before, SONG composed only from generated
        // clips, so a pattern someone stepped in by hand could not appear
        // in a song at all.
        val m = model("Yours")
        val yours = yoursClip()
        GrooveStore.save(m.kitDir, listOf(grooveClip(), yours))

        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        assertEquals(yours.name, plan.sections[2].clip.name, "the variation section is the user's program")
        assertEquals(yours.notes, plan.sections[2].clip.notes, "handed over as stored, not regenerated")
        assertEquals("yours — the program you stepped in, exactly as you left it", plan.sections[2].reason)

        // It takes the variation slot and nothing else: the song does not
        // grow a section, and the derived clips still hold every other one.
        assertEquals(
            listOf("intro", "theme", "variation", "the turn", "reprise", "outro"),
            plan.sections.map { it.name },
        )
        assertEquals("Fixture Groove", plan.sections[1].clip.name, "the theme is still the capture")
    }

    @Test
    fun `more than one program of your own chains into the song, in slot order`() {
        // The chaining half of what a wall of pattern buttons is for.
        val m = model("YoursChain")
        val one = yoursClip()
        val two = Mpc3Clip(GrooveEdit.progEName("Fixture Groove", 1), 1, yoursClip().notes.drop(1))
        val three = Mpc3Clip(GrooveEdit.progEName("Fixture Groove", 2), 1, yoursClip().notes.take(2))
        // Deliberately stored out of order: slot order is what decides.
        GrooveStore.save(m.kitDir, listOf(grooveClip(), three, one, two))

        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        assertEquals(
            listOf("intro", "theme", "variation", "yours 2", "yours 3", "the turn", "reprise", "outro"),
            plan.sections.map { it.name },
            "they follow the variation rather than scattering through the structure",
        )
        assertEquals(one.notes, plan.sections[2].clip.notes)
        assertEquals(two.notes, plan.sections[3].clip.notes)
        assertEquals(three.notes, plan.sections[4].clip.notes)
        assertTrue(plan.sections.all { it.reason.isNotBlank() }, "every section still explains itself")
    }

    @Test
    fun `an empty program is skipped without shifting the ones after it`() {
        val m = model("YoursGap")
        val one = yoursClip()
        val three = Mpc3Clip(GrooveEdit.progEName("Fixture Groove", 2), 1, yoursClip().notes.take(2))
        GrooveStore.save(
            m.kitDir,
            listOf(grooveClip(), one, Mpc3Clip(GrooveEdit.progEName("Fixture Groove", 1), 1, emptyList()), three),
        )

        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        assertEquals(
            listOf("intro", "theme", "variation", "yours 2", "the turn", "reprise", "outro"),
            plan.sections.map { it.name },
            "the silent one is not a section",
        )
        assertEquals(three.notes, plan.sections[3].clip.notes, "and the one after it still plays")
    }

    @Test
    fun `the user's program outranks the measured ghost rule`() {
        // The dynamic fixture would pick `ghosted` on its own measurement.
        // A hand-edit is a variation somebody actually made, so it wins.
        val m = model("YoursDynamic", clip = dynamicGrooveClip())
        val yours = yoursClip("Dynamic Groove")
        GrooveStore.save(m.kitDir, listOf(dynamicGrooveClip(), yours))

        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        assertEquals(yours.name, plan.sections[2].clip.name)
        assertTrue("ghost" !in plan.sections[2].reason, plan.sections[2].reason)
    }

    @Test
    fun `the feel leans every section except the user's own`() {
        // `GrooveProgram` exempts the user's program from FEEL on screen
        // because its steps are already where somebody put them. The song
        // has to agree, or the same clip means two things.
        val m = model("YoursFeel")
        val yours = yoursClip()
        GrooveStore.save(m.kitDir, listOf(grooveClip(), yours))

        val leaned = Arranger.arrange(
            m.kit, m.kitDir, seed = 0,
            feel = -1f, feelTemplate = GrooveFeel.generated(1),
        )
        assertEquals(yours.notes, leaned.sections[2].clip.notes, "a hand-edit does not get leaned on")
        assertNotEquals(
            grooveClip().notes, leaned.sections[1].clip.notes,
            "...while the sections that are derived do carry the feel",
        )
    }

    @Test
    fun `an empty user program is not a section`() {
        // `GrooveEdit.startEmpty` forks a note-less E whenever GROOVE opens
        // on a kit that has recorded nothing, so this is the common state,
        // not a corrupt one. Using it would put a silent stretch in the
        // middle of every such song.
        val m = model("YoursEmpty")
        GrooveStore.save(m.kitDir, listOf(grooveClip(), Mpc3Clip(GrooveEdit.progEName("Fixture Groove"), 1, emptyList())))

        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        assertTrue(plan.sections[2].clip.notes.isNotEmpty(), "the variation section still sounds")
        assertEquals("tight — dynamic range 1.0x < 2x threshold", plan.sections[2].reason)
    }

    @Test
    fun `the base is the captured take even when the user's program is stored first`() {
        // Two rules for "which stored clip is the base" used to agree only
        // because `GrooveEdit.save` appends. Pin the predicate, not the
        // ordering: everything derived must come from the capture.
        val m = model("YoursFirst")
        GrooveStore.save(m.kitDir, listOf(yoursClip(), grooveClip()))

        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        assertEquals("Fixture Groove", plan.sections[1].clip.name, "the theme is the capture, not the hand-edit")
        // And the derived sections come off that capture rather than off
        // the hand-edit: `standard` names them for the base it was handed,
        // so a half derived from PROG E would carry E's name.
        assertTrue(plan.sections[5].clip.name.endsWith("Half"), plan.sections[5].clip.name)
        assertTrue(GrooveEdit.NAME_SUFFIX !in plan.sections[5].clip.name, plan.sections[5].clip.name)
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
        // The song is its bars plus the outro's own ring-out, and nothing
        // in between. This used to read "at least its bars (ring-outs and
        // transitions on top)": every section's ring-out was laid before the
        // next section instead of under it, and pushed each downbeat late.
        val past = mix.snip.frameCount - plan.totalBars * framesPerBar
        assertTrue(past >= 0, "the song is at least its bars: $past frames short")
        assertTrue(past < com.snipsnap.kit.KitPreview.RATE, "no more than the outro's ring-out past the last bar: $past frames")

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
    fun `a stored groove with no notes refuses by name, not by index`() {
        // GrooveEdit.startEmpty stores exactly this - an empty one-bar
        // base - when GROOVE opens on a kit that has recorded nothing, so
        // it is a state a user reaches by tapping STEPS, not a corrupt
        // file. Arranging it used to reach `velocities[velocities.size /
        // 2]` on an empty list and raise a bare IndexOutOfBoundsException.
        val empty = model("Empty", clip = Mpc3Clip("Empty Groove", 1, emptyList()))
        val e = assertFailsWith<IllegalArgumentException> {
            Arranger.arrange(empty.kit, empty.kitDir)
        }
        val message = e.message ?: ""
        assertTrue("Empty Groove" in message, "the refusal names the groove: $message")
        assertTrue("no notes" in message, "the refusal says what is wrong: $message")

        // The guard is what refuses, not luck: without it the same kit
        // reaches the median arithmetic, and that is the exception the
        // BB/SS contract forbids.
        val velocities = emptyList<Float>()
        assertFailsWith<IndexOutOfBoundsException> { velocities[velocities.size / 2] }

        // One note is enough to plan from - the guard refuses nothing else.
        val one = model("One", clip = Mpc3Clip("One", 1, listOf(Mpc3Note(36, 0, 0.9f))))
        assertTrue(Arranger.arrange(one.kit, one.kitDir).sections.isNotEmpty())
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

                if (name == "a hit at the last pulse") {
                    // Regression guard for the GrooveVariations.quantize fix: a
                    // hit one pulse before the loop end used to clamp to
                    // `limit - 1`, which in a 240-pulse grid is never itself a
                    // multiple of 240 - an off-grid ghost baked straight into
                    // whichever quantize-derived clip an arrangement ships, and
                    // this "Edge" fixture's own note (`4 * bar - 1`) is exactly
                    // that shape. `Arranger.arrange` reaches `quantize` only
                    // through GrooveVariations.standard's Tight slot (or Swing,
                    // if a later caller asks for it) - "intro"/"theme"/
                    // "reprise"/"outro" deliberately preserve the raw capture's
                    // own timing (sparse filters by velocity only; half just
                    // doubles it), and "the turn" (fill) legitimately rolls
                    // 32nds that land on multiples of 120, not every 16th - so
                    // only the quantize-derived section is asserted on here.
                    val quantized = plan.sections.filter { it.clip.name.endsWith("Tight") || it.clip.name.contains("Swing") }
                    assertTrue(quantized.isNotEmpty(), "'$name': no quantize-derived section found to guard")
                    for (section in quantized) {
                        section.clip.notes.forEach { n ->
                            assertEquals(
                                0L,
                                n.timePulses % s16,
                                "'$name': an off-grid ghost reached '${section.name}' at ${n.timePulses}",
                            )
                        }
                    }
                }

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

    /** Deliberately OFF the 16th grid — on-grid notes make a tight feel a no-op, and the assertions below vacuous. */
    private fun offGridClip(): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        return Mpc3Clip(
            "Loose Fixture", 1,
            listOf(
                Mpc3Note(36, 37, 0.9f),
                Mpc3Note(37, 4 * s16 - 31, 0.85f),
                Mpc3Note(36, 8 * s16 + 44, 0.9f),
                Mpc3Note(37, 12 * s16 - 18, 0.9f),
                Mpc3Note(38, 2 * s16 + 22, 0.5f),
                Mpc3Note(38, 6 * s16 - 25, 0.5f),
            ),
        )
    }

    /** Same shape as [offGridClip], but one loud hit over five quiet ones — dynamic range 4.5x, well past the ghost threshold, so a fully tight feel also exercises [GrooveVariations.ghosted]'s input, not just the always-snapping quantize path. */
    private fun offGridDynamicClip(): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        return Mpc3Clip(
            "Loose Dynamic Fixture", 1,
            listOf(
                Mpc3Note(36, 37, 0.9f),
                Mpc3Note(37, 4 * s16 - 31, 0.2f),
                Mpc3Note(36, 8 * s16 + 44, 0.2f),
                Mpc3Note(37, 12 * s16 - 18, 0.2f),
                Mpc3Note(38, 2 * s16 + 22, 0.2f),
                Mpc3Note(38, 6 * s16 - 25, 0.2f),
            ),
        )
    }

    private fun noteTimes(plan: Arranger.Arrangement): List<List<Long>> =
        plan.sections.map { s -> s.clip.notes.map { it.timePulses } }

    @Test
    fun `arrange honours the swing it is given`() {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        // offGridClip's six hits all nearest-round to EVEN 16ths (steps 0,
        // 2, 4, 6, 8, 12 - the same even steps grooveClip() itself sits
        // on), and swing() only pushes the ODD ("and") 16ths - so that
        // fixture alone can't tell 50% from 75% apart, whatever the
        // percent. One more off-grid hit on an odd step (739 rounds to
        // step 3, still no multiple of 240) gives the push something to
        // move without perturbing the dynamic-range branch: note 38 and
        // velocity 0.5 are already in the fixture and the kit, and the
        // seven-note median (0.85) keeps `preferGhosts` false so the
        // variation section still carries the swing slot.
        val clip = offGridClip().let { it.copy(notes = it.notes + Mpc3Note(38, 3 * s16 + 19, 0.5f)) }
        val m = model("ArrangeSwing", clip = clip)
        val straight = Arranger.arrange(m.kit, m.kitDir, seed = 0, swingPercent = 50)
        val swung = Arranger.arrange(m.kit, m.kitDir, seed = 0, swingPercent = 75)
        assertNotEquals(
            noteTimes(straight), noteTimes(swung),
            "ARRANGE ignored swingPercent entirely before this - 50 and 75 must differ",
        )
    }

    @Test
    fun `arrange honours a feel it is given`() {
        val m = model("ArrangeFeel", clip = offGridClip())
        val plain = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        val tight = Arranger.arrange(
            m.kit, m.kitDir, seed = 0, feel = -1f, feelTemplate = GrooveFeel.generated(1),
        )
        assertNotEquals(noteTimes(plain), noteTimes(tight), "a fully tight feel must reach the arrangement")

        // `assertNotEquals` above passes as soon as ANY ONE section moved,
        // so it can't catch a site that was quietly left on `base` while
        // its siblings moved to `felt` - which is exactly what happened to
        // GrooveVariations.ghosted and GrooveVariations.fill. Check every
        // section on its own: at t = -1 every note felt touches lands
        // exactly on the 16th grid, so any section built from felt must
        // be on-grid throughout. "the turn" is the one exception - its
        // generated roll deliberately rolls 32nds (multiples of 120, not
        // every 16th; see GrooveVariations.kt:156-163) into the back half
        // of its fill bar, so only the surviving FIRST half (the played
        // notes fill() keeps verbatim from its input) proves the point
        // there without the 32nd roll producing a false failure.
        val s16 = Mpc3Clip.PULSES_PER_16TH
        for (section in tight.sections) {
            val notes = if (section.name == "the turn") {
                section.clip.notes.filter { it.timePulses % Mpc3Clip.PULSES_PER_BAR < 8 * s16 }
            } else {
                section.clip.notes
            }
            notes.forEach { n ->
                assertEquals(
                    0L, n.timePulses % s16,
                    "'${section.name}': fully tight feel left an off-grid note at ${n.timePulses} - this section did not receive felt",
                )
            }
        }
    }

    @Test
    fun `arrange's ghosted variation also receives the feel, not the base`() {
        // Same regression as above, isolated to GrooveVariations.ghosted:
        // offGridClip's own dynamic range (1.0x) never crosses the ghost
        // threshold, so it can't exercise this call site. This fixture's
        // real dynamic range (4.5x) does, while keeping the same off-grid
        // hits a fully tight feel has something to snap.
        val m = model("ArrangeFeelGhost", clip = offGridDynamicClip())
        val tight = Arranger.arrange(
            m.kit, m.kitDir, seed = 0, feel = -1f, feelTemplate = GrooveFeel.generated(1),
        )
        val s16 = Mpc3Clip.PULSES_PER_16TH

        val variation = tight.sections.first { it.name == "variation" }
        assertTrue(
            variation.clip.name.endsWith("Ghosted"),
            "fixture's dynamic range should have picked the ghosted branch: ${variation.clip.name}",
        )
        variation.clip.notes.forEach { n ->
            assertEquals(
                0L, n.timePulses % s16,
                "variation (ghosted): fully tight feel left an off-grid note at ${n.timePulses} - " +
                    "GrooveVariations.ghosted received base instead of felt",
            )
        }
    }

    @Test
    fun `a feel with no template is a no-op, not a crash`() {
        val m = model("ArrangeNoTpl", clip = offGridClip())
        assertEquals(
            noteTimes(Arranger.arrange(m.kit, m.kitDir, seed = 0)),
            noteTimes(Arranger.arrange(m.kit, m.kitDir, seed = 0, feel = -1f, feelTemplate = null)),
            "a caller with no template has no feel to apply",
        )
    }

    // ---- the mixdown on the grid (song-making plan, phase 2) --------------

    /** Where section k must start: the frame of its first pulse, at KitPreview's own arithmetic. */
    private fun gridStarts(plan: Arranger.Arrangement, bpm: Float): List<Int> {
        val framesPerPulse = 60.0 / bpm * com.snipsnap.kit.KitPreview.RATE / Mpc3Clip.PULSES_PER_BEAT
        var pulses = 0L
        return plan.sections.map { s ->
            val start = (pulses * framesPerPulse).roundToInt()
            pulses += s.clip.lengthPulses * s.repeats
            start
        }
    }

    @Test
    fun `every section starts on its own downbeat`() {
        // Measured before this: theme 600 ms (0.92 beat) late at 92 BPM, the
        // turn 1.84 beats late, the outro 5.52 beats late. Each ring-out was
        // laid before the next section instead of under it.
        for (bpm in listOf(null, 140f)) {
            val m = model("Grid ${bpm ?: "default"}")
            m.setTempo(bpm)
            m.save()
            val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
            val mix = Arranger.mixdown(m.kit, m.kitDir, plan)
            assertEquals(
                gridStarts(plan, com.snipsnap.kit.KitPreview.playedBpm(bpm)),
                mix.sectionStarts,
                "every section on its bar at ${bpm ?: "the default"} BPM",
            )
        }
    }

    @Test
    fun `the pull-up plays over the last beat and stops at the turn`() {
        val rate = com.snipsnap.kit.KitPreview.RATE
        val end = rate * 2
        val cut = end - rate / 2
        // A section with a ring-out past its end, so there is a tail to silence.
        val seg = Snip(FloatArray((end + rate / 2) * 2) { i -> 0.3f * sin(2 * PI * 220 * (i / 2) / rate).toFloat() }, 2, rate)

        val pulled = Arranger.pullUpOver(seg, cut, end)
        assertEquals(end, pulled.frameCount, "nothing sounds past the turn's downbeat")
        for (i in 0 until cut * 2) assertEquals(seg.samples[i], pulled.samples[i], "untouched before the last beat (sample $i)")
        var energy = 0.0
        for (i in cut * 2 until end * 2) energy += pulled.samples[i] * pulled.samples[i].toDouble()
        assertTrue(energy > 0.0, "the spinback fills the last beat")
        assertTrue(
            (cut * 2 until end * 2).any { pulled.samples[it] != seg.samples[it] },
            "the last beat is the spinback, not the section played on",
        )
    }

    private fun sine(hz: Double, seconds: Float = 1f): Snip {
        val rate = 44_100
        return Snip(FloatArray((seconds * rate).toInt()) { 0.5f * sin(2 * PI * hz * it / rate).toFloat() }, 1, rate)
    }

    private fun centsOff(hz: Float, target: Double) = abs(1200 * log2(hz / target))

    @Test
    fun `a bass note an octave or a twelfth up plays at its written pitch`() {
        // An octave is a speed of exactly 2, which the old 0.51..1.99 window
        // refused: every octave in an Answer line played at the root.
        val root = sine(110.0)
        for ((semitones, hz) in listOf(12 to 220.0, 19 to 110.0 * Math.pow(2.0, 19 / 12.0), -12 to 55.0)) {
            val note = Arranger.repitched(root, semitones)!!
            val found = Pitch.detect(note)!!
            assertTrue(centsOff(found.hz, hz) < 50.0, "$semitones semitones: wanted $hz Hz, heard ${found.hz}")
        }
        assertSame(root, Arranger.repitched(root, 0), "the root plays the sample as it is")
        assertNull(Arranger.repitched(root, Arranger.BED_REACH_SEMITONES + 1), "past the reach, the note is left out")
    }

    @Test
    fun `a three-four groove tiles by its own bar`() {
        val waltz = Mpc3Clip(
            "Waltz", 1,
            listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(38, 960, 0.7f), Mpc3Note(38, 1920, 0.7f)),
            pulsesPerBar = 3 * Mpc3Clip.PULSES_PER_BEAT,
        )
        val three = Arranger.tiled(waltz, 3)
        assertEquals(3 * waltz.lengthPulses, three.lengthPulses)
        assertEquals((0 until 9).map { it * 960L }, three.notes.map { it.timePulses }.sorted(), "one beat apart, no gaps, nothing past the end")
    }

    @Test
    fun `the bed covers its section exactly, whatever its own length`() {
        val bar = Mpc3Clip.PULSES_PER_BAR
        val oneBar = Mpc3Clip("Bass", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(43, bar / 2, 0.8f)))
        assertEquals(
            (0 until 4).flatMap { listOf(it * bar, it * bar + bar / 2) },
            Arranger.bedNotes(oneBar, 4 * bar).map { it.timePulses },
            "a one-bar line repeats to fill a four-bar section",
        )
        val twoBars = Mpc3Clip("Bass", 2, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(43, bar + bar / 2, 0.8f)))
        assertEquals(listOf(0L), Arranger.bedNotes(twoBars, bar).map { it.timePulses }, "a two-bar line stops at a one-bar section's end")
    }

    /** Energy of the left channel over [fromMs, toMs) after [frame]. */
    private fun energyAfter(mix: Snip, frame: Int, fromMs: Int, toMs: Int): Double {
        val rate = com.snipsnap.kit.KitPreview.RATE
        var acc = 0.0
        for (f in frame + fromMs * rate / 1000 until minOf(frame + toMs * rate / 1000, mix.frameCount)) {
            acc += mix.samples[f * 2] * mix.samples[f * 2].toDouble()
        }
        return acc
    }

    @Test
    fun `an open hat chokes on the next section's closed hat`() {
        // Sections overlap now that each ring-out sounds under the next one's
        // opening, so the kit's choke has to reach across the boundary. The
        // open hat sits on the theme's last 16th (about 160 ms before the
        // boundary) and rings 500 ms; the closed hat is 80 ms long, so from
        // 100 to 300 ms past the boundary only an unchoked open hat sounds.
        val m = KitBuilderModel.create("Choke", File(temp, "Choke"))
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.assign(4, DrumSynth.openHat(), DrumClass.HAT_OPEN)
        m.save()
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val open = Mpc3Clip("Open", 1, listOf(Mpc3Note(39, 15 * s16, 1f)))
        fun song(next: Mpc3Clip) = Arranger.Arrangement(
            "Choke Song", 0,
            listOf(Arranger.Section("theme", open, 1, "open hat last"), Arranger.Section("variation", next, 1, "then this")),
        )
        val closed = Arranger.mixdown(m.kit, m.kitDir, song(Mpc3Clip("Closed", 1, listOf(Mpc3Note(38, 0, 1f)))))
        val boundary = closed.sectionStarts[1]
        assertTrue(energyAfter(closed.snip, boundary, 100, 300) < 1e-6, "the closed hat on one chokes the open hat: ${energyAfter(closed.snip, boundary, 100, 300)}")

        // The control: with nothing in the group on one, the same window does hold the open hat's tail.
        val late = Arranger.mixdown(m.kit, m.kitDir, song(Mpc3Clip("Late", 1, listOf(Mpc3Note(38, 12 * s16, 1f)))))
        assertTrue(energyAfter(late.snip, late.sectionStarts[1], 100, 300) > 1e-3, "the window hears an unchoked open hat")
    }

    @Test
    fun `the tape stop ends the whole song, a ringing bass note with it`() {
        // A twelve-second bass note under the reprise rings far past the
        // outro. The tape stop has to take it down with the drums at the
        // outro's end, not leave it playing on at full speed.
        val m = model("Tape")
        val rate = 44_100
        val note = Snip(FloatArray(12 * rate) { 0.4f * sin(2 * PI * 110.0 * it / rate).toFloat() }, 1, rate)
        WavWriter.write(File(m.kitDir, "bass.wav"), note)
        AnswerStore.save(
            m.kitDir,
            AnswerStore.StoredAnswer(0, "Tape Answer", "bass.wav", Mpc3Clip("Bass", 1, listOf(Mpc3Note(45, 0, 0.9f)))),
        )
        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        val mix = Arranger.mixdown(m.kit, m.kitDir, plan)

        val framesPerBar = (60.0 / com.snipsnap.kit.KitPreview.DEFAULT_BPM * com.snipsnap.kit.KitPreview.RATE * 4).toInt()
        val past = mix.snip.frameCount - plan.totalBars * framesPerBar
        assertTrue(past in 0 until com.snipsnap.kit.KitPreview.RATE, "the song ends at the outro's ring-out, bass and all: $past frames past")
        val lastFrame = mix.snip.frameCount - 1
        assertTrue(abs(mix.snip.samples[lastFrame * 2]) < 1e-3f, "the tape has stopped by the last frame: ${mix.snip.samples[lastFrame * 2]}")
        // And the bass really rang into the outro, or the checks above would
        // pass with no bass at all: against the same plan with no Answer, the
        // outro's first second carries the reprise's last note on top.
        File(m.kitDir, AnswerStore.FILE_NAME).delete()
        val bare = Arranger.mixdown(m.kit, m.kitDir, plan)
        val outro = plan.sections.lastIndex
        val withBass = energyAfter(mix.snip, mix.sectionStarts[outro], 0, 1000)
        val without = energyAfter(bare.snip, bare.sectionStarts[outro], 0, 1000)
        assertTrue(withBass - without > 100.0, "the reprise's bass rings into the outro: $withBass with it, $without without")
    }

    @Test
    fun `a long pad struck before the outro does not hold back the tape stop`() {
        // A twelve-second pad on the reprise's last off-beat rings far past the
        // outro inside the same take. The song still ends where the outro does:
        // its bars and its own ring-out.
        val m = model("Long Pad")
        val rate = 44_100
        m.assign(5, Snip(FloatArray(12 * rate) { 0.3f * sin(2 * PI * 220.0 * it / rate).toFloat() }, 1, rate), DrumClass.PERC)
        m.save()
        val bar = Mpc3Clip.PULSES_PER_BAR
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val reprise = Mpc3Clip("Reprise", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(40, 12 * s16, 0.9f)))
        val outro = Mpc3Clip("Outro", 2, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(36, bar, 0.9f)))
        val plan = Arranger.Arrangement(
            "Long Song", 0,
            listOf(Arranger.Section("reprise", reprise, 1, "a long pad late"), Arranger.Section("outro", outro, 1, "kicks")),
        )
        val mix = Arranger.mixdown(m.kit, m.kitDir, plan)

        val framesPerBar = (60.0 / com.snipsnap.kit.KitPreview.DEFAULT_BPM * com.snipsnap.kit.KitPreview.RATE * 4).toInt()
        val past = mix.snip.frameCount - 3 * framesPerBar
        assertTrue(past in 0 until com.snipsnap.kit.KitPreview.RATE, "the song ends at the outro's ring-out, not the long pad's: $past frames past")
        val lastFrame = mix.snip.frameCount - 1
        assertTrue(abs(mix.snip.samples[lastFrame * 2]) < 1e-3f, "the tape has stopped the long pad: ${mix.snip.samples[lastFrame * 2]}")
    }

    // ---------- a kept song (SongPlan) ----------

    private fun assertSameSong(a: Arranger.Arrangement, b: Arranger.Arrangement) {
        assertEquals(a.sections.map { it.name }, b.sections.map { it.name })
        assertEquals(a.sections.map { it.repeats }, b.sections.map { it.repeats })
        assertEquals(a.sections.map { it.clip }, b.sections.map { it.clip })
    }

    @Test
    fun `the draft carries a reference for every section, and the plan of it resolves to the same song`() {
        val m = model("Keep")
        GrooveStore.save(m.kitDir, listOf(grooveClip(), yoursClip()))
        val draft = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        assertTrue(draft.sections.all { it.ref.isNotBlank() }, draft.sections.map { it.ref }.toString())
        assertEquals(
            listOf("sparse", "captured", "yours:0", "fill", "captured", "half"),
            draft.sections.map { it.ref },
        )

        val resolved = Arranger.resolve(m.kit, m.kitDir, Arranger.toPlan(draft))
        assertEquals(emptyList(), resolved.refused)
        assertSameSong(draft, resolved.arrangement)
    }

    @Test
    fun `the same plan is the same song, notes and audio`() {
        val m = model("Same", clip = dynamicGrooveClip())
        val plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 2))
        val a = Arranger.resolve(m.kit, m.kitDir, plan).arrangement
        val b = Arranger.resolve(m.kit, m.kitDir, plan).arrangement
        assertSameSong(a, b)
        assertTrue(Arranger.mixdown(m.kit, m.kitDir, a).snip.samples.contentEquals(Arranger.mixdown(m.kit, m.kitDir, b).snip.samples))
    }

    @Test
    fun `a beat edited since the plan was kept flows into the song, and the order and repeats stay`() {
        val m = model("Flows")
        var plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        plan = plan.moved(0, 1)!!.withRepeats(1, 3)!!      // theme first, then the intro, played x3
        val before = Arranger.resolve(m.kit, m.kitDir, plan).arrangement
        GrooveStore.save(m.kitDir, listOf(Mpc3Clip("Fixture Groove", 1, grooveClip().notes.take(3))))
        val after = Arranger.resolve(m.kit, m.kitDir, plan).arrangement
        assertEquals(before.sections.map { it.name }, after.sections.map { it.name })
        assertEquals(listOf("theme", "intro"), after.sections.take(2).map { it.name })
        assertEquals(listOf(4, 3), after.sections.take(2).map { it.repeats })
        assertNotEquals(before.sections[0].clip.notes, after.sections[0].clip.notes, "the theme is the new capture")
    }

    @Test
    fun `a pattern made after another was deleted is not picked up by the old section`() {
        // The aliasing this guards: a SONG section keeps `yours:<number>`. When
        // a deleted program's number went back to the next new pattern, the
        // section quietly played that stranger.
        val m = model("Retired")
        val base = grooveClip()
        GrooveStore.save(m.kitDir, listOf(base))
        repeat(3) { GrooveEdit.forkNew(m.kitDir, yoursClip()) }
        val third = GrooveEdit.loadAll(m.kitDir).last()
        assertEquals(2, GrooveEdit.progIndexOf(third))
        val plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        assertTrue(plan.sections.any { it.clip == ClipRef.yours(2) }, "the song uses the third pattern")

        assertTrue(GrooveEdit.deleteProgE(m.kitDir) { it == 2 })
        val fresh = GrooveEdit.forkNew(m.kitDir, yoursClip())!!
        assertEquals(3, GrooveEdit.progIndexOf(fresh), "the new pattern does not take the retired number")

        val resolved = Arranger.resolve(m.kit, m.kitDir, plan)
        assertEquals(listOf(ClipRef.yours(2)), resolved.refused)
        assertTrue(resolved.arrangement.sections.none { it.ref == ClipRef.yours(2) }, "nothing plays the retired number")
        val replaced = resolved.arrangement.sections.filterIndexed { i, _ -> plan.sections[i].clip == ClipRef.yours(2) }
        assertTrue(replaced.isNotEmpty() && replaced.all { ClipRef.yoursId(it.ref) == null }, "planned again as a derived clip, never as another of yours")
    }

    @Test
    fun `a named pattern gives its section the name and a draft never overflows a song`() {
        val m = model("Named")
        GrooveStore.save(m.kitDir, listOf(grooveClip()))
        repeat(GrooveEdit.MAX_USER_PROGRAMS) { GrooveEdit.forkNew(m.kitDir, yoursClip()) }
        GrooveEdit.rename(m.kitDir, GrooveEdit.loadAll(m.kitDir)[1], "verse")
        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        assertTrue(plan.sections.size <= SongPlan.MAX_SECTIONS, "${plan.sections.size} sections")
        assertTrue(plan.sections.any { it.name == "verse" && it.ref == ClipRef.yours(1) }, plan.sections.map { it.name }.toString())
        assertEquals("yours — your pattern VERSE, exactly as you left it", plan.sections.first { it.ref == ClipRef.yours(1) }.reason)
        Arranger.toPlan(plan)
    }

    @Test
    fun `a reference that no longer resolves is refused by name and that section is planned again`() {
        val m = model("Stale")
        GrooveStore.save(m.kitDir, listOf(grooveClip(), yoursClip()))
        val plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        assertEquals("yours:0", plan.sections[2].clip)
        // The program is deleted after the plan was kept.
        GrooveStore.save(m.kitDir, listOf(grooveClip()))
        val gone = plan.withRepeats(2, 3)!!.withLock(2, true)!!

        val resolved = Arranger.resolve(m.kit, m.kitDir, gone)
        assertEquals(listOf("yours:0"), resolved.refused, "named, and once")
        val again = resolved.arrangement.sections[2]
        assertEquals("variation", again.name, "the section stays where the person put it")
        assertEquals("tight", again.ref, "planned again as the arranger would draft it")
        assertEquals(3, again.repeats)
        assertTrue(again.locked)
        assertEquals(gone.sections.size, resolved.arrangement.sections.size, "no section is dropped")
    }

    @Test
    fun `a name this build does not know is refused too, and a section with no draft of its name falls back to the capture`() {
        val m = model("Unknown")
        val plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0)).added("from-the-future")!!
        val resolved = Arranger.resolve(m.kit, m.kitDir, plan)
        assertEquals(listOf("from-the-future"), resolved.refused)
        assertEquals("captured", resolved.arrangement.sections.last().ref)
    }

    @Test
    fun `a fill the kit can no longer roll is refused`() {
        val withSnare = model("Fills")
        val plan = Arranger.toPlan(Arranger.arrange(withSnare.kit, withSnare.kitDir, seed = 0))
        assertTrue(plan.sections.any { it.clip == "fill" })
        val bare = model("NoSnare", withSnare = false)
        val resolved = Arranger.resolve(bare.kit, bare.kitDir, plan)
        assertEquals(listOf("fill"), resolved.refused)
    }

    @Test
    fun `reroll keeps a locked section's take and redraws the unlocked ones`() {
        val m = model("Reroll", clip = dynamicGrooveClip())
        val base = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        val variation = base.sections.indexOfFirst { it.name == "variation" }
        val turn = base.sections.indexOfFirst { it.name == "the turn" }
        assertEquals("ghosted", base.sections[variation].clip, "a dynamic groove ghosts its variation")
        val locked = base.withLock(variation, true)!!

        val before = Arranger.resolve(m.kit, m.kitDir, locked).arrangement
        // Some seed gives a different fill (not every pair of seeds does).
        val newSeed = (1..30).first { n ->
            Arranger.resolve(m.kit, m.kitDir, locked.rerolled(n)).arrangement.sections[turn].clip != before.sections[turn].clip
        }
        val rerolled = locked.rerolled(newSeed)
        val after = Arranger.resolve(m.kit, m.kitDir, rerolled).arrangement
        assertEquals(before.sections[variation].clip, after.sections[variation].clip, "locked: the same ghosts")
        assertNotEquals(before.sections[turn].clip, after.sections[turn].clip, "unlocked: a new fill")
        assertEquals(newSeed, rerolled.seed)

        // And the unlocked one is the arranger's own draw for that seed.
        val direct = Arranger.arrange(m.kit, m.kitDir, seed = newSeed)
        assertEquals(direct.sections.first { it.name == "the turn" }.clip, after.sections[turn].clip)
    }

    private fun withBass(m: KitBuilderModel) {
        val rate = 44_100
        WavWriter.write(File(m.kitDir, "bass.wav"), Snip(FloatArray(rate) { 0.4f * sin(2 * PI * 110.0 * it / rate).toFloat() }, 1, rate))
        AnswerStore.save(m.kitDir, AnswerStore.StoredAnswer(0, "Flag Answer", "bass.wav", Mpc3Clip("Bass", 1, listOf(Mpc3Note(45, 0, 0.9f)))))
    }

    private fun mixOf(m: KitBuilderModel, plan: SongPlan) =
        Arranger.mixdown(m.kit, m.kitDir, Arranger.resolve(m.kit, m.kitDir, plan).arrangement).snip.samples

    @Test
    fun `a section's bass and pull-up are its own, not its name's`() {
        val m = model("Flags")
        withBass(m)
        val plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        val renamed = plan.copy(sections = plan.sections.mapIndexed { i, s -> s.copy(name = "part ${i + 1}") })
        assertTrue(mixOf(m, plan).contentEquals(mixOf(m, renamed)), "every section renamed, the same song: bass and pull-up stayed")

        val turn = plan.sections.indexOfFirst { it.pullUp }
        val theme = plan.sections.indexOfFirst { it.name == "theme" }
        assertTrue(turn > 0 && plan.sections[theme].bass)
        assertTrue(!mixOf(m, plan).contentEquals(mixOf(m, plan.withPullUp(turn, false)!!)), "turning the pull-up off changes the song")
        assertTrue(!mixOf(m, plan).contentEquals(mixOf(m, plan.withBass(theme, false)!!)), "turning the theme's bass off changes the song")
        // An added section plays the bass, which a "section N" never did.
        val added = plan.added(ClipRef.CAPTURED)!!
        assertTrue(!mixOf(m, added).contentEquals(mixOf(m, added.withBass(added.sections.lastIndex, false)!!)), "an added section carries the bass")
    }

    @Test
    fun `a section planned again after a refusal keeps its own bass and pull-up`() {
        val m = model("FlagsRefused")
        val plan = SongPlan(0, listOf(PlanSection("theme", "gone-clip", bass = false, pullUp = true), PlanSection("reprise", ClipRef.CAPTURED)))
        val resolved = Arranger.resolve(m.kit, m.kitDir, plan)
        assertEquals(listOf("gone-clip"), resolved.refused)
        val s = resolved.arrangement.sections[0]
        assertEquals(false, s.bass, "not the draft theme's bass")
        assertEquals(true, s.pullUp)
        assertEquals(plan.sections.map { it.bass to it.pullUp }, Arranger.toPlan(resolved.arrangement).sections.map { it.bass to it.pullUp })
    }

    private val full = setOf(ClipRef.CAPTURED, ClipRef.TIGHT, ClipRef.GHOSTED)
    private val light = setOf(ClipRef.SPARSE, ClipRef.HALF)

    @Test
    fun `REROLL redraws an unlocked section's clip within its family and keeps the order`() {
        val m = model("RedrawFamily", clip = dynamicGrooveClip())
        val base = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        val draws = (1..40).map { Arranger.rerolled(m.kit, m.kitDir, base, it) }
        for (r in draws) {
            assertEquals(base.sections.map { it.name }, r.plan.sections.map { it.name }, "the order and names stay")
            for ((was, now) in base.sections.zip(r.plan.sections)) {
                when (was.clip) {
                    in full -> assertTrue(now.clip in full, "${was.name}: ${was.clip} redrew to ${now.clip}, not a full beat")
                    in light -> assertTrue(now.clip in light, "${was.name}: ${was.clip} redrew to ${now.clip}, not a light one")
                    else -> assertEquals(was.clip, now.clip, "${was.name}: a fill stays a fill")
                }
            }
            // Every section it laid out resolves: nothing was drawn that the kit cannot make.
            assertTrue(Arranger.resolve(m.kit, m.kitDir, r.plan).refused.isEmpty())
        }
        // It actually redraws: across seeds the theme is not always the capture, nor the intro always sparse.
        val theme = base.sections.indexOfFirst { it.name == "theme" }
        val intro = base.sections.indexOfFirst { it.name == "intro" }
        assertTrue(draws.map { it.plan.sections[theme].clip }.toSet().size > 1, "the theme never changed clip")
        assertTrue(draws.map { it.plan.sections[intro].clip }.toSet().size > 1, "the intro never changed clip")
        // The same reroll of the same plan is the same song.
        assertEquals(draws[4], Arranger.rerolled(m.kit, m.kitDir, base, 5))
    }

    @Test
    fun `REROLL leaves locked sections and your own programs alone`() {
        val m = model("RedrawKeeps", clip = dynamicGrooveClip())
        GrooveStore.save(m.kitDir, listOf(dynamicGrooveClip(), yoursClip("Dynamic Groove")))
        val base = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        val variation = base.sections.indexOfFirst { it.name == "variation" }
        val theme = base.sections.indexOfFirst { it.name == "theme" }
        assertTrue(ClipRef.yoursId(base.sections[variation].clip) != null, "the variation is the person's program")
        val locked = base.withLock(theme, true)!!
        for (seed in 1..20) {
            val r = Arranger.rerolled(m.kit, m.kitDir, locked, seed)
            assertEquals(locked.sections[theme], r.plan.sections[theme], "locked: untouched, seed and all")
            assertEquals(locked.sections[variation].clip, r.plan.sections[variation].clip, "yours is never swapped out")
            assertEquals(seed, r.plan.seed)
        }
        // A song whose unlocked sections are all the person's own has nothing to redraw, and says so.
        val onlyYours = SongPlan(0, listOf(base.sections[variation], base.sections[theme].copy(locked = true)))
        assertEquals(0, Arranger.rerolled(m.kit, m.kitDir, onlyYours, 1).redrawn)
    }

    @Test
    fun `REROLL never draws ghosts a kit cannot make, and keeps a section's length`() {
        val m = model("RedrawNoSnare", withSnare = false, clip = dynamicGrooveClip())
        val base = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        val before = Arranger.resolve(m.kit, m.kitDir, base).arrangement
        for (seed in 1..30) {
            val r = Arranger.rerolled(m.kit, m.kitDir, base, seed)
            assertTrue(r.plan.sections.none { it.clip == ClipRef.GHOSTED }, "no snare, so no ghosts")
            val after = Arranger.resolve(m.kit, m.kitDir, r.plan)
            assertTrue(after.refused.isEmpty())
            for ((b, a) in before.sections.zip(after.arrangement.sections)) {
                assertEquals(b.bars, a.bars, "${b.name}: ${b.bars} bars became ${a.bars}")
            }
        }
    }

    @Test
    fun `REROLL avoids playing the same clip twice in a row when the family has another`() {
        val m = model("RedrawNeighbours", clip = dynamicGrooveClip())
        // Theme, theme, theme: three full sections side by side, all unlocked.
        val plan = SongPlan(0, List(3) { com.snipsnap.kit.PlanSection("body ${it + 1}", ClipRef.CAPTURED, repeats = 4) })
        for (seed in 1..30) {
            val clips = Arranger.rerolled(m.kit, m.kitDir, plan, seed).plan.sections.map { it.clip }
            assertTrue(clips.zipWithNext().none { (a, b) -> a == b }, "seed $seed drew $clips")
        }
    }

    @Test
    fun `repeats are cut to what one sequence holds, and the picker offers only what resolves`() {
        val m = model("Reach")
        assertEquals(16, Arranger.maxRepeats(grooveClip()))
        assertEquals(4, Arranger.maxRepeats(Mpc3Clip("Long", 16, listOf(Mpc3Note(36, 0, 0.9f)))))
        assertEquals(1, Arranger.maxRepeats(Mpc3Clip("Huge", 64, listOf(Mpc3Note(36, 0, 0.9f)))))
        val plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0)).withRepeats(0, 16)!!
        val s = Arranger.resolve(m.kit, m.kitDir, plan).arrangement.sections[0]
        assertEquals(16, s.repeats)
        assertEquals(s.clip.bars * 16, s.bars)

        assertEquals(
            listOf("captured", "tight", "half", "sparse", "ghosted", "fill"),
            Arranger.available(m.kit, m.kitDir).map { it.ref },
        )
        val noSnare = model("Reach2", withSnare = false)
        assertEquals(
            listOf("captured", "tight", "half", "sparse"),
            Arranger.available(noSnare.kit, noSnare.kitDir).map { it.ref },
            "no snare, so no ghosts and no fill on offer",
        )
        GrooveStore.save(m.kitDir, listOf(grooveClip(), yoursClip(), Mpc3Clip(GrooveEdit.progEName("Fixture Groove", 2), 1, yoursClip().notes.take(2))))
        assertEquals(
            listOf("captured", "tight", "half", "sparse", "ghosted", "fill", "yours:0", "yours:2"),
            Arranger.available(m.kit, m.kitDir).map { it.ref },
            "every program of your own, by its own number",
        )
    }

    @Test
    fun `every turn after the first section is pulled up into, and a song with none is not`() {
        val m = model("Turns")
        val plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        val turn = plan.sections.indexOfFirst { it.name == "the turn" }
        val twice = plan.duplicated(turn)!!
        val none = plan.without(turn)!!
        val beat = (60.0 / com.snipsnap.kit.KitPreview.playedBpm(m.kit.tempoBpm) * com.snipsnap.kit.KitPreview.RATE).toInt()

        for ((p, turns) in listOf(plan to 1, twice to 2, none to 0)) {
            val arr = Arranger.resolve(m.kit, m.kitDir, p).arrangement
            assertEquals(turns, arr.sections.count { it.name == "the turn" })
            val mix = Arranger.mixdown(m.kit, m.kitDir, arr)
            assertEquals(arr.sections.size, mix.sectionStarts.size)
            // The same song with its turns' pull-up turned off: no cue, so no spinback. (Renaming no longer does it: the mark is the section's own.)
            val plain = Arranger.mixdown(m.kit, m.kitDir, arr.copy(sections = arr.sections.map { if (it.name == "the turn") it.copy(pullUp = false) else it }))
            for ((i, section) in arr.sections.withIndex()) {
                if (section.name != "the turn") continue
                val end = mix.sectionStarts[i]
                val differs = (maxOf(0, end - beat) until end).any { f ->
                    mix.snip.samples[f * 2] != plain.snip.samples[f * 2] || mix.snip.samples[f * 2 + 1] != plain.snip.samples[f * 2 + 1]
                }
                assertTrue(differs, "the last beat before turn #$i is the spinback")
            }
            if (turns == 0) assertTrue(mix.snip.samples.contentEquals(plain.snip.samples), "no turn, no pull-up")
        }
    }

    @Test
    fun `every pass of every section is on the grid, and the passes run end to end`() {
        val m = model("Passes")
        val drafted = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0))
        val plan = Arranger.resolve(m.kit, m.kitDir, drafted.withRepeats(1, 3)!!).arrangement
        val mix = Arranger.mixdown(m.kit, m.kitDir, plan)
        assertEquals(plan.sections.sumOf { it.repeats }, mix.passes.size, "one pass per time through")
        val framesPerPulse = 60.0 / com.snipsnap.kit.KitPreview.playedBpm(m.kit.tempoBpm) * com.snipsnap.kit.KitPreview.RATE / Mpc3Clip.PULSES_PER_BEAT
        var pulses = 0L
        var k = 0
        for ((i, s) in plan.sections.withIndex()) {
            for (r in 0 until s.repeats) {
                val p = mix.passes[k++]
                assertEquals(i, p.section)
                // NEXT BAR's cut points: one per bar line inside the pass, on the clip's own bar, from the sample rate the mix was made at.
                val bar = s.clip.pulsesPerBar
                assertEquals(
                    (1 until s.clip.bars).map { b -> ((pulses + b * bar) * framesPerPulse).roundToInt() },
                    p.barLines,
                    "section $i pass $r bar lines",
                )
                assertEquals((pulses * framesPerPulse).roundToInt(), p.start, "section $i pass $r on its bar")
                pulses += s.clip.lengthPulses
                assertEquals((pulses * framesPerPulse).roundToInt(), p.end, "section $i pass $r ends on the next bar")
            }
        }
        assertEquals(mix.sectionStarts, plan.sections.indices.map { i -> mix.passes.first { it.section == i }.start })
        assertTrue(mix.passes.zipWithNext().all { (a, b) -> a.end == b.start }, "no gap and no overlap between passes")
    }

    private fun sections(vararg names: String) = SongPlan(7, names.map { PlanSection(it, ClipRef.CAPTURED, repeats = 1) })

    @Test
    fun `KEEP THIS ORDER keeps each run of a section as that section played that many times`() {
        val kept = sections("intro", "theme", "the turn").let { it.withLock(1, true)!!.withBass(2, true)!! }
        val made = Arranger.playedOrder(kept, listOf(1, 1, 1), listOf(1, 1, 0, 2, 2, 1))!!
        assertEquals(listOf("theme" to 2, "intro" to 1, "the turn" to 2, "theme" to 1), made.plan.sections.map { it.name to it.repeats })
        assertEquals(kept.sections[1].copy(repeats = 2), made.plan.sections[0], "the lock, seed and flags go with it")
        assertEquals(kept.sections[2].copy(repeats = 2), made.plan.sections[2])
        assertEquals(7, made.plan.seed)
        assertEquals(0, made.left)
        assertNull(Arranger.playedOrder(kept, listOf(1, 1, 1), emptyList()), "nothing played keeps nothing")
    }

    @Test
    fun `a pass cut short at a bar line is kept as one whole pass, until a section can hold part of one`() {
        // S1a open question: a section is a clip and a whole number of repeats, so a cut pass cannot be a shorter section without a new stored field.
        val kept = sections("intro", "theme")
        val made = Arranger.playedOrder(kept, listOf(4, 4), listOf(0, 0, 1))!!
        assertEquals(listOf("intro" to 2, "theme" to 1), made.plan.sections.map { it.name to it.repeats })
    }

    @Test
    fun `a song played straight through keeps the song it was`() {
        val kept = sections("intro", "theme").withRepeats(1, 3)!!
        assertEquals(kept, Arranger.playedOrder(kept, listOf(1, 1), listOf(0, 1, 1, 1))!!.plan)
    }

    @Test
    fun `a run longer than one sequence holds is split, and a song past its limit says what was left out`() {
        val kept = sections("theme")
        // One sequence holds 64 bars, so a 16-bar clip plays at most 4 times in one section.
        val long = Arranger.playedOrder(kept, listOf(16), List(10) { 0 })!!
        assertEquals(listOf(4, 4, 2), long.plan.sections.map { it.repeats })
        val many = sections("a", "b")
        val alternating = List(SongPlan.MAX_SECTIONS + 3) { it % 2 }
        val cut = Arranger.playedOrder(many, listOf(1, 1), alternating)!!
        assertEquals(SongPlan.MAX_SECTIONS, cut.plan.sections.size)
        assertEquals(3, cut.left)
    }

    // ---- DROP: the last bar keeps only the kick, or the bed --------------------------------

    private val dropBar = Mpc3Clip.PULSES_PER_BAR
    private val dropS16 = Mpc3Clip.PULSES_PER_16TH

    /** A kit with the kick, snare and closed hat of [model], and a 12 s PERC pad on slot 5 (note 40) that rings across any bar it is struck in. */
    private fun dropModel(name: String): KitBuilderModel {
        val m = model(name)
        val rate = 44_100
        m.assign(5, Snip(FloatArray(12 * rate) { 0.3f * sin(2 * PI * 220.0 * it / rate).toFloat() }, 1, rate), DrumClass.PERC)
        m.save()
        return m
    }

    /** Section "a": two bars, the kick on every bar's one; [extras] adds the long pad in bar one and a snare and a hat in bar two. Then "b": a kick on its downbeat. */
    private fun dropSong(extras: Boolean, drop: Boolean, bass: Boolean = false, dropLast: Boolean = false): Arranger.Arrangement {
        val notes = mutableListOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(36, dropBar, 0.9f))
        if (extras) notes += listOf(Mpc3Note(40, 0, 0.9f), Mpc3Note(37, dropBar + 4 * dropS16, 0.9f), Mpc3Note(38, dropBar + 8 * dropS16, 0.9f))
        val a = Arranger.Section("a", Mpc3Clip("A", 2, notes), 1, "r", bass = bass, pullUp = false, drop = drop)
        val b = Arranger.Section("b", Mpc3Clip("B", 1, listOf(Mpc3Note(36, 0, 0.9f))), 1, "r", bass = false, pullUp = false, drop = dropLast)
        return Arranger.Arrangement("Drop", 0, listOf(a, b))
    }

    private fun window(mix: Arranger.Mix, from: Int, to: Int) = mix.snip.samples.copyOfRange(from * 2, to * 2)

    private fun dropFrames(m: KitBuilderModel, mix: Arranger.Mix): Pair<Int, Int> {
        val fpp = 60.0 / com.snipsnap.kit.KitPreview.playedBpm(m.kit.tempoBpm) * com.snipsnap.kit.KitPreview.RATE / 960
        return (dropBar * fpp).roundToInt() to mix.sectionStarts[1]
    }

    private fun energy(x: FloatArray): Double = x.sumOf { it.toDouble() * it }

    @Test
    fun `DROP keeps only the kick in the last bar, and no tail from before it rings in`() {
        val m = dropModel("DropKick")
        val full = Arranger.mixdown(m.kit, m.kitDir, dropSong(extras = true, drop = true))
        val kickOnly = Arranger.mixdown(m.kit, m.kitDir, dropSong(extras = false, drop = true))
        val (from, to) = dropFrames(m, full)
        assertEquals(listOf("kick"), full.drops.map { it.kept })
        // A hat, a snare, and a pad struck a whole bar earlier and still ringing: none of it in the bar. The same bar, bit for bit, as one that never had them.
        assertTrue(window(full, from, to).contentEquals(window(kickOnly, from, to)), "only the kick is in the dropped bar")
        assertTrue(energy(window(kickOnly, from, to)) > 1e-3, "and the kick is there")
        // Nothing outside the bar changes: before it, the song is the song without DROP (bar one's pad and all, up to the ramp into the cut)...
        val plainFull = Arranger.mixdown(m.kit, m.kitDir, dropSong(extras = true, drop = false))
        val ramp = 220
        assertTrue(window(full, 0, from - ramp).contentEquals(window(plainFull, 0, from - ramp)), "the bars before the drop are as they were")
        // ...and after it, the pad's 12 s tail does not come back under section b: it is what a song without the pad plays.
        assertTrue(window(full, to, full.snip.frameCount).contentEquals(window(kickOnly, to, kickOnly.snip.frameCount)), "no tail from before the drop returns after it")
        // Control: without DROP the same window does hold them.
        val plain = Arranger.mixdown(m.kit, m.kitDir, dropSong(extras = true, drop = false))
        assertTrue(energy(window(plain, from, to)) > 2 * energy(window(full, from, to)), "the extras are audible when DROP is off")
        assertTrue(plain.drops.isEmpty())
    }

    @Test
    fun `DROP never lengthens the section, and the next downbeat lands on the same sample`() {
        val m = dropModel("DropGrid")
        for (dropLast in listOf(false, true)) {
            val plain = Arranger.mixdown(m.kit, m.kitDir, dropSong(extras = true, drop = false))
            val dropped = Arranger.mixdown(m.kit, m.kitDir, dropSong(extras = true, drop = true, dropLast = dropLast))
            assertEquals(plain.sectionStarts, dropped.sectionStarts)
            assertEquals(plain.passes, dropped.passes)
            assertEquals(plain.snip.frameCount, dropped.snip.frameCount, "the song is as long as it was")
            // The kick on b's downbeat sounds from the sample it sounds from on its own; nothing before it in the dropped bar's last few ms.
            val alone = Arranger.mixdown(m.kit, m.kitDir, Arranger.Arrangement("B", 0, listOf(dropSong(false, false).sections[1])))
            val onset = (0 until alone.snip.frameCount).first { abs(alone.snip.samples[it * 2]) > 1e-4f }
            val at = dropped.sectionStarts[1]
            val first = (at - 200 until dropped.snip.frameCount).first { abs(dropped.snip.samples[it * 2]) > 1e-4f }
            assertEquals(at + onset, first, "b's downbeat, sample for sample")
        }
    }

    @Test
    fun `DROP with the bed keeps only the bed, and the tail of an earlier bed note does not ring in`() {
        val m = dropModel("DropBed")
        val rate = 44_100
        WavWriter.write(File(m.kitDir, "bass.wav"), Snip(FloatArray(12 * rate) { 0.4f * sin(2 * PI * 110.0 * it / rate).toFloat() }, 1, rate))
        fun mixWith(bassClip: Mpc3Clip, extras: Boolean, drop: Boolean): Arranger.Mix {
            AnswerStore.save(m.kitDir, AnswerStore.StoredAnswer(0, "Drop Answer", "bass.wav", bassClip))
            return Arranger.mixdown(m.kit, m.kitDir, dropSong(extras = extras, drop = drop, bass = true))
        }
        // A one-bar line sounds in both bars of "a" (and its 12 s note from bar one rings into bar two). A two-bar line that holds a note in bar two only does not.
        val everyBar = Mpc3Clip("Bass", 1, listOf(Mpc3Note(45, 0, 0.9f)))
        val lastBarOnly = Mpc3Clip("Bass", 2, listOf(Mpc3Note(45, dropBar, 0.9f)))
        val a = mixWith(everyBar, extras = true, drop = true)
        val b = mixWith(lastBarOnly, extras = false, drop = true)
        val (from, to) = dropFrames(m, a)
        assertEquals(listOf("bed"), a.drops.map { it.kept })
        assertTrue(window(a, from, to).contentEquals(window(b, from, to)), "the bed's own bar and nothing else: no kick, no pad, no tail of bar one's bass note")
        assertTrue(energy(window(b, from, to)) > 1.0, "the bed is there")
        // Control: without DROP, bar one's note still rings under bar two.
        val plainA = mixWith(everyBar, extras = true, drop = false)
        val plainB = mixWith(lastBarOnly, extras = false, drop = false)
        assertTrue(!window(plainA, from, to).contentEquals(window(plainB, from, to)))
        // Section a had BASS on, so it was not the kick that stayed.
        assertTrue(energy(window(a, from, to)) > 0.0)
    }

    @Test
    fun `DROP with no kick hit and no bed in the last bar leaves it alone and says why`() {
        val m = dropModel("DropNone")
        val noKick = Arranger.Section("a", Mpc3Clip("A", 2, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(38, dropBar, 0.9f))), 1, "r", bass = false, pullUp = false, drop = true)
        val song = Arranger.Arrangement("D", 0, listOf(noKick, dropSong(false, false).sections[1]))
        val dropped = Arranger.mixdown(m.kit, m.kitDir, song)
        val plain = Arranger.mixdown(m.kit, m.kitDir, song.copy(sections = song.sections.map { it.copy(drop = false) }))
        assertTrue(dropped.snip.samples.contentEquals(plain.snip.samples), "the bar is as it was")
        assertEquals(listOf(null), dropped.drops.map { it.kept })
        assertTrue(dropped.drops.single().reason.contains("left as it is"), dropped.drops.single().reason)
    }

    @Test
    fun `a pull-up into the next section takes the boundary from a DROP in a hand-edited song`() {
        val m = dropModel("DropClash")
        val s = dropSong(extras = true, drop = true)
        val clash = s.copy(sections = listOf(s.sections[0], s.sections[1].copy(pullUp = true)))
        val withDrop = Arranger.mixdown(m.kit, m.kitDir, clash)
        val without = Arranger.mixdown(m.kit, m.kitDir, clash.copy(sections = clash.sections.map { it.copy(drop = false) }))
        assertTrue(withDrop.snip.samples.contentEquals(without.snip.samples), "the pull-up plays, the drop does not")
        assertEquals(null, withDrop.drops.single().kept)
    }

    @Test
    fun `DROP is deterministic, and the plan carries it through resolve and back`() {
        val m = dropModel("DropPlan")
        val a = Arranger.mixdown(m.kit, m.kitDir, dropSong(extras = true, drop = true))
        val b = Arranger.mixdown(m.kit, m.kitDir, dropSong(extras = true, drop = true))
        assertTrue(a.snip.samples.contentEquals(b.snip.samples))
        for (seed in listOf(3, 11)) {
            val seeded = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = seed)).withDrop(1, true)!!.withDrop(2, true)!!
            val x = Arranger.mixdown(m.kit, m.kitDir, Arranger.resolve(m.kit, m.kitDir, seeded).arrangement)
            val y = Arranger.mixdown(m.kit, m.kitDir, Arranger.resolve(m.kit, m.kitDir, seeded).arrangement)
            assertTrue(x.snip.samples.contentEquals(y.snip.samples), "seed $seed: the same plan with DROP is the same audio")
            assertEquals(x.drops, y.drops)
        }
        val plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0)).withDrop(1, true)!!
        val resolved = Arranger.resolve(m.kit, m.kitDir, plan).arrangement
        assertEquals(plan.sections.map { it.drop }, resolved.sections.map { it.drop })
        assertEquals(plan.sections.map { it.drop }, Arranger.toPlan(resolved).sections.map { it.drop })
        // A section planned again after a refusal keeps its drop too.
        val gone = SongPlan(0, listOf(PlanSection("theme", "gone-clip", drop = true), PlanSection("reprise", ClipRef.CAPTURED)))
        assertTrue(Arranger.resolve(m.kit, m.kitDir, gone).arrangement.sections[0].drop)
    }

    @Test
    fun `the dropped bar is exactly one bar long, and a kick ringing past it stops at its end`() {
        val m = dropModel("DropPure")
        val bpm = com.snipsnap.kit.KitPreview.playedBpm(m.kit.tempoBpm)
        val frames = (dropBar * 60.0 / bpm * com.snipsnap.kit.KitPreview.RATE / 960).roundToInt()
        // A kick on the bar's last 16th, whose body is longer than the 16th left.
        val bar = Arranger.dropBar(m.kit, m.kitDir, Arranger.DropKeeps.KICK, listOf(Mpc3Note(36, 15 * dropS16, 1f), Mpc3Note(38, 0, 1f)), emptyList(), null, dropBar, frames, bpm)
        assertEquals(frames, bar.frameCount)
        assertEquals(0f, bar.samples[(frames - 1) * 2], 0.05f, "ramped down into the bar's end")
        // The hat that was not a kick is not in it; the same bar from the kick alone is the same audio.
        val kickAlone = Arranger.dropBar(m.kit, m.kitDir, Arranger.DropKeeps.KICK, listOf(Mpc3Note(36, 15 * dropS16, 1f)), emptyList(), null, dropBar, frames, bpm)
        assertTrue(bar.samples.contentEquals(kickAlone.samples))
        val kickFrame = (15 * dropS16 * 60.0 / bpm * com.snipsnap.kit.KitPreview.RATE / 960).roundToInt()
        assertTrue(energy(bar.samples.copyOfRange(0, (kickFrame - 1) * 2)) < 1e-12, "silent before the kick")
    }

    // ---- MUTES: a section leaves lanes out ---------------------------------------------------

    private val muteKick = PlanSection.padLane(1)
    private val muteSnare = PlanSection.padLane(2)
    private val muteHat = PlanSection.padLane(3)

    /** Two bars of kick, snare and hat (pads 1, 2 and 3), with [without] lanes' notes taken out of the clip itself, and [mutes] asked of the section. */
    private fun muteSong(mutes: Set<String> = emptySet(), without: Set<Int> = emptySet(), bass: Boolean = false): Arranger.Arrangement {
        val notes = listOf(
            Mpc3Note(36, 0, 0.9f), Mpc3Note(36, dropBar, 0.9f),
            Mpc3Note(37, 4 * dropS16, 0.9f), Mpc3Note(37, dropBar + 4 * dropS16, 0.9f),
            Mpc3Note(38, 2 * dropS16, 0.7f), Mpc3Note(38, dropBar + 2 * dropS16, 0.7f),
        ).filter { Mpc3Note.slotFor(it.note) !in without }
        val a = Arranger.Section("a", Mpc3Clip("A", 2, notes), 1, "r", bass = bass, pullUp = false, mutes = mutes)
        val b = Arranger.Section("b", Mpc3Clip("B", 1, listOf(Mpc3Note(36, 0, 0.9f))), 1, "r", bass = false, pullUp = false)
        return Arranger.Arrangement("Mutes", 0, listOf(a, b))
    }

    private fun sha(x: FloatArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        for (f in x) d.update(java.nio.ByteBuffer.allocate(4).putInt(f.toRawBits()).array())
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a muted lane is exactly that lane's hits left out, and the others are untouched`() {
        val m = dropModel("MuteLane")
        val full = Arranger.mixdown(m.kit, m.kitDir, muteSong())
        for ((lane, slot) in listOf(muteKick to 1, muteSnare to 2, muteHat to 3)) {
            val muted = Arranger.mixdown(m.kit, m.kitDir, muteSong(mutes = setOf(lane)))
            val gone = Arranger.mixdown(m.kit, m.kitDir, muteSong(without = setOf(slot)))
            assertTrue(muted.snip.samples.contentEquals(gone.snip.samples), "$lane muted is the song with that pad never played")
            assertTrue(!muted.snip.samples.contentEquals(full.snip.samples), "$lane is audible when it is not muted")
            assertTrue(energy(muted.snip.samples) < energy(full.snip.samples), "$lane left out takes energy out")
        }
        // Two lanes at once are both gone.
        val two = Arranger.mixdown(m.kit, m.kitDir, muteSong(mutes = setOf(muteKick, muteHat)))
        assertTrue(two.snip.samples.contentEquals(Arranger.mixdown(m.kit, m.kitDir, muteSong(without = setOf(1, 3))).snip.samples))
    }

    @Test
    fun `muting never changes the length, the bar grid or the passes, even with every lane muted`() {
        val m = dropModel("MuteGrid")
        val plain = Arranger.mixdown(m.kit, m.kitDir, muteSong())
        for (mutes in listOf(setOf(muteKick), setOf(muteKick, muteSnare, muteHat))) {
            val muted = Arranger.mixdown(m.kit, m.kitDir, muteSong(mutes = mutes))
            assertEquals(plain.sectionStarts, muted.sectionStarts)
            assertEquals(plain.passes, muted.passes)
            assertEquals(plain.snip.frameCount, muted.snip.frameCount, "the song is as long as it was")
        }
        // The last section muted whole: its own ring-out still sets where the song ends.
        val s = muteSong()
        val lastMuted = s.copy(sections = listOf(s.sections[0], s.sections[1].copy(mutes = setOf(muteKick))))
        assertEquals(plain.snip.frameCount, Arranger.mixdown(m.kit, m.kitDir, lastMuted).snip.frameCount)
    }

    @Test
    fun `a song with no mutes is sample for sample the song it was, and a mute that names nothing played changes nothing`() {
        val m = dropModel("MuteZero")
        val plain = Arranger.mixdown(m.kit, m.kitDir, muteSong())
        // A mute of a pad the clip never hits, and of a bed the section does not have, goes through the filter and leaves every sample alone.
        val idle = Arranger.mixdown(m.kit, m.kitDir, muteSong(mutes = setOf(PlanSection.padLane(99), PlanSection.BED_LANE)))
        assertEquals(sha(plain.snip.samples), sha(idle.snip.samples))
        assertEquals(plain.sectionStarts, idle.sectionStarts)
        assertEquals(plain.drops, idle.drops)
        val seeded = Arranger.arrange(m.kit, m.kitDir, seed = 3)
        val a = Arranger.mixdown(m.kit, m.kitDir, seeded)
        val b = Arranger.mixdown(m.kit, m.kitDir, Arranger.resolve(m.kit, m.kitDir, Arranger.toPlan(seeded)).arrangement)
        assertEquals(sha(a.snip.samples), sha(b.snip.samples), "a planned song with no mutes is the arranger's song")
    }

    @Test
    fun `a muted mix is deterministic`() {
        val m = dropModel("MuteDet")
        val a = Arranger.mixdown(m.kit, m.kitDir, muteSong(mutes = setOf(muteSnare)))
        val b = Arranger.mixdown(m.kit, m.kitDir, muteSong(mutes = setOf(muteSnare)))
        assertEquals(sha(a.snip.samples), sha(b.snip.samples))
    }

    @Test
    fun `a section with every lane muted is a silent section, not a failed mix`() {
        val m = dropModel("MuteAll")
        // A one-section song is its own stretch: with nothing left to play there is nothing for the kit to render, and that is silence.
        val one = muteSong().copy(sections = listOf(muteSong().sections[0]))
        val plain = Arranger.mixdown(m.kit, m.kitDir, one)
        val silent = Arranger.mixdown(m.kit, m.kitDir, one.copy(sections = listOf(one.sections[0].copy(mutes = setOf(muteKick, muteSnare, muteHat)))))
        assertEquals(plain.sectionStarts, silent.sectionStarts)
        assertEquals(plain.snip.frameCount, silent.snip.frameCount)
        assertTrue(energy(silent.snip.samples) < 1e-12)
        // Between two pull-ups it is a stretch of its own too.
        val three = Arranger.Arrangement("Three", 0, listOf(one.sections[0], one.sections[0].copy(pullUp = true, mutes = setOf(muteKick, muteSnare, muteHat)), one.sections[0].copy(pullUp = true)))
        assertEquals(3, Arranger.mixdown(m.kit, m.kitDir, three).sectionStarts.size)
    }

    @Test
    fun `a muted bed leaves the bed out of that section only`() {
        val m = dropModel("MuteBed")
        val rate = 44_100
        WavWriter.write(File(m.kitDir, "bass.wav"), Snip(FloatArray(2 * rate) { 0.4f * sin(2 * PI * 110.0 * it / rate).toFloat() }, 1, rate))
        AnswerStore.save(m.kitDir, AnswerStore.StoredAnswer(0, "Mute Answer", "bass.wav", Mpc3Clip("Bass", 1, listOf(Mpc3Note(45, 0, 0.9f)))))
        val withBed = Arranger.mixdown(m.kit, m.kitDir, muteSong(bass = true))
        val bedMuted = Arranger.mixdown(m.kit, m.kitDir, muteSong(bass = true, mutes = setOf(PlanSection.BED_LANE)))
        val noBass = Arranger.mixdown(m.kit, m.kitDir, muteSong(bass = false))
        assertTrue(!withBed.snip.samples.contentEquals(noBass.snip.samples), "the bed is heard")
        assertTrue(bedMuted.snip.samples.contentEquals(noBass.snip.samples), "BASS on with the bed muted is BASS off")
        assertEquals(withBed.snip.frameCount, bedMuted.snip.frameCount)
        // A drum mute leaves the bed in.
        val drumMuted = Arranger.mixdown(m.kit, m.kitDir, muteSong(bass = true, mutes = setOf(muteHat)))
        assertTrue(!drumMuted.snip.samples.contentEquals(bedMuted.snip.samples))
    }

    @Test
    fun `DROP on a section whose kick is muted keeps a silent bar and says so`() {
        val m = dropModel("MuteDrop")
        val song = dropSong(extras = true, drop = true)
        val muted = song.copy(sections = listOf(song.sections[0].copy(mutes = setOf(muteKick)), song.sections[1]))
        val mix = Arranger.mixdown(m.kit, m.kitDir, muted)
        val (from, to) = dropFrames(m, mix)
        assertEquals(listOf("kick"), mix.drops.map { it.kept })
        assertTrue(mix.drops.single().reason.contains("muted") && mix.drops.single().reason.contains("silent"), mix.drops.single().reason)
        assertTrue(energy(window(mix, from + 220, to - 220)) < 1e-9, "the kick is the only thing DROP keeps, and it is muted")
        // The control: the same song with the kick played has it in that bar, and is the same length.
        val loud = Arranger.mixdown(m.kit, m.kitDir, song)
        assertTrue(energy(window(loud, from, to)) > 1e-3)
        assertEquals(loud.snip.frameCount, mix.snip.frameCount)
        assertEquals(loud.sectionStarts, mix.sectionStarts)
        // With the kick not muted but another lane muted, DROP is what it was.
        val other = song.copy(sections = listOf(song.sections[0].copy(mutes = setOf(muteHat)), song.sections[1]))
        assertEquals("the last bar keeps only the kick", Arranger.mixdown(m.kit, m.kitDir, other).drops.single().reason)
    }

    @Test
    fun `a muted hat does not choke the open hat before it`() {
        val m = KitBuilderModel.create("MuteChoke", File(temp, "MuteChoke"))
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.assign(4, DrumSynth.openHat(), DrumClass.HAT_OPEN)
        m.save()
        val s16 = Mpc3Clip.PULSES_PER_16TH
        fun song(mutes: Set<String>) = Arranger.Arrangement(
            "Choke Song", 0,
            listOf(
                Arranger.Section("theme", Mpc3Clip("Open", 1, listOf(Mpc3Note(39, 15 * s16, 1f))), 1, "open hat last"),
                Arranger.Section("variation", Mpc3Clip("Closed", 1, listOf(Mpc3Note(38, 0, 1f))), 1, "then this", mutes = mutes),
            ),
        )
        val chokes = Arranger.mixdown(m.kit, m.kitDir, song(emptySet()))
        assertTrue(energyAfter(chokes.snip, chokes.sectionStarts[1], 100, 300) < 1e-6, "the closed hat chokes the open hat")
        val muted = Arranger.mixdown(m.kit, m.kitDir, song(setOf(PlanSection.padLane(3))))
        assertTrue(energyAfter(muted.snip, muted.sectionStarts[1], 100, 300) > 1e-3, "a hat that is not played chokes nothing")
    }

    @Test
    fun `mutes ride through resolve and back, and name the pads a clip plays`() {
        val m = dropModel("MutePlan")
        val plan = Arranger.toPlan(Arranger.arrange(m.kit, m.kitDir, seed = 0)).withMute(1, muteKick, true)!!.withMute(1, PlanSection.BED_LANE, true)!!
        val resolved = Arranger.resolve(m.kit, m.kitDir, plan).arrangement
        assertEquals(listOf(emptySet(), setOf(muteKick, PlanSection.BED_LANE)), resolved.sections.take(2).map { it.mutes })
        assertEquals(plan.sections.map { it.mutes }, Arranger.toPlan(resolved).sections.map { it.mutes })
        val gone = SongPlan(0, listOf(PlanSection("theme", "gone-clip", mutes = setOf(muteHat)), PlanSection("reprise", ClipRef.CAPTURED)))
        assertEquals(setOf(muteHat), Arranger.resolve(m.kit, m.kitDir, gone).arrangement.sections[0].mutes, "a section planned again keeps its mutes")
        assertEquals(listOf(1, 2, 3), Arranger.lanes(muteSong().sections[0].clip))
    }
}
