# Shared delivery audit — next implementation batch

Read-only source audit at `57b9a17456d0e7416939f62cadd44270f8cd0d2d`,
7 November 2026. These findings define SC-02/03/04 work; they are not new
playback or hardware test results.

## Round robin and velocity

| Path | Existing behavior | Work still required |
| --- | --- | --- |
| Pad authoring | `Robin.apply(..., zones=...)` creates a velocity × take chain. `ChainInfo`/`ChainZone` retain boundaries, velocity windows and per-zone cycles. | Preserve already-authored native layers, original recipes, roots and exact anchor samples. The kit-wide control currently refuses layered, tonal and loop pads. Extending eligibility alone would bypass those safeguards. |
| Variation source | `Restrike` supports physical re-strikes for struck SKIN and TERRA; general variants can process rendered audio. | Record whether an engine supplies native alternate excitation or a musical audio variation. Preserve pitch for melodic material and keep takes deterministic. Do not claim every engine has native re-strikes. |
| Phone pad sequence | `app/.../PadEngine.kt` increments `hits[pad.slot]`; `PadHit.resolve` uses that index modulo the selected zone's cycle. | Reconcile with per-zone sequence behavior below. Interleaving velocities can produce different take order from the exported/JVM-preview contract. Advance/reset policy and unsuccessful-note behavior need explicit tests. |
| JVM pad preview | `kit/.../KitPreview.kt` counts by `slot * 1000 + zone.baseSlice`, so each velocity zone has its own sequence. | Share one documented selection contract with phone playback, rather than maintaining two different counters. |
| MPC 3 drum export | `Mpc3TrackWriter` writes grid-zone slice anchors and cycle lengths. | Add read-back coverage for those exact fields. `ExportReadBack` currently emits SKIP for a chain's slice zones; an otherwise unblocked export is not proof that RR × velocity survived. Physical playback remains its own evidence. |
| MPC 2 drum export | A single-zone RR chain can become full-range cycle layers. A velocity grid uses each zone's anchor take in XPM; it does not retain the full RR dimension. | Report the representation limit explicitly and retain a complete supported primary delivery. Do not label anchor-only compatibility output as full round robin. |
| SFZ / DecentSampler drum export | Writers already emit per-zone sequence regions/groups for chain grids. | Reuse and verify this path; avoid replacing an existing supported representation. |
| Keygroups | `InstrumentStore` versions 1–3 store velocity layers and optional stacked components. `Zone.layerFor` chooses the first velocity match; `KeyHit` has no alternate-take selector. | Add versioned take identity and note-on selection shared by reference/phone playback. One key press must select coherent components; note-off must release exactly those voices. Stacked components are simultaneous, not RR takes. Determine each export's supported representation before promising parity. |

Concrete regression to add in SC-02: with two velocity zones and two takes,
play **soft, hard, soft, hard**. Independent zone counters select
**soft-0, hard-0, soft-1, hard-1**. The present phone per-pad counter instead
selects **soft-0, hard-1, soft-0, hard-1**. This is a source-derived example;
it has not yet been run on a phone. Preserve the existing single-zone sequence.

Representative acceptance cases for the shared change:

- EROSION's accepted three-touch kit: every zone's original sample remains exact;
  alternate excitation/treatment must retain each voice's identity.
- A picked melodic voice: stable root and tuning, finite keygroup plus appropriate
  STRUM; RR must not turn the chord out of tune.
- A sustained, stacked melodic instrument: coherent component selection, held
  loop/release behavior, repeated notes and voice stealing.
- A drum kit: authored dynamics, independent take cycles, deterministic replay,
  supported-format read-back and genre arrangement.

No universal number of takes or velocity layers is assigned here. Existing
implementation limits are not new owner requirements.

## Chord delivery

`ChordStabs` and `PhotoChord` use specific STOOP/SNAP sources. `Spread` preserves
authored roots only for an explicit subset, otherwise using pitch detection.
The next audit must follow the actual chord/arp entry points through source
selection, root/range, voicing, simultaneous notes, persistence and export.
Already-chordal voices need an explicit role so chord tools do not accidentally
stack a complete chord on every requested chord tone. A TUNE control alone is
not evidence of this capability.

## Implementation order

1. Reconcile pad sequence selection and add the interleaved-velocity regression.
2. Preserve authored velocity layers and recipe/root provenance through RR edits;
   implement and verify slice-grid read-back.
3. Add versioned keygroup take selection and supported export mapping.
4. Audit chord/arp delivery, then apply the shared paths to engine packages.

Keep these changes in a separate package from the already-committed accepted-bank
EROSION integration. Batch the broad regression gate after the package's focused
behavioral checks, rather than repeatedly running the full suite per engine.
