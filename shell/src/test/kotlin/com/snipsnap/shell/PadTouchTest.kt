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
    fun `the bottom of a pad is the hardest hit`() {
        assertEquals(1f, PadHit.velocityAt(100f, 100f))
    }

    @Test
    fun `the top is the softest, and never silent`() {
        val v = PadHit.velocityAt(0f, 100f)
        assertEquals(PadHit.SOFTEST, v)
        assertTrue(v > 0f, "a pad that makes no sound reads as broken, not as soft")
    }

    @Test
    fun `lower is always harder`() {
        var previous = -1f
        for (y in 0..100 step 5) {
            val v = PadHit.velocityAt(y.toFloat(), 100f)
            assertTrue(v > previous, "velocity fell going down the pad at y=$y")
            previous = v
        }
    }

    /**
     * The convention PLAY, GROOVE and KEYS already used, pinned here so the
     * next screen to grow a pad grid cannot quietly pick the other one.
     */
    @Test
    fun `the floor is the one the other grids have always used`() {
        assertEquals(0.35f, PadHit.SOFTEST)
    }

    /**
     * What a tap at the pad's vertical centre would have produced — the
     * velocity a synthesized TalkBack click uses, because it has no
     * position to read. Neither the softest nor the hardest hit available
     * to a sighted finger, which is the honest answer for "somewhere".
     */
    @Test
    fun `the centre velocity is the middle of the range, not full`() {
        assertEquals(PadHit.velocityAt(0.5f, 1f), PadHit.CENTER)
        assertTrue(PadHit.CENTER > PadHit.SOFTEST && PadHit.CENTER < 1f)
    }

    /**
     * A pointer can be reported slightly outside the cell it went down in —
     * a fingertip overlapping the padding, a coordinate rounded past the
     * edge. Clamped, not extrapolated: outside the top is the hardest hit,
     * not louder than one.
     */
    @Test
    fun `a touch reported outside the cell is clamped, not extrapolated`() {
        assertEquals(PadHit.SOFTEST, PadHit.velocityAt(-20f, 100f))
        assertEquals(1f, PadHit.velocityAt(150f, 100f))
    }

    /**
     * A cell with no height cannot divide. Answering full velocity is the
     * safe direction: the pad plays exactly as it did before this existed.
     * The same lesson as `TapeDeckModel.restoreView`'s non-finite zoom and
     * `Audition.barOf`'s zero `stepsPerBar` — a degenerate input must not
     * reach the audio path as NaN.
     */
    @Test
    fun `a cell with no height plays at the centre rather than dividing by zero`() {
        for (h in listOf(0f, -10f, Float.NaN)) {
            val v = PadHit.velocityAt(50f, h)
            assertTrue(v.isFinite(), "height $h produced $v")
            assertEquals(PadHit.CENTER, v, "height $h should fall back to the centre, not to a guess")
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
     * The point of the whole change, for the pad SOFT HITS actually makes.
     *
     * `addGhostLayers(slot)` defaults to **one** soft zone, and that is the
     * only way the SOFT HITS button builds layers — so this is the case a
     * player meets. One soft zone splits at MIDI 63, and the floor (44)
     * sits inside it.
     */
    @Test
    fun `the top of a ghosted pad plays the softest take and the bottom plays the live one`() {
        val pad = ghosted(softZones = 1)
        val soft = fileAt(pad, y = 0f, height = 100f)
        val hard = fileAt(pad, y = 100f, height = 100f)
        assertEquals("layer0.wav", soft, "the top of the pad missed the softest take")
        assertEquals("layer1.wav", hard, "the bottom of the pad missed the live take")
        assertNotEquals(soft, hard, "SOFT HITS is still inaudible from the grid")
    }

    /**
     * A limitation, pinned so it is a fact rather than a surprise.
     *
     * STACK THE TAKES stacks up to `StackTakes.MAX_SOFT` (3) soft zones,
     * and their windows get narrower as they multiply: two zones split at
     * MIDI 41, three at 31. The touch floor is 0.35 → **MIDI 44**, which is
     * above both — so on a stacked pad the softest take cannot be reached
     * by touch at all, however high on the pad the finger lands.
     *
     * This is not new to J37; it is a property of `MIN_VELOCITY = 0.35f`,
     * which PLAY, GROOVE and KEYS have shipped all along. Lowering the
     * floor would reach those zones and would change how three screens feel
     * to play, so it is a decision rather than a fix, and this test states
     * the current answer rather than asserting a wish.
     */
    @Test
    fun `a stacked pad's softest take sits below the touch floor - a known limit`() {
        for (softZones in 2..3) {
            val pad = ghosted(softZones)
            val softestReachable = fileAt(pad, y = 0f, height = 100f)
            assertNotEquals(
                "layer0.wav",
                softestReachable,
                "$softZones soft zones: the softest take became reachable by touch. That is an improvement, " +
                    "not a failure — but it means the floor moved, so update this test and say so.",
            )
            assertEquals(
                "layer1.wav",
                softestReachable,
                "$softZones soft zones: the softest a touch can reach should be the second zone (MIDI 44)",
            )
        }
    }

    /**
     * A pad with no layers is unaffected in *which file* it plays — there
     * is only one — but it is still hit softer, which is what makes the
     * gesture consistent rather than a special case for ghosted pads.
     */
    @Test
    fun `an unlayered pad still plays quieter from the top of the cell`() {
        val pad = KitPad(slot = 1, sampleFile = "one.wav", displayName = "KICK", drumClass = DrumClass.KICK)
        val soft = PadHit.resolve(pad, PadHit.velocityAt(0f, 100f), 0) { 1_000L }!!
        val hard = PadHit.resolve(pad, PadHit.velocityAt(100f, 100f), 0) { 1_000L }!!
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
