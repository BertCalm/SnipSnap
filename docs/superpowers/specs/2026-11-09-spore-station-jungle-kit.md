# Spore Station: psychedelic jungle kit specification

Version 1.0 · 2026-11-09 · candidate sound design, pending owner listening and MPC hardware check.
Repository baseline: c00edb9970d2967b1716a0ece2cd7146bfbe0b14.

The characteristic gesture is **tight breakbeat → ghost-note chatter → metallic mutation → dark echoes disappearing into space**.

## 1. Scope and landing

Two corresponding 16-pad banks at 170 BPM: Core and Mutations. This is a recipe/kit family, not a new engine. ONSET and SKIN already provide articulated drum bodies; TINES and SIREN provide contrasting punctuation. No new engine registry, classifier implementation, Android picker, feedback solver or HOLD mechanism is needed. Existing source engines and the fixed rack retain their DSP contracts. Synthesis is offline, 44.1 kHz; sources use their existing oversampled renderers. Names contain no gear trademarks and no recordings are sampled.

Space is an analogy expressed by spectral motion and delayed replies, not a physical simulation. Preserve the low-end transient and dry rhythmic core. Mutations occupy equivalent pad positions so a performer can exchange a hit without relearning the layout. The two banks export as separate MPC programs to preserve matching A01–A16 positions.

## 2. Sources and roles

| Pad | Name | Source | Role |
|---|---|---|---|
| A01 | Root Kick | ONSET KICK | Short primary low end |
| A02 | Heel Kick | SKIN KICK | Lighter syncopation |
| A03 | Canopy Crack | SKIN SNARE | Main backbeat |
| A04 | Understory | SKIN SNARE | Softer timbre and lower mixer level for ghosts |
| A05 | Fracture | ONSET SNARE | Accent contrast |
| A06 | Twig | SKIN STICK | Dry punctuation |
| A07 | Needle | SKIN HAT_CLOSED | Short high-frequency pulse |
| A08 | Aperture | SKIN HAT_OPEN | Matching open articulation |
| A09 | Dust | SKIN SHAKER | Quiet subdivision |
| A10 | Halo Ride | SKIN RIDE | Controlled wash |
| A11 | Root Tom | ONSET TOM | Descending fills |
| A12 | Spore Knock | ONSET PERC_PLUCK | Pitched wood/string response |
| A13 | Chrome Seed | ONSET PERC_METAL | Inharmonic reply |
| A14 | Liquid Zap | TINES ZAP | Descending electronic gesture |
| A15 | Canopy Call | SIREN BIRD | Short electronic call |
| A16 | Event Horizon | Reversed SKIN SNARE | Reverse arrival and echo |

All numeric sound settings in SporeStation.parts are candidate shape values, not measured optima. ONSET model version 4 is explicit. SKIN retains model 1: the existing model-2 snare already includes another kit's recording chain. No claims of authentic recorded break sampling.

## 3. Signal flow

```
source macros + velocity --> existing engine [audio, 44.1 kHz output]
  --> reverse (selected pads) --> restrained CRUNCH --> RING (selected)
  --> TAPE (mutations) --> PHASE (selected) --> ECHO (selected)
  --> existing rack tail budget --> 4 ms delivery fade
  --> raw WAV + recipe + pad mixer level
```

The graph uses FxChain's fixed stage order. In this revision there is no added room: initial comparisons isolate source, converter texture, modulation and echo before deciding whether another space stage helps. Kick and paired hats avoid echo. A16 is a whole-source reverse before echo, not a reverse delay. Stereo is supported by delivery and the audition mixer, but this revision deliberately uses mono sources and mono treatments. Spaciousness comes from time/spectrum; no stereo-width claim.

## 4. Macro contract

Controls are recipe-builder controls resolved into ordinary typed patches and FxChain settings. No new app controls or serialized engine type are introduced. Defaults: BODY .50, BITE .35, DUST .15, TWIST .10, SPACE .20. Their low/middle/high sweeps are audition assets. BODY and BITE adjust the source only; the neutral position preserves the authored source.

