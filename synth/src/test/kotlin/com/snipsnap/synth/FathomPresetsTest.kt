package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1 of `docs/SYNTH_UPGRADE.md`, FATHOM's turn: twelve presets per voice
 * (thirty-six total).
 *
 * Copilot's review of this PR caught a real mistake in an earlier version
 * of this file: FATHOM is *not* like TINES/PLUCK/VELVET/TONEWHEEL/VOX
 * here. Those five are statically TONAL in `SynthScreen`'s own mapping,
 * never judged from a render — but FATHOM isn't in `SynthScreen`'s
 * `Engine` enum at all yet (a pre-existing gap this PR doesn't close; see
 * the file's own top KDoc, which already named only five engines joining
 * THUMP), and `FathomTest`'s own `factory defaults classify consistently`
 * establishes that FATHOM's raw voices carry real classifier ambiguity:
 * DEEP and GRIND read as `DrumClass.KICK`, GLASS as `DrumClass.PERC` — and
 * GRIND/GLASS were each *re-pinned* once already as their DSP changed. A
 * preset is exactly the kind of macro move that could push a voice across
 * that boundary silently, so this needs the same identity check THUMP's
 * own preset suite has, not the "no ambiguity" claim this file used to
 * make.
 *
 * **The synth-depth phase-0 start-phase seeding (Task 2) split this file's
 * single test into two, of genuinely different kinds** (fix round 2,
 * docs/.superpowers/sdd/2026-09-19-synth-depth-phase-0/task-2-report.md).
 * Before seeding, all three voices classified uniformly per-voice and one
 * test with one expected class per voice was an honest invariant check. Now:
 *
 * - **DEEP stays a real invariant.** It's a single sine per note - no paired
 *   oscillators, nothing for start-phase seeding to touch - and every DEEP
 *   preset still classifies KICK. `every DEEP preset classifies as KICK`
 *   below is a genuine uniformity check, unchanged in kind from before.
 * - **GRIND and GLASS are NOT uniform any more**, and forcing them into a
 *   "default + exceptions" shape (this file's previous structure) let a
 *   snapshot masquerade as an invariant with the same test name and read
 *   misleadingly to the next person who opened this file. `GRIND and GLASS
 *   preset classifications are pinned per preset, not a uniformity
 *   invariant` below is an explicit, fully-enumerated snapshot instead -
 *   every one of GRIND's and GLASS's 24 presets gets its own pinned
 *   [DrumClass], with the measured centroid/lowRatio that put it there.
 */
class FathomPresetsTest {

    @Test
    fun `every DEEP preset classifies as KICK`() {
        val failures = mutableListOf<String>()
        for (preset in FathomPresets.forVoice(FathomVoice.DEEP)) {
            val got = Classifier.classify(preset.render()).drumClass
            if (got != DrumClass.KICK) failures += "${preset.name}: expected KICK, got $got"
        }
        assertTrue(failures.isEmpty(), "DEEP presets that don't classify as KICK:\n${failures.joinToString("\n")}")
    }

    /**
     * GRIND's saw pair (`phaseLow`, `phase2`) no longer starts phase-locked:
     * their discontinuities land at different points in the cycle instead
     * of the same instant, roughly doubling the edge rate and lifting
     * spectral centroid across the board (`FathomTest`'s "factory defaults
     * classify consistently" has the full mechanism and the DEEP control
     * that confirms it - DEEP, with no second edge to offset, is
     * unaffected). Measured per preset, centroid Hz / lowRatio, as they
     * stood right after that seeding (fix round 2 - before Task 6):
     *
     * ```
     * GRINDER       TOM   165.2 Hz  0.847      SLIDING GRIT  TOM   130.5 Hz  0.819
     * HOLLOW GROWL  TOM   147.1 Hz  0.837      LOUD GRIND    KICK  124.8 Hz  0.888
     * RAW SAW       TOM   186.3 Hz  0.848      SOFT GROWL    TOM   112.1 Hz  0.790
     * WIDE GRIND    PERC  213.6 Hz  0.208      DETUNED GRIT  TOM   188.7 Hz  0.705
     * DIRTY GROWL   KICK  125.9 Hz  0.881      THROB         TOM   169.3 Hz  0.871
     * BEATING SUB   TOM   133.1 Hz  0.844      GRAVEL BASS   PERC  203.8 Hz  0.338
     * ```
     * 8 of 12 land on TOM (the new default); DIRTY GROWL and LOUD GRIND stay
     * under the classifier's `KICK_STRETCH_CENTROID_HZ` shelf; WIDE GRIND
     * and GRAVEL BASS - already the widest-detune presets in the set - drop
     * `lowRatio` low enough to fall through to PERC instead.
     *
     * **GLASS's mechanism is different, and was mis-stated in fix round 1**
     * (that round's comment claimed "the same mechanism" as GRIND's
     * edge-doubling - wrong, GLASS is FM, it has no saw pair to double the
     * edge rate of). Spectrally re-verified for fix round 2 by comparing
     * `Fft.magnitudeSpectrum` on the two re-pinned presets against a
     * pre-seeding render of the identical patch (temporarily reverted
     * `Fathom.kt` to the commit before this seeding, same technique as the
     * GRIND f/2 check, reverted back after measuring):
     *
     * ```
     *                    lowRatio (pre -> post)   centroid Hz (pre -> post)   class (pre -> post)
     * GLASSY LOW         0.533 -> 0.551             163.8 -> 154.7             PERC -> TOM
     * GLIDE BELL         0.535 -> 0.554             146.1 -> 145.2             PERC -> TOM
     * default (control)  0.430 -> 0.457             204.8 -> 191.5             PERC -> PERC (unchanged)
     * ```
     * The centroid barely moves for either preset (and drops slightly, not
     * up as GRIND's does) - re-pinning "by the same mechanism" would have
     * been wrong on the numbers alone. What actually moved is `lowRatio`,
     * which for both presets crosses `Classifier.BASS_DOMINANT_LOW_RATIO`
     * (0.55) from just under to just over. That threshold is what routes a
     * sound into `classifyBass` (where centroids in the 130-160 Hz range
     * that don't clear `KICK_MAX_CENTROID_HZ`/`KICK_STRETCH_CENTROID_HZ`
     * fall through to TOM) versus `classifyBright` (where the same sound
     * would land on PERC). The default patch's own `lowRatio` moves by
     * about the same amount (0.430 -> 0.457) but starts and ends further
     * from 0.55, so it doesn't cross and stays PERC - consistent with only
     * these two presets, sitting close to that boundary already, tipping
     * over.
     *
     * `phase` (GLASS's carrier) and `phaseMod` (its modulator) are now
     * seeded independently instead of both starting at 0.0, which shifts
     * their relative phase at note-on - the transient the classifier's
     * `lowRatio` feature (measured over the render, attack included) is
     * sensitive to, in phase-modulation synthesis the same way it's
     * sensitive to a beating pair's cancellation pattern for GRIND, even
     * though the underlying DSP mechanism producing the shift is unrelated.
     * `RATIO` is unison (1x) for both presets (0.35 and 0.3 both snap to
     * `RATIOS[1] = 1f`, same as the default's own 0.25), so this isn't an
     * inharmonic-sideband effect either - it's a small, real, measured
     * change in low/high energy balance right at a classifier threshold,
     * not a large centroid swing.
     *
     * **Fix round 3 (Task 6, key-tracking the filter cutoff): the KICK/TOM
     * line fired again, on two GRIND presets this time**, and this round
     * changed how this table pins rather than just re-measuring it.
     *
     * SLIDING GRIT and SOFT GROWL both flipped from their fix-round-2
     * pinned TOM to KICK once CUTOFF started tracking pitch (their own
     * TUNE sits below the engine's key-tracking reference, so their
     * cutoff - and with it `lowRatio` and `centroidHz` - moved darker,
     * same direction as every other below-centre GRIND preset). Measured
     * post-Task-6:
     *
     * ```
     * SLIDING GRIT   centroid 130.5 -> 129.592 Hz   lowRatio 0.819 -> 0.8501   TOM -> KICK
     * SOFT GROWL     centroid 112.1 -> 110.290 Hz   lowRatio 0.790 -> 0.8752   TOM -> KICK
     * ```
     *
     * Rather than just update the two entries and move on, this round asked
     * whether pinning exact classifier OUTPUT is even the right test for a
     * preset sitting ON a decision boundary - because that is now this
     * table's second flip at this exact KICK/TOM line (fix round 2 already
     * noted GLASSY LOW/GLIDE BELL crossing the *other* boundary,
     * `BASS_DOMINANT_LOW_RATIO`, by 0.001-0.004 at the time it was pinned -
     * a margin explicitly called brittle then, which is exactly what tipped
     * this round).
     *
     * **Measurement, not assumption: perturb each preset's own measured
     * [com.snipsnap.audio.Features] by a small relative amount and see
     * whether the class flips.** [isBoundaryAdjacent] nudges `centroidHz`
     * and `lowRatio` each by ±[BOUNDARY_PERTURBATION] independently (four
     * variants) and re-classifies; if any variant lands on a different
     * class than the unperturbed one, the preset is living on a boundary,
     * not decisively inside a class. This sidesteps needing to know which
     * of `Classifier`'s several private thresholds is the relevant one for
     * a given preset (there are three in play just for GRIND/GLASS's
     * KICK/TOM/PERC split: `KICK_MAX_CENTROID_HZ`, the
     * `KICK_STRETCH_CENTROID_HZ`/`KICK_STRETCH_MIN_LOW_RATIO` pair, and
     * `BASS_DOMINANT_LOW_RATIO`) - it just asks the classifier itself.
     *
     * At [BOUNDARY_PERTURBATION] = 5%, swept from 1% up to confirm this
     * isn't an artifact of one arbitrary choice:
     *
     * ```
     * perturbation   fragile count   newly fragile at this step
     * 1%             2               SLIDING GRIT, METAL SUB
     * 2%             3               + BEATING SUB
     * 3%             5               + SOFT GROWL, SUBOCTAVE FM
     * 5%             7               + DIRTY GROWL, LOUD GRIND
     * 8%             9               + GLIDE BELL, MELLOW FM
     * 10%            10              + GLASSY LOW
     * ```
     *
     * SLIDING GRIT flips at a 1% nudge - a wobble smaller than ordinary
     * FFT/windowing measurement noise, let alone a DSP change. At 5%, 7 of
     * the 24 presets (29%) are fragile - **not two unlucky presets, a
     * systemic property of this table**: GRIND's KICK-classified presets in
     * particular are ALL boundary-adjacent (DIRTY GROWL, LOUD GRIND,
     * SLIDING GRIT, SOFT GROWL - every single one), because `lowRatio`
     * comfortably clears `KICK_STRETCH_MIN_LOW_RATIO` (0.85) for nearly
     * every non-PERC GRIND preset (0.85-0.93 across the board), which makes
     * `centroidHz` vs. 130 Hz the sole effective KICK/TOM discriminant for
     * this voice - a single scalar threshold with no second gate to catch
     * a near-miss. **Conclusion: (b) - pinning exact classifier output is
     * the wrong test for a preset sitting this close to a threshold.** The
     * shift that flipped SLIDING GRIT/SOFT GROWL this round was a real,
     * measured, intentional DSP change (key tracking), not noise in the
     * classifier or the render - but landing a real change's effect
     * exactly on a hair-thin decision line is not evidence the preset
     * "should" be one specific class over the other.
     *
     * **What changed**: [grindAndGlassPinned] now pins a `Set<DrumClass>`
     * per preset instead of one class, decided with hysteresis rather than
     * a single cutoff (see [TIGHT]/[LOOSE]'s own KDoc - a single threshold
     * here would just relocate the brittle-pin problem, since DIRTY GROWL
     * and LOUD GRIND sit exactly at 5% in the sweep). 15 presets, clear
     * even at the 8% [LOOSE] perturbation, keep a single-class set - those
     * are still a hard regression gate, unchanged in strictness (a flip to
     * some third, implausible class, e.g. SNARE, would still fail
     * immediately). 9 presets, fragile at the 5% [TIGHT] perturbation (7)
     * or only in the 5-8% hysteresis band (GLIDE BELL, MELLOW FM - pinned
     * wide by choice, not by requirement), are pinned to the two classes
     * reachable from each other by that nudge (every one of the nine is a
     * KICK/TOM or PERC/TOM pair - acoustically adjacent categories for a
     * "loud low hit" sound, never a pairing with something like
     * HAT_CLOSED). The classification test's own second half cross-checks
     * every entry's set width against a fresh [isBoundaryAdjacent]
     * measurement each run - so if a pinned-narrow preset later drifts
     * fragile, or a pinned-wide one drifts decisively clear even at the
     * loose perturbation, the table is forced to be revisited and
     * re-justified rather than silently going stale in either direction.
     * This is what stops "pin a wider set" from being this test's own
     * version of the vacuous-test failure mode: the width itself is
     * measured and enforced, not a one-time escape hatch.
     *
     * All 24 GRIND/GLASS presets pinned explicitly - this table is a
     * snapshot of measured output, not a claim that either voice classifies
     * uniformly (see this class's own top KDoc for why that distinction
     * matters).
     */
    private val grindAndGlassPinned: Map<Pair<FathomVoice, String>, Set<DrumClass>> = mapOf(
        (FathomVoice.GRIND to "GRINDER") to setOf(DrumClass.TOM),
        (FathomVoice.GRIND to "HOLLOW GROWL") to setOf(DrumClass.TOM),
        (FathomVoice.GRIND to "RAW SAW") to setOf(DrumClass.TOM),
        (FathomVoice.GRIND to "WIDE GRIND") to setOf(DrumClass.PERC),
        // Boundary-adjacent (KICK/TOM, fragile at 3-5%): either side is
        // acceptable - see this class's own top KDoc for the measurement.
        (FathomVoice.GRIND to "DIRTY GROWL") to setOf(DrumClass.KICK, DrumClass.TOM),
        (FathomVoice.GRIND to "THROB") to setOf(DrumClass.TOM),
        // Boundary-adjacent (TOM/KICK, fragile at 2%).
        (FathomVoice.GRIND to "BEATING SUB") to setOf(DrumClass.TOM, DrumClass.KICK),
        (FathomVoice.GRIND to "GRAVEL BASS") to setOf(DrumClass.PERC),
        // Boundary-adjacent (KICK/TOM, fragile at 1% - this round's flip).
        (FathomVoice.GRIND to "SLIDING GRIT") to setOf(DrumClass.KICK, DrumClass.TOM),
        // Boundary-adjacent (KICK/TOM, fragile at 5%).
        (FathomVoice.GRIND to "LOUD GRIND") to setOf(DrumClass.KICK, DrumClass.TOM),
        // Boundary-adjacent (KICK/TOM, fragile at 3% - this round's other flip).
        (FathomVoice.GRIND to "SOFT GROWL") to setOf(DrumClass.KICK, DrumClass.TOM),
        (FathomVoice.GRIND to "DETUNED GRIT") to setOf(DrumClass.TOM),
        // Boundary-adjacent (PERC/TOM, fragile at 1%).
        (FathomVoice.GLASS to "METAL SUB") to setOf(DrumClass.PERC, DrumClass.TOM),
        (FathomVoice.GLASS to "GLASSY LOW") to setOf(DrumClass.TOM),
        (FathomVoice.GLASS to "BELL BASS") to setOf(DrumClass.PERC),
        (FathomVoice.GLASS to "FM GROWL") to setOf(DrumClass.PERC),
        (FathomVoice.GLASS to "RINGING SUB") to setOf(DrumClass.PERC),
        (FathomVoice.GLASS to "HARSH FM") to setOf(DrumClass.PERC),
        // Boundary-adjacent (TOM/PERC, fragile at 8% - hysteresis band, pinned wide).
        (FathomVoice.GLASS to "GLIDE BELL") to setOf(DrumClass.TOM, DrumClass.PERC),
        // Boundary-adjacent (PERC/TOM, fragile at 3%).
        (FathomVoice.GLASS to "SUBOCTAVE FM") to setOf(DrumClass.PERC, DrumClass.TOM),
        (FathomVoice.GLASS to "CLANGY BASS") to setOf(DrumClass.PERC),
        // Boundary-adjacent (PERC/TOM, fragile at 8% - hysteresis band, pinned wide).
        (FathomVoice.GLASS to "MELLOW FM") to setOf(DrumClass.PERC, DrumClass.TOM),
        (FathomVoice.GLASS to "UNSTABLE FM") to setOf(DrumClass.PERC),
        (FathomVoice.GLASS to "TWELFTH BELL") to setOf(DrumClass.PERC),
    )

    /**
     * How far (relatively) a preset's own measured centroid/lowRatio are
     * nudged to test whether it sits on a `Classifier` decision boundary -
     * see [grindAndGlassPinned]'s KDoc for the sweep (1% through 10%) that
     * grounds this choice. Two thresholds, not one, deliberately - see
     * [isBoundaryAdjacent] and the hysteresis test below for why a single
     * cutoff here would just relocate the brittle-pin problem to a new
     * threshold (DIRTY GROWL and LOUD GRIND sit exactly AT 5% in the
     * sweep - a single enforced cutoff there would flap on either of them
     * with no real regression involved).
     *
     * [TIGHT] (5%) sits past SLIDING GRIT/METAL SUB (fragile at 1%) and
     * SOFT GROWL/SUBOCTAVE FM/BEATING SUB (fragile by 3%): fragile at this
     * perturbation REQUIRES a wide (multi-class) pin. [LOOSE] (8%) sits
     * past GLIDE BELL/MELLOW FM (fragile by 8%) and short of GLASSY LOW
     * (10%): NOT fragile even at this wider perturbation REQUIRES a narrow
     * (single-class) pin. Between the two is a hysteresis band where
     * either pin width is accepted without complaint - real gaps in the
     * sweep data on both sides (nothing new turns fragile between 3-5% or
     * 5-8%), not arbitrary numbers.
     */
    private val TIGHT = 0.05f
    private val LOOSE = 0.08f

    /**
     * Whether nudging [features] by ±[pct] in `centroidHz` or `lowRatio`
     * (independently, four variants total) changes `Classifier.classify`'s
     * answer. True means the preset that produced [features] is sitting
     * close enough to a decision boundary, at this perturbation size, that
     * pinning its classification to one exact [DrumClass] would be
     * asserting on measurement/DSP noise rather than a real property of
     * the sound - see [grindAndGlassPinned]'s KDoc.
     */
    private fun isBoundaryAdjacent(features: com.snipsnap.audio.Features, pct: Float): Boolean {
        val base = Classifier.classify(features).drumClass
        val nudged = listOf(
            features.copy(centroidHz = features.centroidHz * (1 + pct)),
            features.copy(centroidHz = features.centroidHz * (1 - pct)),
            features.copy(lowRatio = (features.lowRatio * (1 + pct)).coerceAtMost(1f)),
            features.copy(lowRatio = (features.lowRatio * (1 - pct)).coerceAtLeast(0f)),
        )
        return nudged.any { Classifier.classify(it).drumClass != base }
    }

    @Test
    fun `GRIND and GLASS preset classifications are pinned per preset, and pin width tracks measured fragility`() {
        // One render per preset, feeding both checks below - FATHOM renders
        // are the slow part of this suite, and a second pass computing the
        // same Features twice bought nothing.
        val classificationFailures = mutableListOf<String>()
        val widthFailures = mutableListOf<String>()
        for (voice in listOf(FathomVoice.GRIND, FathomVoice.GLASS)) {
            for (preset in FathomPresets.forVoice(voice)) {
                val pinned = grindAndGlassPinned[voice to preset.name]
                    ?: error("${voice.name}/${preset.name} has no pinned entry - add one rather than falling back to a default")
                val features = com.snipsnap.audio.FeatureExtractor.extract(preset.render())
                val got = Classifier.classify(features).drumClass
                if (got !in pinned) {
                    classificationFailures += "${voice.name}/${preset.name}: pinned $pinned, got $got"
                }

                // Hysteresis, not a single cutoff (see TIGHT/LOOSE's own
                // KDoc): fragile at the tight perturbation FORCES a wide
                // pin (a single-class pin there is a future false alarm
                // waiting to happen); clear even at the loose perturbation
                // FORCES a narrow one (a wide pin there is needlessly
                // hiding a real regression). In between, either is fine -
                // no assertion.
                val pinnedWide = pinned.size > 1
                val fragileTight = isBoundaryAdjacent(features, TIGHT)
                val clearLoose = !isBoundaryAdjacent(features, LOOSE)
                if (fragileTight && !pinnedWide) {
                    widthFailures += "${voice.name}/${preset.name}: fragile at ${TIGHT * 100}% but pinned " +
                        "single-class ($pinned) - widen the pin before this flaps on the next unrelated change"
                }
                if (clearLoose && pinnedWide) {
                    widthFailures += "${voice.name}/${preset.name}: still decisive even at ${LOOSE * 100}% but " +
                        "pinned as boundary-adjacent (set=$pinned) - narrow the pin back to one class"
                }
            }
        }
        assertTrue(
            classificationFailures.isEmpty(),
            "presets that drifted outside their pinned class set (update the table above, with fresh " +
                "measurements and an isBoundaryAdjacent re-check, if this is a legitimate DSP change):\n" +
                classificationFailures.joinToString("\n"),
        )
        assertTrue(
            widthFailures.isEmpty(),
            "pinned set width disagrees with measured boundary fragility:\n${widthFailures.joinToString("\n")}",
        )
    }

    @Test
    fun `every preset renders clean non-silent audio`() {
        for (voice in FathomVoice.entries) {
            for (preset in FathomPresets.forVoice(voice)) {
                val snip = preset.render()
                assertTrue(snip.frameCount > 0, "${preset.name} rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() }, "${preset.name} produced non-finite samples")
                assertTrue(snip.samples.all { it in -1f..1f }, "${preset.name} clipped")
                assertTrue(snip.peak() > 0.5f, "${preset.name} is too quiet: ${snip.peak()}")
            }
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in FathomPresets.all()) {
            val restored = FathomPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertTrue(
                preset.render().samples.contentEquals(restored.render().samples),
                "${preset.name} rendered differently after a round-trip",
            )
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in FathomVoice.entries) {
            val names = FathomPresets.forVoice(voice).map { it.name }
            assertEquals(12, names.size, "$voice should ship 12 presets, has ${names.size}")
            assertEquals(names.toSet().size, names.size, "$voice has duplicate preset names: $names")
            for (name in names) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
        }
    }

    @Test
    fun `no preset name references a real drum machine`() {
        val offenders = FathomPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }
}
