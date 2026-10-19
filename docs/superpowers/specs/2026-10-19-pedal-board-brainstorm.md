# The pedal board, continued: a brainstorm of the next pedals and how the chain grows

**Status:** brainstorm, 2026-10-19, not yet decided; nothing here is built or scheduled. As of this date Stage 0 (DRIVE and COMP) is built and awaits the owner's phone listen, and Stage 1 (the WAH section, PR #576) is built with its listening gate pending; the WAH chip is not on the row yet. It is input to the owner's choice of what becomes a spec next. The pedal board spec's Stages 1 to 5 (`docs/superpowers/specs/2026-10-04-pedal-board-design.md`) stand as written; this document says where new work would slot in beside them.

**Who this is for:** the owner. Every idea says in plain words why it is a recipe or a section, why it sits where it sits in the chain, and what the owner would listen for at its gate. Code is named only where the next step has to go there.

**How it was made:** eleven angles (a guitarist, a sampler, a novice, a DSP engineer, a chain designer, a house-style reader and five gap hunters) proposed about sixty ideas. A merge folded aliases together and dropped the weak ones. Two verifiers then checked each of the 24 highest-ranked survivors against the code as it stands today: one read the code and answered two questions (can it be built, and what must change first; is it already in the rack under another name), the other read the specs and the October direction and scored whether it is worth the owner's time (1 to 5, where 5 means both audiences would tap it on the first day). Their caveats are carried here, not smoothed over. Where a number is quoted it was measured or read off the code by a verifier; where it is a guess it says so. The controller then checked the document's code references by hand: the rack order and its pinned tests (speed first, swell second; valve right after squash; wah right after contour), the preset-name collisions named for each saved key, the rows' free chip counts, ENSEMBLE's vibrato, the organ engine's rotary, VOX's formants, the keyed family's doors, OUTSIDE's ROOM and KEEP, and the way a chip re-treats a pad's original audio. The DSP sketches, effort letters and listening claims are the agents' judgement and are not measurements.

## 1. What a pedal is here

A pedal is either a recipe (a named set of values for sections the rack already has, no new sound-making code, one line in a table, the way DRIVE is VALVE at DRIVE 1.0) or a section (new DSP in a fixed slot of the rack with a few 0..1 knobs, each with a neutral where it does nothing, and an owner listening gate before it earns a chip), and a few live outside the rack as keyed treatments (they read the kit's key or tempo, or need a tail longer than the rack's one-second cap, the way ETERNAL does) or as boards (one chip that sets several sections at once, which is a bigger recipe, not a new kind). A chip on the pad sheet acts on the pad's baked audio, so on a JANGLE or CHUG pad every chip today lands after the pad's own landing amp, and the spec's Stage 3 is the design that puts a pedal in front of that amp. The AMT stepper (default 0.7) fades every knob of a recipe toward its neutral, so a recipe must set its values high enough that the first tap already sounds like the pedal (WAH's lesson: it stretches its reach by 1.5 so AMT 0.7 reads as full). A section's name becomes a permanent key in every saved `kit.json` and a forbidden word in preset names, so the saved key is often not the chip word (DIVE's key is `plunge` because four SIREN presets are named DIVE WAIL, DIVE TRILL, DIVE LASER and DIVE BIRD).

## 2. The chain as it would grow

The rack order is fixed and is the signal path. A section takes the slot a real pedal board would give it; no routing screen is added. Below, the order as it would look if every surviving section passed its gate. Legend: plain words are today's sections; `{STAGED}` is a pedal the spec already plans; `[NEW]` is a surviving idea from this brainstorm, with its saved key in brackets.

```
 TRANSPORT (length may change here, by instruction, outside the tail cap)
   speed(PITCH) -> [DIVE: plunge] -> swell(SWELL) -> (reverse)

 RACK, BEFORE THE AMP (what the amp will distort)
   smear -> ghost -> spike -> [FADE IN: volume] -> [SUB: octaver] -> eq -> contour
   -> wah(WAH) -> [TALK: formant] -> {COMPRESSOR, Stage 2} -> squash(COMP) -> {FUZZ, Stage 5}
                                                                                   |
                                                                                   v
                                                                             valve(DRIVE)  <== the amp
 RACK, AFTER THE AMP (what the amp already made)                                   |
   crunch -> [CHOKE: hush] -> ring -> dub -> vinyl -> tape -> [VIBRATO: vibrato]  <-+
   -> ensemble -> phase -> {FLANGER, Stage 4} -> {TREMOLO, Stage 4} -> [ROTARY: rotary]
   -> echo(SLAP, and DELAY as a recipe) -> [REWIND: rewind] -> spring(WASH) -> motion

 RECIPES (no slot: they ride a section that already exists)
   DELAY  = echo at long, dark values        (and `spaced`: echo + spring + a little bass, CLI only)
   BLOOM  = swell + valve + spring           (the free first listen for FADE IN; exists today)

 OUTSIDE THE RACK, the KEYED row (reads the kit's key or tempo, or needs a long tail)
   TUNE  BODY  WOBBLE  ETERNAL  |  [ON BEAT: echoed]  [PUMP: pumped]  [SHIMMER: shimmered]  [HOWL: howled]

 THE CHAIN ITSELF (no sound of their own; they change how every pedal lands)
   FRONT OF AMP (Stage 3): a pedal's sections join a guitar pad's landing chain and the pad re-renders
   MERGE: a second chip joins the first instead of replacing it
   SHORT PAD: laws for the hat and the click (one full cycle, no end click, a refusal in words)
```

