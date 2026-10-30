# SnipSnap UI clarity: one modern look, one grammar, plain words

*Design, 2026-10-27. Written after a studio review on 2026-10-26: an
emulator walk of a debug build in both modern schemes, plus three code
audits of the default branch at `f6a47dba3`. File and line references
are to that commit unless they say otherwise. Every decision marked
**owner** was taken by the owner in that conversation. Every decision
marked **call** was made by the designer, and the owner can overturn it.*

## 1. Intent

The owner's words: the UI "is not easy to understand". It should be
"self explanatory, easy to get to features, well designed sub menus and
nesting". And: "I prefer clarity and usability over cutesy-ness or kitsch
… I would prefer professionalism and user experience over cult appeal."

SnipSnap should read as a professional instrument. Every word and every
visual on screen either helps you understand something or helps you do
something. The owner is the only user so far. That makes breaking UI
changes cheap now, and the cost only rises from here.

The studio review found that the top level is right. The four places,
their stages, the dock and the gear were built in October and work.
What is hard to understand lives below that level:

- Sub-screens are built nine different ways.
- 14 of the 26 sub-screens the navigation model defines bypass it.
- Back labels contradict where Back goes.
- One screen often carries several names.
- Some features are reachable only by holding.
- In the modern look, the text is all caps at 12 sp, and surfaces barely
  separate from the background.
- Loading shows as one static word ("DUBBING…") at the foot of the screen.

## 2. Goals

- **G1. One look.** Modern is the only look, with two colourways (VENT dark,
  OPENSKY light) and an Automatic setting that follows the phone.
- **G2. Every sub-screen is a real screen.** It is routed through
  `Navigation`, survives a place switch, and has a back label that is always
  true.
- **G3. Three containers, one meaning each:** screen, sheet and section. No
  other sub-menu pattern.
- **G4. One plain name per thing.** It is the same on the tab, the button
  that opens it, its back label, Help and every message.
- **G5. Everything is reachable by a visible control.** A hold is only ever a
  shortcut.
- **G6. Readable at arm's length.** Sentence case for sentences, a 14 sp body
  size, contrast floors on text and on control edges, and no truncated
  button labels.
- **G7. Loading is obvious.** Anything slower than 0.2 s shows motion where
  you tapped and in the dock, with a count wherever a count is knowable.
- **G8. A pad editor a new user can find their way around.**

## 3. Non-goals

- Redesigning the top level. The four places, stages, dock, Back order
  and `PadsState` stay. The October shape review commended them
  (`docs/UX_SHAPE_REVIEW_2026_10.md:361-371`).
- Any change to sound, DSP, engines, presets or render code. The only
  exception is reporting progress from code that already loops (§7.6).
- New musical features. The only additions are UI over operations that
  already exist: project rename and delete, and an engine list.
- Tablet or landscape layouts, localization, predictive-back animation.

## 4. Anti-goals (a failure even if it works)

- Joke, period or metaphor copy coming back in any form. That includes the
  boot text, `.EXE`, "the machine", DUB, SIDE A, TAPE PROPERTIES and
  DEMAGNETIZING.
- An action reachable only by a long-press, a multi-tap or a gesture.
- A back label naming a place Back does not go to.
- Something working for more than 0.2 s with nothing moving on screen. A
  progress bar that moves on a timer rather than on the work also counts
  (§7.6).
- A loading state that looks like an empty state. Today a cold shelf says
  "NOTHING TAPED YET." while it is still loading.
- One large PR rewriting `App.kt`. It is about 233 KB, and parallel
  sessions merge into it daily.
- Renaming anything on disk. UI names change; folder names, file formats
  and MPC export names do not. `snips/`, `Projects/`, `kit.json`,
  `groove.json` and the `.xpm`/`.xpj` vocabulary stay exactly as they are.

## 5. Constraints

- **Repo rules.** `.claude/skills/steward/SKILL.md` governs commits, PRs,
  merge commits and the never-list. Where an owner decision below changes
  a law test, the test is rewritten to the new rule in the same PR. A test
  is never skipped or loosened to get green.
