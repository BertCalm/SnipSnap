# Fired Bronze: stronger processor and amp

The characteristic gesture is **hand-struck contact → compressed, saturated body
→ more distinct repeats and space → driven speaker response**.

Job: CONCEIVE, a stronger recipe family following listening feedback. Baseline:
`c48470634d36a0b987c7793c1c0e830033c273a2`. Josh requested more processor and
probably more amp. The accepted dry renderer and Color v1 remain unchanged.
This is an authored sound-design revision, not new hardware calibration.

## Why the earlier settings were restrained

Verified in Color v1 and `Valve.gainFor`: processor DRIVE was .397 at default,
while the bus amp was .510. The model's steeper gain range starts at .6.
Only 35% of the compression branch reached the output. Even the earlier hot
alternative drove the processor to .496. The processor was therefore still
below that model's stronger saturation range.

The new defaults put processor DRIVE at .6656 and the bus/skin amp at .680.
Bronze amp DRIVE is .640. Compression is now 75% wet, with greater depth, a
faster attack and faster recovery. The delay feedback control rises from .12
to .25, shared-bus send scaling from .35 to .5, and reverb size from .12 to .26.
Amp drive is now independent of processor drive; this is the important control
change. Existing oversampled VALVE, COMPRESSOR, ECHO and SPRING code is reused.
No new oscillator, nonlinear curve or feedback topology is introduced.

## What to compare

The same accepted 96 BPM groove runs through six routes:

| Route | Processor | Amp |
|---|---|---|
| Dry | bypass | bypass |
| Previous | Color v1 | Color v1 |
| Processor pushed | new | Color v1 |
| Amp pushed | Color v1 | new |
| Both pushed | new | new |
| Edge | stronger new settings | stronger new settings |

The middle two routes isolate the changed stage while leaving its companion
at the previous setting. They are not processor-only or amp-only bypasses.
The new downloadable kit uses Both pushed. The isolated snare, Tin, open hat
and gong also have stage-separated comparisons. All sixteen pads have the
new treatment. A separate groove sums individually processed pads to represent
the export route; it does not share the main groove's compressor state.

```
unchanged dry source → EQ/gate → 25% direct + 75% compression
 → stronger 4× saturation → darker repeats + diffuse space
 → independently driven 4× amp/speaker → DC cleanup + end fade
```

Four 0–1 controls: PRESS .65 controls depth/ratio; HEAT .72 controls processor
drive; SPACE .45 controls sends; AMP .70 controls final amp drive. BPM remains
96, valid 60–180. Edge fixes PRESS .85, HEAT .90 and AMP .88 while retaining
SPACE/BPM. Recipe JSON contains the source, route and settings under a separate
`FIRED_BRONZE_PUSH` version-1 schema. Existing recipes never change their meaning.

STACK remains the selected direction: clear stage attribution, low structural
risk, one round. JUXTAPOSE would add a clean parallel amp path but risks comb
filtering without latency alignment. COUPLE would feed amp energy back into the
source and require a different stability/calibration project. No new engine is
justified: SKIN/TERRA cover source roles and the existing rack owns the effects.
No phone registration or HOLD is added.

## Limits and verification

Both nonlinear stages retain 4× oversampling and existing gain laws. These
primitives are peak-relative; per-pad drive is not an electrical input-level
simulation. Velocity still changes the dry source's timbre and native level.
The shared-bus pass responds to overlapping hits within one waveform. All
original long source frames are retained, with at most one added second of
space. Feedback stays below unity. The final peak bound does not stabilize a
feedback solver. No new higher-rate alias-reference measurement is claimed.

The main sonic risks are flattened attacks, loss of Tin/Na distinction, harsh
bronze upper partials, and effects masking faster patterns. These require the
matched listening comparisons. New perceived strength or quality is not claimed
as heard. Existing reference research and hardware-model limitations remain in
[Color v1 notes](FIRED_BRONZE_COLOR.md).

Tests check deterministic recipes, zero/soft velocity, numeric/control corners,
retained tail lengths, exact previous-reference identity, and independent
harmonic-to-fundamental power growth in each pushed nonlinear stage. The harmonic
probe uses a 220 Hz sinusoid and measures harmonics 2–5, which excludes simple
level gain as the explanation. This is not an instrument realism score.
Treatment timing uses three warmed host runs per pad, excluding source rendering.
Linear bounded passes need no solver cache. Phone performance is not established.

```sh
./gradlew --no-daemon :synth:test --tests '*FiredBronzePushTest' --tests '*FiredBronzeColorTest' --tests '*DeterminismTest.FIRED*'
./gradlew --no-daemon :synth:generateFiredBronzePushAudition
python3 scripts/fired_bronze_push_page.py
```

Measured harmonic/fundamental power on the Kotlin 220 Hz probe rose from
0.000736 to 0.084857 for the processor, and from 0.009196 to 0.091948 for the
amp. This is about 115× and 10× respectively in the measured power ratio, not
output volume. The new harmonic test passed. The focused Kotlin build and generator completed with exit 0: 11 tests passed
(3 new processing probes, 3 previous processing probes, 5 determinism checks).
Independent export validation found 16 pads, 48 samples, correct velocity zones
and hat choking; dry and previous-treatment grooves are byte-identical to their
prior WAVs. All page links and ZIP integrity checks passed.

Native WAV analysis over the first ten seconds of the full groove measured
peak-to-RMS crest factors of 16.106 dB for Previous, 9.814 dB for Processor pushed,
11.571 dB for Amp pushed, 8.694 dB for Both, and 7.917 dB for Edge. These describe
dynamic range, not a listening verdict. Default full groove peak is .3556;
Edge is .4033. Gong remains 9 seconds including added space.

Warmed treatment-only medians: kick 176.3 ms, snare 73.7 ms, Tin 55.8 ms,
gong 489.1 ms on this host. Source rendering is additional. The short-hit target
is not met consistently; no phone performance claim is made.
Repository-wide `test --fail-fast` reached its 180-second limit (exit 124);
no complete-suite pass is claimed. Hardware loading and browser interaction
are unverified.

For hosting, standalone audition audio is encoded as lossless FLAC. The
site-packaging script decodes every conversion and checks SHA-256 against the
original PCM before removing the duplicate WAV. All 618 current Site clips
passed; MPC ZIPs retain their WAV files. This reduces expanded assets below
the hosting limit without changing samples.

## Why it is built this way

- Drive now reaches a more nonlinear part of the model, so the change survives level matching.
- A larger compressor contribution makes body and recovery more prominent.
- Independent processor and amp settings expose which stage adds useful character.
- The old treatment and dry sources remain exact references, with original long tails retained.
- Edge is a separate comparison so its loss of attack or articulation does not become the only available direction.
