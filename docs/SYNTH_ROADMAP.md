# Synth roadmap — generate kits, not just capture them

Capture is the headline; synthesis is the second leg. The goal: dial in drum
sounds (and eventually keys) inside SnipSnap, render them to WAVs, and ship
them through the exact same kit → preflight → export pipeline as captured
snips. A kit where pad A01 came off a YouTube rip and pad A02 came out of the
synth is one kit, not two features.

**Cairn reconstruction (2026-10-24).** KNOCK and ANSWER share a bounded passive
motion/contact core. Higher contacts survive the 243-point middle-control grid
and disappear when mount coupling is removed. Saved model/note/velocity/cue
recipes and 92 finite audition cases are available outside the factory roster
and phone picker. Historical six-voice recordings remain separate references.
HOLD loops, other voices, phone cost and sonic acceptance remain open. See
[CAIRN.md](CAIRN.md) and the [private audition](https://cairn-voice-comparison.bertcalm.chatgpt.site/reconstruction/).

**Tidal finite instrument (2026-10-24).** PAIR and STREAM adapt a softened
restricted two-center encounter into two original harmonic populations, persistent
cohorts, surviving cores, and extended ringing strands. The owner approved the
second source pass. Six model-2 presets, the phone SYNTH editor and a dry
sixteen-pad FRESH TAPE starter preserve complete note/velocity/seed/model recipes.
Production rendering shares its source stages with diagnostic evidence and adds
a bounded completed-audio cache. Pads play once despite long-sample LOOP filing.
HOLD remains zero; reconstruction, the other voices, and factory expansion remain later work.
See [TIDAL.md](TIDAL.md) for model limits, reproducibility, and the listening gate.
The [private audition](https://tidal-snipsnap-audition.bertcalm.chatgpt.site)
opens with eighteen model-2 comparisons and retains the original 58-case matrix.
Full preset curation, physical phone performance and MPC listening remain open.

**Chamber finite instrument (2026-10-24).** HYBRID and AQUARIUM place a
simultaneously struck membrane, bar, cowbell and triangle in a rectangular room
with ten independent material regions. Physical early paths, surface excitation,
return coupling and a shared passive late field test whether instrument placement
changes the answer. Complete model/seed/note/velocity/room recipes, six factory
recipes, CLI replay, phone controls and a sixteen-pad dry MPC 3/MPC 2 kit are
available. Completed-render caching and cancellation support offline preview;
fresh settings still run the coupled simulation. This stage is finite only:
positive HOLD is rejected, and page Repeat replays the finite strike. See [CHAMBER.md](CHAMBER.md)
for policies and reproduction; [CHAMBER_SPEC.md](CHAMBER_SPEC.md) remains the source
proposal. The [private audition](https://chamber-snipsnap-audition.bertcalm.chatgpt.site)
includes a refreshed current-model control/preset study and kit-sample phrases,
with earlier timpani comparisons archived. The owner approved model 2's timbre
and preferred model 3's 3 dB membrane mix lift. That voicing and saved earlier
models remain replayable. The wider library, true HOLD, advanced room editor
and physical phone/MPC acceptance remain later work.

**Confluence recorded-source proof (2026-10-17).** A finite 1–8-performer
renderer, dedicated source-backed recipe, licensed VocalSet fixtures and dry
audition generator test the gesture of more performers joining. UNISON,
RESPONSE and marked-vowel DRONE use stable identities and complete source
regions. This stage is a standalone rendering API; phone source preparation,
normal patch registration, factory presets, 48 voices and HOLD remain later
work. See [CONFLUENCE.md](CONFLUENCE.md) for reproduction, source attribution,
portable audition recipes and listening limits. The owner's listening verdict
is pending.

**Filament, approved three-voice instrument.** THREAD, PULSE and CURL implement
contain → bend → spill: normalized launches, ordered endpoint depletion,
reactive exchange between guided routes and a damped escaped halo. Twelve
approved dry model-3 recipes are in the factory library, phone SYNTH picker
and sixteen-pad starter. Saved root, velocity, geometry and models 1–3 remain
replayable. Playback shares the diagnostic DSP core while skipping optional
capture work, supports cancellation and uses a bounded dry-preview cache.
The model-3 THREAD keygroup pilot retains actual startup before settled
sustain, with C3/C4/C5 roots, one velocity layer and host note-off release.
Physical phone/MPC acceptance and the larger proposed roster remain later
work. See [FILAMENT.md](FILAMENT.md) for reproduction, measured limits and
the private listening page.

**Caustic finite instrument (2026-10-23).** The owner selected Alloy and
heard the fuller tonal body after finding Bloom/Deep indistinguishable. The
first native ALLOY voice adds SOURCE, FOCUS, BODY and LENGTH with saved ROOT,
VELOCITY and native model 1; HOLD is reserved. Static coherent paths feed the
same 24-mode material, with an exact linear response and a bounded geometry
cache. Native patch/preset/velocity/CLI/phone routing, eight starter positions,
and a dry sixteen-pad MPC kit develop that heard direction. Six starter
positions are new listening candidates. The archived 48 recordings, recipes
and four archives remain unchanged. Deformation, SCATTER, ORBIT, true sustained
HOLD, additional voices and hardware checks remain later work. See
[CAUSTIC.md](CAUSTIC.md) for the control contract, verification and limitations.

## Why this is cheaper than it sounds

Pads are one-shots. That means **synthesis here is offline rendering** — a
pure function from parameters to a `Snip` — not a real-time engine:

- No latency budget, no Oboe coupling, no voice management. Render on commit,
  audition through the same player as any sample.
- Every downstream stage already exists and is tested: `Cleanup`, `WavWriter`,
  `KitAssembler`, `Preflight`, `KitExporter`.
- It's already demonstrated: `DrumSynth` (kicks with pitch sweeps, filtered
  snares, high-passed hats, FM-free toms) powers the play-mode prototype's
  demo kit, and the `:kit` end-to-end test synthesizes a break and exports it
  as a loadable MPC program. The roadmap below is "grow that seed", not
  "build a synthesizer from nothing."
- Fully testable in CI: rendered audio can be measured (centroid, decay,
  pitch) with the analysis code we already have. The classifier becomes a
  test harness: a THUMP kick preset must classify as KICK.

## The one legal guardrail: sound yes, names never

"Clones of classics" is a fine ambition **as sound**. Emulating the character
of a vintage analog drum voice or an FM electric piano is legitimate and done
industry-wide; the circuits' patents are long gone. What is not ours:

- **Trademarked names and model numbers.** Nothing in the app, presets, or
  store listing gets named after real machines — not the famous Roland/Akai/
  Linn/E-mu/Yamaha model numbers, not obvious near-misses of them.
- **Trade dress.** No recreating another machine's faceplate; everything
  wears TapeOS.
- **Sampled content from the originals.** All synthesis from scratch — which
  is also what makes the sounds ours to ship.

So the engines get our own cassette-era names, and preset descriptions say
"boomy analog kick", never "the famous one".

This prohibition covers product surfaces — engine and voice names, preset
names, descriptions, and commit messages — not documents that discuss the
rule itself: this roadmap, design specs, and implementation plans may name
machines when the point is naming the rule that forbids naming them.

## The engines

### CREASE — pressure memory in a phase map (native integration)

ROUND, PINCH and RUBBER share two smooth, monotonic phase-distortion layers.
FOLD sets their geometry; PRESSURE drives bounded deformation; DRAG delays it;
SPLIT separates the shapes and response times; RELAX unfolds them. HOLD extends
finite notes and reserves its top step for a recurring, converged pressure loop.
The MIDI root stays outside the six controls. Shape-sensitive reciprocal load,
history/instantaneous and coupled/independent probes, higher-rate references and
raw diagnostics make the mechanism reviewable before factory voicing.

`./gradlew :synth:generateCreaseAudition` generates the dry listening matrix and
standalone audition recipes in `testkit/crease-audition-r3/`. The first audition
had insufficient variation; the owner preferred model 2's wider analytic phase
maps. Model 3 retains those shapes and refines lag/recovery, with six named
listening presets and constant-level gesture comparisons. Models 1 and 2 remain
exactly replayable. The approved three voices and six presets are registered in native patches,
velocity routing, the SYNTH picker, FRESH TAPE and CLI. Model-3 MAKE INSTRUMENT and
CLI `--instrument` retain one continuous attack/settling/held history, sample
bounded minor-third zones around the saved root, and export through the existing
phone/dual-MPC package path with a player-controlled release. Paper Keys and Ink
Ribbon are the first held-instrument listening pair. Optional TOUCH LAYERS retains three independent velocity-driven
pressure histories, level-matched by their sustained loops before live key
gain; default single-layer rendering stays unchanged. The touch study adds a
PINCH root comparison without a new factory voice. Voice expansion remains
separate work. [CREASE](CREASE.md) records the scope and
reproduction commands.

### THUMP — analog-style drum voices (the drum synth engine)

One engine, per-class voice models, each a small parameter set with big
range. First-generation voices:

| Voice | Model | Macro knobs |
|---|---|---|
| Kick | sine w/ exponential pitch sweep + click transient | TUNE · SWEEP · DECAY · CLICK · DRIVE |
| Snare | two detuned tones + filtered noise | TUNE · SNAP (noise mix) · DECAY · TONE |
| Hats | six-oscillator square cluster → bandpass (the classic metallic recipe) | TUNE · DECAY (cl/op) · METAL |
| Clap | burst train + noise tail | SPREAD · DECAY · TONE |
| Tom / Conga | swept tone, tunable family | TUNE · SWEEP · DECAY |
| Cowbell / Rim / Clave | two-tone square pair / damped tick | TUNE · DECAY |
| SPARK (prototype; awaiting audition) | sine body + phase/ring-modulated edge, separate low-pass responses; related hit snapshots, with MOVE deferred ([design and validation](THUMP_SPARK.md)) | TUNE · DECAY · PUNCH · METAL · COLOR |

Every voice has since grown PUNCH (U3), and SNARE a membrane body with
STRIKE and WIDTH (Phase 1B). **Sound-design round (shipped):** each voice
then opened up the constants it used to hardcode, as macros whose default
is the old constant exactly, so no preset that predates them moved:

| Voice | Added | What it opens up |
|---|---|---|
| Kick | BEND · HOLD | pitch-drop speed (long dive ↔ instant thud); a full-level hold before DECAY, the sustained boom |
| Snare | RATTLE | wires choked with the head ↔ sizzling long after it |
| Hats | NOISE | white noise under the square cluster, the washy side |
| Clap | CLAPS · ROOM | 3–6 impacts; tail level, dry slaps ↔ a hall |
| Tom | BEND · CLICK · DRIVE | synth-tom "pew" ↔ tight knock; a stick attack; saturation |
| Cowbell | RATIO · TONE · RING | the pair's interval (sour ↔ agogo-like); filter centre; filter resonance |
| Rim | RING · BODY | dry tick ↔ pitched clave; a drum head under the rim (a rimshot) |

KICK and SNARE reach eight macros - past the seven TIDE's notes below
treat as the ceiling, deliberately: the owner chose range over the cap for
this round. The new ones stay off DE-SAMPLE's grid (each searched macro
would triple its voice's points), and every voice gained four BOLD
presets that push them.

**SKIN got the same round**, in the same shape (defaults exact, off
DE-SAMPLE's grid, four BOLD presets per voice), with macros that suit a
modal engine rather than an oscillator one:

| Voice | Added | What it opens up |
|---|---|---|
| Kick | CLICK · DROP | the beater, felt ↔ hard plastic; the head's pitch dip when struck hard (tension modulation, every mode at once) |
| Snare | RATTLE · SIZZLE | wire ring against the head's; the wires' highpass, level-matched so it moves shape, not loudness |
| Hats | RING | the bank's resonance, a trashy band ↔ ringing metal (default 0.8: the travel is below today's sound, not above; the trashy end stops where closed hats would start reading as SNARE) |
| Tom | DROP · CLICK | the classic tom bend; a stick on the head |
| Ride | SIZZLE | rivets: a bright buzz outlasting the wash |
| Shaker | SWELL · GRAIN | a thrown "shh" attack; separate beads instead of hiss |
| Stick | BODY · CLICK | a shell under the stick; a wood crack |

Ride BELL (clear tones from the cup) was built and dropped: any bell above
~10% of the wash's peak classifies as SNARE, and one quiet enough to pass
was all but inaudible. SIZZLE took its place because it pushes the
centroid away from that line instead of across it. `Dsp.around` - the
exact-default map both engines use - moved out of THUMP for SKIN to share.

**TINES got the same round** (defaults exact - including the EP and music
box `Keys` builds from `Tines.strike`, which it calls with its own
constants and never sees these macros - and four BOLD presets per voice):

| Voice | Added | What it opens up |
|---|---|---|
| every voice | BITE | how much faster the FM index dies than the note: a sharp strike that settles pure ↔ a buzzy tone that stays bright to the end |
| Bell | CLANG | the partner strike's interval, a fifth ↔ the stretched octave ↔ off every harmonic (church bell, gong), louder at the top |
| Chime, Block | RATIO | the snapped ratio set BELL already had; each default lands on the ratio the voice used to hardcode |
| Zap | BEND · RATIO | the drop's speed, long laser ↔ pitched thud; the FM colour |
| Toy | RATIO · SHAPE | the FM colour; the wobble squared off, sliding pitch ↔ a stepping chip trill |
| Kalimba | TICK | the nail, fleshy thumb ↔ six times today's click (measured ~-27 dB under the onset today) |

Presets ship, knobs refine — MVP is preset + 3-5 macros per voice, never a
modular patchbay. A "SYNTH KIT" action renders a whole 16-pad kit from one
style preset (the demo kit becomes THUMP's factory default).

### MURK — tuned wood, traveling pressure, and answering owls

MURK ports the [engineering spec](superpowers/specs/2026-10-03-murk-engine-engineering-spec.md)
into the offline melodic engine pipeline. Six voices — CLUNK, THWACK,
FRONT, HOOT, GROVE, ALARM — share four tuned trees, a damped bidirectional
grove, and finite synthesized owl responses. STRIKE changes contact,
TRUNK changes wood and cavity, FOG loads the source and transports sound,
AGITATION changes response behavior, and GROVE changes spacing and transfer.
TUNE follows the existing note grid; HOLD supplies repeated strikes for a
settled recurring loop. Velocity enters through the existing physical-strike
adapter, separately from contact character.

The listening gate is `./gradlew :synth:generateMurkAudition`, which writes
`testkit/murk-audition/index.html`, its manifest, and WAVs. Playback starts
only on request. Every clip has a readable description, and the page supports
keyboard controls and stopping all sound. Raw/matched comparisons and isolated
wood, atmosphere, and owl clips expose the dry engine before effects. See
[MURK](MURK.md) for contracts, diagnostics, and the review procedure.

The port remains subject to the owner's listening verdict and phone/MPC checks.
Numerical stability and deterministic rendering do not establish sonic acceptance.

### NIMBUS — six suspended cymbals at one root

NIMBUS ports the [engineering spec](superpowers/specs/2026-10-05-nimbus-engine-engineering-spec.md)
into the deterministic offline melodic pipeline. Every render contains Body,
Bell, Paper, Dark, Flex and Wire. Six voices select excitation and response;
upper modal ratios, losses and bounded flex distinguish the cymbals while the
principal modes retain the requested note. Neighbor exchange, slow suspension,
chamber reaction and motion-derived rim contact create the spreading and
gathering gesture. HEIGHT and SPACING control independent geometry.

Nine dry presets and `SynthKits.nimbus()` provide a sixteen-pad kit. The SYNTH
picker, FRESH TAPE, patches, recipes and physical velocity share the existing
host interfaces. HOLD supplies explicit powered modal drive and settled loop
material. The [published audition](https://snipsnap-nimbus-audition.bertcalm.chatgpt.site)
and `./gradlew :synth:generateNimbusAudition` expose voice/pitch/velocity checks,
macro and interaction sweeps, raw/matched pairs, isolated identities, causal
switches and held loops. See [NIMBUS](NIMBUS.md) for implementation limits and
validation. The owner's listening verdict and phone/MPC checks remain open.

### TESSERA — mixed materials answering through a moving chamber

TESSERA ports the [engineering spec](superpowers/specs/2026-10-05-tessera-engine-engineering-spec.md)
into the deterministic melodic pipeline. WOOD, COURSE, TUBE, ANSWER, FOLDING
and CHAMBER use one coupled object: wooden bars, paired string courses, metal
tubes and a shared frame. MATERIAL changes hammer projection continuously;
HAMMER changes contact, SCALE and FOLD shape the returning paths, and MOTION
lets bounded powered walls reshape sound already traveling. Receiving ports
and finite pressure collectors excite the other materials inside the engine.

TUNE spans C3–C5. HOLD extends finite activity before its top step exports a
settled loop, without the initial isolated strike. Eight presets, a dry
sixteen-pad kit, patch recipes, CLI rendering and the phone synth picker use
the same object. `./gradlew :synth:generateTesseraAudition` generates the
listening page, raw/matched audio and causal traces under
`testkit/tessera-audition/`. [TESSERA](TESSERA.md) documents the model,
integration contracts and listening gates. Sonic acceptance remains the
owner's listening decision; device validation remains outstanding.

### STOOP — original groove voices with an answering articulation

STOOP ports the [supplied engineering specification](superpowers/specs/2026-10-10-stoop-engine-engineering-spec.md).
BASS, TINE, CLAV, ORGAN, LEAD and CHORD use family-specific source recipes.
TOUCH changes attack character continuously; COLOR shapes spectrum;
SHADOW adds a quieter, newly articulated reply; WEAR supplies bounded
recording character; LENGTH shapes the finite envelope. HOLD returns
settled loop-only material, including a sustained shadow companion.

Patches retain explicit MIDI 24–96, gesture velocity, source settings,
shadow timing/interval/articulation, chord intervals and model version.
The initial 48-preset roster is weighted toward bass and keys (16 bass,
10 tine, 8 clav, 6 organ, 4 lead, 4 chord). It awaits the owner's listening
verdict; source variety and numerical checks are not sonic approval.
Finite notes route TONAL, held notes LOOP. The phone picker, CLI, saved
recipes and dry sixteen-pad starter kit share the renderer.

The October 23 Luthier polish introduces model 2: finite CLAV retains a
selected low-harmonic body behind its pick, and held CLAV precomputes its
fixed registration. Narrow corrections keep CHORD filtering referenced to
the marked root, HOLD's oscillator clock stable when SHADOW is raised from
zero, and LENGTH active in bright settled spectra. Saved model 1 recipes
retain their renderer; missing/null JSON model fields also load as model 1.
New constructors use model 2. Round Corner, Dust Tines, Soft Clav, Amber
Organ, Nose Talk and Velvet Stack stay on model 1; the other 42 factory
recipes retain their settings and use model 2. All eighteen legacy audio
fixtures remain bit-identical. The full JVM baseline passed 5,969 tests;
62 focused checks passed after the final root-preservation correction.
Held saw/pulse LENGTH now moves only upper partials, preventing a root
component null between otherwise valid control settings.

Run `./gradlew :synth:generateStoopAudition` for the listening matrix,
or add `-PstoopQuick` for its smaller smoke pack. Supplying
`-PstoopBaselineDir` and `-PstoopOriginalBaselineDir` preserves both published
banks in the 558-clip comparison: October 15 is the default previous-bank
comparison, with October 10 available as the first bank. [STOOP](STOOP.md)
documents model compatibility, reproduction commands, the as-built polish,
HOLD and the exported shadow's playback-rate limit.
Release naming and device/hardware validation remain separate gates.

### PITCHWHEEL — wooden teeth catching through stretching resin

The owner heard PITCHWHEEL R1 as six similar struck sounds distinguished
mainly by rhythm. Model 2 revised acoustic articulation; the owner's v2 review
marked five voices Revise and found PLUCK most different, with no Keep verdict.
Model 3 is the current provisional revision and awaits a new dry
listening verdict. CLUNK, PLUCK, DRAW, RECOIL, THAWED and TURN
share one pitched wheel, flexible fingers and bounded resin attachments.
PUSH supplies gesture energy; TOOTH shapes catches and releases;
ADHESION changes stretch and resistance;
HEAT sets the initial thermal state; BODY shapes the wooden resonance.
Independent renders reset wheel phase, heat and resin state.

Patches save an explicit MIDI root from 24–96 (default 48), velocity from
0–1 and a render model. Newly authored patches use model 3. Saved model 1
patches retain `PitchwheelV1`; saved model 2 patches retain `PitchwheelV2`.
JSON with an absent or null model selects V1. Editing, recipe replay and
velocity variants preserve the stored model. TUNE is neutral at 0.5 and
offsets that root by ±12 semitones, giving an effective note range of
12–108 without clamping
back to the saved-root range. Modes above 0.42 times the output sample rate
are removed from the audible pickup before decimation. HOLD at or above
0.99 returns settled loop-only material.
Its loop-specific calibration uses a deterministic attachment cycle and
caps adhesion at 0.65; it is an explicit export constraint rather than
evidence that every finite mechanical trajectory repeats.

Model 2 introduced stronger body pickup into the same ten-mode bank, separate
inharmonic body modes, voice-dependent loss and TOOTH projection, and a
contact-rise reservoir for release work. Finger friction, filament slip and
snaps use distinct receiving projections. Transfers remain charged to the
mechanical work ledger; modal radial growth adds exactly the allocated work,
and undelivered release energy counts as stored energy and held-loop state.
The geometry seed deliberately retains legacy model 1, preserving the tooth
layout across the revision. Revised acoustic transfers can still change
heating and damping, so this does not promise identical encounter timing.

Model 3 revises contact-position projections and individual mode losses so
body and harmonic differences can persist after the initial attack. DRAW and
THAWED use longer contact rise; THAWED's release projection responds to the
current resin temperature. PLUCK retains its model-2 release projection and
decay calibration. All voices still share the root and accounted ten-mode
network. The audition adds actual moving-wheel probes for funded bowing,
reverse encounters, thermal evolution and settled powered loops. Release-only
resonator hits compare body colour and decay without exercising those mechanisms.

The initial roster is limited to six voice defaults and Endless Turn.
`SynthKits.pitchwheel()`, the FRESH TAPE starter, patch recipes, CLI rendering
and the phone picker expose the same dry instrument. Finite notes route as
TONAL; held notes route as LOOP. Run
`./gradlew :synth:generatePitchwheelAudition` for the compact first listening
pack under `testkit/pitchwheel-audition/`, or append `-PpitchwheelFull` for
the broader raw/matched probes and interaction checks. [PITCHWHEEL](PITCHWHEEL.md)
records the model, diagnostics and integration contracts. The revised prototype
has not received the owner's dry listening approval; broader presets wait for
that approval.
Numerical verification, phone/MPC validation and release naming remain
separate acceptance work.

### LACQUER — layered tone with depth from articulation

Lacquer implements the supplied [engineering spec](superpowers/specs/2026-10-11-lacquer-engine-engineering-spec.md)
as independent generated body, edge, trail and quiet reinforcement layers.
KEY, GLASS, BRASS, BASS, PLUCK and SILK share RELIEF, SHEEN, BODY, CONTOUR
and GLUE, plus host TUNE and HOLD. RELIEF changes actual layer onsets and
envelope development. Held loops use periodic layer contours with separated
phases, because initial arrivals cannot remain audible in loop-only material.

Thirty-six recipes provide six per voice, preserving the original eight reference
recipes and the sixteen-pad starter. Saved patches retain the layer
model, timing, envelopes, processing and loop profile. The dry kit, recipes,
velocity renderer, CLI and synth picker follow existing host paths. Finite notes
route as TONAL; HOLD's top step routes as LOOP. The audition generator writes
real Kotlin-rendered native/matched audio, solos, five-point sweeps, interaction
grids and held comparisons to `testkit/lacquer-audition/`.
[LACQUER](LACQUER.md) documents reproduction and acceptance. The expanded bank
is available for owner listening review, with three twelve-preset MPC programs
and the original starter. Numerical checks do not establish a listening verdict;
Android device, MPC hardware and naming checks remain separate acceptance work.

### WICK — ignite, cascade, recover

The [supplied specification](WICK_SPEC.md) is being tested as a six-voice
PLUCK/WOOD/BREATH/BUBBLE/FLICKER/RATTLE listening prototype. One pitched complex source feeds three
parallel low-pass gates. Each has bounded charge and recoverable availability;
real releases schedule later pulses into the next gate. SPARK, FEED, CASCADE,
TIMBRE, RECOVERY and HOLD are the six controls, with explicit saved MIDI 24–84
and velocity. The oscillator/filter techniques draw on TIDE; the gate network
and release scheduler are new.

Twenty-five provisional recipes, JSON/velocity/CLI integration and a dry sixteen-pad
kit support the first audition. WICK stays outside the factory preset roster,
phone picker and starter menu pending listening. Run
`./gradlew :synth:generateWickAudition` for native/matched dry audio, five-point
controls, register/velocity probes, mechanism ablations, held examples and seven
musical phrases: six solo voices using one score and the retained three-voice arrangement.
The [private audition](https://wick-snipsnap-audition.bertcalm.chatgpt.site)
and [implementation notes](WICK.md) retain the scope and current limits.
The proposed 36-preset library follows listening to RATTLE.
The longer pitched BUBBLE response is the owner's preferred working baseline;
the owner also accepted WOOD's dry pitched body with smaller answering rebounds.
The owner also accepted BREATH's sustained root and soft overlapping openings.
The owner also accepted FLICKER's brief metallic answers around a clear pitched body.
RATTLE now tests pitched clusters with depleted and recovering pulse strength;
[design notes](WICK_RATTLE.md) explain the target and its limits.

### TANGLE — oscillators that catch and release

The supplied [Tangle engineering specification](superpowers/specs/2026-10-11-tangle-engine-engineering-spec.md)
is ported as an experimental pitched engine. BASS, LEAD, HOLLOW, PULSE,
METAL and KNOT share two causal reciprocally modulated oscillators,
encounter-derived held controls, temporary phase attraction, octave-down
support and a quiet unmodulated root. TANGLE, CHASE, CATCH, BITE, WEIGHT and
HOLD are the six saved macros; MIDI 24–84 and velocity 0–1 are explicit patch
fields. CATCH at zero disables capture while reciprocal influence continues.

The initial audition roster has 36 provisional recipes: 10 bass, 8 lead
(including HOLLOW), 6 pulse, 6 metallic and 6 experimental, with held examples
for all voices. `SynthKits.tangle()` supplies sixteen dry C minor pentatonic
pads, preserving note and velocity in recipes. Patch, preset and velocity
dispatch, CLI overrides, the phone picker and FRESH TAPE starter use the
same engine. Finite sounds route as TONAL; HOLD at or above .99 selects LOOP.
Run `./gradlew :synth:generateTangleAudition` to build the dry listening pack
and diagnostic data under `testkit/tangle-audition/`.

This is an implemented prototype awaiting the owner's listening verdict.
Numeric verification does not establish that CHASE conveys who leads,
CATCH audibly settles and releases, or held loops preserve the texture.
Macro mappings, voice calibrations and names remain provisional. The port
does not claim a circuit recreation of its historical starting point.
[TANGLE.md](TANGLE.md) records the saved-format and integration contracts,
HOLD constraint and remaining acceptance work.

### OHM — finite coupled family strings

One plucked foreground and two answering companions exchange waves through a
shared bridge and membrane. TOUCH, BODY, LENGTH, MEDDLING and COMMUNE shape
the note; native MIDI 48–72, velocity, seed, STEADY/GATHER gesture and renderer
model survive recipes, edits, presets and pad placement. Model 2 retains a
clearer foreground and middle-range control variation after the owner's
listening feedback. Saved model-1 recipes keep the original renderer.

The five auditioned factory recipes join the synth picker and CLI. NEW KIT
→ STARTERS → OHM adds eight Solo notes in C minor pentatonic from
C3 to F4 and eight companion/register contrasts, preserving the accepted
macro maps. All sixteen pads are dry, unchoked TONAL one-shots.
`./gradlew :synth:generateOhmKit` writes `testkit/SnipSnap Ohm Kit/`, native
MPC 3 and MPC 2 programs sharing PCM24 WAVs, editable kit and pad recipes,
a manifest and a ZIP beside the kit. [OHM.md](OHM.md) records the layout,
performance measurements and device handoff.

Optional model 3 now adds friction bowing, embedded captured excitation,
bounded FM elder, source-audio grains, captured-buffer freeze and HOLD.
EXTEND opts in without changing the accepted factory bank. HOLD delivers a
loop-only pad; MAKE INSTRUMENT preserves the arrival and a sustain marker
through the existing phone/MPC keygroup path. Bowed HOLD uses a disclosed
captured join, while plucked/captured HOLD measures physical continuation.
`:synth:generateOhmExpandedAudition` isolates each addition and repeats held
material; `:shell:generateOhmExpandedHandoff` exports three provisional C3–C5
instruments. [Extension notes](OHM_EXPANDED.md) record source limits, loop
evidence, host costs and the listening status.

The model-3 sources and their combination studies remain unheard. Phone
response, phone save/reopen and MPC hardware acceptance remain open.
Separate rendered notes continue to start fresh ensembles.

### FLOCK — finite oscillator flocks

GATHER, RIBBON and SPLIT use twenty-four audible oscillators with changing
local separation, alignment and cohesion. SPREAD, PULL, FLOW, SCATTER, EDGE
and HOLD are the six controls; SYNTH also exposes ROOT (C2–C6) and VELOCITY.
HOLD selects a two-to-six-second one-shot at every setting. Saved roots,
velocities, exact 64-bit seeds and render models survive editing, preset
storage and pad placement; SPREAD uses the authored root.

The owner confirmed that revised FLOW is audible. Model 2 changes the pace of
the existing group equations while preserving each original default exactly;
model-1 recipes retain their original rendering behavior. Nine factory presets
join `Presets` and the phone picker. FRESH TAPE's fixed FLOCK starter supplies
eight GATHER notes in C minor pentatonic from C4 to F5 and eight other factory
recipes, all dry TONAL pads without choking.

`./gradlew :synth:generateFlockKit` writes `testkit/SnipSnap Flock Kit/` with
a native MPC 3 `.xtd`, an MPC 2 `.xpm` twin and shared PCM24 WAVs in its
`_[TrackData]` folder, complete editable recipes, a manifest and a ZIP beside
the kit. [FLOCK.md](FLOCK.md) records the exact layout and listening evidence.
RIBBON now also offers explicit saved HELD playback, separate from finite HOLD.
It captures a two-second opening and roughly six-second body, supplies native
press/release audition, and makes a nine-root phone/MPC keygroup instrument with
saved release and source recipe. See
[FLOCK_RIBBON_INSTRUMENT.md](FLOCK_RIBBON_INSTRUMENT.md). Live interaction between
separately rendered notes remains outside this sample-based path. Phone playback,
target render latency and MPC hardware checks remain open.

### EMBOUCHURE — effort changes how the note speaks

A first dry prototype supplies a finite virtual-player gesture to one pitched
resonator through a reed-like, lip-like or friction driver. REED, BRASS, BASS,
WHISTLE, BOW and SOFT share EFFORT, REED, SHAPE, GRAIN and LENGTH; HOLD supplies
continuing energy for a settled loop, and TUNE is the host note control.
WHISTLE is explicitly a clear air/reed-derived approximation. Each independent
mono render starts fresh; source power stops before a finite note's passive
release. Velocity scales player input within the selected EFFORT character.

The initial eight original presets are Leaning Reed, Muted Bell, Breath Pocket,
Soft Entry, Short Overblow, Relaxed Bow, Narrow Air and Held Effort. They save
MIDI base, velocity, renderer model, fixed driver and a named smooth player
profile beside the macro map. TUNE spans 24 semitones above the saved base;
both base and played note must fit the voice's calibrated register. CLI
`--midi` selects the played note directly by resetting TUNE to zero.

Model 2 widens the physical control response after feedback that the first
controls were too subtle, while preserving all six finite default voices
sample-for-sample. Model 1 is frozen for existing recipes, including JSON that
omits its model field. The audition includes original/revised comparisons at
identical settings and separate listening notes for each model.

The factory catalog has 36 presets: 8 REED, 6 BRASS, 6 BASS, 4 WHISTLE, 6 BOW and 6 SOFT.
The accepted initial eight retain their recipes, names, order and Model 2 sound.
`SynthKits.embouchure()` keeps eight REED pentatonic notes and those original
eight dry presets, all with recipes. Three additional sixteen-pad factory banks
pair REED/WHISTLE, BRASS/BASS and BOW/SOFT, each with twelve presets and four
complementary keyed notes. The `embouchure` FRESH TAPE starter, normal SYNTH
picker, user presets, native velocity variants, CLI rendering and host MPC
export use the same source. Finite notes are TONAL, HOLD is LOOP.
`./gradlew :synth:generateEmbouchureAudition` writes the listening pack under
`testkit/embouchure-audition/`. [EMBOUCHURE](EMBOUCHURE.md) records configuration,
routing and audition conventions; the attached brief is preserved under
[engineering/embouchure-engine-spec.md](engineering/embouchure-engine-spec.md).
The separate edited Gyre contract mentioned by that brief was not provided;
current sibling APIs govern this port. The owner accepted Model 2's voices and
controls and authorized the 36-preset expansion. The new presets still need their
listening verdict. Android/MPC hardware playback and release naming remain
separate checks; the drum exporter requires manually enabling repeat for HOLD
samples.

### MURMUR — FM articulation through a moving mouth

MURMUR ports the [supplied engineering specification](engines/MURMUR_SPEC.md)
into six dry pitched roles: BASS, KEY, PLUCK, LEAD, CHOIR and METAL. A small
four-operator FM core feeds a moving formant bank, with a direct tonal anchor
and seeded breath shaped by the same mouth state. MOUTH, EDGE, BREATH,
MOTION and BODY are its five timbral controls; HOLD is the sixth control.
The requested root is an explicit MIDI note from 36–96 (default 60), and
velocity changes excitation energy and bite rather than changing a macro.

Each saved recipe retains its routing, four operator ratios, complete
formant targets, trajectory, direction, reference duration and envelope.
Patch editing, velocity layers and rack replay preserve that state.
The six dry default voices have owner approval after reducing CHOIR's air.
The 36-preset bank preserves the original twelve and adds twenty-four dry patches:
eight BASS, four KEY, four PLUCK, eight LEAD, six CHOIR and six METAL in total.
The accepted renderer remains unchanged. `SynthKits.murmur()`
contains sixteen dry recipe pads: eight KEY notes in C minor pentatonic
from C3, seven contrasts and a settled held choir. Finite notes route as
TONAL and HOLD at or above 0.99 routes as LOOP, with no drum choke group.

The Android picker and FRESH TAPE starter expose the engine. ROOT and
VELOCITY are separate from the six macro sliders; the CLI accepts
`snipsnap synth MURMUR KEY --all --midi 60 --velocity .6 --out <dir>`.
`./gradlew :synth:generateMurmurAudition` builds raw/matched examples and
the standalone listening page; `-PmurmurFull` adds the broad note,
velocity, macro and interaction matrix. `generateMurmurKit` exports the
sixteen-pad MPC acceptance kit. HOLD exports settled sustain without the
initiating onset because the sample API has no attack-plus-loop region.
The expanded preset bank's listening review, phone/MPC validation and release
naming remain open.

### REVEL — interlocking skins through moving microphones

REVEL ports the [supplied specification](REVEL_SPEC.md): six modal heads at
four stations share a passive floor and one deterministic interlocking phrase.
A bounded friction contact, pressure-bent articulated pair, alternating rolling
pair and broad pulse remain active across CIRCLE, CLOSE, CROSSING, SPIRO,
FRICTION and PROCESSION. One to three microphones observe the same source states
through moving gain, absorption and fractional-delay paths, summed to mono.
PLAY, SKIN, ORBIT, WEAVE and REACH are the five timbral controls; TUNE supplies
the host's C3–C5 root and HOLD selects settled loop-only material.

Patches save phrase timing, microphone geometry, performer seed and velocity.
Ten provisional presets, `SynthKits.revel()`, the phone picker, FRESH TAPE,
CLI note/velocity overrides and dry recipes use the existing melodic pipeline.
`./gradlew :synth:generateRevelAudition` produces raw/matched comparisons,
source and floor studies, microphone contrasts, macro grids and held loops.
Add `-Pquick` for the compact first listening set. The
[private published audition](https://snipsnap-revel-audition.bertcalm.chatgpt.site)
awaits the owner's listening verdict. [REVEL](REVEL.md) documents current
contracts and measured validation; phone/MPC verification and release naming
remain separate gates.

### LATTICE — directional passage and a rejected outline

LATTICE adapts the supplied projection-gate concept to the offline melodic
renderer. One deterministic harmonic source feeds four directional groups;
ordered gates transmit surviving components and send actual rejection into a
bounded resonant outline. TURN, STAGES, TWIST, COLOR and REJECT expose the
mechanism; the audition retains common source gain while crossed transmission
disappears and intermediate orientations restore it.

The owner accepted R2's source and contrast direction on 2026-10-23: “New sounds
are better”, then “Clearer, we can proceed to the next steps”. The native
milestone implements WARM/PLUCK/HOLLOW voices, model-2 patches with explicit MIDI
and velocity, relative TUNE, finite HOLD and settled periodic sustain at HOLD's
top step. It adds twelve dry factory proposals, a sixteen-pad melodic starter,
CLI routing and the Android picker. New presets and HOLD still await hearing.
Native validation and physical phone/MPC checks remain separately recorded in
[LATTICE.md](LATTICE.md); moving gate geometry and the rest of the original
engineering specification remain later work. `generateLatticeAudition` retains
the accepted comparisons and adds R3 native/held examples plus patch and MPC
starter downloads. The original R1 and accepted R2 evidence stays preserved.

### PRISM — harmonics with different lifetimes

PRISM ports the [engineering specification](superpowers/specs/2026-10-11-prism-engine-engineering-spec.md)
as a deterministic 32-partial additive engine. KEY, PLUCK, ORGAN, BASS, GLASS
and VEIL share five independently developing spectral groups. GRAIN changes
spectral organization, DRIFT separates group timing, SHAPE moves broad partial
masks, STRIKE articulates the onset and BODY reinforces lower and middle energy.
TUNE keeps the requested root stable; HOLD exports settled loop-only material.

The 40-preset bank, patches, dry sixteen-pad kit, starter, CLI and phone
picker use the same 44.1 kHz mono/4× renderer and shared melodic loudness.
Finite notes route as TONAL and held material as LOOP. The
[published audition](https://prism-snip-snap-audition.bertcalm.chatgpt.site)
preserves the earlier factory bank, raw/matched voices across register and velocity,
five-point sweeps, interaction corners and HOLD extremes. Run
`./gradlew :synth:generatePrismAudition` to generate the current 40-preset pack.
[PRISM](PRISM.md) records saving, rendering and loop contracts. The owner's dry
sonic review is positive. Phone and MPC playback still require device validation;
PRISM remains a working name pending release naming checks.

The source-derived [body opening](PRISM_OPENING.md) is the owner's preferred
motion direction. Soft Arrival, Wire Bloom, Bowed Bloom and Hollow Rise append
their complete saved A/B recipes after the preceding 36 presets, preserving
existing sounds and selection indices. Their finite 0.8-second movement needs
no donor audio at playback. The phone keeps HOLD below its loop threshold for
these recipes; factory promotion retains their motion. A separate release
matrix covers three notes and two velocities per opening. Repeating motion,
additional motion controls and a donor picker remain later work.

### SHOAL — materials animated by moving energy

Model 3 carries the owner's preferred material-study direction into the engine:
WOOD, BOWL and WIRE select resonant frequencies, contact coupling and decay;
GLASS and RIPPLE select how energy moves. Native velocity changes contact and
modal excitation as well as loudness. Root MIDI 24–84 and material stay separate
from the six controls. New patches start with WOOD; saved models 1 and 2 retain
their exact harmonic renderer. Ordinary edits and native velocity retain the
saved model and material.

Six finite material recipes, two HARMONIC held recipes and a sixteen-pad C minor
pentatonic starter are available through the factory registry, CLI and phone
picker. The new bodies include passive resonant tails and explicitly refuse
HOLD loops. HARMONIC retains the tested captured periodic control trajectory.
`./gradlew :synth:generateShoalMaterialAudition` renders the production material,
motion, note and velocity comparisons; `generateShoalAudition` preserves the
model-2 matrix. Touch/velocity listening and phone/MPC playback remain validation
steps. ORGAN, WHISTLE, DRIFT, SWARM and the proposed 36-preset roster are later work.
[SHOAL](SHOAL.md) records the as-built contracts and retained proposal.

### ALLOY — generated attack entering a four-operator tonal body

The [supplied engineering specification](ALLOY_ENGINE_SPEC.md) is ported to the
existing deterministic offline engine, patch, velocity and kit interfaces.
MATERIAL blends generated wood, tine/string and breath/contact projections.
FUSE feeds the conditioned attack into carrier phase, while EDGE changes ordinary
PM articulation independently. BODY preserves root support and shapes carrier
balance; LENGTH and HOLD control finite gestures and settled periodic excitation.
Held exports omit the initial attack and retain MATERIAL/FUSE through the same port.

The listening selection retains original BASS/SOFT and revised KEY/PLUCK/BRASS/
STRING. Thirty-six factory presets provide six per voice, replacing the spec's
uneven allocation. The [selected release audition](https://snipsnap-alloy-audition.bertcalm.chatgpt.site/selected/)
uses those configurations for presets, register/velocity coverage, five-point
sweeps, interactions, attack-port removals and held seams. Its matching sixteen-pad
MPC kit carries complete native recipes. Generate it with
`./gradlew :synth:generateAlloySelectedAudition`; the original gallery and contrast
audition remain frozen references. The selected control matrix extends the legacy
tests without reinterpreting saved configurations. The voice choice has been heard;
the expanded preset bank, phone performance, physical MPC playback and release
naming remain separate acceptance work. [ALLOY](ALLOY.md) records the contracts.
This is original synthesis, with no factory PCM imports or compatibility claim.

### CONTOUR — a stable body with a moving digital edge

CONTOUR ports the [engineering spec](superpowers/specs/2026-10-11-contour-engine-engineering-spec.md)
as an original two-layer wavetable instrument. Eight generated harmonic families
provide phase/frame interpolation and pitch-dependent bandwidth. BASS, KEY,
PLUCK, LEAD, HOLLOW and SWELL select related table pairs and envelope/trajectory
recipes. SCAN sets their base spectra, OFFSET separates their spectral positions,
and MOTION changes travel before a bounded generic filter and compensated drive.
TUNE is the centered host pitch control; velocity changes excitation and spectrum.

Nine provisional recipes, a sixteen-pad FRESH TAPE kit, saved structural patch
configuration, the phone SYNTH picker and CLI rendering join the existing
deterministic mono pipeline. HOLD supplies settled sustain; the Keys adapter
exports a nonzero loop marker for keygroups. The existing CONTOUR rack effect
keeps its implementation and effect recipe format.

`./gradlew :synth:generateContourAudition` writes the dry listening matrix under
`testkit/contour-audition/`, including matched/raw controls, note/velocity checks,
five-point sweeps, required interactions, isolated layers and repeated held loops.
[CONTOUR](CONTOUR.md) records integration contracts and numerical verification.
The [Luthier EDGE review](CONTOUR_EDGE_REVIEW.md) addresses a resting envelope
that could hide the edge layer. The first revision saved the foreground response;
earlier JSON retains the legacy response. `generateContourEdgeAudition` exports
180 complete finite/held comparisons over five EDGE positions for owner listening.
After the owner clarified weak zero-to-maximum movement on seven recipes, those
recipes and corresponding fresh voices select a contrast response that trades
body overtones for edge detail with added root support. Soft Digital and Bright
Margin retain their preceding recipes, and the fresh PLUCK default retains
FOREGROUND. Both earlier saved response laws remain available. The
focused page compares pinned preceding recipes and adds 0 → 50 → 100% sequences.
The [first-round audition](https://snipsnap-contour-audition.bertcalm.chatgpt.site)
is published with owner-private access and all 477 cases.
The owner's sonic verdict precedes expansion to the proposed 36 presets;
Android/device, MPC hardware and release naming checks remain open.

### SKITTER — provoke, flutter, settle

SKITTER ports the [engineering specification](superpowers/specs/2026-10-11-skitter-engine-engineering-spec.md)
into the deterministic melodic pipeline. BASS, LEAD, HOLLOW, FLUTTER, CHIRP and
FRAY share two oscillators with unequal pitch recovery, a seeded noise exciter,
signal-history detectors, bounded agitation and one nonlinear multimode filter.
BUZZ, SKITTER, AGITATE, VENT and STATIC act inside that coupled system; HOLD
selects periodic sustained material.

Patches retain MIDI root 24–84, velocity and seed. Thirty-six dry presets and
`SynthKits.skitter()` provide the initial roster and sixteen-pad kit. Patch
recipes, physical velocity, CLI, FRESH TAPE and the phone synth picker expose
the same engine. `./gradlew :synth:generateSkitterAudition` renders the
voice/register/velocity matrix, macro sweeps and interactions, causal ablations,
raw/matched clips and held boundaries. The [published audition](https://skitter-snipsnap-audition.bertcalm.chatgpt.site)
is the listening gate; [SKITTER](SKITTER.md) records the implementation,
measurements and explicit HOLD approximation. The owner's sonic verdict and
phone/MPC checks remain open. The history and agitation model is a new design,
not a reconstruction of the historical Wasp circuit.

### CRUNCH — the character processor (not a synth, the secret weapon)

Vintage sampler character as a per-pad effect: bit-depth reduction to ~12-bit,
resample through a low, era-correct rate, filter. Two reasons it's in the
synth roadmap:

1. It's what makes THUMP sounds gel with captured material.
2. Applied to *captured* snips, it's the "make my YouTube rip sound like
   1987" knob — arguably more valuable than any oscillator.

Cheap DSP (quantize + resample + one-pole filters), lives in the `Cleanup`
stage as an optional pass, per-pad setting stored in `kit.json`.

### The key engines — chosen for fun-per-knob

Brainstormed against one axis: playability. Every paradigm below is the
*fun-first* version of itself, and all of them obey the playability rules
(next section).

**VELVET — subtractive.** The undisputed playability king: filter +
resonance is the most gratifying knob in synthesis and it's nearly
impossible to make an ugly sound. Two oscs, resonant low-pass, two
envelopes, LFO. Macros: SHAPE (saw↔pulse w/ PWM) · FAT (detune/unison) ·
CUTOFF · SQUEEZE (resonance + env amount) · GLIDE (mono). Basses, pads,
brass, strings.

**TINES — FM, deliberately small.** Full FM is famously unfun to program;
the fun version is **two operators with RATIO snapped to musical values**
and one BRIGHT knob driving the index. Macros: RATIO (snapped) · BRIGHT ·
BITE (index envelope) · WOBBLE. E-pianos, bells, metallic percussion, growl
basses. Three fixed algorithms, never more.

**TONEWHEEL — additive, made playable.** Pure additive (draw 64 partials)
is synthesis as data entry — the least playable paradigm there is. But
additive with a handle humans have loved for 90 years is **drawbars**:
eight harmonic sliders, a PERC click register, WARBLE (vibrato/chorus),
DIRT (drive). TapeOS loves sliders, and organ stabs on a 4×4 grid are
ridiculous fun. This is the additive engine.

**PLUCK — Karplus-Strong physical modeling (1983, era-correct).** The best
fun-per-parameter ratio in synthesis: essentially one knob (DAMP) and it
always sounds good. Macros: DAMP · BODY (resonator colour) · PICK (exciter
brightness) · DOUBLE (12-string detune; the sympathetic tarab on SITAR). Kalimbas, nylon guitar, harps,
koto. Cheap to render, impossible to ruin.

**Free bonus, no engine required:** a chip-tune preset pack is VELVET
squares rendered through CRUNCH. Maximum kitsch, zero new DSP.

### The playability rules (these outrank the engine list)

1. **Preset-first, knobs-second.** Every engine is playable at first touch;
   3–6 macros, never a patchbay.
2. **Macros speak plain words** — BRIGHT, FAT, DIRT, WOOD, AIR — never
   "mod index". A macro moves several parameters underneath so it always
   does something musical.
3. **Bounded ranges.** You can't detune into garbage unless you ask; sweet
   spots are wide by construction.
4. **SCRAMBLE.** A dice roll within musical bounds on every panel, always
   undoable. Half of synth fun is slot-machine discovery.
5. **The instant loop.** Knob turn → re-render (one-shots render in tens of
   ms) → auto-retrigger, so every move is heard immediately.
6. **Sampling-era honesty.** Motion (LFO, sweeps) bakes into the render —
   you are sampling a synth, which is exactly the workflow the MPC was born
   into. Sustained key patches render a loopable sustain segment; loop-point
   authoring joins the keygroup export work.

## Keys need the keygroup door

Drum engines render one-shots onto pads — zero new export work. **Key engines
need KEYGROUP programs**: render the patch at several pitches (e.g. every
minor third across 4 octaves), export as a multisampled keygroup instrument
the MPC plays chromatically.

That makes keygroup export the gating dependency for TINES/VELVET keys — and
it's another golden-file job: save a keygroup program from real hardware,
template it, round-trip it (same method that built the drum writer). Worth
grabbing a keygroup export whenever the reference kits get made.

## The play surface stays 4×4 — settled

Keys are played on the same 4×4 pad grid as drums. No piano keyboard UI,
ever. This is consistency with the instrument, not a compromise: the MPC
itself plays keygroups on pads (Pad Perform), so what your hands learn in
SnipSnap is exactly what the hardware gives you back.

What follows from it:

- **Note layouts, not keyboards.** Bottom-left pad (A01) is the root.
  Two layouts at launch:
  - **CHROMATIC** — 16 semitones ascending left→right, bottom→top; just over
    an octave per bank.
  - **SCALE** — only in-scale notes on the pads (pick key + scale), 16 notes
    ≈ two-plus octaves, wrong notes physically impossible. The mode most
    people should live in.
  A fourths-stacked grid (rows offset by a fourth, chord shapes become
  grips) is a later third option.
- **Banks are octaves.** A/B/C/D shift the same layout up/down — the same
  muscle-memory move as drum banks.
- **Keys need note-off.** Drums are one-shots; keys sustain while held and
  release on lift. The play engine grows hold/release, per-patch mono mode
  with glide (SQUELCH-style basslines are exactly mono + glide), and slide
  becomes glissando in scale mode.
- **Export matches.** Rendered keygroup programs play the same way on the
  MPC's own pads; the root-note and layout conventions travel with the kit.

## Where it lives

- New `:synth` module, pure Kotlin, zero deps, same testing discipline.
  `DrumSynth` in `:audio` is the seed and eventually thins to a wrapper over
  THUMP presets.
- Patches are JSON (via `:json`), stored per-pad in `kit.json` (a synth pad
  keeps its recipe next to its rendered WAV, so it stays editable) and as
  shareable preset files. **Shipped**: `PadRecipe` (a [`Patch`] from any
  engine + an `FxChain`) serializes into `KitPad.recipe` — opaque to `:kit`,
  typed in `:synth` — and the factory kits carry it on every pad. The test
  is the promise: a kit folder regenerates its own WAVs bit-for-bit from
  nothing but `kit.json`.
- UI is a TapeOS control panel: sunken LCD scope showing the rendered
  waveform, chunky sliders, preset list in a sunken listbox. Peak-1996
  plausible — parameter synths in software are exactly the ReBirth-era move.
  Engine panels are where the loudest scheme's users get what they deserve.

## Phasing

| Phase | Ships | Depends on |
|---|---|---|
| S1 | **shipped** — `:synth` module: THUMP voices, SCRAMBLE, patch JSON, SYNTH KIT render + testkit export | — |
| S2 | **shipped** — CRUNCH character pass in `:synth`, works on captured snips too | — |
| S2.5 | **shipped** — the FX rack: EQ (RBJ three-band: BASS/MID/AIR, 0.5 = flat), SQUASH (punch), TAPE (wow/flutter + hysteresis drive + head wear, ChowDSP-inspired), ECHO (tape delay), SPRING (Schroeder reverb), REVERSE; `FxChain` fixes the order (reverse→eq→squash→crunch→tape→echo→spring), owns a total tail budget, serializes per-pad JSON | S2 |
| S2.6 | **shipped** — SMEAR joins the rack (reverse→**smear**→eq→…): the STN transient mask taken out by AMOUNT, peak-matched; the `smeared` character; on the phone, the PAD SHEET's second TREATMENT row | S2.5 |
| S3 | **shipped** — TINES percussion voices (BELL/CHIME/BLOCK/ZAP/TOY, 2-op FM, snapped RATIO) fill A13-A16 of the factory kit | S1 |
| S3.5 | **shipped** — PLUCK (Karplus-Strong, 4 body voices) + TONEWHEEL (8 drawbars + PERC/WARBLE/DIRT, 3 registrations) render one-shots; TUNE snaps to semitones; `SynthKits.melodic()` is a playable pentatonic kit | S1 |
| S3.7 | **shipped** — VOX (three-formant vocal synthesis: VOWEL morphs A→E→I→O→U, CHOIR/ROBOT/GHOST) + GRAINS (granular resynthesis — the engine that eats captures: SIZE/SMEAR/DRIFT/PITCH/SHINE over any source snip); `SynthKits.cloud()` is the atmosphere kit | S1 |
| S3.6 | **shipped** — VELVET one-shot stabs (BASS/BRASS/SQUELCH/CHIP: naive saw/pulse stack + sub, resonant SVF with envelope sweep, SHAPE/FAT/CUTOFF/SQUEEZE/DECAY) — and the chip-tune bonus is real: `SynthKits.chip()` runs VELVET squares and THUMP/TINES drums through one CRUNCH converter | S1 + S2 |
| S3.8 | **shipped** — FATHOM bass voices (DEEP/GRIND/GLASS: sine with a SWEEP attack blip, a SPREAD-detuned saw pair, low-tuned 2-op FM with a snapped RATIO), engine-owned DRIVE before the filter, and GLIDE on every voice | S1 |
| S4 | **shipped** — keygroup export in both generations: `KeygroupWriter` (`.xpm`, corrected line-by-line against commercial programs) and `Mpc3TrackWriter.writeKeygroup` (`.xty`, corpus-guarded); hardware load check pending | reference corpus (done) |
| S5 | **shipped** — the instrument suite: `Keys` renders engines at exact MIDI pitch (EP from TINES with velocity-true soft/hard renders, Organ from TONEWHEEL with mathematically-cut sustain loops, Harp from PLUCK, Music Box from TINES), multisampled every minor third, packaged dual-generation (`.xty` + `.xpm` twin in one `_[TrackData]/`) | S3 + S4 |
| S6 | **shipped** — SKIN, a second drum engine alongside THUMP: modal synthesis rather than THUMP's oscillators (KICK/SNARE/TOM sum decaying sine partials at inharmonic ratios — a struck membrane's own recipe; STICK is that same recipe's single-partial limit; HAT_CLOSED/HAT_OPEN/RIDE run continuous noise through a bank of resonant filters; SHAKER runs continuous noise through one deliberately wide, non-resonant filter), eight voices classifier-verified against THUMP's own `DrumClass` gates where a dedicated class exists, PUNCH on every voice | S1 + U3 + U5 + U6 |
| S8 | **shipped** — RESIN, the ladder engine (BASS/LEAD/BRASS: a three-oscillator STACK through `Dsp.Ladder`, the four-pole transistor-ladder low-pass with tanh in the loop, measured before use; CUTOFF key-tracked, CREAM the feedback up to self-oscillation, CONTOUR the filter envelope's amount and speed on one knob) and CONTOUR, the same filter as a rack section after EQ — design in `docs/superpowers/specs/2026-09-24-resin-ladder-engine-design.md` | S1 + U5 + U6 |
| S8.1 | **shipped** — RESIN, held: a RESIN patch as a nine-zone keys instrument that sounds while a key is held (ATTACK/RELEASE; loops of whole sub-octave periods with the detuned square snapped to one beat per loop and the pitch fitted to whole frames; CREAM capped at the self-oscillation threshold), from SYNTH's `MAKE INSTRUMENT ▸` or `snipsnap synth RESIN <VOICE> --instrument` — design in `docs/superpowers/specs/2026-09-25-resin-held-pad-design.md` | S5 + S8 |
| S8.2 | **shipped** — RESIN, droning: a RESIN patch as a loop-grid track (`DroneBlock`, a recipe rendered at bake time) that breathes through the ladder (MOTION, BREATHS 1/2/4) and spans the fewest intervals that keep its note within 3 cents, every oscillator and breath whole cycles per loop so the wrap is exact; re-sliced on every tempo change, and never rendered on the engine thread; from SYNTH's `DRONE TO LOOP ▸` or `snipsnap synth RESIN <VOICE> --drone` — design in `docs/superpowers/specs/2026-09-25-resin-drone-design.md` | S8.1 + loop grid |
| S9 | **built, awaiting the audition gate** — TIDE, the West Coast engine (BONGO/DRIP/GONG/FLARE: a phase-modulated sine through a triangle-core wavefolder into a note-keyed low-pass gate whose release slows as it falls; FOLD/WARP/GLOW/DECAY/WANDER, RATIO on GONG and FLARE, DECAY holding GONG and FLARE open; WANDER seeded from the recipe and a take index; eighth-order band limit before decimation; forty presets) — spec and as-built notes under S9 below | S1 + U5 + U6 |
| S10 | **Phase 1 shipped** — GLINT, the phase-distortion engine (REED/BOTTLE/KAZOO: a sine burst at `k`× the fundamental windowed to zero by each cycle's end, so the formant sweeps while the pitch does not move; TUNE/PEAK/FOLLOW/BODY/BLOOM/DECAY, FOLLOW morphing the peak between absolute Hz and note-tracking over `Dsp.keyTrack`, PEAK snapping to integer harmonics up to k=12, the body decaying faster than the burst) — design in `docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md`. TRACE (the window taken from your own material) and the preset roster are Phases 2 and 3, gated on the audition. **D1 shipped** — BODY is a real second formant at `k/5.6`, clamped to `K_MIN` (2) below `kBase ≈ 11.2` (PEAK ≈0.575 at the reference note) — so at the shipped default (PEAK 0.45, kBase 8) the body pins to 2×f0, a 4× interval rather than 5.6× — on its own single envelope (the old mean-removed window copy was the same harmonic series the burst already carried, and measured inaudible), and it rides its own envelope at 0.8× the burst's — though the glass tail that ratio was meant to buy only measures on two of the three voices: REED and KAZOO lose body share from head to tail (2.31→1.25, 5.72→2.13) while BOTTLE gains it (28.5→29.4) and rings rather than burning off, and which of those a player wants is a question for the audition; BLOOM's depth is free of its rate, fixed at a 0.45 s sweep; and PEAK's harmonic snap stays in the render path but now floors at `k ≥ 3` — between `K_MIN = 2` and 3 there is only one integer, so snapping flattened both velocity layers onto it, and below the floor the ratio now runs free instead. Depth design in `docs/superpowers/specs/2026-09-26-glint-depth-design.md`. **D2 shipped** — three new mechanisms, not new windows: CICADA re-clocks its own carrier `CICADA_SUBCYCLES` (4) times inside every cycle of the fundamental, the window applied to that sub-phase rather than the outer one so none of the four inner restarts click; RATCHET steps a ladder of integer harmonics up from its own (snapped) base ratio, one rung every `RATCHET_STEP_SECONDS` (0.15s), but only at the next phase wrap, never mid-cycle; PLATE's formant is `k(t) = kBase·(1 + BLOOM·amp_env(t))`, tied directly to the note's own amplitude envelope rather than to any clock, so a longer note holds its brightness longer in absolute time than a shorter one does. Implementation diverged from the plan in two places: CICADA gets its own ceiling, `kCeilingFor` = `K_MAX / CICADA_SUBCYCLES` (10, not 40) rather than the shared ceiling every other voice uses, so its *effective* ceiling after the ×4 re-clock still lands on the same musical ceiling as the rest; and `ratchetLadder`'s bottom rung runs through `snapRatio`, not an unconditional round, so it stays unrounded below `SNAP_FLOOR` and above `SNAP_CEILING` the same way `ratioFor` already does — closing the velocity-collision bug `SNAP_FLOOR` fixed, on a second path. `macrosFor` stays uniform at six macros on every voice, RATCHET and PLATE included. The D2 audition (`testkit/glint-d2-audition/`, `GlintD2AuditionGenerator`) is the gate D3's preset roster waits on. **Redefined 2026-09-29 — built, awaiting the audition gate** — D2 measured the window slot nearly inert (a window swap ≈0.1–0.2 on the same-run distance scale, one harmonic ≈1.5), so a voice is now *the path the formant takes*, on one saw window: SWEEP (up or down — BLOOM becomes bipolar), STEP (RATCHET), BRASS (PLATE) and VOWEL (two bursts at mouth formants in Hz, PEAK choosing the vowel), plus BREATHE, a held note for every voice whose loop closes exactly because PD is periodic. TRACE failed its gate (pitched captures trace as a plain decay) and is dropped, as are BOTTLE, KAZOO, CICADA and the D4 carrier warp; old voice names still load. Design in `docs/superpowers/specs/2026-09-29-glint-paths-design.md`. MAKE INSTRUMENT ▸ makes any GLINT patch a nine-zone held instrument (`Keys.glintPad`, `GlintPadMaker`), and held renders keep the saw window's DC, about 0.11 of peak on VOWEL at the top of TUNE, with a one-pole blocker through the three-breath render named as the fix if a chord shows it. **DEPTH shipped** (PR #431) — a seventh macro (VOWEL's sixth), default 0 so every sound is unchanged, that rounds the window's edge over its lower half and fades a plain sine in over its upper half (the sine scaled to the burst's loudness, its share of the power the square of `2·DEPTH − 1`, a bare sine at 1); both ingredients are zero at the wrap, so `k` still moves only there. It was heard as probe clips by the owner before it was specified, and the real engine's pads went to him before it merged, with his verdict "good". **Factory roster built** — eight presets per voice, 32 in all (`GlintPresets`), each one of the numbered held-pad candidates of a stratified spread of PEAK, BODY, BLOOM and DEPTH (`GlintRosterCandidates`, the clips sent to the owner), chosen by a stated rule (not shrill, not nearly a plain sine, then the most different) and approved by him; the final listen is his ear's check. FOLLOW is exactly inert at the audition's pitch, so it ships with TUNE and DECAY at its default and is heard across the keyboard at the final listen. SCRAMBLE now seeds from a random preset, as every engine with a roster does. Design in `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md`. | S1 + U5 + U6 |
| S12 | **built, awaiting the audition gate** — SIREN, the dub siren (WAIL/TRILL/LASER/BIRD, a voice being the LFO's shape: a PolyBLEP square whose pitch the LFO moves in the log domain, phase-continuous, through a one-pole and a drive that GRIT opens together; TUNE snapped, RATE 0.25–25 Hz, DEPTH to two octaves each way, SWEEP the dive or climb into the note, HOLD the gated length whose top is LOOP — whole LFO periods, the pitch fitted so the pulse closes on whole cycles, cut at a zero crossing, for the SURFACE to hold under a finger; forty presets with one LOOP per voice; SEND TO PAD bakes the rack's ECHO into a one-shot's recipe and lands a LOOP dry) — design and as-built notes in `docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md` | S1 + U5 + U6 + the SURFACE |
| S13 | **built; PLUCK Phase 3a complete** — SITAR, the fifth string — the jawari ships at 0.010 (a clean A/B over 0.015; see the spec's "The jawari" for the three engineering rounds and two gates), the tarab raised to an audible level, four sympathetic loops under DOUBLE, per-voice stiffness allpass; stiffness ships HIGH from a single chip (`p3a_stiff_high`), and BODY ships at 1 settled across two gates (an initial chip, then a direct A/B once combined with the wrap and the raised tarab exposed a real tradeoff worth Josh's ear specifically — see the spec's "Dispersion" and "The gourd body"). Two narrow, disclosed exceptions came out of BODY 1: SITAR's own DAMP=1 duration is held to a looser, honest ceiling instead of the shared "short thud" bound, and two of its twelve presets (RINGING, LOW TONIC) carry their own smaller BODY override so their own most-exposed corners (DAMP 0; the root note, close to the body's 110 Hz mode) keep their usual peak headroom. A sourced body table remains a future follow-up the day a source opens, not blocking | S3.5 |
| S14 | **R1-R5 built** — FORK, the modal electric piano (TINE/BAR/NODE/REED: a struck bar rung through four tuned modes; TUNE snapped, STRIKE the hammer (cutoff, its own decay and the tine's swing together, plus a direct boost on the higher modes' own gain — real hammer physics, measured necessary once BAR's closely-spaced modes showed the filter channel alone was not enough), BARK the pickup's closeness, STIFF morphing the four modes from a harmonic string through the voice's own bar to a stretched one, DECAY the fundamental's own t60 with higher modes falling off faster by a fixed slope; a captured snip's own head can stand in for the hammer, kept in the recipe so a struck pad still regenerates from `kit.json`. TINE/BAR/NODE share an asymmetric magnetic pickup — `v / (1 - x)^2`, the standard reluctance model — whose even-harmonic bark thins on its own as the tine's swing decays, with no envelope faking it; NODE is the same cantilever tine as TINE, read at its own second mode's node (derived from the Euler-Bernoulli cantilever boundary conditions, not fitted), its own R2 A/B against TINE and R3's pitch glide (even pushed to a deliberately exaggerated 80 cents as a diagnostic) both coming back too close to call by ear — the owner's call at that gate was to settle there: 80 cents ships as NODE's real default, and TINE/NODE/BAR stand as FORK's own instrument, not a piano emulation. R4 reopened the piano question from a different angle — REED, a second real electric-piano family (a Wurlitzer-style electrostatic comb pickup, not another Rhodes-style tweak): a symmetric, odd-harmonic-dominant drive in place of the asymmetric bark (a naive electrostatic derivation gives the same curve back; the real, sourced difference is the comb electrode's own multi-tap geometry and reported odd-harmonic tone), a two-tap comb modal color reusing NODE's own mode-shape derivation, and — after the symmetric curve alone measured as a real spectral difference but still read as "different tone, not different character," the same verdict NODE and the glide got — a discrete mechanical-contact rattle at hard strikes and a close pickup, gated by a grace window and a refractory period once two real bugs (a near-zero impulse scale; a persistent waveform clip nobody had throttled) were found and fixed through a throwaway, never-committed prototyping pass. Thirty-two presets across the four voices and a real keys instrument, `Keys.fork` multisampled every minor third and exported dual-generation through `InstrumentSuite.renderFork`. R5 shipped U4's stereo opt-in — a sixth macro, WIDTH (default 0, every preset stays mono), spreading the bank's own overtones per-mode via `Modes.spread` (the fundamental stays dead centre) while each pickup stage runs independently per deinterleaved channel; `SPLIT TO PADS` and the phone's `STRIKE FROM ▸` picker stay open, app/UI scope) — design and as-built notes in `docs/superpowers/specs/2026-09-27-fork-electric-piano-engine-design.md` | S1 + U6 |
| S15 | **Phase 1a shipped** — SILK's string toolkit: PLUCK's Karplus-Strong loop lifted into `Strings.kt` (the tuning budget, the pick exciter, a per-sample `Loop`, the decay-following trim, the body drive) with no change to a single PLUCK sample — pinned render hashes per voice, and a frozen copy of the old loop matched sample for sample over 384 cases. SILK itself (OUD, GUZHENG, SANTUR, SHAMISEN; SCALE and INFLECT) is Phases 1b–4 — design in `docs/superpowers/specs/2026-09-27-silk-string-engine-design.md`, research in `docs/superpowers/plans/2026-09-27-silk-research.md`. **Note (2026-09-28):** the frozen copy is the pre-wrap PLUCK loop specifically — `Pluck.kt`'s own `ks` diverged from it the same day, on a parallel branch, when SITAR's jawari became the wrap (S13); `Strings.pluck`'s `jawari` is still the old soft-bend limiter. `Pluck.kt` intentionally does not call into `Strings` yet as a result — reconciling the two is a follow-on, not resolved here | S3.5 (PLUCK) |
| S16 | **Phase 1b shipped** — SILK's SCALE and INFLECT (fifteen scale rows, TUNE walking degrees, a bounded centred cents nudge), `Strings.Dispersion` (the corrected Rauhala & Välimäki stiffness allpass), `Strings.Course` (N detuned loops), and `Strings.Loop.retune` (a pitch envelope carrying allpass/filter state through), plus its first two voices: OUD (course, slide, Erkut's two-mode body) and GUZHENG (the pressed semitone bend via retune; its own stiffness dispersion is wired through but, at GUZHENG's actual B, measures too weak to move a partial — a real, disclosed limitation, not a bug, left for a follow-on). Registered through `Patches.kt`'s JSON dispatch and `Velocity.kt`'s macro/brightness lookup, with canary coverage in `DeterminismTest`/`PadRecipeTest`/`VelocityGrooveShuffleTest`; `SynthScreen.kt`'s engine picker and `SilkPresets.kt` are deferred, the same as shipped TERRA (absent from `SynthScreen.kt`) and GLINT/TERRA (absent from `Presets.kt`) already are, and presets are Phase 4's own job regardless, once a voice is proven rather than authored blind. Both voices classify PERC by default (GUZHENG may also read SNARE) — a plucked string's own energy never crosses the classifier's bass-dominant gate, the same reason PLUCK's own defaults read PERC rather than TONAL. PLUCK's two Phase 1a proofs (the pinned renders, the frozen-copy grid) still hold. SANTUR and SHAMISEN are Phases 2–3 | S15 (SILK Phase 1a) |
| S17 | **Phase 2 shipped** — SANTUR, a four-course mallet-driven voice: `Strings.course` and `Strings.pluck` both take a pluggable `Exciter` now (the burst that drives a loop is a parameter, not a hardcoded call), and SANTUR's own `Strings.mallet` is a raised-cosine pulse, not a pick burst, its onset width set from hardness the same way a piano hammer's own contact time sets its brightness (Askenfelt & Jansson's proxy, cited in the spec); the comb that shapes strike position (`positionComb`) is shared code, pulled out of the old pick-only path rather than duplicated. SANTUR's stiffness is fixed and sourced (`SANTUR_B = 3.1e-4`), not a knob, and its WASH is a sympathetic resonator bank built fresh per render from the current SCALE's own degrees across three octaves (`Silk.washModesFor`), tuned to the *un-inflected* table as the spec requires and deliberately deduplicated at the octave seams (a naive build was tried and shown, by a real failing test, to double-book a resonator at every seam; the shipped version does not). Two findings are disclosed rather than papered over: `Strings.course`'s per-loop feedback step, which the Phase 1b postmortem blamed for a multi-cent OUD detuning, could not be reproduced at its original value in either an excitation-isolated or a full end-to-end test — the step is shipped smaller anyway, as a costless precaution, but the real Phase 1b bug's cause is still open, and most likely lives in the U6 oversample/decimate path rather than in `course` itself; and SANTUR's own sourced B, like GUZHENG's in S16, measures too weak in the actual render to produce the partial-8 stretch its own physics predicts — the same disclosed limitation, now confirmed at a second, larger B. Registered through `Patches.kt`/`Velocity.kt` and a `DeterminismTest` canary; `PadRecipeTest.onePatchPerEngine()` and `VelocityGrooveShuffleTest`'s brightness list are genuinely one-per-*category* lists (GUZHENG, a fully shipped and macro-distinct S16 voice, was never added to either) and were deliberately left alone rather than padded with a redundant entry. `SynthScreen.kt` and `SilkPresets.kt` stay deferred, same as S16. The full render chain was verified by the test suite only — this sandbox cannot play audio, so SANTUR has not yet been listened to by ear, the same gate S16 and several other rows above flag open. SHAMISEN is Phase 3 | S16 (SILK Phase 1b) |
| S18 | **Phase 3 shipped** — SHAMISEN, SILK's fourth and last voice from the original spec's roster: a single string carrying a built-in pitch glide, the sawari buzz, and a bachi/skin strike. The headline finding: SHAMISEN's sawari nonlinearity (the spec's own `Collision`) needed no new DSP at all — it *is* `Strings.Loop`'s existing `jawari` fold-back, reused on a second voice via a small fixed, unsourced-magnitude dispersion (`Silk.SHAMISEN_B`) whose only sourced job is keeping the buzz's own precursor from vanishing (van Walstijn/Bridges/Mehes: "with EI = 0 the precursor disappears"), not a stretch claim. A correction caught by review, not assumed away: this is the *older*, pre-wrap jawari model — a one-sided fold-back proven equivalent to the legacy `Pluck.ks` sample for sample since Phase 1a — not the dynamically delay-shortening wrap SITAR's own production path (`Pluck.ks`) actually uses today; S15's own row already discloses that divergence. Real, sourced, and independently tested regardless — SAWARI's own tests check its claims directly rather than borrowing SITAR's current ones. What *is* new: `Silk.shamisenLoop`'s own built-in pitch envelope (not a macro — it fires on every note, scaled by PICK), running the opposite direction from every other `Loop.retune` caller (sharp-to-settled rather than low-to-high, per the sourced glide data); a body table (frame 573.2/630.8 Hz, neck 67.55/85.38 Hz) whose gains deliberately invert OUD's/GUZHENG's own low-mode-dominant shape, since SHAMISEN's radiated spectrum is a strong high-pass around 700 Hz; and SLAP, a new `withSlap` helper driving a small skin-mode placeholder from a synthetic noise burst rather than the string's own first difference, RMS-matched the way `Strings.bodyRing` matches its own wet mix. Two things are disclosed rather than silently resolved: the sourced pitch glide's own long-term drift (~0.8%, ~14 cents) exceeds the 5-cent bound every other SILK tuning test enforces, so the glide models only the sourced 100 ms fast transient and settles fully to the degree by its own end, a deliberate scope decision flagged in the code, not a silent contradiction of the source; and a genuine bug macro-fuzzing caught before merge — `shamisenLoop`'s initial tuning-budget call omitted `jawari`, while `Loop.retune`'s own internal budget always uses the loop's *real*, stored `jawari` value, so retuning back to the exact frequency the loop was built for could come out needing one more sample than the loop had room for; fixed by passing `jawari` into the initial call too, the same consistency `dispersion` already had, plus a cheap clamp as a second safety net against the same class of float rounding. A second review finding, also fixed rather than argued with: the energy-never-rises test's own peak was a plain global max over the whole render, which no later block can ever exceed by definition — tautological, not a check; rewritten to take the peak over an independent attack window instead, and confirmed (by temporarily flipping the fold-back's own sign) that the rewritten version actually fails when energy does grow, where the original could not have. The classifier was measured, not assumed against the spec's own stale "all four read TONAL" line (already known wrong since S16): SHAMISEN's default reads PERC, same as the other three voices, with no special case needed. Registered through a `DeterminismTest` canary only — `PadRecipeTest.onePatchPerEngine()` and `VelocityGrooveShuffleTest`'s brightness list stay untouched, the same corrected one-per-*category* precedent S17 established. `SynthScreen.kt` and `SilkPresets.kt` stay deferred, same as S16/S17. The full render chain was verified by the test suite only — this sandbox cannot play audio, the same open gate every prior SILK row carries. This closes SILK's original four-voice roster (OUD, GUZHENG, SANTUR, SHAMISEN); Phase 4 is presets, by ear, after each voice's own gate | S17 (SILK Phase 2) |
| S19 | **R1 built, awaiting the audition gate** — BORE, the blown bore (FLUTE and SAX): a valve in a pipe on `Strings.Loop`, where every other engine is a strike or an oscillator under an envelope. FLUTE is an air jet (half-period transit, a soft-clip labium, pressure as the jet's input gain); SAX is Smith's passive reed-reflection table on a cone. Five macros (TUNE, BREATH, LIP, CHIFF, HOLD), BREATH mapped onto each voice's *measured* speaking window so no corner is air or silence, a pitch pin, a tongue pop and a breath-noise chiff at the onset, a 90 Hz key thump at CHIFF's top (the tape keyboard's clonk, relocated to an onset), a pitch vibrato scaled by HOLD, and HOLD's top step a LOOP of about two seconds (since the seventh round cut from the held middle of a long one-shot and closed by a blend, with the one-shot's vibrato and breath, dry of rack tape; a join that does not hold throws. Until then a seamless two-second LOOP in whole periods, the played pitch corrected until they fit, with no noise and no vibrato). One-shot pads land with the rack's TAPE in their recipe (`Bore.landingChain`, WOBBLE .1 — the tape-flute story told as a recipe, not DSP); the spec's separate tape stage is the rack's TAPE by another name. Design and its R1 measurements: `docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md` ("R1, as built"). Registered through `Patches`/`Presets`/`Velocity` with canaries in `DeterminismTest`/`PadRecipeTest`/`PresetsTest`/`SynthKitTest`, blocklist terms for the tape keyboard and the reed makers (`mello(?!w)`, so "mellow" passes), `SynthKits.bore()` and `testkit/SnipSnap Bore Kit`, `generateBoreKit` and `generateBoreAudition`. **What R1 changed against its own design, measured:** SAX's root is C3, not C2 (a reed's onset is a number of periods: 0.3-0.4 s at 131 Hz, over 0.8 s at 65); LIP's tight end is 0.78, not 0.85; FLUTE's bore cutoff is a multiple of the note (with a fixed bell a C4's 3rd mode took over above p 0.8); the jet delay is fractional (rounded, the LOOP's pitch correction oscillated); what is heard is the bore wave, not the valve's injected sample (which carried the mouth's DC pressure, and the classifier read 8 of the first 16 presets as a snare or a clap); the spec's pressure vibrato moved the pitch by under a cent and is now a real pitch vibrato; the breath noise was inaudible at the spec's values and is raised. The seam check that means something is the kept stretch against itself one loop later — the loop played twice is tautologically periodic and reported 0.00 on loops two whole periods off (the periodic loops' check, ARCO's still; BORE's blend-closed loop is judged by its join, since the seventh round). All 16 presets read PERC or LOOP by the classifier (two low SAX presets may read TONAL, also non-drum), none a drum. **Provisional, and not heard:** nothing in BORE has been listened to (this sandbox cannot play audio); the presets are authored from measurements, the constants marked "listening" (turbulence ranges, chiff burst, thump level, the reed's bell, the pop) are guesses, and the audition page is where a human says which is wrong. **The first listening note (SAX wants more reed bite) is answered by a presence bell on the SAX output** (2 kHz, +12 dB at a loose lip easing to 40% of that at a tight one; +5.5 dB of 1-4 kHz against the fundamental at the default knobs; four strengths on the audition page's first card; none heard); a second note (still needs more: "buzzier and raspier") added a soft-clip rasp after the pipe (`Bore.raspBend`, one-shots only, loose lip and hard breath; about 8 dB more of 1-4 kHz again; a LOOP carries it too since the fifth round); design doc "R1, the reed's bite" and "Round 1.2: the rasp", which also record that 2 of 117 SAX corners never closed as a LOOP even before it. A third round measured the SAX against a real tenor (UNSW recordings): the fundamental is the strongest partial in 1 of 13 real notes and ours was a fundamental with the rest far under it, so a voicing was added to the output (`Bore.voice`: the fundamental region cut, everything over 3 kHz lifted, opening with the note's loudness so the drum classifier does not read it as a snare; a LOOP took it whole, as a plain filter, in the fifth round and has the one-shot's since the seventh); design doc "Round 1.3: the voicing". A fourth round fixed the attack (0.34-0.45 s to 80% at C3 against a real tenor's 0.05-0.12 s): a tonal "tongue seed" at the note's own frequency goes into the bore at the tongue's release (`Bore.TONGUE_SEED`, scaled by CHIFF, BREATH and the reed's level, capped, reeds only; a LOOP is cut after the onset, where it lives), so a default note speaks in 0.10 s at C3 and 0.04 s at C5, CHIFF 0 is still the slow swell and a BREATH 0 note stays soft; the voicing's brightness now blooms after the level (`VOICE_BLOOM`, relative to the note's own ceiling, plateau unchanged) so the faster head window does not read as a snare to the classifier (HIGH STAB read 0.63 before it; the six one-shot SAX presets now read 0.16-0.44); design doc "Round 1.4: the attack"; the attack was heard and kept. A fifth round answered the kit card's "Solo loop sounds like a cheap keyboard": a LOOP now carries the rasp and the voicing (the voicing as a plain full-strength filter, since the one-shot's loudness-following gain cannot repeat across a wrap), and its pitch match is found at the join with a Hann-weighted fractional-lag search instead of a parabola over a quarter-second average, which read 0.3 ppm and was 0.05-0.2 frames out; a loop that did not close with a tenth of the bar to spare was settled twice as long (gone in the seventh round). On 125 SAX corners the median seam goes from 2.2e-5 to 1.4e-6 with the rasp and voicing in; four corners never closed, on the old code too (they render since the seventh round). The loop was brighter than its one-shot at low notes (13.8 against 6.2 dB of bite at C3: `LOOP_VOICE_SHARE`, gone; a loop cut from the one-shot has its bite), and the audition's LOOP section. Design doc "Round 1.5: the loop". **Heard (2026-10-04): still a cheap keyboard.** The owner says the one-shot of the same note sounds good and only the loops are bad (too steady or hissy, buzzy or thin). The loop is frozen: its steady stretch takes no noise and no vibrato, because noise in the bore moves the phase and the seam would not close (the one-shot's pitch wanders 5 cents and its between-harmonic energy is -42 dB; the loop's is 0.00 cents and -80 dB). A prototype with vibrato and noise added afterwards, fitted to the one-shot's numbers, was "still doesn't sound very good"; a prototype loop cut from the one-shot's own held note with a crossfaded join matches the one-shot's numbers and has no verdict. design doc "Round 1.6: the loop, heard". **A seventh round (2026-10-11) built the loop the way the owner's ear pointed:** cut from the held middle of a long one-shot (the one-shot's own path at HOLD 0.5, so it has the one-shot's vibrato, breath, rasp, bite and voicing plateau) starting no sooner than 1.5 s (a loose reed's level settles before its waveform: cut at 0.65 s, a C3 stretch correlated 0.62 with its continuation, and 0.989 from 1.5 s), its length searched within 0.12 s of two seconds for the best match between its head and the held note's continuation (10 or 11 vibrato cycles: 1.92 or 2.12 s), the head faded in from that continuation over 0.3 s by a power-constant law (a plain linear fade of waves that correlate 0.62 dipped the level 1.34 dB), rotated onto a zero crossing and levelled; no retry and no pitch ladder in BORE (the ladder stays for ARCO), and `LOOP_VOICE_SHARE` is gone. On 252 corners (125 SAX, 27 FLUTE and the 100-loop fuzz) every loop renders, the four SAX corners the periodic loop never closed among them; the worst join correlates 0.989 and dips 0.49 dB (0.984 and 0.46 on 819 more renders, none refused), the loop's pitch movement and breath match the one-shot's (6.06 against 6.04 cents and -33.8 against -36.0 dB between the harmonics at C3), its bite is the one-shot's to within 0.2 dB, and it renders in 0.37 s at the median. **Not heard.** The pitch is no longer exactly TUNE (within about 4 cents), the same breath and vibrato come round every 1.92 or 2.12 s, the breath, which differs between the two passes, dips about 3 dB across the 0.3 s blend (no guard reads it: the total level dips 0.5 dB at most), and R2's held keygroup, which assumed whole periods, would need them back. Design doc "Round 1.7: the loop, cut from the one-shot". Brightness across BREATH is still open. Known limits, disclosed: the high SAX notes may now speak slightly fast (0.04 s at C5 against a real 0.05-0.12 s; the roster of presets was laid out when low notes were slow and has not been re-laid out); FLUTE's LIP and BREATH move the timbre 4-8 dB. The phone (picker entry, README count, `→ SURFACE ▸`) is R1.1; held instrument and keys are R2 | S15 (SILK Phase 1a — `Strings.kt`) |
| S20 | **R1 built and heard once (the held-note gate passed); R1c retune heard once (BOW, the stab and the vibrato yes); BODY rebuilt in R1g (above the middle of the knob, a low lift on the plain's own gain) and put to a blind page that came back void by its own rules (R1h); the owner said merge it; R1j put C2 and F#2 (two of the nine capped notes) under the lift to a second blind page and the owner could not tell the more-bell split from the lift as built, so C2 to G#2 stay as built** — ARCO, the bowed string (CELLO and ERHU): a bow dragging a string, where every other sustaining engine here is a valve in a pipe or an oscillator under an envelope. The string is `Strings.Bow` (the two-segment string with the bow's friction junction, S15's toolkit); `Arco.kt` is the player. Five macros: TUNE, BOW (the stroke, from a 400 ms slow bow to a 10 ms stab with a bite), GRIP (the bow's pressure and the bridge's brightness on one axis, drawn per voice at every TUNE step), BODY (up to the middle of the knob, how loud the box rings, cut where the stopped string ends; above the middle the box stays at its middle size and a low shelf and a broad bell are added on the note at the plain's own gain, 3.5 dB each at 0.75 and 6.0 dB each at 1, backed off per note only where the 0.95 peak bar leaves no room, nothing turned down at the top) and HOLD (how long the bow stays on, 0.3 to 4 s, whose top step is a seamless two-second LOOP built with BORE's loop machinery: whole periods in whole frames, the played pitch corrected until they fit, a render that cannot close throws). A vibrato of about ten cents at 6.1 Hz is baked into the note and scaled by how long it is held (none on a stab, none in a LOOP; ERHU's is a plain sine, CELLO's drifts in rate and depth and swells in over half a second), and the bow's lift is followed by a stop, so a note ends the way a hand ends it. Dry, mono, no seed and no noise: a bow needs none, so a recipe regenerates bit for bit. CELLO runs C2 to C4; ERHU runs D4 to A5, 19 semitones and not 24, because from the 24th semitone (D6) no pressure the grip can reach slips once a period; B5, 21 semitones, is the last step that does, and ERHU stops two short of it at A5. `SynthKits.arco()` is the kit (CELLO walking the pentatonic as stabs, six ERHU presets, a LOOP for each voice), with `generateArcoKit` and `generateArcoAudition`; registered through `Patches`/`Presets`/`Velocity`. **The pass rule, set by the owner:** the engine passes or fails on a three-second held note, bowed against a synth, not on a one-second stab. The audition page's first card plays, for each voice at three pitches, three clips of three seconds each with no labels (CELLO: bowed, plucked, a sustaining synth; ERHU: bowed, sung, a sustaining synth), and the engine fails if the bowed note cannot be picked out from the synth or itself sounds like a synth; the one-second stab check in a two-bar pattern at 90 bpm comes second. **Heard once (2026-10-01):** the owner picked the bowed note out of three unlabelled three-second clips six times out of six (CELLO and ERHU at three pitches each; sure on four, unsure on ERHU C5, no confidence set on CELLO A#3), so both voices pass the held-note gate and their presets stand. The stab was a yes at BOW 1 and a nearly at BOW 0.5; GRIP 1 and HOLD 1 (both voices), ERHU BOW 1 and ERHU's vibrato were yes; BODY 1 (both voices), CELLO BOW 1 and CELLO's vibrato were nearly, and the owner said why: not enough box, not enough bite, too mechanical. **R1c (2026-10-02)** retuned the three: CELLO's bite (3.0 times the velocity, a 60 ms time constant, half of its share pressing the string: `OVERSHOOT_MAX_CELLO`, `BITE_SECONDS_CELLO`, `BITE_PRESSURE_CELLO`), CELLO's vibrato (it drifts in rate and depth and swells in over half a second: `VIBRATO_HUMAN`; ERHU keeps the plain sine) and BODY (a box climbing to 1.75 times the string, `BODY_TOP`, since retired); ERHU MOON FIDDLE's BODY went 0.85 to 0.8 and CELLO GRIT BOW's BOW 0.85 to 0.65. **Not unchanged:** the bigger bite changes the BOW 1 stab the owner marked YES (E-flat3 of its figure is now a scrape). **R1c heard (2026-10-02):** the bite, the stab's three hits (E-flat3's scrape included) and the vibrato yes; BODY 1 at 1.75 times the string still "doesn't seem to do anything" and "seems a little light". **R1d heard (2026-10-03, headphones):** both hidden repeats SAME, all thirteen new clips DIFFERENT, none MORE BOX, "dull and muffled rather than full and resonant" (every candidate cut the top above 3 kHz by 7 to 20 dB because the page levelled every clip); the owner asked for warmth and weight and said louder is fine. **R1e heard (2026-10-03):** the warmth page (a low shelf and a broad bell on the string at the plain's own gain): "Very hard to tell", the overall questions blank, FULLER on 7 of 18 chips, all at +3.2 dB rms or more over the plain (ERHU's flat louder-only clip among them). **R1f (2026-10-04):** GYRE's BODY ("meh" and "All sounds the same", then a box and "Yes better") swung a low band 21 to 24 dB against ARCO's 1 to 3 dB (measured, first 0.4 s, both level-matched), and ARCO's finish took 5 dB back off R1c's box (rms +5.2 dB at the plain's gain, +0.0 to +0.2 after); the owner chose "Louder and fuller, treble untouched". **R1g built (2026-10-04):** BODY at and under 0.5 is R1c's render to the sample (a frozen copy of R1c's finish, 0 differing samples); above it the box stays at its 0.5 size and BODY adds the lift (0.78 dB per element at 0.55, 3.5 at 0.75, 6.0 at 1; CELLO shelf 200 Hz and bell 300 Hz, ERHU 600 and 800 Hz) at the plain's own gain through `Arco.finished`, the one finish, backed off per note to a finished peak of 0.94 (`PEAK_CAP`; the 0.95 bar unchanged). Measured: 99 prediction numbers compared, 0 missed; rms rise over the plain +3.48 to +4.03 dB at 0.75 and +6.15 to +7.03 at 1 where nothing is capped, never a fall; the 3 to 8 kHz band never below the plain on the ten grid notes at 0.75 and 1; C2 to G#2 capped (C2 +3.02 dB rms, one sound from BODY 0.70 up); the nine presets above the knee +0.8 to +6.5 dB louder, every roster bar kept; the kit regenerated (A09, A10, A12, A13 and A14 changed with the engine; A15 was already stale at the base and the lift moves it too; A16 was already stale at the base and its sound is unchanged). **R1h heard (2026-10-04, headphones):** a blind page of 24 clips; by the pass rules written before the owner listened **the page is VOID** (the two identical C2 clips drew SAME and FULLER, the top-cut control (the plain through a high shelf, re-levelled) was called FULLER, BODY 0.9 was ranked fullest above BODY 1.0), and the record does not argue it. With that caveat: both hidden repeats SAME; BODY 1.0 FULLER at C3 (ten plays), C5 and A5, SAME at C2; nothing at BODY 0.75 or above DULLER or TOO MUCH; the louder-only twins DIFFERENT at C3, C5 and A5 (SAME at C2), never FULLER; R1c's levelled way DULLER (one play each); in each forced pair one order picked BODY 1 and the other said can't tell; BODY 1.0 kept at the top of the knob for both voices. The owner: "Merge it, record the result, fix C2 later." The owner merged #445 on 2026-10-04 (14:22 UTC); the kit regeneration and this row were not in it. **R1j (2026-10-06 to 2026-10-13):** C2 to G#2 hit the 0.94 peak cap before BODY 1; asked in plain text how to fix them, the owner replied "More bell", then chose "Listen first (Recommended)" when the scout showed the two rulers disagree (at the same peak, against the lift as built at C2, bell only is +0.85 dB A-weighted and -1.04 dB rms, shelf only +1.52 dB rms and -1.02 dB A-weighted). A short page played C2 and F#2 as the plain, the lift as built, more bell, bell only and more shelf (15 pair questions: seven comparisons each asked in both orders, and one hidden identical pair); review rewrote the reading rules before anyone listened (the first ones would have built on 42.6 percent of pages answered by guessing, the final ones on 1.34 percent). **R1j heard (2026-10-12, earbuds):** "I CAN'T TELL" on 11 of 15 pairs and "B SOUNDS FULLER" on four (the lift as built over the plain at C2, more shelf at C2 and F#2, bell only at F#2), more bell not chosen over the lift as built in any of its four answers; by the rules written beforehand nothing is built, and the owner chose "Record it and leave C2 (Recommended)" (2026-10-13): the engine is unchanged. **Still not heard:** the presets, the kit (regenerated, unheard), the loops, the low end of each knob, the stab against the two brass stabs, whether the bowed note still reads as a synth once you know it is bowed (the second half of the pass rule), C2 to about G#2 beyond C2 and F#2 (those two were played on R1j's one earbuds listen, F#2 earlier on R1e and C2 on the void R1h page; C#2 to F2, G2 and G#2 were never played), and ERHU above C5 beyond the three A5 clips on the void page (one play each; no BODY page has played any other ERHU note above C5, so that is inconclusive listening at A5 and none elsewhere); the builders did not try the page on Safari, iOS or a real phone, and which phone and browser the owner's runs used were not recorded (R1j's device answer was "earbuds"). The constants marked "listening" in `Arco.kt` (`OVERSHOOT_FROM`, the bite's three numbers, `LIFT_HALF_DB`, `LIFT_TOP_DB`, the lift's corners, `PEAK_CAP`, the CELLO box and ERHU's `ERHU_BODY_ANCHOR_HZ` and `ERHU_BODY_T60_SCALE`, the vibrato's `VIBRATO_*` and shapes) remain guesses the owner's ears are setting | S15 (`Strings.kt`), S19 (BORE's loop machinery) |
| S21 | **R2 built, awaiting the gate (TOUCH and a bow; DRAWN and BOURDON); R0 and R1 built; R1b (BODY and SPIN rebuilt after the first listen) passed the second; R1c (HOLD refitted from choked to open) passed the third: every round-one knob has passed the audition gate** — GYRE, coupled strings and a rotor (FLICK, HALO, and from R2 DRAWN and BOURDON): four plucked strings at 1, 2, 3 and 4 times the note on one bridge, whose membrane (`Strings.Membrane`, an RBJ bandpass bank in Double with non-negative weights summing to at most 1) couples them passively through `Strings.Bridge` (`I − (2c/N)·H·1·1ᵀ`, R0), so a plucked string sets the others ringing (FLICK's third string answers at −26.4 dB, HALO's at −26.9; exactly 0 with the bridge off); eight sympathetic strings that listen to the bridge; a box that BODY sizes, from small, thin and nasal to large, hollow and warm, which also loads the strings (BODY 0 to 1 moves the octave bands 8.1 to 14.4 dB; it moved 0.6 to 4.1); and a rotor, at least 0.8 Hz, that moves which upper string is loudest, the box's peaks, the coupling, the membrane's weights, the strings' loss and which sympathetic string answers, never the level, with the strings retuned at every step so it never bends the pitch (a 164 Hz centroid swing at 2.5 Hz against 0.4 Hz for a tremolo of the same depth; a short FLICK note at SPIN 0.25 swings its partials 11 dB). TUNE · SYMPATHY · SPIN · BODY · HOLD; TENSION is part of each voice, TOUCH and the bow are R2. Each string is pre-tuned by the bridge's own phase, with a wolf guard where a membrane mode sits on the note, for pitch and for how fast the fundamental may drain (worst 3.4 cents over 25 notes × BODY × SYMPATHY); the strings' loop filters stop at 5 kHz (FLICK's A4 read as a snare; worst share over 2 kHz 0.41, no drum class anywhere); SYMPATHY's sympathetic share lands at −22.4, −12.5, −8.4 and −5.3 dB at 0.3, 0.6, 0.8 and 1; HOLD is when a hand lands on the instrument (the played strings to a 0.15 s t60, the sympathetic strings to a release that SYMPATHY lengthens, up to a 2.5 s t60) and a note renders until both are 45 dB down (the quietest ending 55.1 dB under its attack; before, a short sympathetic note was cut 14 dB down); nothing ever grows past its attack across every macro at both ends. HOLD runs from a choke (the hand 0.04 s after the pluck) to an open note (the hand once the note has rung out, `0.55 + 2.75 × SYMPATHY²` s), every step landing about 10 dB lower than the last (round one's FLICK hand landed 50 dB down at HOLD 0.5 and 108 dB down at 0.95). Renders 88 ms (FLICK, 0.88 s) and 395 ms (HALO, 3.34 s) warmed on a desktop JVM. `generateGyreAudition` was the gate (verdicts under `gyre_r1_*`, `gyre_r1b_*`, `gyre_r1c_*`). **R2 (a bow on the strings), built, round-two audition awaiting the listen:** every string is a `Strings.Bow` with a bridge port (R2a, #449: no audio change, proven sample for sample against a frozen bow) and TOUCH lowers a bow onto them (the pluck falls as cos, the contact rises as the fourth power of sin, the bow takes over around TOUCH 0.3 to 0.45), so TOUCH 0 is round one measured against a frozen copy (pitch within 0.00 cents, waveform −50 to −58 dB, bands 0.01 to 0.12 dB). HOLD on a bowed note is the stroke (FLICK 3 s, HALO 6 s, DRAWN 4 s, BOURDON 6 s); SPIN also eases how hard the upper strings are bowed (0.35). Two new voices start bowed, from the document's table: DRAWN (C3, TOUCH .85) and BOURDON (C2, TOUCH .9). A bowed note is measured onto its key (7 to 18 cents off at BODY 0.5 to 1 before; after, at TOUCH 1 at most 4.6 on FLICK, HALO and DRAWN and 9.8 at TOUCH 0.5, the catch; BOURDON's lowest notes at BODY 1 sit on a membrane mode: 15.3 on the test's grid, 24.4 at D2 and F2 in a full scan); the drawn note's raw level is trimmed 15 dB (inaudible: every render is levelled) and the sympathetic strings 3.5 to 8.5 dB on FLICK, HALO and DRAWN (BOURDON's +0.5 to −4.5 dB: it runs cold) so SYMPATHY's shares hold (−22.9, −12.3, −7.7, −4.9 dB at TOUCH 1). Bounded over 30 seconds at every extreme (raw peak 0.57 to 0.87; a pluck never grows past its attack, a drawn note holds its level, BOURDON's drone builds 3 dB). The round-one figures above (88 and 395 ms, 55.1 dB under the attack) are round one's. Written down, not fixed: just over the bow's catch it is raucous and the pitch detector can read a higher partial (never a lower one); a caught bow is 14 to 26 dB over the pluck's attack, so the middle of TOUCH is a bowed note with a pluck's tick. Renders 78 ms (FLICK, 0.88 s) to 794 ms (BOURDON, 6.56 s) warmed. 86 clips under `verdicts/gyre_r2_*`. Plan `docs/superpowers/plans/2026-10-04-gyre-round-2.md`; "R2, as built" in the GYRE spec. Design and measurements: `docs/superpowers/specs/2026-10-01-gyre-coupled-string-engine-design.md`; plan `docs/superpowers/plans/2026-10-01-gyre-round-1.md` | S15 (`Strings`) + ARCO R1a (`Strings.Bow`, for R2) |
| S22 | **R1 built, gate passed (round 4, 2026-10-04); R1.1's picker entry landed 2026-10-23 with PING, SING and BLADE (EDDY, VESSEL and SHARD follow round 7's listen); the app's MAKE INSTRUMENT path is still for a desktop session with the SDK; R2a (the LOOP) passed round 5; R2b (held keys) merged (#452), round 6 awaiting the listen; R2c (EDDY, VESSEL, SHARD) built, round 7 awaiting the listen:** round 1 kept 144 of 166 clips and cut none; round 2 kept the WATER fix and PING's velocity — MERCURY, a rubbed, bent, water-loaded modal object (PING, SING and BLADE): one imaginary resonant body, part glass and part bent steel, that can be tapped or rubbed through the same contact, where every other sustaining engine here is a string, a pipe or an oscillator. The body is `Modes.Bank` (its R0: a drivable, retunable, reciprocally coupled modal bank, stable by two enforced bounds) and `Modes.Friction` (a friction curve solved implicitly against the speed it moves); `Mercury.kt` is the player. 12 primary modes (Rayleigh's thin ring for PING and SING, the free-free beam for BLADE) and 4 vessel modes 3.5-7 % from their partners, so COUPLE trades energy rather than adding gain. Seven macros: TUNE, BEND (a gesture into the note from up to two semitones away, and a deformation of the upper modes), RUB (a tap at 0, a sustained rub at 1, along one contact; the tap has a 0.2 floor so a rub always lands), WATER (one moving mass loading every mode together, rising and falling at 0.5–2 Hz in each mode's own phase, and moving what each mode radiates; depth as WATER^0.25, so 0.05 adds 10–21 cents of drift and a 1.6–2.6 dB swell. Round 1's √WATER fluttered and was not heard on SING or BLADE), GLASS (damping, its slope up the modes, the pickup's tilt and the contact's roughness), COUPLE (spring strength; the coupled note is put back on pitch once per note by an eigen-solve) and HOLD (the contact's length, 0.3 to 4 s, then the object rings out for half its fundamental's t60, 0.4 to 2.5 s, released on a raised cosine). Below HOLD's top step every render is a one-shot (HOLD's top step is the LOOP, R2a below); at the default GLASS a one-shot runs past the classifier's 1.5 s line and is filed LOOP by length. Dry and mono; the strike's burst, the contact's grain and the water's start are seeded from the voice and the note. PING runs C4 to C6, SING and BLADE G3 to G5. `SynthKits.mercury()` is the kit (PING up the pentatonic, four SING and four BLADE presets), with `generateMercuryKit` and `generateMercuryAudition`; registered through `Patches`/`Presets`/`Velocity` (velocity moves GLASS on PING, proven by an onset-centroid sweep and kept by ear; on SING and BLADE velocity is the touch, a render parameter: a soft rub swells in, up to 31 times slower, and a hard one catches with a 40 ms scrape under 1.5 kHz that rides the note's own swell, at least 14 dB under it; round 3's, levelled against the held tone, led the note's start and was heard as a clap). Design and the Phase-0 record in `docs/superpowers/specs/2026-10-01-mercury-modal-glass-engine-design.md` and `docs/superpowers/plans/2026-10-01-mercury-phase-0-spike.md`. **Heard and kept (R1):** round 1 kept 144 of 166 clips; round 2 kept the WATER fix and PING's velocity; round 3's scrape was heard as a clap and round 4's, riding under the note, was kept; R1's gate passed at round 4 (2026-10-04). **R2a built, round 5 heard and approved (2026-10-04):** HOLD's top step is a seamless LOOP (whole periods and whole WATER orbits, retuned until it closes, seam under 1e-3; no roughness in a loop; RUB at least .6 so PING rubs; the hard corner, a low note at low GLASS with high COUPLE or WATER, nudged toward GLASS .6 and COUPLE .5, the owner's choice); every preset at every TUNE closes as asked. **R2b built, round 6 awaiting the listen:** `Keys.mercuryPad` (nine zones every minor third from each voice's own root, HOLD forced to its top step, the loop doubled with the marker at the second copy, cancellable), `MercuryPadMaker` in the shell (RELEASE alone, SIREN's shape) and three instruments, SnipSnap Mercury Ping, Sing and Blade, in `testkit/Instruments` with the sidecar and the zip; in tune within 0.29 cents with WATER still (the ends and middle of each voice) and within 3.1 cents on average at the defaults, all 27 zones within 0.00 dB of the melodic level, with a played wrap that steps no more than the loop itself does. **R2c built (2026-10-04), round 7 awaiting the listen:** three voices more on one `Geometry` per object, the three shipped voices unchanged to the bit (a hash of 39 renders before and after, and golden values kept in `MercuryTest`): **EDDY** (a singing bowl, six measured doublets, A2 to A4), **VESSEL** (a thin steel shell whose table is derived and recomputed in a test, each vessel the quadrature partner of a primary, A2 to A4) and **SHARD** (a free plate off square, a converged Ritz table, C4 to C6), the owner's defaults for the five sound macros, 24 presets, struck velocity on VESSEL and SHARD (GLASS) and a touch on EDDY, a second 16-pad kit (`SnipSnap Mercury Kit 2`), the defaults' loop closing un-nudged at all 25 keys of each new voice (a committed sweep), WATER .05 audible on all three. Their held instruments follow the listen | S20 (ARCO's friction precedent), R0 `Modes.Bank` |
| S23 | **R1 built, gate passed (items 2 and 3), PICK map built, the R1 PR pending** — MAGNET, the electric string (JANGLE, single coils on the low E, and CHUG, humbuckers on the low B), and VALVE, its amp on any pad. MAGNET is `Strings.pluck` (pick position 0.085, trimmed to its decay between 0.25 and 4 s, then ended on its own fade: cubed over the last 150 ms on the decay path, an extra fourth power over 225 ms on the ring-ceiling path; JANGLE's loop damping is pitch-compensated, `fb^(1/r)` and a loop corner times `sqrt r` for a note r times the open string, so E4 rings as long as E2 instead of a third of it) read through R0's `Strings.pickup`, Jaffe and Smith's position comb on the string's output: a neck group at 0.42 and a bridge group at 0.12 of the string, BLEND weighting them linearly, CHUG's humbucker an aligned coil pair, then a per-voice pickup resonance and the melodic output chain. Four macros: TUNE (two octaves snapped from the open string), MUTE (a palm mute: the loop's brightness and feedback together, so the note shortens and darkens as one), PICK (a thumb to a wire: the exciter's corner 150 to 16 000 Hz, a second exciter pole below the default and the loop's and the pickup resonance's corners opening above it, all pinned so the map is neutral at the default) and BLEND (the pickup selector as a knob). The engine renders dry: `Magnet.landingChain(voice)` lands a pad through VALVE by recipe (CHUG at DRIVE 0.85, gain 106, SAG 0.4, TONE 0.3, CAB 0.95; JANGLE through VALVE's default amp, DRIVE 0.70, SAG 0.35, TONE 0.50, CAB 0.60: CHUG's gain the owner's pick by ear at the gate, JANGLE's amp the one the owner picked with the long ring, adopted by the controller), so every MAGNET pad carries a `valve` section the user can turn or remove. VALVE is the rack's amp section, built and heard as V1 and V1.1 (DRIVE up to a gain of 1000, SAG a supply sag, TONE after the tube, CAB from no cabinet to a closed wall, run at 4× inside; the `amped` character); its design and amendments are in the same spec ("V1.1 amendments"). PICK is registered as the velocity macro: its 1 % per tenth clause holds at every tenth on both voices at the defaults (smallest step 16.82 % on JANGLE, 13.85 % on CHUG). Registered through `Patches` and `Velocity` with canaries in `DeterminismTest`/`PadRecipeTest`/`SynthKitTest`/`VelocityGrooveShuffleTest`; there is no `Presets` roster and no preset. `SynthKits.magnet()` and `testkit/SnipSnap Magnet Kit` (16 pads, 6.01 MiB, regenerated for the owner's picks), `generateMagnetKit` and `generateMagnetAudition` (95 clips); blocklist terms for an amplifier line and a speaker maker in `PresetTestSupport.trademarkBlocklist`. Design and its R1 measurements: `docs/superpowers/specs/2026-09-29-magnet-valve-design.md` ("R1, as built"). **Measured on the built engine:** in tune across 1 350 cells (450 at each of PICK 0, the default and 1), worst 1.66 cents (the specification's engine was flat by up to 69); the single-coil comb 46.4 dB (h2) and 47.0 dB (h4) under their neighbours at position 0.5 and 37.5 dB (h4) at 0.25, 6 to 10 dB shallower than the spike's, cause not isolated; CHUG's aligned humbucker pair 34.1 dB under the single coil at h18 against 1.4 dB for the unaligned sum; BLEND swinging the spectrum 18.0 dB (JANGLE, h5) and 14.7 dB (CHUG, h2); MUTE 1 rendering 0.241 (JANGLE) and 0.165 (CHUG) of MUTE 0's length; the pitch through the landing amp within 0.20 cents of the dry read over PICK 0, the default and 1 (0.08 at the default); JANGLE's fundamental losing 7.7, 7.8 and 8.1 dB per second at E2, E3 and E4 (7.7, 15.6 and 32.4 before the ring law) and E4's t-20 1.20 s (0.32 s before it); a landed note ending at least 55 dB under its peak at each landing's own BLEND, at CHUG's landing, the kit's lead amp and JANGLE's landing across MUTE 0, the default and 1 and PICK 0, 0.15, the default and 1 (worst 55.8 dB, CHUG at gain 106, MUTE 0, PICK 0.15), and at least 54.6 dB across the 16 worst cells of each landing re-read at BLEND 0 and 1 (worst 54.6 dB, CHUG's landing, TUNE step 4, MUTE 1, PICK 0.55, BLEND 0; the bar is 53). **What R1 changed against its own plan, measured:** PICK is registered as the velocity macro (the plan expected the top tenths to fail the 1 % clause and printed it only); the DC bound is 1e-4, about -80 dBFS, with two direct tests of the DC stage (the corners read at most 8.38e-10); the audition writes 95 clips, not 47, because the spec's PICK defaults are 0.94 and 0.48 octave darker than the spike's and the as-played chug stabs differ 1.98 s against 0.51 s in length (a length-matched pair built from CHUG at MUTE 1.00, a decay-matched saw pair and a symmetric pair with both stabs cut to the same length are added); a string ends on its own fade before the amp (cubed 150 ms where the trim ended it on its decay, an extra fourth power over 225 ms where the ring ceiling cut it), chosen by a grid across MUTE, PICK and the three landings, which leaves every landed kit pad ending at least 66 dB under its peak (the regenerated kit's ring-ceiling pads end in digital silence). **Heard:** the gate (2026-10-01): item 2 passes clean (the guitar picked by its tone on the cut-matched and both-cut pairs, "night and day"), item 3 (blind identity, JANGLE) passed once the amp was examined (guitar at E2 and E3, harp at E4), and the owner picked gain 106 for CHUG; the second listen (2026-10-01/02): every E4 clip without the long ring was called a harp and every clip with it (law L2) a guitar, the owner picked VALVE's default amp with the long ring (and called the amps with the old ring "none of them"; the controller adopted it for JANGLE), and passed JANGLE, and picked the PICK ends (the spike's candidate that opens both, built as ruling 23); the roots, the resonances, the pick position and the lead chain are still *shape*. **The gate:** both must-pass items (2, CHUG, and 3, JANGLE) passed, so neither voice is dropped. **Remaining:** the owner's call on the R1 PR; presets (eight per voice, by ear, after the gate); R1.1, the phone (picker entry and its arms, the chip decision, README's engine count); R2 (MAKE INSTRUMENT as a decaying keygroup with PICK layers, then pitch droop, fret buzz, sympathetic strings and pick scrape, each behind its own listen) | S15 (SILK Phase 1a — `Strings.kt`), MAGNET R0 (`Strings.pickup`), VALVE V1/V1.1 |
| S24 | **Water and control revisions built; listening verdict pending** — FLOTILLA is in the phone picker. R1 feedback identified static across voices and weak control separation. The water revision darkens and bounds splash texture and balances held hulls. The 23 October follow-up retains indexed hull identities across FLOTILLA/VESSEL, fades newly engaged hulls into the mechanics, and excites both endpoints of physical pair contacts under one excitation budget. Per-hull water streams avoid global splash-count reseeding. Six voices, seven macros, fourteen presets and `SynthKits.flotilla()` retain their saved formats; regenerated audio intentionally changes. HOLD remains stationary repeated forcing with carried resonator/filter state. Design: `docs/superpowers/specs/2026-10-04-flotilla-engine-design.md`. | S1 |
| S25 | **Revised voice built; app integrated; listening verdict pending** — TREMOR exposes HIDE, UNISON, ROLL, WIRE, CHARGE and FRACTURE with eight macros and a dry FRESH TAPE starter. R1 was heard as a damped drum plus squeal; the revision calms circuit feedback, exposes real bead contacts and passive cage tone, lengthens the hide body, and closes output tails. The 23 October articulation follow-up broadens soft-touch contact duration and modal footprint while retaining full-touch samples; it acts on both initial and held micro-strikes. HOLD at .5 returns a loop; held actuator feedback remains open, and the copied seam check verifies its constructed join rather than natural convergence. Factory filing intentionally overrides generic import classification for SETTLING BED and CHARGED TAIL. Design: `docs/superpowers/specs/2026-10-04-tremor-engine-design.md`. | S22 (`Modes.Bank`) + S15 (`Strings.Loop`) |
| S26 | **PVC voice and factory recipes accepted; app integrated** — AEROSTAT now exposes the approved `052bf946` voicing and roster in the phone picker, preset desk and FRESH TAPE starter. The listener selected every revised factory-note and common-C3 comparison. One FLOAT voice, both banks, seven macros and native strike velocity retain their patch format. HOLD at .99 returns the settled whistle loop without an opening strike; STRIKE, INERTIA and RELEASE do not change that equilibrium buffer. Factory names remain SOFT CATCH, TWIN PIPES, HEAVY ROTOR, DRIFTING STEAM and HIGH ENVELOPE. The original feature-branch approval had not reached default; this integration carries those exact DSP/recipe files across. Design: `docs/superpowers/specs/2026-10-04-aerostat-engine-design.md`. | S1 |
| S27 | **Port built; C4 default and one-minute ceremony approved; playable ROOT mechanically verified, listening pending** — CIRCUIT is an invented moving ensemble of three pressure/lip-driven breath resonators, stone rattle, paired wooden clapper, clay vessel and synthesized grunt/uh-huh gestures. ROOT, PROCESSION, ANSWER, VOICED, EXPANSE and CONFLUENCE share TUNE (C2–C4, default C4, neutral C3), BREATH, DIAMETER, ORBIT, PACE, CANYON and HOLD. The owner approved the merged C4 starting note and continuous ROOT minute (“Sounds good”); the minute remains an offline authored performance. The current candidate brings call → listening → causal answer → settle into ordinary ROOT finite and held rendering, using shared input pressure, selected role omissions and paired held call/listening cells without an output gate. Actual acoustic arrivals, occupancy, reply depth and a finite energy budget still govern answers. Seven unchanged comparison cases, including the five other voice defaults, EXPANSE held material and the approved minute, are bit-identical to their baselines. Fifteen dry presets now include five ROOT settings: FIRE CIRCLE (soft close pressure and moderate space), NIGHT REPLY (wider, longer finite returns) and EMBER PULSE (settled recurring pulse) join the original two. All three start at C4; all twelve previous preset names, maps and relative order, including lower explicit notes, and the sixteen-pad SynthKits.circuit() kit are retained. Existing app controls, patch/preset/velocity registration and PadRecipe formats expose the additions without a new macro or host API. HOLD ≥ .99 returns a settled loop without an opening gesture, with compatible clocks and state-convergence checks. generateCircuitAudition retains the ordinary raw/matched matrix and diagnostics; generateCircuitCeremony supplies the separate continuous minute. **The new ROOT score and three presets are candidates, not an extension of the minute’s listening approval.** Historical R4 evidence remains 370 variants/740 WAVs, 360 classifier guards, zero numerical recoveries, eleven closed held loops, 39 unique checks (28 focused plus eleven additional), and a debug APK verified against 534 byte-identical build inputs. R4/R5/R6 recordings and measurements are revision-specific snapshots, not validation of this candidate. Runtime 1cd56a1bc759dfcdd825c242ed1ad1d80d042b50 passed 90 native render guards, 209/209 independent artifact checks and 36/36 transition checks; held listening cells 1/3/5 have accepted causal replies. The unchanged EXPANSE held desktop median improved from 12.336657 s to 11.185123 s (9.334% lower) with unchanged render-thread allocation, using one warmup and three sequential timed runs in each separate 1 GiB JVM. Android assembleDebug and compileDebugAndroidTestKotlin passed; the APK source binding, signature, engine classes and four native ABIs were verified against that exact runtime. The full nine-module JVM suite passed all 5,922 tests with zero failures, errors or skips. New musical acceptance, physical-phone performance and Circuit-specific MPC validation remain pending. Models, history, design rationale and current validation status: [CIRCUIT.md](CIRCUIT.md). | S1 + existing patch, rack and kit pipeline |
| S28 | **R1 port built, listening pending** — CISTERN, a pitched membrane under a finite suspended droplet field (FIRST, DRIP, CASCADE, POOL, RIPPLE, RECOVERY). STRIKE, SUSPENSION, DROP, SKIN, DRAIN and HOLD; note is explicit MIDI 36–84, velocity is event energy. Releases have causal lineage and finite travel; landings excite the same surface, accumulate regional liquid, and can trigger further drops. DRAIN changes modal loading and contact response as the surface recovers. HOLD supplies powered liquid return and maintenance, and returns the settled loop buffer without an initiating strike. Twelve provisional presets, `SynthKits.cistern()` (its first eight pads cover all six profiles rather than transposing one voice), patch/recipe/velocity registration, CLI note and velocity overrides, picker and starter-kit entry. `generateCisternAudition` writes raw and matched-loudness pairs, all voices at C2/C4/C6 and three velocities, all controls at 0/.25/.5/.75/1 with neutral companions, five 3×3 interactions and diagnostic event/liquid traces. The supplied design is preserved with implementation notes at `docs/superpowers/specs/2026-10-05-cistern-engine-design.md`. The imagined holding field, modal ratios, thresholds and preset names remain design choices awaiting listening; numeric checks do not establish the sonic identity or release naming | S1 + shared modal/render utilities |
| S28 | **R1 implemented, awaiting the audition** — COROLLA, a mechanical flower of coupled metal petals (TONGUE, BLOSSOM, CHOIR, CHATTER, ORBIT, HUSK). TUNE spans C3–C5; PULL, BLOOM, FIELD, CONTACT and CHAMBER control the shared physical model. HOLD at 0.99 returns settled sustain material: the initial opening gesture belongs to the finite render, because the current sample API has no attack-plus-loop region. Velocity is event strength on the render. Twelve complete presets, two held; dry `SynthKits.corolla()` has eight TONGUE scale notes and eight contrasts. Registered through `Patches`, `Presets`, `Velocity` and the Android engine picker; the generic CLI can audition presets. Finite renders route as TONAL for IN KEY and pad tuning; HOLD alone selects LOOP. This engine metadata overrides the generic classifier's length-based LOOP bucket; neither class receives a drum choke group. `generateCorollaAudition` is the listening gate. Design: `docs/superpowers/specs/2026-10-05-corolla-engine-design.md`. The modal ratios, coupling, powered mechanism and preset settings remain provisional until heard | S22 (`Modes.Bank`) |
| S30 | **Stage 0 (revised) built; Stage 1 (the WAH section) passed its owner gate on 2026-10-23, with a chip for each of its three moves (WAH, TOUCH, ROCK); COMP did not pass the same listen (too little lift at AMT 0.7), so Stage 2 followed: the COMPRESSOR section, prototyped, gated in two rounds and built on 2026-10-24, the chip renamed SUSTAIN (a snare rings 249 ms at AMT 70 against 84 dry and 124 under the old SQUASH recipe); DELAY (ECHO's HEAD) built, its gate answered, a confirm listen pending** — the pedal board's first stage as the owner's two listens left it (the first found DRIVE and COMP not enough and the wah no wah; the second picked stronger candidates): a seventh pad-sheet row, PEDALS, with two recipe chips over sections the rack already has, no new rack section. DRIVE is VALVE at the top of its gain law (DRIVE 1.0, SAG 0.15, CAB 0.08: 36 percent harmonic distortion on a steady 220 Hz sine at the pad sheet's AMT 0.7 and 38 at AMT 1) with a pre-amp end fade, because VALVE lifts a quiet tail by its own gain and the spike measured the last 20 ms of a snare 19.8 dB under its peak at AMT 1 without one, and the shipped chain's own test 9.1 (the bar is 50): a power-shaped fade of 95 ms at power 5 at full strength, its length scaled by the amp's lift at the AMT over its lift at full (about a third of that length at the pad sheet's AMT 0.7, none where the amp does not lift), never reaching the first half of a pad, chosen from a declared grid (30 to 120 ms, powers 2 to 7) against the spec's bars (53 dB under the peak, the spec's 50 with 3 dB of margin, and a DC mean under 1e-4) at AMT 0.7, 0.8, 0.9 and 1 on a snare and a choir, with no claim of an optimum. Known edges of the fade, disclosed and not fixed: it is capped at half a pad and is a third as long at AMT 0.7, so a pad shorter than about 200 to 250 ms (a hat, a rim, a click, a trimmed hit) still ends hot through gain 1000, a deliberate trade to keep a short pad's body (a snare trimmed to 100 ms ends 24.6 dB under its peak at AMT 0.7 and 7.9 at AMT 1; the end tests cover a 333 ms snare and a 1.44 s choir); the fade lives in `Treatments.apply`, the door of the chip, of the replay of a character and of the CLI `treat`, so a BREED child, which renders an fx-only recipe through the bare chain, inherits a driven parent's DRIVE unfaded (a fix would route the fade through `PadRecipe.process` or into the chain and waits for a listen to find it; Stage 3's design must decide where the fade lives); and a pad whose loudest point lies inside the faded span (a reversed hit, a swell) is untested, because VALVE normalises to its input's peak. COMP is the strongest SQUASH there is (AMOUNT 1.0, ATTACK 0.05), interim: at AMT 0.7 it lifts a tail over its body by only 2.0 to 5.7 dB, SQUASH's own ceiling, and the COMPRESSOR section replaces it. The WAH chip is held back for its own section (the quack recipe was never a wah), and that section is now built and awaiting its owner gate: `wah`, a resonant band-pass (a time-varying `TptSvf`, linear, so nothing to oversample) swept exponentially between a heel of 280 Hz and a toe of 2800 Hz by three moves held as weights, not a switch (TOUCH follows the pad's own envelope, AUTO is a sine with a flat top and the owner's round-two pick, ROCK is one raised-cosine gesture), with SPEED and Q, after CONTOUR and before SQUASH in the rack; reach is 1.5 times the weights capped at 1, so the `wahed` character at the pad sheet's default AMT 0.7 is full strength and AMT 0.5 gives 0.75, and the same stretch holds the position at the toe, so AUTO rests there for 39 percent of each cycle at AMT 100 and 14 percent at AMT 70 and ROCK at full weight is there from 152 ms to 446 ms (its audible rise is 152 ms, not 250); TOUCH and ROCK park at the heel for most of a long note (about 3.25 to 3.5 s of a 4 s guitar note at the default SPEED), a known property and not a defect; `wahed`, `touchwahed` and `rockwahed` are recipe characters (reachable from the CLI's `treat` and from saved kits), and the WAH chip joins the PEDALS row only if the section passes the owner's gate, for which `generateWahAudition` renders 35 clips (a snare, a choir and both MAGNET guitars, each guitar on its baked pad as the pad sheet does it and in front of its landing amp as a rig). A chip rewrites the pad's baked audio, so on a guitar pad COMP lands after the amp and DRIVE stacks a second amp; `generatePedalAudition` renders both ways (32 clips: the rig order for DRIVE is two renders in a row, the pedal and then the amp, and the card's PUNCH and the AMPED character sit beside COMP and DRIVE). The spec stages the rest: the COMPRESSOR section, the board in front of the amp for guitar pads, TREMOLO and FLANGER, and FUZZ deferred (DRIVE at its top is already gain 1000). Stage 1b (2026-10-20): a second page for the WAH gate, the short pads (a hat, a rim, a 60 ms snare; no click, since no engine renders one) and the full snare through AUTO as shipped and with a one-cycle floor on its rate (`Wah.fitted`, a listening door, sample-exact at zero cycles), plus the brainstorm's three zero-DSP listens for FADE IN, VIBRATO and DELAY, with ENSEMBLE beside TAPE's wobble and a second, closer ECHO recipe beside the long one (`generatePedalListens`, 89 clips (48 on 2026-10-20, 58 with the long-note sheet, 78 with the DELAY gate, 89 with the confirm listen); the rack's tail cap, measured, lands on repeats 48 to 80 dB under the hit, so the brainstorm's 'chops the fourth repeat' hazard was wrong and the page asks instead whether the cut is heard). The owner's first listen (2026-10-21): everything sounds good, but on the jangle and the choir the DELAY does not repeat the attack as it does on a hit (measured: the first repeat adds 37 dB over the snare's silence at 225 ms and 0.5 dB over the jangle's still-sounding note); a fifth sheet adds ROLL at two divisions and three test-source prototypes of an attack-fed echo (ECHO's repeat line fed by SPIKE's attack or by the first 120 ms, the repeats at the attack's own level under the note, its first repeat levelled to the note's own peak, and one with the note ducked under each repeat, set to 24 dB with a 150 ms recovery and measured at 15 dB on the jangle, an end on purpose, because the jangle's attack measures only 2 dB over its body so a repeat at the attack's level is a 4 dB bump) beside the plain DELAY, and a section follows only if the owner picks one; the brainstorm of what follows is `docs/superpowers/specs/2026-10-19-pedal-board-brainstorm.md`. DELAY (`docs/superpowers/specs/2026-10-22-delay-head-design.md`): on the long-note sheet (2026-10-22) the owner named the plain DELAY at 100 and the head-fed echo at 120 ms the most like a delay, liked ROLL as a secondary option (and on 2026-10-23 left it where it is), and asked for HEAD on ECHO. ECHO gains HEAD, a macro and not a section. At 0 it is ECHO bit for bit, pinned by 18 hashes recorded before the change; at 1 the repeats are fed only the note's first 120 ms. It ships with the `delayed` character (MIX 0.75, the owner's pick) and an ungated DELAY entry in the pedal library. The long-note sheet and its prototypes are retired, and the DELAY gate takes its place on the Stage 1b page. The owner answered it on 2026-10-24: HEAD off is the better delay at the same level on every held note, MIX 100, the repeats further apart, and DELAY joins the pedals; the resulting recipe (TIME 1.0, MIX 1.0, no HEAD) gets a confirm listen before the flip, because nobody has heard the three changes as one recipe, and at AMT 100, with the repeats 350 ms apart, the rack's one-second cap cuts the trail on the held notes while it is still sounding (the chug 20.7 dB under its peak). The rest of the Stage 1b page was answered the same day (SWELL on the jangle keeps its 2026-10-21 answer): the WAH floor won on every short pad and on the full snare (three sweeps a second) and should ship inside WAH; SWELL and BLOOM pass, but a FADE IN pedal is still wanted, and so is a VIBRATO pedal (TAPE's wobble reads as vibrato on the jangle and as nothing on the choir); the DELAY sheet's long recipe (MIX 50, no HEAD) is a delay and the pick on the snare and the choir, where its cut at 100 "fades out on its own", and not on the jangle ("No" and "Neither is right"; of the cut, "Cannot tell"); and the slate is "up to five on the row, swapped in from a larger slate", where the pedal library design assumed five or six on one pad. The gate (2026-10-23): the owner heard a wah on the snare and the choir, found SPEED's and Q's ends fine and either side of the amp good on the guitars (in front a little better), and asked for TOUCH and ROCK chips, so row seven is DRIVE, COMP, WAH, TOUCH and ROCK (36 chips on the card). Specs: `docs/superpowers/specs/2026-10-04-pedal-board-design.md`, for the WAH section `docs/superpowers/specs/2026-10-17-wah-section-design.md`, for DELAY `docs/superpowers/specs/2026-10-22-delay-head-design.md`, and for the pedal library `docs/superpowers/specs/2026-10-21-pedal-library-and-board-design.md` | S2.5 (the FX rack: SQUASH), S23 (VALVE V1/V1.1) |
| S11 | **Built, rounds 1-4** — VOX, the whole shebang: a throat (vocal-cord pulse, five formants of natural width, breath puffs), vibrato and wobble, CHOIR as seven singers in sections in stereo, held notes, SIZE and GLIDE (round 1); ONSET consonants and a BEATBOX voice (round 2); round 3's THROAT (overtone singing, with GROWL and YODEL), WRAITH (sine-wave speech, with ALIEN and STUTTER) and SWARM (a crowd, with STUTTER); round 4's SPEAK (a talking voice counting one to eight, speech chip to person) — under S11 below | S3.7 + U6 |

**Ballast integration status (23 October 2026):** the reviewed bass-driven frame,
wires and glass are available through the phone picker, preset desk, Spread and a
dry FRESH TAPE starter. The owner liked the bass; the revised glass graduation,
FRAME/SPAN response and native touch still await a listening verdict. TILES is
the glass voice's display label; GLINT remains its stored identity and PRISM/TILES
aliases load. HOLD at .99 returns a loop with deliberately calmer contact
mechanics. Design and historical measurements:
`docs/superpowers/specs/2026-10-04-ballast-bass-engine-design.md`.
The 24 October performance follow-up preserves all 30 compared output buffers,
adds sparse modal participation and reuses bass traces across structural edits
within a 24 MiB, four-entry cache. ROOT warm desktop medians moved from 230 to
165 ms for a one-shot and 491 to 283 ms for HOLD. Cold misses remain about
228/517 ms; the tens-of-ms target and phone timing remain open.

THAW is the next material-state engine (S27), ported from
`docs/superpowers/specs/2026-10-03-thaw-engine-design.md`: four modal ice
plates, two powered copper runners, local enthalpy and liquid layers,
delayed conserved channels, passive acoustic exchanges, bounded phase stress
and a small wooden enclosure. BRITTLE, RUNNER, MELT, CHANNEL, FROST and SHEET
share TUNE (C3–C5), CONTACT, HEAT, FREEZE, CHANNELS, THICKNESS and HOLD. Twelve
dry presets, `SynthKits.thaw()`, the THAW FRESH TAPE starter and the phone
picker are included. `generateThawAudition` supplies raw/matched clips and
diagnostics; `generateThawKit` exports the sixteen-pad acceptance kit.
Independent pads start frozen; HOLD exports settled sustain without a cold
attack because a pad has no attack-plus-loop-region field. Implementation
and verification notes are in `docs/THAW.md`. **Awaiting the owner's sonic
verdict.** The normalized material constants and preset names remain
provisional.

UNDERTOW follows as S28, ported from
[`2026-10-05-undertow-engine-engineering-spec.md`](superpowers/specs/2026-10-05-undertow-engine-engineering-spec.md):
one powered suction reservoir, four weighted flaps, ceramic rim contacts,
four tuned airflow resonators and a shared hollow shell. KNOCK, BREATH,
FLUTTER, SEAL, HOLLOW and SURGE share TUNE (C3–C5), DRAW, FLAP, WEIGHT,
SPIRAL, LEAK and HOLD. The apertures alter excitation, loading and shared
pressure before the final sum. Twelve dry presets, patch/recipe replay,
velocity energy, pitched kit routing, `SynthKits.undertow()`, the phone synth
picker and the FRESH TAPE starter use the same deterministic network.
`generateUndertowAudition` provides raw/matched voices,
macro sweeps, interaction grids, pressure/contact ablations, passive release
and difficult held material. Independent pads begin at rest; HOLD's top step
exports settled powered suction without the first catch because the sample
format has no attack-plus-loop-region field. [UNDERTOW](UNDERTOW.md) records
the diagnostic and verification contracts. **Awaiting the owner's listening
verdict.** The material settings, preset names and sonic acceptance remain
provisional.

SUTURE follows as S29, ported from [the supplied engineering specification](SUTURE_SPEC.md).
BLOOM, THREAD, CLOSE, MURMUR, STRAIN and SHELL share a pitched bronze vessel,
elastic cords, wooden eyelets, bounded stitch closure, aperture-dependent
cavity loading and dissipative seam contacts. Its controls are TUNE (C3–C5),
GAP, STITCH, CORD, SEAM, CAVITY and HOLD. The port includes patch/velocity
dispatch, twelve presets, `SynthKits.suture()`, a FRESH TAPE starter, the
phone picker and `generateSutureAudition`. HOLD exports settled powered
material because the pad API has no attack-plus-loop-region field.
Implementation and validation notes are in [SUTURE.md](SUTURE.md).
**Awaiting the owner's sonic verdict.** The reduced mechanical constants,
voice shapes and release names remain provisional.

SPOOL follows as the returning recording machine, ported from the
[supplied engineering specification](SPOOL_SPEC.md). STRING, CHOIR, FLUTE,
BRASS, PALIMPSEST and CUT use original note-specific source performances,
three loaded strips, diagonal heads, audible spring return, deterministic
splices and delayed cross-strip imprint. TUNE (C3–C5), HEAD, STRAIN, RETURN,
IMPRINT, SPLICE and HOLD share that machine. Patch recipes retain gate/source
duration, source roster and contact/transport/head/splice/write geometry;
velocity changes the contact gesture. Thirty-six dry presets, `SynthKits.spool()`,
the FRESH TAPE starter, phone picker and CLI expose the full factory bank.
HOLD explicitly exports a finite `FROZEN_SNAPSHOT` imprint through repeated
forward/return cycles. `./gradlew :synth:generateSpoolAudition` builds the
listening pack; [SPOOL.md](SPOOL.md) describes integration and its limits.
The owner accepted the original six sounds and musical audition on 2026-10-14.
The expanded bank contains eight strings, eight choir, six flute, six brass and
eight experimental presets; the six originals and starter mapping remain unchanged.
Published recordings and complete recipes support the new bank's listening pass;
device and release-name validation remain outstanding.

RELAY follows as S30, ported from [the supplied engineering specification](RELAY_SPEC.md).
KEY, BASS, PLUCK, ORGAN, CHIME and FLOW share four original generated roles:
rounded pluck, harmonic body, bell detail and soft tone. Source-order schedules
morph continuously at a fixed phrase clock; independent accents place rhythmic
emphasis without changing the requested root. TUNE, ORDER, PULSE, BLEND, COLOR,
LENGTH and HOLD use the pitched host conventions. The port includes complete
phrase serialization, native velocity, `SynthKits.relay()`, the phone picker and
a FRESH TAPE starter. The performance bank now contains 36 dry recipes: eight
KEY, six BASS, six PLUCK, six ORGAN, four CHIME and six FLOW. The six earlier seeds
remain unchanged. The SYNTH panel adds a VELOCITY control and note names; explicit
kit selections supply sixteen distinct pads without growing with the bank.
`generateRelayPerformanceAudition` builds a 100-clip raw/matched listening page
with all presets, six musical performances, three velocities per voice and the
sixteen starter pads, plus 24 optional PULSE response comparisons. The PULSE
candidate remaps nominal positions only for listening; the engine response,
factory macros and saved model stay unchanged. The earlier `generateRelayAudition`
engineering matrix remains available. HOLD exports repeating material without a
separate attack region. [RELAY.md](RELAY.md) records the integration and listening packs.
**The owner approved the 36-preset bank's sound on 2026-10-21.** Hands-on device
verification remains open, and the candidate PULSE mapping needs its own listen.

S1 and S2 are pre-app-buildable in this repo with CI coverage, same as
everything else. S4 is the one that needs hardware again.

## Placement

This slots after the app MVP (capture → kit → export must ship first — the
synth makes kits better, capture makes the app exist). CRUNCH is the likely
queue-jumper: it improves captured kits, which is MVP territory.

## What comes after S5

S1–S5 shipped this roadmap's original scope with thirty voices and no
presets, so rule 1 above ("preset-first, knobs-second") was only half
true when those phases closed. [`docs/SYNTH_UPGRADE.md`](SYNTH_UPGRADE.md)
is the note that measured the gap: the preset library, a SCRAMBLE that
lands, punch, stereo, and MATCH. That upgrade's own non-goals parked "a
second drum engine" as scoped-out; S6 revisited the decision and shipped
SKIN. `SkinPresets.kt` and the SKIN branch in `Presets.kt` have since
landed, the same pass THUMP got in U1. The current roster is
`Presets.forVoice`.

## S7 — SNAP: the photo engine

The app's name has two halves and until S7 only one of them was cashed.
SNAP (`synth/Snap.kt`, `Photo.kt`, `SnapPatch` in `Patches.kt`) turns a
picture from the phone's camera into a pad, two ways at once, both chosen
because they land on a *one-shot* the pipeline already knows how to trim,
place and export — not on a minute of spectrogram texture:

1. **Wavetable.** One line read through the photo is one cycle of a
   waveform: 256 brightness values, looped at a pitch. The voice picks the
   line — HORIZON reads left to right (each column averaged top to bottom,
   so it is the picture's silhouette, not one noisy row of pixels), PLUMB
   reads top to bottom the same way, ORBIT walks a ring around the centre.
   A ring closes on itself, so that cycle wraps with no seam; the other two
   get a 16-sample blend at the seam so an unrelated pair of end pixels does
   not click once per period. A skyline is buzzy, a gradient is soft,
   stripes are harmonic.

2. **Feature mapping.** The photo's summary numbers (`Snap.look`: mean
   brightness, contrast, mean saturation, saturation-weighted circular mean
   hue and how strongly the photo agrees on it, and the mean brightness step
   between points one cell apart on a 128-cell grid, across and down) set
   the four macros (`Snap.macrosFrom`): hue → TUNE across two octaves from
   A2, red low and violet high, the circle cut at rose (330°) so a 355° red
   and a 5° red are neighbours at the bottom of the knob rather than two
   octaves apart; a grey photo, or one whose colours cancel (half red, half
   cyan has no dominant hue), lands on the centre detent; brightness →
   BRIGHT (the filter, floored so a night shot is dark, not inaudible);
   colourfulness → DECAY; detail → GRIT, measured on a grid so a 160 px
   thumbnail and a 1024 px one of the same scene agree and a horizontal
   stripe counts as much as a vertical one. Every mapped value is bounded
   the way SCRAMBLE's are, so no photo lands on garbage. They are starting
   points to wreck: the sliders stay live and AS SHOT puts them back.

The voice is a wavetable oscillator (linear interpolation, rendered through
U6's 4x oversample and decimate because a 256-point table read at 440 Hz has
plenty above the band of its own and GRIT's drive makes more), into the
`TptSvf` low-pass that follows the envelope a little, into `Dsp.drive`,
under a 3 ms attack and a DECAY-set exponential tail. Renders are capped at
1.45 s so a pad stays under the classifier's 1.5 s LOOP line; at the top of
DECAY the tail outlasts its 500 ms TONAL line, and on a smooth line the
classifier calls the note a note (a square-wave stripe photo it hears as a
bright PERC — which it is).

What a pad stores is the 256 numbers and the macros, never the photo:
`SnapPatch` writes a `table` field beside `Patches`' common four, and the
WAV rebuilds from it bit for bit like every other synth recipe. The photo's
line is in the sidecar as plain digits — and a sidecar edited by hand meets
the same doors a photo does: a wrong-length, out-of-range or flat table is
a `JsonException` like any other malformed recipe (BREED and the recipe
replay already catch that), never a silent pad. The patch keeps its own
copy of the table.

Placement: like GRAINS, SNAP is outside SYNTH's `Engine` picker (it needs a
`Photo`, not a voice enum) and has its own tab. TAKE PHOTO uses the system
camera's `TakePicturePreview` contract — the small bitmap the camera app
hands back, no file, no storage permission, no CAMERA permission of our own.
"Small" is a promise some camera apps break, so the screen shrinks whatever
arrives to 512 px on the long side before reading it and catches the
`OutOfMemoryError` a 12 MP one would raise (`Copy.SNAP_TOO_BIG`), and it
keeps the line it read together with the chip and photo that read it, so
SEND TO PAD lands what was heard under the name of the chip that made it.
The one refusal comes in words (`Copy.SNAP_FLAT`): a line with no swing in
it — a plain wall read top to bottom, a clear sky read left to right — has
no waveform to play, and the screen says so instead of landing a silent
pad.

Not done, deliberately: a spectrogram scan (the Aphex trick) would need an
inverse FFT and produce textures, not hits; a preset roster (SNAP's presets
are photos); stereo.

### S7.1 — DRAW: the oscillator you draw

The photo was the first pen; a finger is the second. A SNAP pad is 256
numbers looped at a pitch and the engine never cared where they came from,
so DRAW (`synth/Draw.kt`, the DRAW chip and overlay in `SnapScreen`) is
only the pen:

- **`Draw.stroke`** lays a segment onto a table: every point the segment
  crosses takes its height there, the rest are untouched, either direction,
  clamped at the panel's edge. The screen sends one stroke per touch move
  from the previous position, so a fast finger that skips twenty points
  draws a line through all of them rather than dots. Every function returns
  a new array — a draft and a committed line never share storage.
- **Starting shapes**: `Draw.wave` (SINE, TRIANGLE, SAW, SQUARE, PULSE) for
  the cycle, `Draw.shape` (HOLD, FALL, PLUCK, SWELL, BOUNCE) for the volume.
  The surface opens over the line that is playing — the photo's, or the
  last drawing — so a photo line can be redrawn in part.
- **`Draw.smooth`**: a three-point average, circular for a cycle (whose
  last point neighbours its first), endpoints held for a shape (the note's
  start and finish are where they were drawn).
- **The volume shape** is `Draw.ENVELOPE_SIZE` (64) points across the
  note, stored as an optional `envelope` field beside the patch's `table`,
  read by `Snap.render` in place of the DECAY exponential under the same
  3 ms ramp; DECAY then sets only the length. A recipe without the field
  reads as it always did. A shape that never opens is refused at both doors
  (`Copy.SNAP_SHAPE_SILENT` on the surface, a `JsonException` from a
  sidecar), as a flat drawn wave is (`Copy.SNAP_DRAW_FLAT`).
- **`SnapVoice.DRAWN`** is the fourth line, with no photo behind it:
  `Snap.table` and `Snap.read` refuse it, `readsPhoto` says which lines a
  photo can be read along, and the screen keeps a drawn line current for as
  long as the DRAW chip is the selected one.

The surface auditions on every change with the sliders' own debounce, and
the main screen's render loop stands still while it is open so the two
never fight. DONE commits the wave only if it was drawn on or a starting
shape was picked (a visit to set the SHAPE alone leaves the photo's line
the photo's), and the shape only if one was drawn; CANCEL and back leave
everything as it was.

A hardening pass over DRAW (an independent read plus this document's own)
settled the following: the last drawing is kept across chip taps and new
photos and comes back through the surface's LAST button (a photo chip's
read used to replace it and DRAW reopened on blank); opening the surface
plays nothing until the first stroke, and closing it does not replay what
it just played; a new photo selects its own HORIZON line rather than
landing the photo's knobs on a drawn one; a shape "opens" only if it
reaches `Draw.OPENS` (8 of 255, -30 dB) — `any { it > 0 }` let a one-level
bump through that `normalize` then lifted to a full-scale click — and every
door uses that one check; `"envelope": null` in a sidecar reads as no
shape; FALL is drawn to end where the undrawn note is cut
(`Snap.LENGTH_OVER_T60`); the panel draws point i at the centre of the
column the pen maps a touch onto, so the line and the finger agree; a
touch with a NaN in it, or a table with no points, draws nothing; and a
shape drawn over nothing is kept with a toast saying why it is not yet
heard.

### S7.2 — PHOTO FIELD: the whole picture under a finger

SNAP reads one line through a photo and DRAW draws one; a photo has two
dimensions and a million pixels. The GRAIN FIELD screen (`docs`: the pad
sheet's scatter, `GrainField.analyze` in `:audio`) already plays a scatter
of short grains from wherever a finger is, and all it needs is a sample and
a list of positions — a `GrainField.GrainMap`. So the picture is the map.

`PhotoField.build` (`synth/PhotoField.kt`) cuts the photo into a grid
(16×12 by default, a cell at least a pixel each way so a photo smaller
than the grid reads in overlapping cells rather than refusing), and for
each cell: `Snap.look` for its reading and `Snap.macrosFrom` for its
knobs, `Snap.table` for its HORIZON line — or a SINE from `Draw.wave` when
the line is flat, because the field must sound everywhere and a patch of
clear sky is a pure tone at its hue — then `Snap.grain` for a steady
`GRAIN_FRAMES` (4096, ~93 ms) tone of it, HOLD-shaped under the usual ramp
since the field's voice windows every grain itself, scaled by the cell's
brightness between `DARKEST_LEVEL` (0.25) and 1 so a night shot still
speaks. The grains sit end to end in one source and the map places grain
i at the centre of cell i, y down as the canvas draws it. No
`Projector` comes with a photo, so DUET's chip stays hidden.

`Snap.grain` is `render` with a length of the caller's choosing
(`synthesize`'s `lengthSeconds`), through the same oversampled path, cut to
the exact frame count the field addresses by stride.

On the screen, FIELD builds the field once per photo (off the main thread,
`Copy.SNAP_FIELD_BUSY` on the LCD meanwhile) and opens `GrainFieldScreen`
with a `PrebuiltField` — the field's sample and map, the photo as a
backdrop drawn dimmed under the dots, PHOTO FIELD for a title and ◄ SNAP
for the way back — in place of loading and analyzing a pad. SNAP's own
audition stops when the field opens and its render loop stands still while
it is up, so the two voices never overlap.

CLOUD is `PhotoField.cloud`: the field's whole source through
`Grains.render` (2.5 s, seeded), landed on a pad through the same slot
chooser as SEND TO PAD, as audio with no recipe — every GRAINS pad is its
own truth — classed LOOP, named Snap Cloud.

Deliberately later: capturing a drag across the field as audio (GRAIN
FIELD's own declared next phase, not built for pads either); the
spectrogram reading of a photo (the image as a picture of sound, through
the spectral door and `Pghi` the retune already uses) as a second field
mode; a KEY lock that pins every cell to one note so the field varies only
in timbre.

A hardening pass over PHOTO FIELD (an independent read plus this
document's own) settled the following. The voice re-triggers on a fixed
512-frame clock and a photo cell's grain is a steady tone from phase zero,
so copies of one grain overlap-added at a fixed period comb-filtered each
other — near silence at the notes whose period divides the hop badly (a
JVM overlap-add of a 311 Hz grain measured under a sixth of its jittered
level); `GrainMap.jitterTriggers` asks the voice for hops drawn between
half and one-and-a-half of the clock, which scatters the phases, and a
photo field sets it while an analyzed pad's map keeps the even cadence.
`Snap.grain` renders at native rate, the one place SNAP does, because two
hundred oversampled-and-decimated grains took four seconds on a desktop
and the voice windows and mixes them eight deep anyway. `Snap.table`
interpolates between the pixels of a line narrower than the table instead
of repeating each one, so a 32-pixel cell (or a thumbnail) is a curve and
not a staircase buzzing at the pixel rate. A cell whose swing is under
`SOFT_SWING` (24) is blended toward a sine in proportion, since the cycle
is normalized to full scale before it plays and a four-level sky would
otherwise be a full-scale four-step square. On the screen: a build that
lands after a newer photo drops itself, a new photo closes a field that
is up (the main loop stood still behind an overlay no longer shown), TAKE
PHOTO waits for a build, the field overlay catches touches so a tap in a
gap cannot reach AUDITION and play SNAP's voice over the field's, the
prebuilt field is remembered so a recomposition does not tear the voice
down mid-drag, the way back consumes its quiet even with nothing to
render, and CLOUD onto a synth pad clears the pad's recipe (replaceAudio
keeps one when handed null, and the old note would have regenerated over
the cloud on the next rebuild from the sidecar).

### S7.3 — A KIT FROM ONE PHOTO

`docs/PHOTO_SPECS.md` §1, built as specced. `PhotoKit.build` (`synth/PhotoKit.kt`)
is `PhotoField.build`'s own reading of a picture, cut 4×4 instead of 16×12,
each cell landing as a full SNAP **pad** (`Snap.render`, a note) rather than
a grain: `Snap.look` for the reading, `Snap.macrosFrom` for the knobs,
`Snap.table(HORIZON)` plus `PhotoField.cellTable`'s own blend toward a sine
for the line, so a flat cell never refuses here either — the kit must fill
every pad, same as the field must sound everywhere. Cell (column, row),
row 0 the photo's top row, lands at slot `13 - 4×row + column`: (0, 0) is
A13, (0, 3) is A01 — `PadBanks`'s own top-row-first numbering, the same
geometry `KitArt`'s GRID style and `KitScreen`'s own grid already draw.
`AutoPlace` never runs: the layout is the picture's, not the drum
convention's.

Colour needed one seam: `Snap.Reading` gained `meanRgb` (a third pair of
accumulators alongside `Snap.look`'s luminance and hue sums, packed through
`Photo.rgb`), and `ArrangedPad` gained an optional `colorHex`, checked
first in `KitAssembler.assembleArranged` ahead of `AutoPlace.colorFor` —
every other caller passes none and gets the class colour exactly as
before. The recipe is the pad's `SnapPatch` (`PadRecipe(patch =
patch).toJsonValue()`), so a photo kit regenerates from its `kit.json`
sidecar like every other synth kit; it is not a folder of anonymous WAVs.

Landing is `KitShelf.landPhotoKit` (`:app`) — `render`'s own shape
(`freshName`, `KitAssembler.assembleArranged`, a fresh `Entry`) off a
photo's own cells instead of a starter's seed — called from `App.kt`'s
`buildPhotoKit`, which mirrors `fresh` line for line: the same whole-app
`busy` overlay (`Copy.SNAP_KIT_BUSY`), the same shelf-list refresh, the
same `open`/`screen = AppScreen.KIT` landing. KIT ▸ sits beside FIELD ▸
and CLOUD ▸ on the SNAP screen; unlike them it leaves the screen, which is
why it rides the app's own busy lock rather than a local one the way
`buildingField`/`cloudBusy` do — the same shape `fresh`/`finishBreed`/
`texture` already use for "render offline, land on the shelf, open it."

### S7.4 — PRINT: capturing a drag across the field

`docs/PHOTO_SPECS.md` §2, built as specced. `GrainFieldScreen`'s own KDoc
called this out as "v1 is play-only… capturing the performance is the
declared next phase, not built yet"; PRINT is that phase, for the pad
sheet's own field as much as PHOTO FIELD's, since both share the one
`GrainFieldScreen` composable and the one `GrainVoice`.

`GrainVoice` gained `startPrint(): Boolean` / `stopPrint(): FloatArray?`
and three `@Volatile` fields in the same one-writer/one-reader shape
`targetX`/`targetY`/`gated` already use: `printing` (the UI writes it,
the render loop reads it once per block), `printed` (the render loop
writes it, `stopPrint` reads it back), and `printBuffer` — `PRINT_SECONDS`
(60, `SurfaceEngine.MAX_PRINT_SECONDS`'s own ceiling) worth of frames,
preallocated once in `start()` so the render loop itself never allocates,
the same discipline the mix block follows. The tap sits in `runLoop`
right after the block is clamped to `[-1, 1]` and before it is written to
the `AudioTrack` — a print holds exactly what the track was about to
play. The arithmetic (how much of one block still fits, stopping dead at
the ceiling) is `PrintTap` (`:shell`, new) — a pure function with its own
JVM test, since `GrainVoice` is Kotlin over `AudioTrack` and has none.
`stopPrint` waits one block's worth of wall-clock time before reading the
buffer back: the loop reads `printing` once per block, so the block
already in flight when STOP lands still has to land in the buffer first;
a print that reached the ceiling on its own has already stopped and needs
no such wait.

`GrainFieldScreen` gained a PRINT / STOP PRINT header chip beside DUET's,
an `● PRINTING` line over the field while one runs, and the landing:
`stopPrint()` off `Dispatchers.Default` (never the UI thread — the sleep
above would jank it), then `SnipStore.import` on IO, the exact shape
`SurfaceScreen`'s own PRINT already uses (`Copy.surfacePrinted`,
`Copy.PRINT_LOST` reused verbatim; a new `Copy.GRAIN_FIELD_NOTHING_PRINTED`
in place of `SURFACE_NOTHING_PRINTED`, whose wording says "hold the
surface" — true on SURFACE, not on a screen with no surface on it). A new
`onFieldPrinted: () -> Unit` callback threads through both call sites —
`App.kt`'s own GRAIN FIELD and, one level down, `SnapScreen`'s PHOTO
FIELD overlay — to the same `{ importCount++ }` shelf-reload `SURFACE`'s
own `onPrinted` already triggers. No PAD landing here: PRINT only ever
lands on TAPE, the spec's own recommended reading ("it is what was
heard"); a captured drag reaches pads afterward through the ordinary
TAPE → CHOP door, same as any other capture.

### S7.5 — TILT: tip the phone to play the picture

`docs/PHOTO_SPECS.md` §3, built as specced. `TiltSource` (`:app`) read only
roll (gravity along the device's X axis); it now reads pitch too (Y axis),
guarded and set independently of roll so a NaN or a missing reading on one
axis never holds the other back. Both are `@Volatile` floats, 0..1, flat at
0.5, written from the sensor thread and read from the UI/control-rate loop
— the same single-value contract `tilt` already had.

The mapping from a raw (roll, pitch) reading to the field's cursor —
dead-banded (`DEAD_ZONE`, 0.02: below this much change from the cursor's
last position, a reading is a still hand's jitter, not a move, or the
field would shimmer at rest) and smoothed (`SMOOTHING`, 0.5, DUET's own
factor) — is `TiltCursor.step` (new, `:shell`), a pure function with its
own JVM test, since `GrainFieldScreen`'s loops have none.

`GrainFieldScreen` gained a TILT header chip and a control-rate
`LaunchedEffect` in the exact shape DUET's own loop already has: every
tick, if no finger is down, `TiltCursor.step` walks `autoPos` toward the
phone's current tilt and calls `voice.setTarget`/`gate`, a finger always
winning over either automatic cursor the same way. DUET and TILT are
exclusive — the spec's own recommendation, "one automatic cursor at a
time" — enforced where each chip is tapped on: turning one on turns the
other off, since both drive the one `autoPos`/`voice` and nothing
reconciles two live writers of it. Available on both `GrainFieldScreen`
callers, the pad sheet's own field as much as PHOTO FIELD's, since
nothing about TILT is photo-specific — the chip only shows when
`TiltSource.available` (a gravity or accelerometer sensor exists).

The one thing this environment cannot settle: the Y axis's sign in
portrait. `SurfaceScreen`'s existing use of `TiltSource.tilt` already
fixed the X axis's convention on a real device; pitch's sign (tip away =
up or down) is an on-device check, named as such in `app/README.md`'s
verify line.

### S7.6 — PATH: walk a picture in time

`docs/PHOTO_SPECS.md` §4, built as specced, both landings — §1 shipped
first, so the ring landing had a kit to name pads on. `PhotoPath`
(`synth/PhotoPath.kt`, new) is three pure functions: `sample` resamples a
drawn polyline by arc length to a fixed step count (a fast stroke and a
slow one over the same line give the same walk; a path that lingers over
one stretch gives it no more points than a path that crossed it in a
blink), `cellsFor` maps each resampled point to a `PhotoKit` grid cell
(0..1 both ways, clamped, `PhotoKit.COLUMNS`/`ROWS` by default so a
walked path always names the same cells a photo kit from the same
picture would), and `render` walks the cells as one gapless loop.

`render` needed a third `Snap` shape alongside `render` (full
oversampled, decay-driven length) and `grain` (native-rate, exact
length, for a grain field's hundreds-at-once cost): `Snap.cut`, new,
oversampled like `render` but exact-length and HOLD-shaped like `grain`
— a path is at most 64 steps, not a field's hundreds, so the
oversampling cost stays affordable, and a walked path is one gapless
line, not a run of separate hits, so each step must hold to its own end
rather than decay away before the next starts. `PhotoKit.kt` itself
picked up a small refactor first: `cellAt`/`slotFor` pulled out of
`build`'s own inline loop, so `PhotoPath.render` reads a cell exactly
the way a photo kit's own pad does — one function, not two readings that
could drift.

`PhotoPath` stays `:synth`-only; it never builds an `Orbit` or
`PatternOrbit`, since `:synth` has no dependency on `:loop`. That glue —
`App.kt`'s new `buildPathRing` — mirrors `OrbitScreen`'s own
`addPatternRing`/`addSnipRing` shape from outside the screen: load
`orbits.json` if one exists (else `OrbitPresets.fromKit`, the same
tempo/rate fallback `OrbitScreen`'s own loader uses), refuse past
`OrbitSet.MAX_ORBITS` with the existing `Copy.ORBIT_RINGS_FULL`, append
a `PatternOrbit(kit, hits)` whose hits are `PhotoKit.slotFor(column,
row)` per step, save, and hand the result back into `App.kt`'s own
hoisted `orbitSet`/`orbitSelected` before navigating to `AppScreen.ORBIT`
— the same whole-app busy overlay `buildPhotoKit` uses, since RING
leaves SNAP the way KIT ▸ does. The loop-pad landing stays local to
SNAP, `sendPathToSlot` mirroring `sendCloudToSlot` line for line
(audio with no recipe, `DrumClass.LOOP`, the same `SlotChooserOverlay`).

DRAW's own overlay gained a third tab, PATH — shown only over a photo —
with a `PathLcd` panel (the photo dimmed underneath, `GrainFieldScreen`'s
own backdrop convention, plain top-down coordinates rather than
`DrawLcd`'s wave/shape y-up), a STEPS chip row (8/16/32/64), and its own
bottom row (`CLEAR | CANCEL | LOOP PAD ▸ | RING ▸`) in place of
WAVE/SHAPE's `SMOOTH | CLEAR | LAST | CANCEL | DONE` — PATH commits
nothing through DONE, since LOOP PAD ▸ and RING ▸ each land the walked
cells directly, the same way KIT ▸ leaves through its own callback
rather than a draft.

### S7.7 — DUET on a photo field

`docs/PHOTO_SPECS.md` §5, built as recommended (fixed at
brightness/loudness), with one correction to the spec's own design
sketch — see below. `GrainField.Projector` (`:audio`) is now an
interface, one method, `project(vector: FloatArray): Pair<Float,
Float>`; the hand-rolled PCA basis `analyze` fits from a sample's own
grains kept its shape and its `internal constructor` under a new name,
`PcaProjector`. Every call site outside `:audio` only ever called
`.project(...)` on whatever `GrainMap.projector` held, so the rename
touched nothing beyond `:audio` itself and two stale KDoc `[...]` links
in `GrainFieldScreen.kt`; the one test that reached past the interface
(`GrainFieldTest`'s degenerate-axis case, which reads `.degenerate1`/
`.degenerate2` — fields the interface has no business declaring, since
`AxesProjector` has no notion of a degenerate axis) now casts to
`PcaProjector` for those two lines instead.

`AxesProjector(x: Axis = Axis.CENTROID)` (`:audio`, new) is the other
`Projector`: no grains to fit a PCA basis from, so it reads `x` straight
off `Similar.vector`'s own named dimension (`Axis` enum, one entry per
vector index) and leaves `y` at the map's own 0.5 centre. That is the
one place this build deviates from the spec's own sketch of
`AxesProjector(x: Axis, y: Axis)`: loudness is deliberately not one of
`Similar.vector`'s nine dimensions (the class's own KDoc — "a quiet
snare is still a snare"), so a second `Axis` for y would have to
misname some other spectral feature as loudness instead of just not
having one. `PhotoField.build` (`:synth`) sets `projector =
AxesProjector()` on its map — the one-line change the spec priced — so
a photo field carries a projector unconditionally, unlike an analyzed
sample's, which is only ever present when `analyze` didn't hit the
degenerate/too-few-grains cases `PcaProjector`'s own KDoc already
documents.

The spec's "the tick maps y itself" line (its own stated smaller
option, next to threading level through the interface as a tenth
dimension) is what actually wires loudness to y, and it needed a real
few lines in `GrainFieldScreen.kt`'s DUET loop, not the "DUET's code
changes not at all" the spec predicted for the interface-extraction
bullet alone: after `p.project(vector)` returns, `y` is replaced with
`MicSessionService.level.value` (already read once per tick for the
silence gate, so no new mic read) whenever `p is AxesProjector`,
leaving a `PcaProjector`-backed field's own second principal component
untouched. DUET's chip visibility (`projector != null`) and every other
line of that loop needed no change — the polymorphic `.project(...)`
call was already the only thing DUET asked of a projector.

### S7.8 — BREED: two photos, one child's line

`docs/PHOTO_SPECS.md` §6, built as specced. `crossTable(a: IntArray, b:
IntArray, coin: () -> Int)`, a new top-level function beside `object
Draw` in `Draw.kt` (not a method of `Draw` itself — crossing two lines
is breeding arithmetic, not "the pen" `Draw`'s own KDoc says that object
is), crosses two same-length point arrays point by point: `coin()` spun
fresh for every point, 0 → A's own value, 1 → B's, anything else → the
mean — the identical three-way shape `Breed.pick` already flips for a
macro. Size-agnostic on purpose: the same function crosses a SNAP
table's 256 points and a drawn envelope's 64. The coin is a callback
rather than a concrete `Random` type so `Breed`'s own `java.util.Random`
drives it directly — every other seeded function in `:synth` uses
`kotlin.random.Random`, and `crossTable` taking a `java.util.Random`
parameter just to suit one caller in another module would have been the
wrong module owning the convention.

`Breed.cross` (`:shell`) gained one branch: when both parents' patches
are `SnapPatch` (already guaranteed same engine and voice by the
surrounding check), a new private `crossSnap` crosses their tables
through `crossTable`, and their envelopes too when both drew one — a
new `crossEnvelope` giving a shape only one parent has the identical
"rides half the time" treatment `crossMacros` already gives a rack
section only one parent has. `SnapPatch`'s own constructor is the one
place that can refuse the result (a flat table, or a shape that never
opens), so `crossSnap` treats that refusal the way `breed()`'s own
outer classifier audit already treats a mismatched class: one retry
(a full fresh spin of both table and envelope), then a fallback — here,
A's own line and shape verbatim, since a photo kit's own line is always
a valid `SnapPatch` to begin with. The crossed macros are decided
before any of this and ride through unconditionally, on the fallback
path too: a table refusing to cross is not a reason to also throw away
a macro cross that succeeded.

Nothing on the SNAP or BREED screens changed — the spec's own costing
was right about that: BREED's existing button already crosses whatever
`Breed.cross` hands it back, and this changes only what that call
produces for a SNAP pad.

### S7.9 — SPECTRUM: the photo as a spectrogram

`docs/PHOTO_SPECS.md` §7, built as recommended (log frequency, its own
button). `Spectrogram.read(photo, seconds, sampleRate, seed): Snip`
(`synth/Spectrogram.kt`, new) builds one `FloatArray(Spectral.BINS)`
per output frame and hands the whole grid to `Pghi.invert` — a synthetic
spectrogram grid, not a real STFT of anything, since the "signal" here
is a picture read as one. Two axes, read differently on purpose:

- **Time (columns).** Each frame's fractional photo-x position is
  interpolated the exact way `Snap.table`'s own `resample` handles a
  line shorter than its 256-point table — the linear-interpolation
  else-branch, generalized from `TABLE_SIZE` to `frameCount` — so a
  narrow photo's columns blend into each other rather than repeating in
  a staircase.
- **Frequency (rows).** Each magnitude bin *b* has one true frequency,
  `Spectral.binHz(b, sampleRate)` — the plain linear FFT spacing every
  other `:audio` caller already assumes — and *that* frequency is what
  the log scale maps onto a photo row (~40 Hz at the bottom, Nyquist at
  the top; linear would put nine tenths of the image above 2 kHz). The
  bin index itself is never log-spaced.

That second point was a real bug on the first pass, not a hypothetical
one: an earlier version computed a bin's "frequency" by *inverting* the
log-row formula as a function of the bin index — `hz(b) = 40 ×
(nyquist/40)^(b/(BINS−1))` — then immediately fed that same `hz(b)`
back into `ln(hz/40)/logSpan` to pick a row. The two operations are
exact inverses of each other, so the whole thing canceled algebraically
into a **linear** bin-to-row map, silently defeating the log scale
entirely. It surfaced as a correctness bug, not a crash: `SpectrogramTest`
painted a photo's row for a chosen 1000 Hz and probed the inverted
audio with a single-bin Goertzel detector expecting energy to
concentrate there: instead it found near-flat noise from 100 Hz up to
5 kHz and a sharp spike around 10 kHz — the tone had landed roughly an
octave and a half sharp of where it was painted, following the linear
map's own bin arithmetic rather than the log scale the picture was
drawn against. The fix reads each bin's real linear frequency via
`Spectral.binHz` first, *then* runs that through the log-row formula —
one direction only.

The other two knobs the spec named for keeping a photograph from
reading as hiss: `magnitude = luminance²` (a power curve, so a dark
photo is near-silent rather than carrying a hiss floor), and each
frame's own bins under 5% of that frame's peak floored to true zero
before `Pghi.invert` ever sees them. `SpectrogramTest` covers all four
of the spec's own suggested cases — a bright row's tone lands near that
row's frequency (Goertzel probe), a black photo inverts to exact
silence, a bright column's energy concentrates in the output's own
middle third (a "click" at that column's time), and a non-positive
`seconds` refuses outright — plus the regression case above.

Landing is SPECTRUM ▸, a new button beside FIELD ▸/CLOUD ▸/KIT ▸ on the
SNAP screen, needing only the open photo (not FIELD's own grid the way
CLOUD does) — `sendSpectrumToSlot` mirrors `sendCloudToSlot` line for
line: recipe-less audio, `DrumClass.LOOP`, the same `SlotChooserOverlay`.
No `Grains.render` pass over the result — the texture itself is the pad,
the same "both are recipe-less audio, as CLOUD is" reading the spec's
own landing section offered as the simpler of its two options.

### S7.10 — LIVE: the camera preview as a continuous voice

`docs/PHOTO_SPECS.md` §8, built with its own recommended gate (§1–§3
played on a phone first) explicitly waived — there is no phone in this
session to play them on, and the answer, asked directly, was to build
it anyway.

**The native voice.** `LiveSnapEngine.cpp`/`.h` (`app/src/main/cpp/`,
new) is a wavetable oscillator, not a grain field: one `LiveTable`
(256 points, `Snap.liveCycle`'s own shape — already −1..1, zero-mean,
seam-blended) read every sample, cross-fading to a new table over a
fixed ~50 ms window rather than swapping in a click, at the *same*
phase on both sides of the fade so the blend is of two comparable
cycles rather than two waves drifting apart in time. The table handshake
is `SurfaceEngine::adoptPendingSample`'s own single-slot pointer
convention, copied rather than reinvented: a `pending_`/`retired_` pair
of atomics so the audio thread only ever adopts and never frees, and the
UI thread only ever frees what the audio thread has already retired.
Three macros — TUNE, BRIGHT, GRIT — glide through `ParameterSmoother`
exactly as `SurfaceEngine`'s own corners do; BRIGHT's smoother doubles
as the actual audio-rate lowpass filter itself (`filter_.setTarget(sample);
filter_.next()`), the same object playing both roles because a one-pole
follower and a one-pole lowpass are the identical piece of math. DECAY
is read off every frame (`Snap.macrosFrom`) and shown on its own slider,
but never sent to the engine at all — the class's own header KDoc says
why: a continuous voice never stops sounding to decay away, unlike every
one-shot SNAP render.

Rate-dependent state (both smoothers and the crossfade's own frame
count) is sized in a private `configureForRate(fs)`, called once from
the constructor at the default 48000 Hz and again from `start()` at
whatever rate the device actually opens — `SurfaceEngine`'s own
constructor keeps the same split, because the host test harness
(`app/src/main/cpp/test/`) constructs an engine directly and calls
`onAudioReady` without ever calling `start()`.

**A real bug the host tests caught before a phone would have.** The
first draft's `setMacros()` correctly stored its three targets into
atomics, but nothing in `onAudioReady()` ever read them back into the
smoothers — `tuneSmoother_`/`brightSmoother_`/`gritSmoother_` just kept
gliding toward whatever they were `.snap()`ped to at construction and
never moved again. Every macro was a dead knob: the engine compiled,
opened a stream, and made sound, and would have passed a listen test
that never actually moved BRIGHT. A new test,
`live_snap_engine_bright_opens_the_filter`, caught it directly — two
renders at BRIGHT 0 and BRIGHT 1 came back with the identical filtered
level (confirmed with a stray `printf` before the fix: `dark=11.395226
lit=11.395226`, bit for bit) until `onAudioReady` was given three lines
reading `targetTune_`/`targetBright_`/`targetGrit_` into the smoothers'
own `setTarget` right after adopting a pending table. All five new
tests (silence with no table pushed, a table's pitch once one arrives,
a mismatched `pushFrame` length clamped rather than overrun, the
crossfade never clicking across a table swap, and this one) pass
alongside the suite's existing 97.

**The JNI/Kotlin owner shape.** `jni.cpp` gained a third `extern "C"`
block, `NativeLiveSnap` — the same create/destroy/start/stop/sampleRate/
needsRestart/isShared/latencyMillis shape `NativeSurface` and
`NativePads` already keep, plus `pushFrame`/`setMacros`.
`LiveSnapVoice.kt` is the Kotlin owner class, `SurfaceEngine.kt`'s own
shape copied exactly: a `Long` handle, every method `@Synchronized` and
a no-op once closed, `close()` idempotent. Both `CMakeLists.txt`s (the
production `add_library` and the host test's `add_executable`) list
`LiveSnapEngine.cpp` — the two-source-lists footgun the steward skill
warns about, watched for on purpose this time.

**`Snap.liveCycle`.** `:synth`'s own `cycle(table)` — the raw table to
the seam-blended, zero-mean cycle a wavetable oscillator actually
reads — was `internal`, unreachable from `:app`'s native/Kotlin tree. A
one-line public `Snap.liveCycle(table) = cycle(table)` is the only
`:synth` change this section needed.

**The screen and the camera — unverified beyond static review.**
`LiveSnapScreen.kt` (`app/src/main/kotlin/com/snipsnap/app/ui/`, new) is
CameraX `Preview` + `ImageAnalysis`, bound to the screen's own
`LocalLifecycleOwner`, with `ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888`
so each analyzed frame becomes a `Bitmap` by a direct
`copyPixelsFromBuffer` (Android's ARGB_8888 byte order and CameraX's
RGBA_8888 byte order agree, which is the whole reason that output format
exists) rather than a hand-rolled YUV_420_888 conversion, downscaled
before `Bitmap.toPhoto()` (widened from `private` to `internal` in
`SnapScreen.kt` for exactly this reuse) feeds `Snap.table`/`Snap.look`/
`Snap.liveCycle` into the voice on the analyzer's own background thread.
The line just read is drawn over the preview; the four macro sliders
(TUNE/BRIGHT/DECAY/GRIT, `MacroSlider` reused read-only) track the same
reading; FREEZE lands the last frame on `SnapScreen`'s own `photo`/
`reading`/`macros` through the identical assignment TAKE PHOTO's own
camera-result callback already makes. `AndroidManifest.xml` gained the
CAMERA permission (optional `uses-feature`, so a camera-less device
still opens SNAP; LIVE ▸ asks and, refused, says so and closes) and
`app/build.gradle.kts` gained `androidx.camera:camera-core`/`camera2`/
`lifecycle`/`view` 1.4.1.

No Android SDK reaches this session — the native engine is proved by its
own host tests, `Snap.liveCycle` by a JVM test, but the CameraX bind,
the permission flow, the preview's own look and the live sound on a
real stream have only been read, never run. `app/README.md`'s on-device
checklist carries a LIVE line naming exactly that.

### S7.11 — CHORD: the photo picks a chord

`PhotoChord` (`synth/PhotoChord.kt`, new) reads one photo as a chord and
lands the chord on the pads low to high, so `Arp` (`:loop`) can walk it.
Three readings, each on its own axis so they cannot move together:

- **Root** from hue, through SNAP's own TUNE mapping, so the chord is
  rooted on the note the photo's SNAP pad already plays; a grey photo
  lands on the centre detent, A.
- **Third** from brightness: at or above mid-grey (`MAJOR_LUMINANCE`,
  0.5) is major, below is minor.
- **Seventh** from colourfulness, at the halfway point `Snap.macrosFrom`
  stretches saturation around (`SEVENTH_SATURATION`, 0.25).

`PhotoChord.tones` is every chord tone inside SNAP's two-octave TUNE
range, from the root's first appearance, so the root sits on A01 (the
`Scales` keys-on-pads rule). That is four pads for a high root (root,
third, fifth, root again) up to nine for a low seventh chord, so an UP
arpeggio across them always spans at least an octave inside one bank.
`build` renders every pad from the whole photo's HORIZON line (through
`PhotoField.cellTable`'s sine blend, so a plain wall still fills its
chord) and returns the slots an arpeggio should walk beside the pads.

Each pad wears `PhotoChord.colorFor` its pitch: `Snap.hueForSemitones`
(the TUNE mapping run backwards, new and shared) at full saturation, so
photographing a pad's light and reading it with SNAP plays that pad's
note. `PhotoChordTest` holds that round trip for every semitone, by
pitch class, since the hue circle closes and the bottom and top A share
a colour.

Not done: the landing. `:synth` cannot see `:loop`, so the glue that
lands the pads on the shelf and writes an `Arp.run` ring over
`Built.slots` belongs in `:app`, beside `buildPathRing`, and has not
been written.

### S7.12 — TELEPHONE: a sound whispered through pictures

Every door before this one runs picture to sound. `Spectrogram.portrait`
(new) is the first the other way: a sound painted on exactly the scale
`Spectrogram.read` plays a picture back on, columns of time by rows of
log frequency. The two share `rowForBin` (pulled out of `read`, whose
behaviour is unchanged; `SpectrogramTest` still passes as it was), so
each is the other's way back. A row takes the loudest bin `read` would
light from it, so a partial between rows is never averaged into the
dark; brightness is the square root of level against the portrait's
peak, `read`'s power curve undone. Frames are kept when their centre
lies inside the audio. `Spectral` pads a frame of silence at each end,
and drawing those would add a black margin that the next `read` plays
as extra time. Reverting that filter alone turns the edge column of a
steady tone from 0.73 to 0.0, which is what the edge test asserts
against.

The colour carries pitch as SNAP hears it, `Photo.tint` (new) shading a
row's hue to exactly the brightness the level asks for. Rec. 601 luma is
a straight sum of the channels, so the tint changes nothing `read` sees,
to within a channel's rounding. It changes what SNAP sees: a 220 Hz
tone's portrait reads through `Snap.look` as TUNE at A3.

`Telephone` (`synth/Telephone.kt`, new) is the loop: `pass` paints a
sound and reads the portrait back at the sound's own length, and `chain`
repeats it up to `MAX_GENERATIONS` (16) times, each pass hearing only
the one before. A pass keeps where the energy sits in time and pitch
(`TelephoneTest` holds 440 Hz within 3% through one) and loses phase,
anything under a frame's 5% floor, detail finer than a row or a column,
and whatever 8-bit pixels round away. Deterministic per seed.

Not done: the screen. The portraits want showing in a row as the
generations drift, with any generation's sound one tap from a pad.

## S9 — TIDE: the West Coast engine

**Status:** S9 built (engine, presets, tests, SYNTH picker, testkit
kit); **waiting on its audition gate** before S9.1. Where the build
departs from this spec, and why, is under "As built" below.

RESIN (S8) is the East Coast half of synthesis: a rich wave, cut down by
a resonant filter. TIDE is the other half. It **builds** harmonics instead
of cutting them: a plain sine is bent, folded and modulated until it is
bright, and then a low-pass gate closes brightness and level together, the
way a struck object goes quiet. The signature result is the "bongo": a
woody, wet, pitched knock with nothing like it anywhere else in the picker.

Guardrail: the style is named after the coast, never after its makers.
Nothing on a product surface (engine, voices, presets, descriptions,
commits) names a manufacturer, a module or a model number. The words
below (wavefolder, low-pass gate, complex oscillator) are generic
terms of the craft. The guard that enforces this,
`PresetTestSupport.trademarkBlocklist`, knows only the drum-machine and
keyboard makers today, so S9 extends it with this style's own: `buchla`,
`serge` and `make\s*noise`, plus their model numbers as they come up in
review.

### Why a new engine and not a preset

| Already in `:synth` | Where |
|---|---|
| Two-operator FM with a snapped RATIO | `Tines` (`RATIOS`, `strike`) |
| A resonant two-pole low-pass | `Dsp.TptSvf` |
| 4× oversampled render, decimated to `RATE` | `Dsp.OVERSAMPLE`, `Dsp.decimate` |
| Loudness levelling for melodic voices | `Dsp.levelTo` at `Dsp.MELODIC_LOUDNESS_TARGET` |
| Deterministic per-render seeds | `Dsp.seedFor`, `Dsp.Noise` |

So TIDE is only worth building for the three things none of that does:

1. **The wavefolder.** Past full scale, a folder reflects the wave back on
   itself instead of clipping it, so each extra unit of drive adds a new
   fold and a new set of odd harmonics. A sine stays a sine at zero, and
   turns glassy, then vocal, then snarling as FOLD rises. Nothing in the
   tree adds harmonics this way. `tanh` drive (RESIN, FATHOM) squashes;
   folding multiplies.
2. **The low-pass gate.** One control drives a VCA and a low-pass together,
   through a slow-to-let-go response (the light-dependent resistor of the
   original circuits). Brightness and level fall at once, and the tail
   slows as it fades. That coupling is the "bongo". A VCA after a filter
   envelope can approximate it, but only by accident.
3. **Uncertainty.** A smooth, seeded random source that nudges timbre and
   decay a little per note, so sixteen hits of one pad are sixteen
   slightly different knocks. It is baked, deterministic and bounded.

### Signal path

Rendered at `Dsp.RATE * Dsp.OVERSAMPLE` and decimated at the end, like
every engine. Folding is the most alias-prone operation in synthesis, so
oversampling is not optional here.

```
MOD sine (carrier × RATIO) ──phase-mod (WARP)──▶ CARRIER sine at the note
    ──▶ FOLDER (FOLD, opened by the strike, closing with the gate)
    ──▶ LOW-PASS GATE (VCA + two-pole low-pass on one control, DECAY)
    ──▶ decimate ──▶ levelTo(MELODIC_LOUDNESS_TARGET + voice offset) ──▶ fadeTail
         ▲
    WANDER: one seeded smooth-random line nudging fold depth, gate decay and
            WARP per note; never pitch
```

- **Phase modulation, not frequency modulation.** The carrier's phase is
  pushed around; its frequency never moves. Pitch therefore stays exactly
  on the note at any WARP, which keeps TUNE, `Keys`, SPREAD and the
  keygroup export honest. Through-zero FM would sound close but drift the
  pitch the detector reads.
- **Sine folder:** `y = sin(π/2 · drive · x)`, drive `1 → 6` from FOLD. It
  is smooth, so it aliases less than a triangle folder at the same
  brightness, and at drive 1 it passes a sine untouched. A small fixed DC
  offset before the fold, set per voice, adds the even harmonics that
  keep it from sounding like a square.
- **Gate control `c`:** rises in about 2 ms, then falls as
  `dc/dt = −c / τ(c)` with `τ(c) = τ₀ · (1 + 3·(1 − c))`, so the tail
  slows as it fades. Gain is `c^1.3`. Cutoff maps `c` from 60 Hz up to
  18 kHz, key-tracked through `Dsp.keyTrack` so a high note is not
  darker than a low one at the same setting. Resonance stays low (a gate is
  not a squelch filter); BONGO alone gets a little, for the pop.
- **The fold closes with the gate.** Fold depth is `FOLD × c`, so a hit is
  brightest at the strike and mellows into its tail, the second half of
  what makes it sound struck rather than switched.

### Macros

Plain words and bounded ranges (playability rules 2 and 3). Every voice has
the first five; RATIO appears only where it is the point.

| Macro | Moves | Range |
|---|---|---|
| **TUNE** | the note, snapped to semitones from the voice's root | 24 semitones, like VELVET/PLUCK/VOX |
| **FOLD** | folder drive at the strike | 1× (clean) → 6× |
| **WARP** | phase-modulation depth | index 0 → 3, `Dsp.expMap` |
| **DECAY** | the gate's `τ₀` | per voice, e.g. BONGO 20 ms → 400 ms |
| **WANDER** | how far the random line strays, per note | 0 (identical hits) → ±25% on fold, decay and WARP |
| **RATIO** | modulator : carrier, snapped | `Tines.RATIOS` (GONG, FLARE only) |

### Voices

| Voice | What it is | Root | Expected class |
|---|---|---|---|
| **BONGO** | the signature: sine, ratio 1, light fold, a short gate with a touch of resonance | C3 | TONAL |
| **DRIP** | high and very short; the pitch chirps into the note over its first 15 ms and lands on it before the detector's window opens | C5 | PERC or TONAL |
| **GONG** | an inharmonic RATIO, long gate, low fold: metallic and ringing | C3 | TONAL (pitch may read unclear) |
| **FLARE** | the lead/stab: the fold opens wide and closes slower than the gate, a brassy "wah" of harmonics | C2 | TONAL |

"Expected" means the mapping in `SynthScreen`'s `drumClass` table mirrors
what the classifier test actually measures, the rule FATHOM set, not what
this table guesses.

Presets (`TidePresets.kt`) ship in the first phase, 8–12 per voice, named
for the sound: WOOD BONGO, RAIN DRIP, TEMPLE GONG, SNARL FLARE. SCRAMBLE
uses `Dsp.scrambleNear`, so a dice roll stays inside each voice's sweet
spot.

### It already works with what shipped

- **SPREAD** (PR #327): TIDE keeps exact pitch, so one BONGO spread across
  a bank in MIN PENT is the classic West Coast plucked pattern in one tap.
  WANDER never moves pitch, so the pitch SPREAD detects is the pitch every
  pad plays. GONG may read as no clear pitch, and SPREAD already handles
  that.
- **The rack:** SPRING and ECHO after a BONGO is most of the genre's
  ambience; nothing new is needed.
- **The CLI:** `snipsnap synth TIDE BONGO --all --out <dir>` works once the
  engine is registered, so every preset can be heard without a phone.

### Tests (CI measures the sound, not just the code)

- **Pitch:** at every snapped TUNE step, BONGO and FLARE detect within
  5 cents with FOLD and WARP at 0, and within 10 cents with both at
  maximum. Folding and phase modulation keep the fundamental.
- **The gate's signature:** the spectral centroid of the tail is at least
  1.5× lower than at the strike (brightness closes with level), and the
  fall from −6 dB to −20 dB takes longer than the first 6 dB did (the
  tail slows).
- **FOLD adds harmonics:** the centroid rises across FOLD 0 → 0.5 → 1
  for every voice at its defaults.
- **Aliasing floor:** at FOLD 1, WARP 1 and the top TUNE, energy between
  harmonics stays at least 45 dB under the harmonic energy.
- **Determinism:** the same recipe renders bit-identical audio (the
  regenerate-from-`kit.json` promise); WANDER 0 makes take indices
  identical, and WANDER above 0 makes them differ with the same pitch.
- **Loudness:** within the band the other melodic engines are held to.
- **Identity:** each voice's defaults and presets classify as its mapped
  class, and 200 SCRAMBLEs are all audible and unclipped.
- **Recipe:** `TidePatch` round-trips through JSON and `Patches.fromJsonValue`.

### Phasing

| Step | Ships |
|---|---|
| **S9** | `synth/Tide.kt` (`TideVoice`, macros, render), `TidePatch` in `Patches.kt`, `TidePresets.kt` and its `Presets` branch, the tests above, TIDE in the SYNTH picker (…→ RESIN → TIDE → THUMP; README's engine count goes from nine to ten), and a `SnipSnap Tide Kit` generator under `testkit/` (`./gradlew :synth:generateTideKit`). **Ends at an audition gate**: nothing proceeds until the voices have been heard, the rule the synth-depth work set. |
| **S9.1** | FLARE and BONGO as keys instruments through S8.1's `MAKE INSTRUMENT ▸` path, via a `Keys.tide(midi)` renderer at exact pitch. A gate is a strike device, so "held" means the gate parks at a sustain level with the fold settled before the loop starts. Whether that still sounds like TIDE is a listening question. |
| **S9.2** | *Optional:* TIDE POOL, uncertainty driving the *notes*: a seeded, in-key random melody landed on the loop grid as an `Arp`-style ring over a spread bank. This is the genre's generative patch, and the one place WANDER is allowed to choose pitches (from the scale, never between them). |

### As built — 2026-09-25

Measured while building, and each one changed the design rather than
the test:

- **The gate is keyed to the note, not to a fixed floor.** Closed is half
  the note, open is 64 times it (held between 6 and 18 kHz), exponential
  between. With the spec's absolute 60 Hz floor the gate closed through a
  high note's fundamental almost at once, and a closing filter pulls a
  partial's phase as it passes: DRIP at C5 read −22 cents in its first
  60 ms. Keyed, the gate passes the fundamental late and quiet on every
  note. A small sag remains, the way a struck drum's pitch falls: `TideTest`
  holds a clean note within 10 cents while the gate is over half open and
  within 30 cents down to a tenth.
- **An eighth-order band limit at 19.5 kHz runs before decimation.** The
  shared decimator rejects only about 18 dB just above the new Nyquist.
  Measured on a steady fold at C6 (drive 6, index 3): 36.5 dB of clarity at
  4×, 37.1 dB at 8×, 45.3 dB at 4× with the band limit. The leak, not the
  render rate, set the floor, so TIDE keeps the family's 4× render.
- **Above C6, FOLD and WARP ease off with pitch** (`reachAt`). DRIP's top
  octave at full fold and full WARP puts harmonics past what 4× holds (32 dB
  clarity at C7 even band-limited, 41 dB at 8× at twice the render time).
  Only DRIP plays up there. `TideTest` holds every harmonic voice's top note
  at ≥ 45 dB and proves the measure sees aliasing (the native-rate render
  scores at least 10 dB worse).
- **DECAY is the time to −60 dB, per voice**, not τ₀ (ranges as revised
  below). The release slows by `1 + 1.5·(1 − c)`, not `1 + 3·(1 − c)`: at 3
  a long GONG ran to ten seconds.
- **RATIO snaps to whole numbers on FLARE (1–4) and to bell ratios on GONG**
  (1.4, 2.7, 3.5, 4.2, 5.8), not to `Tines.RATIOS` for both. A non-integer
  ratio on the lead made it a bell with no clear pitch.
- **BONGO and DRIP are PERC; GONG and FLARE are TONAL** (as revised
  below). The struck pair follows the classifier; the held pair follows
  RESIN's rule.
- **Velocity reaches TIDE through FOLD.** It joins `Velocity`'s brightness
  macros, so a soft strike folds less, not just quieter.
- **WANDER's seed is the recipe plus a take index.** The same take always
  renders the same bytes (the kit regenerates to the bit); take 1 differs
  from take 0 only when WANDER is above 0. Nothing lands takes on pads yet:
  a round-robin chain is where they belong.
- **Ten presets per voice**, forty in all, `TidePresets.kt`.

Open questions 1–4 are answered as recommended: TIDE, FOLD, the seed rule
above, and DRIP as PERC.

### Revision — GLOW and held notes, 2026-09-26

The audition said "more character and oomph" and "very staccato". A
measurement found why: the strike was rich and the tail was bare. Counting
harmonics within 40 dB of the loudest, with each of the three things that
close with the gate held open in turn (DECAY 1):

| | 0–40 ms | 40–120 ms | 120–280 ms | 280–600 ms |
|---|---|---|---|---|
| WOOD BONGO, as built | 13 | 6 | 3 | 1 |
| … WARP not following the gate | 14 | 6 | 3 | 2 |
| … fold not following the gate | 11 | 7 | 5 | 3 |
| … filter held open | 14 | 9 | 6 | 3 |
| SNARL FLARE, as built | 21 | 11 | 6 | 5 |
| … filter held open | 21 | 12 | 11 | 16 |

The filter took the most, the fold closing with it took the tail twice,
and WARP barely mattered. So:

- **GLOW**, a new macro on every voice: how much brightness outlives the
  level. At 0 the gate is as built (the classic knock). Up, the fold keeps
  up to 60% of its depth once the gate has closed, and the filter follows
  `c^(1 − 0.75·GLOW)` rather than `c`, closing behind the level. Defaults:
  BONGO and DRIP 0.3, GONG 0.6, FLARE 0.7. Measured, SNARL FLARE at its
  default keeps 13 harmonics at 40–120 ms where GLOW 0 keeps 6; WOOD BONGO
  at GLOW 1 keeps 5 at 120–280 ms where GLOW 0 keeps 1.
- **Held notes live in DECAY on GONG and FLARE.** DECAY's top half holds
  the gate fully open for up to 60% of the note before it releases, so a
  long setting is a held note that then rings out. A separate HOLD macro
  would have made GONG and FLARE eight macros; this keeps them at seven,
  THUMP SNARE's count, with RESIN's CONTOUR as the one-knob precedent.
  BONGO and DRIP never hold.
- **Longer ranges.** DECAY now runs BONGO 0.12–1.5 s, DRIP 0.06–0.8 s,
  GONG 0.4–4 s, FLARE 0.25–4 s; the struck pair stops at 2 s, the held
  pair at 4 s.
- **GONG and FLARE are TONAL.** Long enough to hold, the classifier reads
  GONG as LOOP for four of ten presets, SNARE for three, PERC for three;
  FLARE as PERC or LOOP by length. They are pitched notes, so they take
  RESIN's rule and are held to measuring harmonic instead. BONGO and DRIP
  still classify PERC (8 of 10 presets each) and stay PERC.
- **Level, by loudness.** A held note meets the loudness target under a
  low peak (SLOW SUNRISE: 3.3 s at peak 0.29), a short hit meets the peak
  ceiling first; the tests hold "at the target or at the ceiling", which
  is the rule the engine follows, instead of "peak over 0.5".

The character stages auditioned alongside (a pitch thump, body, drive,
click, punch) are parked, not built: GLOW answered the missing harmonics
first, and the next audition says whether the oomph is still missing.

### Revision — the edge, 2026-09-26

With GLOW in, the audition said the voices "walk the line between good and
weird" and asked to push further. Five levers were prototyped and
auditioned one at a time and together (four presets: WOOD BONGO, TEMPLE
GONG, SNARL FLARE, RAIN DRIP); the pick was all five at a moderate
setting. They are built in as how TIDE sounds, not as knobs: an EDGE macro
would have made GONG and FLARE eight macros, past THUMP SNARE's seven.

- **SWEEP.** The modulator starts 2.2 times its ratio and dives onto it
  with a 20 ms time constant: WARP's sidebands fall into place in the
  strike, a zap. Auditioned at 60 ms, it left SNARL FLARE's pitch
  unreadable into its second hundred milliseconds (read 196 Hz for 131,
  confidence 0.47); at 20 ms it reads true, confidence 0.85, by 50 ms.
- **CROSS.** The folded output feeds back into the modulator's phase, 0.75
  radians per unit, so fold and WARP argue instead of chaining. Feedback
  like this is clean while `CROSS · index · drive` stays under about 1,
  rough to about 1.6 and noise past it (mapped on a steady fold at C3:
  index 1, drive 6 kept 79 dB of clarity at CROSS 0.1 and 30 at 0.4; full
  FOLD and WARP at 0.75 had none, 0.6 dB). So the loop is held at 1.5, the
  rough side on purpose: SNARL FLARE's strike sits right there, and a full
  corner keeps its harmonics 25-40 dB clear, a snarl rather than a hiss.
- **TILT.** The fold's bias leans +0.4 rad at the strike to −0.4 as the
  gate closes: the even harmonics turn over across the note.
- **WOBBLE.** Three seeded random lines, stepped at 11 Hz and smoothed over
  6 ms, jitter the fold's depth and WARP's index by ±22.5% and the fold's
  bias by ±0.27 rad, so a held note is never quite still. Seeded from the
  recipe (and from the take only when WANDER is up), so a pad plays the
  same way every time and WANDER 0 still makes every take identical.
- **REACH.** FOLD and WARP reach 40% further: drive 8.4 at FOLD 1 (was 6),
  index 4.2 at WARP 1 (was 3). The "above C6" easing is replaced by one
  rule that knows the modulator: the brightest corner reaches about
  `drive · (1 + index · ratio) · note`, and where that passes 22 kHz both
  ease together (`reachAt`). Low notes get all of the extra; DRIP's top
  octave and FLARE's high RATIOs ease back. Measured on a steady
  full-corner fold, the worst, DRIP at C7, keeps 45.7 dB of clarity; at the
  old notion's 25.1 kHz it and FLARE's C4 at RATIO 4 sat at 44.3-44.5.

None of them touches a clean note: SWEEP and CROSS act through WARP's
index, WOBBLE's depth moves scale with FOLD and WARP, and TILT and WOBBLE's
bias fade in over FOLD's first tenth. `TideTest` holds FOLD 0, WARP 0 to
harmonics 50 dB under the fundamental.

What moved in the tests, and why:

- **Pitch is read from 100 ms, not 50.** At 50 ms SWEEP still has the
  modulator 10% sharp and BONGO at full everything read a step high. From
  100 ms every harmonic voice at full FOLD and WARP, both RATIO ends,
  three takes, reads the clean note's pitch, now with a confidence floor
  (0.6) so a note that turned to noise fails too. Every FLARE preset
  reads its note within 10 cents at confidence 0.8 from 100 ms.
- **GONG and FLARE are held to ringing harmonic from 100 ms.** The zap is
  noisy by design (TEMPLE GONG reads flatness 0.23 over the whole note,
  0.05 from 100 ms); the presets' rings all measure 0.08 or under.
- **GONG's corners clang.** At full FOLD and WARP, a bell ratio gives CROSS
  no period to lock to, and GONG measures noise-like there (flatness
  0.26-0.42, was 0.06-0.14). GONG is a bell with no one pitch, so this is
  scrap metal struck hard rather than a broken note, and it is kept.

The classifier now reads BONGO as PERC for all ten presets (was 8) and
DRIP for nine (was 8): the zap reads more struck. GONG and FLARE are
TONAL as before.

### Revision — the oomph, 2026-09-26

The character stages parked after GLOW were ported onto the engine as it
stands (GLOW and the edge in) and auditioned one at a time and in pairs,
on WOOD BONGO, LOW CONGA, RAIN DRIP, TEMPLE GONG, SNARL FLARE and FOLD
BASS. The pick was "g OOMPH": THUMP and BODY. DRIVE (saturation), CLICK
(a noise transient) and PUNCH (a transient shaper) were heard and left
out. Both are built in, like the edge: GONG and FLARE have no room for
an eighth macro.

- **THUMP.** The strike starts seven semitones sharp and falls onto the
  note with a 5 ms time constant, carrier and modulator together, the way
  a drum head's pitch drops as it is hit. At 20 ms 13 cents remain, and
  every pitch reading starts later than that. BONGO's top note reads
  623 Hz over its first 1-8 ms for a 523 Hz note.
- **BODY.** A clean sine on the carrier's phase, half the gate's level,
  under the same VCA: the note itself under the fold. The fold spreads a
  bright voice's energy up the spectrum and leaves the note thin. The
  fundamental's share of the first 150 ms, across FLARE's ten presets:
  −2.7 to −28.3 dB without BODY (SNARL FLARE the worst), −1.3 to −6.6
  with it. The struck voices, whose note already carried them, move
  under 1 dB.
- **BODY takes the note's polarity, once per note.** A fold and a RATIO
  above 1 can turn the note's fundamental upside down, and a sine added
  blind then cancels it: REED STAB (RATIO 3) lost 2 dB of note and
  HOLLOW HORN 5. A running read of the polarity was tried and dropped:
  under a fundamental 28 dB down (SNARL FLARE) it wavered, and BODY with
  it (SNARL FLARE fell to −12 dB, OCTAVE GROWL to −19). The sign of the
  whole note's correlation with BODY's sine, taken after the loop, is
  steady and always adds.

What moved in the tests: "brightness closes with the level" asked the
strike to be twice as bright as the tail. BODY's sine under the strike
pulls its centroid toward the note, most on DRIP (C6, the least fold
room): 1.9 times its tail with BODY, 2.6 without. The test now holds what
the gate actually does, more strictly: the tail closes to the note itself
(within 10%), and the strike is at least 1.5 times brighter. New tests:
the strike starts sharp and lands; every FLARE preset's note sits within
8 dB of the whole. The classifier reads BONGO and DRIP as PERC on all ten
presets each.

### Revision — CLICK, 2026-09-26

Of the three character stages left out of the oomph, CLICK came back as a
knob: BONGO and DRIP's seventh macro (THUMP SNARE's count), starting at 0
on every preset, so nothing that shipped changes. GONG and FLARE have no
room. DRIVE and PUNCH stay out.

- **After the VCA, not before it.** The prototype put the noise burst
  into the gate, which takes 2 ms to open while the click is gone in
  1.5: at CLICK 1 it added 5% to WOOD BONGO's energy above the note in
  the first 4 ms, so the audition's "e CLICK" was barely a click. After
  the VCA, at 1.5 times full scale falling over 2 ms, CLICK 1 gives WOOD
  BONGO 3.1 times, LOW CONGA 5.2, RAIN DRIP 1.5 (a strike already
  bright); the body under it, levelled to the same peak, loses 0.3, 0.6
  and 1.5 dB. A brighter click (differenced noise) was tried and measured
  weaker: at the 4x render most of its energy sits above what the band
  limit keeps.
- **CLICK is left out of the WANDER seed**, so adding the macro left every
  existing recipe's draws where they were: the Tide Kit regenerates to
  the byte.
- **Velocity does not reach CLICK.** `Velocity` moves one brightness macro
  per voice (FOLD, on TIDE), so a soft strike is darker but clicks as
  hard. Moving two would be a change to `Velocity` for every engine, left
  for when it is wanted.

### Not doing

A patchbay or patch cables (rule 1: presets and macros, never modular);
real-time synthesis (TIDE renders offline like every engine); through-zero
FM (it moves the pitch, see above); stereo in S9 (mono first, as RESIN's
drone was; width is a later per-voice decision).

### Open questions

1. **The name.** TIDE is unused anywhere in the tree. COAST was the obvious
   pick, but `TapeDeck.Mode.COAST` already means a flicked reel coasting to
   a stop.
2. **FOLD, the word.** It is the genre's own word, but CHOP already has a
   FOLD layout. They live on different screens and mean different things;
   is that acceptable, or should the macro be BEND?
3. **WANDER and MOTION.** The synth-depth design says per-note randomness
   should make a *re-render* differ. The recipe promise says a kit
   regenerates bit-for-bit. This spec resolves it by seeding from the patch
   plus an explicit take index, so the same take is always identical and
   different takes differ. MOTION, when built, should use the same rule.
4. **DRIP's class.** Short and high reads PERC to the classifier. If it
   lands PERC, it stays out of SPREAD's pitch path unless a pitch is found.

## S11 — VOX, the whole shebang

VOX shipped (S3.7) as three formants over a buzzing source: four knobs,
nothing moving inside a note, one adult throat for every pitch, the
same breath on every render. Asked to "go for the whole shebang" and add
"unique, great sounding, bordering on weird" ideas, the plan is three
rounds, each prototyped, auditioned by ear and only then built:

1. **Alive** (this round): a real throat, motion, a real choir, held
   notes, SIZE and GLIDE.
2. **Speak**: consonant onsets ("ba", "da", "ma", "hey", "ts") and a
   BEATBOX voice (vocal kick, snare, hat, rim).
3. **Weird**: three new voices, THROAT (overtone singing, a whistled
   melody over a drone), SWARM (a crowd of 8-16) and WRAITH (sine-wave
   speech), and four behaviours, GROWL, STUTTER, ALIEN (vowels past
   what a human throat can shape) and YODEL. The knob budget (seven)
   cannot take all four on every voice: round 3 decides where each
   lives.

The uses to tune for, asked: drum hits, vocal chops, pads and choirs.

### Round 1, as built — 2026-09-26

The first audition was too subtle ("I'm not detecting much
difference"). A difference measure (the average spectrum in 24 log
bands, levelled, RMS dB) was calibrated before it was trusted: the new
render path alone, with every feature off, measured 1-4.5 dB from the
old engine (a detuned pair's beating depends on where it starts), a
semitone of pitch 8. The first throat and choir sat in that noise, so
the second audition turned everything up and played each preset as
NOW-then-NEW pairs. The choir still "didn't come across": seven fixed
detunes starting together fuse into one voice, and a mono file cannot
place singers. A third audition gave the choir motion, sections and
stereo. The pick: everything.

- **The throat.** CHOIR and GHOST sing through a Rosenberg vocal-cord
  pulse (opens over 40% of the cycle, snaps shut over 16%, rests), with
  breath that puffs while the folds are open. Five formants, F4 and F5
  fixed at 3.3 and 4.2 kHz, at roughly constant bandwidths (60-180 Hz)
  rather than a fixed Q; F4 and F5 at levels 1 and 0.55, the "singer's
  formant" ring. F1 never sits below 1.15 times the note, so a high note
  keeps its vowel. ROBOT keeps its square wave. Measured against the old
  engine: 4.4-7.5 dB, a semitone's worth of spectral change.
- **Alive.** Vibrato of 70 cents (GHOST 90, slower), easing in from
  30 ms over 120, on a slow random wobble of 15 cents and 12% level.
  ROBOT holds dead steady. Measured over a held GHOST note: 190 cents
  peak to peak; ROBOT 0.
- **The choir.** CHOIR is seven singers: two basses an octave down,
  three in the middle, two sopranos an octave up, each with their own
  throat (0.88-1.2x), detune (−35 to +36 cents), vibrato, a start up to
  60 ms late (the middle singer always on time, so the strike stays
  crisp), and a place in the stereo field (equal-power pans shuffled per
  recipe). CHOIR renders in stereo; ROBOT and GHOST stay mono. L/R
  correlation of a held CHOIR note: 0.63. The pitch detector still reads
  the written note, not the basses: TUNE 1 reads 445.5 Hz for 440.
- **Held notes.** DECAY runs 0.25-3 s to −60 dB and its top half holds,
  up to 60% of the note at DECAY 1 (TIDE's rule). No note outlasts
  `Vox.MAX_SECONDS`, 4 s.
- **SIZE** scales every formant by `2^((0.5 − SIZE)·2.2)`, about ×2.1
  to ×0.47, chipmunk to giant. ROBOT's centroid falls 7.7 times across
  it, GHOST's 2.7.
- **GLIDE** moves the vowel over the first 0.5 s (or 60% of the note):
  below 0.5 toward A, above toward U, by up to the whole A-U line. U
  gliding to A doubles in brightness; GLIDE 0.5 holds (0.99).
- **Seeded.** Each note's wobble, detunes, onsets and pans come from its
  recipe, so a pad regenerates to the byte.

What moved in the tests, and why:

- **"One-shot" is 4 s, not 1.5.** Held notes, like TIDE's.
- **"Reads as percussion" is now "a short VOX is a hit, a long one is
  held".** Up to DECAY 0.6 (about 1.4 s) every voice reads PERC or SNARE
  and serves as a drum hit or a chop; at 0.8 and above it reads LOOP, a
  pad. The app has always filed VOX as TONAL. Scrambles are held to
  never reading KICK or UNKNOWN (CHOIR's 30 rolls: 14 LOOP, 13 SNARE,
  3 PERC).
- **BREATH.** BREATH 0 now carries the throat's own puffs (flatness
  0.22), so BREATH 1 (0.30) is 1.4 times it, not 1.5; the test holds
  1.25 and, more to the point, that the pitch detector finds a note at
  BREATH 0 and none at BREATH 1.
- **New tests**: CHOIR stereo and wide, the lone voices mono; vibrato on
  GHOST, none on ROBOT; DECAY 1 holds within 4 dB at 40-50% of the note,
  DECAY 0.3 has fallen past 12; SIZE moves the centroid over 1.5x; GLIDE
  moves the vowel and 0.5 holds it; the vocal-cord pulse has no DC,
  peaks at the closure and rests closed.

Presets: still twelve per voice, eleven of them now set SIZE or GLIDE
where the name already says so (LOW/HIGH/DEEP throats; SPEAK BOX's
"wah", DROID VOICE, EH CHOIR, HIGH SIGH, LOW MOAN, HAUNTED OOH gliding).
The Cloud Kit's six VOX pads and three choir-fed GRAINS pads regenerate
(GRAINS folds the stereo choir to mono, as it does any stereo source).

**Known cost.** A 3.9 s stereo CHOIR note takes about 1.8 s to render on
the CI-class JVM, and 1.66 s of that is `Dsp.decimate`'s windowed-sinc
resampler, shared by every engine, run twice for stereo. Synthesis
itself is 340 ms. Short notes (drum hits, chops) are a fraction of it.
Speeding up the shared decimator would change every engine's bytes and
every kit, so it is its own change, not this one.

### Round 2, as built — 2026-09-26 ("Speak")

The plan was consonant onsets on the singing voices and a BEATBOX voice.
Both went through the audition more than once.

**ONSET**, the singing voices' seventh macro, snaps across none, m, b, d,
h, t, s: the note opens on that consonant. At 0, every preset's setting,
nothing changes: ONSET is left out of the recipe seed, and the Cloud Kit
regenerates to the byte.

- **m** hums through closed lips (F1 only, at 0.55 of the voice) for
  90 ms, then opens. **b** and **d** close for 35-40 ms with a softer
  murmur. **h** breathes through the vowel's shape for 100 ms; **t** is a
  20 ms stop and 60 ms of breath before the voice; **s** hisses (6 kHz)
  for 160 ms. The vowel's envelope starts when the vowel does.
- **No pops.** The first audition measured every consonant 8-38 dB
  louder than its vowel: the envelope started at the strike, and the
  pops and hiss were fixed levels while the vowel comes out of its
  narrow formants about 20 times quieter than it leaves them. Fixed by
  starting the envelope at the vowel and setting the hiss against the
  vowel's own RMS. Then b, d and t were "clucky". Three causes were
  found and measured (a click measure: the largest sample step around
  the release, low-passed at 1.5 kHz, over the vowel's own; a plain
  vowel scores 3.5-5): the envelope dropped to zero at the release and
  re-attacked, the hum's upper formants switched on in one sample, and
  the burst was a narrow 700 Hz "tok". With those fixed ROBOT's "ba"
  went from 28 to 10, CHOIR's from 14 to 4. The audition still heard a
  cluck with the pops at 30%, and none without them and with the mouth
  opening over 90 ms instead of 40: so the pops are gone.
- **b and d without pops** differ where the vowel opens from (F2 800
  against 1800) and in the closure: lips shut let almost nothing above
  F1 through (8%), the tongue behind the teeth leaves the front of the
  mouth bright (60%). Their closures measure 306 against 420 Hz on
  ROBOT, 361 against 507 on GHOST.
- **Vibrato waits for the vowel.** Measured from the strike, a GHOST
  note after an s already swung a full 90 cents as its vowel began.

**BEATBOX** (`VoxBeatbox`), a fourth voice: TUNE, HIT, DECAY, SIZE. HIT
snaps across KICK, three snares (PF, PSH, K), three hats (TS, T, TSS
open) and RIM.

- The first version's snares and hats were filtered noise and "sounded
  basically like the hat and snare in THUMP": the spectral measure put
  them 11.5 and 10.2 dB from THUMP's, the nearest of anything. Rebuilt
  as a mouth: breath through a moving vocal tract (the "psh" opening O
  toward E), a constriction setting the hiss (s ~7-8 kHz, sh ~2.7 kHz,
  f broad), fluttering breath and, on the snares, a trace of voice. The
  hats moved to 18-19 dB from THUMP's hat (its own closed and open hats
  are 8.8 apart).
- "Better but still synthetic and thin": three takes (real, punchy,
  big), and the pick was BIG. Built in: warmer breath that swells after
  the release, spit crackle, a jittering mouth, the voice louder, longer
  and dropped into the chest, the mouth moving further, tails 30%
  longer, and the close mic: an 11 dB low shelf at 250 Hz, saturation
  and a transient shaper. Measured: the snares' brightness moves
  0.39-1.28 against THUMP's snare's 0.24 and carries -0.9 to -2.6 dB of
  its energy under 500 Hz; the hats -14.5 to -16.2, THUMP's hat -40.9.
- **Filed by its hit.** The drum classifier does not hear a mouth's
  drums as what they stand for (the kick reads PERC, the hats and the
  rim SNARE), so `Vox.drumClassFor` files BEATBOX by HIT: KICK, SNARE,
  HAT_CLOSED, HAT_OPEN, PERC. `SynthScreen` passes the knobs through
  when it sends to a pad; the singing voices stay TONAL.
- Twelve presets: three kicks, four snares, four hats, a rim.

### Round 3, THROAT as built — 2026-09-27 ("Weird", first voice)

**Where the behaviours live.** The singers are at the seven-knob budget
(TUNE, VOWEL, BREATH, DECAY, SIZE, GLIDE, ONSET), so none of the four
behaviours fits on them. Asked, the pick was to spread them across the
three new voices as their own knobs and leave CHOIR, ROBOT and GHOST as
they are: THROAT takes GROWL and YODEL; SWARM (STUTTER) and WRAITH
(ALIEN, STUTTER) follow. THROAT was auditioned first.

**THROAT** (`VoxThroat`): TUNE, WHISTLE, MELODY, DRONE, DECAY, GROWL,
YODEL. A low drone (A1 to A3) through a glottal pulse, and above it a
whistle: one harmonic picked out by a pair of 25 Hz resonances in
series, the way a singer merges two formants into one sharp peak.

- **WHISTLE** snaps across the 5th to 13th harmonic; the whistled one
  stands 29-47 dB over its neighbours (a sung one is roughly 20-40).
- **MELODY** walks the whistle up to six harmonics and home; between
  points the resonance glides and the harmonics pop out one by one, the
  sound of the style. At 0 it holds.
- **DRONE** brings up the body: the energy under 600 Hz goes from -26 to
  -8 dB of the whole.
- **GROWL**: every other pulse damped, with rasp shaped by the pulse, as
  the false vocal folds flap at half the pitch. The half harmonics rise
  from -74 dB to within 3 dB of the note.
- **YODEL** flips chest to head and back, up a sixth then an octave,
  long-short like a lilt, with a catch in the voice at each break.

What the audition changed:

- **The whistle's width is fixed.** Offered narrow, medium and wide
  (15, 25, 45 Hz): "I can't tell the difference". Measured, the three
  sat 1.2-1.7 apart on the difference measure, inside its noise floor.
  Medium, and no knob.
- **The yodel was rebuilt.** The first "is strange and doesn't seem
  right": the whistle stayed where it was when the voice jumped, ringing
  between the new note's harmonics, and the head voice was a bare sine
  on no vowel. Now the whistle steps aside while the voice is up, the
  vowel follows the register ("oh" low, "ee" high), the head voice is
  the same pulse softened and breathed, with vibrato on a held note, and
  the high notes make a tune. Heard: "quirky, but interesting", kept.
- **GROWL stayed as first heard.** A deeper one (damping 0.9, rasp 0.7
  against 0.8, 0.35) measured 1.1 from it, which nobody would hear.
- **No SIZE.** THROAT wanted eight knobs; SIZE went, as the whistle and
  DRONE already set the colour.

Filed TONAL like the other singers (short settings read PERC, long ones
LOOP). The "too quiet" checks for VOX now read loudness, as TIDE's do:
levelled to the same loudness, a whistle's steady tone peaks at
0.31-0.5, where the old peak > 0.5 rule would have called it silent.
Twelve presets: drones and whistled melodies, three hits (GROWL STAB,
WHISTLE BLIP, RASP HIT), two yodels and a pad.

### Round 3, WRAITH as built — 2026-09-27 ("Weird", second voice)

**WRAITH** (`VoxWraith`): TUNE, WORD, DECAY, TUNED, ALIEN, STUTTER,
BREATH. Sine-wave speech: no voice at all, three pure tones gliding
along the paths a speaker's first three formants take through a word.
The ear hears whistling until it catches the word; the audition heard
the words as "slightly abstract and left to interpretation, but I think
that is interesting and good", so they stay that way, not pushed toward
plain speech.

- **WORD** snaps across six words (why, you, no, hello, wow, yeah),
  each a path of F1-F3 and openness through its sounds. Slowed, why's
  second tone sits at 540-750 Hz through the w and the ah and lifts to
  2200 for the ee.
- **DECAY** is how long the word takes, about `Vox.lengthFor`: 0.3 s, a
  hit, to 3 s, the word slowed nearly six times into a pad.
- **TUNED** crossfades (equal power) the gliding tones with the same
  tones stepping onto the harmonics of TUNE's note. Asked whether to
  glide or step, the pick was a knob: 0 is ghostly speech, 1 sits in a
  key (every tone measured within 0.8% of a harmonic), between plays
  both. TUNE moves the word and the key note together.
- **ALIEN** moves the tones further than a mouth can (the loudest tone
  travels 2400 Hz over why, against 450 without), pushes F1 up and F2
  down across each other, and gives each a twin a few Hz off, beating.
- **STUTTER** grabs the word's opening up to four times first ("wh-wh-
  why"); one to five bursts, measured exactly.
- **BREATH** turns the tones to whispered noise bands (flatness 0.0001
  to 0.19).

Every difference in the audition measured 21-43 on the difference
measure, well clear of its 1-4.5 noise floor: no "can't tell the
difference" this time. Pure tones have nothing to fold, so WRAITH
renders at the output rate, not the 4x the buzzing throats need (the
longest note renders in about 50 ms); the dispatch test says so and a
test of its own holds it. Filed TONAL (PERC when short, LOOP when
long). Twelve presets across the six words, as hits, chops in key and
slowed pads.

### Round 3, SWARM as built — 2026-09-27 ("Weird", third voice; round 3 complete)

**SWARM** (`VoxSwarm`): TUNE, WORD, CROWD, LOOSE, EFFORT, DECAY, STUTTER.
A crowd: two to sixteen mouths, men and women (the women an octave up),
each with its own throat, detune, pitch wander, timing and place in the
stereo field. Stereo, like CHOIR. It was the voice most at risk of
sounding like CHOIR, so it was aimed at people rather than singers: no
vibrato, speech-like onsets and a shout's rise and fall. Measured
against CHOIR's "aah", 9.1 on the difference measure.

- **WORD** is what the crowd says together: hey, ho, yeah, huh, ooh,
  aah.
- **LOOSE** is the heart of it. Its lower half loosens a chant (the
  opening's rise measured 30 ms tight, 95 at 0.25, 250 at 0.5, as the
  mouths come in over a moment); its upper half turns mouths, one by
  one, to talking: random syllables of consonant loci and vowels, words
  of two to four, pauses between. At 1 it is a murmuring room.
- **CROWD** is 2 to 16 mouths, squared so most of its travel is at the
  small end. Measured on a murmur, the envelope's lumpiness is 0.38 for
  two mouths and 0.17 for sixteen, flat past about ten: you can pick out
  two people, and past eight a crowd is a crowd (8 and 16 measured 1.2
  apart on the difference measure, inside its noise floor). Asked, the
  knob stays, as "a few people to a crowd".
- **EFFORT** goes hushed, talk, shout. A shout is four semitones
  higher, rises and falls on its word, and is brightened and pushed.
  Hushed is a soft voice (a third of its strength, fewer upper
  harmonics) under breath: a library, not a seance.
- **STUTTER** grabs the word's opening up to four times, together when
  the crowd is tight, scattered when it is loose; counted by the silent
  gaps between grabs, exact across nine crowds.

What the audition changed:

- **The quiet end went from whisper to hushed voices, over three
  auditions.** First: "everything sounds great except whisper sounds
  demonic". It was noise through the voice's own narrow resonances,
  ringing into a low moan (-4.7 dB of its energy under 500 Hz, centred
  at 2.2 kHz). Rebuilt to hiss rather than moan (wider, weaker
  resonances, a thinned low end, breath at 2-6.5 kHz: -18 dB under
  500 Hz, centred at 3.8 kHz, a real whisper's range; a first try
  overshot into 10-12 kHz of static), it was "somewhat better, but could
  be a little more tamed". Four takes (softer, quieter, both) all left
  the whispering room "still scary sounding": a room of voiceless
  whispers is a horror film whatever its tone. Asked, the pick was
  hushed voices: a soft voice kept under the breath. A note is found in
  7 of 8 windows of a hushed room, and it stays breathier than talk
  (flatness 0.36 against 0.07). WHISPER ROOM became HUSHED ROOM.
- **The first mouth is always on time**, like CHOIR's middle singer.
  Two or three late talkers left the classifier's first 93 ms silent
  (it read them UNKNOWN), and a pad that starts late sounds late.

Babble searches each mouth's path on from where it last was, not from
the start; the longest preset renders in about 2.5 s here, CHOIR's
longest in about 2. Twelve presets: shouted hits, chants and a cheer,
held stadium vowels, murmuring and hushed rooms, and a stutter.

That completes round 3, and with it S11: three rounds, prototyped and
auditioned by ear before each was built.

### Round 4, SPEAK as built — 2026-09-29

Asked for after round 3: a voice that says short real words more
clearly than WRAITH's and SWARM's abstract ones. The answers that shaped
it: counting words; one knob from a talking machine to a person; for
short pad hits and pitched chops both.

**SPEAK** (`VoxSpeak`): TUNE, WORD, HUMAN, DECAY, SIZE, EFFORT, STUTTER.
A small formant speech synthesizer in Klatt's mould: the buzz and a
breath through five resonances in a chain (so a vowel's formants keep
their natural balance), a separate hiss for s, f, th and v, short
place-shaped bursts for t and k, a nasal pole and zero for n. Each word
is a script of mouth targets. ONSET's "no pops" lesson carried over:
every noise is set against the vowel's own level, not a fixed one.
Mono; TUNE runs C2 to C4, its middle a man's talking pitch.

- **WORD**: one to eight, for count-ins and "five, six, seven, eight".
  All eight were heard clear at the first audition.
- **HUMAN**. The first audition heard the ends "fairly similar": 4.3-4.7
  on the difference measure, just over its noise floor, because pitch
  movement and jitter barely move a long-term spectrum. The machine
  became an 80s speech chip, a raw pulse bright to the top, its mouth
  jumping every 22 ms, its sound held at 10 kHz and 48 levels: 8.3-10.7
  from the person, the middle halfway (5.4-6.7 and 4.8-5.1) once the
  tone was set to turn human later than the pitch does. The person's
  pitch moves through the word (measured 5.5 semitones on "one"),
  wanders and jitters, and the buzz rounds and breathes. Kept as heard.
- **DECAY** stretches the vowels, never the consonants: "fiiive" as a
  chop, about a second at most. Every word files PERC or SNARE at every
  DECAY: SPEAK is hits and chops, not pads.
- **SIZE**, child to giant, the same direction as VOX's. The giant at
  VOX's own reach (formants to 0.47) was heard "muffled rather than
  resonating": 8 dB under the normal voice over 2 kHz. Three takes were
  auditioned; the pick stops the formants at 0.7, rings them on half the
  bandwidth and puts a light chest resonance under them (4.8 dB under,
  measured on "one"). The hisses barely move, so a big mouth still says
  "s".
- **EFFORT**, hushed, talking, shouting, with **INFLECT folded in**:
  four knobs were asked for (SIZE, EFFORT, STUTTER, INFLECT) and the
  budget has room for three. A calm word lifts and falls like a
  statement; a shout opens the jaw, brightens the buzz and climbs
  through the word like "EIGHT!". The machine stays on the note at any
  effort, so a chop stays in key. Hushed keeps a voice under the breath,
  SWARM's lesson.
- **STUTTER**, up to three false starts. Heard slow twice: first at the
  word's own pace (0.31 s a false start), then at 60% of it (0.18 s),
  when "eight" sounded right and the hissing openings of five, six and
  seven still dragged. Each false start is now capped at eight's length:
  0.107-0.110 s on every word.

Twelve presets: plain counts from the chip and the person, a robot, two
shouts and a hush, a giant and a child, a stretched chop and a held
word, two stutters. The longest render takes about 60 ms.

## NACRE — second finite listening prototype

PEARL renders an octave duet from two four-string harps, separate bodies, and eight sympathetic resonators. Model 2 strengthens harmonic touch, finite ringing, shared-current motion, and coral/seaweed material response after the first audition was heard mainly as strum-pattern variation. The topology remains bounded and acyclic outside the passive active-string loops. Saved model-1 recipes retain the exact published renderer and defaults. TOUCH, DUET, CURRENT, VENUE and BLOOM accompany saved MIDI, physical velocity, model and exact seed. A 59-clip audition opens with ten original/revised and fixed-pattern, level-matched tone comparisons, followed by mechanism probes and control sweeps. Six provisional recipes and CLI/recipe/velocity support remain outside the factory roster. Conch, cave, HOLD, the phone picker and held-key integration are deferred; nonzero HOLD is refused. **Listening acceptance pending.** See [NACRE.md](NACRE.md).

## S24 — FLOTILLA, as built

A pool of vessels under a warm dome, added because the screen already
loops whatever macros an engine declares: seven controls fit, so SKIN is
a macro and not a voice setting hidden to satisfy a limit that is not
there.

The mechanical step is 210 Hz so it lands on a whole number of audio
samples. Contacts are refractory for a pair, a splash and the wall. R1's
default RIPPLE measured about eleven contacts; that historical count is
not a current fixture. The pad files LOOP because it rings past 1.5 s.
Turning the surface drive off removes the contacts. A packed pool of
eighteen large hulls starts without overlap.

The pool uses damped modes and exaggerated force coupling. The held note
uses stationary repeated forcing rather than requiring free hulls to return
home. Its seam is checked against real successive resonator/filter periods.
The owner heard R1 and reported static and weak control separation. The
revised voice remains a listening candidate; numerical checks do not replace
that verdict.

## Apsis — first listening prototype

PASSAGE is a sample-free pitched center and stable roster of up to six orbital
resonant bodies, with three participating at default. Safeguarded Kepler timing
drives excitation and contractive internal state exchange. Separate MIDI and
velocity, six movement controls, versioned recipes, six provisional settings,
CLI rendering and a dry starter kit use the existing JVM pipeline. HOLD uses a
commensurate reference-compatible roster and settled adjacent-cycle evidence;
it does not freeze the movement to manufacture a seamless pad.

The first audition compares circular/elongated timing, gathered/staggered
arrivals, population, orbital scale, coupling and an otherwise unexcited
recipient, mechanism simplifications, playing range and held repetition.
The proposed six-voice, 36-preset production bank, phone picker and device
acceptance remain later work. The owner's listening verdict is pending.
Contract and reproduction commands: [APSIS.md](APSIS.md).

Round two responds to weak audible variation: the first full mix put about 99%
of powered spectral energy in the fundamental. Model 2 lowers that masking,
exposes body-specific finite contact resonances and shortens bright-mode memory.
The private study retains an exact original/revised pair plus six same-pitch
preset gestures. Model-1 recipes keep their renderer, including recipes whose
model metadata is absent. Numerical contrast screens supplement stability and
pitch checks; the owner's revised listening verdict and faster rerendering are
still open.

The Apsis musical-bank round now supplies sixteen role-based pads and forty
PCM24 samples, native MPC 3/MPC 2 programs with explicit held-loop flags, seven
dry scores using those exact samples, and native velocity-zone inspections.
Model 3 adds excitation color and a weaker material port alongside the resonant
bridges, plus bounded prepared-motion reuse and a lighter production diagnostic
path. Saved models 1/2 retain their audio. Scoped engine/export checks and
desktop/mobile audition checks pass; physical MPC playback and the owner's
musical-bank verdict remain open.

Android integration adds APSIS to SYNTH with PASSAGE and the six presets,
independent ROOT/VELOCITY, all six movement controls, complete recipe restoration,
SAVE PRESET/YOURS, SPREAD's authored root and SEND TO PAD. Held previews repeat
the settled cycle until STOP. Held pads carry a saved full-sample loop flag and
gate on release; both drum exporters write the corresponding slice-loop and
sustain settings. Finite notes retain complete one-shot tails. Dedicated
keyboard multisampling and physical phone/MPC acceptance remain separate.
