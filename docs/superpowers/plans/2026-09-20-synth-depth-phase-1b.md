# Synth Depth Phase 1B — THUMP's snare on the modal spine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild `Thump.snare` as a membrane body with wires rattling freely against it, spanning continuously from a real drum to the static burst it makes today.

**Architecture:** `Modes.ring` with `Material.MEMBRANE` replaces the two detuned sines that currently stand in for a drum head. The wires stay a separate, direct noise layer — not folded into the modal excitation, which was the prototype's error — so they sizzle against the head rather than being filtered through it. `SNAP` becomes the body↔static axis with both ends genuinely reachable.

**Tech Stack:** Kotlin, `:synth`. Offline render-to-buffer, 4× oversampled then decimated.

**Spec:** `docs/superpowers/specs/2026-09-18-synth-depth-design.md` — see its "The audition gate's findings — 2026-09-20" section, which is the authority for everything below.

## Why the snare and not TINES

The spec assumed TINES and said the audition gate existed to confirm or overturn that. The gate overturned it. Played the full post-Phase-0 set, the sound that failed was the snare: *"they all have a very burst-of-static feel."*

That is literally what it is. `Thump.snare` is `(1-snap)*body + snap*rattle` where body is two detuned sines at ratio 1.83 and rattle is lowpassed white noise. Shipped presets set SNAP between 0.30 and 0.93, so the noise dominates — 80% undifferentiated hiss over a thin pure tone that cannot read as a drum. A real head is a circular membrane, and those Bessel ratios are already built and sourced as `Modes.Material.MEMBRANE`.

## Global Constraints

- **`PadRecipe.VERSION` stays 1.** See the ruling below — this reverses what the spec's Phasing table says, deliberately.
- **Determinism:** same patch → byte-identical audio. No clock, no shared mutable RNG.
- **Never hardcode 44100** — `Dsp.RATE` or a passed `rate`.
- **No invented values.** Measured, sourced, or a marked placeholder. No third category.
- **Acoustic claims need a real spectrum** (`Fft` in `:audio`), never autocorrelation — it misled this project twice, once reporting a 523.93 Hz string as 1047 Hz.
- **Any bounded parameter gets a reachability test, swept not spot-checked.** This project has now shipped three ranges narrowed to their author's taste, and a two-point test that passed a wrong implementation.
- **Extremes must be extreme, including the ugly end.** A range curated to only tasteful settings has already made the user's decisions for them, and lo-fi is a documented brand value here.
- **Naming rule (`SYNTH_ROADMAP.md:27`):** no trademarked names, model numbers, or near-misses.
- **Test command:** `./gradlew :synth:test`. Hand long runs to the controller; commit before reporting.

### Ruling: `PadRecipe.VERSION` does NOT bump, contradicting the spec's Phasing table

The spec says Phase 1 carries a 1→2 bump because new macros arrive that v1 recipes lack. Reading the code, that reasoning does not hold:

- `Patches.validateMacros` (`Patches.kt:85-92`) checks that a patch's macros are a **subset** of the voice's declared list. A v1 recipe missing `STRIKE` validates fine.
- `Thump.render` starts `defaults(voice).toMutableMap()` and overlays the patch's macros, so an absent macro already takes its default. The migration the spec wanted happens for free.
- Kits store **baked WAVs**; nothing re-renders on load, so saved work is untouched either way.
- And bumping is actively harmful: `RecipeReplay.kt:85` swallows the version throw into `null`, so a bump makes every older recipe vanish from breed and replay.

Bump when the schema becomes genuinely incompatible. Adding an optional macro is not that.

---

## File Structure

**Modify:**
- `synth/src/main/kotlin/com/snipsnap/synth/Thump.kt` — `snare()` rebuilt; `macrosFor(SNARE)` gains `STRIKE`
- `synth/src/test/kotlin/com/snipsnap/synth/ThumpTest.kt` — the snare's new tests

**Create:** nothing. `Modes` already exists and is reviewed.

