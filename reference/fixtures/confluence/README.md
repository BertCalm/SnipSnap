# Confluence human vocal fixtures

These are recorded human voices from **VocalSet: A Singing Voice Dataset** by
Julia Wilkins, Prem Seetharaman, Alison Wahl, and Bryan Pardo (2018).

- Dataset record: <https://zenodo.org/records/1193957>
- DOI: <https://doi.org/10.5281/zenodo.1193957>
- License: [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/)
- Local license text: [LICENSE-CC-BY-4.0.txt](LICENSE-CC-BY-4.0.txt)
- Initial sources retrieved: 2026-10-17; wordless starter expansion: 2026-10-18

The Zenodo record explicitly identifies `cc-by-4.0`; its metadata snapshot is
retained in `zenodo-record.json`. Preserve the above attribution and license
link when distributing these recordings or rendered adaptations. Describe
rendered ensembles as processed VocalSet recordings; do not imply that the
dataset authors or singers endorse SnipSnap.

## Files and marked regions

All canonical files are mono, 44.1 kHz, PCM16 WAV. The canonical versions are
cropped and given constant gain to a -3 dBFS sample peak. The airy vowel has the
explicit entrance fade described below; the other seven files have no fades.
No denoising, EQ, tuning, stretching, synthetic replacement, or generative
processing was applied. Eight complete original recordings remain in `originals/`, including the
retired male1 vowel recording retained for the boundary regression history.

| Canonical file | Duration | Reference note / observed frequency | Marked sustain, frames |
| --- | ---: | --- | --- |
| `vowel-male.wav` | 3.80 s | C3 / 126.708 Hz | 22050–121275 |
| `vowel-female.wav` | 2.95 s | C4 / 261.065 Hz | 30870–94815 |
| `phrase-male.wav` | 7.25 s | Initial C3 / 129.217 Hz | 22050–37485 |
| `phrase-female.wav` | 6.55 s | Initial C4 / 263.697 Hz | 26460–39690 |
| `airy-female.wav` | 1.20 s | C4 / 261.898 Hz | 6615–30870 |
| `airy-phrase-female.wav` | 7.20 s | Initial C4 / 262.808 Hz | 17640–35280 |
| `clear-phrase-male.wav` | 8.30 s | Initial C3 / 128.395 Hz | 26460–46305 |
| `rounded-vowel-male.wav` | 3.55 s | C3 / 127.659 Hz | 26460–110250 |

Frame intervals are half-open: start inclusive, end exclusive. Each complete
canonical file is also its selected phrase. `manifest.json` is authoritative
for IDs, paths, frame positions, exact content hashes, original crop ranges,
gain, attribution, and pitch observations.

## Wordless starter selection

`manifest.json` separates four wordless starter candidates from the earlier
demonstrations. `starterVoiceIds` maps stable bank IDs to the source fixtures:

| Bank ID | Fixture ID | Actual recorded style | Native material | Role |
| --- | --- | --- | --- | --- |
| `low-ah` | `vowel-male` | Straight `/a/`, male3 | C3 vowel | Foundation |
| `breathy-arc` | `airy-phrase-female` | Breathy `/a/`, female1 | Complete C4–C5–C4 arpeggio | Upper group |
| `clear-arc` | `clear-phrase-male` | Straight `/a/`, male3 | Complete C3–C4–C3 arpeggio | Wordless answer |
| `round-oo` | `rounded-vowel-male` | Straight `/u/`, male3 | Complete first C3 vowel gesture | Rounded foundation |

These four entries carry `wordless: true` and `usageFactoryCandidate: true`.
Their technique names come from the dataset's actual `straight` and `breathy`
folders. `/a/` and `/u/` are recorded vowels; no words, humming, or different
singing techniques were manufactured. They are openly licensed third-party
VocalSet recordings, with the attribution above, rather than project-owned or
commissioned master recordings. Perceptual acceptance remains a listening
judgment.

The Row excerpts remain historical comparison material with
`demonstrationOnly: true`, `wordless: false`, and `usageFactoryCandidate: false`.
The legacy `primaryPhraseId` still identifies that earlier demonstration;
the starter selection uses `starterVoiceIds` and `primaryWordlessPhraseId`.

## Source selections

The vowel files retain the first straight `/a/` long-tone gesture and its
release before the next register gesture. The male vowel uses singer **male3**,
original 0.15–3.95 seconds: its natural release is visible around 3.3–3.8 seconds
and the next register gesture begins around 4.3 seconds. No curated fade was
needed. Its normalized final 50 ms RMS is about 0.00217, under one percent of
the marked sustain RMS. The female vowel retains its quiet natural release;
a small pitch scoop while it dies away is part of the recording.

