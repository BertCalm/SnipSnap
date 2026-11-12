# MIRRORBALL disco kit: design

Status: round 0 (Python harness, not Kotlin). Nothing built in the app yet. Nothing heard yet.
Repository truth: `1d8f24ec` (main after #1037). Read: `FxChain.SECTIONS` (the rack runs
compressor → squash → forge → valve → … → tape → … → echo, so the amp always comes before the
tape and the echo), `Compressor.kt`, `Squash.kt`, `Echo.kt`, `Tape.kt`, `Valve.kt`,
`BreakerChain.kt`, `Gatecrash.kt`, `GatecrashKits.kt`, `Skin.kt`, `docs/BREAKER.md`,
`docs/GATECRASH.md`, `docs/ENGINE_ACCEPTANCE_GATES.md`.

The characteristic gesture is **dead, clicky strike → FET squeeze → tape repeats in rhythm →
the amp pulls the repeats up behind the hit**.

## What it is

A 1970s disco drum kit, printed through the owner's chain in the owner's order: an FET
compressor (the 1176 pedal), then a multi-head tape echo (the El Capistan), then an amp. The owner
attached a research note on disco drums (2026-11-12). Its essentials:
- the kick is low, heavily damped, compressed, with a beater click around 2 to 3 kHz;
- the snare is medium-high, towelled, short, crisp;
- the hi-hat drives the groove: eighths and sixteenths, open hats on the off-beat;
- the room is dry; space comes from a short plate or a subtle tape slap, not a wash.

## The references, distilled (research names only, never product names in the app)

- **UAFX 1176 Studio Compressor** (pedal):
  - **Controls (manual and product page):** Input, Output, Attack, Release, Ratio (Off up to
    "All", all buttons in), a Single/Dual/Sustain mode switch, and a parallel mode.
  - **The manual:** 1176 attack times are much faster than most compressors. Attack and Release
    fully clockwise add harmonics and distortion.
  - **Time ranges:** the pedal's pages give none. The model uses the original hardware's published
    20 µs to 800 µs attack and 50 ms to 1.1 s release, and says so.
  - **Essence:** a fixed threshold you drive into. Its attack is fast enough to shave a beater, and
    at fast release the gain follows the waveform, which is distortion. "All" pins the ratio and
    makes the knee and attack misbehave.
- **Strymon El Capistan dTape Echo** (user manual):
  - **Fixed head:** delays of 50 to 250 ms, 150 to 750 ms or 300 ms to 1.5 s. The tap subdivisions
    are 1/16, dotted 1/8 and 1/4.
  - **Multi head:** two of three heads play at once (1 & 2, 2 & 3, 1 & 3), 240 ms to 1.2 s.
  - **The tape:** Tape Age narrows bandwidth, Wow & Flutter is "mechanically related tape speed
    fluctuations", and Repeats runs "from one to saturated oscillation".
  - **Secondary controls:** spring reverb, low-end contour, tape bias (under- to over-biased:
    headroom against saturation) and tape crinkle.
  - **Essence:** fixed head spacings make the repeats a rhythm, not a number. Each pass saturates,
    darkens and thins.
- **The amp:** the brief names none. The assumption is a small clean tube combo with an open-back
  speaker, the usual pedal-board destination. Its essence is asymmetric preamp gain, a mid-scooped
  tone stack, supply sag and a speaker that rolls off the top.
- **Disco drums:** see the owner's note above. **Essence:** dry, controlled, transient-first, with
  the hat as the lead voice.

## Why it earns a pad (sibling check)

The nearest siblings are BREAKER and GATECRASH.
- **BREAKER** is the closest. It is an engine: drums → saturation → FET compressor → spring tank →
  slap → compressor → VALVE. It owns an order the rack cannot make.
- **GATECRASH** is a kit family. It reuses SKIN, THUMP and ONSET hits through one new treatment
  stage, and prints an authored 16-pad kit with zones and takes.

What neither can do:
- **Tape repeats at musical head spacings,** wobbling and darkening per pass. BREAKER's slap is a
  plain delay line, and the rack's ECHO has one head.
