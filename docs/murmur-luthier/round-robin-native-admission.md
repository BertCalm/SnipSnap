# MURMUR RR native admission boundary

This slice adds a native chord primitive and a pure Kotlin admission
controller with focused tests. It does not load a saved bank, expose a screen, or claim Android
playback/device acceptance. The accepted source, samples, ordinary/v1 shelves,
legacy InstrumentPlayer and legacy shared-clock instrument JNI API are unchanged.

## Ownership

One controller owns one **freshly created** PadEngine native handle and one
immutable committed bank. Its bank loader must validate the source-bound RR bank,
retain its exact zone/root/touch/take/PCM identity, and map each loaded sample index
to a controller Cell. Four takes each have soft/medium/hard entries. This loader
and its memory budget are not implemented here. Do not construct descriptors from
an unchecked sidecar and call that bank validation.

No legacy pad/instrument/scheduled-note producer may use this handle. Each bank
reload must destroy the old handle and create a new one for the new controller;
never reuse old ended-ID queues with a controller whose IDs restart at one.
The handle owner must serialize stream/bank/handle lifecycle operations with the
controller, keep the handle alive throughout a call, poll while releases or retry
debt exist, and destroy it at final disposal. These UI/lifecycle integrations are
still required. The port is an internal bridge, not a handle owner.

## Admission and release

`admitRoundRobinChord` copies seven equal-size JNI arrays, checks pending/copy
exceptions after each region, and validates all 1–8 notes against the current
committed native bank before one `SpscRing::pushAll`. Each note has its own sample,
start/end, loop, gain and pitch. Invalid members or ring capacity refusal publish
nothing. No cursor policy lives in C++; no old API limit or sound law is widened.
New RR admission refuses read speed above the native ceiling instead of silently
clamping it. All voices start at the top of the same callback. Bank generation
stamping and stale/stop ended reports use the existing engine handshake.

`MurmurRoundRobinAdmission` owns the **global eight outstanding voice** quota
across all takes. Queued attacks and released tails remain counted until native
ended IDs arrive. It never steals a voice. A duplicate/unmapped final-note chord,
invalid live velocity, capacity refusal or native refusal consumes no take or
voice ID. Same-zone notes advance its shared cursor in caller order; other zones
advance independently. MIDI velocity windows are 1–42 / 43–84 / 85–127, matching
the package/reference player; live gain stays velocity ×0.35. IDs are monotonic
and not reused; exhaustion refuses future admissions.

A note-off snapshots all existing attacks at that MIDI note. Refused stops retain
the same IDs and fade; retry never targets a newer attack at the same note.
Accepted stops still occupy the quota until ended. Reset changes only cursors;
all-off requests releases and resets cursors, retaining release debt on refusal.
The owner must schedule `poll()` even after the last UI key is lifted.

## Validation scope

The dedicated CMake `round_robin_admission_tests` target drives production
PadEngine and production JNI with host stubs: independent windows/pitches/loops,
whole-chord invalid/ring refusal, bounded count, stale generation/stop reporting,
all seven JNI copy exceptions, array mismatch, and legacy shared-clock/displaced
voice behavior. The pure controller tests exercise cursor/ID atomicity, global
quota including release tails, captured retries, reset, immutable descriptors
and separate old/new port identity. Neither test family is a phone audio run.

Next: a source-bound finite bank loader with a fresh owned handle, exact retained
PCM loading and bounded memory, explicit shelf selection, lifecycle polling and
shutdown, then selected-source normal Android compilation/runtime and device
checks. Held252-cell streaming remains separate. No new audition is needed for
this code-only boundary; prior listening approvals remain unchanged.
