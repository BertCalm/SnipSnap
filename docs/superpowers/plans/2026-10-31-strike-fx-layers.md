# STRIKE FX Layers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From a pad's sheet, make DRY / ROOM / ECHO velocity layers from the pad's own sample with two knobs, audition before keeping, and store the bank in the pad recipe so every layer rebuilds.

**Architecture:** `PadRecipe` grows a `layers` list (version 3) and a bank-rendering method; a new `StrikeFxBank` object in `:synth` owns the knob-to-chain mapping; two `KitBuilder` verbs in `:shell` write and clear the bank the way SOFT HITS does; `RecipeReplay` learns to PASTE a bank; the pad sheet gets a STRIKE FX section with two `StepperSlider` knobs, AUDITION, KEEP and CLEAR. Playback is untouched.

**Tech Stack:** Kotlin, Gradle multi-module (`:synth`, `:shell`, `:kit`, `:app`), Jetpack Compose, `kotlin.test` JUnit tests, the existing `FxChain` offline rack.

**Spec:** `docs/superpowers/specs/2026-10-31-strike-fx-layers-design.md`

## Global Constraints

- No DSP on the performance path: `shell/.../StrikeStrip.kt`, `PadHit.kt` and `app/.../PadEngine.kt` are not modified.
- `Kit.kt` invariants stand: 1..4 layers, ascending windows, `velocityLayers.last().sampleFile == sampleFile`.
- Recipe JSON written by today's app (version 2) must still read; an unbanked recipe is still written as 2 and only a banked one as 3.
- Knob values are the macro values: ROOM is spring `SIZE` (default `0.42f`), SLAP is echo `TIME` (default `0.9f`). Fixed macros: spring `TONE 0.7f, MIX 1f`; echo `REPEAT 0.55f, TONE 0.8f, MIX 0.9f`. Terminal fade `4f` ms.
- Layer names, bottom to top: `DRY`, `ROOM`, `ECHO`. Windows from `StackTakes.windows(2)` (1..41, 42..84, 85..127). File names from `freeLayerNames`.
- Copy is facts in the house voice, upper case, in `object Copy` (`shell/.../Personality.kt`). No model identifier anywhere.
- One Gradle invocation at a time on this machine, `./gradlew --no-daemon`, focused test classes only; the full suite runs in CI.
- Commit messages carry the harness's attribution trailers.

## Review Focus

