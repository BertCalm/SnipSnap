# MURMUR saved round-robin bank

This opt-in format carries the qualified `MurmurRoundRobinInstrumentSource`
cells through package storage and the JVM reference player. It changes no
existing source, old selected-bank v1 reader, saved renderer identity, factory
recipe or accepted audio. The three selected source settings remain one PLUCK
voice. Four takes and three native touches are dimensions, not new voices.

`MurmurRoundRobinInstrumentBank.writeNew` creates one new directory containing
`murmur-round-robin-bank.json` and four fixed-take package directories. Each
root/touch/take WAV appears once. Roots include the selected domain edges,
every third semitone and the authored root; midpoint zones transpose at most
one semitone. Native touch values remain absolute .35/.65/1, mapped to MIDI
velocity windows 1–42/43–84/85–127. The authored source touch stays in the
complete source snapshot. Take indices 0–3 preserve the exact new cell codec,
renderer binding and excitation policy already qualified in the parent.

Each fixed-take child has its own `MURMUR_RR_FIXED_TAKE_PACKAGE` metadata,
explicit take coordinate, three-layer phone sidecar, native XTY and legacy XPM.
The bank manifest names all four program identities and binds their ordered
recipe and WAV hash lists. Validation checks exact root and child closure,
complete unique ordered cell grids, executable recipes, raw/finished/decoded
hash fields, actual WAV hashes and mono PCM24 bounds, sampler roots/loops,
source domains, release, and byte-exact descriptors. It refuses unknown,
missing, fractional, relabelled and duplicate dimensions, symlinks, changed
samples and undeclared files. These hashes prove internal consistency; only
independent native replay can establish native authorship for a delivered bank.

Production captures stream serially into private staging. Complete validation
precedes one new-directory move. Cancellation or validation failure removes
only private staging. Existing destinations are refused. `exportFixedTake`
uses the existing three-entry shelf transaction and rollback semantics, copying
one exact already-admitted child with its provenance and no rendering.

## Supported export meaning

The four MPC programs are fixed takes. Neither XPM nor XTY here claims to
rotate automatically. Each contains three nonoverlapping native touch layers,
not twelve overlapping layers. The unchanged generic formats explicitly reject
that unsupported flattening. A supported automatic MPC RR representation and
physical-device checks remain separate work. An exported fixed-take phone
sidecar also plays that fixed take in the current generic player.

## Saved-sample reference player

`MurmurRoundRobinPlayer.load` admits the complete bank, reconstructs routing
from verified rows and rechecks each decoded PCM hash while loading owned
samples. It has no native synthesis callback. Loaded PCM is limited to 128 MiB;
that is a reference-player bound, not an Android memory qualification.

One cursor belongs to each pitch zone and is shared across native touch levels.
An accepted attack advances that zone modulo four. Different zones advance
independently; two pitches in one zone consume successive takes in caller order.
Reload and `allOff` restart all cursors at zero. `resetRoundRobin` resets sequence
state without cutting or retargeting already sounding voices. Velocity zero,
nonfinite/out-of-range velocity, invalid notes, duplicate chord tones and a
chord larger than eight are refused without advancing any cursor. Whole-chord
preflight reserves capacity before any voice starts.

Four persistent reference engines preserve earlier take tails. Admission allows
at most eight active voices in total, with released tails counted until rendered
out; a full player refuses new attacks instead of stealing them. `noteOff`
reaches every prior take of that pitch, and `allOff` releases every engine.
The combined stereo bus clamps to full scale. Rendering is bounded to 8192
frames per call. This is a JVM reference implementation, not a claim about
Android JNI queues, the phone bus, physical-device playback, or an installed
MAKE/INSTRUMENTS/KEYS route. Android integration remains open.

## Verification scope

`MurmurRoundRobinInstrumentBankTest` supplies twelve new contracts using short,
explicitly synthetic 512-frame finite or 1024-frame held fixtures. These exercise
transport, canonical admission, complete grids, fixed-take exports, rollback,
old-v1 separation, saved sample selection, chord preflight, live gain, cross-take
tails and key-up. Synthetic repeated held test cycles are not native-history
proof. No unchanged parent native gates are repeated. Actual compilation and
terminal test outcomes belong to the source-bound external continuation receipt;
this document does not assert a test pass.

Next: produce a bounded bank from actual qualified native captures; independently
replay and check delivered artifacts; integrate the explicit RR format into the
Android instrument route; then publish source-bound owner listening. Existing
heard v1 bank audio and prior owner/device approvals retain their exact scopes.
