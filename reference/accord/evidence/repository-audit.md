# Accord repository audit

Audit date: 2026-10-31. Read-only inspection; no Kotlin implementation, repository edits, website edits, Gradle run, or new audio measurements.

## Provenance and current pin

- Current default branch: `claude/mobile-mpc-drum-sampler-t58x74`.
- Current inspected commit: `d7c2379c16520df2679457679844b56d32777712`, committed 2026-10-31T22:29:19Z, merge of PR #779.
- Canonical source base: https://github.com/BertCalm/SnipSnap/tree/d7c2379c16520df2679457679844b56d32777712
- Previous inspection pin: `4e36a2bf8cf8bfb07dba35379558b54429b5d73d`.
- GitHub compare reports the current pin is 888 commits ahead and zero behind. The comparison response exposes only 300 changed files, so its file list was not used to infer that an unlisted file stayed unchanged.
- `/workspace/accord-repo-review` is a source-only snapshot, not a Git checkout, and has no saved provenance manifest. All 15 files in it were checked against the previous commit's complete Git tree. Removing the one extra final newline introduced while materializing each fetched text makes every file's Git blob hash match that prior pin. Its provenance is therefore independently verified.
- Current source copies used for grep are under `/tmp/accord-audit-current`. They likewise contain an extra final newline from materialization; original line numbers remain unchanged.
- The complete current tree was returned without truncation (4,240 entries). It contains no Accord-named source/spec file.
- Read current `AGENTS.md`, current repository Luthier instructions and current `.claude/skills/luthier/references/snipsnap-conventions.md`. Conventions still declare their base audit date as 2026-10-21; current source takes precedence.

## Current API evidence

All paths below are relative to the repository and all locations refer to the current pin.

| Need | Verified source |
|---|---|
| Patch interface | `synth/src/main/kotlin/com/snipsnap/synth/Patches.kt:14`: sealed `Patch` with name, engine, voiceName, macros, render, withMacros, JSON methods. |
| Registry / common JSON | `Patches.kt:43` dispatches `fromJsonValue`; `:127` writes common fields; `:143` begins generic `decode`; `:166` validates macro names/ranges. `Patches.VERSION = 1` at `:32` is the common schema version, not an acoustic/mechanical model version. |
| Rename/edit persistence | `Patches.edited` at `Patches.kt:35` reserializes engine-specific state rather than reconstructing a bare common patch. Include an Accord test that edits name/macros while retaining note, velocity, seed, model and profile. |
| Velocity integration | `Velocity.kt:196`: exhaustive private `macroSpecsFor`; `:385`: exhaustive private `touchedRender`. Current FLOCK cases at `:268`, `:430`; PITCHWHEEL cases at `:253`, `:412`. Add Accord to both. Brightness override needs a measured monotonic mapping before registration. |
| Exact saved seed | `FlockPatch.kt:11–21` persists midi, velocity, seed, model and playback configuration. `:43–55` writes custom JSON, including canonical decimal-string Long seed at `:48`; `:72–74` validates it. Do not store a Long in a JSON Double. |
| Model compatibility | `PitchwheelPatch.kt:20`, `:33–38`: independent rendering model and explicit legacy dispatch; missing legacy model handling at `:56`. Accord should reject unsupported explicit models rather than silently rerendering them as current. |
| Stable RNG | `Dsp.kt:147`: `Dsp.seedFor(vararg parts: Any): Int`; `:171`: `Dsp.Noise(seed)`. `TidalDynamics.kt:131` uses `Random(Dsp.seedFor(...MODEL_VERSION, settings.seed))`. Separate phase/rate/roughness substreams with stable identifiers; changing acoustic profile must not reseed mechanics. |
| Rates / level | `Dsp.kt:33`: OVERSAMPLE=4; `:573`: MELODIC_LOUDNESS_TARGET=.1834f; `:727`: levelTo; `:770`: decimate. Output remains mono 44.1 kHz, internal audio 176.4 kHz. |
| Band limiting / finish order | `Tide.kt:537`: internal bandLimit; `Gyre.kt:840`: band-limit → decimate → mean subtraction / 20 Hz DC blocking → levelTo → one-shot fade. Reuse the sequence, not GYRE's renderer. A loop must preserve filter/decimator context instead of applying one-shot tail fading across its wrap. |
| Modal audio | `Modes.kt:37`: Mode; `:88`: ring; `:494`: Bank; `:519`: tune; `:609`: dense drive. Added `Bank.participation` at `:625`, sparse drive at `:633`, sparse pickup at `:650`. These can reduce zero-weight contact work. They do not solve nonlinear metronome/base dynamics. Modal normalization caveat in ring KDoc remains. |
| Seam metric | `Keys.kt:147`: MAX_SEAM_ERROR=1e-3; `:188`: seamError against 256 genuinely preceding frames; `:201`: requireSeam. Silent reference material gives an undefined energy ratio, so acceptance needs a meaningful signal floor as well as the seam bar. |
| Loop planning | `Keys.kt:169`: planLoop is RESIN-specific and rounds pitch very slightly to fit frame count. It is not a general pitch-preserving Accord loop constructor. |
| Duration classifier | `audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt:81`: private LOOP_MIN_SECONDS=1.5f; `:146` labels any nonsilent render longer than 1.5 s LOOP before spectral classification. This class label is not proof of periodicity. Long finite auditions and sustained playback must be described separately. |
| Presets | `Presets.kt:18`: forVoice dispatch; `:314`: all registry. Preset files and defaults must preserve BRUSH as default, WOOD, TINE and the explicitly chosen TINE variants, rather than importing the original six speculative voices. |
| Kit conventions | `SynthKits.kt:383`: bore; `:838`: pitchwheel; `:1155`: flock. Use the current engine's note/velocity/profile fields and real duration/class logic. |
| Test roster | `synth/src/test/kotlin/com/snipsnap/synth/PatchTypes.kt:56`: GYRE remains rosterless; `:67`: current prototypes include CAIRN, WICK, NACRE, FERRY. BORE is a complete integration reference. |
| Determinism | `DeterminismTest.kt:179` covers held FLOCK recipes after cache eviction with full signed seeds; `:371` covers BORE finite/loop regeneration. Accord needs its own canary and cache-hit/miss equivalence. |

