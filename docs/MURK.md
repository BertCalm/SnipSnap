# MURK

MURK renders an imaginary acoustic grove: tuned wood, delayed pressure
fronts, and synthesized owls whose responses depend on those disturbances.
The [design specification](superpowers/specs/2026-10-03-murk-engine-engineering-spec.md)
describes the intent. The propagation rules and animal behavior are musical
inventions, not validated atmospheric or biological models.

## Listen before accepting

```sh
./gradlew --no-daemon :synth:generateMurkAudition
```

Open `testkit/murk-audition/index.html` with its neighboring audio files in
place. The page works from a local folder and can also be served over HTTP.
It includes all fourteen factory presets, voice and pitch comparisons, physical strike velocity, macro
sweeps, interaction grids, raw/matched pairs, isolated components, disabled
coupling comparisons, extremes, and recurring HOLD clips. Its manifest
records the settings and measured output of each clip.

Sound starts only when requested. Clip descriptions remain readable with
audio off; controls have keyboard focus and accessible names. Stop all
ends playback, and starting another clip stops the previous one. Native
audio controls provide a fallback.

HOLD cards repeat by default after Enable audio and Play. The main player
uses a decoded audio buffer to avoid the browser media player's seek gap
between cycles. Pause and Stop ramp smoothly to silence. Turn Repeat off
for a one-cycle preview with a smooth ending; the periodic WAV itself keeps
its complete boundary and ongoing responses. For gapless HOLD playback,
use the main controls over HTTP(S); native/local-file playback remains a
fallback.

After changing the listening page, refresh its existing cards without
rerendering the WAVs or event records:

```sh
./gradlew --no-daemon :synth:generateMurkAudition -PmurkRefreshPage=true
```

MURK's audition files use loudest-200-ms RMS 0.12 with a 0.90 peak ceiling,
and the page starts at 85% playback volume with audio off. This listening
gain affects the audition exports only. Raw comparison partners keep their
original amplitude, while isolated source taps inherit their full reference's
gain to retain the actual wood, fog, and owl balance. Matched WAV filenames
end in `_listen_012.wav` so browsers request the louder files.

Start with the six default voices. Listen for a woody pitched attack,
separate traveling arrivals, and an owl answer that belongs to the same
event. Compare the raw/matched pairs before judging component balance.
Then compare links on/off, calls on/off, and owl-to-fog coupling on/off.
Finally audition the high-register THWACK, low-register CLUNK, all-high
corners, and several wraps of each held loop.

The bat revision moves the original THWACK character into CLUNK. At the
same settings, CLUNK retains that reference's material, decay, and seed.
THWACK now drives a short rough contact through the upper wood modes, then
releases a sharper impulse into the full tuned trunk. Listen for a distinct
"thhh" before the "wack" and for the pitched wood that follows it.

The hosted revision starts with matched before/after clips at C3, C4, and
C5. To reproduce those comparisons, generate the original audition from
commit `60b3a3634a8446dd0d38057f14604d4dbd80b708` in a separate checkout,
then supply that output directory to the current generator:

```sh
./gradlew --no-daemon :synth:generateMurkAudition \
  -PmurkBaselineDir=/absolute/path/to/original/murk-audition \
  -PmurkBaselineRevision=60b3a3634a8446dd0d38057f14604d4dbd80b708
```

The original baseline sources remain at RMS 0.03 and retain their recipe,
Git revision, and source WAV checksum. The generator validates that level,
then applies a linear gain to match the current RMS 0.12 listening target;
the manifest records the source level and applied baseline playback gain.
Without a baseline, the generator produces the current 295-clip library.

The owner's listening verdict remains open. JVM checks establish numerical
and integration behavior; phone playback, MPC export feel, and subjective
sound quality require listening on the intended devices.

## Host contract

- Mono samples at 44.1 kHz; synthesis runs on the shared 4× clock and is
  band-limited through the existing decimator.
