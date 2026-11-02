# CHAMBER — finite quartet instrument

CHAMBER is a finite pitched quartet whose room participates in its decay.
A membrane, bar, cowbell and triangle receive one shared strike timestamp.
Their positions determine direct arrivals, first reflections, boundary
excitation and energy returning to the instruments. Material names describe
designed musical responses, rather than measured building materials.

This stage implements **HYBRID and AQUARIUM**, six factory recipes, complete
patch replay, phone controls and a sixteen-pad dry MPC kit. The supplied
[engineering specification](CHAMBER_SPEC.md) remains the unchanged proposal.
Its other four voices, 36-preset library, expanded room editor and sustained
HOLD operation remain later work. The owner accepted the revised membrane
direction and reported the model-3 mix was better, then judged the wider
upper-register revision "Much better". That response is the working renderer
for new sounds. This does not establish acceptance of every preset or control pair.

The [private audition](https://chamber-snipsnap-audition.bertcalm.chatgpt.site)
provides raw/matched playback, replayable downloads and the finite instrument's
current control study. Earlier timpani comparisons remain archived. The
[original 114-clip study](https://chamber-snipsnap-audition.bertcalm.chatgpt.site/archive/model-1/index.html)
remains available unchanged. Numerical checks do not establish sonic acceptance.

## Finite instrument integration

New patches use model 5: the wider control range together with pitch-aware
strikes and sympathetic boundary responses. The app labels this **WIDER RESPONSE**.
Complete saved recipes keep their explicit model, including versions 1–4;
recalling a preset, editing ROOT or VELOCITY, and sending a sound to a pad never
silently upgrades it. A saved earlier recipe offers **USE WIDER RESPONSE**,
which changes only its renderer and preserves its name, controls, note, velocity,
room, tuning, enabled strikes and exact seed. The complete edited draft persists
immediately and remains editable through navigation, preset saving and pad delivery.
The original six factory recipes and sixteen-pad MPC bank remain explicitly
model 3, so their existing sounds and published kit stay reproducible.

The phone SYNTH picker exposes both rooms, five physical controls, native ROOT
and excitation VELOCITY. HOLD stays hidden because only finite strikes are
supported. Complete recipes retain room dimensions, source/listener positions,
material assignments, tuning, enabled strikes, model and exact seed through
control edits, preset recall, draft restoration and delivery to a pad. SPREAD
uses the saved MIDI root instead of asking a detector to choose between the
membrane, bar and higher metal partials.

Preview rendering runs away from the UI thread. A bounded per-screen cache keeps
only complete full-quality recordings for exact recipe repeats, including
preview followed by delivery to a pad. Its eighteen-second mono PCM budget is
about 3.2 MB; returned arrays are independent copies. New settings still run the
coupled simulation. Superseded work checks cancellation, and cancelled or stale
work cannot replace the current preview. Cache keys include the physical room,
resolved controls, note, velocity, seed and sound-model version.

The production renderer caches unchanged modal-port observations, prepares fixed
listener-delay interpolation and primitive path data once, and skips incremental
waveguide-energy bookkeeping when diagnostics are disabled. Diagnostic renders
retain their complete energy ledger. Four captured model-3 production recordings
(both voices at C3/C4) retain every Float32 bit after these changes; earlier
published model-1/model-2 WAVs have separate compatibility guards.

Desktop production measurements use 4.5-second mono renders, 4x internal
processing, velocity .75, seed zero and default rooms at C3/C4. After two warmup
rounds, twelve fresh recordings took **1.759–2.944 seconds** (median 1.935 s);
twelve exact-recipe cache hits took **0.448–0.781 ms** (median 0.580 ms).
These are Temurin 21.0.13+9 JVM measurements, not phone playback latency.
The pre-change baseline used another JDK patch version and warmup sequence, so
these numbers do not establish a controlled percentage speedup. Fresh settings
remain above the house tens-of-milliseconds goal and require background work;
fast cache hits do not close that remaining render-budget gap.

Reproduce absolute production timings and Float32 hashes with
`./gradlew --no-daemon :synth:benchmarkChamber`. The task writes timings,
preparation/synthesis/output stages and its measurement conditions under
`testkit/chamber-performance/`. The Android regression suite separately logs
cold, warm-fresh and cached times under `ChamberPerformance`, without imposing
a brittle timing assertion or claiming physical-device acceptance.

The six current recipes join the factory roster and FRESH TAPE starter menu.
They keep their existing settings while the refreshed study exposes the wider
listening decision. `ChamberKits` supplies one shared sixteen-pad recipe bank:
eight native HYBRID notes in C minor pentatonic, six factory rooms and two
boundary/placement contrasts. Each pad is a dry tonal one-shot without a choke
group, transposition, effects or sustained loop.

`generateChamberKit` exports the actual native MPC 3 track and MPC 2 program,
PCM24 samples, saved recipes, manifest and checksums in
`SnipSnap-Chamber-Kit.zip`. Automated export read-back and preflight checks
cover file structure and sample references. Physical MPC loading and listening
remain separate acceptance steps.

```sh
./gradlew --no-daemon :synth:generateChamberKit -PchamberKitOutput=/tmp/chamber-kit
./gradlew --no-daemon :synth:generateChamberAudition -PchamberRevision=finite -PchamberOutput=/tmp/chamber-finite -PchamberPrevious=/absolute/model-3-mix-audition -PchamberKit='/tmp/chamber-kit/SnipSnap Chamber Kit'
python3 scripts/package_chamber_site.py /tmp/chamber-finite /tmp/chamber-hosting
```

The final command prepares static hosting within the provider's 256 MiB expanded
archive limit and requires `ffmpeg`. The full generated study remains in WAV.
Hosting converts selected raw diagnostics to lossless FLAC, verifies every
decoded PCM16 frame, and records original/container/PCM hashes in
`hosting-audio.json`. Matched WAVs, featured raw WAVs, starter/MPC downloads and
all historical files retain their bytes. The original manifest is retained as
`manifest-rendered.json`; the hosting manifest names each delivered format.

The original finite study uses model 3 across registers, velocities, presets,
macro sweeps and interacting-control corners. Musical context clips use the same
exported recordings as the MPC kit. Each finite event includes its attack and
decay; neither context playback nor page Repeat demonstrates HOLD. Previous
model-1, model-2 and model-3 comparison assets remain available separately.

### Why it is built this way

- Preserve the accepted membrane voicing while improving the path from controls
  to a saved sampler sound. Render compatibility is checked independently of UI.
- Cache complete recordings because instrument and room states are coupled.
  Geometry caching alone does not remove the expensive synthesis loop.
- Keep native note rendering and sample transposition explicit: the former
  preserves the physical room, while the latter shifts the recorded room too.
- Keep HOLD as a separate DSP milestone. A stable decaying room needs recurring
  excitation and settled state before it can supply a sustained loop.

### Hardware acceptance still required

On a phone, measure fresh and repeated preview latency, change controls rapidly,
leave and restore SYNTH, then send an edited room to a pad and verify its saved
recipe. On MPC 2 and MPC 3, load the exported program, check all sixteen pads,
root notes, overlapping tails and the membrane balance at soft and hard playing
levels. Software builds, emulator checks and format read-back do not establish
these physical-device results.

## Wider endpoints — explicit model-4 listening candidate

The [wider-extremes comparison](https://chamber-snipsnap-audition.bertcalm.chatgpt.site/extremes/index.html)
puts twenty wider endpoints beside twenty existing model-3 endpoints and the two
accepted defaults. It changes one control at a time at C4, velocity .75 and seed
zero. At publication the default remained model 3; the original factory recipes
and MPC kit remain pinned there. Model 4 stays available for exact saved replay;
new sounds now use its wider controls through the register-aware model 5.
Saved models 1–3 keep their renderer laws.

The five controls reach different underlying quantities:

| Control | Wider candidate behavior |
| --- | --- |
| STRIKE | Soft contact can reach twice the old duration, with less upper-mode participation; hard contact reaches a quarter of the old duration with an 80 μs minimum. Soft extension is bounded by each source's actual tuned period. |
| BALANCE | The excitation floor falls from .17 to .025 and the focus width from .69 to .30 at the endpoints. A small low-pitched anchor remains while the selected source becomes more distinct. |
| MATERIAL | Boundary decay scales from .22× to 2×, capped at three seconds; reflection cutoff scales from .30× to 1.30×. These are designed responses, not measured materials. |
| DIVIDE | Normalized geometric coupling weights become more blended or local, with powers .50 to 2.50. Material weights remain convex and the scattering network remains passive. |
| PLACEMENT | Sources move nearer or farther around the fixed listener. A common scale respects wall, source and listener clearance. Authored `EXPLICIT` positions remain unchanged. |

MATERIAL and DIVIDE also bring the room pickup forward by up to 10×, outside
feedback. This changes the room/source proportion, rather than master gain;
matched playback and normalized spectral comparisons separate timbre from level.
Each voice's accepted default follows the unchanged renderer path exactly.
All new endpoint constants are shape choices awaiting listening acceptance.

Local verification covers 61 distinct CHAMBER tests across the full focused run
and a subsequent rate-reference run. Published model-1/model-2 WAVs and four
accepted model-3 Float32 production hashes remain exact. Both wider voices pass
the existing high-register 4×/8× pressure and spectral targets without relaxing
their margins. These are JVM measurements, not listening or phone results.

The study measures attack, body, early answer and late-tail windows separately,
plus cumulative-energy times and requested-root/octave-down tone shares. Complete
energy ledgers cover the new endpoints and combined extremes. Upper metal sources
can cross a half-sine contact notch as STRIKE changes at high notes; separate
diagnostics inspect that transition. The membrane/bar root guards do not imply
uniform triangle-root strength throughout the control range.

```sh
./gradlew --no-daemon :synth:generateChamberExtremesAudition -PchamberExtremesBaseline=/absolute/finite-WAV-study -PchamberExtremesOutput=/tmp/chamber-extremes -PchamberExtremesExtendedChecks=true
python3 scripts/package_chamber_extremes_site.py /absolute/current-hosted-dist /tmp/chamber-extremes /tmp/chamber-extremes-hosting
```

The full generated study stays in WAV. Hosting encodes only its new raw/matched
recordings in lossless FLAC, verifies every PCM16 sample and references the prior
recordings in place. Existing archives, recipes and listening notes are retained.
Fresh settings still require the coupled simulation; this is a sound-range study,
not a claim of new phone latency or hardware acceptance.

### Why these ranges are separate

- Preserve the accepted center and exact saved sounds while testing stronger edges.
- Change contact, source participation, boundary behavior and geometry so overall
  volume alone cannot account for the variation.
- Keep observer pickup outside feedback so a more audible room does not inject
  energy into the passive physical network.
- Compare a small set of endpoints before changing the current factory library.

## Upper register — heard working response

The owner's wider-range listen identified a weaker resonant answer on higher
notes. A Kotlin/JDK17 baseline at `efb365650` rendered 36 complete 4.5-second
recordings. At MATERIAL 1, model 4's cumulative-energy T90 falls from 1.431 s
at C4 to .343 s at C6 in HYBRID, and from 1.256 s to .399 s in AQUARIUM.
Room/direct RMS falls from 3.123 to .071 and from 1.255 to .017 respectively.
T90 measures the complete mix's energy distribution; it is not material T60.
The individual source decay times change little. Fixed boundary resonances
therefore lose their contribution as the requested note rises.

There is also a distinct excitation defect: the old .5 ms minimum contact can
span more than two periods of the high triangle's actual fundamental. A long
half-sine force can cancel that fundamental even when its resonator has a long
decay. Increasing decay alone cannot restore energy that was never excited.

The register revision keeps the wider controls and makes these preparation changes:

- Above C4, shorten the legacy minimum contact with pitch so the body's upper
  modes keep receiving excitation. Also bound contact by each source's actual
  tuned period, following STRIKE toward a shorter hard contact. The .8-period
  soft/default cap and .2-period hard cap are voicing choices. The owner judged
  the resulting upper-register comparison "Much better".
- Above C4, transpose the boundary modal frequencies with the requested note.
  This is a musical adaptation of the designed material response. Physical room
  dimensions, propagation delays and existing damping times stay fixed;
  it does not claim that a real room retunes itself.
- Fade a note-aligned sympathetic root into each boundary between C4 and C5.
  Its decay follows the material mixture's first mode, alongside the existing
  material families. The enlarged modal port stays unit normalized and exchanges
  existing energy without another strike. This costs at most ten additional modes.
- Give the high-register triangle's fundamental up to 25% more modal weight,
  with the same unit port normalization, to retain its requested pitch when the
  shorter contact also excites more upper modes.
- Calibrate only surface and late-field pickups with the square root of the
  upper-register note ratio, bounded at 2 at C6. This is an observer voicing
  choice, like the existing boundary and membrane pressure calibrations. It
  changes the returning room's proportion without changing network energy,
  direct sound or early reflections.
- Scale all upper-register observer pressure by the inverse of that note-ratio
  square root to reserve PCM headroom for the restored transients. This bounded,
  prepared calibration uses no measured peak, clipping or runtime limiter.
  Matched playback retains the same room/body proportions.

These changes prepare the existing simulation and add no separate audio-rate
stage. Exact-recipe cache identity already includes note and model.
Fresh renders retain the coupled simulation's existing cost; no new phone
latency or hardware acceptance is claimed. Model 5 is now the default for new
sounds; model 3 remains pinned in the original factory recipes and kit, and all
earlier sound revisions remain separately replayable. The listening verdict
applies to the published comparison rather than every possible control setting.

The paired study compares the previous wider response with this candidate at
C4, C5 and C6, holding MATERIAL at 1 and retaining the voiced defaults elsewhere.
The complete-recording guards require each C6 answer/attack ratio to exceed its
previous value by 25% and retain at least half of C4's relative answer, with T90
at least 60% of C4. These are declared engineering targets, not listening verdicts.
MATERIAL contrast is measured across the entire .55–2 s tail with a joint
time/frequency distribution normalized once for the full window. Counterexamples
reject unchanged audio and gain alone while detecting same-pitch decay changes.
A single short normalized spectral slice remains an informational descriptor;
it cannot by itself determine whether a pitched decay has changed.

```sh
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Chamber*Test'
./gradlew --no-daemon :synth:generateChamberRegisterAudition -PchamberRegisterBaseline=/absolute/register-baseline -PchamberRegisterPublished=/absolute/extremes-WAV-study -PchamberRegisterOutput=/tmp/chamber-register
python3 scripts/package_chamber_register_site.py /absolute/current-hosted-dist /tmp/chamber-register /tmp/chamber-register-hosting
```

Final exports require a clean source commit. The hosting step verifies every
lossless FLAC sample and seek table, preserves prior recordings, and reuses
byte-identical source snapshots through explicit hash-checked hosted paths.

The integration adds complete-recording Float32 goldens captured before promotion
from both revised C6 clips and both previous wider C6 clips. New patch and public
render defaults must select the heard renderer; complete saved versions 1–5 must
survive JSON, control edits and pad recipes without migration. Cache keys separate
all five versions. Historical performance exports validate against the original
kit's model rather than the runtime default, so promoting new sounds cannot relabel
old recordings. Focused numerical and Android build results are recorded separately
from the user's listening verdict; fresh physical-device timing remains unverified.

## Timpani mix lift — model 3

The owner heard model 2 and approved its sound, then asked for a little more
presence in the quartet. Model 3 applies **+3.000 dB** to the membrane listener
pickup (`10^(3/20)`), preserving model 2's tuning, modal weights, excitation,
decay and physical room coupling. Before the shared output levelling, other
source pickups and the surface/late network observers retain their levels.
The complete network energy states are unchanged.

All **39 focused CHAMBER checks pass**, including measured 3 dB membrane gain,
waveform-shape preservation, identical room-energy ledgers and exact published
model-1/model-2 WAV replay. The full-room pitch checks pass at MIDI 36/48/60/84
in both rooms.

At that milestone new patches used model 3. Saved model-1 and model-2 recipes continue to reproduce
their original sounds; captured published WAV hashes guard both versions.
The page compares accepted model 2 with boosted model 3 at C3 and C4, including
full quartets and dry timpani solos at one shared gain per room/note group.
The accepted page remains archived separately, with its original audio, recipes
and source records. Its archive-navigation link is relocated to the preserved
model-1 study; the original model-2 manifest is retained alongside it.

```sh
./gradlew --no-daemon :synth:generateChamberAudition -PchamberRevision=timpani-boost -PchamberOutput=/tmp/chamber-timpani-boost -PchamberPrevious=/absolute/model-2-audition
```

The original model-1 study stays at its existing archive URL. The previous
model-1/model-2 comparison remains reproducible with `chamberRevision=timpani`.

## Timpani revision — model 2

The owner heard model 1 and reported: **“The timpani doesn't come through at all.”**
Its direct membrane body was about 21 dB below the bar over 50–500 ms, and its
pitched decay disappeared rapidly. The 12/s acoustic port load overwhelmed the
membrane's intrinsic decay; sharing the bar's root made the weak body easier to mask.

Model 2 reduces that load to 0.5/s, lengthens intrinsic membrane decay from 1.15
seconds to 1.65 seconds, and gives the near-integer upper modes more presence.
Half-integer modes remain at lower participation to avoid octave ambiguity.
A separate 24× membrane radiation calibration restores its listener level. This observer
calibration affects direct and reflected pickup, never feedback energy or strike
work. The other three dry source banks and random realizations remain unchanged;
room returns respond to the revised membrane coupling. The tuning ratios remain
1×, 1×, 2× and 4×; no hidden octave shift supplies the improvement.

At default C3/C4 settings, the direct membrane body over 50–500 ms is now
about **2.9 dB below the bar in HYBRID** and **2.1 dB below it in AQUARIUM**,
including source-distance pickup gains. It was about 21 dB below before.
Full-room pitch probes at MIDI 36, 48, 60 and 84 retain the requested root in
both rooms; the largest detected deviation is 17.8 cents. These measurements
verify level and tuning guards, rather than replacing the owner's listening verdict.

Model 2 introduced this voicing. **Saved model-1 recipes retain model 1** through decoding,
macro edits, native velocity and CLI replay. Two captured model-1 recipes and
published raw-WAV SHA-256 values guard exact reproduction of the original sounds.

All **38 focused CHAMBER checks pass** for this revision, covering DSP, room
geometry, patch/preset/CLI replay, original model-1 WAV hashes, dry-source isolation
and the new membrane body guard. The ensemble pitch check now also covers C3.

The revised page features C3 quartets and provides C3/C4 comparisons in each room.
Each four-clip group uses one gain derived from its revised full quartet. Previous
quartets and both membrane solos receive that same gain and tail fade. Solos keep
their actual quartet strike strength and balance; they are never independently
boosted. Revision-specific IDs preserve the original listening notes and avoid
reusing old audio URLs. The complete original page, audio, recipes and rendering
source snapshots are archived separately.

Reproduce the focused revision from a retained original render directory:

```sh
./gradlew --no-daemon :synth:generateChamberAudition -PchamberRevision=timpani -PchamberOutput=/tmp/chamber-timpani -PchamberPrevious=/absolute/model-1-audition
```

Omit `chamberPrevious` for the standalone 16-clip revision without the archive.
The default generator still produces the complete control matrix using the current
model. The compact starter ZIP contains the 16 revision clips and their sources;
it excludes the archived original matrix.

## Controls and complete recipes

STRIKE changes contact hardness and excitation. BALANCE moves through overlapping
instrument weights, retaining a presence floor for enabled strikes. MATERIAL
continuously traverses WOOD, CONCRETE, METAL, GLASS and SUBMERGED_GLASS. DIVIDE
crossfades a uniform global response toward independently assigned regions.
PLACEMENT moves the four instruments along a continuous, bounded path while
the listener stays fixed. All controls use 0–1 values.

HOLD is present in the recipe contract at zero. **Any positive HOLD is rejected.**
The first prototype renders a finite strike and decay. An audition page's Repeat
control repeats that complete clip, including its attack; it does not demonstrate
a sustained instrument, settled loop or held keygroup.

`ChamberPatch` saves the requested MIDI root (36–84), velocity (0–1), model version,
signed 64-bit seed, controls and complete `ChamberConfig`. The seed is serialized
as canonical decimal text to preserve every bit through JSON. Zero velocity is
silence. Tuning defaults are membrane 1×, bar 1×, cowbell 2× and triangle 4× the
requested root; the four ratios and per-instrument strike-enable flags are saved.
An unstruck instrument can still respond sympathetically through the room.
With all four strikes disabled, a fresh room stays at rest. Velocity changes
physical excitation effort, hardness and the upper modes it excites. Raw audition
files preserve the resulting energy differences; production rendering levels the
finished sound to SnipSnap's shared melodic target.

Configuration also saves physical room dimensions, normalized listener and source
positions, placement endpoints and mode, material mode and policy, layout,
contrast palette, split height, blend width and all ten region targets. Macro
edits preserve that authored configuration. Derived delays, filters, modal states
and traveling energy are rebuilt deterministically.

`ChamberPresets` contains **Four Answers**, **Soft Split**, **Glass Corners**,
**Aquarium Ceiling**, **Quiet Canopy** and **High Water**. The recipes are
available to the CLI, patch and recipe decoders, user presets, native velocity
adapter, shared factory roster, Android engine picker, FRESH TAPE and
`SynthKits.chamber()`. Their broader musical acceptance remains open.

## Geometry and material policy

The rectangular room has four walls, a floor and a ceiling. Each wall has lower
and upper assignments, giving ten stable regions. HYBRID explicitly assigns two
wood walls and two glass walls, with independent concrete floor and glass ceiling.
AQUARIUM assigns concrete to lower walls and floor, submerged glass to upper walls
and ceiling. Its horizontal split is editable.

There are four direct paths and 24 first-order image-source reflections. Each
reflection selects its material at the actual boundary intersection. A narrow
physical band blends adjacent lower and upper responses at the split. Forty
source-to-region connections supply propagation delays and position-dependent
weights to boundary excitation and returning energy. Placement therefore changes
more than the listener's short delay taps.

At DIVIDE=0 all regions follow MATERIAL. At higher DIVIDE, each region crossfades
the five parallel material responses toward its own target. In `MACRO_LAYOUT`
mode, saved low/high palette values and `UNIFORM`, `OPPOSING_WALLS`,
`ALTERNATING_WALLS` or `LOWER_UPPER` derive those targets. In `EXPLICIT_TARGETS`
mode, the ten saved values supply them. Both required voices use explicit targets.
The serialized `COMMON_OFFSET` policy shifts each target by
`(MATERIAL - materialReference) * materialOffsetScale`, bounded to 0–1, before
the DIVIDE response crossfade. Defaults preserve the named targets at each voice's
reference material setting. No global material average replaces early paths or
surface assignments; averaging is a diagnostic comparison only.

`MACRO_PATH` placement interpolates between saved source endpoints in physical
space; `EXPLICIT` uses the saved starting positions directly. Physical positions
keep at least 0.18 m clearance from boundaries. Authored paths that pass within
0.25 m of another source or the fixed listener are rejected. Attenuation uses a
minimum 1 m distance, preventing singular gain near a pickup.

## Feedback and listener branches

The feedback model includes instrument modes, boundary modes, both directions of
all forty source/region connections, region/late-field connections and eight
shared late delay lines. Each modal bank evolves through damped rotations in
energy coordinates. Unit-normalized modal and wave ports exchange energy through
orthogonal rotations; an orthogonal shared scattering junction mixes the late
field. Causal integer delays and losses at most one complete the passive network.
Between finite primary strike increments, its modal and traveling energy cannot
grow apart from floating-point storage rounding. Diagnostics account for the
complete network and the finite strike-work budget.

Direct pickups, filtered first reflections and boundary radiation are observer
branches. SUBMERGED_GLASS adds a delayed, gently modulated secondary boundary
response there. This time-varying branch is excluded from feedback and its energy
proof. Its wavering character belongs to the canopy response; the quartet keeps
its own dry identity. Listening comparisons disable returns, average boundaries
or freeze the secondary modulation to expose each mechanism.

## Playback and reproduction

Each rendered note contains its own room. A chord made from several clips plays
several independent room recordings. Rendering another requested note keeps
the saved room's physical resonances and dimensions. Transposing an already
rendered sample shifts the room along with the instruments. A shared room across
live notes would require a different playback adapter.

From the repository root:

```sh
./gradlew --no-daemon test
./gradlew --no-daemon :synth:generateChamberAudition
./gradlew --no-daemon :cli:run --args='synth CHAMBER HYBRID --all --midi 60 --velocity .75 --seed 0 --out /tmp/chamber-hybrid'
./gradlew --no-daemon :cli:run --args='synth CHAMBER AQUARIUM --preset "Aquarium Ceiling" --midi 48 --velocity .65 --seed 41 --out /tmp/chamber-aquarium'
./gradlew --no-daemon :cli:run --args='synth CHAMBER HYBRID --patch /absolute/path/to/chamber-patch.json --out /tmp/chamber-replay'
```

`--patch` requires the requested voice to match the saved recipe. Optional
`--midi`, `--velocity` and `--seed` overrides retain its complete room. The audition
task writes generated audio, recipes, diagnostics and a listening page under
`testkit/chamber-audition/`; those outputs and the generated MPC kit are ignored by
Git. The page source is retained with the test resources so regeneration can
reproduce the listening interface. No phone or MPC hardware acceptance is claimed.


## Original model-1 validation (2026-10-18)

The original audio was rendered from clean engine commit
`4621959c5b66869f9aaeb04cecb8669a6faabd08`. All **114 requested cases passed**.
A second, separate JVM render reproduced all **228 raw/matched WAVs byte for
byte**. The manifest records each recipe and WAV hash; `source-provenance.json`
and `source/` preserve the rendering source. The manifest's presentation hashes
identify the published page, whose browser icon was added after audio rendering.

All **35 CHAMBER checks** passed across DSP, geometry, complete patch replay,
diagnostic PCM replay, saved presets and CLI integration. Six other module
suites passed **1,344 tests**. The Android debug build and native CI tests passed;
the full JVM, Android and native baseline subsequently passed at commit `d49f0b26`
in [CI run 39339176543](https://github.com/BertCalm/SnipSnap/actions/runs/39339176543).
The original implementation landed through [PR #574](https://github.com/BertCalm/SnipSnap/pull/574).

Default full mixes at MIDI 36, 60 and 84 pass the existing melodic classifier
guard for both voices. At the maximum-control MIDI-84 comparison, the 4× versus
8× reference spectral cosines are 0.999972 (HYBRID) and 0.999850 (AQUARIUM),
with RMS ratios 0.9983 and 0.9603. This comparison includes integer-delay timing
interference and is not a direct measurement of alias power. Six maximum-control,
full-velocity probes show no unforced network-energy increase; final/peak energy
at six seconds is below 0.000058, with unscaled peaks below 0.037.

Chromium browser checks passed at 1440 px desktop and 390 px mobile widths:
real WAV decoding, one active source, pause/resume/seek, exact finite-clip repeat,
raw/matched switching, keyboard stop, filters, persistent notes, JSON export and
downloads. No layout overflow, console errors or failed requests were observed.
To open the downloaded starter page locally, serve its directory over HTTP
(for example `python3 -m http.server`); it loads the included JSON manifest.
