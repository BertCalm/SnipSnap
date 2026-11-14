# CAIRN SING sampler studies

The retained-SING sampler code came back from the recovery backup on
2026-11-13. It packages nine accepted SING recordings as five keygroup
studies of **one SING voice**: EARLIER and MOVING at C3–C5, and GROOVE 0, 50
and 100 at C4 only. These are five settings of one voice, not five new voices
or a finished CAIRN factory bank. `Cairn.kt`, `CairnPatch.kt`, `CairnSing.kt`
and `CairnTumble.kt` are unchanged, so every saved model keeps its sound.

The phone offers them under MAKE › INSTRUMENTS › CAIRN SING. The nine native
Float captures are not shipped: the phone renders each one the first time a
study needs it and keeps it only if it matches its pinned SHA-256 (see
[Rendering the captures on the phone](#rendering-the-captures-on-the-phone)).

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
  owns capture access, and checks cancellation before and during admission.
- `CairnSingRenderedCaptures` (shell) is that loader on the phone. It renders
  each capture natively, checks it against its pin, and caches it (see below).
  The nine `.full.json` metadata files ship next to it as shell resources
  (`shell/src/main/resources/cairn-retained-sing/`).

## Rendering the captures on the phone

The owner chose this route on 2026-11-14, so the 6.3 MB of Float captures stay
out of the public repository.

1. MAKE › INSTRUMENTS › CAIRN SING ▾ lists the five studies. Choosing one runs
   off the UI thread as a cancellable busy job ("MAKING CAIRN SING").
2. For each of the study's one or three sources, `CairnSingRenderedCaptures`
   looks in the app's cache (`cacheDir/cairn-sing-captures/`). It uses a cached
   capture only if it is exactly 705,600 bytes and its SHA-256 matches the pin.
3. A capture that is missing, damaged or truncated is rendered through
   `CairnAcceptedSingContext.prepare`, which already refuses a render whose
   PCM24 anchors differ. Its little-endian Float bytes must then match the
   Float pin before they are written (atomically) to the cache. The busy job
   counts the renders.
4. Any mismatch raises `CairnSingRenderedCaptures.Mismatch`. Nothing is cached,
   a damaged cached copy is removed, no instrument is made, and the phone says
   "CAIRN SING didn't match its kept recording on this phone, so nothing was
   made."
5. `CairnSingInstrumentMaker.readCaptures` then reads every pair again with its
   own Float, metadata and PCM24 checks. The rendered path cannot admit
   anything the shipped-asset path would have refused.

If the phone's cache is cleared, the next study simply renders again. On this
JVM all nine renders take about 2.5 s together; a phone is slower.

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
`CairnSingRenderedCapturesTest` (7 tests) checks the phone's loader on the
JVM:

- all nine native renders match their Float pins;
- a corrupted render (silence, one flipped low bit, or the wrong length) is
  refused and never cached;
- a damaged or truncated cached capture is rendered again, and one that cannot
  be repaired is removed;
- a good cache is used without rendering;
- cancellation stops between renders;
- a refused render never reaches the shelf;
- every metadata resource ships with the shell.

`CairnSingInstrumentPickerTest` (5 tests) admits real instruments from those
rendered captures: all five choices with their bounded ranges, repeated
choices beside an orphan descriptor, and rollback after cancelled admission.
It also keeps the earlier checks that an unknown or cancelled choice opens
nothing and a broken loader leaves no instrument.

Two androidTests cover the phone:

- `CairnSingStudyPickerTest` checks that the INSTRUMENTS bench offers all five
  studies and dispatches their IDs, and offers none while busy.
- `CairnSingRenderOnDeviceTest` renders all nine captures on the device,
  checks each against its pin, and admits MOVING from the rendered cache.
  This is the check that the phone's own floating-point maths reproduces the
  accepted captures.

```sh
./gradlew --no-daemon :shell:test --tests '*CairnSing*'
```

## What was left out

The backup carried 19 retained files under `app/src/main/assets/cairn-retained-sing/`:
nine native Float captures (`before-48`, `before-60`, `before-72`, `after-48`,
`after-60`, `after-72`, `groove-0`, `groove-50` and `groove-100`, each
`.full.f32le`, 705,600 bytes), their nine `.full.json` metadata files and a
`manifest.json`. The nine Float captures stay out of this public repository;
the phone renders them instead. The metadata files ship as shell resources.
Still left out:

- the asset `manifest.json` and the backup's `CairnSingAssetProvider`, which
  read the shipped captures; `CairnSingRenderedCaptures` replaces it;
- `CairnSingCaptureInputsTest`, whose checks read the shipped Float captures;
- shell's `processTestResources` step, which copied the app asset bank into
  the test resources.

## Owner listening

On 2026-11-13 the owner heard all five studies played as keys through the
reference sampler (`InstrumentEngine`, touch .8): EARLIER and MOVING at their
three recorded roots (C3, C4, C5), then at E3, A3, F4 and A4 (each a root
moved by the sampler) and a C major chord on C4; and GROOVE 0, 50 and 100 at
C4, each held seven seconds. The owner kept all seven clips (KEEP, no notes).
This covers the studies as the reference sampler plays them, not a phone or
MPC playback.

## Open gates

Playing the studies on a physical phone, MPC hardware loading, native velocity
layers, round robin and the remaining CAIRN voices are open. See the
CAIRN section of [the acceptance gates](ENGINE_ACCEPTANCE_GATES.md).
