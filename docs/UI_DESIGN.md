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

## Rules of the language

- Every control is one of three bevels; no flat buttons, no rounded-corner
  cards, no drop shadows outside the LCD glow.
- Icons are drawn (inline SVG in the 90s chunky style) — never emoji.
- Monospaced LCD numerals for all times; they must not jitter.
- Group boxes with pixel-font legends replace "cards".
- One signature animation: the snip's cassette flying onto the shelf.
  Reels spin whenever audio moves. Everything else is instant.
- Error dialogs are honest little message boxes ("SPOTIFY BLOCKS THE TAPE —
  USE THE SCREEN RECORDER, I'LL PULL THE AUDIO OUT").

## Still open

- Exact MPC 16-colour pad palette mapping (needs the colour encoding answer
  from the golden-file work — packed RGB int in MPC 3 makes this easy).
- Whether the desktop metaphor extends to a "My Kits" file-manager screen or
  kits stay a menu.
- Landscape play mode layout.
