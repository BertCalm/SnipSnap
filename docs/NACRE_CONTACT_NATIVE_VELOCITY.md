# NACRE model-4 native velocity recipe

This is an explicit opt-in source recipe. It adds no starter, bank generator,
download replacement, UI route, new voice or physical-device approval. Existing
model-3 `NacreVelocityRecipe` / `NacreVelocityKits`, their source field and policy,
and every existing renderer/default/factory recipe remain unchanged.

The two CONTACT settings (.35 and .8) already received KEEP at native velocity1,
TOUCH .30, MIDI48 and seed7. Those exact hard source arrays remain the reference.
Soft and Medium below are new native performances that need separate listening.

## Source contract

`NacreContactVelocityRecipes.recipeFor(hardPatch)` accepts a complete dry PEARL
model-4 source, native velocity1, TOUCH .30 and finite HOLD0. Every model-4 control
must be saved, including CONTACT; partial/default-inferred bank sources refuse.
The complete note, signed 64-bit seed, name, model and all controls other than
TOUCH remain identical across layers.

| MIDI velocity | Layer | Native velocity | TOUCH | Fixed sampling gain |
| --- | --- | ---: | ---: | --- |
| 1–41 | Soft | .30 | .05 | unchanged `NacreVelocityKits.samplingGain(.30, .05)` |
| 42–84 | Medium | .65 | .18 | unchanged `NacreVelocityKits.samplingGain(.65, .18)` |
| 85–127 | Hard | 1 | .30 | 1 |

Velocity0 selects no layer. The established native velocity/TOUCH excitation is
reused because the original velocity-only proposal was already known to be too
close to scalar gain. CONTACT remains fixed; it is not a new velocity curve.
The gain cancels only the existing explicit native velocity/TOUCH effort factors.
It is never fitted to peak, RMS, a hash or a rendered comparison.

## Ownership and saved policy

The separate source key is `nacreContactVelocityLayers`. Its policy is
`native-velocity-touch-contact-model4-v1`, `policyVersion=1`, `schemaVersion=1`.
It cannot be decoded as model-3 v1 and does not broaden that old contract.

Each layer owns a copied, unmodifiable macro map and a fresh dry `PadRecipe`.
The bank owns its layer list. JSON requires exact top/layer fields and complete
canonical nested recipes. Unknown fields, mixed sources, fractional windows,
wrong model/velocity/TOUCH/gain and Double changes rounded away by Float decoding
refuse. Use the provided codec to retain the complete source precision.

`renderAllLayers()` replays three complete six-second mono 44.1kHz sources.
Returned arrays are independently owned; Hard is copied with no gain arithmetic.
Wrong shape/rate/length, nonfinite audio or output above the unchanged .95
ceiling refuses. The recipe never normalizes, trims, fades or edits audio to pass.
Ordinary one-source REPLAY remains separate from full native bank replay.

## Verification boundaries

Focused tests cover policy/compatibility, immutable ownership, tamper refusal,
full-buffer replay and native hard equivalence. The native check renders six unique C3 source cases, then explicitly replays Soft
after policy serialization (seven native invocations total). It records fresh
gain-removed residuals against Hard without importing model-3 thresholds or
claiming listening acceptance.

Tests normally emit no files. A fresh Root-granted native gate may set
`snipsnap.nacreContactVelocity.captureDir` and the actual 40-hex
`snipsnap.nacreContactVelocity.sourceCommit`. Only after both hard hashes and all
six native deliveries pass does it create a new capture directory containing
six final Float32LE buffers and `native-velocity-captures.json`. The metadata binds
full PadRecipes, velocities/TOUCH/CONTACT, windows/gains, format/length, native and
delivered hashes, and measured residuals. Actual house PCM24/WAV encodings are hashed in memory,
including the parsed RIFF data chunk, without creating WAV files. Capture reuses
the first six already-tested arrays;
it never rerenders, changes gain or overwrites a previous failed attempt.

Unit/native execution, independent artifact checks, browser publication/listening,
normal App/APK integration and physical acceptance remain distinct gates. This
source increment does not establish any of those results by itself.
