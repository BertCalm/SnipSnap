# Reference kits

**MPC 3 on the Live III is the only target.** Everything MPC 2 moved to
[Backlog](#backlog-mpc-2) — not deleted, not broken, just off the critical path
until there is hardware to verify it on.

One clarification before the backlog reads as bigger than it is: the `:xpm`
module is still the **only thing that produces loadable output today**, and MPC
3 loads MPC 2 content — that is Akai's own documented interop route. So `:xpm`
stays live as the shipping path. What is deprioritised is *verifying it against
MPC 2 hardware*, which nobody here owns.

## The one file that matters

A drum program saved by the **Live III**.

Akai's authoring tools write `.xtd` / `.xty` — gzip, an ACVS header whose object
type is `SerialisableTrackData`, then JSON, with a sibling
`<name>_[TrackData]/` folder of WAVs. Four real examples are already in
[`golden/mpc3-track/`](golden/mpc3-track/) and the schema is written up in
[`../docs/MPC3_FORMAT.md`](../docs/MPC3_FORMAT.md#the-standalone-program-container--answered).

What those four files cannot tell us is whether **firmware** writes the same
thing, and — more usefully — what any of the optional fields mean. Akai's
factory content leaves nearly everything at default, so a stock kit is almost
useless for the questions that are still open.

## What a good reference save looks like

The variation is the whole point. A kit saved with every value at default
confirms structure we already have; a kit with a handful of values deliberately
pushed off-default closes real questions. ~5 minutes.

1. On the Live III, start a new project and add a **Drum** program.
2. Assign a sample to **all 16 pads of bank A**. Any samples — factory content
   is fine. All 16 matters: a partially-filled program shows how the empty-slot
   encoding behaves against a full one.
3. Now make it *un*-default, because each of these answers something specific:

   | Do this | Because |
   |---|---|
   | Give 3–4 pads **distinct colours** | No per-pad `colour` key exists in any harvested file, though `padsFollowTrackColour: false` implies one should. Settles whether it's absent from the schema or just never set. |
   | Nudge **tune** on two pads (say +3 and −5) | Real values exist now (MPC 2 `TuneCoarse` −4…+2, MPC 3 `fineTune` −10), but never both fields in one program. Confirms signed semitones for coarse; `fineTune`'s unit is still open. |
   | ~~Set one pad to **Note Off**~~ | Done — all three `triggerMode` values are now observed on filled pads, `1` on snare rolls in Pro Studio Kit 3. Nothing left to check here. |
   | Change **level** and **pan** on a couple of pads | Confirms which of the several `pan`/`volume` fields is load-bearing, and which dialect firmware writes. |
   | **Trim** one pad's sample start/end | `sampleEnd` reads `0` and is ignored in favour of `sliceInfo.End`. Proves that's universal, not a quirk of untrimmed factory content. |

4. Save the program (`Save As` → `SnipSnapRef`) to SD or USB.
5. Copy the resulting file off the card — whatever extension it comes out as.

**Then save it a second time with one single value changed** (say pad A05's
tune). Diffing two near-identical files is still the fastest way to locate a
field with certainty, and it is the only technique that works when a field's
name is misleading — which this format has already proven it can be.

### If keygroups matter too

Velocity layers are no longer the open question — real 8-way splits turned up in
Acoustic Drum Tools, Percussion Tools and MPC Upright Bass, in both containers,
and are written up in
[`../docs/MPC3_FORMAT.md`](../docs/MPC3_FORMAT.md#velocity-layers--confirmed-with-real-examples).

What a hardware-saved keygroup would still add is firmware provenance, and one
specific unknown: **no round-robin selector field has ever been located.** The
Ambient Box drives round-robin through `SliceIncrement` /
`SliceIncrementRngSeed` / `SliceCycleLength` on layers that all span `0–127`,
but nothing observed says "cycle these" rather than "switch on velocity". Build
a keygroup with two zones, two velocity layers, and a round-robin pair, and the
diff answers it. Drop it in `golden/liveiii-36/` beside the drum save.

## Where to put them

```
reference/golden/
├── liveiii-36/          ← what you save. <device>-<firmware>
├── mpc3-track/          ← harvested from Akai/F9 expansions (done)
└── expansion/           ← first-party Expansion.xml (done)
```

Name the folder `<device>-<firmware>` so we can tell later which file came from
where — that matters when firmware changes the format under us.

**No audio.** Don't commit the WAVs or the `_[TrackData]/` folders; they're
large, they're Akai factory content, and the writer doesn't need them. The
`.gitignore` in `golden/` enforces this by extension — and note it only catches
audio *extensions*, so check anything without one before committing it.

## Already harvested

See [`golden/README.md`](golden/README.md) for provenance and the extraction
recipe. Two things came out of two commercial expansion installers:

- **`golden/mpc3-track/`** — two Akai drum tracks and two F9 instrument tracks.
  These established the container, the drum schema corrections, and the keygroup
  model.
- **`golden/expansion/`** — five `Expansion.xml` files across both dialects.
  They looked like they contradicted `ExpansionWriter` until the fourth
  arrived; they don't. Akai's `version="1.0"` + `<directory>` form is what
  **desktop-installed** content uses, and the `version="2.0.0.0"` +
  `buildVersion` + `<local/>`/`<priority>`/`<description>` form is what
  **standalone** packs use. We emit the standalone form, element for element,
  in order. See
  [`../docs/MPC_EXPORT.md`](../docs/MPC_EXPORT.md#two-dialects-and-we-write-the-right-one).
- **`golden/keygroup/`** — three real MPC 2 keygroup `.xpm` programs from a
  commercial 2026 standalone pack. `KeygroupWriter` had never seen one.
- **`golden/drum/`** — a 2017 drum `.xpm` plus the internal listing of the
  `.xpn` it shipped in. The listing shows real `.xpn` archives are **flat**,
  which `XpnPackager` is not; the program carries **real pad colours**, which
  nothing in this project had ever seen.

## The other half: does our output load?

Separately from harvesting references, the writer's output needs to survive
contact with hardware — and on the Live III this is testable **today**, since
`:xpm` output is MPC 2 content and MPC 3 loads it:

1. `gradle :xpm:test` — proves no accidental drift
2. Generate a 16-pad kit with real WAVs beside it
3. Load it on the **Live III**
4. Confirm all 16 pads fire, on the right pads, at the right pitch, with the
   hat mute group choking

If the kit comes up **shifted by exactly one pad**, that's the instrument
numbering base — flip `XpmWriter(instrumentBaseIndex = 1)` and it's solved.
That question is still live even with MPC 2 deprioritised, because it shows up
in output loaded on an MPC 3.

## Backlog: MPC 2

Parked until there's an MPC One or Live II to test against. Nothing here is
wrong or broken — it is unverified, and staying unverified is an accepted cost
while MPC 3 is the only target.

- **A drum `.xpm` off MPC 2 hardware.** The MPC 2 XPM structure came from a
  program saved by standalone firmware 2.9.1.2 (see
  [`../docs/XPM_STRUCTURE.md`](../docs/XPM_STRUCTURE.md)) and `:xpm` writes it
  today — but that structure is second-hand. A real export would turn every
  open question in [Unverified](../docs/XPM_STRUCTURE.md#unverified) —
  instrument numbering base, gap handling, version header, element naming —
  from a debate into a diff. Goes in `golden/one-2x/` or `golden/liveii-2x/`.
- **A keygroup `.xpm` off MPC 2 hardware.** Much less urgent than it was.
  `KeygroupWriter` was built from second-hand vocabulary and had never seen a
  real keygroup program; [`golden/keygroup/`](golden/keygroup/) now holds three,
  so its *structure* is referenceable. What a hardware save would still add is
  firmware provenance — those three are one vendor's output, and a vendor can
  be idiosyncratic without being wrong.
