# M0 — Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `:app` Android module as a thin Compose shell over the eight proven Kotlin/JVM modules, so a phone can browse kit folders, tap a 4×4 pad grid, hear the WAVs, and flip TapeOS schemes.

**Architecture:** `:app` is a *binding layer*, not a rewrite. The scheme tables, layout constants, motion budget, shipped copy and every screen state machine already exist as tested pure Kotlin in `:shell`; the kit format, assembler and synth engines exist in `:kit`/`:synth`. This milestone adds Compose composables that read those objects, an Android storage root, and a `SoundPool` adapter. No colour, string, or algorithm is retyped here — where this plan needs a value, it names the `:shell` constant that holds it.

**Tech Stack:** Kotlin 2.0.21 (unchanged from the eight existing modules), Android Gradle Plugin 8.13.2, Gradle 8.14.3 (existing wrapper), Jetpack Compose BOM 2024.12.01 (foundation only — **no Material**), Java 17, minSdk 29 / compileSdk 35.

## Global Constraints

Every task's requirements implicitly include this section.

- **Kotlin stays at 2.0.21.** All eight modules declare it; `:app` matches. Do **not** bump Kotlin, Gradle, or any existing module's build file. The 525 green tests are out of scope and must stay green.
- **Android:** `minSdk = 29`, `compileSdk = 35`, `targetSdk = 35`, `namespace`/`applicationId` = `com.snipsnap.app`, Java 17 source/target.
- **Versions, exact:** AGP `8.13.2` · Compose BOM `2024.12.01` · Kotlin Compose compiler plugin `2.0.21` · activity-compose `1.9.3` · core-ktx `1.15.0` · lifecycle-runtime-ktx `2.8.7`.
- **No Material.** TapeOS is a complete design system with its own surfaces; Material3 would fight it. Use `androidx.compose.foundation` only — `BasicText`, `Box`, `Row`, `Column`, `Canvas`. If you reach for `androidx.compose.material3.*`, stop: the composable you need is a TapeOS one from Task 2. This binds the **dependency graph**, not just imports: `androidx.compose.material*` must not appear on any configuration, including debug-only ones. (`ui-tooling` pulls it in transitively, which is why M0 ships without it — see Task 1's dependency block.)
- **`:app` declares its own module dependencies.** `:shell` uses `implementation`, not `api`, so nothing reaches `:app` transitively. `:app` declares all six it touches: `:json :xpm :audio :kit :synth :shell`. (`docs/APP_PLAN.md` says "all six modules" — that sentence predates `:shell` and `:synth`; this list governs.)
- **No algorithm in `:app`.** If a screen needs logic, it lands in a module with tests first. `:app` holds Compose bindings, Android adapters, and nothing else.
- **No `Context` in any constructor.** App-layer classes take `java.io.File` roots and plain data. `Context` appears only in named adapter files (`MainActivity.kt`, `PadPlayer.kt`, `Settings.kt`). This is what keeps the kit repository, the nav reducer and the token mapping testable as plain JUnit.
- **No re-stated design values.** Precisely:
  - **Colours:** every colour comes from `Schemes` via `toColor()`. A literal hex colour in `:app` is a defect — the one exception is the modal scrim in `NewTapeDialog` (`Color(0xCC000000)`), which is a translucent black, not a scheme colour.
  - **Named layout constants:** where `Layout` has a constant for something, use it — `TITLEBAR_H`, `MENU_ROW_H`, `STATUS_BAR_H`, `LCD_HEADER_H`, `OUTER_MARGIN`, `PAD_H`, `PAD_GAP`, `PAD_RADIUS`, `PRIMARY_ACTION_H`, `MIN_HIT_TARGET`. Hardcoding `34.dp` for the titlebar is a defect. Incidental padding, spacing and corner radii that `Layout` does **not** name (a 10.dp gutter, a 4.dp corner) are ordinary Compose values and are fine inline — do not invent new `:shell` constants for them in M0.
  - **Durations:** from `Motion`. **Typefaces:** from `TapeFonts`, which maps `:shell`'s `Type` names.
  - **Personality copy** — toasts, quips, empty states, easter eggs — comes from `Copy` and is never inlined. Plain screen furniture that `Copy` does not define ("MY KITS", "BANK A", "CANCEL") is written inline; do not add it to `:shell` in M0.
- **Legal guardrail (from `docs/SYNTH_ROADMAP.md`):** no trademarked machine names or model numbers anywhere — code, comments, test names, commit messages, or UI copy. Describe sounds, never brands.
- **Tests:** JUnit 5 via `useJUnitPlatform()`, matching the other modules. All of it runs with `./gradlew :app:testDebugUnitTest` — no emulator, no instrumentation.
  - What **must** be unit-tested: every non-Compose class and function in `:app` — token conversion, the nav reducer, `KitLibrary`, `slotForCell`/`cellForSlot`, `verifyKitName`, `FileSettings`. This is the whole reason `Context` is kept out of constructors.
  - What is **not** unit-tested in M0: `@Composable` functions and the `SoundPool` adapter. There is no Compose test harness or Robolectric in this project and M0 does not add one — those are verified by the emulator checks each task ends with, and by the exit test. A reviewer should not treat an untested composable as missing coverage; it should treat *logic hiding inside* a composable as a defect, since that logic belongs in a testable function.

**Environment (already provisioned, do not re-install):**
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
- Installed: `platforms;android-35`, `build-tools;35.0.0`, `platform-tools` (37.0.1), `emulator` (37.1.11), `system-images;android-35;google_apis;arm64-v8a`
- AVD `snipsnap_pixel` exists — 1080×2400 @ 420dpi (411×914dp, close to the 390dp design width)
- Every `./gradlew` command must be preceded by `eval "$(fnm env)" && fnm use 20` per `CLAUDE.md`.

---

### Task 1: The Android module, and the locale defect the port exposes

Scaffolding `:app` is the easy half. The load-bearing half is the audit nobody has run: the eight modules have only ever executed on a desktop JVM under an English locale. A grep for desktop-only APIs (`javax.sound`, `java.awt`, `java.beans`, `javax.swing`) comes back clean — but `String.format`'s `%d` conversion **localizes digits**, and the kit pipeline builds every sample filename with `%02d`. Verified on this machine:

```
locale   String.format("%s%02d","A",3)   String.format("%s_%s_%02d","A01","Kick",1)
ar-EG    A٠٣                              A01_Kick_٠١
fa-IR    A۰۳                              A01_Kick_۰۱
en-US    A03                              A01_Kick_01
```

On an Arabic-, Persian- or Burmese-locale phone every generated WAV filename gets Eastern-Arabic digits. `KitPad`'s validation permits them (it only forbids `/` and `\`), so they reach `kit.json` and the MPC program, where `Names.isMpcSafe` finally rejects them at Preflight — long after the files exist. `%x` is **not** localized, so `Json.kt:99` and `Mpc3Json.kt:74` are safe and must not be touched.

The fix is `Locale.ROOT` on the five sites that produce filenames or program data, in the modules that own them.

**Files:**
- Modify: `settings.gradle.kts` (add `pluginManagement` block, `include(":app")`)
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/snipsnap/app/MainActivity.kt`
- Modify: `xpm/src/main/kotlin/com/snipsnap/xpm/PadNoteMap.kt:53`
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/KitAssembler.kt:74,102`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt:72,137`
- Test: `xpm/src/test/kotlin/com/snipsnap/xpm/PadNoteMapLocaleTest.kt`
- Test: `kit/src/test/kotlin/com/snipsnap/kit/KitAssemblerLocaleTest.kt`
- Test: `app/src/test/kotlin/com/snipsnap/app/CorePortTest.kt`

**Interfaces:**
- Consumes: `PadNoteMap.labelForPad(padNumber: Int): String`; `KitAssembler.assembleArranged(name: String, arranged: List<ArrangedPad?>, dir: File): Kit`; `ThumpKits.classic(): List<ArrangedPad?>` (16 non-null pads, A01–A16); `KitStore.load(dir: File): Kit`
- Produces: a buildable `:app` module with `com.snipsnap.app` as its package root; `PadNoteMap.labelForPad` and `KitAssembler` filenames guaranteed ASCII under any default locale.

- [ ] **Step 1: Write the failing locale tests in the modules that own the defect**

`xpm/src/test/kotlin/com/snipsnap/xpm/PadNoteMapLocaleTest.kt`:

```kotlin
package com.snipsnap.xpm

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pad labels become filenames, and filenames go on a FAT card an MPC has to
 * browse. `String.format`'s `%d` follows the default locale, so on an
 * Arabic- or Persian-locale phone an unpinned `%02d` emits Eastern-Arabic
 * digits — a bug no desktop-JVM test can see.
 */
class PadNoteMapLocaleTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    @Test
    fun `pad labels stay ASCII under an eastern-digit locale`() {
        for (tag in listOf("ar-EG", "fa-IR", "my-MM")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals("A01", PadNoteMap.labelForPad(1), "locale $tag")
            assertEquals("A03", PadNoteMap.labelForPad(3), "locale $tag")
            assertEquals("A16", PadNoteMap.labelForPad(16), "locale $tag")
            assertEquals("B01", PadNoteMap.labelForPad(17), "locale $tag")
        }
    }
}
```

`kit/src/test/kotlin/com/snipsnap/kit/KitAssemblerLocaleTest.kt`:

```kotlin
package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class KitAssemblerLocaleTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    // Snip(samples, channels, sampleRate) — channels comes second; passing
    // the rate there trips `require(channels in 1..2)`.
    private fun tone(): Snip = Snip(FloatArray(2048) { 0.2f }, 1, 44100)

    @Test
    fun `sample stems and display names stay ASCII under an eastern-digit locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val dir = Files.createTempDirectory("kit-locale").toFile()
        val arranged = listOf(
            ArrangedPad(tone(), DrumClass.KICK),
            ArrangedPad(tone(), DrumClass.KICK),
            ArrangedPad(tone(), DrumClass.SNARE),
        )
        val kit = KitAssembler.assembleArranged("LOCALE KIT", arranged, dir)
        for (pad in kit.pads) {
            assertTrue(
                pad.sampleFile.all { it.code in 32..126 },
                "non-ASCII in sample filename: ${pad.sampleFile}",
            )
            assertTrue(
                pad.displayName.all { it.code in 32..126 },
                "non-ASCII in display name: ${pad.displayName}",
            )
            assertTrue(Names.isMpcSafe(pad.sampleStem), "not MPC-safe: ${pad.sampleStem}")
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
eval "$(fnm env)" && fnm use 20
./gradlew :xpm:test --tests '*PadNoteMapLocaleTest*' :kit:test --tests '*KitAssemblerLocaleTest*'
```

Expected: FAIL — `expected:<A01> but was:<A٠١>` and "non-ASCII in sample filename".

- [ ] **Step 3: Pin `Locale.ROOT` on the five sites that produce filenames or program data**

`xpm/.../PadNoteMap.kt:53` — replace `return "%s%02d".format(bank, within)` with:

```kotlin
        // Locale.ROOT, not the default: `%d` localizes digits, and a pad
        // label whose "01" arrives as "٠١" becomes a filename no MPC will
        // browse. Every label here is an ASCII identifier, not display text.
        return String.format(java.util.Locale.ROOT, "%s%02d", bank, within)
```

`kit/.../KitAssembler.kt:74` — replace `val stem = "%s_%s_%02d".format(label, className, n)` with:

```kotlin
            val stem = String.format(Locale.ROOT, "%s_%s_%02d", label, className, n)
```

`kit/.../KitAssembler.kt:102` — replace `displayName = "%s %02d".format(className, n),` with:

```kotlin
                displayName = String.format(Locale.ROOT, "%s %02d", className, n),
```

