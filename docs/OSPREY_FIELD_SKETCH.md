# Between shores · a dry OSPREY field sketch

The owner confirmed the sound in app and asked to keep moving. This short
performance puts the existing R3 kit into musical context while phone
save/reopen, recipe replay and MPC playback checks remain open.

Hear it on the [audition page](https://snipsnap-osprey-audition.bertcalm.chatgpt.site/#performance).
The performance download contains a reference WAV, MIDI, score and instructions;
the [unchanged R3 kit](https://snipsnap-osprey-audition.bertcalm.chatgpt.site/downloads/osprey-r3-kit.zip)
is a separate download.

## The arrangement

Sixteen bars at nominal 90 BPM introduce the five calm coasts, then bring rough
replies and two storm accents into a sparse dialogue. BETWEEN SHORES closes the
phrase. Three additional bars let every eight-second one-shot finish, making
the complete reference about 51 seconds. All twelve strikes use velocity 127.

| Bar | Pad | Sound |
| --- | --- | --- |
| 1 | A01 | Atlantic calm |
| 3 | A04 | Nordic calm |
| 5 | A07 | Mediterranean calm |
| 7 | A10 | Pacific calm |
| 9 | A13 | Southern calm |
| 10 | A02 | Atlantic rough |
| 11 | A05 | Nordic rough |
| 12 | A08 | Mediterranean rough |
| 13 | A11 | Pacific rough |
| 14 | A15 | Southern storm |
| 15 | A03 | Atlantic storm |
| 16 | A16 | Between shores |

Each sound keeps its original pitch, stereo samples and complete tail. There
are no added FX, loops, resampling or changes to the engine, patches or kit.
The reference linearly sums the original WAVs at their saved pad levels, then
applies one common listening gain to the whole mix. That gain is recorded in
the score; samples are not normalized individually.

## Reproduce the reference

With Python 3 and NumPy installed, use the preserved kit ZIP and its adjacent
handoff manifest:

```sh
python scripts/render-osprey-field-sketch.py \
  --kit testkit/osprey-handoff/osprey-r3-kit.zip \
  --handoff testkit/osprey-handoff/handoff-manifest.json \
  --out testkit/osprey-field-sketch
```

The renderer checks the source archive and WAV hashes before reading them.
Its score records source provenance, event timing, actual MPC trigger notes,
sample hashes, the common listening gain and output diagnostics. It also writes
a Standard MIDI File and a performance ZIP. Generated files are ignored by Git.

## Try the MIDI on the MPC

Load the R3 native XTD or compatibility XPM, then import the performance MIDI
and assign its drum part to that kit. The MIDI uses channel 10 and the kit's
exported pad-note mapping. For example, Nordic calm uses trigger note 82 while
its original sample stays at C4; that trigger is a pad address.

The sixteen-bar phrase is followed by three tail bars. Do not trim the final
sample or treat the file as a seamless loop. Match listening volume when
comparing the WAV with the MPC, since the reference's common listening gain
and the hardware's mixer, velocity and one-shot behavior can differ.

MIDI import and playback still need the [Live III check](OSPREY_MPC_PASS.md).
The reference proves a reproducible arrangement of the published samples;
it does not establish byte-identical or verified hardware playback.
