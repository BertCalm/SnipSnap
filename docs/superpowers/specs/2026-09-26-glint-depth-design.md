# GLINT depth — a voice is a mechanism, and BODY gets a second source

**Status:** design, approved in conversation 2026-09-26. Not implemented.
**Date:** 2026-09-26
**Plan:** to be written (`docs/superpowers/plans/2026-09-26-glint-depth.md`)
**Parent:** [`2026-09-25-glint-phase-distortion-design.md`](2026-09-25-glint-phase-distortion-design.md) — Phase 1, shipped as roadmap S10. This is the depth pass its audition demanded.
**Roadmap:** the `SYNTH_ROADMAP.md` row is amended when implementation starts, not now.

## Why

Phase 1 shipped an engine that measures exactly as designed and does not sound like an instrument. Josh auditioned 32 clips on 2026-09-26. Six clips out of 32 earned KEEP — the three glass tails and the three upper harmonics. Nothing earned DROP. Almost everything earned OK, which in that vocabulary means mediocre.

His words, verbatim, are the design input:

> "Kazoo sounds like a bleep. Like an 8 bit instrument. Bottle sounds like a beep. Reed is a boing sound."
> "Like the harmonics — the voice itself needs some work, sounds like a boing. Like a jawharp."
> "Body 1 doesn't really seem to have an impact."
> "I don't get a sense of movement."
> "It sounds like a blip."

Every one of those words describes a short, simple, toy-like sound. Not glassy, not vocal, not resonant.

### The probe

A throwaway probe tested one hypothesis — that the envelope-driven macros finish before they can be heard. Partly right, and wrong in an instructive way:

| tested | verdict |
|---|---|
| Longer note (0.55 s → 1.48 s) | "It's better but still jawharpy" — short and long marked **equally** OK |
| BODY given room at the long length | **"Not really"** |
| BLOOM slowed sevenfold | **"Most improved on slow"** — the only KEEP |

Measured: at its shipped speed BLOOM's spectral centroid falls to 0.92× and then sits flat for the rest of the note. Slowed to a 0.45 s sweep it travels to 0.35× over 300 ms.

Klaus Schulze read that result more precisely than the hypothesis did: *"He shrugged at 1.48 seconds but leaned forward for the slow bloom. He is not asking for longer notes. He is asking for slower change."*

### The seance

Eight designers reviewed the engine. Six of eight independently named BLOOM's amount-speed coupling as the defect. Four of eight independently reached the same finding about BODY. Don Buchla stated the root cause most sharply:

> "There is only one source of harmonic content in this entire engine — the window's edge. Everything else is that same edge, restated. REED, BOTTLE, KAZOO are not three instruments — they are one window function with its corners rounded differently."

Bob Moog reached it from the other side: *"A second voice needs a second **source** of spectral information, not a second copy of the first one."*

That is the whole diagnosis. GLINT's elegance — the window doubling as the body waveform, so BODY cost one macro and no second oscillator — is exactly why BODY is inaudible and why three voices sound like one.

## What changes

Three things, in order of importance.

### 1. A voice becomes a mechanism, not a window shape

Every other engine in the fleet defines a voice as a whole character with its own mechanism: SKIN's KICK/SNARE/HAT, THUMP's eight, TIDE's BONGO/DRIP/GONG/FLARE. GLINT is the only engine whose voices are one function with different corners — which is Buchla's complaint restated as an architecture problem.

Six voices, four mechanisms:

| Voice | Window | Mechanism | Character |
|---|---|---|---|
| **REED** | saw | one window, one carrier | buzzy, bright — unchanged |
| **BOTTLE** | triangle | one window, one carrier | hollow, woody — unchanged |
| **KAZOO** | trapezoid | one window, one carrier | nasal, toy — unchanged |
| **CICADA** | triangle | carrier re-clocks inside every cycle | dense, a lattice of edges |
| **RATCHET** | trapezoid | the formant steps between fixed harmonics, never glides — a hard jump every ~150 ms, no interpolation | a tuning dial, deliberately toylike |
| **PLATE** | saw | the formant falls with the amplitude envelope, no separate clock | struck, physical |

