package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE

/**
 * Velocity variants: softer renderings of a hit for the pad's lower
 * velocity zones.
 *
 * Physics does the design here: a softer strike excites fewer high
 * partials, so "soft" is a darker version of the same sound — not merely a
 * quieter one (the hardware already handles quieter). [atVelocity] is the
 * synth-patch version of that idea taken all the way: instead of low-passing
 * one frozen render, it moves the voice's own brightness macro and renders
 * again, so a soft hit differs in onset and harmonic content, not just tilt.
 * [soften] stays the fallback for captured audio and for voices with no
 * such macro. A phone-made kit gains ghost notes that sound like ghost
 * notes either way.
 */
object Velocity {

    /**
     * A darker rendering of [snip]; [amount] 0 = untouched, 1 = softest.
     * Peak-matched — timbre change only, the MPC's velocity curve owns level.
     */
    fun soften(snip: Snip, amount: Float): Snip {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val cutoffHz = Dsp.expMap(1f - a, 700f, 14_000f)
        var inPeak = 0f
        for (v in snip.samples) { val x = if (v < 0) -v else v; if (x > inPeak) inPeak = x }

        val out = FloatArray(snip.samples.size)
        for (ch in 0 until snip.channels) {
            val lp = Dsp.OnePole(snip.sampleRate)
            var i = ch
            while (i < snip.samples.size) {
                out[i] = lp.lp(snip.samples[i], cutoffHz)
                i += snip.channels
            }
        }

        var outPeak = 0f
        for (v in out) { val x = if (v < 0) -v else v; if (x > outPeak) outPeak = x }
        if (outPeak > 1e-9f && inPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /**
     * [count] soft variants of [snip], softest first — ready for
     * `ArrangedPad.softVariants`. Depths are spaced so each zone is an
     * audible step: with two variants, soft ≈ closed-fist, mid ≈ relaxed.
     */
    fun variants(snip: Snip, count: Int = 2): List<Snip> {
        require(count in 1..3) { "1..3 soft variants (4 zones total), got $count" }
        return List(count) { i -> soften(snip, (count - i).toFloat() / (count + 1)) }
    }

    /**
     * The floor [BRIGHTNESS_MACROS] is scaled toward at velocity 0, as a
     * fraction of the macro's own ceiling. Not a chosen number — it is
     * [Keys.ep]'s shipped FM-index scaling (`Keys.kt:48`,
     * `Dsp.lin(bright, 0.9f, 3.2f)`) expressed as a ratio: 0.9 / 3.2.
     * Reusing the ratio rather than the raw index keeps this generic across
     * macros with unrelated numeric ranges.
     */
    private const val VELOCITY_FLOOR_RATIO = 0.9f / 3.2f

    /**
     * [patch] rendered *as struck at* [velocity] — the timbre macro moves and
     * the voice is synthesized again, rather than one render being low-passed.
     * A quiet strike on a real instrument excites fewer partials; it is not a
     * loud strike with a blanket over it. Falls back to [soften] for voices
     * that expose no brightness macro.
     */
    fun atVelocity(patch: Patch, velocity: Float): Snip {
        val v = velocity.coerceIn(0f, 1f)
        val key = BRIGHTNESS_MACROS.firstOrNull { it in patch.macros }
            ?: return soften(patch.render(), 1f - v)
        val asked = patch.macros.getValue(key)
        // A macro parked at (or near) 0 has no ceiling to scale down from -
        // multiplying it by anything still gives 0, so every velocity would
        // render identically and the parameter would silently stop guarding
        // anything. Every shipped preset sets its brightness macro above
        // this, but a hand-built Patch could not; fall back rather than go
        // quiet.
        if (asked <= 1e-6f) return soften(patch.render(), 1f - v)
        // Velocity scales the macro toward its floor, never above what the
        // preset asked for: a preset's brightest is still its own ceiling.
        val scaled = asked * Dsp.lin(v, VELOCITY_FLOOR_RATIO, 1f)
        return patch.withMacros(patch.macros + (key to scaled)).render()
    }

    /**
     * Macros that mean "how hard was this struck", in preference order
     * (first match in a patch's own macro map wins).
     *
     * - BRIGHT (TINES, all voices) — directly scales the FM modulation
     *   index (`Tines.kt` bell/chime/block/zap/toy), the exact shape of
     *   [Keys.ep]'s velocity precedent.
     * - CUTOFF (VELVET all voices; FATHOM all voices) — the resonant
     *   low-pass cutoff both engines are built around (`Velvet.kt`'s own
     *   KDoc calls filter+resonance "the most gratifying knob in
     *   synthesis"; `Fathom.kt`'s signal path is source → DRIVE → CUTOFF →
     *   envelope).
     * - TONE (THUMP SNARE, CLAP) — maps straight to a low-pass cutoff in Hz
     *   (`Thump.kt:190,252`, `toneHz`).
     * - METAL (THUMP HAT_CLOSED, HAT_OPEN) — drives the cascaded
     *   high-pass cutoff that shapes the hats' sizzle (`Thump.kt:219`,
     *   `hpHz`).
     * - DIRT (TONEWHEEL, all voices) — pushes the additive sum into
     *   `Dsp.drive` saturation, the one nonlinearity in an otherwise pure
     *   additive engine (`Tonewheel.kt:127`); a harder drawbar strike
     *   reads as more overdrive, same as a harder hit on a driven amp.
     *
     * DRIVE was in an earlier draft of this list — THUMP KICK's only other
     * macro option and, on paper, "pre-filter saturation adds harmonics"
     * read as brightness-shaped. Measuring it (spectral centroid across a
     * DRIVE sweep on KICK's DUSTY BOOM preset) found a *U-shaped* response,
     * not a monotonic one: centroid falls from 45.99Hz at DRIVE=0 to a
     * minimum of 45.48Hz around DRIVE=0.35, then climbs to 46.91Hz at
     * DRIVE=1. Every shipped KICK preset's own DRIVE setting sits at or
     * past that valley, so scaling *down* from it by velocity walks back
     * up the falling side of the curve and comes out *brighter*, not
     * darker — the opposite of what a soft hit should do. FATHOM's DRIVE
     * (which was never reachable anyway — CUTOFF is on every FATHOM voice)
     * is excluded for the same reason: nothing here justifies trusting
     * DRIVE's direction without re-measuring it per engine. THUMP KICK
     * falls back to [soften].
     *
     * PLUCK (DAMP) and VOX (no candidate macro at all) are deliberately
     * absent: DAMP is Karplus-Strong loop damping, and *raising* it makes
     * the string darker — the opposite polarity of every macro above,
     * where raising the value brightens. Folding it in would need a
     * per-macro sign flip this list doesn't otherwise carry, so PLUCK and
     * VOX patches fall back to [soften] instead.
     */
    private val BRIGHTNESS_MACROS = listOf("BRIGHT", "CUTOFF", "TONE", "METAL", "DIRT")
}