`shell/.../KitBuilder.kt:72` — replace the `?: "%s %02d".format(...)` fallback with:

```kotlin
                ?: String.format(Locale.ROOT, "%s %02d", AutoPlace.nameFor(drumClass), classCount(drumClass) + 1),
```

`shell/.../KitBuilder.kt:137` — replace `val stem = Names.sanitizeStem("%s_%02d".format(base, n))` with:

```kotlin
            val stem = Names.sanitizeStem(String.format(Locale.ROOT, "%s_%02d", base, n))
```

Add `import java.util.Locale` to `KitAssembler.kt` and `KitBuilder.kt` (`PadNoteMap.kt` uses the fully-qualified name above, so it needs no import — or add one and shorten the call, either is fine).

`KitBuilder.kt:134` (`"%s_%s".format(...)`) has no numeric conversion and is already locale-safe — leave it. **Do not** touch `json/Json.kt:99`, `mpc3/Mpc3Json.kt:74`, `mpc3/Acvs.kt:117`, or any `cli/` site: `%x` does not localize, and the CLI's `%f` output is desktop console text, not data.

- [ ] **Step 4: Run the locale tests to verify they pass, then the whole suite**

```bash
./gradlew :xpm:test --tests '*PadNoteMapLocaleTest*' :kit:test --tests '*KitAssemblerLocaleTest*'
./gradlew test
```

Expected: the two new tests PASS; the full suite stays green (525 tests before these two).

- [ ] **Step 5: Commit the core fix on its own**

```bash
git add xpm/src kit/src shell/src
git commit -m "Pin Locale.ROOT where format strings build filenames

The kit pipeline names every WAV with %02d. String.format follows the
default locale, so on an Arabic-, Persian- or Burmese-locale phone the
counter arrives as Eastern-Arabic digits and the filename stops being
MPC-safe — a bug the desktop suite could never see, surfaced by porting
the modules to Android. %x is not localized, so the JSON writers stay
as they are."
```

- [ ] **Step 6: Add the `pluginManagement` block and `:app` to `settings.gradle.kts`**

The Android Gradle Plugin lives in Google's Maven repo, which Gradle's default plugin resolution does not consult. Prepend this block **above** `rootProject.name` (a `pluginManagement` block must be the first thing in the file):

```kotlin
// The Android Gradle Plugin resolves from Google's Maven, not the plugin
// portal — :app cannot apply com.android.application without this.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Then add to the bottom of the existing `include` list:

```kotlin
include(":app")
```

Leave the eight existing `include` lines and `rootProject.name` untouched. Do **not** add a `dependencyResolutionManagement` block — every module in this repo declares its own `repositories {}`, and `:app` will follow that pattern.

- [ ] **Step 7: Write `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.13.2"
    kotlin("android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.snipsnap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snipsnap.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-m0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// Java 17 bytecode, matching the eight modules this app is a shell over.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // :shell uses `implementation`, so none of these arrive transitively —
    // the app declares every module it touches.
    implementation(project(":json"))
    implementation(project(":xpm"))
    implementation(project(":audio"))
    implementation(project(":kit"))
    implementation(project(":synth"))
    implementation(project(":shell"))

    // Foundation only. TapeOS is a complete design system; Material would
    // fight it at every surface.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Deliberately absent: androidx.compose.ui:ui-tooling and
    // ui-tooling-preview. They exist to serve @Preview in Android Studio,
    // M0 writes no @Preview, and ui-tooling drags
    // androidx.compose.material onto the debug classpath — which the
    // no-Material constraint forbids. A later milestone that actually
    // wants previews can add them back with an
    // `exclude(group = "androidx.compose.material")`.

    // JUnit 5, as in every other module. Named explicitly rather than via
    // kotlin("test") so the platform launcher is on the runtime classpath.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
```

- [ ] **Step 8: Write the manifest and a minimal activity**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="SnipSnap"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

`app/src/main/kotlin/com/snipsnap/app/MainActivity.kt`:

```kotlin
package com.snipsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Schemes

/**
 * The one Android entry point. Everything it hosts is a binding over
 * `:shell` — this class holds the `Context` so nothing else has to.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = Schemes.DEFAULT
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(scheme.lcd or 0xFF000000.toInt())),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = Copy.BOOT_READY,
                    style = TextStyle(color = Color(scheme.lcdInk or 0xFF000000.toInt())),
                )
            }
        }
    }
}
```

- [ ] **Step 9: Write the port check — the test that proves the core runs here**

`app/src/test/kotlin/com/snipsnap/app/CorePortTest.kt`:

```kotlin
package com.snipsnap.app

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.ThumpKits
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The architectural bet, made checkable: every algorithm this app calls is
 * pure Kotlin/JVM, so rendering a whole factory kit must work from inside
 * the Android module exactly as it does in `:kit`'s own suite. If the core
 * ever reaches for a desktop-only API, this is the test that says so.
 */
class CorePortTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    private fun tempDir(): File = Files.createTempDirectory("snipsnap-port").toFile()

    @Test
    fun `the factory kit renders and assembles from inside the app module`() {
        val dir = tempDir()
        val kit = KitAssembler.assembleArranged("SNIPSNAP KIT 01", ThumpKits.classic(), dir)

        assertEquals(16, kit.pads.size)
        assertTrue(File(dir, "kit.json").isFile, "kit.json was not written")
        for (pad in kit.pads) {
            val wav = File(dir, pad.sampleFile)
            assertTrue(wav.isFile, "missing ${pad.sampleFile}")
            assertTrue(wav.length() > 44, "${pad.sampleFile} is header-only")
        }

        // The folder is the kit: reloading it must give the same pads back.
        assertEquals(kit.pads.map { it.slot }, KitStore.load(dir).pads.map { it.slot })
    }

    @Test
    fun `generated filenames stay ASCII under an eastern-digit locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val dir = tempDir()
        val kit = KitAssembler.assembleArranged("SNIPSNAP KIT 01", ThumpKits.classic(), dir)
        for (pad in kit.pads) {
            assertTrue(
                pad.sampleFile.all { it.code in 32..126 },
                "non-ASCII in generated filename: ${pad.sampleFile}",
            )
            assertTrue(File(dir, pad.sampleFile).isFile)
        }
    }
}
```

- [ ] **Step 10: Write `local.properties` (already git-ignored)**

```bash
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
```

Confirm it is ignored — `.gitignore` already lists `local.properties`; `git status --porcelain` must not show it.

- [ ] **Step 11: Build and test**

```bash
eval "$(fnm env)" && fnm use 20
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, and both `CorePortTest` tests PASS.

If `assembleDebug` fails with a Kotlin metadata mismatch from a Compose artifact ("class file was compiled by a newer Kotlin"), the BOM is too new for Kotlin 2.0.21 — step **down** to `2024.10.01` and retry. Do not bump Kotlin; that would put all eight modules at risk for a milestone that needs no modern Compose API.

- [ ] **Step 12: Commit the scaffold**

```bash
git add settings.gradle.kts app
git commit -m "Scaffold :app — Compose over the eight proven modules

An Android module that depends on :json :xpm :audio :kit :synth :shell
directly, because :shell exports none of them transitively. Foundation
only, no Material: TapeOS brings its own surfaces. CorePortTest renders
the whole factory kit from inside the Android module, which is how we
find out that the pure-Kotlin bet holds."
```

---

### Task 2: The TapeOS theme — tokens, fonts, bevels, two surfaces

`:shell` already holds the six scheme tables, the four font names, the layout constants and the motion budget as tested Kotlin. This task binds them to Compose: packed `0xRRGGBB` ints become `Color`, the four typefaces become a `FontFamily` set loaded from bundled TTFs, and the bevel/two-surface rules become reusable modifiers and composables.

Fonts are **bundled**, not downloadable. `androidx.compose.ui.text.googlefonts` needs the Play Services font provider, and the AVD runs `google_apis` (no Play Store) — downloadable fonts would fail in exactly the iteration loop this milestone depends on.

**Files:**
- Create: `app/src/main/res/font/vt323.ttf`, `silkscreen.ttf`, `michroma.ttf`, `permanent_marker.ttf`
- Create: `app/licenses/OFL-vt323.txt`, `OFL-silkscreen.txt`, `OFL-michroma.txt`, `APACHE-permanentmarker.txt`, `README.md`
- Create: `app/src/main/kotlin/com/snipsnap/app/theme/Tokens.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/theme/Fonts.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/theme/Bevel.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/theme/Surfaces.kt`
- Test: `app/src/test/kotlin/com/snipsnap/app/theme/TokensTest.kt`

**Interfaces:**
- Consumes: `com.snipsnap.shell.Scheme` (17 fields: `id, cssClass, gray, grayHi, grayEdge, grayMid, grayDark, ink, ink2, title1, title2, titleInk, desk1, desk2, lcd, lcdInk, amber, field`), `Schemes.ALL`, `Schemes.DEFAULT`, `Schemes[id]`, `Schemes.OILSLICK_SWEEP`, `Schemes.classColor(DrumClass)`, `Schemes.padLabelInk(Scheme, DrumClass)`, `Scheme.luma(Int)`, `SchemeId`, `Layout`, `Type`, `Motion`
- Produces:
  - `fun Int.toColor(): Color` — packed `0xRRGGBB` → opaque `Color`
  - `val LocalScheme: ProvidableCompositionLocal<Scheme>`
  - `@Composable fun TapeOsTheme(scheme: Scheme, content: @Composable () -> Unit)`
  - `fun Scheme.tokens(): List<Pair<String, Int>>`
  - `object TapeFonts { val lcd: FontFamily; val pixel: FontFamily; val display: FontFamily; val marker: FontFamily }`
  - `@Composable fun Modifier.raised(inset: Dp = 2.dp): Modifier`
  - `@Composable fun Modifier.pressed(inset: Dp = 2.dp): Modifier`
  - `@Composable fun Modifier.sunken(inset: Dp = 2.dp): Modifier`

    `inset` is the **bevel edge thickness**, not a corner radius. The bevel
    draws square edges; rounding is a separate concern composed by the
    caller — `Modifier.clip(RoundedCornerShape(Layout.PAD_RADIUS.dp)).raised()`
    clips the bevel to rounded corners, which is how Task 5's pad grid gets
    its `r6` pads. Keeping them separate is why one bevel serves square
    chrome, rounded pads and pill-shaped cells alike.
  - `@Composable fun LcdSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit)`
  - `@Composable fun ChromeSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit)`

- [ ] **Step 1: Fetch the four typefaces and their licences**

Two naming rules apply and they conflict, so the files land in two places:

- `res/font/` accepts **only** `.ttf/.otf/.ttc/.xml`, and every filename must be lowercase letters, digits and underscores. `VT323-Regular.ttf` is illegal twice over; a stray `.txt` there is an **aapt2 build error**, not a warning.
- The OFL and Apache licences must still travel with the fonts, so they go in `app/licenses/` — a plain directory outside `res/`, untouched by the resource compiler.

```bash
mkdir -p app/src/main/res/font app/licenses
BASE=https://raw.githubusercontent.com/google/fonts/main

curl -sfL "$BASE/ofl/vt323/VT323-Regular.ttf"                        -o app/src/main/res/font/vt323.ttf
curl -sfL "$BASE/ofl/silkscreen/Silkscreen-Regular.ttf"              -o app/src/main/res/font/silkscreen.ttf
curl -sfL "$BASE/ofl/michroma/Michroma-Regular.ttf"                  -o app/src/main/res/font/michroma.ttf
curl -sfL "$BASE/apache/permanentmarker/PermanentMarker-Regular.ttf" -o app/src/main/res/font/permanent_marker.ttf

