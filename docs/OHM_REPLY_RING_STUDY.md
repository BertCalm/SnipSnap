# OHM Reply and Ring listening study

Status: a finite-note listening prototype. The owner found Pluck easier to
understand and found little useful change in the Grain and Memory examples.
The previously liked direction is the model-2 companion and shared-body depth.
This study develops that direction before adding or promoting app controls.

The characteristic gesture is **pluck → companions answer → shared resonance fades**.

## What is being tried

REPLY sets the spacing of the existing five companion entries, from a tight
cluster through the existing timing to separated answers. RING sets the two
companions' damping independently of the main string's decay. These are
controls for OHM's existing instrument, not a new engine. PLUCK already covers
picked-string damping and TRIO already covers articulated coupled players;
OHM's distinction remains its string, whistle-like and glass-like companions
sharing a bridge.

The entry schedule is an authored playing gesture. The string propagation,
losses and reciprocal bridge are actual DSP mechanisms. Neither the controls
nor their numerical differences establish listening approval.

## Boundaries and signal flow

The Kotlin prototype lives only in `synth/src/ohmReplyRingStudy/`. Production
renderers, saved recipes, factory presets, GATHER and held instruments retain
their existing source. It derives from `OhmV2.kt` at repository commit
`482c55a146b1569ba9ccad27e4ebd274ad398df4`, unchanged from the accepted study.

```text
one pick ──────────────────→ main string ──┐
five entries [REPLY] ──────→ companions ───┼─ reciprocal bridge → acoustic readout
                            loss [RING] ──┘                    → raw/matched WAV
```

| Control | Middle | Changes | Listening comparison |
| --- | --- | --- | --- |
| REPLY | .5 | Companion entry times and the delay between their two sources | Tight / Natural / Spaced, with RING fixed |
| RING | .5 | Whistle and glass round-trip loss | Short / Medium / Long, with REPLY fixed |

At REPLY 0/.5/1, the first entry is .06/.12/.18 seconds and the last is
.51/1.92/3.33 seconds. Minimum spacing is 100 ms, so finite glass strokes
cannot collapse into one louder strike. Overlapping whistle pressure uses
`1 − product(1 − envelope)` with each envelope bounded to 0..1. This keeps
pressure bounded without rescaling the finished audio.
Whistle excitation uses the accepted middle damping as a fixed pressure-unit
conversion, so increasing RING does not simultaneously turn down the input.

RING uses logarithmic interpolation through whistle T60 values .18/1.0525/2.8
seconds and glass values .12/.83/2.2 seconds. Its middle matches the old side
damping at LENGTH .65. The main string retains its own LENGTH law. Changing
a companion's loss can affect the coupled string; independence means separate
damping controls, not disconnection from the shared body.
The inherited feedback ceiling can shorten the high RING target in the upper
register: at C5 the uncoupled estimate is about 1.62 seconds for the whistle
and 1.14 for glass. The page uses C4; requested T60 metadata is not a measured
radiated decay claim.

The companions occupy higher notes. Once the pluck fades, a mixed-signal pitch
estimate can follow the octave companion. This is present in the accepted
reference: at 2.5 seconds its octave/root band-energy ratio is about +14.28 dB,
essentially identical to the study midpoint. The study checks the main port's
native pitch separately, requires the mixed attack to retain the played note,
and labels later estimates as root, octave or third harmonic while rejecting
off-series pitch. It does not claim that the perceived tail always remains C4.

The seven-second buffer is common to all five study sounds. The source
horizon accommodates the last pressure envelope and glass stroke, leaving
room for natural decay. The middle WAV is shared between the two comparisons.
The original model-2 step-2/3 reference recordings remain separate, unchanged
anchors; they are not claimed to isolate one new control.

## Reproduction and evidence

```sh
./gradlew --no-daemon :synth:checkOhmReplyRingStudy
./gradlew --no-daemon :synth:generateOhmReplyRingAudition
```

Replay a downloaded study score with the same generator task and
`-PohmReplyRingReplay=/path/to/score.json`,
`-PohmReplyRingReplayOutput=/path/to/result.wav`, and optional
`-PohmReplyRingReplayMode=raw|matched`.

The isolated compilation uses the real main DSP toolkit and does not compile
the large engine-test library. The study checks deterministic output, exact
solo and uncoupled foreground preservation, decoded pitch, bounded pressure,
state headroom, companion timing and decay. The generator exports complete
raw/matched WAVs, replayable study scores, source hashes and diagnostics.
Study scores are not production app patches.

Per-note work remains three running ports and the existing bridge; the two
new laws prepare a small fixed schedule and loss coefficients once per note.
There is no new iterative solver or unbounded source count. Host render cost
must be measured on the no-taps playback path before reporting performance;
it is not a phone-latency claim.

## Why it is built this way

- The original pluck gives each example a recognizable attack and root.
- Entry spacing supplies a clear timing gesture using the existing companions.
- Independent companion damping gives the tail its own role without stretching
  the main string's intrinsic decay.
- Keeping the study outside production protects saved sounds while the owner
  judges whether either addition is useful.

Verified: source architecture and existing toolkit at the named commit.
Proposed: the control mappings, pressure-overlap law and listening usefulness.
Measured so far: 36 coordinated renders passed the standalone study checks,
including 24 C3/C4/C5 × coupling × REPLY/RING corners. The uncoupled primary
and the accepted solo prefix remain exact. In the sampled mixed window starting
at .75 seconds, worst error was 15.09 cents at uncoupled C5 and 1.18 cents in
the coupled corners. Delivered
companion energy centroids moved from .427 to 1.086 to 1.734 seconds across
REPLY. Glass retention 0.43–0.53 seconds after the last stroke increased from
−95.52 to −41.73 to −25.27 dB across RING, relative to its early post-stroke
response. These establish timing and damping changes, not musical approval.

Heard: the owner's earlier Pluck/depth preference only; these new examples
have not been heard. Final export, replay and browser receipts accompany the
published study.

The no-taps playback path was measured on JDK 17 with two warmups and three
timed calls for each of the five C4 settings. Median thread CPU times were
94–111 ms per seven-second note, and median wall times were 94–113 ms on this
host. This is a small host timing sample, not a measured phone response.

The first complete export passed: five study notes and two unchanged accepted
references, fourteen raw/matched WAVs and seven complete replay scores. Every
raw and matched score reproduced its PCM16 WAV byte for byte. In decoded,
level-matched audio after 300 ms, adjacent REPLY settings redistributed
10.5–11.6% of normalized temporal energy; adjacent RING settings redistributed
14.8–22.0%. These are time-envelope measurements, not waveform-null distances
or a claim that the owner has heard a useful difference. The downloadable
source snapshot includes the standalone checks and this design note.
