# FACSIMILE round 0: drum kits replicated from reference loops

Four kits synthesized from nine reference drum loops the owner supplied (2026-11-18). The owner
asked for the references to be **replicated, not sampled**: no reference audio is in any kit.
This round is a Python listening round, as the house does before any Kotlin. Nothing here is on
the shelf, and nothing has been heard yet.

## The idea in one paragraph

A loop is several drums overlapping. Stage 1 pulls the loop apart into drums and a timeline with
NMFD. Stage 2 merges the parts of one drum, lists every hit (time to the millisecond, loudness),
and lines the hits up. Stage 3 fits a small synth to each drum: falling-pitch partials (a kick's
or an 808's body, a zap's sweep), ringing partials (a snare's shell, a tom's ring) and 26 bands of
shaped noise, then corrects it in a closed loop until its band envelopes match the reference. The
fit writes **numbers only** (`models/*.json`); the synth (`synth.py`) renders from those numbers
and never reads audio. The grooves re-perform each reference pattern with the replica drums.

## Files

| File | Reads | Writes | What it does |
|---|---|---|---|
| `decompose.py` | reference WAVs | work folder | NMFD with two constraints: a drum fires only at a detected onset, and a template only decays after 40 ms (else NMFD learns patterns, not drums). Circular, because the loop repeats. |
| `drums.py` | work folder | `inventory.json`, aligned hits | Merges parts that co-fire, refines each hit's time on the separated audio, aligns the strong hits. |
| `glidefit.py` | (a hit) | (numbers) | Fits a falling pitch by analysis by synthesis: demodulate the hit by a candidate glide and keep the one that leaves the most energy standing still. Two shapes: falling in Hz (kick body) and falling in octaves (zap). |
| `fit.py` | work folder | `models/<loop>.json`, `fit-report.json` | The band target, the partials, the closed loop (best pass kept). |
| `synth.py` | a model | audio | The instrument. Velocity and round robin live here. |
| `groove.py` | `models/`, `kits.json` | groove WAVs | Re-performs each loop: measured timing and loudness, four takes dealt in turn, 808 choke. |
| `measure.py` | references, work folder, models, grooves | `measure-report.json` | Groove and drum distances, velocity character change, take distinctness. |
| `build_audition.py` | all of the above | audition folder | The listening page's audio and `data.json`. |
| `kits.json` | | | The four kits, hand-named, and each loop's choke group. |

## Run it

The reference loops are licensed audio and are **not** in the repository. Put them in a folder of
their own, keep the work folder outside the repository, and run with `python3 -I` (the scripts
add their own folder to the import path):

```sh
python3 -I decompose.py REFS WORK        # about 3 minutes
python3 -I drums.py REFS WORK
python3 -I fit.py WORK models            # about 11 minutes; add a loop's tempo to fit one loop
python3 -I groove.py models GROOVES
python3 -I fit_glue.py REFS GROOVES && python3 -I groove.py models GROOVES   # glue, then re-render
python3 -I measure.py REFS WORK models GROOVES
python3 -I build_audition.py REFS WORK models AUDITION
python3 -I test_glidefit.py && python3 -I test_steady.py   # known-answer checks
```

Requires NumPy, SciPy, soundfile and matplotlib; `build_audition.py` also calls ffmpeg.

## What is committed and what is not

Committed: the code, `kits.json`, and `models/`: per drum, glide pitches and times, partial
frequencies, envelope breakpoints in dB, and the reference's hit times and loudness (the groove's
notation). Not committed: the reference loops, the separated audio, the aligned hits and the
templates (all derived from reference PCM), and every rendered WAV or MP3.

## Round 0 results (Python harness, 2026-11-18)

Listening page: [FACSIMILE Round 0](https://claude.ai/artifact/LvtMyNDXUr9jjvVvw8uPMa) (private to
the owner). Numbers from `models/fit-report.json` and `models/measure-report.json`.