curl -sfL "$BASE/ofl/vt323/OFL.txt"                  -o app/licenses/OFL-vt323.txt
curl -sfL "$BASE/ofl/silkscreen/OFL.txt"             -o app/licenses/OFL-silkscreen.txt
curl -sfL "$BASE/ofl/michroma/OFL.txt"               -o app/licenses/OFL-michroma.txt
curl -sfL "$BASE/apache/permanentmarker/LICENSE.txt" -o app/licenses/APACHE-permanentmarker.txt

ls -la app/src/main/res/font/ app/licenses/
```

All four TTFs must be non-empty (tens to hundreds of KB each). Write `app/licenses/README.md`:

```markdown
# Bundled typefaces

The four TapeOS faces, vendored from github.com/google/fonts so the app
never needs the Play Services font provider (the development emulator
image has no Play Store).

| Face | File | Licence |
|---|---|---|
| VT323 | `res/font/vt323.ttf` | SIL Open Font License 1.1 — `OFL-vt323.txt` |
| Silkscreen | `res/font/silkscreen.ttf` | SIL Open Font License 1.1 — `OFL-silkscreen.txt` |
| Michroma | `res/font/michroma.ttf` | SIL Open Font License 1.1 — `OFL-michroma.txt` |
| Permanent Marker | `res/font/permanent_marker.ttf` | Apache License 2.0 — `APACHE-permanentmarker.txt` |
```

- [ ] **Step 1b: Prove the resources compile before writing any Kotlin against them**

A bad filename under `res/` fails at resource-link time with a message that has nothing to do with fonts, so catch it now rather than at Step 9.

```bash
eval "$(fnm env)" && fnm use 20
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL, and `R.font.vt323` et al. now exist.

- [ ] **Step 2: Write the failing token test**

`app/src/test/kotlin/com/snipsnap/app/theme/TokensTest.kt`:

```kotlin
package com.snipsnap.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokensTest {

    @Test
    fun `packed rgb becomes an opaque colour`() {
        assertEquals(Color(0xFFC3C7CB), 0xC3C7CB.toColor())
        assertEquals(Color(0xFF000000), 0x000000.toColor())
        assertEquals(Color(0xFFFFFFFF), 0xFFFFFF.toColor())
    }

    @Test
    fun `every scheme token survives the trip to Compose`() {
        // Compare packed ints via toArgb(), not reconstructed float
        // channels: Color stores each channel as a Float, so `red * 255`
        // can land at 194.99999 for 0xC3 and truncate to 194.
        for (scheme in Schemes.ALL) {
            for ((name, rgb) in scheme.tokens()) {
                assertEquals(
                    rgb or 0xFF000000.toInt(),
                    rgb.toColor().toArgb(),
                    "$name in ${scheme.id} round-tripped wrong",
                )
            }
        }
    }

    /**
     * The two-surface rule as a *relative* invariant: in every scheme the
     * LCD is darker than the chrome it sits on.
     *
     * This is stricter than `:shell`'s own `SchemesTest`, which asserts the
     * absolute `luma(lcd) < 40`. OILSLICK's chrome is `0x221A34` — luma ≈ 31,
     * already under that threshold — so an LCD anywhere in the 31–40 band
     * would pass `:shell` and still be lighter than the surface around it.
     * That band is what this test closes.
     *
     * What it does *not* cover: it reads the raw `Int` fields, so it says
     * nothing about `toColor()` or about `LcdSurface`/`ChromeSurface`
     * picking the right field. Those two lines of wiring are trusted by
     * inspection until there is a Compose test harness to check them.
     */
    @Test
    fun `the lcd is darker than the chrome in every scheme`() {
        for (scheme in Schemes.ALL) {
            assertTrue(
                Scheme.luma(scheme.lcd) < Scheme.luma(scheme.gray),
                "${scheme.id}: LCD is not darker than chrome",
            )
        }
    }
}
```

Add this helper to `Tokens.kt` in Step 4 — the test needs it to enumerate tokens without naming each one twice:

```kotlin
/** Every colour token, paired with its field name, for exhaustive checks. */
fun Scheme.tokens(): List<Pair<String, Int>> = listOf(
    "gray" to gray, "grayHi" to grayHi, "grayEdge" to grayEdge,
    "grayMid" to grayMid, "grayDark" to grayDark,
    "ink" to ink, "ink2" to ink2,
    "title1" to title1, "title2" to title2, "titleInk" to titleInk,
    "desk1" to desk1, "desk2" to desk2,
    "lcd" to lcd, "lcdInk" to lcdInk, "amber" to amber, "field" to field,
)
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests '*TokensTest*'
```

Expected: FAIL — unresolved reference `toColor`.

- [ ] **Step 4: Write `Tokens.kt`**

```kotlin
package com.snipsnap.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes

/**
 * The bridge between `:shell`'s scheme tables and Compose.
 *
 * Every colour in this app comes through here. `:shell` owns the values —
 * a colour that exists only in the UI layer cannot be regression-tested,
 * which is the whole reason the tables live in a JVM module.
 */

/** Packed `0xRRGGBB` (the form `:shell` stores) as an opaque Compose colour. */
fun Int.toColor(): Color = Color(this or 0xFF000000.toInt())

/** Every colour token, paired with its field name, for exhaustive checks. */
fun Scheme.tokens(): List<Pair<String, Int>> = listOf(
    "gray" to gray, "grayHi" to grayHi, "grayEdge" to grayEdge,
    "grayMid" to grayMid, "grayDark" to grayDark,
    "ink" to ink, "ink2" to ink2,
    "title1" to title1, "title2" to title2, "titleInk" to titleInk,
    "desk1" to desk1, "desk2" to desk2,
    "lcd" to lcd, "lcdInk" to lcdInk, "amber" to amber, "field" to field,
)

val LocalScheme: ProvidableCompositionLocal<Scheme> =
    staticCompositionLocalOf { Schemes.DEFAULT }

@Composable
fun TapeOsTheme(scheme: Scheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScheme provides scheme, content = content)
}
```

`Schemes.OILSLICK_SWEEP` is deliberately **not** bound here. No M0 surface draws the sweep — the titlebar uses the two-stop `title1 → title2` gradient every scheme has, OILSLICK included — and a brush nothing calls is dead code with a rotation bug waiting in it. It gets written when a screen needs it.

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests '*TokensTest*'
```

Expected: PASS (3 tests).

- [ ] **Step 6: Write `Fonts.kt`**

```kotlin
package com.snipsnap.app.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.snipsnap.app.R

/**
 * The four TapeOS typefaces, bundled rather than downloaded.
 *
 * `androidx.compose.ui.text.googlefonts` needs the Play Services font
 * provider; the development emulator image is `google_apis` (no Play
 * Store), so a downloadable font would fail in exactly the loop this
 * milestone is iterated in. `:shell`'s [com.snipsnap.shell.Type] names
 * which face goes where; this object supplies them.
 */
object TapeFonts {
    /** VT323 — everything on an LCD. */
    val lcd: FontFamily = FontFamily(Font(R.font.vt323))

    /** Silkscreen — pixel UI chrome, menu row, status bar. */
    val pixel: FontFamily = FontFamily(Font(R.font.silkscreen))

    /** Michroma — display headers and key actions. */
    val display: FontFamily = FontFamily(Font(R.font.michroma))

    /** Permanent Marker — handwriting on pads and cassette labels. */
    val marker: FontFamily = FontFamily(Font(R.font.permanent_marker))
}
```

- [ ] **Step 7: Write `Bevel.kt`**

The bevel is the TapeOS working surface: a light edge top-left, a dark edge bottom-right, inverted when pressed, inverted-and-recessed when sunken. Drawn with `drawBehind` so it costs no extra layout node.

```kotlin
package com.snipsnap.app.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The three bevel states of a TapeOS control. Two-pixel edges, light from
 * the top-left — the 1996 convention the whole design system is built on.
 *
 * Each takes its colours from [LocalScheme], so a control written once
 * looks right in all six schemes.
 */
private fun Modifier.bevel(
    body: Color,
    topLeft: Color,
    bottomRight: Color,
    inset: Dp,
): Modifier = this.drawBehind {
    val w = inset.toPx()
    drawRect(color = body)
    // Top and left edges.
    drawRect(color = topLeft, topLeft = Offset.Zero, size = Size(size.width, w))
    drawRect(color = topLeft, topLeft = Offset.Zero, size = Size(w, size.height))
    // Bottom and right edges.
    drawRect(
        color = bottomRight,
        topLeft = Offset(0f, size.height - w),
        size = Size(size.width, w),
    )
    drawRect(
        color = bottomRight,
        topLeft = Offset(size.width - w, 0f),
        size = Size(w, size.height),
    )
}

// All three add exactly `inset` to the content box in each axis — only
// *which* side differs. That is what makes a press look like a press:
// the content travels down-right by `inset` while the node's measured
// size never changes. Pad only the pressed state and a wrap-content
// button grows 2dp when you touch it and shrinks when you let go, which
// reads as a flinch rather than a click.

/** A button at rest: light top-left, dark bottom-right. */
@Composable
fun Modifier.raised(inset: Dp = 2.dp): Modifier {
    val s = LocalScheme.current
    return bevel(s.gray.toColor(), s.grayHi.toColor(), s.grayDark.toColor(), inset)
        .padding(bottom = inset, end = inset)
}

/** The same button held down: the light source flips, content shifts in. */
@Composable
fun Modifier.pressed(inset: Dp = 2.dp): Modifier {
    val s = LocalScheme.current
    return bevel(s.grayMid.toColor(), s.grayDark.toColor(), s.grayHi.toColor(), inset)
        .padding(top = inset, start = inset)
}

/** A well: lists, fields, anything content sits *inside*. */
@Composable
fun Modifier.sunken(inset: Dp = 2.dp): Modifier {
    val s = LocalScheme.current
    return bevel(s.field.toColor(), s.grayDark.toColor(), s.grayEdge.toColor(), inset)
        .padding(bottom = inset, end = inset)
}
```

- [ ] **Step 8: Write `Surfaces.kt` — the two-surface rule as composables**

```kotlin
package com.snipsnap.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The one TapeOS invariant, in code: **gray bevelled chrome is where you
 * work; the dark LCD is where sound lives.** Light schemes lighten the
 * chrome — the LCD stays dark in all six, which `SchemesTest` in `:shell`
 * and `TokensTest` here both enforce.
 *
 * Screens compose these two rather than painting their own backgrounds, so
 * the rule cannot drift one screen at a time.
 */

/** Readouts, waveforms, pad grids — anything that represents sound. */
@Composable
fun LcdSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val s = LocalScheme.current
    Box(modifier = modifier.background(s.lcd.toColor()), content = content)
}

/** Window bodies, button rows, dialogs — anything you operate. */
@Composable
fun ChromeSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val s = LocalScheme.current
    Box(modifier = modifier.background(s.gray.toColor()), content = content)
}
```

- [ ] **Step 9: Build and run the suite**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/res/font app/licenses app/src/main/kotlin/com/snipsnap/app/theme app/src/test
git commit -m "TapeOS theme: bind the scheme tables to Compose

Tokens, the four bundled typefaces, the three bevel states, and the
two-surface rule as composables. :shell keeps owning the values — this
layer only converts them, and TokensTest proves nothing inverts on the
way across. Fonts ship in res/font with their licences: the emulator
image has no Play Store, so downloadable fonts would fail in exactly the
loop we iterate in."
```

---

### Task 3: The SNIPSNAP.EXE window — titlebar, menu row, status bar

The chrome every screen sits inside, ported from `design/TapeOS Oilslick.dc.html` (lines 78–115 for the titlebar and menu, 505–509 for the status bar). Navigation state is a pure reducer so it is unit-testable without an emulator.

