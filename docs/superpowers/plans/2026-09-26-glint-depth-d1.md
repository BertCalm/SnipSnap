# GLINT Depth D1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give GLINT a real second formant under the peak, free BLOOM from its amount-speed coupling, and stop the harmonic snap from silently killing velocity response — the three repairs the Phase 1 audition demanded, on the three voices that already exist.

**Architecture:** BODY stops mixing a mean-removed copy of the window (which is the same harmonic series the burst already carries) and becomes a second windowed burst two and a half octaves down, on its own slower single envelope. BLOOM's two time constants collapse to one fixed slow value, so the macro means depth alone. The harmonic snap moves out of the render path and into PEAK's authoring, so velocity's scaling produces a continuous ratio instead of quantising both layers onto the same integer.

**Tech Stack:** Kotlin, JVM 17, Gradle 8.14.3 (`./gradlew`), `kotlin.test` with backtick test names. No new dependencies.

**Spec:** [`docs/superpowers/specs/2026-09-26-glint-depth-design.md`](../specs/2026-09-26-glint-depth-design.md)

## Global Constraints

- **Module:** `synth/src/main/kotlin/com/snipsnap/synth/`, tests in `synth/src/test/kotlin/com/snipsnap/synth/`.
- **`Dsp` is `internal object Dsp`** — always qualified: `Dsp.RATE`, `Dsp.OVERSAMPLE`, `Dsp.expMap`, `Dsp.envAt`, `Dsp.lin`.
- **Never hardcode a sample rate.** `synthesize` takes `rate: Int`; `render` passes `Dsp.RATE * Dsp.OVERSAMPLE` and decimates to `Dsp.RATE`.
- **Macro values are always 0..1 floats**, mapped to DSP ranges inside `synthesize`.
- **Six macros, unchanged names:** TUNE · PEAK · FOLLOW · BODY · BLOOM · DECAY. No seventh macro. The roadmap's 3–6 rule holds.
- **Three voices in D1:** REED, BOTTLE, KAZOO. CICADA, RATCHET and PLATE are D2 and must not appear.
- **One-shot:** `durationSeconds < 2f` at every macro corner.
- **NEVER BACKGROUND A COMMAND.** A subagent cannot receive background-task notifications and will hang. Foreground only. Run `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"` while iterating; the controller owns the full-suite regression sweep.
- **Measure, never predict.** Every threshold in this plan that is marked *measure this* must be set from a number observed during implementation and recorded in a dated comment. Phase 1 lost three fix rounds to predicted numbers that were wrong by up to 10×.
- **Never loosen an assertion to make it pass.** Report the number and stop. Every failing threshold on the Phase 1 plan turned out to be a defect in the plan, not the implementation.

### Constants after this plan

| Constant | Before | After | Why |
|---|---|---|---|
| `BODY_DECAY_RATIO` | `0.45f` | `0.8f` | the second formant rings longer than the old copy did, and is no longer double-enveloped |
| `BODY_RATIO_DIVISOR` | — | `5.6f` | the second formant sits ~2.5 octaves below the main one |
| `BODY_MIX` | — | `0.7f` | its level relative to the main burst at BODY 1 |
| `BLOOM_FAST_T60` | `0.06f` | *deleted* | the coupling |
| `BLOOM_SLOW_T60` | `0.30f` | *deleted* | the coupling |
| `BLOOM_T60` | — | `0.45f` | the sweep rate the probe tested and Josh preferred |
| `BLOOM_MAX` | `3f` | unchanged | |
| `K_MIN` / `K_MAX` / `SNAP_CEILING` | `2f` / `40f` / `12f` | unchanged | |

### Build commands

```bash
./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.GlintTest"
./gradlew --no-daemon :synth:compileTestKotlin
```

---

### Task 1: BODY becomes a second formant

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `Dsp.envAt(t, t60)`, `Dsp.expMap`, `windowAt(voice, phase)`, `ratioFor(voice, tune, peak, follow)`, `K_MIN`.
- Produces: `Glint.BODY_RATIO_DIVISOR: Float`, `Glint.BODY_MIX: Float`, `BODY_DECAY_RATIO` retuned to `0.8f`. `Glint.windowMean` is **deleted**.

