# OILSLICK Token Layer & Pad-Sheet Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every gap in `docs/DESIGN_GAP.md` §1, §2d–§2g and §3 that can be closed headlessly on this branch — the scheme token layer, the eight dark colorways, the shipped copy, and the pad-sheet treatment domain.

**Architecture:** `:shell` is the app's brain (`shell/build.gradle.kts`: *"this module IS the app's brain; `:app` binds Compose to it"*). Every value the design names becomes a testable Kotlin constant here, so a colour that exists only in a design file can be regression-tested. Six new `Scheme` fields get defaults derived from existing constructor params, so authoring eight schemes stays a data edit rather than 48 invented numbers. The pad-sheet treatments live in a new `:synth` table kept deliberately separate from the bank-B remix table.

**Tech Stack:** Kotlin 2.0.21 / JVM 17, Gradle, `kotlin.test` on the JUnit platform. No Android, no Compose — everything here runs under `./gradlew test`.

## Global Constraints

- Colours are packed `0xRRGGBB` `Int`. Never strings, never `0xAARRGGBB`.
- **The two-surface rule:** the LCD stays dark in every scheme. `SchemesTest` enforces `Scheme.luma(lcd) < 40` and `luma(lcdInk) - luma(lcd) > 80`. Any new scheme must pass unmodified.
- Test method names are backticked prose: ``fun `six schemes in picker order`()``. Assertions sweep `Schemes.ALL` as properties rather than spot-checking one scheme.
- `FxChain` validates macro **names** against `Eq/Squash/Crunch/Tape/Echo/Spring.MACROS` and **values** to `0f..1f` in its `init`. An unknown macro name throws at construction, so treatment chains are compile-time-ish safe but must use real names: `Tape` = `WOBBLE`/`DRIVE`/`AGE`, `Crunch` = `BITS`/`RATE`/`TONE`/`GRIT`, `Squash` = `AMOUNT`/`ATTACK`.
- All user-facing copy is SHOUTED — full caps, terminal period. See `docs/PERSONALITY.md`.
- Commit messages: one evocative line, then a plain body. Match `git log` style (`"Z6.3 art wire-through: the waveform tile ships by default"`). No `feat:`/`fix:` prefixes — this repo doesn't use them.
- Run the full module suite before every commit: `./gradlew :shell:test :synth:test :kit:test`.

## Decisions already taken (2026-08-29)

| Question | Decision |
|---|---|
| CLEAR scheme | **Cut.** All light shells retired. |
| How many colorways | **All eight** on the artboard ship, including SODIUM/ICE/VAPOR. |
| TAPE / DIRT treatments | **Author real chains** from `Tape` and `Crunch` macros. |
| FRESH TAPE roster | **Keep code names** (FACTORY, LUCKY DIP), add a VELOCITY starter, keep LUCKY DIP A/B. The design doc gets updated to the real seven. |
| `plan-03-live-android` | **Out of scope.** LOOP + Compose is a separate, gated plan. |

## Out of scope — follow-on plans

These gaps are real and remain open after this plan lands:

- **§2b TAKES + BIN** — needs a retention model in `:kit`. `Personality.kt` already ships `"EJECTED. THE BIN KEEPS IT 30 DAYS."` with nothing behind it.
- **§2c GROOVE PROG E** — user step-edit storage. A–D already map onto `GrooveVariations`.
- **§2a LOOP screen** — blocked on the `plan-03-live-android` branch decision.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt` | `Scheme` fields, the eight tables, `Type`, `Layout`, `Motion` | 1–4 |
| `shell/src/test/kotlin/com/snipsnap/shell/SchemesTest.kt` | scheme invariants | 1–4 |
| `shell/src/main/kotlin/com/snipsnap/shell/KitArt.kt` | two default-param call sites | 3 |
| `cli/src/main/kotlin/com/snipsnap/cli/ArtCommand.kt` | one default-param call site | 3 |
| `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` | shipped copy | 5 |
| `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt` | pad-sheet treatment table | 6 |
| `synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt` | persist the treatment name and amount | 7, 9 |
| `synth/src/main/kotlin/com/snipsnap/synth/Shuffle.kt` | record the name at remix time | 7 |
| `shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt` | the VELOCITY starter | 8 |
| `kit/src/main/kotlin/com/snipsnap/kit/Kit.kt` | `KitPad.ghosts` | 9 |
| `kit/src/main/kotlin/com/snipsnap/kit/KitStore.kt` | persist `ghosts` | 9 |
| `kit/src/main/kotlin/com/snipsnap/kit/ExportFormats.kt` | the `.XPJ` cycler label | 9 |

---

### Task 1: `Scheme` gains `warn` — the needle stops rendering cyan

`docs/DESIGN_GAP.md` §1a. The handoff splits one field into two: `lcd-alt` (OILSLICK `#40E0E8`, cyan) and `warn` (`#FFB000`, the needle and onset bars). `Scheme.amber` is currently doing both jobs, and OILSLICK sets it to cyan — so GROOVE's needle, spec'd as *"amber `warn` in OILSLICK-cyan slot"*, disappears into the readouts.

`amber` keeps its name and its meaning (the second LCD colour). `warn` is new.

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt` (the `Scheme` data class, ~line 21–57; all six scheme tables)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/SchemesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Scheme.warn: Int` — a warm warning colour, defaulting to `0xFFB000`. Tasks 2–4 add fields alongside it; Task 3 supplies per-scheme values.

- [ ] **Step 1: Write the failing test**

Add to `SchemesTest.kt`:

```kotlin
    @Test
    fun `warn is warm in every scheme - the needle is never the LCD colour`() {
        for (scheme in Schemes.ALL) {
            val r = (scheme.warn shr 16) and 0xFF
            val g = (scheme.warn shr 8) and 0xFF
            val b = scheme.warn and 0xFF
            assertTrue(
                r > g && g > b,
                "${scheme.id}: warn ${"%06x".format(scheme.warn)} is not warm — " +
                    "the needle and onset bars must read as a warning, not as readout text",
            )
        }
    }

    @Test
    fun `OILSLICK separates its warn from its amber slot`() {
        assertEquals(0x40E0E8, Schemes.OILSLICK.amber, "the amber slot is cyan in OILSLICK")
        assertEquals(0xFFB000, Schemes.OILSLICK.warn, "but the needle stays amber")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SchemesTest"`
Expected: FAIL — compilation error, `Unresolved reference: warn`.

- [ ] **Step 3: Add the field**

In `Schemes.kt`, in the `Scheme` data class, immediately after the `amber` param:

```kotlin
    /** The "amber" accent slot (second LCD colour). Cyan in OILSLICK. */
    val amber: Int,
    /** Sunken input/list background. */
    val field: Int,
    /**
     * Warnings, the GROOVE needle, onset bars. Always warm, and deliberately
     * *not* [amber] — OILSLICK spends its amber slot on cyan, so a needle
     * drawn with [amber] vanishes into the readouts it is supposed to cross.
     */
    val warn: Int = 0xFFB000,
```

`warn` must come after `field` because it has a default and Kotlin requires defaulted params to follow required ones only when callers use positional args — every scheme table here uses named args, but keeping defaults last matches the file's existing shape.

