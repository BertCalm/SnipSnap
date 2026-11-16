# CUTTINGS: plunderphonic drum kit design

Status: proposed full design; R1 Python sound study now rendered. No owner listening acceptance or full instrument gate claimed passed. See `prototype/cuttings/README.md` for implementation evidence and limits.
Repository truth: `ccf772c2153fbebc2c99e6f0985c9773382c370d`, inspected 2026-11-15.

The characteristic gesture is **find a striking fragment → expose its original room → cut into another record → stop on a deliberate seam**.

## What it is

A playable drum family whose hits appear excavated from different records. A kick retains a short bass-player shadow; a snare brings a flash of a different room; a hat contains a clipped tambourine or cymbal pickup. Each is an authored musical find. Source contrast must survive at modest processing settings.

Provisional source choice: original performances and synthesized recording scenes, treated as imaginary records. The owner has not yet selected original-only, supplied recordings, or hybrid sourcing. Preserve a source manifest so supplied recordings can enter the same recipes later. No existing commercial recording is assumed available. This models the aesthetic of record collage; it does not claim that generated material was sampled from historical records.

The literal mechanisms are cropping, resampling, crossfading, source replacement, recording coloration and sequencing. The imaginary record/session history is an artistic organizing device. Continuous crackle is optional and off by default.

## Landing and nearest siblings

Start as a source-scene bank plus deterministic splice recipes and authored drum kits. Reuse existing drum synthesis where appropriate; do not start by rebuilding a general drum synthesizer. A dedicated engine registration is justified only if audition establishes a useful editable instrument beyond the kit recipes.

SOOT already supplies worn physical drums and a recording-chain identity. SPOOL already supplies tape transport and splice behavior. Neither inspected design makes the selection of independently recorded drum/context fragments, with stable attack/body/tail assignments per pad, its central kit contract. CUTTINGS must demonstrate that difference with record wear bypassed. COSMIC BREAKS provides a repository precedent for a kit family without a new registered synth.

## Three directions

Scores are design judgments, 1–5. Higher clarity/novelty is better; higher risk/cost is harder.

| Direction | Operator and audible behavior | Clarity | Novelty | Risk | Cost |
| --- | --- | --- | --- | --- | --- |
| **Strong: Cut-and-keep** | SPLICE: one record supplies the onset, another replaces a selected body/tail window; small musical remnants reveal the cut | 5 | 4 | 3 | 3 |
| Alternate A: Whole finds | JUXTAPOSE: intact hits from contrasting recording scenes, assembled across pads | 4 | 3 | 2 | 2 |
| Alternate B: Impossible pressing | STACK: simultaneously layer disparate fragments into dense composite hits | 3 | 2 | 4 | 3 |

Strong wins because the splice becomes an audible gesture while the attack remains useful to a drummer. Whole finds is the useful baseline. Stacking risks masking the small discoveries that motivated the instrument.

## Proposed source scenes and pad roles

Author short multi-event scenes before extracting hits, so bleed has a musical cause. All names describe original imaginary material. Three dynamics and four distinct performances per dynamic are required for every shipped role, including context material. These are hypotheses, not accepted factory presets.

| Scene | What the cut preserves | Candidate roles |
| --- | --- | --- |
| Close funk session | Short skin attack, muted bass pickup, tight room | Main kick, ghost kick, dry snare |
| Small jazz room | Brush drag, ride wash, open room tail | Brush snare, open hat, ride cut |
| Percussion ensemble | Hand skin, stick chatter, adjacent shaker | Hand drum, rim, shaker |
| Library percussion cue | Felt impact, metal resonance, abrupt orchestral-room cutoff | Low impact, bell cut, transition hit |
| Garage rehearsal | Loose snare wires, handclap overlap, short room overload | Main backbeat, clap, crash cut |
| Early electronic session | Dry pulse with short gated room and mechanical accents | Alternate kick, tick hat, machine accent |

