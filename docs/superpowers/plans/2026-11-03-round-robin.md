# Round Robin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make round robin a phone control (per pad and kit-wide, amount dial C..H, 4..8 takes) whose takes are clearly audible, survive edits, and really cycle on the MPC.

**Architecture:** Round-robin settings move out of the pad's recipe into a typed `KitPad.robin` field (plus a kit-level default `Kit.robin`), so the pad keeps its patch and every audio edit can "lift" the chain, edit the original, and re-render. The variation becomes a four-axis, dial-scaled render in `RobinVariation`. `.xpm` exports split a robin chain into up to 4 take WAVs on full-range layers with `ZonePlay` 0; every other export keeps today's chain path.

**Tech Stack:** Kotlin, Gradle multi-module (`:kit`, `:xpm`, `:shell`, `:cli`, `:app` Compose), kotlin.test, Android instrumented tests on AVD `snipsnap_pixel`.

**Spec:** `docs/superpowers/specs/2026-11-03-round-robin-design.md`

## Global Constraints

- `./gradlew --no-daemon` only, one Gradle at a time on this machine. If a `:cli` run fails with `NoSuchMethodError … copy$default`, run `:cli:clean` first.
- The dial runs from point C (0.0) to point H (1.0); the six ear-tested points sit at 0.0, 0.2, 0.4, 0.6, 0.8, 1.0. **Default F = 0.6.**
- Takes per pad: **4..8 on the phone controls, default 6**. `Robin.apply` and the CLI keep accepting 2..8.
- MPC `.xpm`: at most **4** take layers, each **0–127**, instrument **`ZonePlay` 0**. Take one is layer 1.
- Take one is always the untouched original. Same original + settings + seed + slot renders the same bytes.
- A kit with no robin pads exports byte-identical to today (all existing goldens pass unchanged).
- Copy law: every `Copy` const shouts and ends in a full stop (or sits on `PersonalityTest.notASentence`). Reversal law: a destructive line names a way back. Digits format with `Locale.ROOT`.
- Commit titles are declarative sentences with a why-body, ending with the session's attribution lines.

## Review Focus

1. **Rapid edits on a robin pad** (treat, then untreat, then turn off, all inside one second): the bin must restore in last-in-first-out order and "off" must leave the treated-or-untreated original, never a chain or a stale take. Test: Task 4 `edits on a robin pad undo in order even within one millisecond`.
2. **Re-importing an exported round-robin program** (`.xpn` or program folder back onto the phone): the importer meets four full-range layers and must land a playable pad, not refuse on overlapping velocity zones. Test: Task 6 `an exported robin program imports back as a playable pad`.
3. **A foreign chain** (a captured PSK kit, a CHOP FOLD pad, a `--zones` grid) on the kit-wide switch or the pad switch: left exactly as it was, and its export unchanged. Test: Task 5 `the kit switch leaves foreign chains and grids alone`, Task 6 `a foreign chain exports exactly as before`.
4. **A pad edited by a synth or recipe replay while round-robined**: the patch re-render must land on the original and the takes re-render; the pad must not end up a single take with stale chain boundaries. Test: Task 4 `recipe replay on a robin pad re-renders its takes`.
5. **Very short samples** (a 20 ms click) at H: the front trim must not eat the hit, every take keeps audio. Test: Task 2 `a very short hit keeps audio in every take at H`.

---

### Task 1: Robin settings on the pad and the kit

**Files:**
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/Kit.kt` (new `RobinSettings`, `KitRobin`; `KitPad.robin`; `Kit.robin`; `KitPad.isRobinChain`)
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/KitStore.kt` (codec, legacy synthesis)
- Test: `kit/src/test/kotlin/com/snipsnap/kit/KitStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class RobinSettings(val amount: Float?, val takes: Int, val seed: Int = 0, val followsKit: Boolean = true)` — `amount` 0..1 on the C..H dial; `null` = legacy variation (pads made before this work). `init` requires `takes in 2..8`, `amount` in 0..1 when set.
  - `data class KitRobin(val amount: Float = KitRobin.DEFAULT_AMOUNT, val takes: Int = KitRobin.DEFAULT_TAKES)` with `DEFAULT_AMOUNT = 0.6f`, `DEFAULT_TAKES = 6`, `MIN_TAKES = 4`, `MAX_TAKES = 8`.
  - `KitPad.robin: RobinSettings? = null`, `Kit.robin: KitRobin? = null`.
  - `val KitPad.isRobinChain: Boolean` — `chain != null && robin != null && chain.zones == null`.
  - JSON keys: pad `"robin": {"amount"?, "takes", "seed", "follows"}`; kit `"robin": {"amount", "takes"}`. Absent fields are not written.

