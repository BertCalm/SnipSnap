# Aquarium on ORBIT and stereo performance storage

Read against SnipSnap `82a1f9c74086d574d4de3eb1b7dc0944069c89d6`.
Extends the accepted continuous PerformanceBus and the explicit SURFACE route.
The six-effect DSP, parameter registry, presets and render model are unchanged.

The characteristic gesture is choose a kit sound → opt a ring set into KIT FX
→ play the arrangement → keep one fresh turn and its chosen tail as stereo audio.

## Routing and ownership

OPEN THE SET exposes AQUARIUM · ORBIT. USE KIT FX references the containing
kit's saved KIT → PERFORMANCE settings: all controls, seed, gain and global
bypass. It does not copy them into a second editor. ORBIT supplies its own BPM,
including to tapped-pad audition; a kit's different BPM is not substituted.
Fresh opt-in requests a reset to the saved seed, retained if UI updates coalesce.

`orbits.json` has optional `aquariumFromKit` (default false) and
`aquariumTailSeconds` (default 3, finite 0..30). Version remains 7; old default
files keep their shape and dry behavior. Present values decode strictly. Ordinary
set edits, history and remount state retain these fields. Invalid/future kit FX
metadata stays preserved and warns while resolving dry. An unpatched route uses
the zero-mix default, so it does not clock hidden wet DSP. A patched global bypass
retains the configured processor's running history, as on KIT and SURFACE.

```text
pattern voices + snip rings + pan/level/solo/sections
    → existing final interleaved stereo sum
    → accepted native PerformanceBus
    → AudioTrack sink

fresh copy of the same mixer and processor, frame zero
    → one transport turn, then zero input for the selected FX tail
    → stereo SNIPS file at the MPC rate
```

Tapped pads use ORBIT's existing independent PadEngine, configured with the same
sound and ORBIT tempo. Its audio/history is separate from the arrangement. Taps
are excluded from BOUNCE. KIT, SURFACE and other streams retain their own owners.
This is not a universal master insert. CLIP TO KIT writes notes, not baked FX or
native MPC inserts. A wet bounce is baked; KIT FX would process it again.

## Playback and bounce contract

OutputProcessor runs after the complete sum and before the sink writes. Default
dry preserves the existing finite waveform and floating overs. The accepted wet
branch clamps input to +/-1 and uses the existing output gain and linked 0.99
sample-peak guard. Dry bypass retains its original range. This is not true peak.

Playback and bounce starting fresh with the same set, bank, sound, tempo and
block size have identical output over the requested source frames. BOUNCE is a
fresh deterministic rendition, not a recording of the running transport's current
history, live edits or tap audition. Subsequent live laps can differ from a fresh
bounce because modulation and reverb history continue. Stereo motion and mixed
signal dynamics belong to the sum, not independent per-pad renders.

The source stops at the exact requested frame, even inside a mix block. No new
hit or snip activity after that point reaches the effect. The processor then
receives zero input for the selected tail. No finite PAD renderer or automatic
normalization is inserted. Tail length is zero while unpatched, globally bypassed
or all mixes are zero; the saved tail preference remains. The ending can truncate
a long decay and a wet print can have a loop seam. There is no seamless-loop
guarantee or automatic end fade.

The combined source and tail count is checked before output allocation against
the mixer's stereo bound and SNIPS' actual 180-second cap, using the resampler's
frame-floor law at 44.1 kHz. A print that would be shortened is refused in words.
Output storage is `8 * frames` bytes before resampling/encoding, in addition to
the prepared bank and native effect state. Thirty tail seconds at 48 kHz add
11.52 MB of floats. Phone memory and timing remain physical acceptance work.

## Worker, controls and lifecycle

The new JNI float-array bus reuses PerformanceBus and the shared strict control
decoder. A native handle is prepared before transfer to the mix worker. The UI
preflattens settings and hands immutable snapshots through an atomic reference;
only the mix worker publishes native controls, processes and destroys its handle.
Tempo comes from the set adopted by OrbitEngine at each block. Ordinary edits and
section boundaries keep FX history. Rewind and RESET TAIL use the accepted smooth
reset policy, which fades down, clears and rejoins rather than teleporting the
room's modulation phase in a live buffer. New PLAY prepares fresh history.

The bridge copies regions through fixed 256-frame stereo native scratch. It
never pins a Java array through processing or allocates/frees C++ storage there.
No JSON, string lookup, file IO or mutex is added to that processing call. This
is ORBIT's existing blocking AudioTrack worker, not an Oboe callback. The JVM
mixer's existing voice/scheduling allocations remain; JNI/VM copy overhead and
physical phone deadlines are not inferred from native allocation interception.

A timed UI join does not authorize freeing native audio state. The worker's
finally block closes its processor; the UI can close the sink to release blocked
writes without touching that handle. Background/disposal stops the transport.
A generation counter prevents an in-flight bank preparation from starting audio
after STOP. A failed wet preparation or rejected processing request is reported,
and a faulted offline result is refused before storage. Optional preparation
failure still permits default-dry playback. AudioTrack's existing device-loss and
focus policies remain; physical recovery is unmeasured.

## Stereo shelf landing and SURFACE regression

The ordinary SnipStore import intentionally folds to mono. Its new explicit
`preserveStereo` option retains both channels while keeping resampling, atomic
writing, duration reporting and the cap. Both ORBIT BOUNCE and SURFACE → TAPE now
select this option. Stereo truncation counts frames and retains both channels.
This fixes the earlier SURFACE storage gap: native/JNI/Kotlin were stereo, while
the old shelf landing still folded. SURFACE → PAD was already a direct Snip path.
TAPE's editor continues to fold files to mono when reading them; a stereo shelf
file is not a stereo TAPE editing engine.

The failed original SURFACE emulator report identifies SurfaceScreenTest:77:
disk had saved OFF while semantics still showed KIT FX ON. Its frozen clock
advanced only while the disk condition was false. A disk write can win before a
frame commits the state change, so disk-only completion cannot prove redraw. The
test now waits for both persisted and semantic state and repeats three on/off
cycles. Assertions and metadata/source-byte checks remain.

## Evidence ledger

- **Read/verified:** the named base's ORBIT mix/sink/bounce paths, separate audition
  handle, set codec, shelf mono conversion and capped import, and exact original
  SURFACE failure report. Accepted DSP sources/registry/presets are unchanged.
- **Tests supplied:** exact dry and fresh live/bounce equality; analytic zero-input
  tail across 1/37/256/2048-frame blocks; tempo/rewind signaling; strict optional
  storage and preflight bounds; native JNI parity for unsigned seed, tempo-synced
  S&H, sine stereo phase and reset; pending/read/write exception refusal; zero
  native processing allocations/frees; opposing stereo shelf channels through
  resampling and truncation; real Android mixer/JNI/shelf and UI route/play/save.
  Actual results belong in the PR validation report.
- **Unmeasured/unheard:** physical phone CPU, latency, power and recovery, and MPC
  listening. No new listening verdict is inferred from host or emulator checks.

## Why it is built this way

- One post-sum processor gives the rings one continuous environment and preserves
  the accepted effect laws without a second DSP implementation.
- A shared mixer hook lets fresh playback and bounce use identical processing;
  an exact source endpoint prevents hidden notes from entering the printed tail.
- A saved opt-in respects existing dry sets and keeps the kit's sound editable
  in its existing home. The set keeps ownership of musical time and print length.
- Owner-thread destruction prevents a stalled sink and timed UI teardown from
  turning into a native use-after-free.
- Explicit stereo storage prevents the final landing from undoing the width that
  every earlier audio stage preserved; normal imports retain their old contract.