Each 16-pad kit has two kicks, two snares, closed/open hats, clap/rim, hand drum, shaker, metal hit, low impact, cymbal cut, odd percussion cut, a roll and a rising fill. Roles can change across kits; those names are not a mandate to reuse identical samples.

Pitch-bearing remnants are fixed percussion context. V1 does not promise playable chord, bass or string instruments. If those become playable voices, the melodic/STRUM gates apply before they ship.

## Signal flow

```text
scene + dynamic + take + stable seed
    → source performance and recording context
    → per-scene recording color and room
    → cached source PCM
    → onset/body/tail window selection
    → bandlimited varispeed and alignment
    → authored source replacement with short seam fades
    → context amount and final crop
    → mild common glue → DC control/headroom/tail fade
    → 3 velocity zones × 4 takes → persisted pad → playback/export
```

Keep per-record room/color upstream of the splice so fragments retain different histories. Keep the onset at a stable playable location. Prefer body/tail replacement to summing competing attacks or bass fundamentals. Main kicks retain a clean low-frequency anchor. Avoid per-fragment normalization, which would elevate quiet bleed to lead-instrument level.

## Macro contract

Defaults are provisional normalized values, except SPEED. Controls must produce useful changes at low and middle settings.

| Control | Default | Quantity changed | Interaction and proof |
| --- | --- | --- | --- |
| SPEED | 1.0× | Linked source playback speed and pitch, proposed range 0.65–1.35× | CUT operates in output time; verify pitch/duration relation and no aliasing at extremes |
| CUT | 0.45 | Role-relative retained tail duration, from a clipped hit to its complete usable tail | BLEED cannot extend beyond the crop; compare decay length and transient retention |
| SPLICE | 0.35 | Replacement strength of the authored secondary body/tail window | At zero retain primary source; with CUT short, preserve an audible splice region without moving the onset; compare both source contributions |
| BLEED | 0.20 | Gain of authored neighboring musical/room material | CUT and SPLICE must keep context subordinate; audition isolated context and complete kit |
| WEAR | 0.20 | Per-scene bandwidth loss, restrained saturation and slow speed error | SPEED × WEAR must retain kick definition and hat separation; compare clean/colored renders |
| GLUE | 0.15 | Bounded common saturation/compression strength | BLEED × GLUE must not pump context above the hit; level-match bypass comparisons |

HOLD is appropriate only on the authored roll/rising fill: select a finite 1-, 2- or 4-bar phrase, default 1 bar, using explicit saved BPM (default 90 until authored otherwise). Single hits remain finite with no artificial sustain control. Phrase recipes preserve the event count, spacing, dynamics and source/take assignments. Exported phrases have their duration baked into audio; do not promise live tempo following.

## Shelf and articulation requirements

Ship three independently authored 16-pad kits:

- **Pocket Finds**, boom-bap: close kicks, abrupt snare room cuts, uneven but controlled hat lengths, subdued bass bleed.
- **Night Fragments**, trip-hop: lower and longer impacts, brush/hand percussion, roomy half-time backbeats, sparse context with deliberate silences.
- **Cut Circuit**, big beat/broken beat: alternating kick identities, aggressive short backbeats, cymbal catches, syncopated percussion and abrupt edit accents.

Distinct source choices, role assignments, tuning, envelopes, balances and demonstration grooves are required. Applying three effects settings to one kit fails the drum gate.

Each kit receives an authored roll and rising fill. The rise comes from composed subdivision, dynamic progression and a controlled opening of the cut, ending in a deliberate release. Do not increase random event density across the whole palette. Four alternate phrase performances retain the same rhythmic design; three velocity levels change performance character as well as gain.

## Velocity, round robin and persistence

Every pad, including phrases: 3 dynamics × 4 distinct takes. Initial velocity split proposal: MIDI 1–42, 43–84 and 85–127; zero is not a strike. Confirm the app's actual boundary mapping during implementation.

