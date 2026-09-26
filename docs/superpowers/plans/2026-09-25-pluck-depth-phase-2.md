# PLUCK Depth Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give PLUCK a BODY stage driven by the string's first difference with sourced per-voice resonance tables, add BANJO as the fourth string voice, remove PLUCK's KALIMBA now that TINES' kalimba won the audition, move the melodic kit's kalimba pads to TINES, and render the Phase 2 audition set.

**Architecture:** PLUCK's string output drives a bank of fixed two-pole resonators (`Modes.ring` against a 1 Hz fundamental so the table is in absolute Hz) through a first-difference high-pass, the body layer is RMS-matched to the string and added by the BODY macro; BODY 0 skips the stage and is byte-identical to the string. BANJO is a voice row plus a body table built on the MEMBRANE ratios. KALIMBA leaves `PluckVoice`, and `SynthKits.melodic()` renders its five kalimba pads through a new `tines()` helper at the same notes.

**Tech Stack:** Kotlin/JVM (`:synth`, Gradle, JDK 17, JUnit via `kotlin("test")`), one comment in `:app`, the existing `Dsp`/`Modes`/`Velocity`/`WavWriter`/`Loudness` helpers.

**Spec:** `docs/superpowers/specs/2026-09-25-pluck-depth-design.md` — "The string path", "BANJO", "Macros", "The bodies", "Phasing and gates" (row 2), "Testing", "Decisions taken at review" (5, 6) and "Phase 1 gate". The body tables come from `docs/superpowers/plans/2026-09-25-pluck-depth-body-research.md`, which is committed beside this plan; only its confirmed or corrected values reach code.

## Global Constraints

- **Naming:** no trademarked names, model numbers or near-misses on any product surface (voice names, preset names, descriptions, commit messages). `PresetTestSupport.trademarkBlocklist` enforces the letter; the reviewer enforces the spirit.
- **Sourced, not recalled:** every body resonance in code is a row of the research note marked confirmed or corrected, with its source number in a comment; a decay is "measured" only when the note says so, otherwise it is a shape for the audition to tune and the comment says that.
- **Measure, never guess:** every test threshold is a starting number the plan may tighten after the audition, never loosen without saying why in the commit. Comments state what was measured, never an unverified mechanism.
- **BODY 0 is byte-identical to the string:** the body stage is skipped entirely at zero.
- **Every engine renders at `Dsp.RATE * Dsp.OVERSAMPLE` and decimates;** the body rings at the render rate the caller passes.
- **PLUCK's presets are disposable** (36 remain after KALIMBA's twelve go, and BANJO adds twelve) and only have to keep rendering clean; BANJO's are DSP-authored like the rest.
- **Per-voice defaults for BODY are placeholders** until the audition (macro values from the spec: NYLON 0.5, KOTO 0.35, HARP 0.4, BANJO 0.6), written as a table with a comment naming the spec.
- **Pad recipes may change** (pre-launch, decision 1): a saved `PLUCK/KALIMBA` patch becomes undecodable and that is accepted.
- **Commands run from the worktree** `/Users/joshuacramblet/SnipSnap/.claude/worktrees/pluck-depth-phase-1`, branch `claude/pluck-depth-phase-2` (from the merged base `f7f6def5`). Gradle in the foreground only, `--max-workers=2`, never in the background. JDK 17 on the path. `local.properties` is present so `:app` is in the build.
- **Every commit ends with these two lines**, copied character for character:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
  ```
- **Nothing is pushed** and no PR is opened by the executor.

---

## File Structure

| File | Responsibility |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` | voice table gains BANJO and loses KALIMBA; BODY macro; `bodyFor`, `withBody` |
| `synth/src/main/kotlin/com/snipsnap/synth/PluckPresets.kt` | BANJO's twelve presets; the kalimba list removed |
| `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt` | `fixed(hz, gain, t60)` helper |
| `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt` | the KALIMBA carve-out in `brightnessOverride` goes |
| `synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt` | `tines()` helper; A07–A11 on TINES KALIMBA |
| `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt` | one comment that names PLUCK's KALIMBA |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt` | gains `lowBandEnergy` |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt` | BODY tests; PICK sweep back to every voice |
| `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt` | five-cent bound at BODY's ends |
| `synth/src/test/kotlin/com/snipsnap/synth/ExportRegressionTest.kt` | the PLUCK pad becomes BANJO |
| `synth/src/test/kotlin/com/snipsnap/synth/VelocityGrooveShuffleTest.kt` | the soften-fallback case moves to VOX |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt` | the Phase 2 clip set |
| `synth/src/test/resources/audition/pluck-audition.html` | the page's clip lists and questions move to Phase 2 |

---

### Task 1: BANJO — the fourth string voice

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (`PluckVoice`, `macrosFor`, `rootFor`, the constants `when` in `synthesize`)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/PluckPresets.kt` (`forVoice`, new `banjoPresets`)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt`, `PluckPresetsTest.kt`, `TuningAccuracyTest.kt` (existing loops over `PluckVoice.entries`)

**Interfaces:**
- Consumes: the existing voice pattern (`macrosFor`, `rootFor`, the constants `when`), `MacroSpec(name, default)`.
- Produces: `PluckVoice.BANJO` with root 196 Hz, loop cutoff 5600 Hz, exciter range 2000–10000 Hz, ring 1.0; defaults DAMP 0.5, PICK 0.7, STRIKE 0.4, DOUBLE 0.1; `PluckPresets.forVoice(PluckVoice.BANJO)` with twelve presets.

- [ ] **Step 1: Write the failing test**

Append inside `class PluckTest`:

```kotlin
    @Test
    fun `BANJO is a bright string with a short default ring`() {
        // The spec's BANJO row: root G3, brighter loop than HARP, picked
        // near the bridge, short notes. Pinned here as reach, not taste.
        assertEquals(196f, Pluck.frequencyFor(PluckVoice.BANJO, 0f))
        val banjo = FeatureExtractor.extract(Pluck.render(PluckVoice.BANJO))
        val nylon = FeatureExtractor.extract(Pluck.render(PluckVoice.NYLON))
        assertTrue(banjo.centroidHz > nylon.centroidHz, "a banjo should read brighter than a nylon string: ${banjo.centroidHz} vs ${nylon.centroidHz}")
        val strike = Pluck.defaults(PluckVoice.BANJO).getValue("STRIKE")
        assertTrue(strike < 0.5f, "the default pick sits near the bridge, got STRIKE $strike")
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --max-workers=2 --console=plain 2>&1 | tail -20`

Expected: compilation FAILS — `PluckVoice.BANJO` does not exist.

- [ ] **Step 3: Add the voice**

In `Pluck.kt`, change the enum to:

```kotlin
enum class PluckVoice { KALIMBA, NYLON, HARP, KOTO, BANJO }
```

In `macrosFor`, add before the closing brace of the `when`:

```kotlin
        // Fingerpicks close to the bridge, short notes (spec, "BANJO").
        PluckVoice.BANJO -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DAMP", 0.5f), MacroSpec("PICK", 0.7f),
            MacroSpec("STRIKE", 0.4f), MacroSpec("DOUBLE", 0.1f),
        )