The nine menu items and three status cells, verbatim from the prototype:

| Menu | KITS · KIT · TAPE · CHOP · PLAY · SYNTH · EXPORT · ⚙ · HELP |
|---|---|
| Status | cell 1 fixed (tape), cell 2 fixed (snips), cell 3 flex (quip) |

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/nav/Screen.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/nav/NavState.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/SnipSnapWindow.kt`
- Test: `app/src/test/kotlin/com/snipsnap/app/nav/NavStateTest.kt`

**Interfaces:**
- Consumes: `Layout.TITLEBAR_H` (34), `Layout.MENU_ROW_H` (26), `Layout.STATUS_BAR_H` (26), `Layout.OUTER_MARGIN` (12); `Copy.STATUS_QUIPS`, `Copy.rotating(lines, n)`; `Personality`, `Delight.quipsEnabled(level)`; `LocalScheme`, `TapeFonts`, `toColor()` from Task 2
- Produces:
  - `enum class Screen(val menuLabel: String, val title: String)` — `KITS, KIT, TAPE, CHOP, PLAY, SYNTH, EXPORT, PROPS, HELP`
  - `data class NavState(val screen: Screen, val personality: Personality, val quipIndex: Int)`
  - `fun NavState.goTo(screen: Screen): NavState`
  - `fun NavState.tickQuip(): NavState`
  - `fun NavState.statusQuip(): String`
  - `@Composable fun SnipSnapWindow(state: NavState, onMenu: (Screen) -> Unit, tapeCell: String, snipCell: String, content: @Composable () -> Unit)`

- [ ] **Step 1: Write the failing nav test**

`app/src/test/kotlin/com/snipsnap/app/nav/NavStateTest.kt`:

```kotlin
package com.snipsnap.app.nav

import com.snipsnap.shell.Copy
import com.snipsnap.shell.Personality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NavStateTest {

    @Test
    fun `the menu row is the prototype's nine items in order`() {
        assertEquals(
            listOf("KITS", "KIT", "TAPE", "CHOP", "PLAY", "SYNTH", "EXPORT", "⚙", "HELP"),
            Screen.entries.map { it.menuLabel },
        )
    }

    @Test
    fun `the titlebar names the app everywhere except the tape deck`() {
        assertEquals("SNIPSNAP.EXE", Screen.KITS.title)
        assertEquals("SNIPSNAP.EXE", Screen.KIT.title)
        assertEquals("TAPE DECK", Screen.TAPE.title)
    }

    @Test
    fun `navigation moves the screen and leaves everything else alone`() {
        val start = NavState(Screen.KITS, Personality.FULL, quipIndex = 2)
        val next = start.goTo(Screen.PLAY)
        assertEquals(Screen.PLAY, next.screen)
        assertEquals(Personality.FULL, next.personality)
        assertEquals(2, next.quipIndex)
    }

    @Test
    fun `quips rotate through the shipped list at FULL`() {
        var s = NavState(Screen.KITS, Personality.FULL, quipIndex = 0)
        assertEquals(Copy.STATUS_QUIPS[0], s.statusQuip())
        s = s.tickQuip()
        assertEquals(Copy.STATUS_QUIPS[1], s.statusQuip())
        assertNotEquals(s.statusQuip(), NavState(Screen.KITS, Personality.FULL, 0).statusQuip())
    }

    @Test
    fun `quips wrap without running off the end`() {
        val s = NavState(Screen.KITS, Personality.FULL, quipIndex = Copy.STATUS_QUIPS.size)
        assertEquals(Copy.STATUS_QUIPS[0], s.statusQuip())
    }

    @Test
    fun `OFF and MILD show the drive readout instead of a quip`() {
        for (level in listOf(Personality.OFF, Personality.MILD)) {
            val s = NavState(Screen.KITS, level, quipIndex = 3)
            assertEquals(NavState.CARD_READY, s.statusQuip(), "personality $level")
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests '*NavStateTest*'
```

Expected: FAIL — unresolved reference `Screen`.

- [ ] **Step 3: Write `Screen.kt`**

```kotlin
package com.snipsnap.app.nav

/**
 * The nine menu destinations, in the prototype's order
 * (`design/TapeOS Oilslick.dc.html`, the menu row). [title] is what the
 * titlebar reads on that screen — the deck and the properties panel rename
 * the window, everything else is SNIPSNAP.EXE.
 *
 * M0 implements KITS, KIT and PROPS; the rest are routable so the menu row
 * is honest about what the app will be, and each lights up in its
 * milestone.
 *
 * The title is written out per constant rather than referenced from the
 * companion: Kotlin evaluates enum constructor arguments before the
 * companion object is initialised, so `KITS("KITS", APP_TITLE)` does not
 * compile.
 */
enum class Screen(val menuLabel: String, val title: String) {
    KITS("KITS", "SNIPSNAP.EXE"),
    KIT("KIT", "SNIPSNAP.EXE"),
    TAPE("TAPE", "TAPE DECK"),
    CHOP("CHOP", "SNIPSNAP.EXE"),
    PLAY("PLAY", "SNIPSNAP.EXE"),
    SYNTH("SYNTH", "SNIPSNAP.EXE"),
    EXPORT("EXPORT", "SNIPSNAP.EXE"),
    PROPS("⚙", "TAPE PROPERTIES"),
    HELP("HELP", "SNIPSNAP.EXE"),
}
```

- [ ] **Step 4: Write `NavState.kt`**

```kotlin
package com.snipsnap.app.nav

import com.snipsnap.shell.Copy
import com.snipsnap.shell.Delight
import com.snipsnap.shell.Personality

/**
 * Where the app is and how chatty it is. A plain immutable value with pure
 * transitions — the whole navigation layer is testable without an
 * emulator, which is the point of keeping `Context` out of it.
 */
data class NavState(
    val screen: Screen,
    val personality: Personality = Personality.FULL,
    val quipIndex: Int = 0,
) {
    companion object {
        /** What the third status cell reads when quips are switched off. */
        const val CARD_READY = "SD (E:) READY"
    }
}

fun NavState.goTo(screen: Screen): NavState = copy(screen = screen)

/** Advances the rotating status quip; call on [com.snipsnap.shell.Motion.QUIP_ROTATE_MS]. */
fun NavState.tickQuip(): NavState = copy(quipIndex = quipIndex + 1)

/**
 * The third status cell. Law 3 — jokes never gate function: with
 * personality below FULL the cell still says something true and useful.
 */
fun NavState.statusQuip(): String =
    if (Delight.quipsEnabled(personality)) {
        Copy.rotating(Copy.STATUS_QUIPS, quipIndex)
    } else {
        NavState.CARD_READY
    }
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests '*NavStateTest*'
```

Expected: PASS (6 tests).

- [ ] **Step 6: Write `SnipSnapWindow.kt`**

```kotlin
package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
// NB: no `import ...layout.weight` — RowScope.weight and ColumnScope.weight
// are member extensions and cannot be imported by name. Inside a Row {} or
// Column {} the scope receiver supplies them; an explicit import resolves to
// an unrelated internal property and fails to compile.
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.nav.NavState
import com.snipsnap.app.nav.Screen
import com.snipsnap.app.nav.statusQuip
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.Layout

/**
 * The window everything lives in: a 1996 title bar, a menu row, the
 * screen, and a three-cell status bar — ported from the working prototype
 * (`design/TapeOS Oilslick.dc.html`). Sizes come from `:shell`'s [Layout],
 * which is the handoff's dp table in Kotlin form.
 */
@Composable
fun SnipSnapWindow(
    state: NavState,
    onMenu: (Screen) -> Unit,
    tapeCell: String,
    snipCell: String,
    content: @Composable () -> Unit,
) {
    val s = LocalScheme.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(s.desk1.toColor(), s.desk2.toColor())))
            .padding(Layout.OUTER_MARGIN.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(s.gray.toColor())
                .padding(4.dp),
        ) {
            Titlebar(title = state.screen.title)
            MenuRow(current = state.screen, onMenu = onMenu)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            StatusBar(tapeCell = tapeCell, snipCell = snipCell, quip = state.statusQuip())
        }
    }
}

@Composable
private fun Titlebar(title: String) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.TITLEBAR_H.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.horizontalGradient(listOf(s.title1.toColor(), s.title2.toColor())))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = TextStyle(
                color = s.titleInk.toColor(),
                fontFamily = TapeFonts.display,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            ),
        )
    }
}

@Composable
private fun MenuRow(current: Screen, onMenu: (Screen) -> Unit) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.MENU_ROW_H.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (screen in Screen.entries) {
            BasicText(
                text = screen.menuLabel,
                modifier = Modifier
                    .clickable { onMenu(screen) }
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                style = TextStyle(
                    color = if (screen == current) s.lcdInk.toColor() else s.ink2.toColor(),
                    fontFamily = TapeFonts.pixel,
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp,
                ),
            )
        }
    }
}

@Composable
private fun StatusBar(tapeCell: String, snipCell: String, quip: String) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.STATUS_BAR_H.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        StatusCell(tapeCell, s.lcdInk.toColor())
        StatusCell(snipCell, s.amber.toColor())
        StatusCell(quip, s.amber.toColor(), modifier = Modifier.weight(1f), fontSize = 8)
    }
}

@Composable
private fun StatusCell(
    text: String,
    ink: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    fontSize: Int = 9,
) {
    val s = LocalScheme.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(s.lcd.toColor())
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = ink,
                fontFamily = TapeFonts.pixel,
                fontSize = fontSize.sp,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}
```

- [ ] **Step 7: Point `MainActivity` at the window**

Replace `MainActivity.kt`'s `setContent` body with:

```kotlin
        setContent {
            var state by remember { mutableStateOf(NavState(Screen.KITS)) }

            LaunchedEffect(state.personality) {
                while (true) {
                    delay(Motion.QUIP_ROTATE_MS.toLong())
                    state = state.tickQuip()
                }
            }

            TapeOsTheme(Schemes.DEFAULT) {
                SnipSnapWindow(
                    state = state,
                    onMenu = { state = state.goTo(it) },
                    tapeCell = "TAPE 0:00",
                    snipCell = "0 SNIPS",
                ) {
                    LcdSurface(modifier = Modifier.fillMaxSize()) {
                        BasicText(
                            text = state.screen.menuLabel,
                            modifier = Modifier.align(Alignment.Center),
                            style = TextStyle(
                                color = LocalScheme.current.lcdInk.toColor(),
                                fontFamily = TapeFonts.lcd,
                                fontSize = 25.sp,
                            ),
                        )
                    }
                }
            }
        }
```

Add the imports the compiler asks for (`androidx.compose.runtime.*`, `kotlinx.coroutines.delay`, `com.snipsnap.shell.Motion`, the theme and nav packages).

- [ ] **Step 8: Build, test, and see it on the emulator**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
(emulator -avd snipsnap_pixel -no-snapshot-save -no-boot-anim &) && adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
./gradlew :app:installDebug
adb shell am start -n com.snipsnap.app/.MainActivity
sleep 3 && adb exec-out screencap -p > /tmp/m0-window.png
```

Expected: the CHROME scheme window — blue gradient titlebar reading SNIPSNAP.EXE, nine-item menu row, dark LCD body, three status cells. Tapping a menu item changes the LCD label and the highlighted item.

- [ ] **Step 9: Commit**

```bash
git add app/src
git commit -m "The SNIPSNAP.EXE window: titlebar, menu row, status bar

Nine menu items and three status cells, ported from the working
prototype. Navigation is a pure reducer over :shell's Personality and
Copy, so the quip rotation and the OFF/MILD fallback are unit tests
rather than something you have to launch a phone to check."
```

