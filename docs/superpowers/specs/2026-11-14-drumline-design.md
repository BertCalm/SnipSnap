# Drumline: battery kit design and first risk prototype

Status: proposed engineering handoff with a measured Python prototype. Instrument acceptance is **not passed**. No Kotlin implementation or shelf registration has been made. Nothing has been heard or approved by the owner in this session.

Repository truth: SnipSnap `23cde0d1c107039c23fd74ff5c3d2b359a7f6e75`, 2026-11-14. Read root AGENTS.md, repository Luthier and conventions, ENGINE_ACCEPTANCE_GATES.md and INSTRUMENT_ACCEPTANCE_GATES.md. Owner gates supersede older melodic-only conventions.

The characteristic gesture is **coordinated stick attack → distinct drum bodies → overlapping cord buzz and head ring → clean release**.

## 1. Instrument and landing

Build a marching battery whose pad is an articulation played by the appropriate section. A snare pad defaults to six individual snare players. A tenor pad defaults to three players striking the same selected drum within their respective sets. Five bass pads represent five differently sized drums, normally one player per drum. A bass-unison articulation plays the five sizes together. Cymbal articulations use several players, each holding a pair.

Proposed name: DRUMLINE. No samples or manufacturer names are required. The dry section must establish the identity before room effects are considered. Mono output preserves the app/MPC workflow; ensemble size must survive mono without stereo chorus.

Nearest siblings verified in source: SKIN's SNARE is a two-mode body with separately enveloped wire noise and a 170–260 Hz fundamental range. Its TOM is a two-mode body at 90–220 Hz. TREMOR describes up to four coordinated strikers on one hide drum with a bead tray and string cage. Neither inspected model provides independent marching snare drums, multi-tenor sets, a graduated bass line and paired cymbals. Reuse shared output, velocity and kit transport; do not alter accepted SKIN or TREMOR sounds.

Three directions, scored as design judgments (5 is strongest clarity/novelty):

| Direction | Mechanism | Gesture clarity / roster novelty | Risk / cost |
| --- | --- | --- | --- |
| Strong: independent battery | JUXTAPOSE independent struck bodies under one section event score | 5 / 4 | Medium acoustic risk; four build rounds |
| Alternate A: SKIN section recipes | STACK independently re-struck SKIN hits with short delays | 4 / 2 | Low integration cost, high marching-identity risk; two rounds |
| Alternate B: coupled battery | COUPLE drums through an acoustic response network | 3 / 4 | High feedback, tuning and CPU risk; five or more rounds |

The strong direction wins because separate player identities and the correct instrument families address the brief directly. The initial landing is an isolated prototype; the proposed production landing is one drum engine and two authored kits, contingent on listening. Final factory presets follow listening.

## 2. Signal flow and separation

```
voice, size, articulation, velocity, take, macros, model version
  -> stable player identities + finite event score (control rate)
  -> each player's force/contact pulse (176.4 kHz)
  -> each player's head/body modes + snare response or cymbal model
  -> sum section with bounded player compensation
  -> band limit -> decimate -> DC removal -> common level -> tail fade
  -> 44.1 kHz mono hit -> velocity/take grid -> save/play/export
```

The model literally runs damped modes and independently seeded excitation. Human coordination is an analogy implemented as bounded onset and contact distributions. It does not simulate arms, sticks in flight, a woven head, individual cord collisions, air propagation or a real marching formation. Cymbal collision modeling is not present in the prototype.

Two identity levels are essential:

- Player identity, fixed across velocity and take: drum tuning, mode losses and gain balance. It is keyed by instrument/size/player/model.
- Strike identity, changed by take/event: contact duration, position/weight, force and fresh noise. It is keyed by player/take/event/seed/model. Changing a macro must not choose an unrelated player roster.

Keep the section anchored at the trigger. One player starts at zero and others follow within bounded offsets; the event log retains exact starts. Do not independently trim each player after mixing. Shared transport trimming must be tested against section timing and flam grace notes.

## 3. Sources and energy

Snare: start with 8–12 head/body modes and a short bright stick pulse. Add a head-driven cord-response approximation with independent player noise. Rimshot excites head and rim together with altered modal weights; stick click needs its own wood/contact response. Simply raising the level does not make a rimshot.

