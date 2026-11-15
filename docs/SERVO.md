# SERVO: design brief and round-0 audition

Status: **round 1 (2026-11-15): the Kotlin engine and three kits on the FRESH TAPE shelf, with velocity and round
robin proven in software through rendering, saving, playback and MPC 3 export. Not heard.** The owner's
instruction on 2026-11-15 was "Must pass the gates", so the port did not wait for a listen to round 0; every
sound setting is therefore still "shape". The engine stays in progress until the sound and hardware gates have
the owner's evidence (see "Gates" and the entry in
[ENGINE_ACCEPTANCE_GATES.md](ENGINE_ACCEPTANCE_GATES.md#servo-round-1-2026-11-15-kotlin-kits-on-the-shelf-not-heard)).
What the Kotlin round built and measured is under [As built](#as-built-round-1-kotlin-2026-11-15).

Prototype: [`prototype/servo/`](../prototype/servo/) (`servo_proto.py` is the model, `kits.py` the three
kits and their phrases, `measures.py` the house distance measures, `gen_audition.py` renders and measures,
`build_page.py` writes the audition page, `compare_kotlin.py` measures the port). Every number above "As built"
is from that Python harness, not from Kotlin.

Listening page: <https://claude.ai/artifact/XGH7434aQcdBcffRRnHLA8> (private to the owner; published
2026-11-15, version 3). It opens with the Kotlin kits as read back from the MPC 3 export, then carries the
prototype: a playable 4×4 pad grid with three velocity layers and four takes per
pad, the rail heard on its own, each kit's groove, roll and riser, every control on every voice, the gates
and the ledger.

Repository truth: read at `40e36dba` (2026-11-15). Grepped: `Thump.kt` (`ThumpVoice`, the six-square hat
ratios 1, 1.342, 1.681, 1.940, 2.318, 2.703 at line 910), `Dsp.kt` conventions through the Luthier
contract, `docs/SCUFF.md`, `docs/CYPHER.md`, `docs/TEMPEST_FULL_KIT.md`, `docs/SPORE_STATION.md`,
`docs/COSMIC_BREAKS.md`, `docs/FOOTWORK.md`. The name: `servo` appears in no `.kt`, `.md` or `.py` file.

The owner's brief (2026-11-15): "a drum kit that would be good for electro, ghetto tech, neurofunk. Lean
in on the genre to slightly exaggerate the sound and add a bit of creativity and uniqueness to the sound."
Read as: one machine, three sixteen-pad genre kits (the drum gate asks for several), with one new mechanism
that carries the exaggeration. Job: CONCEIVE.

## What it is

Machine drums (oscillators, pinged resonators, square clusters, filtered noise) running off one power
supply that is too weak for the job. Every hit draws current. A few milliseconds later the supply voltage,
the **rail**, sags, and then it recovers, ringing if it is underdamped. Three things in every voice read
the rail at once: the pitch falls, the clip ceiling and output swing fall, and a resonant tone peak sweeps
down. One state, three readouts, and each readout is one genre's signature pushed a little past where a
real machine would go.

**Gesture line.** The characteristic gesture is the trigger lands clean → the rail sags: the drum dives
and flat-tops → the rail rings back: the tail growls → recovered and silent before the next sixteenth.

Working name SERVO (a small motor that hunts back to its position, as the rail does). No maker or model
name is used for the engine, a voice, a kit or a pad. The ghetto tech kit is named SERVO JIT, after the
Detroit dance that goes with the music, so the kit's name does not carry the genre's.

## The genres, distilled

| Genre | What defines its drums (mechanism) | The readout that exaggerates it | Source |
|---|---|---|---|
| Electro | an analog drum machine with a very low, long bass drum; staccato, syncopated, mechanical | **pitch**: every drum dives as it lands (ZAP throws it, the sag pulls it) | [Electro (music)](https://en.wikipedia.org/wiki/Electro_(music)); tempo 110 to 140 is a weak, generator-site figure |
| Ghetto tech | the same two machines, faster (about 145 to 160 BPM), harder, short hits run hot | **swing**: the drum squares off against a ceiling the rail lowers (HEAT) | [ghettotech summary](https://www.wikiwand.com/en/articles/Ghettotech), [production guide](https://blog.samplefocus.com/blog/electro-ghettotech-ableton-live-12-guide); low-authority sources that disagree by 10 BPM |
| Neurofunk | aggressive, digitally designed kits; a snare body near 200 Hz over a kick near 100 Hz; heavy compression; moving, distorted mids | **tone**: a resonant peak rides a ringing rail into the clipper (GROWL) | [EDMProd drum tips](https://www.edmprod.com/6-drum-tips/), [drum and bass guide](https://www.edmprod.com/how-to-make-drum-and-bass/); 174 BPM is this brief's choice, not sourced |

Literal or analogy: **analogy with a stated law.** The rail is a two-pole resonant low-pass of the hit's
own envelope, and three parameter laws read it. No schematic is modelled, and no claim is made that a real
drum machine behaves this way. Circuit-benders do starve supplies to get dives and crunch; that practice
is the inspiration, not the model. Not simulated: component nonlinearity, thermal drift, the supply's real
impedance, any specific circuit.

## Why it earns a pad (sibling check)

- **SCUFF** (`Scuff.kt`): the nearest kit. Dry Detroit rap machine drums at 110 to 130 BPM with a static
  bus clipper (SLAM), crush, a comb and a cocked band-pass. Its clip, tone and pitch are fixed for the
  length of a hit. It cannot make a hit's own loudness move its pitch, headroom and tone together over
  time, and its kick lives at 70 to 140 Hz.
- **THUMP** (`Thump.kt`): the clean analog source (kick, snare, hats, clap, tom, cowbell, rim, spark, sub).
  SERVO's hat is THUMP's six-square cluster and a THUMP preset could stand in for it with the rail off.
  THUMP has no state shared between pitch, drive and tone.
- **TEMPEST**: digital strike, driven bite, degrading repeats. A different gesture (repeats, not a sag).
- **SPORE STATION, COSMIC BREAKS**: the 170 BPM kits. Both are break-style kits with printed effects, not a
  machine kit, and neither has a kick-to-hat interaction.

What no sibling does: one slow state per hit that drives pitch, headroom and tone at once, velocity that
reaches the sound by pulling that state harder, round robin that is the same drum caught at a different
moment of recovery, and phrases in which the kick's draw moves the hats.

## The three directions

| Direction | Operator | Gesture / novelty / risk / cost |
|---|---|---|
| **Strong: the weak rail** | MUTATE (the condition changes: a starved supply) | Clear four-step gesture that lives in a one-shot. One state gives all three genre signatures. Risk: the sag thins a kick's body; the growl may read as a wobble. Cheap: one biquad over a control signal. Rounds: Python proof → Kotlin engine → kits and export |
| Alternate A: drums with a mouth | PORT (a vocoder's formants onto drum tails) | Every hit speaks a vowel; electro's robot voice and neuro's talking bass on drums. Novel, but VOX BEATBOX and MURMUR are close, and it risks reading as a wah. Same cost |
| Alternate B: one beating body under the whole kit | COUPLE | Every pad excites a shared detuned resonator pair, so the kit growls as one object. The strongest "third sound", but it only exists in a loop: sixteen one-shot pads on an MPC cannot share a body. High risk for a pad sampler |

Strong wins because its gesture survives in a single exported pad, where the MPC needs it, and still gives
phrases something no one-shot kit has.

## Signal flow

```
macros, velocity ─► amplitude envelope ─► LOAD (what the hit draws; × velocity^1.5)
        │                                   │
        │                                   ▼
        │                  [RAIL: resonant 2-pole low-pass, DC gain 1]      SAG = depth, GROWL = ring
        │                  26 Hz, zeta 1  ...  72 Hz, zeta 0.055             [pure function of envelope,
        │                                   │ r(t), tanh-bounded at 0.85     macros, velocity, take: cacheable]
        │        ┌──────────────────────────┼─────────────────────────────┐
        ▼        ▼ pitch × 2^(-0.5 r)       ▼ tone × 2^(-1.6 r)           ▼ swing × (1 - 0.7 r)
[VOICE @176.4 kHz: sines, PolyBLEP squares/saws, noise] ─► [TONE: moving peak or low-pass] ─► [HEAT: drive
        into the ceiling, soft knee, output × swing] ─► band-limit ─► decimate 4:1 ─► DC ─► tail fade ─► 44.1 kHz mono

Phrase pads (roll, riser, groove): every hit's LOAD sums into ONE rail; each voice reads its own sag plus
a share (CROSS) of everyone else's, bounded at 1.2 kicks. One-way: the audio never feeds the rail.
```

## Macro contract

| Control | Default | What it changes in the model | Required interaction | Measured, 0 against 1 (Python) |
|---|---:|---|---|---|
| TUNE | .50 | the voice's root or cluster base (kick 38 to 78 Hz, snare body 150 to 265 Hz, hat cluster 320 to 620 Hz) | TUNE × SAG must keep the root | 3.6 dB (crash) to 28.8 dB (laser); tail within 10.9 cents of the root at every SAG × GROWL corner |
| DECAY | .50 | the amplitude envelope's T60, per voice (kick 0.12 to 1.1 s, hat 30 to 620 ms) | DECAY lengthens the load, so the sag lasts longer | 8.6 dB (crash) to 37.3 dB (hat) |
| ZAP | .40 | how far and how long the pitch is thrown at the start (kick: 0.6× in 9 ms to 11× in 50 ms); on noise voices, a sweep of the band | ZAP × SAG: two dives of different speeds | 3.2 dB (hat) to 31.6 dB (laser); ZAP × SAG interaction is weak on at least one voice (0.34 dB) |
| SAG | .45 | how weak the supply is: the depth of r. 0 is the rail switched off, exactly | SAG × GROWL, SAG × HEAT | 3.6 dB (clap) to 14.3 dB (stab); SAG × GROWL 3.0 dB; SAG × HEAT 0.64 dB on the weakest voice |
| GROWL | .35 | the rail's damping and ring rate together (zeta 1 at 26 Hz to zeta 0.055 at 72 Hz) | does nothing at SAG 0 (measured bit for bit) | 2.1 dB (tom) to 12.4 dB (rim); ripple in the loudness contour rises step by step: kick -31.2, -28.1, -25.0, -18.3 dB at GROWL 0, .35, .7, 1 |
| HEAT | .40 | drive into the ceiling, 0 to 24 dB, with a bias that makes the clip lopsided | the rail lowers the ceiling, so SAG raises the clipping | 7.4 dB (snare) to 26.5 dB (crash) |
| Velocity | | load × v^1.5, level into HEAT × v, plus per-voice strike laws (click, noise bandwidth, a clap that loses a tap) | | soft against hard at matched level: 3.8 dB (snare) to 11.8 dB (stab) at defaults; 3.2 dB at the least across the 48 kit pads |

No HOLD: finite drums. Every constant is "shape" until the listen.

## Voices

KICK, SNARE, CLAP, HAT, RIM, BELL, TOM, LASER, STAB, CRASH. HAT is closed or open by DECAY. BELL is two
squares a ratio 1.48 apart through a band-pass. LASER is the sweep on its own. STAB is a bass hit: two
saws 34 cents apart over a sub, through a resonant low-pass that rides the rail, ending on a hard mute.

## The three kits

| Kit | BPM | Leads with | Mean decay | Closest two pads |
|---|---:|---|---:|---|
| SERVO ELECTRO | 126 | the dive: ZAP high, a cool damped rail, low HEAT, a long low kick, cowbell, laser toms | 286 ms | 5.3 dB (A01 / A05) |
| SERVO JIT | 156 | the flat-top: HEAT .5 to .85, everything short and tuned up, a kick on every beat | 222 ms | 5.7 dB (A03 / A07) |
| SERVO NEURO | 174 | the snarl: GROWL .5 to 1, SAG .5 to 1, snare body at 199 Hz, two stabs | 291 ms | 7.3 dB (A09 / A10) |

The same slot in two kits is 12.8 to 14.5 dB apart on average and 5.9 dB at the closest (the crash, JIT
against NEURO). No pad is shared. Each kit has sixteen one-shots, B01 a roll and B02 a riser. The pad
tables are in `kits.py` and on the listening page.

**The shared rail.** Each groove, roll and riser is rendered twice: on one rail, and with every hit on its
own. They differ by 1.4 to 2.1 dB on the house measure. One-shot pads cannot affect each other on an MPC,
so only baked phrase pads carry the shared rail. That is a limit of the format, stated, not solved.

## Velocity and round robin

- **Velocity** is not level alone: a harder hit draws more, so it dives deeper, clips harder and growls
  more, and its strike changes too.
- **Round robin** is the machine's own kind. Take 1 fires on a rested rail. Takes 2 to 4 fire as if
  another hit landed 30 to 90 ms earlier, with the trigger a quarter stronger or weaker, the tuning within
  7 cents and the decay within 5 percent. Closest takes across the 48 pads: 1.03 dB (the ELECTRO rim). ETCH's takes heard as one strike measured 0.5 to 0.8 dB, so the rim and the kick are near the
  line: the listening page asks about them.

## Render budget

Python: mean 22.6 ms a hit, slowest 174.6 ms (the crash, whose moving tone stage runs block by block in
Python). The rail is one biquad over the envelope and is a pure function of the macros, velocity and take,
so it can be cached; pitch never needs it rebuilt. A Kotlin port should land inside the tens-of-ms rule,
but that is proposed, not measured.

## Sonic risks (where this would sound bad)

1. **The sag thins the kick.** The body ducks under its transient by design; at high SAG a boom may lose
   weight. Probe: the RAIL section's kick, off against sagging. ELECTRO's kick sits at SAG .3 for this.
2. **The growl is a wobble, not a snarl.** At GROWL 1 the ripple is 12 to 13 dB above GROWL 0 in the
   loudness contour, but still 18 to 23 dB under the whole contour. It may want more depth or a faster ring.
3. **The tail's pitch rises as the rail recovers.** Real machine kicks only fall. This is the
   exaggeration; it may read as wrong on a long boom.
4. **Take 2 to 4 start duller than take 1**, because their attack does not get a full rail. In a fast
   pattern that is the point; on a slow one it may read as an uneven pad.
5. **The shared rail smears hats** in a dense groove. Compare the two grooves per kit.
6. **Aliasing is not measured.** Squares and saws are PolyBLEP at 4×, and HEAT is band-limited before the
   rate drops, but no probe against a higher-rate reference has run.
7. **The tone stage is block-wise in Python** (64 frames at 4×); Kotlin's `Dsp.TptSvf` per sample will
   differ slightly under fast modulation. NumPy's generator stands in for `Dsp.Noise`, so a Kotlin take
   will be a new take, not a copy.

## Landing

Proposed: a drum engine `Servo.kt` (ten voices, six macros, no HOLD), a kit family `ServoKits` of three
shelf kits with B01 and B02 baked on the shared rail, in the SCUFF and CLUTTER shape. Not proposed until
the listen: the picker entry and factory presets ("presets authored blind are disposable"). The rail is
inside the voice (the oscillators read it), so it cannot be a rack section over THUMP.

## Gates

| Gate | State | Evidence |
|---|---|---|
| Melodic, keygroup, chord tool | N/A | A drum engine |
| Articulation (HOLD, STRUM) | N/A | Finite hits |
| Percussion: roll or riser | Built, not heard | A15 Roll and A16 Riser on every kit, each note a fresh strike on one shared rail, with the whole 3 × 4 grid (`ServoKitsTest`) |
| Drums: several genre kits on the shelf | Built, not heard | `servo-electro`, `servo-jit`, `servo-neuro` registered once each in `StarterKits` and `StarterShelf` (SKINS) at 126, 156 and 174 BPM; `ServoKitsTest` proves no role of one kit is another's |
| Velocity in rendering | Pass in software | `ServoTest`, `ServoKitsTest`: a soft hit is a different hit on every voice and every pad; exported pads measure at least 1.5 dB soft against hard at matched level (the ELECTRO riser), 2.2 dB on a one-shot |
| Round robin in rendering | Pass in software | Four different takes in every zone of every pad, matched in loudness within 0.04 dB; closest takes 0.59 dB (ELECTRO Rim), near the 0.5 to 0.8 dB ETCH's takes were heard as one strike |
| Saving, playback, export | Pass in software | `ServoKitTest` and `:shell:generateServoKits`: all 576 slices of the three kits checked against their renders after save, reload, two playback cycles of the app's pad resolver and the MPC 3 track read back |
| MPC 2 | Format limit | Each velocity zone keeps its first take ([owner decision, 2026-11-14](ENGINE_ACCEPTANCE_GATES.md#mpc-2-and-round-robin-owner-decision-2026-11-14)) |
| Sound | **Open** | Not heard. Only the owner can close it |
| Phone and MPC | **Open** | Nothing loaded on a device. Only the owner can close it |
| SYNTH picker, factory presets | Not built | Not gates. The engine is `ROSTERLESS` until the kits are heard ("presets authored blind are disposable") |

## Verified / measured / proposed / heard

- **Verified:** the APIs and docs named under "Repository truth" at `40e36dba`; the name is free.
- **Measured (Python harness):** 626 renders deterministic and finite, DC under 0.0011; root within 10.9
  cents at every SAG × GROWL corner (kick and tom, three tunings); largest |r| 0.796 against the 0.85
  bound; SAG 0 makes GROWL a bit-exact no-op; every control moves every voice (weakest TOM GROWL, 2.1
  dB); an unknown control, a NaN control and a NaN velocity change nothing; velocity 0 is silent.
- **Proposed:** every constant ("shape"), the three kits' settings, the Kotlin landing, the render budget
  on a phone.
- **Measured (Kotlin):** see "As built".
- **Heard:** nothing. The author cannot play audio.

## What to judge first on the listening page

1. THE RAIL: does the sag add punch and a dive without thinning the drum, and is the ringing rail a growl?
2. Each kit's groove on one rail against the pads as exported: movement or smear?
3. Is each kit its genre, and how far should the exaggeration go (more, less, or only on some pads)?

## Why it is built this way

- **The rail is worked out before any sound is made, from the hit's envelope alone.** That makes it cheap
  and repeatable, and it means nothing can feed back and run away: the sound reads the rail, the rail
  never reads the sound.
- **The rail lags the hit by a few milliseconds.** The first click always gets a full supply, so the drum
  keeps its punch, and the dive and squash arrive just behind it. Remove the lag and the attack goes dull.
- **One state drives three things.** If pitch, ceiling and tone each had their own envelope they would be
  three effects stacked on a drum. Tied to one rail they move together, which is what makes it sound like
  one machine straining and not like processing.
- **The tone peak sits before the clipper.** The peak decides which harmonics the clip makes. After the
  clipper it would only be an equaliser; before it, a moving peak becomes a moving snarl.
- **The risky decision is letting the body duck under its own transient.** It is what gives the punch and
  the growl, and it is also the thing most likely to make a kick sound thin. SAG at 0 switches the whole
  mechanism off exactly, so the plain machine drum is always one knob away.

## As built: round 1, Kotlin (2026-11-15)

Built at the owner's word ("Must pass the gates") without a listen to round 0. Read at `2036a8da`.

**What exists.** `Servo.kt` (ten voices, six macros, velocity and four takes, `phraseRails` for a shared rail),
`ServoPatch.kt` (velocity, take and model version on the patch), `ServoKits.kt` (three kits), the `Patches`,
`Velocity` and `SynthRoot` arms, three `StarterKits` entries in one `StarterShelf` group, `ServoTest`,
`ServoKitsTest`, the shell's `ServoKitTest`, a `DeterminismTest` canary through JSON, hand-built entries in
`PatchesTest`, `PadRecipeTest` and `SynthRootTest`, `ServoAuditionGenerator` (`:synth:generateServoAudition`)
and `ServoKitGenerator` (`:shell:generateServoKits`). The engine is in `PatchTypes.ROSTERLESS`.

**What changed against the round-0 design.**

- **Sixteen pads are fourteen hits, a roll and a riser** (the SCUFF and GATECRASH shape), not sixteen hits plus a
  bank B. Two parts of each prototype kit play only inside the phrases (ELECTRO: Robot Tom and Crash; JIT: Clave
  and Short Crash; NEURO: Low Rim and Crash). NEURO's A12 is the Snarl Stab in place of the Low Rim.
- **Takes are matched in loudness within a zone** (CLUTTER's law), not levelled by the first take (SCUFF's law).
  Measured with the first-take law, a take that fires on a rail already down came out up to 5.3 dB lower in
  peak (ELECTRO's Tick Hat, soft zone): the rail sets the output swing. A pad whose takes step in level reads as
  an uneven pad, so the level is matched and the duller attack is kept. Sonic risk 4 was real and is half
  answered: the level, not the character.
- **Each take has its own trigger strength** (1, 0.76, 1.24, 0.9, with a small seeded wobble). Drawn freely, two
  takes of ELECTRO's Short Kick measured 0.52 dB apart on the exported kit. The prototype carries the same law.
- **Phrases are a bar and two bars**, not two and four beats, so each files as a LOOP (over 1.5 s) as the house
  kits' phrases do.

**The port against the prototype** (`compare_kotlin.py`, the house distance): every voice's hard hit sits within
the spread of the prototype's own four takes.

| Voice | Kotlin to nearest Python take, dB | Python's own takes, dB | Correlation | Soft vs hard, Kotlin / Python, dB |
|---|---:|---|---:|---|
| KICK | 0.6 | 2.1 to 3.3 | 0.997 | 9.0 / 8.9 |
| SNARE | 2.6 | 2.4 to 2.8 | 0.808 | 3.7 / 3.8 |
| CLAP | 4.3 | 4.4 to 5.2 | 0.214 | 7.5 / 5.9 |
| HAT | 2.3 | 2.4 to 4.7 | -0.384 | 7.1 / 7.7 |
| RIM | 1.4 | 1.3 to 1.8 | 0.997 | 8.1 / 7.1 |
| BELL | 1.5 | 1.1 to 2.0 | 0.899 | 5.8 / 5.4 |
| TOM | 0.9 | 2.1 to 3.8 | 0.999 | 9.9 / 10.0 |
| LASER | 1.4 | 1.3 to 3.1 | -0.871 | 5.2 / 5.0 |
| STAB | 9.5 | 5.6 to 15.3 | -0.817 | 6.4 / 11.8 |
| CRASH | 3.0 | 3.2 to 4.0 | -0.169 | 11.1 / 11.1 |

Correlation is low or negative wherever a voice is noise or starts on a free-running phase (the draws differ);
the sine voices correlate 0.997 to 0.999. The STAB's two saws start on random phases, so its takes are far
apart in both models and its soft-against-hard figure moves with the draw.

**Disclosed, not fixed.**

- Kotlin's `Random` replaces numpy's generator, and the house band limit and decimator replace the prototype's.
- The TONE stage keeps the prototype's block-wise coefficients (64 frames at 4×) so the two agree; a per-sample
  `Dsp.TptSvf` would be better behaved under modulation and is a sound change for a later round.
- The closest takes on the exported kits are 0.59 dB apart (ELECTRO Rim), inside the band ETCH's takes were
  heard as one strike. A machine rim barely varies; whether it should vary more is the owner's call.
- Soft against hard on the phrase pads is 1.5 to 1.9 dB at matched level: a phrase's notes already span
  velocities, so its zones differ less than a single hit's.
- Aliasing is still not measured against a higher-rate reference.
- A kit builds in 8 to 12 s on the development JVM (the two phrases are 50 notes for each of twelve cells); a
  single hit renders in 4 to 17 ms, the crash in 40 ms (JIT warmed, one run). No phone timing exists.

**Tests.** `ServoTest` (10): finite, centred, under the LOOP line, silent tail; peak match and class; every control
moves every voice and an unknown or NaN one changes nothing; SAG 0 makes GROWL a bit-exact no-op and GROWL
matters with the rail on; the rail stays inside its bound at the worst corner on every take; the tail is within
30 cents of the TUNE root at every SAG × GROWL corner while the sag does lower the body; soft is a different
hit; four takes differ and replay; scramble bounds; names. `ServoKitsTest` (9): determinism, bounds, loudness
match, velocity, takes, the shared rail changes the phrases, the built grid and provenance, the three kits
differ. `ServoKitTest` (3): the grid through save, reload, playback and MPC 3; playback picks the zone by
velocity and walks the takes in order; the kits are on the shelf once each.
