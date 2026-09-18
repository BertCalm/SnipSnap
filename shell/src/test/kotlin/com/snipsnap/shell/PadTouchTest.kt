package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Where in a pad the finger landed, as a velocity.
 *
 * J37 in `docs/UX_JOURNEY_PLAN_2026_09.md`: SOFT HITS builds real
 * velocity-layer WAVs and `PadHit.resolve` has always picked a layer by
 * velocity — but the KIT grid passed a hard-coded `1f`, so the feature
 * could be switched on and never heard. The missing piece was never the
 * audio path; it was that a tap carries no force, so nothing produced a
 * velocity to pass.
 *
 * Tap position is that something. The rule is only useful if it actually
 * reaches the zones `StackTakes.windows` lays out, which is what the
 * second half of this file checks against the real boundaries rather than
 * against a number chosen to make the test pass.
 */
class PadTouchTest {

    // ---- the mapping ----

    @Test
    fun `the top of a pad is the hardest hit`() {
        assertEquals(1f, PadHit.velocityAt(0f, 100f))
    }

    @Test
    fun `the bottom is the softest, and never silent`() {
        val v = PadHit.velocityAt(100f, 100f)
        assertEquals(PadHit.SOFTEST, v)
        assertTrue(v > 0f, "a pad that makes no sound reads as broken, not as soft")
    }

    @Test
    fun `lower is always softer`() {
        var previous = Float.MAX_VALUE
        for (y in 0..100 step 5) {
            val v = PadHit.velocityAt(y.toFloat(), 100f)
            assertTrue(v < previous, "velocity rose going down the pad at y=$y")
            previous = v
        }
    }

    /**
     * A pointer can be reported slightly outside the cell it went down in —
     * a fingertip overlapping the padding, a coordinate rounded past the
     * edge. Clamped, not extrapolated: outside the top is the hardest hit,
     * not louder than one.
     */
    @Test
    fun `a touch reported outside the cell is clamped, not extrapolated`() {
        assertEquals(1f, PadHit.velocityAt(-20f, 100f))
        assertEquals(PadHit.SOFTEST, PadHit.velocityAt(150f, 100f))
    }

    /**
     * A cell with no height cannot divide. Answering full velocity is the
     * safe direction: the pad plays exactly as it did before this existed.
     * The same lesson as `TapeDeckModel.restoreView`'s non-finite zoom and
     * `Audition.barOf`'s zero `stepsPerBar` — a degenerate input must not
     * reach the audio path as NaN.
     */
    @Test
    fun `a cell with no height plays at full velocity rather than dividing by zero`() {
        for (h in listOf(0f, -10f, Float.NaN)) {
            val v = PadHit.velocityAt(50f, h)
            assertTrue(v.isFinite(), "height $h produced $v")
            assertEquals(1f, v, "height $h should fall back to a full hit")
        }
    }

    // ---- does it actually reach the layers? ----

    private fun ghosted(softZones: Int): KitPad {
        val windows = StackTakes.windows(softZones)
        val layers = windows.mapIndexed { i, w -> KitLayer("layer$i.wav", w.first, w.last) }
        return KitPad(slot = 1, sampleFile = "layer${windows.lastIndex}.wav", displayName = "SNARE", drumClass = DrumClass.SNARE, velocityLayers = layers)
    }

    private fun fileAt(pad: KitPad, y: Float, height: Float): String? =
        PadHit.resolve(pad, PadHit.velocityAt(y, height), hitIndex = 0) { 1_000L }?.sampleFile

    /**
     * The point of the whole change: the bottom of the pad has to land in
     * the softest zone, and the top in the live one. Checked against
     * `StackTakes.windows`'s real boundaries — with one soft zone the split
     * is at MIDI 63, with two it is at 41, so a floor chosen by eye could
     * easily clear one and miss the other.
     */
    @Test
    fun `the bottom of a ghosted pad plays the softest take and the top plays the live one`() {
        for (softZones in 1..2) {
            val pad = ghosted(softZones)
            val soft = fileAt(pad, y = 100f, height = 100f)
            val hard = fileAt(pad, y = 0f, height = 100f)
            assertEquals("layer0.wav", soft, "$softZones soft zone(s): the bottom of the pad missed the softest take")
            assertEquals("layer$softZones.wav", hard, "$softZones soft zone(s): the top of the pad missed the live take")
            assertNotEquals(soft, hard, "$softZones soft zone(s): SOFT HITS is still inaudible from the grid")
        }
    }

    /**
     * A pad with no layers is unaffected in *which file* it plays — there
     * is only one — but it is still hit softer, which is what makes the
     * gesture consistent rather than a special case for ghosted pads.
     */
    @Test
    fun `an unlayered pad still plays quieter from the bottom of the cell`() {
        val pad = KitPad(slot = 1, sampleFile = "one.wav", displayName = "KICK", drumClass = DrumClass.KICK)
        val soft = PadHit.resolve(pad, PadHit.velocityAt(100f, 100f), 0) { 1_000L }!!
        val hard = PadHit.resolve(pad, PadHit.velocityAt(0f, 100f), 0) { 1_000L }!!
        assertEquals(hard.sampleFile, soft.sampleFile)
        assertTrue(soft.gainLeft < hard.gainLeft, "a soft tap was not quieter")
        assertTrue(soft.gainRight < hard.gainRight, "a soft tap was not quieter")
    }

    /**
     * The floor has to be audible on its own terms, not merely non-zero:
     * `midiVelocity` rounds, and a floor that rounded to 0 would resolve to
     * silence through `gains`.
     */
    @Test
    fun `the softest tap is still a real MIDI velocity`() {
        assertTrue(PadHit.midiVelocity(PadHit.SOFTEST) > 0, "the softest tap rounds to MIDI 0 - silence")
    }
}
