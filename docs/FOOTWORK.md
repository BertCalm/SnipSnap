# FOOTWORK: percussive dance and street-object drumkits

Status: R0 sound rejected by the owner: “Doesn't really sound like the real sounds”. The owner now recognizes the metallic ta-ee study as tap; exact reference matching remains approximate. The owner selected the heavier full-dynamics direction for continuation. Native kit listening and device acceptance remain open. Source assumption: entirely synthesized; no performance recordings are used. Working name FOOTWORK; the stage production named in the brief is a research reference, not a kit name.

Repository baseline: `6e2e4067634a06160590b06107d2f031f48e2e32`. APIs inspected: `Clutter.render`, `Clutter.renderRaw`, `ClutterKits`, `Modes.ring`, `Dsp.seedFor`, `ArrangedPad.velocityTakes`, `StarterKits`, `StarterShelf`. The registry contains 99 `*Patch.ENGINE` cases at this baseline; the older inventory is not used as the current count.

The characteristic gesture is **toe tick → heel knock → two-way brush → weight-bearing stamp → hand/body answer → ensemble landing**.

## What is being built

A drumkit family whose rhythm comes from bodies moving on surfaces. The first four pads must remain distinguishable at matched loudness: a stamp is not a pitched kick preset, a heel is not a quieter toe, and a shuffle is not one click repeated. Brushes have a direction and travel envelope; a flap changes from a brush to a weight-bearing ball contact. Hand claps and thigh slaps supply backbeats. Buckets, boards and loose metal extend the dance rather than replacing it with a generic junk kit.

The minimum delivery is three distinct 18-pad shelf kits. Sixteen finite voices occupy A01–A16; B01 is an authored roll and B02 an authored riser. Every pad, including both gestures, has three velocity layers and four genuinely different takes. Total: 648 cells. The exported playback path must retain all cells.

## Three directions

| Direction | Operator | Gesture / novelty / risk / cost |
|---|---|---|
| **Recommended: dancer plus object ensemble** | SPLICE | Clear shoe/floor gestures plus existing object models; adds contact sequences missing from CLUTTER; moderate realism risk, lowest integration cost; proof → Kotlin/kit delivery → device gate |
| Suspended stage instrument | COUPLE | Several performers excite one shared floor with ringing memory; strongest shared-body identity; harder energy, mixing and export parity problems; at least one additional model-validation round |
| Choreographed machine ensemble | PORT, analogy | Foot positions conduct objects in call-and-response; clear phrase identity but more generative and less directly playable; timing/UI design cost and greater distance from the request |

The first direction wins because individual pads remain playable as drums and still combine into recognizable movement. No new general-purpose engine is proposed until the missing gesture model proves that it needs one.

Nearest siblings: **CLUTTER** already models floor stamps, buckets, boards and loose metal, but its existing object set has no toe/heel/brush articulation vocabulary. **REVEL** already models a performed drum ensemble and microphone placement, but does not model shoe contact or a travelling scrape. Reuse CLUTTER's established objects without altering its accepted sounds. FOOTWORK needs a focused shoe/body source module and a kit renderer with an event scheduler. A kit-side source recipe is sufficient unless an editable picker is separately commissioned; it must not pretend to be an existing single-hit Patch.

## Pad map and genre differences

| Slot | Role | Tap Jazz · 124 BPM | Street Breaks · 110 BPM | Industrial Stepping · 132 BPM |
|---|---|---|---|---|
| A01 | Stamp | suspended wooden stage | heavy sole on pavement | work boot on braced deck |
| A02 | Heel | loaded heel plate | thick heel knock | heavy heel on grate |
| A03 | Toe | bright toe plate | toe scrape/tick | steel-edged toe |
| A04 | Ball step | planted forefoot | rubber sole slap | flat boot landing |
| A05 | Forward brush | light metal scuff | gritty brush | steel scrape |
| A06 | Back brush | shorter return stroke | reverse-direction scuff | dragging return |
| A07 | Shuffle | forward/back brush | hard double scuff | grate sweep pair |
| A08 | Flap | brush then ball step | brush then sole slap | scrape then landing |
| A09 | Hand clap | single close clap | loose small group | tight forceful group |
| A10 | Thigh slap | light skin slap | broad low slap | short damped accent |
| A11 | Chest pat | rounded body knock | chest thud | low body accent |
| A12 | Finger click | close dry click | doubled click | sharp knuckle/click |
| A13 | Bucket | palm on small bucket | stick on large bucket | hard rim/body strike |
| A14 | Broom | light bristle swish | broom sweep/stab | stiff-bristle scrape |
| A15 | Loose metal | restrained lid touch | lid/handle accent | bin lid strike |
| A16 | Board | small wooden clack | broad board slap | braced board crack |
| B01 | Roll | alternating toe/heel roll | brush-step roll | accelerating boot alternation |
| B02 | Riser | brush-to-stamp crescendo | gathering feet and claps | foot roll into metal landing |