**Deliberately NOT touched:** `ThumpPresets.kt`. The 16 snare presets will sound different under the new DSP and must be re-authored **by ear**, which is Task 3 and needs a person. Leaving them stale-but-valid is the honest interim state.

---

### Task 1: The membrane body with free wires

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Thump.kt` (`snare()`, and `macrosFor` for SNARE)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/ThumpTest.kt`

**Interfaces:**
- Consumes: `Modes.ring(excitation, fundamentalHz, modes, rate)`, `Modes.tableFor(Material.MEMBRANE)`, `Modes.atPosition(modes, position)`, `Dsp.Env`, `Dsp.Noise`, `Dsp.OnePole`, `Dsp.expMap`, `Dsp.lin`
- Produces: `Thump.snare` rebuilt; SNARE macros become `TUNE, SNAP, DECAY, TONE, PUNCH, STRIKE`

**The macro mapping** — five existing names keep their meaning, one is added:

| Macro | Was | Becomes |
|---|---|---|
| `TUNE` | body sine pitch 140–260 Hz | membrane fundamental — drum size |
| `DECAY` | shared t60 | head damping (scales every mode's t60) |
| `SNAP` | body/rattle crossfade | **the body↔static axis** — see Task 2 |
| `TONE` | rattle lowpass 2200–9000 Hz | wire brightness |
| `PUNCH` | transient (already wired) | unchanged |
| `STRIKE` | — | **new**: strike position on the head |

**The structure, from the gate's correction.** The wires are a *separate direct layer*, not part of the modal excitation. Folding them in was the prototype's error and it made every variant sound alike, because the wires were being filtered through the head instead of rattling against it:

```
head   = Modes.ring(impulse + short noise burst, tune, MEMBRANE struck at STRIKE, rate)
wires  = highpassed noise * its own envelope        // direct, NOT through the head
out    = body(SNAP) * head + wire(SNAP) * wires
```

- [ ] **Step 1: Write the failing test**

Add to `ThumpTest.kt`:

```kotlin
    @Test
    fun `the snare body is a membrane, not two sines`() {
        // A membrane's partials sit at the Bessel ratios. Two sines at 1.83
        // cannot produce a peak near 2.135x the fundamental; a real head does.
        val snip = Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to 0.1f, "DECAY" to 0.8f, "TUNE" to 0.4f))
        val fftSize = 4096
        val spectrum = com.snipsnap.audio.Fft.magnitudeSpectrum(snip.samples, fftSize)
        val f0 = Thump.snareFundamental(0.4f)
        fun energyNear(hz: Float): Float {
            val lo = hz * 0.94f; val hi = hz * 1.06f
            return spectrum.indices.filter {
                com.snipsnap.audio.Fft.binToHz(it, fftSize, Dsp.RATE) in lo..hi
            }.maxOfOrNull { spectrum[it] } ?: 0f
        }
        // (2,1) at 2.1354 is the mode two sines at 1.83 cannot fake.
        assertTrue(
            energyNear(f0 * 2.1354f) > energyNear(f0 * 1.83f) * 0.5f,
            "expected real membrane structure, not a 1.83 sine pair",
        )
    }

    @Test
    fun `STRIKE changes the snare's spectrum across its whole travel`() {
        // Swept, not spot-checked: a two-point test on this project once passed
        // a wrong implementation. Hitting nearer the rim wakes higher modes.
        val centroids = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { p ->
            val snip = Thump.render(ThumpVoice.SNARE, mapOf("STRIKE" to p, "SNAP" to 0.15f))
            com.snipsnap.audio.FeatureExtractor.extract(snip).centroidHz
        }
        for (i in 0 until centroids.size - 1) {
            assertTrue(
                kotlin.math.abs(centroids[i] - centroids[i + 1]) > 1f,
                "STRIKE did nothing between step $i and ${i + 1}: $centroids",
            )
        }
    }

    @Test
    fun `the snare still renders clean audio at every macro corner`() {
        val names = Thump.macrosFor(ThumpVoice.SNARE).map { it.name }
        for (corner in listOf(0f, 1f)) {
            val snip = Thump.render(ThumpVoice.SNARE, names.associateWith { corner })
            assertTrue(snip.samples.all { it.isFinite() }, "NaN/Inf at all-$corner")
            assertTrue(snip.samples.any { kotlin.math.abs(it) > 0.1f }, "silence at all-$corner")
        }
    }
