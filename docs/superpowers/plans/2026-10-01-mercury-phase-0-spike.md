# MERCURY — the Phase-0 record: a rubbed modal object, measured

**Status:** a record, not a plan. Nothing here is in the build. The two
Kotlin sources below were run in the test source set at `01b1ff9` on
2026-10-01, over four iterations, and then deleted. They are kept as text
so the numbers can be re-run, as ARCO's and BORE's spike records keep
theirs
([`2026-09-29-arco-phase-0-spike.md`](2026-09-29-arco-phase-0-spike.md),
[`2026-09-28-bore-phase-0-spike.md`](2026-09-28-bore-phase-0-spike.md)).
R0 rebuilds the bank from the design
([`../specs/2026-10-01-mercury-modal-glass-engine-design.md`](../specs/2026-10-01-mercury-modal-glass-engine-design.md),
"Phase 0, as measured") rather than copying anything here.
**Date:** 2026-10-01
**Gate:** the design's Phase-0 gate is "speaks, in tune, decays":
- rub onset reliable across pitch and pressure;
- passive energy decay at every COUPLE;
- a BEND sweep does not pump amplitude;
- WATER 0.05 measurable inside a 2 s note;
- raw states bounded at every corner.

**It passes on the ROTATION bank with the iteration-3 settings.** The
TRAPEZOID bank, as built here, fails.
**Not heard.** This sandbox cannot play audio. Everything below is
measurement. The sonic claims wait for R1's audition.

## What this file holds

- **The model** (Appendix B, `MercurySpike.kt`). One modal object:
  - 12 primary modes and 4 vessel modes;
  - a strike and a friction contact acting through one contact vector;
  - reciprocal springs between the modes;
  - BEND as a ratio deformation with an onset gesture and a ±2-semitone
    excursion;
  - WATER as one damped, circularly forced 2-D mass that loads every mode
    from a single state.

  Two candidate banks run that same object:
  - **ROTATION:** each mode is `z = ωq − i·v`, rotated by `r·e^{iωT}`, then
    kicked by the springs and the contact.
  - **TRAPEZOID:** each mode is a trapezoidal SVF, damped from t60, with
    explicit coupling.

  The friction is φ(η) = √(2a)·η·e^(−aη²+½), with its peak at η* = 0.01
  and the finger at 3η*. It is solved implicitly, by a scalar Newton step,
  against the contact velocity it changes, and applied back through the
  same vector.
- **Mode tables** are Rayleigh's closed forms, not tuned numbers:
  - **RING** (PING, SING): thin ring, inextensional bending,
    f_k ∝ k(k²−1)/√(k²+1) for k = 2…13.
  - **BEAM** (BLADE): free-free beam, β = 4.7300, 7.8532, 10.9956, 14.1372,
    then (2k+1)π/2.

  These are the textbook formulas. They are not measurements of a glass or
  a saw.
- **Designed numbers** are marked as such in the source: the vessel
  detunes, BEND coefficients, GLASS curves, κ and the mass oscillator.
- **The probes** (Appendix C, `MercurySpikeTest.kt`). Print-only; the one
  assertion is `assertTrue(true)`. Four `@Test` methods (`spike`,
  `iteration2`, `iteration3`, `iteration4`) each write a report. Iteration
  4 answers the review on #423.
- **The reports** (Appendix A), verbatim.

## How to re-run

Copy Appendices B and C into `synth/src/test/kotlin/com/snipsnap/synth/`,
so that `internal` members of `:synth` resolve. Then:

```
MERCURY_SPIKE_OUT=/tmp/m1.md MERCURY_SPIKE_OUT2=/tmp/m2.md MERCURY_SPIKE_OUT3=/tmp/m3.md MERCURY_SPIKE_OUT4=/tmp/m4.md \
  ./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.MercurySpikeTest' -i
```

All four methods took 208 s of wall time on the cloud session's four
cores, including Gradle start-up. The final source reproduces every table
in Appendix A identically except P9's wall-clock timings. That was checked
by re-running all four methods from the final source and diffing the
first three against their original runs. The only other difference is
I3b's heading, which the review on #423 corrected (see item 6). Remove the
files afterwards; if `git status` still shows them, that is the reminder.

Iterations 2 and 3 are flags on `Params`, and their defaults reproduce
iteration 1:
- `anchorFix`
- `onsetSeconds`
- `waterSqrt`
- `contactTaper`
- `onsetPeriods`
- `plainLaplacian`
- `tapFloor`

## What the numbers settled

1. **ROTATION is the bank (P1, P2, P7).**
   - **Passive decay.** Strike only, with the geometry fixed: no energy rise
     in any 1 ms block from 3 ms to 1.5 s, at COUPLE 0, 0.3, 0.6 and 1, on both
     tables. It still had 0 rises with BEND 1, WATER 1 and COUPLE 1 all
     moving, so the time-varying K did no net positive work on these
     renders. That is a measurement, not a proof.
   - **A ±12-semitone, 2 Hz sweep** holds energy within 0.9990–0.9997 of the
     unswept note. The pickup moves at most 0.35 dB.
   - **All 128 corners are finite.** The worst state is 0.053 against a
     finger speed of 0.03.
2. **TRAPEZOID as built fails (P1, P2, P7).**
   - It is passive at COUPLE 0.
   - With the coupling force taken from the previous sample it diverges:
     ×10⁴⁶ by 1 s at COUPLE 0.3, and NaN at 0.6 and above.
   - The sweep pumps energy by ×0.25 to ×4 (±6 dB).
   - The one-sample lag is a phase advance that grows with ωT. The top
     modes (16 kHz at C4) gain about 10³/s.
   - A trapezoid made passive needs the coupled implicit solve (a 32×32
     system whenever K moves). ROTATION gets passivity from a split that
     is the exact flow of each part: the damped rotation is the exact free
     flow, and the kick is the exact flow of the spring potential. That is
     why its explicit kick is safe and TRAPEZOID's is not. Not pursued.
3. **The springs flatten the note; one eigen-solve per note fixes it (P6,
   I2d).**
   - The vessel modes sit 3.5–7 % from their partners, and the springs
     repel them. The fundamental falls 13 cents at COUPLE 0.3 and 85 cents
     at COUPLE 1 (I4a). At the voice defaults it is 6.6–19.8 cents flat
     (P3).
   - **P6's "−60.00" is a clipped reading.** P6 scanned only ±60 cents, so
     its COUPLE 1 rows read the edge of the scan. I4a re-measured with a
     ±600-cent scan and got −85.3.
   - **The springs' form.** Iterations 1–3 use `K = diag(ω²) − A`. These are
     the springs `½·k_ij·(q_i − q_j)²` on modes whose own stiffness is
     pre-reduced to `ω_i² − Σ_j k_ij`, so an uncoupled mode keeps ω_i on the
     diagonal. The energy the probes track is exactly `½vᵀv + ½qᵀKq` for
     that K. K is positive definite because the effective κ times the
     degree is at most 0.8, which keeps it strictly diagonally dominant.
   - **Plain springs measured too (I4a, I4b).** The review on #423 asked
     whether the results were artifacts of that choice, so iteration 4 ran
     the plain form, `K = diag(ω²) + L`:
     - It is passive as well: no energy rise at COUPLE 0.3, 0.6 or 1.
     - It moves the note the other way, +48 to +121 cents, because the
       springs add stiffness.
     - The anchor fix takes it to 0.02–0.03 cents.
     - The voices land within 1.4 cents, on mode 0, from MIDI 36 to 84.

     So no conclusion depends on the form. The compensated form is kept
     because its correction is smaller. TRAPEZOID was not re-run with
     plain springs: its divergence comes from the one-sample lag, which
     both forms share.
   - **Fix:** solve K's eigenvalues once at the settled shape (Jacobi,
     16×16). Then scale every mode so the eigenvector that is mostly mode 0
     sits on the note. Because `k ∝ ω²`, a uniform scale is exact.
   - Result: 0.02 cents at every COUPLE (I2d).
4. **Pressure must be set by onset time, not as a multiple of the
   threshold (P4, I2b).**
   - At a fixed multiple of the linear threshold, the onset depends on the
     t60, because the threshold *is* the fundamental's damping. At GLASS
     0.85 a rub at 2× threshold had not finished growing after 1.6 s, and
     8× took 0.3–1.5 s.
   - Setting `P = (γ₀ + 1/τ) / (|φ′(v_B)|·b₀²)` makes τ the linear e-fold
     time whatever the GLASS.
5. **Fast onset at low notes costs pitch and mode capture (I2a, I2b).**
   - With a 20–40 ms e-fold, MIDI 36 pulls flat by 19–41 cents. That is
     friction flattening, which scales with pressure over ω.
   - With a tap, a uniform contact let **mode 1 capture the rub**. SING at
     MIDI 36 and 48 sounded at mode 1, an octave and a sixth (1817 cents)
     up.

   Two changes fixed both:
   - a **contact-patch taper**, `b_i = ratio_i^(−0.5)`: a finger averages
     out short wavelengths, so upper modes see less of the drive;
   - an **e-fold in periods**, `τ = max(20 ms, 12/f)`: low glass speaks
     slower, as real glass does.

   With both (I3a, I3b, I3c):
   - every voice is within 2.2 cents from MIDI 36 to 84;
   - mode 0 wins in all 15 voice renders, all 20 onset renders, and 64 of
     64 rubbed corners;
   - rub pitch error is under 0.6 cents everywhere.
6. **The tap is the rub's seed: BORE's POP, again (I2b, I3b).**
   - Friction alone starts from the finger's ramp transient, which falls
     as 1/ω. At a fixed e-fold, an un-seeded rub is slower the higher the
     note: at a 20 ms e-fold it takes 45 ms at MIDI 36 and 339 ms at MIDI 84
     (I2b). With the e-fold in periods it takes 334–1,127 ms (I3b).
   - With RUB 0.7 (tap weight 0.45), onset is 60–205 ms across MIDI 36–84.
   - At RUB 1 the spec's `tapWeight = cos(π·RUB/2)` is exactly 0, so R1
     needs a floor: the finger has to land. I3b's "RUB 1" column is
     therefore unseeded. Its first heading said "minimum seed", which was
     wrong; the review on #423 caught it, and the heading is corrected.
   - **The floor, measured (I4c):** `tapWeight = max(cos(π·RUB/2)·tap,
     floor)`.
     - **0.2** brings RUB 1's onset to 95–534 ms across MIDI 36–84, on mode
       0, within 0.6 cents. The 534 ms is MIDI 36, where the 12-period
       e-fold is 183 ms by design.
     - **0.1** gives 130–1,093 ms.
     - **0.05 is worse than nothing at MIDI 36** (1,407 vs 1,127 ms): a
       landing that small works against the finger's own start-up
       transient instead of seeding it.
     - R1 starts from 0.2 and confirms it by ear.
7. **WATER needs √ at the bottom (P5, I2c).**
   - Linear depth makes WATER 0.05 almost nothing. Its level-aligned
     spectral distance from WATER 0 is 0.002, about 1/30 of a BEND step of
     +0.05.
   - With depth ∝ √WATER and rate 0.6 + 2.4·WATER Hz it is 0.007, about a
     quarter of that BEND step, with 4–6 cents of slow drift where WATER 0
     gives 0.4–2.6 (PING and SING; BLADE's drift is dominated by its BEND
     gesture).
   - Whether that is audible is the audition's call.
8. **COUPLE moves energy, and WATER changes where it goes (P6).**
   - The vessel is never struck, yet its share of the energy reaches
     0.12 / 0.51 / 0.62 by 600 ms at COUPLE 0.3 / 0.6 / 1, and sloshes:
     the share swings by up to 0.82.
   - At COUPLE 0.6, WATER 0.5 moves the 300 ms share from 0.31 to 0.53.
     That is the WATER × COUPLE interaction the spec asks for, on paper.
9. **The classifier files nothing as a drum (P8).**
   - The R1 voices at MIDI 36, 60 and 84 read LOOP at 2.5 s, which is the
     length rule, and PERC when cut to 0.6 s.
   - These were peak-normalised, not levelled. R1's preset contract is the
     real check.
10. **Cost is not a concern (P9).** An 8 s note with every mechanism on
    renders in 0.43–0.48 s on ROTATION. The spec's proposed budget is 10 s, so R1 can
    afford per-sample retuning, and 24 modes if the audition asks for them.

## What was not measured

- **HOLD/LOOP.** That is R2, behind its own probe. The design's warning
  stands: a friction-locked mode under moving WATER may never become
  exactly periodic.
- **Velocity.**
- **Aliasing,** beyond the 14–19 kHz mode fade.
- **GLASS's friction selectivity** (it only shaped damping and pickup
  here).
- **The five interaction grids,** as listening material.
- **Determinism, as an assertion.** It is deterministic by construction,
  and the three runs and the re-run were identical, but no test asserts
  it.
- **Any listening at all.**

## The iteration log