These are proposed recipes, not 54 heard presets. Genre identity must survive disabling room/effects. Kits differ in surface, contact, source balance, roles, articulation and pattern, not only BPM or reverb. Original four-bar patterns will be authored; no choreography or audio is copied from a show.

## Model and signal flow

```
voice + surface + velocity + take + source version
       -> deterministic contact plan (time, force, position, contact width, direction)
       -> shoe/skin pulse or travelling roughness excitation @176.4 kHz
       -> contact modes + driven floor/body modes
       -> sum with independent object strikes where the pad calls for them
       -> anti-alias filter -> 4:1 decimation -> DC removal -> fixed voice gain -> tail fade
       -> 44.1 kHz mono source grid -> saved kit -> native/SFZ/MPC playback
```

A damped resonator bank is literal DSP, with pole radius `exp(-ln(1000)/(rate*t60)) < 1`. Contact width shortens with increasing force and filters the excitation spectrum. Floor coupling is a **one-way driven response**, not bidirectional feedback. Modes/decays are authored shape hypotheses until calibrated or heard. We do not simulate a full shoe, human balance, elastic contact geometry or a room wave equation.

The proposed physical brush is a travelling roughness field. The current native study instead uses distinct pressure envelopes, noise bands and chatter trajectories for forward/back motion; it does not simulate spatial contact geometry. A forward/back pair does not reuse identical PCM. Multi-contact gestures preserve one plan of accents and directions while each impact gets its own seed. Seed derivation must include source version, voice, take and event index, with stable ordering. Left/right variation is contact-position and damping variation in mono, not a panning trick.

The Python proof follows the Luthier prototype-template workflow and uses the repository `snipkit` conditioning/modal helpers. Its gain-compensated modal option differs from `Modes.ring`'s default arithmetic: the Kotlin port must preserve that explicitly rather than silently substituting an uncompensated bank.

### Controls for the proposed shoe/body source

| Control | Default | Quantity changed | Interaction and required proof |
|---|---:|---|---|
| TUNE | .50 | bounded shoe/floor mode-frequency scale, ±4 semitones | TUNE × FLOOR must keep toe/heel identities; no sub-only stamp |
| CONTACT | .50 | impulse duration and initial friction texture | CONTACT × velocity changes normalized attack spectrum |
| WEIGHT | .50 | force split into high contact modes versus low support modes | WEIGHT × FLOOR changes floor-energy ratio at common gain |
| FLOOR | .40 | support participation/position on the kit's selected surface | surface topology comes from the recipe; prove effect at low/mid settings |
| DAMP | .50 | frequency-dependent loss from foot loading/grip | DAMP × CONTACT shortens ring without deleting the leading click |

Velocity is not an extra macro. It changes force, contact width, spectral weighting and, where appropriate, whether a loose part moves. R0 implements voice-specific contact laws rather than this complete macro surface.

### Dynamics and repeats

Use velocities .30/.65/1.00 and four takes per layer. Sources retain force-dependent dynamics using fixed calibration gains; do not normalize each foot contact. The implementation must document exactly where the sampler adds its velocity gain, so source shaping and playback scaling are neither accidentally doubled nor lost. Render tests measure timbral difference at matched level as well as loudness. Take tests must distinguish actual waveform changes from gain-only or offset-only copies and bound unintended loudness jumps.

