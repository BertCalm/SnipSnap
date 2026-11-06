# SCOUR: an impact that builds (rounds R1 to R6)

SCOUR is a pitched impact engine. A roller spins up against a free metal or glass body while shot
rains on it faster and harder, each shot denting the surface; the dented fraction climbs and the
body's upper partials climb with it, so the build rises. Then one heavy slam lands and the hardened
body rings out. Six bodies take the same blast: PLATE, PANE, SHEET, BEAM, DISC and ROD. Every sound is a
finite one-shot, a pure function of its six controls and the voice, rendered offline.

The design, the Python prototype and every measurement are in
[the spec](superpowers/specs/2026-10-26-scour-engine-engineering-spec.md); its "As built" sections
list each place the Kotlin differs from it and why. SCOUR is on the phone's SYNTH picker, has twenty-four
factory presets and a sixty-four-pad starter kit. The owner has heard every audition up to R5 and
accepted the voices and kit banks in them. DISC, ROD and bank D (R6) are built and not yet heard (see
Listening). On the phone the owner reports SCOUR sounds fine; it has not been heard through an MPC.

## Voices

| Voice | Body | Lowest note | Root rings | Character |
| --- | --- | --- | --- | --- |
| PLATE | The completely free circular plate, eight modes (Leissa, NASA SP-160, Table 2.5, ν = 0.33) | C3 | 2.2 s | Crowded metal. Keeps the tuning it hardened to. |
| PANE | The completely free square plate at ν = 0.225, close to glass, six modes | C3 | 1.4 s | Glassier. Gives the hardening back as it rings. |
| SHEET | A free steel rectangle three times as long as wide (a theatre thunder sheet, 0.6 by 1.8 m), fourteen modes | C2 | 4.0 s | An octave down. Its overtones crowd together above nine times the root, which is the rumble. |
| BEAM | A free-free steel beam (a girder hung by its ends) in bending, seven modes at ratios 1, 2.757, 5.404, 8.933, 13.344, 24.814, 31.872 | C3 | 3.0 s | Few, wide-spaced modes: a struck bar's clang. |
| DISC | A completely free annular plate, a brake disc: the hole's radius half the outer, ν = 0.3, nine modes at ratios 1, 2.181, 2.675, 4.027, 4.932, 7.285, 7.722, 11.019, 11.112 | C3 | 2.8 s | Ringing metal. The hole lifts the breathing mode to 2.181 (PLATE's is 1.729) and puts several partials near whole multiples. Keeps its hardening. |
| ROD | A uniform free-free steel rod struck on its end, in longitudinal vibration: twelve modes at ratios 1 to 12 | C3 | 3.2 s | A harmonic ping: every partial a whole multiple of the root. HARDEN stretches it only a little, so it stays one. |

PANE's and SHEET's tables come from a Rayleigh-Ritz plate solve, BEAM's from the roots of
`cos βL · cosh βL = 1`, DISC's from the exact Bessel solution of the free annulus (24 entries of
Leissa's Table 2.35 reproduced within 0.46 %) and ROD's from the longitudinal modes of a uniform rod,
`f_n = n·c/2L`. Each voice is *heard at one point* on its body, chosen on the nodal line of a mode that
would otherwise split the house pitch detector's autocorrelation peak and make the note read a semitone,
an octave or a twelfth off (DISC's point was found by scoring 294 candidates with a mirror of the detector;
ROD is heard at its struck end, where every mode moves alike). That is a position on the body, not a
filter, and it is what lets the detector read each SCOUR pad's note.

## The gesture

1. **The roller spins up.** Its speed climbs as the square of the time through the build, from still
   to RPM's top speed (at most 5 turns a second), and the slam stops it in 60 ms. It reads a fixed,
   seeded roughness table, so the grind brightens as it speeds up.
2. **Shot rains on the body.** A seeded random schedule whose rate climbs twentyfold, exponentially,
   and ends at SHOT's rate when the slam lands. Each shot's force follows the house energy law (so
   the mean power does not follow the rate) and swells from a quarter to full as the square of the
   build's progress; that swell is the only loudness ramp.
