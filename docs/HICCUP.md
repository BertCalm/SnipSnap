# HICCUP: glitch drums that stumble inside the hit

Status: **round 4, on SYNTH's picker (DRUMS); three kits on the shelf; twenty-one presets in the factory bank; kits heard 2026-11-15, hardware unverified.** The Kotlin engine is `Hiccup.kt` (with `HiccupPatch.kt`), the kits are `HiccupKits.kt`, the presets are `HiccupPresets.kt`. On 2026-11-15 the owner heard the Kotlin kits (see "The listen" below); the presets were not heard one by one and no hardware has played anything. The constants marked "shape" in the spec were not changed by that listen. The engine stays in progress until every applicable owner gate has evidence (see [the gates entry](ENGINE_ACCEPTANCE_GATES.md)).

Design spec, with each round's "As built" section: [`docs/superpowers/specs/2026-11-14-hiccup-glitch-drum-engine-design.md`](superpowers/specs/2026-11-14-hiccup-glitch-drum-engine-design.md). Prototype: [`docs/prototypes/hiccup/`](prototypes/hiccup/). Numbers from the prototype are from Python, not Kotlin.

## What it is

A drum engine for glitch hop, IDM and hyperpop. A clean synthesized hit is written into a short memory. A seeded fault script then re-reads that memory: it snags at a point, repeats a slice, the repeats may shorten until they are a pitch, and every re-read is coarser than the last. Everything is computed. Nothing is sampled.

**Gesture line.** The characteristic gesture is hit → snag → ratchet → buzz → cut.

The point is that the glitch is inside the strike. It follows velocity and changes on each round-robin take, instead of being one effect printed over a pad.

Voices: KICK, SNARE, CLAP, HAT_CLOSED, HAT_OPEN, TOM, BLIP, SQUEAK. Every hit is finite. There is no HOLD, no stereo, no tempo inside `render` (the kits fit their phrases to their own BPM).

## The three units

1. **Source** (`HiccupSource`). One clean hit, computed at 176.4 kHz and peak-normalized. This is the memory. TUNE, DECAY and TOY shape it, and velocity is contact hardness (sweep depth, click level, noise band edges), not level alone.
2. **Script** (`HiccupScript`). Five slots per voice, drawn once from a seed built from the voice name. Each slot has six random draws: whether it exists, where it snags, how long its slice is, how much its repeats shrink, whether it reads backward, whether the read speed dives. The draws never depend on a macro. The macros only scale them, so turning a knob does not reshuffle the pattern.
3. **Reader** (`HiccupReader`). One read-position map over the memory. It plays the hit, pauses at each event, repeats the slice, then continues from the end of the slice, so a fault lengthens the hit. CRUSH is applied here, once per re-read.

The output stage band-limits, decimates, blocks DC, normalizes to 0.95 peak and fades the tail. There is no feedback path, so nothing needs bounding: each output sample is one memory sample times a gain of at most 1, plus at most half a quantizer step. Total length is capped at 1.4 s.

## The macros

Every voice takes the same six. The defaults are TUNE .50, DECAY .50 (closed hat .15, open hat .70), TOY .35, FAULT .45, CHOP .50, CRUSH .35.

| Macro | What it changes, in plain words |
|---|---|
| TUNE | The pitch of the source, one octave either way. It no longer moves the pitch the short repeats buzz at (see the groove rules). |
| DECAY | How long the source rings. A longer memory has more to snag on. |
| TOY | How cheap the source is. At 0 it is a smooth, continuous drum. Turned up, pitch and level move in 60 Hz steps, the wave squares off, and the noise becomes a low-rate console hiss. |
| FAULT | How many stumbles the script is allowed to play, and how deep. Raising it only adds events, so a stumble at FAULT .40 is the one at .30 plus one more. Soft hits stumble less. |
| CHOP | How long each repeated slice is, from a stutter (about 110 ms) down to a pitch (about 1.6 ms). |
| CRUSH | How much each re-read loses: a coarser time hold and fewer amplitude levels on every generation. The first pass is nearly clean, so the attack stays punchy and the damage builds on the repeats. At 0 it is bypassed exactly. |

With FAULT at 0 and CRUSH at 0 the reader's output equals the source bit for bit. The interactions that matter: TOY and CRUSH are two different cheapnesses (the source and the memory), and CRUSH hurts more when FAULT gives it more re-reads.

## Velocity and round robin

The patch carries `velocity`, `take` and `model`, in the SCUFF patch shape. Velocity changes the strike (and FAULT is scaled down for soft hits), so a soft hit differs from a hard one in spectral shape and in how much it stumbles.

Takes are dealt, not wobbled. Take 0 is the plain render. Takes 1 to 4 are dealt from a 4 by 4 Latin square over four dimensions, so no two takes share a setting on any of them:

- the snag position (offsets of minus 12, minus 4, plus 4, plus 12 percent of the dry length);
- the slice length (one step up or down on the grid, see below);
- the shrink of the repeats;
- the strike itself (contact jitter and a new noise seed).

The exist draws never move, so every take has the same events as take 0 at the same FAULT. Four takes are one drum stumbling four ways.

