# DE-SAMPLE — the nearest synth patch to a captured hit

*The spec for FEATURE_PLAN XX3.*

## The idea

Rendering runs one way: a THUMP patch (a voice and a handful of 0..1
macros) becomes audio. DE-SAMPLE runs it the other way, approximately: a
captured hit becomes the patch that sounds most like it — a recipe the
engine can render again forever, edit knob by knob, and breed with. The
capture is not analysed into synthesis parameters (there is no inverse
of a drum synth); it is *measured*, and the measurement is compared with
the measurements of patches rendered ahead of time.

## The grid

Every voice's macro space is walked on a coarse grid — three levels per
macro (`0.15`, `0.5`, `0.85`: the corners of the space are bad neighbours,
the thirds are not) — and every point is rendered once and measured by
the classifier's own extractor (`FeatureExtractor`): centroid, rolloff,
flatness, zero-crossing rate, three band ratios, duration, decay. KICK
has five macros (243 points), SNARE four (81), the hats, clap and tom
three (27 each), cowbell and rim two (9 each): a few hundred renders,
done once per process and kept.

## The distance

`Similar.distance` — the Euclidean distance between the two normalized
nine-dimensional feature vectors, level left out on purpose (a quiet
snare is still a snare). Zero is the same sound. The nearest grid point
wins; where a kit pad's class is known the search starts on the voices
kindred to it (`Desample.voicesFor`), so a hat comes back as a hat and
not as the snare that happened to measure a hair nearer.

## The refinement

From the winning point a coordinate descent walks each macro up and
down by a step (0.175, halved whenever a round no longer helps, eight
rounds), keeping any move that measures nearer. An off-grid patch comes
back within a few hundredths; a patch on the grid comes back exactly,
distance zero, and renders back the same bytes.

## Honesty

The distance is always reported. Past `FAR` (0.45) the nearest patch is a
stranger: the CLI says so, the builder refuses to replace the pad unless
forced, and the phone's toast names the closest voice and how far it is
instead of pretending. A capture of a real drum lands well inside the
bound; a second of hiss lands outside it.

## The doors

- `snipsnap desample <hit.wav> [--out patch.json]` prints the patch;
- `snipsnap desample <kit-dir> <pad> [--force] [--undo]` makes the pad
  the patch's own render, the patch riding it as its recipe, the capture
  in the bin, `desampled` stamped with the distance for `lineage`;
- the PAD SHEET's DE-SAMPLE card on the phone, one button.

## Bounds

Deterministic: no seed, all measurement. The grid is THUMP's only — the
melodic engines are a different search (a note, then macros) and stay
below the line.
