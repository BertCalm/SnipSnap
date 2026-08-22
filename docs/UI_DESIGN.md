# UI design — TapeOS

The visual language and the interaction decisions behind it. Mockups live as
editable artboards in [`../design/`](../design/) (one `.dc.html` per screen),
published as the **SnipSnap TapeOS** design canvas.

## Settled decisions

| Decision | Call |
|---|---|
| Capture bubble | **One tap = snip, nothing else.** Trim always happens later, in comfort. The bubble never opens a screen — during a session, the bubble *is* the app. |
| Play mode | **Early build**, not a nice-to-have. If the kit doesn't feel good under fingers in the app, it won't feel good on the MPC. Latency work (Oboe) lands with it. |
| Visual language | **90s desktop quirk × cassette tape** — "TapeOS". |

## Why the cassette works

The metaphor is not a costume. The ring buffer *is* a tape loop, so the
interface can tell the truth:

| Object | Meaning |
|---|---|
| Tape loop / reels | the rolling 60 s capture buffer; reels spin while armed |
| Bubble progress ring | how much tape is behind you |
| A snip | a labelled mini-cassette on the shelf |
| Shell colour | drum class (dashed shell = unclassified) |
| Handwritten label (marker font) | the name — everything user-named looks hand-written |
| A kit | a mixtape; export is **dubbing** |

## The two-surface rule

Everything in the app is one of two surfaces, and the split is period-correct
(think dark Winamp sitting on a gray desktop):

1. **Gray bevels = tools.** Windows, wizards, lists, buttons. Light chrome,
   2 px bevels, four shades. Every control is RAISED, PRESSED or SUNKEN —
   pressing a control flips its light source.
2. **Dark LCD = sound.** Anything that plays or displays audio: waveforms,
   counters, the whole of play mode. Green ink (`#49e83e`) on near-black
   (`#0c130b`), amber (`#ffb000`) for the needle and selections, scanlines.
   This is also what makes the performance surface dark-room/OLED friendly
   without breaking the aesthetic.

## Tokens (see the TapeOS sheet artboard)

- **Chrome:** `#c3c7cb` gray, hi `#ffffff`, mid `#868a8e`, dark `#3f4347`;
  title bars `#000082 → #1878c8`; desktop teal `#0a7a78` — always dithered
  (4 px checker), never flat.
- **LCD:** bg `#0c130b`, ink `#49e83e`, needle/selection `#ffb000`,
  record red `#e83a2e`, tape orange `#ff7a1a`.
- **Class colours** (shell = pad = MPC pad colour on export — one language):
  kick `#e8542e`, snare `#ffc41f`, clap `#e8409f`, hat-cl `#1fc6cf`,
  hat-op `#7adfe4`, tom `#9a6cf0`, perc `#8fd424`, tonal `#b06cf0`,
  loop `#3f8cf0`.
- **Type, four voices:** Verdana ~12 px for window text; Silkscreen (pixel)
  for pad IDs/status/hints; VT323 for every LCD numeral; Permanent Marker for
  anything hand-named. Nothing else.
- **Scale honestly:** it's Win9x-*inspired*, not pixel-accurate — bevels and
  type are scaled up so every hit target stays ≥ 44 px (pads are 76 px+).

## Screens (one artboard each)

- **Kit Builder (Main)** — SNIPSNAP.EXE window on the teal desktop: LCD kit
  plaque, 4×4 bevelled grid (bottom row = kick/snare/hats, per the
  finger-drumming layout `AutoPlace` targets), SNIP SHELF group box of
  mini-cassettes with a real Win9x scrollbar, big record button, status bar.
- **Tape Deck (trim)** — cassette up top with spinning reels; waveform LCD
  where **the audio drags and the needle stays put** (precision without tiny
  handles); onset ticks from `Transients` as snap targets; chunky transport;
  COMMIT SNIP.
- **Chop Shop** — sliced waveform with amber cut lines; a list view of slices
  with mini-waves and class chips; **low confidence looks unsure** (dashed
  gray chip, "NOT SURE") instead of confidently wrong; auto-place preview LCD;
  SEND TO GRID.
- **Play Mode** — all LCD, no chrome: full-screen dark 4×4, colour as bottom
  edge + glow on trigger, BPM readout. Minimal by design; latency is the
  feature.
- **Export Wizard** — the InstallShield-style wizard is a perfect fit for
  preflight: check rows (drawn SVG check/warning), format + destination
  combos, segmented blue progress ("DUBBING…"), and the exact written path
  shown on success.
- **Bubble** — four states over someone else's app: ARMED (partial ring),
  TAPE FULL, SNIP! (flash + count badge + heavy haptic), DRAG DOWN = EJECT.

## Schemes (themes, the Win95 way)

