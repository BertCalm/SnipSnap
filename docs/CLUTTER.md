# CLUTTER: design brief and round-0 audition

Status: **round 2, the four genre kits on the shelf, audition approved by the owner (2026-11-14).** Round 0c's
Python renders were accepted by the owner on 2026-11-14 ("All are good", "I like it as is"), and the owner then
approved the Kotlin kits' audition ("I approve the audition", listening page version 8). `Clutter.kt` ports all 22 objects (round 1); `ClutterKits` puts the four accepted genre kits on the shelf
with their velocity and round-robin grids proved through saving, playback and the MPC 3 export (round 2). The
engine stays in progress until every applicable owner gate has evidence (see "Gates" below).

Prototype: [`prototype/clutter/`](../prototype/clutter/) (`clutter_proto.py` is the model, `measures.py` the
house distance measures, `gen_audition.py` renders and measures, `build_page.py` writes the audition page).
Every number below is from that Python harness, not from Kotlin.

Listening page: <https://claude.ai/artifact/6dkeY2rrjNGUvUYvobs3v5> (private to the owner; published
2026-11-13, version 7 for the five kits). It carries a playable 4×4 pad grid with three velocity
layers and four round-robin takes per pad, the kit, velocity ladders, takes, bank B (the roll and the
groove), and every control on every object.

Repository truth: read at `523aacfb` (2026-11-13). APIs named here were grepped: `Modes.ring`,
`Modes.Mode(ratio, gain, t60, pan)`, `Dsp.seedFor`, `Dsp.Noise`, `Room` (`synth/.../Room.kt`: SIZE, WALLS,
DISTANCE), `PadHit.resolve` (`shell/.../PadHit.kt`), `Soot.render(voice, macros, velocity, take, model)` as
the drum-engine shape to copy. `CLUTTER` is not used as a name anywhere in `synth/`, `shell/` or `docs/`
(it appears once in prose in `docs/UAT_2026_09.md`).

## What it is

A drum engine whose voices are everyday objects, modelled rather than sampled: the found-sound kit that
street bucket drummers, kitchen-table beatmakers and junk percussion bands build by hand. The owner asked
for "a found sound style drum kit" on 2026-11-13 and chose synthesized objects over recorded ones.

**The characteristic gesture is: a hand or a spoon hits an everyday object → its body rings → whatever is
loose in it or on it rattles a moment later → the kitchen answers, then it is still.**

Working name CLUTTER (the stuff on the counter). No product or maker is named anywhere.

Sixteen objects, one per pad. The first version had eight objects plus six variations and a roll and a
groove on the pads; the owner's note "A kit is 16 pads" and the choice "16 different objects" replaced them
with eight more objects. The layout is the one `AutoPlace` expects (A01 kick, A02 snare, A03/A04 hats, A05 second kick, A06 clap, A07/A08 second hats, toms, percussion):

| Pad | Object | Class | What rings | What is loose |
|---|---|---|---|---|
| A01 | Bucket | KICK | trapped air (a falling boom) + clamped plate | the wire handle |
| A02 | Rice Box | SNARE | cardboard panel + box air | the rice |
| A03 | Rice Jar | HAT_CLOSED | the glass wall | the contents |
| A04 | Key Ring | HAT_OPEN | keys as free-free bars + the ring | the keys |
| A05 | Suitcase, stomped | KICK | lossy fibreboard lid + a short air thud, no pitch drop | the two latches |
| A06 | Book Slam | CLAP | board cover; pages meeting in taps; an air pop | page flutter |
| A07 | Matchbox, shaken | HAT_CLOSED | the cardboard tray | the matches: a forward stroke and a softer one back |
| A08 | Cutlery Drawer, slammed | HAT_OPEN (crash-like) | the wooden drawer front | 6 to 20 pieces of cutlery piling into the front |
| A09 | Tub, lid on | TOM | the plastic lid over a box of air | an unclipped corner of the lid |
| A10 | Frying Pan | TOM | heavy free circular plate in beating pairs + the handle | the handle's loose rivet, which buzzes while the pan swings past its play |
| A11 | Knuckle (table) | PERC | dense wooden plate + bone | a fork and a spoon |
| A12 | Saucepan | PERC | thin-ring steel modes + the base | the lid |
| A13 | Bottle | PERC | thin-ring glass + the air inside | rocking on its base |
| A14 | Mug, tapped with a spoon | PERC | thick ceramic ring, pairs split wide by the handle | the spoon left inside |
| A15 | Wine Glass, flicked | PERC | thin glass, few partials, 2 s ring | ice cubes |
| A16 | Zipper, pulled | PERC | one click per tooth along a 12 cm pull, cloth rustle | the pull tab at the end |

