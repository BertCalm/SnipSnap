# Expansions on the Phone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a phone user gather shelf kits into a named expansion with one tile, born from the shelf's hold action with its art picked at birth, and export it to the card as one MPC expansion folder or `.xpn`, with a last look at the tile before the write.

**Architecture:** Kits gain a stable optional `id` in `kit.json`, assigned when a kit first joins an expansion. An `ExpansionStore` in `:shell` keeps one `expansion.json` per expansion under the app's files dir (title, identifier fixed at birth, member kit ids in order, seed kit id, art knobs) and resolves members against the shelf, so rename and bin need no bookkeeping. An `ExpansionExportModel` beside `ExportWizardModel` drives `PackBuilder` over the members with per-member preflight, J-Cards and read-back. On the phone, the shelf's armed row gains an EXPANSION door, the shelf lists expansions, and EXPORT gains a KIT / EXPANSION switch.

**Tech Stack:** Kotlin, Gradle multi-module (`:kit`, `:shell`, `:app`), Jetpack Compose (`:app`), the tile family from Part 2 (`ArtKnobs`, `KitArt`, `ArtPickerSheet`).

**Spec:** `docs/superpowers/specs/2026-10-31-mark-tile-art-expansions-design.md` (Part 3). Builds on Part 2 (PR #854); this branch is stacked on it.

## Global Constraints

- Run Gradle only as `./gradlew --no-daemon …`, one Gradle at a time on the machine; gate on exit code and on the XML report counts. `:shell:test` takes about nine minutes; run single classes with `--tests` while iterating.
- Device tests on the `snipsnap_pixel` AVD: `./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`. Inside a `BottomSheet`, press controls with `performSemanticsAction(SemanticsActions.OnClick)`; `performScrollTo` inside the dialog's scroll hangs.
- No model identifiers in code or comments. Commit footers are the harness's.
- Steward laws: plain declarative commit titles with why-bodies; tests never skipped or weakened; the Copy law (every `Copy` string shouts and ends in a full stop, or sits on `PersonalityTest.notASentence` with a comment; busy lines end in `…`); digit formatting passes `Locale.ROOT` (`FormatLocaleTest`); `"kit.json"` is spelled only in `KitStore.kt` (use `KitStore.FILE_NAME`); the AWT law (`:app` never calls an AWT entry point).
- The spec's decisions, verbatim in effect:
  - Expansion store: `expansions/<id>/expansion.json` under the app's files dir, holding the MPC-safe title, manufacturer `"SnipSnap"`, the identifier, member kit ids in order, the seed kit id, and the art knobs. A kit may belong to more than one expansion.
  - Kit identity: an optional `id` field in `kit.json`. Rename and bin move directories and never touch membership; a binned kit is hidden from its expansions and rejoins on restore; permanent deletion removes it from every expansion. An expansion with no members keeps its name and knobs, shows a placeholder, and refuses to export.
  - Identifier: fixed at birth, `app.snipsnap.<title-slug>.<short random id>`, never recomputed. A rename changes the title and the on-card folder name, not the identifier. The name prompt refuses a title another expansion already has.
  - Birth: the shelf's hold action gains ADD TO EXPANSION, listing existing expansions and NEW. NEW asks for a name (the one typed-name dialog, MPC-safe rules as the shelf's rename), then opens the art picker for the new expansion seeded from the kit being added; choosing art finishes the birth and the kit is a member. Adding to an existing expansion just adds. The picker lists which kit seeds the art.
  - Export: a KIT / EXPANSION switch at the top of EXPORT; `ExportFormat` stays per-kit; the expansion subject has its own model, offering the on-card folder and `.xpn` only; preflight runs over every member and a failing member is skipped and named; read-back runs per member; each member gets its J-Card; folder and `.xpn` are named after the title; before the write, the tile as it will be written with a CHANGE ART door into the same picker.
  - Art for an expansion draws from the seed kit alone; the caption is the expansion title; if the seed kit leaves, the first remaining member seeds.
