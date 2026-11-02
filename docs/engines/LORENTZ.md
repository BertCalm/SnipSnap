# LORENTZ finite collision engine

The characteristic gesture is **falling sweep → finite collision → irreversible capture → steady sub tail**. FUSE uses a sine flyer and rounded contact; LORENTZ uses a PolyBLEP square flyer and a longer, rippling tear. The target phase is continuous and its root never follows the flyer sweep.

This is a musical analogy to magnetic pulse welding, not an electromagnetic or contact simulation. It uses no recordings, random draws, feedback solver, or persistent render state.

## Source and controls

Model 3 ports the owner's accepted Audition 03 Python equations. The reference source is retained in `scripts/lorentz_reference.py`; `scripts/generate_lorentz_reference.py` regenerates the sparse raw fixture used by `LorentzTest`. Inputs are quantized to Kotlin Float before reference generation. The Kotlin output stage differs from the Python harness: it uses the actual house band limit, decimator, DC removal and perceived-level conditioning.

| Control | Default | Behavior |
| --- | ---: | --- |
| TUNE | .50 | C1 through C3 in semitones; default C2 |
| CAPACITOR | .70 | Rate of the falling pitch, with faster collision at higher settings |
| GAP | .20 | Frequency span and approach time; wider settings rise into contact |
| IMPACT | .60 | Contact duration, PM severity and transient contribution |
| WELD | .50 | Tail decay and bounded harmonic drive |
| HOLD | .00 | Interpolates decay toward 1.2 seconds, without a loop |

At full velocity, the source equations preserve Audition 03. Softer velocities reduce contact severity; output level scales after the shared loudness stage, so normalization cannot erase touch dynamics. Velocity zero renders silence. Saved patches retain velocity and explicitly record model 3; unsupported models are refused rather than silently retuned.

The flyer starts at `root × ratio` and falls exponentially. `ratio = 40 × 2^(2 × (GAP − .2))` and the fall rate is `35 × 10^CAPACITOR / 2^(1.5 × (GAP − .2))`. Collision occurs once at `log(ratio)/rate`. The free branch continues at the root through a 1.5 ms smootherstep capture; no audible waveform is hard-reset.

FUSE contact spans 10–54 ms and LORENTZ contact 12–78 ms. Modulation ratios settle from 2.37 to 1 and 5.13 to 2 using integrated frequency laws. The collision branch retires completely. IMPACT does not alter the settled raw tail. Each source is bounded and each mix convex; the bound exists before finishing rather than being supplied by the limiter.

GAP × CAPACITOR controls approach time. IMPACT × GAP changes the contrast between flight and collision. WELD × HOLD controls tail length; at WELD=1, HOLD adds no further decay. Renders are capped at 1.45 seconds and HOLD remains an ordinary finite one-shot. It has no sustain-loop region or seam claim.

## Integration

`LorentzPatch` participates in JSON decoding, `Presets`, native `Velocity` dispatch, CLI rendering/recall, complete-JSON favorite promotion and shell root detection. Six anchors per voice reproduce the listened default, clean/hard contact, close/wide gap and long-tail source positions. Their Kotlin output still needs listening.

`SynthKits.lorentz()` produces sixteen dry, finite pads: eight FUSE notes in C minor pentatonic from C1, followed by eight voice/control anchors. Every pad retains a replayable recipe. Classification comes from the actual rendered PCM; no blanket KICK mapping is promised for bright, long or high notes. The kit generator avoids a second render solely for metadata.

The Android picker is deliberately deferred: this session has no Android SDK. The existing picker count is unchanged. The CLI and JVM kit pipeline are the available entry points.

```sh
./gradlew :synth:generateLorentzAudition
./gradlew :synth:generateLorentzKit
./gradlew :cli:run --args='synth LORENTZ LORENTZ --preset 3 --macros GAP=.75 --velocity .8 --out /tmp/lorentz'
```

The audition task writes 26 actual Kotlin WAVs, full saved patches, a listening page, raw sample evidence and metrics under `testkit/lorentz-audition/`. The kit task writes MPC 3 and compatibility MPC 2 programs, the WAVs and editable `kit.json`, and checks both exports with the ordinary read-back path. Neither task commits generated audio.

