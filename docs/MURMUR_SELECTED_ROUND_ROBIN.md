# MURMUR selected native round robin

This opt-in source policy gives the three kept model-4 PLUCK shapes four deterministic
repeat identities. It keeps the complete source patch, requested note, native velocity,
all controls, routing, ratios, spectral targets and envelopes fixed. Take zero retains
the existing excitation seed. Takes one to three select new breath/contact noise before
the same moving formant bank. They are performances of one PLUCK voice, not new voices,
presets or velocity levels.

## Saved contract and compatibility

`MurmurRoundRobinRecipe` saves kind `MURMUR_SELECTED_ROUND_ROBIN`, version `1`, policy
`NATIVE_BREATH_CONTACT_V1`, the independently derived selected-source ID, take `0..3`,
and the complete canonical `MurmurPatch`. Unknown fields, incomplete nested sources,
lossy Float values, unsupported policies and nonintegral/out-of-range selectors refuse.
The source owns an immutable JSON snapshot; every patch read is a fresh deep decode.

Only the existing selected HOLLOW WIRE NOTE, HOLLOW WIRE HOLD and STEADY ROOT C2 shapes
qualify, within their existing supported note ranges and at positive native velocity.
BREATH must be positive for the changed takes. Existing selected shapes already enforce
that; this policy does not claim useful repeat variation at zero breath or for six voices.
Names do not select eligibility: complete controls/configuration do.

The normal `Murmur.render`, `renderRaw`, `probe`, config constructors, patch serialization,
models 1–4, default model, factory bank and selected instrument v1 source format retain
their signatures and behavior. New explicit `renderRoundRobin`/`probeRoundRobin` entry
points own the extension. No constructor field or ordinary patch/cache key was added.
A repeat-aware consumer must use the full envelope, including take, as its source/cache
identity. The existing bank and player do not adopt it automatically.

## Why it is built this way

- Breath/contact noise enters the native excitation before the resonant bank, so its
  variation is more than an output gain or a renamed preset. Pitch ratios, envelope and
  note duration stay fixed.
- The old seed tuple is a separate take-zero branch. Changing it would change accepted
  audio, even if the replacement seed seemed equivalent.
- A held take repeats one complete noise table. Unbounded random noise at the wrap would
  break the intended periodic source and the real preceding-cycle seam.
- A separate source format avoids silently changing old saved models or compiled callers.
  Strict decoding makes unsupported sources fail rather than fall back to another sound.
- Capturing complete native state makes repeat scheduling independent of process history,
  wall-clock time or render order. There is no live counter or mutable shared RNG.

## Verification ledger

Source review: this change is based on `a359a8583ae5b7b1afa02e364cb3cb30dccb4c60`.
The new native branch changes only the seed used by the established `Dsp.Noise` generator.
Its cost is one additional stable seed hash per render; no extra native pass or solver
is introduced. End-to-end latency remains to be measured in the granted native gate.

Eight focused tests are authored: four codec/ownership/eligibility contracts and four
native checks. Native checks request ten calls: three take-zero raw-native hashes against
independent retained selected references; one independent model-2 KEY held final-Float
canary; four finite repeat takes plus one exact serialized replay; one changed held take
with its real previous-cycle seam. Finite differences cover all six take pairs after
best scalar gain removal. The `>1e-6` residual bar proves numerical non-gain variation,
not audibility. Test reports label raw-native and house-finished Float planes separately.

At source freeze these tests have not run. Root owns the resource-bound execution and
its later terminal receipt. No new sound has been heard or approved. Low accepted BREATH
may make the variation subtle. Wider-register/per-take listening, velocity-bank/RR-layer
export, scheduling, onset/release adaptation, app integration, runtime and device gates
remain open. A raw held seam is not acceptance of a playable held instrument. The prior
accepted heard WAVs retain their saved gains/presentations and approval scope.
