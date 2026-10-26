# OHM: optional sources and sustained instruments

Status: implemented model-3 prototype; focused checks and audition browser QA
passed. The new sounds have not been heard by the owner. Repository baseline:
`5e953da5`. Models 1 and 2,
the five accepted factory recipes and the finite starter kit remain available.

The characteristic gesture is **attack → answering companions → shared body
→ sustained relationship → release**. Family behaviour is an authored musical
analogy. Waveguide propagation, friction at the bow and reciprocal bridge
scattering are actual DSP mechanisms.

## The direction chosen

The strong direction is **SPLICE + COUPLE**: replace the foreground exciter
and introduce bounded companion sources before OHM's existing shared bridge.
ARCO already owns a bowed string and GYRE already couples bowed strings;
ORPHICA owns pluck-history grains. These become options inside OHM because
the solo/argument/communion relationship is its useful distinction. Gesture
clarity is high, novelty belongs to that relationship, numerical risk is
concentrated at the bow and loop, and the cost is one coordinated build round
plus the owner's listening pass.

Alternate A is **INVERT**: feed the body's output back through the grain
source. That could make a self-listening family, but introduces a new powered
feedback path and requires a different stability argument. Gesture clarity
is medium, novelty is higher, risk is high, and cost is at least another
proof/build round. It is outside this implementation.

Alternate B is **DISTIL**: freeze a phase-folded harmonic spectrum instead
of audio history. It gives a clean pitched drone but projects out inharmonic
source detail. Gesture clarity is high, novelty is shared with ORPHICA,
risk is moderate and cost is another source/listening round. This build uses
an actual captured audio buffer and names it accordingly.

## Signal path and saved contract

```text
native note, velocity, seed, five macros + HOLD, source options
               |
     pluck / friction bow / embedded capture
               |                       dry exciter history
               |                         |           |
               |                    Hann grains   buffer freeze
               |                         |           |
               |                 bounded companion excitation + FM elder
               |                         |
          foreground <== shared bridge + membrane ==> answering resonators
                                   |
                conditioning -> mono 44.1 kHz -> complete-instrument level
                                   |
                  finite ending / loop-only pad / attack+sustain KeyNote
```

Every note owns fresh state. The history source observes dry excitation;
no resonator or finished output is recirculated into it. `OhmOptions` stores
the exciter, elder/grain/freeze amounts, FM ratio/index, grain duration and
optional captured PCM beside the macro map. FM index is in radians, bounded
to 0–3, with ratio 0.5–2 and a note-aware upper-band allowance. Grain duration
is 20–200 ms. These bounds and gains are design choices pending listening.

`OhmCapture` embeds canonical mono 44.1 kHz PCM16, by default 250 ms and at
most 500 ms. Its Base64 framing, size and SHA256 are validated before use.
The recipe survives deletion or editing of its original pad. Capture onset
alignment, normalization and fades happen once during preparation. Returned
sample arrays are copies; serialized PCM and its hash are immutable.

The original five performance controls keep their roles:

| Control | Default | Model quantity and interaction |
| --- | --- | --- |
| TOUCH | .5 | Excitation contact/brightness; source type changes the contact mechanism |
| BODY | .5 | Radiation formants and membrane resonance; interacts with coupling |
| LENGTH | .65 | Finite decay/duration and held gesture's time scale |
| MEDDLING | .55 | Participation and staging of answering sources; zero is solo |
| COMMUNE | .45 | Shared bridge influence, independent radiation and pitch wander |
| HOLD | 0 | Finite driven duration below .99; continuing captured sustain at the top |

The phone explicitly opts into model 3 through EXTEND. Existing model-1/2
recipes retain their renderer. The source options and HOLD survive edits,
save/reopen, native velocity rendering, pad recipes and preset promotion.
The original factory bank is not expanded with unheard presets.

## Held sound and instrument landing

Full HOLD continuously drives the resonators. A held pluck is a disclosed
renewed-excitation adaptation; a decaying one-time pluck cannot sustain
itself. GATHER runs its arrival once and then stays at the selected final
relationship. Source clocks share a note-fitted period. A bounded session
advances through attack, settling, retained audio and genuine future audio.
PLUCK and CAPTURED loop tests compare physical continuation and an independently
preceding cycle, including decoded PCM; repeating a file against itself is
not proof. Bowed sustain uses a selected stretch of the continuously running
friction model, with a disclosed blended join. Its physical continuation error
is retained separately from the stored-loop join, correlation, level dip and
sample-step/slope checks. The stored loop is not claimed to be naturally
periodic physical state.