Each voice supplies its own *kind* of movement, which is why removing BLOOM's rate knob costs nothing: a sweep, a lattice, a ladder of steps, and a fall tied to loudness are four different motions, and choosing between them is choosing a voice.

CICADA takes the triangle deliberately: it is the softest base, so the nesting supplies the edge rather than doubling one that is already there. RATCHET takes the trapezoid because the toy character is the point. PLATE takes the saw because a struck plate is brightest at the strike.

The names continue the existing family — objects that buzz or ring. A cicada is a nested-clock buzzer. A ratchet clicks through fixed positions. A struck plate's brightness tracks its loudness.

### 2. BODY becomes a real second source, on every voice

Not a new voice — a repair applied to all six. `body·(w − mean)` is replaced by a **second windowed burst** at its own ratio:

```
out = amp(t) · [ w(φ)·sin(2π·k(t)·φ)
               + BODY · env₂(t) · w(φ)·sin(2π·k₂·φ) ]
```

- `k₂ ≈ k / 5.6` — roughly two and a half octaves below the main formant, clamped to `K_MIN`
- `env₂` decays at ≈0.8 × the amp `t60`, **single-enveloped** — not compounded the way the old BODY was
- mixed at about 0.7 of the main burst at BODY 1

Measured in the structural probe: centroid 0.72–0.85× of the reference, a low colour that was not there before. Isao Tomita's framing is the one to keep in the code comment: *"Two formants, two speeds, is a vowel and the room it sits in. One formant doing double duty is a kazoo."*

The old BODY's double-envelope bug disappears with the old BODY.

### 3. BLOOM loses its coupling and keeps its macro

Six ghosts said split BLOOM into rate and depth. That would be a seventh macro and break the roadmap's 3–6 rule, so instead:

**BLOOM becomes depth only, at a fixed slow rate.**

`BLOOM_FAST_T60` and `BLOOM_SLOW_T60` collapse into a single `BLOOM_T60 = 0.45f` — the value the probe tested and Josh preferred. BLOOM's macro sets depth alone. The inverse coupling disappears because the rate is no longer coupled to anything; it is constant.

Nothing is lost. The fast sweep measured as static (0.92×) and nobody wanted it. Movement at other speeds is now a *voice* choice — PLATE's formant moves with the amplitude envelope, RATCHET's steps — rather than a knob position.

## Macros — still six, unchanged names

| Macro | Change |
|---|---|
| **TUNE** | none |
| **PEAK** | none, except the velocity fix below |
| **FOLLOW** | none |
| **BODY** | same macro, mixes a second formant instead of a redundant window copy |
| **BLOOM** | **how far the formant travels.** Rate is no longer a macro: on REED/BOTTLE/KAZOO/CICADA it is a sweep at a fixed `BLOOM_T60 = 0.45f`; on PLATE it is the depth of the loudness coupling (`k(t) = kBase·(1 + BLOOM·amp_env(t))`); on RATCHET it is how far up the harmonic ladder the steps climb. One meaning — the extent of the formant's travel — and each voice supplies *how* it travels |
| **DECAY** | none. Its 0.12–1.4 s range stays: the probe showed length is not what was missing |

## The velocity bug

Dave Smith found a defect rather than a taste, and it is verified:

| PEAK | hard `k` | soft `k` | |
|---|---|---|---|
| 0.03 – 0.06 | 2 | 2 | **byte-identical renders — no velocity response at all** |
| 0.075 and up | 3 | 2 | works |

`Velocity.atVelocity` scales the brightness macro by `VELOCITY_FLOOR_RATIO = 0.28125`, and because `PEAK` snaps to integers, both the soft and hard values quantise onto `k = 2` below about PEAK 0.06. The two renders come out the same bytes.

**Fix:** the dead zone exists only where snapping has a single integer to offer. Between `K_MIN = 2` and 3 there is exactly one integer, so snapping flattens that whole span onto `k = 2` — and that flattening is why both velocity layers landed on the same ratio.

