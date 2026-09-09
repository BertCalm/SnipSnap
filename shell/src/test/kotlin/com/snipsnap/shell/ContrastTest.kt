package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WCAG 2.x AA contrast guardrails, built on [Scheme.contrastRatio] (real
 * sRGB-linearized relative luminance — not the fast Rec.601 [Scheme.luma]
 * approximation `SchemesTest` uses for its own, looser checks). Turns the
 * accessibility audit's measured ratios (2026-09-08-ux-audit/accessibility.md,
 * finding 5/6/7) into assertions so a future retune that regresses one of
 * these colours fails the build instead of shipping quietly.
 *
 * Thresholds: 4.5:1 for body text (WCAG 1.4.3), 3:1 for large text / UI
 * graphical objects (WCAG 1.4.11).
 */
class ContrastTest {

    private val AA_TEXT = 4.5
    private val AA_UI = 3.0

    // ==================== The math itself, sanity-checked ====================

    @Test
    fun `contrastRatio matches known WCAG reference pairs`() {
        // Pure black on pure white (and the reverse) is the textbook 21:1.
        assertApprox(21.0, Scheme.contrastRatio(0x000000, 0xFFFFFF), absoluteTolerance = 0.01)
        assertApprox(21.0, Scheme.contrastRatio(0xFFFFFF, 0x000000), absoluteTolerance = 0.01)
        // A colour against itself is always 1:1 — no contrast at all.
        assertApprox(1.0, Scheme.contrastRatio(0x7A6AA0, 0x7A6AA0), absoluteTolerance = 0.001)
        // Order must not matter — the formula takes lighter-over-darker
        // regardless of argument order.
        assertApprox(
            Scheme.contrastRatio(0xC86050, 0x2E3136),
            Scheme.contrastRatio(0x2E3136, 0xC86050),
            absoluteTolerance = 0.0001,
        )
    }

