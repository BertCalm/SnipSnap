# MAGNET — the Phase 0 spike, recorded

**Status:** a record, not a plan. Nothing here is in the build.
**Date:** 2026-09-29
**Design:** [`../specs/2026-09-29-magnet-valve-design.md`](../specs/2026-09-29-magnet-valve-design.md)
— "The specification, as reviewed", "The physics, measured" and "VALVE's
placement" are the numbers this file produced.

## What this is

Two throwaway probes, each a `synth/src/main` object plus a print-only test
gated on `COIL_SPIKE_OUT`, run in scratch worktrees off `50d3f80c` and never
committed. The two reports below are kept verbatim, each ending with its
complete Kotlin, so every number can be re-run: copy a part's two files into
`synth/src/main/kotlin/com/snipsnap/synth/` and
`synth/src/test/kotlin/com/snipsnap/synth/` and run
`COIL_SPIKE_OUT=<dir> ./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.CoilProbeTest"`
(part A) or `--tests "com.snipsnap.synth.CoilSpikeTest"` (part B).

- **Part A** transcribes the owner's specification ("COIL") as `CoilProbe`,
  with six compile-driven changes, and measures it.
- **Part B** builds the corrected model on the house's primitives as
  `CoilSpike` and measures the string, the pickup comb, PICK, and three
  placements of the amp stage.

The reports keep their own heading levels; each begins with its own title.
The rendered WAVs were scratch output and are not kept.

---

# MAGNET Phase 0, part A: the COIL specification as written, transcribed and measured

Base: worktree `agent-ae19883c171b7de24`, HEAD 91841146 (contains 50d3f80c; `Strings.kt` and `Tide.kt` exist). Nothing committed or pushed. Two files created, none modified.

## 1. Headline

The specification's engine does not play the note it names: on the raw waveguide every one of the 110 pitch readings (65 in grid 1, 45 in grid 2) is flat, from -1.1 to -68.9 cents, and on the final Snip 109 of 110 are flat, from -0.4 to -68.9 cents (the single exception is CHUG DRIVE 1 at +14.0 cents, where the loudest peak near the fundamental is a saturation product). The flatness grows with TUNE (higher note) and with MUTE (more damping): at the default MUTE 0.2 the raw error is -2 to -16 cents across voices and tunes, and at MUTE 1 it is -13.6 cents at 82 Hz, -26.6 at 165 Hz, -52.0 at 330 Hz and -68.9 at 440 Hz. Its DC is not zero and is made by the amp stage: the final mean is -0.0157 absolute (-0.0165 of peak) on the CHUG default, -0.0596 (-0.0627 of peak) at CHUG DRIVE 1, and +0.0446 (+0.047 of peak) on FUZZBOX MUTE 0; the raw waveguide already sits off zero by 0.0001 to 0.0035 and there is no DC blocker anywhere in the chain. The pickup-position prediction held: with the single coil, moving the tap over {0.05, 0.12, 0.28, 0.42, 0.5} changed harmonics 1..12 of `raw` by at most 0.00 dB (limit was 0.5 dB); the humbucker adds a real comb (harmonic 7 sits at 18.7 dB against 26.8 at h6 and 35.2 at h8) but is equally blind to tap position (worst spread 0.02 dB).

All numbers below come from the one Gradle run recorded at the end. Gradle exit code: 0.

## 2. Compile changes

Nothing in an existing file was edited.

- C1: `CoilPatch` omitted (sealed `Patch`, exhaustive `when` in `Velocity.macroSpecsFor`). `CoilProbe.scramble` keeps the spec's `near: Patch?` parameter (`Patch.macros` exists) and compiles.
- C2: `CoilPresets` replaced by a private `ProbePresets` returning `List<Map<String, Float>>`: CHUG 3, JANGLE 2, LEAD 1 as written; MUTED and FUZZBOX are empty lists (so `scramble` would throw on those voices; it is never called).
- C3: `render(...)` and `renderStages(...)` gained `pickupPosOverride: Float? = null, humbuckerOverride: Boolean? = null`; `pickupPos = pickupPosOverride ?: when (voice) {...}` and `isHumbucker = humbuckerOverride ?: (voice == CHUG || voice == LEAD)`. Nothing else changed.
- C4: `internal fun renderStages(...)` returns `data class Stages(raw, amped, cabbed, final: Snip)`; `render` delegates to it (`.final`). `raw` is `rawBuffer` after the waveguide loop (never mutated afterwards), `amped` is `applyAmpDrive`'s output, `cabbed` is a `copyOf()` of `applyCabinetNetwork`'s output taken before `Tide.bandLimit` (which and `normalizeByFold` mutate in place). Everything after is the spec's chain verbatim.
- C5: `applyToneStack` and `applyCabinetNetwork` got `@Suppress("UNUSED_PARAMETER")` on their unused `voice` parameter (cosmetic, no behavior change). `enum class CoilProbeVoice` is public as the spec's `CoilVoice` was; `CoilProbe` is `internal`. The build printed two warnings, neither in a Coil file.
- C6: `renderStages` also takes `skipBandLimit: Boolean = false`, used only by grid 4's variant (skips the single `Tide.bandLimit(cabProcessed, renderRate)` line; `false` is the spec's chain).
- Real declarations vs the brief: `FineTuning` lives in the test source set (`synth/src/test/.../FineTuning.kt`), not synth main (harmless, only the test uses it). `WavWriter.write(out, snip, depth = PCM_24, smpl = null)` writes 24-bit PCM by default. `Pitch.detect` returns `PitchEstimate?` (hz, confidence). `PluckSpectra.toneEnergy` returns Goertzel power summed over +-8 Hz in 2 Hz steps, so dB values are `10*log10`. Every other signature matched.
- Test choices: four `@Test` methods, each gated on `COIL_SPIKE_OUT` as its first line and each writing `gridN.md` to that directory and stdout. The only assertions are finiteness guards (recorded per render, asserted at the end of each test so the table is written first). `harmonicClarity` uses start 0.05 s, n = 65536, measured against the nominal `frequencyFor` pitch, so a note that is flat by tens of cents scores badly by construction (section 5). h2..h8 are `10*log10(P_k/P_1)` on `final` over the first 0.25 s (attack included).

## 3. Tables

Grid 1 columns: NF = non-finite counts raw/amped/cabbed/final; peak and mean (DC) per stage; DC/pk = mean over peak per stage; cents = `FineTuning.measuredHz` on `raw` at 176.4 kHz and on `final` at 44.1 kHz against `frequencyFor(voice, tune)` (the `want Hz` column is that target, following any TUNE change); Pitch.detect = hz@confidence; h2..h8 in dB re h1; clarity = `harmonicClarity(final)` in dB; loud = `Loudness.of(final)` and its ratio to 0.1834; class = `Classifier.classify(final).drumClass`; dur in seconds; ms/s = wall milliseconds per rendered second (the first row includes JIT warm-up).

### Grid 1: macro corners (65 renders; WAVs in `spike-out/a/<VOICE>_<MACRO>_<VALUE>.wav`, `DEFAULTS_0` for defaults)

Zero non-finite samples in every stage of every render.

