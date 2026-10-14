# The screen plan — every screen in the four-place shape

*Written 2026-10-14 for the SDK session that will build the shape in
[`MODERN_SHAPE.md`](MODERN_SHAPE.md) under the decisions in
[`DIRECTION_2026_10.md`](DIRECTION_2026_10.md). Every page below was
written against the screen's own source: its KDoc, the models it binds,
the regions it draws and the doors in and out. Nothing here is drawn
from memory of the app. Models the pages name as **new** are the fast
follow, built and tested in `:shell` before the SDK session starts
(§6). Nothing in this document changes code.*

## 0. How to read a page

Every screen gets one page with the same six parts:

- **Today** — what it is and where it is, from its KDoc.
- **Lands in** — place ▸ stage, or which screen it becomes a bench of.
  A *place* is one of the four tabs. A *stage* is a segment inside
  PROJECT or TAPE. A *bench* is a screen you take one thing to and come
  back from; it has a back chip and lives under the stage that sent you.
- **Keeps / moves / goes** — what is unchanged inside, what moves to
  another screen, what is removed because the shape made it redundant.
- **Binds** — the `:shell` or `:kit` model it reads today, and the new
  model it will read. Where the new model is named, §6 specifies it.
- **Modern look** — only what is specific to this screen; the general
  rules are in `MODERN_LOOK.md` §3.
- **SDK checklist** — the exact binding work, as lines a session can tick.

Three rules hold on every page:

1. **No screen's internals change** unless the page says so. The shape
   moves screens; it does not redesign benches.
2. **State survives a stage switch.** Today `App.kt` draws screens with a
   plain `when (screen)`, which throws away every local `remember` on a
   tab change (the J20–J26 class of findings). The navigation model (§6)
   holds per-place state, so switching PADS → BEAT → PADS returns to the
   same pad, bank and open box. A stage switch is not a tab switch.
3. **The dock is on every screen** except the three that are *all* LCD
   with no chrome: PLAY's fullscreen grid, the bubble, and LIVE SNAP's
   camera view.

## 1. The frame

The frame is what every screen sits inside. It replaces the title bar,
the twelve-tab menu row and the status bar.

### Title bar

Today: `SNIPSNAP.EXE` on a gradient, a NOTE chip while the workshop is open.
**Becomes:** the wordmark on the left; on the right the open project's
name and the gear (SETUP, HELP). The NOTE chip stays where it is while the
workshop is open. The title bar is flat with a hairline under it.

### Place row

Today: twelve tabs in four groups, scrolling, with ◂ ▸ cues.
**Becomes:** four places, always visible, no scroll: **SHELF · TAPE ·
PROJECT · MAKE**. The selected place is a filled chip. The row is the
first thing TalkBack reads after the title.

### Stage row

New. Under the place row on PROJECT (`PADS · BEAT · OUT`) and on TAPE
(`DECK · CUT`). A segment row, the control CHOP and GROOVE already use.
SHELF and MAKE have no stage row; they are lists.

### Dock

New, at the foot of every screen, on the LCD surface. Three regions:

- **Transport:** ▶ / ■ for the project's beat, `94 BPM · 2 BARS`, and
  `● REC` when BEAT can record. Playing the beat here keeps playing when
  you move to a pad bench, which today means leaving GROOVE and losing it.
- **Capture:** the light and counter the shelf's ArmControl shows today
  (`ARMED · 00:41 ON TAPE`, `SNIP`), or `LISTEN · MIC` when nothing is
  armed. This is the one control that was on the shelf and nowhere else.
- **Now:** what is playing or what just happened, and the standing door.
  The toast's words land here and stay until replaced; the door (`CUT ▸`,
  `OUT ▸`) stays until taken. The 7-second offer dwell retires.

### Status bar

Today: three cells, "where you are · SHELF: n · busy line or kit name".
**Goes.** Where you are is the place row and the stage row. The shelf
count is on the SHELF. The busy line and the kit name are the dock's
*Now* region. This is the one piece of chrome removed outright.

### Toast and message box

The toast's text goes to the dock's *Now* region (above). The message
box is unchanged: a modal card for the landing notes, the blocked
captures and the preflight refusals. It is the only modal in the app and
stays that way.

