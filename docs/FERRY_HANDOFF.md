# Ferry handoff

Snapshot: 2026-11-07. Repository: [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap).

## Resume here

The wood-to-wire sound and MATCH control have passed the owner's listening gates.
The LOSS ending has been rejected twice. The current listening page is **Study 07,
full decay**, and its result is still pending. Keep LOSS approval pending until
the owner gives that verdict.

Open <https://ferry-first-listen.bertcalm.chatgpt.site>, refresh, and choose
**Play B: full decay**. Allow about ten seconds. The answer prefix is
`Ferry full-decay listen:`. This page is owner-private.

The engine snapshot described here is model 4. The current full-decay audition is a standalone
harness using that engine's network; it is not a new saved engine model. An
ordinary model-4 patch still renders the rejected short release.

This handoff adds documentation and retains the audition source, measurements,
recipes and page. The engine implementation remains unchanged.

## Hearing ledger

Only the owner's responses establish listening acceptance.

| Round | Owner's response | Status |
| --- | --- | --- |
| First Python handoff | “Yes, clearly.” | Approved reference |
| Second Python register/speed study | “Clear throughout.” | Approved reference |
| Kotlin port, 2026-10-30 | “Ferry Kotlin listen: Same character.” | Approved aligned Kotlin sound |
| MATCH, 2026-11-01 | “Ferry MATCH listen: Clear change, note stays clear.” | Approved MATCH range |
| LOSS model 3, 2026-11-06 | “Ferry LOSS listen: Something sounds wrong. Short stops abruptly rather than fade.” | Rejected one-second dry cutoff |
| LOSS model 4, 2026-11-07 | “Ferry LOSS revised listen: Still stops abruptly.” | Rejected 350 ms release |
| Study 07 full decay, 2026-11-07 | No response yet | Awaiting hearing |

Numerical checks passed for the earlier releases. The owner's rejection of their
musical ending remains valid and is the reason for the current comparison.

## Code and commits already saved

The default branch is `claude/mobile-mpc-drum-sampler-t58x74`.
The handoff branch is `claude/ferry-handoff`, based on the exact engine snapshot
used for the published study. The default branch may contain later work. Continue implementation
against the current default or merge it into the working feature branch; keep
the pinned engine snapshot for strict Study 07 reproduction.

