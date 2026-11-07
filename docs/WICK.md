# WICK — engine and factory bank

WICK is a pitched, offline instrument built around one shared complex source
and three parallel low-pass gates. Actual gate releases schedule later
excitation in the next gate. Each stage has separate charge and available
excitation; repeated pulses deplete that availability, which recovers over
time. This is an articulation model, not a claim of physical energy conservation.

The supplied [engineering specification](WICK_SPEC.md) is preserved as the
proposal. WICK implements **PLUCK, WOOD, BREATH, BUBBLE, FLICKER and RATTLE**,
a shared factory bank of **36 editable patches, six per voice**, and a dry
sixteen-pad kit. The first 27 recipes retain their settings. Nine additions
complete the bank and await owner listening; acceptance of the voices and
three RATTLE bodies does not establish a verdict on these new recipes.
Numerical checks do not establish that the cascade is musically convincing.

The accepted [RATTLE bodies](WICK_RATTLE_PATCHES.md) are normal saved patches:
Warm wire is the new default, with Hollow reed and Rubbed glass as alternate
sources. The retained [source comparison](WICK_RATTLE_SOURCES.md) preserves the
accepted audio and earlier body. Existing saved RATTLE JSON without a body
field retains its earlier source; new patches save their choice explicitly.

## Controls and recipes

SPARK changes initial and recurrent excitation hardness. FEED supplies bounded
recurrent pulses. CASCADE transmits release events between gates; zero disables
that transmission. TIMBRE changes the shared oscillator's harmonic complexity.
RECOVERY changes depletion memory and associated tail behavior. HOLD's top
step returns settled repeating material.

The six controls are normalized 0–1. Requested MIDI root (24–84) and velocity
(0–1) are saved separately in `WickPatch`. Internal oscillator phases, gate
states and event queues are rebuilt deterministically, not serialized. Zero
velocity is silence, including held operation.

BUBBLE's listening-driven default now uses FEED **.25**, giving the answering
gates more space after feedback that the original sounded chaotic. Its other
controls remain SPARK .40, CASCADE .70, TIMBRE .45, RECOVERY .50 and HOLD 0.
The provisional **Busy Bubbles** recipe retains FEED .65. **Spent Bubbles** and
**Held Bubbles** also retain their explicit, denser settings. These recipes use
the current gate response. Saved patches keep their recorded controls; the
supplied engineering specification remains the original proposal.

After feedback that the note was masked by rapid pops, BUBBLE's primary gate
now rings longer, its attacks round off more slowly, and the answering stages
sit below the primary tone. Primary pitch tracking keeps the longer midrange
ring bounded in the bass. Its finite render has another .4 seconds of decay;
excitation admission still ends at the previous time. PLUCK keeps its response.

The retained BUBBLE comparison section compares **Earlier pops** and **Longer pitched
tone** at MIDI 48, 60 and 72, with the same pitch, velocity and all six controls.
Diagnostic `legacyBubbleEnvelope=true` reproduces the response from source
revision `140d951a72aaca5f3a42af6b33c484bf4b645d16` for those references. Its
reference recipes save that option explicitly; the candidate uses a normal
Wick patch. Short-window energy and requested-root measurements help compare
the note's body after the attack. The owner preferred this new BUBBLE direction,
which remains the working baseline. The owner also accepted WOOD and the
three-voice musical phrases.

WOOD aims for a dry pitched wooden knock with smaller answering rebounds. It
uses the same causal gate network with its own source ratio, body harmonics,
gate timing, darker opening response and stage balance. Its default is SPARK
.60, FEED .15, CASCADE .40, TIMBRE .40, RECOVERY .40 and HOLD 0, rooted at MIDI 48.
The established recipes are **Dry Wood**, **Soft Body**, **Small Rebounds**
and **Held Wood**. The wood character is a listening target rather than an
acoustic-body simulation.

BREATH targets a soft pitched swell with overlapping gate answers, rooted at MIDI 60.
Its default is SPARK .20, FEED .60, CASCADE .35, TIMBRE .20, RECOVERY .70 and HOLD 0.
The established recipes are **Open Air**, **Single Breath**, **Layered Air** and
**Held Breath**. “Breath” describes the opening gesture; it is not a simulated
wind instrument or a noise source. See the [design notes](WICK_BREATH.md).

FLICKER targets brief bright metallic openings around a pitched body, rooted
at MIDI 60. Its default is SPARK .55, FEED .45, CASCADE .65, TIMBRE .80,
RECOVERY .35 and HOLD 0. The established recipes are **Metal Flash**, **Single Glint**,
**Bright Chain** and **Held Flicker**. See the [design notes](WICK_FLICKER.md).

RATTLE targets a pitched strike followed by clustered pulses that weaken under
repeated feeding and recover between admissions. Its controlled default is
SPARK .65, FEED .50, CASCADE .65, TIMBRE .45, RECOVERY .65 and HOLD 0, rooted
at MIDI 60. **Warm Wire**, **Hollow Reed** and **Rubbed Glass** save the three
accepted source choices at those controls. **Depleted Rattle**, **Small Rattle**
and **Held Rattle** use Warm wire with their existing control settings. The dense
supplied setting is retained as Depleted Rattle. See the
[saved-body integration](WICK_RATTLE_PATCHES.md) and original
[design notes](WICK_RATTLE.md).