- [ ] **Step 4: Give the cool-amber schemes an explicit warn**

The default `0xFFB000` is correct for CHROME, FERRIC, METAL and SNACK_BAR, whose `amber` is already warm. OILSLICK's and CLEAR's are not — add `warn` to those two tables only (CLEAR is deleted in Task 3, but leave it compiling until then):

```kotlin
    val OILSLICK = Scheme(
        SchemeId.OILSLICK, "t-oilslick",
        gray = 0x221A34, grayHi = 0x40306A, grayEdge = 0x2A2044, grayMid = 0x120E1A, grayDark = 0x060410,
        ink = 0xC8B2F8, ink2 = 0x7A6AA0,
        title1 = 0x5A2AE0, title2 = 0xE040C8, titleInk = 0xFFFFFF,
        desk1 = 0x0C0618, desk2 = 0x140B24,
        lcd = 0x0A0714, lcdInk = 0xC8B2F8, amber = 0x40E0E8, field = 0x161020,
        warn = 0xFFB000,
    )
```

CLEAR's `amber` is `0xFF9A1A`, already warm — no change needed there.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SchemesTest"`
Expected: PASS, all tests.

- [ ] **Step 6: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt \
        shell/src/test/kotlin/com/snipsnap/shell/SchemesTest.kt
git commit -m "The needle gets its own amber back

Scheme.amber was doing two jobs — the second LCD colour and the warning
colour — and OILSLICK spends its amber slot on cyan, so the GROOVE needle
was being drawn in the same colour as the readouts it crosses. warn is now
its own field, defaulting to #FFB000, and a property test holds every
scheme's warn to a warm hue."
```

---

### Task 2: The five remaining new tokens

`docs/DESIGN_GAP.md` §1b. The handoff's token table names six fields `Scheme` doesn't have; Task 1 added `warn`. The other five are `raised`, `win`, `winFrame`, `deskGlow`, `ink3`, `accent`.

Four of them fall out of values the schemes already carry — verified against OILSLICK's handoff row:

| new token | handoff value | derivation | exact? |
|---|---|---|---|
| `accent` | `#E040C8` | `= title2` (OILSLICK `title2 = 0xE040C8`) | ✓ |
| `raised` | `#221A34 → #161020` | `= gray` (start; the end is `field`) | ✓ |
| `winFrame` | the OILSLICK sweep | `= title2`; OILSLICK renders `OILSLICK_SWEEP` instead | ✓ |
| `win` | `#1A1424 → #120E1A` | `= grayMid` (the end); OILSLICK pins the start | ✗ pin |
| `deskGlow` | radial `#2A1050 → #0C0618` | `= desk2`; OILSLICK pins the glow centre | ✗ pin |
| `ink3` | `#584A80` | `= ink2`; OILSLICK pins the third tier | ✗ pin |

Defaults referencing earlier constructor params is legal Kotlin and keeps seven schemes from needing 35 invented numbers.

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/SchemesTest.kt`

**Interfaces:**
- Consumes: `Scheme.warn` from Task 1.
- Produces: `Scheme.raised`, `.win`, `.winFrame`, `.deskGlow`, `.ink3`, `.accent` — all `Int`, all defaulted. Task 3's eight tables override only where the artboard or handoff pins a value.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `OILSLICK matches the handoff token table exactly`() {
        val s = Schemes.OILSLICK
        assertEquals(0xE040C8, s.accent, "accent — selection and row inset bar")
        assertEquals(0x221A34, s.raised, "raised — button and empty-pad gradient start")
        assertEquals(0x1A1424, s.win, "win — window body gradient start")
        assertEquals(0x2A1050, s.deskGlow, "desk-glow — the radial behind everything")
        assertEquals(0x584A80, s.ink3, "ink3 — third text tier")
    }

    @Test
    fun `the three ink tiers descend in every scheme`() {
        for (scheme in Schemes.ALL) {
            val tiers = listOf(scheme.ink, scheme.ink2, scheme.ink3).map { Scheme.luma(it) }
            val descending = tiers.zipWithNext().all { (a, b) -> a >= b }
            val ascending = tiers.zipWithNext().all { (a, b) -> a <= b }
            assertTrue(
                descending || ascending,
                "${scheme.id}: ink tiers $tiers don't form a hierarchy — " +
                    "ink2 and ink3 must step away from ink, not straddle it",
            )
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SchemesTest"`
Expected: FAIL — `Unresolved reference: accent`.

- [ ] **Step 3: Add the five fields**

In the `Scheme` data class, after `warn`:

```kotlin
    /**
     * Third text tier, below [ink2]. OILSLICK uses it for the dimmest
     * chrome labels; schemes that never needed a third tier reuse [ink2].
     */
    val ink3: Int = ink2,
    /** Selection: the chosen menu item, the 3px inset bar on a selected row. */
    val accent: Int = title2,
    /** Raised-surface gradient start (buttons, empty pads). Ends at [field]. */
    val raised: Int = gray,
    /** Window-body gradient start. Ends at [grayMid]. */
    val win: Int = grayMid,
    /**
     * Window frame, 3px. Replaces the bevel highlight/shadow pair for
     * schemes that don't bevel — OILSLICK draws [Schemes.OILSLICK_SWEEP]
     * here instead of a flat colour.
     */
    val winFrame: Int = title2,
    /** Centre of the radial glow behind the desk; fades to [desk1]. */
    val deskGlow: Int = desk2,
```

- [ ] **Step 4: Pin OILSLICK's three**

Extend the OILSLICK table from Task 1:

```kotlin
        warn = 0xFFB000, ink3 = 0x584A80, win = 0x1A1424, deskGlow = 0x2A1050,
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SchemesTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt \
        shell/src/test/kotlin/com/snipsnap/shell/SchemesTest.kt
git commit -m "Six tokens the new table treats as load-bearing

raised, win, winFrame, deskGlow, ink3 and accent. Four derive from values
the schemes already carry — accent is title2 and raised is gray, exactly,
in OILSLICK — so only three needed pinning. The bevel quartet stays for
now; OILSLICK has no bevels and winFrame is what replaces them."
```

---

### Task 3: Eight dark colorways, four retirements, a new default

`docs/DESIGN_GAP.md` §2f. `design/Schemes.dc.html` carries eight complete token sets in exactly the shape `Scheme` declares. CLEAR is cut, and CHROME, FERRIC and SNACK_BAR go with the light shells.

