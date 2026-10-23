# Spec template: the SnipSnap engine engineering specification

Calibrated to the specs Josh already commissions (Rung, Skirl, Terrane: numbered sections,
"physically inspired pitched synthesis engine for SnipSnap", a gesture line, macro and voice
tables, rounds) and to what the repo actually asks for (the BORE registration table, Gyre's
guardrails, "As built" appended in the same commit as the code). Three additions the earlier
specs lacked: a signal-flow diagram, a render budget, and a verified-vs-proposed ledger.

Write in the house voice: declarative, measured, no hype. A spec is a proposal; say so in
the status line, and keep "proposed", "measured" and "heard" apart throughout.

## Short form: the design brief (for a first conversation, 1 to 2 pages)

```
# <Name>: design brief
Status: concept. Nothing rendered, nothing heard.
Repository truth: <commit hash read; the APIs grepped>.

The characteristic gesture is **A → B → C → D**.

## What it is (one paragraph)
## Why it earns a pad (nearest siblings: X, Y; what they cannot do)
## Signal flow
<ASCII diagram, see §3 below>
## Macro contract (table: control | default | what quantity it changes | required interaction)
## The three directions (Strong, Alternate A, Alternate B, one paragraph each, scored)
## Risks (solver, stability, render time, trademark, register)
## Cheapest proof (what to prototype first, what number says it works)
## Why it is built this way (two to five plain-language notes for the owner)
```

## Long form: the engineering specification

Use this section list. Drop a section only with a sentence saying why.

```
# <Name> Engine: Engineering Specification
Version 1.0 · <date>
Status: proposed engineering handoff. <what remains outstanding: validation, probes, listening>.
Repository truth: <commit hash of SnipSnap read while writing; which APIs were grepped>.

## 1. Summary
   One paragraph. The gesture line. One sentence on what is computed vs imagined.
## 2. Goals and scope
   Musical goals (what it plays). V1 includes / excludes. No samples, mono, dry identity.
## 3. SnipSnap contract
   Offline mono 44.1 kHz; 4× internal (`Tide.bandLimit` → `Dsp.decimate`); `Dsp.seedFor`;
   `Dsp.levelTo(MELODIC_LOUDNESS_TARGET)`; 3 to 6 macros plus HOLD; `drumClassFor` keeps
   it out of the drum set; HOLD seam `Keys.MAX_SEAM_ERROR`; the registration checklist in
   snipsnap-conventions.md §3. Name real APIs only.
## 4. Signal flow
   The diagram (§3 of this file). Name each box as a unit (XxxDynamics, XxxExciter,
   XxxResonators, XxxHold) and say which are pure functions of macros (cacheable).
## 5. Architecture and separation
   The units, their inputs and outputs, what crosses between them, what never feeds back.
## 6..N. The model, one section per mechanism
   Equations in a code block, units, frames. For each: what is literal, what is analogy,
   what is not simulated. Stability argument for every feedback path. Constants labelled
   "shape" until measured.
## N+1. Excitation and energy budget
   The bounded available-excitation proxy; the one monotonic mapping to audio; never
   per-event normalization.
## N+2. Resonators and root
   Mode counts, how the note tunes them, the compensation rule for material macros, the
   sibling toolkit used (`Modes`, `Strings`).
## N+3. Macro contract
   | Control | Default | Changes which model quantity | Required interactions |
   Every macro must be audible and measurable; name the test.
## N+4. Voices
   R1 ships two voices (the house pattern); the rest wait for the audition gate. Table of
   defaults as hypotheses.
## N+5. HOLD
   Which shipped strategy (Mercury's whole-period plan, Bore's cut-from-held, reserved,
   refused) and why. Seam measured against a real preceding cycle.
## N+6. Render budget and caching
   Target ms per knob turn on a phone; what is precomputed; the cache key (every macro that
   affects mechanics, model version); what pitch alone must not rebuild; solver caps and the
   flagged fallback when a cap is hit.
## N+7. Numerical and output requirements
   Determinism, finiteness, DC, peak, aliasing probes (against a higher-rate reference),
   classifier.
## N+8. Integration
   The checklist rows that apply, with file names that exist (grep them), and the deferrals
   (app picker) with the house's reason.
## N+9. Probes and auditions
   Mechanics probes, audio probes, the audition sections (voices at three notes, each macro
   swept, pairs at corners, edges, the kit, the LOOP ×8).
## N+10. Build rounds and gates
   R0 toolkit changes if any; R1 two voices and the probes; R2 HOLD; R3 voices and presets
   after listening; R4 kit, audition, docs. Each round ends at an audition gate.
## N+11. Sonic risks (where this would sound bad)
   Three to six named failure modes and the probe that catches each.
## N+12. Research references
   Papers, open-source code read (with license), products studied (research text only).
## N+13. Verified / proposed / heard ledger
   A short table: claim | status | evidence.
## As built (appended by the implementing session, same commit as the code)
```

## 3. Signal-flow diagram conventions

ASCII, left to right, one box per unit, arrows labelled with the signal's rate and kind.
Mark cacheable precomputation with [cache: keys]. Mark one-way readouts with a single arrow
and two-way couplings with a double arrow. Example:

```
note, velocity, macros
        │
        ▼
[Mechanics: rolling disk ODE]──(descriptors @1 kHz: α, contact φ, load, resistance power)──▶
   [cache: LEAN, BRAKE, COLLAPSE, model v]
        │
        ▼
[Excitation: contact texture ⊗ phase, bounded power]──(audio @176.4 kHz)──▶[Disk modes (6..8)]
                                                                            │
                                                          ──▶[Plate modes (6..10)]◀──(position weights)
                                                                            │
                                                                            ▼
                                   [Output: bandLimit → decimate → DC → levelTo → fadeTail]
```

## 4. Macro contract table shape

| Control | Default | What it changes in the model | Required interaction | Test |
|---|---:|---|---|---|
| TUNE | .50 | the root the resonators tune to | | pitch within N cents at every corner |
| BRAKE | .40 | μ in the resistance torque law (floor > 0) | BRAKE × COLLAPSE sets how much evolution precedes exit | trajectory length monotonic in BRAKE |

## 5. Verified / proposed / heard ledger shape

| Claim | Status | Evidence |
|---|---|---|
| `Dsp.seedFor` exists and takes vararg parts | verified | `Dsp.kt:147`, read 2026-10-21 |
| Six disk modes carry a clear root at C2 | proposed | none yet; R1 probe |
| The shudder is sharper than the rolling texture at lower energy | heard? | not heard; audition section EDGES |

## 6. Things the house will send back

- An API name that does not grep.
- A constant with no provenance and no "shape" label.
- A feedback path with no bound.
- Presets before the listen.
- A HOLD seam measured on a loop played twice.
- A final limiter doing the job of a stability argument.
- A macro that is a mix knob with a physical name.
- A render that takes seconds with no cache story.
- Maker names in engine, voice or preset names.
