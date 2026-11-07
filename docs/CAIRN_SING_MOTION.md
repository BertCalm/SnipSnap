# CAIRN: a moving held SING

Status: production listening revision, 2026-11-07. All 8,848 tests in the
complete nine-module JVM gate pass, with no failures, errors or skips. Repository read: `97a5d6309de1825dbd3940e578384b11368b6f16`.

The gesture is **continuous rubbing → slow beating in the body → overtones
brighten and soften around the note → continuous rubbing**. Josh asked for a
little more motion in held SING, like a singing bowl.

## Luthier review and change

Try next [A]: the original held contact has a small pressure stroke, but its
relative harmonic weights are constant. This makes its sustained colour fairly
stationary. Keep the stable pitched carrier and give the rubbing contact a slow,
periodic distribution across the fundamental and upper partials.

Model 5 adds this contact motion only to held SING. Model 4 recipes keep their
exact waveform; finite SING and all three contact voices render as before.
The existing 2 kHz regenerative energy trajectory and six-mode passive bank
remain the same. This is a bowl-inspired contact/readout analogy, with designed
shape constants, rather than a bowl geometry or microscopic friction simulation.
MERCURY and THAW remain the nearer literal friction siblings; this is a small
Cairn voice revision.

```
cached pressure and regenerative energy
  → steady note-phase traction × opposed harmonic contact weights
      ↑ one rim revolution per existing two-second pressure cycle
  → original six passive modes
  → band limit → decimate → DC removal / high-pass → level
  → actual preceding audio + kept continuing cycle
```

The contact's single slow phase is shared with the pressure cycle: 0.5 Hz with
a small 1 Hz shape component. The fundamental and upper partials swell at
different phases. This puts nearby sidebands around a stable carrier and moves
the partial-to-root balance, rather than applying one gain envelope to the
completed note. The carrier frequency, modal tuning and period are unchanged.

GROOVE increases contact-motion depth. BODY and velocity moderate that depth
alongside their existing ring and contact behavior. Motion is computed by a
bounded oscillator recurrence while building the force arrays; there is no new
solver, trajectory cache or audio effect. The maximum depth is .58. The three
harmonic weights stay positive within conservative analytic bounds; the
fundamental weight lies in .7216..1.2784. This remains a one-way driven readout;
the existing regenerative energy bound applies to its energy proxy, not a claim
that the audio readout is an independently conserved physical bowl.

The two-second forcing is periodic. The exported audio is obtained by driving
the bank, with its state closed by the existing uncoupled solver, and processing
real preceding and kept audio together. A separately advanced twelve-cycle bank
is the reference. Repeating the exported audio is only the audition player's
eight-cycle preview, never the seam proof.

## What to verify and hear

The rendered waveform probes compare root-envelope depth and changing
second-partial/root balance with model 4 in C3, C4 and C5. A master tremolo alone
cannot satisfy the harmonic-balance probe. Other checks cover the root across
the cycle, held control corners, deterministic soft and silent renders, real
continuation suffixes, the independently warmed bank and versioned recipes.
Replay the earlier native fixtures before claiming preservation.

The comparison offers matched before/after controls in three registers and
three GROOVE settings. The listening risks are too much pulsing, too much
brightness movement and a loop that feels short. Only Josh supplies the musical
verdict. Phone/MPC playback remains unverified in this environment.

## Why it is built this way

- Slow beating supplies the bowl-like movement requested for the held note.
- Opposed overtone weights move its colour as well as its volume.
- One shared cycle keeps the movement continuous through the loop join.
- Retaining the carrier and resonant body protects the note and SING's identity.
- A saved model number keeps earlier recipes reproducible.

## Evidence ledger

| Claim | State | Evidence |
| --- | --- | --- |
| Carrier, modal tuning, energy law and finite waveform retained | Read | Versioned branch in CairnSing; Cairn MODEL_VERSION 5 |
| New motion is musically right | Open | Awaiting owner listening |
| Rendered default motion in C3/C4/C5 | Measured in Kotlin | Root-envelope depth 16.3–16.6%, previously 3.1–3.2%; partial/root balance depth 39.7–40.6% |
| Root over three registers, three GROOVE settings and three cycle windows | Measured in Kotlin | Maximum 4.1349 cents, minimum confidence .9732 |
| Six independent held-cycle comparisons | Measured in Kotlin | Real preceding seam and twelve-cycle warm-reference difference both zero |
| Focused Kotlin gate | Verified | 40 tests, exit 0, including 32 held control corners and recipe/version tests |
| Prior native recipes | Verified | 300 recipes, 600 WAVs byte for byte unchanged; 178 eligible cases also unchanged through model 5 |
| Complete nine-module JVM gate | Verified | Exit 0, 8,848 tests, no failures, errors or skips |
| Idle server timing | Measured | 54.10 ms moving held median, 53.07 ms original; first moving HOLD 273.77 ms |

## Reproduction

With JDK 21:

```sh
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Cairn*Test' --tests com.snipsnap.synth.ModesPeriodicTest
./gradlew --no-daemon :synth:generateCairnSingMotionAudition
./gradlew --no-daemon test
python3 scripts/prepare-cairn-sing-motion-site.py --site /absolute/path/to/cairn-site
```

The new task writes `testkit/cairn-sing-motion/`. Optional
`-PcairnSingMotionLegacyRoots='/path/to/reconstruction;/path/to/hold;/path/to/tumble;/path/to/sing'`
replays their saved native recipes. The prior SING generator stays pinned to
model 4. Packaging preserves earlier Site assets, compares lossless PCM, checks
real continuation suffixes and restores the complete source patch in a fresh
Git index. `--source-only` updates source/prose after unchanged audio is verified.

## As built and measured

The native task supplies 16 recordings, including 14 held cases. Their actual
preceding/kept Float seams and independently decoded 24-bit PCM seams are zero;
their twelve-cycle warm-reference differences are zero. All 300 earlier native
recipes replay unchanged, producing 600 byte-identical WAVs. The prior SING task
is explicitly pinned to model 4. Historical recordings remain retained assets.

The default root-envelope depth is 16.3–16.6% across C3/C4/C5, compared with
3.1–3.2% in model 4. The second-partial/root balance depth is 39.7–40.6%.
These use 64 Hann-windowed 120 ms projections on the rendered waveform and
depth (P95 − P5)/(P95 + P5), independent of the contact's coefficient law.
Nearby half-Hz sidebands have amplitudes 7.5–9.4% of the stable carrier in
the measured defaults. C5's 4×/8× waveform-relative RMS difference is 1.359%;
this is a rate comparison rather than a proof of zero aliasing everywhere.

The idle server comparison uses twelve warmed renders with changed SLOPE per
version. Medians are 53.07 ms for model 4 and 54.10 ms for model 5. The first
model-5 HOLD takes 273.77 ms including DSP initialization, not whole-JVM startup.
The warmed path remains above the proposed 50 ms server target. Phone/MPC
latency and the owner's verdict remain open.

The complete JVM run exited 0 with 8,848 tests and no failures, errors or
skips. It ran from `6edd57b1c6e55562fd326adf68caf64008a370c5`; subsequent changes are verification prose
and frontend presentation/interaction. The DSP hashes match the native audio.
Android compilation/device tests were unavailable because no Android SDK is
installed; no Android or native C++ files change.

Chromium decodes all 16 recordings. The five viewport widths 1440/1024/768/390/360,
eight-cycle scheduling, register/stop behavior, every listening control, audio
and manifest retries and the source download pass. The package preserves 1,568
prior Site files and verifies its complete source patch in a fresh Git index.