Dynamic levels change source excitation, attack spectrum, snare-wire/room response or selected recorded performance. Round robin varies actual strikes and associated context while retaining the chosen source identities and splice recipe. New hiss, a gain change or a detune of one identical strike does not qualify.

Use `Dsp.seedFor` for procedural repeatability. Persist source content hashes, scene version, source windows, dynamic/take IDs, splice recipe version, all controls, BPM/phrase length and selection policy. Render deterministically from those values. Cache keys include every field affecting PCM. Preserve the sample pool and cycle order across save/load; document whether the live cycle position resets on reload.

Verified integration points: `ArrangedPad.velocityTakes` accepts 2–4 zones with 2–8 takes per zone and requires the main sample to match the first hard take; `PadHit.resolve` accepts velocity and hit index. These contracts are useful starting points, not evidence that CUTTINGS is integrated.

## Build rounds and acceptance evidence

1. **Identity proof:** kick, snare, closed hat and one odd percussion cut, each already 3 × 4. Compare intact source, splice without wear, and finished hit at matched loudness. Include one groove with context muted/unmuted. If the cut only becomes interesting under heavy degradation, revise the source material.
2. **Articulation proof:** one composed roll and rising fill; all lengths and dynamics, repeated takes, plus a dry full-kit groove. Listen before expanding the palette.
3. **Shelf:** finish all three genre kits after the source identity is accepted. Factory macro defaults follow listening, not preset-count targets.
4. **End-to-end closure:** exercise rendering, persistence, playback and each export target with the actual shipped grids. Record concrete evidence in a separate as-built section.

| Gate | Required evidence | Current status |
| --- | --- | --- |
| Multiple genre kits | Three shelf entries with distinct roles/source choices and playable grooves | Not yet verified |
| Appropriate articulation | Finite hits; authored roll/rising fill with saved duration/tempo and clean endings | Not yet verified |
| Velocity | Timbral differences at all three levels; boundary tests before/after save/export | Not yet verified |
| Round robin rendering | Four distinct performances at fixed velocity; deterministic regeneration and stable pad identity | Not yet verified |
| Saving | All 12 variants per pad retained with recipe, ordering and PCM/window identity | Not yet verified |
| Playback | Two full cycles per zone; velocity changes during cycling; audition actual playback output | Not yet verified |
| Export | Imported variants and actual playback windows/selection match the authored grid | Not yet verified |
| Melodic/keygroups/chord tools | Not applicable to fixed percussion-only scope; apply full gate if playable melodic voices are added | Not applicable, scoped |
| STRUM | No picked/thumbed string voice is proposed | Not applicable, scoped |

The owner gates are authoritative: [engine gates](ENGINE_ACCEPTANCE_GATES.md), [instrument gates](INSTRUMENT_ACCEPTANCE_GATES.md). MPC 2's combined velocity/RR limitation has an owner exception; MPC 3 native remains the judged target. The repository documents a shared unresolved MPC 3 embedded slice-map hardware capture. Metadata readback or an SFZ round trip cannot close that hardware gate. Do not label this kit complete while applicable playback/export evidence is missing.

## Numerical requirements, cost and sonic risks

Output: mono 44.1 kHz. Use 4× processing for nonlinear source/color stages, bandlimit before decimation, and bandlimited resampling for speed changes. Remove DC, leave peak headroom, preserve authored relative levels and fade final tails. Crossfade splice boundaries using role-dependent short windows, initially 1–4 ms; longer fades must not erase attacks. All durations and gains are bounded; no new feedback network is required.

Proposed performance budget, not measured: a warmed single-hit control edit within 50 ms on the target phone. Cache source scenes separately from cheap cut/splice renders. Full 12-variant grids and multi-bar phrases render asynchronously. Benchmark cold source preparation, warm edits, grid generation and peak memory separately; cap scene duration at 4 seconds in the first proof. Source cache keys include performance seed, dynamics, take, scene version and upstream color; output cache adds speed, crop, splice, context, glue and phrase fields.

Main risks and checks:

