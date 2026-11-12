# CYPHER

Hip-hop drum kits for the FRESH TAPE shelf, and THUMP's new SUB voice, the sliding sub drum they needed.

The family's gesture is **a genre's kick and snare → the space or converter its era printed them through →
the hats' grid (swung, straight, or rolled) → the phrase that proves the kit is that genre**.

CYPHER is a kit family in the GATECRASH shape, not a Patch engine: sixteen pads per kit (fourteen hits, then
two phrases), three velocity zones of four takes on every pad, source provenance on every pad, saved with the
version that printed it. The hits come from THUMP, SKIN, ONSET and RIVET as those engines stand. What is new
is one sound the roster lacked, a sub drum whose pitch holds its root and then glides to another note, and
three small things on the kit side: a phrase note may be early or late by an authored number of
milliseconds, a note may carry a pitch offset (a hat roll that rises), and the strike helpers GATECRASH kept
privately now live in one place (`KitStrikes`) for both families.

The [engineering specification](superpowers/specs/2026-11-12-cypher-hiphop-drum-kits.md) has the six kits'
design, the Python prototype's numbers, and what changed while building.

**Status: round 1 built, not heard.** The owner chose the family on 2026-11-12 ("I want to build this"),
asked for the sub as an editable THUMP voice, and chose TRAP and DRILL first. Both kits are on the shelf, with
their velocity and round-robin grid proven through save, playback and MPC 3 export in software. Nothing has
been played on a phone or an MPC, and the owner has not yet heard round 1. The
[listening page](https://claude.ai/artifact/ARC6e7nxteRTuAmiocntHG) is private to the owner's account.

## THUMP SUB

`ThumpVoice.SUB`: TUNE (E1 to E2), DECAY (0.25 to 1.2 s to −60 dB), SLIDE (an octave down to an octave up,
none at .5), WHEN (where the slide starts, 0.15 to 0.75 of the decay), DRIVE (a tanh at 4×, gain 1 to 6) and
CLICK. No PUNCH: the clipper is the only shaper it wants. The pitch law is one function of time integrated
into phase, never a retune of a ringing resonator, so a slide cannot click. It reads velocity (drive, click and
the onset settle follow it; the root never moves) and takes (four takes deal settle, click, drive, slide start
and glide time out so no two share a setting; the root never moves). It re-strikes through `Restrike`, so a
pad of it gets fresh takes from the app's robin. No factory presets until the owner's listening verdict
(`PatchTypes.PROTOTYPE_VOICES`, like SPARK); the kits carry their own authored settings.

Why it is new: THUMP's kick only falls onto its root in the first tens of milliseconds and its tail is capped
so it keeps reading as a kick; FATHOM's GLIDE slides into the note from below; the SUB pedal is an octave-down
copy with no pitch move.

The classifier files a sub with a long decay as TONAL (its decay rule is 500 ms); the default reads KICK.
The kits author a sub pad's class as KICK, and the test records the classifier's reading beside it rather
than hiding it. No DECAY makes a LOOP: the longest render is under the 1.5 s loop floor by construction.

## The two kits

| Kit | Tempo | What it is | Print |
|---|---|---|---|
| CYPHER TRAP | 140 | A short punch over four subs (two roots, a slide down a fourth, a slide up an octave), a piercing snare, a 40 ms hat, a clap, a rim, a bell, a zap, two shakers | Dry throughout: precision is the print |
| CYPHER DRILL | 142 | A kick over four subs (a root, a slide down a fifth, a slide up, a late slide down a fourth), an ONSET snare and a clap in one dark big room, a 47 ms hat, two RIVET metal hits in the same room, a dry RIVET snare, a rim, a shaker | `Gatecrash` STONE: a large hard room, half crushed, open 140 ms, closing over 60 ms, 3 dB under the drum (6 under the metal), low-passed at 3 kHz |

Each kit's A15 and A16 are phrases. TRAP: a hat roll (one bar tightening from eighths to sixteenths to 1/32
triplets to 1/64, rising four semitones across the last beat) and a sub line. DRILL: a triplet snare bar (the
snare on two and the and of three with sixteenth-triplet clap pickups, in the room) and a sub call.

Chokes: hats cut hats (group 1), the two phrases cut each other (group 2), and every sub cuts every other sub
(group 3), on the pads and inside the phrases. That is how sliding subs are played.