Kits deliver three velocity zones (.30, .65, 1) of four takes each through `ArrangedPad.velocityTakes`. Grid take t is engine take t plus 1. The MPC 2 export keeps each velocity layer's first take only (owner's decision of 2026-11-14), so an MPC 2 plays one take per zone.

## The groove rules (R3, 2026-11-15)

A 160 BPM groove made from the round 2 kit was called incoherent. Four causes were measured in the prototype and fixed in the engine (`Hiccup.MODEL_VERSION` 1 to 2, `HiccupKits.VERSION` 1 to 2):

- **The attack is kept.** An event that starts at the attack is moved so it ends no earlier than 18 ms (`GUARD_SECONDS`), so the first 18 ms play as the dry hit. Measured: the first 15 ms of the stumbled render match the unstumbled one at .994 or better on every voice.
- **One key.** Repeats shorter than 12 ms become a pitch, and every voice snaps that pitch to the same minor pentatonic over 55 Hz (`KEY_HZ`), in any octave. Five drums no longer buzz in five keys. The voice root and TUNE no longer decide the buzz.
- **Stumbles come in whole grid units.** A slice is the CHOP slice at half, one or two times its length, and the stumble time is a whole number of grid units (a unit is the CHOP slice doubled or halved into 40 to 80 ms). Each kit pad's CHOP is a note value at the kit's BPM (`HiccupKits.div`), so the repeats sit on the kit's own grid. At LAB and SUGAR several pads reach the fastest value the engine can make (1/512).
- **A cap.** Total stumble per hit is at most FAULT times a velocity weight times 6 units, and never less than one. The backbone pads (kick, snare, closed hat, clap) also carry a lower FAULT. Measured: the longest backbone pad falls 30 dB below its peak within 4.5 sixteenths at HOP, 4.9 at LAB and 5.6 at SUGAR (limit 6).

Whether this makes the groove coherent to the ear is untested.

## The kits

Three genre kits of sixteen pads, on the shelf under SKINS (FRESH TAPE menu). Each pad is a velocity grid of three zones by four takes. A01 to A14 are hits, A15 is a roll and A16 is a riser. A03 and A04 (closed and open hat) share mute group 1, and the two phrases share mute group 2, so the riser can cut the roll. Kits differ in voices, tuning and roles, not only in effect strength: every pair differs in all 16 pads, with no shared kick or snare.

| Kit | Id | BPM | Direction (a hypothesis) |
|---|---|---:|---|
| HICCUP HOP | `hiccup-hop` | 98 | Glitch hop: a deep kick, long slices, moderate faults, low TOY. The stumble is a swing. |
| HICCUP LAB | `hiccup-lab` | 150 | IDM: slices short enough to be pitches, higher FAULT on snares and percussion, deep CRUSH. |
| HICCUP SUGAR | `hiccup-sugar` | 160 | Hyperpop: high TOY, raised TUNE, short decays, BLIP and SQUEAK forward. |

Pad layout (hit name, voice):

| Pad | HOP | LAB | SUGAR |
|---|---|---|---|
| A01 | Lurch Kick, KICK | Relay Kick, KICK | Candy Kick, KICK |
| A02 | Trip Snare, SNARE | Shard Snare, SNARE | Pop Snare, SNARE |
| A03 | Tick Hat, HAT_CLOSED | Pin Hat, HAT_CLOSED | Sprinkle Hat, HAT_CLOSED |
| A04 | Skid Hat, HAT_OPEN | Spray Hat, HAT_OPEN | Fizz Hat, HAT_OPEN |
| A05 | Stagger Clap, CLAP | Packet Clap, CLAP | Sticker Clap, CLAP |
| A06 | Sag Tom, TOM | Null Tom, TOM | Bounce Tom, TOM |
| A07 | Hop Tom, TOM | Bit Tom, TOM | Boing Tom, TOM |
| A08 | Clean Kick, KICK | Sub Thud, KICK | Donk Kick, KICK |
| A09 | Clean Snare, SNARE | Comb Snare, SNARE | Sparkle Snare, SNARE |
| A10 | Rewind Snare, SNARE | Morse, BLIP | Coin, BLIP |
| A11 | Zip, BLIP | Scan, BLIP | Laser, BLIP |
| A12 | Squeeze Toy, SQUEAK | Chirp, SQUEAK | Squeaky, SQUEAK |
| A13 | Buzz Kick, KICK | Grain Kick, KICK | Giggle, SQUEAK |
| A14 | Dust Hat, HAT_CLOSED | Static, HAT_OPEN | Glitter Clap, CLAP |
| A15 | Trip Roll | Shard Roll | Pop Roll |
| A16 | Stagger Riser | Morse Riser | Coin Riser |

The roll is one bar of the kit's snare, struck on eighths, then sixteenths, then thirty-seconds, with FAULT stepping from 0 up to the pad's value and velocity rising. The riser is one bar of sixteenth strikes with CHOP rising from a stutter toward a pitch, TUNE up a fifth of the knob and velocity rising, so it is the engine's own gesture stretched over a bar. Phrases are classed LOOP and are about two to three seconds long.

