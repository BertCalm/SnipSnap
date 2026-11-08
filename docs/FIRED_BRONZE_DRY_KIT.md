# Fired Bronze: dry kit round

The characteristic gesture is **recognizable hand contact → shaped drum body →
articulation-specific rattle or bronze closure → an unprocessed tail**.

Repository baseline: `ed202d4138d0ee5166cc85a80ba3705523cf3f6f`.
Job: CONCEIVE, implementing the approved direction as kit recipes. Josh described
R2 as “Way better”; that supports retaining its dry source character. It does
not establish acceptance of these new drums or historical authenticity.

## Landing

A separate, versioned `FiredBronzeDryKit` recipe family and 16-pad MPC program.
Both older renderers and their saved recipes remain unchanged. No new engine,
factory registration, phone picker, or effect rack is required for this round.
The nearest siblings are SKIN (modal kit drums) and TERRA (world percussion).
Neither provides these five recording-derived articulation envelopes, but both
already cover the broad drum categories, so another registered engine is not justified.

The selected direction is SPLICE: keep the measured contact and spectral behavior
while supplying the missing kit function. Gesture clarity high, roster novelty
limited to this source family, risk moderate, one build round. Alternatives:
MUTATE would transpose the existing voices alone, with low cost but weak snare/hat
function; COUPLE would simulate a membrane and bronze collision network, with
higher solver and calibration risk and at least two rounds. The user selected
the first direction by approving the preceding recommendation.

## Construction and evidence

```
R2 Ga articulation → lower register + fixed-pitch membrane support → kick
R2 Na + Te → rectified vibration follower → band-shaped rattle → snare
published kettle frequency family → expanded bronze partials → closure loss → hats
R2 source recipes ───────────────────────────────────────────────→ remaining pads
                                      ↓
4× band limit → decimate → DC cleanup → house level → native velocity
```

The kick retains the fitted Ga attack, shifted down an octave at default, and
adds a 60.4 Hz decaying support. The support has no pitch dive. Its gain, decay
and onset are authored shapes. Source transposition beyond the original range
is not a measured performance. The snare lowers Na and adds Te. Its rattle reads
the rectified body through a release follower and lowpass; this is one-way
control, not a simulated wire collision or a claim about a traditional drum.

Open, closed and foot hats share 24 proposed bronze partials. Their ratios expand
the published kettle row; this is family resemblance, not measured cymbal data.
Closure increases loss in that same bank. Noise supplies only brief contact.
All partials above 17 kHz are omitted, and synthesis runs at 176.4 kHz before the
house anti-alias filters. No feedback network or numerical solver is present.
No phone benchmark or higher-rate alias-error bound is claimed.

Controls: TUNE defaults .5 and shifts pitch by ±12 semitones (source layering
clamps near the profile's lower range); TOUCH defaults .5 and changes partial
weight or rattle activity; DAMP defaults 0 and adds positive loss. Velocity changes
contact color before one final gain. TUNE×TOUCH and TOUCH×DAMP corners are tested
for finite output. These are offline recipe fields, not a registered macro/HOLD
interface. Long sources are filed LOOP by the house duration convention; they
are finite one-shots, not seamless loops. No HOLD behavior is claimed.

## Pads and playback

A01 kick, A02 snare, A03 closed hat, A04 open hat, A05 Tin, A06 Tun,
A07 Na, A08 Te, A09 Ga, A10 moving bass, A11 open clay, A12 covered clay,
A13 low kettle, A14 high kettle, A15 foot hat, A16 gong.

All three hats share choke group 1. The MPC export carries three timbre layers;
soft layers are peak-matched because MPC supplies velocity gain. Native audition
files retain the renderer's velocity gain. The groove scheduler applies choke
with a 3 ms fade. Its two versions share the same 96 BPM foundation. Full adds
hand strokes, clay and kettle, then gives the final gong its complete 8-second
tail. This is an authored four-bar hybrid pattern, not a traditional tala or
gendhing. No pedal or amp is applied in this round.

## Reproduce and inspect

```sh
./gradlew --no-daemon :synth:test --tests '*FiredBronzeDryKitTest' --tests '*FiredBronzeR2Test' --tests '*DeterminismTest.FIRED*'
./gradlew --no-daemon :synth:generateFiredBronzeDryKitAudition
python3 scripts/fired_bronze_dry_kit_page.py
```

The render target is tens of milliseconds per short source on a warmed host;
the 8-second gong remains a separate long-tail cost. Work is bounded at 24
partials and 1.2 seconds for new drums; the existing R2 profiles are cached in
its five-entry immutable map. The audition writes three-run warmed medians.

## Verification ledger

- Verified: R2 raw synthesis, output helpers, velocity matching, arranged-kit
  export and choke API exist on the baseline above.
- Heard: Josh preferred the R2 dry source study. New kit listening is pending.
- Proposed: new kick support, snare rattle, bronze spectrum and drum balance.
- Uncalibrated: clay and pressure candidates retain the R2 caveats.
- Measured: focused Kotlin run passed with exit 0: 11 tests (3 kit probes,
  5 R2 probes, 3 determinism checks). The same command rendered the pack.
- Measured: warmed median render times were kick 37.6 ms, snare 65.8 ms,
  closed hat 6.4 ms, open hat 38.7 ms, foot hat 6.5 ms; gong 876.6 ms.
  These are host timings. Zero timing entries in CSV mean not benchmarked.
- Measured: exported XPM has 16 populated pads, 3 contiguous velocity zones
  per pad (1–41, 42–84, 85–127), and choke group 1 on A03/A04/A15 only.
  All sample references resolve, and all WAVs are mono 44.1 kHz PCM24.
- Measured: native foundation/full groove peaks are .3502/.3533, without clipping.
  Independent WAV analysis found the open hat's .1–.2 s energy relative to
  its first .04 s was .316; closed was .000166. This checks closure behavior,
  not perceived realism. `validation.json` retains the analysis output.
- Repository-wide `test --fail-fast` reached the 180-second limit (exit 124).
  It is incomplete; no full-suite pass is claimed. A Kotlin daemon startup
  warning occurred during shell compilation; the run continued afterward.
- MPC hardware loading and browser interaction are not verified in this environment.

Reference attribution and licenses remain in R2's `NOTICE.txt`, copied into the
pack. No new recordings were acquired. See [R2 notes](FIRED_BRONZE_R2.md) for
individual recording provenance and published kettle/gong sources.

## Why it is built this way

- The accepted strokes remain intact so there is a stable reference for every change.
- The kick gets a low tail without replacing its hand-struck attack with a pitch sweep.
- The snare's rattle follows body vibration so it ends with the instrument rather than hanging as separate hiss.
- One bronze bank makes open and closed hats related articulations; shared choke makes that relationship playable.
- The main risk is that transposition and extra rattle obscure the source character. Dry pads and matched grooves expose that before processing is added.