### The bubble

Unchanged. It rides over other apps while a session is armed and knows
nothing about the frame. The dock's capture region is the in-app view of
the same session.

## 2. The map

Every screen that exists today, where it lands, and whether its insides change.

| Today | Reached by | Lands in | Insides |
|---|---|---|---|
| KitsScreen (SHELF) | tab | **SHELF** | changes: projects list added above kits (§3.1) |
| KitScreen (KIT) | tab | **PROJECT ▸ PADS** | changes: kit switcher, pad strip, action rows folded (§3.3) |
| TapeScreen (TAPE) | tab | **TAPE ▸ DECK** | unchanged |
| ChopScreen (CHOP) | tab | **TAPE ▸ CUT** | unchanged; source line always present |
| ExportScreen (EXPORT) | tab | **PROJECT ▸ OUT** | changes: a second card for the session (§3.5) |
| PlayScreen (PLAY) | tab | **PROJECT ▸ PADS**, fullscreen toggle | the in-window grid is PADS'; the fullscreen pop-up stays |
| GrooveScreen (GROOVE) | tab | **PROJECT ▸ BEAT** | transport moves to the dock |
| OrbitScreen (ORBIT) | tab, GROOVE ▸ | **PROJECT ▸ BEAT ▸ ORBIT** (bench) | unchanged |
| ArrangeScreen (SONG) | GROOVE ▸ | **PROJECT ▸ BEAT ▸ SONG** (bench) | unchanged |
| LOOP (own Activity) | SHELF ▸, SNIPS ▸ | **PROJECT ▸ BEAT ▸ LOOP** (bench) | folded into the frame (§3.4) |
| SynthScreen (SYNTH) | tab | **MAKE ▸ SYNTH** | unchanged |
| SnapScreen (SNAP) | tab | **MAKE ▸ SNAP** | unchanged |
| LiveSnapScreen | SNAP ▸ | MAKE ▸ SNAP ▸ LIVE (bench) | unchanged |
| SurfaceScreen (SURFACE) | tab, SYNTH ▸ | **MAKE ▸ SURFACE** | unchanged |
| KeysScreen (KEYS) | shelf instrument row | **MAKE ▸ INSTRUMENTS** (bench) | unchanged |
| PropertiesScreen (SETUP) | tab | **gear ▸ SETUP** | gains the LOOK row |
| HelpScreen (HELP) | tab, SETUP ▸ | **gear ▸ HELP** | rewritten per place |
| SnipsScreen | SHELF ▸ | SHELF ▸ SNIPS (bench) | unchanged |
| DeletedKitsScreen, DeletedSnipsScreen | SHELF ▸, SNIPS ▸ | SHELF ▸ BIN (one bench, two lists) | merged (§3.1) |
| XRayScreen, DoublesScreen | SHELF ▸ TOOLS | SHELF ▸ TOOLS (benches) | unchanged |
| PadSheetScreen | hold a pad, DOUBLES GO ▸ | PADS ▸ pad strip OPEN ▸, or the hold | unchanged |
| GrainField, TapeSplice, StackTakes, SpreadOverlay | PAD SHEET ▸ | benches under PADS | unchanged |
| SplitScreen | KIT action row | PADS ▸ THIS KIT ▸ SPLIT (bench) | unchanged |
| TakesBinScreen | KIT action row | PADS ▸ THIS KIT ▸ VERSIONS (bench) | unchanged |
| PadCaptureScreen | hold an empty pad | PADS ▸ pad strip CAPTURE ▸, or the hold | unchanged |
| BubbleOverlay | the service | unchanged | unchanged |

Thirteen tabs become four places and five stages. Every bench keeps its
back chip, and the back chip now names the stage it returns to.

## 3. The pages

### 3.1 SHELF

**Today.** `KitsScreen`: an LCD header with filter and sort chips; a
scrolling list of kit rows (hold to RENAME / DELETE), then INSTRUMENTS,
ROOMS and IN THE BIN; an empty-shelf panel with the first-run note; a
footer of six buttons (NEW KIT ▸ STARTERS, the ArmControl, SNIPS ▸,
LOOP ▸, DELETED KITS ▸, TOOLS ▸); two pop-up menus.

