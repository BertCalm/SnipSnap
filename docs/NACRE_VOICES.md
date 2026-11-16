# NACRE's new sources: HARMONICS, SHELL, BUBBLE, GROTTO and CONCH

Status: **kept by the owner** as new NACRE voices, in five rounds on 15 and 16 Nov 2026, each all KEEP with no notes:
- **`nacre-voices-1`** (H1, H2, H3, L1, L2 and L3): HARMONICS and SHELL.
- **`nacre-voices-2`** (Q1, Q2, U1, U2 and U3): the eight HARMONICS and SHELL presets, and BUBBLE.
- **`nacre-voices-3`** (Q3): BUBBLE's four presets.
- **`nacre-grotto-1`** (D1 to D10): GROTTO, the bowed bass string with a real held loop (see [GROTTO](#grotto-model-8)).
- **`nacre-conch-1`** (O1 to O11, 16 Nov 2026): CONCH, the pair of blown shells with a real held loop (see [CONCH](#conch-model-9)).

Every render behind those clips is pinned (`NacreVoicesTest`, `NacreGrottoKeptTest`, `NacreConchKeptTest`). All five are NACRE voices in the SYNTH picker (`NacreVoice.HARMONICS`, `NacreVoice.SHELL`, `NacreVoice.BUBBLE`, `NacreVoice.GROTTO`, `NacreVoice.CONCH`) with four presets each, and every one of those twenty presets is a sound the owner heard and kept. There is no starter or kit yet. See [NACRE.md](NACRE.md) for PEARL and the design notes this follows.

## What the owner asked for

From the NACRE voice design (14 Nov 2026):
- **New sources are allowed.** Each keeps NACRE's sympathetic harp strings and its water.
- **The first round is HARMONICS and SHELL.**
- **GROTTO and CONCH** waited for a HOLD decision. The owner decided it on 15 Nov 2026: real held loops on GROTTO and CONCH only, opening with NACRE's own slow swell; PEARL, HARMONICS, SHELL and BUBBLE stay one-shot and byte-identical.
- **BUBBLE** came next, in round `nacre-voices-2`, and was kept.
- **GROTTO** followed, in round `nacre-grotto-1`, and was kept in full.
- **BUBBLE's presets** were heard in round `nacre-voices-3` and kept.
- **CONCH** came last, in round `nacre-conch-1`, and was kept in full. NACRE now has its six voices.

## Saved models

| Model | Source | What it is |
| --- | --- | --- |
| 1–4 | PEARL | Unchanged. Model 3 is the default; model 4 adds CONTACT and strike shapes. |
| 5 | HARMONICS | Harp harmonics: a finger touches each string's midpoint at the pluck. |
| 6 | SHELL | Small struck modal shells instead of strings. |
| 7 | BUBBLE | A pitched bubble that glides up onto its note. |
| 8 | GROTTO | A bowed bass string under quiet glassy glints, C2 to C4, with a real held loop. |
| 9 | CONCH | A pair of blown shells an octave apart, C2 to C5, with a real held loop. |

- **How a saved recipe chooses a source:** an ordinary `NacrePatch` whose `voice` is `HARMONICS` (model 5), `SHELL` (model 6) or `BUBBLE` (model 7). Each voice has exactly one saved model: `Nacre.defaultModel(voice)` gives it, `NacreModels.supports(voice, model)` checks the pair, and a mismatch is refused. PEARL keeps models 1–4.
- **Controls:** the same six as model 3 (TOUCH, DUET, CURRENT, VENUE, BLOOM, HOLD), with PEARL's defaults. CONTACT is refused. HOLD must be 0 on models 5 to 7; only GROTTO's model 8 and CONCH's model 9 save a nonzero HOLD (`NacreModels.supportsHold`).
- **Code:** HARMONICS, SHELL and BUBBLE live in `NacreVoices.kt`; GROTTO in `NacreGrotto.kt` and CONCH in `NacreConch.kt`, with their held clock in `NacreHeld.kt`. `NacreModels` routes models 5, 6 and 7 to `NacreVoices`, model 8 to `NacreGrotto` and model 9 to `NacreConch`; nothing in models 1–4 is called or changed. Making BUBBLE a saved model changed no sample: its renders match the desk clips byte for byte.

## What every source keeps from PEARL

One change to the shared strum: a softer strike sets fewer of the higher strings or shells ringing (each string's share is scaled by (.35 + .65 × velocity) to the power of its place in the harp). So a soft hit is darker as well as quieter. PEARL's own law has no such term, and PEARL is unchanged.

- **The paired performers:** a bass harp and an upper harp an octave apart, four strings each (ratios 1, 2, 4 and 8), using PEARL's strum law. DUET sets the answering offset and turns the upper strum downward at .65 and above.
- **The two harp bodies:** the same three modes each.
- **The eight sympathetic harp strings,** tuned to the note and driven by the bodies through the same one-way coupling.
- **The water:**
  - PEARL's internal current at its core .20.
  - Model 3's coral and seaweed returns: `Nacre.returns`, now `internal`, unchanged.
  - VENUE, CURRENT and BLOOM, as model 3 applies them after the source.

## HARMONICS (model 5)

A harpist plays a harmonic by plucking with a finger resting lightly on the string's midpoint; the finger lifts and the string rings an octave up, purer and rounder.

- **The string:** each one is PEARL's waveguide loop, tuned an octave below the note.
- **The touch:**
  - For 3 to 6 of the string's periods after the pluck (longer at a softer TOUCH), the returning wave is averaged with itself one sounding period earlier. That cancels every odd string mode, the ones without a node at the midpoint.
  - The finger's own low-pass softens what passes: cutoff 1.5–6 times the note, darker at a softer TOUCH and velocity.
  - The touch then lifts over one period, and the even modes ring on at the requested note.
- **The pluck** is near the quarter point, where a harmonic is played. It adds a tone at the sounding pitch, as PEARL's pluck does. A softer strike has less pick noise against that tone.
- **The loop** keeps only a few partials of the note: its brightness is 2.2–5 times the note (PEARL's is 6–21 times), a little darker for a softer strike. It rings 25% longer than PEARL's.
- **Stability:** the touch can only remove energy. It mixes past returns convexly, and its low-pass has unity gain at DC.

## SHELL (model 6)

- **The shells:** each string event strikes a small shell with five modes at 1, 2.41, 4.13, 6.20 and 8.57 times the note, weighted 1, .70, .45, .28 and .16. Modes above about 15.9 kHz are dropped.
- **Decay:** each mode is a damped two-pole resonator. A mode's decay shortens with its ratio^.45 from 2.6 s (bass) or 1.9 s (upper), falling by .8 per string up the harp. The lowest mode, at the note, rings longest, so the ring-out carries the pitch.
- **The mallet:** a raised-cosine contact. It lasts longer, so it sounds duller, at a softer TOUCH and velocity: (.12 + .9 × (1 − TOUCH)) × (1.8 − .8 × velocity) ms.

## BUBBLE (model 7)

- **The source:** each string event is a bubble, a damped tone that starts below its note and glides up onto it (the "bloop"), with a faint octave and a short breath of noise at the onset.
- **The glide:** 0.8 to 3 semitones, deeper at a firmer TOUCH and a harder strike. It approaches the note exponentially over 14–24 ms, so the ring-out is on pitch.
- **Decay:** 1.1 s (bass) and 0.75 s (upper), falling by .8 per string up the harp.
- **Softer is darker:** a softer strike has a shallower glide, less octave and a darker, quieter breath, on top of the shared law that rings fewer upper strings.
- **What it keeps from PEARL:** the paired performers, bodies, sympathetic strings and water, as HARMONICS and SHELL do.

## GROTTO (model 8)

A bow drives the bass harp's root string; NACRE's harps, sympathetic strings and water run after it, as on the other voices. Quiet glassy glints on the upper harp answer it.

- **The string:** a stick-slip bowed waveguide (`Strings.Bow`) in ARCO CELLO's measured C2 to C4 grip window, so GROTTO plays MIDI 36 to 60 (a patch outside that range is refused). TOUCH and velocity walk the window together: a firmer touch or a harder note presses harder on a brighter string, and a softer note reaches the harps darker.
- **The stroke:** NACRE's own slow swell, the owner's choice for a held start: the bow reaches its speed over 0.35 to 0.8 s (slower at a lighter TOUCH).
- **The glints:** HARMONICS' touched-node pluck on the upper harp at 2, 4, 8 and 16 times the note, 15 to 20 dB under the bow. DUET sets how many come in each loop's length (none at 0, two at the default .15, five at 1); a firmer bow pushes the first one later.
- **HOLD:**
  - Below .99 it sets a finite bow of 2.5 s (HOLD 0) to 8 s (.98). The bow then lifts over 0.12 s and the strings ring for 3 s.
  - The top step is a loop: whole note periods in whole frames, about 3.71 s, two swells of the water. Every water rate is fitted to whole cycles of the loop (the held seaweed moves at about 1.9 a second instead of 2.0). The bow closes by ARCO's retune route; the linear chain after it closes by settling. A loop whose seam is over the house bar is refused by name, never crossfaded.
  - A finite note is the held note, sample for sample, until the bow begins to lift.
  - `NacrePatch.render()` returns the loop alone at the top step (class LOOP); `NacreGrotto.renderKey` returns the note's own stroke and warm-up with the loop marked after it.
- **Two changes from the design, both told to the owner before the round:**
  - The slow swell in the held part moves the string's level (±14%), not the bow's speed. Varying the bow speed pulled the note out of tune, so the loop wouldn't close above C2 (cycle error 2e-2 at F2 and 0.36 at C3, against 1e-6 to 1e-5 with the bow held steady).
  - The sympathetic strings are lifted for GROTTO (`SYMPATHETIC_LIFT` 3). Under a steady bow they sat 26 dB under it at NACRE's own gain; lifted, they sit about 16 dB under on the loop.
- **Level:** a fixed reference gain (`OUTPUT_GAIN` 1.6), so level follows velocity as on every NACRE voice.
- **Measured on the round's held C2** (velocity .8, seed 7): 163,842 frames (3.715 s, 243 periods), seams 5.6e-9 before and 2.6e-8 after, cycle error 4.3e-7, one retune pass. Held D3: 163,687 frames, 545 periods. No clip needed turning down.

### Round `nacre-grotto-1` (15 Nov 2026, all KEEP, no notes)

| Clip | What it played |
| --- | --- |
| D1 | C2 finite, default controls, a 4 s bow |
| D2 | C2, TOUCH .15, .30 and .70, 4 s bows |
| D3 | C3 then C4, 4 s bows |
| D4 | Held C2 from its first stroke, then the loop three times |
| D5 | The held loop alone, eight times end to end |
| D6 | Held C2 at DUET 0, .15 and .55, each loop twice |
| D7 | Held C2 at VENUE 0 and 1, then BLOOM .15 and .70, each loop twice |
| D8 | C2 soft, medium and hard (velocity .35, .65, 1), 4 s bows |
| D9 | GROTTO beside ARCO CELLO and OSTERIA BOWED at C2, then beside ARCO SARANGI at D3, matched in loudness |
| D10 | A slow overlapping bass line, C2, F2, G2, at HOLD 0 (2.5 s bows) |

Everything not named is at the default controls, velocity .8 and seed 7.

## CONCH (model 9)

Two shells blown together, the second an octave above the first: the soft, hollow breath across a shell's opening the owner chose on 15 Nov 2026, not a lip-buzzed blast. NACRE's harps, sympathetic strings and water run after the shells, as on the other voices.

- **The shell:** a jet across the mouth of a vessel. A `Modes.Bank` holds the cavity's mode at the note and three weak, inharmonic spiral-chamber modes (2.3, 3.8 and 5.4 times the note); a delayed jet, bounded by `tanh`, reads the cavity and drives the bank. Only the cavity is in the jet's loop, so the chamber colours the tone without sounding a pitch of its own. CONCH plays MIDI 36 to 72 (C2 to C5); a patch outside that range is refused.
- **The breath:** TOUCH and velocity set one breath pressure: level, how hard the jet saturates, how far its flow reaches up the spectrum, its edge tone, the chamber and the hiss. So a harder breath is brighter as well as louder. The breath comes in with NACRE's own slow swell, 0.55 to 1.4 s (slower at a lighter TOUCH).
- **The pair:** the bass shell sounds into the bass harp and the upper shell an octave above into the upper harp. They meet only downstream, in NACRE's linear chain. DUET sets how much later the upper shell's breath enters (PEARL's answering law, scaled to a breath). From DUET .65 the shells lean against each other: the upper shell gets one whole period more than the octave in each loop (two from .85), a slow beat of exactly that many cycles per loop.
- **HOLD:**
  - Below .99 it sets a finite breath of 2.5 s (HOLD 0) to 8 s (.98). The breath then fades over 0.3 s and the shells and strings ring for 3 s.
  - The top step is a loop of whole periods of both shells in whole frames, about 3.71 s, two swells of the water, on GROTTO's held clock (`NacreHeld`). Each shell closes on its own by ARCO's retune route (`LoopRetune`); the chain after them closes by settling. A loop whose seam is over the house bar is refused by name, never crossfaded.
  - A finite note is the held note, sample for sample, until its breath begins to fade.
  - `NacrePatch.render()` returns the loop alone at the top step (class LOOP); `NacreConch.renderKey` returns the note's own breath and warm-up with the loop marked after it.
- **Two changes from the design:**
  - **The breath's hiss is heard beside the jet, not fed into it.** The hiss is a seeded noise exactly one loop long, coloured by the shell's own modes and laid beside the tone, so it can't unlock the loop (the design's named fallback). Turbulence reaches the cavity only while the breath swells in: that is what starts the jet, and it is gone before the loop.
  - **The sympathetic strings are lifted for CONCH** (`SYMPATHETIC_LIFT` 2, tilted up further below C3), as GROTTO's are under its bow. A shell's near-sinusoid gives the bass harp's body little to answer at C2, where the strings measured 25 dB under the shells, against 15 dB from C3 up.
- **Level:** a fixed reference gain (`OUTPUT_GAIN` .55), so level follows velocity as on every NACRE voice.
- **Measured on the round's held C3** (velocity .8, seed 7): 163,842 frames (3.715 s; 486 periods of the bass shell, 972 of the upper), seams 5.4e-9 before and 1.0e-8 after, cycle error 6.4e-9, one retune pass per shell. Held C2: 243 periods; held C4: 163,673 frames, 971 periods. The worst seam in the round was 1.7e-7 (DUET .80, whose upper shell has 973 periods: one beat a loop). Only O10, the matched A/B, was turned down (1.68 dB) to stay under .94.

### Round `nacre-conch-1` (16 Nov 2026, all KEEP, no notes)

| Clip | What it played |
| --- | --- |
| O1 | C3 finite, default controls, a 4 s breath |
| O2 | C3, TOUCH .15, .30 and .70, 4 s breaths |
| O3 | Held C3 at DUET .15, .50 and .80, each from its first breath, then the loop once more |
| O4 | Held C3 from its first breath, then the loop three times |
| O5 | The held loop alone, eight times end to end |
| O6 | C2, C4 and C5, 4 s breaths |
| O7 | Held C2, then held C4, each loop twice |
| O8 | Held C3 at VENUE 0 and 1, then BLOOM .15 and .70, each loop twice |
| O9 | C3 soft, medium and hard (velocity .35, .65, 1), 4 s breaths |
| O10 | CONCH held beside BORE FLUTE, EMBOUCHURE WHISTLE, OBBLIGATO and NACRE BUBBLE at C4, then beside OBBLIGATO at C3, matched in loudness |
| O11 | A short overlapping phrase, C3, E3, G3, C4, G3, at HOLD 0 (2.5 s breaths) |

Everything not named is at the default controls, velocity .8 and seed 7.

## Presets

Four per voice, every one heard and kept. HARMONICS, SHELL and BUBBLE are at C3, velocity 1, seed 7; GROTTO's are at C2 and CONCH's at C3, velocity .8, seed 7, as rounds `nacre-grotto-1` and `nacre-conch-1` played them. The first of each is the voice's default sound.

| Voice | Preset | TOUCH | DUET | CURRENT | VENUE | BLOOM | Heard |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| HARMONICS | Glass Harmonic | .30 | .15 | .20 | .15 | .35 | kept (H1–H3, Q1) |
| HARMONICS | Soft Harmonic | .10 | .15 | .20 | .15 | .45 | kept (Q1) |
| HARMONICS | Harmonic Kelp | .30 | .15 | .35 | 1 | .50 | kept (Q1) |
| HARMONICS | Harmonic Ring | .70 | .15 | .20 | .15 | .70 | kept (Q1) |
| SHELL | Shell Tap | .30 | .15 | .20 | .15 | .35 | kept (L1–L3, Q2) |
| SHELL | Deep Shell | .15 | .15 | .20 | .15 | .60 | kept (Q2) |
| SHELL | Shell Reef | .30 | .15 | .30 | 0 | .55 | kept (Q2) |
| SHELL | Shell Bloom | .60 | .15 | .20 | .60 | .80 | kept (Q2) |
| BUBBLE | Bubble Drop | .30 | .15 | .20 | .15 | .35 | kept (U1–U3) |
| BUBBLE | Soft Bubble | .10 | .15 | .20 | .15 | .45 | kept (Q3) |
| BUBBLE | Bubble Stream | .30 | .55 | .35 | .60 | .50 | kept (Q3) |
| BUBBLE | Bubble Burst | .80 | .15 | .20 | .15 | .70 | kept (Q3) |
| GROTTO | Grotto Bow | .30 | .15 | .20 | .15 | .35 | kept (D1); a 4 s bow |
| GROTTO | Soft Grotto | .15 | .15 | .20 | .15 | .35 | kept (D2, the light bow); a 4 s bow |
| GROTTO | Grotto Glints | .30 | .55 | .20 | .15 | .35 | kept (D6, the most glints); held loop |
| GROTTO | Grotto Kelp | .30 | .15 | .20 | 1 | .35 | kept (D7, the far seaweed); held loop |
| CONCH | Conch Breath | .30 | .15 | .20 | .15 | .35 | kept (O1); a 4 s breath |
| CONCH | Soft Conch | .15 | .15 | .20 | .15 | .35 | kept (O2, the light breath); a 4 s breath |
| CONCH | Conch Lean | .30 | .80 | .20 | .15 | .35 | kept (O3, the slow lean); held loop |
| CONCH | Conch Bloom | .30 | .15 | .20 | .15 | .70 | kept (O8, the most bloom); held loop |

Every GROTTO preset is one of the renders the owner kept, sample for sample (`NacreGrottoKeptTest`); none adds an unheard setting. The 4 s bow is HOLD `NacreGrotto.holdFor(4f)` (about .267), the same value the desk used, and the held two use HOLD 1, the held recipe of D4 and D5.

CONCH's presets follow the same rule (`NacreConchKeptTest`): each is one of the renders the owner kept, sample for sample. The 4 s breath is HOLD `NacreConch.holdFor(4f)` (about .267), the value the desk used, and the held two use HOLD 1, the held recipe of O4 and O5.

BUBBLE's four presets were heard together in round `nacre-voices-3` (Q3: the four at C3, 3.5 s apart, as Q1 and Q2 were) and kept; their renders are pinned in `NacreVoicesTest`.

BUBBLE's other three:
- **Soft Bubble:** a light touch. A shallower, rounder bloop with less octave and breath, and a little more bloom.
- **Bubble Stream:** DUET .55 spreads the two harps' bubbles apart into a trickle, in moving, wetter water.
- **Bubble Burst:** a firm touch. The deepest bloop, with the most octave and breath, and a long bloom.

Measured at C3 against Bubble Drop (software only): Soft Bubble is 3.6 dB darker and about 3 dB quieter, but its ring-out timbre is close (0.62 on the ring-out measure, under PEARL's widest pair of 0.81), so it differs mostly in the strike. Bubble Stream (4.6) and Bubble Burst (2.2) are further apart. Bubble Burst, 6.5 dB brighter, reaches the .94 peak guard.

## Level

- **Fixed reference gain:** each source has one output gain (`HARMONICS_OUTPUT_GAIN` 4.3, `SHELL_OUTPUT_GAIN` 1.935, `BUBBLE_OUTPUT_GAIN` 3.35). They were set once, so that hard C3 at the default controls has PEARL's level: RMS over the first 2 s is 0.0998 (HARMONICS), 0.0999 (SHELL), 0.0999 (BUBBLE) and 0.0999 (PEARL). They are not matched per patch, so velocity and TOUCH keep their effect on level.
- **Peak guard:** a scalar guard holds peaks at or under .94.

## Tests (`NacreVoicesTest`, `NacreVoiceRegistrationTest`)

- **PEARL unchanged:** every PEARL model (1–4) at three notes and strikes, all five presets and a strike-shaped layer render byte for byte as on the untouched base (0d57e9e80).
- **Saved models:** 5, 6 and 7 replay exactly from JSON and refuse CONTACT and HOLD.
- **Determinism:** renders repeat exactly, and the seed is a real input.
- **Peak:** at or under .94 at MIDI 28, 36, 48, 60 and 72, with the loudest settings.
- **Pitch:** within 8 cents at MIDI 36, 48, 60 and 72. HARMONICS is measured 0.3–1.5 s, SHELL on its ring-out at 1.5–3.5 s.
- **Duller when softer:** the share of power above four times the note (first 0.3 s) rises by at least 1 dB per step, both with velocity (.3, .65, 1) and with TOUCH (0, .3, 1). The spectral centroid didn't work as a measure: the fundamentals dominate it.
- **A new voice, not a setting:**
  - The measure is timbre on the ring-out: semitone-band power from 0.5 to 3 s, in dB against the strongest band, floored at −45 dB.
  - Each source must be further from every PEARL setting (presets, TOUCH 0/1, DUET 1, BLOOM 1, a soft strike) than the widest pair of PEARL settings is apart, with a margin.
  - The same margin applies between each pair of HARMONICS, SHELL and BUBBLE.
- **Render budget:** a cold render takes under 20 s.
- **Registration:**
  - The four voice entries and their saved models.
  - Every entry point (`Nacre.render`, `NacrePatch`, `NacreVoices`) renders the same.
  - Four presets per voice, names of 14 letters or fewer, JSON replay and roster lookup.
  - The first preset is the kept default sound (pinned hash), and no two presets render the same.
  - On the phone, `NacreSynthScreenTest` reaches each voice and its presets (not yet run on a device).
- **BUBBLE's pitch** is measured on the direct bass harp (0.05–0.6 s): its tone decays within about a second, so in the full mix the delayed water returns, copies of the same tone, pull the spectral peak. At C3 the mix reads −16 cents while the bass harp, the sympathetic strings and the BLOOM-0 mix read −0.1, −0.1 and +1.1.
- **GROTTO** (`NacreGrottoTest`, `NacreHoldRefusalTest`, `NacreGrottoKeptTest`): the held loop closes under the house seam at C2, C3 and C4, soft and hard and at the TOUCH × DUET corners, with no wrap step bigger than the loop's own; every note from C2 to C4 settles within 0.05 cents of its plan; the bow speaks in Helmholtz motion (one slip a period); a finite note is the held note until the lift; DUET 0 has no glints; the sympathetic strings and the water stay within 18 dB of the bow on the loop; models 1 to 7 refuse HOLD in the renderer, the patch and JSON.
- **CONCH** (`NacreConchTest`, `NacreHoldRefusalTest`, `NacreConchKeptTest`): the held loop closes without a click from C2 to C5, soft and hard and at the TOUCH × DUET corners; the loop played eight times end to end is the real next lap; every note settles in tune, both shells, at its own octave; a finite note is the held note until the breath fades; DUET moves the upper shell's answer and, from .65, makes a whole-cycle beat; the sympathetic strings and the water are still heard under a held breath; velocity and TOUCH change the breath's colour, not only its level; the shells stay bounded; GROTTO's kept renders don't move.
- **Kept renders,** pinned by SHA-256:
  - the 22 renders behind H1–H3 and L1–L3 (`nacre-voices-1`);
  - the eight HARMONICS and SHELL presets behind Q1 and Q2 (`nacre-voices-2`);
  - the renders behind U1–U3 (`nacre-voices-2`): eleven BUBBLE notes, through a saved model-7 recipe, and PEARL at C3 and C4.
  - BUBBLE's four presets behind Q3 (`nacre-voices-3`).
  - every GROTTO render behind D1 to D10 (`nacre-grotto-1`): the finite notes, the held key and its loop, and each held setting's loop; and the four GROTTO presets against them.
  - every CONCH render behind O1 to O11 (`nacre-conch-1`): the finite notes, the held C3's key and its loop, each held setting's loop, the held C2 and C4, and O10's BORE, EMBOUCHURE, OBBLIGATO and BUBBLE references; and the four CONCH presets against them.
  - For every round, a git-excluded scratch test re-rendered every desk clip and found it identical to the published WAV, byte for byte.

## Measured (C3, defaults, seed 7)

| | HARMONICS | SHELL | PEARL |
| --- | --- | --- | --- |
| Pitch error, MIDI 36 / 48 / 60 / 72 (cents) | 1.3 / 2.5 / 0.5 / −0.6 | 0.0 / 0.0 / 0.0 / 0.0 | 0.7 at 48 |
| Brightness above 4 × note, soft / medium / hard (dB) | −29.3 / −28.1 / −25.4 | −9.5 / −8.3 / −7.3 | −21.0 / −21.0 / −21.0 |
| Brightness, TOUCH 0 / .3 / 1 (dB) | −28.3 / −25.4 / −19.1 | −10.0 / −7.3 / −4.5 | |
| Nearest PEARL setting, ring-out timbre | 2.43 | 1.77 | widest PEARL pair: 0.81 |
| Peak at hard C3 | 0.81 | 0.68 | 0.55 |
| Cold render | 0.4–1.0 s | 0.3–0.4 s | |

- HARMONICS and SHELL are 4.34 apart on the same measure.
- BUBBLE at C3: brightness soft / medium / hard −30.9 / −25.5 / −20.9 dB; peak at hard C3 0.80. Its pitch is under Tests.
- The first half second is different. There, PEARL's DUET and TOUCH settings differ from each other as much as these sources differ from PEARL, because DUET moves the strum. So the numbers alone don't settle "new voice or PEARL setting". The owner's ears did: both were kept as new voices.

## Still open

- **Heard:**
  - HARMONICS and SHELL: soft, medium and hard at C3; a phrase from C2 to C5; a side-by-side with PEARL at C3 and C4; and all eight presets at C3.
  - BUBBLE: the same first three kinds of clip, at its default controls (Bubble Drop), and all four presets at C3 (Q3).
  - GROTTO and CONCH: their rounds in full (see each voice above).
  - Other notes, touches and settings have not been heard.
- **GROTTO and CONCH on the phone and the MPC:** the picker shows GROTTO after BUBBLE and CONCH after GROTTO, each with HOLD, plays a held preview as a loop, and saves and recalls a held recipe (`NacreSynthScreenTest`, not yet run on a device). Neither has been heard on a phone. No MPC check yet: the loop's wrap, release on key-up and MPC 2's mono-per-pad retrigger on a held pad. The CLI marks a held render's WAV as one whole `smpl` loop.
- **Not built:**
  - a starter or kit for HARMONICS, SHELL, BUBBLE, GROTTO and CONCH;
  - velocity layers and round robin;
  - keygroups (for GROTTO and CONCH, the shared loop-zone route, or a NACRE maker that keeps the stroke or breath, is the owner's call);
  - the spec's conch and cave venues.
