# WIDE — editable stereo board

WIDE is the first compound effects board, accepted through the
[listening audition](https://snipsnap-wide-audition.bertcalm.chatgpt.site).
Its order is **TAPE → EQ → ENSEMBLE → SPRING**, independent of the existing
fixed `FxChain`. The production renderer reproduces all 38 audition WAVs
byte-for-byte when run through the audition generator's matching and phrasing.

## On the pad sheet

Open **TREATMENT → WIDE**. AMT starts at 70%. Moving it stages a candidate;
it writes nothing until **KEEP**. **HEAR ORIGINAL** and **HEAR WIDE** compare
the same source in stereo, with the pad's current level and shape. **HIT**
also preserves stereo on a pad carrying WIDE.

Each KEEP renders from the sound before WIDE, including its existing baked
effects. Repeated amount edits do not stack boards. Every velocity layer is
processed, preserving its velocity window. A round-robin chain is refused.
This is a printed per-hit effect, with fresh effect state on each hit, not a
continuous live bus or a seamless-loop processor.

**REMOVE** restores the original audio and complete original recipe.
**UNDO** reverses the last WIDE KEEP or REMOVE, including the previously kept
amount. Both preserve later level, pan, tuning and shape changes. Undo is one
step; a later unrelated audio or recipe edit invalidates it. Generic treatment
chips ask the user to remove WIDE first. Copy Last Treatment copies WIDE's
settings onto the destination pad's own source.

Remove WIDE before changing SOFT HITS, stacking takes or using SPREAD. Those
actions change the sample inventory that WIDE needs to restore the source.
Replacing a pad's audio from another screen clears its old WIDE recipe, so the
replacement is treated as a new sound.

## Saved state

`Wide.process(Snip, amount)` is the pure, deterministic renderer. AMT changes
parameter intensity, rather than mixing a delayed wet signal against the dry;
zero is an exact bypass. Nonzero settings retain the existing tape and chorus
delay/coloration, including near zero. Reverb adds at most Spring's existing
1.2-second tail. There is no audition gain in the production renderer.

`BoardRecipe` versions WIDE's processing order and mappings independently.
Typed `PadRecipe` can append a board after its existing source patch and FX;
older board-free recipes retain their JSON and rendering behavior.

The pad sheet stores an opaque `boardPad` wrapper containing the board,
its baseline snapshot ID and current main/layer inventory. `.boards/` inside
the kit holds content-addressed source WAVs and recipe snapshots, outside the
purged bin. Processed WAVs use new filenames, so another pad sharing the
original sample is not changed. A batch renders before adopting its files;
missing or damaged sources refuse before replacing the active pad.

Keep `.boards/` when copying a SnipSnap kit folder to preserve editing and undo.
Kit merge and BREED's retained pads carry WIDE's baseline into the new kit;
they start a new undo history and remain editable if the parent kit is removed.
Flat MPC/XPN exports carry the baked stereo WAVs and do not carry SnipSnap's
editing history, consistent with other treatments. No new MPC-side effect is
required. The original source and recipe are preserved verbatim; WIDE does not
move effects before a synth's existing landing amp.

## Validation

- `:synth:test`: WIDE/BoardRecipe/PadRecipe board tests cover accepted sound
  hashes, bypass, deterministic stereo rendering, tail limits and old recipes.
- `:shell:test`: BoardSheet/integration tests cover source retention, reload,
  nonstacked amounts, velocity layers, shared audio, metadata-preserving undo,
  damaged sources, replay and flattened stereo export.
- `:synth:generateWideAudition`: regeneration retains the audition WAV hashes.
- `:app:compileDebugAndroidTestKotlin`: the Android interaction suite covers
  staged amounts, KEEP, pad navigation, REMOVE and UNDO. Run it on a phone or
  through the repository's `emulator-tests` workflow to verify those gestures.

Phone listening and interaction remain the final check for stereo playback,
the staged controls and long rendering gestures on device.

## Phone check

1. Open a mono tonal pad, expand TREATMENT and leave WIDE at 70%.
2. Compare HEAR ORIGINAL and HEAR WIDE on headphones. Leaving without KEEP
   should leave the pad unchanged.
3. KEEP, close the sheet and play the pad from the kit. The stereo image
   should match the preview; reopen the sheet to see the saved 70%.
4. KEEP 35%, then UNDO. The pad should return to 70%, without stacking effects.
5. Change LEVEL or SHAPE, then REMOVE. Those controls should stay changed
   while the original audio and recipe return. UNDO should bring WIDE back.
6. Repeat with a velocity-layered pad, listening at soft and hard velocities,
   and export the kept result to the MPC as an ordinary stereo sample.
