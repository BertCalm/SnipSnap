# Classifier calibration corpus

Real captured hits, labeled by ear — the ground truth `Classifier`'s
thresholds get tuned against. The thresholds shipped today were tuned on
synthetic renders (`DrumSynth`), which the concept doc flags as a starting
point, not a finished calibration; this folder is where the finish comes
from.

## Contributing captures

Drop WAVs straight in this folder, named for what your ear says they are:

```
kick_yt_break01.wav
snare_phone_room.wav
hatclosed_vinyl.wav
hatopen_vinyl.wav
tonal_bass_note.wav
```

The label is everything before the first `_`, case-insensitive:
`kick` · `snare` · `clap` · `hatclosed` (or `closedhat`) · `hatopen` (or
`openhat`) · `tom` · `perc` · `tonal` · `loop`. Any sample rate, mono or
stereo, PCM or float — real *captures* (phone, capture path, video rips),
not synth renders; renders are what the thresholds already know.

A dozen files is enough to start; more is better. `snipsnap classify` on
a capture prints the features next to the verdict, which is a fast way to
pre-check a label.

## What runs against it

`CalibrationCorpusTest` in `:audio` scans this folder on every test run:

- empty folder → the test passes quietly (nothing to calibrate yet);
- with captures → it prints the full report — per-file verdicts with the
  features behind every miss (the numbers you move a threshold by), the
  confusion matrix, per-class and overall accuracy — and **fails below
  60% overall**, the "the classifier is broken on real material" line.

## The overrides log

Beside the WAVs, `overrides.jsonl` collects **teach-the-machine** data:
every chip override in the chop screen, logged as a feature vector plus
the human's label — never audio, so it's rights-clean by construction.
The app appends it only behind a consent switch; `TeachLog` (`:shell`)
is the reader/writer, and `TeachLogTest` scores every logged correction
against the current rules on each test run.

Threshold changes motivated by this corpus belong in `Classifier` with a
comment naming the file(s) or log line(s) that motivated them.
