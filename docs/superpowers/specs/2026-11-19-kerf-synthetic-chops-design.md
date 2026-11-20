# KERF: Synthetic Chops. Engineering Specification

Version 1.0 · 2026-11-19

Status: rounds 1 and 2a to 2d built (the Kotlin renderer and fitter, the kit action and its
command, the pad sheet's SYNTH TWIN, and SYNTH TWIN ALL with BLEED and SLOP on a twin; see
"As built" at the end). The engine is not written. Heard by the owner: the Python prototype,
the round-1 Kotlin page ("Sounds good, continue", 2026-11-19) and the round-2 loop-to-kit
page ("Audition is fine", 2026-11-20). Nobody has made a twin on a phone yet. KERF is a
working name (a kerf is the sliver a saw removes); no maker or product is named by it.

Owner decisions, 2026-11-19 ("I take your recommendations proceed"): the patch format is
carried the recommended way (section 6, A); velocity starts from the ghost-against-snare
comparison (section 11, now run); the kits stay at BLEED 1.0 and SLOP 0.5 until a listen
says otherwise. Later the same day ("I take your recommendation"): KERF labels its own
pads from what it measures, in place of the capture classifier.

Repository truth: read at `5f208336`. Grepped and found: `Chopper.byTransients`,
`Transients.detect`, `Fft.forward` / `Fft.inverse` (`:audio`), `Desample.nearest` and
`Desample.voicesFor` (`:synth`), `Dsp.seedFor`, `Dsp.Noise`, the sealed `Patch` interface
(`Patches.kt`), `ArrangedPad` (`kit/.../KitAssembler.kt`), `StrikeVelocity`. `:synth` already
depends on `:audio`. Not found and not assumed: any existing patch type that carries a
payload larger than a macro map.

## 1. Summary

The characteristic gesture is **the last hit still ringing → a new hit lands on top of it →
its own ring and wash → the knife comes down early or late**.

KERF renders a drum hit the way it sounds when it has been chopped out of a loop: with the
neighbouring hits leaking in and the cut points slightly off. It does this from numbers
only. A fitter measures a real chop and writes a patch; a renderer turns a patch back into
audio. The renderer is computed (oscillators, shaped noise, resonances). The chop behaviour
(bleed, slop) is a model of what a hand chop does, not a recording of one.

It is built as one renderer with two front ends:

- a **capture-side tool**: chop a loop, get a synthetic kit back;
- a **drum engine**: authored patches behind macros.

## 2. Goals and scope

Musical goal: break-style one-shots that keep the glue of the loop they came from, and that
can be re-cut, re-tuned and re-bled after the fact, which a sample cannot.

V1 includes: the renderer, the patch format, the fitter, BLEED and SLOP, four macros beyond
those, kits from the tool.

V1 excludes: stereo, a modelled saturation or tape stage (the source's colour is copied as
spectrum only), tempo-following, loops (every render is a finite one-shot), HOLD.

Factory content: the owner holds commercial licences for the four packs the prototype was
fitted to and has decided they are usable as sources (2026-11-19). The repository holds
fitted numbers only, never the pack audio.

## 3. SnipSnap contract

Offline, mono, 44.1 kHz, a pure function from patch to `Snip`. Seeds through `Dsp.seedFor`;
noise from `Dsp.Noise` only. One departure from the pitched-engine contract, stated here so
it is a decision and not an accident:

- **No 4× internal rate.** The renderer has no nonlinear stage: sines below 15 kHz, noise
  shaped by a linear filter bank, and band-limited bursts. Nothing in it can alias, so
  oversampling would cost four times the work for nothing. If a saturation stage is added
  later it must run oversampled in the house way (`Tide.bandLimit` then `Dsp.decimate`).
- **Drum classes are real.** KERF is a drum engine, so its voices file as KICK, SNARE,
  HAT_CLOSED, HAT_OPEN and PERC, as SOOT's and BREAKER's do, with a test that classifies
  real renders.
- **No HOLD.** Every hit is finite and under the classifier's 1.5 s loop threshold; a patch
  longer than that is refused at fit time.

## 4. Signal flow

```
patch (numbers), macros, neighbours' patches
        │
        ├──▶[KerfBody: sine partials, glide + start + decay]──────────────────────────┐
        │                                                                             │
        ├──▶[Dsp.Noise(seed)]──▶[KerfColour: ≤48 narrow resonances]──▶[KerfBands: 19   │
        │                         (static per patch)                   bands × layers, │
        │                                                              time-varying]──▶(+)──▶ hit
        ├──▶[KerfTicks: ~1 ms bursts at exact samples]────────────────────────────────┘
        │
        ▼
[KerfChop]  hit  +  BLEED × (previous patch rendered past its own cut, fading)
            start moved early (pre-roll of previous tail) or late (attack shaved)
            end run into the first ms of the next patch
            edge fade 1 ms → 0.1 ms
        │
        ▼
[Output: remove DC → level → peak guard]

Fitter (capture side, runs once per hit, not per knob turn):
[Chopper.byTransients]──▶ slice ──▶[KerfFit: partials → metal → layers → bands → colour → ticks]──▶ patch
```

`KerfBody`, `KerfColour`, `KerfBands` and `KerfTicks` are pure functions of the patch and
the macros. `KerfChop` additionally reads two neighbour patches.

## 5. Architecture and separation

