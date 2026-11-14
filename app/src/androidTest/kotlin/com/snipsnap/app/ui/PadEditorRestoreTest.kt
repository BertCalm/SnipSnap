package com.snipsnap.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.PadEditorKeys
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pad editor's parked state across process death. The holder's state goes into the instance
 * Bundle and comes back; navigation does not, so the editor is not in the stack when the process
 * returns and the next pad opened on the same kit must start from its defaults, not pad 3's.
 *
 * Uses [rememberPadEditorKeys], the call `App` makes. With the old wiring (`remember { PadEditorKeys(..) }`)
 * the restored keys object has an empty key list, `opened()` releases nothing, and pad 7 draws
 * under the restored `pad-editor:/kits/a` entry, so "FOLD OPEN" shows and this test fails.
 */
@RunWith(AndroidJUnit4::class)
class PadEditorRestoreTest : ComposeScreenTest() {

    private var slot by mutableIntStateOf(3)
    private var openedAfterRestore by mutableStateOf(false)

    // Not snapshot state: flipping it must not recompose the old composition before it is saved.
    private var restoredProcess = false

    @Test
    fun aPadOpenedAfterProcessDeathStartsFromDefaultsNotThePreviousPadsState() {
        val restorer = StateRestorationTester(compose)
        var keysRef: PadEditorKeys? = null
        restorer.setContent {
            val holder = rememberSaveableStateHolder()
            val keys = rememberPadEditorKeys(holder)
            keysRef = keys
            // As in App: navigation is not restored, so after process death the editor is not in the stack until opened.
            val editorUp = !restoredProcess || openedAfterRestore
            ReleasePadEditorWhenGone(editorUp, keys)
            if (editorUp) {
                holder.SaveableStateProvider(keys.keyFor("/kits/a")) {
                    var open by rememberKeptFlag("/kits/a", slot)
                    Column {
                        Text(if (open) "FOLD OPEN" else "FOLD SHUT", Modifier.clickable { open = !open })
                        Text("PAD $slot")
                    }
                }
            }
        }
        compose.runOnUiThread { keysRef!!.opened("/kits/a", 3) }
        compose.onNodeWithText("FOLD SHUT").performClick()
        compose.onNodeWithText("FOLD OPEN").assertExists()

        restoredProcess = true
        restorer.emulateSavedInstanceStateRestore()
        compose.onAllNodesWithText("FOLD", substring = true).assertCountEquals(0)

        // The user opens pad 7 on the same kit, as App's openPadSheet does.
        compose.runOnUiThread { keysRef!!.opened("/kits/a", 7); slot = 7; openedAfterRestore = true }
        compose.waitForIdle()
        compose.onNodeWithText("PAD 7").assertExists()
        compose.onNodeWithText("FOLD SHUT").assertExists()
    }
}
