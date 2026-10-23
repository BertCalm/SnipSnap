# Source transformation: R0 repository review

Status: REVIEW, completed as a documentation and code-inspection pass. R1 remains proposed; no new processor, benchmark, audio render, or listening judgment is claimed.

Inspected SnipSnap commit: `4e36a2bf8cf8bfb07dba35379558b54429b5d73d`. The original baseline was `565551e7974a4c61954199fac56a7a8d3dd502ed`. The handoff is present on the main development branch; its original feature branch was absent from the remote during the preceding review. This pass uses the merged handoff. No applicable `AGENTS.md` was found under `/workspace`. The installed Luthier skill and the checked-in skill's conventions, rubric, inventory, DSP catalog, combination grammar, gear guidance, and spec template informed this pass. Repository evidence overrides dated inventory summaries.

The R1 gesture is **source attack → a chosen fixed resonant spectrum → a static blend of two bodies → a bounded ringing release**. Changing BODY between renders changes the blend; R1 does not move resonances during a render.

## Fix now

- **[B] Include model identity in coefficient keys.** The original phased plan keyed coefficients only by rate/root/morph/decay/damping. Two families can share those values and require different coefficients. The handoff now requires table identity and algorithm version as well. The R1 plan adds cache-on/cache-off and family/version invalidation tests.
- **[B/E] Make the conventional branch consistently deferred.** The original brief required a separately auditionable conventional filter while the phased plan left inclusion open. R1 now proves the banks and dry/wet path alone. The reference's full parallel topology remains later work.

## Verified repository facts

Paths and line numbers below refer to the inspected commit, not to proposed APIs. New names in the implementation plan are explicitly proposals.

| Area | Declaration / evidence | Consequence |
|---|---|---|
| Module direction | `synth/build.gradle.kts:382` depends on `:audio`; `:audio` has no project-module dependency; `shell/build.gradle.kts:10` and `:15` depend on both. | Put the new shared processor in `:audio`. Neither `:audio → :synth` nor a new module is necessary. |
| Input format | `audio/.../Cleanup.kt:12`, `Snip`, holds interleaved floats, 1–2 channels, positive rate, and whole frames. `Snip.peak`, line 33, deliberately ignores non-finite samples. | Preserve format explicitly. A peak check alone cannot validate input finiteness. |
| Legacy BODY | `audio/.../Body.kt:77`, `Body.ring`, returns the source for zero amount; lines 85–106 append T60 frames and run per-channel resonators with one shared decay. Lines 109–127 peak-match wet audio and apply final makeup. | Keep this method byte-for-byte unchanged. Its chord-tone model and source-dependent gain are not the new processor's gain contract. |
| BODY entry points | `shell/.../Keyed.kt:92`, `Keyed.apply`, dispatches `bodied` to `Body.rootFor` and `Body.ring` at lines 103–107; `cli/.../BodyCommand.kt:43–57` validates decay and derives the existing root/modes. | Do not redirect the capture operation or CLI BODY command in R1. |
| Modal toolkit | `synth/.../Modes.kt:25` is `internal`; `Mode` carries ratio/gain/T60/pan. `ring`, line 88, fixes coefficients per call, skips invalid/Nyquist modes, and uses unnormalized gain at lines 98–117. | Reuse the modal mathematics and audited ratio facts, not the strike-oriented renderer or its visibility. Low-frequency and sustained-input gain need a separate design. |
| Existing morph | `synth/.../Modes.kt:334`, `Modes.morph`, resamples slots and interpolates log frequency, gain, and decay. | The repo already has static table morphing. R1's independent-bank crossfade is a deliberate simpler alternative, not a claim that morphing is absent. |
| Model provenance | `Modes.kt:158–174` lists four free-bar ratios and other material tables, with source attributions; lines 139–154 explicitly label gains/decays as shapes. `stiffString`, line 189, gives a harmonic series when stiffness is zero. | Use a four-partial harmonic family and the existing four free-bar ratios. Do not extrapolate sixteen physical modes from four sourced entries or represent authored weights as measurements. |
| GRAINS | `synth/.../Grains.kt:55`, `Grains.render`, takes source/length/seed; lines 70–121 convert stereo to mono, scatter Hann-windowed slices, resample linearly, normalize, and fade. | It is not a cycle tracker, independent formant shifter, or antialiased upward resampler. Leave it unchanged; R3 needs a separate proof. |
| Pitch detector | `audio/.../Pitch.kt:48`, `Pitch.detect`, examines a configurable window after an attack offset and returns one `PitchEstimate`; lines 36–38 bound its search/window. | Useful for fixture checks, not a ready-made voiced/unvoiced trajectory or cycle-mark analysis. R1 takes an explicit reference pitch and runs no detector. |
| Smear | `synth/.../Smear.kt:43`, `Smear.process`, delegates to `Separate.smear`; `audio/.../Separate.kt:119` implements that operation. | An existing spectral treatment is a candidate for later comparison, not proof of equivalence to the reference's granular smear. |
| Rack execution | `synth/.../FxChain.kt:88–104` runs transport/arrival first and rack sections in declared order; `SECTIONS`, line 189, defines execution/serialization order. | A future section needs an explicit insertion point. R1 adds no section or schema field. |
| Rack tail | `FxChain.kt:115–123` caps added tail and fades the cut; `MAX_CHAIN_TAIL_SECONDS`, line 168, is 1.0 s, measured after transport/arrival. | R1's standalone tail is at most 0.6 s. That does not establish how stacked rack tails should be budgeted in R2. |
| Saved pad path | `synth/.../PadRecipe.kt:21` stores patch/fx/board metadata; `render`, line 74, renders a patch then calls `process`; `process`, line 81, applies the existing rack, board, and terminal fade. | Preserve old JSON and deterministic delivery by adding no new pad or rack field in R1. The audition manifest is a standalone replay record. |
| UI boundary | `app/.../ui/PadSheetScreen.kt:960` sends keyed treatments through `keyedPad`; `app/build.gradle.kts:17–34` declares SDK 35/minimum 29/instrumentation runner. | A shared JVM processor is feasible independently of UI. No new control or picker is proposed for this round; app compilation/device behavior is unverified here. |
| Siblings | `Mercury.kt:444` renders its own instrument through `Modes.Bank`; `Tessera.kt:748` renders a generated instrument; `Vox.kt:560` and `Murmur.kt:321` generate vocal-like sources. | Those engines establish nearby timbres, not a general captured-source effect with the proposed contract. No new engine or roster registration is justified for R1. |

