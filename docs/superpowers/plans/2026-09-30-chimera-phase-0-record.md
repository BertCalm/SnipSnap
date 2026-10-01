# CHIMERA — the Phase-0 record: what was tried, how, and what it measured

**Status:** a record, not a plan. Nothing here is in the build. Every program below ran in a throwaway worktree
(the spikes at `5e3f5f3e`, the Phase-0 re-measure at `75b550c1`) and was then discarded; the sources are kept
as text and as patches, as far as "Durability" and "How to re-run" say, the way the ARCO record
([`2026-09-29-arco-phase-0-spike.md`](2026-09-29-arco-phase-0-spike.md)) and BORE's
([`2026-09-28-bore-phase-0-spike.md`](2026-09-28-bore-phase-0-spike.md)) keep theirs. The specs build from their
own designs rather than copying anything here. Every excerpt below is labelled **spike, not for the build**.
**Date:** 2026-09-30
**Tree for code cites:** `75b550c1`. Evidence was measured at `5e3f5f3e` (and design maps at `1a2ec180`); every
`file:line` below was re-read at `75b550c1`.
**Related:** the Group A spec
[`../specs/2026-09-30-become-strung-say-design.md`](../specs/2026-09-30-become-strung-say-design.md) (BECOME, STRUNG,
SAY) and the Group B spec for the TERRA hook (HIT, BEND, TALK)
[`../specs/2026-09-30-terra-hit-bend-talk-design.md`](../specs/2026-09-30-terra-hit-bend-talk-design.md). "The brief",
used below, is the owner-approved design brief the two specs were written from (a session working file, not in the tree); each
spec restates the owner's decisions in its "Decisions already taken". This record adds measurements and method. It changes no
decision. Where a measurement shows that a sentence of the owner-approved design is inexact or cannot hold as worded,
this record says so plainly and asks the owner in its closing table; the list is in "What the numbers settled".
**Evidence (local, read-only, not in the tree):**
`~/Documents/snipsnap-chimera-evidence-2026-09-29/` (README there); Phase-0 working files in the session scratchpad
(see "Durability" below).

## What this file holds

The CHIMERA brainstorm began with two outside documents proposing a "meta-engine" that renders two engines and
joins them. The review came first (§1) and six rounds followed (§2 to §7). This record keeps, for each: what it
tested, how it was built and gated, and the numbers it produced.

1. **The six-lens review** of the proposal (§1).
2. **Round 1 audition**: 140 clips from prototypes of the proposal as written, as fixed, and of what the app
   already does (§2).
3. **Round 2, struck**: another pad's hit ringing TERRA's own modes, 55 clips from two sub-experiments (§3).
4. **Round 2, hybrids**: the post-render hybrids made as good as they could be, 56 clips (§4).
5. **The "start here" page**: nine clips replacing 111 (§5).
6. **The bold round**: steer, ring and talk prototypes, nine clips (§6).
7. **Phase 0 at `75b550c1`**: the Group B experiments re-run on today's tree, plus the HIT-at-1 listening check (§7).

Then the formulas and short excerpts of the adopted techniques (§8), the load-bearing numbers with their tags
(§9), what was rejected and why (§10), what was not measured (§11), and the appendices: the Phase-0 tables restated
in full (A), the Phase-0 prototype source (B), the spike map and patches (C), and the Phase-0 listening page (D).

## Durability: what survives and what does not

- **In the evidence folder:** the six-lens reports, every audition clip and note, the struck, hybrid and bold experiment
  folders, and `spikes/` (the patches and new files of the eight spike worktrees). It does **not** hold Phase 0.
- **Not in the evidence folder:** Phase 0's own files live in the session scratchpad
  (`.../scratchpad/phase0-work/`, `.../scratchpad/chimera-audition/PHASE0/`, the throwaway worktree `snipsnap-phase0`).
  They are: the twenty-two test sources (twenty in `:synth`, two in `:shell`) and the build diff (`phase0-work/code/`), the tables and findings
  (`tables.md`, `findings.json`), the data folders, the six listening clips and their page fragment
  (`fragments-phase0.json`). A scratchpad does not outlive its session, so this record keeps what it can in text:
  the Phase-0 tables (Appendix A), the prototype source in full and the harness switch (Appendix B), and the lists of
  sources and tasks in "How to re-run". **What is not embedded:** the harness itself (`Phase0.kt`, `Phase0Bold.kt`), which exists only
  in the scratchpad, and the spike sources it builds on, which can be rebuilt from the evidence `spikes/` plus the switch in
  Appendix B. Until the harness is copied into the evidence folder, "re-run Phase 0" is a rebuild of the spikes and a rewrite
  of the harness, not a replay, and the six Phase-0 clips (Appendix D) are not in the evidence folder either.
- **Tags used below:** `(phase0 §N)` and `(phase0 G-xx)` cite a section number or gate name of the Phase-0 report, a
  scratchpad file that is not durable. Every figure so tagged is restated in §7 or in Appendix A, whose table headings carry
  the section number (for example "§4.2"), so each tag can be resolved inside this record; `(phase0 T#)` is a table of that
  report. `(struck-shape S#)`, `(struck-motion M#)`, `(struck-r2)`, `(verify)`, `(verify-r2)`, `(hybrid-r2)`,
  `(bold-steer)`, `(bold-ring)`, `(bold-talk)` are the evidence reports of those names; `(synthesis §N)` is the six-lens
  synthesis; `(empirical §N)`, `(empirical F#)`, `(dsp F#)`, `(api B#)`, `(fleet F#)`, `(house F#)`, `(product F#)` are the
  six lenses.
- **Two uses of "round" and of "S1":** "round 1" and "round 2" always mean the two audition rounds of the experiments (§2,
  §3). "Build round R1" to "R4" mean the Group B build rounds the brief names: R1 HIT in the engine, R2 TERRA on the phone,
  R3 BEND and TALK, R4 the pad-sheet group and the shared chooser. "S1" is struck-shape's section 1, which tested the
  sin-theta gain scaling (§3.1, item 1); "after S1", "S1 gain" and "S1 on" name that fix, and "the S1 target" is its
  per-mode impulse match.

## How to re-run

