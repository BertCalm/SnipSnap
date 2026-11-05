# Keygroup voice mode and full-keyboard zones: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Every exported keygroup carries a user-chosen voice mode (POLY, LEAD, GLIDE) in both MPC descriptors and the phone sidecar, plays end to end across the keyboard, and the phone's KEYS player honours the mode.

**Architecture:** A `VoiceMode` enum in `:xpm` owns the format mapping; `KeygroupProgram` carries it and gains a pure `fullKeyboard()` that stretches edge zones to 0/127. The three writers read the enum. `OneNote.writePackage` (the one packaging door) applies `fullKeyboard()` and a new `rewriteDescriptors` lets KEYS change the mode in place. `InstrumentEngine` (`:shell`, JVM-tested reference) and `InstrumentPlayer` (`:app`) choke on a mono mode.

**Tech Stack:** Kotlin, Gradle multi-module, kotlin.test, Compose (`:app`).

**Spec:** `docs/superpowers/specs/2026-10-31-keygroup-voice-mode-design.md`

**Base:** `origin/claude/mobile-mpc-drum-sampler-t58x74` at `fde661771`. Branch `claude/keygroup-voice-mode`, worktree `~/SnipSnap-voice-mode`.

## Global Constraints

- `.claude/skills/steward/SKILL.md` governs: merge commits, never rebase or force-push, plain declarative commit titles (no `feat:` prefixes), long bodies that say why. The harness adds the attribution footer; write none by hand.
- One Gradle invocation at a time on this machine (other worktrees may be building). Always `./gradlew --no-daemon`. Gate on exit code, never on grepping output.
- Module tests: `./gradlew --no-daemon :xpm:test`, `:mpc3:test`, `:kit:test`, `:shell:test`, `:synth:test`. `:app` has no host tests; prove it with `./gradlew --no-daemon :app:assembleDebug` and `:app:compileDebugAndroidTestKotlin`.
- Never skip or quarantine a test. A golden hash that changes because the output changed on purpose is refreshed per the golden test's own instructions and the commit body says why.
- No model identifiers in commit messages, PR text or code comments.
- `GLIDE` portamento time is one constant, `VoiceMode.GLIDE_PORTAMENTO = 0.1f`, referenced nowhere else as a literal.

## Review Focus

1. A sidecar whose `voiceMode` is missing or an unknown string reads as POLY; the instrument is not refused. (Task 4 test.)
2. `fullKeyboard()` on keygroups that tie at the lowest or highest note stretches every tied keygroup, and leaves a program already at 0..127 unchanged. (Task 1 tests.)
3. `rewriteDescriptors` on a name whose `_[TrackData]` folder is missing throws rather than writing a program that points at no samples. (Task 5 test.)
4. In LEAD on the phone, releasing the *old* key after the new key is down must not stop the new voice. (Task 6 test on `InstrumentEngine`.)
5. The `.xty` golden for a POLY keygroup changes on purpose (monophonic false, poliphony 0); the drum golden must not change at all. (Task 3 step.)

---

### Task 1: `VoiceMode` and `KeygroupProgram.fullKeyboard()`

**Files:**
- Create: `xpm/src/main/kotlin/com/snipsnap/xpm/VoiceMode.kt`
- Modify: `xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt:38-57` (`KeygroupProgram`)
- Test: `xpm/src/test/kotlin/com/snipsnap/xpm/VoiceModeTest.kt` (create)

**Interfaces:**
- Produces:
  ```kotlin
  enum class VoiceMode(val mono: Boolean, val legato: Boolean, val retrigger: Boolean) {
      POLY(false, false, false), LEAD(true, false, true), GLIDE(true, true, false);
      val polyphony: Int            // 1 when mono else 0
      val portamentoSeconds: Float  // GLIDE_PORTAMENTO when legato else 0f
      companion object {
          const val GLIDE_PORTAMENTO = 0.1f
          fun parse(name: String?): VoiceMode  // exact enum name, else POLY
      }
  }
  ```
  `KeygroupProgram` gains `val voiceMode: VoiceMode = VoiceMode.POLY` after `explicitNoteOn`, and
  `fun fullKeyboard(): KeygroupProgram` returning a copy where every keygroup whose `lowNote` equals the minimum `lowNote` has `lowNote = 0`, and every keygroup whose `highNote` equals the maximum `highNote` has `highNote = 127`.