## Validation and limits

The required probes cover raw agreement with the independent Python fixture, every macro corner, finite/bounded output, DC, tail periods, finite HOLD, macro timing, contact contrast, retirement of IMPACT, high-rate comparison, sanitation, scramble, velocity, patch/pad recall and the preset/kit registry. `DeterminismTest` carries a dedicated canary.

The sparse fixture samples 54 combinations of voice, register, GAP and IMPACT, including points around collision and contact release. It validates the source, not Python's approximate finishing. The high-rate probe is a relative waveform/filter comparison rather than a pure alias-rejection measurement.

Render work is O(frames), with constant-size planning and no mechanical cache. There is no phone timing guarantee. `generateLorentzAudition` records actual local JVM render times; those include cold/JIT effects and must not be presented as a phone benchmark.

## Why it is built this way

1. An analytic finite crossing guarantees the defining collision happens.
2. Smoothly retiring the free oscillator avoids an instantaneous phase-reset jump; cancellation during capture is still possible.
3. Different contact behavior gives both voices an identity after the flyer has stopped.
4. A protected clean target and finite modulation branch preserve the final sub root.
5. Native output, classification and touch remain separate from the accepted source equations so the handoff can be measured and auditioned.

## As built

Implementation basis: repository `46a046e8dd24ed50149c0fc1cf96b2a694d59af3`, with the accepted Python Audition 03 as the source reference. The owner heard and approved that Python pass. Kotlin source, output conditioning, new integration and MPC exports require their own checks; no Android or hardware listening verdict is inferred from source parity.


### Measured Kotlin evidence

The final current-base checks returned exit 0: 157 tests, with no failures, errors or skips (21 synth, 2 CLI and 134 shell). The synth selection includes 12 LORENTZ core/preset tests, the determinism canary and eight shared preset-registry tests. The command was `./gradlew --no-daemon :synth:test --tests '*Lorentz*' --tests '*LORENTZ collision recipes*' --tests com.snipsnap.synth.PresetsTest :cli:test --tests '*Lorentz*' :shell:test --tests '*Lorentz*' --tests '*ConventionTest*' --tests '*UserPresetsTest*'`. The selected synth, CLI and shell suites ran on this source; unchanged dependency tasks reused Gradle results. Integration base: `7cb8f34507c664ad671b952f93934c0bc53265c5`. Its sparse raw comparison covers 54 voice/register/GAP/IMPACT cases and 3,294 sample positions, with absolute tolerance 0.000002. All 128 macro corners passed raw bounds and finished-output checks. Tail periods were checked at three registers and the GAP/IMPACT corners. Removing only the collision branch caused the intended IMPACT contrast assertion to fail; the ordinary source was restored and the focused checks passed again.

The generator produced 26 actual Kotlin clips: 9 classified KICK and 17 PERC. Their maximum sample peak was 0.99 and maximum absolute DC about 0.000105. Local JVM render calls ranged from 11.75 to 59.88 ms, including cold/JIT effects; these are not phone measurements. MPC 3 and MPC 2 read-back passed all sixteen pads. The reader explicitly skips pad color, one-shot and humanize fields it does not understand; no hardware acceptance is implied.

The current-base generator reproduced all 26 published native WAVs byte for byte and passed both MPC read-back checks again. The hosted [Kotlin audition](https://lorentz-sound-audition.bertcalm.chatgpt.site) preserves the accepted Python pass beside it. Its listening copies use gain matching; native downloads and the [MPC kit](https://lorentz-sound-audition.bertcalm.chatgpt.site/lorentz-mpc-kit.zip) preserve the exported samples.

A separate `./gradlew --no-daemon test` run on the original `46a046e` base returned exit 1 at `:synth:test`: 4,047 of 4,048 synth tests passed, with no errors or skips. The sole failure was the shared expected-roster assertion missing LORENTZ’s twelve presets; CLI and shell were not reached in that command. That assertion now includes the bank and passed in the current-base checks above. The shared complete-JSON promotion check was extended to decode and compare the actual recipe and raw macro bits. Two stale starter counts inherited from the newer base were corrected after checking `StarterKits.ALL`. These results do not establish a clean full JVM run on the final current-base tree; that remains a CI gate, alongside Android compilation.
