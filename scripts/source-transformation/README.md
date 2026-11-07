# Compact resonant-body deliveries

These helpers package the saved Kotlin version-comparison render into small, self-contained FLAC/HTML auditions. They preserve the existing experiments and do not change the processor or its defaults. Start with [the handoff](../../docs/plans/source-transformation/HANDOFF.md) for current owner feedback and acceptance status.

Requirements: Python 3.9+ with NumPy and SciPy, plus `ffmpeg` on `PATH`. Run from the repository root after generating the comparison:

```sh
./gradlew --no-daemon :synth:generateResonantBodyVersionAudition \
  -PbodyVersionOutput=/tmp/resonant-body-comparison

python3 scripts/source-transformation/package_comparison.py \
  --render /tmp/resonant-body-comparison --output /tmp/resonant-body-delivery
python3 scripts/source-transformation/gain_probe.py \
  --render /tmp/resonant-body-comparison --output /tmp/resonant-body-delivery
python3 scripts/source-transformation/prepare_raw_gain.py \
  --render /tmp/resonant-body-comparison --output /tmp/resonant-body-delivery
python3 scripts/source-transformation/make_listening_master.py \
  --render /tmp/resonant-body-comparison --output /tmp/resonant-body-delivery
python3 scripts/source-transformation/package_level_player.py \
  --output /tmp/resonant-body-delivery
```

Use a fresh render directory. All helpers share an artifact parent outside the render tree. Each refuses to replace its own outputs unless `--overwrite` is explicitly supplied; unrelated helpers' outputs can coexist. `package_level_player.py` requires the raw-gain and listening-master records first. Each script supports `--help`.

| Helper | Output and checks |
|---|---|
| `package_comparison.py` | Before/after direct FLAC and standalone comparison HTML; matched audio decodes exactly to saved PCM24. Raw/dry float copies are converted to PCM24 for playback. Encoding inputs, receipts and queue JavaScript are kept in `resonant-body-v2-mobile/`. |
| `gain_probe.py` | `resonant-body-v2-level-probe/gain-report.json` and `review.md`; saved-PCM measurements plus a labelled Python equation stress. This is not a new Kotlin render or phone test. |
| `prepare_raw_gain.py` | Sixteen fully wet trials at 0/+6/+12/+18 dB, with .98 linked sample guarding if needed, PCM24 verification and asset records. No guard attenuation is needed for the selected fixtures. |
| `make_listening_master.py` | Four CURRENT/LOUDER pairs: burst +1.5 dB, sustained +12 dB, equal gain across bodies within each source group. Writes individual assets, a direct FLAC, sample/estimated true-peak checks and records. |
| `package_level_player.py` | Standalone level-comparison HTML containing eight current/louder clips and sixteen raw trials; checks embedded hashes and labels. |

The four shareable files appear directly under the artifact parent:

- `resonant-body-v2-before-after.flac` (339,912 bytes on the validated corpus)
- `resonant-body-v2-comparison.html` (811,413 bytes)
- `resonant-body-v2-louder-listen.flac` (192,678 bytes)
- `resonant-body-v2-level-comparison.html` (643,526 bytes)

The level preview changes playback gain only, with no compression, limiter, resampling or per-clip renormalization. The source-group gains are not an engine gain policy. Raw-gain copies and listening masters are quantized to PCM24; preserve the Kotlin float WAVs for numerical evidence.

Historical experiment metadata and owner quotes are retained as such. Rebuilding a page does not create a new listening verdict, overall acceptance, full JVM result or phone measurement. The comparison page reproduces the original listening experiment; the handoff contains the later favourable timbre feedback.

Fresh-output smoke validation during handoff recreated all four downloads byte-identically, verified FLAC PCM, left 104 original render files unchanged, checked `--help`/Python compilation, and exercised overwrite refusal. Previously recorded Chromium playback checks are described in the handoff; packaging alone does not rerun them. Serve locally with an allowed HTTP server if browser policy prevents opening local files; do not alter managed browser policy.