CLUTTER's public `render` currently peak-matches and clamps physical velocity to at least .05. Its kit calibration is also deliberately different from this foot-source policy. Reusing it requires an explicit adapter with zero-velocity silence and a tested level law, or use of its inspected internal raw path with a new fixed calibration. Never change existing CLUTTER behavior to make FOOTWORK pass.

### Articulation

One-shots are finite; HOLD and melodic STRUM are not applicable to these foot/body/object drums. Brushes have an authored finite travel duration. The roll is a finite alternate-foot performance with new strikes; the riser is a finite increase in density/force ending in a deliberate landing. No seamless sustain is claimed. CLUTTER's owner-specific roll exemption does not apply here.

Bound gestures to 40 contacts (the native riser currently uses 33), minimum 20 ms event spacing, maximum four beats plus a 1.5-second decay tail. Within a phrase, contact drive may excite a still-ringing support. Do not splice a fresh floor tail onto each hit if that destroys continuity. The proposed implementation should retain the shared linear floor response through the event buffer; no nonlinear global state is needed.

## Render budget and engineering landing

Target warmed single-hit rendering in tens of milliseconds; initial ceiling 50 ms median and 100 ms worst on the development JVM, then measure on the phone. These are targets, not current phone measurements. Use 8–16 contact modes plus 4–8 support modes, bounded source lengths (0.2–1.5 seconds), and precomputed filter coefficients. Cache immutable mode tables by surface, voice, TUNE, FLOOR, DAMP and source version; keep velocity/take excitation out of that cache. Multi-hit offline pad and whole-kit generation get separate measured budgets and cancellation checkpoints.

Kotlin landing: new focused source and kit arranger under `synth/src/main/kotlin/com/snipsnap/synth/`; populate `ArrangedPad.velocityTakes`; register three stable starter IDs exactly once in `StarterKits` and `StarterShelf`. Source metadata records version, recipe, velocity and take grid. Preserve old recipe versions when sound changes. Use the existing export paths rather than inventing a new sampler format. A full engine/Patch registration is not needed merely to expose three kits; if an editable engine is later required, apply the complete registration checklist then.

## Acceptance contract: no shortcuts

| Gate | Required proof | Current state |
|---|---|---|
| Melodic six voices/keygroups/chords | Not applicable: no melodic instrument is delivered | N/A |
| HOLD / STRUM | Finite foot, body and object percussion; no picked/thumbed string voice | N/A |
| Percussion roll/riser | Authored B01/B02 on every kit; per-event fresh strikes; complete velocity/take grids | Native 648-cell proof passed; listening open |
| Multiple genre shelf kits | Three materially distinct kits, registered once each; render/preflight test every starter | Three native layouts registered; genre listening open |
| Rendering | All 648 cells finite/bounded/DC/tail-safe; deterministic recipes; zero velocity; timbral dynamics; real RR diversity | All 648 native cells passed |
| Saving | Save/load complete source metadata, zones, takes, frame windows and PCM for all 648 cells | Software proof passed |
| Playback | Native selection at every zone boundary, RR wrap, gain-applied windows; device contact/stop behavior | Selection, cycling and gain proof passed; device behavior open |
| SFZ | Complete regions, import/replay PCM and velocity/RR selection | Software proof passed |
| MPC 3 | Complete zone/take grid, slice indices and packed PCM read-back; no first-take fallback | PCM/cycle-field read-back only; missing slice-map export keeps RR gate open |
| MPC 2 | Document the owner's repository-wide format-limit exception; never market it as full RR | Format exception only |
| Sound acceptance | Hear isolated contacts, pairs, composite steps, dynamic/repeat grids and each genre groove | Open |
| Device acceptance | Owner checks phone and physical MPC with the delivered files | Open |

A browser's play button is not native-kit playback evidence. A manifest saying “four takes” is not evidence that playback selects all four. Passing the Python sound proof closes neither export nor shelf gates. Prior approvals of other kits do not apply to FOOTWORK.

## Proof sequence and sonic risks