This is the task that breaks call sites — `SchemesTest` pins the six-scheme roster in six places, and three production sites default to `Schemes.CHROME`.

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt` (`SchemeId` enum, all tables, `ALL`, `DEFAULT`, `padLabelInk`, `CLEAR_PAD_INK`)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/KitArt.kt:66` and `:111` — `scheme: Scheme = Schemes.CHROME`
- Modify: `cli/src/main/kotlin/com/snipsnap/cli/ArtCommand.kt:40` — `?: Schemes.CHROME`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/SchemesTest.kt`

**Interfaces:**
- Consumes: every field from Tasks 1–2.
- Produces: `SchemeId.{METAL, OILSLICK, PETROL, INFRARED, ACID, SODIUM, ICE, VAPOR}`; `Schemes.{PETROL, INFRARED, ACID, SODIUM, ICE, VAPOR}`; `Schemes.DEFAULT == OILSLICK`. `SchemeId.CHROME`, `.FERRIC`, `.SNACK_BAR`, `.CLEAR` and `Schemes.CLEAR_PAD_INK` cease to exist.

- [ ] **Step 1: Rewrite the roster test**

Replace the existing `six schemes in picker order` test in `SchemesTest.kt` with:

```kotlin
    @Test
    fun `eight dark schemes in picker order, OILSLICK by default`() {
        assertEquals(
            listOf(
                SchemeId.METAL, SchemeId.OILSLICK, SchemeId.PETROL, SchemeId.INFRARED,
                SchemeId.ACID, SchemeId.SODIUM, SchemeId.ICE, SchemeId.VAPOR,
            ),
            Schemes.ALL.map { it.id },
        )
        assertEquals(SchemeId.OILSLICK, Schemes.DEFAULT.id)
    }

    @Test
    fun `every scheme is dark now - the light shells are gone`() {
        for (scheme in Schemes.ALL) {
            assertTrue(
                Scheme.luma(scheme.gray) < 90,
                "${scheme.id}: chrome ${"%06x".format(scheme.gray)} is a light shell, and those were cut",
            )
        }
    }
```

Then delete the two CLEAR assertions at `SchemesTest.kt:71-72` and `:77`, and change `:59`'s `listOf(Schemes.OILSLICK, Schemes.CLEAR)` to `listOf(Schemes.OILSLICK)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SchemesTest"`
Expected: FAIL — `Unresolved reference: PETROL`.

- [ ] **Step 3: Rewrite the enum and tables**

Replace the `SchemeId` enum:

```kotlin
enum class SchemeId(val displayName: String) {
    METAL("METAL"),
    OILSLICK("OILSLICK"),
    PETROL("PETROL"),
    INFRARED("INFRARED"),
    ACID("ACID"),
    SODIUM("SODIUM"),
    ICE("ICE"),
    VAPOR("VAPOR"),
}
```

Delete the `CHROME`, `FERRIC`, `SNACK_BAR` and `CLEAR` `Scheme` tables. Keep `METAL` and `OILSLICK` as they stand. Add six, transcribed from `design/Schemes.dc.html` — `--gray/--g-hi/--g-e/--g-mid/--g-dk` map to `gray/grayHi/grayEdge/grayMid/grayDark`, `--t1/--t2` to `title1/title2`:

```kotlin
    val PETROL = Scheme(
        SchemeId.PETROL, "t-petrol",
        gray = 0x141A28, grayHi = 0x30406A, grayEdge = 0x1A2438, grayMid = 0x0C101C, grayDark = 0x04060E,
        ink = 0xB2C8F8, ink2 = 0x6A7CA0,
        title1 = 0x2A6AE0, title2 = 0x40E890, titleInk = 0xFFFFFF,
        desk1 = 0x060A18, desk2 = 0x0B1224,
        lcd = 0x070A14, lcdInk = 0xB2C8F8, amber = 0x40E890, field = 0x101624,
    )

    val INFRARED = Scheme(
        SchemeId.INFRARED, "t-infrared",
        gray = 0x221218, grayHi = 0x5C3040, grayEdge = 0x2C141A, grayMid = 0x12080C, grayDark = 0x0A0304,
        ink = 0xF8B2C0, ink2 = 0xA06A78,
        title1 = 0x9A2AE0, title2 = 0xE02A5A, titleInk = 0xFFFFFF,
        desk1 = 0x160408, desk2 = 0x200A10,
        lcd = 0x120608, lcdInk = 0xF8B2C0, amber = 0xFF8A1A, field = 0x1A0C12,
        warn = 0xFF8A1A,
    )

    val ACID = Scheme(
        SchemeId.ACID, "t-acid",
        gray = 0x161E0E, grayHi = 0x4A6030, grayEdge = 0x243218, grayMid = 0x0E160A, grayDark = 0x060A02,
        ink = 0xD4F0A0, ink2 = 0x8AA060,
        title1 = 0x6AB010, title2 = 0x20D0E8, titleInk = 0xFFFFFF,
        desk1 = 0x0A1204, desk2 = 0x101A08,
        lcd = 0x0A1004, lcdInk = 0xD4F0A0, amber = 0x20D0E8, field = 0x121A0A,
    )

    val SODIUM = Scheme(
        SchemeId.SODIUM, "t-sodium",
        gray = 0x221A0C, grayHi = 0x5C4C28, grayEdge = 0x2C2410, grayMid = 0x120E06, grayDark = 0x080502,
        ink = 0xF0D8A8, ink2 = 0xA08A58,
        title1 = 0xC85A10, title2 = 0xFFB000, titleInk = 0xFFFFFF,
        desk1 = 0x160E02, desk2 = 0x201606,
        lcd = 0x120C04, lcdInk = 0xFFD25E, amber = 0xFF6A2A, field = 0x1A140A,
        warn = 0xFF6A2A,
    )

    val ICE = Scheme(
        SchemeId.ICE, "t-ice",
        gray = 0x10202E, grayHi = 0x305468, grayEdge = 0x16283A, grayMid = 0x081420, grayDark = 0x030A12,
        ink = 0xB8E2F8, ink2 = 0x6A92A8,
        title1 = 0x1A5AC8, title2 = 0x58C8FF, titleInk = 0xFFFFFF,
        desk1 = 0x04101C, desk2 = 0x081826,
        lcd = 0x060E16, lcdInk = 0xB8E2F8, amber = 0x58C8FF, field = 0x0C1A26,
    )

    val VAPOR = Scheme(
        SchemeId.VAPOR, "t-vapor",
        gray = 0x10201C, grayHi = 0x2E5C4A, grayEdge = 0x1A2C26, grayMid = 0x081410, grayDark = 0x040C08,
        ink = 0xA8F0D8, ink2 = 0x62A08A,
        title1 = 0x10B088, title2 = 0x40E8C0, titleInk = 0xFFFFFF,
        desk1 = 0x061410, desk2 = 0x0A1C16,
        lcd = 0x06120E, lcdInk = 0xA8F0D8, amber = 0xB27AF8, field = 0x0C1A16,
    )
```

PETROL, ACID, ICE and VAPOR have cool `amber` slots and take the default `warn = 0xFFB000`. INFRARED and SODIUM pin their own, warmer than the default and already on-palette.

Then:

```kotlin
    /** Picker order — the order the design system tells its story in. */
    val ALL: List<Scheme> = listOf(METAL, OILSLICK, PETROL, INFRARED, ACID, SODIUM, ICE, VAPOR)

    val DEFAULT: Scheme = OILSLICK
```

- [ ] **Step 4: Simplify `padLabelInk` and delete the light table**

With CLEAR gone the branch has one arm. Replace the body:

