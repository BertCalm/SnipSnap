# Round robin on the phone and the MPC

Owner brainstorm, 2026-11-02/03, with two rounds of ear tests.

## Intent

Drums made in SnipSnap sound **mechanical and flat** when one hit repeats:
the same snare sixteen times in a roll is a machine gun, not a drummer. The
owner hears it on the phone's pads and on the MPC Live III after export, and
wants round robin to fix both: each pad holds several takes, nudged copies of
its one hit, played in turn so no two hits in a row are identical.

The pieces partly exist and do not reach the person playing. `Robin.apply`
renders seeded variants into a chain (one WAV, slice boundaries in
`KitPad.chain`), and the phone's `PadEngine` already steps through a chain's
takes per hit. But round robin is reachable only from the CLI (`snipsnap
robin`) and recipe replay; its variation (±0.7 dB, ±10 cents, ≤2 ms, no tone)
is near the edge of hearing; and on the MPC every exported chain pad plays take
one, because `.xpm` drum pads are written with one layer windowed to slice 0
and `ZonePlay` 1.

## What the owner decided

- **The variation is nudged takes of one hit**, not separately captured takes.
  Take one is always the untouched original.
- **Amount is a continuous dial**, from point C (subtle) to point H
  (deliberately rough), **default F**. The points were chosen by ear (below).
