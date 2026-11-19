# SIDEROOM: five acoustic drum-kit studies

Status: R1 rejected by the owner for drum-machine character. R2 recorded acoustic core was approved for sound. R4 synthesis was approved for sound. R5 eight-instrument Open Road was approved. R6 Tight Pocket was approved. R7 Angular was approved. R8 High Voltage was approved. R9 introduces Deep Pocket, completing the five sound-study candidates. No SnipSnap shelf, native serialization or hardware completion claim. Source inspected at `4ecab4c9f`; isolated implementation starts from `e7ccfef58` on `claude/sideroom-study`.

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


## R4: contact, wires and metallic modes

The owner called R3 a better direction and asked to continue. R4 retains R3 and the approved R2 acoustic bank as selectable comparators. The current renderer builds with `python3 prototype/sideroom/resynth.py R2_OUTPUT R4_OUTPUT R3_OUTPUT`; the R3 renderer remains available at commit `cca4590b9`.

A second 256-sample analysis window captures the first 65 ms at 1 ms envelope landmarks. Fresh noise shaped by that descriptor replaces upper-frequency contact over the first 32 ms, while the kick/snare low body remains continuous. Snare upper energy gets irregular, seeded 1.8 ms wire-contact bursts that fade over 160 ms. These bursts are an authored collision analogy, not source waveform recovery. Each hat velocity profile has 40 measured inharmonic modes from an 8192-sample analysis. Continuous oscillators carry 24% of the nominal component energy alongside stochastic wash. The final result is calibrated to the measured dynamic energy; no original phase or waveform is stored in the model. A final 18 kHz low-pass restrains the upper spectrum.

The site offers Refined synth, Previous synth and Acoustic with the same authored groove and complete grids. Each mode retains three levels and three takes; 108 WAV performances total. The same file/export checks pass, plus runtime audio-read isolation, determinism, meaningful normalized take differences and simulated player checks. Actual browser/device audio remains unverified by automation.

Measured: 2,616,902-byte parameter model, Python median render 27.72 ms and maximum 425.03 ms, with no peak safety attenuation required. Long open-hat tails cost more; pre-rendered grids remain the proposed native cache strategy. Full-family and native gates remain open. This round tests texture development, not completion or an assertion of acoustic equivalence.


## R5: eight-instrument Open Road kit

The owner approved R4 and asked to continue on this path. R5 adds rack tom, floor tom, ride and crash using the same descriptor-driven synthesis. Core synth and acoustic files are copied byte-for-byte from R4; the core descriptor profiles are unchanged. The new source analysis uses the same pinned Virtuosity Drums CC0 repository. Toms use the mid microphone, adjacent dynamics 4–6, 9–11 and 14–16; ride/crash use overhead dynamics 1–3 with recorded RR 1–3. These are new targets awaiting approval, not a claim to reproduce the commercial song's exact kit.

The drum-body phase treatment extends to toms. The forty-mode metallic oscillator/wash treatment extends to ride and crash. Source tails are bounded at four seconds for toms and ten for cymbals, with fifty-millisecond ending fades. Native rendering uses no source audio reads; this remains a Python spectral-synthesis prototype. Generated alternates and three measured velocity profiles exist for every new voice.

The page exposes four pads at a time in Toms & cymbals / Approved core banks. The four-bar groove moves from hats to ride, then a tom fill and crash. A separate short fill provides a direct articulation comparison. These two example phrases do not close the eventual authored roll/riser or native articulation gates.

Build: `python3 prototype/sideroom/expansion.py R4_OUTPUT R5_OUTPUT`; copy `expanded.html` as `index.html` and `expanded.js`. Tests: `check-acoustic.py OUTPUT`, `check-resynth.py OUTPUT` and `node prototype/sideroom/check-expanded-player.mjs OUTPUT`. The pinned source index is `expansion-sources.json`; downloaded FLAC Git blob hashes are verified by the shared acoustic fetcher and SHA256 provenance is retained.

Verified: 144 WAV performances across synth and acoustic modes, all 72 core comparison files byte-identical to R4, monotonic velocity energy, generated take differences, deterministic rerenders, endpoint and peak bounds, complete eight-note SFZ velocity/RR mappings, ZIP byte equality, and simulated routing of every performance through both pad banks. Real browser/device/sampler audio remains unverified. Median new-voice Python render was 162.85 ms, maximum 427.46 ms. Native work needs cached banks; no native latency claim. Other genre kits, native shelf/save/playback/export and hardware gates remain open.


## R6: Tight Pocket alongside approved Open Road

