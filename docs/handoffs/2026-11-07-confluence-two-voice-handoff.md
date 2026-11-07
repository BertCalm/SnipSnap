# SnipSnap Confluence: two-voice handoff

Prepared November 7, 2026. **Josh approved the generated solo and confirmed that A + the generated voice reads as two singers.** The approved result is a standalone listening prototype. Porting it into the Android app remains the next implementation milestone.

The characteristic gesture is **one recorded vowel → a second voice with its own pitch movement and vocal color → two independent entrances → a finite release**.

## Start here

1. Listen to [the published six-clip comparison](https://confluence-snipsnap-audition.bertcalm.chatgpt.site/voices/two-singers/), especially **Generated from A · alone** and **A + generated voice**.
2. Read this document, `AGENTS.md`, and the repository's Luthier and Steward skills.
3. Fetch the latest default branch before production work. The previous selected-bank [PR #811](https://github.com/BertCalm/SnipSnap/pull/811) is merged; do not resume its stale implementation branch or replace current app files with old snapshots.
4. Reproduce the packaged prototype, then introduce an explicit, versioned Confluence rendering path for qualified held vowels. Keep existing recipes reproducible.

| Reference | Dated state |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Default head inspected for this handoff | `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd` |
| Handoff branch | `claude/confluence-two-voice-handoff` |
| Original DSP snapshot used in the audition | `5c028180fa7fd025618197ff1e4154f6262c6c11` |
| Published audition source commit | `aae8ed6141b34f2a572d8b9ef905dde093a0b944` in the separate Site checkout |
| Audition publication | November 6, 2026, 22:34 UTC; existing audience retained |
| Portable package | [assets/2026-11-07-confluence-two-voices/](assets/2026-11-07-confluence-two-voices/) |

This commit preserves documentation, source, recordings and evidence under `docs/handoffs/`. It changes no production DSP, app screen, registry, source bank or APK. The earlier My Voice and selected-bank implementations are already present on the inspected default branch. Their documentation is in [CONFLUENCE_MY_VOICE.md](../CONFLUENCE_MY_VOICE.md), [CONFLUENCE_SELECTED_BANK.md](../CONFLUENCE_SELECTED_BANK.md), and [CONFLUENCE.md](../CONFLUENCE.md).

## Listening decision and its scope

Josh first reported that the earlier ensemble sounded like stereo widening rather than multiple singers. The new comparison removes stereo placement and room, supplies a real independent A+B reference, and tests a generated counterpart with independently scheduled pitch pulses.

Josh said **“The generated voice sound pretty good”**, then answered **“Yes. I want to hand this off commit and give me a md handoff doc”** to the question of whether A + generated voice also feels like two singers together. Record this as owner approval of these particular solo and paired examples on November 7.

The scope is a comfortable, near-C4, held `/a/` body. It does not establish quality for words, breaths, changing vowels, full phrases, high notes, a keyboard range, or cultural singing gestures. Those need separate auditions. The assistant's numerical checks do not substitute for this listening decision.

The frozen November 6 `README.txt`, manifest and reports still say listening is pending or contain `ownerListeningVerdict: null`. Preserve them as dated evidence. **This section records the later owner verdict; it does not retroactively change the approved audio or frozen manifest.**

## What is in the package

The package is self-contained: two raw source crops, six delivered WAVs, provenance and CC BY 4.0 text, expanded Kotlin/Java source, the original generator ZIP, Python finalizer, independent audit scripts/reports, and the published page's HTML/CSS/JavaScript.

| Clip, in listening order | Purpose |
| --- | --- |
| `audio/a-alone.wav` | Actual recorded singer A, native pitch |
| `audio/b-alone.wav` | Actual independent singer B, native pitch |
| `audio/a-plus-a.wav` | Same-take doubling control using the stock TEXTURE primitive |
| `audio/a-plus-b.wav` | Separately recorded A+B, constructed dry reference |
| `audio/synthetic-b-alone.wav` | Approved generated counterpart, made from A |
| `audio/a-plus-synthetic-b.wav` | A plus exactly that generated counterpart |

All six are mono, 44,100 Hz, PCM24, 132,300 frames, 3.000 seconds. Source bodies are two seconds. The final 250 ms is exactly silent. Components are matched first to −22 LUFS integrated using FFmpeg EBU R128 and constant gain; mixtures use equal-power summing, then each complete clip receives a final constant matching gain. There is no room, stereo placement, harmony, compressor or limiter. Measured loudness matching does not guarantee identical perceived loudness for different vocal colors.

`manifest.json` is the authority for each delivered WAV's SHA-256 and processing chain. The A+A row isolates the existing doubling primitive in this short window; it is not an exact replay of the app's complete 30-second PAIR arrangement.

## Sources and rights

Both raw crops come from **VocalSet: A Singing Voice Dataset**, Julia Wilkins, Prem Seetharaman, Alison Wahl and Bryan Pardo (2018), [Zenodo record 1193957](https://zenodo.org/records/1193957), **CC BY 4.0**. Attribution and the full license travel with the package. Retain credits and identify derivatives when distributing the source recordings or processed audio. These are anonymized dataset identifiers, not personal names or endorsements.

| Source | Original archive member | Half-open original crop | Measured native median |
| --- | --- | --- | --- |
| A, `male3`, straight `/a/` | `FULL/male3/long_tones/straight/m3_long_straight_a.wav` | Frames 220500–308700, 5.00–7.00 s | 256.19076853797463 Hz |
| B, `female1`, straight `/a/` | `FULL/female1/long_tones/straight/f1_long_straight_a.wav` | Frames 50715–138915, 1.15–3.15 s | 260.7071119744289 Hz |

The raw crops preserve original levels and pitch. Their only changes are the exact crop and lossless PCM16-to-PCM24 integer expansion. No fade, denoising, pitch correction or gain is baked into them. They have active cut edges; declared finite audition envelopes are a separate processing stage. These are body slices, not complete naturally released gestures. The singers were recorded separately, not as a live duet.

**The generated waveform uses A only.** B supplies a single constant median-frequency reference for this controlled study. B's waveform, changing pitch contour and vocal identity are not used to generate the candidate. No trained model, new recorded person, generated breath or text articulation is involved.

See `public-sources.json` and `sources/ATTRIBUTION.txt` for original/crop hashes, exact provenance and numerical qualification. A personal recording must retain its own ownership/provenance; it must not inherit this dataset's license or curated-source eligibility.

## The DSP change that matters

Read [src/IndependentSinger.kt](assets/2026-11-07-confluence-two-voices/src/IndependentSinger.kt) first. It is an audition adapter, not shipped app code. The adjacent `ConfluenceVoice.kt` is an unchanged production snapshot from `5c028180f`; the shim supplies its constants/enums for standalone compilation. Do not copy that old renderer over the latest repository file.

```text
A waveform + A epoch analysis
  → choose input grains using A's local epochs and periods
  → alter pulse/grain color with fixed scale 1.08
  → place output grains on a separate target-frequency clock
  → source chronology ratio 0.992 + voiced/unvoiced blend
  → finite envelope + component matching
  → generated solo, or sum with A + final clip matching
```

The critical separation is between **input grain selection** and **output pulse timing**. A's local periods may select and size source grains. They must not silently remain the generated voice's output pitch curve. In the adapter, output spacing is `44100 / targetHz(t)` samples.

For elapsed output seconds `t`, this version uses:

```text
grown = 1 - exp(-6 * max(0, t - 0.12))
cents(t) = 8.5 * grown * sin(2π * (5.23*t + 0.31))
         + 3.5 * sin(2π * (0.61*t + 0.07))
         + 2.0 * sin(2π * (1.37*t + 0.62))
targetHz(t) = 260.7071119744289 * 2^(cents(t) / 1200)
```

| Prototype parameter | Exact setting / limit |
| --- | --- |
| Adapter version | 1 |
| Pulse/grain color scale | `1.08f`; private ablation uses `1.00f` |
| Source chronology ratio | `.992f` |
| Input period clamp | 72–680 samples, inherited from the stock method |
| Base-frequency guard | 130–300 Hz; a code bound, not proof of quality across that range |
| Color-scale guard | 0.9–1.1 |
| A entrance / attack / release | 0.200 s / 60 ms / 140 ms |
| B and generated entrance / attack / release | 0.245 s / 90 ms / 180 ms |
| A+A control duration ratios | `1.0066572427749634`, `1.0034866333007812` |
| A+A second onset relative to first | `−0.0057596371882086` s |

Finite cosine envelopes remove hard body-cut boundaries, and rendering removes the body mean. The generated body is slightly shorter because of its chronology ratio. The unvoiced fallback still follows the source; this is not generalized consonant or phrase synthesis. The scale parameter changes grain color; it is not a calibrated biological vocal-tract model.

At the inspected default, `ConfluenceVoice.render` returns early for TEXTURE and its pitched route advances output timing from the local source period. Simply increasing individuality, detuning the same recording or adding stereo effects will not reproduce this independent-clock contract.

## Reproduce before porting

Dependencies: JDK 17 with `java` and `javac`, Kotlin 2.0.21 command-line compiler, Python 3 with NumPy/SciPy, and FFmpeg with `ebur128`. The script accepts a `KOTLINC_BIN` executable override. This is a standalone compilation; no Android SDK or Gradle application build is needed.

Run on a temporary copy because the renderer regenerates audio, manifests and measurements:

```sh
task_replay_dir=$(mktemp -d)
cp -R docs/handoffs/assets/2026-11-07-confluence-two-voices/. "$task_replay_dir/"
cd "$task_replay_dir"
sha256sum --check SHA256SUMS
cp manifest.json expected-manifest.json
bash render.sh
python3 - <<'PY'
import hashlib, json
from pathlib import Path
expected = json.loads(Path('expected-manifest.json').read_text())
for clip in expected['clips']:
    actual = hashlib.sha256(Path(clip['path']).read_bytes()).hexdigest()
    assert actual == clip['sha256'], clip['id']
print('All six WAVs match the approved reference exactly.')
PY
python3 reviewer/audit_final.py
python3 reviewer/audit_pitch.py
```

The finalizer is required: the Java runner's intermediate RMS matching is not the delivery loudness policy. Generated timing/date fields can change on replay; compare the six WAVs against the **saved original** manifest, not a newly generated manifest against itself. The original `generator.zip` contains the renderer, sources and finalizer; the surrounding expanded package adds delivered audio, page source and review evidence.

To use the preserved local comparison page, serve the package root with `python3 -m http.server 4173 --bind 127.0.0.1` and open `http://localhost:4173`. The six players and downloads use local assets. Navigation to the surrounding bank/passage-picker pages belongs to the hosted site and is not included in this standalone package. Historical page copy still describes the November 6 listening study.

Frozen identifiers:

- `generator.zip` SHA-256: `268eaeb2b32eaa47da8bfb9391366b116b4f0f40398e114801af81889d58e592`
- `manifest.json` SHA-256: `36f0577e01b6667de75f79cea9f158f324e651febef32ebb38437e51df707f83`
- Raw A SHA-256: `2b98cc138a2772fa9414cf94cf8214f684daf47f93853a6c2f40bb78e8d32a51`
- Raw B SHA-256: `a49742f4fc604265f3e34534a4e1acf3481266894a5614af05d0c01dcd9b4b6f`

## Evidence and limits

| Label | Result / evidence |
| --- | --- |
| Heard, November 7 | Josh approved generated solo and the paired two-singer impression for these clips. |
| Verified | Six delivered WAVs and two raw sources agree with frozen hashes; generator has 13 safe entries; expanded source matches that archive. |
| Measured, independent pitch audit | Broad normalized autocorrelation, 100 ms windows / 10 ms hop, 120–700 Hz: generated pitch vs intended target correlation 0.9877; vs A contour 0.0686; p95 absolute target error 1.85 cents. See `reviewer/pitch-clock-audit.json`. |
| Measured, author pitch analysis | Separate broad-YIN method/windows: target correlation 0.9690, source correlation 0.0794, p95 error 3.87 cents. Do not combine these methods into one score. See `reports/measurement.json`. |
| Measured, color ablation | Fixed output clock, scale 1.08 vs 1.00: normalized spectral-envelope RMS difference 0.934 dB. This measures a processing difference, not human identity. |
| Measured, delivered levels | −21.999 to −22.001 LUFS, 0.002 LU spread; no clipping/nonfinite samples; last 250 ms silent. Independent final audit also checks declared sums. |
| Measured, original host timing | JDK 17, one CPU: analysis 93.8 ms; six clips plus color ablation 94.8 ms, excluding FFmpeg. Not Android latency. |
| Verified, reproduction | Fresh source compilation on November 6 reproduced all six complete WAV hashes. The November 7 handoff replay is recorded separately in `reviewer/handoff-replay-2026-11-07.json`. Same-host reproducibility does not prove cross-platform identity. |
| Verified, original browser QA | Chromium: six advancing/exclusive players, 3-second duration, no autoplay or external requests, working downloads, no horizontal overflow at tested 320–1400 px widths. Browser QA was performed before publication; no new browser feature is added here. |
| Proposed | Versioned production integration, persistent singer identities and a qualified target-pitch contract described below. |
| Unmeasured / unauditioned | Phone cost and memory for the new adapter; wide register; words, articulation, full cultural phrases; indefinite HOLD; keyboard mapping; cross-platform byte identity. |

This handoff changes only documentation/reference assets. The standalone replay and package checks validate the preserved prototype. The full app/JVM/native/device suites were not rerun for this documentation commit; old feature test totals are not evidence that the new production path is implemented.

## Recommended implementation sequence

### 1. Port a small, explicit held-vowel path

Start with a qualified personal or wordless held vowel and a dry pair in the existing Confluence workflow. Reuse `ConfluencePersonalVoice.PreparedVoice` and its epoch analysis where suitable. Add an explicit versioned opt-in independent-clock mode; leave legacy TEXTURE and existing pitched recipes unchanged. Do not register a new engine just to host this voice transformation.

Define and persist the generated voice's target center, pitch-curve parameters/phase, color scale and chronology ratio. In this study B's median is a controlled comparison reference. A real single-source product needs a musical target derived from the qualified source/guide or an explicit user choice; do not hardcode 260.707 Hz or require a second recording. Keep source tuning distinct from requested equal-tempered pitch.

Separate **stable singer traits** from **per-phrase performance variation**. Current `fixedIdentities` defaults to true; when false, `Confluence.eventIdentity` can regenerate the full identity after the first event. Review that seam so a singer does not acquire a different mouth on every entrance. Persist the model/parameter version and include it in any affected analysis/render keys and editable recipes. Never silently reinterpret old model versions.

The approved study matches components before summing, then matches the complete clip. Current production Confluence levels the mixed output. Component matching would therefore be new production behavior: define its policy explicitly within the new version, rather than changing legacy recipe levels globally. The standalone FFmpeg finalizer is reference processing, not an Android runtime dependency.

### 2. Preserve app, memory and source contracts

- Prepare/cache off the UI thread, keyed by canonical source identity, selected frames, analysis/model version and parameters that affect preparation. Keep knob-to-render cost measurable and caches bounded. Do not retain one full 30-second `ConfluenceReport` per control state.
- Preserve `ConfluenceSelectedBank`'s existing two-entry, 8 MiB completed-render cache budget. Earlier report retention produced a 192 MiB Android-heap failure; verify navigation to another heavy engine after leaving the voice screen.
- Use the existing bench's immutable retained preview for SAVE PAD. Recheck an empty destination and current source under the existing `KitWrites` guard, including after suspension or kit changes.
- Keep the raw take and selected source closure, canonical hashes, attribution, native backup/restore, merge/breed capacity and ordinary XPN rendered-audio behavior intact. A new identity must survive save/reopen and supported backup without losing its source or changing its voice.
- Retain mono 44.1 kHz finite `Snip` output and the existing source-processor conditioning contract. This prototype is source-based processing at the current rate, not a new oscillator requiring a copied oversampling chain.

The selected Yaman, kulning and khöömei passages currently use TEXTURE and can have changing pitch or strong overtones. Their roots/text eligibility are not newly established by this vowel experiment. Do not enable bulk pitched resynthesis for them; qualify and audition any new treatment separately, preserving original playback and existing refusal/unknown states.

### 3. Verify the production implementation, then audition the phone build

Meaningful new checks should cover:

- Independent output pitch follows its declared target rather than A's contour, while source epochs still provide input grains. Include a source with substantial F0 movement so the wrong clock would fail.
- A fixed-clock color ablation changes the envelope while preserving the intended pitch; target changes do not silently undo the color setting.
- Deterministic render/JSON/save-reopen, explicit version compatibility, finite/bounded output, DC, true peak, edges, unvoiced fallback and refusal bounds. Keep legacy TEXTURE and factory/bank golden output unchanged.
- Exact preview-to-pad audio; original-source immutability; credits and source closure through native backup, merge and breed; stale preview/kit-change and occupied-pad guards.
- Android preparation/render latency and peak memory, including leaving the voice screen for a heavy engine on a 192 MiB heap. Host timing alone does not close this gate.

For production changes run Steward's full JVM gate (`./gradlew --no-daemon test` without an SDK; `./gradlew --no-daemon test -x :app:test` with one), plus relevant Android build/device checks. Run native CMake checks if C++ or its build changes. Judge completed exit codes, not partial logs. The next phone audition should compare original, generated solo and dry pair before room or further singers are added.

## Code map at the inspected default

| Concern | Existing repository location |
| --- | --- |
| Source analysis, TEXTURE early return, pitched pulse loop | `synth/src/main/kotlin/com/snipsnap/synth/ConfluenceVoice.kt` |
| Identity, event scheduling, conditioning and shared room | `synth/src/main/kotlin/com/snipsnap/synth/Confluence.kt` |
| Recipe/model representation | `synth/src/main/kotlin/com/snipsnap/synth/ConfluenceRecipe.kt` |
| Personal qualification and cached analysis | `synth/src/main/kotlin/com/snipsnap/synth/ConfluencePersonalVoice.kt` |
| Selected bank and bounded render cache | `synth/src/main/kotlin/com/snipsnap/synth/ConfluenceSelectedBank.kt` |
| Curated and personal workshop/save paths | `shell/src/main/kotlin/com/snipsnap/shell/ConfluenceSelectedWorkshop.kt`, `ConfluencePersonalWorkshop.kt` |
| Existing curated bench | `app/src/main/kotlin/com/snipsnap/app/ui/ConfluenceVoicesScreen.kt` |
| App mutation serialization | `app/src/main/kotlin/com/snipsnap/app/KitWrites.kt` |

Re-read current APIs after fetching the default; this map is evidence from `ae4bd8d`, not a replacement for inspecting the next integration base.

## Why it is built this way

- The real A+B recording reference gives the owner a direct comparison for a two-singer impression.
- Dry mono and level matching make width, room and simple loudness changes less likely to dominate the decision.
- Separate source-grain and output-pitch clocks let the generated part perform its own pitch motion.
- Stable color and independent timing address different cues; neither the spectrum measurement nor the clock measurement alone proves a different perceived singer.
- The small qualified vowel scope makes the approved behavior reproducible before expanding into harder phrases or registers.

## Paste into the next implementation session

> Continue SnipSnap Confluence from `docs/handoffs/2026-11-07-confluence-two-voice-handoff.md` on `claude/confluence-two-voice-handoff`. Josh approved the generated solo and A + generated two-singer impression on November 7. This is an approved held-/a/ listening prototype, not an app implementation or approval of full cultural phrases. Read AGENTS.md and the relevant repository skills, fetch current default, and reproduce the packaged six clips first. Port an explicit versioned independent-output-clock path for qualified vowels into the existing Confluence workflow. Preserve legacy TEXTURE/pitched outputs, stable per-singer identity, immutable preview-to-pad saves, bounded memory, source credits and portable editable source closures. Define a real single-source target-pitch contract instead of depending on the reference B recording. Validate and deliver a phone audition of original, generated solo and dry pair before expanding scope. The frozen November 6 reports say listening pending; the later owner verdict is recorded in the handoff.