R0 first proves toe/heel/stamp/brush/clap/thigh, then shuffle and a toe/heel flam. The static audition contains each voice solo, all three velocity layers/four takes, and an original 112 BPM dance phrase. Judge whether the pair distinctions survive loudness matching, whether brushes scrape rather than hiss, and whether the low support feels like a floor rather than a tuned bass drum. A short candidate bank may sound wooden, bell-like, papery or synthetic; those are listening failures to fix before populating the entire shelf.

R1 ports accepted contact behavior, adds the remaining body/object roles and macro probes, and verifies Python/Kotlin waveform or measured-feature parity with explicitly disclosed RNG/filter differences. R2 adds three full kits, rolls/risers and all 648-cell end-to-end checks. R3 is the full-kit listening/device acceptance pass. No release “complete” label before every applicable row closes.

## Verified / proposed / heard

- **Verified:** repository paths and APIs above; existing CLUTTER footprint and kit calibration; legal 3×4 grid dimensions in `ArrangedPad`; current owner gate definitions.
- **Measured:** R0 passes 96-cell source checks: every cell deterministically replays; every layer has four different normalized waveforms; every take changes timbre and level across velocity. Maximum peak is 0.700001 or less and maximum absolute DC is below 0.000054. All 105 WAV headers/hashes and ZIP integrity pass. See `prototype/footwork/r0-report.json`. These are Python source/render checks only.
- **Proposed:** all genre layouts, new control surface, render-budget targets, remaining voices and Kotlin integration.
- **Heard:** the owner rejected R0 for failing to resemble the real sounds. After the metallic ta-ee revision, the owner said it “sounds like tap to me,” while noting the reference match is not exact. This passed recognizable tap character. The owner subsequently requested more heft and selected the resulting full-dynamics version. This selects the source direction, not every new native body/object voice, genre kit or device gate.

## Reference reset after the first listen

Code review found that six nominally different contacts share the same four-mode ratio pattern, with retuning and gain differences. The brush has an envelope-shaped noise source, not the travelling roughness/direction model described in the proposal. The shuffle repeats this brush mechanism twice. Distinct normalized waveforms and passing health checks did not prove recognizable shoe or body-percussion identity.

The revised experiment starts with one tap-shoe contact against a credited recording, with four training and four disjoint check strikes. It replaces the sparse resonator bank with broad filtered-noise contact envelopes. See [reference-reset reproduction and provenance](../prototype/footwork/reference_reset/README.md). The recording is on tiles in an echoing room, not a verified toe/heel-separated wooden-stage reference. No reference PCM is loaded at synthesis time. Every remaining voice and full-kit gate stays open.

The audition now exposes recorded reference, rejected model and new candidate separately. Owner listening, not spectrum distance, decides whether this is a useful contact source. The full kit will not inherit an approval from this single anchor.

## Gesture-family audition

The Python study now supplies toe, heel, forward/back brushes, shuffle, flap, an authored roll and an accelerating riser, plus an original 116 BPM phrase. These extend the recognizable ta-ee source but remain unapproved as distinct gestures. Small-metal is a provisional construction choice; the owner did not select between small/open. This is not the planned three-kit shelf delivery. Native velocity/round-robin persistence, playback and export remain open. See `prototype/footwork/reference_reset/dance_gestures.py` and its generated report for the current prototype evidence.

## Native full-dynamics implementation

The owner selected full dynamics. Native model 2 separates the new stamp (heel-to-sole loading) and ball step (a tight forefoot impact), after model 1 had overly correlated first-bank attacks. Model 1 remains explicitly renderable; model 2 retains its seed law and leaves the selected toe/heel/brush sources byte-identical. `Footwork.kt` ports the accepted contact coefficients, metallic ta-ee response, brushes and weighted floor support to a deterministic JVM source. `FootworkData.kt` is generated coefficient data, not waveform storage. Gaussian draws come from `Dsp.Noise`; the house band-limit/decimator replaces SciPy's output filter. Exact PCM equality with Python is not claimed. Across 72 retained contact cells, measured native/Python RMS ratios are 0.950–1.113; the maximum 100–700 Hz energy-fraction difference is 0.076 and maximum T90 difference is 2.70 ms. See `native-parity-report.json`. `native_parity.py` measures level, 100–700 Hz energy fraction and T90 over 72 saved native contact cells against the selected Python family. Native listening is a separate gate.

