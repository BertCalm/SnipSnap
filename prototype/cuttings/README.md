# CUTTINGS R2 sound study

Python prototype, not a Kotlin engine. Original generated recording scenes; no external audio assets. Adapted from the repository Luthier prototype template, with its seed, DC and measurement helpers. The renderer uses SciPy polyphase decimation, not the Kotlin output implementation.

Audition: https://cuttings-audition.bertcalm.chatgpt.site

Four roles: kick/bass shadow, snare/clap room, hat/tambourine catch, wood/metal cut. Each has 3 dynamic levels × 4 independent modeled performances, with source/splice/finished versions. Recording-scene parameters and cut locations are fixed hypotheses for this first audition; the six proposed macros, genre shelf and phrase articulations are not implemented yet.

## Reproduce

From the SnipSnap repository, with Python 3, NumPy and SciPy available:

```sh
python3 prototype/cuttings/cuttings_proto.py /tmp/cuttings-r2
cp prototype/cuttings/index.html prototype/cuttings/app.js /tmp/cuttings-r2/
node prototype/cuttings/check_player.mjs /tmp/cuttings-r2
```

Serve the generated directory using the environment's supported static preview infrastructure. The page needs HTTP for its manifest and audio fetches. The download link expects a `cuttings-r2.zip` containing `audio/`, `manifest.json` and `report.json`; package those files when distributing the page.

## Evidence and limits

`report.json` records this workspace's Python measurements. Rendered PCM is finite, peak is below 0.84, all 48 variants are distinct within their zones, RMS rises across dynamics, and adjacent dynamics differ spectrally after RMS matching. Hashes verify every generated WAV against the saved manifest. Re-rendering the middle dynamic's third take for each role gives identical PCM. This is prototype persistence, not SnipSnap save/load.

`check_player.mjs` verifies file selection, 4-take cycling, fixed-take selection, stage resets, eight-hit timing, context-free groove selection and Stop in a simulated Web Audio environment. Browser audio output was not verified: the managed browser/preview tools were unavailable. Source publishing helpers were also absent; the site's exact source was committed and pushed using Git with an ephemeral credential supplied through hidden stdin, then packaged and deployed through Sites. No credential was saved.

The page compares stages using one RMS-derived comparison gain per voice/stage, calibrated across medium takes and retained across other dynamics. Grooves use independent RMS matching. This is approximate level matching, not a perceptual loudness guarantee. Groove traces toggles only neighboring musical fragments, retaining the same drum arrangement and room responses. The eight-hit player always uses two full take cycles irrespective of the manual take selector.

The timing measurement is a desktop cold render of three stages, not a phone benchmark. Higher-rate alias comparison, phone performance, Kotlin parity, app persistence/playback, native MPC playback/export, genre kits, authored roll/rising fill and owner sound acceptance remain open. Do not promote this study to engine completion.

## Why it is built this way

- Separate room and context material gives each fragment a recording identity before common processing.
- Smooth body/tail replacement preserves the playable onset while exposing a different source.
- Dynamic excitation and alternate strike positions vary performances without randomly replacing the pad's identity.

## Study 02 revision

Owner requested more distinct, unique, exaggerated character. Retained primary source performances and added a bent bass catch, a snare room dropout and late slap, three descending jingle catches, and reverse-metal pickup into a damped bell. These changes occur before common finishing. `revision-comparison.json` records RMS-matched spectral differences from Study 01, not a listening verdict. All 48 variant and player-selection checks pass again. The original published study remains under `/r1/` on the audition site. Browser audio and full instrument gates remain unverified.