- [ ] **Step 1: Write the failing tests** in `KitStoreTest`:

```kotlin
@Test fun `robin settings round-trip on pad and kit`() {
    val kit = Kit("K", listOf(chainPad(slot = 1).copy(robin = RobinSettings(0.6f, 6, seed = 0, followsKit = false))),
        robin = KitRobin(0.8f, 5))
    val back = roundTrip(kit)
    assertEquals(RobinSettings(0.6f, 6, 0, false), back.pads.single().robin)
    assertEquals(KitRobin(0.8f, 5), back.robin)
}

@Test fun `a kit without robin writes no robin keys`() {
    val json = KitStore.toJson(Kit("K", listOf(KitPad(1, "A01_Kick_01.wav"))))
    assertFalse("\"robin\"" in json)
}

@Test fun `a legacy robin pad reads as a robin chain on the legacy variation`() {
    // chain + recipe {"robin": {"takes": 3, "seed": 7}}, no "robin" pad field
    val pad = KitStore.padFromJson(Json.parse(LEGACY_ROBIN_PAD_JSON))
    assertEquals(RobinSettings(amount = null, takes = 3, seed = 7, followsKit = false), pad.robin)
    assertTrue(pad.isRobinChain)
}

@Test fun `a legacy grid and a foreign chain stay foreign`() {
    assertNull(KitStore.padFromJson(Json.parse(LEGACY_ZONED_ROBIN_PAD_JSON)).robin) // recipe robin has "zones"
    assertFalse(KitStore.padFromJson(Json.parse(FOREIGN_CHAIN_PAD_JSON)).isRobinChain) // chain, no recipe
}
```

- [ ] **Step 2: Run, expect FAIL** (unresolved `RobinSettings`)

Run: `./gradlew --no-daemon :kit:test --tests 'com.snipsnap.kit.KitStoreTest'`

- [ ] **Step 3: Implement** the types and codec. Legacy synthesis lives in the pad reader only: a pad with `chain`, no `"robin"` field, and `recipe.robin` without `zones` gets `RobinSettings(null, takes, seed, followsKit = false)`. The legacy recipe is left as it is.

- [ ] **Step 4: Run, expect PASS**, then the whole module: `./gradlew --no-daemon :kit:test` → all pass, goldens unchanged.

- [ ] **Step 5: Commit** — "Give round robin a typed home on the pad and the kit"

---

