package com.snipsnap.shell

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The player's view of the tape, carried across the deck that replaces it.
 *
 * J22 in `docs/UX_JOURNEY_PLAN_2026_09.md`: a snip landing anywhere in the
 * app reloads TAPE, and the reload builds a fresh [TapeDeckModel] — so the
 * zoom the player pinched to and the readout they picked went back to
 * defaults under their finger. The quick-settings SNIP tile makes that
 * reachable without ever leaving the screen, and the only toasts on that
 * path are OOM and truncation, so nothing said it had happened.
 *
 * The split this tests is the whole point. **Zoom and readout describe the
 * player**, so they carry. **Position and selection describe the tape** —
 * they are frame offsets into one particular recording, and carrying them
 * onto a different tape would put IN/OUT somewhere nobody chose. The
 * existing idle guard already refuses a reload while the deck is playing or
 * holding a selection; this is for the reload that is allowed to happen.
 */
class TapeDeckViewTest {

    private val rate = 44_100

    private fun deck(seconds: Int = 10): TapeDeckModel {
        val tape = FloatArray(rate * seconds) { sin(2 * PI * 220 * it / rate).toFloat() }
        return TapeDeckModel(tape, rate, intArrayOf(rate, rate * 3))
    }

    private val lo = TapeDeckModel.ZOOM_PX_PER_SEC.first().toFloat()
    private val hi = TapeDeckModel.ZOOM_PX_PER_SEC.last().toFloat()

    // ---- what the view is ----

    @Test
    fun `a fresh deck opens at the first rung, in real time`() {
        val d = deck()
        assertEquals(lo, d.view.pxPerSec)
        assertFalse(d.view.odometer)
    }

    @Test
    fun `the view reports what the player set`() {
        val d = deck()
        d.cycleZoom()
        d.toggleOdometer()
        assertEquals(d.pxPerSec, d.view.pxPerSec)
        assertTrue(d.view.odometer)
    }

    // ---- carrying it ----

    @Test
    fun `zoom and readout survive onto a fresh deck`() {
        val old = deck()
        old.cycleZoom()
        old.toggleOdometer()
        val fresh = deck(seconds = 4)
        fresh.restoreView(old.view)
        assertEquals(old.pxPerSec, fresh.pxPerSec)
        assertTrue(fresh.odometer)
    }

    /**
     * The half that must NOT carry.
     *
     * `position`, `inFrame` and `outFrame` are frame offsets into one
     * particular tape. A four-second tape given a selection measured on a
     * ten-second one would show IN and OUT somewhere the player never put
     * them — or past its own end. The view is the player's; the marks are
     * the tape's.
     */
    @Test
    fun `position and selection do not carry onto a different tape`() {
        val old = deck()
        // `select` is the direct route: `setIn`/`setOut` mark wherever the
        // playhead currently is, which makes the setup about the transport
        // rather than about what is being tested.
        old.select(rate * 7, rate * 9)
        assertTrue(old.hasSelection, "the source deck was supposed to hold a selection")
        assertTrue(old.position > 0.0, "the source deck was supposed to have moved off the top")

        val fresh = deck(seconds = 4)
        fresh.restoreView(old.view)
        assertEquals(0.0, fresh.position, "a carried view moved the playhead")
        assertFalse(fresh.hasSelection, "a carried view brought IN/OUT from another tape")
    }

    // ---- a view that cannot be honoured as given ----

    @Test
    fun `a zoom outside the ladder is clamped rather than honoured`() {
        val below = deck().also { it.restoreView(TapeDeckModel.View(lo / 100f, odometer = false)) }
        val above = deck().also { it.restoreView(TapeDeckModel.View(hi * 100f, odometer = false)) }
        assertEquals(lo, below.pxPerSec)
        assertEquals(hi, above.pxPerSec)
    }

    /**
     * `coerceIn` propagates NaN rather than clamping it, and a NaN
     * px-per-second reaches the waveform as a column count — so this is the
     * one bad value that would not merely look wrong but draw nothing at
     * all, with no exception to say why.
     */
    @Test
    fun `a nonsense zoom falls back to the first rung instead of propagating`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            val d = deck()
            d.restoreView(TapeDeckModel.View(bad, odometer = false))
            assertTrue(d.pxPerSec.isFinite(), "pxPerSec became ${d.pxPerSec} from $bad")
            assertTrue(d.pxPerSec in lo..hi, "pxPerSec became ${d.pxPerSec} from $bad")
        }
    }

    // ---- the carrier, which is what the screen actually holds ----

    @Test
    fun `the first deck a carrier sees is left exactly as it opened`() {
        val carrier = TapeDeckModel.ViewCarrier()
        val first = deck()
        carrier.adopt(first)
        assertEquals(lo, first.pxPerSec, "the first deck had a view imposed on it from nowhere")
        assertFalse(first.odometer)
    }

    @Test
    fun `a carrier hands the previous deck's view to the next one`() {
        val carrier = TapeDeckModel.ViewCarrier()
        val first = deck()
        carrier.adopt(first)
        first.cycleZoom()
        first.toggleOdometer()

        val second = deck(seconds = 3)
        carrier.adopt(second)
        assertEquals(first.pxPerSec, second.pxPerSec)
        assertTrue(second.odometer)
    }

    @Test
    fun `a carrier follows the newest deck, not the one it started on`() {
        val carrier = TapeDeckModel.ViewCarrier()
        val first = deck().also(carrier::adopt)
        first.cycleZoom()

        val second = deck().also(carrier::adopt)
        second.cycleZoom()
        second.toggleOdometer()

        val third = deck().also(carrier::adopt)
        assertEquals(second.pxPerSec, third.pxPerSec, "the carrier handed on a view two decks stale")
        assertTrue(third.odometer)
    }

    /**
     * The reload is the point of all this: whatever the player had set up
     * is what they still see afterwards.
     */
    @Test
    fun `a reload leaves the player looking at the tape the way they had it`() {
        val carrier = TapeDeckModel.ViewCarrier()
        val before = deck().also(carrier::adopt)
        repeat(2) { before.cycleZoom() }
        before.toggleOdometer()
        val zoomed = before.pxPerSec

        // A snip lands: TAPE rebuilds its deck against the new audio.
        val after = deck(seconds = 6).also(carrier::adopt)

        assertEquals(zoomed, after.pxPerSec)
        assertTrue(after.odometer)
        assertEquals(before.zoomLabel, after.zoomLabel, "the ZOOM button would read differently")
    }
}
