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
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.PadHit
import com.snipsnap.shell.StrikeStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real fader and pad gesture, without a device audio stream obscuring touch failures. */
class StrikeStripTest : ComposeScreenTest() {
    private val state = StrikeState()
    private val owner = mutableStateOf(state)
    private val kit = Kit("STRIKE TEST", listOf(KitPad(1, "snare.wav", displayName = "SNARE")))
    private val hits = mutableListOf<StrikeStrip.Hit>()

    private fun show() {
        setPhoneContent {
            val strike = owner.value
            val glow = remember { mapOf(1 to Animatable(0f)) }
            Column(Modifier.fillMaxSize()) {
                StrikeControls(strike, Modifier.fillMaxWidth())
                StrikePadArea(strike, Modifier.fillMaxWidth().height(360.dp)) {
                    PlayBank(
                        kit, windowRows(0), glow,
                        onHit = { _, velocity, _ -> hits += strike.hit(velocity) },
                        onRelease = { _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private fun fader() = button("STRIKE STRENGTH")
    private fun pad() = compose.onNodeWithContentDescription("PAD A01: SNARE")

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
        button("STRIKE STRIP").performClick()
        pump()
        fader().assertExists()
        button("STRIKE STRIP").assertIsSelected()
        for (label in listOf("STRIKE ON", "LEFT", "STAY", "VOL OFF")) assertReadsInFull(label)
        val bounds = pad().fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("pad keeps a 48dp touch target", bounds.width >= 48f * density && bounds.height >= 48f * density)
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
        button("STRIKE THUMB SIDE").performClick()
        pump()
        assertTrue(fader().fetchSemanticsNode().boundsInRoot.left > before)
        fader().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        pad().performClick()
        pump()
        assertEquals(1, PadHit.midiVelocity(hits.last().velocity))
        assertEquals(1f, hits.last().gainVelocity, 0.0001f)
        button("STRIKE CHANGES VOLUME").performClick()
        pad().performClick()
        pump()
        assertEquals(PadHit.SOFTEST, hits.last().gainVelocity, 0.0001f)
        button("STRIKE STRIP").performClick()
        pad().performClick()
        pump()
        assertEquals(PadHit.CENTER, hits.last().velocity, 0.0001f)
        assertEquals(PadHit.CENTER, hits.last().gainVelocity, 0.0001f)
    }
}
