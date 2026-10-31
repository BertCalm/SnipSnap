package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.kit.Kit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * PAD CAPTURE's back chip on a device: it reads the label the app hands it
 * (`Navigation.backChip`, so it names the screen Back lands on), says that
 * label aloud as "BACK TO ...", and a tap calls `onBack`. The mic is not armed,
 * so the screen shows its START MIC state and nothing is recorded: this
 * screen's chip does not depend on what is under it.
 *
 * Shown with [LocalReduceMotion] on, as `ComposeScreenTest`'s KDoc asks.
 */
@RunWith(AndroidJUnit4::class)
class PadCaptureScreenTest : ComposeScreenTest() {

    private val backs = AtomicInteger(0)

    private fun show(backLabel: String) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val entry = KitShelf.Entry(File(cache, "capture-test-kit"), Kit(name = "CAPTURE UI TEST", pads = emptyList()))
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                PadCaptureScreen(
                    entry = entry,
                    slot = 3,
                    armed = false,
                    onRequestArm = {},
                    backLabel = backLabel,
                    onBack = { backs.incrementAndGet() },
                    onToast = {},
                    onKitUpdated = {},
                    appScope = rememberCoroutineScope(),
                )
            }
        }
    }

    @Test
    fun the_back_chip_reads_the_label_it_is_given_in_full() {
        show("◄ PADS")
        assertReadsInFull("◄ PADS")
    }

    @Test
    fun the_back_chip_is_spoken_as_back_to_that_screen_and_a_tap_calls_onBack() {
        show("◄ PADS")

        button("BACK TO PADS").assertIsEnabled()
        assertEquals("nothing has closed it yet", 0, backs.get())
        button("BACK TO PADS").performClick()
        pump()
        assertEquals("one tap closes it once", 1, backs.get())
    }
}
