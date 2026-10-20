# UX review of the four-place shape as built — 2026-10-17

*Scope: the shape (PRs #530–#539) and the modern look (#545, branch
`claude/modern-look`), walked on the `snipsnap_pixel` emulator under OPENSKY
with a project open and cold, every place, stage, sheet and the fullscreen
Popup. Seven lenses (direction, interaction, visual, inclusivity, systems,
feasibility, polish), deduplicated and grouped by surface. File:line refs
are against `claude/modern-look` at `9badcdec`.*

*Companion to `SCREEN_PLAN_2026_10.md` (the plan this is measured against)
and `MODERN_SHAPE.md` (the parked findings it reopens).*

## 0. The short version

The skeleton is right and every reviewer said so: four places, one stage
row, one dock, capture always one tap away, every control named, no timed
toasts left. What is wrong is **thin at the seams again**, one level down:
the frame is honest but the pages inside it still speak with the tab era's
words, the dock's one line has no lifecycle, and three empty states have no
way in. Six of seven reviewers independently flagged the same two things
first (the stale dock line, the cold PROJECT page); five flagged the
fullscreen grid.

Twenty-two tweaks follow, ranked, in four batches. Batches 1–3 are small
to medium and need no product decision. Three items need Josh's ruling
(§5).

## 1. Systemic (3+ reviewers)

### S1. The dock line is a log, not a status — never cleared
*Drift, Sheen, Marina, Lattice, Haven, Coral.*

`DockModel.clear()` has no caller. `goToPlace` / `goToStage` /
`goToScreen` / `takeDockDoor` (`App.kt:2743-2765`) never touch the dock;
`clearTransientState()` abandons fifteen hand-off vars but not the line.
Every write is anonymous (`onToast → dock.say`, 132 sites), so a line
carries no place, kind or expiry.

Seen: `NO HITS IN THIS SOURCE. OPEN CUT AND TRY GRID…` fired from a
`LaunchedEffect` on **entering** CUT (`ChopScreen.kt:201`) and then shown
on PADS, BEAT, OUT, MAKE — and on CUT itself, where it says "open CUT".
`SHARED STREAM. A LITTLE MORE LATENCY.` (`SurfaceScreen.kt:409`,
`LiveSnapScreen.kt:144`, also on entry) shown on HELP and SETUP. The line
is the `liveRegion`, so a stale sentence is read aloud as a live fault.

A second bug under the same roof: **SEND TO PADS' success line never
shows.** `ChopScreen.kt:~1172` says `Copy.sentToPads(n)` and the next
statement, `onSentToGrid` (`App.kt:3570-3586`), calls `offer(KIT_OFFER…)`,
which replaces the whole `Now`. The user sees only "THE PADS ARE LOADED.
TAKE THE KIT OUT." Same for `landedOnto` / `foldedOnto`.

