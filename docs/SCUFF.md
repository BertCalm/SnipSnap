# SCUFF: design brief and round-0 audition

Status: **round 0b, Python prototype; sound direction accepted by the owner 2026-11-12.** No Kotlin exists. The engine stays in
progress until every applicable owner gate has evidence (see "Gates" below).

Prototype: [`prototype/scuff/`](../prototype/scuff/) (`scuff_proto.py` is the model,
`gen_audition.py` renders and measures, `build_page.py` writes the audition page). Every
number below is from that Python harness, not from Kotlin.

Repository truth: read at `4f47fdb4` (2026-11-12). APIs named here were grepped: `Thump.kt`
(`kick`, `clap`, `rim`, `hat`, `ThumpVoice`), `Tines.kt` (`TinesVoice.BELL`, `ZAP`), `Crunch.kt`
(BITS, RATE, TONE, GRIT), `Dive.kt` (`MAX_SEMITONES`, `FAST_SEC`), `Gatecrash.kt` (`Space`,
`holdMs`, `closeMs`), `Pedals.kt` (WAH, ROCK, DIVE, ROOM, HUSH), `FxChain.kt` (`reverse`),
`PresetTestSupport.trademarkBlocklist`, `GatecrashKits.kt`, `BoomBapKits.kt`.

## What it is

A dry, mid-forward, confrontational drum kit for Detroit and Flint rap at 110 to 130 BPM on a
straight sixteenth grid, and the processing chain that makes it that: crushed claps and rims,
a metal-box slapback instead of a room, a cocked mid band-pass, and a bus clipper that fuses
the kick and the bass hit into one flat-topped growl. The owner supplied the brief on
2026-11-12: five core voices, five rare voices, six effects and a chain order, with target
numbers for each. Those numbers are the design targets; the harness measures against them.

**Gesture line.** The characteristic gesture is a dry hit at the front of the speaker → the
mids bark → the bus squares it off against the ceiling → gone before the next sixteenth.

Working name SCUFF (a scuff on concrete: dry, scraped, mid-forward). "808", "909", "707",
"SP-1200", "MPC" and every maker in the brief are on the trademark blocklist, so none can
name the engine, a voice or a preset. The voices are KICK, CLAP, BOOM, RIM, HAT, BELL, ZAP,
TICK, PLUCK and INHALE.

## References, distilled (gear-modeling.md §1)

