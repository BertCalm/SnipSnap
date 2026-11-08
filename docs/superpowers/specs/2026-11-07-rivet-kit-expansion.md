# RIVET model 2: the six-role kit trial

Status: candidate expansion of the approved direction; new voices and pad colors await listening.
Source baseline: `659c62d`, the first RIVET implementation, on top of `cc47036c`.

The characteristic gesture remains **impact → metal rattle → walls answer → dark decay**.

After the first audition was shared, the user said, “That sounds great, let's continue.”
This authorizes continuing the direction. It is not a recorded per-clip verdict on all
98 comparisons, and it cannot approve voices that did not exist at that point.

## What changes

Model 2 appends TOM, METAL, HAT_CLOSED and HAT_OPEN to KICK and SNARE. It retains the
same six controls and the explicit HOLD refusal. This is one engine with six roles.
The original kick and snare keep their precise source arithmetic, seed version 1, output
processing and room mapping. Model 2 can render their old model-1 recipes; new roles
are refused in model 1. New noise uses seed version 2, with one shared HATS identity.

| Role | Main mechanism | Native source length | What METAL changes |
|---|---|---:|---|
| KICK | Retained membrane, shell and beater | .45 s | Retained model 1 |
| SNARE | Retained membrane, shell and wires | .36 s | Retained model 1 |
| TOM | Rooted membrane plus stretched upper shell | .42 s | Upper ratios and shell participation/decay |
| METAL | Two inharmonic struck banks plus a contact edge | .40 s | Upper ratios, shell participation and ringing time |
| HAT_CLOSED | Shared bright modal/noise metal source, heavily damped | .14 s | Upper ratios, ring and noise lifetime |
| HAT_OPEN | Same source and grain realization with weaker damping | .42 s | The same quantities over longer decay ranges |

These are designed modal shapes, not measured drums or cymbals. TUNE maps to 75–220 Hz
for tom, 180–620 Hz for metal, and 1.8–4.2 kHz for the hats' cluster base. Hat shell ratios
stay below output Nyquist at their highest tuning. All new source/drive processing runs
at 4x and uses the existing finish/parallel-room path. No shared engine or room DSP changes.
SIZE still controls the building alone. All finite renders remain below 1.5 seconds.

## Candidate kit

`RivetKits.patches` owns sixteen names and control maps. `RivetKits.build` and
`SynthKits.rivet` render those exact recipes, with three native strike zones at .3/.6/1.
Soft zones are re-struck through the engine, then peak-matched to the main hit; the
sampler owns playback amplitude. Every pad carries its complete editable PadRecipe.

| Pads | Roles |
|---|---|
| A01–A04 | Main kick, main snare, matched closed/open hats |
| A05–A08 | Deep kick, hard snare, press and pipe percussion |
| A09–A12 | Low/mid/high toms and a sheet-metal hit |
| A13–A16 | Metallic kick, loose-wire snare, second closed/open hat pair |

Only A03/A04/A15/A16 use choke group 1. All other pads use group 0. Choking is a kit/MPC
playback behavior; it cuts the baked room tail along with the hat, not a shared live reverb.
All pads remain one-shots.

Three kit variants use the same source recipes with SIZE .1/.55/1 and WALLS .75. Each
has sixteen pads, 48 PCM24 samples, native MPC 3 `.xtd`, an MPC 2 `.xpm` twin and a four-bar
120 BPM pattern. The audition uses the actual exported velocity WAVs and one common gain
across the three room-size grooves. Its linear velocity gain and 2 ms choke fade are
documented preview laws, not claims of a recording from hardware.

Run `./gradlew --no-daemon :synth:generateRivetKit` and open `testkit/rivet-kit/index.html`.
The generator reads back both program formats, compares every exported sample with its
native render, checks each pad's recipe, and packages one portable ZIP per room.

## Contracts and first validation

- Four frozen SHA-256 digests were recorded from `659c62d` **before** modifying the source:
  all 64 corners for each original voice at velocities .25 and 1. Model 1 must match those
  digests, and model 2 must produce the same PCM for those original inputs.
- All six voices run the corner, low/middle macro movement, pair interaction, velocity,
  finite/DC/peak/duration, settling-room, immutable JSON and model/HOLD refusal probes.
- A hat-pair probe checks related early attacks and substantially different late responses.
- Kit tests cover role coverage, sixteen unique pad names, three strike zones, recipe replay
  and hat-only choking. The exporter separately verifies the actual files.

The first focused run passed **24 tests** with Gradle exit 0. This includes all **384**
full-strength corners, the frozen **256** original corner/strike renders, and hat/kit probes.
The first export run verified **144** PCM zones and both MPC program formats across the
three rooms. The follow-up integration run passed **57 tests** with Gradle exit 0:
49 synth tests (including patch/recipe/preset registries) and 8 shell root-editor tests.

The final packages were independently inspected: each contains 48 WAVs, 16 explicit
PadRecipe files, one `.xtd` and one `.xpm`; none contains the temporary staging tree.
All 144 WAVs are mono PCM24 at 44.1 kHz. Maximum PCM deviation from the native rendered
zone is **0.0000000596046**, maximum sample peak **0.95000005**, maximum absolute mean
**0.0000015428**, and longest sample **1.289728 s**. Only the four hats use choke group 1.
These are file/software checks, not hardware or listening claims.

The unfiltered command `./gradlew --no-daemon --max-workers=2
:synth:generateRivetKit test --fail-fast` was started against the expanded source.
Full-suite status is tracked separately; focused green is not a full regression claim.

## What remains open

The new roles, the candidate pad colors and the complete grooves need the user's ear.
Phone render latency, physical MPC loading, SDK-backed picker work and factory/starter
promotion remain open. This round stays outside the factory Presets/StarterKits registries.
It supplies portable candidate programs for that next verdict.

## Why it is built this way

- Frozen PCM guards let the kit grow without changing the original sounds.
- A shared hat source makes open and closed feel related; different damping provides the gesture.
- Native soft strikes change the contact and spectrum rather than filtering one frozen sample.
- Three coherent room-size kits make the building change easy to compare and load on a sampler.
- The riskiest new choice is the metal/hat balance in a full pattern. Numbers prove boundedness
  and replay, while the next audition decides whether those roles fit around the kick and snare.
