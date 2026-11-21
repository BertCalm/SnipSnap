# DRAWL Engine: Engineering Specification

Version 0.1 · 2026-11-20
Status: round 1 built. The owner kept all six voices of the Python prototype after rounds 0b to 0d
(§18); the native Kotlin engine is built and probed and awaits its first listen (As built). No
acceptance gate is passed yet.
Repository truth: read at `7fd194f1`. APIs grepped: `Strings.Loop` and `Strings.Loop.retune`
(`Strings.kt:529`, `:675`), `Strings.tune` (`:101`), `Strings.pluckExciter` (`:366`), `Strings.pickup`
(`:401`), `Strings.bodyRing` (`:1321`), `Restrike.canRestrike` (`Restrike.kt:35`), `OsteriaStrum`
(the shipped STRUM), `Accord.renderTake` and `AccordKeygroupMaker.RELEASE_SECONDS` (`:56`, 0.6 s),
`Velocity.macroSpecsFor` and `touchedRender` (`Velocity.kt:203`, `:426`), `Classifier.LOOP_MIN_SECONDS`
(`Classifier.kt:81`, 1.5 s), `Keys.MAX_SEAM_ERROR` (`Keys.kt:159`), `FxChain` sections `tape`, `vibrato`,
`tremolo`, `echo`, `spring` (`FxChain.kt:285` to `:294`), `PresetTestSupport.trademarkBlocklist` (`:27`).
Prototype: `prototype/drawl/`. Audition page: published to the owner as an artifact (round 0).

## 1. Summary

A string stopped by a moving bar, pedal or finger, where the pitch path over the note is the
performance. Six voices fill the western and cosmic country niche the owner asked for: a resonator
guitar (CONE), an electric slide (BOTTLE), a lap steel (LAP), a pedal steel (STEEL), a country
flat-top (FLATTOP) and a western nylon guitar (GUT).

The characteristic gesture is **pick → the bar slides the note into place → the bar shimmers →
the string rings out through its body**.

Computed: a waveguide string (`Strings.Loop`) retuned every sample along a precomputed pitch path;
the path's three laws (a bar on an arm, a pedal to a stop, a finger over frets); the bar's scrape
over wound strings at rib-passing frequency; the string behind the bar; a pick shaped as a pull.
Imagined or shaped: every body is a small modal table or a pickup-and-amp chain read one way from
the string; nothing couples back into the string.

## 2. What the references measure

The owner uploaded fifteen loops and one-shots (`prototype/drawl/NOTICE.txt` lists them by sha256;
the audio is commercial and is not in the repo). `prototype/drawl/measure.py` read them; the numbers
are in `reference_metrics.json`. Python harness, not a Kotlin test.

| Behaviour | Measured | Where | Trust |
|---|---|---|---|
| Resonator body | LTAS peaks at 571 to 603, 764 to 872 and 1050 to 1190 Hz in all four resonator loops that are single lines or sparse; 2347 Hz in one | skippy, lequint, trotting G, unchained | high: four files agree |
| Bar vibrato | 5.9 to 7.5 Hz, 140 to 190 cents peak to peak, centred on the note | skippy, lequint, wasteland slide | high on these three single lines |
| Short slides | 1,900 to 3,400 cents/s over 2 to 4 semitones | skippy, lequint, wasteland slide | high |
| Long slides | about 10,000 cents/s over 10 to 19 semitones; a slide lasts about 0.1 to 0.2 s whatever its length | raaka one-shot, lap-steel chord slide, pedal steel | medium |
| Decay | resonator −17 to −25 dB/s on the loudest picked note; electric slide −5 to −10; pedal steel −5 | all | medium: a player's damping is in it |
| Brightness | centroids 227 to 1129 Hz; almost no energy above 4 kHz in any file | all | low as a target: most files are one producer's processed pack |
| Register | steel and slide loops sit at a median 250 to 373 Hz; resonator loops 73 to 368 Hz | all | medium |

Not trusted: glide and vibrato statistics on the chordal loops (the tracker reads chord changes as
glides), and any brightness target finer than "dark". Six of the resonator loops share one producer,
so their shared darkness is partly that producer's processing.

## 3. Why it earns a pad (sibling check)

Registered engines recounted at `7fd194f1`: 110 (`Patches.kt`). No engine models a bar, a pedal steel
or a resonator cone (grep for dobro, resonator guitar, slide guitar, pedal steel, lap steel, bottleneck
found nothing but a drumline doc).

- **PLUCK** (NYLON, HARP, KOTO, BANJO, SITAR, UPRIGHT) and **MAGNET** (JANGLE, CHUG): fixed pitch.
  Neither can move a note after the pick.
- **SILK** OUD's SLIDE glides up one scale degree in 0.05 to 0.35 s from below and is the only shipped
  glide (`Silk.kt:349`). It has no vibrato law, no bar, no pedal, no chord that slides, no cone.