| Unit | Module | In | Out |
|---|---|---|---|
| `KerfPatchData` | `:synth` | JSON | the fitted numbers (section 6) |
| `KerfRender` | `:synth` | patch data, macros, neighbours | `Snip` |
| `KerfFit` | `:synth`, beside `Desample` | `Snip` (one slice), seed | patch data |
| `Kerf` + `KerfPatch` | `:synth` | voice, macros | `Snip`, via `KerfRender` |
| tool entry | `:shell`, beside `KitBuilder.desamplePad` | a loop | a kit of patches |

Nothing feeds back. The fitter never runs inside a render. The renderer never reads audio.

`KerfFit` sits beside `Desample` because that is the shipped precedent for "captured hit in,
patch out", and because `:synth` already depends on `:audio` for `Fft` and `Chopper`.

## 6. The patch format

This is the contract between the fitter and the renderer, and the thing a saved kit stores.
Version it (`MODEL_VERSION`, the PITCHWHEEL pattern) so a save keeps the model it was made
with.

```
seconds   Float                     length of the slice
seed      Int                       from Dsp.seedFor(loop, index); drives noise and SLOP
partials  [ {hz_end, glide_hz, glide_s, amp, start_s, attack_s, t60, phase} ]   ≤ 23
bands     [ {snap, snap_t60, level, t60, floor, attack_s,
             layers: [ {at_s, snap, level} ]} ]                                  19
colour    {base, res: [ [hz, gain, bandwidth_hz] ]}                              ≤ 48
ticks     [ {at_s, amp, hz} ]                                                    ≤ 6
events    [ Float ]                 times of later hits inside the slice
```

Size: about 6 KB of JSON per hit after rounding to six significant figures (measured:
1.27 MB for 64 pads, each stored with both neighbours).

**Open decision, the one that shapes the Kotlin work.** Every shipped patch is a voice enum
plus a macro map. A fitted KERF patch does not fit in a macro map. Two ways to carry it:

- **A (recommended):** factory voices are an enum whose entries point at bundled patch-data
  resources, so `KerfPatch` keeps the house shape (voice + macros) for the engine. Kits made
  by the tool store their patch data in the pad recipe, the way a rendered bank carries
  provenance. This needs one new field in the pad recipe and leaves `Patches.decode` alone.
- **B:** a `KerfPatch` that carries the data inline. It makes one type serve both front
  ends, but it is the first patch whose JSON is kilobytes, and every test that reflects over
  the sealed `Patch` interface would meet it.

## 7. The model, one section per mechanism

All of it is literal: these equations run. What is not simulated is listed in 7.7.

### 7.1 Body partials

```
f(t)   = hz_end + glide_hz · exp(−t / glide_s)
env(t) = ramp from start_s to attack_s, then 10^(−3 (t − attack_s) / t60)
y(t)   = amp · env(t) · cos(phase + 2π ∫ f)
```

Fitted greedily, strongest first, each subtracted before the next is sought. Up to 7 under
1500 Hz. A candidate is kept only if its projection onto the residual is at least 0.35;
band-passed noise also looks like a sine to the fitter, and this is what refuses it.
`start_s` exists because a tone that enters late must not ramp from the start of the slice.

### 7.2 Noise bands and layers

19 bands built from FFT bins (256-point, hop 32): single bins to about 1.4 kHz, then
widening by a third of an octave. Built from bins so no band is ever empty. Per band, in
power:

```
e(t) = snap · 10^(−6 t / snap_t60) + level · 10^(−6 t / t60) + floor
       + Σ layers on(t − at_s) · [ snap_k · 10^(…) + level_k · 10^(…) ]
```

Two decays because a hit is a fast crack on a slower ring; with one decay the fit kept the
ring and lost the crack (measured: renders came out at 0.5 to 0.8× the source centroid).
`floor` is the part that never dies inside the slice: hat wash, room, hiss. Layers exist
because a band with one peak cannot hold a second sound; without them a hat on a kick's
tail vanished. Solved by non-negative least squares over a 16-step decay grid.

What the noise must supply is "source minus body", band by band, on smoothed band
energies, then rescaled so body plus noise carries the source's total in that band.
Subtracting bin by bin and clamping left a +4 dB low-mid bias (measured).

### 7.3 Colour

A cymbal does not ring as steady sines. Steady sines were tried; the fitter refused nearly
all of them (0.08 per hit above 500 Hz) because each tone's phase wanders. It rings as
noise through many narrow resonances, which third-octave bands blur into hiss.

```
D(f)      = P(f) / C(f)        P: 21 Hz-step power spectrum of the residual
                               C: P blurred to a third of an octave
colour(f) = base + Σ gain_k / (1 + ((f − hz_k) / (bandwidth_k / 2))²)
```

Spikes of `D` above 2.2 between 1.5 and 16 kHz become resonances, strongest 48 kept. The
curve is normalised to mean 1 inside each coarse band, so the band fit's energy is not
disturbed. The noise passes through it before it is shaped in time.

### 7.4 Ticks

The noise is shaped in 5.8 ms windows and cannot make anything sharper. Where the source's
top end (above 2 kHz) spikes at least 1.25× harder than the render, a burst is added at
that sample: two cycles of a damped sine at the tick's own brightness plus seeded noise,
both gone in about 1 ms. Each tick is then checked against the render and scaled back if it
would exceed the source's own spike.

### 7.5 Bleed

```
out(t) = hit(t) + BLEED · tail_prev(t) · 10^(−3 t / 0.5 s)
```

