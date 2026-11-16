# Knee Break: reference-led kit brief

Status: CONCEIVE, first Python sound proof. Not an integrated instrument or an approved sound.
Repository read: `f1842eb48f175fb5fc77e02efeeda97c42c15d6a`.

The characteristic gesture is **soft beater or stick contact → short damped drum body → wires and nearby metal answer → a shared microphone compresses and optionally repeats the groove**.

## What the four references establish

Numbered in recording-time order, not attachment order. All are user-provided screen recordings.

| Recording | Verified visual evidence | Design use, proposed |
|---|---|---|
| 12:08:19, 14.861 s | Picture-in-picture acoustic kit and effects imagery. Fine control labels are not reliably readable. | A processed acoustic context; do not infer a precise pedal chain. |
| 12:10:17, 8.777 s | Caption “One mic drum break”, microphone demonstration and a cloth-damped kit. | A coherent mono perspective, close body and restrained overhead brightness. |
| 12:11:14, 12.190 s | Caption “Vintage vibes”, visibly damped heads and stick playing. | A short head decay with articulation carried by contact and wires. |
| 12:11:56, 13.630 s | “Knee mic effects” with successive “Delay”, “Lo-fi cassette”, “Compression” labels. | Three separable behaviours to compare, with a shared bus as the preferred final routing. |

The visible account on references 2–4 is `@kylekrysadrums`. These are behavioural references, not samples for redistribution. No samples from the videos are used in the proof. No specific microphone, pedal or plugin circuit is claimed; the visible processing labels do not establish settings or routing order.

Measured by the Python STFT analysis at mono 44.1 kHz: references 1–3 have respectively 90.5%, 94.3%, and 91.7% of spectral energy below 1.5 kHz; the effects demonstration has 75.2%. These are whole-recording statistics, including any speech, overlap and screen-capture encoding. They are not isolated kick/snare spectra and must not become exact EQ targets. Strong attack timestamps and band measures are in `prototype/knee-break/reference-measures.json`. No tempo, drum tuning or isolated decay is claimed from these measurements.

## Landing and nearest siblings

Land as a named kit/recipe family first. A new synthesis engine is not justified by the current evidence.

- **EMBER:** already has damped membrane drums, separate wire response, fitted acoustic hats and cymbals, and microphone/recording colour. Its warm club identity is the nearest source model. The proof uses its Python model with DISTANCE and WARMTH at zero, plus proposed tuning and damping. Its fixed voicing still remains, so “close” does not mean an uncoloured physical-model output.
- **BREAKER:** already has articulated acoustic breaks, multiple genre kits and per-pad velocity/take grids. Its amp/spring/slap chain is a different identity. Borrow the kit delivery pattern only after the source audition.

Neither existing kit's individually printed pad effects can reproduce a compressor responding to simultaneous hits across the whole kit. That routing distinction, plus the new source settings, is the useful addition. Keep a bus-treated phrase preview separate from per-pad delivery.

Verified APIs at the commit above: `Ember.render(voice, macros, velocity, take)`, `Breaker.render`, `Compressor.process`, `Tape.process`, `Echo.process`. The proof imports `prototype/ember/ember_proto.py:render`; it does not call the Kotlin APIs and does not establish Kotlin performance or sample equality.

## Three directions

1. **Strong: DISTIL, damped one-mic kit.** Clear contact → body → compressed room gesture; moderate novelty as a recipe, low model risk, two sound rounds before integration. Wins because the sources can be judged dry while shared processing supplies the reference's recording character.
2. **Alternate A: STACK, printed cassette break kit.** Clear hit → tape colour → repeat gesture; lower novelty beside BREAKER, low implementation cost, greater risk of smearing ghost notes. One extra treatment round once the source is right.
3. **Alternate B: COUPLE, sympathetic kit.** Head strikes also drive neighbouring wires and metal; stronger mechanical distinction, but calibration and energy-transfer risk are higher and independent pads cannot carry arbitrary cross-pad history. At least three model/audition rounds. No full coupled simulation in this proof.

## Signal flow

```
velocity + take + source settings
  → contact shape → damped membrane / fitted hat → independent wire or plate response
  → close output ───────────────────────────────────────────────┐
  → proposed knee colour + 11 ms reflection → compression ───────┤
                              → cassette colour + finite echo ──┤
                                                               → output
```

