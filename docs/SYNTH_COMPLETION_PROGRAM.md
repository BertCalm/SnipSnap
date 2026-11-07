# Synth completion programme

Owner standard agreed 2026-11-07. Initial repository baseline:
`cc47036c43e36d3eca78bd6e70cb15994b08d469`.

The coordinating inventory contains 53 handoff workstreams and 87 registered
engines. They overlap. Neither number means that many unfinished engines.
Keep original listening approvals and completed milestones; assess the expanded
product requirements separately. The external master handoff also preserves
unmerged studies, source patches, nine listening waves and branch-only SKIRL.

## Completion contract

| Applicable scope | Required result |
| --- | --- |
| Melodic engine | At least six distinct voices, each with presets; playable keygroup instruments, a premade kit and chord-tool compatibility |
| Melodic extended articulation | Appropriate HOLD; picked/thumbed strings use STRUM instead of repeating the ordinary pluck, unless repetition is an explicitly documented part of that voice's character |
| Percussion extended articulation | Authored roll/riser, not identical repeated hits; some percussion voices may remain finite |
| Drum synth | Multiple distinct genre kits available on the shelf |
| All synth instruments | Round robin and velocity levels through the applicable renderer, recipe, player and export paths |

Classify mixed engines by voice. Do not impose the six-voice rule on an effect,
processor or kit utility. Count musical identities separately from presets,
source revisions and articulations. Registered enum counts are evidence for this
review, not automatic proof of six distinct musical voices. There is no agreed
quota for presets per voice, RR takes, velocity layers or individual genres.

Use separate evidence states: **verified**, **partial**, **missing**,
**not applicable**, **not yet audited**. Engineering checks do not establish that
Josh has heard or accepted a sound. An earlier acceptance is not erased by a new
delivery requirement. Do not require an unrelated new hardware approval when an
applicable owner/device decision is already recorded.

## Shared-path findings

These are source findings on the baseline above, not fresh playback measurements.

| Path | Evidence and implication | Work package |
| --- | --- | --- |
| Drum RR | `shell/.../Robin.kt` has deterministic chains and a separate velocity × take grid. `RobinKit.refusal` excludes layered, tonal/note-keyed, loop and long pads from the kit-wide control. `ArrangedPad` disallows soft variants together with takes. Existing RR does not satisfy the all-instrument requirement. | SC-02: preserve velocity zones and roots while adding supported take selection; define format limits explicitly |
| Native repeated strikes | `synth/.../Restrike.kt` implements source re-strikes for struck SKIN voices and TERRA; other paths can vary rendered audio. | SC-02: record native versus audio variation per voice; preserve deterministic seeds and original take |
| Keygroup RR | `InstrumentStore.Zone.layerFor` selects the first velocity match; `KeyHit` has no take counter. `Keygroup` holds up to eight velocity layers. Stacked zones are simultaneous components, not round-robin takes. | SC-03: versioned take identity, selection/reset, recipe replay and supported export mapping, including stacked instruments |
| Velocity | `Velocity.touchedRender` exhaustively routes engines to native touch or the brightness/filter fallback. `InstrumentStore`, `KeyHit` and `InstrumentEngine` support velocity layers. This does not prove every engine's authored keygroup uses them or that touch is musically useful. | Per-voice audit and native touch listening; verify sampler gain is applied once |
| Chords | `ChordStabs` authors STOOP CHORD patches; `PhotoChord` authors SNAP pads. `Spread.detect(snip, patch)` preserves authored roots for an explicit subset and otherwise uses pitch detection. Generic chord support for every melodic voice is not established. | SC-04: trace root, range, voicing, play/replay and export across the actual chord/arp tools |

Do not add independent RR implementations to dozens of engines before these
shared contracts are settled. A pitch nudge that detunes a chord is not an
acceptable substitute for a controlled variant of a melodic voice. A format
that cannot preserve an RR × velocity arrangement must not silently discard one
dimension. Existing saved recipes and legacy export bytes need compatibility
coverage as these paths change.

## Execution packages

| ID | Package | Exit evidence |
| --- | --- | --- |
| SC-00 | Compiled voice/factory inventory, followed by voice-role and delivery audit | All registered engines enumerated; uncovered voices explicit; counts do not imply listening acceptance |
| SC-01 | EROSION accepted-bank factory, picker and starter integration | Exact eight recipes; three native touch zones; editable saved patches; MPC 2/3 software read-back; JVM and Android checks recorded |
| SC-02 | Drum/tonal pad RR with velocity | Controlled variant identities and root retention; reproducible replay; UI, player and export agreement |
| SC-03 | Keygroup RR with velocity | Per-note selection/reset and note-off behavior; stacked-component agreement; versioned store and supported-format round trip |
| SC-04 | Melodic chord/arp compatibility | Authored roots and range handling, voicings, performance, save/replay and export verified per applicable voice |
| SC-05 | Six-voice expansions and articulation revisions | Small mechanism-specific audition proposals, then accepted voices, presets, instruments and kits |
| SC-06 | Drum genre shelves and final packaging | Distinct arrangements with RR/touch support, shelf discovery and export checks |

SC-01 is integration of previously accepted audio. It does not complete EROSION's
new genre/RR requirements or add a held articulation. Its eight recipes are three
registered percussion voices, not eight melodic voices. TUNE is relative, so the
starter must not claim a musical key. The accepted finite DSP remains unchanged.

Independent source recovery continues alongside these packages: preserve SKIRL's
branch and recover cloud-only bundles before attempting integration. Preserve
OSTERIA/TRIO patches and existing source restrictions. Do not blindly merge old
cloud branches or retire them based on this tracker.

## Listening cadence

Keep the existing nine-wave queue. The first listening group is NEURON Z1,
APSIS GRAIN/ANCHOR, OSTERIA, MURMUR and OBLITERATE, each with its own outstanding
question and pinned artifact. These are a mixture of synths and processors;
their completion criteria differ.

Each new delivery should contain a short audition index, the question for each
voice, dry and relevant articulation/touch comparisons, complete recipes, source
identity, measured checks and the unresolved verdict. Josh can return
**keep / tweak / skip** with a short note per item. While an audition is awaiting
feedback, advance independent engineering during active sessions. No automatic
background service is configured.

## Reproduction and evidence

```sh
./gradlew --no-daemon :synth:generateCompletionInventory
```

The inventory goes to `build/reports/synth-completion/inventory.json`; set
`-PcompletionInventoryOutput=/absolute/path/inventory.json` to retain it elsewhere.
It reads compiled sealed Patch implementations, their voice identities and the
factory dispatcher. It records the base commit and dirty working-tree status.
Control-name presence is only a review lead; it cannot establish a useful HOLD,
STRUM, roll, riser or velocity response. No sound is rendered by this command.

For SC-01, run the EROSION core/bank/preset tests, `PresetsTest`, the EROSION starter
test, shelf/catalogue/convention checks, and the full JVM gate. Compile Android
picker changes with an SDK. Record results in the implementation handoff rather
than treating historical test results as a current pass.

## Why this order

- Shared take and velocity handling affects the whole roster, so one tested
  contract prevents dozens of incompatible implementations.
- Integrating the accepted EROSION bank produces a usable result without asking
  Josh to approve the same sounds again.
- Voice counts identify missing scope; mechanism and role review prevents filler
  presets from being relabelled as new voices.
- Listening in small groups keeps sonic decisions focused while unrelated
  engineering continues in this coordinating thread.
