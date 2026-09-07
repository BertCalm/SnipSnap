# Sample Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user bring their own WAVs — a Splice folder, a USB dump, anything — into SnipSnap as a kit, with the classifier's guesses reviewable before anything is written.

**Architecture:** Three layers, each independently testable. A pure-JVM triage model in `:shell` decides what every file is and where it would go. An Android layer picks files via SAF and copies their bytes into app storage so every existing `File`-based engine works untouched. A review screen (CHOP's idiom) shows the proposal and commits it via `KitAssembler`. No vendor API exists or is used; nothing leaves the device.

**Tech Stack:** Kotlin 2.0.21 / JVM 17, Gradle, `kotlin.test` on JUnit platform, Compose (foundation-only) for the screen, Android SAF for file access.

**Spec:** `docs/superpowers/specs/2026-08-31-sample-import.md` — read its inventory table and "Constraints and the honest hard parts" before Task 1.

## Global Constraints

- **Never overwrite.** Import writes to empty pads or a brand-new kit only. If a target kit has no room, say so and offer a new kit. (User ruling, spec "Answered".)
- **Loops offer both.** A detected loop presents CHOP IT and KEEP IT WHOLE (a LOOP-class pad). Default highlight on CHOP IT; never decide silently. (User ruling.)
- **IMPORT lives on the FRESH TAPE menu** as a seventh starter alongside the six generated ones. (User ruling.)
- **WAV only this pass.** Compressed decode is a separate follow-up against `audio/DecodeContract`; the picker must say so rather than failing mysteriously.
- Copy picked bytes into app storage before decoding — `:audio` engines take `File`, SAF gives `Uri`. Do not add stream overloads to `:audio`.
- Colours are packed `0xRRGGBB` Int; every colour from the scheme or `Schemes.classColor`. Test names are backticked prose. Commit messages: one evocative line, blank line, plain body, no `feat:`/`fix:` prefixes.
- Standing UI rules (these failed review on six prior screens): Canvas dimensions via `.dp.toPx()`; no file I/O on the composition thread; busy flags cleared in `finally`; `catch (e: Exception)` rethrows `CancellationException` first; audition audio stops on `ON_STOP` and releases on dispose; hit targets ≥ `Layout.MIN_HIT_TARGET`; copy from `Copy` constants (inline strings only for messages embedding exception text).

## Engine surfaces this plan consumes (verified, exact)

```kotlin
// audio/Cleanup.kt
data class Snip(val samples: FloatArray, val channels: Int, val sampleRate: Int) {
    val frameCount: Int; val durationSeconds: Float
}
Cleanup.toMono(snip): Snip
// audio/WavReader.kt
WavReader.read(file: File): Snip
// audio/Classifier.kt
data class Classification(val drumClass: DrumClass, val confidence: Float, val features: Features)
Classifier.classify(snip: Snip): Classification
// audio/AutoPlace.kt
AutoPlace.PREFERENCES: Map<DrumClass, List<Int>>   // class -> preferred slots
AutoPlace.muteGroupFor(drumClass): Int
AutoPlace.colorFor(drumClass): String              // "#RRGGBB"
// audio/Tempo.kt
data class TempoEstimate(val bpm: Float, val confidence: Float) { val label: String }
Tempo.estimate(snip: Snip, config: Transients.Config = Transients.Config()): TempoEstimate?
// audio/Chopper.kt
Chopper.autoSliceCount(snip: Snip, config: Transients.Config = Transients.Config()): Int
// kit/KitAssembler.kt
data class ArrangedPad(snip, drumClass, recipe, level, tuneCoarse, tuneFine, softVariants, source)
KitAssembler.assembleArranged(name: String, arranged: List<ArrangedPad?>, dir: File,
    key: KeySpec? = null, tempoBpm: Float? = null): Kit
// app/KitShelf.kt
data class Entry(val dir: File, val kit: Kit)
KitShelf.freshName(base: String): String
```

---

### Task 1: Filename metadata parser

Every library bakes BPM and key into filenames; parsing them is free accuracy. `Tempo.label` already *writes* `"92bpm"`, so this is its inverse.

**Files:**
- Create: `audio/src/main/kotlin/com/snipsnap/audio/SampleName.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/SampleNameTest.kt`

**Interfaces:**
- Consumes: `KeySpec.parse(spec: String): KeySpec` (audio/KeySpec.kt — throws on unparseable; catch it).
- Produces: `SampleName.parse(stem: String): SampleName.Hints` where
  `data class Hints(val bpm: Float?, val key: KeySpec?)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SampleNameTest {

    @Test
    fun `bpm in the shapes libraries actually write`() {
        assertEquals(92f, SampleName.parse("BREAK_92bpm_dusty").bpm)
        assertEquals(140f, SampleName.parse("loop-140-BPM-drums").bpm)
        assertEquals(87f, SampleName.parse("87 bpm shuffle").bpm)
    }

    @Test
    fun `a bare number is only a bpm when it is plausibly one`() {
        assertEquals(128f, SampleName.parse("techno_128_loop").bpm)
        assertNull(SampleName.parse("kick_04").bpm, "04 is a take number, not a tempo")
        assertNull(SampleName.parse("sample_2048").bpm, "2048 is not a tempo")
    }

    @Test
    fun `keys, in the spellings that show up`() {
        assertEquals("Am", SampleName.parse("pad_Am_warm").key?.label)
        assertEquals("C#m", SampleName.parse("lead C#min bright").key?.label)
        assertEquals("F", SampleName.parse("stab_Fmaj").key?.label)
    }

    @Test
    fun `a stem with neither yields neither, and never throws`() {
        val hints = SampleName.parse("just_a_name")
        assertNull(hints.bpm)
        assertNull(hints.key)
    }

    @Test
    fun `Tempo's own label round-trips back out`() {
        val written = TempoEstimate(92.4f, 0.9f).label
        assertEquals(92f, SampleName.parse("kit_$written").bpm)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:test --tests "com.snipsnap.audio.SampleNameTest"`
Expected: FAIL — `Unresolved reference: SampleName`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.snipsnap.audio

/**
 * What a filename is willing to tell us.
 *
 * Every sample library writes tempo and key into the stem because that is
 * how producers browse. [Tempo.label] writes `92bpm` on the way out; this
 * reads it — and the handful of other spellings the rest of the world uses
 * — on the way in. Hints are preferred over detection when they are
 * plausible, because a library's own metadata beats our autocorrelation;
 * detection stays the fallback and the cross-check.
 */
object SampleName {

    data class Hints(val bpm: Float?, val key: KeySpec?)

    /** Tempi outside this are take numbers, sample rates, or years. */
    private val PLAUSIBLE_BPM = 60f..200f

    private val BPM_LABELLED = Regex("""(\d{2,3})\s*-?\s*bpm""", RegexOption.IGNORE_CASE)
    private val BARE_NUMBER = Regex("""(?<![\d.])(\d{2,3})(?![\d.])""")
    private val KEY = Regex(
        """(?<![A-Za-z])([A-G][#b]?)\s*(maj|major|min|minor|m)?(?![A-Za-z])""",
    )

    fun parse(stem: String): Hints = Hints(bpm = bpmOf(stem), key = keyOf(stem))

    private fun bpmOf(stem: String): Float? {
        BPM_LABELLED.find(stem)?.groupValues?.get(1)?.toFloatOrNull()
            ?.takeIf { it in PLAUSIBLE_BPM }?.let { return it }
        // No "bpm" word: a bare number is a tempo only if it could be one.
        return BARE_NUMBER.findAll(stem)
            .mapNotNull { it.groupValues[1].toFloatOrNull() }
            .firstOrNull { it in PLAUSIBLE_BPM }
    }

    private fun keyOf(stem: String): KeySpec? {
        for (m in KEY.findAll(stem)) {
            val note = m.groupValues[1]
            val quality = m.groupValues[2].lowercase()
            val spec = when {
                quality.startsWith("min") || quality == "m" -> "${note}min"
                quality.startsWith("maj") -> "${note}maj"
                else -> note
            }
            runCatching { KeySpec.parse(spec) }.getOrNull()?.let { return it }
        }
        return null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :audio:test --tests "com.snipsnap.audio.SampleNameTest"`
Expected: PASS. If `KeySpec.parse`'s accepted spellings differ from `"Amin"`/`"Amaj"`, read `KeySpec.kt:53`'s regex and `SCALE_WORDS` map and adjust `spec` to what it actually accepts — do not change `KeySpec`.

- [ ] **Step 5: Commit**

```bash
git add audio/src/main/kotlin/com/snipsnap/audio/SampleName.kt \
        audio/src/test/kotlin/com/snipsnap/audio/SampleNameTest.kt
git commit -m "Filenames have been telling us the tempo all along

Every library writes bpm and key into the stem because that is how
producers browse. Tempo.label already writes 92bpm on the way out; this
reads it back, plus the spellings everyone else uses. A bare number is a
tempo only when it could plausibly be one — 04 is a take, 2048 is not a
tempo."
```

---

### Task 2: The triage model

Pure JVM. Given named audio, decide what each file is, where it would go, and what the user can do about it. No Android, no disk writes.

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/SampleImport.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/SampleImportTest.kt`

**Interfaces:**
- Consumes: `SampleName.parse` (Task 1); `Classifier.classify`; `Tempo.estimate`; `Chopper.autoSliceCount`; `AutoPlace.PREFERENCES`; `Snip`.
- Produces:
  ```kotlin
  enum class ImportKind { ONE_SHOT, LOOP }
  data class Candidate(
      val sourceName: String, val snip: Snip, val kind: ImportKind,
      val drumClass: DrumClass, val confidence: Float,
      val bpm: Float?, val key: KeySpec?, val override: DrumClass? = null,
  ) { val effectiveClass: DrumClass }
  class ImportPlan(val candidates: List<Candidate>) {
      fun cycleClass(index: Int): ImportPlan
      fun placements(padCount: Int = 16): List<Candidate?>
      fun overflow(padCount: Int = 16): List<Candidate>
      fun arranged(padCount: Int = 16): List<ArrangedPad?>
      val kitTempo: Float?
  }
  object SampleImport { fun triage(sources: List<Pair<String, Snip>>): ImportPlan }
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleImportTest {

    /** One short thump — a one-shot by any measure. */
    private fun oneShot(rate: Int = 44_100): Snip {
        val n = rate / 8
        return Snip(FloatArray(n) { i ->
            val env = (1f - i.toFloat() / n)
            (sin(2.0 * Math.PI * 60.0 * i / rate).toFloat()) * env * env
        }, 1, rate)
    }

    /** Four thumps a beat apart at 120bpm — rhythmic and long. */
    private fun loop(rate: Int = 44_100): Snip {
        val beat = rate / 2
        val n = beat * 4
        val out = FloatArray(n)
        for (b in 0 until 4) {
            for (i in 0 until beat / 8) {
                val env = 1f - i.toFloat() / (beat / 8)
                out[b * beat + i] = sin(2.0 * Math.PI * 60.0 * i / rate).toFloat() * env * env
            }
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `a short hit triages as a one-shot, a long rhythmic file as a loop`() {
        val plan = SampleImport.triage(
            listOf("kick_01" to oneShot(), "break_120bpm" to loop()),
        )
        assertEquals(ImportKind.ONE_SHOT, plan.candidates[0].kind)
        assertEquals(ImportKind.LOOP, plan.candidates[1].kind)
    }

    @Test
    fun `the filename's bpm wins over detection when it is plausible`() {
        val plan = SampleImport.triage(listOf("break_92bpm" to loop()))
        assertEquals(92f, plan.candidates[0].bpm, "the stem said 92")
    }

    @Test
    fun `cycling a class overrides the classifier without losing its guess`() {
        val plan = SampleImport.triage(listOf("mystery" to oneShot()))
        val guessed = plan.candidates[0].drumClass
        val cycled = plan.cycleClass(0)
        assertTrue(cycled.candidates[0].override != null, "the override is recorded")
        assertEquals(guessed, cycled.candidates[0].drumClass, "the machine's guess is kept")
        assertEquals(cycled.candidates[0].override, cycled.candidates[0].effectiveClass)
    }

    @Test
    fun `placements follow AutoPlace's preferences and never collide`() {
        val plan = SampleImport.triage(
            List(4) { "hit_$it" to oneShot() },
        )
        val placed = plan.placements(16)
        assertEquals(16, placed.size)
        val occupied = placed.filterNotNull()
        assertEquals(occupied.size, occupied.distinct().size, "no candidate placed twice")
    }

    @Test
    fun `more candidates than pads overflow rather than overwrite`() {
        val plan = SampleImport.triage(List(20) { "hit_$it" to oneShot() })
        val placed = plan.placements(16).filterNotNull()
        assertEquals(16, placed.size, "a 16-pad kit takes 16")
        assertEquals(4, plan.overflow(16).size, "the rest are named, not dropped")
    }

    @Test
    fun `arranged pads carry the effective class, not the overridden guess`() {
        val plan = SampleImport.triage(listOf("hit" to oneShot())).cycleClass(0)
        val arranged = plan.arranged(16).filterNotNull()
        assertEquals(plan.candidates[0].effectiveClass, arranged[0].drumClass)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SampleImportTest"`
Expected: FAIL — `Unresolved reference: SampleImport`.

- [ ] **Step 3: Write the implementation**

Write `SampleImport.kt` implementing exactly the Interfaces block above. Rules the tests encode, plus these that they cannot:

- `ImportKind`: LOOP when the snip is long enough to be a loop AND rhythmic — use `Tempo.estimate(snip)` and treat a null estimate or `confidence < 0.3f` as ONE_SHOT (0.3 is `TempoEstimate`'s own documented "don't label it" floor — cite that KDoc in a comment). Add a duration floor so a long non-rhythmic pad isn't a loop; pick it from `Chopper`'s own notion of sliceable material rather than inventing a number, and say in a comment which you used.
- `bpm`: `SampleName.parse(stem).bpm ?: Tempo.estimate(snip)?.bpm`.
- `key`: `SampleName.parse(stem).key` only (detection of key from arbitrary audio is out of scope; leave null otherwise).
- `Candidate.effectiveClass`: `override ?: drumClass`.
- `cycleClass(index)`: advance through `DrumClass.entries` from the current effective class, returning a NEW `ImportPlan` (immutable, matching `ChopReviewModel`'s own idiom — read it).
- `placements`: honour `AutoPlace.PREFERENCES` — a candidate takes its class's first free preferred slot; anything unplaced by preference fills the lowest free slot; anything still unplaced is overflow. LOOP-kind candidates prefer `DrumClass.LOOP`'s slots.
- `arranged`: `ArrangedPad(snip = ..., drumClass = effectiveClass, source = mapOf("file" to sourceName))` — `source` is what the PAD SHEET's provenance line reads.
- `kitTempo`: the most common non-null `bpm` among candidates, else null (it becomes `assembleArranged`'s `tempoBpm`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SampleImportTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/SampleImport.kt \
        shell/src/test/kotlin/com/snipsnap/shell/SampleImportTest.kt
git commit -m "Triage: what is this file, and where would it go

The classifier already knows a kick from a hat and AutoPlace already knows
where a kick belongs. This is the part that asks both on behalf of a folder
the user just handed us, keeps the machine's guess next to the user's
correction, and overflows rather than overwrites when a folder is bigger
than a kit."
```

---

### Task 3: SAF picker and the copy-into-app-storage bridge

The Android half. Picks files, copies bytes into app storage so every `File`-based engine works untouched, decodes WAVs, and hands `(name, Snip)` pairs to Task 2.

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/SampleIntake.kt`
- Test: none (Android framework code; the logic it feeds is covered by Task 2). State this in the commit body.

**Interfaces:**
- Consumes: `SampleImport.triage` (Task 2); `WavReader.read`; `Cleanup.toMono`.
- Produces:
  ```kotlin
  class SampleIntake(private val context: Context) {
      data class Intake(val plan: ImportPlan, val skipped: List<String>)
      suspend fun ingest(uris: List<Uri>, onProgress: (done: Int, total: Int) -> Unit): Intake
      companion object { val MIME_TYPES = arrayOf("audio/wav", "audio/x-wav") }
  }
  ```

- [ ] **Step 1: Write `SampleIntake.kt`**

Requirements — no test file, so the code must be self-evidently careful:
- `ingest` runs entirely on `Dispatchers.IO`; it is `suspend` and must check `coroutineContext.isActive` between files so a cancelled import stops promptly on a thousand-file folder.
- For each `Uri`: resolve a display name via `DocumentFile.fromSingleUri(context, uri)?.name` (or the `OpenableColumns.DISPLAY_NAME` cursor — pick one, comment why), copy the stream into `context.cacheDir/import/<sanitised>.wav`, then `Cleanup.toMono(WavReader.read(file))`.
- Anything that throws (not a WAV, unreadable, unsupported) lands in `skipped` **by name** — never crash the import for one bad file. A skipped list the user can see beats a mystery.
- `onProgress(done, total)` after each file so the screen can show a count.
- Clear `cacheDir/import` at the START of an ingest (last run's copies are dead weight).
- KDoc must state: SAF hands out `Uri`s and every `:audio` engine takes `File`, so bytes are copied into app storage rather than teaching `:audio` to read streams; and that this pass is WAV-only, with `DecodeContract` naming the follow-up for compressed formats.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/SampleIntake.kt
git commit -m "Bytes first, questions after: the intake bridge

SAF hands out Uris; every engine in :audio takes a File. Rather than teach
the audio layer to read streams, the intake copies what the user picked
into app storage and hands the engines ordinary files. A folder with one
bad WAV in it imports the rest and names the one it skipped.

No unit test: this is framework plumbing, and the model it feeds is covered
by SampleImportTest."
```

---

### Task 4: The IMPORT review screen

CHOP's idiom, pointed at a folder. Shows what was found, what it thinks each is, where each is going — and writes nothing until the user commits.

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/ImportScreen.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (state + the FRESH TAPE entry's route)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt` (the IMPORT starter row)
- Test: none (Compose); the model is Task 2's.

**Interfaces:**
- Consumes: `SampleIntake.ingest`, `ImportPlan`, `Candidate`, `KitAssembler.assembleArranged`, `KitShelf.freshName`.
- Produces: `@Composable fun ImportScreen(...)` and an `AppScreen.IMPORT` route.

- [ ] **Step 1: Add IMPORT to the FRESH TAPE menu**

The FRESH TAPE sheet lists `StarterKits.ALL`. Add a seventh row BELOW them — IMPORT is a starter, but not a generated one, so it sits apart with a hairline above it (`scheme.ink3`). Label "IMPORT ▸", blurb "Your own samples. A folder, a phone, a friend's USB stick." Tapping it launches the SAF picker.

- [ ] **Step 2: Launch the picker and ingest**

Use `rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments())` with `SampleIntake.MIME_TYPES`. On result: run `ingest` with a busy state showing `done/total`, then hold the resulting `Intake`. Cancellation and errors follow the standing rules (busy in `finally`, rethrow `CancellationException`).

- [ ] **Step 3: The review list**

One row per candidate: source name (the ORIGINAL, so the user recognises their file), a class chip in `Schemes.classColor(effectiveClass)` (tap cycles via `plan.cycleClass(i)`), the destination pad label, and for LOOP-kind rows two actions — **CHOP IT** (highlighted default) and **KEEP IT WHOLE**. Overflow candidates render below a divider under a line naming how many did not fit; they are not placed and not written.

Skipped files list at the bottom in `scheme.ink2` — count and names.

- [ ] **Step 4: Commit the import**

Primary action IMPORT: `KitAssembler.assembleArranged(KitShelf.freshName("IMPORT"), plan.arranged(16), newKitDir, key = null, tempoBpm = plan.kitTempo)` on `Dispatchers.IO`, then add to the shelf and open the new kit (the `onSentToGrid` pattern in `App.kt`). **Never target an existing kit's pads in this pass** — new kit only, per the global constraint; the constraint's "empty pads of the open kit" path is a follow-up, and the screen should not offer what it cannot honour.

A candidate marked CHOP IT is NOT included in `arranged`; instead, after the kit lands, if exactly one loop was marked CHOP IT, navigate to CHOP with it (the `TapeCommit` shape App already carries). If more than one, import the kit and toast that the loops are on the shelf to chop one at a time — do not invent a queue.

- [ ] **Step 5: Verify**

Run: `./gradlew test` (baseline is whatever HEAD carries — report the real number) and `./gradlew :app:assembleDebug`.
Expected: both green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/ui/ImportScreen.kt \
        app/src/main/kotlin/com/snipsnap/app/App.kt \
        app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt
git commit -m "IMPORT joins the fresh tape menu

A kit begins one of two ways: the machine invents the sound, or you bring
it. Six starters did the first; this does the second. Nothing is written
until the list looks right — the classifier's guesses are chips you can
cycle, the ones that did not fit are named rather than dropped, and a loop
asks whether to chop it or keep it whole rather than deciding for you."
```

---

### Task 5: Teach the classifier from import corrections

Import corrections are training data of exactly the kind CHOP already feeds `TeachLog` — same consent gate, same file.

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/SampleImport.kt` (add the accessor)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/ImportScreen.kt` (call it under the gate)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/SampleImportTest.kt` (append)

**Interfaces:**
- Consumes: `TeachLog.Example(label: DrumClass, features: Features, machineSaid: DrumClass)` — read `shell/TeachLog.kt:25` for the exact field list before writing.
- Produces: `ImportPlan.labeledOverrides(): List<TeachLog.Example>`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `only corrected candidates become teaching examples`() {
        val plan = SampleImport.triage(
            listOf("a" to oneShot(), "b" to oneShot()),
        ).cycleClass(0)
        val examples = plan.labeledOverrides()
        assertEquals(1, examples.size, "one correction, one example")
        assertEquals(plan.candidates[0].effectiveClass, examples[0].label)
        assertEquals(plan.candidates[0].drumClass, examples[0].machineSaid)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.SampleImportTest"`
Expected: FAIL — `Unresolved reference: labeledOverrides`.

- [ ] **Step 3: Implement**

`labeledOverrides()` returns an example per candidate whose `override != null`, carrying the user's class as `label`, the classifier's as `machineSaid`, and the `Features` from the classification (keep the `Classification` on `Candidate` if it isn't already — adjust the data class and its constructor sites if so). Mirror `ChopReviewModel.labeledOverrides()`; read it first.

- [ ] **Step 4: Gate the call in the screen**

In `ImportScreen`'s commit path, after the kit lands: `if (teachEnabled && plan.labeledOverrides().isNotEmpty()) TeachLog.append(file, plan.labeledOverrides())` — same `teachEnabled` flag CHOP uses, same target file CHOP writes (find it; do not invent a second log). No extra toast.

- [ ] **Step 5: Run tests**

Run: `./gradlew test` and `./gradlew :app:assembleDebug`
Expected: both green.

- [ ] **Step 6: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/SampleImport.kt \
        shell/src/test/kotlin/com/snipsnap/shell/SampleImportTest.kt \
        app/src/main/kotlin/com/snipsnap/app/ui/ImportScreen.kt
git commit -m "Every correction on the import list teaches the machine

Same consent gate as CHOP, same log, same shape: what the classifier said,
what you said instead, and the features that fooled it. A folder of a
thousand samples is the best teaching material this app will ever see."
```

---

## Verification

After Task 5:

```bash
./gradlew test && ./gradlew :app:assembleDebug
```

Then confirm on a device: pick a folder of WAVs, watch the review list appear, cycle a class chip, mark a loop CHOP IT, import — a new kit lands on the shelf, opens, and the pads play.

## Known follow-ups (not this plan)

- **Compressed decode** (mp3/m4a) via `MediaExtractor` against `audio/DecodeContract`, with the device instrumentation test that contract specifies. The picker's WAV-only limit lifts when this lands.
- **Import into the open kit's empty pads**, the second half of the never-overwrite ruling.
- **Resampling on mixed-rate folders** — `Resampler` exists; decide the kit's rate and resample the rest rather than refusing.