Tenor: six sizes across the set, with individual membrane roots, losses and shell response. Use smooth excitation-position weights to differentiate center strokes and edge/spock strokes. A spock must have a small bright body, not merely a resampled low tom.

Bass: five roots and losses, starting around 65–150 Hz as an unvalidated shape range. A broad soft mallet pulse and restrained pitch drop preserve the head tone. The bass-unison pad is a five-size chord of drum bodies, not five copies of the lowest drum.

Cymbals: independently excited dense inharmonic modes with fast contact and slower wash, plus a choke envelope and a short hi-hat-style touch made by contacting the pair. Begin with a fitted/reviewed procedural model; the three-voice prototype does not establish cymbal realism.

For each passive mode, pole radius `r = exp(-ln(1000)/(T60 * sampleRate))`, with positive bounded T60, is strictly below one. Positive losses and finite drive bound the response; there is no audio feedback between players. Filtered contact and band-limited output control high-frequency energy. Do not normalize each player's hit: that would erase force differences. Start with section compensation `1/sqrt(N)` and one final level stage, then measure correlation-dependent peak growth at every player-count corner. A limiter is not the stability proof.

Initial head ratios, level weights, roots, contact times, tuning scatter and timing scatter are shape values, not measurements of real marching instruments. The prototype uses ideal-membrane-like ratios; real high-tension composite heads and paired heads need listening/fitting.

## 4. Controls and articulation

Controls are voice-specific. BASS single-drum voices omit PLAYERS and TIGHT because each size has one player. Bass-unison is a distinct articulation; it does not expose a knob that changes the number of bass sizes silently.

| Control | Initial default | Model quantity | Required proof |
| --- | ---: | --- | --- |
| TUNE | .50 | root scales by ±6 semitones, each player's offsets retained | root motion; identity preserved across takes |
| PLAYERS | .65 | 1–8 snare players, 1–4 tenor players; discrete count | correct event count; solo/section contrast at matched level |
| TIGHT | .80 | bounded inter-player timing spread; never random global latency | onset diagnostics and matched transient spread; interaction with PLAYERS |
| STRIKE | .55 | contact width and high-mode excitation | low/mid/high spectral change; interaction with velocity |
| DAMP | .50 | modal loss and cord settling time | independent decay measurement; interaction with STRIKE |

For cymbals, proposed controls are TUNE, PLAYERS, TIGHT, STRIKE and CHOKE. They must earn their own probes. Do not expose unrelated controls just to match another voice's panel.

HOLD policy: finite one-shots and finite authored rudiments, as permitted by the owner gates. No fake sustained drum, no seamless-loop claim, no STRUM. DAMP controls ring. The roll and riser are explicit event scores with fresh contacts, not a pasted waveform. Store their rate/tempo and duration in the recipe rather than assuming render receives kit tempo. Prototype roll: 24 strokes at 65 ms intervals with rising force, followed by an accent at 1.64 s; 3 s including release. Production needs double-stroke accents/grace events and a separate accelerating riser.

## 5. Two authored 16-pad shelf kits

These are arrangements to audition, not accepted factory presets. Both retain all four battery families. Their distinctions must be in source tuning, damping, section size, articulation and score, not only FX.

| Pad | Field Cadence | Street Cadence |
| --- | --- | --- |
| A01 | Six-player snare center | Four-player lower, more open snare center |
| A02 | Snare rimshot section | Open snare rimshot section |
| A03 | Snare flam | Wider accented snare flam |
| A04 | Finite double-stroke roll | Accelerating snare riser |
| A05 | Tenor spock | Tenor spock with open ring |
| A06 | Tenor high | Lower-tuned tenor high |
| A07 | Tenor middle | Lower-tuned tenor middle |
| A08 | Tenor low | Open tenor low |
| A09 | Bass 1, highest | Bass 1, highest, softer mallet |
| A10 | Bass 2 | Bass 2, softer mallet |
| A11 | Bass 3 | Bass 3, open head |
| A12 | Bass 4 | Bass 4, open head |
| A13 | Bass 5, lowest | Bass 5, lowest, longer body |
| A14 | Bass unison | Authored split-bass answer |
| A15 | Cymbal section crash | Smaller cymbal-section crash |
| A16 | Cymbal choke | Short paired-cymbal touch |

