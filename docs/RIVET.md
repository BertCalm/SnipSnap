# RIVET

Industrial drums whose building grows from workshop to warehouse.

The characteristic gesture is **impact → metal rattle → walls answer → dark decay**.

RIVET is a registered six-voice Kotlin candidate: KICK, SNARE, TOM, METAL, HAT_CLOSED and
HAT_OPEN. The first kick/snare direction was approved for continuation. It owns its source,
metallic shaping, oversampled drive and parallel room response. The shared `Modes` and
`Room` implementations supply the DSP. Existing engines and ROOM recipes are unchanged.

The six controls are TUNE, STRIKE, METAL, DRIVE, SIZE and WALLS. SIZE changes the room's
geometry, not the drum tuning. WALLS changes absorption. A separate close branch preserves
the strike. Nonzero HOLD is explicitly unsupported; all renders are finite one-shots.

The [engineering specification](superpowers/specs/2026-11-07-rivet-industrial-drum-engine-design.md)
records the model, macro contracts, integration surfaces, risks, build rounds and evidence.
The [model-2 kit trial](superpowers/specs/2026-11-07-rivet-kit-expansion.md) records the
expansion, frozen model-1 compatibility checks and three portable sixteen-pad kits.
New roles and pad colors remain listening candidates.

Generate the full kit trial:

```sh
./gradlew --no-daemon :synth:generateRivetKit
```

Open `testkit/rivet-kit/index.html`. Workshop, factory and warehouse versions share the
same sixteen source recipes, with three native strike zones per pad and hat choking.
Each download contains native MPC 3 and MPC 2 programs. Physical loading is still pending.

Generate the native listening pack:

```sh
./gradlew --no-daemon :synth:generateRivetAudition
```

Open `testkit/rivet-audition/index.html`. Begin with three two-bar, 120 BPM grooves using
the same strikes in three room sizes. Then compare native and common-loudness hits,
control sweeps, macro pairs, three tunings and touch levels. Saved JSON patches accompany
every normal hit. `manifest.json` records velocity separately; use
`Rivet.render(patch.voice, patch.macros, patch.modelVersion, velocity)` to reproduce a soft
clip. Groove event files carry complete patches, velocities, frame positions and shared gain.

Saved `RivetPatch` recipes require a full immutable macro snapshot and an explicit model version.
Model 1 retains KICK/SNARE; model 2 adds four roles while reproducing those old sounds exactly.
`Patches.fromJsonText` and `PadRecipe` replay them. `Velocity.atVelocity` strikes the native
source again, retaining the room and macro map rather than filtering a previously rendered hit.

The room still has its existing one-second tail allowance. This gives warehouse geometry
with a short decay; a several-second hang needs a later, explicit design. The parallel room
branch includes ROOM's direct signal. A crushed or gated isolated room return is not part
of this first implementation.

Listening to the new roles, phone performance, factory presets, MPC hardware checks and
the Android picker remain future rounds. No SDK is present here, and
no factory presets or starter kit are promoted before the listening verdict. The new engine
is explicitly listed in the test registry's prototype exemptions.

## Why it is built this way

- The close branch anchors the rhythm as the room grows.
- A separate metal bank changes upper resonances while the drum root stays fixed.
- Reusing modal and room DSP keeps the first round focused on the shared instrument controls.
- The original two-voice gate established the close/room balance before the kit expansion.
