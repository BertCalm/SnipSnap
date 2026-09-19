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

**From the phone**, the same files come by **LABEL THIS HIT**
(`docs/WORKSHOP.md`, WS3): with the WORKSHOP open, a pad sheet's BENCH
box copies the pad's WAV into `Calibration/` beside the kits under this
folder's own naming rule, and **SEND HITS TO BENCH** on SETUP packs that
folder as `SnipSnap Hits <date>.zip`. Unzip it and copy the WAVs in
`Calibration/` straight in here. Renders are refused at the tap; what
arrives is captures, labelled by ear.

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

Since CONFIRM ALL (`docs/WORKSHOP.md`, WS2) the same file also holds
**confirmations** — a chip the human vouched for, written as the same
line with the label equal to what the machine said — so `TeachLogTest`
counts the two apart and its agreement line reads as accuracy, not only
as misses fixed.

## The cut ratings

`cuts.jsonl` beside it is **RATE THE CUTS**: one line per rated chop —
the stars, the CUT bench's settings that made the cuts (mode, HITS,
EAR, CUT, SNAP), the slice count, how many chops ran before it, how many
cuts were then moved by hand, how many chips were corrected or
confirmed, and the source's length and tempo. No audio, no features.
`CutRatings` (`:shell`) is the reader/writer; `CutRatingsTest` sums the
file by setting on each test run, best first.

Both get here by **SEND TO BENCH** in the app's WORKSHOP
(`docs/WORKSHOP.md`: seven taps on SETUP's title open it). The button
packs every log on the phone — each kit's, and the bin's — into
`SnipSnap Bench <date>.zip`; unzip it and copy its `overrides.jsonl` and
`cuts.jsonl` straight into this folder, over the last ones. The zip is
cumulative (every log still on the phone), so the newest files supersede
rather than append. Its `manifest.txt` says which kit gave how many
lines.

Threshold changes motivated by this corpus belong in `Classifier` with a
comment naming the file(s) or log line(s) that motivated them.