Additional engine articulations can expose all six tenor sizes and snare stick clicks without overcrowding the first kit. Quiet snare strokes belong in velocity layers, not solely on a separate ghost-note pad. Suggested bank: three velocity zones × four fresh takes per articulation; validate the supported transport/target limits before freezing the count. Each take re-renders the complete section. A roll take re-strikes every event and player.

## 6. Render budget and cache

Proposed production goal: warmed median under 50 ms and p95 under 100 ms for a default hit on a named supported phone; record cold time and worst corners separately. Full bank generation is asynchronous with progress and cancellation. These are targets, not measurements.

Compute contact kernels and modal coefficients outside the sample loop. Kotlin should use bounded second-order recurrences; the Python vectorized sinusoids plus FIR convolution are a proof harness, not the production implementation. Cache coefficients by model/voice/size/tune/damp/player identity, and contact kernels by strike/velocity/contact identity. A completed PCM cache must include all sound-affecting inputs, including take, seed, articulation score and rate; bound it by bytes. Changing pitch rebuilds coefficients but not player identities or event scores. No geometry solver is required.

Measured raw Python renders on this execution host: SNARE 329.57 ms, TENOR 164.89 ms, BASS 84.23 ms, single timing each. These do not include finishing and do not establish phone performance. The render-time gate is unverified and current Python latency exceeds the proposed production target.

## 7. Integration surfaces verified and proposed

Verified by source read/search at the recorded commit: `Skin.render` velocity/take arguments; `Restrike.canRestrike` and `Restrike.take`; `Velocity.macroSpecsFor` and `Velocity.touchedRender`; `PadHit.resolve`; `StarterKits` and `StarterShelf`; `Dsp.seedFor`, `Dsp.levelTo`, `Dsp.decimate`, `Modes.ring`, and `Tide.bandLimit`. Existing `BreakerKits.kt`, `BreakerKitTest.kt`, `BreakerKitProof.kt` and `BreakerKitGenerator.kt` provide integration examples. No `Drumline*` API exists yet; all such names below are proposed.

Production work must add a versioned patch/JSON dispatch, native velocity path, native re-strike path, preset registration after listening, both kit recipes, starter/shelf entries, generators, deterministic canaries and recipe tests. Preserve the model in saved recipes. Follow the current picker/catalog pattern with an emulator test; the updated repository convention does not require deferring picker work just because the local session lacks an Android SDK. Update README and roadmap when the implementation actually lands.

Use intended drum roles. The older guard excluding KICK/SNARE/TOM applies to melodic engines, not this requested drum engine. Verify how authored roles and the current classifier treat long cymbal tails and finite rudiments; do not cut a valid tail solely to dodge a length heuristic.

## 8. Evidence and acceptance ledger

| Gate | Status now | Evidence needed to close |
| --- | --- | --- |
| Six melodic voices, keygroups, chord tools | Not applicable | Drum battery; no melodic-engine claim |
| Appropriate articulation | Not yet verified | Finite hits designed; Python finite snare roll rendered; production roll/riser and listening outstanding |
| Multiple distinct genre kits on shelf | Not yet verified | Both authored layouts above must be implemented, balanced, registered and heard |
| Timbral velocity | Python probe pass only | All production articulations, every bank zone and matched-level auditions |
| Round-robin rendering | Python probe pass only | All production take pairs, invariant drum identity, fresh section strikes |
| Save/load | Not yet verified | Exact recipe replay and retained PCM grid, zone/take counts, settings and score |
| Playback | Not yet verified | Zone boundaries, cycles/wrap, per-zone selection, mute/choke and exact frame windows through PadHit and native player |
| MPC 3 export | Not yet verified | Read-back plus actual slice-map/window/selection playback on MPC; existing repository hardware issue remains open |
| MPC 2 velocity | Not yet verified | Velocity zones and first-take PCM preserved and loaded |
| MPC 2 combined velocity × RR | Not applicable to RR gate | Owner's 2026-11-14 format exception; do not advertise preserved combined cycling |
| Numerical/aliasing | Partial Python evidence | Full corners, high-rate reference, raw bounds, invalid macros and Kotlin probes |
| Render time | Not yet verified | Named-phone warmed/cold timing and bank-generation cost |
| Real drumline character | Not yet verified | Owner auditions of solo/section, touch/takes, rudiments and both full kit grooves |
| Overall instrument | Not passed | All applicable rows above need evidence |

