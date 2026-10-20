# Scanned-chain first audition

This is a finite, dry, mono listening experiment for adapting XO-Obscura's
scanned-synthesis idea to SnipSnap. It lives in the synth module's test sources
and does not register a product engine, patch format, SYNTH picker entry or
MPC instrument. The sound still needs a listening decision.

## Listen

The [private published audition](https://snipsnap-scanned-chain-audition.bertcalm.chatgpt.site)
opens with Explore parameters. Five discrete settings for each of ten controls
are actual renders from the same prototype. A uses the authored default for
most controls; BLOOM uses an explicit no-force reference so the delayed drive
can be compared with its absence. B starts at the authored default and plays
the selected setting. The slider selects those recordings
and does not run live synthesis. Frozen / moving retains the original nine
comparisons.

```text
./gradlew :synth:generateScannedChainAudition
./gradlew :synth:generateScannedChainSweeps
python scripts/package-scanned-chain-audition.py testkit/scanned-chain-audition
```

Open `testkit/scanned-chain-audition/index.html` with its WAV folders beside it,
or open the generated `testkit/scanned-chain-listen.html`, which embeds all
listening audio and needs no server or network. Browser policies can restrict
opening local files; the folder can also be served by an ordinary static server.
The ZIP contains the page, WAVs, source previews, manifest, implementation
snapshots and a patch for reviewing the experiment against its repository base.
The hosted download stores the same samples in lossless 24-bit FLAC to fit the
hosting asset budget. All 139 hosted clips decode to exactly the rendered WAV
PCM; local generation and packaging retain WAV files.

Nine comparisons cover PLUCK, METAL and BLOOM at C4, an original drawn line,
three source-derived shapes, and PLUCK at C3 and C5. The room source comes from
the repository's real `reference/live3 room take.wav` recording. The kalimba
and nylon sources come from existing synthesized melodic-kit WAVs and are
labelled as synthesized sources.

A freezes the entire chain at its initial shape. B evolves that same chain.
They share scanner pitch, initial displacement, velocity and amplitude envelope.
The frozen control disables the physical driver; BLOOM's moving version has a
finite changing drive. Switching A/B preserves the playback position, while
Restart plays the current take from its attack. There is one player and no
autoplay or sustain-loop mode. Listening notes remain in the browser and can
be downloaded as JSON.

Matched comparisons give each take a constant gain to equalize whole-file RMS,
targeting 0.12 with a shared lower target if either take would exceed 0.90 peak.
Silence is not amplified. This is not perceptual loudness matching: physical
motion can redistribute energy across time and frequency. Raw files preserve
relative render levels with one shared attenuation across the entire pack only
if required to fit below 0.90 peak. The manifest retains pre-export metrics,
exported metrics, gains, source provenance and audio hashes.

Start by deciding whether B adds useful character to A. Then compare the drawn
and source-derived shapes: do their differences remain useful after the chain
starts moving? The low/high PLUCK pair tests how that character translates
across pitch. Numerical activity is evidence of a change, not a sonic verdict.

## Isolated parameter sweeps

SHAPE, MOTION, DAMP, BEND and BLOOM are the main controls. More parameters
contains TUNE, ATTACK, RELEASE, MEMORY and DRIVE RATE. Every control has five
settings and a marked default. Exact physical configuration, initial shape,
seed, source hashes and measured exported PCM levels are saved under
`sweeps/manifest.json` and included in the combined page manifest.

Numerical sweeps change exactly one configuration field; SHAPE changes only
the input array. Different controls use suitable fixed contexts. Most use a
passive bright shape; BEND uses an explicitly rough trace to expose cubic
spring behavior. BLOOM uses a quieter bright strike with a weak initial root,
so the existing low-mode force can add a delayed body. Its fixed damping and
centering help the strike fall away; only drive amount varies within its five
steps. DRIVE RATE retains the original factory BLOOM source and settings.
Selecting Factory BLOOM in the SHAPE group changes the initial waveform only,
without switching on a driver.

The earlier BLOOM example already had a strong root, and comparing its 0.8
default with 1.0 was especially subtle. The revised example keeps the same
physical driver, starts B at 0.8, and labels A as BLOOM OFF (0). Listen around
one to two seconds after the attack. Its late-to-early RMS contrast changes by
about 8.3 dB between no force and the default, including after constant level
matching; the earlier context changed by about 1.1 dB on the same windows.

MOTION scales physical rates together while scanner pitch stays fixed. DAMP
removes velocity; it can leave a settled pitched waveform when centering is
active. BEND is nonlinear stiffness, rather than musical pitch bend. BLOOM
adds a finite changing force and does not supply infinite sustain. ATTACK
reveals already-running mechanics; RELEASE uses a passive context so its
envelope setting cannot also change a driver window. MEMORY adjusts restoring
centering; DRIVE RATE changes force frequency with its amount fixed at 0.8.

Raw mode shares any required PCM attenuation within each five-setting group.
Matched mode uses one constant gain per clip and a group-shared RMS target
that keeps all peaks below 0.90. Matching can conceal level or decay changes,
so compare both modes. The original nine-pair audio and prototype source
remain unchanged; sweep generation checks their hashes.

## Model

`ScannedChainPrototype` simulates 128 equal masses in a periodic ring with
linear and cubic neighbor springs. Weak centering springs attach masses to
their authored resting profile. One musical note is one fresh deterministic
render; the scanner's speed sets its nominal pitch independently of mechanical
motion. Supported experiment scanner notes are MIDI 36 through 96.

The solver uses simultaneous velocity-Verlet updates with split exact viscous
damping. It measures stored mechanical energy, damping loss and external work.
Conservative tangent-stiffness and snapshot-bandwidth guards reject an invalid
state. Initialization removes shape DC and scales down overlarge shapes once;
the running chain is not clipped, normalized or mixed with an additional root
oscillator. BLOOM receives an explicit finite time-varying force.

Successive complete chain snapshots are projected into pitch-limited spatial
Fourier coefficients and interpolated with velocity-aware cubic Hermite
interpolation. The audio scanner runs at the shared 4× rate and uses SnipSnap's
decimator to produce 44.1 kHz audio. A conservative sideband allowance protects
the authored range; this is not a proof of strict band limitation for arbitrary
nonlinear modulation. Actual mobile render latency and a stricter aliasing
measurement remain work for a product implementation.

The room source uses a fixed 20 ms window. The two tonal synthesized sources
use a single cycle from a confident source pitch estimate, preventing an
arbitrary multi-cycle window from moving their strongest scanner harmonic into
a different register. Extraction uses arithmetic mono downmix, equal-width
averages into 128 points, a Hann taper, mean removal and an initial peak bound.
Exact input and physical displacement arrays are embedded in the manifest with
source paths, file hashes, frame windows and tonal pitch estimates. These are timbral
imprints. They do not transfer speech, source chronology, source envelopes or
original pitch. A weak or absent first spatial harmonic can make the audible
note differ from the nominal scanner rate; measured pitch estimates are
recorded without replacing the source with a separate oscillator.

## Verification and next decision

```text
./gradlew :synth:test --tests com.snipsnap.synth.ScannedChainPrototypeTest
./gradlew :synth:test --tests com.snipsnap.synth.ScannedChainSweepGeneratorTest
```

Focused tests check passive energy and silence, independent deterministic
renders, cycle-shape evolution beyond gain or polarity, scanner tuning and
missing-fundamental integrity, independent pitch and mechanical motion,
bounded physical settings, and the frozen state. The listening page is tested
separately for playback, position-preserving A/B, level selection, source
previews, responsive layout and notes export.

The first local run passed all nine focused JVM tests with zero failures or
skips. The parameter update adds six focused checks for one-field isolation,
deterministic source data, safe constant gains, silence preservation and BEND
activity beyond gain, plus a BLOOM regression requiring at least 6 dB of
gain-independent late-swell contrast against its no-force reference.
All fifteen pass. Independent PCM decoding checked all
36 initial raw/matched 24-bit WAVs, their
hashes, peak bounds and equal matched-pair RMS. Maximum relative mechanical
energy accounting residual across the audition was approximately 0.000364.
The sweep update additionally checks all 100 new raw/matched exports, their
hashes, group-shared levels, matched RMS and one-field configuration isolation.
Browser checks played every WAV and the embedded page's first and last pairs,
and checked A/B position, source playback, level metrics and notes downloads.
Direct local-file navigation is restricted by the execution browser's policy;
these checks used local HTTP with byte-range support. No Android or MPC device
checks were run for this experimental source.

If the sound earns a product engine, add a versioned patch carrying the initial
shape and configuration, native excitation velocity, presets and the existing
recipe/pad/export integrations. Continuous source excitation, stereo preview,
and held keygroups each need their own contract and verification. An evolving
chain is not automatically periodic, and a LOOP classification does not create
sustained playback in an MPC drum program.