After the first RATTLE audition, the owner described the result as one voice
being hit repeatedly and asked for a more cohesive character. The earlier
cohesion revision kept the controls and reserve network while softening openings
and retaining more of the primary body between pulses. The opening comparison
plays **Earlier separate hits** and **Connected body** at C3/C4/C5 with matching
controls. `legacyRattleEnvelope=true` reproduces the earlier response as an
explicit diagnostic recipe; ordinary RATTLE patches use the new response.
Both sides retain the earlier source through an explicit `CURRENT` override;
this is a historical envelope comparison. The five accepted voices retain their render paths. See the
[cohesion revision](WICK_RATTLE_COHESION.md) for its evidence and limits.

`WickPresets` now supplies the shared `Presets.forVoice("WICK", voice)` factory
route, the CLI and the Android synth picker. Each voice has six recipes. The
nine additions are **Quiet Cinders**, **Bright Relay**, **Hard Grain**, **Hollow
Steps**, **Clear Beads**, **Faint Current**, **Warm Drift**, **Distant Metal** and
**Quick Shards**; the audition identifies them separately for listening.

Android retains the complete typed patch through macro edits, drafts,
navigation, save and send. It exposes the shared ROOT control at MIDI 24–84,
native VELOCITY, and RATTLE's BODY choices: Warm wire, Hollow reed and Rubbed
glass. A recalled earlier body remains identifiable as Previous body. Native
velocity changes excitation inside the engine rather than applying only an
output gain. Authored roots travel with sent pads and SPREAD uses the saved
MIDI root instead of redetecting it. `UserPresets` roster exports reconstruct
the full patch JSON, preserving body, root, velocity and controls.

The FRESH TAPE menu now offers WICK under MOVING AND STRANGE. `SynthKits.wick()` supplies sixteen
dry pads with regenerable recipes: the five other voices at C3/C4, all three
RATTLE bodies at C4, and Held Bubbles, Held Breath and Held Rattle. Held Flicker
remains available in the library and audition. The 36-recipe factory bank is
larger than the kit; eleven exact library recipes and five transposed default
variations occupy its sixteen pads.

```sh
./gradlew --no-daemon :synth:generateWickAudition
./gradlew --no-daemon :synth:generateWickKit
./gradlew --no-daemon :cli:run --args='synth WICK BUBBLE --all --midi 48 --velocity .65 --out /tmp/wick'
./gradlew --no-daemon :cli:run --args='synth WICK WOOD --all --midi 60 --velocity .75 --out /tmp/wood'
./gradlew --no-daemon test
```

## Listening evidence

The audition renders the real Kotlin engine with an empty FX chain. It contains
native and matched audio, all six voices, factory recipes,
five-step macro sweeps, pitch/velocity probes, interactions and held examples.
Mechanism comparisons disconnect the cascade, force constant availability, or
replace correlated brightness/gain with gain-only gating. Diagnostic JSON
records event parent stages, release and admission times, requested/admitted
strengths, availability and bounded-state measurements. Ablation recipes retain
their diagnostic options separately from normal production patch JSON.

Seven dry musical phrases use a two-bar score at 100 BPM: the same notes,
onsets and velocities rendered separately by PLUCK, WOOD, BREATH, BUBBLE, FLICKER and RATTLE,
plus the retained phrase sharing those events across PLUCK, WOOD and BUBBLE. Each note is a
fresh pad render; recovery state is internal to that note. One fixed assembly
gain preserves differences in strike strength. Loudness matching applies to
the completed phrase. Sequence recipes save the score and every note's exact
patch so the arrangement can be regenerated.

