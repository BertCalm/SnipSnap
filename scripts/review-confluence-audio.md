# Confluence signal review

This optional offline analysis needs Python 3, NumPy, and SciPy:

```sh
python -m pip install numpy scipy
python scripts/review-confluence-audio.py
```

Generate the Confluence audition first. The default input is the repository's
`testkit/confluence-audition` folder, regardless of the command's working
directory. The default report is `source-signal-review.json` inside that folder.
Override either location when reviewing a copied audition:

```sh
python scripts/review-confluence-audio.py \
  --audition /path/to/confluence-audition \
  --output /path/to/source-signal-review.json
```

The report records the audition manifest's SHA-256 and checks decoded exported
PCM format, finite samples, peaks, matched levels, complete source-region event
durations, roster retention, and identical scheduling across the SPACE pair.
An independent autocorrelation tracker compares source and isolated performer
pitch contours using each event's duration ratio. A nonzero exit code indicates
a recorded signal or trace check failure.

These checks cannot establish word intelligibility, plausible human identity,
or whether the result sounds like performers joining. Those remain listening
judgments. Rerun this script after regenerating the audition so the report
matches the published audio and manifest.

Held-vowel fixture manifests can declare `boundaryGuard` settings. The reviewer
checks the **selected phrase end**, final 50 ms amplitude, full last-500 ms
high-energy pitch trajectory, and marked sustain pitch range. These guards are
specific to the curated audition fixtures, not restrictions on user audio.
Check source fixtures before regenerating the audition with:

```sh
python scripts/review-confluence-audio.py --fixtures-only \
  --output /tmp/confluence-fixture-boundaries.json
```

Use `--fixtures /path/to/fixtures` to override the fixture directory. Ordinary
audition review checks the copied manifest and recordings in `AUDITION/sources`.
Run `python scripts/test_confluence_fixture_boundaries.py` for the three
regressions: current vowels pass, the retired male1 crop fails both amplitude
and pitch checks, and a stale selected end marker fails even if the complete
canonical file has a quiet tail.
