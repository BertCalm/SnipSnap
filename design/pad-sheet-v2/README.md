# Pad Sheet v2 and Rooms — the design boards

The canvas that decided wave DDD (the pad sheet folds), exported here so the
repo carries the decision beside the code. Live canvas:
https://claude.ai/code/artifact/4b7284cc-6d6b-420e-9af4-236cafadea74

Every `*.dc.html` is one artboard (390 × 844, OILSLICK, the app's own tokens,
bevel, type ramp and 44 px hit floor lifted from `Schemes.kt`, `Bevel.kt` and
`TapeTheme.kt`); `canvas.json` lays them out on two pages. Open a board in a
browser with `support.js` beside it, or seed them back onto a canvas.

| Board | What it shows |
|---|---|
| `Main.dc.html` | **Direction A, chosen** — five group boxes closed; the whole sheet fits one screen |
| `MutateOpen.dc.html` | A with MUTATE open (the ROOMS chips), the sheet cut under the pinned nav |
| `OutsideOpen.dc.html` | A with OUTSIDE open mid-trip: the strip in lcd-alt, the reels turning |
| `Rooms.dc.html` | THE SHELF with a ROOMS section; a held row revealing FORGET → BIN |
| `DirectionB.dc.html` | *Not chosen* — a five-chip workshop selector |
| `DirectionC.dc.html`, `WorkshopScreen.dc.html` | *Not chosen* — a short sheet plus a second screen; two ideas kept for A |
| `TripIdle.dc.html`, `TripListening.dc.html`, `TripSending.dc.html`, `TripBack.dc.html`, `TripRefused.dc.html` | **OUTSIDE, state by state** (page three): the box alone through a trip — idle, the mic open, the sweep out, back and measured, refused. Drawn from the code after wave DDD; the note names two small gaps for the code to close (the measured line on an LCD; KEEP ROOM lit in lcd-alt) |
| `LandingSkips.dc.html`, `LandingRefused.dc.html` | **The landing's message box** (page four, wave FFF): a kit file with one skip; a share the shelf refused. Built blind, drawn here to check it — the refusal's long line moved the box's line cap from two to three |

The spec note on `canvas.json` (page one, `spec`) is the handoff for Compose:
one box open at a time, remembered per kit; the pad nav pinned; the reels on a
trip; KEEP ROOM's dim rule; a 44-character label budget. Built in wave DDD;
`docs/DESIGN_GAP.md` records what the older handoff no longer covers.

Pages three and four were added after the build, the other way round: the
code came first (OUTSIDE in wave DDD, the message box in wave FFF) and the
boards check it. Where board and code differ the note on the page says
which should move.