```

**API note, already checked for you:** `Fft.magnitudeSpectrum(samples, size = 4096)`
takes an FFT SIZE (not a sample rate), and `Fft.binToHz(bin, fftSize, sampleRate)`
converts. `FeatureExtractor.extract(snip).centroidHz` is real.

Add `internal fun snareFundamental(tune: Float): Float` to `Thump` so the test asserts against the engine's intent rather than re-deriving the mapping.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*ThumpTest*'`
Expected: FAIL — the membrane test fails against the current two-sine body.

- [ ] **Step 3: Rebuild `snare()`**

```kotlin
    /**
     * SNARE — a membrane with wires resting on it.
     *
     * It used to be two detuned sines plus lowpassed noise, crossfaded, and
     * the audition gate's verdict on that was "a very burst-of-static feel."
     * Fair: at the shipped presets' SNAP values the noise ran 80% of the mix,
     * over a body that two sines at ratio 1.83 could never make read as a
     * drum. A real head is a circular membrane — the Bessel ratios in
     * [Modes.Material.MEMBRANE].
     *
     * The wires stay their own layer rather than joining the modal
     * excitation. A prototype that fed them through the head lost exactly
     * what makes a snare a snare: the wires rattle AGAINST the drum, they are
     * not filtered BY it.
     */
    private fun snare(m: Map<String, Float>, rate: Int): FloatArray {
        val tune = snareFundamental(m.getValue("TUNE"))
        val snap = m.getValue("SNAP")
        val damp = Dsp.expMap(m.getValue("DECAY"), 0.05f, 0.34f)
        val air = m.getValue("TONE")
        val strike = m.getValue("STRIKE")

        val frames = frames(0.6f, rate)

        // The stick, plus a breath of noise so the head is struck rather than
        // plucked. Short: this excites the membrane, it is not the wires.
        val exc = FloatArray(frames)
        exc[0] = 1f
        val stickNoise = Dsp.Noise(3)
        val stickEnv = Dsp.Env(attackSeconds = 0.0005f, decay2T60 = 0.02f)
        for (i in exc.indices) exc[i] += 0.35f * stickNoise.next() * stickEnv.at(i.toFloat() / rate)

        val head = Modes.atPosition(Modes.tableFor(Modes.Material.MEMBRANE), strike)
            .map { it.copy(t60 = it.t60 * damp) }
        val body = Modes.ring(exc, tune, head, rate)

        // The wires: broadband, highpassed into sizzle, decaying on their own
        // clock. Not routed through the head - see the KDoc above.
        val out = FloatArray(frames)
        val wireNoise = Dsp.Noise(11)
        val dull = Dsp.OnePole(rate)
        val wireEnv = Dsp.Env(attackSeconds = 0.0008f, decay2T60 = damp * 3f)
        val bodyGain = snareBodyGain(snap)
        val wireGain = snareWireGain(snap)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val raw = wireNoise.next()
            val sizzle = raw - dull.lp(raw, Dsp.expMap(air, 900f, 5000f))
            out[i] = bodyGain * body[i] + wireGain * sizzle * wireEnv.at(t) * 1.8f
        }
        return out
    }

    /** The head's fundamental: drum size, from piccolo to a deep 14-inch. */
    internal fun snareFundamental(tune: Float): Float = Dsp.expMap(tune, 120f, 330f)
```

Add `MacroSpec("STRIKE", 0.28f)` to `macrosFor(ThumpVoice.SNARE)`. **0.28 is a placeholder** — off-centre, where a snare is actually played — and belongs at the next audition, so mark it as such in a comment.

