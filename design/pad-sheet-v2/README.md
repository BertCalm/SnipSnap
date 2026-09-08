# Pad Sheet v2 and Rooms — the design boards

The canvas that decided wave DDD (the pad sheet folds), exported here so the
repo carries the decision beside the code. Live canvas:
https://claude.ai/code/artifact/4b7284cc-6d6b-420e-9af4-236cafadea74

Every `*.dc.html` is one artboard (390 × 844 unless noted, OILSLICK, the
app's own tokens, bevel, type ramp and 44 px hit floor lifted from
`Schemes.kt`, `Bevel.kt` and `TapeTheme.kt`); `canvas.json` lays them out on
pages, one per screen or flow checked, added to as each pass lands rather
than fixed in number. Open a board in a browser with `support.js` beside it,
or seed them back onto a canvas.

| Board | What it shows |
|---|---|
| `Main.dc.html` | **Direction A, chosen** — five group boxes closed; the whole sheet fits one screen |
| `MutateOpen.dc.html` | A with MUTATE open (the ROOMS chips), the sheet cut under the pinned nav |
| `OutsideOpen.dc.html` | A with OUTSIDE open mid-trip: the strip in lcd-alt, the reels turning |
| `Rooms.dc.html` | THE SHELF with a ROOMS section; a held row revealing FORGET → BIN |
| `DirectionB.dc.html` | *Not chosen* — a five-chip workshop selector |
| `DirectionC.dc.html`, `WorkshopScreen.dc.html` | *Not chosen* — a short sheet plus a second screen; two ideas kept for A |
| `TripIdle.dc.html`, `TripListening.dc.html`, `TripSending.dc.html`, `TripBack.dc.html`, `TripRefused.dc.html` | **OUTSIDE, state by state** (page three): the box alone through a trip — idle, the mic open, the sweep out, back and measured, refused. Drawn from the code after wave DDD; the note named two small gaps between board and code, closed by wave GGG (the measured line now reads on an LCD; KEEP ROOM now lights in lcd-alt) |
| `LandingSkips.dc.html`, `LandingRefused.dc.html` | **The landing's message box** (page four, wave FFF): a kit file with one skip; a share the shelf refused. Built blind, drawn here to check it — the refusal's long line moved the box's line cap from two to three |
| `KitClosed.dc.html`, `KitKeyOpen.dc.html`, `KitTextureOpen.dc.html` | **KIT · the action row and TEXTURE** (page five): the closed sheet's six-line action row (TAKES + BIN, REMIX BANK B, KEY, SCULPT, STRETCH, SHARE), and KEY / TEXTURE each open beneath it. Drawn from the code against the oldest artboard's single three-item row — nothing clips or needs a code fix; the note flags the six-line density as a candidate for a `GroupBox` pass, not an emergency |
| `GrainLoading.dc.html`, `GrainIdle.dc.html`, `GrainDuetNeedsMic.dc.html`, `GrainDuetArmed.dc.html` | **GRAIN FIELD** (page six): loading, idle (the scatter), DUET without the mic armed (START MIC full-width), DUET armed (the auto-cursor ring). Never had a board — arrived from another branch. Held up as built, no fix |
| `SurfaceXY.dc.html`, `SurfaceMorph.dc.html`, `SurfaceEmpty.dc.html` | **SURFACE** (page seven): XY mode, MORPH (the readout's longest case), no pad loaded. Never had a board either — and drawing MORPH's readout (latency, X/Y, up to four corner weights, TILT, the pad name, one un-wrapped LCD line) found it clipping mid-digit, well past the frame at 390. Every board here shows the fix (`TapeText` now allows a second line), not the clipped original |
| `ShareBusy.dc.html`, `ShareChooser.dc.html`, `ShareClean.dc.html`, `ShareNowhere.dc.html`, `BackupEmpty.dc.html`, `BackupSkips.dc.html` | **SHARE / BACKUP · the chooser hand-off** (page eight, DESIGN_GAP's last undrawn pair): SHARE packing (every door dimmed, PACKING… in the status bar), the system chooser itself drawn schematically (SnipSnap owns nothing past this point), the clean return toast, and the no-receiver toast — then BACKUP's empty-shelf gate (dimmed, no chooser ever offered) and a partial backup's message box (one kit skipped, two packed). Drawn from `ShareOut.kt`, `App.kt`'s `shareKit`/`backupShelf`, and `LandingNote.kt` |
| `SplitNotSplit.dc.html`, `SplitWorking.dc.html`, `SplitDeskPlaying.dc.html`, `SplitPrintHot.dc.html` | **SPLIT** (page nine): a pad chosen and SPLIT lit amber, the desk visible but disabled at rest; WORKING (the spectrogram pass, every door dimmed); the desk playing — SINES pushed up and reversed, TRANSIENT untouched, AIR pulled down and muted, STOP lit; printed hot to TAPE, the desk pushed toward +6 dB and the toast carrying PRINT's own hot-peak warning. Never had a board — EEE9 arrived from another branch. Drawn from `SplitScreen.kt`, `Layers.kt` and `Fader`'s own drawing code; held up as built, no fix |

The spec note on `canvas.json` (page one, `spec`) is the handoff for Compose:
one box open at a time, remembered per kit; the pad nav pinned; the reels on a
trip; KEEP ROOM's dim rule; a 44-character label budget. Built in wave DDD;
`docs/DESIGN_GAP.md` records what the older handoff no longer covers.

Pages three through nine were added after the build, the other way
round: the code came first (OUTSIDE in wave DDD, the message box in wave
FFF, KIT's action row and TEXTURE grown over several waves, GRAIN FIELD,
SURFACE and SPLIT arriving whole from another branch, SHARE/BACKUP built
blind across F6.3 and X3.3) and the boards check it. Where board and
code differed the note on the page said which should move; wave GGG
closed the trip's two, and SURFACE's readout was fixed in the same pass
that drew it. Page five found no defect — its note records that outcome
too, so a future pass doesn't re-ask the question. GRAIN FIELD, SPLIT
and SHARE/BACKUP all held up the same way: page eight closed
`DESIGN_GAP.md`'s last "still undrawn" line, and page nine checks EEE9,
the newest screen with no board of its own yet.