Here `audio/...`, `synth/...`, `shell/...`, `cli/...`, and `app/...` abbreviate their existing `src/main/kotlin/com/snipsnap/<module>/` paths.

## Supplied-manual statements and adaptation choices

The local texts are the evidence; page references below are printed page numbers. Supermodal printed page N is physical PDF page N+1, as recorded in [SOURCES.md](references/SOURCES.md). Original PDF binaries and diagrams were not available for independent verification.

| Manual statement | Evidence | Decision |
|---|---|---|
| Pitch and formants have separate controls; downward smoothing fills grain gaps. | Manipulator 1.4.3 pp. 12–13. | R3 only; do not substitute ordinary sample-rate changes or GRAINS for this claim. |
| Harmonic shifting is pitch-tracked frequency translation; fractional step positions crossfade integer results. | Manipulator pp. 14–15. | R3 requires its own sideband/negative-frequency/neutral tests. Ring modulation is not equivalent. |
| Alternation uses groups measured in source cycles; smear precedes the main algorithms. | Manipulator pp. 16–17. | Later pitch/cycle analysis and a documented processing order. |
| Conventional and modal filters run in parallel; models/Xforms vary frequency/amplitude configurations. | Supermodal 1.0.0 pp. 13–14, 18–20. | R1 adapts only the modal-body idea. Independent-bank output blending is not physical mode interpolation or the full product topology. |
| Damping reduces higher-partial volume; partial limits remove frequency ranges. | Supermodal p. 21. | Our proposed DAMP shortens higher-mode T60. This is an explicit design departure, not an assertion about the reference's implementation. RING controls the base T60. |
| Quality settings use 32/64/100 filters per side. | Supermodal p. 45. | No reason to match those counts before a four-mode proof has earned expansion. |

The manuals do not disclose measured material tables, proprietary algorithms, or reusable audio. No product name becomes an engine, voice, family, or preset name. No external implementation is copied. The repository lacks a top-level LICENSE at the inspected commit; this review does not infer a license for project or third-party code.

## Decisions for R1

| Question | Proposed resolution and reason |
|---|---|
| Placement | New `ResonantBody` processor in `:audio`, consumed directly by a test-side audition generator in `:synth`; existing dependency direction suffices. |
| Model boundary | Separate model version 1, no replacement of BODY or `Modes`. Four harmonic ratios versus four free-bar ratios establish different spectra with bounded work. Both are idealized filter families. |
| Morph | A static convex output crossfade of two independent banks. No coefficient interpolation or running-state retuning. It can expose both spectra at once; listening must judge whether that is useful. |
| Channels / rate | Preserve mono/stereo, process each channel independently with shared immutable coefficients, support 44.1/48 kHz explicitly. No widening, downmix, or implicit resampling. |
| Internal rate | Native input rate for this fixed linear filter. Engine oversampling rules are not automatically processor requirements. There is no nonlinear drive or moving coefficient in R1; later additions reopen aliasing and latency design. |
| Gain | Normalize excitation analytically per mode, use normalized nonnegative mode weights and fixed makeup, then one attenuation-only stereo-linked output guard. Record raw state/output peaks and applied attenuation. No peak/loudness matching of each render. |
| Tail | Append the base T60, bounded at 0.6 s, then fade wet output over the last 10 ms. Exact zero-mix bypass preserves original length. No HOLD or loop claim. |
| Cache | Immutable versioned tables, bounded per-processor coefficient cache, render-local channel/mode state. Full keys and invalidation cases are in the R1 plan. |
| Performance | Owner specified Nothing Phone (3). Proposed gate: warm p95 ≤50 ms for a one-second mono source plus maximum tail, eight active modes at 44.1 kHz, in a non-debuggable build. Record cold timing and allocations too. It is an unmeasured target. |
| Scope | No conventional branch, source transformation, motion, CLI product command, rack serialization, UI, preset bank, or legacy refactor in R1. |

