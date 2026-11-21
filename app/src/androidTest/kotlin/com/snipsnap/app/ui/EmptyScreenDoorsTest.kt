package com.snipsnap.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.Copy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The zero-data screens' ways forward (UAT 2026-11 user states, D-6, ruled 2026-11-21: each
 * gets one, even where that is LISTEN outside the dock). CREATE said "Open a kit for new pads."
 * with no door to a kit, and INSTRUMENTS said "No instruments yet…" with no door to SYNTH.
 * SAVED SAMPLES' LISTEN · MIC is wired the same way and is read by the same law of ButtonTierTest
 * (law 5) that holds its panel to its content.
 */
@RunWith(AndroidJUnit4::class)
class EmptyScreenDoorsTest : ComposeScreenTest() {

    @Test
    fun create_with_no_kit_offers_the_library() {
        var opened = 0
        setPhoneContent { MakeList(onOpen = {}, hasKit = false, onOpenLibrary = { opened++ }) }
        tap(Copy.LIBRARY_DOOR)
        assertEquals(1, opened)
    }

    @Test
    fun create_with_a_kit_open_draws_no_library_door() {
        setPhoneContent { MakeList(onOpen = {}, hasKit = true, onOpenLibrary = {}) }
        button(Copy.LIBRARY_DOOR).assertDoesNotExist()
    }

    @Test
    fun an_empty_instruments_list_offers_synth() {
        var opened = 0
        setPhoneContent { InstrumentsBench(emptyList(), "MAKE", {}, {}, onOpenSynth = { opened++ }) }
        tap(Copy.SYNTH_DOOR)
        assertEquals(1, opened)
    }

    /** The positive control: with nothing wired, the list is the line alone, as before. */
    @Test
    fun an_empty_instruments_list_with_no_synth_wired_draws_no_door() {
        setPhoneContent { InstrumentsBench(emptyList(), "MAKE", {}, {}) }
        button(Copy.SYNTH_DOOR).assertDoesNotExist()
    }
}
