# Confluence Engine — Engineering Specification

## 1. Summary

**Confluence** is a vocal-source instrument for SnipSnap. The user supplies one or more vocal recordings; the engine arranges and transforms them into a gathering of distinct performers with persistent identities, registers, breathing schedules, and relationships between sections.

The defining gesture is **one voice → shared intention → many individual voices**. Growth should sound like more performers joining, not simply louder audio or additional chorus taps. Groups can sustain a foundation, carry a phrase, answer one another, and gather toward shared timing and pitch before loosening again.

This handoff specifies proposed behavior and implementation stages. It does not include a working DSP engine, supplied recordings, or verified repository changes. Numerical limits and defaults below are prototype targets unless identified as existing project requirements. Confluence is a working name; availability has not been assessed.

## 2. Product Scope and Architectural Difference

Confluence is asset-dependent. Unlike the preceding sample-free engines, it requires source selection, analysis, persistent asset references, dependency-aware export, and explicit handling of missing material. Implement those surfaces as part of the engine, not as an undocumented prerequisite.

V1 is deterministic offline mono rendering at 44.1 kHz with five timbral/behavior macros plus HOLD, patch JSON, presets, kit integration, auditions, and melodic loudness leveling. Follow the supplied Gyre conventions while confirming current interfaces in the repository. No codebase has been inspected for this document.

A pitch-aware vocal transformation stage supplies individual performers; a separate ensemble scheduler determines their behavior. No text-to-singing, new words, speaker-identity model training, automatic translation, or exact reproduction of a cultural tradition is required. Repeated/transformed source material remains the basis of every voice.

## 3. User Flow

1. Add one or more vocal recordings and select usable regions.
2. Confirm suggested phrase boundaries and, where useful, a stable vowel/sustain region and reference pitch.
3. Choose a performance profile and assign sources to available roles or sections.
4. Audition a small gathering, then adjust crowd and behavior macros.
5. Render a finite sample or held loop and export through the normal workflow.

Defaults should work with one well-recorded vowel or phrase. Additional sources improve diversity but are not mandatory. The interface must identify unavailable roles in plain language, such as “Add a sustained region for the drone section.” Do not silently invent source capabilities.

No audio is required to deliver this specification. Audio import and audition are implementation work for the engineer.

## 4. Source Contract

Prototype import support: WAV and FLAC, mono or stereo, 16/24-bit PCM or supported floating-point input. Decode to finite floating-point samples, resample once with a quality band-limited resampler, and store the canonical analysis/render asset at 44.1 kHz. Document supported channel handling; preview stereo downmix and allow a single-channel choice where cancellation occurs.

Initial limits: up to 8 source assets per patch, selected regions approximately 0.25–30 seconds, and up to 4 sections. Keep original files intact. Use stable content hashes for asset identity and cache keys; filenames and absolute local paths are not sufficient identifiers.

Validate silent, clipped, corrupt, excessively short, and nonfinite material. Clipped or noisy recordings may still be usable with clear quality feedback. Silent/corrupt inputs must not launch a render that returns an unexplained empty sample. Preserve original phrase ordering by default.

Store source/region metadata: asset hash, region start/end, role eligibility, reference-pitch policy, phrase boundaries, optional sustain markers, and analysis version. User corrections override automatic suggestions and survive serialization.

## 5. Analysis and Pitch Policy

Analyze once per asset/region and cache reusable descriptors: voiced/unvoiced likelihood, candidate fundamental and confidence, amplitude envelope, spectral envelope, transient boundaries, and suggested breath/phrase boundaries. Treat these as fallible DSP estimates, not semantic understanding of language.

Provide two explicit source modes:

- **PITCHED:** the user confirms a reference note or reliable stable segment. Requested MIDI pitch transposes the source's relative melodic contour from that reference. It does not flatten the phrase into one note.
- **TEXTURE:** unreliable, multiphonic, strongly rough, or predominantly unvoiced material keeps its original pitch behavior by default. Register transformations are conservative. MIDI may select a documented playback mapping rather than implying accurate melodic tuning.

A manual reference note must remain available when automatic detection fails. Do not mistake an overtone for the fundamental and silently transpose an octave. Complex throat-like or rough sources require dedicated listening fixtures rather than assuming the pitch estimator handles them correctly.

Speech and breath segments should not be harmonized as if they were stable vowels. Preserve their temporal placement and use appropriate unvoiced processing. Analysis must not manufacture phoneme labels or translations.

## 6. Performer Model

Every virtual performer has a stable ID and a seeded identity vector: register offset, spectral-envelope adjustment, breath/roughness amount, attack tendency, habitual timing offset, bounded pitch tendency, phrase-duration tendency, and leader/follower sensitivity.

