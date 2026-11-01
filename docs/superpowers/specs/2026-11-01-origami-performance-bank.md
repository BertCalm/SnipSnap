# ORIGAMI performance bank

Status: implementation and audition preparation; the new preset shapes await listening.
Repository baseline: `82a1f9c74086d574d4de3eb1b7dc0944069c89d6`, read 2026-11-01.

The characteristic gesture is **strike softly or firmly → the paper opens or folds → a stable note remains → release**.

## Scope and landing

This is a preset and instrument bank over the accepted model-4 sources. It adds
one BLOOM and one UNFOLD recipe per voice, each with velocity FOLD enabled.
CRANE supplies finite keys; FAN and STILL also supply four held instruments.
The original six factories remain first in their respective voice lists, with
their names, seeds, velocity, controls, STEADY gesture and LEVEL expression intact.
The sixteen-pad starter explicitly uses those six references and keeps its layout.

The nearest siblings are PRISM's developing partial groups and CREASE's touch
deformation. ORIGAMI already supplies the needed source/reflection relationship,
so this round authors existing behavior. It adds no voice, macro, renderer,
spectral operation, rack effect or live processing.

## Chosen direction

The user selected an expressive bank. COUPLE the existing fold gesture with
the existing touch law, then package it as complete presets and sampled keys.
This has a clear playing action, low implementation cost and direct value for
the current engine. STACKing a new effects chain would introduce another
source of color; MUTATEing the source would reopen the accepted voicing.
Those remain alternatives rather than requirements for this bank.

## Recipe contract

| Voice | Presets | FOLD | MOTION | Gesture role |
| --- | --- | ---: | ---: | --- |
| CRANE | Paper Bloom / Paper Unfold | .65 | .35 | A quick change around the struck key. |
| FAN | Fifth Bloom / Fifth Unfold | .65 | .55 | An opening or closing sustained fifth color. |
| STILL | Soft Bloom / Soft Unfold | .62 | .70 | A slower change after the soft onset. |

Each pair differs only in gesture direction. All six keep TUNE, CREASE, TURN
and WARP at .5; model 4, seed `20261012`, velocity .85 and no absolute MIDI.
These amounts and names are authored shapes, not listening verdicts. The
existing expression law scales the fold below that saved velocity ceiling.
Soft/mid/hard audition touches therefore use actual velocities .255/.51/.85.
The hard finite take must reproduce the saved factory exactly.

The six macros retain their established meanings. TUNE follows the played
root; CREASE fixes the reflection position; FOLD sets the selected color;
TURN translates the upper bank; WARP changes spacing; MOTION sets the travel
time in these two gestures. Velocity does not change their clock or geometry.

```text
complete factory recipe + played note + touch
    -> existing expression law and once-only fold gesture
    -> accepted root/source/reflected bank
    -> existing conditioning and finite note
                                  or
    -> production held maker -> 9 zones x 3 layers -> sampler sustain/release
```

Verified entry points are `OrigamiPresets`, `Presets.forVoice`, `Patches.edited`,
`SynthKits.origami`, `OrigamiPadMaker`, `InstrumentStore` and `KeyHit`.
`references()` and `performance()` keep one source of truth for bank membership.
Factory lookup exposes twelve patches; it does not turn the starter into 22 pads.

## Audition and export

The reproducible shell generator supplies exact recipes, soft/mid/hard finite
examples, short musical performances and four actual FAN/STILL keygroup exports.
Native and whole-take matched audio are separate. Matching must preserve the
relative levels of notes within a performance. Long STILL takes cross at least
three actual loop wraps before note-off; the manifest records their positions.

The held banks use the existing .6-second release and nine C3–C5 sampled roots.
The shared export policy stretches the outer playback zones to MIDI 0–127;
that uses edge-sample transposition rather than adding rendered roots.
Each preserves the production `.xty`, sibling TrackData directory, `.xpm`,
27 PCM24 WAVs, playback sidecar and complete `.origami-keys.json` authoring recipe.
Downloads target MPC. On Android, select the factory and use MAKE INSTRUMENT;
the app currently has no keygroup ZIP import route.

No new simulation or cache is introduced. Each voice's two recipes share
geometry and can use the existing gesture basis cache. Export banks serially
and release their decoded samples before loading the next. Report measured
generation time and storage; do not turn host timings into phone promises.

## Verification and listening ledger

Required checks cover unchanged original factories and starter slots, complete
factory selection/save/recall, CLI output for all four choices per voice,
opposing source/fold journeys, matched soft/hard color contrast and unchanged
unfolded source audio. Export evidence must describe actual files, layer windows,
roots, loops, key-up release and memory payloads. Browser playback remains native
HTML audio, including the self-contained starter.

The risks are a quick CRANE gesture being too subtle, the softest touch losing
useful contrast, and slow STILL motion becoming repetitive over long holds.
Numbers can establish different behavior and healthy files; the owner decides
whether the six sounds earn their places. Physical MPC playback remains a
hardware check until an actual instrument import is tried.

## As built

The six factories, frozen starter membership and integration checks are implemented.
Final render, export, build and browser results are recorded with the generated bank
and publication handoff after execution. No new sound-quality verdict is assigned.

## Why it is built this way

- New presets make the accepted fold gestures and touch response immediately playable.
- Paired directions share the same source and geometry, making the movement easy to compare.
- The six original recipes and sixteen starter pads remain dependable references.
- Real layered keygroups carry the response into the sampler instead of demonstrating only a rendered preview.
- Long held examples expose repeated motion and note-off behavior that short single notes cannot establish.