- **Takes per pad are adjustable, 4 to 8, default 6.**
- **Switchable per pad** (the pad's hold menu, beside SHAPE and TUNE) **and for
  the whole kit at once**; the whole-kit switch **skips long loops**.
- **The kit remembers its amount and take count; a pad can override both.**
- **Turning round robin off restores the original pad exactly.**
- **On the MPC, an exported round-robin pad carries 4 takes and cycles them in
  order** (see Export). The phone keeps its full 4 to 8.

### The variation points (ear-tested)

Each take varies four things, each drawn once per take from the pad's seed
and scaled by the amount: level, pitch (a resample, so length follows), a few
milliseconds trimmed off the front, and brightness (a tilt around a 3 kHz
one-pole: blend toward the low-passed signal to darken, add back the
difference to brighten).

| point | level spread | pitch | front trim | brightness tilt | owner's verdict |
|---|---|---|---|---|---|
| C | ±0.14 (≈±1.2 dB) | ±18 cents | ≤3 ms | ±0.15 | low end of the dial |
| D | ±0.22 (≈±1.9 dB) | ±28 cents | ≤4 ms | ±0.30 | "closest, still a bit subtle" (round 1) |
| E | ±0.30 (≈±2.6 dB) | ±38 cents | ≤5 ms | ±0.42 | |
| **F** | **±0.40 (≈±3.5 dB)** | **±50 cents** | **≤6 ms** | **±0.56** | **right, on all five sounds** |
| G | ±0.50 (≈±4.4 dB) | ±65 cents | ≤8 ms | ±0.72 | where it starts to feel forced |
| H | ±0.60 (≈±5.3 dB) | ±80 cents | ≤10 ms | ±0.88 | top of the dial, a deliberate choice |

The dial moves continuously between these points (piecewise-linear between
neighbours is the builder's default). Today's `Robin` spreads (±0.08, ±10 c,
≤2 ms, no tone) sit below C and are what the owner called inaudible.

The ear test used `DrumSynth` kick, snare, closed hat, clap and tom, four
takes cycled in 8-hit rolls; the owner picked F for every type.

## Goals

- A round-robin control on the phone: per pad, and kit-wide.
- The amount dial (C..H, default F) and the take count (4..8, default 6).
- The new variation (level, pitch, front trim, brightness) replacing today's
  subtle-only variant for pads made through the new control.
- The MPC really alternating takes on an exported pad.
- Off restores the original exactly; re-applying with the same settings and
  seed gives the same takes (deterministic).

## Non-goals

- Captured multi-take round robin (recording several real hits); the takes are
  variants of one hit.
- The velocity × round-robin grid (`Robin.apply(zones = …)`). It stays as it
  is; this work is single-zone round robin.
- Random play order. Cycle order only (the owner chose it; `ZonePlay` 2 is
  unproven on drums and can repeat a take).
- More than 4 takes on an MPC `.xpm` pad. Drum pads hold exactly 4 layers.

## Anti-goals

- **No audible difference.** The default must be clearly heard (F, not today's
  amount).
- **Forced variation.** The default must not sound like a different drum;
  anything beyond G is the user's deliberate choice on the dial.
- **Export churn or drift.** A kit with no round-robin pads exports byte for
  byte as today (the goldens). The same pad, settings and seed export the same
  bytes.
- **Losing the original.** Off, undo and the bin must always bring back the
  exact original sample.

## Export

The research (2026-11-03, against the harvested programs in
`reference/golden`):

- `ZonePlay` is the per-instrument play mode in `.xpm`: **0 = cycle**
  (Masada's *Cycle kit*, 16 pads of 2–3 takes, every layer 0–127),
  **1 = velocity** (the Upright Bass's velocity-split layers; SnipSnap's writer
  always writes 1), **2 = likely random** (the Kalimba, full-range layers).
  The FEATURE_PLAN GG4 verdict that `.xpm` has no layer-cycling field is wrong
  and should be corrected.
- Every drum instrument has exactly 4 `<Layer>` slots, in Masada (MPC 1.9.6)
  and in a drum program the owner's own MPC software (2.15) saved.

So an exported round-robin pad, in every `.xpm`-based form (program folder,
expansion folder, `.xpn`, packs): its first **4 takes become 4 separate WAV
files**, one per layer, each layer **0–127**, the instrument's **`ZonePlay`
0**. A pad with fewer than 4 takes writes the takes it has. The MPC plays them
in order, hit by hit. Take one stays layer 1.

Other forms:

- **MPC 3 `.xtd`**: keep the existing Slice Motion path (`sliceIncrement`,
  `sliceCycleLength`); it can name all 4..8 takes. Whether firmware honours it
  is unverified, and the `.xpm` path above is the one the owner plays.
- **DecentSampler** already cycles (`seqMode="round_robin"`); it gets the
  new takes as they are.
- **SFZ, MIDI, others**: the builder's call, matching what each already does
  with chains.

## Constraints

- Kotlin, the existing module split: the variation and its knobs in `:shell`
  (`Robin`), the export in `:xpm` / `:kit`, the controls in `:app`.
- One Gradle at a time per machine; steward laws (declarative commit titles
  with why-bodies, tests never skipped or weakened, the Copy law, the Reversal
  law, `Locale.ROOT` for digits).
- Existing round-robin pads (made by `snipsnap robin` with today's spreads)
  keep working and keep exporting; how they appear on the new control is the
  builder's call.
- Hardware verification is owed: the owner plays an exported kit on the MPC
  Live III and confirms the pad cycles.

## Left to the builder

- The dial's exact curve between the ear-tested points, and how its position
  reads on screen.
- Where the kit-wide switch lives (the KIT screen is the natural home) and its
  copy, with `Personality.kt` as the model.
- What counts as a "long loop" for the kit-wide skip (a pad with `loop` set is
  the floor; a length threshold is the builder's call).
- Whether changing the dial or take count on an existing round-robin pad
  re-renders it in place or asks first.
- How recipe replay stores the new knobs (`Robin.apply` already replays from
  recipes with `takes` / `seed` / `zones`).
- Whether the CLI `robin` verb gains `--amount`.

## Builder check

A fresh reviewer read this as a builder on 2026-11-03. Its questions, and
where each landed:

**Settled as my calls** (the owner can overturn any of them):

- **Separate WAVs on the MPC, not windowed layers.** The writer could point
  four layers at one chain WAV with slice windows, which is less work. But
  only separate files under `ZonePlay` 0 are proven, in Masada's Cycle kit,
  and the owner's one hardware check should test the proven shape. Windowed
  layers stay the fallback if separate files cause trouble.
- **Pads round robin will not touch.** Velocity-layered pads, robin pads with
  `zones`, and chains round robin did not make (captured kits, CHOP FOLD) are
  skipped by the kit-wide switch. The pad switch is disabled on them with a
  line saying why. Off only ever undoes a chain that `Robin` made.
- **The seed stays internal and deterministic.** No re-roll control in this
  work. The stored seed keeps today's `seed*31+slot` derivation so old pads
  render as they did.
- **Old pads.** Pads made with today's 2 or 3 takes show as on, with their
  take count. They re-render under the new variation only when someone moves
  their dial or take count. The CLI keeps accepting 2 to 8 takes.
- **MPC 3 humanize is left alone.** It is a setting the person chose; round
  robin does not change or zero it.

**For the owner:** see "Owner answers" below.