- **An echo printed into an amp.** VALVE comes before TAPE and ECHO in the rack; BREAKER's amp is
  after a spring.
- **A disco source set.** The dead kick and towelled snare are SKIN settings that no kit has yet.

The gesture is a kit's, not a new instrument's. So the lightest landing that keeps it is a
GATECRASH-style kit (one treatment stage, authored pads) rather than an engine.

## Signal flow

```
SKIN / THUMP hit (velocity, take)
   │ mono 44.1 kHz
   ▼
[PRESS: FET compressor, at 4x]
   INPUT into a fixed threshold · attack 20 µs to 800 µs · release 50 ms to 1.1 s,
   two-stage (program dependent) · ratio 4 / 8 / 12 / 20 / ALL ·
   gain-cell bend grows with gain reduction · output-transformer soft round
   │
   ▼
[REEL: three heads on one moving tape, at 1 / 3 / 4 sixteenths (120 BPM assumed)]
   one or two heads read · wow 0.7 Hz + flutter 6.5 Hz + seeded drift move every head ·
   loop: tape age low-pass → low-end contour high-pass → repeats (loop gain ≤ 0.92) →
   record head tanh (bias) → tape
   dry + send × wet
   │
   ▼
[COMBO: clean tube amp, at 4x]
   asymmetric preamp (VALVE's curve) → mid scoop + bright shelf → sag (power envelope,
   4 ms down / 120 ms back) → power soft clip → open-back speaker (HP 75 Hz, +3 dB at
   110 Hz, LP 5.2 kHz; 8.5 kHz on the bright candidate)
   │
   ▼
[Output: DC block → cap 1.49 s with a 150 ms raised-cosine fade → level → 4 ms fade]
```

In Kotlin the stage would reuse `Valve`'s curve and `Valve.upsample` → `Tide.bandLimit` →
`Dsp.decimate`, and `Dsp.Noise`/`Dsp.seedFor` for the tape drift. It would be a treatment object
beside `Gatecrash`, called from a `MirrorballKits` builder. It would not be an `FxChain`
section, because the order is the point.

## Per-pad settings (R0 shapes)

| Pad | Source | PRESS | REEL send |
|---|---|---|---|
| Kick | SKIN KICK: TUNE .22, DECAY .30, TONE .40, PUNCH .70, CLICK .72, DROP .08 | +14 dB in, attack .45, release .80, 8:1 | none: four on the floor stays four |
| Snare | SKIN SNARE: TUNE .58, SNAP .72, DECAY .26, PUNCH .62, RATTLE .60, SIZZLE .55 | +16 dB, .70, .75, 4:1 | head 1, send .16, repeats .15 |
| Closed hat | SKIN HAT_CLOSED: TUNE .55, DECAY .22, TONE .45 | +10 dB, .60, .60, 4:1 | heads 1+2 (the gallop), .30, .35 |
| Open hat | SKIN HAT_OPEN: TUNE .55, DECAY .42, TONE .45 | +10 dB, .60, .55, 4:1 | heads 1+2, .24, .30 |
| Shaker | SKIN SHAKER: TONE .6, DECAY .35, PUNCH .6 | +12 dB, .70, .60, 4:1 | heads 1+2, .28, .35 |
| Tom | SKIN TOM: TUNE .45, DECAY .45, DROP .25, CLICK .4 | +12 dB, .55, .60, 8:1 | head 2, .22, .25 |
| Synth tom | THUMP TOM: TUNE .55, SWEEP .85, DECAY .55, PUNCH .55 | +12 dB, .55, .55, 4:1 | heads 2+3, .30, .45 |

Tape age .45 and wow .5 on every pad. The amp candidates are clean (preamp gain 1.6, sag .35),
bright (clean with the speaker opened to 8.5 kHz) and pushed (gain 5, sag .6).

## R0 (2026-11-12, Python harness, not Kotlin)

Sources: SKIN and THUMP rendered by the app at `1d8f24ec` (velocity 1 and 0.45, takes 0 and 1),
through `scripts/mirrorball_r0.py`. Page: https://claude.ai/artifact/EJ9vFTy9SBbj2cbQJ125oH

