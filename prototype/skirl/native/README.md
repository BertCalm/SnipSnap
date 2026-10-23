# Native Skirl evidence

The Kotlin acceptance tests live in `synth/src/test/kotlin/com/snipsnap/synth/`:
`SkirlDynamicsTest`, `SkirlAcousticsTest`, `SkirlTest`, `SkirlPatchTest`, and the
SKIRL determinism canary. CLI and shell tests verify saved recipe integration.

The first repository-wide run was interrupted by an execution-environment restart.
Its log had no reported failures but no final exit result. The replacement run uses
three isolated synth test workers, with every assertion and the existing 2 GiB
worker heap limit preserved. The helper changes execution concurrency only:

```sh
./gradlew --no-daemon --max-workers=4 --init-script prototype/skirl/native/test-workers.init.gradle test
```

The replacement gate passed with **5,994 tests and no failures or skips** across
nine JVM modules. `verification.json` records module counts, source hashes, archive
checks and cross-JVM production-wave identity. The CLI distribution and final audition
tasks also completed with exit 0.

Final render benchmarks ran separately after all test workers exited. `benchmark.json`
records cleared-cache/default warm costs: warm medians **148.85 ms ROLL / 160.73 ms
WHIRR**, above the proposed 50 ms target. These are Linux JVM measurements, not phone
measurements.

`matrix_quality.json` records the complete 106-case audition scan and its 212
early/tail pitch windows: all pass, with worst error 18.342 cents and minimum
confidence .7974. All 106 production PCM16 WAVs are byte-identical to the separate
preflight JVM run. Preliminary contended timings were discarded; the retained matrix
report and benchmark come from the final standalone run.

`extra_windows.json` records a separate direct-JVM check of 40 additional
0.30-second windows using the unconstrained house pitch detector. It samples
low WHIRR during rolling, C6 soft/firm touch, and EDGE-zero high notes, including
the free decay. It is numerical evidence, not an owner listening review.

Regenerate the complete native listening matrix from the repository root:

```sh
./gradlew --no-daemon :synth:generateSkirlAudition
```

Optional original Python comparisons require the historical reference WAVs;
see `docs/SKIRL.md` for their portable regeneration command. Generated WAVs and
HTML remain outside version control under `testkit/skirl-audition/`. The HTML
embeds its manifest and can be opened locally after unpacking the listening ZIP.

The signed rim texture and note-aware pulse width are acoustic revisions from
the earlier Python audition. Mechanics comparisons and acoustic listening
comparisons answer different questions. Desktop JVM timing does not establish
Android latency.
