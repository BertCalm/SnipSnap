# SnipSnap Mark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give SnipSnap its mark: the pad-cluster symbol and the two Unbounded lockups, drawn once in `:shell`, used as the app icon, in the title bar and on the J-Card, and renderable from the CLI so the owner can eyeball it.

**Architecture:** One drawing routine, `Mark`, expressed against the existing platform-neutral `ArtCanvas`, which grows three primitives (filled polygon, text in a bundled font, text width). The AWT adapter in `KitArt` and the Android adapter in `AndroidKitArt` implement them. Unbounded ExtraBold ships once, as a `:shell` resource that the app also packs as an asset. The launcher icon is a vector drawable of the same geometry, pinned to `Mark` by a test.

**Tech Stack:** Kotlin, Gradle multi-module (`:shell`, `:cli`, `:app`), `java.awt` (JVM adapter only), `android.graphics` + Jetpack Compose (`:app`), Google Fonts Unbounded (OFL 1.1).

**Spec:** `docs/superpowers/specs/2026-10-31-mark-tile-art-expansions-design.md` (Part 1). Parts 2 and 3 get their own plans after this ships.

## Global Constraints

- Run Gradle only as `./gradlew --no-daemon …`, one Gradle at a time on the machine; gate on exit code.
- `:app` joins the build only when `local.properties` has `sdk.dir` (copy it from the main checkout; it is git-ignored).
- No model identifiers in code or comments. Commit footers are the harness's.
- Steward laws: plain declarative commit titles with why-bodies; tests are never skipped or weakened; the Copy law (every `Copy` constant is a shouting sentence with a full stop, or sits on `PersonalityTest`'s reviewed list). This part adds no copy.
- Colours are packed `0xRRGGBB` ints everywhere `ArtCanvas` is involved, as today.
- Geometry decided by the owner (spec Part 1): four diamonds in a 2×2 touching at the corners, accent on the top-left and bottom-right, ink on the other two; one-line lockup SNIP · symbol · SNAP with SNAP in accent, symbol at cap height; stacked lockup SNIP over SNAP flush left with natural spacing, symbol to the right exactly as tall as cap-top to baseline, a small fixed gap after the wider word.
- The title bar keeps the accessible name `SNIPSNAP`.
- The font is Unbounded ExtraBold, static TTF from `https://github.com/googlefonts/Unbounded/raw/main/fonts/ttf/Unbounded-ExtraBold.ttf`, with the repository's `OFL.txt` committed beside it.

## Review Focus

1. A scheme whose `ink` and `accent` are the same colour would turn the x into a solid square; `Mark.draw` must still show two tones. Test in Task 2 (`the symbol keeps two tones on every scheme`).
2. A mark drawn too small (height under 8 px) collapses the diamonds into noise; `Mark.draw` refuses it loudly. Test in Task 2 (`a mark under eight pixels is refused`).
3. The font file missing from a platform (asset directory not merged, resource stripped) must fail with a named error, not draw nothing. Tests in Task 1 (`the bundled font loads through the class loader`) and Task 4 (`the font asset is packed`).
4. CI's JVM runs headless; loading a font must work with `java.awt.headless=true`. Test in Task 1 (`the font loads headless`).
5. The title bar must never ellipsise the lockup when a long project name shares the row; the lockup reports a fixed width and the row measures it first. Test in Task 6 (`a long project name does not squeeze the lockup`).

---

### Task 1: ArtCanvas grows polygon and text; the font ships in `:shell`

**Files:**
- Create: `shell/src/main/resources/fonts/Unbounded-ExtraBold.ttf` (downloaded, OFL), `shell/src/main/resources/fonts/OFL.txt`
- Create: `shell/src/main/kotlin/com/snipsnap/shell/ArtFont.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/KitArt.kt:33-36` (`ArtCanvas`), `:176-190` (`AwtCanvas`)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ArtCanvasTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class ArtFont(val resourcePath: String) {
      UNBOUNDED_EXTRABOLD("fonts/Unbounded-ExtraBold.ttf");
      /** The TTF bytes, read through the class loader; throws IllegalStateException naming the path when absent. */
      fun bytes(): ByteArray
  }
  enum class TextAnchor { START, MIDDLE, END }
  interface ArtCanvas {
      fun fillRect(x: Int, y: Int, w: Int, h: Int, colorRgb: Int)            // unchanged
      fun drawArc(...)                                                        // unchanged
      fun fillPolygon(xs: FloatArray, ys: FloatArray, colorRgb: Int)
      fun drawText(text: String, font: ArtFont, sizePx: Float, x: Float, baselineY: Float, anchor: TextAnchor, colorRgb: Int)
      fun textWidth(text: String, font: ArtFont, sizePx: Float): Float
  }
  ```
  `KitArt.AwtCanvas` implements all five; `KitArt.render` keeps working unchanged. `AwtFonts.font(ArtFont): java.awt.Font` (internal object in `ArtFont.kt`'s file or `KitArt.kt`) caches one base `Font` per `ArtFont`, created with `Font.createFont(Font.TRUETYPE_FONT, bytes.inputStream())`, derived per size with `deriveFont(sizePx)`.

- [ ] **Step 1: Download the font and licence into `shell/src/main/resources/fonts/`**

Run:
```bash
mkdir -p shell/src/main/resources/fonts
curl -sSL -o shell/src/main/resources/fonts/Unbounded-ExtraBold.ttf https://github.com/googlefonts/Unbounded/raw/main/fonts/ttf/Unbounded-ExtraBold.ttf
curl -sSL -o shell/src/main/resources/fonts/OFL.txt https://github.com/googlefonts/Unbounded/raw/main/OFL.txt
file shell/src/main/resources/fonts/Unbounded-ExtraBold.ttf
```
Expected: `TrueType Font data` and a non-empty `OFL.txt` beginning with "Copyright".

- [ ] **Step 2: Write the failing tests**

```kotlin
class ArtCanvasTest {
    private fun render(size: Int, draw: (ArtCanvas) -> Unit): BufferedImage  // BufferedImage + KitArt.AwtCanvas(g), antialias on

    @Test fun `the bundled font loads through the class loader`() {
        assertTrue(ArtFont.UNBOUNDED_EXTRABOLD.bytes().size > 50_000)
    }
    @Test fun `the font loads headless`() {
        System.setProperty("java.awt.headless", "true")
        val font = AwtFonts.font(ArtFont.UNBOUNDED_EXTRABOLD)   // must not throw under headless
        assertTrue(font.fontName.contains("Unbounded"), font.fontName)
    }
    @Test fun `fillPolygon paints a diamond and nothing outside it`() {
        val img = render(100) { it.fillRect(0, 0, 100, 100, 0x000000); it.fillPolygon(floatArrayOf(50f, 90f, 50f, 10f), floatArrayOf(10f, 50f, 90f, 50f), 0xFF0000) }
        assertEquals(0xFF0000, img.getRGB(50, 50) and 0xFFFFFF)
        assertEquals(0x000000, img.getRGB(5, 5) and 0xFFFFFF)
    }
    @Test fun `drawText paints with the bundled face, deterministic, anchored`() {
        val a = render(200) { it.fillRect(0,0,200,200,0x000000); it.drawText("SNAP", ArtFont.UNBOUNDED_EXTRABOLD, 40f, 100f, 120f, TextAnchor.MIDDLE, 0xFFFFFF) }
        val b = render(200) { /* same */ }
        assertTrue(bytesOf(a).contentEquals(bytesOf(b)))
        assertTrue(countNonBlack(a) > 300, "text was painted")
        // MIDDLE anchor: ink on both sides of x=100
        assertTrue(leftmostInk(a) < 100 && rightmostInk(a) > 100)
    }
    @Test fun `textWidth grows with size and matches drawn extent`() {
        val w20 = KitArt.AwtCanvas(g).textWidth("SNIP", ArtFont.UNBOUNDED_EXTRABOLD, 20f)
        val w40 = KitArt.AwtCanvas(g).textWidth("SNIP", ArtFont.UNBOUNDED_EXTRABOLD, 40f)
        assertTrue(w40 > w20 * 1.8f && w40 < w20 * 2.2f)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ArtCanvasTest'`
Expected: compilation failure (no `ArtFont`, no `fillPolygon`).

- [ ] **Step 4: Implement `ArtFont`, `TextAnchor`, `AwtFonts`, and the three primitives on `ArtCanvas` and `AwtCanvas`**

`fillPolygon` goes through `Graphics2D.fill(Path2D.Float)`. `drawText` uses `FontMetrics.stringWidth` for the anchor offset and `drawString` at the baseline. `textWidth` returns `FontMetrics.stringWidth(text).toFloat()`. The class-loader read: `ArtFont::class.java.classLoader.getResourceAsStream(resourcePath) ?: error("font resource missing: $resourcePath")`.

- [ ] **Step 5: Run the shell tests**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ArtCanvasTest' --tests 'com.snipsnap.shell.KitArtTest' --tests 'com.snipsnap.shell.JCardTest'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

Title: `Give the art canvas a polygon, a bundled face and a text measure`. Body: why the seam grows (the mark needs filled diamonds and real type; the pixel font stays for what uses it), why the font is a `:shell` resource (one copy for JVM and phone), the OFL note.

---

### Task 2: The `Mark` drawing routine

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/Mark.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/MarkTest.kt`

**Interfaces:**
- Consumes: `ArtCanvas`, `ArtFont.UNBOUNDED_EXTRABOLD`, `TextAnchor` (Task 1); `Scheme.ink`, `Scheme.accent`, `Scheme.lcd` (`Schemes.kt`).
- Produces:
  ```kotlin
  object Mark {
      enum class Variant(val id: String) { SYMBOL("symbol"), LOCKUP("lockup"), STACKED("stacked");
          companion object { fun byId(id: String): Variant? } }
      /** One pad of the symbol: centre and half-diagonal. */
      data class Diamond(val cx: Float, val cy: Float, val r: Float)
      /** The four pads of a symbol whose bounding box is [height] square with its top-left at ([x],[y]); order TL, TR, BL, BR. Pads touch: r = height / 4. */
      fun symbol(x: Float, y: Float, height: Float): List<Diamond>
      const val MIN_HEIGHT = 8f
      /** Unbounded ExtraBold's cap height as a fraction of the em; the lockups size type so caps match the mark height. */
      const val CAP_RATIO = 0.72f
      /** Gap between the wider word and the symbol in STACKED, as a fraction of height. */
      const val STACKED_GAP = 0.14f
      /** Gap each side of the symbol in LOCKUP, as a fraction of height. */
      const val LOCKUP_GAP = 0.25f
      /** The mark's width at [height] on [canvas] (text measured there). */
      fun width(canvas: ArtCanvas, variant: Variant, height: Float): Float
      /** Draw with the box's top-left at ([x],[y]) and the box [height] tall; width is [width]. Requires height >= MIN_HEIGHT. */
      fun draw(canvas: ArtCanvas, variant: Variant, x: Float, y: Float, height: Float, ink: Int, accent: Int)
      /** [ink]/[accent] for a scheme, with the accent replaced by `scheme.amber` when it equals the ink. */
      fun tones(scheme: Scheme): Pair<Int, Int>
  }
  ```
  SYMBOL: `symbol(x, y, height)` filled TL and BR in accent, TR and BL in ink. LOCKUP: type size `height / CAP_RATIO`; "SNIP" in ink ending at `x + wSnip`, symbol of `height` after `LOCKUP_GAP·height`, "SNAP" in accent after another gap; baseline at `y + height`. STACKED: two lines, each type size `(height − lineGap) / 2 / CAP_RATIO` where `lineGap = 0.12·height`, SNIP's baseline at `y + capTop + cap`, SNAP's at `y + height`, both at `x` with `TextAnchor.START`; the symbol's box is `height` tall at `x + max(wSnip, wSnap) + STACKED_GAP·height`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class MarkTest {
    @Test fun `the symbol is four touching pads filling its box`() {
        val d = Mark.symbol(10f, 20f, 40f)
        assertEquals(4, d.size)
        assertEquals(listOf(10f, 20f), listOf(d.minOf { it.cx - it.r }, d.minOf { it.cy - it.r }))
        assertEquals(listOf(50f, 60f), listOf(d.maxOf { it.cx + it.r }, d.maxOf { it.cy + it.r }))
        assertEquals(d[0].cx + d[0].r, d[1].cx - d[1].r, "TL's right point is TR's left point")
        assertEquals(d[0].cy + d[0].r, d[2].cy - d[2].r, "TL's bottom point is BL's top point")
    }
    @Test fun `every variant renders deterministic with both tones present`() {
        for (v in Mark.Variant.entries) for (h in listOf(16f, 120f)) {
            val a = png(v, h, ink = 0xF2F2F2, accent = 0xFF7A3D); val b = png(v, h, ...)
            assertTrue(a.contentEquals(b), "$v@$h")
            assertTrue(hasColour(a, 0xF2F2F2) && hasColour(a, 0xFF7A3D), "$v@$h shows ink and accent")
        }
    }
    @Test fun `the stacked symbol is as tall as the two lines and sits after the wider word`() {
        // draw STACKED at height 100 through a recording ArtCanvas that captures fillPolygon and drawText calls
        val rec = RecordingCanvas(); Mark.draw(rec, Mark.Variant.STACKED, 0f, 0f, 100f, 1, 2)
        assertEquals(0f, rec.polygons.minOf { it.minY }); assertEquals(100f, rec.polygons.maxOf { it.maxY })
        val wider = rec.texts.maxOf { it.measuredWidth }
        assertEquals(wider + Mark.STACKED_GAP * 100f, rec.polygons.minOf { it.minX }, 0.01f)
        assertEquals(listOf(0f, 0f), rec.texts.map { it.x }, "both words flush left")
    }
    @Test fun `the lockup puts the symbol at cap height between the words`() { /* RecordingCanvas: symbol box height == 100, SNIP ends before symbol minX − LOCKUP_GAP·100, SNAP starts after maxX + LOCKUP_GAP·100, SNAP's colour is accent */ }
    @Test fun `width matches the drawn extent`() { /* for each variant: width() == recorded maxX of polygons/texts (±0.5) */ }
    @Test fun `the symbol keeps two tones on every scheme`() {
        for (s in Schemes.ALL) { val (ink, accent) = Mark.tones(s); assertTrue(ink != accent, s.id.toString()) }   // Schemes.ALL: Schemes.kt:326
    }
    @Test fun `a mark under eight pixels is refused`() {
        assertFailsWith<IllegalArgumentException> { Mark.draw(RecordingCanvas(), Mark.Variant.SYMBOL, 0f, 0f, 7.9f, 1, 2) }
    }
}
```
`RecordingCanvas` is a test-only `ArtCanvas` that stores polygon point sets and text calls (with `textWidth` computed through a real `AwtCanvas` so the layout is honest). Check `Schemes.kt` for the name of the all-schemes list and use it.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.MarkTest'`
Expected: compilation failure (no `Mark`).

- [ ] **Step 3: Implement `Mark` in `shell/src/main/kotlin/com/snipsnap/shell/Mark.kt`**

Diamonds are filled from their four points (`cx, cy−r`, `cx+r, cy`, `cx, cy+r`, `cx−r, cy`), never through a rotation.

- [ ] **Step 4: Run the tests**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.MarkTest'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Title: `Draw the SnipSnap mark: the pad cluster and its two lockups`. Body: the owner's geometry decisions and why the cluster is built from points on its own diagonal.

---

### Task 3: `snipsnap mark` renders the three variants

**Files:**
- Create: `cli/src/main/kotlin/com/snipsnap/cli/MarkCommand.kt`
- Modify: `cli/src/main/kotlin/com/snipsnap/cli/Main.kt:441` (add `"mark" -> MarkCommand.run(args.drop(1), out)` beside `"art"`), and the usage text that lists `art`
- Test: `cli/src/test/kotlin/com/snipsnap/cli/MarkCommandTest.kt`

**Interfaces:**
- Consumes: `Mark.draw`, `Mark.width`, `Mark.tones`, `KitArt.AwtCanvas`, `Schemes`, `ArtCommand.parseScheme` (private today at ArtCommand.kt:68; make it `internal`).
- Produces: `snipsnap mark [--out DIR] [--scheme ID] [--size N]` writing `mark_symbol.png`, `mark_lockup.png`, `mark_stacked.png`: each a PNG on the scheme's `lcd` ground, the mark at `size` px tall with `size/4` margin, canvas width fitted to `Mark.width`. Default size 120, range 16..1024.

- [ ] **Step 1: Write the failing test** (follow `CliTest.kt`'s way of running a verb into a temp dir)

```kotlin
@Test fun `mark writes three PNGs sized to the mark`() {
    run("mark", "--out", dir.path, "--size", "64")
    for (v in listOf("symbol", "lockup", "stacked")) {
        val img = ImageIO.read(File(dir, "mark_$v.png"))
        assertEquals(64 + 32, img.height, v)
        assertTrue(img.width >= 96, v)
    }
    assertEquals(ImageIO.read(File(dir, "mark_symbol.png")).width, 64 + 32, "the symbol is square")
}
@Test fun `mark refuses a size outside 16..1024`() { assertFailsWith<CliError> { run("mark", "--size", "8") } }
```

- [ ] **Step 2: Run to verify failure** — `./gradlew --no-daemon :cli:test --tests 'com.snipsnap.cli.MarkCommandTest'` → unknown command / compile error.
- [ ] **Step 3: Implement `MarkCommand.run(args: List<String>, out: PrintStream): Int`** using `Options.parse` like `ArtCommand`.
- [ ] **Step 4: Run** `./gradlew --no-daemon :cli:test` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `Render the mark from the command line`.

---

### Task 4: The Android canvas draws polygons and bundled type

**Files:**
- Modify: `app/build.gradle.kts` (android `sourceSets["main"].assets.srcDir("../shell/src/main/resources")`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/AndroidKitArt.kt` (`png` gains `assets: AssetManager`; `AndroidCanvas(canvas, assets)` implements the three primitives)
- Modify: `app/src/main/kotlin/com/snipsnap/app/AndroidJCard.kt` (same `assets` parameter, passed to `AndroidCanvas`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/ExportScreen.kt:305-306, 554` (pass `context.assets`)
- Create: `app/src/main/kotlin/com/snipsnap/app/AndroidFonts.kt`
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/AndroidCanvasTest.kt`

**Interfaces:**
- Consumes: `ArtFont`, `TextAnchor`, `ArtCanvas` (Task 1).
- Produces:
  ```kotlin
  object AndroidFonts { fun typeface(assets: AssetManager, font: ArtFont): Typeface }   // Typeface.createFromAsset(assets, font.resourcePath), cached per font
  object AndroidKitArt { fun png(kit, kitDir, style, scheme = Schemes.DEFAULT, seed = 0, size = KitArt.DEFAULT_SIZE, label = kit.name, assets: AssetManager): ByteArray }
  object AndroidJCard  { fun png(kit, kitDir, …, assets: AssetManager): ByteArray }
  internal class AndroidCanvas(canvas: Canvas, assets: AssetManager) : ArtCanvas
  ```
  `fillPolygon` → `Path` + `drawPath` with the fill paint (antialias on). `drawText` → a text `Paint` with the typeface, `textSize = sizePx`, `textAlign` from the anchor (LEFT/CENTER/RIGHT), `drawText(text, x, baselineY, paint)`. `textWidth` → `paint.measureText`.

- [ ] **Step 1: Write the failing instrumented test**

```kotlin
@RunWith(AndroidJUnit4::class) class AndroidCanvasTest {
    @Test fun theFontAssetIsPacked() { InstrumentationRegistry.getInstrumentation().targetContext.assets.open("fonts/Unbounded-ExtraBold.ttf").use { assertTrue(it.read() >= 0) } }
    @Test fun polygonAndTextPaint() { /* 200px ARGB bitmap; AndroidCanvas(canvas, assets); fillRect black; fillPolygon red diamond; drawText "SNAP" white MIDDLE at (100,150); assert centre pixel red, a white pixel exists below y=150-40, textWidth("SNAP",…,40f) > 60 */ }
    @Test fun markRendersOnThePhone() { /* Mark.draw(AndroidCanvas, STACKED, 10f,10f,80f, ink, accent) → both colours present */ }
}
```

- [ ] **Step 2: Gate the test compiles and fails to find the asset / primitives**

Run: `./gradlew --no-daemon :app:compileDebugAndroidTestKotlin` → compile error (missing `AndroidFonts`, constructor shape).

- [ ] **Step 3: Implement** the Gradle assets source dir, `AndroidFonts`, the primitives on `AndroidCanvas`, the `assets` parameter on both `png` functions, and the three call sites in `ExportScreen.kt`.

- [ ] **Step 4: Gates**

Run: `./gradlew --no-daemon :app:assembleDebug` then `./gradlew --no-daemon :app:compileDebugAndroidTestKotlin` → both BUILD SUCCESSFUL. Then `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep Unbounded` → the TTF is listed under `assets/fonts/`. The instrumented test runs in CI's emulator job.

- [ ] **Step 5: Commit** — `Let the phone's art canvas fill polygons and set bundled type`. Body: why the assets dir points at `:shell`'s resources (one font file, two loaders), why `png` takes an `AssetManager` (Typeface wants assets or a file, never a stream).

---

### Task 5: The launcher icon and the quick-settings tile icon are the symbol

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/drawable/ic_mark_symbol.xml`, `app/src/main/res/values/ic_launcher_background.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/AndroidManifest.xml:38-44` (`android:icon="@mipmap/ic_launcher"`, `android:roundIcon="@mipmap/ic_launcher_round"`), `:123` (tile service `android:icon="@drawable/ic_mark_symbol"`)
- Test: `app/src/test/kotlin/com/snipsnap/app/LauncherIconTest.kt` (plain JVM; `app/src/test` does not exist yet, create it; `:app:testDebugUnitTest` runs it)

**Interfaces:**
- Consumes: `Mark.symbol(x, y, height)` (Task 2); `Schemes.DEFAULT.lcd`, `.accent`, `.ink`.
- Produces: an adaptive icon. Foreground: a 108×108 viewport vector with four `<path>` elements, each `pathData` the diamond's four points from `Mark.symbol(30f, 30f, 48f)` (the 48 dp mark centred in the 66 dp safe zone), fill colours `Schemes.DEFAULT.accent` for TL and BR, `Schemes.DEFAULT.ink` for TR and BL, as `#RRGGBB` hex. Background: `ic_launcher_background` = `Schemes.DEFAULT.lcd`. `ic_mark_symbol.xml`: the same four paths on a 24×24 viewport from `Mark.symbol(2f, 2f, 20f)`, all four `?android:attr/colorControlNormal`-free: fill `#FFFFFF` (quick-settings tiles tint it).

- [ ] **Step 1: Write the failing test**

```kotlin
class LauncherIconTest {
    private fun paths(resFile: String): List<List<Pair<Float, Float>>>  // parse <path android:pathData="M x,y L x,y L x,y L x,y Z"> from app/src/main/res/<resFile>
    @Test fun `the foreground is the mark's own geometry`() {
        val expected = Mark.symbol(30f, 30f, 48f).map { d -> listOf(d.cx to d.cy - d.r, d.cx + d.r to d.cy, d.cx to d.cy + d.r, d.cx - d.r to d.cy) }
        assertEquals(expected, paths("drawable/ic_launcher_foreground.xml"))
    }
    @Test fun `the tile icon is the mark at twenty dp`() { assertEquals(Mark.symbol(2f, 2f, 20f).map{…}, paths("drawable/ic_mark_symbol.xml")) }
    @Test fun `the manifest points at the adaptive icon`() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:icon=\"@mipmap/ic_launcher\"" in m && "android:roundIcon=\"@mipmap/ic_launcher_round\"" in m)
        assertTrue("@android:drawable/ic_btn_speak_now" !in m, "no stock glyph left")
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.snipsnap.app.LauncherIconTest'` → FAIL (files absent).
- [ ] **Step 3: Write the five resource files and the two manifest attributes.** Format `pathData` as `M x,y L x,y L x,y L x,y Z` with the floats printed the way the test parses them (one decimal is enough; parse with `toFloat`).
- [ ] **Step 4: Run** the unit test and `./gradlew --no-daemon :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `Put the SnipSnap symbol on the launcher and the quick-settings tile`. Body: the stock speaker glyph is gone; the vector is pinned to `Mark` so the icon and the drawn mark cannot drift apart.

---

### Task 6: The title bar carries the one-line lockup

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/MarkLockup.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/Chrome.kt:249-256` (replace the tilted `Box` and `TapeText("SNIPSNAP", …)` with `MarkLockup(height = 15.dp)`)
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/TitleBarTest.kt`

**Interfaces:**
- Consumes: `Mark.symbol`, `Mark.CAP_RATIO`, `Mark.LOCKUP_GAP`, `Mark.tones` (Task 2); `AndroidFonts.typeface` (Task 4); `LocalScheme`, `Int.tape` (existing).
- Produces:
  ```kotlin
  @Composable fun MarkLockup(height: Dp, modifier: Modifier = Modifier)
  ```
  A `Row` with `Modifier.semantics(mergeDescendants = true) { contentDescription = "SNIPSNAP" }`: `BasicText("SNIP")` in the scheme's `titleInk`, a `Canvas` of `height × height` drawing `Mark.symbol` with the scheme's `tones`, `BasicText("SNAP")` in the accent tone; the texts use `FontFamily(Font(assets, ArtFont.UNBOUNDED_EXTRABOLD.resourcePath))` at `fontSize = (height.value / Mark.CAP_RATIO).sp` and `maxLines = 1`, `softWrap = false`; the row has `Modifier.wrapContentWidth(unbounded = true)` so it is never squeezed. In `TitleBar`, the lockup keeps the `weight(1f, fill = false)` row slot's first position so the project name still ellipsises first.

- [ ] **Step 1: Write the failing Compose test**

```kotlin
@RunWith(AndroidJUnit4::class) class TitleBarTest {
    @get:Rule val rule = createComposeRule()
    @Test fun theLockupIsNamedSnipSnap() {
        rule.setContent { TapeTheme { TitleBar(onGear = {}) } }   // use the app's theme wrapper name from TapeTheme.kt
        rule.onNodeWithContentDescription("SNIPSNAP").assertExists()
        rule.onNodeWithText("SNIP", substring = false).assertExists()
        rule.onNodeWithText("SNAP").assertExists()
    }
    @Test fun aLongProjectNameDoesNotSqueezeTheLockup() {
        rule.setContent { TapeTheme { Box(Modifier.width(320.dp)) { TitleBar(projectName = "A".repeat(80), onGear = {}) } } }
        val lockup = rule.onNodeWithContentDescription("SNIPSNAP").fetchSemanticsNode()
        val widthPx = lockup.size.width
        rule.setContent { TapeTheme { TitleBar(onGear = {}) } }
        assertEquals(rule.onNodeWithContentDescription("SNIPSNAP").fetchSemanticsNode().size.width, widthPx)
    }
}
```

- [ ] **Step 2: Gate** — `./gradlew --no-daemon :app:compileDebugAndroidTestKotlin` → compile error (no `MarkLockup`).
- [ ] **Step 3: Implement `MarkLockup` and swap it into `TitleBar`.** Remove the now-unused `rotate` import if nothing else uses it.
- [ ] **Step 4: Gates** — `./gradlew --no-daemon :app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL. The Compose tests run in CI's emulator job.
- [ ] **Step 5: Commit** — `Set the SnipSnap lockup in the title bar`. Body: replaces the tilted square and wordmark; accessible name unchanged on purpose.

---

### Task 7: The J-Card wears the symbol

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/JCard.kt:141-150` (`spine`: draw `Mark.Variant.SYMBOL` at the spine's left end, `0.6 * h` tall, vertically centred, with `Mark.tones(scheme)`; the text line starts after it plus `m`)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/JCardTest.kt`

**Interfaces:**
- Consumes: `Mark.draw`, `Mark.tones` (Task 2).

- [ ] **Step 1: Add the failing assertion** to a new test `the spine carries the mark`: render a card at width 600, find the spine band (use `JCard.heightFor` and the existing layout constants the test file already knows), assert pixels of both `Mark.tones(scheme)` colours appear inside the spine band's left `h` pixels.
- [ ] **Step 2: Run** `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.JCardTest'` → the new test FAILS, the determinism and ArtCanvas-parity tests still pass.
- [ ] **Step 3: Implement** the spine change.
- [ ] **Step 4: Run** `./gradlew --no-daemon :shell:test` → BUILD SUCCESSFUL (every shell test, not just J-Card: `KitArtTest` and `WearTest` touch the same canvas).
- [ ] **Step 5: Commit** — `Stamp the J-Card spine with the SnipSnap symbol`.

---

### Task 8: Whole-plan gates

- [ ] `./gradlew --no-daemon :shell:test :cli:test` → BUILD SUCCESSFUL.
- [ ] `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL.
- [ ] `./gradlew --no-daemon -q :cli:run --args="mark --out /tmp/mark-check --size 160"` and look at the three PNGs: the owner judges them before the PR.