| Measured | Result |
|---|---|
| PRESS deepest gain reduction | kick 17.3 dB, snare 14.9, tom 15.2, synth tom 13.3, hats 10.5, shaker 11.3 |
| Crest, first 100 ms (dry → pressed → full) | snare 19.0 → 16.4 → 14.7 dB (denser); kick 11.2 → 13.0 → 14.7 (the beater click stands further out of a squeezed body) |
| FET distortion from the fast detector alone | kick energy above 150 Hz, 60 to 250 ms: −67.9 dB dry, −67.9 at the slowest knobs, −63.3 at the fastest (+4.6 dB) |
| REEL stability at repeats 1, two heads, impulse | 375 ms windows fall every window, −55.5 to −106.3 dB over 3.4 s; no limiter |
| **The order** (first repeat against the hit, owner order vs rack order) | closed hat −14.2 vs −20.6 dB; shaker −14.3 vs −21.3; tom −13.6 vs −21.8, centroid 188 vs 241 Hz; synth tom −10.7 vs −18.4. With the amp last, the repeats come back 6 to 8 dB louder and darker. The sag squeezes hit and repeats together |
| The speaker against disco brightness | snare centroid (first 150 ms) 11.4 kHz dry → 4.6 kHz full; closed hat 13.0 → 5.3 kHz. The bright speaker is on the page as the answer to try |
| Amp sag | 3.3 to 4.8 dB of gain taken at the hit (clean candidate) |
| Length | kick 0.32 s, snare 0.45 s; echoing pads 1.28 to 1.49 s (capped, so they file as hits, not LOOP) |
| Render time | Python only, 0.24 to 0.49 s per pad; no Kotlin number yet |

## Risks

1. **The amp's speaker darkens a disco snare and hats.** Measured above. GATECRASH round 3 was
   heard as "missing some brightness" for the same reason. The bright-speaker candidate is on the
   page.
2. **Echo against the groove.** Repeats at sixteenths and dotted eighths land on the grid. On the
   kick they would turn four into sixteen, so the kick gets no send.
3. **Tempo.** `render` has no tempo, so 120 BPM is assumed. The R1 kit will state its tempo, as
   the GATECRASH kits do (96, 118, 108).
4. **Per pad is not a bus.** A real 1176 on the drum bus makes the kick duck the hats. Per-pad
   prints cannot, so the page plays both. If the bus wins, it lands as one or two loop pads (a
   phrase, as GATECRASH's fill and build), and the hits stay per pad.
5. **"All" mode is a law, not a circuit:** ratio 20, a 12 dB knee, a 3 dB attack overshoot and
   double gain-cell bend. Judge it by ear or drop it.
6. **Trademarks.** Add `1176`, `uafx`, `universal audio`, `strymon` and `capistan` to
   `PresetTestSupport.trademarkBlocklist` when the kit's pad names land. The word "MIRRORBALL" is
   generic.

## Render budget

GATECRASH's hits take 22 to 105 ms in Kotlin, and BREAKER's chain (two 4x stages) is under
100 ms. MIRRORBALL also has two 4x stages (PRESS and COMBO) and a 1.49 s tape loop at 1x.
Proposed budget: under 100 ms per hit warm. A 16-pad kit with three zones × four takes is
192 renders, about 10 to 20 s per kit at build time, as GATECRASH. There is no solver, so nothing
needs a cache.

## Completion gates (docs/ENGINE_ACCEPTANCE_GATES.md)

| Gate | Applies | Plan |
|---|---|---|
| Melodic, keygroups, chord tools | no | drum kit |
| Articulation | finite hits, no HOLD | an authored fill and build per kit, as GATECRASH's A15/A16. The disco build: an accelerating open-hat or synth-tom riser into the tape |
| Drums: multiple distinct genre kits | yes, if it becomes a family | candidates after the listen: Philly soul-disco (live kit, strings-era dry), New York loft (more percussion, longer echo), Euro machine-disco (THUMP/ONSET sources, sequenced hats). Distinct sources, tunings and pad roles, not one kit at three echo levels |
| Velocity and round robin through render, save, playback, export | yes | the GATECRASH path: three zones × four takes, SKIN re-strikes, `PadHit.resolve`, MPC 3 read-back. MPC 2 keeps only first takes (shared limit, disclosed) |

R0 sits outside all of these. It is a sound study.

## Ledger

| Claim | Status | Evidence |
|---|---|---|
| The rack runs VALVE before TAPE and ECHO | verified | `FxChain.SECTIONS`, `1d8f24ec` |
| The 1176 pedal's controls and "fully clockwise adds harmonics" | verified (maker's manual and product page) | UA help-centre manual; uaudio.com |
| The El Capistan's head modes, delay ranges and tap subdivisions | verified (user manual) | strymon.net user manual |
| 20 µs to 800 µs attack, 50 ms to 1.1 s release | sourced from the original hardware's published specs, not the pedal's pages | the pedal does not publish them |
| Head spacing 1 : 3 : 4 sixteenths | inferred from the manual's tap subdivisions | the manual gives no head distances |
| Numbers in the R0 table | measured | `scripts/mirrorball_r0.py`, Python harness |
| Every per-pad setting | proposed (shape) | none |
| Anything sounds disco | not heard | the R0 page |

