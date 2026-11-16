# SIDEROOM: five acoustic drum-kit studies

Status: R1 rejected by the owner for drum-machine character. R2 recorded acoustic core was approved for sound. R3 tests sample-derived synthesis against it. No SnipSnap shelf, native serialization or hardware completion claim. Source inspected at `4ecab4c9f`; isolated implementation starts from `e7ccfef58` on `claude/sideroom-study`.

The characteristic gesture is **stick or beater contact → head/metal response → wire or shell after-sound → damping to silence**. Each kit chooses a different physical balance within that gesture.

## References and interpretation

The owner selected separate kits in one family, then asked to build. Screens supplied two Casey Jones excerpts, one Train in Vain, two Fashion, two I Believe in a Thing Called Love and two Get on Top. The last title was supplied by the owner. Recordings guide listening targets; no source recording is embedded or sampled. Song credits remain reference labels, not instrument brands. Capture spectrum measurements are not measurements of isolated drums or proof of recording equipment.

| Study kit | Reference | Proposed target |
| --- | --- | --- |
| Open Road | Grateful Dead, Casey Jones | Round low heads, wooden backbeat, open toms |
| Tight Pocket | The Clash, Train in Vain | Short kick, pointed backbeat, clipped hats |
| Angular | David Bowie, Fashion | Low dry kick, high taut snare, precise metal |
| High Voltage | The Darkness, I Believe in a Thing Called Love | Larger shells, broad wires, long cymbal accents |
| Deep Pocket | Red Hot Chili Peppers, Get on Top | Firm kick, high shell bite, ghost notes and active hats |

BPM values in the prototype are authored arrangement choices, not measured source tempos. Modal frequencies, decay times and contact weights are design values, not claimed physical measurements.

## Landing and sibling check

SOOT already implements decaying membrane/metal models and physical strike variation; BACKWASH has acoustic percussion, genre kits and a recording chain. Those mechanisms cover this gesture. SIDEROOM therefore lands as a kit family, not a new registered synth engine. R1 adapts the modal/metal approach from `scripts/soot_r0/soot_r0.py`; it does not carry SOOT's cassette, spring or dictaphone effects. `BackwashAcoustic.source` was inspected as an alternative, but its current bank covers auxiliary percussion rather than a full source kit.

The chosen DISTIL route builds five instrument tunings and loss profiles: clear gesture, modest mechanism novelty, moderate acoustic-realism risk, one listening round before native work. A STACK alternative would put one source kit through five recording chains: lower cost but weaker source distinction. A COUPLE alternative would model sympathetic snare and tom bleed: higher novelty and state-management cost, deferred until the dry sources are accepted. The owner already chose separate kits; these are implementation alternatives rather than another approval request.

## Signal flow

```text
kit + voice + strike level + take
  → contact brightness / position / force
  → decaying membrane modes OR dense metal modes
  → shell contact and snare-wire response
  → 4× anti-aliased downsampling → 22 Hz high-pass → finite fades
  → one fixed calibration per kit instrument → WAV grid
  → authored groove/fill or SFZ region mapping
```

Tension relaxation is an analogy encoded as a small pitch trajectory. Membrane/metal decays are explicit stable exponential modes. Snare-wire flutter is shaped noise, not a collision solver. There is no measured room, physical interaction solver or gear emulation. No hidden feedback, normalization per velocity hit, or whole-kit compressor is used.

No source-macro panel is promised in this prototype. The musical controls are Kit, Strike (three timbral levels), Performance (four takes or cycle), pad bank, groove and eight-hit comparison. Each hit changes spectral excitation with velocity; takes change contact position, modal weights, metal phases and noise. Finite one-bar rolls and two-bar lifts use the same source grids. Pads hold through their finite tails; no infinite HOLD claim. Closed hats choke open hats in the browser and in the authored groove. SFZ exports specify their shared choke group.

## Build and checks

`python3 prototype/sideroom/render.py /absolute/output`

`python3 prototype/sideroom/check.py /absolute/output`

Copy `prototype/sideroom/index.html` and `app.js` into the output for the player. Rendering is offline, with 4× internal rate and 44.1 kHz mono PCM16 listening/export WAVs. A single source calibration across all twelve strikes preserves velocity energy. Render costs are recorded in `report.json`; no tens-of-ms native render claim is made. The later native landing must cache accepted source grids or meet the house interactive render budget.

R1 supplies five studies, ten pads each, with three levels and four alternate performances. ZIP packages include complete WAV grids, a manifest and SFZ mapping. Checks cover determinism, finite samples, dynamic ordering, meaningful normalized take differences, distinct kit spectral signatures, endpoint/DC/peak bounds, PCM save/readback, ZIP byte equality and all 127 playable MIDI velocities over nine cyclic selections in the parsed SFZ mappings. The mapping simulator is not a real SFZ sampler or MPC playback proof.

## Gate ledger

| Gate | R1 evidence / remaining work |
| --- | --- |
| Listening | All five studies await owner approval |
| Genre kits on shelf | Five distinct authored studies; native shelf integration remains open |
| Velocity / RR rendering | Three timbral levels × four distinct performances per pad, checked after PCM writing |
| Saving | WAV, manifest and ZIP round trip; native KitStore remains open |
| Playback | Browser player with cycle selection/choke; simulated checks do not establish actual browser/device audio |
| Export | SFZ files and selection mapping checked; actual sampler playback and native MPC export remain open |
| Percussion articulation | Finite one-bar roll and two-bar lift per study, with all twelve variants |
| Melodic / chord / STRUM | N/A to this fixed percussion family |
| Phone and MPC | Not tested |

## Why it is built this way