The shipped fix is a floor, `SNAP_FLOOR = 3f`: `snapRatio` snaps only within `SNAP_FLOOR..SNAP_CEILING` and is identity below it, while `ratioFor` keeps snapping on every note exactly as before. An earlier attempt removed the snap from `ratioFor` instead, computing velocity layers at full resolution — but that silently dropped the per-note harmonic staircase whenever FOLLOW < 1, which is GLINT's default (0.8). At PEAK 0.3, FOLLOW 0, the old snapped code gave `k` = 10, 9, 9, 8 across semitones; the unsnapped version gave 9.824, 9.274, 8.753, 8.262, never landing on an integer. Harmonic landing was one of only two things the audition marked KEEP, so that approach was overridden before it shipped.

Measured across 51 PEAK samples, the floor cuts the dead zones from four (PEAK 0.00–0.06) to one, at PEAK exactly 0 — and `Velocity.atVelocity`'s existing `asked <= 1e-6f` guard already routes that case to `soften` before the collision matters, so it is zero in practice. A reviewer confirmed the margin analytically: the hard/soft ratio gap is `20^(0.575·asked)`, which grows with PEAK, so by the time the soft value reaches the floor (peak ≈ 0.319) the gap is already ≈1.6 in `k` — past the ≤1 a rounding collision needs. The defect cannot recur inside the band. The audition corroborates the floor from the other direction: `k` = 3, 5 and 8 were marked KEEP, `k` = 2 only "ok" — the zone worth keeping was `[3,12]` all along.

Smith's rule is still the comment to keep: *"Quantization for the ear, full resolution for the diff — never conflate them again."* The shipped fix honors it by trimming where the snap applies rather than by splitting the ratio into two computations — the render's `k` is never conflated across velocities inside `[3,12]`, which is the outcome the rule demands.

The snap range is therefore `k = 3…12`, not `2…12` as Phase 1 stated. `k = 2` is deliberately left unsnapped: that sub-range offered only one integer, and flattening onto it was the defect. One asymmetry falls out of the floor: `k = 3`'s snap catchment is `[3.0, 3.5)` rather than the `[2.5, 3.5)` every other integer in the band gets, because below 3.0 the ratio runs free. Harmless, but it makes `k = 3` a slightly thinner target for `scramble`'s random draws.

This matters beyond GLINT: the patch format and the velocity system are shared with ten sibling engines, and GLINT is the first to snap a brightness macro.

## Data flow and compatibility

`GlintVoice` grows from three entries to six. That is a **breaking change to any stored GLINT patch** — but there are none: GLINT ships no presets, and no factory kit places a GLINT pad. `Patches.decode` rejects an unknown voice name already, so a hand-written patch naming a removed voice fails loudly rather than rendering the wrong sound. Nothing to migrate.

Registration points, and this table is **not exhaustive** — Phase 1 learned that the hard way:

| File | Change |
|---|---|
| `Glint.kt` | three new mechanisms, BODY redefined, BLOOM's constants collapsed, `macrosFor` returns five for RATCHET and PLATE; `SNAP_FLOOR = 3f` added, `snapRatio` floored to `SNAP_FLOOR..SNAP_CEILING` |
| `GlintTest.kt` | the new mechanisms' tests; existing tests re-pointed at six voices |
| `Velocity.kt` | none — the fix lives in `Glint.kt`'s snap, not in how velocity computes its ratio |
| `SynthScreen.kt` | nothing — voices come from `GlintVoice.entries`, and `drumClass` already maps every voice to TONAL |
| `docs/SYNTH_ROADMAP.md` | amend the S10 row |

Before implementation, grep for every exhaustive `when` over `Patch` and every hardcoded engine or voice list rather than trusting this table. Phase 1's "exhaustive" registration table missed `Velocity.macroSpecsFor`'s sealed `when` — which stops `:synth` compiling — and `shell`'s `UserPresetsTest` roster.

## Testing

Everything Phase 1 proved must keep passing, re-pointed at six voices. In particular:

