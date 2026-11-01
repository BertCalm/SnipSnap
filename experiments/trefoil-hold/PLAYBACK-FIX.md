# Held audition playback revision

Owner report: "Section 2 sounds good, the others clip or clip at the repeat."
Section 2 is the note-on/note-off listening reference for this round. Its WAVs,
all other held WAVs and the approved finite bank remain byte-identical.

## Fix now

- [D] The original player opened and closed already-ringing recordings without
  transport ramps. The ordered loop starts at -0.183282 full scale; its peak is
  0.237558. A sudden change from silence to that first sample produces a step
  even though the file has headroom. Playback now has 12 ms gain transitions for
  start, stop, seek and recording changes.
- [D] Repetition depended on the browser media element's loop handling. The
  recurrence checks proved the WAV's periodic continuation; they did not prove
  gapless device playback. One Web Audio buffer source now repeats without a
  JavaScript restart. A/B sources switch at the current phase with scheduled
  gain transitions. Fetching the next recording leaves the current source running.
- [C] WAV decoding preserves the original 44.1 kHz PCM frames even when the output
  context uses 48 kHz. Resampling an isolated cycle before looping can introduce
  a separate edge condition. Output-rate conversion now acts on the running source.

## Measured

`transport_check.py` renders the actual production Web Audio graph in Chromium's
OfflineAudioContext. It checks all six candidates at 44.1 and 48 kHz, compares
44.1 kHz output with independently rendered continuation samples, checks silent
gaps and boundary slopes against interior slopes, and exercises interruption of
an in-progress gain ramp. A deliberately hard-switched test signal reproduces
a 0.25 step. Production transitions reduce the largest step below 0.0011 while
18 rapid opposite-polarity switches stay within the original 0.25 peak.

The realtime browser check also crosses a loop boundary on the same source,
uses Play/Pause and seek, and stops a deliberately delayed download before it
can resume playback. These checks extend the earlier WAV and interaction checks;
they do not constitute listening acceptance on Josh's device.

## Disclosed, not fixed

Raw loop downloads retain their full-level periodic state and original bytes.
An external player that starts or stops such a WAV abruptly can still click.
The audition adds transport envelopes at playback; it does not crossfade or
fade the raw loop itself to conceal a resonator recurrence error. Section 2
remains a finite natural-release recording. Musical texture is unchanged.

## What I measured / what I only read

Measured: stored PCM peaks and endpoints, scheduled Web Audio output, repeat
continuation, gain interruptions, browser controls, note persistence and exports.
Read: the original HTML media-element loop implementation at source commit
`0f8beee80f92c5d155dc3cf17f16823c2202df7f`. Josh reported the sound; no claim is
made to have heard his device's output or reproduced its specific browser gap.
