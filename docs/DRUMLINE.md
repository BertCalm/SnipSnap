# DRUMLINE

DRUMLINE is a marching battery in progress. The owner preferred the revised Python cymbals: an open crash with lingering plate ring, and progressive damping instead of an abrupt whole-signal cutoff. The native model carries those mechanisms forward. On 2026-11-15 the owner accepted the published native revision 4 ("Sounds good, proceed"). Preserve that sound as the approved baseline.

The characteristic gesture is coordinated stick attack → independent bodies → overlapping cord/plate ring → finite release.

## What is implemented

The renderer has 16 source voices: snare, rimshot, six tenor sizes, five bass sizes, crash, choke and touch. Five finite articulations add flam, double-stroke roll, accelerating riser, bass unison and split bass. Defaults are six snare players, three tenor players, one player per bass size and three cymbal players. Player identity remains fixed across takes; each take re-excites the same bodies with fresh force, contact and noise. All synthesis is procedural and mono; no recordings ship.

TUNE moves body frequencies; STRIKE changes contact width or metal excitation bandwidth; DAMP changes modal losses. Multi-player voices also expose PLAYERS and TIGHT. The bass line has separate size voices rather than a knob which duplicates its lowest drum. All articulations are finite; HOLD and STRUM are not applicable. Long cymbal decays and scored rudiments retain authored PERC roles rather than being shortened to satisfy the capture classifier's duration heuristic.

Two starters each have sixteen pads and three velocity levels by three takes per level. FIELD CADENCE has tight heads, six snares, a double-stroke roll, bass unison and a cymbal choke at 120 BPM. STREET CADENCE lowers tuning, opens head losses, reduces section size, softens bass contacts and uses a riser, split bass and short cymbal touch at 100 BPM. The difference is in source, articulation and arrangement, not effect strength. Each pad retains its versioned patch recipe and full rendered grid.

## Rendering and numerical contract

The internal rate is 176.4 kHz. Complex oscillator recurrences replace per-sample trigonometric evaluation of every mode; coefficients are computed once per mode. Natural damping is strictly positive. Choke loss is the integral of a smoothstep contact ramp, with positive frequency-dependent damping, updated in 64-sample blocks. Each oscillator's state norm decreases after excitation; no limiter is used as a stability argument. Final output is band-limited, decimated, DC-blocked, leveled once and faded over 4 ms. Rudiments mix fresh raw strikes before this common output stage so their rebound dynamics survive.

No unbounded cache or geometry solver is used. Sequential bank generation bounds memory independently of core count. The playability target is tens of milliseconds per one-shot; actual warm timings are emitted by `generateDrumlineKit`, including slow cymbals and scored figures. Phone timing is not inferred from host timings.

## Reproduction and evidence

- Focused source tests: `./gradlew --no-daemon :synth:test --tests '*DrumlineTest'`.
- Complete kit/save/play/export tests: `./gradlew --no-daemon :shell:test --tests '*DrumlineKitTest'`.
- Full regression gate: `./gradlew --no-daemon test`.
- Native kit and audition generation: `./gradlew --no-daemon :shell:generateDrumlineKit`.

The generator writes both native kit folders, MPC 3 tracks with MPC 2 twins, full per-pad velocity/take audition strips, all source voices and articulations, grooves through KitPreview, diversity measurements and render timing. It checks PCM after saving and after export; zone boundary selection and two cycles through PadHit; persisted recipe replay; velocity spectral differences and take similarity; and MPC metadata read-back. Exporting a file does not establish that hardware selects its slices correctly.

| Owner gate | Current evidence |
| --- | --- |
| Melodic voices, keygroups, chords | Not applicable: drum battery |
| Articulation | Finite hits and authored roll/riser implemented; native audition accepted |
| Multiple genre kits | Two authored shelf starters implemented; native audition accepted |
| Velocity and RR rendering | Passed all 21 voices and 32 authored pads; see measurements below |
| Save/play/export software | Passed both complete grids in software; hardware remains open |
| MPC 2 combined velocity/RR | RR exception applies per owner's 2026-11-14 decision; velocity still tested |
| Phone and MPC hardware | Not yet verified |
| Kotlin sound | Native revision 4 accepted on 2026-11-15 |
| Render budget | Host: bass 21–23 ms, snare 135 ms, crash 866 ms, roll 4.36 s; fast-render target not met |
| Full control corners and alias-reference comparison | Not yet verified |
| Overall | In progress; never inferred from a successful preview |

## Why it is built this way

