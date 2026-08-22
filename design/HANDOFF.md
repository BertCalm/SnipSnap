# TapeOS OILSLICK — Android handoff

Scheme for SnipSnap's TapeOS shell. Prototype: `TapeOS Oilslick.dc.html`.
Static artboard tokens: `Schemes.dc.html` (`.t-oilslick`), picker row in `TapeProperties.dc.html`.

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
| Handwriting | Permanent Marker | 13-15px, pad + cassette labels |

Android: VT323/Silkscreen/Permanent Marker on Google Fonts; Michroma too. Sizes are dp at 390dp width.

## Layout constants

Phone frame 390×844, outer margin 12. Titlebar 34 (r4). Menu row 26, items pad 3×4 — 9 items max. Status bar 26, 3 cells (fixed, fixed, flex-quip). LCD header 40-44. Pad grid 4×4, gap 8, pad h 76, r6. Primary action h 52-58: 2px OILSLICK rim + dark fill. Hit targets ≥44.

## Screens in prototype (map to repo modules)

| Screen | Repo source | Notes |
|---|---|---|
| KITS | (new) | tape shelf, status ON CARD/DRAFT |
| KIT | Main.dc.html | pads WebAudio-triggered, arm session |
| TAPE | TapeDeck.dc.html + prototype/tapedeck.html | drag-under-fixed-needle, zoom 1-4×, snap-to-onset, pencil rewind gag |
| CHOP | ChopShop.dc.html | chip tap = cycle class label, "YOU ✓" |
| PLAY | PlayMode.dc.html | in-window 4×4; ⟳ → fullscreen 8×2 landscape |
| SYNTH | docs/SYNTH_ROADMAP.md THUMP | 5 voices, macro sliders, SCRAMBLE, scope |
| EXPORT | ExportWizard.dc.html | preflight, format cycler, dub progress |
| ⚙ | TapeProperties.dc.html + Schemes.dc.html | live mini-preview scheme picker |
| HELP | docs/ANDROID_CAPTURE.md | 3-step onboarding + consent mock |
| Bubble | Bubble.dc.html | ring = tape fill, tap=snip, drag-down=eject (hot zone bottom, y>660) |

## Personality (PERSONALITY.md compliance)

One gag per screen, OFF/MILD/FULL prop. Toasts 2.6s, oil-rim card. Quips rotate 6s in status bar (FULL only).
Shipped copy: "TAPE ROLLING. GO STEAL A SOUND (LEGALLY)." · "TAPED. NO TAKEBACKS." · "IT'S OURS NOW." · "CLEAN CUT. NICE EARS." · "SNIP! LAST 60s KEPT." · "EJECTED. TAPE IS KEPT." · "PENCIL REWIND. OLD SCHOOL." · "DUB DONE. SOUNDS 3% WARMER NOW." · "FRESH TAPE. SMELLS LIKE FERRIC OXIDE." · "RE-CHOPPED. THE MACHINE APOLOGIZES FOR SLICE 3." · "SNACK BAR. SHIPPED OUT OF RESPECT."

## Motion

Reels spin 1.2s linear while playing. Toast: 250ms ease-out rise+fade. Pad hit: 180ms glow decay. Bubble drag: scale 1.08. Dub progress: 180ms/file × 24. No other animation — TapeOS is snappy, not springy.