---

### Task 4: Kit storage and the KITS shelf

Kit folders live under the app's private files directory. `:kit`'s `KitStore` already does list/load/save against a `File` root, so the app layer only supplies the root, seeds a first-run kit, and renders the shelf.

The seed is `ThumpKits.classic()` rendered through `KitAssembler` **on device** — 16 pads of real synthesized audio, no bundled assets. It doubles as proof the synth engines run on Android.

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/store/KitLibrary.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt`
- Test: `app/src/test/kotlin/com/snipsnap/app/store/KitLibraryTest.kt`

**Interfaces:**
- Consumes: `KitStore.list(root: File): List<File>`, `KitStore.load(dir: File): Kit`, `KitStore.FILE_NAME` (`"kit.json"`); `KitBuilderModel.open(kitDir: File)`, `.create(name: String, kitDir: File)`; `KitAssembler.assembleArranged(...)`; `ThumpKits.classic()`; `Names.isMpcSafe(name)`, `Names.sanitizeStem(stem)`; `Copy.EMPTY_SHELF`, `Copy.FRESH_TAPE`; `Kit.pads`
- Produces:
  - `class KitLibrary(private val root: File)` with `fun list(): List<KitEntry>`, `fun open(entry: KitEntry): KitBuilderModel`, `fun create(name: String): KitBuilderModel`, `fun seedIfEmpty(): Boolean`
  - `data class KitEntry(val dir: File, val name: String, val padCount: Int)`
  - `@Composable fun KitsScreen(entries: List<KitEntry>, onOpen: (KitEntry) -> Unit, onNew: () -> Unit)`
  - Seed kit name constant `KitLibrary.SEED_NAME = "SNIPSNAP KIT 01"`

- [ ] **Step 1: Write the failing library test**

`app/src/test/kotlin/com/snipsnap/app/store/KitLibraryTest.kt`:

```kotlin
package com.snipsnap.app.store

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KitLibraryTest {

    private fun library() = KitLibrary(Files.createTempDirectory("kits").toFile())

    @Test
    fun `an empty root lists nothing`() {
        assertTrue(library().list().isEmpty())
    }

    @Test
    fun `seeding an empty root renders the factory kit`() {
        val lib = library()
        assertTrue(lib.seedIfEmpty(), "first seed should report that it wrote something")

        val entries = lib.list()
        assertEquals(1, entries.size)
        assertEquals(KitLibrary.SEED_NAME, entries[0].name)
        assertEquals(16, entries[0].padCount)

        val kit = lib.open(entries[0]).kit
        for (pad in kit.pads) {
            assertTrue(
                java.io.File(entries[0].dir, pad.sampleFile).length() > 44,
                "${pad.sampleFile} is header-only",
            )
        }
    }

    @Test
    fun `seeding is idempotent`() {
        val lib = library()
        lib.seedIfEmpty()
        assertFalse(lib.seedIfEmpty(), "a populated shelf must not be re-seeded")
        assertEquals(1, lib.list().size)
    }

    @Test
    fun `created kits appear on the shelf, sorted`() {
        val lib = library()
        lib.create("ZEBRA")
        lib.create("APPLE")
        assertEquals(listOf("APPLE", "ZEBRA"), lib.list().map { it.name })
    }

    @Test
    fun `a new kit starts empty`() {
        val lib = library()
        assertEquals(0, lib.create("NIGHT BUS").kit.pads.size)
        assertEquals(0, lib.list().single().padCount)
    }

    @Test
    fun `names that would not survive an MPC card are refused`() {
        val lib = library()
        assertFailsWith<IllegalArgumentException> { lib.create("BAD/NAME") }
        assertFailsWith<IllegalArgumentException> { lib.create("   ") }
        assertTrue(lib.list().isEmpty(), "a refused name must not leave a folder behind")
    }

    @Test
    fun `a folder without kit json is not a kit`() {
        val lib = library()
        lib.create("REAL")
        java.io.File(lib.root, "not-a-kit").mkdirs()
        assertEquals(listOf("REAL"), lib.list().map { it.name })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests '*KitLibraryTest*'
```

Expected: FAIL — unresolved reference `KitLibrary`.

- [ ] **Step 3: Write `KitLibrary.kt`**

```kotlin
package com.snipsnap.app.store

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.ThumpKits
import java.io.File

/** One tape on the shelf: the folder, its name, and how full it is. */
data class KitEntry(
    val dir: File,
    val name: String,
    val padCount: Int,
)

/**
 * The shelf: kit folders under one root.
 *
 * Takes a [File], never a `Context` — the Activity resolves
 * `filesDir/kits` and hands it over, which is what lets the whole shelf be
 * tested on a temp directory with no emulator in sight.
 */
class KitLibrary(val root: File) {

    fun list(): List<KitEntry> = KitStore.list(root).map { dir ->
        val kit = KitStore.load(dir)
        KitEntry(dir = dir, name = kit.name, padCount = kit.pads.size)
    }.sortedBy { it.name.lowercase() }

    fun open(entry: KitEntry): KitBuilderModel = KitBuilderModel.open(entry.dir)

    /** FRESH TAPE. Rejects a name the MPC's browser could not show. */
    fun create(name: String): KitBuilderModel {
        require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
        return KitBuilderModel.create(name, File(root, Names.sanitizeStem(name)))
    }

    /**
     * First run: render the factory kit rather than ship WAVs. Sixteen pads
     * of synthesized audio cost a second of CPU, weigh nothing in the APK,
     * and prove the engines run on the device. Returns false — and touches
     * nothing — if the shelf already has tapes on it.
     */
    fun seedIfEmpty(): Boolean {
        root.mkdirs()
        if (KitStore.list(root).isNotEmpty()) return false
        val dir = File(root, Names.sanitizeStem(SEED_NAME))
        KitAssembler.assembleArranged(SEED_NAME, ThumpKits.classic(), dir)
        return true
    }

    companion object {
        const val SEED_NAME = "SNIPSNAP KIT 01"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests '*KitLibraryTest*'
```

Expected: PASS (7 tests).

- [ ] **Step 5: Write `KitsScreen.kt`**

The shelf row from the prototype (lines 365–388): a cassette glyph, the handwritten name, a meta line, and a right-hand status column; then the NEW BLANK TAPE button.

```kotlin
package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.store.KitEntry
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout

/** MY KITS — the tape shelf. */
@Composable
fun KitsScreen(
    entries: List<KitEntry>,
    onOpen: (KitEntry) -> Unit,
    onNew: () -> Unit,
) {
    val s = LocalScheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LcdHeader(left = "MY KITS", right = "${entries.size} TAPES")

        if (entries.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicText(
                    text = Copy.EMPTY_SHELF,
                    style = TextStyle(
                        color = s.ink2.toColor(),
                        fontFamily = TapeFonts.pixel,
                        fontSize = 9.sp,
                    ),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.dir.absolutePath }) { entry ->
                    ShelfRow(entry = entry, onOpen = { onOpen(entry) })
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Layout.PRIMARY_ACTION_H.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(s.lcd.toColor())
                .border(2.dp, s.lcdInk.toColor(), RoundedCornerShape(6.dp))
                .clickable { onNew() },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "+  NEW BLANK TAPE",
                style = TextStyle(
                    color = s.lcdInk.toColor(),
                    fontFamily = TapeFonts.display,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                ),
            )
        }
    }
}

@Composable
private fun ShelfRow(entry: KitEntry, onOpen: () -> Unit) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(s.field.toColor())
            .clickable { onOpen() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = entry.name,
                style = TextStyle(
                    color = s.ink.toColor(),
                    fontFamily = TapeFonts.marker,
                    fontSize = 15.sp,
                ),
            )
            BasicText(
                text = "${entry.padCount} SNIPS",
                style = TextStyle(
                    color = s.ink2.toColor(),
                    fontFamily = TapeFonts.pixel,
                    fontSize = 9.sp,
                ),
            )
        }
        BasicText(
            text = if (entry.padCount == 0) "DRAFT" else "ON SHELF",
            style = TextStyle(
                color = s.amber.toColor(),
                fontFamily = TapeFonts.pixel,
                fontSize = 9.sp,
            ),
        )
    }
}

/** The sunken LCD strip every screen wears at the top. */
@Composable
fun LcdHeader(left: String, right: String) {
    val s = LocalScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.LCD_HEADER_H.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(s.lcd.toColor())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            text = left,
            style = TextStyle(color = s.lcdInk.toColor(), fontFamily = TapeFonts.lcd, fontSize = 25.sp),
        )
        BasicText(
            text = right,
            style = TextStyle(color = s.amber.toColor(), fontFamily = TapeFonts.lcd, fontSize = 21.sp),
        )
    }
}
```

- [ ] **Step 6: Wire the shelf into `MainActivity`**

`filesDir` is the only `Context` touch. Seeding renders 16 WAVs, so it runs off the main thread:

```kotlin
        val library = KitLibrary(File(filesDir, "kits"))

        setContent {
            var state by remember { mutableStateOf(NavState(Screen.KITS)) }
            var entries by remember { mutableStateOf(emptyList<KitEntry>()) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { library.seedIfEmpty() }
                entries = withContext(Dispatchers.IO) { library.list() }
            }
            // …quip ticker as before…

            TapeOsTheme(Schemes.DEFAULT) {
                SnipSnapWindow(
                    state = state,
                    onMenu = { state = state.goTo(it) },
                    tapeCell = "TAPE 0:00",
                    snipCell = "${entries.size} TAPES",
                ) {
                    when (state.screen) {
                        Screen.KITS -> KitsScreen(
                            entries = entries,
                            onOpen = { openKit = it; state = state.goTo(Screen.KIT) },
                            // Task 6 Step 4g replaces this with the FRESH
                            // TAPE dialog; the button is inert until then.
                            onNew = { },
                        )
                        else -> LcdSurface(modifier = Modifier.fillMaxSize()) { /* placeholder */ }
                    }
                }
            }
        }
```

Track the opened kit in a `var openKit by remember { mutableStateOf<KitEntry?>(null) }` — Task 5 needs it.

- [ ] **Step 7: Build, test, and confirm the seed on the emulator**

```bash
./gradlew :app:testDebugUnitTest :app:installDebug
adb shell am start -n com.snipsnap.app/.MainActivity
sleep 8 && adb exec-out screencap -p > /tmp/m0-shelf.png
adb shell run-as com.snipsnap.app ls -la files/kits/SNIPSNAP_KIT_01 | head -20
```

Expected: the shelf shows one tape, "SNIPSNAP KIT 01 · 16 SNIPS", and the folder listing shows `kit.json` plus 16 `.wav` files.

- [ ] **Step 8: Commit**

```bash
git add app/src
git commit -m "Kit storage and the tape shelf

KitLibrary takes a File root, never a Context, so the whole shelf is
unit-tested on a temp directory. First run renders the factory kit on
device instead of shipping WAVs: sixteen pads of synthesized audio cost
a second of CPU, weigh nothing in the APK, and prove the engines run
here."
```

---

### Task 5: The pad grid and the sound

The 4×4 grid over a kit, and the `SoundPool` adapter that makes it audible. Two facts to get right:

1. **Grid orientation is MPC, not reading order.** A01 is bottom-left; A13 is the top row (`Copy.KONAMI_PADS`'s doc comment states this, and it is what the hardware does). So the top row is slots 13–16 and the bottom row 1–4.
2. **"Hear WAVs" needs a machine-checkable form.** A screenshot cannot prove audio. `PadPlayer` reports load success through `SoundPool.OnLoadCompleteListener` and returns the non-zero stream id from `play()`, both logged — so the emulator loop can gate M0 instead of leaving it to the user's phone.

`SoundPool` is explicitly interim (M4 replaces it with Oboe/AAudio); it is behind an interface so that swap touches one file.

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/audio/PadPlayer.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/PadGrid.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt`
- Test: `app/src/test/kotlin/com/snipsnap/app/ui/PadGridLayoutTest.kt`

