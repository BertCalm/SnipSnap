# The SnipSnap mark, the tile art family, and expansions on the phone

Owner brainstorm, 2026-10-31, after playing the first SnipSnap XPNs on an
MPC Live III. Three parts, built in this order; each is useful on its own.

## Intent

An expansion's tile is the first thing a user sees in the MPC browser,
next to Akai's own. Today SnipSnap draws one (four styles, pixel-font
caption, waveform by default, no choice on the phone), and it reads as a
diagram. The owner wants tiles that are **abstract but pleasing**, drawn
from the kit's own sound, chosen by the user with a few knobs, carrying a
mark that says it came from SnipSnap. That mark does not exist yet: the
launcher icon is a stock Android glyph and the title bar's wordmark is a
tilted square before SNIPSNAP. And the moment of choosing the art should
be a moment of delight early in a kit's life, not a setting buried in
export, which means the phone needs the idea of an expansion that kits
join.

## Goals

- A SnipSnap mark: a symbol, a one-line lockup, a stacked lockup.
- Eight tile styles that feel like one family: the four new ones from the
  brainstorm and the four existing ones redrawn with the same energy.
- A small set of knobs: style, palette, seed sample, mark variant.
- Expansions as named collections on the phone, born from the shelf,
  with their art chosen at birth and changeable at export.
- The tile renders identically in intent on the phone and on the JVM
  (CLI and tests); the owner's prototyping loop (`snipsnap art`) keeps
  working with every new style.

## Non-goals

- Importing photos or hand-made art (the packager keeps accepting
  caller-supplied PNG bytes; nothing here removes that).
- Animated or per-program art inside an expansion (an XPN has one tile).
- Reading expansions back off the card into the phone's model.
- Round robin, which is its own brainstorm.
- Changing the J-Card beyond swapping in the new mark.

## Anti-goals (what would make this a failure even if it works)

- A tile the name cannot be read on. Every palette and style must keep
  the kit name legible at 128 px; a result that needs luck is not a style.
- Tiles that look like the current ones with a new coat: if a style is a
  bar chart or a grid of boxes, it has not been redrawn.
- Export churn: the same expansion, same knobs, same platform must
  produce the same PNG bytes (re-exporting must not change a card).
- A new typed-name dialog anywhere but the one place expansions are born.
- Anything that touches the MPC program formats. This project writes PNG
  bytes and an `Expansion.xml` that already exists.

## Part 1 · The mark

Decided by the owner on screen mockups:

- **Symbol:** four diamonds (squares on point) in a 2×2, touching at the
  corners so the cluster reads as an x. The diagonal pair (top-left,
  bottom-right) is the accent colour; the other pair is the ink. Drawn
  from points on a grid of the diamond's own diagonal, never as rotated
  rectangles (that overlapped).
- **One-line lockup:** SNIP · symbol · SNAP, Unbounded ExtraBold, SNIP in
  ink, SNAP in accent, the symbol at cap height between the words.
- **Stacked lockup:** SNIP over SNAP, both set with the face's natural
  spacing and flush left so S stacks on S and N on N; the symbol to the
  right, exactly as tall as the block from SNIP's cap top to SNAP's
  baseline, a small fixed gap after the wider word. This is the owner's
  preferred form for tiles.
- The symbol alone is the **app icon** (adaptive icon: on the dark LCD
  ground by default; the accent pair swaps to ink on an accent ground so
  contrast holds).
- The one-line lockup replaces the title bar's tilted square + SNIPSNAP.
  It keeps the accessible name "SNIPSNAP" (my call), so TalkBack and the
  existing title-bar tests see what they saw.

My calls:

- Colours come from the active scheme (`Scheme.ink`, `Scheme.accent`),
  not a fixed orange; the mock-ups' orange was the tape accent. On a tile
  the mark takes the palette's ink and accent (Part 2).