### Task 2: The dial-scaled variation

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/RobinVariation.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/RobinVariationTest.kt`

**Interfaces:**
- Produces:
  - `data class Spreads(val level: Float, val cents: Float, val trimSec: Float, val tilt: Float)`
  - `object RobinVariation { val POINTS: List<Spreads>; fun spreadsAt(amount: Float): Spreads; fun takes(original: Snip, count: Int, amount: Float, rng: java.util.Random): List<Snip> }` — `takes()[0]` is `original` itself.
  - `const val TILT_HZ = 3000f`

Point table (C..H), copied from the spec:

| amount | level | cents | trimSec | tilt |
|---|---|---|---|---|
| 0.0 | 0.14 | 18 | 0.003 | 0.15 |
| 0.2 | 0.22 | 28 | 0.004 | 0.30 |
| 0.4 | 0.30 | 38 | 0.005 | 0.42 |
| 0.6 | 0.40 | 50 | 0.006 | 0.56 |
| 0.8 | 0.50 | 65 | 0.008 | 0.72 |
| 1.0 | 0.60 | 80 | 0.010 | 0.88 |

Variant algorithm (the part the tests don't pin): per take, draw four uniforms `u1..u4` in order before any early return: trim = `u1 * trimSec`, cents = `(2u2-1) * cents`, gain = `1 + (2u3-1) * level`, t = `(2u4-1) * tilt`. Trim is capped at a quarter of the hit. Then resample with `TempoFit.repitch` as today. The tilt is a one-pole low-pass with matched-Z `a = exp(-2π·TILT_HZ/sr)`. When `t < 0` it computes `y = x + |t|(lp − x)`, and when `t > 0` it computes `y = x + t(x − lp)`. Gain and a clamp to ±1 follow. Because the draws don't depend on amount, moving the dial scales the same takes.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `the points are the ear-tested table`() {
    assertEquals(Spreads(0.14f, 18f, 0.003f, 0.15f), RobinVariation.spreadsAt(0f))
    assertEquals(Spreads(0.40f, 50f, 0.006f, 0.56f), RobinVariation.spreadsAt(0.6f))
    assertEquals(Spreads(0.60f, 80f, 0.010f, 0.88f), RobinVariation.spreadsAt(1f))
}
@Test fun `between points the dial is linear`() {
    val mid = RobinVariation.spreadsAt(0.7f)
    assertEquals(0.45f, mid.level, 1e-5f); assertEquals(57.5f, mid.cents, 1e-4f)
}
@Test fun `take one is the original and the rest differ`() {
    val t = RobinVariation.takes(hit, 6, 0.6f, Random(5))
    assertSame(hit, t[0]); assertEquals(6, t.size)
    assertTrue(t.drop(1).all { !it.samples.contentEquals(hit.samples) })
}
@Test fun `same seed same takes`() { /* two Random(5) runs → contentEquals for every take */ }
@Test fun `the dial scales the same takes`() {
    // take 2's peak gain ratio vs original has the same sign at 0.2 and 1.0, larger magnitude at 1.0
}
@Test fun `darker takes lose top end and brighter ones gain it`() {
    // white-noise original; for each take, HF energy (first difference RMS) / full RMS
    // moves the same direction as that take's tilt draw (re-draw u4 from a parallel Random(5) stream)
}
@Test fun `a very short hit keeps audio in every take at H`() {
    val click = Snip(FloatArray(882) { if (it < 40) 0.9f else 0f }, 1, 44_100) // 20 ms
    RobinVariation.takes(click, 8, 1f, Random(1)).forEach { assertTrue(it.frameCount > 0 && it.samples.any { s -> s != 0f }) }
}
@Test fun `a 48 kHz hit keeps its rate`() { /* every take's sampleRate == 48_000 */ }
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.RobinVariationTest'`
- [ ] **Step 3: Implement** `RobinVariation` as specified.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "Render round-robin takes on the ear-tested amount dial"

---

### Task 3: Robin.apply keeps the pad and takes settings

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Robin.kt`
- Modify: `cli/src/main/kotlin/com/snipsnap/cli/RobinCommand.kt`, `cli/src/main/kotlin/com/snipsnap/cli/Main.kt:206` (help line), `docs/CLI.md` (robin section)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/RobinTest.kt`, the CLI's robin test

**Interfaces:**
- Consumes: `RobinSettings`, `KitPad.isRobinChain` (Task 1); `RobinVariation.takes` (Task 2).
- Produces:
  - `Robin.apply(model, slot, settings: RobinSettings): KitPad`. Single-zone only. It writes `pad.robin = settings` and keeps `pad.recipe` untouched. Legacy `settings.amount == null` renders with today's `variant` and the 3-draw stream, so legacy output is byte-identical.
  - The existing `Robin.apply(model, slot, takes, seed, zones)` stays for the CLI and replay. Without `zones` it delegates with `RobinSettings(null, takes, seed, followsKit = false)`, so its output is unchanged. With `zones` it behaves exactly as today.
  - `Robin.undo(model, slot)` restores from the bin and sets `chain = null, robin = null`. It nulls `recipe` only when the recipe is pure legacy robin bookkeeping (a lone `"robin"` key).
  - `Robin.rerender(model, slot, settings: RobinSettings): KitPad` = undo then apply. A pad that isn't a robin chain is refused.
  - `Robin.original(model, slot): Snip` reads slice 0 of the chain WAV (take one = the original), for previews.
  - RNG seed stays `seed * 31 + slot`.
  - CLI: `snipsnap robin <kit> <pad> [--amount 0..1] [--takes N] [--seed S] [--zones Z] [--undo]`. Without `--amount`, behaviour is byte-identical to today.

