# ORIGAMI TOUCH response

Status: implementation contract; integrated validation and owner listening pending.
Repository baseline: `482c55a146b1569ba9ccad27e4ebd274ad398df4`, read 2026-11-05.

The characteristic gesture is **soft strike with a rounded body → stronger strike with more of the accepted upper body → one fold or unfold → release**.

## Problem and landing

On November 5 the owner reported, “Soft - hard sound the same.” The published
Nov 1 FOLD bank contained distinct, correctly routed samples. Its Native soft
and hard levels differed by 6.80 dB, but Matched playback removed that gain cue.
Gain-independent whole-note correlation remained .993–.999. In UNFOLD, every
velocity ended at FOLD zero, leaving settled-body residuals of only
.0003–.0285% after a best scalar fit. The common root and octave dominated the
body, so changing the smaller reflected share alone gave limited contrast.

This round adds an explicit **TOUCH expression mode** to the existing engine.
It changes upper-bank balance beneath the accepted hard endpoint. It adds no
engine, voice, macro, bright excitation, distortion or new fold geometry. PRISM
and CREASE are the nearest developing-spectrum and touch-deformation siblings;
ORIGAMI already owns the root/source/reflection relationship needed here.
The accepted source definitions and the previous LEVEL/FOLD recipes remain.

## Chosen response

Keep the existing FOLD-depth law. Add a second velocity law that attenuates the
complete upper bank against the separate raw fundamental, including the common
octave, dry source and reflected material. Upper bank means all non-anchor
components, including reflections below the root; it is not a frequency cutoff.
This also acts after UNFOLD reaches
zero fold. Candidate B was selected over the gentler candidate A for its larger
measured separation; no listening preference has been assigned to either.

| Mode | Response |
| --- | --- |
| LEVEL | Existing volume response and exact saved behavior. |
| FOLD | Existing fold-depth-only response and exact saved behavior. |
| TOUCH | The same fold-depth response plus the new upper-bank scale. |

For actual performed velocity `v` in 0–1:

```text
depthScale(v) = .25 + .75 * v^1.25
upperScale(v) = .1 + .9 * clamp(v / .85, 0, 1)^2
raw note = raw root + upperScale(v) * existing raw upper bank
    -> one existing whole-note output level stage
```

The .1 floor, .85 unity point and squared shape are authored values. At actual
velocity .85 or above, use literal delegation to the old FOLD path, avoiding a
new arithmetic route at that endpoint. This preserves old FOLD audio at .85
and 1 exactly. The original authored fold amount is reached at actual velocity
1; the old depth law still reduces it at .85. TOUCH never adds upper material
beyond the old FOLD sound at the same velocity.

The law uses absolute performed velocity. Preview and exported layers multiply
the saved ceiling by .3/.6/1 before applying it. At the factory ceiling .85,
those actual velocities are .255/.510/.850 and upperScale is .181/.424/1.
A custom .42 ceiling gives about .31974 even at its own FULL touch: the law is
not renormalized to make a quiet saved ceiling reach unity.

Source ratios, phases, raw root, partial frequencies, geometry, onset envelope,
gesture clock, finite duration and held loop positions remain unchanged. The
whole-note level stage can change the final fundamental amplitude when upper
material is reduced; this is a timbral balance change, not sample identity for
the finished root. FOLD zero and SOURCE diagnostics still receive TOUCH balance.

```text
saved recipe + played note + actual strike
    -> existing FOLD-depth law and once-only gesture
    -> unchanged root / source / reflected partials
    -> root + TOUCH-scaled complete upper bank
    -> one existing output level stage
    -> finite pad, or held maker with per-layer loops and key-up release
```

## Factories and compatibility

The current six performance factories keep their names, macros, seed `20261012`,
velocity .85, model 4 and paired gesture directions. Only their explicit
expression choice changes from FOLD to TOUCH.

| Voice | BLOOM / UNFOLD | FOLD | MOTION |
| --- | --- | ---: | ---: |
| CRANE | Paper Bloom / Paper Unfold | .65 | .35 |
| FAN | Fifth Bloom / Fifth Unfold | .65 | .55 |
| STILL | Soft Bloom / Soft Unfold | .62 | .70 |

TUNE, CREASE, TURN and WARP remain .5. `OrigamiPresets.performance()` supplies
these TOUCH factories; `foldPerformance()` preserves the Nov 1 FOLD six.
`references()` retains the original six LEVEL patches. Factory lists still
contain twelve choices, four per voice, and choices three/four are the current
performance pair. The existing sixteen-pad starter keeps its exact membership
and audio.

Expression metadata remains model 1 with the added explicit mode discriminant
`TOUCH`. Saved LEVEL/FOLD JSON and audio are not reinterpreted or upgraded.
Missing/null expression still means LEVEL. Expressive modes require geometry
model 4. Source, geometry, gesture and held model versions do not change.
Saved presets, navigation, SEND, CLI and authoring sidecars retain the complete
mode and the authored velocity; temporary preview strengths never replace it.

## How to compare and play

On SYNTH, choose a performance factory and select SOFT, MID or FULL under
PREVIEW HIT. Changing that selection or a control renders quietly. **Tap
AUDITION to hear it.** A tap while rendering queues the newest result. Selecting
a hit alone does not promise playback. The same manual AUDITION policy applies
to gesture/expression edits.

