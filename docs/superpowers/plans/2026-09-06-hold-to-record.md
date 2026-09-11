# Hold-to-Record (Capture-to-Pad Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the second capture gesture to the pad-capture surface: press-and-HOLD to record while held, release to place the clip on the pad.

**Architecture:** No new recording path — the armed mic session already records continuously into the 60s ring. HOLD stamps `SystemClock.elapsedRealtime()` on press; on release, elapsed ms → frames → `MicSessionService.snapshotTail(frames)` slices exactly the held window off the ring. A new pure DSP function `PadCapture.holdClip` cleans that slice (no transient hunt — the fingers defined the boundaries), and the existing serialized assign path (`captureWriteMutex` → `KitBuilderModel.open/assign/save`) lands it on the slot.

**Tech Stack:** Kotlin, Jetpack Compose, existing `:audio` (JVM-tested) + `:app` modules.

## Global Constraints

- Same as Phase 1 (plan `2026-09-04-capture-to-pad.md`): `eval "$(fnm env)" && fnm use 20` first; sample rate 44_100 everywhere; `Snip(samples, 1, 44_100)`; `:app` has NO unit tests (compile gate); `git add` only touched files, never `-A`; trailers on every commit:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MDVos8CfKQ4ewvwrcNUhrS
  ```
- Do not regress GRAB. The `captureWriteMutex` must serialize HOLD's write exactly like GRAB's.
- Copy stays plain: `HOLD TO REC`, `RECORDING…`.

---

## Task 1: `PadCapture.holdClip` — clean a hold-defined slice

**Files:**
- Modify: `audio/src/main/kotlin/com/snipsnap/audio/PadCapture.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/PadCaptureTest.kt` (append)

**Interfaces:**
- Consumes: `Snip`, `Cleanup.process(snip, config)` with `CleanupConfig` defaults (DC → trim silence → normalize → fades), existing `MIN_ONESHOT_FRAMES`.
- Produces:
  ```kotlin
  const val MAX_HOLD_FRAMES = 30 * 44_100          // 30s cap — half the ring, far beyond any one-shot
  fun holdClip(raw: FloatArray, sampleRate: Int): Snip?
  ```
  Contract: cleans the WHOLE buffer (leading-silence trim is fine and wanted — the finger lands before the sound starts; but NO onset hunt, NO cutting to the last hit). Null if the cleaned result is shorter than `MIN_ONESHOT_FRAMES` (held in silence).

- [ ] **Step 1: Append failing tests**

```kotlin
@Test
fun `holdClip keeps the whole held window, not just the last hit`() {
    // Two hits inside one held window — both must survive (unlike grabOneShot).
    val total = 88_200                       // 2s hold
    val raw = FloatArray(total)
    for ((at) in listOf(intArrayOf(11_025), intArrayOf(55_125))) {
        for (i in 0 until 4_410) {
            val idx = at + i; if (idx >= total) break
            val w = 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / 4_410).toFloat())
            raw[idx] = 0.8f * w * kotlin.math.sin(2.0 * Math.PI * 180.0 * i / 44_100.0).toFloat()
        }
    }
    val snip = PadCapture.holdClip(raw, 44_100)
    assertNotNull(snip)
    // Both hits span ~0.25s..1.35s of audio — the clip must be long enough to contain both,
    // i.e. much longer than one hit (grabOneShot on this input would be far shorter).
    assertTrue(snip.frameCount > 44_100, "held clip must keep both hits, got ${snip.frameCount} frames")
    assertTrue(snip.peak() > 0.9f)
}

