package com.snipsnap.app.ui

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.tape
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Clarity spec D7 at 360 dp: a button or section label wraps to a second line
 * and only a name the player typed is cut short. The slider label columns are
 * a minimum that grows, the screen titles may take two lines.
 *
 * Old-code arithmetic: MacroSlider's label was `width(56.dp)` on one line, and
 * VELOCITY at 13 sp is ~59 dp, so it was drawn "VELOC..." and the
 * `isLineEllipsized` check failed. A label column that is only a minimum (up
 * to 96 dp) lets it through, and a longer one wraps instead.
 */
@RunWith(AndroidJUnit4::class)
class LabelWrapLayoutTest : ComposeScreenTest() {

    @Test
    fun macro_slider_velocity_reads_in_full_and_the_track_keeps_120_dp() {
        setPhoneContent {
            val scheme = LocalScheme.current
            MacroSlider("VELOCITY", .5f, scheme.amber.tape, scheme, onValueChange = {})
        }
        assertReadsInFull("VELOCITY")
        val track = compose.onNodeWithContentDescription("VELOCITY", useUnmergedTree = true).getBoundsInRoot().width
        assertTrue("the VELOCITY track is $track at 360 dp, under 120 dp", track >= 120.dp)
    }

    @Test
    fun macro_slider_longest_label_wraps_and_the_track_keeps_120_dp() {
        // Wider than the 96 dp cap, so it must wrap, not be cut short.
        val label = "GRAIN SIZE WINDOW"
        setPhoneContent {
            val scheme = LocalScheme.current
            MacroSlider(label, .5f, scheme.amber.tape, scheme, onValueChange = {})
        }
        assertNotCutShort(label)
        val track = compose.onNodeWithContentDescription(label, useUnmergedTree = true).getBoundsInRoot().width
        assertTrue("the track is $track at 360 dp, under 120 dp", track >= 120.dp)
    }

    @Test
    fun stepper_slider_alternates_reads_in_full_and_the_track_keeps_120_dp() {
        setPhoneContent {
            val scheme = LocalScheme.current
            StepperSlider("ALTERNATES", .5f, "4", scheme.amber.tape, scheme, true, {}, {})
        }
        assertReadsInFull("ALTERNATES")
        val track = compose.onNodeWithContentDescription("ALTERNATES", useUnmergedTree = true).getBoundsInRoot().width
        assertTrue("the ALTERNATES track is $track at 360 dp, under 120 dp", track >= 120.dp)
    }

    @Test
    fun find_similar_sounds_title_is_not_cut_short() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "wrap-doubles-${System.nanoTime()}").apply { mkdirs() }
        try {
            setPhoneContent {
                DoublesScreen(KitShelf(root), "◂ LIBRARY", {}, {}, { _, _ -> })
            }
            assertNotCutShort("FIND SIMILAR SOUNDS")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun saved_samples_title_is_not_cut_short() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "wrap-snips-${System.nanoTime()}").apply { mkdirs() }
        try {
            setPhoneContent {
                SnipsScreen(KitShelf(root), root, "◂ LIBRARY", {}, {}, {}, {}, {}, {}, {})
            }
            assertNotCutShort("SAVED SAMPLES")
        } finally {
            root.deleteRecursively()
        }
    }
}
