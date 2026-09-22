package com.snipsnap.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.PrintLength
import com.snipsnap.shell.TouchSurface.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SURFACE on a device: the half of the phone pass a machine can see.
 * Labels reading in full at a phone's width, the mode row selecting, the
 * print row's two buttons, the panel strip, and a second finger raising
 * Z. What only an ear can judge - the sound under the finger, MORPH's
 * blend, ECHO on the beat - stays on the checklist.
 *
 * The screen is laid out at [PHONE_WIDTH_DP], the narrow phone every
 * label has to survive, whatever device the suite happens to run on:
 * the first pass came back with "TA..." for → TAPE on a phone this wide,
 * and a test on a tablet would never have seen it.
 *
 * The clock, the finder helpers and the width check are [ComposeScreenTest]'s
 * own — this file used to carry its own copies of all three, word for
 * word identical to [GrooveScreenTest]'s, before they were extracted.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceScreenTest : ComposeScreenTest() {

    private val toasts = mutableListOf<String>()

    private fun show(entry: KitShelf.Entry?) {
        setPhoneContent {
            SurfaceScreen(
                entry = entry,
                onToast = { toasts += it },
                onPrinted = {},
                onKitUpdated = {},
            )
        }
    }

    /** A one-pad kit with a tempo, on disk where the screen can read it. */
    private fun tempoKit(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "surface-test-kit-${System.nanoTime()}").apply { mkdirs() }
        WavWriter.write(File(dir, "one.wav"), Snip(FloatArray(4410), channels = 1, sampleRate = 44_100))
        return KitShelf.Entry(dir, Kit(name = "TEST KIT", pads = listOf(KitPad(slot = 1, sampleFile = "one.wav")), tempoBpm = 92f))
    }

    private fun pad(mode: Mode) = compose.onNodeWithContentDescription("TOUCH SURFACE, ${mode.name} MODE")

    private fun padState(mode: Mode): String? =
        pad(mode).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun zOf(state: String?): Float? {
        if (state == null) return null
        return Regex("""Z (\d+\.\d+)""").find(state)?.groupValues?.get(1)?.toFloat()
    }

    @Test
    fun every_label_on_the_top_rows_reads_in_full_at_phone_width() {
        show(entry = null)
        // The mode row: the five modes alone.
        for (m in Mode.entries) assertReadsInFull(m.name)
        // The print row: where it lands, how long, and the button.
        assertReadsInFull("→ TAPE")
        assertReadsInFull("NO TEMPO")
        assertReadsInFull("PRINT")
        // The strip, and VOICE's first row.
        assertReadsInFull("VOICE")
        assertReadsInFull("SHAPE")
        assertReadsInFull("MOD")
        assertReadsInFull("◄ PAD")
        assertReadsInFull("PAD ►")
        assertReadsInFull("RING")
        assertReadsInFull("LATCH")
    }

    @Test
    fun the_print_row_reads_in_full_while_a_print_could_run() {
        show(entry = tempoKit())
        // With a tempo the bars button is live and its longest word is on it
        // after two taps; → TAPE flipped is → PAD.
        waitFor("the bars button") { compose.onAllNodesWithText("FREE", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tap("FREE")
        tap(PrintLength.label(PrintLength.BARS[1]))
        assertReadsInFull(PrintLength.label(PrintLength.BARS[2]))
        tap("→ TAPE")
        assertReadsInFull("→ PAD")
    }

    @Test
    fun tapping_a_mode_selects_it_and_the_pad_says_so() {
        show(entry = null)
        pad(Mode.XY).assertExists()
        button("XY").assertIsSelected()
        tap("VECTOR")
        button("VECTOR").assertIsSelected()
        pad(Mode.VECTOR).assertExists()
        pad(Mode.XY).assertDoesNotExist()
    }

    @Test
    fun the_print_destination_flips_between_tape_and_pad() {
        show(entry = null)
        button("→ TAPE").assertExists()
        button("→ PAD").assertDoesNotExist()
        tap("→ TAPE")
        button("→ PAD").assertExists()
        button("→ TAPE").assertDoesNotExist()
        tap("→ PAD")
        button("→ TAPE").assertExists()
    }

    @Test
    fun without_a_tempo_the_bars_button_says_so_and_is_off() {
        show(entry = null)
        button("NO TEMPO").assertIsNotEnabled()
        // PRINT needs a pad to print.
        button("PRINT").assertIsNotEnabled()
    }

    @Test
    fun with_a_tempo_the_bars_button_cycles_the_print_lengths() {
        show(entry = tempoKit())
        waitFor("the bars button") { compose.onAllNodesWithText("FREE", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val labels = PrintLength.BARS.map { PrintLength.label(it) }
        assertEquals("FREE", labels.first())
        for (i in labels.indices) {
            button(labels[i]).assertExists()
            tap(labels[i])
        }
        // Round the loop: back at FREE.
        button(labels.first()).assertExists()
    }

    @Test
    fun the_strip_shows_one_panel_at_a_time_and_the_print_row_stays() {
        show(entry = null)
        button("VOICE").assertIsSelected()
        button("LATCH").assertExists()
        button("SET A").assertDoesNotExist()
        tap("SHAPE")
        button("SET A").assertExists()
        button("LATCH").assertDoesNotExist()
        button("→ TAPE").assertExists()
        button("PRINT").assertExists()
        tap("MOD")
        button("MOD A").assertExists()
        button("SET A").assertDoesNotExist()
        button("→ TAPE").assertExists()
        button("PRINT").assertExists()
    }

    @Test
    fun a_second_finger_in_xyz_raises_z_and_a_first_finger_alone_does_not() {
        show(entry = null)
        tap("XYZ")
        waitFor("a Z in the pad's state") { zOf(padState(Mode.XYZ)) != null }
        assertEquals(0f, zOf(padState(Mode.XYZ)))
        // One finger down and held: Z stays where it was.
        pad(Mode.XYZ).performTouchInput { down(0, center) }
        pump()
        waitFor("the first finger at the centre") { padState(Mode.XYZ)?.contains("X 0.50") == true }
        assertEquals(0f, zOf(padState(Mode.XYZ)))
        // A second finger, slid from beside the first to the far corner: Z
        // is the gap over the pad's diagonal, so this lands near a half.
        pad(Mode.XYZ).performTouchInput {
            down(1, center + Offset(width / 8f, 0f))
            moveTo(1, Offset(width * 0.98f, height * 0.98f))
        }
        waitFor("Z above 0.3") { (zOf(padState(Mode.XYZ)) ?: 0f) > 0.3f }
        pad(Mode.XYZ).performTouchInput { up(1); up(0) }
        pump()
    }

    @Test
    fun outside_xyz_the_pad_reports_no_z() {
        show(entry = null)
        waitFor("the pad's state") { padState(Mode.XY) != null }
        assertFalse(padState(Mode.XY)!!.contains("Z "))
    }
}
