# ETCH: design brief and round-1 audition

Status: **round 3, Python prototype; the owner accepted the round-3 audition 2026-11-12.** No Kotlin exists. The engine stays in
progress until every applicable owner gate has evidence (see "Gates" below).

Prototype: [`prototype/etch/`](../prototype/etch/) (`etch_proto.py` is the model,
`gen_audition.py` renders and measures, `build_page.py` writes the audition page).
Every number below is from that Python harness, not from Kotlin.

## What it is

A squelching step-line bass, through a drive that glues the steps, into a tempo delay and a
reverb. The owner supplied two reference clips on 2026-11-11: a minimal, chilled groovebox jam
whose track 11 is the bass, and a 303-style box with a hand riding cutoff, resonance,
envelope and decay. The owner named the processing: a bass overdrive or transparent boost
first, to gently saturate the filter peaks and glue the steps, then a stereo delay and reverb
pedal.

**Gesture line.** The characteristic gesture is a sixteen-step line → accents bite and build
→ slides carry the pitch and keep the filter open → the drive evens out the peaks →
dotted-eighth repeats fall into a room behind it.

Working name ETCH (acid etches metal). The words "acid" and "303" are on the preset trademark
blocklist (`PresetTestSupport.kt`), so neither can name the engine, a voice or a preset.

## References, distilled (gear-modeling.md §1)

