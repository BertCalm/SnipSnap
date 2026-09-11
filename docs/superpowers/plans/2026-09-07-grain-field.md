# GRAIN FIELD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pad's sample becomes a playable timbre landscape: grains scattered on an LCD by sonic character, played by dragging a finger — similar grains cluster, so you scrub *timbre*, not time.

**Architecture:** Offline (`:audio`, TDD): `GrainField.analyze(snip)` dices the sample into fixed 4096-frame grains (hop 2048), fingerprints each via the existing `FeatureExtractor.extract` → `Similar.vector` (normalized 9-dim), and projects to 2D with a small hand-written PCA (power iteration — none exists in the repo). Live (`:app`): `GrainVoice`, a render-thread streaming voice (TapeVoice's thread/generation idiom + AndroidAudioSink's blocking-write-as-clock; volatile (x,y) target written by the UI, read per block by the render loop) that continuously triggers Hann-windowed grains near the target. Screen (`:app`): `GrainFieldScreen`, a full-screen overlay in the KIT `when`-branch (same App-state pattern as PadSheet/PadCapture), opened by a new GRAIN button on the PAD SHEET; starfield Canvas + drag (touch = sound, lift = silence).

**Tech Stack:** Kotlin; `FeatureExtractor`/`Similar`/`Cleanup`/`WavReader` (:audio); AudioTrack streaming; Compose Canvas.

## Global Constraints

- House rules as prior plans: `eval "$(fnm env)" && fnm use 20`; 44_100; `git add` only touched files; no FOLLOWUP-REPORT.md / spike/; trailers:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MDVos8CfKQ4ewvwrcNUhrS
  ```
- `FeatureExtractor.extract` FFTs only the head 4096 samples of what it's given — which is EXACTLY one grain here (grain size 4096 by design, so the fingerprint covers the whole grain). Do not change Features.kt.
- The voice must never allocate in its render loop (preallocate block + grain envelope; grains reference the shared source FloatArray by offset, never copy per trigger).
- `:app` tasks gate on `./gradlew :app:compileDebugKotlin` + review; `:audio` task is full TDD.
- Existing `Granular`/`Similar`/`Features` behavior untouched.

---

## Task 1: `GrainField.analyze` — grains, fingerprints, 2D projection (`:audio`, TDD)

**Files:**
- Create: `audio/src/main/kotlin/com/snipsnap/audio/GrainField.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/GrainFieldTest.kt`

**Interfaces:**
- Consumes: `Cleanup.toMono`, `FeatureExtractor.extract(snip): Features`, `Similar.vector(features): FloatArray` (9-dim, normalized).
- Produces:
  ```kotlin
  object GrainField {
      const val GRAIN_FRAMES = 4_096      // ~93ms @ 44.1k — matches FeatureExtractor's head window
      const val HOP_FRAMES = 2_048
      data class Grain(val startFrame: Int, val x: Float, val y: Float)  // x,y ∈ 0..1
      data class GrainMap(val grains: List<Grain>, val grainFrames: Int = GRAIN_FRAMES)
      fun analyze(snip: Snip): GrainMap?   // null if fewer than 4 audible grains
  }
  ```

**Algorithm (locked):**
1. Fold to mono. Slide a `GRAIN_FRAMES` window at `HOP_FRAMES`; drop grains whose peak < 1e-4 (silence).
2. Fewer than 4 surviving grains → null.
3. Per grain: wrap `copyOfRange` in a `Snip` → `FeatureExtractor.extract` → `Similar.vector` → row of a grains×9 matrix. (Offline allocation is fine.)
4. PCA by hand: mean-center columns; 9×9 covariance; top-2 eigenvectors by power iteration (~50 iters, then deflate: `C' = C − λ·v·vᵀ`, iterate again); project rows onto the two eigenvectors.
5. Min-max normalize each projected axis to 0..1. Degenerate axis (max−min < 1e-9, e.g. identical grains) → spread that axis by grain index (`i/(n−1)`) so the field never collapses to a point.
6. Return grains ordered by `startFrame`.

- [ ] **Step 1: Failing tests**