**Why `windowMean` goes:** it existed only to strip DC from the unipolar window before mixing it. The replacement body term is `w(φ)·sin(2π·k₂·φ)` — a windowed sine, which carries no DC for any `k₂ ≥ 1`. The hazard is gone, so the function and its numeric-integration test go with it. **The DC assertion in the clean-render test stays** — that guards the output, not the constant.

- [ ] **Step 1: Write the failing tests**

Replace the existing `BODY puts a fundamental under the peak` and `the glass tail - the body burns off and leaves the resonance ringing` tests with these, and delete `declared window means match numeric integration`:

```kotlin
    @Test
    fun `BODY adds a second formant, not a copy of the first`() {
        // The old BODY mixed the mean-removed window back in — the same
        // harmonic series the burst already carries, spread down from k*f0.
        // Four reviewers independently found it inaudible for that reason,
        // and Josh's audition agreed: "Body 1 doesn't really seem to have an
        // impact." The replacement is a genuine second burst at k/5.6, so
        // there is energy near k2*f0 that BODY 0 does not have at all.
        // Measured 2026-09-26: <fill from your own run, see below>
        val rate = Dsp.RATE
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.5f, "PEAK" to 0.8f, "BLOOM" to 0f, "FOLLOW" to 1f, "DECAY" to 0.8f)
            val f0 = Glint.frequencyFor(voice, 0.5f)
            val k = Glint.ratioFor(voice, 0.5f, 0.8f, 1f)
            val k2 = (k / Glint.BODY_RATIO_DIVISOR).coerceAtLeast(Glint.K_MIN)
            val bare = energyAt(Glint.render(voice, still + ("BODY" to 0f)).samples, k2 * f0, rate)
            val full = energyAt(Glint.render(voice, still + ("BODY" to 1f)).samples, k2 * f0, rate)
            assertTrue(full > bare * 3f, "$voice: BODY should put real energy at k2*f0 ($bare -> $full)")
        }
    }

    @Test
    fun `the second formant outlasts nothing and rings under the peak`() {
        // BODY_DECAY_RATIO is 0.8 and the term is single-enveloped now, so
        // the second formant is still present well into the note rather than
        // gone by 0.13 s as the old double-enveloped body was.
        // Measured 2026-09-26: <fill from your own run>
        val rate = Dsp.RATE
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.5f, "PEAK" to 0.8f, "BLOOM" to 0f, "FOLLOW" to 1f, "DECAY" to 0.8f, "BODY" to 1f)
            val snip = Glint.render(voice, still)
            val f0 = Glint.frequencyFor(voice, 0.5f)
            val k2 = (Glint.ratioFor(voice, 0.5f, 0.8f, 1f) / Glint.BODY_RATIO_DIVISOR).coerceAtLeast(Glint.K_MIN)
            val half = (snip.durationSeconds * 0.5f * rate).toInt()
            val late = energyAt(snip.samples, k2 * f0, rate, from = half)
            val earlyRef = energyAt(snip.samples, k2 * f0, rate, from = 0)
            assertTrue(late > earlyRef * 0.02f, "$voice: the second formant should still be ringing at the halfway point ($earlyRef -> $late)")
        }
    }
```

`energyAt` already exists in `GlintTest.kt` at line 243, from Phase 1's formant-position test: `private fun energyAt(samples: FloatArray, hz: Float, sampleRate: Int, from: Int = 0, n: Int = 16384): Float` — a Goertzel over a named frequency, with the `from` offset these tests need. Verified present 2026-09-26; use it as-is.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — `BODY adds a second formant` fails because the current body term puts no energy at `k2·f0`, and `windowMean` is still referenced.

- [ ] **Step 3: Write the implementation**

In `Glint.kt`, replace the `BODY_DECAY_RATIO` constant and its KDoc with:

