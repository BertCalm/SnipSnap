# CUTTINGS R3 sound study

Python prototype, not a Kotlin engine. Original generated recording scenes; no external audio assets. Adapted from the repository Luthier prototype template, with its seed, DC and measurement helpers. The renderer uses SciPy polyphase decimation, not the Kotlin output implementation.

Audition: https://cuttings-audition.bertcalm.chatgpt.site

Sixteen roles: the four original signature hits, ten additional percussion cuts, a composed roll and a rising fill. Each has 3 dynamic levels × 4 independent modeled performances, with source/splice/finished versions. Recording-scene parameters and cut locations are fixed hypotheses for this first audition; the six proposed macros, multiple genre shelf entries and editable phrase lengths are not implemented yet.

## Reproduce

From the SnipSnap repository, with Python 3, NumPy and SciPy available:

```sh
python3 prototype/cuttings/cuttings_proto.py /tmp/cuttings-r3
cp prototype/cuttings/index.html prototype/cuttings/app.js /tmp/cuttings-r3/
node prototype/cuttings/check_player.mjs /tmp/cuttings-r3
```

Serve the generated directory using the environment's supported static preview infrastructure. The page needs HTTP for its manifest and audio fetches. The download link expects a `cuttings-r2.zip` containing `audio/`, `manifest.json` and `report.json`; package those files when distributing the page.

## Evidence and limits

`report.json` records this workspace's Python measurements. Rendered PCM is finite, peak is below 0.84, all 192 variants are distinct within their zones, RMS rises across dynamics, and adjacent dynamics differ spectrally after RMS matching. Hashes verify every generated WAV against the saved manifest. Re-rendering the middle dynamic's third take for each role gives identical PCM. This is prototype persistence, not SnipSnap save/load.

`check_player.mjs` verifies file selection, 4-take cycling, fixed-take selection, stage resets, eight-hit timing, context-free groove selection and Stop in a simulated Web Audio environment. Browser audio output was not verified: the managed browser/preview tools were unavailable. Source publishing helpers were also absent; the site's exact source was committed and pushed using Git with an ephemeral credential supplied through hidden stdin, then packaged and deployed through Sites. No credential was saved.

The page compares stages using one RMS-derived comparison gain per voice/stage, calibrated across medium takes and retained across other dynamics. Grooves use independent RMS matching. This is approximate level matching, not a perceptual loudness guarantee. Groove traces toggles only neighboring musical fragments, retaining the same drum arrangement and room responses. The eight-hit player always uses two full take cycles irrespective of the manual take selector.

The timing measurement is a desktop cold render of three stages, not a phone benchmark. Higher-rate alias comparison, phone performance, Kotlin parity, app persistence/playback, native MPC playback/export, genre kits, phrase listening acceptance and owner sound acceptance remain open. Do not promote this study to engine completion.

## Why it is built this way

- Separate room and context material gives each fragment a recording identity before common processing.
- Smooth body/tail replacement preserves the playable onset while exposing a different source.
- Dynamic excitation and alternate strike positions vary performances without randomly replacing the pad's identity.

## Study 02 revision

Owner requested more distinct, unique, exaggerated character. Retained primary source performances and added a bent bass catch, a snare room dropout and late slap, three descending jingle catches, and reverse-metal pickup into a damped bell. These changes occur before common finishing. `revision-comparison.json` records RMS-matched spectral differences from Study 01, not a listening verdict. All 48 variant and player-selection checks pass again. The original published study remains under `/r1/` on the audition site. Browser audio and full instrument gates remain unverified.

## Study 03: Pocket Finds kit expansion

One 16-pad prototype kit, divided into four compact audition crates: Signature, Backbeats, Texture and Transitions. The original 144 signature-stage WAVs are byte-identical to Study 02. New source roles use distinct membrane/modal/noise exciters and musical tail replacements. The groove now introduces shaker, hand percussion, open hat, impact and metal without playing every pad at once.

Roll and rising fill are finite one-bar phrases at 89 BPM, followed by a short release (3.0166 seconds total). The roll uses a composed rim/snare pattern and wood exit. The rise accelerates from hand drum through rim/snare subdivisions to a wood cutoff. Both have three dynamic performances and four take sequences. No infinite HOLD or live tempo-following is claimed. Editable 1/2/4-bar HOLD remains outstanding.

Checks: 192 variant renders, 576 stage WAVs, monotonic velocity RMS, RMS-matched timbral velocity differences, RR diversity, deterministic re-renders and WAV-manifest integrity pass. Simulated player checks now include crate navigation and phrase-repeat spacing that prevents overlaps. Browser audio remains unverified. Download archives are split into hits and phrases, with independently complete sample manifests. Deployment is private at the same Site; `/r2/` retains the prior study. Full Kotlin/app/export gates remain open.