The roll and the groove moved off the sixteen pads to a second bank; the roll then became a one-shot (B01 Bin Lid).

## Why it earns a pad (sibling check)

The roster has 95 registered engines at `523aacfb` (`Patches.kt`), and the drum side has grown fast:
THUMP, SKIN, TERRA, ONSET, RIVET, SOOT, SCUFF, BREAKER, FIRED BRONZE, plus the TRIAD and boom-bap kits.

- **RIVET** (industrial drums in a building) is the nearest: metal, drive and a room. Its voices are drum
  roles played on drums. It has no loose parts and no household materials (cardboard, glass, paper).
- **SOOT** (boom bap from physical models: skin knock, papery crack, coil clatter) models real drums and
  a dictaphone. Its coil is a snare's wires, a fixed part of a drum.
- **TERRA BOX** is a cajón: a wooden box with wires. The rice box is cardboard (far lossier) whose buzz
  comes from grains in flight, so it arrives later and only above a threshold.

What none of them do: **parts that only move once the strike is hard enough to lift them.** Rice, a lid, a
handle and cutlery stay put on a soft hit and fly on a hard one, land after their flight time (2 v / g)
and bounce. That turns velocity into a change of what happens, which is exactly the owner's
velocity-as-timbre gate, and it is the sound people mean by "found": things that are not built to be
instruments, and so rattle.

## Signal flow

```
note, velocity, take, macros
        │
        ▼
[contact pulse] area = impulse J (velocity^1.2), width = TOOL (palm 7 ms .. metal 0.2 ms) × velocity^-0.2
        │
        ├──(force @176.4 kHz)──▶ [object body: modal bank, strike-position weights per take] ──────────┐
        │                                                                                              │
        └──▶ lift = (J − threshold) / (1 − threshold), threshold fixed, RATTLE loosens it               │
                 │                                                                                     │
                 ▼                                                                                     │
          [loose parts] grains: land at 2 v0 / g, bounce once │ parts: v(k+1) = e·v(k), rocking pairs   │
                 │                                                                                     │
                 ├──▶ their own modes (grain clicks, lid, handle, keys, fork)  ───────────────────────────┤
                 └──▶ a small kick back into the body (one-way, never re-thrown) ─────────────────────────┤
                                                                                                       ▼
                     bandLimit → decimate ─▶ [MIC: phone tone + kitchen response, cache: MIC] ─▶ DC → level → fade
```

The loose parts are a one-way readout: they are scheduled from the strike and then re-excite the body;
the body never throws them a second time. That keeps the cost fixed. Disclosed, not fixed.

## Macro contract

Five controls plus the strike's velocity and the take. No HOLD: every hit is finite (the SOOT, SCUFF and
RIVET position). TOOL and RATTLE are read per object, because what you hit a jar with and what is loose in
a pot are different things.