`siblingPatches` remains absent: local source grep found no declaration, and repository code search restricted to `path:synth` returned zero matches with `incomplete_results=false`. Do not put it in the implementation checklist.

## Changes that matter since the previous audit

- PITCHWHEEL, PITCHWHEELPATCH, GYRE and CLASSIFIER are byte-for-byte unchanged between the two local materialized snapshots.
- FLOCK now persists a separate FINITE/HELD playback choice, release time and held reconstruction version, while preserving the old meaning of HOLD as finite 2–6-second duration (`FlockPatch.kt:7–21`). Do not copy the old FLOCK patch verbatim or reinterpret existing models.
- `FlockHeld.kt:12–21` explicitly describes captured, reconstructed sustain rather than making drifting dynamics periodic. It retains two seconds of onset before a selected roughly six-second movement. Cache owns its arrays and returns copies (`:54–59`).
- `Keys.kt:17` already has `KeyNote(snip, loopStartFrame)`. Current `FlockTouchKeys.kt:45` returns complete sampled layers, each with a retained opening and loop region (`:105`). Do not repeat the old inventory's blanket claim that attack-plus-loop cannot exist in the sample/instrument path. Verify the exact pad/keygroup export route selected during Accord implementation.
- `Modes.Bank` gained compiled sparse participation vectors, useful for the contact/bank mapping.
- `Dsp.FixedOnePole` now exists at `Dsp.kt:461`; `fadeTailCosine` at `:820`. Neither requires changing the accepted Accord acoustic equations.
- GYRE is still incomplete as a release checklist reference. The skill inventory's 66-engine count and blanket export-gap statements are historical context, not current counts.

## Does Accord still merit an engine?

Yes, conditional on retaining the approved support-mediated timing model and demonstrating its audible effect. Profiles alone would not justify another engine; the distinct mechanism does.

| Existing engine | What it actually does | What Accord adds |
|---|---|---|
| FLOCK | 24 particles gather in pitch/energy coordinates at 420 Hz. See `Flock.kt:23–35`, `:184–265`. | Contact-event timing changes because fifteen self-sustaining oscillators accelerate one common moving base and receive its acceleration back. |
| BALLAST | A powered electronic bass drives a frame; six passive glass tiles ride its acceleration and their mounts push back. KDoc `Ballast.kt:21–59` explicitly says the structure has no trigger of its own and settles when the bass stops. | Autonomous sustained oscillators can change one another's articulation phases and frequency locking without an external event clock. |
| LIBRATION | Planar circular restricted three-body trajectories near L1–L5. `LibrationTrajectory.kt:8–16` and `:63–68`; independent paths mapped to voicing at `Libration.kt:82–104`. | It is not a pendulum instrument and has no shared mobile-base interaction or particle-to-particle response. Moving Accord profiles into LIBRATION would remove Accord's defining dynamics. |
| TIDAL | Restricted gravitational encounter of two centers and 2,048 test particles, reduced to 32 persistent harmonic cohorts. `Tidal.kt:20–28`; `TidalDynamics.kt:17–35`. | Emergent synchronization of autonomous articulations instead of encounter geometry controlling harmonic excitation. |
| APSIS | Six prescribed Kepler orbits control modal injection/exchange. `ApsisOrbit.kt:5` explicitly says audio exchange never changes trajectories; `Apsis.kt:14–24` describes an orthogonal modal junction. | Its shared acoustic exchange does not provide reciprocal inertial mechanical phase gathering. |
| GYRE / PITCHWHEEL | Audio-wave bridge coupling / a finite driven wheel with contact/energy diagnostics. | Useful bank/diagnostic precedents, but neither implements Accord's fifteen self-sustaining mechanisms. |