## Try next

- **[A] Audition the same source through both endpoints before expanding mode count.** A harmonic spectrum and a bar spectrum should be distinguishable after level matching. If they are not, first change authored weights/decays or family choice; more filters do not establish identity.
- **[C/D] Compare an impulse with a sustained on-resonance tone.** A gain law that makes a click dramatic can overload on sustained material; one that bounds sustained material may make taps too quiet. R1 must show raw and matched audio plus the amount of attenuation, rather than hiding this trade with normalization.

## Big idea

**[B] Grow a reusable captured-source body stage only after its contract works.** The long-term benefit is the same processor acting on a captured tap and on an existing synth render. R1 establishes that boundary without changing either saved recipes or engine synthesis. The parallel conventional branch and later cycle transformer can then be evaluated independently.

## Disclosed, not fixed

- Four-mode families may sound sparse, and a bank crossfade may sound like layering rather than one changing material. No listening evidence resolves this yet.
- BAR's control specifies its first resonant mode; it does not guarantee a detector will report that mode as a musical root.
- The proposed output guard may attenuate the dry contribution when the wet path peaks. Auditions must disclose this; it is not transparent level preservation.
- Original PDFs, original modal research publications, target-phone runtime/OS details, and target-device measurements were not independently inspected in R0. Ratio provenance is the existing repository's attributed table plus the stated analytical harmonic law, not a new measurement of a physical object.
- An owner-approved dry capture and a Nothing Phone (3) benchmark run remain R1 inputs. Generated fixtures permit independent implementation/testing before those arrive. Neither is needed to finish this documentation pass.
- No proposed performance, spectral, decay, or listening acceptance gate has passed merely because it is written here.

## What I measured / what I only read

This pass inspected code and manual text, not audio. A Python standard-library check of the final handoff verifies local Markdown links, both exact extracted-text SHA-256 values, and consecutive PDF page headers (30 Manipulator, 48 Supermodal). Original PDF hashes cannot be recomputed without those binaries. See the reproducible command below and the R1 plan for future DSP measurements.

Commands used: `git status --short`, `git rev-parse HEAD`, `find /workspace -name AGENTS.md -not -path '*/.git/*'`, `rg` declaration/call-site searches, `nl -ba`/`sed` source reads, and the documentation checks below. The mandatory spec-review searches found: no `siblingPatches` in `synth`; `Keys.MAX_SEAM_ERROR = 1e-3` at `Keys.kt:147`; rosterless/prototype sets at `PatchTypes.kt:56,68`; classifier threshold 1.5 s at `Classifier.kt:81`; private velocity dispatchers at `Velocity.kt:191,368`; `Pitchwheel.MODEL_VERSION = 3` at `Pitchwheel.kt:21`. These are located code facts, not audio measurements.

Run from the repository root:

```sh
git diff --check
python3 - <<'PY'
from pathlib import Path
import hashlib, re
root = Path('docs/plans/source-transformation')
manifest = (root / 'references/SOURCES.md').read_text()
for name, count in [('manipulator', 30), ('supermodal', 48)]:
    path = root / 'references' / (name + '-manual.txt')
    data = path.read_bytes()
    assert hashlib.sha256(data).hexdigest() in manifest
    pages = list(map(int, re.findall(r'^===== PDF PAGE (\d+) =====$', data.decode(), re.M)))
    assert pages == list(range(1, count + 1))
for path in root.rglob('*.md'):
    for target in re.findall(r'\]\(([^)]+)\)', path.read_text()):
        if not re.match(r'https?://', target):
            assert (path.parent / target.split('#')[0]).exists(), (path, target)
print('Manual hashes, 78 page headers, and local Markdown links verified')
PY
git diff --name-only
git ls-files --others --exclude-standard
```

No application build or regression suite was run for these documentation-only changes. The prior environment-onboarding results are not evidence for a new DSP model. Verified: repository declarations and document integrity. Proposed: all R1 equations, controls, thresholds, and file additions. Measured DSP: none. Heard: none.