- **OSTERIA** owns the shipped STRUM, on a spectral source, not a string.

What none of them can do: a note whose pitch path (land, overshoot, shimmer, pedal rise to a stop,
fret-stepped slide) is the performance, and a chord that moves as one bar. That is a gesture, so it is
an engine, not a voice of PLUCK: the path laws, the scrape and the behind-the-bar string are shared by
all six voices and belong in one place.

## 4. Three directions

1. **Strong, SPLICE + PORT (the bar path), built in round 0.** One engine; the moving stop is the
   exciter of change, the house string is the resonator, and each voice splices a body. Gesture
   clarity high (slide, shimmer, ring); novelty high against the roster; risk low (reuses
   `Strings.Loop.retune`, measured clean below); cost four build rounds. Wins because one mechanism
   carries all six voices and all six gates' evidence.
2. **Alternate A, STACK (voices and recipes on existing engines).** PLUCK gains FLATTOP, MAGNET gains
   a slide voice, SILK's slide is widened, and "cosmic" is a recipe over TAPE, ECHO and SPRING. Cheaper
   per voice, but the bar path would be written three times (one quantity in three places), and no
   single engine would reach six voices, a kit and keygroups of this sound. Rejected.
3. **Alternate B, COUPLE (the cone as a two-way bridge).** The biscuit and cone become a
   `Strings.Membrane` bridge that every string drives and that drives them back, with sympathetic
   strings of an open tuning ringing on the cone (the GYRE pattern). The most "third sound" of the
   three; risk is the coupling bound and a render cost several times higher. Keep as CONE's upgrade
   after round 3 if the one-way cone reads as an EQ.

## 5. Signal flow

```
note, velocity, take, macros (TUNE SLIDE WAVER PICK BODY RING HOLD), STRUM (intervals, sweep, direction)
      │
      ▼
[DrawlPath: bar landing | pedal ramp | fret staircase  + hand vibrato + tension pitch]   [cache: SLIDE WAVER take velocity]
      │ cents(t) @176.4 kHz, one per string (bar shared, pedals per string)
      ├──────────────▶[DrawlScrape: noise → SVF band-pass at bar speed / rib pitch]──┐ (bar voices only)
      ▼                                                                              ▼
[DrawlPick: slope of a pulled triangle + 25 % combed noise + metal click]──▶(+)──▶[Strings.Loop, retune per sample]
                                                                                    │ string out
                                         ┌──────────────────────────────────────────┤
                                         ▼ (one way, weak)                          │
                         [behind-the-bar Strings.Loop, contrary path]──────────▶(+) │
                                                                                    ▼
             [Body: CONE biscuit curve → cone modes → 3 kHz mass roll-off | flat-top/nylon modes |
              pickup comb (fixed delay) → coil → amp tanh → speaker]  (STEEL: volume-pedal swell)
                                                                                    ▼
                          [Output: bandLimit → decimate → DC → levelTo (shared per performance) → fadeTail]
```

Every box above the output stage is a pure function of the patch, the note, velocity and take, so
re-rendering on a knob turn has nothing to invalidate but the render itself.

## 6. The model

### 6.1 The pitch path (the instrument's identity)

`cents(t)` against the target note, sampled at the internal rate.

```
bar landing (CONE BOTTLE LAP):  x'' = wn²(0 − x) − 2ζ·wn·x',  x(0) = −extent,  wn = 3.2 / dur,  ζ = 0.8 ± 0.05
pedal ramp (STEEL):             x(t) = −extent · (1 − clamp((t − t0) / dur, 0, 1)), then 5 ms smoothing
fret staircase (FLATTOP GUT):   round(bar landing / 100) · 100, then 2 ms smoothing
vibrato (after landing):        depth · ramp(t − land − 0.15 s over 0.35 s) · sin(2π∫rate·(1 + 0.03·wander))
                                FLATTOP: one-sided (a bend only rises)
tension modulation:             + 12 · v² · exp(−t / 0.12 s) cents (a hard pick starts sharp)
```

- A bar is carried by an arm, a damped mass-spring aimed at the note. With ζ = 0.8 it overshoots by
  about 1.5 % and corrects. That small landing is what separates a slide player from a portamento.
- A pedal travels at constant speed into a mechanical stop: no overshoot. The two laws are the
  difference between LAP and STEEL as much as the bodies are.
- A finger over frets steps a semitone at a time.
- Literal: the three laws as kinematics. Shape: every constant (ζ, 3.2, 0.15 s, 0.35 s, 3 %, 12 cents,
  0.12 s). Sourced: the slide speed law (§7) and the vibrato range (§2).

### 6.2 The string

`Strings.Loop` built at the lowest pitch the path reaches and retuned every sample with
`Strings.Loop.retune` (its contract: build at the lowest note, move only toward shorter loops; a path
that dips below its start is built at the dip). Loop gain is set per sample from RING's T60 so the hand's
loss is per second, not per round trip, whatever the bar does.

