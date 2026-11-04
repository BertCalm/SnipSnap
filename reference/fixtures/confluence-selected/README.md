# Reproduce the nine selected Confluence SOURCE files

`approved-selections.json` is the unchanged nine-section export approved for this
bank. Its SHA-256 is
`2ea504d802161b415814540922691cff00d1365ef56680ccd231c73d793f3df7`.
It records seconds in the exact listening files, not frames in unavailable
original WAVs. The shipping manifest is
[`synth/src/main/resources/confluence-selected-bank/bank.json`](../../../synth/src/main/resources/confluence-selected-bank/bank.json).

The script regenerates only the nine raw SOURCE WAVs. It does not apply the
shipping manifest's separate REFERENCE gain/fades or render any ensemble.
Full recordings, decoded buffers and source-page HTML are not stored here.

## Local inputs and acquisition

Place these three files in one local directory. Acquisition is manual; the
script makes no network requests and refuses a different file checksum.

| Local filename | Public acquisition URL | SHA-256 |
| --- | --- | --- |
| `hindustani-yaman-preview.mp3` | [Freesound HQ preview](https://cdn.freesound.org/previews/193/193920_1859932-hq.mp3) | `eb1e810fb29b79817ec5f48eccff81f18a4188836ca0b89284703bc8a48ee8a9` |
| `swedish-kulning.flac` | [Verified lossless listening FLAC](https://confluence-snipsnap-audition.bertcalm.chatgpt.site/voices/assets/swedish-kulning.flac) | `18f2f0c0b448203ae56c5a765204b64fd36e589912ffd0ae4c3b6966453dc099` |
| `khoomei-preview.mp3` | [Freesound HQ preview](https://cdn.freesound.org/previews/240/240916_2007100-hq.mp3) | `0c5e61b4cb8c2bb7559d9cb25742c15e5473d7c11cba35f8f14adf106e816be5` |

The two MP3 files are public lossy previews, not original WAVs. Yaman credits
Kaustuv Kanti Ganguli, shared by sankalp / CompMusic, under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/):
[source page](https://freesound.org/people/sankalp/sounds/193920/).
The Khöömei demonstration is shared by nemaavla under
[CC0](https://creativecommons.org/publicdomain/zero/1.0/); its
[source page](https://freesound.org/people/nemaavla/sounds/240916/) does not name
the performer. No performer ethnicity is inferred.

Kulning credits Anna Moraeus and Jemt-Kersti Andersson, recorded by Matts Arnberg
in Dalarna in 1954, from the Swedish Performing Arts Agency / Centre for Swedish
Folk Music and Jazz Research, under CC0:
[source and permission page](https://commons.wikimedia.org/wiki/File:Kulning_p%C3%A5_kor_-_SMV_-_SVA_BB_5345_13.wav).
As an alternative to the FLAC, save the
[original archival 44.1 kHz, 16-bit stereo WAV](https://upload.wikimedia.org/wikipedia/commons/c/c5/Kulning_p%C3%A5_kor_-_SMV_-_SVA_BB_5345_13.wav)
as `kulning-original.wav`, SHA-256
`cc6a668a15ba49d324102a18247106332edcf5fab39da0da501a680931cff3cc`.
Its decoded PCM16 and the listening FLAC were verified identical, SHA-256
`4e44a201c65a8a96720dfbbca2607ef024572070d5c15ccff9d0049565186bdd`.
The script also checks the exact decoded mono float32 checksum for either input.

The original cache filenames `freesound-193920-yaman-hq-preview.mp3`,
`kulning-lossless.flac` and `freesound-240916-khoomei-hq-preview.mp3` are accepted
aliases. Changing container encoding or redownloading a different preview does
not satisfy the hash pins.

## Run and verify

Requires Python 3, NumPy, FFmpeg and FFprobe. The verified preparation used
**FFmpeg 7.1.5-0+deb13u1** and **NumPy 2.3.5**. Other versions may decode the MP3
files differently; a mismatch is refused rather than accepted as a new source.

From the repository root:

```sh
python3 reference/fixtures/confluence-selected/prepare.py \
  --inputs cached-listening-files \
  --output regenerated-selected-voices
```

Both paths are supplied by the caller. Output must be new or empty and separate
from the inputs and this reference bundle. `--manifest` can explicitly select
the shipping `bank.json`; the default finds it in this checkout.

The operation checks the pinned files, gapless decoded frame counts and exact
decoded mono float32 hashes. It rounds each approved edge independently with
`floor(seconds * 44100 + 0.5)`, then checks the shipping frame coordinates.
Stereo channels are summed and averaged in float32. PCM24 uses
`floor(float64(sample) * 8388607 + 0.5)` with RIFF word padding, matching the
original preparation and SnipSnap's WAV writer. No resampling, normalization,
cleanup, DC removal, fading, pitch/time processing or repetition occurs.

Every regenerated WAV byte hash, byte count and decoded canonical PCM hash must
match the shipping manifest **before any output is written**. Canonical identity
is SHA-256 of little-endian int32 rate and frame count followed by float32 samples
decoded with SnipSnap's signed PCM24 `/ 8388607f` scale, clamped at -1.
Success writes `voices/*.wav` and `reproduction.json`, recording the checks and
actual tool versions. This is an exact file/timeline reproduction check, not a
new listening or cultural-authenticity assessment.
