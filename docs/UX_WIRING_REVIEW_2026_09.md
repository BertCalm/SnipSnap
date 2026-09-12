# Wiring and visibility review, 12 September 2026

A read of everything that landed on the app in the two days after the
September UAT (PRs #149–#173): is each new or reworked feature actually
wired end to end, can a user reach it, is it visible, is it where they
would look, is it labelled in words they would understand, and does
anything explain it.

**Method, and its honest limit.** The Android app cannot be launched in
this environment (no SDK, no device), so nothing here is a screenshot.
What *is* real: every callback below was traced by reading it from the
Compose control through `App.kt` into `:shell` and back; every string
quoted is the constant the screen draws; every layout claim is arithmetic
on the constants in `Schemes.kt` and is marked as estimated. The JVM suite
was run on this branch first and is green (2051 tests, 0 failures), so
nothing below is a model bug wearing a UI — it is all in the last inch.

Severity is the UAT's: **S1** a user is misled, blocked, or hears the
wrong thing; **S2** friction that makes the tool slower or smaller than it
is; **S3** polish, stale words, and things that get worse with use.

---

## The headline

**The wiring is almost entirely sound.** Of the fourteen features
checked, thirteen have every callback connected, no no-op defaults at
their call site, refusals in words, and an accessible name on the new
controls. The a11y pass held: the new work got the same treatment the
old work did, with three exceptions listed under S2/S3.

What the wave did *not* consistently do is tell the user. Three
patterns account for most of what follows:

1. **A feature ships with no on-screen trace.** MORPH's tilt response
   touched only the engine and its store; nothing on SURFACE, in a
   toast, or in HELP says the phone's angle now does anything. A
   feature the user cannot discover is, to them, not shipped.
2. **A refusal or hint names a control by a word that is not on any
   screen.** HUM says "ARM THE MIC"; the button is `LISTEN · MIC`.
   The no-hits toast says "TRY GRID"; GRID is inside a collapsed box.
3. **The same word means different things one tab apart.** GRID is
   four controls on CHOP. BOUNCE goes "TO SNIPS" on GROOVE and LOOP and
   "TO TAPE" on ORBIT, and lands in the same folder. CATCH is the
   capture verb in HELP's first line and a specific gesture in its
   seventeenth.

And two things that are not visibility at all but were found on the way,
and outrank everything else because the user *hears* them:

- **HEAR on the MUTATE card plays a stereo render through a mono
  voice** — an octave down at half speed on any stereo source.
- **GROOVE plays a 3/4 or 5/4 clip from ORBIT as 4/4.**

---

## Findings, by severity

### S1 — misled, blocked, or hears the wrong thing

| # | Finding | Evidence |
|---|---|---|
| **1** | **HEAR plays MUTATE's preview an octave low at half speed.** `Mutate.render` up-mixes both parents to two channels and returns interleaved stereo. `onHear` hands that straight to `audition()`, which builds a `TapeVoice` — a `CHANNEL_OUT_MONO` track over a bare `FloatArray`. Every other play path on the sheet calls `Cleanup.toMono` first; HEAR is the one that does not. The fix is one call. | `PadSheetScreen.kt:1288-1290`, `:321-331`; `Mutate.kt:178-179`, `:473-486`; `TapeVoice.kt:43`, `:328`, `:355`; compare `PadSheetScreen.kt:293`, `:381` |
| **2** | **GROOVE plays a non-4/4 ORBIT clip as 4/4, and shows no meter.** CLIP ▸ KIT now writes `pulsesPerBar` into `groove.json` and `GrooveStore` persists it, but nothing in `app/src/main` reads it. GROOVE's loop length is `bars × STEPS_PER_BAR`, its accent is `step % 16 == 0`, its bar counter divides by 16. A two-bar 3/4 clip loops as 32 steps with eight silent ones on the end; its "2 BARS" is not ORBIT's "BAR 12 · 3/4". `GrooveStore.kt:30-40` warns about *old builds* misreading exactly this; the current screen does it too. | `GrooveStore.kt:96-97`, `:127`; `GrooveScreen.kt:892`, `:955`, `:1617`, `:1679`; `OrbitScreen.kt:1241`; `grep -rn pulsesPerBar app/src/main` → nothing |
| **3** | **The CATCH grid on TAPE is wider than a phone held upright.** `BankRow` needs `MIN_HIT_TARGET × 8 + PAD_GAP × 7` = 440 dp *per bank* to lay both banks side by side; under that it falls back to two fixed 440 dp banks in a horizontal scroll. TAPE is a portrait screen at ~390 dp, so bank A's eighth column is clipped and bank B is a scroll away — during a hold. PLAY only uses `BankRow` in landscape; portrait PLAY uses the 4×4 window grid. `docs/CATCH.md:25` ("both banks, drawn as PLAY draws them") is true only of landscape. | `PadGrid.kt:125-139`; `Schemes.kt:383`, `:408`; `TapeScreen.kt:929`; `PlayScreen.kt:336`, `:426` |
| **4** | **HUM's refusal names a control that does not exist.** With the mic not running, HUM toasts `ARM THE MIC FIRST. HUM LISTENS THROUGH IT.` Nothing on any screen is called ARM; the door is `LISTEN · MIC` on the KITS shelf, two tabs away. `docs/CHOP_CONTROLS.md` §10 claims the toast "says which door arms it" — it does not. A first-time user is stopped with no route. | `Personality.kt:738`; `ChopScreen.kt:545`; `KitsScreen.kt:1277` |
| **5** | **GRAIN ▸ is very likely drawn at zero height.** The button carries `Modifier.weight(1f)` while its sibling MAKE INSTRUMENT is `fillMaxWidth()` — a leftover from a `Row`. It now sits in `GroupBox`'s content `Column`, inside the sheet's `verticalScroll` column, where the incoming max height is unbounded; Compose gives a weighted child of an unbounded column zero main-axis space, and `heightIn(min = 48)` cannot exceed an incoming max of 0. This is the **only door into GRAIN FIELD** (`grainFieldSlot` is set nowhere else), and `PAD_SHEET_LEGEND` promises GRAIN. Present since the group-box commit, so older than this wave — but nobody has walked it since. **Read off the layout rules, not a device; one screenshot settles it.** | `PadSheetScreen.kt:2403-2409`, `:2040`; `GroupBox.kt:104-108`; `ActionButton` at `PadSheetScreen.kt` (`heightIn`, not `requiredHeightIn`); `App.kt:2429` (sole setter) |

### S2 — friction, hidden features, silent refusals

| # | Finding | Evidence |
|---|---|---|
| **6** | **Tilt in MORPH mode is invisible.** The commit touched `SurfaceEngine.cpp` and `SurfaceStore.kt` only. The `TILT 0.50` readout predates it; no toast, legend, HELP line or mode copy says tilt shapes anything. There is no tilt on/off control anywhere in the app, so a user holding the phone at an angle in MORPH hears a resonance offset on every corner with no explanation and no way out. The screen's own KDoc still says tilt is XYZ-only. | `git show --stat 074ac99`; `SurfaceScreen.kt:595` (same line at `074ac99~1`), `:79-82`; `Personality.kt:406` (the one HELP line) |
| **7** | **SET A–D are dimmed in the mode you are told to use them in.** `dimmed = mode != Mode.MORPH`, yet the SURFACE explainer boards and `BENCH.md` both say "find a sound in XY or XYZ, tap SET A". Dimmed-but-live reads as "not now". | `SurfaceScreen.kt:474-479`; `design/surface-explainer/` |
| **8** | **GROOVE's BOUNCE greys out with no reason.** `enabled = playing && currentClip != null && bankReady && …`; the button simply dims. RECORD on the same screen deliberately toasts the reason instead of gating ("Telling the user beats gating the button outright"). A user on a stopped loop sees a dead BOUNCE and nothing says PLAY first. | `GrooveScreen.kt:1853-1855` vs `:1340-1345` |
| **9** | **CHOP's mode row goes silently dead while busy or humming.** `SegmentButton` has no `enabled` parameter; the bench wraps taps in `if (!busy)` and `rechopTo` returns when `humming`. So during CUTTING… or a hum, BY HITS / GRID / GHOSTS / EAR / CUT / the ladder look live, announce as actionable, and do nothing — against the screen's own stated rule, "dimmed, not disabled; the toast explains". | `ChopScreen.kt:1505-1510`, `:419`, `:1321-1349`, `:1118-1120` |
| **10** | **RE-CHOP is not gated on a running hum, and drops it.** `enabled = !rechopBusy && !sendBusy` omits `humming`; the model swap resets `humming` (it is `remember(model)`), the hum is lost, and the toast says only RE-CHOPPED. `CHOP_CONTROLS.md` §10 says RE-CHOP is off while the hum runs. | `ChopScreen.kt:927-929`, `:394` |
| **11** | **◀ ▶ are live but dead on a hummed chop.** The unit falls to PART, the buttons stay enabled and speak "ONE PART FEWER", and `stepHits` returns early because a hummed model has no hit count. No dim, no toast. | `ChopScreen.kt:1269-1273`, `:439`, `:751` |
| **12** | **Any bench change silently resets the layout row to CLASSIC.** `layout` is `remember(model)`; every rechop, merge, split or hum swaps `model`. A user on FOLD or MELODIC who taps ◀ is put back on CLASSIC with no word. | `ChopScreen.kt:375` |
| **13** | **The zoom ladder is two gates deep and is not called what HELP calls it.** It appears only inside the CUT box (closed by default) and only after GRID is chosen. Its first rung is `COUNT`, an in-house term with no hint. HELP says "ZOOM ON CHOP"; the screen has no control named ZOOM, only a caption. The GRID segment and the COUNT rung do the same thing. | `ChopScreen.kt:410`, `:1305`, `:1321-1323`, `:732-736` vs `:473-475`; `Personality.kt:419` |
| **14** | **Two toasts point into a closed box.** `NO HITS IN THIS SOURCE. TRY GRID…` and the AUTO-none toast name GRID, which lives inside the collapsed CUT `GroupBox`; nothing says "open CUT". | `Personality.kt:122`, `:1070`; `ChopScreen.kt:410` |
| **15** | **MERGE / SPLIT have no on-screen hint.** They live inside the chip's class picker; HELP knows ("UNDER A CHIP") but CHOP does not say so. | `ChopScreen.kt:1121-1134`; `Personality.kt:414` |
| **16** | **CATCH's DONE count can be stale across sessions.** `catchSession.landed` is cleared only in `catchDone`; leaving TAPE by the menu mid-catch never calls it. The next session's DONE announces the old slots and may open KIT on their bank with nothing caught. | `App.kt:1594`, `:1620`; `TapeScreen.kt:789-799` |
| **17** | **CATCH's queued landings are invisible, by design, and DONE drops the grid before they land.** `finishCatch` removes the grid at once; `catchDone` then silently joins the write chain before toasting. The user sees a plain deck with nothing pending. A whole-tape catch also leaves a permanent selection behind. | `TapeScreen.kt:791`, `:765-768`; `App.kt:1560`, `:1618`; `docs/CATCH.md:113` |
| **18** | **DUST FROM ▸ on an untreated pad cannot set AMT first.** Copy says "…GOES UNDER THIS PAD AT AMT", but AMT is `enabled = activeSegment != null`, so the first pick always runs at the default 0.7. | `Personality.kt:819`; `PadSheetScreen.kt:2981`, `:1964`, `:2022` |
| **19** | **A tab switch mid-treatment goes dark.** The rewrite survives on `appScope` and the landing toast arrives, but between the switch and the landing there is no busy state anywhere: the sheet's header dies with the sheet and KIT's `busy` is App's own. Returning to KIT shows a tappable grid while the rewrite is in flight (safe under `KitWrites.mutex`, just unannounced). | `PadSheetScreen.kt:700`, `:832`, `:1225`, `:2916-2921`; `App.kt:2065-2071`, `:2532` |
| **20** | **LOOP's mute toggles are invisible to TalkBack.** `TrackHeader` is raw `.clickable { onToggle() }` with no label and no state; engaged is a colour. `TempoStep` and `BlockCell` in the same file were fixed for exactly this. | `LoopGrid.kt:345-358` vs `:221`, `:440-446` |
| **21** | **SURFACE's mode buttons carry no selected state, and the pad's description omits MORPH.** Modes are `dimmed = m != mode` only; TalkBack hears three equal buttons. The pad's `stateDescription` speaks X/Y (and Z in XYZ) but never the corner weights or tilt, so a screen-reader user in MORPH gets the least of any mode. | `SurfaceScreen.kt:400-408`, `:502-506` |
| **22** | **PAD SHEET is still hold-only.** UAT 4/5 are half closed: the legend is permanent, the hint now repeats until the sheet is first opened, TalkBack has `OPEN PAD SHEET`, and DOUBLES' GO ▸ opens it directly. There is still no tap path and no menu entry; the file says so itself. | `KitScreen.kt:92`, `:361-371`, `:749`, `:856`; `App.kt:2280-2293`, `:2221` |
| **23** | **Three documents describe a build that no longer exists.** `DEVICE_TEST_GUIDE.md` says LOOP has "no tempo control, and a block tap that does nothing yet" (both shipped) and has no item for GROOVE's BOUNCE, the least verifiable thing in the wave. `SPECS_2026_09.md` still says "GROOVE cannot be bounced at all" and "no screen arms it". `CHOP_CONTROLS.md` §2 says the CUT bench is four rows (it is up to six). `BENCH.md` benches tilt in XYZ only. | `DEVICE_TEST_GUIDE.md:105-107`; `SPECS_2026_09.md:128-142`; `CHOP_CONTROLS.md:19`; `BENCH.md:130-131` |

### S3 — words, glyphs, and stale text

| # | Finding | Evidence |
|---|---|---|
| **24** | **GRID means four things on CHOP.** The GRID segment (equal parts), ON THE GRID (tempo snap), the `GRID ×8` header, and SEND TO GRID (the pads). `16TH` is both a snap value and a ladder rung with different meanings, and the two rows never show together. | `ChopScreen.kt:1283`, `:1346`; `Ladder.kt:40`; `ChopReview.kt:196` |
| **25** | **BOUNCE has three names for one destination.** LOOP: "IT IS IN SNIPS NOW"; GROOVE: "BOUNCED … TO SNIPS"; ORBIT: `BOUNCE TO TAPE` / "ON TAPE". All three write through `SnipStore.import` into the same folder SNIPS lists. Neither GROOVE's nor LOOP's button says where it goes before the tap. | `Personality.kt:1405`, `:1435`, `:1618`; `LoopBounce.kt:135`; `GrooveScreen.kt:507`; `OrbitScreen.kt:898`, `:1461` |
| **26** | **LOOP's `BOUNCE ▸ n BARS` breaks the glyph rule and can read "1 BARS".** ▸ means "goes somewhere" elsewhere in the app (ORBIT dropped it for exactly this reason: "both write in place"); LOOP's bounce stays on LOOP. The label is a raw string outside `Copy`, so it missed the `barsOf` singular fix the toast got. | `LoopGrid.kt:275`; `OrbitScreen.kt:1455-1464`; `Personality.kt:1635` |
| **27** | **CATCH means two things in HELP.** Line one: "TAPE — CATCH A SOUND" (capture). Line seventeen: "CATCH ON TAPE: HOLD A PAD…" (the gesture). | `Personality.kt:394`, `:417` |
| **28** | **HELP has no line for LOOP, BOUNCE, OUTSIDE, or SURFACE's modes.** It covers CATCH, HUM, ZOOM, GHOSTS, FOLD, the CUT bench and DUST; the bounce family and the SURFACE/OUTSIDE work are absent. (HELP itself, and SETUP, sit off the menu row's right edge; the arrow cue from UAT 10 is the only pointer.) | `Personality.kt:402-422`; `Chrome.kt:215-228` |
| **29** | **In CATCH mode, empty pads still announce PLAY and the transport speaks a glyph.** Empty pads say `EMPTY PAD A01`, action `PLAY`; the catch header's `▶` / `■` are bare, where the normal row says `▶ PLAY` / `■ STOP`. No `stateDescription` for armed or caught. | `PadGrid.kt:201`, `:264`; `TapeScreen.kt:926`, `:951`, `:1235` |
| **30** | **`▶ HEAR` and `▶ HIT` set no `accessibilityLabel`** despite `ActionButton`'s own KDoc existing for the glyph case. | `PadSheetScreen.kt:2706`, `:3361`, `:3505-3516` |
| **31** | **Group-box legends are repeated as card titles inside the box** ("TREATMENT" then "TREATMENT"; likewise MUTATE, OUTSIDE, SHAPE), where `UI_DESIGN.md` says boxes *replace* cards. | `PadSheetScreen.kt:2917`, `:3074`, `:3142`, `:3410`; `UI_DESIGN.md:168` |
| **32** | **OUTSIDE's cancel does not stop the mic.** The KDoc keeps the trip on the composable's scope so "a tab switch stops the mic", but `OutsideSession.run` is a blocking `read` loop with no cancellation check; the mic runs to `listenFrames`, then the return is discarded with no toast. The trip is a few seconds, so the cost is small; the stated reason is false. | `PadSheetScreen.kt:1507-1516`, `:1620`; `OutsideSession.kt:99-122` |
| **33** | **Stale KDoc and doc lines.** `CutBench` doc lists two modes (there are four plus the ladder); `SegmentButton` doc still says "CLASSIC/MELODIC, Batch 3"; `MutateCard` says four moves and two partner kinds (six and five); `PadSheet.kt:11` says five segments (six); `UAT_2026_09.md:65` says 20 segments (21 with DUST); `Hum.Reading.pattern` exists "for a later READ AS GROOVE" and is read only by a test; `LoopWrites.kt:11-13` under-counts its writers; the SURFACE explainer boards predate the tilt commit and show a `(FLAT)` suffix the readout never prints. | `ChopScreen.kt:1226-1229`, `:1490-1503`; `PadSheetScreen.kt:3095-3097`; `Hum.kt:70-73`; `design/surface-explainer/SetCorner.dc.html:34` |

