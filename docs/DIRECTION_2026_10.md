# Direction, October 2026 — the project, the dock, and the second look

*Decisions taken 2026-10-13 on the two proposals
([`MODERN_LOOK.md`](MODERN_LOOK.md), [`MODERN_SHAPE.md`](MODERN_SHAPE.md)),
what they change, and the two things they add: a dark colourway for the
modern look, and a measured read of where the current design falls short on
accessibility. Nothing in `:app`, `:kit` or `:shell` changes with this
document.*

## 1. The decisions

| Question | Decision | What it changes below |
|---|---|---|
| Is the kit the unit of work? | **No. The project is.** A kit is one of a project's outputs. Kits live in a library shared by every project: make one in project A, open project B, it is there. | §2 |
| Who comes first? | **The novice and the Koala user**, with the MPC owner's benefits kept as the differentiator, not dropped. | §3 |
| Does the dock hold capture state? | **Yes.** The dock is the transport, the capture light and counter, what is playing, and the standing next step. | `MODERN_SHAPE.md` §3 stands as written |
| Shape first or look first? | **Open**, with a stronger steer: the current design reads as too gimmicky and may not be properly accessible. The modern look is the direction, not a second class beside the first. | §4, §5 |

## 2. The project is the unit

### What exists already

The repo already has the project, at export time. `SessionBuilder` turns
*N kit folders and M instruments* into one `.xpj` the MPC opens with
everything standing: kits on tracks, each kit's grooves as the project's
sequences, instruments beside them. Its own KDoc calls this "whole
sessions, not just kits". So the decision is not to invent a project; it is
to make the thing the exporter already assembles into the thing the user
opens.

