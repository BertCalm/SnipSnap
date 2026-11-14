# Saved finite MURMUR RR shelf admission

`MurmurRoundRobinShelf` is the separate saved-bank admission/controller layer for
the finite **STEADY ROOT C2** selection. It accepts the canonical kept source's
84 cells: seven roots, three native touch layers and four takes. This is one
PLUCK setting, not four or 84 voices. Admission copies existing files; it does
not render, normalize, change envelopes or infer listening approval.

Each entry lives under `murmur-round-robin-banks/<name>/` with the unchanged bank
in `bank/` and a complete sorted file-length/SHA256 inventory in `admission.json`.
The directory move publishes the whole entry after source consistency, copied
bytes, source recipe, all packet descriptors and the admission seal are checked.
Names never overwrite an existing entry. Cancellation before publication removes
only owned staging files; a completed move remains success. Source/shelf overlap
and symbolic-link traversal are refused. Staging cleanup does not follow links.

The inventory identity travels with `SavedBank`. Listing reports invalid entries
separately. Loading and fixed-take export recheck all saved files, so a stale
handle cannot silently load replacement content at the same name, and changing
a child WAV is detected even when the top bank manifest is unchanged. The four
fixed-take export packets are checked against the exact admitted take inventory
before publication; a failed publication rolls back its new entries only.

All APIs perform blocking IO and belong on an IO dispatcher. Admission limits a
bank to 64 MiB of files, 128 files and 160 nodes, copies through 64 KiB buffers,
and requires 32 MiB remaining free space in addition to the copy and seal.
The existing reference player's 128 MiB decoded-PCM ceiling is also enforced.
Held/HOLLOW and other sources are rejected before their larger grids are read;
they need a separate bounded streaming/chunked design.

This namespace intentionally contains **no ordinary instrument sidecar at its
root**. Its entries do not appear in `InstrumentStore.list`. An explicit
`exportFixedTake` operation makes one ordinary instrument, clearly named R1–R4;
that program remains fixed-take on both the phone's old player and MPC. Automatic
MPC rotation is not claimed.

`loadReference` opens the previously qualified saved-PCM JVM reference player.
Its per-zone/shared-touch rotation, transactional chord admission, global eight
voices including tails, release and reset behavior are delegated unchanged.
The Android/JNI player's current admission API is single-pitch; this change does
not wire a whole RR bank into that player, expose a misleading playable UI item,
or claim Android runtime/device acceptance. App discovery/UI and native whole-
chord playback are subsequent work.

New focused tests cover byte-exact admission, namespace separation, explicit
fixed-take export/reference load, no overwrite, cancellation, mutation at copy
and publication boundaries, stale identities, symlinks/overlap and bounded input.
Their saved PCM is synthetic; native capture/replay and the older 643 package/
player tests retain their separate qualifications and are not rerun by this gate.
