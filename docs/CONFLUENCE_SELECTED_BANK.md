# Confluence selected voice bank

Status: implemented phrase-bank stage; validation and listening results are recorded below. This is a source bank and preset family for the existing Confluence renderer. It is not a new synthesis engine or a completed keyboard instrument.

Initial repository inspection: `a41184d8ae63af8d05fb4e80deccf0ce5cc01f86` (My Voice PR #755). Integration base: default branch `7cb8f3450`, including the merged My Voice work and current instrument updates. Contracts checked: `ConfluenceRecipe`, `ConfluenceVoice`, `ConfluenceSelectedBank`, `ConfluenceSelectedWorkshop`, native kit backup and XPN source metadata.

The characteristic gesture is **recorded passage → independent entrances → small timing differences → shared room → silence**.

## What ships

Nine approved passages from three recordings: four Yaman, three kulning, two khöömei. These are nine selections, not nine singers. The two khöömei cuts intentionally overlap. Source tradition descriptions follow the recording pages; the app does not claim to synthesize or authenticate a cultural singing practice. Text content remains `UNKNOWN`.

`FRESH TAPE → CONFLUENCE VOICES` creates sixteen phrase pads: nine playback references, three pairs, three four-part groups, and a short kulning response. `MAKE → CONFLUENCE VOICES` compares each original source with a prepared reference and solo/pair/group treatments, then saves the exact retained preview onto an empty pad. The nine reference pads and new bench pads reopen with their saved controls. The seven authored starter arrangements keep their exact recipes; they are fixed arrangements in this stage.

The existing four-voice wordless bank, its three-role Assembly contract, and My Voice remain separate.

## Source and playback contracts

All stored source WAVs are mono 44.1 kHz PCM24. Approved seconds are mapped independently to the nearest native sample at each boundary; the largest rounding adjustment is about 11.3 microseconds. No boundary snapping, repair, denoising, fades, gain change, pitch shift, time stretch, repetition or resampling is baked into those WAVs. Downmixing and PCM24 encoding are disclosed. File hashes and canonical PCM hashes are checked by the Kotlin loader, including WavReader's actual PCM24 divisor.

The browser and FFmpeg gapless timelines were compared before cutting. No duration-based scaling or guessed MP3 encoder offset is applied. The Yaman and khöömei files remain explicitly labeled derivatives of lossy public previews. Kulning was acquired as an original PCM WAV and a lossless FLAC listening copy.

| Mode | Audio behavior |
|---|---|
| SOURCE | Untouched approved canonical crop, at its recorded level and native register. |
| REFERENCE | One declared constant gain and cosine edge envelopes; no timing or pitch processing. |
| SOLO / PAIR / GROUP | One, two or four TEXTURE parts, finite 30-second scheduling, slight varispeed and entrance variation, shared room and output leveling. |

Reference gain is `min(0.16 / sourceRms, 0.8 / sourceSamplePeak)`, stored explicitly in the manifest. This is a playback comparison aid, not a statement about a recording's original loudness. Edges use 220 frames (about 5 ms), except Yaman 04's 4,410-frame (100 ms) release. Its approved raw cut ends during active singing and remains unchanged. The separate playback release is visible in the credits and audition metadata.

The existing TEXTURE renderer interpolates chronological playback when part durations differ. This is varispeed, so processed parts can change pitch slightly. Even zero variation retains the renderer's small part-identity differences. The interface does not promise pitch preservation, formant synthesis, independent human singers, or a new cultural performance.

## Controls and bounds

| Control | Meaning |
|---|---|
| Passage | One complete approved source region. |
| SOURCE / REFERENCE / SOLO / PAIR / GROUP | Comparison or finite arrangement; never HOLD. |
| VARIATION | Maps 0–1 to Confluence DIVERGENCE 0–0.15. It affects bounded entrance and duration differences. |
| ROOM | Shared Confluence SPACE, 0–1. |

Every processed recipe explicitly states all five existing engine macros. REGISTER is locked to zero, MIDI note is absent, and all regions use TEXTURE with a null reference note. No sustain or loop seam is asserted. Pads are one-shot LOOP-class phrases with zero coarse/fine tuning, so assigning into a keyed kit cannot silently tune them.

The bank does not mix recording families in its starter groups. The raw Yaman and khöömei levels differ substantially; a cross-recording ensemble needs an explicit per-source mix contract beyond the present recipe.

## Why it is built this way

Confluence already supplies the gathering gesture; VOX supplies synthesized formants and FLOCK supplies oscillator populations. These new recordings change the material, so they belong in a bank and presets rather than another engine registration.

High kulning fundamentals extend beyond the existing voice tracker's range. A production probe folded some of them down an octave even while reporting a high voiced share. Khöömei has competing low-frequency candidates and strong upper partials. Treating a whole-phrase median as a confirmed fundamental would produce unreliable tuning. A Yaman phrase median is also not a verified rāga tonic.

The current bank therefore preserves complete native gestures. A future keyboard stage must qualify individual stable-note derivatives, confirm roots by measurement and listening, test modest transposition first, and use additional recordings when a register cannot be covered cleanly. The original nine approvals do not establish new sustain markers or new crop boundaries.

## Data flow, caching and numerical limits

```text
bank.json + hash-checked source WAVs
    → immutable canonical PCM
    ├→ SOURCE copy
    └→ declared gain + cosine edges [prepared PCM identity]
         ├→ REFERENCE copy
         └→ finite TEXTURE scheduling → shared room → output level → pad
```

TEXTURE preparation retains chronological samples and deliberately empty pitch evidence. It does not run an unnecessary tracker or manufacture a root/confidence score. Prepared material identity includes its canonical PCM, region, mode and analysis version. Boundary conditioning uses StrictMath cosine so the same raw asset and declared conditioning reproduce the same content-addressed source across JVM platforms; nine independent reference hashes are pinned in tests. The completed-render cache includes exact manifest fingerprint, preparation/analysis version, complete recipe and normalization setting. It holds at most two reports and returns defensive PCM copies.

Existing Confluence limits still apply: at most eight assigned regions/parts, 2–30-second finite recipes, bounded event counts, complete first-phrase admission, finite output checks and bounded shared-room feedback. The shipped bench uses one region and 1/2/4 parts at 30 seconds. RESPONSE is used only by a short authored kulning preset; long Yaman 04 is not forced into a response that cannot fit. Existing renderer tests retain responsibility for its shared scheduling and DSP bounds; bank tests verify source identity, admitted presets, disclosures, cache identity and defensive copies.

Phone render time and listening quality remain device/owner checks. Host measurements are not presented as phone performance or as listening results.

## Portability and credits

The portable manifest and all nine WAVs, both license texts and source notice live under `.confluence-banks/<manifestSha256>/`. Pads bind the exact bank ID/version/fingerprint, used voice IDs, source hashes and recipe. SOURCE and REFERENCE selectors are explicit; processed recipes are normal `ConfluenceRecipe` JSON. `bankSettings` belongs to adjustable bench/reference pads only.

Native backup carries a bounded bank sidecar bound to its XPN and sample inventory. Restore verifies all declared payload sizes/hashes inside the import staging transaction, sharing the existing restore budget with personal voices. Kit merge and Breed preflight and copy referenced source closures. Missing or changed banks refuse reopening; a factory bank is never substituted for a missing dependency. Ordinary XPN import retains rendered audio and credits but demotes source-bank editability because an ordinary MPC program does not carry this source closure.

Yaman is CC BY 4.0: Kaustuv Kanti Ganguli, shared by sankalp/CompMusic, recorded at UPF Barcelona. Kulning and khöömei are CC0. Kulning's source credits Anna Moraeus and Jemt-Kersti Andersson, recorded by Matts Arnberg; no individual excerpt is assigned to either singer without evidence. Khöömei's uploader is nemaavla and its performer is unidentified. Per-used-source credits, URLs, license links and modifications travel in pad metadata and export attribution notices.

## Verified / proposed / heard ledger

| Claim | Status | Evidence |
|---|---|---|
| Nine cuts retain approved intervals and cached listening-file identity | Measured | Selection export, preparation audit, nearest-frame crop provenance, file/PCM hashes. |
| Source licenses and named/unknown performers | Verified from cached primary pages | Source URLs and full legal texts retained in the bank. |
| High kulning tracker octave errors; ambiguous khöömei candidates | Measured host probes | Production tracker and wider candidate analysis; no confirmed roots assigned. |
| Exact preview/save, source closure and import demotion | Implemented; validation below | Focused synth, kit, shell and Android tests. |
| Keyboard sustain, multiple note zones and safe transposition ranges | Proposed later stage | Requires independently qualified source regions and listening. |
| Processed presets sound acceptable | Owner listening pending | Published source/reference/part comparisons use production Kotlin audio. No listening verdict is inferred from metrics. |

## Validation

Focused validation passed: 10 synth bank tests, 7 kit portability tests, and 68 shell workshop/convention tests. Both the Android app APK and device-test APK compiled successfully with Java 17 and Android SDK 35. The full JVM suite and CI device execution are reported with the pull request; compilation alone is not an on-device test result. The audition generator is `:shell:generateConfluenceSelectedAudition`; its output records source revision, exact bank download fingerprint and per-variant processing. The existing audition's source picker and saved selection data remain available.

## As built

The source manifest fingerprints the nine original WAVs, both legal texts and source notice. No changes were made to the legacy Confluence renderer, wordless bank or Assembly APIs. The current default branch also restores full PRISM macro drafts before default controls; the independently identified startup regression was resolved upstream while this bank was being built. Its existing device regression tests remain intact.

Two inherited integration regressions were corrected without changing their assertions: the narrow-keygroup rewrite test now declares its own package directory, and OVERLAP/MERIDIAN exporters explicitly preserve their authored native zone ranges instead of accepting OneNote's full-keyboard default.
