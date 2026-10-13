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

```
library/                      shared by every project
  kits/<name>/                 a kit folder, exactly as today
  snips/                       as today
  instruments/, rooms/         as today
projects/<name>/
  project.json                 name, tempo, key, which kits (by reference), active kit
  beat/                        patterns and the song: today's groove.json content, moved
  tapes/                       what was captured in this project (snips still land in the library)
  out/                         what this project has written: kit folders, sessions, bounces
```

- **A kit is referenced, not copied.** Edit it in project B and project A
  sees the edit, which is what "the kit should be available" means. A
  project that wants its own copy duplicates it on purpose, the way
  VERSIONS already takes a take.
- **The beat moves to the project.** `groove.json` today holds "the kit's
  grooves" because the kit was the only container. In the project model
  a pattern belongs to the project and *plays* a kit, which is also how
  `SessionBuilder` already writes sequences. The export of a bare kit
  keeps embedding its clips, unchanged: a project's patterns that use that
  kit are what gets embedded.
- **Migration is one rule.** Every existing kit becomes a project of the
  same name holding that kit, with the kit's `groove.json` as the
  project's beat. Nothing is lost and nothing asks.

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
| `winFrame` | `#45473C` | `.console` border | 40 luma from `gray`, at the law's floor |
| `title1` = `title2` / `titleInk` | `#1D1E1B` / `#EEEADD` | masthead | flat bar, hairline under it |
| destructive text | `#D6694D` | `--red` | 4.8 : 1 on `gray`; the `bin` token §4 of the look proposal asks for |

VENT passes **every** law in `SchemesTest` and `ContrastTest` as they are
written today, including the three that OPENSKY fails. Its third ink tier
clears AA text contrast, which no current scheme's does. So the order of
work can be: VENT as a ninth scheme under the OG drawing (a pure data
change, provable now), then the `Look` axis, then OPENSKY once the
light-shell decision is reopened. The user gets the dark modern vibe first
and the light one second, and the light one carries the only test changes.

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

1. **VENT as a scheme** under the OG drawing. Data only; every test passes
   as written. One day. Proves the colour half on a phone before anything
   else.
2. **The project model** in `:kit` and `:shell` with the migration and
   tests. The biggest change and the one the JVM can prove in full.
3. **The shape** in `:app`, in the OG look: four places, the dock, the
   stages, the pad strip. Checked by the UAT sim's tap counts.
4. **The `Look` axis** and the modern drawing, with the type floor from §5.
5. **OPENSKY**, which reopens the light-shell law with VENT already shipped
   as proof the look works.

Steps 1 and 2 need no Android SDK. Steps 3 to 5 do, for proof, and this
session cannot reach one.
