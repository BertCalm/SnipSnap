package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.mpc3.MpcXRay
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * X-RAY's back chip on a device: it reads the label the app hands it
 * (`Navigation.backChip`, so it names the screen Back lands on), says that
 * label aloud as "BACK TO ...", and a tap calls `onBack`. The reading is a
 * fixed unreadable one: this screen is read-only and pure, so what is under
 * the header does not matter to the chip, and an unreadable reading draws
 * without a list.
 *
 * Shown with [LocalReduceMotion] on, as `ComposeScreenTest`'s KDoc asks.
 */
@RunWith(AndroidJUnit4::class)
class XRayScreenTest : ComposeScreenTest() {

    private val backs = AtomicInteger(0)

    private fun show(backLabel: String) {
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                XRayScreen(
                    fileName = "demo.xpn",
                    reading = MpcXRay.Reading(kindLabel = "XPN EXPANSION", unreadable = "NOTHING TO READ IN THIS TEST"),
                    backLabel = backLabel,
                    onBack = { backs.incrementAndGet() },
                )
            }
        }
    }

    @Test
    fun the_back_chip_reads_the_label_it_is_given_in_full() {
        show("◄ SNIPS")
        assertReadsInFull("◄ SNIPS")
    }

    @Test
    fun the_back_chip_is_spoken_as_back_to_that_screen_and_a_tap_calls_onBack() {
        show("◄ SNIPS")

        button("BACK TO SNIPS").assertIsEnabled()
        assertEquals("nothing has closed it yet", 0, backs.get())
        button("BACK TO SNIPS").performClick()
        pump()
        assertEquals("one tap closes it once", 1, backs.get())
    }
}