- Generic drum synthesis under vinyl noise: audition splice with wear/noise bypassed, and inspect whether source/context choices remain identifiable.
- Mud from bass/context overlap: isolate kick anchors and compare the full groove with BLEED off and on at matched level.
- A chopped transient that loses punch: inspect onset timing and compare attack energy before/after splice; listen at soft dynamics too.
- Chaotic RR identity changes: fixed-velocity repeated-hit audition must retain the pad's source recipe and recognizable role.
- Unmusical rising fill: audition with the rhythm section; preserve a composed arc and a clear exit.
- Aliasing or clicks: speed/color corners against a higher-rate reference, seam residuals, finite samples, DC and peak checks.

First audition should fit one screen: four pad buttons, dynamic and take selectors, source/splice/finished comparison, one groove, and a level-matched bypass. Expand to kit tabs after the first identity proof.

## Verified / proposed / heard

| Claim | Status | Evidence |
| --- | --- | --- |
| Gates and sibling designs inspected | Verified | Repository at the commit above; SOOT, SPOOL, COSMIC BREAKS and owner gate documents |
| Seed and velocity-grid/playback hooks exist | Verified | `Dsp.kt`, `KitAssembler.kt`, `PadHit.kt` |
| Source palette, controls, genre kits and timing budget | Proposed | This design; no implementation benchmark |
| Sound character accepted | Awaiting owner audition | R1 Python study published; no owner acceptance yet |
| End-to-end gates passed | Not verified | Build and target playback evidence still required |

## R1 implementation evidence (2026-11-15)

The first four roles now have 48 modeled performances and 144 source/splice/finished WAVs. A four-bar groove has matched-level stage comparisons and musical-context bypass. The compact audition is at https://cuttings-audition.bertcalm.chatgpt.site. Source, measurements and player selection checks are in `prototype/cuttings/`. This implements the identity proof only: six editable macros, full genre kits, phrases and Kotlin/app/MPC integration remain outstanding. The initial source choice remains original procedural imaginary sessions.

## R2 character revision

Study 02 exaggerates each secondary source gesture in response to owner feedback: bass catch, snare room dropout/slap, tumbling jingle and reverse-metal/bell cut. Primary source seeds remain stable for comparison. The 48-performance grid is regenerated and checked; original Study 01 remains available at `/r1/` on the same audition site. This is still a prototype, with no new completion-gate claims.

## R3 Pocket Finds prototype

The owner called Study 02 a better direction and asked to continue. Study 03 expands to one 16-pad prototype kit: 14 finite percussion hits plus a composed one-bar roll and rising fill, all 3 dynamics × 4 takes. The four Study 02 signatures remain byte-identical. The new audition uses four crates of four pads and an expanded groove. This is not yet multiple genre shelf kits or Kotlin/app/MPC integration; those gates and editable phrase lengths remain open. Measurements and simulated playback selection checks are in `prototype/cuttings/`.

## R4 sampler color

Owner requested a little old-school sample color. A parallel 22.05 kHz/12-bit converter approximation adds modest bandwidth loss, low-level quantization texture and mild peak compression. The Before sampler stage preserves all Study 03 finished samples exactly. This remains a sound-study revision, with full instrument gates open.

## Native sample-bank landing

After Study 04 acceptance, the instrument landed as `CuttingsKits`: the exact approved sample bank, three authored shelf kits and 1/2/4-bar roll/riser pairs. See [CUTTINGS](CUTTINGS.md) for current implementation and gate evidence. The earlier six-control source-engine proposal remains a design option, not a shipped control panel.

## Why it is built this way

- Short recording scenes give each cut a believable reason to contain room, bass or percussion bleed. Adding noise to a lone drum cannot provide that history.
- Replacing a body or tail preserves a strong onset while revealing a second source. Stacking everything would obscure both discoveries.
- Stable source choices across round robins let the pad remain an instrument; alternate performances keep repeats alive.
- Separate genre kits establish musical range. The risky decision is how much surrounding music to retain, so the first proof exposes BLEED in a complete groove.