**Interfaces:**
- Consumes: `KitBuilderModel.bank(bankIndex: Int): List<KitPad?>`, `.kit`, `.name`, `.emptyStateLine`; `KitPad.slot/displayName/drumClass/sampleFile`; `PadNoteMap.labelForPad(Int)`; `Schemes.classColor(DrumClass)`, `Schemes.padLabelInk(Scheme, DrumClass)`; `Layout.PAD_H` (76), `Layout.PAD_GAP` (8), `Layout.PAD_RADIUS` (6); `Motion.PAD_GLOW_MS` (180)
- Produces:
  - `fun slotForCell(row: Int, col: Int, bankIndex: Int = 0): Int` — MPC orientation
  - `fun cellForSlot(slot: Int): Pair<Int, Int>`
  - `interface PadSound { fun load(slot: Int, file: File); fun reset(); fun play(slot: Int): Int; fun release() }`
  - `class PadPlayer(context: Context) : PadSound`
  - `@Composable fun PadGrid(pads: List<KitPad?>, onHit: (Int) -> Unit)`
  - `@Composable fun KitScreen(model: KitBuilderModel, sound: PadSound, onHit: (Int) -> Unit)`

- [ ] **Step 1: Write the failing grid-layout test**

`app/src/test/kotlin/com/snipsnap/app/ui/PadGridLayoutTest.kt`:

```kotlin
package com.snipsnap.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The grid reads like an MPC, not like a page: A01 is bottom-left and A13
 * is the top row. Getting this backwards would put the kick where the
 * cymbal belongs on every kit ever made.
 */
class PadGridLayoutTest {

    @Test
    fun `the bottom-left cell is A01`() {
        assertEquals(1, slotForCell(row = 3, col = 0))
    }

    @Test
    fun `the top row is slots 13 to 16`() {
        assertEquals(listOf(13, 14, 15, 16), (0..3).map { slotForCell(row = 0, col = it) })
    }

    @Test
    fun `the bottom row is slots 1 to 4`() {
        assertEquals(listOf(1, 2, 3, 4), (0..3).map { slotForCell(row = 3, col = it) })
    }

    @Test
    fun `every cell maps to a distinct slot covering the bank`() {
        val slots = (0..3).flatMap { r -> (0..3).map { c -> slotForCell(r, c) } }
        assertEquals((1..16).toSet(), slots.toSet())
    }

    @Test
    fun `bank B continues where bank A stopped`() {
        assertEquals(17, slotForCell(row = 3, col = 0, bankIndex = 1))
        assertEquals(32, slotForCell(row = 0, col = 3, bankIndex = 1))
    }

    @Test
    fun `cellForSlot inverts slotForCell`() {
        for (r in 0..3) {
            for (c in 0..3) {
                assertEquals(r to c, cellForSlot(slotForCell(r, c)))
            }
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests '*PadGridLayoutTest*'
```

Expected: FAIL — unresolved reference `slotForCell`.

- [ ] **Step 3: Write the layout functions (top of `PadGrid.kt`)**

```kotlin
/**
 * Grid cell → pad slot, in MPC orientation: row 0 is the **top** row and
 * holds slots 13–16, row 3 is the bottom and holds 1–4, so A01 sits
 * bottom-left exactly as it does on the hardware. What your hands learn
 * here is what the MPC gives back.
 */
fun slotForCell(row: Int, col: Int, bankIndex: Int = 0): Int {
    require(row in 0..3 && col in 0..3) { "cell out of range: $row,$col" }
    require(bankIndex in 0..7) { "bank 0..7 (A..H), got $bankIndex" }
    return bankIndex * 16 + (3 - row) * 4 + col + 1
}

/** The inverse of [slotForCell], within the slot's own bank. */
fun cellForSlot(slot: Int): Pair<Int, Int> {
    require(slot >= 1) { "slot out of range: $slot" }
    val within = (slot - 1) % 16
    return (3 - within / 4) to (within % 4)
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests '*PadGridLayoutTest*'
```

Expected: PASS (6 tests).

- [ ] **Step 5: Write `PadPlayer.kt`**

```kotlin
package com.snipsnap.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File

/**
 * What a pad needs in order to make a sound. M4 replaces the
 * implementation with Oboe/AAudio for the latency the play surface wants;
 * keeping the seam here means that swap touches one file.
 */
interface PadSound {
    /** Loads [file] for [slot]. Idempotent per slot. */
    fun load(slot: Int, file: File)

    /**
     * Forgets every loaded pad. Call before loading a different kit —
     * [load] is idempotent per *slot*, so without this the new kit's pad 1
     * would keep playing the old kit's pad 1.
     */
    fun reset()

    /** Fires [slot]. Returns the stream id, or 0 if the pad is not ready. */
    fun play(slot: Int): Int

    fun release()
}

/**
 * The interim player: `SoundPool` decodes short one-shots into memory and
 * fires them with acceptable jitter for browsing a kit. Good enough to
 * hear a pad; not good enough to play a groove, which is why M4 exists.
 *
 * Holds a `Context` — one of the three files in this app allowed to.
 */
class PadPlayer(context: Context, maxStreams: Int = 8) : PadSound {

    private val pool = SoundPool.Builder()
        .setMaxStreams(maxStreams)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    /** slot → sound id, present only once the pool reports the load done. */
    private val ready = HashMap<Int, Int>()
    private val pending = HashMap<Int, Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            val slot = pending.entries.firstOrNull { it.value == sampleId }?.key
            if (status == 0 && slot != null) {
                ready[slot] = sampleId
                pending.remove(slot)
                Log.i(TAG, "pad $slot loaded (sample $sampleId)")
            } else {
                Log.w(TAG, "pad load failed: sample=$sampleId status=$status slot=$slot")
            }
        }
    }

    override fun load(slot: Int, file: File) {
        if (ready.containsKey(slot) || pending.containsKey(slot)) return
        if (!file.isFile) {
            Log.w(TAG, "pad $slot: no such file ${file.name}")
            return
        }
        pending[slot] = pool.load(file.absolutePath, 1)
    }

    override fun reset() {
        for (sampleId in ready.values) pool.unload(sampleId)
        for (sampleId in pending.values) pool.unload(sampleId)
        ready.clear()
        pending.clear()
    }

    override fun play(slot: Int): Int {
        val sampleId = ready[slot] ?: run {
            Log.w(TAG, "pad $slot not ready")
            return 0
        }
        val stream = pool.play(sampleId, 1f, 1f, 1, 0, 1f)
        Log.i(TAG, "pad $slot hit -> stream $stream")
        return stream
    }

    override fun release() {
        pool.release()
        ready.clear()
        pending.clear()
    }

    /** True once [slot] has finished loading — the exit test's hook. */
    fun isReady(slot: Int): Boolean = ready.containsKey(slot)

    companion object {
        const val TAG = "SnipSnapPad"
    }
}
```

- [ ] **Step 6: Write the grid and the KIT screen**

Append to `PadGrid.kt`:

```kotlin
/**
 * The 4×4 grid. An assigned pad wears its class colour as a border and a
 * handwritten label; an empty one is bare chrome. Both come from
 * `:shell`'s tables so the colours match the MPC's own.
 */
@Composable
fun PadGrid(pads: List<KitPad?>, onHit: (Int) -> Unit) {
    val s = LocalScheme.current
    Column(verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
        for (row in 0..3) {
            Row(horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                for (col in 0..3) {
                    val slot = slotForCell(row, col)
                    val pad = pads.getOrNull(slot - 1)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(Layout.PAD_H.dp)
                            .clip(RoundedCornerShape(Layout.PAD_RADIUS.dp))
                            .background(if (pad == null) s.gray.toColor() else s.lcd.toColor())
                            .then(
                                if (pad == null) Modifier
                                else Modifier.border(
                                    2.dp,
                                    Schemes.classColor(pad.drumClass).toColor(),
                                    RoundedCornerShape(Layout.PAD_RADIUS.dp),
                                ),
                            )
                            .clickable { onHit(slot) },
                    ) {
                        BasicText(
                            text = PadNoteMap.labelForPad(slot),
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 5.dp),
                            style = TextStyle(
                                color = s.ink2.toColor(),
                                fontFamily = TapeFonts.pixel,
                                fontSize = 9.sp,
                            ),
                        )
                        if (pad != null) {
                            BasicText(
                                text = pad.displayName,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    color = Schemes.classColor(pad.drumClass).toColor(),
                                    fontFamily = TapeFonts.marker,
                                    fontSize = 13.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
```

`KitScreen.kt`:

```kotlin
package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.audio.PadSound
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.KitBuilderModel
import java.io.File

/** The KIT screen: one bank of the open kit, audible. */
@Composable
fun KitScreen(model: KitBuilderModel, sound: PadSound, onHit: (Int) -> Unit) {
    val s = LocalScheme.current
    val bank = model.bank(0)

    // Every pad in the bank is loaded once, when the kit opens. The reset
    // matters: loads are keyed by slot, so without it pad 1 of this kit
    // would still be playing pad 1 of the last one.
    LaunchedEffect(model.kitDir) {
        sound.reset()
        for (pad in bank) {
            if (pad != null) sound.load(pad.slot, File(model.kitDir, pad.sampleFile))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LcdHeader(left = model.name, right = "BANK A")

        val empty = model.emptyStateLine
        if (empty != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    text = empty,
                    style = TextStyle(color = s.ink2.toColor(), fontFamily = TapeFonts.pixel, fontSize = 9.sp),
                )
            }
        }

        // `model.bank(0)` already returns slots 1..16 in order, nulls for
        // empty pads — exactly what PadGrid indexes.
        PadGrid(pads = bank) { slot ->
            sound.play(slot)
            onHit(slot)
        }
    }
}
```

- [ ] **Step 7: Wire KIT into `MainActivity` and release the pool**

Create the player once, dispose it with the Activity:

```kotlin
    private lateinit var padSound: PadPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padSound = PadPlayer(this)
        // …
    }

    override fun onDestroy() {
        padSound.release()
        super.onDestroy()
    }
```

In the `when (state.screen)`, open the selected kit:

```kotlin
                        Screen.KIT -> {
                            val entry = openKit
                            if (entry == null) {
                                KitsScreen(entries, onOpen = { openKit = it; state = state.goTo(Screen.KIT) }, onNew = {})
                            } else {
                                val model = remember(entry.dir) { KitBuilderModel.open(entry.dir) }
                                KitScreen(model = model, sound = padSound, onHit = {})
                            }
                        }
```

and set `openKit` in the shelf's `onOpen`.

- [ ] **Step 8: Verify the sound programmatically on the emulator**

```bash
./gradlew :app:testDebugUnitTest :app:installDebug
adb logcat -c
adb shell am start -n com.snipsnap.app/.MainActivity
sleep 8
# Open the seeded kit, then tap the bottom-left pad (A01).
adb shell input tap 540 700    # shelf row
sleep 2
adb shell input tap 200 1750   # bottom-left pad; adjust from the screenshot
sleep 1
adb logcat -d -s SnipSnapPad
adb exec-out screencap -p > /tmp/m0-kit.png
```

Expected in logcat: sixteen `pad N loaded (sample M)` lines, then `pad 1 hit -> stream S` with **S non-zero**. A zero stream id means the pool refused the play — that is the failure this gate exists to catch. Read `/tmp/m0-kit.png` to confirm the grid renders with class-coloured borders and that A01 is bottom-left.

