# Libration: sustained instrument

Status: implemented, with focused JVM checks passing; final build validation is
in progress. The owner accepted finite phone/MPC behavior
on 2026-10-31 and authorized this milestone. The new sustain has not been heard.
Repository truth: `c65d2521f5391f8443182a06bfbfff2eb8360459`.

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
  Full-suite and Android results are recorded below when complete.
- Proposed: musical usefulness of repeating the readout and the selected
  three-period settling budget outside the measured configuration set.
- Heard: the finite bank is accepted; this sustained version is not yet heard.

## Why it is built this way

- A separate held version preserves the accepted finite recipes and their sound.
- Reusing actual motion descriptors keeps the five sound controls meaningful.
- Continuous excitation, modal and conditioning state give the loop a real
  preceding history instead of hiding a join between independent recordings.
- The release control matches what the phone and MPC sample players can do.
- Bounded preparation and serial zones keep a slow generation cancellable and
  make its cost visible.