Identity persists across phrases, rests, and loop periods. Time-varying behavior is a small deviation around that identity, not a complete resampling of characteristics every frame. Low-register voices should not randomly turn bright and hurried midphrase.

Start with 1–48 individually rendered performers. This is a prototype offline budget, not a claim of 48 independent human identities from one recording. A single source can create transformed variants; multiple source performers are needed for greater natural diversity. Optional larger-crowd approximation is deferred until the individual model succeeds.

Generate IDs from the patch seed and fixed roster slots. Increasing CROWD retains existing performers and adds new ones; it must not reseed the whole ensemble. Use fractional activation/gain near roster boundaries to make growth smooth.

## 7. Vocal Transformation

Prototype a formant-aware time/pitch processor with separate control over pitch, spectral-envelope character, and phrase timing. Candidate implementation families include a harmonic-plus-residual model or a phase-coherent spectral processor. Select the implementation through source-fixture auditions before committing to a production dependency.

Requirements are perceptual: clear vowels and phrase attacks survive moderate transformations; pitch changes need not shift formants by the same ratio; breath and unvoiced portions remain plausible; sustained regions do not become metallic buzz or repeated grains.

Begin with conservative per-performer transformations: roughly ±3 semitones for ordinary diversity and spectral-envelope scaling around 0.90–1.10. Octave roles require separate validation and appropriate source assignment; do not assume a 12-semitone shift from any recording is believable. Time variation should be restrained, initially about ±2–6% within marked phrase constraints.

REGISTER can select octave roles or different supplied sources before stretching one source excessively. DIVERGENCE may widen transformations toward deliberately synthetic territory, but the profile must expose that expectation. Do not claim that formant shifting alone produces a different singing technique, language, or tradition.

Share immutable source analysis and cached transformed bases where useful, but retain independent phrase schedules, envelopes, and small deviations. Identical playback with phase offsets alone is insufficient.

## 8. Roles and Performance Profiles

Use behavior-based profiles rather than treating cultural names as synthesis algorithms:

| Profile | Arrangement behavior | Source needs |
|---|---|---|
| UNISON | Shared phrase with individual entrances and recovery | One complete phrase |
| DRONE | Staggered sustained voices with optional phrase carriers | Marked sustain region |
| RESPONSE | Two or more sections alternate intact phrases | One repeatable phrase; multiple phrases preferred |
| PROCESSION | Gradual joining, overlap, and coordinated phrase movement | Complete phrases |
| HALO | Quiet foundation with sparse upper sustained ornaments | Suitable pitched sustain regions |
| ASSEMBLY | Mixed carrier, foundation, and responder roles | Phrase plus sustain, optionally multiple assets |

A profile selects role allocation, coordination strength, source policies, and allowable interval relationships. Default to unison/octave support where suitable. Additional scale or ratio sets are explicit patch configuration; do not impose a Western harmony template on every source.

Foundation requires a usable sustain region or suitable source. Ornaments reuse supplied vocal gestures or marked vowels. Without an ornament source, the role is disabled or uses a clearly identified vowel sustain; it must not fabricate cries, words, or specialized vocal techniques.

## 9. Phrase and Breath Scheduling

Represent each performer as REST, INHALE/PAUSE, ENTER, SING, and RELEASE states. A breath state can simply be silence unless an appropriate source breath is available. No separate audible inhalation asset is mandatory.

Phrase carriers preserve internal source order. Shift or stretch complete phrases and protect marked attacks. Responses begin after a section's release with a bounded delay; they are not random cuts into the middle of a syllable. Use marked sustain regions for long vowels rather than stretching consonants indefinitely.

Stagger rests in sustained profiles so individual performers stop while the section remains continuous. Enforce a configurable minimum active foundation share when source material supports it. Finite phrase profiles may intentionally include collective silence.

RESPONDER decisions use completed events with explicit delays and refractory windows. Bound simultaneous entrances, event rate, queue depth, and the number of phrase repetitions. Finite renders stop admitting new phrases early enough to finish their allowed tails. Use deterministic truncation diagnostics if the selected duration cannot contain a complete phrase.

## 10. Collective Influence — Defining Interaction

Use a sparse graph of section leaders and neighboring performers. Each performer follows a shared phrase reference plus a bounded local deviation. Recent neighboring entrances can pull that deviation toward the group; a foundation section can weakly stabilize sustained pitch departures.

Example control update:

```text
deviationNext = deviation
              + followAmount * (neighborTarget - deviation)
```

Use a followAmount in [0,1] derived from a time constant. Compute targets from the previous complete state, using normalized nonnegative neighbor weights. Bound pitch and timing deviations separately. Never integrate unrestricted pitch drift or let a following rule skip backward within a word.

Collective influence adjusts next entrances, next breath points, and small sustained pitch deviations. It does not erase an imported melody or force consonants to jump in time. Keep an external phrase/pitch anchor so a local agreement cannot move the whole ensemble permanently off reference.