Python `drumline.py` exited 0. All seven narrowly scoped assertions passed for the default three source families: deterministic PCM, finite/peak/DC, matched-level velocity distinction, distinct takes, active controls, independent attack/decay window and bounded onset spread. These are not the repository acceptance gates.

Matched-level soft/hard log-spectral distances: snare 15.42 dB, tenor 7.45 dB, bass 4.57 dB. Matched-level take-difference RMS: .546, .196, .031 respectively. Negative controls reject a scaled copy (spectral distance approximately zero) and a copied take (difference zero). Values establish difference, not realism or appropriate magnitude.

The post-bandlimit snare energy above output Nyquist is approximately 0.0000569 of the measured spectrum before decimation. This is only a proxy, not a passed alias-error comparison against a higher-rate reference. Python uses NumPy RNG plus the skill's approximate decimator and RMS leveling; it is not sample-identical to Dsp.Noise or Kotlin Loudness.of.

## 9. Build rounds, sonic risks and tests

1. Fit the snare and tenor source character, then test solo versus sections at matched level. Reject a chorus-like pitched smear, handclap attack or broad hiss. Keep player tuning fixed through repeated strikes.
2. Add five bass sizes and cymbal families, all articulations, native velocity and full re-strike banks. Inspect every soft/hard pair and every take pair; waveform difference alone cannot approve sound.
3. Integrate both kits, authored rudiments and save/play/export; exercise actual zone boundaries and complete cycles after reload. Verify that transport start trimming does not erase grace notes or diversity. Render grooves from the exported PCM as well as source recipes.
4. Run the complete JVM suite (`./gradlew --no-daemon test`, inspect exit status), appropriate Android/native/emulator checks, macro sweeps and corners, then phone/MPC bench and owner listening. A green test cannot close a hearing or hardware row.

Additional test obligations: unknown macros have no effect; non-finite controls fall back; player/take stability across macro edits; raw energy bounds over all count/tightness/strike corners; head roots within stated tolerances for tenor/bass; no requirement for clean pitch confidence on snare or cymbals; prospective alias test against 8× reference; long-tail and class handling; trademark blocklist; JSON version replay. Prove a growth test catches deliberately injected late energy.

Risks to settle by listening: tight sections may read as one oversized drum; loose sections may read as flams on every note; high-tension heads may sound like tuned toms; the one-way snare noise approximation may hiss rather than chatter; many normalized hits may erase accents; a small take difference may be numerically distinct but perceptually repetitive. Six players and the timing/tuning spread remain hypotheses until heard.

## Why it is built this way

- Each snare player owns a drum, so the section contains several attacks and decays rather than a louder duplicate.
- Player tuning stays stable while contact changes, so repeated hits should belong to the same line.
- Bass sizes stay on separate pads so split parts can move through the line as real bass parts do.
- Velocity changes excitation before finishing; turning down one hard hit cannot supply a convincing soft stroke.
- The risky decision is procedural marching-snare/cymbal realism. The prototype is deliberately an audition candidate, and the acceptance ledger prevents numerical success from being mistaken for a finished instrument.


## As built: second Python audition round

The owner called the first published audition a “good start”. This approves the starting direction only. On the subsequent “Start” instruction, a second source study was rendered. Round 1's renderer, files and published page remain available unchanged for comparison.

Round 2 contains 16 source voices: center snare and rimshot, six tenor sizes, five individual bass sizes, open crash, crash/choke and short paired-cymbal touch. Defaults are six snare players, three tenor players, one player per bass size and three cymbal players. Snare excitation now includes three resonant cord-noise bands with a head-rate gate; rimshot adds separately decaying rim modes. Cymbal players use 112 stable inharmonic modes and a short contact wash. This is a procedural approximation of paired cymbals, not a simulation of two plates colliding. Constants remain shape values.

The finite studies are a 32-stroke double roll plus release, four alternating flams, an accelerating riser, a split-bass answer, bass unison and two full battery cadences at 120 and 100 BPM. Each event has its own strike identity and retained score. Left/right labels select fresh contact streams; the model does not simulate arm mechanics. Event velocity acts before the one shared phrase output stage.