    private fun assertApprox(expected: Double, actual: Double, absoluteTolerance: Double) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= absoluteTolerance,
            "expected $expected, got $actual (tolerance $absoluteTolerance)",
        )
    }

    // ==================== Finding 5: destructive-action text ====================

    @Test
    fun `BIN_RED_GLOW clears AA text contrast against every scheme's chrome gray`() {
        // gray (raisedBevel's fill) is the lightest of the backgrounds this
        // text is actually drawn on across the 6 screens that use it — the
        // other sites use lcd/field/sunkenField, all darker, so gray is the
        // binding constraint. Was 0xC86050: 3.26-4.49:1, failing in all 8
        // schemes (audit finding 5).
        for (scheme in Schemes.ALL) {
            val ratio = Scheme.contrastRatio(Schemes.BIN_RED_GLOW, scheme.gray)
            assertTrue(
                ratio >= AA_TEXT,
                "${scheme.id}: BIN_RED_GLOW vs gray = %.2f:1, below AA text (%.1f:1)".format(ratio, AA_TEXT),
            )
        }
    }

    @Test
    fun `BIN_RED_GLOW also clears AA against every scheme's lcd surface`() {
        // The darker of the two backgrounds it's drawn on — passes with
        // more margin than gray by construction (lcd is darker than gray
        // in every scheme), checked directly rather than assumed.
        for (scheme in Schemes.ALL) {
            val ratio = Scheme.contrastRatio(Schemes.BIN_RED_GLOW, scheme.lcd)
            assertTrue(
                ratio >= AA_TEXT,
                "${scheme.id}: BIN_RED_GLOW vs lcd = %.2f:1, below AA text (%.1f:1)".format(ratio, AA_TEXT),
            )
        }
    }

    // ==================== Finding 5 (pad slot tag) / 6 (secondary chrome text) ====================

    @Test
    fun `ink2 clears AA text contrast against gray in every scheme, at full opacity`() {
        // The pad-slot-tag fix (KitScreen/PlayScreen) dropped the old
        // 0.6/0.7 alpha reduction in favour of full-opacity ink2 — this is
        // the guardrail that makes that safe. Also directly covers finding
        // 6 (secondary chrome text: METAL/OILSLICK/PETROL/INFRARED were
        // 3.47-4.14:1 before their ink2 was raised).
        for (scheme in Schemes.ALL) {
            val ratio = Scheme.contrastRatio(scheme.ink2, scheme.gray)
            assertTrue(
                ratio >= AA_TEXT,
                "${scheme.id}: ink2 vs gray = %.2f:1, below AA text (%.1f:1)".format(ratio, AA_TEXT),
            )
        }
    }

    @Test
    fun `ink2 clears AA against the darker filled-pad backdrop too`() {
        // KitScreen/PlayScreen's filled-pad tag sits on darken(gray, 0.30)
        // as its base layer (before any class-colour wash on top of it —
        // see the next test for that composited case, which the app now
        // sidesteps with an opaque backing chip rather than relying on
        // this alone).
        for (scheme in Schemes.ALL) {
            val bg = Schemes.darken(scheme.gray, 0.30f)
            val ratio = Scheme.contrastRatio(scheme.ink2, bg)
            assertTrue(
                ratio >= AA_TEXT,
                "${scheme.id}: ink2 vs darken(gray,0.30) = %.2f:1, below AA text (%.1f:1)".format(ratio, AA_TEXT),
            )
        }
    }

    /**
     * Documents, rather than hides, the residual gap the opaque backing
     * chip (KitScreen/PlayScreen's pad tag, `theme.BinRedGlow`-style fix)
     * exists to close: a class-coloured wash drawn *underneath* the tag at
     * partial alpha can still drag ink2's effective background bright
     * enough to fail AA, even though ink2 itself and the clean chrome
     * background both pass. Composites the brightest class colours
     * (HAT_OPEN, SNARE) at the wash strengths the two screens actually use
     * (30% for KitScreen's PadWaveform, 12% for PlayScreen's resting wash)
     * over darken(gray, 0.30), matching the numbers in KitScreen.kt/
     * PlayScreen.kt's own comments on their tag backing chips.
     */
    @Test
    fun `worst-case class-colour wash under the pad tag would fail without its backing chip`() {
        val hatOpen = 0x7ADFE4
        val snare = 0xFFC41F
        var sawAFailure = false
        for (scheme in Schemes.ALL) {
            val base = Schemes.darken(scheme.gray, 0.30f)
            for (washColor in listOf(hatOpen, snare)) {
                for (alpha in listOf(0.30, 0.12)) {
                    val composited = blend(washColor, base, alpha)
                    val ratio = Scheme.contrastRatio(scheme.ink2, composited)
                    if (ratio < AA_TEXT) sawAFailure = true
                }
            }
        }
        assertTrue(
            sawAFailure,
            "expected at least one scheme/class-colour/alpha combination to fail AA here — if none do, the " +
                "backing chip in KitScreen.kt/PlayScreen.kt may no longer be load-bearing and this test (and the " +
                "comments pointing at it) should be revisited, not just loosened",
        )
    }

    private fun blend(fg: Int, bg: Int, alpha: Double): Int {
        val r1 = (fg shr 16) and 0xFF; val g1 = (fg shr 8) and 0xFF; val b1 = fg and 0xFF
        val r2 = (bg shr 16) and 0xFF; val g2 = (bg shr 8) and 0xFF; val b2 = bg and 0xFF
        val r = Math.round(r1 * alpha + r2 * (1 - alpha)).toInt()
        val g = Math.round(g1 * alpha + g2 * (1 - alpha)).toInt()
        val b = Math.round(b1 * alpha + b2 * (1 - alpha)).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    // ==================== OILSLICK's ink3: a documented, measured gap ====================

    @Test
    fun `OILSLICK ink3 is a known, measured AA gap - not a silent one`() {
        // Left un-fixed deliberately (see Schemes.kt's comment on this
        // field): the minimum lightening that clears 4.5:1 here lands
        // within a few percent of the now-brighter ink2, erasing the
        // two-tier hierarchy ink3 exists to draw. This test pins the
        // measured ratio so a future change to either token is a
        // deliberate decision, not an accidental drift.
        val ratio = Scheme.contrastRatio(Schemes.OILSLICK.ink3, Schemes.OILSLICK.gray)
        assertTrue(
            ratio in 2.0..2.3,
            "OILSLICK ink3 vs gray = %.2f:1 — expected ~2.14:1 (the measured, accepted gap); ".format(ratio) +
                "if this moved, update this test and Schemes.kt's comment together, whichever direction it moved",
        )
        assertTrue(ratio < AA_TEXT, "if ink3 now clears AA on its own, drop this test's framing — it's no longer a gap")
    }

    // ==================== Finding 7: selection state ====================

    @Test
    fun `pressedBevel's accent border clears the 3-to-1 graphical-object threshold in every scheme`() {
        // The non-colour-alone fix for finding 7 (1.02-1.07:1 fill
        // contrast, effectively invisible) is a thicker, accent-tinted
        // border rather than a fill change — this checks the border is
        // actually visible against the pressed fill it sits on, in every
        // scheme, at the UI-component threshold (not the text one: a
        // border is a graphical object, not text).
        for (scheme in Schemes.ALL) {
            val pressedFill = Schemes.darken(scheme.gray, 0.10f)
            val ratio = Scheme.contrastRatio(scheme.accent, pressedFill)
            assertTrue(
                ratio >= AA_UI,
                "${scheme.id}: accent vs pressedBevel fill = %.2f:1, below the UI-component threshold (%.1f:1)"
                    .format(ratio, AA_UI),
            )
        }
    }
}
