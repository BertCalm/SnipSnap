# Short Pluck Keys

Short Pluck Keys turns Scanned Chain's existing Short Pluck preset into a
finite sampled keyboard instrument for MPC 2 and MPC 3. It captures thirteen
roots from MIDI 36 through 72 (C2–C5), every three semitones, at three physical
velocities: 0.30, 0.65 and 1.00. That makes 39 mono, 44.1 kHz, 24-bit samples.
Each source lasts 0.8 seconds. There are no sustain loops.

## Generate and load

```sh
./gradlew :cli:generateScannedChainKeys
```

The generator writes an instrument ZIP and an unpacked package beneath
`testkit/scanned-chain-keys/`. Keep the `.xty` and its matching
`_[TrackData]` folder together when copying the instrument to MPC storage.
Load the `.xty` on MPC 3, or the `.xpm` inside the TrackData folder on MPC 2.
The package's `LOADME.txt` describes its exact layout and loading steps.

The CLI command is
`scanned-chain-keys [--patch FILE] [--out DIR] [--overwrite]`.
The bank manifest and individual source patches retain the complete shape,
macros, model, note, physical velocity, seed and envelope. Recipes can be
edited and rendered again; each exported WAV also includes the documented
capture-gain correction below. The phone instrument sidecar retains the
keygroup map, rather than embedding synthesis recipes in that map.
To rebuild an edited PLUCK source, pass its saved `scanned-chain.patch.json`
to `--patch`. The default task uses the registered Short Pluck preset.
An edited source can have a different duration or less physical touch
contrast; the measurements below describe the default bank.

SnipSnap's share/import flow currently rejects keygroup packages. This ZIP
therefore supplies MPC delivery directly; installing its sidecar and sample
folder in the phone's private `files/Kits/Instruments` folder requires a
developer file-copy workflow. It does not add a MAKE INSTRUMENT button or
claim tap-to-import support.

## Playing behavior

| Setting | Behavior |
| --- | --- |
| Register | C2–C5 in the phone sidecar and both MPC descriptors. Keys outside that range are unmapped. |
| Root spacing | Three semitones. Interior notes transpose a neighboring capture by at most one semitone. |
| Touch layers | Soft, medium and firm physical excitation; hard velocity switches, without crossfades. |
| Playback | Polyphonic, note-on triggered. Holding a key lets its finite sample finish. |
| Note-off | A short host release cuts the remaining sample when a key is released early. The setting is 0.05: 50 ms in SnipSnap's reference player; MPC envelope timing is not calibrated in seconds. |

The source preset's 0.65-second RELEASE is a fade counting backward from
the sample's end. The host release above is a separate note-off behavior.
Changing the host release cannot extend the captured sound.

## Why it is built this way

- Native captures retain the same mechanical evolution clock across the
  register. Closely spaced roots keep intervening sampler transposition small.
- Physical velocity changes the simulated excitation. The nine-sample study
  at C2, C4 and C5 retained modest waveform and spectral differences after
  removing level differences, supporting three layers without changing DSP.
- The production renderer already multiplies its finishing target and peak
  ceiling by capture velocity. Each bank sample divides that scalar out once;
  the sampler then supplies playing-velocity gain. The physical trajectory
  stays intact. Bank samples are not independently RMS-matched.
- Complete source recipes travel with the delivery, so a sampled instrument
  remains reproducible and editable.
- Finite playback preserves the authored ending. A fabricated tail loop would
  change the gesture and would require a separate sound-design decision.

## Study evidence

The initial study rendered the unchanged model-two Short Pluck preset at
C2, C4 and C5 with all three physical velocities. It retained raw production
WAVs and whole-clip RMS-matched comparisons, plus exact patch recipes and
source provenance. All nine recipes reproduced their floating-point audio.

Across three early/body pitch windows, the nine notes measured within 2.06
cents of their targets, with detector confidence at least 0.841. Maximum raw
peak was 0.686 and maximum absolute DC was below 1.1e-9. After fitting a
single scalar, soft-to-firm residual RMS relative to the firm signal was
0.1333–0.1336 over the full clip and about 0.050 in the early body. Normalized
STFT-magnitude distance was about 0.125. These describe numerical waveform
and spectral differences, not percentages of perceived difference.

Listening remains the owner's judgment. The listening page presents both
production-level and RMS-matched study files so the touch differences can
be compared without treating louder as better.

The complete default bank was then checked through its exported phone
sidecar and PCM24 files. All 39 recipes, after the stated capture-gain
correction, matched the WAVs within 5.97e-8. Across 117 pitch probes the
maximum error was 6.41 cents and minimum confidence was 0.841. Maximum
sample peak was 0.686 and absolute DC was below 2.07e-9. Every WAV retained
its native sampler root and zero sustain loops. A held C4 stopped at the
finite ending; early note-off was silent within 51 ms in the reference
player. The nine-second, 110 BPM demo uses that player and the exported
bank, with native and intermediate notes and varying touch, without FX or
an added normalization stage. These are numerical and reference-playback
checks, rather than a new MPC hardware test.