At low DIVERGENCE, performers gather closely around the reference. At higher values, leader changes and delayed influence create audible waves of agreement and loosening. RESPONSE changes the organization into answering groups. Log causal section/performer events so this can be distinguished from unrelated random LFO modulation.

## 11. Macro Set

| Macro | Low | High | Starting neutral |
|---|---|---|---:|
| CROWD | One performer | Up to 48 performers across sections | .40 |
| REGISTER | Narrow register distribution | Wider supported low/middle/upper roles | .35 |
| DIVERGENCE | Close shared delivery | Wider identity, timing, and pitch differences | .30 |
| RESPONSE | Shared entrances/delivery | Staggered answering groups and overlap | .25 |
| SPACE | Close and individually legible | More distant, enclosed gathering | .40 |
| HOLD | Finite arrangement | Seamless repeating gathering | .00 |

CROWD must change participation and group structure, not merely gain. REGISTER changes a distribution with stable performer assignments, not a global pitch bend. DIVERGENCE changes bounded individual behavior without erasing identities. RESPONSE must not be only a delay effect.

At CROWD = 0, section interactions are necessarily unavailable: keep RESPONSE audible through the solo phrase/repetition/rest pattern and REGISTER through the solo register mapping. Do not pretend one performer can produce call-and-response sections. No-dead-knob acceptance should distinguish these declared structural limits from accidental inactive controls.

Required interactions: CROWD × DIVERGENCE changes collective coherence; CROWD × RESPONSE reveals section structure; REGISTER × DIVERGENCE changes internal vocal contrast; RESPONSE × SPACE changes how overlapping sections remain legible.

## 12. SPACE and Mono Rendering

SPACE jointly changes direct/diffuse balance, distance-dependent spectral absorption, early-reflection timing, and section clarity. Use one shared bounded room model with section-specific sends/delays, rather than a full expensive reverberator per performer.

Mono output cannot encode literal left/right positions. Convey distance and group separation through directness, spectrum, timing, and restrained reflection differences. Avoid phase inversion or decorrelation tricks that vanish on mono export.

Keep a dry audition path. Crowd identity, roles, and coordination must remain audible with the room bypassed. Large reverb must not conceal duplicated voices, poor phrase cuts, or processor artifacts.

## 13. Defaults and Presets

| Profile | CROWD | REGISTER | DIVERGENCE | RESPONSE | SPACE |
|---|---:|---:|---:|---:|---:|
| UNISON | .30 | .20 | .20 | .10 | .35 |
| DRONE | .45 | .45 | .25 | .20 | .50 |
| RESPONSE | .50 | .35 | .30 | .80 | .40 |
| PROCESSION | .65 | .40 | .40 | .50 | .50 |
| HALO | .40 | .65 | .30 | .30 | .65 |
| ASSEMBLY | .75 | .55 | .50 | .65 | .55 |

These are prototype hypotheses. Plan 24 arrangement presets, four per profile, explicitly tagged with source requirements. Example names: First Gathering, Close Unison, Staggered Breath, Low Foundation, Answering Room, Slow Arrival, Upper Halo, and Many Returns.

Arrangement templates can be asset-free, but a playable preset must resolve actual sources. Ship neutral commissioned/licensed or project-owned fixtures if playable factory presets are required; otherwise show that sources need assignment. Missing material must not silently render synthetic placeholder chanting.

## 14. Reference Traditions and Content Direction

The user's creative references include Gregorian and Byzantine chant, cantu a tenore, Tuvan throat singing, Vedic mantra, Buddhist chanting, ululation, isicathamiya, and Indigenous Australian vocal traditions. These are a content/performance direction list, not interchangeable DSP modes or an assertion of shared technique.

V1 uses the neutral profiles above. Future tradition-specific packs require appropriate recordings and explicit musical direction for that pack: repertoire/phrase treatment, ensemble roles, tuning, articulation, source eligibility, and labeling. Preserve source provenance and performer/pack metadata. More specific community or practice names should replace broad labels where applicable.

The engine may deliberately combine separately configured sections. It must not label a generic formant-shifted vowel as an authentic performance of one of these traditions. No cultural research or authenticity review is claimed by this specification.

## 15. HOLD and Duration

Finite render duration depends on the selected phrases and arrangement. Start with a 2–30 second product range, subject to repository/export limits; phrase profiles must accommodate at least one complete selected phrase plus release. Do not force every recording into the shorter durations used by oscillator engines. If a phrase cannot fit, require another region or an explicitly selected excerpt.

HOLD uses a repeating arrangement period containing complete phrase cycles or marked vowel sustains. Stagger performers across the period and include overlapping releases/preroll. A phrase may cross the export boundary, but its cyclic continuation must preserve internal order.