The kits are verified in software through save, reload, playback order and MPC 3 export read-back (`HiccupKitsTest`, `HiccupKitTest`, `:shell:generateHiccupKits`). No phone or MPC has played them.

## The listen

On 2026-11-15 the owner listened to an audition page of the Kotlin engine (`Hiccup.MODEL_VERSION` 2, `HiccupKits.VERSION` 2): a four-bar groove from each of the three starter kits (HICCUP HOP, LAB, SUGAR), every hit pad A01 to A14 of each kit at the hardest velocity zone, each kit's roll and riser, and the four round-robin takes of SUGAR's Pop Snare. The owner's verdict, verbatim: "sounds check passes". The 21 presets were not auditioned one by one, and no phone or MPC has played the engine or the kits. The softer velocity zones, the round-robin takes of every other pad and the picker were not on the page. The verdict is on the kits and the engine as heard; it does not say anything about hardware.

## The presets

Twenty-one presets in `HiccupPresets`, at least two on every voice, each the six macros authored from the macro contract, struck at velocity 1, take 0 (the plain render the picker also plays). After the owner's listen of 2026-11-15 they were filed in the factory bank (`Presets.all()`, as SCUFF's were), so the picker shows them as preset chips and `PatchTypes.PROTOTYPES` no longer lists HICCUP. That listen covered the kits, not these presets: they were not auditioned one by one, and nobody has said they sound good.

| Voice | Presets |
|---|---|
| KICK | Clean Thump, Lurch, Buzz Kick, Candy Donk |
| SNARE | Dry Crack, Trip, Rewind, Sparkle |
| CLAP | Stagger, Glitter |
| HAT_CLOSED | Tick, Dust |
| HAT_OPEN | Skid, Static |
| TOM | Sag, Boing |
| BLIP | Coin, Morse, Laser |
| SQUEAK | Squeeze Toy, Giggle |

## Known limits

- **The classifier files some hits differently.** Dry, every voice files as its own class. At the default CRUSH the hats file as SNARE, because the first re-read's hold folds the memory's remaining content into the low band and takes the high-band share under the hat rule. A kick or tom with the default FAULT (a stumbled one) files PERC. Kits and the picker assign a pad's class by voice, so this does not change what a pad is called. The assertion in `HiccupTest` is narrower than the voices: it holds each drum voice dry, KICK, SNARE, CLAP and TOM at FAULT 0 with default TOY and CRUSH, and both hats at FAULT 0 and CRUSH 0.
- **The aliasing fold probe is inconclusive.** The spec asks for a crushed render at 8 times and 4 times to agree after the output stage within minus 40 dB. Measured at FAULT .8 and CRUSH 1 the difference was between minus 20.5 dB and minus 2.2 dB depending on the voice, so nothing is asserted beyond finiteness. The two renders also differ by up to half an internal sample in event position and hold phase, which this measure does not remove, so it overstates the fold. How much was not separated. A crushed render is broadband by design, which is why the house near-Nyquist probe is applied to the dry source only.
- **MPC 2 export keeps one take per velocity layer.** It is the format's limit, not the engine's.
- **Repeat counts are not strictly monotone in CHOP.** The budget jumps by an octave with the grid unit, so the test allows a fall of up to 15 percent between CHOP steps.
- **Takes may be too wide for one drum.** Whether the four strata read as one drum stumbling four ways is untested.
- **Heard so far:** the Python prototype clips and, on 2026-11-15, the page described under "The listen". The presets were not heard one by one and no hardware has played the kits.

## Why it is built this way

- **The script is drawn once and the knobs only scale it.** If the draws depended on the knob values, a small turn of FAULT would give a different stutter. With them fixed, FAULT at 40 is FAULT at 30 plus one more event, so the knob can be learned.
- **CRUSH is tied to re-reads, not laid over the pad.** The first pass is nearly clean, so the attack stays punchy and the damage builds on the repeats. Without that it is the rack's crunch section, which already exists.
- **Short repeats snap to one key.** Below about 12 ms the ear stops hearing repeats and hears a pitch. Left to chance the pitch is out of tune, and a separate key per voice made five drums buzz in five keys. One pentatonic key over 55 Hz makes the buzz something a kit can sit in.
- **Stumbles are counted in grid units.** Random slice lengths and millisecond stumble times landed nowhere in a bar. Whole units tied to each pad's CHOP, and a cap on the total, let a hit stay close to the sixteenth it was played on.
- **Takes are dealt, and the exist draws are frozen across them.** Round robin should be distinct strikes, but a glitch that changes too much per take stops being one drum. The listen decides whether the four strata are too wide.

## Gates

See [ENGINE_ACCEPTANCE_GATES.md](ENGINE_ACCEPTANCE_GATES.md#hiccup-2026-11-14-round-1-engine-round-2-kits-round-3-presets-docs-and-picker-round-4-roster-kits-heard-2026-11-15). In short: rendering, saving, playback and MPC 3 export are verified in software, and the kits were heard on 2026-11-15; hardware playback and the picker's on-device test are open, and the presets have not been heard one by one.
