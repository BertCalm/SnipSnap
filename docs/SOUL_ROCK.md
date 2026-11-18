# Soul and rock acoustic kits

Three factory kits preserve the synthesized audition the owner approved on
2026-11-16: “It all sounds great, proceed.” [Listen to the approved bank](https://chatgpt.com/space/page_b74b130ffbc0819181b5a1e9e4530fc5).
This approval covers the published Python sound, not phone or MPC hardware.

| Starter | Tempo | Character and complementary role |
| --- | ---: | --- |
| SOUL POCKET | 108 | More damping and a closer perspective; tambourine and claps. |
| ROADHOUSE ROCK | 134 | Lower kick, stronger wires, open hats and cowbell. |
| OPEN ROOM ROCK | 89 | Longer tails and room, ride-led playing and an extra high tom. |

Each kit has 16 pads, three velocity zones and three distinct takes: 432 strikes.
Every kit includes a finite press roll and cymbal swell. Closed, open and pedal
hats share mute group 1. Sizzle remains the approved sizzle voice; a true crash
is a future addition, not an unannounced substitution.

## What ships

`SoulRockKits` loads losslessly compressed mono 44.1 kHz PCM16 factory audio.
The audio was synthesized with the existing EMBER Python membrane, wire and
cymbal models. No source recording excerpts play inside the instruments.
These are **frozen synthesized factory samples**, not a new editable Kotlin
synth engine. The optional native recipe field stays empty; source metadata
retains the original voice, macro settings, kit, slot, bank version and audition.
Each build owns its decoded arrays and does not resynthesize on a pad trigger.
Compressed assets add approximately 32.7 MiB before APK packaging.

The manifest records a SHA-256 and frame count for every approved WAV. Loading
and saving must preserve those samples; no further normalization, EQ or noise
substitution is applied. The bank already contains its within-zone take leveling,
velocity timbre and level law, room and true-peak headroom. Sampler gain remains
the app's normal playback behavior.

The reference screens identify Stevie Wonder's *Signed, Sealed, Delivered*,
Thin Lizzy's *Cowboy Song* and four excerpts of Argent's *Hold Your Head Up*.
These names are research references only. The authored grooves are not song
transcriptions. Kick targets came from low-band-dominant events, which can contain
overlapping instruments; neither a studio-equipment identification nor an exact
reference reconstruction is claimed. EMBER's CC0 model-source credits are in
`synth/src/main/resources/soul-rock/v1/NOTICE.txt`.

## Evidence and gates

- **Sound:** owner-approved Python audition, 2026-11-16. The shipped factory
  samples are pinned to those approved WAV hashes.
- **Python measurements:** 432 distinct files; estimated 4x true peak below
  0.93, maximum absolute DC below 0.000023, final 10 ms RMS below 0.000675.
  Take RMS spread within each zone is below 0.001 dB. These are numerical
  checks, not perceptual similarity scores or a full alias-reference proof.
- **Rendering/loading:** `SoulRockKitsTest` checks all approved hashes, frame
  counts, mono/rate, bounds, tails, gain-matched shape differences, dynamic
  levels, take leveling, ownership of buffers and finite roll/swell durations.
- **Shelf/save/play/export:** `SoulRockRoundTripTest` exercises all three
  registered kits, all 432 PCM windows, every MIDI velocity, three take cycles,
  source identity, SFZ round-trip and MPC 3 metadata/PCM read-back.
- **MPC 2:** the existing combined velocity/round-robin format exception applies;
  retaining a zone's first take is not an MPC 3 exception.
- **Phone/MPC hardware:** open for this collection. Earlier kit approvals do not
  apply. MPC 3 software read-back does not establish hardware slice behavior.
- **Melodic/keygroup/chord/HOLD/STRUM:** not applicable to finite drum banks.
- **Repository regression and Android:** see the delivery PR's exact-head checks.

The focused integration run passed 93 checks (2 synth and 91 shell) with Gradle
exit 0. A fresh local compile exhausted the default 3 GB compiler heap; the
successful run used `-Pkotlin.daemon.jvmargs=-Xmx6g`. No tests were skipped.

## Reproduce and export

The accepted baseline is the versioned resource bank, not a rerun that changes
noise, floating-point rounding or gain calibration. The Python exploration and
measurements live in `prototype/soul-rock`; those scripts document the audition's
origin and remain an experimental rendering path. `build.py` followed by
`refine.py` is not claimed to regenerate every final WAV byte-for-byte after the
iterative kick and peak corrections. The manifest and frozen files are canonical.

```sh
./gradlew --no-daemon :synth:test --tests '*SoulRockKitsTest'
./gradlew --no-daemon :shell:test --tests '*SoulRockRoundTripTest'
./gradlew --no-daemon :shell:generateSoulRockKits
```

The generator verified all three exported banks: 48 pads and 432 slices. On this
host, each kit decoded in 311–486 ms; this is not a phone measurement. The stable
starter-ID check also passed.

The generator requires an empty output folder. It writes native kits, MPC 3 tracks,
read-back pad/touch auditions and a verification CSV under `testkit/soul-rock-kits`.
It verifies the saved PCM, actual playback windows and actual exported track.

## Why it is built this way

- Frozen synthesis preserves the exact sound the owner approved, including the
  cowbell that is not yet implemented in the native EMBER renderer.
- Three kits carry distinct tuning, damping, perspective and complementary roles.
- Full strike grids preserve dynamic timbre and alternate takes through export.
- Baking the room and long swell avoids expensive synthesis during playback.

## Accepted coupled snare, version 2

On 17 November 2026 the owner accepted **Stronger snare snap and rattle**:
“Ok. I can live with that”. Version 2 changes only Roadhouse pad 2 to the exact
nine accepted coupled-head/contact-wire renders. Soul Pocket, Open Room and all
other Roadhouse pads retain version 1 PCM. Existing saved kits remain unchanged;
newly built Roadhouse starters use the accepted snare.

The snare retains natural take-level differences (largest measured zone spread
3.835 dB); equalizing those takes would alter the accepted audio. Its source metadata
identifies the coupled model and does not carry obsolete EMBER macro settings.
Version 2 resolves its nine snare resources explicitly and fails if one is missing;
all other strikes resolve to the immutable version 1 bank. Exact accepted hashes are
pinned independently in the test. Model source and prototype probes are retained in
`prototype/soul-rock/coupled-snare`. This remains frozen synthesis, not an editable
Kotlin engine. Phone/MPC hardware acceptance for this revision remains open.

Revision validation: `SoulRockKitsTest` and `SoulRockRoundTripTest` passed (four
focused tests). The generator verified all 48 pads / 432 strikes through saved PCM,
playback cycles and MPC 3 metadata/PCM read-back. All six download ZIP parts passed
CRC and payload checks. Full repository regression/CI is tracked on the revision PR;
these local checks do not imply Android or physical MPC acceptance.