## Sources

- UAFX 1176 Studio Compressor manual: https://help.uaudio.com/hc/en-us/articles/17410682214420-UAFX-1176-Studio-Compressor-Manual
- 1176 Studio Compressor pedal page: https://www.uaudio.com/guitar-pedals/1176-studio-compressor-pedal.html
- El Capistan user manual: https://www.strymon.net/manuals/ElCapistan_UserManual.pdf
- El Capistan quick start: https://www.strymon.net/manuals/El_Capistan_QuickStart_Web_REVE.pdf
- Compressor gain computer: Giannoulis, Massberg and Reiss, "Digital Dynamic Range Compressor
  Design: A Tutorial and Analysis", JAES 60(6), 2012 (already cited in `Compressor.kt`)

## Why it is built this way

1. **The order is the instrument.** The amp is last, so the echoes go through the tubes too. The
   amp's sag squeezes the hit, and the repeats behind it come up 6 to 8 dB. The rack cannot do
   this, because its amp runs before its tape and echo.
2. **The compressor goes first, so the tape gets an even level.** Every hit reaches the tape at
   nearly the same strength, so the repeats are as steady as the playing. That is the disco hi-hat.
3. **The heads set the rhythm.** A sixteenth plus a dotted eighth on a hi-hat is a gallop. The
   tempo is fixed per kit, so the repeats land on the grid.
4. **Kick dry, hats wet.** Disco was a dry sound. The echo is a send that each pad gets in its
   own amount, and the kick gets none.
5. **The risky decision is the speaker.** A real amp's speaker is dark, and a disco snare is
   bright. The page lets the owner choose, rather than guessing.

## Round 0, heard (2026-11-12)

The owner's words: "Sounds kind of basic and not very punchy or character rich."

Measured afterwards on the round-0 renders, 0 to 300 ms:
- **The kick was nearly a pure sub sine.** Its fundamental was under 60 Hz and everything above 250 Hz was 56 to
  80 dB down. The beater band (1.5 to 4 kHz) was about 80 dB down. The research note's beater click was simply
  absent, and no EQ can boost what is not there.
- **The tom was a pure sine** at 100 to 250 Hz, with nothing above 600 Hz.
- **The speaker darkened the snare and hats.** It took 10 to 12 dB off their 8 to 16 kHz.
- **The chain added almost no harmonics.** The FET bend and the clean amp were too gentle to be heard as
  character.

Round 1's Kotlin build (commit `decead4f`) carries round 0's settings, so it shares these findings. It waits on
round 0b's pick before its sound is changed. One of its fifteen tests failed at that commit: the Loft kick's
soft takes are 0.004 apart, under the 0.005 floor.

## Round 0b (2026-11-12, Python harness, not Kotlin)

Script: `scripts/mirrorball_r0b.py`. Page: https://claude.ai/artifact/RC5kSEXbMUNHUmNvnAhLnN