| Change | Pull request | State verified for this handoff |
| --- | --- | --- |
| Initial Kotlin port | [#712](https://github.com/BertCalm/SnipSnap/pull/712) | Merged; head `e2ee7b1bdbd423e9f70dd1a5d2d7cbbce936ac10` |
| MATCH | [#785](https://github.com/BertCalm/SnipSnap/pull/785) | Merged; head `0abbf9c9959c6f9d114235832be73ce0b90f10a3` |
| LOSS | [#858](https://github.com/BertCalm/SnipSnap/pull/858) | Merged; final head `fe02aabaebafe0ebcbd1d909f15d459197e20f21` |
| Short-note release | [#894](https://github.com/BertCalm/SnipSnap/pull/894) | Merged; head `341ac7fdbe82cba7e256b08d2e93d0958ef8710b` |

The model-4 code commit is
[`341ac7fdbe82cba7e256b08d2e93d0958ef8710b`](https://github.com/BertCalm/SnipSnap/commit/341ac7fdbe82cba7e256b08d2e93d0958ef8710b).
The published Study 07 Site source commit is
`fa3e75f83c7b8b18b543ba306996ae33e8afdd6a` in the separate Sites source repository.
Both were already committed and pushed before this handoff.
The default was also inspected at `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`;
its `Ferry.kt` matches the study source and still declares model 4.

### Production behavior

The characteristic gesture is **strike → handoff → sympathetic bloom → return**.
One finite PASSAGE voice uses a passive, energy-normalized 22-state complex
network. Only the struck A material and its quiet root are initially excited.

- Explicit MIDI: 36–72. Saved velocity, exact signed 64-bit seed and model.
- Controls: HANDOFF, MATCH, LOSS and reserved HOLD=0.
- MATCH offsets only B's upper modes by up to six cents. Fundamentals stay fixed.
- LOSS multiplies all material amplitude-loss rates by .5×–4×; neutral is .5.
  Tuning, reciprocal links, pickups and the excitation remain fixed.
- Models 1 and 2 retain four-second output. Model 3 uses `4 / lossScale` seconds:
  eight seconds at LOSS=0, four at .5, one at 1. These are observation windows.
- Model 4 adds `.35 * (2 * LOSS - 1)` seconds of cosine release above LOSS=.5.
  The dry endpoint lasts 1.35 seconds. Neutral/bloom output and models 1–3 replay
  exactly. This release is the second rejected ending.
- Output is mono 44.1 kHz, rendered internally at 4×. The compiled-plan LRU has
  32 entries keyed by MIDI and HANDOFF/MATCH/LOSS bits. Excitation retains its
  original version salt, preserving seeded recipes.

PASSAGE remains a prototype outside the factory roster and phone picker.
Natural production termination, SPAN/BODY, HOLD, more voices, kits and phone/MPC
acceptance remain separate work.

## Current full-decay audition

Study 07 compares only the dry setting: HANDOFF=.5, MATCH=1, LOSS=1,
velocity=1, seed=7, at MIDI 36/48/72.

- **A:** the exact previous model-4 PCM audio, followed by one second of silence.
- **B:** the same physical trajectory continued until a quiet state criterion is
  met, followed by one second of silence. There is no 350 ms cosine release.
  The normal 4 ms house fade occurs after the tail is already quiet.
- B's first quarter-second RMS matches A. The raw first second is exactly equal
  to the existing network trajectory. PCM opening correlation exceeds .999999999.
- The criterion bounds future unconditioned pickup amplitude by
  `norm(pickup) * sqrt(stateEnergy) <= 2e-5`. The search uses quarter-second
  increments and refuses if the criterion is not met before 32 seconds.
- The stop threshold and silence padding are proposed audition choices. This
  prototype covers the three listed dry notes, not the entire macro cube.

| Register | B network audio | B total with silence | Final 500 ms float RMS |
| --- | ---: | ---: | ---: |
| Low, MIDI 36 | 9.50 s | 10.50 s | −121.63 dBFS |
| Middle, MIDI 48 | 9.25 s | 10.25 s | −121.14 dBFS |
| High, MIDI 72 | 9.25 s | 10.25 s | −123.12 dBFS |

A lasts 2.35 seconds including its silent pause. The browser was checked to play
B beyond 9.5 seconds and through its actual EOF. This separates a quiet ending
from the player's stop event.

The measured harness render/conditioning time was about 123–153 ms on the server
JVM after plan compilation. That includes a prefix consistency render and is
not a complete production-cache benchmark or a phone measurement.

## Files retained in this commit

| File | Purpose |
| --- | --- |
| [FERRY.md](FERRY.md) | Main engineering record and earlier as-built sections |
| [FerryNaturalTailStudy.java](../design/ferry-natural-tail-study/FerryNaturalTailStudy.java) | Exact standalone native-network audition harness |
| [manifest.json](../design/ferry-natural-tail-study/manifest.json) | Measurements, source hashes, six WAV hashes and hearing ledger |
| [natural-recipes/](../design/ferry-natural-tail-study/natural-recipes/) | Three source patches and three extended-study recipes |
| [index.html](../design/ferry-natural-tail-study/index.html) | Retained Study 07 listening page |
| `synth/src/main/kotlin/com/snipsnap/synth/Ferry.kt` | Controls, versions, rendering and output stage |
| `synth/src/main/kotlin/com/snipsnap/synth/FerryNetwork.kt` | Passive network, compiled readout and state diagnostics |
| `synth/src/main/kotlin/com/snipsnap/synth/FerryPatch.kt` | Versioned saved recipes and strict parsing |
| `synth/src/test/kotlin/com/snipsnap/synth/Ferry*Test.kt` | Original, MATCH, LOSS and release checks |
| `synth/src/test/resources/ferry-model{1,2,3}-goldens.json` | Independent legacy whole-Float hashes |
| `reference/ferry/` | Original network fixtures and prior measured reports |

WAVs regenerate and are hosted in the Site; they are not added to Git here.
The extended-study recipe describes the extra rendering steps. Loading its
embedded base patch alone through the normal CLI reproduces A, not B.

The original user attachment was named `47-Ferry-Engine-Engineering-Spec.md`.
Its temporary upload path is no longer present in this workspace. `docs/FERRY.md`
is the current repository engineering record; the original proposal is also
part of the conversation history.

## Reproduce the six current WAVs

Use JDK 17. Run from the SnipSnap root at the engine snapshot above or this
handoff branch. The harness calls Kotlin internal methods through generated
Java names, so its ABI is pinned to that snapshot.

```bash
./gradlew --no-daemon :synth:testClasses

FERRY_CP=$(python3 - <<'PY'
import os
from pathlib import Path
root = Path.cwd()
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
stdlib = sorted((cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21').glob('*/*.jar'))
assert stdlib, 'Run Gradle first to resolve Kotlin 2.0.21'
modules = ['synth', 'audio', 'json', 'xpm', 'kit', 'loop', 'mpc3']
print(':'.join([str(root / m / 'build/classes/kotlin/main') for m in modules] + [str(stdlib[0])]))
PY
)

mkdir -p build/ferry-natural-tail-harness testkit/ferry-natural-tail-audition
javac -cp "$FERRY_CP" -d build/ferry-natural-tail-harness \
  design/ferry-natural-tail-study/FerryNaturalTailStudy.java
java -Xmx768m -XX:ActiveProcessorCount=4 \
  -cp "build/ferry-natural-tail-harness:$FERRY_CP" FerryNaturalTailStudy \
  "$PWD/testkit/ferry-natural-tail-audition" \
  > testkit/ferry-natural-tail-audition/native-report.json
cp design/ferry-natural-tail-study/index.html testkit/ferry-natural-tail-audition/index.html
```

The output contains `natural-audio/`, source patches, diagnostic Float files and
the native report. Compare each generated WAV's SHA-256 with `manifest.json`.
The six WAVs were reproduced byte-identically in separate JVM runs. The commands
above were also exercised from this handoff and matched all six published hashes.
The local page's historical-study footer links are supplied by the hosted Site.

Existing engine auditions remain available through
`generateFerryAudition`, `generateFerryMatchAudition`,
`generateFerryLossAudition` and `generateFerryReleaseAudition`.
They retain their historical model versions. Study 07 has no Gradle task yet.

## Validation and limits

- Model-4 work: 27 focused Ferry/CLI checks passed. The complete
  `./gradlew --no-daemon test` returned exit 0 on 2026-11-06 after 8,660 passing
  checks, in 1 hour 10 minutes 36 seconds. Updated CLI help was also compiled and
  checked through the real command.
- The 81-case model-4 short-setting grid retained the 20-cent/.9 body-pitch
  gates: maximum 18.11 cents, minimum confidence .9251. The original 225-case
  model-3 grid remains enforced.
- A low-note probe crossing the model-4 cosine release reported .8725 confidence
  and 12.69-cent deviation. This is disclosed in `docs/FERRY.md`; it is distinct
  from the pre-release body gate.
- Study 07: native raw-prefix equality, old PCM preservation, finite/headroom
  checks, quiet-tail checks and six byte-identical replay WAVs passed.
- Browser checks passed for full B playback, A→B sequencing, actual durations,
  Stop, low/high notes, copy fallback/persistence, mobile layout, archived studies
  and all six audio hashes. No page errors were observed.
- No Android SDK was present. Phone/MPC behavior and timing remain unmeasured.

The handoff commit changes retained documentation/research artifacts only.
Its full `./gradlew --no-daemon test` pre-push check returned exit 0 on
2026-11-07 in 17 minutes 58 seconds: 2,401 checks executed, with 4 Gradle tasks
executed and 39 unchanged tasks up-to-date. This is separate from the earlier
8,660-check model-4 run. Links, seven retained JSON files, harness/source hashes
and the documented six-WAV reproduction were also verified.

## Next actions

1. Obtain and record the owner's Study 07 verdict. The current result is pending.
2. If B still feels abrupt, locate the audible event and note/time in this full
   reference before selecting another ending. If it is smooth but too long,
   retain this reference while choosing a shorter musical decay.
3. Once an ending is chosen, design its production rule across the complete
   LOSS/HANDOFF/MATCH range and registers. Measure cache/render cost, actual cap
   behavior and the conditioned tail. Preserve accepted neutral audio and saved
   older versions through independent fixtures; version changes to audible output.
4. Keep factory promotion, new controls/voices, HOLD and phone/MPC integration
   separate from this ending decision.

For DSP work read `AGENTS.md` and `.claude/skills/luthier/SKILL.md` first.
For PR work also read `.claude/skills/steward/SKILL.md`. Use normal feature-branch
pushes and the repository's default branch; approval/merge remains with the owner.

## Site and workspace references

Reuse the existing Site and preserve its audience when changing the audition.

- Project: `appgprj_6ad97ae18550819185366f038be81a41`
- Current successful deployment: `appgdep_6aef371a1f6481918483b237f895ab05`
- Saved version: `appgprj_6ad97ae18550819185366f038be81a41~appgver_62acea99814881918881157974e05740`
- Site source commit: `fa3e75f83c7b8b18b543ba306996ae33e8afdd6a`
- Hosted archives: `/first-listen/`, `/second-listen/`, `/kotlin-listen/`,
  `/match-listen/`, `/loss-first-listen/`, `/release-first-listen/`.

Workspace conveniences, which may not survive a new environment:

- Engine/handoff checkout: `/workspace/SnipSnap-ferry-release`
- Site checkout: `/workspace/sites/ferry-first-listen`
- Rendered study: `/workspace/ferry-natural-tail-study`
- Independent replay: `/workspace/ferry-natural-tail-replay`
- Browser QA: `/workspace/scratch/ferry_natural_browser_check.py`
- Detailed local state: `/workspace/scratch/ferry-natural-state.json`
- Latest deployed archive: `/workspace/ferry-natural-listen-deploy.tar.gz`

The committed harness, report, recipes and this document provide the durable
continuation point. Obtain new Site credentials through the Sites connector;
credentials are not stored in this handoff.
