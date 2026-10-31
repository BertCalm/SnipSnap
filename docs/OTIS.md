# Otis: accepted finite performances

Otis is the accepted Organ, Steam, Blues Harp and Squeeze collection as editable
SnipSnap pad recipes. Its characteristic gesture is **one or more keys open →
shared pressure or cabinet responds → the body moves → a finite release closes**.
The owner accepted the stronger pipe and harmonica direction and preferred Shared;
the restored Squeeze chord draw was accepted on 2026-10-30.

Repository basis: `a5caac7fd6c8a2e6d09ec740c60fb995c6bef368`. The frozen research
sources and listening ledger live in [OTIS_PROTOTYPE.md](OTIS_PROTOTYPE.md). This
landing uses the accepted organ revision, Steam/Harp character revision, and restored
Squeeze draw. The old TONEWHEEL, BELLOWS and REGISTERS patches remain on their own
renderers.

The collection takes its musical direction from the
[XOtis architecture brief](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/otis-engine-architecture.md).
The finite sources are original SnipSnap implementations.

## What ships

- Four picker voices: ORGAN, STEAM, HARP (labelled Blues Harp), and SQUEEZE.
- Six controls per voice and sixteen auditioned finite presets, including the
  harmonica chords and the longer Squeeze draw.
- Full score, source details, MIDI anchor, authoring strength, seed and renderer
  version in every `OtisPatch`. Editing macros or renaming preserves that state.
- Native source velocity, MAKE draft restoration, user-preset replay, CLI overrides,
  a starter kit and a rebuildable MPC program. Pads play once.

The landing stays a finite performance collection. The accepted chords need one
player-pressure trace for the whole score; they are not a stack of independent
single-note renders. This common score contract is why the four voices stay together.
TONEWHEEL is the closest organ sibling, while BELLOWS and REGISTERS cover related
reed roles. Those APIs do not carry this exact finite score and shared source state.

## Controls

All controls are normalized 0..1. The stored profile and full control snapshot
preserve the sound even if a later factory preset changes.

| Voice | Controls after TUNE | Source behavior |
| --- | --- | --- |
| Organ | BARS, CLICK, DRIVE, ROTARY, LENGTH | Registration around the saved bars; contact click; shared amp; still/slow/accelerating/fast cabinet regions |
| Steam | PRESSURE, BREATH, WANDER, SHARED, LENGTH | Sustained harmonic body, finite chuff, bounded pipe drift and common player budget |
| Blues Harp | PRESSURE, BREATH, BEND, VIBRATO, LENGTH | Moving mouth focus, breath articulation, entrance scoop and delayed vibrato |
| Squeeze | PRESSURE, MUSETTE, PULSE, SHARED, LENGTH | Unequal ranks, nasal reed body, shared swell/pitch motion and early upper-reed choke |

TUNE is centered at .5 and transposes the entire score, retaining chord intervals.
The saved MIDI anchor spans 48..72; relative TUNE can reach wider notes, bounded so
every event remains within 36..84. LENGTH defaults to .5 and scales finite onsets
and gates from half to twice their saved timing. It never turns a chord into a loop.
The readout shows the finite gesture duration. Shared defaults to .85; Harp keeps
its Shared setting in the complete profile while its six controls expose bend and
vibrato.

Playing strength scales each event's saved velocity before the source, including
the shared demand. Native velocity layers retain the authored strength ceiling and
score. The sampler owns the later velocity gain. The interaction can change motion
and colour as well as level; no monotonic brightness override is advertised.

## Signal flow and output

```text
saved score + root + strength + controls + seed
  ├─ Organ: nine bars/key → shared amp/cabinet/rotor
  └─ Air: key envelopes → shared pressure (~1 kHz) → pipe/reed sources (4× audio)
       ↓
house band limit → two-stage decimation → causal 20 Hz DC removal + settling
       ↓
unlevelled source comparison OR house level for production → mono 44.1 kHz pad
```

These are musical pipe/reed and player-budget analogies, not physical reconstructions
of steam, a mouth or bellows. The shared state is bounded, and no limiter supplies
the stability argument. Squeeze intentionally admits a small common pitch vibrato
under load, up to 8.2 cents at its default full-load setting and 22 cents at the
control corner. The .40 pressure floor softens the chord without closing its moving
upper reeds. A single reference-strength key and the independent-pressure bypass
retain the accepted unloaded source.