**Lands in.** SHELF, the first place.

**Keeps / moves / goes.**
- Keeps: kit rows and their hold actions, INSTRUMENTS, ROOMS, the starter
  menu, TOOLS, the filter and sort chips, the pick-a-kit modes (ASSIGN,
  BREED) with their headers.
- Adds: a **PROJECTS** section at the top: one row per project (name,
  `2 KITS · 94 BPM · edited 2h ago`), `NEW PROJECT ▸`. Opening a row
  opens PROJECT. On a shelf with no projects the migration has already
  made one per kit, so this section is never empty when the kit list is
  not.
- Moves: the ArmControl to the dock (every screen). LOOP ▸ to BEAT.
  DELETED KITS ▸ and the bin section into one **BIN** bench with two
  lists (kits, snips); the two deleted screens are the same layout today.
- Goes: nothing else.
- The empty state: the first-run note reads the four places, not four
  tabs (`MODERN_SHAPE.md` §3).

**Binds.** Today `KitShelf` (an `:app` class), `StarterKits`, `ShelfFilter`,
`Rooms`, `Ages`, `KitStore`. New: **`ShelfModel`** (§6.2) over `ProjectStore`
and `KitShelf`, which lists projects, runs `ProjectMigration.adopt` on
open, and answers "which projects hold this kit" for the kit row's
subtitle.

