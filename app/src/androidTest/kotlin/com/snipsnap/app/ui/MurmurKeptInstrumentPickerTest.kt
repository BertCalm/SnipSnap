package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.MurmurKeptInstrumentPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MurmurKeptInstrumentPickerTest : ComposeScreenTest() {
    @Test fun realInstrumentsBenchOffersThreeKeptSettingsAndDispatchesStableIds() {
        val choices = mutableListOf<String>()
        setPhoneContent {
            InstrumentsBench(emptyList(), "MAKE", {}, {}, onMakeMurmurInstrument = { choices += it })
        }
        for (choice in MurmurKeptInstrumentPicker.choices) {
            button("MURMUR PLUCK INSTRUMENTS").performSemanticsAction(SemanticsActions.OnClick)
            pump()
            assertTrue(compose.onAllNodesWithText("${choice.label} · ${choice.range}", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty())
            button("MAKE MURMUR ${choice.label}").performSemanticsAction(SemanticsActions.OnClick)
            pump()
        }
        assertEquals(MurmurKeptInstrumentPicker.choices.map { it.id }, choices)
    }

    @Test fun busyInstrumentsBenchDoesNotDispatchAnotherMurmurCreation() {
        val choices = mutableListOf<String>()
        setPhoneContent {
            InstrumentsBench(emptyList(), "MAKE", {}, {}, onMakeMurmurInstrument = { choices += it },
                murmurInstrumentEnabled = false)
        }
        button("MURMUR PLUCK INSTRUMENTS").assertIsNotEnabled()
        pump()
        assertTrue(compose.onAllNodesWithText("MURMUR PLUCK", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(choices.isEmpty())
    }
}