| # | Changed | Why | Result |
|---|---|---|---|
| 1 | Both banks; spec-shaped excitation (pressure as a multiple of the threshold, linear WATER) | Choose a bank; find out where the spec's model breaks | ROTATION passive, bounded and retune-safe; TRAPEZOID diverges with coupling. Rub sustains on mode 0 but slowly; COUPLE flattens; WATER 0.05 near zero |
| 2 | `anchorFix`, `onsetSeconds` (40 ms), `waterSqrt` | Items 3, 4 and 7 above | Pitch exact under COUPLE; onset 0.1–0.7 s; WATER 0.05 is a quarter of a BEND step. New failures: low-note flattening up to −41 cents, and mode-1 capture on SING 36/48 |
| 3 | `contactTaper` 0.5, `onsetPeriods` 12 | Item 5 | Every gate passes: pitch ±2.2 cents, mode 0 everywhere, onset 60–205 ms with the tap, every corner bounded |
| 4 | `plainLaplacian`, `tapFloor`; I3b relabelled | The review on #423: was the coupling form an artifact, and was RUB 1's seed ever tested? | Plain springs are also passive and also fixed by the anchor solve (+48 to +121 cents before it, 0.02 after), so no conclusion moves. A 0.2 landing floor gives RUB 1 an onset of 95–534 ms. P6's −60 was a clipped scan; the true value is −85 |

## Appendix A — the reports

### A1. Iteration 1 (`spike`)

#### MERCURY Phase-0 spike — probe output

#### P1. Passive decay: strike only, BEND centred, WATER 0

Total energy (kinetic + modal + spring) per 1 ms block, from 3 ms (after the strike) to 1.5 s. 'rises' counts blocks above their predecessor; 'worst' is the largest block-to-block ratio.

| bank | table | COUPLE | rises | worst ratio | E(1 s)/E(10 ms) |
|---|---|---|---|---|---|
| ROTATION | RING | 0.00 | 0 | 0.997647541 | 5.93e-02 |
| ROTATION | RING | 0.30 | 0 | 0.996795532 | 1.76e-02 |
| ROTATION | RING | 0.60 | 0 | 0.996768443 | 6.03e-03 |
| ROTATION | RING | 1.00 | 0 | 0.997170470 | 3.24e-03 |
| ROTATION | BEAM | 0.00 | 0 | 0.997645290 | 5.87e-02 |
| ROTATION | BEAM | 0.30 | 0 | 0.996778170 | 1.75e-02 |
| ROTATION | BEAM | 0.60 | 0 | 0.996774623 | 6.02e-03 |
| ROTATION | BEAM | 1.00 | 0 | 0.997012435 | 3.24e-03 |
| TRAPEZOID | RING | 0.00 | 0 | 0.998506477 | 5.92e-02 |
| TRAPEZOID | RING | 0.30 | 1425 | 1.128899041 | 7.24e+46 |
| TRAPEZOID | RING | 0.60 | 993 | NaN | 5.62e+307 |
| TRAPEZOID | RING | 1.00 | 349 | NaN | NaN |
| TRAPEZOID | BEAM | 0.00 | 0 | 0.998503926 | 5.87e-02 |
| TRAPEZOID | BEAM | 0.30 | 1410 | 1.138846593 | 3.36e+49 |
| TRAPEZOID | BEAM | 0.60 | 920 | NaN | NaN |
| TRAPEZOID | BEAM | 1.00 | 320 | NaN | NaN |

Modulated (parameter work measured, not forbidden): BEND 1 gesture, WATER 1, COUPLE 1, strike only.

| bank | table | rises | worst ratio | E(1 s)/E(10 ms) |
|---|---|---|---|---|
| ROTATION | RING | 0 | 0.996971 | 2.73e-03 |
| ROTATION | BEAM | 0 | 0.996951 | 2.67e-03 |
| TRAPEZOID | RING | 280 | NaN | NaN |
| TRAPEZOID | BEAM | 170 | NaN | NaN |

#### P2. Retune under a ±12-semitone, 2 Hz sweep (4 primaries, no vessel, COUPLE 0, strike only)

Ratio of total energy, swept over static, per 1 ms block from 10 ms to 1.4 s; and the 20 ms RMS of the pickup in dB, swept over static.

| bank | energy ratio min | energy ratio max | pickup dB min | pickup dB max |
|---|---|---|---|---|
| ROTATION | 0.9990 | 0.9997 | -0.35 | 0.32 |
| TRAPEZOID | 0.2490 | 3.9856 | -6.22 | 6.09 |

#### P3. Pitch at the spec's voice defaults (ROTATION unless marked), measured 1.4–1.8 s

`Pitch.detect` (the house detector, integer-lag autocorrelation, 0.25 s) and the fundamental partial's frequency (Hann DFT scan, 0.4 s). Cents against the requested note. WATER is on at the defaults, so a few cents of drift are the design, not an error.

| voice | MIDI | detect cents | conf | partial cents | dominant mode | share |
|---|---|---|---|---|---|---|
| PING | 36 | -14.7 | 0.93 | -6.4 | 0 | 0.55 |
| PING | 48 | -14.7 | 0.96 | -6.6 | 0 | 0.69 |
| PING | 60 | -14.7 | 0.98 | -6.6 | 0 | 0.97 |
| PING | 72 | -14.7 | 0.99 | -6.6 | 0 | 1.00 |
| PING | 84 | 5.8 | 0.99 | -6.7 | 0 | 1.00 |
| SING | 36 | -7.1 | 0.94 | -9.6 | 0 | 0.56 |
| SING | 48 | -9.6 | 0.97 | -9.5 | 0 | 0.74 |
| SING | 60 | -14.7 | 0.98 | -9.4 | 0 | 0.99 |
| SING | 72 | -14.7 | 0.99 | -9.4 | 0 | 1.00 |
| SING | 84 | 5.8 | 0.99 | -9.4 | 0 | 1.00 |
| BLADE | 36 | -17.3 | 0.94 | -19.9 | 0 | 0.55 |
| BLADE | 48 | -19.8 | 0.97 | -19.8 | 0 | 0.71 |
| BLADE | 60 | -14.7 | 0.98 | -19.8 | 0 | 0.99 |
| BLADE | 72 | -14.7 | 0.99 | -19.7 | 0 | 1.00 |
| BLADE | 84 | -35.0 | 0.99 | -19.7 | 0 | 1.00 |

TRAPEZOID, PING and SING at MIDI 60:

| voice | detect cents | partial cents |
|---|---|---|
| PING | none | -52.9 |
| SING | none | -60.0 |

#### P4. Rub onset and sustain (RUB 1, no strike, contact 0–1.6 s, WATER 0, BEND centred)

'steady' is the RMS over 1.3–1.6 s; 'onset' is the first 5 ms window at 90 % of it; 'flat' is RMS(1.45–1.6)/RMS(1.3–1.45); 'mode' is the strongest mode and 'cents' its offset from that mode's own frequency.

| table | GLASS | MIDI | pressure × thr | steady | onset ms | flat | mode | cents |
|---|---|---|---|---|---|---|---|---|
| RING | 0.85 | 36 | 1.2 | 1.45e-04 | 5 | 0.977 | 0 | -9.3 |
| RING | 0.85 | 36 | 2.0 | 6.22e-04 | 758 | 1.076 | 0 | -9.2 |
| RING | 0.85 | 36 | 4.0 | 1.97e-02 | 1157 | 1.020 | 0 | -11.0 |
| RING | 0.85 | 36 | 8.0 | 2.07e-02 | 334 | 0.986 | 0 | -16.7 |
| RING | 0.85 | 36 | 16.0 | 2.13e-02 | 105 | 0.994 | 0 | -33.0 |
| RING | 0.85 | 48 | 1.2 | 1.99e-05 | 0 | 0.958 | 0 | -9.6 |
| RING | 0.85 | 48 | 2.0 | 8.21e-05 | 0 | 1.053 | 0 | -9.6 |
| RING | 0.85 | 48 | 4.0 | 1.68e-03 | 1372 | 1.358 | 0 | -9.5 |
| RING | 0.85 | 48 | 8.0 | 2.08e-02 | 768 | 1.000 | 0 | -11.3 |
| RING | 0.85 | 48 | 16.0 | 2.13e-02 | 284 | 1.002 | 0 | -17.3 |
| RING | 0.85 | 60 | 1.2 | 5.10e-06 | 0 | 0.958 | 0 | -9.7 |
| RING | 0.85 | 60 | 2.0 | 2.09e-05 | 978 | 1.053 | 0 | -9.7 |
| RING | 0.85 | 60 | 4.0 | 4.06e-04 | 1397 | 1.335 | 0 | -9.7 |
| RING | 0.85 | 60 | 8.0 | 2.09e-02 | 1053 | 1.000 | 0 | -10.1 |
| RING | 0.85 | 60 | 16.0 | 2.13e-02 | 434 | 1.000 | 0 | -11.5 |
| RING | 0.85 | 72 | 1.2 | 1.11e-06 | 0 | 0.957 | 0 | -9.7 |
| RING | 0.85 | 72 | 2.0 | 4.53e-06 | 1093 | 1.052 | 0 | -9.7 |
| RING | 0.85 | 72 | 4.0 | 8.71e-05 | 1407 | 1.333 | 0 | -9.7 |
| RING | 0.85 | 72 | 8.0 | 1.94e-02 | 1352 | 1.161 | 0 | -9.8 |
| RING | 0.85 | 72 | 16.0 | 2.14e-02 | 574 | 1.000 | 0 | -10.2 |
| RING | 0.85 | 84 | 1.2 | 3.06e-07 | 0 | 0.958 | 0 | -9.7 |
| RING | 0.85 | 84 | 2.0 | 1.25e-06 | 1107 | 1.053 | 0 | -9.7 |
| RING | 0.85 | 84 | 4.0 | 2.40e-05 | 1407 | 1.333 | 0 | -9.7 |
| RING | 0.85 | 84 | 8.0 | 7.44e-03 | 1497 | 3.102 | 0 | -9.7 |
| RING | 0.85 | 84 | 16.0 | 2.14e-02 | 688 | 1.000 | 0 | -9.8 |
| BEAM | 0.45 | 36 | 1.2 | 8.40e-05 | 10 | 0.967 | 0 | -9.0 |
| BEAM | 0.45 | 36 | 2.0 | 7.34e-04 | 1142 | 1.158 | 0 | -8.6 |
| BEAM | 0.45 | 36 | 4.0 | 7.31e-03 | 519 | 1.008 | 0 | -12.0 |
| BEAM | 0.45 | 36 | 8.0 | 7.31e-03 | 145 | 0.988 | 0 | -23.2 |
| BEAM | 0.45 | 36 | 16.0 | 7.11e-03 | 60 | 0.993 | 0 | -40.0 |
| BEAM | 0.45 | 48 | 1.2 | 1.10e-05 | 0 | 0.945 | 0 | -9.5 |
| BEAM | 0.45 | 48 | 2.0 | 8.29e-05 | 1187 | 1.108 | 0 | -9.4 |
| BEAM | 0.45 | 48 | 4.0 | 7.55e-03 | 1187 | 1.002 | 0 | -10.2 |
| BEAM | 0.45 | 48 | 8.0 | 7.79e-03 | 404 | 1.000 | 0 | -13.0 |
| BEAM | 0.45 | 48 | 16.0 | 7.59e-03 | 140 | 1.003 | 0 | -24.0 |
| BEAM | 0.45 | 60 | 1.2 | 2.78e-06 | 0 | 0.944 | 0 | -9.7 |
| BEAM | 0.45 | 60 | 2.0 | 2.07e-05 | 1212 | 1.105 | 0 | -9.7 |
| BEAM | 0.45 | 60 | 4.0 | 2.99e-03 | 1472 | 2.371 | 0 | -9.6 |
| BEAM | 0.45 | 60 | 8.0 | 7.94e-03 | 584 | 1.000 | 0 | -10.5 |
| BEAM | 0.45 | 60 | 16.0 | 8.00e-03 | 239 | 1.000 | 0 | -13.3 |
| BEAM | 0.45 | 72 | 1.2 | 6.01e-07 | 0 | 0.944 | 0 | -9.7 |
| BEAM | 0.45 | 72 | 2.0 | 4.45e-06 | 1277 | 1.105 | 0 | -9.7 |
| BEAM | 0.45 | 72 | 4.0 | 4.04e-04 | 1447 | 1.655 | 0 | -9.7 |
| BEAM | 0.45 | 72 | 8.0 | 7.98e-03 | 773 | 1.000 | 0 | -9.9 |
| BEAM | 0.45 | 72 | 16.0 | 8.14e-03 | 324 | 1.000 | 0 | -10.6 |
| BEAM | 0.45 | 84 | 1.2 | 1.66e-07 | 0 | 0.944 | 0 | -9.7 |
| BEAM | 0.45 | 84 | 2.0 | 1.23e-06 | 1277 | 1.105 | 0 | -9.7 |
| BEAM | 0.45 | 84 | 4.0 | 1.10e-04 | 1442 | 1.638 | 0 | -9.7 |
| BEAM | 0.45 | 84 | 8.0 | 7.99e-03 | 923 | 1.000 | 0 | -9.8 |
| BEAM | 0.45 | 84 | 16.0 | 8.17e-03 | 394 | 1.000 | 0 | -10.0 |

