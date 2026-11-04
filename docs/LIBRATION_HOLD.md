# Libration: sustained instrument

Status: implemented and published. The complete JVM baseline and current
integration checks pass; their source revisions and scope are recorded below.
The owner accepted finite phone/MPC behavior on 2026-10-31 and authorized this
milestone. New HOLD listening and phone/MPC acceptance remain pending.
Implementation base: `c65d2521f5391f8443182a06bfbfff2eb8360459`.

The characteristic gesture is **strike → gather into a recurring orbit → sustain
→ fade after key-up**.

## Direction and scope

The strong direction periodically traverses a computed trajectory's descriptors
forward and backward, preserving particle identities while fitting oscillator
phase to a whole-number cycle. The descriptors are literal CR3BP readouts; their
repeated traversal is a driven musical adaptation, not a claim that an arbitrary
gravitational orbit has closed. A continuous exciter and modal bank start from
rest and settle before the sample's loop marker. No rendered tail is recycled
as recurrence evidence.

| Direction | Gesture and sibling difference | Risk and cost |
|---|---|---|
| Strong: PORT with periodic readout | Retains Libration's changing wood/glass cloud in a held key; TIDAL demonstrates descriptor reconstruction, CREASE demonstrates onset-plus-loop export | Moderate phase/state closure risk; one instrument milestone |
| Alternate A: FREEZE the readout | A stationary pose sustains cheaply, but loses the orbit's spectral movement | Low numerical risk; weaker gesture |
| Alternate B: solve literal periodic orbits | Shooting methods could close selected CR3BP families, but do not give every independently initialized particle one common musical period | High solver and pitch-fitting risk; multiple research rounds |

This extends LIBRATION. It does not add another engine or expand the approved
twelve-preset roster. Existing finite rendering models, saved JSON and PCM
remain the compatibility reference. A separate `heldModel` identifies a newly
supported HOLD recipe; absent metadata continues to mean finite only.

## Signal flow

```text
source patch: REGION / WANDER / NUDGE / SWARM
    → CR3BP trajectories at 500 Hz [bounded session cache; source model + controls]
    → periodic forward/back descriptor traversal [held rendering version]
root + touch → whole-cycle carrier and excitation-phase fitting
    → continuously running cloud + original 14-mode sympathetic-bank design
    → band limit → decimate → DC removal → one common note gain
    → real onset and settling prefix | measured sustain cycle
    → phone/MPC keygroup player → key-up volume release
```

## Control and version contract

| Control | Held behavior | Required proof |
|---|---|---|
| REGION | Selects/blends the original physical populations | Family/identity preservation and audible movement |
| WANDER | Changes physical initial displacement | Changed periodic descriptor field |
| NUDGE | Changes the physical initial velocity and departures inside each traversal | Changed excitation history, including DEPARTURE |
| SWARM | Adds the original stable particle identities | Voice cap and bounded drive allocation |
| RESONANCE | Changes the original sympathetic-bank decay and send | Energy, timbre and state recurrence |
| ROOT | Fits the loop around the requested note | Root fitting below 0.1 cent; detector check on the full sound |
| TOUCH | Preserves source excitation/spectrum and native energy | Level-matched articulation and ordered soft/hard energy |
| LENGTH | Retains the finite source gate for a return to one-shot mode | Saved-field recall; disabled during HOLD |
| HOLD | Binary finite/recurring sustain with an explicit supported `heldModel` | Legacy refusal, JSON identity, real continuation seam |
| RELEASE | Instrument-player amplitude release after key-up | Phone player preview and exported program-envelope agreement |

The existing `KeyNote`, `Keys.seamError`, `OneNote.writePackage`,
`KeygroupProgram.volumeRelease` and SYNTH `HeldSpec` paths provide the sample
and instrument landing. The sample contains its onset and a loop to its end.
Release is the player's volume envelope; offline samples cannot run a fresh
physical trajectory after an arbitrary live note-off.

