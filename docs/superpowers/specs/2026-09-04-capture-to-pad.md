# CAPTURE TO PAD — the grid is the canvas, the pad is the record button

**Status:** spec. Depends on: mic capture verified working (the level meter in
the retroactive-snip build must move on real input — if the mic delivers
silence, fix that first; GRAB/HOLD both grab nothing otherwise).

**The promise:** you don't pick a kit and then fill it from elsewhere. You land
on an **empty grid**, long-press a blank pad, and the sound goes straight onto
that pad — either grabbed retroactively from the always-listening ring, or
recorded live while you hold. No TAPE, no CHOP, no journey.

**Why this exists:** today capture is a shelf-global act and a sample only
reaches a pad by assembling a whole kit from chops (`KitAssembler.assembleArranged`).
There is no empty template (every `fresh()` starter renders 16 filled pads),
empty pads have no touch handler at all, and arming is wired only to the shelf.
This spec makes the grid the front door. It also happens to answer the
"tape-metaphor obscures function" complaint: a pad that records when you hold it
needs no vocabulary.

## Settled by the design conversation (not open questions)
- **Gesture: both, meter-forward.** Long-press an empty pad opens a pad-capture
  surface showing the live level meter. **Tap GRAB LAST** = pull the last hit
  from the 60 s ring (retroactive). **Press-and-HOLD** = record live while held,
  release to place. The meter (already built in retroactive-snip) is the
  "mic is hearing you" feedback on the very pad being filled.
- **Empty pads only, v1.** Filled pads keep their existing tap (play) +
  long-press (PadSheet) behavior. Re-record / overdub onto a filled pad is a
  later phase.
- **Reuse the stack.** The ring, the level meter, transient trim
  (`Transients`/`Chopper`), and `CaptureDoctor` cleanup already exist. The new
  code is the pad-capture surface, arm-from-inside-the-kit, and a single-slot
  assign primitive.

## Architecture

### 1. The BLANK starter (`:shell`/`:kit`, the empty canvas)
A new `StarterKits.Starter` (working name **EMPTY**; avoid tape-metaphor copy)
whose render produces a kit with an **empty pad list** — the model already
tolerates this (`KitScreen` renders `null` slots as inert blanks). Offer it in
the FRESH menu alongside FACTORY / LUCKY DIP / MELODIC. No audio, no pads, just
a named empty kit folder that persists like any other.

### 2. Empty pads become live (`:app` `KitScreen.kt`)
Empty slots currently attach no `pointerInput` (`KitScreen.kt:157-234`, the
`pad == null` branch is static text). Add a long-press handler to empty slots
that opens the pad-capture surface for that slot. Filled-pad behavior is
unchanged.

### 3. Arm-from-pad (`:app`)
Arming + `RECORD_AUDIO`/overlay permission currently live only on the shelf
(`App.kt:210-218`, `requestArm`, passed only to `KitsScreen`). Lift the
permission+arm entry point so the pad-capture surface can trigger it: if the
mic is not armed when the surface opens, the surface's first job is to request
permission and arm (same `MicSessionService` path the shelf uses — do not
duplicate the service logic). Once armed, the meter comes alive and GRAB/HOLD
are enabled.

### 4. The pad-capture surface (`:app`, new composable)
Opened for a specific slot. Shows: pad number, the live level meter
(`MicSessionService.level`), an elapsed/liveness tick, and two controls:
- **GRAB LAST** (tap): snapshot the tail of the ring (~last 2 s is enough for a
  one-shot), run `Transients` to find the last hit, trim to it, `CaptureDoctor`
  clean. Yields a short one-shot sample.
- **HOLD TO REC** (press-hold): record from arm-start-of-press to release
  (cap at a sane max, e.g. a few seconds), then `CaptureDoctor` clean. No
  transient-trim (the user defined the boundaries by holding).
Either result flows to the single-slot assign primitive (§5), then the surface
closes and the pad shows filled. Cancel/back leaves the slot empty. A failed
capture (empty result, exception) shows a toast (CLAUDE.md: user-facing async
failures must toast).

### 5. Single-slot assign primitive (`:kit`/`:shell`, the genuinely new code)
`assignSampleToSlot(kit, slot, samples, sampleRate): Kit` (exact signature TBD
in plan): write the WAV into the kit folder via `WavWriter` and return a
`kit.copy` with a `KitPad(slot=…, sampleFile=…, …)` added/replaced — the same
WAV-write + `KitPad` construction `KitAssembler.assembleArranged` already does
(`KitAssembler.kt:92,115-128`), but at one-slot granularity instead of rebuilding
the whole kit. Default the new pad's metadata (drumClass via `Classifier` if
cheap, else UNKNOWN; level/pan/tune neutral; `oneShot=true` for GRAB one-shots).
Persist through the existing kit-save path and bump `open.kit` identity so
`KitScreen`'s `PadPlayer` reloads (same pattern as PadSheet/CHOP `onKitUpdated`).
`invalidateCache(sampleId)` per CLAUDE.md after writing the buffer.

### 6. Fold in the CHOP kit-gate fix (sibling, same bug class)
`ChopScreen.kt:93` early-returns `EmptyChop` when `entry == null`, before the
kit-independent `loadFromCommit` path runs — the same gate we just removed from
TAPE, one screen down. Fix it so a snip-based `lastCommit` chops into a NEW kit
via the existing `onSentToGrid` create-kit path with no kit open. This keeps the
"chop one snip across many pads with no kit" path working; the "one sound → one
pad" path bypasses CHOP entirely via this spec.

## Phases (Bummer's cut)
- **Phase 1:** BLANK starter + empty-pad long-press + pad-capture surface with
  **GRAB LAST** + single-slot assign + arm-from-pad. This is the whole loop for
  the retroactive gesture, end to end, on an empty grid.
- **Phase 2:** HOLD TO REC (momentary record, press-to-release), inline
  permission polish, max-length cap + trim.
- **Phase 3:** re-record/overwrite a filled pad; BLANK as first-run default;
  CHOP gate fix (can also be pulled earlier — it's independent).

## Constraints
- **Do not build until the mic is verified capturing audio** (level meter moves
  on real input). GRAB and HOLD both depend on a non-silent ring.
- Reuse `MicSessionService` for arming — one service, new surface. No second
  capture path.
- Sample rate: derive from the capture, never hardcode 44100 (CLAUDE.md).
- Match SnipSnap's visual language (Scheme tokens, LCD/tape aesthetic). No new
  color constants, no new external deps.
- Empty-pad interaction must not regress filled-pad tap/long-press.

## Explicitly out (this pass)
- Overdub/layering onto an existing pad (velocity layers) — later.
- Choosing which bank (A/B) at capture time — v1 fills bank A.
- Multi-pad "spray" capture (record a phrase, auto-slice across pads) — that IS
  the CHOP path; not duplicated here.
