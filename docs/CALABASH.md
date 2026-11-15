# CALABASH: design brief and round-0 audition

Status: **round 0, a Python prototype (2026-11-15), heard and approved.** Four sixteen-pad drum kits the owner asked
for on 2026-11-15: Afrobeat, Afrobeats, Mbalax and Afro-Cuban jazz, "distinct, versatile", following the acceptance
gates. The owner chose four kits (Afrobeat and Afrobeats apart) and a round-0 prototype before any Kotlin. On
2026-11-15 the owner heard the listening page and wrote "Sounds great"; asked whether that was a listen to the page,
they answered "I listened: approve all": all four kits and every voice. Round 0 (`prototype/calabash` at `967f53f7f`)
is the accepted baseline the Kotlin port is judged against. No Kotlin exists yet. The engine stays in progress until every applicable owner gate has evidence (see "Gates" below and the
gates entry in [ENGINE_ACCEPTANCE_GATES.md](ENGINE_ACCEPTANCE_GATES.md#calabash-round-0-2026-11-15-python-prototype-heard-and-approved)).

Prototype: [`prototype/calabash/`](../prototype/calabash/) (`calabash_proto.py` is the model, `fitted.py` the fitted
voices, `kits.py` the four kits and their grooves, `fit_calabash.py` and `hands.json` the fit to CC0 recordings,
`check_fit.py` the fit against the recordings, `gen_audition.py` renders and measures, `build_page.py` writes the
listening page). Every number below is from that Python harness, not from Kotlin.

Listening page: <https://claude.ai/artifact/8vhxM5dJriounZm38WFn8g> (private to the owner; published 2026-11-15). It carries a playable 4×4 pad
grid for all four kits with three velocity layers and four round-robin takes per pad, each kit's groove, every
fitted voice beside the recording it was fitted to, every stroke, velocity ladders and takes for all 64 pads, all
36 voices and every control on every voice.

Repository truth: read at `30392708e` (2026-11-15). APIs named here were grepped: `PadHit.resolve`
(`shell/.../PadHit.kt`), `Restrike.take`, `StarterKits.ALL` and `StarterShelf.GROUPS` (the SKINS heading),
`Clutter.render(voice, macros, velocity, take)` and `Soot.render(voice, macros, velocity, take, model)` as the
drum-engine shape to copy, `Velocity.macroSpecsFor` / `touchedRender`, `PatchTypes.ROSTERLESS` / `PROTOTYPES`, and
BREAKER's fitted cymbals at `synth/src/main/resources/breaker/cymbals.json`. `CALABASH` is a working name (the gourd
under the shekere and under many West African resonators); it is not an engine, voice or preset name anywhere in the
repository.

## What it is

**The characteristic gesture is: a palm, fingertips or a thin stick lands on a skin, a bell or a gourd → the stroke
decides which of the instrument's own resonances ring (an open tone, a choked slap, a pressed stick, a talking drum
squeezed while it rings) → the hand, the bead net or the jingles answer → the room and the era's tape close around
it.**

The four musics share a family tree (West African drumming and its Atlantic crossings) but not a sound, so the kits
are built from different instruments in each role, not one kit at four settings:

- **Afrobeat** (Lagos, the 1970s, 112 BPM): a live rhythm section in one room. A drummer opening and closing the hats
  around a tight, high snare; two congas and a tumba; a shekere on the sixteenths; the sticks' line; a bell.
- **Afrobeats** (Lagos and Accra, now, 104 BPM): programmed. A deep, round kick, a crisp rim on the three-three-two,
  claps and snaps on the backbeat, a swung shaker, congas and a talking drum answering, a high bell. Dry and clean.
- **Mbalax** (Dakar, the 1980s and 90s, 126 BPM): a sabar ensemble over a kit. The nder (the tallest open-bottom
  sabar, the lead drum) on stick and hand; the mbëng-mbëng carrying the mbalax, the accompaniment part; the cól, the
  closed-bottom bass drum; the tama talking over it; straight eighths and a high, cracking snare. A bright live room.
  Drum names and roles are from Prof. Patricia Tang's sabar terms (MIT OpenCourseWare 21M.030, 2013).
