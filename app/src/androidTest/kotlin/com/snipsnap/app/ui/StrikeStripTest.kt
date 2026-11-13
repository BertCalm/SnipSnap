package com.snipsnap.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitLayer
import com.snipsnap.shell.PadHit
import com.snipsnap.shell.StrikeStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real fader and pad gesture, without a device audio stream obscuring touch failures. */
class StrikeStripTest : ComposeScreenTest() {
    private val state = StrikeState()
    private val owner = mutableStateOf(state)
    private val kit = Kit("STRIKE TEST", listOf(KitPad(1, "snare.wav", displayName = "SNARE", velocityLayers = listOf(
        KitLayer("dry.wav", 1, 41, "DRY"), KitLayer("room.wav", 42, 84, "REVERB"), KitLayer("snare.wav", 85, 127, "ECHO"),
    ))))
    private val hits = mutableListOf<StrikeStrip.Hit>()

    private fun show() {
        setPhoneContent {
            val strike = owner.value
            val glow = remember { mapOf(1 to Animatable(0f)) }
            Column(Modifier.fillMaxSize()) {
                StrikeControls(strike, Modifier.fillMaxWidth())
                StrikeLayerFeedback(strike, Modifier.fillMaxWidth())
                StrikePadArea(strike, Modifier.fillMaxWidth().height(360.dp)) {
                    PlayBank(
                        kit, windowRows(0), glow,
                        onHit = { slot, velocity, _ ->
                            val touch = strike.hit(velocity)
                            hits += touch
                            strike.recordHit(kit.pad(slot)!!, touch.velocity)
                        },
                        onRelease = { _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private fun fader() = button("VELOCITY")
    private fun pad() = compose.onNodeWithContentDescription("PAD A01: SNARE")

    private fun stateOf(label: String): String =
        button(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
            ?: error("\"$label\" has no stateDescription")

    @Test
    fun named_layer_highlight_follows_hits_and_does_not_move_with_a_returning_thumb() {
        state.enabled = true
        state.returns = true
        show()
        for ((index, amount) in listOf(0f, 0.5f, 1f).withIndex()) {
            fader().performSemanticsAction(SemanticsActions.SetProgress) { it(amount) }
            pad().performClick()
            pump()
            for ((i, name) in listOf("DRY", "REVERB", "ECHO").withIndex()) {
                val layer = compose.onNodeWithContentDescription("VELOCITY LAYER ${i + 1}: $name")
                if (i == index) layer.assertIsSelected() else layer.assertIsNotSelected()
                assertReadsInFull(name)
            }
        }
        compose.runOnIdle { state.releaseTouch() }
        pump()
        assertEquals(0f, state.strength, 0f)
        compose.onNodeWithContentDescription("VELOCITY LAYER 3: ECHO").assertIsSelected()
        pad().performClick()
        pump()
        compose.onNodeWithContentDescription("VELOCITY LAYER 1: DRY").assertIsSelected()
    }

    @Test
    fun a_reused_pad_reads_the_new_kits_strip_instead_of_its_old_pointer_callback() {
        state.enabled = true
        state.strength = 0f
        show()
        pad().performTouchInput { down(center); up() }
        pump()
        assertEquals(1, PadHit.midiVelocity(hits.last().velocity))
        compose.runOnIdle { owner.value = StrikeState(enabled = true, strength = 1f) }
        pump()
        pad().performTouchInput { down(center); up() }
        pump()
        assertEquals(1f, hits.last().velocity, 0.0001f)
    }

    @Test
    fun strip_is_optional_and_its_controls_fit_a_narrow_phone() {
        show()
        fader().assertDoesNotExist()
        button("VELOCITY STRIP").performClick()
        pump()
        fader().assertExists()
        button("VELOCITY STRIP").assertIsSelected()
        for (label in listOf("STRIP ON", "LEFT", "STAY", "VOL OFF")) assertReadsInFull(label)
        val bounds = pad().fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("pad keeps a 48dp touch target", bounds.width >= 48f * density && bounds.height >= 48f * density)
    }

    /**
     * 7d: PADS draws the four strip buttons inside a VELOCITY STRIP section that starts closed.
     * Opening it names each toggle by its caption, and a toggle in it still works.
     */
    @Test
    fun the_velocity_strip_section_starts_closed_names_each_toggle_and_a_toggle_still_works() {
        setPhoneContent {
            Column(Modifier.fillMaxSize()) { StrikeStripSection(owner.value, LocalScheme.current) }
        }
        // Closed: the heading and the strip's state are there, no toggle is.
        button("EXPAND VELOCITY STRIP").assertExists()
        compose.onAllNodesWithText("VELOCITY STRIP", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("STRIP OFF", useUnmergedTree = true).assertExists()
        for (label in listOf("VELOCITY STRIP", "VELOCITY STRIP THUMB SIDE", "VELOCITY STRIP ON RELEASE", "VELOCITY STRIP CHANGES VOLUME")) {
            button(label).assertDoesNotExist()
        }
        for (caption in listOf("STRIP", "SIDE", "RELEASE", "LEVEL")) {
            compose.onAllNodesWithTag("strike-caption-$caption", useUnmergedTree = true).assertCountEquals(0)
        }

        button("EXPAND VELOCITY STRIP").performClick()
        pump()
        // Open: every toggle is there, each under the caption saying what it sets.
        button("COLLAPSE VELOCITY STRIP").assertExists()
        for (caption in listOf("STRIP", "SIDE", "RELEASE", "LEVEL")) {
            assertTrue("caption $caption is on screen", compose.onAllNodesWithTag("strike-caption-$caption", useUnmergedTree = true).fetchSemanticsNodes().size == 1)
        }
        for (label in listOf("VELOCITY STRIP", "VELOCITY STRIP THUMB SIDE", "VELOCITY STRIP ON RELEASE", "VELOCITY STRIP CHANGES VOLUME")) {
            button(label).assertExists()
        }
        for (text in listOf("LEFT", "STAY", "VOL OFF")) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertExists()
        }

        // A toggle still works, and the closed summary follows the state.
        button("VELOCITY STRIP").performClick()
        pump()
        assertTrue(state.enabled)
        assertEquals("STRIP ON", stateOf("VELOCITY STRIP"))
        button("VELOCITY STRIP ON RELEASE").performClick()
        pump()
        assertTrue(state.returns)
        button("COLLAPSE VELOCITY STRIP").performClick()
        pump()
        button("VELOCITY STRIP ON RELEASE").assertDoesNotExist()
        assertTrue("closing the section keeps the setting", state.returns)
        // Closed again, the section's own summary now reads ON (the one remaining text of that name).
        compose.onNodeWithText("STRIP ON", useUnmergedTree = true).assertExists()
    }

    /**
     * Side, return and volume do nothing while the strip is off, so each says it waits for STRIKE
     * (what a screen reader hears where a sighted player sees the quieter ink). They are dimmed,
     * not disabled: a tap while the strip is off still lands and is still there when it comes on.
     */
    @Test
    fun the_settings_behind_strike_say_they_wait_for_it_and_keep_their_value() {
        show()
        val waits = "takes effect when the VELOCITY STRIP is on"
        val behind = listOf("VELOCITY STRIP THUMB SIDE", "VELOCITY STRIP ON RELEASE", "VELOCITY STRIP CHANGES VOLUME")
        for (label in behind) assertTrue("$label should say it waits for STRIKE", stateOf(label).endsWith(waits))
        assertEquals("STRIKE itself never waits on the strip it turns on", "STRIP OFF", stateOf("VELOCITY STRIP"))

        button("VELOCITY STRIP ON RELEASE").performClick()
        pump()
        assertTrue("a tap while the strip is off is not swallowed", state.returns)
        button("VELOCITY STRIP ON RELEASE").assertIsSelected()
        assertEquals("RETURN, $waits", stateOf("VELOCITY STRIP ON RELEASE"))

        button("VELOCITY STRIP").performClick()
        pump()
        for (label in behind) assertFalse("$label still says it waits once STRIKE is on", stateOf(label).endsWith(waits))
        assertEquals("RETURN", stateOf("VELOCITY STRIP ON RELEASE"))
        button("VELOCITY STRIP ON RELEASE").assertIsSelected()
        assertEquals("STRIP ON", stateOf("VELOCITY STRIP"))
    }

    @Test
    fun thumb_can_move_while_another_finger_hits_without_retriggering_the_pad() {
        state.enabled = true
        show()
        val slider = fader().fetchSemanticsNode().boundsInRoot
        val pad = pad().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(0, Offset(slider.center.x, slider.bottom - 2f))
            down(1, pad.center)
        }
        pump()
        assertEquals(1, hits.size)
        assertEquals(1, PadHit.midiVelocity(hits[0].velocity))
        compose.onRoot().performTouchInput {
            moveTo(0, Offset(slider.center.x, slider.top + 2f))
        }
        pump()
        assertEquals("moving a thumb is not another pad hit", 1, hits.size)
        assertEquals("the old note retains its strength", 1, PadHit.midiVelocity(hits[0].velocity))
        compose.onRoot().performTouchInput { up(1) }
        pump()
        compose.onRoot().performTouchInput { down(1, pad.center); up(1); up(0) }
        pump()
        assertEquals(2, hits.size)
        assertEquals(1f, hits[1].velocity, 0.0001f)
        assertTrue("volume stays independent", hits.all { it.gainVelocity == 1f })
        assertEquals(1f, state.strength, 0.0001f)
    }

    @Test
    fun return_resets_on_finger_up_and_on_cancel() {
        state.enabled = true
        state.returns = true
        show()
        fader().performTouchInput { down(0, Offset(center.x, 2f)); up(0) }
        pump()
        assertEquals(0f, state.strength, 0f)
        fader().performTouchInput { down(0, Offset(center.x, 2f)) }
        pump()
        assertTrue(state.strength > 0.9f)
        fader().performTouchInput { cancel() }
        pump()
        assertEquals(0f, state.strength, 0f)
    }

    @Test
    fun side_and_accessible_adjustments_change_the_same_live_strip() {
        state.enabled = true
        show()
        val before = fader().fetchSemanticsNode().boundsInRoot.left
        button("VELOCITY STRIP THUMB SIDE").performClick()
        pump()
        assertTrue(fader().fetchSemanticsNode().boundsInRoot.left > before)
        fader().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        pad().performClick()
        pump()
        assertEquals(1, PadHit.midiVelocity(hits.last().velocity))
        assertEquals(1f, hits.last().gainVelocity, 0.0001f)
        button("VELOCITY STRIP CHANGES VOLUME").performClick()
        pad().performClick()
        pump()
        assertEquals(PadHit.SOFTEST, hits.last().gainVelocity, 0.0001f)
        button("VELOCITY STRIP").performClick()
        pad().performClick()
        pump()
        assertEquals(PadHit.CENTER, hits.last().velocity, 0.0001f)
        assertEquals(PadHit.CENTER, hits.last().gainVelocity, 0.0001f)
    }
}