- Decisions this plan makes where the spec is silent (each is a ruling the owner can overturn):
  - A kit's `id` is written when the kit first joins an expansion (`KitIds.ensure`), not on every read: a read that writes would rewrite `kit.json` on paths that today leave its bytes alone.
  - The id is 12 lowercase hex characters; the expansion's short random id is 6 lowercase alphanumerics; both from an injectable `Random` so tests are deterministic.
  - The ADD TO EXPANSION sheet marks the expansions the kit is already in; tapping one of those takes the kit out. That is the only place a member leaves.
  - Expansions are listed on the shelf above the kits; holding one reveals RENAME and DELETE like a kit. Deleting an expansion deletes its `expansion.json` only; its kits stay on the shelf. There is no bin for expansions.
  - Inside a pack, J-Cards ride as `[J-Cards]/<kit>.png`, the convention `snipsnap pack` already uses.
  - The phone writes the expansion's knobs to `expansion.json` at birth, so its tile can never drift with the screen's scheme.

## Review Focus

1. A member kit renamed, binned, restored or permanently deleted between birth and export: the expansion must show and export exactly the members still on the shelf, in birth order, never throw. Tests in Task 3 (`membership survives rename, bin and restore`, `permanent deletion leaves every expansion`).
2. Two expansions whose titles differ only in case, or a title that is MPC-unsafe: the name prompt refuses both, and a rename refuses them too. Test in Task 2 (`a title another expansion has is refused, whatever its case`).
3. Every member fails preflight: nothing is written and the user is told which kits failed and why, never a half-written folder. Test in Task 4 (`an expansion whose every member is blocked writes nothing and names them`).
4. Re-exporting the same expansion twice with untouched knobs writes the same tile bytes and the same identifier. Test in Task 4 (`a second export writes the same tile and identifier`).
5. A corrupt or later-version `expansion.json` beside good ones: the shelf lists the good ones and skips the bad one, never crashes. Test in Task 2 (`a corrupt expansion.json is skipped, not fatal`).

---

### Task 1: Kits gain a stable id