Measured risk, settled: retuning every sample through a 12-semitone slide under full vibrato. The
house read (integer delay, two-tap average, allpass fraction, as SILK uses it) puts the content above
10 kHz at −91.4 dB under the note in the glide and −110.4 dB under vibrato; a Lagrange read measures
−91.3 and −162.7 dB (`round0_report.json`, `zipper`). Both are far below audibility, so **DRAWL keeps
the house loop** and needs no new delay-line code. Round 0b's brighter CONE loop raises the glide figure
to −68.3 dB in both reads alike (−67.9 Lagrange), so that is the string's own top, not an artifact;
under vibrato the house read stays at −94.7 dB (`round0b_report.json`). Root pitch on a still bar: within 0.3 cents at three
notes per voice in both reads.

### 6.3 The pick (one toolkit change)

`Strings.pluckExciter` is one period of combed noise, which deals every harmonic a random level per
seed. Measured in round 0: a LAP take's second harmonic came out between 1 and 13 dB over its
fundamental depending on the seed, enough for a take to read an octave up. DRAWL's pick is the slope
of the string pulled into a triangle at the pick position (harmonic k starts at sin(πkp)/k), plus 25 %
of the house combed noise for roughness (35 % at full touch), low-passed by the pick's hardness, plus
a 0.6 ms high-passed click for metal picks.

Round 1 adds it as a new `Strings` function beside `pluckExciter`. Nothing else calls it, so PLUCK's
and SILK's frozen grids stay byte-identical; `StringsTest` must prove it.

### 6.4 Scrape and the string behind the bar

- Scrape (bar voices): noise through a TPT SVF band-pass whose centre is bar speed over rib pitch
  (0.35 mm, shape), gain rising with speed, quieter on plain strings. Bar speed comes from the slide plus
  15 % of the vibrato (a vibrating bar mostly rocks; shape). Round 0 first fed it the whole vibrato and
  measured a −26 dB noise floor that never decayed; the 15 % rule removed it.
- Behind the bar: the stretch between bar and nut rings at `f_open / (1 − f_open / f)`, fed one way
  and weakly from the note, damped hard. Its pitch moves against the note during a slide. Gain rises
  with RING (an undamped hand). One-way, so it cannot feed energy back.

### 6.5 Bodies

| Voice | Body | Provenance |
|---|---|---|
| CONE | asymmetric soft curve (the biscuit driving the cone; drive from BODY and from touch) → modes 580, 820, 1180, 2350 Hz → 120 Hz high-pass → 5.5 kHz mass roll-off (3 kHz in round 0); the 2350 Hz mode raised to gain 0.8 (0b); cone and cover-plate ring at 3.3 and 4.7 kHz | modes measured (§2); the rest shape |
| FLATTOP | modes 100 and 205 Hz (air, top), 431, 598, 727, 1500 Hz | 431/598/727 measured on the acoustic loop; 100/205 shape |
| GUT | modes 95, 190, 425, 501, 764 Hz; dark loop filter | 425/501/764 measured on the nylon loops |
| BOTTLE, LAP, STEEL | pickup comb at a fixed distance from the bridge (a fixed delay in samples wherever the bar is, because stopped length times frequency is constant) → coil resonance → amp tanh → speaker band; STEEL adds a 1.5 kHz presence peak | STEEL's peak from the pedal-steel loop; LAP's dark coil from the lap loop's centroid; rest shape |

STEEL's volume-pedal swell is a raised-cosine fade of 0.02 to 0.7 s on the output.

## 7. Macro contract

| Control | Default (CONE) | Changes which model quantity | Required interaction | Test (round 0 number, Python) |
|---|---:|---|---|---|
| TUNE | .50 | the target note, ±12 semitones around the voice root | the bar's open string and the scrape's bar speed follow it | root within 0.3 cents at TUNE .25/.5/.75 |
| SLIDE | .30 | bar: slide extent 12·S^1.3 semitones in 0.07 + 0.012·semitones s; fretted: 0 to 3 frets; STEEL: pedal travel 0 to 2 semitones (a half pedal mid-knob) | with WAVER: the vibrato starts after the landing | macro distance 12 to 31 at 0 vs 1 |
| WAVER | .45 | hand vibrato depth 150·W^1.3·(1 − 0.2·W) cents (0d) (×0.6 STEEL, ×0.45 GUT, ×0.35 FLATTOP one-sided) and rate 5.0 + 2.6·W − 0.8·W² Hz (levels off as it widens; 0c) | with SLIDE (above); scrape takes 15 % of it | pitch spread (std) 0 / 33 / 78 cents at 0 / .5 / 1 on CONE (0d) |
| PICK | .75 | pick hardness (burst low-pass, per-voice range), pick position 0.30 to 0.07 of the string, metal click; STEEL: low half swells in under the volume pedal | with velocity: touch shifts hardness ×2^(2.5(v − .75)) | distance 6 to 15 |
| BODY | .60 | CONE: cone share and biscuit drive; FLATTOP, GUT: body share; electrics: amp drive | with velocity: a harder pick drives the cone and amp further | distance 5 to 15 |
| RING | .55 | the hand's loss: T60 of the fundamental between per-voice bounds (CONE 0.8 to 8 s), loop brightness ×0.75 to 1.25, and the string behind the bar | with HOLD: HOLD gives a long RING room to finish | distance 11 to 21 |
| HOLD | 0 | render length from the voice's base (2.5 to 4 s) to 8 s; finite, never a loop | with RING | 2.5 → 8.0 s |

