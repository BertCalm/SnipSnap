# Spore: approved synthetic core and three complete kits

The owner accepted the kick/snare audition with “Sounds good, proceed”. This revision ports the approved kick to Kotlin, preserves the approved snare, and builds three sample-free drum arrangements around them. The new arrangements are awaiting a listening verdict; approval of the pair is not approval of every new voice.

## Sound and instrument design

| Kit | Tempo | Character and roles |
|---|---:|---|
| Spore Dry Jungle | 170 BPM | Approved dry kick/snare anchors, heel and ghost strokes, rim, stick, tight/open hats, shaker, ride, two toms and psychedelic accents |
| Spore Dub Breaks | 85 BPM | Lower, fuller core, cross-stick and woodblock, hand pluck and echo wood; triplet roll |
| Spore Orbital Breaks | 155 BPM | Shorter, brighter core, wire clap and metal, ring/phase colour, broken-beat pattern |

Each kit contains 16 individual voices plus an authored snare roll and accelerating/pitch-rising riser. All 18 pads have three velocities (0.30, 0.65, 1.00) and three takes. There are 54 pad roles and 486 rendered cells across the shelf. Closed/open hats share a choke group; shakers do not. These are finite percussion instruments: melodic six-voice/keygroup/chord and string STRUM requirements do not apply. The hand-pluck accent is a finite percussion colour, not a separately delivered melodic string instrument.

`SporeKick` and `SporeSnare` use frozen reference-calibrated modal oscillators and filtered noise. The shared `SporeCore` retains the snare's exact numerical operations, oversampling and conditioning. Both produce 450 ms mono hits at 44.1 kHz, internally running at 176.4 kHz. The kick uses a distinct five-mode frequency bank, beater excitation and downward pitch relaxation. Fixed model gain preserves touch dynamics; the kernel does not normalize individual strikes. The original Python models remain unchanged, and no recording is loaded by either source.

The remaining voices reuse SKIN, ONSET and TINES with genre-specific recipes. Velocity changes timbre and level; alternate takes change excitation or model parameters. Existing source limiting remains part of those engines. Echo, phase, ring and reverse treatments are restricted to colour accents (and Dub echo wood). Jungle pads 1 and 3 return the approved sources directly. Variations in other kits deliberately change their controls.

Rolls use fresh strikes, not copies of one hit: sixteen sixteenth-note events, or twelve triplet events for Dub. The riser has 24 events, accelerating from quarter-beat to eighth-beat spacing while force and pitch rise. Gesture duration is bounded by four beats plus the 450 ms source tail. Colour tails are capped at 1.35 seconds with a fade. Source recipes validate genre, pad, velocity, take and version.

## Integration and acceptance evidence

Three new starter IDs (`spore-synth-jungle`, `spore-synth-dub`, `spore-synth-broken`) appear once each in the SKINS shelf. Existing Spore and acoustic starters are retained. Saved `sourceRecipe` metadata can rerender the grid through the Kotlin API; it is not a fabricated single-hit app `PadRecipe` or an editable synth-picker patch.

| Gate | Evidence and limit |
|---|---|
| Core sound | Owner approved the prior pair; new arrangements await listening |
| Port preservation | 17 Python waveform comparisons per core, covering nine velocity/take cells and eight control extremes; all passed |
| Source health | All 54 roles checked for finite output, peak, DC, zero tail, silence at zero force, monotonic levels, timbral velocity change and distinct takes |
| Determinism | Versioned JSON recipe replay, canonical and fallback takes; Jungle anchor grids equal approved sources exactly |
| Genre shelf | Three distinct layouts registered and found by shelf checks |
| Percussion gestures | Roll/riser event structure and every gesture velocity/take checked |
| Native save/playback | All 486 cells regenerated from saved metadata, compared to PCM24 within three sample steps, resolved through PadHit including take wrap; deterministic KitPreview and hat choke counterexample checked |
| SFZ | 162 regions per kit; complete grids imported, PCM and playback windows compared |
| MPC 3 | Complete three-layer/three-take grid and packed PCM read back, with four-bar pattern; no MPC 2 fallback |
| Device | Android timing/playback and physical MPC import/playback remain unverified |

Focused `SporeKickTest`, `SporeSnareTest`, `SporeSyntheticKitsTest`, SPORE determinism cases, `StarterShelfTest`, `SporeSyntheticDeliveryTest` and `SporeSnareDeliveryTest` passed. The full JVM regression run is tracked separately in the PR. No native C++ code changed. Browser controls can be structurally tested here, but browser decoding/listening is not a result of the Kotlin checks.

Complete render, regeneration, verification and archive generation measured 23.00 seconds for Jungle, 21.49 seconds for Dub and 21.73 seconds for Orbital on this cloud JVM. These include repeated rendering and export checks, not single bank generation latency or phone timing. The fixed event counts, fixed hit durations and bounded effect tails constrain work; starter generation is offline.

## Audition and reproduction

The owner-private audition is `/synthetic-kits/` on the existing Spore Station Site. It has three four-bar grooves, three saved-kit kick/snare touch clips and 54 raw pad solos. Groove previews apply one clip-wide headroom scale after pad mixer levels; solos use raw source gain. Nine separate ZIPs deliver Native, SFZ and MPC 3, one complete kit per archive. Every archive is below the Site's 25 MiB per-file limit, with no grid cells dropped.

Generate with `:shell:generateSporeSyntheticKits -PsporeSyntheticOutput=<directory>`. Package with `python3 scripts/build_spore_synthetic_audition.py <directory> <site>/dist/synthetic-kits`. The generator writes PCM hashes and software-gate evidence to `manifest.json`; packaging validates WAV headers, hashes and ZIP integrity. Test waveform oracles are development resources only and are not loaded at runtime.

## Why it is built this way

- Frozen core coefficients and waveform oracles preserve the sound the owner approved while permitting a shared renderer.
- Separate genre layouts and core variations give the shelf musically distinct choices; effects do not supply the identity of the dry Jungle anchors.
- Whole-grid source recipes preserve every velocity and take instead of attaching misleading single-hit patch metadata.
- Fresh, force-shaped strikes make rolls and risers articulated performances rather than repeated PCM copies.
- Separate format archives preserve complete grids within hosting limits and keep hardware acceptance distinct from file validation.