```kotlin
    /**
     * The second formant's t60 as a fraction of the amp t60. Single-
     * enveloped, unlike the body term this replaced: that one was scaled by
     * its own envelope and then again by the amp envelope, so two
     * exponentials composed and its real decay was ~0.31x t60 rather than
     * the 0.45 the constant claimed.
     */
    const val BODY_DECAY_RATIO = 0.8f

    /**
     * How far below the main formant the second one sits — k/5.6, about two
     * and a half octaves. The old BODY mixed the mean-removed window back
     * in, which is the same harmonic series the burst already carries, so it
     * was inaudible however loud it was mixed. A second burst at its own
     * ratio is a second *source*: "two formants, two speeds, is a vowel and
     * the room it sits in" (design 2026-09-26).
     */
    const val BODY_RATIO_DIVISOR = 5.6f

    /** The second formant's level relative to the main burst at BODY 1. */
    const val BODY_MIX = 0.7f
```

Delete `windowMean` entirely, with its KDoc.

Then replace `synthesize`'s body-term setup and loop line:

```kotlin
        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val bodyMix = m.getValue("BODY") * BODY_MIX
        val bodyT60 = t60 * BODY_DECAY_RATIO
        // The second formant is pinned to the *base* ratio, not the
        // bloom-modulated one: it is a separate resonance, not a shadow of
        // the first. Clamped to K_MIN so it never falls below two burst
        // cycles per window and stops being a formant at all.
        val k2 = (kBase / BODY_RATIO_DIVISOR).coerceAtLeast(K_MIN)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = windowAt(voice, phase)
            val k = (kBase * (1f + bloomAmount * Dsp.envAt(t, bloomT60))).coerceIn(K_MIN, K_MAX)
            val burst = w * sin(2.0 * PI * k * phase).toFloat()
            // A windowed sine carries no DC, so unlike the window copy this
            // replaced, nothing here needs mean-removing.
            val body = bodyMix * Dsp.envAt(t, bodyT60) * w * sin(2.0 * PI * k2 * phase).toFloat()
            out[i] = amp.at(t) * (burst + body)
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
```

- [ ] **Step 4: Run the tests and record the numbers**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: PASS.

**Print the measured energies** for both new tests and paste them into the `<fill from your own run>` placeholders, dated 2026-09-26. If either threshold fails, report the measured number and stop — do not adjust it.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Give BODY a second source instead of a second copy

The window was doing two jobs: it shaped the burst and it was the body
waveform, so mixing it back in added the same harmonic series the burst
already carried. Inaudible however loud, which is what the audition
found and what four reviewers independently diagnosed.