The Python groove sums original hits first, then processes the common signal. This lets the same compressor react to kick/snare/hat overlap. The individual hit bank prints that chain separately on each hit, a different behaviour disclosed on the audition. The cassette nonlinearity runs at 4× and is decimated with `resample_poly`. The finite echo uses four repeats, 160 ms apart, with gain multiplied by .27 each pass. All these constants are design shapes, not recovered settings.

## Proposed 16 pads

| Pad | Role | Purpose |
|---|---|---|
| A01 | Felt kick | Main low thump |
| A02 | Damped snare | Main backbeat |
| A03 | Closed hat | Short pulse |
| A04 | Open hat | Accents; same choke group as closed/pedal hat |
| A05 | Short kick | Syncopation without low-tail buildup |
| A06 | Ghost snare | Softer contact and wire response, not just quieter main snare |
| A07 | Rimshot | Hard backbeat accent |
| A08 | Cross-stick | Sparse groove variation |
| A09 | Low tom | Damped fill body |
| A10 | Rack tom | Higher fill voice |
| A11 | Pedal hat | Foot closure |
| A12 | Dark ride | Distinct stick point and metal decay |
| A13 | Shaker | Quiet subdivision, independent contact grains |
| A14 | Woodblock | Dry counter-rhythm |
| A15 | Snare drag/short roll | Authored finite strokes with fresh takes |
| A16 | Accelerating snare riser | Authored finite build with an ending |

This layout is proposed. The first proof renders A01–A04 only, to settle the source sound before expanding it. A13–A14 are complementary choices, not instruments positively identified in the videos.

The intended shelf family has three musically distinct kits: **Pocket** (damped funk, dry ghost-note articulation), **Dust Break** (lower/shorter drums and compressed accents for boom bap), and **Tape Dub** (cross-stick-led groove with selective echo throws). The first proof's three treatments share the same four sources and therefore do not yet satisfy the distinct-genre-kit gate.

## Proposed controls

These are a recipe-level contract, not new registered macros.

| Control | Default | Model quantity | Interaction and proof |
|---|---:|---|---|
| TUNE | .40 | Head/modal frequency; map separately for drum and metal | Sweep fitted modes below Nyquist; check body pitch without changing timing |
| DAMP | .80 | Frequency-dependent head/plate loss | DAMP × WIRES: short heads must retain a wire response; measure decay separately |
| WIRES | .45 | Snare/contact response amount | Velocity changes onset/brightness, not gain alone; compare gain-matched soft/hard spectra |
| KNEE | .35 | Direct/shared-microphone blend | KNEE × CRUSH changes body-to-room envelope; compare summed groove with per-hit print |
| CRUSH | .40 | Compressor threshold/ratio and release | Verify transient reduction and recovery at kick/snare overlap |
| ECHO | 0 | Finite dark repeat send | Repeats preserve strike onset; max setting terminates within delivery tail budget |

No sustained HOLD is needed for individual drum hits. A15/A16 supply authored finite articulations. Any future tempo-locked phrase uses kit BPM; do not claim an engine render receives tempo automatically.

## First proof and gates

`prototype/knee-break/render.py` produces four sources × three velocity levels × three takes × three treatments = 108 hit files, plus three original two-bar groove demonstrations at 94 BPM. The pattern is not a transcription. It uses closed/open-hat choke handling. Each source is re-rendered at each velocity and take; a declared velocity gain is applied because EMBER's Python preview stage peak-normalizes its output. Take identity comes from independent contact/noise, not post-render random EQ.

Checks run against decoded WAVs: finite output, peak below .95, DC below .005, increasing mean energy across velocity layers, three distinct takes after gain removal, and an exact repeated kick render. The manifest includes hashes and frame counts. Report wall time includes the whole Python bank, not phone latency. These checks are not a perception test, a fitted reference match, or a completed velocity/export gate.

Next sound gate: compare the close kick/snare/hat articulation first, then the same groove through knee compression and cassette/delay. If the source fails, revise that before tuning effects. Once accepted, fill out A05–A16 and the three genre arrangements.

Integration remains: versioned recipes; native velocity/take selection; save/reload; playback; round-trip export checks on all supported formats; shelf registration; hardware audition. Follow `docs/INSTRUMENT_ACCEPTANCE_GATES.md`, including its explicit MPC 2 format exception. No export format is claimed by this WAV audition.

Render target for a later Kotlin recipe: tens of ms for a cached single-hit re-render. Cache fixed modal/filter coefficients and rendered source strikes by voice, model version, source macros, velocity, take and rate. Processing-only edits reuse source PCM. The current Python proof does not meet or measure that device target.