The owner approved R5. R6 preserves all 72 Open Road synth pad files byte-for-byte and adds an eight-instrument Tight Pocket candidate aimed at the Train in Vain reference. Both selectable kits are synthesized. The same groove/fill and 100 BPM tempo isolate timbre differences. The previous acoustic comparator remains in R5 assets/history.

Tight Pocket analyzes a separate hand-muted snare articulation from the same pinned CC0 library: adjacent source levels 4–6, 9–11 and 14–16, verified by blob hash and recorded in `pocket-sources.json` and provenance. Other instruments adapt accepted descriptors with authored tuning ratios (1.03–1.14), decay ratios (.56–.83), and gentle frequency-dependent excitation weights. Changes happen in the parameter model before generating new noise/oscillator strikes. No exact commercial reference equipment match is asserted. The shared download helper now retries transient source-network failures.

Build: `python3 prototype/sideroom/pocket.py R5_OUTPUT R6_OUTPUT`; copy `family.html` as `index.html` and `family.js`. Tests: `check-acoustic.py OUTPUT`, `check-resynth.py OUTPUT`, and `node prototype/sideroom/check-family-player.mjs OUTPUT`. Both models, complete WAV grids, SFZ mappings and provenance travel in the package. Median new Python render was 23.27 ms, maximum 318.25 ms; no extra peak calibration was required.

Verified: 144 saved performances, 72 unchanged approved pads, three ordered velocities and three distinct generated takes per voice, deterministic/audio-read-free synthesis, complete SFZ mappings, ZIP byte equality, and simulated player routes. A representative hard-take energy-decay probe (time containing 90% of energy) falls from .097 to .060 s for kick, .035 to .024 s for snare, and .498 to .286 s for open hat. All eight Tight Pocket instruments measure shorter. These measures do not establish perceptual approval or exact reference matching.

Tight Pocket awaits listening approval. Angular, High Voltage and Deep Pocket remain unbuilt in the accepted synthesis approach. Native shelf, save/playback/export integration, authored roll/riser requirements and hardware checks remain open; two listening kits do not close those gates.


## R7: Angular

The owner approved Tight Pocket and asked to proceed. Angular is the third candidate, guided by the Fashion reference: lower dry kick, high rimshot snare, short toms and precise metal. The R6 builder now accepts an explicit kit configuration, so this kit shares the descriptor/synthesis/export path instead of duplicating it. `angular.py` supplies the configuration; R6 defaults remain supported.

A separate snare rimshot analysis uses original source levels 3–5, 6–8 and 10–12 from the same pinned CC0 library. `angular-sources.json` pins the downloaded blob hashes; provenance stores source SHA256 and URLs. Angular uses the accepted Open Road base descriptors, not compounded Tight Pocket transformations. Authored frequency ratios range .88–1.25 and decay ratios .46–.62. The rimshot has its own three recorded dynamic groups; synthesis generates new alternate strikes.

Build: `python3 prototype/sideroom/angular.py R6_OUTPUT R7_OUTPUT`, then `python3 prototype/sideroom/package-family.py R7_OUTPUT`. Copy `family.html` as `index.html` and `family.js`. Deploy all output except the combined `open-road-acoustic.zip`, which exceeds Sites' 32 MiB blob limit. Three individual ZIPs include each kit's full WAV grid, SFZ, source notice/license, parameter model and filtered manifest; their WAV bytes are checked against the rendered files.

Verified: 216 performances and all player routes, complete SFZ velocity/RR selection, runtime audio-read isolation, deterministic synthesis, monotonic dynamics, generated take variation and all 144 approved pad files byte-identical to R6. Representative hard-take low-spectrum maxima measured at 75.4 Hz for Angular kick and 418.6 Hz for Angular snare, versus 91.5/267.8 for Tight Pocket and 86.1/230.1 for Open Road. These are spectrum peaks, not asserted physical fundamentals or proof of perceived character. Median new render 22.10 ms, maximum 255.44 ms; no extra peak calibration required.

Angular awaits listening approval. High Voltage and Deep Pocket are still unbuilt in this accepted synthesis path; the native shelf, serialization/playback/export, authored articulation and hardware gates remain open. All genre references are listening/design targets, not sampled commercial audio or claimed equipment reconstructions.


## R8: High Voltage

The owner approved Angular. High Voltage is the fourth candidate, guided by the I Believe in a Thing Called Love reference: lower shells, a broad off-center snare, and longer cymbal sustain. Three previously approved kit grids and models are retained byte-for-byte. The shared family builder now carries an explicit kit-to-model manifest mapping, fixing the prior two-kit-specific model-copy assumption before it could mislabel a model in the fourth-kit package.

