# Neuron-Z1 / SPACE revision 2 handoff

Prepared 2026-11-07. This is an unregistered Kotlin/JVM physical-model prototype.
The immediate next step is Josh's listening verdict on the revised SPACE mapping.

## Start here

- **Private listening page:** https://neuron-z1-audition.bertcalm.chatgpt.site
- **Original full audition:** https://neuron-z1-audition.bertcalm.chatgpt.site/original.html
- **Current comparison ZIP:** https://neuron-z1-audition.bertcalm.chatgpt.site/space-r2-comparison.zip
- **Original reconstructed ZIP:** https://neuron-z1-audition.bertcalm.chatgpt.site/neuron-z1-audition-site.zip
- **Published verification:** https://neuron-z1-audition.bertcalm.chatgpt.site/space-r2/verification.json
- **Repository branch:** `claude/neuron-z1-space-r2-handoff` in `BertCalm/SnipSnap`.

The page defaults to C4, SPACE 100%. Press **Play original → revised**, then
**Play revised 0 → 50 → 100%**. Both variants have equal overall RMS level.
The page also provides five SPACE settings, three note registers, raw WAVs and
downloadable recipes. Private access requires Josh's authorized account.

## Current decision and authorized scope

Josh listened to the first reconstruction and reported: **“I don't think space
is doing anything.”** Inspection found about 94–96% of the SPACE endpoint clips'
energy in their first 100 ms, versus a 714 ms body-motion cycle. The old spectral
test established a difference, but did not establish useful audible motion.

Josh authorized the recommended correction and publication of an original-versus-
revised comparison. That work is complete and the updated page is published.
He has **not yet given a listening verdict on SPACE revision 2**.

The next question is: **Does the movement register at 50% and 100%, and still feel
like one played object, or has it become a filter effect?** Obtain that verdict
before production presets, HOLD, app integration, registry work or MPC kits.
There is no trained neural model, nonlinear reed-valve simulation or brass model.
Do not describe numerical checks or successful browser playback as sound approval.

## What is committed

The handoff branch starts from the DSP toolkit commit used for the audition:
`84471534aef0182f9098c38374a62a5aa0899244`. It adds documentation and an opt-in
prototype folder; it does not register an engine or alter production build files.

| Path | Purpose |
| --- | --- |
| `prototypes/neuron-z1/src/MorphProof.kt` | Original renderer, revised readout, original generator/test and version-1 replay |
| `prototypes/neuron-z1/src/MorphSpaceRevision.kt` | Six revision tests, comparison generation and version-2 replay |
| `prototypes/neuron-z1/prototype.init.gradle` | Opts these files into the synth test source set for that invocation |
| `prototypes/neuron-z1/run.sh` | Serial reconstruction, tests, published-byte comparison and local packaging |
| `prototypes/neuron-z1/verify.py` | Checks WAV formats/hashes, combines test results and assembles a local page |
| `prototypes/neuron-z1/site/index.html` | Final published comparison page, including private-download fix |
| `prototypes/neuron-z1/reference-audio-sha256.json` | SHA-256 of all 146 original and comparison WAVs |
| `prototypes/neuron-z1/evidence/` | Publication-time measurements, test XML, hosted browser checks and deployment records |
| `prototypes/neuron-z1/DESIGN.md` | Detailed rationale and the original workspace's reproduction notes |

The handoff copies change only filesystem plumbing: Kotlin output paths use the
`morph.workspace` JVM property supplied by the init script. The DSP is preserved.
The portable commands below supersede absolute `/workspace/...` commands in the
publication-time design notes. Generated audio and ZIPs live under ignored
`build/neuron-z1/`; they are reproducible and are not added to repository history.

The original session's pre-reconstruction ZIP was inaccessible. The November 1
build was explicitly reconstructed from the supplied conversation script. Its
audio is the reference for this handoff. Do not claim recovery of the inaccessible
archive or compare new package checksums with its historical checksum.

## Reproduce from a fresh checkout

Requires Java, Python 3, the repository Gradle wrapper and dependency access on a
cold machine. Java 21 and Gradle 8.14.3 were used in this environment. An Android
SDK is not required for these JVM checks. Keep proxy and CA trust configured;
never disable TLS verification to make Gradle work.

