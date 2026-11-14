package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.SynthRoot
import com.snipsnap.synth.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * Actual performance controls: ordinary root/sweep/HOLD edits never strand a saved chord.
 * ROOT lives in the screen's shared ROOT/OCT row, not in this panel; [rootAt] applies a
 * fraction exactly as that row's SetProgress does, through [SynthRoot].
 */
@RunWith(AndroidJUnit4::class)
class OsteriaStrumControlsTest : ComposeScreenTest() {
    private fun press(label:String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        pump()
    }
    private fun dial(label:String,value:Float) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
        pump()
    }
    private fun rootAt(patch: OsteriaPatch, fraction: Float): OsteriaPatch {
        val control = SynthRoot.of(patch)!!
        val span = (control.range.last - control.range.first).coerceAtLeast(1)
        return control.at((control.range.first + fraction.coerceIn(0f, 1f) * span).roundToInt()) as OsteriaPatch
    }

    @Test fun explicitStrumRootBoundsAndSingleNoteReturnPreserveSource() {
        val original=OsteriaPresets.forVoice(OsteriaVoice.PLUCK).first()
        var patch by mutableStateOf(original)
        setPhoneContent { OsteriaPerformanceControls(patch,Color(0xffcc7744),Schemes.VENT) { patch=it(patch) } }
        compose.onNodeWithContentDescription("ROOT").assertDoesNotExist()
        press("OSTERIA STRUM")
        assertEquals(listOf(0,3,7),patch.strum!!.intervals)
        compose.runOnIdle { patch=rootAt(patch,1f) }; pump()
        assertEquals(77,patch.midi)
        assertTrue(patch.strum!!.notes(patch.midi).all { it in 36..84 })
        compose.runOnIdle { patch=rootAt(patch,0f) }; pump()
        assertEquals(36,patch.midi)
        press("OSTERIA STRUM MAJOR")
        press("OSTERIA STRUM DIRECTION")
        dial("SWEEP",1f)
        assertEquals(listOf(0,4,7),patch.strum!!.intervals)
        assertEquals(OsteriaStrum.Direction.UP,patch.strum!!.direction)
        assertEquals(.24,patch.strum!!.sweepSeconds,0.0)
        for (hold in listOf(0f,.5f,1f)) {
            compose.runOnIdle { patch=Patches.edited(patch,patch.name,patch.macros+("HOLD" to hold)) as OsteriaPatch }
            pump()
            assertEquals(hold,patch.macros.getValue("HOLD"))
            assertNotNull(patch.strum)
        }
        assertEquals(patch,OsteriaPatch.fromJsonText(patch.toJsonText()))
        val oldSweep = compose.onNodeWithContentDescription("SWEEP").fetchSemanticsNode()
            .config[SemanticsActions.SetProgress].action!!
        press("OSTERIA SINGLE NOTE")
        compose.runOnIdle { assertTrue(oldSweep(.5f)) }
        pump()
        assertNull(patch.strum)
        assertEquals(original.config,patch.config)
        assertEquals(1,patch.toJsonValue().entries.getValue("version").int())
        compose.runOnIdle { patch=rootAt(patch,1f) }; pump()
        assertEquals(84,patch.midi)
    }

    @Test fun loadedMaximumSpanChordHasAStableSingleRootSlider() {
        var patch by mutableStateOf(OsteriaPresets.forVoice(OsteriaVoice.PLUCK).first().copy(
            midi=36,strum=OsteriaStrum.Articulation(listOf(0,48))))
        setPhoneContent { OsteriaPerformanceControls(patch,Color(0xffcc7744),Schemes.VENT) { patch=it(patch) } }
        compose.runOnIdle { patch=rootAt(patch,1f) }; pump()
        assertEquals(36,patch.midi)
        assertEquals(listOf(36,84),patch.strum!!.notes(patch.midi))
    }
}