- [ ] **Step 1: Write the failing tests** in `RobinTest`:

```kotlin
@Test fun `a synth pad keeps its patch under round robin`() {
    val before = model.pad(1)!!.recipe
    val pad = Robin.apply(model, 1, RobinSettings(0.6f, 6))
    assertEquals(before, pad.recipe); assertEquals(RobinSettings(0.6f, 6), pad.robin); assertEquals(6, pad.chain!!.cycle)
}
@Test fun `off restores the original bytes and keeps the patch`() { /* apply → undo → WAV bytes == before, recipe == before, robin == null */ }
@Test fun `legacy apply is byte-identical to before`() { /* Robin.apply(model, 1, takes = 3, seed = 7) WAV sha == LEGACY_SHA pinned from main */ }
@Test fun `rerender with new settings changes takes but not take one`() { /* slice 0 bytes equal original; cycle 8 after rerender(…, takes = 8) */ }
@Test fun `the original reads back from take one`() { /* Robin.original == original samples */ }
```

  Pin `LEGACY_SHA` by running today's `Robin.apply(takes = 3, seed = 7)` on the test fixture before you change `Robin.kt`.

- [ ] **Step 2: Run, expect FAIL** — `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.RobinTest'`
- [ ] **Step 3: Implement.** `replaceAudio(slot, pad.recipe) { … }` is passed the pad's own recipe, so the patch survives.
- [ ] **Step 4: Run, expect PASS**, then `:cli:test` with a new `--amount` case and the existing robin CLI tests unchanged.
- [ ] **Step 5: Commit** — "Keep a pad's patch under round robin and render it on the dial"

---

### Task 4: Edits work on a robin pad

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Robin.kt` (`around`)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt`, which has 13 `requireNotChained` call sites at lines 156, 468, 483, 521, 566, 621, 683, 770, 1040, 1068, 1089, 1635, and the helper at 1644.
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Retrim.kt:135`, `shell/src/main/kotlin/com/snipsnap/shell/MutateSheet.kt:437`, `shell/src/main/kotlin/com/snipsnap/shell/RecipeReplay.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/RobinEditsTest.kt` (create)

**Interfaces:**
- Consumes: `Robin.undo`, `Robin.apply(model, slot, settings)` (Task 3).
- Produces:
  - `fun <T> Robin.around(model: KitBuilderModel, slot: Int, block: () -> T): T`. On a robin chain it reads `settings = pad.robin`, calls `undo`, runs `block`, then calls `apply(model, slot, settings)` and returns the block's value. On anything else it just runs `block`.
  - In `KitBuilder`, `requireNotChained` refuses foreign chains only (`chain != null && !isRobinChain`). Each guarded public method body runs inside `Robin.around(this, slot) { … }`. `replaceAudio` and `requireRewritable` stay unwrapped, because `Robin.apply` calls them on the lifted pad.
  - `Retrim` and `MutateSheet` refuse only foreign chains, and the robin case goes through `around`. In `RecipeReplay`, a pad with `robin != null` replays inside `around`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a treatment on a robin pad lands on the original and the takes re-render`() {
    Robin.apply(model, 1, RobinSettings(0.6f, 6))
    model.treatPad(1, "tape")
    val pad = model.pad(1)!!
    assertTrue(pad.isRobinChain); assertEquals(6, pad.chain!!.cycle)
    assertContentEquals(treatedAloneSamples, Robin.original(model, 1).samples) // same treatment on a plain copy
}
@Test fun `edits on a robin pad undo in order even within one millisecond`() {
    // fixed clock or back-to-back calls: apply, treat, untreat, Robin.undo
    // → WAV bytes == the untouched original
}
@Test fun `a foreign chain still refuses edits`() { assertFailsWith<IllegalArgumentException> { model.treatPad(FOREIGN_SLOT, "tape") } }
@Test fun `recipe replay on a robin pad re-renders its takes`() { /* replay → isRobinChain, cycle kept, take one == fresh patch render */ }
@Test fun `retrim and mutate work on a robin pad`() { /* each returns success; pad still isRobinChain */ }
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.RobinEditsTest'`
- [ ] **Step 3: Implement.** If the same-millisecond test fails because two bin entries share `binnedAtMillis`, give `moveToBin` a strictly increasing stamp (last stamp + 1 when they collide). Keep the bin filename format.
- [ ] **Step 4: Run, expect PASS**, then the full shell suite: `./gradlew --no-daemon :shell:test` → 0 failures.
- [ ] **Step 5: Commit** — "Let every edit reach a round-robin pad's original"

