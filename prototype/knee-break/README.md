# Knee Break first sound proof

Design and reference ledger: [KNEE_BREAK.md](../../docs/KNEE_BREAK.md).
Source dependency: the existing `../ember/ember_proto.py` and its fitted-parameter resources at repository commit `f1842eb48f175fb5fc77e02efeeda97c42c15d6a`. No source-video audio is distributed.

Run from the repository root:

```sh
OPENBLAS_NUM_THREADS=1 python prototype/knee-break/render.py testkit/knee-break-proof
```

Open the generated `index.html` from its folder. Keep `audio/`, `manifest.json`, `verification.json` and `NOTICE.txt` beside it. The folder is a WAV audition, not an MPC or SFZ program. The proof deliberately covers four core voices before the proposed 16-pad layout.
