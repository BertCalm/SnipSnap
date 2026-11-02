# Tile Art Family Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the four diagram-like browser tiles with a family of eight abstract styles drawn from the kit's own sound, chosen with four knobs (style, palette, seed pad, mark) that persist beside the kit, render the same way on the JVM and the phone, and are picked on the phone from one art picker reached from EXPORT.

**Architecture:** `ArtCanvas` grows paths, fills (solid with alpha, linear, radial) and circles; the AWT and Android adapters implement them. A `TileSound` reads the seed pad once into envelopes and attack shapes. `KitArt.draw` takes an `ArtKnobs` value instead of style+scheme, dispatches to eight style routines in `TileStyles.kt`, sets the kit name in a bundled face inside a per-style name zone, and stamps the mark bottom-right. Knobs persist in `art.json` beside `kit.json`. The phone's picker renders the eight styles live at 256 px off the main thread and writes the sidecar on every change.

**Tech Stack:** Kotlin, Gradle multi-module (`:shell`, `:cli`, `:app`), `java.awt` (JVM adapter only), `android.graphics` + Jetpack Compose (`:app`), Google Fonts Oswald / Playfair Display / Space Grotesk / Unbounded (all OFL 1.1).

**Spec:** `docs/superpowers/specs/2026-10-31-mark-tile-art-expansions-design.md` (Part 2). Part 1 shipped as PR #832; Part 3 gets its own plan after this ships.

## Global Constraints