`FootworkKits` defines Tap Jazz (124 BPM), Street Breaks (110 BPM) and Industrial Stepping (132 BPM), with eighteen pads each. Every role has three force levels and four takes, including finite rolls/risers. Body voices are new short skin/noise models; bucket, loose metal and board adapt CLUTTER's raw source using one fixed full-force calibration shared by every velocity and take. Existing CLUTTER behavior is unchanged. Genre surfaces vary damping, low-mid support, upper contact balance and object tool settings; their subjective identities are not approved merely by having different waveforms.

The full-dynamics source gain stays fixed at 2. Tap Jazz uses unity genre gain; Street/Industrial use a fixed .85 safety calibration, after a hard Street stamp exceeded the peak budget during development. The new hand-clap source uses a fixed .45 calibration, not per-take normalization. Individual source clips retain their force-dependent levels.

### Velocity law through native playback

The native pad resolver applies `padLevel * liveVelocity` at centered pan. FOOTWORK pads use level 1. Each stored velocity anchor contains `source(anchorVelocity) / anchorVelocity`, so playing at that anchor reproduces the selected source dynamics once, rather than attenuating it twice. Intermediate live velocities use normal continuous pad gain and the selected zone. Round-trip checks compare decoded, gain-applied audio to the source at every authored anchor. The native/SFZ rendering, saved-grid and playback tests cover all 648 cells. MPC 3 contains the PCM and cycle fields, but its shared exporter does not write the chain-WAV slice map; round-robin export therefore remains open, not merely untested on hardware.

### Native delivery evidence

The generator `:shell:generateFootworkKits` checks source health/determinism/timbral dynamics/real repeat differences, saves complete kits, reloads exact PCM windows, exercises every MIDI velocity and repeated cycles, round-trips SFZ and reads actual MPC 3 ACVS slice/zone metadata plus packed WAVs. The browser audition uses the saved native playback windows and gains. Browser previews are 16-bit PCM for size; exported packages retain 24-bit PCM. MPC 2 remains the owner's format exception and is not advertised as a complete round-robin delivery. Phone and physical MPC acceptance remain open. MPC 3 RR also has a concrete export blocker: the shared writer documents HH1.4, a missing chain-WAV slice map. Native and SFZ checks do not close this gate. The repository has commercial `.xtd` fixtures but no chain-WAV slice-map fixture; a working sliced/RR program saved on an MPC, including its WAV/data folder, is needed to establish and test that encoding.

Generation is reproducible into a new empty directory with `./gradlew --offline --no-daemon :shell:generateFootworkKits -PfootworkOutput=/absolute/new/directory`. `FootworkKitTest` runs the software delivery proof; `FootworkTest` checks deterministic source behavior, invalid inputs and genre changes beyond gain/tempo. Full-dynamics source selection does not approve native noise/filter differences or the newly added voices.

## Why it is built this way

- Shoe contact and floor support are separate, so toe, heel and stamp can differ in attack and body instead of just pitch or level.
- Movement has a contact sequence; independent strikes make shuffles and flaps behave like gestures rather than delayed copies.
- Existing object models provide the stage ensemble while development effort goes into the missing dancer.
- Fixed source calibration preserves the relationship between light steps and heavy landings.
- Explicit gate evidence prevents a convincing browser demo from being mistaken for a complete sampler instrument.

### Model 3: pad identity revision

Owner feedback on model 2: Tap Jazz 02 Heel and 03 Toe sound alike; 09 Hand Clap,
10 Thigh Slap, 11 Chest Pat, 12 Finger Click, 13 Bucket and 16 Board do not evoke
their labels convincingly. Numerical attack separation did not establish perceptual identity.

Model 3 replaces Heel with a short loaded knock and reduces its free metallic response;
Toe keeps the earlier ta-ee source. Clap models finger-edge then cupped-palm contact;
thigh uses a bright palm/finger slap with heavily damped body; chest uses a broad low
body response; finger click uses a broadband crack and short palm cavity response with
no sustained sine. Bucket now uses a hand transient plus inharmonic plastic base/wall
modes; Board uses hard contact and multiple short wood modes with a secondary floor
contact. These two roles no longer reuse the Clutter presets. Loose Metal still does.
Roll and riser inherit the new heel. The other unflagged single gestures are unchanged.