**Tweak (M):** scope the line. `DockModel.say(text, at: Location)` stamps
where it was said; `onLeave(from, to)` drops a line said elsewhere and
keeps the door (a door's job is to survive the move). `Places.restingLine
(place, stage)` is the per-place default instead of one global
`DOCK_READY`. Call `dock.onLeave` beside `clearTransientState()`. Make the
CUT and SNAP entry lines in-page state, not dock events. Fold the sent
count into the offer text (`offer("${sentToPads(n)} ${KIT_OFFER}", …)`).
Laws: `DockModelTest` ("an offer stays through plain lines" must hold),
`ConventionTest:1335-1372` (still routes through `dock.say`/`offer`), the
`Copy` reflective law for new sentences. Risk: an async write landing
after the user left — stamp the location at write time.

### S2. Cold PROJECT contradicts the story
*All seven.*

`App.kt:3392` draws `KitScreen(entry = null)` whenever the stage is PADS
and no bench is open — it never checks for a project. The page then shows
two empties: the switcher's `NO KITS YET` + `+ FROM SHELF`, and a 55 %-
height LCD saying `NO TAPE IN THE DECK. OPEN ONE FROM SHELF.`
(`KitScreen.kt:189-195`, `Copy.NO_TAPE_IN_DECK` at `Personality.kt:2604`,
whose KDoc still says "KIT/PLAY with no tape ever committed" — tab-era
copy) with a second `SHELF ▸` door. `+ FROM SHELF` is a silent no-op with
no project (`App.kt:3397-3403` guards on `p != null`). `SWITCH KIT` opens
a sheet with zero rows and CANCEL.

**Tweak (S):** one panel, project vocabulary, one door. No project: "NO
PROJECT OPEN. OPEN ONE ON THE SHELF, OR START ONE." → `SHELF ▸`. Project
with no kit: "THIS PROJECT HAS NO KIT YET. ADD ONE FROM THE SHELF, OR
RECORD IN TAPE." → `+ FROM SHELF` (pick mode) and `TAPE ▸`. Hide the
switcher row and `SWITCH KIT` in both. Reserve `NO_TAPE_IN_DECK` for
DECK. New sentences go in `Copy` (caps, full stop); leave
`Places.switcher()`/`NO_KITS` alone (`PlacesTest:62-65` pins them).
BEAT and OUT have the same hole (`READ_GROOVE_NEEDS_KIT`,
`NO_KIT_FOR_EXPORT` "EXPORT DUBS A KIT FOR THE MPC…", `NO_KIT_FOR_ORBIT`
"OPEN ONE FROM SHELF FIRST" — all tab-era) — a shared
`PlaceEmpty(place, stage)` reading from a `Places.EMPTIES` table would
close all four and give `PlacesTest` a table to pin (M).

### S3. The fullscreen grid is a second, classic-only pad
*Haven, Coral, Sheen, Marina, Lattice.*

`FullscreenPlayGrid` (`PlayScreen.kt:46`) → `BankRow` (`PadGrid.kt:133`) →
`PlayPad` (`PadGrid.kt:255`), which has **no `Look.MODERN` branch**:
`darken(gray, .30)` fill, 2 dp class rim, `classTint` (55 % to white)
name. On paper that is pastel on grey — Perc 04/05/06 are invisible
(`44-fullscreen.png`). The Popup is not the cause; `LocalScheme`
propagates. `PlayPad` is also GROOVE's record grid and TAPE's CATCH, so
all three carry classic rims under the modern look.

Layout: `BANK_A_ROWS`/`BANK_B_ROWS` make each bank 8 × 2; `BankRow` puts
the banks side by side (16 × 2) and needs `maxWidth ≥ 888 dp`
(`bankFloor` 440 × 2 + gap). A landscape phone gives ~780–860 dp after
insets, so it falls into the `horizontalScroll` fallback with fixed-width
banks: B08/B16 are off-screen with no scroll hint, and pads are ~48 × 125
dp slivers. `EXIT FULL`, the only way out, sits in a
`.height(STATUS_BAR_H)` (26 dp) row that clamps its 48 dp child
(`PlayScreen.kt:66` vs `:81`).

**Tweak (M):** lift the modern branch from `PadCell`
(`KitScreen.kt:1349-1362`: raised fill, hairline, 4 dp class bottom edge,
`classInkOn` name) into one `padSkin(scheme, cls, shape, lit)` helper that
`PlayPad` and `PadCell` both call — extract only the skin, not the gesture
block (`ConventionTest:2575-2640` and `:2836-2860` read the literal
`onTap(slot, PadHit.velocityAt(…))` and the `CENTER` in each
`onClick(label = "PLAY")`). Lay the banks out as **two 4 × 4 blocks side by
side** (bankFloor drops to 216 dp; both fit in ~440 dp; the scroll fallback
goes away; MPC bottom-up numbering is already what `windowRows(bank)`
gives, so 2 × 4 × 4 beats 4 × 8). Header `heightIn(min = 48)`. Sweep GROOVE
and CATCH in OPENSKY and VENT after.

### S4. Three empty states have no way in
*Drift, Haven, Coral, Sheen, Marina, Lattice.*

- **TAPE DECK, no tape** (`39-tape.png`): a 640 dp black LCD with a 1 px
  needle, POS 0:00.00 / LEN --.--, a full transport and IN/OUT/ZOOM, all
  live-looking. `EmptyDeck` (`TapeScreen.kt:~380`) only appears on
  `failed`, and routes to `SHELF ▸`. Nothing points at `LISTEN · MIC`.
  **Tweak (S):** no tape → replace transport + edit groups with one panel
  "NO TAPE YET. TAP LISTEN · MIC BELOW, OR OPEN A SNIP." with a door to
  SNIPS; keep the readouts dimmed or drop them.
- **BEAT, no pattern** (`48-beat.png`): a 560 dp dark LCD, one sentence
  at 45 % height, three equal buttons pinned to the bottom. The dock's
  `PLAY THE BEAT` chip plays nothing and says nothing (`App.kt:3860`,
  `GrooveScreen.kt:1976`). Plan §3.4 asked for `ORBIT ▸ · SONG ▸ · LOOP ▸`
  in one row; SONG/LOOP are below the fold (`GrooveScreen.kt:2450-2457`).
  **Tweak (S):** top-align the sentence ("NO BEAT YET. TAP RECORD AND PLAY
  PADS."), make `● RECORD` the filled primary, START YOURS / ORBIT ▸ / SONG
  ▸ / LOOP ▸ one secondary row, BARS stepper under them; hide or disable
  the dock play chip until a pattern exists, with a line when tapped.
- **CUT, 0 slices** (`40-cut.png`): `SEND TO PADS` is the dark primary
  (`ChopScreen.kt:1119-1122` gates only on busy), `LANDS AS A01_PERC_01
  CHOP` and `AUTO-PLACE: FILL IN ORDER` stay, sixteen grey slabs. The only
  response is the dock line from S1. **Tweak (S):** `&& sliceCount > 0`;
  hide LANDS AS / AUTO-PLACE at zero; grid → one line "NO HITS FOUND. TRY
  GRID ▸". `PrimaryAction` (`KitsScreen.kt:1166`) disabled = text alpha
  0.5 only, rim and fill stay — give it `ActionButton`'s disabled look
  (`PadSheetScreen.kt:4057`), rim only when enabled.

### S5. HELP promises what the engine doesn't do
*Sheen, Marina, Haven, Forge.*

`Places.HELP["THE DOCK"]`: "THE BEAT PLAYS FROM HERE AND KEEPS PLAYING
WHEREVER YOU GO." The clock is `GrooveScreen`'s `withFrameNanos` loop
(`:1130-1165`), torn down with the screen (`:1008-1018`); `onPlayToggle` is
passed only on BEAT. Plan §1/§4's headline benefit ("treat a pad while the
beat plays") is not delivered. **Tweak now (S):** prose — "THE BEAT PLAYS
WHILE BEAT IS OPEN. THE CHIP STOPS IT." (PersonalityTest holds lines to 80
chars). **Later (L, 2–4 days, high risk):** a `BeatPlayer` owned by App —
two `PadEngine`s (PADS has its own at `KitScreen.kt:~228`) must not run at
once; live-record touch stamps and bounce ride the same state. Do it
last.

### S6. The PADS header says the kit's name three times
*Coral, Sheen, Drift, Haven, Lattice, Marina.*

Title bar `FMAJ7 2`, switcher `FMAJ7 2 ▾ · 1 KIT IN THIS PROJECT`, LCD
header `FMAJ7 2`. The "double separator" is `▾` (U+25BE,
`Places.kt:72`) rendering as a dot in both faces. The switcher wraps to
two lines at 360 dp and `maxLines = 2` at ~22 chars/line truncates the
half the code comment says users need (`KitScreen.kt:972-983`). With one
kit, `SWITCH KIT` opens a sheet whose only row is the current kit
(`App.kt:3872-3882`) and "· ON THE GRID" collides with quantize language.
`⟳ FULL` draws as a circle (no face has the glyph) in a 40 dp header that
clamps its 48 dp chip (`KitScreen.kt:474` vs `:490`).

**Tweak (S+S):** (a) merge the switcher into the LCD header: `FMAJ7 2 ▸ ·
6 PADS` with `FULL` as a plain word and `+ FROM SHELF` trailing; the
project count goes in the accessible name; frees 48 + 8 dp. (b) `<2`
kits: the chip goes straight to ADD FROM SHELF; the sheet gains a `+ FROM
SHELF ▸` row; marker "· ACTIVE". Header `heightIn(min = 48)`. `▾` → `▸` or
a drawn chevron.

## 2. By surface (the rest, Important)

### Frame
- **F1. Hit-target clamps.** Dock transport chip ~20 dp wide
  (`DockChip` has `heightIn(48)` but no width floor, `Frame.kt:450-453`);
  `LISTEN · MIC` row likewise (`:356`). `PlaceRow` and `Dock` are fixed
  `.height(…)` (`:226`, `:335`), so a 150–200 % font scale clips the
  two-line status. **Tweak (S):** `widthIn(min = MIN_HIT_TARGET)` on
  `DockChip`; `heightIn(min = …)` on both rows.
- **F2. The ■ chip contradicts its name.** On BEAT, stopped, the chip
  shows `■` (`Places.STOPPED`) with accessible name PLAY THE BEAT and an
  accent border (`Frame.kt:~368`). Off BEAT it is an inert `■` beside the
  capture light that reads as STOP. **Tweak (S):** `▶` when stopped and
  live; drop the readout off BEAT.
- **F3. Doors cover one hop.** The only `offer`s are CUT → OUT
  (`App.kt:3574, 3584`); `Places.DOOR_CUT/DOOR_PADS/DOOR_BEAT` are declared
  and unused; KIT_OFFER skips BEAT; the standing `EXPORT ▸` persists on
  SHELF, MAKE and on OUT itself (a no-op). All navigation is in the top
  fifth of the phone; the dock is the only bottom-reach control.
  **Tweak (S):** SNIP success offers `CUT ▸`; SEND TO PADS offers `BEAT ▸`
  (OUT stays one stage-tap away); hide a door whose target is the current
  location. **Cheap reach win:** a resting door per stage (PADS → `BEAT
  ▸`, BEAT → `OUT ▸`) so the next step is under the thumb.
- **F4. Silent kit switch.** A chop send `landKit`s (`App.kt:752`) and
  activates the new kit under the user; `activateKit` (`:2809`) says
  nothing. **Tweak (S):** `dock.say("ON THE GRID: <KIT>")`.
- **F5. Failures clipped.** `DUB_FAILED` / `"DUST FAILED: ${e.message}"`
  (`App.kt:1559`) / `dubWouldOverwrite` go to the two-line dock
  (`Frame.kt:~399`) and are replaced by the next `say`. The mic-denied
  `BlockedDialog` (`App.kt:3928`) says "TURN IT ON IN YOUR PHONE'S
  SETTINGS" with no button. `PROJECT_OPEN_FAILED` (`:772`) is actually
  `landKit` bookkeeping. **Tweak (S):** action-needed failures through
  `openNote`/`MessageBox` as `EXPORT_BLOCKED` already does; an
  `ACTION_APPLICATION_DETAILS_SETTINGS` button; rename to "COULDN'T ADD
  THAT KIT TO THE PROJECT."

### SHELF
- **SH1. The primary action is the wrong unit** (Marina, Coral). Plan
  §3.1 made the project the unit; the one filled button is `NEW KIT ▸
  STARTERS` and `NEW PROJECT ▸` is a plain row inside the scroll
  (`KitsScreen.kt:464`). Every project is named after its one kit (the
  migration made them 1:1), so PROJECTS and KITS read as the same list
  twice and the distinction is invisible. **Tweak (S, needs ruling §5b):**
  `NEW PROJECT ▸` filled and pinned when projects exist; STARTERS primary
  only at zero projects; SNIPS + TOOLS → one two-up row (saves 56 dp);
  project subtitles "KIT: FMAJ7 2" / kit rows "IN FMAJ7 2" (`ShelfModel`
  already answers held-by). `FIRST_RUN_PLACES_NOTE`'s "KIT IT" → "PLAY
  IT" (it names no door; add "LISTEN · MIC BELOW").

### PADS
- **P1. First impression is a row of empties.** MPC order (A13–A16 on
  top) is correct and stays. The waveform is `cls.copy(alpha = 0.30)` on
  cream (`KitScreen.kt:~1449`) — lime/mint/cyan vanish. **Tweak (S):**
  alpha 0.55 in the `classInkOn` colour on paper; the strip line names
  the selected pad (plan §3.3's standing line); fold `TAP A PAD · IT
  PLAYS…` (a screen-local const outside the copy laws, in `ink3` at
  3.85:1) into the LCD header's sub-line or `ink2`.
- **P2. THIS KIT is clipped under the dock** with no scroll cue on first
  load. **Tweak (S):** bottom padding = `DOCK_H`.
- **P3. The empty-pad long-press** opens capture (`KitScreen.kt:1268`) —
  well modelled for TalkBack, invisible otherwise. Say so in the strip's
  empty line: "HOLD AN EMPTY PAD TO CATCH A SOUND INTO IT."

### OUT
- **O1. Preflight twice**, under WRITE KIT and WRITE SESSION; the
  "READY TO DUB" bar is drawn solid before anything is written (reads as
  100 %); `CARD · NONE — SAVING TO THIS PHONE` is a warn-weight row;
  preflight rows are mixed case in an all-caps UI. Plan §3.5's ALSO list
  (SHARE KIT, BOUNCE, MIDI, CHART) became one `BEAT WRITERS ▸` door to
  BEAT. **Tweak (S):** one preflight above both cards; THE PROJECT as a
  one-line status; empty sunken track until busy; "SAVES TO THIS PHONE.
  NO CARD." in `ink2`; caps.

### MAKE
- **M1. Four bare rows, 60 % empty** (`MakeScreen.kt:46-59`; the KDoc
  says the plan's "last: RESIN · KICK, to A09" subtitle is deliberately
  not drawn). SNAP and SURFACE mean nothing before tapping; the words are
  defined only in HELP, behind the gear. **Tweak (S):** a header line
  ("SOUND FROM NOTHING. EACH ONE LANDS ON THE ACTIVE KIT.") and a 12 sp
  `ink2` sub-line per 72 dp row (SYNTH: tune an engine · SNAP: a picture
  into a sound · SURFACE: play a touch surface · INSTRUMENTS: what you
  made, on 16 pads), reusing `Places.HELP`'s MAKE lines; a no-kit
  one-liner.

### Paper look (OPENSKY)
- **L1. Chrome inks on the LCD.** `ink2`/`ink3` are chrome inks; inside
  `lcdPanel` on paper they are 2.84:1 / 3.79:1 — CUT's header "FROM
  A01_PERC_01" is invisible (`ChopScreen.kt:~799`). **Tweak (S):**
  secondary LCD text = `lcdInk` at 0.75 or `amber`; grep `ink2|ink3`
  inside `lcdPanel` blocks.
- **L2. Scanlines over raised chips** inside LCDs (`Bevel.kt:179-200`
  draws the stripe over content): FULL, RECORD, START YOURS, ORBIT are
  striped. **Tweak (S):** modern stripe `drawBehind`, or alpha ≤ 0.10.
- **L3. `ink3` carries instructions** ("PROJECTS · OPEN ONE, OR START
  ONE", TRANSPORT/EDIT captions, BANK B · EMPTY) at ~3.8:1 on cream.
  **Tweak (S):** `ink2` for instructions, `ink3` for real disabled only.

### HELP / SETUP
- **H1.** HELP is right per place but an 11 sp mono wall in an LCD; SETUP
  buries LOOK (the biggest visible change) below TEACH / RATE / FILES /
  CARD. **Tweak (M):** sections as collapsible `GroupBox`es (THIS KIT's
  pattern), opened at the place you came from; LOOK first on SETUP; a
  version / what's-new line (the T5 Patron has no surface at all).

## 3. Minor
- Glyphs are from three vocabularies at three sizes: `◄ ►` (HeaderChip,
  pad sheet prev/next), `◀ ▶` (CUT steppers, BEAT bars), `▸` doors at 11
  sp (a 4 px triangle), `→` sends (`SnipsScreen.kt:479-485`). Pick `◄ ►`
  step / `▸` door / `→` send; a 16 dp right-aligned chevron for doors; add
  a ConventionTest regex for strays.
- `tapeClick` sets `contentDescription` only (`Chrome.kt:174`) — no
  `Role.Button`, and glyph-laden names ("◄ A06", "SNIPS ▸ EVERY CATCH, ONE
  LIST") read as noise. Add the role; spoken names without glyphs,
  separate from the label (no law touched).
- Capture light is an 8 dp dot with no `stateDescription`; add "MIC
  ARMED".
- TitleBar name `widthIn(max = 140)` ellipsises with no full-name
  semantics; the wordmark Row has no weight and can ellipsise first
  (`Chrome.kt:242-251`).
- SHELF rows all read `OPEN ▸`; nothing marks the open project; header
  could carry the count ("PROJECTS · 16").
- `EMPTY_KIT` = "16 EMPTY PADS." (`Personality.kt:82`) gives no way in.
- HELP/SETUP's back chip is `◄ BACK`; name the place.
- Capture sheet's first row repeats the chip's own label; "MIC ▸ RECORD
  THIS ROOM".
- Three row gaps (4/6/8 dp, `Frame.kt:226/253`, `App.kt:2968`) and
  hardcoded radii (3/4 dp) → `Layout.GAP_S/M`, `Layout.CHIP_RADIUS`.
- Residue drawn by nothing: `Copy.HELP_MORE`, `HELP_MORE_HEADER`,
  `PAD_SHEET_LEGEND`, `PAD_SHEET_HINT`, `NO_KIT_STATUS` (kept alive by
  `PersonalityTest.kt:163,181,505`, `ConventionTest.kt:1318` — retarget
  those at `Places.HELP`); check `SURFACE_PRINTING_NO_TEMPO`,
  `XRAY_NOTHING_TO_SHOW`, `SHELF_FILTER_ALL`; `Chrome.kt:95` "Never a
  tab"; `FullscreenPlayGrid` is the last user of `STATUS_BAR_H`.

## 4. Corrections to the reviewers
- Haven's "class names fail contrast on the strip" is wrong for the
  strip: `9badcdec`'s `classInkOn` is in the walk APK and the names are
  the darkened class ink (pixel-checked, `crop-strip.png`). It is right
  for the fullscreen grid, which is S3.
- "B08/B16 are clipped" — they are off-screen in a `horizontalScroll`
  with no hint, not clipped; same fix.
- The double separator is not a stray `·`; it is `▾` with no glyph.

## 5. Rulings Josh owes
a. **Stage row: keep the SELECTED sub-label?** It is the decided state
   convention (border + sub-label) and the colour-blind-safe cue, but it
   makes the row two lines (unselected chips top-align a single word and
   look clipped) and costs ~16 dp. Options: keep and vertically centre
   (S, no ruling); or modern-only: 4 dp accent bar at the chip's bottom —
   the pad's own device — with the word kept in the accessible name.
b. **SHELF's filled button: NEW PROJECT or NEW KIT ▸ STARTERS?** The plan
   says the project is the unit; the page says the kit is.
c. **BeatPlayer extraction now or later?** HELP is wrong either way until
   one or the other ships; the prose fix is one line.

## 6. Batches (each a PR, green on the laws before the next)
1. **Honest empties + copy (S×6):** S2, S4 (all three), S5 prose, O1, M1,
   F4. Mostly `Copy`/`Places` and call sites; `UatSimTest` prose where it
   names the old constants.
2. **The dock's lifecycle (M + S×3):** S1 (scoped line + resting lines +
   the overwritten send line), F2, F3, F5.
3. **The pad surfaces (M + S×4):** S3 (shared skin, 2 × 4 × 4, header),
   S6 (header merge, one-kit chip, glyph), P1, P2, F1.
4. **Paper polish + HELP (S×3 + M):** L1, L2, L3, H1, the Minor list.
5. **Later:** BeatPlayer (L), `PlaceEmpty` table (M) if batch 1's four
   hand-rolled empties want a home.

## 7. Parked findings, re-scored
| | status | why |
|---|---|---|
| P3.1 thirteen destinations | closed | four places, two stage rows, always visible; chrome costs 186 dp of the 211 budget |
| P5.1 project vs kit | half | model done; the page can't show it while names coincide (SH1) |
| P2.2 vocabulary | half | HELP defines; the places and MAKE don't (M1) |
| P1.4 HELP wall | half | one tap away, by place; still a wall (H1) |
| J10 timed toasts → doors | half | the door stands; the line is global (S1); one hop only (F3) |
| W#22 pad sheet door | closed | tap → strip names it → OPEN ▸ |

## 8. Commendations (what not to touch)
The frame itself; `DockModel.Now` as one value so a door can never
outlive its words; `NavState`/`DockState` write-through mirrors; the
Back order (bench → stage → place) and `anyOverlayOpen`; `PadsState`
surviving stage switches; every control named and `MIN_HIT_TARGET`
enforced at ~70 sites; the pad's merged semantics (PLAY + long-press OPEN
PAD SHEET + the empty-pad hint); honest failure paths in every async
catch; WRITE's progress bar and busy lock; the STARTERS sheet's copy; the
4 dp class edge and `classInkOn` on paper (the most legible grid in the
app); accent 5.16:1 on paper and the `amberOnChrome` split; SHELF's two
section headers, which say the story in plain words.