```kotlin
package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GrainFieldTest {
    // First half pure 220Hz tone, second half white-ish noise (deterministic LCG) — two clearly distinct textures.
    private fun toneThenNoise(): Snip {
        val n = 88_200
        val out = FloatArray(n)
        for (i in 0 until n / 2) out[i] = (0.6 * kotlin.math.sin(2.0 * Math.PI * 220.0 * i / 44_100.0)).toFloat()
        var state = 12345L
        for (i in n / 2 until n) {
            state = state * 6364136223846793005L + 1442695040888963407L
            out[i] = ((state ushr 40).toInt() / 8_388_608f - 1f) * 0.4f
        }
        return Snip(out, 1, 44_100)
    }

    @Test
    fun `coordinates are normalized and grains ordered by time`() {
        val map = GrainField.analyze(toneThenNoise())
        assertNotNull(map)
        assertTrue(map.grains.size >= 20, "expected dozens of grains, got ${map.grains.size}")
        assertTrue(map.grains.all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(map.grains.zipWithNext().all { (a, b) -> a.startFrame < b.startFrame })
    }

    @Test
    fun `distinct textures land in distinct regions`() {
        val map = GrainField.analyze(toneThenNoise())!!
        val half = 44_100
        val tone = map.grains.filter { it.startFrame + GrainField.GRAIN_FRAMES <= half }
        val noise = map.grains.filter { it.startFrame >= half }
        assertTrue(tone.size >= 5 && noise.size >= 5)
        fun centroid(g: List<GrainField.Grain>) = Pair(g.map { it.x }.average(), g.map { it.y }.average())
        fun spread(g: List<GrainField.Grain>, c: Pair<Double, Double>) =
            g.map { kotlin.math.hypot(it.x - c.first, it.y - c.second) }.average()
        val ct = centroid(tone); val cn = centroid(noise)
        val between = kotlin.math.hypot(ct.first - cn.first, ct.second - cn.second)
        assertTrue(
            between > (spread(tone, ct) + spread(noise, cn)),
            "tone and noise clusters overlap: between=$between",
        )
    }

    @Test
    fun `silence yields null`() {
        assertNull(GrainField.analyze(Snip(FloatArray(88_200), 1, 44_100)))
    }

    @Test
    fun `identical grains still spread instead of collapsing`() {
        // Constant-amplitude steady tone: features nearly identical for every grain.
        val n = 88_200
        val out = FloatArray(n) { (0.5 * kotlin.math.sin(2.0 * Math.PI * 330.0 * it / 44_100.0)).toFloat() }
        val map = GrainField.analyze(Snip(out, 1, 44_100))
        assertNotNull(map)
        val xs = map.grains.map { it.x }
        assertTrue((xs.max() - xs.min()) > 0.5f, "degenerate axis must spread by index, got range ${xs.max() - xs.min()}")
    }
}
```

- [ ] **Step 2: Verify failure** — `./gradlew :audio:test --tests "com.snipsnap.audio.GrainFieldTest"` → FAIL (GrainField unresolved).
- [ ] **Step 3: Implement per the locked algorithm** (PCA inline in the file; ~60 lines of math; document power-iteration + deflation briefly).
- [ ] **Step 4: Green + full `:audio:test`.** Fixture rule as always: adjust fixtures (amplitudes, LCG seed, durations) if real Features/PCA behavior needs it; never hollow assertions; report changes.
- [ ] **Step 5: Commit** — `feat(audio): GrainField — grains fingerprinted and projected to a 2D timbre map`.

---

## Task 2: `GrainVoice` — the live granular voice (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/GrainVoice.kt`

**Interfaces:**
- Consumes: `GrainField.GrainMap`, the source mono `FloatArray` + sampleRate; TapeVoice's thread/generation idiom (`app/.../TapeVoice.kt:42-88` — AtomicBoolean/AtomicInteger generation guard) and AndroidAudioSink's blocking-write pacing.
- Produces:
  ```kotlin
  class GrainVoice(source: FloatArray, sampleRate: Int, map: GrainField.GrainMap) {
      fun start()                       // idempotent; spins the render thread
      fun setTarget(x: Float, y: Float) // UI thread, any rate; volatile write
      fun gate(on: Boolean)             // finger down/up — sound only while on
      fun release()                     // stop thread + AudioTrack, terminal
  }
  ```