```sh
git clone --branch claude/neuron-z1-space-r2-handoff \
  https://github.com/BertCalm/SnipSnap.git
cd SnipSnap
bash prototypes/neuron-z1/run.sh
python3 -m http.server 8765 --directory build/neuron-z1/site
```

Open `http://localhost:8765`. Stop that local server when finished.
Run Gradle invocations serially; overlapping builds previously caused Kotlin
cache conflicts in this checkout.

The runner first regenerates the original audition and checks its 86 WAVs
against the saved published hashes. It then runs the six revision tests and
36 existing StringsTest tests. Finally it checks all **146 WAVs** and packages
the local audition. Its two invocations cover **43 distinct tests**: one original
prototype test, six revision tests and 36 StringsTest tests.

This repository-based runner was validated on November 7: all 43 tests passed,
all 146 WAV hashes matched the published audio, and standalone version-2 replay
produced a byte-identical WAV. The fresh handoff result is preserved in
`prototypes/neuron-z1/evidence/handoff-portability.json`.

Fresh results are in `build/neuron-z1/verification.json` and `checks/`. Timings
and ZIP timestamps can change on another machine, so a rebuilt ZIP may have a
different checksum. The pinned WAV hashes establish whether the audio changed.
Do not weaken or update those hashes just to make a failing render pass.

### Replay one comparison recipe

After the runner has generated the recipes, use a new output filename:

```sh
bash ./gradlew --no-daemon --max-workers=3 \
  -I prototypes/neuron-z1/prototype.init.gradle :synth:replayMorphSpace \
  -PmorphRecipe="$PWD/build/neuron-z1/space-r2/recipes/revised-60-1.0.json" \
  -PmorphOutput="$PWD/build/neuron-z1/my-replay.wav"
cmp build/neuron-z1/my-replay.wav \
  build/neuron-z1/space-r2/matched/revised-60-1.0.wav
```

Version-1 recipes use `:synth:replayMorphProof` with the same property names,
for example `build/neuron-z1/audition/recipes/model-0.5.json`. Version-2 comparison
recipes specify renderer `morph-space-r2`, variant `original` or `revised`, and
`raw_gain` for exact comparison-level replay. Unknown fields/versions/variants,
HOLD, invalid numeric ranges and existing output files are refused.

## Sound and implementation contract

The characteristic gesture is picked attack → breath-driven sustain → moving
body color → source release and decay. Both excitation types feed **one tuned
waveguide**; this does not crossfade complete instruments. Breath is bounded
colored-noise excitation, not a self-oscillating reed valve.

| Parameter | Default | Role |
| --- | --- | --- |
| MIDI | 60; range 48–72 | Explicit root |
| MODEL | 0.4 | Excitation balance and body centers |
| SPACE | 0.5 | Body-readout motion depth |
| STIFF | 0.6 | Tuned dispersion and round-trip loss |
| BRIGHT | 0.5 | Excitation and loop bandwidth |
| Velocity | 0.7 | Excitation work and output target |

The original readout remains the renderer's default for recipe compatibility.
Revision 2 is explicitly selected as `EARLY_MOTION`. It makes a smooth
dark–bright–dark excursion during 0–90 ms, peaks at 45 ms and returns to the
MODEL center by 180 ms. A narrower dark readout and stronger bright readout
increase contrast. A direct guide component retains the root. SPACE uses a
square-root depth curve; **SPACE 0 is byte-identical across variants**.

Readout motion does not feed back into or retune the guide. Source-off remains
0.55 seconds. Output is mono, 44.1 kHz, 24-bit WAV, 61,740 frames / 1.4 seconds,
with internal rendering at 176.4 kHz. The seed stays
`Dsp.seedFor("morph-proof", 1, midi)`. Only immutable tuning coefficients are
cached, at most 32 entries; every render has fresh delay/filter state.

The house band-limit, decimation, DC-removal, leveling and tail-fade sequence
remains. Comparison audio instead applies one scalar to each complete raw clip
for a common RMS target, lowered once to keep every comparison peak at or below
0.9. No compression or per-window normalization is used. The original audition's
house-levelled files and archive remain intact.