- The mark is one Kotlin drawing routine over the art canvas (Part 2),
  so the icon, the title bar, the tile and the J-Card all draw the same
  geometry; the launcher icon is generated from it at build time or
  committed as a rendered asset (builder's choice, say which).
- Unbounded ExtraBold ships as a bundled OFL font file used by both the
  phone and the JVM renderer. The title bar may use the Compose text
  stack with the same font file.

## Part 2 · The tile art family

### Styles

Eight, one family, all drawn from the kit's own audio and the kit name.
The four from the brainstorm (shown and liked):

- **Bloom** — the seed sample's loudness envelope wrapped around a circle,
  filled with a two-stop gradient, a dark hole in the middle where the
  name sits in condensed caps.
- **Ridges** — the attack sliced into stacked ridge lines that fade back,
  each line's shape from a later window of the sample; name top-left in
  an italic serif.
- **Field** — a dot grid whose dot size and opacity follow the envelope;
  the name in a wide geometric face across the middle on a soft band.
- **Ribbon** — the attack window as one smooth thick stroke with a glow,
  two-tone gradient, name in a wide-tracked grotesk below.

The four existing styles, **kept by name and redrawn** so they belong to
the same family (the owner: "played upon to make them more dynamic and
interesting like the other four"). What each must keep is its idea, not
its drawing:

- **Waveform** — still "all the pads end to end", but as one continuous
  flowing form (a river, not a strip of rectangles), class colours
  blending along it.
- **Grid** — still the 4×4 bank, but each cell is a small abstract drawn
  from that pad's own sound (its envelope as a bloom or a ridge), lit by
  class colour, not a lit box.
- **Slices** — still one shape per pad sized by length, but as nested or
  radiating forms rather than a bar chart.
- **Rings** — still seeded arcs per pad, but with weight, glow and overlap
  so it reads as a sound rather than a chart.

The pixel-font caption goes. The kit name is part of the picture in every
style, in a face that contrasts the field. The 5×7 font stays in the
codebase for anything else that uses it.

### Knobs

- **Style** (eight).
- **Palette**: a reviewed list of named pairings (ground, field colours,
  ink), not a free colour wheel. Each palette guarantees name legibility
  over every style (the anti-goal above is tested, not hoped). My call:
  start with six to eight palettes, including one per existing scheme
  family so a VENT user gets a VENT-looking tile by default.
- **Seed sample**: which pad's sound draws the field. Default is the
  kit's longest pad (most to draw); the user can tap another pad. For an
  expansion the seed is a kit and a pad; every style, including the
  per-pad ones (Waveform, Grid, Slices, Rings), draws from the seed kit
  alone (my call: an expansion's art is "one kit's sound", which keeps
  the picker fast and the result readable; aggregating six kits into one
  Grid was tried in thought and rejected). The caption is the expansion
  title. If the seed kit leaves, the first remaining member becomes the
  seed and the tile re-renders.
- **Persistence (my call)**: for a kit the knobs live in `art.json`
  beside `kit.json` (a sidecar, so the kit format is untouched); for an
  expansion in `expansion.json`. A re-export with no change in knobs
  writes the same bytes.
- **Mark**: symbol, one-line, or stacked, bottom-right. Default stacked.

Nothing else. Seed-for-rings stays as a hidden parameter the CLI can set.

### Rendering

The existing seam stays: `KitArt.draw` paints against `ArtCanvas`; the
JVM adapter uses AWT, the phone's uses `android.graphics`. `ArtCanvas`
grows the primitives the new styles need: filled and stroked paths
(polyline, polygon, quadratic curves), circles, linear and radial
gradients, opacity, and text in a bundled font (`drawText(text, fontId,
size, x, y, anchor, colour)`). Fonts ship as OFL files inside `:shell`'s
resources and the app's assets: Unbounded ExtraBold for the mark, plus
the faces the styles use (my call on the exact four; the builder may
reduce to three if two styles can share).

Determinism per platform: same inputs, same bytes. AWT and Android
rasterise text differently, so bytes differ across platforms; that is
accepted and documented. Tests (my call) do not pin whole-image hashes
for text-bearing styles, because the synth goldens already show that
local arm64 and CI disagree on rasterised bytes. They assert: rendering
twice in one process gives identical bytes; the mark's pixels are
present at the expected corner; the palette's ground and ink are the
dominant colours; and the legibility rule below holds. The builder
verifies early, on CI, that `Font.createFont` from a bundled file works
headless there; if it does not, the fallback is glyph outlines extracted
once per face into a committed path table and filled through
`ArtCanvas`, which the builder should spike before committing to either.

**Legibility rule (my call).** Every style reserves a name zone (Bloom's
hole, Field's band, a strip for the others). The test renders every
style × palette at 128 px and checks the contrast ratio of the ink
against the mean luminance of the rendered pixels inside the name zone
is at least 4.5:1.

The CLI `art` verb gains `--palette`, `--seed-pad`, `--mark`, and renders
all eight styles side by side with no `--style`, as it does today.

### Where the picker appears

One composable, the **art picker**: the eight styles rendered live as
tiles for the kit or expansion at hand, the three knobs under them. It is
reached from Part 3's birth flow and from EXPORT.

## Part 3 · Expansions on the phone

### What an expansion is

A named collection of shelf kits with one tile. Stored on the phone
beside the kits (my call: `expansions/<id>/expansion.json` under the app's
files dir, holding the MPC-safe title, the manufacturer "SnipSnap", the
identifier, the member kit ids in order, the seed kit id, and the art
knobs). A kit may belong to more than one expansion.

**Kit identity (my call).** Kits gain a stable id: a new optional `id`
field in `kit.json`, generated and written the first time a kit without
one is read. Rename and bin move directories and never touch membership;
a binned kit is hidden from its expansions and rejoins on restore;
permanent deletion removes it from every expansion. An expansion with no
members keeps its name and knobs; its tile needs a member to draw from,
so an empty expansion shows a placeholder and refuses to export.

**Identifier (my call).** Fixed at birth: `app.snipsnap.<title-slug>.<short
random id>`, never recomputed, so the MPC's cache sees one pack across
re-exports. Expansions can be renamed later through the same hold-action
the shelf uses for kits; a rename changes the title and the on-card folder
name, not the identifier. The name prompt refuses a title another
expansion already has.

### The birth moment (owner-decided)

On the shelf, the hold-action that today offers RENAME OR DELETE gains
**ADD TO EXPANSION**. It lists existing expansions and **NEW**. NEW asks
for a name (the one typed-name dialog; MPC-safe rules enforced with the
shelf's existing rename copy as the model) and then opens the art picker
for the new expansion, seeded from the kit being added. Choosing art
finishes the birth; the kit is a member. Adding to an existing expansion
just adds. The art picker also lists which kit seeds the art, so a
second kit can take over later.

### Export

EXPORT gains a second subject beside the kit: the **expansion** (my
call on shape: a KIT / EXPANSION switch at the top of EXPORT; the
`ExportFormat` enum is per-kit and stays as it is; the expansion subject
has its own model beside `ExportWizardModel`, generalised to a list of
member kits, offering the on-card folder and `.xpn` forms only). Its
preflight runs over every member kit and a member that fails is skipped
and named, the way `PackBuilder` already does; READ BACK runs per
member; each member gets its J-Card the way a single kit does today; the
folder and the `.xpn` are named after the expansion title. Before the
write the user gets the "last chance": the tile as it will be written,
with a CHANGE ART door into the same picker. A one-kit export keeps
working as today and now also offers the picker.

### Copy

TapeOS shouts: every new line is upper case, every toast ends with a
full stop, busy lines end with an ellipsis and sit in the Copy law's
reviewed list. Names to be settled by the builder with `Personality.kt`
as the model: the hold-action label, the NEW line, the name prompt, the
"added" toast, the "skipped member" line, the CHANGE ART door.

## Constraints

- Kotlin, the existing module split: `:shell` holds the mark and art
  drawing and the expansion model (JVM-testable); `:app` holds the Android
  canvas adapter, the picker, the shelf and export screens; `:cli` the
  `art` verb; `:kit` the packaging (already there).
- One Gradle at a time per machine; `./gradlew --no-daemon`.
- No model identifiers in code or comments; attribution footers are the
  harness's.
- The steward's laws: plain declarative commit titles with why-bodies,
  tests never skipped or weakened, the Copy law.

## Left to the builder

- The exact geometry constants of each style and the palette values; the
  owner judges them on screen through the CLI `art` loop before the phone
  picker ships.
- Whether the launcher icon is generated or committed.
- The expansion store's exact JSON and migration (none needed: new file).
- How the picker renders eight live tiles on a phone without jank
  (my suggestion: 256 px previews off the main thread, cached per knob
  set).

## Order

1. The mark (icon, title bar, drawing routine, fonts bundled).
2. The art family and the picker, wired to today's one-kit export.
3. Expansions: store, shelf hold-action, birth flow, collection export.

## Builder check

A reviewer read this as a builder would and asked eight questions; all
were settled above as my calls and marked "(my call)". The ones worth the
owner's eye: kit ids in `kit.json`, expansion art drawing from one seed
kit only, the KIT / EXPANSION switch in EXPORT, and no whole-image byte
pins for text-bearing styles.
