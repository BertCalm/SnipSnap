# Crease shared-cache experiment

Date: 2026-11-06. Baseline source: `7dd9d090e`.
Luthier job: REVIEW of the accepted C4 study's memory cost.

The characteristic gesture remains press the onset, hold its motion, release
at any point, follow the cached unfolding to silence. The owner reported that
the existing phone study works. That is not a timing or stress measurement.

## Fixed experiment

Use the retained r2 PCM16 cache and the actual Kotlin captures from
`907a6638699363afc2179df594d8a293ef822b86`. Keep the 309 release coordinates,
held PCM, patch, gain 1.2239602314709794, phase correction, cubic reads,
neighbour interpolation, 221-frame blend and Float fade unchanged.
No engine equations or presets change.

Try integer-phase-aligned shared basis waveforms. For period P=168.5625,
offset q_i=round(centered_mod(cut_i,P)), pad A=89 and tail length L=53106,
aligned_i[k]=tail_i[clamp(k-A-q_i,0,L-1)], length L+2A=53284.
Read the aligned row at originalPosition+A+q_i. Integer alignment introduces
no resampling; fractional interpolation remains the existing reader's job.
Fit a mean plus principal components only to the 309 cache tails. Quantize
each waveform to signed PCM16 using its maximum absolute scale; fold that
scale into Float32 coefficients. Candidate component counts, including the
mean, are fixed at 4, 8, 12, 16, 24, 32, 48, 64.

Retain all 1,628 existing test positions as development cases. Choose the
lowest component count passing their original limits: normalized error RMS
<=.031623; absolute peak error <=.025; harmonic TV <=.02; added key-up step
<=.001; no nonfinite/clipped output. Keep the 12 MiB delivery limit. The new
resident cache goal is <=8 MiB including held PCM, basis, coefficients,
coordinate/offset tables and eight voices' coefficient working buffers.
Report transient decode storage and callback cost separately. Do not expand
all 309 tails in a shipping reader or decode/allocate at key-up.

Before opening new exact continuations, lock the chosen rank and quantized
basis/coefficient hashes in a committed candidate receipt. If no candidate
passes, retain the failed result and stop promotion; changing the scheme
requires another named experiment. A scratch training-only diagnostic before
this protocol measured bank reconstruction errors, not release fidelity or
new ground truth. Those diagnostics do not constitute validation.

Fresh positions use Python random.Random(0x43414348). Exclude every old test
and bank cut. Take (j+.19)*.0005 seconds for j=0..1023, rounded to output
frames, retaining distinct unused positions; then add 300 distinct prefix
positions (1..loopStart-1) and 300 distinct steady positions
(loopStart..heldFrames-1). Publish their exact list and hash before capturing.
References must be continuations of the same actual compiled Kotlin circuit,
with public-prefix, complete-state-fork and deterministic-repeat checks.
Run the locked representation once against these new positions. Do not
retune on them. Every old failure/report remains available.

## Promotion

Only a candidate passing all old and fresh cases can replace the study's
transport. Verify actual JS and native readers against the quantized Python
reference, including 48 kHz, malformed inputs, gestures and allocation-free
callback behavior. Measure processing cost. Then build and publish a phone
study and audition comparison, preserving the owner-private audience and
accepted reference recordings. Report host/emulator evidence separately
from owner listening and physical-device behavior. A keyboard range and
normal-app integration remain later work.

## Why it is built this way

1. Carrier alignment makes related tails easier to share without adding a
   second fractional resampling stage that could change the sound.
2. Short basis waveforms and coefficients trade some callback arithmetic for
   memory; their actual accuracy and processing cost decide whether to ship.
3. The held recording stays bit-exact so the accepted onset and sustain do
   not depend on the compression experiment.
4. Old cases select the candidate; fresh unseen positions test that fixed
   choice and cannot be used to repair it silently.
5. Lazy decompression at key-up risks a delayed release; expanding the old
   tails at startup would conceal the memory cost. Neither meets this goal.

Ledger: verified baseline/captures and owner report; measured training-only
bank diagnostic; proposed shared representation and cost; nobody has heard
this candidate yet. No outside DSP source is incorporated.