| Control | Quantity changed | Pair interaction and proof |
|---|---|---|
| BODY | BODY where present, otherwise bounded source DECAY offset | BODY × SPACE: longer source into echoes; measure duration/late energy and audition masking |
| BITE | First available PUNCH/SNAP/BITE/GRIT source control | BITE × DUST: transient spectrum entering converter; compare level-matched attacks |
| DUST | Converter bit/rate reduction and input grit | Verify finite bounded output and a changed waveform at .1/.5/.9 |
| TWIST | Ring wet proportion and phaser depth on eligible color/mutation pads | TWIST × SPACE: moving spectrum into repeats; audition Chrome Seed |
| SPACE | Echo wet level and repeat setting on eligible pads | BODY × SPACE; verify growing late energy on a short source |

TWIST and SPACE are intentionally inactive on protected core kicks/hats; these are role-specific recipe controls, not a claim that five knobs affect every pad. Sweeps use Chrome Seed, where all five are active. No HOLD control: all delivered pads are finite one-shots. The factory recipes are proposed candidates until heard.

## 5. Time, dynamics and performance

Tempo is a kit-side constant, 170 BPM. ECHO uses an exponential TIME mapping from .06 to .35 seconds; invert that map for eighth (.5 beat) and dotted eighth (.75 beat) delays. Quantization tolerance is one output frame. Baked echoes do not follow host tempo. The rack adds at most one second over its input; do not silently extend this contract. Long atmosphere pads are outside this revision.

Each program uses three timbral velocity zones, .3/.6/1.0 source strikes. Soft render peaks match the hard layer once, following Velocity.peakMatch; velocity gain belongs to sampler playback. A04 additionally has a quieter mixer level and a different source recipe. Core and mutation hats share mute group 1 within each program; the ride and other percussion use group 0. Audition grooves implement the same layer selection, mixer gains and 5 ms hat choke fade. Avoid independently normalizing ghost notes in the musical mix.

## 6. Audition contract

Publish a responsive static page with:
- 32 pads, grouped by bank; full raw and loudest-200-ms-RMS matched versions, with clear mode labels.
- Dry source comparisons, with A16's dry comparison explicitly the unreversed original.
- Same two-bar 170 BPM event list for dry/core/mutation grooves, plus a composed hybrid demonstration. Identical-event grooves share one raw bus attenuation and one matched gain so relative balance remains comparable.
- Five control sweeps on Chrome Seed and corner comparisons for interacting pairs.
- Downloadable Core and Mutation MPC 3 XTD / MPC 2 XPM programs with native SnipSnap recipes and velocity layers; separate listening pack; spec, manifest and metrics downloads.
- No autoplay, one active audio element, keyboard-operable native controls, error message on failed playback; no implied listening acceptance.

Raw means source/recipe output without audition loudness matching. Pad levels remain program metadata; raw standalone files do not bake them. Matched single hits support timbre comparison and intentionally do not preserve ghost loudness. Grooves preserve pad levels. The page must make this distinction visible.

## 7. Render budget and determinism

Target tens of milliseconds for a short source on a phone; measure actual desktop batch timings and label the hardware context. These are targets, not acceptance claims. No new solver or unbounded event count. The generator holds the 16 dry source buffers and both banks' rendered pads in memory and reuses those for comparisons/grooves. A future interactive cache key must include source patch JSON/model version, all recipe controls/FX, sample rate, velocity and recipe version; pitch changes invalidate the source cache. No new persistent app cache in this revision.

## 8. Verification and sonic risks

