# WICK finite keygroups

WICK's accepted patch, presets and DSP remain unchanged. This delivery gives
finite WICK notes a bounded keygroup package using the existing native renderer,
MPC writers and phone-sidecar format. It does not convert HOLD loops into finite
notes or claim a new held/strummed articulation.

## What the maker does

`WickKeygroupMaker.Spec` keeps the complete source patch, requested key range and
sampler release. Its default range is MIDI 24–84. Native roots occur every minor
third, with both boundaries and the exact authored root retained. Every key
therefore uses a native capture at most one semitone away. The program keeps
these boundaries instead of stretching the end zones over the whole keyboard.

Each root has three independently rendered excitation strengths: .30, .65 and
1.00 times the authored patch velocity. The hard layer at the authored root is
the original complete source patch. RATTLE's body, all controls and legacy body
identity survive every copy. There is no gain compensation or extra normalization
in the maker; WICK retains its own output stage and the sample player applies
live key velocity. Sampler release uses the existing host envelope, not a new DSP
decay model or a calibrated MPC release-time claim.

All layers explicitly have no sustain loop. The program is polyphonic and
declares NoteOn. `render` works serially; cancellation is checked around each
note and WICK also polls thread interruption during its render. A default
full-range voice requires 63 native captures, or 66 for an authored root between
the regular grid points. This package does not claim a full-bank render or phone
preparation time.

`writeNewPackage` stages a fresh portable directory containing the `.xty`, its
complete `_[TrackData]` folder, legacy `.xpm` and phone sidecar. A source manifest
inside TrackData binds each actual WAV hash to its complete source recipe.
Validation checks all cells, roots, non-loop sampler sheets and all three
descriptor forms before the directory is published. Existing destinations are
refused. This lower-level API creates a portable directory. Android uses
`WickPadMaker` and `InstrumentPackageAdmission` to place the complete packet's
three siblings directly into the actual app-private instrument shelf.

## Android sheet and fresh shelf admission

Finite recipes with nonzero excitation now offer MAKE INSTRUMENT. The existing
finite-sheet hooks keep attack/release replacement controls hidden and describe
finite notes rather than indefinite sustain. HOLD-loop recipes remain intact:
their button is unavailable with an explanation instead of forcing HOLD down.
The preview renders the exact saved patch at its authored root and velocity.

Zone/touch rendering stays serial and participates in the screen's interruptible
render path, cancellation and progress accounting. Each note is explicitly
unlooped. The source capture keeps its own finite envelope; key lift uses the
existing fixed sampler-release default, without a new release-control promise.
MPC envelope durations still require hardware calibration.

The admission helper builds and validates a fresh complete packet before moving
TrackData, then `.xty`, then the listing sidecar into the instrument directory.
All WAV/recipe provenance remains inside TrackData. Extra root files are refused
rather than lost during admission. Existing names are refused; handled failures
roll back only the newly published entries, preserving other shelf instruments.
This scoped helper does not promise crash durability or isolation from readers
and unrelated external publishers.

The real sheet regressions cover finite wording and HOLD-loop refusal. They are
authored but their Android compilation/device execution belong to the normal
validation queue. JVM admission tests use actual `InstrumentStore.list` and
`toProgram`, source bindings, and injected failure at each publication move.

## Held entry source

`Wick.inspectHeldSource` keeps the real cold-start history of a held recipe's
selected engine up to its accepted loop: the opening periods, then the accepted
cycle bit for bit, joined under the existing seam bar. A replay fallback starts a
new history rather than continuing the abandoned causal run. It is a held
pulse/schedule opening, not a finite attack or a natural release, and every
existing render path is unchanged (`WickHeldEntrySourceTest` pins Held Glow's
accepted cycle and context hashes).

`WickHeldPadMaker` is a one-root prototype: the genuine settled predecessor
period is pre-roll, then the accepted cycle loops. It has no sheet or export
route; `WickHeldPadMakerTest`, `WickHeldSamplerPlaybackTest` and
`WickHeldSavedSourceTest` check its capture, sampler playback and a saved,
reopened copy. Native held keygroups across a range are not built.

## Completion evidence and gaps

This work came back from the owner's recovery backup. There, WICK's engine,
patch and presets matched published source 0af740847ebe1c5a812645659f7cd23767e9c1bd,
which this branch contains. The backup records: "The owner has now accepted
WICK's nine additions and MERIDIAN's current voice comparisons for sound. That
does not certify hardware or the complete standard."

| Requirement | WICK |
| --- | --- |
| Voices and presets | Six accepted voices; 36 complete presets |
| HOLD | Existing causal-recovery loops preserved; the held entry source and one-root prototype above; finite keygroups do not replace held-instrument work |
| Keygroups | Finite maker, source bindings and fresh Android shelf route implemented; musical bank and device validation pending |
| Premade kit | Existing 16-pad starter with all voices and three RATTLE bodies; single-capture pads |
| Velocity | Existing native excitation field; three-touch finite keygroups |
| Round robin | No authored take identity or keygroup round-robin metadata |
| Chord tools | Root edits retain complete patches; keygroup polyphony is exercised with real ChordStabs note sets |

The existing `ChordStabs.kit` generator builds STOOP, and `PhotoChord.build`
builds SNAP; this change does not replace their source engines. The focused
keygroup fixture schedules the actual 384 major/minor `ChordStabs.stabs` note
sets through `InstrumentEngine`, proving supported note selection, simultaneous
voices and bounded program routing. It uses synthetic PCM and does not establish
fresh WICK tuning measurements, new chord presets or hardware playback.

WICK's PLUCK HOLD/STRUM interpretation remains an explicit instrument-design
question: the accepted loop is a causal gate/recovery gesture, and this delivery
does not silently substitute a new picked-string performance. Native held
keygroups, a strum option if required, round robin, musical-bank delivery,
normal Android execution and physical MPC/phone acceptance remain distinct work.

## Why it is built this way

- Complete patch copies keep accepted sound and old RATTLE body identity intact.
- Sampling the requested note directly avoids applying root transposition twice.
- Three native touch strengths change excitation before WICK's normal output
  stage; they are not merely three copies at different playback gains.
- Explicit non-loop metadata keeps finite gestures from becoming a repeated
  pluck when a key is held.
- Source bindings make the delivered PCM and editable recipe identity reviewable
  without claiming that arbitrary imported audio was synthesized from its label.

Focused checks are in `WickKeygroupMakerTest`, `WickPadMakerTest` and, on a
device, `WickFiniteInstrumentScreenTest`.
