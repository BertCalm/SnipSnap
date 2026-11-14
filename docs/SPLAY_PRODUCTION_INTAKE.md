# SPLAY accepted population registration

Source preparation, 2026-11-10. Base is the green integration source
`591d7d2b913fc00a8605351ee14ea6a1f61aa984`. This successor reconciles the
uncompiled recovery packet `627d72eb125da8f019f55b6d5baa96680740a627` with
that base, including its newer TEMPEST routes. No compiler, synthesis renderer,
JVM test or device test has run for this successor during preparation.

## Delivered source scope

`Presets.forVoice("SPLAY", "TONGUE")` and `Presets.all()` now expose the five
KEEP population performances through `SplayFactoryPresets`. Their complete
accepted names, model 2, macros, MIDI 60, velocity .8 and seed 41001 come from
`SplayAcceptedBank` without a second authored recipe table. Counts 1, 2, 4, 8
and 16 are five settings of one voice. They do not satisfy the six-voice
melodic instrument requirement.

The original `SplayPresets` file stays byte-identical and separately callable.
The CLI keeps its historical presets in positions 1 through 5 and appends the
five accepted population settings at positions 6 through 10. The default
invocation and existing numbered invocations retain their old meaning. New
factory recipes can also be replayed through their complete native `--patch`
inputs. The five older provisional recipes are not added to `Presets`.

The real `Patches` dispatcher, both exhaustive `Velocity` arms, authored-root
`SynthRoot`/`Spread`, full-JSON `UserPresets` output, and CLI input/sidecar routes
are preserved from the recovery packet with current-base context resolved.
Neither sealed `Patch` nor its other implementations are replaced or removed.

## Preserved evidence and explicit limits

`Splay.kt`, `SplayTrajectoryBank.kt`, `SplayPatch.kt` and `SplayPresets.kt` remain
byte-identical to packet 627 and its archived original source. The 11 text
resource files (the manifest, five native inputs and five original diagnostic
wrappers) are unchanged; the five matched WAVs are pinned by SHA-256 only (see below). Original raw-PCM
hash references and the model-1 raw-PCM canary are unchanged. There is no new
population law, model bump, seed interpretation or output normalization.

One original test assertion in `SplayPatchTest` changes to reflect registration:
the historical provisional names must remain absent while the factory contains
the five exact accepted inputs. Its renderer/decoder/velocity assertions remain
intact. `SplayAcceptedBankTest` now asserts the exact factory bank instead of an
empty roster. On this repository its matched-recording comparison checks the
regenerated WAV against the recording's pinned SHA-256, because the recordings
themselves are not shipped; the raw-PCM comparisons are unchanged. This is not a
claim that every archived test file is still byte-identical.

Native `patch.render()` and the audition room's raw output multiplied by its
fixed matched gain remain separate paths. The existing PCM replay test compares
the latter to the actual heard recordings, not to native loudness normalization.
Original raw Float buffers are unavailable; the source-pinned old/current Float
probe remains required before any later control-law or acoustic revision.

Phone picker, starter kit, six distinct melodic voices, positive HOLD/STRUM,
keygroup delivery, native alternate contacts/RR and timbral velocity remain open.
Current velocity is the original gain-only behavior. Generic sample variation
and five population presets do not close those requirements. This source packet
does not change sound approval, publish an audition, or certify device behavior.

## On this repository

This source came back from the recovery backup on 2026-11-13. The five matched
PCM16 recordings (`pc-1.wav` to `pc-16.wav`) are binary audio and stay out of this
public repository; their SHA-256 values remain pinned in `SplayAcceptedBank` and the
manifest. The text fixtures (manifest, five native `.patch.json` files and five
diagnostic `.recipe.json` wrappers) are in `synth/src/test/resources/splay-accepted/`.
Test results for this branch are recorded in the SPLAY section of
[the acceptance gates](ENGINE_ACCEPTANCE_GATES.md).

## Why it is built this way

- Factory lookup points to exact heard inputs, so registration cannot redraw a
  sound through a new population mapping or renamed recipe.
- Historical preset positions remain stable for saved scripts and old workflow.
- Real registry tests use the actual sealed implementation graph, so missing
  integration arms remain compiler or contract failures.
- PCM provenance and native output stay distinct, so replay compares the same
  output path the owner heard.
- Partial instrument scope remains explicit while production wiring advances.
