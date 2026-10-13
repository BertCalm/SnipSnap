# ONSET — first listening experiment

ONSET tests a continuous blend between SnipSnap's electronic and physical
drum sources. The first scope is two voices: KICK and HAT_CLOSED. Five
positions per voice and three copies of the same two-bar beat form the
listening gate. The middle should earn its place by sound.

## Rendering

`Onset.render` uses the unfinished THUMP and SKIN source cores at 4× the
44.1 kHz output rate. Their original public render paths retain their
existing mappings and finishing. ONSET suppresses the kick sources'
individual clicks, blends the bodies with cosine/sine gains, and adds one
shared transient. A common body shelf and PUNCH shaping precede a single
decimation and final safety/tail handling.

TUNE resolves to the same 45–62 Hz fundamental for both kick sources.
Hats have different natural excitation and resonance frequencies; TUNE
applies the same relative ±4-semitone shift to both. Both sources in each
voice receive the same decay T60. SNAP, BODY and EDGE author their
respective attack, low-body and spectral character. BLEND is the only
parameter changed within each first-listen comparison row.

Fixed source calibration and output headroom preserve the blend's native
level behavior. Equal-power coefficients do not guarantee constant
perceived loudness when the sources share correlated fundamentals.
The audition supplies both native output and copies matched by the RMS
of their loudest 200 ms window. Matching applies one gain to a complete
clip, including the full beat.

THUMP is an analog-style oscillator/envelope engine; this experiment
uses that electronic character rather than claiming detailed circuit
simulation. SKIN supplies modal membranes and resonant noise. The first
pass measures the musical value of the blend before expanding its sources.

## Reproduce the audition

```sh
./gradlew :synth:generateOnsetAudition
```

Output: `testkit/onset-audition/`, containing ten dry hit comparisons,
three identical-pattern beats at 104 BPM, raw/matched 24-bit WAVs, the
listening page, a manifest, resolved settings, source hashes and a ZIP.
The beat uses only these two voices. All audio comes from the JVM renderer.

The page begins with level matching enabled, plays only on request,
supports stopping all audio, and keeps listening notes in the browser.

## Acceptance and next scope

Numerical checks cover reproducibility, bounded finite output, coherent
source controls and the existing drum engines. Sonic acceptance belongs
to the listening review: useful endpoints, a cohesive midpoint and
audible control over the transition.

This is a renderer and audition prototype. Android picker registration,
typed pad recipes, additional voice families and live voice interaction
remain subsequent integration work. Finished blends and motion rendered
inside a hit can follow SnipSnap's sample export pipeline; interaction
between separate rearrangeable voices requires a shared runtime or a
rendered phrase.