- **Periodicity** — the correlation at the exact fractional one-period lag, threshold 0.98, at both BLOOM extremes. **CICADA is the risk**: a carrier that re-clocks inside the cycle must still leave the output periodic at `f0`, because its sub-cycles divide the cycle an integer number of times. If CICADA cannot hold 0.98, its sub-division is wrong, not the test.
- **The formant lands where it is named** — the Goertzel test, extended to the second formant: energy at `k₂·f0` must exceed its decoys.
- **No click** — at fractional part 0.25, where the discontinuity is maximal. CICADA's inner restarts must each land on silence too, or it will click at every sub-cycle rather than once per cycle.
- **Velocity** — a new test that the soft and hard renders differ at every PEAK from 0 to 1, which is the bug above stated as an assertion.
- **RATCHET's steps** — the centroid must be piecewise constant, not a ramp: measure in windows and assert the plateaus.
- **PLATE's coupling** — the centroid's fall must track the amplitude envelope's fall, not a separate curve.

Every new threshold is set from a measurement taken during implementation, never from a prediction. Phase 1 lost three rounds to predicted numbers that were wrong by up to 10×.

## Phasing and gates

| Phase | Ships | Gate |
|---|---|---|
| **D1** | BODY as a second formant, on the existing three voices; BLOOM decoupled; the velocity fix | audition before D2 |
| **D2** | CICADA, RATCHET, PLATE | D1 audition passed |
| **D3** | the preset roster, authored by ear | D2 audition passed |

D1 first because it is the repair the whole council agreed on, it touches every voice, and it can be heard on the engine that already exists. Building three new mechanisms on top of a broken BODY would mean auditioning six voices that all share one known defect.

## What this displaces

**TRACE — the voice whose window comes from a captured snip or a photograph — moves behind all of this.** It was Phase 2 and it is now after D3.

Buchla's warning is the reason, and it is worth quoting in full because TRACE was the most attractive idea in the original design:

> "Ship TRACE before you fix this and you'll just get photographs of jawharps. A user-supplied window doesn't add a new mechanism, it adds new *content* to the same single mechanism — you'll have infinite costumes on the same one-note actor. Depth isn't more shapes. It's more independent things happening per sample."

TRACE multiplies variety. It does not add depth. It is worth building once there is a second source for it to sit against — at which point a captured window shaping *two* formants is a genuinely different proposition.

## The dissent, recorded

Ikutaro Kakehashi argued against the other seven, and this spec does not follow him. It should be on the record that he may be right:

> "Your owner did not describe a failure, he described a *lineage*. 'Bleep,' '8-bit,' 'jawharp' — these are the exact words people used for the TB-303 before it was acid... You built a fretting instrument and are apologizing for the fret buzz. Don't fix the jawharp. Feature it. Ship the bleep."

RATCHET is his position given a voice rather than the whole engine. If the D2 audition finds RATCHET is the one people reach for, that is Kakehashi's verdict arriving late, and the roadmap should follow it rather than defend this spec.

## Out of scope

- **Stereo, space, width.** Tomita wanted distance and air; the engine is mono by design and the FX rack owns reverb. Not this pass.
- **A seventh macro.** The 3–6 rule holds. Movement variety comes from voices.
- **Extending DECAY past 1.4 s.** Length was measured not to be the problem.
- **Real-time performance, aftertouch, note-off.** GLINT renders one-shots; that is the engine's contract.

## Decisions taken in conversation — 2026-09-26

| Question | Decision |
|---|---|
| Should the four probe variants be switches? | No — switches would be a patchbay and break the macro budget. They become voices. |
| All four as voices? | Three become voices (CICADA, RATCHET, PLATE). The fourth, the second formant, becomes how BODY works on every voice — a dead macro on five of six voices is not defensible. |
| Split BLOOM into rate and depth? | No — that is a seventh macro. BLOOM becomes depth at a fixed slow rate, which removes the coupling without adding a knob. |
| Keep KAZOO alongside RATCHET? | Yes. They share a window and differ in mechanism, which is exactly what a voice now means. |
| When does TRACE land? | After D3. It multiplies costumes until there is a second actor. |