@Test
fun `holdClip on silence yields null`() {
    assertNull(PadCapture.holdClip(FloatArray(88_200), 44_100))
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :audio:test --tests "com.snipsnap.audio.PadCaptureTest"` → FAIL (holdClip unresolved).

- [ ] **Step 3: Implement**

```kotlin
/** Frames cap for a single HOLD — far beyond any one-shot, half the ring. */
const val MAX_HOLD_FRAMES = 30 * 44_100

/**
 * Cleans a hold-defined slice: the user's press/release chose the window, so
 * keep ALL of it (no onset hunt) — just the commit-time chain (DC → trim
 * silence → normalize → fades). Null if held in silence.
 */
fun holdClip(raw: FloatArray, sampleRate: Int): Snip? {
    if (raw.isEmpty()) return null
    val cleaned = Cleanup.process(Snip(raw, channels = 1, sampleRate = sampleRate))
    return if (cleaned.frameCount < MIN_ONESHOT_FRAMES) null else cleaned
}
```

- [ ] **Step 4: Verify pass + no regressions** — targeted test, then full `:audio:test`.

- [ ] **Step 5: Commit** — `git add` the two files; message: `feat(audio): holdClip — clean a hold-defined capture window`.

---

## Task 2: HOLD TO REC on the pad-capture surface

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PadCaptureScreen.kt`

**Interfaces:**
- Consumes: `MicSessionService.snapshotTail(frames)`, `MicSessionService.SAMPLE_RATE`, `PadCapture.holdClip`, `PadCapture.MAX_HOLD_FRAMES`, `Classifier.classify`, `captureWriteMutex`, the existing `grab()`'s assign/toast/back plumbing and `grabbing` guard.
- Produces: a HOLD TO REC control under GRAB.

- [ ] **Step 1: Refactor the shared commit path**

Extract the tail of `grab()` (classify → `captureWriteMutex.withLock { open/assign/save }` → null-toast vs `onKitUpdated`/toast/`onBack`, all inside the existing try/catch/finally + `grabbing` guard) into a private `commitToPad(snipProducer: () -> Snip?)`-style helper (exact shape implementer's choice) so GRAB and HOLD share one write path and one guard. GRAB behavior must remain byte-identical from the user's perspective.

- [ ] **Step 2: The HOLD control**

Below GRAB, add a `HOLD TO REC` button (same DeckButton-style visual family, enabled when `armed && !grabbing`) with press/release gesture semantics (pointerInput awaitFirstDown → hold → release; NOT a click):
- On press: stamp `holdStart = SystemClock.elapsedRealtime()`; show recording state — label switches to `RECORDING…` and the elapsed readout is visible (the surface already shows the meter+elapsed when armed).
- On release: `val heldMs = SystemClock.elapsedRealtime() - holdStart`; `val frames = (heldMs * MicSessionService.SAMPLE_RATE / 1000L).toInt().coerceIn(1, PadCapture.MAX_HOLD_FRAMES)`; run the shared commit path with `snapshotTail(frames)?.let { PadCapture.holdClip(it, MicSessionService.SAMPLE_RATE) }`.
- A press shorter than ~150ms should toast a hint (`HOLD TO RECORD`) instead of committing a useless sliver — use a `MIN_HOLD_MS = 150L` gate before even snapshotting.
- Add a small hint line: the surface's existing hint text should now mention both gestures (`GRAB the sound that just happened, or HOLD to record`) — keep it one line, plain.

- [ ] **Step 3: Compile** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 4: Reason through** (report): press during armed session → release slices exactly the held window off the ring (the ring was recording the whole time — no start/stop of any recorder); double-fire impossible (`grabbing` guard shared); leaving mid-commit completes via appScope; GRAB unchanged.

- [ ] **Step 5: Commit** — `git add` only PadCaptureScreen.kt; message: `feat(capture): HOLD TO REC — press to record, release to place`.

---

## Self-Review (done)
Spec coverage: HOLD gesture ✓ (Task 2), max-length cap ✓ (`MAX_HOLD_FRAMES` + `coerceIn`), no-transient-trim contract ✓ (Task 1), shared serialized write ✓ (Step 1 refactor). Type consistency: `holdClip(FloatArray, Int): Snip?` matches `grabOneShot`'s shape; frames math uses `SAMPLE_RATE` not a literal. The one intentional latitude: `commitToPad`'s exact signature is implementer's choice — both callers live in the same file.