```

In `rootFor`, add `PluckVoice.BANJO -> 196f` (G3, the open-G tonal centre). In the constants `when` in `synthesize`, add:

```kotlin
            // A steel string over a taut head: brighter than HARP.
            PluckVoice.BANJO -> { loopHz = 5600f; pickLo = 2000f; pickHi = 10000f; ring = 1.0f }
```

- [ ] **Step 4: Add the presets**

In `PluckPresets.kt`, add `PluckVoice.BANJO -> banjoPresets` to `forVoice`, and after `kotoPresets` add:

```kotlin
    private val banjoPresets = listOf(
        p(PluckVoice.BANJO, "OPEN G", "TUNE" to 0.5f, "DAMP" to 0.5f, "PICK" to 0.7f, "STRIKE" to 0.4f, "DOUBLE" to 0.1f),
        p(PluckVoice.BANJO, "CLAWHAMMER", "TUNE" to 0.4f, "DAMP" to 0.6f, "PICK" to 0.55f, "STRIKE" to 0.55f, "DOUBLE" to 0.05f),
        p(PluckVoice.BANJO, "BRIGHT ROLL", "TUNE" to 0.6f, "DAMP" to 0.45f, "PICK" to 0.9f, "STRIKE" to 0.3f, "DOUBLE" to 0.15f),
        p(PluckVoice.BANJO, "PLUNK", "TUNE" to 0.3f, "DAMP" to 0.75f, "PICK" to 0.5f, "STRIKE" to 0.5f, "DOUBLE" to 0f),
        p(PluckVoice.BANJO, "TENOR", "TUNE" to 0.45f, "DAMP" to 0.4f, "PICK" to 0.65f, "STRIKE" to 0.45f, "DOUBLE" to 0.1f),
        p(PluckVoice.BANJO, "MUTED HEAD", "TUNE" to 0.35f, "DAMP" to 0.85f, "PICK" to 0.4f, "STRIKE" to 0.6f, "DOUBLE" to 0f),
        p(PluckVoice.BANJO, "HIGH FIFTH", "TUNE" to 0.8f, "DAMP" to 0.5f, "PICK" to 0.8f, "STRIKE" to 0.35f, "DOUBLE" to 0.1f),
        p(PluckVoice.BANJO, "TWIN STRING", "TUNE" to 0.5f, "DAMP" to 0.45f, "PICK" to 0.7f, "STRIKE" to 0.4f, "DOUBLE" to 0.6f),
        p(PluckVoice.BANJO, "RINGING", "TUNE" to 0.55f, "DAMP" to 0.2f, "PICK" to 0.75f, "STRIKE" to 0.4f, "DOUBLE" to 0.2f),
        p(PluckVoice.BANJO, "THUMB", "TUNE" to 0.25f, "DAMP" to 0.55f, "PICK" to 0.45f, "STRIKE" to 0.7f, "DOUBLE" to 0.05f),
        p(PluckVoice.BANJO, "TINNY", "TUNE" to 0.7f, "DAMP" to 0.6f, "PICK" to 0.95f, "STRIKE" to 0.15f, "DOUBLE" to 0.1f),
        p(PluckVoice.BANJO, "SOFT PICK", "TUNE" to 0.4f, "DAMP" to 0.5f, "PICK" to 0.3f, "STRIKE" to 0.5f, "DOUBLE" to 0.1f),
    )
```

Update `PluckPresets.kt`'s KDoc line "Twelve presets per voice (four voices, forty-eight total)" to "(five voices, sixty total)"; Task 2 changes it again.

- [ ] **Step 5: Run the PLUCK suites**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.PluckPresetsTest" --tests "com.snipsnap.synth.TuningAccuracyTest" --max-workers=2 --console=plain 2>&1 | tail -25`

Expected: PASS. Every `PluckVoice.entries` loop now covers BANJO, including the five-cent tuning bound over 25 semitones and both STRIKE ends. If `factory defaults all classify as percussion` fails for BANJO, raise its default DAMP by 0.1 and re-run until it passes, and say so in the commit; if `DAMP zero rings at least twice as long as DAMP half on every voice` fails for BANJO, report the two durations and stop.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt \
        synth/src/main/kotlin/com/snipsnap/synth/PluckPresets.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt
git commit -F - <<'EOF'
Give PLUCK a BANJO voice: a steel string over a head, picked near the bridge

The fourth string, per the spec's Phase 2 row: root G3, a loop cutoff
brighter than HARP's, an exciter range up to 10 kHz, and defaults that put
the pick near the bridge with short notes. Its body table arrives with the
BODY stage; this commit is the voice row and its twelve presets.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 2: KALIMBA leaves PLUCK; the kit's kalimba pads move to TINES

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (`PluckVoice`, `macrosFor`, `rootFor`, constants `when`)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/PluckPresets.kt` (`forVoice`, delete `kalimbaPresets`, KDoc)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt:232-241` (`brightnessOverride`)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt:44-49,61-80` (`tines()` helper, A07–A11)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt:1093` (comment)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt` (`PICK moves the centroid at every step of its travel`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/ExportRegressionTest.kt:89`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/VelocityGrooveShuffleTest.kt` (`atVelocity falls back to soften for voices with no brightness macro`)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/SynthKitTest.kt` (existing), `shell/src/test/kotlin/com/snipsnap/shell/UserPresetsTest.kt` (existing)

**Interfaces:**
- Consumes: `TinesVoice.KALIMBA`, `Tines.KALIMBA_TUNE_SEMITONES` (24, root A3 = 220 Hz, the same root and span PLUCK's KALIMBA had), `TinesPatch(name, voice, macros)`, `VoxPresets.forVoice(VoxVoice.CHOIR)` (VOX's macros are TUNE, VOWEL, BREATH, DECAY — none is a brightness macro).
- Produces: `PluckVoice { NYLON, HARP, KOTO, BANJO }`; `SynthKits.melodic()` pads A07–A11 as `TinesPatch`es; `Velocity.brightnessOverride` answering `"PICK"` for every `PluckPatch`.

- [ ] **Step 1: Write the failing tests**

In `SynthKitTest.kt`, append inside the class:

```kotlin
    @Test
    fun `the melodic kit's kalimba pads are TINES notes at the same pitches`() {
        // KALIMBA moved engines (spec decision 5, Phase 1 gate: TINES won).
        // The pads keep their slots and their notes; only the engine changes.
        val kit = SynthKits.melodic()
        for (i in 6..10) {
            val recipe = PadRecipe.fromJsonValue(kit[i]!!.recipe!!)
            val patch = recipe.patch as? TinesPatch
            assertTrue(patch != null, "pad ${i + 1} should be a TINES patch, got ${recipe.patch?.engine}")
            assertEquals(TinesVoice.KALIMBA, patch!!.voice, "pad ${i + 1} voice")
        }
    }
```

(`PadRecipe.patch` is a nullable `Patch?`, hence the safe cast.)

In `PluckTest.kt`, change `PICK moves the centroid at every step of its travel` to iterate `PluckVoice.entries` again and cut its comment down to: the sweep is the precondition for routing velocity through PICK, the same sweep the snare's SNAP had to pass.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.SynthKitTest" --max-workers=2 --console=plain 2>&1 | tail -15`

Expected: the new kit test FAILS — pads 7–11 are PLUCK patches.

- [ ] **Step 3: Remove the voice from PLUCK**