Primary sibling comparison: FLOCK + BALLAST. LIBRATION, TIDAL and APSIS are especially useful architecture precedents, not alternate homes for the approved physical mechanism.

## Current preparation and cache precedents

- `LibrationRenderSession.kt:15`: bench-scoped session with separate audio and population caches; 4 MiB audio / two populations by default. `Libration.populationKey` (`Libration.kt:74`) excludes MIDI, velocity and resonance. Session snapshots macro maps, cancels superseded work, times physics/audio separately and returns owned audio arrays.
- `ApsisPreparationCache.kt:11`: generic internal preparation-only LRU, defaults six entries, 32 MiB total, 12 MiB per entry. `getOrPrepare` at `:31` prepares outside the lock and checks cancellation before publication. Immutable fully prepared values only.
- `Tidal.kt:50–79`: distinct trajectory preparation and bounded audio caches, plus per-stage timing. `TidalDynamics.Settings` at `:39` is its full mechanical preparation key.
- These are existing internal engine-specific facilities, not a universal public simulation API. Accord may adapt the pattern or extract a neutral helper where genuinely shared. It must still implement and validate its own pendulum mass-matrix RHS, solver and contact extraction.
- Do not claim LIBRATION's adaptive solver is DOP853. `LibrationTrajectory.kt:150–202` uses seven stages and an embedded fourth-order estimator (Dormand–Prince style 5(4)), landing on readout targets. It provides no ready-made 32-state Accord dense event solver.

## Concrete integration checklist

1. `Accord.kt` and separate mechanics/contact/acoustics helpers; `AccordVoice { BRUSH, WOOD, TINE }`, BRUSH default, explicit acoustic profile/variant tables. Do not make acoustic variant identity reseed the mechanical state.
2. `AccordPatch.kt`: name, voice, macros, explicit MIDI, velocity, seed, independent model version, required acoustic profile and finite/held configuration. Persist any source duration that affects behavior. Common schema remains Patches.VERSION.
3. Add JSON dispatcher and both Velocity cases. Verify macro edits and rename retain all saved fields.
4. `AccordPresets.kt`, both Presets registrations; presets only for reviewed material. A prototype exemption is explicit if the first build is intentionally not a release.
5. Add engine test file, preset tests, determinism canary, JSON tests, actual-render classifier probes, physics convergence/ablation and cached/uncached identity tests. Register hand-built patches wherever current exhaustive roster tests require them.
6. Add `SynthKits.accord`, kit generator/task, audition generator/task/HTML. Auditions preserve the accepted BRUSH/WOOD/TINE clips and approved TINE alternatives as named comparisons; production defaults require Kotlin parity listening.
7. HOLD/export: choose and version the actual reconstruction strategy. Validate seam after decimation/conditioning and retain preceding context. Add KeyNote/keygroup export only through a verified current route, including MIDI/velocity layer semantics.
8. Add docs/ACCORD.md, roadmap row, README roster change, and an As built/verified/measured/heard ledger. Add app picker/UI only in an Android-capable implementation session after reading current SynthScreen mappings.
9. For implementation, run `./gradlew --no-daemon test` and judge exit status. No implementation gate is claimed by this audit.

## Blockers and limits

- No blocker to writing a self-contained handoff. The current repository is available read-only through GitHub tools.
- The original source snapshot was stale; a spec that cites its previous line numbers or assumes no existing preparation-cache patterns would be wrong.
- Release acceptance remains contingent on Kotlin numerical/audio parity, measured render cost, correct actual-render class behavior, HOLD strategy/seam checks, and owner listening. This audit does not supply any of those measurements.
- There is a repository convention tension: long nonsilent audio is automatically classified LOOP, while some existing finite pitched engines return TONAL from their engine helper. Accord must choose and test its declared mapping instead of assuming duration proves seamlessness or silently changing the classifier.
- Current approved Python sound/variant artifacts are not in the SnipSnap tree. The handoff must carry their separate provenance, exact tables, source hashes and owner decisions supplied by the main session; do not infer them from this repository audit.