The [private listening page](https://wick-snipsnap-audition.bertcalm.chatgpt.site)
asks whether the spark starts an audible chain, feeding exposes depletion,
RECOVERY changes later articulation, and TIMBRE stays audible through the gates.
Feedback stays in the listener's browser and can be downloaded as JSON.

## HOLD and playback

HOLD must retain recurrent SPARK activity and a declared excitation floor when
FEED is zero. The renderer checks independently generated consecutive audio
and complete network state within a bounded search. The manifest identifies
the actual strategy used by each clip, including any periodic event replay
derived from a causal run. Such replay is an explicit periodic constraint;
live gate charge, depletion, recovery and filtering remain active.

The seam is measured using genuine preceding-cycle context, including PCM16
quantization. Repeating one copied buffer is not convergence evidence.

A held pad contains sustain material only. The audition's Repeat control loops
it in the browser; a normal exported kit pad plays its buffer once. An initial
strike followed by a sustaining keygroup needs a separate instrument adapter.
No Android device or MPC hardware acceptance is claimed in this round.

## Render cost

The performance change hoists fixed voice/stage gate coefficients out of the
per-sample three-gate loop. It retains the original expressions and sample
arithmetic order. An isolated actual-Kotlin comparison against baseline
`e634076db9fdf5a0ca9a8aacbbbcb5193c7bd3d4` checked 50 default, held, body,
register, macro-edge and legacy-envelope cases. Raw float samples, levelled
samples, genuine held contexts and diagnostics matched exactly in every case.
This is evidence for that optimization; completion build and export outcomes
are recorded separately in the generated validation reports.

Separate cloud JVM processes used Temurin 21.0.13, x86_64, two active processors
and JFR profiling. Both ran the same 50-case sweep before seven timed renders
per case. Median time reductions across 18 cases ranged from **3.25% to 14.40%**,
with a median reduction of **8.79%**. The [recorded comparison](benchmarks/wick/gate-hoist.json)
retains the baseline/source hashes, method, case medians and p95 values. Default-voice medians were:

| Voice | Finite before → after (ms) | Held before → after (ms) |
|---|---:|---:|
| PLUCK | 211.1 → 188.7 | 921.6 → 858.4 |
| BUBBLE | 286.8 → 261.8 | 789.9 → 738.3 |
| WOOD | 259.6 → 246.7 | 2834.3 → 2742.2 |
| BREATH | 348.9 → 298.7 | 2445.1 → 2161.5 |
| FLICKER | 182.1 → 166.0 | 2375.5 → 2209.5 |
| RATTLE (Warm wire) | 251.4 → 228.0 | 963.3 → 867.3 |

The tens-of-ms interaction target is still unmet in these cloud measurements.
WOOD, BREATH and FLICKER defaults still take 15 held-search attempts. Physical
phone render speed and MPC loading/playback have not been tested.

To record a fresh portable JVM profile from the checkout root:

```sh
./gradlew --no-daemon :synth:benchmarkWick \
  -PwickWarmups=1 -PwickRepetitions=7 \
  -PwickSourceRevision="$(git rev-parse HEAD)"
```

The task writes `testkit/wick-audition/render-profile.json` by default; override
it with an absolute `-PwickProfileOutput=/path/to/render-profile.json`. It
measures finite and held defaults for all six voices plus both alternate
RATTLE bodies. The report includes the initial render separately, subsequent
warm-up and sample counts, every timed value, median/p95, frame counts, hashes,
held strategy and runtime metadata. Its first render is per case in one JVM,
not an independently cold process. Hashing and JSON generation sit outside the
timed `Wick.inspect` call. With seven samples nearest-rank p95 is the maximum;
the report is timing evidence rather than a pass/fail speed gate. This portable
task has a different warm-up protocol from the isolated before/after study and
does not reproduce that study's exact timings or establish phone performance.

## Remaining physical acceptance

Physical phone and MPC hardware are unavailable in this cloud session. A
software emulator can check Android behavior separately; it cannot establish
phone render speed or MPC playback acceptance.

- On a phone, preview all six voices, edit ROOT and VELOCITY, select all three
  RATTLE bodies, save/recall each choice and SEND it to a pad. Confirm the saved
  recipe and played sound retain the selected note, strike and body. Record
  finite and held render timing with the production instrumentation below.
- On MPC hardware, load both the MPC 2 program and MPC 3 kit exports. Check all
  sixteen pad names, the three RATTLE bodies at C4, relative levels, choke
  behavior and one-shot playback. Compare the exported audio with the audition.
- HOLD exports a sustain loop buffer. An ordinary kit pad plays that buffer
  once; repeating it requires manual repeat or a separate looping adapter.

With one target phone connected, run:

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.WickRenderBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.wickProfile=true \
  -Pandroid.testInstrumentationRunnerArguments.wickRuns=3
```

The opt-in test warms each case once, times three fresh renders and reports
device identity, every sample and the median/max for all six voices and three
RATTLE bodies, finite and held. It excludes assertions, playback and disk IO
from timing. Results appear in instrumentation output and the target app's
cache as `wick-render-profile.json`. For an already installed debug/test APK
pair, the same measurement can run directly:

```sh
adb shell am instrument -w \
  -e class com.snipsnap.app.WickRenderBenchmark \
  -e wickProfile true -e wickRuns 3 \
  com.snipsnap.app.brassgrow.test/androidx.test.runner.AndroidJUnitRunner
```

## Acceptance status

Implementation measurements and checks are recorded beside the generated
manifest and on the audition page. PLUCK, the revised BUBBLE direction, WOOD
and the three-voice phrases have received positive listening feedback. BREATH is now accepted as a working voice.
FLICKER is also accepted as a working voice. The first RATTLE response was
judged too much like repeated hits. After the cohesion revision, the owner asked
for a different underlying tone, liked the three source alternatives, and
approved Warm wire as default with Hollow reed and Rubbed glass as saved variants.
The nine factory-bank additions have not received owner listening feedback.
The short-window classifier can
mislabel a late BREATH swell when the window ends before any real decay;
its raw label and direct envelope evidence are retained in diagnostics.
The source names, constants, voice settings and proposed release identity are
provisional.