Ordinary HOLD pads receive only the repeating region. `renderHeld` returns
attack plus sustain with a positive loop marker for `OhmPadMaker`. The
existing instrument path exports native C3–C5 sample zones, a playable phone
instrument, MPC 3 `.xty`, MPC 2 keygroup `.xpm`, shared WAVs with `smpl`
metadata, complete instrument metadata and a portable `.patch.json`.
Release is saved with the patch, in 0.02–3 seconds, and playback owns note-off.

The warm-host target is tens of milliseconds for short finite previews and
roughly 300 ms for a typical held render, with a bounded 12-second settling
horizon, one future cycle and filter context, and frequent cancellation checks.
At LENGTH .65, a final-core JDK 17 amd64 benchmark measured finite renders at
120–234 ms and held renders at 126–235 ms across the three exciters and MIDI
48/60/72. Two warmups and five timed calls per case gave a worst individual
call of 285 ms. This is a small timing sample, not a worst-case guarantee.
Finite notes contain 4.53 seconds of audio; held rendering simulates
roughly 4.55–5.46 seconds in these cases. This longer finite-note cost exceeds
the short-preview target and remains a performance limit. These measurements
are host rendering, not phone response. Full source state is not
cached across patches; fixed coefficients, capture encoding and held source
tables are prepared outside the sample loop.

## Acceptance and listening

Numeric checks cover legacy PCM preservation, complete recipe round trips,
capture immutability, source-off behaviour, causal grains and freeze,
bounded energy/headroom, pitch, FM rate convergence, cancellation, held
continuation and real instrument note-off. The audition isolates each new
feature at independent and coupled relationships, includes combined phrases
and repeats actual sustain regions eight times after a single arrival.
Raw and matched playback retain complete recipes and segment timings.

Verified: existing shared primitives and instrument/export paths were read
at `5e953da5`. Proposed: new gains, gesture timing and render budgets.
Measured: initial Kotlin source/held tests, including actual future audio,
and the host timing sample above; full integration checks are in progress.
Heard: only the earlier model-2 plucked sound has owner feedback; model 3
remains unheard.

## As built and measured

The coordinated Kotlin run passed 71 focused synth/shell/CLI tests, including
all 134 exact model-2 baseline comparisons and the published model-1/2 PCM16
pins. Android debug assembly and device-test Kotlin compilation passed.
Device tests were compiled, not executed locally.

The captured foreground initially lost its late root under continuous octave
pressure. A smooth fundamental displacement and short finite answering breaths
corrected that balance. Across 18 BOW/CAPTURED pitch windows at C3/C4/C5,
the worst measured root error was 1.09 cents and confidence stayed at least
.978. Model-3 elder OFF/ON leaves the physical foreground bit-exact with
COMMUNE zero; nonzero coupling changes all three foreground exciters. The
bow measurement comes from inside its friction junction, so an output mix
alone cannot satisfy the test.

The audition contains 19 studies, 57 WAVs and 19 portable score recipes.
Browser checks exercised all 24 OFF/ON seeks, playback/level switching,
review storage/export and downloads. Desktop and 320/390/768 layouts passed
without horizontal overflow. Three provisional handoff instruments contain
27 native zones; generation validates the actual MPC 3/MPC 2 programs,
sampler markers, PCM24 samples, release and editable metadata.

The [private extended audition](https://ohm-snipsnap-audition.bertcalm.chatgpt.site/expanded/)
keeps the accepted study available at its root. Its Recipe downloads are
complete audition scores, replayed through `generateOhmExpandedAudition`
with `-PohmExpandedReplay=/path/to/score.json`,
`-PohmExpandedReplayOutput=/path/to/output.wav` and optional
`-PohmExpandedReplayMode=raw|matched`. Handoff `.patch.json` files are
ordinary patches accepted by the CLI's `--patch` option.

Phone response, phone save/reopen and actual MPC loading/playback remain
physical-device checks. New source mappings, combinations and loop character
still need the owner's listening verdict.

## Why it is built this way

- Exciter choices act on the same body, so OHM keeps its relationship between
  solo sound, independent answers and shared resonance.
- Source-stage grains and freeze add memory without an unbounded audio
  feedback path or per-layer normalization erasing the energy relationship.
- A full populated buffer preserves actual audio; a single held sample
  would be a constant and would not preserve the source's texture.
- The attack and loop are separate sample regions, so a held key does not
  keep replaying the family's arrival.
- Renderer versioning keeps the accepted instrument available while the
  new sources and sustain are judged through listening.