- Separate bodies let the section keep several attacks and decays without stereo chorus.
- Fixed player identities and fresh contact preserve the impression of the same line playing again.
- Progressive, frequency-dependent hand loss lets cymbals darken and settle instead of hitting a mute wall.
- Saved recipes and the shared kit grid keep the sound and repeat pool together through normal app operations.

[Native audition and downloadable kits](https://snipsnap-drumline-audition.bertcalm.chatgpt.site/kotlin.html) · [Preferred Python cymbals](https://snipsnap-drumline-audition.bertcalm.chatgpt.site/python.html)

## Verified native round

On 2026-11-14 the focused renderer tests (six), both complete-kit tests (two), and `:shell:generateDrumlineKit` completed with exit 0. The field kit's closest take correlation is 0.999870 and minimum matched-level velocity shape movement is 1.110 dB; street is 0.999924 and 1.393 dB. The shared acceptance thresholds are below 0.99995 correlation and above 0.5 dB shape movement. Bass mallet modal weights were widened after the first saved-bank proof exposed a near-copy at soft velocity. Thresholds were retained.

Each kit has 16 pads × 3 velocity levels × 3 takes. The proof covers all exported PCM, persisted recipes, inclusive velocity boundaries and two repeat cycles per zone. Both kits include an authored cadence. Their combined layouts exercise every voice and finite articulation. The generator measured approximately 60 seconds for the field kit and 47 seconds for street on this host. These bank build times and the one-shot timings leave the fast-render gate open.

Local full regression attempts after merging default branch `42b37f6` exposed stale roster prose, an overlong picker description, starter-order drift and a missing prototype audition catalog. Those were corrected. The unsuccessful runs were stopped after their failures; neither is a full-suite pass. Focused verification of all affected registrations and the complete drumline grids completed with exit 0 (2m 23s): PresetsTest, DrumlinePresetsTest, ConventionTest, EngineCatalogTest, StarterShelfTest, the exact starter-order contract, and DrumlineKitTest. The same 144 affected checks passed again after merging default branch `149e4aa` (exit 0, 2m 50s). Final unfiltered JVM and Android/UI verification is tracked in [PR #1116 checks](https://github.com/BertCalm/SnipSnap/pull/1116/checks). This workspace has no Android SDK. No phone/MPC hardware pass or native sound approval is claimed.

Recorded host evidence: [field grid](evidence/drumline/drumline-field.csv), [street grid](evidence/drumline/drumline-street.csv), and [render times](evidence/drumline/render-times.csv). Timings are warmed single renders under Temurin 21.0.13 on the shared Linux host; they are not phone estimates.

`DrumlinePresets` exposes the exact field/street kit settings as 32 named audition references. They cover all 21 voices and remain outside the factory preset bank pending native listening. No extra sound settings were invented to satisfy the registry.


## Body and attack revision, 2026-11-15

Reviewed against `6406291`, whose full JVM, Android build, native and emulator checks passed. The owner asked for more bass/tenor body, an actual rim clack and more snare snap. This is a sound revision within the existing battery, not a new engine or a claim of native approval.

Model 2 gives the lowest bass and tenor modes more weight and slower positive losses, plus a lower companion-head mode at 0.78 times the head frequency. Upper modes still settle quickly. Larger bass sizes favor the main head over the very low companion; the first candidate did not give the largest bass enough measured body at a soft strike. A short raised-cosine contact drives three extra rim resonances; a brief band-limited noise burst gives the snare wires an earlier leading edge without lengthening their hiss. The constants are listening shapes, not measured drum geometry. The added companion head is a modal approximation, not a coupled air-cavity solver.

The cymbal signal path and player/excitation seeds stay fixed. Model-1 recipes remain supported, and recipes with no model field decode as model 1. New patches and kit version 2 use model 2. The audition includes six previous/revised comparisons at the same controls, alongside both regenerated complete kits. The common house leveler is retained; different transient and decay shapes can still change perceived loudness.

Why these changes: body should persist after the mallet or stick leaves; the rim needs its own hard contact rather than just a brighter head; snare snap should lead the wire decay rather than add a long noise tail. Each new mode has positive loss. The listening risk is excess boom or a clack that dominates the head, which the comparisons are intended to expose.


The final focused run completed with exit 0 (5m 1s): twelve renderer/catalog/revision tests, the two up-to-date complete-kit tests from the preceding run on the same production code, and a fresh complete-kit generator. The revision tests cover 33 bass/tenor voice × velocity comparisons, six snare/rim touch comparisons averaged over three takes, 144 control combinations across the revised drum families, seven published model-1 WAV hashes, missing-version recipe decoding, and cymbal equality across renderer revisions. The full unfiltered JVM suite was then started separately; its completion is not implied by the focused pass. Current branch CI is linked above.

In the Kotlin harness, the filtered low-band late/early energy ratio increases by 1.55–2.28 times across all six tenors and five bass sizes at three touch levels (40–140 ms against 0–25 ms, nominal 35–850 Hz one-pole band). Snare/rim upper attack energy normalized to the first 150 ms increases by 1.72–2.97 times (first 8 ms, nominal 1.5–10 kHz band, three takes averaged). These ratios describe attack/decay shape, not perceived loudness or a listening verdict. [Measurements](evidence/drumline/model2-body-attack.csv).

The regenerated field and street grids retain take diversity: maximum correlation 0.999481 and 0.999499; minimum velocity shape movement 0.990 and 1.327 dB. The original thresholds remain in force. [Field](evidence/drumline/drumline-field-model2.csv) · [Street](evidence/drumline/drumline-street-model2.csv). Warm host timings are approximately 21 ms for bass, 49–52 ms for tenors, 184 ms for snare, 210 ms for rimshot, 860 ms for crash and 6.15 s for the roll; [recorded timings](evidence/drumline/render-times-model2.csv) were taken during a shared-host build and are not isolated benchmarks or phone estimates. The render budget remains open. Full control coverage, alias-reference comparison, native listening and device gates also remain open.


During integration, default branch `70a2b1f` added CLUTTER's picker/roster and other accepted work. The first unfiltered local run was stopped with exit 143 before resolving the merge; it is not a full-suite pass. The merge keeps both engines, all upstream changes, 89 picker entries and 132 starters. The drumline renderer, kit recipes, shared output DSP and drum export path are unchanged by that merge. The published revision's asset links, ZIP contents and hosting size limits were checked; browser playback was not exercised in this environment.

The merged branch's focused verification completed with exit 0: 154 checks covering the complete drumline suite, preset registry, picker catalog, shelf and repository conventions, including both complete kit exports. Full unfiltered verification is restarted after that merge and tracked separately in the PR checks. No full-local pass is claimed.


## Rimshot contrast revision, 2026-11-15

The owner heard the native snare and rimshot as the same sound. The model-2 hard-hit clips were not duplicate files, but first-150-ms correlation was 0.8904 in FIELD and 0.9079 in STREET. The previous test compared each revised attack against its own old version; it did not prove enough separation between snare and rimshot. The shared snare-wire layer masked the distinction.

Model 3 changes only the rimshot source and scored figures that end on it. Its hard-contact modes now include a stronger, longer-lived lower rim resonance, with player-specific detuning. The wire response is quieter and shorter, and the rimshot no longer receives the ordinary snare's added noise-snap burst. The head remains underneath so this is still a head-plus-rim strike. Mode gains, frequencies and lifetimes are listening shapes. All losses stay positive. The ordinary snare, basses, tenors, cymbals and snare-only flam keep their model-2 waveforms; saved models 1 and 2 remain supported. Kit version 3 uses the revised rimshot.

The new direct comparison test spans both kit settings, three velocities and three takes, using level-independent first-150-ms correlation and late-energy fractions. It also pins seven published model-2 WAVs, including old roll/riser accents, and checks all eighteen unaffected voices/figures against model 2. The audition now starts with snare-then-rimshot pairs cut from each exported kit's PCM, soft to hard, and retains the previous rimshot for comparison. These tests are measurable contrast checks; the owner still decides whether the clack is convincing. [Pair measurements](evidence/drumline/model3-snare-rim.csv).

The previous revision at `9a7da32` completed the full local JVM suite with exit 0 (1h 59m) and passed JVM, Android-build and native CI. Its emulator suite failed ETCH's saved-preset recall assertion: the test waited for a disk file, although `savePreset` publishes the refreshed preset list to Compose only after returning from off-main work. The test now waits for the actual named recall control, keeping its existing recall and exported-audio assertions. No Android SDK is available locally; that synchronization fix still needs its device result in CI. No all-gates-passed claim follows from the previous JVM pass.

The renderer-3 focused tests and full kit generator completed with exit 0 (8m 7s): fifteen source/catalog/revision tests and both complete-kit tests. All 18 snare/rim pairs have correlation below 0.430 and normalized late-energy ratio below 0.223. The original round-robin and velocity thresholds remain unchanged. [Field grid](evidence/drumline/drumline-field-model3.csv) · [Street grid](evidence/drumline/drumline-street-model3.csv) · [Host timing](evidence/drumline/render-times-model3.csv). Static asset links, both comparison-strip formats, four ZIP CRCs and hosting size bounds were checked; browser playback remains untested here.

Integration also merges default branch `6e2e40676`, preserving the new PATIO starters and CAIRN studies. The shelf now has 135 starters, with both DRUMLINE and PATIO in the exact-order test, and 89 picker engines. No drumline renderer, shared output DSP or drum export code changes in that merge.

The merged revision passed 158 focused checks with exit 0 (5m 48s), covering the drumline source and complete kits, preset registry, picker, shelf, starter order and repository conventions. The renderer-3 audition is published. A fresh unfiltered JVM run and current-commit CI are tracked separately; acceptance gates remain open as listed above.

## Small bass body revision, 2026-11-15

Review started from `065c840b2de46ceec78e5a66af43b16de7e87619`. The owner heard the small bass body as too similar to the lowest tenor. Their nominal main-head frequencies are 145 Hz and 140 Hz, and both used the same membrane ratio table and lower companion ratio. A pitch difference alone does not establish a different drum identity.

Renderer 4 changes BASS_1 and its appearances in bass unison/split only. A 50% broader finite mallet force, reduced upper-mode weights, and a stronger lower-head resonance at 0.70 times the main-head frequency shift its body away from the tenor. The main-head tuning is retained. These are sound-design shapes, not measured shell geometry or a coupled-head solver. The lower resonance can alter perceived pitch, and too much weight could obscure the small drum's place in the five-bass line; the complete-kit audition is needed alongside the isolated comparison. Other source voices retain renderer-3 audio. Saved renderer-1/2/3 recipes remain supported, including the previous bass figures.

The new body-spectrum check measures the Hann-windowed 20–200 ms body, comparing energy below a tuning-scaled 125 Hz boundary with total 40–800 Hz energy. It covers both kit settings, all three velocities and three takes. The metric is independent of level; it measures a timbral distinction, not realism or listening acceptance. Published model-3 WAV hashes and byte equality for unaffected voices guard compatibility. Existing control-corner, numerical, velocity/take, saved playback and export checks remain unchanged.

The preceding unfiltered renderer-3 local run was stopped with exit 143 to revise the source; it is not a full-suite pass. Current full CI and device results remain tracked in PR #1116. No phone/MPC playback, listening acceptance, render-budget or alias-reference gate is closed by this revision.

The first candidate failed the hard STREET take-2 contrast check: lower-body share 0.3194 against tenor 0.1375 fell short of the required 0.20 difference. Increasing the companion-head gain from 1.25 to 1.60 made all eighteen comparisons pass without lowering the threshold. In the revised Kotlin renders, the minimum bass-minus-tenor lower-body share is 0.2972, and the minimum increase over the previous bass is 0.3967. [Measurements](evidence/drumline/model4-bass-tenor.csv). The ordinary snare, revised rimshot, all tenors, larger basses and cymbals are byte-identical to renderer 3 at the checked touch/take; saved renderer-3 hashes also pass.

The final focused run completed with exit 0 (7m 54s): eighteen source/catalog/revision checks, both complete-kit tests, and a fresh complete-kit generator. No tests were skipped or thresholds lowered. [Field grid](evidence/drumline/drumline-field-model4.csv) · [Street grid](evidence/drumline/drumline-street-model4.csv) · [Host timings](evidence/drumline/render-times-model4.csv). Full regression and device CI are tracked separately; browser and hardware playback remain untested here.

## Approved factory bank, 2026-11-15

After the small-bass revision, the owner approved the published native audition with "Sounds good, proceed". Source `5af588d3bc23a9d586ef4d85302ef0057e4220f3` and Site version `appgver_3e6854fff3e081919744715dfc5d2487` identify that baseline. This is listening approval; phone/MPC hardware and render-speed evidence are separate.

The 32 FIELD/STREET pad recipes now appear in the factory preset bank, covering all 21 voices and articulations. Names are short kit-prefixed labels; settings and audio are unchanged. Kit recipes explicitly retain renderer 4 when a future experimental renderer is added. A checked-in manifest pins every published hard-hit PCM24 WAV, and the factory test renders each restored recipe against those hashes. Registry, name lookup and per-voice coverage checks exercise the actual factory dispatcher. The existing device picker test now recalls STREET SNARE, checks its tuning/damping, edits it and verifies the saved model.

The prior full local run ended without a terminal result or recoverable process handle; it is not counted as passed. Android compilation and native CI passed for the preceding source; full JVM and emulator results still need their terminal outcomes. Factory integration triggers fresh verification. No DSP or output-stage code changes in this round.

Factory integration verification completed with exit 0 (1m 16s): 98 registry, approved-audio and repository-convention checks. Every one of the 32 factory renders matches its published PCM24 WAV hash. A fresh full unfiltered JVM run and current-head device CI are required separately.
