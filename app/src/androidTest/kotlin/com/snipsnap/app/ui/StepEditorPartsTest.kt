package com.snipsnap.app.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.shell.Copy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BEAT step editor's pad picker and step-detail strip, composed on their own
 * (they are the new parts; GrooveScreen's overlay is reached only through a
 * scroll this harness cannot do, see [GrooveScreenTest]). One state object
 * each, as in the screen, so what is asserted is what the player's finger does.
 */
@RunWith(AndroidJUnit4::class)
class StepEditorPartsTest : ComposeScreenTest() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dirs = mutableListOf<File>()
    private val edits = CopyOnWriteArrayList<StepEdit>()

    @After
    fun tearDown() {
        scope.cancel()
        dirs.forEach { it.deleteRecursively() }
    }

    private val kit = Kit(
        name = "STEP TEST",
        pads = listOf(KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK)),
        tempoBpm = 92f,
    )

    private fun stateWith(clip: Mpc3Clip): StepEditorState {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "step-parts-${System.nanoTime()}").apply { mkdirs() }
        dirs += dir
        GrooveStore.save(dir, listOf(Mpc3Clip("Kit", 1, emptyList()), clip))
        return StepEditorState(dir, clip.name, scope) { edits += it }
    }

    private val clip = GrooveEdit.toggleStep(Mpc3Clip("Kit E", 1, emptyList()), GrooveEdit.Lane.SNARE, 4)

    @Test
    fun holding_a_lit_step_offers_four_levels_and_a_tap_writes_that_velocity() {
        val state = stateWith(clip)
        state.select(GrooveEdit.Lane.SNARE, 4)
        setPhoneContent {
            StepDetailStrip(state, GrooveEdit.Lane.SNARE, clip, LocalScheme.current, "SNARE", 0) { edits += it }
        }
        for (name in listOf(Copy.VEL_SOFT, Copy.VEL_MED, Copy.VEL_HARD, Copy.VEL_ACCENT)) button(Copy.stepVelocitySpoken(name)).assertExists()
        // The editor's own 0.9 reads as HARD.
        button(Copy.stepVelocitySpoken(Copy.VEL_HARD)).assertIsSelected()
        tap(Copy.stepVelocitySpoken(Copy.VEL_SOFT))
        val written = edits.last().change!!(clip)
        assertEquals(GrooveEdit.VelocityLevel.SOFT.velocity, GrooveEdit.velocityAt(written, GrooveEdit.Lane.SNARE, 4)!!, 0f)
    }

    @Test
    fun the_strip_offers_roll_nudge_and_length_and_a_roll_tap_writes_that_many_hits() {
        val state = stateWith(clip)
        state.select(GrooveEdit.Lane.SNARE, 4)
        setPhoneContent {
            StepDetailStrip(state, GrooveEdit.Lane.SNARE, clip, LocalScheme.current, "SNARE", 0) { edits += it }
        }
        for (n in 1..4) button(Copy.stepRollSpoken(n)).assertExists()
        button(Copy.stepRollSpoken(1)).assertIsSelected()
        for (name in listOf(Copy.STEP_EARLY, Copy.STEP_LATE, Copy.STEP_CENTRE)) button(name).assertExists()
        button(Copy.stepLengthSpoken(Copy.STEP_LEN_TIE_2)).assertExists()
        tap(Copy.stepRollSpoken(3))
        val written = edits.last().change!!(clip)
        assertEquals(3, com.snipsnap.kit.StepShape.rollOf(written, GrooveEdit.Lane.SNARE, 4))
    }

    @Test
    fun tapping_a_chance_sets_every_hit_of_a_rolled_step() {
        val rolled = com.snipsnap.kit.StepShape.setRoll(clip, GrooveEdit.Lane.SNARE, 4, 3)
        val state = stateWith(rolled)
        state.select(GrooveEdit.Lane.SNARE, 4)
        setPhoneContent {
            StepDetailStrip(state, GrooveEdit.Lane.SNARE, rolled, LocalScheme.current, "SNARE", 0) { edits += it }
        }
        for (c in com.snipsnap.kit.StepShape.CHANCES) button(Copy.stepChanceSpoken(c)).assertExists()
        button(Copy.stepChanceSpoken(100)).assertIsSelected()
        tap(Copy.stepChanceSpoken(50))
        val written = edits.last().change!!(rolled)
        assertEquals(3, written.notes.size)
        assertEquals(listOf(50, 50, 50), written.notes.map { it.chance })
    }

    @Test
    fun holding_an_empty_step_says_so_and_offers_no_levels() {
        val state = stateWith(clip)
        state.select(GrooveEdit.Lane.SNARE, 5)
        setPhoneContent {
            StepDetailStrip(state, GrooveEdit.Lane.SNARE, clip, LocalScheme.current, "SNARE", 0) { edits += it }
        }
        assertTrue(compose.onAllNodesWithContentDescription(Copy.stepVelocitySpoken(Copy.VEL_SOFT)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(Copy.STEP_DETAIL_EMPTY, useUnmergedTree = true).assertExists()
    }

    @Test
    fun choosing_a_pad_in_the_picker_reassigns_the_lane_and_writes_it_beside_the_clips() {
        val state = stateWith(clip)
        setPhoneContent {
            LanePadPicker(state, GrooveEdit.Lane.KICK, clip, kit, LocalScheme.current, "KICK")
        }
        // Sixteen pads on screen, the one with a sound named, the rest empty.
        for (slot in 1..16) {
            compose.onAllNodesWithContentDescription("PAD $slot: ", substring = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        }
        tap(Copy.stepPadSpoken(16, Copy.STEP_PAD_EMPTY))
        assertEquals(16, state.lanePads[GrooveEdit.Lane.KICK.ordinal])
        waitFor("the lane map on disk") { GrooveStore.loadLanePads(dirs.last())[clip.name]?.get(0) == 16 }
        assertTrue("the clip's notes are still there", GrooveStore.load(dirs.last()).last().notes.size == 1)
    }
}