| voice | macro | value | NF r/a/c/f | peak raw | peak amp | peak cab | peak fin | DC raw | DC amp | DC cab | DC fin | DC/pk raw | DC/pk amp | DC/pk cab | DC/pk fin | cents raw | cents fin | Pitch.detect | want Hz | h2 | h3 | h4 | h5 | h6 | h7 | h8 | clarity dB | loud | loud/0.1834 | class | dur s | ms/s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CHUG | DEFAULTS | - | 0/0/0/0 | 0.503 | 0.998 | 0.912 | 0.950 | -0.00136 | -0.01025 | -0.01461 | -0.01567 | -0.0027 | -0.0103 | -0.0160 | -0.0165 | -2.2 | -1.9 | 82.3@0.95 | 82.40 | -6.1 | -7.9 | -10.4 | -14.4 | -15.9 | -15.6 | -15.7 | 42.0 | 0.1779 | 0.970 | LOOP | 1.91 | 41.8 |
| CHUG | TUNE | 0 | 0/0/0/0 | 0.504 | 0.995 | 0.845 | 0.950 | -0.00093 | -0.00735 | -0.01048 | -0.01271 | -0.0019 | -0.0074 | -0.0124 | -0.0134 | -4.1 | -1.4 | 41.2@0.90 | 41.20 | 3.7 | -0.2 | -2.6 | -3.8 | -4.7 | -5.8 | -7.3 | 60.1 | 0.1262 | 0.688 | LOOP | 1.91 | 21.4 |
| CHUG | TUNE | 1 | 0/0/0/0 | 0.651 | 0.998 | 0.858 | 0.945 | -0.00069 | -0.00632 | -0.00900 | -0.01024 | -0.0011 | -0.0063 | -0.0105 | -0.0108 | -5.9 | -6.0 | 164.6@0.95 | 164.80 | -4.3 | -13.9 | -18.7 | -19.7 | -20.8 | -27.2 | -13.5 | 18.7 | 0.1542 | 0.841 | LOOP | 1.91 | 19.5 |
| CHUG | DRIVE | 0 | 0/0/0/0 | 0.503 | 0.742 | 0.445 | 0.950 | -0.00136 | -0.00266 | -0.00379 | -0.00822 | -0.0027 | -0.0036 | -0.0085 | -0.0086 | -2.2 | -2.8 | 82.3@0.94 | 82.40 | -6.3 | -8.3 | -11.0 | -15.2 | -16.8 | -16.6 | -16.7 | 42.2 | 0.1269 | 0.692 | LOOP | 1.91 | 18.9 |
| CHUG | DRIVE | 1 | 0/0/0/0 | 0.503 | 1.000 | 1.223 | 0.950 | -0.00136 | -0.05419 | -0.07724 | -0.05956 | -0.0027 | -0.0542 | -0.0631 | -0.0627 | -2.2 | 14.0 | 82.3@0.95 | 82.40 | -4.0 | -4.6 | -6.4 | -9.8 | -10.9 | -10.2 | -9.7 | 39.7 | 0.2444 | 1.332 | LOOP | 1.91 | 16.8 |
| CHUG | TONE | 0 | 0/0/0/0 | 0.503 | 0.998 | 0.820 | 0.950 | -0.00136 | -0.01025 | -0.01461 | -0.01720 | -0.0027 | -0.0103 | -0.0178 | -0.0181 | -2.2 | -1.9 | 82.3@0.95 | 82.40 | -5.6 | -6.3 | -6.9 | -9.1 | -9.8 | -10.4 | -11.9 | 46.3 | 0.1845 | 1.006 | LOOP | 1.91 | 16.3 |
| CHUG | TONE | 1 | 0/0/0/0 | 0.503 | 0.998 | 0.999 | 0.950 | -0.00136 | -0.01025 | -0.01462 | -0.01288 | -0.0027 | -0.0103 | -0.0146 | -0.0136 | -2.2 | -1.9 | 82.3@0.94 | 82.40 | -6.2 | -8.3 | -11.1 | -15.4 | -18.2 | -21.5 | -24.3 | 40.7 | 0.1444 | 0.787 | LOOP | 1.91 | 16.8 |
| CHUG | CAB | 0 | 0/0/0/0 | 0.503 | 0.998 | 0.946 | 0.950 | -0.00136 | -0.01025 | -0.01479 | -0.01587 | -0.0027 | -0.0103 | -0.0156 | -0.0167 | -2.2 | -2.0 | 82.3@0.95 | 82.40 | -3.1 | -6.1 | -9.2 | -14.7 | -20.4 | -18.8 | -16.9 | 41.0 | 0.1746 | 0.952 | LOOP | 1.91 | 17.5 |
| CHUG | CAB | 1 | 0/0/0/0 | 0.503 | 0.998 | 0.913 | 0.950 | -0.00136 | -0.01025 | -0.01468 | -0.01548 | -0.0027 | -0.0103 | -0.0161 | -0.0163 | -2.2 | -2.2 | 82.3@0.95 | 82.40 | -6.8 | -7.9 | -9.2 | -11.4 | -13.8 | -14.8 | -15.4 | 42.6 | 0.1783 | 0.972 | LOOP | 1.91 | 15.9 |
| CHUG | MUTE | 0 | 0/0/0/0 | 0.548 | 0.999 | 0.919 | 0.950 | -0.00295 | -0.02044 | -0.02913 | -0.03060 | -0.0054 | -0.0205 | -0.0317 | -0.0322 | -1.3 | -0.9 | 82.3@0.95 | 82.40 | -5.8 | -7.3 | -9.7 | -13.6 | -14.9 | -14.6 | -14.6 | 38.4 | 0.2074 | 1.131 | LOOP | 3.50 | 16.2 |
| CHUG | MUTE | 1 | 0/0/0/0 | 0.344 | 0.975 | 0.917 | 0.950 | -0.00278 | -0.02247 | -0.03194 | -0.03608 | -0.0081 | -0.0231 | -0.0349 | -0.0380 | -13.6 | -13.0 | 81.8@0.87 | 82.40 | -6.4 | -8.5 | -11.4 | -15.9 | -18.0 | -18.2 | -18.8 | 7.4 | 0.0921 | 0.502 | PERC | 0.20 | 16.4 |
| CHUG | SAG | 0 | 0/0/0/0 | 0.503 | 0.998 | 0.927 | 0.950 | -0.00136 | -0.00856 | -0.01220 | -0.01323 | -0.0027 | -0.0086 | -0.0132 | -0.0139 | -2.2 | -2.0 | 82.3@0.95 | 82.40 | -6.1 | -7.9 | -10.5 | -14.5 | -16.0 | -15.8 | -15.8 | 42.0 | 0.1806 | 0.985 | LOOP | 1.91 | 16.7 |
| CHUG | SAG | 1 | 0/0/0/0 | 0.503 | 0.998 | 0.903 | 0.950 | -0.00136 | -0.01338 | -0.01907 | -0.02004 | -0.0027 | -0.0134 | -0.0211 | -0.0211 | -2.2 | -1.7 | 82.3@0.94 | 82.40 | -6.0 | -7.8 | -10.2 | -14.2 | -15.7 | -15.4 | -15.5 | 42.0 | 0.1737 | 0.947 | LOOP | 1.91 | 16.0 |
| JANGLE | DEFAULTS | - | 0/0/0/0 | 0.577 | 0.815 | 0.519 | 0.950 | 0.00056 | 0.00110 | 0.00156 | 0.00316 | 0.0010 | 0.0013 | 0.0030 | 0.0033 | -5.9 | -5.8 | 164.6@0.94 | 164.82 | -2.9 | -7.8 | -7.4 | -9.8 | -10.7 | -8.3 | -5.6 | 17.3 | 0.0912 | 0.497 | LOOP | 1.91 | 18.8 |
| JANGLE | TUNE | 0 | 0/0/0/0 | 0.713 | 0.888 | 0.760 | 0.950 | -0.00013 | -0.00027 | -0.00038 | -0.00050 | -0.0002 | -0.0003 | -0.0005 | -0.0005 | -2.2 | -3.1 | 82.3@0.93 | 82.41 | -3.5 | -4.8 | -8.9 | -18.3 | -18.5 | -9.1 | -3.5 | 38.2 | 0.0934 | 0.509 | LOOP | 1.91 | 17.2 |
| JANGLE | TUNE | 1 | 0/0/0/0 | 0.590 | 0.760 | 0.526 | 0.988 | -0.00061 | -0.00118 | -0.00169 | -0.00349 | -0.0010 | -0.0016 | -0.0032 | -0.0035 | -11.3 | -11.6 | 326.7@0.96 | 329.64 | 1.0 | -3.3 | -3.7 | -8.2 | -5.7 | -5.6 | -9.7 | 8.9 | 0.1025 | 0.559 | LOOP | 1.91 | 16.1 |
| JANGLE | DRIVE | 0 | 0/0/0/0 | 0.577 | 0.431 | 0.242 | 0.950 | 0.00056 | 0.00045 | 0.00064 | 0.00277 | 0.0010 | 0.0010 | 0.0026 | 0.0029 | -5.9 | -5.8 | 164.6@0.94 | 164.82 | -3.0 | -7.7 | -7.3 | -9.7 | -10.6 | -8.3 | -5.7 | 17.3 | 0.0817 | 0.446 | LOOP | 1.91 | 16.2 |
| JANGLE | DRIVE | 1 | 0/0/0/0 | 0.577 | 0.998 | 0.828 | 0.950 | 0.00056 | 0.00197 | 0.00280 | 0.00350 | 0.0010 | 0.0020 | 0.0034 | 0.0037 | -5.9 | -5.8 | 164.6@0.94 | 164.82 | -2.9 | -7.8 | -7.7 | -10.4 | -10.9 | -8.2 | -5.5 | 17.3 | 0.1448 | 0.789 | LOOP | 1.91 | 16.6 |
| JANGLE | TONE | 0 | 0/0/0/0 | 0.577 | 0.815 | 0.433 | 0.950 | 0.00056 | 0.00110 | 0.00156 | 0.00381 | 0.0010 | 0.0013 | 0.0036 | 0.0040 | -5.9 | -5.8 | 164.6@0.95 | 164.82 | 0.1 | -2.1 | -4.1 | -8.4 | -10.1 | -8.2 | -5.9 | 21.5 | 0.0959 | 0.523 | LOOP | 1.91 | 15.9 |
| JANGLE | TONE | 1 | 0/0/0/0 | 0.577 | 0.815 | 0.560 | 0.950 | 0.00056 | 0.00110 | 0.00156 | 0.00257 | 0.0010 | 0.0013 | 0.0028 | 0.0027 | -5.9 | -5.8 | 164.6@0.93 | 164.82 | -3.5 | -9.8 | -15.8 | -15.9 | -14.4 | -10.7 | -7.3 | 16.5 | 0.0774 | 0.422 | LOOP | 1.91 | 15.9 |
| JANGLE | CAB | 0 | 0/0/0/0 | 0.577 | 0.815 | 0.496 | 0.950 | 0.00056 | 0.00110 | 0.00158 | 0.00336 | 0.0010 | 0.0013 | 0.0032 | 0.0035 | -5.9 | -5.8 | 164.6@0.94 | 164.82 | -4.7 | -15.2 | -11.5 | -12.3 | -12.5 | -9.8 | -7.0 | 17.4 | 0.0960 | 0.523 | LOOP | 1.91 | 15.9 |
| JANGLE | CAB | 1 | 0/0/0/0 | 0.577 | 0.815 | 0.534 | 0.950 | 0.00056 | 0.00110 | 0.00157 | 0.00308 | 0.0010 | 0.0013 | 0.0029 | 0.0032 | -5.9 | -5.8 | 164.6@0.94 | 164.82 | -1.0 | -4.9 | -6.3 | -9.2 | -10.3 | -7.9 | -5.3 | 17.9 | 0.0893 | 0.487 | LOOP | 1.91 | 16.5 |
| JANGLE | MUTE | 0 | 0/0/0/0 | 0.612 | 0.838 | 0.522 | 0.950 | 0.00143 | 0.00280 | 0.00400 | 0.00806 | 0.0023 | 0.0033 | 0.0077 | 0.0085 | -4.6 | -4.3 | 164.6@0.96 | 164.82 | -2.9 | -7.7 | -7.3 | -9.7 | -10.4 | -7.9 | -5.1 | 21.2 | 0.1348 | 0.735 | LOOP | 3.50 | 16.0 |
| JANGLE | MUTE | 1 | 0/0/0/0 | 0.413 | 0.674 | 0.476 | 0.950 | 0.00127 | 0.00248 | 0.00353 | 0.00765 | 0.0031 | 0.0037 | 0.0074 | 0.0081 | -26.6 | -26.4 | 162.7@0.87 | 164.82 | -3.6 | -10.0 | -11.6 | -16.1 | -18.6 | -17.1 | -15.2 | 8.3 | 0.0409 | 0.223 | PERC | 0.20 | 16.2 |
| JANGLE | SAG | 0 | 0/0/0/0 | 0.577 | 0.815 | 0.520 | 0.950 | 0.00056 | 0.00110 | 0.00157 | 0.00316 | 0.0010 | 0.0013 | 0.0030 | 0.0033 | -5.9 | -5.8 | 164.6@0.94 | 164.82 | -2.9 | -7.8 | -7.4 | -9.8 | -10.7 | -8.3 | -5.6 | 17.3 | 0.0912 | 0.497 | LOOP | 1.91 | 16.8 |
| JANGLE | SAG | 1 | 0/0/0/0 | 0.577 | 0.815 | 0.519 | 0.950 | 0.00056 | 0.00109 | 0.00155 | 0.00314 | 0.0010 | 0.0013 | 0.0030 | 0.0033 | -5.9 | -5.8 | 164.6@0.94 | 164.82 | -2.9 | -7.8 | -7.4 | -9.8 | -10.7 | -8.3 | -5.6 | 17.3 | 0.0912 | 0.497 | LOOP | 1.91 | 15.8 |
| LEAD | DEFAULTS | - | 0/0/0/0 | 0.640 | 1.000 | 1.055 | 0.950 | -0.00084 | -0.01139 | -0.01624 | -0.01466 | -0.0013 | -0.0114 | -0.0154 | -0.0154 | -8.2 | -6.6 | 219.4@0.96 | 220.00 | -18.9 | -6.7 | -11.3 | -13.9 | -11.4 | -3.0 | 0.2 | 4.8 | 0.1920 | 1.047 | LOOP | 1.91 | 15.9 |
| LEAD | TUNE | 0 | 0/0/0/0 | 0.632 | 1.000 | 0.937 | 0.950 | -0.00152 | -0.01714 | -0.02443 | -0.02467 | -0.0024 | -0.0171 | -0.0261 | -0.0260 | -3.0 | -4.0 | 109.7@0.96 | 110.00 | -6.3 | -9.9 | -15.4 | -16.9 | -19.6 | -25.8 | -24.3 | 37.2 | 0.1981 | 1.080 | LOOP | 1.91 | 15.8 |
| LEAD | TUNE | 1 | 0/0/0/0 | 0.558 | 1.000 | 1.295 | 0.949 | 0.00187 | 0.01303 | 0.01858 | 0.01361 | 0.0034 | 0.0130 | 0.0143 | 0.0143 | -15.5 | -15.4 | 436.6@0.97 | 440.00 | -8.6 | -1.1 | 0.1 | 4.1 | -3.2 | -5.3 | -24.1 | -5.1 | 0.1521 | 0.830 | LOOP | 1.91 | 16.8 |
| LEAD | DRIVE | 0 | 0/0/0/0 | 0.640 | 0.913 | 0.773 | 0.950 | -0.00084 | -0.00259 | -0.00370 | -0.00516 | -0.0013 | -0.0028 | -0.0048 | -0.0054 | -8.2 | -7.6 | 219.4@0.95 | 220.00 | -26.5 | -7.3 | -9.8 | -28.1 | -10.4 | -3.3 | 0.2 | 4.8 | 0.1240 | 0.676 | LOOP | 1.91 | 15.9 |
| LEAD | DRIVE | 1 | 0/0/0/0 | 0.640 | 1.000 | 1.401 | 0.950 | -0.00084 | -0.03686 | -0.05254 | -0.03558 | -0.0013 | -0.0369 | -0.0375 | -0.0375 | -8.2 | -1.7 | 219.4@0.97 | 220.00 | -13.8 | -6.7 | -15.8 | -8.0 | -10.4 | -3.7 | -0.7 | 5.1 | 0.2833 | 1.545 | LOOP | 1.91 | 15.8 |
| LEAD | TONE | 0 | 0/0/0/0 | 0.640 | 1.000 | 0.904 | 0.950 | -0.00084 | -0.01139 | -0.01624 | -0.01738 | -0.0013 | -0.0114 | -0.0180 | -0.0183 | -8.2 | -6.6 | 219.4@0.96 | 220.00 | -14.3 | -3.9 | -10.8 | -14.3 | -12.3 | -4.3 | -1.5 | 7.3 | 0.1911 | 1.042 | LOOP | 1.91 | 16.4 |
| LEAD | TONE | 1 | 0/0/0/0 | 0.640 | 1.000 | 1.130 | 0.950 | -0.00084 | -0.01139 | -0.01624 | -0.01316 | -0.0013 | -0.0114 | -0.0144 | -0.0139 | -8.2 | -6.6 | 219.4@0.96 | 220.00 | -19.8 | -14.8 | -16.2 | -16.6 | -13.0 | -4.0 | -0.4 | 4.4 | 0.1739 | 0.948 | LOOP | 1.91 | 15.9 |
| LEAD | CAB | 0 | 0/0/0/0 | 0.640 | 1.000 | 1.071 | 0.950 | -0.00084 | -0.01139 | -0.01644 | -0.01468 | -0.0013 | -0.0114 | -0.0153 | -0.0155 | -8.2 | -6.6 | 219.4@0.96 | 220.00 | -22.6 | -9.8 | -12.5 | -14.6 | -11.9 | -3.3 | -0.0 | 4.7 | 0.1909 | 1.041 | LOOP | 1.91 | 15.8 |
| LEAD | CAB | 1 | 0/0/0/0 | 0.640 | 1.000 | 1.049 | 0.950 | -0.00084 | -0.01139 | -0.01632 | -0.01493 | -0.0013 | -0.0114 | -0.0156 | -0.0157 | -8.2 | -6.6 | 219.4@0.96 | 220.00 | -15.7 | -6.1 | -11.3 | -14.1 | -11.6 | -3.2 | -0.1 | 5.1 | 0.1938 | 1.057 | LOOP | 1.91 | 15.8 |
| LEAD | MUTE | 0 | 0/0/0/0 | 0.677 | 1.000 | 1.088 | 0.950 | -0.00220 | -0.02286 | -0.03259 | -0.02855 | -0.0032 | -0.0229 | -0.0299 | -0.0301 | -5.9 | -3.0 | 219.4@0.98 | 220.00 | -15.0 | -5.5 | -12.7 | -9.9 | -10.8 | -1.6 | 2.3 | 8.9 | 0.2757 | 1.503 | LOOP | 3.50 | 16.3 |
| LEAD | MUTE | 1 | 0/0/0/0 | 0.540 | 0.998 | 1.036 | 0.950 | -0.00193 | -0.03105 | -0.04425 | -0.04172 | -0.0036 | -0.0311 | -0.0427 | -0.0439 | -35.2 | -35.1 | 219.4@0.92 | 220.00 | -26.7 | -14.6 | -20.2 | -23.8 | -19.9 | -14.0 | -12.8 | 3.7 | 0.0705 | 0.385 | PERC | 0.20 | 15.9 |
| LEAD | SAG | 0 | 0/0/0/0 | 0.640 | 1.000 | 1.084 | 0.950 | -0.00084 | -0.00674 | -0.00961 | -0.00860 | -0.0013 | -0.0067 | -0.0089 | -0.0090 | -8.2 | -6.7 | 219.4@0.96 | 220.00 | -20.8 | -7.1 | -11.5 | -14.6 | -11.7 | -3.3 | -0.1 | 4.8 | 0.1916 | 1.045 | LOOP | 1.91 | 16.1 |
| LEAD | SAG | 1 | 0/0/0/0 | 0.640 | 1.000 | 1.134 | 0.950 | -0.00084 | -0.01992 | -0.02839 | -0.02380 | -0.0013 | -0.0199 | -0.0250 | -0.0251 | -8.2 | -6.2 | 243.6@0.84 | 220.00 | -16.2 | -6.1 | -11.1 | -13.2 | -11.2 | -2.8 | 0.5 | 4.8 | 0.1780 | 0.970 | LOOP | 1.91 | 16.7 |
| MUTED | DEFAULTS | - | 0/0/0/0 | 0.553 | 0.987 | 0.715 | 0.946 | 0.00025 | 0.00024 | 0.00034 | 0.00045 | 0.0004 | 0.0002 | 0.0005 | 0.0005 | -5.9 | -6.0 | 164.6@0.95 | 164.82 | 1.2 | -0.7 | 2.2 | 1.7 | -2.0 | -6.9 | -6.6 | 24.0 | 0.1420 | 0.774 | LOOP | 1.91 | 15.8 |
| MUTED | TUNE | 0 | 0/0/0/0 | 0.689 | 0.974 | 0.753 | 0.950 | -0.00015 | -0.00119 | -0.00170 | -0.00232 | -0.0002 | -0.0012 | -0.0023 | -0.0024 | -2.1 | -8.9 | 82.3@0.93 | 82.41 | -1.4 | 0.2 | 0.5 | -1.0 | -0.0 | 2.4 | 4.3 | 39.0 | 0.1377 | 0.751 | LOOP | 1.91 | 15.9 |
| MUTED | TUNE | 1 | 0/0/0/0 | 0.683 | 0.996 | 0.806 | 0.979 | 0.00072 | 0.00212 | 0.00302 | 0.00367 | 0.0010 | 0.0021 | 0.0037 | 0.0038 | -11.3 | -11.6 | 326.7@0.96 | 329.64 | 0.1 | 3.7 | 2.6 | -11.4 | -7.2 | -8.7 | -21.4 | 6.8 | 0.1488 | 0.811 | LOOP | 1.91 | 16.0 |
| MUTED | DRIVE | 0 | 0/0/0/0 | 0.553 | 0.680 | 0.392 | 0.944 | 0.00025 | 0.00036 | 0.00052 | 0.00130 | 0.0004 | 0.0005 | 0.0013 | 0.0014 | -5.9 | -5.8 | 164.6@0.95 | 164.82 | 1.1 | -0.8 | 2.2 | 1.8 | -1.8 | -6.4 | -6.5 | 24.0 | 0.1024 | 0.559 | LOOP | 1.91 | 16.5 |
| MUTED | DRIVE | 1 | 0/0/0/0 | 0.553 | 1.000 | 1.153 | 0.948 | 0.00025 | -0.00733 | -0.01045 | -0.00867 | 0.0004 | -0.0073 | -0.0091 | -0.0092 | -5.9 | -6.1 | 164.6@0.96 | 164.82 | 1.2 | -1.0 | 1.6 | 0.6 | -3.8 | -10.2 | -9.0 | 24.3 | 0.2104 | 1.147 | LOOP | 1.91 | 15.8 |
| MUTED | TONE | 0 | 0/0/0/0 | 0.553 | 0.987 | 0.716 | 0.948 | 0.00025 | 0.00024 | 0.00034 | 0.00047 | 0.0004 | 0.0002 | 0.0005 | 0.0005 | -5.9 | -6.0 | 164.6@0.96 | 164.82 | 4.2 | 4.9 | 5.6 | 3.2 | -1.4 | -6.8 | -6.9 | 29.3 | 0.1685 | 0.919 | LOOP | 1.91 | 15.7 |
| MUTED | TONE | 1 | 0/0/0/0 | 0.553 | 0.987 | 0.914 | 0.945 | 0.00025 | 0.00024 | 0.00034 | 0.00038 | 0.0004 | 0.0002 | 0.0004 | 0.0004 | -5.9 | -6.0 | 164.6@0.94 | 164.82 | 0.7 | -2.8 | -6.1 | -4.3 | -5.8 | -9.4 | -8.3 | 21.6 | 0.1132 | 0.617 | LOOP | 1.91 | 16.2 |
| MUTED | CAB | 0 | 0/0/0/0 | 0.553 | 0.987 | 0.730 | 0.945 | 0.00025 | 0.00024 | 0.00035 | 0.00048 | 0.0004 | 0.0002 | 0.0005 | 0.0005 | -5.9 | -6.0 | 164.6@0.95 | 164.82 | -0.5 | -8.2 | -1.9 | -0.7 | -3.9 | -8.5 | -8.0 | 22.8 | 0.1422 | 0.775 | LOOP | 1.91 | 15.7 |
| MUTED | CAB | 1 | 0/0/0/0 | 0.553 | 0.987 | 0.730 | 0.946 | 0.00025 | 0.00024 | 0.00035 | 0.00045 | 0.0004 | 0.0002 | 0.0005 | 0.0005 | -5.9 | -6.0 | 164.6@0.95 | 164.82 | 3.1 | 2.2 | 3.3 | 2.3 | -1.6 | -6.6 | -6.3 | 25.0 | 0.1453 | 0.792 | LOOP | 1.91 | 15.6 |
| MUTED | MUTE | 0 | 0/0/0/0 | 0.595 | 0.991 | 0.724 | 0.948 | 0.00063 | 0.00186 | 0.00265 | 0.00341 | 0.0011 | 0.0019 | 0.0037 | 0.0036 | -4.6 | -4.6 | 164.6@0.97 | 164.82 | 1.3 | -0.6 | 2.4 | 1.9 | -1.9 | -6.8 | -6.2 | 21.9 | 0.1970 | 1.074 | LOOP | 3.50 | 16.0 |
| MUTED | MUTE | 1 | 0/0/0/0 | 0.395 | 0.948 | 0.721 | 0.923 | 0.00056 | 0.00051 | 0.00073 | 0.00092 | 0.0014 | 0.0005 | 0.0010 | 0.0010 | -26.6 | -26.4 | 162.7@0.85 | 164.82 | 0.5 | -2.9 | -2.0 | -4.6 | -10.1 | -16.8 | -16.2 | 6.2 | 0.0634 | 0.346 | PERC | 0.20 | 16.0 |
| MUTED | SAG | 0 | 0/0/0/0 | 0.553 | 0.988 | 0.724 | 0.946 | 0.00025 | 0.00095 | 0.00135 | 0.00176 | 0.0004 | 0.0010 | 0.0019 | 0.0019 | -5.9 | -6.0 | 164.6@0.95 | 164.82 | 1.3 | -0.7 | 2.3 | 1.8 | -2.0 | -6.9 | -6.6 | 24.0 | 0.1402 | 0.764 | LOOP | 1.91 | 16.3 |
| MUTED | SAG | 1 | 0/0/0/0 | 0.553 | 0.987 | 0.711 | 0.946 | 0.00025 | -0.00107 | -0.00153 | -0.00207 | 0.0004 | -0.0011 | -0.0022 | -0.0022 | -5.9 | -5.9 | 164.6@0.95 | 164.82 | 1.1 | -0.8 | 2.1 | 1.6 | -2.2 | -7.0 | -6.7 | 24.0 | 0.1457 | 0.794 | LOOP | 1.91 | 15.9 |
| FUZZBOX | DEFAULTS | - | 0/0/0/0 | 0.539 | 1.000 | 1.189 | 0.950 | 0.00137 | 0.01049 | 0.01495 | 0.01211 | 0.0025 | 0.0105 | 0.0126 | 0.0127 | -5.9 | -5.9 | 164.6@0.96 | 164.82 | -8.4 | -21.0 | -25.8 | -24.7 | -19.3 | -12.4 | -10.3 | 23.3 | 0.1981 | 1.080 | LOOP | 1.91 | 17.1 |
| FUZZBOX | TUNE | 0 | 0/0/0/0 | 0.553 | 1.000 | 1.107 | 0.950 | 0.00049 | -0.00184 | -0.00262 | -0.00227 | 0.0009 | -0.0018 | -0.0024 | -0.0024 | -2.1 | -2.8 | 82.3@0.95 | 82.41 | -8.0 | -12.9 | -20.6 | -32.6 | -26.0 | -19.4 | -15.0 | 37.0 | 0.1691 | 0.922 | LOOP | 1.91 | 15.9 |
| FUZZBOX | TUNE | 1 | 0/0/0/0 | 0.729 | 1.000 | 1.367 | 0.949 | 0.00062 | -0.00164 | -0.00233 | -0.00163 | 0.0008 | -0.0016 | -0.0017 | -0.0017 | -11.3 | -11.1 | 326.7@0.96 | 329.64 | 0.1 | 0.2 | -1.4 | -4.9 | -1.3 | -2.3 | -9.7 | 2.9 | 0.2397 | 1.307 | LOOP | 1.91 | 15.9 |
| FUZZBOX | DRIVE | 0 | 0/0/0/0 | 0.539 | 0.991 | 0.843 | 0.950 | 0.00137 | 0.00589 | 0.00839 | 0.00960 | 0.0025 | 0.0059 | 0.0100 | 0.0101 | -5.9 | -5.8 | 164.6@0.95 | 164.82 | -8.0 | -19.4 | -28.5 | -29.2 | -17.9 | -11.9 | -9.6 | 23.2 | 0.1319 | 0.719 | LOOP | 1.91 | 16.7 |
| FUZZBOX | DRIVE | 1 | 0/0/0/0 | 0.539 | 1.000 | 1.598 | 0.950 | 0.00137 | 0.01939 | 0.02764 | 0.01643 | 0.0025 | 0.0194 | 0.0173 | 0.0173 | -5.9 | -5.8 | 164.6@0.97 | 164.82 | -9.2 | -23.7 | -21.4 | -21.9 | -21.2 | -13.4 | -12.4 | 24.0 | 0.2650 | 1.445 | LOOP | 1.91 | 16.3 |
| FUZZBOX | TONE | 0 | 0/0/0/0 | 0.539 | 1.000 | 1.097 | 0.950 | 0.00137 | 0.01049 | 0.01495 | 0.01307 | 0.0025 | 0.0105 | 0.0136 | 0.0138 | -5.9 | -5.9 | 164.6@0.97 | 164.82 | -5.4 | -15.4 | -22.4 | -23.2 | -18.7 | -12.3 | -10.6 | 26.2 | 0.1961 | 1.069 | LOOP | 1.91 | 16.1 |
| FUZZBOX | TONE | 1 | 0/0/0/0 | 0.539 | 1.000 | 1.311 | 0.950 | 0.00137 | 0.01049 | 0.01496 | 0.01102 | 0.0025 | 0.0105 | 0.0114 | 0.0116 | -5.9 | -5.9 | 164.6@0.96 | 164.82 | -8.9 | -23.1 | -34.2 | -30.7 | -23.0 | -14.9 | -12.1 | 23.1 | 0.1783 | 0.972 | LOOP | 1.91 | 16.3 |
| FUZZBOX | CAB | 0 | 0/0/0/0 | 0.539 | 1.000 | 1.270 | 0.950 | 0.00137 | 0.01049 | 0.01514 | 0.01137 | 0.0025 | 0.0105 | 0.0119 | 0.0120 | -5.9 | -5.9 | 164.6@0.96 | 164.82 | -10.2 | -28.4 | -30.0 | -27.1 | -21.1 | -14.0 | -11.7 | 24.1 | 0.1891 | 1.031 | LOOP | 1.91 | 16.1 |
| FUZZBOX | CAB | 1 | 0/0/0/0 | 0.539 | 1.000 | 1.148 | 0.950 | 0.00137 | 0.01049 | 0.01503 | 0.01254 | 0.0025 | 0.0105 | 0.0131 | 0.0132 | -5.9 | -5.9 | 164.6@0.96 | 164.82 | -6.5 | -18.1 | -24.7 | -24.1 | -18.8 | -12.1 | -10.0 | 23.4 | 0.2042 | 1.113 | LOOP | 1.91 | 16.1 |
| FUZZBOX | MUTE | 0 | 0/0/0/0 | 0.554 | 1.000 | 1.187 | 0.950 | 0.00348 | 0.03920 | 0.05588 | 0.04462 | 0.0063 | 0.0392 | 0.0471 | 0.0470 | -4.6 | -4.2 | 164.6@0.97 | 164.82 | -8.9 | -23.2 | -23.2 | -21.7 | -19.8 | -12.0 | -10.0 | 21.6 | 0.2565 | 1.398 | LOOP | 3.50 | 16.4 |
| FUZZBOX | MUTE | 1 | 0/0/0/0 | 0.446 | 1.000 | 1.178 | 0.950 | 0.00310 | 0.01862 | 0.02655 | 0.02152 | 0.0069 | 0.0186 | 0.0225 | 0.0227 | -26.6 | -26.4 | 162.7@0.87 | 164.82 | -8.9 | -22.8 | -40.7 | -39.8 | -30.6 | -24.5 | -23.3 | 9.8 | 0.0818 | 0.446 | PERC | 0.20 | 16.1 |
| FUZZBOX | SAG | 0 | 0/0/0/0 | 0.539 | 1.000 | 1.241 | 0.950 | 0.00137 | 0.01741 | 0.02482 | 0.01903 | 0.0025 | 0.0174 | 0.0200 | 0.0200 | -5.9 | -6.0 | 164.6@0.96 | 164.82 | -8.4 | -20.7 | -25.2 | -24.6 | -18.5 | -11.9 | -9.9 | 23.3 | 0.1845 | 1.006 | LOOP | 1.91 | 16.4 |
| FUZZBOX | SAG | 1 | 0/0/0/0 | 0.539 | 1.000 | 1.115 | 0.950 | 0.00137 | -0.00221 | -0.00315 | -0.00272 | 0.0025 | -0.0022 | -0.0028 | -0.0029 | -5.9 | -5.7 | 164.6@0.95 | 164.82 | -8.5 | -21.3 | -26.7 | -24.7 | -20.6 | -13.3 | -11.0 | 23.3 | 0.2219 | 1.210 | LOOP | 1.91 | 17.1 |

renders: 65

### Grid 2: tuning grid (every voice x TUNE {0, 0.5, 1} x MUTE {0, 0.5, 1}; cents vs `frequencyFor`)

| voice | TUNE | MUTE | want Hz | cents raw | cents final |
|---|---|---|---|---|---|
| CHUG | 0.0 | 0.0 | 41.20 | -3.7 | -0.4 |
| CHUG | 0.0 | 0.5 | 41.20 | -5.1 | -2.5 |
| CHUG | 0.0 | 1.0 | 41.20 | -7.5 | -6.6 |
| CHUG | 0.5 | 0.0 | 82.40 | -1.3 | -0.9 |
| CHUG | 0.5 | 0.5 | 82.40 | -4.6 | -4.6 |
| CHUG | 0.5 | 1.0 | 82.40 | -13.6 | -13.0 |
| CHUG | 1.0 | 0.0 | 164.80 | -4.5 | -5.1 |
| CHUG | 1.0 | 0.5 | 164.80 | -9.5 | -9.9 |
| CHUG | 1.0 | 1.0 | 164.80 | -26.6 | -26.4 |
| JANGLE | 0.0 | 0.0 | 82.41 | -1.3 | -3.3 |
| JANGLE | 0.0 | 0.5 | 82.41 | -4.5 | -5.1 |
| JANGLE | 0.0 | 1.0 | 82.41 | -13.7 | -13.2 |
| JANGLE | 0.5 | 0.0 | 164.82 | -4.6 | -4.3 |
| JANGLE | 0.5 | 0.5 | 164.82 | -9.5 | -9.8 |
| JANGLE | 0.5 | 1.0 | 164.82 | -26.6 | -26.4 |
| JANGLE | 1.0 | 0.0 | 329.64 | -8.4 | -8.6 |
| JANGLE | 1.0 | 0.5 | 329.64 | -19.7 | -19.6 |
| JANGLE | 1.0 | 1.0 | 329.64 | -52.0 | -52.1 |
| LEAD | 0.0 | 0.0 | 110.00 | -2.0 | -2.3 |
| LEAD | 0.0 | 0.5 | 110.00 | -5.6 | -6.6 |
| LEAD | 0.0 | 1.0 | 110.00 | -17.8 | -17.8 |
| LEAD | 0.5 | 0.0 | 220.00 | -5.9 | -3.0 |
| LEAD | 0.5 | 0.5 | 220.00 | -13.4 | -13.0 |
| LEAD | 0.5 | 1.0 | 220.00 | -35.2 | -35.1 |
| LEAD | 1.0 | 0.0 | 440.00 | -11.6 | -11.1 |
| LEAD | 1.0 | 0.5 | 440.00 | -26.1 | -26.1 |
| LEAD | 1.0 | 1.0 | 440.00 | -68.9 | -68.9 |
| MUTED | 0.0 | 0.0 | 82.41 | -1.1 | -18.0 |
| MUTED | 0.0 | 0.5 | 82.41 | -4.5 | -6.1 |
| MUTED | 0.0 | 1.0 | 82.41 | -13.6 | -13.3 |
| MUTED | 0.5 | 0.0 | 164.82 | -4.6 | -4.6 |
| MUTED | 0.5 | 0.5 | 164.82 | -9.5 | -9.9 |
| MUTED | 0.5 | 1.0 | 164.82 | -26.6 | -26.4 |
| MUTED | 1.0 | 0.0 | 329.64 | -8.4 | -8.6 |
| MUTED | 1.0 | 0.5 | 329.64 | -19.7 | -19.6 |
| MUTED | 1.0 | 1.0 | 329.64 | -52.0 | -52.1 |
| FUZZBOX | 0.0 | 0.0 | 82.41 | -1.3 | -2.0 |
| FUZZBOX | 0.0 | 0.5 | 82.41 | -4.5 | -4.8 |
| FUZZBOX | 0.0 | 1.0 | 82.41 | -13.6 | -13.2 |
| FUZZBOX | 0.5 | 0.0 | 164.82 | -4.6 | -4.2 |
| FUZZBOX | 0.5 | 0.5 | 164.82 | -9.5 | -9.9 |
| FUZZBOX | 0.5 | 1.0 | 164.82 | -26.6 | -26.4 |
| FUZZBOX | 1.0 | 0.0 | 329.64 | -8.4 | -7.3 |
| FUZZBOX | 1.0 | 0.5 | 329.64 | -19.7 | -19.6 |
| FUZZBOX | 1.0 | 1.0 | 329.64 | -52.0 | -52.1 |