`tail_prev` is the previous hit's patch rendered past the point where it was cut. The fit
already contains some of the previous hit, so BLEED is extra on top; at BLEED 1 it adds a
median of −24 dB relative to the hit (10th to 90th percentile −42 to −7 dB).

### 7.6 Slop

Drawn once per pad from the pad's seed, so a pad always chops the same way.

| Draw | Range at SLOP 1 | Effect |
|---|---|---|
| start early (3 pads in 4) | up to 12 ms | the previous hit's tail sits in front of the attack |
| start late (1 pad in 4) | up to 3 ms | the attack is shaved; it starts with a tick |
| end late | up to 36 ms, skewed short | the slice catches the front of the next hit |
| edge fade | 1 ms down to 0.1 ms | the cut can click |

Late starts are rarer and capped because they cost punch. BLEED 0 with SLOP 0 is exactly
the clean render (checked in the harness).

### 7.7 Not simulated

- The source's saturation, compression and tape colour. They are copied as spectrum, so
  they do not respond to level.
- The beater or stick as an object. A tick is a burst placed where one was measured.
- Stereo. The sources measured 0.02 to 0.19 side-to-mid; the render is mono.
- Time-varying colour. The resonance bank is fixed for the slice, so when a cymbal enters
  late over a kick, the kick's click also passes through the cymbal's resonances.

## 8. Excitation and energy budget

There is no feedback path anywhere in the renderer, so there is no stability argument to
make. Every component is a decaying envelope times a bounded source. Level is fitted
absolutely (body and noise are each calibrated against the source), not normalised per
event. The output stage levels once.

## 9. Macro contract

Constants are shape until listened to.

| Control | Default | What it changes in the model | Required interaction | Test |
|---|---:|---|---|---|
| TUNE | .50 | scales every partial's `hz_end` and `glide_hz`, the colour's centres and the band centres, ±12 semitones | | body pitch within 20 cents of the shift at both ends |
| CUT | 1.0 | fraction of `seconds` rendered before the edge | CUT × BLEED: a short cut leaves mostly bleed | render length equals `seconds × CUT` to the frame |
| DECAY | .50 | multiplies every `t60`; `snap_t60` only shortens | DECAY × CUT sets how loud the hit is at the edge | level at the edge monotonic in DECAY |
| BLEED | .50 | level of the previous patch's tail, and scales `floor` | BLEED × SLOP: an early start shows the bleed before the attack | energy before the attack monotonic in BLEED |
| SLOP | .50 | the size of the four draws in 7.6 | as above | SLOP 0 with BLEED 0 is byte-identical to the clean render |

Five macros. No HOLD. BLEED's low half is "less than the source had" (it scales `floor`
below 1), so the knob runs dry chop → as found → smeared.

## 10. Voices

Voices are fitted patches the owner approved by ear, not hand-authored tables. R1 ships a
kick and a snare (the house pattern of two voices first) chosen from the prototype's Kit B,
which has the most complete class spread. The rest wait for the audition gate. Voice and
preset names must not carry pack, maker or loop names; `PresetTestSupport.trademarkBlocklist`
gains the four pack makers' names.

A voice is three patches, not one: the hit and the two neighbours BLEED and SLOP read.

## 11. Velocity and round robin

The owner's gate requires both through rendering, saving, playback and export, and a
volume-only velocity does not pass.

**Round robin** has a natural answer: each take is the same patch with a different SLOP
draw and noise seed. The takes are genuinely different strikes (different pre-roll, a
different overrun, different noise), not copies. To prove: takes differ sample for sample
and by a stated minimum in onset position; the pool survives save and load.

**Velocity** needs a design decision before R3. Proposed, not prototyped:

- a softer hit lowers `snap` and every tick's `amp` against `level` (less crack, same ring);
- it shortens `glide_hz` on the body's first partial by v² (the `StrikeVelocity` tension
  rule, reused as a law, not as code);
- it rolls off the top bands' level progressively;
- BLEED is **not** scaled, so a ghost note sits deeper in its neighbours' wash. This is the
  part a sample cannot do and the reason to do velocity in the model.

### What the recordings say (measured 2026-11-19)

`prototype/kerf/ghost_vs_snare.py` compares the fitted patches of ghost notes with the full
snares of the same loop: same drum, same room, same chain, different stick force. Nine loops
have at least two of each (67 snares, 46 ghosts). Each figure is ghost minus snare inside
one loop, then the median across loops.

| Quantity | Ghost against snare | Consistent in |
|---|---:|---:|
| Peak level | −11.1 dB | 9 of 9 loops |
| Bleed under the attack, against the hit | +12.2 dB | 9 of 9 |
| Floor against the hit's own energy | +5.3 dB | 8 of 9 |
| Tick against the hit's peak | −2.2 dB | 7 of 9 |
| Spectral centroid | 0.86× | 6 of 9 |
| Top-end share of the hit (above 3 kHz) | −1.9 dB | 5 of 9 |
| Crack against ring | −1.2 dB | 5 of 9 |
| Body pitch, pitch glide | no difference | 4 of 8, 3 of 8 |

What this settles:

- **The wash and the bleed do not follow the hit down.** A ghost note is 11 dB quieter and
  the bleed under it is 12 dB louder against it, which is the bleed staying where it was.
  This is the strongest finding and it confirms the proposal: velocity scales the hit, not
  BLEED and not `floor`.
