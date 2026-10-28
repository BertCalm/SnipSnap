# Ferry: first Kotlin PASSAGE port

The characteristic gesture is **strike → handoff → sympathetic bloom → return**.

This is one finite, dry, mono PASSAGE voice. It ports the owner-approved Python
study to SnipSnap's Kotlin renderer. The owner answered “Yes, clearly” to the first
handoff comparison and “Clear throughout” to nine low/middle/high, slow/normal/fast
examples. Those answers approve the Python references. Kotlin listening remains
a separate gate at <https://ferry-first-listen.bertcalm.chatgpt.site>.

Repository base inspected: `bf17f4561bda7418cc4ebf7bae8e78d7c8c45329`.

## Scope and controls

The patch saves `voice`, `midi`, `velocity`, signed 64-bit `seed`, `model`, name
and macros. MIDI is explicit, from 36 to 72; a separate TUNE knob is unnecessary
for this first listening API. Model 1 deliberately exposes only HANDOFF and a
reserved HOLD, an exception to the eventual three-to-six timbral-control shape.
There are no inert MATCH/SPAN/LOSS/BODY controls. They require subsequent design
and listening. PASSAGE's three provisional recipes stay outside the factory roster.

| Control | Default | Meaning |
| --- | ---: | --- |
| HANDOFF | .5 | Reciprocal A/B and B/support links; .25/.5/1 reproduce the approved .35/1/3.5 rate factors. Zero isolates B. |
| HOLD | 0 | Only zero is accepted; this version does not sustain or export a loop. |

All notes last four seconds, matching the approved comparison. A four-millisecond
house fade ends the file. This is a retained prototype cutoff, not a claim that
the physical state has decayed to silence. Energy-based termination and production
tail treatment remain open. Finite samples carry TONAL engine metadata, like
NACRE; a generic duration classifier independently calls a four-second recording LOOP.

Velocity changes A's initial displacement energy and upper-partial balance.
Nondefault seeds apply tiny, stable ID-derived excitation differences through
`Dsp.seedFor`. No seed changes phase, tuning, coupling, transfer clocks, or B's
initial state. The default seed preserves the approved displacement exactly.

## Model and separation

```
saved note, velocity, seed, HANDOFF
           │
           ├─ tuning / reciprocal graph / damping ── cached compiled network
           │                                           │
           └─ finite A displacement ───────────────────┘
                         │
             A (4) ↔ B (4) ↔ support (4) ↔ receivers (9)
                 A also connects to support
                         │
                 material-specific pickups
                         │
        independent quiet A root (1), added at readout
                         │
       bandLimit → decimate → DC → levelTo → fadeTail
```

The state is an energy-normalized complex vector. `da/dt = (i H − Γ) a`, with
real symmetric H and nonnegative diagonal Γ. Unforced energy `a†a` cannot grow.
Only A and its independent root receive initial displacement; B, support and all
nine receivers begin at rest. Material weights observe states and do not inject
energy. This reduced narrow-band model is not a nonlinear pendulum simulation.

`FerryNetwork` is separate from `Modes.Bank`: the latter uses displacement/velocity
stiffness kicks and a different energy definition. The port reuses
`Modes.symmetricEigen` and validates eigen residual and orthogonality. It does
not diagonalize H and then pretend unequal physical damping is diagonal there.

The complete H's four lowest eigenfrequencies are kept within a proposed five-cent
fundamental constraint. Every reciprocal link touching A/B/support fundamentals,
including support-to-bank links, is scaled together when necessary. The independent
root is never retuned. The approved C3 normal graph requires no cap adjustment.
Identifying the four lowest frequencies is valid only for this fixed bank, above
1.57 times the root. Future MATCH/SPAN requires participation tracking or an enlarged
root subspace. A five-cent spectral constraint does not itself prove perceived pitch;
the Python fast C2 pitch proxy was about 15.5 cents sharp in one middle window.

## Passive update and cost

At 176.4 kHz, precompute `U = D exp(i H / rate) D`, with
`D = exp(−Γ / (2 rate))`. The middle factor is unitary; damping is contractive.
Both the discrete physical state and unequal wood/wire losses are retained.

