# Backroom boom bap study

Status: More Snap accepted by the user as the sonic baseline; MPC hardware loading
and factory-bank promotion remain pending. The new four-bar phrase awaits listening.
Repository baseline: `6e9959ac3a37b4a0a6167840b36a3453e6294761`.

The characteristic gesture is **heavy knock → papery crack → loose metallic shuffle → short dark room tail**.

## What changed

This is a SKIN kit and recording recipe, not a new engine. `BoomBapKits.parts()`
holds six fully resolved candidate patches: kick, snare, closed/open hats,
cross-stick and shaker. A02's lower velocity layers supply the ghost snare.
The remaining ten kit slots are empty. The source patches start from existing
SKIN presets, with a small number of named overrides. No factory preset changes.

SKIN SNARE now reads strike velocity. A softer stroke tilts its two body modes
using the existing contact law, reduces the wire excitation level and bandwidth,
and shortens the wire decay. The upper/body balance moves before the drum's
existing output stage. The wire envelope uses `0.55 + 0.45 sqrt(v)` times the
chosen wire decay; the excitation low-pass uses `14000 v^0.8` Hz and the wire
level uses `sqrt(v)`. Contact filtering fades out with `1-v`, so approaching
full strength does not abruptly switch the excitation bandwidth. These are **shape laws**, not measured wire mechanics.
The full-strength branch bypasses the new filter and keeps the old arithmetic,
noise seed and duration. A nonzero re-strike take seeds a fresh wire texture.

`Velocity.layerAt` can now re-render a synth articulation through its saved
`FxChain`. The rack sees the rendered source scaled by the requested relative
velocity (floor 0.05), then the result is peak-matched to the reference. This
lets saturation react to the strike while the sampler still owns playback
loudness. An empty rack is identical to the dry path. The full reference format
and the actual softer result must both match the reference channels/sample rate;
incompatible formats and captured audio keep the filtered-audio fallback.

Robin's velocity grid and KitBuilder's ghost layers now resolve the source's
brightness control even with a rack present. Recipes with additional boards,
pedals, layer banks or terminal fades stay on the audio fallback because this
path cannot replay their whole recipe. Legacy round-robin processing of wet
pads remains unchanged; this work changes wet velocity zones, not that path.

## Signal flow

```text
velocity → SKIN body and wire excitation → existing drum output stage
         → strike-level rack input → CRUNCH → TAPE → ROOM
         → peak match to reference → sampler velocity gain
```

CRUNCH supplies converter grain; TAPE rounds highs with small modulation;
ROOM supplies common early reflections and a short tail. These are existing
processors. Their settings in `BoomBapKits.coloration` are audition hypotheses.
CRUNCH's deliberate sample-rate reduction is marked by the saved recipe's alias
metadata. The source retains the existing four-times render/decimation path.
This is a recording-character analogy, not a model of any named product.

## Reproduce and listen

```sh
./gradlew --no-daemon :synth:generateBoomBapAudition
# Optional destination:
./gradlew --no-daemon :synth:generateBoomBapAudition -PboomBapOutput=/path/to/study
```

Output: `testkit/boom-bap-audition/` and `testkit/boom-bap-audition.zip`.
Open `index.html` locally; it embeds its manifest, so it needs no server.
The page plays matched copies. `native/` retains renderer output, `recipes/`
contains complete source/rack recipes, and `manifest.json` records articulation
strengths and the groove's timing. `metrics.csv` records duration, peak, mean,
power-weighted spectral centroid and host rendering times. All renders are
Kotlin, not the Python harness.

The two-bar groove is 88 BPM. Dry and processed versions share one event list,
per-part gains and velocity accents. Offbeat hats are slightly late, backbeats
are slightly behind the kick, and ghost snares have lower strike strengths.
Hat tails choke with a short fade. Individual audition clips are loudness
matched; each complete groove receives one gain, retaining its internal dynamics.
The six-pad XPM kit carries two soft variants (strike strengths 1/3 and 2/3)
beneath each full hit and editable recipes. The isolated listening comparison
uses 0.25, 0.6 and 1.0 to expose the ghost-note contrast more clearly. Hardware loading remains unverified.

Listen for the snare's new ghost beside the old filtered version; the kick's
knock after coloration; hat accents surviving darkening; and the room leaving
space between hits. Factory promotion waits for the MPC check. The user accepted the More Snap sound after auditioning successive revisions.

## Cost and limits

There is no solver. Snare work is linear in sample count, with two modes and
one additional one-pole filter for soft wire excitation. The audition caches
identical `(part, velocity)` hits for each groove. Production velocity stacks
share a single delivered-format probe, but each layer still renders and runs
the rack separately. The target is tens of milliseconds per changed pad on a
phone; warmed host medians are evidence about this host only. ROOM may exceed
that budget and needs separate device profiling before any latency claim.

The main sonic risks are a ghost snare becoming too dull, loss of kick attack
through the coloration chain, and room tails masking the shuffle. The old/new,
dry/processed and three-strength comparisons make those decisions audible.

## Verification ledger

- **Verified by source:** existing drum/rack APIs and patch serialization at the baseline above.
- **Implemented:** soft snare excitation, rack replay for velocity layers, six-pad candidate and audition generator.
- **Measurements and tests:** recorded in the As built section below after running the Kotlin checks.
- **Heard:** user accepted More Snap as the sonic baseline. No assistant listening claim. The new phrase, phone latency and MPC hardware loading remain open.

