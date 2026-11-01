package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [pressTracking], the finger under every pad that sounds a held note. */
class PressTrackingTest : ComposeScreenTest() {
    private val log = mutableListOf<String>()
    private val padA = mutableStateOf(true)

    private fun show() {
        setPhoneContent {
            Row(Modifier.fillMaxSize()) {
                if (padA.value) {
                    Box(
                        Modifier.width(100.dp).fillMaxHeight()
                            .semantics { contentDescription = "PAD A" }
                            .pressTracking("a", { y, h, _, _ -> log += "A down ${"%.2f".format(java.util.Locale.ROOT, y / h)}" }, { log += "A up" }),
                    )
                }
                Box(
                    Modifier.width(100.dp).fillMaxHeight()
                        .semantics { contentDescription = "PAD B" }
                        .pressTracking("b", { _, _, _, _ -> log += "B down" }, { log += "B up" }),
                )
            }
        }
    }

    private fun boundsOf(label: String) = button(label).fetchSemanticsNode().boundsInRoot

    @Test
    fun a_touch_presses_where_it_lands_and_releases_when_it_lifts() {
        show()
        val a = boundsOf("PAD A")
        compose.onRoot().performTouchInput { down(0, Offset(a.center.x, a.top + a.height * 0.75f)) }
        pump()
        assertEquals(listOf("A down 0.75"), log)
        compose.onRoot().performTouchInput { up(0) }
        pump()
        assertEquals(listOf("A down 0.75", "A up"), log)
    }

    @Test
    fun two_fingers_on_two_pads_press_and_release_on_their_own() {
        show()
        val a = boundsOf("PAD A")
        val b = boundsOf("PAD B")
        compose.onRoot().performTouchInput {
            down(0, a.center)
            down(1, b.center)
        }
        pump()
        assertEquals(listOf("A down 0.50", "B down"), log)
        compose.onRoot().performTouchInput { up(0) }
        pump()
        assertEquals("lifting one finger leaves the other pad held", listOf("A down 0.50", "B down", "A up"), log)
        compose.onRoot().performTouchInput { up(1) }
        pump()
        assertEquals(listOf("A down 0.50", "B down", "A up", "B up"), log)
    }

    @Test
    fun a_pad_torn_down_under_a_finger_releases_its_note() {
        show()
        compose.onRoot().performTouchInput { down(0, boundsOf("PAD A").center) }
        pump()
        assertEquals(listOf("A down 0.50"), log)
        compose.runOnIdle { padA.value = false }
        pump()
        assertTrue("the release must not be skipped: $log", log.contains("A up"))
        compose.onRoot().performTouchInput { up(0) }
        pump()
        assertEquals("and only once", 1, log.count { it == "A up" })
    }

    @Test
    fun the_same_pad_presses_again_after_each_release() {
        show()
        val b = boundsOf("PAD B")
        repeat(3) {
            compose.onRoot().performTouchInput { down(0, b.center); up(0) }
            pump()
        }
        assertEquals(List(3) { listOf("B down", "B up") }.flatten(), log)
    }
}
