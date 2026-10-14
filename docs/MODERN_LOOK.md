# A second look for TapeOS — the OpenSky proposal

*Status: proposal, 2026-10-13. Nothing in `:shell` or `:app` changes with this
document. The side-by-side mockup is [`../design/ModernLook.html`](../design/ModernLook.html).*

> **Decided, later the same day — see [`DIRECTION_2026_10.md`](DIRECTION_2026_10.md).**
> The modern look is the direction, not a second class beside the first; the
> OG look stays as CLASSIC. A dark colourway, VENT, joins OPENSKY and passes
> every scheme law as written, so it ships first. The questions in §6 are
> answered there. The token table and the build order below still hold.

The OpenSky audition page (Round 3, October 2026) has a visual language of its
own: paper, one orange, hairlines instead of edges, a serif italic for the
words that matter, a monospace face for the words the machine says, and a dark
player dock at the foot of a light page. This document asks whether that
language can become a **class of SnipSnap schemes** beside the OG ones, and
what it would cost.

Short answer: yes, in two steps. The colours fit the existing token table
almost exactly. The *drawing* does not, and that is the part the current system
has no slot for.

## 1. What a scheme is today, and what it is not

[`UI_DESIGN.md`](UI_DESIGN.md#schemes-themes-the-win95-way) states the rule:
*a scheme is a token swap and nothing else — same bevels, same layout, same
type.* The code holds to it:

- `shell/.../Schemes.kt` — `Scheme` is a table of packed colours
  (`gray`, `grayHi` … `lcd`, `lcdInk`, `amber`, `accent`, `deskGlow`). Eight
  rows, all dark, `OILSLICK` by default.
- `app/.../theme/Bevel.kt` — the *surface language* as modifiers:
  `raisedBevel`, `pressedBevel`, `sunkenField`, `lcdPanel` (with scanlines),
  `windowFrame`, `rememberDeskBrush` (the 3px checkerboard). These read colours
  from the scheme but decide the *shape* themselves: 2dp gradient bevels,
  1px-in-3 scanlines, a dithered desk.
- `app/.../theme/TapeTheme.kt` — `TapeFonts` and `TapeType`, one global set of
  four faces (VT323, Silkscreen, Michroma, Rock Salt). Not per scheme.

So the OpenSky page splits cleanly into two halves:

| Half | Fits today? | Where it lives |
|---|---|---|
| Colours (paper, ink, orange, card, line) | **Yes**, as a ninth `Scheme` row | `Schemes.kt` |
| Drawing (hairlines, flat fills, no dither, system faces) | **No**, there is no axis for it | `Bevel.kt`, `TapeTheme.kt` |

The proposal is therefore **two axes**: the existing *scheme* (colour) and a
new *look* (surface and type). OG schemes keep the OG look. OPENSKY is the
first scheme in the MODERN look.

## 2. The OPENSKY token table

Every value below is lifted from the audition page's `:root` and component
rules, then mapped onto the slot that does the same job in TapeOS. Ratios are
WCAG contrast, computed with `Scheme.contrastRatio`.

| Token | Value | From the page | Note |
|---|---|---|---|
| `gray` | `#F8F3E9` | `--paper` | chrome body; luma 243 |
| `grayHi` | `#FFFFFF` | card highlight | unused by the modern look's flat surfaces, kept for the schema |
| `grayEdge` | `#DBD5C7` | `--line` | every hairline |
| `grayMid` | `#EEE6D8` | tab hover | pressed / hover fill |
| `grayDark` | `#B4AD9E` | `.clip-play` border | the darker rule |
| `ink` / `ink2` / `ink3` | `#292820` / `#69675A` / `#817A6B` | `--ink` / `--muted` / placeholder | 13.4 : 1, 5.2 : 1, 3.9 : 1 on `gray` |
| `title1` = `title2` | `#F8F3E9` | masthead | a flat bar; the modern look draws a hairline under it |
| `titleInk` | `#292820` | wordmark | |
| `desk1` / `desk2` | `#EFE8DA` / `#F3EDDF` | page ground | the modern look draws them flat, not as a checker |
| `deskGlow` | `#FFD350` | the sun | the radial behind the window **is** the sun |
| `lcd` / `lcdInk` | `#22211A` / `#F8F3E9` | `.player-dock` | the dock is `#292820`; one step darker here so `lcd` sits inside the dark-surface test with margin |
| `amber` | `#FF8C00` | `--orange` | 6.9 : 1 on `lcd`, **2.1 : 1 on `gray`** (see §4) |
| `warn` | `#FF8C00` | `.player-light.active` | warm, as the needle test requires |
| `field` / `raised` | `#FFFAF1` | `--card` | lists, inputs, pads |
| `accent` | `#A74C00` | `--deep-orange` | 5.5 : 1 on `gray`; 4.1 : 1 against the pressed fill |
| `winFrame` | `#B4AD9E` | `.clip-play` border | drawn 1dp in the modern look, not 3dp |

What stays fixed, exactly as `UI_DESIGN.md` lists it: the eight class colours
(kick red is kick red on paper too), record red, the dark LCD, every `Layout`
constant, every 48dp target.

## 3. The MODERN look, element by element

How the same tokens are drawn when `look == MODERN`. The right-hand column is
the audition-page rule it comes from.

| TapeOS element | OG look | MODERN look | Page source |
|---|---|---|---|
| Raised surface (button, empty pad) | 2dp light-from-top-left bevel on `gray` | `raised` fill, 1dp `grayEdge` line, 2dp radius | `.sound-card`, `.test-tab` |
| Pressed / selected | bevel inverted, 3dp accent ring | `ink` fill with `gray` text | `.test-tab[aria-pressed=true]`, `.decision-button` |
| Sunken field (list, input) | 1dp inverted bevel on `field` | `field` fill, 1dp line, no inset | `.note-card textarea` |
| Primary action | OILSLICK rim, dark fill | `ink` fill, `gray` text, square corners | `.primary-button` |
| Window frame | 3dp `winFrame` (OILSLICK: the sweep) | 1dp `winFrame` | `.sound-card` border |
| Title bar | `title1 → title2` gradient | flat `gray`, hairline below, heavy wordmark with the tilted square | `.masthead`, `.wordmark-square` |
| Desk | 3px `desk1`/`desk2` checker + `deskGlow` radial | flat `desk1` + `deskGlow` radial (the sun) + faint ray wedges | `.sun-disc`, `.sun-rays` |
| LCD | `lcd` fill, 1px-in-3 scanline at 30 % | same fill, same stripe at 18 % | `.sun-disc::after` (2px-in-3 at 18 %) |
| Assigned pad | 2dp class border + 14px glow | class colour as a 4dp bottom edge, no glow | PlayMode's "colour as bottom edge" |
| Groove / separator | 1dp dark + 1dp light notch | 1dp `grayEdge` | `.voice-strip` borders |

**Type.** The modern look uses no downloaded faces, which matches how the page
ships. All four come from the system, through Compose's `FontFamily.SansSerif`,
`FontFamily.Serif` and `FontFamily.Monospace`:

| Slot | OG | MODERN | Page source |
|---|---|---|---|
| `TapeFonts.Lcd` | VT323 | serif italic (readouts in mono) | `h2`, `.sound-card-header h4` |
| `TapeFonts.Pixel` | Silkscreen | mono, +1 tracking, upper-case | `.eyebrow`, `.clip-meta` |
| `TapeFonts.Display` | Michroma | sans, weight 600 to 900 | `.primary-button`, `.wordmark` |
| `TapeFonts.Marker` | Rock Salt | serif italic | `.hero-tagline`, `.macro-list dd` |

The last row is the one that keeps the metaphor honest. TapeOS sets everything
a person named in handwriting. The modern look sets everything a person named
in italic, and nothing else in italic, so "the user's words look different from
the machine's" survives the change of face.

## 4. The three tests that would fail, and why that is the real decision

`SchemesTest` and `ContrastTest` are the design system's laws. OPENSKY passes
all of them except three, and all three fail for the same reason: they assume
chrome is dark.

1. **`every scheme is dark now - the light shells are cut`** asserts
   `luma(gray) < 90`. OPENSKY's `gray` is 243. This test *is* the August
   decision (`DESIGN_GAP.md` §2f, 2026-08-28: "CLEAR is cut. Every light shell
   goes with it"). A paper scheme reopens it, and should do so on purpose: the
   proposal is not "bring CLEAR back" (a pale bevelled shell) but "a printed
   look that happens to be light". If the answer is still no, the modern look
   can only ship in dark colourways, which is a smaller and still real idea.
2. **`ink2 clears AA against the darker filled-pad backdrop`** computes the
   backdrop as `darken(gray, 0.30)`. Darkening paper gives a mid-tone that
   `ink2` cannot read on (2.5 : 1). On a light scheme the filled-pad backdrop
   should be `field`, not a darkened `gray`. The modern look draws it that way;
   the test needs to ask the look which backdrop it uses.
3. **`BIN_RED_GLOW clears AA against every scheme's chrome gray`** —
   `BIN_RED_GLOW` is a scheme-independent constant tuned to read on dark chrome.
   On paper it is 2.5 : 1. The page's own error colour (`#813600`, 7.7 : 1 on
   paper) is the right value here. The fix is to make it a per-scheme `bin`
   token with today's constant as the default, which also honours the comment
   on that constant: destructive text must read as red in every scheme.

Two more things the numbers flag that no test catches:

- **`amber` on chrome.** 118 call sites read `scheme.amber`; most draw on the
  LCD, where orange reads at 6.9 : 1. A few draw on chrome (the HELP ▸ button,
  a toast's door label). On paper those are 2.1 : 1. Those sites should read
  `accent` when the look is modern, or the modern look needs an `amberOnChrome`
  rule. They need to be found by hand; the grep cannot tell a surface from a
  colour.
- **`ink3` is 3.9 : 1 on paper.** OILSLICK's `ink3` is already a measured,
  documented AA gap (2.1 : 1) for the same reason: the third tier exists to be
  dimmer than the second. OPENSKY's gap is smaller than the default scheme's.

## 5. How it would be built

In order of how much they change, smallest first. Each step compiles and tests
on its own.

1. **`Look` on `Scheme`.** `enum class Look { OG, MODERN }` and a
   `val look: Look = Look.OG` field on `Scheme`. Every existing row is
   unchanged. `Schemes.ALL` gains `OPENSKY`. The three tests in §4 learn to ask
   `scheme.look` before applying the dark-chrome laws; the other laws (two
   surfaces, warm `warn`, ink tiers, accent separation) apply to both looks
   unchanged. The ink-tier test already carries a light-scheme branch.
2. **`Bevel.kt` branches on the look.** Each modifier keeps its name and
   signature and draws the modern form when `scheme.look == MODERN`. 99 call
   sites of `raisedBevel`, 58 of `sunkenField`, 124 of `lcdPanel` and 26 of
   `pressedBevel` need no edit; they already pass the scheme in. This is the
   same shape `windowFrame` already has for OILSLICK's sweep.
3. **Type follows the look with no call-site churn.** `TapeType`'s styles are
   read at 526 sites as `TapeType.pixel` and friends. Rather than threading a
   new parameter through all of them, make each style a Compose-readable
   property: `val pixel: TextStyle @Composable get() = LocalLook.current.pixel`.
   Every call inside a composable keeps compiling as written. The handful of
   reads outside composition (inside a `remember` or a draw lambda) will fail
   to compile and name themselves. This needs an Android SDK to prove; a cloud
   session cannot build `:app` (see `.claude/skills/steward/SKILL.md`).
4. **The picker.** `PropertiesScreen` lists `Schemes.ALL` as one column. It
   should list two groups under pixel legends, `OG` and `MODERN`, in the
   picker order the design tells its story in. Persistence needs nothing:
   `PREF_SCHEME` stores the `SchemeId` name and `OPENSKY` is just one more.
5. **The artboard.** `design/Schemes.dc.html` is the source of truth for
   token values; OPENSKY's row goes there too, so `SchemesTest`'s
   "matches the handoff" habit has something to match.

Not in scope, and not needed for the first cut: the five OILSLICK special
cases in `Chrome.kt`, `KitsScreen.kt` and `PadCaptureScreen.kt` (they test
`scheme.id == OILSLICK` and fall through to the plain branch for everything
else, OPENSKY included).

## 6. Questions before any of this is built

1. **One scheme or a family?** The page gives one palette. "A new class of
   themes" suggests more than one colourway. A dusk variant (paper → deep
   blue-gray, sun → a low orange) and a dark-paper variant (ink ground, paper
   text, same hairlines) are the obvious two, and they are design work, not
   code work. The dark-paper one would also pass the current dark-chrome laws
   as they stand.
2. **Surfaces only, or surfaces and type?** Steps 1, 2, 4 and 5 give a
   complete modern look in the OG faces. Step 3 is where most of the risk and
   most of the character is. It can ship second.
3. **Is the August cut reopened?** §4.1. This is the one decision that is not
   a matter of taste or cost.
4. **Does PLAY mode change at all?** It is all LCD, no chrome, so under the
   two-surface rule it should look nearly the same in both looks. If the answer
   is "it should feel modern too", that is a third axis and a different
   document.
