# Versioned HOLD evidence

This folder separates acoustic capture evidence from the original finite mechanics.
Repeated captures assume externally replenished sound. The unchanged finite work budget
does not fund infinite repetition. Native listening acceptance remains open.

- `tests.json`: focused native gate and the initial 36-case held matrix. The final full
  gate is recorded separately; its whole-cycle/phase test and classifier checks supersede
  the earlier focused assertion set.
- `listening.json`: exact 18-clip pack, source/compiler and audio hashes, independent
  method replay, sampler metadata, ZIP paths/CRC and browser QA.
- `capture-length-proof.json`: bounded `.6/.9/1.2 s` and note-dependent alternatives,
  including 30 transition renders and unchanged finite audio. No candidate was a clear
  improvement across notes, so production retains the original approximately `.6 s` capture.
- `phase-pitch-analysis.json` and `phase_pitch_analysis.py`: explain short-window detector
  refusal with the existing autocorrelation threshold and full-cycle Fourier lines. A
  known-target local-peak lookup diagnoses refusal; it is not an unconstrained detector.
- The adjacent Java experiment drivers reproduce the bounded capture comparisons against
  separately compiled helper overlays. The production Kotlin helper was never changed.
- `android-harness.json`: current API compilation against compile-only Android stubs;
  no SDK build, device execution or phone result.
- `model2-filing-failure.json`: the completed model-2 gate's two failing methods; the
  generic classifier misfiled short bass cores. All other tests passed in that run.
- `model3-span.json`: genuine multi-capture export proof and 36 current LOOP cases.
- `model2-replay-fixtures.json`: twelve independently frozen audio/marker fixtures.
- `model3-focused.json`: normal Gradle checks after the filing fix.
- `listening-model2.json`: earlier published short-core evidence; current `listening.json`
  describes the model-3 pack.

Regenerate listening artifacts with `:synth:generateSkirlHoldAudition`; generated WAVs and
ZIPs live under ignored `testkit/skirl-hold-audition/`. The pack keeps refused estimates:
35/36 fixed-window estimates, 12/12 whole-loop estimates and 18/24 soft-C2 phase estimates
return a root. A successful detector window does not guarantee all windows or substitute
for listening. The initial acceptance limit is 50 cents, not concert tuning.

Current model 3 exports several real captures above the classifier's 1.5-second LOOP
boundary. Model 2 preserves its original approximately .6-second core and exact saved
audio, even where the generic classifier guessed KICK/TOM. The primitive texture stays
the same; export span and primitive capture period are separate measurements.

WAVs include their root and forward loop in RIFF `smpl` metadata. Exact method descriptors
distinguish `renderHeld` and derived eight-pass listening sequences from ordinary patch
rendering. The offline page embeds its manifest; browser HTTP playback was exercised.
Direct file navigation was blocked by the browser's administrator policy, so only the
portable structure was verified for that mode.

## Reproduce the capture-length proof

Run from a checkout of `4b00ed1dd2f2e0ed31f44c718c30ee7cdfb118f5`, the committed
production source used by this proof. The source hashes in `capture-length-proof.json`
also identify the four relevant DSP files. Use JDK 21 with its source-file compiler,
`kotlinc` 2.0.21 on `PATH`, and Python 3. The original JVM was 21.0.12.1.
Nothing below needs the original session's scratch files or modifies production Kotlin.

Build the current libraries, then create the four research copies. The ROLL-only
copies identify the voice from the two disjoint, calibrated launch-angle ranges;
that is an experimental shortcut, not a proposed production API.

