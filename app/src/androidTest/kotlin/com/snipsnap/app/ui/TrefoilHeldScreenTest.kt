package com.snipsnap.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.Schemes
import com.snipsnap.synth.*
import androidx.compose.ui.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/** Real mode/performance controls preserve a complete old finite recipe on return. */
@RunWith(AndroidJUnit4::class)
class TrefoilHeldScreenTest : ComposeScreenTest() {
    private fun press(label: String) {
        val node=compose.onNodeWithContentDescription(label,useUnmergedTree=true).fetchSemanticsNode()
        assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke());pump()
    }
    @Test fun heldEntryLocksNudgeAndFiniteReturnKeepsItsOriginalModelAndTimbre() {
        val finite=TrefoilPatch("OLD FINITE",TrefoilVoice.WEAVE,
            Trefoil.defaults(TrefoilVoice.WEAVE)+("NUDGE" to .17f),midi=55,velocity=.37f,seed=Long.MIN_VALUE,model=1)
        var patch by mutableStateOf(finite);val settings=mutableMapOf<String,String>()
        setPhoneContent {
            TrefoilPerformanceControls(patch,settings,Color(0xffcc7744),Schemes.VENT) { change -> patch=change(patch) }
        }
        press("TREFOIL HELD")
        assertEquals(TrefoilPlayback.HELD,patch.playback);assertEquals(0f,patch.macros.getValue("NUDGE"));assertEquals(1f,patch.macros.getValue("HOLD"))
        assertEquals(finite.seed,patch.seed);assertEquals(55,patch.midi)
        press("TREFOIL stationary geometry");assertEquals(0f,patch.macros.getValue("WEAVE"))
        compose.runOnIdle { patch=patch.copy(midi=57,velocity=.31f,releaseSeconds=.9f) };pump()
        press("TREFOIL FINITE")
        assertEquals(1,patch.model);assertEquals(finite.macros,patch.macros);assertEquals(finite.seed,patch.seed)
        assertEquals(57,patch.midi);assertEquals(.31f,patch.velocity);assertEquals(.9f,patch.releaseSeconds)
        assertEquals(patch,TrefoilPatch.fromJsonText(patch.toJsonText()))
    }
}