**The spikes (all at `5e3f5f3e`).** Each spike is a worktree's tracked edits (`changes.patch`, which is only one
or two appended gradle tasks) plus its untracked files (`new-files/`). To rebuild one: check out `5e3f5f3e` in a
scratch worktree, `git apply spikes/<name>/changes.patch`, copy `new-files/` over the tree, then run its task.
Every spike lives in the test source sets (`synth/src/test/...`, and `shell/src/test/...` for the "nearest in the
app" runners) because it needs `internal` members of `:synth`. None belongs in the tree; remove the files after a run.

| spike folder | round | gradle tasks it registers (read from its `changes.patch`) |
|---|---|---|
| `empirical` | round-1 CHIMERA review and audition; hybrid-tune; the round-2 hybrid generator | `generateChimeraAudition`, `hybridTune`, `hybridR2` |
| `hybrid2` | hybrid-macros (macro liveness, product, cost) | `generateChimeraAudition`, `hybridMacros` |
| `mutate` | round-1 MUTATE clips (what the app already does) | `generateMutateAudition` (:shell), `mutateVoxKickSpike` |
| `struck` | round-1 STRUCK; struck-shape; the round-2 struck generator | `generateTerraStruckAudition`, `runStruckShape`, `generateStruckR2` |
| `struck2` | struck-motion | `generateTerraStruckAudition`, `runStruckMotion` |
| `bold-steer` | bold round, steer | `steerSpike`, `steerNearest` (:shell) |
| `bold-ring` | bold round, ring | `generateRing`, `generateRingNearest` (:shell) |
| `bold-talk` | bold round, talk | `talkRender`, `talkNearest` (:shell) |

Example (from `bold-steer/notes.md`): `./gradlew --offline :synth:steerSpike` writes the three clips and the exact
macros of every part; `./gradlew --offline :shell:steerNearest` scores each against the nearest thing the app can
already make. Run every task offline and one at a time; each prints its tables to stdout and to a file named in its source.

**Phase 0 (at `75b550c1`).** Make a detached worktree at `75b550c1` and copy these into the test source sets. They are the
twenty-two files of the scratchpad's `phase0-work/code/`; see "Durability" for where that is.
- `synth/src/test/kotlin/com/snipsnap/synth/`: the prototype `Phase0Bank` (Appendix B, in full); the harness `Phase0`
  (modes `replay gates buzz timing bold clips hit1 s1`) and `Phase0Bold`; the struck spikes `TerraStruckSpike`,
  `TerraStruckShape`, `TerraStruckShapeRunner`, `TerraStruckShapeCandidates`, `StruckMetrics`, `TerraStruckMotion` (the
  capture rule; the harness uses it) and `TerraStruckR2`; the steer spikes `SteerSpike`, `SteerTerra`, `SteerTide`; the talk
  spikes `TalkDrum`, `TalkWord`, `TalkClips`, `TalkGong`, `TalkKick`, `TalkMeasure`, `TalkUtil`.
- `shell/src/test/kotlin/com/snipsnap/shell/`: the nearest-in-the-app runners `SteerNearest` and `TalkNearest`.
- The spike sources are the evidence's `spikes/struck`, `struck2`, `bold-steer` and `bold-talk` files, rebased by one switch,
  `TerraStruckSpike.legacyCompressor` (default off): off is today's output chain, on re-creates the chain before
  `a6dcb87e`. The switch is in no evidence spike; Appendix B.2 gives its text.
- Four gradle tasks, in the build diff (`build-gradle-and-tracked.diff`): `:synth:phase0Shape`, `:synth:phase0`,
  `:shell:steerNearestP0`, `:shell:talkNearestP0`. They pass `-Plegacy` to the switch as the system property `p0.legacy`,
  and `phase0` and `phase0Shape` hard-code the scratchpad as their output folder, so a rebuild must edit that path.

Commands as run: `./gradlew --offline :synth:phase0Shape -Plegacy=false -Poutsub=data -Psections=all` and
`./gradlew --offline :synth:phase0 -Plegacy=true|false -Pmode=replay,gates,buzz,timing,bold,clips`
(`-Plegacy=true` is the BEFORE column; `-Pmode` also takes `hit1` and `s1`).

## What the numbers settled

- The proposal does not compile (12 errors, 3 root causes) and, once it does, 6 of its 7 voices are decimated twice
  (0.250 times length, 4.000 times pitch) (synthesis §1, §2).
- At defaults, the post-render hybrids are sounds the app already makes: NCC (normalised cross-correlation of two waveforms: 1 is identical, 0 unrelated) 0.9993 to 1.0000 against TERRA alone,
  TIDE alone or MUTATE's stack, from 20 ms (hybrid-r2 §0). That is why CHIMERA is not an engine.
- The real gap is one pad's hit exciting another engine's body. Adding the two bodies as signals is not a blend;
  blending in the gain domain is (struck-shape S6, S6b). That is HIT.
- Three motions the app cannot make were prototyped and kept by the owner: a pitch curve borrowed from another hit
  (BEND), a vowel path shaping a body's partials (TALK), a formant filter that follows a word (SAY). Two more
  fall out as small changes to what exists: a morph over time (BECOME), sympathetic strings (STRUNG).
- Phase 0 re-ran the Group B experiments on today's tree. HIT 0.5 did not move; two things moved (§7, items 1 and 2).
- Phase 0 and the writing of this record found places where the owner-approved wording is inexact or cannot hold as
  written. None is reworded here; each is listed in §7 or §8 and is an owner decision in the closing table, except the
  zero-gain item, which changes only what its test compares (a spec instruction, not a decision):
  - "BUZZ/CAVITY drive unchanged at the level match" cannot hold, because the level match is a peak match on the whole body (§7.4);
  - a zero gain that freezes the phase "then clicks on reopen": the measured effect is a phase error, not a click (§7.6);
  - "rings about 1.6 times longer under TALK" is the mode-1 figure only: the measured clip uses ratios of 1.6 to 2.7 (§8.4);
  - the capture fallback "when the head peak is below 1e-4": the measured rule tests the whole-source peak (§8.2);
  - the three-clip HIT-at-1 check was built as six clips (§7, "The listening check").
  One more item conflicts with a round-2 target and not with the brief (§7.5).

---

## 1. The six-lens review (synthesis, api, dsp, empirical, fleet, product, house)

**What it tested.** Six reviewers read the proposal against the tree at `5e3f5f3e` through six lenses: `api` (does it
compile against the real signatures), `dsp` (the signal maths), `empirical` (build it with the minimum compile fix,
run it and its own tests), `fleet` (does the app already do each voice), `product` (is each voice worth a pad),
`house` (does it follow the house's process). Two verifiers re-checked the findings from fresh code reads and
fresh renders: `verify-code` (33 findings: 32 confirmed, 1 uncertain, 0 refuted) and `verify-signal` (18 findings,
all confirmed, four with a corrected sub-claim). One finding overturned a lens and its verifier: the lambda two
lenses named as "the natural injection point" for a striker in TERRA's loop does not drive its modes (synthesis intro).

**How.** The empirical lens transcribed the proposal's code into a worktree with three compile-driven changes
(rename three enum constants, delete six arguments) and ran it (`ChimeraProbeTest`, `ChimeraTest`), at every voice and
a 300-render random sweep, with a sine-through-the-back-end test to prove the rate fault, and a "correct-rate
counterfactual" that up-samples each finished part before the proposal's own blend. Its renders were the first
audition (§2).

**What it found** (all tagged to the synthesis table):
- 12 compile errors from 3 root causes, all in `Terra.render`: wrong enum names (6), a `seed` parameter that does not
  exist (4), a `striker` parameter that belongs to FORK (2) (api B1-B3, empirical F1; synthesis §1).
- Six voices are decimated twice: a finished 44.1 kHz clip is passed to a stage that assumes four times the rate.
  Sine test: 22050 frames in, 5512 out, pitch exactly 4.000 times (200/1000/3000/4875 Hz become
  800/4000/12000/19500 Hz); a true 176.4 kHz sine is unchanged (control). Real renders at defaults: ratio 0.250 on
  all six; the seventh (THROAT) is 1.000 because it was written at the true rate (empirical §A2, dsp F1; synthesis §2).
  Relabelling the output as 11025 Hz proves it: 0.4900 s, f0 157.5 Hz against TERRA alone at 156.4 Hz.
- Two of its four tests fail: peak above the ceiling (six voices peak at exactly 1.0) and a KICK classification
  (it reads TOM at the correct rate; SNARE came up in 0 of 448 grid renders). Registering its 11 presets breaks
  `PresetsTest` (expected 848, got 859). Full `:synth:test`: 1051 tests, 3 failures (empirical F6 for the two failing
  `ChimeraTest` tests, empirical F9 for `PresetsTest` and the full suite; synthesis §3).
- Five of its seven voices duplicate MUTATE (SPLICE or STACK) or the RING chip (fleet F1, verified). Two
  (BEATBOX_TERRA, WRAITH_TERRA) are bit-identical to each other and to TERRA alone once it compiles, because the
  vocal hit is rendered and thrown away (empirical F4). One (THROAT_TERRA) calls no TERRA at all (dsp F6).
- 13 of 42 voice-macro slots are dead, measured bit-identical at 0 and 1 (empirical F5); preset names hit the house
  name blocklist (product F1-F3, house F7).
- The one idea worth carrying: another pad's hit, stored as data, rings TERRA's modes (synthesis §5, §6; fleet F2).
  FORK already does this on its own side (`Fork.striker`, `ForkPatch.striker`).

## 2. Round 1 audition: 140 clips

**What it tested, by section** (counts from the folders; all mono 44.1 kHz 16-bit, levelled by `AuditionLevel.level`,
verified by an independent re-measurement of all 140: 0 mismatches on duration, centroid or loudest partial) (verify):

| section | clips | what it is |
|---|---|---|
| AS_WRITTEN | 18 | the proposal's engine, compile-fixed, 7 voices at defaults and its 11 presets |
| PARTS | 14 | each component engine alone at the proposal's own settings |
| FIXED | 13 | the correct-rate counterfactual: parts up-sampled once, then the proposal's own blend and back end |
| MULTIPLY | 10 | the proposal's ring-and-drive topology |
| MUTATE | 18 | what the app already makes from the same pairs (MUTATE SPLICE, STACK) |
| STRUCK | 67 | TERRA struck by another pad's hit: *ringed* (the striker rings TERRA's mode bank) against *layered* (the striker added as an exciter), 4 voices, three strikers, with and without a first difference |

**How STRUCK was built** (`struck/notes-STRUCK.md`): everything TERRA keeps private (mode tables, exciters, the
additive bank, the cavity stage, the output chain) was copied into the spike and asserted bit-identical to
`Terra.render` for all four voices at 24 macro probes; the generator aborted if any probe differed. *Ringed*: the
882-sample `Fork.striker` head, up-sampled to 176.4 kHz, zero-padded, through `Modes.ring` with TERRA's own mode
table, then the voice's own post-bank stage and output chain. *Layered*: the striker stands in for TERRA's exciter
lambda.

**What it found.**
- The proposal's fixed hybrids are 0.250 times length and 4 times pitch; `FIXED` is 1.000 and 4.000 times that
  (verify §1; frame counts exact, all seven voices).
- Ringed against layered: the ringed body's spectral centroid moves with the striker but only by 2.0 Hz (1.7 %)
  over the whole body and 6.9 Hz (about 6 %) over the first 93 ms; the measure that separates them is overtone
  balance: ringed spread 14 dB, layered 0.00 dB (verify §2). "Coupling" is therefore real but is not a centroid
  effect.
- The owner's verdict on round 1: "they sound ok", and asked for experiments to see what to tweak. They chose
  both tracks (struck and hybrid).

## 3. Round 2, struck: two sub-experiments, one convergence spec, 55 clips

### 3.1 struck-shape (brightness and coupling)

**What it tested.** Why round 1's ringed bodies were dull, and how to blend the striker's colour in. Ten strikers:
six synthetic (BEATBOX KICK, BEATBOX RIM, THUMP KICK, THUMP SNARE, WRAITH WORD, NOISE HAMMER) and four factory
samples (kick, snare, closed hat, clap), each captured with `Fork.striker`. Sections S0 to S7 and a diagnostic. The synthetic strikers are renders of the app's own
engines: THUMP KICK and THUMP SNARE are THUMP voices; BEATBOX KICK and BEATBOX RIM are VOX's BEATBOX voice; WRAITH WORD is
VOX's WRAITH voice (sine-wave speech, `Vox.kt:54`) at its own WORD macro .2, which has nothing to do with SAY's WORD macro;
NOISE HAMMER is FORK's noise exciter. The BEATBOX, WRAITH and THROAT voices dropped in §10 are the proposal's `_TERRA`
voices, not VOX's.

**How.** Sections `gate s0 s1 s2 s3 s3b s4 s5 s6 s6b s7 timing diag` of one runner (`TerraStruckShapeRunner`); the
mode bank in four implementations (shipped `Modes.ring`, a sin-theta gain version, a double-precision version, and
a complex-phasor version that equals the additive bank for an impulse). Measures: **OB** (overtone balance) =
10 log10 of the energy above 1.5 times the fundamental over the energy at or below it, from a Hann (smooth bell-shaped
window) power spectrum from 20 ms after the 1 % onset; f0 by a 2^17 FFT over 100-300 ms; time to peak; first-5-ms peak; crest; class from
`Classifier`. A pure decaying sine through the same chain gives the **OB floor**: within 3 dB of it a row is
distortion products, not mode energy (struck-shape §1.5).

**What it found** (struck-shape §0):
1. Round 1's dullness was two things. The resonator's 1/sin(theta) tilt is cancelled exactly by scaling each mode's
   gain by sin(theta) (this is struck-shape's section S1, and "S1" below names the fix): an impulse through the bank then equals today's additive modes to 0.00 dB per mode (max 0.09 dB
   across voices; residual -66 to -82 dB). What is left is the striker's own spectrum, which is the real coupling.
2. After the head, the ringed body equals today's modes times the striker's magnitude at each mode (S7A: at most
   0.31 dB per mode from 20 ms on).
3. Brightness is still striker-dependent after S1: mean OB against unstruck, ten strikers: membrane -0.7 dB (range
   -15.7 to +6.9), cavity +7.1, bell -2.9, bar -9.5 (range -33.8 to +27.1). Bass-heavy strikers starve the upper
   modes. Too much coupling, not too little, is the risk.
4. **Blending has to change.** Adding today's body and the ringed body as signals is not a blend: the two are
   uncorrelated in phase (Pearson correlation of the two bodies, -0.99 to +1.00), modes interfere, and OB is non-monotone in the blend amount for 6
   to 9 of 10 strikers per voice (membrane THUMP KICK reads -19.6 dB at .75 between endpoints of 0 and -7.1 dB).
   Blending **in the gain domain** (each mode's gain times `(1-c) + c*s*|P_k(n)|`) is monotone in c for 10 of 10
   strikers on membrane, bell and bar (9 of 10 on the cavity), is bit-identical to TERRA at c = 0 or with an impulse
   striker, and at c = .5 keeps mean brightness within 1.6 dB (membrane -0.33, bell -1.12, bar -1.61, cavity +3.0).
5. Other knobs: a first difference at .5 is inert (at most 0.03 dB) and is a tilt, not a coupling control; FORK's
   absolute 1.5-9 kHz FORCE low-pass is inert on the low voices (0.06-0.17 dB on membrane); a voice-relative one
   moves OB 2-3 dB. A bass-heavy striker delays the ringed clip's peak by 6-24 ms (0.1-0.4 ms unstruck), and only a
   striker-shaped head brings it back.
6. Side finding: `Modes.ring`'s float coefficients detune low modes (cavity -4.7 cents at default TUNE, +13.8 at
   TUNE 0; membrane -7.89 cents at TUNE 0 in struck-motion M1.0). Offered separately (design brief, out of scope).

### 3.2 struck-motion (droop, head, cavity, CLACK, BUZZ, cost, robustness)

**What it tested.** Seven things a TERRA striker path has to survive. M1 droop when ringed; M2 head length and
window; M3 cavity drive staging; M4 CLACK composed with a striker; M5 BUZZ on a ringed body; M6 cost; M7
robustness. Gates G1 to G4 (replica equals `Terra.render` bit for bit: 32 probes; the 20 ms head equals
`Fork.striker` bit for bit: 10 strikers; the round-1 path equals `Modes.ring` at DROOP 0).

**What it found** (struck-r2 §0): a 20 ms head with the house 2 ms raised-cosine fade (a 40 ms head rejected);
double-precision coefficients; the coupled/rotation form retuned every sample reproduces TERRA's droop; the cavity's
tanh is near-linear at today's drive; no cost blow-up (ringed with a cheap reference 0.66-0.96 times an unstruck
render; the gain-domain spike 1.3-1.5 times, of which about 1.5 ms is a second resample a build does not need).
M7 produced the **safe capture rule** (§8.2): round 1's `Fork.striker` renders digital silence for a kick preceded by
20 ms of silence, for a single-sample spike after sample 881, and for any source whose head is silent (struck-motion M7.1).

**Cross-report correction** (struck-r2 §0): struck-motion's recommended retune ran with the tilt on (without S1), so
its secondary numbers (M2 mode-1 bumps, M3 tanh levels, M4 click ratio, M5 BUZZ time, the STD membrane OB) were
pre-S1 and had to be re-measured on the combined candidate.

### 3.3 The convergence spec and the 55 clips

`struck-r2-spec.md` combined the two into three candidates. The audition (55 clips, three sections) was rendered by
`generateStruckR2` with the hard gates G-A1 to G-A4, G-B1, G-C1, G-R1, G-R2 enforced (the run aborts on a failed gate). G-A1 to G-A3 are
bit-identity checks of the coloured candidates against `Terra.render`; G-A4 compares A-D with RUNG; G-B1 checks RUNG with an
impulse against `Terra.render`; G-C1 checks that the capture head equals `Fork.striker`; G-R1 and G-R2 check the two anchor
clips (today's drum byte-equal to round 1's, and the round-1 clip a sha-256-checked copy);
five runs, the last byte-identical to the fourth (`notes-r2-struck.md`).
- **A, COLOURED:** TERRA's own additive bank with each mode's gain multiplied per sample by
  `(1-c) + c*s*|P_k(n)|` (the adopted HIT, §8.1). A-D reads the striker at the drooping pitch.
- **B, RUNG:** the striker rings TERRA's own resonators (retuned by DROOP, S1 gain, a FORCE low-pass, TERRA's click
  on top). Kept as the **strong reference clip**, not as the algorithm.
- **C, RUNG with an edge:** bell and bar only. Not adopted.
- Anchors: today's drum, and a byte copy of the round-1 clip the owner had heard (checked by sha256).

**Gate outcomes (round 2, as run).** G-A1, G-A2, G-A3, G-C1: bit-identical. G-B1 on the cavity needed its tolerance
widened from 0.06 to 0.10 dB to pass (0.0930 dB) (notes-r2-struck, deviation 3). G-A4 (A-D against RUNG with the
low-pass off) **failed on the membrane**: per-mode band levels differ by 1.86, 4.58 and 2.13 dB for the three
strikers, although OB agrees to 0.00 dB; most of the gap is one overall level offset (after removing the median,
the worst is 1.55, 0.28 and 0.07 dB). The five A-D clips were dropped from the page (55, not 60) and rendered
off-page.

**Independent verification** (`verify-r2`, 111 clips): every description number reproduced (durations to 0.23 %, f0 to
0.055 %, centroids to 0.57 %, OB above -40 dB to 0.22 dB); OB below -45 dB cannot be carried by a 16-bit file (floor
about -51 dB), so those claims are float-render figures. The struck "keeps today's brightness" claim holds only for
COLOURED 50 % on the membrane.

## 4. Round 2, hybrids: two sub-experiments, one spec, 56 clips

**What it tested.** Whether the post-render hybrids, made as good as they could be, earn a voice. `hybrid-tune`: H1
pitch lock between the two parts, H2 the splice law (equal-power against linear) and gain match, H3 a measured
drum-class map in place of the proposal's thresholds, X1 native rate, X2 a detector check. `hybrid-macros`: H4 macro
liveness at 0 and 1 for every slot, H5 the TINES_TIDE product, H6 the TERRA_TINES pitch lock, H7 THROAT as a drum, H8
cost. The convergence spec (`hybrid-r2-spec.md`) defined C1 (round-1 sound, cleaned), C2 (C1 plus every treatment
adopted or left for the ear) and C3 (no engine: MUTATE). The 56 clips were three sections (SPLIT 22, BLEND 23,
THROAT 11); a polyphase up-sampler (`up4fast`) was bit-identical to `Resampler.resample` and took the correct-rate
path from 2.6-9.1 times its costliest part to 1.3-2.5 times (hybrid-r2 §6.2).