---

## Feature by feature

Wired = every callback connected end to end with no no-op default at the call site. Reachable = a user can get there without a secret. Labelled = the on-screen words say what it does. Explained = something in-app (legend, toast, HELP) teaches it.

| Feature | Wired | Reachable | Labelled | Explained | Verdict |
|---|---|---|---|---|---|
| CATCH on TAPE | ✓ | ✓ full-width `CATCH A HIT` button | ✓ every state in words | ✓ header + HELP | **Grid too wide in portrait (S1 #3)**; DONE count stale (#16) |
| CUT bench, MERGE / SPLIT | ✓ | box closed by default; MERGE/SPLIT under a chip | ✓ | HELP only (#15) | Fine once found |
| GHOST CHOP | ✓ | mode segment in CUT box | ✓ `GHOSTS` | ✓ toast + HELP | Fine; readout may truncate (est.) |
| ON THE GRID / FOLD | ✓ | snap row under BY HITS/GHOSTS; FOLD always | `FOLD` opaque until tapped | toast on tap + HELP | Fine |
| Zoom ladder | ✓ | two gates deep (#13) | `COUNT` unexplained; not called ZOOM | caption + HELP | Hard to find |
| HUM the chop | ✓ | mode segment `HUM` / `STOP` | ✓ | ✓ toast + HELP | **Refusal names a control that isn't there (S1 #4)**; RE-CHOP drops it (#10) |
| SEND writes hum as groove | ✓ CLASSIC only | — | toast on landing | doc only | Fine; nothing says beforehand that SEND writes a groove |
| GROOVE BOUNCE | ✓ | TRANSPORT group under PLAY | `BOUNCE` / `WAITING…` / `BOUNCING…` | toast on landing | Disabled without reason (#8); destination unnamed (#25) |
| LOOP bounce + tempo | ✓ | bottom row of LOOP | `BOUNCE ▸ n BARS`, `− 90 BPM +` | toast + legend | Glyph and "1 BARS" (#26); mute toggles unnamed (#20) |
| SNIPS shows a landing | ✓ | overlay | — | — | Fine |
| ORBIT CLIP ▸ KIT → GROOVE | ✓ reload wired | — | toast says the clip landed | — | **GROOVE misreads the meter (S1 #2)** |
| HEAR on MUTATE | ✓ | `▶ HEAR` beside MUTATE ▸ | ✓ | KDoc only | **Stereo into a mono voice (S1 #1)** |
| DUST FROM ▸ / CRACKLE | ✓ | TREATMENT box | ✓ | ✓ HELP | AMT locked on first pick (#18) |
| OUTSIDE (REAMP/ROOM/KEEP ROOM) | ✓ | fourth box | ✓ stage on the button | ✓ explainer in the box | Cancel doesn't stop the mic (#32) |
| MORPH tilt | ✓ engine + store, tested | always on, no control | none | none | **Invisible (S2 #6)** |
| Print tap on pads | ✓ | via GROOVE BOUNCE | — | — | Fine; `SPECS` says it has no caller (#23) |
| GRAIN FIELD | ✓ | `GRAIN ▸` in MAKE box | ✓ | legend | **Likely zero-height (S1 #5)** |

---

## What is working, and shouldn't be touched

- **Every new callback reaches App.kt and the shell.** No new control is passed `{}` at its real call site; the defaults that exist on `TapeScreen`, `ChopScreen`, `OrbitScreen` and `PadSheetScreen` are all overridden where the screens are mounted.
- **Refusals are in words and say the next step**, with the one exception in #4. CATCH's seven refusal strings are the model for the rest of the app.
- **The a11y pass carried into the new work**: SegmentButtons speak `selected`; ◀ ▶ carry spoken labels; steppers carry range info; GroupBox strips announce EXPAND/COLLAPSE and the legend; the LOOP tempo steps and block cells are labelled.
- **The bounce family is not one quantity in three places.** LOOP renders offline through `LoopBounce`; GROOVE prints through the native tap; ORBIT renders its own. Three engines, three screens, and the landing count on `LoopBounce` is only claimed by the one that uses it.
- **Group-box rules hold on the pad sheet**: one open at a time, closed by default, remembered per kit, nav pinned.
- **HELP is current for CHOP and TAPE**, the `M0` tag is gone, and a test holds HELP's four loop stages to the real tabs.

---

## If only five things get fixed

1. `Cleanup.toMono` before `audition` in `onHear` (#1). One line.
2. Read `pulsesPerBar` in GROOVE and use it for loop length, accents and the bar counter — or refuse a non-4/4 clip in words until it does (#2).
3. Give CATCH a portrait layout: the 4×4 window grid PLAY already uses in portrait, or a bank toggle (#3).
4. Reword `HUM_NOT_LISTENING` to name `LISTEN · MIC` on KITS (#4), and `CHOP_NO_HITS` to say "OPEN CUT, TRY GRID" (#14).
5. Confirm #5 with one screenshot; if GRAIN ▸ is missing, drop the `weight(1f)` for `fillMaxWidth()`.

Then, cheaply: a toast on GROOVE's disabled BOUNCE (#8), a `selected` state on SURFACE's modes and a `TILT` word in MORPH's copy (#6, #21), and the three stale documents (#23).
