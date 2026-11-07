# RIVET

Industrial drums whose building grows from workshop to warehouse.

The characteristic gesture is **impact → metal rattle → walls answer → dark decay**.

RIVET is a registered six-voice engine: KICK, SNARE, TOM, METAL, HAT_CLOSED and
HAT_OPEN. Josh accepted the full kit audition and approved phone integration. It owns its source,
metallic shaping, oversampled drive and parallel room response. The shared `Modes` and
`Room` implementations supply the DSP. Existing engines and ROOM recipes are unchanged.

The six controls are TUNE, STRIKE, METAL, DRIVE, SIZE and WALLS. SIZE changes the room's
geometry, not the drum tuning. WALLS changes absorption. A separate close branch preserves
the strike. Nonzero HOLD is explicitly unsupported; all renders are finite one-shots.

The [engineering specification](superpowers/specs/2026-11-07-rivet-industrial-drum-engine-design.md)
records the model, macro contracts, integration surfaces, risks, build rounds and evidence.
The [model-2 kit trial](superpowers/specs/2026-11-07-rivet-kit-expansion.md) records the
expansion, frozen model-1 compatibility checks and three portable sixteen-pad kits.
The sixteen audition roles are now factory presets at SIZE .55. Their source controls and
rendered audio are unchanged. The original three-space comparison recipes remain available
through `RivetPresets.spaceCases` for reproducing the lab audition.

## On the phone

Choose SYNTH → RIVET for six voices and sixteen factory sounds. The six macros and a
separate VELOCITY slider edit finite hits. HOLD and a semitone ROOT editor are absent:
TUNE is continuous drum tuning. Saved presets, navigation drafts and sent pad recipes
retain the complete macro map, model version and strike strength.

FRESH TAPE → RIVET WORKSHOP / RIVET FACTORY / RIVET WAREHOUSE supplies the accepted
sixteen-pad bank at SIZE .10 / .55 / 1.00 respectively. All use WALLS .75, with two soft
source renders plus the hard hit. Hats on A03, A04, A15 and A16 share mute group 1.
Replaying an individual pad recipe rebuilds its main hit; the kit builder supplies the
two additional velocity layers.

The Android app and its instrumentation tests are compiled with SDK 35, JDK 17 and the
repository's pinned NDK. Device execution and physical MPC playback remain unverified.

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
Recipes written before the VELOCITY field default to full strength, preserving their PCM.
New recipes store velocity explicitly. `Velocity.atVelocity(patch, v)` scales that saved strike
by `v`, so a full-strength layer reproduces even a saved soft patch exactly.
`Patches.fromJsonText` and `PadRecipe` replay them. `Velocity.atVelocity` strikes the native
source again, retaining the room and macro map rather than filtering a previously rendered hit.

The room still has its existing one-second tail allowance. This gives warehouse geometry
with a short decay; a several-second hang needs a later, explicit design. The parallel room
branch includes ROOM's direct signal. A crushed or gated isolated room return is not part
of this first implementation.

## MPC load-and-play check

Download the three kit ZIPs from the [published audition](https://rivet-drum-audition.bertcalm.chatgpt.site).
Extract each archive with its TrackData folder intact. Use the `.xtd` for MPC 3 or the
`.xpm` inside TrackData for MPC 2. Load all three programs and record the MPC model and
firmware alongside the result.

1. Confirm sixteen populated pads, no missing samples, and one-shot playback.
2. Play each pad softly, at medium strength and hard. Confirm three timbral layers and
   natural playback dynamics. The exported layers are peak-matched; the sampler supplies level.
3. Strike A04 or A16, then A03 or A15. Confirm any hat cuts the previous hat, including
   across the Metal and Scrap pairs. Kick, snare, tom and percussion must keep ringing.
4. Compare A01, A02, A07 and A12 across rooms at the same velocity. The close strike and
   tuning should remain stable while reflections spread. Check warehouse tails for clicks.
5. Play the supplied four-bar clip at 120 BPM, save the project, reopen it and repeat the
   layer/choke check. Record any difference from the published software grooves.

On the phone, audition all six voices, recall a factory preset, edit SIZE and VELOCITY,
save it, leave and return, and send it to an empty pad. Its pad audio should match the
edited audition. Then create each room starter and export it to the MPC.

The Kotlin tests cover frozen kick/snare PCM, recipe compatibility, factory registration,
three-layer starter delivery and exported WAV replay. The Compose device suite adds
last-frame edit/save/send and navigation coverage; compiling it is not a device pass.

Phone-integration verification: 176 relevant JVM tests passed (52 synth, 124 shell),
including the picker/catalogue conventions. Both `:app:assembleDebug` and
`:app:assembleDebugAndroidTest` passed; the development APK's signature verifies.
Regenerating all three native MPC kits passed format read-back. All 144 velocity WAVs
and three groove WAVs match the accepted audition byte for byte. Earlier unfiltered
JVM attempts ended without a final result; no full-suite pass is claimed.

## Why it is built this way

- The close branch anchors the rhythm as the room grows.
- A separate metal bank changes upper resonances while the drum root stays fixed.
- Reusing modal and room DSP keeps the first round focused on the shared instrument controls.
- The original two-voice gate established the close/room balance before the kit expansion.
