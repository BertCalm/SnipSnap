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
finite samples and their musical quality awaits listening.
