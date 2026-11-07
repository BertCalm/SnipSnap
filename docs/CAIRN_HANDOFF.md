# Cairn engine handoff

As of **2026-11-07**. This document is the starting point for continuing Cairn
without the chat history. The latest sound change is committed, merged and green
in CI. The new held SING motion still needs the owner's listening verdict.

## Start here

1. Read [AGENTS.md](../AGENTS.md) and its Luthier/Steward entry points.
2. Listen to the [held SING before/after](https://cairn-voice-comparison.bertcalm.chatgpt.site/sing-motion/).
   Start with **Hear SING's new motion**, then **The steady rim**. Compare C3,
   C4 and C5; GROOVE has separate low/middle/high recordings.
3. Read [the held-motion design and evidence](CAIRN_SING_MOTION.md).
4. Continue from the current default branch. Preserve the four distinct voice
   roles and the rendering of saved earlier models.

Repository: <https://github.com/BertCalm/SnipSnap>.
The default branch is `claude/mobile-mpc-drum-sampler-t58x74`.

## Commits and integration status

| Item | Reference |
| --- | --- |
| Latest DSP feature commit | [`cc7c9a4fde8fde4681d6f75e2310dc1dc9948c17`](https://github.com/BertCalm/SnipSnap/commit/cc7c9a4fde8fde4681d6f75e2310dc1dc9948c17) |
| Held SING motion PR | [#947](https://github.com/BertCalm/SnipSnap/pull/947), merged 2026-11-07 |
| Merge commit | `4db77e38e912d3ffe4d6d61a4b73e01c008111d7` |
| Feature CI | [Run 42352322012](https://github.com/BertCalm/SnipSnap/actions/runs/42352322012), completed successfully |
| Initial SING implementation | [#884](https://github.com/BertCalm/SnipSnap/pull/884), merged |
| Default revision inspected for this handoff | `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd` |

The inspected default contains #947. `Cairn.kt`, `CairnSing.kt`, `CairnTumble.kt`,
`CairnPatch.kt` and `Modes.kt` match the feature commit. This handoff adds
documentation; it does not change DSP or regenerate the published audio.

## Sound direction and current roster

The owner's priority is **clear differences between voices**. Earlier iterations
were too similar. The higher contacts in ANSWER were specifically liked.
The latest request was: give held SING a little more motion, like a singing bowl.
That request produced model 5; it is not an approval of its final sound.

| Voice | Defining gesture | Musical role |
| --- | --- | --- |
| KNOCK | A compact group of direct contacts, a short ring, then space | Punctuation |
| ANSWER | The first bead moves the mount, causing quieter higher-stone replies | Conversation |
| TUMBLE | One stone rebounds in progressively smaller, closer landings | Cascade / fill |
| SING | One soft touch opens into continuous smooth rubbing; held notes have slow beating and moving overtones | Sustained song |

**GRIND and VESSEL are planned names, not implemented Cairn voices.** GRIND's
intended territory includes irregular catches and scrapes; their complete
designs still need their own decisions and listening gates. The historical
six-voice recordings remain references. Their original renderer was not
recovered; the current source implements the four voices above.

## Engine and compatibility contract

- Offline, mono, 44.1 kHz output; normally 4x internal rendering.
- Supported note range is C3-C5, MIDI 48-72. Keep lower-register expansion as a
  separate voicing/classification decision.
- Controls: TUNE, WEIGHT, SLOPE, GROOVE, FRICTION, BODY, HOLD. Notes, velocity,
  cue choice and DSP model travel with `CairnPatch` recipes.
- HOLD below .99 renders a finite gesture. At .99-1 it renders a maintained
  loop. ANSWER uses four seconds; the other voices use two seconds. These are
  free-running cycles, not host-tempo synchronization.
- Mechanics/control trajectories run at 2 kHz and use a bounded 24-entry motion
  cache. Pitch changes reuse mechanics. SING's finite HOLD value is in its
  cache key because it changes contact dwell. Finished audio is not cached.
- The output stage is band limit, decimate, DC removal/20 Hz high-pass, level;
  finite notes get the house tail fade. Held audio retains its loop boundary.
- Mechanics and modal audio are a one-way design. Material constants and the
  bowl interpretation are documented shapes/analogies, not measured materials.

| Saved model | Behavior introduced |
| --- | --- |
| 1 | Original finite KNOCK/ANSWER; the top HOLD step is reserved |
| 2 | Maintained KNOCK/ANSWER and the light-WEIGHT ANSWER root adjustment |
| 3 | TUMBLE's gravity/drag/restitution cascade |
| 4 | SING's regenerative rubbing gesture |
| 5 | Bowl-inspired motion in held SING only; the current default |

Missing model fields still decode as model 1. TUMBLE requires model 3 or later;
SING requires model 4 or later. Saved model-4 SING keeps its original sound.
Finite SING and the three contact voices retain their samples through model 5.
Do not silently replace old recipe models or repin historical generators to the
latest default. The original SING audition generator is explicitly pinned to 4.

## What model 5 changes

`CairnSing.kt` retains the existing bounded regenerative energy trajectory,
pitched carrier, six passive modes and output stage. For held SING in model 5,
one slow rim phase changes the fundamental and upper-harmonic weights at
different moments. It shares the existing two-second pressure cycle: 0.5 Hz
with a small 1 Hz shape component. This creates nearby sidebands and changing
harmonic balance while keeping the note centred.

GROOVE increases motion depth; BODY and velocity moderate it alongside their
existing behavior. There is no new iterative solver. This is a contact/readout
analogy, not a simulation of bowl geometry or microscopic stick/slip.

Preserve the held-loop proof: drive a real preceding segment and kept cycle,
and compare with a separately advanced twelve-cycle modal-bank reference.
Eight repetitions in the player are a listening aid. They are not the seam
measurement, and an output crossfade or tiled waveform must not replace it.

## Validation already completed

These results belong to the merged SING implementation above, not a claim that
all later unrelated default-branch changes were tested by the same local run.

| Check | Result |
| --- | --- |
| Complete local JVM gate | 8,848 tests across nine modules; exit 0; no failures, errors or skips |
| Focused Cairn/periodic-state gate | 40 tests passed, including five motion tests and 32 held control corners |
| Historical native replay | 300 earlier recipes, 600 WAVs byte for byte unchanged |
| New comparison | 16 recordings, including 14 held cases |
| Real held continuation | Zero Float and decoded 24-bit PCM seam errors in the comparison; zero difference from the twelve-cycle warm reference |
| Default root-envelope depth | 16.3-16.6% across C3/C4/C5; previously 3.1-3.2% |
| Default second-partial/root balance depth | 39.7-40.6%, demonstrating colour movement beyond one master gain envelope |
| Root probe | Maximum 4.1349 cents; confidence at least .9732 across three registers, three GROOVE settings and three cycle windows |
| C5 4x/8x comparison | 1.359% relative waveform RMS difference; not a global proof of zero aliasing |
| Browser | All 16 files decode; five widths from 360 to 1440; playback scheduling, controls, stop/register behavior, retries and source download pass |
| Earlier Site assets | 1,568 files preserved |
| GitHub CI for #947 | All three synth shards, other JVM suites, aggregate JVM check, Android build and native tests succeeded |

The local gate had no Android SDK. Android compilation was subsequently verified
by CI. Physical-phone playback, phone latency, emulator listening and MPC
hardware acceptance are not established by these results.

Idle server held-render medians were **53.07 ms before and 54.10 ms with motion**
over twelve warmed new-control trials per version. The first moving HOLD took
**273.77 ms**, including DSP initialization but not whole-JVM startup. The
warmed path still misses the proposed 50 ms target; cold rendering needs work.

## Source map

| File or directory | Purpose |
| --- | --- |
| [Cairn.kt](../synth/src/main/kotlin/com/snipsnap/synth/Cairn.kt) | Public voice/model contract, KNOCK/ANSWER, motion cache and render dispatch |
| [CairnPatch.kt](../synth/src/main/kotlin/com/snipsnap/synth/CairnPatch.kt) | Saved model, macros, note, velocity, cue and JSON |
| [CairnTumble.kt](../synth/src/main/kotlin/com/snipsnap/synth/CairnTumble.kt) | Analytic flight, rebound and supported-rest behavior |
| [CairnSing.kt](../synth/src/main/kotlin/com/snipsnap/synth/CairnSing.kt) | Regenerative energy trajectory, traction, model-5 motion and modal readout |
| [CairnPresets.kt](../synth/src/main/kotlin/com/snipsnap/synth/CairnPresets.kt) | One provisional starting patch per voice; outside the shared factory roster |
| [Modes.kt](../synth/src/main/kotlin/com/snipsnap/synth/Modes.kt) | Shared modal bank and uncoupled periodic-state helpers |
| [CairnSingMotionTest.kt](../synth/src/test/kotlin/com/snipsnap/synth/CairnSingMotionTest.kt) | Independent waveform measurements, pitch, continuation, bounds and compatibility |
| [CairnSingMotionAuditionGenerator.kt](../synth/src/test/kotlin/com/snipsnap/synth/CairnSingMotionAuditionGenerator.kt) | Production comparison, recipes, benchmarks and optional historical replay |
| [Audition frontend](../synth/src/test/resources/cairn-sing-motion-audition/) | HTML/JS/CSS for the before/after page |
| [Site packager](../scripts/prepare-cairn-sing-motion-site.py) | Lossless PCM checks, preserved assets and restorable source ZIP |
| [Model-4 SING](CAIRN_SING.md), [TUMBLE](CAIRN_TUMBLE.md), [foundation](CAIRN.md) | Earlier mechanism decisions and dated evidence |

The older documents describe historical milestones and may list a now-built
voice as pending. Use this overview and `CAIRN_SING_MOTION.md` for current status.
Recipe dispatch and velocity integration exist in `Patches.kt` and `Velocity.kt`;
the shell also preserves Cairn's explicit note. Cairn remains in the prototype
roster. A finished four-voice factory bank, starter kit and Android picker are
still separate work.

## Reproduce and extend

Use JDK 21 and read the repository's current build instructions. From the repo:

```sh
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Cairn*Test' --tests com.snipsnap.synth.ModesPeriodicTest
./gradlew --no-daemon :synth:generateCairnSingMotionAudition
./gradlew --no-daemon test
```

The audition task writes `testkit/cairn-sing-motion/`: 24-bit WAVs, true
continuations, recipes, measured results and `manifest.json`. `cairnSingMotionOutput`
can override that directory. To reproduce the preservation check, supply the
retained native baseline directories:

```sh
./gradlew --no-daemon :synth:generateCairnSingMotionAudition \
  -PcairnSingMotionLegacyRoots='/path/to/cairn-reconstruction;/path/to/cairn-hold;/path/to/cairn-tumble;/path/to/cairn-sing'
```

Those baseline WAVs are generated/retained artifacts, not tracked fixtures in
every checkout. Without them, the task can render the new comparison but cannot
repeat the 600-WAV preservation check. Do not regenerate changed baselines and
treat that as evidence of preservation. For exact published-source reproduction,
use feature commit `cc7c9a4fde8fde4681d6f75e2310dc1dc9948c17` in a clean worktree.

For the retained audition Site checkout, packaging also needs Python with NumPy,
SciPy and FFmpeg:

```sh
python3 scripts/prepare-cairn-sing-motion-site.py --site /absolute/path/to/cairn-site
```

`--source-only` refreshes source/prose after unchanged DSP is verified. Use the
existing Site source and hosting workflow; preserve its audience and earlier
pages. Run one Gradle build at a time. The last complete local gate took about
76 minutes, so inspect its real exit code and avoid duplicate runs.

## Listening and source recovery

- [Latest held SING comparison](https://cairn-voice-comparison.bertcalm.chatgpt.site/sing-motion/)
- [Original four-voice SING page](https://cairn-voice-comparison.bertcalm.chatgpt.site/sing/)
- [Three contact voices](https://cairn-voice-comparison.bertcalm.chatgpt.site/tumble/)
- [KNOCK/ANSWER HOLD](https://cairn-voice-comparison.bertcalm.chatgpt.site/hold/)
- [First rebuilt engine](https://cairn-voice-comparison.bertcalm.chatgpt.site/reconstruction/)
- [Historical recorded bank](https://cairn-voice-comparison.bertcalm.chatgpt.site/)

The Site is owner-private; a new collaborator may need owner-granted access.
Reuse the project binding in the retained Site checkout's `.openai/hosting.json`.
The page's Measurements section links the complete source download and code.

The [published source ZIP](https://cairn-voice-comparison.bertcalm.chatgpt.site/sing-motion/source/cairn-sing-motion-source.zip)
restores feature tree `53d1501c3ce84f80450a5b78a2439dc76b7d4225` from GitHub base
`97a5d6309de1825dbd3940e578384b11368b6f16` using its complete patch. Its SHA-256 is
`18ce773412b5a43734bc42ec7915667ee1afde59203fd09812ded33d3312a6a9`.
That archive pins the audition implementation; this later handoff document is
delivered separately and does not change that archive's provenance.

In the retained execution workspace, the Site source is
`/workspace/sites/cairn-voice-comparison`. Prior native baseline directories are
under the separate `SnipSnap-cairn`, `SnipSnap-cairn-integrated`,
`SnipSnap-cairn-tumble` and `SnipSnap-cairn-sing` worktrees' `testkit/` directories.
Session reports are in `/workspace/scratch/cairn-motion-*.json`. These paths are
conveniences for this workspace, not dependencies a fresh checkout can assume.
The Cairn verification run is complete and its temporary preview was stopped.

## Open work and suggested next order

1. **Listen to held SING.** Confirm the amount of movement, upper-tone motion
   and repeated-cycle feel. Keep KNOCK, ANSWER and TUMBLE distinct. Treat further
   changes as new decisions; preserve already-saved recipe models.
2. **Measure on a phone and MPC.** Verify playback, cue/loop handling, root,
   velocity and perceived distinction. Profile warmed control turns and cold
   initialization on the actual device before choosing optimizations.
3. **Finish product delivery.** Author the four-voice recipe library after
   listening, build a useful kit and add the picker/controls with an Android
   SDK. Provisional starting patches are not a finished factory bank.
4. **Design GRIND and VESSEL separately.** Establish each gesture, cost, control
   contract and smallest listening proof before expanding the roster.

Suggested continuation prompt:

> Continue Cairn from docs/CAIRN_HANDOFF.md. The four-voice engine and model-5
> held SING motion are merged and verified. Start with the latest listening
> gate, preserve saved models and earlier recordings, and keep the voices
> clearly distinct. Read AGENTS.md and the applicable Luthier/Steward guidance.
