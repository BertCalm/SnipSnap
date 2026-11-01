# Confluence My Voice

Implementation basis: `e561bec68ea68a3c81d507ce5391320ba9de44c6`, 2026-10-30.
Status: first implementation; verification results are recorded below as they complete. Subjective listening remains with the owner.

The characteristic gesture is **record a comfortable vowel → choose its steady section → compare original, solo and ensemble → keep the voice → make a new pad**.

## Scope and landing

This is a personal-source workflow for the existing Confluence processor. The nearby existing tools are Confluence's finite performer arrangements and One Note's captured-note mapping. My Voice adds a durable user-owned source and a short preparation flow; it does not register another synthesis engine.

The first version records up to six seconds or imports a short recording. The selected vowel is 0.6–8 seconds. It provides original playback, a processed solo and a four-performer ensemble. The DSP also qualifies and renders two performers and nearby notes within two semitones of the nearest measured root. Those additional DSP parameters are not exposed as new screen controls in this first interface. Output is finite mono 44.1 kHz audio; indefinite HOLD remains unsupported.

Open **MAKE → MY VOICE**, record or import, then compare ORIGINAL, SOLO and
ENSEMBLE. ADJUST TRIM exposes the two boundaries; USE THIS TRIM checks the new
passage. Name and SAVE VOICE before saving the chosen sound onto the displayed
empty pad. A voice can be kept before a kit is open, then reopened from the same
screen when a destination kit is available.

Three directions were considered:

- **Source substitution, chosen:** one comfortable vowel enters the existing Confluence renderer. Recording effort and integration cost stay small, and the original provides a direct processing reference.
- **Personal phrase with answering group:** retain a complete recorded contour and use it as a call. It requires separate qualification and presentation for deliberately changing pitches.
- **Personal multisample bank:** record low, middle and high notes, then choose the nearest source for each target note. It extends the natural range, with more capture and mapping work.

## Signal path and controls

```text
mic / imported decoded audio
  ├── exact raw float32 take retained on explicit SAVE
  └── unlevelled mono / 44.1 kHz conversion
       → selected-channel clipping check and independent pitch qualification
       → selected PCM24 WAV representation, decoded again
       → content identity + measured native pitch + cached epoch analysis
       → Confluence finite performers
       → original / solo / ensemble preview
       → new ordinary pad + kit-local source closure
```

| Choice | Processing |
| --- | --- |
| ORIGINAL | Selected untreated recording; no performer transformation |
| SOLO | One Confluence performer at the nearest equal-tempered root, without room |
| ENSEMBLE | Four performers, conservative individuality, room 0.15 |
| TRIM | Requalifies the chosen canonical frame range before enabling processed playback or saving |
| SAVE VOICE | Keeps raw take, canonical processor source, selection, measured pitch and versioned assessment locally |
| SAVE PAD | Writes a separate ordinary sample into an empty pad and carries its editable source files |

The source processor uses the existing Confluence time-domain pitch-synchronous overlap/add implementation and output conditioning. Its source-based processing remains at the existing renderer's rate; a new oscillator or physical-model oversampling chain is not introduced. A processed solo is not an untouched reference even with low individuality. ORIGINAL is an actual separate playback path.

## Qualification and sound risks

The initial held-vowel qualifier checks finite samples, usable duration and amplitude, selected raw-channel rail contacts, periodicity, pitch stability, ambiguous octave candidates and agreement with the existing epoch tracker. It evaluates a wider pitch range than the renderer so a low or high recording cannot qualify merely through an apparently convenient octave alias.

The initial native band is **80–500 Hz**. Duration, level, confidence, stability and spectral-support thresholds are conservative first-version product choices, rather than a universal description of good singing. Breathiness, roughness, strong overtones and room reflections can still make pitch estimation difficult. The interface reports a practical refusal and retains original playback, allowing a different trim or take.

Native pitch is a continuous MIDI value. The nearest integer root defines the nearby output notes, preserving the distinction between the recorded tuning and the requested tempered note. No personal recording acquires the curated bank's wordless eligibility or CC BY declaration.

## Identity, lifetime and portability

`ConfluencePersonalLibrary` keeps immutable UUID entries in the app's personal library. Each entry contains `voice.json`, the exact untreated `raw.wav` float32 take, and a canonical mono PCM24 `source.wav`. Source PCM identity is computed after writing and decoding the actual WAV. Original raw and source file hashes, canonical PCM identity, crop, root, assessment and preparation versions must agree when reopened.

