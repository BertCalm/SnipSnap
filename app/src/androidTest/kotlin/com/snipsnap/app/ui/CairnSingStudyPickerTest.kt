package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.CairnSingInstrumentPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CairnSingStudyPickerTest : ComposeScreenTest() {
    @Test fun realInstrumentsBenchOffersAllFiveStudiesAndDispatchesStableIds() {
        val choices = mutableListOf<String>()
        setPhoneContent {
            InstrumentsBench(emptyList(), "MAKE", {}, {}, onMakeCairnStudy = { choices += it })
        }
        for (choice in CairnSingInstrumentPicker.choices) {
            button("CAIRN SING INSTRUMENTS").performSemanticsAction(SemanticsActions.OnClick)
            pump()
            assertTrue(compose.onAllNodesWithText("${choice.label} · ${choice.range}", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty())
            button("MAKE CAIRN ${choice.label}").performSemanticsAction(SemanticsActions.OnClick)
            pump()
        }
        assertEquals(CairnSingInstrumentPicker.choices.map { it.id }, choices)
    }

    @Test fun busyInstrumentsBenchDoesNotDispatchAnotherCairnCreation() {
        val choices = mutableListOf<String>()
        setPhoneContent {
            InstrumentsBench(emptyList(), "MAKE", {}, {}, onMakeCairnStudy = { choices += it },
                cairnStudyEnabled = false)
        }
        button("CAIRN SING INSTRUMENTS").assertIsNotEnabled()
        pump()
        assertTrue(compose.onAllNodesWithText("CAIRN SING", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(choices.isEmpty())
    }
}
