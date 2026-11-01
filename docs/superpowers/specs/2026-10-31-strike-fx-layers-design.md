# STRIKE FX on an ordinary pad: DRY, ROOM and ECHO layers from one sample

*Design, 2026-10-31. Written after the owner's phone audition of the STRIKE FX
demo starter (verdict: clear) and a short design conversation the same day.
It follows the desktop handover at `docs/handoffs/2026-10-31-strike-fx-desktop.md`,
which names this as the next milestone. File and line references are to the
default branch at `a21d9529e`. Every decision marked **owner** was taken by the
owner in that conversation. Every decision marked **call** was made by the
designer, and the owner can overturn it.*

## 1. Intent

The owner's goal from the start of the STRIKE work: soft and hard hits should
change the sound, not just the level, with a control one thumb can move while
the other hand plays pads. The STRIKE strip and the STRIKE FX demo starter
deliver that for four demo pads. This milestone brings it to a pad the owner
captured or printed themself.

The owner's picture of the first use, in their words: a snare they captured,
"dry at bottom, room in the middle, slapback echo on top". Two knobs to shape
the room and the slapback, an audition button to hear the result before
committing, then keep it.

## 2. Goals

- From a pad's sheet, make three velocity layers from the pad's own sample:
  DRY (bottom), ROOM (middle), ECHO (top), named so the LAST row on the pads
  screen reads them.
- Two knobs shape the result before it is made: ROOM for the room size, SLAP
  for the slapback repeat time.
- AUDITION renders the three versions in memory and plays them in a row.
  Nothing is written until KEEP.
- KEEP writes the layers to the pad and stores the knob settings in the pad's
  recipe, so reopening the kit, rebuilding the pad, or importing an export
  with its sidecar regenerates all three layers.
- The bank travels into the supported MPC exports the way the demo's does.
- Nothing renders when a pad is played. The strip picks a cached layer, as
  today.

## 3. Non-goals

- Continuous live modulation of a ringing note. The strip picks one of three
  stepped layers; the thumb's position is continuous but the choice is not.
  The handover and `docs/STRIKE_STRIP.md` keep this separate on purpose.
- More than one response at a time. One pad gets one bank (DRY, ROOM, ECHO).
  Other banks (brightness, four layers) are later work once this is heard.
- Changing how the STRIKE strip maps position to layer or gain
  (`shell/.../StrikeStrip.kt`, `PadHit.kt`). Playback is untouched.
- Changing the demo starter. `StrikeDemoKit` keeps its fixed settings; it may
  later be re-expressed through the new recipe shape, but not in this
  milestone.

## 4. Anti-goals

- A version of this that renders on pad-down, or on knob movement. The owner
  chose an explicit AUDITION button over live re-rendering (**owner**).
- A bank that cannot be rebuilt. The demo's weakness is that its recipe
  regenerates only the ECHO layer; this milestone exists to fix that for user
  pads. If KEEP writes files whose settings are not in the recipe, it has
  failed.
- Losing the owner's original sample. DRY must be the original bytes, and
  CLEAR must put the pad back exactly as it was.

## 5. What exists, and what this builds on

Read before planning; the handover's code map section has the longer list.