3. **Each shot dents the surface.** The dented fraction (coverage) climbs by `S += κ·e·(1 − S)`, so it
   stays under 1 by its own arithmetic, and relaxes at a rate the voice sets (PANE's is ten
   times PLATE's).
4. **Coverage retunes the body.** About every millisecond every mode above the root is raised by the
   voice's stretch × HARDEN × coverage × its place in the body. The root keeps its pitch; the top mode
   climbs most. Hardening moves the frequencies and leaves the ring alone, which is the owner's pick.
5. **The slam.** At the end of BUILD, one 0.6 ms half-sine lands where the shots land and dents the
   body once more.
6. **The ring.** The root rings for its voice's 60 dB time times `1 + 2 × HOLD`; each mode above it
   rings shorter by the voice's per-mode factor. PLATE, SHEET and BEAM keep the hardened tuning; PANE
   gives it back as it rings.

The output is the house stage: band limit, decimate, remove the mean, a 20 Hz high-pass, level to the
melodic loudness target, a faded tail. Every draw is seeded from the voice, so a recipe regenerates
the same sample.

## Controls

| Control | Action |
| --- | --- |
| TUNE | The note: 24 semitones up from the voice's lowest note, so the default .5 is an octave above it. |
| BUILD | How long the build runs before the slam, .25 to 8 s on an exponential law. The default .6 is 2 s, one bar at 120 BPM. |
| RPM | The roller's top speed, from .15 of full speed at 0 to full speed at 1. |
| SHOT | The shot rate at the slam, 3 to 300 a second on an exponential law. The default .7 is about 75. The build starts at a twentieth of it. |
| HARDEN | How far coverage lifts the upper modes. At 0 the tuning does not move. |
| HOLD | How long the body rings after the slam, one to three times the voice's root ring. It never loops. |

The defaults are TUNE .5, BUILD .6, RPM .5, SHOT .7, HARDEN .6 and HOLD 0. A sound is as long as its
build plus its root ring, never under 1.6 s: PANE's shortest is 1.65 s and SHEET's longest is 20 s.

## Playing and saving

`ScourPatch` saves the voice, its six controls and the name. TUNE carries the note. Velocity is shot
speed: each shot lands harder and leaves more coverage, and the slam scales with it. It does not change
the length, which is a closed form of BUILD and HOLD alone. `Scour.MODEL_VERSION` is 1 and seeds every
draw; every saved SCOUR sound regenerates through it (the presets, the starter's pads and anything
saved from the phone), so changing it changes them all.

Every SCOUR render is over 1.5 s, so a pad files as LOOP-class, but it is a one-shot: no setting loops,
and HOLD's top step is simply the longest ring. A random draw (`scramble`) may therefore touch all six
controls, since every sound ends.

On the phone the SYNTH picker lists SCOUR after ARCO and before KEEL, under PADS & FX and BELLS &
MALLETS ("Shot-blasted plates that build to a slam, then ring"). It has the four voice chips, the six
controls, the twenty-four presets by voice and the shared ROOT and OCT steppers (SCOUR's root is TUNE).

## Presets

Twenty-four dry impacts, four a voice, all on C minor pentatonic from the voice's lowest note and naming
all six controls. The lengths are the build plus the root ring.

| Voice | Preset | Note | Build | Total |
| --- | --- | --- | --- | --- |
| PLATE | IRON SLAM | C3 | 0.25 s | 3.3 s |
| PLATE | FORGE RISE | C3 | 2.0 s | 5.5 s |
| PLATE | GRINDER RUN | C3 | 4.8 s | 9.2 s |
| PLATE | PEENED BELL | C4 | 0.7 s | 7.3 s |
| PANE | GLASS SNAP | C4 | 0.25 s | 1.9 s |
| PANE | FROST RISE | G3 | 1.4 s | 4.2 s |
| PANE | SHATTER RUN | C3 | 2.8 s | 5.1 s |
| PANE | PANE TOLL | E♭4 | 0.5 s | 4.7 s |
| SHEET | STEEL BOOM | C2 | 0.25 s | 7.5 s |
| SHEET | SHEET RISE | F2 | 2.0 s | 8.4 s |
| SHEET | THUNDER RUN | C2 | 4.8 s | 13.6 s |
| SHEET | DISTANT STORM | G2 | 2.8 s | 14.8 s |
| BEAM | GIRDER CLANG | C3 | 0.25 s | 5.1 s |
| BEAM | RIVET RISE | G3 | 2.0 s | 6.2 s |
| BEAM | GANTRY RUN | E♭3 | 4.8 s | 10.8 s |
| BEAM | FOUNDRY BELL | C4 | 0.7 s | 9.7 s |
| DISC | ROTOR SLAM | C3 | 0.25 s | 4.7 s |
| DISC | CALIPER RISE | G3 | 2.0 s | 7.0 s |
| DISC | DRAG RUN | E♭3 | 4.8 s | 10.4 s |
| DISC | DISC BELL | C4 | 0.7 s | 9.1 s |
| ROD | NEEDLE PING | C3 | 0.25 s | 5.4 s |
| ROD | SPIKE RISE | G3 | 2.0 s | 6.5 s |
| ROD | RAIL RUN | E♭3 | 4.8 s | 11.2 s |
| ROD | STRUT BELL | C4 | 0.7 s | 10.3 s |

`ScourPresetsTest` keeps any two presets of a voice at least .2 apart (RMS over the controls). The
names and settings are proposals the owner has not been asked about one by one.

## The kit and the starter

The SCOUR starter on the FRESH TAPE menu fills sixty-four pads (key C minor pentatonic) over four
banks: bank A is PLATE and PANE, bank B is SHEET, bank C is BEAM and bank D is DISC and ROD. A build is cut to one beat, two
beats, one bar or two bars, so a pad hit that far before a downbeat lands its slam on it: 0.5, 1, 2
and 4 s at 120 BPM, and 0.67, 1.33, 2.67 and 5.33 s at 90 BPM.

- **Bank A.** Pads 1 to 4 are PLATE's builds at 120 BPM and pads 5 to 8 PANE's at 90 BPM, all at C3
  with every other control at its default. Pads 9 to 12 are PLATE's presets and pads 13 to 16 PANE's.
- **Banks B and C.** Pads 1 to 4 are the voice's builds at 120 BPM and pads 5 to 8 at 90 BPM. Pads 9
  to 12 are its presets. Pads 13 to 16 are short hits (BUILD 0) on E♭, F, G and B♭ above the voice's
  lowest note, each struck its own way.
- **Bank D.** Laid out as bank A: pads 1 to 4 are DISC's builds at 120 BPM and pads 5 to 8 ROD's at
  90 BPM, each on its own note; pads 9 to 12 are DISC's presets and pads 13 to 16 ROD's.

Banks B, C and D's builds each have their own note, roller, shot, hardening and ring, because the owner
heard the first, all-default bank B as "pretty similar". The settings came from a search over steps
of .1 that kept every SHOT at .2 or more, used no note more than twice and maximised the closest
pair (bank D's: .29 for DISC and .28 for ROD within a voice, with ROD's hardening held to .5 or under
so its builds stay pings). The hits sit on E♭, F, G and B♭ because in bank B a hit on the root beside
STEEL BOOM, already the bare slam on C2, rendered as a near-copy.

```sh
./gradlew :synth:generateScourKit        # the MPC kit, in testkit/SnipSnap Scour Kit/ (not committed)
./gradlew :synth:generateScourAudition   # the listening page, in testkit/scour-audition/ (not committed)
```

## Listening

| Round (audition date) | What the owner said |
| --- | --- |
| R1 (2026-10-28) | Hardening is clearly audible on PLATE and PANE, and the hardened ring is longer. "Sounds the same as plate though." |
| R1.1 (2026-10-30) | As a riff, no ("a gong with static sounds on top"); as an impact over a beat, maybe; as a bed, no. RING .5 for both voices. Chose impacts and risers. |
| R2 (2026-10-30) | The build and hit into a drop: yes. The short hits alone: yes. The build clearly rises. Chose two tempos for the kit and dropped the held loop. |
| R3 kit (2026-10-31) | Would load the kit as it is: yes. The build lengths: yes. Chose SHEET first, then BEAM. |
| R4 (2026-11-01) | SHEET into a drop: yes. "Yes, it rumbles." Its place an octave down is right. Bank B: "with changes". Chose character and pitch. |
| R5 (2026-11-01) | Bank B varied enough now: yes. Bank C as it is: yes. BEAM into a drop: yes, "a struck girder". Chose to put SCOUR on the phone. |
| Phone (2026-11-05) | "Sounds fine on phone." Asked for six voices; chose a free ring (a brake disc) and a rod, both in one round, with a new bank D. |

Not yet heard: DISC, ROD and bank D (R6); SCOUR through an MPC, where SHEET's lowest octave (65 to 262
Hz across TUNE) may thin out.

## What it does not model

The model is an analogy. Coverage moving the modes is borrowed from cold work hardening a metal,
which really raises yield strength and barely the stiffness. Not simulated: plastic flow, residual
stress, curvature, rebound, stick-slip and air. The strike keeps the root at full weight, though a
real free plate has no motion at its centre for the root; that departure is deliberate, so a centre
blast always sounds the note. BEAM is one plane of Euler-Bernoulli bending: no torsion, no second
plane, no shear. DISC is thin-plate theory on a flat annulus: no hat, no vanes (a vented disc has
them) and no thickness or shear. ROD is longitudinal only and exactly harmonic: it leaves out the lateral
inertia that detunes a real rod's upper harmonics, and the bending modes (BEAM is those). Every constant
the code marks `shape` is a listening hypothesis.

## Where it lives

`Scour.kt`, `ScourPatch.kt` and `ScourPresets.kt` in `:synth`; the kit in `SynthKits.scour()` with
`ScourKitGenerator.kt`; the audition in `ScourAuditionGenerator.kt` and `scour-audition.html`;
the picker entry in `SynthScreen.kt` and `EngineCatalog.kt`. The tests are `ScourTest` (the
mechanics, each voice's body, pitch reads and the energy law) and `ScourPresetsTest` (the roster,
the kit and the spread of banks B, C and D).