1. A stereo or 48 kHz source: ROOM and ECHO must keep the source's channel count and rate, and DRY must be byte-identical to the source (Task 3 test `keep preserves the source bytes and the source format`).
2. A very short source (under 50 ms, shorter than the 4 ms fade plus the rack's head): the bank must still render three finite, non-empty snips (Task 2 test `a 20 ms source still yields three finite layers`).
3. KEEP on a pad that already has SOFT HITS or stacked takes: the old `_vN.wav` files must be deleted, not orphaned, and `freeLayerNames` must not hand back a name that still exists on disk (Task 3 test `keep over soft hits replaces their files`).
4. A version 2 recipe with no `layers` must round-trip with every other field unchanged, and a version 3 recipe must be refused by nothing that reads recipes today (Task 1 tests, plus Task 4's `RecipeReplay` test that a banked recipe without `patch` is not `Refused`).
5. CLEAR after export and re-import: the DRY layer must be the one restored as the main file even when the sidecar re-attached the names (Task 5 test `a kept bank survives export and import and clears back to one sample`).

---

### Task 1: `PadRecipe` version 3 with `layers`

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `data class LayerRecipe(val name: String, val fx: FxChain? = null, val terminalFadeMs: Float? = null)` with `toJsonValue(): JsonValue.Obj` and `companion fun fromJsonValue(value: JsonValue): LayerRecipe`.
  - `PadRecipe(..., val layers: List<LayerRecipe>? = null)` as the last constructor parameter.
  - `PadRecipe.VERSION = 3`; `fromJsonValue` accepts 2 and 3.
  - `fun PadRecipe.renderBank(): List<Snip>` and `fun PadRecipe.processBank(source: Snip): List<Snip>`, both returning the layers bottom to top, DRY first.
  - `val PadRecipe.isBanked: Boolean get() = !layers.isNullOrEmpty()`.

- [ ] **Step 1: Write the failing tests** in `PadRecipeTest.kt`, in the file's existing style (`kotlin.test`):

```kotlin
@Test fun `a version 2 recipe reads with no layers and re-saves as version 3 unchanged otherwise`() {
    val v2 = Json.parse("""{"recipe":2,"fx":{"spring":{"SIZE":0.3}},"alias":false}""")
    val parsed = PadRecipe.fromJsonValue(v2)
    assertNull(parsed.layers)
    val again = parsed.toJsonValue()
    assertEquals(3, again["recipe"]!!.int())
    assertEquals((v2 as JsonValue.Obj).entries - "recipe", again.entries - "recipe")
}

@Test fun `a banked recipe round-trips its layers`() {
    val bank = listOf(
        LayerRecipe("DRY"),
        LayerRecipe("ROOM", FxChain(spring = mapOf("SIZE" to 0.42f, "TONE" to 0.7f, "MIX" to 1f)), 4f),
        LayerRecipe("ECHO", FxChain(echo = mapOf("TIME" to 0.9f, "REPEAT" to 0.55f, "TONE" to 0.8f, "MIX" to 0.9f)), 4f),
    )
    val recipe = PadRecipe(layers = bank)
    assertEquals(recipe, PadRecipe.fromJsonValue(recipe.toJsonValue()))
    assertEquals(listOf("DRY", "ROOM", "ECHO"), PadRecipe.fromJsonValue(recipe.toJsonValue()).layers!!.map { it.name })
}

@Test fun `layers alone satisfy the empty-recipe guard`() {
    PadRecipe(layers = listOf(LayerRecipe("DRY")))   // must not throw
    assertFailsWith<IllegalArgumentException> { PadRecipe(layers = emptyList()) }
}

@Test fun `an unknown version is still refused`() {
    assertFailsWith<JsonException> { PadRecipe.fromJsonValue(Json.parse("""{"recipe":4}""")) }
}

@Test fun `processBank returns DRY untouched then one snip per layer`() {
    val source = Snip(FloatArray(4410) { if (it == 0) 0.8f else 0f }, 1, 44100)
    val bank = PadRecipe(layers = listOf(LayerRecipe("DRY"), LayerRecipe("ROOM", FxChain(spring = mapOf("SIZE" to 0.42f, "TONE" to 0.7f, "MIX" to 1f)), 4f)))
    val out = bank.processBank(source)
    assertEquals(2, out.size)
    assertContentEquals(source.samples, out[0].samples)
    assertTrue(out[1].frameCount > source.frameCount)
    assertTrue(out[1].samples.all { it.isFinite() })
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.PadRecipeTest'`
Expected: FAIL (compile errors: `LayerRecipe`, `layers`, `processBank` undefined).

- [ ] **Step 3: Implement** in `PadRecipe.kt`:
  - `data class LayerRecipe(val name: String, val fx: FxChain? = null, val terminalFadeMs: Float? = null)` with `init { require(name.isNotBlank()) }`, `toJsonValue()` writing keys `name`, `fx` (when set), `terminalFadeMs` (when set), and `fromJsonValue`.
  - Add `val layers: List<LayerRecipe>? = null` as the last `PadRecipe` parameter. Change the `init` guard to `patch != null || fx != null || board != null || pedals != null || !layers.isNullOrEmpty()`; add `layers?.let { require(it.isNotEmpty()) { "a bank has at least one layer" } }`.
  - `VERSION = 3`. In `fromJsonValue`: `if (version != 2 && version != VERSION) throw JsonException(...)`; read `layers` from `obj["layers"]` as an array of `LayerRecipe.fromJsonValue`. In `toJsonValue`: `layers?.let { obj["layers"] = JsonValue.Arr(it.map(LayerRecipe::toJsonValue)) }`.
  - `fun processBank(source: Snip): List<Snip>`: `val dry = process(source)` is NOT what the spec wants; the source IS dry. Body: `layers.orEmpty().map { l -> val wet = l.fx?.process(source) ?: source; l.terminalFadeMs?.let { ms -> Dsp.fadeTail(wet, ms) } ?: wet }`, reusing the existing private `terminalFade` logic (lift it to take the ms).
  - `fun renderBank(): List<Snip> = processBank(render())`.
  - `val isBanked get() = !layers.isNullOrEmpty()`.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.PadRecipeTest'`
Expected: PASS, including the file's existing tests.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt
git commit -m "Give pad recipes a layer bank: version 3, read 2 and 3"
```

---

### Task 2: `StrikeFxBank`, the knob-to-chain mapping

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/StrikeFxBank.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/StrikeFxBankTest.kt`

**Interfaces:**
- Consumes: `LayerRecipe`, `PadRecipe.processBank` (Task 1); `FxChain`, `Spring`, `Echo`.
- Produces: `object StrikeFxBank` with
  - `const val DEFAULT_ROOM = 0.42f`, `const val DEFAULT_SLAP = 0.9f`, `const val TERMINAL_FADE_MS = 4f`
  - `val NAMES = listOf("DRY", "ROOM", "ECHO")`
  - `fun layers(room: Float, slap: Float): List<LayerRecipe>` (requires both in 0..1)
  - `fun roomOf(recipe: PadRecipe): Float?` and `fun slapOf(recipe: PadRecipe): Float?` (read back from `layers[1].fx.spring["SIZE"]` and `layers[2].fx.echo["TIME"]`; null when the recipe is not a STRIKE FX bank)
  - `fun isStrikeFx(recipe: PadRecipe?): Boolean` (three layers named exactly `NAMES`)
  - `fun render(source: Snip, room: Float, slap: Float): List<Snip>` = `PadRecipe(layers = layers(room, slap)).processBank(source)`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `layers carry the knob values and the fixed macros`() {
    val l = StrikeFxBank.layers(0.42f, 0.9f)
    assertEquals(listOf("DRY", "ROOM", "ECHO"), l.map { it.name })
    assertNull(l[0].fx)
    assertEquals(mapOf("SIZE" to 0.42f, "TONE" to 0.7f, "MIX" to 1f), l[1].fx!!.spring)
    assertEquals(mapOf("TIME" to 0.9f, "REPEAT" to 0.55f, "TONE" to 0.8f, "MIX" to 0.9f), l[2].fx!!.echo)
    assertEquals(4f, l[1].terminalFadeMs); assertEquals(4f, l[2].terminalFadeMs)
}

@Test fun `knob values read back from a recipe exactly`() {
    val r = PadRecipe(layers = StrikeFxBank.layers(0.13f, 0.77f))
    assertEquals(0.13f, StrikeFxBank.roomOf(r)); assertEquals(0.77f, StrikeFxBank.slapOf(r))
    assertTrue(StrikeFxBank.isStrikeFx(r))
    assertFalse(StrikeFxBank.isStrikeFx(PadRecipe(fx = FxChain(spring = mapOf("SIZE" to 0.5f)))))
    assertNull(StrikeFxBank.roomOf(PadRecipe(fx = FxChain(spring = mapOf("SIZE" to 0.5f)))))
}

@Test fun `render keeps the source format and bounds the tails`() {
    val stereo48k = Snip(FloatArray(4800 * 2) { if (it < 2) 0.7f else 0f }, 2, 48000)
    val out = StrikeFxBank.render(stereo48k, 0.42f, 0.9f)
    assertEquals(3, out.size)
    out.forEach { assertEquals(2, it.channels); assertEquals(48000, it.sampleRate); assertTrue(it.samples.all { s -> s.isFinite() && abs(s) <= 1f }) }
    assertContentEquals(stereo48k.samples, out[0].samples)
    assertTrue(out[1].frameCount <= stereo48k.frameCount + 48000 && out[2].frameCount <= stereo48k.frameCount + 48000)
}

@Test fun `a 20 ms source still yields three finite layers`() {
    val tiny = Snip(FloatArray(882) { if (it == 0) 0.5f else 0f }, 1, 44100)
    StrikeFxBank.render(tiny, 0f, 0f).forEach { assertTrue(it.frameCount > 0 && it.samples.all { s -> s.isFinite() }) }
}

@Test fun `knobs outside 0..1 are refused`() {
    assertFailsWith<IllegalArgumentException> { StrikeFxBank.layers(1.2f, 0.5f) }
    assertFailsWith<IllegalArgumentException> { StrikeFxBank.layers(0.5f, -0.1f) }
}

@Test fun `the same knobs render the same bytes twice`() {
    val src = Snip(FloatArray(2205) { if (it == 0) 0.6f else 0f }, 1, 44100)
    val a = StrikeFxBank.render(src, 0.42f, 0.9f); val b = StrikeFxBank.render(src, 0.42f, 0.9f)
    a.zip(b).forEach { (x, y) -> assertContentEquals(x.samples, y.samples) }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.StrikeFxBankTest'`
Expected: FAIL, `StrikeFxBank` undefined.

- [ ] **Step 3: Implement `StrikeFxBank.kt`** as the Interfaces block says. The room chain is `FxChain(spring = mapOf("SIZE" to room, "TONE" to 0.7f, "MIX" to 1f))`; the echo chain `FxChain(echo = mapOf("TIME" to slap, "REPEAT" to 0.55f, "TONE" to 0.8f, "MIX" to 0.9f))`. If the stereo test fails because `FxChain.process` folds to mono, that is a defect to report, not to paper over: stop and write it in the PR body.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.StrikeFxBankTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/StrikeFxBank.kt synth/src/test/kotlin/com/snipsnap/synth/StrikeFxBankTest.kt
git commit -m "Add StrikeFxBank: ROOM and SLAP knobs become a DRY, ROOM, ECHO layer bank"
```

---

### Task 3: `KitBuilder` verbs: audition, keep, clear

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt` (next to `addGhostLayers` at :410 and `clearGhostLayers` at :956)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` (`object Copy`)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/StrikeFxLayersTest.kt` (new)

**Interfaces:**
- Consumes: `StrikeFxBank` (Task 2); `PadRecipe`, `LayerRecipe` (Task 1); existing `StackTakes.windows`, `freeLayerNames`, `update`, `Breed.recipeOf`, `PrintedPads.has`, `Copy.boardRemoveBeforeSoftHits`.
- Produces, on `KitBuilderModel`:
  - `fun strikeFxAudition(slot: Int, room: Float, slap: Float): List<Snip>`: pure; reads the source (6.4 step 1 of the spec) and returns `StrikeFxBank.render(source, room, slap)`; writes nothing.
  - `fun strikeFxLayers(slot: Int, room: Float, slap: Float): KitPad`: KEEP.
  - `fun clearStrikeFxLayers(slot: Int): KitPad`: CLEAR.
  - `fun strikeFxSource(slot: Int): File` (private is fine; the test reaches it through behaviour): `_v1`-style DRY file when the pad is a STRIKE FX bank, else the main file.
  - `Copy.STRIKE_FX_ON = "STRIKE FX ON. BOTTOM DRY, MIDDLE ROOM, TOP ECHO."`, `Copy.STRIKE_FX_OFF = "STRIKE FX OFF. ONE SAMPLE AGAIN."`, `Copy.STRIKE_FX_REPLACES_LAYERS = "STRIKE FX REPLACES THIS PAD'S SOFT HITS. KEEP?"`

- [ ] **Step 1: Write the failing tests** in `StrikeFxLayersTest.kt`, mirroring `KitBuilderTest.kt:17` (`Files.createTempDirectory("strikefx").toFile()`, `KitBuilderModel.create("StrikeFx", dir)`, a pad assigned from a short generated WAV). Helper `fun bytesOf(f: File) = f.readBytes()`.

```kotlin
@Test fun `keep writes three ascending named layers and the recipe`() {
    val pad = m.strikeFxLayers(1, 0.42f, 0.9f)
    assertEquals(listOf("DRY", "ROOM", "ECHO"), pad.velocityLayers.map { it.displayName })
    assertEquals(StackTakes.windows(2), pad.velocityLayers.map { it.velStart..it.velEnd })
    assertEquals(pad.sampleFile, pad.velocityLayers.last().sampleFile)
    val recipe = Breed.recipeOf(pad)!!
    assertEquals(0.42f, StrikeFxBank.roomOf(recipe)); assertEquals(0.9f, StrikeFxBank.slapOf(recipe))
    assertEquals(3, recipe.layers!!.size)
}

@Test fun `keep preserves the source bytes and the source format`() {
    val before = bytesOf(File(dir, m.kit.pad(1)!!.sampleFile))
    val pad = m.strikeFxLayers(1, 0.42f, 0.9f)
    assertContentEquals(before, bytesOf(File(dir, pad.velocityLayers[0].sampleFile)))
    val src = WavReader.read(File(dir, pad.velocityLayers[0].sampleFile))
    pad.velocityLayers.drop(1).forEach { l -> val s = WavReader.read(File(dir, l.sampleFile)); assertEquals(src.channels, s.channels); assertEquals(src.sampleRate, s.sampleRate) }
}

@Test fun `keep leaves every other recipe field as it was`() {
    // assign a synth pad (any Patch via Patches) so the recipe has a patch; then keep
    val beforeRecipe = Breed.recipeOf(m.kit.pad(2)!!)!!
    val after = Breed.recipeOf(m.strikeFxLayers(2, 0.42f, 0.9f))!!
    assertEquals(beforeRecipe, after.copy(layers = null))
}

@Test fun `audition writes nothing`() {
    val listing = dir.listFiles()!!.map { it.name }.sorted()
    val out = m.strikeFxAudition(1, 0.2f, 0.5f)
    assertEquals(3, out.size)
    assertEquals(listing, dir.listFiles()!!.map { it.name }.sorted())
    assertEquals(m.kit.pad(1)!!.velocityLayers, emptyList())
}

@Test fun `keep over soft hits replaces their files`() {
    m.addGhostLayers(1, softZones = 2)
    val ghosts = m.kit.pad(1)!!.velocityLayers.map { it.sampleFile }.filter { it != m.kit.pad(1)!!.sampleFile }
    val pad = m.strikeFxLayers(1, 0.42f, 0.9f)
    ghosts.forEach { assertFalse(File(dir, it).exists(), it) }
    assertEquals(3, pad.velocityLayers.size)
}

@Test fun `a second keep reads the DRY layer and rewrites the others in place`() {
    val first = m.strikeFxLayers(1, 0.42f, 0.9f)
    val dryBytes = bytesOf(File(dir, first.velocityLayers[0].sampleFile))
    val second = m.strikeFxLayers(1, 0.1f, 0.3f)
    assertEquals(first.velocityLayers.map { it.sampleFile }, second.velocityLayers.map { it.sampleFile })
    assertContentEquals(dryBytes, bytesOf(File(dir, second.velocityLayers[0].sampleFile)))
    assertEquals(0.1f, StrikeFxBank.roomOf(Breed.recipeOf(second)!!))
}

@Test fun `clear restores the single sample and removes the bank from the recipe`() {
    val before = bytesOf(File(dir, m.kit.pad(1)!!.sampleFile)); val recipeBefore = m.kit.pad(1)!!.recipe
    val kept = m.strikeFxLayers(1, 0.42f, 0.9f)
    val soft = kept.velocityLayers.dropLast(1).map { it.sampleFile }
    val cleared = m.clearStrikeFxLayers(1)
    assertTrue(cleared.velocityLayers.isEmpty())
    assertContentEquals(before, bytesOf(File(dir, cleared.sampleFile)))
    soft.forEach { assertFalse(File(dir, it).exists()) }
    assertEquals(recipeBefore, cleared.recipe)
}

@Test fun `a printed board refuses strike fx with the existing copy`() {
    // use the same setup PrintedPadsIntegrationTest uses to print a board on a pad, then:
    val e = assertFailsWith<IllegalArgumentException> { m.strikeFxLayers(3, 0.42f, 0.9f) }
    assertTrue(e.message!!.startsWith("REMOVE "))
}

@Test fun `the strip reaches all three layers of a kept pad`() {
    val pad = m.strikeFxLayers(1, 0.42f, 0.9f)
    val hits = listOf(0f, 0.5f, 1f).map { StrikeStrip.hit(StrikeStrip.Settings(enabled = true), it, 1f) }  // use the real signature from StrikeStripTest.kt:21
    assertEquals(listOf(0, 1, 2), hits.map { PadHit.layerIndex(pad, (it.first * 127).roundToInt().coerceIn(1, 127)) })
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.StrikeFxLayersTest'`
Expected: FAIL, the three verbs undefined.

- [ ] **Step 3: Implement** in `KitBuilder.kt`, modelled on `addGhostLayers`:
  - `strikeFxSource(slot)`: `val pad = kit.pad(slot)`; if `StrikeFxBank.isStrikeFx(Breed.recipeOf(pad))` and `pad.velocityLayers.size == 3` return `File(kitDir, pad.velocityLayers[0].sampleFile)` else `File(kitDir, pad.sampleFile)`.
  - `strikeFxAudition`: `require(!PrintedPads.has(pad.recipe)) { Copy.boardRemoveBeforeSoftHits(...) }`; `StrikeFxBank.render(WavReader.read(strikeFxSource(slot)), room, slap)`.
  - `strikeFxLayers`: same guard; `val source = WavReader.read(strikeFxSource(slot))`; `val bank = StrikeFxBank.render(source, room, slap)`; names: when the pad is already a STRIKE FX bank reuse its two soft file names, otherwise delete the old soft files (as `clearGhostLayers` does) and take `freeLayerNames(pad.sampleStem, 2)`; write `names[0]` = the source bytes (copy the file, do not re-encode), `names[1]` = `bank[1]`, then the main file = `bank[2]` via `WavWriter.write`; windows `StackTakes.windows(2)`; `KitLayer(names[0], w0.first, w0.last, "DRY")`, `KitLayer(names[1], ..., "ROOM")`, `KitLayer(pad.sampleFile, ..., "ECHO")`; recipe = `(Breed.recipeOf(pad) ?: PadRecipe(layers = ...)).copy(layers = StrikeFxBank.layers(room, slap)).toJsonValue()`; `update(slot) { it.copy(velocityLayers = layers, recipe = recipeJson) }`.
  - `clearStrikeFxLayers`: guard; require `StrikeFxBank.isStrikeFx(recipe)`; copy the DRY file's bytes over the main file; delete the two soft files; recipe = `recipe.copy(layers = null)` when any other field is set, else `null`; `update(slot) { it.copy(velocityLayers = emptyList(), recipe = ...) }`.
  - The three `Copy` constants.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.StrikeFxLayersTest' --tests 'com.snipsnap.shell.KitBuilderTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt shell/src/main/kotlin/com/snipsnap/shell/Personality.kt shell/src/test/kotlin/com/snipsnap/shell/StrikeFxLayersTest.kt
git commit -m "Add STRIKE FX verbs to KitBuilder: audition, keep, clear a DRY, ROOM, ECHO bank"
```

---

### Task 4: PASTE a bank with `RecipeReplay`

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/RecipeReplay.kt` (`Plan` sealed class :41, `plan` :70-111, `apply` :155)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/RecipeReplayTest.kt`

**Interfaces:**
- Consumes: `PadRecipe.isBanked`, `StrikeFxBank.roomOf/slapOf` (Tasks 1, 2), `KitBuilderModel.strikeFxLayers` (Task 3).
- Produces: `data class Plan.StrikeFx(val room: Float, val slap: Float) : Plan()`; `plan()` returns it for any recipe where `"layers" in e` and `StrikeFxBank.isStrikeFx(parsed)` (checked before the `"patch" in e` branch); `apply()` handles it by calling `strikeFxLayers(targetSlot, room, slap)`.

- [ ] **Step 1: Write the failing tests** next to `plans refuse by name, never by skipping` (:41):

```kotlin
@Test fun `a strike fx bank plans as StrikeFx, not as a patch and not refused`() {
    val recipe = PadRecipe(layers = StrikeFxBank.layers(0.3f, 0.6f)).toJsonValue()
    assertEquals(RecipeReplay.Plan.StrikeFx(0.3f, 0.6f), RecipeReplay.plan(recipe))
    val withPatch = PadRecipe(patch = somePatch, layers = StrikeFxBank.layers(0.3f, 0.6f)).toJsonValue()
    assertEquals(RecipeReplay.Plan.StrikeFx(0.3f, 0.6f), RecipeReplay.plan(withPatch))
}

@Test fun `applying a strike fx plan banks the target pad from its own sample`() {
    // two pads with different samples; keep on 1; apply plan(pad1.recipe) to slot 2
    val done = RecipeReplay.apply(RecipeReplay.plan(m.kit.pad(1)!!.recipe), m, targetSlot = 2 /* match the real apply signature at :155 */)
    val pad2 = m.kit.pad(2)!!
    assertEquals(listOf("DRY", "ROOM", "ECHO"), pad2.velocityLayers.map { it.displayName })
    assertContentEquals(originalBytesOfPad2, File(dir, pad2.velocityLayers[0].sampleFile).readBytes())
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.RecipeReplayTest'`
Expected: FAIL, `Plan.StrikeFx` undefined.

- [ ] **Step 3: Implement**: add the `Plan.StrikeFx` class; in `plan()`, before line 108's `"patch" in e`, add `if ("layers" in e) { val parsed = runCatching { PadRecipe.fromJsonValue(recipe) }.getOrNull(); if (parsed != null && StrikeFxBank.isStrikeFx(parsed)) return Plan.StrikeFx(StrikeFxBank.roomOf(parsed)!!, StrikeFxBank.slapOf(parsed)!!) }` (a banked recipe that is not a STRIKE FX bank falls through to today's branches). In `apply()`, add the `is Plan.StrikeFx ->` arm beside `is Plan.Patch ->` (:192), calling `strikeFxLayers` and returning the same `Done` shape with `Copy.STRIKE_FX_ON` as its message. Also update the test `plans read every dial the writers wrote` (:69) if it enumerates `Plan` subclasses.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.RecipeReplayTest' --tests 'com.snipsnap.shell.OnsetRecipeReplayTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/RecipeReplay.kt shell/src/test/kotlin/com/snipsnap/shell/RecipeReplayTest.kt
git commit -m "PASTE a STRIKE FX bank onto another pad from that pad's own sample"
```

---

### Task 5: Export and import keep the bank (tests, and fixes only if they fail)

**Files:**
- Test: `kit/src/test/kotlin/com/snipsnap/kit/StandaloneExportSidecarTest.kt`, `kit/src/test/kotlin/com/snipsnap/kit/ImportSidecarTest.kt`
- Modify (only if a test fails): `kit/src/main/kotlin/com/snipsnap/kit/KitExporter.kt`, `ImportSidecar.kt`

**Interfaces:**
- Consumes: a kit.json pad with three layers named DRY/ROOM/ECHO and a version 3 recipe (built by hand in `:kit`, which cannot depend on `:shell`; copy the JSON shape Task 1 writes).
- Produces: nothing new; proof that §6.6 of the spec holds.

- [ ] **Step 1: Write the failing-or-passing tests**, in each file's existing style (`Files.createTempDirectory("export-sidecar")`):

```kotlin
// StandaloneExportSidecarTest
@Test fun `a kept strike fx bank survives both standalone exports with names windows and recipe`() {
    // pad with layers [("snare_v1.wav",1,41,"DRY"),("snare_v2.wav",42,84,"ROOM"),("snare.wav",85,127,"ECHO")] and recipe {"recipe":3,"alias":false,"layers":[...]}
    // export each supported format, read back with the sidecar
    assertEquals(listOf("DRY","ROOM","ECHO"), restored.velocityLayers.map { it.displayName })
    assertEquals(listOf(1..41, 42..84, 85..127), restored.velocityLayers.map { it.velStart..it.velEnd })
    assertEquals(3, restored.recipe!!["recipe"]!!.int())
    assertEquals(3, (restored.recipe!!["layers"] as JsonValue.Arr).items.size)
}

// ImportSidecarTest
@Test fun `a kept bank survives export and import and clears back to one sample`() {
    // export, import with sidecar, then assert the DRY layer file is byte-identical to the pre-export main file's original bytes
    // (the kit module has no clear verb; assert the data a clear needs is intact: layers[0].sampleFile exists and equals the original source bytes)
}
```

- [ ] **Step 2: Run**

Run: `./gradlew --no-daemon :kit:test --tests 'com.snipsnap.kit.StandaloneExportSidecarTest' --tests 'com.snipsnap.kit.ImportSidecarTest'`
Expected: PASS without code changes. If a test fails, the fix belongs in `KitExporter`/`ImportSidecar` and must not weaken the window-and-file matching at `ImportSidecar.kt:24-28`.

- [ ] **Step 3: Commit**

```bash
git add kit/src/test/kotlin/com/snipsnap/kit/StandaloneExportSidecarTest.kt kit/src/test/kotlin/com/snipsnap/kit/ImportSidecarTest.kt
git commit -m "Prove a STRIKE FX bank survives export and import"
```

---

### Task 6: The STRIKE FX section on the pad sheet

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt` (handler near `onGhostsToggle` :1672; control near the `ToggleChip("SOFT HITS", ...)` :3247; `audition` :535; `StepperSlider` :4213)
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/StrikeFxSectionTest.kt` (new; model on `app/src/androidTest/kotlin/com/snipsnap/app/ui/StrikeStripTest.kt`'s setup)

**Interfaces:**
- Consumes: `strikeFxAudition`, `strikeFxLayers`, `clearStrikeFxLayers`, `Copy.STRIKE_FX_*` (Task 3); `StrikeFxBank.DEFAULT_ROOM/SLAP/roomOf/slapOf/isStrikeFx` (Task 2); existing `commitPadEditNow`, `audition`, `busy`, `StepperSlider`, `ToggleChip`.
- Produces: a `@Composable private fun StrikeFxSection(pad: KitPad, room: Float, slap: Float, busy: Boolean, kept: Boolean, onRoom: (Float) -> Unit, onSlap: (Float) -> Unit, onAudition: () -> Unit, onKeep: () -> Unit, onClear: () -> Unit, scheme: Scheme, classColor: Color)`; a `@Composable private fun ReplaceLayersConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit)` modelled on `KitsScreen.kt:1169`'s `KitDeleteConfirmDialog` with `Copy.STRIKE_FX_REPLACES_LAYERS` as its text; and these handlers in `PadSheetScreen`:
  - `var strikeRoom by remember(model, slot) { mutableStateOf(StrikeFxBank.roomOf(recipe) ?: StrikeFxBank.DEFAULT_ROOM) }`, likewise `strikeSlap`.
  - `fun onStrikeAudition()`: `if (busy) return`; `appScope.launch` a job (cancel the previous one through a `strikeAuditionJob` var) that calls `m.strikeFxAudition(slot, strikeRoom, strikeSlap)` off the main thread, then for each snip `audition(snip, pad.level)` then `delay(1000)`.
  - `fun onStrikeKeep()`: if the pad has layers and is not already a STRIKE FX bank, set `confirmStrikeReplace = true` and return; else `commitPadEditNow("STRIKE FX", onSuccess = { onToast(Copy.STRIKE_FX_ON) }) { mm -> mm.strikeFxLayers(slot, strikeRoom, strikeSlap) }`.
  - `fun onStrikeClear()`: `commitPadEditNow("STRIKE FX", onSuccess = { onToast(Copy.STRIKE_FX_OFF) }) { mm -> mm.clearStrikeFxLayers(slot) }`.
  - The SOFT HITS toggle, when the pad is a STRIKE FX bank, goes through `ReplaceLayersConfirmDialog` the other way (same dialog, its confirm calls `mm.clearStrikeFxLayers(slot)` then `mm.addGhostLayers(slot)` in one `commitPadEditNow`).

- [ ] **Step 1: Write the failing Compose tests** (names and assertions; setup copied from `StrikeStripTest`):

```kotlin
@Test fun strike_fx_section_fits_a_narrow_phone_beside_soft_hits()      // 360 dp width: nodes "STRIKE FX", "ROOM", "SLAP", "AUDITION", "KEEP" all displayed, no horizontal clip
@Test fun audition_changes_nothing_in_the_kit()                          // tap AUDITION; kit.json bytes and pad.velocityLayers unchanged
@Test fun keep_then_clear_leaves_the_pad_as_found()                     // tap KEEP; assert layer names DRY ROOM ECHO shown; tap CLEAR; assert single sample, same bytes as before
@Test fun keep_over_soft_hits_asks_first()                               // enable SOFT HITS; tap KEEP; dialog text == Copy.STRIKE_FX_REPLACES_LAYERS; confirm; three layers
@Test fun knobs_read_back_from_a_kept_pad()                              // keep with ROOM .2 SLAP .5; reopen the sheet; sliders show .2 and .5
```

- [ ] **Step 2: Compile the test APK** (one run): `./gradlew --no-daemon :app:compileDebugAndroidTestKotlin`
Expected: FAIL on the new node names until Step 3.

- [ ] **Step 3: Implement** the section, dialog and handlers as the Interfaces block says. Knobs are two `StepperSlider`s with `valueText` the knob value to two decimals; `onFractionCommit` is a no-op (nothing renders on knob movement). AUDITION, KEEP and CLEAR are the sheet's existing button component (whatever `ToggleChip`'s sibling action chip is in this file; one component, no new type). CLEAR shows only when `StrikeFxBank.isStrikeFx(recipe)`. All controls `enabled = !busy`.

- [ ] **Step 4: Run** (one run each; the emulator is the slow part):
`./gradlew --no-daemon :app:compileDebugAndroidTestKotlin`, then `./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.StrikeFxSectionTest`
Expected: exit 0. If no emulator is attached, say so in the PR body and let CI's `emulator-tests` be the run.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt app/src/androidTest/kotlin/com/snipsnap/app/ui/StrikeFxSectionTest.kt
git commit -m "Pad sheet: STRIKE FX section with ROOM and SLAP knobs, AUDITION, KEEP and CLEAR"
```

---

### Task 7: The law, the docs, and the PR

**Files:**
- Modify: `shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt`
- Modify: `docs/STRIKE_STRIP.md` (a "STRIKE FX on your own pad" section), `docs/SYNTH_ROADMAP.md` (the STRIKE row's status), `app/README.md` (the pad sheet's controls list, if it enumerates them)

**Interfaces:**
- Consumes: `PadRecipe.isBanked`, `Breed.recipeOf`, `StarterKits.ALL` (iterated as `KitArtTest.kt:87` does).

- [ ] **Step 1: Write the law** in `ConventionTest.kt`:

```kotlin
@Test fun `law - a banked recipe lists exactly one layer per velocity layer`() {
    for (starter in StarterKits.ALL) {
        val kit = /* build the starter the way KitArtTest.kt:87 does */
        for (pad in kit.pads) {
            val r = Breed.recipeOf(pad) ?: continue
            if (r.isBanked) assertEquals(pad.velocityLayers.size, r.layers!!.size, "${starter.id} slot ${pad.slot}")
        }
    }
}
```

- [ ] **Step 2: Run**: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ConventionTest'`
Expected: PASS (no starter is banked yet; the law is armed for the day the demo starter moves to version 3).

- [ ] **Step 3: Docs**: in `docs/STRIKE_STRIP.md` add the section: what the two knobs are, that AUDITION writes nothing, what KEEP writes (three files, names, windows, recipe version 3), what CLEAR restores, that PASTE carries the bank, and the disclosure that no owner listen of a user-pad bank is recorded yet. Replace the doc's closing "General FX-layer authoring for arbitrary pads ... remain separate work" line with the new state. Recompute any count the edited prose states.

- [ ] **Step 4: Commit and open the PR**

```bash
git add shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt docs/STRIKE_STRIP.md docs/SYNTH_ROADMAP.md app/README.md
git commit -m "Law: a banked recipe has one layer per velocity layer; document STRIKE FX on a user pad"
git push -u origin claude/strike-fx-layers
```

Open a draft PR against `claude/mobile-mpc-drum-sampler-t58x74` titled "STRIKE FX on an ordinary pad: DRY, ROOM and ECHO layers from one sample", body in the `claude/` dialect (`## What this is`, `## What is in it`, `## Verified` with the exact Gradle commands and exit codes, `## Not verified` naming the full suites, the emulator run if not local, and "No one has heard a user-pad bank yet; the owner's snare is the audition gate"). The owner flips it ready after listening.

---

## Self-review notes

- Spec coverage: §6.1/6.2 → Task 6 and Task 2 constants; §6.3 → Task 3 `strikeFxAudition` + Task 6 sequence; §6.4 → Task 3; §6.5 → Tasks 1, 4; §6.6 → Task 5; §6.7 → unchanged by design; §7 tests → Tasks 1 to 7; §8 constraints → Global Constraints. Bank-B twins (§6.5 last bullet): bank-B twins (`remixBankB`) do not copy the recipe: each twin is ECHO through a fresh fx-only recipe from Shuffle, so it never carries a bank. It is BREED's kept children that copied the recipe, and `Breed.place` now strips `layers` from them. This plan leaves twins as they are and the PR body says so. That is a deliberate scope cut from the spec's "twin re-renders its own three files", to be its own follow-up.
- Names used consistently: `LayerRecipe`, `layers`, `isBanked`, `processBank`, `renderBank`, `StrikeFxBank.{DEFAULT_ROOM, DEFAULT_SLAP, NAMES, layers, roomOf, slapOf, isStrikeFx, render}`, `strikeFxAudition`, `strikeFxLayers`, `clearStrikeFxLayers`, `Plan.StrikeFx`, `Copy.STRIKE_FX_ON/OFF/REPLACES_LAYERS`.
- Review Focus items 1 to 5 each have a named test in Tasks 2, 3, 4 and 5.