- The five kits change head tuning, damping and metal identity before processing, so differences can be judged on individual pads.
- The sound starts dry; recording effects can follow after the drums earn approval.
- All takes and dynamic levels travel with the study files, avoiding a convincing preview built from only one hard hit.
- Finite phrases keep authored articulation inspectable. The uncertain part is acoustic realism, particularly cymbal wash and snare wires; listening decides that.

## Audition

The private first-listen page is https://sideroom-audition.bertcalm.chatgpt.site . One kit selector, three banks and four visible pads keep the comparison compact. Each kit has a direct ZIP download containing its WAV grid and SFZ program.

## R1 run evidence

On 2026-11-16, the Python renderer and both validation programs exited 0. All 600 PCM performances pass file, dynamics, take-diversity and finite-output checks. Minimum pairwise normalized seven-band spectral distances across the five kits are 0.0442 for kick, 0.0854 for snare and 0.0448 for closed hat. These measurements show numerical differentiation, not perceived authenticity. Rendering the complete bank, fills and grooves took 81.98 seconds in this environment. The simulated player passes kit selection, three banks, velocity selection, cycling, hat choke, spaced phrase comparisons and Stop. Actual browser audio and sampler/device playback have not been verified.


## R2: recorded acoustic core

The owner rejected the synthesized source character. R2 replaces the audition with four recorded instruments and two treatments: minimally cleaned source and Open Road. These are candidate sources, not a claimed match to the song recording. The other four kits remain deferred until the core is accepted.

Sources are Virtuosity Drums / Versilian Studios, performed by Austin McMahon, CC0 1.0, pinned at `9f04cf9a734527edfbb0a4eee1f674e45bbf71bc`. Downloaded source bytes are checked against Git blob hashes; provenance includes SHA256 hashes and exact selection URLs. No commercial reference recording is sampled. Kick close mic, snare close mic and overhead hats are used without combining mic positions.

Three dynamic levels × three recorded alternatives × four instruments × two treatments produce 72 WAVs. Kick/hats use source levels 2, 3, 4 and original RR 1–3. Snare uses adjacent original recorded strengths 13–15, 23–25, 32–34 as authored RR groups, not purported same-dynamic source round robins. One fixed gain per instrument preserves the recorded dynamics. Source treatment adds 22 Hz cleanup and endpoint fades; Open Road adds broad low-pass filtering and gentle kick/snare tail attenuation. Treatment matching uses energy over the complete instrument grid, never per-hit normalization. Linear filtering runs at 44.1 kHz; this recording study does not need a nonlinear oversampled synthesizer stage.

Rebuild: `python3 prototype/sideroom/acoustic.py /absolute/output`, then copy `acoustic.html` as `index.html` and `acoustic.js` to the output. Requires numpy, scipy and ffmpeg. The source cache is `/workspace/shared/sideroom-acoustic-cache`. Validate with `check-acoustic.py OUTPUT` and `node prototype/sideroom/check-acoustic-player.mjs OUTPUT`.

Verified: 72 PCM files, ordered dynamics, distinct file hashes, endpoint/peak bounds, ZIP byte equality and complete SFZ velocity/RR regions. Simulated player tests cover pad selection, explicit/cycling takes, velocity, hat choke, treatment position and stop. Browser audio, real SFZ playback and MPC/phone playback remain unverified. No native shelf, save/export, full-family or roll/riser gate is closed by this smaller study. The previous R1 gate table describes historical prototype evidence, not acceptance.


## R3: synthesis from measured acoustic descriptors

The owner approved R2's acoustic sound and asked whether synthesis can create it. R3 uses analysis-based spectral resynthesis, not a claimed physically exact drum simulation. The approved Open Road recordings remain the comparator on the same compact audition page.

Offline analysis averages magnitude spectra across three recorded hits at each of three velocities. It stores frequency/time magnitude-envelope knots and energy targets in JSON, without waveform samples or original phase. Runtime interpolates these envelopes over fresh seeded noise STFTs. Kick/snare low resonance peaks receive continuous sinusoidal phase, while the high-frequency texture remains stochastic. Seeded tuning, spectral tilt and time-stretch variation produce new strikes. This is a sample-derived model: it must not be described as independently invented acoustic physics or as playing the original samples. The CC0 attribution/provenance remains with the model and comparator.

The gesture is measured contact spectrum → evolving body/wire/metal resonances → measured decay contour, with fresh excitation each time. Native controls, physical collision behavior, additional kits and native integration are not implemented. This linear band-limited spectral prototype runs at 44.1 kHz without a nonlinear stage requiring oversampling. Serialized models are precomputed once; later native work can cache rendered grids by model/version, velocity, take and accepted controls. No native tens-of-ms claim is made.

Build: `python3 prototype/sideroom/resynth.py R2_OUTPUT R3_OUTPUT`; copy `resynth.html` as `index.html` and the shared `acoustic.js`. Verify with `check-acoustic.py R3_OUTPUT`, `check-resynth.py R3_OUTPUT`, and `node prototype/sideroom/check-acoustic-player.mjs R3_OUTPUT`.

Measured in this environment: 1,415,951-byte model, median Python render 20.65 ms, maximum 143.08 ms. First-0.5-second mean RMS across takes is within 0.3 dB of the acoustic comparator for all twelve voice/velocity combinations. This establishes energy agreement, not perceptual fidelity. Verified 36 deterministic repeat renders, audio-read-free synthesis, normalized alternate-strike differences, 72 saved comparison WAVs, ordered dynamics, ZIP equality, SFZ regions and simulated player behavior. Actual browser/sampler listening is not established by these checks. All full-family/native/hardware gates remain open as recorded above.