| Control | Default | What it changes in the model | Required interaction | Measured 0 vs 1 (weakest object) |
|---|---:|---|---|---|
| TUNE | .50 | the object's size: every mode × 2^(±7/12) (zipper: tooth size) | none | 2.8 dB (STOMP) |
| TOOL | per object | contact width: palm, wood, metal (jar: sand, rice, coins inside; matchbox: shake sharpness; drawer: slam; zipper: pull speed); a metal tool adds its own ring | TOOL × velocity: both shorten the contact | 3.3 dB (BOOK) |
| RATTLE | per object | how much is loose and how loosely: the lift threshold and the part count (pan: the rivet's play) | RATTLE × velocity: the threshold is crossed by the strike | 3.0 dB (JAR) |
| DAMP | per object | a hand on the object: every t60 × 1 .. 0.15, higher modes faster | DAMP × MIC: a damped hit leaves less for the room | 2.7 dB (STOMP) |
| MIC | per object | the phone: 12 cm (proximity lift) to 2.8 m across a 3.6 × 3.0 × 2.5 m tiled kitchen (image sources, first and second order; a 0.32 s tail; capsule band and presence) | MIC × DAMP | 4.4 dB (BINLID) |

The distance is the house measure over the whole render (10 ms frames, third octaves, floored 80 dB under
the loudest). Every control moves every one of the 22 objects by at least 2.7 dB (the stomp's DAMP and TUNE are
the weakest: a heavy floor rings briefly whatever its size or damping).

**Velocity and takes, round 0c.** Velocity shortens a contact by v^−0.5 for a palm or a fingertip down to
v^−0.2 for metal (Hunt-Crossley: soft materials stiffen as they compress, p near 3; hard contacts follow Hertz,
p = 1.5); a utensil rings harder the harder it lands. A take moves the strike point (which modes it reaches),
reshuffles the loose parts, and changes the hand: DAMP ±0.05 and TOOL ±0.04, seeded by the object and the
take, never by velocity, so a soft and a hard hit of one take are the same hand.

## Three directions

1. **Strong (SPLICE + COUPLE): objects with loose parts.** The design above. Each object is a modal body
   with a sourced mode table where one exists (thin-ring flexure n(n²−1)/√(n²+1) for the pot and the
   bottle; clamped and free circular plates for the bucket base and the lid; free-free bars for keys and
   cutlery; a simply supported rectangle for the box), and every object shares one loose-parts
   mechanism. Gesture clarity high; novelty against the roster high (no engine has a lift threshold);
   risk moderate (the rattle can switch on too abruptly); cost one prototype round plus the usual Kotlin
   rounds.
2. **Alternate A (MUTATE presets): a found-sound kit over existing engines.** RIVET at its smallest SIZE,
   TERRA BOX, TINES BLOCK, SKIN hats, through CRUNCH and ROOM. Cheapest, no engine. It cannot do rice,
   glass and air, or anything loose, so velocity stays a brightness change. Novelty low.
3. **Alternate B (COUPLE, riskier): the kitchen as one instrument.** Every object sits on one table, a
   `Modes.Bank` that couples them, so hitting the pot makes the bottle ring and the cutlery jump: each pad
   carries a faint halo of the others. The most "third sound", and the most likely to make sixteen pads
   sound alike (the kit's pads must stay apart). Worth trying later as a TABLE control on Strong, not as
   the engine.

Strong wins because its one new mechanism serves the gate the owner cares about most (velocity changes the
strike) and keeps every pad a different object.

## Round 0: what was built and measured

The audition kit is the sixteen objects above, one per pad. Bank B holds B01 Bin Lid (a one-shot: the owner
replaced the round-0 pot roll with one on 2026-11-13, "Roll becomes one shot, groove can stay") and B02
Kitchen Groove (two bars at 92 BPM; every hit a fresh take; the jar chokes the keys). On the page's pad grid,
A03 chokes A04 and A07 chokes A08 in every kit.

### Round 0c: six more objects and four genre kits

The owner chose four genre kits (2026-11-13): Kitchen Lo-fi, Street Bucket, Junk Boom Bap, Microbeat. The
gate asks for "authored musical choices with distinct roles, balance and character", so the kits do not
share the CLUTTER kit's settings; they draw on six more objects as well (22 in all), defined in
[`prototype/clutter/kits.py`](../prototype/clutter/kits.py):

| Object | Class | What rings | What is loose |
|---|---|---|---|
| STOMP, a heel on a wooden floor | KICK | a 3 × 2 m span of boards on joists, plus the boards' own knock | glasses in the cupboard |
| BAG, a paper bag of air smacked | CLAP | a one-sided pressure pulse (the crack, after DC) and the tear | the torn flaps |
| BINLID, a corrugated metal lid | HAT_OPEN (crash) | 24 plate modes scattered by the ribs; the high cloud fed by a short kick that grows with velocity squared, an **analogy** of a thin plate's nonlinear cascade | the wire bail handle |
| CAN, an empty drinks can flicked | PERC | a very thin aluminium ring plus its air | the ring-pull |
| SWITCH, a wall light switch | HAT_CLOSED | two clicks (the rocker leaves, the spring snaps it over), housing and wall box | a loose cover plate |
| BOARD, a chopping board slapped | SNARE | a thick 40 × 28 cm wooden plate and the air under a cupped palm | one corner rocking on the counter |

| Kit | BPM | Swing | Phone | Character | Soft vs hard, min | Closest takes | Closest two pads |
|---|---:|---:|---|---|---:|---:|---:|
| CLUTTER | 92 | none | 0.31 | the sixteen objects at their defaults | 5.8 dB | 1.9 dB | 10.4 dB |
| KITCHEN LO-FI | 82 | 58% | 0.40, the kitchen in every hit | palms and wooden spoons, damped, warm | 5.8 dB | 1.8 dB | 7.7 dB |
| STREET BUCKET | 120 | none | 0.13, outdoors, close and dry | drumsticks and a ladle, loose, bright | 4.4 dB | 1.2 dB | 5.4 dB |
| JUNK BOOM BAP | 90 | 56% | 0.31 | suitcase and heel knock, bag and book crack, tight hats | 4.3 dB | 1.7 dB | 8.5 dB |
| MICROBEAT | 100 | none | 0.03, almost touching | tiny objects tuned up, damped, clicky | 3.9 dB | 1.2 dB | 8.5 dB |

Between the genre kits no pad is the same object at the same settings. The same pad slot in two kits is
15.1 dB apart on average at the closest pair of kits (Lo-fi and Boom Bap) and 6.0 dB at the closest single slot
(A09, Lo-fi's low tub against Boom Bap's). Each kit has a two-bar groove at its own tempo and swing, every hit
dealing the next take at its own zone.

Round-0c defects the probes caught, all fixed before publishing: the microbeat mug's takes were 0.19 dB apart
and the bin lid's 0.48 dB (damped ringing objects with nothing loose varied too little between takes; fixed by
the wider strike positions and the hand); the microbeat mug and glass moved 1.7 and 2.8 dB from soft to hard
(fixed by the soft-contact law, a spoon left in the mug and an ice cube that settles within 120 ms); RATTLE
moved the bag by 0.1 dB and the bin lid by 0.6 dB, and DAMP the bag by 1.6 dB.

Measured (Python harness, `gen_audition.py` at round 0):

- Deterministic, finite, peak 0.89, DC 0.0 at every zone and take; an unknown control, a NaN control and
  a NaN velocity all fall back to the default render.
- **Velocity changes the strike.** Soft (0.35) against hard (1.0) at matched level, on all 80 pads of the five
  kits: at least 3.9 dB (Microbeat) and up to 25 dB (Rice Box). The rice box's centroid goes 554 Hz soft to
  4.0 kHz hard; the knuckle's 317 Hz to 1.6 kHz as the cutlery joins in; the suitcase's 227 Hz to 727 Hz as
  the latches hop.
- **The bucket's boom falls.** Hard: 78 Hz in the first 30 ms, 64 Hz by 100 to 300 ms. Soft starts at 72 Hz.
- **The suitcase does not drop; the bucket does.** Suitcase 75 Hz early, 79 Hz late; bucket 78 → 64 Hz.
- **Takes are distinct strikes.** On all 80 pads the closest pair of takes is 1.2 dB apart (Street Bucket and
  Microbeat). ETCH's takes that the owner heard as one strike measured 0.5 to 0.8 dB on the unfloored version
  of this measure.
- **Render time.** Python, 960 renders: 35 ms mean per hit, 176 ms the slowest (the cutlery drawer: up to
  twenty pieces, two modes each, for 1.2 s at 176.4 kHz). The MIC response builds in 2 ms and is cached by MIC alone.

Four defects the probes caught during the round, all fixed before publishing: RATTLE did nothing on the
bottle (0.0 dB between 0 and 1: the rocking was never scaled by the control); the key ring took 160 ms
per hit (a full-length buffer per collision, now one convolution); TOOL moved the cutlery drawer by only
0.55 dB (the slam reached the wooden thump, which the cutlery buries; the slam now throws the cutlery too);
and RATTLE moved the zipper by 2.4 dB (the tab's clink was too quiet).

## Round 1: the Kotlin port (2026-11-14)

`synth/.../Clutter.kt` ports all 22 objects with every round-0c constant: the contact law, the lift threshold,
the bounce trains, the modal tables, the take's hand, the phone and the kitchen. `ClutterPatch` saves velocity,
take (0 to 3) and `MODEL_VERSION` 1. Registered in `Patches`, the two `Velocity` arms (a velocity re-strikes the
object) and `SynthRoot` (rootless, as the other drum engines); `PatchTypes.ROSTERLESS` until presets follow the
kits; hand-built entries in `PatchesTest`, `PadRecipeTest` and `SynthRootTest`; a `DeterminismTest` canary
through JSON.

What could not be the same arithmetic, disclosed:
- **Random draws.** Kotlin's `Random` (seeded by `Dsp.seedFor`) in place of numpy's generator, with Box-Muller
  normals and Marsaglia-Tsang gammas. Every strike point, grain and loose part is another realisation of the
  same law, so a Kotlin take is a new take of the accepted object, not a copy of a Python one.
- **Resampling.** The house `Tide.bandLimit` and `Dsp.decimate` in place of the prototype's FIR: a 1 ms
  lag (about 40 samples) and the same pass band.
- **Filters are the same designs.** scipy's `butter(2, band)` is reproduced exactly: the analog prototype moved
  to a band-pass, then the bilinear transform with prewarping. Checked against scipy at five band edges: 0.0 dB
  difference. The second-order low- and high-passes are the RBJ biquad at Q = 1/√2, which is scipy's design.

Measured with `prototype/clutter/compare_kotlin.py` against the Python render, object by object (Python
harness; `./gradlew :synth:generateClutterAudition` writes the Kotlin clips):
- **No brightness bias.** Averaged over each object's four takes, Kotlin's share of energy above 4 kHz is
  0.06 dB under Python's across the 22 objects (median 0.4 dB under).
- **The deterministic objects are the same waveform** in all but where the hand lands: correlation 0.93 to
  0.99 on the bucket, box, pot, bottle, suitcase, tub, glass, stomp, can and board.
- **17 of 22 hard hits sit within the spread of Python's own four takes.** The other five (tub, bag, bin lid,
  can, switch) sit just outside a four-take spread. The switch was checked against 40 Python takes: Kotlin's
  energy above 4 kHz (−10.8 dB) is inside their range (−20 to −8.9 dB), so four takes had simply drawn dark ones.
- The darkest objects first measured brighter in Kotlin by their centroid. That was the measure: the Kotlin
  clips are 16-bit WAVs, and quantising the Python renders the same way closes the tub's gap entirely.

`ClutterTest` holds the probes in Kotlin on all 22 objects:
- finite, peak-matched, DC under 0.005, the object's length plus the MIC tail;
- every take replays byte for byte, and the closest two of four takes are at least 1 dB apart;
- soft (0.35) against hard (1.0) differs by at least 3 dB in shape at matched level;
- every control moves every object by at least 1.5 dB over the whole render, and an unknown control, a NaN
  control or a NaN velocity changes nothing;
- the patch round-trips through JSON and refuses a take, velocity or model it cannot render.
Measured in Kotlin by the comparison script: soft against hard is at least 3.95 dB (the switch), and the closest
two takes of any object are 1.55 dB apart.

Two physical claims are tests too:
- **A soft hit leaves the loose parts lying still:** on the box, pot, bucket, table and mug at velocity 0.3,
  RATTLE 0 and RATTLE 0.5 render byte for byte the same.
- **The pan's rivet only buzzes past its play:** soft, RATTLE changes nothing; hard, it does.
- The bucket's boom falls by more than 10 % from the first 30 ms to the tail.

One claim did not survive as a test. "The suitcase does not glide" was measured as its loudest frequency early
against late, and failed (75 Hz against 48 Hz). The cause is the measure, not the engine: the suitcase's lid
(78 Hz) and its air (52 Hz) fade at different rates, so the loudest one changes with no glide. That half stays a
design statement.

Local test runs need one workaround that is not CLUTTER's: OpenJDK 21.0.12's JIT crashes compiling
`Splay::renderDetailed` (`type.cpp:1283`, the crash `build.gradle.kts` already excludes for Filament and
CAUSTIC). It reproduced on a clean checkout of the base branch, without CLUTTER. CI's Temurin 17 does not hit it.
Local runs here excluded that one method with `-XX:CompileCommand`; nothing in the build changed.

Not heard: no one has listened to the Kotlin renders. Not measured in Kotlin: render time per hit.

## Round 2: the kits on the shelf (2026-11-14)

`synth/.../ClutterKits.kt` holds the four accepted genre kits pad for pad, generated from
`prototype/clutter/kits.py` so no setting was retyped: KITCHEN LO-FI (`clutter-lofi`, 82 BPM), STREET BUCKET
(`clutter-street`, 120), JUNK BOOM BAP (`clutter-boombap`, 90) and MICROBEAT (`clutter-micro`, 100). They are
FRESH TAPE starters under SKINS (`StarterKits`, `StarterShelf`), the shelf now 129 starters.

Each pad is a grid of three velocity zones (0.35, 0.65, 1.0, the zones the accepted audition played) of four
takes, rendered by the engine at that velocity and take; A03 chokes A04 and A07 chokes A08; every pad carries its
provenance (engine, kit version 1, model 1, kit, role, BPM, object, zones, takes). Sixteen one-shots a kit, no
phrases on the pads: the owner chose sixteen objects per kit, and the grooves stay on the listening page.

**Level within a zone is CLUTTER's own law.** The GATECRASH and SCUFF law sets one gain from a zone's first take's
peak and lets the takes share it. Measured on these kits with a scratch probe, that left the takes of granular
objects 6 to 10 dB apart in peak and up to 7 dB in loudness: the peak of a sand jar, a zip or a matchbox is where a
few grains coincided. The accepted audition peak-matched every take, so no take was heard quieter than its
neighbours; but peak-matching a granular take makes the one with no coincidence the loudest. So every take of a
zone is matched in loudness over its first 150 ms, and the zone is set so its highest-peaking take peaks at 0.92.
`ClutterKitsTest` holds the four takes within 0.1 dB.

The gate, in software:
- **Rendering:** `ClutterKitsTest` holds every pad of every kit:
  - the same bytes twice, finite, centred and silent at the end;
  - each zone's takes equally loud, its highest peak at 0.92;
  - a soft zone a different strike in shape, not a quieter copy;
  - four different strikes per zone;
  - the classes, the chokes and the provenance;
  - the four kits four kits: different tempos, no pad the same object at the same settings, every slot different
    in shape across kits;
  - no maker's name;
  - a built kit the same bytes as its pads rendered one by one.
- **Saving, playback, export:** `ClutterKitTest` takes a kick, a hat and the bin lid through saving, reloading,
  `PadHit.resolve` (the zone by velocity, the four takes dealt in order, then repeated) and the MPC 3 exporter,
  read back. `./gradlew :shell:generateClutterKits` does it for every pad of all four kits: "Verified Kitchen Lo-fi:
  16 pads, 192 slices, saved PCM + playback + MPC 3 track", the same for Street Bucket, Junk Boom Bap and
  Microbeat. That is 768 slices, each checked against its render. Each kit builds in 4 to 7 s here.
- **Not exercised:** the MPC 2 export (on the house's other kits it keeps one take per zone), a phone, an MPC.

The listening page's version 8 puts every pad of the four Kotlin kits on the page, read back from the MPC 3 export
through the app's pad player: the hard hit and a strip of all twelve strikes (MP3 listening copies; the WAVs are
the proof). On 2026-11-14 the owner answered: "I approve the audition". Preserve `Clutter.MODEL_VERSION` 1 and
`ClutterKits.VERSION` 1 as the approved baseline. This is a listen to the kits as the app renders and exports them,
not on a phone or an MPC.

## Risks (where it could sound bad)

| Risk | What would show it | Probe or question |
|---|---|---|
| The rattle switches on like a gate, not a physics | a medium hit that is a clean knock and a hard hit that is a different instrument | the velocity ladder; a fourth zone between .65 and 1 |
| Modal objects read as "generic bell" | the pot and the bottle sound like synth bells | the owner's listen; the mode tables are sourced, the damping is shape |
| The kit sounds like a sound-effects library, not drums | pads that are convincing alone and useless in a beat | each kit's groove |
| The kick is too light for a phone or an MPC | 64 Hz boom with little above it | centroid 273 Hz hard; BUCKET TOOL sweep |
| The jar's wall rings like a pitch | a closed hat with a note | JAR TUNE and DAMP sweeps |
| The room is the same on every pad | the kitchen as set dressing | MIC is per object; the Kotlin port should call the shared `Room` |

## Gates (owner, `docs/ENGINE_ACCEPTANCE_GATES.md`)

- **Melodic, keygroup and chord tool:** not applicable. A drum engine.
- **Articulation:** finite hits, no HOLD. Not a plucked string.
- **Percussion (roll or riser):** waived by the owner on 2026-11-14 ("I overrule that gate"). CLUTTER ships no
  roll or riser; B01 stays the Bin Lid one-shot. This is an owner exception, not a pass.
- **Drums (several genre kits):** four, accepted by the owner as authored on 2026-11-14 ("All are good", "I like
  it as is"), on the shelf in Kotlin since round 2, and their Kotlin audition approved ("I approve the audition").
- **Velocity and round robin:** rendering, saving, playback and the MPC 3 export verified in software (rounds 1
  and 2, above). The MPC 2 export, a phone and an MPC: open.

## Next rounds

- **R0b**: done. The owner accepted round 0c without changes on 2026-11-14 and waived the roll gate.
- **R1 Kotlin**: done (below). Two choices differ from what this plan said, both to keep the accepted sound:
  the modal banks keep the prototype's sin(theta)-compensated two-pole loop instead of `Modes.ring` plus a
  peak match per body (which would rebalance every object), and MIC keeps the prototype's kitchen sketch
  instead of the shared `Room` (a different room is a different sound; moving to `Room` is its own listen).
- **R2 kits**: done (above).
- **Next**: hardware (a phone, an MPC) and the MPC 2 export; presets and the roster (CLUTTER stays in
  `PatchTypes.ROSTERLESS` until then); the app picker. The owner approved the Kotlin kits' audition on 2026-11-14.

## Ledger

| Claim | Status | Evidence |
|---|---|---|
| `Modes.ring`, `Modes.Mode`, `Dsp.seedFor`, `Room`, `PadHit.resolve` exist | verified | grep at `523aacfb` |
| CLUTTER is free as an engine name | verified | grep of `synth/`, `shell/`, `docs/` |
| Every render deterministic, finite, bounded | measured | `gen_audition.py` checks |
| Every control moves every object (22 objects, five controls) by at least 2.7 dB | measured | `macro_activity_db` in the manifest |
| Four genre kits differ in objects, settings, tempo and swing; no shared pad | measured | `between` in the manifest |
| The bin lid's brightness is a plate's nonlinear cascade | analogy, not simulated | `binlid` in `clutter_proto.py` |
| The Kotlin port renders the accepted objects | measured (Python harness against Kotlin clips) | `compare_kotlin.py`: no brightness bias; 17 of 22 within Python's own take spread |
| Soft hits leave loose parts still; the rivet needs its play; the bucket's boom falls | verified in Kotlin by test | `ClutterTest` |
| The four genre kits are on the shelf with their grids through save, playback and MPC 3 export | verified in software | `ClutterKitsTest`, `ClutterKitTest`, `generateClutterKits` (768 slices) |
| A zone's takes are equally loud | verified by test | `ClutterKitsTest`: within 0.1 dB over 150 ms |
| The Kotlin kits are the sound | heard, approved | the owner, 2026-11-14: "I approve the audition" (listening page version 8, read back from the MPC 3 export) |
| Soft and hard differ on every pad; nothing rattles below the threshold | measured; by construction | velocity section |
| Thin-ring, plate and bar ratios are the textbook ones | proposed (sourced formulas, shape damping) | `clutter_proto.py` constants |
| The objects and the four genre kits are the sound | heard, accepted | the owner, 2026-11-14: "All are good", "I like it as is" (Python renders, listening page version 7) |

## Why it is built this way

- **Every object is a body plus its loose parts.** The body is what rings when you hit it; the loose parts
  are what make it sound found rather than manufactured. Turn RATTLE to 0 on any object and you hear how
  much of the character they carry.
- **The rattle has a threshold, not a volume knob.** Rice only leaves the panel when the panel accelerates
  harder than gravity holds it down, so soft hits stay clean. That is why a soft and a hard hit differ by
  up to 25 dB in shape at the same level, which is the owner's velocity gate met by physics rather than by
  a filter.
- **TOOL is a contact width with the impulse held constant.** A palm and a spoon that deliver the same push
  ring the low modes the same; only the short contact reaches the high ones. So TOOL brightens without
  getting louder, and a harder hit shortens the contact too (Hertz contact, time ∝ v^−1/5).
- **A take is the hand landing somewhere else.** Each take moves the strike point (which modes it reaches)
  and reshuffles how the loose parts fall, so round robin is four strikes, not four copies.
- **The risky decision** is the one-way coupling: the loose parts never throw the body back into the air.
  It keeps every hit a fixed cost (tens of milliseconds), and the price is that a very hard hit cannot
  start a second chain of rattles. If the listen says the hard hits feel too tidy, that is where to look.