- [ ] **Step 9: Commit**

```bash
git add app/src
git commit -m "The pad grid, audible

A01 bottom-left and A13 on the top row, MPC orientation, with the
mapping unit-tested both ways — putting it backwards would move the
kick on every kit ever made. SoundPool sits behind a PadSound interface
because M4 swaps it for Oboe; it logs load status and stream ids so the
emulator loop can prove a pad sounded instead of trusting a screenshot."
```

---

### Task 6: New tapes, Tape Properties, and the M0 exit test

Two affordances remain, and then the gate. **FRESH TAPE** finishes the `KitStore` clause — M0 asks for "list, open, **create**", and Task 4 built and tested `KitLibrary.create()` but left `onNew` a no-op, so the button is visibly dead until this task. **Tape Properties** covers "flip schemes", writing the choice through a persistence adapter so it survives a relaunch.

Name validation is a pure function, not dialog code: it has three outcomes (accept, reject, accept-with-a-quip for the TEST egg) and all three are worth a test.

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/store/Settings.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/store/KitNaming.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/NewTapeDialog.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/PropsScreen.kt`
- Create: `scripts/m0-exit-test.sh`
- Test: `app/src/test/kotlin/com/snipsnap/app/store/SettingsTest.kt`
- Test: `app/src/test/kotlin/com/snipsnap/app/store/KitNamingTest.kt`
- Modify: `docs/APP_PLAN.md` (mark M0 done)

**Interfaces:**
- Consumes: `Schemes.ALL`, `Schemes[id]`, `SchemeId`, `Schemes.DEFAULT`; `Personality`, `Delight.toastsEnabled(level)`; `Names.isMpcSafe(name)`; `Copy.FRESH_TAPE`, `Copy.kitNameResponse(name)`; `KitLibrary.create(name)`, `KitLibrary.list()`; `LocalScheme`, `TapeFonts`, `toColor()`
- Produces:
  - `interface SettingsStore { var schemeId: SchemeId; var personality: Personality }`
  - `class FileSettings(private val file: File) : SettingsStore` — two lines of `key=value`, no Android dependency
  - `sealed interface NameVerdict` with `Ok(name, quip)` and `Rejected(reason)`
  - `fun verifyKitName(proposed: String, existing: List<String>): NameVerdict`
  - `@Composable fun NewTapeDialog(existing: List<String>, onConfirm: (String) -> Unit, onDismiss: () -> Unit)`
  - `@Composable fun PropsScreen(current: SchemeId, personality: Personality, onScheme: (SchemeId) -> Unit, onPersonality: (Personality) -> Unit)`

- [ ] **Step 1: Write the failing settings test**

`app/src/test/kotlin/com/snipsnap/app/store/SettingsTest.kt`:

```kotlin
package com.snipsnap.app.store