## Sonic risks

- A clean sine-like kick may miss the actual beater and head texture.
- Excess wire energy may turn the snare into hiss; too little loses ghost articulation.
- Knee colour may become boxy instead of intimate; judge the close reference first.
- Printing compression per pad loses shared pumping; compare it explicitly with the bus groove.
- Tape/echo can hide source defects or erase ghost notes.
- Screen-recording mix statistics may reflect speech and capture processing rather than drum timbre.

## Evidence ledger

Verified: four videos inspected visually; audio decoded and measured; nearest models and APIs read at the named commit. Measured: reference whole-mix statistics and generated-file checks. Proposed: pad map, source settings, processing constants, genres and control laws. Heard/approved: none of the new material. No claim of having listened or matched the recordings by ear.

## Why it is built this way

- Damping belongs in the drum model so it removes ringing before compression raises the tail.
- Separate contact and wire response let soft notes remain expressive.
- A shared groove bus exposes the microphone/compressor interaction that isolated pads cannot reproduce.
- Reusing an existing acoustic model gets to a useful listening comparison before paying for a new engine.
- Four core voices are the cheapest proof: if they miss the reference, more pads will not fix it.

## Round 2: recorded-acoustic comparison after source rejection

Owner feedback: “The base drums are weak and artificial.” Round 1 is rejected.
The snare's DAMP .88 maps to a main T60 of .112 seconds; its normalized output
also received pad, velocity and .7 delivery gain. These are implementation
findings, not a complete measured explanation of the owner's listening result.

`prototype/knee-break/revise_dry.py` builds Body and Tighter candidates from
existing CC0 Virtuosity recordings in `spore-station/v7`, checking source hashes.
Initial contact remains intact before an amplitude envelope damps the tail.
Recorded microphone ambience remains. No user-video audio or new effects are
used. Snare alternatives are neighboring recorded dynamics, not same-dynamic RR.
The page compares the old and new grooves at matched two-bar RMS, and isolated
hard hits at matched first-200-ms RMS. Its separate velocity/take selector keeps
delivery dynamics. All 72 candidate files pass decoded headroom, DC, layer-mean
energy ordering and gain-removed pair-diversity checks. Listening approval and
instrument integration remain open.

Why this revision is built this way: recorded contacts establish an acoustic
baseline; two tail lengths isolate damping preference; level matching reduces
loudness bias; the rejected version stays available for direct comparison.

## Round 3: synth replication of both acoustic targets

Owner: “I like both better, but you can replicate with synth.” Both R2 tail
settings remain targets. `fitted_synth.py` fits three recorded velocity layers
per voice into 24 bands of resonant frequency/weight/phase and 17 coarse envelope
points. It generates oscillators and fresh filtered noise at 4× and decimates;
its runtime takes numeric JSON only. This is measured resynthesis, not a full
physical membrane model, PCM replay or a sample/oscillator hybrid.

`revise_synth.py` applies the same Body/Tighter damping curves and produces 72
candidate hits. They pass the decoded headroom/DC/layer-order/take-diversity
checks. A repeat snare render is deterministic with WAV reading disabled, and a
second take differs. First-200-ms normalized spectral band-shape errors range
from 0.59 to 1.69 dB across hard first strikes; this is an in-sample fitting
measure, not a perceptual test or evidence of generalization. The published
page compares each target/candidate pair at matched RMS. Listening approval,
Kotlin implementation and end-to-end delivery remain open.

Why it is built this way: body resonances get coherent oscillators; contact,
wires and high-frequency texture get fresh noise; coarse envelopes retain the
preferred attack/body/tail balance without replaying waveforms. The risk is
that coarse bands miss the exact transient texture, which only the comparison
can settle. Existing CC0 attribution stays with the derived numeric model.

## Round 4: full kit and processing audition

The owner accepted the R3 core as “pretty close to exact”, then authorized both
16-pad kits and the processing comparisons. `full_kit.py` expands the proposed
pad map, preserving all 72 dry R3 core files byte-for-byte. New fitted sources
cover heel kick, ghost snare, rimshot, cross-stick, tom, pedal hat and ride;
the lower tom scales fitted modal frequencies. Shaker and woodblock are newly
procedural. The finite roll and accelerating riser rotate six independent
snare contacts per take, with authored event strengths and timing.