- **Proof order.** Prove on the JVM first: `:shell` models, `UatSimTest`
  journeys and `ConventionTest` laws. Then compile `:app`, then
  before/after screenshots on the emulator. Visual PRs use the AVD
  `snipsnap_pixel` and package `com.snipsnap.app.brassgrow`. The 2026-10-26
  emulator walk is the baseline: OPENSKY and VENT, SHELF, TAPE, PROJECT,
  PADS, the pad editor with TREATMENT open, BEAT, OUT, MAKE, SYNTH,
  SETUP and HELP.
- **Gradle.** Never run two Gradle invocations in one worktree at the same
  time. Fast gate:
  `./gradlew --no-daemon :shell:test :kit:test :cli:test :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.
  Leave `:synth:test` (1.5 h) to CI unless synth code is touched.
- **Accessibility.**
  - Touch targets of 48 dp minimum. Today's offenders: `PropertiesScreen`
    44 dp ×8, `OrbitScreen` 36 dp ×7, `TitleChip` 34 dp.
  - TalkBack labels on every control.
  - The live region for status, as the dock has today (`Frame.kt:423`).
  - Honour the system "Remove animations" setting, which nothing honours
    today.
- **State.** A sub-screen's state survives a place or stage switch. That
  is the reason for routing (`Navigation.kt:9-17`).

## 6. Decisions already taken

| # | Decision | Who | Supersedes |
|---|---|---|---|
| D1 | Retire CLASSIC. Modern is the only look; VENT is the default. In flight as PR 1, `claude/retire-classic-look`. | owner | `DIRECTION_2026_10.md:129` |
| D2 | No hidden jokes, eggs or stylized voice. | owner | `PERSONALITY.md` laws 1, 2 and 4; `UX_PERSONA_REVIEW_2026_09.md:109`, `:603`; `MODERN_SHAPE.md:168-169` |
| D3 | Sentence case "where appropriate" (§7.5). | owner | the all-caps law, `PersonalityTest.kt:747-781` |
| D4 | The pad editor's insides may be redesigned. | owner | `MODERN_SHAPE.md:170`; `SCREEN_PLAN_2026_10.md:31-32` |
| D5 | Names: Library, Sample (Record, Chop), Project (Pads, Beat, Export), Create, Settings. Snips become Samples. | owner | the place and stage labels in `Navigation.kt:139-160` |
| D6 | Obvious loading: a bar or spinner that draws attention. | owner | none |
| D7 | Labels ellipsize and never wrap is reversed. Button and section labels may wrap to two lines; only user-given names ellipsize. | call | `UI_DESIGN.md:194-208` |
| D8 | The workshop and Teach the machine come out of the app. The owner has never used them. | owner | `SCREEN_PLAN_2026_10.md:413`; `docs/WORKSHOP.md` |
| D9 | The hidden pencil-rewind hold goes, because ◄◄ already rewinds. | call | `UX_JOURNEY_PLAN_2026_09.md:699-710` |

Each PR that implements a decision adds a one-line dated status note at
the top of every doc it supersedes. It does not rewrite or delete those
docs.

## 7. Design

### 7.1 Look

- Modern only. Settings ▸ **Appearance** offers **Automatic** (the
  default), **Dark** and **Light**. Automatic follows the system dark-mode
  setting. Dark is VENT and Light is OPENSKY (**call**: show the plain
  words, and keep VENT and OPENSKY as internal ids).
- The title bar shows the wordmark **SnipSnap**, with no `.EXE`. The boot
  text and the boot timer (`Places.kt:72`, `BootText.kt`) are removed. The
  app opens straight onto Library, showing a loading state (§7.6) until
  the shelf list returns.
- The italic serif for names the user gave (kits, pads, projects) stays.
  It is a clarity rule, not decoration.
- Cassette visuals stay only where they show something real. Reels showing
  tape position stay. The DUB card and SIDE A/B copy on Export go.

### 7.2 Vocabulary

**The rule.** A name says what the thing does in words a producer already
knows. A brand-ish feature name (for example Aquarium) is allowed only if
the control that opens it carries a one-line plain description. Each name
means exactly one thing in the app.

**Approved (owner, D5):**

| Today | New |
|---|---|
| SHELF | Library |
| TAPE ▸ DECK, CUT | Sample ▸ Record, Chop |
| PROJECT ▸ PADS, BEAT, OUT | Project ▸ Pads, Beat, Export |
| MAKE | Create |
| SETUP, TAPE PROPERTIES | Settings |
| SNIPS | Samples |

**Proposed (call; the vocabulary PR puts the full ledger in front of the
owner):**

| Today | Proposed | Why |
|---|---|---|
| PAD SHEET | Pad editor | |
| LISTEN · MIC (dock) | Record, with the source shown: Mic or App audio | |
| WRITE KIT / WRITE SESSION | Export kit / Export project | |
| DUBBING…, WRITING — DO NOT EJECT | Loading… / Exporting… | |
| BIN, VERSIONS + BIN | Trash, Versions | |
| X-RAY | Inspect file | |
| DOUBLES | Find duplicates | |
| BREED | Combine kits | |
| REMIX BANK B | Make bank B variations | |
| OUTSIDE (REAMP, ROOM) | Re-amp and room | |
| SNAP (Create) | Image to sound | |
| SURFACE | Touch surface | |
| GROOVE, GRV, SONG, ARRANGE | Beat, Song | One name per screen |
| CUT, CHOP | Chop | |
| OUT, EXPORT | Export | |
| PADS, KIT (back labels) | Pads | |

**Collisions to resolve**, each name keeping one meaning
(`sh/Mutate.kt:41`; `ChopScreen.kt:1602`; `OrbitScreen.kt:1389`;
`SnapScreen.kt:1439`):

- STACK, SPLICE, SPLIT and ROOM are each both a pad-editor tool and a
  MUTATE mode.
- SNAP is a Create tool and a Chop grid row.
- SPREAD is a pad-editor tool and an ORBIT euclid chip.
- TAPE is a place and a treatment chip.
- CUT is a stage and a shape control.
- ORBIT is a Beat screen and a SNAP voice.

The fix renames the secondary use. For example, MUTATE's modes become
"Layer", "Join", "Separate" and "Room tone"; the builder proposes the
names and the owner reviews them in the ledger.

**Ledger and law.** A new `docs/VOCABULARY.md` lists every on-screen name
with what it means. A `ConventionTest` law checks that the place, stage
and bench labels in `Navigation.kt`/`Places.kt`, and every back label,
appear in the ledger.

### 7.3 Navigation grammar: three containers

| Container | Use it for | Rules |
|---|---|---|
| **Screen** | A full page you go into and come back from: pad editor, Samples, Song, Grain, Splice, Settings, Help and so on | Opened only with `Navigation.open(Bench.X)`. Its back label comes only from `Navigation.backChip`. `▸` on a control means "opens a screen", and nothing else. At most two screens stack above a stage. |
| **Sheet** | Picking one thing, a short menu, or a row's actions: kit switcher, starters, tools, record source, engine list, row `⋮` menus | One bottom-sheet component, replacing `ChoiceSheet` (`Frame.kt:546,563`) and its copies in `StarterMenu`/`ToolsMenu` (`KitsScreen.kt:704-719`). Each row is a name plus a one-line description. Swipe down, tap the scrim or press Back to dismiss. `▾` means "opens a sheet". |
| **Section** | Grouping on a page: Settings groups, pad-editor groups, This kit, Help | It folds open. **Its closed line says what is inside or its current value, never "UNTOUCHED".** Example: "Effects · Wide 70%, Crush", or with nothing applied, "Effects · width, saturation, character". Exclusive or independent opening is the builder's call per screen, but it is the same within one screen. |

`MessageBox` stays the only blocking dialog, and only for destructive
confirmations (`SCREEN_PLAN_2026_10.md:88-93`).

**Removed patterns:**

- Inline panels behind `▸` (KEY, SCULPT, STRETCH, AQUARIUM, ORBIT's
  swap-in panels) become sections or screens.
- Tap-to-cycle through more than three options (the 63-engine name cycler
  at `SynthScreen.kt:2233`, sort and show chips) becomes a sheet.
- Centred pop-up menus become sheets.
- The 14 untracked overlays become screens (§7.3.1).

**The gear (call).** Today the gear opens a pop-up of SETUP, HELP and
MIDI. Instead, the title bar carries two labelled icon buttons: **Help**
(?) and **Settings** (gear). Each opens its screen directly. MIDI becomes
the "MIDI devices" row inside Settings. That removes one container, and
Help becomes one tap from anywhere.

#### 7.3.1 Routing every sub-screen

Today 14 `Bench` entries are never opened (`Navigation.kt:168-195`). They
run as loose state in `App.kt:3272-3301` and `:3417-3588`: `padSheetSlot`,
`grainFieldSlot`, `spliceSlot`, `stackSlot`, `takesBinOpen`,
`padCaptureSlot`, `arrangeOpen`, `binOpen`, `doublesOpen` and `xray`, plus
SnapScreen's `showLive`, `drawing` and `showField`, and the pad editor's
`spreadSource`. They are wiped on every move (`App.kt:2759-2803`).

**Target:**

- Each is opened with `nav.open(Bench.X)` and drawn from `location.top`.
- Each screen's argument (which pad, which file) is held in the place's
  state record (`PadsState` and its siblings), not in a local `remember`.
- Every hand-written back label is deleted. Today they read `◄ KIT` at
  `PadSheetScreen.kt:3746` and `GrainFieldScreen.kt:488`, `◄ GRV` at
  `OrbitScreen.kt:2128`, and `◄ GROOVE` at `ArrangeScreen.kt:202`.
- Grain, Splice, Stack and Audition stop clearing the pad editor on entry
  (`App.kt:3504-3529`), so Back from them returns to the pad editor.
- Shelf pick modes (snip to kit, Combine kits, `+ FROM SHELF`) show a
  visible banner, "Pick a kit · Cancel" (they have no cancel today).

**Semantics (call):**

- **Going home.** The place row stays a way home. Tapping a place lands
  on its stage with its screens closed, as `NavigationTest.kt:115` pins
  today. What survives is the stage's state record, so you land with the
  same pad and bank selected.
- **Screens in the stack keep their state.** A screen that is still in the
  back stack under another screen keeps its state. The pad editor under
  Grain, Splice or Stack keeps its open section and any unapplied edits,
  through a `SaveableStateHolder` keyed by bench, with the state that
  matters moved to `rememberSaveable`. A running preview may stop.
- **Screens opened away from their home.** Some screens open on top of the
  current place rather than jumping to theirs: Synth from "Make from this
  pad", and Samples from "From samples". Their back label is derived from
  what is below them: the `backName` of the screen below, else the stage,
  else the place (`Navigation.backChip`). The static `Bench.backChip` is
  gone; every back label is derived this way.
- **Audition.** It stays a stage switch to Beat. Back returns through
  history to the pad editor, which history already records.
- **The two-screen limit.** Help and Settings don't count toward it.

**Laws:**

- Every `Bench` entry has an opener outside `Navigation.kt`.
- No `*Screen.kt` draws its own back label.
- `UatSimTest` journeys show Back from Grain landing on the pad editor.

### 7.4 Every hold gets a visible equivalent

| Hold or gesture today | Visible control |
|---|---|
| Hold a filled pad → pad editor (`KitScreen.kt:1355,1554`) | Tapping a pad selects and plays it; the selected-pad strip's button reads **Edit** (today `OPEN ▸`). The hold stays as a shortcut. |
| Hold an empty pad → capture (`KitScreen.kt:1431`) | Selecting an empty pad shows **Record into pad** and **From samples** in the strip (this partly exists, `sh/PadStrip.kt:36`). |
| Hold a kit row → rename/delete (`KitsScreen.kt:1034`) | A `⋮` on each row opens a sheet: Rename, Delete. |
| Project rows: no rename or delete exists | A `⋮` sheet: Rename, Delete, Remove a kit (`ShelfModel.dropKit` exists and has no caller, `ShelfModel.kt:112`). **Call:** Delete moves the project to Trash, keeping the promise that every delete goes to the bin first (`Places.kt:185`); Trash lists and restores projects beside kits; the kits a project holds are untouched. Rename renames the `Projects/<name>/` folder and the name in `project.json` (`Project.kt:228`); a name already in use is refused with a message. Both are new `ProjectStore` operations (`Project.kt:108`), JVM-tested. |
| Hold a room / saved card / synth preset to forget it (`KitsScreen.kt:848`, `ExportScreen.kt:1317`, `SynthScreen.kt:5426`) | A `⋮` sheet with Forget or Delete. |
| SYNTH engine: tap the name to cycle 63 engines | An **Engine** button opens a sheet listing engines grouped by kind. The grouping is a sound question: ask the luthier for categories. **Call:** engine names stay; they are instrument identities with their own docs. Each gets a one-line description drafted from its `docs/<ENGINE>.md`, checked by the luthier and reviewed by the owner in the ledger. Character chips also keep their names; descriptions come from their KDoc. |
| Chop: hold a slice chip to restore the detector's call (`ChopScreen.kt:1654`) | The slice's sheet has **Reset to detected**. |
| ORBIT: hold a step, hold a ring chip, tap the readout (`OrbitScreen.kt:2732, 2922, 2151`) | Visible controls; layout is the builder's call. |
| Seven taps on the SETUP title → workshop | Removed with the workshop (D8; §7.4.1). |
| Hold the left reel → pencil rewind | Removed (D9). |

**Law:** every `onLongClick` / `onLongPress` / `combinedClickable(onLongClick=`
site names its visible equivalent. A `twin =` comment or wrapper is the
builder's mechanism; the house pattern is a source-scanning test in
`ConventionTest`. Scrim absorbers (`Frame.kt:532`) are exempt by name.

#### 7.4.1 The workshop and Teach the machine (owner, D8)

The owner has never used either, so both come out of the app's UI:

- The 7-tap knock on the Settings title (`sh/Workshop.kt:30`,
  `PropertiesScreen.kt:131`) and `workshopOpen` (`App.kt:773`).
- The NOTE chip in the title bar (`App.kt:3196`).
- CONFIRM ALL and the stars row on Chop (`ChopScreen.kt:944`).
- The BENCH box in the pad editor (`PadSheetScreen.kt:3436`).
- SEND TO BENCH and SEND HITS TO BENCH (`PropertiesScreen.kt:394`).
- The TEACH THE MACHINE setting and its chip-correction logging. Its
  only consumer is `BenchExport` (`TeachLog.kt`; `BenchExport.kt:109`).

**Unrelated, stays:** `ConfluenceWorkshop` is an engine feature, not this
workshop.

**Staged, for a later owner decision.** The JVM-side pipeline stays in
`:shell` for now, with its tests: `TeachLog`, `BenchExport`, `CutRatings`,
`LabelledHits`, `BenchNotes`, `Workshop`, `ChopReview`. Deleting the code
and its tests is a separate cleanup PR, because removing tests is the
owner's call under the repo's rules. Until then the code has no UI entry,
and a status line in `docs/WORKSHOP.md` says so.

### 7.5 Readability

**Type scale.** It is defined once in `TapeType`, which already has
composable getters (about 660 reads, no call-site edits needed):

| Style | Size |
|---|---|
| Body | 14 sp |
| Secondary | 13 sp |
| Button and label | 13 sp, caps, +0.5 tracking |
| Eyebrow / section title | 11 sp, caps |
| Screen title | 20 sp |
| Readouts | mono |

The minimum anywhere is 11 sp. Sub-floor sizes are fixed:
`OrbitScreen.kt:2336` at 7 sp, and `LoopGrid.kt` with raw sizes and a
Material3 `Text` (`:136, 143-488`).

**Case (owner, D3):**

- Sentence case for anything that is a sentence: hints, empty states,
  descriptions, errors, dock messages, Help, sheet row descriptions.
- Caps for place and stage tabs, buttons, section titles and pad ids.
- Copy constants in `Personality.kt` are split so the law can tell them
  apart, for example `Labels` and `Messages` objects. The mechanism is the
  builder's.
- `PersonalityTest.kt:747-781` is rewritten to "messages are sentence case
  and end in a full stop; labels are caps".
- The `uppercase()` calls (46 of them) are removed where they force
  messages into caps.

**Voice guide** (goes at the top of `Personality.kt` and in
`docs/VOCABULARY.md`):

- Say what happened, or what to do next.
- Buttons are verbs.
- Name things exactly as they are labelled on screen.
- Errors say what failed and what to try.
- No metaphors, jokes or period references.
- An ellipsis means work in progress, and nothing else.

**Contrast laws**, added to `ContrastTest` for both schemes:

- Text is at least 4.5:1 against its background.
- The boundary of an interactive control is at least 3:1 against what it
  sits on (WCAG 1.4.11). Today raised fill against chrome is 1.06:1 and
  hairlines are 1.24 to 1.41:1.
- No `amber` on chrome. There are 136 raw `.amber` reads against 7
  `amberOnChrome` sites; amber on OPENSKY chrome is 2.11:1. Chrome uses
  `accent`.
- A per-scheme destructive red is at least 4.5:1 on its fill. It replaces
  `BIN_RED_BORDER`, which is defined 9 times (`Frame.kt:482` and eight
  copies). DELETE → BIN is unreadable on OPENSKY today.

**Button tiers.** One **primary** per screen (filled with the accent or
ink), then **secondary** (outlined), then **text** buttons. Today almost
every button is the same bordered box.

**Selection.** The visible "SELECTED" sub-label on the active stage is
removed (`ChopScreen.kt:1828`, stage rows). Selection shows by fill and
by TalkBack state.

**Empty states.** One sentence and one primary button, sized to the
content. No more 60%-height empty LCD panel.

**Truncation (D7).** Button and section labels may wrap to two lines.
`TapeText`'s default `maxLines = 1` plus ellipsis (`Chrome.kt:117,124`)
stays only for user-given names. Fixed-height rows in `Layout`
(`Schemes.kt:467-564`) grow to fit.

**Pads screen header.** Today PADS stacks four rows above the grid: a kit
row, a bank row, and STRIKE OFF | LEFT | STAY | VOL OFF, an unlabelled
row whose meaning is not readable from the screen. The STRIKE row moves
into a labelled "Strike strip" section that starts closed, with each
toggle named.

### 7.6 Loading (owner, D6)

**Model.** Replace App's `busy: String?` (`App.kt:456`) with a `:shell`
type that can be tested on the JVM:

```kotlin
data class Busy(val label: String, val done: Int? = null, val total: Int? = null, val cancel: (() -> Unit)? = null)
```

A null `total` means indeterminate. The exact shape is the builder's
call. Local busy flags (pad editor, SNAP, Beat, SYNTH) report into the
same model, so the dock always knows.

**Display:**

1. **The dock.** A full-width accent progress bar, at least 4 dp, along
   the dock's top edge, with the label in plain words: "Loading Factory…",
   "Exporting · 12 of 17 files". It is determinate when `total` is known,
   and an animated sweep otherwise. **Cancel** appears when `cancel` is
   set. This is the attention-drawing element the owner asked for.
2. **The control that started it.** A small spinner, a working label
   ("Loading…"), and disabled until the job is done. The guard flag is set
   synchronously before the launch. The pad editor sets `busy = true`
   inside the launch today (`PadSheetScreen.kt:1012`), and Split concedes
   "a fast second tap beats it" (`SplitScreen.kt:469`).
3. **The destination.** For kits, the pad grid shows 16 pulsing
   placeholders until the kit lands. For Record and Chop on first open,
   the waveform box shows "Reading recording…" with the bar instead of a
   blank LCD (`TapeScreen.kt:359`, `ChopScreen.kt:184`). For the Library
   on cold start, a loading state instead of "NOTHING TAPED YET."
   (`KitsScreen.kt:410-436`).

**Timing.** Show after 200 ms. Once shown, stay at least 500 ms so it
never flickers.

**Honest progress.**

- The Export bar is driven by a timer today (`ExportScreen.kt:759`;
  `ExportWizard.kt:26`, elapsed ÷ 180 ms). It must report real files
  written.
- Determinate wherever the count is already known: export files, Chop
  All (`uris.size`), Breed, Dust All and bank B variations (16 pads),
  Make Instrument (it already shows "n/N", `SynthScreen.kt:4442`), Image
  to sound's kit (16 cells).
- Starter kits are indeterminate in the first loading PR. A follow-up may
  add an optional per-pad callback to `StarterKits.Starter.render` for
  starters whose builders loop over pads (`StarterKits.kt:62-66`).

**Cancel.** Offered only where the work is already interruptible
(`offMainInterruptible`) or loops over items and can stop between them.
File writes stay `NonCancellable`, as today (`SynthScreen.kt:1931`).

**What a cancel leaves (call):**

- Jobs that *add* something keep what is finished and say so ("Stopped
  after 3 of 8"). Chop All and Make Instrument are additive.
- Jobs that create a new kit discard the half-made kit folder: starter
  kits, Combine kits, Image to sound's kit.
- Jobs that *overwrite* existing pads get no Cancel: bank B variations and
  Dust All.
- Export gets no Cancel.

**Reduced motion (call: the scope of §5).** When the system animator
scale is 0, decorative and loading animation stops: the bar and
placeholders are static, and the label carries the count or "Working…".
Motion that shows real data stays, because it is information: playheads,
meters, and reels at tape position.

**Every silent operation gets an entry**, each found in the 2026-10-27
inventory:

- Open project and switch kit. These have no guard either: a double tap
  can create two projects (`App.kt:2939`, inferred).
- Key / In key.
- Opening Keys.
- Touch-surface pad load.
- Kit bank load into the engine (`PadHost.kt:99-113`).
- The Mutate family.
- Make instrument from the pad editor.
- Versions and Trash restore.

A wrong busy label is fixed too: MAKE PAD reads "DUBBING…" during any
other pad job (`PadSheetScreen.kt:3369`).

### 7.7 Pad editor (owner, D4)

**Process.** Mockups come first: two or three directions shown to the
owner, who picks one before any code. The constraints below hold for
all of them.

**Always visible at the top:**

- Pad name and class.
- Previous and next pad.
- Play.
- The waveform.
- Level, pan and tune.
- Mode: one-shot, choke group, soft hits.

**Below, grouped by what you want to do, as sections with honest closed
lines (§7.3):**

| Group | Holds | Today's parts |
|---|---|---|
| **Edit** | Trim, Splice, Stack | RE-TRIM, SPLICE, STACK |
| **Effects** | Width and rush, character chips in labelled groups, shape, re-amp and room | TREATMENT, SHAPE, OUTSIDE |
| **Variations** | The renamed MUTATE modes, roulette, drift | MUTATE |
| **Make from this pad** | Instrument, synth, grain | MAKE |
| **Audition in beat** | | AUDITION |

**Delete** sits last, in the readable destructive style.

The 37 character chips sit today in 7 unlabelled rows, some truncated
("CONTO…", "ENSEM…"). They get labelled groups and full names. **The
grouping (what each character does, sonically) is a question for the
luthier.** The designer does not judge sound.

**Depth.** At most one more screen below the pad editor. Grain, Splice and
Stack are screens, and Back returns to the pad editor (§7.3.1).

## 8. Sequence: small PRs, each green on its own

| # | PR | Proof |
|---|---|---|
| 1 | Retire classic (in flight) | JVM and compile gate; screenshots show only the LOOK buttons gone |
| 2 | Loading: `Busy` model, dock bar, control spinners, kit placeholders, cold-start and Record/Chop loading states, honest Export progress, guards set synchronously | JVM tests for `Busy` timing (200 ms / 500 ms); screen recording of a TIDAL starter load |
| 3 | Route the 14 sub-screens; back labels from the model; Back from Grain/Splice/Stack lands on the pad editor | `UatSimTest` journeys; new laws |
| 4 | Containers: sheet component, Help and Settings in the title bar, MIDI into Settings, Tools/starters/kit switcher/record source as sheets, honest section summaries | Screenshots; tap counts |
| 5 | Vocabulary and voice: ledger for owner review first, then `Personality.kt` rewritten screen by screen, the case law, boot text and `.EXE` removed | Ledger law; copy case law |
| 6 | Visible equivalents for holds: `⋮` sheets, project rename/delete, engine list, pick-mode Cancel; the workshop and Teach the machine removed from the UI (§7.4.1) | Long-press law |
| 7 | Readability: type scale, button tiers, contrast laws, 48 dp targets, empty states, Pads header, Automatic appearance | Contrast laws; before/after screenshots in both schemes |
| 8 | Pad editor: mockups, owner pick, build | Owner review of mockups; screenshots; `UatSimTest` depth |

**Why this order.**

- PR 2 depends on nothing else and fixes what the owner noticed first.
- PR 3 is plumbing with no visual change, and PRs 4, 6 and 8 build on it.
- PR 5 comes before 7 so truncation is measured once, on the final
  wording.
- PR 8 comes last because it uses the screens, sheets, sections, names
  and type that the earlier PRs establish.

Each PR merges the default branch in before it is pushed, and touches
`App.kt` in the smallest slice it can.

## 9. How we know it worked

- **Taps to reach**, counted by `UatSimTest` before and after for: pad
  editor, an effect on a pad, Grain, the engine list, export kit, Help,
  Settings ▸ Appearance, project rename, Samples, Song.
- **Laws:** back labels come from the model; every bench has an opener;
  every hold has an equivalent; the vocabulary ledger matches; message
  case; contrast floors; 48 dp targets (`Schemes.kt:542` already holds
  the floor).
- **Screens:** the before/after screenshot set per visual PR, in both
  schemes, plus one TalkBack pass and one reduced-motion pass before PR 8.

## 10. Left to the builder

- Component APIs, file placement and the exact shape of `Busy`.
- Animation durations within 150 to 300 ms.
- Exclusive or independent sections per screen.
- The detection mechanism for each law.
- Proposed renames for the collision list and the remaining jargon, to
  be reviewed by the owner in the ledger.
- The ORBIT layout for its now-visible controls.
- The order of commits within a PR.

## 11. Open questions for the owner

None open. The builder check (2026-10-27) raised seven questions:

- One went to the owner: the workshop. The answer is D8.
- Six were settled as designer calls, marked **call** where they appear:
  - **Navigation semantics** (§7.3.1). The place row stays a way home. A
    screen in the stack keeps its state. Screens opened away from their
    home take their back label from what is below them.
  - **Project delete and rename** (§7.4). Delete goes to Trash; rename
    renames the folder and is refused on a name clash.
  - **What a cancel leaves** (§7.6). Additive jobs keep finished work, a
    half-made new kit is discarded, and overwriting jobs and Export have
    no Cancel.
  - **Reduced-motion scope** (§7.6). Decorative and loading motion stops;
    motion that shows real data stays.
  - **Pad editor state under Grain, Splice and Stack** (§7.3.1). It is kept
    with `SaveableStateHolder`.
  - **Engine and character names** (§7.4). They stay, with descriptions
    drafted from each engine's doc or KDoc, checked by the luthier and
    reviewed by the owner.

A staged follow-up for the owner: §7.4.1.