- Determinism: typed recipe JSON round trip produces byte-identical float audio and finite samples.
- Output: finite, nonzero, PCM-range peaks; DC measured per clip; fail on nonfinite values or clipping, report DC and crest rather than assuming tonal tests apply to noise drums.
- Chokes: hats only; sample tails and velocity zones retained in program export. Read back export and compare PCM bytes to generated WAVs; parse XTD/XPM and compare program levels/chokes/layer layout.
- Tempo: delay map within one frame. Groove event timestamps use absolute beat positions, no accumulated integer rounding.
- Timbral velocity: soft and hard must differ after common peak matching. Ghost part is not a gain-only duplicate of main snare.
- Sound risks: overly bright hats, soft snare attack, pitched metal masking backbeats, converter grit mistaken for accidental aliasing, stacked echoes masking subsequent notes. Inspect measurements, then owner judges musical success on the page.
- No seamless-loop claim for groove WAVs: include ring-out. No phone timing or MPC hardware success claim from JVM tests.

Run ./gradlew --no-daemon test and the audition generator; retain exit status. New source engine canaries/registry tests are not needed because no engine is added. Kit-level determinism and export checks are required.

## 9. Integration and rounds

SporeStation.kt owns authored parts and recipe mappings; SynthKits.sporeStation exposes the kit. SporeStationTest owns recipe, tempo and role contracts. SporeStationAuditionGenerator owns rendering, event schedules, program export and metrics; generateSporeStationAudition invokes it. The tracked audition HTML is copied to the generated output. Large generated WAVs stay under ignored testkit; deployment uses a separate Sites checkout. Source changes remain in the SnipSnap checkout.

R1: implement all source roles and two candidate banks, measurements and listening page. R2, after owner feedback: tune sources/treatments and decide whether room, stereo or additional fills earn their cost. No implementation promise that unseen presets are final.

## 10. Verified / proposed / heard ledger

Verified at baseline: OnsetVoice extension roles, SkinVoice roles, SirenVoice.BIRD, TinesVoice.ZAP, PadRecipe.render/process, FxChain fixed order and one-second tail cap, Echo.delayFrames, ArrangedPad velocity/choke fields, RivetKits velocity-layer precedent, AuditionLevel.level and program export helpers.
Proposed: source settings, treatment maps, musical balance, 170 BPM identity and phone render budget.
Measured: see generated manifest.json and validation.json, populated only by the Kotlin generator and verification scripts.
Heard: pending owner audition; no acceptance inferred from passing tests.

## Why it is built this way

- Short kicks and controlled hats leave room for bass and dense edits.
- Related main/ghost/accent snares make fast chops articulate rather than merely repeat.
- Corresponding mutation pads make effects intentional performance choices.
- Existing engines keep every source editable and avoid a new engine's integration cost.
- Ringing buildup is the risky decision; dry/matched comparisons and fixed grooves expose it before presets are accepted.

## As built

Implemented as a recipe family with two 16-pad banks and three velocity layers per pad.
The Kotlin generator produced 79 clips (158 raw/matched WAVs), two MPC kit ZIPs
with 48 WAVs each, and a listening pack. Seven focused JVM tests passed;
all generated recipe replays, finite/peak checks, PCM-layer comparisons and
MPC 3/MPC 2 read-backs passed. Artifact validation and mocked-DOM page-control
checks passed. Browser playback remains unverified because the managed browser
QA tool is unavailable; no screenshot or listening claim is made.

Measured in the Kotlin/JVM cloud container: the Core bank including velocity
layers took 1111 ms and Mutations took 691 ms in the first successful generator
run; individual cached-pass dry sources took 1.19–22.59 ms. These are single-run
observations, not phone benchmarks. Maximum sample peak was 1.0. Maximum full-file
absolute mean was .012343 on the short Heel Kick (dry source .012284); this
asymmetry is disclosed and left unchanged. No new output DC processor is added.

The exporter uses Mpc3Exporter with mpc2Twin and an embedded two-bar clip. Both
programs preserve velocity zones; the demo uses the program's .71 master gain
in addition to pad levels, linear audition velocity gain and its common bus gain.
Waveforms have full ring-out and are not declared seamless loops.

