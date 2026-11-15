# Velour A12: selected B ride

The owner heard the previous A12 as tambourine-like and selected **B / Tighter
body** from the Round 7 reference comparison on 2026-11-15. The four published
hard-strike comparisons (dry, Close, Velour, Bloom) remain byte-identical after
expanding B to three velocities and four takes. Those additional layers/takes
extend the selection; they are not separately claimed as owner-heard.

The previous SKIN noise-bank ride is replaced only at A12. A15 tambourine and
all other pads retain their approved PCM. The normal shelf loads take one of
B's three velocity layers, preserving ordinary velocity-layer MPC delivery.
The optional round-robin grid loads all twelve B strikes per treatment. Full
MPC cycling remains an open acceptance gate; this change does not remove the
format limits documented in VELOUR_ROUND_ROBIN.md.

## Sound and provenance

This is the existing Kotlin `BreakerCymbal` fitted ride, read at
`745dca55fd84e47d3532ef68a8ac4c2a8f82cfd2`: 400 measured modes and a separate stick
burst. B uses damping 1.15, no pitch shift, a 10 kHz source low-pass and the
retained Round 5 Python board with ride send 0.18. The four takes use the model's
independent modal phases, amplitude variation and contact noise. It is fitted
resynthesis, not a new physical simulation or four acoustic recordings.

Reference: Virtuosity Drums (Versilian Studios and Karoryfer Samples), performed
by Austin McMahon, CC0-1.0, upstream commit
`9f04cf9a734527edfbb0a4eee1f674e45bbf71bc`. The license, reference URL, source
commit and file hashes ship under `velour/ride-b-v1/`. The selected hard-preview
hashes are pinned in `scripts/import_velour_ride_b.py`.

The bank freezes the exact PCM16 listening data decoded from lossless FLAC.
Native kit exports can store this at 24 bits without creating additional detail.
There are 36 WAVs: three treatments × three velocities × four takes. All were
checked for finite output, DC, headroom, strictly increasing velocity energy,
distinct takes after gain removal, and no more than 25 ms of digital silence at
the end. The cymbal's actual ringing decay is intentionally longer than the old
noise burst. Audio is already printed through the board and must not run through
another board on load.

## Reproduce and verify

The Site source holds `source/round7/RideBridge.java`, `render_selected_b.py` and
`verify.py`. The bridge calls the existing Kotlin model; the Python step retains
the auditioned board. `scripts/import_velour_ride_b.py <site-checkout>` decodes the
FLAC deliveries into JVM resource WAVs and pins their hashes.

`VelourRideBankTest` checks all 36 hashes and windows, ownership of PCM buffers,
B take-one selection, and every nonride shelf and round-robin sample against the
unchanged sources and variation function. The existing Velour starter and
round-trip tests exercise saving, every MIDI velocity, nine-hit selection,
SFZ round trips, and standard MPC velocity exports. Test execution results are
reported separately; no pending full regression is claimed as passing.

## Why it is built this way

- A fitted cymbal body and independent stick attack address the rejected timbre.
- Frozen delivery preserves the selected hard sound without another DSP port.
- Standard kits keep velocity layers; the opt-in grid keeps all four takes.
- The correction is confined to A12 and retains the approved tambourine.

Focused validation on 2026-11-15: seven tests passed across the five Velour
classes (`VelourRideBankTest`, `VelourApprovedKitsTest`,
`VelourRoundRobinKitsTest`, `VelourApprovedStarterTest`, and
`VelourRoundRobinRoundTripTest`). The independent generated-package audit passed
all 576 PCM windows, 48 native chains, and three DecentSampler grids. The
separate standard MPC 2/3 read-back checked all 48 velocity samples per kit.
Full repository regression and physical MPC listening/cycling are separate
checks, not implied by these results.