`snareBodyGain` / `snareWireGain` are Task 2's; for this task make them `1f - snap` and `snap` so the build runs, and say in the report that Task 2 replaces them.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :synth:test --tests '*ThumpTest*'`
Expected: the three new tests PASS. **Other snare tests will fail** — the sound genuinely changed. For each: confirm the failure is the audio legitimately changing, not a bug, with spectral or numerical evidence, then update it. Never loosen a tolerance to get green without that evidence.

- [ ] **Step 5: Hear it**

```bash
./gradlew :cli:installDist
cli/build/install/cli/bin/cli synth THUMP SNARE --all --out /tmp/snare-1b
```
The presets are still the old values, so these will sound wrong in places — that is expected and it is Task 3's job. What you are checking is that it sounds like **a drum being hit**.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Thump.kt \
        synth/src/test/kotlin/com/snipsnap/synth/ThumpTest.kt
git commit -m "Give the snare a membrane body and wires that rattle against it"
```

---

### Task 2: SNAP spans drum to static, both ends reachable

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Thump.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/ThumpTest.kt`

**Interfaces:**
- Produces: `Thump.snareBodyGain(snap: Float): Float`, `Thump.snareWireGain(snap: Float): Float`

**The requirement, verbatim from the gate:** *"would want to be able to get close to the static burst with settings as well."* The old sound is not a defect to delete — it is a sound in the palette, and lo-fi is on-brand here. So SNAP must travel from a real drum at 0 to a genuine static burst at 1.

A linear `(1-snap)` / `snap` crossfade does **not** achieve this: at SNAP 1 the body is gone but the wires are merely at unity, which is not the saturated hiss the old engine made. The curve needs the body to fall away faster than the wires rise, and the wires to keep climbing after the body is silent.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `SNAP spans a real drum to a static burst`() {
        // Both ends must be REACHABLE - the static burst is a palette sound,
        // not a defect, and a range curated to only tasteful settings has
        // already made the user's decisions for them.
        // Spectral FLATNESS is the tonal-vs-noise measure :audio actually
        // exposes: a flat spectrum is noise, a peaky one is pitched. So the
        // drum end must be LOW and the static end HIGH - note the direction.
        fun flatnessAt(snap: Float): Float {
            val snip = Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to snap, "DECAY" to 0.7f))
            return com.snipsnap.audio.FeatureExtractor.extract(snip).flatness
        }
        val drum = flatnessAt(0f)
        val static = flatnessAt(1f)
        assertTrue(static > drum * 2f, "SNAP=1 should be clearly noisier: drum=$drum static=$static")
    }

    @Test
    fun `SNAP moves at every step of its travel`() {
        // Swept. A crossfade that saturates early leaves half the knob dead,
        // which is this project's most-repeated defect.
        val points = (0..8).map { it / 8f }
        val measured = points.map { s ->
            val snip = Thump.render(ThumpVoice.SNARE, mapOf("SNAP" to s, "DECAY" to 0.7f))
            com.snipsnap.audio.FeatureExtractor.extract(snip).centroidHz
        }
        for (i in 0 until measured.size - 1) {
            assertTrue(
                kotlin.math.abs(measured[i] - measured[i + 1]) > 1f,
                "SNAP is dead between ${points[i]} and ${points[i + 1]}: $measured",
            )
        }
    }
```

**API note, already checked for you:** `FeatureExtractor.extract(snip)` returns
`Features` with `centroidHz, rolloffHz, flatness, zeroCrossingRate, lowRatio,
midRatio, highRatio, durationSeconds, decayMs, peak`. There is **no** `tonality`
field — `flatness` is the tonal-vs-noise measure, and it runs the opposite way
(high = noisy). `Fft.magnitudeSpectrum(samples, size = 4096)` takes an FFT SIZE,
not a sample rate, and `Fft.binToHz(bin, fftSize, sampleRate)` does the
conversion.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*ThumpTest*'`
Expected: FAIL on the span test with a linear crossfade.

- [ ] **Step 3: Shape the curve**

