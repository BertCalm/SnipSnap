# RUSH — swell, metal and pitched repeats

RUSH is SnipSnap's second compound effects board, adapted from **ONRUSH** in
the [Epic Chains design](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs%2Fspecs%2F2026-04-05-epic-chains-fx-design.md).
Its fixed order is **AUTO-SWELL → RING → DISTORTION → ENVELOPE FILTER → PITCHED ECHO**.
The sound-design mapping is a first listening candidate; numerical checks do
not establish a listening verdict.

## On the pad sheet

Open **TREATMENT**, then choose **RUSH** in the compound-board card. AMT starts
at 70% for a new board. Moving it stages a candidate and writes nothing until
**KEEP**. **HEAR ORIGINAL** and **HEAR RUSH** compare the preserved source and
the staged result in stereo, using the pad's current level and shape. Reopening
the pad selects its saved board and amount. **HIT** preserves the kept stereo
result.

KEEP always renders from the sound before any compound board, including that
sound's existing baked effects. Repeated amount edits do not stack. Choosing
RUSH on a WIDE pad and keeping it replaces WIDE from that same source; choosing
WIDE on a RUSH pad works in the other direction. The card identifies the active
board while staging its replacement. Every velocity layer is processed with
its velocity window preserved. Round-robin chains are refused.

**REMOVE** restores the complete original audio and recipe. Select the active
board to remove it. **UNDO** reverses the last KEEP or REMOVE, including a switch
between WIDE and RUSH, and restores that board's amount. Later level, pan,
tuning and shape changes survive REMOVE and UNDO. Undo is one step; a later
unrelated audio or recipe edit invalidates it. Remove the active board before
using generic treatments, changing SOFT HITS, stacking takes or using SPREAD.

This is an offline effect printed into each hit, with fresh state per render.
It is not a continuous live bus or a seamless-loop processor. Each triggered
sample carries its own printed echo tail.

## What the five stages do

1. **Auto-Swell** follows the linked input envelope and softens each detected
   onset with a rising gain envelope. It is a true input-triggered swell,
   rather than a reversed recording or a fixed filter sweep. Detection uses
   each input's linked peak as its reference, so a quiet copy can trigger the
   same timing as a loud copy.
2. **Ring** reuses SnipSnap's sine ring modulator at a fixed 180 Hz carrier.
   AMT increases its mix up to 38%. The two stereo channels share the carrier.
3. **Distortion** reuses the four-times-oversampled VALVE amp with its cabinet
   disabled. Drive, a small amount of sag and tone increase with AMT. This is
   an asymmetric tube saturation approximation of ONRUSH's hard-clip stage.
4. **Envelope Filter** uses the post-distortion input envelope to move a
   smoothed resonant low-pass filter. It responds to the incoming sound,
   rather than moving only because time has elapsed. Its follower is also
   relative to that stage's linked input peak.
5. **Pitched Echo** prints six decaying taps on a fixed 240 ms grid. The left
   side alternates unison and +7 semitones; the right alternates +7 and unison.
   The pitched taps use crossfaded read heads. This is a bounded feedforward
   echo, without feedback pitch accumulation, a BBD model or tempo sync.

AMT controls stage intensity; it is not a parallel dry/wet crossfade. Zero is
an exact bypass. The full board adds at most 1.5 seconds to the input. There is
no audition loudness matching in the production renderer. Ring and VALVE retain
their existing peak-matching behavior; the new stages use attenuation-only
peak guards. Both envelope controls follow changes within the source relative
to its linked peak. Scaling a source down alone should preserve their timing
and tone movement while producing a proportionally quieter result. No audition
gain is silently printed into the kept pad.

## Saved state and export

`Rush.process(Snip, amount)` is the deterministic production renderer.
`BoardRecipe` identifies and versions RUSH separately from WIDE, including its
stage order and AMT mappings. The existing `boardPad` wrapper and kit `.boards/`
snapshots preserve the baseline audio and opaque original recipe. The board
editor uses that baseline for amount changes and board switches.

