# BALLAST — a bass that shakes a structure

## App integration and listening status (2026-10-21)

The app integration imports the reviewed bass model from `e1d7485a` (merged #468),
including graduated glass, stronger FRAME/SPAN response, native soft touch and
the bounded structure pickup. Its six voices, presets, patch desk, Spread root
and dry starter are available on the phone path. The glass voice displays as
TILES because GLINT and PRISM are now separate engines; saved identity and seeds
remain GLINT, and both prior labels still decode. Revised listening acceptance
is pending. The sections below retain the original as-built measurements.

The focused audition uses one frozen baseline gain for every touch/component.
Capture `generateBallastComparison -PballastComparisonVersion=before` on the
baseline source first; candidate renders refuse missing calibration. The full
audition now also uses conditioned raw touches before applying shared gain.

## Current performance work, 24 October 2026

The Luthier follow-up retains the existing 4× simulation, bass, contacts and
output stage. `Modes.Bank` can compile a participation vector once and visit
only its nonzero entries during drives and pickups. It retains ascending
accumulation order, so skipping zero weights changes cost without changing the
equations. Ballast also retains one contact callback for the render.

The oscillator, filter and envelope now produce a reusable bass trace. Its key
contains the voice, actual frequency, oscillator ratio, wobble frequency,
lifecycle, gate, DRIVE and velocity. SYMPATHY, SPAN, GLASS and FRAME reuse that
trace because the structure has no return path into the bass source. Every
render still creates fresh frame, string, tile and output state. The cache
retains at most four keys and 24 MiB, publishes private read-only arrays under
a monitor, computes misses outside that monitor, and can reuse a longer
trace's exact prefix. This accelerates structural edits rather than merely
memoizing an unchanged finished sample.

**Measured with actual Kotlin through a Java scratch harness on a JDK 21 Linux
server, without JFR:** ROOT's default one-shot median moved from **230 ms to
165 ms**, and its held median from **491 ms to 283 ms**. These are five-sample
medians after warmup; the candidate mostly reuses its source trace. Seven
successive GLASS edits measured **161 ms** for a one-shot and **279 ms** held.
Clearing the cache before each edit measured **228 ms** and **517 ms**: a held
source miss was slightly slower than the original default held render.

The tens-of-milliseconds target therefore remains open. The expensive modal
simulation is still present on every render; no lower-rate or altered physical
model is being presented as a sound-preserving optimization. These timings are
diagnostics from this server, not phone measurements or a latency guarantee.

All **30** compared output buffers were byte-identical to baseline `b17c3bd`:
the six voices in both lifecycles, plus three extreme/soft cases per voice.
Hashes cover big-endian float32 samples and equal frame counts. New
`ModesSparseTest` and `BallastPerformanceTest` exercise dense/sparse equivalence,
copied weights, cache keys, prefix reuse, memory limits, concurrent requests and
independent output buffers. The report is retained with the scratch harness as
`ballast-perf/fair-performance-report.json`. Nothing in this performance pass is
a new listening verdict; the revised glass and structural balance still await
the owner's audition.


## Held keys (MAKE INSTRUMENT), 24 October 2026

BALLAST is the first bass with a keygroup. MAKE INSTRUMENT on the phone, and
`./gradlew :shell:generateBallastKeys` on the desktop, export thirteen zones a
minor third apart from C1 to C4, the same layout for every voice. Each zone
keeps the note's own onset and the structure settling in front of the loop
BALLAST already exports at the top of HOLD, so a short press plays the attack
and a held key sustains on the settled bass. The loop marker sits where the
settling ends (3.6 s for most voices, up to 5.8 s for WIRE). One velocity
layer, rendered at full touch. RELEASE is the only knob, 0.1 to 1.5 s.

How it is cut. `Ballast.renderHeldNote` runs the same steady simulation and the
same calmer last-resort passes as the loop render, keeps the preroll as the
attack instead of discarding it, and does not rotate the loop to a zero
crossing, so the frame before the marker is the frame the loop's end wraps
back to. The seam is measured on the sample that ships, and a zone that does
not close throws rather than shipping a click. Rotated and levelled like the
exported loop, a zone's loop matches HOLD's own loop to within 1.2e-7. The
one-shot and loop paths are unchanged.

How it is levelled. Each zone is levelled on its first 0.85 s (the shortest
one-shot's gate and release), not on its loop. BALLAST's two oscillators beat
with a phase seeded per note, so a zone's onset sits from about 4 dB under its
loop to 2.5 dB over it. Levelled on the loop, as RESIN's pad is, ROOT's short
notes spread 6.8 dB over the thirteen zones, up to 4.4 dB between neighbours
(RMS over the first half second); levelled on the onset,
they match within 1.5 dB (C1 sits 1.3 dB low because the peak limit pulls it
down), and the loops differ by up to 3.5 dB between neighbours (D#3 to F#3), which is heard
only on a long hold. Through the phone's sample player, a semitone walk from
C1 to C3 on four presets spreads 3.0 to 3.7 dB on the house loudness measure,
with the worst step between neighbouring notes 1.7 dB on the ROOT presets and
3.2 dB on WIRE. Plain RMS steps are larger, about 4 dB, below 120 Hz, where
the house measure deliberately reads quieter.

Measured (desktop, JDK 17): every default ROOT zone closes on the plain
structure with seams under 3e-9; all six voices at C1, F#2 and C4 close, worst
2.1e-4 (DEEP at C1); the four generated instruments' worst zone is 4.8e-4
(LONG STRING). Thirteen zones render in 1.7 to 2.8 s in parallel on the
desktop; the phone renders them one at a time. Tests: `BallastHeldTest`
(synth), `BallastPadMakerTest` (shell), and `BallastSynthScreenTest`
(instrumented; compiled, not yet run on a device or emulator).

Not heard. The three questions in `testkit/ballast-keys/README.txt`: is the
held key the BALLAST the owner liked, with its onset intact; is there a click
or pump each time the loop comes round; does any step between zones jump in
tone. Phone render time is not measured.

## Original as-built record, 4 October 2026

The following measurements and initial listening questions are retained from R1.
They predate the graduated glass, stronger structural pickups and later app
integration. The owner subsequently liked the bass and asked for more gradual
glass, clearer FRAME/SPAN and stronger touch differences. Acceptance of those
revisions remains pending.

**R1 status:** built, awaiting its first audition when this record was written.
**Roadmap:** the Ballast integration status near S24–S26 in `docs/SYNTH_ROADMAP.md`.
**Handoff:** the supplied *Ballast — Engineering Specification* v1.0 (3 October 2026). This document is what was built against it, and the numbers the suite measured. The constants marked "listening" in `Ballast.kt` are first values for that gate, not sourced ones.

## What it is

A deep electronic bass with a direct path and a force path. The force drives a resonant frame. The frame shakes six sympathetic wires, tuned a double octave below the note up to three octaves above it, and six glass tiles that hang inside the frame and knock into each other and into their housing. Each knock rings the tile it hit, and the ring is the energy that knock lost. Stop the bass and the wires, the frame and the last taps settle by themselves. Nothing in the structure has a trigger of its own.

Six voices share that architecture and differ in the bass's contour, which octaves are weighted, how the frame is mounted and how the tiles are set:

| Voice | DRIVE | SYMPATHY | SPAN | GLASS | FRAME | What it is for |
|---|---:|---:|---:|---:|---:|---|
| ROOT | .40 | .25 | .30 | .10 | .35 | the bass, with the structure as a faint body |
| WIRE | .45 | .70 | .45 | .20 | .50 | long wires that keep going after the note |
| GLINT | .40 | .45 | .60 | .65 | .45 | the glass up front |
| DEEP | .45 | .45 | .70 | .15 | .65 | the octaves below, on a soft mount |
| BLOOM | .55 | .65 | .50 | .40 | .70 | a structure that arrives late |
| SWARM | .75 | .65 | .80 | .85 | .65 | everything at once |

TUNE walks C1 to C4 (MIDI 24 to 60) in semitones. HOLD 0 gates the bass for 0.6 s and HOLD just under the loop threshold gates it for 3 s; from 0.99 the render is the held loop. The neutral column is DRIVE .45, SYMPATHY .35, SPAN .40, GLASS .25, FRAME .45, HOLD 0. Output is a mono `Snip` at 44.1 kHz, rendered at 4×, band-limited, decimated, DC-blocked twice and levelled with `Dsp.levelTo` to `Dsp.MELODIC_LOUDNESS_TARGET`.

## Where the handoff and the tree disagreed

The handoff proposed `Strings.Loop` for the wires if it was compatible. It is not. A waveguide loop couples to a frame only through a scattering junction, and the house's reciprocal coupling already exists as springs in `Modes.Bank` (MERCURY's R0), with the stability bounds enforced by `tune` and `connect`. So the wires are modal: ten harmonics each, with a small stiffness (`STRING_STIFFNESS` 4e-5, so a harmonic stays inside a fraction of a percent of its place) and a seeded detune of a few tenths of a cent so coincident modes beat and never lock. A wire at 1/4 of the note therefore has modes at 1/4, 1/2, 3/4 and 1 times the note, and only the last of those is in tune with the bass.

The handoff's actuator has two parts. The built one has three, because the first two did not reach the lower wires (measured: their onset sat around −120 dB, which is silence). The third is below.

The handoff allows a wrap crossfade where tile contacts will not settle to a repeating orbit. The built loop tries three things before it would need one, and on the 72 corners the suite renders none of them was needed, so no crossfade is applied. The fallback is still there and is named below.

`Keys.ballastPad` and the phone's picker were outside R1. The phone path was added
later; a keys instrument remains deferred. HOLD's top step is the held loop inside
`Ballast.render`.

## The bass and the actuator

Two band-limited oscillators, a saw and a pulse, into `Dsp.Ladder`. DRIVE opens the cutoff (about two octaves of travel), narrows the pulse, raises the resonance and the input gain, and — separately — raises the force. The filter also droops over 0.3 s and wobbles by up to 0.45 octaves at 3.1 Hz, so a held note is not a frozen spectrum.

The actuator reads that signal, not a motion LFO:

- **Fast.** The signal, high-passed at 8 Hz and soft-bounded by a tanh, drives the two audio-rate frame modes. This is what the wires at and above the note hear.
- **Slow.** The signal's rectified energy, between 0.8 and 18 Hz, drives the rocking mount. Onsets, releases, the beat of the two oscillators and the filter's wobble move it. The audio cycle does not, so the tiles knock when the bass's energy moves and not once per cycle.
- **The kick.** The same rectified energy, low-passed at 0.7 times the note and high-passed at 2.5 Hz, applied to the wire modes under the note. It is the change of the bass's envelope. A steady tone leaves nothing of it, so it cannot be accused of being a sub oscillator.

Velocity is a render parameter, as on MERCURY's rubbed voices: no knob. A soft touch attacks more slowly (up to 16 ms), opens the filter less and pushes the structure less. `Velocity.atVelocity` calls `Ballast.render` with it. A pad or a preset renders at velocity 1.

## The frame and the wires

Three frame modes. The rocking mount goes from 14 Hz and a 0.10 s t60 at FRAME 0 to 4.5 Hz and 1.3 s at FRAME 1, and a heavier voice rocks slower. The two audio modes sit at inharmonic ratios of the note (about 1.6 and 2.9, softening as FRAME rises) with a t60 from 0.08 s to 1.2 s. Springs join the rocking mode to the audio modes.

Each wire mode is sprung to the audio frame mode nearest it in pitch. The springs' total kappa on each frame mode is SYMPATHY's (0.02 to 0.5, and FRAME scales that 0.7 to 1.25), shared out by the mode's weight, so a node cannot pass `Modes.MAX_NODE_KAPPA`. SYMPATHY 0 keeps the small structural path. SPAN does not retune anything: the root wire's weight is always 1 and the others fall off with their distance in octaves, the falloff widening from about half an octave to between 2.4 and 3 as SPAN rises. A wire's own decay scales with its ratio and its harmonic number; the fundamental of the note's own wire runs from 0.8 s to 8 s of t60 before the voice's own multiplier.

Modes fade in between 14 and 30 Hz and out between 6.5 and 9.5 kHz, and are never made outside 10 Hz to 11 kHz. In a held loop a mode that is not a harmonic of the note has its t60 capped at 1.6 s, so the onset's inharmonic ring dies in the preroll instead of beating against the loop forever.

## The glass

Six tiles, each a mass on its own mount, in the frame's coordinates. The mount goes from 26 Hz (the tile follows the frame) to 3.4 Hz (it stays behind), and GLASS releases the tiles one by one, so the first travel of the knob frees one tile and the last frees all six. Frame acceleration drives them. Neighbours and the two housing walls meet through compression-only springs with dissipative damping, integrated at 44.1 kHz. A contact that is still pressed does nothing audible; when it separates, its damper's loss is what rings the tile.

The ring is an exact energy increment on that tile's three modes: the velocity is raised by the amount that adds `RING_SHARE` (0.3) of the loss, split between the two tiles of a pair and spread over the modes by how long the contact lasted. The glass bank is never driven by anything else, and switching its pickup off leaves the frame and the wires bit-identical, so the ring cannot feed the structure. The readout saturates (`GLASS_KNEE` 0.2) so a dense rattle is loud and not a wall. A tile that would travel past 12 model units is stopped and counted; across the 192 knob corners none was.

GLASS 0 is the wide gap and GLASS 1 is tiles that nearly touch, with the contact stiffening from 700 Hz to 2.4 kHz. Measured on GLINT at DRIVE 0.6 and C2, the knock counts at GLASS 0, .25, .5, .75 and 1 are 0, 9, 25, 85 and 361. So the bottom of the knob is silence and the first travel is where the knocks appear, rather than a faint contact at 0. The handoff preferred a faint contact at 0; the built law spends the bottom of the knob on the gap closing, and the rattle lives in the top half. That is a listening question.

## The held loop

The note is planned so the driven state repeats: at least two seconds, a whole number of periods that is a multiple of four (so the 1/4 wire's harmonics of the note close inside it), the pitch moved by under half a frame to fill whole frames, the second oscillator a whole number of beats from the first, and the filter wobble a whole number of cycles. The structure runs for a preroll of 3 to 6.5 s, the stretch is conditioned, and `Keys.seamError` compares the 256 frames before the loop with the 256 after it.

Contacts are nonlinear, so a corner can fail to settle. The passes, in order: the plain preroll, a preroll 1.6 times longer, then the same with the mounts more damped and the gaps floored wider (`LOOP_GAP_FLOOR` 0.02, so GLASS above roughly .8 is the same gap in a loop), then the contacts opened entirely. The first pass that lands under half the 1e-3 bar wins. On 72 corners (six voices, C1, C2 and C4, four mixes of DRIVE and GLASS) every one closed, the worst seam was 3.88e-4, and none needed a calmer pass. The exported loop is cut at a zero crossing and levelled. A loop that cannot close throws rather than shipping a click.

A one-shot ends when the slowest of the wire, the frame and the glass is about 45 dB down, inside 8 s, and the last portion of that tail fades on a raised cosine.

## What the suite measured

Printed by `BallastTest` and `BallastPresetsTest` on the built engine:

- The bass's own peak sits within 11 cents of the note TUNE names (ROOT, WIRE and DEEP at C1, C2 and C3, read on the direct path). The filter's resonance is what moves it; the wires do not.
- A held ROOT at C2 carries the energy under its note 67.9 dB below the note. There is no sub-sonic component added.
- WIRE and DEEP at C3, sympathy and span full, glass off: the 1/4 wire's onset is 66 to 96 dB over its level four seconds later, and the 1/2 wire 74 to 94 dB over. The note itself in the wires sits around −28 to −31 dB in the same units, 60 dB and more above that leftover. The bass's own direct path has the same gap between its note and its 1/4 and 1/2.
- With the source forced off at 1.5 s, every voice's structure energy falls on every step (worst rise 0.87×, which is a fall) and ends 68 to 80 dB down.
- The ring energy is 0.3000 of the knock loss on every voice, and the frame and wire pickups are sample-identical with the glass pickup switched off.
- GLINT at C2, DRIVE 0.7, GLASS 0.7: 84 knocks with the slow drive, 0 without it, 13 at velocity 0.2. The rocking mode moves more than ten times further with the drive than without.
- With the bass never switched on, SWARM renders digital silence and zero knocks.
- All 48 presets classify LOOP by length and the classifier agrees; none of the standard renders (four notes, six voices) reads as a kick, snare, clap, hat or tom. A preset's filed class matches its rendered length against the classifier's 1.5 s line.
- Onset centroids rise from a soft touch to a hard one on every voice (ROOT 138 to 247 Hz, GLINT 172 to 1867 Hz, SWARM 4603 to 5377 Hz).
- The top of a hardest, highest, widest render (SWARM, GLINT, WIRE at C4, every knob that adds energy at 1) holds the band above 19 kHz between 46 and 58 dB under the rest.
- Eight seconds of WIRE renders in 0.79 s; a held SWARM loop at C1 renders in 0.69 s. The 192 raw knob corners stay finite, worst peak 1.36 before levelling, and no travel stop engaged.
- Identical input is identical output, including a held loop (`DeterminismTest` as well).

## Registration

`BallastPatch` (`ENGINE` `"BALLAST"`) round-trips through `Patches`. `BallastPresets` ships eight per voice, every knob named, TUNE on a semitone. Two of the handoff's suggested names collided with the rack: ECHO is a section, so "Lower Echo" shipped as LOWER REPLY, and SWELL is a section, so the bloom presets shipped as SLOW CLIMB and WARM LIFT. The rest of the suggested set is in the roster under its own words (QUIET GLASS, LONG STRING, UPPER HALO, LOOSE MOUNT, DELAYED OPEN, DENSE TILES, TIGHT BASE, GLASS WAKE, HELD FRAME, WIDE OCTAVES, and HEAVY FRAME for the deep one).

`SynthKits.ballast()` is sixteen dry pads: ROOT up the minor pentatonic from C2, then LONG STRING, UPPER HALO, LOWER REPLY, LOOSE MOUNT, GLASS WAKE, DENSE TILES, DELAYED OPEN, SLOW CLIMB, DENSE RATTLE, TILE STORM, LOW WELL and WARM LIFT. `./gradlew :synth:generateBallastKit` writes the MPC program under `testkit/`; `./gradlew :synth:generateBallastAudition` writes 232 clips, `manifest.json` and the listening page under `testkit/ballast-audition/` (gitignored). The page's STRUCTURE section is the same note with the bass alone, the wires and frame alone, both, everything, and the bass stopped dead at one second.

## Deferred work

The phone picker and roster count have since been integrated. A keys instrument
remains deferred. A held pad is `Ballast.render` with HOLD at 1; a multisampled
instrument still needs its own export and listening gate. The revised audition's
verdict, and any constant that verdict moves, remain open.