Velocity and take are performance arguments, not macros. Unknown keys change nothing and a non-finite
value falls back to its default (both probed, all six voices).

## 8. Voices

| Voice | Root (TUNE .5) | Base length | Character in one line | Answers to |
|---|---|---|---|---|
| CONE | A3 | 2.5 s | bar, fingerpicks, nasal cone, fast decay | the eight resonator loops |
| BOTTLE | D4 | 3.0 s | bottleneck electric, grit, wide vibrato | wasteland slide, country slide, slide one-shot |
| LAP | D4 | 3.5 s | dark pickup, chords that slide as one | lap-steel chord slide |
| STEEL | E4 | 4.0 s | volume swell, pedals rise to a stop, long ring | pedal-steel swells |
| FLATTOP | D3 | 2.5 s | fretted steel-string boom, fret-stepped slide-in | acoustic trotting |
| GUT | E3 | 2.5 s | nylon, finger vibrato, dark | nylon spaghetti and outlaw loops |

Against the references (`round0c_compare.json`; CONE and BOTTLE as changed in round 0b, LAP in 0c; Python harness):

| Voice | Centroid, DRAWL / refs (Hz) | Decay, DRAWL / refs (dB/s) |
|---|---|---|
| CONE | 669 (488 in round 0) / 368 to 693 | −18.9 / −24.7 to −4.3 |
| BOTTLE | 766 (573) / 494 to 1129 | −8.9 (−12.3) / −10.2 to −5.4 |
| LAP | 592 (463; round 0c) / 561 | −12.2 (−16.6) / −16.5 |
| STEEL | 597 note, 683 chord / 1051 | −6.2 / −5.3 |
| FLATTOP | 351 / 274 | −17.0 / −12.2 |
| GUT | 266 / 307 to 359 | −23.6 / −17 to −4 |

Disclosed: STEEL is darker than its one reference; brightness waits for the owner's ear rather than
a fit to one processed loop.