- Six voices: CLUNK, THWACK, FRONT, HOOT, GROVE, ALARM. Fourteen factory
  presets land dry, and `SynthKits.murk()` creates sixteen pitched recipe pads.
- TUNE selects C3–C5 (MIDI 48–72) in semitone steps, with C4 at the midpoint.
  The five timbral controls are STRIKE, TRUNK, FOG, AGITATION, and GROVE.
  HOLD supplies the existing one-shot/recurring-sample behavior.
- `MurkPatch` stores voice and normalized macros using the shared JSON
  version. `PadRecipe` and `FxChain` regenerate the source before effects.
- Velocity follows `Velocity.atVelocity` and the newer engines' physical
  strike argument. It changes excitation and stimulus energy independently
  of STRIKE's contact character; the host owns playback gain.
- Every independent render starts the grove at rest and uses `Dsp.seedFor`.
  Pads share no pressure or agitation state.
- Held output is a settled recurring grove. `Snip` has no attack-plus-loop
  marker, so that buffer excludes the initial isolated strike. Playback
  retains the current sample API.

## Controls

| Control | Low → high |
| --- | --- |
| STRIKE | Broad bat-like contact → short bright axe-like contact |
| TRUNK | Tight lighter wood → dense hollow wood and cavity |
| FOG | Light source loading and clear travel → heavier loading and broader arrivals |
| AGITATION | Sparse soft replies → faster, firmer hoots, barks, and restrained screeches |
| GROVE | Compact local exchange → spaced propagation and longer conversation |
| HOLD | Finite strike and decay → explicitly powered recurring strikes |

AGITATION zero disables calls. This leaves tuned wood and passive fog
available for direct playing and for stability comparisons.

Each tree has four wood modes and two coupled cavity modes. Neighbor travel
is approximately 65–575 ms; each acoustic passage retains at most 0.675 of
its wave amplitude. A finite one-shot allows twelve calls globally, four
per owl, and at most two generations of vocal propagation. Count and scalar
vocal-energy budgets represent the animal source separately from passive
acoustic circulation.

Separate radiation gains scale responding wood and traveling atmosphere at
the output pickup, making those paths audible beside the original strike.
The grove circulates its internal wave amplitudes through the modeled
coupling and bounded sources. Output balance changes independently of the
passive energy and behavioral budgets; vocal audio enters the acoustic
links at their wave scale while call events retain their behavioral strength.

HOLD zero gives one primary strike. Above 0.20 it introduces a finite
gesture sequence; at 0.99 and above it returns a recurring buffer. The
loop uses a causal response schedule learned from the behavioral model,
checks wrap refractory intervals and budgets, and prerolls the acoustic
state for four cycles. The predecessor used for seam measurement is
separately rendered. Nominal one-shots span roughly 5.8–8.7 seconds, with a
10-second diagnostic maximum.

## Development checks

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.MurkTest
./gradlew --no-daemon test
```

The Murk tests inspect dry render diagnostics rather than relying on final
loudness processing to conceal behavior. Coverage includes deterministic
samples and event order, source-tree loading before the first arrival,
direct-root tuning, delayed propagation, passive decay, owl causality,
response budgets, call-to-fog coupling, physical velocity, macro travel,
patch/recipe round trips, pitched routing, and recurring-loop boundaries.

Diagnostic controls are internal implementation tools and are not added to
the product's macro surface. Generated audition files are ignored by Git;
the generator, documentation, and tests are the reviewable source.

The implementation pass on 5 October 2026 measured direct-root error below
0.014 cents in the selected note/material/loading cases, a 6.9% change in
the first 50 ms of the isolated source when FOG moves from zero to one,
and a 74.8 dB passive drop after eight seconds in the tested extreme.
Representative held seams passed the existing `1e-3` metric using a genuine
preceding cycle. These measurements describe the test cases, not every
possible patch or a listening verdict.