Why it is built this way:

- Different arrival times and resonance families give each action its own mechanism.
- The heel drops most delayed plate chatter so toe can retain that defining response.
- Contact brightness and duration change with force; fixed gain preserves full dynamics.
- Seeded arrival and mode variation supply four repeat takes without storing recordings.
- Realism is the unresolved risk: these are authored hypotheses, not calibrated physical replicas.

Verified source baseline before revision: `3a1d28ce`. Model 1 and 2 remain explicitly
renderable with their existing seed law. The public reference pages researched for body
sounds could not be downloaded by the analysis environment (HTTP 403); no coefficients
were fitted to those recordings and none are included in the kit. The audition compares
previous/new at original playback gain and provides all three velocity levels and four takes.
MPC 3's missing chain-WAV slice map remains an export blocker, not a passed RR gate.

### Street Breaks identity feedback and reference study

The owner subsequently rejected Street Breaks 01/02 as alike and 09–13/16 as
unconvincing. Native model 3 is not sound-approved. The surface filter on shared
body/object sources did not supply a separately convincing Street voice set.

`prototype/footwork/street_reference/` now contains a five-source Python benchmark
(stamp, heel, clap, chest thump and finger snap), fitted to separately labeled CC0
recordings with held-out repeat examples. This is a source study, not native model 4.
Its 60-cell render checks do not establish save/playback/export or listening acceptance.
Thigh, bucket and board remain unresolved; no unsuitable body sounds are relabeled
as substitutes. Native downloads remain the previous unapproved candidates.

### Industrial, kit contrast and performance rejection

The owner reports that Industrial shares Street's failed source identities, all
three kits are extremely similar, grooves sound sequential, and roll/riser sound
like alternating stomps. Source identity, three-distinct-kit and tap-articulation
acceptance are failed, not satisfied by the earlier transport tests.

The code audit confirms one shared pad-role map with surface filtering, a groove
generator with one integer-beat hit at a time, and toe/heel alternation for both
roll and riser. `prototype/footwork/performance_reset/` documents replacement kit
contracts and renders separate arrangement/gesture studies. These add overlapping
parts, syncopation, ghost notes, rests and multi-contact tap phrases. Twenty-four
new gesture velocity/repeat cells pass the study checks. The native source models,
kit downloads and exported roll/riser remain previous candidates; neither source
realism nor independent kit identity is claimed fixed by new arrangements.

### Tap refinement after phrase feedback

The owner clarified that the new phrase studies, not the recorded-reference
buttons, were closer: Tap is weakest; Street and Industrial are pretty good.
Those two exact phrase studies are retained as the working direction, without
converting that feedback into approval of every source voice or delivery gate.

`prototype/footwork/tap_refinement/` revises only Tap: role-specific contact shaping
preserves the toe's delayed plate, restores the heel knock and exposes complete
brush/spank motion. A one-dancer event plan adds call/answer, a quiet passage and
a turnaround; related roll/build studies use the same vocabulary. There are 36
phrase/gesture cells and 48 single-contact cells. Render assertions and PCM16
round-trip checks pass. Six Street/Industrial audition WAV hashes are unchanged.
This remains an audition study; native models and kit downloads are not promoted.

### Tap weight and connected-timing follow-up

The owner finds the refined Tap too weak and lacking human feel. The wording was
interpreted as strike naturalness and/or rhythm, with clarification requested.
`prototype/footwork/tap_weight_feel/` restores the native contact body removed by
the previous toe filtering, firms landing accents and authors connected movement
offsets. Per-foot/contact repeat counters replace global event-index cycling.
A same-contact/old-grid comparison isolates timing. No measured-human timing or
realism claim is made. The 36 gesture and 48 contact cells pass source assertions;
Street/Industrial retained audio hashes remain unchanged. Native kits stay at
model 3 and the source/delivery acceptance gates remain open.

### Full-weight tap impact

