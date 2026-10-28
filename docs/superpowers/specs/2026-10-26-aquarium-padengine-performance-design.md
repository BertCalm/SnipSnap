# Aquarium performance: PadEngine and PRINT

**Base read:** `fd791f583579dda3dcab3d2ea9e34f9975b07a12`

**Scope:** the first continuous host path from the [integration plan](../plans/2026-10-18-aquarium-foundation.md). The accepted six-stage DSP and finite PAD printing remain unchanged.

The characteristic gesture is several pad hits → one shared effect clock and room → continued tail between hits → a stereo print of the same output.

## Routes read in the current app

| Route | Actual owner | This stage |
| --- | --- | --- |
| KIT/PADS, beat transport, GROOVE pad voices and its native BOUNCE | Shared `PadHost.engine`, a native `PadEngine` | Configurable continuous bus; PRINT/BOUNCE is after processing |
| SPLIT | Its own `PadEngine` | Same per-instance API; defaults dry and is not configured by another kit's host |
| ORBIT tapped-pad audition | Its own `PadEngine` | Same per-instance API; independent default-dry state |
| Held keygroup/instrument playback | `InstrumentPlayer` owns a separate `NativePads` handle | Default dry; no new InstrumentPlayer performance control |
| ORBIT transport | `OrbitEngine` → `AndroidAudioSink` | Separate mixer; later routing work |
| SURFACE | Native `SurfaceEngine`, mono master/print before L/R duplication | Later stereo bus and print refactor |
| LOOP and other `AudioSink` transports | JVM mixers → `AndroidAudioSink` | Later routing work |
| SYNTH/TAPE preview | `TapeVoice`/`AudioTrack` | Separate playback path |
| ARRANGE and finite stereo auditions | `MixVoice`/`AudioTrack` | Separate playback path |
| Camera preview | Native `LiveSnapEngine` | Separate native stream |
| MPC kit export, finite bounces | Existing offline render/export paths | No live-bus recipe implied; kept PAD Aquarium audio still exports normally |

There is no universal app master insert. Configuration belongs to one `PadEngine` instance. The shared kit host is the first intended caller; a setter does not alter other streams.

The shared host's count-in/metronome clicks are ordinary banked voices and pass through the same bus and print tap. A separately routed dry metronome is not implemented here.

This change supplies the native/JNI/Kotlin backend and a source-independent settings value. The PERFORMANCE panel, automatic kit-folder persistence, other mixers and their bounces are subsequent work. No inactive PERFORMANCE control is added.

## Signal and output contract

```text
loaded sample voices, pans, gains, fades and frame-offset commands
    → existing summed stereo +/-1 clamp
    → accepted Aquarium Processor, actual stream rate and current host BPM
    → fixed output gain
    → stereo-linked sample-peak guard
    → wet transition envelope / global dry-bypass crossfade
    → speaker buffer and the identical interleaved stereo PRINT tap
```

The existing pre-effect mix clamp is retained. This stage does not recover transients already clipped by that clamp. Default configuration is dry; ordinary finite dry output remains bit-exact, including after explicitly bypassing a configured wet bus.

Performance output gain defaults to 0.5 and accepts 0..1. Zero deliberately mutes the fully wet branch. A linked gain uses the larger left/right sample peak, instant attack and a 50 ms exponential release to keep fully wet output at or below the native host's 0.99 sample ceiling. The tiny final rounding clamp enforces that ceiling after Float conversion. The guard is outside the six effects and every feedback loop. There is no block normalization, automatic per-hit gain or added latency/lookahead. This is a sample-peak policy, not a true-peak claim.

During a global bypass crossfade, the unchanged dry branch retains its existing +/-1 ceiling. Both endpoints are bounded; the transition can exceed 0.99 because dry can reach 1. Global bypass alone keeps the configured effects receiving the live mix and advancing through silence. Turning it back on does not revive a frozen global tail.

## Settings and bounded transitions

`AquariumPerformanceSettings` serializes its own format version, the complete `AquariumRecipe` (52 physical controls and unsigned seed), enabled state, gain, and explicit transition/output policy IDs. It contains no source hashes or offline tail/fade settings. The host supplies current BPM separately; `PadHost.request` follows the kit's tempo, or free time at zero. This value is reusable storage format, not automatic persistence in this stage.

One serialized control producer validates a complete native snapshot and publishes it through a fixed eight-slot SPSC ring, seven usable messages. A callback consumes at most seven messages, keeps the latest target and ORs reset requests. Queue overflow returns false without changing the native desired state. Kotlin keeps the latest requested configuration and retries from the existing frame-rate drain; a reset stays sticky until queued.

Sound/seed/tempo changes fade the wet branch to silence over 20 ms, adopt the complete snapshot while inaudible, then fade back over 20 ms. The dry branch does not surge into a quiet wet patch during an edit. Output gain has its own 20 ms linear slew. Global enable/bypass has a separate 20 ms dry/wet ramp. Rapid edits replace the pending target without restarting an already-running fade-out. Returning to the original target resumes the current sound instead of leaving its envelope down.

These are bounded transitions, not interpolation of filter laws or discrete modes. Continuous parameter automation can duck while adopting a new sound; that trade must remain visible when live controls arrive. The accepted core retains dormant history in an individually bypassed stage. Global bypass is clocked, but this host does not claim that a zero-mix individual stage advances its private delay/LFO state. An explicit reset requests a fresh seeded state.

