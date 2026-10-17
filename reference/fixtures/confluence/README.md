# Confluence human vocal fixtures

These are recorded human voices from **VocalSet: A Singing Voice Dataset** by
Julia Wilkins, Prem Seetharaman, Alison Wahl, and Bryan Pardo (2018).

- Dataset record: <https://zenodo.org/records/1193957>
- DOI: <https://doi.org/10.5281/zenodo.1193957>
- License: [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/)
- Local license text: [LICENSE-CC-BY-4.0.txt](LICENSE-CC-BY-4.0.txt)
- Retrieved: 2026-10-17

The Zenodo record explicitly identifies `cc-by-4.0`; its metadata snapshot is
retained in `zenodo-record.json`. Preserve the above attribution and license
link when distributing these recordings or rendered adaptations. Describe
rendered ensembles as processed VocalSet recordings; do not imply that the
dataset authors or singers endorse SnipSnap.

## Files and marked regions

All canonical files are mono, 44.1 kHz, PCM16 WAV. The canonical versions are
cropped and given constant gain to a -3 dBFS sample peak. No denoising, tuning,
stretching, fades, synthetic replacement, or generative processing was applied.
The four complete original recordings remain in `originals/`.

| Canonical file | Duration | Reference note / observed frequency | Marked sustain, frames |
| --- | ---: | --- | --- |
| `vowel-male.wav` | 3.50 s | C3 / 128.043 Hz | 22050–121275 |
| `vowel-female.wav` | 2.95 s | C4 / 261.065 Hz | 30870–94815 |
| `phrase-male.wav` | 7.25 s | Initial C3 / 129.217 Hz | 22050–37485 |
| `phrase-female.wav` | 6.55 s | Initial C4 / 263.697 Hz | 26460–39690 |

Frame intervals are half-open: start inclusive, end exclusive. Each complete
canonical file is also its selected phrase. `manifest.json` is authoritative
for IDs, paths, frame positions, exact content hashes, original crop ranges,
gain, attribution, and pitch observations.

The vowel files retain the first straight `/a/` long-tone gesture and its
release before the next register gesture. The phrase files retain the first
source section of the dataset's `row_straight` excerpt through its substantial
inter-line pause. These sections were selected using the amplitude envelope,
pitch continuity, and intervening rests. No semantic transcription or owner
listening approval is claimed. Do not further shorten them to satisfy a render
duration without checking word boundaries. The complete original recordings
are available when a longer source region is preferred.

The pitch values are median local autocorrelation observations over the marked
stable regions, not an assertion of perfectly tuned singing or ground-truth
phoneme labels. The male low vowel is about 38 cents below nominal C3; retain
this source character or use the measured reference frequency explicitly.
The phrase files contain a melody: a reference note must transpose its contour,
not flatten all notes to the reference.

## Reproduction

Run `python reference/fixtures/confluence/acquire.py` from the repository root.
The script reads only the four required ZIP members using HTTP ranges, verifies
their CRCs and pinned SHA-256 hashes, and recreates the canonical files from
the committed manifest. It does not download the complete 2 GB archive.

The original recordings' ZIP member names are also retained in
`archive-selection.json`. The local CC BY 4.0 text was retrieved from Creative
Commons' own data repository at
<https://raw.githubusercontent.com/creativecommons/cc-legal-tools-data/main/docs/licenses/by/4.0/legalcode.txt>.
