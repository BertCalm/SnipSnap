# ARCO — the Phase-0 record: the specification rendered, and the bow rebuilt

**Status:** a record, not a plan. Nothing here is in the build. The two
Kotlin pairs below ran once each in throwaway worktrees at `b8b8557` on
2026-09-28/29 and were then discarded; they are kept as text so the
numbers in the design's "The physics, measured" and its appendix tables
can be re-run, the way BORE's spike record
([`2026-09-28-bore-phase-0-spike.md`](2026-09-28-bore-phase-0-spike.md))
keeps its own. Round one rebuilds the bow from the design
([`../specs/2026-09-29-arco-bowed-string-engine-design.md`](../specs/2026-09-29-arco-bowed-string-engine-design.md))
rather than copying anything here.
**Date:** 2026-09-29

## What this file holds

Two measurements, in the order they were made, each with its report,
its iteration log and its source:

1. **The probe** (Appendices D–G): the two documents' engine — part one's
   `Arco.kt` with SYMPATHY and the sarangi tarab, and part two's
   `Arco.kt` with `SolinaEnsemble.kt` — transcribed faithfully into the
   test source set of a worktree with three compile-driven changes
   (C1–C3, recorded in Appendix D), rendered at 176.4 kHz at every voice
   and fourteen macro corners, and measured: finite everywhere, and no
   note at any setting. Appendix D is the report; E and F are the
   transcriptions; G is the print-only test that wrote the tables.
2. **The spike** (Appendices A–C): the reference bowed string — STK's
   two-segment junction with its bow table, fetched — rebuilt on the
   house's ring, allpass and one-pole with `Strings.tune`'s budget, beside
   every single-ring variant a bow could ride, and measured over five
   iterations: bounded, in tune, a textbook Helmholtz sawtooth on two
   segments; DC, an octave down, a knife-edge or a clarinet on one.
   Appendix A is the report with its iteration log; B is the model; C is
   the print-only test.

## How to re-run

Both pairs are test-source files (`synth/src/test/kotlin/com/snipsnap/synth/`),
so `internal` members of `:synth` resolve. Copy a pair in, then:

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.BowSpikeTest' -i
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.ArcoProbeTest'
```

Each test is print-only — its one assertion is `assertTrue(true)` — and
writes its tables to a file named in its source as well as to stdout
(`-i` shows stdout). On the cloud session's four cores the spike took
2 m 19 s and the probe 2 m 04 s after the first compile. Neither pair
belongs in the tree: the probe reproduces an engine that does not work,
and the spike's `BowSpike` is the design's shape without the design's
budgeted `Strings.Bow`, its window-mapped GRIP or its output chain. Remove
the files after the run; a `git status` that shows them is the reminder.

## What the numbers settled

- The documents' loop is a relay with a 46 % DC operating point and no
  period (D, Table A; the map in D §H2 has no fixed point).
- A bow cannot ride one `Strings.Loop` ring (A, table (9)): the physically
  signed single ring parks at DC = v_bow — on `Strings.Loop` literally, to
  the digit; the inverted ring plays an octave down; BORE's DC-blocked
  ring speaks 9–10 cents flat in islands; the half-period ring is a
  clarinet.
- Two segments with the reference table, budgeted by `Strings.tune`,
  are bounded in all 60 grid cells, in tune to +0.4…+2.9 cents at
  65–440 Hz, and produce single-slip Helmholtz motion — h2 −5.9 dB,
  −6.5 to −6.8 dB per octave, a flyback five times faster than the
  ramp — once the bridge corner is STK's 44.1 kHz one (3023.6 Hz) and
  pressure sits in the measured single-slip region (A, tables (2)–(4)).
- The pitch residual is a fixed share of the bridge filter's delay;
  0.85 of it brings all ten pitch cells within ±2.8 cents (A, iteration
  5).
- Hard pressure gives multiple slips, never a subharmonic; a resting
  bow stops the string, a lifted one lets it ring at the formula's t60;
  the string builds up over ~40 periods (A, tables (4), (5), (7)).

## Appendix A — the spike's report: the corrected bow on the house's primitives

Worktree: `/home/user/SnipSnap/.claude/worktrees/wf_17fb7c2a-cfb-2` at `b8b8557` (two untracked files, nothing committed, no tracked file touched, main checkout untouched):
- `synth/src/test/kotlin/com/snipsnap/synth/BowSpike.kt` — 375 lines, `internal object BowSpike`, marked SPIKE (test source set, so `Strings.tune`, `Strings.Loop`, `Tide.bandLimit` resolve).
- `synth/src/test/kotlin/com/snipsnap/synth/BowSpikeTest.kt` — 733 lines, one print-only test `spike()`; the only assertion is `assertTrue(true)`.

Copies, every run's gradle log and the tables were kept in the session's scratchpad (not in the tree) — `BowSpike.kt`, `BowSpikeTest.kt`, `spike-tables.txt` (the final run's 494 lines, written by the test itself), `iter1.log` … `iter5.log` (gradle `-i` stdout), `warm-build.log`.

Build/run: `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.BowSpikeTest' -i` → exit 0, `BUILD SUCCESSFUL in 2m 19s` on the final run (iter5.log:890); iteration 1 exited 1 (a helper crashed on a row with no f0 peak, fixed in iteration 2; no non-finite render anywhere). Every number below is copied from `spike-tables.txt` (iteration 5) unless a row says which earlier log it came from.

### Headline

**The STK bowed string, transcribed onto the house's ring-plus-allpass and budgeted with `Strings.tune`'s closed form, works: bounded in all 60 grid cells, in tune to +0.4…+2.9 c at 65–440 Hz, and it produces textbook Helmholtz motion — one slip per period, a −6 dB/octave sawtooth at the bridge with h2 at −5.9 dB — but only once two things the task's brief did not say are set: the bridge filter must be STK's 44.1 kHz filter (3023 Hz), not STK's pole formula evaluated at 176.4 kHz (9028 Hz, a third of the loss, and the string then never leaves 2–4 slips per period), and "pressure" must sit in the measured single-slip region (≥ 0.65 at amplitude 0.5).** Two residuals are real: the budget over-subtracts the bridge one-pole's phase delay because the slip is triggered by the corner's broadband arrival, so pitch reads sharp and grows with f0 (+12.1 c at 880 Hz; a measured 0.85 share of the filter delay brings all ten pitch cells within ±2.8 c), and the single-slip region is patchy (islands along the pressure axis, and β = 0.2 is a hole).

**The integration answer (9): a bow cannot ride one `Strings.Loop` ring.** The single full-period ring with the physically right sign (fb +0.95) parks at DC = v_bow and never speaks, on my mirror ring and on `Strings.Loop` literally (identical 0.1299); with BORE's in-loop DC blocker at f0/25 it sustains a sawtooth-like note but 9–10 c flat, only in islands of the pressure axis (stuck at DC over most of it), with a knife-edge corner (f0/10 → −25 c, f0/50 → stuck) and no bow position at all. The task's literal sign (fb −0.95) plays an octave down. The half-period ring (BORE's HOLLOW shape, β pinned at 0.5) is the one single-ring bow that is robust, but it is a clarinet: h2 at −35 to −40 dB, no bow position, a weak f0/2. **Build on two rings: (iii) two `Strings.Loop`s wired by hand through R0's `reflected()`/`inject()` hook and a fractional `roundTrip` (β and 1−β), packaged in R1 as `Strings.Bow` — which is (i) — never (ii).** R0 needs exactly BORE's first and third additions (fractional `roundTrip`, the junction split); the DC-blocker addition is not needed for the bow (the two-segment loop's mean is ≤ 0.003).

### The two models, as pseudo-code with every number

Both at `Dsp.RATE * Dsp.OVERSAMPLE` = 176 400 Hz (`Dsp.kt:21, :33`), both return the raw wave arriving at the bridge (STK's output tap before its body filters, `stk_Bowed.h:122`) and, from iteration 3, the string velocity under the bow; no body, no normalise. The core is measured on that buffer; the "finished Snip" is `Tide.bandLimit` → `Dsp.decimate` → `Dsp.levelTo(MELODIC_LOUDNESS_TARGET)` → `Dsp.fadeTail`, the chain `Fork.kt:465-468` runs, and the "core Snip" is the same without `levelTo`.

Constants (BowSpike.kt): `REFLECTION 0.95` (`stk_Bowed.cpp:56`), `OFFSET 0.001` (`:48`), `RHO_MIN 0.01`, `RHO_MAX 0.98` (`stk_BowTable.h:26`, STK's clamps rather than the brief's 0..1; `rhoMax 1.0` was probed and changes nothing that matters), `BETA_DEFAULT 0.127236` (`stk_Bowed.cpp:73`), `ATTACK_S 0.020`, `RELEASE_S 0.050`, `vBow = 0.03 + 0.2·amplitude` (`:105`), `slope = 5 − 4·pressure` (`:152`), `rho(dv) = clamp((|slope·(dv + 0.001)| + 0.75)^−4, 0.01, 0.98)` (`stk_BowTable.h:86-96`). Bridge one-pole: STK's `setPole(0.75 − 0.2·22050/rate)` with gain 0.95 (`stk_Bowed.cpp:55-56`) is `0.95 · Dsp.OnePole.lp(x, fc)` with `fc = −ln(pole)·rate/2π` (`Dsp.kt:417`'s `a = 1 − exp(−2π fc/rate)`, so the pole is `exp(−2π fc/rate)`): pole 0.65 at 44.1 kHz → **3023.6 Hz** (`DEFAULT_BRIDGE_HZ`, iteration 4 on), pole 0.725 at 176.4 kHz → 9028.4 Hz (iterations 1–3's default, kept as a probe). Bridge filter delay at f0: read back from `Strings.tune(f0, fc, rate).exact` as `(rate/f0) − 0.5 − exact` (`Strings.kt:110-114, :161`; the closed form written out agrees to 4 decimals in every row of table 0). Fractions: first-order Jaffe–Smith allpass `a = (1 − frac)/(1 + frac)`, the form `Strings.kt:398-400` uses, single tap (no two-tap average, so no 0.5 in the budget). Seedless: the 20 ms ramp is the perturbation and the loop self-starts; 1 % `Dsp.Noise` on v_bow was probed once (table 10) and changes the regime not at all. Release: from iteration 3 the bow LIFTS (`vNew = 0`, STK's `bowDown_ = false`) once the 50 ms ramp reaches 0; the resting-bow alternative is measured beside it in table 7.

**MODEL A** (STK `tick()`, `stk_Bowed.h:104-126`, on two `Seg` rings):

```
D      = rate/f0 − filterDelay(f0, bridgeHz)        # budget: neck + bridge + filter = one period
bridge = Seg(beta·D);  neck = Seg((1−beta)·D)        # each Seg: n = floor, frac → allpass a
per sample:
  vBow       = vMax · env(n)                         # 20 ms ramp, hold, 50 ms ramp, then bow lifted
  bridgeOut  = bridge.out();  neckOut = neck.out()   # read n back, then allpass; push comes after
  bridgeRefl = −(0.95 · lp(bridgeOut, bridgeHz))     # Dsp.OnePole, one per render
  nutRefl    = −neckOut
  vString    = bridgeRefl + nutRefl                  # the two incoming velocity waves
  dv         = vBow − vString
  vNew       = bowDown ? dv · rho(dv, slope) : 0
  neck.push(bridgeRefl + vNew);  bridge.push(nutRefl + vNew)
  out        = bridgeOut;  bowTap = vString + vNew
```

Budget at defaults (table 0, bridge 3023.6 Hz; `filterDelay` 8.79 samples at every f0 ≤ 440, 8.54 at 880):

| f0 | D | segA = β·D (n / frac / a) | segB = (1−β)·D (n / frac / a) |
|---|---|---|---|
| 41.2 | 4272.759 | 543.649 (543 / 0.649 / 0.213) | 3729.111 (3729 / 0.111 / 0.801) |
| 65.41 | 2688.042 | 342.016 (342 / 0.016 / 0.969) | 2346.027 (2346 / 0.027 / 0.948) |
| 130.81 | 1339.732 | 170.462 (170 / 0.462 / 0.368) | 1169.270 (1169 / 0.270 / 0.575) |
| 220 | 793.040 | 100.903 (100 / 0.903 / 0.051) | 692.137 (692 / 0.137 / 0.759) |
| 440 | 392.179 | 49.899 (49 / 0.899 / 0.053) | 342.280 (342 / 0.280 / 0.562) |
| 880 | 191.910 | 24.418 (24 / 0.418 / 0.411) | 167.492 (167 / 0.492 / 0.341) |

**MODEL B** (one ring mirroring `Strings.Loop.next`'s stages — tap → allpass → `loopLp` → [DC blocker] → `fb` — with the junction where Loop's `x +` is, `Strings.kt:429`):

```
exact = roundTrip·rate/f0 − filterDelay − dcLead      # dcLead = Strings.kt:142-146's form at corner dcHz (negative)
ring  = Seg(exact)
per sample:
  ret0 = ring.out();  ret = fb · lp(ret0, bridgeHz);  [dc += dcA·(ret − dc); ret −= dc]
  vString = vGain · ret;  dv = vBow − vString;  vNew = bowDown ? dv · rho(dv, slope) : 0
  ring.push(ret + vNew);  out = ret0;  bowTap = vString + vNew
```

| Variant | roundTrip | fb | vGain | DC blocker | What it is |
|---|---|---|---|---|---|
| B_FULL_NEG | 1.0 | −0.95 | 1 | none | the brief's literal form: a full period, one inversion |
| B_FULL_POS | 1.0 | +0.95 | 1 | none | a two-ended string folded into one ring: two inversions per period cancel, Karplus–Strong's sign |
| B_FULL_POS_DC | 1.0 | +0.95 | 1 | f0/25, lead budgeted | B_FULL_POS with BORE's cone blocker (the corner BORE's spike measured best) |
| B_HALF_NEG | 0.5 | −0.95 | 2 | none | the bow at the string's midpoint (β = 0.5) reduced by symmetry — BORE's HOLLOW shape |
| B on Strings.Loop | 1.0 | +0.95 | 1 | none (unreachable without jawari, `Strings.kt:424`) | literally `Strings.Loop(tune(f0, 3023.6, rate).n − 1, a, 0.95, 3023.6, rate)`; `next(vNew)` returns `x + fb·lp(...)` with the returning wave private, so the junction uses the previous sample's `y − x` and the one-sample lag is charged by the `n − 1` |

The v_string derivation. At a bow point the string velocity is the sum of the two incoming travelling velocity waves plus what the bow adds: `v = v_in_nut + v_in_bridge + vNew`, and STK's `dv` uses the incoming sum (`stk_Bowed.h:108`). A single ring has ONE incoming wave, so `vGain = 1`. "The bow at the bridge" is not that: the bridge-side incoming wave is then the instantaneous reflection of the wave the bow just sent (a delay-free loop), and a rigid bridge is a velocity node, so a bow there cannot drive the string at all — which is why STK puts the bow at β = 0.127 and not at 0. The one honest single-ring bow with two incoming waves is the midpoint: at β = 0.5 both round trips are T/2 and, ignoring the filter, both incoming waves are identical by symmetry, so one half-period ring carries both and `vGain = 2` (B_HALF_NEG); the price is the bridge filter and its loss applied twice per period (0.95² and 2·8.79 samples of lag, table 7's 506 ms formula).

### Iteration log