- [ ] **Step 1: Write the failing tests** in `VoiceModeTest.kt`:
  - `` `mapping table` ``: POLY → polyphony 0, portamentoSeconds 0f, mono false, retrigger false; LEAD → 1, 0f, true, true, legato false; GLIDE → 1, `VoiceMode.GLIDE_PORTAMENTO`, true, legato true, retrigger false.
  - `` `parse falls back to POLY` ``: `parse("LEAD") == LEAD`, `parse("lead") == POLY`, `parse(null) == POLY`, `parse("") == POLY`.
  - `` `fullKeyboard stretches the edges only` ``: three keygroups 40..42, 43..45, 46..48 → 0..42, 43..45, 46..127; middle unchanged; `rootNote`, `layers`, `name`, `voiceMode`, `volumeRelease` carried over.
  - `` `fullKeyboard stretches every tied edge and is idempotent` ``: keygroups 40..50 and 40..60 (two share the low edge) → both get lowNote 0, the 60 gets 127; applying `fullKeyboard()` twice equals applying once; a single 0..127 keygroup is returned equal.
- [ ] **Step 2: Run** `./gradlew --no-daemon :xpm:test --tests '*VoiceModeTest*'` — expected: compilation FAIL (unresolved `VoiceMode`).
- [ ] **Step 3: Implement** `VoiceMode.kt` and the two additions to `KeygroupProgram`. `fullKeyboard()` is pure; it must not re-sort `keygroups`.
- [ ] **Step 4: Run** `./gradlew --no-daemon :xpm:test` — expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — title: `Give a keygroup program a voice mode and a way to span the keyboard`.

---

### Task 2: the `.xpm` twin writes the five program-level tags

**Files:**
- Modify: `xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt:78-101` (`write`)
- Test: `xpm/src/test/kotlin/com/snipsnap/xpm/KeygroupWriterTest.kt`

**Interfaces:**
- Consumes: `program.voiceMode` (Task 1).
- Produces: after `<ProgramName>` and before `<Instruments>`, in this order and at the same indent as `ProgramName`:
  `<Mono>`, `<Program_Polyphony>`, `<PortamentoTime>` (`.f()`, six decimals), `<PortamentoLegato>`, `<PortamentoQuantized>False</PortamentoQuantized>`. Booleans are `True` / `False`.

- [ ] **Step 1: Write the failing tests** in `KeygroupWriterTest.kt`:
  - `` `a POLY program says so in the header` ``: `write(program())` contains `<Mono>False</Mono>`, `<Program_Polyphony>0</Program_Polyphony>`, `<PortamentoTime>0.000000</PortamentoTime>`, `<PortamentoLegato>False</PortamentoLegato>`, `<PortamentoQuantized>False</PortamentoQuantized>`, and `xml.indexOf("<Mono>")` is between `indexOf("<ProgramName>")` and `indexOf("<Instruments>")`.
  - `` `LEAD and GLIDE headers` ``: `program().copy(voiceMode = LEAD)` → `<Mono>True</Mono>`, `<Program_Polyphony>1</Program_Polyphony>`, `<PortamentoLegato>False</PortamentoLegato>`; `GLIDE` → `<Mono>True</Mono>`, `<PortamentoTime>0.100000</PortamentoTime>`, `<PortamentoLegato>True</PortamentoLegato>`.
- [ ] **Step 2: Run** `./gradlew --no-daemon :xpm:test --tests '*KeygroupWriterTest*'` — expected: FAIL on the new assertions.
- [ ] **Step 3: Implement** in `KeygroupWriter.write`, mirroring `XpmWriter.appendProgramParams` lines 113-118.
- [ ] **Step 4: Run** `./gradlew --no-daemon :xpm:test` — expected: BUILD SUCCESSFUL. The corpus-shape test at the end of `KeygroupWriterTest` must still pass: all five tags exist at program level in `reference/golden/keygroup/*.xpm`.
- [ ] **Step 5: Commit** — title: `Write voice mode into the MPC 2 keygroup twin`.

---

### Task 3: the `.xty` drum block takes a `VoiceMode`

**Files:**
- Modify: `mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt:571-612` (`drumObj`, `keygroupDrumObj`, `drumBlock`)
- Test: `mpc3/src/test/kotlin/com/snipsnap/mpc3/Mpc3TrackWriterTest.kt`
- Golden: `reference/golden/snapshots/golden-keygroup.xty.sha256`

