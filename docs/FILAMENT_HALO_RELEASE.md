# CURL independent halo release, prototype 1

The characteristic gesture is **strike → sustain → release the body → let the halo linger**.
This round captures one model-3 CURL voice at C4 with one excitation strength.
It does not change the established THREAD/PULSE/CURL instrument makers or defaults.
Source APIs were read at `22a6b40055192504f91a5a58f78e0ab01b53871e`.

## What is built

`FilamentHaloKeys.capture` runs the existing held network once. It retains the
actual quiet onset and the same accepted sustain cycle as `FilamentKeys.note`.
The body is the weighted through collector. The halo is the remaining weighted
spill collector, which contains escaped branch sound and its modal response.
Both parts share the source clock, loop marker, FIR and output gain. No part
is normalized, re-excited or independently loudness-matched. Their sum recovers
the ordinary mixed capture within Float rounding.

```text
one CURL network, periodic drive from quiet state
    ├─ weighted through collector ─ body PCM ─ short key-up fade ─┐
    └─ weighted spill collector   ─ halo PCM ─ longer key-up fade ├─ sum
                    same onset, FIR, gain and loop clock         ┘
```

This is a **release envelope over captured periodic components**, not a solver
that stops injection and computes the subsequent unforced physical response.
The halo keeps the captured sustain's motion while it fades. Listening must
decide whether that is musically convincing before broadening the feature.

## Playback and package contract

| Surface | Prototype behavior |
| --- | --- |
| Capture | CURL model 3 only; other patches/models are refused explicitly. |
| A control | One ordinary mixed sample, whole-note release 0.18. |
| B experiment | Two overlapping keygroups, body release 0.18, halo release 0.85. |
| Phone sidecar | Opt-in v3, `stackZones: true`, required release on each zone. Ordinary packages retain their exact v2 shape. v1/v2 overlap stays first-match. |
| Phone trigger | `KeyHit.resolveAll` selects each component's velocity window. Identical sample lengths, loop markers and pitch ratios are required; a missing/incompatible component silences the whole note. |
| Native playback | Existing `NativePads.noteOnLayers` queues the components atomically at one frame; each voice receives its own `stopVoice` fade. No JNI or DSP engine API change. |
| Reference player | Same resolver; one key press counts as one logical note. Stealing/choking removes all components of that note. |
| MPC 2 XPM | Separate `VolumeRelease` per overlapping instrument. |
| MPC 3 XTY | Separate zone amp-envelope `Release.value0`; global amp envelope remains unlinked. |
| Voice-mode rewrite | `InstrumentStore.toProgram` preserves overlap opt-in and both zone releases. |

The reference/phone fades interpret these values as seconds, with the phone's
existing 1 ms minimum. MPC writers retain their existing raw parameter mapping.
**Exact MPC durations and simultaneous playback of this exported pair require
hardware validation.** The harvested `Bass-MPC3 AM Upright Bass.xpm` contains
23 filled keygroups, 27 overlapping pairs and distinct per-zone releases;
this supports the structure, not a calibrated seconds-to-MPC envelope law.

Stacks are limited to four simultaneous components by the shared resolver.
The prototype uses two. Eight key presses remain eight logical notes; native
playback consumes two sample voices per press, and the existing native
32-voice pool also contains release tails. Sustained overload/tail stealing
on a physical phone remains part of admission before a full bank rollout.

## Reproduce and judge

```sh
./gradlew :shell:generateFilamentHaloAudition \
  -PfilamentHaloOutput=/absolute/fresh/output/directory
```

The task writes both MPC formats, phone sidecars, PCM24 WAVs with sampler sheets,
the complete original patch, two A/B performances, a portable listening page,
hashes and a downloadable ZIP. Zones cover MIDI 54–66 around the C4 source;
this experiment deliberately does not stretch one source across the keyboard.
The performances load the actual exported PCM24 through `InstrumentEngine`,
hold beyond the first wrap, then release. Host velocity is 0.8 in both takes.
The held-prefix comparison is made at unchanged gain. The manifest records
the actual note-off frame, reconstruction error, tail RMS and capture time.

Judge whether the halo belongs to the original strike, whether the body lets
go clearly, and whether a lingering halo obscures the next note. The owner
has not yet accepted the sound. Physical phone and MPC tests remain open;
the generated manifest keeps those flags false.

For phone testing use a build containing this change and retain each program's
sidecar next to its complete track-data directory on the instrument shelf.
An older build cannot read the v3 experiment. The existing synth MAKE/PREVIEW
workflow continues to create the established mixed instruments.

## Cost and cache boundary

Capture is an offline preparation operation, not a realtime macro response.
It runs the same convergence work once and adds one direct-component history
and FIR pass. The halo is a residual of the conditioned mixed and direct
captures, so it requires no second network run. The measured workstation cost
is recorded by the audition task; it is not a phone timing claim.

The prepared pair is the reusable object. A future UI cache must key it by
the complete patch (model, voice, macros, velocity, seed/profile/geometry),
absolute MIDI, capture version and sample rate. Keep one active pair and
invalidate on those inputs; do not cache by voice name alone. This round
does not add a UI cache or new macro. Release edits operate on descriptors
and playback envelopes, so they require no PCM render or re-export.

## Evidence ledger

- **Verified in source:** legacy null overrides preserve writer output paths;
  stack triggering uses the existing atomic native command; v3 decoding requires
  explicit stack and zone-release fields; older formats remain readable.
- **Tested:** common-gain reconstruction and unchanged mixed PCM; deterministic
  capture and cancellation; paired velocity selection; missing/incompatible
  component refusal; independent fades; logical-note stealing; sidecar round
  trip and both writer envelopes. The native harness exercises the same paired
  looping/independent-stop commands used by the phone.
- **Measured:** exported-file A/B prefix and tail values, frame markers and
  workstation capture time live in the generated `manifest.json`.
- **Proposed:** longer halo release improves CURL's hand-lift gesture.
- **Unverified:** owner listening, physical phone timing/overload behavior,
  MPC import and exact MPC envelope durations. No listening claim is made.

## Why it is built this way

- One network run keeps the body and halo attached to the same strike.
- Shared filtering and gain preserve the balance; independent leveling would
  change the instrument before the player even releases a key.
- Separate keygroups keep velocity slots available for later touch layers.
- An explicit sidecar opt-in keeps older overlapping instruments predictable.
- The risky choice is fading a baked halo loop. The A/B is the cheapest way
  to decide whether that gesture deserves the full bank and phone controls.