| Iter | What changed | What it measured | Log |
|---|---|---|---|
| 1 | The brief as written: bridge pole formula at 176.4 kHz (9028 Hz), seedless, all B variants side by side | A speaks at 130.83 Hz (+0.2 c, ac 0.97), bounded (peak 0.41), mean −0.0008 — but h2 +6.8 dB ABOVE h1 and a −1.0 dB/oct "slope"; B_FULL_POS = DC 0.1299 (= v_bow) exactly, and `Strings.Loop` reproduces it to the digit; B_FULL_NEG: no f0 peak; B_FULL_POS_DC: 130.06 Hz (−9.9 c), h2 −0.7, −7 dB/oct; B_HALF_NEG: +1.2 c, h2 −49 dB. The asymmetry helper crashed on B_FULL_NEG (`NegativeArraySizeException`, no f0 peak → garbage period) | iter1.log |
| 2 | Guards; a regime column on every row; a bow-point velocity tap (`vString + vNew`); a DC-corner sweep for B; the 3023 Hz filter as a probe | Bow-point stick fraction 0.93 at A's defaults but h2 +12 dB re h1 — not one slip per period; B DC corner: f0/10 −26 c, f0/25 −9.9 c, f0/50 and below stuck (rms = v_bow); 3023 Hz: A's slope −4.7 / −6.0 dB/oct at C3 / A3 and asym ratio 0.33 / 0.48 (a fast flyback appears) | iter2.log |
| 3 | Slips-per-period counter on the bow tap (falling crossings of 0.5·v_bow over 10 periods); the bow LIFTS after the release ramp; DC as its own regime label; the classifier prefers the f0 lag | **A at STK defaults is triple-slip (3.0 slips/period) at 9028 Hz and double-slip (2.0) at 3023 Hz**; single slip (1.0, slope −6.7 dB/oct, stick 0.97, asym 0.21) only at pressure 0.9 with 3023 Hz; the 9028 Hz Schelleng rows never leave 2–4 slips at any pressure or amplitude; a resting bow after the release clamps the string (t60 1720 ms of a stopped string vs 840 ms lifted); B_FULL_POS_DC is DC over most of the pressure axis | iter3.log |
| 4 | `DEFAULT_BRIDGE_HZ = 3023.6`; a loss × force map (bridge corner × pressure); β sweep at pressure 0.5 and 0.9; pressure 0.9 rows in tables 2, 3, 5 | Single slip needs |H(f0)| ≤ 0.99907 (corner ≤ 3024 Hz) and pressure ≥ 0.65–0.7 at amp 0.5, from 0.5 at 1500 Hz, from 0.9 at amp 0.8; 4500 Hz and above never at amp 0.5. Pitch reads sharp and grows with f0: +1.5 c (C3) → +12.1 c (880) at pressure 0.5, +9.3 at 0.9 | iter4.log |
| 5 | A `filterDelayShare` probe (the fraction of the one-pole's f0 phase delay the budget subtracts) | Share 1.0: +0.4 … +12.1 c; **0.85: −0.5 … +0.9 c over ten cells (worst −2.8 at 440/p0.5)**; 0.7: −1.2 … −12.8 c. Residual at 880 Hz: +1.07 samples at share 1.0, −0.22 at 0.85 | iter5.log (the run every table below is copied from) |

Why iteration 4 changed the filter: STK's `0.75 − 0.2·22050/rate` is not rate-invariant — it is a pole, and the same pole at four times the rate is a corner four times higher minus a bit (0.65 → 3023 Hz at 44.1 kHz, 0.725 → 9028 Hz at 176.4 kHz). The per-period high-frequency loss is what limits the corner's sharpness; with a third of it the memoryless friction curve lets the corner trigger two to four slips per period (iteration 3), which is the even-harmonic-dominant spectrum iterations 1–2 saw without naming it. 3023 Hz is what STK users hear. Why the pitch is sharp: `Strings.tune`'s budget subtracts the one-pole's phase delay at f0 (8.79 samples), which is what a sinusoid at f0 sees; a bowed string's period is set by the arrival of the Helmholtz corner, a step whose threshold crossing through a one-pole with τ = 9.3 samples happens earlier than the f0 phase delay — so the budget takes ~1.2 samples too many out of the loop, a fixed number of samples that is 1.5 c of a 1340-sample period and 12 c of a 192-sample one. At 9028 Hz (2.6 samples of delay) the same effect was 0.2–1.3 c, which is why iteration 1's tuning looked perfect. It is amplitude-dependent by nature (the crossing time depends on threshold over step), so it is a measured per-voice correction (BORE's cone precedent, `Dsp.Ladder`'s KDoc), not a closed form.

### Tables

Measurement definitions (BowSpikeTest.kt). *steady*: the last 300 ms before the release starts (200 ms in table 3). *acPitch*: normalised autocorrelation of the raw 176.4 kHz buffer for lags within ±6 % of the f0 period, parabolic interpolation on the peak; *ac* is that peak's height. *FineTuning*: the house's `FineTuning.measuredHz` (Hann, ≥ 65536-point FFT, parabolic; `FineTuning.kt:32`) on the core Snip from 0.5 s over 0.4 s. *Pitch.detect*: `Pitch.detect(finished, fromSec 0.5, windowSec 0.4)` (`Pitch.kt:37`); its 220.50 / 441.00 / 882.00 are its lag quantisation (44100/lag), as in BORE. *h1..h10*: Hann-windowed Goertzel power at k·(measured f0), each maximised over ±1.5 %, in dB re h1. *slope*: least-squares fit of those dB against log2(k) — a sawtooth is −6. *asym ratio*: max(x′)/|min(x′)| over the last period (a sine 1.0; a sawtooth's fast flyback puts it far from 1). *stickFrac*: the fraction of the period where |x′| < 0.2·max|x′| (a sine 0.13, a sawtooth → 1). *slips/period*: on the bow-point tap, falling crossings of 0.5·v_bow over the last ten periods, ÷ 10 (Helmholtz 1.0). *regime*: SILENT (rms < 1e-3), DC (mean-removed rms < 1e-3), APERIODIC (no autocorrelation peak ≥ 0.6 for lags 0.45–3.3 periods), PD (Goertzel power at f0/2 or f0/3 within 20 dB of f0), else PERIODIC with the lag's pitch. *centroid*: magnitude-weighted to 20 kHz on a Hann 65536 frame of the raw steady signal; *Features centroid / rolloff*: `FeatureExtractor.extract` (`Features.kt:57`) on 4096 samples of the core Snip from 0.6 s.

#### (0) Bridge filter delay: `Strings.tune` read-back vs the closed form

| f0 | 41.2 | 65.41 | 130.81 | 220 | 440 | 880 |
|---|---|---|---|---|---|---|
| filterDelay via tune (samples) | 8.7938 | 8.7929 | 8.7886 | 8.7781 | 8.7297 | 8.5448 |
| closed form (Strings.kt:110-114) | 8.7938 | 8.7929 | 8.7886 | 8.7781 | 8.7297 | 8.5448 |
| B_FULL_POS_DC dc lead at f0/25 | −27.243 | −17.159 | −8.580 | −5.102 | −2.551 | −1.275 |

#### (9) Side by side at defaults (pressure 0.5, amp 0.5, β 0.127, 3023.6 Hz, 1.0 s)

f0 = 130.81 Hz, bridge tap:

| model | max abs | non-finite | steady peak | steady RMS | steady mean | acPitch (cents, ac) | h1..h10 dB re h1 | slope | asym / stick | slips | regime |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A two-seg | 0.3541 | 0 | 0.3526 | 0.1532 | −0.0002 | 130.92 (+1.5, 0.97) | 0 8.3 −9.5 2.8 −13.9 1.1 −14.6 −6.2 −17.0 −8.7 | −4.7 | 0.33 / 0.88 | 2.0 | PERIODIC 130.96 |
| A, pressure 0.9 (table 10) | 0.6605 | 0 | 0.6117 | 0.2865 | −0.0029 | 130.93 (+1.6, 0.97) | 0 −5.9 −9.3 −11.5 −13.0 −13.6 −11.5 −22.9 −23.3 −22.7 | −6.7 | 0.21 / 0.97 | 1.0 | PERIODIC 130.96 |
| B_FULL_NEG | 0.1288 | 0 | 0.1288 | 0.1039 | 0.0268 | no f0 peak (−0.87) | flat 0 … −2.6 | −0.7 | — | — | PD: 65.4 Hz, f0/2 **+47.2 dB** re f0 |
| B_FULL_POS | 0.1299 | 0 | 0.1299 | 0.1299 | 0.1299 | no f0 peak (0.00) | — | — | — | — | **DC (mean 0.1299 = v_bow)** |
| B_FULL_POS_DC | 0.6137 | 0 | 0.6099 | 0.2059 | 0.0457 | 130.13 (**−9.0**, 0.97) | 0 −0.8 −2.2 −4.3 −7.3 −11.7 −19.0 −31.2 −21.9 −18.6 | −8.3 | 1.25 / 0.90 | 1.0 | PERIODIC 130.09 |
| B_HALF_NEG | 0.1053 | 0 | 0.1053 | 0.0706 | 0.0236 | 131.07 (+3.5, 0.86) | 0 −38.7 −9.6 −39.4 −14.0 −39.7 −16.6 −39.2 −19.2 −40.0 | −6.7 | 0.99 / 0.95 | 1.0 | PD: f0/2 −18.2 dB |
| B on Strings.Loop | 0.1299 | 0 | 0.1299 | 0.1299 | 0.1299 | no f0 peak (0.00) | — | — | — | — | **DC (mean 0.1299)** |

f0 = 220 Hz, bridge tap:

| model | max abs | steady peak | steady mean | acPitch (cents, ac) | h1..h10 dB re h1 | slope | asym / stick | slips | regime |
|---|---|---|---|---|---|---|---|---|---|
| A two-seg | 0.3839 | 0.3835 | 0.0004 | 220.36 (+2.8, 0.98) | 0 −0.8 −2.1 −19.3 −5.3 −6.9 −19.4 −15.3 −21.6 −14.7 | −6.0 | 0.48 / 0.83 | 2.0 | PERIODIC 220.22 |
| B_FULL_NEG | 0.1288 | 0.1288 | 0.0280 | no f0 peak | flat | −2.4 | — | — | PD: 110.1 Hz, f0/2 +42.7 dB |
| B_FULL_POS | 0.1299 | 0.1299 | 0.1299 | none | — | — | — | — | DC 0.1299 |
| B_FULL_POS_DC | 0.5296 | 0.5296 | 0.0504 | 218.89 (−8.8, 0.98) | 0 −0.8 −2.3 −4.4 −7.5 −11.9 −18.8 −31.6 −24.2 −21.3 | −8.8 | 1.25 / 0.85 | 1.0 | PERIODIC 218.86 |
| B_HALF_NEG | 0.1053 | 0.1053 | 0.0237 | 220.72 (+5.6, 0.88) | 0 −34.5 −9.6 −35.0 −13.8 −35.3 −16.7 −35.8 −19.1 −36.7 | −6.4 | 1.00 / 0.93 | 1.0 | PD: f0/2 −18.9 dB |
| B on Strings.Loop | 0.1299 | 0.1299 | 0.1299 | none | — | — | — | — | DC 0.1299 |

Bow-point tap (the Helmholtz test — a rectangular wave sticking for 1 − β of the period): A at 130.81 stick 0.89 with h2 +13.5 dB (two slips); B_FULL_POS_DC stick 0.90, h2 −0.8, one slip; B_HALF_NEG stick 0.96, h2 −69.7, one slip. At 220: A stick 0.86 (two slips), B_FULL_POS_DC 0.84, B_HALF_NEG 0.93.

The DC-corner sweep for B_FULL_POS_DC (raw acPitch cents; regime): at 130.81 — f0/10 −24.5 (lead −21.4 smp), f0/25 −9.0 (−8.6), f0/50 stuck (PD 291 Hz on an rms 0.088 wobble of the v_bow DC), f0/100 stuck, f0/200 stuck; at 220 — f0/10 −22.9, f0/25 −8.8, f0/50 and below stuck. f0/25 is the only corner that speaks and it is 9 c flat.

Loss × force map, model A, 130.81 Hz, amp 0.5, β 0.127 — cells are slips/period / slope dB/oct / pitch cents / steady peak:

| bridge Hz | \|H(f0)\| | delay smp | p 0.3 | p 0.5 | p 0.7 | p 0.9 | p 1.0 |
|---|---|---|---|---|---|---|---|
| 1000 | 0.99155 | 27.42 | 2.0 / −9.3 / +4.6 / 0.26 | 2.0 / −8.0 / +5.6 / 0.24 | **1.0** / −9.1 / +3.6 / 0.46 | 1.0 / −8.4 / +4.8 / 0.47 | 1.0 / −7.6 / +5.4 / 0.47 |
| 1500 | 0.99622 | 18.17 | 2.0 / −6.8 / +2.7 / 0.28 | **1.0** / −8.4 / +2.2 / 0.46 | 1.0 / −6.8 / +2.2 / 0.49 | 1.0 / −6.2 / +4.0 / 0.54 | 1.0 / −6.1 / +4.5 / 0.57 |
| 2000 | 0.99787 | 13.52 | 2.0 / −5.9 / +2.3 / 0.33 | 2.0 / −5.2 / +2.3 / 0.33 | **1.0** / −6.7 / +1.7 / 0.53 | 1.0 / −6.8 / +2.6 / 0.57 | 1.0 / −7.2 / +3.1 / 0.59 |
| 3024 | 0.99907 | 8.79 | 2.0 / −5.9 / +1.5 / 0.34 | 2.0 / −4.7 / +1.5 / 0.35 | **1.0** / −7.4 / +1.1 / 0.55 | 1.0 / −6.7 / +1.6 / 0.61 | 1.0 / −6.1 / +1.7 / 0.65 |
| 4500 | 0.99958 | 5.75 | 2.0 / −6.3 / +1.0 / 0.34 | 3.0 / −1.0 / +0.6 / 0.39 | 3.0 / −1.3 / +0.5 / 0.48 | 2.0 / −1.0 / +0.5 / 0.70 | 1.5 / −2.9 / +0.3 / 0.85 |
| 6000 | 0.99976 | 4.20 | 4.0 / −2.6 / +0.6 / 0.37 | 3.0 / −1.0 / +0.4 / 0.40 | 3.0 / −1.3 / +0.4 / 0.56 | 2.0 / −1.2 / +0.3 / 0.71 | 2.0 / −0.3 / +0.2 / 0.86 |
| 9028 | 0.99990 | 2.64 | 4.0 / +0.2 / +0.3 / 0.38 | 3.0 / −1.0 / +0.2 / 0.41 | 3.0 / −1.6 / +0.3 / 0.58 | 2.0 / −1.8 / +0.2 / 0.74 | 2.0 / −0.2 / +0.1 / 0.92 |

At amp 0.8: 1500 Hz single-slip from p 0.9; 3024 Hz from p 0.9 (3.0 / 3.0 / 2.0 / 1.0 / 1.0); 6000 Hz never. At 220 Hz, amp 0.5: 1500 Hz from p 0.5; 3024 Hz from p 0.7; 6000 Hz from p 0.9. Note how the pitch error tracks the filter delay across rows (+0.3 c at 2.6 samples, +1.5 at 8.8, +4.6 at 27.4): the residual is a fixed share of the budgeted delay.

Filter-delay-share probe (raw acPitch cents at pressure 0.5 / 0.9):

| share | 65.41 | 130.81 | 220 | 440 | 880 | residual smp at 880, p0.9 |
|---|---|---|---|---|---|---|
| 1.0 (tune's rule) | +0.4 / +0.5 | +1.5 / +1.6 | +2.8 / +2.9 | +2.9 / +5.6 | **+12.1 / +9.3** | +1.07 |
| 0.9 | −0.2 / −0.1 | +0.4 / +0.5 | +0.9 / +1.0 | −1.0 / +1.8 | +4.6 / +1.8 | +0.21 |
| **0.85** | −0.5 / −0.4 | −0.2 / −0.0 | −0.1 / 0.0 | −2.8 / −0.1 | +0.9 / −1.9 | −0.22 |
| 0.8 | −0.8 / −0.6 | −0.8 / −0.6 | −1.0 / −0.9 | −4.7 / −2.0 | −2.8 / −5.6 | −0.65 |
| 0.7 | −1.3 / −1.2 | −1.9 / −1.7 | −2.9 / −2.7 | −8.5 / −5.7 | −10.2 / −12.8 | −1.49 |

#### (1) Boundedness, 130.81 Hz, 1.5 s renders — max|x| / finite / steady peak

Model A, pressure × amplitude (rows) × β (columns): **all 60 cells finite**; max|x| 0.208 … 0.822, steady peak 0.208 … 0.818, every max within 0.06 of its steady peak (no onset overshoot). The extremes: (0.1, 0.2, β 0.2) 0.208; (0.9, 0.8, β 0.127) 0.822 / 0.818; (0.9, 0.5, β 0.08) 0.765 / 0.762. Level rises with pressure (0.29 → 0.67 at amp 0.5, β 0.127) and hardly with amplitude. Full grid: spike-tables.txt:373-391.

Model B_FULL_POS_DC (15 cells, all finite): speaks only at (0.1, 0.2) 0.390, (0.1, 0.8) 0.471, (0.3, 0.8) 0.562, (0.5, 0.5) 0.661; the other eleven cells sit at the v_bow DC (0.070 / 0.129 / 0.189 = 0.03 + 0.2·amp). B_HALF_NEG (15 cells, all finite): 0.061 … 0.182, monotone in both axes, no holes.

#### (2) Pitch at defaults

| model | f0 | raw acPitch | cents | ac | FineTuning | cents | Pitch.detect (conf) | cents | finished peak | Loudness |
|---|---|---|---|---|---|---|---|---|---|---|
| A | 65.41 | 65.424 | +0.4 | 0.95 | 65.488 | +2.1 | 65.43 (0.96) | +0.5 | 0.448 | 0.1834 |
| A | 130.81 | 130.923 | +1.5 | 0.97 | 130.778 | −0.4 | 130.86 (0.98) | +0.7 | 0.454 | 0.1834 |
| A | 220 | 220.356 | +2.8 | 0.98 | 220.001 | 0.0 | 220.50 (0.99) | +3.9 | 0.457 | 0.1834 |
| A | 440 | 440.737 | +2.9 | 0.99 | 440.699 | +2.7 | 441.00 (0.99) | +3.9 | 0.344 | 0.1834 |
| A | 880 | 886.170 | **+12.1** | 1.00 | 886.164 | +12.1 | 882.00 (0.99) | +3.9 | 0.331 | 0.1834 |
| A p0.9 | 65.41 / 130.81 / 220 / 440 / 880 | | +0.5 / +1.6 / +2.9 / +5.6 / +9.3 | 0.95–1.00 | | +5.6 / +1.6 / +2.9 / +5.6 / +9.2 | 65.43 / 130.86 / 220.50 / 441.00 / 882.00 | | 0.532 / 0.490 / 0.420 / 0.370 / 0.362 | 0.1834 |
| B_FULL_POS_DC | 65.41 | 65.628 | +5.8 | 0.89 | 65.305 | −2.8 | **null** | — | 0.775 | 0.1809 |
| B_FULL_POS_DC | 130.81 / 220 / 440 / 880 | | **−9.0 / −8.8 / −6.2 / −0.9** | 0.97–1.00 | | −9.5 / −8.8 / −6.2 / −0.9 | 130.09 / 219.40 / 436.63 / 882.00 | −9.6 / −4.7 / −13.3 / +3.9 | 0.620 / 0.546 / 0.422 / 0.262 | 0.1834 |
| B_HALF_NEG | 65.41 / 130.81 / 220 / 440 / 880 | | +1.9 / +3.5 / +5.6 / +10.5 / **+17.8** | 0.84–0.92 | | +1.6 / +3.3 / +5.5 / +10.4 / +17.7 | 65.53 / 131.25 / 220.50 / 441.00 / 882.00 | | 0.437 / 0.355 / 0.320 / 0.304 / 0.304 | 0.1834 |

Against the 5-cent bar: A passes at 65–440 Hz with tune's rule and at all five with share 0.85 (the correction is a per-voice number, not a knob); B_FULL_POS_DC fails at 130–440 in the flat direction; B_HALF_NEG fails from 440 up (its residual is A's twice over — two filter passes per period). `levelTo` lifts every speaking render to the 0.1834 target; nothing here tested a stuck render through it, but table 1's DC cells would be lifted to full-scale DC the way BORE's sub-threshold renders were lifted to hiss.

#### (3) Helmholtz signature (last 200 ms)

| model | f0 | h1..h10 dB re h1 | slope dB/oct | asym ratio | stickFrac | centroid Hz |
|---|---|---|---|---|---|---|
| A (p 0.5, two slips) | 130.81 | 0 7.8 −9.6 2.3 −14.5 0.7 −17.2 −6.4 −16.1 −9.3 | −4.80 | 0.327 | 0.876 | 1718 |
| A (p 0.5, two slips) | 220 | 0 −1.1 −2.6 −17.0 −5.8 −7.5 −19.5 −16.0 −20.7 −14.7 | −6.03 | 0.480 | 0.834 | 1916 |
| **A p 0.9 (one slip)** | 130.81 | 0 −5.9 −9.3 −11.6 −13.0 −13.7 −11.6 −23.2 −23.3 −22.7 | **−6.77** | 0.208 | 0.974 | 1471 |
| **A p 0.9 (one slip)** | 220 | 0 −5.9 −9.3 −11.7 −13.3 −14.2 −13.6 −23.3 −20.8 −21.0 | **−6.49** | 0.212 | 0.959 | 1702 |
| B_FULL_POS_DC | 130.81 | 0 −0.7 −2.0 −3.9 −6.5 −10.3 −16.2 −29.6 −25.1 −19.5 | −8.35 | 1.254 | 0.897 | 838 |
| B_FULL_POS_DC | 220 | 0 −0.8 −2.3 −4.4 −7.5 −11.9 −18.8 −31.6 −24.2 −21.3 | −8.82 | 1.253 | 0.846 | 979 |
| B_HALF_NEG | 130.81 | 0 −38.7 −9.6 −39.4 −14.0 −39.8 −16.6 −39.1 −19.3 −40.2 | −6.68 | 0.986 | 0.952 | 1501 |
| B_HALF_NEG | 220 | 0 −34.4 −9.6 −35.1 −13.8 −35.2 −16.8 −35.8 −19.1 −36.8 | −6.41 | 1.001 | 0.930 | 1650 |
| B_FULL_NEG | 130.81 | (at k·f0 of a note an octave down: meaningless) | | — | — | 755 |

Single-slip A is a sawtooth by every number: h2 −5.9 (an ideal sawtooth's is −6.02), h3 −9.3 (−9.54), h4 −11.6 (−12.04), the fit −6.5 to −6.8 dB/oct, the flyback five times faster than the ramp (0.21), stuck 96–97 % of the period; the notch at h8 (−23 dB) is the bow position (1/β = 7.86). B_FULL_POS_DC's harmonics sit ~5 dB high through h6 (h2 −0.8) — a sawtooth with a rounded corner, and asym 1.25 with no flyback direction. The h-slope alone does not separate them; slips/period and asym do.

#### (4) Schelleng rows, 130.81 Hz, β 0.127 (last 300 ms)

Model A — pressure → regime, steady peak, h2 re h1 dB, slips/period, stick fraction (bow tap). Every one of the 63 cells is PERIODIC at f0 (ac 0.97–0.98, pitch +0.7 or +2.0 c) and finite; no cell is SILENT, DC, PD or APERIODIC. What changes is the number of slips:

| amp (v_bow) | pressure → slips/period | single-slip cells (h2 = −5.6 … −6.1 dB) | peak range |
|---|---|---|---|
| 0.5 (0.130) | 0.00–0.25: 3 · 0.30–0.50: 2 · 0.55–0.60: 3 · **0.65–0.75: 1** · 0.80–0.85: 3 · **0.90–1.00: 1** | 0.65, 0.70, 0.75, 0.90, 0.95, 1.00 | 0.288 → 0.646 |
| 0.2 (0.070) | 0.00–0.05: 3 · **0.10–0.40: 1** · 0.45–0.55: 3 · **0.60–0.80: 1** · 0.85: 1.7 · **0.90–1.00: 1** | 0.10–0.40, 0.60–0.80, 0.90–1.00 | 0.232 → 0.475 |
| 0.8 (0.190) | 0.00–0.15: 4 · 0.20–0.55: 3 · 0.60–0.70: 2 · 0.75–0.80: 3 · **0.85–0.90: 1** · 0.95: 3 · **1.00: 1** | 0.85, 0.90, 1.00 | 0.268 → 0.987 |

In the multiple-slip cells h2 re h1 runs from −5 to **+26 dB** (amp 0.8, p 0.20) — the octave dominates, the note reads as f0 by autocorrelation only. "Does hard pressure growl?" — not in this table: no period doubling and no aperiodicity anywhere on the pressure axis; hard pressure raises the level (peak 0.29 → 0.65 at amp 0.5) and, past the threshold, buys the single slip. Pressure 0.00 is not "bow up" here (slope 5, the table still acts; STK's `bowDown_` handles that, `stk_Bowed.cpp:149-151`) — the engine has to lift the bow explicitly. Full rows: spike-tables.txt:137-207.

Model B_FULL_POS_DC (amp 0.5): PERIODIC at f0 (−9.6 c, one slip, h2 −0.7) only at pressure 0.00–0.05 and 0.50–0.65; 0.10–0.45 stuck at the v_bow DC with a 291 Hz wobble (labelled PD by the f0/3 rule on an rms 0.129 signal); 0.70 a dying 130.76 Hz (stick 0.18); **0.75–1.00 DC**. Amp 0.2: speaks 0.00–0.15, DC from 0.25. Amp 0.8: speaks 0.00–0.40 (−7.0/−8.3 c) and 0.75–0.85, stuck 0.45–0.70, DC from 0.90. Model B_HALF_NEG: PERIODIC or weakly PD in all 63 cells, one slip everywhere, h2 −38 to −41 dB, +3.2 c; f0/2 at −14 to −19 dB in 8 / 13 / 8 cells of the three rows (a genuine weak period-two alternation, since the classifier's 2P lag beats the P lag by more than 0.03 there); peaks 0.059 → 0.217. Full rows: spike-tables.txt:209-372.

#### (5) Onset — time to 90 % of steady RMS (5 ms windows; steady = mean RMS 0.6–0.9 s), 20 ms attack

| model | f0 | p 0.3 | p 0.5 | p 0.8 | p 0.9 |
|---|---|---|---|---|---|
| A | 65.41 | 410 ms (165 to 50 %) | 325 (135) | 330 (15) | 410 (15) |
| A | 220 | 180 (50) | 160 (50) | 245 (95) | 230 (10) |
| A | 41.2 (table 10) | — | 420 (135) | — | — |
| B_FULL_POS_DC | 65.41 | 40 (25) | 35 (25) | 40 (30) | 40 (30) |
| B_FULL_POS_DC | 220 | 20 (15) | 400 (25) | 25 (20) | 25 (20) |
| B_HALF_NEG | 65.41 | 35 (20) | 35 (20) | 20 (20) | 25 (20) |
| B_HALF_NEG | 220 | 25 (20) | 30 (10) | 25 (10) | 15 (10) |

The two-segment string takes 325–420 ms to reach 90 % of its level at C2/E1 and 160–245 ms at A3 (half level in 10–165 ms): a 0.95-loss string builds up over ~40 periods. The spec's "30–60 ms for the contrabass to stabilise" is what the single-ring variants do, not the STK string; the 10–15 ms "to 50 %" cells at hard pressure are the first multiple-slip burst, not the note.

#### (6) Bow position, model A, 220 Hz (last 200 ms)

pressure 0.5:

| β | segA smp | raw centroid Hz | Features centroid | rolloff | cents | h1..h8 dB re h1 | slope | slips |
|---|---|---|---|---|---|---|---|---|
| 0.05 | 39.65 | 2623 | 1207 | 4619 | +2.7 | 0 15.1 18.3 27.8 16.2 11.5 15.3 19.5 | +4.42 | 4.0 |
| 0.08 | 63.44 | 1815 | 702 | 3068 | +1.9 | 0 12.0 −0.3 5.1 −0.8 −0.0 −1.7 −5.3 | −2.50 | 2.0 |
| 0.127 | 100.72 | 1952 | 679 | 3101 | +2.8 | 0 −0.7 −2.0 −19.8 −5.2 −6.8 −19.1 −15.3 | −5.68 | 2.0 |
| 0.2 | 158.61 | 1794 | 866 | 2638 | +1.5 | 0 23.9 −7.6 27.5 −7.4 20.5 −11.8 11.6 | −0.58 | 2.0 |
| 0.3 | 237.91 | 1701 | 516 | 3090 | +2.5 | 0 −5.7 −7.9 −11.5 −11.8 −8.7 −17.3 −22.3 | −6.04 | 1.0 |
| 0.4 | 317.22 | 1327 | 521 | 1981 | +1.7 | 0 2.9 −1.9 −11.7 −16.3 −17.4 −8.4 −13.2 | −6.00 | 1.0 |

pressure 0.9:

| β | raw centroid Hz | Features centroid | rolloff | cents | h1..h8 dB re h1 | slope | slips |
|---|---|---|---|---|---|---|---|
| 0.05 | 2532 | 1086 | 4845 | +3.1 | 0 4.7 13.1 −11.0 1.8 6.4 −8.6 1.0 | −1.54 | 3.0 |
| 0.08 | 1571 | 657 | 3531 | +2.0 | 0 −5.5 −9.3 −11.6 −13.6 −15.4 −16.5 −18.1 | −6.01 | 1.0 |
| 0.127 | 1703 | 489 | 3068 | +2.9 | 0 −5.9 −9.3 −11.6 −13.3 −14.2 −13.4 −24.0 | −6.32 | 1.0 |
| 0.2 | 1812 | 1018 | 2422 | +1.0 | 0 27.5 −6.6 31.7 1.5 26.0 −12.2 19.8 | +1.19 | 2.0 |
| 0.3 | 1730 | 1079 | 2196 | +1.4 | 0 −5.3 −7.8 −7.1 −5.4 1.8 0.5 −10.5 | −0.72 | 2.0 |
| 0.4 | 1544 | 595 | 2638 | +1.8 | 0 3.0 −1.6 −11.0 −10.1 −17.0 −7.4 −11.4 | −5.24 | 1.0 |

Brightness does rise toward the bridge (raw centroid 1327 → 2623 Hz over β 0.4 → 0.05 at p 0.5; 1544 → 2532 at p 0.9; rolloff 1981 → 4619) and the position notch sits where a comb would put it (h8 at β 0.127, h4/h7 at 0.3 … ) — but β is not a comb: it moves the regime. β 0.05 is 3–4 slips at both pressures (the bow too close to the bridge for this force), β 0.2 is a two-slip cell with h2 +24 to +32 dB (an octave), and single-slip cells are 0.08–0.127 at p 0.9 and 0.3–0.4 at p 0.5. A position comb on a single ring would give the notches without the regime; the real β gives both, and the engine has to keep β and pressure inside the measured single-slip cells.

#### (7) Release, 130.81 Hz defaults: 1.0 s hold, 50 ms ramp to 0, then ring-down (RMS in 20 ms windows re the window before the release)

Formula with the 3023.6 Hz filter: |H(f0)| = 0.99907, per-period loss 20·log10(0.95·0.99907) = −0.4536 dB, t60 = 60/0.4536 periods = 132.3 periods = **1011 ms**; B_HALF_NEG has two bridge passes per period → 506 ms.

| model | rms before | t60 measured | trace (ms → dB) |
|---|---|---|---|
| **A, bow lifted after the ramp** | 0.1649 | **900 ms** | 120 → −12.8, 320 → −24.9, 520 → −36.7, 720 → −48.5, 920 → −60.6 (a straight −5.9 dB / 100 ms = −0.45 dB per period after the first 100 ms) |
| A, bow resting (v_bow 0, table active) | 0.1649 | 1800 ms | 120 → −27.6 then −1.9 dB / 100 ms |
| B_FULL_POS_DC, lifted | 0.2232 | 940 ms | −6.4 dB / 100 ms |
| B_FULL_POS_DC, resting | 0.2232 | 140 ms | 220 → −87 (killed in a few periods) |
| B_HALF_NEG, lifted | 0.0683 | 520 ms | −12.0 dB / 100 ms (formula 506) |
| B_HALF_NEG, resting | 0.0683 | 340 ms | −21 dB / 100 ms |

The lifted string rings at the formula's slope to within the first window (the 100 ms head start is the bowed level draining to the free level). A resting bow is a different instrument: at v_bow = 0 the table sits at rho 0.98 near dv = 0, so `vNew ≈ −0.98·vString` — the string is stopped at the bow, splits into a 0.127 and an 0.873 string, and what rings for 1.8 s is that. So a DECAY macro works on 0.95 and the bridge corner (t60 = 0.9–1.0 s at C3 here; `fb` toward 0.998 as `Strings.damping` does would stretch it), and the release must lift the bow, which the spec's loop cannot express (no bow state).

#### (8) Cost — ms per rendered second at 176.4 kHz (1.0 s at C3, median of 3 after 2 warm-ups, no decimation)

| model | runs | median |
|---|---|---|
| A two-seg | 9.9 / 9.9 / 9.9 | **9.9 ms/s** |
| B_FULL_POS_DC | 9.5 / 9.5 / 9.5 | 9.5 |
| B_HALF_NEG | 8.9 / 8.8 / 9.0 | 8.9 |
| B on Strings.Loop | 12.2 / 12.3 / 12.2 | 12.2 |

Against BORE's Appendix B 26–59 ms/s and FORK's 88: the bow loop is a two-ring, one-`pow` inner loop and costs a third of BORE's; the finished chain (bandLimit, decimate, levelTo) and a body would add what they add to every melodic engine.

#### (10) Robustness corners

| corner | max abs | non-finite | steady peak / RMS / mean | pitch | h1..h10 | slope | asym / stick | slips | regime |
|---|---|---|---|---|---|---|---|---|---|
| A pressure 1.0, amp 1.0 (HEAVY CRUNCH), 130.81, 1.5 s | **1.1135** | 0 | 1.1127 / 0.4826 / 0.0011 | 130.88 (+0.9) | 0 −11.6 −14.4 −9.1 −6.7 −5.8 −5.7 −10.1 −10.4 −13.7 | −1.7 | 1.75 / 0.90 | 2.0 | PERIODIC — bounded, not a growl: two slips and a flat spectrum, peak past 1.0 on the raw wave (levelTo scales it) |
| same with rhoMax 1.0 | 1.0516 | 0 | 1.0331 / 0.5122 / 0.0016 | 130.93 (+1.5) | 0 −6.0 −9.4 −11.7 −13.2 −14.0 −12.4 −29.2 −22.9 −22.4 | −7.3 | 0.26 / 0.97 | 1.0 | PERIODIC — a sawtooth |
| B_FULL_POS_DC pressure 1.0, amp 1.0 | 0.2286 | 0 | 0.2254 / 0.2254 / 0.2254 | none | — | — | — | — | **DC 0.2254** |
| B_HALF_NEG pressure 1.0, amp 1.0 | 0.2369 | 0 | 0.2369 / 0.1484 / 0.0951 | 131.07 (+3.4) | odd only, h2 −37.8 | −6.6 | 1.06 / 0.95 | 1.0 | PD f0/2 −16.2 dB |
| A, v_bow 0 (bow lifted), 1.0 s | 0.000000 | 0 | rms 0 / mean 0 | — | — | — | — | — | exactly silent, no DC |
| B_FULL_POS_DC, v_bow 0 | 0.000000 | 0 | 0 / 0 | — | — | — | — | — | exactly silent |
| A 41.2 Hz defaults (D 4272.76 = 543.65 + 3729.11), 1.5 s | 0.4196 | 0 | 0.4188 / 0.1797 / −0.0015 | 41.21 (+0.2, ac 0.92) | 0 −0.4 −1.5 0.0 −4.0 5.1 −8.1 −15.4 0.5 −2.1 | −1.7 | 1.42 / 0.89 | 4.0 | PERIODIC; onset 420 ms to 90 % (135 to 50 %) — speaks, in tune, but four slips at this pressure |
| B_FULL_POS_DC 41.2 Hz | 0.1294 | 0 | 0.1285 / 0.0810 / 0.0716 | 41.54 (+14.4, ac 0.81) | 0 −1.5 −5.3 −11.7 … | −11.6 | 0.69 / 0.77 | 0.5 | a half-stuck relaxation |
| B_HALF_NEG 41.2 Hz | 0.1053 | 0 | 0.1053 / 0.0698 / 0.0217 | 41.23 (+1.2, ac 0.81) | odd only, h2 −50.4 | −7.5 | 0.91 / 0.99 | 1.0 | PERIODIC |
| A 130.81, 1 % noise on v_bow | 0.3907 | 0 | 0.3903 / 0.1641 / 0.0002 | 130.91 (+1.4) | 0 4.4 −9.5 −1.1 … | −5.0 | 0.42 / 0.87 | 2.0 | PERIODIC — noise changes nothing about the regime |
| A 130.81, bridge 9028 Hz (iterations 1–3's default) | 0.4149 | 0 | 0.4149 / 0.1812 / −0.0008 | 130.83 (+0.2) | 0 6.8 0.5 4.4 1.8 7.3 1.1 −10.1 6.8 −3.0 | −1.0 | 1.80 / 0.89 | 3.0 | PERIODIC — three slips |

Non-finite samples: 0 in every render of every table (about 400 renders across the five runs).

### The recommendation for (9), with its evidence

Does MODEL B sustain Helmholtz-like motion at all? Three answers for three signs. (a) fb +0.95 — the sign a two-ended string folded into one ring must have — does not: it parks at v_bow, a stable fixed point (the ring fills with the stick velocity, `ret → 0.95·v_bow`, `dv → 0.05·v_bow`, rho → 0.98, and nothing ever forces a slip because there is no inverting return to drive dv large), and `Strings.Loop` reproduces the fixed point to the digit (0.1299 at both pitches). (b) fb −0.95, the brief's literal form, oscillates at f0/2 (f0/2 at +47 and +43 dB re f0): a full-period ring with one inversion resonates at odd multiples of f0/2 — it is a bow at the centre of a string of period 2T. (c) fb +0.95 with BORE's in-loop DC blocker at f0/25 does sustain a single-slip sawtooth-like note (slope −8.3, h2 −0.8, one slip), but it is 9–10 c flat at C3–A3 with the lead already budgeted (the operating point, mean +0.046, is what the blocker fights), it speaks only in islands (amp 0.5: pressure ≤ 0.05 and 0.50–0.65; DC from 0.75; amp 0.2: DC from 0.25), the corner is a knife edge (f0/10 → −25 c, f0/50 → stuck), and its centroid is 838–979 Hz against A's 1471–1916 because it has no bow position — it is a bow whose only return path is a full period away, a topology with no physical position. The half-period ring (d) is the honest single-ring bow, β pinned at 0.5: robust in all 63 Schelleng cells and 15 boundedness cells, one slip everywhere, but an odd-only series (h2 −35 to −40 dB — a clarinet, the timbre BORE's HOLLOW already has), a weak f0/2 at −14 to −19 dB across half the cells, no brightness axis, +3.5 c rising to +17.8 c at 880 because the filter is charged twice per period, and half A's level.

Model A against the same yardsticks: bounded in every cell; +0.4 to +2.9 c at 65–440 Hz (−0.5 to +0.9 with the 0.85 share; +12.1 at 880 without it); single-slip Helmholtz in the measured (loss ≤ 3024 Hz, pressure ≥ 0.65) region with a textbook sawtooth (h2 −5.9, −6.5 to −6.8 dB/oct, flyback 5× the ramp); bow-position brightness (centroid 1327 → 2623 Hz as β 0.4 → 0.05); a Schelleng row that is periodic at f0 across the whole pressure axis with slips/period as the axis that moves; a free ring-down at the formula's slope once the bow lifts; 9.9 ms/s.

**Build on (iii), two `Strings.Loop`s wired by hand, and package the pair as `Strings.Bow` in R1 (which is (i)); not (ii).** What R0 (the Strings.kt-only PR) has to add for it, against BORE's three (`2026-09-28-bore-woodwind-engine-design.md:851-877`):

1. `tune(…, roundTrip: Double = 1.0)` — **fractional**, not only 0.5/1.0: the bridge Loop is tuned at `roundTrip = β` with `loopHz` = the bridge corner (3023.6 Hz at 176.4 kHz — a corner, so it is rate-invariant where STK's pole is not), the nut Loop at `roundTrip = 1 − β` with `loopHz` at `tune`'s cap (`min(loopHz, 0.45·rate)`, `Strings.kt:110`; its 0.06-sample lag is charged like any other), and each Loop keeps its own two-tap 0.5 (the spike's single tap made no difference here either: the residual is the corner effect, not the tap). `retune` (`Strings.kt:450`) carries the factor through, which is also STK's vibrato (`neckDelay_.setDelay`, `stk_Bowed.h:117-119`) for free.
2. The junction split `reflected(): Float` / `inject(y: Float)` with `next(x) = inject(x + reflected())` — exactly BORE's shape; the bow is `rB = bridge.reflected(); rN = nut.reflected(); v = rB + rN; vNew = bowDown ? (vBow − v)·rho(vBow − v) : 0; nut.inject(rB + vNew); bridge.inject(rN + vNew)`, with `fb = −0.95` on the bridge Loop and `fb = −1.0` on the nut Loop (both already expressible: `fb` is a Float, `Strings.kt:377`). The "B on Strings.Loop" row is the proof that the lag-charged workaround is not good enough as a junction (it reproduces the DC fixed point exactly, so it is faithful, but a two-Loop bow cannot be built on `next(x)` because each Loop's returning wave is needed before the other Loop's write).
3. BORE's second addition (`dcBlock`/`dcHz`) is **not** needed by the bow: the two-segment loop's steady mean is |≤ 0.003| in every A row (the nut inversion keeps it zero-mean); it is only the single-ring bow that needs a blocker, and that is the model not to build.
4. Two things R0 cannot give and R1 must: a measured per-voice tuning correction for the corner effect (about −0.15 × the bridge filter's f0 delay, i.e. ~1.3 samples at 3023 Hz; the share 0.85 row), the `Dsp.Ladder` precedent BORE's cone already uses; and `MIN_LOOP_SAMPLES`'s `require` (`Strings.kt:38, :168`) is safe for the bridge segment down to β 0.05 at 880 Hz (9.6 samples) but a voice root above that with a low β needs checking at design time, not by the require.

### What the corrected engine would still need

- **A body.** The raw bridge wave is the instrument's input, not its sound: STK follows it with six biquads (`stk_Bowed.cpp:61-66`) and the spec's voices with `Modes` tables; `Strings.bodyRing` (`Strings.kt:722`, differentiated drive, RMS-matched, identity at 0) is the house's route, with the spec's cello/contrabass air and wood numbers (98 / 220 / 340 Hz; 58 / 110 / 180) unsourced and labelled *shape* until cited, as BORE's formant rule says.
- **β as a real position, not a comb** — and a constrained one: β moves the regime (0.05 is 3–4 slips at any pressure, 0.2 is an octave-dominant hole, 0.08–0.127 and 0.3–0.4 are single-slip depending on pressure), so a BOW-position axis must be mapped onto measured cells, or fixed per voice (STK fixes 0.127). A position comb on one ring gives the notches but not the physics, and the physics is the part that costs.
- **The (loss, force) map as the macro contract.** Single-slip Helmholtz needs the bridge corner ≤ ~3 kHz and pressure ≥ 0.65 (amp 0.5), ≥ 0.9 (amp 0.8), with islands in between; below that the string plays 2–4 slips per period (an octave or two up in timbre, f0 by autocorrelation). ROSIN (the spec's table slope) and BOW (velocity) both cross those islands; the spec's "hard pressure → period-doubling growl" is not what a memoryless table does (0 PD cells in 63) — growl needs a different friction law (a thermal or hysteretic bow, or a rosin-temperature state) or a deliberate loss/force corner, and that is a gate question, not a knob.
- **Pressure 0 must lift the bow** (STK's `bowDown_`): the table at slope 5 still plays (table 4, row 0.00), and a resting bow at v_bow 0 stops the string at the bow (table 7).
- **A release that lifts the bow**, then the free ring-down (t60 0.9–1.0 s at C3 with 0.95; DECAY would move `fb` and/or the corner the way `Strings.damping` does), plus `fadeTail`.
- **Onset**: 325–420 ms to 90 % at C2/E1, 160–245 ms at A3 — the string's own build-up. The spec's 30–60 ms contrabass claim is not this model's number; an onset shaper (a pressure or velocity overshoot on the attack) is a gate question.
- **The held LOOP seam**: the note is periodic with ac 0.97–0.98 after 0.7 s and its mean is zero, so a loop start past ~0.45 s at C2 (~0.25 s at A3) has a seamable tail; the pitch residual must be corrected first or the seam and the keygroup disagree by up to 12 c at the top.
- **Vibrato**: `retune` on the nut Loop (STK modulates the neck delay at 6.1 Hz with gain ≤ 0.4·baseDelay·…, `stk_Bowed.cpp:58, :117-119`), baked per BORE's rule; not tested here.
- **Noise**: 1 % on v_bow changes nothing measurable (table 10); the spec's "dynamic air turbulence injection" has no analogue in a bow and STK has none; if a bow-hair noise is wanted it is a gate question with a level.
- **The tarab** (SARANGI's `Pluck.ks` sympathetic loops driven by the bridge signal, spec p.8) — untested; `Strings.pluck` with the bridge wave as `exciter` input is the shape, and the bridge wave's DC is zero so it will not thump.
- **What `levelTo` does to a stuck render**: not tested; table 1's DC cells (B only) would be lifted to full-scale DC by `levelTo` the way BORE's sub-threshold renders were lifted to hiss — one more reason the engine must never render the single-ring model, and must render "bow lifted" as exact silence (it does: 0.000000).
- **The pitch correction** (share 0.85 of the bridge filter's delay, measured, ±2.8 c over ten cells) and a claims test at 5 c across the voice roots, including 880.
- **The 4× band-limit is still needed**: the friction table's kink mints harmonics up to the ring's bandwidth (h10 of single-slip A is −22 dB, the raw centroid 1.5–1.9 kHz); `Tide.bandLimit` before `decimate` as every melodic engine does.

## Appendix B — `BowSpike.kt`, as run

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * SPIKE - throwaway. The ARCO (bowed string) Phase-0 spike: the corrected
 * bowed string built on the house's primitives, the way STK's Bowed does
 * it, so BowSpikeTest can print numbers. Not an engine: no Patch, no
 * preset, no picker entry. Lives in the TEST source set so `internal`
 * symbols (Strings.tune, Strings.Loop, Tide.bandLimit) resolve.
 *
 * Two models, both at [RATE] = 176 400 Hz, both returning the raw
 * bridge-side signal (no body, no normalise):
 *
 * MODEL A - STK-faithful, two round-trip segments (stk_Bowed.h:104-126,
 * stk_BowTable.h:84-99, stk_Bowed.cpp:100-105, :150-155). The bow sits at
 * `beta` of the string from the bridge; the bridge-side line is the
 * bow->bridge->bow round trip (beta*D) and the neck-side line the
 * bow->nut->bow round trip ((1-beta)*D), where D = one period minus the
 * bridge filter's phase delay at f0 (Strings.tune's closed form,
 * Strings.kt:110-114, read back through tune()'s `exact`). Each fraction
 * is a first-order Jaffe-Smith allpass, the same form Strings.Loop.next
 * uses (Strings.kt:398-400), single tap (no two-tap average).
 *
 * MODEL B - ONE ring with the junction at its one point: BORE's
 * reflected()/inject() shape with the bow table in place of the reed
 * table. Variants (see [BVariant]) differ in the ring's round-trip factor,
 * the sign of its feedback, the string-velocity gain at the junction and
 * whether an in-loop DC blocker runs.
 */
internal object BowSpike {

    const val RATE = Dsp.RATE * Dsp.OVERSAMPLE // 176 400

    /** STK stringFilter_.setGain(0.95) (stk_Bowed.cpp:56). */
    const val REFLECTION = 0.95f

    /** STK bowTable_.setOffset(0.001) (stk_Bowed.cpp:48). */
    const val OFFSET = 0.001f

    /** STK BowTable minOutput_/maxOutput_ defaults (stk_BowTable.h:26). */
    const val RHO_MIN = 0.01f
    const val RHO_MAX = 0.98f

    /** STK betaRatio_ (stk_Bowed.cpp:73). */
    const val BETA_DEFAULT = 0.127236f

    /** The task's envelope: 20 ms linear attack on v_bow, hold, 50 ms linear release to 0. */
    const val ATTACK_S = 0.020f
    const val RELEASE_S = 0.050f

    /** STK's bridge one-pole: pole 0.75 - 0.2 * 22050 / rate (stk_Bowed.cpp:55). */
    fun stkPole(rate: Int): Double = 0.75 - 0.2 * 22050.0 / rate

    /** The Dsp.OnePole cutoff whose pole exp(-2*pi*fc/rate) equals [pole] (Dsp.kt:417). */
    fun poleToHz(pole: Double, rate: Int): Float = (-ln(pole) * rate / (2.0 * PI)).toFloat()

    /** STK's pole formula evaluated at our 176.4 kHz: pole 0.725, OnePole cutoff ~9029 Hz. */
    val BRIDGE_HZ_STK_AT_176K: Float = poleToHz(stkPole(RATE), RATE)

    /** STK's pole formula at its usual 44.1 kHz: pole 0.65, ~3023 Hz - the filter STK users actually hear. */
    val BRIDGE_HZ_STK_AT_44K: Float = poleToHz(stkPole(Dsp.RATE), Dsp.RATE)

    /**
     * The bridge corner the spike settled on (iteration 4): STK's pole
     * formula is not rate-invariant - at 176.4 kHz it gives 9028 Hz, a
     * third of the per-period high-frequency loss STK users hear at 44.1
     * kHz (3023 Hz), and with that little loss the string never leaves
     * multiple-slip motion (iteration 3's Schelleng rows: 2-4 slips per
     * period at every pressure). 3023 Hz is what STK sounds like.
     */
    val DEFAULT_BRIDGE_HZ: Float = BRIDGE_HZ_STK_AT_44K

    /** STK startBowing: maxVelocity_ = 0.03 + 0.2 * amplitude (stk_Bowed.cpp:105). */
    fun vBowFor(amplitude: Float): Float = 0.03f + 0.2f * amplitude

    /** STK controlChange(BowPressure): slope = 5 - 4 * pressure (stk_Bowed.cpp:152). */
    fun slopeFor(pressure: Float): Float = 5f - 4f * pressure

    /** STK BowTable::tick (stk_BowTable.h:84-99): rho = clamp((|slope*(dv+offset)| + 0.75)^-4, min, max). */
    fun rho(dv: Float, slope: Float, rhoMax: Float = RHO_MAX): Float {
        val s = abs((dv + OFFSET) * slope) + 0.75f
        val r = s.toDouble().pow(-4.0).toFloat()
        return r.coerceIn(RHO_MIN, rhoMax)
    }

    /**
     * The bridge one-pole's phase delay at [f0], in samples, read back from
     * Strings.tune's own budget (Strings.kt:110-114, :161): tune's
     * `exact = (rate / freq) - filterDelay - 0.5` with nothing else on, so
     * filterDelay = (rate / freq) - 0.5 - exact. `rate / freq` is a Float
     * division there, so it is reproduced as one here.
     */
    fun onePoleDelay(f0: Float, hz: Float, rate: Int): Double {
        val t = Strings.tune(f0, hz, rate)
        return (rate / f0).toDouble() - 0.5 - t.exact
    }

    /** The same closed form, written out (Strings.kt:110-114 verbatim) - a cross-check row in the report. */
    fun onePoleDelayClosedForm(f0: Float, hz: Float, rate: Int): Double {
        val filterA = 1.0 - exp(-2.0 * PI * kotlin.math.min(hz, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * f0 / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        return -filterPhase / w
    }

    /** One-pole magnitude at [f0]: a / sqrt(1 - 2 r cos w + r^2), for the ring-down formula. */
    fun onePoleMag(f0: Float, hz: Float, rate: Int): Double {
        val a = 1.0 - exp(-2.0 * PI * kotlin.math.min(hz, rate * 0.45f) / rate)
        val r = 1.0 - a
        val w = 2.0 * PI * f0 / rate
        return a / kotlin.math.sqrt(1.0 - 2.0 * r * cos(w) + r * r)
    }

    /** Strings.kt:142-146 verbatim, with the corner a parameter (BORE's dcHz): the one-pole high-pass's lead at [f0] (negative). */
    fun dcBlockerDelay(f0: Float, dcHz: Float, rate: Int): Double {
        val dcA = (1.0 - exp(-2.0 * PI * dcHz / rate)).toFloat()
        val w = 2.0 * PI * f0 / rate
        val r = 1.0 - dcA
        val phase = atan2(sin(w), 1.0 - cos(w)) - atan2(r * sin(w), 1.0 - r * cos(w))
        return -phase / w
    }

    /**
     * One fractional delay segment: a ring of `n + 2` floats read `n`
     * samples back (written at step j, read at step j + n before that
     * step's push) through a first-order allpass for the fraction -
     * Strings.Loop.next's tap and allpass (Strings.kt:398-400) without the
     * two-tap average. `out()` then `push()` once per step.
     */
    class Seg(val exact: Double) {
        val n: Int = floor(exact).toInt()
        val frac: Float = (exact - n).toFloat()
        val a: Float = (1f - frac) / (1f + frac)
        private val size = n + 2
        private val h = FloatArray(size)
        private var i = 0
        private var apX1 = 0f
        private var apY1 = 0f

        init {
            require(n >= 1) { "segment of $exact samples is shorter than one sample" }
        }

        fun out(): Float {
            val d = h[Math.floorMod(i - n, size)]
            val y = a * (d - apY1) + apX1
            apX1 = d
            apY1 = y
            return y
        }

        fun push(x: Float) {
            h[i % size] = x
            i++
        }
    }

    /** BRIDGE: the wave arriving at the bridge (STK's output). BOW: vString + vNew, the string's velocity under the bow - Helmholtz motion's rectangular wave with slip fraction beta. */
    enum class Tap { BRIDGE, BOW }

    class Params(
        val f0: Float,
        val pressure: Float = 0.5f,
        val amplitude: Float = 0.5f,
        val beta: Float = BETA_DEFAULT,
        val bridgeHz: Float = DEFAULT_BRIDGE_HZ,
        val seconds: Float = 1.0f,
        /** Where the 50 ms release starts; default = seconds - RELEASE_S (the note fills the render). */
        val releaseAt: Float = -1f,
        val attack: Float = ATTACK_S,
        val release: Float = RELEASE_S,
        /** Multiplicative noise on v_bow (0 = seedless); Dsp.Noise(seed). */
        val noise: Float = 0f,
        val seed: Int = 1,
        val rhoMax: Float = RHO_MAX,
        /** v_bow override in absolute units (< 0 = use vBowFor(amplitude)); the "bow lifted" corner passes 0. */
        val vBowAbs: Float = -1f,
        /** Which signal to return: the wave arriving at the bridge (STK's output tap) or the string velocity at the bow point. */
        val tap: Tap = Tap.BRIDGE,
        /** Model B only: overrides the variant's DC blocker corner (as a fraction of f0) when >= 0. */
        val dcHzOverF0: Float = -1f,
        /** Lift the bow (vNew = 0, STK's bowDown_ = false) once the release ramp has reached 0, so the ring-down is the free string's. Iteration 3. */
        val lift: Boolean = true,
        /**
         * Iteration 5 probe: the share of the bridge one-pole's phase delay
         * at f0 that the budget subtracts. 1.0 is Strings.tune's rule (the
         * linear resonance); a bowed string's period is set by the corner's
         * arrival, which sees less of the filter's delay, so 1.0 reads sharp.
         */
        val filterDelayShare: Float = 1f,
    ) {
        val vMax: Float get() = if (vBowAbs >= 0f) vBowAbs else vBowFor(amplitude)
        val slope: Float get() = slopeFor(pressure)
        val total: Int get() = (seconds * RATE).toInt()
        val releaseStart: Int get() = if (releaseAt >= 0f) (releaseAt * RATE).toInt() else total - (release * RATE).toInt()
    }

    /** True once the release ramp has finished (the bow is off the string when [Params.lift]). */
    fun released(p: Params, n: Int): Boolean = n >= p.releaseStart + (p.release * RATE).toInt().coerceAtLeast(1)

    /** Both taps of one render. */
    class Out(val bridge: FloatArray, val bow: FloatArray)

    /** v_bow's envelope at sample [n]: linear attack, hold, linear release, then 0. */
    fun envelope(p: Params, n: Int): Float {
        val attackN = (p.attack * RATE).toInt().coerceAtLeast(1)
        val releaseN = (p.release * RATE).toInt().coerceAtLeast(1)
        val rs = p.releaseStart
        return when {
            n < attackN && n < rs -> n.toFloat() / attackN
            n < rs -> 1f
            n < rs + releaseN -> 1f - (n - rs).toFloat() / releaseN
            else -> 0f
        }
    }

    class Budget(val filterDelay: Double, val d: Double, val segA: Double, val segB: Double)

    fun budgetA(p: Params): Budget {
        val fd = onePoleDelay(p.f0, p.bridgeHz, RATE) * p.filterDelayShare
        val d = (RATE / p.f0).toDouble() - fd
        return Budget(fd, d, p.beta * d, (1.0 - p.beta) * d)
    }

    /**
     * MODEL A, STK's tick() (stk_Bowed.h:104-126) on two [Seg]s:
     *   bridgeRefl = -(0.95 * lp(bridge.out))      // bridge inverts, loses, filters
     *   nutRefl    = -neck.out                      // rigid nut inverts
     *   vString    = bridgeRefl + nutRefl
     *   dv         = vBow - vString
     *   vNew       = dv * rho(dv)
     *   neck.push(bridgeRefl + vNew); bridge.push(nutRefl + vNew)
     *   out        = bridge.out (the wave arriving at the bridge, STK's output tap before its body filters)
     */
    fun renderA(p: Params): FloatArray = renderABoth(p).let { if (p.tap == Tap.BOW) it.bow else it.bridge }

    fun renderABoth(p: Params): Out {
        val b = budgetA(p)
        val bridge = Seg(b.segA)
        val neck = Seg(b.segB)
        val lp = Dsp.OnePole(RATE)
        val noise = Dsp.Noise(p.seed)
        val slope = p.slope
        val out = FloatArray(p.total)
        val bow = FloatArray(p.total)
        for (n in 0 until p.total) {
            var vBow = p.vMax * envelope(p, n)
            if (p.noise > 0f) vBow *= 1f + p.noise * noise.next()
            val bridgeOut = bridge.out()
            val neckOut = neck.out()
            val bridgeRefl = -(REFLECTION * lp.lp(bridgeOut, p.bridgeHz))
            val nutRefl = -neckOut
            val vString = bridgeRefl + nutRefl
            val dv = vBow - vString
            val down = !(p.lift && released(p, n))
            val vNew = if (down) dv * rho(dv, slope, p.rhoMax) else 0f
            neck.push(bridgeRefl + vNew)
            bridge.push(nutRefl + vNew)
            out[n] = bridgeOut
            bow[n] = vString + vNew
        }
        return Out(out, bow)
    }

    /**
     * MODEL B's variants. [roundTrip] is the ring's length as a fraction of
     * the period (BORE's tune(roundTrip)), [fb] the feedback at the one
     * junction (the bridge filter and loss all in one place), [vGain] the
     * factor from the returning wave to the string velocity the bow sees,
     * [dcHzOverF0] an in-loop DC blocker corner as a fraction of f0 (0 =
     * none; BORE's cone measured best at f0/25).
     */
    enum class BVariant(val roundTrip: Double, val fb: Float, val vGain: Float, val dcHzOverF0: Float, val note: String) {
        /** The task's literal form: a full period, one inversion. */
        B_FULL_NEG(1.0, -REFLECTION, 1f, 0f, "full period, fb -0.95, v=ret"),
        /** A two-ended string folded into one ring: two inversions per period cancel, so fb +0.95 (Karplus-Strong's sign). */
        B_FULL_POS(1.0, +REFLECTION, 1f, 0f, "full period, fb +0.95, v=ret"),
        /** As B_FULL_POS with BORE's cone DC blocker at f0/25 in the loop, budgeted. */
        B_FULL_POS_DC(1.0, +REFLECTION, 1f, 1f / 25f, "full period, fb +0.95, v=ret, DC blocker f0/25"),
        /** The bow at the string's midpoint (beta = 0.5) reduced by symmetry: both half-string round trips are one half-period ring, both incoming waves equal, v = 2*ret. */
        B_HALF_NEG(0.5, -REFLECTION, 2f, 0f, "half period, fb -0.95, v=2*ret (beta pinned at 0.5)"),
    }

    class BudgetB(val filterDelay: Double, val dcDelay: Double, val exact: Double)

    fun dcRatio(p: Params, v: BVariant): Float = if (p.dcHzOverF0 >= 0f) p.dcHzOverF0 else v.dcHzOverF0

    fun budgetB(p: Params, v: BVariant): BudgetB {
        val fd = onePoleDelay(p.f0, p.bridgeHz, RATE)
        val dcr = dcRatio(p, v)
        val dcd = if (dcr > 0f) dcBlockerDelay(p.f0, p.f0 * dcr, RATE) else 0.0
        return BudgetB(fd, dcd, v.roundTrip * (RATE / p.f0).toDouble() - fd - dcd)
    }

    /**
     * MODEL B on a private ring that mirrors Strings.Loop.next's stages
     * (tap -> allpass -> loopLp -> [DC blocker] -> fb) with the junction
     * where Loop's `x +` is (Strings.kt:429):
     *   ret0 = ring.out(); ret = fb * lp(ret0); [ret -= dc(ret)]
     *   vString = vGain * ret; dv = vBow - vString; vNew = dv * rho(dv)
     *   ring.push(ret + vNew); out = ret0
     */
    fun renderB(p: Params, v: BVariant): FloatArray = renderBBoth(p, v).let { if (p.tap == Tap.BOW) it.bow else it.bridge }

    fun renderBBoth(p: Params, v: BVariant): Out {
        val b = budgetB(p, v)
        val ring = Seg(b.exact)
        val lp = Dsp.OnePole(RATE)
        val noise = Dsp.Noise(p.seed)
        val slope = p.slope
        val dcr = dcRatio(p, v)
        val dcA = if (dcr > 0f) (1.0 - exp(-2.0 * PI * (p.f0 * dcr) / RATE)).toFloat() else 0f
        var dc = 0f
        val out = FloatArray(p.total)
        val bow = FloatArray(p.total)
        for (n in 0 until p.total) {
            var vBow = p.vMax * envelope(p, n)
            if (p.noise > 0f) vBow *= 1f + p.noise * noise.next()
            val ret0 = ring.out()
            var ret = v.fb * lp.lp(ret0, p.bridgeHz)
            if (dcA > 0f) {
                dc += dcA * (ret - dc)
                ret -= dc
            }
            val vString = v.vGain * ret
            val dv = vBow - vString
            val down = !(p.lift && released(p, n))
            val vNew = if (down) dv * rho(dv, slope, p.rhoMax) else 0f
            ring.push(ret + vNew)
            out[n] = ret0
            bow[n] = vString + vNew
        }
        return Out(out, bow)
    }

    /**
     * MODEL B literally on Strings.Loop (fb +0.95, loopHz = the bridge
     * corner). next(x) returns `x + fb * lp(ap(avg))` (Strings.kt:429) and
     * keeps the returning wave private, so the junction here uses the
     * PREVIOUS sample's returning wave (`ret = y - x` of the last step) - a
     * one-sample lag, charged to the budget by constructing the Loop with
     * `tune(...).n - 1`. Everything else (two-tap average and its 0.5, the
     * allpass, loopLp) is Loop's own. No DC blocker is reachable without
     * jawari (Strings.kt:424), so this is B_FULL_POS's twin, not B_FULL_POS_DC's.
     */
    fun renderBOnLoop(p: Params): FloatArray {
        val t = Strings.tune(p.f0, p.bridgeHz, RATE)
        val loop = Strings.Loop(t.n - 1, t.a, REFLECTION, p.bridgeHz, RATE)
        val noise = Dsp.Noise(p.seed)
        val slope = p.slope
        var ret = 0f
        val out = FloatArray(p.total)
        for (n in 0 until p.total) {
            var vBow = p.vMax * envelope(p, n)
            if (p.noise > 0f) vBow *= 1f + p.noise * noise.next()
            val dv = vBow - ret
            val down = !(p.lift && released(p, n))
            val vNew = if (down) dv * rho(dv, slope, p.rhoMax) else 0f
            val y = loop.next(vNew)
            ret = y - vNew
            out[n] = ret / REFLECTION // the wave arriving at the junction before loss, like renderB's ret0 (filtered here, since Loop's lp is inside)
        }
        return out
    }
}
```

## Appendix C — `BowSpikeTest.kt`, as run

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.synth.BowSpike.BVariant
import com.snipsnap.synth.BowSpike.Params
import com.snipsnap.synth.BowSpike.RATE
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SPIKE - print-only. Every measurement is a table row; the report is the
 * artefact. The only assertion is assertTrue(true). Tables are also
 * written to [OUT] so the numbers survive the worktree.
 */
class BowSpikeTest {

    companion object {
        const val OUT = "build/arco-spike-tables.txt" // the session wrote this to its scratchpad; a re-run writes under build/
        const val C3 = 130.81f
        const val C2 = 65.41f
        const val E1 = 41.2f
    }

    private val log = StringBuilder()

    private fun line(s: String = "") {
        println(s)
        log.append(s).append('\n')
    }

    private fun flush() {
        try {
            File(OUT).parentFile.mkdirs()
            File(OUT).writeText(log.toString())
        } catch (e: Exception) {
            println("could not write $OUT: $e")
        }
    }

    private fun f(x: Double, d: Int = 2) = String.format("%.${d}f", x)
    private fun f(x: Float, d: Int = 2) = f(x.toDouble(), d)

    // ---------------------------------------------------------------- helpers

    private fun maxAbs(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var m = 0f
        for (i in from until min(to, x.size)) { val a = abs(x[i]); if (a > m) m = a }
        return m
    }

    private fun nonFinite(x: FloatArray): Int = x.count { !it.isFinite() }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceAtLeast(0); val b = min(to, x.size)
        if (b <= a) return 0.0
        var acc = 0.0
        for (i in a until b) acc += x[i].toDouble() * x[i]
        return sqrt(acc / (b - a))
    }

    private fun mean(x: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceAtLeast(0); val b = min(to, x.size)
        if (b <= a) return 0.0
        var acc = 0.0
        for (i in a until b) acc += x[i]
        return acc / (b - a)
    }

    private fun cents(measured: Double, want: Double) = FineTuning.cents(measured, want)

    /** Normalised autocorrelation r(lag)/r(0) over [from, from+len). */
    private fun acf(x: FloatArray, from: Int, len: Int, lagFrom: Int, lagTo: Int): DoubleArray {
        val r = DoubleArray(lagTo + 1)
        val m = mean(x, from, from + len)
        var e = 0.0
        for (i in from until from + len) { val v = x[i] - m; e += v * v }
        if (e <= 1e-18) return r
        for (lag in lagFrom..lagTo) {
            var s = 0.0
            for (i in from until from + len - lag) s += (x[i] - m) * (x[i + lag] - m)
            r[lag] = s / e
        }
        return r
    }

    /** The raw-rate autocorrelation pitch near [f0] (±6 %), parabolic interpolation on the lag peak; returns (hz, peak). */
    private fun acPitch(x: FloatArray, from: Int, len: Int, f0: Float): Pair<Double, Double> {
        val p = RATE / f0.toDouble()
        val lo = (p * 0.94).toInt().coerceAtLeast(2)
        val hi = (p * 1.06).toInt() + 1
        val r = acf(x, from, len, lo - 1, hi + 1)
        var best = lo; var bv = -2.0
        for (lag in lo..hi) if (r[lag] > bv) { bv = r[lag]; best = lag }
        val a = r[best - 1]; val b = r[best]; val c = r[best + 1]
        val den = a - 2 * b + c
        val delta = if (den != 0.0) 0.5 * (a - c) / den else 0.0
        return (RATE / (best + delta)) to bv
    }

    /** Hann-windowed Goertzel power at [hz] over [from, from+len). */
    private fun goertzel(x: FloatArray, from: Int, len: Int, hz: Double): Double {
        val w = 2.0 * PI * hz / RATE
        val coeff = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until len) {
            val win = 0.5 - 0.5 * cos(2.0 * PI * i / (len - 1))
            val s = x[from + i] * win + coeff * s1 - s2
            s2 = s1; s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    /** The strongest Goertzel power within ±[span] of [hz] (11 steps). */
    private fun peakPower(x: FloatArray, from: Int, len: Int, hz: Double, span: Double = 0.015): Double {
        var best = 0.0
        for (s in -5..5) {
            val h = hz * (1.0 + span * s / 5.0)
            val e = goertzel(x, from, len, h)
            if (e > best) best = e
        }
        return best
    }

    private fun db(p: Double, ref: Double) = 10.0 * log10(max(p, 1e-30) / max(ref, 1e-30))

    /** h1..hK in dB re h1, at multiples of the MEASURED f0 (±1.5 % search each). */
    private fun harmonics(x: FloatArray, from: Int, len: Int, f0meas: Double, k: Int = 10): DoubleArray {
        val p1 = peakPower(x, from, len, f0meas)
        return DoubleArray(k) { i -> db(peakPower(x, from, len, f0meas * (i + 1)), p1) }
    }

    /** Least-squares slope of harmonic level (dB) against log2(k), k = 1..K: a sawtooth is -6 dB/oct. */
    private fun slopeDbPerOct(h: DoubleArray): Double {
        val n = h.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) { val xk = log2((i + 1).toDouble()); sx += xk; sy += h[i]; sxx += xk * xk; sxy += xk * h[i] }
        val den = n * sxx - sx * sx
        return if (den == 0.0) 0.0 else (n * sxy - sx * sy) / den
    }

    /**
     * Waveform asymmetry over the last whole period ending at [end]:
     * ratio = max(x') / |min(x')| (a sine gives 1; a sawtooth's fast flyback
     * puts it far from 1), and stickFrac = the fraction of the period where
     * |x'| < 0.2 * max|x'| (a sine gives 0.13, a sawtooth close to 1).
     */
    private fun asymmetry(x: FloatArray, end: Int, periodSamples: Double): Pair<Double, Double> {
        if (!periodSamples.isFinite() || periodSamples < 2.0 || periodSamples > end - 2) return Double.NaN to Double.NaN
        val p = periodSamples.roundToInt()
        val start = end - p - 1
        if (start < 0) return Double.NaN to Double.NaN
        var maxUp = 0.0; var maxDown = 0.0; var maxAbsD = 0.0
        val d = DoubleArray(p)
        for (i in 0 until p) {
            d[i] = (x[start + i + 1] - x[start + i]).toDouble()
            if (d[i] > maxUp) maxUp = d[i]
            if (d[i] < maxDown) maxDown = d[i]
            if (abs(d[i]) > maxAbsD) maxAbsD = abs(d[i])
        }
        val ratio = if (maxDown == 0.0) Double.POSITIVE_INFINITY else maxUp / -maxDown
        var stick = 0
        for (v in d) if (abs(v) < 0.2 * maxAbsD) stick++
        return ratio to stick.toDouble() / p
    }

    /** Magnitude-weighted spectral centroid (Hz, to 20 kHz) of a Hann-windowed 65536-point frame from [from]. */
    private fun centroid(x: FloatArray, from: Int): Double {
        val n = 65536
        val re = FloatArray(n); val im = FloatArray(n)
        val len = min(n, x.size - from)
        for (i in 0 until len) re[i] = x[from + i] * (0.5f - 0.5f * cos(2.0 * PI * i / (len - 1)).toFloat())
        Fft.forward(re, im)
        var num = 0.0; var den = 0.0
        val binHz = RATE.toDouble() / n
        for (b in 1 until n / 2) {
            val hz = b * binHz
            if (hz > 20_000.0) break
            val m = hypot(re[b].toDouble(), im[b].toDouble())
            num += hz * m; den += m
        }
        return if (den > 0) num / den else 0.0
    }

    /** The core decimated (Tide.bandLimit -> Dsp.decimate), no levelTo: the house's melodic chain minus the level stage (Fork.kt:465-466). */
    private fun coreSnip(raw: FloatArray): Snip {
        val b = raw.copyOf()
        Tide.bandLimit(b, RATE)
        return Snip(Dsp.decimate(b, Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
    }

    /** The finished Snip: core + Dsp.levelTo(MELODIC_LOUDNESS_TARGET) + fadeTail (Fork.kt:467-468). */
    private fun finishedSnip(raw: FloatArray): Snip {
        val s = coreSnip(raw)
        Dsp.levelTo(s.samples, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(s.samples)
        return s
    }

    enum class Regime { SILENT, DC, PERIODIC, PERIOD_DOUBLED, APERIODIC }

    class Cell(val regime: Regime, val rms: Double, val acPeak: Double, val peakHz: Double, val subDb: Double, val thirdDb: Double, val cents: Double)

    /**
     * The Schelleng classification over [from, from+len): SILENT (RMS < 1e-3);
     * APERIODIC (no normalised autocorrelation peak >= 0.6 for lags 0.45 P .. 3.3 P);
     * PERIOD_DOUBLED (Goertzel power at f0/2 within 20 dB of f0, or at f0/3);
     * else PERIODIC, with the pitch of the strongest lag and its cents vs f0.
     */
    private fun classify(x: FloatArray, from: Int, len: Int, f0: Float): Cell {
        val r = rms(x, from, from + len)
        if (r < 1e-3) return Cell(Regime.SILENT, r, 0.0, 0.0, -99.0, -99.0, 0.0)
        val m = mean(x, from, from + len)
        val acRms = sqrt(max(r * r - m * m, 0.0))
        // A constant is not aperiodic, it is stuck: the mean-removed RMS says which.
        if (acRms < 1e-3) return Cell(Regime.DC, m, 0.0, 0.0, -99.0, -99.0, 0.0)
        val p = RATE / f0.toDouble()
        val lo = (0.45 * p).toInt().coerceAtLeast(2)
        val hi = (3.3 * p).toInt()
        val ac = acf(x, from, len, lo, hi)
        var best = lo; var bv = -2.0
        for (lag in lo..hi) if (ac[lag] > bv && ac[lag] >= ac[max(lag - 1, lo)] && ac[lag] >= ac[min(lag + 1, hi)]) { bv = ac[lag]; best = lag }
        // Prefer the lag at the f0 period when its peak is within 0.03 of the strongest (a 2P or 3P lag is never
        // lower than P for a periodic signal; a dominant h3 puts a spurious peak at 2P/3).
        var pBest = (p * 0.94).toInt(); var pv = -2.0
        for (lag in (p * 0.94).toInt()..(p * 1.06).toInt()) if (ac[lag] > pv) { pv = ac[lag]; pBest = lag }
        if (pv >= bv - 0.03) { best = pBest; bv = pv }
        val e1 = peakPower(x, from, len, f0.toDouble())
        val sub = db(peakPower(x, from, len, f0 / 2.0), e1)
        val third = db(peakPower(x, from, len, f0 / 3.0), e1)
        val hz = RATE / best.toDouble()
        val c = cents(hz, f0.toDouble())
        val regime = when {
            bv < 0.6 -> Regime.APERIODIC
            sub > -20.0 || third > -20.0 -> Regime.PERIOD_DOUBLED
            else -> Regime.PERIODIC
        }
        return Cell(regime, r, bv, hz, sub, third, c)
    }

    /**
     * Slips per period on the bow-point velocity: falling crossings of
     * 0.5 * vBow (the string sticks at ~0.98 vBow and slips negative) over the
     * last [periods] periods, divided by [periods]. Helmholtz motion = 1.0;
     * double slip = 2.0.
     */
    private fun slipsPerPeriod(bow: FloatArray, end: Int, periodSamples: Double, vBow: Float, periods: Int = 10): Double {
        if (!periodSamples.isFinite() || periodSamples < 2.0) return Double.NaN
        val span = (periodSamples * periods).roundToInt()
        val start = end - span
        if (start < 1) return Double.NaN
        val thr = 0.5 * vBow
        var count = 0
        for (i in start until end) if (bow[i - 1] >= thr && bow[i] < thr) count++
        return count.toDouble() / periods
    }

    private fun cellStr(c: Cell): String = when (c.regime) {
        Regime.SILENT -> "SILENT(rms ${f(c.rms, 5)})"
        Regime.DC -> "DC(mean ${f(c.rms, 4)})"
        Regime.APERIODIC -> "APERIODIC(ac ${f(c.acPeak)}, rms ${f(c.rms, 3)})"
        Regime.PERIOD_DOUBLED -> "PD(${f(c.peakHz, 1)}Hz f0/2 ${f(c.subDb, 1)}dB f0/3 ${f(c.thirdDb, 1)}dB)"
        Regime.PERIODIC -> "PERIODIC(${f(c.peakHz, 2)}Hz ${f(c.cents, 1)}c ac ${f(c.acPeak)})"
    }

    private fun steadyWindow(p: Params, seconds: Float = 0.3f): Pair<Int, Int> {
        val len = (seconds * RATE).toInt()
        return (p.releaseStart - len) to len
    }

    private fun renderRow(name: String, o: BowSpike.Out, p: Params) {
        val x = if (p.tap == BowSpike.Tap.BOW) o.bow else o.bridge
        val (from, len) = steadyWindow(p)
        val (hz, ac) = acPitch(x, from, len, p.f0)
        val speaks = ac >= 0.3 && hz.isFinite() && hz > 0
        val h = if (speaks) harmonics(x, from, len, hz) else harmonics(x, from, len, p.f0.toDouble())
        val (ratio, stick) = if (speaks) asymmetry(x, from + len, RATE / hz) else (Double.NaN to Double.NaN)
        val pitchCol = if (speaks) "${f(hz, 2)} (${f(cents(hz, p.f0.toDouble()), 1)} c, ac ${f(ac)})" else "no f0 peak (ac ${f(ac)})"
        val slips = if (speaks) slipsPerPeriod(o.bow, from + len, RATE / hz, p.vMax) else Double.NaN
        line("| $name | ${f(maxAbs(x), 4)} | ${nonFinite(x)} | ${f(maxAbs(x, from, from + len), 4)} | ${f(rms(x, from, from + len), 4)} | ${f(mean(x, from, from + len), 4)} | $pitchCol | ${h.joinToString(" ") { f(it, 1) }} | ${f(slopeDbPerOct(h), 1)} | ${f(ratio)} / ${f(stick)} | ${f(slips, 1)} | ${cellStr(classify(x, from, len, p.f0))} |")
    }

    // ---------------------------------------------------------------- the spike

    @Test
    fun spike() {
        line("# BowSpike tables (iteration 5: iteration 4's defaults plus the filter-delay-share probe; RATE $RATE)")
        line("default bridgeHz = ${f(BowSpike.DEFAULT_BRIDGE_HZ, 1)}")
        line("bridgeHz STK-at-176k = ${f(BowSpike.BRIDGE_HZ_STK_AT_176K, 1)} (pole ${f(BowSpike.stkPole(RATE), 4)}); STK-at-44k = ${f(BowSpike.BRIDGE_HZ_STK_AT_44K, 1)} (pole ${f(BowSpike.stkPole(Dsp.RATE), 4)})")
        line("vBow = 0.03 + 0.2*amp; slope = 5 - 4*pressure; offset ${BowSpike.OFFSET}; rho in [${BowSpike.RHO_MIN}, ${BowSpike.RHO_MAX}]; reflection ${BowSpike.REFLECTION}; attack ${BowSpike.ATTACK_S} s; release ${BowSpike.RELEASE_S} s; default beta ${BowSpike.BETA_DEFAULT}")
        line()
        section0Budget()
        section9SideBySide()
        section2Pitch()
        section3Helmholtz()
        section4Schelleng()
        section1Bounded()
        section5Onset()
        section6Position()
        section7Release()
        section10Corners()
        section8Cost()
        flush()
        assertTrue(true)
    }

    private fun section0Budget() {
        line("## 0. Budget at defaults (filterDelay from Strings.tune vs the closed form; segment lengths)")
        line("| f0 | bridgeHz | filterDelay tune | closed form | D | segA (beta*D) n/frac/a | segB n/frac/a | B full exact | B dc(f0/25) lead | B half exact |")
        line("|---|---|---|---|---|---|---|---|---|---|")
        for (f0 in listOf(E1, C2, C3, 220f, 440f, 880f)) {
            val p = Params(f0)
            val b = BowSpike.budgetA(p)
            val sa = BowSpike.Seg(b.segA); val sb = BowSpike.Seg(b.segB)
            val bf = BowSpike.budgetB(p, BVariant.B_FULL_POS)
            val bd = BowSpike.budgetB(p, BVariant.B_FULL_POS_DC)
            val bh = BowSpike.budgetB(p, BVariant.B_HALF_NEG)
            line("| $f0 | ${f(p.bridgeHz, 1)} | ${f(b.filterDelay, 4)} | ${f(BowSpike.onePoleDelayClosedForm(f0, p.bridgeHz, RATE), 4)} | ${f(b.d, 3)} | ${f(b.segA, 3)} ${sa.n}/${f(sa.frac, 3)}/${f(sa.a, 3)} | ${f(b.segB, 3)} ${sb.n}/${f(sb.frac, 3)}/${f(sb.a, 3)} | ${f(bf.exact, 3)} | ${f(bd.dcDelay, 3)} | ${f(bh.exact, 3)} |")
        }
        line()
        flush()
    }

    private val rowHeader = "| model | max abs | nonfinite | steady peak | steady RMS | steady mean | acPitch Hz (cents, ac) | h1..h10 dB re h1 | slope dB/oct | asym ratio / stickFrac | slips/period | regime |"
    private val rowSep = "|---|---|---|---|---|---|---|---|---|---|---|---|"

    private fun section9SideBySide() {
        line("## 9. Side by side at defaults (pressure 0.5, amp 0.5, beta 0.127, 1.0 s; steady = last 300 ms before release)")
        for (f0 in listOf(C3, 220f)) {
            line("### f0 = $f0")
            line(rowHeader); line(rowSep)
            val p = Params(f0)
            renderRow("A two-seg", BowSpike.renderABoth(p), p)
            for (v in BVariant.entries) renderRow("B ${v.name} (${v.note})", BowSpike.renderBBoth(p, v), p)
            renderRow("B on Strings.Loop (fb +0.95, lag charged)", BowSpike.renderBOnLoop(p).let { BowSpike.Out(it, it) }, p)
            line()
            line("Bow-point velocity tap (vString + vNew): Helmholtz motion is a rectangular wave sticking for 1 - beta of the period")
            line(rowHeader); line(rowSep)
            val pb = Params(f0, tap = BowSpike.Tap.BOW)
            renderRow("A bow-point", BowSpike.renderABoth(pb), pb)
            renderRow("B_FULL_POS_DC bow-point", BowSpike.renderBBoth(pb, BVariant.B_FULL_POS_DC), pb)
            renderRow("B_HALF_NEG bow-point", BowSpike.renderBBoth(pb, BVariant.B_HALF_NEG), pb)
            line()
        }
        line("### Iteration probe: B_FULL_POS DC blocker corner (fraction of f0) at 130.81 and 220 Hz")
        line(rowHeader); line(rowSep)
        for (f0 in listOf(C3, 220f)) for (r in listOf(1f / 10f, 1f / 25f, 1f / 50f, 1f / 100f, 1f / 200f)) {
            val p = Params(f0, dcHzOverF0 = r)
            renderRow("B_FULL_POS_DC f0/${(1f / r).roundToInt()} @ $f0 (lead ${f(BowSpike.budgetB(p, BVariant.B_FULL_POS_DC).dcDelay, 2)} smp)", BowSpike.renderBBoth(p, BVariant.B_FULL_POS_DC), p)
        }
        line()
        line("### Iteration 4 probe: loss x force map, model A at 130.81, amp 0.5, beta 0.127 - bridge corner (rows) x pressure (cols): slips/period, h-slope dB/oct, pitch cents, steady peak")
        line("| bridgeHz | |H(f0)| | filterDelay | " + listOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f).joinToString(" | ") { "p $it" } + " |")
        line("|---|---|---|---|---|---|---|---|")
        for (hz in listOf(1000f, 1500f, 2000f, 3023.6f, 4500f, 6000f, 9028.4f)) {
            val cells = listOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f).map { pr ->
                val p = Params(C3, pressure = pr, bridgeHz = hz)
                val o = BowSpike.renderABoth(p)
                val (from, len) = steadyWindow(p)
                val (hzm, ac) = acPitch(o.bridge, from, len, C3)
                if (ac < 0.3) "no f0 (ac ${f(ac)})" else {
                    val h = harmonics(o.bridge, from, len, hzm)
                    "${f(slipsPerPeriod(o.bow, from + len, RATE / hzm, p.vMax), 1)} / ${f(slopeDbPerOct(h), 1)} / ${f(cents(hzm, C3.toDouble()), 1)} c / ${f(maxAbs(o.bridge, from, from + len), 2)}"
                }
            }
            line("| ${f(hz, 0)} | ${f(BowSpike.onePoleMag(C3, hz, RATE), 5)} | ${f(BowSpike.onePoleDelay(C3, hz, RATE), 2)} | ${cells.joinToString(" | ")} |")
        }
        line("Same map at amp 0.8 (vBow 0.19):")
        line("| bridgeHz | " + listOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f).joinToString(" | ") { "p $it" } + " |")
        line("|---|---|---|---|---|---|")
        for (hz in listOf(1500f, 3023.6f, 6000f)) {
            val cells = listOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f).map { pr ->
                val p = Params(C3, pressure = pr, amplitude = 0.8f, bridgeHz = hz)
                val o = BowSpike.renderABoth(p)
                val (from, len) = steadyWindow(p)
                val (hzm, ac) = acPitch(o.bridge, from, len, C3)
                if (ac < 0.3) "no f0 (ac ${f(ac)})" else {
                    val h = harmonics(o.bridge, from, len, hzm)
                    "${f(slipsPerPeriod(o.bow, from + len, RATE / hzm, p.vMax), 1)} / ${f(slopeDbPerOct(h), 1)} / ${f(cents(hzm, C3.toDouble()), 1)} c / ${f(maxAbs(o.bridge, from, from + len), 2)}"
                }
            }
            line("| ${f(hz, 0)} | ${cells.joinToString(" | ")} |")
        }
        line("Same map at 220 Hz, amp 0.5:")
        line("| bridgeHz | " + listOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f).joinToString(" | ") { "p $it" } + " |")
        line("|---|---|---|---|---|---|")
        for (hz in listOf(1500f, 3023.6f, 6000f)) {
            val cells = listOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f).map { pr ->
                val p = Params(220f, pressure = pr, bridgeHz = hz)
                val o = BowSpike.renderABoth(p)
                val (from, len) = steadyWindow(p)
                val (hzm, ac) = acPitch(o.bridge, from, len, 220f)
                if (ac < 0.3) "no f0 (ac ${f(ac)})" else {
                    val h = harmonics(o.bridge, from, len, hzm)
                    "${f(slipsPerPeriod(o.bow, from + len, RATE / hzm, p.vMax), 1)} / ${f(slopeDbPerOct(h), 1)} / ${f(cents(hzm, 220.0), 1)} c / ${f(maxAbs(o.bridge, from, from + len), 2)}"
                }
            }
            line("| ${f(hz, 0)} | ${cells.joinToString(" | ")} |")
        }
        line()
        line("### Iteration 5 probe: filter-delay share in A's budget (1.0 = Strings.tune's rule) - raw acPitch cents at pressure 0.5 / 0.9, bridge 3023")
        line("| share | 65.41 | 130.81 | 220 | 440 | 880 | residual samples at 880 (p0.9) |")
        line("|---|---|---|---|---|---|---|")
        for (share in listOf(1.0f, 0.9f, 0.85f, 0.8f, 0.7f)) {
            val cells = listOf(C2, C3, 220f, 440f, 880f).map { f0 ->
                listOf(0.5f, 0.9f).map { pr ->
                    val p = Params(f0, pressure = pr, filterDelayShare = share)
                    val x = BowSpike.renderA(p)
                    val (from, len) = steadyWindow(p)
                    val (hz, ac) = acPitch(x, from, len, f0)
                    if (ac < 0.3) "no f0" else f(cents(hz, f0.toDouble()), 1)
                }.joinToString(" / ")
            }
            val p880 = Params(880f, pressure = 0.9f, filterDelayShare = share)
            val x880 = BowSpike.renderA(p880)
            val (from, len) = steadyWindow(p880)
            val (hz880, _) = acPitch(x880, from, len, 880f)
            val residual = RATE / 880.0 - RATE / hz880
            line("| $share | ${cells.joinToString(" | ")} | ${f(residual, 2)} |")
        }
        line()
        line("### Iteration probe: bridge filter at STK's 44.1 kHz pole (${f(BowSpike.BRIDGE_HZ_STK_AT_44K, 0)} Hz) for A and B_FULL_POS_DC")
        line(rowHeader); line(rowSep)
        for (f0 in listOf(C3, 220f)) {
            val p = Params(f0, bridgeHz = BowSpike.BRIDGE_HZ_STK_AT_44K)
            renderRow("A bridge 3023 @ $f0", BowSpike.renderABoth(p), p)
            renderRow("A bridge 9028 @ $f0", BowSpike.renderABoth(Params(f0, bridgeHz = BowSpike.BRIDGE_HZ_STK_AT_176K)), Params(f0, bridgeHz = BowSpike.BRIDGE_HZ_STK_AT_176K))
            renderRow("B_FULL_POS_DC bridge 3023 @ $f0", BowSpike.renderBBoth(p, BVariant.B_FULL_POS_DC), p)
            val pb = Params(f0, bridgeHz = BowSpike.BRIDGE_HZ_STK_AT_44K, tap = BowSpike.Tap.BOW)
            renderRow("A bridge 3023 bow-point @ $f0", BowSpike.renderABoth(pb), pb)
        }
        line()
        flush()
    }

    private fun section2Pitch() {
        line("## 2. Pitch at defaults: acPitch on raw 176.4k, FineTuning.measuredHz on the core Snip (44.1k), Pitch.detect on the finished Snip")
        line("| model | f0 | raw acPitch Hz | cents | ac | FineTuning Hz | cents | Pitch.detect Hz (conf) | cents | finished peak | finished Loudness |")
        line("|---|---|---|---|---|---|---|---|---|---|---|")
        val models = listOf<Pair<String, (Params) -> FloatArray>>(
            "A" to { p -> BowSpike.renderA(p) },
            "A p0.9" to { p -> BowSpike.renderA(Params(p.f0, pressure = 0.9f)) },
            "B_FULL_POS_DC" to { p -> BowSpike.renderB(p, BVariant.B_FULL_POS_DC) },
            "B_HALF_NEG" to { p -> BowSpike.renderB(p, BVariant.B_HALF_NEG) },
        )
        for ((name, render) in models) for (f0 in listOf(C2, C3, 220f, 440f, 880f)) {
            val p = Params(f0)
            val x = render(p)
            val (from, len) = steadyWindow(p)
            val (hz, ac) = acPitch(x, from, len, f0)
            val core = coreSnip(x)
            val fine = try { FineTuning.measuredHz(core, f0, fromSec = 0.5f, bodySeconds = 0.4f) } catch (e: Exception) { Double.NaN }
            val fin = finishedSnip(x)
            val pd = Pitch.detect(fin, fromSec = 0.5f, windowSec = 0.4f)
            val pdHz = pd?.hz?.toDouble() ?: Double.NaN
            line("| $name | $f0 | ${f(hz, 3)} | ${f(cents(hz, f0.toDouble()), 1)} | ${f(ac)} | ${f(fine, 3)} | ${f(cents(fine, f0.toDouble()), 1)} | ${if (pd == null) "null" else "${f(pdHz, 2)} (${f(pd.confidence)})"} | ${if (pd == null) "-" else f(cents(pdHz, f0.toDouble()), 1)} | ${f(maxAbs(fin.samples), 3)} | ${f(Loudness.of(fin), 4)} |")
        }
        line()
        flush()
    }

    private fun section3Helmholtz() {
        line("## 3. Helmholtz signature at defaults (last 200 ms; harmonics at k * measured f0)")
        line("| model | f0 | h1..h10 dB re h1 | slope dB/oct | asym ratio | stickFrac | centroid Hz (raw, steady) |")
        line("|---|---|---|---|---|---|---|")
        val models = listOf<Pair<String, (Params) -> FloatArray>>(
            "A" to { p -> BowSpike.renderA(p) },
            "A p0.9" to { p -> BowSpike.renderA(Params(p.f0, pressure = 0.9f)) },
            "B_FULL_POS_DC" to { p -> BowSpike.renderB(p, BVariant.B_FULL_POS_DC) },
            "B_HALF_NEG" to { p -> BowSpike.renderB(p, BVariant.B_HALF_NEG) },
            "B_FULL_NEG" to { p -> BowSpike.renderB(p, BVariant.B_FULL_NEG) },
        )
        for ((name, render) in models) for (f0 in listOf(C3, 220f)) {
            val p = Params(f0)
            val x = render(p)
            val (from, len) = steadyWindow(p, 0.2f)
            val (hz, _) = acPitch(x, from, len, f0)
            val h = harmonics(x, from, len, hz)
            val (ratio, stick) = asymmetry(x, from + len, RATE / hz)
            line("| $name | $f0 | ${h.joinToString(" ") { f(it, 1) }} | ${f(slopeDbPerOct(h), 2)} | ${f(ratio, 3)} | ${f(stick, 3)} | ${f(centroid(x, from), 0)} |")
        }
        line()
        flush()
    }

    private fun section4Schelleng() {
        line("## 4. Schelleng rows at 130.81 Hz, beta 0.127: pressure 0..1 step 0.05, last 300 ms of a 1.0 s note")
        val pressures = (0..20).map { it * 0.05f }
        val models = listOf<Pair<String, (Params) -> BowSpike.Out>>(
            "A" to { p -> BowSpike.renderABoth(p) },
            "B_FULL_POS_DC" to { p -> BowSpike.renderBBoth(p, BVariant.B_FULL_POS_DC) },
            "B_HALF_NEG" to { p -> BowSpike.renderBBoth(p, BVariant.B_HALF_NEG) },
        )
        for ((name, render) in models) for (amp in listOf(0.5f, 0.2f, 0.8f)) {
            line("### $name, amplitude $amp (vBow ${f(BowSpike.vBowFor(amp), 3)})")
            line("| pressure | slope | regime | steady peak | h2 re h1 dB | slips/period | stickFrac (bow tap) |")
            line("|---|---|---|---|---|---|---|")
            for (pr in pressures) {
                val p = Params(C3, pressure = pr, amplitude = amp)
                val o = render(p)
                val x = o.bridge
                val (from, len) = steadyWindow(p)
                val c = classify(x, from, len, C3)
                val (hz, ac) = acPitch(x, from, len, C3)
                val speaks = ac >= 0.3 && c.regime != Regime.DC
                val h2 = if (speaks) db(peakPower(x, from, len, 2 * hz), peakPower(x, from, len, hz)) else Double.NaN
                val slips = if (speaks) slipsPerPeriod(o.bow, from + len, RATE / hz, p.vMax) else Double.NaN
                val stick = if (speaks) asymmetry(o.bow, from + len, RATE / hz).second else Double.NaN
                line("| ${f(pr)} | ${f(p.slope, 1)} | ${cellStr(c)} | ${f(maxAbs(x, from, from + len), 3)} | ${f(h2, 1)} | ${f(slips, 1)} | ${f(stick)} |")
            }
            line()
            flush()
        }
    }

    private fun section1Bounded() {
        line("## 1. Boundedness at 130.81 Hz, 1.5 s: max|x| / finite / steady peak (last 300 ms before release)")
        line("### Model A: rows pressure x amplitude, columns beta")
        val betas = listOf(0.08f, 0.127f, 0.2f, 0.3f)
        line("| pressure | amp | " + betas.joinToString(" | ") { "beta $it" } + " |")
        line("|---|---|" + betas.joinToString("") { "---|" })
        for (pr in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) for (amp in listOf(0.2f, 0.5f, 0.8f)) {
            val cells = betas.map { beta ->
                val p = Params(C3, pressure = pr, amplitude = amp, beta = beta, seconds = 1.5f)
                val x = BowSpike.renderA(p)
                val (from, len) = steadyWindow(p)
                "${f(maxAbs(x), 3)} / ${if (nonFinite(x) == 0) "finite" else "NONFINITE ${nonFinite(x)}"} / ${f(maxAbs(x, from, from + len), 3)}"
            }
            line("| $pr | $amp | ${cells.joinToString(" | ")} |")
        }
        line()
        for (v in listOf(BVariant.B_FULL_POS_DC, BVariant.B_HALF_NEG)) {
            line("### Model B ${v.name}: rows pressure, columns amplitude")
            line("| pressure | amp 0.2 | amp 0.5 | amp 0.8 |")
            line("|---|---|---|---|")
            for (pr in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
                val cells = listOf(0.2f, 0.5f, 0.8f).map { amp ->
                    val p = Params(C3, pressure = pr, amplitude = amp, seconds = 1.5f)
                    val x = BowSpike.renderB(p, v)
                    val (from, len) = steadyWindow(p)
                    "${f(maxAbs(x), 3)} / ${if (nonFinite(x) == 0) "finite" else "NONFINITE ${nonFinite(x)}"} / ${f(maxAbs(x, from, from + len), 3)}"
                }
                line("| $pr | ${cells.joinToString(" | ")} |")
            }
            line()
        }
        flush()
    }

    /** Time (ms) from t = 0 to the first 5 ms window whose RMS reaches 90 % of the steady RMS (mean over 0.6-0.9 s). */
    private fun onsetMs(x: FloatArray, p: Params): String {
        val win = (0.005f * RATE).toInt()
        val steady = rms(x, (0.6f * RATE).toInt(), (0.9f * RATE).toInt())
        if (steady < 1e-4) return "no steady state (rms ${f(steady, 5)})"
        var start = 0
        var t50 = "never"
        while (start + win <= p.releaseStart) {
            val r = rms(x, start, start + win)
            if (t50 == "never" && r >= 0.5 * steady) t50 = f((start + win) * 1000.0 / RATE, 0)
            if (r >= 0.9 * steady) return "${f((start + win) * 1000.0 / RATE, 0)} ms to 90 % ($t50 ms to 50 %; steady rms ${f(steady, 4)})"
            start += win
        }
        return "never ($t50 ms to 50 %)"
    }

    private fun section5Onset() {
        line("## 5. Onset: time to 90 % of steady RMS (5 ms windows), 20 ms attack")
        line("| model | f0 | pressure 0.3 | pressure 0.5 | pressure 0.8 | pressure 0.9 |")
        line("|---|---|---|---|---|---|")
        val models = listOf<Pair<String, (Params) -> FloatArray>>(
            "A" to { p -> BowSpike.renderA(p) },
            "B_FULL_POS_DC" to { p -> BowSpike.renderB(p, BVariant.B_FULL_POS_DC) },
            "B_HALF_NEG" to { p -> BowSpike.renderB(p, BVariant.B_HALF_NEG) },
        )
        for ((name, render) in models) for (f0 in listOf(C2, 220f)) {
            val cells = listOf(0.3f, 0.5f, 0.8f, 0.9f).map { pr -> val p = Params(f0, pressure = pr); onsetMs(render(p), p) }
            line("| $name | $f0 | ${cells.joinToString(" | ")} |")
        }
        line()
        flush()
    }

    private fun section6Position() {
        line("## 6. Bow position, model A at 220 Hz defaults: centroid of the raw steady signal, FeatureExtractor centroid of the core Snip's steady 4096, h1..h8")
        for (pr in listOf(0.5f, 0.9f)) {
        line("pressure $pr")
        line("| beta | segA samples | raw centroid Hz | Features centroid Hz | rolloff Hz | acPitch cents | h1..h8 dB re h1 | slope dB/oct | slips/period |")
        line("|---|---|---|---|---|---|---|---|---|")
        for (beta in listOf(0.05f, 0.08f, 0.127f, 0.2f, 0.3f, 0.4f)) {
            val p = Params(220f, beta = beta, pressure = pr)
            val o = BowSpike.renderABoth(p)
            val x = o.bridge
            val (from, len) = steadyWindow(p, 0.2f)
            val (hz, _) = acPitch(x, from, len, 220f)
            val h = harmonics(x, from, len, hz, 8)
            val core = coreSnip(x)
            val cf = (0.6f * Dsp.RATE).toInt()
            val steadyCore = Snip(core.samples.copyOfRange(cf, min(core.samples.size, cf + 4096)), 1, Dsp.RATE)
            val feat = FeatureExtractor.extract(steadyCore)
            line("| $beta | ${f(BowSpike.budgetA(p).segA, 2)} | ${f(centroid(x, from), 0)} | ${f(feat.centroidHz, 0)} | ${f(feat.rolloffHz, 0)} | ${f(cents(hz, 220.0), 1)} | ${h.joinToString(" ") { f(it, 1) }} | ${f(slopeDbPerOct(h), 2)} | ${f(slipsPerPeriod(o.bow, from + len, RATE / hz, p.vMax), 1)} |")
        }
        line()
        }
        flush()
    }

    private fun section7Release() {
        line("## 7. Release at 130.81 Hz defaults: 1.0 s hold, 50 ms ramp to 0, ring-down t60 from 20 ms RMS windows (reference = the window before the release)")
        val mag = BowSpike.onePoleMag(C3, BowSpike.DEFAULT_BRIDGE_HZ, RATE)
        val perTrip = 20.0 * log10(BowSpike.REFLECTION * mag)
        val formula = -60.0 / perTrip * (1000.0 / C3)
        line("formula: |H(f0)| = ${f(mag, 5)}, per-trip loss ${f(perTrip, 4)} dB, t60 = ${f(formula, 1)} ms (model A: both segments, one bridge filter per period)")
        val magHalf = perTrip * 2
        line("model B_HALF_NEG has two bridge passes per period: t60 = ${f(-60.0 / magHalf * (1000.0 / C3), 1)} ms")
        line("| model | rms before release | t60 ms (measured) | windows: t(ms) -> dB re ref |")
        line("|---|---|---|---|")
        val models = listOf<Pair<String, (Params) -> FloatArray>>(
            "A" to { p -> BowSpike.renderA(p) },
            "B_FULL_POS_DC" to { p -> BowSpike.renderB(p, BVariant.B_FULL_POS_DC) },
            "B_HALF_NEG" to { p -> BowSpike.renderB(p, BVariant.B_HALF_NEG) },
        )
        for ((name0, render) in models) for (lift in listOf(true, false)) {
            val name = "$name0 ${if (lift) "bow lifted after the ramp" else "bow resting (vBow 0, table active)"}"
            val p = Params(C3, seconds = 3.0f, releaseAt = 1.0f, lift = lift)
            val x = render(p)
            val win = (0.020f * RATE).toInt()
            val rs = p.releaseStart
            val ref = rms(x, rs - win, rs)
            val trace = StringBuilder()
            var t60 = "not reached"
            var k = 0
            while (rs + (k + 1) * win <= x.size) {
                val r = rms(x, rs + k * win, rs + (k + 1) * win)
                val d = 20 * log10(max(r, 1e-12) / max(ref, 1e-12))
                if (k % 5 == 0 && k <= 60) trace.append("${(k + 1) * 20}->${f(d, 1)} ")
                if (d <= -60.0 && t60 == "not reached") t60 = "${(k + 1) * 20}"
                k++
            }
            line("| $name | ${f(ref, 4)} | $t60 | $trace |")
        }
        line()
        flush()
    }

    private fun section10Corners() {
        line("## 10. Robustness corners (steady = last 300 ms before release)")
        line(rowHeader); line(rowSep)
        run {
            val p = Params(C3, pressure = 1.0f, amplitude = 1.0f, seconds = 1.5f)
            renderRow("A p1.0 a1.0 (HEAVY CRUNCH) 130.81", BowSpike.renderABoth(p), p)
            renderRow("B_FULL_POS_DC p1.0 a1.0", BowSpike.renderBBoth(p, BVariant.B_FULL_POS_DC), p)
            renderRow("B_HALF_NEG p1.0 a1.0", BowSpike.renderBBoth(p, BVariant.B_HALF_NEG), p)
            val pl = Params(C3, pressure = 1.0f, amplitude = 1.0f, seconds = 1.5f, rhoMax = 1.0f)
            renderRow("A p1.0 a1.0 rhoMax 1.0", BowSpike.renderABoth(pl), pl)
        }
        run {
            val p = Params(C3, vBowAbs = 0f)
            val x = BowSpike.renderA(p)
            line("| A vBow 0 (bow lifted) | ${f(maxAbs(x), 6)} | ${nonFinite(x)} | - | ${f(rms(x, 0, x.size), 6)} | ${f(mean(x, 0, x.size), 6)} | - | - | - | - | - | - |")
            val xb = BowSpike.renderB(p, BVariant.B_FULL_POS_DC)
            line("| B_FULL_POS_DC vBow 0 | ${f(maxAbs(xb), 6)} | ${nonFinite(xb)} | - | ${f(rms(xb, 0, xb.size), 6)} | ${f(mean(xb, 0, xb.size), 6)} | - | - | - | - | - | - |")
        }
        run {
            val p = Params(E1, seconds = 1.5f)
            val b = BowSpike.budgetA(p)
            line("E1 41.2 Hz budget: D ${f(b.d, 2)}, segA ${f(b.segA, 2)}, segB ${f(b.segB, 2)}")
            val o = BowSpike.renderABoth(p)
            renderRow("A 41.2 Hz defaults", o, p)
            line("onset ${onsetMs(o.bridge, p)}")
            renderRow("B_FULL_POS_DC 41.2 Hz", BowSpike.renderBBoth(p, BVariant.B_FULL_POS_DC), p)
            renderRow("B_HALF_NEG 41.2 Hz", BowSpike.renderBBoth(p, BVariant.B_HALF_NEG), p)
        }
        run {
            line("### Bridge filter alternative: STK's pole at 44.1 kHz (${f(BowSpike.BRIDGE_HZ_STK_AT_44K, 0)} Hz) instead of at 176.4 kHz (${f(BowSpike.BRIDGE_HZ_STK_AT_176K, 0)} Hz), model A defaults")
            line(rowHeader); line(rowSep)
            for (f0 in listOf(C3, 220f)) {
                val p = Params(f0, bridgeHz = BowSpike.BRIDGE_HZ_STK_AT_44K)
                renderRow("A bridge ${f(p.bridgeHz, 0)} Hz, $f0", BowSpike.renderABoth(p), p)
            }
            line("### Noise on vBow (1 %) - does it change the regime at defaults?")
            line(rowHeader); line(rowSep)
            val p = Params(C3, noise = 0.01f)
            renderRow("A noise 1 %", BowSpike.renderABoth(p), p)
            line("### Iteration 3 probes at 130.81: rhoMax 1.0 (a bow that truly sticks); harder pressure; STK's own 44.1 kHz filter with hard pressure")
            line(rowHeader); line(rowSep)
            renderRow("A rhoMax 1.0 defaults", BowSpike.renderABoth(Params(C3, rhoMax = 1.0f)), Params(C3, rhoMax = 1.0f))
            renderRow("A pressure 0.9", BowSpike.renderABoth(Params(C3, pressure = 0.9f)), Params(C3, pressure = 0.9f))
            renderRow("A pressure 0.9 amp 0.2", BowSpike.renderABoth(Params(C3, pressure = 0.9f, amplitude = 0.2f)), Params(C3, pressure = 0.9f, amplitude = 0.2f))
            renderRow("A pressure 0.9 bridge 3023", BowSpike.renderABoth(Params(C3, pressure = 0.9f, bridgeHz = BowSpike.BRIDGE_HZ_STK_AT_44K)), Params(C3, pressure = 0.9f, bridgeHz = BowSpike.BRIDGE_HZ_STK_AT_44K))
            renderRow("A pressure 0.9 rhoMax 1.0 bridge 3023", BowSpike.renderABoth(Params(C3, pressure = 0.9f, rhoMax = 1.0f, bridgeHz = BowSpike.BRIDGE_HZ_STK_AT_44K)), Params(C3, pressure = 0.9f, rhoMax = 1.0f, bridgeHz = BowSpike.BRIDGE_HZ_STK_AT_44K))
            renderRow("A beta 0.2 pressure 0.9", BowSpike.renderABoth(Params(C3, pressure = 0.9f, beta = 0.2f)), Params(C3, pressure = 0.9f, beta = 0.2f))
        }
        line()
        flush()
    }

    private fun section8Cost() {
        line("## 8. Cost: ms per rendered second at 176.4 kHz (1.0 s at 130.81 defaults; median of 3 after 2 warm-ups)")
        line("| model | run 1 | run 2 | run 3 | median ms/s |")
        line("|---|---|---|---|---|")
        val models = listOf<Pair<String, (Params) -> FloatArray>>(
            "A two-seg" to { p -> BowSpike.renderA(p) },
            "B_FULL_POS_DC" to { p -> BowSpike.renderB(p, BVariant.B_FULL_POS_DC) },
            "B_HALF_NEG" to { p -> BowSpike.renderB(p, BVariant.B_HALF_NEG) },
            "B on Strings.Loop" to { p -> BowSpike.renderBOnLoop(p) },
        )
        for ((name, render) in models) {
            val p = Params(C3, seconds = 1.0f)
            repeat(2) { render(p) }
            val t = (1..3).map {
                val t0 = System.nanoTime(); render(p); (System.nanoTime() - t0) / 1e6
            }
            line("| $name | ${t.joinToString(" | ") { f(it, 1) }} | ${f(t.sorted()[1], 1)} |")
        }
        line()
        flush()
    }
}
```

## Appendix D — the probe's report: the specification's engine, as written

**Tag:** empirical. **Tree:** worktree `/home/user/SnipSnap/.claude/worktrees/wf_17fb7c2a-cfb-1` at `b8b8557`, three untracked test-source files, nothing committed, main checkout untouched.
**Files:** `synth/src/test/kotlin/com/snipsnap/synth/ArcoProbeV1.kt` (281 lines, spec part 1's `Arco.kt`), `ArcoProbeV2.kt` (332 lines, part 2's `SolinaEnsemble.kt` + `Arco.kt`), `ArcoProbeTest.kt` (635 lines, eight print-only tests, the only assertion is `assertTrue(true)`). Copies, the two gradle logs, both runs' tables (`empirical-tables-run1.txt`, `empirical-tables.txt`) and the JUnit XML are in `the session's scratchpad/`.
**Command:** `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.ArcoProbeTest'` — run 1 (fresh worktree, :json/:audio/:synth/:kit/:xpm/:mpc3 compiled from scratch): exit 0, 2 m 48 s wall, the suite itself 98.6 s; run 2 (after adding measurements): exit 0, 2 m 04 s wall, tests B 42.6 s / C 44.2 s / E 7.4 s / G 2.9 s / H ~4 s / D 1.0 s / F 0.5 s / A 0.01 s. Run 2 reproduced every number of run 1 outside the timing table (a `diff` of the first five columns of every table row: only the cost rows and the two rewritten sections differ) — the render is deterministic. Compiler warnings on the probe sources: one, the spec's own `private inline fun readInterpolated` ("expected performance impact from inlining is insignificant", ArcoProbeV2.kt:116).

### Headline

Transcribed with three compile-driven changes (C1–C3 below) and rendered at 176.4 kHz, the spec's engine is **finite everywhere** — no NaN, no overflow, in 140 renders across five voices, two versions and fourteen macro corners each — and **plays no note.** The stick-slip loop never enters Helmholtz motion: at every voice, every TUNE and every corner the raw loop's 200 ms before release has a zero-crossing rate of 12–24 kHz, the house `Pitch.detect` returns `null` (or an octave-below artefact at ROSIN 0), the global FFT peak wanders (563 Hz to 6.5 kHz on a 65–1175 Hz note), the six "harmonics" at k·f0 sit within ±7 dB of each other (slope −2.6 to +4.8 dB/oct where a sawtooth would be −6), and the autocorrelation at one loop period is −0.17 to −0.21. What the loop makes is a chaotic orbit of the friction map with a 46 % DC offset (raw mean 0.884 on a 1.917 peak at defaults; 0.56 at BOW 1) — the map `x → μ(v_bow − x) + 0.985·x` has **no fixed point** (test H2 scans [−3, 6]) and its scalar iteration from 0 reproduces the raw loop's peak to three decimals (1.926 / 2.477 / 2.877 for defaults / BOW 1 / BOW 1+ROSIN 1 against the rendered 1.917 / 2.477 / 2.877). The finished Snips carry that DC into the WAV (mean +0.44 on a 0.95 peak for CELLO and CONTRABASS) because nothing in the chain removes it. Where a pitch *is* audible it is a fixed body resonance, not TUNE: ERHU's finished render peaks at 292–293 Hz at TUNE 0, 0.5 and 1 (the `Modes.ring` fundamental hard-coded at 293 Hz), SARANGI's at 129–130 Hz (hard-coded 130). The only in-tune line in the whole probe is v2's injected sawtooth at SOLINA 1 — at f0 within measurement resolution, −5.8 to −6.2 dB/oct, r(D) = 0.70 — which is an oscillator, not the string; and v2's stereo image, −8 to −12 dB (L−R)/(L+R) out of the ensemble, is collapsed to −43 dB by running the mono-only `Tide.bandLimit` over an interleaved buffer, so the finished v2 Snip is mono to −43 dB and its mono-fold "test" passes at 0.00 dB trivially. The GURDY "drone" is the constant −0.3497 (`Dsp.saw` does not exist; the value the spec computes once outside the loop is `2·(73.41/176400) − 1`). SYMPATHY's tarab strings are seeded at 130.81·ratio and measured at 130.55 / 147.37 / 163.52 / 174.28 / 196.49 / 218.02 / 244.94 Hz at TUNE 0, 0.5 and 1 alike, buried 3–36 dB under the chaos; BODY is a no-op on ERHU and SARANGI (rows identical to three decimals) and on v1's GURDY. `drumClassFor` predicts TONAL; the Classifier says PERC (CELLO, CONTRABASS) or SNARE (ERHU, SARANGI, GURDY) at defaults and LOOP at DECAY 1 only because the 3.5 s render trips the 1.5 s length rule (`Classifier.kt:72`).

### The transcription: compile changes

The spec's Kotlin compiled at the first gradle run with exactly these edits (each marked `// C<n>:` in the source):

| # | Where | Change | Why |
|---|---|---|---|
| C1 | `ArcoPatch` (both parts) | `data class ArcoPatchV1(...)` without `: Patch` and without `override`; the `init` block's `Patches.validateMacros(this, …)` replaced by the same three checks inlined | `Patch` is a **sealed** interface (`Patches.kt:14`) and Kotlin requires a sealed type's direct subtypes in the same module and package; the test source set is a separate compilation. `validateMacros` takes a `Patch` (`Patches.kt:94`), so it cannot be called on the un-sealed copy; its body (`:95-99`) is three `require`s, copied. In `synth/src/main` the class would compile as written — it is `TerraPatch.kt` to the letter — and `Patches.fromJsonValue`'s exhaustive `when` (`:36-57`) would then need its arm. Only v1's patch was transcribed; v2's differs in one macro name. |
| C2 | `Arco.render` (both) | the body moved into `renderDebug(...)` returning the raw loop buffer, a copy of the body buffer taken before `Tide.bandLimit`/`normalizeByFold` (v2: also the stereo ensemble before them), and the Snip; `render()` returns `renderDebug(...).snip` | the probe's "measure the core at native rate" requirement; no arithmetic changed. |
| C3 | `applyAcousticBody`, GURDY (both) | `Dsp.saw(...)` → a private `saw(phase: Double)` copied verbatim from `Fathom.kt:140` | **`Dsp.saw` does not exist.** `Dsp.kt` has `square` (`:180`) and nothing else oscillator-shaped; four engines each carry their own private `saw` of the same one line (`Fathom.kt:140`, `Resin.kt:146`, `ResinDrone.kt:94`, `Velvet.kt:118`). The spec calls it **once, outside the per-sample loop**, with the constant `73.41 / 176400`, so `droneWave` is the constant −0.99917 whichever saw is used. |

Everything else resolved as the spec wrote it, which is worth recording: `Pluck.ks` is `internal` with exactly the named parameters the spec uses (`freq, seconds, damp, bodyLoopHz, pickHz, seed, rate` — `Pluck.kt:559-570`; the three trailing defaults `position`, `stiffness`, `jawari` pass their `require`s at 0); `Tide.bandLimit(buf, rate)` is `internal` (`Tide.kt:533`) — and mono-only: one `Dsp.TptSvf` state per stage walked over `buf.indices` (`:534-540`), which is what collapses v2's stereo; `Modes.ring(excitation, fundamentalHz, modes, rate)` matches the spec's argument order (`Modes.kt:85-90`) and `Modes.resample(material, slots)` is `internal` (`:282`); `Punch.applyOversampled(raw, amount, rate, channels)` accepts the spec's mixed named/positional call (`Punch.kt:270`); `Snip(samples, channels = 2, sampleRate)` is legal (`Cleanup.kt:12-20`); `Dsp.Biquad.bandpass(f0, q, rate)` and `.peaking(f0, gainDb, q, rate)` (`Dsp.kt:374`, `:396`), `Dsp.OnePole(rate).lp(x, hz)` (`:414-419`), `Dsp.Noise` (`:171`), `Dsp.seedFor` (`:147`), `Dsp.normalizeByFold(buf, channels)` (`:560`), `Dsp.normalize(buf, target)` (`:510`), `Dsp.limitPeak` (`:654`), `Dsp.fadeTail(buf, ms, rate, channels)` (`:710`), `MacroSpec(name, default, neutral)` (`Thump.kt:33`), `DrumClass.LOOP/TONAL` (`Classifier.kt:8-19`). The spec's `import com.snipsnap.synth.Dsp.RATE` resolves because `Dsp` is `internal` in the same module. `val prng = Dsp.Noise(...)` and v1's `val t` are declared and never read (the "turbulence injection" of §3's BOW row has no code); `const val TWO_PI = 2.0 * Math.PI` compiled.

### What was measured, and how

All at the render rate 176 400 Hz unless said otherwise. "Raw" is the loop's own output before the body; "body" is `applyAcousticBody`'s output before `Tide.bandLimit`/`normalizeByFold`/`Punch`; "Snip" is the finished 44.1 kHz clip (v2: its mono fold via `Cleanup.toMono` for the mono measures).

- **finite?** — index of the first non-finite sample in raw and body (none found anywhere).
- **raw peak / mean / mean÷peak** — over the whole buffer; body peak / mean likewise.
- **Pitch.detect** — the house autocorrelation detector (`Pitch.kt:37`, `Pitch.detect(Snip(slice, 1, 176400), fromSec 0, windowSec 0.2)`) on the 200 ms ending at the release (`totalFrames − releaseFrames`, so the 50 ms linear release is excluded), mean removed. **zero-crossing** — mean interpolated rising-zero-crossing period over the same slice (ForkTest's idiom, `ForkTest.kt:120`). **FFT peak** — Hann window, zero-padded to 2¹⁸ (0.67 Hz bins), parabolic interpolation, strongest bin in 20 Hz–20 kHz. Cents against `Arco.frequencyFor(voice, tune)`. **agree** — "yes" if Pitch and ZC are within 10 c; "ZC only" when Pitch returned null.
- **r(1) / r(D)** — normalised autocorrelation of the same slice at lag 1 and at lag `round(delaySamples)`. A Helmholtz note would have r(D) near 1.
- **h2…h6** — the strongest Hann-Goertzel within ±3 % of k·fRef over the last 100 ms before release, dB re h1 (the spike's Appendix B idiom); fRef = the FFT peak if it lies within a fifth of f0, else f0 itself (in every row without SOLINA it is f0: the peak was never within a fifth). **slope** — least-squares dB against log₂k. **f0/2** — the same peak search at fRef/2, dB re h1.
- **onset90** — first 5 ms window whose RMS reaches 90 % of the RMS of the middle third of the note, on the raw buffer after a 20 Hz one-pole DC blocker (a plain mean removal reads the DC step as an instant onset; run 1's 2.5 ms values were that).
- **centroid / rolloff** — `FeatureExtractor.extract(snip)` (`Features.kt:57`: the first 4096 samples, 93 ms, of the Snip). **Snip Pitch.detect** — house defaults (0.05 s, 0.25 s). **peak, RMS, mean (DC), Loudness.of** — on the Snip (Loudness folds stereo internally, `Loudness.kt:21`). **Classifier** — `Classifier.classify(features).drumClass` (`Classifier.kt:130`) against `Arco.drumClassFor(voice, macros)`.
- **(L−R)/(L+R)** (v2) — 10·log₁₀ of the difference-channel energy over the sum-channel energy, on the Snip and on the pre-bandLimit stereo ensemble.
- **cost** — `System.nanoTime` around `render()`, one warm-up then the median of three, divided by the Snip's duration.

Defaults: TUNE 0.5, BOW 0.6, ROSIN 0.5, BODY 0.5, DECAY 0.5, SYMPATHY 0.3 (v1) / SOLINA 0 (v2). Constants those produce (test A): muS = lin(ROSIN, 0.6, 1.4) = 1.0, muD 0.25, vBow = lin(BOW, 0.1, 1.5) = 0.94, v0 0.15, loop feedback 0.985, dampingHz = expMap(ROSIN, 3500, 16000) = 7483 Hz, attackFrames = lin(1−ROSIN, 0.02, 0.08)·rate = 8819 (50 ms), releaseFrames 8820 (50 ms), duration expMap(DECAY, 0.25, 3.5) = 0.935 s (DECAY 0: 0.250 s, DECAY 1: 3.500 s). TUNE 0.5 snaps to +12 semitones: CELLO 130.82, CONTRABASS 82.40, ERHU 587.32, SARANGI 261.62, GURDY 293.66 Hz.

### A1 · Analytic loop frequencies (what the loop would play if it oscillated)

`delaySamples = 176400 / f0`, no inversion, feedback 0.985; the one-pole at `dampingHz` inside the loop adds a phase lag of 3.27 samples at f0 (ROSIN 0.5; 7.5 at ROSIN 0, 1.3 at ROSIN 1), unbudgeted, so a note — were there one — would read flat by 1200·log₂(D/(D+lag)):

| voice | TUNE | f0 Hz | D samples | lag @ROSIN 0.5 | predicted Hz | cents | @ROSIN 0 | @ROSIN 1 |
|---|---|---|---|---|---|---|---|---|
| CELLO | 0 / 0.5 / 1 | 65.41 / 130.82 / 261.64 | 2696.8 / 1348.4 / 674.2 | 3.27 | 65.33 / 130.50 / 260.38 | −2.1 / −4.2 / −8.4 | −4.8 / −9.6 / −19.2 | −0.8 / −1.7 / −3.3 |
| CONTRABASS | 0 / 0.5 / 1 | 41.20 / 82.40 / 164.80 | 4281.6 / 2140.8 / 1070.4 | 3.27 | 41.17 / 82.27 / 164.30 | −1.3 / −2.6 / −5.3 | −3.0 / −6.1 / −12.1 | −0.5 / −1.1 / −2.1 |
| ERHU | 0 / 0.5 / 1 | 293.66 / 587.32 / 1174.64 | 600.7 / 300.4 / 150.2 | 3.27 / 3.27 / 3.24 | 292.07 / 581.00 / 1149.81 | −9.4 / −18.7 / −37.0 | −21.5 / −42.5 / −81.6 | −3.7 / −7.5 / −14.9 |
| SARANGI | 0 / 0.5 / 1 | 130.81 / 261.62 / 523.24 | 1348.5 / 674.3 / 337.1 | 3.27 | 130.49 / 260.36 / 518.22 | −4.2 / −8.4 / −16.7 | −9.6 / −19.2 / −38.0 | −1.7 / −3.3 / −6.7 |
| GURDY | 0 / 0.5 / 1 | 146.83 / 293.66 / 587.32 | 1201.4 / 600.7 / 300.4 | 3.27 | 146.43 / 292.07 / 581.00 | −4.7 / −9.4 / −18.7 | −10.8 / −21.5 / −42.5 | −1.9 / −3.7 / −7.5 |

The interpolator: `readD = writeIdx − delaySamples` is negative for the first D samples of every ring cycle (the ring is 2D+64 long), `readD.toInt()` truncates toward zero, so `frac` is negative there and the "interpolation" is a linear *extrapolation* — the same F3 as BORE. It lands at the right delay to first order; it alternates the kernel between a low-pass and a slight high-boost every D samples. Moot, since nothing periodic goes round.

### Per-voice tables

Every row of every table is in `empirical-tables.txt`; the rows below are the complete v1 tables for CELLO (the pattern) and the rows that differ in kind for the others. v2's raw loop is the same code and rendered the same numbers to every printed digit at every corner except SOLINA 1 (the only v2 row where the loop's content changes), so v2's tables are given as their SOLINA rows plus the stereo column.

#### v1 CELLO (root 65.41 Hz; f0 130.82 Hz at TUNE 0.5)

Table A — the core:

| config | finite? | raw peak | raw mean | mean/peak | body peak | body mean | Pitch.detect Hz (c) | zero-crossing Hz (c) | FFT peak Hz (c) | agree | r(1) / r(D) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| defaults | yes | 1.917 | 0.884 | 0.46 | 1.438 | 0.663 | null | 12064 (+7832) | 1784.2 (+4524) | ZC only | 0.90 / −0.17 |
| TUNE 0 | yes | 1.926 | 0.894 | 0.46 | 1.444 | 0.670 | null | 13721 (+9255) | 563.6 (+3729) | ZC only | 0.88 / −0.20 |
| TUNE 1 | yes | 1.833 | 0.880 | 0.48 | 1.375 | 0.660 | null | 12147 (+6644) | 1167.0 (+2589) | ZC only | 0.90 / −0.19 |
| BOW 0 | yes | 1.094 | 0.094 | 0.09 | 0.820 | 0.071 | null | 12021 (+7826) | 1093.0 (+3675) | ZC only | 0.90 / −0.17 |
| BOW 1 | yes | 2.477 | 1.393 | 0.56 | 1.857 | 1.045 | null | 12151 (+7845) | 2022.3 (+4740) | ZC only | 0.90 / −0.18 |
| ROSIN 0 | yes | 1.512 | 0.866 | 0.57 | 1.134 | 0.650 | 65.3 (−1203) | 4586 (+6158) | 974.4 (+3476) | no | (run 1) |
| ROSIN 1 | yes | 2.325 | 0.895 | 0.39 | 1.744 | 0.672 | null | 22778 (+8933) | 2599.2 (+5175) | ZC only | 0.65 / 0.06 |
| BODY 0 | yes | 1.917 | 0.884 | 0.46 | 1.917 | 0.884 | null | 12064 (+7832) | 1784.2 (+4524) | ZC only | 0.90 / −0.17 |
| BODY 1 | yes | 1.917 | 0.884 | 0.46 | 0.959 | 0.442 | null | 12064 (+7832) | 1784.2 (+4524) | ZC only | 0.90 / −0.17 |
| DECAY 0 | yes | 1.917 | 0.759 | 0.40 | 1.438 | 0.569 | 1043.8 (+3595) | 6938 (+6875) | 116.0 (−208) | no | 0.97 / 0.29 |
| DECAY 1 | yes | 1.917 | 0.919 | 0.48 | 1.438 | 0.689 | null | 12019 (+7826) | 2141.3 (+4839) | ZC only | (run 1) |
| SYMPATHY 0 | yes | 1.917 | 0.884 | 0.46 | 1.438 | 0.663 | null | 12064 (+7832) | 1784.2 (+4524) | ZC only | (run 1) |
| SYMPATHY 1 | yes | 1.917 | 0.884 | 0.46 | 1.438 | 0.663 | null | 12064 (+7832) | 1784.2 (+4524) | ZC only | 0.90 / −0.17 |
| BOW 1 ROSIN 1 | yes | 2.877 | 1.413 | 0.49 | 2.158 | 1.060 | null | 22768 (+8932) | 3956.2 (+5902) | ZC only | 0.64 / 0.05 |

Table B — harmonics and the finished Snip:

| config | fRef | h2 / h3 / h4 / h5 / h6 dB re h1 | slope dB/oct | f0/2 dB | onset90 ms | centroid / rolloff | Snip Pitch | peak | RMS | mean (DC) | Loudness | Classifier vs drumClassFor |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| defaults | 130.8 | 0.6 / −0.8 / 0.5 / −0.8 / −5.1 | −1.3 | −7.1 | 27.5 | 100 / 6234 | 1025.6 c0.91 | 0.950 | 0.468 | 0.441 | 0.2013 | PERC vs TONAL |
| TUNE 0 | 65.4 | 3.4 / −7.3 / 4.3 / −0.8 / 7.3 | 1.4 | 5.1 | 67.5 | 7 / 1647 | 1191.9 c0.90 | 0.950 | 0.469 | 0.440 | 0.2263 | TONAL vs TONAL |
| TUNE 1 | 261.6 | 3.7 / 4.9 / 5.9 / 1.5 / 1.9 | 0.7 | 12.0 | 22.5 | 309 / 12672 | 1191.9 c0.93 | 0.950 | 0.489 | 0.462 | 0.2001 | SNARE vs TONAL |
| BOW 0 | 130.8 | 3.3 / 3.4 / 6.3 / 9.0 / 8.2 | 3.4 | 9.1 | 22.5 | 1053 / 8107 | null | 0.950 | 0.251 | 0.082 | 0.2612 | PERC vs TONAL |
| BOW 1 | 130.8 | 3.6 / 5.1 / 9.4 / 5.1 / 3.7 | 2.0 | 5.7 | 52.5 | 12 / 2562 | 1102.5 c0.96 | 0.950 | 0.555 | 0.534 | 0.2067 | TONAL vs TONAL |
| ROSIN 0 | 130.8 | −4.4 / −0.8 / −9.0 / −7.7 / −4.8 | −2.6 | 3.7 | 32.5 | 20 / 2401 | 958.7 c0.95 | 0.950 | 0.572 | 0.547 | 0.2157 | PERC vs TONAL |
| ROSIN 1 | 130.8 | 7.8 / 4.8 / 7.0 / 7.3 / 6.2 | 2.2 | 5.1 | 27.5 | 257 / 8742 | 1160.5 c0.87 | 0.950 | 0.394 | 0.366 | 0.1962 | PERC vs TONAL |
| BODY 0 / 1 | 130.8 | 0.6 / −0.8 / 0.5 / −0.8 / −5.1 | −1.3 | −7.1 | 27.5 | 100 / 6234 | 1025.6 c0.91 | 0.950 | 0.468 | 0.441 | 0.2013 / 0.2014 | PERC vs TONAL |
| DECAY 0 | 116.0 | −2.8 / 1.8 / 5.5 / 4.4 / −2.2 | 1.1 | −1.6 | 27.5 | 100 / 6234 | 1160.5 c0.90 | 0.950 | 0.425 | 0.378 | 0.1931 | PERC vs TONAL |
| DECAY 1 | 130.8 | 2.6 / 8.1 / 9.0 / 8.4 / 8.8 | 3.8 | 7.8 | 27.5 | 100 / 6234 | 1025.6 c0.91 | 0.950 | 0.479 | (run 1) | 0.2013 | LOOP vs LOOP |
| SYMPATHY 0 / 1 | 130.8 | 0.6 / −0.8 / 0.5 / −0.8 / −5.1 | −1.3 | −7.1 | 27.5 | 100 / 6234 | 1025.6 c0.91 | 0.950 | 0.468 | 0.441 | 0.2013 | PERC vs TONAL |
| BOW 1 ROSIN 1 | 130.8 | 0.9 / −5.2 / −6.8 / −2.9 / 0.4 | −1.1 | −6.4 | 42.5 | 47 / 5480 | 1102.5 c0.92 | 0.950 | 0.485 | 0.465 | 0.2016 | PERC vs TONAL |

Read: BODY 0 and BODY 1 give the same Snip to the fourth decimal of Loudness (the body mix is 0.5·input + 0.9·body at BODY 1 and pure input at BODY 0, but the `Punch`/normalise stage re-levels; the body rows differ only in the pre-normalise buffer). The Snip's `Pitch.detect` values 1025.6 / 1191.9 / 958.7 / 1102.5 / 1160.5 Hz are `44100 / lag` for lags 43, 37, 46, 40, 38 — the detector's shortest allowed lags, reporting the chaos's high-frequency texture, not a note; the "c0.9" confidence is the autocorrelation of a smoothed noise at a short lag.

#### v1 CONTRABASS (root 41.20; f0 82.40)

| config | raw peak / mean / ratio | Pitch.detect | ZC Hz | FFT peak Hz | r(1) / r(D) | h2…h6 dB | slope | f0/2 | onset90 ms | Snip Pitch | Snip mean | Loudness | Classifier vs pred. |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| defaults | 1.924 / 0.888 / 0.46 | null | 13212 | 602.5 | 0.88 / −0.19 | −2.8 / −7.0 / 0.4 / 3.7 / 1.8 | 1.4 | 5.6 | 52.5 | 1025.6 | 0.439 | 0.2187 | PERC vs TONAL |
| TUNE 0 (41.2 Hz) | 1.926 / 0.875 / 0.45 | null | 12108 | 765.0 | 0.90 / −0.19 | 5.4 / 4.3 / 5.0 / 9.1 / 10.1 | 3.5 | 4.0 | 102.5 | 537.8 | 0.431 | 0.2377 | TONAL vs TONAL |
| TUNE 1 (164.8) | 1.913 / 0.882 / 0.46 | null | 12554 | 2239.9 | 0.89 / −0.19 | 5.1 / 8.1 / 12.7 / 8.1 / 9.0 | 3.8 | 11.0 | 17.5 | 1191.9 | 0.442 | 0.1970 | CLAP vs TONAL |
| BOW 0 | 1.096 / 0.096 / 0.09 | null | 13138 | 210.7 | 0.89 / −0.20 | 9.1 / 2.6 / 2.9 / 0.7 / 8.0 | 1.0 | 2.0 | 37.5 | null | 0.084 | 0.2812 | PERC vs TONAL |
| BOW 1 | 2.477 / 1.391 / 0.56 | null | 13323 | 2847.5 | 0.88 / −0.21 | 6.5 / 3.8 / 5.7 / 4.7 / 7.1 | 2.0 | 9.7 | 87.5 | 958.7 | 0.532 | 0.2116 | TONAL vs TONAL |
| ROSIN 0 | 1.524 / 0.867 / 0.57 | 41.2 (−1202 c) | 4893 | 1770.3 | — | 1.0 / 0.4 / −1.2 / −2.5 / 3.8 | 0.2 | 8.5 | 62.5 | 787.5 | 0.540 | 0.2200 | TONAL vs TONAL |
| ROSIN 1 | 2.433 / 0.902 / 0.37 | null | 24157 | 3640.5 | 0.62 / 0.10 | 19.9 / 16.8 / 16.8 / 11.7 / 16.7 | 4.6 | 4.9 | 42.5 | 816.7 | 0.368 | 0.2174 | PERC vs TONAL |
| DECAY 0 | 1.924 / 0.776 / 0.40 | null | 3997 | 179.9 | 0.99 / 0.43 | 1.0 / 5.4 / −0.8 / 1.8 / −3.7 | −0.7 | −7.9 | 57.5 | 787.5 | 0.382 | 0.2061 | PERC vs TONAL |
| DECAY 1 | 1.924 / 0.919 / 0.48 | null | 13392 | 39.0 | — | −2.9 / −0.6 / −2.5 / 0.9 / 0.2 | 0.3 | 4.9 | 52.5 | 1025.6 | — | 0.2187 | LOOP vs LOOP |
| BOW 1 ROSIN 1 | 2.977 / 1.408 / 0.47 | null | 23974 | 3027.5 | 0.62 / 0.09 | −3.9 / 3.6 / −4.0 / 1.2 / 2.4 | 0.9 | −13.6 | 72.5 | 1075.6 | 0.464 | 0.2120 | TONAL vs TONAL |

BODY 0 / 1, SYMPATHY 0 / 1: identical to defaults in every column (Loudness 0.2187 / 0.2188). The centroid at TUNE 0 and BOW 1 reads 3 Hz and rolloff 11 Hz: the first 93 ms of the Snip is almost pure DC.

#### v1 ERHU (root 293.66; f0 587.32)

| config | raw peak / mean / ratio | Pitch.detect | ZC Hz | FFT peak Hz | r(1) / r(D) | h2…h6 dB | slope | f0/2 | onset90 | Snip Pitch | Snip mean | Loudness | Classifier vs pred. |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| defaults | 1.887 / 0.878 / 0.47 | null | 12779 | 947.5 | 0.89 / −0.19 | −1.2 / −0.1 / −0.3 / 4.1 / 1.9 | 1.2 | 1.4 | 37.5 | **294.0** c0.89 | 0.283 | 0.2438 | SNARE vs TONAL |
| TUNE 0 (293.66) | 1.852 / 0.877 / 0.47 | null | 12668 | 1629.5 | 0.89 / −0.18 | 5.0 / 1.5 / −1.2 / 0.7 / −2.8 | −1.2 | 3.0 | 22.5 | **302.1** c0.82 | 0.296 | 0.2228 | SNARE vs TONAL |
| TUNE 1 (1174.6) | 1.857 / 0.878 / 0.47 | null | 11972 | 1822.2 | 0.90 / −0.19 | 8.5 / 6.6 / 5.6 / 3.4 / 4.1 | 0.9 | 8.1 | 27.5 | **292.1** c0.77 | 0.299 | 0.2154 | SNARE vs TONAL |
| BOW 0 | 1.014 / 0.095 / 0.09 | null | 12539 | 3829.4 | 0.89 / −0.18 | 5.6 / 2.5 / 2.2 / 3.6 / 1.8 | 0.5 | 5.2 | 42.5 | 294.0 c0.64 | 0.039 | 0.2381 | SNARE vs TONAL |
| BOW 1 | 2.400 / 1.403 / 0.58 | null | 11939 | 2609.8 | 0.90 / −0.18 | 3.9 / 3.2 / 0.7 / 2.8 / 4.6 | 1.0 | 1.5 | 17.5 | 294.0 c0.94 | 0.381 | 0.2052 | SNARE vs TONAL |
| ROSIN 0 | 1.489 / 0.866 / 0.58 | 291.1 (−1215 c) | 4663 | 2637.8 | — | 1.2 / −3.9 / −2.1 / −4.6 / −3.9 | −1.9 | 4.0 | 47.5 | 292.1 c0.95 | 0.315 | 0.2718 | PERC vs TONAL |
| ROSIN 1 | 2.249 / 0.894 / 0.40 | null | 22887 | 4266.6 | 0.64 / 0.05 | −0.6 / 1.3 / 0.8 / 0.6 / 4.1 | 1.2 | −0.7 | 12.5 | 312.8 c0.84 | 0.295 | 0.1978 | SNARE vs TONAL |
| DECAY 0 | 1.887 / 0.742 / 0.39 | 1191.9 (+1225 c) | 10733 | 2100.6 | 0.94 / 0.33 | 0.9 / −0.1 / 1.3 / 1.7 / −1.2 | 0.0 | 0.9 | 37.5 | 294.0 c0.87 | 0.244 | 0.2162 | SNARE vs TONAL |
| DECAY 1 | 1.887 / 0.916 / 0.49 | null | 11750 | 912.5 | — | 4.8 / −0.9 / 2.5 / 4.1 / 2.7 | 0.9 | 3.2 | 37.5 | 294.0 c0.89 | — | 0.2438 | LOOP vs LOOP |
| BOW 1 ROSIN 1 | 2.797 / 1.426 / 0.51 | null | 22989 | 5375.7 | 0.64 / 0.05 | 5.2 / 4.7 / 1.9 / 7.2 / 0.5 | 0.8 | −1.3 | 7.5 | 459.4 c0.89 | 0.368 | 0.1927 | SNARE vs TONAL |

BODY 0 / 1 and SYMPATHY 0 / 1: identical to defaults in every column — `bodyMacro` is not read in the membrane branch and SARANGI's `sympathy` branch does not apply to ERHU. The finished ERHU plays **293 Hz at every TUNE**: the `Modes.ring(drive, 293f, …)` fundamental.

#### v1 SARANGI (root 130.81; f0 261.62)

| config | raw peak / mean / ratio | Pitch.detect | ZC Hz | FFT peak Hz | r(1) / r(D) | h2…h6 dB | slope | f0/2 | onset90 | Snip Pitch | Snip mean | Loudness | Classifier vs pred. |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| defaults | 1.833 / 0.878 / 0.48 | null | 12148 | 1725.5 | 0.90 / −0.18 | 2.1 / 7.4 / 7.3 / 3.4 / 5.5 | 2.2 | 0.6 | 22.5 | 177.8 c0.73 | 0.269 | 0.2038 | SNARE vs TONAL |
| TUNE 0 (130.81) | 1.917 / 0.885 / 0.46 | null | 12299 | 2559.4 | 0.89 / −0.19 | 5.8 / 10.1 / 5.8 / 4.1 / 10.0 | 2.7 | 7.6 | 27.5 | 150.0 c0.82 | 0.263 | 0.1616 | PERC vs TONAL |
| TUNE 1 (523.24) | 1.823 / 0.878 / 0.48 | null | 11892 | 332.7 | 0.90 / −0.19 | −0.8 / 3.4 / 5.3 / 4.7 / 1.1 | 1.6 | −1.8 | 37.5 | 479.3 c0.79 | 0.274 | 0.1918 | SNARE vs TONAL |
| BOW 0 | 1.061 / 0.095 / 0.09 | null | 12226 | 1194.4 | 0.90 / −0.19 | 0.9 / −2.9 / 2.4 / 1.6 / −1.2 | 0.1 | 0.8 | 22.5 | 69.1 c0.45 | 0.042 | 0.2105 | SNARE vs TONAL |
| BOW 1 | 2.431 / 1.399 / 0.58 | null | 11897 | 89.7 | 0.90 / −0.18 | 1.7 / 1.9 / 2.9 / 2.0 / 3.0 | 1.0 | 1.6 | 17.5 | 135.7 c0.92 | 0.387 | 0.1881 | CLAP vs TONAL |
| ROSIN 0 | 1.490 / 0.862 / 0.58 | 130.3 (−1207 c) | 4761 | 911.6 | — | 10.3 / 5.8 / 4.3 / 3.5 / 3.3 | 0.4 | 2.1 | 27.5 | 130.5 c0.94 | 0.311 | 0.2424 | PERC vs TONAL |
| ROSIN 1 | 2.314 / 0.889 / 0.38 | null | 22872 | 2691.5 | 0.63 / 0.04 | 5.6 / 7.9 / 10.5 / 11.9 / 12.0 | 4.8 | −1.7 | 12.5 | 882.0 c0.86 | 0.244 | 0.1479 | CLAP vs TONAL |
| DECAY 0 | 1.809 / 0.739 / 0.41 | 1138.1 (+2545 c) | 9484 | 3012.9 | 0.95 / 0.33 | −7.2 / −8.8 / −4.8 / −4.8 / −4.8 | −1.4 | −6.8 | 22.5 | 134.5 c0.72 | 0.243 | 0.1954 | SNARE vs TONAL |
| DECAY 1 | 1.866 / 0.915 / 0.49 | null | 12078 | 941.7 | — | 0.8 / −9.7 / 3.0 / −4.4 / −3.5 | −1.3 | 1.7 | 22.5 | 177.8 c0.73 | — | 0.2038 | LOOP vs LOOP |
| SYMPATHY 0 | 1.833 / 0.878 / 0.48 | null | 12148 | 1725.5 | — | as defaults | 2.2 | 0.6 | 22.5 | 177.8 c0.73 | 0.267 | 0.2041 | SNARE vs TONAL |
| SYMPATHY 1 | 1.833 / 0.878 / 0.48 | null | 12148 | 1725.5 | 0.90 / −0.18 | as defaults | 2.2 | 0.6 | 22.5 | 177.8 c0.72 | 0.267 | 0.2032 | SNARE vs TONAL |
| BOW 1 ROSIN 1 | 2.877 / 1.413 / 0.49 | null | 22883 | 4900.2 | 0.64 / 0.04 | 0.2 / 2.9 / −0.9 / 0.2 / 0.8 | 0.1 | 1.9 | 17.5 | 506.9 c0.88 | 0.333 | 0.1567 | SNARE vs TONAL |

BODY 0 / 1 identical to defaults. SYMPATHY 0 → 1 moves the body peak 1.226 → 1.235 and Loudness 0.2041 → 0.2032: the seven tarab strings are 0.7 % of the body's peak.

#### v1 GURDY (root 146.83; f0 293.66)

| config | raw peak / mean / ratio | body peak / mean | Pitch.detect | ZC Hz | FFT peak Hz | h2…h6 dB | slope | f0/2 | onset90 | Snip Pitch | Snip mean | Loudness | Classifier vs pred. |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| defaults | 1.852 / 0.877 / 0.47 | 2.928 / 0.810 | null | 12668 | 1629.5 | 5.0 / 1.5 / −1.2 / 0.7 / −2.8 | −1.2 | 3.0 | 22.5 | 1160.5 c0.83 | 0.273 | 0.1620 | SNARE vs TONAL |
| TUNE 0 (146.83) | 1.922 / 0.879 / 0.46 | 3.402 / 0.817 | null | 12117 | 6543.8 | −6.6 / −1.7 / −3.5 / −0.9 / 0.2 | 0.4 | 4.4 | 22.5 | 1002.3 c0.77 | 0.230 | 0.1526 | PERC vs TONAL |
| TUNE 1 (587.32) | 1.887 / 0.878 / 0.47 | 3.026 / 0.810 | null | 12779 | 947.5 | −1.2 / −0.1 / −0.3 / 4.1 / 1.9 | 1.2 | 1.4 | 37.5 | 1130.8 c0.82 | 0.269 | 0.1608 | SNARE vs TONAL |
| BOW 0 | 0.986 / 0.092 / 0.09 | 1.088 / **−0.252** | null | 12678 | 775.8 | −8.3 / 0.6 / −1.1 / −2.3 / −3.3 | −0.1 | 4.4 | 17.5 | 1075.6 c0.48 | **−0.223** | 0.2397 | SNARE vs TONAL |
| BOW 1 | 2.467 / 1.404 / 0.57 | 5.378 / 1.996 | null | 12870 | 454.2 | −1.2 / −4.1 / −2.1 / 2.1 / 1.2 | 0.6 | 1.5 | 17.5 | 1025.6 c0.93 | 0.359 | 0.1543 | SNARE vs TONAL |
| ROSIN 0 | 1.492 / 0.863 / 0.58 | 2.046 / 0.764 | 146.3 (−1207 c) | 4474 | 734.9 | 3.3 / −2.7 / 3.5 / 3.3 / 1.3 | 0.7 | 6.1 | 32.5 | 1160.5 c0.86 | 0.361 | 0.2019 | PERC vs TONAL |
| ROSIN 1 | 2.337 / 0.891 / 0.38 | 4.541 / 0.879 | null | 24361 | 205.7 | −3.7 / −4.7 / −4.0 / −3.9 / −1.9 | −1.0 | −17.9 | 7.5 | 1160.5 c0.81 | 0.184 | 0.1234 | SNARE vs TONAL |
| BODY 0 / 1 | as defaults | 2.928 / 0.810 | null | 12668 | 1629.5 | as defaults | −1.2 | 3.0 | 22.5 | 1160.5 | 0.273 | 0.1620 | SNARE vs TONAL |
| DECAY 0 | 1.803 / 0.738 / 0.41 | 2.639 / 0.602 | 1152.9 (+2368 c) | 9929 | 2148.9 | −0.5 / 2.6 / 0.6 / 0.2 / −0.1 | 0.1 | 2.1 | 22.5 | 1130.8 c0.82 | 0.222 | 0.1658 | SNARE vs TONAL |
| DECAY 1 | as defaults | 2.928 / 0.867 | null | 12611 | 4567.8 | 6.7 / 3.4 / 11.2 / 5.5 / 6.7 | 2.5 | 11.3 | 22.5 | 1160.5 | — | 0.1631 | LOOP vs LOOP |
| SYMPATHY 0 | as defaults | **1.502 / 0.527** | null | 12668 | 1629.5 | as defaults | −1.2 | 3.0 | 22.5 | 1130.8 | 0.336 | 0.1979 | SNARE vs TONAL |
| SYMPATHY 1 | as defaults | **6.314 / 1.468** | null | 12668 | 1629.5 | as defaults | −1.2 | 3.0 | 22.5 | 1160.5 | 0.236 | 0.1469 | SNARE vs TONAL |
| BOW 1 ROSIN 1 | 2.934 / 1.423 / 0.48 | 7.304 / 2.088 | null | 23606 | 1830.3 | 4.3 / 2.3 / 5.5 / 5.3 / 6.1 | 2.2 | −3.5 | 17.5 | 1160.5 c0.93 | 0.272 | 0.1317 | SNARE vs TONAL |

The body mean at SYMPATHY 0 is 0.527 = raw mean 0.877 + the drone constant −0.3497: the "D2 bourdon" is a DC shift. At SYMPATHY 1 the chien rattle (`(|dry| − 0.35)² · 2.5 · sympathy`, positive-only, low-passed) lifts the body peak to 6.3 and the mean to 1.47 — more DC. BOW 0 is the one render whose finished DC is negative (−0.223): the raw is at 0.09, the drone's −0.35 wins.

#### v2 — SOLINA rows (the loop rows are the v1 numbers to every printed digit)

| voice | config | raw peak / mean / ratio | body peak / mean | Pitch.detect (c) | ZC Hz | FFT peak (c) | r(1) / r(D) | h2…h6 dB re h1 | slope | f0/2 | onset90 | centroid / rolloff | Snip Pitch | Snip mean | Loudness | Classifier | (L−R)/(L+R) Snip |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CELLO | SOLINA 0 | 1.917 / 0.884 / 0.46 | 1.438 / 0.663 | null | 12064 | 1784.2 (+4524) | 0.90 / −0.17 | 0.6 / −0.8 / 0.5 / −0.8 / −5.1 | −1.3 | −7.1 | 27.5 | 101 / 6471 | 1025.6 | 0.431 | 0.1968 | PERC vs TONAL | −35.0 |
| CELLO | SOLINA 1 | 1.563 / 0.530 / 0.34 | 1.172 / 0.398 | 131.4 (+8.3) | 5165 | 130.9 (+1) | 0.97 / **0.70** | **−5.7 / −8.5 / −11.5 / −12.4 / −15.8** | **−5.8** | −34.0 | 27.5 | 57 / 3435 | 595.9 c0.84 | 0.394 | 0.1973 | PERC vs TONAL | −43.7 |
| CONTRABASS | SOLINA 1 | 1.593 / 0.533 / 0.33 | 1.195 / 0.400 | 82.8 (+7.9) | 5038 | 82.4 (+1) | 0.97 / 0.71 | −6.1 / −9.5 / −11.2 / −14.5 / −14.5 | −5.8 | −25.1 | 47.5 | 24 / 2864 | 82.3 c0.91 | 0.392 | 0.1986 | PERC vs TONAL | −44.4 |
| ERHU | SOLINA 1 | 1.572 / 0.527 / 0.33 | 1.040 / 0.211 | 588.0 (+2.0) | 5413 | 587.4 (0) | 0.96 / 0.70 | −6.4 / −9.5 / −12.5 / −12.7 / −16.6 | −6.0 | −27.2 | 42.5 | 157 / 7989 | 294.0 c0.89 | 0.238 | 0.2487 | PERC vs TONAL | −42.9 |
| SARANGI | SOLINA 1 | 1.561 / 0.527 / 0.34 | 0.954 / 0.211 | 261.7 (+0.7) | 4850 | 261.6 (0) | 0.97 / 0.70 | −6.5 / −10.6 / −12.2 / −15.3 / −15.6 | −6.2 | −33.0 | 42.5 | 169 / 5782 | 260.9 c0.85 | 0.250 | 0.2325 | PERC vs TONAL | −44.2 |
| GURDY | SOLINA 1 | 1.518 / 0.526 / 0.35 | 2.435 / 0.333 | 294.0 (+2.0) | 5559 | 293.6 (0) | 0.96 / 0.70 | −5.8 / −9.1 / −11.1 / −13.8 / −15.7 | −6.0 | −24.9 | 42.5 | 480 / 7074 | 296.0 c0.90 | 0.172 | 0.2099 | SNARE vs TONAL | −39.6 |

At SOLINA 1 the raw buffer is `0.6·stringWave + 0.5·pressure·saw`: the saw is the −6 dB/oct series and the in-tune line; r(D) = 0.70 (not 1) because 0.6 of the chaos is still there; Pitch.detect's +8 c at 131.4 Hz is its lag quantisation at 176.4 kHz (1342 vs 1348.4 samples). The stereo column: **−35 dB at SOLINA 0** on two channels that left `SolinaEnsemble.process` identical (its `amount ≤ 0.001` branch duplicates the mono input) — that difference is manufactured by `Tide.bandLimit` walking one filter state across L, R, L, R…; and **−43.7 dB at SOLINA 1** where the ensemble's own output was −12.5 dB (F4). GURDY's v2 BODY macro (rattle × bodyMacro, the v1 sympathy) does act: BODY 0 → 1 Loudness 0.1982 → 0.1461.

#### D · v2 at SOLINA 1: the saw against the loop

| voice | f0 | raw peaks within ±6 % of f0, Hz (dB re strongest) | Snip peaks within ±6 % (mono, from 0.1 s) | raw global peak at SOLINA 0 |
|---|---|---|---|---|
| CELLO | 130.82 | 130.55 (0) | 131.89 (0); 137.11 (−7.5); 125.83 (−8.9) | 1784.16 (+4524 c) |
| CONTRABASS | 82.40 | 82.10 (0) | 82.60 (0) | 602.47 (+3444 c) |
| ERHU | 587.32 | 587.45 (0); 570.63 (−28.8); 599.57 (−29.9) | 596.54 (0); 580.56 (−0.1); 576.69 (−2.1) | 947.54 (+828 c) |
| SARANGI | 261.62 | 261.76 (0); 273.88 (−28.7); 249.65 (−34.1) | 263.11 (0); 259.24 (−3.9); 270.17 (−12.1) | 1725.46 (+3266 c) |
| GURDY | 293.66 | 293.39 (0); 281.95 (−28.4); 310.21 (−29.0) | 295.41 (0); 290.36 (−1.4); 302.14 (−7.5) | 1629.45 (+2967 c) |

The single raw line is the saw (130.55 vs 130.82 is the 0.67 Hz bin's resolution, ±4 c at 130 Hz). There is no second line for the string to be "locked" to: at SOLINA 0 the same buffer's strongest component is 828–4524 cents away. In the Snip the ensemble's modulation splits the saw into sidebands 5–12 Hz either side (the 5.85 Hz LFO), 0.1–9 dB down.

#### H1 · Where the note is (v1, TUNE 0 / 0.5 / 1)

| voice | TUNE | f0 | raw global peak Hz | body global peak Hz | Snip global peak Hz | Snip level at f0 re its peak, dB | Snip r(one period of f0) |
|---|---|---|---|---|---|---|---|
| CELLO | 0 / 0.5 / 1 | 65.41 / 130.82 / 261.64 | 563.6 / 1784.2 / 1167.0 | 563.6 / 1784.2 / 1167.0 | 2519.3 / 2023.7 / 3538.3 | −13.7 / −9.6 / −10.5 | 0.01 / −0.00 / 0.01 |
| CONTRABASS | 0 / 0.5 / 1 | 41.20 / 82.40 / 164.80 | 765.0 / 602.5 / 2239.9 | same as raw | 203.8 / 40.3 / 3384.9 | −11.0 / −5.5 / −12.6 | 0.15 / −0.01 / 0.00 |
| ERHU | 0 / 0.5 / 1 | 293.66 / 587.32 / 1174.64 | 1629.5 / 947.5 / 1822.2 | **293.6 / 293.2 / 464.2** | **292.8 / 291.6 / 293.1** | −0.0 / −36.0 / −35.3 | 0.27 / −0.57 / −0.28 |
| SARANGI | 0 / 0.5 / 1 | 130.81 / 261.62 / 523.24 | 2559.4 / 1725.5 / 332.7 | **207.7 / 130.3 / 129.0** | **206.4 / 130.0 / 129.3** | −3.9 / −34.9 / −31.2 | 0.03 / −0.58 / −0.18 |
| GURDY | 0 / 0.5 / 1 | 146.83 / 293.66 / 587.32 | 6543.8 / 1629.5 / 947.5 | 353.0 / 106.6 / 947.7 | 810.8 / 2818.1 / 2085.8 | −8.7 / −8.6 / −9.2 | −0.03 / −0.01 / −0.01 |

ERHU at TUNE 0 reads "in tune" only because its f0 there happens to be the membrane's 293 Hz; at TUNE 0.5 the Snip's autocorrelation at the f0 period is −0.57 — a 293 Hz tone sampled at half its period. SARANGI at TUNE 0 peaks at 206–208 Hz: the membrane's second mode (130 × 1.5933 = 207.1, `Modes.kt:163`).

#### H2 · The friction map on its own

`g(x) = μ(v_bow − x) + 0.985·x` with the spec's μ (sgn·(0.25 + (μS − 0.25)/(1 + (Δv/0.15)²))); every lane n mod D of the delay line iterates this map on its own (the one-pole spreads it over ~4 neighbouring samples, the interpolator over 2). A fixed point needs 0.015·x = μ(v_bow − x): scanned in 0.0005 steps over [−3, 6]:

| BOW | ROSIN | vBow | μS | fixed point | orbit mean (steps 2000–20000) | peak | min | r(1) | first 12 iterates from 0 |
|---|---|---|---|---|---|---|---|---|---|
| 0.6 | 0.5 | 0.940 | 1.00 | none | 0.960 | **1.926** | −0.074 | 0.413 | 0.27 0.55 0.89 1.80 1.50 1.17 0.69 1.13 0.57 0.91 1.88 1.58 |
| 0 | 0.5 | 0.100 | 1.00 | none | 0.092 | 1.098 | −0.901 | 0.430 | 0.77 0.47 0.11 −0.89 −0.61 −0.32 0.02 0.87 0.58 0.25 −0.37 −0.05 |
| 1 | 0.5 | 1.500 | 1.00 | none | 1.496 | **2.477** | 0.478 | 0.398 | 0.26 0.51 0.77 1.04 1.35 1.95 1.60 0.81 1.08 1.40 2.16 1.85 |
| 0.6 | 0 | 0.940 | 0.60 | none | 0.933 | 1.526 | 0.326 | 0.094 | 0.26 0.52 0.80 1.23 0.89 1.44 1.14 0.75 1.12 0.71 1.06 0.57 |
| 0.6 | 1 | 0.940 | 1.40 | none | 0.905 | 2.326 | −0.474 | 0.559 | 0.28 0.58 0.99 −0.30 −0.03 0.25 0.55 0.94 −0.47 −0.20 0.07 0.35 |
| 1 | 1 | 1.500 | 1.40 | none | 1.460 | **2.877** | 0.078 | 0.564 | 0.26 0.52 0.79 1.08 1.44 2.68 2.38 2.06 1.70 1.01 1.34 2.10 |

The rendered raw peaks are 1.917 / 1.094 / 2.477 / 1.512 / 2.325 / 2.877 for the same six corners: the loop's amplitude and its ~0.9 mean are the map's, not a wave's. Why there is no fixed point, from the code: for x below v_bow, Δv > 0 and μ ≥ 0.25, so a stationary x would need x = μ/0.015 ≥ 16.7 — above v_bow, contradiction; for x above v_bow, μ ≤ −0.25 and x would have to be negative — below v_bow, contradiction. The map has a jump of ≈ 2·μS at x = v_bow (μ flips sign through `sgn`), so every lane bounces between ≈ −0.07 and ≈ 1.93 with no period.

### E · Render cost (ms per rendered second; median of 3 after one warm-up; two runs)

| voice | v1 defaults (0.935 s) | v1 DECAY 1 (3.5 s) | v2 defaults | v2 SOLINA 1 |
|---|---|---|---|---|
| CELLO | 34.8 / 48.9 | 46.9 / 36.1 | 68.7 / 77.4 | 101.2 / 101.9 |
| CONTRABASS | 34.8 / 52.7 | 34.6 / 34.4 | 68.0 / 68.3 | 94.8 / 101.3 |
| ERHU | 36.5 / 37.5 | 39.9 / 36.5 | 71.1 / 78.9 | 121.5 / 91.3 |
| SARANGI | 71.6 / 58.0 | 48.5 / 43.2 | 73.9 / 70.6 | 98.2 / 96.5 |
| GURDY | 43.4 / 41.5 | 41.7 / 41.2 | 75.3 / 75.3 | 104.9 / 98.9 |

v1 is in BORE's 37–59 range (BORE A9) except SARANGI, whose seven `Pluck.ks` strings render 2 s each at 176.4 kHz whatever the note length. v2 doubles the cost at SOLINA 0 — `SolinaEnsemble.process` still allocates and duplicates a stereo buffer, and `Tide.bandLimit`, `normalizeByFold`, `Punch.applyOversampled` (which decimates twice) and `fadeTail` all run over 2× the samples — and reaches 91–122 ms/s at SOLINA 1, above FORK's 88 (BORE A9).

### F · The SOLINA stage on its own (44.1 kHz)

**F1 · A pure 220 Hz sine, 2 s, amount 1.0** — zero-crossing pitch per 100 ms window, cents against 220:

| | L | R | mono fold (L+R)/2 |
|---|---|---|---|
| mean | +7.6 c | −2.0 c | +0.7 c |
| sd across 20 windows | 32.3 c | 27.9 c | 18.4 c |
| min / max | −51.3 / +89.0 c | −86.6 / +38.6 c | −48.5 / +48.1 c |
| whole-file FFT peak | 221.73 Hz | 218.31 Hz | 218.28 Hz |
| Loudness.of | 0.6255 | 0.6242 | 0.5570 |

Per-window rows are in the tables file; the L channel's RMS swings 0.108–0.710 window to window (the two taps beating), R 0.232–0.732. The stage does not produce "zero net pitch deviation": the *sum of three* taps cancels (test F3 evaluates sin x + sin(x+2π/3) + sin(x+4π/3) = 0.000000000), but L carries taps 0+1 and R taps 1+2, and each pair's delay derivative is a sinusoid of the same depth as one tap's. Mono fold against stereo energy: −0.76 dB by RMS, Loudness fold 0.557 vs 0.626 per channel (−1.0 dB); (L−R)/(L+R) = −7.2 dB.

**F2 · White noise (`Dsp.Noise(1234)`, 4 s, 0.3 peak-scaled), L channel** — the dry/wet mix `dry·(1−a) + wet·a` against a ≈ 7.5 ms delay predicts a comb with peaks at k·133.3 Hz and notches at (k+½)·133.3 Hz:

| amount | Welch-averaged ripple (16384-pt Hann, 15 hops), mean over k = 1..15 | max k | short-window (4096, eight times) min / max | Loudness L / R / fold | fold vs stereo RMS | (L−R)/(L+R) |
|---|---|---|---|---|---|---|
| 0.5 | +0.98 dB (per k: 9.3 −3.7 −3.7 −1.4 1.9 3.2 2.0 −0.7 −0.2 1.1 1.0 3.5 1.2 −0.6 1.9) | 9.3 dB at k = 1 | −2.6 / +2.6 dB | 0.1149 / 0.1154 / 0.1084 | −0.48 dB | −9.3 dB |
| 0.9 | −0.07 dB (per k: −1.1 −0.1 1.4 0.0 −0.9 −0.2 −1.3 −0.6 0.1 0.2 0.1 0.0 0.5 0.0 0.8) | 1.4 dB | −0.8 / +2.6 dB | 0.1568 / 0.1579 / 0.1360 | −1.22 dB | −4.9 dB |

The static comb the mix predicts is smeared by the modulation: the delay sweeps 7.5 ± (2.8 + 0.45)·amount ms, i.e. 5.9–9.1 ms at 0.5 and 4.6–10.4 ms at 0.9, so the comb spacing wanders 110–170 Hz and 96–217 Hz and the time-averaged spectrum flattens to within ±1.4 dB at 0.9; instantaneous (93 ms) ripple is still ±2.6 dB. At 0.5 the first peak/notch pair (133 vs 200 Hz) is 9.3 dB — the low end of the comb survives the averaging. At amount 0.9 the fold loses 1.22 dB against the stereo energy — the spec's own 1.2 dB bound, just crossed.

**F3 · The 1.724 s claim** — 0.58 × 1.724 = 0.99992 cycles; 5.85 × 1.724 = **10.0854** cycles. 1.724 s is one period of the slow LFO and not a whole number of the fast one; the smallest T with both whole is **100 s** (0.58 = 29/50, 5.85 = 117/20 → 58 slow and 585 fast cycles).

**F4 · v2 finished Snip at SOLINA 1, CELLO and ERHU** — Loudness L / R / fold 0.1973 / 0.1973 / 0.1973 (CELLO), 0.2487 / 0.2487 / 0.2487 (ERHU); fold vs stereo RMS −0.00 dB; (L−R)/(L+R) **−43.7 / −42.9 dB in the Snip against −12.5 / −8.4 dB in the ensemble's own output**. The spec's phase-correlation test would pass with 0.00 dB of margin because the mono-only `Tide.bandLimit` has already folded the channels.

### G · The SARANGI tarab against TUNE (v1, SYMPATHY 1 − SYMPATHY 0)

The render is deterministic and `sympathy` reaches only `tarabOut`, so the sample-wise difference of the two body buffers is the tarab alone. Over 0.1–0.6 s of the pre-normalise body (the `Pluck.ks` plucks decay), Hann FFT at 0.67 Hz:

| TUNE | f0 | body global peak (S1) | tarab global peak | tarab peak re body's f0 line | 130.8 | 147.2 | 163.5 | 174.4 | 196.2 | 218.1 | 245.3 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 130.81 | 206.67 Hz | 490.59 Hz | −12.8 dB | 130.55 (−25.1) / +0.1 | 147.37 (−14.0) / **+10.0** | 163.52 (−22.7) / −3.2 | 174.28 (−35.6) / +0.2 | 196.49 (−19.5) / +2.6 | 218.02 (−33.4) / 0.0 | 244.94 (−19.3) / 0.0 |
| 0.5 | 261.62 | 130.10 Hz | 490.59 Hz | +1.9 dB | 130.55 (−10.4) / +0.1 | 147.37 (+0.7) / −0.8 | 163.52 (−8.0) / +4.1 | 174.28 (−20.9) / +0.4 | 196.49 (−4.7) / +0.5 | 218.02 (−18.7) / 0.0 | 244.94 (−4.6) / +0.1 |
| 1 | 523.24 | 129.08 Hz | 490.59 Hz | +3.5 dB | 130.55 (−8.8) / 0.0 | 147.37 (+2.3) / +1.3 | 163.52 (−6.4) / −1.2 | 174.28 (−19.3) / −0.8 | 196.49 (−3.1) / −0.2 | 218.02 (−17.1) / +0.3 | 244.94 (−3.0) / −0.3 |

Each cell: the tarab difference's peak nearest 130.81·ratio in Hz (dB re the body's line at f0), then the full body's level at that frequency at SYMPATHY 1 minus SYMPATHY 0. **The seven lines sit at the same seven frequencies at every TUNE** (130.55 / 147.37 / 163.52 / 174.28 / 196.49 / 218.02 / 244.94 Hz — all within the bin's ±4 c of 130.81·ratio) and so does their strongest component, 490.59 Hz (= 130.81 × 3.75, the 1.875 string's second harmonic). In the mixed body they are invisible to within ±1.3 dB except 147 Hz at TUNE 0 (+10 dB) and 163.5 Hz at TUNE 0.5 (+4.1 dB): `tarabOut` is `symp · sympathy · 0.25` against a body whose chaos peaks at 1.2. Seeds: `seed + (r·100).toInt()` with the unresolved `seed = 0` → 100, 112, 125, 133, 150, 166, 187, the same on every patch. The body's global peak at TUNE 0.5 and 1 (130.10 / 129.08 Hz) is the fixed 130 Hz membrane mode, not the string.

### What the spec promised versus what it renders

| Claim | Where | Rendered |
|---|---|---|
| "Helmholtz motion … self-sustained non-linear friction" (§1) | v1 p.1, the friction loop p.6–7 | No periodic motion at any corner: r(D) = −0.17…−0.21, ZC 12–24 kHz, Pitch null. The loop injects `pressure·μ(Δv)` as an *additive* term into a single 0.985-feedback line — the friction is a force added to a velocity wave with no impedance, no bow–string junction, no second travelling direction (STK adds `Δv·bowTable(Δv)` to *both* the neck and bridge lines and reads the string velocity as the sum of two inverted reflections, `stk_Bowed.h:104-116`; its table returns a reflection coefficient clamped to [0.01, 0.98], `stk_BowTable.h:84-99`). The map that results has no fixed point (H2) and is chaotic. |
| `drumClassFor`: TONAL below DECAY 0.65, LOOP above | v1 p.5, v2 p.7 | Classifier at defaults: PERC (CELLO, CONTRABASS), SNARE (ERHU, SARANGI, GURDY); TONAL only where the DC-heavy first 93 ms reads as bass (CELLO TUNE 0 and BOW 1; CONTRABASS TUNE 0, BOW 1, ROSIN 0 and BOW 1+ROSIN 1); CLAP at CONTRABASS TUNE 1 and SARANGI BOW 1 / ROSIN 1 (bursty *and* flat — a noise); LOOP at DECAY 1 because the 3.5 s clip exceeds `LOOP_MIN_SECONDS = 1.5` (`Classifier.kt:72`) — the rule fires on length before any spectrum is read, so the prediction is right for a reason unrelated to the sound; DECAY 0.66–1 would predict LOOP for 1.06–1.5 s clips the Classifier would not call LOOP. |
| CONTRABASS "30–60 ms for Helmholtz stick-slip to stabilize" | v1 p.2 | onset90 (20 Hz DC-blocked raw): 52.5 ms at defaults, 102.5 at TUNE 0, 87.5 at BOW 1, 62.5 at ROSIN 0 (80 ms ramp), 42.5 at ROSIN 1 (20 ms ramp). The number at defaults is inside the claim, but it is the 50 ms linear pressure ramp (`attackFrames`) plus the map's transient (~4 iterations of D — 12 ms at 82 Hz, 24 ms at 41 Hz), not stick-slip settling: there is no stick-slip. |
| "Subharmonic bifurcation … period-doubling" under hard bow pressure | v1 p.2 | f0/2 relative to h1 at BOW 1 + ROSIN 1: −6.4 / −13.6 / −1.3 / +1.9 / −3.5 dB (CELLO…GURDY) on a spectrum whose h2…h6 are themselves within ±7 dB of h1 — the value at f0/2 is the noise floor. No period doubling and no period. |
| BOW "sets v_bow and dynamic air turbulence injection" | v1 p.3 | `prng` is constructed and never read. BOW scales v_bow and, through it, the map's DC: raw mean 0.094 / 0.884 / 1.393 at BOW 0 / 0.6 / 1. |
| "Phase-locked saw tap … taps the fundamental f0 of the physical waveguide" | v2 p.1 | The saw's step is `f0 / renderRate` from `frequencyFor`: locked to the macro, not to the string. It is the only in-tune line in the render (D). Nothing in the waveguide is periodic for it to lock to. |
| "Net pitch deviation across the three taps sums to zero … eliminates seasick pitch wobbling" | v2 p.2 | Three-tap sum: 0 (F3). L and R each carry two taps: pitch sd 32 / 28 c per 100 ms window, excursions to +89 / −87 c; the fold sd 18 c, ±48 c (F1). |
| "1.72 s common multiple of 0.58 Hz and 5.85 Hz" | v2 p.13 | 0.9999 and 10.085 cycles; the common period is 100 s (F3). |
| Mono fold within 1.2 dB of the stereo energy | v2 p.13 | Stage alone: −0.76 dB (sine, 1.0), −0.48 dB (noise, 0.5), **−1.22 dB** (noise, 0.9). Engine: 0.00 dB, because the Snip is mono to −43 dB (F4). |
| Stereo output | v2 p.9–10 | `Tide.bandLimit(stereoEnsemble, renderRate)` walks a mono filter over the interleaved buffer: −12.5 / −8.4 dB of stereo difference becomes −43.7 / −42.9 dB, and two identical channels at SOLINA 0 acquire a −35 dB difference. `normalizeByFold`, `Punch.applyOversampled`, `fadeTail` and `Snip` do take `channels = 2`. |
| Solina formant curves: Cello 320 Hz Q 1.2 with −12 dB shelf above 1.2 kHz; Viola 780 Hz + 1.8 kHz; Violin 2.6 kHz with bass cut below 400 Hz | v2 p.2 | Two peaking filters: 380 Hz +6 dB Q 1.2 and 2400 Hz +5 dB Q 1.5; no shelf, no viola, no bass cut. |
| BBD: D0 7.0 ms, A_slow 2.5 ms, A_fast 0.4 ms | v2 p.2 | Code 7.5 / 2.8 / 0.45 ms. |
| DC | nowhere | Raw mean/peak 0.46 at defaults, 0.56 at BOW 1; finished Snip mean +0.43…+0.53 on a 0.95 peak for CELLO/CONTRABASS at every corner but BOW 0 (+0.08); GURDY BOW 0 −0.22. No DC blocker anywhere; `Tide.bandLimit` is a low-pass, `Punch` a transient shaper, `decimate` linear. |
| Output level: `normalizeByFold → Punch.applyOversampled(0.4) → limitPeak` | v1 p.7, v2 p.9 | Peak 0.950 in every finite render (Punch re-levels to the loudness of its own peak-normalised reference, `Punch.kt:270-326`, `rescaleToLoudness` at `:325`); Loudness.of 0.113–0.281 against `MELODIC_LOUDNESS_TARGET` 0.1834 (`Dsp.kt:507`), spread 7.9 dB across the corners. The drum output stage, as BORE found (`Punch.kt:270`; its callers are Thump, Skin, Terra). |

### Prose and code disagree — every place the render exposed

1. **`Dsp.saw` does not exist** (C3), and the GURDY drone is evaluated once outside the loop: a constant −0.99917 × 0.35 = −0.3497 added to every sample (body mean 0.527 = raw 0.877 − 0.350 at SYMPATHY 0). The "D2 bourdon" is a DC offset; the prose's "bourdons tuned to root and fifth (f0 and 1.5·f0)" is neither.
2. **The friction loop is not a waveguide** — one delay line, one direction, no junction, no nut/bridge reflections (the prose: "Bidirectional Digital Waveguide", "String snaps back"); its map has no fixed point and no period (H2, Table A).
3. **Pitch does not follow TUNE on any voice.** CELLO/CONTRABASS/GURDY render no line at f0 (H1: the Snip's f0 level is 5.5–13.7 dB under a wandering global peak); ERHU renders 292–293 Hz and SARANGI 129–130 / 206–208 Hz at every TUNE — the `Modes.ring` fundamentals hard-coded at 293 and 130 Hz.
4. **The tarab is at fixed frequencies** — `130.81f * r` in the code against "tuned to the scale degrees" of the main string in the prose — measured at 130.55 … 244.94 Hz at TUNE 0, 0.5 and 1 alike (G); and it is a set of seven decaying `Pluck.ks` plucks with a fixed 2 s budget, not "continuously stimulated by differentiated bridge force"; the prose names `Pluck.sympathetic()`, which is `private` with the signature `(input, hz, rate, coupling, onset, onsetSamples)` (`Pluck.kt:880`). Prose: 35 strings; code: 7. Their seeds depend on nothing in the patch.
5. **BODY is a no-op on ERHU and SARANGI** (`bodyMacro` unread in the membrane branch; BODY 0 and BODY 1 rows identical to every printed digit) and on v1's GURDY (rattle scaled by `sympathy`); it is a linear wet/dry mix on CELLO/CONTRABASS. The prose: "Blends wooden plate SVF filters with Modes.MEMBRANE, equal-power sine crossfade". No SVF is used; the wood body is two RBJ bandpasses.
6. **CELLO body**: prose 98 Hz Q 15 / 220 Hz Q 25 / 340 Hz Q 18; code 104 Hz Q 12 / 220 Hz Q 18, no 340. **CONTRABASS**: prose 58 / 110 / 180 Hz; code 58 Q 12 / 110 Q 18, no 180. **ERHU**: prose "nasal formant 1.2–2.2 kHz", "two strings D4/A4"; code: none, one string. Prose quotes MEMBRANE as "(1.00, 1.59, 2.14, 2.30)"; `Modes.kt:163` holds five ratios 1 / 1.5933 / 2.1354 / 2.2954 / 2.9172 and `resample(…, slots = 5)` uses all five.
7. **Macro maps**: the §3 table says BOW `Dsp.lin(0.1f, 2.5f)` — code `lin(0.1, 1.5)`; ROSIN `Dsp.around(0.2f, 0.5f, 0.95f)` — code `lin(rosin, 0.6, 1.4)` for μS, `expMap(rosin, 3500, 16000)` for the loop low-pass and `lin(1−rosin, 0.02, 0.08)` for the attack (ROSIN is three things); DECAY "tail ring-down after bow release, 0.15–3.5 s" — code: note *duration* `expMap(decay, 0.25, 3.5)` with a fixed 50 ms linear release and no ring-down (v2's prose says 0.25–3.5 s); TUNE "continuous carrier or 24-semitone snapping, `Dsp.expMap`" — code snaps with `Math.round`. SYMPATHY's default 0.3 is not in the table; SOLINA's default is 0.0 while `ArcoPresets` sets it to 0.80–0.95 on five of eleven presets.
8. **ROSIN does not "force a delayed, aggressive crunch"**: at ROSIN 0 (μS 0.6) the raw mean/peak rises to 0.57 and `Pitch.detect` returns an octave *below* f0 with the ZC at 4.5–4.9 kHz; at ROSIN 1 (μS 1.4, loop low-pass 16 kHz) the ZC doubles to 23–24 kHz and r(1) falls 0.90 → 0.62 — ROSIN sets how white the chaos is.
9. **The Solina "passive RLC" prose vs two peaking biquads**, the BBD constants (7.0/2.5/0.4 vs 7.5/2.8/0.45 ms), the three-tap-cancellation claim and the 1.72 s figure (F1–F3), and the stereo chain (F4), as tabled above.
10. **`Punch.applyOversampled(…, amount = 0.4f)`** is described as "Band-limiting & Punch decimation"; it is the drum engines' transient shaper (`Punch.kt:270`), and it leaves the DC and the level spread untouched.
11. **`ArcoPatch : Patch`** cannot live outside `synth/src/main`, and there `Patches.fromJsonValue` must gain an arm (`Patches.kt:36-57`); `Velocity.macroSpecsFor` is an exhaustive `when` that will not compile without an `ArcoPatch` arm (BORE's api §6.3). Not rendered; the compiler said so (C1).
12. **Cost prose absent**; measured 35–72 ms/s (v1), 68–79 (v2 dry), 91–122 (v2 SOLINA 1).

### Iteration log

| Run | What ran | Result | What changed after |
|---|---|---|---|
| 1 | v1 + v2 transcriptions, ArcoProbeTest with tests A–G; first gradle run in the worktree (full module compile) | exit 0 in 2 m 48 s; all 140 renders finite; no note anywhere; onset90 read 2.5 ms on every row | onset was measured on a mean-removed buffer, so the 46 % DC step at t = 0 read as the onset → replaced by a 20 Hz one-pole DC blocker before the RMS windows. Added: the Snip's mean (DC) column; r(1)/r(D) autocorrelation; test H (H1 "where the note is", H2 the scalar map); test G rewritten to measure the tarab as body(S1) − body(S0) rather than through the mixed body, where it was under the chaos. |
| 2 | the same command | exit 0 in 2 m 04 s; every non-timing number of run 1 reproduced to the printed digit | none. Tables in `empirical-tables.txt`; run 1 kept as `empirical-tables-run1.txt`. |

Nothing in the spec's arithmetic was changed at any point; C1–C3 are the only edits and none touches a number the tables report.

### Constants used by the probe (not the spec's)

Analysis windows: 200 ms before the release (pitch, autocorrelation, FFT), 100 ms before the release (harmonics), Snip from 0.1 s (H1, D). FFT 2¹⁸ points Hann, parabolic peak. Goertzel: Hann, ±3 % search in 25 steps (±1.5 % for the tarab, ±0.5 % for the Snip's global peak). DC blocker 20 Hz one-pole; onset windows 5 ms; "steady" = RMS of the middle third of the note. Pitch.detect windows as stated per column. Map scan [−3, 6] in 0.0005 steps; orbit 20 000 steps from 0, statistics over steps 2 000–20 000. SOLINA stand-alone tests at 44 100 Hz: sine 220 Hz, 0.5 amplitude, 2 s; noise `Dsp.Noise(1234)` × 0.3, 4 s; Welch 16384-point Hann at 8192 hop, 15 hops; short windows 4096 at eight equally spaced starts; comb spacing 133.333 Hz, ±1 bin at peaks and troughs. Cost: median of three after one warm-up. Reference frequencies always `Arco.frequencyFor(voice, tune)`.

## Appendix E — `ArcoProbeV1.kt`, the transcription of part one

```kotlin
package com.snipsnap.synth

// EMPIRICAL PROBE - the ARCO spec part 1 (Arco.kt v1, SYMPATHY + SARANGI
// tarab) transcribed as written from
// scratchpad/spec-arco-v1-bowed.txt (pages 4-9), renamed so that v1 and v2
// compile side by side. Every departure from the spec's text is marked
// "// C<n>:" and listed in the empirical report. Not for the build.

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Shared by both transcriptions (the spec declares it once, in part 1). */
enum class ArcoVoice {
    CELLO,
    CONTRABASS,
    ERHU,
    SARANGI,
    GURDY
}

// C1: the spec's `data class ArcoPatch(...) : Patch` cannot be compiled from
// the test source set - `Patch` is a sealed interface (Patches.kt:14) and
// Kotlin requires a sealed type's direct subtypes to sit in the same module
// AND package; the test source set is a separate compilation. The class is
// kept in its shape without `: Patch` / `override`, and the `init` block's
// `Patches.validateMacros(this, ...)` (which takes a `Patch`,
// Patches.kt:94) is replaced by the same three checks inlined (:95-99).
data class ArcoPatchV1(
    val name: String,
    val voice: ArcoVoice,
    val macros: Map<String, Float>,
) {
    init {
        require(name.isNotBlank()) { "patch name must not be blank" }
        val names = ArcoV1.macrosFor(voice).map { it.name }.toSet()
        for ((k, v) in macros) {
            require(k in names) { "unknown macro $k for ${voice.name} (knows $names)" }
            require(v in 0f..1f) { "macro $k out of 0..1: $v" }
        }
    }

    val engine: String get() = ENGINE
    val voiceName: String get() = voice.name
    fun render(): Snip = ArcoV1.render(voice, macros)
    fun withMacros(macros: Map<String, Float>): ArcoPatchV1 = copy(macros = macros)

    companion object {
        const val ENGINE = "ARCO"
        const val VERSION = Patches.VERSION
    }
}

/** What the probe reads: the raw loop, the body output before normalising, and the Snip. */
class ArcoV1Debug(
    /** The stick-slip loop's own output, 176.4 kHz, before the body. */
    val raw: FloatArray,
    /** applyAcousticBody's output, 176.4 kHz, before bandLimit / normalizeByFold / Punch. */
    val body: FloatArray,
    val snip: Snip,
    val f0: Float,
    val delaySamples: Float,
    val attackFrames: Int,
    val releaseFrames: Int,
    val totalFrames: Int,
)

object ArcoV1 {
    const val TUNE_SEMITONES = 24

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("BOW", 0.6f),
        MacroSpec("ROSIN", 0.5f, neutral = 0.5f),
        MacroSpec("BODY", 0.5f),
        MacroSpec("DECAY", 0.5f),
        MacroSpec("SYMPATHY", 0.3f),
    )

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: ArcoVoice): List<MacroSpec> = MACROS

    fun defaults(voice: ArcoVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun drumClassFor(voice: ArcoVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val decay = macros["DECAY"] ?: defaults(voice).getValue("DECAY")
        return if (decay > 0.65f) DrumClass.LOOP else DrumClass.TONAL
    }

    fun rootHz(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CONTRABASS -> 41.20f // E1
        ArcoVoice.CELLO -> 65.41f // C2
        ArcoVoice.SARANGI -> 130.81f // C3
        ArcoVoice.GURDY -> 146.83f // D3
        ArcoVoice.ERHU -> 293.66f // D4
    }

    fun frequencyFor(voice: ArcoVoice, tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return rootHz(voice) * 2f.pow(semis / 12f)
    }

    fun render(
        voice: ArcoVoice,
        macros: Map<String, Float> = emptyMap(),
        seed: Int = 0,
    ): Snip = renderDebug(voice, macros, seed).snip // C2: the spec's body moved into renderDebug so the probe can read the core

    fun renderDebug(
        voice: ArcoVoice,
        macros: Map<String, Float> = emptyMap(),
        seed: Int = 0,
    ): ArcoV1Debug {
        val m = defaults(voice) + macros
        val renderRate = RATE * Dsp.OVERSAMPLE // 176.4 kHz internal execution
        val tune = m.getValue("TUNE")
        val bowMacro = m.getValue("BOW")
        val rosin = m.getValue("ROSIN")
        val bodyMacro = m.getValue("BODY")
        val decay = m.getValue("DECAY")
        val sympathy = m.getValue("SYMPATHY")
        val f0 = frequencyFor(voice, tune)
        val durationSec = Dsp.expMap(decay, 0.25f, 3.5f)
        val totalFrames = (durationSec * renderRate).toInt().coerceAtLeast(256)
        val rawBuffer = FloatArray(totalFrames)
        val prng = Dsp.Noise(if (seed == 0) Dsp.seedFor("ARCO", voice, f0) else seed)

        // 1. Digital Waveguide Delay Lines
        val delaySamples = renderRate / f0
        val maxDelay = (delaySamples * 2.0).toInt() + 64
        val delayLine = FloatArray(maxDelay)
        var writeIdx = 0

        // Bow friction curve parameters
        val muS = Dsp.lin(rosin, 0.6f, 1.4f)
        val muD = 0.25f
        val vBow = Dsp.lin(bowMacro, 0.1f, 1.5f)
        val v0 = 0.15f
        val attackFrames = (Dsp.lin(1f - rosin, 0.02f, 0.08f) * renderRate).toInt()
        val releaseFrames = (0.05f * renderRate).toInt()
        val stringFilter = Dsp.OnePole(renderRate)
        val dampingHz = Dsp.expMap(rosin, 3500f, 16000f)

        // 2. Bowed Stick-Slip Synthesis Loop
        for (n in 0 until totalFrames) {
            val t = n.toFloat() / renderRate
            // Bow Normal Pressure Envelope
            val pressure = when {
                n < attackFrames -> (n.toFloat() / attackFrames)
                n > totalFrames - releaseFrames -> (totalFrames - n).toFloat() / releaseFrames
                else -> 1.0f
            }
            // Waveguide read with linear interpolation
            val readD = writeIdx - delaySamples
            var r0 = readD.toInt()
            while (r0 < 0) r0 += maxDelay
            r0 %= maxDelay
            val r1 = (r0 + 1) % maxDelay
            val frac = (readD - readD.toInt()).toFloat()
            val incomingWave = delayLine[r0] + (delayLine[r1] - delayLine[r0]) * frac
            // Relative velocity calculation
            val vString = incomingWave
            val deltaV = (vBow * pressure) - vString
            val sgn = if (deltaV >= 0f) 1f else -1f
            // Rational polynomial friction curve: mu(deltaV)
            val mu = sgn * (muD + (muS - muD) / (1.0f + ((deltaV / v0) * (deltaV / v0))))
            val frictionForce = pressure * mu
            // Excite string & filter high-frequency losses at the nut
            val stringWave = stringFilter.lp(frictionForce + (incomingWave * 0.985f), dampingHz)
            delayLine[writeIdx] = stringWave
            writeIdx = (writeIdx + 1) % maxDelay
            rawBuffer[n] = stringWave
        }

        // 3. Acoustic Resonator Body Coupling
        val bodyFiltered = applyAcousticBody(rawBuffer, voice, bodyMacro, sympathy, renderRate, seed)
        val bodyCopy = bodyFiltered.copyOf() // C2: probe copy before the in-place stages

        // 4. Band-limiting & Punch decimation
        Tide.bandLimit(bodyFiltered, renderRate)
        Dsp.normalizeByFold(bodyFiltered, channels = 1)
        val decimated = Punch.applyOversampled(bodyFiltered, amount = 0.4f, RATE, channels = 1)
        Dsp.limitPeak(decimated)
        Dsp.fadeTail(decimated, ms = 4f, rate = RATE, channels = 1)
        val snip = Snip(decimated, channels = 1, sampleRate = RATE)
        return ArcoV1Debug(rawBuffer, bodyCopy, snip, f0, delaySamples, attackFrames, releaseFrames, totalFrames)
    }

    // C3: the spec calls `Dsp.saw(phase)`; no such function exists in Dsp.kt
    // (only `Dsp.square`, Dsp.kt:181). Four engines carry their own private
    // saw of the same shape (Fathom.kt:140, Resin.kt:146, ResinDrone.kt:94,
    // Velvet.kt:118); this is Fathom's, verbatim.
    private fun saw(phase: Double): Float = (2.0 * (phase - Math.floor(phase)) - 1.0).toFloat()

    private fun applyAcousticBody(
        input: FloatArray,
        voice: ArcoVoice,
        bodyMacro: Float,
        sympathy: Float,
        rate: Int,
        seed: Int
    ): FloatArray {
        val total = input.size
        val output = FloatArray(total)

        // Differentiate bridge velocity to cancel 1/sin(theta) onset hazard (Pluck precedent)
        val drive = FloatArray(total)
        var prev = 0f
        for (i in 0 until total) {
            drive[i] = input[i] - prev
            prev = input[i]
        }

        when (voice) {
            ArcoVoice.CELLO, ArcoVoice.CONTRABASS -> {
                // Carved Wood Body: Air Cavity + Wood Formants
                val airFreq = if (voice == ArcoVoice.CELLO) 104f else 58f
                val woodFreq = if (voice == ArcoVoice.CELLO) 220f else 110f
                val airRes = Dsp.Biquad().apply { bandpass(airFreq, 12f, rate) }
                val woodRes = Dsp.Biquad().apply { bandpass(woodFreq, 18f, rate) }
                for (n in 0 until total) {
                    val s = drive[n]
                    val body = (airRes.process(s) * 0.6f) + (woodRes.process(s) * 0.4f)
                    output[n] = (input[n] * (1f - bodyMacro * 0.5f)) + (body * bodyMacro * 1.8f)
                }
            }
            ArcoVoice.ERHU, ArcoVoice.SARANGI -> {
                // Stretched Membrane Body via Modes.kt (Goatskin/Python skin)
                val membraneModes = Modes.resample(Modes.Material.MEMBRANE, slots = 5)
                val rung = Modes.ring(drive, if (voice == ArcoVoice.ERHU) 293f else 130f, membraneModes, rate)
                Dsp.normalize(rung, 1.0f)
                // Sympathetic Strings (Tarab) on Sarangi
                val tarabOut = FloatArray(total)
                if (voice == ArcoVoice.SARANGI && sympathy > 0.01f) {
                    val ratios = floatArrayOf(1.0f, 1.125f, 1.25f, 1.333f, 1.5f, 1.667f, 1.875f)
                    for (r in ratios) {
                        val symp = Pluck.ks(
                            freq = 130.81f * r,
                            seconds = 2.0f,
                            damp = 0.2f,
                            bodyLoopHz = 5000f,
                            pickHz = 8000f,
                            seed = seed + (r * 100).toInt(),
                            rate = rate
                        )
                        val len = minOf(total, symp.size)
                        for (i in 0 until len) tarabOut[i] += symp[i] * (sympathy * 0.25f)
                    }
                }
                for (n in 0 until total) {
                    output[n] = (input[n] * 0.4f) + (rung[n] * 0.6f) + tarabOut[n]
                }
            }
            ArcoVoice.GURDY -> {
                // Continuous Drone + Chien (Buzzing Bridge)
                val droneFreq = 73.41f // D2 Bourdon
                val droneWave = saw(droneFreq.toDouble() / rate) // C3: was Dsp.saw(...)
                val buzzingPlate = Dsp.OnePole(rate)
                for (n in 0 until total) {
                    val dry = input[n]
                    // Chien non-linear bridge chatter threshold
                    val rattle = if (abs(dry) > 0.35f && sympathy > 0.01f) {
                        val over = abs(dry) - 0.35f
                        over * over * 2.5f * sympathy
                    } else 0f
                    val filteredRattle = buzzingPlate.lp(rattle, 4500f)
                    output[n] = dry + (droneWave * 0.35f) + filteredRattle
                }
            }
        }
        return output
    }
}
```

## Appendix F — `ArcoProbeV2.kt`, the transcription of part two

```kotlin
package com.snipsnap.synth

// EMPIRICAL PROBE - the ARCO spec part 2 (SolinaEnsemble.kt + Arco.kt v2
// with SOLINA) transcribed as written from
// scratchpad/spec-arco-v2-solina.txt (pages 3-11), renamed so that v1 and
// v2 compile side by side. Every departure from the spec's text is marked
// "// C<n>:" and listed in the empirical report. Not for the build.

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Eminent/Solina String Ensemble processor: tri-phase dual-LFO BBD chorus
 * with classic Solina fixed formant bandpass networks.
 */
object SolinaEnsembleProbe {
    private const val TWO_PI = 2.0 * Math.PI
    private const val PHASE_120 = 2.0 * Math.PI / 3.0
    private const val PHASE_240 = 4.0 * Math.PI / 3.0
    private const val SLOW_LFO_HZ = 0.58
    private const val FAST_LFO_HZ = 5.85
    private const val BASELINE_DELAY_SEC = 0.0075f
    private const val SLOW_DEPTH_SEC = 0.0028f
    private const val FAST_DEPTH_SEC = 0.00045f

    class State(rate: Int = RATE) {
        val maxDelayFrames = (0.025f * rate).toInt()
        val delay0 = FloatArray(maxDelayFrames)
        val delay1 = FloatArray(maxDelayFrames)
        val delay2 = FloatArray(maxDelayFrames)
        var writeIdx = 0
        val bbdFilter0 = Dsp.OnePole(rate)
        val bbdFilter1 = Dsp.OnePole(rate)
        val bbdFilter2 = Dsp.OnePole(rate)
        // Solina Cello & Violin passive formant peaking filters
        val celloPeak = Dsp.Biquad().apply { peaking(380f, 6f, 1.2f, rate) }
        val violinPeak = Dsp.Biquad().apply { peaking(2400f, 5f, 1.5f, rate) }
    }

    /**
     * Applies the Solina string ensemble transformation to [input].
     * Returns an interleaved stereo FloatArray [L, R, L, R, ...].
     */
    fun process(
        input: FloatArray,
        amount: Float,
        rate: Int,
        state: State = State(rate),
    ): FloatArray {
        if (amount <= 0.001f) {
            // Dry pass-through duplicated to stereo
            val monoOut = FloatArray(input.size * 2)
            for (i in input.indices) {
                monoOut[i * 2] = input[i]
                monoOut[i * 2 + 1] = input[i]
            }
            return monoOut
        }
        val totalFrames = input.size
        val output = FloatArray(totalFrames * 2)
        val maxDelay = state.maxDelayFrames
        val baseDelay = BASELINE_DELAY_SEC * rate
        val slowDepth = SLOW_DEPTH_SEC * rate * amount
        val fastDepth = FAST_DEPTH_SEC * rate * amount
        val slowStep = TWO_PI * SLOW_LFO_HZ / rate
        val fastStep = TWO_PI * FAST_LFO_HZ / rate
        val invSqrt2 = 0.70710678f

        for (n in 0 until totalFrames) {
            val t = n.toDouble()
            // 1. Solina Formant Pre-Conditioning
            val raw = input[n]
            val shaped = state.violinPeak.process(state.celloPeak.process(raw))
            val dryWetIn = (raw * (1f - amount * 0.5f)) + (shaped * amount * 0.8f)
            // 2. Dual-LFO Modulation with 120-Degree Spatial Phase Offsets
            val slow0 = sin(slowStep * t)
            val slow1 = sin(slowStep * t + PHASE_120)
            val slow2 = sin(slowStep * t + PHASE_240)
            val fast0 = sin(fastStep * t)
            val fast1 = sin(fastStep * t + PHASE_120)
            val fast2 = sin(fastStep * t + PHASE_240)
            val d0 = baseDelay + slowDepth * slow0.toFloat() + fastDepth * fast0.toFloat()
            val d1 = baseDelay + slowDepth * slow1.toFloat() + fastDepth * fast1.toFloat()
            val d2 = baseDelay + slowDepth * slow2.toFloat() + fastDepth * fast2.toFloat()
            // 3. Write incoming sample to all three BBD delay lines
            state.delay0[state.writeIdx] = dryWetIn
            state.delay1[state.writeIdx] = dryWetIn
            state.delay2[state.writeIdx] = dryWetIn
            // 4. Fractional Delay Read with Linear Interpolation
            val tap0 = readInterpolated(state.delay0, state.writeIdx, d0, maxDelay)
            val tap1 = readInterpolated(state.delay1, state.writeIdx, d1, maxDelay)
            val tap2 = readInterpolated(state.delay2, state.writeIdx, d2, maxDelay)
            // Advance circular buffer
            state.writeIdx = (state.writeIdx + 1) % maxDelay
            // 5. BBD Anti-Aliasing Analog Clock Loss (6.5 kHz rolloff)
            val warm0 = state.bbdFilter0.lp(tap0, 6500f)
            val warm1 = state.bbdFilter1.lp(tap1, 6500f)
            val warm2 = state.bbdFilter2.lp(tap2, 6500f)
            // 6. Stereo Spatial Summing
            val wetL = (warm0 + warm1) * invSqrt2
            val wetR = (warm1 + warm2) * invSqrt2
            val dryL = raw
            val dryR = raw
            output[n * 2] = dryL * (1f - amount) + wetL * amount
            output[n * 2 + 1] = dryR * (1f - amount) + wetR * amount
        }
        return output
    }

    private inline fun readInterpolated(buf: FloatArray, writeIdx: Int, delay: Float, maxDelay: Int): Float {
        val readD = writeIdx - delay
        var r0 = readD.toInt()
        while (r0 < 0) r0 += maxDelay
        r0 %= maxDelay
        val r1 = (r0 + 1) % maxDelay
        val frac = readD - readD.toInt()
        return buf[r0] + (buf[r1] - buf[r0]) * frac
    }
}

/** What the probe reads from v2: the raw loop (+saw), the mono body, the stereo ensemble before bandLimit, the Snip. */
class ArcoV2Debug(
    val raw: FloatArray,
    val body: FloatArray,
    /** Interleaved L/R at 176.4 kHz, before Tide.bandLimit / normalizeByFold / Punch. */
    val stereo: FloatArray,
    val snip: Snip,
    val f0: Float,
    val delaySamples: Float,
    val attackFrames: Int,
    val releaseFrames: Int,
    val totalFrames: Int,
)

object ArcoV2 {
    const val TUNE_SEMITONES = 24

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("BOW", 0.6f),
        MacroSpec("ROSIN", 0.5f, neutral = 0.5f),
        MacroSpec("BODY", 0.5f),
        MacroSpec("DECAY", 0.5f),
        MacroSpec("SOLINA", 0.0f),
    )

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: ArcoVoice): List<MacroSpec> = MACROS

    fun defaults(voice: ArcoVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun drumClassFor(voice: ArcoVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val decay = macros["DECAY"] ?: defaults(voice).getValue("DECAY")
        return if (decay > 0.65f) DrumClass.LOOP else DrumClass.TONAL
    }

    fun rootHz(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CONTRABASS -> 41.20f // E1
        ArcoVoice.CELLO -> 65.41f // C2
        ArcoVoice.SARANGI -> 130.81f // C3
        ArcoVoice.GURDY -> 146.83f // D3
        ArcoVoice.ERHU -> 293.66f // D4
    }

    fun frequencyFor(voice: ArcoVoice, tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return rootHz(voice) * 2f.pow(semis / 12f)
    }

    fun render(
        voice: ArcoVoice,
        macros: Map<String, Float> = emptyMap(),
        seed: Int = 0,
    ): Snip = renderDebug(voice, macros, seed).snip // C2: body moved into renderDebug for the probe

    fun renderDebug(
        voice: ArcoVoice,
        macros: Map<String, Float> = emptyMap(),
        seed: Int = 0,
    ): ArcoV2Debug {
        val m = defaults(voice) + macros
        val renderRate = RATE * Dsp.OVERSAMPLE
        val tune = m.getValue("TUNE")
        val bowMacro = m.getValue("BOW")
        val rosin = m.getValue("ROSIN")
        val bodyMacro = m.getValue("BODY")
        val decay = m.getValue("DECAY")
        val solina = m.getValue("SOLINA")
        val f0 = frequencyFor(voice, tune)
        val durationSec = Dsp.expMap(decay, 0.25f, 3.5f)
        val totalFrames = (durationSec * renderRate).toInt().coerceAtLeast(256)
        val rawBuffer = FloatArray(totalFrames)
        val prng = Dsp.Noise(if (seed == 0) Dsp.seedFor("ARCO", voice, f0) else seed)

        // 1. Waveguide Delay Sizing
        val delaySamples = renderRate / f0
        val maxDelay = (delaySamples * 2.0).toInt() + 64
        val delayLine = FloatArray(maxDelay)
        var writeIdx = 0

        // Bow friction curve coefficients
        val muS = Dsp.lin(rosin, 0.6f, 1.4f)
        val muD = 0.25f
        val vBow = Dsp.lin(bowMacro, 0.1f, 1.5f)
        val v0 = 0.15f
        val attackFrames = (Dsp.lin(1f - rosin, 0.02f, 0.08f) * renderRate).toInt()
        val releaseFrames = (0.05f * renderRate).toInt()
        val stringFilter = Dsp.OnePole(renderRate)
        val dampingHz = Dsp.expMap(rosin, 3500f, 16000f)

        // Solina divide-down saw phase accumulator
        var sawPhase = 0.0
        val sawStep = f0.toDouble() / renderRate

        // 2. Physical Stick-Slip Simulation Loop
        for (n in 0 until totalFrames) {
            val pressure = when {
                n < attackFrames -> (n.toFloat() / attackFrames)
                n > totalFrames - releaseFrames -> (totalFrames - n).toFloat() / releaseFrames
                else -> 1.0f
            }
            // Waveguide read with linear interpolation
            val readD = writeIdx - delaySamples
            var r0 = readD.toInt()
            while (r0 < 0) r0 += maxDelay
            r0 %= maxDelay
            val r1 = (r0 + 1) % maxDelay
            val frac = (readD - readD.toInt()).toFloat()
            val incomingWave = delayLine[r0] + (delayLine[r1] - delayLine[r0]) * frac
            // Non-linear friction calculation
            val vString = incomingWave
            val deltaV = (vBow * pressure) - vString
            val sgn = if (deltaV >= 0f) 1f else -1f
            val mu = sgn * (muD + (muS - muD) / (1.0f + ((deltaV / v0) * (deltaV / v0))))
            val frictionForce = pressure * mu
            // Nut loss filter
            val stringWave = stringFilter.lp(frictionForce + (incomingWave * 0.985f), dampingHz)
            delayLine[writeIdx] = stringWave
            writeIdx = (writeIdx + 1) % maxDelay
            // Solina Top-Octave Divider Sawtooth Injection
            var sample = stringWave
            if (solina > 0.01f) {
                sawPhase += sawStep
                if (sawPhase >= 1.0) sawPhase -= 1.0
                val rawSaw = (2.0 * sawPhase - 1.0).toFloat()
                sample = (stringWave * (1f - solina * 0.4f)) + (rawSaw * pressure * solina * 0.5f)
            }
            rawBuffer[n] = sample
        }

        // 3. Resonator Body Coupling
        val bodyFiltered = applyAcousticBody(rawBuffer, voice, bodyMacro, renderRate, seed)

        // 4. Solina Tri-Phase BBD Ensemble Stage
        val stereoEnsemble = SolinaEnsembleProbe.process(bodyFiltered, solina, renderRate)
        val stereoCopy = stereoEnsemble.copyOf() // C2: probe copy before the in-place stages

        // 5. Band-Limiting, Decimation, and Punch Dynamics
        Tide.bandLimit(stereoEnsemble, renderRate)
        Dsp.normalizeByFold(stereoEnsemble, channels = 2)
        val decimated = Punch.applyOversampled(stereoEnsemble, amount = 0.4f, RATE, channels = 2)
        Dsp.limitPeak(decimated)
        Dsp.fadeTail(decimated, ms = 4f, rate = RATE, channels = 2)
        val snip = Snip(decimated, channels = 2, sampleRate = RATE)
        return ArcoV2Debug(rawBuffer, bodyFiltered, stereoCopy, snip, f0, delaySamples, attackFrames, releaseFrames, totalFrames)
    }

    // C3: `Dsp.saw` does not exist; Fathom.kt:140's private saw, verbatim.
    private fun saw(phase: Double): Float = (2.0 * (phase - Math.floor(phase)) - 1.0).toFloat()

    private fun applyAcousticBody(
        input: FloatArray,
        voice: ArcoVoice,
        bodyMacro: Float,
        rate: Int,
        seed: Int
    ): FloatArray {
        val total = input.size
        val output = FloatArray(total)

        // Differentiate bridge velocity to cancel 1/sin(theta) onset hazard
        val drive = FloatArray(total)
        var prev = 0f
        for (i in 0 until total) {
            drive[i] = input[i] - prev
            prev = input[i]
        }

        when (voice) {
            ArcoVoice.CELLO, ArcoVoice.CONTRABASS -> {
                val airFreq = if (voice == ArcoVoice.CELLO) 104f else 58f
                val woodFreq = if (voice == ArcoVoice.CELLO) 220f else 110f
                val airRes = Dsp.Biquad().apply { bandpass(airFreq, 12f, rate) }
                val woodRes = Dsp.Biquad().apply { bandpass(woodFreq, 18f, rate) }
                for (n in 0 until total) {
                    val s = drive[n]
                    val body = (airRes.process(s) * 0.6f) + (woodRes.process(s) * 0.4f)
                    output[n] = (input[n] * (1f - bodyMacro * 0.5f)) + (body * bodyMacro * 1.8f)
                }
            }
            ArcoVoice.ERHU, ArcoVoice.SARANGI -> {
                val membraneModes = Modes.resample(Modes.Material.MEMBRANE, slots = 5)
                val rung = Modes.ring(drive, if (voice == ArcoVoice.ERHU) 293f else 130f, membraneModes, rate)
                Dsp.normalize(rung, 1.0f)
                for (n in 0 until total) {
                    output[n] = (input[n] * 0.4f) + (rung[n] * 0.6f)
                }
            }
            ArcoVoice.GURDY -> {
                val droneFreq = 73.41f // D2 Bourdon
                val droneWave = saw(droneFreq.toDouble() / rate) // C3: was Dsp.saw(...)
                val buzzingPlate = Dsp.OnePole(rate)
                for (n in 0 until total) {
                    val dry = input[n]
                    val rattle = if (abs(dry) > 0.35f) {
                        val over = abs(dry) - 0.35f
                        over * over * 2.5f * bodyMacro
                    } else 0f
                    val filteredRattle = buzzingPlate.lp(rattle, 4500f)
                    output[n] = dry + (droneWave * 0.35f) + filteredRattle
                }
            }
        }
        return output
    }
}
```

## Appendix G — `ArcoProbeTest.kt`, as run

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * EMPIRICAL PROBE of the ARCO spec's engine as written (ArcoProbeV1 /
 * ArcoProbeV2). Print-only: every measurement is a table row; the only
 * assertion is `assertTrue(true)`. Tables are also written to
 * [REPORT_FILE] so the run's output survives gradle's stdout capture.
 */
class ArcoProbeTest {

    companion object {
        const val REPORT_FILE = "build/arco-probe-tables.txt" // the session wrote this to its scratchpad; a re-run writes under build/
        const val RR = Dsp.RATE * Dsp.OVERSAMPLE // 176400
        private val out = StringBuilder()

        @Synchronized
        fun line(s: String = "") {
            println(s)
            out.append(s).append('\n')
            try {
                val f = File(REPORT_FILE)
                f.parentFile.mkdirs()
                f.writeText(out.toString())
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun f(x: Double, d: Int = 2): String = if (x.isNaN()) "NaN" else if (x.isInfinite()) (if (x > 0) "+inf" else "-inf") else String.format("%.${d}f", x)
    private fun f(x: Float, d: Int = 2): String = f(x.toDouble(), d)
    private fun sci(x: Double): String = if (!x.isFinite()) f(x) else if (abs(x) >= 1000 || (abs(x) < 0.001 && x != 0.0)) String.format("%.2e", x) else f(x, 3)

    private fun cents(hz: Double, want: Double): Double = 1200.0 * ln(hz / want) / ln(2.0)

    /** Index of the first non-finite sample, or -1. */
    private fun firstNonFinite(buf: FloatArray): Int {
        for (i in buf.indices) if (!buf[i].isFinite()) return i
        return -1
    }

    private fun nonFiniteCount(buf: FloatArray): Int { var c = 0; for (v in buf) if (!v.isFinite()) c++; return c }

    /** Copy with every non-finite sample set to 0, so the measurements below stay finite. */
    private fun sanitize(buf: FloatArray): FloatArray = FloatArray(buf.size) { if (buf[it].isFinite()) buf[it] else 0f }

    private fun peakOf(buf: FloatArray): Double { var p = 0.0; for (v in buf) if (v.isFinite() && abs(v) > p) p = abs(v.toDouble()); return p }
    private fun meanOf(buf: FloatArray): Double { var s = 0.0; var n = 0; for (v in buf) if (v.isFinite()) { s += v; n++ }; return if (n == 0) 0.0 else s / n }
    private fun rmsOf(buf: FloatArray): Double { var s = 0.0; var n = 0; for (v in buf) if (v.isFinite()) { s += v.toDouble() * v; n++ }; return if (n == 0) 0.0 else sqrt(s / n) }

    private fun removeMean(x: FloatArray): FloatArray { val m = meanOf(x).toFloat(); return FloatArray(x.size) { x[it] - m } }

    /** x minus a one-pole low-pass at [hz]: a DC blocker, so an onset measure is not fooled by a DC step. */
    private fun dcBlock(x: FloatArray, rate: Int, hz: Double = 20.0): FloatArray {
        val a = (1.0 - exp(-2.0 * PI * hz / rate)).toFloat()
        var lp = 0f
        return FloatArray(x.size) { lp += a * (x[it] - lp); x[it] - lp }
    }

    /** Normalised autocorrelation of [x] at [lag] samples. */
    private fun autocorr(x: FloatArray, lag: Int): Double {
        if (lag <= 0 || lag >= x.size - 1) return Double.NaN
        var e = 0.0; var s = 0.0
        for (i in 0 until x.size - lag) { e += x[i].toDouble() * x[i]; s += x[i].toDouble() * x[i + lag] }
        return if (e <= 0) Double.NaN else s / e
    }

    /** [ms] milliseconds ending at frame [end] (exclusive). */
    private fun sliceEndingAt(buf: FloatArray, end: Int, rate: Int, ms: Double): FloatArray {
        val n = (ms / 1000.0 * rate).toInt()
        val e = end.coerceIn(0, buf.size)
        val s = (e - n).coerceAtLeast(0)
        return buf.copyOfRange(s, e)
    }

    /** Mean rising-zero-crossing period, linearly interpolated (ForkTest's idiom); null under 3 crossings. */
    private fun zcHz(x: FloatArray, rate: Int): Double? {
        val times = ArrayList<Double>()
        for (i in 1 until x.size) {
            if (x[i - 1] < 0f && x[i] >= 0f) {
                val fr = x[i - 1] / (x[i - 1] - x[i])
                times += (i - 1) + fr.toDouble()
            }
        }
        if (times.size < 3) return null
        val span = times.last() - times.first()
        return rate / (span / (times.size - 1))
    }

    /** Hann-windowed magnitude spectrum, zero-padded to 2^18 points. */
    private fun spectrum(x: FloatArray, n: Int = 1 shl 18): DoubleArray {
        val re = FloatArray(n)
        val im = FloatArray(n)
        val len = min(x.size, n)
        for (i in 0 until len) {
            val w = 0.5f - 0.5f * cos(2.0 * PI * i / (len - 1)).toFloat()
            re[i] = x[i] * w
        }
        Fft.forward(re, im)
        return DoubleArray(n / 2) { hypot(re[it].toDouble(), im[it].toDouble()) }
    }

    /** Loudest bin above [minHz], parabolic-interpolated. */
    private fun fftPeakHz(mag: DoubleArray, rate: Int, minHz: Double = 20.0, maxHz: Double = 20000.0): Double {
        val n = mag.size * 2
        val binHz = rate.toDouble() / n
        val lo = (minHz / binHz).toInt().coerceAtLeast(1)
        val hi = (maxHz / binHz).toInt().coerceAtMost(mag.size - 2)
        var best = lo; var bm = -1.0
        for (b in lo..hi) if (mag[b] > bm) { bm = mag[b]; best = b }
        val a = mag[best - 1]; val c = mag[best + 1]; val d = a - 2 * bm + c
        val delta = if (d != 0.0) 0.5 * (a - c) / d else 0.0
        return (best + delta) * binHz
    }

    /** Local maxima within ±[span] of [nearHz], strongest first: (Hz, dB re the strongest). */
    private fun peaksNear(mag: DoubleArray, rate: Int, nearHz: Double, span: Double, count: Int = 3): List<Pair<Double, Double>> {
        val n = mag.size * 2
        val binHz = rate.toDouble() / n
        val lo = ((nearHz * (1 - span)) / binHz).toInt().coerceAtLeast(2)
        val hi = ((nearHz * (1 + span)) / binHz).toInt().coerceAtMost(mag.size - 3)
        val found = ArrayList<Pair<Double, Double>>()
        for (b in lo..hi) if (mag[b] > mag[b - 1] && mag[b] >= mag[b + 1]) found += (b * binHz) to mag[b]
        val top = found.sortedByDescending { it.second }.take(count)
        val ref = top.firstOrNull()?.second ?: 1.0
        return top.map { it.first to 20 * log10(it.second / ref + 1e-30) }
    }

    /** Hann-windowed Goertzel magnitude at [hz]. */
    private fun goertzel(x: FloatArray, hz: Double, rate: Int): Double {
        val n = x.size
        val w = 2.0 * PI * hz / rate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) {
            val win = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            val s = x[i] * win + coeff * s1 - s2
            s2 = s1; s1 = s
        }
        return sqrt(abs(s1 * s1 + s2 * s2 - coeff * s1 * s2))
    }

    /** Strongest Goertzel within ±[span] of [hz] (25 steps) - the spike's "peak within ±3 %". */
    private fun peakAmp(x: FloatArray, hz: Double, rate: Int, span: Double = 0.03, steps: Int = 24): Double {
        var best = 0.0
        for (s in 0..steps) {
            val h = hz * (1 - span + 2 * span * s / steps)
            if (h <= 0 || h >= rate / 2.0) continue
            val g = goertzel(x, h, rate)
            if (g > best) best = g
        }
        return best
    }

    private fun db(a: Double, ref: Double): Double = 20 * log10((a + 1e-30) / (ref + 1e-30))

    /** Least-squares slope of dB against log2(k). */
    private fun slopeDbPerOct(dbs: List<Double>): Double {
        val pts = dbs.mapIndexedNotNull { i, v -> if (v.isFinite()) (ln((i + 1).toDouble()) / ln(2.0)) to v else null }
        if (pts.size < 2) return Double.NaN
        val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
        var num = 0.0; var den = 0.0
        for ((x, y) in pts) { num += (x - mx) * (y - my); den += (x - mx) * (x - mx) }
        return if (den == 0.0) Double.NaN else num / den
    }

    /** First 5 ms window whose RMS reaches 90 % of the mid-note RMS, in ms; -1 never. */
    private fun onset90Ms(ac: FloatArray, rate: Int, steadyFrom: Int, steadyTo: Int): Double {
        if (steadyTo <= steadyFrom + 16) return -1.0
        val steady = rmsOf(ac.copyOfRange(steadyFrom, steadyTo))
        if (steady <= 1e-9) return -1.0
        val win = (0.005 * rate).toInt()
        var s = 0
        while (s + win <= ac.size) {
            val r = rmsOf(ac.copyOfRange(s, s + win))
            if (r >= 0.9 * steady) return (s + win / 2.0) / rate * 1000
            s += win
        }
        return -1.0
    }

    private fun pitchOf(x: FloatArray, rate: Int): Double? {
        if (x.size < 2400) return null
        val est = Pitch.detect(Snip(x, channels = 1, sampleRate = rate), fromSec = 0f, windowSec = x.size.toFloat() / rate)
        return est?.hz?.toDouble()
    }

    private fun centsStr(hz: Double?, want: Double): String = if (hz == null) "null" else "${f(hz, 1)} (${f(cents(hz, want), 1)} c)"

    private fun onePoleLagSamples(cutoffHz: Double, hz: Double, rate: Int): Double {
        val a = 1.0 - exp(-2.0 * PI * min(cutoffHz, rate * 0.45) / rate)
        val w = 2.0 * PI * hz / rate
        val r = 1 - a
        val phase = atan2(r * sin(w), 1 - r * cos(w))
        return phase / w
    }

    private fun mono(s: Snip): FloatArray = if (s.channels == 1) s.samples else Cleanup.toMono(s).samples
    private fun channel(inter: FloatArray, c: Int): FloatArray = FloatArray(inter.size / 2) { inter[it * 2 + c] }

    private fun stereoDiffDb(inter: FloatArray): Double {
        val l = channel(inter, 0); val r = channel(inter, 1)
        var d = 0.0; var s = 0.0
        for (i in l.indices) { val a = (l[i] - r[i]).toDouble(); val b = (l[i] + r[i]).toDouble(); d += a * a; s += b * b }
        return 10 * log10((d + 1e-30) / (s + 1e-30))
    }

    // ---------------------------------------------------------------- one render, measured

    private class Core(
        val raw: FloatArray, val body: FloatArray, val snip: Snip, val f0: Float, val delaySamples: Float,
        val attackFrames: Int, val releaseFrames: Int, val totalFrames: Int, val stereo: FloatArray?,
    )

    private fun renderV1(voice: ArcoVoice, m: Map<String, Float>): Core {
        val d = ArcoV1.renderDebug(voice, m)
        return Core(d.raw, d.body, d.snip, d.f0, d.delaySamples, d.attackFrames, d.releaseFrames, d.totalFrames, null)
    }

    private fun renderV2(voice: ArcoVoice, m: Map<String, Float>): Core {
        val d = ArcoV2.renderDebug(voice, m)
        return Core(d.raw, d.body, d.snip, d.f0, d.delaySamples, d.attackFrames, d.releaseFrames, d.totalFrames, d.stereo)
    }

    private fun measure(label: String, c: Core, predicted: String, v2: Boolean): Pair<String, String> {
        val rate = RR
        val f0 = c.f0.toDouble()
        val nf = firstNonFinite(c.raw)
        val nfBody = firstNonFinite(c.body)
        val finiteStr = (if (nf < 0) "yes" else "NO @${f(nf * 1000.0 / rate, 1)}ms (${nonFiniteCount(c.raw)})") +
            (if (nfBody < 0) "" else " body NO @${f(nfBody * 1000.0 / rate, 1)}ms")
        val raw = sanitize(c.raw)
        val body = sanitize(c.body)
        val rawPeak = peakOf(raw); val rawMean = meanOf(raw)
        val dcFrac = if (rawPeak > 0) rawMean / rawPeak else 0.0
        val sustainEnd = (c.totalFrames - c.releaseFrames).coerceAtLeast(1)
        val last200 = removeMean(sliceEndingAt(raw, sustainEnd, rate, 200.0))
        val last100 = removeMean(sliceEndingAt(raw, sustainEnd, rate, 100.0))
        val pHouse = pitchOf(last200, rate)
        val pZc = zcHz(last200, rate)
        val mag = spectrum(last200)
        val pFft = fftPeakHz(mag, rate)
        val agree = when {
            pHouse == null && pZc == null -> "neither"
            pHouse == null -> "ZC only"
            pZc == null -> "Pitch only"
            abs(cents(pHouse, pZc)) < 10 -> "yes"
            abs(cents(pHouse, pFft)) < 10 -> "Pitch=FFT"
            abs(cents(pZc, pFft)) < 10 -> "ZC=FFT"
            else -> "no"
        }
        val dRound = Math.round(c.delaySamples)
        val rD = autocorr(last200, dRound)
        val r1 = autocorr(last200, 1)
        val rowA = "| $label | $finiteStr | ${sci(rawPeak)} | ${sci(rawMean)} | ${f(dcFrac)} | ${sci(peakOf(body))} | ${sci(meanOf(body))} | ${centsStr(pHouse, f0)} | ${centsStr(pZc, f0)} | ${f(pFft, 1)} (${f(cents(pFft, f0), 0)} c) | $agree | ${f(r1)} / ${f(rD)} |"

        // harmonics referenced to the FFT global peak when it sits within a fifth of f0, else to f0 itself
        val fRef = if (abs(cents(pFft, f0)) < 700) pFft else f0
        val h = (1..6).map { k -> peakAmp(last100, k * fRef, rate) }
        val hdb = h.map { db(it, h[0]) }
        val slope = slopeDbPerOct(hdb)
        val sub = db(peakAmp(last100, fRef / 2, rate), h[0])
        val ac = dcBlock(raw, rate)
        val onset = onset90Ms(ac, rate, (c.totalFrames * 0.35).toInt(), (c.totalFrames * 0.65).toInt())
        val snip = c.snip
        val feats = FeatureExtractor.extract(snip)
        val cls = Classifier.classify(feats)
        val sp = Pitch.detect(snip)
        val m = mono(snip)
        val snipPeak = peakOf(snip.samples); val snipRms = rmsOf(m); val snipMean = meanOf(m)
        val loud = Loudness.of(snip)
        val stereo = if (v2) " ${f(stereoDiffDb(snip.samples), 1)} |" else ""
        val rowB = "| $label | ${f(fRef, 1)} | ${hdb.drop(1).joinToString(" / ") { f(it, 1) }} | ${f(slope, 1)} | ${f(sub, 1)} | ${if (onset < 0) "never" else f(onset, 1)} | ${f(feats.centroidHz, 0)} / ${f(feats.rolloffHz, 0)} | ${sp?.let { "${f(it.hz, 1)} c${f(it.confidence)}" } ?: "null"} | ${f(snipPeak, 3)} | ${f(snipRms, 3)} | ${f(snipMean, 3)} | ${f(loud, 4)} | ${cls.drumClass} vs $predicted |$stereo"
        return rowA to rowB
    }

    private fun configs(v2: Boolean): List<Pair<String, Map<String, Float>>> {
        val sym = if (v2) "SOLINA" else "SYMPATHY"
        return listOf(
            "defaults" to emptyMap(),
            "TUNE 0" to mapOf("TUNE" to 0f),
            "TUNE 1" to mapOf("TUNE" to 1f),
            "BOW 0" to mapOf("BOW" to 0f),
            "BOW 1" to mapOf("BOW" to 1f),
            "ROSIN 0" to mapOf("ROSIN" to 0f),
            "ROSIN 1" to mapOf("ROSIN" to 1f),
            "BODY 0" to mapOf("BODY" to 0f),
            "BODY 1" to mapOf("BODY" to 1f),
            "DECAY 0" to mapOf("DECAY" to 0f),
            "DECAY 1" to mapOf("DECAY" to 1f),
            "$sym 0" to mapOf(sym to 0f),
            "$sym 1" to mapOf(sym to 1f),
            "BOW 1 ROSIN 1" to mapOf("BOW" to 1f, "ROSIN" to 1f),
        )
    }

    private fun voiceTables(v2: Boolean) {
        val tag = if (v2) "v2 (SOLINA)" else "v1 (SYMPATHY)"
        for (voice in ArcoVoice.entries) {
            line()
            line("### $tag - $voice  (root ${ArcoV1.rootHz(voice)} Hz; TUNE 0.5 -> f0 ${f(ArcoV1.frequencyFor(voice, 0.5f), 2)} Hz)")
            line()
            line("Table A - the core (raw loop at 176.4 kHz; pitch from the 200 ms before the release, mean removed):")
            line()
            line("| config | finite? | raw peak | raw mean | mean/peak | body peak | body mean | Pitch.detect Hz (cents) | zero-crossing Hz (cents) | FFT peak Hz (cents) | agree | autocorr r(1) / r(D) |")
            line("|---|---|---|---|---|---|---|---|---|---|---|---|")
            val rowsB = ArrayList<String>()
            for ((label, m) in configs(v2)) {
                val predicted = (if (v2) ArcoV2.drumClassFor(voice, m) else ArcoV1.drumClassFor(voice, m)).toString()
                try {
                    val c = if (v2) renderV2(voice, m) else renderV1(voice, m)
                    val (a, b) = measure(label, c, predicted, v2)
                    line(a); rowsB += b
                } catch (t: Throwable) {
                    line("| $label | THREW ${t::class.simpleName}: ${t.message?.take(120)} | | | | | | | | | |")
                    rowsB += "| $label | threw | | | | | | | | | | | |"
                }
            }
            line()
            line("Table B - harmonics (last 100 ms before release, peak within ±3 % of k·fRef; fRef = FFT peak if within a fifth of f0, else f0) and the finished Snip:")
            line()
            val stereoHdr = if (v2) " (L-R)/(L+R) dB |" else ""
            line("| config | fRef Hz | h2 / h3 / h4 / h5 / h6 dB re h1 | slope dB/oct | f0/2 dB re h1 | onset90 ms (20 Hz DC-blocked raw) | centroid / rolloff Hz | Snip Pitch.detect | peak | RMS | mean (DC) | Loudness.of | Classifier vs drumClassFor |$stereoHdr")
            line("|---|---|---|---|---|---|---|---|---|---|---|---|---|" + (if (v2) "---|" else ""))
            for (r in rowsB) line(r)
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `A - analytic loop frequencies`() {
        line("## ARCO empirical probe - tables (rendered at ${RR} Hz, decimated to ${Dsp.RATE})")
        line()
        line("### A1 - analytic loop (delaySamples = 176400 / f0; one-pole lag at f0 for dampingHz at ROSIN 0/0.5/1 = ${f(Dsp.expMap(0f, 3500f, 16000f), 0)} / ${f(Dsp.expMap(0.5f, 3500f, 16000f), 0)} / ${f(Dsp.expMap(1f, 3500f, 16000f), 0)} Hz)")
        line()
        line("| voice | TUNE | f0 Hz | delaySamples | lag smp @ROSIN 0.5 | predicted comb Hz (rate/(D+lag)) | predicted cents | lag @ROSIN 0 -> cents | lag @ROSIN 1 -> cents |")
        line("|---|---|---|---|---|---|---|---|---|")
        for (voice in ArcoVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val f0 = ArcoV1.frequencyFor(voice, tune).toDouble()
            val d = RR / f0
            val lag5 = onePoleLagSamples(Dsp.expMap(0.5f, 3500f, 16000f).toDouble(), f0, RR)
            val lag0 = onePoleLagSamples(Dsp.expMap(0f, 3500f, 16000f).toDouble(), f0, RR)
            val lag1 = onePoleLagSamples(Dsp.expMap(1f, 3500f, 16000f).toDouble(), f0, RR)
            val comb = RR / (d + lag5)
            line("| $voice | $tune | ${f(f0)} | ${f(d, 2)} | ${f(lag5, 2)} | ${f(comb, 2)} | ${f(cents(comb, f0), 1)} | ${f(lag0, 2)} -> ${f(cents(RR / (d + lag0), f0), 1)} | ${f(lag1, 2)} -> ${f(cents(RR / (d + lag1), f0), 1)} |")
        }
        line()
        line("Friction constants at defaults: muS = ${Dsp.lin(0.5f, 0.6f, 1.4f)}, muD = 0.25, vBow = ${Dsp.lin(0.6f, 0.1f, 1.5f)}, v0 = 0.15, loop feedback 0.985, attackFrames = ${(Dsp.lin(0.5f, 0.02f, 0.08f) * RR).toInt()} (${f(Dsp.lin(0.5f, 0.02f, 0.08f) * 1000, 0)} ms), releaseFrames = ${(0.05f * RR).toInt()}, duration = ${f(Dsp.expMap(0.5f, 0.25f, 3.5f), 3)} s (DECAY 0: ${f(Dsp.expMap(0f, 0.25f, 3.5f), 3)}, DECAY 1: ${f(Dsp.expMap(1f, 0.25f, 3.5f), 3)}).")
        line("GURDY drone constant: saw(73.41/176400) = ${f((2.0 * (73.41 / 176400.0) - 1.0), 5)} -> x 0.35 = ${f(0.35 * (2.0 * (73.41 / 176400.0) - 1.0), 4)} added to every sample (a DC offset, not a drone).")
        assertTrue(true)
    }

    @Test
    fun `B - v1 voices at defaults and corners`() {
        voiceTables(v2 = false)
        assertTrue(true)
    }

    @Test
    fun `C - v2 voices at defaults and corners`() {
        voiceTables(v2 = true)
        assertTrue(true)
    }

    @Test
    fun `D - v2 SOLINA 1 saw versus loop peaks`() {
        line()
        line("### D - v2 at SOLINA 1: spectral peaks within ±6 % of f0 in the raw loop+saw (last 200 ms before release) and in the finished Snip (mono fold, 0.1 s..)")
        line()
        line("| voice | f0 | raw peaks Hz (dB re strongest) | Snip peaks Hz (dB re strongest) | raw peak @SOLINA 0 Hz |")
        line("|---|---|---|---|---|")
        for (voice in ArcoVoice.entries) {
            try {
                val c1 = renderV2(voice, mapOf("SOLINA" to 1f))
                val c0 = renderV2(voice, mapOf("SOLINA" to 0f))
                val f0 = c1.f0.toDouble()
                val sustainEnd = c1.totalFrames - c1.releaseFrames
                val raw1 = removeMean(sliceEndingAt(sanitize(c1.raw), sustainEnd, RR, 200.0))
                val raw0 = removeMean(sliceEndingAt(sanitize(c0.raw), sustainEnd, RR, 200.0))
                val pk1 = peaksNear(spectrum(raw1), RR, f0, 0.06)
                val pk0 = fftPeakHz(spectrum(raw0), RR)
                val m = mono(c1.snip)
                val body = m.copyOfRange(min(m.size - 1, 4410), m.size)
                val pks = peaksNear(spectrum(sanitize(body)), Dsp.RATE, f0, 0.06)
                line("| $voice | ${f(f0)} | ${pk1.joinToString("; ") { "${f(it.first, 2)} (${f(it.second, 1)})" }} | ${pks.joinToString("; ") { "${f(it.first, 2)} (${f(it.second, 1)})" }} | ${f(pk0, 2)} (${f(cents(pk0, f0), 1)} c) |")
            } catch (t: Throwable) {
                line("| $voice | THREW ${t::class.simpleName}: ${t.message?.take(100)} | | | |")
            }
        }
        assertTrue(true)
    }

    @Test
    fun `E - render cost`() {
        line()
        line("### E - render cost (System.nanoTime around render(), one warm-up then median of 3, ms per rendered second)")
        line()
        line("| voice | v1 defaults | v1 DECAY 1 | v2 defaults | v2 SOLINA 1 |")
        line("|---|---|---|---|---|")
        fun cost(block: () -> Snip): String {
            return try {
                block()
                val times = (1..3).map {
                    val t0 = System.nanoTime()
                    val s = block()
                    val ms = (System.nanoTime() - t0) / 1e6
                    ms / s.durationSeconds
                }.sorted()
                f(times[1], 1)
            } catch (t: Throwable) { "threw" }
        }
        for (voice in ArcoVoice.entries) {
            val a = cost { ArcoV1.render(voice) }
            val b = cost { ArcoV1.render(voice, mapOf("DECAY" to 1f)) }
            val c = cost { ArcoV2.render(voice) }
            val d = cost { ArcoV2.render(voice, mapOf("SOLINA" to 1f)) }
            line("| $voice | $a | $b | $c | $d |")
        }
        assertTrue(true)
    }

    @Test
    fun `F - SOLINA stage on its own`() {
        val rate = Dsp.RATE
        line()
        line("### F1 - SOLINA on a pure 220 Hz sine (2 s at 44.1 kHz, amount 1.0): zero-crossing pitch per 100 ms window, cents vs 220")
        line()
        val n = 2 * rate
        val sine = FloatArray(n) { (0.5 * sin(2.0 * PI * 220.0 * it / rate)).toFloat() }
        val st = SolinaEnsembleProbe.process(sine, 1.0f, rate)
        val l = channel(st, 0); val r = channel(st, 1)
        val mo = FloatArray(l.size) { (l[it] + r[it]) * 0.5f }
        line("| window | L Hz (cents) | R Hz (cents) | mono Hz (cents) | L rms | R rms | mono rms |")
        line("|---|---|---|---|---|---|---|")
        val win = rate / 10
        val lc = ArrayList<Double>(); val rc = ArrayList<Double>(); val mc = ArrayList<Double>()
        for (w in 0 until n / win) {
            val s = w * win; val e = s + win
            val ls = removeMean(l.copyOfRange(s, e)); val rs = removeMean(r.copyOfRange(s, e)); val ms = removeMean(mo.copyOfRange(s, e))
            val lh = zcHz(ls, rate); val rh = zcHz(rs, rate); val mh = zcHz(ms, rate)
            lh?.let { lc += cents(it, 220.0) }; rh?.let { rc += cents(it, 220.0) }; mh?.let { mc += cents(it, 220.0) }
            line("| ${f(s / rate.toDouble(), 1)}-${f(e / rate.toDouble(), 1)} s | ${centsStr(lh, 220.0)} | ${centsStr(rh, 220.0)} | ${centsStr(mh, 220.0)} | ${f(rmsOf(ls), 3)} | ${f(rmsOf(rs), 3)} | ${f(rmsOf(ms), 3)} |")
        }
        fun summary(x: List<Double>): String { if (x.isEmpty()) return "n/a"; val m = x.average(); val sd = sqrt(x.map { (it - m) * (it - m) }.average()); return "mean ${f(m, 1)} c, sd ${f(sd, 1)}, min ${f(x.min(), 1)}, max ${f(x.max(), 1)}" }
        line()
        line("Summary: L ${summary(lc)}; R ${summary(rc)}; mono ${summary(mc)}.")
        line("Whole-file FFT peak: L ${f(fftPeakHz(spectrum(l, 1 shl 18), rate), 2)} Hz, R ${f(fftPeakHz(spectrum(r, 1 shl 18), rate), 2)} Hz, mono ${f(fftPeakHz(spectrum(mo, 1 shl 18), rate), 2)} Hz. Input rms ${f(rmsOf(sine), 3)}.")
        line("Mono-fold loudness on the sine: Loudness.of L ${f(Loudness.of(Snip(l, 1, rate)), 4)}, R ${f(Loudness.of(Snip(r, 1, rate)), 4)}, mono fold ${f(Loudness.of(Snip(mo, 1, rate)), 4)}, interleaved (house: folds internally) ${f(Loudness.of(Snip(st, 2, rate)), 4)}; RMS mono vs sqrt(mean(L²,R²)) = ${f(20 * log10(rmsOf(mo) / sqrt((rmsOf(l) * rmsOf(l) + rmsOf(r) * rmsOf(r)) / 2)), 2)} dB; (L-R)/(L+R) ${f(stereoDiffDb(st), 1)} dB.")

        line()
        line("### F2 - SOLINA on white noise (4 s, Dsp.Noise(1234), 44.1 kHz): comb ripple, L channel. Peaks at k·133.33 Hz vs troughs at (k+0.5)·133.33 Hz (the 7.5 ms dry/wet comb).")
        line()
        line("| amount | Welch-averaged ripple (16384-pt Hann, 15 hops) mean dB over k=1..15 | max | short-window (4096) ripple at 8 times: min / max dB | mono-fold: Loudness L / R / fold / interleaved | RMS fold vs stereo dB | (L-R)/(L+R) dB |")
        line("|---|---|---|---|---|---|---|")
        for (amount in listOf(0.5f, 0.9f)) {
            val noise = Dsp.Noise(1234)
            val nn = 4 * rate
            val x = FloatArray(nn) { noise.next() * 0.3f }
            val y = SolinaEnsembleProbe.process(x, amount, rate)
            val yl = channel(y, 0); val yr = channel(y, 1)
            val fold = FloatArray(yl.size) { (yl[it] + yr[it]) * 0.5f }
            fun ripple(nfft: Int, starts: List<Int>): List<Double> {
                val acc = DoubleArray(nfft / 2)
                for (s in starts) {
                    val re = FloatArray(nfft); val im = FloatArray(nfft)
                    for (i in 0 until nfft) re[i] = yl[s + i] * (0.5f - 0.5f * cos(2.0 * PI * i / (nfft - 1)).toFloat())
                    Fft.forward(re, im)
                    for (b in acc.indices) acc[b] += re[b].toDouble() * re[b] + im[b].toDouble() * im[b]
                }
                val binHz = rate.toDouble() / nfft
                val out = ArrayList<Double>()
                for (k in 1..15) {
                    val pb = (k * 133.333 / binHz).toInt(); val tb = ((k + 0.5) * 133.333 / binHz).toInt()
                    var p = 0.0; var t = 0.0
                    for (o in -1..1) { p += acc[pb + o]; t += acc[tb + o] }
                    out += 10 * log10(p / t)
                }
                return out
            }
            val welch = ripple(16384, (0 until 15).map { it * 8192 })
            val shorts = (0 until 8).map { i -> ripple(4096, listOf(i * (nn - 4096) / 7)).average() }
            line("| $amount | ${f(welch.average(), 2)} | ${f(welch.max(), 2)} | ${f(shorts.min(), 2)} / ${f(shorts.max(), 2)} | ${f(Loudness.of(Snip(yl, 1, rate)), 4)} / ${f(Loudness.of(Snip(yr, 1, rate)), 4)} / ${f(Loudness.of(Snip(fold, 1, rate)), 4)} / ${f(Loudness.of(Snip(y, 2, rate)), 4)} | ${f(20 * log10(rmsOf(fold) / sqrt((rmsOf(yl) * rmsOf(yl) + rmsOf(yr) * rmsOf(yr)) / 2)), 2)} | ${f(stereoDiffDb(y), 1)} |")
            line("  amount $amount per-k Welch ripple dB: ${welch.joinToString(" ") { f(it, 1) }}")
        }

        line()
        line("### F3 - the LFO common-period claim")
        line("0.58 Hz x 1.724 s = ${f(0.58 * 1.724, 5)} cycles; 5.85 Hz x 1.724 s = ${f(5.85 * 1.724, 5)} cycles.")
        var found = "none under 200 s"
        var k = 1
        while (k <= 200 * 0.58 + 1) {
            val t = k / 0.58
            val m = 5.85 * t
            if (abs(m - Math.rint(m)) < 1e-6) { found = "${f(t, 4)} s (${k} slow cycles, ${f(m, 0)} fast cycles)"; break }
            k++
        }
        line("Smallest T with both 0.58·T and 5.85·T whole: $found. (0.58 = 29/50, 5.85 = 117/20 -> T = 100 s.)")
        line("LFO sum check: sin(x) + sin(x+2pi/3) + sin(x+4pi/3) at x = 0.7 = ${f(sin(0.7) + sin(0.7 + 2 * PI / 3) + sin(0.7 + 4 * PI / 3), 9)} - the three-tap SUM cancels; L carries taps 0+1 and R taps 1+2, neither sums to zero.")

        line()
        line("### F4 - v2 CELLO/ERHU at SOLINA 1: the finished Snip's mono fold versus its channels")
        line()
        line("| voice | Loudness L / R / fold | RMS fold vs stereo dB | (L-R)/(L+R) dB in Snip | (L-R)/(L+R) dB in pre-bandLimit stereo |")
        line("|---|---|---|---|---|")
        for (voice in listOf(ArcoVoice.CELLO, ArcoVoice.ERHU)) {
            try {
                val c = renderV2(voice, mapOf("SOLINA" to 1f))
                val s = c.snip
                val sl = channel(s.samples, 0); val sr = channel(s.samples, 1)
                val fold = mono(s)
                line("| $voice | ${f(Loudness.of(Snip(sl, 1, Dsp.RATE)), 4)} / ${f(Loudness.of(Snip(sr, 1, Dsp.RATE)), 4)} / ${f(Loudness.of(s), 4)} | ${f(20 * log10(rmsOf(fold) / sqrt((rmsOf(sl) * rmsOf(sl) + rmsOf(sr) * rmsOf(sr)) / 2)), 2)} | ${f(stereoDiffDb(s.samples), 1)} | ${f(stereoDiffDb(sanitize(c.stereo!!)), 1)} |")
            } catch (t: Throwable) { line("| $voice | THREW ${t::class.simpleName}: ${t.message?.take(100)} | | | |") }
        }
        assertTrue(true)
    }

    @Test
    fun `H - where the note is, and the friction map on its own`() {
        line()
        line("### H1 - where the note is (v1, other macros at default): global FFT peak of the raw loop, of the body buffer (both: 200 ms before release, mean removed) and of the finished Snip (mono, from 0.1 s), the Snip's level at f0 (±3 %) re its global peak, and the Snip's autocorrelation at one period of f0")
        line()
        line("| voice | TUNE | f0 | raw global peak Hz | body global peak Hz | Snip global peak Hz | Snip level at f0 re global peak dB | Snip r(period) |")
        line("|---|---|---|---|---|---|---|---|")
        for (voice in ArcoVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            try {
                val c = renderV1(voice, mapOf("TUNE" to tune))
                val f0 = c.f0.toDouble()
                val sustainEnd = c.totalFrames - c.releaseFrames
                val raw = removeMean(sliceEndingAt(sanitize(c.raw), sustainEnd, RR, 200.0))
                val body = removeMean(sliceEndingAt(sanitize(c.body), sustainEnd, RR, 200.0))
                val m = mono(c.snip)
                val sn = removeMean(m.copyOfRange(min(m.size - 1, 4410), m.size))
                val magS = spectrum(sn)
                val gp = fftPeakHz(magS, Dsp.RATE)
                val atF0 = peakAmp(sn, f0, Dsp.RATE)
                val atGp = peakAmp(sn, gp, Dsp.RATE, 0.005)
                val d44 = Math.round(Dsp.RATE / f0).toInt()
                line("| $voice | $tune | ${f(f0)} | ${f(fftPeakHz(spectrum(raw), RR), 1)} | ${f(fftPeakHz(spectrum(body), RR), 1)} | ${f(gp, 1)} | ${f(db(atF0, atGp), 1)} | ${f(autocorr(sn, d44))} |")
            } catch (t: Throwable) { line("| $voice | $tune | THREW ${t::class.simpleName}: ${t.message?.take(80)} | | | | | |") }
        }
        line()
        line("### H2 - the friction map on its own: x -> mu(vBow - x) + 0.985·x (the spec's loop with the one-pole and the interpolator removed; every lane n mod D of the delay line iterates this map on its own)")
        line()
        line("| BOW | ROSIN | vBow | muS | fixed point x = g(x)? | orbit from 0 (steps 2000-20000): mean | peak | min | r(1) | first 12 iterates |")
        line("|---|---|---|---|---|---|---|---|---|---|")
        for ((bow, rosin) in listOf(0.6f to 0.5f, 0f to 0.5f, 1f to 0.5f, 0.6f to 0f, 0.6f to 1f, 1f to 1f)) {
            val vBow = Dsp.lin(bow, 0.1f, 1.5f).toDouble()
            val muS = Dsp.lin(rosin, 0.6f, 1.4f).toDouble()
            val muD = 0.25; val v0 = 0.15
            fun g(x: Double): Double {
                val dv = vBow - x
                val sgn = if (dv >= 0) 1.0 else -1.0
                val mu = sgn * (muD + (muS - muD) / (1.0 + (dv / v0) * (dv / v0)))
                return mu + 0.985 * x
            }
            var fixed = "none in [-3, 6]"
            var xx = -3.0
            var prev = g(xx) - xx
            while (xx < 6.0) {
                val cur = g(xx) - xx
                if (prev * cur < 0 && abs(cur) < 0.02) { fixed = "x = ${f(xx, 3)}"; break }
                prev = cur; xx += 0.0005
            }
            val orbit = DoubleArray(20000); var x = 0.0
            for (i in orbit.indices) { x = g(x); orbit[i] = x }
            val tail = orbit.copyOfRange(2000, orbit.size)
            val mean = tail.average(); val pk = tail.maxOf { abs(it) }; val mn = tail.min()
            var e = 0.0; var sum = 0.0
            for (i in 0 until tail.size - 1) { val a = tail[i] - mean; val b = tail[i + 1] - mean; e += a * a; sum += a * b }
            line("| $bow | $rosin | ${f(vBow, 3)} | ${f(muS, 2)} | $fixed | ${f(mean, 3)} | ${f(pk, 3)} | ${f(mn, 3)} | ${f(sum / e, 3)} | ${orbit.take(12).joinToString(" ") { f(it, 2) }} |")
        }
        assertTrue(true)
    }

    @Test
    fun `G - v1 SARANGI tarab follows TUNE or not`() {
        line()
        line("### G - v1 SARANGI tarab at SYMPATHY 1 versus 0, TUNE 0 / 0.5 / 1. The render is deterministic and SYMPATHY touches only tarabOut, so body(S1) - body(S0) IS the tarab: its FFT peak nearest each 130.81·ratio (±1.5 %) over 0.1-0.6 s of the pre-normalise body buffer, Hz and dB re the S1 body's own line at f0 (peak within ±3 % of f0), then that line's level in the S1 body versus the S0 body")
        line()
        val ratios = floatArrayOf(1.0f, 1.125f, 1.25f, 1.333f, 1.5f, 1.667f, 1.875f)
        line("| TUNE | f0 Hz | body FFT global peak Hz (S1) | tarab (diff) global peak Hz | tarab peak dB re f0 line | " + ratios.joinToString(" | ") { "${f(130.81 * it, 1)}: diff peak Hz (dB re f0) / S1-S0" } + " |")
        line("|---|---|---|---|---|" + ratios.joinToString("") { "---|" })
        for (tune in listOf(0f, 0.5f, 1f)) {
            try {
                val c1 = renderV1(ArcoVoice.SARANGI, mapOf("TUNE" to tune, "SYMPATHY" to 1f))
                val c0 = renderV1(ArcoVoice.SARANGI, mapOf("TUNE" to tune, "SYMPATHY" to 0f))
                val f0 = c1.f0.toDouble()
                val from = (0.1 * RR).toInt(); val to = min(c1.body.size, (0.6 * RR).toInt())
                val b1 = removeMean(sanitize(c1.body).copyOfRange(from, to))
                val b0 = removeMean(sanitize(c0.body).copyOfRange(from, to))
                val diff = FloatArray(b1.size) { b1[it] - b0[it] }
                val pk = fftPeakHz(spectrum(b1), RR)
                val magD = spectrum(diff)
                val pkD = fftPeakHz(magD, RR)
                val ref1 = peakAmp(b1, f0, RR)
                val cells = ratios.map { r ->
                    val hz = 130.81 * r
                    val near = peaksNear(magD, RR, hz, 0.015, 1).firstOrNull()
                    val aD = peakAmp(diff, hz, RR, span = 0.015)
                    val a1 = peakAmp(b1, hz, RR, span = 0.015)
                    val a0 = peakAmp(b0, hz, RR, span = 0.015)
                    "${near?.let { f(it.first, 2) } ?: "-"} (${f(db(aD, ref1), 1)}) / ${f(db(a1, a0), 1)}"
                }
                line("| $tune | ${f(f0)} | ${f(pk, 2)} | ${f(pkD, 2)} | ${f(db(peakAmp(diff, pkD, RR, 0.01), ref1), 1)} | ${cells.joinToString(" | ")} |")
            } catch (t: Throwable) { line("| $tune | THREW ${t::class.simpleName}: ${t.message?.take(100)} | | |") }
        }
        line()
        line("Pluck.ks tarab seeds: `seed + (r * 100).toInt()` with seed = 0 -> " + ratios.joinToString(", ") { "${(it * 100).toInt()}" } + " (the ratio 1.333 -> 133, 1.667 -> 166; two strings never share a seed, but none depends on the patch).")
        assertTrue(true)
    }
}
```