**Design (locked):**
- Render thread loop: preallocated `FloatArray(BLOCK)` (1024). Every `TRIGGER_HOP` frames (512) while gated, trigger one grain: read volatile `(x,y)`, pick a grain by weighted choice among the K=8 nearest to the target (linear scan over the grain list is fine — hundreds of grains at ~86 triggers/sec; use a small deterministic per-voice `java.util.Random`), start it at gain `1f/MAX_OVERLAP`. Active grains (cap ~16; steal oldest) each add `source[start + i] * hann(i)` into the block — Hann precomputed once (`FloatArray(GRAIN_FRAMES)`), grains reference `source` by offset (zero per-trigger allocation).
- Write the mixed block to a `MODE_STREAM` AudioTrack with blocking writes as the clock (AndroidAudioSink's idiom). Gate-off lets active grains finish their envelopes (no click) but triggers nothing new.
- Thread teardown copies TapeVoice: a generation/AtomicBoolean so `release()` from the UI can't race the loop; `release()` joins with a timeout then `AudioTrack.release()`.
- No allocation, no locks, no logging inside the loop. Volatiles for x/y/gate are the only cross-thread channel.

- [ ] **Step 1: Read TapeVoice.kt + AndroidAudioSink.kt fully; implement per the locked design.**
- [ ] **Step 2: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 3: Commit** — `feat(app): GrainVoice — streaming granular voice with a live 2D target`.

---

## Task 3: `GrainFieldScreen` + PAD SHEET entry + App wiring (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/GrainFieldScreen.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt` (GRAIN button in the RE-TRIM/MAKE INSTRUMENT row)
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (overlay state + KIT `when`-branch)

**Interfaces:**
- Consumes: `GrainField.analyze`, `GrainVoice`, PadSheet's WAV read path (`WavReader.read(File(entry.dir, pad.sampleFile))` → `Cleanup.toMono` → `Snip`, see `PadSheetScreen.kt:182-202`), the App overlay-state pattern (padSheetSlot/padCaptureSlot), TapeScreen's `awaitEachGesture` drag idiom (`TapeScreen.kt:790-844`) extended to 2D via `change.position`.
- Produces: `GrainFieldScreen(entry, slot, onBack, onToast)` and App state `grainFieldSlot: Int?`.

**Screen behavior (locked):**
- On open: load the pad's Snip on `Dispatchers.IO` → `GrainField.analyze` on `Dispatchers.Default` → loading LCD ("LISTENING TO THE GRAIN…") meanwhile; analyze null → toast + back.
- Field: full-screen `lcdPanel` Canvas; each grain a small 2–3dp dot at (x,y) in `scheme.ink2`; dots within a radius of the touch lit in `scheme.accent`; a small cursor ring at the touch point. Redraw driven by touch position state (a drag updates a `mutableStateOf` read INSIDE the Canvas draw lambda — same draw-phase discipline as WaveformLcd; the ~hundreds of static dots may be cached in a Path/points list built once per map).
- Touch: `awaitEachGesture` — down → `voice.gate(true)` + `setTarget`; drag → `setTarget` (normalized to canvas size, clamped 0..1); up/cancel → `voice.gate(false)`.
- Header: back chip (release voice + `onBack`), pad tag (`A%02d`), plain hint line "DRAG TO PLAY THE GRAIN FIELD".
- Lifecycle: `DisposableEffect` releases the voice on dispose; MenuRow's clear-list gets `grainFieldSlot = null` (voice release rides the dispose).
- PAD SHEET: add a `GRAIN ▸` button beside RE-TRIM (same DeckButton style, disabled while busy), wired like RE-TRIM's navigation but to `grainFieldSlot = slot` via a new `onGrainField: (Int) -> Unit` param threaded through App.kt. The KIT `when` gets a `grainFieldSlot != null && sheetEntry != null` branch rendering the screen (before the PadSheet branch so it sits on top when both set — actually: opening GRAIN from PadSheet should CLOSE the sheet: set `padSheetSlot = null` when setting `grainFieldSlot`).
- V1 is play-only (no capture of the performance) — note it in the screen KDoc as the declared next phase.

- [ ] **Step 1: Implement screen + wiring per the locked behavior.**
- [ ] **Step 2: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 3: Reason through** (report): open → analyze → drag plays near-grains → lift silences → back releases; tab switch mid-play releases via dispose; sheet closes when field opens; analyze-null path toasts and returns.
- [ ] **Step 4: Commit** — `feat(app): GRAIN FIELD — drag a pad's timbre landscape`.

---

## Self-Review (done)
Grain size = FeatureExtractor head window (4096) so fingerprints cover grains exactly ✓. PCA absent from repo → written in Task 1 with degenerate-axis fallback, tested ✓. Voice allocation-free loop + TapeVoice teardown idiom ✓. Screen follows overlay-state + draw-phase disciplines ✓. Type consistency: `GrainMap`/`Grain`/`analyze` names match across tasks; `GrainVoice(source, sampleRate, map)` consumed as declared ✓. V1 scope: play-only, entry from PAD SHEET only ✓.