**What it found** (hybrid-r2 §0, §6):
- Round-1 FIXED clips against what the app already makes, NCC from 20 ms: THUMP_TERRA against TERRA alone **1.0000**
  and against MUTATE's 10 ms splice **1.0000**; THUMP_TIDE against TIDE alone **0.9993**; TERRA_TINES against MUTATE
  STACK **0.9997**. The split hybrids' 9.3 ms head is under half a cycle of the kick's own 46-78 Hz.
- The authored `drumClassFor` agrees with the classifier on 32 % of THUMP_TERRA cells and 10 % of THUMP_TIDE cells;
  gain match is phase-sensitive as built (2 ms windows; applied gain moved from +17.8 to +9.1 dB across the knob);
  THROAT as a drum needs a source change to VOX; TINES_TIDE's audible length is 0.095-0.12 s in a 0.458 s buffer.
- Conclusion carried into the brief: CHIMERA is not an engine. Its post-render hybrids equal MUTATE or TERRA alone
  after 20 ms. The idea survives as data inside TERRA (HIT) and as MUTATE/rack additions.
- Both sub-experiments' own `report.md` writes were refused; their durable records are the `work/` and `data/` tables.

## 5. The "start here" page

The owner found 111 clips too many and gave up. A nine-clip page replaced it (`START_HERE`: `cm_unstruck`,
`cm_a50_tkick`, `cm_a50_wraith`, `cm_b_tkick`, `cm_b_wraith`, `ttk_c2_s80`, `ttk_mutate_splice40`, `ttn_c2_unison`,
`ttn_mutate_stack`: today's drum, HIT .5 with two strikers, the strong "rung" recipe with two strikers, and the two
hybrid pairs against what MUTATE makes). Owner answers: **Q1** "A knob between them" (today, subtle, strong) for
TERRA struck; **Q2** "I think we could go further/bolder" on hybrids. A standing preference followed: every audition
page leads with at most 10 clips and at most 3 questions; every gate in both specs follows it. The brief dates the owner's
answers only as the range 2026-09-29 to 2026-09-30, and the evidence folder holds no per-answer dates, so none is given
here; the two answers above are quoted as the brief quotes them.

## 6. The bold round: the steer, ring and talk families (nine clips)

In this section "steer", "ring" and "talk" in lower case name the three families of prototypes. The TALK knob (§8.4), the
RING chip and the SAY section are the app features, always written in capitals; the families are not features.