`LibrationPatch` keeps finite source models 1..3 and omits `heldModel` from
legacy finite JSON. HOLD one requires held model 1 and finite source model 3;
raw JSON rejects fractional HOLD before narrowing to Float. Returning to NOTE
keeps the saved source fields and calls the original renderer. The separate
versioned keyboard recipe can also make held keys directly from a finite
model-3 patch, without changing that source patch.

The five timbral defaults remain the finite voice defaults. GATHER uses
`0/.48/.12/.55/.40`, TWINS `1/.40/.14/.95/.53`, and DEPARTURE
`.5/.52/.72/.78/.66` in REGION/WANDER/NUDGE/SWARM/RESONANCE order. A new patch
defaults to ROOT 60, TOUCH .8, LENGTH 6 seconds and HOLD zero; the keygroup
release defaults to .6 seconds and spans .1..1.5 seconds. Presets retain their
authored roots, lengths, touch and timbral values.

## Proof and render budget

Prototype the loop/state-closure method using the Luthier Python harness, clearly
labelled as Python rather than Kotlin. The Kotlin renderer must then measure its
actual preceding and following cycles, phase closure, pitch, DC, raw/final peak,
level drift, cancellation and model/recipe compatibility. A doubled stored loop
does not prove recurrence. Oversampled reference comparisons cover bright/high
notes. Tests must also exercise the phone player and keygroup read-back.

Physics is prepared once per bounded session and reused for pitch, touch and
resonance. Final preview audio has a byte budget. Keygroup zones render serially,
with cancellation and visible progress. Fresh multi-cycle rendering may take
seconds and must report its measured cost; recalled previews should avoid it.

Risks are a long first render, motion becoming too repetitive, loss of low-level
departure detail, modal or conditioning state failing to recur, and a key-up
fade being mistaken for a separately simulated release tail. The audition must
expose those behaviors before any new sustain preset is called approved.

## Implementation and measured handoff (2026-11-01)

`LibrationKeys` chooses a 3.8..4.2-second loop in multiples of 64 output frames,
fitting the root to integer turns below .1 cent. The six-second physical table
is sampled at 500 Hz. A raised-cosine descriptor traversal goes from time zero
to five seconds and back. Each cloud carrier and excitation pulse receives a
constant phase-rate correction so its integrated phase closes over that loop.
No oscillator, envelope or modal state resets at the wrap.

The exciter, fourteen-mode bank, output filter and decimator run continuously
from rest. Three actual cycles precede the retained fourth; a fifth continues
the circuit for recurrence verification. The exported key sample keeps the
onset and first four cycles. Pad HOLD takes only the recurring cycle and rotates
it to a quiet trigger, without fading or replacing repeated samples. The same
note gain applies to the entire attack and loop, with peak capped at .94.

SYNTH provides NOTE/HOLD, retains ROOT/TOUCH/LENGTH and the full versioned patch,
keeps HOLD through SCRAMBLE and navigation, and sends held pads as gated loops.
MAKE INSTRUMENT retains the attack and exposes .1..1.5-second release. Minor-third
sampled roots span up to twelve semitones either side of the saved root, clipped
to MIDI 36..84. Adjacent keygroups tile that span with at most one semitone of
sample transposition. One authored excitation touch is rendered per zone; live
key velocity changes playback gain. Optional velocity layers remain future work.

The screen cache retains at most 4 MiB of final loop audio and two physical
populations. Each keygroup-generation session retains one physical population;
zones render serially and cancellation interrupts the off-main render. Saved
recipes contain neither cache state nor measured timing.