## Verification and limits

Publication-time checks on November 7:

| Check | Result |
| --- | --- |
| Targeted revision suite | 42 passed: six revision + 36 StringsTest; zero failed/skipped |
| Original regression | All 86 published original WAVs reproduced byte for byte |
| Comparison | 30 takes / 60 WAVs; all recipes replayed byte-identically |
| Parameter edges | 50 cases |
| Maximum absolute corner pitch error | 28.5376361 cents; gate 35 cents |
| Minimum corner pitch confidence | 0.46549967; gate 0.3 |
| Maximum conditioned corner peak | 0.98231643 |
| Maximum guide peak | 0.7079118 |
| Maximum absolute output mean | 4.8456067e-7 |
| Maximum late/source-on guide-energy ratio | 7.0674977e-7 |
| Comparison RMS | Approximately 0.026803387 across all 30 takes |
| Maximum comparison peak | 0.9 |
| Hosted browser | Nine playback checks; 65 asset integrity checks; 15 selector combinations |
| Browser interactions | A/B, sweep, stop, mobile overflow and recipe/ZIP downloads passed |

The motion test compares the moving revision against the same readout frozen
at its starting position. It measures high/low spectral-energy ratios in three
early windows and requires a time-varying difference above 3 dB at SPACE 0.25,
0.5 and 1. The measured ranges were 3.476, 3.861 and 3.669 dB. A gain-only
counterexample must fail that criterion. This establishes changing color,
not subjective usefulness or approval.

No five-cent pitch guarantee, Android performance result, full application-suite
rerun, separate higher-rate aliasing comparison or subjective sound approval is
claimed. Earlier onboarding counts are not evidence for this change.

## Published artifacts and deployment

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Original reconstructed ZIP | 8,514,629 | `2b351c80a1e6f981642faafa10411ac4c1f3e00aee2756709dff69a233699087` |
| Final SPACE comparison ZIP | 23,316,522 | `1fed4d4dec343d2bf0d9646848567a2224090365da969559ffc5d85ab5ce091f` |

The final comparison ZIP includes source, recipes, both auditions and the
original reconstructed ZIP. Both downloads were verified through the hosted
page. Do not substitute local `/workspace/...` links as proof of delivery.

- Site project: `appgprj_6ae75de4e8888191a8bd48a755765e00`.
- Final Sites source commit: `f9d58e6e7542161ddc67099c8335394c78ad6156`.
- Final deployment: `appgdep_6aeec5350ea0819191fad56d5904678f`.
- Saved version: `appgprj_6ae75de4e8888191a8bd48a755765e00~appgver_d3b72dbff5a88191b2e772e2c5d49593`.
- Visibility: owner-private. Preserve it on updates.

The Sites source repository is separate from `BertCalm/SnipSnap`. Its source
commit above is not a SnipSnap commit. In the original workspace the checkout is
`/workspace/sites/neuron-z1-audition`, with `dist/` configured in
`.openai/hosting.json`. The working prototype remains at `/workspace/neuron-z1`.
A fresh session must not assume either directory exists; use this handoff branch
or obtain the Site's source through its existing project ID.

For further publication, reuse that Site, acquire a fresh source write credential,
push the exact source used for packaging, then use the native private deployment
operation with its commit and archive. Credentials must remain out of files,
logs and this document. A native successful deployment verifies publication;
repeat playback and download checks for changed assets.

Direct downloads canceled in the automated private-site browser. The shipped
page fixes this by fetching same-origin files through the authenticated page,
then saving a Blob. Keep that handler when editing the page. Do not weaken
private visibility or rotate authentication merely to make testing easier.

## Next engineer's first actions

1. Check out this handoff branch and read the current listening status above.
2. Open the live comparison and obtain Josh's verdict on revised SPACE.
3. If further DSP work is requested, run the opt-in runner before editing and
   preserve the original WAV hashes as regression references.
4. Keep any next candidate explicit and comparable. Update measurements and
   versioned recipes if the sound changes; never silently replace original files.
5. Expand into production only after a separate decision. BORE and EMBOUCHURE
   remain the nearest shipped siblings; this proof has not established that it
   deserves a distinct production engine.