- **A soft hit is slightly duller and its tick slightly softer**, at about 0.86× the
  centroid and −2 dB of tick per 11 dB of level. Both are real but modest.
- **The crack-against-ring and top-share laws are not supported** as stated: 5 of 9 loops is
  a coin toss. Do not build a strong version of either.
- **The v² glide law has no support here.** These snares do not glide at any level. It may
  still hold for kicks; these loops have no soft kicks to test it on.

Revised proposal for R3: velocity scales `snap`, `level`, the partials and the ticks
together; `floor` and BLEED stay fixed; the top bands roll off gently (to about 0.86×
centroid at −11 dB); ticks fall 2 dB further than the hit at −11 dB. Constants are shape
until heard.

## 12. Render budget and caching

| Stage | Python harness | Note |
|---|---:|---|
| Render, one hit | 12 ms | measured; the per-knob cost |
| `chopped`, one pad | about 3 renders | hit plus both neighbours |
| Fit, one hit | 555 ms | measured; once per chop, never per knob |
| Colour and ticks only | 90 ms | measured (462 hits in 41 s) |

The Kotlin target is tens of milliseconds per knob turn. Cache the two neighbour renders,
keyed by their patch identity, `MODEL_VERSION`, TUNE and DECAY; BLEED, SLOP and CUT then
cost one render and a mix. The colour filter is static per patch and TUNE: cache the
filtered noise under the same key.

The fit is a one-off action with a progress state. At 0.5 s per hit a 16-pad kit takes
about 9 s in Python; no Kotlin figure exists.

Implementation note for the band stage: the prototype shapes noise with an STFT (256-point,
hop 32, `Fft` exists in `:audio`). A bank of 19 band-pass filters with per-sample gains is
the alternative. Start with the STFT: it is what was heard, and the goldens can pin it.

## 13. Numerical and output requirements

Determinism (byte-identical, every voice, both chop settings) with a `DeterminismTest`
canary. Finite and bounded output. |mean| under 0.005. Peak under a stated ceiling. No
aliasing probe is needed while the renderer has no nonlinearity (section 3); a test should
assert that property by rendering at 44.1 kHz and at 88.2 kHz and comparing below 18 kHz,
so a later nonlinear stage cannot slip in unnoticed. Render length equals the filed class
to the frame. JSON round trip of the patch data. A non-finite macro falls back to its
default. The classifier files each voice in its own drum class.

The Python LCG is not `Dsp.Noise`, so Kotlin renders will not match the Python WAVs sample
for sample. Port parity is judged on the fit metrics in section 16, not on bytes.

## 14. Integration

Rows of the registration checklist (snipsnap-conventions.md §3) that apply at each stage:

- **R1 (renderer):** `KerfRender.kt`, `KerfPatchData.kt`, their tests. No registration.
- **R2 (tool):** `KerfFit.kt`; a `KitBuilder` entry beside `desamplePad`; the pad recipe
  field from section 6.
- **R3 (engine):** rows 1 to 14: `Kerf.kt`, `KerfPatch.kt`, the `Patches.fromJsonValue`
  arm, both `Velocity.kt` arms, `KerfPresets.kt`, `SynthKits`, the kit generator, the
  audition generator, docs, and the app picker entry with an on-device test.

## 15. Probes and auditions

Fit probes (per hit, against its source): band error, envelope correlation, level, centroid
ratio, top-end peak, ring match. Audition sections: each voice clean against chopped; BLEED
swept; SLOP swept; BLEED × SLOP at the corners; CUT and DECAY at the corners; TUNE at ±4;
the round-robin takes of one pad in a row; the kit as it lands; a loop rebuilt hit by hit
beside its source.

## 16. What the prototype measured

Python harness (`prototype/kerf/`), 14 loops, 462 hits. Not Kotlin.

| Measure (median over 462 hits) | First fit | Final |
|---|---:|---:|
| Band-by-band error over time | 4.5 dB | 3.4 dB |
| Loudness-shape correlation | 0.88 | 0.94 |
| Level against source | −0.6 dB | −0.5 dB |
| Spectral centroid against source | 1.03× | 1.04× |
| Top-end peak against source | −1.7 dB (kick/snare/stack) | −0.5 dB |
| Ring match (ceiling 0.32) | 0.09 | 0.28 |

The floor of the band-error measure is 1.6 dB: two renders of one patch with different
noise seeds differ by that much. The ring-match ceiling is found the same way.

Known shortfalls, with numbers: snare attacks run about 2 dB hot at the peak; two loops
rebuild with an envelope correlation of 0.76 and 0.77; some open hats still ring less than
the source (tonal share 0.21 to 0.23 against 0.28 to 0.35) and land 2.5 to 3.9 dB soft at
the top-end peak; the layer detector fires in 414 of 462 slices, about three per slice, so
a layer is not always a separate drum.

## 17. Build rounds and gates

- **R0.** Decided: A (owner, 2026-11-19). The pad recipe field itself lands in R2, with the
  tool that writes it, so that no field ships unused.
- **R1.** Built. `KerfRender` and the patch data, fed the committed `kit_patches.json`.
  Gate, still open: the owner hears Kotlin renders of the four kits beside the Python ones.
- **R2a.** Built. `KerfFit` and `kitFromLoop`. Gate, met by measurement: fit metrics within
  the section 16 figures on the same slices. Gate, open: a listen to kits the Kotlin code
  made from a loop.
