# Accord acoustic handoff audit

Date: 31 October 2026. Status: approved sound direction with Python reference artifacts; proposed Kotlin implementation. No new audio was rendered for this audit.

The owner accepted BRUSH as the default, with WOOD and the TINE variations retained. The latest approval was “Sounds good to me,” followed by “Proceed as recommended.” This accepts the sound direction. It does not rank the TINE options, certify a Kotlin port, or establish full control/seed/register coverage. The assistant makes no listening claim.

## 1. Which acoustic versions to carry

| Profile or artifact | Handoff treatment | Evidence |
|---|---|---|
| Original BRUSH / WOOD / TINE | Preserve as the listening references. BRUSH is the default. | `/workspace/accord-musicality/*.wav`; conversation approval reported by the primary agent |
| Original TINE upper ratio **5.96** | Carry into the default TINE profile and all three accepted option directions. | [Original mode table](../reference/musicality/render.py#L31); [option overrides](../reference/tine-options/render.py#L36) |
| Refined TINE upper ratio **6.00** | Archive as a diagnostic experiment, excluded from defaults. | [Explicit refinement override](../reference/refinement/render.py#L32); owner could not distinguish this technical A/B before requesting more audible options |
| Soft attack / bright edge / long ring | Retain all three as accepted directions. No winner or combined default was selected. Their note/velocity extrapolation remains proposed. | [Options report](../reference/tine-options/report.json); latest owner approval |
| Register and velocity laws | Carry as the next implementation's measured/proposed starting mappings, preserving reference anchors. Revalidate them on the actual house output path. | [Controls](../reference/refinement/controls.py#L50); [refinement report](../reference/refinement/report.json) |

The 6.00 experiment improved held-out short-window detector scores in a Python study while leaving modal root centers unchanged. It was not an approved replacement for the 5.96 reference. That distinction must survive model-version and preset naming decisions. See [pitch study recommendation](../reference/refinement/pitch_study.json).

## 2. Exact reference mode tables

Each tuple is `(frequency ratio to requested note, linear modal input gain, t60 seconds)`. Values below are the C3/MIDI 48, reference velocity `.75` anchors. C3 is `130.8127826502993 Hz` under A4 = 440 Hz. These are sound-design tables, not measured wood or metal properties.

| Voice | Tongue modes | Body modes |
|---|---|---|
| BRUSH | `(1,1,1.05)`, `(2,.13,.52)`, `(3,.025,.29)`, `(4,.009,.17)` | `(1,.26,1.35)`, `(1.50,.06,.55)`, `(2.25,.018,.31)`, `(3.25,.009,.19)` |
| WOOD | `(1,.85,.48)`, `(2,.50,.29)`, `(3,.24,.17)`, `(4.04,.10,.10)` | `(1,.22,.72)`, `(1.51,.07,.22)`, `(2.29,.035,.16)`, `(3.41,.018,.10)` |
| TINE | `(1,1,.83)`, `(2,.38,.40)`, `(3,.20,.24)`, `(5.96,.60,.26)` | `(1,.14,.62)`, `(1.50,.035,.28)`, `(2.42,.025,.17)`, `(3.24,.012,.11)` |

| Voice | Positive contact width | Body output gain | Brushing coefficient | Direct pulse gain |
|---|---:|---:|---:|---:|
| BRUSH | 4.5 ms | .58 | .0024 | .55 |
| WOOD | 2.1 ms | .50 | 0 | 1 |
| TINE | .72 ms | .32 | 0 | 1 |

Authoritative locations: [musicality tables](../reference/musicality/render.py#L26) and [control-law copies](../reference/refinement/controls.py#L12). Controls explicitly supply `pulse_gain=1` where the first renderer used its default value.

All fifteen mechanical histories feed a **common four-mode tongue and common four-mode body per voice** in the approved renderer. There are no distinct per-mechanism acoustic detunes, material tables, input-position weights, or separate body sends. The original engineering proposal's fifteen four-mode tongues and six-to-ten raft modes were not implemented in these approved studies. Do not silently expand the mode architecture during the port.

Direct contacts and pressure-gated brushing pass through banks with identical coefficients. The two linear input paths can therefore be summed before one tongue and one body bank. This algebraic reduction preserves event histories; floating-point summation differences still need a parity check. Acoustic output does not feed back into mechanics.

## 3. Contact force and event timing

The sound reference reads exact contact roots from `gathered-events.json`. Stored event fields are reference-cycle time, mechanism index, direction, and absolute angular speed in normalized solver units. In the audition fixture, divide event time by two reference cycles per musical second. Keep events in `[4,12)` musical seconds, subtract the four-second mechanics preroll, and excite for eight seconds. There are 528 contacts in this excerpt. Acoustic banks and filter states begin at zero; they are not audio-prerolled.

For each direction, construct a symmetric Hann kernel once:

```text
N = max(4, round(width_ms * direction_width * .001 * 176400))
direction_width = 1 for positive passage; 1.12 for negative passage
kernel[k] = (.5 - .5*cos(2*pi*k/(N-1))) / sum(Hann), k=0..N-1
event_amplitude = sqrt(min(abs_angular_speed/.8, 2)) / 15
position = (event_time - preroll_time) * internal_rate
```

Distribute the weighted kernel between `floor(position)` and the next sample using fractional weights `(1-fraction, fraction)`. Contact time is the **start** of the kernel, so wider contacts naturally move force centroid later while keeping the geometric event time unchanged. Opposite passages retain the same fundamental force polarity; their width is the only built-in tick/tock difference here. Do not reset modal phase on an impact.

The unit-area normalization is a fixed kernel calibration, not independent normalization of each hit or mechanism. Event strength retains its bounded speed proxy. No event times are snapped, merged or globally phase-corrected. Multiply the summed force by direct pulse gain and a 60 ms half-cosine excitation shutdown over musical seconds `7.94..8.00`; it is exactly zero afterward. This shutdown is the end of excitation, not a synchronization envelope.

Sources: [original contacts](../reference/musicality/render.py#L101), [refined contacts](../reference/refinement/render.py#L41), [option contacts](../reference/tine-options/render.py#L53).

Python `round` uses ties-to-even, while a naïve Kotlin rounding choice may differ. Specify the rounding rule when reproducing kernel lengths. The symmetric Hann denominator is `N-1`, not the periodic-Hann denominator `N` used by some other exciters.

## 4. Pressure-gated BRUSH excitation

The smooth geometry readout is:

```text
pressure(t) = sum_i [ max(0, 1-(theta_i(t)/.17)^2)^2
                     * min(abs(omega_i(t))/.8, 2) ] / 15
brush_force(t) = brush_coefficient * pressure(t) * roughness(t)
```

Angular speed is the normalized solver velocity, not a velocity multiplied again by musical PACE. This prevents musical time scaling from adding an accidental amplitude multiplier.

The Python trajectory was saved at 40 samples per reference cycle, which is 80 Hz at this audition pace. Theta and omega are linearly interpolated to a 2 kHz pressure readout, then pressure is linearly interpolated to audio rate. This is an interpolation of a coarse smooth readout, not a verified 2 kHz physical trajectory. A denser production readout must preserve the intended envelope and be compared before claiming sonic parity.

Roughness is `tanh(NumPy default_rng(20261022).standard_normal(n))`, followed by a second-order Butterworth bandpass specified with edges 80 and 2600 Hz, at 176.4 kHz. There is no dry noise bypass. Both direct and brush force are low-passed at 10 kHz before entering the same root-bearing tongue/body filters. Pressure shares the 60 ms shutdown; it is zero after excitation ends. TINE and WOOD have no brushing component.

Source: [pressure and roughness](../reference/musicality/render.py#L72). The measured original BRUSH/direct audio RMS ratios were −16.4465 dB at C2, −9.78395 dB at C3 and −9.28855 dB at C4, motivating the fixed register correction below. The refined normal-velocity render measured approximately −9.749/−9.784/−9.779/−10.607 dB at C2/C3/C4/C5. These are results for one curated trajectory, not universal limits.

## 5. Continuous note and velocity laws

Inputs are fractional MIDI `m in [36,72]` and velocity `v in [0,1]`. The Python function rejects nonfinite/out-of-range values. Production should apply the actual repository's input policy explicitly, rather than silently carrying Python exceptions into the public API.

Define:

```text
r = (m-48)/12
delta = (v-.75)/.75       when v <= .75
        (v-.75)/.25       when v > .75
upper_weight(ratio) = clamp(log2(ratio)/2, 0, 1)
```

Apply the following to the reference table:

```text
width_ms = base_width_ms * min(1, 2^(-r)) * exp(-ln(1.5)*delta)
decay_scale = 2^(-.08*r) * exp(.12*delta)
each t60 *= decay_scale
each tongue gain *= exp((.42*delta - .10*max(r,0))*upper_weight)
each body gain   *= exp((.20*delta - .10*max(r,0))*upper_weight)
body_output_gain *= exp(.10*delta)
direct_pulse_gain *= exp(.13*delta)
brush_coefficient *= 10^(register_db(m)/20) * exp(-.18*delta)
```

The `width_ms` returned by the controls function is **already note-scaled**. The refined renderer applies it directly. Do not repeat the original renderer's `min(1,C3Hz/noteHz)` multiplier.

All modal ratios and the requested note's root frequency remain unchanged by these laws. Root mode gain has `upper_weight=0`; root decay can change. At C3/velocity `.75`, every acoustic control field exactly equals its original reference value before the archived TINE 6.00 override and before output headroom calibration.

For brush register balance, let `s(x)=clamp(x,0,1)^2 * (3-2*clamp(x,0,1))`:

```text
register_db = 6.662590280187672 * s(-r),                      r <= 0
              -.495396182549136 * s(r),                      0 < r <= 1
              -.495396182549136 + (-.75+.495396182549136)
                                    * s(r-1),               r > 1
```

This gives +6.6626/0/−.4954/−.75 dB correction at C2/C3/C4/C5. C2 and C4 adjustments were derived from the earlier measured imbalance; C5 is a restrained design extrapolation. The curve is fixed by note, not estimated from each render.

The separate amplitude law is:

```text
amp_gain = (v/.75)^.9,          0 < v <= .75
           0,                  v = 0
           1 + .3*t - .1*t^3,  v > .75, t=(v-.75)/.25
```

Amplitude is .438383 at velocity `.3`, 1 at `.75`, and 1.2 at 1. The upper cubic matches the lower curve's first derivative at the reference. Harder contacts also change spectrum and resonator input, so actual output RMS is not simply 1.2 times normal RMS.

Sources: [laws and validation](../reference/refinement/controls.py#L50), [control report](../reference/refinement/controls_report.json). The saved control sweep covered 43,935 finite/nonnegative note/velocity combinations and preserved root ratios. It is not an audio or listening proof.

## 6. Level calibration and preservation of velocity

There are three distinct calibration policies in the artifacts:

1. **Original musicality comparison:** one whole-file RMS gain per voice/note, targeting `.105`, followed by a static peak-headroom gain if peak would exceed `.95`. This was acceptable for comparing three voices at one reference velocity. It is not a velocity law.
2. **Refinement matrix:** render `.3/.75/1` with amplitude law included. For each voice/note, choose `wanted_gain=.105/RMS(normal_velocity_full_file)`, then `shared_gain=min(wanted_gain, .94/worst_peak_across_three_velocities)`. Reuse that one gain across every velocity. No individual velocity is leveled back to `.105`.
3. **TINE option comparison:** one scalar per option matching RMS over **active seconds 0..8** to the approved reference PCM's active RMS `.11070362093232501`. Tail length does not affect this match. Original reference is copied byte-for-byte and remains 8.9 seconds; options are 10.2 seconds.

Sources: [original leveling](../reference/musicality/render.py#L133), [shared reference/headroom calibration](../reference/refinement/render.py#L124), [option matching](../reference/tine-options/render.py#L119).

The refinement's C3 WOOD normal render was reduced by `.523471 dB` to leave room for hard velocity. Its acoustic controls match the approved C3 shape, but its PCM gain does not. TINE 6.00's normal render correlates `.982996` with the original 5.96 PCM; it is not identical. The BRUSH C3 normal correlation was `.99999999` with only PCM/float-level differences. These facts distinguish a parameter anchor from a byte-identical audio anchor.

Production must calibrate the actual house loudness/output path at the reference velocity, retain that calibration for other velocities, and preserve intended timbral/energy changes. The Python RMS target `.105` is not equivalent to `Dsp.MELODIC_LOUDNESS_TARGET=.1834`. Do not copy the 6.00 TINE diagnostic gain table into the 5.96 default without recalibration. Do not peak-normalize each contact, mechanism, bank, or velocity render.

## 7. Modal recurrence and Kotlin conversion constraints

The Python references use double-precision coefficient/state arithmetic and `modal_ring(..., compensate=True)`:

```text
frequency = requested_hz * ratio
rho = exp(-6.9078/(t60*internal_rate))
theta = 2*pi*frequency/internal_rate
input_gain = table_gain * sin(theta)
y[n] = input_gain*x[n] + 2*rho*cos(theta)*y[n-1] - rho^2*y[n-2]
bank_output = sum(mode_outputs)
raw = tongue(direct_force + brush_force)
      + body_output_gain * body(direct_force + brush_force)
```

Modes at or above internal Nyquist are skipped. All approved frequencies through C5 are well below output Nyquist, but a general implementation must suppress unsafe high modes before decimation. Positive t60 puts every pole strictly inside the unit circle. The acoustic topology is feedforward; no final limiter serves as a stability argument.

The existing Kotlin `Modes.ring` lacks a `compensate` argument and applies plain modal gain. A direct call with these table gains therefore produces the wrong pitch/rate-dependent level balance. Preserve the explicit `sin(theta)` gain calibration in the caller or a validated internal helper. The toolkit's Float coefficients/state also differ from Python doubles; low-note tuning and decay need numerical parity checks. A Double implementation choice is proposed, not a claim that a matching public API already exists.

Verified local snapshot APIs: [Modes.Mode](https://github.com/BertCalm/SnipSnap/blob/4e36a2bf8cf8bfb07dba35379558b54429b5d73d/synth/src/main/kotlin/com/snipsnap/synth/Modes.kt#L37), [Modes.ring](https://github.com/BertCalm/SnipSnap/blob/4e36a2bf8cf8bfb07dba35379558b54429b5d73d/synth/src/main/kotlin/com/snipsnap/synth/Modes.kt#L88), [Dsp.seedFor](https://github.com/BertCalm/SnipSnap/blob/4e36a2bf8cf8bfb07dba35379558b54429b5d73d/synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt#L147), [Dsp.Noise](https://github.com/BertCalm/SnipSnap/blob/4e36a2bf8cf8bfb07dba35379558b54429b5d73d/synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt#L171), [Dsp.levelTo](https://github.com/BertCalm/SnipSnap/blob/4e36a2bf8cf8bfb07dba35379558b54429b5d73d/synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt#L716), [Dsp.decimate](https://github.com/BertCalm/SnipSnap/blob/4e36a2bf8cf8bfb07dba35379558b54429b5d73d/synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt#L759), and [Gyre output order](https://github.com/BertCalm/SnipSnap/blob/4e36a2bf8cf8bfb07dba35379558b54429b5d73d/synth/src/main/kotlin/com/snipsnap/synth/Gyre.kt#L840). This source snapshot was inspected at repository commit `4e36a2bf8cf8bfb07dba35379558b54429b5d73d`; recheck the actual integration checkout.

## 8. Filters, boundaries and PCM

The reference signal path is:

```text
direct force ── 8th-order Butterworth LP10k ─┐
brush force ─── 8th-order Butterworth LP10k ─┴─ tongue + scaled body
  → 8th-order Butterworth LP20k
  → two cascaded 2:1 FIR decimators
  → subtract whole-buffer mean
  → one-pole HP20Hz
  → one constant calibrated gain
  → 4ms tail fade and3ms head fade
  → mono44.1kHz16-bit PCM
```

The approximate Python decimator uses `firwin(63,.45)` and zero-state `lfilter(...)[::2]` at each stage. Its taps and latency are not the Kotlin house decimator. Python HP coefficient is `alpha=RC/(RC+1/rate)`, `RC=1/(2*pi*20)`; recurrence is `y=alpha*(previous_y+current_x-previous_x)`. Entire-buffer mean removal means extending a tail can change tiny initial samples even without changed excitation; copied references avoid claiming false byte parity.

The prototype's PCM writer clips at ±1, scales by 32767 and truncates to signed16-bit; its reader divides by32768. Reports may therefore distinguish unquantized float results from actual playback PCM. Clipping was not used to hide an unstable signal. Kotlin output comparisons must name precision, rounding, filter latency and PCM policy.

Sources: [Python filter implementations](../reference/mechanics/snipkit.py#L182), [PCM writer](../reference/mechanics/snipkit.py#L285). Production uses the house `Tide.bandLimit` then `Dsp.decimate`, DC removal and reference-calibrated shared leveling. Those filters require fresh calibration and a new listening comparison; approximate Python taps are not a production substitute.

## 9. TINE option overrides

Apply each override to the original 5.96 reference independently:

| Option | Exact change | Unchanged |
|---|---|---|
| SOFT ATTACK | Positive contact width `.72 → 3.2 ms`; negative remains1.12times positive | Every mode ratio, gain, t60 and body gain |
| BRIGHT EDGE | Tongue gains at ratios2/3/5.96 become `.85/.50/1.50`, previously `.38/.20/.60` | Root gain1, all mode ratios/t60, body table/gain, contact width |
| LONG RING | Every tongue/body t60 multiplied3 | Every mode ratio/gain, body gain and contact width |

LONG RING therefore has tongue t60s `2.49/1.20/.72/.78 s` and body t60s `1.86/.84/.51/.33 s`. Options have2.2seconds of unforced release. The original reference has.9seconds. The new longer ring was not automatically compressed to restore discrete ticks.

Measured C3 option results from quantized playback files:

| Option | Active RMS | Peak | Centroid | Upper-energy share | Release above−40dB |
|---|---:|---:|---:|---:|---:|
| Reference | .1107036 | .52710 |178.23Hz |12.84% |.25s |
| Soft attack | .1106884 | .53052 |132.05Hz |3.06% |.24s |
| Bright edge | .1106890 | .66870 |313.13Hz |44.93% |.23s |
| Long ring | .1106882 | .47372 |181.21Hz |13.30% |1.16s |

“Upper” means above1.7times the requested root. Release threshold is relative to maximum10ms RMS over active8seconds, within the captured finite tail. It is not an estimate of total physical decay to silence.

Original TINE WAV SHA256 is `b4ee77fef2d948def9a35d26700f98967a9016f6c659a8821a5ee1f67fd8b951`; the option reference copy has the same hash. The selected musical event-history SHA256 is `7619f5803cb89a4837a07e0b7dfb764e67ade86e314e281786ee221c601e2770`. See [option report](../reference/tine-options/report.json).

## 10. Seeds, parity and unfinished work

Mechanical phase and rate offsets were drawn from one NumPy generator initialized with integer20261022. Brushing separately resets NumPy's generator to the same integer, draws Gaussian values, applies `tanh`, and filters. These are not production project-RNG substreams. Identical integer seeds do not give identical NumPy and Kotlin noise sequences.

The refinement loads the same event, pressure, roughness and shutdown arrays once and reuses them across notes, voices and velocities; input hashes are recorded in its report. Preserve that independence: changing note, velocity, contact or acoustic option must not reseed existing mechanisms. Production needs stable, versioned phase/rate/acoustic substreams through the project's actual seed mechanism. A project-RNG conversion of BRUSH roughness requires comparison; its PCM is not automatically preserved. No external source samples are needed.

The modal root centers are the requested note. This does not establish that every active-contact window is heard or detected at that note. Corrected Python NSDF uses an unrestricted40..2000Hz search in.3second windows; short-window deviations and occasional detector register errors remain in the reports. The pitch study rejected a universal empirical root-detuning curve because it harmed held-out WOOD and BRUSH windows. No global cents correction, sample-rate retuning or event retiming belongs in this handoff.

Global normalized CONTACT and RAFT macro mappings, seed robustness, all musical PACE behavior, the full controls interaction cube, closed-state HOLD, Kotlin precision/performance, higher-rate alias comparison, production loudness matching and actual-phone/MPC listening are still unvalidated. The approved profile tables do not prove those contracts. In particular, the body's acoustic bank here receives contacts/pressure, not a platform-acceleration send; adding that proposed send is an additional sound-design change.

### Verified / measured / approved / proposed ledger

| Claim | Status | Limit |
|---|---|---|
| Exact tables, force/readout laws and gain policies above | Verified from named Python files | Not Kotlin production code |
| Reference hash, options metrics, note/velocity artifact QA | Measured previously in Python; audited from saved reports | One curated mechanical history; no fresh rendering in this audit |
| BRUSH default, WOOD and TINE variations retained | Owner-approved direction | No ranking of TINE options; no bit-parity or complete-engine acceptance |
| 5.96 as default compatibility reference;6.00 diagnostic archived | Handoff decision confirmed by primary agent against conversation | New model/preset choice required before any later substitution |
| Continuous register/velocity laws and Kotlin DSP conversion | Proposed starting implementation | Must survive actual house-stage probes and listening |
| Assistant listened or judged sound quality | Not claimed | Owner supplies listening judgment |

### Why it is built this way

- The root-bearing tongue stays separate from timing mechanics so the note remains playable while contacts organize themselves.
- Fixed force calibration and one shared reference-velocity level preserve soft/hard articulation and the concentration of collective excitation.
- The three TINE options change one audible axis at a time, retaining an untouched original for comparison.
- Explicit modal gain compensation, RNG conversion and filter differences keep the Kotlin port from silently changing the accepted sound.