| Mechanism | Where | How this milestone uses it |
|---|---|---|
| Velocity layers on a pad: 1 to 4, ascending windows, the last layer's file is the pad's main file | `kit/.../Kit.kt:13-24`, `:68`, `:114-121` | The bank is three layers. ECHO is the top layer and therefore becomes the pad's main file; DRY and ROOM are `_v1.wav` and `_v2.wav`. This is the same arrangement as the demo starter and as SOFT HITS. |
| Velocity windows and `_vN.wav` naming | `shell/.../StackTakes.kt` (`windows(n)`), `KitBuilder.kt` (`freeLayerNames`), `kit/.../KitAssembler.kt:136-152` | Use `StackTakes.windows(softCount = 2)` (two soft zones under the main layer, so three windows), the same helper the SOFT HITS and stacked-takes doors use, so a sidecar import matches on identical windows. Use `freeLayerNames` for the `_vN.wav` names. Do not invent a second split. |
| The existing door that adds layers to an ordinary pad | `shell/.../KitBuilder.kt:410-455` (`addGhostLayers`), called from the pad sheet's SOFT HITS toggle at `app/.../PadSheetScreen.kt:1681` | Model the new shell verb on it: same preconditions (no printed board; the pad's main file readable), same file writing, same commit path (`commitPadEditNow`). |
| Offline rack | `synth/.../FxChain.kt:105` (`process(snip)`), `Spring.kt` (SIZE, TONE, MIX), `Echo.kt` (TIME, REPEAT, TONE, MIX, HEAD) | ROOM is a spring chain, ECHO an echo chain, each fed the DRY snip independently, as `StrikeDemoKit.kt:16-17, 26-32` does. |
| The demo's settings | `StrikeDemoKit.kt:16-17`: spring `SIZE .42, TONE .7, MIX 1`; echo `TIME .9, REPEAT .55, TONE .8, MIX .9`; `PadRecipe(..., terminalFadeMs = 4f)` | The knobs' starting positions (**call**, see 6.2). |
| Pad recipe, version 2 | `synth/.../PadRecipe.kt:21-73`, `VERSION = 2` at `:134`; `fromJsonValue` rejects other versions at `:149` | Extended to version 3 with a `layers` list (**owner**: shape 1). Readers accept 2 and 3. |
| Recipe readers | `Breed.kt:162`, `RecipeReplay.kt:109`, `PedalSlate.kt:174, :361`, `SynthWork.kt:43`, `PadSheet.kt:405`, `SynthScreen.kt:3922` | All go through `PadRecipe.fromJsonValue`, so the version change is one place; each reader's behaviour on a banked recipe is listed in 6.5. |
| Verbs that refuse layered pads | `KitBuilder.kt:151, :532, :591, :656, :819, :1447-1454` | Unchanged. They already protect a bank from being half-rewritten. |
| Export and sidecar | `kit/.../KitExporter.kt:154-166, :203, :230-232`; `ImportSidecar.kt:9-49` | Already carry every layer, its window, its name and the recipe bytes. The bank travels for free once the recipe holds it. |
| One audition voice on the pad sheet | `app/.../PadSheetScreen.kt:415-432`, `fun audition(target: Snip, level, shape)` at `:535` | AUDITION plays the three in-memory snips through this voice. |
| Strip playback | `shell/.../StrikeStrip.kt:28-38`, `PadHit.kt:36-41, :146-165`, `app/.../PadEngine.kt:212-216, :350-376` | Untouched. Layer files are cached at kit open as today. |

## 6. Decisions

### 6.1 Where it lives (**owner**, placement **call**)

A STRIKE FX section on the pad sheet, beside SOFT HITS (**call**: the two are
siblings; both turn one sample into velocity layers). It shows two knobs, an
AUDITION button, a KEEP button, and, once a bank is kept, a CLEAR button and
the three layer names.

Copy, in the house voice (facts, no personality): the section is titled
STRIKE FX; the knobs are ROOM and SLAP; the buttons are AUDITION, KEEP and
CLEAR. A kept pad's sheet shows "DRY · ROOM · ECHO" as the layer names. The
builder picks the component for the knobs from what the synth screen already
uses for macros; no new control type.

### 6.2 The two knobs (**owner**: two knobs; mapping **call**)

- ROOM, 0..1, drives the spring chain's SIZE. Its default is the demo's .42
  (about 0.52 s decay). TONE .7 and MIX 1 are fixed.
- SLAP, 0..1, drives the echo chain's TIME. Its default is the demo's .9
  (about 293 ms). REPEAT .55, TONE .8 and MIX .9 are fixed.
- Direction (**call**): more ROOM is a bigger room; more SLAP is a slower
  slapback (longer TIME). If the owner finds "more SLAP = faster" more natural
  on the phone, invert the display mapping only; the stored value is the
  chain's TIME.
- The knob's value is the macro's value. ROOM at .42 means spring SIZE .42;
  SLAP at .9 means echo TIME .9. Nothing is stored twice: the sheet reads the
  knob positions back from the kept recipe's chains (6.5), and there is no
  scaling between knob and macro to drift through. The seconds the macro maps
  to (`Spring.kt:48`, `Echo.kt:50-51`) are display text only, if shown at all.

The fixed macros are fixed in this milestone so the result stays within what
was heard clear on the demo. A later round can expose them.

### 6.3 AUDITION (**owner**)

