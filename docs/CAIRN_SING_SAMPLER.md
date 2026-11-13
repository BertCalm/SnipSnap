# CAIRN SING sampler studies

The retained-SING sampler code came back from the recovery backup on
2026-11-13. It packages nine accepted SING recordings as five keygroup
studies of **one SING voice**: EARLIER and MOVING at C3–C5, and GROOVE 0, 50
and 100 at C4 only. These are five settings of one voice, not five new voices
or a finished CAIRN factory bank. `Cairn.kt`, `CairnPatch.kt`, `CairnSing.kt`
and `CairnTumble.kt` are unchanged, so every saved model keeps its sound.

The app does not offer these studies yet. The backup's phone picker needs the
nine native Float captures as Android assets, and this repository does not
carry them (see [What was left out](#what-was-left-out)).

## What is in the repository

- `CairnAcceptedSingSources` names the nine Wave 3 KEEP SING recipes: models
  4 (EARLIER) and 5 (MOVING, GROOVE) at roots 48, 60 and 72. Each source pins
  its original recipe bytes (in `synth/src/main/resources/cairn-accepted-sing/recipes/`)
  and the SHA-256 of its accepted native PCM24 cycle and full buffer.
- `CairnAcceptedSingContext` maps a SING render's two coordinate spaces: the
  kept later cycle and the full buffer with its real driven predecessor. It
  refuses any render whose PCM24 encoding differs from the accepted anchors.
- `CairnAcceptedSingDelivery` gives only the first 220 predecessor frames a
  raised-cosine intro, as the kept audition did. The loop body and the join
  window before it stay bit-exact. There is no natural attack or release.
- `CairnSamplerDelivery` reads a retained capture from a File or a bounded
  stream (705,600 Float bytes exactly, at most 65,536 metadata bytes), checks
  cancellation between reads, and refuses a capture that no longer encodes both
  PCM anchors. It owns the five studies' note zones: EARLIER and MOVING map
  48–53 to root 48, 54–65 to root 60 and 66–72 to root 72 (upper tie, at most
  six semitones of transposition). The GROOVE studies play C4 only. The loop
  starts at frame 88,200 of 176,400, and the sampler release is 0.08 s.
- `CairnSingInstrumentMaker` (shell) pins each capture's Float and metadata
  SHA-256, writes a new keygroup package with its original recipes, delivery
  policy and source manifest, and validates every byte before shared
  descriptor-last admission. It never replaces an existing instrument.
- `CairnSingInstrumentPicker` (shell) maps the five choice IDs to fresh
  instrument names. It takes the capture loader as an argument, so the caller
  owns asset access, and checks cancellation before and during admission.

## Tests

`CairnSingInstrumentMakerTest` (8 tests) needs no binary asset. Its fixture
renders each of the nine sources natively through
`CairnAcceptedSingContext.prepare`, then reads that buffer through the same
pinned `readCapture` path. On 2026-11-13 all nine renders matched both PCM24
anchors and the retained captures' Float SHA-256 pins. Today's engine therefore
reproduces the nine retained captures bit for bit on this JVM. This is a check,
not a regenerated asset: no capture file is written into the repository.

The test covers the five-program catalogue, bounded C3–C5 and C4 coverage,
refusal of unknown, truncated or substituted captures, the untouched loop body,
all five packages with their recipes and mapping, chords inside the captured
range, staged-byte tampering and rollback after an interrupted move.
`CairnSingInstrumentPickerTest` keeps the two checks that need no capture:
an unknown or cancelled choice opens nothing, and a broken loader leaves no
instrument behind.

```sh
./gradlew --no-daemon :shell:test --tests '*CairnSing*'
```

## What was left out

The backup carried 19 retained files under `app/src/main/assets/cairn-retained-sing/`:
nine native Float captures (`before-48`, `before-60`, `before-72`, `after-48`,
`after-60`, `after-72`, `groove-0`, `groove-50` and `groove-100`, each
`.full.f32le`, 705,600 bytes), their nine `.full.json` metadata files and a
`manifest.json`. The nine Float captures are binary audio and stay out of this
public repository until the owner decides where they live. The nine metadata
files are here as test resources under `shell/src/test/resources/cairn-retained-sing/`.
Everything that needs the Float captures stayed out too:

- `CairnSingAssetProvider` and the `CairnSingStudyPicker` composable, with the
  `App.kt` and `MakeScreen.kt` hooks that open MAKE → INSTRUMENTS → CAIRN SING.
  Without the assets every choice would fail.
- `CairnSingStudyPickerTest` (androidTest).
- `CairnSingCaptureInputsTest`. Eight of its nine checks read the shipped Float
  captures, and the ninth only guards that same stream path.
- Three `CairnSingInstrumentPickerTest` checks that admit real instruments from
  the shipped captures: all five choices, repeated choices beside an orphan
  descriptor, and cancelled admission.
- Shell's `processTestResources` step, which copied the app asset bank into
  the test resources.

Once the owner settles where the captures live, these pieces can come back
unchanged.

## Open gates

Owner sampler listening, Android playback, MPC hardware loading, native
velocity layers, round robin and the remaining CAIRN voices are open. See the
CAIRN section of [the acceptance gates](ENGINE_ACCEPTANCE_GATES.md).