- Run Gradle only as `./gradlew --no-daemon …`, one Gradle at a time on the machine; gate on exit code. `:shell:test` takes about six minutes; run a single class with `--tests` while iterating.
- `:app` joins the build only when `local.properties` has `sdk.dir`. App unit tests run on JUnit 4 (`:app:testDebugUnitTest`); device tests on the `snipsnap_pixel` AVD via `./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`.
- No model identifiers in code or comments. Commit footers are the harness's.
- Steward laws: plain declarative commit titles with why-bodies; tests are never skipped or weakened; the Copy law (every `Copy` string constant shouts and ends in a full stop, or sits on `PersonalityTest.notASentence` with a comment; busy lines end in `…` and sit on that list too).
- The AWT law (`ConventionTest`): `java.awt`/`javax.imageio` appear in `:shell` only in `KitArt.kt`, `JCard.kt`, `Lineage.kt`; `:app` never calls an AWT entry point.
- Colours are packed `0xRRGGBB` ints across `ArtCanvas`; alpha is a separate `Float` 0..1.
- Anti-goals from the spec, each pinned by a test here: the name is legible at 128 px on every style × palette (contrast ≥ 4.5:1, Task 4's harness, extended by Tasks 5 and 6); same kit + same knobs + same platform = same PNG bytes; the J-Card changes by not one pixel beyond Part 1 (its parity and determinism tests stay as they are).
- No whole-image byte pins for text-bearing tiles (every tile now bears text): tests assert determinism within one process, geometry of the mark, dominance of the palette ground, and the legibility rule.
- Fonts are static TTFs, committed with the family's `OFL.txt`:
  - Oswald Bold: `https://raw.githubusercontent.com/googlefonts/OswaldFont/main/fonts/ttf/Oswald-Bold.ttf` (108,496 bytes); licence `https://raw.githubusercontent.com/googlefonts/OswaldFont/main/OFL.txt`
  - Playfair Display Bold Italic: `https://raw.githubusercontent.com/google/fonts/d08233f9c581786e8c834a1f0695a4f27869c119/ofl/playfairdisplay/PlayfairDisplay-BoldItalic.ttf` (215,008 bytes; the commit before the family went variable-only); licence `https://raw.githubusercontent.com/google/fonts/main/ofl/playfairdisplay/OFL.txt`
  - Space Grotesk Bold: `https://raw.githubusercontent.com/floriankarsten/space-grotesk/master/fonts/ttf/static/SpaceGrotesk-Bold.ttf` (116,056 bytes); licence `https://raw.githubusercontent.com/floriankarsten/space-grotesk/master/OFL.txt`
  - Field's wide geometric face is Unbounded ExtraBold, already bundled (the spec allowed sharing).
- Decisions the spec left to the builder, made here (the owner judges the results through `snipsnap art` before the picker ships): default style BLOOM, default mark STACKED, default seed the longest pad, one palette per scheme (eight, values below), geometry constants per style in Tasks 4 to 6.

## Review Focus

1. A kit with no pads, or whose WAVs are missing, must still produce a tile with ground, name and mark on every style, never an exception. Test in Task 4 (`an empty kit still gets a name and a mark`), looping every style as later tasks add them.
2. A sidecar naming a style, palette or pad slot that no longer exists must fall back to defaults, never throw. Tests in Task 3 (`a stale sidecar falls back to defaults`) and Task 2 (`a missing seed slot falls back to the longest pad`).
3. A long name in the MPC-safe alphabet must shrink into its name zone at 128 px, never overflow the tile or the mark. Test in Task 4 (`a long name shrinks into its zone`).
4. A silent seed sample must draw a flat field, never NaN or Infinity geometry. Test in Task 2 (`silence is flat, not NaN`).
5. A re-export after the sidecar round-trips through disk must write the same bytes. Test in Task 7 (`the sidecar round trip changes no bytes`).

---

### Task 1: ArtCanvas grows paths, fills and circles; three faces ship

**Files:**
- Create: `shell/src/main/resources/fonts/Oswald-Bold.ttf`, `Oswald-OFL.txt`, `PlayfairDisplay-BoldItalic.ttf`, `PlayfairDisplay-OFL.txt`, `SpaceGrotesk-Bold.ttf`, `SpaceGrotesk-OFL.txt` (downloaded from the URLs in Global Constraints; the existing `OFL.txt` is Unbounded's and is renamed `Unbounded-OFL.txt`)
- Create: `shell/src/main/kotlin/com/snipsnap/shell/ArtPath.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/ArtFont.kt` (three entries)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/KitArt.kt` (`ArtCanvas`, `AwtCanvas`)
- Modify: `shell/src/test/kotlin/com/snipsnap/shell/JCardTest.kt` (`IntCanvas` gains `error()` stubs for the three new methods, as it did in Part 1)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ArtCanvasTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // ArtPath.kt — no java.awt here
  class ArtPath {
      sealed class Segment {
          data class Move(val x: Float, val y: Float) : Segment()
          data class Line(val x: Float, val y: Float) : Segment()
          data class Quad(val cx: Float, val cy: Float, val x: Float, val y: Float) : Segment()
          data object Close : Segment()
      }
      val segments: List<Segment>
      fun moveTo(x: Float, y: Float): ArtPath
      fun lineTo(x: Float, y: Float): ArtPath
      fun quadTo(cx: Float, cy: Float, x: Float, y: Float): ArtPath
      fun close(): ArtPath
      companion object {
          /** A closed polyline through the points, as [Line]s. */
          fun polygon(xs: FloatArray, ys: FloatArray): ArtPath
          /** An open polyline through the points. */
          fun polyline(xs: FloatArray, ys: FloatArray): ArtPath
      }
  }
  sealed class ArtFill {
      abstract val alpha: Float                       // 0..1, 1 = opaque
      data class Solid(val rgb: Int, override val alpha: Float = 1f) : ArtFill()
      data class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val rgb0: Int, val rgb1: Int, override val alpha: Float = 1f) : ArtFill()
      data class Radial(val cx: Float, val cy: Float, val r: Float, val inner: Int, val outer: Int, override val alpha: Float = 1f) : ArtFill()
  }
  // ArtFont.kt
  enum class ArtFont(val resourcePath: String) {
      UNBOUNDED_EXTRABOLD("fonts/Unbounded-ExtraBold.ttf"),
      OSWALD_BOLD("fonts/Oswald-Bold.ttf"),
      PLAYFAIR_BOLD_ITALIC("fonts/PlayfairDisplay-BoldItalic.ttf"),
      SPACE_GROTESK_BOLD("fonts/SpaceGrotesk-Bold.ttf");
  }
  // KitArt.kt
  interface ArtCanvas {
      // existing five unchanged
      fun fillPath(path: ArtPath, fill: ArtFill)
      fun strokePath(path: ArtPath, strokeWidth: Float, fill: ArtFill)   // round caps and joins
      fun fillCircle(cx: Float, cy: Float, r: Float, fill: ArtFill)
  }
  ```
  `AwtCanvas` maps `Solid` to `Color`, `Linear` to `GradientPaint` (non-cyclic), `Radial` to `RadialGradientPaint(cx, cy, r, [0f, 1f], [inner, outer])`, and `alpha` to `AlphaComposite.getInstance(SRC_OVER, alpha)` set for the call and restored after. Paths become `Path2D.Float` (`quadTo` for `Quad`).

- [ ] **Step 1: Download the three faces and licences into `shell/src/main/resources/fonts/`, rename `OFL.txt` to `Unbounded-OFL.txt`**

Run: `cd shell/src/main/resources/fonts && git mv OFL.txt Unbounded-OFL.txt && curl -fsSL -o Oswald-Bold.ttf <url> && …` for the six files, then `xxd -l 4 *.ttf`
Expected: every TTF starts `0001 0000`; sizes 108496, 215008, 116056.

- [ ] **Step 2: Write the failing tests in `ArtCanvasTest.kt`**

Each test paints a 100×100 `BufferedImage` (TYPE_INT_RGB, antialiasing on) through `KitArt.AwtCanvas` after a white `fillRect`, then reads pixels with `and 0xFFFFFF`.

```kotlin
@Test fun `a linear fill runs from one tone to the other`()
// fillPath(ArtPath.polygon(rect 0,0..100,100), Linear(0f,50f,100f,50f, 0xFF0000, 0x0000FF))
// pixel (2,50): red channel > 200, blue < 60; pixel (97,50): blue > 200, red < 60

@Test fun `a radial fill is inner at the centre and outer at the rim`()
// fillCircle(50,50,45, Radial(50,50,45, 0xFFFFFF, 0x000000)); pixel (50,50) luminance > 230; pixel (50,8) luminance < 40

@Test fun `alpha blends with what is under`()
// fillPath(rect, Solid(0x000000, 0.5f)) over white; pixel (50,50) each channel in 100..160

@Test fun `a quadratic segment bulges toward its control point`()
// path moveTo(0,100) quadTo(50,0,100,100) close; fill Solid(0x000000)
// pixel (50,60) is dark (< 60); pixel (50,20) is white (the curve's apex is y = 50)

@Test fun `a circle fills its centre and not its corner`()
// fillCircle(50,50,40, Solid(0x000000)); pixel (50,50) dark; pixel (3,3) white

@Test fun `every bundled face loads headless and sets ink`()
// for every ArtFont: System.setProperty("java.awt.headless","true") already set by the test task;
// drawText("H", font, 60f, 10f, 80f, START, 0x000000); some pixel in rows 30..80, cols 10..60 is dark
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ArtCanvasTest'`
Expected: compilation fails on `ArtPath`, `ArtFill`, `fillPath`, `OSWALD_BOLD`.

- [ ] **Step 4: Implement `ArtPath.kt`, the three `ArtFont` entries, the three `ArtCanvas` methods and their `AwtCanvas` bodies; add `error()` stubs to `JCardTest.IntCanvas`**

- [ ] **Step 5: Run the test class, then the suite**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ArtCanvasTest'` then `./gradlew --no-daemon :shell:test`
Expected: BUILD SUCCESSFUL both times; ConventionTest's AWT law still green (no new AWT import outside `KitArt.kt`).

- [ ] **Step 6: Commit**

```bash
git add shell/src/main/resources/fonts shell/src/main/kotlin/com/snipsnap/shell/ArtPath.kt shell/src/main/kotlin/com/snipsnap/shell/ArtFont.kt shell/src/main/kotlin/com/snipsnap/shell/KitArt.kt shell/src/test/kotlin/com/snipsnap/shell/ArtCanvasTest.kt shell/src/test/kotlin/com/snipsnap/shell/JCardTest.kt
git commit -m "Give the art canvas paths, gradients, alpha and three more faces"
```

---

### Task 2: `TileSound` reads the seed pad into envelopes

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/TileSound.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/TileSoundTest.kt`

**Interfaces:**
- Consumes: `WavReader.read(File): Snip`, `Cleanup.toMono(Snip): Snip`, `Schemes.classColor(DrumClass): Int`, `Kit.pads`, `KitPad.slot/sampleFile/drumClass`.
- Produces:
  ```kotlin
  internal class TileSound(val mono: FloatArray, val sampleRate: Int) {
      val frames: Int get() = mono.size
      val peak: Float                      // max |sample|, 0 for silence
      /** RMS per equal bin, scaled so the loudest bin is 1; all zeros for silence. Never NaN. */
      fun envelope(bins: Int): FloatArray
      /** The signed peak shape of slice [index] of [count] equal slices, [points] samples long, scaled to -1..1 by the whole sound's peak; zeros for silence. */
      fun slice(index: Int, count: Int, points: Int): FloatArray
      /** [slice] of the first [windowMs] milliseconds: the attack. */
      fun attack(points: Int, windowMs: Int = 120): FloatArray
      companion object {
          fun read(file: File): TileSound?                               // null when the file is missing or unreadable
          /** Every pad whose WAV reads, in slot order, with its class colour. */
          fun pads(kit: Kit, kitDir: File): List<PadSound>
          /** The slot with the most frames, or null for a kit with no readable pad. */
          fun longestSlot(kit: Kit, kitDir: File): Int?
          /** The seed: [slot]'s sound when it exists and reads, else the longest pad's, else null. */
          fun seed(kit: Kit, kitDir: File, slot: Int?): TileSound?
      }
  }
  internal data class PadSound(val slot: Int, val sound: TileSound, val color: Int)
  ```

- [ ] **Step 1: Write the failing tests**

Fixture: a temp kit dir with three WAVs as `KitArtTest.buildKit` writes them (8 000-frame kick tone, 5 000-frame snare, 2 000-frame hat), plus a 4 000-frame all-zero `A04_Silent_01.wav`, plus a pad whose `sampleFile` names a file that does not exist.

```kotlin
@Test fun `the envelope of a decaying tone descends`()
// a 8 000-frame tone multiplied by exp(-i / 2000): envelope(8) is strictly descending, [0] == 1f, every value in 0..1

@Test fun `silence is flat, not NaN`()
// the silent pad: envelope(16) all 0f; attack(64) all 0f; peak 0f; no value isNaN or isInfinite

@Test fun `a slice has the points asked for and stays within one`()
// slice(2, 4, 48).size == 48; every |v| <= 1f; attack(32).size == 32

@Test fun `the longest pad is the default seed`()
// longestSlot == 1 (the 8 000-frame kick)

@Test fun `a missing seed slot falls back to the longest pad`()
// seed(kit, dir, slot = 9).frames == 8_000; seed(kit, dir, null).frames == 8_000; the pad with the missing file is absent from pads()
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.TileSoundTest'`
Expected: compilation fails on `TileSound`.

- [ ] **Step 3: Implement `TileSound.kt`**

`envelope`: bin boundaries `frames * b / bins`; RMS over each; divide by the max RMS when it exceeds 1e-6, else leave zeros. `slice`: for each of `points` sub-windows of the slice, the sample with the largest magnitude (signed), divided by `peak` when above 1e-6. `attack`: the window is `min(frames, sampleRate * windowMs / 1000)` frames.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.TileSoundTest'`
Expected: BUILD SUCCESSFUL, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/TileSound.kt shell/src/test/kotlin/com/snipsnap/shell/TileSoundTest.kt
git commit -m "Read a kit's seed pad into the envelopes the tile styles draw from"
```

---

### Task 3: Palettes, knobs and the `art.json` sidecar

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/TilePalettes.kt`
- Create: `shell/src/main/kotlin/com/snipsnap/shell/ArtKnobs.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ArtKnobsTest.kt`

**Interfaces:**
- Consumes: `KitArt.Style` (still the four today; Task 4 adds BLOOM, so `ArtKnobs.default` uses `Style.WAVEFORM` in this task and Task 4 changes it to BLOOM with its own test), `Mark.Variant`, `Json`/`JsonValue`, `AtomicFile.writeText`.
- Produces:
  ```kotlin
  data class Palette(val id: String, val name: String, val ground: Int, val ink: Int, val field1: Int, val field2: Int)
  object Palettes {
      val VENT, DUSK, PHOSPHOR, OPENSKY, TIDE, MEMO, RISO, SAGE: Palette
      val ALL: List<Palette>                       // that order
      fun byId(id: String): Palette?
      fun forScheme(scheme: Scheme): Palette       // by scheme.id, VENT when unknown
  }
  data class ArtKnobs(val style: KitArt.Style, val palette: Palette, val seedSlot: Int?, val mark: Mark.Variant) {
      companion object {
          fun default(scheme: Scheme): ArtKnobs    // WAVEFORM for now, forScheme(scheme), null (AUTO), STACKED
      }
  }
  object ArtSidecar {
      const val FILE = "art.json"
      private const val VERSION = 1
      fun read(kitDir: File): ArtKnobs?            // null when absent, unparsable, wrong version, or any id unknown
      fun write(kitDir: File, knobs: ArtKnobs)     // {"version":1,"style":"bloom","palette":"vent","seedSlot":3|null,"mark":"stacked"}
      fun resolve(kitDir: File, scheme: Scheme): ArtKnobs = read(kitDir) ?: ArtKnobs.default(scheme)
  }
  ```
  Palette values (ground = the scheme's `lcd`, ink = `lcdInk`, field1 = `amber`, field2 = `accent` where it differs from amber, else `ink2`):

  | id | name | ground | ink | field1 | field2 |
  |---|---|---|---|---|---|
  | vent | VENT | 0x0F100E | 0xEEEADD | 0xE08A68 | 0xB3B4A6 |
  | dusk | DUSK | 0x0E1117 | 0xE7E9EE | 0xF2A25C | 0xAEB4C0 |
  | phosphor | PHOSPHOR | 0x080B08 | 0xB6F2BE | 0x7BE38A | 0xA7B5A3 |
  | opensky | OPEN SKY | 0x22211A | 0xF8F3E9 | 0xFF8C00 | 0xA74C00 |
  | tide | TIDE | 0x132022 | 0xEEF3F2 | 0x5FD3C6 | 0x00676B |
  | memo | MEMO | 0x1C2030 | 0xFBF5D6 | 0xFFC94A | 0x1F4FA8 |
  | riso | RISO | 0x241A2A | 0xFAF0EE | 0xFF7DB8 | 0xB0245F |
  | sage | SAGE | 0x18221B | 0xEBEFE6 | 0xA6E07F | 0x2F6B3A |

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `every scheme has a palette and every palette an id`()
// Schemes.ALL.forEach { Palettes.forScheme(it).id == it.id.name.lowercase() }; Palettes.ALL.size == 8; ids distinct; byId("riso") === Palettes.RISO; byId("mauve") == null

@Test fun `the sidecar round trips`()
// write(dir, ArtKnobs(Style.RINGS, Palettes.TIDE, 7, Mark.Variant.SYMBOL)); read(dir) == same; File(dir, "art.json").readText() contains "\"seedSlot\": 7"

@Test fun `an absent sidecar resolves to the scheme's default`()
// resolve(emptyDir, Schemes.MEMO) == ArtKnobs.default(Schemes.MEMO); default(...).palette == Palettes.MEMO; .seedSlot == null; .mark == STACKED

@Test fun `a stale sidecar falls back to defaults`()
// write {"version":1,"style":"vortex","palette":"vent","seedSlot":null,"mark":"stacked"} → read == null; version 2 → null; "not json" → null; none of them throw
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ArtKnobsTest'`
Expected: compilation fails on `Palettes`.

- [ ] **Step 3: Implement the two files**

`read` parses with `runCatching`, checks `version`, resolves each id with `byId`, and returns null on any miss. `write` goes through `AtomicFile.writeText` like `DubStamp`.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ArtKnobsTest'`
Expected: BUILD SUCCESSFUL, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/TilePalettes.kt shell/src/main/kotlin/com/snipsnap/shell/ArtKnobs.kt shell/src/test/kotlin/com/snipsnap/shell/ArtKnobsTest.kt
git commit -m "Name the tile palettes and keep a kit's art knobs beside it"
```

---

### Task 4: The tile frame: knobs in, name zone, mark, legibility harness, and Bloom

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/TileStyles.kt`
- Create: `shell/src/main/kotlin/com/snipsnap/shell/TileType.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/KitArt.kt` (`Style` gains BLOOM; `draw`/`render`/`png`/`awtPng` take `ArtKnobs`)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/ArtKnobs.kt` (`default` style becomes BLOOM)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/JCard.kt:129` (`KitArt.waveform` becomes `KitArt.waveformStrip`; the spine's pixels do not change)
- Modify: every caller of the old signatures so the build stays green: `ExportWizard.kt:43,141,162-164`, `cli/.../Exports.kt:35-53`, `cli/.../PackCommand.kt:50`, `cli/.../SideACommand.kt:68`, `cli/.../AlbumCommand.kt:128`, `cli/.../ArtCommand.kt`, `app/.../AndroidKitArt.kt`, `app/.../ui/ExportScreen.kt:305,554`. In this task each caller passes `ArtSidecar.resolve(kitDir, Schemes.DEFAULT)` or `ArtKnobs.default(...)` with its old style overriding `style`; Tasks 7 to 10 give them their real wiring.
- Test: `shell/src/test/kotlin/com/snipsnap/shell/KitArtTest.kt` (rewritten), `shell/src/test/kotlin/com/snipsnap/shell/ArtKnobsTest.kt` (one new test)

**Interfaces:**
- Consumes: Task 1 primitives, `TileSound`, `Palette`, `ArtKnobs`, `Mark.draw/width/symbol`.
- Produces:
  ```kotlin
  object KitArt {
      enum class Style(val id: String, val blurb: String) { WAVEFORM, GRID, SLICES, RINGS, BLOOM("bloom", "the seed's loudness wrapped round a circle, the name in the hole") }
      const val DEFAULT_SIZE = 600
      fun draw(canvas: ArtCanvas, kit: Kit, kitDir: File, knobs: ArtKnobs, size: Int, label: String, ringsSeed: Int = 0)
      fun render(kit: Kit, kitDir: File, knobs: ArtKnobs, size: Int = DEFAULT_SIZE, label: String = kit.name, ringsSeed: Int = 0): BufferedImage
      fun png(kit: Kit, kitDir: File, knobs: ArtKnobs, size: Int = DEFAULT_SIZE, label: String = kit.name, ringsSeed: Int = 0): ByteArray
      fun awtPng(kit: Kit, kitDir: File, knobs: ArtKnobs): ByteArray
      /** Margin: size * 0.06, rounded. */
      internal fun margin(size: Int): Int
      /** The mark's cap height on a tile: SYMBOL size*0.08, STACKED size*0.07, LOCKUP size*0.04. */
      internal fun markHeight(variant: Mark.Variant, size: Int): Float
      /** Where the mark's top-left lands: right edge at size - margin, bottom edge at size - margin. */
      internal fun markOrigin(canvas: ArtCanvas, variant: Mark.Variant, size: Int): Pair<Float, Float>
      /** The width the bottom strip reserves for the mark plus a gap: SYMBOL 0.12, STACKED 0.26, LOCKUP 0.44 of size. */
      internal fun markReserve(variant: Mark.Variant, size: Int): Int
      /** The box the name must stay inside and the legibility test measures. */
      internal fun nameZone(style: Style, variant: Mark.Variant, size: Int): Box
      internal fun waveformStrip(canvas: ArtCanvas, box: Box, kit: Kit, kitDir: File)   // the old waveform, renamed; JCard's spine keeps calling it
  }
  // TileType.kt
  internal object TileType {
      /** The largest size <= maxSizePx whose width fits maxWidthPx, never below 6. */
      fun fit(canvas: ArtCanvas, text: String, font: ArtFont, maxWidthPx: Float, maxSizePx: Float): Float
      /** drawText char by char with [trackingEm] extra advance per glyph; START anchor. */
      fun tracked(canvas: ArtCanvas, text: String, font: ArtFont, sizePx: Float, x: Float, baselineY: Float, trackingEm: Float, colorRgb: Int)
      fun trackedWidth(canvas: ArtCanvas, text: String, font: ArtFont, sizePx: Float, trackingEm: Float): Float
  }
  // TileStyles.kt — one internal fun per style, all with this shape
  internal fun bloom(canvas: ArtCanvas, size: Int, palette: Palette, seed: TileSound?, label: String, zone: Box)
  ```
  `draw`: `fillRect(0,0,size,size, palette.ground)`, resolve `seed = TileSound.seed(kit, kitDir, knobs.seedSlot)`, dispatch the style (old four still through their old code with `palette.ground/ink/field1` where they used `scheme.lcd/lcdInk/amber`), then `Mark.draw(canvas, knobs.mark, x, y, markHeight, palette.ink, palette.field1)`. The old 5×7 caption and hairline go. Name zones: BLOOM = the square inscribed in the hole; every other style for now = the bottom strip `Box(margin, round(size*0.80), size - 2*margin - markReserve, round(size*0.14))`.

  BLOOM geometry: centre (size/2, size/2); R = size*0.40; `env = seed.envelope(180)` (zeros when null); polar polygon of 360 points, angle i, radius `R * (0.45 + 0.55 * env[min(i, 359-i)])` (mirrored, so it reads as a bloom); `fillPath(path, Radial(cx, cy, R, field1, field2))`; `strokePath(path, size*0.008, Solid(ink, 0.35))`; hole `fillCircle(cx, cy, R*0.42, Solid(ground))`; name `OSWALD_BOLD`, uppercase, `MIDDLE` at cx, size fitted to the hole's inscribed square width, baseline `cy + fitted*0.36`.

- [ ] **Step 1: Write the failing tests (rewrite `KitArtTest.kt`)**

Keep `buildKit`. Replace the four tests with these, every loop over `KitArt.Style.entries` and `Palettes.ALL` so Tasks 5 and 6 extend coverage by adding enum entries:

```kotlin
@Test fun `every style renders on every palette, deterministic, right size, ground dominant`()
// knobs = ArtKnobs(style, palette, null, STACKED); png twice at 200 → equal bytes; 200×200;
// the most frequent exact colour among pixels sampled every 4th row and column is palette.ground

@Test fun `the name stays legible in its zone on every style and palette`()
// a TextlessCanvas(AwtCanvas) wrapper (test-local) forwards everything but drawText; KitArt.draw at 128 with mark STACKED;
// mean relative luminance of the pixels in KitArt.nameZone(style, STACKED, 128);
// WCAG contrast of palette.ink against that mean >= 4.5 (contrast helper: (L1+0.05)/(L2+0.05), lighter over darker)

@Test fun `the mark sits bottom-right in the palette's two tones`()
// for every style on Palettes.VENT with mark SYMBOL at 300: Mark.symbol(markOrigin.x, markOrigin.y, markHeight) pad centres read field1, ink, ink, field1;
// with mark STACKED and LOCKUP: the box from markOrigin to (size - margin) holds both field1 and ink pixels

@Test fun `an empty kit still gets a name and a mark`()
// Kit("Empty", emptyList()) saved to a dir with no WAVs; every style × Palettes.PHOSPHOR renders at 128 without throwing; mark pads present as above; some pixel inside nameZone is not ground (the name was set)

@Test fun `a long name shrinks into its zone`()
// "A Really Quite Long Kit Name Indeed 2026" on every style at 128 with mark LOCKUP: no non-ground pixel in the row band of the zone lies outside the zone's x-range plus 1 px (sample the zone's rows across the full width)

@Test fun `the seed pad changes the field and the rings seed changes only rings`()
// BLOOM with seedSlot 1 vs 3 → different bytes; BLOOM ringsSeed 3 vs 4 → same bytes; RINGS ringsSeed 3 vs 4 → different bytes

@Test fun `every starter kit renders every style without error`()   // keep, with knobs
```
In `ArtKnobsTest`: `@Test fun \`the default style is bloom\`()` asserting `ArtKnobs.default(Schemes.VENT).style == KitArt.Style.BLOOM`.
Move the old `pixel type fits long names` test into a new `PixelTypeTest.kt` unchanged except that it no longer calls `KitArt.png` (PixelType stays in the codebase for the J-Card and the ranks that use it).

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.KitArtTest'`
Expected: compilation fails on `ArtKnobs`-taking `png`, `nameZone`, `Style.BLOOM`.

- [ ] **Step 3: Implement `TileType.kt`, `bloom` in `TileStyles.kt`, the new `KitArt` surface, and sweep the callers listed above**

- [ ] **Step 4: Run the class, then `:shell:test` and `:cli:test`, then the app compile**

Run: `./gradlew --no-daemon :shell:test` then `./gradlew --no-daemon :cli:test` then `./gradlew --no-daemon :app:compileDebugKotlin`
Expected: all green. `JCardTest` unchanged and green (the spine still calls `waveformStrip`). `ExportWizardTest` adjusted only where it named `artStyle`.

- [ ] **Step 5: Render and eyeball**

Run: `./gradlew --no-daemon :cli:run --args="art <a starter kit dir> --out <scratchpad>/art-check"` (the `application` plugin's run task, as Part 1 rendered `mark`), send the bloom PNG to the owner's side panel.
Expected: a bloom with the name in the hole, the stacked mark bottom-right.

- [ ] **Step 6: Commit**

```bash
git add shell cli app
git commit -m "Draw the tile from art knobs: a name zone, the mark, and Bloom"
```

---

### Task 5: Ridges, Field and Ribbon

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/TileStyles.kt`, `KitArt.kt` (`Style` gains RIDGES, FIELD, RIBBON; dispatch; `nameZone` cases)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/KitArtTest.kt` (one new test; the loops pick up the new entries)

**Interfaces:**
- Consumes: Task 4's style function shape, `TileType`, `TileSound.slice/attack/envelope`.
- Produces: `Style.RIDGES("ridges", "the attack sliced into ridge lines that fade back")`, `Style.FIELD("field", "a dot field that swells with the sound, the name on a band")`, `Style.RIBBON("ribbon", "the attack as one glowing stroke")`.

  RIDGES: 9 ridges, baselines from `size*0.32` to `size*0.92` evenly; ridge k is `seed.slice(k, 9, 96)` drawn as a polyline across `margin..size-margin`, amplitude `size*0.11*(1 - 0.6*k/8)` (upward for positive values); under each ridge fill the polygon down to the next baseline in `Solid(ground)` before stroking (occlusion); stroke width `size*0.007`, `Solid(ink, 1 - 0.7*k/8)`, the first ridge in `field1`. Name zone `Box(margin, margin, size - 2*margin, round(size*0.20))`; name `PLAYFAIR_BOLD_ITALIC` as typed (not uppercased), `START` at margin, baseline `margin + fitted*0.9`, fitted to the zone width at max `size*0.12`.

  FIELD: 12×12 dots in `Box(margin, margin, size-2m, size-2m)`; dot (col,row) reads `env = seed.envelope(144)[row*12+col]`; radius `cell*(0.08 + 0.36*env)`, `Solid(lerp(field1, field2, col/11f), 0.3 + 0.7*env)`. Band `fillRect(0, round(size*0.40), size, round(size*0.20), …)` as `fillPath` with `Solid(ground, 0.88)`. Name zone = the band inset by margin; name `UNBOUNDED_EXTRABOLD`, uppercase, `MIDDLE`, fitted to zone width at max `size*0.10`, baseline `size*0.50 + fitted*0.36`.

  RIBBON: `pts = seed.attack(160)` as a polyline from `x = margin` to `size - margin`, `y = size*0.42 - pts[i]*size*0.24`; three strokes: width `size*0.10` `Solid(field2, 0.18)`, width `size*0.05` `Solid(field1, 0.35)`, width `size*0.022` `Linear(margin, 0, size-margin, 0, field1, field2)`. Name zone = bottom strip; name `SPACE_GROTESK_BOLD`, uppercase, `TileType.tracked` at 0.18 em, `START` at margin, baseline `size*0.90`, fitted (with tracking) to the zone width at max `size*0.08`.

  `lerp(a, b, t)` on packed RGB joins the colour helpers at the foot of `KitArt.kt` (move `lighten`/`darken`/`lerp` into a small `internal object TileColor` in `TileStyles.kt` if `KitArt.kt` passes 400 lines).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `ridges and ribbon follow the seed's attack and field follows its envelope`()
// RIDGES, FIELD, RIBBON each: seedSlot 1 vs seedSlot 3 → different bytes at 200; the silent pad as seed → renders without throwing and is still ground-dominant
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.KitArtTest'`
Expected: compilation fails on `Style.RIDGES`.

- [ ] **Step 3: Implement the three styles and their `nameZone` cases**

- [ ] **Step 4: Run the class, then the suite; render all styles and send them**

Run: `./gradlew --no-daemon :shell:test` ; then the `art` render as in Task 4 Step 5.
Expected: green; the legibility, mark, empty-kit and long-name tests now cover eight styles.

- [ ] **Step 5: Commit**

```bash
git add shell
git commit -m "Add Ridges, Field and Ribbon to the tile family"
```

---

### Task 6: Waveform, Grid, Slices and Rings redrawn

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/TileStyles.kt`, `KitArt.kt` (the four old private style bodies are deleted; `waveformStrip` stays for the J-Card; `drawArc` stays on `ArtCanvas` because the Android adapter and `IntCanvas` implement it, but no tile calls it)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/KitArtTest.kt` (one new test)

**Interfaces:**
- Consumes: `TileSound.pads(kit, kitDir): List<PadSound>` (per-pad styles draw the seed kit's pads, class-coloured), Task 4/5 helpers.
- Produces: the four entries keep their ids and get new blurbs: WAVEFORM "every pad end to end as one river, class colours blending along it", GRID "the 4×4 bank, each pad a small bloom of its own sound", SLICES "one wedge per pad, radiating, sized by length", RINGS "seeded arcs per pad with weight and glow".

  WAVEFORM: join all pads' mono in slot order; 256 columns of min/max via `PeaksPyramid.build(joined).columns(0, total, 256)`; river path: `moveTo` at the first column's max, `quadTo` through successive maxima (control point at the midpoint, y of the earlier column), along the top; back along the minima; close; y centre `size*0.42`, amplitude `size*0.26`, x from margin to `size-margin`; `fillPath(river, Linear(margin, 0, size-margin, 0, field1, field2))`; then for each pad, the sub-path of its columns filled `Solid(classColor, 0.55)`. Name zone = bottom strip; name `SPACE_GROTESK_BOLD`, uppercase, `START`, fitted at max `size*0.08`.

  GRID: 4×4 cells in `Box(margin, margin, size-2m, round(size*0.70))`, MPC layout as today (A01 bottom-left); a pad's cell: `env = sound.envelope(24)`, polar polygon of 48 points radius `cell*0.46*(0.35 + 0.65*env[i/2])`, `Solid(classColor, 0.9)`, plus `fillCircle(centre, cell*0.10, Solid(ground))`; empty cell: `fillCircle(centre, cell*0.06, Solid(ink, 0.15))`. Name as WAVEFORM but `OSWALD_BOLD`.

  SLICES: wedges from `(size/2, size*0.42)`, N = pads.size (≤ 32), wedge i sweeps `360/N` degrees starting at `-90 + i*360/N`, outer radius `R*sqrt(frames/maxFrames)` with `R = size*0.34`, inner radius `R*0.18`, drawn as a 14-point polygon (inner arc back), `Solid(classColor, 0.85)`, then a `strokePath` of the outer arc width `size*0.006` `Solid(ink, 0.25)`. Name as WAVEFORM.

  RINGS: centre `(size/2, size*0.42)`, `Random(ringsSeed)` as today for start angles; pad i radius `R*(i+1)/(N+1)` with `R = size*0.36`, sweep `30 + 300*frames/maxFrames`, arc as a 48-point `ArtPath.polyline`; glow stroke width `w*2.8` `Solid(classColor, 0.18)`, then width `w = size*0.010 + peak*size*0.028` `Solid(classColor, 0.95)`. Name as WAVEFORM.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `the redrawn four are no longer bars and boxes`()
// GRID on buildKit at 200: the pixel at a filled cell's centre is ground (the hole), not class colour — a lit box would be class colour there
// SLICES at 200: the pixel at (size/2, size*0.42) is ground (the inner hole); a bar chart would leave it ground too, so also: some pixel on the ray at 45° from centre at radius R*0.5 is not ground
// WAVEFORM at 200: at x = size/2 the lit column is one contiguous vertical run (a river), and its top is not a single-pixel rectangle edge: the lit run at x = size/2 - 1 differs in height by at most 3 px
// RINGS at 200: every pad's arc has pixels whose colour is neither ground nor the exact class colour (the glow blended)
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.KitArtTest'`
Expected: FAIL on the GRID assertion (today's cell centre is class colour).

- [ ] **Step 3: Implement the four redraws; delete the old bodies; keep `waveformStrip`**

- [ ] **Step 4: Run `:shell:test` and `:cli:test`; render all eight and send them**

Expected: green, `JCardTest` untouched; eight PNGs in the side panel for the owner.

- [ ] **Step 5: Commit**

```bash
git add shell
git commit -m "Redraw Waveform, Grid, Slices and Rings into the tile family"
```

---

### Task 7: Knobs flow through export on the JVM

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/ExportWizard.kt` (`artRenderer: (Kit, File, ArtKnobs) -> ByteArray`, `var artKnobs: ArtKnobs?` replacing `artStyle`, resolved from the sidecar at construction with `Schemes.DEFAULT` unless the caller passes `artKnobs`)
- Modify: `cli/src/main/kotlin/com/snipsnap/cli/Exports.kt` (`parseArtStyle` becomes `fun artKnobs(kitDir: File, styleWord: String?): ArtKnobs` = sidecar or default, style overridden when a word is given), `PackCommand.kt`, `SideACommand.kt`, `AlbumCommand.kt` (knobs from the first kit's sidecar; label stays the title)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ExportWizardTest.kt`, `cli/src/test/kotlin/com/snipsnap/cli/ExportCommandTest.kt` (or whichever CLI test already drives `--art`; find it with `grep -rn '"--art"' cli/src/test`)

**Interfaces:**
- Consumes: `ArtSidecar.read/write/resolve`, `KitArt.awtPng(kit, kitDir, knobs)`.
- Produces: `ExportWizardModel(kit, kitDir, artRenderer, jCardRenderer, artKnobs: ArtKnobs? = ArtSidecar.resolve(kitDir, Schemes.DEFAULT))`; `var artKnobs: ArtKnobs?` (null skips artwork, as `artStyle = null` did).

- [ ] **Step 1: Write the failing tests**

```kotlin
// ExportWizardTest
@Test fun `the sidecar round trip changes no bytes`()
// a kit dir; model A with default knobs writes an EXPANSION to destA; ArtSidecar.write(kitDir, model.artKnobs!!); model B (fresh, reads the sidecar) writes to destB; the two tile PNGs are byte-equal

@Test fun `a sidecar picks the tile the export writes`()
// ArtSidecar.write(kitDir, knobs(FIELD, RISO, null, SYMBOL)); a renderer spy records the knobs it was handed; write EXPANSION; spy saw style FIELD and palette RISO

// CLI test
@Test fun `--art overrides only the style`()
// sidecar says BLOOM/TIDE; `snipsnap export <kit> --format expansion --art rings --out …`; the tile's (0,0) pixel is TIDE's ground and the export log names rings
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ExportWizardTest'` and the CLI class.
Expected: compilation fails on `artKnobs`.

- [ ] **Step 3: Implement; sweep `ConventionTest` for any pin on `artStyle` or the renderer's type and update the pin to the new names (never delete a law)**

- [ ] **Step 4: Run `:shell:test` and `:cli:test`**

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add shell cli
git commit -m "Carry a kit's art knobs through every export path"
```

---

### Task 8: `snipsnap art` takes the knobs

**Files:**
- Modify: `cli/src/main/kotlin/com/snipsnap/cli/ArtCommand.kt`, `cli/src/main/kotlin/com/snipsnap/cli/Main.kt` (usage text for `art`)
- Modify: any doc that lists `art`'s flags (`grep -rn 'snipsnap art' README.md docs cli/README.md`)
- Test: `cli/src/test/kotlin/com/snipsnap/cli/ArtCommandTest.kt` (create if absent; `grep -rln '"art"' cli/src/test` first)

**Interfaces:**
- Produces: `snipsnap art <kit-dir> [--style ID] [--palette ID] [--seed-pad N] [--mark symbol|lockup|stacked] [--scheme ID] [--seed N] [--size N] [--out DIR]`. No `--style` renders all eight. `--palette` wins over `--scheme` (which maps through `Palettes.forScheme`); neither given → the kit's sidecar, else VENT. `--seed` is the rings dice, unchanged. The summary line prints the knobs; the "narrow it down" hint names the new flags and the palette ids.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `art with no style writes all eight`()
// eight files `<name>_<style>.png`, one per Style.entries, stdout names each

@Test fun `art takes a palette, a seed pad and a mark`()
// `--palette riso --seed-pad 2 --mark symbol --size 128`: (0,0) of the bloom PNG is RISO's ground; Mark.symbol pad centres (via KitArt.markOrigin on an AwtCanvas probe) read RISO field1/ink

@Test fun `art refuses an unknown palette or mark`()
// `--palette mauve` → non-zero, stderr lists the palette ids; `--mark big` → non-zero, stderr lists symbol,lockup,stacked
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :cli:test --tests 'com.snipsnap.cli.ArtCommandTest'`
Expected: FAIL (unknown option `--palette`).

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run `:cli:test`**

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add cli README.md docs
git commit -m "Let snipsnap art set the palette, the seed pad and the mark"
```

---

### Task 9: The phone's canvas draws paths, fills and circles; knobs reach the Android renderer

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/AndroidKitArt.kt` (`png(kit, kitDir, knobs: ArtKnobs, size, label, assets)`; `AndroidCanvas` implements the three primitives)
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/AndroidCanvasTest.kt` (three tests), `app/src/androidTest/kotlin/com/snipsnap/app/TileArtDeviceTest.kt` (new)

**Interfaces:**
- Consumes: Task 1 types; Task 4's `KitArt.draw(canvas, kit, kitDir, knobs, size, label, ringsSeed)`.
- Produces: `AndroidKitArt.png(kit: Kit, kitDir: File, knobs: ArtKnobs, size: Int = KitArt.DEFAULT_SIZE, label: String = kit.name, assets: AssetManager): ByteArray`. `AndroidCanvas` maps `Solid` to `Paint.color` + `alpha`, `Linear` to `LinearGradient(... Shader.TileMode.CLAMP)`, `Radial` to `RadialGradient`, and clears the shader after each call; paths use `Path.quadTo`; strokes use `Paint.Cap.ROUND`/`Join.ROUND`.

- [ ] **Step 1: Write the failing device tests**

```kotlin
// AndroidCanvasTest additions (same 100×100 ARGB bitmap fixture as its existing tests)
@Test fun `a linear fill runs from one tone to the other on the phone`()
@Test fun `alpha blends on the phone`()
@Test fun `a quadratic segment bulges on the phone`()
// assertions mirror ArtCanvasTest's

// TileArtDeviceTest
@Test fun `every style renders deterministic bytes on the phone`()
// a 3-pad fixture kit written under context.filesDir/tile-test (WavWriter as KitArtTest); for every Style × Palettes.VENT at 128: png twice → contentEquals; decoded bitmap 128×128; pixel (0,0) is VENT ground
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.AndroidCanvasTest` (emulator up)
Expected: compilation fails on `fillPath` (the app's canvas does not implement `ArtCanvas` yet).

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run both classes on the emulator, then `:app:assembleDebug`**

Expected: 6 + 1 tests green; APK builds; `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets/fonts` lists four TTFs.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Let the phone's art canvas fill paths and gradients and take the art knobs"
```

---

### Task 10: The art picker, reached from EXPORT

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/ArtPicker.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/ExportScreen.kt` (session creation resolves knobs with the active scheme; an `ArtRow` under `FormatPickerRow`; the landed note's tile uses the knobs)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` (copy below), `shell/src/test/kotlin/com/snipsnap/shell/PersonalityTest.kt` (`notASentence` entries)
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/ArtPickerTest.kt`, `shell/src/test/kotlin/com/snipsnap/shell/PersonalityTest.kt` (the existing law picks the new strings up)

**Interfaces:**
- Consumes: `BottomSheet(title, onDismiss, content)`, `offMain`, `TapeText`, `Modifier.tapeClick/raisedBevel/lcdPanel`, `Layout.MIN_HIT_TARGET`, `KitWrites.mutex`, `AndroidKitArt.png(knobs)`, `ArtSidecar`.
- Produces:
  ```kotlin
  // ArtPicker.kt
  @Composable internal fun ArtPickerSheet(kit: Kit, kitDir: File, knobs: ArtKnobs, onKnobs: (ArtKnobs) -> Unit, onDismiss: () -> Unit)
  /** 256 px previews, one per knob set, rendered off main through AndroidKitArt and kept for the sheet's life. */
  internal class ArtPreviews(private val context: Context, private val kit: Kit, private val kitDir: File) {
      suspend fun bitmap(knobs: ArtKnobs): ImageBitmap     // cached by knobs
  }
  @Composable internal fun ArtRow(knobs: ArtKnobs, enabled: Boolean, scheme: Scheme, onOpen: () -> Unit)
  // Personality.kt
  const val ART_ROW = "ART"                                   // notASentence: a row heading
  const val ART_ROW_NOTE = "WRITTEN WITH EXPANSION AND XPN."
  const val ART_SHEET_TITLE = "PICK THE ART"                  // notASentence: a sheet title
  const val ART_PALETTE = "PALETTE"                           // notASentence: a knob heading
  const val ART_SEED_PAD = "SEED PAD"                         // notASentence
  const val ART_SEED_AUTO = "AUTO"                            // notASentence: a chip
  const val ART_MARK = "MARK"                                 // notASentence
  const val ART_BUSY = "DRAWING THE TILES…"                   // notASentence: a busy line
  const val ART_SET = "ART SET."
  fun artSummary(style: String, palette: String, seed: String, mark: String): String = "$style · $palette · $seed · $mark"
  ```
  Sheet layout, top to bottom inside `BottomSheet(Copy.ART_SHEET_TITLE, onDismiss)`: a 2-column grid of the eight styles, each an `Image` of `previews.bitmap(knobs.copy(style = s))` with the style name under it, `tapeClick(label = "STYLE: ${s.name}")`, the picked one framed in `scheme.amber`; while a preview is pending the cell shows `Copy.ART_BUSY`; then three chip rows headed `ART_PALETTE` (eight chips, label `"PALETTE: ${p.name}"`), `ART_SEED_PAD` (`AUTO` then one chip per pad slot `"A%02d"`, label `"SEED PAD: A01"`), `ART_MARK` (`symbol`, `lockup`, `stacked` upper-cased, label `"MARK: STACKED"`). Every tap calls `onKnobs` with the changed copy; the sheet stays open.

  EXPORT: `ArtRow` sits under `FormatPickerRow`: heading `ART`, a bevelled row reading `Copy.artSummary(knobs.style.name, knobs.palette.name, seed label, knobs.mark.id.uppercase())` with `▸`, click label `"ART: <summary>"`, caption `ART_ROW_NOTE`, enabled when `!session.busy`. Opening shows the sheet; `onKnobs` sets `model.artKnobs`, writes the sidecar under `KitWrites.mutex` off main, and bumps `revision`; dismiss toasts `ART_SET` when the knobs changed since opening. Session creation resolves `ArtSidecar.read(dir) ?: ArtKnobs.default(scheme)` with the screen's scheme. `landedNote` renders its tile with `model.artKnobs`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// PersonalityTest: nothing to write — the existing law fails on the new constants until they are listed/punctuated; run it after Step 3.

// ArtPickerTest (extends ComposeScreenTest; fixture kit as ExportScreenTest builds one)
@Test fun `the art row opens the picker and a pick lands in the sidecar`()
// setPhoneContent { ExportScreen(...) }; tap("ART: BLOOM · VENT · AUTO · STACKED"); assertReadsInFull(Copy.ART_SHEET_TITLE);
// tap("STYLE: FIELD"); tap("PALETTE: RISO"); waitFor { ArtSidecar.read(kitDir)?.style == Style.FIELD };
// tap("CLOSE"); the row now reads "FIELD · RISO · AUTO · STACKED"; the toast ART SET. showed (onToast spy)

@Test fun `eight previews arrive`()
// open the sheet; waitFor(10 s) { compose.onAllNodesWithContentDescription(substring "STYLE: ").fetchSemanticsNodes().size == 8 }; no node still reads ART_BUSY after settling
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.ArtPickerTest`
Expected: compilation fails on `ArtPickerSheet`.

- [ ] **Step 3: Implement the copy, the sheet, the row, and the EXPORT wiring**

- [ ] **Step 4: Run `:shell:test --tests '*PersonalityTest'`, the device class, then `ExportScreenTest` on the device; screenshot EXPORT with the sheet open and send it**

Expected: all green; the owner sees the sheet.

- [ ] **Step 5: Commit**

```bash
git add app shell
git commit -m "Pick a kit's tile art from EXPORT"
```

---

### Task 11: Whole-plan gates

- [ ] **Step 1: Full JVM suites**

Run: `./gradlew --no-daemon :shell:test` then `./gradlew --no-daemon :cli:test` then `./gradlew --no-daemon -PcreaseStudyBuild=true :app:testDebugUnitTest :app:testCreaseStudyUnitTest :app:assembleDebug :app:assembleStrikeFx :app:compileDebugAndroidTestKotlin :app:compileCreaseStudyKotlin`
Expected: BUILD SUCCESSFUL each; test counts read from `build/test-results` XML, no failures.

- [ ] **Step 2: Device suites touched by this plan**

Run the emulator classes `AndroidCanvasTest`, `TileArtDeviceTest`, `ArtPickerTest`, `ExportScreenTest`, `TitleBarTest`.
Expected: all green.

- [ ] **Step 3: Render the eight styles on three palettes and send them**

Run: `snipsnap art <starter kit> --out …` for `--palette vent`, `--palette riso`, `--palette phosphor`; `SendUserFile` the 24 PNGs or a contact sheet.
Expected: the owner can judge the family before Part 3 is planned.

- [ ] **Step 4: Update `docs/MODERN_LOOK.md` where it describes the tiles or the wordmark, and any doc that names the four styles**

Run: `grep -rn 'pixel-font\|5×7\|WAVEFORM/GRID\|four styles' docs README.md app/README.md`
Expected: each hit either updated to the eight-style family or left because it describes something else, with the reason in the commit body.

- [ ] **Step 5: Commit the docs**

```bash
git add docs README.md app/README.md
git commit -m "Describe the eight-style tile family in the docs"
```
