# DRUMLINE

DRUMLINE is a marching battery in progress. The owner preferred the revised Python cymbals: an open crash with lingering plate ring, and progressive damping instead of an abrupt whole-signal cutoff. The native model carries those mechanisms forward. Kotlin's noise generator, filters and output leveling differ from the Python harness, so native sound approval is a separate gate.

The characteristic gesture is coordinated stick attack → independent bodies → overlapping cord/plate ring → finite release.

## What is implemented

Model 1 has 16 source voices: snare, rimshot, six tenor sizes, five bass sizes, crash, choke and touch. Five finite articulations add flam, double-stroke roll, accelerating riser, bass unison and split bass. Defaults are six snare players, three tenor players, one player per bass size and three cymbal players. Player identity remains fixed across takes; each take re-excites the same bodies with fresh force, contact and noise. All synthesis is procedural and mono; no recordings ship.

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
| Articulation | Finite hits and authored roll/riser implemented; native listening pending |
| Multiple genre kits | Two authored shelf starters implemented; native listening pending |
| Velocity and RR rendering | Passed all 21 voices and 32 authored pads; see measurements below |
| Save/play/export software | Passed both complete grids in software; hardware remains open |
| MPC 2 combined velocity/RR | RR exception applies per owner's 2026-11-14 decision; velocity still tested |
| Phone and MPC hardware | Not yet verified |
| Kotlin sound vs preferred Python | Not yet approved |
| Render budget | Host: bass 21–23 ms, snare 135 ms, crash 866 ms, roll 4.36 s; fast-render target not met |
| Full control corners and alias-reference comparison | Not yet verified |
| Overall | In progress; never inferred from a successful preview |

## Why it is built this way

- Separate bodies let the section keep several attacks and decays without stereo chorus.
- Fixed player identities and fresh contact preserve the impression of the same line playing again.
- Progressive, frequency-dependent hand loss lets cymbals darken and settle instead of hitting a mute wall.
- Saved recipes and the shared kit grid keep the sound and repeat pool together through normal app operations.

[Native audition and downloadable kits](https://snipsnap-drumline-audition.bertcalm.chatgpt.site/kotlin.html) · [Preferred Python cymbals](https://snipsnap-drumline-audition.bertcalm.chatgpt.site/)

## Verified native round

On 2026-11-14 the focused renderer tests (six), both complete-kit tests (two), and `:shell:generateDrumlineKit` completed with exit 0. The field kit's closest take correlation is 0.999870 and minimum matched-level velocity shape movement is 1.110 dB; street is 0.999924 and 1.393 dB. The shared acceptance thresholds are below 0.99995 correlation and above 0.5 dB shape movement. Bass mallet modal weights were widened after the first saved-bank proof exposed a near-copy at soft velocity. Thresholds were retained.

Each kit has 16 pads × 3 velocity levels × 3 takes. The proof covers all exported PCM, persisted recipes, inclusive velocity boundaries and two repeat cycles per zone. Both kits include an authored cadence. Their combined layouts exercise every voice and finite articulation. The generator measured approximately 60 seconds for the field kit and 47 seconds for street on this host. These bank build times and the one-shot timings leave the fast-render gate open.

Local full regression attempts after merging default branch `42b37f6` exposed stale roster prose, an overlong picker description, starter-order drift and a missing prototype audition catalog. Those were corrected. The unsuccessful runs were stopped after their failures; neither is a full-suite pass. Focused verification of all affected registrations and the complete drumline grids completed with exit 0 (2m 23s): PresetsTest, DrumlinePresetsTest, ConventionTest, EngineCatalogTest, StarterShelfTest, the exact starter-order contract, and DrumlineKitTest. Final unfiltered JVM and Android/UI verification will be recorded from CI. This workspace has no Android SDK. No phone/MPC hardware pass or native sound approval is claimed.

Recorded host evidence: [field grid](evidence/drumline/drumline-field.csv), [street grid](evidence/drumline/drumline-street.csv), and [render times](evidence/drumline/render-times.csv). Timings are warmed single renders under Temurin 21.0.13 on the shared Linux host; they are not phone estimates.

`DrumlinePresets` exposes the exact field/street kit settings as 32 named audition references. They cover all 21 voices and remain outside the factory preset bank pending native listening. No extra sound settings were invented to satisfy the registry.