```sh
set -eu
./gradlew --no-daemon :cli:installDist
skirl_proof_dir="testkit/skirl-capture-proof"
python3 - "$skirl_proof_dir" <<'PY'
from pathlib import Path
import sys

output = Path(sys.argv[1])
source = Path("synth/src/main/kotlin/com/snipsnap/synth/SkirlHold.kt").read_text()
original = "min(.60, motion.duration * .30)"
assert source.count(original) == 1, "Use the recorded production source"
targets = {
    "hop09": "min(.90, motion.duration * .60)",
    "hop12": "min(1.20, motion.duration * .60)",
    "rollnote09": "if (motion.params.leanAlphaRad > .25) min(.90, min(.60 * maxOf(1.0, 130.8 / hz), motion.duration * .60)) else min(.60, motion.duration * .30)",
    "rollnote12": "if (motion.params.leanAlphaRad > .25) min(1.20, min(.60 * maxOf(1.0, 130.8 / hz), motion.duration * .60)) else min(.60, motion.duration * .30)",
}
(output / "base06").mkdir(parents=True, exist_ok=True)
for name, target in targets.items():
    folder = output / name
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "SkirlHold.kt").write_text(source.replace(original, target))
PY
skirl_proof_cp="$(python3 - <<'PY'
from pathlib import Path
import os
roots = [Path("synth/build/classes/kotlin/main")]
roots += sorted(Path("cli/build/install/cli/lib").glob("*.jar"))
assert len(roots) > 1, "Build :cli:installDist first"
print(os.pathsep.join(str(path.resolve()) for path in roots))
PY
)"
for skirl_variant in base06 hop09 hop12 rollnote09 rollnote12; do
  skirl_variant_cp="$skirl_proof_cp"
  if [ "$skirl_variant" != base06 ]; then
    kotlinc -no-stdlib -no-reflect -jvm-target 17 -module-name synth \
      -Xfriend-paths="$PWD/synth/build/classes/kotlin/main" \
      -classpath "$skirl_proof_cp" \
      -d "$skirl_proof_dir/$skirl_variant/classes" \
      "$skirl_proof_dir/$skirl_variant/SkirlHold.kt"
    skirl_variant_cp="$PWD/$skirl_proof_dir/$skirl_variant/classes:$skirl_proof_cp"
  fi
  case "$skirl_variant" in
    base06) skirl_drivers="HoldCaptureVariants HoldCaptureNormalizedPhases HoldCaptureTransition" ;;
    hop09|hop12) skirl_drivers="HoldCaptureVariants HoldCaptureNormalizedPhases" ;;
    *) skirl_drivers="HoldCaptureTransition" ;;
  esac
  for skirl_driver in $skirl_drivers; do
    java -Xmx512m -cp "$skirl_variant_cp" \
      "prototype/skirl/native/hold/$skirl_driver.java" \
      "$skirl_proof_dir/$skirl_variant/$skirl_driver" \
      > "$skirl_proof_dir/$skirl_variant/$skirl_driver.log"
  done
done
```

These are POSIX-shell commands; `:` joins the overlay and runtime classpaths on
Linux/macOS. On Windows, use `;` for that join. The overlay must come first so the
unchanged engine invokes the research helper. `-Xfriend-paths` permits access to
the original synth module's internal types. No overlay belongs in a production build.

`HoldCaptureVariants` measures twelve fixed start times from 0 to .55 seconds;
`HoldCaptureNormalizedPhases` measures twelve equally spaced positions over each
complete loop. `HoldCaptureTransition` covers ROLL MIDI 36/40/44/47/48 at velocities
.25 and 1, with twelve positions per loop. Logs contain actual JVM detector results,
independent seam diagnostics, output health and finite Float-bit hashes. WAVs are
PCM16. The transition matrix compares `base06`, `rollnote09` and `rollnote12`;
the two-voice matrices compare `base06`, `hop09` and `hop12`.

With NumPy installed, reproduce the strongest periodic Fourier line from those WAVs:

```sh
python3 - "$skirl_proof_dir" <<'PY'
from pathlib import Path
import hashlib, re, sys, wave
import numpy as np

root = Path(sys.argv[1])
for path in sorted(root.rglob("*-loop.wav")):
    with wave.open(str(path)) as wav:
        assert (wav.getnchannels(), wav.getsampwidth()) == (1, 2)
        rate = wav.getframerate()
        samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2") / 32768.0
    match = re.match(r"roll_(\d+)_", path.name)
    midi = int(match[1]) if match else 36
    target = 440 * 2 ** ((midi - 69) / 12)
    power = abs(np.fft.rfft(samples)) ** 2
    power[0] = 0
    hz = int(np.argmax(power)) * rate / len(samples)
    print(path.relative_to(root), "strongestHz", hz,
          "cents", 1200 * np.log2(hz / target),
          "wavSha256", hashlib.sha256(path.read_bytes()).hexdigest())
PY
```

Use the exact cycle without a Hann window: these are the Fourier-series lines of
the repeated capture. The strongest line alone does not establish perceived pitch.
Compare the measurements and WAV hashes with `capture-length-proof.json`; the
commands create new artifacts without overwriting the committed reports.

To reproduce the separate known-target refusal analysis, generate the current
listening pack and use the existing NumPy/SciPy prototype requirements:

```sh
./gradlew --no-daemon :synth:generateSkirlHoldAudition
python3 prototype/skirl/native/hold/phase_pitch_analysis.py \
  testkit/skirl-hold-audition \
  --output testkit/skirl-capture-proof/phase-pitch-analysis.json
```
