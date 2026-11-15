# Spore kick listening study

A separate, unapproved Python kick study. The approved snare model is unchanged.
Five decaying body frequencies and eight filtered-noise bands are fitted to seven
kick strikes in the supplied live 170 BPM clip, with four disjoint check strikes.
The reference is mixed audio, so microphone/processing and other drum bleed are
not separable. The renderer loads no reference recording.

```sh
OPENBLAS_NUM_THREADS=1 python3 scripts/spore_kick_study/test_model.py
OPENBLAS_NUM_THREADS=1 python3 scripts/spore_kick_study/calibrate.py \
  --reference /path/to/live-clip.wav --previous /path/to/old-kick.wav \
  --output /path/to/kick-study
```

This study uses the snare study's oscillator/noise fitting method with a different
frequency dictionary, decay dictionary, beater contact and downward pitch recovery.
It does not establish a finished drumkit, a native kick recipe or owner approval.