The tap as a seed (BORE's POP): RING, GLASS 0.85, pressure 2× threshold, RUB 1 (no tap) vs RUB 0.7 (tap weight 0.45).

| MIDI | onset ms, RUB 1 | onset ms, RUB 0.7 |
|---|---|---|
| 36 | 758 | 928 |
| 60 | 978 | 1063 |
| 84 | 1107 | 903 |

#### P5. WATER inside a 2 s note (MIDI 60; other macros at the voice defaults)

'drift' is the peak-to-peak of the fundamental partial in cents over 50 ms windows, 0.3–1.9 s. 'dist' is the level-aligned spectral distance from the same voice at WATER 0. Reference: the same distance between BEND 0.50 and BEND 0.55 at WATER 0.

| voice | WATER | drift p-p cents | dist vs WATER 0 |
|---|---|---|---|
| PING | 0.00 | 3.5 | 0.0000 |
| PING | 0.05 | 3.4 | 0.0019 |
| PING | 0.10 | 4.2 | 0.0037 |
| PING | 0.20 | 5.9 | 0.0076 |
| PING | 0.65 | 11.9 | 0.0275 |
| PING | ref BEND +0.05 | - | 0.0554 |
| SING | 0.00 | 1.8 | 0.0000 |
| SING | 0.05 | 1.9 | 0.0020 |
| SING | 0.10 | 2.8 | 0.0042 |
| SING | 0.20 | 4.3 | 0.0092 |
| SING | 0.65 | 11.4 | 0.0394 |
| SING | ref BEND +0.05 | - | 0.0749 |
| BLADE | 0.00 | 25.4 | 0.0000 |
| BLADE | 0.05 | 25.2 | 0.0060 |
| BLADE | 0.10 | 27.8 | 0.0143 |
| BLADE | 0.20 | 29.7 | 0.0339 |
| BLADE | 0.65 | 52.5 | 0.0946 |
| BLADE | ref BEND +0.05 | - | 0.0286 |

#### P6. COUPLE: vessel energy share after a strike (RING, MIDI 60, RUB 0, BEND centred)

Vessel share = vessel energy / total energy (the vessel is never struck; b = 0). 'pitch' is the fundamental partial 0.2–0.6 s against COUPLE 0, in cents. With WATER 0.5, 'swing' is the max − min of the share over 0.2–1.0 s.

| COUPLE | WATER | share 10 ms | share 100 ms | share 300 ms | share 600 ms | swing | pitch cents |
|---|---|---|---|---|---|---|---|
| 0.00 | 0.00 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.00 |
| 0.30 | 0.00 | 0.1685 | 0.2132 | 0.1150 | 0.1211 | 0.2537 | -13.37 |
| 0.60 | 0.00 | 0.4136 | 0.0700 | 0.3100 | 0.5064 | 0.5453 | -40.92 |
| 1.00 | 0.00 | 0.4106 | 0.5247 | 0.2413 | 0.6210 | 0.8203 | -60.00 |
| 0.00 | 0.50 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 1.14 |
| 0.30 | 0.50 | 0.1707 | 0.2218 | 0.0940 | 0.1044 | 0.2727 | -13.16 |
| 0.60 | 0.50 | 0.4159 | 0.0919 | 0.5324 | 0.2574 | 0.5978 | -42.24 |
| 1.00 | 0.50 | 0.4140 | 0.5808 | 0.2362 | 0.4634 | 0.8254 | -60.00 |

#### P7. Corners: BEND, RUB, WATER, GLASS, COUPLE each at 0 and 1 (32), MIDI 36 and 84, tap 1, pressure 8× threshold

| bank | table | renders | all finite | max state | worst corner (B R W G C, MIDI) | max raw peak |
|---|---|---|---|---|---|---|
| ROTATION | RING | 64 | true | 4.35e-02 | 1 1 1 0 0, 36 | 8.11e-02 |
| ROTATION | BEAM | 64 | true | 5.25e-02 | 1 1 1 0 0, 36 | 8.92e-02 |
| TRAPEZOID | RING | 64 | false | Infinity | 0 0 0 0 1, 84 | NaN |
| TRAPEZOID | BEAM | 64 | false | Infinity | 0 0 0 0 1, 84 | NaN |

Scale: the driver moves at 0.03; a mode rung at that speed has state ≈ 0.03.

#### P8. Classifier on the spec's voice defaults (one-shot length as rendered, 2.5 s; and PING cut to 0.6 s)

| voice | MIDI | class (2.5 s) | class (0.6 s) |
|---|---|---|---|
| PING | 36 | LOOP | PERC |
| PING | 60 | LOOP | PERC |
| PING | 84 | LOOP | PERC |
| SING | 36 | LOOP | PERC |
| SING | 60 | LOOP | PERC |
| SING | 84 | LOOP | PERC |
| BLADE | 36 | LOOP | PERC |
| BLADE | 60 | LOOP | PERC |
| BLADE | 84 | LOOP | PERC |

#### P9. Cost: an 8 s render at MIDI 60, 12 + 4 modes, every mechanism on (median of 3, after warm-up)

| bank | render ms (8 s of audio) | raw buffer MB |
|---|---|---|
| ROTATION | 479 | 5.6 |
| TRAPEZOID | 511 | 5.6 |

### A2. Iteration 2 (`iteration2`)

#### MERCURY Phase-0 spike — iteration 2 (anchor fix, onset-set pressure, √WATER)

#### I2a. Pitch with the anchor fix (voice defaults, √WATER, pressure for a 40 ms e-fold), measured 1.4–1.8 s

| voice | MIDI | detect cents | partial cents | dominant mode |
|---|---|---|---|---|
| PING | 36 | 3.2 | 0.7 | 0 |
| PING | 48 | 0.6 | 0.8 | 0 |
| PING | 60 | -4.5 | 0.7 | 0 |
| PING | 72 | 5.8 | 0.7 | 0 |
| PING | 84 | 5.8 | 0.7 | 0 |
| SING | 36 | 1817.4 | 60.0 | 1 |
| SING | 48 | 1817.4 | 60.0 | 1 |
| SING | 60 | -4.5 | -1.3 | 0 |
| SING | 72 | 5.8 | -0.3 | 0 |
| SING | 84 | 5.8 | 0.0 | 0 |
| BLADE | 36 | -22.3 | -19.7 | 0 |
| BLADE | 48 | -14.7 | -8.1 | 0 |
| BLADE | 60 | -4.5 | -3.1 | 0 |
| BLADE | 72 | -14.7 | -1.8 | 0 |
| BLADE | 84 | 5.8 | -0.1 | 0 |

#### I2b. Onset with pressure set by a target e-fold time (RUB 1 vs RUB 0.7 with its tap; WATER 0; anchor fix)

'onset' is the first 5 ms window at 90 % of the 1.3–1.6 s RMS; 'flat' as in P4; 'cents' is the fundamental partial against the note (pressure flattening).

| table | MIDI | e-fold ms | onset ms RUB 1 | flat | cents | onset ms RUB 0.7 + tap | cents |
|---|---|---|---|---|---|---|---|
| RING | 36 | 20 | 45 | 0.996 | -30.5 | 30 | 60.0 |
| RING | 36 | 40 | 120 | 1.005 | -22.1 | 0 | 60.0 |
| RING | 36 | 80 | 334 | 0.985 | -7.0 | 579 | -5.3 |
| RING | 48 | 20 | 115 | 1.002 | -21.5 | 0 | 60.0 |
| RING | 48 | 40 | 309 | 1.000 | -6.7 | 294 | -5.2 |
| RING | 48 | 80 | 763 | 0.998 | -1.6 | 424 | -1.3 |
| RING | 60 | 20 | 210 | 0.999 | -6.4 | 130 | -5.0 |
| RING | 60 | 40 | 464 | 0.996 | -1.6 | 234 | -1.2 |
| RING | 60 | 80 | 1053 | 1.003 | -0.4 | 399 | -0.3 |
| RING | 72 | 20 | 289 | 1.000 | -1.5 | 90 | -1.2 |
| RING | 72 | 40 | 614 | 1.000 | -0.4 | 140 | -0.3 |
| RING | 72 | 80 | 1352 | 1.122 | -0.1 | 254 | -0.1 |
| RING | 84 | 20 | 339 | 1.000 | -0.4 | 65 | -0.3 |
| RING | 84 | 40 | 733 | 1.000 | -0.1 | 110 | -0.1 |
| RING | 84 | 80 | 1492 | 3.265 | 0.0 | 195 | -0.0 |
| BEAM | 36 | 20 | 60 | 1.004 | -41.0 | 70 | -38.0 |
| BEAM | 36 | 40 | 105 | 1.020 | -19.1 | 105 | -15.1 |
| BEAM | 36 | 80 | 319 | 0.987 | -4.9 | 105 | -3.6 |
| BEAM | 48 | 20 | 105 | 0.998 | -17.8 | 65 | -14.2 |
| BEAM | 48 | 40 | 314 | 0.999 | -4.7 | 80 | -3.7 |
| BEAM | 48 | 80 | 753 | 0.997 | -1.1 | 229 | -0.8 |
| BEAM | 60 | 20 | 210 | 1.001 | -4.5 | 50 | -3.5 |
| BEAM | 60 | 40 | 464 | 0.996 | -1.2 | 115 | -0.9 |
| BEAM | 60 | 80 | 1053 | 1.003 | -0.3 | 195 | -0.2 |
| BEAM | 72 | 20 | 284 | 1.000 | -1.1 | 60 | -0.9 |
| BEAM | 72 | 40 | 614 | 1.000 | -0.3 | 100 | -0.2 |
| BEAM | 72 | 80 | 1367 | 1.210 | -0.0 | 180 | -0.1 |
| BEAM | 84 | 20 | 339 | 1.000 | -0.3 | 85 | -0.2 |
| BEAM | 84 | 40 | 733 | 1.000 | -0.1 | 155 | -0.1 |
| BEAM | 84 | 80 | 1502 | 3.037 | 0.0 | 279 | -0.0 |

#### I2c. √WATER inside a 2 s note (MIDI 60, voice defaults, anchor fix, 40 ms e-fold)

| voice | WATER | drift p-p cents | dist vs WATER 0 |
|---|---|---|---|
| PING | 0.00 | 2.6 | 0.0000 |
| PING | 0.05 | 5.6 | 0.0073 |
| PING | 0.10 | 7.3 | 0.0105 |
| PING | 0.20 | 8.7 | 0.0154 |
| PING | 0.65 | 14.1 | 0.0353 |
| PING | ref BEND +0.05 | - | 0.0263 |
| SING | 0.00 | 0.4 | 0.0000 |
| SING | 0.05 | 4.2 | 0.0070 |
| SING | 0.10 | 6.0 | 0.0100 |
| SING | 0.20 | 7.6 | 0.0144 |
| SING | 0.65 | 13.6 | 0.0289 |
| SING | ref BEND +0.05 | - | 0.0268 |
| BLADE | 0.00 | 26.7 | 0.0000 |
| BLADE | 0.05 | 28.6 | 0.0286 |
| BLADE | 0.10 | 29.1 | 0.0418 |
| BLADE | 0.20 | 33.4 | 0.0467 |
| BLADE | 0.65 | 61.4 | 0.0961 |
| BLADE | ref BEND +0.05 | - | 0.0275 |

#### I2d. COUPLE with the anchor fix (RING, MIDI 60, strike only, WATER 0): fundamental partial 0.2–0.6 s against the note

| COUPLE | partial cents | vessel share 300 ms |
|---|---|---|
| 0.00 | -0.00 | 0.0000 |
| 0.30 | 0.02 | 0.0959 |
| 0.60 | 0.02 | 0.5373 |
| 1.00 | 0.02 | 0.5534 |

### A3. Iteration 3 (`iteration3`)

#### MERCURY Phase-0 spike — iteration 3 (contact taper 0.5, e-fold = max(20 ms, 12 periods))

#### I3a. Pitch at the voice defaults (anchor fix, √WATER), measured 1.4–1.8 s

| voice | MIDI | detect cents | conf | partial cents | dominant mode | share |
|---|---|---|---|---|---|---|
| PING | 36 | 3.2 | 0.94 | 0.7 | 0 | 0.53 |
| PING | 48 | 0.6 | 0.97 | 0.8 | 0 | 0.60 |
| PING | 60 | -4.5 | 0.98 | 0.7 | 0 | 0.86 |
| PING | 72 | 5.8 | 0.99 | 0.7 | 0 | 1.00 |
| PING | 84 | 5.8 | 1.00 | 0.7 | 0 | 1.00 |
| SING | 36 | 3.2 | 0.94 | -0.0 | 0 | 0.53 |
| SING | 48 | 0.6 | 0.97 | -0.4 | 0 | 0.62 |
| SING | 60 | -4.5 | 0.98 | -0.4 | 0 | 0.89 |
| SING | 72 | 5.8 | 0.99 | -0.4 | 0 | 1.00 |
| SING | 84 | 5.8 | 1.00 | -0.1 | 0 | 1.00 |
| BLADE | 36 | -4.5 | 0.94 | -2.2 | 0 | 0.51 |
| BLADE | 48 | -4.5 | 0.97 | -1.9 | 0 | 0.53 |
| BLADE | 60 | -4.5 | 0.98 | -2.1 | 0 | 0.60 |
| BLADE | 72 | -14.7 | 0.99 | -1.9 | 0 | 0.76 |
| BLADE | 84 | 5.8 | 0.99 | -0.2 | 0 | 0.86 |

#### I3b. Onset (WATER 0, GLASS as voice, COUPLE 0.25): RUB 1, unseeded (its tap weight is cos 90° = 0), vs RUB 0.7 with its tap

| table | MIDI | e-fold ms | onset ms RUB 1 | cents | mode | onset ms RUB 0.7 | cents | mode |
|---|---|---|---|---|---|---|---|---|
| RING | 36 | 183 | 1127 | -0.5 | 0 | 205 | -0.2 | 0 |
| RING | 48 | 92 | 913 | -0.5 | 0 | 145 | -0.4 | 0 |
| RING | 60 | 46 | 544 | -0.6 | 0 | 105 | -0.5 | 0 |
| RING | 72 | 23 | 334 | -0.6 | 0 | 65 | -0.5 | 0 |
| RING | 84 | 20 | 339 | -0.2 | 0 | 65 | -0.1 | 0 |
| BEAM | 36 | 183 | 1068 | -0.1 | 0 | 175 | 0.3 | 0 |
| BEAM | 48 | 92 | 898 | -0.4 | 0 | 145 | -0.2 | 0 |
| BEAM | 60 | 46 | 544 | -0.5 | 0 | 100 | -0.4 | 0 |
| BEAM | 72 | 23 | 334 | -0.5 | 0 | 60 | -0.4 | 0 |
| BEAM | 84 | 20 | 339 | -0.2 | 0 | 85 | -0.1 | 0 |

#### I3c. Corners again with the iteration-3 settings (ROTATION; 32 corners × MIDI 36, 84; tap 1)

| table | all finite | max state | worst corner | mode-0 capture at RUB 1 corners |
|---|---|---|---|---|
| RING | true | 3.91e-02 | B1 R1 W1 G1 C1, 84 | 32 / 32 |
| BEAM | true | 4.88e-02 | B1 R1 W1 G1 C1, 84 | 32 / 32 |

### A4. Iteration 4 (`iteration4`)

#### MERCURY Phase-0 spike — iteration 4 (review: plain springs vs the compensated form; a real landing floor)

#### I4a. Coupling form (RING, MIDI 60, strike only, WATER 0, BEND centred)

'compensated' is iterations 1–3 (K = diag(ω²) − A); 'plain' is K = diag(ω²) + L. Energy rises are counted as in P1; pitch is the fundamental partial 0.2–0.6 s against the note, without and with the anchor fix; share is the vessel's at 300 ms.

| form | COUPLE | rises | worst ratio | pitch cents, no fix | pitch cents, fix | vessel share 300 ms |
|---|---|---|---|---|---|---|
| compensated | 0.30 | 0 | 0.996796398 | -13.4 | 0.02 | 0.1150 |
| compensated | 0.60 | 0 | 0.996802004 | -40.9 | 0.02 | 0.3100 |
| compensated | 1.00 | 0 | 0.997162000 | -85.3 | 0.02 | 0.2413 |
| plain | 0.30 | 0 | 0.996931562 | 48.0 | 0.03 | 0.0896 |
| plain | 0.60 | 0 | 0.997027945 | 82.1 | 0.02 | 0.1670 |
| plain | 1.00 | 0 | 0.997221764 | 121.0 | 0.02 | 0.2375 |

#### I4b. Plain springs at the iteration-3 voice settings (anchor fix on), measured 1.4–1.8 s

| voice | MIDI | partial cents | dominant mode |
|---|---|---|---|
| PING | 36 | 1.1 | 0 |
| PING | 60 | 1.1 | 0 |
| PING | 84 | 1.1 | 0 |
| SING | 36 | 0.5 | 0 |
| SING | 60 | 0.2 | 0 |
| SING | 84 | 0.6 | 0 |
| BLADE | 36 | -0.5 | 0 |
| BLADE | 60 | -0.2 | 0 |
| BLADE | 84 | 1.4 | 0 |

#### I4c. RUB 1 with a landing floor under the tap weight (iteration-3 settings, WATER 0, COUPLE 0.25)

Onset as in I3b. Floor 0 is I3b's unseeded RUB 1.

| table | MIDI | floor 0 | floor 0.05 | floor 0.1 | floor 0.2 | cents at 0.2 | mode at 0.2 |
|---|---|---|---|---|---|---|---|
| RING | 36 | 1127 | 1407 | 1023 | 534 | -0.3 | 0 |
| RING | 48 | 913 | 619 | 439 | 259 | -0.6 | 0 |
| RING | 60 | 544 | 309 | 234 | 155 | -0.6 | 0 |
| RING | 72 | 334 | 170 | 135 | 95 | -0.6 | 0 |
| RING | 84 | 339 | 160 | 130 | 95 | -0.2 | 0 |
| BEAM | 36 | 1068 | 1432 | 1093 | 534 | 0.1 | 0 |
| BEAM | 48 | 898 | 639 | 439 | 259 | -0.4 | 0 |
| BEAM | 60 | 544 | 309 | 229 | 155 | -0.5 | 0 |
| BEAM | 72 | 334 | 170 | 135 | 95 | -0.5 | 0 |
| BEAM | 84 | 339 | 180 | 145 | 115 | -0.2 | 0 |

## Appendix B — the model (`MercurySpike.kt`)

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * MERCURY Phase 0: a throwaway spike, not in the build. It is kept as text in
 * docs/superpowers/plans/2026-10-01-mercury-phase-0-spike.md (Appendix B).
 *
 * One modal object: [Params.primaries] primary modes plus [Params.vessels]
 * vessel modes. A strike and a friction contact both act through the same
 * contact vector b. Reciprocal springs couple the modes. BEND deforms the
 * ratios with an onset gesture, and one moving mass (WATER) loads every mode
 * from a single state. Two candidate banks run the same object:
 *
 * - ROTATION: each mode is z = ωq − i·v, rotated by r·e^{iωT} every sample
 *   (the exact damped free flow). It is then kicked by the coupling springs
 *   and by the contact force (a symplectic split).
 * - TRAPEZOID: each mode is a trapezoidal SVF (the TptSvf topology with
 *   damping set from t60 rather than floored at Q 10). Coupling is explicit,
 *   from the previous sample's displacements.
 *
 * In both banks the friction is solved implicitly against the contact
 * velocity it changes (a scalar Newton step), and is applied back through b.
 */
internal object MercurySpike {
    const val RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val LN1000 = 6.907755278982137
    private const val CTRL = 8

    /** Friction curve φ(η) = √(2a)·η·e^(−aη² + ½); its peak of 1 sits at η* = 1/√(2a) = 0.01. */
    const val FRICTION_A = 5000.0

    /** Driver (finger) speed: 3η*, on the falling side of φ. */
    const val BOW_V = 0.03
    private const val KAPPA = 0.12
    private val VESSEL_DETUNE = doubleArrayOf(0.035, -0.045, 0.06, -0.07)
    private const val FADE_LO = 14_000.0
    private const val FADE_HI = 19_000.0

    enum class Bank { ROTATION, TRAPEZOID }
    enum class Table { RING, BEAM }

    data class Params(
        val hz: Double,
        val table: Table = Table.RING,
        val bank: Bank = Bank.ROTATION,
        val seconds: Double = 2.0,
        val bend: Double = 0.5,
        val rub: Double = 0.35,
        val water: Double = 0.0,
        val glass: Double = 0.6,
        val couple: Double = 0.3,
        /** Strike scale (velocity stand-in); 0 = no strike. */
        val tap: Double = 1.0,
        /** Rub pressure as a multiple of the fundamental's linear onset threshold. */
        val pressureRatio: Double = 3.0,
        val contactSeconds: Double = 1.2,
        val gestureSeconds: Double = 0.25,
        val primaries: Int = 12,
        val vessels: Int = 4,
        val seed: Int = 1,
        /** Probe only: a forced ±sweep of every mode, in semitones, at [sweepHz]. */
        val sweepSemitones: Double = 0.0,
        val sweepHz: Double = 2.0,
        val trackEnergy: Boolean = false,
        /** Iteration 2: rescale every mode so the coupled system's anchor eigenfrequency lands on [hz]. */
        val anchorFix: Boolean = false,
        /** Iteration 2: set pressure by a target linear growth time (e-fold, s) instead of [pressureRatio]. */
        val onsetSeconds: Double? = null,
        /** Iteration 2: WATER depth ∝ √WATER and rate 0.6 + 2.4·WATER Hz (iteration 1: ∝ WATER, 0.4 + 2.6·WATER). */
        val waterSqrt: Boolean = false,
        /** Iteration 3: contact-patch taper, b_i = ratio_i^(−taper) (a finger averages short wavelengths). */
        val contactTaper: Double = 0.0,
        /** Iteration 3: e-fold = max([onsetSeconds], onsetPeriods / hz). */
        val onsetPeriods: Double = 0.0,
        /**
         * Iteration 4: plain springs, K = diag(ω²) + L. The default (iterations 1–3) is the
         * compensated form K = diag(ω²) − A: the same springs on modes whose own stiffness is
         * pre-reduced by Σ_j k_ij, so an uncoupled mode keeps ω_i on the diagonal.
         */
        val plainLaplacian: Boolean = false,
        /** Iteration 4: the finger's landing, a floor under the tap weight (RUB 1 has cos 90° = 0). */
        val tapFloor: Double = 0.0,
    )

    class Result(
        /** Pickup velocity at [RATE], raw: no band limit, no DC removal, no level. */
        val raw: FloatArray,
        /** Total energy (kinetic + modal + spring), mean over each 1 ms block. */
        val energy: DoubleArray,
        val vesselEnergy: DoubleArray,
        val maxState: Double,
        val finite: Boolean,
        val nanos: Long,
        /** Settled mode frequencies (BEND settled, WATER at its nominal load). */
        val settledHz: DoubleArray,
    )

    /** Thin ring, inextensional bending (Rayleigh): f_k ∝ k(k²−1)/√(k²+1), k = 2, 3, … */
    fun ringRatios(n: Int): DoubleArray {
        val r = DoubleArray(n) { i -> val k = i + 2.0; k * (k * k - 1) / sqrt(k * k + 1) }
        return DoubleArray(n) { r[it] / r[0] }
    }

    /** Free-free beam (Rayleigh): f_k ∝ β_k², β = 4.7300, 7.8532, 10.9956, 14.1372, then (2k+1)π/2. */
    fun beamRatios(n: Int): DoubleArray {
        val b = DoubleArray(n) { i ->
            when (i) {
                0 -> 4.7300; 1 -> 7.8532; 2 -> 10.9956; 3 -> 14.1372
                else -> (2.0 * (i + 1) + 1) * PI / 2
            }
        }
        return DoubleArray(n) { (b[it] / b[0]).pow(2) }
    }

    /** Cyclic Jacobi for a small symmetric matrix: (eigenvalues, eigenvectors as columns). */
    fun jacobi(m0: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = m0.size
        val a = Array(n) { m0[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(100) {
            var off = 0.0
            for (i in 0 until n) for (j in i + 1 until n) off += a[i][j] * a[i][j]
            if (off < 1e-30 * (a.indices.sumOf { a[it][it] * a[it][it] })) return@repeat
            for (pp in 0 until n) for (qq in pp + 1 until n) {
                if (a[pp][qq] == 0.0) continue
                val theta = (a[qq][qq] - a[pp][pp]) / (2 * a[pp][qq])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) {
                    val akp = a[k][pp]; val akq = a[k][qq]
                    a[k][pp] = c * akp - s * akq; a[k][qq] = s * akp + c * akq
                }
                for (k in 0 until n) {
                    val apk = a[pp][k]; val aqk = a[qq][k]
                    a[pp][k] = c * apk - s * aqk; a[qq][k] = s * apk + c * aqk
                }
                for (k in 0 until n) {
                    val vkp = v[k][pp]; val vkq = v[k][qq]
                    v[k][pp] = c * vkp - s * vkq; v[k][qq] = s * vkp + c * vkq
                }
            }
        }
        return DoubleArray(n) { a[it][it] } to v
    }

    fun phi(x: Double) = sqrt(2 * FRICTION_A) * x * exp(-FRICTION_A * x * x + 0.5)
    fun dphi(x: Double) = sqrt(2 * FRICTION_A) * exp(-FRICTION_A * x * x + 0.5) * (1 - 2 * FRICTION_A * x * x)

    private fun fade(f: Double) = when {
        f <= FADE_LO -> 1.0
        f >= FADE_HI -> 0.0
        else -> 0.5 * (1 + cos(PI * (f - FADE_LO) / (FADE_HI - FADE_LO)))
    }

    /** House output chain minus levelling: Tide.bandLimit at 4×, Dsp.decimate, mean removed. */
    fun condition(raw: FloatArray): FloatArray {
        val buf = raw.copyOf()
        Tide.bandLimit(buf, RATE)
        val d = Dsp.decimate(buf, Dsp.RATE)
        val m = d.average().toFloat()
        for (i in d.indices) d[i] -= m
        return d
    }

    fun render(p: Params): Result {
        val t0 = System.nanoTime()
        val rotation = p.bank == Bank.ROTATION
        val dt = 1.0 / RATE
        val frames = (p.seconds * RATE).toInt()
        val np = p.primaries
        val nv = p.vessels
        val n = np + nv
        val base = if (p.table == Table.RING) ringRatios(np) else beamRatios(np)
        val ratio0 = DoubleArray(n) { i -> if (i < np) base[i] else base[i - np] * (1 + VESSEL_DETUNE[(i - np) % 4]) }
        val isVessel = BooleanArray(n) { it >= np }
        // BEND: signed per-mode deformation; the anchor (mode 0) never moves.
        val bendA = DoubleArray(n) { i -> if (i == 0) 0.0 else 0.12 * sin(1.3 * i + 0.4) }
        val bendB = DoubleArray(n) { i -> if (i == 0) 0.0 else 0.05 * i / n }
        fun ringK(i: Int) = (if (i < np) i else i - np) + 2.0
        fun beamShape(i: Int, x: Double) = cos(((if (i < np) i else i - np) + 1.5) * PI * x)
        // Contact at the rim (ring) or the free end (beam): an antinode of every primary.
        val contact = DoubleArray(n) { i -> if (isVessel[i]) 0.0 else ratio0[i].pow(-p.contactTaper) }
        val tilt = 0.8 - 0.7 * p.glass
        val pickup = DoubleArray(n) { i ->
            if (isVessel[i]) {
                0.5 * (if ((i - np) % 2 == 0) 1 else -1)
            } else {
                val s = if (p.table == Table.RING) cos(ringK(i) * 0.4) else beamShape(i, 0.3)
                s * ratio0[i].pow(-tilt)
            }
        }
        // GLASS: correlated damping distribution (and the pickup tilt above).
        val t60Fund = 1.2 + 7.8 * p.glass
        val alpha = 1.6 - 1.2 * p.glass
        val t60Base = DoubleArray(n) { i ->
            if (isVessel[i]) 0.6 + 1.4 * p.glass else max(0.02, t60Fund * ratio0[i].pow(-alpha))
        }
        // Springs: primary neighbours, and each vessel to its partner and the next primary. Max degree 4.
        // Default form: K = diag(ω²) − A, i.e. springs ½·k·(q_i − q_j)² on modes whose own stiffness
        // is ω_i² − Σ_j k_ij; PD because κ_eff·degree ≤ 0.8 < 1 (strict diagonal dominance).
        // [Params.plainLaplacian]: K = diag(ω²) + L, the same springs with nothing pre-reduced.
        val ei = ArrayList<Int>()
        val ej = ArrayList<Int>()
        for (i in 0 until np - 1) { ei += i; ej += i + 1 }
        for (v in 0 until nv) {
            ei += np + v; ej += v
            if (v + 1 < np) { ei += np + v; ej += v + 1 }
        }
        val ne = ei.size
        val eI = ei.toIntArray()
        val eJ = ej.toIntArray()
        val kEdge = DoubleArray(ne)

        val tapW = max(cos(PI * p.rub / 2) * p.tap, p.tapFloor)
        val rubW = sin(PI * p.rub / 2)
        val pThr = 2 * LN1000 / (t60Fund * abs(dphi(BOW_V)) * contact[0] * contact[0])
        val gamma0 = 2 * LN1000 / t60Fund
        val pressure = if (p.onsetSeconds != null) {
            rubW * (gamma0 + 1 / max(p.onsetSeconds, p.onsetPeriods / p.hz)) / (abs(dphi(BOW_V)) * contact[0] * contact[0])
        } else p.pressureRatio * pThr * rubW
        val strikeFrames = ((0.4 + 0.8 * (1 - p.glass)) * 1e-3 * RATE).toInt().coerceAtLeast(2)
        val impulse = 0.03 * tapW
        val strikePeak = impulse * PI / (2 * strikeFrames * dt)
        val noiseFrames = (0.002 * RATE).toInt()
        val noise = Dsp.Noise(Dsp.seedFor("MERCURY-SPIKE", p.seed, p.hz, "strike"))
        val massRng = Dsp.Noise(Dsp.seedFor("MERCURY-SPIKE", p.seed, p.hz, "mass"))

        // WATER: one damped, circularly forced 2-D mass oscillator; every mode reads it.
        val om = 2 * PI * (if (p.waterSqrt) 0.6 + 2.4 * p.water else 0.4 + 2.6 * p.water)
        var mx = 0.3 * massRng.next()
        var my = 0.3 * massRng.next()
        var mvx = 0.0
        var mvy = 0.0
        val mu = 0.06 * (if (p.waterSqrt) sqrt(p.water) else p.water)
        val anchorNominal = 1.0 / sqrt(1 + mu * 0.18)
        val cTarget = 2 * p.bend - 1
        // Anchor fix: the springs repel near modes, so the coupled anchor is not ω0. Solve K's
        // eigenvalues once at the settled shape (WATER unloaded) and scale every mode so the
        // eigenvector that is mostly mode 0 sits on the note. k ∝ ω², so a uniform scale is exact.
        val pitchFix = if (p.anchorFix && p.couple > 0) {
            val w = DoubleArray(n) { i -> 2 * PI * p.hz * ratio0[i] * exp(bendA[i] * cTarget + bendB[i] * cTarget * cTarget) }
            val k = Array(n) { DoubleArray(n) }
            for (i in 0 until n) k[i][i] = w[i] * w[i]
            for (e in 0 until ei.size) {
                val i = ei[e]; val j = ej[e]
                val kk = KAPPA * p.couple * min(w[i], w[j]).let { it * it }
                k[i][j] -= kk; k[j][i] -= kk
                if (p.plainLaplacian) { k[i][i] += kk; k[j][j] += kk }
            }
            val (vals, vecs) = jacobi(k)
            var best = 0
            for (m in 0 until n) if (abs(vecs[0][m]) > abs(vecs[0][best])) best = m
            w[0] / sqrt(vals[best])
        } else 1.0

        val re = DoubleArray(n); val im = DoubleArray(n)
        val ic1 = DoubleArray(n); val ic2 = DoubleArray(n)
        val omega = DoubleArray(n); val cr = DoubleArray(n); val sr = DoubleArray(n)
        val a1 = DoubleArray(n); val a2 = DoubleArray(n); val a3 = DoubleArray(n)
        val q = DoubleArray(n); val v = DoubleArray(n)
        val b = DoubleArray(n); val pk = DoubleArray(n)
        val load = DoubleArray(n)
        val fc = DoubleArray(n)
        val out = FloatArray(frames)
        val blockLen = RATE / 1000
        val blocks = if (p.trackEnergy) frames / blockLen else 0
        val energy = DoubleArray(blocks)
        val vEnergy = DoubleArray(blocks)
        var eAcc = 0.0
        var veAcc = 0.0
        var maxState = 0.0
        val ctrlDt = CTRL * dt
        val contactFrames = (p.contactSeconds * RATE).toInt()
        val releaseFrames = (0.03 * RATE).toInt()
        val rampFrames = (0.02 * RATE).toInt()
        var cSum = 0.0
        var dv = BOW_V

        fun solve(rhs: Double, c: Double): Double {
            var x = dv
            repeat(8) {
                val g = x + c * phi(x) - rhs
                val gp = 1 + c * dphi(x)
                if (abs(gp) < 1e-9) return@repeat
                val nx = x - g / gp
                val done = abs(nx - x) < 1e-13
                x = nx
                if (done) { dv = x; return x }
            }
            dv = x
            return x
        }

        for (t in 0 until frames) {
            if (t % CTRL == 0) {
                val time = t * dt
                val ax = -om * mvx - om * om * mx + om * om * 0.6 * cos(om * time)
                val ay = -om * mvy - om * om * my + om * om * 0.6 * sin(om * time)
                mvx += ax * ctrlDt; mvy += ay * ctrlDt
                mx += mvx * ctrlDt; my += mvy * ctrlDt
                val rho2 = (mx * mx + my * my).coerceAtMost(1.0)
                val ang = atan2(my, mx)
                val xm = (0.5 + 0.45 * mx).coerceIn(0.0, 1.0)
                val env = exp(-time / p.gestureSeconds)
                val c = cTarget * (1 - env)
                val excursion = 2.0.pow(2 * cTarget * env / 12)
                val sweep = if (p.sweepSemitones != 0.0) 2.0.pow(p.sweepSemitones * sin(2 * PI * p.sweepHz * time) / 12) else 1.0
                for (i in 0 until n) {
                    load[i] = when {
                        mu == 0.0 -> 0.0
                        isVessel[i] -> 0.5 * rho2
                        p.table == Table.RING -> rho2 * cos(ringK(i) * ang).let { it * it }
                        else -> beamShape(i, xm).let { it * it }
                    }
                    val ratio = ratio0[i] * exp(bendA[i] * c + bendB[i] * c * c)
                    val f = (p.hz * pitchFix * excursion * sweep * ratio / sqrt(1 + mu * load[i]) / anchorNominal).coerceAtMost(0.45 * RATE)
                    val g = fade(f)
                    b[i] = contact[i] * g * (1 - 0.3 * p.water * load[i])
                    pk[i] = pickup[i] * g
                    val w = 2 * PI * f
                    omega[i] = w
                    val t60 = t60Base[i] / (1 + 4 * mu * load[i])
                    if (rotation) {
                        val r = exp(-LN1000 * dt / t60)
                        cr[i] = r * cos(w * dt); sr[i] = r * sin(w * dt)
                    } else {
                        val gg = tan(PI * f / RATE)
                        val k = 2 * LN1000 / (t60 * w)
                        a1[i] = 1 / (1 + gg * (gg + k)); a2[i] = gg * a1[i]; a3[i] = gg * a2[i]
                    }
                }
                for (e in 0 until ne) {
                    val i = eI[e]; val j = eJ[e]
                    val wm = min(omega[i], omega[j])
                    kEdge[e] = KAPPA * p.couple * (1 + 0.3 * p.water * (load[i] + load[j])) * wm * wm
                }
                cSum = 0.0
                for (i in 0 until n) cSum += if (rotation) b[i] * b[i] * dt else b[i] * b[i] * a2[i] / omega[i]
            }
            val vB = BOW_V * min(1.0, t.toDouble() / rampFrames)
            val pNow = when {
                t < contactFrames -> pressure
                t < contactFrames + releaseFrames -> pressure * (1 - (t - contactFrames).toDouble() / releaseFrames)
                else -> 0.0
            }
            var fs = 0.0
            if (impulse > 0) {
                if (t < strikeFrames) fs = strikePeak * sin(PI * t / strikeFrames)
                if (t < noiseFrames) fs += 0.1 * strikePeak * noise.next() * (1 - t.toDouble() / noiseFrames)
            }
            var o = 0.0
            if (rotation) {
                for (i in 0 until n) {
                    val r0 = re[i]; val i0 = im[i]
                    re[i] = cr[i] * r0 - sr[i] * i0
                    im[i] = sr[i] * r0 + cr[i] * i0
                    q[i] = re[i] / omega[i]
                }
                fc.fill(0.0)
                for (e in 0 until ne) {
                    val i = eI[e]; val j = eJ[e]
                    if (p.plainLaplacian) { fc[i] += kEdge[e] * (q[j] - q[i]); fc[j] += kEdge[e] * (q[i] - q[j]) }
                    else { fc[i] += kEdge[e] * q[j]; fc[j] += kEdge[e] * q[i] }
                }
                var vS = 0.0
                for (i in 0 until n) { im[i] -= fc[i] * dt; vS += b[i] * -im[i] }
                val f = if (pNow > 0) pNow * phi(solve(vB - vS, pNow * cSum)) else 0.0
                val total = f + fs
                for (i in 0 until n) {
                    im[i] -= b[i] * total * dt
                    v[i] = -im[i]
                    o += pk[i] * v[i]
                }
            } else {
                fc.fill(0.0)
                for (e in 0 until ne) {
                    val i = eI[e]; val j = eJ[e]
                    if (p.plainLaplacian) { fc[i] += kEdge[e] * (q[j] - q[i]); fc[j] += kEdge[e] * (q[i] - q[j]) }
                    else { fc[i] += kEdge[e] * q[j]; fc[j] += kEdge[e] * q[i] }
                }
                var vS0 = 0.0
                for (i in 0 until n) {
                    val x0 = (fc[i] + b[i] * fs) / (omega[i] * omega[i])
                    vS0 += b[i] * omega[i] * (a1[i] * ic1[i] + a2[i] * (x0 - ic2[i]))
                }
                val f = if (pNow > 0) pNow * phi(solve(vB - vS0, pNow * cSum)) else 0.0
                for (i in 0 until n) {
                    val x = (fc[i] + b[i] * (fs + f)) / (omega[i] * omega[i])
                    val v3 = x - ic2[i]
                    val v1 = a1[i] * ic1[i] + a2[i] * v3
                    val v2 = ic2[i] + a2[i] * ic1[i] + a3[i] * v3
                    ic1[i] = 2 * v1 - ic1[i]; ic2[i] = 2 * v2 - ic2[i]
                    v[i] = omega[i] * v1; q[i] = v2
                    o += pk[i] * v[i]
                }
            }
            out[t] = o.toFloat()
            for (i in 0 until n) {
                val s = max(abs(v[i]), abs(omega[i] * q[i]))
                if (s > maxState) maxState = s
            }
            if (p.trackEnergy) {
                var e = 0.0
                var ve = 0.0
                for (i in 0 until n) {
                    val ei2 = 0.5 * (v[i] * v[i] + omega[i] * omega[i] * q[i] * q[i])
                    e += ei2
                    if (isVessel[i]) ve += ei2
                }
                for (k in 0 until ne) {
                    val qi = q[eI[k]]; val qj = q[eJ[k]]
                    e += if (p.plainLaplacian) 0.5 * kEdge[k] * (qi - qj) * (qi - qj) else -kEdge[k] * qi * qj
                }
                eAcc += e; veAcc += ve
                if ((t + 1) % blockLen == 0) {
                    val bi = (t + 1) / blockLen - 1
                    if (bi < blocks) { energy[bi] = eAcc / blockLen; vEnergy[bi] = veAcc / blockLen }
                    eAcc = 0.0; veAcc = 0.0
                }
            }
        }
        val finite = out.all { it.isFinite() } && maxState.isFinite()
        val settled = DoubleArray(n) { i ->
            p.hz * ratio0[i] * exp(bendA[i] * cTarget + bendB[i] * cTarget * cTarget)
        }
        return Result(out, energy, vEnergy, maxState, finite, System.nanoTime() - t0, settled)
    }
}
```

## Appendix C — the probes (`MercurySpikeTest.kt`)

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.synth.MercurySpike.Bank
import com.snipsnap.synth.MercurySpike.Params
import com.snipsnap.synth.MercurySpike.Table
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MERCURY Phase 0: the probes. Print-only; the one assertion is true. Writes
 * its tables to $MERCURY_SPIKE_OUT (or build/mercury-spike.md) and to stdout.
 */
class MercurySpikeTest {
    private val sb = StringBuilder()
    private fun line(s: String = "") { sb.appendLine(s); println(s) }
    private fun f(x: Double, d: Int = 2) = String.format(Locale.ROOT, "%.${d}f", x)
    private fun e(x: Double) = String.format(Locale.ROOT, "%.2e", x)
    private val out44 = Dsp.RATE

    private fun midiHz(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)
    private fun cents(a: Double, b: Double) = 1200 * ln(a / b) / ln(2.0)

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        val a = from.coerceIn(0, x.size); val z = to.coerceIn(a, x.size)
        for (i in a until z) s += x[i].toDouble() * x[i]
        return if (z > a) sqrt(s / (z - a)) else 0.0
    }

    /** Hann-windowed DFT magnitude of x[from, from+len) at [hz]. */
    private fun mag(x: FloatArray, hz: Double, from: Int, len: Int): Double {
        var cs = 0.0; var sn = 0.0
        val w = 2 * PI * hz / out44
        for (k in 0 until len) {
            val i = from + k
            if (i >= x.size) break
            val win = 0.5 - 0.5 * cos(2 * PI * k / (len - 1))
            cs += x[i] * win * cos(w * k); sn += x[i] * win * sin(w * k)
        }
        return hypot(cs, sn)
    }

    /** Strongest frequency within ±[span] cents of [hz], 0.5-cent scan, parabolic refine. Returns (hz, magnitude). */
    private fun fine(x: FloatArray, hz: Double, from: Int, len: Int, span: Double = 60.0, step: Double = 0.5): Pair<Double, Double> {
        val steps = (2 * span / step).toInt()
        val m = DoubleArray(steps + 1) { mag(x, hz * 2.0.pow((-span + it * step) / 1200), from, len) }
        var best = 0
        for (i in m.indices) if (m[i] > m[best]) best = i
        var c = -span + best * step
        if (best in 1 until steps) {
            val a = m[best - 1]; val b = m[best]; val d = m[best + 1]
            val den = a - 2 * b + d
            if (den != 0.0) c += 0.5 * (a - d) / den * step
        }
        return hz * 2.0.pow(c / 1200) to m[best]
    }

    private fun dominant(x: FloatArray, settled: DoubleArray, from: Int, len: Int): Triple<Int, Double, Double> {
        var bi = -1; var bm = 0.0; var bhz = 0.0; var total = 0.0
        for (i in settled.indices) {
            if (settled[i] > 18_000) continue
            val (hz, m) = fine(x, settled[i], from, len, span = 40.0, step = 2.0)
            total += m * m
            if (m > bm) { bm = m; bi = i; bhz = hz }
        }
        return Triple(bi, bhz, if (total > 0) bm * bm / total else 0.0)
    }

    private fun dec(p: Params) = MercurySpike.condition(MercurySpike.render(p).raw)

    @Test
    fun spike() {
        // Warm the JIT.
        repeat(2) { MercurySpike.render(Params(hz = 262.0, seconds = 1.0)) }
        line("# MERCURY Phase-0 spike — probe output")
        line()
        p1Passive()
        p2Retune()
        p3Pitch()
        p4Onset()
        p5Water()
        p6Couple()
        p7Corners()
        p8Classifier()
        p9Cost()
        val path = System.getenv("MERCURY_SPIKE_OUT") ?: "build/mercury-spike.md"
        File(path).writeText(sb.toString())
        assertTrue(true)
    }

    @Test
    fun iteration2() {
        repeat(2) { MercurySpike.render(Params(hz = 262.0, seconds = 1.0)) }
        line("# MERCURY Phase-0 spike — iteration 2 (anchor fix, onset-set pressure, √WATER)")
        line()
        i2Pitch()
        i2Onset()
        i2Water()
        i2Couple()
        val path = System.getenv("MERCURY_SPIKE_OUT2") ?: "build/mercury-spike-2.md"
        File(path).writeText(sb.toString())
        assertTrue(true)
    }

    private fun v2(v: Voice, hz: Double, onset: Double = 0.04) = voiceParams(v, hz).copy(anchorFix = true, waterSqrt = true, onsetSeconds = onset)

    private fun i2Pitch() {
        line("## I2a. Pitch with the anchor fix (voice defaults, √WATER, pressure for a 40 ms e-fold), measured 1.4–1.8 s")
        line()
        line("| voice | MIDI | detect cents | partial cents | dominant mode |")
        line("|---|---|---|---|---|")
        for (v in voices) for (m in listOf(36, 48, 60, 72, 84)) {
            val hz = midiHz(m)
            val r = MercurySpike.render(v2(v, hz))
            val d = MercurySpike.condition(r.raw)
            val from = (1.4 * out44).toInt(); val len = (0.4 * out44).toInt()
            val est = Pitch.detect(Snip(d, 1, out44), fromSec = 1.4f, windowSec = 0.25f)
            val (ph, _) = fine(d, hz, from, len)
            val (dm, _, _) = dominant(d, r.settledHz, from, len)
            line("| ${v.name} | $m | ${est?.let { f(cents(it.hz.toDouble(), hz), 1) } ?: "none"} | ${f(cents(ph, hz), 1)} | $dm |")
        }
        line()
    }

    private fun i2Onset() {
        line("## I2b. Onset with pressure set by a target e-fold time (RUB 1 vs RUB 0.7 with its tap; WATER 0; anchor fix)")
        line()
        line("'onset' is the first 5 ms window at 90 % of the 1.3–1.6 s RMS; 'flat' as in P4; 'cents' is the fundamental partial against the note (pressure flattening).")
        line()
        line("| table | MIDI | e-fold ms | onset ms RUB 1 | flat | cents | onset ms RUB 0.7 + tap | cents |")
        line("|---|---|---|---|---|---|---|---|")
        for ((table, glass) in listOf(Table.RING to 0.85, Table.BEAM to 0.45)) for (m in listOf(36, 48, 60, 72, 84)) for (tau in listOf(0.02, 0.04, 0.08)) {
            val hz = midiHz(m)
            val res = listOf(1.0, 0.7).map { rub ->
                val d = dec(Params(hz = hz, table = table, seconds = 1.7, contactSeconds = 1.6, rub = rub, tap = 1.0, water = 0.0, bend = 0.5, glass = glass, couple = 0.25, onsetSeconds = tau, anchorFix = true))
                val a = (1.3 * out44).toInt(); val mid = (1.45 * out44).toInt(); val z = (1.6 * out44).toInt()
                val steady = rms(d, a, z)
                val flat = rms(d, mid, z) / rms(d, a, mid).coerceAtLeast(1e-30)
                val w = out44 / 200
                var s = 0; var onset = -1.0
                while (s + w < z) { if (rms(d, s, s + w) >= 0.9 * steady) { onset = 1000.0 * s / out44; break }; s += w }
                val (ph, _) = fine(d, hz, a, z - a)
                Triple(onset, flat, cents(ph, hz))
            }
            line("| $table | $m | ${f(tau * 1000, 0)} | ${f(res[0].first, 0)} | ${f(res[0].second, 3)} | ${f(res[0].third, 1)} | ${f(res[1].first, 0)} | ${f(res[1].third, 1)} |")
        }
        line()
    }

    private fun i2Water() {
        line("## I2c. √WATER inside a 2 s note (MIDI 60, voice defaults, anchor fix, 40 ms e-fold)")
        line()
        line("| voice | WATER | drift p-p cents | dist vs WATER 0 |")
        line("|---|---|---|---|")
        for (v in voices) {
            val hz = midiHz(60)
            val ref = dec(v2(v, hz).copy(water = 0.0))
            for (w in listOf(0.0, 0.05, 0.10, 0.20, 0.65)) {
                val d = dec(v2(v, hz).copy(water = w))
                var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
                var s = (0.3 * out44).toInt()
                val win = (0.05 * out44).toInt()
                while (s + win < (1.9 * out44).toInt()) {
                    val c = cents(fine(d, hz, s, win, span = 40.0, step = 1.0).first, hz)
                    lo = minOf(lo, c); hi = max(hi, c); s += win
                }
                line("| ${v.name} | ${f(w)} | ${f(hi - lo, 1)} | ${f(specDistance(ref, d, (0.3 * out44).toInt(), (1.9 * out44).toInt()), 4)} |")
            }
            val bent = dec(v2(v, hz).copy(water = 0.0, bend = v.bend + 0.05))
            line("| ${v.name} | ref BEND +0.05 | - | ${f(specDistance(ref, bent, (0.3 * out44).toInt(), (1.9 * out44).toInt()), 4)} |")
        }
        line()
    }

    private fun i2Couple() {
        line("## I2d. COUPLE with the anchor fix (RING, MIDI 60, strike only, WATER 0): fundamental partial 0.2–0.6 s against the note")
        line()
        line("| COUPLE | partial cents | vessel share 300 ms |")
        line("|---|---|---|")
        val hz = midiHz(60)
        for (c in listOf(0.0, 0.3, 0.6, 1.0)) {
            val r = MercurySpike.render(Params(hz = hz, rub = 0.0, couple = c, water = 0.0, anchorFix = true, trackEnergy = true))
            val d = MercurySpike.condition(r.raw)
            val (h, _) = fine(d, hz, (0.2 * out44).toInt(), (0.4 * out44).toInt())
            line("| ${f(c)} | ${f(cents(h, hz), 2)} | ${f(r.vesselEnergy[300] / r.energy[300], 4)} |")
        }
        line()
    }

    @Test
    fun iteration3() {
        repeat(2) { MercurySpike.render(Params(hz = 262.0, seconds = 1.0)) }
        line("# MERCURY Phase-0 spike — iteration 3 (contact taper 0.5, e-fold = max(20 ms, 12 periods))")
        line()
        line("## I3a. Pitch at the voice defaults (anchor fix, √WATER), measured 1.4–1.8 s")
        line()
        line("| voice | MIDI | detect cents | conf | partial cents | dominant mode | share |")
        line("|---|---|---|---|---|---|---|")
        for (v in voices) for (m in listOf(36, 48, 60, 72, 84)) {
            val hz = midiHz(m)
            val r = MercurySpike.render(v3(v, hz))
            val d = MercurySpike.condition(r.raw)
            val from = (1.4 * out44).toInt(); val len = (0.4 * out44).toInt()
            val est = Pitch.detect(Snip(d, 1, out44), fromSec = 1.4f, windowSec = 0.25f)
            val (ph, _) = fine(d, hz, from, len)
            val (dm, _, share) = dominant(d, r.settledHz, from, len)
            line("| ${v.name} | $m | ${est?.let { f(cents(it.hz.toDouble(), hz), 1) } ?: "none"} | ${est?.let { f(it.confidence.toDouble()) } ?: "-"} | ${f(cents(ph, hz), 1)} | $dm | ${f(share)} |")
        }
        line()
        line("## I3b. Onset (WATER 0, GLASS as voice, COUPLE 0.25): RUB 1, unseeded (its tap weight is cos 90° = 0), vs RUB 0.7 with its tap")
        line()
        line("| table | MIDI | e-fold ms | onset ms RUB 1 | cents | mode | onset ms RUB 0.7 | cents | mode |")
        line("|---|---|---|---|---|---|---|---|---|")
        for ((table, glass) in listOf(Table.RING to 0.85, Table.BEAM to 0.45)) for (m in listOf(36, 48, 60, 72, 84)) {
            val hz = midiHz(m)
            val res = listOf(1.0 to 0.0, 0.7 to 1.0).map { (rub, tap) ->
                val r = MercurySpike.render(Params(hz = hz, table = table, seconds = 1.7, contactSeconds = 1.6, rub = rub, tap = tap, water = 0.0, bend = 0.5, glass = glass, couple = 0.25, onsetSeconds = 0.02, onsetPeriods = 12.0, anchorFix = true, contactTaper = 0.5))
                val d = MercurySpike.condition(r.raw)
                val a = (1.3 * out44).toInt(); val z = (1.6 * out44).toInt()
                val steady = rms(d, a, z)
                val w = out44 / 200
                var s = 0; var onset = -1.0
                while (s + w < z) { if (rms(d, s, s + w) >= 0.9 * steady) { onset = 1000.0 * s / out44; break }; s += w }
                val (ph, _) = fine(d, hz, a, z - a)
                val (dm, _, _) = dominant(d, r.settledHz, a, z - a)
                Triple(onset, cents(ph, hz), dm)
            }
            val tau = 1000 * max(0.02, 12 / hz)
            line("| $table | $m | ${f(tau, 0)} | ${f(res[0].first, 0)} | ${f(res[0].second, 1)} | ${res[0].third} | ${f(res[1].first, 0)} | ${f(res[1].second, 1)} | ${res[1].third} |")
        }
        line()
        line("## I3c. Corners again with the iteration-3 settings (ROTATION; 32 corners × MIDI 36, 84; tap 1)")
        line()
        line("| table | all finite | max state | worst corner | mode-0 capture at RUB 1 corners |")
        line("|---|---|---|---|---|")
        for (table in Table.values()) {
            var finite = true; var worst = 0.0; var worstName = ""; var cap = 0; var rubCorners = 0
            for (mask in 0 until 32) for (m in listOf(36, 84)) {
                val bit = { k: Int -> if (mask shr k and 1 == 1) 1.0 else 0.0 }
                val r = MercurySpike.render(Params(hz = midiHz(m), table = table, seconds = 2.0, contactSeconds = 1.6, bend = bit(0), rub = bit(1), water = bit(2), glass = bit(3), couple = bit(4), tap = 1.0, onsetSeconds = 0.02, onsetPeriods = 12.0, anchorFix = true, contactTaper = 0.5, waterSqrt = true))
                finite = finite && r.finite
                if (r.maxState > worst) { worst = r.maxState; worstName = "B${bit(0).toInt()} R${bit(1).toInt()} W${bit(2).toInt()} G${bit(3).toInt()} C${bit(4).toInt()}, $m" }
                if (bit(1) == 1.0) {
                    rubCorners++
                    val d = MercurySpike.condition(r.raw)
                    val a = (1.3 * out44).toInt(); val z = (1.6 * out44).toInt()
                    if (dominant(d, r.settledHz, a, z - a).first == 0) cap++
                }
            }
            line("| $table | $finite | ${e(worst)} | $worstName | $cap / $rubCorners |")
        }
        line()
        val path = System.getenv("MERCURY_SPIKE_OUT3") ?: "build/mercury-spike-3.md"
        File(path).writeText(sb.toString())
        assertTrue(true)
    }

    @Test
    fun iteration4() {
        repeat(2) { MercurySpike.render(Params(hz = 262.0, seconds = 1.0)) }
        line("# MERCURY Phase-0 spike — iteration 4 (review: plain springs vs the compensated form; a real landing floor)")
        line()
        line("## I4a. Coupling form (RING, MIDI 60, strike only, WATER 0, BEND centred)")
        line()
        line("'compensated' is iterations 1–3 (K = diag(ω²) − A); 'plain' is K = diag(ω²) + L. Energy rises are counted as in P1; pitch is the fundamental partial 0.2–0.6 s against the note, without and with the anchor fix; share is the vessel's at 300 ms.")
        line()
        line("| form | COUPLE | rises | worst ratio | pitch cents, no fix | pitch cents, fix | vessel share 300 ms |")
        line("|---|---|---|---|---|---|---|")
        val hz = midiHz(60)
        for (plain in listOf(false, true)) for (c in listOf(0.3, 0.6, 1.0)) {
            val base = Params(hz = hz, rub = 0.0, couple = c, water = 0.0, plainLaplacian = plain, trackEnergy = true)
            val r = MercurySpike.render(base)
            var rises = 0; var worst = 0.0
            for (k in 3 until r.energy.size - 1) { val ratio = r.energy[k + 1] / r.energy[k]; if (ratio > 1 + 1e-12) rises++; worst = max(worst, ratio) }
            val d0 = MercurySpike.condition(r.raw)
            val d1 = dec(base.copy(anchorFix = true))
            val from = (0.2 * out44).toInt(); val len = (0.4 * out44).toInt()
            val c0 = cents(fine(d0, hz, from, len, span = 600.0, step = 1.0).first, hz)
            val c1 = cents(fine(d1, hz, from, len).first, hz)
            line("| ${if (plain) "plain" else "compensated"} | ${f(c)} | $rises | ${f(worst, 9)} | ${f(c0, 1)} | ${f(c1, 2)} | ${f(r.vesselEnergy[300] / r.energy[300], 4)} |")
        }
        line()
        line("## I4b. Plain springs at the iteration-3 voice settings (anchor fix on), measured 1.4–1.8 s")
        line()
        line("| voice | MIDI | partial cents | dominant mode |")
        line("|---|---|---|---|")
        for (v in voices) for (m in listOf(36, 60, 84)) {
            val hz2 = midiHz(m)
            val r = MercurySpike.render(v3(v, hz2).copy(plainLaplacian = true))
            val d = MercurySpike.condition(r.raw)
            val from = (1.4 * out44).toInt(); val len = (0.4 * out44).toInt()
            line("| ${v.name} | $m | ${f(cents(fine(d, hz2, from, len).first, hz2), 1)} | ${dominant(d, r.settledHz, from, len).first} |")
        }
        line()
        line("## I4c. RUB 1 with a landing floor under the tap weight (iteration-3 settings, WATER 0, COUPLE 0.25)")
        line()
        line("Onset as in I3b. Floor 0 is I3b's unseeded RUB 1.")
        line()
        line("| table | MIDI | floor 0 | floor 0.05 | floor 0.1 | floor 0.2 | cents at 0.2 | mode at 0.2 |")
        line("|---|---|---|---|---|---|---|---|")
        for ((table, glass) in listOf(Table.RING to 0.85, Table.BEAM to 0.45)) for (m in listOf(36, 48, 60, 72, 84)) {
            val hz2 = midiHz(m)
            val res = listOf(0.0, 0.05, 0.1, 0.2).map { fl ->
                val r = MercurySpike.render(Params(hz = hz2, table = table, seconds = 1.7, contactSeconds = 1.6, rub = 1.0, tap = 1.0, tapFloor = fl, water = 0.0, bend = 0.5, glass = glass, couple = 0.25, onsetSeconds = 0.02, onsetPeriods = 12.0, anchorFix = true, contactTaper = 0.5))
                val d = MercurySpike.condition(r.raw)
                val a = (1.3 * out44).toInt(); val z = (1.6 * out44).toInt()
                val steady = rms(d, a, z); val w = out44 / 200
                var s = 0; var onset = -1.0
                while (s + w < z) { if (rms(d, s, s + w) >= 0.9 * steady) { onset = 1000.0 * s / out44; break }; s += w }
                Triple(onset, cents(fine(d, hz2, a, z - a).first, hz2), dominant(d, r.settledHz, a, z - a).first)
            }
            line("| $table | $m | ${f(res[0].first, 0)} | ${f(res[1].first, 0)} | ${f(res[2].first, 0)} | ${f(res[3].first, 0)} | ${f(res[3].second, 1)} | ${res[3].third} |")
        }
        line()
        val path = System.getenv("MERCURY_SPIKE_OUT4") ?: "build/mercury-spike-4.md"
        File(path).writeText(sb.toString())
        assertTrue(true)
    }

    private fun v3(v: Voice, hz: Double) = voiceParams(v, hz).copy(anchorFix = true, waterSqrt = true, onsetSeconds = 0.02, onsetPeriods = 12.0, contactTaper = 0.5)

    /** P1. Strike, then nothing: total energy must never rise (fixed geometry, no mass motion). */
    private fun p1Passive() {
        line("## P1. Passive decay: strike only, BEND centred, WATER 0")
        line()
        line("Total energy (kinetic + modal + spring) per 1 ms block, from 3 ms (after the strike) to 1.5 s. 'rises' counts blocks above their predecessor; 'worst' is the largest block-to-block ratio.")
        line()
        line("| bank | table | COUPLE | rises | worst ratio | E(1 s)/E(10 ms) |")
        line("|---|---|---|---|---|---|")
        for (bank in Bank.values()) for (table in Table.values()) for (c in listOf(0.0, 0.3, 0.6, 1.0)) {
            val r = MercurySpike.render(Params(hz = 262.0, table = table, bank = bank, seconds = 1.5, rub = 0.0, bend = 0.5, water = 0.0, couple = c, trackEnergy = true))
            var rises = 0; var worst = 0.0
            for (k in 3 until r.energy.size - 1) {
                val ratio = r.energy[k + 1] / r.energy[k]
                if (ratio > 1 + 1e-12) rises++
                worst = max(worst, ratio)
            }
            line("| $bank | $table | ${f(c)} | $rises | ${f(worst, 9)} | ${e(r.energy[999] / r.energy[9])} |")
        }
        line()
        line("Modulated (parameter work measured, not forbidden): BEND 1 gesture, WATER 1, COUPLE 1, strike only.")
        line()
        line("| bank | table | rises | worst ratio | E(1 s)/E(10 ms) |")
        line("|---|---|---|---|---|")
        for (bank in Bank.values()) for (table in Table.values()) {
            val r = MercurySpike.render(Params(hz = 262.0, table = table, bank = bank, seconds = 1.5, rub = 0.0, bend = 1.0, water = 1.0, couple = 1.0, trackEnergy = true))
            var rises = 0; var worst = 0.0
            for (k in 3 until r.energy.size - 1) {
                val ratio = r.energy[k + 1] / r.energy[k]
                if (ratio > 1 + 1e-12) rises++
                worst = max(worst, ratio)
            }
            line("| $bank | $table | $rises | ${f(worst, 6)} | ${e(r.energy[999] / r.energy[9])} |")
        }
        line()
    }

    /** P2. Does retuning pump amplitude? ±12 semitones at 2 Hz on four free modes vs the same note unswept. */
    private fun p2Retune() {
        line("## P2. Retune under a ±12-semitone, 2 Hz sweep (4 primaries, no vessel, COUPLE 0, strike only)")
        line()
        line("Ratio of total energy, swept over static, per 1 ms block from 10 ms to 1.4 s; and the 20 ms RMS of the pickup in dB, swept over static.")
        line()
        line("| bank | energy ratio min | energy ratio max | pickup dB min | pickup dB max |")
        line("|---|---|---|---|---|")
        for (bank in Bank.values()) {
            val base = Params(hz = 262.0, bank = bank, seconds = 1.5, rub = 0.0, couple = 0.0, glass = 1.0, primaries = 4, vessels = 0, trackEnergy = true)
            val a = MercurySpike.render(base)
            val b = MercurySpike.render(base.copy(sweepSemitones = 12.0))
            var lo = Double.MAX_VALUE; var hi = 0.0
            for (k in 10 until 1400) { val r = b.energy[k] / a.energy[k]; lo = minOf(lo, r); hi = max(hi, r) }
            var dlo = Double.MAX_VALUE; var dhi = -Double.MAX_VALUE
            val w = MercurySpike.RATE / 50
            var s = w
            while (s + w < (1.4 * MercurySpike.RATE).toInt()) {
                val d = 20 * log10(rms(b.raw, s, s + w) / rms(a.raw, s, s + w))
                dlo = minOf(dlo, d); dhi = max(dhi, d); s += w
            }
            line("| $bank | ${f(lo, 4)} | ${f(hi, 4)} | ${f(dlo)} | ${f(dhi)} |")
        }
        line()
    }

    private data class Voice(val name: String, val table: Table, val bend: Double, val rub: Double, val water: Double, val glass: Double, val couple: Double, val gesture: Double)

    private val voices = listOf(
        Voice("PING", Table.RING, 0.50, 0.08, 0.10, 0.75, 0.20, 0.08),
        Voice("SING", Table.RING, 0.50, 0.85, 0.12, 0.85, 0.25, 0.6),
        Voice("BLADE", Table.BEAM, 0.65, 0.80, 0.15, 0.45, 0.30, 0.4),
    )

    private fun voiceParams(v: Voice, hz: Double, bank: Bank = Bank.ROTATION) = Params(
        hz = hz, table = v.table, bank = bank, seconds = 2.5, contactSeconds = 2.0,
        bend = v.bend, rub = v.rub, water = v.water, glass = v.glass, couple = v.couple, gestureSeconds = v.gesture,
    )

    /** P3. Pitch at the R1 voices' spec defaults, MIDI 36–84. */
    private fun p3Pitch() {
        line("## P3. Pitch at the spec's voice defaults (ROTATION unless marked), measured 1.4–1.8 s")
        line()
        line("`Pitch.detect` (the house detector, integer-lag autocorrelation, 0.25 s) and the fundamental partial's frequency (Hann DFT scan, 0.4 s). Cents against the requested note. WATER is on at the defaults, so a few cents of drift are the design, not an error.")
        line()
        line("| voice | MIDI | detect cents | conf | partial cents | dominant mode | share |")
        line("|---|---|---|---|---|---|---|")
        for (v in voices) for (m in listOf(36, 48, 60, 72, 84)) {
            val hz = midiHz(m)
            val r = MercurySpike.render(voiceParams(v, hz))
            val d = MercurySpike.condition(r.raw)
            val from = (1.4 * out44).toInt()
            val est = Pitch.detect(Snip(d, 1, out44), fromSec = 1.4f, windowSec = 0.25f)
            val (ph, _) = fine(d, hz, from, (0.4 * out44).toInt())
            val (dm, _, share) = dominant(d, r.settledHz, from, (0.4 * out44).toInt())
            line("| ${v.name} | $m | ${est?.let { f(cents(it.hz.toDouble(), hz), 1) } ?: "none"} | ${est?.let { f(it.confidence.toDouble()) } ?: "-"} | ${f(cents(ph, hz), 1)} | $dm | ${f(share)} |")
        }
        line()
        line("TRAPEZOID, PING and SING at MIDI 60:")
        line()
        line("| voice | detect cents | partial cents |")
        line("|---|---|---|")
        for (v in voices.take(2)) {
            val hz = midiHz(60)
            val d = dec(voiceParams(v, hz, Bank.TRAPEZOID))
            val est = Pitch.detect(Snip(d, 1, out44), fromSec = 1.4f, windowSec = 0.25f)
            val (ph, _) = fine(d, hz, (1.4 * out44).toInt(), (0.4 * out44).toInt())
            line("| ${v.name} | ${est?.let { f(cents(it.hz.toDouble(), hz), 1) } ?: "none"} | ${f(cents(ph, hz), 1)} |")
        }
        line()
    }

    /** P4. Rub onset: friction only (RUB 1, no strike), pressure as a multiple of the linear threshold. */
    private fun p4Onset() {
        line("## P4. Rub onset and sustain (RUB 1, no strike, contact 0–1.6 s, WATER 0, BEND centred)")
        line()
        line("'steady' is the RMS over 1.3–1.6 s; 'onset' is the first 5 ms window at 90 % of it; 'flat' is RMS(1.45–1.6)/RMS(1.3–1.45); 'mode' is the strongest mode and 'cents' its offset from that mode's own frequency.")
        line()
        line("| table | GLASS | MIDI | pressure × thr | steady | onset ms | flat | mode | cents |")
        line("|---|---|---|---|---|---|---|---|---|")
        for ((table, glass) in listOf(Table.RING to 0.85, Table.BEAM to 0.45)) for (m in listOf(36, 48, 60, 72, 84)) for (pr in listOf(1.2, 2.0, 4.0, 8.0, 16.0)) {
            val hz = midiHz(m)
            val r = MercurySpike.render(Params(hz = hz, table = table, seconds = 1.7, contactSeconds = 1.6, rub = 1.0, tap = 0.0, water = 0.0, bend = 0.5, glass = glass, couple = 0.25, pressureRatio = pr))
            val d = MercurySpike.condition(r.raw)
            val a = (1.3 * out44).toInt(); val mid = (1.45 * out44).toInt(); val z = (1.6 * out44).toInt()
            val steady = rms(d, a, z)
            val flat = rms(d, mid, z) / rms(d, a, mid).coerceAtLeast(1e-30)
            val w = out44 / 200
            var onset = -1.0
            var s = 0
            while (s + w < z) { if (rms(d, s, s + w) >= 0.9 * steady) { onset = 1000.0 * s / out44; break }; s += w }
            val (dm, dhz, _) = dominant(d, r.settledHz, a, z - a)
            val dc = if (dm >= 0) f(cents(dhz, r.settledHz[dm]), 1) else "-"
            line("| $table | ${f(glass)} | $m | ${f(pr, 1)} | ${e(steady)} | ${if (onset >= 0) f(onset, 0) else "-"} | ${f(flat, 3)} | $dm | $dc |")
        }
        line()
        line("The tap as a seed (BORE's POP): RING, GLASS 0.85, pressure 2× threshold, RUB 1 (no tap) vs RUB 0.7 (tap weight 0.45).")
        line()
        line("| MIDI | onset ms, RUB 1 | onset ms, RUB 0.7 |")
        line("|---|---|---|")
        for (m in listOf(36, 60, 84)) {
            val res = listOf(1.0, 0.7).map { rub ->
                val d = dec(Params(hz = midiHz(m), seconds = 1.7, contactSeconds = 1.6, rub = rub, water = 0.0, glass = 0.85, couple = 0.25, pressureRatio = 2.0 / kotlin.math.sin(PI * rub / 2)))
                val a = (1.3 * out44).toInt(); val z = (1.6 * out44).toInt()
                val steady = rms(d, a, z); val w = out44 / 200
                var s = 0; var onset = -1.0
                while (s + w < z) { if (rms(d, s, s + w) >= 0.9 * steady) { onset = 1000.0 * s / out44; break }; s += w }
                onset
            }
            line("| $m | ${f(res[0], 0)} | ${f(res[1], 0)} |")
        }
        line()
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }

    /** Mean over frames of the level-aligned spectral distance ‖|A|−|B|·g‖/‖|A|‖, 2048-point frames, hop 1024. */
    private fun specDistance(a: FloatArray, b: FloatArray, from: Int, to: Int): Double {
        val n = 2048
        var sum = 0.0; var count = 0
        var s = from
        while (s + n <= to) {
            val ma = DoubleArray(n / 2); val mb = DoubleArray(n / 2)
            for ((x, m) in listOf(a to ma, b to mb)) {
                val re = DoubleArray(n) { x[s + it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }
                val im = DoubleArray(n)
                fft(re, im)
                for (k in 0 until n / 2) m[k] = hypot(re[k], im[k])
            }
            val na = sqrt(ma.sumOf { it * it }); val nb = sqrt(mb.sumOf { it * it })
            if (na > 0 && nb > 0) {
                var d = 0.0
                for (k in 0 until n / 2) { val x = ma[k] / na - mb[k] / nb; d += x * x }
                sum += sqrt(d); count++
            }
            s += n / 2
        }
        return if (count > 0) sum / count else 0.0
    }

    /** P5. Is a small WATER measurable inside a 2 s note? */
    private fun p5Water() {
        line("## P5. WATER inside a 2 s note (MIDI 60; other macros at the voice defaults)")
        line()
        line("'drift' is the peak-to-peak of the fundamental partial in cents over 50 ms windows, 0.3–1.9 s. 'dist' is the level-aligned spectral distance from the same voice at WATER 0. Reference: the same distance between BEND 0.50 and BEND 0.55 at WATER 0.")
        line()
        line("| voice | WATER | drift p-p cents | dist vs WATER 0 |")
        line("|---|---|---|---|")
        for (v in voices) {
            val hz = midiHz(60)
            val ref = dec(voiceParams(v, hz).copy(water = 0.0))
            for (w in listOf(0.0, 0.05, 0.10, 0.20, 0.65)) {
                val d = dec(voiceParams(v, hz).copy(water = w))
                var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
                var s = (0.3 * out44).toInt()
                val win = (0.05 * out44).toInt()
                while (s + win < (1.9 * out44).toInt()) {
                    val c = cents(fine(d, hz, s, win, span = 40.0, step = 1.0).first, hz)
                    lo = minOf(lo, c); hi = max(hi, c); s += win
                }
                line("| ${v.name} | ${f(w)} | ${f(hi - lo, 1)} | ${f(specDistance(ref, d, (0.3 * out44).toInt(), (1.9 * out44).toInt()), 4)} |")
            }
            val bent = dec(voiceParams(v, hz).copy(water = 0.0, bend = v.bend + 0.05))
            line("| ${v.name} | ref BEND +0.05 | - | ${f(specDistance(ref, bent, (0.3 * out44).toInt(), (1.9 * out44).toInt()), 4)} |")
        }
        line()
    }

    /** P6. Does COUPLE move energy into the vessel, and what does it do to the pitch? */
    private fun p6Couple() {
        line("## P6. COUPLE: vessel energy share after a strike (RING, MIDI 60, RUB 0, BEND centred)")
        line()
        line("Vessel share = vessel energy / total energy (the vessel is never struck; b = 0). 'pitch' is the fundamental partial 0.2–0.6 s against COUPLE 0, in cents. With WATER 0.5, 'swing' is the max − min of the share over 0.2–1.0 s.")
        line()
        line("| COUPLE | WATER | share 10 ms | share 100 ms | share 300 ms | share 600 ms | swing | pitch cents |")
        line("|---|---|---|---|---|---|---|---|")
        val hz = midiHz(60)
        val zero = dec(Params(hz = hz, rub = 0.0, couple = 0.0, water = 0.0))
        val (h0, _) = fine(zero, hz, (0.2 * out44).toInt(), (0.4 * out44).toInt())
        for (w in listOf(0.0, 0.5)) for (c in listOf(0.0, 0.3, 0.6, 1.0)) {
            val r = MercurySpike.render(Params(hz = hz, rub = 0.0, couple = c, water = w, trackEnergy = true))
            val sh = DoubleArray(r.energy.size) { r.vesselEnergy[it] / r.energy[it] }
            var lo = 1.0; var hi = 0.0
            for (k in 200 until 1000) { lo = minOf(lo, sh[k]); hi = max(hi, sh[k]) }
            val d = MercurySpike.condition(r.raw)
            val (h, _) = fine(d, hz, (0.2 * out44).toInt(), (0.4 * out44).toInt())
            line("| ${f(c)} | ${f(w)} | ${f(sh[10], 4)} | ${f(sh[100], 4)} | ${f(sh[300], 4)} | ${f(sh[600], 4)} | ${f(hi - lo, 4)} | ${f(cents(h, h0), 2)} |")
        }
        line()
    }

    /** P7. Every macro corner, maximum strike, 8× threshold pressure: finite and bounded? */
    private fun p7Corners() {
        line("## P7. Corners: BEND, RUB, WATER, GLASS, COUPLE each at 0 and 1 (32), MIDI 36 and 84, tap 1, pressure 8× threshold")
        line()
        line("| bank | table | renders | all finite | max state | worst corner (B R W G C, MIDI) | max raw peak |")
        line("|---|---|---|---|---|---|---|")
        for (bank in Bank.values()) for (table in Table.values()) {
            var finite = true; var worst = 0.0; var worstName = ""; var peak = 0.0; var count = 0
            for (mask in 0 until 32) for (m in listOf(36, 84)) {
                val bit = { k: Int -> if (mask shr k and 1 == 1) 1.0 else 0.0 }
                val r = MercurySpike.render(Params(hz = midiHz(m), table = table, bank = bank, seconds = 2.0, contactSeconds = 1.5, bend = bit(0), rub = bit(1), water = bit(2), glass = bit(3), couple = bit(4), tap = 1.0, pressureRatio = 8.0))
                count++
                finite = finite && r.finite
                if (r.maxState > worst) { worst = r.maxState; worstName = "${bit(0).toInt()} ${bit(1).toInt()} ${bit(2).toInt()} ${bit(3).toInt()} ${bit(4).toInt()}, $m" }
                peak = max(peak, r.raw.maxOf { abs(it) }.toDouble())
            }
            line("| $bank | $table | $count | $finite | ${e(worst)} | $worstName | ${e(peak)} |")
        }
        line()
        line("Scale: the driver moves at ${MercurySpike.BOW_V}; a mode rung at that speed has state ≈ ${MercurySpike.BOW_V}.")
        line()
    }

    /** P8. The classifier on the R1 voices' spec defaults (unlevelled, but the classifier is level-blind enough for a first look). */
    private fun p8Classifier() {
        line("## P8. Classifier on the spec's voice defaults (one-shot length as rendered, 2.5 s; and PING cut to 0.6 s)")
        line()
        line("| voice | MIDI | class (2.5 s) | class (0.6 s) |")
        line("|---|---|---|---|")
        for (v in voices) for (m in listOf(36, 60, 84)) {
            val d = dec(voiceParams(v, midiHz(m)))
            val peak = d.maxOf { abs(it) }.coerceAtLeast(1e-9f)
            val n = FloatArray(d.size) { d[it] / peak * 0.5f }
            val short = n.copyOf((0.6 * out44).toInt())
            line("| ${v.name} | $m | ${Classifier.classify(Snip(n, 1, out44)).drumClass} | ${Classifier.classify(Snip(short, 1, out44)).drumClass} |")
        }
        line()
    }

    /** P9. Cost: an 8 s note, 16 modes, all mechanisms on. */
    private fun p9Cost() {
        line("## P9. Cost: an 8 s render at MIDI 60, 12 + 4 modes, every mechanism on (median of 3, after warm-up)")
        line()
        line("| bank | render ms (8 s of audio) | raw buffer MB |")
        line("|---|---|---|")
        for (bank in Bank.values()) {
            val p = Params(hz = 262.0, bank = bank, seconds = 8.0, contactSeconds = 6.0, rub = 0.6, water = 0.5, couple = 0.5, bend = 0.7)
            val times = (0 until 3).map { MercurySpike.render(p).nanos / 1e6 }.sorted()
            line("| $bank | ${f(times[1], 0)} | ${f(8.0 * MercurySpike.RATE * 4 / 1e6, 1)} |")
        }
        line()
    }
}
```