| Measure | Result |
|---|---|
| Fit, averaged over three noise seeds | median 1.84 dB, 90% under 5.79, worst 6.26 (57 drums) |
| One take at 4x against its target, mean of four takes | median 4.24 dB, worst 9.09 |
| The reference's own single hits against the same target | median 9.28 dB |
| Velocity: spectral-shape change, v=.4 against v=1 at matched loudness | 1.34 dB at least, median 3.08 |
| Round robin: closest pair of four takes | 7.9 dB at most (a copy measures over 40) |
| Grooves: band distance against the reference | 6.7 to 11.8 dB; 8 of 9 under the reference shifted one 16th (8.98 to 23.4), loop 163 level with it (9.45 against 8.98) |
| Grooves: level against the reference | 106, 113, 154, 90 within 0.9 dB; 127 at -2.8; 102, 103, 134, 163 at -4.6 to -5.3 |
| Pads: replica level against its reference strike (full-strength pads) | median -0.2 dB; tom-beat hat -10.8, tom-beat kick +8.5 |

Part of the remaining groove shortfall is probably bleed: each separated reference lane carries
some of the other drums, and a replica lane carries none. Not yet shown.

## Ledger (round 0, 2026-11-18)

- **Verified:** the render path reads only `models/*.json` (`synth.py`, `groove.py` import no
  audio reader). The glide fitter recovers five known synthetic glides, including a 3 kHz
  sweep and a log-frequency zap, within 5% in start pitch, end pitch and speed, and within 4%
  in level, and picks the right shape each time (`python3 -I test_glidefit.py`).
- **Measured (Python harness, not Kotlin):** see `models/fit-report.json` and
  `models/measure-report.json`. The listening page shows each number beside its pad.
- **Proposed:** the pad roles and names in `kits.json`, read from templates and patterns, not
  heard. Velocity is a house law this round (softer strikes tilt about 2.5 dB per octave darker
  at full softness, decay up to 15% faster, and fall 30% less far in pitch); the references'
  own soft hits have not been fitted yet.
- **Heard:** nothing. Only the owner hears.

## Known limits, disclosed

- The band distance cannot tell a tone from noise with the same energy: a noise-only fit of the
  zap scored better than the tonal sweep that is actually there. The ear decides that.
- The replicas are mono, as the house renders. The references' stereo width is not replicated.
- 808 tails: the references choke each 808 note with the next, so the full tail was never heard.
  The fit continues the decay it measured before the cut. Whether a groove chokes its 808s was
  decided by measurement, not assumed: only loop 154's 808 measured closer choked; the 808s of
  loops 127, 134 and 163 measured 1.9 to 4.4 dB closer ringing over each other.
  The rule only fires for a low-heavy drum (over half its energy under 150 Hz) that was falling
  slower than 150 dB/s before a 25 dB drop inside 35 ms. Applied to every drum, it gave hats
  1.4 s washes and made two grooves 8 to 20 dB too loud; that bug was caught by the groove
  level check and fixed before anything was published.
- Tails under the template's floor: once a drum is 40 dB under its peak it must keep falling at
  60 dB/s or faster. NMFD leaves a flat plateau 45 to 60 dB down where no hit shows a late tail.
  For a drum that is not low-heavy, the floor can sit only 30 to 50 dB down, so a second rule
  applies: once it has fallen 20 dB, if it then falls slower than 8 dB/s over 100 ms, it keeps
  falling at 60 dB/s from there. Low-heavy drums (808s, boom toms) are left to ring as measured,
  which is why a few of them are 1.4 s long.
- Some NMFD components are artifacts (a kick's sub re-fired on its own). The grooves use every
  component, so nothing in the reference goes missing; the pads use only the drums named in
  `kits.json`.
- Two fit bugs were caught by the groove level check (grooves 113 and 134 measured 8 to 20 dB
  louder than their references). First, a grid point at the render's very end can never be
  measured, so the closed loop raised it every pass and the drum swelled into its last sample;
  such points are now frozen at silence and no band may be set more than 6 dB over the drum's
  loudest target. Second, the loop fitted one noise recording's random wobble (narrow-band
  noise wanders 4 to 6 dB at any instant), which left other takes and the 4x render 2 dB
  further off than the fit reported; the fit now averages its replica over three noise seeds,
  and `measure.py` reports the mean distance over the four takes.
- Grooves still came out 2 to 8 dB quiet, worst on drums made of soft hits, for three
  reasons, all fixed. The noise band filters started from silence, so a narrow low band took
  50 to 100 ms to ring up and was nearly silent during the strike itself (the loop had been
  inflating those bands to compensate); they now run over a discarded lead-in. The velocity
  tilt made soft hits darker and also quieter, counting their measured softness twice; a
  strike's energy is now restored after the tilt. And the target was a median across hits,
  about 1.6 dB under the mean the replica is measured as; it is now a median of three group
  means.