- **Afro-Cuban jazz** (New York, the 1950s, 176 BPM): the big band's Latin section beside a jazz drummer. Congas on
  the tumbao, bongos on the martillo, a mambo bell, the timbalero's shell (paila) and abanico, the clave on
  two-three, a ride and an open kick. A big studio heard from across the floor, a valve and tape chain.

What makes it the instrument rather than a sample of it, as mechanisms:

- **Fitted, not guessed.** The owner heard TERRA's first hand drums as "drum machine" and "synthetic"
  ([TERRA_HANDS.md](TERRA_HANDS.md)) and TRIAD's congas as "weak and flat" ([TRIAD_HANDS_R2.md](TRIAD_HANDS_R2.md)),
  and EMBER's cymbals stopped sounding like sheet metal once they were resynthesized from recordings
  ([EMBER.md](EMBER.md), round 0b). So every voice with a CC0 recording behind it starts from that recording's own
  resonances: quinto, conga, tumba, both bongos, two cowbells, both agogo bells, two pairs of claves, a shaker and
  claps (VCSL, CC0 1.0). Each is up to 160 measured modes with two decay slopes and the head's pitch glide, plus
  the residual (what the modes leave out: the hand's slap, the room) as band noise on a 1 ms grid over the first
  30 ms. Velocity moves each mode by its band's measured soft-minus-hard tilt and the residual toward the soft
  recording's.
- **The stroke is the event.** STROKE moves a voice between its players' strokes: bass, open, muffled, slap on the
  congas; thumb, open, muffled, slap on the bongos; open hand, hand slap, open stick, pressed stick on the sabar;
  head, rimshot, shell on the timbale; mouth, body, muted on the cowbell. The open and muffled hand-drum strokes are
  the recordings'. The slap (the heel chokes the note, the cupped fingers crack) and the bass (the palm in the
  middle) are built on the fitted head, because the library has no slap.
- **The talking drum squeezes while it rings.** The dùndún and the tama are hourglass drums whose two laced heads
  tighten together when the arm squeezes the cords, so every mode rises at once, within one beat. SQUEEZE (up to ten
  semitones) and CONTOUR (press up after the hit, let go after it, up and back down) set the path.
- **The sabar's shell and switch.** A pegged goatskin head rings high over a tall open shell (its air as a tube at odd
  multiples of c/4L) or, on the cól, a closed egg (one cavity mode); the galan is a thin springy switch with its own
  whip. Pressing the head with the hand while the stick lands raises its pitch and chokes it.
- **The timbale's steel.** A thin plastic head, very tight, over a shallow steel shell whose ring modes (thin-ring
  ratios, as axial pairs) answer every stroke; the paila is the stick on the shell itself.
- **The shekere's net.** A cloud of bead impacts whose rate and hardness follow the gourd's throw, landing in an
  accent where the net catches up, over a gourd's air (165 Hz for an 8-litre gourd with a 4 cm neck, from c/2π
  √(A/VL)) and its dry shell; the bottom slap booms the air and jumps the whole net.
- **Every stroke is a fresh strike.** Four takes per velocity zone move where the hand or stick lands (a quarter of
  the upper modes change sign; the strike radius moves), how long it stays on, the glide, and fresh noise in the
  slap, the beads and the room.
- **The era.** ROOM runs from a dead booth to the back of a big live studio; AGE is EMBER's ribbon, valve and tape
  (round 0e, the version the owner heard as "Much better"), 0 an exact bypass. The kits set them: Afrobeats .05 /
  .0, Mbalax .35 / .25, Afrobeat .3 / .55, Afro-Cuban jazz .6 / .7.

## Why it earns a pad (sibling check)