### Grid 3: pickup position (JANGLE defaults, `humbuckerOverride` false then true)

JANGLE defaults, f0 = 164.82 Hz, raw at 176400 Hz, toneEnergy over first 0.25 s; dB are absolute 10*log10(power)

humbuckerOverride = false
| harmonic | Hz | pos 0.05 dB | pos 0.12 dB | pos 0.28 dB | pos 0.42 dB | pos 0.5 dB | max spread dB |
|---|---|---|---|---|---|---|---|
| h1 | 164.8 | 47.19 | 47.19 | 47.19 | 47.19 | 47.19 | 0.00 |
| h2 | 329.6 | 48.63 | 48.63 | 48.63 | 48.63 | 48.63 | 0.00 |
| h3 | 494.5 | 47.96 | 47.96 | 47.96 | 47.96 | 47.96 | 0.00 |
| h4 | 659.3 | 45.02 | 45.02 | 45.02 | 45.02 | 45.02 | 0.00 |
| h5 | 824.1 | 40.63 | 40.63 | 40.63 | 40.63 | 40.63 | 0.00 |
| h6 | 988.9 | 38.86 | 38.86 | 38.86 | 38.86 | 38.86 | 0.00 |
| h7 | 1153.7 | 40.79 | 40.79 | 40.79 | 40.79 | 40.79 | 0.00 |
| h8 | 1318.6 | 43.15 | 43.15 | 43.15 | 43.15 | 43.15 | 0.00 |
| h9 | 1483.4 | 44.64 | 44.64 | 44.65 | 44.65 | 44.64 | 0.00 |
| h10 | 1648.2 | 45.00 | 45.00 | 45.00 | 45.00 | 45.00 | 0.00 |
| h11 | 1813.0 | 43.88 | 43.89 | 43.89 | 43.89 | 43.89 | 0.00 |
| h12 | 1977.8 | 40.51 | 40.51 | 40.52 | 40.52 | 40.52 | 0.00 |
worst spread across harmonics: 0.00 dB

humbuckerOverride = true
| harmonic | Hz | pos 0.05 dB | pos 0.12 dB | pos 0.28 dB | pos 0.42 dB | pos 0.5 dB | max spread dB |
|---|---|---|---|---|---|---|---|
| h1 | 164.8 | 49.96 | 49.96 | 49.96 | 49.96 | 49.96 | 0.00 |
| h2 | 329.6 | 50.66 | 50.66 | 50.66 | 50.66 | 50.66 | 0.00 |
| h3 | 494.5 | 48.66 | 48.66 | 48.66 | 48.66 | 48.66 | 0.00 |
| h4 | 659.3 | 43.57 | 43.57 | 43.57 | 43.57 | 43.57 | 0.00 |
| h5 | 824.1 | 35.64 | 35.64 | 35.64 | 35.64 | 35.64 | 0.00 |
| h6 | 988.9 | 26.79 | 26.78 | 26.78 | 26.79 | 26.78 | 0.01 |
| h7 | 1153.7 | 18.66 | 18.67 | 18.66 | 18.67 | 18.67 | 0.02 |
| h8 | 1318.6 | 35.22 | 35.22 | 35.21 | 35.22 | 35.22 | 0.01 |
| h9 | 1483.4 | 41.58 | 41.58 | 41.58 | 41.58 | 41.58 | 0.00 |
| h10 | 1648.2 | 44.70 | 44.70 | 44.70 | 44.71 | 44.70 | 0.00 |
| h11 | 1813.0 | 45.31 | 45.31 | 45.31 | 45.31 | 45.31 | 0.00 |
| h12 | 1977.8 | 42.97 | 42.97 | 42.97 | 42.97 | 42.97 | 0.00 |
worst spread across harmonics: 0.02 dB

### Grid 4: aliasing at the top (TUNE 1, DRIVE 1; other macros default)

| voice | TUNE | DRIVE | want Hz | clarity with bandLimit dB | clarity without bandLimit dB | delta dB | peak with | peak without |
|---|---|---|---|---|---|---|---|---|
| CHUG | 1 | 1 | 164.80 | 19.1 | 19.1 | -0.0 | 0.947 | 0.947 |
| FUZZBOX | 1 | 1 | 329.64 | 3.3 | 3.3 | -0.0 | 0.949 | 0.949 |

## 4. Reading the tables

- Grid 3, single coil: the spectrum of `raw` does not depend on tap position at all (the spread column reads 0.00 dB at every one of the 12 harmonics). Read this as the prediction confirmed: one tap on one delay line is a delay, not a comb.
- Grid 3, humbucker: the second tap is a fixed 0.00045 s (79.4 samples at 176.4 kHz) behind the first, so the notch is at 1/(2 x 0.00045) = 1111 Hz for every note and every tap position (arithmetic, not a measurement); on this render it lands between h6 and h7 of 164.8 Hz (h7 at 1153.7 Hz reads 18.66 dB, h6 26.8, h8 35.2). Tap position changes nothing (worst spread 0.02 dB). The spec's text says the notch "naturally notches out high frequencies"; at 1.1 kHz it is a mid notch, and it does not move with the note.

## 5. Other observations, each with the render that showed it

- Why the pitch is flat is an inference, not measured: the loop's delay is exactly `renderRate / f0` samples, but the one-pole loss filter (cutoff 1800 to 16000 Hz by MUTE) and the stiffness allpass add phase delay inside the loop that is not compensated. That fits the pattern in grid 2 (error grows with f0 and with MUTE, e.g. JANGLE TUNE 1: -8.4, -19.7, -52.0 cents at MUTE 0, 0.5, 1), but I did not test it.
- Raw vs final divergence: CHUG DRIVE 1 reads -2.2 cents raw and +14.0 on final; MUTED TUNE 0 MUTE 0 reads -1.1 raw and -18.0 final (grid 2); MUTED TUNE 0 at defaults reads -2.1 raw and -8.9 final. In these the loudest bin within a semitone of the target after the amp, tone stack and cab is not the fundamental, so a single "cents" number on `final` is not trustworthy for the high-gain or 82 Hz cases.
- Aliasing (grid 4): `Tide.bandLimit` changed nothing: clarity with and without it is 19.1 / 19.1 dB (CHUG) and 3.3 / 3.3 dB (FUZZBOX), peaks identical to three places. Likely reason (inference): the cabinet's voice-coil one-pole at 4.5 to 5.8 kHz and the biquads already remove everything near 19.5 kHz at 4x, so there is nothing left for it to do. This grid does not show whether aliasing is a problem, because clarity here is confounded by the pitch error: FUZZBOX TUNE 1 is -11 cents on final, so its harmonics are off the nominal grid and 3.3 dB says as much about tuning as about fold-back.
- Harmonic clarity in grid 1 varies from -5.1 dB (LEAD TUNE 1) to 60.1 dB (CHUG TUNE 0); best at CHUG defaults (42.0) and worst for LEAD (-5.1 to 8.9 dB except TUNE 0 at 37.2). LEAD defaults have an almost flat top (h3..h8 at -6.7, -11.3, -13.9, -11.4, -3.0, +0.2 dB re h1, h2 at -18.9), i.e. a bright, noise-like spectrum, not a harmonic one.
- Level: `Loudness.of(final)` against 0.1834 ranges from 0.223x (JANGLE MUTE 1) to 1.545x (LEAD DRIVE 1). The chain ends in `normalizeByFold` and Punch, not a loudness level. Before normalization the cabinet output peaks above 1 in high-gain renders (1.598 FUZZBOX DRIVE 1, 1.401 LEAD DRIVE 1, 1.367 FUZZBOX TUNE 1); the final never exceeds 0.988.
- Classifier: 60 of 65 renders classify LOOP (all with duration 1.91 s or 3.50 s); the 5 PERC results are exactly the five MUTE 1 renders (0.20 s). `drumClassFor` in the spec would say TONAL for the 60.
- Duration: MUTE 0.2 gives 1.91 s, MUTE 0 gives the 3.50 s cap, MUTE 1 gives the 0.20 s floor.
- SAG: no measurable effect on JANGLE (SAG 0 and 1 give the same peak, spectrum and clarity, DC differs only in the 5th place: 0.00316 vs 0.00314), because JANGLE's gain keeps |x| under 1 almost always. It shows up as DC on the high-gain voices: CHUG -0.0139 (SAG 0) to -0.0211 (SAG 1) of peak, and it flips the sign on FUZZBOX (+0.0200 to -0.0029) and MUTED (+0.0019 to -0.0022). LEAD SAG 1 misled `Pitch.detect` (243.6 Hz at confidence 0.84, target 220).
- DC source: the asymmetric curve (`tanh` above zero, `x / sqrt(1 + x^2)` below) is soft on the negative side, so high-gain voices go negative (CHUG DRIVE 1 amped mean -0.0542); the tone stack and cabinet, having no DC blocker, pass it through (cabbed -0.0772) and the final keeps -0.0596.
- Speed: steady-state 15.6 to 21.4 ms of wall time per rendered second (the first render, with JIT warm-up, 41.8); `raw` alone is a 176.4 kHz single-sample loop.
- Not measured: determinism across two renders, anything with `seed != 0`, `scramble`, the addendum. Pitch and clarity were measured only at the default seed's noise.


## 6. Appendix: complete source, verbatim

### synth/src/main/kotlin/com/snipsnap/synth/CoilProbe.kt

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * THROWAWAY PROBE. The owner's COIL specification, transcribed as written and
 * measured; nothing here ships. See the report for the compile changes C1..
 */
enum class CoilProbeVoice {
    CHUG,
    JANGLE,
    LEAD,
    MUTED,
    FUZZBOX,
}

internal object CoilProbe {

