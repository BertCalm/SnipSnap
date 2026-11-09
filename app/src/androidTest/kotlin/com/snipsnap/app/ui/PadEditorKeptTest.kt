package com.snipsnap.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.PadEditorKeys
import com.snipsnap.synth.AquariumEditor
import com.snipsnap.synth.AquariumRenderRecipe
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pad editor's kept state through a GRAIN FIELD, SPLICE or STACK round trip (spec §7.3.1).
 *
 * The stand-in editor draws inside the same `SaveableStateHolder` wiring `App` uses and holds its
 * state with the same `rememberKept*` helpers `PadSheetScreen` does; the tool is a different screen
 * drawn in its place, so the editor leaves composition exactly as it does on device. One round trip
 * stands for the three tools: App draws all three the same way, by what `location.top` is. When the
 * editor leaves the stack for good, App's release effect calls [PadEditorKeys.release]; the test
 * calls it at the same point (its decision rules are `PadEditorKeepTest` in `:shell`).
 */
@RunWith(AndroidJUnit4::class)
class PadEditorKeptTest : ComposeScreenTest() {

    private companion object {
        const val TIDE = "aqua_tide_mix"
        val DEFAULT: Float = AquariumEditor(AquariumRenderRecipe()).render.settings.parameters.getValue(TIDE)
        val STAGED: Float = if (DEFAULT == 0.5f) 0.25f else 0.5f
    }

    private var onTool by mutableStateOf(false)
    private var slot by mutableIntStateOf(5)
    private var applied by mutableIntStateOf(0)
    private lateinit var keys: PadEditorKeys

    private fun host() = setPhoneContent {
        val holder = rememberSaveableStateHolder()
        keys = remember(holder) { PadEditorKeys(holder::removeState) }
        if (onTool) {
            Text("TOOL")
        } else {
            holder.SaveableStateProvider(keys.keyFor("/kits/a")) {
                var open by rememberKeptFlag("/kits/a", slot)
                val knobs = rememberKeptKnobs(slot, null)
                var become by rememberKeptFraction(slot, null, 0f)
                var move by rememberKeptName(slot, null, "REAMP")
                var draft by rememberKeptAquariumEditor(listOf(slot, applied).hashCode()) { AquariumEditor(AquariumRenderRecipe()) }
                Column {
                    Text(if (open) "FOLD OPEN" else "FOLD SHUT", Modifier.clickable { open = !open })
                    Text("KNOB ${knobs["REAMP"] ?: "none"}", Modifier.clickable { knobs["REAMP"] = 0.75f })
                    Text("BECOME $become", Modifier.clickable { become = 0.5f })
                    Text("MOVE $move", Modifier.clickable { move = "ROOM" })
                    Text(
                        "DRAFT ${draft.render.settings.parameters.getValue(TIDE)}",
                        Modifier.clickable { draft = draft.set(TIDE, STAGED) },
                    )
                    Text("PAD $slot")
                }
            }
        }
    }

    private fun toolAndBack() {
        compose.runOnUiThread { onTool = true }
        pump()
        compose.onNodeWithText("TOOL").assertExists()
        compose.onAllNodesWithText("FOLD", substring = true).assertCountEquals(0)
        compose.runOnUiThread { onTool = false }
        pump()
    }

    @Test
    fun foldsKnobsMovesAndTheDraftSurviveAToolOverTheEditor() {
        host()
        compose.onNodeWithText("FOLD SHUT").performClick()
        compose.onNodeWithText("KNOB none").performClick()
        compose.onNodeWithText("BECOME 0.0").performClick()
        compose.onNodeWithText("MOVE REAMP").performClick()
        compose.onNodeWithText("DRAFT $DEFAULT").performClick()
        // setPhoneContent freezes the clock, and performClick only waitForIdle's.
        // With autoAdvance off that does not run the frame, so the new text is
        // not composed until pump(), the same wait the other assertions here use.
        pump()
        compose.onNodeWithText("DRAFT $STAGED").assertExists()
        toolAndBack()
        compose.onNodeWithText("DRAFT $STAGED").assertExists()
        compose.onNodeWithText("FOLD OPEN").assertExists()
        compose.onNodeWithText("KNOB 0.75").assertExists()
        compose.onNodeWithText("BECOME 0.5").assertExists()
        compose.onNodeWithText("MOVE ROOM").assertExists()
    }

    @Test
    fun aDraftMadeAgainstASoundTheToolChangedIsDroppedNotRestored() {
        host()
        compose.onNodeWithText("DRAFT $DEFAULT").performClick()
        compose.runOnUiThread { onTool = true }
        pump()
        // SPLICE or STACK rewrote the pad: what the draft was started from is not what is applied now.
        compose.runOnUiThread { applied++; onTool = false }
        pump()
        compose.onNodeWithText("DRAFT $DEFAULT").assertExists()
    }

    @Test
    fun leavingTheStackForGoodDropsTheState() {
        host()
        compose.onNodeWithText("FOLD SHUT").performClick()
        compose.onNodeWithText("KNOB none").performClick()
        compose.runOnUiThread { onTool = true }
        pump()
        // Back out of the editor, then open it again: App's effect has released what it parked.
        compose.runOnUiThread { keys.release(); onTool = false }
        pump()
        compose.onNodeWithText("FOLD SHUT").assertExists()
        compose.onNodeWithText("KNOB none").assertExists()
    }

    @Test
    fun anotherPadOpenedWhileTheEditorIsParkedDoesNotInheritItsState() {
        host()
        compose.onNodeWithText("FOLD SHUT").performClick()
        compose.runOnUiThread { onTool = true }
        pump()
        // openPadSheet on pad 6 drops what pad 5 parked (PadEditorKeys.opened), then the editor draws.
        compose.runOnUiThread { keys.opened("/kits/a", 5); keys.opened("/kits/a", 6); slot = 6; onTool = false }
        pump()
        compose.onNodeWithText("PAD 6").assertExists()
        compose.onNodeWithText("FOLD SHUT").assertExists()
    }

    @Test
    fun steppingPadsInsideTheEditorStartsTheNextPadFromItsDefaults() {
        host()
        compose.onNodeWithText("FOLD SHUT").performClick()
        compose.runOnUiThread { slot = 6 }
        pump()
        compose.onNodeWithText("FOLD SHUT").assertExists()
        compose.onNodeWithText("FOLD SHUT").performClick()
        toolAndBack()
        compose.onNodeWithText("PAD 6").assertExists()
        compose.onNodeWithText("FOLD OPEN").assertExists()
    }
}