**Files:**
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/Kit.kt:267` (`Kit` gains `val id: String? = null`), `kit/src/main/kotlin/com/snipsnap/kit/KitStore.kt` (`toJson` writes `"id"` only when non-null; `fromJson` reads `obj["id"]` as an optional string)
- Create: `kit/src/main/kotlin/com/snipsnap/kit/KitIds.kt`
- Test: `kit/src/test/kotlin/com/snipsnap/kit/KitIdsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Kit(..., val id: String? = null)   // last parameter, defaulted
  object KitIds {
      /** The kit's id, writing a fresh one into its kit.json first when it has none. */
      fun ensure(kitDir: File, random: Random = Random.Default): String
      /** The id already in the kit's kit.json, or null; never writes. */
      fun read(kitDir: File): String?
      /** 12 lowercase hex characters. */
      fun fresh(random: Random): String
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a kit without an id loads and saves exactly as before`()
// KitStore.save(kit without id, dir); bytes A; load + save; bytes B == A; the text has no "\"id\""

@Test fun `ensure writes an id once and keeps it`()
// KitIds.read(dir) == null; val a = KitIds.ensure(dir, Random(7)); a.length == 12, all hex lowercase;
// KitIds.ensure(dir, Random(99)) == a; KitStore.load(dir).id == a

@Test fun `the id survives a rename's rewrite`()
// ensure; KitStore.save(KitStore.load(dir).copy(name = "Renamed"), dir); KitStore.load(dir).id == the same id
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :kit:test --tests 'com.snipsnap.kit.KitIdsTest'`
Expected: compilation fails on `KitIds`.

- [ ] **Step 3: Implement**

`ensure` loads, returns `kit.id` when present, else saves `kit.copy(id = fresh(random))` through `KitStore.save` and returns it.

- [ ] **Step 4: Run `:kit:test` and `KitBuilderTest`/`KitStoreTest` in `:shell` and `:kit`**

Run: `./gradlew --no-daemon :kit:test` then `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.KitBuilderTest'`
Expected: green; the no-op byte-equality test still holds because nothing writes an id on load.

- [ ] **Step 5: Commit** — "Give kits a stable id when they first join an expansion"

---

### Task 2: The expansion store

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/ExpansionStore.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ExpansionStoreTest.kt`

**Interfaces:**
- Consumes: `ArtKnobs`, `ArtSidecar`'s JSON shape for knobs (reuse by extracting `ArtSidecar.knobsToJson(knobs): JsonValue.Obj` and `knobsFromJson(obj): ArtKnobs?` as internal helpers in `ArtKnobs.kt`), `Names.isMpcSafe`, `ExpansionMeta`'s identifier regex, `AtomicFile.writeText`.
- Produces:
  ```kotlin
  data class Expansion(
      val id: String,                 // the folder name: 6 lowercase alphanumerics
      val title: String,              // MPC-safe
      val identifier: String,         // app.snipsnap.<slug>.<id>, fixed at birth
      val memberKitIds: List<String>, // birth order, no duplicates
      val seedKitId: String?,
      val knobs: ArtKnobs,
  ) { val manufacturer: String get() = "SnipSnap" }

  class ExpansionStore(val root: File, private val random: Random = Random.Default) {
      sealed interface NameCheck { data object Ok : NameCheck; data object Unsafe : NameCheck; data object Taken : NameCheck }
      fun list(): List<Expansion>                      // sorted by title, case-insensitive; corrupt files skipped
      fun get(id: String): Expansion?
      fun checkTitle(title: String, exceptId: String? = null): NameCheck
      fun create(title: String, firstKitId: String, knobs: ArtKnobs): Expansion   // require(checkTitle == Ok)
      fun add(id: String, kitId: String): Expansion    // no-op when already a member
      fun remove(id: String, kitId: String): Expansion // clears seedKitId when it was the seed
      fun rename(id: String, title: String): Expansion // require(checkTitle(title, id) == Ok); identifier unchanged
      fun setArt(id: String, knobs: ArtKnobs, seedKitId: String?): Expansion
      fun delete(id: String): Boolean
      /** Drop every member id not in [liveKitIds] (kits on the shelf or in its bin) from every expansion; returns how many memberships went. */
      fun prune(liveKitIds: Set<String>): Int
      fun containing(kitId: String): List<Expansion>
      companion object {
          const val FILE = "expansion.json"
          fun identifier(title: String, id: String): String   // "app.snipsnap." + slug + "." + id; slug = letters/digits lowercased, blank → "pack"
      }
  }
  ```
  `expansion.json`: `{"version":1,"title":…,"manufacturer":"SnipSnap","identifier":…,"members":[…],"seed":…|null,"art":{…ArtSidecar's fields…}}`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `create, add, remove, rename and delete round trip through disk`()
// create("Dusty Crate", "k1", knobs) → id 6 chars, identifier == "app.snipsnap.dustycrate.${id}", members [k1], seed k1;
// add(id, "k2"); add(id, "k2") (no-op); members [k1, k2]; a fresh ExpansionStore(root).get(id) equals the latest value;
// rename(id, "Dusty Crate II") keeps identifier; remove(id, "k1") → members [k2], seedKitId == null; delete(id) → get(id) == null

@Test fun `a title another expansion has is refused, whatever its case`()
// create("Night Shift", …); checkTitle("night shift") == Taken; checkTitle("Night Shift", exceptId = thatId) == Ok;
// checkTitle("A/B") == Unsafe; checkTitle("Ends.") == Unsafe; rename(other, "NIGHT SHIFT") throws IllegalArgumentException

@Test fun `the identifier is fixed at birth and safe for the MPC`()
// identifier("Café Beats!", "a1b2c3") == "app.snipsnap.cafbeats.a1b2c3" (non-ASCII letters dropped by the slug rule: isLetterOrDigit && code < 128);
// identifier("!!!", "zz9zz9") == "app.snipsnap.pack.zz9zz9"; every create's identifier matches ExpansionMeta's regex (construct an ExpansionMeta with it)

@Test fun `prune drops kits that are gone for good and keeps the rest`()
// two expansions sharing k1; prune(setOf("k2")) == 2; both now lack k1; an expansion left empty still lists with its title and knobs

@Test fun `a corrupt expansion.json is skipped, not fatal`()
// one good expansion; a second folder whose expansion.json is "not json"; a third with version 2; list() == [the good one]
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ExpansionStoreTest'`
Expected: compilation fails on `ExpansionStore`.

- [ ] **Step 3: Implement**

Folder ids: `fresh` 6 chars from `"abcdefghijklmnopqrstuvwxyz0123456789"`, retried while the folder exists. Writes through `AtomicFile.writeText`. The slug keeps ASCII letters and digits only (`ExpansionMeta`'s regex is ASCII).

- [ ] **Step 4: Run the class, then `ArtKnobsTest` (the extracted knob JSON must not change `art.json`'s bytes)**

Expected: green.

- [ ] **Step 5: Commit** — "Keep expansions as named collections of kit ids with their own tile knobs"

---

### Task 3: Members resolve against the shelf

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/ExpansionMembers.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ExpansionMembersTest.kt`

**Interfaces:**
- Consumes: `Expansion`, `KitIds.read`, `KitStore.list`/`load`.
- Produces:
  ```kotlin
  /** A member kit present on the shelf: its folder and its kit. */
  data class Member(val kitId: String, val dir: File, val kit: Kit)
  object ExpansionMembers {
      /** Every kit folder under [shelfRoot] (not the bin) that has an id, by id. Kits without an id are not members of anything. */
      fun index(shelfRoot: File): Map<String, Member>
      /** [expansion]'s members present in [index], in birth order. */
      fun present(expansion: Expansion, index: Map<String, Member>): List<Member>
      /** The seed: the seed kit when present, else the first present member, else null. */
      fun seed(expansion: Expansion, index: Map<String, Member>): Member?
      /** Ids of kits on the shelf or in its bin (folders under [binDir]): what prune must keep. */
      fun liveIds(shelfRoot: File, binDir: File): Set<String>
  }
  ```
  `index` reads `kitDir/kit.json` for every direct child of `shelfRoot` that `KitStore.list` returns, skipping reserved and dot-folders as `KitShelf.list` does (`.bin`, `Instruments`, `Rooms`, `snips`).

- [ ] **Step 1: Write the failing tests** (fixture: a temp shelf root with three kits saved by `KitStore.save`, ids by `KitIds.ensure(dir, Random(n))`; the bin is `root/.bin`, and "binning" moves a folder there with `renameTo`, as `KitShelf.deleteKit` does)

```kotlin
@Test fun `membership survives rename, bin and restore`()
// expansion of [k1, k2, k3]; rename k2's folder and its kit name → present() still [k1, k2, k3] in that order;
// bin k2 → present() == [k1, k3], seed falls to k1 when k2 was the seed; restore k2 (move back) → [k1, k2, k3] again

@Test fun `permanent deletion leaves every expansion`()
// two expansions with k1; bin k1 then delete its binned folder; store.prune(liveIds(root, bin)) == 2; neither lists k1

@Test fun `a kit without an id is a member of nothing`()
// a kit saved without KitIds.ensure is absent from index()
```

- [ ] **Step 2: Run to verify they fail** — compilation fails on `ExpansionMembers`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the class** — green.
- [ ] **Step 5: Commit** — "Resolve an expansion's members against the shelf so rename and bin need no bookkeeping"

---

### Task 4: Exporting an expansion

**Files:**
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/ExportReadBack.kt` (extract `fun verifyProgram(kit: Kit, program: File, format: ExportFormat): List<Finding>`; `verify` delegates to it after `programFile`)
- Create: `shell/src/main/kotlin/com/snipsnap/shell/ExpansionExport.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ExpansionExportTest.kt`

**Interfaces:**
- Consumes: `PackBuilder.build(kitDirs, destRoot, meta, artworkPng, withPreviews, asXpn, overwrite, extraFiles): PackBuilder.Result`, `ExpansionMeta(title, manufacturer, identifier = …)`, `Preflight`, `ExportReadBack.verifyProgram`, `ExpansionMembers`, `Label.forKit(dir)` for the J-Card catalog.
- Produces:
  ```kotlin
  class ExpansionExportModel(
      private val expansion: Expansion,
      private val members: List<Member>,           // present members, in order
      private val seed: Member?,
      private val tileRenderer: (Kit, File, ArtKnobs, String) -> ByteArray = { k, d, knobs, label -> KitArt.png(k, d, knobs, label = label) },
      private val jCardRenderer: (Kit, File, String?) -> ByteArray = JCard::awtPng,
  ) {
      enum class Form { FOLDER, XPN }
      var form: Form = Form.FOLDER
      var knobs: ArtKnobs = expansion.knobs        // the picker's CHANGE ART edits this
      val empty: Boolean                           // no present members
      /** Per member: its preflight findings; a member with any FAIL is skipped. */
      fun runPreflight(): Map<Member, List<Finding>>
      /** The tile as it will be written: the seed kit drawn with [knobs], captioned with the title; null when empty. */
      fun tile(): ByteArray?
      sealed interface WriteResult {
          data class Done(val result: PackBuilder.Result, val readBack: Map<String, List<Finding>>, val skipped: List<Pair<String, String>>) : WriteResult
          data class WouldOverwrite(val path: File) : WriteResult
          data class Nothing(val skipped: List<Pair<String, String>>) : WriteResult   // empty, or every member blocked
      }
      fun write(destRoot: File, overwrite: Boolean): WriteResult
  }
  ```
  `write`: empty → `Nothing(emptyList())`; meta from the expansion's title and identifier; `extraFiles` = `"[J-Cards]/<kit.name>.png"` per member that passes preflight; `asXpn = form == XPN`; `PackBuilder`'s `DestinationExists` → `WouldOverwrite`; its "nothing packable" `IOException` → `Nothing(skipped)`; read-back runs `verifyProgram` on `result.directory/Programs/<kit.name>.xpm` for every packed member.

- [ ] **Step 1: Write the failing tests** (fixture: `PackBuilderTest`'s `buildKit` shape, three kits with ids; a "blocked" kit names a missing WAV)

```kotlin
@Test fun `an expansion lands as one folder named after its title with a tile and a J-Card per member`()
// FOLDER: Done; result.directory.name == title; Expansion.xml carries the identifier and "SnipSnap"; the tile PNG exists;
// [J-Cards]/<each>.png exists; readBack has an entry per member and no FAIL

@Test fun `the xpn form writes the archive twin`()
// XPN: result.xpn != null and is named "<title>.xpn"

@Test fun `a blocked member is skipped and named, the rest land`()
// [good, blocked, good]: Done; skipped == [blocked.name to its FAIL message]; packed has the two good names

@Test fun `an expansion whose every member is blocked writes nothing and names them`()
// all blocked: Nothing(skipped of size 3); destRoot has no Expansions/<title>

@Test fun `an empty expansion refuses to export`()
// members empty: empty == true; tile() == null; write(...) == Nothing(emptyList())

@Test fun `a second export writes the same tile and identifier`()
// write twice to two roots: the tile PNG bytes and Expansion.xml's identifier are equal

@Test fun `the tile draws the seed kit with the expansion's title`()
// a tileRenderer spy records (kit, knobs, label): kit == seed.kit, label == expansion.title, knobs == model.knobs
```

- [ ] **Step 2: Run to verify they fail** — compilation fails on `ExpansionExportModel`.
- [ ] **Step 3: Implement**, including the `verifyProgram` extraction (run `ExportReadBackTest` after it: unchanged).
- [ ] **Step 4: Run `ExpansionExportTest`, `:kit:test`, `ExportWizardTest`** — green.
- [ ] **Step 5: Commit** — "Export an expansion's members as one pack with a tile, J-Cards and read-back"

---

### Task 5: The art picker serves an expansion

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/ArtPicker.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt`, `shell/src/test/kotlin/com/snipsnap/shell/PersonalityTest.kt`
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/ArtPickerTest.kt` (one new test)

**Interfaces:**
- Produces:
  ```kotlin
  @Composable internal fun ArtPickerSheet(
      kit: Kit, kitDir: File, knobs: ArtKnobs, onKnobs: (ArtKnobs) -> Unit, onDismiss: () -> Unit,
      label: String = kit.name,                                   // an expansion passes its title
      seedKits: List<Member> = emptyList(),                       // non-empty: a SEED KIT chip row
      onSeedKit: (Member) -> Unit = {},
      title: String = Copy.ART_SHEET_TITLE,
  )
  // ArtPreviews.forKit(assets, kit, kitDir, label): previews captioned with [label]; keyed by knobs per kit, so a seed change builds a new ArtPreviews
  const val ART_SEED_KIT = "SEED KIT"   // notASentence: a knob heading
  ```
  The SEED KIT row sits above PALETTE; chip text is the kit's name, tap label `"SEED KIT: <NAME>"`; the picked one is the current seed.

- [ ] **Step 1: Write the failing device test**

```kotlin
@Test fun theSeedKitRowSwapsWhichKitDrawsTheTile()
// compose ArtPickerSheet directly (setPhoneContent) with two kits as seedKits, label "MY EXPANSION";
// press("SEED KIT: SECOND"); the onSeedKit spy received the second member;
// the style cells' previews are drawn (no ART_BUSY left) with the caption MY EXPANSION (assert via a preview cache peek is not possible; assert onSeedKit and that 8 STYLE cells exist)
```

- [ ] **Step 2: Run** — compilation fails on the new parameters.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `ArtPickerTest` and `PersonalityTest`** — green; the existing picker tests unchanged.
- [ ] **Step 5: Commit** — "Let the art picker draw an expansion's tile and choose its seed kit"

---

### Task 6: Born from the shelf

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/ExpansionSheet.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt` (`KitRow`'s revealed actions gain a third door; the screen gains `expansions: ExpansionStore`, `onExpansionsChanged: () -> Unit`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (one `ExpansionStore(File(filesDir, "expansions"))` held beside the shelf, passed to KITS and EXPORT)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt`, `PersonalityTest.kt`
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/ExpansionBirthTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 2, 5; `KitRenameDialog`'s pattern for the name dialog (a new `ExpansionNameDialog(taken: (String) -> ExpansionStore.NameCheck, onCancel, onConfirm: (String) -> Unit)` in `ExpansionSheet.kt`, same text-field and REROLL layout, RENAME's place taken by `NAME IT`).
- Produces (copy, all in `Copy`):
  ```kotlin
  const val EXPANSION_DOOR = "EXPANSION ▸"                 // notASentence: the armed row's third door
  const val EXPANSION_SHEET_TITLE = "ADD TO EXPANSION"     // notASentence: a sheet title
  const val EXPANSION_NEW = "NEW"                          // notASentence: a row
  const val EXPANSION_NAME_TITLE = "NAME THE EXPANSION"    // notASentence: a dialog title
  const val EXPANSION_NAME_CONFIRM = "NAME IT"             // notASentence: a button
  const val EXPANSION_NAME_TAKEN = "ANOTHER EXPANSION HAS THAT NAME."
  const val EXPANSION_NAME_UNSAFE = "NAMES CAN'T HOLD / \\ : * ? \" < > | OR END IN A DOT OR SPACE."
  const val EXPANSION_IN = "IN"                             // notASentence: the marker on an expansion the kit is in
  fun kitJoined(kit: String, expansion: String): String = "$kit IS IN $expansion."
  fun kitLeft(kit: String, expansion: String): String = "$kit IS OUT OF $expansion."
  fun expansionBorn(expansion: String): String = "$expansion IS ON THE SHELF."
  ```
  Flow: armed row → `EXPANSION ▸` (tap label `"ADD ${kit} TO AN EXPANSION"`) → `ExpansionSheet` (BottomSheet titled `EXPANSION_SHEET_TITLE`): a `NEW` row, then each expansion (`title`, `Copy.countOf(n, "KIT", "KITS")`, `IN` when the kit is a member; tap toggles membership with `kitJoined`/`kitLeft` toasts) → `NEW` → `ExpansionNameDialog` → on confirm, `ArtPickerSheet` for the kit with `label = title`, `knobs = ArtKnobs.default(scheme)`, title `"ART FOR <TITLE>"` (built in code from `Copy.ART_FOR` = `"ART FOR"`, notASentence) → closing the picker calls `KitIds.ensure` then `ExpansionStore.create(title, id, knobs)` and toasts `expansionBorn`. Cancelling the name dialog creates nothing. All store writes run off main under `KitWrites.mutex`.

- [ ] **Step 1: Write the failing device tests** (fixture: two kits saved under a test shelf root; the screen composed with a fresh `ExpansionStore` in the test's cache dir)

```kotlin
@Test fun holdingAKitThenNewNamesAnExpansionPicksItsArtAndMakesTheKitAMember()
// arm the row (long-press semantics action); tap "ADD <KIT> TO AN EXPANSION"; press NEW; type "NIGHT SHIFT"; press NAME IT;
// the picker titled "ART FOR NIGHT SHIFT" shows; press "PALETTE: RISO"; press CLOSE;
// store.list().single(): title "NIGHT SHIFT", members == [the kit's id], knobs.palette == RISO; toast expansionBorn("NIGHT SHIFT")

@Test fun aTakenNameIsRefusedAndNothingIsBorn()
// an expansion "NIGHT SHIFT" exists; NEW → type "night shift" → NAME IT disabled and EXPANSION_NAME_TAKEN shows; CANCEL → still one expansion

@Test fun tappingAnExpansionTheKitIsInTakesItOut()
// the kit is a member; open the sheet; the row reads IN; tap it → members empty, toast kitLeft; tap again → member, toast kitJoined
```

- [ ] **Step 2: Run** — compilation fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `ExpansionBirthTest`, `PersonalityTest`, `ConventionTest`, and the existing shelf device tests (`KitsScreenBusyTest`, `KitsScreenLoadingTest`)** — green.
- [ ] **Step 5: Commit** — "Start an expansion from the shelf: name it, pick its art, the kit joins"

---

### Task 7: Expansions on the shelf

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt` (an EXPANSIONS group above the kits when any exist)
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (prune after `sweepDeletedKits` at :1173 and after `emptyKitBin`, with `ExpansionMembers.liveIds(shelf.root, shelf.binDir)`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/DeletedKitsSection.kt:155` (prune after `emptyKitBin`)
- Modify: `Personality.kt`, `PersonalityTest.kt`
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/ExpansionShelfTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  const val EXPANSIONS_HEADING = "EXPANSIONS"                       // notASentence: a group heading
  const val EXPANSION_EMPTY = "NO KITS YET. HOLD A KIT AND TAP EXPANSION TO ADD ONE."
  fun expansionDeleted(name: String): String = "$name IS GONE. ITS KITS STAY ON THE SHELF."
  ```
  A row shows the title, `countOf(present, "KIT", "KITS")` of present members (or `EXPANSION_EMPTY` when none), and the seed kit's tile at 64 dp from `AndroidKitArt.bitmap` (or an empty panel when empty). Tapping opens EXPORT with the expansion as subject (Task 8's `onExportExpansion(id)`). Holding reveals RENAME and DELETE as `KitRow` does; RENAME opens `ExpansionNameDialog` prefilled; DELETE confirms with `"DELETE THIS EXPANSION? ITS KITS STAY ON THE SHELF."` (a `Copy` constant, `EXPANSION_DELETE_CONFIRM`, notASentence: a question).

- [ ] **Step 1: Write the failing device tests**

```kotlin
@Test fun theShelfListsExpansionsWithTheirKitCount()
// two expansions (one with 2 present members, one empty): headings and "2 KITS" and EXPANSION_EMPTY read in full

@Test fun renameKeepsTheIdentifierAndDeleteKeepsTheKits()
// hold → RENAME → "LATE SHIFT" → store title changed, identifier unchanged; hold → DELETE → confirm → store empty, both kits still listed

@Test fun aBinnedKitLeavesTheCountAndComesBackOnRestore()
// shelf.deleteKit(member) → the row reads "1 KIT"; shelf.restoreKit → "2 KITS"
```

- [ ] **Step 2: Run** — compilation fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the class and the shelf device tests** — green.
- [ ] **Step 5: Commit** — "List expansions on the shelf with rename, delete and their live member count"

---

### Task 8: EXPORT's expansion subject

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/ExpansionExportPanel.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/ExportScreen.kt` (a KIT / EXPANSION switch at the top; the KIT side is today's screen, untouched)
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (`onExportExpansion(id)` routes to EXPORT with the subject set; a hoisted `ExpansionExportSession` beside `exportSession` so a write survives a tab switch)
- Modify: `Personality.kt`, `PersonalityTest.kt`
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/ExpansionExportTest.kt`

**Interfaces:**
- Consumes: Task 4's model with `tileRenderer = { k, d, knobs, label -> AndroidKitArt.png(k, d, knobs, label = label, assets) }` and `jCardRenderer = AndroidJCard.png`; Task 5's picker; the card destination and dub flow `ExportScreen` already uses for EXPANSION (`dubDestRoot`, the SAF card copy); `LandingNote.dubbed` for the landing box.
- Produces:
  ```kotlin
  const val EXPORT_SUBJECT_KIT = "KIT"              // notASentence: a switch label
  const val EXPORT_SUBJECT_EXPANSION = "EXPANSION"  // notASentence
  const val EXPANSION_PICK = "WHICH EXPANSION"      // notASentence: a row heading
  const val CHANGE_ART = "CHANGE ART ▸"             // notASentence: the last-chance door
  const val EXPANSION_FORM_FOLDER = "ON-CARD FOLDER" // notASentence
  const val EXPANSION_FORM_XPN = "ONE FILE (.XPN)"   // notASentence
  const val EXPANSION_NONE = "NO EXPANSIONS YET. HOLD A KIT ON THE SHELF TO START ONE."
  const val EXPANSION_REFUSED_EMPTY = "THIS EXPANSION HAS NO KITS. NOTHING WAS WRITTEN."
  fun memberSkipped(kit: String, reason: String): String = "$kit SKIPPED: ${reason.uppercase(Locale.ROOT)}."
  fun expansionWritten(name: String, kits: Int): String = "$name WRITTEN: ${countOf(kits, "KIT", "KITS")}."
  ```
  Panel, top to bottom: which expansion (a row that opens a list, like FORMAT's), the members (each name, or `memberSkipped` after preflight), the form (folder / `.xpn`), the tile as it will be written (`model.tile()` at 160 dp) with `CHANGE ART ▸` opening Task 5's picker for the seed kit with `label = title` and `seedKits = present members` (a pick updates `model.knobs`, and `ExpansionStore.setArt` saves it), then WRITE, which runs `model.write` off main under `KitWrites.mutex` and lands through the same card/landing path a kit's EXPANSION write uses.

- [ ] **Step 1: Write the failing device tests** (fixture: an expansion of two playable kits in a test store; a third kit that is blocked)

```kotlin
@Test fun theSwitchShowsTheExpansionSubjectWithItsTileAndMembers()
// EXPORT with subject EXPANSION: the expansion's title, both member names, and the tile image (a node with the CHANGE ART label) are present

@Test fun changeArtOpensThePickerAndThePickIsSaved()
// press CHANGE ART; press "STYLE: RIDGES"; CLOSE → store.get(id).knobs.style == RIDGES

@Test fun writingLandsOneFolderAndNamesASkippedMember()
// add the blocked kit; WRITE → toasts include memberSkipped(blocked, …) and expansionWritten(title, 2);
// Exports.dir(context)/Expansions/<title>/Expansion.xml exists

@Test fun anEmptyExpansionRefusesToWrite()
// an expansion whose only member was binned: WRITE disabled or toasts EXPANSION_REFUSED_EMPTY; nothing under Expansions/<title>
```

- [ ] **Step 2: Run** — compilation fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the class, `ExportScreenTest`, `ArtPickerTest`, `PersonalityTest`, `ConventionTest`, `FormatLocaleTest`** — green; the KIT side's tests unchanged.
- [ ] **Step 5: Commit** — "Export an expansion from EXPORT with a last look at its tile"

---

### Task 9: Whole-plan gates

- [ ] **Step 1:** `./gradlew --no-daemon :kit:test`, `:shell:test`, `:cli:test`, then `-PcreaseStudyBuild=true :app:testDebugUnitTest :app:testCreaseStudyUnitTest :app:assembleDebug :app:assembleStrikeFx :app:compileDebugAndroidTestKotlin :app:compileCreaseStudyKotlin`. Expected: green, counts from XML.
- [ ] **Step 2:** Device classes touched: `ExpansionBirthTest`, `ExpansionShelfTest`, `ExpansionExportTest`, `ArtPickerTest`, `ExportScreenTest`, `KitsScreenBusyTest`, `KitsScreenLoadingTest`, `TitleBarTest`. Expected: green.
- [ ] **Step 3:** Screenshots for the owner: the armed row with its three doors, the ADD TO EXPANSION sheet, the shelf's EXPANSIONS group, and EXPORT's expansion subject with its tile. Send them.
- [ ] **Step 4:** Docs: `docs/UI_DESIGN.md` or the shelf/export docs that describe the hold action and EXPORT (`grep -rn 'RENAME OR DELETE\|HOLD A KIT' docs`), and `Copy.SHELF_LEGEND` ("HOLD A KIT TO RENAME OR DELETE IT") becomes "HOLD A KIT TO RENAME, DELETE OR ADD IT TO AN EXPANSION" with its test updated. Commit — "Describe expansions on the phone in the docs and the shelf legend"