- **R2b.** Built. The kit action in `:shell` beside `desamplePad`, the pad recipe field,
  KERF's own labels, and `snipsnap kerf`.
- **R2c.** Built. The pad sheet's rules for a twin, its wording, and the MAKE box's SYNTH
  TWIN row. Gate, open: the owner chops a loop of their own in the app and makes a pad its
  twin on a phone.
- **R2d.** Built. SYNTH TWIN ALL on the kit screen, and BLEED and SLOP on a twin. Gate,
  open: the owner makes a kit of twins on a phone and moves BLEED and SLOP.
- **R3.** The ghost-against-snare comparison, then velocity and round robin. Gate: the
  owner's velocity/round-robin gate through render, save, playback and export.
- **R4.** Engine registration, voices and presets after listening, genre kits, audition,
  docs. Gate: the drum gate (multiple distinct genre kits on the shelf).

## 18. Sonic risks

| Risk | What it would sound like | Probe |
|---|---|---|
| Layers over-fitted | a tail that flutters | count layers whose level is under −30 dB of the band peak; prune and re-measure |
| Colour fitted to chance | a faint fixed whistle on short slices | ring match against a second, different slice of the same cymbal |
| Ticks too hot | a click in front of the snare | top-end energy in the first 8 ms within ±3 dB of source |
| BLEED doubles the floor | wash builds up when pads repeat | energy before the attack at BLEED 1 against the source chop |
| TUNE moves the colour | a pitched-down hat rings like a different cymbal | listen; consider TUNE leaving colour fixed above 2 kHz |
| Velocity as volume | a soft hit that is only quieter | centroid and crack-to-ring ratio must move with velocity |

## 19. Siblings and landing

- **CUTTINGS** makes the same gesture as a printed sample bank with no editable source.
  It cannot re-cut, re-bleed or re-tune after the fact, and it cannot take a user's loop.
- **Desample** matches a captured hit to the nearest THUMP patch. It has no floor, no
  neighbours and no cut, so its result sounds like a drum machine, not a chop.
- **SOOT, SCUFF, BREAKER** are authored kits with a fixed chain; none models a neighbour.

The landing is the lightest one that keeps the gesture: the tool first, the engine after,
because the engine's voices come out of the tool.

## 20. Research references

No external code was read or adapted. The methods are textbook: sinusoids-plus-noise
modelling (Serra and Smith, "Spectral Modeling Synthesis", Computer Music Journal 14(4),
1990), matching pursuit for the partials (Mallat and Zhang, IEEE Trans. Signal Processing
41(12), 1993), Welch's averaged periodogram for the fine spectrum.

SnipSnap has no LICENSE file; flagged once here because this design is about deriving
content from third-party recordings.

## 21. Verified / proposed / heard ledger

| Claim | Status | Evidence |
|---|---|---|
| `Chopper.byTransients`, `Fft`, `Desample.nearest`, `Dsp.seedFor`, `Dsp.Noise` exist | verified | grep at `5f208336` |
| `:synth` depends on `:audio` | verified | `synth/build.gradle.kts:1399` |
| Renders are deterministic | measured | `render_kits.py` twice, same sha256 (`a193420423dac404`), Python |
| BLEED 0 with SLOP 0 equals the clean render | measured | `np.allclose`, Python |
| The figures in section 16 | measured | `run_fit.py`, `refine_all.py`, Python |
| The renderer cannot alias | proposed | argument in section 3; the 88.2 kHz test in section 13 would prove it |
| Round robin by re-drawing SLOP gives distinct strikes | proposed | no test yet |
| Velocity leaves the floor and the bleed where they were | measured | `ghost_vs_snare.py`: 9 of 9 and 8 of 9 loops, Python |
| The revised velocity law in section 11 | proposed | constants not prototyped, not heard |
| The Kotlin renderer matches the Python one | measured | `KerfRenderTest`: lengths equal to the frame on 64 pads; level and band figures in "As built" |
| A chopped pad renders in tens of ms in Kotlin | measured | 49.6 ms per pad on the JVM including warm-up, `generateKerfAudition` |
| The Kotlin renders sound like the Python ones | heard | owner, 2026-11-19, on the round-1 page: "Sounds good, continue" (my reading: no difference worth raising; no pad was singled out) |
| The Kotlin fitter fits real chops as well as the Python one | measured | 63 of the bank's source slices, figures in "As built: round 2a" |
| A loop becomes a kit in the app's own code | measured | two of the owner's loops, 16 pads each in 7.5 and 8.2 s on the JVM |
| Kits the Kotlin code made from a loop sound like their chops | heard | owner, 2026-11-20, on the loop-to-kit page: "Audition is fine" |
| A twin can be made and undone from the pad sheet on a device | not verified here | written without an Android SDK; `android-build` compiles it and `emulator-tests` runs `PadSheetUndoScreenTest` |
| The synthetic twins are acceptable | heard | owner, 2026-11-18: "Seems closer. I think I can live with it." |
| Cymbal ring under another hit is present | heard, partly | owner reported it missing on Kit A pads 1, 4, 6, 9, 11, 13, 16 and elsewhere; the colour stage followed; the quote above is the only verdict since |
| BLEED and SLOP levels are right | not heard | no verdict given on which step to ship |

## Why it is built this way

- **Sines for the body, noise for the rest.** A kick's thump is a falling pitch, which a
  sine follows exactly. A snare's rattle and a hat are noise, so they are shaped noise.
