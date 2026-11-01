# Apsis: attacks with memory

2026-11-01. Python/Numba audition revision, not a Kotlin or Android release.
Reviewed base: `fe897be84044ad9182db2f9f6c820374045e7f61`.

## Owner's listening decision

GRAIN and ANCHOR are the favorites; TETHER is acceptable. VESSEL and CORONA need
more variation between repeated attacks, with the previous attack bleeding into
the next. This revision changes only those two audition identities. The original
render function and all other voices retain their original output.

## Findings and change

**Fix now, sound character:** repeated contacts reuse a fixed strike law. CORONA's
body-mode T60 values were .24, .09, .07 and .06 seconds, so its bright response
largely disappears between encounters. VESSEL rings longer, but its contacts
still arrive with similar excitation. Existing modal state already persisted;
the problem was its excitation and decay balance, not a missing reverb effect.

The revised gesture is **encounter → a contact shaped by available energy →
ringing memory → the next contact arriving over that memory**.

Each encounter draws from one bounded recovery budget. Recovery time follows
the inner orbital period. Requests depend on encounter speed, normalized
distance and fractional participation. Coincident contacts share the same
available energy proportionally. A busy cluster therefore produces different
strengths from a well-separated arrival, deterministically, with no per-hit
normalization or random volume jitter. Hardness follows available energy and
encounter strength, changing mallet width or breath-packet edge and duration.

The default finite schedule has roughly 7 dB of planned contact-strength range
at one fixed playing velocity. This is an excitation measurement, not a claim
that every output peak differs by that amount while older tails overlap.

The modal bank remains live across every attack. Lower material losses retain
root and upper resonance into subsequent encounters. CORONA emits shaped breath
packets into this longer-lived body; VESSEL uses strikes with varying force and
contact duration. CORONA has one fixed radiation calibration for the complete
voice. It is never recalibrated separately for each attack.

## Stability, loops and cost

The recovery budget stays in [0,1]. At each group, the spent amount is
`available * (1-exp(-sum(requests)))`; the available remainder stays nonnegative.
Total spending is bounded by initial availability plus the supplied recovery
over elapsed time. For a held schedule, its affine return map is solved for a
periodic boundary state and checked independently.

Every free modal rotation still has radius below one; junctions remain
orthogonal. Lengthening decay does not add energy. External excitation and
radiation gains remain outside this free-network proof. A counterfactual that
clears the bank on every attack is used to test that the normal output actually
retains earlier ringing.

Held output uses actual preroll, a kept cycle, and real continuation before the
output resampler. This avoids its zero-padding edge artifact. Audio recurrence
is checked against an independent preceding cycle, not a pasted duplicate.

Geometry remains in the existing immutable cache. Encounter planning is a small
control-rate calculation outside the audio loop. These are desktop Python
prototypes; phone latency and production aliasing qualification are not claimed.

## Audition and verification

The page opens with original/revised C4 pairs for VESSEL and CORONA. The two
voices' identity, phrase, range, touch, control and held takes are revised.
GRAIN, ANCHOR, TETHER, PASSAGE and the existing native bank remain available.
The previous C4 files are retained as before references; new audio has versioned
URLs so cached audio cannot masquerade as the new render.

Matched playback uses one listening gain for a complete take, preserving its
internal attack dynamics. Raw playback retains the first study's common monitor
gain. Touch and macro families retain a shared listening gain within each family.
The three-note scores preserve complete approaches and overlapping sample tails.

The generator checks C2/C4/C6 pitch, held recurrence, deterministic replay and
44 decoded PCM files. Unit probes cover recovery bounds, contact variation,
periodic closure, retained ringing, the reset counterfactual, zero velocity,
scope and the existing prototype checks. Six legacy float renders were frozen
before editing and compared bit-for-bit after the optional renderer extension.

Owner preference is **heard feedback**. Excitation contrast, PCM properties and
recurrence are **measured**. The revised voices' musical improvement remains
**proposed until the owner listens**. No new presets or Android voices ship here.

## Reproduce

```sh
python3 -m pip install numpy scipy numba
python3 scripts/apsis_voice_study.py testkit/apsis-voices
python3 scripts/generate_apsis_attack_study.py testkit/apsis-voices testkit/apsis-attack-memory
python3 -m unittest discover -s scripts -p 'test_apsis_*study.py' -v
```

## Why it is built this way

- A shared recovery budget gives clustered and spaced contacts different strength.
- Hardness changes the attack spectrum and shape as well as its amplitude.
- A live, slower-decaying body lets each new strike meet the previous resonance.
- Original references and versioned audio URLs make the change assessable.