`voltage-sources.json` pins off-center snare source levels 13–15, 23–25 and 32–34 from the same CC0 Virtuosity Drums commit. This is distinct from Open Road's center strike, Tight Pocket's muted strike and Angular's rimshot. Other source descriptors start from Open Road, with authored pitch ratios .82–.96 and decay ratios 1.12–1.40. Runtime remains generated noise/oscillator synthesis from magnitude descriptors; no source waveform playback. These are design choices, not measurements of the reference's equipment.

Build: `python3 prototype/sideroom/voltage.py R7_OUTPUT R8_OUTPUT`, then `package-family.py R8_OUTPUT`. The same family page and test commands apply. Mixer output now accommodates complete selected hit tails, and comparison phrases are padded to the longest kit so switching playback position remains valid. This does not change approved pad PCM. Deploy individual kit ZIPs, not the oversized combined ZIP.

Verified: 288 saved performance routes, complete eight-note SFZ dynamic/RR mapping, finite/peak/endpoint checks, ordered dynamics, deterministic sample-free runtime, generated take variation, per-kit ZIP byte equality, 216 approved pad files and all three prior descriptor models unchanged. Simulated controls cover all four kits, both banks, fill, hat choke and stop; actual browser/device/sampler audio remains unverified by automation. All phrase durations match across kits and end cleanly.

Representative hard-take 90%-energy times: High Voltage kick .116 s versus Open Road .097; snare .094 versus .035; floor tom .352 versus .262; crash 1.313 versus .929. These support the intended longer decay; listening decides the character. Median new Python render 55.14 ms, maximum 717.72 ms. Long tails require cached native renders rather than a promised interactive latency.

High Voltage awaits listening approval. Deep Pocket is the remaining family sound study. Native shelf, save/playback/export integration, complete authored articulation and hardware acceptance remain open.


## R9: Deep Pocket and full five-kit audition

The owner approved High Voltage. Deep Pocket is the fifth candidate, guided by Get on Top: firm kick, high snare bite, ghost-note sensitivity and active hats. Three snare layers intentionally use different original articulations: center levels 13–15 for soft strikes, off-center 23–25 for medium, rimshot 10–12 for accents. The source index and SHA256/blob provenance retain this distinction. These are measured velocity descriptors with newly generated alternates, not commercial song samples or original waveform playback.

Authored tuning/decay ratios are in `deep.py`. The high-tuned snare has more sustain than Angular; the kick remains firmer and shorter than Open Road. The new Pocket groove uses an original syncopated kick pattern, ghost notes and opening/closing hats. It is rendered identically in event timing and level for all five kits. Original groove and fill remain selectable. No phrase is a transcription or claim of matching reference tempo.

Build: `python3 prototype/sideroom/deep.py R8_OUTPUT R9_OUTPUT`; run `pocket-groove.py`, then `package-family.py`. Copy `family.html`/`family.js`, then use `stage-family.py R9_OUTPUT SITE_DIST`. The staging helper compresses prior loose model JSON losslessly, records gzip encoding and model paths in the website manifest, and checks the deployment size limit. Per-kit ZIPs retain plain original model JSON with a kit-specific model mapping. No audio is recompressed or changed by this staging step.

A fixed output-gain field is now retained in new model serialization and honored by synthesis. Deep Pocket snare needs 0.989767 fixed attenuation for headroom; all its velocity/take differences remain intact. This closes the prototype mismatch between raw model rerender and calibrated PCM export. It does not claim native KitStore completion.

Verified: all 360 sample routes, three ordered velocities and three distinct alternate strikes per instrument, finite/peak/endpoint checks, complete SFZ regions, deterministic audio-read-free synthesis, per-kit ZIP byte equality, simulated Pocket groove routing and hat choke. All 288 approved pads and four prior models are unchanged. Rerendering all 72 Deep Pocket performances from saved parameters exactly reproduces exported PCM. All comparison phrases have matched lengths and clean endings. Median new Python render 29.27 ms, maximum 404.11 ms; native caching remains necessary for long tails.

All five requested sound-study candidates now exist. Deep Pocket still awaits listening approval. Native shelf/registration, save/playback/export round trips, complete authored roll/riser behavior where appropriate, and phone/MPC checks remain open. The Python audition and SFZ simulation do not close these gates.


## Native cached-bank integration — 17 November 2026