**Modern look.** Rows with hairlines, no bevels; the kit name in serif
italic (the user's words), the counts in mono. Projects and kits are two
headed lists on one page, not two tabs.

**SDK checklist.**
- [ ] Add the PROJECTS section above the kit list, fed by `ShelfModel.projects()`.
- [ ] `NEW PROJECT ▸` creates an empty project and opens PROJECT ▸ PADS with the switcher reading `+ FROM SHELF`.
- [ ] Opening a project sets it in the navigation model; opening a kit row opens the first project that holds it, else makes one.
- [ ] Remove the ArmControl from the footer (it is in the dock).
- [ ] Merge DELETED KITS and the in-bin section into the BIN bench.
- [ ] Rewrite the empty-shelf note to the four places; update `ConventionTest`'s loop-names law.

### 3.2 TAPE ▸ DECK and TAPE ▸ CUT

**Today.** `TapeScreen`: cassette title row, the waveform LCD, the
readout, then a scrolling deck in three groups (TRANSPORT, EDIT, the
commit row with KEEP / INSTANT KIT / CATCH A HIT, DESTINATIONS). CATCH
mode replaces the deck with a bank grid. `ChopScreen`: the slice list
and the CUT box over whatever TAPE last committed, with RE-CHOP and SEND
TO PADS.

**Lands in.** TAPE, with two stages: **DECK** (today's TAPE) and **CUT**
(today's CHOP). KEEP on DECK moves you to CUT; the dock's door says
`CUT ▸` and stays. CATCH and RE-TRIM are modes of DECK, as now.

**Keeps / moves / goes.**
- Keeps: everything inside both screens.
- Moves: TAPE's TRANSPORT row stays (it is the tape's transport, not the
  beat's; the two are different machines and the dock's ▶ is the beat).
  INSTANT KIT and CATCH DONE land on PROJECT ▸ PADS. READ AS GROOVE and
  COPY GROOVE land on PROJECT ▸ BEAT. SEND TO PADS lands on PADS and the
  dock's door reads `OUT ▸`.
- Goes: the "offer" toast after KEEP; the stage row and the dock door
  replace it. CHOP's three-tier silent source fallback: CUT always names
  its tape in the header, because it is the tape's own second stage.

**Binds.** Today `TapeDeckModel`, `CatchModel`, `Retrim`, `Dig`,
`SnipStore`, `PeaksPyramid`; `ChopReviewModel`, `KitBuilderModel`,
`CutRatings`, `Hum`, `Ladder`, `TeachLog`. Unchanged. The landing targets
become the project's active kit (`Project.activeKit`), not "the open kit".

**Modern look.** The waveform LCD is the dark surface; the deck below it
is paper with hairline groups. The three group legends become eyebrows.

**SDK checklist.**
- [ ] Give TAPE a stage row; DECK and CUT are the two stages; KEEP switches the stage.
- [ ] CUT's header always shows the source (`sourceFromKit` logic extended to the commit path).
- [ ] Replace `offer(Copy.KEEP_OFFER, CHOP)` with a dock door; same for CHOP → EXPORT.
- [ ] INSTANT KIT, CATCH DONE and SEND TO PADS land on `project.activeKit` and open PROJECT ▸ PADS.
- [ ] Keep the CATCH grid and RE-TRIM header exactly as they are.

### 3.3 PROJECT ▸ PADS

**Today.** `KitScreen`: LCD header (kit name, key, BPM, bank, pad count);
bank tabs; the 4×4 grid; two legend lines; the provenance line; then a
scrolling action area of nine buttons on six rows (VERSIONS + BIN,
REMIX BANK B, KEY, SPLIT, DUST ALL, BREED, GRANULAR, SLOW & FREEZE,
SHARE) plus an inline KEY or texture panel. `PlayScreen` draws the same
grid for performance with a PANIC and a ⟳ FULL button and a MIDI line.

**Lands in.** PROJECT ▸ PADS, the hub.

**Keeps / moves / goes.**
- Keeps: the grid, bank tabs, the hold-to-open gesture, the pad glow,
  velocity by tap position, the provenance line, the fullscreen pop-up.
- Adds above the grid: the **kit switcher**, one line:
  `KITCHEN ▾ · 2 KITS IN THIS PROJECT · + FROM SHELF`. Tapping the name
  lists the project's kits; `+ FROM SHELF` opens SHELF in pick mode and
  returns. Adds below the grid: the **pad strip**, one line for the
  selected pad: `A02 · snare · CRUSH 35% · CHOKE 1 — OPEN ▸`, or for an
  empty pad `A08 · EMPTY — CAPTURE ▸ · FROM SNIPS ▸`. Tap selects and
  plays; OPEN ▸ is the tap door into the pad sheet the reviews asked for.
- Folds the action area into one closed group, **THIS KIT**, that reads
  what it holds when closed (`KEY Am · BANK B EMPTY · 3 VERSIONS`), the
  way the pad sheet's boxes do, and opens to the same nine actions in a
  two-column grid. KEY and the texture panels open inline inside it as
  today.
- Moves: SHARE ▸ to OUT (it is an output). PLAY's PANIC and MIDI line to
  the PADS footer; PLAY's ⟳ FULL to the header. The two legend lines
  become one, since the strip now says what a hold does.
- Goes: the PLAY tab. The pad-sheet discovery hint and its preference,
  since the strip is a permanent door.

**Binds.** Today `KitBuilderModel`, `TextureKits`, `Breed`, `KeyPicker`,
`PadBanks`, `PeaksPyramid`, `VoiceAllocator`, `Provenance`, `MutateSheet`,
`PadSheetBoxes`, `DustPrints`; PLAY's `MidiPads`, `StreamFacts`,
`MidiWire`. New: **`PadStrip`** (§6.4), a pure function from a pad to its
line, built on `PadSheetBoxes`' closed-box strings; the switcher reads
`Project` and `ProjectStore`.

**Modern look.** Pads are card-white with a hairline and the class colour
as a 4dp bottom edge; the selected pad gets a 1dp accent ring and the
strip names it (never colour alone). Labels in serif italic. The grid is
the only element on the page with any depth.

**SDK checklist.**
- [ ] Draw the switcher from `Project.kits` / `activeKit`; switching calls `Project.activated` and saves through `ProjectStore`.
- [ ] Draw the pad strip from `PadStrip.line(pad)`; OPEN ▸ calls `openPadSheet`, CAPTURE ▸ opens PadCapture, FROM SNIPS ▸ opens SNIPS in pick mode.
- [ ] Wrap the nine actions in a `GroupBox` legend THIS KIT, closed by default, with `PadSheetBoxes`-style closed text.
- [ ] Move PANIC, ⟳ FULL and the MIDI line in from PlayScreen; delete the PLAY tab; keep `PlayScreen`'s fullscreen pop-up as the ⟳ target.
- [ ] Move SHARE ▸ to OUT.
- [ ] Hold bank, selected pad and open box in the navigation model's PADS state, not in `remember(entry.dir)`.

### 3.4 PROJECT ▸ BEAT, with ORBIT, SONG and LOOP as benches

**Today.** `GrooveScreen`: LCD header, the program row (CAPTURED · SWING ·
HALF · SPARSE · YOURS), the needle-roll, the take row, then a scroll
with TRANSPORT, SHAPE THE GROOVE and WRITE IT OUT · OR TAKE IT FURTHER,
and a pinned ● RECORD. `OrbitScreen`: the ring rail and a swapping
control panel, reached from GROOVE's ORBIT ▸ or its own tab.
`ArrangeScreen`: sections, a readout, PLAY / REROLL. LOOP: a separate
Activity, six track columns, a BPM row and BOUNCE, with its own fixed
palette and no title bar.

**Lands in.** PROJECT ▸ BEAT is today's GROOVE. ORBIT, SONG and LOOP are
three benches off it, reached from one row: `ORBIT ▸ · SONG ▸ · LOOP ▸`.
The beat is the project's (`ProjectStore.beat`), played on the active
kit (`DIRECTION_2026_10.md` §2).

**Keeps / moves / goes.**
- Keeps: the program row, the needle-roll, the step editor, SHAPE THE
  GROOVE, the record flow, ORBIT and SONG whole.
- Moves: GROOVE's TRANSPORT group (▶ PLAY / ■ STOP, the bounce button)
  to the dock, where it keeps playing across benches. ● RECORD stays
  pinned on BEAT and mirrors into the dock as `● REC` while BEAT is the
  stage. ORBIT's own tab goes; its `◄ GRV` back chip becomes `◄ BEAT`.
  LOOP leaves its Activity and becomes a bench inside the frame under the
  dock, keeping its grid, with the scheme's colours instead of its fixed
  palette. Its SNIPS ▸ door opens SHELF ▸ SNIPS in pick mode.
- Goes: nothing from GROOVE's shaping or writing rows. WRITE IT OUT
  (MIDI, CHART) stays here *and* is listed under OUT, because it writes.

**Binds.** Today `GrooveEdit`, `GrooveFeel`, `GrooveProgram`, `GrooveStore`,
`GrooveVariations`, `KitPreview`, `LiveRecord`, `MidiGroove`, `Audition`,
`Chart`, `VoiceAllocator`; ORBIT's `Orbit*` family; `Arranger`; LOOP's
`Session`, `SessionBuilder` (the `:loop` one), `Bouncer`. Change:
`GrooveStore.load(kitDir)` reads become `ProjectStore.beat(projectDir)`
for the project's own patterns, with the kit's clips as the fallback a
fresh project starts from. New: **`DockModel`** (§6.3) carries the
transport state out of GrooveScreen.

**Modern look.** The needle-roll is the dark surface and keeps its
scanlines at the sun's opacity. The program row is a segment row. The
three benches are a row of three hairline chips.

**SDK checklist.**
- [ ] Rename the tab's screen to BEAT; route GROOVE's transport through `DockModel`.
- [ ] Read and write the beat through `ProjectStore.beat` / `saveBeat`; seed from the active kit's `GrooveStore` when the project has none.
- [ ] Replace the ORBIT tab and GROOVE's ORBIT ▸ with one bench row: ORBIT ▸ · SONG ▸ · LOOP ▸.
- [ ] Host `LoopGrid` inside the frame as a bench; retire `LoopActivity`; theme it from `LocalScheme`.
- [ ] Hold program, swing, feel, seed and the editor state in the navigation model's BEAT state (today App lifts some of these; lift the rest).

### 3.5 PROJECT ▸ OUT

**Today.** `ExportScreen`: LCD header, a scroll of PREFLIGHT, DESTINATION,
FORMAT and the dub progress card, a pinned WRITE KIT button, and the
done state with READ BACK, SHARE ▸ and WRITE ANOTHER. The session format
(`MPC SESSION (.XPJ)`) exports the open kit alone. SHARE ▸ THIS KIT AS
ONE FILE sits on KIT's action row; GROOVE's BOUNCE renders the beat to
SNIPS.

**Lands in.** PROJECT ▸ OUT, with two cards and a list:

1. **THIS KIT** — today's wizard, over the active kit, unchanged. Format
   explanations open by default, as J34 decided.
2. **THE PROJECT** — the session: every held kit on a track, the beat on
   the active one, the project's tempo. One button, `WRITE SESSION`, the
   same preflight card above it, the same card leg below it. Its refusal
   names a missing kit (`ProjectKitsMissingException`).
3. **ALSO** — `SHARE KIT ▸` (from KIT's row), `BOUNCE THE BEAT ▸` (GROOVE's
   bounce, listed here too), `MIDI ▸` and `CHART ▸`.

**Keeps / moves / goes.**
- Keeps: the wizard whole, the card leg, the read-back, DubStamp.
- Moves: SHARE from KIT; the list of writers from BEAT (listed, not moved).
- Goes: the EXPORT tab; the offer toast after SEND TO PADS, replaced by
  the dock's standing `OUT ▸`.

**Binds.** Today `ExportWizardModel`, `LandingNote`, `DubStamp`,
`ExportFormat`, `Preflight`. Change: `ExportWizardModel` gains the
session over a `Project` (§6.6), calling `SessionBuilder.build(project,
shelf, …)` and `SessionMixdown.render(project, shelf)`. Both exist.

**Modern look.** Two cards with hairlines; the dub progress reel stays
(it is the one animation the rules allow, and it tells the truth).

**SDK checklist.**
- [ ] Render the wizard as card one over `project.activeKit`.
- [ ] Add card two bound to `ExportWizardModel`'s project session stage.
- [ ] Add the ALSO list; move SHARE's handler in from KitScreen.
- [ ] Delete the EXPORT tab; the dock door `OUT ▸` opens this stage.

### 3.6 MAKE

**Today.** Three tabs (SYNTH, SNAP, SURFACE), each a lab that sends a pad
or a tape into the open kit; KEYS, an instrument player reached from the
shelf's instrument rows; LIVE SNAP from SNAP.

**Lands in.** MAKE, a list page with four rows that open the labs as
full-height benches: **SYNTH ▸**, **SNAP ▸**, **SURFACE ▸**, **INSTRUMENTS ▸**
(the shelf's instrument rows, each opening KEYS). Each row's subtitle
says what it last made (`last: RESIN · KICK, to A09`). A lab's SEND TO
PAD lands on the project's active kit; SURFACE's → TAPE lands on TAPE ▸
DECK, as today.

**Keeps / moves / goes.**
- Keeps: every lab whole. SNAP's overlays (DRAW, FIELD, LIVE) and its
  ORBIT doors (PATH RING, CHORD) unchanged, the latter landing on BEAT ▸
  ORBIT.
- Moves: the instrument rows from SHELF to MAKE ▸ INSTRUMENTS. MAKE
  INSTRUMENT on SYNTH and the pad sheet still writes to the shelf's
  `Instruments/`; only where the list is shown moves.
- Goes: three tabs.

**Binds.** Unchanged for every lab (`SynthScreen`'s engine families,
`SnapScreen`'s `Photo*`, `SurfaceScreen`'s `TouchSurface` and
`SurfaceStore`, `KeysScreen`'s `InstrumentStore` and `MidiKeys`). The
MAKE page itself is new and reads `ShelfModel.instruments()` plus a
"last made" line the labs already toast.

**Modern look.** The labs' scope LCDs are the dark surface; their
sliders become hairline tracks with a filled thumb. SURFACE has no LCD
header today and keeps none.

**SDK checklist.**
- [ ] Build the MAKE list page; each row opens the lab as a bench with `◄ MAKE`.
- [ ] Move the instrument rows here; KEYS's back chip reads `◄ MAKE`.
- [ ] Every lab's slot chooser lands on `project.activeKit`.
- [ ] Hold each lab's unkeyed state (engine, voice, macros; photo and line; mode and panel) in the navigation model's MAKE state so leaving to hear a pad and coming back does not reset the lab. This is the single largest state-loss fix in the plan: SYNTH, SNAP and SURFACE lose everything on a tab switch today.

### 3.7 The gear: SETUP and HELP

**Today.** `PropertiesScreen`: the scheme picker, TEACH THE MACHINE, four
read-only facts, HELP ▸, and the workshop behind seven taps on the title.
`HelpScreen`: one LCD panel of boot lines, THE LOOP and 21 WORTH KNOWING
lines; 13th of 13 tabs.

**Lands in.** A gear in the title bar opens a two-row sheet: **SETUP ▸**
and **HELP ▸**. One tap from every screen.

**Keeps / moves / goes.**
- SETUP keeps everything and gains a **LOOK** row above the schemes
  (CLASSIC / MODERN, the `Look` axis) with the schemes under it grouped
  by look. The seven-tap knock stays; it is a workshop door, not a user
  door.
- HELP is rewritten as five short sections, one per place and one for
  the dock, each three to five lines, with the MPC formats named in the
  first section's first line (the front door says who it is for). The 21
  WORTH KNOWING lines are redistributed under the place they belong to.
- Goes: two tabs.

**Binds.** `Schemes`, `SchemeId`, `Copy`. Change: `Scheme.look` (§6.5);
`Copy.HELP_*` restructured by place, with the convention tests that read
them updated.

**SDK checklist.**
- [ ] Gear in the title bar; sheet with SETUP ▸ and HELP ▸.
- [ ] LOOK row in SETUP, writing the look beside the scheme preference.
- [ ] HELP from the new `Copy` sections.
- [ ] Delete the SETUP and HELP tabs.

### 3.8 The benches, one line each

Unchanged inside; only the way in and the back chip change.

| Bench | From | Back chip | Note |
|---|---|---|---|
| PAD SHEET | PADS strip OPEN ▸, or the hold, or DOUBLES GO ▸ | `◄ PADS` | its five boxes untouched; `padSheetBox` stays lifted |
| GRAIN FIELD, SPLICE, STACK, SPREAD | PAD SHEET | `◄ PAD` | unchanged |
| SPLIT | PADS ▸ THIS KIT | `◄ PADS` | unchanged |
| VERSIONS + BIN | PADS ▸ THIS KIT | `◄ PADS` | unchanged |
| PAD CAPTURE | PADS strip CAPTURE ▸, or the hold on an empty pad | `◄ PADS` | unchanged |
| SNIPS | SHELF | `◄ SHELF` | → PAD lands on the active kit's pick; → LOOP opens BEAT ▸ LOOP |
| BIN | SHELF | `◄ SHELF` | DELETED KITS and DELETED SNIPS as two headed lists |
| X-RAY, DOUBLES | SHELF ▸ TOOLS | `◄ SHELF` | unchanged |
| ORBIT, SONG, LOOP | BEAT | `◄ BEAT` | §3.4 |
| LIVE SNAP, DRAW, FIELD | SNAP | `◄ SNAP` | unchanged |
| KEYS | MAKE ▸ INSTRUMENTS | `◄ MAKE` | unchanged |

## 4. Journeys, counted off the structure

Taps counted on the code's own doors, today against the plan. Not
measured on a device; the sim (§6.7) will measure them.

| Journey | Today | Plan | What changed |
|---|---|---|---|
| Cold start to a sound | SHELF ▸ NEW KIT ▸ STARTERS ▸ pick: 2 | 2 | same |
| Capture, cut, pads, out | TAPE ▸ KEEP ▸ (toast) CHOP ▸ ▸ SEND ▸ (toast) EXPORT ▸ ▸ WRITE: 5, two of them timed toasts | KEEP (stage moves) ▸ SEND ▸ OUT ▸ WRITE: 4, no timed step | the stage row and the dock's standing door |
| Treat a pad while the beat plays | GROOVE ▸ PLAY ▸ KIT ▸ hold ▸ treat ▸ KIT ▸ GROOVE: 7, and the beat stopped on the tab change | dock ▶ ▸ PADS ▸ tap ▸ OPEN ▸ treat: 5, the beat keeps playing | the dock |
| Open a treated pad's bench, no gesture known | none (hold only) | tap ▸ OPEN: 2 | the strip |
| Export the whole project as a session | not possible in the app | OUT ▸ WRITE SESSION: 2 | the project |
| Reach HELP | drag ▸ tab 13: 2 | gear ▸ HELP: 2, from anywhere | no drag |
| Tune a synth, hear it on the grid, tune again | SYNTH ▸ SEND ▸ KIT ▸ tap ▸ SYNTH (state lost) | SYNTH ▸ SEND ▸ PADS ▸ tap ▸ MAKE ▸ SYNTH (state kept) | the navigation model's per-place state |

## 5. Decisions taken in this plan

Each is a call the SDK session should not have to make, and each can be
reversed before any of it is built.

1. **The status bar goes.** Its three cells are covered by the place row,
   the SHELF and the dock.
2. **LOOP comes inside the frame.** It is the one screen with its own
   Activity and palette; as a bench under BEAT it gets the dock and the
   scheme. If the Activity has to stay for the engine's sake, it stays
   under the dock as a hosted view, which is the SDK session's call.
3. **KEYS lives under MAKE.** It is a player, not a maker, but it plays
   what MAKE makes, and the instrument list is where you find it.
4. **PLAY is a mode of PADS**, not a place. The fullscreen grid stays as
   the ⟳ target, which is the part of PLAY that is different from KIT.
5. **TAPE gets a stage row**, so CUT belongs to its tape. This is the
   smallest change that makes CHOP always know its source.
6. **The kit's action row becomes one closed group** that reads what it
   holds. Nine buttons on six rows is the densest thing on the hub and
   most of them are weekly, not hourly, actions.

## 6. The models — the fast follow

Built and tested in `:shell` before the SDK session, so the session binds
rather than designs. Names are proposals.

### 6.1 `Navigation`
A pure state machine: `Place { SHELF, TAPE, PROJECT, MAKE }`, `Stage`
per place (`DECK, CUT`; `PADS, BEAT, OUT`), a bench stack, and a back
stack over all of it, plus **per-place state** records (PADS: bank,
selected pad, open box; BEAT: program, swing, feel, seed; MAKE: each
lab's settings; TAPE: the deck's selection and zoom). Back pops a bench,
then a stage change, then a place change, in that order. Tests: every
door in §2 is a transition; Back never lands on SHELF from inside
PROJECT unless SHELF was the previous place.

### 6.2 `ShelfModel`
Over `ProjectStore`, `ProjectMigration`, `KitShelf`'s file layout: the
projects list with subtitles, the kit list with "held by" subtitles,
`openProject`, `newProject`, `addKitToProject`. Runs `adopt` on open.
Tests over a temp shelf, like `ProjectStoreTest`.

### 6.3 `DockModel`
`transport: Stopped | Playing(bpm, bar) | Recording(bar)`, `capture:
Idle | Armed(seconds) | Snipped`, `now: Line(text, door: Door?)`, where a
`Door` is a place/stage target and a label. `say(text)` replaces the
line; `offer(text, door)` sets a door that stays until `take()` or the
next `offer`. Tests: an offer survives a stage change and a `say`
without a door.

### 6.4 `PadStrip`
`line(pad: KitPad?, slot: Int): Strip` → `Strip(id, name, facts, doors)`
built on `PadSheetBoxes`' closed-box strings so the strip and the sheet
never disagree. Tests: a treated pad, a raw pad, an empty pad, a pad
with layers.

### 6.5 `Scheme.look`
`enum class Look { CLASSIC, MODERN }` and `val look: Look = CLASSIC` on
`Scheme`; VENT becomes `MODERN`. `SchemesTest` asserts the picker groups
by look. Data only; the drawing follows in the SDK session.

### 6.6 `ExportWizardModel` session stage
A `project` entry point beside the kit one, calling the two project
overloads that already exist, with the same READY → WRITING → COMPLETE
stages and the missing-kit refusal worded by `Copy`. Tests over a temp
shelf with two kits and a beat.

### 6.7 The sim
`UatSimTest` gains a run over `Navigation`: the journeys in §4 as
transitions, with tap counts asserted so the table above becomes a
measurement rather than a count by hand.

### 6.8 Copy
Place names, stage names, the first-run note, HELP by place, the dock's
lines, and the convention tests that read them. The roster-count law and
the loop-names law both need the new names before anything compiles.

## 7. What waits for the SDK session

Only Compose: the frame (title bar, place row, stage row, dock), the
switcher and the strip on PADS, the MAKE list page, the gear sheet, the
BIN bench's two lists, and hosting LOOP. Every one of those is a binding
to a model in §6, and the checklists in §3 are the work, in order.
