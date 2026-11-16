# FOOTWORK: percussive dance and street-object drumkits

Status: R0 sound rejected by the owner: “Doesn't really sound like the real sounds”. The owner now recognizes the metallic ta-ee study as tap; exact reference matching remains approximate. Neither small nor open variant was singled out. Full instrument acceptance remains open. Source assumption: entirely synthesized; no performance recordings are used. Working name FOOTWORK; the stage production named in the brief is a research reference, not a kit name.

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

Brushes are a travelling roughness field shaped by pressure and signed motion; a forward/back pair must not reuse identical PCM. Multi-contact gestures preserve one plan of accents and directions while each impact gets its own seed. Seed derivation must include source version, voice, take and event index, with stable ordering. Left/right variation is contact-position and damping variation in mono, not a panning trick.

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

One-shots are finite; HOLD and melodic STRUM are not applicable to these foot/body/object drums. Brushes have an authored finite travel duration. The roll is a finite two-beat alternate-foot performance with new strikes; the riser is a four-beat increase in density/force ending in a deliberate landing. No seamless sustain is claimed. CLUTTER's owner-specific roll exemption does not apply here.

Bound gestures to 32 contacts, minimum 20 ms event spacing, maximum four beats plus a 1.5-second decay tail. Within a phrase, contact drive may excite a still-ringing support. Do not splice a fresh floor tail onto each hit if that destroys continuity. The proposed implementation should retain the shared linear floor response through the event buffer; no nonlinear global state is needed.

## Render budget and engineering landing

Target warmed single-hit rendering in tens of milliseconds; initial ceiling 50 ms median and 100 ms worst on the development JVM, then measure on the phone. These are targets, not current phone measurements. Use 8–16 contact modes plus 4–8 support modes, bounded source lengths (0.2–1.5 seconds), and precomputed filter coefficients. Cache immutable mode tables by surface, voice, TUNE, FLOOR, DAMP and source version; keep velocity/take excitation out of that cache. Multi-hit offline pad and whole-kit generation get separate measured budgets and cancellation checkpoints.

Kotlin landing: new focused source and kit arranger under `synth/src/main/kotlin/com/snipsnap/synth/`; populate `ArrangedPad.velocityTakes`; register three stable starter IDs exactly once in `StarterKits` and `StarterShelf`. Source metadata records version, recipe, velocity and take grid. Preserve old recipe versions when sound changes. Use the existing export paths rather than inventing a new sampler format. A full engine/Patch registration is not needed merely to expose three kits; if an editable engine is later required, apply the complete registration checklist then.

## Acceptance contract: no shortcuts

| Gate | Required proof | Current state |
|---|---|---|
| Melodic six voices/keygroups/chords | Not applicable: no melodic instrument is delivered | N/A |
| HOLD / STRUM | Finite foot, body and object percussion; no picked/thumbed string voice | N/A |
| Percussion roll/riser | Authored B01/B02 on every kit; per-event fresh strikes; complete velocity/take grids | Open |
| Multiple genre shelf kits | Three materially distinct kits, registered once each; render/preflight test every starter | Open |
| Rendering | All 648 cells finite/bounded/DC/tail-safe; deterministic recipes; zero velocity; timbral dynamics; real RR diversity | R0 subset only |
| Saving | Save/load complete source metadata, zones, takes, frame windows and PCM for all 648 cells | Open |
| Playback | Native selection at every zone boundary, independent RR counters/wrap, correct gains and contact/stop behavior | Open |
| SFZ | Complete regions, import/replay PCM and velocity/RR selection | Open |
| MPC 3 | Complete zone/take grid, slice indices and packed PCM read-back; no first-take fallback | Open |
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
- **Heard:** the owner rejected R0 for failing to resemble the real sounds. After the metallic ta-ee revision, the owner said it “sounds like tap to me,” while noting the reference match is not exact. This passes recognizable tap character only; neither variant was selected, and other voices and full-kit acceptance remain open.

## Reference reset after the first listen

Code review found that six nominally different contacts share the same four-mode ratio pattern, with retuning and gain differences. The brush has an envelope-shaped noise source, not the travelling roughness/direction model described in the proposal. The shuffle repeats this brush mechanism twice. Distinct normalized waveforms and passing health checks did not prove recognizable shoe or body-percussion identity.

The revised experiment starts with one tap-shoe contact against a credited recording, with four training and four disjoint check strikes. It replaces the sparse resonator bank with broad filtered-noise contact envelopes. See [reference-reset reproduction and provenance](../prototype/footwork/reference_reset/README.md). The recording is on tiles in an echoing room, not a verified toe/heel-separated wooden-stage reference. No reference PCM is loaded at synthesis time. Every remaining voice and full-kit gate stays open.

The audition now exposes recorded reference, rejected model and new candidate separately. Owner listening, not spectrum distance, decides whether this is a useful contact source. The full kit will not inherit an approval from this single anchor.

## Gesture-family audition

The Python study now supplies toe, heel, forward/back brushes, shuffle, flap, an authored roll and an accelerating riser, plus an original 116 BPM phrase. These extend the recognizable ta-ee source but remain unapproved as distinct gestures. Small-metal is a provisional construction choice; the owner did not select between small/open. This is not the planned three-kit shelf delivery. Native velocity/round-robin persistence, playback and export remain open. See `prototype/footwork/reference_reset/dance_gestures.py` and its generated report for the current prototype evidence.

## Why it is built this way

- Shoe contact and floor support are separate, so toe, heel and stamp can differ in attack and body instead of just pitch or level.
- Movement has a contact sequence; independent strikes make shuffles and flaps behave like gestures rather than delayed copies.
- Existing object models provide the stage ensemble while development effort goes into the missing dancer.
- Fixed source calibration preserves the relationship between light steps and heavy landings.
- Explicit gate evidence prevents a convincing browser demo from being mistaken for a complete sampler instrument.
