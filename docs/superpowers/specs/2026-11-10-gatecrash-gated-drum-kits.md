# GATECRASH: 1980s gated drum kits, engineering specification

Version 1.0 · 2026-11-10
Status: built. Four rounds; the owner approved round 4 by ear on 2026-11-11. Android and MPC hardware not
exercised. Sections 1 to 12 record the round-1 design; the "Round" sections at the end record what each
listen changed, and win where they differ.
Repository truth: read at `a90049ce`. Grepped before naming: `Room.plan`, `Room.bloom`, `Room.lateGain`,
`Valve.upsample`, `Tide.bandLimit`, `Dsp.decimate`, `Dsp.seedFor`, `Dsp.Noise`, `Skin.render(voice, macros,
velocity, take)`, `Thump.render(voice, macros, velocity)`, `Onset.render(voice, macros, modelVersion,
velocity)`, `ArrangedPad.velocityTakes`, `KitAssembler.assembleArranged`, `KitStore.load`, `PadHit.resolve`,
`Mpc3Exporter.exportTrack`, `StarterKits.ALL`, `StarterShelf.GROUPS`, `TriadKitProof`.

The characteristic gesture is **strike → the room rises to meet it → held flat → cut to black**.

## 1. Summary

Three authored sixteen-pad drum kits for the FRESH TAPE shelf, built on the brief "the ultimate 80s drum kit,
that gated tom sound, turned to 11". Each kit is fourteen playable hits, a fill and a build. Every pad has
three velocity zones of four takes. The hits come from SKIN, THUMP and ONSET as they stand; what is new is
one treatment stage, `Gatecrash`, which makes the space two different ways. The room, the crush, the clip and
the gate are computed. The stone room itself is the existing ROOM model, not a measurement of any real room.

## 2. Goals and scope

