# CUPOLA: design brief

Status: concept plus a Python prototype. The owner has heard and approved the TUBES voice (v5) and
called the steel voices "decent"; nothing else is heard. No Kotlin written. CUPOLA is a working name.
Prototype: [`prototype/cupola/`](../../../prototype/cupola/README.md). The listening packs named in the
ledger were made during the design session; `prototype/cupola/ab_v5.py` regenerates the last one
byte for byte (checked), and the reference recordings stay outside the repository (`refs.py`).
Repository truth: SnipSnap `ffbdda54` while designing (110 engines registered in `Patches.kt`); every
cited line re-checked on `ab051130`, where 112 are registered. APIs grepped and
cited by file and line below; anything not cited is a proposal.

The characteristic gesture is **hand strike → the note blooms into its octave and fifth → the
scale answers → one shared halo fades**.
For the tubes: **paddle push → the open column sounds every harmonic at once → the upper ones fall away → the low fundamental rings last**.

## What it is

One instrument body tuned to a scale. Every note of a handpan, tongue drum or tank drum is a
field on one shared steel shell. Strike one field and the others that share its overtones start
ringing too, so a phrase leaves a halo in key. The slapped air tubes are a bundle of tuned
tubes on one frame: the same idea in air, much shorter. The scale comes from the preset (SCALE,
a stepped macro like SILK's), and the struck note comes from the key you press. A chromatic note
gets almost no answer, because nothing in the scale shares its overtones. Nothing is scheduled;
the answer comes from which frequencies coincide.

## What the owner's references measured (Python harness, loop-level, notes overlap)

| Family | Overtones | Ring | Other |
|---|---|---|---|
| Handpan (7 loops, F minor) | near 2× and 3× (1.996; 2.88 to 3.07) | 1.5 to 8 s | a component at about 87 Hz under an F key (a cavity); attack darker than body (595/863 Hz) |
| Tongue drum (6 loops, D major) | 2.008, 4.015, 6.013: almost exactly harmonic | 5 to 30 s | in-tune to ±7 cents; neighbours ring along |
| Tank drum (11 loops, G/A/C minor) | nearly pure; others 15 to 35 dB down | 1 to 2 s | a component at 0.81 to 0.88 of each note, about 65 to 75 Hz: a cavity |
| Slapped tubes (13 loops, 2 one-shots; 61 isolated hits) | a full harmonic series on a low fundamental, which open ends radiate weakly, so the 2nd harmonic is loudest; a dip at the 3rd (clean one-shot: −3.6, 0, −21, −15, −10, −7.5 dB for harmonics 1 to 6) | clean one-shot: fundamental T60 0.49 s, upper harmonics 0.15 to 0.29 s; the hit falls 20 dB in 121 ms | open at both ends, confirmed by the owner. **Corrected twice:** round 2 read whole loops, whose strong peaks were the other tube of each pair; v2 and v3 then read the low fundamental as a sealed end, and the owner heard them as closed |

## Why it earns a pad

Nearest siblings: HALOBAR (independently damped bars, a tubular-bells voice, echoes outside the
bars), TERRA (one world-percussion object per render, tuned bar and cavity voices), TESSERA (a
TUBE voice in a mixed struck object), TINES (an FM kalimba). Each treats a note as its own object.
None has a body tuned to a scale where one strike sets off the others, and none has a slapped air
column. GYRE and ARCO have sympathetic strings, but on strings with a fixed tuning, not a
playable scale layout on one shell.

## Signal flow

```
key (midi), velocity, take, macros (TUNE SCALE TOUCH BLOOM SYMPATHY RING)
      │
      ▼
[CupolaLayout: TUNE + SCALE → note fields; struck note = midi]  [cache: TUNE, SCALE, RING, SYMPATHY]
      │ field list, mode table per voice, springs between modes < 60 cents apart
      ▼
[CupolaContact: hand pulse (fixed impulse = velocity, width from TOUCH, velocity),
                rebound and strike position from the take]──(force @176.4 kHz)──▶
                                                                                    │
           ┌────────────────────────────────────────────────────────────────────┘
           ▼
[Shell: Modes.Bank, every field's modes, springs kappa from SYMPATHY]  ⇄ two-way, passive
           │   + BLOOM inside the struck field: F_oct = −β·q1², F_1 = −2β·q1·q_oct (two-way in Kotlin)
           │
           ├──▶[Cavity: one air mode under the shell]   one-way readout
           ▼
[radiation weights per mode] ──▶ [house output: Tide.bandLimit → Dsp.decimate → DC → levelTo → fadeTail]

TUBES (v5, owner-approved over v4 "both ways"): mapping B, the fundamental F = key - 12
       paddle: a fast rise (0.66 ms) and a 2.6 ms release, the same on every tube, plus a puff of
         air (noise) into the same column
       → the Shell holding the open-open harmonics k·F, driven at 0.049 of the length, radiated
         k^1.10 up to 553 Hz then flat, odd harmonics x (1-0.864)/(1+0.864) (the two open ends
         push in opposite directions on odd harmonics), T60 = T1/k^0.77
       → bundle neighbours (SYMPATHY), the paddle's pop and the puff heard directly. No seal.
       Kotlin: Modes.Bank, as the steel voices.
```

## Macro contract

Measured columns: Python harness, round 3; "activity" is the house log-spectral distance between
0 and 1, levelled.

| Control | Default | Changes which model quantity | Required interaction | Measured |
|---|---:|---|---|---|
| TUNE | .50 | the key root the fields tune to (±12 semitones); the struck note is the key you play | SCALE | root within 2.4 cents on every steel voice, 8.6 on TUBES |
| SCALE | per voice | which notes exist on the shell (stepped: KURD, CELTIC, MAJOR, MINOR_PENT, PENT, HIJAZ) | SYMPATHY: what can answer | 1.8 to 3.8 dB on a four-note walk; 0 on the key root alone, because every layout keeps the root and its octave |
| TOUCH | .50 | contact width, palm to fingertip (paddle softness on TUBES); skin noise colour | velocity shortens the contact further | 2.8 to 8.5 dB (TUBES v4: 10 dB) |
| BLOOM | .50 | quadratic transfer β from the fundamental into the octave and the next mode (on TUBES: where along the column the push lands, full to hollow) | velocity: bloom grows with amplitude squared (steel) | handpan octave −8.4 → −2.5 dB and fifth −14.5 → −5.0 dB re root at 300 ms; whole-spectrum activity under-reads it (0.4 to 0.7 dB) | TUBES v4: 6.9 dB, low to high |
| SYMPATHY | .50 | spring stiffness kappa between near-coincident modes of different fields | SCALE, RING | in-scale answer −8 to −11 dB of the struck energy on the handpan and tongue; chromatic −23 dB (handpan), −87 dB (tongue). DING's chromatic test note (F#2) answered as strongly as an in-scale one, because its third overtone lands on the scale's D-flat |
| RING | .50 | every T60 (palm-muted to open); render length | SYMPATHY: a longer ring lets answers build | 0.8 to 2.5 dB |
| HOLD | 0 | reserved in round 1 (see below) | | |

Six macros plus HOLD, inside the house's 3 to 6. Every name is a plain word; none is the engine's name.

## Voices

| Voice | Body | Default key, scale | Status |
|---|---|---|---|
| HANDPAN | steel shell, fields at 1:2:3, cavity at half the key | F3, KURD | prototyped |
| DING | the low centre note, stronger cavity just under it | F2, KURD | prototyped |
| TONGUE | tongues at 1 : 2.008 : 4.015 : 6.013, very long ring | D3, MAJOR | prototyped |
| TANK | nearly pure fields, cavity at 0.72 of the key | G2, MINOR_PENT | prototyped |
| TUBES | open-open air columns struck by a paddle, in a bundle; the key names the loudest partial | C4, PENT | **v5 heard and approved by the owner** |
| sixth (after the listen) | candidate: a bass tube. Peaks at 77 to 80 Hz recur in three tube loops; read as open tubes, those are low fundamentals, so a long bass tube is the same model at a low note | | not built |

The owner's melodic gate needs six distinct voices. Measured distance between the five default
renders (full-length house measure): 40 dB (HANDPAN–DING, the closest) to 109 dB. That is a
distinctness number, not a listening verdict.

## The three directions

1. **Strong (COUPLE): one coupled shell per render.** As above. The answer to a strike emerges
   from coincident frequencies in a passive network, `Modes.Bank` with `connect(i, j, kappa)`
   (`Modes.kt:496`, `:535`), stability bound `MAX_NODE_KAPPA = 1.0` (`Modes.kt:869`). Gesture
   clear; new against the roster; moderate render-time risk; about four rounds.
2. **Alternate A (SPLICE): new mode tables on HALOBAR or TERRA plus a one-way sympathetic bank
   after the strike.** Cheapest. The prototype's first round tried exactly this readout and it
   failed by measurement: the tongue's octave sits 6 cents from the next tongue, so a one-way
   drive answered at −34 dB, and on TANK a chromatic note drew as much answer as an in-scale
   one (−22 against −24 dB). Coupling has to be two-way to pull near-miss modes together.
3. **Alternate B (INVERT): the cavity plays the shell.** The air under the shell becomes a
   two-way partner of every field, so a strike thumps the air and the air re-excites the ding:
   closer to the underside "port" sound. Riskier, because a broadband air coupling moves every
   note and needs its own stability argument. Worth trying only after Strong is heard.

Strong wins because it is the only direction whose defining behaviour (in-scale notes answer,
chromatic notes do not) the prototype measured working.

## HOLD and roll

Proposal: HOLD reserved in round 1, with a test that says so (Gyre's pattern). These are struck
instruments: a seamless loop of a decaying hit is not the instrument. The owner's percussion
gate asks for an authored roll or riser where appropriate. Handpan and tongue players roll
with alternating hands, so a finite ROLL (alternating takes, rising velocity, the shell keeping
its halo through the whole roll) is the round-3 candidate for HOLD's top step, after the
one-shots are heard. SnipSnap's `Roll.kt` is a keyed treatment that re-triggers a rendered hit
on the kit grid. It is not the same thing: it cannot keep one shell ringing across strokes.

## Velocity and round robin (the owner's end-to-end gate)

- **Velocity changes the physics, not the level.** The pulse carries a fixed impulse equal to the
  velocity; a harder hit is a shorter contact (brighter) and blooms more (amplitude squared).
  Measured soft (.25) against hard (.95) on the port of `CalabashTest.distanceDb`
  (`CalabashTest.kt:192-238`): 2.7 to 5.1 dB, every voice over that test's 2 dB bar.
- **Round robin is the take.** The take is a seed (`Dsp.seedFor`, `Dsp.kt:163`) that moves the
  strike position across the field, the finger rebound (delay 1.2 to 7 ms, weight 6 to 35 %) and
  the contact width. Closest pair of four takes: HANDPAN 1.94, DING 1.21, TONGUE 1.63, TANK 1.39,
  TUBES v4 1.44 to 2.67 dB (dealt, see below). Round 3's tubes measured **0.9** dB. CalabashTest's bar is 1.0 dB, set after the owner heard takes at 0.5 to 0.8
  dB as one strike (`CalabashTest.kt:64`). The tube failure comes from independent random draws
  landing close (takes 0 and 3). **Proposal: deal takes from stratified positions** (take k
  takes bin k of the position range and a different rebound bin) so four takes are apart by
  construction, then prove it with the same test.
- The gate runs through rendering, saving, playback and export. This brief only covers rendering.

## Risks

1. **BLOOM made energy in the prototype.** The Python bloom is a one-way readout: the octave
   gains energy the fundamental never loses. With BLOOM and SYMPATHY both at the top, DING's
   later level reached 1.27 times its attack. BLOOM's cap of 3.0 is the largest depth tried
   where BLOOM alone never grows. Kotlin fix: the two-way quadratic force pair above, from one
   potential (β·q1²·q2), applied each sample through `Bank.drive` (`Modes.kt:646`). Its test:
   `Bank.energy()` (`Modes.kt:702`) never rises after the strike.
2. **Output can swell while energy falls.** At SYMPATHY 1 the tongue's output rose by 0.5 dB
   after the attack, because energy moved from a weakly radiating overtone into a neighbour's
   strongly radiating fundamental. That is the shimmer, and it is physical. The never-grows probe
   should read the bank's energy; the output swell gets its own ceiling.
3. **Render time.** In numpy, 0.4 to 3.9 s per note (an exact eigen solve plus three filter passes
   at 176.4 kHz). The Kotlin cost is unmeasured: about 50 modes and 22 springs stepped
   per sample for 4 to 6 s. Mitigations: keep only fields connected to the struck note by a spring
   path (the rest never move, so this is exact), cut the render at −60 dB, cache the layout.
   Cheapest proof: one Kotlin bench of the HANDPAN default before anything else.
4. **Length files as LOOP.** The classifier files anything over 1.5 s as LOOP
   (`Classifier.kt:81`). A handpan note rings 4 s and a tongue note 6 s. Either class by length
   (OTIS's "P/L by length") or cut the one-shot. That is a decision for the owner, because a cut
   loses the halo.
5. **Low voices may vanish on a phone.** Share of energy above 150 Hz: HANDPAN 100 %, TUBES v4 75 % at C3, 56 % at the one-shot's 66 Hz,
   DING 34 %, TONGUE 7 % (its D3 root is 147 Hz), TANK 1 %. TANK at G2 is nearly a pure 98 Hz tone,
   so a phone will not play it. The OCTAVER pedal's history in this repo says the same thing.
   Candidate: lift TANK's second mode or its cavity, and judge it by ear.
6. **Tube register: decided, B (owner, 2026-11-21: "I guess b").** A key names the tube's loudest
   partial, its 2nd harmonic; the tube's fundamental sits an octave under the key, as the sample
   pack names its tubes. The root test checks the loudest partial (the key), and says so.
   **Hard hits attack more sharply than the references:** at full level the C/F phrase still has 12
   sample steps over 0.08 at note starts; the owner's D_C loop has none. Not heard as a fault yet.
7. **Trademarks.** "Hang" and the maker names behind the tongue and tank references must not
   appear in engine, voice or preset names. Add them to `PresetTestSupport.trademarkBlocklist`.
   "Handpan" and "tongue drum" are generic.

## Integration (proposed rows, checked against the repo)

- Keygroup: a `Keys.cupolaPad(voice, macros, midi, cancelled)` builder mirroring
  `Keys.mercuryPad(voice, macros, midi, cancelled)` (`Keys.kt:351`). The shape fits: the preset
  keeps the shell in its key while each key strikes its own note.
- Velocity: arms in `Velocity.macroSpecsFor` (`Velocity.kt:225`) and `touchedRender`
  (`Velocity.kt:450`), with velocity reaching the contact physics.
- Round robin through kits: follow an existing `*RoundRobinKits.kt` (TERRA, TEMPEST, ACCORD
  exist).
- The rest is the standard registration checklist (patch file, `Patches` arm, presets after the
  listen, kit, audition generator, determinism canary, docs, picker).

## Cheapest proof, next

1. Owner listens to the five phrases next to the reference loops and says which voice is closest
   and which is furthest. (Done for the tubes, below; the steel voices were called "decent".)
2. One Kotlin bench: HANDPAN default note on `Modes.Bank` with the two-way bloom, measuring
   milliseconds and `energy()`.
3. Then round 1 proper: HANDPAN and TUBES only (the two body types), the probe set, the
   stratified round robin.

## Verified / measured / proposed / heard ledger

| Claim | Status | Evidence |
|---|---|---|
| `Modes.Bank`, `connect`, `drive`, `energy`, `MAX_NODE_KAPPA = 1.0` exist | verified | `Modes.kt:496, 535, 646, 702, 869` at `ffbdda54` and `ab051130` |
| Keygroup builders take (voice, macros, midi) | verified | `Keys.kt:351` |
| SCALE as a stepped macro has precedent | verified | `Silk.kt:83`, `:264` |
| Classifier files over 1.5 s as LOOP | verified | `Classifier.kt:81` |
| References: handpan 1:2:3, tongue 1:2.008:4.015:6.013 | measured | Python harness on the owner's loops; overlapping notes, so ±1 % |
| Tubes v4 against the clean one-shot: falls 20 dB in 121 ms (121); partial T60 0.65/0.27/0.24/0.21 s (0.49/0.29/0.22/0.19); 3rd-harmonic dip −13 (−21); fundamental +4 (−3.6) | measured | `fit_v4.py`, 110-trial search, residual 4.1 dB |
| Tubes v4: roots within 1.1 cents, re-attack 0.0 to 0.1 dB (one strike), takes 1.44 to 2.67 dB apart, velocity 4.6 to 4.9 dB, never grows | measured | Python ports of the house probes |
| **TUBES v4 sounds right (open, coherent)** | **heard** | owner, 2026-11-21, on the session's `listen/tubes_ab3/`: "Yes. That's it" |
| v2 and v3 tubes sound closed | heard | owner: "The tube is open ended, but those sound like it's not" |
| Steel voices (HANDPAN, DING, TONGUE, TANK) are decent | heard | owner, 2026-11-21: "other voices and decent", on the round-3 phrases; a first verdict, not a close comparison with the references |
| Steel v2 closer to the loops by resynthesis: third-octave spectral error handpan 28 -> 8 dB, tongue 22 -> 8 dB, tank 20 -> 8 dB; ring 300 ms after a hit within 2 dB of the references; pitch within 2.1 cents, takes 1.4 to 2.5 dB apart (dealt), velocity 2.8 to 5.1 dB, never grows | measured | `prototype/cupola/fit_steel.py`, `steel_ab.py`; the transcription snaps notes to each file's scale, so a few notes differ |
| Steel v2 sounds closer to the references | not heard | owner listen pending |
| Tube register: a key names the loudest partial (mapping B) | heard | owner, 2026-11-21: "I guess b", on the session's `listen/tubes_register_ab_long/` |
| v4 tubes do not sound tubular in the higher notes | heard | owner, 2026-11-21; measured cause: above the fitted 66 Hz note v4 lost the octave-down fundamental and its odd partials, and its attack got sharp (contact time shrank with the period) |
| **TUBES v5 is better than v4, high and low** | **heard** | owner, 2026-11-21: "Is better both ways", on the session's `listen/tubes_v4_vs_v5/` |
| TUBES v5 across G3..G5 (mapping B): loudest partial on the key at all six keys; octave-down fundamental −9 to −21 dB (owner's hits −15 to −28); attack above 2 kHz −18 to −25 dB (−23 to −34); re-attack ≤ 0.2 dB; takes 2.5 to 4.2 dB apart; velocity 3.6 to 6.8 dB; never grows | measured | `search_v5.py` (100 trials, residual 8.0 dB), Python ports of the house probes |
| At the one-shot's pitch v5 sits further from the one-shot than v4 did (fundamental −21 against −3.6 dB) | measured, then overruled by ear | the owner preferred v5 there too |
| In-scale notes answer, chromatic notes do not | measured | Python harness: handpan −8 / −23 dB, tongue −11 / −87 dB |
| Velocity and take distances | measured | Python port of `CalabashTest.distanceDb`, not the Kotlin test |
| Every root within 2.4 cents (steel), 1.1 cents (tubes v4) | measured | Python harness pitch probe |
| Two-way bloom never grows | proposed | none yet; Kotlin bench |
| Kotlin render time | proposed | none yet; Kotlin bench |
| Any of it sounds like the references | not heard | owner listen |

## Why it is built this way

1. **One shared body, not separate notes.** The thing all your references have in common is that
   one hit makes the rest of the instrument ring. Separate note objects cannot do that, so the
   notes live on one shell, as they do on the real instruments.
2. **The answer comes from shared overtones, not from a rule.** A handpan's F3 has an overtone
   at F4, so the F4 field rings. A chromatic note has no partner and stays quiet. Because no rule
   says "ring the scale", changing SCALE changes the answer by itself.
3. **The coupling must go both ways.** Round 1 tried the cheaper one-way version and measured it
   failing. The tongue's octave overtone is 6 cents from the next tongue, and only a two-way
   coupling pulls two near-miss notes together. That is why the Kotlin uses `Modes.Bank` and
   not a filter after the strike.
4. **Velocity is physics.** A harder hit is a shorter contact and drives the octave by amplitude
   squared, so a hard note is brighter and blooms more. That is what the velocity gate checks
   for, and it is how the real instruments behave.
5. **The one risky decision is BLOOM.** The prototype's bloom adds energy instead of moving it.
   The Kotlin version must move it (a force pair from one potential) and prove it with the bank's
   own energy reading before anything ships.
6. **The tube taught a rule: one body, one strike.** Two tube versions failed because I explained a
   measurement by adding a separate mechanism (a seal) on top of the tube. The approved version
   explains the same measurement with the tube alone: an open column has a weak, long fundamental.
   When a reference shows something odd, try the plain physical body first, then add parts.