```kotlin
    /**
     * SNAP's two halves. The body falls away faster than the wires rise, and
     * the wires keep climbing after the head has gone quiet — so SNAP 1 is a
     * genuine static burst rather than a drum with the volume down.
     *
     * The audition gate asked for this explicitly: the old engine's
     * burst-of-static IS a sound worth keeping, it just should not be the
     * ONLY sound. Both ends of this knob are meant to be useful.
     */
    internal fun snareBodyGain(snap: Float): Float {
        val s = snap.coerceIn(0f, 1f)
        return (1f - s) * (1f - s)          // quadratic: head clears early
    }

    internal fun snareWireGain(snap: Float): Float {
        val s = snap.coerceIn(0f, 1f)
        return s * (0.6f + 0.9f * s)        // keeps rising past the body's exit
    }
```

Those coefficients are **measured, not guessed**: sweep SNAP, measure tonality and centroid at each step, and tune until both tests pass with margin. Put the measured table in your report.

- [ ] **Step 4: Run, then hear the span**

```bash
cli/build/install/cli/bin/cli synth THUMP SNARE --preset 1 --out /tmp/snap-span
```
Render SNAP at 0, 0.25, 0.5, 0.75, 1 and confirm by ear that 0 is a drum and 1 is a static burst.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Thump.kt \
        synth/src/test/kotlin/com/snipsnap/synth/ThumpTest.kt
git commit -m "Curve SNAP so both ends of it are worth reaching"
```

---

### Task 3: Re-author the snare presets — WITH A PERSON

**This task cannot be done by an agent and must not be attempted by one.**

The 16 SNARE presets were authored blind against the old two-sine engine. Under the new body they are stale: their SNAP values assume a crossfade that no longer exists, none of them set `STRIKE`, and **`PUNCH` is set by exactly one preset out of 128 in the whole THUMP library** despite being fully wired (`Thump.kt:115,132`) — which is most of why the drums read as samey even before the body problem.

Re-authoring them is a listening session. What an agent can usefully prepare:

- [ ] **Step 1: Render the exploration grid**

A grid over the macros that actually separate snares — `TUNE` × `DECAY` × `SNAP` × `STRIKE` × `PUNCH` — at enough points to hear the space, written to a folder with self-describing filenames.

- [ ] **Step 2: Sit with the person and pick**

They choose; the agent records the macro values. No agent invents a preset value in this task.

- [ ] **Step 3: Write the chosen presets, delete the spread assumptions**

The old `presets within a voice do not cluster` tests are already gone (Phase 0, Task 10). Good presets cluster. Do not reintroduce a distribution rule.

- [ ] **Step 4: Check whether the other seven voices have the same PUNCH gap**

They almost certainly do — 1 of 128. That is a separate authoring pass, same method.

---

## Audition Gate

Phase 1C — rolling the spine across the remaining engines — does not start until the rebuilt snare has been heard and Task 3's presets exist. The gate's last outing overturned the flagship choice and corrected the architecture, so it is not ceremony.

---

## Self-Review

**Spec coverage.** The gate's finding 1 (flagship is the snare) is the whole plan. Finding 2 (membrane *and* free wires) is Task 1's structure, with the prototype's error called out in the KDoc so it is not repeated. Finding 3 (static burst stays reachable) is Task 2. Finding 4 (extremes must be extreme) is in Global Constraints and enforced by both swept tests. The `PUNCH` gap is Task 3, correctly scoped as human work.

**Placeholders.** `STRIKE`'s default 0.28 is marked as one. Task 2's curve coefficients are explicitly to be measured and reported, not shipped as written. No other new numbers.

**Type consistency.** `snareFundamental(tune)`, `snareBodyGain(snap)`, `snareWireGain(snap)` are introduced in Tasks 1–2 and used by both the engine and its tests. Task 1 ships provisional gain functions and Task 2 replaces them — stated in both places.

**Known risk.** Task 1 will break existing snare tests because the sound genuinely changes. The instruction is evidence-before-edit, which this project has enforced four times; the risk is an implementer quietly loosening a tolerance instead.