```kotlin
    /**
     * Ink for a handwritten pad label sitting **on** a class-coloured pad.
     *
     * Near-black class-tinted inks, verbatim from the working prototypes. A
     * class colour not in the table (TONAL, UNKNOWN) falls back to darkening
     * the class colour itself.
     */
    fun padLabelInk(scheme: Scheme, drumClass: DrumClass): Int {
        val c = classColor(drumClass)
        return DARK_PAD_INK[c] ?: darken(c, 0.75f)
    }
```

Delete the entire `CLEAR_PAD_INK` map. Leave the `scheme` parameter in place — it is part of the public shape the app calls and will matter again if a light scheme ever returns.

- [ ] **Step 5: Fix the three CHROME call sites**

`shell/src/main/kotlin/com/snipsnap/shell/KitArt.kt`, both line 66 and line 111:

```kotlin
        scheme: Scheme = Schemes.DEFAULT,
```

`cli/src/main/kotlin/com/snipsnap/cli/ArtCommand.kt:40`:

```kotlin
        val scheme = opts["--scheme"]?.let { parseScheme(it) } ?: Schemes.DEFAULT
```

Using `Schemes.DEFAULT` rather than `Schemes.OILSLICK` means the next default change touches one line.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :shell:test :cli:test`
Expected: PASS. `KitArtTest.kt:52` iterates `Schemes.ALL` and now covers eight schemes; if it asserts a fixed count, update the number rather than the loop.

- [ ] **Step 7: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt \
        shell/src/main/kotlin/com/snipsnap/shell/KitArt.kt \
        cli/src/main/kotlin/com/snipsnap/cli/ArtCommand.kt \
        shell/src/test/kotlin/com/snipsnap/shell/SchemesTest.kt
git commit -m "Eight dark schemes, and the lights go out for good

CHROME, FERRIC, SNACK BAR and CLEAR are retired; PETROL, INFRARED, ACID,
SODIUM, ICE and VAPOR arrive transcribed from the Schemes artboard. OILSLICK
is the default. padLabelInk loses its light-scheme arm and CLEAR_PAD_INK
goes with it. Every scheme is now dark, which a property test now holds."
```

---

### Task 4: Rock Salt, and the layout constants the new screens need