All five eight-voice synth kits have owner listening approval. `SideroomKits` v1 adds them to the SKINS shelf with ten pads each: the approved eight hits plus a finite snare roll and tom riser. The 360 approved PCM16 source renders are losslessly compressed resources, with SHA256 checks, descriptor models and CC0 provenance. Native Kotlin assembles the phrases and loads the cached synth bank; this is not a port of the editable Python spectral synthesizer. The cache holds at most twelve snips and returns defensive sample copies.

Every pad has three velocity layers and three takes: 450 native performances in total. Native MIDI zones are 0–41, 42–84 and 85–127, following KitAssembler; the earlier audition SFZ used different boundaries. Standard native pad level and velocity gain apply. Roll/riser gain is fixed across each kit’s nine performances, preserving their dynamics. New phrases still require owner listening approval.

Focused SideroomKitTest and StarterShelfTest pass. Generator checks cover all five kits, KitStore save/load, source replay, two complete take cycles, PCM health, distinct velocity/take character, MPC3 chain PCM and slice metadata, and MPC2 export read-back. MPC2 retains only the first take per velocity layer under the existing exporter limitation. Physical phone/MPC playback remains unverified; no Android SDK is available here.

Regenerate resources with `python prototype/sideroom/native-bank.py /workspace/shared/sideroom-r9 synth/src/main/resources/sideroom/v1`. Run `./gradlew --no-daemon :shell:test --tests com.snipsnap.shell.SideroomKitTest --tests com.snipsnap.shell.StarterShelfTest :shell:generateSideroomKits`. Package with `python prototype/sideroom/native-delivery.py testkit/sideroom-native /workspace/shared/sideroom-native-delivery`; this independently checks actual MPC2 velocity/slice windows and ZIP payload round trips. Generated testkit output is not source.

Native audition: https://sideroom-audition.bertcalm.chatgpt.site . This edition focuses on the new phrases, with original comparison grooves retained and five native kit ZIPs. The previous full single-hit audition is retained in Site source history at `95910084506ef75d8572556c236f27b64809148e`. `check-native-player.mjs` verifies all 90 phrase routes, 15 groove routes, downloads, cycling and stop with simulated audio; no browser/device listening claim. ZIP compression is level 9 to fit the host’s 25 MiB per-file limit without changing payload bytes.

Stage future native pages with `python prototype/sideroom/stage-native.py NATIVE_DELIVERY R9_GROOVES NEW_SITE_DIST`, then `node prototype/sideroom/check-native-player.mjs NEW_SITE_DIST`. The staging script refuses an existing output directory and validates assets and hosting size limits before writing.

The optional full `./gradlew --no-daemon test` sweep was stopped before completion after its shelf-wide rendering checks took an extended run. It is **not a full-suite pass**. It exposed stale README starter counts (162 → 167), now corrected; the stable-ID expectation was also extended with the five literal SIDEROOM IDs. Full repository regression remains required before a future GitHub push. No SnipSnap GitHub push or Android build is claimed for this pass. The native publication is Site source commit `09df29072ce9c8ca1dc7bbdd378f7a1ff5db871f`, deployment `appgdep_6afc6ef935e48191a5dbe1aba22572e7` (succeeded).

Final affected-check re-run: `./gradlew --offline --no-daemon :shell:test` selecting SideroomKitTest, StarterShelfTest, the README roster-count test and the stable starter-ID test **passed** (7 tests, 34 seconds). Offline mode used cached dependencies after a dependency-server connection stalled; test selection and assertions were unchanged. Full repository regression remains incomplete as noted above.


## Native model 2: replace riser with fill — 18 November 2026

Owner accepts the roll and asks to replace the riser with a fill. Starting source: `163733483`. Slot 10 is now Drum Fill: a scored one-bar snare/rack/floor phrase at each kit’s tempo, with five different sixteenth-note patterns. Rests and fixed accents make a phrase; the kit’s existing synthesized hits retain its sound. No acceleration or end crash is added, leaving the next downbeat to the groove. All three velocity layers and three takes remain. Core hits and the approved roll are preserved.

Model 2 metadata identifies the new fill. Model 1 replay retains the old riser, so saved recipes keep their meaning. Both models use the existing v1 source bank; this changes the authored score, not the spectral synthesis or cached hits. Fixed gain across the nine performances keeps velocity dynamics. The fill awaits listening approval; full repository and hardware/export limitations remain open as previously documented.