Renders DRY, ROOM and ECHO in memory from the pad's main file (or, when the
pad already has a kept bank, from its DRY layer file, see 6.4) and plays them
in a row through the sheet's one audition voice: DRY, then ROOM, then ECHO
(**call**: one second apart, each at the pad's level). A second AUDITION
during playback restarts the sequence. Turning a knob does not render.
Nothing is written.

Render cost: the demo made twelve WAVs in 288 ms on a desktop. Three renders of
one pad are expected under 200 ms on the phone; the builder measures and, if
it is slower, shows the sheet's usual busy state rather than blocking the UI.

### 6.4 KEEP (**owner**), and what it writes

KEEP commits through the same edit path as SOFT HITS (`commitPadEditNow`).
The shell verb (**call**: `KitBuilder.strikeFxLayers(slot, room, slap)`):

1. Reads the source. For a pad with no bank: the pad's main file, exactly as
   it stands, with whatever its own recipe (patch, fx, treatment, pedals,
   board) already made of it. That file is what the owner hears today, and it
   is DRY. For a pad with a kept STRIKE FX bank: its DRY layer file, which is
   the same bytes. The layer chains never run on a raw patch render.
2. Renders ROOM and ECHO from the source with the two chains and the 4 ms
   terminal fade. Each chain runs at the source's sample rate and channel
   count (`FxChain.process` takes and returns a `Snip`); the tail is bounded
   by `FxChain`'s existing cap (`MAX_CHAIN_TAIL_SECONDS`, `FxChain.kt:171`),
   so each wet layer is at most the source length plus one second.
3. Writes files in this order so no byte is lost: copy the main file to
   `_v1.wav` (DRY), write ROOM to `_v2.wav`, then overwrite the main file
   with ECHO. Names come from `freeLayerNames`; windows from
   `StackTakes.windows(softCount = 2)`; display names DRY, ROOM, ECHO. Layer files the
   bank replaces (soft hits, stacked takes) are deleted from the kit folder
   the way `clearGhostLayers` deletes them today. A second KEEP on a banked
   pad reads `_v1.wav`, rewrites `_v2.wav` and the main file in place, and
   leaves `_v1.wav` untouched.
4. Stores the recipe (6.5) on the pad.

Preconditions, as `addGhostLayers` has them: no printed board on the pad. A
pad with SOFT HITS layers, or stacked takes, is replaced by the bank after the
sheet's confirmation (**call**: the owner guessed "replaced"; the confirmation
is the house's standing rule for destructive pad edits). The confirmation
names what is being replaced. A pad has one layer bank at a time: when a
STRIKE FX bank is kept, the sheet shows SOFT HITS as off, and turning SOFT
HITS on goes through the same confirmation in the other direction.

CLEAR restores the pad to its single sample: the main file becomes the DRY
layer's bytes, the `_v1.wav` and `_v2.wav` files are deleted, `velocityLayers`
is emptied, and the recipe's `layers` field is removed. Because KEEP left
every other recipe field as it was, removing `layers` is the whole undo; no
copy of the prior recipe is kept (**call**: this replaces an earlier idea of a
`before` field, which would have nested across exports). A pad whose bank
replaced SOFT HITS does not get its soft hits back; CLEAR is to the single
sample.

### 6.5 Recipe version 3 (**owner**: extend `PadRecipe`)

`PadRecipe` gains:

```
layers: List<LayerRecipe>?     // null for every recipe that exists today
LayerRecipe(name: String, fx: FxChain?, terminalFadeMs: Float?)
```

Rules:

- `VERSION` becomes 3. `fromJsonValue` accepts 2 and 3; a version 2 object
  reads with `layers = null` and round-trips unchanged except for the version
  number, which is written as 3 only when the recipe is re-saved. `toJsonValue`
  omits `layers` when null, so an unbanked pad's JSON differs from today only
  in the version number.
- A banked recipe lists the layers bottom to top, and its list length equals
  the pad's `velocityLayers.size`. The last entry describes the main file.
  `fx == null` means "the source, unprocessed" (DRY).
- `PadRecipe.init` today requires one of `patch`, `fx`, `board` or `pedals`
  (`PadRecipe.kt:75`). A non-null `layers` becomes a fifth way to satisfy it
  (**call**), so a captured pad with no recipe can carry a bank-only recipe.
  `Breed.recipeOf` and `warnIfCorruptRecipe` keep treating "present but
  unparsable" as corrupt; a bank-only recipe parses.
- The `patch`, `fx`, `treatment`, `amount`, `alias`, `board` and `pedals`
  fields are kept exactly as they were on the pad before KEEP. They describe
  how the source (DRY) was made; `layers` describes what was made from it.
  No `source` field: a bank's source is by definition its first layer's file.
- Rebuild semantics. The existing stages of `render()` (synth source) and
  `process(snip)` (captured source) produce the source as they do today; that
  result is DRY. A new bank-returning variant (**call**: `renderBank()` /
  `processBank(snip)`) then applies each `LayerRecipe.fx` and terminal fade to
  DRY and returns the snips bottom to top, DRY first. The single-snip methods
  keep their signatures and meaning. For a synth pad, DRY from `render()`
  must equal the kept `_v1.wav` byte-for-byte; render determinism is already
  a house law, so this is a test, not new work.
- Where rebuild is used: (a) re-import of an export whose sidecar restores
  a banked recipe onto matching files needs no rebuild (the files are
  there); (b) PASTE (`RecipeReplay`, 6.5 below); (c) bank-B twins.

Reader behaviour on a banked recipe (one line each, so the builder can check
them off):

- `RecipeReplay` is PASTE, not rebuild in place. Pasting a banked recipe onto
  another pad applies the bank to the target pad's own main file: the target's
  existing recipe fields stay, `layers` is copied, and the three files are
  written as KEEP writes them (**call**). This is the natural meaning of
  "paste STRIKE FX onto this pad too". PASTE onto a printed-board pad is
  refused with the existing door copy.
- `Breed.recipeOf` and the bank-B twins (`KitBuilder.kt:423`): a banked pad's
  twin is a banked pad; the twin re-renders its own three files from its own
  source with the same `layers`.
- `PedalSlate` (`:174, :361`): pedals already refuse layered pads
  (`KitBuilder.kt:819`); the recipe shape does not change that.
- `SynthWork.kt:43` and `SynthScreen.kt:3922`: read `patch` only; `layers`
  is ignored. The synth screen does not author banks.
- `PadSheet.kt:405`: shows the bank's layer names and the two knob values
  recovered from `layers[1].fx` (ROOM = spring SIZE) and `layers[2].fx`
  (SLAP = echo TIME).

### 6.6 Export and import

No new work expected: `KitExporter` writes all zones and their windows, the
headline sample is the top zone (ECHO), names and the recipe bytes ride in the
sidecar, and `ImportSidecar.restore` re-attaches them when the files and
windows match. The builder adds one test per supported format proving a kept
bank survives export and re-import with names, windows and the version 3
recipe intact (7).

### 6.7 What the LAST row shows

Unchanged mechanism: `StrikeStrip.layerHit` reads `displayName`. A kept pad
shows DRY, ROOM or ECHO after a hit. No change to `app/.../StrikeStrip.kt`.

## 7. Tests the milestone owes

- `:synth` `PadRecipeTest` (or the existing recipe test class): version 2
  reads with `layers = null` and re-saves as version 3 with identical fields;
  version 3 round-trips `layers`; a bank's rebuild equals KEEP's files
  byte-for-byte for a fixed source (determinism is already a house law for
  renders).
- `:shell` `KitBuilderTest` (or a new `StrikeFxLayersTest`): KEEP on a plain
  pad writes three ascending windows, DRY equals the source bytes, ROOM and
  ECHO are bounded and length-capped, names are DRY/ROOM/ECHO; KEEP over SOFT
  HITS replaces; CLEAR restores the single sample and the prior recipe;
  printed-board pads are refused with the existing copy.
- `:shell` law in `ConventionTest`: every recipe with `layers != null` has
  `layers.size == velocityLayers.size` across the starter kits (cheap, and it
  will catch the demo starter if it is later moved onto the new shape).
- `:kit` `StandaloneExportSidecarTest` and `ImportSidecarTest`: a kept bank
  survives export and re-import for each supported format.
- `app/androidTest` `PadSheetScreenTest` or a new `StrikeFxSectionTest`:
  the section fits a narrow phone beside SOFT HITS; AUDITION does not change
  the kit; KEEP then CLEAR leaves the pad as found; the layer names show.
- `StrikeStripTest` (shell) already proves a three-layer pad is reachable
  from the strip; add one case using a pad built by the new verb.

## 8. Constraints

- No DSP on the performance path. The strip and `PadEngine.hit` are not
  touched.
- The 1..4 layer invariant and "last layer is the main file" invariant in
  `Kit.kt` are not relaxed.
- Recipe JSON written by today's app must still read. No migration step; the
  version bump is lazy.
- Render budget: AUDITION and KEEP are each one pad; both finish inside the
  sheet's existing busy affordance.
- Copy is facts in the house voice; no new toast text beyond what the edit
  path already emits, plus one confirmation line when a bank replaces
  existing layers.
- The gate for the PR is the house gate: `./gradlew --no-daemon test -x :app:test`
  with an SDK, CI's four jobs green on the synced head, and the owner's phone
  listen of a bank made from their own snare before the PR is called done.

## 9. Left to the builder

- Knob component choice and exact layout within the sheet's section grammar.
- Whether AUDITION's three plays are spaced by a fixed second or by each
  snip's length plus a gap; the owner hears and decides after the first build.
- The exact names of the shell verb and the bank-returning recipe methods.
- The confirmation line's wording when a bank replaces SOFT HITS or stacked
  takes, in the house voice (facts, no personality).
- Whether the demo starter is re-expressed through version 3 in this PR or a
  follow-up (the design permits either; the law in 7 is written to tolerate
  both).

## 10. Open after this milestone (not decided here)

- A brightness bank, and four-layer banks.
- Exposing the fixed macros (spring TONE/MIX, echo REPEAT/TONE/MIX).
- Continuous modulation of ringing tails, which needs a separate playback
  design.