On disk today a kit is a folder: WAVs, `kit.json`, `groove.json` (the
kit's clips and your own programs), `.bin` and takes. Snips live beside the
kits in `snips/`. Instruments and rooms have their own stores.

### The model

*As built 2026-10-14 (`kit/.../Project.kt`, `ProjectMigration.kt`). The
first draft of this block moved the kits under a `library/kits/` folder;
the built one leaves every kit where it is. The shelf root already **is**
the library, the CLI and the exporters already address kits there, and a
project is a new folder that points at old ones. Nothing moves.*

```
<shelf>/<kit>/                  a kit folder, exactly as today; the library
<shelf>/snips/, Instruments/, Rooms/, .bin/      as today
<shelf>/Projects/<name>/
  project.json                   name, kits held (by folder name), the active kit, tempo, key
  beat/groove.json               the project's patterns, in GrooveStore's own format
  tapes/, out/                   reserved for what the project captured and what it wrote
```

`KitStore.list` only sees a direct child holding a `kit.json`, so
`Projects/` is invisible to the shelf's kit scan by the same mechanism that
keeps `Rooms/` and `.bin/` off it. `ProjectStoreTest` pins that.

- **A kit is referenced, not copied.** Edit it in project B and project A
  sees the edit, which is what "the kit should be available" means. A
  project that wants its own copy duplicates it on purpose, the way
  VERSIONS already takes a take.
- **The beat moves to the project.** `groove.json` today holds "the kit's
  grooves" because the kit was the only container. In the project model
  a pattern belongs to the project and *plays* a kit, which is also how
  `SessionBuilder` already writes sequences. The export of a bare kit
  keeps embedding its clips, unchanged: a project's patterns that use that
  kit are what gets embedded. *As built: the project's beat is a
  `groove.json` under `beat/` in the groove store's own format, so the
  exporters need no new codec to read it. In this step the migration
  **copies** the kit's grooves there and the kit keeps its file, because
  every exporter still reads the kit-side file; it retires when they take
  a project as input. `ProjectMigration`'s KDoc carries the same note.*
- **Migration is one rule.** Every existing kit becomes a project of the
  same name holding that kit, with the kit's `groove.json` as the
  project's beat. Nothing is lost and nothing asks. *As built:
  `ProjectMigration.adopt(shelf)`, idempotent, run on every shelf open. A
  kit a person already placed in some project is left there; a kit whose
  name a project already uses for something else is adopted as
  `<name> 2`.*

### What it does to the shape

`MODERN_SHAPE.md`'s four places hold, with one renamed and one stage
re-read:

| Place | Was | Now |
|---|---|---|
| **SHELF** | kits, snips, instruments, rooms, bins, starters | the library, as listed, **plus the projects list at the top**. Opening a project opens the fourth place. |
| **TAPE** | the deck, then CUT | unchanged; a capture lands a snip in the library and a tape in the open project |
| **PROJECT** | was KIT | **PADS · BEAT · OUT**, where PADS is the grid of the project's *active* kit, with a one-line switcher above it (`KITCHEN ▾ · 2 KITS IN THIS PROJECT · + FROM SHELF`) |
| **MAKE** | SYNTH, SNAP, SURFACE, KEYS | unchanged; each lands on the active kit |

The vocabulary falls into place for both audiences without a second line:
PROJECT, KIT, PADS, BEAT, OUT are plain words to a novice, and to an MPC
owner PROJECT is the `.xpj`, KIT is the program, BEAT is the sequence list.
P2.2 ("their vocabulary doesn't navigate") closes with the model rather
than with subtitles.

### Cost

This is the one change in the three documents that touches `:kit` and
`:shell`, not just `:app`: a `ProjectStore` beside `KitStore`, the beat's
store keyed on the project, `KitShelf` learning the library/projects split,
and the migration. It is testable on the JVM in full, which is where this
repo proves things.

## 3. Audience: novice first, MPC owner kept

The persona review rated the app's depth (MUTATE, GRAIN, BREED, SPLIT) and
its cassette aesthetic as differentiators for the Koala user and "twelve
screens" and "no audio out" as the risks. Audio out closed in September;
twelve screens is what the shape closes. For the novice the review's own
line is the brief: *"THE MACHINE GUESSES; ARGUE WITH IT" — this is the tone
the rest of the app should aim at.*

What is kept for the MPC owner, and where it now shows:

- **The front door says so.** HELP's first line, or the empty SHELF, names
  the formats. Today that fact is the ninth word of HELP's fourth bullet.
- **OUT is where the MPC lives.** Program, expansion, `.xpn`, MPC 3 native,
  session. The eight format explanations open by default, as J34 decided.
- **Class colours, choke, velocity layers, root notes** are already
  MPC-correct and already invisible to a novice. They stay as they are.

## 4. The modern look is the direction

The user's own words: the current design is too gimmicky, the audition
pages are easier to read, and the old design may not be properly
accessible. Taken together with `MODERN_LOOK.md`, that changes one thing in
it: the modern look is not a second class beside the OG one, it is where
the app is going. The OG look stays as a `CLASSIC` look with its eight
schemes, for the people who like it, and because it already works.

### The dark colourway: VENT

The second audition page gives the dark half. Mapped onto the same token
slots, every value from its `:root`:

| Token | Value | From the page | Contrast |
|---|---|---|---|
| `gray` | `#1D1E1B` | `--panel` | luma 29, inside today's dark law |
| `field` / `raised` | `#232420` | `--raised` | |
| `grayHi` / `grayEdge` | `#393B33` / `#2D2F28` | `--line` / `--line-soft` | hairlines; 1.5 : 1 on `gray`, so a control needs a fill, not a line alone |
| `grayMid` / `grayDark` | `#181916` / `#0E0F0D` | the condition strip, the deepest ground | |
| `ink` / `ink2` / `ink3` | `#EEEADD` / `#B3B4A6` / `#929587` | `--text` / `--muted` / `--quiet` | 13.9 : 1 · 8.0 : 1 · **5.5 : 1** on `gray` |
| `accent` / `amber` / `warn` | `#E08A68` | `--accent` | 6.4 : 1 on `gray`, 7.3 : 1 on `lcd`; warm |
| `lcd` / `lcdInk` | `#0F100E` / `#EEEADD` | the console's deepest fill | 14.9 : 1 |
| `desk1` = `desk2` | `#151614` | `--bg` | flat |
| `deskGlow` | `#2A1E18` | the attractor's warm end, dimmed | a faint warmth behind the window, not a sun |
| `winFrame` | `#55564B` | `.diagram-axis` stroke | 55 luma from `gray`; the `.console` border (`#45473C`) sits exactly on the law's 40-luma floor and `SchemesTest` refused it |
| `title1` = `title2` / `titleInk` | `#1D1E1B` / `#EEEADD` | masthead | flat bar, hairline under it |
| destructive text | `#D6694D` | `--red` | 4.8 : 1 on `gray`; the `bin` token §4 of the look proposal asks for |

VENT passes **every** law in `SchemesTest` and `ContrastTest` as they are
written today, including the three that OPENSKY fails. Its third ink tier
clears AA text contrast, which no current scheme's does. So the order of
work can be: VENT as a ninth scheme under the OG drawing (a pure data
change, provable now), then the `Look` axis, then OPENSKY once the
light-shell decision is reopened. The user gets the dark modern vibe first
and the light one second, and the light one carries the only test changes.

### Four pages, one rule

Two more audition pages arrived after the first two: ONSET (paper, rust
and lime) and OSPREY (paper, ink-blue and sand). Measured the same way, all
four follow a rule TapeOS already has and the look proposal already kept:
**one accent that reads on chrome, a second that reads on the dark
surface.** That is the `accent` slot and the `amber` slot, and the pages
fill them without having been told to.

| Page | Chrome | Ink | On chrome (`accent`) | On the dark surface (`amber`) | Dark surface |
|---|---|---|---|---|---|
| OPENSKY | `#F8F3E9` | `#292820` | `#A74C00` (5.5 : 1) | `#FF8C00` (6.9 : 1) | `#292820` |
| ONSET | `#EEECE3` | `#252820` | `#AA4B28` (4.7 : 1) | `#D5E17B` lime (10.0 : 1, and **1.2 : 1 on paper**) | `#272D25` |
| OSPREY | `#F5F1E7` | `#082D44` | `#126D8B` (5.2 : 1) | `#84C3CF` aqua (7.3 : 1, 1.7 : 1 on paper) | `#082D44` |
| VENT | `#1D1E1B` | `#EEEADD` | `#E08A68` (6.4 : 1) | the same (7.3 : 1) | `#0F100E` |

ONSET makes the point hardest: its lime is unreadable on paper and
excellent on the dark plate, so the page only ever uses it on the dock,
the transport and a 3 px blend bar. That is exactly what `MODERN_LOOK.md`
§4 asked for when it said the `amber` token must never be drawn on chrome
in the modern look. The rule can now be a test rather than a note: *every
scheme's `amber` clears 4.5 : 1 on `lcd`; every scheme's `accent` clears
4.5 : 1 on `gray`.* Today's eight schemes pass it; so do all four pages.

Two further things the pages settle:

- **OSPREY's dock is light.** It is the one page whose player sits on
  paper-light rather than on a dark plate, with the dark surface used for
  the hero's ocean plate instead. Under the two-surface rule the app's dock
  stays dark, so an OSPREY scheme would take its `lcd` from the ocean
  plate (`#082D44`), which is what the table does.
- **The accent pairs are the schemes.** Chrome and ink barely move across
  the three paper pages (paper is paper); what makes each page its own is
  the accent pair and the temperature of the greys. So a modern scheme is
  cheaper to author than an OG one: four or five values, not twenty. The
  roster of the modern look is, at this writing, OPENSKY, ONSET, OSPREY
  and VENT, with VENT the only one that passes the dark-chrome laws today.

### The two pages, one look

The two pages share a structure and differ in temperature. The paper one
is warm and lit from a sun; the dark one is cool and lit by a line. Both
use one accent, hairlines, mono eyebrows, a serif only for the words that
matter, a numbered sequence of sections, a persistent player. That shared
grammar is the modern look; OPENSKY and VENT are its first two schemes, and
a scheme picker that shows them side by side is the old picker with two
more rows.

## 5. Accessibility: what the measurements say about the current design

The worry is well founded, and most of it is measurable from the source
without a phone. The reviews already fixed the structural half: every
control has an accessible name, disabled controls stay in the tree, hit
targets are 48 dp, `ink2` clears AA in every scheme. What remains is the
visual language itself.

| Measure | Current design | Why it matters | Modern look |
|---|---|---|---|
| **Smallest type** | 8 sp Silkscreen at **228** call sites (`TapeType.pixelSmall`), 9 sp at 127 more | A pixel face at 8 sp is below any readable floor for body text; Android's own guidance sits around 12 sp. The sizes honour the user's font-size setting (no density override in the app), but a 1.3× scale of 8 sp is 10 sp | a 12 sp floor and 14 sp body in a proportional sans; mono labels at 11 sp |
| **Case** | the house voice is upper case; roughly three in five of the longer strings in `Personality.kt` are all caps | all-caps text is measurably slower to read at length and loses word shape; it is fine for labels, not for a four-line explanation | sentence case for anything over a label; caps stay for eyebrows and IDs |
| **Hidden gestures** | about **108** long-press or custom-gesture sites (`combinedClickable` 22, `onLongClick` 28, `onLongPress` 20, `detectTapGestures` 38) | a hold with no visible affordance is invisible to a sighted user who does not know to try it; the reviews added legends where it hurt most, and the pad sheet still has no tap door | the pad strip and the stage row give every hold a tap twin; a hold is a shortcut, never the only door |
| **Third-tier text** | OILSLICK's `ink3` is 2.1 : 1 by design, and the default scheme | documented as a measured gap, still a gap | VENT's third tier is 5.5 : 1; OPENSKY's is 3.9 : 1 |
| **Truncation** | 101 single-line ellipsized texts | a status that ends in "…" at a larger font size loses its verb | sentence-length strips wrap to two lines by default |
| **Colour as the only signal** | class colour on pads is doubled by the class tag text; the selected menu tab by weight and a ring | already handled where it was found | the modern look keeps the doubling: a selected pad gets a ring *and* the strip names it |
| **Scanlines over text** | 30 % black lines every 3 dp over LCD text | reduces effective contrast of every LCD readout by roughly a third of a stop | the dock keeps the stripe at the sun's 18 %, and no small text sits under it |
| **The gimmick budget** | `PERSONALITY.md`: one visible gag per screen, the rest hidden; hidden eggs include two controls on TAPE | law 4 and accessibility pull against each other only when an egg is the only way to do something; the reviews already hold the line that every egg has a TalkBack name | keep the slider (OFF / MILD / FULL), default to MILD, and keep law 4 for *delight* while the shape rule above governs *doors* |

None of this is a judgement on taste. The user's own reading of the two
audition pages as easier is what the type-size row predicts.

## 6. Order of work, revised

1. **VENT as a scheme** under the OG drawing. **Done 2026-10-14**: `Schemes.VENT`,
   ninth in `Schemes.ALL`, pinned to the page's `:root` by `SchemesTest`,
   with its `.t-vent` row and swatch card in `design/Schemes.dc.html`. Every
   law passed as written except one, on the floor: the page's console
   border sat exactly 40 luma from the chrome and the window-frame law
   wants more, so the frame takes the page's axis stroke instead. Proves
   the colour half on a phone before anything else.
2. **The project model** in `:kit` and `:shell` with the migration and
   tests. The biggest change and the one the JVM can prove in full.
   **Started 2026-10-14**, in `:kit`: `Project` (held kits by folder name,
   one active kit, tempo, key, with `withKit` / `withoutKit` /
   `activated`), `ProjectStore` (`Projects/<name>/project.json`, the beat
   under `beat/`, `kitDirs` and `missingKits` so a project opens with a
   kit gone), and `ProjectMigration.adopt`. Pinned by `ProjectStoreTest`
   and `ProjectMigrationTest`. **Still to do**: the shelf (`KitShelf`)
   listing projects above kits and running the migration on open; the
   exporters taking a project's `kitDirs` and beat instead of one kit's;
   `tapes/` and `out/`; and the `:app` wiring, which needs an SDK.
3. **The shape** in `:app`, in the OG look: four places, the dock, the
   stages, the pad strip. Checked by the UAT sim's tap counts.
4. **The `Look` axis** and the modern drawing, with the type floor from §5.
5. **OPENSKY**, which reopens the light-shell law with VENT already shipped
   as proof the look works.

Steps 1 and 2 need no Android SDK. Steps 3 to 5 do, for proof, and this
session cannot reach one.