## Ownership and lifecycle

- Prepare all delay/filter state on the control thread after the actual Oboe stream rate is known and before starting its callback. Process callbacks of any size through fixed 256-frame planar scratch. The callback allocates/frees nothing, looks up no names, parses no JSON and acquires no locks.
- If effect preparation is unavailable, ordinary default-dry playback can still start and the effect diagnostics report unavailable/faulted. Enabling a wet route refuses rather than silently playing dry. A stream already requested wet fails startup if its actual rate or allocation cannot support the processor; preparation is never attempted from its running callback.
- Notes share one processor. Note-on normally changes voices only. Quiet gaps continue feeding zeros to configured effects. Native PRINT copies the actual processed output, including transition/protection behavior, with the existing print ownership handshake.
- Bank replacement fades/clears old effect history and preserves the latest controls. A bank change already stops its old voices; no source recipe is reconstructed for the bus.
- All-off fades the wet branch down and clears it. Wet output stays suspended until a valid new note resumes fresh history, including when a caller chooses a longer voice-release fade. A configured wet route fades the new note in from silence, so a quiet/muted wet setting never leaks an untrimmed dry attack. The ordinary voice fades still apply.
- Stream stop, device loss followed by restart, and final destruction free processor state only after callbacks stop. Restart prepares at the new actual rate, reapplies the latest accepted controls and seed, and fades a configured wet path in from silence. It starts a fresh stream clock; the engine's diagnostic frame counter remains monotonic.
- Corrupt non-finite input becomes zero before feedback. A rejected process result or non-finite wet block never reaches output/PRINT: the wet branch mutes, state resets and it stays faulted until a new valid control publication. Only an explicit global bypass exposes the original dry branch. Diagnostics expose faults and frames with audible gain reduction.

## Evidence ledger

**Read/verified in code:** the route owners above, PadEngine's stereo mix and PRINT ordering, existing bank/command/print handshakes, and the processor's allocation-free snapshot/process/reset API. Native source lists and the retained accepted-core generator checks must both agree.

**Supplied checks:** exact default/configured bypass, shared nonlinear processing versus a premixed voice, accepted-processor clock/history parity, arbitrary callback partitions, quiet gaps, all presets/rates, queue backpressure and sticky reset, gain/configuration/bypass transitions, linked peak protection/release, panic/bank replacement, allocation/free interception, strict live JSON, JNI domains and stereo print equality. Actual execution results belong in the PR and CI report.

**Proposed until measured:** callback cost and power on an Android phone; route-loss behavior on physical audio hardware; later mixer/print integrations. A Linux host benchmark is useful implementation evidence and is not a phone measurement.

**Heard:** the earlier accepted six-effect audition applies to the shared DSP. No live-phone or physical MPC listening verdict is claimed here.

## Host checks and timing

The four native CTest targets pass, including 13 continuous-policy cases and six new engine/JNI integration cases. The new bus target also passes AddressSanitizer and UndefinedBehaviorSanitizer with leak detection. Callback allocation/free interception observes zero allocations and zero frees through configuration adoption, reset, panic and oversized buffers. Removing only the linked limiter gain update makes the named stereo-protection test fail; restoring it returns the complete native suite to green. The focused JVM run passes 25 Aquarium cases and 44 source-convention cases. Android compilation, the two supplied live-control/print tests and the full JVM suite are recorded in the PR/CI validation report.

Five fresh runs per case on the shared Linux executor, `g++ -O2`, 48 kHz, 256-frame callbacks, 3 seconds of stereo tone plus 2 seconds of zero input, BPM 123 and wet gain 0.125:

| Case | Median time for 5 seconds | Range | 95th percentile callback wall time |
| --- | --- | --- | --- |
| Dry/default | 0.066 ms | 0.063..0.202 ms | 0.034 µs |
| Full preset, wet | 144.964 ms | 120.355..208.250 ms | 342.718 µs |
| Full preset, globally bypassed | 123.848 ms | 112.850..226.583 ms | 292.507 µs |
| Full preset with repeated reset/tempo changes | 136.390 ms | 110.671..148.727 ms | 322.732 µs |

This measures the continuous bus and timing instrumentation, excluding preparation, JNI, sample-voice mixing, Oboe and PRINT copying. The reset case publishes a reset/tempo change every 32 callbacks. Observed callback wall-time maxima reached 42.105 ms on this executor. Physical-phone callback deadlines remain unmeasured. Global bypass intentionally retains the configured DSP work to preserve its clock/history.

Reproduce from the repository root:

```sh
g++ -std=c++17 -O2 -Iapp/src/main/cpp \
  app/src/main/cpp/test/performance_bus_benchmark.cpp \
  app/src/main/cpp/aquarium/PerformanceBus.cpp \
  app/src/main/cpp/aquarium/AquariumProcessor.cpp \
  -o /tmp/snipsnap-aquarium-performance-benchmark
/tmp/snipsnap-aquarium-performance-benchmark
```

## Why it is built this way

- One shared processor makes a kit inhabit one room and one motion clock, rather than restarting effects for every hit.
- The existing stereo PRINT tap records the very buffer sent to the speaker, including protection and transitions.
- A bounded queue and preallocated scratch keep work and memory predictable inside the callback.
- Wet-only sound transitions preserve chosen output level; global bypass remains an explicit comparison with the original bus.
- Explicit route scope lets the remaining mixers join deliberately without promising that a global switch already reaches them.