- **In:** three kits the owner chose on 2026-11-10 (Stone Room, Machine Pop, Electro Funk); 14 hits plus 2
  phrases per kit (also the owner's choice); velocity and round robin through rendering, saving, playback
  and MPC 3 export; a listening pack.
- **Out:** a Patch engine, SYNTH picker knobs, HOLD, melodic keygroups, chord tools, stereo, samples of any
  recording. No maker, studio, artist or product names in kit or pad names.
- **Landing:** a kit family, like TRIAD's kits. The owner chose this over a full engine on 2026-11-10. An
  engine costs about 26 integration surfaces including Android screens this session cannot run; the gesture
  needs none of them.

## 3. Sibling check

- **RIVET** puts a drum in a room that grows. Its own page says "a crushed or gated isolated room return is
  not part of this first implementation". It cannot hold a room flat or cut it.
- **ROOM into HUSH** (two rack sections) made "the gated 80s snare" at HUSH's listening gate. HUSH opens on
  the signal's own level and the rack's order is fixed, so there is no compressor between the room and the
  gate, the gate cannot be keyed from the dry drum, and a fill cannot share one room.

What neither can do: bring the room up to the drum's own level and hold it there, then cut it on the drum's
timing rather than the room's.

## 4. Three directions

| Direction | Operator | Gesture clarity | Novelty against the roster | Risk | Cost |
|---|---|---|---|---|---|
| **Strong: far microphone, crushed, gate keyed by the drum, plus a separate nonlinear burst for the machine kit** | DISTIL two real mechanisms, then JUXTAPOSE them across kits | high: each claim is one measurable number | new: no engine or pedal holds a room flat | the crush evens out velocity (measured, see As built) | one new 280-line stage, no new engine |
| Alternate A: ROOM, COMPRESSOR and HUSH as a rack recipe | STACK | low: the rack's fixed order puts the compressor before the room | none: this is what exists | none | cheapest, and does not make the sound |
| Alternate B: a full engine with ROOM, CRUSH, GATE and TUNE knobs | PORT | high | new | Android screens unverifiable here | about 26 surfaces |

Strong wins because it is the only one that makes the room as loud as the drum, and it costs one file.

## 5. Signal flow

```
velocity, take
     │
     ▼
[Source: SKIN / THUMP / ONSET strike]──(dry, mono, 44.1 kHz, peak = touch)──┬──────────────────────────────┐
                                                                           │                              │
                              [Key: attack detector on the dry hit]◀───────┤                              │
                                     │ (attack frames)                     │                              │
            STONE                    ▼                                     ▼                              │
            [Room return: ROOM's taps + 16-line bloom, far mic, no direct] [cache: SIZE, WALLS]           │
                 │ (wet, 44.1 kHz)                                                                        │
                 ▼                                                                                        │
            [Crush: 12:1, threshold 6..40 dB under a full hit's room]                                     │
                 │                                                                                        │
                 ├──────── BURST instead: [hit's first 42 ms ⊛ velvet burst, flat or rising, then stops] │
                 ▼                                                                                        │
            [Soft clip: tanh at 176.4 kHz → Tide.bandLimit → Dsp.decimate]                                │
                 │                                                                                        │
                 ▼                                                                                        ▼
            [Gate (STONE only): open at each attack, hold, close] ──× send = room · touch^1.5 ──▶ (+) ◀── [dry, optionally cut with the gate]
                                                                                                 │
                                                                                                 ▼
                                             [Output: mean out → 20 Hz high-pass → zone gain → trim at −80 dB → fadeTail]
```

A phrase (fill or build) runs the same graph once on the sum of its dry hits, so every note re-keys one
shared gate.

## 6. The model

### 6.1 Stone: room return, crush, clip, keyed gate (literal)

- **Room return.** `Room.plan` at the pad's SIZE and WALLS with the microphone at DISTANCE 0.9. The return is
  the plan's one-bounce and two-bounce taps, darkened once per bounce, plus `Room.lateGain × Room.bloom`. The
  direct sound is left out. Linear. `Room.process` could not be reused whole: it sums the direct sound and
  peak-matches the total. The tap loop is therefore repeated in `Gatecrash.roomReturn` (disclosed
  duplication, 14 lines).
- **Crush.** A peak follower (attack 0.4 ms, release 45 ms) and a 12:1 gain law above a threshold set
  `crushDepthDb` (6 to 40 dB) under the level a full hit's room first reaches. While the room is over the
  threshold the gain rises as the room falls, so the level holds. Not simulated: any particular compressor's
  detector, knee or distortion.
- **Soft clip.** `tanh(g·x)/tanh(g)`, `g` 0.5 to 3.2, at 4x through the house band-limiter.
- **Gate.** Keyed by the dry drum: an attack is a frame where the level leads its own held peak of 3 ms
  earlier by 1.7 times and is above 0.05 of full scale. The gate opens in 1.5 ms, holds `holdMs` after the
  last attack and closes over `closeMs` on a raised cosine. Feed-forward, so there is no stability question.

### 6.2 Burst: a nonlinear reverb (literal in shape, analogy in detail)

The first 42 ms of each hit is convolved with 2,200 randomly signed taps per second whose level is flat or
rising (`tilt`) for `holdMs` and closes over `closeMs`. Then a 180 Hz high-pass, a low-pass set by WALLS, and
the same clip. Nothing decays, so nothing is gated. The shape follows the published description of the
program family ("the sound holds for an initial period set by the decay control before it falls away"). The
tap pattern, density and filters are this design's, not a copy of any unit's.

### 6.3 Velocity

`touch(v) = 0.35 + 0.65 v` is the dry hit's level against a full one. The crush threshold is set against the
full hit. The return is sent at `room · touch^1.5`. Finished zones are levelled to one peak (0.92), because
the app and the MPC both turn pad velocity into gain.

## 7. Control contract

These are authoring controls on `Gatecrash.Space`, set per pad in `GatecrashKits`. None is a user knob.

| Control | Changes | Required interaction | Test |
|---|---|---|---|
| `crush` | threshold depth under the room, 6..40 dB | crush × hold: the hold only reads as flat if the room stays over the threshold that long | `the crush holds the stone room flat where the bare room decays` |
| `holdMs`, `closeMs` | when and how fast the space shuts | a ringing drum must not extend the hold | `the gate cuts the stone room to silence`, `a ringing drum is one attack...` |
| `room` | return level against a full dry hit | room × drive: the clip bounds what `room` can reach | `the stone room stands about as loud as the drum`, `the return is bounded by the soft clip...` |
| `drive` | clip gain | as above | `the soft clip does not fold harmonics back...` |
| `tilt` (BURST) | flat to rising burst | | `a burst does not decay and then stops` |
| `dryCut` | how much the gate also cuts the dry drum | | read, not separately measured |
| `mode` | STONE or BURST | | `the three kits are different kits` |

## 8. The kits

| Pad | Stone Room (96 BPM) | Machine Pop (118 BPM) | Electro Funk (108 BPM) |
|---|---|---|---|
| A01 | Stone Kick (SKIN) | Punch Kick (THUMP) | Boom Kick (THUMP, dry) |
| A02 | Wall Snare (SKIN) | Burst Snare (THUMP) | Snap Snare (ONSET) |
| A03 | Closed Hat | Closed Hat | Closed Hat (dry) |
| A04 | Open Hat | Open Hat | Open Hat (dry) |
| A05 | Cross Stick | Burst Clap | Room Clap |
| A06 | Crack Snare | Rim | Rim Click |
| A07 | Concert Tom 1 | Hex Tom 1 | Cowbell |
| A08 | Concert Tom 2 | Hex Tom 2 | Thud Kick |
| A09 | Concert Tom 3 | Hex Tom 3 | Low Tom |
| A10 | Concert Tom 4 | Hex Tom 4 | Mid Tom |
| A11 | Concert Tom 5 | Cowbell | High Tom |
| A12 | Floor Tom | Spark | Zap |
| A13 | Dark Ride | Tight Snare | Shaker (dry) |
| A14 | Gated Shaker | Machine Crash | Block |
| A15 | Tom Fill (phrase) | Hex Fill (phrase) | Tom Break (phrase) |
| A16 | Snare Build (phrase) | Clap Build (phrase) | Snare Build (phrase) |

Space mechanism: Stone Room is all STONE (one large hard room, three hold lengths). Machine Pop is all BURST.
Electro Funk is a small bright STONE room with a 110 ms hold, and four dry pads. Hats share choke group 1;
the two phrases share group 2.

## 9. Render budget

Target: tens of milliseconds for a hit on a phone. Measured on this cloud JVM, warm: a stone tom 24 ms, a
machine tom 22 ms, a crash 105 ms, a twelve-note fill 206 to 256 ms. A whole kit (192 renders) built in 13.2,
12.1 and 9.6 s in the generator (Machine Pop took 33 s before the clip fix). Starter creation
renders once; pad playback reads saved slices and synthesises nothing. The room plan and its late gain are
cached per (SIZE, WALLS). No phone timing exists.

## 10. Sonic risks

1. **The room is louder than the drum.** The wall snare's held room peaks 2.6 dB over the dry hit. That is the
   brief, and it is the first thing to judge. `room` on each `Space` is the one number to pull back.
2. **The cut clicks.** A raised-cosine close of 10 to 25 ms is short. Listen on the floor tom.
3. **Soft zones sound like a different drum, not a softer one.** The send law is steep (power 1.5).
4. **The machine toms' dive rings on after the burst stops**, since the dry tom is not cut.
5. **Takes are too alike on noise voices.** THUMP, ONSET and SKIN's hats and shaker cannot be re-seeded from
   outside, so their takes are small control moves, not new noise.
6. **Mono.** The famous records are wide. This is one channel.

## 11. Research references

Research text only; no name here appears in the product.

- The sound's origin as described by its engineer: a heavily compressed listen microphone heard through a
  desk's noise gate ([Wikipedia: Gated reverb](https://en.wikipedia.org/wiki/Gated_reverb),
  [MusicRadar](https://www.musicradar.com/features/how-genesis-peter-gabriel-and-phil-collins-stumbled-upon-the-80s-gated-reverb-drum-sound),
  [Produce Like a Pro](https://producelikeapro.com/blog/songs-that-changed-music-phil-collins-in-the-air-tonight/)).
  Sources differ on dates and credit. None was used for a number.
- The later digital "nonlinear" reverb programs
  ([Reverb.com](https://reverb.com/news/how-the-ams-rmx16-defined-a-generation-of-drum-sounds), and the
  unit's [user manual](https://www.manualslib.com/manual/3364679/Ams-Rmx-16.html), of which only a search
  excerpt was read).
- Velvet noise as a sparse reverb kernel is a standard technique; no code was read or adapted for it.

## 12. Verified / proposed / heard ledger

| Claim | Status | Evidence |
|---|---|---|
| The crushed stone room holds within 3 dB across the hold on a 5 ms click; the same room at `crush` 0 falls at least 5 dB more | measured | `GatecrashTest` (the bare room fell 9.1 dB in the run that set the bound) |
| The gate takes the return at least 60 dB down within 5 ms of the close | measured | `GatecrashTest`; the generator's `metrics.csv` reads −180 dB (exact silence) on every STONE pad |
| A held tom room peaks within 6 dB of the dry hit | measured | `GatecrashTest`; `metrics.csv` reads +0.3 to +0.5 dB on the six toms |
| A ringing drum is one attack; a phrase is one per note | measured | `GatecrashTest` over all 42 hits and the phrases |
| The clip does not alias: a folded 7th harmonic under −60 dB, against a 20 dB worse naive clip | measured | `GatecrashTest` |
| A soft zone is duller and has less room for its level | measured | `GatecrashTest` (all 42 hits duller; toms at least 1.5 dB drier) |
| 576 slices survive save, reload, playback and MPC 3 export | measured | `:shell:generateGatecrashKits`, `GatecrashKitTest` |
| Every `Space` number and every source setting | proposed | authored unheard |
| The kits sound like the 1980s | not heard | the listening pack |
| Phone playback, MPC hardware playback | not verified | no device in this session |

## Why it is built this way

- **The compressor is the sound, not the gate.** A gate on a room only shortens it. The crush is what lifts
  the room to the drum's level and keeps it there; the gate then gives it an edge. Take the crush away and
  the test that compares the two fails.
- **The gate listens to the drum, not the room.** That is why the fill works: twelve notes keep one room open
  and it shuts once, after the last. A gate that listened to its own room would chatter.
- **Two mechanisms, because the decade had two.** A live room through a compressor, and later a digital burst
  that never decays. Using both is what makes Machine Pop a different kit rather than Stone Room turned down.
- **Zones are levelled, strikes are not.** The app and the MPC add their own velocity-to-loudness. So the
  zones differ in how the drum was hit and how much room it got, and the pad decides how loud.
- **The risky decision is the send law.** A compressor makes soft and hard hits' rooms nearly the same, which
  is the opposite of what a player expects. The fix is an authored power law, not physics. It needs a listen.

## As built

- **Files:** `synth/.../Gatecrash.kt` (the stage), `synth/.../GatecrashKits.kt` (the three kits),
  `synth/.../GatecrashTest.kt` (19 probes), `shell/.../GatecrashKitTest.kt` (3 integration tests),
  `shell/.../GatecrashKitGenerator.kt` and the `:shell:generateGatecrashKits` task, three `StarterKits`
  entries and their `StarterShelf` place under SKINS. `Room.lateGain` went from `private` to `internal`; no
  ROOM behaviour changed.
- **Changed against the first design, with the measurement that forced it:**
  - *The key.* A fast-against-slow envelope detector heard two attacks in the floor tom (100 Hz ripple) and
    held its gate open. Replaced by level against its own held peak 3 ms earlier. All 42 hits now read one.
  - *The burst's send.* Convolving the whole hit smeared the burst as long as the drum rang (no cut at all on
    the snare). The burst is now fed only the hit's first 42 ms.
  - *Velocity.* The first claim was that a soft hit's room is "flattened less". Measured, the crush did the
    opposite: a soft tom had 2.6 dB **more** room for its level than a hard one. The send law (`touch^1.5`)
    was added to reverse it and the claim was rewritten.
  - *Levels.* Zones were first rendered quieter as well as softer. The app's `PadHit.gains` multiplies by
    velocity again, so they are now peak-matched.
  - *Electro Funk's snare.* THUMP's snare at two settings measured as the same drum between kits (shape
    distance under 0.5). Electro Funk's is now ONSET. ONSET's snare ignores its velocity argument (brightness
    equal to eight figures), so it is touched like THUMP's voices.
  - *Render time.* The clip took 97 ms on a signal that goes silent, against 12 ms on one that rings: the
    band-limiter's states were decaying through denormals. The clip now runs only on the live part.
- **Not done:** MPC 2, SFZ and DecentSampler exports (MPC 2's projection keeps only the first take per zone);
  parallel kit building (the source engines' thread safety was not checked); stereo.

## Round 2 (2026-11-10, after the first listen)

**Heard:** "Yikes. That is harsh and doesn't sound like 80s drums at all. More like loud static."

**Measured after the listen** (Kotlin, round-1 code):

| Pad | Room energy against the drum | Share above 4 kHz: drum / room |
|---|---:|---|
| Stone Room, Wall Snare | +19.8 dB | 24.3% / 22.1% |
| Stone Room, Concert Tom 1 | +19.4 dB | 1.6% / 2.1% |
| Stone Room, Tom Fill | +15.8 dB | 0.7% / 1.1% |
| Machine Pop, Burst Snare | +13.6 dB | 75.3% / 72.1% |
| Machine Pop, Burst Clap | +15.2 dB | 47.0% / 48.4% |
| Electro Funk, Snap Snare | +16.4 dB | 27.2% / 22.2% |

A pad that is 97 to 99 percent room, where the room is as bright as a snare and held flat for 300 ms, is a
burst of noise. Three more things made it worse: `dryCut` 0.5 to 0.7 gated away the drum's own body, `drive`
0.5 put a level-1 plateau through `tanh(2x)`, and THUMP's snare at SNAP 0.75 had 75% of its own energy above
4 kHz before any room.

**What was wrong in the design, not just the settings.** Section 6.3 and the `room` control set the return
against the dry hit's *peak*. A peak says nothing about how much of the pad is room: a plateau at the drum's
peak level for 340 ms carries about 80 times a tom's energy. The probe "the stone room stands about as loud
as the drum" then enforced the fault. That probe is removed.

**Changed:**

- `Space.roomDb`: the return's whole energy against the dry hit's, in dB, for a full hit. `Gatecrash.calibrated`
  measures the pad once and sets the linear send. `MAX_ROOM_DB` is +6; the kits use at most +4 (snare, toms),
  0 to +3 on claps and the tight snares, -2 to -3 on the synth toms, -6 to -12 elsewhere.
- `Space.toneHz` and `lowHz`: a 12 dB per octave low-pass (1.5 to 3.5 kHz by pad) and a 120 Hz high-pass on
  every return, before the crush.
- `drive` defaults to 0.15 (was 0.3 to 0.6). `dryCut` is 0 to 0.3 (was up to 0.7).
- Hats in Stone Room and Machine Pop and the Machine Crash have no space.
- Machine Pop's snares: SNAP and TONE lowered. The Burst Snare's own share above 4 kHz went from 75% to 29%.
- The gesture line is now "strike → the room stands level behind it → cut to black".
- `Gatecrash.VERSION` and `GatecrashKits.VERSION` are 2.

**Measured, round 2:** Wall Snare +4.1 dB with 2.4% of the room above 4 kHz (drum 24.3%). Concert toms +4.2 dB.
Burst Snare +3.1 dB, room 3.9% above 4 kHz (drum 28.9%). Snap Snare +1.4 dB, room 4.8% (drum 27.2%).

**New probes:** every room within 0.3 dB of its authored energy and none over +4 dB; every audible room under
0.6 of its drum's share above 4 kHz; the dry drum outweighs its room in the first 15 ms on every pad; no hat
has a space. 21 of 21 pass.

**Two measurement changes, disclosed.** The held-level probe now uses a 50 ms window: 20 ms held two cycles of
a 115 Hz tom and read its beating as a 4.4 dB level change. The take-distinctness floor went from 0.01 to
0.005: with a quieter room the stone kick's soft takes measure 0.009 apart. Identical audio measures 0, so
the probe still fails on a copied take, but that pair is a weak round robin.

**Lesson for the ledger.** Round 1 passed 19 of 19 probes and sounded like static. Every one of those probes
tested that the mechanism did what the design said. None tested whether the design's amounts were sane. The
three new probes are the ones that would have failed round 1.

**Audition:** the page adds "Less room" and "More room" (6 dB either side) on the main snare, one tom and the
fill of each kit, so the amount can be chosen by ear. Round 2 is not heard.

## Round 3 (2026-11-10, after the second listen)

**Heard, on round 2:** "It seems like 80s drums round off at the end rather than cut abruptly."

The owner is right about the hardware. A gate has a release time, and the nonlinear programs' own description
is that the sound holds "before it falls away". Round 2's `closeMs` was 10 to 25 ms: a chop, not a release.
The round-1 design called the gesture "cut to black" and section 10 asked only whether the cut clicked.

**Changed:** `closeMs` is 130 ms on the stone toms, 110 on the wall snare, 90 on the tight stone pads, 70 on
the stone kick; 50 to 90 across Machine Pop; 45 to 60 in Electro Funk. The shape is unchanged (a raised
cosine on the gate, the burst's own envelope in Machine Pop). `GatecrashKits.VERSION` is 3. The gesture line
is now "strike → the room stands level behind it → rounds off to silence".

**New probe:** no space closes in under 45 ms, and a STONE gate never falls more than 3 dB in a millisecond.
22 of 22 pass.

**Audition:** the three trial pads per kit gain "Sharp cut" (15 ms) and "Longer fade" (twice the authored
close). Whether 110 to 130 ms is the roundness the owner means is not known; that is what the two buttons are
for. Round 3 is not heard.

## Round 4 (2026-11-10, after the third listen)

**Heard, on round 3:** "I think we are missing some brightness."

Round 2 fixed round 1's static with two moves at once: it cut the rooms' level by 12 to 16 dB and it
low-passed them at 1.5 to 3.5 kHz, and it also lowered the machine snares' SNAP and TONE. The level was the
larger fault (rooms at 97 to 99 percent of the pad). The tone cut went further than the fault needed, and the
probe written for it (a room under 0.6 of its drum's share above 4 kHz) then held the kits dull.

**Changed:**

- `toneHz`: stone kick 1.5 to 2.2 kHz, wall snare 3.2 to 5.5, concert toms 2.4 to 4.0, cross stick 2.0 to 3.5,
  ride 2.5 to 4.0; Machine Pop snare 3.5 to 6.0, toms 3.0 to 4.5, clap 2.5 to 5.0; Electro Funk snare 4.0 to
  6.0, toms 2.8 to 4.2, clap 2.5 to 4.5.
- Sources: Machine Pop's two snares get about half of the SNAP and TONE back that round 2 took; the wall
  snare's SNAP is 0.68 (was 0.60); the concert toms' TONE is 0.62 and CLICK 0.55 (were 0.55 and 0.45).
- Room energy (`roomDb`), close times and everything else are unchanged. `GatecrashKits.VERSION` is 4.

**Probe changed, disclosed:** "every room is darker than its drum" (under 0.6 of the drum's share above
4 kHz) is now "no room is brighter than its drum" (under 1.0). The owner's ear overruled the stricter number.
The level probes that hold round 1's fault away are untouched. 22 of 22 pass.

**Audition:** the three trial pads per kit gain "Darker room" (tone at 0.55 times, about round 3) and
"Brighter room" (1.7 times). Each is re-calibrated to the same room energy, so the comparison is colour, not
amount. Whether the missing brightness was in the rooms or in the drums themselves is not known; the page asks.
Round 4 is not heard.

## Round 4 heard (2026-11-11)

**Heard:** "Everything sounds good".

Round 4 is the accepted baseline: `GatecrashKits.VERSION` 4 on `Gatecrash.VERSION` 2. The owner listened on
the published page, which plays 16-bit mono copies of the exported slices, the dry comparisons and the trial
clips. Which of the trial clips (less or more room, sharp or longer fade, darker or brighter) they compared is
not recorded; no change was asked for, so the authored middle stands.

| Claim | Status | Evidence |
|---|---|---|
| The three kits sound right | heard, approved | the owner, 2026-11-11, on round 4 |
| Phone playback, MPC hardware playback | not verified | no device audition |
| MPC 2 round robin | fails | its projection keeps one take per zone |

What the four rounds showed about method: every correction came from a listen, and none from a probe. The
probes were worth having afterwards, as guards on what each listen fixed.