- Round 0b (2026-11-18), heard by the owner as "warping and warbling ... generally the higher
  pitches like long shaker. Almost like a bit of ring modulation". Two causes, both fixed.
  The ringing-tone detector turned random peaks of the noise into steady sine partials: where a
  loop repeats one sample, its chance peaks are identical in every hit, so the median across
  hits kept them, and the long shaker carried a 7.7 kHz sine louder than its own noise (the
  mover hat carried eight). Above 1 kHz a tone must now ring, staying proud in two of three
  slices of the hit, and stand 18 dB proud rather than 12; each tone is sized from its measured
  share of its band, not from its prominence (`test_steady.py`). And the noise loudness
  zigzagged 15 to 30 dB between grid points a few milliseconds apart, a fast amplitude
  modulation that adds buzzy sidebands; every band now rises to its peak within 14 ms and then
  only falls. `measure.py` reports each drum's ring against its reference: after the refit no
  drum rings more than 6 dB over its reference (worst, the mover hat, +3.9 dB; it was +18).
  The price, measured: the fit median rose from 1.84 to 2.52 dB (a strike that may only rise
  then fall cannot chase every flicker of the one reference sample), and the grooves sit 1.5
  to 5.8 dB under their references, where four were within 1 dB.
- Round 0c (2026-11-18): the owner suggested compression to glue the grooves. The raw sum of
  replica drums measured 2 to 7 dB peakier (crest factor) than its reference in 8 of 9 loops,
  so each groove now prints through a bus compressor (`groove.glue`) fitted by `fit_glue.py`
  to its reference's crest factor, with a makeup gain to its level. A classic slow glue attack
  (10 ms) made the grooves PEAKIER (90: 23.6 to 30.3 dB), because it passes each hit's front
  edge and turns down the body; only fast attacks tightened them, so attack is fitted too
  (0.3 to 2 ms chosen). Every compressed groove measured closer to its reference (band
  distance 0.9 to 3.7 dB lower) and matches its level. Loop 163 was already as tight and is
  not compressed (still 5.8 dB quiet). Loop 127 needed a 0.3 ms attack 28 dB under its peak
  and reaches 15.7 dB against the reference's 13.7: the original was likely clipped. The
  single pads are not compressed.