TapeOS themes exactly like the classic Appearance control panel: a scheme is a
**token swap and nothing else** — same bevels, same layout, same type. Because
every control is built from the token set, a theme is one small table, and a
"custom scheme" feature (Save As…) falls out for free.

Named after tape formulations:

| Scheme | Chrome | Desktop | LCD ink | Character |
|---|---|---|---|---|
| **Chrome** (Type II, default) | `#c3c7cb` gray | teal `#0a7a78` | green `#49e83e` | the classic |
| **Ferric** (Type I) | tan `#d4c8a8` | rust `#8a5a24` | amber `#ffb000` | 70s glovebox |
| **Metal** (Type IV) | near-black `#2e3136` | `#101215` | ice `#7adfe4` | high-contrast dark; the 2am scheme |
| **Snack Bar** | yellow `#ffd400` | red `#e81c1c` | yellow | the Hot-Dog-Stand homage, shipped out of respect |
| **Oilslick** (Type ∞) | violet `#221a34` | deep violet `#0c0618` | lavender `#c8b2f8` | Y2K iridescence; cyan in the amber slot |

**Oilslick** goes one token further than the others: the window frame and
titlebar swap the bevel highlight/shadow pair for the OILSLICK conic gradient
(`from 210deg`: `#5a2ae0 → #e040c8 → #40e0e8 → #8a5af0 → #5a2ae0`), which also
rims primary buttons and fills progress bars. On Android that's a Compose
`Brush.sweepGradient`, with a linear three-stop fallback below API 33. It has
its own fully working phone-frame prototype —
[`../design/TapeOS Oilslick.dc.html`](../design/TapeOS%20Oilslick.dc.html):
boot splash, all nine screens, WebAudio pads, capture bubble, scheme picker —
and an Android handoff with the complete token table in
[`../design/HANDOFF.md`](../design/HANDOFF.md).

Full token sets live in the theme classes in
[`../design/Main.dc.html`](../design/Main.dc.html) (`--gray/--g-*`, `--ink/--ink2`,
`--t1/--t2/--title-ink`, `--desk1/--desk2`, `--lcd/--lcd-ink/--amber`,
`--field`).

**Fixed in every scheme** (not tokens):

- the nine **class colours** — pad colour is the shared language with the MPC,
  so kick red is kick red in every scheme
- **record red** `#e83a2e` and the cassette shell/label anatomy
- LCD contrast: ink hue may change per scheme, but sound surfaces stay dark

Mockups: the **Schemes** artboard shows all five side by side; **Tape
Properties** is the picker — the Display-Properties-style dialog with the CRT
preview monitor, scheme list with swatch chips, Save As…/Apply. The **Main**
artboard carries a live `Scheme` tweak on the canvas, so the real screen can be
flipped between all five.

In the app this is a Compose theme object holding the token table; scheme
choice persists per user, and Metal doubles as the dark-room default if the
system asks for dark.

## Rules of the language

- Every control is one of three bevels; no flat buttons, no rounded-corner
  cards, no drop shadows outside the LCD glow.
- Icons are drawn (inline SVG in the 90s chunky style) — never emoji.
- Monospaced LCD numerals for all times; they must not jitter.
- Group boxes with pixel-font legends replace "cards".
- One signature animation: the snip's cassette flying onto the shelf.
  Reels spin whenever audio moves. Everything else is instant.
- Quirk is governed: see [`PERSONALITY.md`](PERSONALITY.md) — plausible-in-1996 only, never in the signal path, never gating function, one visible gag per screen.
- Error dialogs are honest little message boxes ("SPOTIFY BLOCKS THE TAPE —
  USE THE SCREEN RECORDER, I'LL PULL THE AUDIO OUT").

## Settled since: keys on the grid

Melodic content (keygroups, synth patches) plays on the **same 4×4 grid** —
no piano keyboard UI. Root on A01, CHROMATIC and SCALE layouts, banks as
octaves. Consistent with the MPC's own Pad Perform, so hands learn one
surface. Details in [`SYNTH_ROADMAP.md`](SYNTH_ROADMAP.md#the-play-surface-stays-44--settled).

## Settled since: pad colours travel

The colour encoding question is answered — there is no 16-colour palette to
map to. MPC 2 XPM carries free 24-bit RGB per pad (packed `0xRRGGBB` in the
ProgramPads blob, decoded from commercial packs — see
[`XPM_STRUCTURE.md`](XPM_STRUCTURE.md#the-programpads-blob)), and the
exporters now write the class colours straight through: kick red is kick red
on the hardware pads too, exactly as "shell colour = pad colour = MPC pad
colour" promised.

## Still open

- Whether the desktop metaphor extends to a "My Kits" file-manager screen or
  kits stay a menu.
- Landscape play mode layout.
- Custom scheme editor (Save As… exists in the picker mock; the token table
  makes it cheap, but it's not MVP).