Validation: six selected Kotlin tests and the native delivery generator pass (40 seconds): SideroomKitTest, including model-1 replay and unchanged roll PCM, plus StarterShelfTest. All 450 performances pass the existing native save/playback/export software checks. Packaging verifies 150 actual MPC2 velocity/slice windows and ZIP bytes. External byte comparisons against the previous published packages verify all 45 native chains for pads 1–9 (405 performances) and all 45 roll audition WAVs are unchanged. The simulated player check passes all 90 phrase and 15 groove routes, downloads, cycling and stop. This is a focused revision check; no new full-suite or hardware pass is claimed.


## Model 3: promote Original fill — 18 November 2026

Owner prefers the original audition fill to the model-2 replacement. Model 3 promotes that exact phrase into pad 10: eight sixteenth-note snare/snare/rack/rack/floor/floor/snare/floor hits, then simultaneous kick and crash, at 100 BPM. This is the existing two-beat gesture plus its full cymbal tail, not a new one-bar interpretation. Medium/take 1 preserves each kit’s approved original WAV exactly. Remaining velocity layers and takes use the same score and per-instrument take cycling from synthesized source hits. Soft uses soft timbres; hard uses hard timbres throughout; medium preserves the alternating hard/medium accents. Fixed per-layer gains retain ordered energy with no per-take normalization.

The generator `prototype/sideroom/original-fill-bank.py` freezes 45 synthesized phrases into `sideroom/fills-v3`, with hashes and measured layer energy. Kotlin loads and checks them through the bounded cache. Original audition files are the reference, not commercial audio. Model-1 riser and model-2 fill replay remain available to saved recipes. New recipes explicitly record the fill’s 100 BPM. The roll and eight core pads retain their approved PCM. The page uses one Original fill pad instead of competing old/new fill buttons.

Verification: six selected Kotlin tests and generation of all five native kits passed in 52 seconds. All 450 native performance variants pass software save/playback/export read-back. Packaging checks 150 MPC2 windows and ZIP payload bytes. Each published medium/take-1 fill is byte-identical to its original audition WAV; native PCM24 exported slices round back to the exact original PCM16. All 405 core/roll performances and 45 roll preview WAVs remain unchanged. The simulated player check covers 90 phrase routes, ten groove routes, downloads, take cycling and stop. Full-suite and hardware/export limitations remain open.

## Discrete cycling delivery — 19 November 2026

The MPC3 drum writer previously ignored `Pad.cycleLayers`, writing only the headline sample. It now writes every discrete take in the supplied order with `zonePlayTime=0` and full per-file windows. The default chain export is unchanged. `Mpc3Exporter.splitRoundRobinTakes` is opt-in; it uses the existing byte-preserving single-zone splitter and can emit the matching XPM beside the same WAVs.

`SideroomDelivery.exportCycles` produces three export-only companions per kit: Soft, Medium and Hard. Each preserves the ten-pad layout, choke groups and three authored takes. Choosing a program chooses its fixed touch timbre. All 90 performances are available across the three programs, without reliance on the unimplemented embedded chain slice map. This is **not** the combined velocity × round-robin gate passing. Original native kits retain their full replayable source grid; companion provenance explicitly identifies the fixed-touch projection instead of pretending to be the original full recipe.

The primary native grid remains available. MPC2 still keeps its first take per velocity zone; MPC3 chain cycling still needs a hardware-written slice-map fixture (BENCH C / HH1.4). Physical playback of the discrete programs is also not yet verified.

Generate with `:shell:generateSideroomKits`; package the new companions with `python prototype/sideroom/package-cycles.py NATIVE_OUTPUT CYCLE_DELIVERY`. Stage the page with `python prototype/sideroom/stage-native.py NATIVE_DELIVERY R9_GROOVES NEW_SITE_DIST CYCLE_DELIVERY`. The new cycling ZIPs include both formats, one copy of every take and license notices. The audition's approved audio is unchanged.

Focused validation: 50 exporter/delivery tests passed, plus generation of all five native kits (1m 5s). The new companion test reads actual ACVS and XPM descriptors, checks the Cycle setting, take order, full-range velocity windows, choke groups and complete sample windows, then compares all 450 exported WAV performances to the original native grid. ZIP payload round trips pass. All audition WAVs and pre-existing native downloads remain byte-identical. The simulated player check passes all 90 phrase routes, ten groove routes and ten download links. This proves software serialization and sample fidelity, not MPC hardware behavior.

Published cycling delivery: Site source `3e0fd64ce58cea85d6eb5b9b38a1261825003a50`, deployment `appgdep_6afe6e726f388191b4705d39e97017d6` succeeded. The expanded page stays below the hosting limit at 247.63 MiB. The full JVM regression is being run separately from the focused checks; its result must be recorded before claiming a full-suite pass.