Delivery is 2 damping variants × 3 treatments × 16 pads × 3 velocity layers ×
3 takes = 864 WAVs. Six downloadable SFZ grids map notes 36–51, explicit velocity
ranges and three sequence positions, with a shared hat choke group. Browser
pad playback cycles takes and provides eight-hit repeat/cycle comparisons.
Four-bar groove demonstrations process the sum through the shared bus; kit
samples print treatment per hit, a disclosed different compression behaviour.

Python checks passed all 864 files and all six ZIPs, increasing layer mean
energy for every pad/treatment/kit, minimum gain-removed take residual .01467,
maximum peak .69970 and maximum absolute DC 1.054e-6. Independent SFZ checks
verify every sample mapping, velocity range, sequence position and hat-group
field against the manifest. Browser logic checks cover every file selection,
repeat/cycle timing, cancellation, groove URLs and download URLs. No actual
browser, SFZ player, Kotlin port, native shelf or MPC hardware pass is claimed.
The twelve new roles and effects are not yet owner-approved, and the two damping
variants are not a claim of completed distinct-genre shelf kits.

Why it is built this way: approved PCM is frozen to avoid accidental regression;
new strikes carry source variation before processing; authored gestures give
percussion a real beginning and ending; dry and processed comparisons expose
whether effects help the source rather than hide it.

## Round 5: stronger knee-mic contrast

Owner: “Dry and knee mic sound the same.” The old grooves measured about 1.9 dB
below dry RMS, with a substantial dry contribution. `knee_v2.py` now uses 20%
dry / 80% processed signal, 135–3400 Hz band colour with added mid-body, short
reflections, 4x saturation, and 5:1 compression with 1.5 ms RMS detection and
90 ms gain recovery. This is an effect recipe, not a physical microphone model.

The revised groove comparisons match dry RMS; compression in the processed bus
averages 8.2–8.6 dB during detector-active passages, with maxima near 17.2 dB.
All 580 dry/cassette pad and groove WAVs remain hash-identical. All 288 revised
knee pad files pass peak, DC, take diversity and velocity-layer mean-energy
ordering. The two corresponding SFZ packages and all browser mappings were
checked. The owner subsequently accepted this direction: “Better, proceed in this direction.”

Why it is built this way: less dry blend exposes the coloured mic signal;
compression changes the transient/body relation; matched groove RMS reduces
volume bias. The first stronger-ratio experiment slightly inverted rimshot
layer energy, so the final 5:1 ratio retains dynamic ordering without changing
any synthesized source or weakening the check.

## Native kit integration (in review)

After accepting the revised knee direction, the owner authorized continuing.
`KneeBreakKits` freezes the exact synthetic audition deliveries in gzipped WAV
resources. This is the kit-delivery layer, not an editable Kotlin port of the
oscillator model: no new engine or sound-control picker is claimed. The original
Python synthesis, numeric models and processing remain the reproduction sources.
Each new kit owns its decoded buffers and uses the shared 3-zone × 3-strike grid.
Six FRESH TAPE entries preserve Body/Tighter with dry, revised knee and cassette.
They remain treatment variants rather than six distinct genre kits.

The import script verifies every source SHA before lossless compression. The
resource manifest records frames, SHA, source model and published Site commit.
`KneeBreakKitsTest` checks all 864 assets, grid shape, ownership, finite gestures
and velocity ordering. `KneeBreakRoundTripTest` follows saving/reloading, all MIDI
velocities and nine repeated hits per pad, SFZ export/import, and MPC 3 read-back
using the existing independent track verifier. The generator writes native,
SFZ, DecentSampler and MPC 3 packages with an MPC 2 twin. MPC 2's combined
velocity/RR limit remains the owner's documented format exception: first take
per velocity. Hardware playback is a separate unresolved verification step.

The factory mixer level is .65, matching the audition pad player. No board is
applied on load: the effects are already printed. The shared-bus groove demo is
still a separate comparison, not a claim of a live cross-pad effect in these kits.
Focused Kotlin checks and kit generation passed on 2026-11-16 (Gradle exit 0).
The independent native audit matched all 864 chain windows exactly after the
repository PCM16-to-PCM24 conversion, and verified all six ZIP contents.
The first full JVM run failed two shell registry assertions: the explicit
starter-ID list omitted the six kits, and the render-only roster omitted their
frozen pads. Both rosters now include the six exact IDs. The focused round-trip
test also checks preflight, render-only status, and saved source provenance for
every pad. Full-suite status is tracked on PR #1154; no full pass is implied here.
Browser control checks passed all 864 selections and six download mappings.
Hardware playback and the distinct-genre shelf gate remain open.