    const val TUNE_SEMITONES = 24
    const val ROOT_MIDI = 40 // E2 (~82.41 Hz standard low E)

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("DRIVE", 0.45f),
        MacroSpec("TONE", 0.5f, neutral = 0.5f),
        MacroSpec("CAB", 0.6f),
        MacroSpec("MUTE", 0.2f),
        MacroSpec("SAG", 0.35f),
    )

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: CoilProbeVoice): List<MacroSpec> = MACROS

    fun defaults(voice: CoilProbeVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun drumClassFor(voice: CoilProbeVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val mute = macros["MUTE"] ?: defaults(voice).getValue("MUTE")
        return if (mute > 0.65f) DrumClass.PERC else DrumClass.TONAL
    }

    fun rootHz(voice: CoilProbeVoice): Float = when (voice) {
        CoilProbeVoice.CHUG    -> 41.20f // Low E1 (Drop tuning)
        CoilProbeVoice.JANGLE  -> 82.41f // Standard E2
        CoilProbeVoice.LEAD    -> 110.0f // A2
        CoilProbeVoice.MUTED   -> 82.41f // E2
        CoilProbeVoice.FUZZBOX -> 82.41f // E2
    }

    fun frequencyFor(voice: CoilProbeVoice, tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return rootHz(voice) * 2f.pow(semis / 12f)
    }

    // C2: the spec's CoilPresets returns CoilPatch; the probe keeps the macro maps only.
    private object ProbePresets {
        private fun p(vararg macros: Pair<String, Float>): Map<String, Float> = macros.toMap()

        fun forVoice(voice: CoilProbeVoice): List<Map<String, Float>> = when (voice) {
            CoilProbeVoice.CHUG -> chugPresets
            CoilProbeVoice.JANGLE -> janglePresets
            CoilProbeVoice.LEAD -> leadPresets
            CoilProbeVoice.MUTED -> emptyList()
            CoilProbeVoice.FUZZBOX -> emptyList()
        }

        private val chugPresets = listOf(
            p("TUNE" to 0.1f, "DRIVE" to 0.85f, "TONE" to 0.65f, "CAB" to 0.95f, "MUTE" to 0.55f, "SAG" to 0.40f),
            p("TUNE" to 0.0f, "DRIVE" to 0.90f, "TONE" to 0.80f, "CAB" to 0.85f, "MUTE" to 0.80f, "SAG" to 0.20f),
            p("TUNE" to 0.15f, "DRIVE" to 0.75f, "TONE" to 0.35f, "CAB" to 0.90f, "MUTE" to 0.45f, "SAG" to 0.50f),
        )

        private val janglePresets = listOf(
            p("TUNE" to 0.5f, "DRIVE" to 0.25f, "TONE" to 0.70f, "CAB" to 0.20f, "MUTE" to 0.10f, "SAG" to 0.15f),
            p("TUNE" to 0.6f, "DRIVE" to 0.35f, "TONE" to 0.85f, "CAB" to 0.10f, "MUTE" to 0.20f, "SAG" to 0.30f),
        )

        private val leadPresets = listOf(
            p("TUNE" to 0.45f, "DRIVE" to 0.80f, "TONE" to 0.55f, "CAB" to 0.70f, "MUTE" to 0.05f, "SAG" to 0.85f),
        )
    }

    fun scramble(
        voice: CoilProbeVoice,
        random: Random,
        temperature: Float = 0.35f,
        near: Patch? = null,
    ): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) {
            base + near.macros.filterKeys { it in base }
        } else {
            base + ProbePresets.forVoice(voice).random(random).filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** C4: the stages of the chain, for measurement. [cabbed] is a copy taken before Tide.bandLimit. */
    data class Stages(
        val raw: FloatArray,
        val amped: FloatArray,
        val cabbed: FloatArray,
        val final: Snip,
    )

    fun render(
        voice: CoilProbeVoice,
        macros: Map<String, Float> = emptyMap(),
        seed: Int = 0,
        pickupPosOverride: Float? = null,
        humbuckerOverride: Boolean? = null,
    ): Snip = renderStages(voice, macros, seed, pickupPosOverride, humbuckerOverride).final

    // C6: skipBandLimit exists only for grid 4's variant; false is the spec's chain.
    internal fun renderStages(
        voice: CoilProbeVoice,
        macros: Map<String, Float> = emptyMap(),
        seed: Int = 0,
        pickupPosOverride: Float? = null,
        humbuckerOverride: Boolean? = null,
        skipBandLimit: Boolean = false,
    ): Stages {
        val m = defaults(voice) + macros
        val renderRate = RATE * Dsp.OVERSAMPLE

        val tune = m.getValue("TUNE")
        val drive = m.getValue("DRIVE")
        val tone = m.getValue("TONE")
        val cab = m.getValue("CAB")
        val mute = m.getValue("MUTE")
        val sag = m.getValue("SAG")

        val f0 = frequencyFor(voice, tune)
        val stringT60 = Dsp.expMap(1f - mute, 0.08f, 3.2f)
        val durationSec = (stringT60 * 1.25f).coerceIn(0.2f, 3.5f)
        val totalFrames = (durationSec * renderRate).toInt().coerceAtLeast(256)
        val rawBuffer = FloatArray(totalFrames)

        val prng = Dsp.Noise(if (seed == 0) Dsp.seedFor("COIL", voice, f0) else seed)

        // 1. Digital Waveguide Delay Line Setup
        val delaySamples = (renderRate / f0).toDouble()
        val maxDelay = (delaySamples * 2.0).toInt() + 64
        val delayLine = FloatArray(maxDelay)
        var writeIdx = 0

        // Inharmonic dispersion allpass (stiff steel core string)
        val stiffnessCoeff = -0.15f
        var apX1 = 0f
        var apY1 = 0f

        // Nut loss filter
        val nutFilter = Dsp.OnePole(renderRate)
        val nutCutoff = Dsp.expMap(1f - mute, 1800f, 16000f)
        val feedbackGain = Dsp.lin(1f - mute, 0.88f, 0.994f)

        // Plectrum Attack Excitation
        val pickFrames = (0.003f * renderRate).toInt().coerceAtLeast(4)
        val pickBuffer = FloatArray(pickFrames)
        val pickLp = Dsp.OnePole(renderRate)
        val pickCutoff = Dsp.expMap(1f - (mute * 0.5f), 3000f, 15000f)
        for (i in 0 until pickFrames) {
            val env = 0.5f * (1f - cos(2.0 * PI * i / pickFrames).toFloat())
            val noise = prng.next()
            pickBuffer[i] = pickLp.lp(noise * env, pickCutoff)
        }

        // Pickup placement geometry: normalized distance from bridge (0.0 = bridge, 1.0 = nut)
        val pickupPos = pickupPosOverride ?: when (voice) {
            CoilProbeVoice.CHUG -> 0.12f // Bridge humbucker position
            CoilProbeVoice.JANGLE -> 0.28f // Middle single-coil
            CoilProbeVoice.LEAD -> 0.42f // Neck pickup
            CoilProbeVoice.MUTED -> 0.15f // Bridge
            CoilProbeVoice.FUZZBOX -> 0.35f // Neck/Middle
        }
        val tapDelay = (delaySamples * pickupPos).coerceIn(1.0, delaySamples - 1.0)
        val isHumbucker = humbuckerOverride ?: (voice == CoilProbeVoice.CHUG || voice == CoilProbeVoice.LEAD)
        val humbuckerSpacing = (renderRate * 0.00045).coerceAtLeast(1.0) // ~18mm coil spacing

        // 2. Waveguide String Simulation Loop
        for (n in 0 until totalFrames) {
            val excitation = if (n < pickFrames) pickBuffer[n] else 0f

            // Read end of waveguide
            val readD = writeIdx - delaySamples
            var r0 = readD.toInt()
            while (r0 < 0) r0 += maxDelay
            r0 %= maxDelay
            val r1 = (r0 + 1) % maxDelay
            val frac = (readD - readD.toInt()).toFloat()
            val waveEnd = delayLine[r0] + (delayLine[r1] - delayLine[r0]) * frac

            // Inharmonic dispersion allpass
            val dispersed = stiffnessCoeff * (waveEnd - apY1) + apX1
            apX1 = waveEnd
            apY1 = dispersed

            // High frequency dissipation at termination
            val reflected = nutFilter.lp(dispersed, nutCutoff) * feedbackGain

            delayLine[writeIdx] = excitation + reflected

            // Dual-point electromagnetic pickup extraction
            val pReadD = writeIdx - tapDelay
            var pr0 = pReadD.toInt()
            while (pr0 < 0) pr0 += maxDelay
            pr0 %= maxDelay
            val pr1 = (pr0 + 1) % maxDelay
            val pFrac = (pReadD - pReadD.toInt()).toFloat()
            var pickupSignal = delayLine[pr0] + (delayLine[pr1] - delayLine[pr0]) * pFrac

            if (isHumbucker) {
                val hReadD = writeIdx - (tapDelay + humbuckerSpacing)
                var hr0 = hReadD.toInt()
                while (hr0 < 0) hr0 += maxDelay
                hr0 %= maxDelay
                val hr1 = (hr0 + 1) % maxDelay
                val hFrac = (hReadD - hReadD.toInt()).toFloat()
                val coilB = delayLine[hr0] + (delayLine[hr1] - delayLine[hr0]) * hFrac
                pickupSignal = (pickupSignal + coilB) * 0.707f
            }

            writeIdx = (writeIdx + 1) % maxDelay
            rawBuffer[n] = pickupSignal
        }

        // 3. Amplifier Non-Linear Saturation & Power Sag Stage
        val ampProcessed = applyAmpDrive(rawBuffer, voice, drive, sag, renderRate)

        // 4. FMV Tone Stack Filter Section
        val toneProcessed = applyToneStack(ampProcessed, voice, tone, renderRate)

        // 5. Reactive Speaker & Cabinet Impedance Network
        val cabProcessed = applyCabinetNetwork(toneProcessed, voice, cab, renderRate)
        val cabbedCopy = cabProcessed.copyOf()

        // 6. Anti-Aliasing (Tide Butterworth-8) and Punch Decimation
        if (!skipBandLimit) Tide.bandLimit(cabProcessed, renderRate)
        Dsp.normalizeByFold(cabProcessed, channels = 1)

        val punchAmount = Dsp.lin(mute, 0.25f, 0.75f)
        val decimated = Punch.applyOversampled(cabProcessed, punchAmount, RATE, channels = 1)
        Dsp.limitPeak(decimated)
        Dsp.fadeTail(decimated, ms = 4f, rate = RATE, channels = 1)

        return Stages(rawBuffer, ampProcessed, cabbedCopy, Snip(decimated, channels = 1, sampleRate = RATE))
    }

    private fun applyAmpDrive(
        input: FloatArray,
        voice: CoilProbeVoice,
        driveMacro: Float,
        sagMacro: Float,
        rate: Int,
    ): FloatArray {
        val total = input.size
        val output = FloatArray(total)

        val gainPre = when (voice) {
            CoilProbeVoice.CHUG -> Dsp.expMap(driveMacro, 2.0f, 35.0f)
            CoilProbeVoice.LEAD -> Dsp.expMap(driveMacro, 3.0f, 45.0f)
            CoilProbeVoice.FUZZBOX -> Dsp.expMap(driveMacro, 5.0f, 60.0f)
            CoilProbeVoice.MUTED -> Dsp.expMap(driveMacro, 1.5f, 18.0f)
            CoilProbeVoice.JANGLE -> Dsp.expMap(driveMacro, 0.8f, 6.0f)
        }

        var vSag = 0f
        val sagCharge = 1f - exp(-1.0 / (0.005 * rate)).toFloat()
        val sagDischarge = 1f - exp(-1.0 / (0.120 * rate)).toFloat()
        val sagDepth = sagMacro * 0.45f

        for (n in 0 until total) {
            val x = input[n] * gainPre

            // Dynamic grid sag calculation
            val absX = abs(x)
            if (absX > 1.0f) {
                vSag += sagCharge * (absX - 1.0f - vSag)
            } else {
                vSag -= sagDischarge * vSag
            }

            val biasedInput = x - (vSag * sagDepth)

            // Triode asymmetric transfer curve
            val saturated = if (biasedInput >= 0f) {
                tanh(biasedInput)
            } else {
                biasedInput / sqrt(1.0f + biasedInput * biasedInput)
            }

            output[n] = saturated
        }
        return output
    }

    private fun applyToneStack(
        input: FloatArray,
        @Suppress("UNUSED_PARAMETER") voice: CoilProbeVoice,
        toneMacro: Float,
        rate: Int,
    ): FloatArray {
        val total = input.size
        val output = FloatArray(total)

        // Bilinear tonestack approximation (the specification's comment named three amplifier makers; removed here)
        val midDipFreq = Dsp.lin(toneMacro, 380f, 650f)
        val midDipDepth = Dsp.lin(1f - toneMacro, -12f, 2f)
        val trebleFreq = Dsp.expMap(toneMacro, 2500f, 6000f)
        val bassFreq = 120f

        val bassShelf = Dsp.Biquad().apply { lowShelf(bassFreq, 3.0f, rate) }
        val midNotch = Dsp.Biquad().apply { peaking(midDipFreq, midDipDepth, 1.4f, rate) }
        val trebleShelf = Dsp.Biquad().apply { highShelf(trebleFreq, (toneMacro - 0.5f) * 8f, rate) }

        for (n in 0 until total) {
            val s = input[n]
            output[n] = trebleShelf.process(midNotch.process(bassShelf.process(s)))
        }
        return output
    }

    private fun applyCabinetNetwork(
        input: FloatArray,
        @Suppress("UNUSED_PARAMETER") voice: CoilProbeVoice,
        cabMacro: Float,
        rate: Int,
    ): FloatArray {
        val total = input.size
        val output = FloatArray(total)

        // Cab morph: 0.0 = Open-back 1x12 Combo, 1.0 = Closed-back 4x12 Oversized Stack
        val thumpFreq = Dsp.lin(cabMacro, 110f, 78f)
        val thumpQ = Dsp.lin(cabMacro, 1.6f, 2.4f)
        val notchFreq = Dsp.lin(1f - cabMacro, 380f, 500f)
        val notchDepth = (1f - cabMacro) * -9.0f // Open-back rear-phase cancellation notch

        val coneThump = Dsp.Biquad().apply { peaking(thumpFreq, 6.0f, thumpQ, rate) }
        val openBackNotch = Dsp.Biquad().apply { peaking(notchFreq, notchDepth, 2.0f, rate) }

        // Cone breakup modes
        val breakup1 = Dsp.Biquad().apply { bandpass(2600f, 3.5f, rate) }
        val breakup2 = Dsp.Biquad().apply { bandpass(3750f, 4.0f, rate) }

        // Voice-coil inductance lowpass
        val voiceCoilLp = Dsp.OnePole(rate)
        val hfCutoff = Dsp.expMap(1f - (cabMacro * 0.3f), 4500f, 5800f)

        for (n in 0 until total) {
            val s = input[n]
            val thumping = openBackNotch.process(coneThump.process(s))
            val breakups = (breakup1.process(s) * 0.35f) + (breakup2.process(s) * 0.25f)
            val combined = thumping + breakups
            output[n] = voiceCoilLp.lp(combined, hfCutoff)
        }
        return output
    }
}
```

### synth/src/test/kotlin/com/snipsnap/synth/CoilProbeTest.kt

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THROWAWAY PROBE for the COIL specification as written. Print-only; the only
 * assertions are finiteness guards. Gated on COIL_SPIKE_OUT.
 */
class CoilProbeTest {

    private val renderRate = Dsp.RATE * Dsp.OVERSAMPLE
    private val macroNames = listOf("TUNE", "DRIVE", "TONE", "CAB", "MUTE", "SAG")

    // ---------- helpers copied from ForkTest (private there) ----------

    private fun magnitudes(samples: FloatArray, start: Int, n: Int): FloatArray {
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples.getOrElse(start + i) { 0f } * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return FloatArray(n / 2) { b -> (re[b] * re[b] + im[b] * im[b]) }
    }

    private fun harmonicClarity(samples: FloatArray, rate: Int, f0: Float, start: Int, n: Int = 1 shl 16): Double {
        val mag = magnitudes(samples, start, n)
        var on = 0.0
        var off = 0.0
        for (b in 1 until mag.size) {
            val hz = b.toFloat() * rate / n
            if (hz > 20_000f) break
            if (hz < f0 / 2) continue
            val k = Math.round(hz / f0)
            if (abs(hz - k * f0) < 8f) on += mag[b] else off += mag[b]
        }
        return 10 * log10(on / off)
    }

    // ---------- measurement helpers ----------

    private class Stat(val nonFinite: Int, val peak: Double, val mean: Double) {
        val dcOverPeak: Double get() = if (peak > 0.0) mean / peak else 0.0
    }

    private fun stat(x: FloatArray): Stat {
        var bad = 0
        var peak = 0.0
        var sum = 0.0
        var count = 0
        for (v in x) {
            if (!v.isFinite()) { bad++; continue }
            val a = abs(v.toDouble())
            if (a > peak) peak = a
            sum += v
            count++
        }
        return Stat(bad, peak, if (count > 0) sum / count else 0.0)
    }

    private fun db(ratio: Double): Double = 10.0 * log10(ratio.coerceAtLeast(1e-30))

    private fun f(v: Double, d: Int = 3): String = "%.${d}f".format(v)

    private fun cents(hz: Double, want: Float): Double =
        runCatching { FineTuning.cents(hz, want.toDouble()) }.getOrDefault(Double.NaN)

    private fun measured(samples: FloatArray, rate: Int, want: Float): Double =
        runCatching { FineTuning.measuredHz(samples, rate, want) }.getOrDefault(Double.NaN)

    private class Timed(val stages: CoilProbe.Stages, val ms: Double)

    private fun timedRender(
        voice: CoilProbeVoice,
        macros: Map<String, Float> = emptyMap(),
        pickupPos: Float? = null,
        humbucker: Boolean? = null,
        skipBandLimit: Boolean = false,
    ): Timed {
        val t0 = System.nanoTime()
        val s = CoilProbe.renderStages(voice, macros, 0, pickupPos, humbucker, skipBandLimit)
        val ms = (System.nanoTime() - t0) / 1e6
        return Timed(s, ms)
    }

    private val nonFiniteFindings = ArrayList<String>()

    /** Finiteness guard: record now, fail at the end of the test so the table is still written. */
    private fun assertFinite(label: String, st: CoilProbe.Stages) {
        for ((n, a) in listOf("raw" to st.raw, "amped" to st.amped, "cabbed" to st.cabbed, "final" to st.final.samples)) {
            val bad = a.count { !it.isFinite() }
            if (bad != 0) { println("NONFINITE $label $n: $bad samples"); nonFiniteFindings += "$label $n: $bad" }
        }
    }

    private fun guard() = assertEquals(emptyList<String>(), nonFiniteFindings.toList(), "non-finite samples found")

    private fun writeWav(outDir: String, name: String, snip: Snip) {
        File(outDir).mkdirs()
        FileOutputStream(File(outDir, "$name.wav")).use { WavWriter.write(it, snip) }
    }

    private fun emit(outDir: String, file: String, lines: List<String>) {
        File(outDir).mkdirs()
        File(outDir, file).writeText(lines.joinToString("\n") + "\n")
        lines.forEach { println(it) }
    }

    // ---------- grid 1 ----------

    @Test
    fun `grid 1 macro corners`() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val lines = ArrayList<String>()
        lines += "| voice | macro | value | NF r/a/c/f | peak raw | peak amp | peak cab | peak fin | DC raw | DC amp | DC cab | DC fin | DC/pk raw | DC/pk amp | DC/pk cab | DC/pk fin | cents raw | cents fin | Pitch.detect | want Hz | h2 | h3 | h4 | h5 | h6 | h7 | h8 | clarity dB | loud | loud/0.1834 | class | dur s | ms/s |"
        lines += "|" + "---|".repeat(33)
        var count = 0
        for (voice in CoilProbeVoice.entries) {
            val cases = ArrayList<Triple<String, String, Map<String, Float>>>()
            cases += Triple("DEFAULTS", "-", emptyMap())
            for (mn in macroNames) for (v in listOf(0f, 1f)) cases += Triple(mn, v.toInt().toString(), mapOf(mn to v))
            for ((macro, value, macros) in cases) {
                val t = timedRender(voice, macros)
                val st = t.stages
                val label = "$voice $macro $value"
                assertFinite(label, st)
                val fin = st.final
                val tune = macros["TUNE"] ?: CoilProbe.defaults(voice).getValue("TUNE")
                val want = CoilProbe.frequencyFor(voice, tune)
                val sr = stat(st.raw); val sa = stat(st.amped); val sc = stat(st.cabbed); val sf = stat(fin.samples)
                val cRaw = cents(measured(st.raw, renderRate, want), want)
                val cFin = cents(measured(fin.samples, fin.sampleRate, want), want)
                val pd = Pitch.detect(fin)
                val e1 = PluckSpectra.toneEnergy(fin, want)
                val hs = (2..8).map { k -> f(db(PluckSpectra.toneEnergy(fin, want * k) / e1), 1) }
                val clarity = harmonicClarity(fin.samples, fin.sampleRate, want, (0.05f * fin.sampleRate).toInt())
                val loud = Loudness.of(fin)
                val cls = Classifier.classify(fin).drumClass
                lines += "| $voice | $macro | $value | ${sr.nonFinite}/${sa.nonFinite}/${sc.nonFinite}/${sf.nonFinite} | ${f(sr.peak)} | ${f(sa.peak)} | ${f(sc.peak)} | ${f(sf.peak)} | ${f(sr.mean, 5)} | ${f(sa.mean, 5)} | ${f(sc.mean, 5)} | ${f(sf.mean, 5)} | ${f(sr.dcOverPeak, 4)} | ${f(sa.dcOverPeak, 4)} | ${f(sc.dcOverPeak, 4)} | ${f(sf.dcOverPeak, 4)} | ${f(cRaw, 1)} | ${f(cFin, 1)} | ${pd?.let { "${f(it.hz.toDouble(), 1)}@${f(it.confidence.toDouble(), 2)}" } ?: "null"} | ${f(want.toDouble(), 2)} | ${hs.joinToString(" | ")} | ${f(clarity, 1)} | ${f(loud.toDouble(), 4)} | ${f(loud / Dsp.MELODIC_LOUDNESS_TARGET.toDouble(), 3)} | $cls | ${f(fin.durationSeconds.toDouble(), 2)} | ${f(t.ms / fin.durationSeconds, 1)} |"
                val wavName = if (macro == "DEFAULTS") "${voice}_DEFAULTS_0" else "${voice}_${macro}_$value"
                writeWav(outDir, wavName, fin)
                count++
            }
        }
        lines += ""
        lines += "renders: $count"
        emit(outDir, "grid1.md", lines)
        guard()
    }

    // ---------- grid 2 ----------

    @Test
    fun `grid 2 tuning grid`() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val lines = ArrayList<String>()
        lines += "| voice | TUNE | MUTE | want Hz | cents raw | cents final |"
        lines += "|---|---|---|---|---|---|"
        for (voice in CoilProbeVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) for (mute in listOf(0f, 0.5f, 1f)) {
            val t = timedRender(voice, mapOf("TUNE" to tune, "MUTE" to mute))
            assertFinite("$voice tune=$tune mute=$mute", t.stages)
            val fin = t.stages.final
            val want = CoilProbe.frequencyFor(voice, tune)
            val cRaw = cents(measured(t.stages.raw, renderRate, want), want)
            val cFin = cents(measured(fin.samples, fin.sampleRate, want), want)
            lines += "| $voice | $tune | $mute | ${f(want.toDouble(), 2)} | ${f(cRaw, 1)} | ${f(cFin, 1)} |"
        }
        emit(outDir, "grid2.md", lines)
        guard()
    }

    // ---------- grid 3 ----------

    @Test
    fun `grid 3 pickup position`() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val positions = listOf(0.05f, 0.12f, 0.28f, 0.42f, 0.5f)
        val want = CoilProbe.frequencyFor(CoilProbeVoice.JANGLE, CoilProbe.defaults(CoilProbeVoice.JANGLE).getValue("TUNE"))
        val lines = ArrayList<String>()
        lines += "JANGLE defaults, f0 = ${f(want.toDouble(), 2)} Hz, raw at $renderRate Hz, toneEnergy over first 0.25 s; dB are absolute 10*log10(power)"
        for (humbucker in listOf(false, true)) {
            lines += ""
            lines += "humbuckerOverride = $humbucker"
            lines += "| harmonic | Hz | " + positions.joinToString(" | ") { "pos $it dB" } + " | max spread dB |"
            lines += "|---|---|" + "---|".repeat(positions.size + 1)
            val energies = positions.map { pos ->
                val t = timedRender(CoilProbeVoice.JANGLE, emptyMap(), pickupPos = pos, humbucker = humbucker)
                assertFinite("JANGLE pos=$pos hb=$humbucker", t.stages)
                val snip = Snip(t.stages.raw, 1, renderRate)
                (1..12).map { k -> db(PluckSpectra.toneEnergy(snip, want * k)) }
            }
            var worst = 0.0
            for (k in 1..12) {
                val col = energies.map { it[k - 1] }
                val spread = col.max() - col.min()
                if (spread > worst) worst = spread
                lines += "| h$k | ${f(want.toDouble() * k, 1)} | ${col.joinToString(" | ") { f(it, 2) }} | ${f(spread, 2)} |"
            }
            lines += "worst spread across harmonics: ${f(worst, 2)} dB"
        }
        emit(outDir, "grid3.md", lines)
        guard()
    }

    // ---------- grid 4 ----------

    @Test
    fun `grid 4 aliasing at the top`() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val lines = ArrayList<String>()
        lines += "| voice | TUNE | DRIVE | want Hz | clarity with bandLimit dB | clarity without bandLimit dB | delta dB | peak with | peak without |"
        lines += "|---|---|---|---|---|---|---|---|---|"
        for (voice in listOf(CoilProbeVoice.CHUG, CoilProbeVoice.FUZZBOX)) {
            val macros = mapOf("TUNE" to 1f, "DRIVE" to 1f)
            val want = CoilProbe.frequencyFor(voice, 1f)
            val with = timedRender(voice, macros).stages
            val without = timedRender(voice, macros, skipBandLimit = true).stages
            assertFinite("$voice with", with)
            assertFinite("$voice without", without)
            val start = (0.05f * Dsp.RATE).toInt()
            val cw = harmonicClarity(with.final.samples, Dsp.RATE, want, start)
            val cn = harmonicClarity(without.final.samples, Dsp.RATE, want, start)
            lines += "| $voice | 1 | 1 | ${f(want.toDouble(), 2)} | ${f(cw, 1)} | ${f(cn, 1)} | ${f(cw - cn, 1)} | ${f(stat(with.final.samples).peak)} | ${f(stat(without.final.samples).peak)} |"
        }
        emit(outDir, "grid4.md", lines)
        guard()
    }
}
```

## 7. Run

Command: `COIL_SPIKE_OUT=<scratch>/spike-out/a ./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.CoilProbeTest" -i` from the worktree root.

- Gradle exit code: 0 (BUILD SUCCESSFUL in 58 s, including the first compile of the modules).
- Test wall time: 32.5 s for the suite (grid 1: 30.8 s for 65 renders with WAV writes and classifier; grid 2: 1.2 s; grid 3: 0.4 s; grid 4: 0.1 s), 4 tests, 0 failures, 0 skipped.
- The raw Gradle log, the per-grid tables and the 65 WAVs were scratch output (`<scratch>/spike-out/a`) and are not kept.

---

# MAGNET Phase 0, part B: the corrected model on the house's primitives

Base: 91841146 (a descendant of 50d3f80c). Nothing committed. New files only: `synth/src/main/kotlin/com/snipsnap/synth/CoilSpike.kt`, `synth/src/test/kotlin/com/snipsnap/synth/CoilSpikeTest.kt`.
WAVs: `spike-out/b/` (51 files: 34 `P1_<VOICE>_<MACRO>_<VALUE>.wav`, 12 `P1_/P1n_/P2_/P3_` placement files, `DRY_/AMP_` KICK and SNARE, `AMP_SNARE_TRANSPARENT`).

## 1. Headline

**In tune:** the dry string+pickup, measured at 176.4 kHz over all 36 tuning cells, sits between -0.62 and +1.11 cents of nominal (default window; -1.21 to +2.30 with a 0.35 s window), inside the 5-cent bar; the large cents figures in the P1 *final* column (up to +37) are a measurement artefact of reading a spectral peak off a saturated, decaying line, not a pitch shift (Extra B: they change with window start, 15.9 to 43.8 cents on one cell, while `Pitch.detect` reads 82.43 Hz against 82.41 nominal).

**The comb:** the single-coil comb does what it promises, with p = 0.5 leaving h2/h4/h6/h8 at -52.1/-54.0/-36.6/-37.2 dB under their quieter neighbours and p = 0.25 leaving h4/h8 at -47.4/-28.7 dB (all past the 20 dB bar); but the humbucker as briefed (`0.707 * (c(p) + c(p+dp))`) has no coil-spacing notch anywhere in h1..h22, because the two combs' linear-phase terms differ, and a phase-aligned pair measured -49.4 dB at h18 (= 1/dp), matching the algebra to 0.1 dB (Extra C).

**Placement:** on a steady exactly-harmonic input the two 4x placements (P1 in-render and P3 rack) reach the measurement floor (clarity 49.4 to 51.3 dB against 49.6 to 50.9 for an 8x reference) while the native-rate rack pass P2 falls 4.5 to 17.5 dB short (31.9 to 46.8 dB), and on the string renders P2 differs from the 8x reference by -2.4 to -7.5 dB against -27 to -32 dB for P3; the amp costs 5.3 ms per rendered second at 4x against 1.4 at native rate, so P1 (amp 5.3 + output chain 8.5 = 13.8 ms/s) is the cheapest 4x placement, while P3 as briefed costs 84 to 86 ms/s because the windowed-sinc up-sample alone is 72.2 ms/s.

Caveat on the brief's own aliasing metric: `harmonicClarity` on the string renders ranks P2 *best* (CHUG T0.5 D1: P1 61.3, P1n 59.8, P2 67.4, P3 60.6; the 8x reference reads 53.1), which contradicts the steady-tone and residual measurements above. I did not find why (one candidate: a decaying string's lines are wider than the 8 Hz on-harmonic window, so a metric that counts real high harmonics as "off" penalises the placements that keep them); treat the string-render clarity column as not an aliasing measure.

Answers to the dispatch questions, in one place:
- Dry-string pitch error: -0.62 to +1.11 cents (36 cells).
- Comb notching: yes for single coils (numbers above); humbucker spacing notch absent as briefed, present when phase-aligned.
- PICK monotonic in centroid: yes for both voices (0 falling steps of 10), but saturating: JANGLE 1459 to 1618 Hz, CHUG 719 to 835 Hz across PICK 0..1 while the exciter corner moves 4.6x; the last three steps together move the centroid 1.0% (JANGLE) and 1.7% (CHUG).
- CHUG DRIVE 1, TUNE 0.5, clarity (nominal f0) / amp ms per second: P1 61.3 dB / 5.3, P2 67.4 dB / 1.4, P3 60.6 dB / 5.3 (path ms/s: P1 13.8, P2 1.4, P3 84.2). At TUNE 1 all three read 32.0 to 32.1 dB (a 0.86 s note; the dry itself reads 32.5).
- Kick and snare through the P3 amp at DRIVE 0.6: KICK stays KICK, SNARE stays SNARE. The "near-transparent" point (DRIVE 0, SAG 0, TONE 0.5, CAB 0) is not transparent: RMS of (out - dry) is +2.8 dB re dry RMS (+0.9 dB after an RMS-matching gain).

