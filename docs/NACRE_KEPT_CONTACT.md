# NACRE kept contact sources

This local implementation follows owner KEEP for `nacre-contact-loss-035` and
`nacre-contact-loss-080`, published as profile 1 at native source
`9b4664d3801620b04c8969c3f78529df91722ca8`. The canonical feedback is
`/workspace/snipsnap-feedback/2026-11-11-nacre-212843Z/owner-verdicts.json`,
SHA256 `b22a96ba34d789aa3c2e0d14f64ae38e59024e5925b24a34b97ee31bb8923fd4`.
It approves those two settings at PEARL/C3/MIDI48/native velocity1/seed7;
it does not approve all parameters, registers, factory integration or hardware.

## As built, source authoring only

Saved NACRE model 4 explicitly opts into the already heard passive contact law.
Its additional `CONTACT` macro controls only the existing profile-1 nonnegative
round-trip loss. `TOUCH` still controls the original strike; the original five
controls and finite `HOLD=0` retain their meanings. Model4 has six macros plus
HOLD, with CONTACT0 as its bypass/default. The per-period gain remains
`exp(-ln(1000)*CONTACT/(.65*frequency))`; the .65 shape was heard in the exact
kept settings and has not been retuned. No new DSP coefficient or gain stage was
introduced. CONTACT is removed before the existing model3 bus settles its controls.

Models1..3, `Nacre.MODEL_VERSION=3`, default constructors and old partial JSON
stay unchanged. Earlier recipes do not gain a CONTACT key. The NacrePatch
constructor, data-copy fields, JSON schema fields and all model-dispatch method
signatures are unchanged. The existing saved `model` and macro map carry the
new opt-in; ordinary Patches/PadRecipe/UserPresets/Velocity dispatch retain it.
Model3 rejects CONTACT instead of silently ignoring it.

The first three presets retain their order, names and complete source settings:
Pearl Close, Coral Reply, Kelp Passage. Two appended presets, Pearl Moderate
Contact and Pearl Strong Contact, save model4 with CONTACT .35 and .8. Their
other controls remain the heard recipe: TOUCH .30, DUET .15, CURRENT .20,
VENUE0, BLOOM .15, HOLD0; MIDI48, velocity1, seed7. These are five settings of
**one PEARL voice**, with no new genuine voice claim.

The public patch render uses the existing completed core cache and omits
inspection-only stem copies. Loss remains part of the existing core key; edits
to CURRENT/VENUE/BLOOM reuse the same source. Canonical zero uses the model3 cache
key and skips new string arithmetic exactly. No new cache, solver, resampling,
normalization, final gain matching, or shortened capture was added. Existing
cold/warm timing measurements remain historical, not timings of this new source.

The velocity-bank `native-velocity-and-touch-effort-v1` requires model3 already,
so model4 is refused. Its sixteen-pad bank, layer values, compensation law and
accepted hard/Coral/Kelp audio are unchanged. A contact-aware bank policy needs
separate source, velocity/RR/register/chord/keygroup/kit checks.

## UI and scope boundaries

Static source review found that the old app uses `Nacre.macrosFor` for its
ordinary controls, so models1..3 do not show an ignored CONTACT slider.
Restoring a full preset merges its saved macro map and live edits use
`Patches.edited`, retaining the opt-in CONTACT value. The shared model-specific
Velocity specs expose CONTACT only for model4. A dedicated model4 CONTACT slider
in the Android panel is **not implemented** here; App compilation, actual
restore/edit/save behavior, runtime and physical-device gates remain open.

## Verification ledger

Read/verified in source: named APIs, preserved source signatures, old model
routing, typed velocity-policy rejection, existing UI full-map retention and
all changed count/version expectations. An isolated economical worktree holds
this local candidate; the frozen9b parent, normal98f/5e and MURMUR73 are not edited.

Authored, **unrun**: six new public-source tests with complete independent raw
Float, final Float and PCM24 hashes for both kept captures; accepted zero source;
saved JSON/PadRecipe/edit/native velocity; invalid controls; cache ownership and
cancellation; original v1 bank rejection and five-settings/one-voice discovery.
Five materially affected existing preset/validation methods are selected too.
No compiler, JVM, native render, test, new audio, source push, merge, deployment,
owner or device approval occurred during this source authoring.

## Why it is built this way

- A saved model4 is explicit. Old sources keep their model and their exact sound.
- CONTACT is independent of TOUCH because the owner kept a decay change, not a
  different strike or a quieter final signal.
- The old core/bus and cache are reused so the heard source need not be rebuilt
  with another rounding, tuning, finishing or memory model.
- The old velocity bank refuses the new source until a separate bank policy is
  qualified; adding a kept preset does not silently replace accepted layers.
- Independent full-frame witnesses check the public replay path, rather than
  assuming a successful serialization or one waveform prefix proves the sound.