The [held audition](https://libration-hold-audition.bertcalm.chatgpt.site)
contains ROUND KEYS, FINE KEYS and SPLIT PATH as three nine-zone instruments,
covering C3..C5, plus eighteen recordings. Long holds cross two actual sample
wraps before key-up. Long holds and keyboard phrases reload the exported PCM24
files through `InstrumentEngine`; authored-touch and release comparisons use
the same source and player. All listening WAVs use one monitor gain of .75.

Across those 27 exported zones, measured from source revision
`7d137063985c7f7dbb6c14cc0afada6508e9172d`:

| Quantity | Maximum |
|---|---:|
| Real preceding/following cycle relative RMS difference | `1.247e-7` |
| Source/modal state relative difference | `4.865e-12` |
| Cloud/excitation phase discrepancy in turns | `2.299e-10` |
| Native project seam error | `3.986e-15` |
| Exported PCM24 project seam error | `2.875e-14` |
| Absolute root fitting error | `.00273` cent |
| Unconditioned peak | `.34085` |
| Physical-source relative Jacobi drift | `1.316e-14` |

Serial elapsed time per zone was 1.95..10.44 seconds, median 4.09 seconds on the
cloud JVM. The first zone prepares physics; later zones reuse that population.
These are fresh audio renders with session reuse, not phone timings or audio
cache hits. The 4×/16× bright-C6 diagnostic measured normalized spectral-power
distance at most `6.971e-6` over two windows in each voice, with RMS ratios
`1.0000116..1.0000152`. This checks rate agreement, not a complete alias bound.

The generator checks native/MPC-compatible program construction, PCM24 sample
agreement, root and loop markers, complete recipes, release to silence and
byte-exact ZIP round trips. An independent RIFF/ZIP/WAV audit passed all 27
zones and 18 clips, and confirmed 361 existing finite WAV/JSON/ZIP files remain
byte-identical. Chromium checks at 1440 and 390 pixels cover all clips, seeking,
notes, notes export, instrument download and the retained twelve-preset bank.
The refreshed handoff retains all eighteen listening WAVs and all 27 native sample WAVs
byte-for-byte. Independent read-back verifies the authored range and NoteOn
metadata in all 81 phone/MPC zone descriptors; the three ZIPs round-trip.
The browser UI and listening media are unchanged.

Rebuild the handoff from a clean committed checkout:

```sh
./gradlew --no-daemon :shell:generateLibrationHoldAudition
```

The output is isolated in `testkit/libration-sustain/`. The three original finite
audition folders and their archives remain separate.

## Build validation (2026-11-01)

The complete nine-module JVM command exited zero at
`efe80df64dc2c3db6cb9661ae4413acc4d25751d`: 7,505 tests, zero failures or errors,
zero skipped. That baseline includes default revision
`95b53d655e3d29a3d222fdeeb462b49d677ac863`; Gradle reused unchanged module results.

The current integration includes default `7cb8f34507c664ad671b952f93934c0bc53265c5`.
Its complete diagnostic at `b4793e09017a7f4d6ca13f739e202da4e4213e12` ran 7,975
tests: five failures, zero errors and zero skipped. Those failures identified
MERIDIAN's authored range in two export paths, a sidecar snapshot predating
the trigger field, stale starter counts and a late pitch-probe window for
KEEL's added BELLY voice. This diagnostic was not a passing full suite.

At `5d0358aa97ddbaba1924157217e0c621b6fb1d71`, all 550 repair checks across six
modules pass, including every failed assertion above, all Libration tests,
saved-preset promotion, shared export, sample-player contracts and the complete
262-test CLI suite. KEEL retains all nine voice/note cells, its 50-cent limit
and its confidence floor; the probe now reads the active post-attack body.
All 25 audition provenance files still match the verified render source
`7d137063985c7f7dbb6c14cc0afada6508e9172d`. Full regression for the final revision
runs in [PR CI](https://github.com/BertCalm/SnipSnap/pull/812).

The package exporter now explicitly retains the authored clipped range and
declares NoteOn in legacy XPM as well as native MPC 3. Before opting out of
the shared exporter's new full-keyboard default, the new read-back test
failed on the actual 0..127 expansion at root 36. It now checks all three
descriptors at roots 36, 37, 60, 83 and 84. The package rewrite test also
reuses the exported file path for its second read.

Both `:app:assembleDebug` and `:app:assembleDebugAndroidTest` pass from the
current source above with SDK 35 and JDK 21; the app v2 signature verifies.
The STRIKE FX APK assembles, Crease Study Kotlin compiles, and its five host
unit tests pass. Grouping preset state and draft/cache ownership reduces the
actual SYNTH debug method from 263 registers to 245, below the budget implicated
by the emulator's verifier rejection. The Confluence test scopes ROOM to its
gathering card because STRIKE FX exposes another ROOM control. Device UI tests
compile. The emulator run at `8b61897a6fe0ea5518f7c0f241ba97a41ed5e1ec`
executed 391 tests: 388 passed, two optional benchmarks were skipped, and one
CHAMBER test failed because its 17-character preset name exceeded the existing
14-character limit. All three Libration UI tests passed, including HOLD
navigation, scramble, saving and gated pad delivery; SYNTH loaded without an
ART verification error. The CHAMBER fixture now uses a valid name and checks
that precondition before exercising the same-frame edits and save. Its runtime
rerun remains part of final PR CI.

All six native CTest executables pass with assertions enabled at the unchanged
native source from `7d137063985c7f7dbb6c14cc0afada6508e9172d`. Source inputs match
their build snapshots. Device UI tests were not executed locally; no phone
render timing or new HOLD hardware acceptance is claimed.

## Listening and device checks

The new HOLD path still needs owner listening. On the audition page, let each
long hold reach both marked wraps and its key-up ending. Compare the finite
note, the keyboard phrase, authored soft/hard touch and the three release lengths.

For MPC, extract a complete instrument ZIP and keep its program and TrackData
folder together. Load `.xty` on MPC 3 or the adjacent `.xpm` on MPC 2. Try MIDI
48, 60 and 72, plus 61 between sampled roots; hold for at least 23 seconds, then
release. Check the attack, repeated sustain and ending. Live key velocity changes
gain; the saved instrument has one excitation touch per zone.

On the feature phone build, use ROUND KEYS, FINE KEYS or SPLIT PATH. Choose
HOLD, change ROOT/TOUCH and a sound control, save, leave and return, then SEND
to a pad. NOTE should restore the saved finite LENGTH. In MAKE INSTRUMENT,
cancel one generation, make a replacement, and check a held key through its
wrap and release. Record any issue with its recipe, MIDI note, device/OS and
action. This is a new check, separate from the accepted finite instrument.

## Evidence ledger

- Verified: finite phone/MPC acceptance is an owner report; the repository APIs
  above exist at the named base. TIDAL and CREASE provide current held-source and
  sample/export examples.
- Verified: source models 1..3 keep the existing finite kernel; HOLD one needs an
  explicit supported held version. The frozen `Libration.kt` SHA-256 remains
  `033ba2696d06cc6ff81c1f9ed9c3665a662ac56b4b1b581c2d0f62529ac4239c`.
- Measured: a reduced five-case Python harness passed before Kotlin work. Its
  25 Hz archived readouts and three-particle fixture establish the method only.
  Kotlin focused checks pass actual phase/state/preceding-and-following-cycle
  closure, 4×/16× high-note spectral agreement, JSON guards, cancellation,
  bounded-cache identity, dual-MPC loop metadata and real sample-player release.
  The earlier complete baseline passes 7,505 tests. The later 7,975-test
  diagnostic found five integration regressions; all five repaired assertions
  pass in the 550-check repair suite. Both Android APK builds and five Study
  host checks pass; the six native executables cover unchanged native source.
  On-device UI execution and new HOLD listening remain pending.
- Proposed: musical usefulness of repeating the readout and the selected
  three-period settling budget outside the measured configuration set.
- Heard: the finite bank is accepted. No owner listening verdict is recorded
  for this sustained version.

## Why it is built this way

- A separate held version preserves the accepted finite recipes and their sound.
- Reusing actual motion descriptors keeps the five sound controls meaningful.
- Continuous excitation, modal and conditioning state give the loop a real
  preceding history instead of hiding a join between independent recordings.
- The release control matches what the phone and MPC sample players can do.
- Bounded preparation and serial zones keep a slow generation cancellable and
  make its cost visible.