`docs/DESIGN_GAP.md` §3 Q1 and the LOOP/GROOVE layout numbers. The handoff's Type table says Rock Salt *"(was Permanent Marker; tweakable)"*; the LOOP section still says Permanent Marker. The Type table is the authority — it is the section whose job is typefaces, and it explicitly records the change.

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt` (`Type`, `Layout`, `Motion`)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/SchemesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Type.MARKER == "Rock Salt"`; `Layout.{LCD_HEADER_MAX_H, TRACK_HEADER_H, BLOCK_MIN_H, BLOCK_MAX_H, LANDSCAPE_W, LANDSCAPE_H, NEEDLE_Y, STEP_W, NOTE_H}`; `Motion.BUBBLE_DRAG_SCALE`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `the handwriting is Rock Salt`() {
        assertEquals("Rock Salt", Type.MARKER)
    }

    @Test
    fun `LOOP block cells have room to grow but not to sprawl`() {
        assertTrue(Layout.BLOCK_MIN_H < Layout.BLOCK_MAX_H, "a block cell grows between two bounds")
        assertTrue(Layout.BLOCK_MIN_H >= 30, "below 30dp a two-line block cell clips its name")
        assertTrue(Layout.TRACK_HEADER_H >= 24, "the header is a tap target for mute")
    }

    @Test
    fun `the landscape frame is the portrait frame turned over`() {
        assertTrue(
            Layout.LANDSCAPE_W > Layout.LANDSCAPE_H,
            "landscape is wider than tall",
        )
        assertTrue(
            Layout.LANDSCAPE_H < Layout.FRAME_W,
            "landscape loses height to the system bars — 362 against a 390 width",
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SchemesTest"`
Expected: FAIL — `expected:<Rock Salt> but was:<Permanent Marker>`.

- [ ] **Step 3: Update `Type` and extend `Layout` and `Motion`**

In `Type`, replace the `MARKER` constant:

```kotlin
    /**
     * Rock Salt — handwriting on pads, cassette labels and loop blocks.
     * 11–15dp. Replaced Permanent Marker; the LOOP section of the handoff
     * still names the old face and is stale.
     */
    const val MARKER = "Rock Salt"
```

In `Layout`, after `MIN_HIT_TARGET`:

```kotlin
    /** LCD headers run 40–44 depending on whether they carry a counter. */
    const val LCD_HEADER_MAX_H = 44

    // LOOP — the six-column phasing grid.
    /** Track column header; tap toggles mute. */
    const val TRACK_HEADER_H = 24
    /** A block cell grows between these bounds to fill its column. */
    const val BLOCK_MIN_H = 30
    const val BLOCK_MAX_H = 46
    /** Landscape LOOP frame — transport moves to a top bar, cycle strip to the bottom. */
    const val LANDSCAPE_W = 816
    const val LANDSCAPE_H = 362

    // GROOVE — the needle-roll.
    /** The needle is fixed; the notes scroll under it. */
    const val NEEDLE_Y = 96
    /** Horizontal distance one step travels. */
    const val STEP_W = 20
    /** Note block height in a lane. */
    const val NOTE_H = 17
```

In `Motion`, after `QUIP_ROTATE_MS`:

```kotlin
    /** The bubble swells while dragged, so the eject gesture reads as physical. */
    const val BUBBLE_DRAG_SCALE = 1.08f
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SchemesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt \
        shell/src/test/kotlin/com/snipsnap/shell/SchemesTest.kt
git commit -m "Rock Salt on the pads, and numbers for the screens not built yet

The Type table renamed the handwriting and the LOOP section didn't get the
memo; Type wins. Layout gains the LOOP and GROOVE constants — track header,
block bounds, landscape frame, needle position, step width — so the Compose
work has somewhere to read them from instead of inventing them inline."
```

---

### Task 5: The copy the machine hasn't learned to say

`docs/DESIGN_GAP.md` §2g. All 13 lines under *"Shipped copy adds"* and 4 of the 5 PAD SHEET toasts are missing from `Personality.kt` — 17 of 18.

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` (the `Copy` object)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/PersonalityTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: 17 new `const val`s on `Copy`, plus `Copy.keySet(key: String)` and `Copy.takeRestored(take: String)` as functions because they interpolate.

- [ ] **Step 1: Write the failing test**

Add to `PersonalityTest.kt`:

The key cycler is the one line that is deliberately *not* all-caps — `"Am SET."` keeps the key name readable — so it stays out of the shout loop and gets its own shape assertion:

```kotlin
    @Test
    fun `every new toast shouts and stops`() {
        val lines = listOf(
            Copy.MELODIC_ON, Copy.KEY_OFF, Copy.TEACHING_ON, Copy.TEACHING_OFF,
            Copy.BANK_B_LIT, Copy.TWINS_REROLLED, Copy.BACK_FROM_BIN, Copy.BIN_EMPTIED,
            Copy.HUMANIZED, Copy.FORKED_TO_E, Copy.BAR_WIPED, Copy.GHOSTS_ON,
            Copy.TREATED, Copy.INSTRUMENT_MADE, Copy.NO_PITCH,
        )
        for (line in lines) {
            assertEquals(line.uppercase(), line, "TapeOS shouts: '$line'")
            assertTrue(line.endsWith("."), "every line lands on a full stop: '$line'")
        }
    }

    @Test
    fun `the interpolated lines name what they acted on`() {
        assertTrue(Copy.keySet("Am").startsWith("Am SET."), "the key leads its own toast")
        assertTrue(Copy.keySet("Am").endsWith("."), "and still lands on a full stop")
        assertTrue(Copy.takeRestored("T3").startsWith("T3 RESTORED."), "the take leads its own toast")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.PersonalityTest"`
Expected: FAIL — `Unresolved reference: MELODIC_ON`.

- [ ] **Step 3: Add the copy**

In `Personality.kt`, inside `object Copy`, after the existing constants:

```kotlin
    // ---- CHOP: the melodic rule (X1.3) ----
    const val MELODIC_ON = "MELODIC. THE PADS BECOME A SCALE, LOW LEFT."

    // ---- KIT: the key cycler (F5.3) ----
    fun keySet(key: String): String =
        "$key SET. TONAL PADS RETUNE ON ASSIGN — THE KICK IS UNTOUCHED."
    const val KEY_OFF = "KEY OFF. EVERYTHING LANDS AS CAPTURED."

    // ---- Settings: teach the machine (X4.4) ----
    const val TEACHING_ON = "TEACHING ON. THE MACHINE LEARNS FROM YOUR CORRECTIONS."
    const val TEACHING_OFF = "TEACHING OFF. THE MACHINE STOPS TAKING NOTES."

    // ---- BANK B: evil twins (W4.3) ----
    const val BANK_B_LIT = "BANK B LIT. YOUR KIT, BUT EVIL. RECIPES KEPT."
    const val TWINS_REROLLED = "TWINS REROLLED. SAME SEED, DIFFERENT SINS."

    // ---- TAKES + BIN (X2.3) ----
    fun takeRestored(take: String): String = "$take RESTORED. THE PAST, REPLAYED."
    const val BACK_FROM_BIN = "BACK FROM THE BIN. NO QUESTIONS ASKED."
    const val BIN_EMPTIED = "BIN EMPTIED. THE MACHINE FORGETS, AS ASKED."

    // ---- GROOVE ----
    const val HUMANIZED = "HUMANIZED. NOBODY PLAYS LIKE A ROBOT."
    const val FORKED_TO_E = "FORKED TO PROG E. A–D STAY UNTOUCHED."
    const val BAR_WIPED = "BAR WIPED. THE MACHINE FORGIVES."

    // ---- PAD SHEET ----
    const val GHOSTS_ON = "GHOST LAYERS ON. QUIET HITS GO SOFT, NOT JUST QUIETER."
    const val TREATED = "CRUSH ON A02. ORIGINAL SLEEPS IN THE BIN."
    const val INSTRUMENT_MADE = "ONE NOTE IN, WHOLE KEYBOARD OUT. INSTRUMENT ON THE SHELF."
    const val NO_PITCH = "NO CONFIDENT PITCH. THE MACHINE REFUSES POLITELY."
```

`TREATED` is spec'd with a literal pad and treatment. Leave it literal for now — the pad sheet's real call site will want `fun treated(treatment: String, pad: String)`, and inventing that signature before the screen exists would be guessing at its caller. Note this in the commit body.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.PersonalityTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/Personality.kt \
        shell/src/test/kotlin/com/snipsnap/shell/PersonalityTest.kt
git commit -m "Seventeen things the machine could not yet say

The handoff's shipped-copy list grew with BANK B, TAKES, the BIN, PROG E and
the pad sheet, and none of it existed in Copy. Two lines interpolate — the
key cycler and the take restore — so they land as functions. TREATED stays
literal until the pad sheet exists to tell us what its caller looks like.

A property test holds every new line to the house voice: full caps, full stop."
```

---

### Task 6: TAPE and DIRT become real, and AMT reaches zero

`docs/DESIGN_GAP.md` §1c. Two defects and one authoring job:

1. `Treatments.chain()` has `require(amount > 0f && amount <= 1f)`, but the pad sheet specs *"AMT 0-100 in 5s"* — AMT=0 is one stepper tap away and throws.
2. `NONE` is not a name in `Shuffle.TREATMENTS`; calling `chain("NONE", …)` throws `unknown treatment`.
3. `TAPE` and `DIRT` match no existing chain.

**The new chains do not join `Shuffle.TREATMENTS`.** `withRemixBank` picks with `TREATMENTS[random.nextInt(TREATMENTS.size)]`, so growing that list from 5 to 7 changes what every previously-saved seed produces. Bank B's reproducibility is a promise the code already makes (`"TWINS REROLLED. SAME SEED, DIFFERENT SINS."` implies the same seed gives the same sins). The pad sheet gets its own table.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt` (create if absent)

**Interfaces:**
- Consumes: `Tape.MACROS` (`WOBBLE`/`DRIVE`/`AGE`), `Crunch.MACROS` (`BITS`/`RATE`/`TONE`/`GRIT`), `Squash.MACROS` (`AMOUNT`/`ATTACK`), `Shuffle.TREATMENTS`.
- Produces: `Treatments.NONE: String`; `Treatments.PAD_SHEET: List<Pair<String, FxChain>>`; `Treatments.padSheetNames: List<String>`; `Treatments.chain(name, amount)` accepting `amount == 0f` and `name == "NONE"`, both returning a bypass `FxChain()`.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreatmentsTest {

    private fun tick(): Snip {
        val n = 4410
        val samples = FloatArray(n) { i ->
            val env = 1f - i.toFloat() / n
            (if (i % 8 < 4) 0.6f else -0.6f) * env * env
        }
        return Snip(samples, 1, 44_100)
    }

    @Test
    fun `the pad sheet offers exactly NONE CRUSH TAPE DIRT`() {
        assertEquals(listOf("NONE", "CRUSH", "TAPE", "DIRT"), Treatments.padSheetNames)
    }

    @Test
    fun `AMT zero is reachable and does nothing`() {
        assertTrue(Treatments.chain("CRUSH", 0f).isBypass, "AMT 0 is a bypass, not a crash")
        val s = tick()
        val out = Treatments.apply("CRUSH", s, 0f).snip
        assertEquals(s.samples.toList(), out.samples.toList(), "AMT 0 must not touch the audio")
    }

    @Test
    fun `NONE is a treatment the pad sheet can pick`() {
        assertTrue(Treatments.chain("NONE", 1f).isBypass, "NONE is the absence of treatment, not an error")
    }

    @Test
    fun `TAPE and DIRT are real chains that change the audio`() {
        val s = tick()
        for (name in listOf("TAPE", "DIRT")) {
            val out = Treatments.apply(name, s, 1f).snip
            assertTrue(
                out.samples.toList() != s.samples.toList(),
                "$name left the audio untouched — it isn't wired to a chain",
            )
        }
    }

    @Test
    fun `the remix bank keeps its five - adding pad-sheet treatments must not reseed bank B`() {
        assertEquals(
            listOf("reversed", "crushed", "slapback", "washed", "punched"),
            Shuffle.TREATMENTS.map { it.first },
            "growing this list changes what every saved seed produces",
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.TreatmentsTest"`
Expected: FAIL — `Unresolved reference: padSheetNames`.

- [ ] **Step 3: Author the chains and fix the range**

In `Treatments.kt`, inside `object Treatments`, above the existing `names`:

```kotlin
    /** The pad sheet's "no treatment" segment. Not a chain — the absence of one. */
    const val NONE = "NONE"

    /**
     * The PAD SHEET's four segments, which are deliberately **not**
     * [Shuffle.TREATMENTS].
     *
     * The remix bank picks from its own five by index against a seed, so
     * growing that list would change what every previously-saved seed
     * produces — bank B promises the same seed gives the same sins. These
     * four are the per-pad vocabulary instead: CRUSH borrows the remix
     * bank's converter, TAPE and DIRT are their own.
     */
    val PAD_SHEET: List<Pair<String, FxChain>> = listOf(
        "CRUSH" to Shuffle.TREATMENTS.first { it.first == "crushed" }.second,
        // Wow, flutter, saturation and a worn head. The tape *sound*, not the
        // tape *machine* — no echo, so it stacks under anything.
        "TAPE" to FxChain(
            tape = mapOf("WOBBLE" to 0.5f, "DRIVE" to 0.45f, "AGE" to 0.6f),
        ),
        // Grit into a small converter, squashed first so the drive has
        // something consistent to bite. Darker and nastier than CRUSH.
        "DIRT" to FxChain(
            squash = mapOf("AMOUNT" to 0.5f, "ATTACK" to 0.4f),
            crunch = mapOf("BITS" to 0.4f, "RATE" to 0.35f, "TONE" to 0.4f, "GRIT" to 0.85f),
        ),
    )

    /** Segment order for the pad sheet's control, NONE first. */
    val padSheetNames: List<String> = listOf(NONE) + PAD_SHEET.map { it.first }
```

Then replace `chain`:

```kotlin
    fun chain(name: String, amount: Float = 1f): FxChain {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        // AMT 0 and NONE are the same statement made two ways.
        if (name == NONE || amount <= 0f) return FxChain()
        val base = PAD_SHEET.firstOrNull { it.first == name }?.second
            ?: Shuffle.TREATMENTS.firstOrNull { it.first == name }?.second
            ?: throw IllegalArgumentException(
                "unknown treatment '$name' - try one of: ${(padSheetNames + names).joinToString(", ")}",
            )
        if (amount >= 0.999f) return base
        fun scale(params: Map<String, Float>?): Map<String, Float>? =
            params?.mapValues { (_, v) -> (v * amount).coerceIn(0f, 1f) }
        return base.copy(
            eq = scale(base.eq),
            squash = scale(base.squash),
            crunch = scale(base.crunch),
            tape = scale(base.tape),
            echo = scale(base.echo),
            spring = scale(base.spring),
        )
    }
```

The lookup tries `PAD_SHEET` first, then falls back to `Shuffle.TREATMENTS`, so the existing `snipsnap treat` CLI keeps working with `crushed`/`washed`/`punched` unchanged.

- [ ] **Step 4: Make `apply` bypass cleanly at zero**

`apply` builds `PadRecipe(fx = fx)`, and `PadRecipe`'s `init` requires `patch != null || fx != null` — an empty `FxChain()` is non-null, so it constructs. `FxChain.process` on a bypass chain returns the input through `capTail`, which may copy but must not alter samples. Verify the AMT-0 equality test passes; if `capTail` perturbs the tail, add an early return to `apply`:

```kotlin
    fun apply(name: String, snip: Snip, amount: Float = 1f): Treated {
        val fx = chain(name, amount)
        if (fx.isBypass) return Treated(snip, PadRecipe(fx = fx).toJsonValue())
        return Treated(fx.process(snip), PadRecipe(fx = fx).toJsonValue())
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :synth:test`
Expected: PASS — the whole `:synth` suite, not just `TreatmentsTest`, since `chain()` changed under the CLI's feet.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt \
        synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt
git commit -m "TAPE and DIRT get chains, and AMT is allowed to be zero

The pad sheet's four segments never matched the remix bank's five names.
CRUSH borrows the bank's converter; TAPE is wow, flutter and a worn head;
DIRT is grit into a small converter with a squash in front of it.

They live in their own table on purpose. withRemixBank picks by index
against a seed, so growing Shuffle.TREATMENTS from five to seven would
change what every saved seed produces, and a test now pins that list.

chain() also accepted (0, 1] while the design spec'd 0-100 in steps of 5,
so the first tap down from 5 threw. Zero and NONE now both mean bypass."
```

---

### Task 7: Bank B remembers which sin it committed

`docs/DESIGN_GAP.md` §2e. W4.3 wants a *"treatment tag top-right in accent 7px Silkscreen"* on each twin pad. `Shuffle.withRemixBank` destructures the name to `_` and persists only the `FxChain`, and no chain→name lookup exists — so the tag cannot be rendered from stored state.

Persisting the name at remix time is cheap and exact. A reverse lookup is not: `amount < 1` scales the macros, so chain equality breaks the moment anyone touches AMT.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Shuffle.kt:106-121` (`withRemixBank`)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt` (create if absent)

**Interfaces:**
- Consumes: `Treatments.PAD_SHEET`, `Shuffle.TREATMENTS`.
- Produces: `PadRecipe.treatment: String?` — the third constructor param, serialized as `"treatment"` in the recipe object, absent when null. `PadRecipe.VERSION` stays `1`: a new optional key is a backward-compatible addition, and old recipes parse with `treatment == null`.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PadRecipeTest {

    @Test
    fun `a treatment name survives a round trip`() {
        val r = PadRecipe(fx = Treatments.chain("TAPE"), treatment = "TAPE")
        val back = PadRecipe.fromJsonText(r.toJsonText())
        assertEquals("TAPE", back.treatment)
        assertEquals(r.fx, back.fx)
    }

    @Test
    fun `recipes written before the tag existed still parse`() {
        val old = """{"recipe":1,"fx":{"fx":1,"reverse":true}}"""
        assertNull(PadRecipe.fromJsonText(old).treatment, "an untagged recipe is not an error")
    }

    @Test
    fun `every remixed bank-B pad names its treatment`() {
        val bankA = Shuffle.kit(seed = 7).take(16)
        val both = Shuffle.withRemixBank(bankA, seed = 7)
        val bankB = both.drop(16).filterNotNull()
        assertEquals(bankA.filterNotNull().size, bankB.size, "every assigned pad gets a twin")
        for (pad in bankB) {
            val recipe = PadRecipe.fromJsonValue(pad.recipe!!)
            assertEquals(
                true,
                recipe.treatment in Shuffle.TREATMENTS.map { it.first },
                "a twin with no treatment tag can't be labelled: ${recipe.treatment}",
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PadRecipeTest"`
Expected: FAIL — `No value passed for parameter 'treatment'` / `Unresolved reference`.

- [ ] **Step 3: Add the field to `PadRecipe`**

```kotlin
data class PadRecipe(
    val patch: Patch? = null,
    val fx: FxChain? = null,
    /**
     * Which named treatment produced [fx], when one did.
     *
     * Stored rather than derived: [Treatments.chain] scales a chain's macros
     * by AMT, so a treated pad's chain stops equalling the table entry it
     * came from as soon as AMT leaves 100. Bank B's treatment tag and the pad
     * sheet's selected segment both read this.
     */
    val treatment: String? = null,
) {
```

In `toJsonValue`, after the `fx` line:

```kotlin
        fx?.let { obj["fx"] = it.toJsonValue() }
        treatment?.let { obj["treatment"] = JsonValue.Str(it) }
        return JsonValue.Obj(obj)
```

In `fromJsonValue`, in the returned `PadRecipe`:

```kotlin
            return PadRecipe(
                patch = obj["patch"]?.let { Patches.fromJsonValue(it) },
                fx = obj["fx"]?.let { FxChain.fromJsonValue(it) },
                treatment = obj["treatment"]?.str(),
            )
```

`JsonValue.str()` is defined at `json/src/main/kotlin/com/snipsnap/json/Json.kt:22`, alongside the `.int()` and `.obj()` this file already uses.

- [ ] **Step 4: Record the name at remix time**

In `Shuffle.kt`, `withRemixBank`, replace the destructure that discards the name:

```kotlin
        val bankB = bankA.map { pad ->
            if (pad == null) return@map null
            val (treatment, fx) = TREATMENTS[random.nextInt(TREATMENTS.size)]
            ArrangedPad(
                snip = fx.process(pad.snip),
                drumClass = pad.drumClass,
                recipe = PadRecipe(fx = fx, treatment = treatment).toJsonValue(),
                level = pad.level,
            )
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :synth:test :kit:test :shell:test`
Expected: PASS. `:kit` and `:shell` are in the blast radius because `StarterKits` renders `Shuffle.withRemixBank` through `KitAssembler`.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt \
        synth/src/main/kotlin/com/snipsnap/synth/Shuffle.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt
git commit -m "The twin remembers which sin it committed

withRemixBank threw the treatment name away and kept only the chain, so
bank B's treatment tag had nothing to render from. Stored, not derived:
AMT scales a chain's macros, so a treated pad stops equalling the table
entry it came from the moment anyone touches the amount.

The key is optional and the recipe version doesn't move — recipes written
before today parse with a null tag."
```

---

### Task 8: VELOCITY joins the fresh-tape menu

`docs/DESIGN_GAP.md` §3 Q2. The design's FRESH TAPE list names a **Velocity** starter with no builder behind it. `synth/Velocity.kt` exists and `ArrangedPad.softVariants` is the field it feeds — the wiring is missing, not the DSP.

The other roster differences resolve in the code's favour: FACTORY and LUCKY DIP stay (better copy than THUMP and SHUFFLE), and LUCKY DIP A/B stays.

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt`
- Modify: `design/HANDOFF.md` (the FRESH TAPE row — seven starters, real names)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/StarterKitsTest.kt`

**Interfaces:**
- Consumes: `Velocity.variants(snip, count)` → `List<Snip>`; `ThumpKits.classic()` → `List<ArrangedPad?>`; `ArrangedPad.softVariants: List<Snip>`.
- Produces: `StarterKits.byId("velocity")`, seventh entry in `StarterKits.ALL`.

- [ ] **Step 1: Write the failing test**

Add to `StarterKitsTest.kt`:

```kotlin
    @Test
    fun `seven starters, and velocity is one of them`() {
        assertEquals(
            listOf("factory", "lucky-dip", "lucky-dip-ab", "melodic", "chip", "cloud", "velocity"),
            StarterKits.ALL.map { it.id },
        )
    }

    @Test
    fun `the velocity starter gives every pad soft layers`() {
        val dir = kotlin.io.path.createTempDirectory("velocity").toFile()
        try {
            val kit = StarterKits.byId("velocity")!!.render("VELOCITY", dir)
            val assigned = kit.pads.filter { it.sampleFile.isNotBlank() }
            assertTrue(assigned.isNotEmpty(), "the starter rendered an empty kit")
            for (pad in assigned) {
                assertEquals(
                    3, pad.velocityLayers.size,
                    "${pad.displayName}: two soft zones plus the main sample on top",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }
```

Three, not two: `KitAssembler` builds `zoneCount = softVariants.size + 1` and adds a final `KitLayer` for the main sample on top of the soft zones (`KitAssembler.kt:100-112`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.StarterKitsTest"`
Expected: FAIL — the roster assertion fails with six ids, and `byId("velocity")` returns null.

- [ ] **Step 3: Add the starter**

In `StarterKits.kt`, add the import:

```kotlin
import com.snipsnap.synth.Velocity
```

and append to `ALL`, after `cloud`:

```kotlin
        Starter(
            "velocity", "VELOCITY",
            "The house kit with ghost notes. Soft hits sound soft, not just quiet.",
            seeded = false,
        ) {
            ThumpKits.classic().map { pad ->
                pad?.copy(softVariants = Velocity.variants(pad.snip, count = 2))
            }
        },
```

`Velocity.variants` peak-matches its output, so the soft layers are darker rather than quieter — the MPC's velocity curve owns level. That is why this reads as a different kit rather than the same kit turned down.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.StarterKitsTest"`
Expected: PASS.

- [ ] **Step 5: Correct the design doc**

In `design/HANDOFF.md`, the FRESH TAPE row reads *"6 starters from the `StarterKits` registry (Chip/Thump/Cloud/Melodic/Shuffle/Velocity)"*. Replace that parenthetical with:

```
7 starters from the `StarterKits` registry (FACTORY/LUCKY DIP/LUCKY DIP A-B/MELODIC/CHIP/CLOUD/VELOCITY; REROLL applies to the two LUCKY DIP entries, which are the seeded ones)
```

The REROLL note matters: the design specs a REROLL SEED control on the menu, but only `lucky-dip` and `lucky-dip-ab` have `seeded = true`. On the other five the control is a no-op and should render disabled.

- [ ] **Step 6: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt \
        shell/src/test/kotlin/com/snipsnap/shell/StarterKitsTest.kt \
        design/HANDOFF.md
git commit -m "VELOCITY was on the menu but never in the kitchen

The fresh-tape list promised a Velocity starter and no builder existed —
Velocity.soften and ArrangedPad.softVariants were both already there, just
never introduced. The house kit with two soft layers under every hit,
peak-matched so they read darker rather than quieter.

The handoff's roster is corrected to the seven that actually exist, with a
note that REROLL only means anything on the two seeded ones."
```

---

### Task 9: GHOSTS, the AMT that survives a reload, and the .XPJ label

`docs/DESIGN_GAP.md` §2d and §3 Q3 — the last two headless gaps.

`KitPad` already carries `level`, `pan`, `tuneCoarse`/`tuneFine`, `muteGroup` (the pad sheet's CHOKE GRP), `oneShot` and `source` (the Y1.3 provenance line). Two of the sheet's controls have nowhere to persist: **GHOSTS** (W5.3) and the treatment **AMT**. Task 7 stored the treatment *name*; the amount is the other half — without it the sheet cannot restore its own slider on reopen.

And Y3.3 renames the `.XPJ` cycler line, which promises grooves ride along with the kits.

**Files:**
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/Kit.kt` (the `KitPad` data class, ~line 36–66)
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/KitStore.kt` (save ~line 66–80, load ~line 124–145)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt`
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/ExportFormats.kt:24`
- Test: `kit/src/test/kotlin/com/snipsnap/kit/KitStoreTest.kt`, `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt` (from Task 7)

**Interfaces:**
- Consumes: `PadRecipe.treatment` from Task 7.
- Produces: `KitPad.ghosts: Boolean` (default `false`); `PadRecipe.amount: Float?`; `ExportFormat.MPC3_PROJECT.cyclerLabel == "MPC SESSION (.XPJ) — KITS + GROOVES"`.

- [ ] **Step 1: Write the failing tests**

Add to `kit/src/test/kotlin/com/snipsnap/kit/KitStoreTest.kt`:

```kotlin
    @Test
    fun `ghost layers survive a save and load`() {
        val dir = kotlin.io.path.createTempDirectory("ghosts").toFile()
        try {
            val kit = Kit(
                name = "GHOSTKIT",
                pads = listOf(
                    KitPad(slot = 1, sampleFile = "a.wav", ghosts = true),
                    KitPad(slot = 2, sampleFile = "b.wav"),
                ),
            )
            KitStore.save(kit, dir)
            val back = KitStore.load(dir)
            assertEquals(true, back.pads.first { it.slot == 1 }.ghosts, "GHOSTS was switched on and forgot")
            assertEquals(false, back.pads.first { it.slot == 2 }.ghosts, "and off stays off")
        } finally {
            dir.deleteRecursively()
        }
    }
```

Add to `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt`:

```kotlin
    @Test
    fun `the treatment amount round-trips with its name`() {
        val r = PadRecipe(fx = Treatments.chain("DIRT", 0.35f), treatment = "DIRT", amount = 0.35f)
        val back = PadRecipe.fromJsonText(r.toJsonText())
        assertEquals("DIRT", back.treatment)
        assertEquals(0.35f, back.amount)
    }
```

Add to `kit/src/test/kotlin/com/snipsnap/kit/ExportFormatsTest.kt` (create if absent, with the package line `package com.snipsnap.kit` and `import kotlin.test.Test` / `import kotlin.test.assertEquals`):

```kotlin
    @Test
    fun `the XPJ line says what actually rides along`() {
        assertEquals("MPC SESSION (.XPJ) — KITS + GROOVES", ExportFormat.MPC3_PROJECT.cyclerLabel)
        assertEquals("xpj", ExportFormat.MPC3_PROJECT.id, "the CLI word does not move")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kit:test :synth:test`
Expected: FAIL — `Unresolved reference: ghosts`, `No value passed for parameter 'amount'`, and the label assertion reporting `MPC 3 PROJECT (.XPJ)`.

- [ ] **Step 3: Add `KitPad.ghosts`**

In `Kit.kt`, in the `KitPad` data class, immediately after `oneShot`:

```kotlin
    val oneShot: Boolean = true,
    /**
     * Ghost layers: quiet hits render darker, not merely quieter, from the
     * pad's soft variants. Off by default — it only means anything on a pad
     * that has [velocityLayers] to reach for.
     */
    val ghosts: Boolean = false,
```

- [ ] **Step 4: Serialize it**

In `KitStore.kt`'s save block, alongside the other conditional appends after the `linkedMapOf` (next to `p.colorHex?.let { … }`):

```kotlin
                    if (p.ghosts) entries["ghosts"] = JsonValue.Bool(true)
```

Written only when true, so existing `kit.json` files don't grow a field that says nothing. In the load block, after `oneShot`:

```kotlin
                oneShot = p["oneShot"]?.bool() ?: true,
                ghosts = p["ghosts"]?.bool() ?: false,
```

- [ ] **Step 5: Add `PadRecipe.amount`**

In `PadRecipe.kt`, after the `treatment` param added in Task 7:

```kotlin
    /**
     * The AMT the pad sheet was set to when [treatment] was applied, 0..1.
     * Stored beside the name for the same reason: the scaled chain can't be
     * run backwards to recover the amount that produced it.
     */
    val amount: Float? = null,
```

In `toJsonValue`, after the `treatment` line:

```kotlin
        treatment?.let { obj["treatment"] = JsonValue.Str(it) }
        amount?.let { obj["amount"] = JsonValue.Num(it.toDouble()) }
```

In `fromJsonValue`:

```kotlin
                treatment = obj["treatment"]?.str(),
                amount = obj["amount"]?.num()?.toFloat(),
```

`JsonValue.num()` is public and returns `Double` (`Json.kt:23`).

- [ ] **Step 6: Relabel the .XPJ format**

In `ExportFormats.kt`, line 24:

```kotlin
    MPC3_PROJECT("xpj", "MPC SESSION (.XPJ) — KITS + GROOVES"),
```

The `id` stays `"xpj"` — it is the CLI's `--export` word and changing it would break every script that uses it. Only the wizard's cycler line moves.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :kit:test :synth:test :shell:test :cli:test`
Expected: PASS. `:shell` and `:cli` are in the blast radius — `ExportWizard` and the CLI's export help both read `cyclerLabel`, and a test may pin the old string.

- [ ] **Step 8: Commit**

```bash
git add kit/src/main/kotlin/com/snipsnap/kit/Kit.kt \
        kit/src/main/kotlin/com/snipsnap/kit/KitStore.kt \
        kit/src/main/kotlin/com/snipsnap/kit/ExportFormats.kt \
        synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt \
        kit/src/test/kotlin/com/snipsnap/kit/KitStoreTest.kt \
        kit/src/test/kotlin/com/snipsnap/kit/ExportFormatsTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt
git commit -m "GHOSTS remembers, AMT remembers, and the XPJ says what it carries

Two pad-sheet controls had nowhere to live: GHOSTS wasn't on KitPad at all,
and the treatment amount was thrown away the same way the name was — a
scaled chain can't be run backwards to recover the amount that made it, so
reopening the sheet lost the slider.

ghosts only writes to kit.json when true; old kits load as false.

The .XPJ cycler line now promises grooves as well as kits, per Y3.3. The
CLI word stays xpj — scripts depend on it."
```

---

## Verification

After Task 8:

```bash
./gradlew test
```

Expected: the whole suite green. The baseline before this plan was 629 tests; these tasks add roughly 21.

Then confirm nothing still points at a retired scheme:

```bash
find audio cli json kit mpc3 shell synth xpm -name '*.kt' -path '*/src/*' \
  | xargs grep -n "CHROME\|FERRIC\|SNACK_BAR\|SchemeId.CLEAR\|CLEAR_PAD_INK"
```

Expected: no output.

And that the gap report's §1 and §2d–§2g claims are now stale — update `docs/DESIGN_GAP.md` to strike the closed items, leaving §0 (branch divergence), §2a (LOOP), §2b (TAKES/BIN) and §2c (PROG E) standing.