- **The floor and the neighbours are the chop.** A clean hit decays to silence. A chop has
  wash under it and the last hit still ringing. Remove those and it is a drum machine again.
- **Resonances, not tones, for metal.** Tones were tried and failed for a measurable
  reason. The resonance bank is the fix that moved the ring match from 0.09 to 0.28.
- **One renderer, two front ends.** The tool and the engine differ only in where patches
  come from, so the expensive part is written once.
- **The risky decision** is the patch format in section 6. It is the first patch in the
  repo that is not a voice plus a macro map, and everything after R0 depends on how it is
  carried.

## As built: round 1, the renderer (2026-11-19)

Read at `f7eae082`. What exists:

- `synth/.../KerfPatchData.kt`: the patch format of section 6, its JSON, and `KerfBank`,
  which loads the four heard kits from `synth/src/main/resources/kerf/v1/kit_patches.json`.
- `synth/.../KerfRender.kt`: `render` (one hit, clean), `chopped` (BLEED and SLOP),
  `bandEnergies` (the measure a render is held to).
- `synth/src/test/.../KerfRenderTest.kt`, twelve tests.
- `:synth:generateKerfAudition`, which writes the four kits, clean and chopped.

What changed against the design, and why:

- **One copy of the bank.** `kit_patches.json` moved out of `prototype/kerf/` into the
  renderer's resources; the Python scripts now read it from there. Two copies would have
  been one quantity in two places.
- **Each pad's SLOP draw is stored with the pad.** Kotlin has no generator that matches
  NumPy's, so without this every heard pad would have been cut somewhere new. With the draw
  stored, the chopped lengths and the start and overrun times equal the prototype's on all
  64 pads. A pad with no stored draw gets one from `Dsp.Noise` on its seed.
- **The neighbours follow TUNE and DECAY.** The prototype rendered them at their defaults,
  which no heard render ever exercised. A chop pitched down now has its bleed pitched down.
- **The lowest band survives TUNE going up.** The prototype silenced every bin below the
  first band centre, so tuning up muted the lowest band. The port holds the end value. This
  is the one deliberate difference in sound, and only away from TUNE 0.
- **No DC removal.** A slice cut part-way through a 46 Hz kick cycle has a mean of its own
  (worst measured 0.0134), as the source chop does. Removing it would move the chop's
  edges. The test bounds it at 0.02 and does not ask for less.

Parity with the prototype (`KerfRenderTest`, all 64 pads, 1049 bands within 30 dB of each
pad's loudest):

| Measure | Kotlin against Python | Python against itself, other noise seed |
|---|---:|---:|
| Level, median | 0.25 dB | 0.30 dB |
| Level, worst | 2.07 dB | 1.68 dB |
| Band energy, median | 0.68 dB | 0.70 dB |
| Band energy, 95th percentile | 2.76 dB | 3.02 dB |
| Band energy, worst | 12.85 dB | 12.49 dB |

The port differs from the prototype by what a different noise draw differs by, and no more.
The parity test was proven able to fail: with the noise gain multiplied by 1.25 it fails
and nothing else in the file does.

Also tested: byte-identical repeats; BLEED 0 with SLOP 0 is byte-identical to the clean
render on every pad; CUT sets the length to the frame; DECAY and FLOOR lower the last third;
TUNE moves a kick's body by the interval asked (within 60 cents over 14 semitones); added
bleed energy rises with BLEED; early starts carry a pre-roll and late starts shave at most
3 ms; patch data survives JSON on every pad.

Not done in this round, each still owed:

- The 88.2 kHz no-aliasing assertion of section 13. The renderer takes no rate parameter
  yet.
- A `DeterminismTest` canary. `KerfRenderTest` covers determinism; the canary joins when
  the engine registers (R4).
- The one slice over the 1.5 s loop threshold (Kit D pad 6, 3.30 s) is still in the bank.
  The fitter must refuse such a slice (R2); the test allows exactly this one.
- Heard: nothing. The Kotlin renders are on a published audition page beside the Python
  ones and nobody has listened.

## As built: round 2a, the fitter (2026-11-19)

Read at `6e76ec3b`. What exists:

- `synth/.../KerfFit.kt`: `fit` (one chop in, patch data out) and `kitFromLoop` (a loop in,
  up to sixteen pads out, each with both neighbours).
- `synth/src/test/.../KerfFitTest.kt`, six tests, and `:synth:generateKerfFit`, which fits
  the caller's own audio from disk.

The repository holds no source audio, so the suite feeds the fitter renders of the bank,
where the truth is known. How it does on real chops was measured outside the suite on the
owner's loops.

On real chops: the 63 source slices of the bank's pads that are one-shots (the 3.30 s slice
is refused), each fitted by Python and by Kotlin, each render compared with its source by
`prototype/kerf/resynth.py compare`.

| Median over 63 pads | Python fit | Kotlin fit |
|---|---:|---:|
| Band error over time | 3.49 dB | 3.44 dB |
| Loudness-shape correlation | 0.93 | 0.93 |
| Level against source | −0.51 dB | −0.57 dB |
| Centroid against source | 1.03× | 1.05× |
| Ring match | 0.29 | 0.28 |
| Top-end peak against source | −0.62 dB | −0.15 dB |
| Partials / events / resonances / ticks | 4 / 3 / 20 / 2 | 4 / 3 / 20 / 2 |