For this constant, unforced render, compile 64 readout rows `pickupᵀ U^k`
and the block advance `U^64`. Each sample dots its row with the block's actual
state, and each block advances that state once. This is algebraically the same
network stepping, not scheduled envelopes or separately rendered layers.
`FerryTest` compares the block output with direct single-sample stepping and with
independent exact full-system observations.

The render does no per-sample eigensolve, trigonometry or allocation. A 32-entry
LRU retains immutable coefficients keyed by note and HANDOFF bits; model, rate,
voice and the fixed tables are constants of this version. Velocity and seed
affect only excitation and therefore do not invalidate that cache. Each render
owns its state buffers. Thread interruption is checked at every block.

Measure complete renders including house conditioning. The audition manifest
records cold and median-of-three warm server-JVM times for all nine cases.
These are not phone timings. The house goal remains tens of milliseconds; no
phone acceptance or performance claim follows from the Python approval.

The first native audition on a four-processor server JVM with JDK 17 measured
40.1–46.7 ms for a complete four-second warmed render. Cold plan/JIT cases were
41.1–126.9 ms. Native `Pitch.detect` across attack/middle/tail windows measured
at most 14.21 cents of root deviation, with confidence at least .927. Regression
limits for these nine cases are 20 cents and .9 confidence. Peaks were .411–.743,
absolute DC at most 1.34e-5, and all nine reached the shared melodic loudness target.
These figures describe this server and these fixed examples, not every future macro.

## Verification and reproduction

```
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.FerryTest
./gradlew --no-daemon :cli:test --tests com.snipsnap.cli.FerrySynthCommandTest
./gradlew --no-daemon :synth:generateFerryAudition
./gradlew --no-daemon test
```

Validated on 2026-10-28 with JDK 17: both focused suites passed (eight Ferry
checks and two CLI checks), audition generation succeeded, and the complete
`./gradlew --no-daemon test` finished with exit code 0. The full run took
1 hour 7 minutes on this server. No Android SDK was present, so this validates
the JVM modules rather than the Android app or on-device audio.

`generateFerryAudition` writes `testkit/ferry-audition/`, or the directory supplied
with `-PferryOutput=...`: native WAVs, volume-matched approved/Kotlin comparisons,
complete patch recipes, a portable page and measured timing/pitch evidence.
The page asks one primary question: does the Kotlin version keep the approved
character? The wider nine-case native range is optional.

`reference/ferry/approved-round2/` retains the nine approved PCM16 WAVs and their
SHA-256 manifest. They are reference data only; the engine reads no samples.
`synth/src/test/resources/ferry-reference.json` records their networks and exact
full-generator state observations. `scripts/ferry_reference.py` regenerates the
observations with SciPy `expm`, independently of Kotlin's split update.

CLI example:

```
snipsnap synth FERRY PASSAGE --preset 1 --midi 48 --velocity 1 \
  --seed 7 --macros HANDOFF=.5 --out ferry-output
snipsnap synth FERRY PASSAGE --patch saved.patch --out ferry-replay
```

Patches, recipe editing, both velocity dispatcher decisions, provisional CLI
recipes, exact seed serialization, registry tests and a cache-eviction determinism
canary are integrated. Positive HOLD and malformed saved metadata refuse explicitly.
The factory roster, six-voice preset collection, sixteen-pad kit, Android picker,
keygroups and MPC hardware acceptance are deferred to their appropriate gates.

## Why it is built this way

- The coupled states preserve the tuning-dependent handoff the owner approved.
- Different pickups expose the material change in mono without changing network energy.
- A quiet independent root helps keep the note usable while upper modes exchange faster.
- Compiled block observations reduce work while retaining the same passive update.
- The risky balance is root clarity versus audible migration; Kotlin output conditioning
  makes its listening comparison necessary even after numerical agreement.

## Evidence ledger

| Claim | Status |
| --- | --- |
| Original handoff and nine Python register/speed examples | Heard and approved by the owner. |
| Current repository APIs and base commit | Read and verified during this port. |
| Kotlin numerical, serialization, output and CLI contracts | Focused suites and full JVM run passed on 2026-10-28 (exit 0). |
| Kotlin sound character | Awaiting the owner's comparison. |
| Full macro set, held loops, production tail, phone/MPC behavior | Later work. |