| Reference | Mechanism (no brand words) | Source |
|---|---|---|
| Clip A (groovebox jam) | about 112 BPM, low notes F1 and C2, darker; centroid 150 Hz to 5 kHz about 0.9 to 1.5 kHz with drums and chords in the mix | measured, `analyze_ref.py` in the session scratchpad |
| Clip B (303-style box) | about 130 BPM, C2 with octave jumps to C3/C#3; centroid rises from about 1.0 to 2.6 kHz while the hand rides the cutoff | measured, same |
| Bass overdrive | a dry/overdrive balance control, gain, and a two-band EQ after the clip | [manual](https://www.adorama.com/col/productManuals/BSODB3.pdf) |
| Delay and reverb pedal | delay up to ~2 s with repeats to near-infinite; tone tilt in the delay line only; reverb follows delay in the signal path; tap divisions including dotted eighth; a reverse-delay mode | [manual](https://manuals.plus/earthquake/avalanche-run-r2-manual.pdf) |
| The original slide | an RC portamento, 100 kΩ and 220 nF: τ = 22 ms, settled in about 66 ms | [synth-diy thread](https://synth-diy.org/pipermail/synth-diy/2012-May/140040.html) |
| The original accent sweep | a capacitor charged by accents, more prominent as resonance rises, so consecutive accents climb | [x0x notes](https://ladyada.net/wiki/x0x/envelopemods) |

Not confirmed by a source and therefore proposed: that a slid note does not retrigger the
filter envelope.

## Why it earns a pad (sibling check)

- **VELVET SQUELCH** (`Velvet.kt`): one stab through a Chamberlin SVF, with SQUEEZE moving
  resonance and envelope together. It renders one note at a time, so nothing carries from one
  step to the next.
- **FATHOM** (`Fathom.kt`): drive before a resonant low-pass and GLIDE on every voice, but no
  accent, no accent memory and no step line.

What neither can do: state that crosses note boundaries (an accent capacitor that climbs over
a run, a slide that holds the gate and the envelope), a line as the unit of sound, and a tempo
delay whose tail wraps around a loop pad.

## Signal flow

```
16-step line ─► VOICE (4x) ───────────► BITE (4x) ──────► bandLimit ─► SPACE (1x) ─────► finish
                polyBLEP saw / square    pre-emphasis      decimate     delay ─► reverb    DC, level,
                4-pole ladder, tanh      soft clip,                     (tempo, tone in    fade
                accent cap, RC slide     de-emphasis,                   the loop)
                                         blend with clean
```

BITE runs before decimation because a clipper makes new harmonics (the VALVE and FORGE rule).
SPACE runs after it because delay and reverb are linear apart from a bounded feedback.

## Macro contract

| Control | Default | Changes | Measured (Python, LINE B) |
|---|---|---|---|
| TUNE | 0.5 | ±12 semitones around C2 | |
| CUTOFF | 0.3 | base cutoff 110 Hz to 1.2 kHz (exp), soft ceiling 3.5 kHz on the opened filter | energy above 2 kHz −18.7, −12.5, −10.9 dB at 0.1, 0.4, 0.75 |
| PEAK | 0.7 | ladder feedback (k = 4.15 × PEAK), accent-cap discharge time | −25.2, −17.7, −10.5 dB at 0.3, 0.65, 0.95 |
| SWEEP | 0.5 | filter-envelope depth (0.6 to 4.2 octaves) and decay (0.12 to 1.72 s) together | −27.7, −15.3, −10.8 dB at 0.15, 0.5, 0.9 |
| BITE | 0.45 | drive 2 to 24 dB into the clip, drive share 0.3 to 0.85; 0 is an exact bypass | accent lift 5.9, 4.6, 3.6, 2.6 dB and crest 13.6, 11.8, 10.0, 7.4 dB at 0, 0.25, 0.5, 0.8 |
| SPACE | 0.4 | delay level 0.25 to 0.6 and feedback 0.2 to 0.62 together; reverb T60 0.5 to 3.2 s and level; 0 is an exact bypass | |
| Velocity | | the accent: deeper, shorter filter envelope, louder VCA, charges the accent capacitor | level-matched attack centroid 781, 816, 1112, 1324 Hz at 0.35, 0.6, 0.8, 1.0 |

The first CUTOFF map (110 Hz to 3.2 kHz, ceiling 16 kHz) was not monotonic. Upper-band energy
peaked around CUTOFF 0.4 and fell about 2 dB above it, because the resonant peak climbed past
where the saw has energy. The narrower map above rises across the whole knob at SWEEP 0.2 and
0.6. The first BITE high-passed its drive branch at 180 Hz; it left accent lift unchanged
(5.9 to 5.6 dB) because the fundamental bypassed the clip. Both were fixed before publishing.

## The three directions

| | Operator | Idea | Gesture | Novelty | Risk | Cost |
|---|---|---|---|---|---|---|
| **Strong** | STACK | ETCH: line voice + BITE + SPACE inside one engine, rendering notes and loops | clear | new (state across notes) | low | an engine (26 surfaces) |
| Alternate A | DISTIL | a VELVET voice with accent and slide, plus a PEDALS recipe over VALVE → ECHO → ROOM | stab only | low | low | small |
| Alternate B | COUPLE | the delay's feedback runs back through BITE and the ladder, so repeats are re-squelched | strong but busy | high | feedback stability, render time | engine plus solver care |

Strong wins because the reference gesture is the line, not the note: accent memory and slide
need state across steps, and a dotted-eighth tail must wrap around a loop. ECHO cannot do that
(60 to 350 ms, not tempo-synced, 1.1 s tail cap). Alternate B is proposed as the DUB voice.

## Landing

An engine. Loops render one pass beyond two warm-up passes, so delay and reverb tails from
the end of the loop already sound at its start. Seam error, measured from the end of pass 2
into pass 3 of one continuous render, is at most 1.7e-12 (SPACE 0.9) against `Keys.MAX_SEAM_ERROR` (1e-3).
The tempo comes from the line (`render` gets no tempo; the line documents its BPM).

## Round-1 audition

54 clips, all finite; repeated renders byte-identical. The page has the chain one stage at a
time for both lines, a four-bar cutoff ride, BITE and SPACE sweeps, the three filter macros,
accents in a run against spread out, slide off and on, single notes, velocity and round robin.

Measured highlights (Python harness):
- Accents in a run climb: 1,199 → 1,321 → 1,345 → 1,351 Hz; the same accents spread out stay
  at 1,229 Hz.
- The ride, starting from CUTOFF 0.05 and SWEEP 0.35 and climbing to 0.8: centroid by bar 805,
  1,114, 1,302, then back to about 1,100 so the loop closes; energy above 2 kHz −28 → −12 dB.
- BITE at 0.5 takes accented steps from 5.9 to 3.6 dB above plain ones (crest 13.6 → 10.0 dB).
- Round robin is subtle: attack spread across four takes 4.7% at velocity 0.6, 1.0% at 1.0.

## Gates (docs/ENGINE_ACCEPTANCE_GATES.md)

| Gate | Status |
|---|---|
| Six distinct melodic voices with presets | open: six chosen by the owner 2026-11-12 and all six built in Python; SAW and SQUARE heard, RUBBER, WHISTLE, GRIT and DUB not heard; no preset banks |
| Playable keygroups | not yet verified |
| Premade kit | open |
| Chord-tool compatibility | owner's call: the line voice is monophonic by design |
| Appropriate HOLD | open: proposed held note up to a seamless sustain loop, top step plays the line as LOOP |
| STRUM | not applicable: not a picked or thumbed string |
| Percussion roll/riser | not applicable: a bass engine |
| Drum genre kits | not applicable: not a drum engine |
| Velocity, rendering | pass in the Python harness only (timbral, level-matched) |
| Round robin, rendering | partial in the Python harness only: distinct takes, accented takes differ little |
| Velocity/RR saving, playback, export | not yet verified: no Kotlin, no kit, no MPC export |
| Sound character | not heard |

**Overall: incomplete.** Round 1 is a prototype.

## Render budget

Python with numba: a two-bar loop at three passes renders in 0.4 to 1.9 s; all 54 clips in
about 21 s. Proposed for Kotlin: the voice is one 4x per-sample loop with four one-pole stages,
the same order of work as VELVET at 4x, so a two-bar loop (3.7 to 4.3 s, three passes) should
stay inside the tens-of-ms one-shot rule for notes and around 100 ms for loops. Not measured.

## Sonic risks

- BITE's level match is global (whole-render RMS), so a very sparse line drives the clip
  differently from a busy one at the same knob. Offline, so acceptable, but heard only by ear.
- BITE darkens the top slightly: energy above 2 kHz falls from −11.7 to −13.7 dB between
  BITE 0 and 0.8 on LINE B, from its post-clip low-pass. If the owner hears it as dulling, open
  that low-pass.
- Round robin at full accent is close to repeated copies by the attack measure. If the owner
  hears them as one strike, a take needs a larger change (accent depth, envelope shape).
- The ride is baked in only for its audition clip. Baking motion into loop pads is the owner's
  call.
- Mono: the reference pedal is stereo; the house output is mono, so the width is lost.

## Ledger

- **Verified:** sibling engines and FX order read at `bf889e41`; ECHO's 60 to 350 ms and 1.1 s
  tail cap (`Echo.kt`); the blocklist entries for "acid" and "303".
- **Measured:** every number on this page and the audition page, Python harness.
- **Proposed:** every constant marked "shape" in `etch_proto.py`; the six voices; HOLD; the
  slide-does-not-retrigger behaviour.
- **Heard:** round 1b's square against the saw, "That's better"; the whole round-1b audition, "It all seems fine from the audition"; round 2, "Mostly good, but the 4 takes in RR sound the same"; then "It feels a bit mid heavy. Like dollar store headphones"; and the round-3 audition, "I'm good with evening in audition" (read as "everything") (all 2026-11-12). Round 3 is the accepted baseline; nothing in Kotlin, on a phone or on an MPC has been heard.

## Why it is built this way

- **Velocity is the accent.** On this kind of bass the accent is the dynamic: it opens the
  filter further and shortens its decay. So a harder pad hit changes the tone, which is what
  the velocity gate asks for, instead of only changing the volume.
- **The drive comes before the delay**, as you asked, and it carries the whole signal with the
  mids pushed into it. The loudest moments, accents and resonant peaks, hit the clipper hardest,
  so it acts as the compressor that glues the steps. The first version left the bass out of the
  drive and measured no glue at all.
- **The delay repeats lose lows and highs every pass**, so they sit above the bass instead of
  muddying it, and the reverb follows the delay, as the reference pedal does.
- **Loops are rendered three times and the last pass kept**, so the echoes from the end of the
  loop are already ringing at its start. Without that, every loop pad would restart dry.
- **The risky decision is the cutoff range.** The knob stops at a fairly dark ceiling (3.5 kHz
  on the opened filter) because above it the measured brightness fell instead of rising. Your
  ears decide whether the top of the knob is open enough.

## Round 1b: the square wave (2026-11-12)

The owner heard SAW and SQUARE as the same sound. Measured, they nearly were. The round-1
square was `tanh(6 × saw + 1.2)`, a 60/40 pulse with soft edges. A 50% square has almost
no even harmonics, and that hollowness is what tells it from a saw, but this pulse kept its
2nd harmonic at −11.5 dB (raw oscillator). After the filter at default settings, the two
waves' 2nd harmonics were −10.4 and −12.5 dB, within 2 dB. The audition also never played
one line in both waves: LINE A is square and LINE B is saw.

The square is now `tanh(25 × saw)`: 50% duty, short edges. Raw, its 2nd harmonic is −44 dB
and its 3rd −9.3 dB (an ideal square: absent and −9.5 dB). Rendered C2 at default settings,
2nd and 4th harmonics: saw −10.4 and −17.2 dB, square −33.3 and −44.5 dB. A held C2 with
the filter open: saw −8.6 dB, square −50.0 dB. The audition gains a "Saw against square"
section that plays LINE B's notes in both waves at the same settings. 58 clips. Every
square clip changed, including LINE A; not heard at the time.

**Heard, 2026-11-12:** after listening to round 1b the owner said "That's better" about the
square against the saw. This is the owner's judgement of the wave difference only. The
chain, the BITE and SPACE defaults, the ride, round robin and overall sound character have
not been judged yet.

**Heard, 2026-11-12, the whole round-1b audition:** "It all seems fine from the audition."
The owner accepted the published round-1b sound direction: the voice, BITE, SPACE (dotted
eighth into the reverb), the filter macros, accents, slide, single notes, velocity and the
round-robin takes as rendered. Preserve round 1b (`etch_proto.py` at `02b725f2`) as the
accepted baseline: the line defaults stand (LINE A BITE 0.4, SPACE 0.55; LINE B BITE 0.5,
SPACE 0.32; engine defaults BITE 0.45, SPACE 0.4). This is a listen to Python renders on
the audition page, not evidence of Kotlin, phone playback or an MPC. Not yet decided:
whether loop pads carry a baked cutoff ride, and which six voices complete the engine.

**Decided, 2026-11-12:** the owner chose all four proposed voices, so the six are SAW, SQUARE
(both built and accepted), RUBBER, WHISTLE, GRIT and DUB. For the ride the owner chose "some
pads ride": most loop pads hold one setting, and one or two kit pads carry a baked cutoff
ride, so the MPC gets both.

## Round 2: the four new voices (2026-11-12)

Built in the Python prototype, not heard. Each voice has its own mechanism, not only its own
macro settings. Every switch is off for SAW and SQUARE, and all 58 round-1b clips regenerate
**byte-identical** (sha256 of every WAV checked), so the accepted baseline is untouched.

| Voice | Mechanism | Defaults (CUTOFF PEAK SWEEP BITE SPACE) |
|---|---|---|
| RUBBER | square plus a square one octave down, mixed before the filter; low resonance | 0.25 0.3 0.3 0.3 0.2 |
| WHISTLE | the cutoff tracks the note (1 octave per octave); resonance ×1.15, past self-oscillation at the top of PEAK; level into the filter ×0.6, so the filter's own tone dominates | 0.45 0.95 0.2 0.35 0.35 |
| GRIT | a wavefolder before the filter (depth 6); velocity sets the number of folds | 0.35 0.7 0.55 0.5 0.15 |
| DUB | square; inside the delay loop each echo passes a unity-peak band-pass whose centre follows the echo's own level (280 Hz to 2.2 kHz), so every echo squelches again and fades darker | 0.2 0.6 0.4 0.3 0.85 |

Distinctness: the RMS difference of level-matched third-octave spectra of the six loops on one
line. The closest pairs are SAW-SQUARE 3.73 dB, SQUARE-GRIT 5.38 dB, SAW-WHISTLE 6.07 dB, SAW-GRIT 7.49 dB. SAW against SQUARE
(3.7 dB) is the pair the owner heard as clearly different, so every new voice clears the
heard floor by at least 1.6 dB.

| Voice | Attack centroid, velocity 0.35 → 1.0 | Take spread at attack | at 0.2 s |
|---|---|---|---|
| SAW | 765 → 1248 Hz | 4.6% | 3.0% |
| SQUARE | 728 → 1197 Hz | 5.6% | 2.9% |
| RUBBER | 382 → 620 Hz | 8.8% | 5.4% |
| WHISTLE | 622 → 1734 Hz | 5.1% | 2.9% |
| GRIT | 876 → 1102 Hz | 4.3% | 2.8% |
| DUB | 468 → 878 Hz | 5.0% | 2.2% |

Three first attempts failed their measurements and were changed before publishing:
- **DUB** put 99% of its energy above 2 kHz and its loop did not close (seam error 1.4). The
  band-pass in the echo loop amplified by 1/Q, about 4.5×, so the loop gain passed 1 and the
  echoes ran away into a whine. Normalised to unity gain at the centre: −30.8 dB above 2 kHz,
  seam 1.7e-14.
- **GRIT** first clipped before the filter. A clipped saw keeps a saw's harmonic shape, so it sat
  2.9 to 3.3 dB from SAW, and at its first BITE default (0.85) a soft and a hard hit measured
  1,010 and 1,029 Hz. A wavefolder with BITE 0.5 sits 7.5 dB from SAW and 5.4 from SQUARE, and
  responds to velocity (876 → 1,102 Hz).
- **WHISTLE** at PEAK 0.95 without the resonance boost sat 3.4 dB from SAW, closer than SAW and
  SQUARE. The resonance boost and the lower input level put it 6.1 dB from SAW.

Disclosed: RUBBER is by far the darkest voice (−40 dB above 2 kHz). GRIT's accents lift its
level by only 0.1 dB, because folding flattens the peaks; its accent is a change of tone.
Python harness, not Kotlin; nothing in round 2 has been heard.

## Round robin, rebuilt (2026-11-12)

**Heard:** after round 2 the owner said "Mostly good, but the 4 takes in RR sound the same."
That fails the round-robin gate ("Repeated copies of one strike are not round robin"). The
round-1 takes drew small independent random wobbles (filter opening ±1.5%, decay ±6%), so
any two could land close together and every difference was small.

Rebuilt in `take_params`: every take moves five things a listener hears, and with four takes
each dimension's four positions are dealt out by a different permutation (a Latin hypercube),
so no two takes share a position on any dimension. The settled pitch never moves.

| Dimension | Range per take |
|---|---|
| filter opening | ±9.5% of the whole opening (about ±0.8 octave fully open) |
| filter-envelope decay | ±48% |
| resonance feedback | ±18% |
| onset pitch dip | 0 to 40 cents above the note, settling in about 12 ms |
| amp attack | 1.2 to 5.5 ms |

Measured with a new take distance (`take_distance_db`): the RMS difference of two strikes'
level-matched third-octave spectrograms over their first 300 ms. The round-1 takes the owner
heard as the same measured 0.5 to 0.8 dB apart. Rebuilt, at velocity 0.6, the closest two takes
on each voice are 2.9 (SAW) to 3.6 dB (SQUARE) apart, and the widest pair stays under the gap
between velocity 0.6 and 0.8 on every voice (SAW 8.5 against 9.2 dB), so a take should not
read as a different hit. Across velocities 0.6 and 1.0 the closest pairs are 2.2 to 3.0 dB.

The sweep also showed what the measure sees: switching off the filter-opening dimension
alone cut SAW's widest pair from 10.1 to 7.5 dB; the pitch dip and attack speed barely move it.
Their audibility is the owner's call. A first setting (opening ±11%) put SAW's and SQUARE's
widest pairs above a velocity step (10.1 and 10.8 dB) and was narrowed. Narrowing the decay
range did not help: it widened the widest pair.

Only the 15 take clips changed; the other 73 clips are byte-identical. The audition adds an
A/B pair: one take played four times, then four takes. Not heard.

## Round 3: the tone (2026-11-12)

**Heard:** after round 2 the owner said "It feels a bit mid heavy. Like dollar store headphones."

Measured as octave bands relative to the 500 Hz band against reference B (the TD-3 clip), the
lows were fine (ETCH +12 dB at 63 Hz against +4.5), but the top was missing: line B sat 14 dB
short at 8 kHz and 23 dB short at 16 kHz. With nothing above, 250 Hz to 2 kHz reads as boxy.

What caused it, in order of effect:
- **The round-1 cutoff cap.** CUT_HI 1.2 kHz and FC_MAX 3.5 kHz were set in round 1 because a
  brightness measure stopping at 5 to 10 kHz fell at the top of the knob. Measured to 16 kHz,
  the wider range rises steadily (line B centroid 776 → 2,876 Hz across CUTOFF at SWEEP 0.2,
  1,375 → 3,069 Hz at 0.6): the old measure read a rising resonant peak as "darker". That
  round-1 decision was partly a measurement artifact, and it made the engine dull.
- **BITE's post-clip low-pass** (9 to 6 kHz) removed most of the sizzle the clip makes; on
  the loops nearly everything above 4 kHz comes from that clip.
- **The ladder's slope** (0.8 four-pole + 0.2 three-pole). Changing it alone moved line B's top
  only 1 to 2 dB; it matters most on single notes.

Round 3, all in `etch_proto.py`: CUT_HI 2 kHz, FC_MAX 8 kHz, ladder output 0.4 / 0.3 / 0.3 of
the fourth, third and second poles, BITE low-pass 14 to 9 kHz plus a +4 dB high shelf at 3 kHz
(the pedal's HIGH control), and the engine default CUTOFF 0.45 (was 0.3). `ROUND1B_TONE` keeps
the old values for the audition's before/after section.

| dB vs 500 Hz | 4 kHz | 8 kHz | 16 kHz |
|---|---|---|---|
| Reference B | -9.1 | -16.0 | -26.3 |
| Line B, round 1b | -14.2 | -30.4 | -49.7 |
| Line B, round 3 | -7.3 | -18.6 | -34.7 |
| Line A, round 1b | -26.0 | -42.2 | -67.2 |
| Line A, round 3 | -18.1 | -30.6 | -47.3 |
| SAW C2 note, round 1b | -51.1 | -81.2 | -102.4 |
| SAW C2 note, round 3 | -27.5 | -43.7 | -70.9 |

The "before" loops are byte-identical to the published round-1b clips, so the comparison is
fair. Every other clip on the page changes: the accepted round-1b sound is now the reference
for the before/after, not a byte baseline.

Knock-on effects, measured and handled:
- The brighter default left less room for an accent to open the filter, so the velocity
  0.6 → 0.8 gap shrank (SAW 9.2 → 4.8 dB). Velocity still changes the tone on every voice
  (SAW attack centroid 1,223 → 1,663 Hz soft to hard).
- Round robin's widest pairs then passed a velocity step on SAW and SQUARE, so the
  filter-opening range went from ±9.5% to ±7%. Closest pairs are now 1.8 to 3.1 dB (2 to 4
  times the 0.5 to 0.8 dB heard as the same); every widest pair is under a velocity step.
- SAW and SQUARE are 3.1 dB apart (3.7 in round 2). Disclosed for the owner's ears.

Not heard.

## Round 3 accepted (2026-11-12)

**Heard:** after the round-3 audition (version 5) the owner said "I'm good with evening in audition" (read as "everything"). That accepts, as
rendered in the Python harness at `c445980b`:
- the round-3 tone (CUT_HI 2 kHz, FC_MAX 8 kHz, ladder output 0.4/0.3/0.3, BITE low-pass 14
  to 9 kHz with a +4 dB shelf at 3 kHz, default CUTOFF 0.45);
- the rebuilt round robin (five dimensions dealt by a Latin hypercube, filter opening ±7%);
- the six voices: SAW and SQUARE, and the four new ones, RUBBER, WHISTLE, GRIT and DUB, at
  their defaults.

Preserve round 3 as the baseline. This is a listen to Python renders on the audition page,
not evidence of Kotlin, phone playback or an MPC. Questions the page asked that "everything"
answers by default rather than by name: whether RUBBER is too dark, whether GRIT's accents
need more level, how long DUB's wash runs, and whether SAW and SQUARE (3.1 dB apart after the
brighter tone) still read as different. Still open: whether chord tools apply to a mono
line voice.

## Published audition

Round 1 published 2026-11-11 as a private artifact:
<https://claude.ai/artifact/7TUKvRhXeFUz9fJTMsEKJn>, built from `768df8a5` (round 1); round 1b republished to the same link from `02b725f2`
(`gen_audition.py` then `build_page.py`). 54 clips and `manifest.json`. The reference clips
are not on the page. Owner listening is pending.

Round 2 published 2026-11-12 to the same link as version 3, built from `566bbf0b`: 88 clips
(the 58 round-1b clips unchanged, 30 new). Owner listening of the four new voices is pending.

The rebuilt round robin published 2026-11-12 to the same link as version 4, built from
`c4dddfca`: 90 clips (15 take clips replaced, two A/B clips added). Not heard.

Round 3 (the tone) published 2026-11-12 to the same link as version 5, built from `c445980b`:
96 clips, every clip re-rendered with the new tone plus a six-clip before/after section. Not heard.
