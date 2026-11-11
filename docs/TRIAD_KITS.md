# TRIAD kit completion trial

Built on `2ff3e5a1c6c220a4b2475f0ac756a046e7b800c4`. The accepted R2 conga/bongo synthesis and the original shared-bus groove composer are unchanged. This is a drum starter family and finite phrase composer, not a melodic Patch engine.

Three shelf entries: **Warehouse Techno (128 BPM)**, **Submerged House (118 BPM)** and **Fracture Minimal (126 BPM)**. Genre profiles change tuning, sub length, clap length/tone, open-hat length and the two shared-bus phrases. Each has 16 pads:

1. Thud
2. Sub
3. Clap
4. Open hat
5. Closed hat
6. Shaker
7. Conga
8. Bongo
9. Rim
10. Low conga
11. High bongo
12. Clap roll
13. Snare roll (kit revision 2; replaces the alternating hand roll)
14. Shaker riser
15. Full groove, two bars through the genre's shared bus
16. Percussion break, two bars through that same bus

Pads 1–14 are dry, playable source material. Pads 15–16 are printed bus performances. Summing separately treated one-shots cannot reproduce a compressor responding to simultaneous kick, sub and percussion, so the shared-bus gesture is retained as a phrase. Hats share choke group 1; the two phrase pads share group 2. All pads are finite one-shots with natural releases. No seamless HOLD loop or STRUM is claimed; melodic/keygroup/chord gates do not apply to this drum family.

Every pad has three rendered velocity zones and four distinct deterministic takes in each zone. Hand velocity changes the contact synthesis; other hits change appropriate source controls and contact brightness. Phrase velocity changes the signal entering the nonlinear bus. The rolls accelerate from eighths through sixteenths to thirty-seconds over one bar; the shaker build increases grain/brightness and strike strength.

Revision 2 validation: 75 focused tests passed (Gradle exit 0), all 576 exported slices verified again, and the other 45 pad WAV chains matched their previous SHA-256 hashes exactly.

Revision 2 replaces only A13 with a short snare and velocity-sensitive wire snap, retaining the one-bar acceleration and all three velocity zones/four takes. The previous hand roll remains on the audition page for comparison.

## Persistence and export

`ArrangedPad.velocityTakes` feeds the existing `ChainInfo.zones` representation: one WAV containing twelve slices, with each velocity zone pointing to its own four-take group. `KitStore` saves the grid, and `PadHit.resolve` selects the velocity zone and cycles the take. Original soft-layer and single-zone-chain callers retain their paths.

The audition generator checks all 576 rendered slices after PCM24 export and reload, resolves every zone through two full playback cycles, and reads the actual exported MPC 3 track to verify velocity boundaries, base slices, cycle lengths and initial frame windows. The exported chain audio is checked against the reloaded native kit, and the exported track is parsed independently. MPC export intentionally omits editable-history sidecars for Slice Motion grids; importing that track back into SnipSnap is not a native-kit round trip. The native saved kit retains its full grid. The saved source metadata carries genre, role, kit version, hand revision, BPM, velocity levels and take count. This is a rendered bank, not an editable Patch recipe. Changing the bank implementation requires a kit-version increment; existing saved WAVs remain playable.

**MPC 3 is the complete export target.** MPC 2's current grid projection retains velocity switching but only the first take in each zone. The trial therefore offers MPC 3 tracks, not an MPC 2 twin described as complete. Device playback remains a listening gate, not something a software field check can certify.

## Verification ledger

- Heard: Josh accepted hand-drum revision 2. The new kits, rolls and riser await listening.
- Verified by source: three shelf entries, finite articulation, versioned source provenance, existing grid persistence and playback path.
- Automated checks: the focused Gradle run passed 31 tests (exit 0), including TRIAD, hands, grid assembly, shelf placement, playback and export. The generator checked all 576 slices. Independent Python checks confirmed distinct takes, monotonic mean energy across all velocity zones, bounded PCM and bit-identical FLAC previews. The full repository regression was started separately and is still pending at publication.
- Not claimed: hardware playback, Android compilation without an SDK, seamless looping, melodic keygroups or chord-tool support.

The kit builder renders on demand, once per starter creation; pad playback reads the persisted WAV slices and performs no synthesis. Phrase synthesis is deliberately an offline operation. The audition generator records the integration proof for all three banks; it does not imply that a 192-slice bank is created within the single-hit render budget.

## Why it is built this way

- The hand-drum body and contact model Josh accepted remain untouched; kit touch and variation are added around it.
- Velocity changes the source and the bus input, so a soft strike has a different texture as well as a lower level.
- Four takes within each zone prevent repeated hits from always selecting identical audio while retaining deterministic saves.
- Phrase pads preserve interactions between overlapping drums; the individual pads remain available for new patterns.
- Finite authored builds provide useful percussion gestures without promising a loop seam that was never designed.