The hats obey the ratchet rule from the prototype: a hat whose decay to −60 dB is at most 1.5 times the
shortest period in its kit's roll keeps a 35 dB dip between hits; past four times it is a buzz. TRAP's hat is
40 ms for its 26.8 ms sixty-fourths; DRILL's is 47 ms for its 35 ms triplets. `CypherTest` measures both on
the render, and the dip on the TRAP roll itself.

## Generate the proof and the listening pack

```sh
./gradlew --no-daemon :shell:generateCypherKits
python3 scripts/cypher_page/build_page.py testkit/cypher-kits testkit/cypher-kits/site
```

Output goes to `testkit/cypher-kits/` (ignored by git). For each kit the generator builds all 192 slices,
saves the kit, reloads it and checks it equals the saved one, checks every saved slice and then every
exported slice against its render through the app's own pad resolver (`PadHit.resolve`) over two full
cycles of each zone, exports an MPC 3 track and reads it back (velocity ranges, base slices, cycle lengths,
frame windows), and writes `pad-NN.wav`, `dry-NN.wav` for printed pads, `touch-NN.wav` (all twelve strikes),
`driveNN-NN.wav` for the sub pads at DRIVE 0, 0.7 and 1, and `groove.wav`, two bars dealt the way the app
deals them. The page builder needs `ffmpeg` and writes MP3 copies (its lead-in moves the page's onsets by a
few milliseconds; the kit's timing is sample-accurate).

## What was measured (Kotlin tests, this cloud JVM)

- On every sub pad the root read from the zero crossings 100 ms in spans under 10 cents across all three
  zones and four takes. The slide pads land within 80 cents of 95 percent of their interval after three
  glide time constants (a fourth and a fifth down, an octave up); the straight subs drift under 15 cents.
- Every sub pad has more than 1 percent of its energy between 150 and 400 Hz at the authored DRIVE, and the
  soft zone less than the hard. In `ThumpSubTest`, DRIVE 0 leaks under 1 percent there and DRIVE 1 carries
  more than four times DRIVE 0.
- Every hit is a different strike at the soft zone, not a quieter copy, and duller (cleaner, for a sub).
  Every zone holds four different takes.
- Every printed pad's room carries its authored energy against the dry hit within 0.3 dB and none is over
  +4 dB.
- On the DRILL snare bar the GATECRASH attack key hears one attack per distinct note time, each within 2 ms
  of where the note was written. On every phrase every note is an onset. The key cannot hear a sub landing on
  a ringing sub, which is a limit of the probe.
- The 1/64 section of the TRAP hat roll keeps at least a 20 dB dip between consecutive hits.
- A warm SUB render is about 23 ms for a 0.74 s drum on this JVM; a kit built in 7.5 s (TRAP) and 12.0 s
  (DRILL) in the generator. These are not phone timings.

## Limits, stated plainly

- **Not heard.** Round 1 waits for the owner. Every control value is a shape value.
- **Two of six kits.** BACKROOM, LOWRIDER, DUST and FLINT follow once these two are heard.
- **MPC 3 is the export target that keeps the round robin.** MPC 2's projection keeps only the first take of
  each zone. SFZ and DecentSampler exports were not generated. No MPC has loaded these kits.
- **No hardware.** Nothing was played on a phone or an MPC.
- **Rendered bank.** A kit pad cannot be re-edited as a SYNTH patch; SUB itself can, as a THUMP voice.
  Changing the kits means bumping `CypherKits.VERSION`.
- **Mono.** The brief's wide shaker pair is two tunings in mono.
- **The classifier reads a long sub as TONAL.** The kit says KICK.

## Why it is built this way

- Kits, not an engine with a GENRE knob: a genre is a set of authored choices, and every point between two
  genres is a kit nobody wants. The owner chose this shape for the 1980s kits and approved it.
- The sub is a THUMP voice because the owner asked to tune it in the app. The kits still author their own
  settings, so a later preset verdict cannot move a kit.
- The feel is a table of milliseconds on phrase pads, because `render` receives no tempo and the MPC owns
  its swing. Remove the offsets and every phrase is the same straight grid.
- The subs choke each other on the pads and inside the phrases, because a new slide has to stop the last
  one or the line turns to mud.
- The one risky decision is DRIVE on the sub. A clean sub is correct and inaudible on a phone; a driven one
  is audible and less pure. The page plays every sub at three drives so the owner can choose, as they did for
  the SUB pedal.
