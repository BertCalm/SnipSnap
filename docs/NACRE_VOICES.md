# NACRE's new sources: HARMONICS, SHELL and BUBBLE

Status: **kept by the owner** as new NACRE voices, in two rounds on 15 Nov 2026, each all KEEP with no notes:
- **`nacre-voices-1`** (H1, H2, H3, L1, L2 and L3): HARMONICS and SHELL.
- **`nacre-voices-2`** (Q1, Q2, U1, U2 and U3): the eight HARMONICS and SHELL presets, and BUBBLE.

Every render behind those clips is pinned (`NacreVoicesTest`). All three are NACRE voices in the SYNTH picker (`NacreVoice.HARMONICS`, `NacreVoice.SHELL`, `NacreVoice.BUBBLE`) with four presets each. BUBBLE's first preset, Bubble Drop, is the sound the owner kept; its other three presets are **not yet heard** (see [Still open](#still-open)). There is no starter or kit yet. See [NACRE.md](NACRE.md) for PEARL and the design notes this follows.

## What the owner asked for

From the NACRE voice design (14 Nov 2026):
- **New sources are allowed.** Each keeps NACRE's sympathetic harp strings and its water.
- **The first round is HARMONICS and SHELL.**
- **GROTTO and CONCH** wait for a HOLD decision.
- **BUBBLE** came next, in round `nacre-voices-2`, and was kept.

## Saved models

| Model | Source | What it is |
| --- | --- | --- |
| 1–4 | PEARL | Unchanged. Model 3 is the default; model 4 adds CONTACT and strike shapes. |
| 5 | HARMONICS | Harp harmonics: a finger touches each string's midpoint at the pluck. |
| 6 | SHELL | Small struck modal shells instead of strings. |
| 7 | BUBBLE | A pitched bubble that glides up onto its note. |

- **How a saved recipe chooses a source:** an ordinary `NacrePatch` whose `voice` is `HARMONICS` (model 5), `SHELL` (model 6) or `BUBBLE` (model 7). Each voice has exactly one saved model: `Nacre.defaultModel(voice)` gives it, `NacreModels.supports(voice, model)` checks the pair, and a mismatch is refused. PEARL keeps models 1–4.
- **Controls:** the same six as model 3 (TOUCH, DUET, CURRENT, VENUE, BLOOM, HOLD), with PEARL's defaults. HOLD must be 0, and CONTACT is refused.
- **Code:** all three live in `NacreVoices.kt`. `NacreModels` routes models 5, 6 and 7 to it; nothing in models 1–4 is called or changed. Making BUBBLE a saved model changed no sample: its renders match the desk clips byte for byte.

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

## Presets

Four per voice, all at C3, velocity 1, seed 7. The first of each is the voice's default sound, the one the owner kept.

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
| BUBBLE | Soft Bubble | .10 | .15 | .20 | .15 | .45 | **not yet heard** |
| BUBBLE | Bubble Stream | .30 | .55 | .35 | .60 | .50 | **not yet heard** |
| BUBBLE | Bubble Burst | .80 | .15 | .20 | .15 | .70 | **not yet heard** |

BUBBLE's new three:
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
- **Kept renders,** pinned by SHA-256:
  - the 22 renders behind H1–H3 and L1–L3 (`nacre-voices-1`);
  - the eight HARMONICS and SHELL presets behind Q1 and Q2 (`nacre-voices-2`);
  - the renders behind U1–U3 (`nacre-voices-2`): eleven BUBBLE notes, through a saved model-7 recipe, and PEARL at C3 and C4.
  - For both rounds, a git-excluded scratch test re-rendered every desk clip and found it identical to the published WAV, byte for byte.

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
  - BUBBLE: the same first three kinds of clip, at its default controls (Bubble Drop).
  - Other notes, touches and settings have not been heard.
- **Not yet heard: three BUBBLE presets** (Soft Bubble, Bubble Stream, Bubble Burst). They pass in software, but no one has listened to them. A small follow-up round, `nacre-voices-3`, is proposed: one clip of the four BUBBLE presets at C3, 3.5 s apart, as Q1 and Q2 were. Nothing waits on it.
- **Not built:**
  - GROTTO and CONCH, which wait for a HOLD decision;
  - a starter or kit for HARMONICS, SHELL and BUBBLE;
  - velocity layers and round robin;
  - keygroups and HOLD;
  - the spec's conch and cave venues.
