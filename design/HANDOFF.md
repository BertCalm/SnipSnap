# TapeOS OILSLICK — Android handoff

Scheme for SnipSnap's TapeOS shell. Prototype: `TapeOS Oilslick.dc.html`.
Static artboard tokens: `Schemes.dc.html` (`.t-oilslick`, `.t-metal` — light artboards retired with the light schemes), picker row in `TapeProperties.dc.html`. The CLEAR prototype file was deleted; GROOVE and all newer screens live only in `TapeOS Oilslick.dc.html`.

Scheme picker now carries 5 dark/neon schemes only (light shells cut — they didn't land): METAL (TYPE IV), OILSLICK (default), and three Oilslick colorways that swap the full token set but keep the design: PETROL (blue `#2A6AE0` / mint `#40E890` / gold `#F0D040`, navy shell), INFRARED (violet `#9A2AE0` / crimson `#E02A5A` / amber `#FF8A1A`, maroon shell), ACID (chartreuse `#A0E020` / cyan `#20D0E8` / violet `#8A5AF0`, green-black shell). Class colours stay fixed across all schemes.

## Scheme tokens (drop into the existing TapeOS token table)

| Token | Value | Used for |
|---|---|---|
| desk1 / desk2 | `#0C0618` / `#140B24` | desk checker (3px), plus grid overlay `rgba(224,64,200,.08)` h / `rgba(90,42,224,.12)` v @ 22px |
| desk-glow | radial `#2A1050 → #0C0618` at 50% 110% | behind everything |
| win | `#1A1424 → #120E1A` vertical | window body |
| win-frame | OILSLICK gradient (below), 3px | replaces bevel highlight/shadow pair |
| field | `#161020`, border `#34285A`, r6 | sunken panels, lists |
| raised | `#221A34 → #161020`, border `#34285A` | buttons, empty pads |
| lcd | `#0A0714`, inset shadow `0 2px 8px #000` | all LCDs; scanlines: `rgba(0,0,0,.3)` 1px/3px |
| lcd-ink | `#C8B2F8` + glow `rgba(154,108,240,.9)` | primary LCD text |
| lcd-alt | `#40E0E8` + glow `rgba(64,224,232,.8)` | the "amber" slot (cyan here) |
| warn | `#FFB000` | needle, onset bars, warnings |
| ink / ink2 / ink3 | `#C8B2F8` / `#7A6AA0` / `#584A80` | text hierarchy |
| title-ink | `#FFFFFF` | titlebar text |
| accent | `#E040C8` (selected: menu item, row inset bar 3px) | selection |

**OILSLICK gradient** (titlebar, window frame, primary-button rims, progress fills):
`conic-gradient(from 210deg at 50% 40%, #5A2AE0, #E040C8 25%, #40E0E8 50%, #8A5AF0 75%, #5A2AE0)`
Compose Brush: `Brush.sweepGradient` with those 5 stops; on API < 33 fall back to a linear `#5A2AE0 → #E040C8 → #40E0E8`.

## Class colours — unchanged in every scheme (from the repo)

KICK `#E8542E` · SNARE `#FFC41F` · HAT CL `#1FC6CF` · HAT OP `#7ADFE4` · CLAP `#E8409F` · PERC `#8FD424` · TOM `#9A6CF0` · LOOP `#3F8CF0`
Tint (text on dark): mix 55% white. Assigned pad = 2px class border + outer glow `class@60%` 14px; lit = 26px glow + inset `class@40%` + scale .97.

## Type

| Role | Font | Size |
|---|---|---|
| LCD | VT323 | 25/21px LCD headers, 22px readouts, 17-18px small |
| Pixel UI | Silkscreen | 9px (8px status quips), +0.5 tracking |
| Display | Michroma | 11-12px, +2px tracking, key actions/titles |
| Handwriting | Rock Salt | 11-15px, pad + cassette + loop-block labels (was Permanent Marker; tweakable) |

Android: VT323/Silkscreen/Rock Salt on Google Fonts; Michroma too. Sizes are dp at 390dp width.

## Layout constants

Phone frame 390×844, outer margin 12. Titlebar 34 (r4). Menu row 26, items pad 3×4 — 9 items max. Status bar 26, 3 cells (fixed, fixed, flex-quip). LCD header 40-44. Pad grid 4×4, gap 8, pad h 76, r6. Primary action h 52-58: 2px OILSLICK rim + dark fill. Hit targets ≥44.

## Screens in prototype (map to repo modules)

| Screen | Repo source | Notes |
|---|---|---|
| KITS | (new) | tape shelf, status ON CARD/DRAFT |
| KIT | Main.dc.html | pads WebAudio-triggered, arm session |
| TAPE | TapeDeck.dc.html + prototype/tapedeck.html | drag-under-fixed-needle, zoom 1-4×, snap-to-onset, pencil rewind gag |
| TAPE (RE-TRIM) | TapeRetrim.dc.html | opened from a pad's RE-TRIM ▸: header `RE-TRIM A02 · <file>` in the pad's colour, IN/OUT on the pad's cut, a `◀ HIT · HIT 3/7 · HIT ▶` stepper over INSTANT KIT's slices above the primary row, BACK ONTO A02 where KEEP was — `docs/RETRIM.md`. GRAB/HOLD pads have a tape too (every capture lands on the SNIPS shelf) |
| CHOP | ChopShop.dc.html | chip tap = cycle class label, "YOU ✓" |
| PLAY | PlayMode.dc.html | in-window 4×4; ⟳ → fullscreen 8×2 landscape |
| SYNTH | docs/SYNTH_ROADMAP.md THUMP | 5 voices, macro sliders, SCRAMBLE, scope |
| EXPORT | ExportWizard.dc.html | preflight, format cycler, dub progress |
| ⚙ | TapeProperties.dc.html + Schemes.dc.html | live mini-preview scheme picker |
| HELP | docs/ANDROID_CAPTURE.md | 3-step onboarding + consent mock |
| Bubble | Bubble.dc.html | ring = tape fill, tap=snip, drag-down=eject (hot zone bottom, y>660) |
| PAD SHEET | TapeOS Oilslick.dc.html (`isPadSheet`) | long-press assigned pad on KIT (480 ms) — see PAD SHEET section |
| FRESH TAPE | TapeOS Oilslick.dc.html (`isFresh`, overlay on MY KITS) | F4.2 starter menu — bottom sheet over the shelf: 7 starters from the `StarterKits` registry (FACTORY/LUCKY DIP/LUCKY DIP A-B/MELODIC/CHIP/CLOUD/VELOCITY; REROLL applies to the two LUCKY DIP entries, which are the seeded ones), row = name (Rock Salt, class colour) + blurb, selected row 1px class-colour border + glow; REROLL SEED ⡆ counter + LOAD TO GRID (oil rim) → lands on KIT bank A |
| TAKES + BIN | TapeOS Oilslick.dc.html (`isTakes`) | X2.3 — entered from KIT action row. TAKES card (accent-pill header): rows T4…T2, current = NOW tag, older = RESTORE (cyan outline). BIN card (red-pill header `#6A2020`/`#C86050`): class-colour swatch + name + reason + `27D LEFT` countdown (VT323; ≤2 days amber `#FFB000`) + BACK; footer EMPTY THE BIN NOW (red outline). Copy: "EVERY SAVE ARCHIVES A TAKE. EVERY DELETE GOES TO THE BIN FIRST." |
| BANK B | TapeOS Oilslick.dc.html (KIT header + action row) | W4.3 — EVIL TWINS button fills bank B (twins of assigned bank-A pads, treatment tag top-right in accent 7px Silkscreen, colour-washed fill + 2px dashed class border); button becomes REROLL after first run (reshuffles treatments); BANK A/B label in KIT header is tappable to flip, B empty until twins run; pad sheet is bank-A only |
| Small adjusts | TapeOS Oilslick.dc.html | X1.3 CLASSIC/MELODIC segmented on CHOP (accent fill = active; strip text swaps to the melodic rule) · F5.3 KEY cycler in KIT action row (—/Am/Cm/F#m/Eb, cyan border when set) · Y3.3 `MPC SESSION (.XPJ) — KITS + GROOVES` added to the FORMAT cycler · X4.4 TEACH THE MACHINE consent row in ⚙ (off by default, green dot when on, copy: "FEATURES ONLY, NEVER AUDIO. NOTHING LEAVES THE PHONE.") · W12 mini-waveforms on assigned KIT pads (12 bars, class colour @40%, exp decay — loops decay slower; real app draws from `PeaksPyramid`) |
| GROOVE | TapeOS Oilslick.dc.html (`isGroove`) · ported to TapeOS Clear.dc.html | needle-roll, PROG A–E, swing, step editor — see GROOVE screen section |
| LOOP | TapeOS Oilslick.dc.html (`isLoop` / `isLoopLandscape`) | 6-track phasing loop grid — see LOOP screen section |
| ORBIT | Orbit.dc.html · OrbitBassRing.dc.html · OrbitSnipRing.dc.html · OrbitSet.dc.html · OrbitModes.dc.html | the circular sequencer, its own menu-row entry between GROOVE and SYNTH (and GROOVE's ORBIT ▸) — see ORBIT screen section |

## LOOP screen (Android Plan 03 · Task 6 — use these, not hardcoded grey)

6 fixed tracks (columns), each an independent chain of 1–8 blocks. Playhead per track = `interval % chain.length`; full cycle = LCM of chain lengths. Cycle readout: `L INT · L*4 BARS · mm:ss` at `bars*4*60/bpm` sec. Demo clock in prototype runs ~2× — real interval = 4 bars at set BPM.

**Track colours** (header border + block name tint):
DRUMS `#E8542E`/`#F8B09A` · BASS `#8FD424`/`#C6EC8E` · KEYS `#9A6CF0`/`#C8B2F8` · VOX `#E8409F`/`#F8A8D4` · TEX `#40E0E8`/`#8EEEF4` · PERC `#FFC41F`/`#FFE08E`
Block type tag: LOOP `#40E0E8`, PAT `#E040C8`. Muted track: everything `#40306A`, fills `#100C1A`, border `#221A34`.

**Column header** (tap = mute toggle): h24, r4, engaged = `#0A0714` fill, 1px track-colour border, glow `track@33%` 8px + inset `0 2px 6px #000`; label Silkscreen 9px in tint (muted appends " ✕").

**Block cell**: flex column, grows 30–46px, r5, pad 2×3, fill `linear(180deg, #221A34 → #161020)`, border 1px `#34285A`. Contents stacked centered: type tag (Silkscreen 7px, type colour) over name (Rock Salt 11px, tint, nowrap+clip). States — *lit* (playhead, playing, engaged): 2px type-colour border, glow 16px + inset `type@27%` 14px, name goes `#FFF`; *selected*: border `#F8A8D4` + inset ring `#E040C8`. Tap = select/inspect; tap again = pull (min 1 block per chain). "+" add slot: h24, dashed 1px `#34285A`, r5, cap 8.

**Transport (portrait 390)**: LCD header 40 (title cyan `#8EEEF4`, INT counter `#C8B2F8`); PLAY/BPM row h44 (BPM ±2 steppers 32×36 on `#221A34`); info strip h28 LCD (`#40E0E8` 15px, VT323); FULL CYCLE LCD + BOUNCE button h52 (oil-rim 2px, dark teal fill `linear(#0C2426 → #081418)`, cyan glow 20px @35%). Landscape 816×362: same parts, transport in top bar h30, cycle strip bottom h36; playback/mute/BPM state shared across rotation.

**Rebake overlay**: `rgba(10,7,20,.72)` scrim over the grid, Silkscreen `#FFE08E` + amber glow — shown while re-fitting after BPM change.

Compose notes: columns = `Row` of 6 equal `weight(1f)` `Column(spacing 4dp)`, blocks `weight(1f).heightIn(30.dp, 46.dp)`. Playhead advance on interval boundary only (blocks never cut mid-play); pull takes effect after current block finishes.

## ORBIT screen (docs/ORBITS.md)

A bar taped end to end is a ring; a longer snip taped round a bigger ring; one needle drives them all, so the inner ring comes round first. Reached from **ORBIT** on the menu row, between GROOVE and SYNTH (with no kit open the panel says `ORBIT PUTS A KIT ON RINGS. OPEN ONE FROM KITS FIRST.`), or from GROOVE's **ORBIT ▸** — on GROOVE's empty state as one of three h48 accent buttons `● RECORD` `STEPS` `ORBIT ▸` under `NOTHING HERE YET. PLAY A TAKE IN, TAP STEPS IN, OR PUT THE KIT ON RINGS.` (STEPS lands an empty one-bar base and opens the step editor on it), and beside SONG ▸ once a groove exists. Left by **◄ GRV**, which lands on GROOVE. Portrait 390 only; everything fits without scrolling for a single-pad ring.

**Header** LCD h40: ◄ GRV chip (56px, 1px ink2 border) · a ▶ / ■ chip (40px, accent while stopped — the transport lives here so it never scrolls away under a tall strip) · `ORBIT` VT323 21px lcd-ink · two VT323 14px lines right-aligned: the ratio and cycle `3:4:5 · 15 BARS` in lcd-alt (cyan; `warn` once the cycle passes the clip's 64 bars), and `BAR 2/15 · 92 BPM` in lcd-ink. The two lines are one tap target, unlabelled so a screen reader still hears the readout's values (the footer names the tap), that swaps the panel for **THE SET**: a `BAR` row of five chips `12 · 3/4` `16 · 4/4` `20 · 5/4` `24 · 6/4` `32 · 8/4` (the current one accented) then a `SWING` row of seven chips `50 · STRAIGHT` `54` `58` `62` `66` `71` `75` (the current one accented; each moves the odd 16ths of every 16th-stepped ring late by (swing − 50)/50 of a step, and the ring draws the hit where it fires), and a Silkscreen 8px ink3 line `THE BAR EVERY SPANNED RING IS MEASURED AGAINST; FREE RINGS DO NOT CARE. SWING PUSHES THE ODD 16THS LATE — 66 IS A TRIPLET FEEL — ON EVERY RING WHOSE STEP IS A 16TH. 92 BPM — HOLD BPM − / + BELOW TO RUN IT.`; CLOSE returns to the ring panel. The ratio is the rings' distinct lengths in 16ths reduced by their gcd; the cycle is their LCM with the 16-step bar (a locked ring never lengthens it).

**Rings** — an LCD panel (scanlines) the full content width, h300 (h250 while a strip of three or more rows is open), 10px inner padding, rings centred. **Shortest ring innermost**: display order is by period, then steps, then as added, so the inner ring always comes round first. Outer radius = box/2 − 14 (room for the outer label), spacing 26 (shrinks to fit past 5 rings). 12 o'clock is step 0, clockwise. Per ring: 1.25px stroke in the ring's pad class colour @70% (picked: 2.5px lcd-alt; muted or not soloed: `ink3@60%`; snip ring: LOOP blue); a 1.2px tick dot per step in `ink3@60%`; on a ring spanning two or more bars, a 1.5px line in the ring's colour `@80%` reaching 4px either side of the stroke at each bar boundary (a free ring gets none: its bar drifts); hits as class-colour dots r = 2.5 + 2.5·velocity, stepped in or out from the line by pitch (±4px) on a ring with several pads; name + size (`KICK · 16`, Silkscreen 7px, ink2 / lcd-alt when picked) just outside at 12 o'clock. **The needle** is a 3.5px lcd-ink dot with glow trailing a **comet tail one 16th of time long** — 8 arc segments thinning 3.6→0.6px and fading 75%→0 — so a fast inner ring wears a long tail and a slow outer ring a short one; a hit the needle has just passed **flares**: a halo at `class@35%` out to +9px plus the dot growing +3px, fading over 100 ms; and the panel's frame **pulses** in lcd-alt for 350 ms when every ring is back on its downbeat together. Tap a ring to pick it; **long-press to solo** (again to release). Snip rings wear their waveform as bars across the stroke in LOOP blue `#3F8CF0@55%` (`@20%` while muted): 120 buckets round the ring, each the loudest sample in that arc, scaled to the ring's own loudest, half-height up to 9px. While the tempo settles and the snips refit, `REFITTING…` (Silkscreen 7px, lcd-alt) sits in the panel's top-left corner.

**Strip** (the picked ring, unrolled) — an LCD panel under the rings: one row per pad in the ring's voice, highest at the top; a 40px rail with the pad's id in its class colour (tap = hear it); cells ≥22px wide, h26, gap 2, fill = field (beat cells raised), a hit = class colour at 45–100% by velocity, the playhead column 2px lcd-ink while playing. Tap a cell to place a hit (and hear it) or lift it; hold a cell to cycle its weight soft → normal → accent (an accented cell wears a 2px class border), or to drop an accent on an empty cell. The strip scrolls sideways past 16 steps; the ring itself is never an editing surface at phone size.

**Panel** (sunken field, r4, pad 8): ring name Michroma 11px + `PAD` / `3 PADS` / `SNIP` / `SOLO` tag · row one `−` `20 STEPS · 5 BEATS ▾` `+` `SPAN FREE` / `SPAN 2 BARS` (accent border and ink when spanned; a tap moves to the next span round FREE · ½ BAR · 1 BAR · 2 BARS · 4 BARS, a long-press swaps the panel for the five as chips under `HOW MANY BARS IS ONE TURN OF KICK?`; the step label is a tap target that swaps the panel for a picker of sizes: 3 4 5 6 7 8 9 10 12 14 16 20 24 28 32 48 64; a spanned ring's length reads as its span, `3 STEPS · 2 BARS`) · row two, scrolling: `UNDO` (ink3 while there is nothing to undo; steps back one edit, 40 deep, a BPM run counting as one) `ON|OFF` (accent when on) `SOLO` (accent while soloed) `DEL` `SPREAD` (swaps the panel for a 1–12 count picker; fills the ring's first pad with E(k, steps)) `CLEAR` `⚄ DICE` (a seeded reroll of every pad in the voice) `◀` `▶` (turn every hit one step earlier or later, wrapping) `DUP` (a copy of the ring beside it, named `KICK 2`) `REC` (accent while armed: the strip's rail then writes a hit on the ring's nearest step when tapped while playing — the rail's chips wear a 1px accent border and read `RECORD PAD A01` — and only auditions otherwise) · row three `−` `LEVEL 100` `+` `◀` `PAN C` `▶` (level in tenths to 150, pan in quarters; each readout is an unlabelled tap target that resets it to 100 or centre, so a screen reader hears the value itself; the chips carry the names `QUIETER` `LOUDER` `PAN LEFT` `PAN RIGHT`) · `PLAYS` + the kit's pads as chips (in the voice = class fill with lcd ink; carrying hits = 2px class border); tap to add or drop a pad from the voice (the last one stays). Chips min-h 36, field fill, gray-edge border, accent border+ink when lit. A snip ring's panel says what it wraps and what the fit did: `WRAPS BASS 5.WAV (5.2 S) ROUND 20 STEPS · FITS AS IS` (or `TRIMMED 1% OFF THE END`, `PADDED 2% WITH SILENCE`, `SLICED AT 12 HITS · SQUEEZED 8%` — the sliced line in lcd-alt), Silkscreen 8px ink2, up to three lines. **Tempo offer** — when a snip ring lands and its estimated tempo (confidence ≥ 0.3) differs from the set's, a second sunken field with a 1px lcd-alt border sits above the panel: `BASS 5 SOUNDS LIKE 96 BPM · THE SET IS AT 92.` over `SET 96` (accent) · `KEEP 92` · a Silkscreen 8px ink3 note `SET MOVES THE WHOLE SET AND RE-SIZES THE RING TO FIT.` Asked once; DEL on any ring or either chip clears it.

**Transport** one row of h48 buttons (field fill, r6, gray-edge; accent border+ink when lit): `↺` · `BPM −` · `BPM +` · `+ PAD` · `+ SNIP` · `OUT ▸`. `BPM −` / `BPM +` step 2 on touch and **run while held** (400 ms, then every 90 ms); taps move the readout at once and settle 400 ms before the set saves and its snips refit (a set with no snip rings takes the new tempo on the next block). `+ SNIP` swaps the panel for the SNIPS shelf as a list (`WHICH SNIP GOES ROUND A RING?`, h40 LCD rows in lcd-alt, CLOSE chip) and is disabled when the shelf is empty — a picker, never an arrow-cycle. `OUT ▸` swaps the panel for `ONE CYCLE OUT — 15 BARS` with two accent buttons, `BOUNCE ▸ TAPE` (one cycle of what is heard, rendered to a snip on the TAPE shelf) and `CLIP ▸ KIT` (one cycle as an ORBIT clip in the kit's grooves, so it rides to the MPC), or one warn-coloured line when the cycle passes 64 bars; when the set's bar is not 16, one ink2 line above them, `THE MPC COUNTS 4/4 BARS: 3.`, since the clip has no time signature. Footer Silkscreen 8px ink3, up to three lines: `TAP A RING TO PICK IT · HOLD TO SOLO · TAP A CELL FOR A HIT, HOLD IT FOR AN ACCENT · HOLD BPM TO RUN IT · TAP THE READOUT FOR THE BAR · SHORTEST RING INSIDE COMES ROUND FIRST.` **Screen reader**: the rings panel reads as one description (`RINGS, SHORTEST INSIDE: KICK, 1 BAR, PICKED, SNARE, 1 BAR, … THEY MEET EVERY 15 BARS.`), every strip cell carries its state (`EMPTY` / `SOFT HIT` / `HIT` / `ACCENT`) beside its click and long-click labels, and the symbol-only controls have names (`BACK TO GROOVE`, `BACK TO THE TOP`, `SLOWER`, `FASTER`, `ONE STEP FEWER`, `ONE STEP MORE`, `ROLL THE DICE`).

**Length** (OrbitModes.dc.html): free — as long as its steps, the same 16ths per second on every ring, so a bigger ring takes longer (16 against 20 is 4/4 against 5/4, meeting every five bars); spanning one bar — once a bar whatever its steps (a 3-step ring is a triplet against a 4-step ring's quarters, meeting every bar); spanning two bars — one turn is two laps, so a 3-step ring is three hits across eight beats and wears a heavier tick at each bar boundary.

## GROOVE screen

Captured break → four derived programs + one user fork, at fixed 92 BPM · 2 bars · 32 steps (5 lanes: KICK, SNARE, HAT C, HAT O, PERC — class colours as above).

**Programs**: A · AS CAPTURED (per-note humanize offset, reseeds via HUMANIZE) · B · SWUNG (odd steps pushed by swing amount) · C · HALF-TIME · D · SPARSE · E · EDITED (exists only after a fork). B–D are pure functions of A — never stored.

**Needle-roll**: fixed needle at y=96 (amber `warn` in OILSLICK-cyan slot; `#FF9A1A` in CLEAR), notes scroll under it at 20px/step. Note block: lane column ×5, h17 r2, opacity `0.35+0.55*vel`; lit while under needle (<0.7 step): full opacity + 10/18px class glow. Bar ticks left rail: `1.1`-style labels every 4 steps, bar starts in lcd-alt.

**Swing**: 50–75% in ±2 steps, only PROG B listens ("RIDES PROG B").

**EDIT STEPS (fork-to-E)**: tapping EDIT STEPS clones the *current* program's quantized steps into PROG E (`gUser`, `[lane, step, vel][]`), switches to E, opens a full-screen step editor over the groove screen — A–D are never mutated; re-entering edits the same E. Editor = LOOP step editor pattern at 2 bars: header `STEP EDIT — PROG E` + DONE, BAR 1/2 tabs + CLEAR BAR, 5 lanes × 16 cells (cell on = lane colour fill + 8px glow; beat cells slightly lighter; playhead column ring while playing). Tap toggles + auditions the lane voice; new notes land at vel .9 (.6 for HAT C). Footer credits the fork source. Selector shows E only when it exists (A–D cycle otherwise).

**MIDI ▸**: writes `BREAK KIT 92 A–D.MID` (toast mock).

CLEAR mapping: LCDs stay dark (`#101418`, border `#8A96A2`); lcd-hi `#A8ECFF`, readouts `#58C8E8`, needle amber `#FF9A1A`; light raised buttons `#F6F8FB→#E8ECF2` border `#B6C0CC`; editor overlay dark with cells `#161C22`/`#1E262E`, borders `#2E3A44`.

## PAD SHEET (full-screen inspector — wireframe 1d)

Entry: long-press (480 ms) any assigned pad on KIT; tap still triggers the voice (press fires the hit immediately, the timer opens the sheet). Unassigned pads never open it.

Layout top→bottom: header row (◄ KIT 64px · LCD `PAD A02` + class chip in pad colour · ▶ HIT 64px, 2px pad-colour border + glow); waveform LCD h64 (44 bars, exp-decay envelope in pad colour — loops decay slower; W12 draws these from `PeaksPyramid` for real); provenance line (Y1.3 `source`: file @ time · length · bin status); three stepper-sliders LEVEL −24..+6 dB / PAN L50..R50 / TUNE ±12 st (fill bar in pad colour, VT323 readout); toggle row ONE-SHOT · CHOKE GRP 1 · GHOSTS (W5.3 — engaged = pad-colour fill; hats default choke ON per KIT_BEST_PRACTICES); TREATMENT card (Y4.3): NONE/CRUSH/TAPE/DIRT segmented + AMT 0-100 in 5s, picking a treatment auditions the pad; action row RE-TRIM ▸ (→ TAPE opened on the pad's own snip with IN/OUT on its cut, header `RE-TRIM A02 · <file>`, primary button BACK ONTO A02 — `docs/RETRIM.md`; a pad with no tape toasts why) · MAKE INSTRUMENT (W2.3 — enabled styling only on tonal pads, refusal toast otherwise) · EJECT → BIN (X2, red border `#6A2020`/`#C86050`); prev/next pad nav cycling assigned pads only.

Per-pad state is a prefs map keyed by pad id — level/pan/tune/oneShot/choke/ghosts/treat/amt — defaults: −2 dB, C, 0 st, one-shot ON, choke ON for hats, ghosts OFF, NONE @ 35%.

Toasts: "GHOST LAYERS ON. QUIET HITS GO SOFT, NOT JUST QUIETER." · "CRUSH ON A02. ORIGINAL SLEEPS IN THE BIN." · "ONE NOTE IN, WHOLE KEYBOARD OUT. INSTRUMENT ON THE SHELF." · "NO CONFIDENT PITCH. THE MACHINE REFUSES POLITELY." · "EJECTED. THE BIN KEEPS IT 30 DAYS."

## Personality (PERSONALITY.md compliance)

One gag per screen, OFF/MILD/FULL prop. Toasts 2.6s, oil-rim card. Quips rotate 6s in status bar (FULL only).
Shipped copy adds: "MELODIC. THE PADS BECOME A SCALE, LOW LEFT." · "Am SET. TONAL PADS RETUNE ON ASSIGN — THE KICK IS UNTOUCHED." · "KEY OFF. EVERYTHING LANDS AS CAPTURED." · "TEACHING ON. THE MACHINE LEARNS FROM YOUR CORRECTIONS." · "TEACHING OFF. THE MACHINE STOPS TAKING NOTES." · "BANK B LIT. YOUR KIT, BUT EVIL. RECIPES KEPT." · "TWINS REROLLED. SAME SEED, DIFFERENT SINS." · "T3 RESTORED. THE PAST, REPLAYED." · "BACK FROM THE BIN. NO QUESTIONS ASKED." · "BIN EMPTIED. THE MACHINE FORGETS, AS ASKED." · "HUMANIZED. NOBODY PLAYS LIKE A ROBOT." · "FORKED TO PROG E. A–D STAY UNTOUCHED." · "BAR WIPED. THE MACHINE FORGIVES."
Shipped copy: "TAPE ROLLING. GO STEAL A SOUND (LEGALLY)." · "TAPED. NO TAKEBACKS." · "IT'S OURS NOW." · "CLEAN CUT. NICE EARS." · "SNIP! LAST 60s KEPT." · "EJECTED. TAPE IS KEPT." · "PENCIL REWIND. OLD SCHOOL." · "DUB DONE. SOUNDS 3% WARMER NOW." · "FRESH TAPE. SMELLS LIKE FERRIC OXIDE." · "RE-CHOPPED. THE MACHINE APOLOGIZES FOR SLICE 3." · "SNACK BAR. SHIPPED OUT OF RESPECT."

## Motion

Reels spin 1.2s linear while playing. Toast: 250ms ease-out rise+fade. Pad hit: 180ms glow decay. Bubble drag: scale 1.08. Dub progress: 180ms/file × 24. No other animation — TapeOS is snappy, not springy.