Interpretations I had to make where the brief was silent (recorded so round one can overrule them): BLEND between 0 and 1 weights the two full pickups `(1 - BLEND)` and `BLEND` (the brief's "both at 0.5" read as their weights at BLEND 0.5); the two cabinet bandpass taps read the tone stack's output and are added to the thump/notch cascade before the final low-pass; TUNE 0.5 is `round(12)` semitones, one octave above the root (so the "default" notes are E3 and B2); grid 3 (a) and (b) use the pickup with no resonance, grid 3 (c) uses the full voice pickup; harmonic levels are the peak power within +/-1.5% of k*f0, Blackman-Harris, 65536 samples from 0.05 s.

No constant was changed. What differs from the brief: `Tide.bandLimit`, `Dsp.levelTo` and `Dsp.fadeTail` work in place and return Unit (the spike copies first), `Snip` lives in `com.snipsnap.audio` (Cleanup.kt), and `pickup` gained a trailing optional `aligned` parameter (default off, so the brief's signature and behaviour are unchanged).

Gradle: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.CoilSpikeTest"` exit code 0 on the final full run (all 10 tests ran, 41.9 s wall; the first run, 7 tests, 1 m 03 s). Grid tables are from the first run; later runs added Extras A to C and the `aligned` option, whose default leaves every earlier number unchanged. Timings are single-machine, min of 3 to 5 where stated, otherwise one run after warm-up.

## 2. Tables

## Grid 1: corners, P1 (in-render), 34 renders

dry = string+pickup at R (176.4 kHz); final = P1 output at 44.1 kHz. Cents from `FineTuning.measuredHz` (dry at R, final at 44.1 kHz), want = nominal f0. Clarity from 0.05 s at nominal f0. ms/s = milliseconds per rendered second (string+pickup, and amp alone); single timed run after warm-up, so noisy.

| render | non-finite | f0 Hz | dry pk | dry mean | fin pk | fin mean | dry cents | fin cents | detect Hz | clarity dB | loudness | class | dur s | str+pu ms/s | amp ms/s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JANGLE DEFAULTS | 0 | 164.82 | 0.858 | -0.00000 | 0.400 | -0.00000 | -0.6 | -0.3 | 164.6 | 64.6 | 0.1834 | LOOP | 2.50 | 6.2 | 5.7 |
| JANGLE TUNE 0 | 0 | 82.41 | 1.379 | 0.00000 | 0.380 | -0.00000 | 1.1 | 12.5 | 82.4 | 65.6 | 0.1834 | LOOP | 4.00 | 5.2 | 5.0 |
| JANGLE TUNE 1 | 0 | 329.64 | 1.003 | -0.00000 | 0.463 | 0.00000 | -0.1 | 2.3 | 329.1 | 50.2 | 0.1834 | SNARE | 1.14 | 9.7 | 5.2 |
| JANGLE MUTE 0 | 0 | 164.82 | 0.858 | -0.00000 | 0.365 | -0.00000 | -0.6 | -0.4 | 164.6 | 64.7 | 0.1834 | LOOP | 4.00 | 4.9 | 5.2 |
| JANGLE MUTE 1 | 0 | 164.82 | 0.858 | 0.00000 | 0.898 | 0.00001 | -0.6 | -0.1 | 165.2 | 14.5 | 0.1834 | SNARE | 0.39 | 20.0 | 4.4 |
| JANGLE PICK 0 | 0 | 164.82 | 0.682 | 0.00000 | 0.416 | -0.00000 | -0.6 | -0.4 | 164.6 | 64.3 | 0.1834 | LOOP | 2.73 | 6.1 | 5.0 |
| JANGLE PICK 1 | 0 | 164.82 | 0.925 | -0.00000 | 0.401 | 0.00000 | -0.6 | -0.2 | 164.6 | 64.4 | 0.1834 | LOOP | 2.36 | 6.4 | 5.2 |
| JANGLE BLEND 0 | 0 | 164.82 | 1.237 | -0.00000 | 0.375 | 0.00000 | -0.6 | -2.1 | 164.6 | 64.8 | 0.1834 | LOOP | 2.50 | 5.6 | 6.0 |
| JANGLE BLEND 1 | 0 | 164.82 | 1.089 | -0.00000 | 0.406 | -0.00000 | -0.6 | -4.3 | 164.6 | 65.2 | 0.1834 | LOOP | 2.50 | 4.9 | 5.2 |
| JANGLE DRIVE 0 | 0 | 164.82 | 0.858 | -0.00000 | 0.584 | -0.00000 | -0.6 | -0.6 | 164.6 | 66.4 | 0.1834 | LOOP | 2.50 | 6.7 | 5.1 |
| JANGLE DRIVE 1 | 0 | 164.82 | 0.858 | -0.00000 | 0.324 | -0.00000 | -0.6 | -5.9 | 164.6 | 63.5 | 0.1834 | LOOP | 2.50 | 7.5 | 5.3 |
| JANGLE SAG 0 | 0 | 164.82 | 0.858 | -0.00000 | 0.388 | -0.00000 | -0.6 | 0.1 | 164.6 | 65.6 | 0.1834 | LOOP | 2.50 | 6.4 | 5.3 |
| JANGLE SAG 1 | 0 | 164.82 | 0.858 | -0.00000 | 0.432 | -0.00000 | -0.6 | -1.5 | 164.6 | 64.4 | 0.1834 | LOOP | 2.50 | 7.8 | 5.5 |
| JANGLE TONE 0 | 0 | 164.82 | 0.858 | -0.00000 | 0.387 | -0.00000 | -0.6 | -0.3 | 164.6 | 65.1 | 0.1834 | LOOP | 2.50 | 6.4 | 5.2 |
| JANGLE TONE 1 | 0 | 164.82 | 0.858 | -0.00000 | 0.475 | 0.00000 | -0.6 | -0.3 | 164.6 | 65.5 | 0.1834 | LOOP | 2.50 | 6.9 | 5.2 |
| JANGLE CAB 0 | 0 | 164.82 | 0.858 | -0.00000 | 0.420 | 0.00000 | -0.6 | -0.3 | 164.6 | 65.4 | 0.1834 | LOOP | 2.50 | 6.2 | 5.2 |
| JANGLE CAB 1 | 0 | 164.82 | 0.858 | -0.00000 | 0.397 | -0.00000 | -0.6 | -0.3 | 164.6 | 65.0 | 0.1834 | LOOP | 2.50 | 6.8 | 5.1 |
| CHUG DEFAULTS | 0 | 123.48 | 1.025 | -0.00000 | 0.509 | -0.00000 | 0.5 | -2.4 | 123.5 | 63.7 | 0.1834 | LOOP | 1.92 | 5.2 | 5.1 |
| CHUG TUNE 0 | 0 | 61.74 | 1.496 | -0.00000 | 0.374 | 0.00000 | 0.5 | 27.7 | 61.8 | 63.5 | 0.1834 | LOOP | 3.99 | 3.6 | 5.0 |
| CHUG TUNE 1 | 0 | 246.96 | 1.062 | 0.00000 | 0.541 | 0.00000 | 0.4 | 1.3 | 246.4 | 32.8 | 0.1834 | PERC | 0.86 | 9.2 | 5.2 |
| CHUG MUTE 0 | 0 | 123.48 | 1.025 | 0.00000 | 0.434 | 0.00000 | 0.5 | 4.7 | 123.5 | 63.0 | 0.1834 | LOOP | 4.00 | 3.5 | 5.5 |
| CHUG MUTE 1 | 0 | 123.48 | 1.025 | -0.00000 | 0.844 | 0.00000 | 0.5 | -2.3 | 123.5 | 20.9 | 0.1834 | PERC | 0.62 | 12.6 | 4.9 |
| CHUG PICK 0 | 0 | 123.48 | 0.792 | -0.00000 | 0.532 | 0.00000 | 0.5 | -3.2 | 123.5 | 63.5 | 0.1834 | LOOP | 2.04 | 5.0 | 5.3 |
| CHUG PICK 1 | 0 | 123.48 | 1.109 | -0.00000 | 0.497 | 0.00000 | 0.5 | -2.1 | 123.5 | 62.4 | 0.1834 | LOOP | 1.81 | 5.3 | 5.3 |
| CHUG BLEND 0 | 0 | 123.48 | 1.012 | -0.00000 | 0.423 | -0.00000 | 0.5 | 1.2 | 123.5 | 63.8 | 0.1834 | LOOP | 1.92 | 5.1 | 5.3 |
| CHUG BLEND 1 | 0 | 123.48 | 1.025 | -0.00000 | 0.509 | -0.00000 | 0.5 | -2.4 | 123.5 | 63.7 | 0.1834 | LOOP | 1.92 | 5.3 | 5.2 |
| CHUG DRIVE 0 | 0 | 123.48 | 1.025 | -0.00000 | 0.661 | -0.00000 | 0.5 | -1.2 | 123.5 | 62.8 | 0.1834 | LOOP | 1.92 | 5.0 | 5.2 |
| CHUG DRIVE 1 | 0 | 123.48 | 1.025 | -0.00000 | 0.413 | -0.00000 | 0.5 | 5.5 | 123.5 | 61.3 | 0.1834 | LOOP | 1.92 | 5.2 | 5.3 |
| CHUG SAG 0 | 0 | 123.48 | 1.025 | -0.00000 | 0.494 | -0.00000 | 0.5 | -1.3 | 123.5 | 60.8 | 0.1834 | LOOP | 1.92 | 5.0 | 5.1 |
| CHUG SAG 1 | 0 | 123.48 | 1.025 | -0.00000 | 0.547 | -0.00000 | 0.5 | -3.7 | 123.5 | 63.1 | 0.1834 | LOOP | 1.92 | 5.3 | 5.1 |
| CHUG TONE 0 | 0 | 123.48 | 1.025 | -0.00000 | 0.366 | -0.00000 | 0.5 | -2.4 | 123.5 | 62.2 | 0.1834 | LOOP | 1.92 | 5.3 | 5.3 |
| CHUG TONE 1 | 0 | 123.48 | 1.025 | -0.00000 | 0.641 | -0.00000 | 0.5 | -2.4 | 123.5 | 61.6 | 0.1834 | LOOP | 1.92 | 5.1 | 5.1 |
| CHUG CAB 0 | 0 | 123.48 | 1.025 | -0.00000 | 0.580 | -0.00000 | 0.5 | -2.3 | 123.5 | 63.9 | 0.1834 | LOOP | 1.92 | 4.9 | 5.2 |
| CHUG CAB 1 | 0 | 123.48 | 1.025 | -0.00000 | 0.465 | -0.00000 | 0.5 | -2.4 | 123.5 | 62.6 | 0.1834 | LOOP | 1.92 | 5.0 | 5.1 |

Harmonics of the final, dB re h1:

| render | h1 | h2 | h3 | h4 | h5 | h6 | h7 | h8 |
|---|---|---|---|---|---|---|---|---|
| JANGLE DEFAULTS | 0.0 | -10.1 | -4.8 | -7.0 | 4.0 | 5.2 | -12.6 | -5.4 |
| JANGLE TUNE 0 | 0.0 | -16.6 | 2.4 | -6.3 | -1.8 | -8.7 | -16.5 | -15.3 |
| JANGLE TUNE 1 | 0.0 | -2.3 | 8.7 | 5.3 | -3.1 | -9.3 | -20.1 | -27.1 |
| JANGLE MUTE 0 | 0.0 | -9.5 | -4.2 | -7.2 | 4.7 | 6.2 | -7.8 | -4.4 |
| JANGLE MUTE 1 | 0.0 | -14.7 | -10.8 | -17.9 | -10.3 | -15.6 | -43.4 | -34.2 |
| JANGLE PICK 0 | 0.0 | -11.3 | -4.8 | -8.3 | 4.4 | 4.5 | -14.4 | -6.1 |
| JANGLE PICK 1 | 0.0 | -9.8 | -4.9 | -6.6 | 3.8 | 5.4 | -12.2 | -5.4 |
| JANGLE BLEND 0 | 0.0 | -11.4 | -9.5 | -11.4 | -4.0 | 2.6 | -23.1 | -3.1 |
| JANGLE BLEND 1 | 0.0 | -0.3 | 0.4 | -1.9 | 14.0 | 8.7 | -4.9 | -11.5 |
| JANGLE DRIVE 0 | 0.0 | -10.4 | -5.0 | -7.0 | 4.0 | 5.2 | -14.0 | -5.4 |
| JANGLE DRIVE 1 | 0.0 | -9.1 | -4.3 | -8.7 | 4.0 | 5.5 | -5.8 | -5.7 |
| JANGLE SAG 0 | 0.0 | -10.2 | -4.8 | -7.1 | 4.0 | 5.2 | -12.6 | -5.5 |
| JANGLE SAG 1 | 0.0 | -10.1 | -4.8 | -7.0 | 4.0 | 5.3 | -12.5 | -5.4 |
| JANGLE TONE 0 | 0.0 | -7.1 | 0.8 | -3.7 | 5.5 | 5.8 | -12.5 | -5.7 |
| JANGLE TONE 1 | 0.0 | -10.7 | -6.9 | -15.5 | -2.0 | 1.5 | -15.0 | -7.2 |
| JANGLE CAB 0 | 0.0 | -11.9 | -12.3 | -11.1 | 1.6 | 3.4 | -14.1 | -6.8 |
| JANGLE CAB 1 | 0.0 | -8.2 | -2.0 | -6.0 | 4.6 | 5.7 | -12.3 | -5.2 |
| CHUG DEFAULTS | 0.0 | 1.7 | 3.6 | -8.2 | -7.2 | 2.3 | -10.3 | -17.2 |
| CHUG TUNE 0 | 0.0 | 13.2 | 14.1 | 8.6 | 14.6 | 3.8 | -5.4 | -9.4 |
| CHUG TUNE 1 | 0.0 | 18.1 | 30.9 | 15.0 | 3.2 | 11.3 | 5.2 | -14.6 |
| CHUG MUTE 0 | 0.0 | 1.0 | 4.2 | -6.9 | -3.8 | 5.1 | -9.7 | -13.4 |
| CHUG MUTE 1 | 0.0 | -0.6 | -2.2 | -18.6 | -22.5 | -19.0 | -36.4 | -46.0 |
| CHUG PICK 0 | 0.0 | 1.7 | 3.5 | -8.4 | -7.6 | 2.0 | -10.7 | -17.9 |
| CHUG PICK 1 | 0.0 | 1.7 | 3.6 | -8.3 | -7.3 | 2.3 | -10.2 | -17.3 |
| CHUG BLEND 0 | 0.0 | -13.2 | -4.2 | -18.0 | -14.6 | 0.8 | -15.4 | -22.8 |
| CHUG BLEND 1 | 0.0 | 1.7 | 3.6 | -8.2 | -7.2 | 2.3 | -10.3 | -17.2 |
| CHUG DRIVE 0 | 0.0 | 2.0 | 3.7 | -8.1 | -7.4 | 2.4 | -9.7 | -16.9 |
| CHUG DRIVE 1 | 0.0 | 0.8 | 3.4 | -8.7 | -6.3 | 2.4 | -12.9 | -16.0 |
| CHUG SAG 0 | 0.0 | 1.8 | 3.6 | -8.2 | -7.2 | 2.4 | -10.2 | -17.2 |
| CHUG SAG 1 | 0.0 | 1.7 | 3.6 | -8.2 | -7.3 | 2.3 | -10.3 | -17.3 |
| CHUG TONE 0 | 0.0 | 3.1 | 8.0 | -2.3 | -2.9 | 4.8 | -8.8 | -16.4 |
| CHUG TONE 1 | 0.0 | 1.4 | 2.9 | -10.4 | -14.9 | -5.4 | -15.7 | -21.1 |
| CHUG CAB 0 | 0.0 | -1.2 | -0.2 | -17.4 | -13.9 | -2.4 | -14.2 | -20.7 |
| CHUG CAB 1 | 0.0 | 3.4 | 7.5 | -4.4 | -5.0 | 4.1 | -8.8 | -15.8 |


## Grid 2: tuning, 36 cells

Cents error of the measured peak against nominal f0. `dry` uses the default 0.25 s body (2.7 Hz bins at R); `dry wide` a 0.35 s body; `P1 final` is at 44.1 kHz through the amp at default DRIVE/SAG/TONE/CAB.

| voice | TUNE | MUTE | BLEND | f0 Hz | dry cents | dry wide cents | P1 final cents | dry dur s |
|---|---|---|---|---|---|---|---|---|
| JANGLE | 0.0 | 0.0 | 0 | 82.41 | 1.11 | 2.30 | 4.55 | 4.00 |
| JANGLE | 0.0 | 0.0 | 1 | 82.41 | 1.11 | 2.30 | 0.56 | 4.00 |
| JANGLE | 0.0 | 0.5 | 0 | 82.41 | 1.11 | 2.30 | 2.74 | 2.05 |
| JANGLE | 0.0 | 0.5 | 1 | 82.41 | 1.11 | 2.30 | 37.13 | 2.05 |
| JANGLE | 0.0 | 1.0 | 0 | 82.41 | 1.11 | 2.29 | -1.03 | 0.94 |
| JANGLE | 0.0 | 1.0 | 1 | 82.41 | 1.11 | 2.29 | 13.76 | 0.94 |
| JANGLE | 0.5 | 0.0 | 0 | 164.82 | -0.61 | -1.21 | -0.89 | 4.00 |
| JANGLE | 0.5 | 0.0 | 1 | 164.82 | -0.61 | -1.21 | -3.13 | 4.00 |
| JANGLE | 0.5 | 0.5 | 0 | 164.82 | -0.61 | -1.21 | -1.64 | 0.90 |
| JANGLE | 0.5 | 0.5 | 1 | 164.82 | -0.61 | -1.21 | -5.98 | 0.90 |
| JANGLE | 0.5 | 1.0 | 0 | 164.82 | -0.62 | -1.13 | -0.04 | 0.39 |
| JANGLE | 0.5 | 1.0 | 1 | 164.82 | -0.62 | -1.13 | -0.88 | 0.39 |
| JANGLE | 1.0 | 0.0 | 0 | 329.64 | -0.11 | -0.23 | 0.20 | 4.00 |
| JANGLE | 1.0 | 0.0 | 1 | 329.64 | -0.11 | -0.23 | 7.12 | 4.00 |
| JANGLE | 1.0 | 0.5 | 0 | 329.64 | -0.11 | -0.23 | 0.26 | 0.42 |
| JANGLE | 1.0 | 0.5 | 1 | 329.64 | -0.11 | -0.23 | 1.90 | 0.42 |
| JANGLE | 1.0 | 1.0 | 0 | 329.64 | -0.13 | -0.13 | -0.05 | 0.26 |
| JANGLE | 1.0 | 1.0 | 1 | 329.64 | -0.13 | -0.13 | -0.08 | 0.26 |
| CHUG | 0.0 | 0.0 | 0 | 61.74 | 0.53 | 1.04 | -0.88 | 4.00 |
| CHUG | 0.0 | 0.0 | 1 | 61.74 | 0.53 | 1.04 | 11.40 | 4.00 |
| CHUG | 0.0 | 0.5 | 0 | 61.74 | 0.53 | 1.04 | -4.17 | 2.87 |
| CHUG | 0.0 | 0.5 | 1 | 61.74 | 0.51 | 1.03 | 35.29 | 2.87 |
| CHUG | 0.0 | 1.0 | 0 | 61.74 | 0.52 | 1.03 | 2.26 | 1.33 |
| CHUG | 0.0 | 1.0 | 1 | 61.74 | 0.49 | 1.03 | -10.24 | 1.33 |
| CHUG | 0.5 | 0.0 | 0 | 123.48 | 0.51 | 1.00 | 0.66 | 4.00 |
| CHUG | 0.5 | 0.0 | 1 | 123.48 | 0.51 | 1.00 | 4.66 | 4.00 |
| CHUG | 0.5 | 0.5 | 0 | 123.48 | 0.51 | 1.00 | 1.39 | 1.38 |
| CHUG | 0.5 | 0.5 | 1 | 123.48 | 0.51 | 1.00 | -4.28 | 1.38 |
| CHUG | 0.5 | 1.0 | 0 | 123.48 | 0.50 | 0.98 | 0.12 | 0.62 |
| CHUG | 0.5 | 1.0 | 1 | 123.48 | 0.50 | 0.98 | -2.28 | 0.62 |
| CHUG | 1.0 | 0.0 | 0 | 246.96 | 0.42 | 0.83 | -0.33 | 4.00 |
| CHUG | 1.0 | 0.0 | 1 | 246.96 | 0.42 | 0.83 | -0.48 | 4.00 |
| CHUG | 1.0 | 0.5 | 0 | 246.96 | 0.42 | 0.83 | 8.89 | 0.63 |
| CHUG | 1.0 | 0.5 | 1 | 246.96 | 0.42 | 0.83 | 28.23 | 0.63 |
| CHUG | 1.0 | 1.0 | 0 | 246.96 | 0.24 | 0.24 | -0.01 | 0.26 |
| CHUG | 1.0 | 1.0 | 1 | 246.96 | 0.24 | 0.24 | 0.05 | 0.26 |

Dry cents range: -0.62 .. 1.11. P1 final range: -10.24 .. 37.13.


## Grid 3: the comb

### (a) JANGLE single coil, resonance off (20 kHz, k 1), TUNE 0.5, f0 164.82 Hz, dry at R

| case | h1 | h2 | h3 | h4 | h5 | h6 | h7 | h8 |
|---|---|---|---|---|---|---|---|---|
| JANGLE single p=0.5 | 0.0 | -52.1 | 1.6 | -52.4 | 11.1 | -39.4 | -2.8 | -39.9 |
| JANGLE single p=0.25 | 0.0 | 1.6 | 1.6 | -45.8 | 11.1 | 10.5 | -2.9 | -31.6 |

- p=0.5, even harmonics: h2 -53.7 dB under louder neighbour, -52.1 under quieter; h4 -63.5 dB under louder neighbour, -54.0 under quieter; h6 -50.5 dB under louder neighbour, -36.6 under quieter; h8 -39.1 dB under louder neighbour, -37.2 under quieter
- p=0.25, h4 and h8: h4 -57.0 dB under louder neighbour, -47.4 under quieter; h8 -30.8 dB under louder neighbour, -28.7 under quieter
- p=0.25, h2 and h6 for contrast (should not notch): h2 -0.0 dB under louder neighbour, 1.6 under quieter; h6 -0.7 dB under louder neighbour, 13.3 under quieter

### (b) CHUG at p=0.12, resonance off, TUNE 0.5, f0 123.48 Hz, dp 0.0556 (second coil at p+dp. My first guess of an extra notch at k = 0.5/dp = 8.99 was wrong: h9 is not notched, see Extra C)

| case | h1 | h2 | h3 | h4 | h5 | h6 | h7 | h8 | h9 | h10 | h11 | h12 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| single coil p=0.12 | 0.0 | 6.1 | 13.9 | 5.4 | 7.6 | 18.7 | 3.6 | -18.0 | -14.4 | -40.9 | 3.6 | -4.3 |
| humbucker p=0.12 | 0.0 | 5.6 | 12.4 | 2.3 | 2.1 | 10.2 | -1.2 | -8.7 | -9.7 | -45.7 | -4.7 | -10.3 |

- humbucker, h9: h9 -1.0 dB under louder neighbour, 35.9 under quieter
- single coil, h9 (control): h9 3.7 dB under louder neighbour, 26.5 under quieter

### (c) JANGLE BLEND, neck p=0.42 / bridge p=0.12, with the 4500 Hz k 0.40 resonance, dry at R

| case | h1 | h2 | h3 | h4 | h5 | h6 | h7 | h8 | h9 | h10 | h11 | h12 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JANGLE BLEND 0.0 | 0.0 | -7.5 | -0.7 | -4.5 | 1.6 | 8.1 | -17.0 | 5.1 | -3.8 | -47.3 | 1.6 | -28.3 |
| JANGLE BLEND 0.5 | 0.0 | -6.3 | 4.2 | -0.2 | 9.7 | 10.7 | -7.6 | 2.8 | -7.5 | -43.4 | 4.1 | -13.1 |
| JANGLE BLEND 1.0 | 0.0 | 4.0 | 9.5 | 5.4 | 19.6 | 14.4 | 0.2 | -3.1 | -3.8 | -39.2 | 9.2 | -3.1 |


## Grid 4: PICK sweep

Power-weighted spectral centroid of the dry 44.1 kHz render (string + pickup + chain, no amp), 0.05..0.3 s.

### JANGLE

| PICK | exciter corner Hz | centroid Hz | dry peak |
|---|---|---|---|
| 0.0 | 3316 | 1459.4 | 0.894 |
| 0.1 | 3860 | 1492.1 | 0.886 |
| 0.2 | 4494 | 1520.3 | 0.876 |
| 0.3 | 5231 | 1544.2 | 0.864 |
| 0.4 | 6090 | 1563.9 | 0.874 |
| 0.5 | 7089 | 1579.7 | 0.895 |
| 0.6 | 8253 | 1592.2 | 0.908 |
| 0.7 | 9608 | 1601.9 | 0.916 |
| 0.8 | 11185 | 1609.2 | 0.918 |
| 0.9 | 13021 | 1614.5 | 0.917 |
| 1.0 | 15158 | 1618.4 | 0.913 |

Monotonic non-decreasing: true (steps that fall: 0, smallest step 3.8 Hz).

### CHUG

| PICK | exciter corner Hz | centroid Hz | dry peak |
|---|---|---|---|
| 0.0 | 2211 | 719.3 | 0.935 |
| 0.1 | 2573 | 741.2 | 0.955 |
| 0.2 | 2996 | 760.8 | 0.971 |
| 0.3 | 3487 | 777.8 | 0.990 |
| 0.4 | 4060 | 792.2 | 0.990 |
| 0.5 | 4726 | 804.1 | 0.990 |
| 0.6 | 5502 | 813.7 | 0.990 |
| 0.7 | 6405 | 821.3 | 0.990 |
| 0.8 | 7457 | 827.1 | 0.990 |
| 0.9 | 8680 | 831.7 | 0.990 |
| 1.0 | 10105 | 835.0 | 0.990 |

Monotonic non-decreasing: true (steps that fall: 0, smallest step 3.4 Hz).


## Grid 5: placements

P1 = amp at R inside the render (raw string level into the amp), P1n = P1 with the string buffer's peak pre-scaled to the dry snip's peak (so the amp sees the same level as P2/P3), P2 = rack pass at 44.1 kHz, P3 = rack pass 4x oversampled. clarity(nominal) is harmonicClarity at the nominal f0 from 0.05 s; clarity(own) uses the render's own measured f0. centroid is over 0.05..0.3 s. cents are of the measured peak vs nominal f0. amp ms/s is the amp alone per rendered second (min of 3 runs); path ms/s is amp plus whatever else that placement adds after the dry render (P1: output chain; P3: up-sample, band-limit, decimate).

| case | placement | dur s | clarity nominal dB | clarity own dB | centroid Hz | DC mean | cents | detect Hz | loudness | peak | amp ms/s | path ms/s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CHUG T0.5 D1.0 | dry | - | 77.7 | 77.7 | 809 | -0.00000 | -0.0 | 123.5 | 0.1731 | 0.990 | - | - |
| CHUG T0.5 D1.0 | P1 | 1.92 | 61.3 | 28.3 | 1336 | -0.00000 | 5.5 | 123.5 | 0.1834 | 0.413 | 5.3 | 13.8 |
| CHUG T0.5 D1.0 | P1n | 1.92 | 59.8 | 28.8 | 1328 | -0.00000 | 5.3 | 123.5 | 0.1834 | 0.414 | 5.3 | 13.7 |
| CHUG T0.5 D1.0 | P2 | 1.92 | 67.4 | 34.3 | 1289 | -0.00000 | 4.3 | 123.5 | 0.4266 | 0.990 | 1.4 | 1.4 |
| CHUG T0.5 D1.0 | P3 | 1.92 | 60.6 | 34.4 | 1283 | 0.00000 | 4.3 | 123.5 | 0.4264 | 0.990 | 5.3 | 84.2 |
| CHUG T1.0 D1.0 | dry | - | 32.5 | 32.5 | 844 | -0.00000 | -0.0 | 246.4 | 0.1759 | 0.990 | - | - |
| CHUG T1.0 D1.0 | P1 | 0.86 | 32.0 | 32.1 | 1042 | 0.00000 | -1.8 | 246.4 | 0.1834 | 0.429 | 5.4 | 13.9 |
| CHUG T1.0 D1.0 | P1n | 0.86 | 32.0 | 32.0 | 1030 | 0.00000 | -1.6 | 246.4 | 0.1834 | 0.432 | 5.4 | 14.0 |
| CHUG T1.0 D1.0 | P2 | 0.86 | 32.0 | 32.1 | 1033 | -0.00000 | -1.9 | 246.4 | 0.4455 | 0.990 | 1.4 | 1.4 |
| CHUG T1.0 D1.0 | P3 | 0.86 | 32.0 | 32.1 | 1032 | 0.00000 | -1.9 | 246.4 | 0.4429 | 0.990 | 5.4 | 85.9 |
| JANGLE T0.5 D0.5 | dry | - | 78.1 | 78.1 | 1592 | -0.00000 | 0.0 | 164.6 | 0.1834 | 0.908 | - | - |
| JANGLE T0.5 D0.5 | P1 | 2.50 | 65.2 | 65.2 | 1891 | -0.00000 | 0.0 | 164.6 | 0.1834 | 0.387 | 5.1 | 13.6 |
| JANGLE T0.5 D0.5 | P1n | 2.50 | 65.9 | 65.9 | 1896 | -0.00000 | 0.1 | 164.6 | 0.1834 | 0.382 | 5.1 | 13.6 |
| JANGLE T0.5 D0.5 | P2 | 2.50 | 77.4 | 77.5 | 1902 | -0.00000 | 0.3 | 164.6 | 0.4486 | 0.908 | 1.4 | 1.4 |
| JANGLE T0.5 D0.5 | P3 | 2.50 | 64.3 | 64.3 | 1898 | -0.00000 | 0.3 | 164.6 | 0.4437 | 0.908 | 5.1 | 85.5 |


## Grid 6: CRUNCH-rule identity

Thump KICK and SNARE (default macros) through P3's amp at DRIVE 0.6, SAG 0.35, TONE 0.5, CAB 0.6, peak-matched to the dry peak. Centroid over 0..0.3 s.

| voice | state | class | peak | centroid Hz | dur s | rms |
|---|---|---|---|---|---|---|
| KICK | dry | KICK | 0.994 | 42 | 0.35 | 0.2575 |
| KICK | amp (P3, DRIVE 0.6) | KICK | 0.994 | 43 | 0.35 | 0.2962 |
| SNARE | dry | SNARE | 0.931 | 11568 | 0.33 | 0.0805 |
| SNARE | amp (P3, DRIVE 0.6) | SNARE | 0.931 | 6598 | 0.33 | 0.1666 |

Near-transparent point (DRIVE 0, SAG 0, TONE 0.5, CAB 0) on the snare, P3, peak-matched: RMS of (out - dry) is 2.8 dB re dry RMS; after an RMS-matching gain of 0.708: 0.9 dB. Class after: SNARE, centroid 6633 Hz.


## Grid 7: cost of the string

String (pluck + trim) and pickup at defaults at R = 176.4 kHz, min of 5 runs after 3 warm-ups. ms/s is milliseconds per rendered second of the trimmed buffer.

| voice | rendered s | string ms | pickup ms | string ms/s | pickup ms/s | total ms/s |
|---|---|---|---|---|---|---|
| JANGLE | 2.51 | 8.2 | 7.3 | 3.3 | 2.9 | 6.2 |
| CHUG | 1.93 | 6.4 | 3.0 | 3.3 | 1.5 | 4.9 |


## Extra A: aliasing, measured where harmonicClarity is not confounded

Steady input: harmonics of f0 to ~5 kHz at 1/k, peak 0.99, 1.6 s, 44.1 kHz; CHUG-law amp with default SAG/TONE/CAB. clarity from 0.3 s at the exact f0. `err vs 8x` is the residual against the 8x render, dB re its energy over 0.3..1.2 s (includes the small filter-warp difference, so read it as a ranking).

| f0 Hz | DRIVE | amp rate | clarity dB | err vs 8x dB |
|---|---|---|---|---|
| 123.48 | 1.00 | 1x (P2) | 38.5 | -18.9 |
| 123.48 | 1.00 | 4x (P3) | 51.0 | -20.0 |
| 123.48 | 1.00 | 8x | 50.5 | -Infinity |
| 123.48 | 0.45 | 1x (P2) | 46.8 | -19.1 |
| 123.48 | 0.45 | 4x (P3) | 51.3 | -20.0 |
| 123.48 | 0.45 | 8x | 50.4 | -Infinity |
| 246.96 | 1.00 | 1x (P2) | 31.9 | -12.9 |
| 246.96 | 1.00 | 4x (P3) | 49.4 | -30.7 |
| 246.96 | 1.00 | 8x | 50.9 | -Infinity |
| 164.82 | 0.50 | 1x (P2) | 42.4 | -17.2 |
| 164.82 | 0.50 | 4x (P3) | 50.8 | -24.5 |
| 164.82 | 0.50 | 8x | 49.6 | -Infinity |

String renders (the grid-5 cases): the dry 44.1 kHz snip through the amp at 1x, 4x, 8x; residual against 8x over 0.05..0.3 s, and clarity at the nominal f0 (no peak match, so levels are the amp's own).

| case | amp rate | err vs 8x dB | clarity dB |
|---|---|---|---|
| CHUG T0.5 D1.0 | 1x (P2) | -5.4 | 67.4 |
| CHUG T0.5 D1.0 | 4x (P3) | -27.8 | 60.6 |
| CHUG T0.5 D1.0 | 8x | -Infinity | 53.1 |
| CHUG T1.0 D1.0 | 1x (P2) | -7.5 | 32.0 |
| CHUG T1.0 D1.0 | 4x (P3) | -32.2 | 32.0 |
| CHUG T1.0 D1.0 | 8x | -Infinity | 32.0 |
| JANGLE T0.5 D0.5 | 1x (P2) | -2.4 | 77.4 |
| JANGLE T0.5 D0.5 | 4x (P3) | -27.2 | 64.3 |
| JANGLE T0.5 D0.5 | 8x | -Infinity | 55.5 |

P3 cost breakdown, CHUG T0.5 DRIVE 1 (1.92 s), ms per rendered second, min of 3: up-sample 72.2, amp at 4x 5.3, band-limit + decimate 8.0; the amp at 1x is 1.3.


## Extra B: is grid 2's large P1-final cents a pitch shift or a measurement artefact?

The same cells measured with the analysis window starting at 0.02, 0.05 and 0.10 s (`FineTuning.measuredHz`, cents vs nominal), and `Pitch.detect`'s autocorrelation Hz. A real pitch shift would not move with the window; an artefact of a spectral peak read off a decaying, amp-coloured line would.

| cell | signal | cents @0.05 | cents @0.10 | cents @0.02 | Pitch.detect Hz | nominal Hz |
|---|---|---|---|---|---|---|
| JANGLE T0.0 M0.5 B1 | dry 44.1k | -0.0 | -0.0 | -0.0 | 82.43 | 82.41 |
| JANGLE T0.0 M0.5 B1 | P1 final | 37.1 | 15.9 | 43.8 | 82.43 | 82.41 |
| CHUG T0.0 M0.5 B1 | dry 44.1k | -0.0 | -0.0 | 0.0 | 61.76 | 61.74 |
| CHUG T0.0 M0.5 B1 | P1 final | 35.3 | 25.9 | 36.8 | 61.76 | 61.74 |
| CHUG T1.0 M0.5 B1 | dry 44.1k | -0.0 | -0.0 | -0.0 | 246.37 | 246.96 |
| CHUG T1.0 M0.5 B1 | P1 final | 28.2 | 14.8 | 17.9 | 246.37 | 246.96 |
| JANGLE T0.5 M0.5 B0 | dry 44.1k | 0.0 | 0.0 | 0.0 | 165.17 | 164.82 |
| JANGLE T0.5 M0.5 B0 | P1 final | -1.6 | -0.7 | -2.3 | 164.55 | 164.82 |


## Extra C: where the humbucker's second coil notches (CHUG, p=0.12, resonance off, f0 123.48 Hz, dp 0.0556, D1 171, D2 251)

Three humbuckers on the same string: as briefed (`0.707 * (c(p) + c(p+dp))`, each comb `y[n] - y[n-D]`), and phase-aligned (each comb's taps delayed so its centre is the longer comb's centre). The aligned pair is `sin(pi k p1) + sin(pi k p2)`, whose first spacing notch is at k = 1/dp = 17.99; as briefed the two combs' linear-phase terms differ and there is no such notch. The exciter's own 0.10 comb notches h10 in every row. `d` columns are humbucker minus single coil, both re h1: measured, then theory from the delays as rounded.

| harmonic | single dB | hum briefed dB | hum aligned dB | briefed d meas | briefed d theory | aligned d meas | aligned d theory |
|---|---|---|---|---|---|---|---|
| h1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| h2 | 6.1 | 5.6 | 5.7 | -0.5 | -0.5 | -0.4 | -0.4 |
| h3 | 13.9 | 12.4 | 12.7 | -1.5 | -1.5 | -1.2 | -1.2 |
| h4 | 5.4 | 2.3 | 2.8 | -3.1 | -3.1 | -2.6 | -2.6 |
| h5 | 7.6 | 2.1 | 2.8 | -5.5 | -5.5 | -4.8 | -4.8 |
| h6 | 18.7 | 10.2 | 8.9 | -8.5 | -8.5 | -9.8 | -9.9 |
| h7 | 3.6 | -1.2 | -13.1 | -4.8 | -4.8 | -16.7 | -16.7 |
| h8 | -18.0 | -8.7 | -9.9 | 9.4 | 9.4 | 8.2 | 8.1 |
| h9 | -14.4 | -9.7 | -8.0 | 4.6 | 4.7 | 6.3 | 6.3 |
| h10 | -40.9 | -45.7 | -41.8 | -4.8 | -4.7 | -0.9 | -0.9 |
| h11 | 3.6 | -4.7 | -2.2 | -8.3 | -8.2 | -5.8 | -5.8 |
| h12 | -4.3 | -10.3 | -15.6 | -6.0 | -6.0 | -11.3 | -11.3 |
| h13 | 3.0 | -0.4 | -18.3 | -3.4 | -3.4 | -21.3 | -21.3 |
| h14 | 3.8 | 2.3 | -19.5 | -1.5 | -1.5 | -23.3 | -23.3 |
| h15 | -6.9 | -6.8 | -20.3 | 0.1 | 0.1 | -13.4 | -13.4 |
| h16 | -11.4 | -9.2 | -18.0 | 2.1 | 2.1 | -6.7 | -6.7 |
| h17 | -23.2 | -34.8 | -28.2 | -11.7 | -11.7 | -5.0 | -4.9 |
| h18 | -15.4 | -16.8 | -49.4 | -1.4 | -1.4 | -34.1 | -34.2 |
| h19 | -13.4 | -14.4 | -37.5 | -1.1 | -1.1 | -24.1 | -24.1 |
| h20 | -93.0 | -94.7 | -115.8 | -1.7 | -1.6 | -22.8 | -32.6 |
| h21 | -16.1 | -18.9 | -39.0 | -2.8 | -2.8 | -22.9 | -23.0 |
| h22 | -9.4 | -14.4 | -22.2 | -5.0 | -4.9 | -12.8 | -12.9 |


## 3. Other observations

- **Raw string level is not constant.** The dry buffer's peak at 176.4 kHz spans 0.68 (PICK 0) to 1.50 (CHUG TUNE 0) across the grid-1 corners, and the amp's gain law is level-dependent, so an in-render amp needs a level stage in front of it (grid 5's P1n, which peak-scales the string to the dry snip's peak, reads within 1.5 dB clarity of P1 at the three tested cases, so there it does not matter much). P2 and P3 get a loudness-levelled input for free.
- **Peak-matching is the wrong rack level rule.** P2 and P3, peak-matched to the dry peak, land at loudness 0.426 to 0.449 against the melodic target 0.1834 (about 2.4x, +7.7 dB); P1, through the output chain, lands exactly on 0.1834 with peaks between 0.32 and 0.90. A rack section would want loudness-matching, not peak-matching.
- **DRIVE 0 is not a bypass.** The gain law's floor is g = 2, the tone stack and cabinet are always in circuit, and the snare's centroid falls from 11568 to 6598 Hz at DRIVE 0.6 (6633 Hz at the "transparent" point). Class survives; identity does not.
- **The exciter's own 0.10 position comb** (used as briefed) puts a 40 to 47 dB hole at h10 (and h20) in every render (grid 3 tables, h10 column). It is audible as a fixed spectral notch regardless of BLEND; worth deciding whether it belongs.
- **Cost.** String+pickup at R is 6.2 (JANGLE) and 4.9 (CHUG) ms per rendered second (grid 7); the output chain is about 8.5 ms/s (path 13.8 minus amp 5.3). P3's 85 ms/s is 72.2 up-sample + 5.3 amp + 8.0 band-limit and decimate; a much cheaper up-sampler than `Resampler.resample` would leave P3 at about 13 ms/s plus its own cost, the same order as P1.
- **Drum-classifier labels** on the guitar renders are mostly LOOP; short notes (JANGLE TUNE 1 and MUTE 1, CHUG TUNE 1 and MUTE 1) read SNARE or PERC. Irrelevant for a melodic engine, recorded because the brief asked for the column.
- **PICK and dry peak** are not monotonic (JANGLE 0.894 falling to 0.864 then rising), but every render is loudness-levelled afterwards so this does not reach the output.
- **Not verified:** run-to-run determinism was not diffed; the seeds are fixed (`Dsp.seedFor("COILSPIKE", voice)`), so it should hold.

## 4. Appendix: both files verbatim

### CoilSpike.kt

```kotlin

package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * MAGNET Phase 0, part B: a THROWAWAY spike, never shipped. An electric guitar
 * built from the shared string toolkit ([Strings.pluck] into a Jaffe-Smith
 * pickup comb, a pickup resonance, then CHUG's amp law), rendered three ways to
 * find where the amp stage (the future VALVE rack section) should live.
 *
 * Every constant below marked "shape" is a listening guess, not physics, used
 * as the brief gave it: the voice table, the 0.0278 coil-spacing ratio, the
 * neck (0.42) and bridge (0.12) pickup positions, the humbucker's 0.707, the
 * tone stack's corners and gains, the cabinet's two bandpass taps and the amp's
 * sag constants.
 */
internal object CoilSpike {

    /** The render rate for the in-render placement: 176 400 Hz. */
    val R: Int = Dsp.RATE * Dsp.OVERSAMPLE

    /** Neck and bridge pickup positions as a fraction of the open string (shape). */
    const val NECK = 0.42f
    const val BRIDGE = 0.12f

    /** Both voices' string position comb at the exciter (shape). */
    const val PICK_POSITION = 0.10f

    /** Humbucker coil spacing over an open string: 18 mm / 648 mm (shape). */
    const val COIL_SPACING = 0.0278f

    /** Every value here is shape: root, coils, string body, pick corner, pickup resonance, BLEND default. */
    enum class Voice(
        val rootHz: Float,
        val humbucker: Boolean,
        val bodyLoopHz: Float,
        val pickHz: Float,
        val resHz: Float,
        val resK: Float,
        val blendDefault: Float,
        val muteDefault: Float,
        val pickDefault: Float,
    ) {
        JANGLE(82.41f, false, 7000f, 9000f, 4500f, 0.40f, 0.5f, 0.15f, 0.6f),
        CHUG(61.74f, true, 5500f, 6000f, 2800f, 0.55f, 1.0f, 0.35f, 0.55f),
    }

    /** The eight macros, 0..1: four for the engine, four for the amp. */
    data class Macros(
        val tune: Float = 0.5f,
        val mute: Float = 0.15f,
        val pick: Float = 0.6f,
        val blend: Float = 0.5f,
        val drive: Float = 0.45f,
        val sag: Float = 0.35f,
        val tone: Float = 0.5f,
        val cab: Float = 0.6f,
    )

    val MACRO_NAMES = listOf("TUNE", "MUTE", "PICK", "BLEND", "DRIVE", "SAG", "TONE", "CAB")

    fun defaults(v: Voice) = Macros(mute = v.muteDefault, pick = v.pickDefault, blend = v.blendDefault)

    fun set(m: Macros, name: String, value: Float): Macros = when (name) {
        "TUNE" -> m.copy(tune = value)
        "MUTE" -> m.copy(mute = value)
        "PICK" -> m.copy(pick = value)
        "BLEND" -> m.copy(blend = value)
        "DRIVE" -> m.copy(drive = value)
        "SAG" -> m.copy(sag = value)
        "TONE" -> m.copy(tone = value)
        "CAB" -> m.copy(cab = value)
        else -> error("unknown macro $name")
    }

    /** TUNE quantises to semitones over two octaves above the root: `root * 2^(round(tune*24)/12)`. */
    fun f0(v: Voice, tune: Float): Float =
        (v.rootHz * 2.0.pow(Math.round(tune * 24f) / 12.0)).toFloat()

    /** The exciter's low-pass corner: PICK scales it, and at the default it is close to `pickHz`. */
    fun pickCornerHz(v: Voice, pick: Float): Float = v.pickHz * Dsp.expMap(pick, 0.35f, 1.6f) / 0.95f

    // ------------------------------------------------------------------ string

    fun string(v: Voice, m: Macros): FloatArray {
        val f0 = f0(v, m.tune)
        val raw = Strings.pluck(
            f0, 4.0f, Strings.damping(m.mute, v.bodyLoopHz), pickCornerHz(v, m.pick),
            Dsp.seedFor("COILSPIKE", v.name), R, position = PICK_POSITION,
        )
        return Strings.trimToDecay(raw, R, 0.25f, 4.0f)
    }

    // ------------------------------------------------------------------ pickup

    /** The comb's delay in samples: `round(p * rate / f0)`, at least 1. */
    fun combDelay(p: Float, f0: Float, rate: Int): Int = (p * rate / f0).roundToInt().coerceAtLeast(1)

    /**
     * Jaffe-Smith position comb, `c(y, p)[n] = y[n] - y[n - D]`, `D = round(p * rate / f0)`:
     * the string's physical period `rate / f0`, not the loop length. [lead] delays
     * both taps by that many samples (0 for the brief's comb as written).
     */
    fun comb(y: FloatArray, p: Float, f0: Float, rate: Int, lead: Int = 0): FloatArray {
        val d = combDelay(p, f0, rate)
        val out = FloatArray(y.size)
        for (n in y.indices) {
            val a = n - lead
            val b = n - lead - d
            out[n] = (if (a >= 0) y[a] else 0f) - (if (b >= 0) y[b] else 0f)
        }
        return out
    }

    /**
     * One pickup at position [p]: a single coil is the comb, a humbucker is
     * `0.707 * (c(y, p) + c(y, p + dp))`; then the pickup's resonance, a
     * [Dsp.TptSvf] low output at [resHz] and [k].
     *
     * [aligned] (default off, the brief's model as written) matters only to a
     * humbucker: each comb `1 - z^-D` carries a linear-phase term `z^(-D/2)`,
     * and two combs of different D summed as written do not have the same
     * phase, so their sum is not `sin(pi k p1) + sin(pi k p2)` and has no
     * coil-spacing notch. Aligned, each comb's taps are delayed so that its
     * centre sits at the longer comb's centre (a lead of `(Dmax - D) / 2`).
     */
    internal fun pickup(y: FloatArray, p: Float, humbucker: Boolean, dp: Float, resHz: Float, k: Float, f0: Float, rate: Int, aligned: Boolean = false): FloatArray {
        val combed = if (humbucker) {
            val d1 = combDelay(p, f0, rate)
            val d2 = combDelay(p + dp, f0, rate)
            val dMax = maxOf(d1, d2)
            val c1 = comb(y, p, f0, rate, if (aligned) (dMax - d1) / 2 else 0)
            val c2 = comb(y, p + dp, f0, rate, if (aligned) (dMax - d2) / 2 else 0)
            FloatArray(y.size) { 0.707f * (c1[it] + c2[it]) }
        } else comb(y, p, f0, rate)
        val svf = Dsp.TptSvf(rate)
        val out = FloatArray(y.size)
        for (i in combed.indices) {
            svf.process(combed[i], resHz, k)
            out[i] = svf.low
        }
        return out
    }

    /**
     * BLEND between the neck and bridge pickups: 0 is neck alone, 1 is bridge
     * alone, otherwise `(1 - BLEND) * neck + BLEND * bridge`. Each pickup is
     * a full [pickup] (comb then resonance); the resonance is linear, so
     * this is the same as summing the combs and resonating once.
     */
    fun voicePickup(y: FloatArray, v: Voice, m: Macros, f0: Float, resHz: Float = v.resHz, k: Float = v.resK): FloatArray {
        val dp = COIL_SPACING * (f0 / v.rootHz)
        val b = m.blend
        return when {
            b <= 0f -> pickup(y, NECK, v.humbucker, dp, resHz, k, f0, R)
            b >= 1f -> pickup(y, BRIDGE, v.humbucker, dp, resHz, k, f0, R)
            else -> {
                val neck = pickup(y, NECK, v.humbucker, dp, resHz, k, f0, R)
                val bridge = pickup(y, BRIDGE, v.humbucker, dp, resHz, k, f0, R)
                FloatArray(y.size) { (1f - b) * neck[it] + b * bridge[it] }
            }
        }
    }

    // ------------------------------------------------------------------ amp

    /**
     * CHUG's gain law for both voices (`g = expMap(DRIVE, 2, 35)`), at any
     * [rate]: gain, grid sag, asymmetric transfer, DC blocker, tone stack,
     * cabinet. The two cabinet bandpass taps read the tone stack's output
     * and are summed with the thump/notch cascade before the final
     * one-pole low-pass (the brief's "in parallel" read that way).
     */
    fun amp(buf: FloatArray, drive: Float, sag: Float, tone: Float, cab: Float, rate: Int): FloatArray {
        val g = Dsp.expMap(drive, 2f, 35f)
        val chargeA = 1.0 - exp(-1.0 / (0.005 * rate))
        val dischargeA = 1.0 - exp(-1.0 / (0.120 * rate))
        var vSag = 0f

        val dc = Dsp.OnePole(rate)
        val lowShelf = Dsp.Biquad().apply { lowShelf(120f, 3f, rate) }
        val mid = Dsp.Biquad().apply { peaking(Dsp.lin(tone, 380f, 650f), Dsp.lin(1f - tone, -12f, 2f), 1.4f, rate) }
        val highShelf = Dsp.Biquad().apply { highShelf(Dsp.expMap(tone, 2500f, 6000f), (tone - 0.5f) * 8f, rate) }
        val thump = Dsp.Biquad().apply { peaking(Dsp.lin(cab, 110f, 78f), 6f, Dsp.lin(cab, 1.6f, 2.4f), rate) }
        val notch = Dsp.Biquad().apply { peaking(Dsp.lin(1f - cab, 380f, 500f), (1f - cab) * -9f, 2.0f, rate) }
        val bp1 = Dsp.Biquad().apply { bandpass(2600f, 3.5f, rate) }
        val bp2 = Dsp.Biquad().apply { bandpass(3750f, 4.0f, rate) }
        val cabLp = Dsp.OnePole(rate)
        val cabHz = Dsp.expMap(1f - cab * 0.3f, 4500f, 5800f)

        val out = FloatArray(buf.size)
        for (i in buf.indices) {
            val x = buf[i] * g
            val ax = abs(x)
            vSag += if (ax > 1f) ((ax - 1f - vSag) * chargeA).toFloat() else ((0f - vSag) * dischargeA).toFloat()
            val b = x - vSag * sag * 0.45f
            val y0 = if (b >= 0f) tanh(b.toDouble()).toFloat() else b / sqrt(1f + b * b)
            val y = y0 - dc.lp(y0, 20f)
            val t = highShelf.process(mid.process(lowShelf.process(y)))
            val cascade = notch.process(thump.process(t))
            val sum = cascade + bp1.process(t) * 0.35f + bp2.process(t) * 0.25f
            out[i] = cabLp.lp(sum, cabHz)
        }
        return out
    }

    // ------------------------------------------------------------------ output chain

    /** The melodic fleet's chain, from a buffer at [R]: band-limit, decimate, mean, 20 Hz high-pass, level, tail fade. */
    fun outputChain(rBuf: FloatArray): Snip {
        val b = rBuf.copyOf()
        Tide.bandLimit(b, R)
        val out = Dsp.decimate(b, Dsp.RATE)
        var mean = 0.0
        for (v in out) mean += v
        mean /= out.size.coerceAtLeast(1)
        for (i in out.indices) out[i] = (out[i] - mean).toFloat()
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) {
            val x = out[i]
            out[i] = x - hp.lp(x, 20f)
        }
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }

    private fun peakOf(a: FloatArray): Float {
        var p = 0f
        for (v in a) { val x = abs(v); if (x > p) p = x }
        return p
    }

    private fun peakMatch(out: FloatArray, target: Float) {
        val p = peakOf(out)
        if (p > 0f && target > 0f) {
            val k = target / p
            for (i in out.indices) out[i] *= k
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1e6
    }

    // ------------------------------------------------------------------ renders and placements

    /** The string and pickup, at [R] ([buf]) and as the melodic chain's 44.1 kHz [snip]. */
    class Dry(val buf: FloatArray, val snip: Snip, val stringMs: Double, val pickupMs: Double) {
        val seconds: Double get() = buf.size.toDouble() / R
    }

    /** A placement's result: the final [out], and the amp's cost (amp alone, and the whole path from the dry side). */
    class Render(val out: Snip, val ampMs: Double, val pathMs: Double)

    fun dry(v: Voice, m: Macros): Dry {
        val (s, sMs) = timed { string(v, m) }
        val f0 = f0(v, m.tune)
        val (p, pMs) = timed { voicePickup(s, v, m, f0) }
        return Dry(p, outputChain(p), sMs, pMs)
    }

    /** P1, in-render: the amp runs at [R] on the raw string+pickup buffer, then the output chain. [scaleToPeak] pre-scales the buffer's peak (the levelled variant). */
    fun p1(d: Dry, m: Macros, scaleToPeak: Float? = null): Render {
        val src = if (scaleToPeak != null) d.buf.copyOf().also { peakMatch(it, scaleToPeak) } else d.buf
        val (a, aMs) = timed { amp(src, m.drive, m.sag, m.tone, m.cab, R) }
        val (s, cMs) = timed { outputChain(a) }
        return Render(s, aMs, aMs + cMs)
    }

    /** The rack pass at the snip's own rate. */
    fun rackP2(dry: Snip, m: Macros): Render {
        val (out, ms) = timed {
            val a = amp(dry.samples, m.drive, m.sag, m.tone, m.cab, dry.sampleRate)
            peakMatch(a, dry.peak())
            a
        }
        return Render(Snip(out, dry.channels, dry.sampleRate), ms, ms)
    }

    /** The rack pass with internal 4x oversampling. */
    fun rackP3(dry: Snip, m: Macros): Render {
        val up = dry.sampleRate * Dsp.OVERSAMPLE
        val up1 = Resampler.resample(dry, up)
        val (a, aMs) = timed { amp(up1.samples, m.drive, m.sag, m.tone, m.cab, up) }
        val (out, restMs) = timed {
            Tide.bandLimit(a, up)
            val d = Dsp.decimate(a, dry.sampleRate)
            peakMatch(d, dry.peak())
            d
        }
        // The up-sampling is timed too: timed{} above starts after it, so redo it once for its cost.
        val (_, upMs) = timed { Resampler.resample(dry, up) }
        return Render(Snip(out, dry.channels, dry.sampleRate), aMs, aMs + restMs + upMs)
    }
}

```

### CoilSpikeTest.kt

```kotlin

package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.CoilSpike.Macros
import com.snipsnap.synth.CoilSpike.Voice
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MAGNET Phase 0 part B, print-only: measures the spike in [CoilSpike] and
 * appends each grid's table to the report as soon as it exists. The only
 * assertion is that every sample is finite. Gated on COIL_SPIKE_OUT.
 */
class CoilSpikeTest {

    // ---------- helpers copied from ForkTest (private there) ----------

    private fun magnitudes(samples: FloatArray, start: Int, n: Int): FloatArray {
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples.getOrElse(start + i) { 0f } * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return FloatArray(n / 2) { b -> (re[b] * re[b] + im[b] * im[b]) }
    }

    private fun harmonicClarity(samples: FloatArray, rate: Int, f0: Float, start: Int, n: Int = 1 shl 16): Double {
        val mag = magnitudes(samples, start, n)
        var on = 0.0
        var off = 0.0
        for (b in 1 until mag.size) {
            val hz = b.toFloat() * rate / n
            if (hz > 20_000f) break
            if (hz < f0 / 2) continue
            val k = Math.round(hz / f0)
            if (abs(hz - k * f0) < 8f) on += mag[b] else off += mag[b]
        }
        return 10 * log10(on / off)
    }

    // ---------- helpers of the spike's own ----------

    private fun f(x: Double, d: Int = 1): String = if (x.isFinite()) String.format(Locale.ROOT, "%.${d}f", x) else x.toString()
    private fun f(x: Float, d: Int = 1): String = f(x.toDouble(), d)

    private fun table(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("| ").append(headers.joinToString(" | ")).append(" |\n")
        sb.append("|").append(headers.joinToString("|") { "---" }).append("|\n")
        for (r in rows) sb.append("| ").append(r.joinToString(" | ")).append(" |\n")
        return sb.toString()
    }

    private fun report(outDir: String, text: String) {
        val path = System.getenv("COIL_SPIKE_REPORT") ?: "$outDir/tables.md"
        File(path).appendText(text + "\n")
        println(text)
    }

    private fun writeWav(outDir: String, name: String, snip: Snip) {
        File(outDir).mkdirs()
        File(outDir, name).outputStream().use { WavWriter.write(it, snip) }
    }

    private fun nonFinite(a: FloatArray): Int = a.count { !it.isFinite() }
    private fun mean(a: FloatArray): Double { var s = 0.0; for (v in a) s += v; return if (a.isEmpty()) 0.0 else s / a.size }
    private fun peak(a: FloatArray): Float { var p = 0f; for (v in a) if (abs(v) > p) p = abs(v); return p }
    private fun rms(a: FloatArray): Double { var s = 0.0; for (v in a) s += v.toDouble() * v; return sqrt(s / max(1, a.size)) }

    private fun cents(samples: FloatArray, rate: Int, want: Float, fromSec: Float = 0.05f, body: Float = 0.25f): Double =
        runCatching { FineTuning.cents(FineTuning.measuredHz(samples, rate, want, fromSec, body), want.toDouble()) }.getOrElse { Double.NaN }

    /** Power-weighted spectral centroid over [startSec]..[endSec], Blackman-Harris over the span, zero-padded. */
    private fun centroid(samples: FloatArray, rate: Int, startSec: Float, endSec: Float): Double {
        val start = (startSec * rate).toInt().coerceAtMost(samples.size)
        val end = min(samples.size, (endSec * rate).toInt())
        val len = end - start
        if (len < 16) return Double.NaN
        var n = 1
        while (n < len) n *= 2
        val re = FloatArray(n) { i ->
            if (i >= len) 0f else {
                val w = 0.35875 - 0.48829 * cos(2 * PI * i / (len - 1)) + 0.14128 * cos(4 * PI * i / (len - 1)) - 0.01168 * cos(6 * PI * i / (len - 1))
                (samples[start + i] * w).toFloat()
            }
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var num = 0.0
        var den = 0.0
        for (b in 1 until n / 2) {
            val p = re[b].toDouble() * re[b] + im[b].toDouble() * im[b]
            num += b.toDouble() * rate / n * p
            den += p
        }
        return if (den > 0) num / den else 0.0
    }

    /** Harmonic levels h1..h[count] in dB re h1: the peak power within +/-1.5% of k*f0. */
    private fun harmonicsDb(samples: FloatArray, rate: Int, f0: Float, count: Int, startSec: Float = 0.05f): DoubleArray {
        val n = 1 shl 16
        val mag = magnitudes(samples, (startSec * rate).toInt(), n)
        val p = DoubleArray(count) { i ->
            val c = (i + 1) * f0
            val lo = (c * 0.985 * n / rate).toInt().coerceIn(1, mag.size - 1)
            val hi = (c * 1.015 * n / rate).toInt().coerceIn(lo, mag.size - 1)
            var best = 0.0
            for (b in lo..hi) if (mag[b] > best) best = mag[b].toDouble()
            best
        }
        return DoubleArray(count) { 10 * log10((p[it] + 1e-30) / (p[0] + 1e-30)) }
    }

    private fun hRow(label: String, h: DoubleArray, count: Int) = listOf(label) + (0 until count).map { f(h[it]) }

    private fun clarity(s: Snip, f0: Float): Double = harmonicClarity(s.samples, s.sampleRate, f0, (0.05f * s.sampleRate).toInt())

    private fun cls(s: Snip) = Classifier.classify(s).drumClass.name

    // ---------- grid 1: corners, P1 ----------

    @Test
    fun grid1_corners() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        repeat(3) { val m = CoilSpike.defaults(Voice.JANGLE); CoilSpike.p1(CoilSpike.dry(Voice.JANGLE, m), m) } // JIT warm-up, not reported
        val rows = ArrayList<List<String>>()
        val hRows = ArrayList<List<String>>()
        var bad = 0
        for (v in Voice.values()) {
            val base = CoilSpike.defaults(v)
            val cases = listOf<Pair<String, Float?>>("DEFAULTS" to null) +
                CoilSpike.MACRO_NAMES.flatMap { n -> listOf(n to 0f, n to 1f) }
            for ((name, value) in cases) {
                val m = if (value == null) base else CoilSpike.set(base, name, value)
                val label = if (value == null) "$v DEFAULTS" else "$v $name ${f(value, 0)}"
                val f0 = CoilSpike.f0(v, m.tune)
                val d = CoilSpike.dry(v, m)
                val r = CoilSpike.p1(d, m)
                val nf = nonFinite(d.buf) + nonFinite(r.out.samples)
                bad += nf
                val fileName = if (value == null) "P1_${v}_DEFAULTS.wav" else "P1_${v}_${name}_${f(value, 0)}.wav"
                writeWav(outDir, fileName, r.out)
                val dur = r.out.frameCount.toDouble() / r.out.sampleRate
                rows += listOf(
                    label, nf.toString(), f(f0, 2),
                    f(peak(d.buf), 3), f(mean(d.buf), 5), f(r.out.peak(), 3), f(mean(r.out.samples), 5),
                    f(cents(d.buf, CoilSpike.R, f0)), f(cents(r.out.samples, r.out.sampleRate, f0)),
                    Pitch.detect(r.out)?.hz?.let { f(it, 1) } ?: "none",
                    f(clarity(r.out, f0), 1), f(Loudness.of(r.out), 4), cls(r.out), f(dur, 2),
                    f((d.stringMs + d.pickupMs) / d.seconds, 1), f(r.ampMs / dur, 1),
                )
                hRows += hRow(label, harmonicsDb(r.out.samples, r.out.sampleRate, f0, 8), 8)
            }
        }
        report(
            outDir,
            "## Grid 1: corners, P1 (in-render), 34 renders\n\n" +
                "dry = string+pickup at R (176.4 kHz); final = P1 output at 44.1 kHz. Cents from `FineTuning.measuredHz` (dry at R, final at 44.1 kHz), " +
                "want = nominal f0. Clarity from 0.05 s at nominal f0. ms/s = milliseconds per rendered second (string+pickup, and amp alone); " +
                "single timed run after warm-up, so noisy.\n\n" +
                table(
                    listOf("render", "non-finite", "f0 Hz", "dry pk", "dry mean", "fin pk", "fin mean", "dry cents", "fin cents", "detect Hz", "clarity dB", "loudness", "class", "dur s", "str+pu ms/s", "amp ms/s"),
                    rows,
                ) + "\nHarmonics of the final, dB re h1:\n\n" +
                table(listOf("render") + (1..8).map { "h$it" }, hRows),
        )
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 2: tuning ----------

    @Test
    fun grid2_tuning() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val rows = ArrayList<List<String>>()
        var bad = 0
        var dryMin = Double.MAX_VALUE
        var dryMax = -Double.MAX_VALUE
        var finMin = Double.MAX_VALUE
        var finMax = -Double.MAX_VALUE
        for (v in Voice.values()) for (tune in listOf(0f, 0.5f, 1f)) for (mute in listOf(0f, 0.5f, 1f)) for (blend in listOf(0f, 1f)) {
            val m = CoilSpike.defaults(v).copy(tune = tune, mute = mute, blend = blend)
            val f0 = CoilSpike.f0(v, tune)
            val d = CoilSpike.dry(v, m)
            val r = CoilSpike.p1(d, m)
            bad += nonFinite(d.buf) + nonFinite(r.out.samples)
            val dc = cents(d.buf, CoilSpike.R, f0)
            val dcWide = cents(d.buf, CoilSpike.R, f0, 0.05f, 0.35f)
            val fc = cents(r.out.samples, r.out.sampleRate, f0)
            if (dc.isFinite()) { dryMin = min(dryMin, dc); dryMax = max(dryMax, dc) }
            if (fc.isFinite()) { finMin = min(finMin, fc); finMax = max(finMax, fc) }
            rows += listOf(v.name, f(tune, 1), f(mute, 1), f(blend, 0), f(f0, 2), f(dc, 2), f(dcWide, 2), f(fc, 2), f(d.seconds, 2))
        }
        report(
            outDir,
            "## Grid 2: tuning, 36 cells\n\n" +
                "Cents error of the measured peak against nominal f0. `dry` uses the default 0.25 s body (2.7 Hz bins at R); " +
                "`dry wide` a 0.35 s body; `P1 final` is at 44.1 kHz through the amp at default DRIVE/SAG/TONE/CAB.\n\n" +
                table(listOf("voice", "TUNE", "MUTE", "BLEND", "f0 Hz", "dry cents", "dry wide cents", "P1 final cents", "dry dur s"), rows) +
                "\nDry cents range: ${f(dryMin, 2)} .. ${f(dryMax, 2)}. P1 final range: ${f(finMin, 2)} .. ${f(finMax, 2)}.\n",
        )
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 3: the comb ----------

    private fun notchLine(label: String, h: DoubleArray, ks: List<Int>): String {
        val parts = ks.map { k ->
            val lo = h[k - 2]
            val hi = h[k]
            val under = h[k - 1] - max(lo, hi)
            val underQuiet = h[k - 1] - min(lo, hi)
            "h$k ${f(under)} dB under louder neighbour, ${f(underQuiet)} under quieter"
        }
        return "- $label: " + parts.joinToString("; ")
    }

    @Test
    fun grid3_comb() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val v = Voice.JANGLE
        val m = CoilSpike.defaults(v)
        val f0 = CoilSpike.f0(v, m.tune)
        val s = CoilSpike.string(v, m)
        val out = StringBuilder("## Grid 3: the comb\n\n")

        // (a) JANGLE single coil, no resonance
        val h13 = ArrayList<List<String>>()
        val hs = HashMap<Float, DoubleArray>()
        for (p in listOf(0.5f, 0.25f)) {
            val y = CoilSpike.pickup(s, p, false, 0f, 20000f, 1f, f0, CoilSpike.R)
            bad += nonFinite(y)
            val h = harmonicsDb(y, CoilSpike.R, f0, 13)
            hs[p] = h
            h13 += hRow("JANGLE single p=$p", h, 8)
        }
        out.append("### (a) JANGLE single coil, resonance off (20 kHz, k 1), TUNE 0.5, f0 ${f(f0, 2)} Hz, dry at R\n\n")
        out.append(table(listOf("case") + (1..8).map { "h$it" }, h13))
        out.append("\n")
        out.append(notchLine("p=0.5, even harmonics", hs.getValue(0.5f), listOf(2, 4, 6, 8))).append("\n")
        out.append(notchLine("p=0.25, h4 and h8", hs.getValue(0.25f), listOf(4, 8))).append("\n")
        out.append(notchLine("p=0.25, h2 and h6 for contrast (should not notch)", hs.getValue(0.25f), listOf(2, 6))).append("\n\n")

        // (b) CHUG humbucker p = 0.12 with and without the second coil
        val c = Voice.CHUG
        val cm = CoilSpike.defaults(c)
        val cf0 = CoilSpike.f0(c, cm.tune)
        val cs = CoilSpike.string(c, cm)
        val dp = CoilSpike.COIL_SPACING * (cf0 / c.rootHz)
        val rowsB = ArrayList<List<String>>()
        val hb = HashMap<String, DoubleArray>()
        for ((label, hum) in listOf("single coil p=0.12" to false, "humbucker p=0.12" to true)) {
            val y = CoilSpike.pickup(cs, CoilSpike.BRIDGE, hum, dp, 20000f, 1f, cf0, CoilSpike.R)
            bad += nonFinite(y)
            val h = harmonicsDb(y, CoilSpike.R, cf0, 13)
            hb[label] = h
            rowsB += hRow(label, h, 12)
        }
        out.append("### (b) CHUG at p=0.12, resonance off, TUNE 0.5, f0 ${f(cf0, 2)} Hz, dp ${f(dp.toDouble(), 4)} (second coil at p+dp; expected extra notch where k*dp = 0.5, k = ${f(0.5 / dp, 2)})\n\n")
        out.append(table(listOf("case") + (1..12).map { "h$it" }, rowsB))
        out.append("\n")
        out.append(notchLine("humbucker, h9", hb.getValue("humbucker p=0.12"), listOf(9))).append("\n")
        out.append(notchLine("single coil, h9 (control)", hb.getValue("single coil p=0.12"), listOf(9))).append("\n\n")

        // (c) BLEND 0 / 0.5 / 1 on JANGLE, full pickup with resonance
        val rowsC = ArrayList<List<String>>()
        for (b in listOf(0f, 0.5f, 1f)) {
            val y = CoilSpike.voicePickup(s, v, m.copy(blend = b), f0)
            bad += nonFinite(y)
            rowsC += hRow("JANGLE BLEND ${f(b, 1)}", harmonicsDb(y, CoilSpike.R, f0, 13), 12)
        }
        out.append("### (c) JANGLE BLEND, neck p=0.42 / bridge p=0.12, with the 4500 Hz k 0.40 resonance, dry at R\n\n")
        out.append(table(listOf("case") + (1..12).map { "h$it" }, rowsC))
        report(outDir, out.toString())
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 4: PICK sweep ----------

    @Test
    fun grid4_pickSweep() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val sb = StringBuilder("## Grid 4: PICK sweep\n\nPower-weighted spectral centroid of the dry 44.1 kHz render (string + pickup + chain, no amp), 0.05..0.3 s.\n\n")
        for (v in Voice.values()) {
            val rows = ArrayList<List<String>>()
            val cs = ArrayList<Double>()
            for (i in 0..10) {
                val pick = i / 10f
                val m = CoilSpike.defaults(v).copy(pick = pick)
                val d = CoilSpike.dry(v, m)
                bad += nonFinite(d.buf)
                val c = centroid(d.snip.samples, d.snip.sampleRate, 0.05f, 0.3f)
                cs += c
                rows += listOf(f(pick, 1), f(CoilSpike.pickCornerHz(v, pick), 0), f(c, 1), f(d.snip.peak(), 3))
            }
            val drops = (1 until cs.size).count { cs[it] < cs[it - 1] }
            val worst = (1 until cs.size).minOf { cs[it] - cs[it - 1] }
            sb.append("### ${v.name}\n\n").append(table(listOf("PICK", "exciter corner Hz", "centroid Hz", "dry peak"), rows))
            sb.append("\nMonotonic non-decreasing: ${drops == 0} (steps that fall: $drops, smallest step ${f(worst, 1)} Hz).\n\n")
        }
        report(outDir, sb.toString())
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 5: placements ----------

    @Test
    fun grid5_placements() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val cases = listOf(
            Triple(Voice.CHUG, 0.5f, 1.0f),
            Triple(Voice.CHUG, 1.0f, 1.0f),
            Triple(Voice.JANGLE, 0.5f, 0.5f),
        )
        // Warm-up so the timings are not the JIT's.
        run { val m = CoilSpike.defaults(Voice.CHUG).copy(drive = 1f); val d = CoilSpike.dry(Voice.CHUG, m); repeat(2) { CoilSpike.p1(d, m); CoilSpike.rackP2(d.snip, m); CoilSpike.rackP3(d.snip, m) } }
        val rows = ArrayList<List<String>>()
        for ((v, tune, drive) in cases) {
            val m = CoilSpike.defaults(v).copy(tune = tune, drive = drive)
            val f0 = CoilSpike.f0(v, tune)
            val d = CoilSpike.dry(v, m)
            val tag = "${v}_T${f(tune, 1)}_D${f(drive, 1)}"
            val dryPeak = d.snip.peak()
            val renders = listOf(
                "P1" to { CoilSpike.p1(d, m) },
                "P1n" to { CoilSpike.p1(d, m, scaleToPeak = dryPeak) },
                "P2" to { CoilSpike.rackP2(d.snip, m) },
                "P3" to { CoilSpike.rackP3(d.snip, m) },
            )
            rows += listOf(
                "$v T${f(tune, 1)} D${f(drive, 1)}", "dry", "-", f(clarity(d.snip, f0), 1), f(clarity(d.snip, f0), 1),
                f(centroid(d.snip.samples, d.snip.sampleRate, 0.05f, 0.3f), 0), f(mean(d.snip.samples), 5),
                f(cents(d.snip.samples, d.snip.sampleRate, f0), 1), Pitch.detect(d.snip)?.hz?.let { f(it, 1) } ?: "none",
                f(Loudness.of(d.snip), 4), f(dryPeak, 3), "-", "-",
            )
            for ((name, run) in renders) {
                // Min of three timed runs; the output is from the first.
                val first = run()
                var ampMs = first.ampMs
                var pathMs = first.pathMs
                repeat(2) { val r = run(); ampMs = min(ampMs, r.ampMs); pathMs = min(pathMs, r.pathMs) }
                val out = first.out
                bad += nonFinite(out.samples)
                writeWav(outDir, "${name}_$tag.wav", out)
                val dur = out.frameCount.toDouble() / out.sampleRate
                val ownHz = runCatching { FineTuning.measuredHz(out.samples, out.sampleRate, f0) }.getOrElse { Double.NaN }
                rows += listOf(
                    "$v T${f(tune, 1)} D${f(drive, 1)}", name, f(out.frameCount.toDouble() / out.sampleRate, 2),
                    f(clarity(out, f0), 1),
                    if (ownHz.isFinite()) f(harmonicClarity(out.samples, out.sampleRate, ownHz.toFloat(), (0.05f * out.sampleRate).toInt()), 1) else "n/a",
                    f(centroid(out.samples, out.sampleRate, 0.05f, 0.3f), 0), f(mean(out.samples), 5),
                    f(FineTuning.cents(ownHz, f0.toDouble()), 1), Pitch.detect(out)?.hz?.let { f(it, 1) } ?: "none",
                    f(Loudness.of(out), 4), f(out.peak(), 3), f(ampMs / dur, 1), f(pathMs / dur, 1),
                )
            }
        }
        report(
            outDir,
            "## Grid 5: placements\n\n" +
                "P1 = amp at R inside the render (raw string level into the amp), P1n = P1 with the string buffer's peak pre-scaled to the dry snip's peak " +
                "(so the amp sees the same level as P2/P3), P2 = rack pass at 44.1 kHz, P3 = rack pass 4x oversampled. " +
                "clarity(nominal) is harmonicClarity at the nominal f0 from 0.05 s; clarity(own) uses the render's own measured f0. " +
                "centroid is over 0.05..0.3 s. cents are of the measured peak vs nominal f0. amp ms/s is the amp alone per rendered second " +
                "(min of 3 runs); path ms/s is amp plus whatever else that placement adds after the dry render " +
                "(P1: output chain; P3: up-sample, band-limit, decimate).\n\n" +
                table(
                    listOf("case", "placement", "dur s", "clarity nominal dB", "clarity own dB", "centroid Hz", "DC mean", "cents", "detect Hz", "loudness", "peak", "amp ms/s", "path ms/s"),
                    rows,
                ),
        )
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 6: CRUNCH-rule identity ----------

    @Test
    fun grid6_identity() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val m = Macros(drive = 0.6f, sag = 0.35f, tone = 0.5f, cab = 0.6f)
        val rows = ArrayList<List<String>>()
        var snareDry: Snip? = null
        for ((name, voice) in listOf("KICK" to ThumpVoice.KICK, "SNARE" to ThumpVoice.SNARE)) {
            val dry = Thump.render(voice)
            if (voice == ThumpVoice.SNARE) snareDry = dry
            val amped = CoilSpike.rackP3(dry, m).out
            bad += nonFinite(dry.samples) + nonFinite(amped.samples)
            writeWav(outDir, "DRY_$name.wav", dry)
            writeWav(outDir, "AMP_$name.wav", amped)
            for ((label, s) in listOf("dry" to dry, "amp (P3, DRIVE 0.6)" to amped)) {
                rows += listOf(
                    name, label, cls(s), f(s.peak(), 3), f(centroid(s.samples, s.sampleRate, 0f, 0.3f), 0),
                    f(s.frameCount.toDouble() / s.sampleRate, 2), f(rms(s.samples), 4),
                )
            }
        }
        val sb = StringBuilder("## Grid 6: CRUNCH-rule identity\n\n")
        sb.append("Thump KICK and SNARE (default macros) through P3's amp at DRIVE 0.6, SAG 0.35, TONE 0.5, CAB 0.6, peak-matched to the dry peak. Centroid over 0..0.3 s.\n\n")
        sb.append(table(listOf("voice", "state", "class", "peak", "centroid Hz", "dur s", "rms"), rows))

        val dry = snareDry!!
        val t = Macros(drive = 0f, sag = 0f, tone = 0.5f, cab = 0f)
        val out = CoilSpike.rackP3(dry, t).out
        bad += nonFinite(out.samples)
        val n = min(dry.samples.size, out.samples.size)
        val diff = FloatArray(n) { out.samples[it] - dry.samples[it] }
        val dryR = rms(dry.samples.copyOf(n))
        val db = 20 * log10(rms(diff) / dryR)
        // The same, after a best-fit gain (RMS-matched), to separate level from colour.
        val gain = dryR / rms(out.samples.copyOf(n))
        val diffMatched = FloatArray(n) { (out.samples[it] * gain).toFloat() - dry.samples[it] }
        val dbMatched = 20 * log10(rms(diffMatched) / dryR)
        writeWav(outDir, "AMP_SNARE_TRANSPARENT.wav", out)
        sb.append("\nNear-transparent point (DRIVE 0, SAG 0, TONE 0.5, CAB 0) on the snare, P3, peak-matched: RMS of (out - dry) is ${f(db)} dB re dry RMS; ")
        sb.append("after an RMS-matching gain of ${f(gain, 3)}: ${f(dbMatched)} dB. Class after: ${cls(out)}, centroid ${f(centroid(out.samples, out.sampleRate, 0f, 0.3f), 0)} Hz.\n")
        report(outDir, sb.toString())
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- grid 7: cost of the string ----------

    @Test
    fun grid7_cost() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val rows = ArrayList<List<String>>()
        for (v in Voice.values()) {
            val m = CoilSpike.defaults(v)
            repeat(3) { CoilSpike.dry(v, m) }
            var sMs = Double.MAX_VALUE
            var pMs = Double.MAX_VALUE
            var last: CoilSpike.Dry? = null
            repeat(5) {
                val d = CoilSpike.dry(v, m)
                sMs = min(sMs, d.stringMs)
                pMs = min(pMs, d.pickupMs)
                last = d
            }
            val d = last!!
            bad += nonFinite(d.buf)
            rows += listOf(v.name, f(d.seconds, 2), f(sMs, 1), f(pMs, 1), f(sMs / d.seconds, 1), f(pMs / d.seconds, 1), f((sMs + pMs) / d.seconds, 1))
        }
        report(
            outDir,
            "## Grid 7: cost of the string\n\nString (pluck + trim) and pickup at defaults at R = 176.4 kHz, min of 5 runs after 3 warm-ups. ms/s is milliseconds per rendered second of the trimmed buffer.\n\n" +
                table(listOf("voice", "rendered s", "string ms", "pickup ms", "string ms/s", "pickup ms/s", "total ms/s"), rows),
        )
        assertEquals(0, bad, "non-finite samples")
    }

    // ---------- extra probes: what the grids above could not separate ----------

    /** A steady, exactly harmonic tone: harmonics 1..[count] of [f0] at 1/k, peak 0.99, [seconds] long. */
    private fun steadyTone(f0: Float, count: Int, seconds: Float, rate: Int): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in 1..count) s += kotlin.math.sin(2 * PI * k * f0.toDouble() * i / rate) / k
            out[i] = s.toFloat()
        }
        val p = peak(out)
        for (i in out.indices) out[i] = out[i] * 0.99f / p
        return out
    }

    /** The amp at [x]'s rate times [factor]: resample up, amp, band-limit, resample back (factor 1 is the plain native-rate amp). */
    private fun ampAt(x: Snip, m: Macros, factor: Int): FloatArray {
        if (factor == 1) return CoilSpike.amp(x.samples, m.drive, m.sag, m.tone, m.cab, x.sampleRate)
        val up = x.sampleRate * factor
        val u = com.snipsnap.audio.Resampler.resample(x, up)
        val a = CoilSpike.amp(u.samples, m.drive, m.sag, m.tone, m.cab, up)
        Tide.bandLimit(a, up)
        return com.snipsnap.audio.Resampler.resample(Snip(a, 1, up), x.sampleRate).samples
    }

    private fun errDb(a: FloatArray, ref: FloatArray, startSec: Float, endSec: Float, rate: Int): Double {
        val s = (startSec * rate).toInt()
        val e = min(min(a.size, ref.size), (endSec * rate).toInt())
        var num = 0.0
        var den = 0.0
        for (i in s until e) { val d = a[i].toDouble() - ref[i]; num += d * d; den += ref[i].toDouble() * ref[i] }
        return 10 * log10(num / den)
    }

    @Test
    fun extra_alias() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val sb = StringBuilder("## Extra A: aliasing, measured where harmonicClarity is not confounded\n\n")
        // (1) A steady, exactly harmonic input, so the only off-harmonic energy is what the amp adds by aliasing.
        val rows = ArrayList<List<String>>()
        for ((f0, drive) in listOf(123.48f to 1.0f, 123.48f to 0.45f, 246.96f to 1.0f, 164.82f to 0.5f)) {
            val m = Macros(drive = drive)
            val n = (5000f / f0).toInt()
            val x = Snip(steadyTone(f0, n, 1.6f, Dsp.RATE), 1, Dsp.RATE)
            val outs = listOf(1 to "1x (P2)", 4 to "4x (P3)", 8 to "8x").map { (fct, label) -> label to ampAt(x, m, fct) }
            val ref = outs.last().second
            for ((label, y) in outs) {
                bad += nonFinite(y)
                val c = harmonicClarity(y, Dsp.RATE, f0, (0.3f * Dsp.RATE).toInt())
                rows += listOf(f(f0, 2), f(drive, 2), label, f(c, 1), f(errDb(y, ref, 0.3f, 1.2f, Dsp.RATE), 1))
            }
        }
        sb.append("Steady input: harmonics of f0 to ~5 kHz at 1/k, peak 0.99, 1.6 s, 44.1 kHz; CHUG-law amp with default SAG/TONE/CAB. clarity from 0.3 s at the exact f0. ")
        sb.append("`err vs 8x` is the residual against the 8x render, dB re its energy over 0.3..1.2 s (includes the small filter-warp difference, so read it as a ranking).\n\n")
        sb.append(table(listOf("f0 Hz", "DRIVE", "amp rate", "clarity dB", "err vs 8x dB"), rows)).append("\n")

        // (2) The string cases: residual against an 8x render of the same dry snip.
        val rows2 = ArrayList<List<String>>()
        for ((v, tune, drive) in listOf(Triple(Voice.CHUG, 0.5f, 1.0f), Triple(Voice.CHUG, 1.0f, 1.0f), Triple(Voice.JANGLE, 0.5f, 0.5f))) {
            val m = CoilSpike.defaults(v).copy(tune = tune, drive = drive)
            val d = CoilSpike.dry(v, m)
            val outs = listOf(1 to "1x (P2)", 4 to "4x (P3)", 8 to "8x").map { (fct, label) -> label to ampAt(d.snip, m, fct) }
            val ref = outs.last().second
            val f0 = CoilSpike.f0(v, tune)
            for ((label, y) in outs) {
                bad += nonFinite(y)
                rows2 += listOf("$v T${f(tune, 1)} D${f(drive, 1)}", label, f(errDb(y, ref, 0.05f, 0.3f, Dsp.RATE), 1), f(harmonicClarity(y, Dsp.RATE, f0, (0.05f * Dsp.RATE).toInt()), 1))
            }
        }
        sb.append("String renders (the grid-5 cases): the dry 44.1 kHz snip through the amp at 1x, 4x, 8x; residual against 8x over 0.05..0.3 s, and clarity at the nominal f0 (no peak match, so levels are the amp's own).\n\n")
        sb.append(table(listOf("case", "amp rate", "err vs 8x dB", "clarity dB"), rows2)).append("\n")

        // (3) Where P3's cost goes.
        val m = CoilSpike.defaults(Voice.CHUG).copy(drive = 1f)
        val d = CoilSpike.dry(Voice.CHUG, m)
        repeat(2) { CoilSpike.rackP3(d.snip, m) }
        var upMs = Double.MAX_VALUE; var ampMs = Double.MAX_VALUE; var downMs = Double.MAX_VALUE; var amp1Ms = Double.MAX_VALUE
        repeat(3) {
            var t = System.nanoTime()
            val u = com.snipsnap.audio.Resampler.resample(d.snip, Dsp.RATE * 4)
            upMs = min(upMs, (System.nanoTime() - t) / 1e6)
            t = System.nanoTime()
            val a = CoilSpike.amp(u.samples, m.drive, m.sag, m.tone, m.cab, Dsp.RATE * 4)
            ampMs = min(ampMs, (System.nanoTime() - t) / 1e6)
            t = System.nanoTime()
            Tide.bandLimit(a, Dsp.RATE * 4); Dsp.decimate(a, Dsp.RATE)
            downMs = min(downMs, (System.nanoTime() - t) / 1e6)
            t = System.nanoTime()
            CoilSpike.amp(d.snip.samples, m.drive, m.sag, m.tone, m.cab, Dsp.RATE)
            amp1Ms = min(amp1Ms, (System.nanoTime() - t) / 1e6)
        }
        val sec = d.snip.frameCount.toDouble() / Dsp.RATE
        sb.append("P3 cost breakdown, CHUG T0.5 DRIVE 1 (${f(sec, 2)} s), ms per rendered second, min of 3: up-sample ${f(upMs / sec)}, amp at 4x ${f(ampMs / sec)}, band-limit + decimate ${f(downMs / sec)}; the amp at 1x is ${f(amp1Ms / sec)}.\n")
        report(outDir, sb.toString())
        assertEquals(0, bad, "non-finite samples")
    }

    @Test
    fun extra_humbuckerNotch() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        var bad = 0
        val c = Voice.CHUG
        val m = CoilSpike.defaults(c)
        val f0 = CoilSpike.f0(c, m.tune)
        val s = CoilSpike.string(c, m)
        val dp = CoilSpike.COIL_SPACING * (f0 / c.rootHz)
        val count = 22
        val r = CoilSpike.R
        fun h(hum: Boolean, aligned: Boolean) =
            harmonicsDb(CoilSpike.pickup(s, CoilSpike.BRIDGE, hum, dp, 20000f, 1f, f0, r, aligned), r, f0, count)
        val single = h(false, false)
        val hum = h(true, false)
        val humA = h(true, true)
        bad += nonFinite(s)
        // Theory, with the delays as actually rounded. Single: |1 - e^-jw D1|. As briefed: |2 - e^-jw D1 - e^-jw D2|.
        // Aligned: the sum of the two centred combs, |sin(pi k p1e) + sin(pi k p2e)|.
        val d1 = CoilSpike.combDelay(CoilSpike.BRIDGE, f0, r)
        val d2 = CoilSpike.combDelay(CoilSpike.BRIDGE + dp, f0, r)
        val per = r / f0.toDouble()
        fun th(k: Int): Triple<Double, Double, Double> {
            val w = 2 * PI * k / per
            val sing = abs(2 * kotlin.math.sin(w * d1 / 2))
            val re = 2 - cos(w * d1) - cos(w * d2)
            val im = kotlin.math.sin(w * d1) + kotlin.math.sin(w * d2)
            val brief = kotlin.math.sqrt(re * re + im * im)
            val al = abs(kotlin.math.sin(w * d1 / 2) + kotlin.math.sin(w * d2 / 2)) * 2
            return Triple(sing, brief, al)
        }
        fun rel(k: Int, pick: (Triple<Double, Double, Double>) -> Double) =
            20 * log10(pick(th(k)) / th(k).first) - 20 * log10(pick(th(1)) / th(1).first)
        val rows = (1..count).map { k ->
            listOf(
                "h$k", f(single[k - 1]), f(hum[k - 1]), f(humA[k - 1]),
                f(hum[k - 1] - single[k - 1]), f(rel(k) { it.second }),
                f(humA[k - 1] - single[k - 1]), f(rel(k) { it.third }),
            )
        }
        report(
            outDir,
            "## Extra C: where the humbucker's second coil notches (CHUG, p=0.12, resonance off, f0 ${f(f0, 2)} Hz, dp ${f(dp.toDouble(), 4)}, D1 $d1, D2 $d2)\n\n" +
                "Three humbuckers on the same string: as briefed (`0.707 * (c(p) + c(p+dp))`, each comb `y[n] - y[n-D]`), and phase-aligned (each comb's taps delayed so its centre is the longer comb's centre). " +
                "The aligned pair is `sin(pi k p1) + sin(pi k p2)`, whose first spacing notch is at k = 1/dp = ${f(1.0 / dp, 2)}; as briefed the two combs' linear-phase terms differ and there is no such notch. " +
                "The exciter's own 0.10 comb notches h10 in every row. `d` columns are humbucker minus single coil, both re h1: measured, then theory from the delays as rounded.\n\n" +
                table(listOf("harmonic", "single dB", "hum briefed dB", "hum aligned dB", "briefed d meas", "briefed d theory", "aligned d meas", "aligned d theory"), rows),
        )
        assertEquals(0, bad, "non-finite samples")
    }

    @Test
    fun extra_pitchProbe() {
        val outDir = System.getenv("COIL_SPIKE_OUT") ?: run { println("COIL_SPIKE_OUT unset; skipping"); return }
        val rows = ArrayList<List<String>>()
        val cells = listOf(
            Triple(Voice.JANGLE, 0f, 0.5f to 1f), Triple(Voice.CHUG, 0f, 0.5f to 1f),
            Triple(Voice.CHUG, 1f, 0.5f to 1f), Triple(Voice.JANGLE, 0.5f, 0.5f to 0f),
        )
        for ((v, tune, mb) in cells) {
            val m = CoilSpike.defaults(v).copy(tune = tune, mute = mb.first, blend = mb.second)
            val f0 = CoilSpike.f0(v, tune)
            val d = CoilSpike.dry(v, m)
            val r = CoilSpike.p1(d, m)
            for ((label, s) in listOf("dry 44.1k" to d.snip, "P1 final" to r.out)) {
                val body = min(0.25f, s.frameCount.toFloat() / s.sampleRate - 0.15f)
                rows += listOf(
                    "$v T${f(tune, 1)} M${f(mb.first, 1)} B${f(mb.second, 0)}", label,
                    f(cents(s.samples, s.sampleRate, f0, 0.05f, body)), f(cents(s.samples, s.sampleRate, f0, 0.10f, body)),
                    f(cents(s.samples, s.sampleRate, f0, 0.02f, body)), Pitch.detect(s)?.hz?.let { f(it, 2) } ?: "none", f(f0, 2),
                )
            }
        }
        report(
            outDir,
            "## Extra B: is grid 2's large P1-final cents a pitch shift or a measurement artefact?\n\n" +
                "The same cells measured with the analysis window starting at 0.02, 0.05 and 0.10 s (`FineTuning.measuredHz`, cents vs nominal), and `Pitch.detect`'s autocorrelation Hz. " +
                "A real pitch shift would not move with the window; an artefact of a spectral peak read off a decaying, amp-coloured line would.\n\n" +
                table(listOf("cell", "signal", "cents @0.05", "cents @0.10", "cents @0.02", "Pitch.detect Hz", "nominal Hz"), rows),
        )
    }
}

```

Gradle exit code: 0 (final full run, 41.9 s wall).