In `Pluck.kt`: the enum becomes `enum class PluckVoice { NYLON, HARP, KOTO, BANJO }`; delete the `PluckVoice.KALIMBA ->` branches in `macrosFor`, `rootFor` and the constants `when`. Search the file for "KALIMBA" and rewrite any remaining comment so it no longer names a voice that does not exist (the `ks` KDoc's "175.93 samples, at KALIMBA TUNE=1/DAMP=1" becomes a note that the figure was measured on a since-removed 220 Hz voice and that BANJO at 196 Hz now has the shortest loop).

In `PluckPresets.kt`: delete `kalimbaPresets` and its `forVoice` branch; the KDoc reads "(four voices, forty-eight total)" again.

In `Velocity.kt`, the override branch becomes:

```kotlin
    // PICK is proven monotonic for every PLUCK voice by PluckTest's
    // `PICK moves the centroid at every step of its travel`.
    patch is PluckPatch -> "PICK"
```

In `SynthScreen.kt:1093`, edit the comment so it lists PLUCK's voices as `NYLON/HARP/KOTO/BANJO` (the comment only; the `PluckVoice.drumClass` mapping is unchanged).

- [ ] **Step 4: Move the kit pads**

In `SynthKits.kt`, after the `stab(...)` helper add:

```kotlin
    /** A TINES KALIMBA note: TUNE snaps from A3 over the same 24 semitones PLUCK's voices use. */
    private fun tines(name: String, semitone: Int) =
        pad(
            TinesPatch(name, TinesVoice.KALIMBA, mapOf("TUNE" to semitone / Tines.KALIMBA_TUNE_SEMITONES.toFloat())),
            DrumClass.TONAL,
            melodicMotion,
        )
```

and change A07–A11 to:

```kotlin
        // The kalimba's root sits an octave above the nylon's, so subtracting
        // an octave keeps one unbroken pitch line while the timbre climbs.
        // It is a TINES voice now: a kalimba tine is a bar, not a string.
        tines("Kalimba 1", PENTATONIC[6] - 12),  // A07 - kalimba takes over
        tines("Kalimba 2", PENTATONIC[7] - 12),  // A08
        tines("Kalimba 3", PENTATONIC[8] - 12),  // A09
        tines("Kalimba 4", PENTATONIC[9] - 12),  // A10
        tines("Kalimba 5", PENTATONIC[10] - 12), // A11
```

- [ ] **Step 5: Re-point the two tests that named the voice**

`ExportRegressionTest.kt:89` becomes:

```kotlin
        val pluck = PluckPresets.forVoice(PluckVoice.BANJO).first() // the shortest loop of the string voices: loop arithmetic changed under this
```

In `VelocityGrooveShuffleTest.kt`, the test `atVelocity falls back to soften for voices with no brightness macro` uses a VOX preset instead of PLUCK's KALIMBA: replace its comment with one saying VOX exposes TUNE, VOWEL, BREATH and DECAY, none of them in `BRIGHTNESS_MACROS` and no override names it, so it is the voice that still falls back to `soften()` — and every PLUCK voice now goes through PICK; replace `PluckPresets.forVoice(PluckVoice.KALIMBA).first()` with `VoxPresets.forVoice(VoxVoice.CHOIR).first()`; change the assertion message's "PLUCK has no brightness macro" to "VOX has no brightness macro". Leave the assertions themselves unchanged.

- [ ] **Step 6: Run the suites that touch this**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.PluckPresetsTest" --tests "com.snipsnap.synth.SynthKitTest" --tests "com.snipsnap.synth.ExportRegressionTest" --tests "com.snipsnap.synth.VelocityGrooveShuffleTest" --tests "com.snipsnap.synth.TuningAccuracyTest" --max-workers=2 --console=plain 2>&1 | tail -30`

Expected: PASS, including `the pluck side ascends and the root is the lowest note` (the TINES pads sit at the same notes) and `the melodic kit is sixteen tonal pads`. Then run `./gradlew :shell:test --tests "com.snipsnap.shell.UserPresetsTest" --max-workers=2 --console=plain 2>&1 | tail -10` — expected PASS (it reads the voice lists from the enums). Then `./gradlew :app:compileDebugKotlin --max-workers=2 --console=plain 2>&1 | tail -3` — expected BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt \
        synth/src/main/kotlin/com/snipsnap/synth/PluckPresets.kt \
        synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt \
        synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt \
        app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/SynthKitTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/ExportRegressionTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/VelocityGrooveShuffleTest.kt
git commit -F - <<'EOF'
Retire PLUCK's KALIMBA and put the melodic kit's kalimba pads on TINES

The Phase 1 gate chose TINES' kalimba over the string model: a tine is a
clamped-free bar and no body could make a Karplus-Strong loop sound like
one. The voice, its presets and the PICK carve-out in the velocity
override go; the kit's A07-A11 render through TINES at the same notes,
and the two tests that named the voice move to BANJO (the shortest loop)
and to VOX (the engine with no brightness macro).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 3: BODY — the string drives a sourced body

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt` (`fixed` helper, after `stiffString`)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (`macrosFor`, `synthesize`, new `bodyFor`, `withBody`, `rms`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt` (`lowBandEnergy`)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt`, `TuningAccuracyTest.kt`

**Interfaces:**
- Consumes: `Modes.ring(excitation, fundamentalHz, modes, rate)`, `Modes.Mode(ratio, gain, t60)`, `Pluck.trimToDecay`, the research note's "Working tables for the plan".
- Produces: `Modes.fixed(hz: Float, gain: Float, t60: Float): Mode`; `Pluck.BODY_MAX: Float = 3f` (internal); `Pluck.bodyFor(voice): List<Modes.Mode>` (internal); `Pluck.withBody(string: FloatArray, voice: PluckVoice, amount: Float, rate: Int): FloatArray` (internal); the BODY macro on every voice; `PluckSpectra.lowBandEnergy(snip, cutoffHz, seconds): Double`.

- [ ] **Step 1: Write the failing tests**

Add to `PluckSpectra`:

```kotlin
    /** The largest absolute sample in [x]. */
    fun peak(x: FloatArray): Float {
        var p = 0f
        for (v in x) if (kotlin.math.abs(v) > p) p = kotlin.math.abs(v)
        return p
    }

    /**
     * The peak of [x] low-passed at [cutoffHz] (two one-poles in series,
     * 12 dB per octave) over the first [seconds] at [rate]. For the knock
     * test: a thump is a large low-frequency swing at the onset.
     */
    fun lowPassPeak(x: FloatArray, rate: Int, cutoffHz: Float, seconds: Float): Float {
        val n = min(x.size, (seconds * rate).toInt())
        val a = (1.0 - Math.exp(-2.0 * PI * cutoffHz / rate)).toFloat()
        var lp1 = 0f
        var lp2 = 0f
        var p = 0f
        for (i in 0 until n) {
            lp1 += a * (x[i] - lp1)
            lp2 += a * (lp1 - lp2)
            if (kotlin.math.abs(lp2) > p) p = kotlin.math.abs(lp2)
        }
        return p
    }
```

Append inside `class PluckTest`:

```kotlin
    @Test
    fun `BODY is a macro on every voice with a body table`() {
        for (voice in PluckVoice.entries) {
            assertTrue(Pluck.macrosFor(voice).any { it.name == "BODY" }, "$voice has no BODY")
            val table = Pluck.bodyFor(voice)
            // Two is KOTO's count: only its 85 Hz air mode and 100 Hz plate
            // mode are in a source the research note's verifier could open.
            assertTrue(table.size >= 2, "$voice: a body needs at least two sourced modes, got ${table.size}")
            var lastHz = 0f
            for (mode in table) {
                assertTrue(mode.ratio > lastHz, "$voice: body rows must ascend, ${mode.ratio} after $lastHz")
                assertTrue(mode.ratio < 20_000f, "$voice: ${mode.ratio} Hz is not a body mode")
                assertTrue(mode.gain > 0f && mode.t60 > 0f, "$voice: ${mode.ratio} Hz has a non-positive gain or decay")
                lastHz = mode.ratio
            }
        }
    }

    @Test
    fun `BODY zero is the string, byte for byte`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val string = decayingTone(seconds = 0.5f, t60 = 0.4f, rate = rate)
        assertTrue(Pluck.withBody(string, PluckVoice.NYLON, 0f, rate) === string, "amount 0 must skip the stage and return the same buffer")
        for (voice in PluckVoice.entries) {
            val a = Pluck.render(voice, mapOf("BODY" to 0f))
            val b = Pluck.render(voice, mapOf("BODY" to 0f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: BODY 0 must be deterministic")
        }
    }

    @Test
    fun `BODY carries its share`() {
        // The body layer is RMS-matched to the string and scaled by the
        // amount, so (out - string) carries `amount` times the string's RMS.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val string = decayingTone(seconds = 0.5f, t60 = 0.4f, rate = rate)
        for (voice in PluckVoice.entries) {
            val out = Pluck.withBody(string, voice, 1f, rate)
            var body = 0.0
            var dry = 0.0
            for (i in string.indices) {
                val d = (out[i] - string[i]).toDouble()
                body += d * d
                dry += string[i].toDouble() * string[i]
            }
            val share = kotlin.math.sqrt(body / dry)
            assertTrue(share > 0.8 && share < 1.2, "$voice: body share at amount 1 should be ~1, got $share")
        }
    }

    @Test
    fun `the velocity drive knocks no more than driving the body with the string itself`() {
        // The audition's thump came from the spike driving the body with the
        // string's displacement. The bridge force follows the string's
        // velocity, so the body is driven by the first difference; this pins
        // that the velocity drive leaves no more low-frequency swing at the
        // onset than the displacement drive, and prints both ratios so the
        // report can carry the measurement to the gate.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (voice in PluckVoice.entries) {
            val string = Pluck.synthesize(voice, mapOf("BODY" to 0f), rate)
            val velocityDriven = Pluck.withBody(string, voice, 1f, rate)
            val displacementDriven = Pluck.withBody(string, voice, 1f, rate, differentiate = false)
            val onsetV = PluckSpectra.lowPassPeak(velocityDriven, rate, 200f, 0.03f) / PluckSpectra.peak(velocityDriven)
            val onsetD = PluckSpectra.lowPassPeak(displacementDriven, rate, 200f, 0.03f) / PluckSpectra.peak(displacementDriven)
            println("$voice: onset low-band ratio velocity=$onsetV displacement=$onsetD")
            assertTrue(onsetV <= onsetD * 1.01f, "$voice: the velocity drive should not knock more than the displacement drive: $onsetV vs $onsetD")
        }
    }

    @Test
    fun `BODY reaches the ugly end`() {
        // BODY 1 is three times the string's RMS - the spike's "dominant",
        // which read CLOSER on two voices and must stay reachable.
        for (voice in PluckVoice.entries) {
            val plain = FeatureExtractor.extract(Pluck.render(voice, mapOf("BODY" to 0f)))
            val full = FeatureExtractor.extract(Pluck.render(voice, mapOf("BODY" to 1f)))
            assertTrue(
                kotlin.math.abs(full.centroidHz - plain.centroidHz) > plain.centroidHz * 0.05f,
                "$voice: BODY 1 should move the centroid by more than 5%: ${plain.centroidHz} -> ${full.centroidHz}",
            )
        }
    }
```

Append inside `class TuningAccuracyTest`:

```kotlin
    @Test
    fun `BODY at either end keeps every Pluck voice within five cents`() {
        for (voice in PluckVoice.entries) {
            for (body in listOf(0f, 1f)) {
                val snip = Pluck.render(voice, mapOf("TUNE" to 0.5f, "DOUBLE" to 0f, "BODY" to body))
                val want = Pluck.frequencyFor(voice, 12)
                val measured = measuredHz(snip, want)
                val err = abs(cents(measured, want.toDouble()))
                assertTrue(err <= 5.0, "$voice at BODY $body is $err cents off (want $want, got $measured)")
            }
        }
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --max-workers=2 --console=plain 2>&1 | tail -20`

Expected: compilation FAILS — `Pluck.bodyFor`, `Pluck.withBody` and `PluckSpectra.lowBandEnergy` do not exist.

- [ ] **Step 3: Add `Modes.fixed`**

In `Modes.kt`, after `stiffString`, add:

```kotlin
    /**
     * A body resonance at an absolute frequency. A fixed body does not track
     * the note, so callers ring these against a 1 Hz "fundamental":
     * `ring(drive, 1f, listOf(fixed(98f, 1f, 0.45f)), rate)`. [hz] lands in
     * [Mode.ratio], which [ring] multiplies by that 1 Hz.
     */
    fun fixed(hz: Float, gain: Float, t60: Float): Mode = Mode(ratio = hz, gain = gain, t60 = t60)
```

- [ ] **Step 4: Add the body tables and the stage to `Pluck.kt`**

Add `MacroSpec("BODY", <default>)` between STRIKE and DOUBLE in every `macrosFor` branch with these defaults, and this comment above the function: "BODY defaults are placeholders until the audition (spec 2026-09-25, "Macros"), like LOUDNESS_OFFSET." — NYLON 0.5f, KOTO 0.35f, HARP 0.4f, BANJO 0.6f.

Add inside `object Pluck`:

```kotlin
    /** BODY 1 is three times the string's RMS - the spike's "dominant", which stays reachable (spec, "Macros"). */
    internal const val BODY_MAX = 3f

    /**
     * The fixed body of each voice, in absolute Hz. Every row is a confirmed
     * or corrected line of docs/superpowers/plans/2026-09-25-pluck-depth-body-research.md
     * (source numbers in the comments); a t60 marked "shape" there is a
     * placeholder for the audition, not a measurement.
     */
    internal fun bodyFor(voice: PluckVoice): List<Modes.Mode> = when (voice) {
        // Classical guitar - research note section 5.1 (Christensen & Vistisen
        // 1980; Jansson 2002; Su et al. 2024; Richardson via Woodhouse). The
        // 127 Hz Helmholtz antiresonance is deliberately absent: the source
        // calls it a notch in the response, not a radiating peak.
        PluckVoice.NYLON -> listOf(
            Modes.fixed(104f, 1.00f, 0.61f),   // A0 air resonance - measured, Q 29.0
            Modes.fixed(219f, 0.90f, 0.26f),   // T1 top plate - measured, Q 25.8
            Modes.fixed(286f, 0.35f, 0.15f),   // T2 dipole, quiet radiator - shape
            Modes.fixed(370f, 0.30f, 0.15f),   // higher air-cavity mode - shape
            Modes.fixed(436f, 0.25f, 0.12f),   // top-plate mode 3 - shape
            Modes.fixed(510f, 0.15f, 0.10f),   // top-plate mode 4 - shape
            Modes.fixed(645f, 0.10f, 0.08f),   // top-plate mode 5 - shape
        )
        // Koto - research note sections 2 and 5.2. Only two modes are in a
        // source the verifier could open (Coaldrake, ICA 2019: the (0,0) air
        // mode at 85 Hz and the first plate mode at 100 Hz, both confirmed by
        // the acoustic camera; the 2020 JASA paper's abstract says the same).
        // The rest of the koto catalogue is unsupported and stays out until
        // that paper can be read.
        PluckVoice.KOTO -> listOf(
            Modes.fixed(85f, 0.80f, 0.40f),    // air mode (0,0) - shape decay
            Modes.fixed(100f, 1.00f, 0.50f),   // first top-plate eigenmode - shape decay
        )
        // Concert harp - research note section 5.3 (Le Carrou, Gautier &
        // Foltete 2007, one Camac Atlantide Prestige). The 161.9 Hz pitch
        // mode is absent: the source excludes it from play.
        PluckVoice.HARP -> listOf(
            Modes.fixed(54.8f, 0.20f, 0.30f),  // global soundbox motion - shape
            Modes.fixed(80.9f, 0.15f, 0.35f),  // first bending - shape
            Modes.fixed(123.4f, 0.15f, 0.70f), // second bending - shape
            Modes.fixed(152.2f, 0.95f, 0.80f), // T1 soundboard - shape
            Modes.fixed(168.5f, 1.00f, 1.20f), // A0 soundbox air - shape
        )
        // Banjo - research note section 5.4 (Rae 2010; Politzer 2016;
        // Politzer, Woodhouse & Mansour 2021). The two bridge hills are one
        // specific bridge's; the source says other bridges put them elsewhere.
        PluckVoice.BANJO -> listOf(
            Modes.fixed(220f, 0.50f, 0.35f),   // pot air, coupled doublet - shape
            Modes.fixed(234f, 0.60f, 0.09f),   // head (0,1) - measured, bandwidth 20-30 Hz
            Modes.fixed(509f, 0.90f, 0.15f),   // head (1,1) - shape
            Modes.fixed(803f, 0.90f, 0.12f),   // head (2,1) - shape
            Modes.fixed(850f, 0.70f, 0.10f),   // pot-air cylinder mode - shape
            Modes.fixed(1593f, 0.40f, 0.08f),  // head (5,1) - shape
            Modes.fixed(2055f, 0.30f, 0.06f),  // head (7,1), the last strong head mode - shape
            Modes.fixed(3500f, 0.30f, 0.05f),  // bridge hill - shape
            Modes.fixed(5000f, 0.25f, 0.04f),  // bridge hill - shape
        )
    }

    /**
     * The string drives its body. The drive is the string's FIRST
     * DIFFERENCE, because the bridge force follows the string's slope at
     * the bridge, the velocity-like quantity - and numerically because at
     * the 4x render rate the difference passes 5 kHz at 2*sin(pi*5000/176400)
     * ~ 0.178 and 98 Hz at ~ 0.0035, 34 dB apart, which is what keeps the
     * burst out of the low body modes (the spike's knock drove them with
     * the string itself). The body layer is RMS-matched to the string so
     * [amount] means "times the string", then added. Amount 0 returns
     * [string] itself: BODY 0 is the string, byte for byte.
     */
    internal fun withBody(string: FloatArray, voice: PluckVoice, amount: Float, rate: Int, differentiate: Boolean = true): FloatArray {
        if (amount <= 0f) return string
        val table = bodyFor(voice)
        if (table.isEmpty()) return string
        // `differentiate = false` reproduces the spike's displacement drive;
        // only the knock test passes it, production never does.
        val drive = if (differentiate) {
            val d = FloatArray(string.size)
            var prev = 0f
            for (i in string.indices) {
                d[i] = string[i] - prev
                prev = string[i]
            }
            d
        } else {
            string
        }
        val wet = Modes.ring(drive, 1f, table, rate)
        val g = rms(string) / rms(wet).coerceAtLeast(1e-9f)
        val out = FloatArray(string.size)
        for (i in out.indices) out[i] = string[i] + amount * g * wet[i]
        return out
    }

    private fun rms(buf: FloatArray): Float {
        var acc = 0.0
        for (v in buf) acc += v.toDouble() * v
        return sqrt(acc / buf.size.coerceAtLeast(1)).toFloat()
    }
```

In `synthesize`, read the macro next to the others — `val body = Dsp.lin(m.getValue("BODY"), 0f, BODY_MAX)` — and change the last line from `return trimToDecay(out, rate)` to `return trimToDecay(withBody(out, voice, body, rate), rate)` (the body's tail is part of the note the trim measures).

- [ ] **Step 5: Run the PLUCK suites**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.PluckPresetsTest" --tests "com.snipsnap.synth.TuningAccuracyTest" --max-workers=2 --console=plain 2>&1 | tail -30`

Expected: PASS. If `BODY does not knock` fails for a voice, print both energies and stop: the drive is the spec's answer to the knock and a failure here is a finding for the reviewer, not a threshold to move. If `factory defaults all classify as percussion` fails for a voice at its BODY default, lower that voice's default BODY by 0.1 and re-run until it passes, and say so in the commit.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Modes.kt \
        synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt
git commit -F - <<'EOF'
Give PLUCK a BODY: each string drives a sourced fixed resonator bank through its first difference

The audition's body read CLOSER on HARP and KOTO and knocked on every voice,
and the knock was the spike driving the body with the string itself. The
body is now driven by the string's first difference - the bridge force
follows the string's slope, and the difference puts the burst's 98 Hz
content 34 dB under its 5 kHz content at the render rate - RMS-matched to
the string, and added by a BODY macro whose top is three times the string.
The tables are the research note's confirmed rows in absolute Hz; their
decays are shapes for the audition where the literature gave no Q.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 4: The Phase 2 audition set

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt`
- Modify: `synth/src/test/resources/audition/pluck-audition.html`

**Interfaces:**
- Consumes: `Pluck.render(voice, macros)` with the BODY macro, `SynthKits.melodic()` (pads 4–7 are Nylon 5, Nylon 6, Kalimba 1, Kalimba 2), `Tines.render(TinesVoice.KALIMBA, ...)`, `WavWriter`, the generator's existing `level()`.
- Produces: `./gradlew :synth:generatePluckAudition` writing 4 string voices × 6 clips + a KIT_SEAM folder of 4 clips = 28 clips plus `index.html`.

- [ ] **Step 1: Replace the generator's clip set**

In `PluckAuditionGenerator.main`, replace everything between `root.mkdirs()` / `var count = 0` and the page copy with:

```kotlin
        for (voice in PluckVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Pluck.defaults(voice)
            fun write(name: String, snip: Snip) {
                WavWriter.write(File(dir, "$name.wav"), level(snip), WavWriter.BitDepth.PCM_16)
                count++
            }
            write("p2_body_0", Pluck.render(voice, mapOf("BODY" to 0f)))
            write("p2_body_default", Pluck.render(voice))
            write("p2_body_1", Pluck.render(voice, mapOf("BODY" to 1f)))
            write("p2_body_default_strike_bridge", Pluck.render(voice, mapOf("STRIKE" to 0f)))
            write("p2_body_default_ring", Pluck.render(voice, mapOf("DAMP" to 0f)))
            write("p2_body_default_thud", Pluck.render(voice, mapOf("DAMP" to 1f)))
            println("${voice.name}: BODY default ${defaults.getValue("BODY")}")
        }

        // The kit seam: where the melodic kit hands from nylon to kalimba,
        // now across two engines. Pads 5-8 of SynthKits.melodic().
        val seam = File(root, "KIT_SEAM")
        val kit = SynthKits.melodic()
        for ((index, name) in listOf(4 to "a05_nylon_5", 5 to "a06_nylon_6", 6 to "a07_kalimba_1", 7 to "a08_kalimba_2")) {
            WavWriter.write(File(seam, "$name.wav"), level(kit[index]!!.snip), WavWriter.BitDepth.PCM_16)
            count++
        }
```

Update the object's KDoc first line to say it renders the Phase 2 set (BODY at 0, default and 1 for every string voice, STRIKE and DAMP ends at the default body, and the kit seam), and delete `KIT_NOTES` if nothing uses it any more.

- [ ] **Step 2: Point the page at the Phase 2 clips**

In `pluck-audition.html`:

(a) Titlebar tag: `PHASE 1 &middot; STRIKE, RING, KALIMBA` → `PHASE 2 &middot; BODY, BANJO, THE KIT SEAM`.

(b) Replace the guide's three `<p>` paragraphs with:

```html
    <p>Every clip sits at the same level, deliberately quiet: turn up and use headphones.</p>
    <p>Strings: BODY 0 is the string alone, DEFAULT is the placeholder amount, BODY 1 is three times the string. Mark each against the instrument. Listen for a thump at the very start: that is the knock, and it is supposed to be gone.</p>
    <p>BANJO is new. KIT SEAM is four neighbouring pads of the melodic kit, where nylon hands to the kalimba, now on TINES.</p>
```

(c) Replace `STRING_GROUPS`, `KALIMBA_GROUPS` and `VOICES` with:

```js
  var STRING_GROUPS = [
    { label: 'BODY', key: true, clips: [
      ['p2_body_0', 'BODY 0', 'the string alone'],
      ['p2_body_default', 'BODY DEFAULT', 'the placeholder amount'],
      ['p2_body_1', 'BODY 1', 'three times the string, the ugly end']
    ]},
    { label: 'AT THE DEFAULT BODY', clips: [
      ['p2_body_default_strike_bridge', 'STRIKE' + DOT + 'BRIDGE', 'pick at the bridge'],
      ['p2_body_default_ring', 'DAMP 0', 'rings out'],
      ['p2_body_default_thud', 'DAMP 1', 'the dead end']
    ]}
  ];

  var SEAM_GROUPS = [
    { label: 'NYLON TO KALIMBA', key: true, clips: [
      ['a05_nylon_5', 'A05 NYLON 5', 'PLUCK'],
      ['a06_nylon_6', 'A06 NYLON 6', 'PLUCK'],
      ['a07_kalimba_1', 'A07 KALIMBA 1', 'TINES'],
      ['a08_kalimba_2', 'A08 KALIMBA 2', 'TINES']
    ]}
  ];

  var VOICES = [
    { id: 'NYLON', display: 'NYLON', note: 'G3',  hz: '196 HZ', root: 'A2' + DOT + '110 HZ', macros: 'BODY .50 default', body: 'classical guitar', groups: STRING_GROUPS },
    { id: 'KOTO',  display: 'KOTO',  note: 'C#4', hz: '278 HZ', root: 'D3' + DOT + '147 HZ', macros: 'BODY .35 default', body: 'paulownia box', groups: STRING_GROUPS },
    { id: 'HARP',  display: 'HARP',  note: 'F4',  hz: '350 HZ', root: 'E3' + DOT + '165 HZ', macros: 'BODY .40 default', body: 'soundboard', groups: STRING_GROUPS },
    { id: 'BANJO', display: 'BANJO', note: 'G4',  hz: '392 HZ', root: 'G3' + DOT + '196 HZ', macros: 'BODY .60 default', body: 'head over a pot', groups: STRING_GROUPS },
    { id: 'KIT_SEAM', display: 'KIT SEAM', note: 'A05 TO A08', hz: 'the melodic kit', root: 'nylon to kalimba', macros: 'two engines', body: 'PLUCK then TINES', groups: SEAM_GROUPS }
  ];
```

(d) Replace the four verdict questions' markup with:

```html
    <div class="q">
      <div class="ask">1 &middot; Does the body make each string sound like its instrument?</div>
      <div class="sub">BODY DEFAULT and BODY 1 against BODY 0, per voice.</div>
      <textarea id="q_body" placeholder="&hellip;"></textarea>
    </div>
    <div class="q">
      <div class="ask">2 &middot; Is the knock gone?</div>
      <div class="sub">The thump at the very start. If it is still there, say which voices.</div>
      <textarea id="q_knock" placeholder="&hellip;"></textarea>
    </div>
    <div class="q">
      <div class="ask">3 &middot; Does BANJO read as a banjo, and does the kit seam hold?</div>
      <textarea id="q_banjo" placeholder="&hellip;"></textarea>
    </div>
    <div class="q">
      <div class="ask">Anything else</div>
      <textarea id="q_else" placeholder="&hellip;"></textarea>
    </div>
```

and in the script replace every occurrence of `['q_strike', 'q_damp', 'q_kalimba', 'q_else']` (in `paintOverall`, in `apply`, and the input-listener block) with `['q_body', 'q_knock', 'q_banjo', 'q_else']`, the state initialiser with `var state = { overall: { q_body: '', q_knock: '', q_banjo: '', q_else: '' } };`, and `snapshotOf`'s `overall` branch with the same four keys. Change the footer's `PHASE 1` to `PHASE 2`.

- [ ] **Step 3: Check and render**

Run:

```bash
LC_ALL=C grep -c -P '[^\x00-\x7F]' synth/src/test/resources/audition/pluck-audition.html
grep -c "q_strike\|q_damp\|q_kalimba\|KALIMBA_AB\|p1_" synth/src/test/resources/audition/pluck-audition.html
./gradlew :synth:generatePluckAudition --max-workers=2 --console=plain 2>&1 | tail -8 && find testkit/pluck-audition -name "*.wav" | sort
```

Expected: `0`, `0`, `wrote 28 clips + index.html`, and the listing shows 6 clips in each of NYLON, KOTO, HARP, BANJO and 4 in KIT_SEAM. Stale Phase 1 clips from an earlier run may remain in the folder; delete `testkit/pluck-audition` and re-run if the count is not 28.

- [ ] **Step 4: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt \
        synth/src/test/resources/audition/pluck-audition.html
git commit -F - <<'EOF'
Render the PLUCK Phase 2 audition set: BODY at three amounts, BANJO, and the kit seam

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 5: Whole-suite verification

**Files:** none modified.

- [ ] **Step 1: The JVM suite as CI runs it**

Run: `./gradlew --no-daemon test -x :app:test --max-workers=2 --console=plain > /tmp/pluck-phase-2-suite.log 2>&1; echo "exit=$?"; grep -E "FAILED|BUILD" /tmp/pluck-phase-2-suite.log | tail -8`

Expected: `exit=0` and `BUILD SUCCESSFUL`, no `FAILED` lines. Gate on the exit code, not the grep. If anything fails, report the test name and assertion verbatim and stop.

- [ ] **Step 2: The app compiles and the CLI renders**

Run: `./gradlew :app:compileDebugKotlin --max-workers=2 --console=plain 2>&1 | tail -3 && ./gradlew :cli:installDist --max-workers=2 --console=plain 2>&1 | tail -3 && ./cli/build/install/cli/bin/cli synth PLUCK BANJO --preset 1 --out /tmp/pluck-banjo-check && ls /tmp/pluck-banjo-check`

Expected: two BUILD SUCCESSFUL lines and one WAV listed.

- [ ] **Step 3: Report**

Print `git log --oneline f7f6def5..HEAD` and the clip count. No commit.

---

## Handoff after Task 5 (the session that owns the artifact does this)

Publish `testkit/pluck-audition/index.html` with `root = testkit/pluck-audition` and the 28 clips to `https://claude.ai/artifact/Hyiby2DKd4A2FWX6ZtC7VR`, keeping `capabilities: {db: {}}`. Then send Josh the link. His chips in `verdicts/{NYLON,KOTO,HARP,BANJO,KIT_SEAM,overall}` are Phase 2's gate; Phase 3's spec (SITAR, the jawari, sympathetic strings, dispersion) is written from them.