---

### Task 5: The kit-wide switch and dial

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/RobinKit.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt` (`setRobin(KitRobin?)` beside `setTempo`)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` (Copy)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/RobinKitTest.kt`, plus `PersonalityTest` and `ReversalTest`

**Interfaces:**
- Consumes: Tasks 1, 3, 4.
- Produces:
  - `object RobinKit { const val LONG_SEC = 3.0f; fun eligible(pad: KitPad, frames: Long, rate: Int): Boolean; fun on(model): Result; fun set(model, settings: KitRobin): Result; fun off(model): Result }`
  - `data class Result(val changed: List<Int>, val skipped: Map<Int, Skip>)` and `enum class Skip { LOOP, LAYERED, FOREIGN_CHAIN, OWN_SETTING }`.
  - The eligibility rules:
    - Not eligible: `loop == true` or a sample longer than `LONG_SEC` (LOOP), `velocityLayers` not empty (LAYERED), or a chain that isn't a robin chain (FOREIGN_CHAIN).
    - Eligible pads that aren't yet robin go through `Robin.apply` with `RobinSettings(kit.amount, kit.takes, 0, followsKit = true)`.
  - `on` and `set` both store `kit.robin` through `setRobin`.
  - `set` re-renders only robin pads with `followsKit = true`. Pads with `followsKit = false` are reported as `OWN_SETTING`.
  - `off` undoes every robin chain and sets `kit.robin = null`.
  - Every call is deterministic, so the app's kit UNDO is just `set(model, previous)`.
  - Copy: `ROBIN_KIT_ON = "ROUND ROBIN ON %d PADS."` (templated, so add it to `templatedNotALanding` if the Reversal law asks); `ROBIN_KIT_OFF = "ROUND ROBIN OFF. YOUR ORIGINALS ARE BACK."`; `ROBIN_SKIPPED_LOOPS = "LONG LOOPS LEFT AS THEY ARE."`; `ROBIN_FOREIGN = "THIS PAD ALREADY CHAINS ITS OWN TAKES."`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `the kit switch round-robins drums and skips long loops`() {
    val r = RobinKit.on(model) // pads: 1 kick 0.4 s, 2 loop=true, 3 a 6 s pad
    assertEquals(listOf(1), r.changed); assertEquals(Skip.LOOP, r.skipped[2]); assertEquals(Skip.LOOP, r.skipped[3])
    assertEquals(RobinSettings(0.6f, 6, 0, true), model.pad(1)!!.robin)
}
@Test fun `the kit switch leaves foreign chains and grids alone`() { /* bytes and chain identical after on() and off() */ }
@Test fun `pads that follow the kit re-render and pads with their own setting stay`() { /* set(KitRobin(1f, 8)) → follower cycle 8, own pad unchanged + OWN_SETTING */ }
@Test fun `setting the kit back restores identical takes`() { /* sha after set(a); set(b); set(a) == sha after set(a) */ }
@Test fun `kit off brings every original back`() { /* every pad's WAV bytes == before on() */ }
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.RobinKitTest'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS**, plus `--tests '*PersonalityTest' --tests '*ReversalTest'`.
- [ ] **Step 5: Commit** — "Switch round robin on for a whole kit, skipping long loops"

---

### Task 6: MPC exports cycle the takes

**Files:**
- Modify: `xpm/src/main/kotlin/com/snipsnap/xpm/DrumProgram.kt` (`Pad.cycleLayers`)
- Modify: `xpm/src/main/kotlin/com/snipsnap/xpm/XpmWriter.kt:165` (ZonePlay) and `appendLayers` (219)
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/KitExporter.kt` (`buildSlots(…, splitRobinTakes: Boolean = false)`; the callers at lines 96 and 115 pass `true`), `kit/src/main/kotlin/com/snipsnap/kit/SessionBuilder.kt:126` (`true`), `kit/src/main/kotlin/com/snipsnap/kit/XpnPackager.kt` (the same split), `kit/src/main/kotlin/com/snipsnap/kit/ExportReadBack.kt:117`, `kit/src/main/kotlin/com/snipsnap/kit/XpnImporter.kt`
- Modify: `docs/FEATURE_PLAN.md:853` (GG4 correction), `docs/CLI.md:700`
- Test: `xpm/src/test/kotlin/com/snipsnap/xpm/XpmWriterTest.kt`, `kit/src/test/kotlin/com/snipsnap/kit/KitExporterTest.kt`, `XpnPackagerTest.kt`, `XpnImporterTest.kt`