**Interfaces:**
- Consumes: `VoiceMode` (Task 1).
- Produces: `private fun drumBlock(instruments: J, voice: VoiceMode): J` writing `monophonic = voice.mono`, `poliphony = voice.polyphony`, `portamentoTime = voice.portamentoSeconds.toDouble()`, `portamentoLegato = voice.legato`, `monoRetrigger = voice.retrigger`, `portamentoQuantised = false`. `drumObj` passes `VoiceMode.POLY`; `keygroupDrumObj` passes `program.voiceMode`. Rewrite the `keygroupDrumObj` KDoc: the mono pair came from one factory bass track and is now the program's choice.

- [ ] **Step 1: Write the failing tests** in `Mpc3TrackWriterTest.kt`, using the keygroup payload parse the test at line 221 already does:
  - `` `a keygroup's voice mode sets the drum-level pair` ``: POLY → `monophonic` false, `poliphony` 0, `portamentoTime` 0.0, `portamentoLegato` false, `monoRetrigger` false; LEAD → true, 1, 0.0, false, true; GLIDE → true, 1, 0.1, true, false.
  - `` `a drum kit is always polyphonic` ``: the drum `data()` has `monophonic` false and `poliphony` 0 (pins today's behaviour).
- [ ] **Step 2: Run** `./gradlew --no-daemon :mpc3:test --tests '*Mpc3TrackWriterTest*'` — expected: FAIL (LEAD/GLIDE assertions; POLY keygroup currently true/1).
- [ ] **Step 3: Implement** the signature change and the three call sites.
- [ ] **Step 4: Run** `./gradlew --no-daemon :mpc3:test` — expected: `GoldenSnapshotTest` FAILS on `golden-keygroup.xty.sha256` only. Confirm `golden-track.xtd.sha256`, `golden-kit.xpm`, `golden-project.xpj.sha256` are untouched.
- [ ] **Step 5: Refresh the keygroup golden** the way the test's KDoc says (delete the one `.sha256` and re-run, or `UPDATE_GOLDENS`). `git status` must show only that one golden file changed.
- [ ] **Step 6: Run** `./gradlew --no-daemon :mpc3:test` — expected: BUILD SUCCESSFUL.
- [ ] **Step 7: Commit** — title: `Let the keygroup track's voice mode decide mono and polyphony`. Body explains the golden refresh: a POLY keygroup now writes `monophonic false, poliphony 0`.

---

### Task 4: the sidecar carries voice mode, and an `Instrument` can become a program again

**Files:**
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt:57-76` (`Instrument`), `:81-111` (`write`), `:123-188` (`read`)
- Test: `kit/src/test/kotlin/com/snipsnap/kit/InstrumentStoreTest.kt`

**Interfaces:**
- Consumes: `VoiceMode`, `KeygroupProgram`, `Keygroup`, `VelocityLayer(sampleName, frameCount, velStart, velEnd, loopStartFrame)` from `:xpm`.
- Produces:
  - `Instrument(name, release, zones, voiceMode: VoiceMode = VoiceMode.POLY)`.
  - `write` emits top-level `"voiceMode": "<enum name>"` from `program.voiceMode`. `VERSION` stays 2.
  - `read` sets `voiceMode = VoiceMode.parse(obj["voiceMode"]?.str())` (missing or unknown → POLY; a non-string value is a `JsonException` like any other wrong type).
  - `fun Instrument.toProgram(): KeygroupProgram` with `name`, `volumeRelease = playableRelease`, `voiceMode`, and one `Keygroup(lowNote, highNote, rootNote, layers)` per zone where each layer is `VelocityLayer(stem, frameCount, velStart, velEnd, loopStartFrame)` from `zone.playableLayers`, `stem` = the sample path's file name without `.wav`.

- [ ] **Step 1: Write the failing tests** in `InstrumentStoreTest.kt`:
  - `` `voice mode round-trips through the sidecar` ``: write a `KeygroupProgram(voiceMode = GLIDE)`; `read` gives `voiceMode == GLIDE`.
  - `` `a sidecar without a voice mode or with a stranger's value reads as POLY` ``: a v2 sidecar text with no `voiceMode` key → POLY; the same text with `"voiceMode": "WIBBLE"` → POLY, not an exception.
  - `` `an instrument becomes the program that wrote it` ``: write a two-zone, two-layer program with a loop start and release 0.35f; `read(...).toProgram()` equals the original program after `copy()` normalisation (same keygroups, layers, root, release, voice mode, name).
- [ ] **Step 2: Run** `./gradlew --no-daemon :kit:test --tests '*InstrumentStoreTest*'` — expected: compilation FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew --no-daemon :kit:test` and `./gradlew --no-daemon :shell:test --tests '*SidecarFuzzTest*'` — expected: BUILD SUCCESSFUL on both.
- [ ] **Step 5: Commit** — title: `Carry voice mode in the phone's instrument sidecar, and read a program back out of it`.

---

### Task 5: the packaging door spans the keyboard, and can rewrite descriptors in place

**Files:**
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt:261-296` (`writePackage`), add `rewriteDescriptors` beside it
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/InstrumentSuiteGenerator.kt` (apply `fullKeyboard()` before the two writer calls)
- Test: `kit/src/test/kotlin/com/snipsnap/kit/OneNoteTest.kt`
- Regenerate: `testkit/Instruments/*.xty`, `testkit/Instruments/*_[TrackData]/*.xpm`

**Interfaces:**
- Consumes: `KeygroupProgram.fullKeyboard()` (Task 1), `InstrumentStore.write` (Task 4).
- Produces:
  - `writePackage` writes `program.fullKeyboard()` to all three descriptors (the WAV sampler sheet still reads the original program's roots and loops; they are unchanged by `fullKeyboard()`).
  - `fun rewriteDescriptors(destRoot: File, name: String, program: KeygroupProgram)`: `require(dataDir.isDirectory)` via `occupies(destRoot, name)` (throw `IllegalStateException` naming the folder), then write `program.fullKeyboard()` through `Mpc3TrackWriter().writeKeygroupTo`, `KeygroupWriter().writeTo(dataDir, …)`, `InstrumentStore.write`. No WAV is read, written or deleted.

- [ ] **Step 1: Write the failing tests** in `OneNoteTest.kt`:
  - `` `a package plays end to end` ``: `writePackage` with zones 40..42 / 43..45 → the sidecar read back has zones 0..42 and 43..127; the `.xpm` twin contains `<LowNote>0</LowNote>` and `<HighNote>127</HighNote>`.
  - `` `rewriting descriptors changes the mode and leaves the samples alone` ``: after `writePackage` (POLY), record each WAV's `lastModified` and length; `rewriteDescriptors(destRoot, name, program.copy(voiceMode = LEAD))` → sidecar reads LEAD, the `.xpm` contains `<Mono>True</Mono>`, WAV stats unchanged.
  - `` `rewriting a package that is not there is refused` ``: `rewriteDescriptors` on an unused name throws `IllegalStateException`, and afterwards no `.xty` or sidecar exists for that name.
- [ ] **Step 2: Run** `./gradlew --no-daemon :kit:test --tests '*OneNoteTest*'` — expected: FAIL.
- [ ] **Step 3: Implement** both changes in `OneNote.kt` and the one-line generator change.
- [ ] **Step 4: Run** `./gradlew --no-daemon :kit:test` — expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Regenerate the suite**: `./gradlew --no-daemon :synth:generateInstrumentSuite`. Then `git status --porcelain testkit/Instruments`. Rule: only `.xty`, `.xpm` and `instruments.json` may change. If any `.wav` shows as modified (renders are deterministic per machine, not per CPU), restore them: `git checkout -- 'testkit/Instruments/*/*.wav'`.
- [ ] **Step 6: Verify the regenerated descriptors**: for every `testkit/Instruments/*_[TrackData]/*.xpm`, the minimum `<LowNote>` is 0, the maximum `<HighNote>` is 127, and `<Mono>False</Mono>` is present. Run `./gradlew --no-daemon :synth:test --tests '*InstrumentSuiteTest*' --tests '*OverlapHeldKeygroupTest*'` — expected: BUILD SUCCESSFUL (if either asserts the old ±9 edges, update the assertion to 0/127 and say so in the commit body).
- [ ] **Step 7: Commit** — title: `Span the keyboard on every exported keygroup and let a package change its voice mode in place`. Body names the testkit regeneration and the WAV rule.

---

### Task 6: the reference player chokes in a mono mode

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt:84-100` (`noteOn`)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/InstrumentEngineTest.kt`

**Interfaces:**
- Consumes: `Instrument.voiceMode` (Task 4).
- Produces: in `noteOn`, when `instrument.voiceMode.mono`, every sounding voice is released over the choke fade (the same fade the `MAX_VOICES` steal uses) before the new voice starts. POLY path unchanged.

- [ ] **Step 1: Write the failing tests** in `InstrumentEngineTest.kt`, in the style of the held-note test at line 58:
  - `` `LEAD lets one note sound at a time` ``: instrument with `voiceMode = LEAD`, note-on 60 then note-on 64 a few ms later; render; after the choke fade only one voice is active (use the engine's voice count accessor or the rendered energy of the first note's loop region: it must fall to silence within the choke fade while the second note sustains).
  - `` `releasing the old key does not stop the new one` ``: LEAD, note-on 60, note-on 64, note-off 60; the 64 voice is still sounding (render a block after; non-silent).
  - `` `POLY still stacks` ``: default instrument, note-on 60 and 64; two voices sound.
- [ ] **Step 2: Run** `./gradlew --no-daemon :shell:test --tests '*InstrumentEngineTest*'` — expected: FAIL on the LEAD tests.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew --no-daemon :shell:test` — expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — title: `A LEAD or GLIDE instrument sounds one voice at a time in the reference player`.

---

### Task 7: the phone honours the mode, and KEYS lets the user choose it

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/InstrumentPlayer.kt:95-145` (`noteOn`; add `setVoiceMode`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KeysScreen.kt:84-250` (signature unchanged; add a VOICE button in the OCT row at lines 243-245)
- Modify: `app/src/androidTest/kotlin/com/snipsnap/app/ui/OrphicaScreenTest.kt:266-267`

**Interfaces:**
- Consumes: `Instrument.toProgram()` (Task 4), `OneNote.rewriteDescriptors` (Task 5), `VoiceMode`.
- Produces:
  - `InstrumentPlayer.setVoiceMode(mode: VoiceMode)` mirrors `setReleaseSeconds`: `instrument = instrument?.copy(voiceMode = mode)`.
  - `InstrumentPlayer.noteOn`: when `inst.voiceMode.mono`, stop every voice with `PadEngine.CHOKE_FADE_MS` and clear `voices` before the steal loop. Same rule as Task 6.
  - KEYS: an `ActionButton` labelled `VOICE ${mode.name}` (POLY / LEAD / GLIDE) in the row with OCT − / OCT +. Tap cycles POLY → LEAD → GLIDE → POLY. On tap: `player.setVoiceMode(next)` at once, then on `Dispatchers.IO` `OneNote.rewriteDescriptors(sidecar.parentFile, instrument.name, instrument.toProgram().copy(voiceMode = next))`, reported through the screen's existing busy pattern (`ReportBusy`, as the loading-feedback plan prescribes for work that dies with the screen). A failure shows the screen's usual error surface and reverts the label. The button is disabled while a rewrite is in flight.
  - The status line (line 236) appends `  ${mode.name}` when the mode is not POLY.
  - `OrphicaScreenTest` lines 266-267 become `assertEquals(0, …minOf { it.lowNote })` and `assertEquals(127, …maxOf { it.highNote })`, with a comment: exported keygroups span the keyboard.

- [ ] **Step 1: Implement** the three changes. No host test exists for `:app`.
- [ ] **Step 2: Run** `./gradlew --no-daemon :app:assembleDebug` — expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Run** `./gradlew --no-daemon :app:compileDebugAndroidTestKotlin` — expected: BUILD SUCCESSFUL (the on-device suite runs in CI's `emulator-tests` job, not here; say so in the commit body).
- [ ] **Step 4: Commit** — title: `KEYS chooses an instrument's voice mode and the phone plays it that way`.

---

### Task 8: whole-suite gate and the PR

- [ ] **Step 1: Run** `./gradlew --no-daemon test -x :app:test` — expected: exit 0. The five known arm64 golden-hash failures in `:synth` (see memory `snipsnap-local-test-baseline`) are the only acceptable reds; list them by name in the PR body if they appear.
- [ ] **Step 2: Push** `claude/keygroup-voice-mode` and open a PR against `claude/mobile-mpc-drum-sampler-t58x74`. Body: the two defects, the mapping table from the spec, the golden refresh, the testkit regeneration, the `GLIDE_PORTAMENTO` hardware check the owner still owes, and the KEEL / BALLAST follow-up on their own branches.