The two nearest engines:

- **TERRA** (world percussion: membrane, cavity, conical bell, tuned bar; the hand drums SLAP, DAYAN, DARBUKA, DOUM,
  DJEMBE, DHOLAK, BAYAN and TUN). Its djembe was accepted ("full body"). It has no talking drum, no sabar, no timbale,
  no shekere, and its congas are COMPOUND_MEMBRANE, heard as a drum machine.
- **PATIO** (three Latin performance kits, Cumbia, Samba and Latin dub, over TERRA's hand drums) and **TRIAD**'s
  revised conga and bongo (heard "Much better" inside a bus rig). Neither has a recorded instrument's own modes, a
  stroke vocabulary, or a West African instrument.

EMBER is the nearest kit (a 1962 jazz club); CALABASH's Afro-Cuban jazz kit borrows only its hats and chain, and its
drum kit is a different setup (an open 18-inch kick under a Latin section, not the club kit). BACKWASH has a recorded
cowbell inside soul and psych kits.

None of them can do the four things these musics are built on: **a talking drum that bends while it rings**, **the
sabar's stick and hand on a pegged skin over a tall open shell**, **congas and bongos that are the recorded drums'
own resonances, struck in the stroke a player chooses**, and **the timbalero's shell**. And no kit on the shelf is
Afrobeat, Afrobeats, Mbalax or Afro-Cuban jazz.

## The three directions (round 0 took Strong)

| | Direction | Operator | Gesture | Novelty | Risk | Cost |
|---|---|---|---|---|---|---|
| **Strong** | Fitted where a recording exists, physical where none does; one stroke vocabulary; four kits | DISTIL + STACK (EMBER's fitting onto hand percussion; new physical voices beside it) | clear | high: no talking drum, sabar, timbale or shekere on the roster | medium: 36 voices, 10 of them unrecorded | medium |
| Alternate A | A kit family over existing engines (TERRA's hand drums, TRIAD's congas, EMBER's kit, BREAKER's cymbals), as GATECRASH and MIRRORBALL are | PORT | clear | low: TERRA's and TRIAD's membranes, no new instruments | low | low |
| Alternate B | An ensemble that plays itself: a bell's time-line drives the others' timing and the talking drum answers the lead drum | COUPLE | strong | high | high: a phrase engine is a sequencer, not a pad | high |

Strong wins because the four musics need instruments the roster does not have, and because the owner's past
listens point at recordings (fitted cymbals accepted, guessed membranes rejected). Alternate A cannot make a talking
drum, a sabar or a timbale. Alternate B survives as the grooves' interlock and the four authored rolls.

## Signal flow

```
voice, macros, velocity, take
    │
    ▼
[stroke: palm | fingertips | cupped slap | thin switch | curved stick | timbale stick | bead net | the recording's own strike]
    │   contact width and force follow velocity; the take moves where it lands
    ▼
[body: fitted modes + residual @44.1 kHz, upsampled ×4          (congas, bongos, bells, claves, shaker, claps)
       | membrane modes on a pitch path + the dense upper head   (talking drums, sabar, timbale, kit drums)
       | the shell's air: tube (nder, mbëng-mbëng) | cavity (cól) | gourd (shekere)
       | steel ring modes (timbale shell) | EMBER's fitted hats, BREAKER's ride and crash]
    │
    ▼
[ROOM: direct + a sized room, by energy  [cache: ROOM]] ──▶ [AGE: ribbon, valve, tape (EMBER round 0e)]
    ──▶ band-limit, decimate to 44.1 kHz, DC, fade, peak 0.89
```

The dense upper head: EMBER's membrane stops at 50 Bessel modes (about 2.5 kHz on a 340 Hz sabar), so above that a
physical drum carries band noise per third octave dying at the head's own loss law, struck by the same force. A thin
stick brings it out; a broad palm hides it. Its amount per stroke was swept toward close-miked brightness targets
that are themselves shape (in `calabash_proto.py`, `SKIN`), because the first renders were 40 to 75 dB down at 4 to 8
kHz and the owner has asked every recent kit for more top.

## Controls

Three to six per voice, each 0..1, no HOLD (finite hits).

| Control | Default | What it moves | Voices |
|---|---:|---|---|
| TUNE | .50 | head tension, bell or gourd size: ±3 to ±6 semitones (the toms ±10) | all |
| DAMP | .30 | a hand on the shell, a felt, fingers on the bell, how tight the bead net is held | drums, bells, wood, shekere |
| STROKE | by voice | how the hand or stick lands (above); between two strokes both are played, blended at equal power (an analogy, not a solve) | hand drums, sabar, timbale, cowbells, shaker, shekere |
| SQUEEZE | .45 (.35 tama) | how far the arm squeezes the cords: up to ten semitones | dùndún, tama, the talking call |
| CONTOUR | 0 | press up after the hit (0), let go after it (.5), up and back down (1) | dùndún, tama |
| BEATER | .50 | the kick's beater, a felt feathering the head to a hard beater and its click | kick |
| CLICK | .50 | the programmed kick's tick | deep kick |
| WIRES | .50 | how loose the snare wires are | snare, rimshot |
| CROWD | .30 | one pair of hands to a group | clap, snap |
| SPEED | .50 | how fast the roll's strokes come | the four rolls |
| ROOM | .25 | a dead booth over the drum (+18 dB direct) to the back of a big live studio (-2 dB) | all |
| AGE | .30 | the era's chain: ribbon, valve, tape. **0 is an exact bypass** | all |

## The voices (36)

| Source | Voices |
|---|---|
| Fitted to VCSL (CC0) | QUINTO, CONGA, TUMBA, BONGO_HIGH, BONGO_LOW (open and muffled strokes), COWBELL (mouth, body, muted), BIG_BELL (open, muted), AGOGO_HIGH, AGOGO_LOW, CLAVE, STICKS, SHAKER (push, pull, slap), CLAP (solo, group) |
| Built on a fit | the hand drums' bass and slap strokes |
| Physical, no recording | SHEKERE (push, pull, bottom slap), DUNDUN, TAMA, NDER, MBENG, COL, TIMBALE (head, rimshot, shell), KICK, SNARE, RIMSHOT, RIM, TOM, DEEP_KICK (programmed) |
| Reused | HAT_CLOSED, HAT_OPEN, HAT_FOOT (EMBER's fit of Virtuosity Drums), RIDE, CRASH (BREAKER's approved fit), SNAP (EMBER's crowd) |
| Rolls and risers | SHEKERE_RUSH, TALKING_CALL, SABAR_ROLL, ABANICO |

The cabasa (beads on a corrugated steel cylinder) is fitted too, 3.6 dB from its recording, but no voice uses it yet: it
is the fallback if the physical shekere misses.

Long voices are declared PERC, as BREAKER declares its crash and riser: the ride (2.2 s), the crash (2.6 s) and the
four rolls (2.2 to 2.6 s).

## The kits (four, sixteen pads each)

| Kit | BPM | Swing | Pads |
|---|---:|---:|---|
| CALABASH AFROBEAT | 112 | .12 | kick, snare, rimshot, hat closed, half-open and foot, rack and floor tom, shekere and its slap, conga open and slap, tumba, sticks, bell, **shekere rush (A16)** |
| CALABASH AFROBEATS | 104 | .16 | deep kick, rim, clap, snaps, shaker and its pull, hat, open hat, conga slap, quinto, tumba, talking drum and its fall, bell, shekere, **talking call (A16)** |
| CALABASH MBALAX | 126 | 0 | kick, snare, hat, open hat, nder stick, slap and pressed, mbëng open and stick, cól bass and stick, tama and its talk, high tom, crash, **sabar roll (A16)** |
| CALABASH AFRO-CUBAN JAZZ | 176 | 0 | kick, snare, ride, hat foot, conga open, slap and heel, tumba, quinto, bongo high and low, timbale, paila, mambo bell, clave, **abanico (A16)** |

Hats close their open hats; a stroke on one drum cuts that drum's last stroke (the conga's open and slap, the nder's
three strokes, the tama's two). The grooves are two bars on a sixteenth grid, authored in each style, not
transcribed from any record.

## Measured (Python, round 0)

**The fits against their recordings** (`check_fit.py`, each high-passed at its floor; the house distance, the RMS
difference of level-matched third-octave spectrograms, first 150 ms / whole hit). Two real VCSL takes of one drum
measure 4.0 to 8.6 dB apart on the first 150 ms.

| Fit | First 150 ms, dB | Whole, dB | | Fit | First 150 ms, dB | Whole, dB |
|---|---:|---:|---|---|---:|---:|
| quinto open | 3.4 | 4.1 | | cowbell mouth | 5.0 | 4.8 |
| quinto muffled | 4.0 | 4.5 | | cowbell body | 6.5 | 4.6 |
| conga open | 4.3 | 4.0 | | cowbell muted | 6.2 | 4.6 |
| conga muffled | 4.8 | 4.2 | | big bell open | 6.2 | 5.4 |
| tumba open | 4.0 | 4.7 | | big bell muted | 6.8 | 5.5 |
| tumba muffled | 5.2 | 5.6 | | agogo high | 5.8 | 3.6 |
| bongo high open | 4.1 | 4.1 | | agogo low | 5.8 | 6.0 |
| bongo high muffled | 5.2 | 4.5 | | claves (two pairs) | 3.8, 5.3 | 2.2, 3.3 |
| bongo low open | 4.7 | 4.5 | | shaker push, pull, slap | 3.5, 3.9, 3.9 | 2.4, 3.0, 2.1 |
| bongo low muffled | 4.3 | 4.9 | | claps solo, group | 6.1, 5.0 | 5.0, 4.5 |

Through the whole render (ROOM 0, AGE 0, the house output stage) the page's comparisons measure 3.7 to 6.3 dB on the
drums and the shaker, 5.5 to 10.6 dB on the bells, claves and claps (`gen_audition.py`, "refs").

How the fit got there, measured: the first fit carried every mode 66 dB low (scipy's STFT already divides by the
window's sum; BREAKER's fitter divides again, harmless there because its renders are normalized), so the residual
noise was the whole sound; fixed. Room rumble under the bells was being fitted as modes (+26 dB at 63 to 250 Hz on
the muted cowbell); each voice now has a floor (300 Hz bells, 600 agogo, 800 claves, 150 claps). A late-slope pass
(BREAKER's) took the low agogo's whole-hit distance from 11.3 to 6.0 dB.

**The kits** (4 × 16 pads × 3 zones × 4 takes = 768 renders, all deterministic, finite, peak 0.89, DC at most 0.0002):

| Kit | BPM | Soft vs hard, least (pad) | Closest takes (pad) | Closest two pads | Mean centroid |
|---|---:|---|---|---|---:|
| Afrobeat | 112 | 3.1 dB (A08 floor tom) | 1.86 dB (A03 rimshot) | 4.7 dB (snare, rimshot) | 2300 Hz |
| Afrobeats | 104 | 1.84 dB (A16 talking call) | 2.16 dB (A01 deep kick) | 4.98 dB (shaker push, pull) | 3268 Hz |
| Mbalax | 126 | 2.06 dB (A07 nder pressed) | 1.83 dB (A05 nder stick) | 4.47 dB (tama, tama talk) | 2830 Hz |
| Afro-Cuban jazz | 176 | 2.24 dB (A16 abanico) | 2.0 dB (A01 kick) | 5.14 dB (snare, timbale) | 1687 Hz |

For scale: EMBER's round 0 measured at least 1.92 dB soft against hard and 1.19 dB between its closest takes; ETCH
takes the owner heard as one strike measured 0.5 to 0.8 dB.

**Between the kits** (the same slot, hard, first take): no pad is the same voice at the same settings in any two kits.

| Kits | Same slot, mean | Same slot, closest |
|---|---:|---:|
| Afrobeat / Afrobeats | 18.4 dB | 5.56 dB (A11) |
| Afrobeat / Mbalax | 14.6 dB | 3.99 dB (A02) |
| Afrobeat / Afro-Cuban jazz | 17.8 dB | 5.07 dB (A01) |
| Afrobeats / Mbalax | 22.0 dB | 10.35 dB (A07) |
| Afrobeats / Afro-Cuban jazz | 23.6 dB | 12.83 dB (A10) |
| Mbalax / Afro-Cuban jazz | 15.7 dB | 8.41 dB (A02) |

**Every control moves its voice** (163 controls, 0 against 1 over the whole render): the weakest is TOM's AGE at 1.21
dB (the era chain on a dark low drum), then DUNDUN's AGE at 1.49. Before round 0 shipped, the tama's CONTOUR measured
0.81 dB (its squeeze was too slow for its ring); a faster squeeze (65 ms) and a longer ring took it to 2.37.

**Fixed before the owner's listen, measured:** the first physical renders were 40 to 75 dB down at 4 to 8 kHz (the
membrane's 50 modes end near 2.5 kHz, and EMBER's raised-cosine stick has no spectrum above 2/w); the dense upper head
and a half-sine tip with its own click brought the stick drums to within 3 dB a band of their targets. The
programmed kick's soft takes were 1.18 dB apart (now 2.16), the rim's soft hit 1.7 dB from its hard one (now 3.6),
the dùndún's 2.2 (now 2.5).

**An unknown control is ignored, a non-finite control or velocity falls back to its default, AGE 0 is an exact
bypass:** all true.

## Gates

| Gate | State |
|---|---|
| Melodic, keygroup, chord tool | Not applicable: drum kits. |
| Articulation | Finite hits, no HOLD; no picked or thumbed strings. |
| Percussion: roll or riser | Authored and heard (2026-11-15): a roll or riser on every kit's A16 (shekere rush, talking call, sabar roll, abanico). Python. |
| Drums: several genre kits on the shelf | Authored, measured in Python and heard (2026-11-15), not on the shelf: no Kotlin. |
| Velocity and round robin, rendering | Measured in Python on all 64 pads (above). Not Kotlin. |
| Velocity and round robin, saving, playback, export | Open: no Kotlin. The page's grid is a browser stand-in. |
| Sound character | Approved at round 0 (2026-11-15: "Sounds great", "I listened: approve all"). A listen to Python renders, not a phone or MPC. |

## Render budget

Python, one machine: one-shots median 148 ms, the fitted cymbals about 2 to 3 s (EMBER's fitted-cymbal cost), the
rolls 2 to 6 s. In Kotlin the fitted voices are a sum of at most 160 sines plus 27 band-noise tracks at 44.1 kHz,
and the physical ones at most 50 modes plus about 20 band tracks at 4x: both should sit in tens of milliseconds; the
rolls and cymbals belong in the pad's render cache, keyed by their macros, as BREAKER's riser is.

## Sonic risks (where this would sound bad)

- **The slaps are built, not recorded.** The conga, bongo and sabar slaps are a fitted head reshaped plus a
  synthesized skin-on-skin crack. A slap that sounds like a click on a drum is the likeliest first complaint. The fix is
  a recorded slap; none is in VCSL.
- **The unrecorded instruments.** The sabar family, the talking drums, the timbales and the shekere are physical
  models with shape constants and no recording to measure against. They may land where TERRA's first hand drums did
  ("synthetic"). The listening page puts them beside the fitted voices so the difference is audible.
- **The dense upper head and the tip's click** were swept toward targets I chose. Too much reads as a tick riding on
  top of the drum; too little as a dull thud.
- **The Afro-Cuban jazz kit is the darkest** (mean centroid 1687 Hz; AGE .7 is the heaviest era setting on any
  kit, and congas and tumbas are low drums). The owner asked EMBER for more top on every kit; if this one reads mid
  heavy, AGE and ROOM are the first knobs.
- **Fitted bells sit furthest from their recordings** (about 7 to 11 dB on the house measure, where the drums sit 4
  to 6). Their muted strokes are the weakest.
- **The grooves are authored, not transcribed.** Players of these musics may hear a part in the wrong place; the
  pads stand on their own, the grooves are for hearing them in context.
- **One fitted take per stroke.** Every take re-strikes one recording's modes; a real player's takes vary more in
  where the hand lands than the model's sign flips and radius move.

## Next rounds

- **Round 0: approved (2026-11-15).** No round 0b; the baseline stands as rendered.
- **Round 1:** Kotlin. `Calabash.kt` in the drum-engine shape (`render(voice, macros, velocity, take, model)`, as
  `Soot.render`), `CalabashPatch.kt`, a `Patches` arm, two `Velocity` arms, `hands.json` as a synth resource beside
  `breaker/cymbals.json`, a `DeterminismTest` canary, `CalabashTest` with the probe set.
- **Round 2:** the kits on the shelf (`StarterKits.ALL`, the SKINS group in `StarterShelf.GROUPS`), velocity and
  round robin through saving, `PadHit.resolve` playback and MPC 3 export, read back. MPC 2 keeps each zone's first take
  (the owner's format limit).
- **Round 3:** presets from the accepted pads, the roster, the picker; the owner's phone and MPC.

## Why it is built this way

- **Recordings first.** The owner's past listens are clear: guessed drum and cymbal models were heard as synthetic, and
  models fitted to real recordings were accepted. So every instrument with a free (CC0) recording starts from that
  recording's own ringing, measured, and only the instruments nobody has recorded under a free licence are built
  from physics.
- **One stroke control, many strokes.** A conga player's open tone and slap are different events on the same drum, not
  one sound made brighter. STROKE switches the event, so a kit can put the open tone and the slap on two pads that cut
  each other off the way one drum does.
- **The talking drum is a pitch path, not a pitch bend.** The squeeze tightens both heads together, so every
  resonance rises at once while the drum rings. Drop that and it becomes a pitched tom.
- **Era and room are two knobs on every voice.** The four musics are separated by fifty years of studios as much as
  by instruments; ROOM and AGE carry that, and AGE 0 is a clean modern mic so Afrobeats stays crisp.
- **The risky decision** is the built slap and the four unrecorded instruments. They are where this round is most
  likely to miss, and the page sets them beside the fitted ones so the difference can be heard.

## Sources

- Versilian Community Sample Library (VCSL), CC0 1.0, github.com/sgossner/VCSL at
  `c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e`: 52 recordings, listed with sha256 in `prototype/calabash/NOTICE.txt`. They
  do not ship; `hands.json` holds the measured numbers.
- Virtuosity Drums (CC0 1.0), through EMBER's and BREAKER's fits (hats, ride, crash).
- Patricia Tang, "Sabar terms", MIT OpenCourseWare 21M.030 (Spring 2013): the drum names, sizes and roles.
- Yoad Winter, "On the Grammar of Senegalese Drum Language", supplementary clips (phil.uu.nl): the sabar stroke
  families (hand: gin, pin, pax; stick: tan, tac, ce; hand and stick: rwan, rwe, drin). The page names them but does
  not describe them, so this model labels strokes physically and does not claim which name is which.
- "When Music Speaks: An Acoustic Study of the Speech Surrogacy of the Nigerian Dùndún Talking Drum", Frontiers in
  Communication (2021): the squeeze tracks Yorùbá speech's pitch and timing within a beat. Only the abstract was read;
  no number from it is used.