Each prototype copied an engine's loop into a test source object, gave it one extra input, and trusted the copy only
after its neutral setting reproduced the engine bit for bit. Each clip was scored against everything the app can already
make (a "nearest in the app" search over MUTATE modes at several knob settings, the rack chips that move in time, and the
engines' own versions of each gesture) by normalised cross-correlation (NCC) from 20 ms after the onset, best lag
within 64 samples, and by a phase-blind spectrogram correlation; the gate was NCC below 0.9. Nobody had heard any of
them when the notes were written. **Owner verdict, as the brief records it: keep Steer, Ring and Talk** (the brief does not
quote the owner's words here).

### 6.1 The steer family: one engine's motion drives another engine's parameter

Gates (all bit-for-bit): TERRA with the droop line replaced by a curve equals `Terra.render` (21609 frames); TIDE
with the fold amount as a per-sample function equals `Tide.render` (55870 frames); TINES with per-sample index and
pitch equals `Tines.render` (43542); the THUMP kick's pitch curve, copied from `Thump.kt:188, :195, :219`, matches a real
`Thump.render(KICK)` (CLICK 0, DRIVE 0) with a worst relative error of **0.0051** over the first 50 ms (phase0 §4.8; bold-steer printed 0.005). That figure
is the curve against the engine's own render; it is not a pitch tracker.
- **Kick bends the drum** (kept, becomes BEND): TERRA COMPOUND_MEMBRANE with DROOP set to 0 and the kick's curve in its
  place (a two-octave dive, 4.0 at the hit, 1.541 at 50 ms, 1.003 at 200 ms), plus the kick's first 15 ms added after the
  output chain at 0.8 of the body's RMS. Measured pitch track against expected: 188/181 Hz at 30 ms, 123/121, 100/99,
  92/92, 89/89, 88/88 at 180 ms. Nearest in the app: **0.377** (a 100 ms splice of a tom over the kick). **The kept clip
  therefore carries two things, a pitch curve and a layered 15 ms kick head; BEND, as the brief defines it, stores the curve
  only** (§8.3 says what that means for the owner-approved sound).
- **Snare drives the gong** (a later group): TIDE GONG's fold amount follows the snare's envelope. Nearest **0.865**
  (TIDE's own fold-follows-level at GLOW 0), the least margin of the three; the plain gong alone scores 0.686 because
  both are the same note. Aliasing: steered gong 4x against 8x -28.5 dB, about as clean as TIDE at its own maximum fold.
- **A word shapes the bell** (a later group): TINES BELL's index follows the envelope of a spoken word, with pitch
  bends by up to +-30 cents along the word's contour. Nearest **0.215**; both syllables reading by ear was the stated main risk.

### 6.2 The ring family: anything rings anything

The family is largely linear and time-invariant, and MUTATE ROOM and the pad sheet's BODY already are that idea
(`bold-ring` verdict 4). The literal recipes failed: a 20 ms striker through FORK's tine against FORK's own noise
hammer **0.945 waveform NCC and 0.980 spectrogram correlation**; a static kick-plucked string against MUTATE ROOM
**0.949** (polarity flipped) **and 0.992**. Final clips, nearest NCC: snare rings the tines 0.851; kick plucks a string
**0.536** (the kick's own pitch drop retunes the string with `Strings.Loop.retune`, `Strings.kt:611`); clap with
sympathetic strings **0.487**.
- **Clap with sympathetic strings** (kept, becomes STRUNG): a copy of `Pluck.sympathetic` (`Pluck.kt:911`) with the
  feedback made a parameter. Honest limit: the tail is spectrally a plucked chord under a clap (spectrogram
  correlation 0.963 against MUTATE STACK, 0.942 against ROOM); what differs is that the hit's own noise rings the
  strings, plus a +-0.2 % shimmer and the loop's 4 kHz damping. The first 40 ms are the dry clap unchanged
  (maximum difference 0.0 over the first 40 ms).
- Snare rings the tines is parked for FORK: its clip needs a 21,200-sample striker, but `Fork.STRIKER_SAMPLES` is
  882 (`Fork.kt:350`). The 882-sample length is the shape FORK's recipe keeps (`Fork.kt:348-350`, "882 numbers"), and FORK's
  spec holds that a striker is "a hammer, not a drone" (`2026-09-27-fork-electric-piano-engine-design.md:365`, test 6); the
  spec does not state a length limit in those words. Kick plucks a string goes to Group C.

### 6.3 The talk family: hits that talk and morph

Gates: the gong's tract copy with no word equals `Tide.render` (max difference 0.0, 83172 frames); the drum bank with no
word equals `Terra.render` (0.0, 55566 frames); the morph at a constant amount .5 against `Mutate.render` MORPH:
**NCC 1.0000** and spectrogram correlation 1.0000 (lengths 50495 against 37589, because MUTATE interpolates the length
and the spike keeps the longer part). This is a faithfulness gate, not byte identity.
- **The gong speaks** (kept, becomes SAY): a time-varying vowel filter following one VOX SPEAK word's formant path.
  Nearest in the app **0.568** (rack CONTOUR on the plain gong); best MUTATE 0.554. The word's first two resonances
  followed the target at r = 0.99 (F1) and 0.84 (F2), mean error 39 and 71 Hz (the plain gong: 0.31 and -0.24).
  **Ablation:** the same tract applied to the finished gong against the in-engine version: NCC **0.956**, spectrogram
  0.995, so a section after the engine sounds the same. The ablation ran on the engine's 176.4 kHz buffer; running at
  the snip's own rate was not measured. The spike's level-follow was a 30 ms energy follower clamped to .25-6, not the
  rack's peak match; the weak spots are the word's opening (0.04-0.14 s) and its closing nasal.
- **A kick becomes a bell** (kept, becomes BECOME): see §8.5. Nearest **0.589** (a 100 ms splice); spectrogram 0.92-0.93
  against a 40 ms splice. The spike said so honestly: it may read as a longer splice.
- **A talking drum** (kept, becomes TALK): see §8.4. Nearest **0.166**, the lowest of the nine: TERRA's modes carry a
  vowel path and an intonation, and nothing in the app moves a mode balance in time.

## 7. Phase 0 at `75b550c1`

**What it tested.** Whether the Group B experiments still hold on today's tree. Between `5e3f5f3e` and `75b550c1`
(`git diff` over `synth/src/main`, `audio/`, `json/`, `kit/`, `xpm/`, `mpc3/`, `loop/`): Bore, BorePresets, Fork,
ForkPresets, FxChain, Presets, ResinPresets, Silk, StringMachine, Terra, Treatments, Valve, VelvetPresets. The spikes
read only TERRA and, for strikers, Fork, Thump, Vox, Dsp, Modes, Punch, Resampler, Classifier, FeatureExtractor and
Fft. TERRA's only change is the removal of its attack compressor (`a6dcb87e`) and four constants; the bodies of
`Fork.excite` and `Fork.striker` and the two strike-cutoff constants are byte-identical (function-by-function
compare); the four factory WAVs the sample strikers read have equal sha-256. Every struck spike copied the removed
compressor in its output chain, which is why the TERRA numbers had to be re-measured.

**How.** (1) One switch, `legacyCompressor`, rebases every copied chain onto today's. (2) Harness check: with the switch on,
the round-2 plan re-renders **43 of 43** distinct round-2 audition clips byte-identical (the 55 on the page minus 12
declared duplicates), 13 of 15 struck-shape section files identical (the other two differ only in render times and
one path), and both bold clips byte-identical to what the owner heard. So BEFORE below is the published round 2 and
every difference is attributable to the compressor's removal. (3) New: **P0Bank**, a prototype of the Group B change
(Appendix B), and **P0Hit**, HIT run through it. (4) Materiality fixed before the tables were built: OB change at
least 0.5 dB on a row more than 3 dB above the floor; mean or spread changing 0.5 dB; any class flip; time to peak
changing 0.5 ms; any gate that passed and now fails; any pre-chain figure changing at all.

**Gates at 75b550c1** (codes and results restated in Appendix A, "Gates"): copied path equals `Terra.render` (24 renders, bit-identical);
**P0Bank with no inputs equals `Terra.render`** for 4 voices at defaults, 20 macro probes and all 16
`TerraKits.classic()` pads (including the three BUZZ pads and the CLACK pad): **40 cases, bit-identical**; identity level
and pitch curves, alone and together: bit-identical (120 renders); HIT with c = 0 and each capture-rule head:
bit-identical (120); HIT with an impulse at c = .5 and 1: bit-identical (80); prototype HIT equals round-2 `renderA`
bit-identical (80). The one failing gate is the one round 2 already widened: RUNG on the cavity at 0.0951 dB against
0.06 (before 0.0930); it does not apply to HIT, whose impulse is exactly 0.

**What did not move.** HIT .5's overtone balance on all four voices, its time to peak, first-5-ms peak, f0 shift and
class (membrane, bell, bar unchanged to 0.02 dB; cavity +0.25 dB spread); the membrane summary (the three round-2
strikers, THUMP KICK, WRAITH WORD and THUMP SNARE, are within 3.5 dB of today's overtone balance at HIT .5, spread 6.4 dB on
the float render and 6.5 in the 16-bit files of verify-r2; over all ten strikers the range is -8.1 to +3.5 dB and the
spread 11.6 dB, Appendix A T2); monotone in c (10 of
10 on all four voices now); the bold clips and their gates (kick-bends-drum pitch track and 0.377, talking drum 0.166).

**What moved, and the consequence** (each is an owner decision, a spec instruction or, in items 4 and 6, a point where the
measurement differs from the brief's wording; no brief sentence is rewritten here):
1. **The cavity's floor fell 5.8 dB** (-49.6 to -55.3 dB absolute), because the compressor's distortion products were
   part of it. Kick-type rows on the cavity at HIT 1 (and RUNG) read 5.5-5.9 dB lower. Any test that pins a cavity
   dark-striker row must pin it relative to the new floor. (phase0 T3, T2)
2. **RUNG's and round 1's times to peak are not citable**: the maximum sits on a plateau of near-tied peaks (the strong
   clip with WRAITH WORD went 1.6 to 9.3 ms while its first-5-ms peak fell only 0.6 dB). The stable figure is the
   first-5-ms peak, which is 0.6-1.0 dB lower on ringed renders. (phase0 T12)
3. **HIT at 1 is not "today's attack plus colour" for a bass head**: membrane with THUMP KICK peaks at 14.0 ms (today's
   drum 0.2 ms; first-5-ms peak 0.51 of the clip peak, was 0.63), because a kick head leaves almost only the
   fundamental. At .5 the attack is today's (first-5-ms peak 1.000 on all four voices). Claims about HIT's attack must
   name the strength. (phase0 §5.3; Appendix A T11, T12)
4. **Conflict with the brief's text: "HIT's level match (s) keeps their drive as today" and the claims test "BUZZ/CAVITY
   drive unchanged at the level match".** The brief also says "s matches the peak of today's body", and that is what `s`
   does: it matches the body's peak, not the 75 Hz band-passed level the cavity's tanh sees or the level the 0.12 threshold
   BUZZ gates on. Over ten strikers the cavity's tanh input is 0.56-1.06 times today's at HIT .5 and 0.19-1.16 at HIT 1
   (never above 0.357, where tanh is within 4 % of linear); BUZZ's time above threshold at BUZZ 1 is 0.47-1.14 times
   today's at HIT .5 and 0.05-1.25 at HIT 1 (phase0 §4.7; Appendix A). The two brief sentences cannot both hold, and the
   claims test cannot pass as written for BUZZ; it can pin these ranges and equality at HIT 0. **Owner decision, and each
   way out relaxes one approved sentence of the brief:**
   - *Option A (the proposed default, not approved until the owner says so):* BUZZ and the tanh follow the striker. This
     relaxes "keeps their drive as today" (a bright, high-mode hit leaves little low-frequency level to rattle; RUNG
     already behaved so).
   - *Option B:* a band-passed-peak level match on the cavity. It would hold the tanh input and BUZZ near today's, and it
     relaxes "s matches the peak of today's body", at the price of a different overtone balance for bright heads
     (estimate, not measured: a hat head's drive ratio is 0.30, so matching it would lift the low modes about 10 dB).
5. **Conflict with a round-2 target, "S1 at most 0.06 dB per mode on every voice".** Not true for RUNG on the cavity
   under round 2's level rule, before (0.0930) or after (0.0951); under the plain-peak rule it passes by 0.003 dB
   (0.0573). It does not apply to HIT. Do not widen the tolerance (round 2 did); name the level rule beside any S1 figure
   (a spec instruction, not a decision). (phase0 §3, G-B1)
6. **Zero-gain hazard, as measured; it corrects the brief's stated reason.** The brief says a frozen phase "then clicks on
   reopen". With the skip guard reading the table gain, a level curve that is exactly 0 for
   20-60 ms leaves the output exactly 0 inside the window and identical to the no-curve bank after it. The wrong
   version (guard on the product) differs by up to 0.33 after reopening (bank peak 0.82), 0.47 with a smooth 5 ms close
   and reopen. The effect is a **phase error** in the reopened mode, not a first-difference click (0.0004 against
   0.0003 hard; 0.0015 against 0.0016 smooth), so the hazard is real but is a wrong waveform after reopening, not a click.
   The test should compare the waveform after reopening with the no-curve bank, as the brief's hazard intends. (phase0 §3)
7. **A-D.** Round 2's worst per-mode miss was 1.86, 4.58 and 2.13 dB for the three strikers (the round-2 gate log, `r2-work/gates-and-buildlogs.txt`;
   the brief carries it as "1.5-4.6 dB"). On today's chain it reads 1.85, 3.47 and 2.33 dB, **1.9-3.5 dB** (phase0 G-A4). Its OB still matches
   RUNG's to 0.01 dB on the membrane. Keep A-D out of build round R1; it is the first fix candidate if HIT 1 is heard as weaker than RUNG,
   though its attack is not free (peak at 8.7 ms with THUMP KICK, against HIT 1's 14.0 and RUNG's 8.3).
8. **Pitch multiplier hazard:** 0, -3, 1e9 and 1e-9 all render finite once clamped to 0.25-4; NaN passes `coerceIn`,
   so the finite checks at capture and decode are what keep it out. (phase0 §3)
9. **Cost:** HIT is 1.4-1.7 times an unstruck render with the capture-time work counted (2.8-3.6 ms of it over the four
   voices, cacheable per patch and receiver tuning); the neutral path is 0.98-1.05 times. The compressor's removal changed
   the unstruck render by 0-1 ms. (phase0 §4.7; Appendix A, render cost)

**Not re-measured, and why:** THROAT, WRAITH and BEATBOX as TERRA voices (out of scope); FORK's REED family and WIDTH macro
(no striker path); the hybrid reports (post-render, no TERRA chain); **BEND's pitch-track capture against THUMP's curve
and TALK's bake** (build round R3 builds them; Phase 0 only confirmed that the pitch input and the two-input form reproduce the spikes,
and that with DROOP 0 "compose" equals "replace": bit-identical to the spike's bank).

**The listening check.** The brief asks for a three-clip HIT-at-1 check. Phase 0 built **six** clips. **That is a deviation
from the brief and needs the owner's OK**: six stays inside the owner's standing limit of at most 10 clips and at most 3
questions, but it is not the three the owner approved, so the closing table asks. The brief does not name its three clips; a
three-clip subset that answers question 1 is today's drum, HIT 1 with WRAITH WORD, and the strong "rung" clip with WRAITH WORD
as the owner heard it. The six: today's drum; HIT 1 with WRAITH WORD and with THUMP KICK; the strong "rung" recipe with each; and the
WRAITH RUNG clip twice (rebuilt on today's chain, and the earlier clip byte for byte; the chain change lowered the first
5 ms of the rebuilt one by 0.6 dB, so the two differ). Measured gaps the ear can check: with WRAITH WORD, RUNG is 2.7 dB brighter in overtones than HIT 1;
with THUMP KICK, 6.5 dB brighter and peaks sooner (8.3 against 14.0 ms); with THUMP SNARE (not in the check) the order
reverses (HIT 1 is 13 dB brighter: -13.2 against -26.3 dB). So no single read matches RUNG on every striker. The three
questions are in Appendix D. If HIT 1 is heard as weaker, the held-back A-D clips (`chimera-audition/r2-work/held-back-a100d/`) are next.

---

## 8. The adopted techniques: formulas and short excerpts

All excerpts are **spike, not for the build**. They are quoted to fix the maths and the order of operations; the specs
rebuild them on the house's primitives. Cites are to the spike file in `spikes/` and, for the tree they copy, to
`75b550c1`.

### 8.1 HIT: the coloured gain, the running projection, the level match

TERRA's modal loop is `Terra.strikeAndModalBank` (`synth/.../Terra.kt:367-408`): droop line `:390`, skip guard `:396`,
phase accumulate `:397-398`, gain term `:400`. Every voice uses it. HIT multiplies `mode.gain` by a per-mode, per-sample
factor; with the factor exactly 1f the arithmetic is the original's.

```
P_k(n)   = sum_{m=0..n} x[m] * r_k^(-m) * exp(-j * PHI_k(m))      striker x at the 176.4 kHz render rate,
                                                                  r_k = exp(-6.9078 / (t60_k * RR)),  theta_k = 2 pi f0 ratio_k / RR
A (HIT):  PHI_k(m) = theta_k * m                                   (nominal pitch; depth 0)
A-D:      PHI_k(m) = theta_k * (m + depth*(1 - q^m)/(1 - q)),  q = exp(-1/(0.020*RR))     (drooping pitch; not adopted)
gain_k(n) = g_k * ( (1 - c) + c * s * |P_k(n)| )                   c = HIT; |P_k(n)| constant after the head (n >= M)
s         = refPeak / peak(body_s)                                 refPeak = peak of today's 0.65*modalSum over the first
                                                                   3*RR/f0 + 16 samples; body_s = TERRA's bank at gains g_k*|P_k|,
                                                                   no exciter, over max(3*RR/f0, M) + 64 samples
```

The receiver's modes (`r_k`, `theta_k`) are recomputed at render, so retuning the TERRA pad still works. Decay and phase
are TERRA's own, so DROOP, CLACK, BUZZ and the cavity stage are unchanged in form.

Spike, not for the build. The running projection, magnitudes only
(`spikes/struck/new-files/.../TerraStruckR2.kt:77-105`, called as `running(x, p, 0.0)` for HIT):

```kotlin
fun running(x: FloatArray, p: TerraStruckSpike.Plan, depth: Double): Array<DoubleArray> {
    var last = 0; for (i in x.indices) if (x[i] != 0f) last = i
    val m = last + 1                              // |P_k(n)| is constant for n >= m
    val q = exp(-1.0 / (0.020 * RR)); val oneMinusQ = 1.0 - q
    return Array(p.modes.size) { k ->
        val mode = p.modes[k]; val hz = p.fundamentalHz * mode.ratio
        val out = DoubleArray(m)
        if (hz <= 0f || hz >= RR / 2f || mode.t60 <= 0f) return@Array out
        val invR = 1.0 / decayR(mode.t60); val theta = TWO_PI * hz / RR
        var re = 0.0; var im = 0.0; var w = 1.0
        for (n in 0 until m) {
            if (x[n] != 0f) { val nd = n.toDouble()
                val phi = theta * (nd + depth * (1.0 - Math.pow(q, nd)) / oneMinusQ)
                re += x[n] * w * cos(phi); im -= x[n] * w * sin(phi) }
            w *= invR; out[n] = sqrt(re * re + im * im)
        }
        out
    }
}
```

The level match (`TerraStruckR2.kt:134-142`), and the gain table held constant after the head
(`Phase0Bank.kt`, `P0Hit.levelOf`; full source in Appendix B):

```kotlin
fun levelS(p, run): Double {                               // s = refPeak / peak(body_s)
    val m = run[0].size; val periods = (3f * RR / p.fundamentalHz).toInt()
    val n0 = min(p.frames, max(periods, m) + 64)
    val body = gainedBank(withFrames(p, n0), { _ -> 0f }, n0) { k, i -> run[k][min(i, m - 1)].toFloat() }
    val pk = peakOf(body)
    return if (pk > 1e-20f) (refPeakFast(p) / pk).toDouble() else 0.0
}
// P0Hit.levelOf: gains[k][n] = ((1.0 - c) + c * s * run[k][n]).toFloat();  lookup = gains[k][min(n, m - 1)]
```

The skip guard reads the **table** gain only, so a level curve that reaches zero never skips the phase accumulate
(`P0Bank.bank`, Appendix B: `... || mode.gain == 0f) continue`, then `val g = lv?.invoke(k, n)` after it).

At c = 0 every gain is exactly `1f`; with an impulse striker `s*|P_k| = 1` for every mode, so the result is bit-identical
to TERRA at any c (G-A1, G-A2, G-P0d, G-P0e).

### 8.2 The safe capture rule

Rule (struck-motion M7; struck-r2 §1.1), applied once at capture. `Fork.striker` (`Fork.kt:859-870`) is today's capture:
mono, resample to 44.1 kHz, the first 882 samples, `Dsp.normalize`, a 2 ms raised-cosine fade. The rule adds, in order:

1. Source peak over **finite** samples below `1e-4` (-80 dBFS, above a 16-bit file's own floor of about -96 dBFS): there
   is no striker; fall back to today's body (`Terra.render`). **This tests the whole source, before alignment.** The brief
   words the fallback as "when the head peak is below 1e-4", the peak of the aligned 20 ms head. The aligned head always
   contains the onset sample, which is at least 1 % of the source peak, so by construction the two tests can differ only for
   a very quiet source (finite peak 1e-4 to 1e-2, that is -80 to -40 dBFS) whose 20 ms after the onset stay under 1e-4 and
   which is louder later: the source test lets it ring, the head test falls back. The spike measured only the source test,
   and the hostile list below has no such source. The spec chooses between them; the closing table asks, with the brief's
   wording as the default.
2. Otherwise start at the source's **onset** (the first finite sample at or above 1 % of the finite peak, i.e. within 40 dB)
   **minus 1 ms** (44 samples).
3. Take 882 samples; zero any non-finite sample; `Dsp.normalize` to 1; a 2 ms (88 samples) raised-cosine tail fade.

Spike, not for the build (`spikes/struck2/new-files/.../TerraStruckMotion.kt:51-57, :60-64`, and `TSMExpM7.kt:20, :47`):

```kotlin
fun onsetOf(x: FloatArray): Int {                                     // 1 % of the finite peak, minus 1 ms
    var pk = 0f
    for (v in x) if (v.isFinite()) pk = maxOf(pk, abs(v))
    if (pk <= 1e-9f) return 0
    for (i in x.indices) if (x[i].isFinite() && abs(x[i]) >= 0.01f * pk) return maxOf(0, i - RATE / 1000)
    return 0
}
fun sourcePeak(source: Snip): Float { /* finite peak of the whole 44.1 kHz mono source, read BEFORE the normalise */ }
const val SILENT_PEAK = 1e-4f                                         // TSMExpM7.kt:20
// renderRule: if (TSM.sourcePeak(src) < SILENT_PEAK) -> render with no striker (today's body)   [whole source, not the head's peak]
//             else head = TSM.head(src, 20f, Tail.FADE2, align = true, sanitize = true)
```

`head(source, 20f, FADE2)` with `align = false` is asserted bit-identical to `Fork.striker` (G2, 10 strikers), so the rule
changes capture only at the edges. Hostile sources, round 1 against the rule (struck-motion M7.1, membrane defaults):
digital silence and an empty clip render SILENT in round 1 and today's body under the rule; a kick preceded by 20, 25 or
40 ms of silence, and a single-sample spike at 882 or 5000, render SILENT in round 1 and a real hit under the rule; noise
at 1e-4 and below falls back, noise at 1e-3 and above rings.

### 8.3 BEND: the pitch curve

The TERRA input is a pitch multiplier on the droop line: `currentF0 = fundamentalHz * (droopMult * pitch(n))`, with
`droopMult = 1 + droopDepth * exp(-t / 20 ms)` (P0Bank calls it `droop`), `n` counted from the strike and `pitch` clamped to
0.25-4 (P0Bank, Appendix B). "Droop depth" below always means `droopDepth`, the macro-derived number; "droop multiplier" means
`droopMult`. THUMP's own BEND macro (`Thump.kt:195`, the rate at which a kick's pitch falls) is a different thing from the BEND
knob proposed for TERRA. Two spikes bound what was measured:

- **The curve as an analytic function** (steer, clip 1; `spikes/bold-steer/.../SteerSpike.kt:121-126`, a copy of
  `Thump.kt:188, :195, :219`). Spike, not for the build:

```kotlin
fun kickCurve(kick: Map<String, Float>): (Float) -> Float {
    val m = Thump.defaults(ThumpVoice.KICK) + kick
    val sweepMult = Dsp.lin(m.getValue("SWEEP"), 1.2f, 4f)
    val bendRate  = Dsp.around(m.getValue("BEND"), 18f, 90f, 400f).toDouble()
    return { t -> 1f + (sweepMult - 1f) * exp((-bendRate * t)).toFloat() }    // f = base * curve(t)
}
// bank: currentF0 = fundamentalHz * curveAt(t)      (SteerTerra.curvedBank, replacing 1 + droopDepth*exp(-t/0.020))
```

  Against a real THUMP kick (G4): worst relative error **0.0051** over 50 ms (phase0 §4.8; bold-steer printed 0.005). This is the analytic curve, not a captured
  one; in this spike DROOP was 0 and the curve **replaced** the droop line.
- **A curve measured from audio** (ring, clip 2; `spikes/bold-ring/.../RingSpike.kt:182-207`): a captured kick low-passed
  at 300 Hz (two one-poles), the time between successive positive-going zero crossings (linear interpolation, `kickCycles`,
  `:182-193`), one frequency per cycle at the cycle's midpoint, `ratio(t) = f(t)/f_settled` (settled = median of the 100-220 ms
  cycles; `Contour`, `:196-207`), piecewise-linear, held before the first cycle and never below 1 (`coerceAtLeast(1.0)`,
  `:200`, `:203`); the upper limit 3 is `maxRatio` where the curve is used (`:214`, `:222`). The range [1, 3] is a spike
  limit and not the build's clamp, which the brief sets at 0.25-4. Measured by this capture: 147.1 Hz at 3 ms, 126.2 at 18,
  92.9 at 36, 71.3 at 61, settling at 55.5 Hz (bold-ring). The capture was **not validated against a known curve**, and the
  first cycles are the stretch that carries the dive. (The correlation of 0.4-0.5 before 40 ms in the ring notes belongs to a
  different tool: the autocorrelation pitch check of the finished string clip, which is unreliable there. It says nothing
  about this capture.)

**What was not tested.** The brief's design is a fixed **64-point** curve captured from the other pad by a new pitch-track
function, **composed** with DROOP (multiplying it). The 64-point resampling, the capture on arbitrary pads, the match of
that capture against THUMP's known curve (in particular its accuracy in the first 40 ms, where the dive is, which is the real
open question for BEND), and composition with a non-zero DROOP are untested. **The kept clip is not BEND alone:** it carries
a layered 15 ms kick head (§6.1) that BEND does not store, and its 0.377 nearest-in-the-app figure includes that head, so the
owner-approved sound will differ from what BEND by itself gives. HIT with the same pad as striker is the nearest way to
restore an attack, but BEND and HIT together were never rendered or heard; that is a listening risk for build round R3, and
the closing table asks. Phase 0 shows only that with
DROOP 0 the pitch input reproduces the spike bit for bit (pre-chain) and that clamped extreme multipliers stay finite. RESONANT_CAVITY's
75 Hz air resonance does not follow the curve (not tried; owner-approved to stay fixed).

### 8.4 TALK: the vocal-tract gain per mode

Per-mode gain = the vocal tract's magnitude response at each mode's **current** (bent) frequency, for the word's frame at
time t, normalised so the loudest weighted mode keeps the loudest unweighted mode's level, floored at -26 dB, smoothed
with a 1 ms one-pole, updated every 32 samples at 176.4 kHz. Pitch = the droop multiplier times 2^(semis/12), with semis = `glide` times the word's statement contour (VOX's HUMAN
contour, a rise of 1.5 and a fall of 5 semitones, `VoxSpeak.kt:221-222`, `:485`). `glide` is a multiplier on that contour:
1 in VOX and **1.4 in the spike**, which makes the 5-semitone fall a perfect fifth of 7 semitones. The exciter path
(0.35 times the exciter) is untouched, so the strike keeps TERRA's colour.

```
V_k(t)  = | zero(f_k) * pole(f_k) * prod_{i=1..5} res_i(f_k) |      f_k = f0(t) * ratio_k, nasal zero/pole + formants F1..F5
s       = max_k g_k / max_k( g_k * V_k )                           so the loudest weighted mode keeps today's level
gain_k  = g_k * smooth_1ms( max( V_k * s, 10^(-26/20) ) )
f0(t)   = f0 * (1 + droopDepth*exp(-t/20 ms)) * 2^( glide * statement(frac) / 12 ),   statement(frac) = 1.5 sin(pi*min(2 frac,1)) - 5 frac^2
          frac = clamp(t * speed / word length, 0, 1);   glide 1.4 and speed 1.5 in the spike (VOX: glide 1)
```

Spike, not for the build (`spikes/bold-talk/.../TalkWord.kt:130-135` and `TalkDrum.kt`, the per-block part):

```kotlin
fun response(hz: Double, f: Frame, widen: Double, rate: Int): Double {            // TalkWord
    val fs = doubleArrayOf(f.f1.toDouble(), f.f2.toDouble(), f.f3.toDouble(), F4, F5)
    var m = antiMag(hz, NASAL_POLE + (NASAL_ZERO_OPEN - NASAL_POLE) * f.nasal, 100.0, rate) * resMag(hz, NASAL_POLE, 100.0, rate)
    for (k in 0 until 5) m *= resMag(hz, fs[k], BANDWIDTHS[k] * widen * (if (k == 0) 1.0 else f.damp.toDouble()), rate)
    return m
}
// TalkDrum.render, every 32 samples:
//   for k: v[k] = response(currentF0 * ratio_k, frame, widen, RR);   top = max(top, gain_k * v[k])
//   s = maxGain / top;   v[k] = max(v[k] * s, floorLin)
// per sample: vSmooth[k] += smoothK * (v[k] - vSmooth[k]);   modalSum += sin(phase_k) * gain_k * vSmooth[k] * decay
```

The formant paths come from `VoxSpeak.scriptFor` (public), retimed by a copy of the private `retime` (`VoxSpeak.kt:353`)
with stretch 1 and interpolated linearly between targets, as `VoxSpeak.synthesize` does at HUMAN 1 (`:462-480`); the tract's
resonators are copies of the private `Resonator` (`:282`), `AntiResonator` (`:298`) and `Tract` (`:315`).

**When the spike's track equals VOX SPEAK's own.** Only at one operating point: HUMAN 1, EFFORT .5, SIZE .5, no stutter,
stretch 1 (DECAY 0) and no wander. The spike's track leaves out what `VoxSpeak.synthesize` also does: it does not quantise
the control time into chip frames (`tq`, `:464-465`; skipped at HUMAN 1); it does not scale F1 by jaw and throat or F2 to F5
by throat (`:475-477`; both are 1 at EFFORT .5 and SIZE .5); it does not run `stuttered` before `retime` (`:420`); its
contour is the statement part only (VOX's is `h * lerp(statement, call, shout)`, `:487`, with `shout` 0 at EFFORT .5); and it
has none of VOX's slow wander of up to +-40 cents (`:488-491`, `WANDER_CENTS`, `:223`). `TalkWord`'s own header says "no chip
frames" at HUMAN 1. **The copied track and tract were never compared with VOX SPEAK's output.** The talk gate proves only
that "no word" equals `Terra.render` (and, for the gong, `Tide.render`). The brief bakes the track into fixed-length data so
saved TALK pads never depend on later VOX changes; what is baked is therefore this operating point and not VOX's full
control.

**Spike-only deviations that make the clip work** (bold-talk §4; `TalkClips.kt:99-108`):
- **t60 per mode.** Every mode's t60 is scaled and the membrane's gamma step (the decay divisor, `1 + step*k` for mode k,
  numbered from 0) goes from .65 to .30. The scale is `1.6 * (1 + 0.65k) / (1 + 0.30k)`: a per-mode ratio of **1.60, 2.03,
  2.30, 2.48, 2.62, 2.72** for modes 1 to 6 (`TalkClips.kt:103`, `:108`), that is t60 per mode 1.44, 1.11, 0.90, 0.76, 0.65,
  0.58 s instead of 0.90, 0.55, 0.39, 0.31, 0.25, 0.21 s (the ratio of those rounded figures reads 2.76 at mode 6).
- **glide 1.4** (`TalkClips.kt:100`): a fall of 7 semitones, not VOX's 5. The reported 5-cent median pitch error and -14-cent
  worst error, and the 0.856 "glide only" ablation, all depend on it.
- **widen** (a multiplier on every formant bandwidth) **1.0** for the drum (the word's own bandwidths), against 1.5 for the
  gong clip (`TalkClips.kt:99`, `:41`; §8.7).
- A 1.4 s render instead of 1.26 s, word FIVE at speed 1.5.

Reason, measured: with TERRA's own decays the upper modes have fallen 30-40 dB by the time the vowel has moved, and the
upper-mode vowel swing (36 dB at mode 6) depends on the longer upper tails. **The brief's "rings about 1.6 times longer under
TALK" is the mode-1 figure only.** A uniform factor of 1.6 does not reproduce the clip; the per-mode table does, and the data
to reproduce is that formula. Which to build is an owner and listening decision in the closing table. Phase 0 shows the
clip is expressible as data: the two-input prototype (level = vowel gain times the per-mode decay ratio, pitch = the glide)
reproduces the spike's clip to **-116 dB** (maximum sample difference 3e-7).

Measured (bold-talk §4): pitch median error 5 cents, worst -14 cents at 150 ms; per-mode tracking, Pearson correlation r, 0.99, 0.98, 1.00,
1.00, 1.00, 1.00 for modes 1-6; vowel weight swing over the word 13, 9, 25, 28, 33, 36 dB; ablation NCC against glide only
**0.856**, vowel only **0.087** (the glide is the bigger waveform change; the vowel is the brightness swell over 250 ms).

### 8.5 BECOME: a per-frame ramp on MORPH

`Mutate.morph` (`shell/.../Mutate.kt:342`) mixes the two parents' STFT (short-time Fourier transform: the sound cut into
overlapping frames, here 1024 samples with a hop of 256, `Spectral.kt:19-22`) magnitudes with **one** `amount` for every frame
(`:360`), takes the length and the peak target from the same number (`:362`, `:364`), and rebuilds phases with `Pghi.invert`.
The spike makes the amount a function of the frame's centre time:

```
|X_f| = (1 - a(t_f)) |K_f| + a(t_f) * g * |B_f|,    t_f = (f*256 - 512)/44100            (1024-point Hann, hop 256)
a(t)  = smoothstep( (t - 10 ms) / (150 ms - 10 ms) )   clamped 0..1                       (spike)
                                                         (smoothstep(x) = 3x^2 - 2x^3, an S-curve from 0 to 1)
```

Spike, not for the build (`spikes/bold-talk/.../TalkKick.kt:67-72`, the loop only):

```kotlin
for (f in mk.indices) {
    val t = (f * Spectral.HOP - Spectral.FRAME / 2f) / RATE            // frame centre, seconds
    val a = amountAt(o, t)                                              // ramp, or o.constantAmount (Mutate-style)
    mixed.add(FloatArray(Spectral.BINS) { b -> (1f - a) * mk[f][b] + a * g * mb[f][b] })
}
var out = Pghi.invert(mixed, frames, RATE).samples
// then: the kick's own first 15 ms crossfaded back over the PGHI head (raised cosine), a 12 Hz DC blocker, fadeTail
```

Two differences from the brief to keep apart. **The spike's** ramp is a smoothstep over 10-150 ms, with the bell delayed
45 ms, the bell scaled to 2 times the kick's loudness, the kick's first 15 ms crossfaded back over PGHI's smeared head,
and the longer of the two lengths kept; it was tuned by ear-less measurement (hand-over: kick band below 120 Hz falls
56, 55, 53, 50, 44, 35, 16, 0 dB at 0, 20, ..., 140 ms). **The brief's default** is linear in amount per STFT frame, with
output length and peak target following the end amount, and BECOME 0 equal to today's MORPH **byte for byte**. That last is a
design requirement; the spike measured only NCC **1.0000** (spectrogram 1.0000) at a constant .5, with different lengths. The
design must prove byte identity itself (a test), and BECOME's curve is a listening value.

### 8.6 STRUNG: the sympathetic feed

A bank of tuned loops fed by the hit. Spike, not for the build (`spikes/bold-ring/.../RingSpike.kt:99-122`, a copy of the
private `Pluck.sympathetic` at `Pluck.kt:911` with `feedback` made a parameter, house value 0.995):

```kotlin
fun sympathetic(input, hz, rate, coupling, onset, onsetSamples, feedback = 0.995f): FloatArray {
    // tuning budget: integer delay n + first-order allpass fraction, with the 4 kHz loop one-pole's phase delay removed
    ...
    for (i in input.indices) {
        val fed = (if (i < onsetSamples) onset else coupling) * input[i]
        if (i <= n) { out[i] = fed; continue }
        val d = 0.5f * (out[i - n] + out[i - n - 1]); val tuned = a * (d - apY1) + apX1
        apX1 = d; apY1 = tuned
        out[i] = fed + feedback * lp.lp(tuned, SYMPATHETIC_LOOP_HZ)
    }
}
```

The bank (`bloom`, `RingSpike.kt:238-263`): an A-minor chord E3 A3 C4 E4 A4, each loop detuned +-0.2 % alternately;
`coupling` 0.15; `feedback` 0.98 (house 0.995: at 0.995 the first render was 1.795 s and still within 60 dB of its peak);
the loops get **no feed for the first 40 ms** (`onset = 0`, `onsetSamples = 1764`, repurposing the house's "feed more in
the first 10 ms" parameter) so the dry attack is untouched; the clap is high-passed (30 Hz one-pole subtracted) before
feeding because each loop's DC gain is 1/(1-fb) = 50; wet = each loop's output minus its own feed-through, summed over the
five, through a 2 Hz DC blocker; output = dry + 2.5 times wet; length = clap + 1.1 s, then `Strings.trimToDecay(floor 0.4,
ceiling 1.5)` and `fadeTail`. It runs at 44.1 kHz, because the loops are linear. The tried-and-dropped alternatives (first-50 ms
attack correlation): no gate 0.836; gate 30 ms 0.857; gate 40 ms at level 3.0 0.907; the chosen gate 40 ms at level 2.5 0.933.

### 8.7 SAY: the formant track filter

The in-engine spike put the same tract inside TIDE's loop; the **ablation** is the form SAY takes, a filter after the engine.
Spike, not for the build (`spikes/bold-talk/.../TalkGong.kt:177-200`, `postHoc`):

```kotlin
// 1. wet = the tract, retimed every 16 samples from the word's track (F1..F3 from the word; F4 3300, F5 3750 fixed;
//    nasal zero sliding 270 -> 450 Hz with `nasal`; F2..F5 bandwidths times `damp`; every bandwidth times `widen` 1.5)
for (i in dry.indices) {
    if (i % 16 == 0) { fr = track.at(t - startAt); tract.set(f1,f2,f3,F4,F5, fr.damp, fr.nasal, rate, widen) }
    wet[i] = tract.process(dry[i])
}
// 2. level-follow: 30 ms energy followers, g = (sqrt(Ed/Ew))^agc clamped .25..6
// 3. mix: dry only for 12 ms, then smoothstep over 50 ms to the wet path times g
mixed[i] = (1f - w) * dry[i] + w * wet[i] * g
```

Measured: formant slots follow the word at r 0.99 / 0.84 (mean error 39 / 71 Hz); the click index (largest second difference
in a 5 ms window over its rms) is 1.73 for the clip and for the plain gong, so no new discontinuity; inside-the-engine against
after-the-engine NCC **0.956**, spectrogram **0.995**; render 146.5 ms against 111.9 ms for the plain gong (1.31 times).
Not measured: running at the snip's own rate (the spike ran at 176.4 kHz), the word track against VOX SPEAK's own output (§8.4:
the track matches it only at one operating point), the rack's peak match (the spike used the energy
follower above), the word-selector behaviour under `Treatments.fade` and `Breed.crossMacros`, and the first-ever "WORD" macro in
the rack. The spike's tract ran as double-precision two-pole sections.

---

## 9. Load-bearing numbers

Each row names its report. "Moved" means Phase 0 re-measured it; both figures are printed and neither silently replaces the other.

| number | value | tag |
|---|---|---|
| compile errors in the proposal | 12 from 3 root causes (6 enum names, 4 `seed`, 2 `striker`) | synthesis §1 (api B1-B3, empirical F1) |
| decimated twice | 0.250 times length, 4.000 times pitch on 6 of 7 voices | synthesis §2 (empirical §A2, dsp F1) |
| its tests | 2 of 4 fail (`ChimeraTest`); `PresetsTest` 848 expected, 859 got | synthesis §3 (empirical F6 for the two failures, F9 for `PresetsTest`) |
| dead macro slots | 13 of 42 | empirical F5 |
| post-render hybrids vs the app | NCC 1.0000 (TERRA alone), 0.9993 (TIDE alone), 0.9997 (STACK), from 20 ms | hybrid-r2 §0 |
| ringed vs layered | OB spread 14 dB against 0.00; centroid spread 2.0 Hz (1.7 %) | verify §2 |
| impulse equals today's modes | 0.00 dB per mode (max 0.09), residual -66 to -82 dB | struck-shape §0.1 |
| blend monotone in the amount | signal blend: non-monotone for 6 to 9 of 10 strikers per voice; gain domain: 10 of 10 (cavity 9 of 10 at round 2, 10 of 10 at `75b550c1`; moved) | struck-shape S6/S6b; phase0 §4.3 |
| membrane HIT .5, the three round-2 strikers (THUMP KICK, WRAITH WORD, THUMP SNARE) | within 3.5 dB of today's overtone balance (-3.0 / +3.5 / +2.9), spread 6.4 dB (float), 6.5 dB (16-bit) | phase0 §4.6; verify-r2 |
| membrane HIT .5, all ten strikers | range -8.1 to +3.5 dB, spread 11.6 dB, mean -0.33 dB | phase0 §4.2 (Appendix A T2) |
| cavity OB floor | **-49.6** dB (round 2) → **-55.3** dB (75b550c1); moved | struck-shape §1.5; phase0 T3 |
| cavity COLOURED c=1 spread over ten strikers | 33.5 dB → 39.1 dB; moved (kick rows sit on the floor) | struck-shape S6b; phase0 T2 |
| first-5-ms peak, RUNG/round 1, mean | 0.6-1.0 dB lower; moved | phase0 T12 |
| HIT 1, membrane + THUMP KICK, time to peak | 14.0 ms (today 0.2 ms); first-5-ms peak 0.51 (was 0.63) | phase0 §5.3 |
| A-D per-mode miss | 1.86 / 4.58 / 2.13 dB (round 2 log; the brief's "1.5-4.6") → 1.85 / 3.47 / 2.33 = **1.9-3.5 dB**; moved | notes-r2-struck G-A4; phase0 §3 G-A4 |
| RUNG cavity per-mode (BP_PEAK level) | 0.0930 → 0.0951 dB against a 0.06 target | phase0 G-B1 |
| cavity tanh input range, HIT .5 / HIT 1 | 0.56-1.06 / 0.19-1.16 times today's; max 0.357 | phase0 §4.7 |
| BUZZ time above 0.12, HIT .5 / HIT 1 | 0.47-1.14 / 0.05-1.25 times today's | phase0 §4.7 |
| CLACK pre-click (bell, CLACK 1) | 5.9 % today; 7.6 % / 16.5 % at HIT .5 / 1; under the 50 % ceiling at `Terra.kt:250-257` | phase0 §4.7 |
| HIT cost | 1.4-1.7 times unstruck (2.8-3.6 ms capture work); neutral 0.98-1.05 times | phase0 §4.7 |
| harness reproduces round 2 | 43 of 43 clips byte-identical; both bold clips byte-identical | phase0 §2.3 |
| P0Bank, no inputs | bit-identical to `Terra.render`, 40 cases (4 voices, 20 probes, 16 kit pads) | phase0 G-P0a |
| kick-bends-drum | pitch track 188/181, 123/121, 100/99, 92/92, 89/89, 88/88 Hz; curve vs real THUMP 0.0051 | bold-steer G4 (printed 0.005); phase0 §4.8 |
| nearest NCC, steer clip 1 / 2 / 3 | 0.377 / 0.865 / 0.215 | bold-steer |
| nearest NCC, ring clip 1 / 2 / 3 | 0.851 / 0.536 / 0.487 (literal recipes 0.945-0.992) | bold-ring |
| nearest NCC, talk clip 1 / 2 / 3 | 0.568 / 0.589 / 0.166 (spectrogram 0.92-0.93 for clip 2) | bold-talk |
| SAY ablation | NCC 0.956, spectrogram 0.995 | bold-talk §2 |
| TALK bake equivalence | -116 dB, max sample difference 3e-7 | phase0 §4.8 |
| TALK pitch / modes | median 5 cents, worst -14 cents; per-mode r 0.98-1.00; vowel swing up to 36 dB | bold-talk §4 |
| TALK per-mode t60 ratio over TERRA's own | 1.60, 2.03, 2.30, 2.48, 2.62, 2.72 for modes 1 to 6 (not a uniform 1.6); glide 1.4 | bold-talk §4; `TalkClips.kt:100`, `:103`, `:108` |
| STRUNG | attack correlation 0.933 (first 50 ms); tail 0.963 spectrogram vs STACK | bold-ring |

## 10. What was rejected, and why

| rejected | why | tag |
|---|---|---|
| CHIMERA as an engine | its post-render hybrids equal MUTATE or TERRA alone after 20 ms (NCC 0.9993-1.0000) | hybrid-r2 §0, §6.1 |
| splice voices (THUMP_TERRA, THUMP_TIDE), layer voice (TERRA_TINES) | MUTATE SPLICE and STACK, reachable today; STACK is a superset of the layer | synthesis §4 (fleet F1) |
| TINES_TIDE product | a clip-by-clip product is at most a seventh MUTATE mode; clip is silent after the ZAP's 0.165 s | synthesis §4 |
| THROAT, WRAITH, BEATBOX voices | THROAT calls no TERRA and duplicates VOX THROAT; WRAITH and BEATBOX are bit-identical to each other and to TERRA alone (the vocal hit is thrown away) | synthesis §4 (empirical F4, dsp F6) |
| signal-domain blend of today's body and the ringed body | not a blend: uncorrelated phases; OB non-monotone in 6 to 9 of 10 strikers per voice | struck-shape S6 |
| first difference ("dx") on the striker | inert at a = .5 (at most 0.03 dB); at a = 1 with S1 on it is a double tilt; it is a tilt, not coupling | struck-shape S3; struck-r2 §0 |
| FORK's absolute 1.5-9 kHz FORCE low-pass on TERRA | inert on membrane (0.06-0.17 dB) and cavity (about 0.02 dB) | struck-shape S4 |
| a 40 ms head | the 20 ms house head is the shape; a 5 ms head is open on the bar only | struck-motion M2 |
| the non-causal "T0" gain body | applying the striker's magnitude from sample 0 inflates short-t60 modes by tens of dB during the head | struck-shape §0.2; struck-r2 §0 |
| A-D (striker read at the drooping pitch) as HIT's algorithm | missed G-A4 on the membrane (per-mode level, not OB); its attack is not free; first fix candidate only | struck-r2 G-A4; phase0 §5.7 |
| RUNG as the algorithm | it is the strong reference clip, not the build: not bit-identical at c = 0 and it moves the cavity (the cavity per-mode gate failed) | struck-r2 §2B; phase0 G-B1 |
| the literal ring recipes (a 20 ms striker through the tine; a static kick-plucked string) | already in the app: 0.945 waveform and 0.980 spectrogram vs FORK's noise hammer; 0.949 and 0.992 vs ROOM | bold-ring clips 1, 2 |
| "snare rings the tines" in this work | the clip needs a 21,200-sample striker; `Fork.STRIKER_SAMPLES` is 882 (`Fork.kt:350`); parked for FORK | bold-ring clip 1 |
| a rack section needing the voice inside the engine for SAY | no measurable difference in-engine against after-engine (NCC 0.956) | bold-talk §2 |
| silent-gain hazard version (guard on the product) | freezes the phase; reopened mode is off by up to 0.33 of a 0.82 peak | phase0 §3 |
| Group C (kick plucks a string, snare drives a gong, word shapes a bell) | a later spec on the same capture plumbing | brief, "How we got here" item 7 |

## 11. What was not measured

- **No audio in the bold round was heard when written;** the owner then heard the nine clips and kept all three families. Round-2
  struck and hybrid clips: the owner heard the nine-clip page only.
- **BEND:** the 64-point captured curve, its composition with non-zero DROOP, the pitch tracker on arbitrary pads, the
  zero-crossing capture's accuracy in the first 40 ms against a known curve, and BEND without the layered 15 ms kick head
  the kept clip carries (§8.3).
- **TALK against VOX SPEAK's own output:** the copied track and tract were never compared with it, and they match it only at
  HUMAN 1, EFFORT .5, SIZE .5, no stutter, stretch 1 and no wander (§8.4). **VOX SPEAK has no frozen byte-exact golden**
  (only `VoxGrainsTest`, which is behavioural); the same holds for SAY's track.
- **A frozen TERRA golden does not exist.** The Phase-0 gates (G-0, G-P0a, G-P0d, G-P0g) compare the prototype with the live
  `Terra.render` at `75b550c1`. They show equivalence on this tree; they do not prove that saved TERRA bytes survive a later
  change. TERRA is in neither `DeterminismTest` nor `PadRecipeTest`, and `TerraKitsTest`'s "recipe regenerates" check is
  self-referential (both sides run the current code).
- **BECOME ramps shorter than about 23 ms** are smeared by the analysis window (1024 samples, `Spectral.kt:19-22`); the spike's
  ramp starts at 10 ms and runs to 150 ms, and no faster ramp was tried.
- **TALK on the cavity voice,** and any word other than ONE, FIVE and SEVEN (word choice for the drum was by a per-mode weight
  contrast run that was not kept as a file).
- **SAY at the snip's own rate,** with the rack's peak match, and the selector-macro rules (§8.7).
- **BECOME byte identity at 0** (a requirement, not a measurement), and its default (linear) curve by ear (§8.5).
- **STRUNG without a key** (the spike used a fixed A-minor chord; `Body.rootFor` returned 0 for the spike's kick, not A).
- **Phone:** nothing here ran on a phone; `:app` cannot be compiled or verified in this environment. Render times are JVM figures.
- **HIT with velocity layering, round-robin and stereo sources,** and the version rule for older apps (design, not experiment).

---

## Appendix A — the Phase-0 tables, restated (from the Phase-0 report; the report itself is in the scratchpad)

Definitions as in §3.1: OB in dB against unstruck, float render, from 20 ms; the OB floor is a one-mode control; BEFORE is the
round-2 build as published (reproduced by the legacy chain), AFTER is `75b550c1`. COLOURED = HIT; RUNG = the strong reference.
Each heading below carries the Phase-0 report's section number (and table number where it has one), so a tag such as
`(phase0 §4.7)` can be found here; `(phase0 §5.N)` is item N of §7 in this record.

**Gates (phase0 §3), all at `75b550c1` on today's chain.**

| gate | check | result |
|---|---|---|
| G-0 | copied additive path and copied chain (replica) equal `Terra.render` | pass, 24 renders, bit-identical |
| G-C1 | the capture rule's 20 ms head equals `Fork.striker(source)` | pass, bit-identical for the 3 round-2 strikers and the 10 struck-shape strikers |
| G-A1 | COLOURED (A and A-D) at c = 0 with any striker equals `Terra.render` | pass, 144 renders |
| G-A2 | COLOURED with an impulse at c = .5 and 1 equals `Terra.render` | pass, 96 renders |
| G-A3 | A-D with DROOP 0 equals A | pass, 12 pairs |
| G-P0a | P0Bank with no inputs equals `Terra.render`: 4 voices at defaults, 20 macro probes, all 16 `TerraKits.classic()` pads (A04, A07, A16 BUZZ; A15 CLACK) | pass, 40 cases, bit-identical |
| G-P0b/c | identity level curve (1f) and identity pitch curve (1f), alone and together, equal `Terra.render` (same 40 cases) | pass, 120 renders |
| G-P0d | HIT c = 0 with each of the three capture-rule heads equals `Terra.render` (same 40 cases) | pass, 120 renders |
| G-P0e | HIT with an impulse at c = .5 and 1 equals `Terra.render` (same 40 cases) | pass, 80 renders |
| G-P0f | HIT 1 with THUMP KICK on the same 40 cases: length equals TERRA's, all finite, peak at most 1 | pass |
| G-P0g | the 16 shipped pads' audio equals the prototype path with no inputs | pass, 16 of 16 |
| G-P0h | prototype HIT equals round-2 `renderA` (4 voices, 10 strikers, c .5 and 1) | pass, 80 renders, bit-identical (against struck-shape's float-rounded path the largest sample difference is 6e-4, on the cavity only) |
| G-B1 | RUNG with an impulse, FORCE low-pass off, BP_PEAK level: per mode at most 0.06 dB, residual at most -60 dB | membrane 0.0002, bell 0.0104, bar 0.0002 dB pass; **cavity 0.0951 dB fail** (residual -64.0 dB); before 0.0930 dB, passed in round 2 by widening to 0.10; not widened here |
| G-A4 | A-D against RUNG (FORCE low-pass off): OB within 0.5 dB, per-mode level within 1 dB | OB matches (0.00 to 0.01 dB on the membrane); per-mode level fails on all three membrane strikers: 1.85, 3.47, 2.33 dB (before 1.86, 4.58, 2.13), so A-D stays out |

**T3 (phase0 §4.4). OB floor.**

| voice | unstruck OB B/A | floor B/A (dB abs) | unstruck minus floor B/A |
|---|---|---|---|
| MEMBRANE | -18.12 / -18.12 | -67.60 / -67.60 | 49.5 / 49.5 |
| CAVITY | -37.82 / -37.83 | -49.56 / -55.34 | 11.7 / 17.5 |
| BELL | -46.10 / -46.10 | -76.68 / -76.68 | 30.6 / 30.6 |
| BAR | -41.42 / -41.42 | -75.87 / -75.87 | 34.5 / 34.5 |

**T2 (phase0 §4.2). Overtone balance against unstruck over ten strikers (mean / min / max / spread).**

| voice | candidate | BEFORE | AFTER |
|---|---|---|---|
| MEMBRANE | COLOURED .5 | -0.33 / -8.08 / 3.48 / 11.56 | same |
| MEMBRANE | COLOURED 1 | -0.67 / -15.70 / 6.87 / 22.57 | same |
| CAVITY | COLOURED .5 | 3.04 / -6.75 / 12.13 / 18.88 | 2.99 / -7.00 / 12.13 / 19.13 |
| CAVITY | COLOURED 1 | 7.46 / -9.05 / 24.48 / 33.53 | 6.33 / -14.61 / 24.49 / 39.10 |
| CAVITY | RUNG | 6.89 / -8.15 / 21.00 / 29.15 | 5.75 / -14.05 / 20.99 / 35.04 |
| BELL | COLOURED .5 / 1 | -1.12 / -5.66 / 3.31 / 8.97 ; -2.86 / -15.04 / 8.41 / 23.45 | same to 0.01 |
| BAR | COLOURED .5 / 1 | -1.61 / -7.34 / 6.74 / 14.08 ; -9.45 / -33.78 / 27.10 / 60.88 | same to 0.02 |

**Rows that moved by 0.5 dB or more (phase0 §4.3)** (4 rows, all the cavity with a kick-type striker; two within 3 dB of the new floor):
COLOURED 1 with THUMP KICK -9.05 → -14.58, with SAMPLE KICK -8.92 → -14.61; RUNG with THUMP KICK -8.15 → -14.05, with SAMPLE KICK
-7.70 → -13.34. Class flips across HIT and RUNG: none (the one flip in the full set is a candidate the owner never heard).

**T11/T12 (phase0 §4.5). HIT per strength (over ten strikers; first-5-ms peak as mean ratio / dB; time to peak mean ms).**

| voice | c | state | OB mean / spread | time to peak | first-5-ms peak |
|---|---|---|---|---|---|
| MEMBRANE | .5 | B → A | -0.33 / 11.56 → same | 0.29 → 0.29 | 1.000 → 1.000 |
| MEMBRANE | 1 | B → A | -0.67 / 22.57 → same | 4.03 → 4.22 | 0.855 (-1.63 dB) → 0.815 (-2.22 dB) |
| CAVITY | .5 | B → A | 3.04 / 18.89 → 2.99 / 19.15 | 0.47 → 0.47 | 1.000 → 1.000 |
| CAVITY | 1 | B → A | 7.46 / 33.53 → 6.34 / 39.08 | 8.66 → 9.76 | 0.894 (-1.08) → 0.832 (-1.85) |
| BELL | .5 / 1 | B → A | unchanged to 0.01 | 0.12 / 0.81 → same | 1.000 / 0.970 → same |
| BAR | .5 / 1 | B → A | unchanged to 0.02 | 0.31 / 0.52 → same | 1.000 / 1.000 → same |

HIT 1 turns 5 of 10 membrane renders from TOM to PERC before and after alike; HIT .5 never changes class (40 renders).
Mean first-5-ms peak in dB, BEFORE / AFTER / change: RUNG membrane -0.92 / -1.63 / -0.71, cavity -0.93 / -1.96 / -1.03; round 1
membrane -0.88 / -1.55, cavity -2.26 / -3.16, bar -0.96 / -1.64; HIT 1 membrane -1.63 / -2.22, cavity -1.08 / -1.85; bell and bar
unchanged for HIT and RUNG.

**Cavity's tanh input (phase0 §4.7; peak of 1.15 times the 75 Hz band-pass; today's unstruck value 0.3075, body only 0.2983).**

| striker | HIT .5 | HIT 1 | RUNG |
|---|---|---|---|
| THUMP KICK | 0.3260 (x1.06) | 0.3571 (x1.16) | 0.3118 (x1.01) |
| THUMP SNARE | 0.2468 (x0.80) | 0.2049 (x0.67) | 0.2871 (x0.93) |
| WRAITH WORD | 0.2381 (x0.77) | 0.1867 (x0.61) | 0.3199 (x1.04) |

At the largest drive (0.357) tanh(x)/x is 0.960 (4.0 % compression; today's 0.3075 gives 0.970).

**BUZZ (phase0 §4.7), time above the 0.12 threshold at BUZZ 1 (ms at 176.4 kHz).**

| voice | today | striker | HIT .5 | HIT 1 | RUNG |
|---|---|---|---|---|---|
| bar | 53.2 | THUMP KICK / SNARE / WRAITH | 55.2 / 47.8 / 35.7 | 54.2 / 41.3 / 13.3 | 55.4 / 38.6 / 13.6 |
| cavity | 55.6 | THUMP KICK / SNARE / WRAITH | 63.7 / 40.0 / 32.2 | 69.3 / 24.7 / 14.2 | 60.1 / 43.3 / 21.6 |

**CLACK pre-click (phase0 §4.7)** (CONICAL_BELL, CLACK 1, pre-roll peak over body peak): today 5.9 %; HIT .5 7.6 % (THUMP KICK) and 6.9 % (THUMP
SNARE); HIT 1 16.5 % and 15.1 %; RUNG 11.4 % and 14.1 %.

**Render cost (phase0 §4.7)** (median of 7 after 3 warm-ups; two blocks, so the pair shows the noise; striker THUMP KICK), multiples of an unstruck render:

| voice | unstruck ms | P0Bank, no inputs | HIT .5 | HIT 1 | RUNG |
|---|---|---|---|---|---|
| membrane | 10.8 / 11.4 | 1.05 / 1.00 | 1.67 / 1.56 | 1.56 / 1.48 | 0.95 / 0.89 |
| cavity | 9.8 / 9.7 | 1.02 / 1.03 | 1.48 / 1.48 | 1.51 / 1.48 | 1.21 / 1.20 |
| bell | 12.5 / 12.5 | 1.03 / 0.98 | 1.44 / 1.42 | 1.45 / 1.43 | 0.76 / 0.76 |
| bar | 9.9 / 9.9 | 0.99 / 0.98 | 1.42 / 1.42 | 1.43 / 1.41 | 0.90 / 0.90 |

**Bold clips on today's chain (phase0 §4.8).** kick-bends-drum: copy with the droop curve equals `Terra.render` (G1, 21609 frames); real-THUMP
period check worst relative error 0.0051 (bold-steer printed 0.005); clip 0.592 s, peak 0.560, DC 0.00069 (0.00070 now); NCC from 20 ms against plain TERRA at the
settled pitch 0.189 (DROOP 0) / 0.197 (default); nearest in the app 0.3769 (323 candidates). Talking drum: gate max difference 0.0
(55566 frames); pitch error median 5 cents, worst -14 cents; per-mode r 0.99 0.98 1.00 1.00 1.00 1.00; ablation NCC 0.856 / 0.087;
centroid at 60, 120, 200, 300 ms 708, 567, 322, 287 Hz; class PERC; clip peak after levelling 0.4324 (0.4318 now); nearest in the
app 0.1664 (162 candidates); render 58.0 against 27.2 ms (2.1 times).

**Round-2 clip plan re-rendered, summary (phase0 §2.3, appendix T6).** 43 distinct renders; 11 moved by the materiality rule: three with OB moves, all on the cavity (`rc_r1_tkick` -4.35 dB, `rc_a100_tkick`
-5.52 dB and `rc_b_tkick` -7.33 dB, the last two on the floor), and eight with time-to-peak moves on plateau renders only. Examples: `cm_r1_tsnare` peak
4.56 → 13.65 ms; `tb_r1_tkick` 1.79 → 13.31 ms; `cm_b_wraith` 1.59 → 9.25 ms; `rc_a100_tkick` OB -46.86 → -52.37 (on the floor); `rc_b_tkick` -46.56 → -53.89 (on the
floor). The three round-2 membrane strikers (phase0 §4.6): OB against unstruck COLOURED 50 % -3.0 / +3.5 / +2.9 (spread 6.4, mean +1.1), COLOURED
100 % -7.1 / +6.9 / +4.9 (14.0, +1.6), RUNG -0.6 / +9.5 / -8.2 (17.7, +0.3), identical before and after.

## Appendix B — the Phase-0 prototype source, in full (spike, not for the build)

### B.1 `Phase0Bank.kt`

The whole file, as run (test source set at `75b550c1`; 101 lines, 74 of them code). `P0Bank.bank` is `Terra.strikeAndModalBank`
(`Terra.kt:367-408`) with the CLACK onset kept and two optional inputs; with neither, the arithmetic is the original's,
operation for operation. `P0Bank.render` is `Terra.render`'s path through it (bank, the voice's own post-bank stage, then the
output chain at `Terra.kt:132`, `:150-158`: normalise, `Punch.applyOversampled`, `limitPeak`, `fadeTail`). `P0Hit` is HIT,
candidate A. It calls `TerraStruckSpike.plan`, `.postBank`, `.outputChain` and `TerraStruckR2.up`, `.running`, `.levelS`,
which are in the evidence spikes (Appendix C), not here. Note that `droop` here is the droop multiplier (§8.3).

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * PHASE 0 PROTOTYPE (throwaway, never committed): the B-spec TERRA change as a test-source copy.
 *
 * [P0Bank.bank] is `Terra.strikeAndModalBank` (Terra.kt:367-408 at 75b550c1) with the CLACK onset kept and two optional inputs:
 *  - `level(k, n)`: a per-mode multiplier on `mode.gain` (n counted from the STRIKE, i.e. from `onsetSamples`);
 *  - `pitch(n)`: a multiplier on the DROOP line (n counted from the strike), clamped to [PITCH_MIN, PITCH_MAX] so it can never reach 0.
 * With neither input the arithmetic is the original's, operation for operation, so the output is bit-identical to Terra's bank.
 *
 * The skip guard (`mode.gain == 0f`) reads the TABLE gain only, so a zero from `level` never skips the phase accumulate (Terra.kt:396-397):
 * the phase keeps running and the mode reopens in phase. `naiveSkip = true` builds the wrong version (guard on the product) for the hazard test.
 */
internal object P0Bank {

    const val RR = TerraStruckSpike.RENDER_RATE
    const val PITCH_MIN = 0.25f
    const val PITCH_MAX = 4f
    private const val TWO_PI = (2.0 * Math.PI).toFloat()
    private const val T60_NEPERS = 6.9078f
    private const val DROOP_TAU_SECONDS = 0.020f

    class Inputs(val level: ((Int, Int) -> Float)? = null, val pitch: ((Int) -> Float)? = null)

    fun bank(
        p: TerraStruckSpike.Plan,
        exciterAt: (Int) -> Float,
        inp: Inputs? = null,
        frames: Int = p.frames,
        naiveSkip: Boolean = false,
    ): FloatArray {
        val out = FloatArray(frames)
        val phases = FloatArray(p.modes.size)
        val nyquist = RR / 2f
        val lv = inp?.level
        val pm = inp?.pitch
        for (i in out.indices) {
            val exciter = exciterAt(i)
            var modalSum = 0f
            if (i >= p.clackSamples) {
                val n = i - p.clackSamples
                val t = n.toFloat() / RR
                val droop = 1f + p.droopDepth * exp(-t / DROOP_TAU_SECONDS)
                val currentF0 = if (pm == null) p.fundamentalHz * droop else p.fundamentalHz * (droop * pm(n).coerceIn(PITCH_MIN, PITCH_MAX))
                for (k in p.modes.indices) {
                    val mode = p.modes[k]
                    val hz = currentF0 * mode.ratio
                    if (hz <= 0f || hz >= nyquist || mode.t60 <= 0f || mode.gain == 0f) continue
                    val g = lv?.invoke(k, n)
                    if (naiveSkip && g != null && mode.gain * g == 0f) continue
                    phases[k] += TWO_PI * hz / RR
                    if (phases[k] >= TWO_PI) phases[k] -= TWO_PI
                    val decay = exp(-T60_NEPERS * t / mode.t60)
                    modalSum += if (g == null) sin(phases[k]) * mode.gain * decay else sin(phases[k]) * (mode.gain * g) * decay
                }
            }
            out[i] = 0.35f * exciter + 0.65f * modalSum
        }
        return out
    }

    /** Terra.render's path for a [Plan] through this bank: bank, the voice's post-bank stage, then the output chain. */
    fun render(p: TerraStruckSpike.Plan, inp: Inputs? = null, exciterAt: (Int) -> Float = p.exciter, frames: Int = p.frames): Snip =
        TerraStruckSpike.outputChain(TerraStruckSpike.postBank(p, bank(p, exciterAt, inp, frames)), p.clackSamples)
}

/** HIT: candidate A (coloured, nominal-pitch read), run through [P0Bank]. The round-2 formulas, from [TerraStruckR2]. */
internal object P0Hit {

    class Prep(val p: TerraStruckSpike.Plan, val run: Array<DoubleArray>, val s: Double) {
        val m = run[0].size
    }

    /** [head] null = an impulse striker (gates). The head is a capture-rule or Fork.striker head at 44.1 kHz. */
    fun prep(voice: TerraVoice, macros: Map<String, Float>, head: FloatArray?): Prep {
        val p = TerraStruckSpike.plan(voice, macros)
        val x = if (head == null) floatArrayOf(1f) else TerraStruckR2.up(head)
        val run = TerraStruckR2.running(x, p, 0.0)
        return Prep(p, run, TerraStruckR2.levelS(p, run))
    }

    /** gain_k(n) = (1-c) + c*s*|P_k(n)|, constant after the head; c = 0 gives exactly 1f for every mode and sample. */
    fun levelOf(pr: Prep, c: Double): (Int, Int) -> Float {
        val gains = Array(pr.p.modes.size) { k -> FloatArray(pr.m) { n -> ((1.0 - c) + c * pr.s * pr.run[k][n]).toFloat() } }
        return { k, n -> gains[k][min(n, pr.m - 1)] }
    }

    class Out(val snip: Snip, val p: TerraStruckSpike.Plan, val bank: FloatArray, val pre: FloatArray)

    fun render(voice: TerraVoice, macros: Map<String, Float>, head: FloatArray?, c: Double, pitch: ((Int) -> Float)? = null): Out {
        val pr = prep(voice, macros, head)
        val bank = P0Bank.bank(pr.p, pr.p.exciter, P0Bank.Inputs(level = levelOf(pr, c), pitch = pitch))
        val pre = TerraStruckSpike.postBank(pr.p, bank)
        return Out(TerraStruckSpike.outputChain(pre.copyOf(), pr.p.clackSamples), pr.p, bank, pre)
    }
}
```

### B.2 The harness switch

The one edit the rebase needs in the copied spike sources (the same guard before the compressor is in `SteerTerra.kt:298-299`,
which has its own copy of `compressAttack`, `:275`; the function is kept in both spikes from `5e3f5f3e`):

```kotlin
// TerraStruckSpike.kt:36-37
/** PHASE 0: true = the pre-a6dcb87e chain (with Terra's old attack compressor), for the harness check and the BEFORE column only. */
var legacyCompressor = System.getProperty("p0.legacy") == "true"

// TerraStruckSpike.kt:269-271, inside outputChain, right after Dsp.normalize(raw)
if (legacyCompressor) compressAttack(raw, RENDER_RATE)      // `compressAttack` is the spike's private copy of Terra's removed stage (:246)

// TerraStruckSpike.kt:284-286
fun terra(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip =
    if (legacyCompressor) replica(voice, macros) else Terra.render(voice, macros)
```

The `phase0` and `phase0Shape` gradle tasks set the property from `-Plegacy` (`systemProperty("p0.legacy", ...)`). The tasks are in
the build diff (`phase0-work/code/build-gradle-and-tracked.diff`, in the scratchpad, see "Durability"), together with
`steerNearestP0` and `talkNearestP0` in `:shell`; they write to the scratchpad by absolute path.

## Appendix C — where the full spikes live

**`~/Documents/snipsnap-chimera-evidence-2026-09-29/spikes/`** (local; apply on `5e3f5f3e`; never on `75b550c1` without the Phase-0
rebase switch of Appendix B.2). Phase 0's own files (the harness, `Phase0Bank.kt`) are not in this folder; see "Durability". Each folder holds `changes.patch` (tracked edits, the gradle tasks in the table under "How to re-run") and
`new-files/` (untracked sources). Map to what is in this record:

| folder | the files that matter here |
|---|---|
| `struck` | `TerraStruckSpike` (copy of TERRA's tables, exciters, bank, cavity stage, chain; the `replica` gate), `TerraStruckShape` (the bank in four implementations, GAIN_LIN, `strikerMagnitudes`), `TerraStruckShapeRunner`, `TerraStruckR2` (`running`, `levelS`, `renderA`, `gainedBank`), `StruckMetrics`, `TerraStruckAuditionGenerator` |
| `struck2` | `TerraStruckMotion` (the safe capture `head`, `onsetOf`, `sourcePeak`; the coupled-form bank), `TSMExpM1` to `M7` (the seven experiments; `M7` has `SILENT_PEAK`), `TerraStruckMotionRun` |
| `bold-steer` | `SteerSpike` (`kickCurve`, clips), `SteerTerra` (`curvedBank`), `SteerTide`, `SteerNearest` (:shell) |
| `bold-ring` | `RingSpike` (`sympathetic`, `bloom`, `kickCycles`, `Contour`, `kickStringDive`), `RingNearest` (:shell) |
| `bold-talk` | `TalkWord` (track, tract copy, `response`), `TalkDrum`, `TalkGong` (in-engine and `postHoc`), `TalkKick` (morph), `TalkClips`, `TalkMeasure`, `TalkUtil`, `TalkNearest` (:shell) |
| `empirical`, `hybrid2` | `Chimera*.kt` (the proposal, compile-fixed), `ChimeraTest`, `ChimeraProbeTest`, `ChimeraVerifyTest`, `HybridLab`, `HybridTuneH1`-`H3`, `HybridMacrosExperiment`, `HybridR2Generator` |
| `mutate` | `MutateAuditionSpike` (:shell), `MutateVoxKickSpike` |

Reports and clips in the same evidence folder: `chimera-reports/` (six lenses, both verifiers, `synthesis.md`),
`chimera-experiments/` (`struck-shape/report.md`, `struck-motion/results/`, `struck-r2-spec.md`, `hybrid-r2-spec.md`,
`hybrid-tune/`, `hybrid-macros/`, `bold/{steer,ring,talk}/notes.md`), `chimera-audition/` (every clip, `manifest*.json`,
`verify.md`, `verify-r2.md`, `notes-*.md`, `START_HERE/`, `BOLD/`, `r2-work/held-back-a100d/`).

## Appendix D — the Phase-0 listening page (six clips as built, three of them the brief's check)

Mono 44.1 kHz, `AuditionLevel.level` then 16-bit, 0.490 s, peaks 0.19-0.55, RMS within 2.0 dB of each other. HIT 1 is COLOURED
100 % read at the nominal pitch (candidate A through the prototype bank, asserted bit-identical to round-2 `renderA`). RUNG is the
round-2 recipe (FORCE low-pass on, TERRA's click). WRAITH WORD is the struck-shape striker rendered from VOX's WRAITH voice (§3.1).
Checked by an independent script (no renderer code) on the written files; its OB and time to peak
agree with the renderer to 0.02 dB and 0.02 ms (OB: today -18.1, HIT 1 WRAITH -11.2, RUNG WRAITH -8.6, HIT 1 KICK -25.2, RUNG KICK
-18.7 dB; time to peak 0.2, 0.7, 9.3 (heard 1.6), 14.0, 8.3 ms).

**Six clips against the brief's three.** The brief approved a three-clip check without naming the clips. Six were built, which is
a deviation that needs the owner's OK (§7, "The listening check"; the closing table). The last column marks the three-clip subset
that answers question 1.

| id | name | what it is | in the three-clip subset |
|---|---|---|---|
| `cm_unstruck` | TODAY'S DRUM | COMPOUND_MEMBRANE at defaults | yes |
| `cm_hit1_wraith` | HIT 1 · WRAITH WORD | coloured 100 % | yes |
| `cm_rung_wraith` | RUNG · WRAITH WORD | the strong recipe, today's chain | no |
| `cm_rung_wraith_heard` | RUNG · WRAITH WORD · AS YOU HEARD IT | the earlier clip, byte for byte (old chain) | yes |
| `cm_hit1_tkick` | HIT 1 · THUMP KICK | coloured 100 % | no |
| `cm_rung_tkick` | RUNG · THUMP KICK | the strong recipe, today's chain | no |

Questions (three at most; the closing table gives each a default and an alternative): (1) Does HIT 1 sound as strong as the RUNG
clip you heard? (2) Is the soft attack of HIT 1 with the kick head acceptable, or should the drum keep more of today's attack
at 1? (3) Do the two RUNG WRAITH clips, the one you heard and the one rebuilt on today's output stage, sound the same to you?
With only the three-clip subset, question 1 is the only one the page can ask. The page and its fragment (six clips) are not
in the durable evidence folder (see "Durability").

## Owner decisions this record raises (for the specs' "Decisions for the owner")

Where a row says "approved sentence", the alternative or the default relaxes a sentence of the owner-approved design; a default
marked "proposed" is not approved until the owner says so.

| decision | default | alternative |
|---|---|---|
| BUZZ and the cavity's drive under HIT's level match (§7.4). The brief's "keeps their drive as today" and "s matches the peak of today's body" cannot both hold | **proposed, pending the owner's OK:** Option A, BUZZ and the tanh follow the striker (relaxes "keeps their drive as today"); the test pins the measured ranges and equality at HIT 0 | Option B: a band-passed-peak level match on the cavity (relaxes "s matches the peak of today's body"; holds drive and BUZZ near today's; brighter heads get a different overtone balance, with the low modes lifted about 10 dB, an estimate) |
| HIT at 1 (Phase-0 listening check, question 1) | "strong" (1) = coloured 100 % | if heard as weaker than the strong clip: try A-D first, which is not free (attack); decide the fix before build round R1 |
| The soft attack of HIT 1 with a bass head (question 2; §7.3) | accept: HIT 1 is not "today's attack plus colour" for a bass head, and the spec names the strength in every claim about attack | keep more of today's attack at 1; A-D is the first candidate and its attack is not free either (peak 8.7 ms with THUMP KICK, against HIT 1's 14.0) |
| The two RUNG WRAITH clips (question 3; §7.2) | treat the 0.6 dB difference in the first 5 ms as small and cite the rebuilt clip | if the owner hears them differently, judge HIT 1 against the clip as heard (`cm_rung_wraith_heard`) and re-state RUNG's figures on the old chain |
| Six clips or the brief's three (Group B spec, decision 21) | three clips (a subset: today's drum, HIT 1 with WRAITH WORD, RUNG with WRAITH WORD as heard); question 1 only | the six as built, with questions 2 and 3, if the owner OKs the deviation |
| TALK's ring length (§8.4): the brief says "about 1.6 times longer" | the brief's wording, as the Group B spec takes it (its decision 7): a uniform 1.6 on every t60 and on the length; unmeasured, it does not reproduce the clip and shortens the upper-mode vowel tails, so it is a new listening value | the clip as heard: the measured per-mode table, `1.6 * (1 + 0.65k) / (1 + 0.30k)` (1.60 to 2.72), with the damping step .30 and the fixed 1.4 s render; it matches the clip and the -116 dB bake |
| Which peak the capture fallback tests (§8.2; Group B spec, decision 20) | the brief's wording: the peak of the aligned, sanitised 20 ms head, before normalising (re-run the hostile list with it) | the whole-source finite peak, as measured (struck-motion M7); differs only for quiet sources, peak 1e-4 to 1e-2 |
| What BEND stores (§6.1, §8.3) | the pitch curve only, as the brief defines it; HIT from the same pad supplies an attack; the clip's layered 15 ms kick head is not built; the range [1, 3] is a spike limit and the build clamp is the brief's 0.25-4 | also store or layer a kick head (not in the brief; a new decision) |
