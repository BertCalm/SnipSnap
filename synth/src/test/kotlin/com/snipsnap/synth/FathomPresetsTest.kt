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
     * unaffected). Measured per preset, centroid Hz / lowRatio:
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
     * All 24 GRIND/GLASS presets pinned explicitly - this table is a
     * snapshot of measured output, not a claim that either voice classifies
     * uniformly (see this class's own top KDoc for why that distinction
     * matters).
     */
    private val grindAndGlassPinned: Map<Pair<FathomVoice, String>, DrumClass> = mapOf(
        (FathomVoice.GRIND to "GRINDER") to DrumClass.TOM,
        (FathomVoice.GRIND to "HOLLOW GROWL") to DrumClass.TOM,
        (FathomVoice.GRIND to "RAW SAW") to DrumClass.TOM,
        (FathomVoice.GRIND to "WIDE GRIND") to DrumClass.PERC,
        (FathomVoice.GRIND to "DIRTY GROWL") to DrumClass.KICK,
        (FathomVoice.GRIND to "THROB") to DrumClass.TOM,
        (FathomVoice.GRIND to "BEATING SUB") to DrumClass.TOM,
        (FathomVoice.GRIND to "GRAVEL BASS") to DrumClass.PERC,
        (FathomVoice.GRIND to "SLIDING GRIT") to DrumClass.TOM,
        (FathomVoice.GRIND to "LOUD GRIND") to DrumClass.KICK,
        (FathomVoice.GRIND to "SOFT GROWL") to DrumClass.TOM,
        (FathomVoice.GRIND to "DETUNED GRIT") to DrumClass.TOM,
        (FathomVoice.GLASS to "METAL SUB") to DrumClass.PERC,
        (FathomVoice.GLASS to "GLASSY LOW") to DrumClass.TOM,
        (FathomVoice.GLASS to "BELL BASS") to DrumClass.PERC,
        (FathomVoice.GLASS to "FM GROWL") to DrumClass.PERC,
        (FathomVoice.GLASS to "RINGING SUB") to DrumClass.PERC,
        (FathomVoice.GLASS to "HARSH FM") to DrumClass.PERC,
        (FathomVoice.GLASS to "GLIDE BELL") to DrumClass.TOM,
        (FathomVoice.GLASS to "SUBOCTAVE FM") to DrumClass.PERC,
        (FathomVoice.GLASS to "CLANGY BASS") to DrumClass.PERC,
        (FathomVoice.GLASS to "MELLOW FM") to DrumClass.PERC,
        (FathomVoice.GLASS to "UNSTABLE FM") to DrumClass.PERC,
        (FathomVoice.GLASS to "TWELFTH BELL") to DrumClass.PERC,
    )

    @Test
    fun `GRIND and GLASS preset classifications are pinned per preset, not a uniformity invariant`() {
        val failures = mutableListOf<String>()
        for (voice in listOf(FathomVoice.GRIND, FathomVoice.GLASS)) {
            for (preset in FathomPresets.forVoice(voice)) {
                val want = grindAndGlassPinned[voice to preset.name]
                    ?: error("${voice.name}/${preset.name} has no pinned entry - add one rather than falling back to a default")
                val got = Classifier.classify(preset.render()).drumClass
                if (got != want) failures += "${voice.name}/${preset.name}: pinned $want, got $got"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "presets that drifted off their pinned classification (update the table above, with fresh " +
                "measurements, if this is a legitimate DSP change):\n${failures.joinToString("\n")}",
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
