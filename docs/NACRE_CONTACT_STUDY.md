# NACRE passive-contact study, profile 1

This isolated, unregistered candidate responds to the owner decision `nacre-contact` in
`ae0db044476188e9d14dcb155958984d690122b814182a6942b6c8d35728b8d6`:
Firmer TOUCH .65 was too similar to the accepted hard reference. Strong coral and strong
kelp were judged distinct preset-return characters (`nacre-returns`), not extra voices.
The November 7 original handoff proposes passive contact damping as the next experiment.

The gesture stays paired strums → body and sympathetic response → timed returns → rest.
`NacreContactStudy.Profile(additionalLoss, version = 1)` adds a static, nonnegative
round-trip attenuation to the eight played strings before their bodies and returns.
Additional amplitude decay rate is `ln(1000) * additionalLoss / 0.65` per second;
per-period gain is `exp(-ln(1000) * additionalLoss / (0.65 * frequencyHz))`.
0.65 seconds is a proposed shape constant, not a measured optimum. This models passive
finger loading by analogy; there is no finger/contact solver or new excitation source.

The loop's original tuning, low-pass/allpass coefficients, exciters, seed, event order,
note, velocity and body/return mechanics are reused. The extra loss cannot raise the
loop gain. There is one extra precomputed scalar per string and one multiply per string
per existing 32-frame control block, not a new sample loop or additional source cache.
The existing four-entry cache includes the explicit contact loss in its source key.
Cache clearing, cancellation, memory bound and returned-array ownership stay shared.
Warm return edits reuse the study core; cold cost is the existing core render plus this
small loss calculation. Actual render timing and pitch/alias checks remain unmeasured.

Zero additional loss follows the exact old call/gain arithmetic, skipping every new DSP
multiply. Models 1, 2 and 3, `Nacre.MODEL_VERSION = 3`, old JSON routing, factory defaults,
all three presets, accepted hard source and `native-velocity-and-touch-effort-v1` are
unchanged. No CONTACT macro, model 4, new preset, new saved field, bank, HOLD/STRUM policy
or fourth voice is registered. The profile must be explicitly selected by a future
source-bound audition probe; study recipes must record version and loss beside their
complete ordinary model-3 source recipe. Existing saved-patch/recipe APIs cannot save
or silently select this study behavior.

Four focused tests are authored but UNRUN: independent accepted coral/seaweed Float
witnesses at zero loss; nonzero direct-tail contrast with identical events and fixed
output gain; profile cache separation/reuse/ownership; invalid profiles/HOLD/cancellation.
The decay ratio test uses separate onset and late windows; scalar final attenuation or
silence cannot satisfy it. Its numeric criterion is a proposed mechanical regression
check, not proof of audible usefulness. No tests, compilers, native checks, audio or
publication ran during authoring. Accepted references are never regenerated to fit hashes.

Next: under a fresh source/resource grant, compile the three changed/new production files
and this one test against the exact qualified parent dependency providers, with the new
candidate first in provider order; run only `NacreContactStudyTest` (four methods), preserving
all parent source/fixture/model pins. Then render a small source-bound comparison of loss
0/.35/.8 at identical C3/model-3 recipe, seed 7/velocity 1, retaining actual force-free
waveguide output and full six-second audio without output gain matching. Include zero-loss
accepted-reference hashes and strong Coral/Kelp unchanged references. Assess direct decay,
pitch, finiteness, peak/DC, cancellation/cold/warm timing and aliasing before publication.
Only an owner listening choice can authorize later factory/bank-policy adoption. Normal
full-source/app/device gates and saved-history/export checks remain separate future gates.

One genuine PEARL voice remains. Presets, settings, layers, contact profiles and proof cases
are not voices. Six genuine roles, appropriate extended STRUM, keygroups, full velocity/RR,
chord delivery, app/device acceptance and programme completion remain open.

## Why it is built this way

- Loss inside the played strings changes decay and the body/return drives; final gain alone
  could not supply the requested playing-layer contrast.
- Fixed phase-bearing coefficients avoid introducing a new retuning mechanism.
- A named versioned study keeps unheard changes outside saved models and accepted banks.
- Exact zero bypass protects independent accepted Float witnesses; no hash is updated.
- The return path stays shared so the owner-approved Coral/Kelp distinction is preserved.

## Subsequent kept-source exposure, local source authoring

Both exact profile1 contact settings (.35/.8) received owner KEEP on November11.
The isolated successor exposes them as explicit saved model4 CONTACT settings,
while models1..3/default3 and the original audition-only profile API remain
compatible. See `NACRE_KEPT_CONTACT.md` for exact ownership, source routing,
independent full-frame witnesses and open App/bank/device gates. No new render
or pass is claimed by this source-only implementation.
