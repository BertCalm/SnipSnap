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
        val out = FloatArray(snip.samples.size)
        for (ch in 0 until snip.channels) {
            val lp = Dsp.OnePole(snip.sampleRate)
            var i = ch
            while (i < snip.samples.size) {
                out[i] = lp.lp(snip.samples[i], cutoffHz)
                i += snip.channels
            }
        }
        return peakMatch(snip, Snip(out, snip.channels, snip.sampleRate))
    }

    /**
     * [candidate] rescaled so its peak matches [reference]'s — the same
     * normalization [soften] has always done internally, exposed for a
     * caller that reaches for [atVelocity] instead: a lower BRIGHT/CUTOFF
     * macro is naturally quieter as an emergent side effect of the engine,
     * which [atVelocity] does not correct for (it only ever promises not to
     * exceed the preset's own ceiling, not to hold the peak steady). Every
     * velocity-zone call site depends on "peak-matched, timbre only" -
     * level is the hardware's velocity curve's job - so a zone built from
     * [atVelocity] owes it this rescale exactly as one built from [soften]
     * already gets it for free.
     */
    fun peakMatch(reference: Snip, candidate: Snip): Snip {
        var refPeak = 0f
        for (v in reference.samples) { val x = if (v < 0) -v else v; if (x > refPeak) refPeak = x }
        var candPeak = 0f
        for (v in candidate.samples) { val x = if (v < 0) -v else v; if (x > candPeak) candPeak = x }
        if (candPeak <= 1e-9f || refPeak <= 1e-9f) return candidate
        val g = refPeak / candPeak
        val out = FloatArray(candidate.samples.size) { (candidate.samples[it] * g).coerceIn(-1f, 1f) }
        return Snip(out, candidate.channels, candidate.sampleRate)
    }

    /**
     * [count] soft variants of [snip], softest first — ready for
     * `ArrangedPad.softVariants`. Depths are spaced so each zone is an
     * audible step: with two variants, soft ≈ closed-fist, mid ≈ relaxed.
     *
     * Always [soften]s [snip] itself — the fallback half of [variantsAt],
     * pulled out so a caller with no patch at all (captured audio) never
     * has to pass nulls through the patch-aware entry point.
     */
    fun variants(snip: Snip, count: Int = 2): List<Snip> {
        require(count in 1..3) { "1..3 soft variants (4 zones total), got $count" }
        return List(count) { i -> soften(snip, (count - i).toFloat() / (count + 1)) }
    }

    /**
     * [variants], but re-rendered via [atVelocity] when [patch] is a plain
     * synth patch riding no [fx] — the third production velocity-layer
     * generator (`StarterKits`' VELOCITY starter), wired through the exact
     * same decision [layerAt] makes for Robin's zone grid and KitBuilder's
     * ghost layers, so this doesn't grow a fourth, divergent copy of it.
     */
    fun variantsAt(snip: Snip, patch: Patch?, fx: FxChain? = null, count: Int = 2): List<Snip> {
        require(count in 1..3) { "1..3 soft variants (4 zones total), got $count" }
        val spec = patch?.let { brightnessSpec(it) }
        // Resolved once for the whole stack - see canUseAtVelocity's KDoc
        // (Task 5b: the format probe is a full patch.render(), the same
        // cost brightnessSpec's scan already avoids paying per layer).
        val useAtVelocity = canUseAtVelocity(snip, patch, fx)
        return List(count) { i ->
            val amount = (count - i).toFloat() / (count + 1)
            layerAt(snip, patch, fx, 1f - amount, spec, useAtVelocity)
        }
    }

    /**
     * One velocity layer of [reference]: re-rendered via [atVelocity] when
     * [patch] is usable, [soften]ed otherwise. The shared decision behind
     * every production velocity-layer door (Robin's zone grid,
     * KitBuilder's ghost layers, `StarterKits`' VELOCITY starter) —
     * written once here so the three sites can't drift into three
     * slightly different answers to "does this pad get to re-render".
     *
     * Three reasons [patch] is set aside for the [soften] fallback even
     * when it's non-null:
     * - [fx] is non-null — a recipe carrying both a patch and its own FX
     *   chain (a BREED cross can leave both) can't be re-rendered by
     *   [atVelocity], which only knows the bare voice, not the rack on
     *   top of it. Composing "atVelocity's render, then replay the rack"
     *   is a real capability nothing here has been asked to build.
     * - [patch]'s own render doesn't match [reference]'s channel count or
     *   sample rate — an engine's output format is a property of the
     *   engine, not the velocity, so one render settles it: something
     *   (fx, an old export path, a hand-edited file) has made the pad's
     *   actual audio disagree with what the patch would produce today,
     *   and a caller building a chain out of these takes ([Robin]) needs
     *   every take to share one format.
     * - [spec] is null and the raw macro is at/near zero — both of those
     *   are [atVelocity]'s own fallback conditions, applied here too so
     *   [layerAt] and a bare [atVelocity] call never disagree.
     *
     * [useAtVelocity] defaults to resolving [canUseAtVelocity] inline, so a
     * one-off caller gets the old, self-contained behavior for free. A
     * caller re-rendering N layers off one unchanging (reference, patch,
     * fx) triple — every production call site — should resolve it once
     * (alongside [spec]) and pass it in instead: Task 5b found the default
     * path re-running [Patch.render] as a format probe on every single
     * layer, undoing the exact hoist [spec] itself already got.
     */
    fun layerAt(
        reference: Snip,
        patch: Patch?,
        fx: FxChain?,
        velocity: Float,
        spec: MacroSpec?,
        useAtVelocity: Boolean = canUseAtVelocity(reference, patch, fx),
    ): Snip {
        return if (useAtVelocity) {
            // atVelocity isn't peak-matched itself (a duller BRIGHT/CUTOFF
            // is naturally quieter as a side effect) - peakMatch restores
            // "timbre only, level is the hardware's job" for whichever
            // gain curve the caller applies on top.
            peakMatch(reference, atVelocity(patch!!, velocity, spec))
        } else {
            soften(reference, 1f - velocity.coerceIn(0f, 1f))
        }
    }

    /**
     * Whether [patch] (riding no [fx]) can stand in for [reference] via
     * [atVelocity] — same channel count and sample rate. This is
     * [layerAt]'s format probe, and it is not free: it means rendering
     * [patch] once just to read its shape off the result and throw the
     * audio away. A caller re-rendering N velocity layers off one
     * unchanging (reference, patch, fx) triple — [variantsAt], Robin's
     * zone grid, KitBuilder's ghost layers — should call this once and
     * hand the result to every [layerAt] call, the same way [brightnessSpec]
     * already gets resolved once per pad rather than once per layer.
     */
    fun canUseAtVelocity(reference: Snip, patch: Patch?, fx: FxChain?): Boolean =
        patch != null && fx == null && patch.render().let {
            it.channels == reference.channels && it.sampleRate == reference.sampleRate
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
     * [patch]'s own macro specs, straight from the engine that owns its
     * voice — an exhaustive `when` over [Patch]'s nine sealed subtypes, the
     * same shape `Patches.fromJsonValue` already dispatches on by engine
     * string. This is deliberately **not** `patch.macros.keys`: a `Patch`
     * carrying a partial macro map (a hand-built one, or one rebuilt from a
     * stored recipe — see Task 5b) still renders every unset macro at its
     * voice default (every `synthesize()` starts from
     * `defaults(voice).toMutableMap()` and overlays what the patch sets), so
     * whether a voice *has* a brightness macro is a question about the
     * voice, never about which keys happen to be present on one instance.
     */
    private fun macroSpecsFor(patch: Patch): List<MacroSpec> = when (patch) {
        is ThumpPatch -> Thump.macrosFor(patch.voice).let { specs ->
            // SNARE's TONE stopped being a brightness macro when the snare
            // became a membrane-plus-wires voice - see [SNARE_TONE_EXCLUDED]
            // for the measurement. CLAP's TONE is untouched by that rebuild
            // (still a plain lowpass cutoff) and keeps working here, so the
            // exclusion has to be scoped to the voice, not the macro name -
            // filtering it out of SNARE's own spec list (rather than
            // removing "TONE" from [BRIGHTNESS_MACROS] outright) is what
            // keeps CLAP unaffected.
            if (patch.voice == ThumpVoice.SNARE) specs.filterNot { it.name == "TONE" } else specs
        }
        is TinesPatch -> Tines.macrosFor(patch.voice)
        is PluckPatch -> Pluck.macrosFor(patch.voice)
        is VelvetPatch -> Velvet.macrosFor(patch.voice)
        is FathomPatch -> Fathom.macrosFor(patch.voice)
        is TonewheelPatch -> Tonewheel.macrosFor(patch.voice)
        is VoxPatch -> Vox.macrosFor(patch.voice)
        is SkinPatch -> Skin.macrosFor(patch.voice)
        is SnapPatch -> Snap.macrosFor(patch.voice)
    }

    /**
     * [patch] rendered *as struck at* [velocity] — the timbre macro moves and
     * the voice is synthesized again, rather than one render being low-passed.
     * A quiet strike on a real instrument excites fewer partials; it is not a
     * loud strike with a blanket over it. Falls back to [soften] for voices
     * that expose no brightness macro.
     *
     * Resolves [brightnessSpec] on every call. A caller rendering several
     * velocities off the same unchanging [patch] — a round-robin grid, a
     * ghost-layer stack — should resolve it once and use the three-argument
     * overload below instead, so the scan happens once for the whole stack.
     */
    fun atVelocity(patch: Patch, velocity: Float): Snip =
        atVelocity(patch, velocity, brightnessSpec(patch))

    /** [atVelocity] with [spec] already resolved — see that function and [brightnessSpec]. */
    fun atVelocity(patch: Patch, velocity: Float, spec: MacroSpec?): Snip {
        val v = velocity.coerceIn(0f, 1f)
        spec ?: return soften(patch.render(), 1f - v)
        val asked = patch.macros[spec.name] ?: spec.default
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
        return patch.withMacros(patch.macros + (spec.name to scaled)).render()
    }

    /**
     * [patch]'s brightness macro spec — first of [BRIGHTNESS_MACROS] that
     * appears on the voice's own spec list, or null when it has none. This
     * is [atVelocity]'s expensive half: [macroSpecsFor] allocates a fresh
     * `List<MacroSpec>` and this scans it up to five times to find a match.
     * Resolved once here so a caller re-rendering N velocities off one
     * patch (Robin's zone grid, KitBuilder's ghost layers — Task 5b) pays
     * that cost once instead of once per layer.
     *
     * Ask the voice, not the instance: `BRIGHTNESS_MACROS.firstOrNull { it
     * in patch.macros }` would miss a partial macro map where the
     * brightness macro is unset and rendering at its voice default -
     * exactly the frozen-waveform case [atVelocity] exists to replace.
     */
    fun brightnessSpec(patch: Patch): MacroSpec? {
        val specs = macroSpecsFor(patch)
        return BRIGHTNESS_MACROS.firstNotNullOfOrNull { name -> specs.firstOrNull { it.name == name } }
    }

    /**
     * Macros that mean "how hard was this struck", in preference order
     * (first match on the voice's own macro spec wins - see
     * [macroSpecsFor]).
     *
     * - BRIGHT (TINES, all voices) — directly scales the FM modulation
     *   index (`Tines.kt` bell/chime/block/zap/toy), the exact shape of
     *   [Keys.ep]'s velocity precedent.
     * - CUTOFF (VELVET all voices; FATHOM all voices) — the resonant
     *   low-pass cutoff both engines are built around (`Velvet.kt`'s own
     *   KDoc calls filter+resonance "the most gratifying knob in
     *   synthesis"; `Fathom.kt`'s signal path is source → DRIVE → CUTOFF →
     *   envelope).
     * - TONE (THUMP CLAP only — see `SNARE_TONE_EXCLUDED` below) — maps
     *   straight to a low-pass cutoff in Hz (`Thump.kt`, `toneHz`).
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
     * read as brightness-shaped. Measuring it disqualified it, and the
     * measurement is reproducible in a couple of minutes: render
     * `Thump.kick`'s DUSTY BOOM preset (`ThumpPresets.forVoice(ThumpVoice.KICK).first()`,
     * i.e. `TUNE=0.34, SWEEP=0.45, DECAY=0.37, CLICK=0.32`) with `DRIVE` swept
     * over `0f, 0.1f, 0.2f, 0.35f, 0.5f, 0.7f, 1.0f` (`patch.withMacros(patch.macros
     * + ("DRIVE" to d)).render()` per step) and read
     * `com.snipsnap.audio.FeatureExtractor.extract(render).centroidHz` (an
     * FFT-magnitude-weighted spectral centroid, `Fft`/`Features.kt`) at each
     * step. The result is *U-shaped*, not monotonic: 45.99Hz at DRIVE=0,
     * falling to a minimum of 45.48Hz around DRIVE=0.35, then climbing to
     * 46.91Hz at DRIVE=1. Every shipped KICK preset's own DRIVE setting
     * (0.28..0.78 across all 16) sits at or past that valley, so scaling
     * *down* from it by velocity walks back up the falling side of the
     * curve and comes out *brighter*, not darker — the opposite of what a
     * soft hit should do. This is a property of THUMP's current kick
     * tuning, not a law - a future retune could well make DRIVE monotonic,
     * so nothing here pins the U-shape as an invariant; re-run the sweep
     * above before trusting DRIVE again. FATHOM's DRIVE (never reachable
     * anyway - CUTOFF is on every FATHOM voice) is excluded for the same
     * reason: nothing here justifies trusting DRIVE's direction without
     * re-measuring it per engine. THUMP KICK falls back to [soften]; that
     * consequence is what
     * `VelocityGrooveShuffleTest`'s "THUMP KICK falls back to soften too"
     * test locks down.
     *
     * PLUCK (DAMP) and VOX (no candidate macro at all) are deliberately
     * absent: DAMP is Karplus-Strong loop damping, and *raising* it makes
     * the string darker — the opposite polarity of every macro above,
     * where raising the value brightens. Folding it in would need a
     * per-macro sign flip this list doesn't otherwise carry, so PLUCK and
     * VOX patches fall back to [soften] instead.
     *
     * `SNARE_TONE_EXCLUDED`: THUMP SNARE's own TONE used to belong on this
     * list too, back when it meant "rattle lowpass cutoff" (the pre-rebuild
     * two-sines-plus-noise snare). The membrane rebuild
     * (`Thump.kt`'s `snare()`) repointed the same macro at a *highpass*
     * corner on the wire layer only (`air`, 900-5000 Hz) — raising it
     * still brightens the wires in isolation, but a one-pole highpass also
     * sheds energy as its corner rises, so the wire layer gets quieter at
     * the same time it gets brighter. [macroSpecsFor] excludes SNARE's
     * TONE from matching here (CLAP's TONE is unaffected by the rebuild
     * and stays eligible) rather than trying to make TONE level-neutral,
     * because measuring confirmed compensating for it doesn't reliably fix
     * the direction: gain-compensating the wire layer to hold its own RMS
     * constant across TONE (boosting `wireGain` by the inverse of the
     * highpass's own measured RMS loss, 1.0x at TONE=0 up to ~1.12x at
     * TONE=1) cuts the overall mix's centroid *decline* from -345 Hz to
     * -92 Hz across the full sweep, but never flips its sign — the
     * one-pole highpass is too shallow for the wires' own brightening to
     * out-pace the body's fixed low-frequency share even once the level
     * is held even. In the range velocity actually uses (THUMP SNARE's
     * shipped preset, TONE ceiling 0.35, floor-scaled to 0.161 for a soft
     * hit), the compensated centroid still reads soft=1650.29 Hz >
     * hard=1648.09 Hz — backwards, same as uncompensated (soft=1630.58 >
     * hard=1597.54, the exact numbers `VelocityGrooveShuffleTest`'s
     * "atVelocity is actually darker at low velocity" failure reported).
     * THUMP SNARE falls back to [soften] instead, same as KICK/PLUCK/VOX;
     * re-measure before ever putting SNARE's TONE back on this list.
     */
    private val BRIGHTNESS_MACROS = listOf("BRIGHT", "CUTOFF", "TONE", "METAL", "DIRT")
}