Validation: `round2.py` and `check_round2.py` both exited 0. The renderer exercised three velocities times three takes on all 16 voices (144 renders), all finite and within output/DC bounds. It checked every within-zone take pair and both soft-to-hard spectral comparisons at matched level, default determinism and independent attack/decay windows. Mechanism checks passed for fixed player tuning across velocity/takes, invalid controls, five ordered bass fundamentals, cymbal choke release, preserved crash tail, selected raw corners and negative controls for copied gain and injected late growth.

Measured bass dominant frequencies: 145.01, 116.08, 92.86, 74.02 and 59.22 Hz. The largest tested raw corner peak was 2.456. Cymbal choke RMS after 300 ms was below 4e-11; the open crash tail was 0.0353. Single default render plus finishing times ranged from 59.5 ms to 2665.5 ms on the execution host. The cymbal implementation is too slow for the proposed instant-render budget; the production port needs recurrence-based modes and cached coefficients. These are Python measurements, not Kotlin or phone measurements. All rendered takes were checked in memory; this round does not claim a persisted 144-sample kit bank or save/play/export evidence.

Production gates remain open: no Kotlin source, shelf kit, app picker, full macro/alias sweep, saved-bank selection, native playback or MPC export/hardware evidence has been added. No Gradle run is claimed for this Python-only round. Round 2 listening has not yet been approved. The website offers 58 new WAV clips, source and measurement downloads, with the original 13 clips retained.

### Why it is built this way

- A separate rim response changes the rimshot's attack and spectrum instead of merely increasing the center stroke's level.
- Bass roots and contact widths change by drum size, giving split parts distinct voices rather than shifted copies.
- Every scored stroke is freshly rendered, and the complete phrase is levelled once so rebound strokes remain quieter than accents.
- Open and choked cymbals share a player identity, so muting changes the release of the same section. The unresolved risk is whether the procedural plate spectrum is convincing by ear.


## Cymbal revision after owner listening

Owner feedback: “The crash should have a bit of ring. The damp is too abrupt so sounds artificial.” This is a request to revise the cymbals, not approval of their previous sound.

The revision lengthens the life of a broad group of midrange plate modes and extends the crash render to retain their release. Hand damping now integrates a 140 ms smooth contact ramp into each mode's decay, starting near 180 ms with an 8 ms offset between players. Higher modes lose energy faster than lower modes. The old whole-signal cosine gate reached zero after 55 ms; the new modal loss has no forced-zero endpoint. Short touch also has a longer natural decay. A separate noise seed prevents changing render length from changing the modal strike draws. Non-cymbal voices delegate to round 2 unchanged.

Python `cymbal_revision.py` exited 0: 27 velocity/take renders finite and bounded, timbral velocity and within-zone take diversity, deterministic defaults, increased crash ring, gradual choke release and eventual settling. Relative to the first 150 ms, crash RMS in the 0.8–1.2 s window increased from 0.0853 to 0.1643. Choke RMS in the 0.28–0.4 s window is now 0.2147 of attack RMS instead of approximately zero; its final 20 ms peak is below 6e-10. An independent check verified monotonic cumulative contact loss. These measurements establish the intended change, not realism.

Three before/after clips use a shared first-150-ms level reference. Current cymbal voice clips, their soft/repeated/solo examples and both cadence mixes were rebuilt and published. Previous audio and the complete previous page remain available. New sound approval, production integration and all open instrument gates remain pending. Browser/audio playback was not exercised in this environment; static links, WAV headers, archive integrity and native publication success were checked.

Why it is built this way: longer-lived plate modes supply audible ring without adding reverb; mode-dependent hand loss darkens the release instead of silencing the whole spectrum together; matching the attack for comparisons keeps extra tail energy from misleading the volume comparison.

## Kotlin implementation round — 2026-11-14

The owner's “Proceed” follows the preferred cymbal revision. Model 1 now implements the section identities, fresh contact forces, lingering plate ring and progressive modal hand loss in Kotlin. Two sixteen-pad starters cover all twenty-one voices and finite articulations between them, with three velocity zones and three strikes per zone. Picker edits and saved recipes retain the renderer model.

The native port substitutes Dsp.Noise, recurrent modal oscillators, native filters and the shared output leveler. It is therefore a new listening candidate, not a claim of sample-identical Python sound. The preferred Python page is retained alongside the native audition. Current implementation, reproduction commands and evidence are maintained in [DRUMLINE](../../DRUMLINE.md). Hardware, native listening, full alias-reference validation and phone performance cannot be inferred from software test results.
