# THE RETROACTIVE SNIP — mic capture, arrow of time reversed

**Status:** spec.
**The promise:** the app is always listening once armed — a 60-second ring
that is never written to disk. The sound happens, *then* you press. You
cannot miss what already occurred.

**Why mic, not playback capture:** `docs/CAPTURE_RESEARCH_2026.md` — the
system-recorder escape hatch is likely not universal, Android 15 kills
projections at screen lock, the probe was never run, and Koala ships
mic-only as first-class. Meanwhile our own `CaptureProfile`/`CaptureDoctor`
were built and validated against a real phone-mic room recording. The mic
is the proven path AND the creative one: it hears rooms, objects, and
mistakes that no digital tap contains.

**The enormous simplification:** the mic needs NO MediaProjection. No
consent dialog per session, no screen-lock death, no per-app blocking.
Just `RECORD_AUDIO` + a microphone-type foreground service.

## Settled by existing artifacts (not open questions)
- **Ring length 60 s** — `Copy.SNIPPED = "SNIP! LAST 60s KEPT."` shipped.
- **Session model** — arm explicitly ("TAPE ROLLING. GO STEAL A SOUND
  (LEGALLY)." is shipped copy), persistent notification while armed,
  disarm stops everything. `Delight.deckSoundsEnabled(level, captureArmed)`
  already takes a `captureArmed` flag — the shell anticipated this.
- **Mono 44.1k** — `CaptureProfile` calibrated on mono phone-mic audio.
- **Where a snip lands** — TAPE. The deck already opens WAVs; the spine is
  MIC → RING → SNIP → TAPE (trim) → CHOP → KIT. `Copy.EMPTY_SHELF`'s
  "NOTHING TAPED YET" finally becomes false the honest way.
- **The Bubble** — `design/Bubble.dc.html` + HANDOFF row: floating ring =
  tape fill, tap = snip, drag-down = eject (hot zone y>660). It is the
  signature interaction and stays in scope, as the LAST task — the feature
  must work from the notification alone before the overlay exists.

## Architecture

### 1. `CaptureRing` (`:audio`, pure JVM, fully testable)
The realtime ring the design doc demands: single-producer/single-consumer,
**allocation-free on the write path**, no locks. A preallocated FloatArray
(60 s × 44100), a monotonic `@Volatile` write cursor, `write(block, n)`
that only copies and bumps, and `snapshot(seconds): FloatArray` that reads
backward from the cursor into a caller-provided or freshly-allocated
buffer (allocation on the RARE read side is fine). Tearing at the exact
cursor boundary during a concurrent write is acceptable and documented —
one sample of ambiguity at the oldest edge of a 60 s window is inaudible.
The existing `RingBuffer` stays untouched: it is the offline model; its
own KDoc says so.

### 2. `MicSessionService` (`:app`, foreground service)
- `AudioRecord` (source: `UNPROCESSED` when
  `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` says so, else
  `VOICE_RECOGNITION` — the researched fallback with the documented
  no-noise-suppression guarantee), mono, 44.1 k, float.
- One plain reader thread: `read()` into a preallocated block,
  `ring.write(block, n)`. No allocation in the loop.
- Foreground type `microphone`; Android 14+ manifest permission
  `FOREGROUND_SERVICE_MICROPHONE`. Started only from the foreground (the
  ARM action), which sidesteps Android 15's background-start limits.
- Notification: "TAPE ROLLING" + a **SNIP action button** — capture works
  with the phone in your pocket via the notification shade, before the
  Bubble exists.

### 3. The snip commit
On SNIP (notification action, in-app button, or later the Bubble):
`ring.snapshot(60s)` → trim leading silence → `CaptureDoctor` diagnosis →
commit-time `Cleanup` chain → WAV into app storage `snips/` with a
timestamp name → toast `Copy.SNIPPED`. The service keeps rolling — one
session, many snips. Snips survive app death (they are files).

### 4. TAPE integration
TAPE's source priority becomes: newest snip → lastCommit source → open
kit's longest sample. A tiny SNIPS strip (recent snips by time) can wait;
newest-first is the v1 rule and matches the "just caught it" moment.

### 5. The Bubble (last)
`SYSTEM_ALERT_WINDOW` overlay per the artboard: ring fills as the buffer
fills, tap = snip, drag toward the bottom hot zone = eject (ends session,
`Copy.BUBBLE_EJECTED`). Scale 1.08 while dragged (`Motion.BUBBLE_DRAG_SCALE`
— the constant landed weeks ago). Permission is optional: the feature is
complete without it; the Bubble is the delight layer.

## Constraints
- **Privacy is the product's spine:** the ring lives in memory only,
  nothing is written until SNIP, disarm drops the ring. Say this in the
  UI. `POST_NOTIFICATIONS` + `RECORD_AUDIO` runtime prompts on first ARM,
  with the existing X4.4-style honesty ("NOTHING LEAVES THE PHONE").
- The reader thread must survive audio-route changes (headset plug) —
  `AudioRecord` keeps working; do not rebuild the record on route change
  in v1, note it.
- Battery: one mic stream + no disk writes is cheap; the notification
  makes the cost visible and killable.
- Device testing gates the audio tasks — desk gates are compile + JVM
  ring tests; the mic loop itself needs the phone (user-scheduled).

## Explicitly out (this pass)
- Playback capture (own spec, gated on running the never-run probe).
- The Overheard Kit / live classification (walk-becomes-a-kit) — next.
- The Split (HPSS on snips), The Keeper (self-reviewing takes), Room as
  Patch — each is a later spec that CONSUMES snips; nothing here blocks
  them.
- Quick Settings tile.