The production source path is checked against the independent frozen audition
renderers. A fixed-coefficient house DC filter removes repeated coefficient work
with the same arithmetic. Production adds the normal house loudness target; source
comparisons remain unlevelled. The lowest Organ stop retains the previously disclosed
20 Hz high-pass attenuation.

## Recipes, scope and performance

`OtisPatch` stores a complete `OtisProfile`: relative note events, air and organ
details, and Shared strength. All JSON boundaries are strict; unsupported renderer
versions and invalid controls fail before rendering. Events and drawbars are copied
into immutable snapshots. Returned audio never aliases the bounded render cache.

The cache holds at most eight results and one million Float samples (about 4 MB),
keyed by every source-affecting recipe field. Names do not alter sound. Cache hits
help re-triggering and unchanged previews; new knob settings still require a cold
render. Sources allocate their arrays per note/render, use bounded harmonic banks,
and check interruption during synthesis. The UI renders off the main thread and
cancels superseded work through its existing interruptible preview path.

Initial Temurin 17 JVM measurements of the sixteen factory scores ranged from
90 to 922 ms per fresh render (median 223 ms); cache hits were 0.045–0.224 ms.
This is one sample per score, with other build work sharing the CPU, rather than a
phone benchmark. The longest Squeeze chord was the slowest. These costs suit offline
pad creation but still exceed the tens-of-ms target for fresh control edits.

Long finite files are filed as LOOP by duration, and short attacks as PERC. Both
remain one-shot pad performances. No periodic HOLD, held keyboard bank, or cross-pad
pressure sharing is claimed: independent sampled pads on an MPC do not share the
offline renderer's pressure state.

## Rebuild and verify

```sh
./gradlew --no-daemon :synth:generateOtisIntegrationKit
./gradlew --no-daemon test
```

The first command creates `testkit/otis-integration`, with sixteen complete `.patch.json`
recipes, production and matched WAVs, a timing/hash manifest, and the MPC program
folder. Use `-PotisIntegrationOutput=/absolute/path` for another output location.
The same bank is available as the OTIS starter kit. CLI examples:

```sh
snipsnap synth OTIS HARP --all --out ./otis-harp
snipsnap synth OTIS SQUEEZE --patch ./squeeze_draw.patch.json --midi 57 --velocity .6 --seed -7 --macros PULSE=.7,LENGTH=.4 --out ./otis-draw
```

`--midi` resets relative TUNE to its neutral; explicit TUNE and MIDI edits cannot
combine. `--instrument` is unavailable for this finite collection. The exported
sidecar preserves the complete score and source details after every override.

## Evidence and limits

- **Heard:** the owner accepted the source directions and restored Squeeze draw.
- **Verified:** current registry, picker, draft, preset, velocity, kit and CLI APIs;
  complete recipe state and independent frozen source references.
- **Measured:** source null/replay checks, normalized control changes (CLICK over its
  attack window), finite format/peak/DC checks, native velocity, cache isolation and
  classifier/duration probes. The generator records cold/cache-hit JVM timings.
- **Proposed:** no new factory sound shapes are introduced in this integration.
  The new control ranges are bounded engineering mappings around the accepted sources.
- **Limits:** fresh renders exceed the house's tens-of-ms target; a cache hit is
  not a new-setting benchmark. JVM timing and compilation do not
  establish phone latency, device playback, or MPC hardware behavior. Selected
  harmonic-fold probes are not a complete alias certificate.

## Why it is built this way

- A complete score keeps a joining key's effect on an already-held reed or shared
  cabinet. Independent source renders would lose the accepted chord interaction.
- Saved source details protect the accepted sound from future preset edits. The
  six controls edit that recipe without reconstructing it from a name.
- LENGTH states the finite behavior honestly; a new periodic loop would need its
  own seam and listening evidence.
- Frozen reference renderers make the port testable by audio rather than by matching
  coefficient names. The output cache returns copies so later edits cannot poison it.