**Interfaces:**
- Consumes: `KitPad.isRobinChain`, `ChainInfo.window` (existing).
- Produces:
  - `Pad.cycleLayers: List<VelocityLayer>? = null`, 2..4 entries, each 0..127. `init` requires that it is null whenever `velocityLayers` is set.
  - When `cycleLayers` is set, `XpmWriter` writes `<ZonePlay>0</ZonePlay>` and one layer per take, each with `SliceStart` 0 and `SliceEnd` set to the take's frames. Otherwise the output is byte-identical to today.
  - With `splitRobinTakes = true`, `buildSlots` writes each robin chain's first `min(cycle, 4)` takes as `${stem}_RR1..RR4.wav`. The stems are sanitized and pass the same collision check. The chain WAV itself isn't copied, and the pad's `sampleName` is the RR1 stem. Foreign chains and grids take today's path. `.xtd`, DecentSampler and SFZ callers keep `false`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// XpmWriterTest
@Test fun `a cycling pad writes zone play cycle and one full-range layer per take`() {
    val xml = XpmWriter().write(DrumProgram("K", listOf(Pad("K_RR1", 100, cycleLayers = (1..4).map { VelocityLayer("K_RR$it", 100L + it, 0, 127) }))))
    assertTrue("<ZonePlay>0</ZonePlay>" in xml)
    assertEquals(4, Regex("<SampleName>K_RR\\d</SampleName>").findAll(xml).count())
    assertEquals(4, Regex("<VelStart>0</VelStart>\\s*<VelEnd>127</VelEnd>").findAll(xml).count())
}
// KitExporterTest
@Test fun `a six-take robin pad exports four take files in order`() { /* RR1 bytes == take one window, RR4 == take four; no chain WAV in the folder */ }
@Test fun `a foreign chain exports exactly as before`() { /* program bytes == pre-change fixture */ }
@Test fun `a kit without robin pads exports byte-identical`() { /* existing goldens; add an explicit program-folder sha check */ }
// XpnPackagerTest
@Test fun `the xpn carries the take files and a cycling program`() { }
// XpnImporterTest
@Test fun `an exported robin program imports back as a playable pad`() { /* export → import → pad exists, sample is RR1 or a rebuilt chain, no exception */ }
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew --no-daemon :xpm:test :kit:test`
- [ ] **Step 3: Implement.** On import, a `ZonePlay` 0 pad with full-range layers is rebuilt as a chain: concatenate the layers in order and set `ChainInfo(boundaries, cycle = layers.size)`. If that's more than the importer can carry, land layer 1 as a plain pad, and record the choice as a ruling.
- [ ] **Step 4: Run, expect PASS**, every existing golden included. If a golden that pins a *robin* chain's old windowed `.xpm` changes, update it in its own commit and say why. Any other golden that changes is a bug.
- [ ] **Step 5: Commit** — "Export round-robin pads as cycling layers the MPC alternates"

---

### Task 7: Round robin on the pad sheet

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt`. Add a ROUND ROBIN block after the TUNE slider (around line 3419). Change the chain refusals at lines 358, 2430, 3542 and 4830–4913 to foreign-only. Treatment and Aquarium previews read `Robin.original`.
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt:1956, 3504, 6785` and `app/src/main/kotlin/com/snipsnap/app/App.kt:1795`. Each guard becomes `chain != null && !isRobinChain`, and the writes go through `Robin.around`.
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` (Copy)
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/PadSheetRobinTest.kt` (create)

**Interfaces:**
- Consumes: Tasks 3–5.
- Produces the pad block:
  - `ToggleChip("ROUND ROBIN")`. Turning it on applies the kit's `KitRobin`, or the defaults when the kit has none, with `followsKit = true`.
  - `StepperSlider("AMOUNT")` runs 0..1 with the value shown as `"%d".format(Locale.ROOT, round(amount * 100))`.
  - `StepperSlider("TAKES")` runs 4..8. A commit on either slider calls `Robin.rerender` with `followsKit = false` and toasts `ROBIN_OWN`.
  - `ToggleChip("FOLLOW KIT")` re-renders with the kit's settings and `followsKit = true`.
  - On a foreign chain or a layered pad the block is disabled and says `ROBIN_FOREIGN`.
  - On a legacy pad it shows ON with its take count, and AMOUNT reads `CUSTOM` until it is moved.
  - All work runs through `commitPadEditNow`, so busy state, errors and toasts follow the sheet's existing pattern.
  - New Copy: `ROBIN_ON = "ROUND ROBIN ON. TURN IT OFF TO GET THE ORIGINAL BACK."`, `ROBIN_OFF = "ROUND ROBIN OFF. THE ORIGINAL IS BACK."`, `ROBIN_OWN = "THIS PAD KEEPS ITS OWN ROUND ROBIN."`

- [ ] **Step 1: Write the failing device tests.** Each one presses the controls with `performSemanticsAction(SemanticsActions.OnClick)`.

```kotlin
@Test fun roundRobinOnMakesSixTakesAndOffRestores() { /* chip on → pad.chain.cycle == 6; chip off → WAV bytes == before */ }
@Test fun treatmentsStayAvailableOnARobinPad() { /* KEEP enabled, no "Undo round-robin" text present */ }
@Test fun aForeignChainShowsWhyTheSwitchIsOff() { /* text ROBIN_FOREIGN shown; chip disabled */ }
```

- [ ] **Step 2: Boot the AVD and run, expect FAIL.**

```bash
~/Library/Android/sdk/emulator/emulator -avd snipsnap_pixel -no-window -no-audio -no-boot-anim -no-snapshot-load &
./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.PadSheetRobinTest
```

- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS**, then `./gradlew --no-daemon :shell:test --tests '*PersonalityTest' --tests '*ReversalTest'`.
- [ ] **Step 5: Commit** — "Put round robin on the pad sheet with amount and takes"

---

### Task 8: Round robin on the KIT screen

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt` (a ROUND ROBIN row with the switch, AMOUNT, TAKES and UNDO), `app/src/main/kotlin/com/snipsnap/app/KitShelf.kt` (a `robin…` entry beside `inKey`, which opens the model, calls `RobinKit`, and saves)
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/KitScreenRobinTest.kt` (create)

**Interfaces:**
- Consumes: `RobinKit.on/set/off`, `Result`, `Skip` (Task 5).
- Produces:
  - `KitShelf.robin(entry, action: (KitBuilderModel) -> RobinKit.Result): Pair<Entry, RobinKit.Result>`.
  - The KIT screen keeps `previous: KitRobin?` from just before the last dial change. UNDO calls `set(previous)` and is shown only when `previous` is set.
  - After `on`, the toast is `ROBIN_KIT_ON`, followed by `ROBIN_SKIPPED_LOOPS` when anything was skipped as LOOP.
  - Work runs off the main thread with the screen's busy state. A failure shows the existing toast pattern.

- [ ] **Step 1: Write the failing device tests**

```kotlin
@Test fun kitSwitchSkipsTheLoopAndUndoRestoresTheDial() { /* on → drum pad chained, loop pad not; amount to 100 → follower re-rendered; UNDO → follower bytes == the 60 render */ }
@Test fun kitOffBringsEveryOriginalBack() { }
```

- [ ] **Step 2: Run, expect FAIL** (same command, `class=…KitScreenRobinTest`).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.** Then run the full gates and record the counts in the PR:

```bash
./gradlew --no-daemon :kit:test :xpm:test :shell:test :cli:test
./gradlew --no-daemon :app:connectedDebugAndroidTest
adb emu kill; pkill -9 -f qemu-system-aarch64-headless
```

- [ ] **Step 5: Commit** — "Turn round robin on for the whole kit from the KIT screen"

---

**Owed after merge (owner, on hardware):** export a round-robin kit to the MPC Live III and confirm one pad alternates four takes hit by hit.