All performer identities, source positions, pitch/timing deviations, breath schedules, neighbor state, event queues, processor overlap buffers, and room state must recur. Use deterministic periodic control sequences and bounded preroll. Constrain collective behavior to a periodic schedule in HOLD when necessary, while retaining audible individual differences and responses.

Never equate a short vowel loop with seamless repetition of an entire linguistic phrase. Reject incompatible loop-duration choices or use a longer period within limits. Use minimal project-standard wrap treatment after selecting a valid cyclic arrangement, then require `Keys.seamError < 1e-3` on exported audio plus listening for clipped syllables, extra breaths, repeated attacks, and room-tail discontinuity.

## 16. Asset, Patch, and Export Integration

Implement `Confluence.kt`, `ConfluencePatch`, `ConfluencePresets`, source analysis/cache, performer scheduler, asset resolver, patch registration, `SynthKits`, audition generation, tests, and roadmap update. Confirm whether current patch/render interfaces can resolve assets; extending them is an explicit implementation dependency.

Serialize profile, six macros, source hashes and region assignments, manual pitch/boundary corrections, tuning policy, seed context, and analysis/engine version. Keep derived analysis and large PCM outside patch JSON. Cache keys include asset content, region, analysis version, and relevant settings.

Project export must bundle referenced audio plus required metadata or use the project's established portable-asset mechanism. A rendered MPC sample needs no source dependency for playback. Reopening/editing the instrument does. JSON round trip alone is not sufficient: test a packaged project moved to a different directory with caches cleared.

Missing assets produce a clear relink state. Relinking a different hash requires explicit reassignment and reanalysis; do not silently substitute a similarly named file. Source provenance/usage metadata travels with a source pack according to existing project conventions.

## 17. Performance, Safety, and Determinism

Use immutable shared analysis and source buffers. Bound source duration, performer count, cache memory, room size, event queue, transformation ratios, and render search time. Benchmark 1, 8, 24, and 48 performers on an identified machine, recording analysis and render costs separately. Set production budgets from measurements.

Use deterministic seed streams keyed by patch seed, performer ID, and event index. Stable iteration and mix order are required; parallel processing must not change floating-point accumulation order unpredictably. Same assets, versions, patch, note, velocity, and seed produce sample-identical results within the supported runtime.

Resampling and spectral vocal processing use their own appropriate analysis rates and band-limiting. Follow the standard 4× path for nonlinear generated stages where applicable; do not blindly run every vocal analysis at 176.4 kHz or assume oversampling cures spectral smearing. Any shared-path exception must be documented and verified against current architecture.

Crowd mix gain needs a measured correlation-aware budget; 48 related voices can sum coherently. Apply shared melodic leveling after synthesis while retaining raw diagnostics. No NaN, infinity, uncontrolled peaks, DC accumulation, or missing-asset crashes are acceptable.

## 18. Probes and Acceptance

Create fixed project-owned test assets: clean sustained vowel, short articulated phrase, breath-rich voice, rough/multiphonic voice, low-confidence pitch, silence, stereo cancellation, and malformed input. No tradition-specific authenticity claim is needed for these engineering fixtures.

Audition each profile with one suitable source and with multiple contrasting sources. Sweep macros through 0, .25, .50, .75, and 1. Include single-performer behavior, required macro pairs, all-high controls, note transpositions, velocity changes, maximum crowd, incomplete source assignments, moved project exports, and difficult held phrases.

Record pitch/contour preservation where applicable, phrase-boundary integrity, raw peak/RMS, DC, macro distance, active performer count, identity consistency, breath overlap, coordination traces, seam error, processor artifacts, CPU, and memory. Set new numeric thresholds from probes. Compare collective influence enabled/disabled and fixed identities versus framewise randomization at matched loudness.

Pitched factory fixtures must meet current melodic classifier expectations, avoiding KICK, SNARE, CLAP, HAT, and TOM. Arbitrary user recordings cannot guarantee those classifications or a single root. Texture mode must use an explicit supported classification/export policy rather than falsely claiming pitched compliance or forcibly adding an unrequested oscillator.

Build in five stages: asset/region/pitch contract; believable 1–8 performer transformation; persistent roles/breathing and collective influence; 48-performer budgets and HOLD; portable patches/presets/kits. Required automated checks cover determinism, finite output, bounded counts/states, persistent identities, phrase scheduling, missing/corrupt assets, cache invalidation, serialization and portable assets, supported pitched fixtures, and representative held seams.

Confirm the current repository gate and run `./gradlew --no-daemon test` if it remains required. No implementation tests or vocal auditions were executed while writing this specification.

The owner's listening verdict completes acceptance: increasing CROWD sounds like more performers joining; registers and identities remain coherent; breathing feels individual; responses preserve phrases; collective influence can be heard without room effects; and held crowds repeat without audible or linguistic damage.
