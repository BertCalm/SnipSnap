# TIMBER acoustic instrument percussion

Repository baseline: `23cde0d1c107039c23fd74ff5c3d2b359a7f6e75`, plus local TIMBER changes.
Model 2 ports the owner-approved Python round 2 and extended-technique auditions into Kotlin.
The owner accepted the first audition with “Sounds pretty good” and the extension with
“Sound good, continue.” This is approval of Python sound direction, not Kotlin or hardware playback.
Overall instrument acceptance remains open.

The characteristic gesture is **contact → body and strings → damped resonance → silence**.

## Sound family and landing

Four independently authored body profiles: acoustic guitar, acoustic bass guitar, cello and
upright bass. Seven techniques: palm thump, knuckle knock, open string slap, muted string slap,
bridge tap, across-string rake and along-winding scrape. Body frequencies, decays, stiffness,
contact laws and balances are design shapes, not measurements of real instruments.

This remains a finite percussion bank, not a new melodic engine. The nearest siblings are
PLUCK's pitched strings and ARCO's bowed strings. The chosen SPLICE direction replaces the
excitation and damping per technique while keeping each instrument's body. A STACK of muted
PLUCK/ARCO voices risks sounding like notes in every drum role; bidirectional COUPLE would
need a new contact solver and calibration. The bank is the lightest landing for this request.

`TimberKits.contact` exposes all 28 dry instrument/technique combinations. Three authored
sixteen-pad banks are registered through `StarterKits.ALL` and the SKINS shelf. The bank
stores source provenance and rendered velocity/take grids, not an editable `PadRecipe`.

## Signal flow

```text
instrument + technique + velocity + take + model version
    -> finite contact impulses or winding-ridge train at 176.4 kHz
    -> independent wood modes and each instrument's string tuning
    + small filtered direct contact path
    -> house band limit and decimator
    -> mean subtraction, 22 Hz high pass, fixed gain, tail fade
    -> mono 44.1 kHz
```

The same contacts drive strings and body. Palm broadens the contact and damps upper body modes;
muted slap shortens string decay and adds delayed board contacts; bridge tap strengthens upper
wood modes; rake staggers string contacts; scrape traverses a finite winding texture. Scrape
takes share the texture, with small changes of position and travel speed.

The modal poles have radius `exp(-ln(1000)/(T60*rate)) < 1`. Excitation is one-way; there is no
feedback from the body into the contact generator. `TimberModel` uses Double modal state and
coefficients to retain precision at bass frequencies at 4× rate. It does not change the shared
Float `Modes.ring` implementation or any other engine. A sin(theta) factor compensates the
analytical modal impulse amplitude. Unit-area Hann contacts are band-limited before resonance.

The direct contact filter stops after a 2048-frame guard; a continuing-reference test bounds
the omitted tail below 1e-9 full scale. This avoids processing subnormal Float state through
the silent remainder of the hit.

Modal processing ends four T60s after the last forcing sample, a -240 dB amplitude
threshold. `TimberModelTest` compares every technique against a full continuing
reference at the shortest genre damping, requiring output error below 1e-8 full scale.

The output uses one fixed 0.60 gain, with a checked peak budget and no per-strike normalization
or limiter. The three velocity layers change contact shape and collision strength as well as
level. Four deterministic takes vary contact position, width, timing and texture travel.

This is a mechanism port, not byte parity with scipy: house filters, decimation, noise and
floating-point output differ. Python remains the accepted sound reference; the Kotlin pack
provides the next listening comparison. Direct stochastic clicks use `Dsp.Noise` and `Dsp.seedFor`. The winding surface
variations in `TimberScrapeTexture` retain the approved Python ridge pattern across runtimes;
these are procedural model coordinates, not recorded samples.

## Kits and performance

| Shelf ID | Kit | BPM | Core arrangement |
| --- | --- | ---: | --- |
| timber-pocket | TIMBER FOLK POCKET | 92 | Upright palm, bass guitar heel, guitar slap/rake; full body decay |
| timber-boom-bap | TIMBER WOOD BOOM BAP | 86 | Bass guitar deep heel, low upright palm/backbeat, bass guitar rake; shorter decay and swing |
| timber-two-step | TIMBER STRING TWO STEP | 132 | Cello/guitar heels, bass guitar tight slap, cello rake; shortest decay and syncopation |

Each has fourteen hits, an accelerating knuckle roll and a rising string-rake phrase. A04/A05
share choke group 1. A15/A16 are finite phrases with full tails, tagged LOOP for the kit role;
they are not seamless sustain loops. Melodic keys/chords/HOLD/STRUM requirements do not apply.
Every pad carries three velocity zones × four ordered takes. Pad levels are authored and saved.
The preview applies the actual `PadHit.resolve` gains and uses no hidden per-event mix levels.

A hit has a fixed .95-second buffer and a fixed mode count; there is no solver or geometry.
Target phone render time remains tens of milliseconds, unmeasured. Whole-bank creation renders
192 variants, including sixteen-contact phrases. No instant whole-kit promise is made. A future
cache must key model, instrument, gesture, tuning, damping, velocity and take, and protect buffers
from mutation. Actual sandbox throughput is recorded with the generated report.

## Compatibility

New kits save model 2. `LegacyTimberKits` preserves the original model 1 implementation and
`TimberKits.replay` dispatches old source records to it. Unknown versions are refused.
Existing saved audio is unchanged. New source records include model, genre, role, velocity grid,
take count, tempo and articulation; pad balance is stored in the kit itself.