The airy source is the final native C4 `/a/` note in female1's **recorded
breathy-technique arpeggio**. Original 5.95–7.15 seconds starts after the previous
E4-to-C4 transition; the selected stable body is original 6.10–6.65 seconds.
Voicing releases naturally around 6.85–7.00 seconds, followed by a quiet margin.
An explicit **80 ms half-cosine entrance fade** smooths the cropped mid-vowel
entrance before peak normalization. There is no exit fade. The full 7.44-second
original arpeggio remains in `originals/f1_arpeggios_breathy_a.wav`.

The breathy label comes from VocalSet's source-technique metadata, not from EQ,
added hiss, or a synthesis technique claim. Final 50 ms RMS is about 0.00688,
2.44% of its marked body RMS; the high-energy tail remains within 40 cents of
its median pitch. The source is tagged `AIRY_UPPER` for the mixed-role audition.
Whether it sounds sufficiently airy in the gathering remains a listening
judgment.

The longer `airy-phrase-female.wav` retains the **complete recorded breathy
arpeggio**, original 0.20–7.40 seconds, with a naturally quiet entrance and
release. The source moves from native C4 up to C5 and back to C4; its length
comes from the recording. It has only constant peak gain, with **no fade,
stretching, repeated excerpt, EQ, or generated breath**. Its initial C4 marker
is original 0.60–1.00 seconds. The short final-note source remains available as
the earlier proof; the new mixed-role audition uses the complete phrase.

The long phrase's first and last 50 ms RMS are approximately 0.00188 and 0.00263.
Its final 50 ms is 1.64% of whole-phrase RMS. An amplitude-only
`tailBoundaryGuard` verifies this quiet ending without asserting a flat pitch
over the intentional C4–C5–C4 contour.

The clear wordless answer retains male3's **complete straight `/a/` arpeggio**,
original 0.20–8.50 seconds. It begins quietly on C3, reaches C4, returns to C3,
and releases naturally around original 8.1–8.3 seconds before its quiet end.
Only crop and constant gain were applied. The final 50 ms RMS is approximately
0.00105, 0.65% of whole-phrase RMS; its amplitude-only `tailBoundaryGuard`
allows the intended melodic contour.

The rounded source retains male3's first **straight `/u/` C3 gesture**,
original 0.15–3.70 seconds. It releases naturally around original 3.1–3.4 seconds
and ends before the next register gesture begins around 4.1 seconds. Its final
50 ms RMS is approximately 0.00361, 1.20% of marked sustain RMS. Its fixture
guard checks the held pitch and quiet tail. No fade or EQ supplies the rounded
character; it comes from the recorded vowel.

The historical `phrase-male.wav` and `phrase-female.wav` files retain the first
source section of the dataset's `row_straight` excerpt through its substantial
inter-line pause. These sections were selected using the amplitude envelope,
pitch continuity, and intervening rests. No semantic transcription or owner
listening approval is claimed. Do not further shorten them to satisfy a render
duration without checking word boundaries. The complete original recordings
are available when a longer source region is preferred.

The pitch values are median local autocorrelation observations over the marked
stable regions, not an assertion of perfectly tuned singing or ground-truth
phoneme labels. The male low vowel is about 55 cents below nominal C3; retain
this source character or use the measured reference frequency explicitly.
The phrase files contain a melody: a reference note must transpose its contour,
not flatten all notes to the reference.

## Reproduction

Run `python reference/fixtures/confluence/acquire.py` from the repository root.
The script reads only the eight retained ZIP members using HTTP ranges, verifies
their CRCs and pinned SHA-256 hashes, and recreates the canonical files from
the committed manifest. It does not download the complete 2 GB archive.

## Boundary regression

Fixture version 1 used a male1 vowel crop ending at original 3.65 seconds. Tail
review after an audible report found that it included the onset of the next
register gesture around 3.5–3.6 seconds: the final 50 ms was near 246 Hz instead
of the main 128 Hz and its RMS was still 0.151. This was a bad source boundary,
not an acceptable natural release. The original and retired source metadata
remain in `originals/` and `manifest.json`; the playable vowel now uses male3.

The two straight vowel entries and the airy vowel include fixture-specific `boundaryGuard` settings.
The numerical audition reviewer checks the full source tail's amplitude and
high-energy pitch trajectory, plus the marked sustain pitch range. These are
regression checks for these selected held-vowel fixtures, not eligibility thresholds
for arbitrary user voices. They do not establish perceptual acceptance.

The original recordings' ZIP member names are also retained in
`archive-selection.json`. The local CC BY 4.0 text was retrieved from Creative
Commons' own data repository at
<https://raw.githubusercontent.com/creativecommons/cc-legal-tools-data/main/docs/licenses/by/4.0/legalcode.txt>.