What changed, in the order a record was made:
1. **Retuned drums (SKIN):**
   - kick: TUNE .42, DECAY .30, TONE .38, PUNCH .78, CLICK .85, DROP .22;
   - snare: TUNE .52, SNAP .80, DECAY .32, PUNCH .80;
   - tom: CLICK .70, TONE .60.
2. **A felt-beater layer on the kick:** a 4 ms click of noise, band-limited to 1.8 to 5.5 kHz and seeded per
   take. A softer hit gets a quieter, duller click.
3. **PRINT, the drum as recorded:**
   - console EQ: kick +6 dB at 68 Hz, −6 at 380, +11 at 3 kHz; snare +4 at 210, −3 at 700, a +6 shelf at
     5.5 kHz; tom +7 at 3.5 kHz; hats high-passed at 350 Hz and −2.5 at 11 kHz;
   - then tape, asymmetric saturation at 4x.
4. **PRESS in the pedal's parallel mode:** a crushed copy (all buttons, fastest knobs) under the dry hit. The
   kick's compressor attack is at its slowest, 800 µs, so the beater passes.
5. **COMBO driven harder:** preamp gain 2.6 (Punch) or 5 (Hot), against round 0's 1.6. The speaker is bright
   (8.5 kHz) except on the kick and tom (6.5 kHz).

| Measured (first 300 ms unless stated) | Round 0 | Punch | Hot |
|---|---|---|---|
| Kick body, 20 to 80 ms against peak | −15.8 dB | −11.4 | −11.0 |
| Kick 250 to 600 Hz (harmonics) | −55.9 dB | −32.0 | −23.3 |
| Kick beater, 1.5 to 4 kHz, first 15 ms | −23.7 dB (dry source −28.0) | −11.2 | −11.0 |
| Tom body | −10.4 dB | −6.9 | −6.4 |
| Snare 8 to 16 kHz | −24.5 dB | −5.5 | −5.2 |
| Closed hat 8 to 16 kHz | −14.6 dB | −7.7 | −7.9 |

Clap (THUMP), tambourine (BACKWASH's CC0 recording) and conga (TERRA) are new to the study, so they have
no round-0 column.

## Round 1b, heard (2026-11-12)

The owner's words: "It sound a bit mid heavy. Like listening through dollar store headphones."

Measured afterwards: octave-band shares of the whole, in dB, on each kit's two-bar groove.
- **Against the same groove dry:** round 1b lost 17 to 19 dB in the lowest octave (31 Hz) and 12 to 14 dB in the
  top octave (16 kHz) on Philly and Loft. The closed hat and the tambourine each lost 17 dB at 16 kHz.
- **The cause:** COMBO's guitar-speaker model, which every pad went through. It cut under 75 Hz and rolled off at
  6.5 or 8.5 kHz.
- **Against GATECRASH's approved grooves:** 15 to 30 dB short at 31 Hz, 7 to 8 dB short at 16 kHz, and Philly 11
  to 14 dB hotter at 4 to 8 kHz.

## Round 1c (2026-11-12, the app's renders)

Page: https://claude.ai/artifact/75bDrRceDQPXXydrFewRmM. COMBO gains a speaker low cut, a bump level and a
parallel blend. There are four speaker settings (`MirrorballKits.Speaker`):
- **Closed:** round 1b.
- **Open:** 40 Hz up to 13 to 15 kHz, the bump halved.
- **Parallel:** the closed speaker, with the direct signal blended under the amp at equal rms.
- **Open + parallel:** both.

Philly's groove:

| Octave | Dry | Closed (1b) | Open | Parallel | Open + parallel |
|---|---|---|---|---|---|
| 31 Hz | −15.5 | −34.4 | −27.0 | −27.5 | −25.0 |
| 63 Hz | −6.8 | −8.8 | −6.5 | −9.1 | −5.4 |
| 4 kHz | −8.5 | −5.6 | −8.3 | −13.4 | −12.6 |
| 16 kHz | −7.6 | −21.9 | −9.8 | −11.1 | −10.4 |

The kits still print Closed until the owner picks. The 31 Hz octave stays about 10 dB under dry in every
candidate. Part of that is the kick's own +5 dB at 68 Hz, which lowers the octave below's share. Nothing in
round 1c has been heard.