import com.snipsnap.shell.Personality
import com.snipsnap.shell.SchemeId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {

    private fun file(): File = File(Files.createTempDirectory("settings").toFile(), "tape.properties")

    @Test
    fun `an absent file gives the defaults`() {
        val s = FileSettings(file())
        assertEquals(SchemeId.CHROME, s.schemeId)
        assertEquals(Personality.FULL, s.personality)
    }

    @Test
    fun `a chosen scheme survives a reload`() {
        val f = file()
        FileSettings(f).schemeId = SchemeId.OILSLICK
        assertEquals(SchemeId.OILSLICK, FileSettings(f).schemeId)
    }

    @Test
    fun `personality survives a reload`() {
        val f = file()
        FileSettings(f).personality = Personality.OFF
        assertEquals(Personality.OFF, FileSettings(f).personality)
    }

    @Test
    fun `both settings are independent`() {
        val f = file()
        val s = FileSettings(f)
        s.schemeId = SchemeId.SNACK_BAR
        s.personality = Personality.MILD
        val reloaded = FileSettings(f)
        assertEquals(SchemeId.SNACK_BAR, reloaded.schemeId)
        assertEquals(Personality.MILD, reloaded.personality)
    }

    @Test
    fun `a corrupt file falls back to defaults instead of crashing`() {
        val f = file()
        f.parentFile.mkdirs()
        f.writeText("scheme=NOT_A_SCHEME\npersonality=LOUD\ngarbage\n")
        val s = FileSettings(f)
        assertEquals(SchemeId.CHROME, s.schemeId)
        assertEquals(Personality.FULL, s.personality)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests '*SettingsTest*'
```

Expected: FAIL — unresolved reference `FileSettings`.

- [ ] **Step 3: Write `Settings.kt`**

```kotlin
package com.snipsnap.app.store

import com.snipsnap.shell.Personality
import com.snipsnap.shell.SchemeId
import java.io.File

/** The two choices Tape Properties owns. */
interface SettingsStore {
    var schemeId: SchemeId
    var personality: Personality
}

/**
 * Settings as two lines of `key=value` in the app's files directory.
 *
 * A plain [File] rather than `SharedPreferences`: it keeps the whole
 * settings layer testable off-device, and two enum values do not justify
 * an Android dependency. A value the current build cannot parse — an
 * older file, a hand-edited one — falls back to the default rather than
 * refusing to start.
 */
class FileSettings(private val file: File) : SettingsStore {

    private val values: MutableMap<String, String> = read()

    override var schemeId: SchemeId
        get() = values[KEY_SCHEME]?.let { raw ->
            SchemeId.entries.firstOrNull { it.name == raw }
        } ?: SchemeId.CHROME
        set(value) {
            values[KEY_SCHEME] = value.name
            write()
        }

    override var personality: Personality
        get() = values[KEY_PERSONALITY]?.let { raw ->
            Personality.entries.firstOrNull { it.name == raw }
        } ?: Personality.FULL
        set(value) {
            values[KEY_PERSONALITY] = value.name
            write()
        }

    private fun read(): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()
        return try {
            file.readLines()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
                .toMap(mutableMapOf())
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun write() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        } catch (e: Exception) {
            // A settings write that fails must never take the app down; the
            // choice simply does not survive the session.
        }
    }

    private companion object {
        const val KEY_SCHEME = "scheme"
        const val KEY_PERSONALITY = "personality"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests '*SettingsTest*'
```

Expected: PASS (5 tests).

- [ ] **Step 4b: Write the failing name-validation test**

`app/src/test/kotlin/com/snipsnap/app/store/KitNamingTest.kt`:

```kotlin
package com.snipsnap.app.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitNamingTest {

    @Test
    fun `a plain name is accepted with no quip`() {
        val v = verifyKitName("NIGHT BUS", existing = emptyList())
        assertTrue(v is NameVerdict.Ok)
        assertEquals("NIGHT BUS", v.name)
        assertNull(v.quip)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val v = verifyKitName("  NIGHT BUS  ", existing = emptyList())
        assertTrue(v is NameVerdict.Ok)
        assertEquals("NIGHT BUS", v.name)
    }

    @Test
    fun `a blank name is refused`() {
        assertTrue(verifyKitName("   ", existing = emptyList()) is NameVerdict.Rejected)
    }

    @Test
    fun `a name the MPC browser could not show is refused`() {
        for (bad in listOf("BAD/NAME", "A:B", "Q?", "pipe|d", "star*")) {
            assertTrue(verifyKitName(bad, existing = emptyList()) is NameVerdict.Rejected, bad)
        }
    }

    @Test
    fun `a duplicate name is refused, case-insensitively`() {
        val v = verifyKitName("night bus", existing = listOf("NIGHT BUS"))
        assertTrue(v is NameVerdict.Rejected)
        assertTrue(v.reason.isNotBlank())
    }

    @Test
    fun `naming a kit TEST earns the egg but still works`() {
        val v = verifyKitName("TEST", existing = emptyList())
        assertTrue(v is NameVerdict.Ok)
        assertEquals("TEST", v.name)
        assertEquals("VERY CREATIVE.", v.quip)
    }
}
```

- [ ] **Step 4c: Run it to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests '*KitNamingTest*'
```

Expected: FAIL — unresolved reference `verifyKitName`.

- [ ] **Step 4d: Write `KitNaming.kt`**

```kotlin
package com.snipsnap.app.store

import com.snipsnap.kit.Names
import com.snipsnap.shell.Copy

/** What the FRESH TAPE dialog does with what you typed. */
sealed interface NameVerdict {
    /** [quip] is the easter-egg line, if the name earned one. */
    data class Ok(val name: String, val quip: String?) : NameVerdict
    data class Rejected(val reason: String) : NameVerdict
}

/**
 * Kit-name validation as a pure function, so the dialog stays dumb.
 *
 * A kit's name becomes a folder on a FAT card an MPC has to browse, so
 * [Names.isMpcSafe] is the real gate — rejecting early beats writing a
 * folder that fails Preflight later. Duplicates are refused
 * case-insensitively because the card's filesystem does not distinguish
 * them.
 */
fun verifyKitName(proposed: String, existing: List<String>): NameVerdict {
    val name = proposed.trim()
    if (name.isBlank()) return NameVerdict.Rejected("NAME IT SOMETHING.")
    if (!Names.isMpcSafe(name)) return NameVerdict.Rejected("THE MPC CAN'T READ THAT NAME.")
    if (existing.any { it.equals(name, ignoreCase = true) }) {
        return NameVerdict.Rejected("YOU ALREADY HAVE THAT TAPE.")
    }
    return NameVerdict.Ok(name, Copy.kitNameResponse(name))
}
```

- [ ] **Step 4e: Run it to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests '*KitNamingTest*'
```

Expected: PASS (6 tests).

- [ ] **Step 4f: Write `NewTapeDialog.kt`**

```kotlin
package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.store.NameVerdict
import com.snipsnap.app.store.verifyKitName
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor

/**
 * FRESH TAPE: name the thing before it exists.
 *
 * Validation is [verifyKitName], and the rejection shows *inline* rather
 * than disabling the button — telling you why beats a dead control
 * (personality law 3: jokes never gate function, and neither do errors).
 */
@Composable
fun NewTapeDialog(
    existing: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalScheme.current
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(s.gray.toColor())
                .padding(14.dp)
                // Swallow the click so tapping the card doesn't dismiss it.
                .clickable(enabled = false) {},
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                text = "NAME THIS TAPE",
                style = TextStyle(
                    color = s.ink.toColor(),
                    fontFamily = TapeFonts.display,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(s.lcd.toColor())
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it; error = null },
                    singleLine = true,
                    cursorBrush = SolidColor(s.lcdInk.toColor()),
                    textStyle = TextStyle(
                        color = s.lcdInk.toColor(),
                        fontFamily = TapeFonts.lcd,
                        fontSize = 22.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            error?.let {
                BasicText(
                    text = it,
                    style = TextStyle(
                        color = s.amber.toColor(),
                        fontFamily = TapeFonts.pixel,
                        fontSize = 9.sp,
                    ),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(label = "CANCEL", modifier = Modifier.weight(1f)) { onDismiss() }
                DialogButton(label = "ROLL IT", modifier = Modifier.weight(1f)) {
                    when (val verdict = verifyKitName(typed, existing)) {
                        is NameVerdict.Ok -> onConfirm(verdict.name)
                        is NameVerdict.Rejected -> error = verdict.reason
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = LocalScheme.current
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(s.lcd.toColor())
            .border(2.dp, s.lcdInk.toColor(), RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = s.lcdInk.toColor(),
                fontFamily = TapeFonts.pixel,
                fontSize = 9.sp,
            ),
        )
    }
}
```

- [ ] **Step 4g: Wire FRESH TAPE into `MainActivity` and retire the dead button**

Task 4 left `onNew = { }` inert. Give it state, and render the dialog over the window:

```kotlin
            var newTape by remember { mutableStateOf(false) }
            var toast by remember { mutableStateOf<String?>(null) }

            // …inside the KITS branch:
                        Screen.KITS -> KitsScreen(
                            entries = entries,
                            onOpen = { openKit = it; state = state.goTo(Screen.KIT) },
                            onNew = { newTape = true },
                        )

            // …after SnipSnapWindow, still inside TapeOsTheme:
            if (newTape) {
                NewTapeDialog(
                    existing = entries.map { it.name },
                    onDismiss = { newTape = false },
                    onConfirm = { name ->
                        newTape = false
                        scope.launch {
                            withContext(Dispatchers.IO) { library.create(name) }
                            entries = withContext(Dispatchers.IO) { library.list() }
                            if (Delight.toastsEnabled(state.personality)) {
                                toast = Copy.kitNameResponse(name) ?: Copy.FRESH_TAPE
                            }
                        }
                    },
                )
            }
```

`scope` is a `rememberCoroutineScope()`. Render `toast` as a line above the status bar and clear it after `Motion.TOAST_DWELL_MS`:

```kotlin
            LaunchedEffect(toast) {
                if (toast != null) {
                    delay(Motion.TOAST_DWELL_MS.toLong())
                    toast = null
                }
            }
```

- [ ] **Step 5: Write `PropsScreen.kt`**

```kotlin
package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.Personality
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes

/**
 * TAPE PROPERTIES: the scheme picker and the personality slider.
 *
 * Each swatch previews its own scheme's chrome and LCD side by side —
 * you pick a look by seeing it, which is the point of having six.
 */
@Composable
fun PropsScreen(
    current: SchemeId,
    personality: Personality,
    onScheme: (SchemeId) -> Unit,
    onPersonality: (Personality) -> Unit,
) {
    val s = LocalScheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LcdHeader(left = "TAPE PROPERTIES", right = Schemes[current].id.displayName)

        BasicText(
            text = "SCHEME",
            style = TextStyle(color = s.ink2.toColor(), fontFamily = TapeFonts.pixel, fontSize = 9.sp),
        )

        for (scheme in Schemes.ALL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(scheme.gray.toColor())
                    .then(
                        if (scheme.id == current) {
                            Modifier.border(2.dp, s.lcdInk.toColor(), RoundedCornerShape(4.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onScheme(scheme.id) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    text = scheme.id.displayName,
                    style = TextStyle(
                        color = scheme.ink.toColor(),
                        fontFamily = TapeFonts.display,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                    ),
                )
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(scheme.lcd.toColor())
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "LCD",
                        style = TextStyle(
                            color = scheme.lcdInk.toColor(),
                            fontFamily = TapeFonts.lcd,
                            fontSize = 17.sp,
                        ),
                    )
                }
            }
        }

        BasicText(
            text = "PERSONALITY",
            style = TextStyle(color = s.ink2.toColor(), fontFamily = TapeFonts.pixel, fontSize = 9.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (level in Personality.entries) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (level == personality) s.lcd.toColor() else s.gray.toColor())
                        .clickable { onPersonality(level) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = level.name,
                        style = TextStyle(
                            color = if (level == personality) s.lcdInk.toColor() else s.ink2.toColor(),
                            fontFamily = TapeFonts.pixel,
                            fontSize = 9.sp,
                        ),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 6: Wire settings and the props screen into `MainActivity`**

```kotlin
        val settings = FileSettings(File(filesDir, "tape.properties"))
        // …
            var schemeId by remember { mutableStateOf(settings.schemeId) }
            var state by remember { mutableStateOf(NavState(Screen.KITS, settings.personality)) }

            TapeOsTheme(Schemes[schemeId]) {
                SnipSnapWindow(...) {
                    when (state.screen) {
                        // …
                        Screen.PROPS -> PropsScreen(
                            current = schemeId,
                            personality = state.personality,
                            onScheme = { schemeId = it; settings.schemeId = it },
                            onPersonality = { state = state.copy(personality = it); settings.personality = it },
                        )
                    }
                }
            }
```

- [ ] **Step 7: Write the exit-test script**

`scripts/m0-exit-test.sh`:

```bash
#!/usr/bin/env bash
# M0 exit test — browse kits, tap pads, hear WAVs, flip schemes.
# Run against a booted emulator or a connected phone.
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
OUT="${1:-/tmp/m0}"
mkdir -p "$OUT"

echo "== install =="
eval "$(fnm env)" && fnm use 20
./gradlew :app:testDebugUnitTest :app:installDebug

echo "== launch =="
adb logcat -c
adb shell am force-stop com.snipsnap.app
adb shell am start -n com.snipsnap.app/.MainActivity
sleep 10   # first run renders 16 synthesized pads

echo "== the shelf has a tape =="
adb shell run-as com.snipsnap.app ls files/kits
adb exec-out screencap -p > "$OUT/1-shelf.png"

echo "== the kit folder is real =="
KIT=$(adb shell run-as com.snipsnap.app ls files/kits | tr -d '\r' | head -1)
adb shell run-as com.snipsnap.app ls "files/kits/$KIT" | tr -d '\r' | tee "$OUT/kit-listing.txt"
grep -q 'kit.json' "$OUT/kit-listing.txt" || { echo "FAIL: no kit.json"; exit 1; }
test "$(grep -c '\.wav$' "$OUT/kit-listing.txt")" -eq 16 || { echo "FAIL: expected 16 WAVs"; exit 1; }

echo "== filenames are ASCII =="
# BSD grep (macOS) has no -P, and would exit 2 on it — with `&&` that
# failure would short-circuit and let a bad filename through silently.
if LC_ALL=C grep -q '[^ -~]' "$OUT/kit-listing.txt"; then
  echo "FAIL: non-ASCII filename in the kit folder"; exit 1
fi

echo "== pads loaded =="
adb logcat -d -s SnipSnapPad | tee "$OUT/pad.log"
grep -c 'loaded (sample' "$OUT/pad.log"

cat <<'CHECKS'

Automated checks passed. Screenshots and logs are in the output directory.

Remaining checks a script cannot make — do these on the device:

  1. Tap the kit, then tap a pad.
     You HEAR it, and this prints a non-zero stream id:
       adb logcat -d -s SnipSnapPad | grep 'hit ->'

  2. NEW BLANK TAPE -> type a name -> ROLL IT.
     The tape appears on the shelf, and:
       adb shell run-as com.snipsnap.app ls files/kits
     Try "BAD/NAME" first: it must be refused inline, not create a folder.

  3. Open a second kit, tap its pads: you hear THAT kit, not the first one.

  4. Menu -> the gear -> pick each of the six schemes.
     Chrome recolours; the LCD stays dark in all six.

  5. Force-stop and relaunch: the chosen scheme is still set, and the
     shelf still has every tape (no re-seed).
CHECKS
```

```bash
chmod +x scripts/m0-exit-test.sh
```

- [ ] **Step 8: Run the full suite and the exit test**

```bash
./gradlew test :app:testDebugUnitTest
./scripts/m0-exit-test.sh /tmp/m0
```

Expected: every module green (525 existing + the new tests), the script's automated assertions pass, and the screenshots show the shelf and the grid.

- [ ] **Step 9: Mark M0 done in `docs/APP_PLAN.md`**

In the "Where the project stands" table, change the Android app row from **not started** to:

```markdown
| **The Android app** | **M0 done** — `:app` scaffolded on Compose over all six modules; TapeOS theme, window shell, kit shelf, audible pad grid, scheme picker. M1 (capture) is next |
```

And prefix the M0 heading with a done marker:

```markdown
### M0 — Walking skeleton · M — ✓ done
```

- [ ] **Step 10: Commit**

```bash
git add app/src scripts/m0-exit-test.sh docs/APP_PLAN.md
git commit -m "FRESH TAPE, Tape Properties, and the M0 exit test

Creating a kit finally has a UI path, so 'list, open, create' is true of
the app and not just the library. Name validation is a pure function
with its own tests — accept, reject, and the TEST egg are three outcomes
worth pinning. Six schemes pickable with a live chrome/LCD preview on
each swatch, the choice kept in two lines of key=value so settings stay
testable off-device and a corrupt file falls back instead of refusing to
start. m0-exit-test.sh automates what a script can check and names the
five things a human still has to."
```

---

## Self-Review

**1. Spec coverage** — every M0 clause maps to a task:

| `APP_PLAN.md` M0 clause | Task |
|---|---|
| Scaffold `:app` (Compose, minSdk 29) | 1 |
| depend on all six modules | 1 (Global Constraints names the actual six) |
| six scheme token tables | 2 (bound from `Schemes.ALL`) |
| the four fonts | 2 |
| bevel modifiers (raised/pressed/sunken) | 2 |
| two-surface rule as composables | 2 (`LcdSurface`/`ChromeSurface`) |
| SNIPSNAP.EXE window, menu row, status bar | 3 |
| Storage: kit folders via `KitStore` — **list** | 4 (`KitLibrary.list`, shelf UI) |
| — **open** | 4 (`KitLibrary.open`) + 5 (KIT screen) |
| — **create** | 4 (`KitLibrary.create`) + 6 (FRESH TAPE dialog — the UI path) |
| browse kits on a phone | 4 + 6 (script) |
| tap pads, hear WAVs (interim `SoundPool`) | 5 |
| flip schemes in Tape Properties | 6 |

**2. Placeholder scan** — no TBDs, and no step shows code it then tells you not to use. One deliberate deferral remains: `Screen` entries other than KITS/KIT/PROPS render a placeholder body, which is M1–M5 scope, not an M0 gap. Task 4's `onNew` is a no-op *within Task 4 only* and is wired in Task 6 Step 4g — the plan says so at both ends.

**3. Type consistency** — `KitEntry`/`KitLibrary` (Task 4) are consumed unchanged in Tasks 5–6; `PadSound` (Task 5, four members including `reset()`) is the interface `KitScreen` takes; `toColor()`/`LocalScheme`/`TapeFonts`/`Scheme.tokens()` (Task 2) keep the same names throughout; `slotForCell`/`cellForSlot` are defined and consumed in Task 5 only; `verifyKitName`/`NameVerdict` (Task 6) are consumed by `NewTapeDialog` in the same task. `LcdHeader` is defined once in Task 4's `KitsScreen.kt` and imported by Tasks 5 and 6 rather than redefined.

**4. Verified against the source, not assumed:**
- `Snip(samples, channels, sampleRate)` — channels is the **second** parameter (`audio/.../Cleanup.kt:12`). Passing the sample rate there trips `require(channels in 1..2)`.
- `ThumpKits.classic()` returns exactly 16 non-null pads, A01–A16.
- `KitBuilderModel.bank(0)` already returns slots 1..16 in order with nulls for empties.
- `res/font/` accepts only `.ttf/.otf/.ttc/.xml` with lowercase-underscore names, which is why the licences live in `app/licenses/`.
- `%d` localizes digits and `%x` does not — measured on this machine across `ar-EG`, `fa-IR`, `my-MM` (Task 1's table).

## Notes for the executing controller

- The plan modifies three core modules in Task 1 (`:xpm`, `:kit`, `:shell`). That is deliberate, not scope creep: it is the defect the Android port exposes, and `CLAUDE.md`'s architecture rule puts the fix in the module that owns it. It lands as its own commit, gated by `./gradlew test`, before any Android code is written.
- Task 1 is the only task that can fail for environmental reasons. If the Compose BOM and Kotlin 2.0.21 disagree, step the BOM down (see Task 1 Step 11) — do **not** bump Kotlin.
- Tasks 3–6 each end with an emulator check. Those are verification, not deliverables; a task is complete when its unit tests pass and its commit lands. If the emulator is unavailable, note it and continue — the suite is the gate.