| Reference | Mechanism (no brand words) | Source |
|---|---|---|
| The owner's brief | per-voice spectral windows, decay windows, pitch envelopes and a chain order; see the summary table in the brief | owner, 2026-11-12 |
| Distortion pedal with a filter knob | input band-pass → hard clip → a passive low-pass after the clip; the "filter rolled dark" is that post-clip low-pass | [pedal history](https://thejhsshow.com/articles/history-and-timeline-of-the-proco-rat-pedal) (secondary) |
| Bass overdrive | BALANCE mixes dry against overdrive; HIGH and LOW after | [manual](https://www.adorama.com/col/productManuals/BSODB3.pdf) |
| 12-bit sampler | 12-bit linear words at 26.04 kHz | [reissue page](https://www.rossum-electro.com/products/sp-1200/) |
| Analog delay | 20 to 300 ms in the standard mode, feedback to the edge of self-oscillation | [product page](https://www.boss.info/products/dm-2w/) |
| Drum machine hat and rim | six square oscillators from a hex Schmitt inverter near 205, 304, 370, 523, 540 and 800 Hz; the rim family splits a mix into two band-passes | [ICMC 2014](https://speech.di.uoa.gr/ICMC-SMC-2014/images/VOL_2/1453.pdf), [forum analysis](https://www.kvraudio.com/forum/viewtopic.php?p=7494787) |
| Gate reverb pedal | a reverb mode that "condenses" the tail; the gate's own times were not found in the manual pages reached | [maker page](https://www.boss.info/us/products/rv-5/) |

Not confirmed by a source and therefore proposed: the rim's 850 and 1350 Hz centres (the brief's
numbers; the search found no service-manual value), the bit-reduction pedals' exact depth
ranges, and the gate reverb's hold and release (the brief's 60 to 90 ms and 5 ms are used).

## Why it earns a pad (sibling check)

- **THUMP** (`Thump.kt`): the analog drum engine, nine voices. Its KICK tunes 34 to 62 Hz
  (`expMap(TUNE, 34f, 62f)`), a sub kick; the brief's kick lives at 80 to 160 Hz with a drop
  landing near 95 Hz, above THUMP's whole range. Its RIM is one resonator at 1.2 to 2.4 kHz with
  a body under it; the stank rim needs two peaks at 850 and 1350 Hz, overdriven. Its CLAP's
  shortest tail is 150 ms; the Flint clap wants 60 to 90. Its HAT is the same six-square cluster
  (ratios 1, 1.342, 1.681, 1.940, 2.318, 2.703 on 320 to 620 Hz) and nearly covers the brief's
  hat; SCUFF's hat is the one voice a THUMP preset could replace.
- **TINES** (`Tines.kt`): BELL (two-operator FM with RATIO) and ZAP exist. Whether their ranges
  reach a 1 : 1.414 ratio with a 12-cent drift, and a 1.2 kHz to 120 Hz sweep in 100 ms, was
  not verified; the prototype builds its own so the audition is one engine.
- **FATHOM**: a bass with DRIVE and a resonant low-pass, but a note, not a hit with a pitch
  punch and a hard note-off.
- **The chain**: CRUNCH is GRIT without velocity; WAH and ROCK are the cocked band-pass with a
  sweep, not parked; DIVE is DROP (the tape stop) and should be reused; GATECRASH's `Space` is
  a room keyed from the dry hit and already gates, so CHOKE is a short-hold recipe over it;
  the rack has no asymmetric bus clipper with a low-passed wet path and no comb slapback.

What no sibling does: a kick in the 80 to 160 Hz register with a squared-off tail, a two-peak
overdriven rim, a bass hit with a punch and a hard mute, velocity-driven crush, and a bus
clipper that makes two pads one. The hat is the exception and is said so.

## Signal flow

```
voice (4x: 176.4 kHz)                  1x (44.1 kHz)                             4x            1x
KICK CLAP BOOM RIM HAT ──► bandLimit ──► GRIT ──► LOCKER ──► BARK ──► up 4x ──► SLAM ──► bandLimit ──► DC, peak, fade
BELL ZAP TICK PLUCK        decimate      s&h+bits  comb send  BP send             asym clip     decimate
INHALE                                   (claps,   (rim,      (rim,               600 Hz LP on
                                          rims,     clap,      clap,              the wet path,
                                          hats)     pluck)     pluck, bell)       hard ceiling

Two-bar loop pads: every pad through its own GRIT/LOCKER/BARK, summed, then SLAM on the sum
(the bus). This is the only place the kick and the bass fuse; one-shot pads carry SLAM alone.
Rack: DROP = DIVE (variable-speed read to half speed, then mute); CHOKE = GATECRASH Space with
an 80 ms hold and 5 ms close, the burst slammed on the way in.
```

GRIT runs at 1x without an anti-aliasing filter on purpose: the aliasing is the sound (CRUNCH
does the same). SLAM goes back to 4x because a clipper makes harmonics (the VALVE and FORGE
rule). LOCKER and BARK are linear and stay at 1x.

## Macro contract

| Control | Default | Changes | Measured (Python) |
|---|---|---|---|
| TUNE | 0.5 | per voice, inside its register: kick 70 to 140 Hz, bass 45 to 80 Hz, rim 600 to 1200 Hz, hat and crash half an octave | kick 99.6 Hz at 50 ms; bass 59.5 Hz at 100 ms; rim peaks 832 and 1364 Hz |
| DECAY | 0.5 | per voice, inside the brief's window: kick 90 to 220 ms, clap 50 to 120, bass 200 to 500, rim 35 to 90, hat 20 to 60, bell 0.5 to 1.1 s | kick 180 ms, clap 150 (with the comb), bass 290, rim 80, hat 50, bell 830 to -60 dB |
| SLAM | 0.55 | bus drive 0 to 22 dB, bias 0 to 0.12, wet share 0 to 0.85, ceiling gain 1 to 3; 0 is an exact bypass | kick+bass pair: crest 12.2 → 5.1 → 5.8 dB; 100 to 300 Hz -14.9 → -14.0 → -10.6 dB; time at the ceiling 5 → 80 → 49% at 0, 0.55, 0.9 |
| GRIT | 0.45 | hold rate native to 14 kHz and 16 to 9 bits, both deeper with velocity; claps, rims, hats only; 0 is an exact bypass | |
| BARK | 0.35 | band-pass at 1.1 kHz, Q 3.5 to 6, parallel 0 to 50%; 0 is an exact bypass | |
| LOCKER | 0.3 | comb feedback 0.45 to 0.70 and send 0 to 0.6; 11 ms rim, 19 ms clap, 24 ms pluck; 0 is an exact bypass | |
| Velocity | | the kick drops deeper and clicks harder; the bass punches and drives harder; the clap loses a tap and shortens; the rim's upper peak and overdrive grow; GRIT bites deeper; the hat and tick brighten | soft (0.4) against hard (1.0), level-matched: kick 7.9, clap 14.2, bass 3.4, rim 5.6, hat 10.1, bell 10.1, zap 6.5, tick 4.7, pluck 4.4, crash 2.3 dB |

Per-voice extras, all "shape": KICK CLICK and SQUARE, CLAP SNAP (tap spacing 12 to 8 ms), BOOM
DRIVE, RIM BITE, BELL TONE (FM index), ZAP SHAPE.

## The brief's numbers, measured (defaults, Python)

| Target | Measured |
|---|---|
| Kick body 80 to 160 Hz; 250 → 95 Hz in ~20 ms; 120 to 180 ms; nothing under 45 Hz | 80 to 160 Hz carries all the energy (0.0 dB); 186 Hz over the first 10 ms → 99.6 Hz at 50 ms; 180 ms to -60 dB; below 45 Hz -38 dB |
| Clap: 3 to 4 taps 8 to 12 ms apart; 60 to 90 ms; nothing under 250 Hz; peak 1.2 to 2 kHz | taps at 1.7, 9.1, 21.2, 31.5 ms (then the comb); 150 ms to -60 dB with LOCKER at 0.3; below 250 Hz -16 dB; peak band 1317 Hz |
| Bass hit 50 to 75 Hz; punch; strong 100 to 300 Hz; 250 to 450 ms; hard mute | 122 Hz over the first 12 ms → 59.5 Hz at 100 ms; 100 to 300 Hz at -11.3 dB against the sub at -0.4; 290 ms; muted at 285 ms |
| Rim: 850 and 1350 Hz; 40 to 70 ms; 700 Hz to 1.8 kHz | peaks 832 and 1364 Hz; 80 ms to -60 dB; the band carries all the energy |
| Hat: high-passed at 5 kHz; ring 8 to 11 kHz; 25 to 45 ms | ring at 8.8 kHz; below 4 kHz -12 dB (GRIT folds the top down, by design); 50 ms |
| Bell: 600 to 900 ms, 10 to 15 cents flat | 830 ms; -9 cents at the end (FFT resolution about 4 cents) |
| Zap: 1.2 kHz → 120 Hz over 80 to 130 ms | 1074 Hz over the first 12 ms → 147 Hz at 90 ms; 140 ms |
| Pluck: up ~30 Hz in 8 ms, then down ~80 Hz | 263 → 291 → 213 Hz |
| Suck-back: rises over 250 to 400 ms, ends at zero | 310 ms; last sample 0.0 (cut at a zero crossing) |

Two misses, disclosed: the bass hit's harmonics sit 11 dB under its sub even with the body
held inside the shaper (the measure covers the whole note; the sub outlives the drive), and
the kick's and clap's -60 dB times run past the brief's windows because the chain's stages
(SLAM's ceiling gain, LOCKER's comb) lift the tail. Both wait for the owner's ear before
another number is chased.

## The three directions

| | Operator | Idea | Gesture | Novelty | Risk | Cost |
|---|---|---|---|---|---|---|
| **Strong** | DISTIL + STACK | SCUFF: ten voices built to the brief's registers, the four-stage chain inside the engine, loop pads rendered through one bus | clear | kick register, two-peak rim, bass hit, velocity crush, bus clip: none in the roster | the bus only fuses on loop pads; the hat duplicates THUMP | an engine (26 surfaces) |
| Alternate A | SPLICE | a kit family on THUMP, SKIN and TINES sources plus a new SCUFF chain processor, in the GATECRASH mould | clear | the chain only | THUMP's kick stops at 62 Hz, so the register fails; the rim stays one peak | a kit family (cheap) |
| Alternate B | COUPLE | a phrase engine: every pad is a bar at a documented BPM, rendered through one bus, so kick and bass always fuse | strongest | the bus is real everywhere | tempo on every pad; pads are loops, not hits; an MPC plays hits | engine plus tempo story |

Strong wins because the brief's identity is in the voices' registers as much as in the chain,
and Alternate A cannot reach them. Alternate B's bus is kept as Strong's loop pads (the fill
and build pads the GATECRASH kits already carry), so the fusing is heard where it can be.

## Landing

An engine, proposed. `render` gets no tempo; the two-bar pattern documents its BPM (118) the
way GATECRASH's kits carry one. DROP lands on DIVE and CHOKE on a GATECRASH `Space` recipe, so
neither is an engine macro. If the owner hears the hat as THUMP's, it becomes a THUMP preset in
the kit and SCUFF ships nine voices.

## Round-0 audition

Published 2026-11-12 as a private artifact: <https://claude.ai/artifact/Vh9vx4ezAScNhiLp9KFJd8>
(version 1). 133 clips, all finite; pads and the loop render byte-identical across runs. The page has the
kit, the chain one stage at a time on the rim and the clap, the kick and bass fusing at three
SLAM settings (one hit and two bars), each chain macro swept, TUNE and DECAY on three voices,
the two-bar pattern with parts in and out, the rare voices, DROP and CHOKE, velocity on every
voice, and four takes of every voice with an A/B pair.

Measured highlights (Python harness):
- The pair's time at the ceiling goes from 5% dry to 80% at SLAM 0.55; crest 12.2 → 5.1 dB.
- SLAM 0.9 adds 4 dB of 100 to 300 Hz to the pair and moves its centroid from 282 to 778 Hz.
- Round robin: closest two takes per voice 1.5 (bass) to 5.1 dB (clap) apart; widest 2.5 to
  9.0 dB. The hat's takes are free-running oscillator phases, as on the original circuit.
- The bass hit's takes are the closest (1.5 dB) and its widest pair (2.5) stays under its
  velocity step (3.4): a sine with a punch has little to vary. Disclosed.

## Gates (docs/ENGINE_ACCEPTANCE_GATES.md)

| Gate | Status |
|---|---|
| Six melodic voices, keygroups, premade kit, chord tools | not applicable: a drum engine |
| Appropriate HOLD, STRUM | not applicable: finite hits, no strings |
| Percussion roll or riser | open: the 32nd burst and the suck-back are in the pattern; no authored roll or riser pad |
| Multiple distinct genre kits on the shelf | open: one kit's voices in Python; a second kit proposed |
| Velocity, rendering | pass in the Python harness only (timbral, peak-matched) |
| Round robin, rendering | pass in the Python harness only; not heard |
| Velocity and round robin: saving, playback, export | not yet verified: no Kotlin, no kit, no MPC export |
| Sound character | direction accepted 2026-11-12 ("Sounds good"), Python renders only |

**Overall: incomplete.** Round 0 is a prototype.

## Render budget

Python: all 133 clips in about 8 s; one pad in 5 to 40 ms, the two-bar loop in about 1.5 s
(the comb and the bus up-sample dominate). Proposed for Kotlin: every voice is one 4x pass of
oscillators and biquads, the chain is two biquads, a comb and one clipper, the same order of
work as THUMP plus CRUNCH; one-shots should stay inside the tens-of-ms rule, loop pads near
100 ms. Not measured.

## Sonic risks

- **The bus is per pad.** On one-shot pads SLAM clips each hit alone; the kick and bass only
  fuse on loop pads or in the MPC's own mix. If the owner wants the fused growl on every hit,
  the kick pad must carry its own bass (a layered KICK+BOOM voice) or the kit needs a bus print.
- **GRIT folds the hat down.** The hold at 14 to 22 kHz aliases the 9 kHz ring into 4 to 8 kHz;
  that is the metallic grit asked for, and it also puts 8% of the hat's energy under 4 kHz.
- **SLAM lifts tails.** The ceiling gain brings decays up, so -60 dB times run 20 to 60 ms past
  the brief's windows. A gate after SLAM, or a lower ceiling gain, if heard as too long.
- **Velocity on the clap is a tap count change** (four taps to three under 0.55): big (14 dB)
  and stepped. If heard as two different claps, make the fourth tap fade instead of drop.
- **Mono and dry by design**: nothing here needs width, but the metal locker is a comb and a
  comb in mono can sound like a flange on a long hit; it is only sent from rims, claps and the
  pluck.

## Ledger

- **Verified:** THUMP's kick, rim, clap and hat ranges, TINES's voices, CRUNCH's macros, DIVE's
  range, GATECRASH's `Space` fields, the pedal list, the blocklist terms, all read at `4f47fdb4`.
- **Measured:** every number on this page and the audition page, Python harness.
- **Proposed:** every constant marked "shape" in `scuff_proto.py`; the engine landing; the
  rim's 850 and 1350 Hz; the hat as a possible THUMP preset.
- **Heard:** round 0b, 2026-11-12. After it was published the owner said "Sounds good, please merge."
  Preserve round 0b (`scuff_proto.py` at `59c2ea7b`) as the accepted sound-direction baseline. This
  is a listen to Python renders on the audition page, not evidence of Kotlin, phone or MPC playback;
  no pad, stage or landing was singled out, so the open questions on the page stand.

## Why it is built this way

- **Every voice runs at four times the sample rate and the chain's clipper does too**,
  because saturation makes new harmonics above what 44.1 kHz can hold; left at 1x they fold
  back as fizz the brief does not ask for. GRIT is the one stage that is meant to fold, so it
  runs at 1x on purpose.
- **The bus clipper's wet path is low-passed at 600 Hz** and blended with the dry hit. That is
  what makes the growl low-mid (the trunk) instead of fizzy; a full-band clip would brighten
  every hit. Remove the low-pass and the kick turns into a buzz.
- **GRIT follows velocity** because the brief's bit-crusher reference is at its best modulated by
  the hit: hard hits crunch harder, ghost notes stay cleaner. A fixed crush makes the ghosts
  as rough as the accents.
- **Round robin is a different strike, not a wobble.** Each take moves four things a listener
  hears (the kick's drop depth and speed, the clap's tap spacing, the rim's two pitches, the
  hat's oscillator phases), dealt so no two takes share a setting. ETCH's first takes wobbled
  small independent amounts and the owner heard one strike.
- **The risky decision is the per-pad bus.** SnipSnap renders one pad at a time, so the
  kick-and-bass fusion the brief calls defining is only real on loop pads. The audition plays
  it both ways (one hit, two bars) so the owner can say whether the one-shot version is enough.

## Round 0b: more character (2026-11-12)

After seeing round 0's page the owner asked, "Can we make them a little more distinct?" and
then, asked which "them", answered "More character in general." Not a listen: the owner had not
played the clips when they wrote it.

Measured first, before changing anything. Round 0's ten pads were already far apart from each
other (the closest pair, kick against zap, 17.9 dB by the take-distance measure; ETCH's six
voices sit 3.7 to 7.5 dB apart), so the blandness was inside each hit and in the chain. The
stage-by-stage measure found BARK moving every voice by under 1 dB: a unity-gain band-pass
blended at 17 percent is inaudible. Round 0 shipped a stage that did nothing.

Round 0b, every setting "shape", nothing heard:

| Where | Round 0 | Round 0b |
|---|---|---|
| KICK | sine with a drop, a click, one tanh | the drop from 3x (300 Hz) over 27 ms; a 420 Hz knock under the click; a harder, more asymmetric shaper; a resonant low-pass at 2.2x the body (the rubbery bounce) with the click kept on top |
| CLAP | four 6 ms bursts, a +7 dB peak at Q 1.2 | four 3 ms taps rising in level, a +10 dB peak at Q 2.5, a whip crack (2.5 kHz to 900 Hz sine chirp, 12 ms) on the last tap |
| BOOM | one shaper, low-passed; harmonics 11 dB under the sub | a separate harmonic path (high-passed at 90 Hz, clipped hard, kept to 100 to 400 Hz) mixed under the sub; the mute at 0.7 of the decay, while the note is still loud |
| RIM | two resonators Q 18, a quiet click | Q 26, a woody third resonance at 0.56x (480 Hz), a loud 1.5 ms click, BITE default 0.4 → 0.65 |
| HAT | one exponential decay | a 1 ms 9 kHz tip on the contact; a two-stage decay (8 ms then the ring), so it says "tst" |
| BELL | one modulator at 1.414 | a second modulator at 2.756 that dies in 80 ms (the clang), index 1.5 to 7 |
| ZAP | a fixed light clip | the clip hardest at the top of the sweep, SHAPE default 0.3 → 0.5 |
| TICK | hiss only | a 0.8 ms pitched click at 3.6 kHz under the hiss |
| PLUCK | one sine | an octave slap (15 ms) on the contact, drive 1.4 → 2.2 |
| INHALE | one ring modulator | two carriers, 1.37 and 2.21 kHz |
| BARK | unity band-pass, mix 0 to 50% | a boost of 0 to 18 dB on the band-pass; past 0.3 the dry path loses lows and highs as a parked wah does; output peak-matched |
| LOCKER | feedback 0.45 to 0.70, send 0 to 0.6 | feedback 0.5 to 0.74, send 0 to 0.9, a soft clip in the loop (bounded) |
| SLAM | bias 0.12 | bias 0.2 (more even harmonics) |
| GRIT | hold down to 14 kHz | hold down to 11 kHz |
| Every pad | the exponential tail runs out | a tail gate: the pad ends 2 ms after its envelope last falls 40 dB under its peak |
| Defaults | SLAM .55 GRIT .45 BARK .35 LOCKER .3 | SLAM .65 GRIT .55 BARK .5 LOCKER .45 |

Measured, round 0 against round 0b at the defaults (take-distance, dB): kick 20.0, pluck 15.2,
tick 11.5, rim 11.2, clap 8.7, zap 7.5, hat 7.0, bell 5.2, crash 4.6, bass hit 3.6. For scale, a
soft and a hard kick in round 0 were 7.9 dB apart. BARK now moves the clap, rim, pluck and bell
by 3.3 to 4.9 dB. The bass hit's harmonics (100 to 300 Hz) rose from -11.3 to -6.7 dB against
its sub, which answers round 0's first disclosed miss; its before/after distance is the smallest
because the sub, which the measure weights, did not change.

What else moved, and is disclosed:
- Velocity grew on the kick (7.9 → 14.9 dB soft against hard), rim (5.6 → 8.5), bell (10.1 →
  9.2) and zap (6.5 → 8.3), and shrank on the hat (10.1 → 4.7) because the tip is a fixed
  element at every velocity, and on the clap (14.2 → 9.7).
- Round robin: the kick's takes spread 4.7 to 10.2 dB (was 2.3 to 6.2), under its velocity
  step. The tick's takes are now the closest (1.9 to 2.7 dB) and its widest pair equals its
  velocity step (2.6); a tick is a 13 ms hiss and has little to vary.
- The kick-and-bass pair's time at the ceiling at SLAM 0.55 fell from 80 to 40 percent,
  because the bass hit's harmonic path adds crest the clipper does not flatten at that drive.
- The -60 dB times: the tail gate brings the kick to 140 ms (brief 120 to 180) and the hat to
  20 ms (brief 25 to 45; the gate cuts the ring at -40 dB). The clap stays at 150 ms with the
  comb.

The page gained a before/after section (every pad and the two bars, round 0 then round 0b)
and a stage-moves table. Round 0's model is read from git at `f92233a1` when the audition is
generated, so the A/B is against what was published, not a copy. Published 2026-11-12 to the
same link as version 2 (155 clips).

**Heard, 2026-11-12:** "Sounds good, please merge." The owner accepted round 0b's direction as
published. Preserve it as the baseline; nothing in Kotlin, on a phone or on an MPC has been heard.
