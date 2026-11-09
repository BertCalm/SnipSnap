# TRIAD: accepted hand drums and complete rig pack

Owner verdict: the first three rigs sounded great except the weak/flat conga and bongo. After hearing the revised hand drums: “Much better, continue.”

The accepted source is handRevision 2 at the exact R2 settings. No further DSP, pattern, balance, or bus-setting changes are part of this step. The full pack carries that source through Light, Main and Heavy for all three rigs, with stage-one comparisons and nine source stems. Earlier auditions remain archived.

## Reproduction

From the source branch:

```sh
./gradlew --no-daemon :synth:generateTriadAudition
python3 scripts/verify_triad_audio.py testkit/triad-audition
python3 scripts/build_triad_page.py /absolute/output testkit/triad-audition
```

The dedicated accepted-page packager `scripts/build_triad_accepted_page.py` takes a render directory and the existing Site directory. It verifies the three Main performances against the owner's R2 audition, confirms the other seven native stems match R1, and checks that archived files remain unchanged. It ships lossless FLAC for browser playback and PCM24 WAVs in the download archives. FLAC is decoded and checked against the delivered WAV samples before packaging.

`settings.json` records handRevision, tempo, phrase length, source macros, and all three pairs of DRIVE/SPACE values. The source files and exact event list are included. These are finite phrases at 124 BPM plus tails, not seamless loops, an MPC program, or an Android picker entry.

## Verification scope

The accepted source passed seven focused Kotlin tests and twelve independent PCM checks in R2. This round adds delivery checks over all 44 WAVs, exact PCM comparison for lossless transport, archived-file hashes, and gain-invariant comparisons to the accepted Main performances. Repository-wide regression testing is separate; do not infer a full pass from these delivery checks. Browser automation is unavailable in this session; the owner has already played the R1 and R2 Sites.

## Why it is built this way

- The accepted source is frozen so packaging cannot quietly change the sound you chose.
- Main clips are compared against the actual R2 audio to catch an accidental rerender with old hand drums.
- All three intensity settings retain the same groove, making the processing choices easy to compare.
- Original auditions and PCM downloads remain available for reference and sampler use.

## Delivery results

All 44 final WAVs passed independent format, peak and DC checks. The four Main/dry comparisons reproduced the accepted R2 signal shapes with gain-adjusted RMS residual below 0.0000001, consistent with PCM24 quantization. All seven other native source stems matched the first audition byte for byte. The 22 browser FLAC files decoded to the exact WAV PCM. Both WAV archives passed integrity checks and each is below 25 MiB. All 78 pre-existing Site files checked by the packager remained unchanged.