Why before or after the amp: a pedal that shapes what the amp distorts (a volume swell, a sub, a talk box's mouth, a compressor, a fuzz) sits before VALVE; a pedal that the real rig puts in the amp's effects loop or after the speaker (a delay, a reverb, a rotary cabinet, a vibrato, a gate on the output) sits after it. Today every chip lands after a guitar pad's landing amp anyway (section 1), so the slot only matters for stacked chains and for Stage 3; the verifiers point this out on each idea.

**How the PEDALS row grows.** The row holds six chips. DRIVE and COMP are on it; WAH, TREM and FLANGE are promised to it; that leaves one slot. Eight of the ideas below want that slot (DELAY, FADE IN, VIBRATO, CHOKE, ROTARY, REWIND, TALK, SUB), and two cannot (DIVE goes to the TRANSPORT row, which has two free chips; PUMP, SHIMMER, HOWL and ON BEAT go to the KEYED row, which has two). So the sixth chip is a decision the owner makes once, after hearing the claimants side by side, and the proposal in section 6 is to let the first post-amp gate decide it. After that, a second row is forced. The cut BOARDS ROW idea (section 5) is the honest answer to that: an eighth row of sound words (SLOWED, MUFFLE, RADIO) and post-amp rigs, each a recipe over several sections, AMT fading the whole board together. It was set aside only because its mechanism already exists (`punched` is a two-section board today) and its contents were not decided; it is one list entry in `PadSheet.ROWS` when the time comes. The six-per-row and one-word-one-row laws stay. A section that fails its gate still ships as a CLI character with no chip, as the spec already allows, so the row is not what limits what gets built, only what the novice sees.

**What a board chip would be.** A board is a recipe that sets two or more sections (RIG = compressor into a cranked amp into a slap and a spring; METAL = scoop, hot amp, gate; SHOEGAZE = hot amp, wide chorus, long echo, big spring; BOOM = DIVE plus SUB plus bass plus squash). Its cost is one table line and no DSP. Its hazard is AMT: with nine knobs all faded by 0.7 the board is milder in every dimension at once, and a DRIVE 1.0 inside it falls to gain 11 at the first tap, the exact "not enough" the owner heard at Stage 0. Section 4 says what that means for the boards that survived in other ideas' pockets.

## 3. The shortlist

Ranked by the verifiers' worth score, then by cost and certainty. Pedals first (1 to 12), then the three ideas that change the chain itself (13 to 15), ranked among themselves because they are not pedals. "Both audiences" means the MAGNET guitar voices (JANGLE, CHUG) and any pad (a drum, a chop, the choir). Each verifier line gives the verdict and its confidence; "new" means a mechanism the rack does not have.

### 1. DIVE (section, saved key `plunge`), worth 5

**Chip and key:** DIVE on the TRANSPORT row (two free chips). Key `plunge`: four SIREN presets are named DIVE. Recipe `dived` = {DROP 1.0}.

**Kind and slot:** a section, because no knob bends pitch over time (SPEED is a fixed ratio, STOP fades to silence). TRANSPORT stage, right after SPEED, because it grows the pad up to 2x and only that stage may add length, outside the one-second tail cap, as PITCH does.

**What and why:** the pitch starts true, falls DROP semitones fastest at the hit, then holds; the attack is untouched. Kick or tom: the 808 pitch envelope, the second thing a drum-machine user wants. CHUG: the whammy dive bomb, which still reads after the baked amp because a dive is a pitch gesture, not a gain one.

**Macros:** DROP 0..12 semitones [0]; OVER 30 ms..1 s [0.5] (rename FALL).

**Reuses and DSP:** `Dsp.readAt`, SPEED's ratio law and peak match; sum the head position at speed 2^(-drop/12), one variable-speed read; a copy at DROP 0.

**Hazards:** length up to 2x; a late-peaking pad's peak moves; a 40 ms rim dives inside its click; a tonal pad leaves the key (TUNE re-snaps or refuses: say so).

**Effort:** S.

**Gate:** snare at DROP 1: a kick? Tom: an 808 tom? CHUG: a dive bomb with the pick unchanged? Add a rim and a reversed hit.

**Verifiers:** buildable with fixes (0.85): a running sum, not a closed form; the "speed first, swell second" test pin becomes speed, plunge, swell; the recipe must not set OVER at its neutral. New (0.85). Worth 5: both audiences, day one, no PEDALS chip spent.

### 2. DELAY (recipe over ECHO; keyed twin ON BEAT), worth 4

**Chip and key:** DELAY (or TRAIL) on the PEDALS row's last slot; `delayed` = echo {TIME 0.75, REPEAT 0.85, TONE 0.35, MIX 0.5}. Keyed twin `echoed`, chip ON BEAT on the KEYED row: ECHO at three sixteenths of the kit's bar, tail capped at one bar.

**Kind and slot:** a recipe, because ECHO is already the delay and SLAP gives one repeat. The keyed twin is the one new mechanism: the rack has no tempo, the KEYED family does. After the amp, where a rig puts a delay, so no Stage 3.

**What and why:** repeats that trail off darker. JANGLE: the ambient arpeggio; a snare: the dub throw; a hat on the grid: a rhythm. CHUG is the honest negative: repeats smear a riff.

**Macros:** echo TIME, REPEAT, TONE, MIX [all 0]; the keyed form's dial is AMT.

**Reuses and DSP:** Echo, Spring, Eq unchanged; `EchoTime.DIVISIONS` and the Keyed door; the keyed form needs a seconds door on Echo.

**Hazards (measured):** the +1.0 s cap holds four repeats of 225 ms, the fourth only 14 dB down, then cuts: at AMT 1, "repeats that stop"; at 0.7 (151 ms, six repeats) it is fine. DELAY sits beside ECHO, SLAP's display word. Keyed: a hat becomes a bar long; `slapback` must stay bit-identical.

**Effort:** S (recipe), M (keyed).

**Gate:** snare and chop: does the trail trail, does the cap chop it? JANGLE beside SLAP. Keyed: do repeats land on the beat?

**Verifiers:** buildable with fixes (0.8): TIME at most 0.55 and REPEAT at most 0.7, or own the 0.7 sound; a separate division dial. Redundant as a free recipe, new as the keyed door. Worth 4: both audiences, correct after the amp; the cap and the ECHO word keep it off 5.

### 3. VIBRATO (section, saved key `vibrato`), worth 4

**Chip and key:** VIBRATO on the PEDALS row. Recipe `vibratoed` = {DEPTH 1.0} (the verifier drops LATE from it).

**Kind and slot:** a section, because the rack has no pitch vibrato: ENSEMBLE is a chorus, TAPE's wobble is random wow at fixed rates, the staged TREMOLO moves level. Between TAPE and ENSEMBLE, unpinned, after the amp where a Magnatone's vibrato lives, so right on guitars before Stage 3.

**What and why:** one short delay swept by a sine, wet only, so the pitch sings rather than the level. JANGLE: the Magnatone shimmer a chorus cannot give; a straight choir becomes sung; a chop becomes human. The spec's own table lists vibrato as a gap.

**Macros:** DEPTH 0..60 cents [0], reach-stretched 1.5; RATE 2..9 Hz [0.5]; LATE, how long the vibrato takes to arrive, 0..600 ms [0] (rename ARRIVE).

**Reuses and DSP:** `Dsp.tap`, PHASE's one-LFO stereo rule, the vibrato law inside ENSEMBLE's six-player mode; delay d(t) = A(1 - cos 2 pi f t) times an arrival ramp.

**Hazards:** LATE 0.5 in the recipe makes a snare hear the vibrato only as it dies, so the first drum tap reads as nothing (Stage 0's failure); TREM beside VIBRATO invites the Fender naming confusion; linear interpolation's treble droop, as TAPE has.

**Effort:** S.

**Gate:** does the choir sing rather than sound like a tape machine? JANGLE at the defaults: a Magnatone, or "is it on"? Play TAPE WOBBLE 0.8 beside it. A snare at the recipe.

**Verifiers:** buildable with fixes (0.8): drop the base delay and front trim; hold cents constant across RATE; remove the "FIT floor" clause. New (0.8), but if the gate cannot tell it from TAPE WOBBLE, decline it. Worth 4.

### 4. FADE IN (section, saved key `volume`), worth 4

**Chip and key:** FADE IN (both verifiers say a novice reads VOLUME as louder or quieter; SWELL is row two's word). Recipe `eased` = {RISE 0.8}.

**Kind and slot:** a section, because nothing hides the pick while leaving pitch, tone and length alone: SWELL prepends a stretched head and keeps the hit; GHOST and SMEAR change the tone. After SPIKE, before EQ, unpinned, before the amp on purpose: a swell into DRIVE should bloom and WAH's follower should ride it.

**What and why:** the pad times a raised-cosine rise over the first RISE x 800 ms, capped at 70 percent of the pad. JANGLE: the violin and pedal-steel swell, impossible today. Choir and chops: "fade in" in one tap. The cheapest DSP in the rack.

**Macros:** RISE 0..800 ms [0]; FALL, the last 0..600 ms [0] (dead weight for the chip).

**Reuses and DSP:** WAH's raised-cosine shape, the power fade in `Treatments.endFaded`, RING's peak match; a per-frame gain, about 70 lines.

**Hazards:** the peak match: a kick whose peak is at frame 0 loses it, and the match lifts the swell and its noise floor by up to 15 to 20 dB (bound it by a test). Drums are the weak half: a crash bows, a kick becomes a quiet hum made loud.

**Effort:** S.

**Gate:** JANGLE: a pedal-steel swell with no pick? With DRIVE after it, a bloom? Different from SWELL side by side? Hiss? First the free listen: the BLOOM board exists today.

**Verifiers:** buildable with fixes (0.85): slot is spike, volume, eq; cap the lift. New (0.85). Worth 4: both audiences at effort S; the chip word and the drum case hold it off 5.

### 5. CHOKE (section, saved key `hush`), worth 4

**Chip and key:** CHOKE (the idea said HUSH, which reads as "softer"; CHOKE is what drummers call a sound cut short; GATE is taken). Recipe `hushed` = {FLOOR 0.8}.

**Kind and slot:** a section, because nothing closes on level: SQUASH lifts, the pre-amp fade is fixed in time, keyed GATE is a tempo chop. After CRUNCH, the worth verifier's pick: it leaves the pinned valve order and Stage 3 alone, and pad-sheet taps bake in tap order anyway.

**What and why:** a downward expander on the pad's own envelope: when the level falls FLOOR dB under the pad's peak it closes, with a hold and a release. CHUG through DRIVE stops dead between notes (djent is a gate). The gated 80s snare. The Koala user's kitchen clap: end it where the hit ends.

**Macros:** FLOOR, 60 dB under at 0 to 24 dB under at 1 [0], reach-stretched; CHOKE (rename CLOSE), release 300 to 25 ms [0.5].

**Reuses and DSP:** SQUASH's linked detector (copy, 96 lines), `Dsp.OnePole`, RING's peak match; a knee, a hold, hysteresis.

**Hazards:** chatter on a tail hovering at the threshold (the choir); a late-peaking pad gates its own quiet head; a hat at FLOOR 1 is a stub, which is the pedal. "Fixes DRIVE's hot end" is a quarter true: a pad cut mid-body still ends hot.

**Effort:** M.

**Gate:** CHUG through DRIVE: stops dead, no chatter? A snare: gated 80s snare, or a snare missing its tail? A WASHed snare: gated reverb?

**Verifiers:** buildable with fixes (0.8): a true bypass at FLOOR 0; the close must reach zero; no CLI dial or refusal door on a rack section. New (0.8). Worth 4. Reopens "noise gate" with a strong reason; amend that line to "a hum-removal gate".

### 6. REWIND (section, saved key `rewind`), worth 4

**Chip and key:** chip-less until its gate (then REWIND, or BACKFIRE if too close to REVERSE). Recipe `rewound`. A keyed form on the kit's division is noted for later.

**Kind and slot:** a section, because nothing plays a delayed copy backwards: FLIP reverses the whole pad and loses the hit; SWELL puts a reversed head before it. Between ECHO and SPRING, unpinned, after the amp where a reverse delay lives, so no Stage 3.

**What and why:** the hit plays forward; one window later its last window comes back reversed, feeding back so repeats alternate. JANGLE: the backwards-guitar lead. A snare: the reverse-snare swell that today needs a second pad and a hand-reversed copy.

**Macros:** MIX [0], reach-stretched; TIME, the window, 120..700 ms [0]; REPEAT, feedback 0..0.6 [0].

**Reuses and DSP:** ECHO's feedback bus and darkening, the reverse frame loop, `Dsp.fadeTail`; windows of the bus written out backwards one window later, two overlapping phases under raised cosines so seams do not click.

**Hazards (measured):** with windows anchored at the start, one repeat at TIME 700 ms reaches 1.4 s past the pad's end, past the +1.0 s cap: the headline setting is cut mid-rush. Fix: anchor the grid to the pad's end. A third tail extender (the KDoc says two).

**Effort:** M.

**Gate:** one JANGLE note: the backwards guitar swelling in after it, or a reversed copy glued on with a click? A snare: trippy or a mess?

**Verifiers:** buildable with fixes (0.8). New (0.8). Worth 4: both audiences, known from records, correct after the amp today; the owner never asked, and it competes with FUZZ for the last chip.

### 7. PUMP (keyed treatment `pumped`), worth 4

**Chip and key:** PUMP on the KEYED row.

**Kind and slot:** keyed, because it is tempo-locked and the KEYED family is where tempo lives; GATE is its twin (same grid, same refusal). Not a rack section, so no PEDALS chip and no pinned test.

**What and why:** the level dips hard on every beat after the hit and breathes back along a compressor's release; the first hit stays untouched. "Sidechain" is the one word every bedroom producer knows and the Koala user cannot route one. GATE chops square; PUMP breathes. JANGLE's held chord at 1/4 is a known modern sound, and ducking belongs after the amp anyway.

**Macros:** none of its own; AMT is the depth (keyed treatments have no knobs, a correction to the idea). PUMP wants a 1/4 default where GATE has 1/8, which needs its own field.

**Reuses and DSP:** `Gate.chop`'s loop, refusal and peak match line for line; `Wobble.periodSec`; a 5 ms raised-cosine into the dip, an exponential recovery to a floor, one gain across both channels.

**Hazards:** a pad shorter than two divisions is refused (1.3 s at 1/4 and 92 BPM), so every drum one-shot is out. At AMT 0.7 the dip is about 10 dB; the AMT 1 floor of 24 dB may not be extreme enough.

**Effort:** S.

**Gate:** the choir at 1/4: pumps like a club record, first hit untouched? JANGLE: breathing without clicks? Clearly not GATE, not a tremolo?

**Verifiers:** buildable with fixes (0.85): write the floor into the gain law; add the ramp. New (0.85), but if the owner hears "GATE with a softer shape", a SHAPE weight on GATE is cheaper. Worth 4: a first-day tap on long pads, second-day on guitars.

### 8. ROTARY (section, saved key `rotary`), worth 4

**Chip and key:** ROTARY on the PEDALS row. Recipe `spun` = {SPIN 1.0, RAMP 0.6}. ROTOR is a preset word; ROTARY is clean.

**Kind and slot:** a section, because three things are missing at once: a band split at 800 Hz, level and pitch modulation together at two linked rates, and a rate that accelerates. After the staged TREMOLO, before ECHO (re-anchored from "after VIBRATO", which is not staged). After the amp, where a Leslie lives, so right on guitars today.

**What and why:** the horn (above 800 Hz) gets amplitude and Doppler at the fast rate, the drum (below) the same, slower and lagging; RAMP starts slow and spins up. JANGLE and CHUG: the organ-amp guitar. A choir: instant church. Drums: a short swirl at best.

**Macros:** SPIN [0], reach-stretched; SPEED 0.8..7 Hz [0.5]; RAMP, steady to chorale-then-accelerate over 1.5 s [0].

**Reuses and DSP:** the rotor already inside the ORGAN engine (`Tonewheel.kt`), `Dsp.TptSvf`, `Dsp.tap`, RING's peak match; phase accumulators per band so acceleration cannot click. Copy, do not refactor: the organ's renders are pinned byte-stable.

**Hazards:** the two bands read at different delays, so the crossover no longer sums flat (the sketch understates this); low-band AM at 6 Hz pumps a kick (cap it); CHUG may mush. Mono by design.

**Effort:** upper M, about 150 lines.

**Gate:** the choir with RAMP: slow, then spins up like a Leslie flipped to fast? Does fast sound like a spinning speaker rather than TREM and VIBRATO at once? CHUG: Badge, or mush?

**Verifiers:** buildable with fixes (0.8). New (0.8). Worth 4: strong on anything that sustains, thin on hits; drop the ORGAN alias.

### 9. SHIMMER (keyed treatment `shimmered`), worth 4

**Chip and key:** SHIMMER on the KEYED row (preferred to HALO; a keyed name is not a section key, so the preset names that already use the word SHIMMER are untouched).

**Kind and slot:** keyed, for ETERNAL's reason: its tail (1 to 6 s) is the point and the rack's one-second cap would cut it. It reads nothing of the kit.

**What and why:** the pad feeds a loop of delay, octave-up and low-pass with feedback, so each pass rises an octave and darkens; SPRING diffuses the loop's output under the dry. JANGLE: the ambient and worship pad in one tap, and a shimmer sits after the amp in a real rig, so it is right today. Choir and chop: the "make it heavenly" button. Drums: a pitched ring rather than a wash.

**Macros:** AMT alone: tail 1..6 s and wet level together.

**Reuses and DSP:** ECHO's feedback bus, `Spring.process` as diffusion, ETERNAL's door; a 110 ms loop with gain set to reach -60 dB at the tail time, inside it a two-head octave-up shifter written here in about 45 lines rather than waiting for SUB.

**Hazards:** runaway if the loop gain drifts (test decay on a sine); octave pile-up in the top; aliasing in the 2x read; the shifter's warble; the keyed door writes ETERNAL's tail law into every keyed recipe, so SHIMMER must own its own.

**Effort:** L.

**Gate:** the choir: a bright organ cloud that keeps climbing and fades clean, in tune with itself, or whistle and pile-up? A snare: a wash, or a ringing pitch? Play BODY plus WASH beside it.

**Verifiers:** buildable with fixes (0.8). New (0.8). Worth 4: both audiences on sustained pads; drums and CHUG are half a fit and the effort is L.

### 10. HOWL (keyed treatment `howled`), worth 4

**Chip and key:** HOWL on the KEYED row (FEEDBACK is a macro name elsewhere; a VELVET preset named HOWL is fine for a keyed name).

**Kind and slot:** keyed, because it reads the pad's pitch and the kit's key, as TUNE and BODY do, and adds up to 3 s of tail.

**What and why:** the amp feeding back into the pickup: as the note dies, one harmonic of its own pitch rises, holds, then fades, snapped to the key. JANGLE and CHUG: the most "rig" sound there is, and fine after the baked amp (feedback is born at the speaker). Any pad: a snare that sings in key, a chop whose end becomes a note. A click is refused.

**Macros:** AMT: how much of the tail the howl takes and how long it holds. CLI dials: HARMONIC (1, 2 or 3; "the twelfth" is 3x) and HOLD.

**Reuses and DSP:** `Pitch.detect`, `Scales.nearestInKey`, BODY's resonator and makeup, the Keyed door; a narrow resonator in a loop whose gain ramps just over one only after the envelope has fallen 12 dB, limited at the pad's peak.

**Hazards:** the loop gain as sketched is about 40x (the verifier's reading of `Dsp.drive`), so it would slam, not grow: rebuild at unity gain. Tamed too far it collapses into BODY with one mode, which is the redundancy test. A chord's pitch may read wrong.

**Effort:** L.

**Gate:** a JANGLE chord: a note rising out of it and holding, rather than a sine fading in? A snare in a keyed kit: in that key? Never past 3 s? A CHUG note, the weak case.

**Verifiers:** buildable with fixes (0.7, the lowest confidence here). New (0.7). Worth 4: the most rig-like idea on the list.

### 11. SUB (section, saved key `octaver`), worth 3

**Chip and key:** SUB (the idea said OCTAVE; SUB is the word a novice uses and names the recipe's direction). Recipes `octaved` = {DOWN 1.0}; `octaveup` CLI only.

**Kind and slot:** a section, because nothing makes a transposed copy at the same length: SPEED halves the speed and doubles the length. Between SPIKE and EQ, before the amp, unpinned.

**What and why:** a copy one octave below mixed under the pad at the same length and timing: an 808 under a kick that follows its drop, a drop-tuned chug, a twelve-string with UP. The sampler's first-day move; the guitar half waits for Stage 3 (after the baked amp a distorted chug warbles, and low E's octave at 41 Hz is inaudible on a phone speaker).

**Macros:** DOWN [0] and UP [0], reach-stretched 1.5.

**Reuses and DSP:** `Dsp.tap`, `Dsp.TptSvf` pre-filters, RING's peak match; two crossfaded read heads on a circular line over a 50 ms window, the kernel shared with SHIMMER. Fallback: the OC-2 flip-flop divider (monophonic, no UP).

**Hazards:** head-cycle flutter, most audible on the choir and CHUG; a 30 ms rim doubles into a flam; the sub raises the peak so the match pulls the dry down up to 6 dB, so on a phone speaker more AMT reads as "quieter".

**Effort:** M.

**Gate:** a dry kick at DOWN 1: an 808 under it following its drop, the click still in front? CHUG: the guitar dropped an octave, or a warbling second guitar? The choir with UP: organ or chipmunk?

**Verifiers:** buildable with fixes (0.8): no refusal door on a rack section; keep the output length; drop UP if the fallback is chosen. New (0.8). Worth 3: 4 on drums today, 2 on guitars until Stage 3; reopens "octave" on brainstorm personas, not an owner ask.

### 12. TALK (section, saved key `formant`), worth 3

**Chip and key:** TALK on the PEDALS row. Key `formant`: TALK is in "SMALL TALK", VOWEL in about forty presets. Recipe `talked` = {TALK 1}.

**Kind and slot:** a section, because no section runs more than one swept resonance, and a vowel needs two in a fixed relationship (WAH is one band). Proposed between WAH and SQUASH, which rewrites the WAH placement test; a talk box's real place is after the amp (a horn off the speaker into the mouth), so a post-amp slot is more honest.

**What and why:** at the hit the mouth is closed ("oo"), it opens to "ah" at the pad's loudest moment and closes as the note dies. A choir says a word; a chop is re-vowelled; CHUG's mute goes "wow". No phone sampler has it.

**Macros:** TALK, the move's weight [0], reach-stretched; SPEED, the follower's release [0.5].

**Reuses and DSP:** WAH's envelope follower, `Dsp.TptSvf` band outputs, the vowel table inside the VOX engine; three band-passes driven by the follower, a little dry, peak-matched.

**Hazards (to measure):** three narrow bands drop a pad's energy 15 to 25 dB before the match, so the match lifts the floor: a thin, hissy snare. A 60 ms hat says "t". The follower is the TOUCH move the owner ranked below AUTO, an unproven bet.

**Effort:** M.

**Gate:** the choir: a word, or "a filter was applied"? CHUG: "wow"? A snare: talking or thin? A follower-driven move, or a cycling vowel?

**Verifiers:** buildable with fixes (0.8): own constants, one F2 target. New (0.8), but F2 travels only a third of an octave, so the gate must confirm a mouth, not a wah with company; if not, fold it into WAH as a fourth weight. Worth 3: build after WAH passes.

### 13. FRONT OF AMP (the chain: the spec's Stage 3), worth 4

**What it is:** no chip, no knob. Three halves, each gateable alone. UNDER: a chip on a synth pad keeps the pre-chip recipe as an `under` key, so NONE restores it and the pad still knows it is a JANGLE (today NONE nulls the recipe, so DO IT AGAIN, BREED and MUTATE lose the engine; verified in `KitBuilder.kt`). DRY SEND: REAMP sends the dry string out of the jack and lands the return through the pad's own landing amp, so a real wah sits before the amp with no new render path. The composition rule: a chip on a pad that still holds a patch composes the pedal's sections into the landing chain in rack order and re-renders from the patch; a lone DRIVE merges into the landing amp's own DRIVE (the amp turned up, not a second amp).

**Why:** the owner's clearest ask (WAH on JANGLE the rig way) cannot ship as a chip that works on guitars without it; COMPRESSOR, FUZZ, SUB, FADE IN and TALK wait on it. The any-pad audience hears nothing, by design.

**Reuses:** the rig loop the audition generator already runs, `FxChain.withSection`, `PadRecipe.render`, OUTSIDE's alignment.

**Hazards:** the valve collision rule must be written down; the pre-amp end fade has no door on the re-render path and must be re-measured at DRIVE 1.0 on CHUG's shortest string; ghost velocity layers; re-render time on the phone.

**Effort:** UNDER S, DRY SEND M, composition L.

**Gate:** JANGLE: tap WAH, then NONE: exactly the pad you started with, still a JANGLE? CHUG: tap DRIVE: the amp turned up? A real wah: baked REAMP against the dry send: which is the rig you preferred on 10-12?

**Verifiers:** buildable with fixes (0.8): use the pad's own recipe chain as the base, so every engine with a patch qualifies. New (0.8). Worth 4, and "the single highest-value item" for the guitar audience; a 5 needs both.

### 14. MERGE (the chain: a second chip joins the first), worth 4

**What it is:** today a second chip on a chipped pad silently throws the first away (the sheet restores the original, then applies the new chip alone). MERGE keeps the restore and makes the pad's chips a list of members, each with its own AMT, folded into one chain in rack order and re-rendered from the bin's original. DRIVE then WAH renders wah, squash, valve whichever was tapped first; both chips light; one pedal can be turned off alone; a pad copied to another slot would carry its board with it.

**Why:** a row of six pedal chips promises more than one at a time (a pedal board; Koala's FX page), and the WAH gate's own listen (DRIVE then WAH on a snare) will trip over the silent replace. Nothing new to learn: a second lit chip is the most legible thing possible. On guitars it is bounded by Stage 3, but its `board` recipe shape is what Stage 3's composition needs, so design it once, in that spec.

**Reuses:** `FxChain.withSection` (a fifteen-line fold), `Treatments.chain`, the rewrite-every-file door.

**Hazards:** the pre-amp fade must read the merged chain's DRIVE; a bank-B stack must never be re-folded; the AMT stepper has an ambiguous target when two chips are lit (show which chip it moves); UNPLUG as a long-press is a hold with no tap twin, the sheet's known accessibility gap. Fix: tapping a lit chip turns it off; NONE clears the board.

**Effort:** L across seven pieces; MERGE plus tap-to-toggle alone is the first-day value.

**Gate:** snare: DRIVE at 70, then WAH at 100: both lit, and the quack gets dirty rather than the drive vanishing? Tap WAH off: the drive exactly as it was? NONE: the clean snare in one tap?

**Verifiers:** buildable with fixes (0.75). New (0.75). Worth 4: a product defect dressed as a missing feature.

### 15. SHORT PAD (the chain: laws for the hat and the click), worth 3

**What it is:** four pieces, no chip. FIT: a free-running LFO gets a floor of one cycle per pad (on an 85 ms hat WAH AUTO at 2 Hz completes 0.16 of a cycle, a dull band-pass; with FIT, one full wah). SOFT END: a pad that came in quiet-ended and goes out hot gets its last 5 ms faded. REFUSAL: a section can say in words why it will not do what its name says, the house style of ROLL, GATE and ETERNAL. SHORT SET: a hat, a rim, a click and a snare trimmed to 60 ms on every gate's table.

**Why:** a kit is mostly hats, rims and trimmed hits under 150 ms, and today every gate's shortest source is a 333 ms snare, so the failure has never been in front of the owner. Guitars get nothing, by contract.

**Hazards:** FIT moves WAH AUTO on the 333 ms snare from 2 Hz to 3 Hz, which may change the owner's "right on the snare" verdict, so WAH's gate should hear FIT on and off; FIT as sketched also shortens the TOUCH move's release (apply it to AUTO only); SOFT END can break the test pinning the valve-only chain to VALVE; REFUSAL has nothing to say until a section that assumes a note exists.

**Effort:** M in all; the SHORT SET alone is a list change.

**Gate:** the hat and the rim through WAH, TREM, FLANGE: does each do what its name says? The trimmed snare through DRIVE at AMT 1: the end click gone, the hit the same?

**Verifiers:** buildable with fixes (0.8). New (0.8). Worth 3: a correct quality law, not a pedal; split it, and land the SHORT SET now, since it costs nothing.

## 4. Boards

No board survived as its own idea; three live in other ideas' pockets and three were set aside (section 5) with a path back.

- **`spaced` (DELAY's board form):** echo into a small spring with a little bass kept, the dub snare. CLI and saved kits only; there is no row for it, and DUBWISE as a chip word sits too close to DUB on the CHARACTER row. Hazard: the spring eats the echo's share of the one-second cap.
- **BOOM (DIVE plus SUB plus bass plus squash, "make this a kick"):** a recipe line once DIVE and SUB both pass; carried as DIVE's flagship use.
- **BLOOM (swell + DRIVE + spring):** exists today with no code; the free first listen for FADE IN.
- **The octave fuzz (SUB's UP into DRIVE)** and **ORGAN (SUB plus ROTARY):** recipe lines once their sections pass; both stack two shifters' flutter.
- **Set aside but not dead:** RIG as a chip-less `garage` character now and Stage 3's first board later; METAL after CHOKE exists; WALL (the SHOEGAZE half) as one recipe with its values pushed to the top; SLOWED on the TRANSPORT row's free chips, where it belongs more than on a pedal row.

**How AMT would fade a board.** `Treatments.fade` moves every knob of every section toward its neutral by the same fraction, so a board at AMT 0.7 is 70 percent of itself in every dimension at once. That is coherent (a milder rig) but it repeats the Stage 0 finding: a DRIVE 1.0 inside a board falls to 0.7, gain 11 rather than 1000, on the first tap. The cut FULL TAP idea (clamp the amount so 0.7 is full) was set aside because it leaves the top third of the stepper dead; the alternative, which the SHOEGAZE verifier recommends, is to author each board's values so the 0.7 tap already reads as the board, and to let the pre-amp end fade follow any recipe whose VALVE DRIVE is high rather than only `driven`. That is a question for the owner (section 7).

## 5. Considered and set aside

Every cut idea with its one-line reason. "Redundant" means a verifier found substantially the same sound reachable today; "not buildable" means something it depends on does not exist; "not worth it" means it failed the first-day-tap test.

| Idea | Kind | Reason set aside |
| --- | --- | --- |
| BOARDS ROW | other | Redundant as a mechanism: `punched` is already a two-section board and AMT already fades a whole board; the row's contents were not decided and its DRIVE-carrying rigs fail on guitars before Stage 3. Worth 4; it returns as a SOUNDS row when the sixth PEDALS chip is spent. |
| RIG (GARAGE, BLUES, RIGGED) | board | Redundant: `driven`, `compressed`, `slapback` and `washed` in series; at AMT 0.7 its DRIVE 0.78 falls below the `amped` character. Ship chip-less as `garage` now, give it a chip as Stage 3's first board. |
| BOOST | recipe / section | Not worth it (2): DRIVE with an EQ tilt in front, a chip word that promises a level change the rack forbids; the section form is the staged FUZZ minus its starve. A CLI character at most. |
| FULL TAP | other | Redundant with the stepper (every sound it makes is today's at a higher AMT) and leaves a 30 percent dead zone at the top; its per-member half needs MERGE. The question it asks is carried to section 7. |
| METAL (with TIGHT) | board | Not buildable: its defining half is the gate (CHOKE), which does not exist; its guitar half needs Stage 3. Returns as a recipe after CHOKE passes. |
| SHOEGAZE (with CLEAN) | board | Redundant: DRIVE, ENSEMBLE, SLAP, WASH in four taps; nine knobs diluted at AMT 0.7; no pre-amp fade for a non-`driven` hot valve; no row to live on. Worth 4 as a single pushed WALL recipe; CLEAN is `ensembled` plus a smaller `washed`. |
| SLOWED (with SPED UP) | board | Redundant: PITCH then WASH then the TAPE era, three taps. Worth 4 and the novice already has the word; revisit on the TRANSPORT row's free chips, SLOWED first. |
| CLONE (PEDAL, BEND, PEDAL SHARE) | other | Redundant: OUTSIDE ROOM with the pedal on the jack, KEEP ROOM, then MUTATE ROOM at full wet is already an IR loader. Worth 3; trim to a third OUTSIDE move with a one-line honesty readout if wanted. |
| MUFFLE (with CABLE) | recipe | Redundant: CONTOUR at CUTOFF 0.15 with the sweep off, plus a small spring. Worth 3; a CLI character any time, a chip only if a non-pedal word is wanted on the row. |

Ideas the merge dropped before verification:

| Idea | Reason |
| --- | --- |
| SUSTAIN (novice) | It is the staged Stage 2 COMPRESSOR with a rename; carry the naming point (COMP reads as "compilation" to a novice; `sustain` is a free key) to Stage 2's own brainstorm. |
| HARM TREM | A weight on the staged TREMOLO; cannot be heard until TREMOLO exists. Revisit then. |
| OCTAVE UP (rectifier) | Folded into SUB's UP; the rectifier reopens the spec's "only works on a single note" objection with it standing. |
| HARMONY | Needs SUB's kernel plus pitch detection and key logic; a wrong detection gives a sour third. Returns once SUB passes. |
| CAB (`cabbed`) | One line of VALVE values that darkens; cheap to add from the CLI any time. |
| ORGAN | Needs two unbuilt sections; kept inside ROTARY's note. |
| LOUD (`slam`) | Three-band up-and-down compression; peak-match makes the win RMS only; the staged COMPRESSOR covers half. |
| SCRATCH and BACKSPIN | Striking but narrow; the head they need is DIVE's, so they can follow it. |
| BOUNCE | A third tail extender behind DELAY and REWIND. |
| JACK, STOMP, DAISY | Flavour pieces (dropout stutter, switch thunk, a hum bed) with no claimant on either board; DAISY's key `hum` is taken by six presets. |
| BATTERY, BLOWN | Starve-only fuzz and a torn-speaker rattle; both rewrite the valve pin for a flavour; revisit beside FUZZ. |
| CRASH, RUNAWAY | Cheap macros on SPRING and ECHO (a tank kick, a dub-siren burst) with no chip on a full TIME row; good CLI recipes later. |
| MY BOARD | User-saved boards: a product feature that reopens presets; only after a boards row exists. Deferred, not rejected. |
| FUNK (envelope filter on CONTOUR) | Cut on rank; its follower survives as TALK's and WAH's TOUCH. |
| RADIO | Reopens the sheet's "a telephone band-limit is not a pad treatment" ruling; the most-requested novice vocal effect, revisit when a row has room. |
| TOUCH (+ CLEAN UP), EVERY TAKE, PEDAL ALL, GLUE | KitBuilder doors, not pedals (per-velocity AMT, chain pads through the pedal, a kit-wide door, a mixdown bus board); best decided beside the staged COMPRESSOR. |
| REAL (+ YARDSTICK) | REAMP already exists on the OUTSIDE card; DRY SEND folded into FRONT OF AMP. |
| AMP TREM | A slot argument for the staged TREMOLO (after ECHO, the Fender order); carried as the TREMOLO gate's A/B. |
| GARAGE, BLUES, RIGGED; SPACE, DUBWISE; TIGHT; CLEAN; EASE IN, BLOOM; CHOKE; OCTAVER, DEEP; VIBE; HALO, FEEDBACK, TEETH, PLUNGE, SPED UP, TREBLE; DOTTED; UNPLUG, OWN AMT, COPY BOARD, ONE DOOR, STACKED, UNDER; PEDAL, BEND, PEDAL SHARE; FIT, SOFT END, REFUSAL, SHORT SET | Not cut: aliases and halves folded into RIG, DELAY, METAL, SHOEGAZE/CHOKE, FADE IN, CHOKE, SUB, VIBRATO, SHIMMER/HOWL/TALK/DIVE/SLOWED/BOOST, DELAY, MERGE/FULL TAP/FRONT OF AMP, CLONE and SHORT PAD. |

## 6. A proposed order of work

The spec's stages stand: Stage 0 (built, phone listen pending), Stage 1 WAH (built, gate pending), Stage 2 COMPRESSOR, Stage 3 the board in front of the amp, Stage 4 TREMOLO and FLANGER, Stage 5 FUZZ (deferred). New work slots in as lettered stages between them; none replaces a numbered one. Five pedals first, in this order, because each is small, serves both audiences, and needs nothing from Stage 3: DIVE, DELAY, VIBRATO, FADE IN, CHOKE.

**Stage 1b: listens that cost no code (alongside the WAH gate).** Amend the gate's source list to a guitar pad, a hat, a click, a snare and the choir (SHORT PAD's SHORT SET); render WAH with FIT on and off for the hat and the snare as an A/B inside that gate. Add three zero-DSP listens: the BLOOM board (swell + DRIVE + spring) beside SWELL, for FADE IN; TAPE WOBBLE 0.8 with DRIVE 0 and AGE 0 on JANGLE, for VIBRATO; a `delayed` recipe beside SLAP on a snare, a chop and JANGLE at AMT 0.7 and 1, for DELAY. Gate: the owner's words on each. Why first: three of the five pedals get a yes or no before anyone writes a section.

**Stage 2b: DIVE (after COMPRESSOR, before Stage 3).** The one 5 on the list, effort S, the TRANSPORT row has room, both audiences on day one. Gate: section 3, idea 1. Where it slots: it is the transport, not a pedal in the amp's sense, so it does not care about Stage 3.

**Stage 2c: the small post-amp pedals, one gate.** VIBRATO (section, S) and DELAY (recipe, S), plus FADE IN (section, S) if the BLOOM listen said the pick must go rather than be stretched. All three are chip-less until the gate. The gate is also where the owner spends the sixth PEDALS chip, hearing the claimants side by side (section 7, question 1). Why before Stage 3: VIBRATO and DELAY are right after the amp in a real rig, so they are the first pedals that are correct on guitars the pad-sheet way today.

**Stage 3 (as specified), with the verifiers' order inside it.** UNDER first (S, fixes the shipped NONE-forgets-the-engine defect with no audible change), DRY SEND second (M, a real pedal before the amp through the jack, the owner's own A/B), the composition rule third (L, its own spec and plan as the spec already says). MERGE's `board` recipe shape is designed in the same spec so it is written once; MERGE itself (tap-to-toggle, both chips lit) is Stage 3b, after.

**Stage 4 (as specified), then Stage 4b: CHOKE and ROTARY.** TREMOLO's gate carries the AMP TREM A/B (trem then spring versus spring then trem) and FIT on the hat. Then CHOKE after CRUNCH (M; the spec's out-of-scope line amended to "a hum-removal gate"), and ROTARY after TREMOLO (upper M). CHOKE is the first pedal that forces the second-row decision if the sixth chip went at Stage 2c.

**Parked, with their doors named:** REWIND (chip-less, decide against FUZZ at its gate), PUMP and SHIMMER and HOWL (the KEYED row's two free chips; PUMP first, it is S), SUB (after Stage 3, so the chug half is real), TALK (after WAH passes and only if the owner wants a follower-driven move), the rest of SHORT PAD (SOFT END beside the tail cap; REFUSAL when a section that assumes a note exists).

Every stage runs the house process: a ledger, per-task review, a final review, and an owner listening gate; a section that fails its gate ships without a chip.

## 7. Questions for the owner

1. **The sixth PEDALS chip.** Eight ideas want it. Do you want to hear DELAY, VIBRATO and FADE IN side by side at one gate and pick, or decide now that the row is for the spec's five and everything new goes to a second row?
2. **Two out-of-scope items.** "Noise gate" and "octave" are on your own out-of-scope list. Do CHOKE (a gate as a tone on the chug and the gated snare, not hum removal) and SUB (a sub under a kick, which SPEED cannot do) earn a reopen, and should the spec's line be amended to say why?
3. **Plain word or pedal-aisle word.** VOLUME or FADE IN; HUSH or CHOKE; OCTAVE or SUB; DELAY or TRAIL (because ECHO is already on the TIME row); SHIMMER or HALO. The verifiers lean to the plain word every time except SHIMMER and VIBRATO. Which audience names the chip?
4. **A second tap on a chipped pad.** Today it silently replaces the first chip. Should it add (MERGE), and if so, does tapping a lit chip turn it off, with NONE clearing everything? Is a long-press ever acceptable as the only door?
5. **Stage 3's order and your gear.** UNDER, then DRY SEND, then the composition rule: yes? Do you own a wah or a drive pedal to A/B the dry send against the baked REAMP, which is the cheapest way to hear the rig order?
6. **The gate's table.** Should every gate from now on include a hat, a click and a snare trimmed to 60 ms beside the guitar pad, the snare and the choir? And should the WAH gate hear FIT on and off before WAH's chip joins the row?
7. **The first tap on a board.** Should a board's values be authored so the 0.7 tap already sounds like the board (the recommendation), or should the stepper's 0.7 mean "full" for pedal chips (FULL TAP, which leaves the top third of the stepper doing nothing)?
8. **Which audience goes first for the next build.** DIVE serves both; after it, VIBRATO and DELAY lean to the guitar and the choir, SUB and CHOKE lean to drums. Is the order in section 6 the one you want?

## 8. How to ask for the next step

The repo's process for a pedal is a spec, then a plan with a ledger, per-task review, a final review and the owner listening gate as its last task. Example prompts, in the owner's own words:

1. "Turn DIVE from `docs/superpowers/specs/2026-10-19-pedal-board-brainstorm.md` into a design spec at `docs/superpowers/specs/2026-10-xx-dive-section-design.md`, in the shape of the WAH spec: the slot, the macros and their neutrals, the DSP, the hazards the verifier named (the running sum, the speed-first pin, OVER's neutral), the tests, and the gate's clip list including a rim and a reversed hit. Then write the plan with a ledger and per-task review, and make the listening gate the last task."
2. "Set up the Stage 1b listens: add a hat, a click and a 60 ms trimmed snare to the WAH audition generator, render WAH with the one-cycle floor on and off, and render the three zero-DSP listens (BLOOM, TAPE WOBBLE, a `delayed` recipe beside SLAP). Give me one page per listen with the questions I answer in plain words. No new sections."
3. "Write the Stage 3 spec for the board in front of the amp, using FRONT OF AMP from the brainstorm: UNDER first, DRY SEND second, the composition rule third, with the valve collision rule and the pre-amp fade's new door decided in the spec. Design MERGE's `board` recipe shape in the same spec so it is written once."
4. "Spec VIBRATO and the `delayed` recipe as one Stage 2c, chip-less, with one gate where I pick the sixth PEDALS chip. Carry the verifiers' fixes (recipe DEPTH only, cents constant across RATE, TIME at most 0.55 for DELAY) and play TAPE WOBBLE and SLAP beside them."
5. "Before any code, write a one-page decision note answering the eight questions in section 7 with my answers, and amend the pedal board spec's out-of-scope list where I said yes to a reopen, with the reason stated."
