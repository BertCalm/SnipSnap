# Alloy body-linked MATERIAL comparison

This is an opt-in comparison of Rounded Plug, Long Prism and Velvet Rise using
the unchanged Kotlin ALLOY model-1 renderer at SnipSnap commit
`b04496ece7770903549c23ea913bad90bb57f45e`. It does not change the released bank,
old saved-patch fallback, engine arithmetic, or patch schema.

The owner rejected the earlier wood-only study: Long Prism and Velvet Rise had
no audible difference for him, and Rounded Plug was barely different. Its small
waveform/spectral differences did not establish a useful control range.

## Proposed response

The characteristic gesture remains contact -> tonal body -> release or a real
settled HOLD cycle. The new proposal changes the tonal body's harmonic balance
on the lower half of MATERIAL, as well as the generated contact. It alters the
existing four operators' output gains, sustain and decay, route depths, wood
loss/gain balance and output cutoff. All operator/source ratios, the original
global attack/duration/release envelope and the held period stay fixed.

`model.py` is the complete proposed pre-render mapping. At MATERIAL >= .5 it
returns the exact original configuration without interpolating any fields. At
lower positions the original weight is `(2 * MATERIAL) ** power`, with power 2
for Rounded Plug and power 1 for the other two roles. Numeric fields interpolate
from the authored left endpoint to the original. Routes use the union of edge
pairs with absent depth zero; the original exact route list returns at .5.

The bass square law addresses the product of operator gain and sustain: linear
parameter interpolation crowded most measured spectral change near zero. Its
25% body-colour differences from 0% and 50% become approximately .171 and .093,
instead of .246 and .017. The endpoints and approved tone are unchanged.

At .5 the approved original tone is retained. Above .5 the existing air/breath
direction is unchanged. At zero, the proposals are a woody harmonic bass, a
fundamental-led wooden key with a hollow sustained third, and a reedier/hollow
soft body retaining Velvet Rise's slow swell. These names describe authored
intent, not a claim that the owner has heard or approved them.

## Important implementation boundary

This mapping is **not installed in SnipSnap**. Every listening position is a
complete, independently replayable model-1 snapshot. Loading one snapshot and
turning the current live MATERIAL knob does not apply the proposed body-map
changes. A future accepted implementation must explicitly add an opt-in mapping
while preserving old complete saves and missing-config fallback. The new map is
external authoring code for an audition, not a hidden alteration of the engine.

## Rendering and verification

Use Java 17 and the existing compiled JVM module classes. The recorded workspace
classpath is in `classpath.txt`; adjust checkout/scratch paths at the top of
`model.py` when relocating. The three original complete input copies are in
`baselines/`. A fresh checkout must compile the unchanged engine before running
the scratch Java harness.

1. `python3 generate.py` prepares the final snapshot inputs and 264-job ledger.
2. `python3 run_jobs.py` compiles the Java bridge and runs two disjoint workers.
   It records their actual exit codes in `render-exit.json`. The original run
   preceded the bass square-law refinement; fourteen affected intermediate jobs
   were regenerated only after both workers completed, then rerun through
   `run_replacements.py`. The final ledger contains 264 unique inputs.
3. Run `EncodePredecessors` with this render directory as its argument. Record
   its actual exit code in `predecessor-exit.json`. It uses the same Float scalar
   as the actual listening WAV and confirms that every listening byte is
   unchanged before encoding the genuine preceding cycle.
4. `python3 verify.py` measures saved arrays and actual encoded files, writes
   the compact reports and four ZIPs, then exits nonzero for any hard failure.

`MaterialProbe.java` calls `Alloy.renderProbe$synth` for pre-output diagnostics
and separately calls `AlloyPatch.render()` for native PCM24. Python does not
implement a substitute synthesizer. Listening PCM16 uses the existing audition
law: one Float scalar to `Loudness.of` target .03, with peak cap .99.

Verification retains the original raw peak, DC, requested-root, classifier,
state, actual prior-cycle and 4x/8x high-register guards. Classifier checks use
the full event's features with only the LOOP-duration shortcut disabled. Encoded
held seam error is the existing Keys definition: normalized difference energy
over the final 256 samples of the real preceding and exported cycles, not a
loop concatenated with itself. The discarded preceding cycle's initial filter
startup remains a separate diagnostic rather than being copied or crossfaded.

The stronger authoring screen checks isolated sustained-body and full-signal
spectral total variation >= .08 and level-matched difference >= .15. These are
screening criteria, **not audibility or owner approval**. Both quarter intervals
and a denser five-point lower-half sweep are measured. FUSE zero now leaves
MATERIAL body colour active because the proposed mapping changes the body
configuration itself; FUSE still controls the generated-contact modulation port.

`reports/verification.json` records every actual result. `reports/provenance.json`
records source/class hashes and the response descriptor. `reports/metrics.csv`
contains per-render values and observed cloud render costs. They are not phone
performance measurements. The original .5 references and .5/.75/1 air-half
comparisons are checked sample-for-sample against unchanged public renders; the
three finite midpoints also compare with the actual published MPC sample data.

## Edited body comparisons

Six separate files alternate New body (0%) -> Original tone (50%) twice. They
are constructed from the **published PCM16 listening WAVs**, at the same source
time in each pair. Bass finite uses .25-.75 s; Long Prism uses .3-1.3 s; Velvet
Rise uses 1-2 s so its slow onset is excluded. Held comparisons use one entire
genuine captured cycle per block. Each excerpt is scaled to the same pair RMS
target (up to .07), gets a declared 4 ms inclusive linear fade at each block
boundary, and is separated by .15 s of zero PCM.

The manifest records source IDs, source/destination frame ranges, scalar gains,
fade lengths, gaps and quantization. These files are explicitly edited listening
demonstrations. They are **not native patches, one-shots, sampler loops, or a
live MATERIAL implementation**. Native WAV and snapshot download links belong
only to the 36 full render cases.

## Why it is built this way

1. The previous excitation-only change was masked by the selected body. Changing
   audible harmonic gains and held sustain gives the lower half real leverage.
2. Fixed ratios preserve tuning through intermediate settings; a smooth gain
   blend cannot create the detuned partials a ratio sweep could introduce.
3. The exact original branch at .5 preserves the owner's chosen tone and keeps
   the old air half independent of the proposal.
4. Complete snapshots make this round replayable without silently changing old
   projects or pretending the new control law has already shipped.
5. Edited, matched sustained excerpts make comparison easier while the complete
   notes and actual captured loops remain available beside them.

Owner sonic judgement, future opt-in control implementation, phone profiling
and physical MPC import/playback remain unmeasured. No factory-bank promotion
or claim of new listening approval is made by this prototype.