The shell prepares the same zero-based selected PCM24 source before the first preview. A nonzero crop therefore keeps the same source hash and performer seed after SAVE and reload. Preview PCM is defensively copied. A saved pad records its personal voice ID, manifest/file/PCM hashes and a bounded personal recipe. Creating a pad checks an empty destination and unchanged source before carrying the closure into `.confluence-personal/<id>/` inside the kit.

Native kit backup adds a bounded source sidecar bound to the rendered XPN and its sample identities. Restore checks it in staging before publication and preserves existing XPN behavior for unrelated pads, effects and recipes. Ordinary XPN/MPC exports continue to carry rendered samples. A recognized personal source missing from a native backup is an explicit restore refusal rather than a silent substitution.

Merge and breed carry the referenced personal sources, with same-ID conflicts
and capacity checked before creating the child. Saving admits at most 16 distinct
voices and reserves the native backup's metadata allowance within its 128 MiB
per-kit source budget. Native backups and copied kits therefore include the
untouched personal recording. Ordinary XPN reimport retains provenance with a
rendered-source identity, removing editing references whose files are absent.

The microphone request owns a token. Cancellation, backgrounding or a late callback cannot eject another capture session or start an obsolete preview. Explicit saves run in the app scope; kit mutations use the existing write mutex, recheck an empty destination and preserve occupied pads.

## Cost and verification ledger

Qualification and epoch analysis run away from the UI thread. A prepared source owns reusable analysis keyed by canonical PCM identity, selected frames, measured reference and analysis version. Renders reuse that analysis; it is not serialized into a recipe. File/source validation remains mandatory when saving or reopening. Phone latency is not claimed from host measurements.

Measured on 2026-10-30 with the production Kotlin calls, Temurin 17.0.21 and
four JVM-visible processors: preparing the licensed 3.8-second male-vowel fixture
took 486 ms. With prepared analysis reused, three-render medians were 35 ms for
one performer, 23 ms for two, 46 ms for four, and 15 ms for solo at either nearby
two-semitone limit. One native solo render took 144 ms. This small host probe is
not a phone benchmark or a listening verdict.

Verification ledger:

- Kotlin DSP qualification, mutation, determinism, nearby-note and cached-render probes: 12 tests and the host benchmark passed, including the real vowel's nonzero crop.
- Personal library, workshop and breeding: 16 tests passed through a direct Jupiter launcher against the current compiled production/test classes. These cover raw/canonical persistence, selection/root tampering, failed saves, path/symlink refusal, preview/save/reload equality, occupied pads, stale sources and regeneration after deleting donor files.
- Kit capacity, native source backup/restore, merge and ordinary XPN demotion: 17 focused tests passed through Gradle.
- Android app and instrumentation APKs assembled successfully, including all four native ABIs. On an API 35 software emulator without KVM, seven of nine focused device tests passed: four recorder cases, two import cases and the MAKE entry. Both source-reopening screen tests exceeded their existing 60-second waits before reaching later preview/trim assertions. ART tracing showed runnable correlation calculations; package compilation attempts remained in verification mode and instrumentation reported classloader-context mismatches. These failures remain recorded. The full screen workflows require the KVM-backed CI run; no phone performance or microphone/listening verdict is claimed.
- Full JVM catalogue inventory: **7,240 tests across 882 classes covered successfully**. A workspace restart interrupted the initial full run. JUnit discovery and compiled annotations agreed on the complete inventory; 3,720 Synth tests had unambiguous complete-class pass records, all other eight modules completed successfully (3,350 tests), and 16 incomplete or ambiguous Synth classes were rerun in full (170 tests). Both resumed Gradle processes exited zero. The final union audit found no missing test IDs, unsuccessful final results or skipped tests. This is audited interrupted-and-resumed coverage, not a claim of one uninterrupted successful command. The rerun also caught and resolved the app's kit-read locking convention and updated the existing bounded ByteArray-decode exception with its reviewed allocation limits.

## Why it is built this way

- A short comfortable vowel makes setup manageable and gives the processor a steady section to qualify.
- Retaining the untouched take supports honest comparison and later retrimming without building on already processed audio.
- Preparing the persisted source representation before preview keeps the saved voice and performer identities reproducible.
- Personal sources stay separate from the curated bank so recording ownership and source verification remain accurate.
- Source closure in native backups lets a restored kit remain editable while ordinary hardware exports stay rendered audio.
