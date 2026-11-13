# MURMUR round-robin instrument cells

This separate cell format adapts the selected PLUCK round-robin policy into
source-bound finite or held sampler captures. It adds no new voice or preset.
The older selected v1 bank, cell constructor, renderer identity and audio stay
unchanged. This adapter does not yet create a bank or connect a player cursor.

The source is an immutable complete selected model-4 patch. Each saved cell also
owns its exact derived note, absolute native touch (.35, .65 or 1), take (0–3),
release, finite/held output policy and held onset count. Decoding refuses missing
or unknown fields, fractional/nonfinite integers, lossy release values, changed
renderer binding and any source/cell inconsistency. Existing v1 cells and new RR
cells are deliberately separate formats; neither decoder silently upgrades the
other. Release is sampler metadata, not extra synthesis or a baked terminal fade.

The binding names the qualified native implementation parent
`9df2972d2b9b2f189c87b0fb3b995dbf423cb058`, hashes its renderer dependencies and
hashes the new adapter. Its aggregate is SHA-256 of the sorted compact JSON
path-to-SHA256 map. The identity file itself and containing candidate commit are
qualified by external source/gate receipts, avoiding a self-referential hash.
A sealed source identity is not runtime or listening approval. A changed renderer
must get a new format/binding and explicit saved-source compatibility work; do
not relabel old v1 recipes with a current hash.

Finite captures retain the existing house finish. Held captures finish the real
native previous/current pair together, then apply only the existing 220-frame
cosine onset prefix. The loop starts at the current native period, ends at the
full capture length and uses the unchanged seam criterion. Raw current/previous
and finished Float hashes remain separate. Native touch and repeat variation
are not post-render gain substitutions.

The new capture cache owns PCM on insertion and on every read, is keyed by the
complete canonical new cell recipe, and is limited to 16 entries/16 MiB. An
interrupt refuses even a warm result. Ordinary v1 cache contents cannot satisfy
new RR requests, and takes/touches/notes/release/binding cannot alias in this
cache. It avoids repeating expensive offline rendering while preserving saved
provenance. Actual render performance is for the qualified runtime gate to
measure, not a source review claim.

## Verification scope

Authored `MurmurRoundRobinInstrumentSourceTest` contains eight focused tests:
five metadata/cache checks and three native checks, with ten native calls
planned. The native checks compare one finite take0 cell to the existing v1
implementation, cold-replay it, cover three finite touch/register cases and one
STEADY ROOT C2 case, and verify a held take against real adjacent native context
plus cold replay. Three numeric/hash JSONs can be retained through the
`snipsnap.murmur.rr.instrument.measurements` property. No WAV/Float capture files
are generated. See the actual terminal gate receipt for execution results;
authorship alone is not a pass.

This is not exhaustive note/touch/take coverage. It does not add a delivered RR
bank, per-zone player cursor, chord/picker/shelf integration, app package,
listening verdict or physical-device acceptance. Keep every earlier approval at
its original source and scope. Bank/export/import and player integration are the
next product steps after the exact new source gate succeeds.

## Why it is built this way

- A new cell format keeps old saved banks readable with their original identities.
- The full source and take are saved so offline reconstruction cannot silently
  choose another excitation or touch.
- One finished adjacent pair preserves held history; only the onset prefix changes.
- Independent PCM ownership prevents one caller from changing later cached playback.
- Explicit source hashes distinguish reproducibility from a claim that anyone has heard it.