The owner still requests more human oomph and weight. `prototype/footwork/tap_impact/`
adds a finite pressure-driven, damped support response and broad contact knock
beneath toe/heel. Prior timing, force accents, per-foot take selection and brush
PCM are retained exactly. This is an authored mechanical analogy, not a measured
human-weight model. The 36 gesture and 24 impact-contact cells pass source checks;
new phrase peaks remain below 0.77. The same-gain comparison retains the added
impact, with a separate energy-matched comparison for timbre. Street/Industrial
remain unchanged. Native model/kit versions and outstanding gates remain unchanged.

### Owner-reference isolated contact rebuild

The owner reports that the preceding impact study still lacks weight/fullness
and supplies five performance recordings. `prototype/footwork/tap_contact_fit/`
measures all five, then fits two provisional contact groups from the solo clip.
Fresh excitation drives one magnitude-fitted minimum-phase response per candidate;
no reference PCM or recorded phase enters synthesis. Toe/heel names are intended
pad roles, not verified labels for the reference events. Room and capture effects
remain confounds. Six unused same-recording events provide a limited spectral
comparison, not a realism pass. The owner-private page compares isolated reference,
new and previous strikes at equal 120 ms energy and provides 3-velocity × 4-repeat
strips. All 24 source/save-strip checks pass. No new groove or native release is
introduced; the preferred Street/Industrial phrase audio is retained byte-for-byte.
All unresolved source, kit-identity, performance and delivery gates remain open.

### Connected tap gestures from the preferred source direction

The owner calls the reference-guided contacts “Better” and asks to continue.
`prototype/footwork/tap_connected/` preserves all 24 toe/heel PCM cells exactly,
adds authored sliding/return excitation, and develops shuffle, flap, ball-change,
phrase, roll and build candidates. Seventy-two Python render/save cells pass the
study checks. Gestures carry explicit foot/contact/time/force plans, with pickup
motion rather than alternating heels in the roll. These are authored hypotheses,
not extracted dancer motion or approved articulation. Native versions and all
unresolved acceptance gates remain unchanged; Street/Industrial audio is retained.

The owner subsequently finds the shuffle too abrupt. A shuffle-only revision
extends forward/return travel to 110/90 ms, starts the return at 85 ms and uses
zero-slope pressure envelopes. Fixed source gain balances the longer travel.
The other 48 gesture cells and all 24 core contact cells remain PCM-identical.
An energy-matched shuffle comparison and the affected phrase are published for
listening; no native model or acceptance gate is promoted.

The owner then asks to continue. Smooth brush/return sources now carry into the
pickup figures in phrase, roll and build. Travel duration scales with pickup
spacing without changing filter pitch, keeping the planted toe distinct. All
36 isolated gesture cells and 24 core contact cells remain PCM-identical to the
prior revision. The 72-cell Python checks pass again. Native/export gates remain
open; the earlier full JVM command has now completed successfully.

### Native model 4 Tap port

Model/kit version 4 ports the reference-guided heel/toe, smooth brushes, shuffle,
flap and pickup-based roll/riser to Kotlin. Coarse numeric fits generate a fresh
minimum-phase response; no reference PCM enters the renderer. The RNG and house
filters differ from Python, and soft brushes gain small pressure-dependent shape
changes after the native proof caught amplitude-only dynamics.

Six focused synth tests and the 648-cell native/SFZ proof pass. All 552 untouched
role/velocity/take cells retain model-3 PCM; earlier models remain replayable.
Saved playback-window auditions and a separately labeled source phrase accompany
the candidate. See `prototype/footwork/native_v4/` for reproduction and measured
parity. Native listening/device acceptance, unapproved source/kit identities and
the MPC 3 slice-map export blocker remain open.

### Native naturalness rejection and brush reset

The owner finds the brushes unnatural and everything stiff/mechanical. Native
model 4 is not sound-approved. `prototype/footwork/brush_motion/` replaces the
brush experiment's stationary noise swell with speed-spaced microcontacts,
variable pressure and quieter rubbing excitation. A short phrase separately
tests shared timing/force/travel curves. These are authored analogies, not measured
human movement. Forty-eight Python source/save cells pass, with source and timing
comparisons for listening. Native kit/model versions and all outstanding gates
remain unchanged. Delivery correctness does not establish naturalness.