- Round 0d (2026-11-19): the owner asked whether the grooves had echo. No echo is applied,
  but the grooves had more hits than their references (loop 163: 120 onsets against 84). NMFD
  explains a drum's tail by firing its template again 40 to 90 ms after the hit, and each
  firing played back as a fresh strike: slapback. `drums.py` now keeps a firing within 250 ms
  of the drum's last hit only if the drum's separated audio jumps 6 dB at it. Kept events rise
  8 to 53 dB (median per drum), dropped ones 1.4 to 4.4 dB, so 16th-note hats and flams stay;
  599 of 2230 events were re-fires. That was not the whole echo: dropping even 190 more events
  left loop 163 with about 60 extra onsets, because the extras were inside the drum sounds.
  20 of 57 replicas rang 1.5 to 5 times longer than their own NMFD template (a snare 459 ms to
  -30 dB against 157), a sustained hiss that a groove plays as a wash behind every hit. The
  hits the fit learns from are cut from a dense loop and carry the other drums' tails and the
  mix's room. `fit.template_bound` now lets no band fall slower than the template after 25 ms
  (3 dB of slack), nor stand more than 10 dB over the template at the attack (which also
  removed another drum's click from an 808). An 808 whose tail the reference cut keeps its
  natural ring as a pad and is choked at its own next hit in the groove (`self_choke`).
- Round 0e (2026-11-19): the owner heard the knocker groove doubling. One kick had been split
  into four NMFD parts, each timed on its own separated audio, and a low part builds slowly,
  so its start read 10 to 20 ms late: the parts fired 15 to 19 ms apart and one kick played
  as two or three attacks, a flam. `drums.align_hits` now times a split hit from the loop
  itself (onsets above 2 kHz, where a new hit cracks and a kick's own body bloom does not):
  one clear attack and every part snaps to it; several (a real flam, a hat off the kick) and
  each part takes its own, a low part the earliest; no clear attack (a soft hit under other
  sounds) and the parts become one hit at the earliest part's time. The median offset between
  co-firing parts is now 0.0 ms in every loop checked (it was 15 to 19 ms in the knocker
  groove). Only hit times changed: `python3 -I fit.py WORK models --events-only` copies them
  into the models without refitting a sound.
- Round 0f (2026-11-19), kick first, the knocker groove only so far. The owner heard the kick
  as fragmented and asked why it had been split. NMFD learns a few fixed shapes per loop, each
  one pitch and one decay; a kick that varies from hit to hit is spread over several, each hit
  a different blend, so the parts co-fired on only 22 to 88% of each other's hits and the 70%
  merge rule kept them apart. Each was fitted as its own drum and the four were stacked: four
  pitch drops and attacks at once. `drums.kick_first` (loops in `KICK_FIRST`) sums every low
  part's audio into one low track, merges the parts' hits, fits each hit's pitch glide, and
  regroups: the loud hits define the kick (they all swept from about 400 Hz to 64 to 72 Hz in
  about 7 ms), a quieter hit with the same shape is that kick played softer, and the remaining
  low hits group by where they settle (a round low knock following the kick, the sub drop). A
  first version grouped by settled pitch alone and split the one kick into four again, since
  the next hit or another drum's low end lands within 70 to 170 ms. Measured: the knocker
  groove's band distance 7.10 to 7.03 dB, its tonal balance 2.97 to 2.00 dB; the kick is one
  fall from 165 to 64 Hz in 8 ms. KNOCK A01 to A04 are now the kick, the low knock, the kick
  struck softly, and the sub drop.
- Round 0g (2026-11-19), only the hits the loop shows, the knocker groove only so far. The owner
  heard 0f as still fragmented, the kick itself fine. Measured against the reference, three
  causes. First, pieces of tails were played as hits: the knocker kick's tail dips about 70 ms
  after the click and swells back 10 dB, the same on every hit, and that swell had become a
  "low knock" and a "sub drop" striking 110 to 170 ms after each kick, the kick cut short at
  them; snare and hat parts fired where the loop rises 0 to 5 dB in every band; a snare layer
  sat 57 to 85 ms off three backbeats. `drums.confirm_attacks` and kick_first now keep a hit
  only where the loop itself jumps (15 dB under 150 Hz over 30 ms for a low drum, 7 dB in the
  mids or highs over 12 ms for the rest; real hits jump 15 to 65 dB); a strong firing without
  one moves to the nearest real attack within 100 ms, and one drum's firings within 50 ms are
  one hit: 11 dropped, 1 moved, and the kick is one drum of 9 hits cut only at the next kick.
  Second, that tail pulse was rendered as narrow noise bands, so each take swelled at its own
  random moments, extra soft hits inside the kick. `fit.tail_modes` measures it instead as
  damped tones (matrix pencil on the clean takes, 60 to 450 ms under 200 Hz: eight tones,
  67 to 82 Hz with about the same decay and a few at 93 to 113 Hz, one boom whose level
  pulses), rendered with the reference's own phases on every take (synth `mode` partials) and
  faded where the reference kick ends (440 ms); the low glide hands over at 40 to 75 ms. The
  kick's low-band error from 80 ms fell from about 6 to 3 dB per take. Third, the knocker
  snare has a room layer (113-d2) that starts 30 dB under its body and builds for 75 ms; the
  target and noise shape held every band to its first 14 ms, so it played 30 dB too quiet and
  every snare was dry. In loops listed in `fit.SWELL_LOOPS` a band that later stands 6 dB over
  its first 14 ms (robust mean) peaks where it really does. Measured: groove band distance 7.03
  to 6.26 dB (raw 10.92 to 8.49), balance 2.00 to 2.04; the highs still sit 5 to 8 dB under
  the reference. Other loops refit identically. 24 of 50 drums in the other loops have a band
  or more that builds (mostly kick low bands peaking at 20 to 40 ms; 163-d2 by 6 dB in 13
  bands), so the swell rule is a candidate for them too. KNOCK A02 and A04 are now the snare's
  room layer and body on their own.
- Round robin follows the reference's own hit-to-hit spread, clamped to 0.3 to 1.2 dB per band,
  plus fresh noise and phases. Whether that reads as one drum hit again is for the ear.
- Engine gates: this is a prototype. Velocity and round robin are checked in rendering only;
  saving, playback and export are not built (see `docs/ENGINE_ACCEPTANCE_GATES.md`).