Further voice candidates, not built: **TWANG** (baritone electric with a vibrato-arm dip: a fourth path
law, the whole string dips and returns), **RUBBER** (a rubber-bridge guitar, heavily damped thump; the
owner's own reference measures a 227 Hz centroid with 22 % of its energy under 100 Hz), and **CHIME**
(natural harmonics: a node touched at a fraction of the string, for the cosmic end of pedal steel).

## 9. Articulation, velocity and round robin

- **STRUM.** Every voice is picked, so every voice has STRUM (the owner's articulation gate). It takes
  a `ChordStabs.Stab` directly, as `OsteriaStrum.fromChord` does: one string per chord tone, picked
  `sweep` seconds apart (10 to 240 ms), DOWN or UP. On a bar voice the bar is shared, so the chord slides
  as one (the lap-steel gesture); on STEEL the lowest tone holds and the upper tones rise into the chord
  on the pedals (top 2 semitones, inner 1, times SLIDE); fretted chords do not slide. Bodies are shared
  by the strings, so a chord costs strings, not bodies. Round 0 renders every voice's strum both ways.
- **Velocity is timbre.** Touch moves pick hardness, noise share, click, tension pitch, and (through
  level) the cone's and amp's drive. One gain per saved performance, shared across touches and takes
  and lowered only so the hardest touch clears the ceiling (the ACCORD rule). Round 0, level-matched
  onset centroid soft / medium / hard, Hz: CONE 1070/1314/1466, BOTTLE 1887/2520/2745 (both round 0b), LAP 732/822/871,
  STEEL 1455/1692/1789, FLATTOP 622/757/848, GUT 488/576/649. Measured with SLIDE 0: the bar's scrape
  does not follow the pick, so at a soft touch it would stand in for the pick in the probe.
- **Round robin.** A take is the same player again: pick seed, pick position ±6 %, hardness ±10 %,
  slide time ±10 %, landing ζ ±0.05, vibrato phase, rate ±4 %, depth ±8 %, tension ±10 %. Never the target
  pitch. Round 0: four takes per voice, none identical, still-bar pitch within 0.2 cents on every take.
  STEEL's takes are the closest pair (distance 0.99 against 1.8 to 4.8 elsewhere), because the swell
  hides the pick: round 2 adds pedal-timing variation to STEEL's takes.

## 10. HOLD

HOLD is finite: it lengthens the note to 8 s and never loops. That is the policy the owner kept by ear
for HALOBAR and ACCORD, and the one OSTERIA ships: on keys, a held note plays to its end and key-up
fades it over 0.6 s (`AccordKeygroupMaker.RELEASE_SECONDS`). Plucked strings decay by nature, so a
seamless loop would be the wrong instrument for CONE, BOTTLE, FLATTOP and GUT. STEEL and LAP are the
exception worth asking about: a swelled steel chord is a pad, and a loop cut from the held middle (the
BORE strategy, seam measured against a real preceding cycle) is possible. That is the owner's call at
the round-3 gate; until then it is refused and the test says so.

## 11. Render budget

Python round 0 (numba string loop, scipy filters, desktop): the string loop costs 6 to 11 ms per note;
a whole note renders in 80 to 204 ms, a four-string strum in 233 to 532 ms, almost all of it body
filters at 176.4 kHz in scipy. Kotlin target: under 50 ms per note and under 150 ms per strum on a
desktop JVM, measured in round 1, with the strings rendered in parallel only if that target is missed.
Nothing needs a cache beyond the gain (two extra renders per new performance, as ACCORD does).

## 12. The acceptance gates, one by one

| Gate | Plan | Evidence that will close it | Round |
|---|---|---|---|
| Melodic: six voices with presets | CONE BOTTLE LAP STEEL FLATTOP GUT; presets chosen after the listen ("presets authored blind are disposable") | owner keeps each voice by ear; `DrawlPresets` with a factory preset per voice; pins by hash | 1 and 3 |
| Melodic: playable keygroups | `DrawlKeygroupMaker` in ACCORD's shape: roots every minor third over each voice's range, three touches × two takes, finite keys, key-up fade | maker tests on real renders; owner hears key walks from saved samples | 4 |
| Melodic: premade kit | `SynthKits.drawl()`: A01 to A08 CONE up a minor pentatonic over TUNE, A09 to A16 one recipe per voice plus LAP chord slide, STEEL pedal chord and FLATTOP strum; a DRAWL TAKES starter with three touches × two takes; one `StarterShelf.GROUPS` place | kit tests, MPC 3 and MPC 2 export in software, owner listen | 3 |
| Melodic: chord tools | STRUM from `ChordStabs.Stab`; a `DrawlChords.notes` that folds every voicing into range by octaves (the `AccordChords` precedent) | all 960 diatonic, modal and borrowed voicings render, and play on real key samples | 2 and 4 |
| Articulation: HOLD | finite (§10); STEEL/LAP loop is the owner's decision | owner hears held and released keys | 4 |
| Articulation: STRUM | all six voices (§9) | strum renders both ways kept by ear; saved and reloaded with the patch | 2 |
| Percussion, drums | not applicable: a melodic string engine | | |
| Velocity: rendering | timbre, not only level (§9) | `touchedRender` arm; onset-centroid test per voice; owner hears soft, medium, hard | 1 |
| Velocity: saving, playback, export | three touch windows (0 to 42, 43 to 84, 85 to 127) in the TAKES starter and on keys | save, reload, resolver, MPC 3 and MPC 2 read-back tests | 3 and 4 |
| Round robin | `Drawl.renderTake`, `Restrike.canRestrike` gains DRAWL; pinned takes | takes kept by ear; pins; save, reload, resolver and MPC 3 read-back of every take; MPC 2 keeps each touch's first take (the owner's recorded format limit) | 2 and 3 |
| Picker | SYNTH picker entry mirroring ACCORD's arms, `DrawlSynthScreenTest` | CI's emulator run | 4 |
| Hardware | phone playback and an MPC load | the owner's report | 5 |

A disclosed export fallback does not close the velocity or round-robin gate (AGENTS.md); neither does a
listening preview or a green render test.

## 13. Build rounds

- **R0 (done here).** Python prototype, reference measurements, probes, audition page. Gate: the
  owner listens and keeps or changes each voice, its slide and vibrato, touch and takes.
- **R1.** `Drawl.kt`, `DrawlPatch.kt` (the BorePatch shape plus `velocity`, `take` and `strum`),
  `Strings` shaped pick, registration rows 1 to 7, `DrawlTest` with the house probe set (root,
  confidence ≥ 0.3, aliasing against a higher-rate reference, energy never grows with a flipped-sign
  guard, DC, determinism, every macro moves, scramble, classifier), `compare_kotlin.py` against round 0.
  Gate: the owner hears the native renders; pins by SHA-256.
- **R2.** STRUM, velocity and round robin in Kotlin; `DrawlRoundRobinPinsTest`; STEEL's pedal-timing
  takes. Gate: strums and takes kept by ear.
- **R3.** Presets after the listen, `DrawlPresetsTest` (six contracts, blocklist terms), kit and TAKES
  starter, shelf, export tests, `DrawlKitGenerator` and audition generator. Gate: kit listen; the HOLD
  decision.
- **R4.** Keygroups, chord tools on real keys, the picker and its emulator test, docs (`docs/DRAWL.md`,
  roadmap row, README count, the gates entry). Gate: keys and chords from saved samples.
- **R5.** Phone and MPC, by the owner.

## 14. Sonic risks

1. **The slide reads as synth portamento.** Guard: the landing overshoot, the scrape and the string
   behind the bar. Probe: scrape energy during a slide over its level after landing; the listen.
2. **The cone reads as a guitar through an EQ.** Guard: the biscuit curve driven by touch. If the listen
   says EQ, Alternate B (§4) is the upgrade.
3. **Vibrato sounds like an LFO.** Guard: a delayed, ramped onset and a 3 % rate wander; the takes vary
   phase and rate. The listen decides.
4. **Keys stretch the gesture.** A key sample played up to 1.5 semitones from its root changes slide
   time and vibrato rate by up to 9 %. Keys at SLIDE 0 avoid the slide stretch; the listen decides the
   vibrato.
5. **Amp grit aliases.** The tanh runs at 4× before the band limit. Probe: the house aliasing test at
   BODY 1 and full touch.
6. **Everything over 1.5 s files as LOOP.** Every default note is 2.5 to 4 s, so the classifier files it
   as LOOP, as it does ACCORD's and OSTERIA's long notes; `drumClassFor` must agree to the frame.

## 15. Trademarks

Engine, voice and preset names are generic (DRAWL, CONE, BOTTLE, LAP, STEEL, FLATTOP, GUT). "Dobro"
is a brand name; the generic instrument is a resonator guitar. `DrawlPresetsTest` adds blocklist terms
for the makers this brief and its references name or imply: dobro, gibson, fender, rickenbacker,
emmons, sho-bud, and "national" only as `national\s*reso` (the word alone is ordinary English).

## 16. Ledger

| Claim | Status | Evidence |
|---|---|---|
| `Strings.Loop.retune` exists and must be built at the path's lowest pitch | verified | `Strings.kt:675`, read at `7fd194f1` |
| No shipped engine models a bar, pedal steel or resonator cone | verified | grep at `7fd194f1` (§3) |
| Retuning the house loop every sample adds no audible artifact | measured | −91.4 dB glide, −110.4 dB vibrato (Python harness) |
| Root within 0.3 cents, six voices, three notes, both loop reads | measured | `round0_report.json` (Python harness) |
| The house noise pick leaves take-to-take harmonic balance to chance | measured | LAP take h2 1 to 13 dB over h1 before the shaped pick (Python harness) |
| Touch changes timbre on all six voices | measured | onset centroids §9 (Python harness) |
| Four distinct takes, pitch held | measured | `round0_report.json` (Python harness) |
| Resonator modes at 580/820/1180 Hz are the cone's | measured on references; the attribution is proposed | four loops agree; one producer |
| Every body, ζ, rib pitch, scrape and ghost constant | proposed (shape) | none until the listen |
| Kotlin renders a note in under 50 ms | proposed | round 1 |
| Round 0 as a whole | heard, owner, 20 Nov 2026 | "Resonator could use more twang, slide a bit more whine." No other note |
| CONE's twang and BOTTLE's whine as changed in 0b | heard, owner, 20 Nov 2026 | "Better." |
| LAP's whine and the slower wide vibrato (0c) | heard, owner, 20 Nov 2026 | "Better, but maybe the vibrato needs to be a tad shallower." |
| The shallower vibrato (0d) | heard, owner, 21 Nov 2026 | "Yes" |
| All six voices of the Python prototype (round 0d) | kept by ear, owner, 21 Nov 2026 | "Keep all" |
| The native Kotlin engine (round 1) sounds like what was kept | measured, not heard | `round1_compare_kotlin.json`; the round-1 listening page |

## 17. Why it is built this way

- **The pitch path is computed first, then the string follows it.** Because SnipSnap renders offline,
  the whole slide is known before the first sample. That lets the bar land with a small, physical
  overshoot, lets a pedal stop dead, and lets the string be built once at the lowest pitch it will reach.
  Take the path away and every voice is a static pluck that PLUCK already makes.
- **Three laws, not one glide knob.** A bar on an arm overshoots, a pedal hits a stop, a finger steps
  over frets. Players hear those differences before they hear tone, so they are what makes LAP a lap
  steel and STEEL a pedal steel.
- **The pick is shaped, not random.** The house pick is noise, so two picks of the same note can differ
  by 12 dB in one harmonic. That was fine for one-shots; for round robin it makes takes sound like
  different instruments. A pulled-string shape with a little noise keeps the takes one player's.
- **Bodies are read one way.** The string never hears its body, so nothing can run away, every voice is
  cheap, and a body can be swapped without retuning anything. The cost is that the cone cannot push back
  on the string; Alternate B is ready if the listen says that matters.
- **The risky decision** is reusing the house loop for continuous retuning. Round 0 measured it clean,
  so it stays; the decision to reverse it would be a click heard in a slide.

## 18. Round 0b: the owner's first listen

On 20 November 2026 the owner heard round 0 and wrote: "Resonator could use more twang, slide a bit
more whine." Read as model quantities, not tone controls, and measured on a still bar with
`character_probe.py` (`round0b_character.json`; Python harness):

| Change | Why |
|---|---|
| CONE loop low-pass 3.5 → 6.5 kHz; BOTTLE 6 → 10 kHz | both strings lost their top first: harmonics 4 to 10 fell 44 dB (CONE) and 30 dB (BOTTLE) further than the fundamental within a second |
| CONE cone roll-off 3 → 5.5 kHz; 2350 Hz mode gain 0.35 → 0.8 | round 0 had darkened CONE toward the references, and the twang band went with it |
| CONE tension modulation 12 → 28 cents at full touch, settling in 80 ms; PICK default .65 → .75 (nearer the bridge) | a twang is a hard metal pick on a high-action string: it starts sharp and drops |
| BOTTLE coil peak 3.2 kHz Q 2.2 → 2.6 kHz Q 4.5; amp drive range 1 to 6 → 2 to 9; defaults BODY .6, RING .85, WAVER .6; bar 35 % slower | the whine: a vocal pickup peak, sustain that holds the highs up, and a glide long enough to hear |

| Probe (still bar) | CONE round 0 → 0b | BOTTLE round 0 → 0b |
|---|---|---|
| boing, hard pick (cents) | 7.3 → 15.4 | 7.0 → 7.2 |
| sustain energy 1 to 4 kHz (dB of total) | −60.8 → −25.4 | −31.1 → −14.3 |
| harmonics 4 to 10 against the fundamental after 1 s (dB) | −44.4 → −13.5 | −30.4 → −16.8 |
| attack energy 1.5 to 4 kHz (dB of total) | −20.8 → −12.6 | −14.5 → −3.7 |

Both stay inside their references' brightness range (§8). The risk to listen for is BOTTLE's attack:
about half its energy now sits between 1.5 and 4 kHz, which could read as harsh or as a wah. LAP, the
other slide voice, is unchanged until the owner says whether "slide" meant it too. Every other probe
(root, determinism, DC, velocity timbre, takes, macros) still passes on the round-0b renders.

### Round 0c

The owner heard 0b: "Better. I did mean lap as well. The vibrato on the long slide vibrato should be
a smidge slower." (`round0c_character.json`; Python harness.)

- **LAP gets the whine, kept the darker slide.** Loop low-pass 4.5 → 8 kHz; coil peak 2.0 kHz Q 1.4 →
  2.2 kHz Q 3.5 (lower and softer than BOTTLE's 2.6 kHz Q 4.5); amp drive range 0.8 to 3 → 1.5 to 6;
  speaker 3.5 → 5 kHz; defaults BODY .5, RING .65; bar 25 % slower. Sustain energy 1 to 4 kHz −60.1 →
  −23.8 dB; harmonics 4 to 10 against the root after 1 s −59.1 → −20.2 dB; attack 1.5 to 4 kHz −26.1 →
  −11.0 dB. Centroid 463 → 592 Hz against the lap loop's 561. It now decays at −12.2 dB/s against that
  one loop's −16.5: the sustain the whine needs, disclosed for the listen.
- **Wide vibrato levels off.** The rate law was 5.0 + 2.6·W Hz, 7.6 Hz at full WAVER, the fast edge of
  the references' 5.9 to 7.5. It is now 5.0 + 2.6·W − 0.8·W²: 6.05 Hz at half, 6.8 at full, because a
  wider shake moves more of the arm. Measured at full WAVER: BOTTLE 7.45 → 6.68, LAP 7.63 → 6.84,
  STEEL 7.71 → 6.90 Hz; at half, CONE 6.24 → 6.05 Hz. CONE at full WAVER is not measurable: the pitch
  tracker holds it for under 0.15 s through a 150-cent shake and the cone's curve, so its value rests on
  the shared law. The first vibrato probe spliced the pieces either side of tracker dropouts and read
  CONE at 8.7 Hz; it now reads only the longest unbroken stretch.

Every probe still passes on all six voices (`round0c_report.json`).

### Round 0d

The owner heard 0c: "Better, but maybe the vibrato needs to be a tad shallower." Full WAVER reached
250 to 260 cents peak to peak on BOTTLE and LAP against the single-line references' 140 to 190, while
the defaults already sat at or under them, so the trim grows with the width: depth is now
150·W^1.3·(1 − 0.2·W), 20 % less at full WAVER and about 10 % at the defaults. The rate is unchanged
(BOTTLE 6.68 → 6.69 Hz at full). Peak to peak, full WAVER: BOTTLE 259 → 206, LAP 253 → 203, STEEL
130 → 104, CONE 211 (unmeasurable before: the narrower shake lets the tracker follow it, at 6.64 Hz);
defaults: BOTTLE 134 → 118, LAP 80 → 73, CONE 95 → 86, STEEL 28 → 27 (`round0d_character.json`;
Python harness). Every probe still passes on all six voices (`round0d_report.json`).

## As built

### Round 1: the native engine (21 Nov 2026)

The owner kept all six voices of the round-0d prototype ("1. Yes 2. Keep all"). Round 1 ports it to
Kotlin. Nobody has heard the native renders; the round-1 listening page pairs each with the clip it
was ported from.

Built: `Drawl.kt` (the engine), `DrawlPatch.kt` (the patch: the BorePatch shape plus `velocity` and
`take`, each written only when not its default), the registration rows (`Patches.fromJsonValue`,
`Velocity.macroSpecsFor` and `touchedRender`, `SynthRoot`'s TUNE mapping), `PatchTypes.PROTOTYPES`
(no factory presets until the native renders are kept), hand-built entries in `PatchesTest` and
`PadRecipeTest`, a `DeterminismTest` canary over every voice with velocity and take, `DrawlTest`,
and `DrawlAuditionGenerator` with `./gradlew :synth:generateDrawlAudition`. Not built in round 1: STRUM,
the kit, presets, keygroups, the picker (rounds 2 to 4, §13).

Changed against this design, each measured:

- **The string read.** §6.2 planned the house `Strings.Loop`. The prototype the owner heard used a
  Lagrange read, and the house loop's two-tap average darkens every trip: within 1.1 dB on every
  probe, but on STEEL it moved the render further (spectral distance 2.52) than STEEL's own takes
  differ (0.99 to 2.15). DRAWL keeps the heard read in a private class (`Drawl.Read`, about 40
  lines, not a copy of toolkit code).
- **The pick.** §6.3 planned a new `Strings` function. Nothing else needs it yet, so it lives in
  `Drawl.pick`; it moves to `Strings` when a second engine asks for it.
- **Control rate.** The path, tuning budget, loop gains and scrape filter are worked out every 16
  samples (11 kHz) and interpolated, instead of per-sample arrays: memory stays flat with HOLD and a
  default note renders in 68 ms on a desktop JVM after warm-up (`DrawlTest`).
- **Noise, level and the speaker band.** Noise comes from `Dsp.Noise` and `kotlin.random.Random`, so
  no native render matches a Python one sample for sample. The level is the house loudness target,
  one gain per sound shared by every velocity and take (§9). The electric speaker band is two
  cascaded second-order sections; in the prototype that swap moved BOTTLE's 1 to 4 kHz band by
  0.0 dB.

Measured against the heard prototype (`compare_kotlin.py`, `round1_compare_kotlin.json`; the same
probes on both; Python harness measuring the Kotlin WAVs):

| Voice | Centroid, heard / native (Hz) | Decay (dB/s) | Wide vibrato (cents p-p, Hz) | Hard over soft (dB) |
|---|---|---|---|---|
| CONE | 669 / 677 | −21.9 / −21.6 | 215, 6.63 / 212, 6.71 | 6.1 / 5.7 |
| BOTTLE | 762 / 724 | −8.4 / −8.5 | 202, 6.67 / 200, 6.86 | 4.9 / 4.5 |
| LAP | 592 / 604 | −12.2 / −11.7 | 199, 6.84 / 201, 6.70 | 5.3 / 5.2 |
| STEEL | 597 / 578 | −6.4 / −6.3 | 106, 6.88 / 106, 6.93 | 8.4 / 8.3 |
| FLATTOP | 351 / 352 | −17.0 / −17.2 | 36, 6.82 / 35, 6.74 | 9.3 / 9.2 |
| GUT | 266 / 236 | −23.6 / −23.2 | not trackable on either | 9.4 / 9.3 |

The resonance peaks match on LAP and STEEL exactly and share three of four on the rest. The largest
gap is BOTTLE's whine band: across its four takes the heard set read −11.5 to −15.6 dB of 1 to 4 kHz
in the sustain and the native set −13.9 to −17.0 (mean 1.4 dB lower); the take the owner heard by
default was the brightest of its four. Not a design gap (the speaker-filter test above), and left
to the owner's ear.

`DrawlTest` (Kotlin, desktop JVM): roots within 1 cent at TUNE .25/.5/.75 with pitch confidence
0.97 to 0.99 on all six voices; a full slide starts over 300 cents low and lands within 10; every
macro moves the render, an unknown key does not, a NaN falls back; WAVER's pitch spread grows with
the knob; a harder pick is louder and has a brighter level-matched onset on every voice; four takes
are four distinct strikes in tune; render length equals `renderFrames` and the class is LOOP, never
a drum; the tail decays under half the strike; JSON round-trips; scramble stays in bounds.
