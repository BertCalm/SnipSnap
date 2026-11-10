# GATECRASH

Three 1980s drum kits whose room is crushed until it stands as loud as the drum, then cut off.

The characteristic gesture is **strike → the room rises to meet it → held flat → cut to black**.

GATECRASH is a kit family, not a SYNTH engine: there are no knobs to turn. The owner asked for "the ultimate
80s drum kit, turned to 11" on 2026-11-10 and chose a kit family, these three kits, and fourteen hits plus
two phrases per kit. The hits are SKIN, THUMP and ONSET drums as those engines already make them. What is new
is one treatment stage, `Gatecrash`, which prints each pad through its own space.

**Status: candidate. Measured, not heard.** Every setting is a first guess made without ears. Phone and MPC
hardware playback have not been exercised. The
[engineering specification](superpowers/specs/2026-11-10-gatecrash-gated-drum-kits.md) has the model, the
measurements and what changed while building.

## The three kits

FRESH TAPE → SKINS:

| Kit | Tempo | What it is | How its space is made |
|---|---|---|---|
| GATECRASH STONE ROOM | 96 | Acoustic kick and snares, six concert toms, a dark ride | A large hard room heard from its far end, crushed, then gated by the drum |
| GATECRASH MACHINE POP | 118 | Analog kick, snare and clap, four diving synth toms, cowbell, crash | A burst of reflections that does not decay and then stops. No room, no gate |
| GATECRASH ELECTRO FUNK | 108 | A long dry boom kick, snappy snare and clap, cowbell, block, zap | A small bright room with a 110 ms gate. Four pads are dry |

Each kit has sixteen pads: fourteen hits, then a fill on A15 and a build on A16. The kits differ in their
drums, tunings, pad roles and space mechanism. They are not one kit at three effect strengths.

Every pad has three velocity zones (soft, medium, hard) with four takes in each. A softer zone is a softer
strike: duller, and sent to its space at a lower level, so it is drier too. The zones are levelled to the
same peak, because the app and the MPC both make a softer touch quieter on their own. A take is another
strike of the same drum. Hats choke each other (group 1). The fill and the build choke each other (group 2).

The fill and the build are single recordings of a phrase in one shared room. Every note re-opens the same
gate, so the room stands through the phrase and shuts once, after the last note. Playing the tom pads in the
same rhythm gives each tom its own room, which is a different sound.

## Generate the proof and the listening pack

```sh
./gradlew --no-daemon :shell:generateGatecrashKits
```

Output goes to `testkit/gatecrash-kits/` (ignored by git). For each kit it:

1. builds all 192 slices and saves the kit;
2. reloads it and checks the reloaded kit equals the saved one;
3. checks every saved slice, and then every exported slice, against its render, through the app's own pad
   resolver (`PadHit.resolve`), two full cycles of each velocity zone;
4. exports an MPC 3 track and reads the track back: velocity ranges, base slices, cycle lengths and frame
   windows;
5. writes `pad-NN.wav` (the hard zone's first take, read from the exported file), `dry-NN.wav` (the same
   strike with no space), `touch-NN.wav` (all twelve slices, soft to hard), and `groove.wav`, a two-bar
   pattern dealt the way the app deals it.

The listening page is built from that output:

```sh
python3 scripts/gatecrash_page/build_page.py testkit/gatecrash-kits testkit/gatecrash-kits/site
```

It needs `ffmpeg`. The [published audition](https://claude.ai/artifact/2gJJoipRS6Lw1Ckshh5HE7) is private to
the owner's account. It plays every pad, its dry comparison, all twelve strikes and a groove per kit, and
draws each pad's real level over time.

`metrics.csv` has each pad's measured gesture: the return's level early in the hold, at its end, after the
cut, and its peak against the dry hit. `verification.csv` lists what was checked per pad.

## What was measured (Kotlin tests, this cloud JVM)

- A crushed stone room holds within 3 dB across the hold on a short click. The same room with the crush
  backed off falls at least 5 dB more.
- On the six concert toms the held room moves less than 4 dB from 30 ms to the end of a 340 ms hold, and
  peaks within half a dB of the dry hit.
- The gate takes the room to exact silence within 5 ms of the close time.
- A ringing drum is one attack. The stone fill is twelve, the builds sixteen.
- The soft clip's folded harmonics are under −60 dB.
- All 42 hits are duller at the soft zone. The toms are at least 1.5 dB drier for their level.
- Every take differs from every other take of its zone.

Render time, warm: a hit takes 22 to 105 ms and a phrase 206 to 256 ms. A whole kit (192 renders) built in
9.6 to 13.2 s in the generator. These are not phone timings.

## Limits, stated plainly

- **Not heard.** Nobody has listened to these kits.
- **MPC 3 is the export target that keeps the round robin.** MPC 2's grid projection keeps the velocity zones
  but only the first take of each, so an MPC 2 export does not pass the round-robin gate. SFZ and
  DecentSampler exports were not generated.
- **No hardware.** Nothing here was played on a phone or loaded on an MPC.
- **Rendered bank, not recipes.** A pad cannot be re-edited as a SYNTH patch. Changing the kits means
  bumping `GatecrashKits.VERSION`; saved kits keep playing their own WAVs.
- **Mono.**
- **Kit build time.** A kit is 192 renders, made once when the starter is created. That is seconds, not the
  single-hit budget.
- **The room is the existing ROOM model**, capped at a 0.9 s decay. It is not a model of any real room.

## Why it is built this way

- The compressor is the sound. A gate alone only shortens a room; the crush is what lifts it to the drum's
  level and holds it there.
- The gate listens to the dry drum, not to the room, so a fill can keep one room open and a ringing tom
  cannot hold its own gate open.
- Two mechanisms, because the decade had two: a live room through a compressor, and a digital burst that
  never decays.
- Zones are levelled and strikes are not, so the pad decides how loud and the zone decides how hard.
- The one risky decision is how much less room a soft hit gets. A compressor evens that out, so the amount
  is an authored law rather than physics, and it needs a listen.
