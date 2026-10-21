# The pedal board, benchmarked: a sequenced multi-effect, and what the rack takes from it

**Status:** benchmark, 2026-10-21, nothing decided or built. A luthier pass over one plug-in manual the owner supplied (Devious Machines, Infiltrator 2, the maker's own 50-page manual, read in full; three published reviews read for the parts the manual leaves out). The owner asked for two manuals; the second upload was a copy of the luthier skill itself, not a manual, so this document covers one tool and says where a second would change the answer. Repo read at commit `565551e7`.
**Builds on:** the pedal board spec (`2026-10-04-pedal-board-design.md`, Stages 0 to 5), the WAH section (`2026-10-17-wah-section-design.md`, built, gate pending) and the brainstorm of the next pedals (`2026-10-19-pedal-board-brainstorm.md`). Nothing here replaces a numbered stage; the proposals slot in beside them the way the brainstorm's lettered stages do.
**Who this is for:** the owner, then an implementing session. Every proposal says what it reuses, what it costs, what it would be measured by, and what the owner would listen for.

## 1. The tool in one paragraph, as mechanism

Ten effect slots in series, each holding one of about 54 algorithms (four analogue-modelled filter families plus notch, dual and formant; comb, phaser, pitch shift, frequency shift, ring mod, FM, distortion, decimator, delay, reverb, early reflections, vocoder, loop, stretch, varispeed, slow, reverse; EQ; gain, pan, gate, compress, MIDI CC; fourteen FFT effects). Every slot has the same modulation kit: envelope 1 is always wired to the slot's main knob and cycles as an LFO at a synced or free rate, or fires once on an audio peak or a MIDI note; a secondary modulator (envelope 2 or an audio level follower with attack, hold, decay and gate) goes to any of the slot's parameters; the envelope is a drawn, grid-snapped, loopable shape with step editing, curve types, swing, random, quantise, invert and pitch snap to a scale. A 32-step sequencer switches slots on and off per step at a chosen speed, with loop region, rotate and randomise. A master panel outside the chain holds a drive (twelve models, placed before or after the whole chain by one toggle), a compressor (four models), Clean Bass (everything under a set frequency bypasses the whole chain), output gain and a wet/dry mix with a selectable crossfade law. Four macros (eight on demand) each move several parameters with a per-parameter range and curve. Sources: the manual (pages 10, 14 to 47) and the reviews cited at the end.

## 2. The behaviours that define it, and what each means here

Six behaviours, as mechanisms. For each: what it does better than anything in the roster, and the SnipSnap constraint that changes the adaptation (offline, mono, one pad at a time, 3 to 6 macros plus AMT, motion bakes into the render, the rack has no tempo and the KEYED family does).

| | Behaviour (mechanism) | Better than the roster at | What changes here |
|---|---|---|---|
| B1 | **One modulation kit for every effect.** A looping or one-shot envelope on the main knob of any slot; an audio follower or a second envelope on any parameter; retrigger on audio or MIDI (manual pp. 25 to 27, 37 to 39). | Any effect can *move* the same three ways: with the hit, on a clock, once. The rack's sections each carry their own private motion (CONTOUR one sweep from the hit, PHASE an LFO, WAH three moves) and nothing else moves at all (EQ, RING, ECHO TIME, a gain). | The three moves already exist as WAH's TOUCH, AUTO and ROCK weights (`Wah.kt`: `touchAt`, `autoAt`, `rockAt`, `touchPositions`, all `internal` and private to WAH). The adaptation is a shared toolkit, not an envelope editor: the phone draws no envelopes and the app has no per-section dials, so a move is a weight and a shape is a recipe, as the WAH spec decided. |
| B2 | **A step sequencer of effects.** 32 steps, one channel per slot, on or off per step, a speed in note values, a loop region, rotate, randomise (pp. 30 to 32). | Effects that come and go on the grid. That is the tool's identity (its preset folders are BEAT MASHING, GLITCH, EDITS AND FILLS): the *time* at which a pedal is on is the sound. The rack has no such thing: a chip is on for the whole pad. GATE chops the whole pad square. | Tempo lives in the KEYED family (`Keyed.Context.bpm`, `Wobble.periodSec`, GATE's grid and refusal), not the rack. A one-shot shorter than two divisions is refused, as GATE refuses it, so the sequencer is for loops, HOLD pads, the choir and the guitars. And offline rendering removes the tool's own flaw (a reviewer: a sequenced-off reverb cuts its tail): here the pattern can switch the *send* and let the tail ring. |
| B3 | **Audio retrigger with hysteresis and hold-off; envelope loop region; pitch snap.** The envelope restarts on an input peak (trigger level, 6 dB hysteresis, 40 ms hold-off, a trigger filter); loops a chosen region after the trigger; the main knob and envelope snap to a scale (pp. 26, 38 to 40). | Motion locked to the material's own onsets, and a filter that lands on notes. | A one-shot has one onset, so retrigger reduces to "the hit": TOUCH already does that. On a LOOP or HOLD pad each onset could restart the ROCK gesture; the `:audio` module already detects onsets (`Chopper.byTransients`). Pitch snap maps to the kit's key (`Keyed.Context.key`, `Scales.nearestInKey`), which the tool cannot know. |
| B4 | **A master panel outside the chain.** Drive before or after the whole chain by one toggle; a compressor; **Clean Bass** (the low end under a set frequency bypasses every effect); output gain; wet/dry mix with a -6, -3 or 0 dB crossfade law (pp. 10, 24, 40, 41, 46). | A kick keeps its thump through any mangling; the drive's place is one decision, not a routing screen. | The rack already answers "where is the amp" with a fixed slot (VALVE after SQUASH), and Stage 3 is the design for a pedal in front of it. Nothing in the rack keeps a low band clear: a DRIVE or WAH chip on a kick eats the sub. The rack peak-matches every section instead of mixing wet against dry (`FxChain`, no chain-level mix: verified by grep). |
| B5 | **Per-slot mix, gain and glide**, solo, swap, move-insert (pp. 27 to 29). Mix is "how much of this effect"; the knob is "which setting". | The two questions are separate. | On the pad sheet AMT answers both at once: `Treatments.fade` moves every macro toward its neutral by the same fraction. On an exponential law that is the Stage 0 finding (DRIVE at AMT 0.7 is gain 11, at 1 it is gain 1000; the spec flags "20 to 25 times hotter"). The tool's answer to that is a mix knob; the house's is to author recipes so 0.7 reads full (WAH's 1.5 reach stretch). Neither is wrong; see F2. |
| B6 | **Macros with a range and a curve per parameter** (pp. 41 to 44). One knob drops a sound "underwater" by moving a low-pass, a reverb mix and a delay mix together, each along its own curve. | A board that is coherent at every knob position. | A recipe or a board *is* a macro assignment (one knob, many sections), with one fixed curve: linear toward the neutral. The missing half is the curve. A per-macro fade law (log for a gain, linear for a mix) is one field on `MacroSpec` and fixes the DRIVE flag without FULL TAP's dead zone. |

**Also in the tool, dropped here, with the constraint that drops it:** stereo (pan, width, ping-pong, fractional stereo delays: the rack is mono except ENSEMBLE's widening); MIDI notes, CC out, program change, DAW sync, phase lock, latency modes (no host, no transport); the drawn envelope editor and the 32-step grid as UI (no per-section dials on the phone; the CLI is the editor); 600 presets and a dice (the house has `scramble` and the seeded `Shuffle` bank, and authors presets by ear after a gate); a vocoder with an internal carrier (VOX and MURMUR are engines); a quality menu for oversampling (the house measures and fixes 4x for nonlinear sections); the fourteen FFT effects as a set (see Big idea G1: two of them are new here, the rest are covered or out of audience).

**What the tool cannot do that the house does:** render a pad deterministically byte for byte; refuse, in words, a pad too short for the pattern; know the kit's key and tempo without a host; bake a move so the MPC plays it with no plug-in; keep an echo's tail when the pattern switches the pedal off (offline, the send and the return are separate renders).

## 3. The composite: the pedal board that moves

One design, one macro story. From B1 take the shared modulation kit; from B2 the pattern, landed where tempo lives; from B4 the low band that bypasses the chain; from B6 the per-macro fade curve. They compose because each acts on a different axis: the moves on a section's knob over time, the pattern on *when* a section is heard, Clean Bass on *which frequencies* reach the chain, the curve on how AMT reads. Check against the clone test: the tool does modulation, sequencing and clean bass, but it does not bake per pad, refuse a short pad, sequence the send and keep the tail, or snap to the kit's key; and the house does not get its envelope editor, its 32-step grid or its ten free slots (the order stays fixed on purpose). That is the trade, named.

```
 THE PAD (dry, from the bin)
   |
   +--> KEEP LOW: one-pole low-pass at F ----------------------------------+   (F3, a chain door)
   |         low = lp(x); high = x - low  (sums back to x exactly)          |
   v                                                                        |
 high --> the rack in its fixed order (… contour -> wah -> squash -> valve …)|
          each section's knob may now MOVE:                                 |
            pos(t) = TOUCH*p_touch(t) + AUTO*p_auto(t) + ROCK*p_rock(t)     |   (F1, the Moves toolkit,
            (+ RETRIG: p_rock restarts at each onset on a loop pad)         |    lifted from WAH)
   |                                                                        |
   v                                                                        |
 wet -----------------------------------------------------+                 |
                                                          |                 |
 STEPS (keyed, reads the kit's bpm and division): --------+--> out(t) = s(t) ? wet : dry   (T1)
   s(t) from a pattern string "x.x.xx.." on the grid, 3 ms raised-cosine edges,
   the pattern switches the SEND into the tail-making sections, so echo and spring ring out
                                                          |
                                                          v
                                                     + low ----------------------------------+
                                                          |
                                                     peak-matched to the dry pad's peak
```

### 3.1 The macro story

Nothing new for the novice to learn on the pad sheet. A chip is still one tap; AMT still means "how much"; the moves are weights inside recipes; the pattern and the keep-low frequency are CLI dials first (`Keyed.Dials`, the way WOBBLE's division is) and sheet doors only after a gate says they are worth a chip. Macros stay 0..1 with a neutral where they do nothing, as the rack contract requires.

| Control | Lives in | Default / neutral | What it changes in the model | Proves it moves |
|---|---|---|---|---|
| TOUCH, AUTO, ROCK | any section that adopts the Moves kit (today only WAH) | 0 / 0 | the weight of each move on the section's main knob | the WAH tests, copied per section: centroid swing, period, open-then-close |
| RETRIG | the Moves kit, loop pads only | 0 / 0 | how much each detected onset restarts the ROCK gesture (hysteresis 6 dB, hold-off 40 ms as the tool's defaults, then measured) | on a two-hit loop, two rocks; on a one-shot, bit-identical to ROCK |
| SPEED | the Moves kit | 0.5 / 0.5 | the LFO rate, the follower's release, the rock's length together (WAH's law) | WAH's SPEED tests |
| pattern (dial) | `Keyed.Dials`, CLI `treat` | none | the step string on the kit's division | the render equals dry on off-steps and wet on on-steps, to the frame, outside the 3 ms edges |
| keep-low (dial) | the chain door | off / off | the crossover frequency under which the pad bypasses the rack | low band of the output within 1e-6 of the low band of the dry pad |
| fade law (per macro) | `MacroSpec`, read by `Treatments.fade` | linear | how AMT walks a macro toward its neutral (log for a gain) | THD or gain versus AMT roughly linear in dB on a steady sine |

## 4. Findings, ranked

Each: the observation with its source, why it matters for the sound or the architecture, what to try, and what it costs. "Measured" here means a grep on the named commit; no audio was rendered for this document.

### Fix now

**F1. The three moves are the tool's whole modulation story, and they are private to one section.** `Wah.kt` lines 82 to 124 hold `touchAt`, `autoAt`, `rockAt`, `touchPositions` and `reach` as `internal` members of `Wah` (verified). The tool wires the same three modulators to every slot's main knob; the house has them once. This is the recurring finding "a private helper copied between engines is where the next toolkit lives". Try: lift them into a `Moves` object in `synth` (the position law, the reach stretch, the follower, the gesture, the one-cycle floor the brainstorm's SHORT PAD wants), keep WAH bit-identical (its tests pin the sweep's numbers), then let the staged TREMOLO be "AUTO on a gain", the staged FLANGER's and VIBRATO's LFO be the same `autoAt`, and CHOKE's close be "TOUCH on a gain, inverted". Cost: a refactor with no audible change, S; the win is that Stage 4's two sections and the brainstorm's VIBRATO, CHOKE and TALK stop writing their own LFO and follower. Hazard: WAH's goldens; do it before Stage 4, not after.

**F2. AMT conflates "how much pedal" with "which setting", and the tool keeps them apart.** Per-slot Mix (manual p. 28) is an output crossfade; the main knob is the setting. `Treatments.fade` (verified, `Treatments.kt`) is a parameter fade, so a hot amp at AMT 0.7 is a different amp, not the same amp quieter: the Stage 0 "not enough" and the spec's "20 to 25 times hotter at 1 than at 0.7" are both this. Two honest answers, pick one per recipe: (a) author values so 0.7 reads full (the WAH's stretch, the brainstorm's section 7 question 7), or (b) a fade law per macro (B6): `MacroSpec` gains an optional law (`LINEAR`, `LOG`), and `fade` walks a LOG macro in the section's own gain domain, so DRIVE at 0.7 is 70 percent of the way to gain 1000 in dB, not gain 11. (b) is one field, one `when`, one test (THD versus AMT on a 220 Hz sine, monotone and roughly linear in dB) and it does not touch the contract "AMT 0 lands on every neutral". It also answers the brainstorm's FULL TAP without the dead zone. Cost: S. Not a wet/dry crossfade: that would break "AMT 0 is the neutral" and the peak-match contract, and is declined here.

**F3. KEEP LOW: the low end bypasses the chain.** Clean Bass (manual pp. 40, 41): everything under a set frequency passes unaffected; the reviewer's use is a kick through a drum-bus mangling. The rack has nothing like it (grep for crossover, split, keep: only Tonewheel's rotary crossover and SMEAR's FLOOR band, verified); a DRIVE chip on a kick drives the sub, and the brainstorm's SUB notes a phone speaker cannot even hear the result. Try: a chain door, not a section: `low = OnePole.lp(x, F)`, `high = x - low`, the rack runs on `high`, `low` is added back after it, then the usual peak match. A first-order complementary pair sums back to the input exactly (by construction, `high + low = x`), so there is no Linkwitz-Riley crossover to design and no phase hazard at the seam; the price is a 6 dB per octave slope, which for "keep the thump" is the right trade (a steeper split would need the matched pair and a test that it sums flat). Lands in `Treatments.apply` beside the pre-amp fade, so the chip, the CLI `treat` and the recorded character all get it; `PadRecipe.process` does not (the same disclosed edge as the fade). Dial: a frequency, CLI first (120 Hz default, the tool's), a sheet door later. Hazards: VALVE normalises to its input's peak, and the high band alone peaks lower, so the amp drives harder than before at the same AMT (measure THD with and without); a pad whose whole energy is under F is passed untouched and the chip appears to do nothing (say so in words, the ROLL and GATE way). Gate: a kick through DRIVE at AMT 1 with keep-low on and off; a kick through WAH; the choir (should be unchanged above 120 Hz). Cost: about 40 lines plus tests, S. Serves the novice (the kitchen kick) and the sampler audience on day one; the guitar audience is unaffected by design.

### Try next

**T1. STEPS: the pattern, landed in the KEYED family.** The tool's 32-step on/off per slot (manual pp. 30, 31) is its identity, and the rack cannot do it. Try: a keyed treatment `stepped` whose dial is a pattern string on the kit's division (`Keyed.Dials.pattern`, "x.x.xx.." on `Wobble.DIVISIONS`), AMT its depth, GATE's 3 ms raised-cosine edges and GATE's refusal (a pad shorter than two divisions). It needs two renders, dry and wet: the keyed door today gets one `Snip`, so this is a `KitBuilder` composition (the sheet already restores the bin's original before a chip, and `rewriteEveryFile` is the door), the same shape as the brainstorm's MERGE. The offline win: the pattern switches the *input* to the tail-making sections (ECHO, SPRING, DUB), so a sequenced-off echo rings out instead of cutting, which the tool's reviewer names as a flaw. Gate: the choir with WAH on "x.x.x.xx": the wah comes and goes on the beat and the spring tail survives the off-steps? A JANGLE chord with DRIVE on "xx..xx..": a rhythm, or a chopped guitar? Hazards: the one-second tail cap across a pattern of many on-steps; which tempo a pad renders at when the kit's BPM changes later (the WOBBLE rule: it bakes at the tempo it was treated at); the KEYED row has two free chips and PUMP, SHIMMER and HOWL want them. Cost: M. The owner has not asked for this; it is the one thing in the tool the roster cannot approach, so it is the first thing to listen to from this benchmark.

**T2. RETRIG on loop pads.** The tool's audio retrigger restarts the envelope at every peak above a threshold, with hysteresis and hold-off (pp. 38, 39). On a one-shot it is the hit, which TOUCH already follows. On a LOOP or HOLD pad, or a chopped break, each onset could restart ROCK, so a two-bar loop through WAH rocks on every snare. The `:audio` onset detector exists (`Chopper.byTransients`); the tool's defaults (6 dB hysteresis, 40 ms hold-off) are a starting point to measure, not a spec. Lands as a weight in the Moves kit (F1 first). Gate: a drum loop through WAH ROCK with RETRIG: one wah per hit, no double triggers on a flam? Cost: S after F1.

**T3. The amp's position is one toggle, not a routing screen.** The tool's only routing freedom is Drive before or after the whole chain (p. 24, Figure 1 on p. 10). That is the exact shape Stage 3's composition rule should expose for a guitar pad: "this pedal before the amp or after it", decided per pedal by its slot (the spec already fixes which), with no further routing. This finding adds nothing to build; it is evidence for the spec's own rule when the Stage 3 brainstorm is tempted by a routing screen.

**T4. A wah that lands on notes.** Pitch snap (p. 26): the envelope and the main knob snap to a scale, so a filter sweep becomes an arpeggio. The house knows the kit's key (`Keyed.Context.key`, `Scales.nearestInKey`, verified). Try: a keyed form of WAH whose centre path is quantised to scale degrees between heel and toe, holding each for a division. Gate: the choir through it in a keyed kit: an arpeggiated filter, or a wah with steps? Cost: S after F1 and T1 (it is the pattern applied to the centre rather than to the send). Parked until WAH passes its gate.

### Big idea

**G1. Two spectral effects the roster lacks, in a rack where latency is free.** The tool's FFT set (pp. 21 to 24) pays in latency (its manual warns, its reviewers advise delay compensation). Offline, that cost is zero, and `Pghi` exists in `:audio` (ETERNAL uses it). Most of the fourteen are covered or out of audience (Smear and Reverb by SMEAR and SPRING, Stretch by ETERNAL and SPEED, Shift and Rotate by RING in spirit). Two are new here and cheap on top of PGHI: **Corrupt** (keep only the loudest or only the quietest partials, by an amount) and **Lofi** (drop a fraction of harmonics, re-chosen at a rate). Both are the novice's "make it broken" tap and neither serves the guitar audience, so they wait behind the pedal board; the inventory's own thin list names spectral engines, and this is the FX-side half of that gap. Cost: M each, a 1-second tail story, the PGHI phase care.

**G2. A board is a macro with curves.** Once F2 exists per macro, a board (the brainstorm's RIG, WALL, BOOM) can carry a law per member, which is what the tool's macro editor gives per parameter (p. 43: "adjust the curve … to create swooping and whooshing effect changes"). This is the brainstorm's SOUNDS row made coherent at every AMT, with no FULL TAP. Decide after F2 has been heard on DRIVE.

### Disclosed, not fixed

- **Nothing in this document was rendered.** The numbers quoted are the tool's (its manual) and the repo's own measured ones (the pedal board spec, the WAH spec). The first measurement to take is F3's: a kick through DRIVE with and without keep-low, THD and the sub band's deviation.
- **A second manual would have changed section 3.** The composite above is one tool against the roster. If the second tool the owner meant was in the same family (another sequenced multi-effect), the shared core (B1) stays one implementation and only its unique behaviours would be added; if it was a different family (a pedal, an amp), it would land in the pedal board spec's own stages. Re-upload it and this benchmark gains a column.
- **The pattern's tempo bakes.** T1 renders at the kit's BPM at the time of the tap, as WOBBLE, ROLL and GATE do; a later tempo change does not re-render. The house has accepted this for the keyed family and should say so again in T1's spec.
- **The KEEP LOW door is not the door for every consumer** (BREED's children, a patch recipe's own render, the remix bank run the bare chain), the same edge the pre-amp fade carries and Stage 3 must decide.

## 5. Where each lands (the lightest landing that keeps the gesture)

| Proposal | Landing | Why not lighter |
|---|---|---|
| F1 Moves kit | a `synth` toolkit object; no new section, no chip | it is a refactor; the sections that adopt it are the staged ones |
| F2 fade law | one field on `MacroSpec`, one branch in `Treatments.fade` | a recipe-only fix (author 0.7 as full) is lighter and is also offered |
| F3 KEEP LOW | a chain door in `Treatments.apply` with a CLI dial | a section cannot see the whole chain; a recipe cannot bypass it |
| T1 STEPS | a keyed treatment plus a `KitBuilder` composition | the rack has no tempo; the keyed family refuses in words |
| T2 RETRIG | a weight in the Moves kit | a fourth WAH weight would be private again |
| T4 note wah | a keyed form of WAH | the rack has no key |
| G1 spectral | sections, later | no recipe over existing sections makes them |

## 6. A proposed order, beside the spec's stages

1. **Before Stage 4:** F1 (the Moves kit, no audible change, so TREMOLO and FLANGER are written against it) and F2 (the fade law, heard on DRIVE at the Stage 0 phone listen if it is still pending: the same recipe at AMT 0.7 and 1 with the law linear and log).
2. **With Stage 2's compressor or on its own, S:** F3 KEEP LOW, gated on a kick through DRIVE and through WAH.
3. **After the WAH gate passes, M:** T1 STEPS, the one listen that tells the owner whether "effects on the grid" is a product the novice taps, with the spring-tail-survives rule as its first test.
4. **Then:** T2 and T4 as small follow-ons; G1 and G2 after the pedal board's numbered stages.

## 7. Ledger

- **Verified** (read in the repo at `565551e7`): the rack order and `wah`'s slot (`FxChain.kt:190-209`); WAH's moves as `internal` members (`Wah.kt:82-124`); `Treatments.fade` as a parameter fade toward neutrals and `Treatments.apply` as the chip's door with the pre-amp fade (`Treatments.kt`); no chain-level mix or crossover in `FxChain` or `synth` (grep: only Tonewheel's rotary crossover and SMEAR's FLOOR); the KEYED family's `Context(key, bpm)`, `Dials`, refusals, and GATE's `EDGE_SEC = 0.003f`, `MIN_DIVISIONS = 2` (`Keyed.kt`, `Gate.kt`); `Wobble.periodSec` and `DIVISIONS`; `PadRecipe(patch, fx)` and its bare `process`; `Chopper.byTransients`; `Scales.nearestInKey` and `KeySpec`; the PEDALS row holds DRIVE and COMP (`PadSheet.kt:100`). The skill's minimum grep set: `siblingPatches` absent; `Keys.MAX_SEAM_ERROR` present; `Velocity.kt` arms present.
- **Measured:** nothing in audio. The tool's numbers are its manual's; the repo's are the two specs' own.
- **Proposed:** every landing, dial, default and hazard in sections 3 to 6; the tool's retrigger defaults as starting values.
- **Heard:** nothing. The owner hears F2 on DRIVE and F3 on a kick first; T1 is the listen that decides whether the tool's identity belongs in the product.

## 8. Why it is built this way

- **The moves become a toolkit before anything else** because the tool's single idea is that one modulation kit fits every effect, and the house already wrote that kit once, inside WAH. Copying it into TREMOLO and FLANGER would be the private-helper defect the review rubric looks for; lifting it first costs a refactor and buys every later pedal its motion for free. If it were skipped, each new section would carry its own LFO and follower with slightly different laws, and the sheet would stop feeling like one instrument.
- **AMT gets a law per macro rather than a wet/dry knob** because the rack's contract says AMT 0 is the neutral and every section is peak-matched; a crossfade would break both and the tests that pin them. A log law keeps the contract and removes the one place AMT lies (an amp's gain). If the law were left linear, every hot recipe would keep needing a hand-stretched value to read as itself at the first tap.
- **KEEP LOW is a door, not a section,** because it has to see the whole chain (the low band goes around every section) and a first-order split sums back exactly, so the cheapest version is also the correct one. The risky decision is the amp: with the sub removed the amp sees a lower peak and drives harder, which is a measurement to take before the gate, not a reason not to build it.
- **The pattern lives in the KEYED family and switches the send,** because the rack has no tempo and the keyed family already refuses pads too short for a grid in words; and because rendering offline lets the echo's tail ring through an off-step, which the real-time tool cannot do. That is the one place the house can be better than the reference rather than a port of it.
- **The one risky decision** is building STEPS at all: the owner has not asked for sequenced effects, and the audience that taps it first is the novice with a loop, not the guitarist. It is ranked first among the "try next" items because it is the one behaviour in the manual the roster cannot reach by any recipe, and a single gate on the choir settles it.

## 9. Prompt notes for the owner

The ask named two manuals; one arrived (the other upload was the skill). Three things would sharpen a second pass: the second manual itself; which audience the benchmark should serve first (the guitar pads, the novice with a loop, or the drum kit, since F3 serves drums, T1 serves loops and F1 serves guitars); and whether sequenced effects are wanted at all, since T1 is the only item here that is new product rather than a better pedal. Example prompts in the house shape: "Spec F1 and F2 from `docs/superpowers/specs/2026-10-21-pedal-board-benchmark.md` as one refactor PR with WAH bit-identical and the DRIVE THD-versus-AMT test; no new sections." Or: "Write the STEPS spec in the shape of the WAH spec: the keyed door, the pattern dial, the send-switch rule for ECHO and SPRING, GATE's edges and refusal, and a gate on the choir and JANGLE."

## Sources

- Devious Machines, *Infiltrator 2 User Manual* (the owner's upload; also at `deviousmachines.com/manuals/Infiltrator Manual.pdf`, 50 pages): routing and slots p. 10; filters pp. 14, 15; effects pp. 15 to 20; utilities pp. 20, 21; spectral pp. 21 to 24; master overdrives and compressors p. 24; effect controls and modulation pp. 25 to 28; sequencer pp. 30 to 32; envelope editor pp. 32 to 36; LFO, retrigger, loop pp. 37 to 40; master panel, macros pp. 40 to 44; output mix mode p. 46.
- Jim Aikin, "Devious Machines INFILTRATOR 2 — the Synth and Software Review", synthandsoftware.com, October 2022 (ten slots, 54 algorithms, series only; the sequenced-off reverb cuts its tail; no modulation matrix; four macros).
- Attack Magazine, "8 Ways We Love To Use Devious Machines Infiltrator 2" (pitch snap as a phrase maker; the follower on resonance for a per-note wobble; Clean Bass on a drum bus).
- Pluginerds, "Devious Machines Infiltrator 2 Review" (twelve drive models, four compressors, the bass bypass, eight macros in later versions).
- Reviews disagree on the macro count (four in the manual's default view, eight after the "+" button; later versions ship eight). The manual is taken as authoritative for this document.