Keep `.boards/` when copying a SnipSnap kit folder to preserve editing. Kit
merge and BREED carry retained board baselines into the new kit. Flat MPC/XPN
exports contain ordinary baked stereo WAVs; the MPC does not need a matching
effect processor or SnipSnap's edit history. Copy Last Treatment applies the
chosen board and amount to the destination pad's own source.

## Reproduce the listening proof

Run `./gradlew :synth:generateRushAudition`, optionally with
`-PrushOutput=/absolute/output/path`. Generated files live under
`testkit/rush-audition/` and are not committed. The generator uses the production
renderer, and writes 50 native 44.1 kHz PCM24 WAVs plus `manifest.json`:

- Four sources: PLUCK/NYLON, a finite RESIN held lead, VOX/CHOIR and THUMP/SNARE.
  Each has Original, 35%, 70% and 100% stereo clips and their mono folds.
- Five cumulative stages at 100% on the pluck, ending with the exact full-board
  output. These help identify where an attack, tone or tail changes.
- Quiet and loud pluck inputs, each Original and RUSH 70%. Quiet is 18 dB below
  loud **before** processing. All four clips share one audition gain, preserving
  the level difference. The followers use relative peak references, so this
  checks level invariance; it does not claim an absolute trigger threshold.

Main and stage clips are independently matched using SnipSnap's
120 Hz high-pass, loudest-200 ms RMS meter. This is not LUFS or a measure of
perceived stereo loudness. The quiet/loud group is deliberately not matched per
clip. Every mono WAV is `(L + R) / 2` after the stereo gain, without additional
gain, subject only to PCM24 rounding. The manifest records these policies.

Every phrase contains two identical freshly rendered hits. The generator
verifies deterministic repeat renders, preserves the entire printed tail and
leaves at least 250 ms silence before the next hit. Hit spacing is calculated
per source from its longest comparison, rather than truncating a long note or
overlapping the two tails. The held lead is a native 1.2-second RESIN held
render with a 10 ms attack, ordinary melodic source levelling and an explicit
40 ms endpoint fade. It is not presented as a seamless held loop.

The manifest captures Git HEAD, its tree, the working-tree dirty flag, hashes
of the actual selected renderer/source files, complete stage macro recipes,
input and printed-hit sample hashes, WAV hashes, frame counts, hit timing and
fold measurements. A dirty render is explicitly labeled; the Git commit alone
does not claim to identify uncommitted code.

## Phone and MPC check

1. On headphones, compare RUSH 35%, 70% and 100% against the original on a pluck,
   held lead, choir and snare. Judge whether each transformed attack is useful,
   the source remains identifiable and the pitched repeats stay musical.
2. Compare stereo and mono. Check for a hollowed-out source, lost attack or
   large changes in repeat level. Fold measurements support this check but do
   not replace listening.
3. Open a mono tonal pad, choose RUSH at 70%, and preview both versions. Leave
   without KEEP and confirm the pad remains unchanged. Then KEEP, close the
   sheet and play it from the kit; the stereo result should match the preview.
4. KEEP 35%, then UNDO to return to 70% without stacking. Switch to WIDE and
   KEEP, then UNDO; RUSH and its prior amount should return. Confirm the card
   shows the active type and amount after reopening and navigating pads.
5. Change LEVEL or SHAPE, then REMOVE RUSH. The original audio and complete
   recipe should return while those controls stay changed. UNDO should restore
   RUSH. Repeat on a velocity-layered pad at soft and hard velocities.
6. Retrigger the kept pad quickly, listening for clicks or unexpected cutoffs.
   Listen through the entire bounded tail. Export it to the MPC and compare
   the ordinary stereo sample, including soft/hard layers where present.

Phone playback, the useful intensity range and MPC listening remain manual
checks. The first audition does not establish live tempo synchronization,
continuous feedback between hits or seamless-loop behavior.