The browser performance player opens in Native, retaining the real level
response. Matched remains available to compare color at equal level. The
portable starter embeds all 18 Native singles. Both pages use native HTML audio
from an explicit play action, without requiring AudioContext playback.

SEND keeps one finite reference. Apply the existing SOFT HITS workflow to get
per-hit pad color; SEND does not generate those layers automatically. FAN/STILL
MAKE INSTRUMENT instead prepares three layers per root for FOLD or TOUCH.
Layer windows are MIDI velocities 1–42, 43–84 and 85–127. Rendered velocities
stay beneath the saved ceiling; each complete soft/mid sample is peak-matched
to hard before the player applies key gain once. Every layer owns its loop.

The four MPC programs use nine sampled roots, MIDI 48–72 at three-semitone
intervals, three layers and 27 mono 44.1 kHz PCM24 WAVs each. Outer playback
zones extend to MIDI 0–127 through edge transposition. Default release remains
.6 seconds. Keep the root `.xty`, sibling `_[TrackData]/` with `.xpm` and WAVs,
`.instrument.json` playback metadata and `.origami-keys.json` full recipe.
Downloads target MPC; Android has no import route for these keygroup ZIPs.
On the phone, select the factory and use MAKE INSTRUMENT. MPC hardware playback
and the revised sound on a physical phone remain unverified.

## Reproduction and render cost

```sh
./gradlew --no-daemon :shell:generateOrigamiPerformanceBank \
  -PorigamiPerformanceOutput=/path/to/origami-performance-bank
```

Default output is `testkit/origami-performance-bank`. It contains six exact
recipes, 18 singles, six musical phrases/chords and four sequential
soft/mid/hard held takes, with Native and whole-take Matched versions. Held
evidence must record the actually selected layer and at least three real wraps
for each played touch. Match a complete performance once, keeping its internal
note levels intact.

To regenerate the historical family in a separate output directory, add
`-PorigamiPerformanceLegacyFold=true`. Direct generator invocation accepts
`--legacy-fold`. New and historical artifacts retain their own source and recipe
fingerprints; do not relabel old FOLD WAVs as TOUCH.

The change reuses existing root and combined diagnostic paths and the gesture
basis cache; velocity stays outside the geometry cache key. It introduces no
new simulation. Root/upper recombination happens before one level stage.
The standalone JVM prototype's mix-plus-level operation had median **1.97 ms**
and maximum **6.97 ms**. These exclude complete re-render, export, Android
scheduling and playback latency. Final integrated cold/warm costs, cache bytes,
package bytes and decoded sample memory must be measured again from the new
bank; the prototype is not a phone performance promise.

## Evidence and listening ledger

Evidence collected before implementation lives in the Library's
`origami-touch-review-Nov5/` and `origami-touch-prototype-Nov5/` directories.
The review's `measurements.json` and `review.txt` describe the old published
bank. The prototype includes source, exact execution commands, WAVs, hashes,
`summary.json`, `adjacentwindow.json` and `host-cost.json`.

- **Verified:** the standalone probe replayed all 18 old canonical Float32 and
  PCM16 takes exactly. All 12 candidate A/B hard Float32 endpoints matched the
  old hard rendering. Current APIs and serialization were read at the baseline
  above; these prototype checks do not stand in for integrated tests.
- **Measured:** candidate B's minimum adjacent-touch body distance was .179275;
  its minimum soft/hard distance was .516370 across the six presets. Windows
  were `[gestureEnd + .12, gestureEnd + .32)` seconds, each independently
  RMS-normalized, using Euclidean RMS distance on existing PCM16. The old
  FOLD minima were .00008575 and .00012016. Prototype cost is scoped above.
- **Proposed:** regression bars of .12 adjacent and .30 soft/hard for that
  exact window/method. They detect collapse of the intended numerical shape;
  they are not thresholds of audibility or musical quality. Integrated gates
  must also cover old LEVEL/FOLD audio/JSON, hard delegation, all gestures,
  source/root diagnostics, finite SOFT HITS, three-layer held exports, loop
  continuity, recipe retention and quiet-render/manual-AUDITION behavior.
- **Heard:** the owner reported that the old soft/hard response sounded the
  same on Nov 5. The new TOUCH shape has no owner listening verdict. Its risks
  are an overly plain soft body, uneven steps between touches and loss of a
  useful fold color at quieter ceilings. Numerical separation cannot settle
  those questions. Final integrated JVM/Android/browser validation is pending.

## As built

The source contract selects explicit TOUCH for the six current performance
factories and retains the old FOLD family for replay. The generator defaults to
TOUCH, offers the legacy flag, opens the full player in Native and embeds
Native singles in the portable page. Final build, generated-bank, browser and
device results belong in the execution handoff after those checks finish.
No production validation result is inferred from this documentation pass.

## Why it is built this way

- The whole upper body changes, so touch can remain distinct after UNFOLD has opened completely.
- The accepted hard sound is the ceiling; stronger playing adds no new harsh upper material.
- The root, source frequencies and gesture timing stay familiar while the body balance changes.
- An explicit mode preserves old saved sounds and makes the intentional softer-tone revision visible.
- Native playback keeps the real playing dynamics; optional Matched playback helps judge the tone separately.
