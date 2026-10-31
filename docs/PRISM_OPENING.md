# PRISM opening comparison — round 3

This round replaces the generic harmonically rich opening with an opening
derived from the destination's own lower harmonics. The intended gesture is
body first, then upper colour. The previous opening remains available beside
it so the owner's listening decision can focus on that change.

Four sources from the palette round are used: FORK SOFT KEYS, TESSERA Paired
Wire, EMBOUCHURE Relaxed Bow and FATHOM HOLLOW GROWL. Each has the new body
opening, the previous generic opening, the static destination and the original
source reference: 16 cases, with native and matched WAVs and saved recipes.

`PrismMotion.amplitudesA` is an optional, immutable 32-coefficient array. When
present it overrides the starting weights without changing the original
template's carrier frequencies, phase seed or group contours. Existing motion
recipes omit the field and retain their original JSON and rendering path.
Validation and the existing fixed headroom/root safeguards apply to both ends.

The audition derives A from the saved B coefficients using a smooth harmonic
weight mask:

```text
A[i] = B[i] * (0.03 + 0.97 / (1 + (i / 4)^4)), i = 0..31
```

The fundamental is retained; progressively higher harmonics start quieter.
The same 0.8-second interpolation reveals B. This is an amplitude trajectory,
not a new distortion, filter effect, pitch movement or reconstruction of the
original source's attack. Level matching uses the shared audition convention.

```text
./gradlew :synth:test --tests 'com.snipsnap.synth.Prism*'
./gradlew :synth:generatePrismOpeningAudition
```

The generator first reproduces the palette pack, then copies its reference
audio and recipes without changing their bytes. An optional
`-PprismOpeningReferenceManifest=/absolute/path/manifest.json` also checks
those files against an earlier published palette manifest. New body recipes
must round-trip and replay exactly. Source revision, hashes, spectral measures
and late destination comparisons accompany the files.

The hosted page opens on the new body variant and gives the original source
a separate reference button. Round 2 remains at `round-2/`, round 1 at
`round-1/`; each keeps its own browser review namespace. The sounds remain
finite samples. The owner preferred the body opening and chose it as the
baseline; the wider pitch and velocity matrix still needs listening.

## Factory release

The factory bank appends four body-opening presets to the previous 36 without
changing those recipes or their order:

| Factory name | Voice | Round-three example |
| --- | --- | --- |
| Soft Arrival | KEY | FORK SOFT KEYS |
| Wire Bloom | KEY | TESSERA Paired Wire |
| Bowed Bloom | KEY | EMBOUCHURE Relaxed Bow |
| Hollow Rise | BASS | FATHOM HOLLOW GROWL |

`PrismOpeningPresets` freezes the destination coefficients from the saved
round-three recipes. It never rerenders a donor at runtime. Its shared body
mask retains the exact opening weights, original phase-seeding template,
velocity, pitch and 0.8-second trajectory. Tests compare the whole patch,
apart from its factory name, with independent saved audition fixtures.

The existing six macros and HOLD remain the controls. On a motion recipe the
phone bounds HOLD below `Prism.LOOP_THRESHOLD`, including accessibility edits
and SCRAMBLE, and labels it as a finite note. Ordinary presets still reach
LOOP. User saves, navigation drafts and pad recipes retain both endpoints;
factory-preset promotion now embeds the complete motion recipe too.

```text
./gradlew --no-daemon test
./gradlew :synth:generatePrismOpeningReleaseAudition
```

The release audition renders the actual factory presets at three notes and
two velocities, with native and matched WAVs, complete saved recipes and a
diagnostic manifest. JVM render and cancellation timings describe this
machine, not Android performance. Phone interaction and MPC playback require
device validation. The [motion studio](PRISM_MOTION_STUDIO.md) adds an editor,
factory source selection and opt-in repeating motion. These four factory
recipes retain their original finite opening until deliberately edited.