## Why it is built this way

- The wire layer changes separately from the body so a ghost retains the same drum identity.
- The hard-hit branch preserves existing saved presets and their WAVs.
- The rack receives the strike before level matching so a soft hit can distort less.
- One recipe table feeds the kit and audition, avoiding two drifting sets of sound settings.
- The risky decision is how much wire energy a ghost loses. The listening comparison includes the previous behavior.

## Initial implementation validation

The focused Kotlin/production-integration run passed **132 tests** (45 synth,
87 shell), with zero failures/errors/skips. It includes `BoomBapTest`,
`StrikeVelocityTest`, `RestrikeTest`, `VelocityGrooveShuffleTest`, `RobinTest`,
`RobinRestrikeTest` and `KitBuilderTest`. Existing full-strength SKIN golden
hashes remain exact. The snare's new deterministic canary also passed in the
preceding run of `DeterminismTest`.

`generateBoomBapAudition` produced 40 native/matched pairs, twelve complete
recipes, the event manifest, CSV measurements and an XPM kit. All 80 WAVs were
checked for mono 44.1 kHz headers; the XPM parses and the 116-entry ZIP passes
its integrity check. Chromium loaded all 40 players, filtered the ten snare/
groove comparisons, decoded the groove WAV and reported no script errors.
The managed browser blocks local file URLs, so that browser check used a local
HTTP server; the page itself has no fetch/server dependency.

Warmed host medians (Temurin 21.0.13, seven timed renders after warm-up):

| Part | Dry ms | Processed ms |
|---|---:|---:|
| Kick | 7.35 | 22.50 |
| Snare | 3.28 | 17.14 |
| Closed hat | 2.27 | 16.86 |
| Open hat | 17.20 | 33.98 |
| Cross-stick | 1.90 | 14.65 |
| Shaker | 2.84 | 17.91 |

The candidate dry snare's power-weighted centroid measured 223 / 404 / 996 Hz
at 0.25 / 0.6 / 1.0 strike strength (`metrics.csv`, Kotlin FeatureExtractor).
The dry and processed grooves both last 6.955 seconds, including the tail;
raw peaks are 0.420 and 0.433. These are numerical descriptions, not listening
judgments. The wire-decay test isolates the wire layer: the unchanged body
can represent more of a ghost's total late energy as its attack gets darker.

The full unfiltered JVM regression gate passed: **8,866 tests** across nine
modules, with zero failures/errors/skips and Gradle exit status 0. The final
run completed in 57m 42s, reusing successful unchanged module results. A
separate 19-test integration run also passed for CISTERN, MURMUR and OPEN SKY;
those tests now verify exact native-articulation-then-rack output. Listening,
phone latency and MPC hardware loading remain unverified.

## Accepted More Snap baseline

The published audition was refined in two recipe passes and accepted by the
user on 2026-11-07. The repository now uses those exact source and per-pad
rack settings. Twelve saved recipe fixtures and their full-hit PCM24 SHA-256
hashes pin the accepted dry/processed sound, rather than re-authoring it from
a description. The engine and velocity algorithms are unchanged in this pass.

The shared rack uses CRUNCH BITS .16 / RATE .29 / TONE .43 / GRIT .34, TAPE
WOBBLE .09 / DRIVE .60 / AGE .70, and ROOM SIZE .14 / WALLS .40 / DISTANCE .28.
Only the snare gets SPIKE ATTACK .42 / SUSTAIN .50, CRUNCH TONE .55, and TAPE
DRIVE .50 / AGE .60. Its SNAP .70 / SIZZLE .44 / PUNCH .76 provide the sharper
edge while DECAY .40 / RATTLE .62 retain the pushed version's tail settings.
`BoomBapKits.colorationFor` feeds both the kit and the audition, including all
velocity layers. The kit is named `SnipSnap Backroom Snap`.

The accepted two-bar groove remains unchanged. A separate four-bar dry/wet
phrase moves a kick in bar two and adds a quiet pickup; bar four answers with
cross-stick and two rising snare strokes into the turnaround. All events are
recorded in `manifest.json` as `phraseEvents`. Each phrase has one common
render gain and its own matched listening copy, preserving internal accents.
The generator now writes 42 native/matched pairs.

Why: preserve the user's chosen sound exactly, let a small rhythmic variation
carry the development, and keep snare attack shaping separate from the other
five pads. More fills would risk burying the pocket.

Integration validation passed: **9,038 JVM tests** across nine modules, zero
failures/errors/skips, Gradle exit status 0 (1h 30m 41s). All ten Backroom
checks passed on the merged base, including the twelve accepted PCM24 hashes.
The generated audition has 42 native/matched pairs. Its 92 retained audio and
recipe files and 20 MPC program/sample files match the accepted baseline
exactly. Both four-bar renders last 12.409 seconds including decay; native
peaks are 0.417 dry and 0.431 processed. The ZIP passes its integrity check.

The XPM declares velocity zones 1–41, 42–84 and 85–127 on all six pads;
both hats share mute group 1. The user has not yet tested it on MPC, so
hardware loading, ghost-velocity feel, hat choking and kick/snare balance
remain pending. Factory registration remains absent. The new phrase is
published for listening; browser playback was not reverified in this pass.