Worst Kotlin pads: band error 8.1 dB (Python's worst on the same pad is 7.5), level −4.1 dB.
The fit takes 410 ms per hit on the JVM including warm-up (Python: 555 ms).

On renders of the bank (`KerfFitTest`, eight pads of kit B): level median 1.06 dB, worst
3.27 dB; band median 0.87 dB, 95th 3.04 dB over 140 bands. The yardstick, one patch with two
noise seeds, is 0.70 and 3.02.

A loop to a kit: two of the owner's loops, 16 pads each, 7.5 s and 8.2 s.

What changed against the design and the prototype, and why:

- **The chop is the app's, not the prototype's.** `kitFromLoop` uses
  `Chopper.byTransients`, which keeps the strongest sixteen hits. Quieter hats stay inside
  their neighbour's slice, which is what layers are for. The cut points are therefore not
  the ones the owner heard in the Python kits.
- **A hit on the first frame is kept.** The detector does not see a hit that starts the
  file, so the chop began at the second hit and both test loops lost their opening kick.
  Audio before the first cut is now a pad when its peak is at least a quarter of the loop's.
- **A slice over 1.5 s is cut there**, not refused, inside `kitFromLoop`. `fit` itself
  still refuses one. The knife coming down is what this instrument does.
- **Metal candidates are capped at 48 tries, and layers at 8 per band.** Both bound the
  time on long or busy slices; neither changed a figure above.
- **The band-pass is run both ways from rest over a silent pad**, not with SciPy's edge
  extension. The partial counts and levels above say it did not matter.

Findings, not fixed:

- **A hit whose low end is refused loses that low end.** The lowest noise band starts at
  about 86 Hz; below it only an oscillator can carry energy. When the projection test
  refuses a sub partial, nothing else picks it up. This is the −3.27 dB pad in the suite
  and the −4.1 dB pad on real chops. It is a gap in the model, in Python too.
- **The app's classifier mislabels these chops.** On the two loops it called most kicks
  PERC and most hats SNARE. The labels decide a kit's layout, so the tool needs either its
  own labelling from the fit (the body's pitch and the band shares are already measured) or
  the owner's hand. Open for R2b.
- **Where a glide ends cannot be read from a short kick.** The fit clamps it at 20 Hz where
  the truth was 25 Hz. The pitch while the body sounds is right (within 8 % at 30 and
  80 ms), which is what is heard.

Proven able to fail: the second-sound test renders the same fit with layers off and
requires the hat to drop by more than 6 dB. Not proven by a reverted guard: the round-trip
bounds.

Not done in this round, each still owed: R2b entirely; the 88.2 kHz assertion; any listen
to a kit made by `kitFromLoop`.

## As built: round 2b, the kit action (2026-11-19)

Read at `f09a14fe`. What exists:

- `KerfRecipe.kt` and a `kerf` field on `PadRecipe`: the hit's numbers, both neighbours,
  BLEED, SLOP, the stored draw, the controls and the renderer model. A recipe has one
  source, a patch or a twin. A recipe with a twin writes version 4, so an older build
  refuses the pad instead of reading it as empty.
- `KitBuilderModel.kerfPad`, `kerfKit`, `unkerfPad` (`:shell`), beside `desamplePad`.
- `KerfFit.label`: KERF's own drum class for a chop.
- `snipsnap kerf <kit-dir> <pad> | --all [--bleed] [--slop] [--undo]`, and its entry in
  `docs/CLI.md`.

How it lands, and why:

- **The action is per pad, on a kit that already exists**, not a new "loop to kit" door.
  The app already chops a loop into a kit; KERF then replaces pads with their twins. The
  neighbours are the pads either side in slot order, wrapping, which in a chopped kit is
  the loop's order. This reuses every screen and rule the chop already has.
- **Every neighbour is measured from its capture, before anything is written**, so `--all`
  never fits a twin of a twin, and each pad is fitted once.
- **The undo refuses a twin with anything riding it.** A treatment over a twin puts the
  twin's own take on top of the bin; popping it would hand back the twin as if it were the
  capture. Any plain audio rewrite clears KERF's stamp, as it clears DE-SAMPLE's.
- **The label.** The prototype's rules, folded into the app's classes: a stacked hit goes
  with the drum it mostly is, a ghost note is a snare. On the owner's two test loops the
  capture classifier had called most kicks PERC; KERF's labels put a kick on each of the
  four kick positions of the first loop (0.00, 1.62, 2.66, 4.33 s) and a snare on each
  backbeat. Hats are still uneven: some quiet ones come out as snares.

Tested (`CliTest`, `KerfFitTest`): a pad becomes a twin with both neighbours and the asked
SLOP; the saved recipe renders the saved length; the undo is byte-identical and restores
the old label; a pad that is not a twin and a twin with a treatment over it are both
refused untouched; `--all` and `--all --undo`; a recipe survives JSON and plays the same; a
recipe cannot hold a patch and a twin; a rack still rides a twin; labels on six known pads.

Not done in this round, each still owed:

- **R2c.** `PadSheet` does not know a twin yet, so the pad sheet shows no KERF card and no
  undo for one; the app has no button. A twin whose treatment is undone keeps its stamp but
  loses its recipe, and then `kerf --undo` refuses it while the capture is still in the bin.
  That state needs the pad sheet's rules, as DE-SAMPLE has them.
- The 88.2 kHz assertion; the low-end gap of round 2a; any listen to a twin made this way.

## As built: round 2c, the pad sheet (2026-11-20)

Read at `fbfda867`. What exists:

- `PadSheet.isKerfTwin` and `kerfUndoRefusal` (`:shell`), the same shape as DE-SAMPLE's
  pair, and `hasOwnWayBack` counts a twin, so THE BIN hides BACK where the twin can undo
  itself. `KitBuilderModel.unkerfPad` now asks the pad sheet's question, so the button, the
  CLI and the door cannot disagree.
- The MAKE box says "A SYNTH TWIN NOW" for a twin and the grid marks it.
- Wording in `Copy`: the toast, the two refusals, UNDO SYNTH TWIN, the busy line.
- The app: a SYNTH TWIN row in the MAKE box under FIND NEAREST SYNTH, with MAKE SYNTH TWIN
  and its own UNDO, wired as `onDesample` and `onUndesample` are.
- On device: `PadSheetUndoScreenTest.synth_twin_undo_...`, which makes a real twin and
  undoes it from the screen.

The user-facing name is SYNTH TWIN. KERF stays the code's and the documents' name.

Rules settled here, closing the state round 2b left open:

- **Pedals ride a twin and keep it.** A twin is a source like a patch, so pedals re-render
  it and their NONE gives the bare twin back, on top again, still under KERF's label. Then
  the twin's own undo gives the capture.
- **A treatment that replaces the recipe takes the stamp with it when it is undone.** That
  undo has always cleared the recipe; a pad left with a twin's audio and no numbers no
  longer claims a twin. The capture is still in the bin on its own clock.
- **NONE on a bare twin is the twin's undo**: the capture comes back and the pad is called
  what it was called. Two doors, one result.
- **A pad too short to measure is refused in words**, about a fortieth of a second.

Tested off the device (`KerfPadTest`, `PadSheetTest`, `CliTest`): a lone pad's twin has no
neighbours; in a kit the pads either side are its neighbours and are measured, not
replaced; both undo doors give the capture byte for byte and the old label; pedals on and
off; the refusal order; the grid's mark. `ConventionTest`, `PersonalityTest` and
`FormatLocaleTest` pass with the new wording.

Not verified in this session: anything in `app/`. There is no Android SDK here, so the
screen code and the device test were written by reading the DE-SAMPLE arms beside them.

Still owed: a whole-kit button (the CLI's `--all` has no screen; sixteen pads are sixteen
taps); BLEED and SLOP on the sheet (a twin is made at 1.0 and 0.5); the low-end gap of
round 2a; the 88.2 kHz assertion; rounds 3 and 4.

## As built: round 2d, the whole kit and the controls (2026-11-20)

Read at `15291282`. The owner said "Proceed as recommended" to the two things round 2c named
as missing from the screen.

- **SYNTH TWIN ALL** on the kit screen, under DUST ALL and wired as DUST ALL is
  (`App.kerfAll`, `KitBuilderModel.kerfKit`, one `KitWrites.mutex`). Every pad that can take
  it is measured once from its capture. The landing counts the pads made, the pads left
  (layered, chained, printed, too short) and the twins already. If a measure throws part
  way, the pads made before it are saved.
- **BLEED and SLOP** on a bare twin, in the MAKE box's SYNTH TWIN row. Let go of either and
  the pad is re-cut from its own saved numbers (`KitBuilderModel.recutKerfPad`), about a
  tenth of a second. BLEED reads 0 to 2 on the sheet (the recipe allows 4; past 2 the last
  hit swamps this one), SLOP 0 to 1. BLEED is shown only on a twin with a hit before it: a
  twin made alone has nothing to bleed in, and CI's first emulator run caught exactly that
  (the device test moved BLEED on a one-pad kit and the audio did not change).
- `snipsnap kerf <kit> <pad> --recut [--bleed] [--slop]`, the same door.

Decided here, and why:

- **A re-cut is not bin-backed.** The render it replaces is the recipe's to make again, so
  nothing is lost, and the newest take in the bin stays the capture: UNDO SYNTH TWIN gives the
  capture after any number of re-cuts. A take per release would bury the capture under
  renders nobody needs.
- **A twin is not made twice.** MAKE SYNTH TWIN on a twin is refused in words; a second twin
  would measure the first twin's render, not the capture. SYNTH TWIN ALL skips twins and
  counts them.
- **A twin already is its own neighbour's measurement.** When a pad beside a new twin is a
  twin already, its saved numbers stand in for it, so no neighbour is measured from a render.
- **A twin with pedals over it is not re-cut** ([Copy.KERF_RIDDEN]): the cut would land under
  the pedals.

Tested off the device (`KerfPadTest`, eight tests; `CliTest`): a re-cut changes only BLEED and
SLOP, keeps the measure and the knife's draw, writes the audio its numbers make, adds no take,
returns byte for byte to the first cut at the first values, and leaves the undo giving the
capture; both refusals touch nothing; SYNTH TWIN ALL makes three of four pads, counts the
twin already, and gives its neighbours its own numbers; a second run makes nothing. On device
(`PadSheetUndoScreenTest`, a new case): BLEED moved by its adjust action re-cuts and saves
with no new take, and UNDO SYNTH TWIN then gives the capture. Not run here: no Android SDK in
this session.

Still owed: velocity and round robin (R3), the low-end gap of round 2a, the 88.2 kHz
assertion, the engine (R4).