BODY is now a second windowed burst two and a half octaves down, on its
own single envelope. windowMean goes with the old term — a windowed
sine carries no DC, so there is nothing left to strip."
```

---

### Task 2: BLOOM loses its coupling

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: Task 1's `synthesize`.
- Produces: `Glint.BLOOM_T60: Float = 0.45f`. `BLOOM_FAST_T60` and `BLOOM_SLOW_T60` are **deleted**.

- [ ] **Step 1: Write the failing test**

Add this, and delete `BLOOM lands before the note ends, whatever it did on the way` if it references the deleted constants:

```kotlin
    @Test
    fun `BLOOM sweeps at one fixed rate, however deep it is set`() {
        // The shipped coupling ran the sweep t60 from 0.30 s down to 0.06 s
        // as BLOOM rose, so a big sweep was always a fast one and "wide and
        // unhurried" was unreachable. Measured at the shipped setting the
        // centroid fell to 0.92x and then sat flat — Josh heard that as "I
        // don't get a sense of movement". At a fixed 0.45 s it travels to
        // 0.35x over 300 ms, which was the one change he marked KEEP.
        //
        // The assertion: the *time* the sweep takes must not change with
        // depth. Measure how far through the note the centroid has settled
        // at two different BLOOM depths; the settling point must match.
        // Measured 2026-09-26: <fill from your own run>
        val voice = GlintVoice.REED
        val still = mapOf("TUNE" to 0.5f, "PEAK" to 0.5f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.85f)
        fun settledFraction(bloom: Float): Float {
            val snip = Glint.render(voice, still + ("BLOOM" to bloom))
            val head = FeatureExtractor.extract(slice(snip, 0f, 0.04f)).centroidHz
            val tail = FeatureExtractor.extract(slice(snip, snip.durationSeconds * 0.8f, snip.durationSeconds)).centroidHz
            // where the centroid first comes within 10% of its settled value
            for (i in 1..40) {
                val t = i * 0.02f
                val here = FeatureExtractor.extract(slice(snip, t, t + 0.04f)).centroidHz
                if (kotlin.math.abs(here - tail) < kotlin.math.abs(head - tail) * 0.1f) return t
            }
            return Float.NaN
        }
        val deep = settledFraction(1f)
        val shallow = settledFraction(0.5f)
        assertTrue(deep.isFinite() && shallow.isFinite(), "both depths must settle within the note: $deep, $shallow")
        assertTrue(
            kotlin.math.abs(deep - shallow) < 0.08f,
            "the sweep's duration must not depend on its depth: settled at ${deep}s vs ${shallow}s",
        )
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — with the coupling in place, BLOOM 1 settles far earlier than BLOOM 0.5.

- [ ] **Step 3: Write the implementation**

In `Glint.kt`, replace both BLOOM time constants with one:

```kotlin
    /**
     * The formant sweep's t60, fixed. It used to run from 0.30 s at low
     * BLOOM down to 0.06 s at full — depth and rate on one knob, so a big
     * sweep was always a fast one and a wide, unhurried one could not be
     * dialled at all. Measured at the shipped coupling the centroid fell to
     * 0.92x of its opening value and then sat flat for the rest of the note;
     * at 0.45 s it travels to 0.35x over 300 ms, which is the single change
     * the 2026-09-26 audition marked KEEP on both voices tested.
     *
     * BLOOM now means how far the formant travels. Each voice supplies how
     * it travels — D2's PLATE ties it to loudness and RATCHET steps it.
     */
    const val BLOOM_T60 = 0.45f
```

And in `synthesize`:

```kotlin
        val bloomAmount = Dsp.lin(m.getValue("BLOOM"), 0f, BLOOM_MAX)
        val bloomT60 = BLOOM_T60
```

Update `BLOOM_MAX`'s KDoc: it currently documents the old 3× claim and the `K_MAX` clipping crossover. Keep the clipping note — it is still true — and correct any reference to a variable rate.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: PASS. Record the two settling times in the comment.

The periodicity test runs at both BLOOM extremes and must still clear 0.98. A slower sweep changes the waveform's shape more gradually, so it should get *easier*, not harder — if it fails, something else broke.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Free BLOOM's depth from its rate

Depth and rate rode one knob, so the biggest sweep was always the
fastest and a wide unhurried one was unreachable. At the shipped
setting the centroid fell 8% and sat flat: a control that measured as
nearly static, and was heard as no sense of movement.

One fixed rate now, at the value the probe tested. BLOOM means how far
the formant travels; how it travels becomes a property of the voice."
```

---

### Task 3: The snap leaves the render path

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `Glint.ratioAtReference(peak)`, `Glint.snapRatio(k)`, `K_MIN`, `K_MAX`, `SNAP_CEILING`.
- Produces: `Glint.snapPeak(peak: Float): Float`. `ratioFor` no longer snaps.

**The defect, verified:** `Velocity.atVelocity` scales the brightness macro by `VELOCITY_FLOOR_RATIO = 0.9f / 3.2f` (0.28125) and re-renders. Because `ratioFor` snapped, both the soft and the hard value quantised onto `k = 2` below about PEAK 0.06 — **byte-identical renders, no velocity response at all.**

| PEAK | hard `k` | soft `k` | |
|---|---|---|---|
| 0.03 – 0.06 | 2 | 2 | dead |
| 0.075 and up | 3 | 2 | works |

`Velocity` operates in macro space and cannot know about the snap, so the fix belongs in `Glint`: snap where PEAK is *authored*, not where it is rendered.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `velocity always changes the render, at every PEAK`() {
        // The snap used to run inside ratioFor, so velocity's floor-scaled
        // macro quantised onto the same integer as the full one and the two
        // layers came out byte-identical below about PEAK 0.06 — a preset
        // there would have had no velocity response at all. Verified before
        // the fix: hard k=2, soft k=2 at PEAK 0.03 through 0.06.
        for (voice in GlintVoice.entries) {
            for (peak in listOf(0.0f, 0.02f, 0.04f, 0.06f, 0.1f, 0.3f, 0.6f, 1.0f)) {
                val patch = GlintPatch("Vel", voice, Glint.defaults(voice) + ("PEAK" to peak))
                val soft = Velocity.atVelocity(patch, 0.2f)
                val hard = Velocity.atVelocity(patch, 1.0f)
                assertTrue(
                    !soft.samples.contentEquals(hard.samples),
                    "$voice at PEAK $peak: soft and hard renders are identical — no velocity response",
                )
            }
        }
    }

    @Test
    fun `snapPeak lands a macro on a harmonic, and leaves the free zone alone`() {
        for (voice in GlintVoice.entries) {
            for (peak in listOf(0.05f, 0.14f, 0.31f, 0.46f)) {
                val snapped = Glint.snapPeak(peak)
                val k = Glint.ratioAtReference(snapped)
                assertEquals(Math.round(k).toFloat(), k, 1e-3f, "snapPeak($peak) should land on an integer ratio, got $k")
            }
            // Above the ceiling the ratio runs free and snapPeak is identity.
            for (peak in listOf(0.7f, 0.9f, 1.0f)) {
                assertEquals(peak, Glint.snapPeak(peak), 1e-6f, "snapPeak should not touch the continuous zone")
            }
        }
    }

    @Test
    fun `defaults and scramble both sit on a harmonic`() {
        for (voice in GlintVoice.entries) {
            val d = Glint.defaults(voice).getValue("PEAK")
            assertEquals(d, Glint.snapPeak(d), 1e-6f, "$voice's default PEAK should already be snapped")
            for (seed in 1..20) {
                val s = Glint.scramble(voice, kotlin.random.Random(seed)).getValue("PEAK")
                assertEquals(s, Glint.snapPeak(s), 1e-6f, "$voice scramble seed $seed produced an unsnapped PEAK")
            }
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — `Unresolved reference: snapPeak`, and once it exists, `velocity always changes the render` fails at PEAK 0.0–0.06.

- [ ] **Step 3: Write the implementation**

Add to `Glint.kt`, beside `snapRatio`:

```kotlin
    /**
     * [peak] moved to the nearest macro value whose ratio is an exact
     * harmonic — the snap, applied where PEAK is *authored* rather than
     * where it is rendered.
     *
     * It used to live inside [ratioFor], which meant velocity's
     * floor-scaled macro quantised onto the same integer as the full one:
     * below about PEAK 0.06 the soft and hard layers rendered byte-
     * identically and a preset there had no velocity response at all.
     * Snapping the stored value instead leaves the render path continuous,
     * so scaling it always moves the formant. Quantisation for the ear,
     * full resolution for the difference.
     *
     * Identity above [SNAP_CEILING], where the ratio runs free.
     */
    fun snapPeak(peak: Float): Float {
        val k = ratioAtReference(peak)
        if (k > SNAP_CEILING) return peak
        val target = Math.round(k).toFloat().coerceIn(K_MIN, K_MAX)
        return (ln(target / K_MIN) / ln(K_MAX / K_MIN)).coerceIn(0f, 1f)
    }
```

`ln` comes from `kotlin.math.ln` — add the import if it is not already there.

Remove the snap from `ratioFor`'s return. Its last line becomes:

```kotlin
        return (peakHz / f0).coerceIn(K_MIN, K_MAX)
```

and its KDoc gains a line saying the snap now lives in [snapPeak] and is applied when PEAK is set, not when it is rendered.

Make `defaults` snap its PEAK:

```kotlin
    fun defaults(voice: GlintVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }
            .let { it + ("PEAK" to snapPeak(it.getValue("PEAK"))) }
```

And `scramble`, on its returned map:

```kotlin
        return Dsp.scrambleNear(seed, temperature, random)
            .let { it + ("PEAK" to snapPeak(it.getValue("PEAK"))) }
```

`snapRatio` stays — the harmonic-landing test uses it, and D2's RATCHET will need it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: PASS.

**Two Phase 1 tests may need their setup adjusted, not their assertions.** `PEAK values inside one snap zone render identically` asserted that two PEAK values in one zone produce identical bytes — that is no longer true of the render path, and the test should now assert it of `snapPeak` instead. `PEAK pins the formant on the named harmonic` must pass a **snapped** PEAK so the ratio is an exact integer. Adjust the inputs; do not weaken what is asserted. Report what you changed.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Snap PEAK where it is set, not where it is rendered

Velocity scales the brightness macro by a floor ratio and re-renders.
With the snap inside ratioFor, both the scaled and the unscaled value
quantised onto k=2 below about PEAK 0.06, so the two layers came out
byte-identical and a preset there had no velocity response at all.

The snap moves to snapPeak, applied by defaults and scramble. The
render path stays continuous, so scaling the macro always moves the
formant. Quantisation for the ear, full resolution for the difference.

This one travels: the velocity system is shared with ten sibling
engines and GLINT is the first to snap a brightness macro."
```

---

### Task 4: Amend the roadmap

**Files:**
- Modify: `docs/SYNTH_ROADMAP.md`

- [ ] **Step 1: Amend the S10 row**

Find the row beginning `| S10 |` and append to its description, before the closing `|`:

```
 **D1 shipped** — BODY is a real second formant at k/5.6 on its own single envelope (the old mean-removed window copy was the same harmonic series the burst already carried, and measured inaudible); BLOOM's depth is free of its rate, fixed at a 0.45 s sweep; and PEAK's harmonic snap moved out of the render path into `snapPeak` so velocity's floor-scaled layer can no longer quantise onto the same ratio as the full one. Depth design in `docs/superpowers/specs/2026-09-26-glint-depth-design.md`; CICADA/RATCHET/PLATE are D2 and the preset roster is D3, both gated on the D1 audition.
```

- [ ] **Step 2: Verify nothing else claims S10**

Run: `grep -n '^| S10' docs/SYNTH_ROADMAP.md`
Expected: exactly one line.

- [ ] **Step 3: Commit**

```bash
git add docs/SYNTH_ROADMAP.md
git commit -m "Record GLINT's D1 depth pass on the roadmap"
```

- [ ] **Step 4: Stop for the audition**

**Do not begin D2.** The spec gates CICADA, RATCHET and PLATE on hearing D1 first — building three new mechanisms on top of a BODY nobody has listened to yet would mean auditioning six voices that share one unverified repair. Report D1 complete, and say that the audition set is the next step along with every number you had to measure.

---

## Coverage against the spec

| Spec section | Task |
|---|---|
| BODY as a second source (`k₂ ≈ k/5.6`, own single envelope, 0.7 mix) | 1 |
| The old double-envelope bug disappears | 1 (the term it lived in is gone) |
| BLOOM decoupled, `BLOOM_T60 = 0.45f` | 2 |
| BLOOM means "how far the formant travels" | 2 (KDoc); the per-voice meanings are D2 |
| The velocity bug and its fix | 3 |
| Six macros, unchanged names | all — no macro added or renamed |
| Periodicity, formant position, no-click tests keep passing | 1–3 (re-run each task) |
| Roadmap amended | 4 |
| CICADA, RATCHET, PLATE | **not in this plan** — D2 |
| The preset roster | **not in this plan** — D3 |
| TRACE | **not in this plan** — after D3 |
