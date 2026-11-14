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

| Owner gate | Status before verification |
| --- | --- |
| Melodic voices, keygroups, chords | Not applicable: drum battery |
| Articulation | Finite hits and authored roll/riser implemented; native listening pending |
| Multiple genre kits | Two authored shelf starters implemented; native listening pending |
| Velocity and RR rendering | Native paths implemented; test results recorded below when run |
| Save/play/export software | End-to-end proof implemented; test results recorded below when run |
| MPC 2 combined velocity/RR | RR exception applies per owner's 2026-11-14 decision; velocity still tested |
| Phone and MPC hardware | Not yet verified |
| Kotlin sound vs preferred Python | Not yet approved |
| Render budget | Measurement pending; phone unverified |
| Full control corners and alias-reference comparison | Not yet verified |
| Overall | In progress; never inferred from a successful preview |

## Why it is built this way

- Separate bodies let the section keep several attacks and decays without stereo chorus.
- Fixed player identities and fresh contact preserve the impression of the same line playing again.
- Progressive, frequency-dependent hand loss lets cymbals darken and settle instead of hitting a mute wall.
- Saved recipes and the shared kit grid keep the sound and repeat pool together through normal app operations.