Initial comparisons omit room and stereo widening to isolate source, converter
texture, modulation and echo. Those stages can be evaluated after owner feedback.
Android UI registration, owner listening acceptance, phone timing and physical
MPC loading remain pending.

The raw Chrome Seed SPACE sweep has monotonically increasing energy after
200 ms: 5.00e-6, 1.80e-5 and 4.16e-5 amplitude-squared seconds at .1/.5/.9,
measured from PCM24 WAVs by validate_spore_station.py. This proves that the
control changes the late signal; it does not establish a preferred setting.

Full regression completed: `./gradlew --no-daemon test`, exit 0, 9,239 tests,
zero failures/errors/skips in nine JVM modules (1h 25m 20s). Android is outside
this run; no Android UI or native source was changed.

After the full regression run, upstream advanced to `18378ec9`. The merge
preserves the incoming Cosmic Breaks, HALOBAR and UI changes alongside Spore
Station. Declaration conflicts were resolved without changing Spore Station's
DSP or recipes. Post-merge validation passed 105 focused synth/shell/CLI tests,
full audition regeneration and both MPC exports. All 158 audition WAVs and all
96 exported velocity WAVs are byte-identical to the published build. The full
9,239-test result above is from before this upstream merge; the complete suite
was not repeated on the merged head. Browser and MPC hardware checks remain pending.

## Version 2: completion gates

Baseline inspected: `e4f207da` (2026-11-09). The user's completion gates
supersede the initial audition scope. This remains a drum recipe family,
not a new melodic engine: six pitched voices, keygroups, chord tools and
picked-string STRUM are not applicable. All hits remain finite one-shots.

Three FRESH TAPE entries now cover distinct genres: SPORE JUNGLE (170 BPM),
SPORE DUB (85 BPM), SPORE ELECTRO (130 BPM). Dub uses darker source balance,
reduced attack and quieter hats; Electro changes source decay, blend, snap
and metallic ring. Core and Mutations remain useful original comparisons,
not the evidence for multiple genres.

Every shelf pad carries a three-velocity by three-take grid. Struck SKIN
voices use Restrike; remaining voices vary a timbral macro by ±0.035 without
changing TUNE. All choices are deterministic. Each zone is peak matched;
the sampler still applies velocity gain. B01 adds an accented four-beat
snare roll; B02 adds a four-beat reverse-metal rise, doubling event density
halfway while increasing force and gain. Both end with a four-ms fade.
They are authored finite gestures, not seamless HOLD loops.

`ArrangedPad.velocityTakes` feeds existing `ChainInfo` grids. Saving preserves
slice boundaries and velocity windows. `PadHit.resolve` selects the same
zone and take after reloading. A whole grid carries provenance rather than
a single-hit recipe that would discard its other eight takes on replay.
The native kit folder travels with each download for exact reopening.

### Export gate and unresolved hardware dependency

SFZ exports all nine takes using explicit region offsets, ends, velocity
windows and sequence positions. The round-trip test imports these grids and
compares their boundaries and every PCM sample. MPC 2 XPM uses only the first
take per zone. MPC 3 XTD writes cycle parameters, but the existing writer
explicitly lacks the chain-WAV slice-map chunk (HH1.4, bench-blocked).
Consequently **MPC round-robin hardware acceptance remains incomplete**.
The extra formats do not turn that missing evidence into a passed gate.
No proprietary slice map is invented or claimed verified.

The audition adds 18 clips: three velocity/repeat tests, two performance
phrases and an all-hit pass for each genre. The original 79 comparisons stay
available. Repeated-hit tests cycle takes 0,1,2 twice. Raw mode preserves
velocity gain; matched mode is for timbre comparisons.

### Why it is built this way

- Existing slice-grid playback gives saving, playback and SFZ one articulation model.
- Timbre changes across takes avoid detuning the kick and tom roots.
- Separate roll/riser pads keep individual hits useful for sequencing.
- Native kits preserve the complete grid when MPC import cannot reconstruct it.
- MPC hardware acceptance stays open until the actual slice-map contract is proven.
