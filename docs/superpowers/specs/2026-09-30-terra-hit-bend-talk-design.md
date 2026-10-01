# TERRA's hook — HIT, BEND and TALK: another pad's hit inside TERRA's own modes

**Status:** design; Group B of the CHIMERA brainstorm; not implemented. The
brainstorm began with two outside documents proposing CHIMERA, a meta-engine
that renders two engines and joins them. The six-lens review found that the
documents' engine does not compile (12 errors from 3 root causes, all against
`Terra.render`), that six of its seven voices come out a quarter of the length
and two octaves sharp, and that five of its seven voices duplicate MUTATE or the
rack's RING chip ("The specification, as reviewed"). The one idea the app
lacks is **one pad's hit exciting another engine's body**. This document builds
that idea into TERRA, the world-percussion engine, as three drivers on a TERRA
pad: **HIT** (another pad's first 20 ms colours how hard each of the drum's
modes rings), **BEND** (another pad's pitch drop bends the drum) and **TALK**
(the drum speaks one of VOX SPEAK's eight words). All three enter TERRA through
one change, two optional inputs to the modal loop that all four TERRA voices
share, and with neither input TERRA renders exactly as today. The adopted
algorithms were built as throwaway spikes, measured over two experiment rounds
and a "bold" round, and re-measured at the current tree in a Phase 0 (a
throwaway prototype run before design, kept outside the build): the numeric
checks pass, HIT at 0.5 did not move, and one conflict inside the approved brief
(BUZZ's drive under HIT) is escalated rather than reworded, with the six smaller
departures listed in one place ("Where this document departs from the brief",
"Decisions for the owner").
No engine code is committed. This document lands as a docs-only PR (zero check
runs by design: `.github/workflows/tests.yml:18-34` ignores `docs/**` and `**/*.md`; ARCO, BORE,
FORK and MAGNET landed the same way) beside its sibling, the Group A spec, and
the Phase-0 record.
**Date:** 2026-09-30
**Plan:** to be written per round: `docs/superpowers/plans/2026-09-30-terra-hook-r1.md`
(HIT in the engine), `…-r2.md` (TERRA on the phone), `…-r3.md` (BEND and TALK),
`…-r4.md` (the pad-sheet group and the shared chooser). The Phase-0 record is
[`../plans/2026-09-30-chimera-phase-0-record.md`](../plans/2026-09-30-chimera-phase-0-record.md),
kept outside the build as ARCO's was; it holds the experiments' method, the
adopted candidates' formulas and code excerpts, the numbers and where the full
patches live.
**Related:** [`2026-09-29-arco-bowed-string-engine-design.md`](2026-09-29-arco-bowed-string-engine-design.md)
and [`2026-09-28-bore-woodwind-engine-design.md`](2026-09-28-bore-woodwind-engine-design.md)
set this document's shape (an outside spec reviewed against the code, the
experiments measured, the fleet table, claims tests, rounds with gates).
[`2026-09-27-fork-electric-piano-engine-design.md`](2026-09-27-fork-electric-piano-engine-design.md)
set the precedent every stored driver here copies: a captured hit kept **as
data inside the recipe** (`ForkPatch.striker`, `Patches.kt:426-504`), so a
struck pad regenerates bit-for-bit from `kit.json`.
[`2026-09-30-become-strung-say-design.md`](2026-09-30-become-strung-say-design.md)
is Group A, built alongside this one; the two share two pieces of groundwork
(the frozen VOX SPEAK guard and the formant-track extraction, "TALK, the
design") and one component (the shared "pick a pad" chooser, this document's
R4).
**Roadmap:** **S20** is claimed for the TERRA reach round (R2), at
implementation, after a fresh check for a competing claim. Today the phasing
table's highest row is S19 (BORE, `docs/SYNTH_ROADMAP.md:273`), `grep S20` over
`docs/`, `README.md` and `design/` finds only the Group A spec saying that this
document claims it (`2026-09-30-become-strung-say-design.md`, its "Roadmap"
header line and its decision 13), and
TERRA has no row of its own (`grep -in terra docs/SYNTH_ROADMAP.md` finds only
S16's passing mention at `:270`). HIT, BEND and TALK are a TERRA round, not an
engine, and take no row of their own.
**Evidence:** the six-lens review of the CHIMERA documents at `5e3f5f3e` (an
API audit, a DSP desk review, an empirical probe that transcribed the documents'
Kotlin and rendered it, a fleet comparison, a product review and a house
conventions audit, plus two verifiers) and its synthesis, which supplies the
"as reviewed" table (`synthesis`); the round-one struck spike's notes
(`struck-r1`); round two's two sub-experiments, `struck-shape` (brightness and
coupling, sections S0–S7) and `struck-motion` (droop, head, cavity, CLACK,
BUZZ, cost, robustness, M1–M7), their convergence spec (`struck-r2-spec`) and
the independent check of the round-two page (`verify-r2`); the bold round's
steer and talk families (`bold-steer`, `bold-talk`); the design maps written at
`1a2ec180` (`map terra-hook`, `map pad-drive`) and their completeness review
(`critic`); and the **Phase 0 re-measure at `75b550c1`** (`phase0`, tables
`T1`–`T12` and gates `G-*`), run because commit `a6dcb87e` reverted TERRA's
attack compressor, which every struck spike had copied. Tags of the form
`(empirical §A2)`, `(dsp F5)`, `(struck-shape S1)`, `(struck-motion M7)`,
`(verify-r2 §3)`, `(bold-talk §4)`, `(phase0 T11)` or `(map pad-drive §4)` name
the report and its section. The reports are local files in the evidence folder
(Appendix A), not in the tree, so every fact they back is restated here in full and
the tag records only which report measured it. Every code citation is a
repo-relative `file:line` read at **`75b550c1`** for this document (`tree check`);
cites the evidence took at `5e3f5f3e` or `1a2ec180` were re-read there and
corrected where the lines moved. Owner answers are quoted from the brainstorm of
2026-09-29–30. Rechecked at `129bc48e` just before landing: of the files this
document names, `SynthScreen.kt`, `PadSheet.kt`, `Velocity.kt`, `Keys.kt`,
`Dsp.kt`, `DeterminismTest.kt`, `PadRecipeTest.kt`, `README.md`,
`docs/SYNTH_ROADMAP.md` and `synth/build.gradle.kts` moved; none of the cited
claims changed, and the cites keep their `75b550c1` lines (`SynthScreen.kt` and
`PadSheet.kt` by +1 to +16, `synth/build.gradle.kts` by −19; `Velocity.kt` lost
only a doc comment, so `BRIGHTNESS_MACROS`, still with no TERRA macro, is at
`:463` and not `:500`). The `Engine` enum is still thirteen entries, TERRA is
still in neither `DeterminismTest` nor `PadRecipeTest`, S19 is still the
roadmap's highest row, and `Terra.kt`, `TerraPatch.kt`, `Patches.kt`, `Fork.kt`,
`Thump.kt` and `VoxSpeak.kt` are byte-identical, so no Phase-0 number moves.

## Why the TERRA hook

The owner uploaded two generated PDFs proposing CHIMERA, "an offline synthesis
meta-engine in :synth that structurally couples two sound engines (THUMP, TIDE,
TINES, FORK, VOX, RESIN, TERRA) into a single monophonic 44.1 kHz Snip", and
asked whether SnipSnap could implement it. The review's answer, in one line: the
contract half is close to the house shape, the signal half wires every
mechanism to the wrong input, and of its seven voices only one idea is new —
**a vocal or drum hit as the exciter of TERRA's modes** (synthesis §4, §6). The
owner heard the review's candidates (140 clips, round one) and said "they sound
ok", then asked to "build out and run experiments to see what might need to be
tweaked". Round two (111 clips) was too many; the owner gave up on it, and a
nine-clip "start here" replaced it. From that page came the answer that shapes
HIT: "**A knob between them**" (today → subtle → strong). Asked how far a
hybrid should go, the owner said "**I think we could go further/bolder**"; the
bold round's nine clips in three families (steer, ring, talk) ended with
"**keep Steer, Ring and Talk**". The owner then approved a regrouping by build
seam:

- **Group A, the quick wins:** MORPH over time (BECOME), the clap's strings as
  a chip (STRUNG), and the gong-speaks rack section (SAY). Its own spec.
- **Group B, this document, the TERRA hook:** HIT, BEND and TALK — the three
  sounds that need one change inside one engine's loop.
- **Group C, later:** snare drives gong (TIDE's fold curve), word shapes bell
  (TINES' index curve), kick plucks string (the strings retune). It will reuse
  this document's capture plumbing.
- "Snare rings the tines" is parked for FORK, whose 882-sample hammer rule it
  breaks; Ring dissolves into the other groups.

The owner chose the order "**A alongside B**", approved this group's design in
three sections (the TERRA change and what a pad remembers; the chooser and the
pad-sheet group; TERRA on the phone, the tests and the rounds) and said "**Yes,
write the specs**".

Why TERRA, and why here. TERRA's body is an **additive** bank: decaying sines
that start at the strike, each mode with its own gain, t60, strike-position
weight and a droop-sliding pitch on one onset clock, in one function shared by
all four voices — COMPOUND_MEMBRANE, RESONANT_CAVITY, CONICAL_BELL and
TUNED_BAR (`Terra.kt:44, :367-408`). So its per-mode level and its pitch are two
numbers inside one per-sample loop. Other engines accumulate phases too —
THUMP's metallic squares (`Thump.kt:548`), TONEWHEEL's drawbars
(`Tonewheel.kt:193`) — but they are not struck bodies with decaying modes; the
engines that do ring a struck body's modes ring them as resonators fed by a
signal (`Modes.ring`, `Modes.kt:85`; `Fork.excite`, `Fork.kt:491`), where "how
hard did the hit ring mode k" is a property of the signal, not a number you can
turn. In TERRA it is a
number, and the bank already integrates a moving pitch click-free (the phase
accumulator at `Terra.kt:397`), which is what DROOP has done since TERRA
shipped. So the three drivers are one hook (a per-mode level curve and a pitch
curve) and three ways to fill it (from a hit, from a pitch drop, from a word).

A few of the house's words, for a reader new to them. A **mode** is one of a
struck body's natural vibrations, a decaying sine at a fixed multiple of the
fundamental (TERRA's membrane has six, at 1, 1.99, 2.98, 3.99, 4.88 and 5.92
times its note, `Terra.kt:167-168`). **DROOP** is TERRA's pitch sag: a hit
stretches the head, so the drum starts sharp and settles back onto its note
over about 20 ms (`Terra.kt:268, :390`). A **recipe** is what a synth pad
regenerates from: its patch (engine, voice, macro values) plus its rack chain
(`PadRecipe.kt`), saved verbatim in `kit.json`. A **striker** is a captured hit
used as an exciter; FORK keeps one as 882 numbers in its patch
(`Patches.kt:426-504`). A **gate**, unqualified, is a listening page whose verdict decides
whether the next round starts; the reports' own numeric checks (`G-A4`, `G-P0a`,
`G-C1`, bold-steer's `G1` and `G4`) are always tagged with their report here and
are checks, not gates. `G1` and `F1` unqualified are the two labels this
document and Group A's give the VOX SPEAK guard and the formant extraction
("TALK, the design"). **Round one, round two and the bold round** are the
experiment rounds; **R1–R4** are the build rounds, and report tags such as
`struck-r2-spec` and `verify-r2` keep their file names. A **claims test** measures the rendered audio
for the property the design promises. **OB** (overtone balance) is this
document's brightness figure: `10·log10(energy above 1.5 × f0 / energy at or
below it)`, from 20 ms after the onset to the end, on the float render (phase0
§2.4). **NCC** is normalised cross-correlation (1.0 = the same wave); the
rounds used it, from 20 ms after the onset, as the "is it already in the app"
test, with 0.9 as the bar. A **cent** is a hundredth of a semitone.

## The specification, as reviewed

The *contract* half of the CHIMERA documents is close to right: `ChimeraPatch`,
the preset helper and the `Patches`/`Velocity` arms have the house shape. The
*signal* half names real mechanisms and wires every one of them to the wrong
input. Three failures, any one of which sinks the file (synthesis §1–§4):

1. **It does not compile.** Twelve errors from three root causes, all in
   `Chimera.kt`, all against `Terra.render`: six uses of `TerraVoice.MEMBRANE /
   BAR / CAVITY`, which do not exist (the real names are `COMPOUND_MEMBRANE,
   RESONANT_CAVITY, CONICAL_BELL, TUNED_BAR`, `Terra.kt:44`); four `seed=`
   arguments to a `Terra.render(voice, macros)` that takes none
   (`Terra.kt:110`); two `striker=` arguments, which belong to `Fork.render`
   (`Fork.kt:883`) (api B1–B3; empirical F1; reproduced by dsp F3 and
   verify-signal). After the mechanical fix nothing else fails to compile, but
   the fix is not neutral: deleting `striker=` turns two voices into TERRA
   alone.
2. **Six of the seven voices are decimated twice.** Every component `render`
   returns a finished 44.1 kHz Snip (`Thump.kt:174`, `Terra.kt:159`,
   `Tines.kt:183`, `Tide.kt:551`), and CHIMERA hands its samples to
   `Punch.applyOversampled`, which assumes a buffer at four times the rate and
   decimates by four (`Punch.kt:270-271`, `Dsp.kt:695`). A 44.1 kHz sine fed
   through that back end comes out exactly 4.000× in pitch and 0.250× in
   length; six voices measure 0.250 of their components' length at defaults,
   and the one control voice whose buffer really is 4× measures 1.000
   (empirical §A2; dsp F1; verify-signal re-derived it).
3. **The one new topology has nothing to plug into.** TERRA takes no striker,
   and its internal exciter lambda does not drive its modes: the exciter is
   mixed in at 35 % beside the modal sum and read nowhere else
   (`Terra.kt:381, :405`), although the shared function's own KDoc says
   "[exciterAt] into [modes]" (`Terra.kt:354`), which contradicts the code.
   Fleet F2 and the code verifier named that lambda "the natural injection
   point"; the synthesis overturned them by reading the body (synthesis §6).

Read against the code, surface by surface. The middle column is re-verified at
`75b550c1`; numbers measured at `5e3f5f3e` are tagged with the report that
measured them and are unchanged by the drift unless the row says so.

| What the PDF says | What the code says (75b550c1) | What we carry |
|---|---|---|
| §A: CHIMERA is an "offline synthesis meta-engine" coupling two of THUMP, TIDE, TINES, FORK, VOX, RESIN, TERRA "without parallel summing artifacts" | No engine's `render` calls another engine's `render` (house F3 at `5e3f5f3e`; the tree check at `75b550c1` finds only shared helpers crossing between engine files, `Tide.bandLimit` and `Siren.bestCut`, for example `Fork.kt:889`, `Siren.kt:411`, `Bore.kt:727` and `:864`; `Keys.kt`, the S5 key patches, engines played at exact MIDI pitch (`Keys.kt:19-26`), calls one engine per note and is not an engine). Combining happens by data (FORK's stored striker; `PadRecipe(patch, fx)`; GRAINS eating a source Snip) or at kit level (`SynthKits.kt:141-148`). The PDF's code names RESIN and never calls it (the tree reaches RESIN through `ResinPatch.render`, `Patches.kt:267`, and `Keys.kt:239`). One undeclared voice (`layer`) *is* a parallel sum | Not a meta-engine. What survives goes into TERRA as data (this document) or stays in MUTATE |
| §A topology 1, TRANSIENT_SPLIT: equal-power `cos θ / sin θ`, split 4–45 ms, fade 1–15 ms | The algebra is right, but equal power holds only for *uncorrelated* parts: the level is `√(1 + ρ·sin 2θ)`. On real components inside the fade ρ runs −0.98 to +0.94, the fade level −3.9 to −0.66 dB, and the RMS step at the join 0.87 to 0.12 (about −17 dB) at defaults (dsp F8; verify-signal). `Mutate.splice` already does the move with a linear 10 ms fade (`Mutate.kt:49, :304-323`) and a split of at least 5 ms (`Mutate.kt:137`) | MUTATE SPLICE as it is |
| §A topology 2, EXCITER_INJECTION: a 20 ms pulse from `Fork.striker`, differentiated (Δx), into `Modes.ring` or `Tide.fold` | Not implemented: nothing differentiates, the PDF's code never calls `Tide.fold` (the tree calls it at `Tide.kt:490`), `ChimeraMode` is read nowhere. `Fork.striker` returns an undifferentiated 882-sample 44.1 kHz head (`Fork.kt:349-350, :859-870`) whose only consumer is `Fork.excite` (`Fork.kt:491`) (dsp F4; fleet F2). The `1/sin θ` onset hazard is real and Δx flattens it: an impulse through one mode peaks at 497.4 at 55 Hz and at 1.000 once differenced, and a 20 ms head's 110:330 Hz tilt falls from 61.9:1 to 20.3:1 (dsp F5) | **The idea to carry**, in the gain domain on TERRA's own bank (HIT). Δx is not carried: at a = 0.5 it is inert (≤ 0.03 dB) and near a = 1 it is a tilt, not a coupling control (struck-shape S3) |
| §A topology 3, SPECTRAL_INTERSECT: `sA(1−β) + β·drive(sA·sB·3, amount)` | Sample-wise ring modulation into a tanh; at BLEND 1 the parents leak through at 0.0063 / 0.0068, equal pitches give 19.9 % DC, `amount` is hard-coded at 0.35, and the output is exactly zero after the shorter part ends (dsp F7; verify-signal). `Dsp.drive` matches the formula (`Dsp.kt:442`) | Nothing as an engine; a clip × clip product stays a possible MUTATE mode, Group C's or never |
| (undeclared) a fourth topology, `layer`: `A(1−β) + B·β` | A plain sum with no alignment and no polarity check. `Mutate.stack` aligns transients and flips a cancelling layer (`Mutate.kt:281-302`) (fleet F1) | MUTATE STACK |
| Output: `Tide.bandLimit` at 19.5 kHz on the 176.4 kHz blend | The filter is what the PDF says, an 8th-order Butterworth, −3.01 dB at 19.5 kHz when run at 176.4 kHz (`Tide.kt:533`); fed a 44.1 kHz buffer labelled 176.4 kHz its corner lands at 4875 Hz (−3.011 dB at 4875, −9.689 dB at 5500; verify-signal) | Correct helper, only on a buffer that really is 4× |
| Output: `Punch.applyOversampled(raw, punch, RATE)` decimates to 44.1 kHz | `applyOversampled` takes its input as `rate · 4` (`Punch.kt:270-271`), so on finished clips it decimates a second time; Punch then stacks (Thump 0.5 inside, TERRA 1.0 inside, `Terra.kt:242`, then the user's PUNCH) (house F2; dsp F9) | TERRA's own chain, once, on TERRA's own 4× buffer — which is where HIT, BEND and TALK live |
| Output: `limitPeak`, then `fadeTail(4 ms)` | `limitPeak`'s default ceiling is 1.0 (`Dsp.kt:672`); six voices peak at exactly 1.0 (api F5) | Unchanged; it is TERRA's own tail (`Terra.kt:157-158`) |
| §B `ChimeraPatch` | The `TerraPatch` shape (`TerraPatch.kt:10-34`): `init { validateMacros }`, `ENGINE`, `fromJsonValue` through `Patches.decode` (`Patches.kt:75-93`, the version check at `:84-85`). It compiles verbatim and round-trips 11/11 (empirical F14). Its `fromJsonText` returns `Patch` where the precedents return the concrete class (house row 2) | The shape, for a `TerraPatch` with optional driver fields modelled on `ForkPatch` ("Data flow and compatibility") |
| §B `ChimeraMode` enum | Referenced nowhere (api F7) | None |
| §B step 2: a `Patches.fromJsonValue` arm | Correct in form (empirical §1); TERRA already has its arm (`Patches.kt:53`) | Not needed |
| §B step 3: a `Velocity.macroSpecsFor` arm | The one compiler-forced surface, an exhaustive `when` over the sealed `Patch` (`Velocity.kt:191-220`); TERRA already has its arm (`Velocity.kt:217`) | Not needed |
| §B step 3: `brightnessOverride → "PUNCH"` | No sweep behind it. PUNCH is a loudness-preserving transient shaper: 0→1 moves THUMP_TERRA's centroid 335.25→335.31 Hz while RMS falls 0.1659→0.0636, and through `atVelocity` soft hits come out louder (empirical F8; product F5; house F8). The override table names THUMP SNARE, PLUCK, SILK and FORK only (`Velocity.kt:239-254`); TERRA falls back to `soften` | None. TERRA keeps `soften`; HIT is not registered as an override until a monotonic sweep passes ("HIT, the design") |
| §C `MACROS`: one list of six for every voice | 10 of 42 voice-macro slots dead by trace, 13 bit-identical at 0 and 1 when measured; CROSS means fade time, growl or "alien" by voice, BLEND means five different things (empirical F5; product F4; dsp §7) | Only live knobs, per voice (the TERRA/VOX precedent, `Terra.kt:48-103`) |
| §C `drumClassFor` by TUNE thresholds | Never measured: agrees with the house classifier on 6 of 18 renders as proposed, 9 of 18 with the rate fixed (empirical F10) | None; TERRA's reach round adds a measured one ("TERRA on the phone") |
| §C `scramble` | Missing the `temperature >= 1f -> base` branch 13 engine files carry (house F9) | Not needed for the hook; TERRA's own `scramble` is a reach-round surface (TERRA has none today: `grep scramble Terra.kt` is empty) |
| §C `render(voice, macros, seed = 0)`, `defaults + macros` | No recipe can store the seed; seed 0 and 42 render bit-identical on all seven voices (house F10; probe D). `defaults + macros` passes unknown keys and does not clamp, where TERRA drops and clamps (`Terra.kt:111-112`). Sub-engine settings are hidden literals no recipe records (fleet F4; house F4) | `Dsp.seedFor` only; every driver stored as data |
| §C THUMP_TERRA | Does not compile; with the fix it is a post-render `timeSplit` at 0.250 of the length, classifying PERC (TOM at the correct rate) (empirical §A2, K1) | The sound goal — a punchy kick with a real membrane decay — rebuilt as a kick's hit (HIT) and its pitch drop (BEND) driving a TERRA membrane, one pad |
| §C TINES_TIDE, THUMP_TIDE, TERRA_TINES | Ring modulation into a tanh, a splice, and a sum; each 0.250 of its length (empirical F2, F5; dsp F7, F14) | MUTATE (SPLICE, STACK) as it is; THUMP_TIDE's splice is reachable in the app today |
| §C BEATBOX_TERRA | Does not compile (`striker=`, `seed=`); with the only possible fix it is TERRA alone, bit-identical, and the vocal hit is rendered and thrown away (empirical F4) | **Carry the idea** as HIT: any pad's head as the exciter, no voice of its own |
| §C WRAITH_TERRA | The same missing input; bit-identical to BEATBOX_TERRA at defaults; BLEND is a six-step word index on a continuous knob (empirical F4; product F4; dsp F15) | Falls out of HIT for free (a WRAITH pad's head can strike a TERRA pad); dropped as a voice |
| §C THROAT_TERRA | Compiles, and its rate chain is right, but it calls no TERRA: a narrow comb on a drone that swells and reads TONAL, or LOOP past DECAY ≈ 0.616 (dsp F6; empirical F11) | Dropped; throat-as-a-hit belongs to VOX |
| §D `ChimeraPresets`: eleven | Two names hit the house blocklist (a drum-machine model number and a synthesizer maker's name), six exceed 14 characters, one sits beside VOX's STEPPE DRONE, the floor is 8 per voice (`ForkPresetsTest.kt:70`), all authored blind (product F1–F3; house F7) | None of the names; TERRA's presets are authored by ear in R2 |
| §E `ChimeraTest`: four tests, JUnit Jupiter | 2 of 4 fail: the peak test at `ChimeraTest.kt:27` (six voices at 1.000 against a ceiling of 0.991 with no house source) and the class test at `:37` (PERC, not KICK, and still TOM with the rate fixed); the two that pass are vacuous (seed reaches nothing; a finiteness check cannot see a 4× rate bug) (empirical Step 3; verify-signal). The synth tests use `kotlin.test` (product F13) | Claims tests, `kotlin.test` ("Testing") |
| Verification steps: register presets, run only `ChimeraTest` | `PresetsTest` hard-codes the roster sum (`PresetsTest.kt:47-53`), so registering eleven presets broke it: expected 848, got 859; the full `:synth:test` ran 1051 tests with 3 failures (empirical F9, at `5e3f5f3e`). CI runs `./gradlew --no-daemon test -x :app:test` (`.github/workflows/tests.yml:73`) | Run the whole suite; TERRA's roster arrives in R2 with its `PresetsTest` line |
| Registration overall: seven paths | FORK's introducing commit touched 22 non-testkit paths and BORE R1 23; the PDF has no audition generator, no kit, no preset test, no determinism canary, no roadmap row and no design PR (house F5, F12) | The house process: a docs-only design PR, then rounds, each ending at a gate |

One more correction, which the review itself made and the rounds then made
again. The synthesis sketched the carried idea as "a second synthesis path
inside Terra": the striker resampled to 176.4 kHz and rung through a two-pole
bank of TERRA's own modes, then TERRA's chain "(which also absorbs the `1/sin
θ` level)" (synthesis §6, item 2). Round two measured that sentence as wrong:
the normalise restores overall level only, and the per-mode tilt that ringing
adds (about `1/ratio_k`) was round one's dullness (struck-r2-spec §8.1;
struck-shape S1). And round two found a smaller design than a second path: the
gain-domain colouring of TERRA's own additive bank, which is HIT
("The experiments, measured"). The synthesis's output chain also still carried
the attack compressor; `a6dcb87e` removed it, and the chain at `75b550c1` is
`Dsp.normalize` → `Punch.applyOversampled` → `Dsp.limitPeak` →
`Dsp.fadeTail` (`Terra.kt:132, :150-156, :157, :158`; the breadcrumb for the
reverted experiment is the comment at `Terra.kt:222-241`).

### What the spec got right

The corrections are many and the instinct is right, so it is worth being exact.

- **The gap is real.** SnipSnap has no way for one engine's attack to excite
  another engine's *body* inside one render. BREED crosses only the same engine
  and voice (`Breed.kt:251`); velocity layers are zones, not stacks; the rack is
  unary, Snip in and Snip out. FORK's own spec lists "a drum hit rung through a
  tine" as an R1 gate question (`2026-09-27-fork-electric-piano-engine-design.md:403`)
  (product; fleet).
- **The `1/sin θ` onset hazard is real**, and its arithmetic is right (dsp F5).
  It simply never reached the code, and round two found where it actually bites:
  per mode, not overall (struck-shape S1).
- **`ChimeraPatch` is the house shape**, compiles verbatim and round-trips 11/11
  (empirical F14), and every external symbol outside TERRA resolves with the
  signature the PDF assumes — the VOX synthesizers, `Modes.resample/spread/ring`,
  `Resampler.resample`, `Punch.applyOversampled`, every `Dsp` call (api table).
  The invention is confined to one API.
- **It names the one compiler-forced surface**, `Velocity.macroSpecsFor`
  (house §4), and its preset helper `p(voice, name, vararg …)` matches the
  desk-promotion test (`UserPresetsTest.kt:163-167`) (house E1).
- **It picks the right output family** for a one-shot pad, the drum chain, in
  an order that respects `Punch.kt`'s own rules (dsp §9; house §4).
- **It uses `RATE` and `Dsp.OVERSAMPLE`**, never a literal 44100; the control
  voice shows the author knew the 4× contract (synthesis §5).
- **It is numerically clean:** no NaN in 300 random renders or in any probe
  render (empirical F14).
- **It reaches for `Fork.striker`**, the 20 ms, 882-sample head that FORK
  already stores as data. HIT keeps exactly that shape, with a safer capture
  rule in front of it ("HIT, the design").

## The experiments, measured

Four passes, told in order, with what failed said as plainly as what worked.
Rounds one and two and the bold round were measured at `5e3f5f3e`, through an
output chain that still had TERRA's attack compressor; Phase 0 re-measured
everything that matters at `75b550c1` and is the current truth. Where a figure
moved, both are shown as BEFORE / AFTER.

### Round one: the ring works, and it is dull (140 clips in all, 67 of them TERRA struck)

The round-one spike captured a striker with `Fork.striker` (882 samples at
44.1 kHz), resampled it to 176.4 kHz as `Fork.excite` does, and rang it through
`Modes.ring` with each voice's own mode table after `Modes.atPosition`, then
TERRA's own chain (RINGED). Beside it, LAYERED swapped the striker in for
TERRA's exciter lambda, which is what a naive hook gives. Six strikers: BEATBOX
KICK, BEATBOX RIM, THUMP KICK, THUMP SNARE, WRAITH WORD and FORK's noise hammer
(struck-r1). DROOP was ignored in the ring; CLACK was unsupported.

- **Coupling is real, and layering has none.** LAYERED's body after 20 ms is
  flat to 0.00 dB across the six strikers on every voice; RINGED moves the
  overtone balance by 14 dB (membrane) to 46 dB (bar) depending on the striker
  (struck-r1).
- **The centroid is the wrong instrument.** The body centroid moved only 7 Hz
  on the membrane and 2 Hz on the cavity, because the ring's `1/sin θ` makes the
  fundamental dominate whatever strikes it; the band ratio (OB) is what
  separates coupling from layering. The claims test inherits this: it measures a
  band ratio, never the centroid (struck-r1; "Testing", test 2).
- **What failed: the ringed body was dull.** The membrane with THUMP KICK read
  −31 dB of overtones against −18 dB unstruck; the bar read −68 to −76 dB
  against −41, a near-pure sine for every striker but two (struck-r1). Round two
  found why: a missing per-mode tilt correction, not physics ("Round two").
- Tuning held (within 2 cents of unstruck on membrane, bell and bar, 9 cents on
  the cavity) and length is equal by construction (struck-r1).

The owner heard round one and said "they sound ok", and asked for experiments
to find what to tweak.

### Round two: why it was dull, and why the knob must live in the gain domain (111 clips)

Two sub-experiments, then a convergence spec, then an independent check.

**Brightness and coupling (struck-shape).**

- **S1, the dullness, found and fixed.** A two-pole resonator multiplies an
  impulse by `1/sin θ_k`, so the higher a mode the quieter it rings, roughly
  `1/ratio_k`. Scaling each mode's gain by `sin θ_k` cancels it exactly: an
  ideal impulse through the bank then reproduces today's additive modes to
  0.00 dB per mode (worst 0.09 dB across the four voices, waveform residual
  −66 to −82 dB). The round-one recipe had the impulse at −3.6 dB (bell), −6.1
  (membrane), −7.0 (cavity) and −15.9 dB (bar) of overtone balance against
  today (struck-shape S1). Round one's clips are therefore a biased reference,
  3.6 to 15.9 dB darker than a correct ring (struck-r2-spec §8.1).
- **S7A, what a ring leaves behind.** Once the head is over, ringing mode k
  with a striker leaves today's mode k scaled by the striker's magnitude at
  that mode, `A_k = |Σ_m x[m]·r_k^−m·e^(−jθ_k·m)|`; TERRA's own additive bank
  with gains `g_k·s·A_k` agrees with the ring to ≤ 0.31 dB per mode from 20 ms
  on, and to ≤ 0.03 dB of overtone balance on membrane, bell and bar (3.4 dB
  worst on the cavity, whose band-pass and tanh are phase- and
  droop-sensitive) (struck-shape S7A). That identity is HIT's whole idea.
- **After S1, too much coupling.** Over ten strikers (the six of round one plus
  four factory samples: kick, snare, closed hat, clap), the overtone-balance
  spread across strikers is 22.6 / 28.2 / 23.5 / 60.9 dB on membrane / cavity /
  bell / bar, and bass-heavy heads still starve the upper modes (2 / 2 / 5 / 6
  of 10 strikers below −3 dB, against round one's 7 / 6 / 7 / 9) (struck-shape
  §0.3).
- **What failed: adding the two bodies as signals is not a blend.** A
  "coupling amount" that mixes today's body and the ringed body as signals
  interferes (the two are uncorrelated in phase, Pearson −0.99 to +1.00), and
  the overtone balance is non-monotone in the amount for 6 to 9 of 10 strikers
  per voice — the membrane with THUMP KICK reads −19.6 dB at 0.75 between
  endpoints of 0 and −7.1 dB (struck-shape S6). Blending in the **gain domain**
  — TERRA's own bank, each mode's gain multiplied by `(1 − c) + c·s·|P_k(n)|` —
  is monotone in `c` for 10 of 10 strikers on membrane, bell and bar (9 of 10 on
  the cavity), keeps DROOP, the phases and the exciter as today, is
  bit-identical to TERRA at `c = 0` or with an impulse striker, and at `c = 0.5`
  keeps mean brightness within 1.6 dB on three voices (membrane −0.33, bell
  −1.12, bar −1.61 dB) and +3.0 dB on the cavity, with a striker spread of
  11.6 / 18.9 / 9.0 / 14.1 dB and the drum class unchanged in all 40 renders
  (struck-shape S6b). At `75b550c1` the cavity's count is 10 of 10 too (phase0
  §4.3).
- **What failed, caught in review: a non-causal first version.** The first
  gain-domain body applied the final `A_k` from sample 0; that inflated
  short-lived modes by tens of dB during the head (bell with WRAITH WORD: `A_6`
  64 dB above `A_1`) and crushed the level rule. The fix is the **running**
  magnitude `|P_k(n)|`, which grows causally while the striker plays and holds
  after it (struck-shape §7.4). Every gain-domain figure here is from the fixed
  version.

**Motion, head, cavity, cost and robustness (struck-motion).** Droop in a ring
needs a double-precision rotation retuned every sample (M1); a 20 ms head with
the house 2 ms raised-cosine fade beats 40 ms (M2); the cavity's tanh is
near-linear at today's drive (M3); CLACK and BUZZ compose as TERRA does, but
BUZZ's absolute threshold moves with the level (M4, M5); no cost blow-up (M6).
**M7 is the capture rule** HIT adopts: `Fork.striker` used raw has five hazards
— a silent source renders silence, 20 ms or more of lead silence renders
silence, one NaN or Inf renders an all-NaN clip (21609 non-finite samples), a
−90 dBFS noise lead-in is normalised into a noise hammer, and a non-44.1 kHz
source is resampled whole (61 ms for 3 s at 48 kHz) — and the rule (a silence
floor that falls back to today's body, onset alignment, sanitising, then
normalising) fixes all five; over 200 random strikers × random TERRA macros it
gave 0 non-finite renders and 0 silent renders, against 14 silent for the raw
capture, with 15 fallbacks (struck-motion M7; struck-r2-spec §8.6). One
cross-report correction: motion's recommended ring was run with the tilt on,
that is without S1, so its secondary numbers were re-measured before any
decision used them (struck-r2-spec §0).

**The candidates (struck-r2-spec §2).** **A, COLOURED**: TERRA's own bank,
each mode's gain multiplied per sample by `(1 − c) + c·s·|P_k(n)|`, the
striker's running magnitude read at the nominal pitch; **A-D**: the same with
the magnitude read along TERRA's drooping phase; **B, RUNG**: the striker rings
TERRA's own resonators, retuned by DROOP, S1 gain, a FORCE low-pass, TERRA's
click on top; **C**: B with a 562 Hz first-difference edge on bell and bar.

**The independent check (verify-r2 §3), membrane, OB from 20 ms, dB:**

| | THUMP KICK | WRAITH WORD | THUMP SNARE | spread | mean vs unstruck |
|---|---|---|---|---|---|
| unstruck | −18.1 | −18.1 | −18.1 | 0 | 0 |
| round one | −31.2 | −17.3 | −19.2 | 13.9 | −4.4 |
| COLOURED 50 % | −21.1 (−3.0) | −14.6 (+3.5) | −15.2 (+2.9) | 6.5 | +1.1 |
| COLOURED 100 % | −25.2 (−7.1) | −11.2 (+6.9) | −13.2 (+4.9) | 14.0 | +1.6 |
| RUNG | −18.7 (−0.6) | −8.6 (+9.5) | −26.3 (−8.2) | 17.7 | +0.3 |

COLOURED 50 % keeps today's brightness on the membrane (every striker within
about 3.5 dB, 6.5 dB spread) while the striker still changes the body (mode 3
differs 8.8 dB between kick and wraith); RUNG is right on average only because
its errors cancel, and moves the attack (time to peak 7.9 ms with the kick,
0.2 ms unstruck); COLOURED 100 % overshoots and turns the wraith-struck
membrane from TOM to PERC; on the cavity COLOURED 50 % moves −6.8 dB (kick) and
+8.1 dB (wraith) (verify-r2 §3).

**What failed: A-D missed its check.** Round two's check G-A4 asked A-D at full strength to
match RUNG's per-mode levels within 1 dB on the membrane; its overtone balance
matched to 0.00 dB but the per-mode levels missed by a near-uniform offset —
1.5 to 4.6 dB as the design brief recorded it, 1.9–4.3 dB in the independent
check (verify-r2 §2), 1.86 / 4.58 / 2.13 dB over the three strikers in round
two's check log, and **1.85 / 3.47 / 2.33 dB (1.9–3.5) at `75b550c1`**
(phase0 §3, G-A4). Its five clips were dropped from the page (verify-r2 §2);
they are kept as `r2-work/held-back-a100d/`, the first thing to hear if the
owner hears HIT at 1 as weak ("Phasing and gates", Phase 0).

**What failed: the page itself.** 111 clips (55 struck, 56 hybrid) was too many;
the owner gave up on it. A nine-clip "start here" replaced it: today's membrane,
COLOURED 50 % with THUMP KICK and with WRAITH WORD ("subtle"), RUNG with the
same two ("strong"), and four hybrid clips. From it came Q1: "**A knob between
them**" (today → subtle → strong) — which is HIT. That page is also why every
gate below leads with at most ten clips and at most three questions, the
owner's standing preference.

### The bold round: kick bends drum, and a talking drum (9 clips, 3 families)

Asked how far to go, the owner said "I think we could go further/bolder". Two of
the bold round's nine clips are this document's BEND and TALK.

- **Kick bends drum** (bold-steer clip 1). TERRA's membrane with DROOP replaced
  by THUMP KICK's own pitch curve, `1 + (sweepMult − 1)·e^(−bendRate·t)`
  (`Thump.kt:188, :195, :219`), a two-octave dive (4.0 at the hit, 1.541 at
  50 ms, 1.003 at 200 ms), with the membrane an octave over the kick's settled
  note and the kick's first 15 ms added after TERRA's chain as the attack. The
  copied loop with the droop curve equals `Terra.render` (bold-steer's check G1); the copied
  curve matched a real `Thump.render(KICK)` by zero-crossing period to a worst
  relative error of 0.0051 over the first 50 ms (phase0 §4.8; bold-steer G4 printed 0.005); the measured pitch track
  ran 188/181, 123/121, 100/99, 92/92, 89/89, 88/88 Hz (measured / expected) at
  30 to 180 ms; the nearest thing in the app (323 candidates) scored NCC 0.377,
  a 100 ms splice of a slow-bending THUMP TOM over the kick (bold-steer).
- **A talking drum** (bold-talk clip 3, `TalkDrum`). TERRA's membrane whose
  per-mode levels follow the vowel path of FIVE and whose pitch glides with its
  intonation: each mode's gain is the vocal tract's magnitude response at the
  mode's current, bent frequency, normalised to the loudest mode, floored at
  −26 dB, updated every 32 samples at 176.4 kHz with 1 ms smoothing; pitch is
  droop × `2^(semis/12)`, VOX SPEAK's own statement contour scaled so the fall
  is a fifth. Measured: pitch error median 5 cents, worst −14 cents (at 150 ms);
  per-mode tracking r 0.99, 0.98, 1.00, 1.00, 1.00, 1.00 for modes 1–6; vowel
  weight swing 13, 9, 25, 28, 33, 36 dB; centroid 708, 567, 322, 287 Hz at 60,
  120, 200, 300 ms against plain TERRA's 270, 259, 252, 249; ablation NCC 0.856
  against glide-only and 0.087 against vowel-only, so the glide is the bigger
  waveform change and the vowel the brightness swell; nearest in the app (162
  candidates) NCC 0.166, the rack's SPEED on plain TERRA (bold-talk §4). Two
  spike-only deviations: every mode's t60 × 1.6 and the membrane's damping step
  0.65 → 0.30, with a 1.4 s render, because with TERRA's own decays the upper
  modes are 30–40 dB down before the vowel has moved (bold-talk §4). The built
  TALK keeps both as its ring ("TALK, the design").

The owner's answer: "**keep Steer, Ring and Talk**".

### Phase 0: the same numbers on today's tree (75b550c1)

`a6dcb87e` removed TERRA's attack compressor; nothing else in `Terra.kt`
changed between the spikes' tree and `75b550c1` (`git diff --stat`: 11
insertions, 85 deletions, all of it the revert). Phase 0 rebased every copied
chain onto one switch (compressor on = the old chain, off = today's), so every
BEFORE/AFTER difference is the revert and nothing else (phase0 §2.1–§2.2).

- **The harness is round two's.** With the old chain it reproduces all 43
  distinct round-two renders byte for byte as 16-bit files, 13 of 15
  struck-shape section files to the digit, and both bold clips byte for byte
  (phase0 §1, §2.3).
- **The numeric checks pass at `75b550c1`.** A prototype of this document's change,
  `P0Bank` (`Terra.strikeAndModalBank` with the CLACK onset kept and the two
  optional inputs, both indexed from the strike, the skip guard reading the
  table gain only, the pitch multiplier clamped to 0.25–4), with no inputs
  equals `Terra.render` bit for bit for the four voices at defaults, 20 macro
  probes and all 16 `TerraKits.classic()` pads, BUZZ pads and the CLACK pad
  included: 40 cases (G-P0a). Identity curves (exactly 1f) alone and together:
  120 renders, bit-identical (G-P0b/c). HIT at `c = 0` with any of three heads:
  120 renders, bit-identical (G-P0d). HIT with an impulse at 0.5 and 1: 80
  renders, bit-identical (G-P0e). HIT 1 with THUMP KICK on the 40 cases: same
  length, all finite, peak ≤ 1 (G-P0f). The prototype's HIT equals round two's
  `renderA` bit for bit over 4 voices × 10 strikers × 2 strengths (G-P0h). The
  capture rule's head equals `Fork.striker` on the 13 measured strikers (round
  two's three and struck-shape's ten, G-C1) (phase0 §3).
- **HIT 0.5 did not move.** Every overtone-balance statistic over the ten
  strikers is unchanged to 0.02 dB on all four voices (the cavity's spread
  +0.25 dB); time to peak, first-5-ms peak, f0 and class are unchanged; crest
  factor is 0.02–0.10 lower (phase0 §1, T11).
- **HIT 1 moved only on the cavity's floor rows and in its attack.** Membrane,
  bell and bar statistics are unchanged to 0.02 dB; the cavity's mean fell
  1.1 dB and its spread rose 5.6 dB, because kick-type rows sit on an
  overtone floor that itself fell (phase0 T11).

The figures that matter, BEFORE (round two as published) / AFTER (`75b550c1`):

| figure | BEFORE | AFTER | tag |
|---|---|---|---|
| membrane, HIT 0.5, OB-rel mean / min / max / spread over ten strikers | −0.33 / −8.08 / +3.48 / 11.56 dB | the same | phase0 T11 |
| membrane, HIT 1, the same | −0.67 / −15.70 / +6.87 / 22.57 dB | the same | phase0 T11 |
| cavity, HIT 0.5, the same | +3.04 / −6.76 / +12.13 / 18.89 dB | +2.99 / −7.01 / +12.13 / 19.15 dB | phase0 T11 |
| cavity, HIT 1, the same | +7.46 / −9.03 / +24.50 / 33.53 dB | +6.34 / −14.58 / +24.50 / 39.08 dB | phase0 T11 |
| bell, HIT 0.5 / HIT 1, spread | 8.96 / 23.46 dB | the same | phase0 T11 |
| bar, HIT 0.5 / HIT 1, spread | 14.08 / 60.88 dB | 14.08 / 60.89 dB | phase0 T11 |
| membrane, the three round-two heads, HIT 0.5: kick / wraith / snare vs unstruck | −3.0 / +3.5 / +2.9 dB, spread 6.4 (6.5 in the 16-bit files) | the same | phase0 §4.6; verify-r2 |
| cavity overtone floor (a single decaying sine through the chain) | −49.56 dB | **−55.34 dB** | phase0 T3 |
| first-5-ms peak over clip peak, mean, HIT 1: membrane / cavity | −1.63 / −1.08 dB | **−2.22 / −1.85 dB** | phase0 T12 |
| first-5-ms peak, HIT 0.5, every voice | 0.00 dB | 0.00 dB | phase0 T12 |
| membrane with THUMP KICK at HIT 1, time to peak | 13.6 ms | **14.0 ms** (today's drum 0.2 ms) | phase0 §5 item 3, T6 |
| A-D's per-mode miss against RUNG (G-A4) | 1.86 / 4.58 / 2.13 dB | **1.85 / 3.47 / 2.33 dB** | phase0 §3 |
| RUNG's per-mode impulse figure on the cavity (G-B1, band-passed-peak level) | 0.0930 dB | 0.0951 dB, over the 0.06 target, not widened | phase0 §4.1 |
| HIT's per-mode impulse figure, every voice | exactly 0 (bit-identical) | exactly 0 | phase0 §4.1 |
| kick-bends-drum and the talking drum: gates, pitch tracks, per-mode tracking, nearest NCC 0.377 / 0.166 | as published | the same | phase0 §4.8 |
| cost: HIT against an unstruck render, capture work counted | 1.34–1.49× (struck-shape's harness) | 1.4–1.7×; neutral path 0.98–1.05× | phase0 §4.7 |

Four things follow, and the design below carries each one.

1. **The cavity's "no measurable overtones" line is −55.3 dB absolute** (17.5 dB
   under the unstruck cavity's −37.8), not −49.6. A test that pins a dark-striker
   row on the cavity pins it against the new floor (phase0 §5 item 1).
2. **HIT 1 is not "today's attack plus colour" for a bass head.** A kick head
   leaves almost only the fundamental, so its 3–12 ms click is smaller than the
   fundamental's build-up: the membrane with THUMP KICK peaks at 14.0 ms (first
   5 ms at 0.51 of the clip peak), with WRAITH WORD at 0.66 ms. HIT 0.5 keeps
   today's attack on every voice (first-5-ms peak 1.000); any claim about HIT's
   attack names the strength (phase0 §5 item 3).
3. **The level match does not keep BUZZ as today.** HIT's level match `s`
   matches the body's peak, not the 75 Hz band-passed level the cavity's tanh
   sees nor the absolute 0.12 threshold BUZZ gates on. Over the ten strikers,
   BUZZ's time above threshold at BUZZ 1 runs 0.47–1.14 times today's at HIT 0.5
   and 0.05–1.25 at HIT 1 (bar 0.50–1.14 and 0.05–1.24); the cavity's tanh input
   runs 0.56–1.06 times today's at HIT 0.5 and 0.19–1.16 at HIT 1, but never
   above 0.357, where `tanh(x)/x` is 0.960, so the cavity stage stays within
   4 % of linear for every striker (phase0 §4.7, §5 item 4). The brief disagrees
   with itself here: its algorithm line has `s` match the peak of today's body,
   and its drive line says that level match keeps BUZZ's and the cavity's drive
   as today, with the claims test "BUZZ/CAVITY drive unchanged at the level
   match" following the drive line. Both cannot hold, and the test cannot pass
   as written for BUZZ. This is the one conflict, escalated to the owner and not
   reworded ("Where this document departs from the brief", row 1; "Decisions
   for the owner", 2; "Testing", test 6).
4. **The per-mode impulse target (0.06 dB) is RUNG's problem, not HIT's, and
   not a departure from the brief.** The target is round two's own; the brief
   states no such figure, and RUNG is not adopted. HIT
   with an impulse is bit-identical, so its figure is exactly 0. RUNG misses on
   the cavity's 4th mode (0.0951 dB) under round two's band-passed level rule and
   passes by 0.003 dB (0.0573) under the plain-peak rule; round two passed it by
   widening the tolerance to 0.10, and Phase 0 does not (phase0 §4.1, §5 item 5).
   Nothing in this design depends on RUNG.

The zero-gain hazard was measured too. With the skip guard reading the table
gain, a level curve held at exactly 0 for 20–60 ms leaves the output exactly 0
inside the window and identical to the no-curve bank after it (largest
difference 0). Gating the skip on `gain × level` instead, before the phase
accumulates, differs from the right version by up to 0.33 after reopening (bank
peak 0.82), and by 0.47 with a smooth 5 ms close and reopen — a **phase error**
in the reopened mode, not a click: the largest first difference is 0.0004
against 0.0003 (hard window) and 0.0015 against 0.0016 (smooth). The test pins
the waveform after reopening, not a step (phase0 §3, §5 item 6).

### What failed, said plainly

| What | Measured | What it changed |
|---|---|---|
| Round one's ring was dull | membrane −31 dB OB with THUMP KICK against −18; bar −68 to −76 against −41 (struck-r1) | the missing per-mode tilt correction, found in round two (struck-shape S1); the knob moved off the ring entirely |
| A "coupling amount" that adds two bodies as signals | non-monotone in the amount for 6–9 of 10 strikers per voice (struck-shape S6) | HIT lives in the gain domain, never the signal domain |
| The first gain-domain body | non-causal: short modes inflated by tens of dB in the head (struck-shape §7.4) | HIT uses the running magnitude of `P_k(n)` |
| A-D (read at the drooping pitch) | missed its per-mode gate by 1.9–3.5 dB at `75b550c1` (phase0 G-A4) | kept out of R1; first fix candidate if HIT 1 is heard as weak |
| RUNG's impulse equivalence on the cavity | 0.0951 dB against a 0.06 target (phase0 G-B1) | RUNG is not adopted; no tolerance is widened |
| The round-two page | 111 clips; the owner gave up | every gate: ≤ 10 clips, ≤ 3 questions |
| The brief's "level match keeps BUZZ and CAVITY drive" | BUZZ 0.05–1.25× today at HIT 1 (phase0 §4.7) | an owner decision with a default; test 6 is printed, not pinned, until R1's page answers question 3, and then pins the measured per-striker figures |
| The round-one clips as a reference | 3.6–15.9 dB darker than a correct ring (struck-r2-spec §8.1) | never used as "today" |

### Where this document departs from the brief

The brief is the single source of truth and this document keeps every decision
in it; where a sentence of the brief paraphrases a clip the owner kept by ear
(row 6), the clip is the decision. Seven places say something the brief does not
say, or say it more narrowly once Phase 0 or a check of the code had measured
it. Only the first is a conflict (two lines of the brief that cannot both hold);
the rest are restatements, listed so the owner can see which figures and rules
were not in the brief that was approved.

| # | The brief says | What was measured or found | What this document does |
|---|---|---|---|
| 1 | HIT's algorithm: "s matches the peak of today's body". HIT's level match "keeps their [BUZZ's and CAVITY's] drive as today. Test it." The claims test: "BUZZ/CAVITY drive unchanged at the level match" | A peak match leaves the cavity's tanh within 4 % of linear but moves BUZZ's time above its threshold to 0.05–1.25 × today's at HIT 1 (phase0 §4.7). The two lines cannot both hold | **The one conflict.** Keeps the algorithm line (a peak match, the behaviour that was measured) and breaks the drive line for BUZZ; the default is **not owner-approved** and is decided at R1's page, question 3, before test 6 is pinned (decision 2) |
| 2 | "tuning stays within 2 cents", for every voice | Membrane, bell and bar read 0.00 cents at HIT 0.5. The cavity's final render reads up to 2.11 cents at 0.5 and 17.37 at 1, because the fixed 75 Hz stage and a one-peak estimator move the reading while the bank is exact (struck-r2-spec §8.9; phase0 T4) | The 2-cent claim is asserted on the cavity's bank before the cavity stage, and the final-render reading is printed (test 2) |
| 3 | "the three-clip HIT-at-1 listening check" | Two strikers and two recipes need a pair each, and the heard RUNG clip has to be reproduced and rebuilt (phase0 §6) | Six clips were built, inside the ten-clip, three-question limit, and are **proposed, pending the owner's OK**; the default is the brief's three (`cm_unstruck`, `cm_hit1_wraith`, `cm_rung_wraith_heard`), which can ask question 1 only (decision 21; "Phasing and gates") |
| 4 | A-D "missed its per-mode gate by 1.5–4.6 dB" | 1.85 / 3.47 / 2.33 dB, 1.9–3.5 at `75b550c1` (phase0 §3, G-A4) | Cites 1.9–3.5 dB; A-D stays out of R1 |
| 5 | "~20–30 KB per driven pad (FORK's striker costs the same)" | Arithmetic from the JSON writer, inferred and not measured: about 31 KB for a striker, 2 KB for BEND, 13 KB for TALK, so about 46 KB with all three | Says "about 31 KB, inferred" for a struck pad and gives the three-driver figure ("Data flow and compatibility") |
| 6 | "under TALK the modes ring about 1.6× longer" | The heard clip stepped the membrane's damping 0.65 → 0.30 as well and rendered a fixed 1.4 s, so its modes ring 1.6, 2.03, 2.30, 2.48, 2.62 and 2.72 × as long as TERRA's own, and modes 2–6 1.3–1.7 × as long as t60 × 1.6 alone gives (bold-talk §4) | **Now follows the heard clip.** Default: the per-mode ratio 1.6·(1 + 0.65k)/(1 + 0.30k) on each t60, and a render 10/9 as long as TERRA's own (1.4 s at DECAY 1, the clip's). The brief's "about 1.6×" is read as a paraphrase of the clip the owner approved by ear (its mode-1 figure), not a separate decision; the brief read literally, a uniform t60 × 1.6 and a render 1.6 × as long (about 2.0 s), is decision 7's alternative and rings modes 2–6 21–41 % shorter than the clip |
| 7 | "KEEP is undoable (bin-backed `replaceAudio`)" | The bin brings the audio back; every undo door in the house also clears the recipe, and the treatment card's UNDO does not fire on a synth-patch recipe at all | States what undo does to the recipe (the group's own UNDO clears it; the takes history restores it) and asks the owner whether UNDO should restore the previous recipe (decision 17) |

## Against the fleet

| Engine or part | Why the TERRA hook is not it | What it lends |
|---|---|---|
| **FORK's striker** (`ForkPatch.striker`, `Fork.striker`, `Fork.excite`) | FORK rings its striker through resonators, so a hit is a signal into the bank (`Fork.kt:491`); there is no per-mode number to turn, and a "how much" knob would be the signal blend round two rejected (struck-shape S6) | **The storage precedent, verbatim**: a captured hit kept as 882 numbers in the patch, fixed length, finite-checked in `init` and on decode, written only when present, compared by content, carried by `copy` (`Patches.kt:426-504`; `Fork.kt:349-350`). The 20 ms head and its 2 ms raised-cosine fade (`Fork.kt:353, :859-870`). And FORK's door: STRIKE FROM ▸, never built, whose "the picker is GRAINS'" premise FORK's own spec already marked stale (`2026-09-27-fork-electric-piano-engine-design.md:830-838`); it rides along in R4 |
| **MUTATE** (SPLICE, STACK, MORPH, TRANSPLANT) | MUTATE bakes one pad from two finished clips; after 20 ms a splice or a stack *is* one parent. The CHIMERA voices that survived as post-render math equal MUTATE or TERRA alone from 20 ms on: NCC 1.0000 against MUTATE's 10 ms splice and against TERRA alone, 0.9993 against TIDE alone (hybrid-r2-spec). Nothing in MUTATE moves a body's mode balance or its pitch | **The chooser**: `MutateSheet.Partner` (a pad on this kit, a deal, a room, a pad on another kit, a file), `partners` (every other pad, never the pad itself) and `source` (the pad's WAV) (`MutateSheet.kt:95, :98-113, :210-226`), lifted into one shared chooser in R4; ▶ HEAR == KEEP (`MutateSheetTest.kt:93`) |
| **BODY** (the keyed `bodied` treatment, `Body.ring`) | Chord-tone resonators rung by the finished pad (`Keyed.kt:43, :103`); the pad excites a fixed bank, the bank does not belong to another engine, and nothing about a second pad's hit reaches it | Nothing; it stays untouched (Group A keeps its bytes with a frozen guard) |
| **The rack** (RING, SPEED, MOTION, CONTOUR) | Unary, Snip in and Snip out. RING multiplies by a sine, not by a second clip (verify-code on fleet F1); SPEED is a static pitch shift and MOTION a fall over the end of the sound, so the app's best answer to "kick bends drum" was NCC 0.377 and to "a talking drum" 0.166 (bold-steer; bold-talk §4) | The rack still runs after a driven TERRA pad, unchanged (`PadRecipe(patch, fx)`) |
| **VOX SPEAK** | A voice, not a drum: a glottal pulse through a formant tract (`VoxSpeak.kt:409`). It says the word; it cannot make a struck membrane say it | TALK's data: the eight words' formant paths and intonation (`VoxSpeak.kt:49, :105, :353-407, :462-492`), extracted once behind a frozen guard (shared with Group A's SAY) and **baked** into the TERRA pad, so a saved TALK pad never moves when VOX SPEAK is retuned |
| **THUMP's kick** | A kick's own pitch dive bends only the kick | BEND's test reference: the analytic curve `base·(1 + (sweepMult − 1)·e^(−bendRate·t))` (`Thump.kt:188, :195, :219`), against which the pitch-track capture is measured |
| **TERRA itself** (DROOP, CLACK, CAVITY, BUZZ) | DROOP is one fixed curve (20 ms, `Terra.kt:268`) and TERRA's exciter never reaches its modes (`Terra.kt:381, :405`) | **The hook**: one additive bank shared by all four voices (`Terra.kt:367-408`), whose per-mode gain (`:400`) and pitch (`:390`) are numbers in one loop, and whose phase accumulator (`:397`) makes a moving pitch click-free |

**Why TERRA, in one sentence.** Of the fleet's struck bodies, TERRA's is the one
built as decaying sines whose per-mode level and pitch are numbers per sample
(the others ring resonators), so "another pad's
hit sets how hard each mode rings" is a multiply, not a second synthesis path —
which is why HIT is bit-identical to TERRA at 0 and with an impulse, keeps DROOP,
CLACK, BUZZ and CAVITY natively, and costs 1.4–1.7× an unstruck render (phase0
§4.7). The same two numbers carry BEND and TALK.

## The name

**HIT, BEND and TALK**, the drivers; **STRUCK BY**, **BENT BY** and **TALKS**,
the three rows of the pad-sheet group. All three are owner-approved.

- **HIT, not STRIKE.** STRIKE already means hammer hardness on FORK (the macro
  `Velocity.kt:252` registers as FORK's brightness override), and TERRA renamed
  its own strike-position macro from STRIKE to POS for exactly that reason,
  which the code records at `Terra.kt:55-60`. The rack spec says that "two
  unrelated meanings of one word on the same screen is the problem that ruled
  out `HIT`" (`2026-09-13-fx-rack-expansion-design.md:163-165`).
- **Two prior uses of HIT, checked, and why neither binds.** VOX's BEATBOX voice
  has a macro named HIT, a selector of which beatbox hit plays (`Vox.kt:164,
  :197`); and the rack spec rejected HIT as a *rack section's* name because "this
  codebase says 'the hit' in nearly every KDoc to mean the sample itself"
  (`2026-09-13-fx-rack-expansion-design.md:155-156`). TERRA's HIT is not a
  macro: it lives inside the STRUCK BY row of a TERRA pad's sheet and inside the
  stored striker ("Data flow and compatibility"), so it never appears on the
  SYNTH panel beside BEATBOX's HIT, and it means exactly the KDoc sense — how
  much of the other pad's hit strikes this drum.
- **STRIKE FROM on a FORK pad's sheet is not that collision.** It is the name
  FORK's own spec gave the door (`2026-09-27-fork-electric-piano-engine-design.md:830-838`)
  and the approved brief's, it is a row label on the pad sheet and not a macro,
  and the pad sheet draws no synth macros (R4's group is its first synth-patch
  door), so FORK's STRIKE hardness macro, which lives on the SYNTH panel, never
  shares a screen with it. Both words are about one act, the hammer's strike
  (how hard, and by which hit), so they are related meanings and not "two
  unrelated meanings". The same act is called STRUCK BY on a TERRA pad, where
  no STRIKE macro exists (it was renamed POS). FORK's spec placed STRIKE FROM ▸ on
  the SYNTH screen, reusing a source chooser there
  (`2026-09-27-fork-electric-piano-engine-design.md:155-156`); beside the STRIKE
  macro it would be the collision, which is one more reason it rides on the pad
  sheet here (decision 14).
- **BEND** sits beside THUMP's and TINES' BEND macros (`Thump.kt:44, :73`;
  `Tines.kt:137`), which set how fast a pitch falls: the same meaning, a pitch
  bend, so one word keeps one meaning. **TALK** has no macro or chip of that
  name anywhere in the tree (`grep '"TALK'` over `synth/`, `shell/`, `app/`).
  The word list in the TALKS row is VOX SPEAK's own, ONE to EIGHT
  (`VoxSpeak.kt:49`).
- **"Sound yes, names never"** (`docs/SYNTH_ROADMAP.md:27`): no product or maker
  name anywhere — not in a label, a preset, a test name or a commit message.
- Phone labels and refusals follow the Copy laws (`Copy`, `Personality.kt:15`,
  checked by `PersonalityTest`): every Copy constant shouts, the full-stop and
  ceiling laws hold, and jokes never gate function; `:shell` refusals stay
  lowercase, CLI-worded `require` messages (critic).

## Architecture

```
CAPTURE — once, when a pad or a word is picked (public :synth entry points, called from :shell)
  STRUCK BY  pad ─▶ safe capture ──────────▶ striker = head[882] @ 44.1 kHz  +  HIT c ∈ 0..1  (+ from label)
  BENT BY    pad ─▶ pitch-track capture ───▶ bend    = mult[64] on a fixed time grid  (+ from label)
  TALKS      word ─▶ formant track, baked ─▶ talk    = 64 frames × (f1 f2 f3 nasal damp semis) + seconds
                                         └─▶ TerraPatch(name, voice, macros, striker?, bend?, talk?)   ← kit.json, as data

RENDER — every time (Terra.kt), at 176.4 kHz
  Terra.render(voice, macros, drivers)                       drivers == null  →  today's code path, byte for byte
    voice function ─▶ modes after Modes.atPosition, f0, droop depth, frames, onset
    strikeAndModalBank(modes, f0, depth, frames, rate, exciter, onset, level?, pitch?)
      for i ≥ onset:  t = (i − onset) / rate,  n = i − onset
        currentF0 = f0 · (1 + depth · e^(−t/0.020)) · pitch(t)        pitch(t) = clamp(bend(t) · 2^(semis(t)/12), 0.25, 4)
        per mode k:   skip only on hz ≤ 0, hz ≥ nyquist, t60 ≤ 0, gain == 0   ← the TABLE gain, never the curve
                      phase_k += 2π · hz / rate
                      modalSum += sin(phase_k) · (gain_k · level_k(n)) · decay_k
                      level_k(n) = G_k(n) [HIT] · V_k(n) [TALK]               each factor absent ⇒ no multiply
      out[i] = 0.35 · exciter(i) + 0.65 · modalSum
    voice tail: CAVITY's fixed 75 Hz band-pass → tanh (cavity), BUZZ (cavity, bar)
    Dsp.normalize → Punch.applyOversampled(1.0) → Dsp.limitPeak → Dsp.fadeTail       unchanged
```

**One change, inside one function.** All four voices call
`Terra.strikeAndModalBank` (`Terra.kt:367-408`: the membrane at `:458`, the
cavity at `:493`, the bell at `:555`, the bar at `:590`). It gains two optional
inputs, threaded through the four voice functions and a `render` overload:

- a **per-mode level curve**, a factor on `mode.gain` at the gain term
  (`Terra.kt:400`);
- a **pitch curve**, a factor on the droop line (`Terra.kt:390`).

With neither, TERRA renders exactly as today. The null path is a separate
branch that executes today's two expressions verbatim — no multiply at all —
because that is the only form that is byte-identical by construction (map
terra-hook §6). With an input, the expressions are
`fundamentalHz * (1f + droopDepth * exp(-t / DROOP_TAU_SECONDS)) * pitch` (left
to right, so an exact 1f is a no-op) and
`sin(phases[k]) * (mode.gain * level) * decay` — the association round two's
`gainedBank` and Phase 0's `P0Bank` used, which is what makes the prototype's
HIT equal round two's `renderA` bit for bit (phase0 G-P0h). `t` stays a float
and the droop factor is not folded into a precomputed array, because either
change moves the rounding (map terra-hook §6).

**The skip guard reads the table gain only.** The `continue` at `Terra.kt:396`
runs *before* the phase accumulates at `:397`. A level curve must never route
through it: a mode whose level is 0 for a while still advances its phase, so
when it reopens it is where it would have been. Gating on `gain × level` gives
a phase error of up to 0.33 of a 0.82 peak after reopening (phase0 §3). The
pitch multiplier is clamped to 0.25–4 before it reaches the guard, so
`hz <= 0` cannot come from a downward curve; NaN passes `coerceIn`, which is
why every stored curve is finite-checked in `init` and on decode
("Data flow and compatibility"); 0, −3, 1e9 and 1e−9 all render finite once
clamped (phase0 §3). A mode pushed past Nyquist (88.2 kHz) is still skipped as
today; at the clamp's top the membrane's top mode at TUNE 1 reaches 440 × 5.92 ×
4 × 1.65 ≈ 17 kHz, far under it (arithmetic from `Terra.kt:167, :431, :450`).

**The clock is the strike.** Both curves are indexed from `i − onsetSamples`,
the clock DROOP and the per-mode decay already use (`Terra.kt:384`). Only the
CLACKed bell has a non-zero onset (`Terra.kt:536, :552-555`), and indexing from
it keeps the CLACK pre-click landing first and silent in the bank underneath
(`Terra.kt:360-366`). The spikes indexed from frame 0 and silently ignored
CLACK (map terra-hook §2.5); Phase 0's prototype indexes from the onset and the
CLACK pad A15 passes every gate (phase0 §3). Measured on the bell at CLACK 1,
the pre-roll's peak over the body's is 5.9 % today, 7.6 / 6.9 % at HIT 0.5 and
16.5 / 15.1 % at HIT 1 (THUMP KICK / THUMP SNARE), well under the 50 % ceiling
`Terra.kt:250-257` holds it to (phase0 §4.7).

**The pitch curve composes with DROOP.** It multiplies the droop line; it does
not replace it (owner-approved). With DROOP at 0 compose and replace are the
same, and the bold kick-bends-drum clip through the prototype's pitch input is
bit-identical to the spike's bank before the chain (phase0 §4.8). A user who
wants the kick's dive alone sets DROOP to 0; a user who leaves DROOP on gets the
head's own sag on top of the borrowed dive. On the bell and the bar the droop
depth is 0 (`Terra.kt:555, :590`), so a pitch curve there owns f0 outright —
which is why BEND and TALK start on the two drooping voices.

**The cavity stays at 75 Hz.** RESONANT_CAVITY's Helmholtz stage is a fixed
75 Hz band-pass, Q 8, into `tanh(x · 1.15)`, crossfaded by CAVITY
(`Terra.kt:191-193, :495-504`). It does not follow a pitch curve: a drum bent
away from 75 Hz couples less, one bent toward it couples more. The owner
approved keeping it fixed, as part of the cavity's character.

**The level match `s`, CAVITY and BUZZ.** The cavity's tanh and BUZZ's absolute
0.12 threshold (`Terra.kt:199, :417-428`) act on the *un-normalised* bank,
before `Dsp.normalize` (`Terra.kt:132`), so anything that changes the body's
absolute level changes how hard they are driven (map terra-hook §3). HIT's level
match `s` scales the coloured body to the **peak** of today's body for the same
macros (the brief's algorithm line). The brief's drive line asks for more: that
the match keeps the overall drive into both stages as it was tuned. Phase 0
measured that a peak match does not deliver that: `s` matches the peak, not the
75 Hz band-passed level the tanh sees or the time spent above 0.12 ("The
experiments, measured", point 3). The cavity's tanh stays within 4 % of linear
for every striker, so its sound does not change through the saturator; BUZZ's
rattle follows the striker (0.05–1.25 times today's time above threshold at
HIT 1). So the brief's algorithm line is kept and its drive line is broken for
BUZZ, which is the one conflict ("Where this document departs from the brief",
row 1). Whether BUZZ follows the striker (this document's default, the measured
behaviour, **not owner-approved**) or is held near today's by a band-passed
level match on the cavity (the drive line's intent, estimated and not
measured) is the owner's decision 2, answered at R1's page before test 6 is
pinned. TALK carries its own
normalisation (the loudest mode keeps the unweighted loudest mode's level) and
BEND does not change level at all.

**Render rate.** Everything above runs at `RATE · Dsp.OVERSAMPLE`, 176.4 kHz
(`Terra.kt:119`; `Dsp.kt:21, :33`). Stored curves are control-rate data, never
176.4 kHz samples: HIT's head is stored at 44.1 kHz and resampled once at render
(3528 samples for 20 ms, as `Fork.excite` does); BEND and TALK are 64-point
grids read with interpolation.

**The neutral guard comes first.** TERRA has no frozen-render test today:
`render is bit-for-bit deterministic` (`TerraTest.kt:14-20`) compares two runs
of the same code, and `every pad's recipe regenerates its own audio`
(`TerraKitsTest.kt:53-64`) compares the kit with a recipe render through the
same code; neither can see a change, and TERRA is in neither `DeterminismTest`
nor `PadRecipeTest` (tree check; map terra-hook §6). R1 lands, before any other
TERRA change, a **frozen byte-exact guard**: `LegacyTerraBank.kt`, a verbatim
copy of `Terra.render`, its four voice functions, `strikeAndModalBank`,
`applyBuzz` and the cavity stage as they are at `75b550c1`, in test sources,
and a test that `Terra.render` equals it with `assertContentEquals` over a
printed grid — the 16 `TerraKits.classic()` pads plus the four voices at
defaults and at 20 macro probes, the 40 cases Phase 0 ran (phase0 G-P0a), with
`assertEquals(40, cases)` in `StringsTest`'s manner (`StringsTest.kt:22-41`,
against `LegacyPluckLoop`). It passes against the unchanged source, which is
the proof it guards something.

**Cost.** HIT renders at 1.4–1.7 times an unstruck render with the capture work
counted; 3–4 ms of that (the 1.5 ms resample, the running magnitudes and `s`)
is a function of the head and the receiver's tuning, so it can be cached per
patch; the neutral path costs 0.98–1.05 times (phase0 §4.7). The talking-drum
spike rendered at 2.1 times `Terra.render` (58.0 against 27.2 ms) and the
kick-bends-drum at 1.0 times (phase0 §4.8). Nothing here is a render-time risk.

## HIT, the design

**What it is.** The other pad's first 20 ms sets how hard each of the drum's
modes rings. The owner's knob: **HIT 0 = today, 0.5 = subtle, 1 = strong.**

**The algorithm: round two's COLOURED candidate A, in the gain domain on
TERRA's own additive bank** (struck-r2-spec §2 A; struck-shape S6b). At render,
from the stored head and the receiving pad's own modes (after
`Modes.atPosition`, so POS's static weights stay in `mode.gain`, `Terra.kt:456,
:491, :546, :587`):

```
x        = Resampler.resample(Snip(head, 1, 44100), RR).samples         3528 samples, placed at the onset
M        = index of the last non-zero x, plus 1
θ_k      = 2π · f0 · ratio_k / RR                                       the nominal pitch, droop ignored
r_k      = exp(−6.9078 / (t60_k · RR))                                  double precision
P_k(n)   = Σ_{m=0..n} x[m] · r_k^(−m) · e^(−j·θ_k·m)                    running projection, double; constant for n ≥ M
refPeak  = peak of today's 0.65 · modalSum over the first 3·RR/f0 + 16 samples
body_s   = TERRA's bank (droop, phases) with gains g_k · |P_k(n)|, no exciter,
           over the first max(3·RR/f0, M) + 64 samples
s        = refPeak / peak(body_s)                                       the level match
G_k(n)   = (1 − c) + c · s · |P_k(n)|                                   c = HIT
bank     : modalSum += sin(phase_k) · (g_k · G_k(n)) · decay_k
```

- **Bit-identical at `c = 0`**: `(1 − 0) + 0 · s · |P|` is exactly 1f for any
  finite `s · |P|`; the build treats `c = 0` as the null path outright and
  computes `s` only when `c > 0`. If `s` is not finite (a coloured body whose
  peak is 0), the striker renders as absent — today's body. **Bit-identical with an impulse** at `c = 0.5` and `c = 1`:
  `|P_k(n)| = 1` for every mode and `s = 1`, so `G` is exactly 1f; Phase 0
  proved both on 40 cases each (phase0 G-P0d, G-P0e). At other values of `c`
  with an impulse, `(1 − c) + c` may differ from 1f by one rounding; the claims
  test states bit-identity at 0, 0.5 and 1 and a 1-ulp-of-gain bound elsewhere.
- **It keeps DROOP, CLACK, BUZZ and CAVITY natively**, because it is TERRA's
  own bank with its own phases, exciter, cavity stage, BUZZ, CLACK split and
  chain (struck-r2-spec §2 A).
- **The receiver's modes are recomputed at render**, so retuning or re-pitching
  the TERRA pad after picking a striker still works: what is stored is the hit,
  not its projection.
- **Verified** on the membrane at `c = 0.5`: every striker within 3.5 dB of
  today's overtone balance, with a 6.5 dB spread (verify-r2 §3; 6.4 dB on the
  float render, unchanged at `75b550c1`, phase0 §4.6). Monotone in `c` for 10 of
  10 strikers on every voice at `75b550c1` (phase0 §4.3).
- **"Strong" (1) is COLOURED 100 %, pending the Phase-0 listening check.** The
  owner's "strong" clip on the start-here page was RUNG, not COLOURED 100 %.
  The check (six clips, three questions) asks whether HIT 1 sounds as strong,
  whether its soft attack with a kick head is acceptable, and whether the RUNG
  clip rebuilt on today's chain sounds like the one heard ("Phasing and gates",
  Phase 0). If HIT 1 is heard as weaker, Phase 0 decides the fix before R1 —
  first A-D (the held-back clips; overtone balance equal to RUNG's to 0.01 dB on
  the membrane, but its per-mode levels missed by 1.9–3.5 dB, and its kick
  attack peaks at 8.7 ms, so it is not a free fix, phase0 §5 item 7).
- **What HIT 1 does to the attack**, named by strength (phase0 §5 item 3): at
  0.5 the first 5 ms keep today's peak on every voice; at 1 a bass head softens
  the low voices (membrane −2.2 dB, cavity −1.9 dB in the first 5 ms, mean over
  ten strikers; the membrane with THUMP KICK peaks at 14.0 ms). HIT 1 turns 5 of
  10 membrane renders from TOM to PERC and leaves the cavity (KICK), bell and bar
  (PERC) as they were (phase0 §4.5).

**The capture rule: struck-motion M7, at capture, once** (struck-r2-spec §1.1).
A stored head is already normalised, so the rule cannot run later.

1. Fold to mono (`Cleanup.toMono`, `Cleanup.kt:203`, as `Fork.striker` does).
   If the source is not 44.1 kHz, keep its first ~100 ms past the onset and only
   then `Resampler.resample` to 44100 (a whole 3 s source at 48 kHz cost 61 ms,
   struck-r2-spec §0).
2. `pk` = the source's peak over finite samples, used only to find the onset.
3. `onset` = the first finite index with `|s| ≥ 0.01 · pk`; `start = max(0, onset −
   44)` (1 ms of lead).
4. `head` = `source[start, start + 882)`, zero-padded; non-finite samples set
   to 0. If the head's peak, before normalising, is below `1e-4` (−80 dBFS,
   above a 16-bit file's own floor of about −96 dBFS) there is **no striker**:
   the capture returns nothing. This is the brief's wording ("when the head peak
   is below 1e-4"); the spike measured the whole source's finite peak instead
   (struck-motion M7), and the two differ only for a quiet source (finite peak
   1e-4 to 1e-2) whose 20 ms after the onset stay under 1e-4 and which is louder
   later (the Phase-0 record, §8.2). Which peak is tested is decision 20, with
   the brief's as the default; R1 re-runs M7's hostile list with it. On the pad
   sheet a missing striker is a refused pick: the row and the pad's current
   sound stay as they were, and the sheet says so ("The chooser and the
   pad-sheet group"); code that builds a patch without the sheet stores no
   striker, so the pad renders today's body.
5. `Dsp.normalize(head, 1f)`.
6. Fade: for `i < 88`, `head[882 − 88 + i] *= 0.5 · (1 + cos(π·i/88))` — the
   2 ms raised cosine of `Fork.striker` (`Fork.kt:353, :864-868`).

On the 13 strikers Phase 0 measured (round two's three and struck-shape's ten)
the result equals `Fork.striker` bit for bit (phase0 G-C1). By construction it
does so for any mono 44.1 kHz source above the silence floor whose onset is
within 44 samples of the start: `start` is then 0, and `Fork.striker` also reads
from sample 0 (`Fork.kt:859-870`). G-C1 does not cover the ~100 ms truncation
used for sources at another rate, which is new here. The result differs exactly
where `Fork.striker` has its five hazards ("The experiments, measured", round
two). The rule lives in `:synth` beside TERRA as a **public**
`Terra.captureStriker(source: Snip): FloatArray?` (`:shell` calls it, so it
cannot be `internal`; "Surfaces"), and FORK's own `Fork.striker` is left
untouched (its hazards are offered separately, "Out of scope").

**Stored data.** `striker: TerraPatch.Striker(head: FloatArray(882), hit:
Float, from: String?)` — the head exactly `Fork.STRIKER_SAMPLES` long (882 at
44.1 kHz, `Fork.kt:349-350`), every value finite; `hit` in 0..1 (how much of the
other pad's hit strikes this drum; the JSON key is `hit` too); `from` is the
source pad's display label, with the rules under "Data flow and
compatibility". **HIT's amount lives
inside the striker, not as a TERRA macro.** As a macro it would appear on the
SYNTH panel with no effect on an unstruck pad, SCRAMBLE would randomise it,
Breed would average it, and every TERRA voice's macro list would change; inside
the striker it travels only with the hit it scales. The cost: `Velocity`'s
brightness override names a *macro* (`Velocity.kt:239-254`), so registering HIT
as one later needs a small new path. That trade is the owner's decision 3.

**Voices.** All four TERRA voices. **Velocity.** TERRA keeps the `soften`
fallback: no TERRA macro is in `BRIGHTNESS_MACROS` (`Velocity.kt:500`) and TERRA
has no override line, so `atVelocity` returns `soften(patch.render())`
(`Velocity.kt:274`), and `patch.render()` includes the striker. HIT is not
registered as a brightness override until a monotonic sweep passes (the house
rule, `Velocity.kt:247-252` on FORK's STRIKE; `ForkTest`'s STRIKE sweep).

## BEND, the design

**What it is.** The drum's pitch follows another pad's pitch drop: "kick bends
the drum". The owner kept it from the bold round (bold-steer clip 1).

**Stored data.** `bend: TerraPatch.Bend(points: FloatArray(64), from:
String?)`, the points being pitch multipliers on a fixed time grid from the
strike, `t_j = BEND_SPAN · (j / 63)²` for `j = 0..63`, with
`BEND_SPAN` = 0.4 s (a default, owner's decision 6). The squared spacing puts
points every fraction of a millisecond in the first few milliseconds, where a
kick dives fastest (THUMP's `bendRate` reaches 400/s, a 2.5 ms time constant,
at BEND 1, `Thump.kt:195`), and every ~13 ms near the end, where it has
settled. Each multiplier is the source's pitch at `t_j` over its own settled
pitch, so the last point is close to 1 and the curve is *relative*: it bends
the TERRA pad around its own TUNE. Read with linear interpolation in log2 of the
multiplier; held at the last point after `BEND_SPAN`, the rule both spikes used
(map terra-hook §2.5). Every value finite and inside 0.25–4 in `init` and on
decode. 64 points is the house's small-array scale (Draw's envelope is 64,
SNAP's table 256, FORK's striker 882; `Patches.kt:426-432`).

**Render.** `pitch(t) = clamp(bend(t), 0.25, 4)`, composed with DROOP at
`Terra.kt:390` as "Architecture" says (with TALK, also × `2^(semis/12)` before
the clamp). Level is unchanged; nothing else moves.

**The capture: a new pitch-track, measured against THUMP's known curve.** No
pitch-tracking function for a fast sweep exists in the tree: `Pitch.detect` is
an autocorrelation over a 250 ms window (`Pitch.kt:37`) and `FineTuning`'s
windowed FFT is a test helper (`synth/src/test/kotlin/com/snipsnap/synth/FineTuning.kt:32`)
(map pad-drive §5). R3 adds one in `:synth`, deterministic and stateless:

1. The same front end as HIT's capture (mono, the ~100 ms rule extended to
   `BEND_SPAN` plus a margin, onset minus 1 ms, non-finite samples zeroed,
   and the same 1e-4 silence floor on the aligned capture, decision 20).
2. A low-pass a little above the settled pitch (estimated first from the tail
   with a windowed FFT), then zero-crossing periods — the method that matched
   the spike's copied kick curve to a real THUMP render with a worst relative
   error of 0.0051 over the first 50 ms (phase0 §4.8; bold-steer G4 printed 0.005).
3. The settled pitch from the last stable stretch; multipliers = instantaneous
   pitch / settled pitch, resampled onto the 64-point grid; before the first
   full period, the first measured value is held.
4. Refused, by name, when there is no pitch to follow: fewer than 4 zero-crossing
   periods inside the first `BEND_SPAN`, a settled pitch outside 20–2000 Hz, or
   period jitter above 0.10 (the standard deviation of the last six periods over
   their mean). The pad keeps its sound and the sheet says why ("Failure
   handling"). The 4, the 20–2000 Hz and the 0.10 are **starting values**, not
   measurements (decision 6): R3 measures them against kicks, toms, hats,
   noise bursts and chords and pins the final values in the KDoc.

**Its claims test** captures from real `Thump.render(KICK)` renders at three
SWEEP × BEND corners and compares the 64 points with THUMP's own analytic
multiplier, `1 + (sweepMult − 1)·e^(−bendRate·t)` with `sweepMult =
lin(SWEEP, 1.2, 4)` and `bendRate = around(BEND, 18, 90, 400)` (`Thump.kt:188,
:195, :219`), from the end of the second zero-crossing period to `BEND_SPAN`:
median within 10 cents and worst within 35 cents (proposed bars, set from R3's
first printed table and pinned there). A second test renders a BENT BY membrane
at DROOP 0 and reads its zero-crossing periods against `f0 × bend(t)` over
10–200 ms, worst relative error within 1 % (17 cents), the G4 method (bold-steer
G4 measured 0.5 %).

**What BEND is not.** The bold clip added the kick's first 15 ms *after*
TERRA's chain as its attack (bold-steer clip 1). BEND does not layer audio; the
built way to get the kick's hit into the drum is to pick the same pad for STRUCK
BY too, which colours the modes rather than pasting a head. R3's gate hears
both against the bold clip.

**Voices.** COMPOUND_MEMBRANE and RESONANT_CAVITY first, the voices with droop.
On the bell and bar a pitch curve owns f0 outright; they follow if R3's gate
asks.

## TALK, the design

**What it is.** The drum speaks one of VOX SPEAK's eight words: the word's
vowels shape the per-mode levels and its intonation bends the pitch — "a
talking drum". The owner kept it from the bold round (bold-talk clip 3).

**The algorithm (bold-talk §4, `TalkDrum`).** Every 32 samples at 176.4 kHz,
for the baked frame at time `t` (interpolated between the two nearest frames):

```
hz_k(t)  = currentF0(t) · ratio_k                                      the mode's current, bent frequency
R_k      = |H(hz_k; f1, f2, f3, nasal, damp)|                          the vocal tract's magnitude response
w_k      = R_k · max_k(g_k) / max_k(g_k · R_k)                         the loudest weighted mode keeps the loudest unweighted level
V_k      = max(w_k, 10^(−26/20))                                       floored at −26 dB
V_k(n)   = one-pole smoothed, 1 ms                                     applied as level_k(n) at Terra.kt:400
pitch(t) = 2^(semis(t)/12)                                             × droop (and × bend, if both)
```

`H` is the analytic cascade the spike evaluated (`TalkWord.response`, bold-talk
§2 and §4): F1–F3 from the frame, F4 3300 Hz and F5 3750 Hz fixed, base
bandwidths 60 / 90 / 150 / 250 / 200 Hz with F2–F5 multiplied by the frame's
`damp`, a nasal pole at 270 Hz and a zero sliding 270 → 450 Hz with `nasal`,
the word's own bandwidths (widen 1.0) — VOX SPEAK's resonator, anti-resonator
and tract (`VoxSpeak.kt:282-329`) evaluated as a magnitude at a frequency
instead of run as a filter. Because the response is read at the *bent*
frequency, BEND and TALK interact: a pitch curve sweeps each mode through the
vowel (map terra-hook §4.3).

**Under TALK the modes ring longer, as the clip the owner heard rang them**, so
the vowel outlasts the hit (a listening value, TALK only; owner's decision 7).
With TERRA's own decays the upper modes have fallen 30–40 dB before the vowel
has moved and the word is inaudible after about 250 ms (bold-talk §4). Two
things carry the clip's ring, both from `TalkClips.kt` (bold-talk §4):

- **The per-mode t60.** Mode k's t60 (k counted from 0) is multiplied by
  `TALK_RING_k` = 1.6 · (1 + 0.65·k) / (1 + 0.30·k): the 1.6 together with the
  clip's step in the membrane's damping, 0.65 → 0.30. The ratios are 1.60, 2.03,
  2.30, 2.48, 2.62 and 2.72 for modes 1 to 6 (`TalkClips.kt:103`, `:108`), so at
  DECAY 1 the six t60 are 1.44, 1.11, 0.90, 0.76, 0.65 and 0.58 s where TERRA's
  own are 0.90, 0.55, 0.39, 0.31, 0.25 and 0.21 s (arithmetic from `Terra.kt:169,
  :453-454`). The ratio depends on the mode's index alone, so the cavity (four
  modes, its own damping step 1.2, `Terra.kt:173`) takes the first four, 1.60,
  2.03, 2.30 and 2.48. That is an extension of the clip, which was a membrane, and
  nothing measured it (the Phase-0 record, §11): R3 prints it. TERRA's own damping
  step is not changed for any pad.
- **The render length.** The clip rendered a fixed 1.4 s (`TalkClips.kt:104`),
  where TERRA's own render at that DECAY is 1.26 s (`Terra.kt:285-286`). A fixed
  length cannot follow DECAY, so the build's rule is the frame count ×
  `TALK_LENGTH` = 1.4 / 1.26 = 10/9: the clip's 1.4 s at the clip's DECAY 1 (to
  within a frame), and longer or shorter with the t60 base elsewhere.

**The default is the clip the owner heard; the alternative is the brief's
paraphrase.** The brief says "under TALK the modes ring about 1.6× longer".
That sentence paraphrases the approved clip by its mode-1 figure; it is not a
separate decision, and the clip's modes do not all ring 1.6× longer (the ratios
run 1.6 to 2.7). The owner kept the clip by ear, so the clip wins and the
paraphrase read literally is decision 7's alternative: `TALK_RING` 1.6 on every
mode's t60 and on the frame count, with TERRA's own damping step. It gives t60 of
1.44, 0.87, 0.63, 0.49, 0.40 and 0.34 s at DECAY 1 (arithmetic from `Terra.kt:169,
:453-454`) and a render of 1.6 × `framesFor`, about 2.0 s: the lowest mode
matches the clip, and modes 2–6 are 21–41 % shorter than the heard clip's, which
is the problem the spike was built to fix (the upper modes dying before the vowel
moves), less severe than plain TERRA's and more severe than the clip's. R3's page
puts the built TALKS FIVE (clip 7) beside the heard clip (clip 9), so the owner
can hear that the build is the clip, and renders the alternative as a reference
clip below the lead block. The default reproduces the clip's ring and length at
the clip's settings; it is not the clip exactly, because the track is baked on a
64-frame grid and the chain is today's. Phase 0 reproduced the heard clip through
the prototype's two inputs, to a −116 dB residual (largest sample difference
3e−7) (phase0 §4.8), with the spike's own decays and 1.4 s, the longer ring
expressed as a decay ratio in the level curve; R3 keeps that as a reference test
of the *hook* (the internal bank driven with the spike's explicit per-mode decays,
level curve, pitch curve and length reproduces the clip's frozen samples), and
compares the built default with the clip and prints the difference (test 10). The
damping-step change is carried only as that ring ratio, under TALK, and never
moves a TERRA pad that has no TALK. **TALK changes the render length**; "length
unchanged" is HIT's claim and BEND's, not TALK's.

**Stored data: the track, baked.** `talk: TerraPatch.Talk(word: String,
seconds: Float, frames: FloatArray(64 × 6))` — 64 frames evenly spaced over the
word's duration at TALK's speed, each `(f1, f2, f3, nasal, damp, semis)`, with
`word` (one of the eight names, ONE..EIGHT, checked in `init` and on decode)
kept for display only. The track is VOX SPEAK's per-block
formant interpolation after `retime` and the jaw and throat scales
(`VoxSpeak.kt:353-370, :462-480`), at HUMAN 1, run at `TALK_SPEED` = 1.5 (the
spike's 0.600 s word in 0.400 s) and sampled onto the 64-frame grid; `semis` is
VOX SPEAK's own statement contour, `RISE_SEMIS · sin(π·min(2·frac, 1)) −
FALL_SEMIS · frac²` (`VoxSpeak.kt:221-222, :485`), scaled by `TALK_GLIDE` = 1.4
so the fall is a perfect fifth after a 2.1-semitone lift (bold-talk §4). The
speed and the glide scale are baked at pick time, so changing their defaults
later never moves a saved pad. After the last frame the drum holds the last
baked frame and the last pitch (bold-talk §4: "after the word the pitch stays at the
end value"). Every value finite and in its range in `init` and on decode.

**Why baked, and what still lives in code.** A saved TALK pad must not move
when VOX SPEAK is retuned (owner-approved), so the *track* — the part VOX SPEAK
computes — is data. The *response formula* `H` and its fixed F4/F5 and
bandwidths are TERRA-side code; a later change to them would move saved TALK
pads, so TALK's claims tests pin a frozen response table (`H` at a fixed set of
frequencies and frames, `assertContentEquals`), the same guard shape as TERRA's
own.

**Is 64 frames enough?** Unmeasured. Phase 0 reproduced the spike from *live*
curves only (phase0 §5, "not re-measured"); whether a 64-frame bake reproduces
the live track closely enough is R3's first claims test: the per-mode weights
from the baked track against those from the live track, over the word. The
provisional bar is a worst difference of 1.0 dB and a median of 0.3 dB (a
starting value, not a measurement: the vowel swings the weights 9–36 dB over the
word, bold-talk §4), which R3 may move after its first printed table (owner's
decision 6 holds the frame count's default and this bar).

**The word list.** ONE..EIGHT, in VOX SPEAK's order, starting at "—" (off). The
spike chose FIVE by measured contrast: ONE and FIVE swung modes 3–6 by 30–40 dB
over the word, TWO, THREE and EIGHT far less (bold-talk §4). All eight stay
pickable; the gate hears FIVE and ONE first.

**Shared groundwork with Group A's SAY: the frozen VOX SPEAK guard (G1), then
the extraction (F1).** SAY runs VOX SPEAK's tract live as a rack filter; TALK
bakes VOX SPEAK's track and evaluates its tract as a magnitude. The shared seam
is the per-block formant **track**, not only the resonator (critic, A2/B3
dependency 1). VOX SPEAK has no byte-exact test today — only behavioural ones
in `VoxGrainsTest` — so the guard lands first and alone: `LegacyVoxSpeak.kt`,
the whole of `VoxSpeak.kt` at `75b550c1` in test sources, and a test that
`VoxSpeak.synthesize` equals it over the 276-case grid the Group A spec fixes
(`2026-09-30-become-strung-say-design.md`, "Shared groundwork"). Then the
resonator, anti-resonator, tract and track move into one `internal object
Formant` in `Formant.kt`, float operation order verbatim; what else F1 moves (the
size arithmetic as one shared function, the track's added argument) and what
stays out of `Formant` are fixed by the Group A spec ("Shared groundwork"), which
owns both G1's grid and F1's contents. **Whichever of SAY and
this document's R3 reaches it first lands G1 and F1**; the other adds only its
own function on top (TALK's analytic response and the bake; SAY's filter loop).
The Group A spec says the same from its side. **`Formant` and `VoxSpeak` are
`internal`** (`VoxSpeak.kt:47`; `Vox.kt:247`), and Kotlin's `internal` is
module-wide, so `:shell`, where TALKS is picked, cannot call them. TALKS reaches
the track through a **public** `:synth` entry point, `Terra.bakeTalk(word:
String): Talk?` (and BEND and HIT through `Terra.capturePitchTrack` and
`Terra.captureStriker`), which calls `Formant` from inside the module; `Formant`
itself stays internal behind it ("Surfaces").

**Voices.** COMPOUND_MEMBRANE and RESONANT_CAVITY first, as BEND. The cavity's
75 Hz stage stays fixed under TALK too.

**HIT and TALK together.** Each driver's level curve is computed as if it were
alone (HIT's `s` against today's body; TALK's normalisation against the
unweighted modes), and with both the two factors multiply. R3's gate hears the
combination; if it asks, R3 adds a rule.

## The chooser and the pad-sheet group

**Where it lives: the TERRA pad's own sheet**, not SYNTH. On the pad sheet the
slot is known, so the chooser can offer every other pad and never the pad
itself — MUTATE's guard, `partners(kit, slot)` = every other assigned pad, slot
order (`MutateSheet.kt:95`). On SYNTH the destination is unknown until SEND
(`sendToSlot(slot)`, `SynthScreen.kt:488`), so a driver picked there could turn
out to be the destination itself, a rule that could only be enforced at SEND
(map pad-drive §5). SYNTH's destination chooser, `SlotChooserOverlay`, draws
bank A only (`SLOT_ROWS = 13..16, 9..12, 5..8, 1..4`, `SynthScreen.kt:2458-2461`)
and its tint means "landing here".

**One shared chooser, lifted from MUTATE** (owner-approved shared default).
MUTATE's partner picker is the only "which pad" picker in the app:
`MutateSheet.Partner` (a pad on this kit, a crate deal, a room, a pad on
another kit, a file off the phone), `partners`, `otherKits` / `padsOf` and
`source`, which reads the pad's WAV (`MutateSheet.kt:95, :98-113, :149-167,
:210-226`); its card draws the pad chips four to a row, labelled by
`MutateSheet.padTag` ("A03", `PadBanks.tag`), the picked chip filled with the
pad's colour (`PadSheetScreen.kt:3554-3580`; the other kit's pad rows are
`:3649-3666`; `:2552-2561` is the call that wires `partners` in). R4 lifts that
partner model and its `source` into one `:shell` object (a `PadChooser`; the
plan names it), with `MutateSheet` delegating to it so `MutateSheetTest` and
`ConventionTest`'s source-text laws over `PadSheetScreen.kt` stay green
(`ConventionTest` pins `val mutateKnobs = remember(slot) { … }` and MUTATE's
DRIFT assignment by regex; critic). Each caller says which kinds it offers:
MUTATE all five; TERRA's STRUCK BY and BENT BY and FORK's STRIKE FROM a pad on
this kit, a pad on another kit, or a file — not a crate deal or a room
impulse. **The FORK spec's "reuse the GRAINS source chooser" premise is
stale**: no GRAINS source chooser exists in `app/` (GRAINS has no `Engine`
entry, `SynthScreen.kt:1839-1840`), which FORK's own spec already recorded in
round five (`2026-09-27-fork-electric-piano-engine-design.md:830-838`); the
shared chooser is that door. Both groups edit `PadSheetScreen.kt` (3963 lines)
and `Copy`; Group A's BECOME lands first and R4 rebases onto it (Group A spec,
"Shared groundwork").

**The group on a TERRA pad's sheet** (owner-approved sketch):

```
STRUCK BY   [ A03 ▾ ]   HIT  ━━━━●━━━━   .50
BENT BY     [ —   ▾ ]
TALKS       [ ONE ▾ ]
            ▶ HEAR                  KEEP
```

- It appears when the pad's recipe parses (`PadRecipe.fromJsonValue`) to a
  `TerraPatch`. A recipe is the *last* step, not the stack (`RecipeReplay.kt`
  header, `:22-26`): a TERRA pad later bodied, crushed or mutated carries that
  treatment's recipe and shows no TERRA group.
- STRUCK BY and BENT BY open the shared chooser; TALKS opens the word list
  (—, ONE … EIGHT). HIT is a stepper slider shown only when STRUCK BY has a
  pad; BEND and TALK have no knob in this round. BENT BY and TALKS show only on
  COMPOUND_MEMBRANE and RESONANT_CAVITY pads; on the bell and the bar they show
  the house's '—' row so the card never jumps (Group A's BECOME rule).
- **Picking captures once.** Choosing a pad reads its WAV through the shared
  chooser's `source`, runs the capture (HIT's safe capture, or BEND's
  pitch-track) and keeps the result in the sheet's state; choosing a word bakes
  its track. Nothing on the source pad is referenced again. If the source
  changes or is deleted, this pad keeps its sound; the label ("A03", or "SOUL
  A03" for another kit: the display form `MutateSheet.name` builds,
  `MutateSheet.kt:116-122`, not the `Kit:A03` lineage form `source()` builds,
  `:213, :222`) is stored as display text only, in `Striker.from` and
  `Bend.from`, and may go stale harmlessly.
- **One rule for a refused pick.** A pick the capture refuses (a silent source,
  or no pitch to follow) leaves the row and the pad's current sound exactly as
  they were: a pad that already has a striker keeps it, and the sheet says why.
  Nothing is cleared by a failed pick.
- **Picking sets HIT to .50** (the sketch's value), and the slider moves it from
  there; HIT 0 keeps the striker and renders today's body (the same audio as no
  striker, though the recipe still writes version 2 with its striker), so it is
  not a way to clear the row.
- **Clearing.** STRUCK BY and BENT BY each offer "—" at the top of the chooser,
  and TALKS has "—" at the top of its word list; choosing it removes that
  driver. Removing the last driver makes the patch a plain version-1
  `TerraPatch` again (`striker`, `bend` and `talk` all null: it writes version 1
  and today's bytes, "Data flow and compatibility"), so KEEP then writes a plain
  TERRA render.
- **▶ HEAR == KEEP.** One `:shell` function builds the new `PadRecipe(newPatch,
  fx)` from the pad's recipe and the sheet's edits, and both buttons use it:
  HEAR renders it without writing; KEEP writes exactly those bytes — the
  promise `MutateSheetTest` pins for MUTATE ("preview is the sound KEEP would
  write, for every move", `MutateSheetTest.kt:93`). The pad's rack chain
  (`fx`) is kept and re-runs after the new render.
- **KEEP is undoable, and undo says what it does to the recipe.** KEEP goes
  through the model's bin-backed `replaceAudio` (`KitBuilder.kt:494-506`): the
  old WAV moves to the bin (`:503`), the new render is written, and the recipe
  is replaced (`:505`; `replaceAudio` carries a recipe only forward). There are
  two ways back, with different outcomes for the recipe:
  - **The group's own UNDO**, `TerraSheet.undo`, beside `MutateSheet.undo` and
    `OutsideSheet.undo` (`MutateSheet.kt:323`, `OutsideSheet.kt:212`). It brings
    the audio back out of the bin and **clears the recipe**, as every undo door
    in the house does: `untreatPad` and `unEraPad` set `recipe = null`
    (`KitBuilder.kt:795-809`) and `Mutate.undo` calls `untreatPad`
    (`Mutate.kt:274-277`), because the bin holds earlier takes of the audio, not
    of the recipe (`RecipeReplay.kt:22-24`). After it the pad is a plain sample,
    **the TERRA group is not shown** (it appears only when the recipe parses to
    a `TerraPatch`), and the pad's previous TERRA recipe is not restored. The
    treatment card's UNDO is not this door: `PadSheet.unTreatState` answers
    NOTHING for a synth-patch recipe (`PadSheet.kt:311-335, :395-400`), so the
    card's NONE chip and re-treat path, the only callers of `unEraPad`
    (`PadSheetScreen.kt:897, :1005`), never fire on a TERRA pad.
  - **The kit's takes history** (`TakesBinScreen`; `restoreTake`,
    `KitBuilder.kt:989-1001`; `MAX_TAKES` 32, `:1290`) restores a whole earlier
    `kit.json`, with the audio from the bin as of that take, so the old pad
    comes back **with its old recipe** and the group returns.

  Whether the group's UNDO should restore the previous TERRA recipe instead of
  clearing it is the owner's decision 17.

**The first synth-patch re-edit door on the pad sheet.** Today
`PadSheetScreen.kt` has no path that reopens a synth patch's parameters: a
grep for `PadRecipe`, `.patch` and `Patches.` finds only DE-SAMPLE's toasts
(`PadSheetScreen.kt:1982, :1989`) (map pad-drive §5). The one existing
re-render of a patch is DO IT AGAIN's `Plan.Patch`, which replaces the
destination's sound with the patch's own render through
`model.replaceAudio(slot, recipe.toJsonValue()) { recipe.render() }`
(`RecipeReplay.kt:47, :84-87, :161-164`). The TERRA door is that call with an
edited patch, in a new `:shell` object beside `MutateSheet` and `OutsideSheet`
(a `TerraSheet`; the plan names it), committed through the screen's
`commitPadEditNow` under `KitWrites` (`PadSheetScreen.kt:557`).

**Its refusals.** The door asks `requireRewritable(slot)` (`KitBuilder.kt:1174-1181`)
*before* HEAR as well as at KEEP, because "a preview of a move the keep would
then decline is worse than no preview. One gate, asked twice"
(`KitBuilder.kt:1162-1173`):

- a **velocity-layered** destination is refused ("pad N is velocity-layered -
  clear the layers before rewriting its audio", `KitBuilder.kt:1176-1178`);
- a **round-robin chain** destination is refused ("pad N is a round-robin chain
  - `robin --undo` before rewriting it", `KitBuilder.kt:1188-1192`);
- the pad itself is never offered as its own source;
- a source with no hit (a silent pad, under the 1e-4 floor) or no pitch to follow
  (BEND) is refused at pick time with a toast, by the refused-pick rule above: the
  row and the pad's current sound stay as they were.

Phone wording goes through `Copy` and its laws; the `:shell` messages stay the
model's own lowercase `require` text.

**Which take a source gives.** A **velocity-layered** source pad's `sampleFile`
is already its loudest zone (`Kit.kt:60-65`), so the capture reads it as is —
the loudest take, as MUTATE does. A **round-robin chain** source pad's file is
the concatenation of its takes (`Robin.concat`, `KitBuilder.kt:330`), so the
head the capture reads is the **first take of the cycle**; that is the rule,
stated on the sheet's help line. The capture cuts the file at the end of take
one before it reads, using the chain's own table (`ChainInfo.boundaries[1]`,
`Kit.kt:150-171`), so BEND's ~0.4 s window can never run on into take two. A **stereo** source is folded to mono
(`Cleanup.toMono`), as `Fork.striker` does. A source at another sample rate is
cut to its first ~100 ms past the onset before it is resampled ("HIT, the
design").

**FORK's STRIKE FROM rides along.** R4 also offers STRIKE FROM on a FORK pad's
sheet through the same chooser and door, writing `ForkPatch(…, striker)` with
`Fork.striker` exactly as it is (`Fork.kt:859-870`) — FORK's capture hazards
are FORK's decision and are offered separately ("Out of scope"); the chooser's
pick-time silence refusal guards the one that matters most (a silent source
rendering a silent FORK pad) without moving FORK's bytes. Whether FORK also
keeps a SYNTH-side STRIKE FROM before SEND is FORK's call.

**What can be verified here.** `:app` cannot be compiled or tested in this
environment (no Android SDK; `:app` is outside the CI test task,
`.github/workflows/tests.yml:73`). So every decision lives in `:shell` with
tests — what the group shows for a recipe, the chooser's candidates, the
capture, HEAR == KEEP, the refusals — and the composable is thin.
`ConventionTest` is the automated check on `PadSheetScreen.kt`, and the owner's
phone is the final one (R4's gate). The laws that bind the new door, besides
`mutateKnobs` and DRIFT: the `KitWrites` laws (every `KitBuilderModel.open` and
every `save` under `:app` sits inside the `KitWrites` lock span or is
allowlisted, `ConventionTest.kt:670, :892`, so the door opens and saves through
`withFreshKit` as MUTATE's does); the per-move knob law (`:1824-1864`: it bans
`remember(slot, mutateMode)`-style keys and requires per-move state to be
`remember(slot) { mutableStateMapOf … }`, which the group's per-row state
follows); and the law after it, that PAD SHEET state which is not a property of
one pad is not keyed on the slot (the group's picks are a property of one pad,
so they are).

## TERRA on the phone

TERRA has never reached a user's hands. It is registered in `Patches`
(`Patches.kt:53`) and `Velocity` (`Velocity.kt:217`) and nowhere else: it is
absent from the SYNTH screen's `Engine` enum, which lists thirteen engines
(`SynthScreen.kt:1839-1840`), from `Presets` (`Presets.kt:17-98`), from every
file in `app/`, `shell/`, `cli/` and `kit/` (tree check: `grep -rln Terra` is
empty), and from the roadmap's table; its 16-pad kit is reached only through
the `generateTerraKit` Gradle task (`synth/build.gradle.kts:315-323`) into
`testkit/SnipSnap Terra Kit/`. Without it, a TERRA pad exists only in that
testkit kit, and HIT, BEND and TALK would have nothing to drive.

**The reach round (R2)** follows FORK's and BORE's registration list (map
pad-drive §3, §7):

- a SYNTH `Engine` entry — the **14th** — with its arms (`next`, `voices`,
  `macrosFor`, `defaults`, `scramble`, `render`, `drumClass`, `buildPatch`,
  `SynthScreen.kt:1839-1974`), the voice-to-class comment block, and the
  cycler's stale "four engines" comment corrected (`SynthScreen.kt:951`; the
  cycler steps through `entries`, thirteen today). The other counts in prose
  that the 14th entry makes wrong are `Presets.kt:8` ("all thirteen registered
  engines", fixed in R2) and `SynthScreen.kt:188, :191, :199, :251, :1827`
  ("twelve-engine", "eleven", "eleven more", "eleven", "ten"), which are
  already stale against today's thirteen and which no law reads
  (`ConventionTest`'s count law polices only the nouns "scheme" and "starter"):
  R2 fixes the ones in blocks it edits and leaves the rest consciously;
- a `Presets` arm and `all()` term (`Presets.kt:17, :94-98`) with a new
  `TerraPresets.kt`, **eight presets per voice, authored by ear** (thirty-two;
  the `ForkPresetsTest` contract, `ForkPresetsTest.kt:70`), and `TerraKits`'
  inline macros pointed at it where they match. **`TerraPresets.kt` must hold
  the exact helper** `private fun p(voice: TerraVoice, name: String, vararg
  macros: Pair<String, Float>)` (`ForkPresets.kt:14` is the shape):
  `UserPresetsTest` builds that string from `UserPresets.voiceEnum(engine)` and
  requires it in `UserPresets.rosterFile(engine)` for every engine in
  `Presets.all()` (`UserPresetsTest.kt:161-175`), so the test fails the day TERRA
  is registered without it. The same desk promotes a saved preset into that
  table through `UserPresets.rosterLine` and `renderAll`, which write macros
  only (`UserPresets.kt:317-339`), so promoting a driven preset would silently
  drop its striker, bend and talk: the desk **refuses a driven patch** (decision
  19), and the roster stays plain macros;
- `Terra.scramble` and a per-voice `drumClassFor` — neither exists (`grep` of
  `Terra.kt`) — the class measured against `Classifier`, never wished (FORK's
  rule; most TERRA pads read PERC honestly, `TerraKits.kt:21-29`);
- a starter kit, on **SKIN's precedent, not FORK's**: `SkinKits.classic()` is
  plugged into `StarterKits.ALL` as `Starter("skin", …)`
  (`StarterKits.kt:103-107`), exactly as `TerraKits.classic()` would be, whereas
  `SynthKits.fork()` and `SynthKits.bore()` are wired into no starter (the kit
  generators, the audition generators and `SynthKitTest` are their only
  callers). A Terra entry in `StarterKits.ALL` (decision 18) makes the **tenth**
  starter, and that touches: `app/README.md:56` ("nine starters from
  `StarterKits`") and the KDoc at `KitsScreen.kt:86` ("nine starters"), both
  policed by `ConventionTest`'s stale-count law (`ConventionTest.kt:1052-1057,
  :1101-1138`, which scans the two READMEs and the `:shell` and `:app` sources
  and would fail on "nine"); the non-scrolling picker's ceiling comment above
  `KitsScreen.kt:1205` (nine rows, "about five rows of headroom"); and the tests
  that iterate `ALL`: `StarterKitsTest.kt:25-26` and its pinned id list
  (`nine starters, and blank leads them`, `:65-70`), `KitArtTest.kt:87`, `JCardTest.kt:68`, `UatSimTest.kt:125, :406`;
- `PresetsTest`'s roster sum (`PresetsTest.kt:47-53`), the root README's engine
  count ("thirteen engines in the `Engine` picker", `README.md:348`), the
  starter counts named above, and the roadmap row **S20**, claimed at
  implementation after a fresh check;
- TERRA added to `DeterminismTest` (a canary, `DeterminismTest.kt:34-47`'s
  shape) and `PadRecipeTest.onePatchPerEngine()` (`PadRecipeTest.kt:25`).

**The precondition: the owner OKs TERRA's plain sound first.** The house rule
is roster and picker only after the audition gate; TERRA shipped in PR #371
without one (`TerraAuditionGenerator.kt:9-11`). Its page already exists
(`generateTerraAudition`, `synth/build.gradle.kts:325-333`, into
`testkit/terra-audition/`) and holds the 16-pad kit plus, per voice, the
default and DECAY, FORCE, POS and the voice's own macros at both ends, about
56 clips (`TerraAuditionGenerator.kt:8-17`, counted from its knob lists). Per
the owner's standing preference it gains a **lead block of at most ten clips
and three questions** ("Phasing and gates", R2), the rest staying below as
reference. R2 can run alongside R1: it touches no line of the bank.

## Data flow and compatibility

**The recipe.** A driven TERRA pad is an ordinary synth pad:
`PadRecipe(patch = TerraPatch(…), fx)`, saved verbatim in `kit.json`
(`KitStore.kt:24-32`), re-rendered by `PadRecipe.render()`. Every driver is
**data inside the patch**, never a pointer to a pad (owner-approved; the
`ForkPatch.striker` precedent), so a recipe does not drift when THUMP, VOX or
the source pad changes.

**`TerraPatch` becomes a non-data class** with optional fields, in
`ForkPatch`'s shape (`Patches.kt:434-504`): `TerraPatch(name, voice, macros,
striker: Striker? = null, bend: Bend? = null, talk: Talk? = null)`.

- Arrays are copied defensively in and out; lengths are fixed (striker head
  882, bend 64, talk 64 × 6) and every value is finite and in range, checked in
  `init` (`require`, as `Patches.kt:443-449`) and again on decode
  (`JsonException`, as `Patches.kt:489-500`).
- **The display label.** `Striker.from` and `Bend.from` hold the source pad's
  label as the chooser shows it (`MutateSheet.name`'s form: "A03", "SOUL A03",
  or a file's name; `MutateSheet.kt:116-122`). It is **display only**: rendering
  ignores it, and a claims test changes it and compares samples. It is **part of
  the patch's value**: `equals`, `hashCode`, `copy` and the JSON round trip all
  include it, so two patches that differ only in label are different recipe
  data and re-encode to different bytes. It is `null` when there is none: a
  missing key decodes to null and null is not written. When present it is at
  most `MAX_FROM_CHARS` = 24 characters with no control characters, checked in
  `init` and on decode (`IllegalArgumentException`, `JsonException`); a
  non-string value is refused on decode; the chooser cuts a longer label to 24
  characters before it is stored. `SidecarFuzzTest`'s driven TERRA seeds carry a
  `from` that is a number, an object, 25 characters, and a string with a control
  character.
- `equals`, `hashCode` and `copy` are written by hand and compare arrays by
  content, because a data class would compare them by identity
  (`Patches.kt:471-482`). Nothing in the tree uses `TerraPatch`'s generated
  `copy` or `componentN` except its own `withMacros` (`TerraPatch.kt:22`,
  `copy(macros = macros)`, rewritten by hand to keep the drivers), and its only
  constructions are `TerraKits.kt:40` and the dispatcher (tree check), so the
  conversion breaks no caller.
- `render()` passes the drivers through (`Terra.render(voice, macros, drivers)`);
  `withMacros` keeps them (`copy(macros = …)`, as `Patches.kt:455`).
- `toJsonValue` writes the base fields as today and each driver **only when
  present**:

```
{"engine": "TERRA", "version": 2, "name": …, "voice": "COMPOUND_MEMBRANE", "macros": {…},
 "striker": {"from": "A03", "hit": 0.5, "head": [882 numbers]},
 "bend":    {"from": "A01", "points": [64 numbers]},
 "talk":    {"word": "FIVE", "seconds": 0.4, "frames": [384 numbers]}}
```

**The version rule: an older build refuses a driven pad.** `Patches.decode`
ignores unknown fields but refuses any version other than 1
("unsupported patch version", `Patches.kt:84-85`). So:

- a **plain** `TerraPatch` writes `"version": 1` and exactly today's bytes —
  every saved TERRA recipe and the Terra Kit stay byte-stable;
- a **driven** `TerraPatch` (any of the three fields present) writes
  `"version": 2`;
- `TerraPatch.fromJsonValue` accepts version 1 **without** driver fields and
  version 2 **with at least one**; a version-1 object carrying a driver field,
  or a version-2 object carrying none, is refused (`JsonException`), because no
  writer produces either — a hand-edited sidecar is refused, not silently
  played, the door SNAP's and FORK's arrays guard. `Patches.VERSION` stays 1
  for every other engine; how `TerraPatch` shares `Patches.decode`'s common
  fields across two versions is the plan's (a version parameter, or a
  TERRA-local check before the shared decode).

What an older build does with a driven pad, path by path (each read at
`75b550c1`):

| Path | Older build's behaviour | Outcome |
|---|---|---|
| Opening the kit, playing the pad | `KitStore` keeps `recipe` as opaque JSON (`Kit.kt:53-59`) and plays the WAV | the pad sounds as kept |
| DO IT AGAIN (paste) | `RecipeReplay.plan` → `runCatching { PadRecipe.fromJsonValue }` fails → `Plan.Refused(REPLAY_NO_DOOR)` (`RecipeReplay.kt:84-87`) | refused by name |
| BREED | `Breed.recipeOf` catches `JsonException` → null (`Breed.kt:147-156`); the pad is not crossable; `warnIfCorruptRecipe` logs it (`:187-194`, on the predicate `hasCorruptRecipe`, `:181-184`) | refused, nothing rewritten |
| Ghost velocity layers, Robin's zone grid, the VELOCITY starter | `Breed.recipeOf` → null → `soften` of the WAV (`KitBuilder.kt:368-370`, `Robin.kt:97-99`, `StarterKits.kt:122-123`) | layers from the audio; recipe untouched |
| User presets | an entry that fails `Patches.fromJsonValue` is kept as `unread` and written back verbatim (`UserPresets.kt:362-367, :403`) | carried, not shown, not stripped |
| Re-save of the kit | the recipe object is written back verbatim | the driver survives the old build |

No old path re-encodes a driven patch through a `TerraPatch` object, so none can
drop the driver on re-save — the failure the brief names ("silently playing
plain TERRA and dropping the driver"), which a same-version field would have
allowed (critic).

**Drivers survive the house's own rewrites** (new build):

- `Velocity`: layering calls `patch.withMacros(moved).render()`
  (`Velocity.kt:291`) for engines with a brightness macro and
  `soften(patch.render())` otherwise (`:274`); TERRA takes the second today,
  and both keep the drivers.
- `Breed`: a child rewrites macros through the patch's own JSON
  (`Breed.kt:283-288`) and crosses only same-engine, same-voice patches
  (`:251`), so a TERRA child keeps parent A's drivers unchanged — FORK's
  striker's behaviour; HIT, being inside the striker, is never averaged.
- `UserPresets`: a saved preset is the patch's JSON, renamed through it
  (`UserPresets.kt:307-311, :383, :396`); a driven preset carries its data
  (its source label may lie; the sound does not).
- JSON: `toJsonText` / `fromJsonText` round-trip exactly.

**Kit size.** A driver costs what FORK's striker costs. `Json.write`
pretty-prints one array element per line at two spaces per level and writes a
non-integer as `Double.toString` (`Json.kt:58-78, :95`); the head sits seven
levels deep in `kit.json`, so 882 floats cost about 31 KB (arithmetic: ~19
characters a value plus 14 of indent and a comma and newline; inferred, not
measured), BEND's 64 points about 2 KB and TALK's 384 numbers about 13 KB, so
about 46 KB for a pad with all three.
Every dirty save first archives the previous `kit.json` as a take
(`KitBuilder.kt:876-883, :1035-1053`), up to 32 of them (`MAX_TAKES`,
`:1290`), so a driven pad's bytes can sit on disk up to 33 times (about 1 MB for
a struck pad, inferred); and deleting a pad writes a bin tombstone carrying its
pad JSON, recipe included (`KitBuilder.kt:1267-1272`), while a rewrite through
`replaceAudio` moves only the WAV (`:503`). Nothing caps
`kit.json` on read (the parser caps only nesting depth); `KitBackup`'s 8 MB cap
is for extras (`KitBackup.kt:26`). **MPC export is unaffected**: the `.xpm` and
expansion writers read no recipe (map pad-drive §6); they bake the audio.

**Surfaces, by round** (sizes are estimates from FORK's and BORE's footprints):

| # | Surface | What changes | Round |
|---|---|---|---|
| 1 | `synth/.../Terra.kt` | `strikeAndModalBank`'s two optional inputs and its null path; the four voice functions pass them through; `render(voice, macros, drivers)`; HIT's capture and colouring; then BEND and TALK. **The public `:synth` entry points `:shell` calls**: `Terra.captureStriker` (R1), then `Terra.capturePitchTrack` and `Terra.bakeTalk` (R3); `Formant` and `VoxSpeak` stay `internal` behind them, because `internal` is module-wide and `:shell` cannot reach them (`VoxSpeak.kt:47`) | R1, R3 |
| 2 | `synth/.../TerraPatch.kt` | non-data class, `Striker` (with `from`), then `Bend` (with `from`) and `Talk`; the version rule | R1, R3 |
| 3 | `synth/src/test/.../LegacyTerraBank.kt` + a guard test | the frozen 40-case guard, landed first | R1 |
| 4 | `TerraTest.kt` (21 tests today) | HIT's claims tests; then BEND's and TALK's | R1, R3 |
| 5 | `DeterminismTest`, `PadRecipeTest` | TERRA canaries (plain and driven) | R1–R2 |
| 6 | `TerraPresets.kt` (with the exact `p` helper `UserPresetsTest` requires), `Presets.kt` (and its KDoc count), `PresetsTest`, `Terra.scramble`, `drumClassFor`, the desk's refusal of a driven preset (`UserPresets.rosterLine`), the starter kit as a `StarterKits` entry (`StarterKits.kt`, `KitsScreen.kt:86` and its ceiling comment, `app/README.md:56`, `StarterKitsTest`, `KitArtTest`, `JCardTest`, `UatSimTest`), root README count, roadmap S20 | the reach round | R2 |
| 7 | `app/.../SynthScreen.kt` | the 14th `Engine` entry and its arms | R2 (phone-verified) |
| 8 | `synth/src/test/.../LegacyVoxSpeak.kt`, `Formant.kt` | G1 and F1, if Group A's SAY has not landed them | R3 |
| 9 | `shell/.../PadChooser` (lifted), `MutateSheet` delegating, `TerraSheet` | the shared chooser and the TERRA door | R4 |
| 10 | `app/.../PadSheetScreen.kt`, `Copy` | the group's rows and the FORK pad's STRIKE FROM row | R4 (phone-verified) |
| 11 | `TerraAuditionGenerator` + its page | each round's gate clips | every round |

## Failure handling

- **No driver, or a neutral one, renders today's TERRA byte for byte** — the
  null path, guarded by the frozen 40-case test ("Architecture").
- **A silent source** (a head peak under 1e-4, decision 20) gives no striker and no bend. On the pad
  sheet the pick is refused and the row and the pad's current sound stay as they
  were (a pad that already has a striker keeps it); code that builds a patch
  without the sheet stores no driver, so the pad renders today's body — either
  way, never FORK's raw "silent in, silent out" (struck-motion M7;
  struck-r2-spec §7). Lead silence of any length,
  a −90 dBFS lead-in and a single sample at 5000 all capture the real hit, by
  onset alignment (struck-motion M7.1).
- **Non-finite input** is zeroed at capture; non-finite or out-of-range stored
  data is refused in `init` (`IllegalArgumentException`) and on decode
  (`JsonException`), so NaN never reaches the bank, where `coerceIn` would let
  it through (phase0 §3).
- **A non-finite level match** (`s` from a body peak of 0) renders the striker as
  absent; `c = 0` never computes `s` at all.
- **The pitch multiplier** is clamped to 0.25–4 at render, after BEND and
  TALK's semitones are combined; stored bend points outside that range are
  refused.
- **A mode whose level reaches 0** keeps accumulating phase (the skip guard
  reads the table gain), so it reopens in phase (phase0 §3).
- **Length.** HIT and BEND never change the frame count (`framesFor`,
  `Terra.kt:285-286`); TALK lengthens it by `TALK_LENGTH` = 10/9 (1.4 s at DECAY 1
  against 1.26 s, the heard clip's length), by design. A curve
  shorter than the render holds its last value; a longer one is cut.
- **No pitch to follow** (a hat, a noise burst, a chord) refuses BENT BY at pick
  time by name, by the same rule: the row keeps its previous source and the pad
  its current sound.
- **A destination that cannot be rewritten** (velocity-layered, round-robin) is
  refused before HEAR, by `requireRewritable`'s own words.
- **A source pad deleted or changed after the pick** changes nothing: the data
  is in the recipe; only the display label may be stale.
- **An older build** refuses a driven pad by version, on every path that would
  re-render it ("Data flow and compatibility").
- **Cost** is bounded by one extra pass over the striker's 3528 samples per mode
  and a level lookup in the bank: 1.4–1.7× an unstruck render (phase0 §4.7).

## Testing

All `kotlin.test`, never Jupiter; all read off rendered audio — the bank's
pre-chain buffer (an `internal` render at native rate, as `Fork.bank` is read
in `ForkTest.kt:318`) for the physics, the `Snip` for the product claims.
Sweeps in CI stay small (CI minutes are metered, `.github/workflows/tests.yml:3-17`);
the full ten-striker tables are printed behind an opt-in system property, BORE's
split. The strikers are the ten of round two (phase0 §2.4), rendered by the test
from their recipes and the four factory WAVs.

### The ones that carry the claims

Which round first runs each: tests 1–3, 6–8 and 11 from R1; tests 4 and 5 from
R1 on a patch with a striker only, **extended in R3** to a patch with all three
drivers (test 1's `bend`/`talk`-absent cases arrive with those fields in R3);
tests 9–10 are R3's. Test 6 is printed in R1 and pinned once R1's page, question
3, is answered.

1. **No inputs → byte-identical TERRA.** `Terra.render` equals the frozen
   `LegacyTerraBank` copy on the 40 cases (16 Terra Kit pads, four defaults, 20
   macro probes), `assertContentEquals`, `assertEquals(40, cases)`; landed
   first, passing against the unchanged source. Beside it, on the same 40
   cases: identity curves (exactly 1f) alone and together; HIT at `c = 0` with
   each of three heads; HIT with an impulse at `c = 0.5` and `1`; `talk` and
   `bend` absent (from R3, when the fields exist) — all byte-identical
   (phase0 G-P0a–e). And the 16 kit pads'
   recipes serialise to exactly today's JSON bytes (version 1, no driver
   fields), pinned as text.
2. **Coupling, not layering** (restated from the brief: the cavity's tuning
   moves onto the bank, below). At HIT 0.5 on each voice, a dull and a bright
   striker change the **overtone balance after 20 ms** — a band ratio, never the
   centroid, which barely moves (struck-r1) — by at least 3 dB (proposed), while the
   fundamental stays within 2 cents of unstruck and the length is unchanged to
   the frame. Measured at `75b550c1`, HIT 0.5, OB from 20 ms (phase0 T6):
   membrane THUMP KICK −21.07 against THUMP SNARE −15.23 dB (5.8 dB apart);
   cavity THUMP KICK −44.84 against WRAITH WORD −29.67 (15.2); bell THUMP KICK
   −47.78 against THUMP SNARE −43.91 (3.9); bar THUMP KICK −47.82 against WRAITH
   WORD −43.28 (4.5). f0 shift at HIT 0.5 over ten strikers is 0.00 cents on
   membrane, bell and bar (phase0 T4). On the cavity the final render reads up
   to 2.11 cents at 0.5 (17.37 at 1) because the fixed 75 Hz band-pass crossfade
   and a one-peak estimator move the reading while the bank is exact
   (struck-r2-spec §8.9; phase0 T4), so the cavity's 2-cent claim is asserted on
   the bank before the cavity stage and its final-render reading is printed.
   A swap of the exciter lambda (LAYERED) fails this test by construction: its
   body moves 0.00 dB.
3. **Capture safety.** The named hostile sources of struck-motion M7.1, each
   asserted finite, peak ≤ 1, and either a real hit or today's body byte for
   byte: digital silence and an empty source (fallback); white noise at 1e−4 and
   below (fallback) and at 1e−3 and above (a hit); a NaN at sample 10 and +Inf
   at sample 5 (zeroed, a hit); a kick after 20, 40 and 100 ms of silence and
   after 30 ms of −90 dBFS noise (the kick's own head, by onset); a single-sample
   spike at 0, 881, 882 and 5000; a DC step and a 5 ms DC pulse; clipped squares
   at 100, 1000 and 4000 Hz. Then the **200-case robustness sweep** (random
   strikers × random TERRA macros): 0 non-finite renders, 0 silent renders, every
   length equal to its unstruck twin, peak ≤ 1 (struck-motion M7.2: 0 / 0 / 0
   with the rule, 15 fallbacks). And the capture equals `Fork.striker` on the 13 measured
   strikers (phase0 G-C1) and on every mono 44.1 kHz source above the floor
   whose onset is within 44 samples of the start.
4. **Drivers survive the house's rewrites.** For a patch with all three drivers
   (R1: a striker only; R3 adds the other two):
   `withMacros` keeps them; `Velocity.atVelocity` and `variantsAt` render with
   them; `Breed.cross` of two same-voice TERRA pads keeps parent A's drivers and
   `Breed`'s JSON macro rewrite keeps them; `UserPresets` save, rename and reload
   keep them; `toJsonText` → `fromJsonText` is equal by content and re-encodes
   to the same bytes; `equals`/`hashCode` compare arrays by content.
5. **An older build refuses a driven pad.** A driven patch writes `"version":
   2`; the shared `Patches.decode` path that an older build runs refuses it
   ("unsupported patch version 2", `Patches.kt:84-85`) — checked by running the
   generic version-1 decode on the driven object; `RecipeReplay.plan` maps any
   decode failure to `Plan.Refused` (`RecipeReplay.kt:84-87`), checked with a
   recipe that fails to decode; a version-1
   object with a driver field and a version-2 object with none are refused by
   the new decoder (R1: a striker only; R3 adds the bend and talk cases); a
   plain patch writes version 1 and today's bytes (test 1).
6. **BUZZ and CAVITY drive under the level match** — the brief's claim cannot
   pass as written ("The experiments, measured", point 3; "Where this document
   departs from the brief", row 1), so this test encodes the choice made and
   pins numbers that can fail. At HIT 0: identical (test 1). At HIT 0.5 and 1:
   the cavity's tanh input (the peak of 1.15 × the 75 Hz band-pass) stays ≤ 0.36
   (measured max 0.357, phase0 §4.7), and BUZZ's time above 0.12 at BUZZ 1 for
   Phase 0's three named strikers is pinned to the printed values, as a ratio to
   today's within ±0.05. Cavity (today 55.6 ms), THUMP KICK / THUMP SNARE /
   WRAITH WORD: 63.7 / 40.0 / 32.2 ms at HIT 0.5 (1.15 / 0.72 / 0.58 ×) and 69.3
   / 24.7 / 14.2 ms at HIT 1 (1.25 / 0.44 / 0.26 ×). Bar (today 53.2 ms): 55.2 /
   47.8 / 35.7 at 0.5 (1.04 / 0.90 / 0.67 ×) and 54.2 / 41.3 / 13.3 at 1 (1.02 /
   0.78 / 0.25 ×). The ten-striker extremes (cavity 0.47–1.14 × and 0.05–1.25 ×;
   bar 0.50–1.14 × and 0.05–1.24 ×) are printed per striker, each end within
   ±0.05 of its measured value. A change to `s`, to the bank's level or to the
   threshold moves these numbers and fails the test; a wide band would not.
   The test encodes decision 2's default (BUZZ follows the striker, a peak
   match). It is **printed, not pinned, in R1 until R1's page question 3 is
   answered**; if the owner chooses the band-passed level match it is rewritten
   as "within a stated ratio of today's" and pinned then.
7. **A mode at zero level reopens in phase.** A level curve held at exactly 0
   for 20–60 ms on one membrane mode: the bank output inside the window equals
   the bank without that mode, and after reopening equals the no-curve bank
   sample for sample (phase0 §3: largest difference 0). The test compares
   waveforms after reopening, not a step, because the wrong guard shows as a
   phase error, not a click.
8. **HIT is monotone and keeps its promises by strength.** Over `c` ∈ {0, .25,
   .5, .75, 1}, each striker's OB moves monotonically on every voice (10 of 10,
   phase0 §4.3); at 0.5 the first-5-ms peak equals today's (1.000) and the drum
   class never changes (40 renders); at 1 the class flips are printed (5 of 10
   membrane renders TOM → PERC, phase0 §4.5) and the first-5-ms table is printed
   beside the Phase-0 numbers. This sweep is also what any future velocity
   registration of HIT would need first.
9. **BEND follows its captured curve within stated cents.** The capture against
   THUMP's analytic kick curve at three SWEEP × BEND corners: median within
   10 cents, worst within 35 cents, from the second zero-crossing period to
   `BEND_SPAN` (proposed; R3 pins it from its first table). The render against
   the stored curve: a BENT BY membrane at DROOP 0, zero-crossing periods
   against `f0 × bend(t)` over 10–200 ms, worst relative error ≤ 1 % (bold-steer
   G4 measured 0.5 % for the copied curve against a real kick).
10. **TALK's vowel response.** On the talking-drum recipe (TUNE 0.7222, DECAY
    1.0, FIVE), **in the heard clip's configuration**, which is TALK's default
    ("TALK, the design": the clip's per-mode decays and 1.4 s). The figures below
    were measured on the spike there, so they bind the shipped default directly;
    R3 re-measures them on the built, 64-frame-baked track and today's chain,
    prints the difference from the clip, and only then pins them: per-mode measured
    level against intended (gain × weight × own decay), Pearson r ≥ 0.97 for
    every mode (proposed; spike 0.98–1.00); pitch against the intended glide
    median within 6 cents and worst within 20 (proposed; spike 5 / −14, bold-talk
    §4); the centroid falls from the open vowel to the closed one (spike 708 →
    287 Hz over 60–300 ms); the 64-frame baked track's per-mode weights against
    the live track's, worst difference printed and held to the provisional bar
    (1.0 dB worst, 0.3 dB median) until R3 pins it; the frozen response table of
    `H` matches byte for byte; the hook reference (the internal bank with the
    spike's explicit decays and 1.4 s reproduces the heard clip's frozen
    samples, phase0 §4.8); and `talk = null` renders today (test 1).
11. **Determinism.** Every driven patch renders byte-identical twice; TERRA's
    `DeterminismTest` canary (plain and driven) and its `PadRecipeTest` entry.

### The rest

- **Fuzz.** `SidecarFuzzTest` and `DegenerateDoorsTest` gain driven TERRA
  recipes as hostile seeds — wrong lengths, NaN and Infinity as strings, nested
  objects where arrays belong, a version-1 object with drivers — and every
  reader (`PadRecipe`, `RecipeReplay.plan`, `Breed.recipeOf`, `KitDiff`, the
  new `TerraSheet.read`) refuses or returns null, never throws past its own
  catch.
- **The TERRA door, in `:shell`.** The chooser never offers the pad itself; a
  velocity-layered source gives its loudest zone, a chain source its first take,
  a stereo source its mono fold; HEAR's render equals KEEP's written WAV and
  recipe for every combination of the three rows (the `MutateSheetTest.kt:93`
  pattern); a layered or chained destination is refused at HEAR and at KEEP in
  `requireRewritable`'s words; a refused pick changes nothing (the row, the
  striker the pad already has, the sound); "—" clears a driver and clearing the
  last one writes a plain version-1 patch and today's bytes. **UNDO is asserted
  on the recipe as well as the audio:** after `TerraSheet.undo` the WAV equals
  the pre-KEEP file byte for byte *and* the pad's recipe is null (so the group's
  read returns nothing); and restoring the take archived before KEEP brings back
  the pre-KEEP WAV *and* the pre-KEEP recipe. If decision 17 chooses a
  recipe-restoring UNDO, the first assertion becomes "the recipe equals the
  pre-KEEP recipe".
- **The chooser lift.** `MutateSheetTest` passes unchanged; `ConventionTest`'s
  `mutateKnobs` and DRIFT laws pass unchanged; the lifted `source` reads the
  same bytes MUTATE read.
- **Copy.** Every new label, toast and refusal passes `PersonalityTest`'s laws.
- **Reach round.** `TerraPresetsTest` on `ForkPresetsTest`'s six contracts (eight
  per voice, ≤ 14 characters, uppercase, unique, the blocklist, spread);
  `UserPresetsTest`'s helper law on `TerraPresets.kt`; the desk refuses a driven
  preset (and `renderAll` skips it with a line saying so); the starter laws
  above (`StarterKitsTest`'s pinned list, `ConventionTest`'s count law);
  `drumClassFor` agrees with `Classifier` across each voice's DECAY steps;
  `PresetsTest`'s sum; 200 SCRAMBLEs finite and unclipped.
- **Render time**, printed beside the sweep against Phase 0's 1.4–1.7× (HIT) and
  0.98–1.05× (neutral).

## Phasing and gates

Every round ends the house way: stop and listen. Each gate page **leads with at
most ten clips and at most three questions** (the owner's standing preference,
after round two's 111 clips); anything else sits below as reference. Clips are
mono 44.1 kHz, levelled by `AuditionLevel.level`, written by
`TerraAuditionGenerator`.

| Phase | Ships | Gate |
|---|---|---|
| **0 — re-measure** (done, `phase0`; the record is `../plans/2026-09-30-chimera-phase-0-record.md`) | every TERRA number re-measured at `75b550c1`; `P0Bank`, the prototype of the two inputs; all numeric checks passed; one conflict escalated (BUZZ's drive) | **the HIT-at-1 listening check** below — pending the owner |
| **R1 — HIT in the engine** | the frozen 40-case guard first; the two inputs and their null path; HIT's capture and colouring; `TerraPatch` with `Striker` and the version rule; claims tests 1–3, 7–8 and 11 (test 6 printed until R1's page, question 3), and tests 4–5 with a striker only | R1's page (10 clips, 3 questions) |
| **R2 — TERRA on the phone** (may run alongside R1) | the precondition page first; then the 14th `Engine` entry, `TerraPresets` (8 per voice, by ear), `Terra.scramble`, `drumClassFor`, the starter kit, `PresetsTest`, README count, roadmap S20, the canaries | the precondition (10 clips, 3 questions), then R2's page (9 clips, 3 questions); the owner's phone is the final check of the picker |
| **R3 — BEND and TALK** | G1 and F1 if Group A's SAY has not landed them; the pitch-track capture; TALK's bake and response; `bend` and `talk` on `TerraPatch`; claims tests 9–10, and tests 1, 4 and 5 extended to `bend` and `talk` | R3's page (10 clips, 3 questions) |
| **R4 — the pad-sheet group and the shared chooser** | the chooser lifted from MUTATE (after Group A's BECOME); `TerraSheet`; the group on a TERRA pad's sheet; FORK's STRIKE FROM on a FORK pad's sheet | R4's page (6 clips) and a phone checklist, 3 questions |

### Phase 0: the HIT-at-1 listening check (answered: "as strong")

**Answered 2026-09-30.** The owner heard the brief's three clips (decision 21's
default) in the order today's drum, `cm_rung_wraith_heard` (labelled "the strong
clip you picked", byte-identical to the start-here page's strong clip) and
`cm_hit1_wraith` (labelled "HIT at full"), and answered question 1: **"As
strong"**. HIT 1 is COLOURED 100 % and stands into R1, so decision 1 is taken.
Questions 2 and 3 were not asked, and their subjects keep their defaults until
R1's page (decision 4). The text below records the check as it was planned.


The brief planned a three-clip check; Phase 0 built six — a HIT 1 and RUNG pair
for each of two strikers, today's drum, and the RUNG clip as heard — still
inside the ten-clip limit. Six is a deviation, **proposed and pending the
owner's OK** (decision 21). The default is the brief's three: `cm_unstruck`,
`cm_hit1_wraith` and `cm_rung_wraith_heard`, which can ask question 1 only;
questions 2 and 3 below are asked only if the owner OKs the six. Six clips, mono 44.1 kHz, `AuditionLevel.level` then
16-bit, in `phase0/PHASE0/`, fragment `phase0/fragments-phase0.json`, both in the
evidence folder (phase0 §6; Appendix A). HIT 1 is COLOURED 100 % read at the nominal pitch, through the
prototype bank, asserted bit-identical to round two's `renderA`; RUNG is round
two's recipe. Because the chain changed under RUNG, the WRAITH WORD RUNG clip is
given twice: rebuilt on today's chain, and as heard (byte-identical to round
two's `cm_b_wraith` and the start-here copy).

| id | name | what it is |
|---|---|---|
| `cm_unstruck` | TODAY'S DRUM | COMPOUND_MEMBRANE at defaults |
| `cm_hit1_wraith` | HIT 1 · WRAITH WORD | coloured 100 percent |
| `cm_rung_wraith` | RUNG · WRAITH WORD | the strong recipe, today's chain |
| `cm_rung_wraith_heard` | RUNG · WRAITH WORD · AS YOU HEARD IT | the clip from the earlier page, byte for byte |
| `cm_hit1_tkick` | HIT 1 · THUMP KICK | coloured 100 percent |
| `cm_rung_tkick` | RUNG · THUMP KICK | the strong recipe, today's chain |

What the ear can check, with the measured gaps (phase0 §6): with WRAITH WORD,
RUNG is 2.7 dB brighter in overtones than HIT 1; with THUMP KICK, RUNG is 6.5 dB
brighter and peaks sooner (8.3 against 14.0 ms). With THUMP SNARE (not in the
check) the order reverses — HIT 1 is 13 dB brighter than RUNG (−13.2 against
−26.3 dB) — so no single read matches RUNG on every striker. A separate check of
the written files (no renderer code) confirmed all six are mono, 44.1 kHz,
16-bit, 0.490 s, peaks 0.19–0.55, RMS within 2.0 dB of each other, OB and time to
peak within 0.02 dB and 0.02 ms of the renderer's.

Questions:
1. Does HIT 1 sound as strong as the RUNG clip you heard?
2. Is the soft attack of HIT 1 with the kick head acceptable, or should the drum
   keep more of today's attack at 1?
3. Do the two RUNG WRAITH clips, the one you heard and the one rebuilt on
   today's output stage, sound the same to you? (It decides which of the two is
   the strength reference for question 1.)

**What each answer does.** (1) "As strong": HIT 1 = COLOURED 100 % stands into
R1. "Weaker": before R1, the held-back A-D clips (`r2-work/held-back-a100d/`)
are the next page (at most ten clips), and the owner picks between A-D as the
top of the knob and a different top; R1 does not start on a guess. (2) "Keep
more of today's attack": R1's plan carries the fix the owner picks from that
page (A-D is softer than HIT 1 with the kick, 8.7 ms against 14.0, so it is not
a free fix; phase0 §5 item 7). (3) "Different": the rebuilt clip, not the one heard, is the
strength reference, and R1's "strong" means "as strong as RUNG on today's
chain". The owner is then asked question 1 again on a short follow-up page of
two clips (the rebuilt RUNG clip and HIT 1, one question), inside the
standing limits; every round-two RUNG time to peak is already uncitable
(phase0 §5 item 2). "The same": the compressor's removal is inaudible on ringed
renders, and the heard clip stands as the reference. Question 3 was kept
rather than swapped for a HIT 0.5 question because the six clips are already
rendered and none of them is HIT 0.5; the subtle step is heard on R1's page.

### R1's page: HIT in the engine (10 clips)

| # | Clip | Why |
|---|---|---|
| 1 | MEMBRANE · TODAY | the anchor |
| 2 | MEMBRANE · HIT .5 · THUMP SNARE | subtle, bright head |
| 3 | MEMBRANE · HIT 1 · THUMP SNARE | strong, bright head |
| 4 | MEMBRANE · HIT 1 · FACTORY KICK | strong, bass head (the soft attack) |
| 5 | CAVITY · TODAY | the anchor |
| 6 | CAVITY · HIT .5 · WRAITH WORD | the cavity's biggest move at subtle (+8 dB) |
| 7 | BELL · HIT .5 · FACTORY CLAP | a comb-shaped head on a bright voice |
| 8 | BAR · HIT .5 · BEATBOX RIM | the bar's brightest striker |
| 9 | TERRA KIT BUZZ PAD · TODAY | BUZZ as tuned (A04, A07 or A16; the plan picks the one with the most BUZZ) |
| 10 | THE SAME PAD · HIT 1 · FACTORY HAT | BUZZ following a bright, light head |

Questions: (1) Does HIT move from today through subtle to strong in steps you
can hear, on every voice? (2) Is any voice wrong at HIT .5 — the bell or bar
going thin under a dark head — so that HIT needs a floor? (3) Should the rattle
follow the striker (clip 10, the default) or stay as today (decision 2)?

### R2's pages: TERRA on the phone

**The precondition**, the lead block on TERRA's existing page (10 clips): the
four voices at defaults, then six Terra Kit pads, one per family (udu, cajón,
djembe, tabla, agogô, balafon; the plan picks the slots). Questions: (1) Is
TERRA's plain sound ready to go on the phone? (2) Is any voice not ready, and
which? (3) Does the membrane's pitch sag (DROOP) read as a drum or as a glitch?
No roster or picker entry is written until (1) is yes.

**R2's own page** (9 clips): two presets per voice, the two the author judges
most different (8 clips), and the starter kit as one two-bar groove (1 clip);
the other 24 presets are heard on the phone itself. Questions: (1) Is any preset
not earning its slot? (2) Does the starter kit read as a kit? (3) On the phone,
does a TERRA render land on a pad without a wait you notice?

### R3's page: BEND and TALK (10 clips)

| # | Clip | Why |
|---|---|---|
| 1 | MEMBRANE · TODAY (at a TUNE an octave over the kick) | the anchor |
| 2 | MEMBRANE · BENT BY THUMP KICK | the built BEND, captured curve |
| 3 | MEMBRANE · BENT BY + STRUCK BY THUMP KICK, HIT 1 | the built way to get the kick's hit in |
| 4 | KICK BENDS DRUM · AS YOU HEARD IT | the bold clip, byte for byte |
| 5 | MEMBRANE · BENT BY FACTORY KICK | a real recording's pitch drop |
| 6 | CAVITY · BENT BY THUMP KICK | the cavity, its 75 Hz stage fixed |
| 7 | MEMBRANE · TALKS FIVE | the built TALK |
| 8 | MEMBRANE · TALKS ONE | the other high-contrast word |
| 9 | A TALKING DRUM · AS YOU HEARD IT | the bold clip, byte for byte |
| 10 | MEMBRANE · BENT BY THUMP KICK + TALKS FIVE | both pitch drivers at once |

Below the ten, as reference: MEMBRANE · TALKS FIVE with the uniform 1.6 ring, which
is decision 7's alternative.

Questions: (1) Does BENT BY read as the kick bending the drum, as the bold clip
did? (2) Does TALKS (clip 7) read as the talking drum you heard (clip 9) — and does
the longer ring help or hurt? (3) Should a pad be allowed to bend and talk at once?

### R4's gate: the group and the chooser

A page of six clips (four if R3 has not landed: no BENT BY or TALKS clip)
rendered by the `:shell` door itself — the exact bytes KEEP writes — on the
Terra Kit (a membrane pad STRUCK BY a kick pad at .5 and 1, BENT BY it, TALKS
FIVE) and the FORK kit (a FORK pad with STRIKE FROM a snare pad and
without), and a phone checklist of at most ten steps (open a TERRA pad's sheet;
pick STRUCK BY; move HIT; HEAR; KEEP; undo; pick a source on another kit; try a
velocity-layered pad and read the refusal; open a FORK pad; pick STRIKE FROM).
Questions: (1) Does the group read on the phone? (2) Is what HEAR plays what
KEEP wrote, and does UNDO bring the old sound back, with the pad then a plain
sample and no TERRA group (decision 17)? (3) Is the same chooser right for
FORK's STRIKE FROM?

**Order.** Phase 0's check, then R1. R2 may run beside R1 (no shared lines). R3
after R1 (it needs the inputs), sharing G1/F1 with Group A's SAY — whichever
lands first does the extraction behind the frozen guard. R4 after R1 and after
Group A's BECOME; BENT BY and TALKS join the group when R3 has landed, and show
the '—' row until then.

**Effort**, estimated from the spikes and the precedents (estimates, not
measurements): R1 ~350 hand-written lines (the bank change and voice threading
~80, bold-talk §6 having put the TERRA side at 60–80; HIT and its capture ~120;
`TerraPatch` ~110 on `ForkPatch`'s 80; tests ~350) plus a ~600-line frozen copy;
R2 ~300 (presets, registration, `SynthScreen` ~20, tests); R3 ~450 (pitch track
~150, TALK ~150, tests ~300; plus G1/F1 if first: a ~570-line frozen copy and
the extraction); R4 ~600 (the chooser lift ~150, `TerraSheet` ~200, the screen
~150, tests ~250).

## Out of scope

- **Group C** (snare drives gong, word shapes bell, kick plucks string): a later
  spec on this document's capture plumbing; each needs its own engine hook (TIDE's
  fold curve, TINES' index curve, the strings' retune).
- **FORK's whole-hit striker** — a FORK striker longer than 882 samples — and
  "snare rings the tines": FORK's decision; they break FORK's 882-sample hammer
  rule.
- **CHIMERA as an engine**: the post-render hybrids equal MUTATE or TERRA alone
  from 20 ms on, NCC 0.9993–1.0000 (hybrid-r2-spec), and an engine that renders
  other engines would be the first of its kind and would drift whenever any of
  seven engines is retuned (house F3, F4).
- **BEATBOX, WRAITH and THROAT voices**: dropped; any pad's head can strike a
  TERRA pad, and throat-as-a-hit belongs to VOX.
- **RUNG** (resonators driven by the striker) and **A-D** as R1 paths: RUNG
  misses its impulse target on the cavity and moves the attack; A-D misses its
  per-mode gate (phase0 §3). A-D returns only if Phase 0's check asks.
- **Three probable house bugs, offered separately**, not fixed here:
  `Modes.ring`'s float coefficients detune low modes at 176.4 kHz (membrane
  TUNE 0 −8 cents, cavity TUNE 0 +13.8, default cavity −4.7; FORK and THUMP call
  it too, unchecked) (struck-r2-spec §8.5; struck-shape §2.4); `Fork.striker`'s
  capture hazards (struck-r2-spec §8.6); and a polyphase 4× upsampler
  bit-identical to `Resampler.resample` (largest difference 0.0) that cuts the up-conversion-bound hybrid path from 2.6–9.1× to 1.3–2.5× its costliest part, 2–4× faster (hybrid-r2-spec §1 C1, §6).
- **A SYNTH-side driver picker** before SEND: the destination is unknown there
  ("The chooser and the pad-sheet group").
- **A TALK depth or BEND amount knob**, **a change to TERRA's own damping step**
  (TALK's longer ring is a ratio on the t60 under TALK alone), and **BEND and
  TALK on the bell and bar**: not in this round; each is one gate question away.
- **Velocity registration of HIT** before a monotonic sweep passes and a path
  for a non-macro override exists.

## Decisions already taken

The brainstorm's record carries one date range, 2026-09-29–30, and no finer
dates, so every row below carries that range and names the brief section that
holds the decision: "Shared defaults", section 1 (the TERRA change and what a
pad remembers), section 2 (the chooser and the pad-sheet group) or section 3
(TERRA on the phone, tests, rounds).

| Question | Decision | By |
|---|---|---|
| Engine or not | No CHIMERA meta-engine; the one new idea goes into TERRA as data | the six-lens review (synthesis §7); the owner's regrouping, 2026-09-29–30 |
| Struck TERRA's control | "A knob between them" (today → subtle → strong): HIT | owner, Q1, 2026-09-29–30 |
| What HIT 1 is | COLOURED 100 %: "As strong" as the strong clip, heard on the brief's three clips | owner, the Phase-0 check, 2026-09-30 |
| How far | "I think we could go further/bolder"; after the bold round, "keep Steer, Ring and Talk" | owner, Q2 and the bold round, 2026-09-29–30 |
| Grouping and order | Group B = HIT, BEND, TALK; "A alongside B"; Group C later; "snare rings the tines" parked for FORK | owner, 2026-09-29–30 |
| This design | approved in three sections; "Yes, write the specs" | owner, 2026-09-29–30 |
| The hook | one change in `Terra.strikeAndModalBank`: a per-mode level curve on `mode.gain` and a pitch curve on the droop line; none → today, byte for byte, behind a new frozen 16-pad guard | owner, brainstorm 2026-09-29–30, brief section 1 |
| The pitch curve and DROOP | composes (multiplies), never replaces | owner, brainstorm 2026-09-29–30, brief section 1 |
| The clock | both curves start at the strike (onset), so CLACK lands first | owner, brainstorm 2026-09-29–30, brief section 1 |
| The cavity | its 75 Hz air resonance stays fixed while the drum bends | owner, brainstorm 2026-09-29–30, brief section 1 ("part of its character") |
| HIT's algorithm | round two's COLOURED candidate A, gain domain, running magnitude, level match `s`; "strong" = 100 %, pending the Phase-0 check | owner, brainstorm 2026-09-29–30, brief section 1; struck-shape S6b; verify-r2 |
| HIT's capture | the safe capture rule, struck-motion M7 | owner, brainstorm 2026-09-29–30, brief section 1 |
| BEND's data | a fixed-length 64-point curve from a new pitch-track capture, tested against THUMP's kick curve; multiplier clamped (0.25–4) | owner, brainstorm 2026-09-29–30, brief section 1 |
| TALK's algorithm and data | `TalkDrum`'s per-mode vowel response at the bent frequency, floored at −26 dB, 1 ms smoothing, droop × `2^(semis/12)`; the track baked as fixed-length data; the heard clip's ring under TALK (the brief's "~1.6×" is a paraphrase of it, its mode-1 figure; decision 7) | owner, brainstorm 2026-09-29–30, brief section 1; bold-talk §4 |
| Voices | HIT on all four; BEND and TALK on the membrane and the cavity first | owner, brainstorm 2026-09-29–30, brief section 1 |
| Names | HIT (not STRIKE), BEND, TALK; STRUCK BY, BENT BY, TALKS | owner, brainstorm 2026-09-29–30, brief section 1; `Terra.kt:55-60` |
| Storage | data in the recipe (the `ForkPatch.striker` precedent), never a pointer; `TerraPatch` a non-data class with optional fields | owner, brainstorm 2026-09-29–30, brief "Shared defaults" |
| Old builds | refuse a driven pad; plain TERRA recipes byte-stable | owner, brainstorm 2026-09-29–30, brief section 1 |
| Velocity | TERRA keeps `soften`; no HIT override before a monotonic sweep | the house rule (`Velocity.kt:247-252`); owner, brainstorm 2026-09-29–30, brief section 1 |
| The chooser | one shared "pick a pad" chooser lifted from MUTATE, for MUTATE, STRUCK BY / BENT BY and FORK's STRIKE FROM; on the TERRA pad's own sheet | owner, brainstorm 2026-09-29–30, brief section 2, and "Shared defaults" |
| Captures | once; a changed or deleted source changes nothing; the label is display only | owner, brainstorm 2026-09-29–30, brief section 2 |
| Source takes | velocity layers → the loudest zone; stereo → mono; a round-robin chain → its first take (this document states the rule) | owner, brainstorm 2026-09-29–30, brief section 2 |
| TERRA's reach | a reach round (14th `Engine` entry, 8 presets per voice by ear, scramble, class, starter kit, counts, S20), only after the owner OKs TERRA's plain sound | owner, brainstorm 2026-09-29–30, brief section 3; the house's audition-gate rule |
| Gates | every gate ≤ 10 clips, ≤ 3 questions | the owner's standing preference (saved to memory; brief "How we got here", item 10) |
| `:app` | not verifiable here; logic in `:synth`/`:shell` with tests; `ConventionTest` and the owner's phone check the screen | owner, brainstorm 2026-09-29–30, brief "Shared defaults" |

## Decisions for the owner

Each with the default this document takes; a default stands until the owner or
a gate overturns it.

| # | Decision | Default | Alternatives | Where it is decided |
|---|---|---|---|---|
| 1 | What HIT 1 is | COLOURED 100 % | A-D (softer kick attack, per-mode gate missed by 1.9–3.5 dB); a different top | Phase 0's check, questions 1–2 (question 2 only if decision 21 takes the six; otherwise R1's page) **Taken 2026-09-30: "As strong"; see "Decisions already taken".** |
| 2 | BUZZ (and the cavity's drive) under HIT. **The brief disagrees with itself here**: its algorithm line has `s` match the body's peak, its drive line has the level match keep BUZZ's and the cavity's drive as today, and Phase 0 measured that both cannot hold | **BUZZ follows the striker**: `s` stays a peak match, which keeps the brief's algorithm line and breaks its drive line for BUZZ; BUZZ's time above threshold runs 0.05–1.25 × today's at HIT 1, the cavity's tanh stays within 4 % of linear. **This default is not owner-approved**: it is the behaviour that was measured | a band-passed level match on the cavity (keeps the drive line's intent; holds the tanh input and BUZZ near today's; estimated, not measured, to lift a hat head's low modes by about 10 dB, phase0 §5 item 4) | R1's page, question 3, before test 6 is pinned |
| 3 | Where HIT's amount lives | inside the striker (no SYNTH-panel knob, never scrambled or averaged) | a TERRA macro `HIT` on all four voices (reachable by `Velocity`'s override path, but shown with no effect on unstruck pads and averaged by Breed) | here |
| 4 | HIT's attack at 1 with a bass head | accepted as measured (14.0 ms peak, membrane, THUMP KICK) | keep more of today's attack (Phase 0 picks the fix) | Phase 0's check, question 2, if decision 21 takes the six; otherwise R1's page |
| 5 | What the chooser offers TERRA and FORK | a pad on this kit, a pad on another kit, a file | add MUTATE's crate deal and room | R4's gate |
| 6 | Data resolutions and starting bars | BEND 64 points, `t_j = 0.4 s · (j/63)²`; TALK 64 frames over the word. Starting bars, not measurements: BEND is refused under 4 periods in `BEND_SPAN`, outside 20–2000 Hz settled, or above 0.10 period jitter; the baked-against-live TALK weights within 1.0 dB worst and 0.3 dB median | other counts, spacings or bars, set by R3's first printed tables | R3 |
| 7 | TALK's listening values | **The heard clip's ring and length**: each mode's t60 × `TALK_RING_k` = 1.6·(1 + 0.65k)/(1 + 0.30k) (1.60, 2.03, 2.30, 2.48, 2.62, 2.72 for modes 1 to 6; t60 1.44, 1.11, 0.90, 0.76, 0.65, 0.58 s at DECAY 1), and the frame count × `TALK_LENGTH` 10/9 (1.4 s at DECAY 1, the clip's fixed length; it follows DECAY elsewhere); `TALK_SPEED` 1.5, `TALK_GLIDE` 1.4, floor −26 dB, 1 ms smoothing. The ratios apply by mode index, so the cavity takes the first four (unmeasured). The brief's "about 1.6×" is a paraphrase of this clip, so the clip wins | **the brief's paraphrase read literally**: `TALK_RING` 1.6 on every t60 and on the frame count (about 2.0 s at DECAY 1), TERRA's own damping step; a new listening value that rings modes 2–6 21–41 % shorter than the heard clip (t60 1.44, 0.87, 0.63, 0.49, 0.40, 0.34 s); or a different ring | R3's page, clips 7 and 9 (the built default beside the heard clip), question 2; the alternative is rendered as a reference clip below the lead block |
| 8 | BEND and TALK on the bell and the bar | not in R3 ('—' row) | add them (a pitch curve owns f0 there) | after R3's gate |
| 9 | Bend and talk at once; HIT and TALK at once | allowed; pitch factors and level factors multiply | one pitch driver at a time | R3's page, question 3 |
| 10 | A silent source | refused at pick; the row and the pad's current sound stay as they were (code that builds a patch without the sheet stores no driver, so the pad renders today's body) | FORK's "silent in, silent out" | here |
| 11 | The version rule | driven TERRA patches write `"version": 2`; plain stay 1 | bump `PadRecipe.VERSION` (refuses more than needed); same version (an old build silently drops the driver on re-save) | here |
| 12 | Kit size | ~31 KB per struck pad, up to 33 copies with takes (inferred) | a compact head (fewer bits), at the cost of FORK's shape | here |
| 13 | The TALKS word list | all eight, FIVE and ONE heard first | only the high-contrast words | R3's page |
| 14 | FORK's STRIKE FROM | on a FORK pad's sheet in R4, where no STRIKE macro is drawn ("The name") | also on SYNTH before SEND (FORK's call; there it would sit beside the STRIKE macro and FORK would need another word) | R4's gate |
| 15 | Roadmap | S20 for the reach round, claimed at implementation after a fresh check | a later number if taken | R2 |
| 16 | Order | Phase 0 → R1 (R2 alongside) → R3 → R4 | R4 before R3 (HIT's row alone) | here |
| 17 | What the group's UNDO does to the recipe | returns the audio and **clears the recipe**, as every undo door in the house does; the TERRA group then disappears and the previous TERRA recipe is not restored (it stays reachable through the takes history, which restores the old `kit.json`) | `TerraSheet.undo` restores the previous recipe as well (the sheet keeps it in memory for the session, or reads it from the latest take); no UNDO on the group, takes history only | R4's page, question 2 |
| 18 | Is the Terra Kit a starter | a `StarterKits` entry on SKIN's precedent: ten starters, so `app/README.md:56`, `KitsScreen.kt:86` and the tests that iterate `ALL` change ("TERRA on the phone") | a `SynthKits` and testkit item only: no picker entry, none of those edits | R2 |
| 19 | Promoting a driven user preset to the roster | refused by the desk (`UserPresets.rosterLine` writes macros only, so promotion would drop the drivers); `renderAll` skips it with a line saying so | carry the drivers as data in the roster file, a new file shape | here |
| 20 | Which peak the capture's silence floor tests | **the brief's wording**: the aligned 20 ms head's finite peak, before normalising, below 1e-4 ("HIT, the design", capture step 4); R1 re-runs struck-motion M7's hostile list with it | the whole source's finite peak, as struck-motion M7 measured; the two differ only for a quiet source (finite peak 1e-4 to 1e-2) that is louder after its first 20 ms | R1 |
| 21 | The Phase-0 listening check: the brief's three clips or the six built | **the brief's three**: `cm_unstruck`, `cm_hit1_wraith`, `cm_rung_wraith_heard`, question 1 only (decision 4 and the strength reference then keep their defaults until R1's page) | the six as built, with questions 2 and 3, if the owner OKs the deviation | Phase 0's check **Taken 2026-09-30: the three were played.** |

## Appendices

**A. Where the numbers live.** The Phase-0 record
([`../plans/2026-09-30-chimera-phase-0-record.md`](../plans/2026-09-30-chimera-phase-0-record.md))
is the durable home of Phase 0: it holds the method, the adopted candidates'
formulas and code excerpts (HIT's coloured gain, the safe capture, TALK, BEND),
the BEFORE/AFTER tables, the prototype source and the paths of the spike
patches. The files behind it sit outside the tree, in one local, read-only
evidence folder, `~/Documents/snipsnap-chimera-evidence-2026-09-29/`. Phase 0's
own files had been in the session scratchpad and were copied into it on
2026-09-30.

- **The earlier evidence** is `chimera-source.md` (the PDFs verbatim);
  `chimera-reports/synthesis.md` and the six lenses;
  `chimera-experiments/struck-shape/report.md`, `struck-motion/results/m1.txt`
  – `m7.txt`, `struck-r2-spec.md`, `hybrid-r2-spec.md`, `bold/steer/notes.md`,
  `bold/talk/notes.md`; `chimera-audition/notes-STRUCK.md`, `verify-r2.md`, the
  manifests and the earlier clips; and `spikes/` with each throwaway worktree's
  patch, to apply on `5e3f5f3e`.
- **Phase 0, copied on 2026-09-30:** `phase0/phase0-work/` (the harness code,
  `Phase0Bank.kt` among it, with `data/`, `data-legacy/`, `p0/`, `tables.md`,
  `findings.json` and a copy of every new test source and the build diffs in
  `code/`); `phase0/PHASE0/` (the six clips); `phase0/fragments-phase0.json`;
  `phase0/design-and-maps/` (`phase0.md`, the Phase-0 report, with the design
  brief, the design maps and the critic); and `spikes/phase0/` (`changes.patch`
  and `new-files/`, to apply on `75b550c1`). The Gradle tasks `:synth:phase0` and
  `:synth:phase0Shape` were registered only in the throwaway worktree
  `snipsnap-phase0` at `75b550c1` (`grep phase0 synth/build.gradle.kts
  shell/build.gradle.kts` is empty on the tree); their text is in
  `spikes/phase0/changes.patch`, the same as `phase0/phase0-work/code/`'s build
  diff. Those two tasks write to the session scratchpad by absolute path, so a
  rebuild must change that path. With these, Phase 0 can be replayed and not
  only rebuilt from the record.

Where the reproduction commands apply, they are `./gradlew --offline
:synth:phase0 -Plegacy=true|false -Pmode=replay,gates,buzz,timing,bold,clips`
(`-Plegacy=true` is the BEFORE chain) and `:synth:phase0Shape -Plegacy=false
-Poutsub=data -Psections=all`, run in a worktree at `75b550c1` with
`spikes/phase0/` applied (phase0 §7).

**B. The round-two clips the owner heard, BEFORE / AFTER** (OB from 20 ms, float
render, dB; time to peak, ms; phase0 T6):

| clip | OB BEFORE / AFTER | peak ms BEFORE / AFTER | class |
|---|---|---|---|
| `cm_unstruck` | −18.12 / −18.12 | 0.20 / 0.20 | TOM |
| `cm_a50_tkick` (subtle) | −21.07 / −21.07 | 0.27 / 0.27 | TOM |
| `cm_a50_wraith` (subtle) | −14.64 / −14.64 | 0.25 / 0.25 | TOM |
| `cm_b_tkick` (strong) | −18.74 / −18.74 | 7.87 / 8.34 | TOM |
| `cm_b_wraith` (strong) | −8.57 / −8.57 | 1.59 / 9.25 (a plateau of near-tied peaks; first-5-ms peak fell 0.6 dB) | PERC |
| `cm_a100_tkick` (HIT 1) | −25.23 / −25.23 | 13.63 / 14.04 | TOM |
| `cm_a100_wraith` (HIT 1) | −11.25 / −11.25 | 0.66 / 0.66 | PERC |
| `rc_a50_tkick` | −44.59 / −44.84 | 0.43 / 0.43 | KICK |
| `rc_a100_tkick` | −46.86 / −52.37 (on the floor) | 20.54 / 21.25 | KICK |

**C. The CHIMERA documents' sections**, for the "as reviewed" table:
`chimera-source.md` §A (the specification: signal flow, three topologies, output
conditioning), §B (`ChimeraPatch.kt` and the execution steps), §C (`Chimera.kt`),
§D (`ChimeraPresets.kt`), §E (`ChimeraTest.kt` and the verification steps).