MPC 2 retains the first take of each velocity layer under the owner's format exception. MPC 3
exports all chain PCM and cycle descriptors. The repository's missing MPC chain-WAV slice map
and hardware capture remain open, as explicitly parked by the owner. Metadata checks do not
establish hardware round robin. No change here claims to resolve that shared limitation.

## Validation and delivery

Run the focused checks:

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.TimberModelTest --tests com.snipsnap.synth.TimberContactTest --tests '*DeterminismTest.TIMBER*' :shell:test --tests com.snipsnap.shell.TimberKitTest
./gradlew --no-daemon :shell:generateTimberKits -PtimberOutput=testkit/timber-v2
python3 scripts/verify_timber_pack.py testkit/timber-v2
```

`TimberModelTest` covers all 336 dry contact variants, deterministic replay, zero velocity,
finite output, DC, tail decay, all three velocities for all takes, all six take pairs per zone,
and maximum within-zone RMS spread of 1.5 dB. The accepted Python guard thresholds are retained:
soft/hard residual above -35 dB, take residual above -45 dB after a best scalar fit. These are
numerical distinction tests; onset differences contribute and do not prove audible timbral diversity.
They replace model 1's larger residual thresholds, alongside a new level-spread constraint.

`TimberKitTest` covers all 576 kit variants, saved level and choke settings, full provenance replay,
each velocity boundary, two take cycles, actual chain PCM, and MPC 3 descriptors. The generator
writes native kits, MPC 3/MPC 2 exports, grooves, native and matched touch strips, 28 dry contacts,
and `verification.csv`. The Python verifier checks MPC 2 windows and audition asset closure.

## Gate ledger

| Gate | Status |
| --- | --- |
| Sound direction | Python round 2 and extension accepted by owner; Kotlin listening open |
| Multiple genre kits and percussion phrases | Implemented; software checks recorded below; device shelf unverified |
| Velocity and round robin rendering | Implemented; software checks recorded below |
| Saving and playback | Software checks recorded below; physical phone audio unverified |
| MPC 3 export | PCM/metadata checks recorded below; chain slice-map and hardware gate open |
| MPC 2 combined velocity and RR | Owner format exception; velocity export still checked |
| Melodic keys/chords, melodic HOLD/STRUM | Not applicable to finite percussion banks |
| Repository regression | Final command outcome recorded below |

## Why it is built this way

- Independent body modes and string tunings preserve the approved instrument identities.
- Contact drives both wood and strings so the transient belongs to the resonating instrument.
- Small take changes retain expression while avoiding the first bank's large level swings.
- Saved pad levels and real playback gains make the audition representative of the exported kit.
- Keeping model 1 intact prevents new sound-design decisions from rewriting old source replays.

## As built validation

The final focused Gradle command above, including the kit generator, exited 0.
All **100 focused checks pass**: 94 shell checks and six synth checks. They cover
336 dry contact variants and 576 kit variants. Maximum dry take RMS spread is
**0.8816 dB** and maximum sample peak is **0.88545 full scale**.
Across the kits, closest take residual is **-35.5505 dB** and closest soft/hard
residual is **-14.7930 dB**. These are waveform measurements, not listening claims.

Generator time per output variant, including the multi-contact roll/riser pads:
FOLK POCKET **116.70 ms**, WOOD BOOM BAP **97.37 ms**, STRING TWO STEP **142.48 ms**
on this sandbox. A bank contains 192 variants. These figures do not measure phone
latency or the cost of an individual finite contact.

`verify_timber_pack.py` exited 0: 144 MPC 2 velocity windows, 48 chain WAVs and
147 audition WAV references. `package_timber_page.py` produced 16 listening tracks
and the complete native/MPC export archive, with no additional leveling.

`compare_timber_ports.py` measures the 28 dry Kotlin contacts against the accepted
Python hard-strike references. After at most 1 ms alignment and gain fitting,
24 impact/rake comparisons correlate above 0.94; scrapes range from 0.7940 to
0.9704. The winding pattern is retained, but output filters differ. Kotlin listening
remains open, especially the bass scrapes. No byte-parity claim is made.

Historical regression at the original baseline was **blocked**, not green. The run of
`./gradlew --no-daemon --parallel test --fail-fast` found the existing
`PersonalityTest` sentence-case failure in `App.kt:1403` and `App.kt:1405`: two
CAIRN messages use all caps. Both App.kt and PersonalityTest.kt are unchanged
from baseline `23cde0d1c107039c23fd74ff5c3d2b359a7f6e75`. After confirming that
failure, the remaining broad run was interrupted (exit 130); it is not a
completed full-suite result. The isolated sentence-case check reproduced the same
failure and exited 1. The interrupted broad run reported 3,160 passes and one
failure. No Android SDK is installed here.

Repository delivery (2026-11-15): merged default-branch commit `6e2e40676`,
which includes the CAIRN sentence-case fix (`ee6f0a3f0`). The current catalogue
has 136 starters including the three TIMBER banks. Focused validation of this merged
revision passed 101 checks (exit 0), including the formerly failing CAIRN copy
check and TIMBER save/playback/export. Full repository regression is being rerun;
a clean full-suite result is not yet established. Listening and hardware gates remain open.
