# VALVE V1.1: the spike, the oversampling gate and the record

This is the record behind the VALVE V1.1 change ([the plan](2026-09-30-valve-v1-1.md); [the spec, with its V1.1 amendments](../specs/2026-09-29-magnet-valve-design.md)). It holds three things: the spike that rendered the candidate clips the owner chose from, the measurement of the oversampling gate that was ordered and not shipped, and the spike's parameterised copy of VALVE, so either can be re-run. What shipped is the new gain law, the darker closed wall, the default DRIVE 0.65 and the supply-style SAG (commits `d5a0befb` and `cd830c57`). What did not ship is the oversampling gate: the owner asked for the 4x round trip only when DRIVE is up, but the measurement below shows the cabinet's filters run at the snip's rate on the 1x path and so change the approved speaker's tone (a snare through CAB 0.5 steps 1.45 dB in its top third-octave at every DRIVE), so the round trip stays always on until a rate-invariant cabinet exists.

## What the spike was

A throwaway parameterised copy of `Valve.process` (`ValveSpike`, in the appendix), built on base `1a2ec180` (VALVE V1). It rendered 60 candidate clips through THUMP (kick, snare), VELVET (brass) and VOX (choir), folded each to mono, levelled it with `AuditionLevel.level` and wrote it 16-bit, for the owner's round-two listen of 2026-09-30. With its default parameters it equals `Valve.process` sample for sample (74 renders checked). The clips are in four candidate sets:

| Set | Clips | What it offered | Candidates |
|---|---:|---|---|
| `ceil` | 16 | the top of DRIVE | gain at DRIVE 1 of 35 (V1), 100, 300 and 1000; kick, snare, brass, choir |
| `wall` | 12 | the closed wall (CAB 1) | V1's 4500 Hz one pole; 3200 Hz one pole; 2400 Hz one pole; 3200 Hz two poles; snare, brass, choir |
| `drv` | 8 | the default DRIVE | DRIVE 0.45, 0.50, 0.60 and 0.70 on the V1 law (gain 0.953, 1.323, 2.547, 4.904); kick, snare |
| `sag` | 24 | the SAG mechanism | V1's bias, HEADROOM (a pre-tube gain multiplier) and SUPPLY (a post-tube gain reduction), each against SAG 0, at DRIVE 0.6 and 1; kick, snare, brass |

## What the owner chose (2026-09-30, round-two listen, 12 of 13 answered)

| Question | Answer | Consequence |
|---|---|---|
| Top of DRIVE | gain 1000 on kick, snare, brass; 100 on choir | law's top = 1000. The choir's 100 sits at DRIVE about 0.845 on the new law, so it is reachable |
| Closed wall | 3200 Hz with two poles on snare and choir; 2400 Hz one pole on brass | the two-pole 3200 Hz wall (the steepest, per the standing rule that a macro's extremes must be extreme) |
| Default DRIVE | 0.7 (the top of the set), heard under the **V1 law** = gain 4.90 | the new law makes DRIVE 0.7 = gain 11.3, which nobody heard; the default must match the sound, see Ruling 3 |
| SAG | SUPPLY (K 3.0) on kick, snare, brass; overall "keep" | replace the bias mechanism entirely; headroom SAG is dropped |
| 4x oversampling | "only when driven" | gate the round trip on DRIVE (ordered; **not shipped**, see the measurement below) |

The owner accepted the aliasing cost: the page told them the steady-tone clean margin at 4x falls from 51.3 dB (gain 35) to 43.4 / 41.3 / 40.6 dB at gains 100 / 300 / 1000, against the 45 dB bar.

## Which rulings were made

Six rulings were made before execution (the owner can undo any). (1) The wall applies only above CAB 0.6: at or below 0.6 the coil corner and the one pole are V1's exactly, and from 0.6 to 1 the corner runs linearly from V1's 5020 Hz to 3200 Hz while a second identical pole fades in, so CAB 1 equals the spike's `w3` clip. (2) SAG changes character, not only mechanism: SUPPLY is post-tube and keyed on the tube's own output level, so at deep saturation (DRIVE above 0.85, gain above about 100) the follower sits near 1 and SAG becomes a near-constant level offset the peak match cancels; the owner heard SUPPLY only at gain 35 or below. (Measured after the build, on the real code at gain 1000: the drop did not shrink, see the SAG bullet in the spec's V1.1 amendments.) (3) The default DRIVE is 0.65, not 0.7: the owner's 0.7 was heard under the V1 law (gain 4.90), which is DRIVE 0.644 on the new law, and gain 5.37 is 0.65, the nearest clean knob value. (4) The 4x gate was to be set by measurement, at the lowest DRIVE step where the steady probe's clarity at 4x beats 1x by at least 6 dB, and not above 0.6. (5) The aliasing bar is re-pinned, not kept: DRIVE 1 at gain 1000 measures 40.6 dB at 4x, so the test keeps 45 dB at DRIVE 0.6 and pins DRIVE 1 at its measured value minus 2 dB. (6) The spec states V1's numbers until its V1.1 amendments land, so the plan, not the spec, is the reference for the tasks that come first. Rulings made during execution: (7) a confirmation listen of the combined V1.1 sound gates the PR, because each change was heard alone; (8) the CAB continuity test's bound is 0.75 dB per band per 0.01 of CAB, since the top band falls about 0.5 dB per 0.01 above CAB 0.87 (a slope of the darkening wall, not a step) and a snapped-in second pole would move it 5 dB or more; (9) the wall's acceptance check against the spike's `w3` clip reads -54.3 dB and is accepted against a -50 dB bar, because a one-float-step gain change alone moves the spike's own path by -56 dB through float noise in a low-frequency biquad at the 176.4 kHz work rate. After the gate measurement below blocked, Ruling 10 ordered that V1.1 keep the 4x round trip always on, so Ruling 4 produced a threshold (0.55) that was not shipped.

## The confirmation listen (2026-09-30)

Ruling 7's gate: the combined V1.1 sound, heard together for the first time. The owner answered 12 of 12.

| Question | Answer | Consequence |
|---|---|---|
| The new default pad (DRIVE 0.65, gain 5.4) on kick, snare, brass, choir | "right" on all four | 0.65 kept the sound the owner heard under the V1 law |
| DRIVE 0.65 against 0.7 (DRIVE 0.7 on the V1.1 law is gain 11.3) | **0.7** | the default is DRIVE 0.7 (gain 11.3) |
| `amped` (DRIVE 0.6) against as-it-was, kick and snare | "same" | `amped` stays at DRIVE 0.6 |
| SAG at DRIVE 1, SAG 0 / 0.35 / 1, kick and snare | "does nothing" | SAG is an effect of the lower and middle DRIVE range |
| The closed wall under the hotter tube, snare, brass, choir | "right" | the two-pole 3.2 kHz wall stands |

The default moved to 0.7 (commit `fix(valve): default DRIVE 0.7 after the confirmation listen`): the owner picked 0.7 twice, first heard on V1's law at gain 4.90 and then heard on the V1.1 law at gain 11.3 against 0.65, so Ruling 3 (0.65 as the nearest knob value to that gain) was made moot by the owner's own pick. `amped` stays at 0.6, below the default, because the owner heard it as the same as before. SAG does nothing audible at DRIVE 1: the measured steady offset of -1.6 dB at SAG 0.35 and -3.15 / -3.28 dB at SAG 1 on the kick and snare is levelled away in loudness-matched clips, so the owner had nothing to hear. The closed wall is right under the hotter tube. The 0.65 figures elsewhere in this record are historical: they are what was built and measured before the confirmation listen.

## Spike report

### VALVE V1.1 spike: candidate sets

Base `1a2ec180`, branch `claude/valve-v1-1`. Every clip rendered through `ValveSpike.process` (a parameterised copy of `Valve.process`), folded to mono by averaging, levelled with `AuditionLevel.level`, written 16-bit. Peak and end step are read on the levelled float clip; end step = |mean of the last 10 ms| / peak.

#### Summary

- Anchor: PASS. 74 renders of `ValveSpike.process` (default Params) equal `Valve.process` sample for sample (`assertContentEquals`):
- ceil: clarity at 4x (snip rate) for gain 35 / 100 / 300 / 1000 at DRIVE 1: 51.3 / 43.4 / 41.3 / 40.6 dB (28.8 / 27.6 / 27.2 / 26.8 dB); bar 45 dB at 4x; the higher tops c1, c2, c3 fall under it; the kick's end step at old / c1 / c2 / c3: 0.063 / 0.178 / 0.528 / 1.721 %
- wall: noise through CAB 1 at DRIVE 0, old / w1 / w2 / w3: -3 dB 5088 / 4292 / 3801 / 2934 Hz, centroid 4480 / 3776 / 3239 / 2052 Hz; monotonically darker: yes, w1..w3 used as briefed
- drv: 8 clips, KICK and SNARE at DRIVE 0.45 / 0.5 / 0.6 / 0.7 (CAB 0.6, SAG 0); listening only, measures in section 4.
- SUPPLY: SUPPLY_K 3.00 (-5 dB is NOT bracketed inside 0.8..3 (K 0.8: -1.28 dB, K 3: -3.05 dB)); kick drop at DRIVE 1 -3.05 dB against the 4-6 dB target
- HEADROOM: HEAD_DEPTH 0.85 (rule gives 0.85); kick drop -4.22 dB, snare -6.22 dB, worst window -11.58 dB
- sag: audible in the envelope (>= 2 dB against none in 60-250 ms) and end step, kick and snare: KICK d0.6 bias DC tail only (18.2 dB, end 0.775 %); KICK d0.6 head yes (2.1 dB, end 0.006 %); KICK d0.6 supply yes (3.9 dB, end 0.010 %); KICK d1 bias yes (24.6 dB, end 3.568 %); KICK d1 head yes (11.6 dB, end 0.027 %); KICK d1 supply yes (3.1 dB, end 0.079 %); SNARE d0.6 bias DC tail only (9.2 dB, end 0.200 %); SNARE d0.6 head no (0.3 dB, end 0.000 %); SNARE d0.6 supply yes (3.7 dB, end 0.000 %); SNARE d1 bias yes (14.6 dB, end 6.313 %); SNARE d1 head yes (8.0 dB, end 0.002 %); SNARE d1 supply yes (3.2 dB, end 0.003 %)
- Cost: ms per rendered second, V1 32.3; BIAS 32.1, HEADROOM 34.0, SUPPLY 33.8
- 60 clips; manifest.json lists them.

#### 1. Regression anchor

PASS. 74 renders of `ValveSpike.process` (default Params) equal `Valve.process` sample for sample (`assertContentEquals`):
- THUMP kick and snare x DRIVE 0.45 / 0.6 / 1 x SAG 0 / 0.35 / 1 x CAB 0 / 0.6 / 1 (54, TONE at its default), plus TONE 0 and 1 at DRIVE 1 / SAG 1 / CAB 1 (4), plus the snip-rate path at DRIVE 0.6 / 1 x SAG 0 / 1 x CAB 0 / 0.6 (16).
- SAG 0 is bit-exact a no-op in HEADROOM and SUPPLY: 16 renders (kick, snare x DRIVE 0.6 / 1 x CAB 0 / 0.5) equal BIAS at SAG 0.
- Gain law: ValveSpike's law is V1's `Valve.gainFor` at DRIVE <= 0.6 and at gainTop 35 (same code path, so bit-exact); gainTop 35: g(0.6) 2.5470, g(0.8) 9.442, g(1) 35.000; gainTop 100: g(0.6) 2.5470, g(0.8) 15.959, g(1) 100.000; gainTop 300: g(0.6) 2.5470, g(0.8) 27.642, g(1) 300.000; gainTop 1000: g(0.6) 2.5470, g(0.8) 50.468, g(1) 1000.000.

#### 2. ceil: the top of DRIVE (16 clips)

Law: V1's `0.05 * 700^DRIVE` up to DRIVE 0.6 (gain 2.547), log-linear from there to gainTop at DRIVE 1.

| key | frames | peak | end step | settings |
|---|---:|---:|---:|---|
| `ceil/KICK/old` | 15262 | 0.1264 | 0.063 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 35 |
| `ceil/KICK/c1` | 15262 | 0.1132 | 0.178 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 100 |
| `ceil/KICK/c2` | 15262 | 0.1053 | 0.528 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 300 |
| `ceil/KICK/c3` | 15262 | 0.1017 | 1.721 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 1000 |
| `ceil/SNARE/old` | 14709 | 0.1066 | 0.005 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 35 |
| `ceil/SNARE/c1` | 14709 | 0.0921 | 0.022 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 100 |
| `ceil/SNARE/c2` | 14709 | 0.0834 | 0.097 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 300 |
| `ceil/SNARE/c3` | 14709 | 0.0799 | 0.455 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 1000 |
| `ceil/BRASS/old` | 22684 | 0.0736 | 0.009 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 35 |
| `ceil/BRASS/c1` | 22684 | 0.0674 | 0.024 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 100 |
| `ceil/BRASS/c2` | 22684 | 0.0648 | 0.074 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 300 |
| `ceil/BRASS/c3` | 22684 | 0.0640 | 0.255 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 1000 |
| `ceil/CHOIR/old` | 63654 | 0.0636 | 0.000 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 35 |
| `ceil/CHOIR/c1` | 63654 | 0.0632 | 0.000 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 100 |
| `ceil/CHOIR/c2` | 63654 | 0.0639 | 0.000 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 300 |
| `ceil/CHOIR/c3` | 63654 | 0.0638 | 0.000 % | DRIVE 1, CAB 0.5, SAG 0, TONE 0.5, gain at DRIVE 1 = 1000 |

Aliasing: steady probe (harmonics of 246.94 Hz to 5 kHz at 1/k, peak 0.99, 2 s), `harmonicClarity` from 0.3 s, n = 65536, DRIVE 1, SAG 0, TONE 0.5, CAB 0, through `ValveSpike.process` (bar 45 dB at 4x; V1 pinned 51.3 / 28.8).

| tag | gain at DRIVE 1 | clarity 4x | clarity snip rate |
|---|---:|---:|---:|
| old | 35 | 51.3 dB | 28.8 dB |
| c1 | 100 | 43.4 dB | 27.6 dB |
| c2 | 300 | 41.3 dB | 27.2 dB |
| c3 | 1000 | 40.6 dB | 26.8 dB |

Erosion at 4x: the worst of the higher tops reads 40.6 dB against 51.3 dB at 35 (-10.7 dB); BELOW the 45 dB bar at the worst top.

#### 3. wall: the closed wall (12 clips)

| key | frames | peak | end step | settings |
|---|---:|---:|---:|---|
| `wall/SNARE/old` | 14709 | 0.0968 | 0.006 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 4500 Hz x 1 pole |
| `wall/SNARE/w1` | 14709 | 0.1036 | 0.006 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 3200 Hz x 1 pole |
| `wall/SNARE/w2` | 14709 | 0.1121 | 0.006 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 2400 Hz x 1 pole |
| `wall/SNARE/w3` | 14709 | 0.1226 | 0.006 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 3200 Hz x 2 poles |
| `wall/BRASS/old` | 22684 | 0.0602 | 0.013 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 4500 Hz x 1 pole |
| `wall/BRASS/w1` | 22684 | 0.0594 | 0.014 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 3200 Hz x 1 pole |
| `wall/BRASS/w2` | 22684 | 0.0602 | 0.014 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 2400 Hz x 1 pole |
| `wall/BRASS/w3` | 22684 | 0.0607 | 0.014 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 3200 Hz x 2 poles |
| `wall/CHOIR/old` | 63654 | 0.0507 | 0.000 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 4500 Hz x 1 pole |
| `wall/CHOIR/w1` | 63654 | 0.0504 | 0.000 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 3200 Hz x 1 pole |
| `wall/CHOIR/w2` | 63654 | 0.0506 | 0.000 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 2400 Hz x 1 pole |
| `wall/CHOIR/w3` | 63654 | 0.0522 | 0.001 % | DRIVE 1, CAB 1, SAG 0, TONE 0.5, wall 3200 Hz x 2 poles |

Noise measure: seeded uniform white noise, 0.5 s, through `ValveSpike.process` at DRIVE 0, SAG 0, TONE 0.5, CAB 1 (the whole chain, tube linear). Response = sum|Y|^2 / sum|X|^2 in 1/6-octave bands on a 1/48-octave grid, re the 300 Hz-1 kHz average; the -3 dB point interpolated in log f. "first" is the first downward crossing above 1 kHz, "highest" the highest frequency still within 3 dB (the breakup resonances at 2.6 and 3.75 kHz can lift the curve back over after a first dip). Centroid = power-weighted mean frequency of the output (input noise: 10859 Hz).

| tag | coil | -3 dB first | -3 dB highest | centroid | 2 k | 3 k | 5 k | 8 k | 12 k |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| old | 4500 Hz x 1 | 5088 Hz | 5088 Hz | 4480 Hz | +0.1 | +0.3 | -2.9 | -6.0 | -9.0 |
| w1 | 3200 Hz x 1 | 4292 Hz | 4292 Hz | 3776 Hz | -0.4 | -0.8 | -4.7 | -8.4 | -11.6 |
| w2 | 2400 Hz x 1 | 3801 Hz | 3801 Hz | 3239 Hz | -1.2 | -2.0 | -6.4 | -10.5 | -13.8 |
| w3 | 3200 Hz x 2 | 2934 Hz | 2934 Hz | 2052 Hz | -1.7 | -3.3 | -9.8 | -16.8 | -23.1 |

Monotonically darker old > w1 > w2 > w3: -3 dB highest yes, -3 dB first yes, centroid yes.

#### 4. drv: the default DRIVE (8 clips)

V1 law (gainTop 35); gains: DRIVE 0.45 -> 0.953, DRIVE 0.50 -> 1.323, DRIVE 0.60 -> 2.547, DRIVE 0.70 -> 4.904 (the tube's rail is gain 1 on a normalised pad).

| key | frames | peak | end step | settings |
|---|---:|---:|---:|---|
| `drv/KICK/d045` | 15262 | 0.2010 | 0.003 % | DRIVE 0.45, CAB 0.6, SAG 0, TONE 0.5, gain 0.953 (V1 law) |
| `drv/KICK/d050` | 15262 | 0.1985 | 0.003 % | DRIVE 0.50, CAB 0.6, SAG 0, TONE 0.5, gain 1.323 (V1 law) |
| `drv/KICK/d060` | 15262 | 0.1869 | 0.005 % | DRIVE 0.60, CAB 0.6, SAG 0, TONE 0.5, gain 2.547 (V1 law) |
| `drv/KICK/d070` | 15262 | 0.1686 | 0.009 % | DRIVE 0.70, CAB 0.6, SAG 0, TONE 0.5, gain 4.904 (V1 law) |
| `drv/SNARE/d045` | 14709 | 0.2515 | 0.000 % | DRIVE 0.45, CAB 0.6, SAG 0, TONE 0.5, gain 0.953 (V1 law) |
| `drv/SNARE/d050` | 14709 | 0.2265 | 0.000 % | DRIVE 0.50, CAB 0.6, SAG 0, TONE 0.5, gain 1.323 (V1 law) |
| `drv/SNARE/d060` | 14709 | 0.1814 | 0.000 % | DRIVE 0.60, CAB 0.6, SAG 0, TONE 0.5, gain 2.547 (V1 law) |
| `drv/SNARE/d070` | 14709 | 0.1571 | 0.001 % | DRIVE 0.70, CAB 0.6, SAG 0, TONE 0.5, gain 4.904 (V1 law) |

#### 5. Calibration: SUPPLY_K

Rule: SAG 1 at DRIVE 1 (CAB 0.5, TONE 0.5, the sag set's settings) must lower the kick's level over [60, 160) ms after the hit, re its first 20 ms [0, 20), by about 4-6 dB against the SAG 0 render; aimed at -5 dB. The hit = the first frame of the dry source at 1 % of its peak (kick frame 2, snare frame 1). Levels are mean-square over each window, on the levelled mono clip (the levelling cancels in the difference).

| SUPPLY_K | kick DRIVE 1 | snare DRIVE 1 | kick DRIVE 0.6 | snare DRIVE 0.6 |
|---:|---:|---:|---:|---:|
| 0.80 | -1.28 dB | -1.03 dB | -0.82 dB | -0.22 dB |
| 1.00 | -1.52 dB | -1.23 dB | -0.99 dB | -0.29 dB |
| 1.25 | -1.77 dB | -1.47 dB | -1.18 dB | -0.37 dB |
| 1.50 | -2.01 dB | -1.69 dB | -1.35 dB | -0.46 dB |
| 2.00 | -2.41 dB | -2.08 dB | -1.67 dB | -0.63 dB |
| 2.50 | -2.75 dB | -2.44 dB | -1.94 dB | -0.81 dB |
| 3.00 | -3.05 dB | -2.76 dB | -2.19 dB | -0.98 dB |

Outside the brief's range, information only (no clip uses these): K 4: -3.56 dB; K 6: -4.36 dB; K 8: -4.97 dB; K 12: -5.90 dB.

Result: -5 dB is NOT bracketed inside 0.8..3 (K 0.8: -1.28 dB, K 3: -3.05 dB); closest end K = 3.00. The constant in use for the sag clips: **SUPPLY_K = 3.00**, kick at DRIVE 1: -3.05 dB.

What it means: the follower's 5 ms attack charges inside the reference window, so after the first few ms the reduction is close to a constant level offset that the peak match cancels; what is left in the envelope is the hit's first milliseconds poking out before the gain falls, and a tail that sits relatively higher as the tube output (and so the follower) falls - a compressor's shape, not a dip after the hit. The drop grows only slowly with K: 4 dB is first reached at K 6 (outside the range). Reaching 4-6 dB looks like a job for the follower's timing (a slower charge, so the first 20 ms pass before the supply gives way), not for its depth; that is a hypothesis, not measured here.

#### 6. Calibration: HEAD_DEPTH

Sweep at SAG 1, DRIVE 1, CAB 0.5, against the SAG 0 render. "drop" = the same [60, 160) re [0, 20) change as SUPPLY's rule; "max |env diff|" = the largest difference against `none` in the 20 ms windows at 60 / 100 / 160 / 250 ms; "worst window" = the lowest difference against `none` in any window (20-250 ms, kick or snare), the silencing check.

| HEAD_DEPTH | kick drop | snare drop | kick max abs env diff | snare max abs env diff | worst window | kick drop at DRIVE 0.6 |
|---:|---:|---:|---:|---:|---:|---:|
| 0.50 | -1.16 dB | -2.27 dB | 4.64 dB | 3.80 dB | -4.64 dB | -1.18 dB |
| 0.55 | -1.37 dB | -2.64 dB | 5.28 dB | 4.30 dB | -5.28 dB | -1.31 dB |
| 0.60 | -1.63 dB | -3.05 dB | 5.97 dB | 4.84 dB | -5.97 dB | -1.44 dB |
| 0.65 | -1.93 dB | -3.52 dB | 6.72 dB | 5.41 dB | -6.72 dB | -1.57 dB |
| 0.70 | -2.31 dB | -4.05 dB | 7.66 dB | 6.01 dB | -7.66 dB | -1.70 dB |
| 0.75 | -2.78 dB | -4.66 dB | 8.76 dB | 6.64 dB | -8.76 dB | -1.83 dB |
| 0.80 | -3.38 dB | -5.38 dB | 10.05 dB | 7.32 dB | -10.05 dB | -1.97 dB |
| 0.85 | -4.22 dB | -6.22 dB | 11.58 dB | 8.03 dB | -11.58 dB | -2.10 dB |
| 0.90 | -5.46 dB | -7.22 dB | 13.44 dB | 8.74 dB | -13.44 dB | -2.24 dB |

Rule: every depth in the range is already "clearly present" by the report's own bar (2 dB against `none` in 60-250 ms), so the range is narrowed by SUPPLY's stated target - the kick's [60,160) level re its first 20 ms lowered by 4-6 dB, aimed at -5 dB - and by a silencing guard: no 20 ms window of kick or snare (20-250 ms) more than 12 dB under `none` (a quarter of the amplitude; the guard is this spike's judgement, stated so it can be moved). The rule picks **0.85**. The constant in use for the sag clips: **HEAD_DEPTH = 0.85**. 0.90 would sit closer to -5 dB on the kick but takes its 160 ms window 13.4 dB under `none`.

Unlike SUPPLY, HEADROOM's difference grows with time after the hit (the 160 and 250 ms windows move most), because the gain into the tube stays down for the follower's 120 ms release while the pad decays, so the tube leaves saturation sooner: a dip that holds, and the tube is still driven (the effective gain on a full-scale peak never goes under the rail: g(1 - D) + D >= 1 for D <= 1).

#### 7. sag: the SAG mechanism (24 clips)

HEAD_DEPTH 0.85, SUPPLY_K 3.00. CAB 0.5, TONE 0.5.

| key | frames | peak | end step | settings |
|---|---:|---:|---:|---|
| `sag/KICK/d06_none` | 15262 | 0.1879 | 0.005 % | DRIVE 0.6, CAB 0.5, SAG 0, TONE 0.5, sag mode BIAS |
| `sag/KICK/d06_bias` | 15262 | 0.1809 | 0.775 % | DRIVE 0.6, CAB 0.5, SAG 1, TONE 0.5, sag mode BIAS |
| `sag/KICK/d06_head` | 15262 | 0.1928 | 0.006 % | DRIVE 0.6, CAB 0.5, SAG 1, TONE 0.5, sag mode HEADROOM, HEAD_DEPTH 0.85 |
| `sag/KICK/d06_supply` | 15262 | 0.2243 | 0.010 % | DRIVE 0.6, CAB 0.5, SAG 1, TONE 0.5, sag mode SUPPLY, SUPPLY_K 3.00 |
| `sag/KICK/d10_none` | 15262 | 0.1264 | 0.063 % | DRIVE 1.0, CAB 0.5, SAG 0, TONE 0.5, sag mode BIAS |
| `sag/KICK/d10_bias` | 15262 | 0.1449 | 3.568 % | DRIVE 1.0, CAB 0.5, SAG 1, TONE 0.5, sag mode BIAS |
| `sag/KICK/d10_head` | 15262 | 0.1608 | 0.027 % | DRIVE 1.0, CAB 0.5, SAG 1, TONE 0.5, sag mode HEADROOM, HEAD_DEPTH 0.85 |
| `sag/KICK/d10_supply` | 15262 | 0.2104 | 0.079 % | DRIVE 1.0, CAB 0.5, SAG 1, TONE 0.5, sag mode SUPPLY, SUPPLY_K 3.00 |
| `sag/SNARE/d06_none` | 14709 | 0.1847 | 0.000 % | DRIVE 0.6, CAB 0.5, SAG 0, TONE 0.5, sag mode BIAS |
| `sag/SNARE/d06_bias` | 14709 | 0.1884 | 0.200 % | DRIVE 0.6, CAB 0.5, SAG 1, TONE 0.5, sag mode BIAS |
| `sag/SNARE/d06_head` | 14709 | 0.1909 | 0.000 % | DRIVE 0.6, CAB 0.5, SAG 1, TONE 0.5, sag mode HEADROOM, HEAD_DEPTH 0.85 |
| `sag/SNARE/d06_supply` | 14709 | 0.3022 | 0.000 % | DRIVE 0.6, CAB 0.5, SAG 1, TONE 0.5, sag mode SUPPLY, SUPPLY_K 3.00 |
| `sag/SNARE/d10_none` | 14709 | 0.1066 | 0.005 % | DRIVE 1.0, CAB 0.5, SAG 0, TONE 0.5, sag mode BIAS |
| `sag/SNARE/d10_bias` | 14709 | 0.1569 | 6.313 % | DRIVE 1.0, CAB 0.5, SAG 1, TONE 0.5, sag mode BIAS |
| `sag/SNARE/d10_head` | 14709 | 0.1414 | 0.002 % | DRIVE 1.0, CAB 0.5, SAG 1, TONE 0.5, sag mode HEADROOM, HEAD_DEPTH 0.85 |
| `sag/SNARE/d10_supply` | 14709 | 0.2361 | 0.003 % | DRIVE 1.0, CAB 0.5, SAG 1, TONE 0.5, sag mode SUPPLY, SUPPLY_K 3.00 |
| `sag/BRASS/d06_none` | 22684 | 0.1297 | 0.001 % | DRIVE 0.6, CAB 0.5, SAG 0, TONE 0.5, sag mode BIAS |
| `sag/BRASS/d06_bias` | 22684 | 0.1267 | 0.119 % | DRIVE 0.6, CAB 0.5, SAG 1, TONE 0.5, sag mode BIAS |
| `sag/BRASS/d06_head` | 22684 | 0.1397 | 0.001 % | DRIVE 0.6, CAB 0.5, SAG 1, TONE 0.5, sag mode HEADROOM, HEAD_DEPTH 0.85 |
| `sag/BRASS/d06_supply` | 22684 | 0.1669 | 0.002 % | DRIVE 0.6, CAB 0.5, SAG 1, TONE 0.5, sag mode SUPPLY, SUPPLY_K 3.00 |
| `sag/BRASS/d10_none` | 22684 | 0.0736 | 0.009 % | DRIVE 1.0, CAB 0.5, SAG 0, TONE 0.5, sag mode BIAS |
| `sag/BRASS/d10_bias` | 22684 | 0.1186 | 3.080 % | DRIVE 1.0, CAB 0.5, SAG 1, TONE 0.5, sag mode BIAS |
| `sag/BRASS/d10_head` | 22684 | 0.0967 | 0.007 % | DRIVE 1.0, CAB 0.5, SAG 1, TONE 0.5, sag mode HEADROOM, HEAD_DEPTH 0.85 |
| `sag/BRASS/d10_supply` | 22684 | 0.1182 | 0.015 % | DRIVE 1.0, CAB 0.5, SAG 1, TONE 0.5, sag mode SUPPLY, SUPPLY_K 3.00 |

Level envelope: 20 ms RMS windows [t, t+20) ms after the hit, dB re the first window [0, 20); "diff" = that value minus the `none` clip's at the same DRIVE (so the levelling and the peak match cancel); "drop diff" = the calibration measure [60,160) re [0,20) minus `none`'s. Audible = a difference of 2 dB or more against `none` in a window at 60 / 100 / 160 / 250 ms. BRASS is extra (the brief asks for KICK and SNARE).

"DC 250" = the share of the 250 ms window's power that is its mean (mean^2 / mean square): where it is large, the late level in that window is the decaying rest-point DC (the end-step click), not the pad.

| src | DRIVE | tag | 20 | 60 | 100 | 160 | 250 | diff 20 | diff 60 | diff 100 | diff 160 | diff 250 | drop diff | DC 250 | end step | audible |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| KICK | 0.6 | none | -2.4 | -7.6 | -15.0 | -28.9 | -51.4 | - | - | - | - | - | - | 4.9 % | 0.005 % | - |
| KICK | 0.6 | bias | -2.1 | -7.8 | -15.8 | -27.9 | -33.3 | +0.3 | -0.2 | -0.8 | +1.0 | +18.2 | -0.3 | 97.9 % | 0.775 % | yes, DC tail only (18.2) |
| KICK | 0.6 | head | -3.5 | -9.6 | -17.0 | -30.0 | -51.5 | -1.1 | -2.1 | -2.0 | -1.1 | -0.0 | -2.1 | 4.6 % | 0.006 % | **yes** (2.1) |
| KICK | 0.6 | supply | -5.5 | -10.2 | -16.4 | -28.0 | -47.5 | -3.1 | -2.6 | -1.4 | +0.9 | +3.9 | -2.2 | 4.8 % | 0.010 % | **yes** (3.9) |
| KICK | 1.0 | none | -1.5 | -2.2 | -2.1 | -8.3 | -29.4 | - | - | - | - | - | - | 5.1 % | 0.063 % | - |
| KICK | 1.0 | bias | -0.6 | -2.4 | -15.8 | -32.9 | -33.4 | +0.9 | -0.2 | -13.7 | -24.6 | -3.9 | -5.6 | 98.7 % | 3.568 % | **yes** (24.6) |
| KICK | 1.0 | head | -1.8 | -4.2 | -7.9 | -19.9 | -40.0 | -0.3 | -2.0 | -5.9 | -11.6 | -10.5 | -4.2 | 4.9 % | 0.027 % | **yes** (11.6) |
| KICK | 1.0 | supply | -4.6 | -5.3 | -5.2 | -10.5 | -28.4 | -3.1 | -3.1 | -3.1 | -2.2 | +1.0 | -3.1 | 5.2 % | 0.079 % | **yes** (3.1) |
| SNARE | 0.6 | none | -5.7 | -16.4 | -24.0 | -34.7 | -51.6 | - | - | - | - | - | - | 0.0 % | 0.000 % | - |
| SNARE | 0.6 | bias | -5.6 | -16.4 | -23.8 | -32.9 | -42.4 | +0.1 | +0.0 | +0.2 | +1.8 | +9.2 | +0.1 | 87.6 % | 0.200 % | yes, DC tail only (9.2) |
| SNARE | 0.6 | head | -6.1 | -16.6 | -24.1 | -34.6 | -51.3 | -0.4 | -0.3 | -0.1 | +0.1 | +0.3 | -0.2 | 0.0 % | 0.000 % | no (0.3) |
| SNARE | 0.6 | supply | -8.3 | -17.8 | -24.2 | -33.1 | -47.9 | -2.6 | -1.5 | -0.1 | +1.7 | +3.7 | -1.0 | 0.0 % | 0.000 % | **yes** (3.7) |
| SNARE | 1.0 | none | -2.1 | -5.9 | -9.0 | -17.0 | -33.4 | - | - | - | - | - | - | 0.1 % | 0.005 % | - |
| SNARE | 1.0 | bias | -2.2 | -9.1 | -19.2 | -26.4 | -18.8 | -0.2 | -3.2 | -10.2 | -9.4 | +14.6 | -5.9 | 98.9 % | 6.313 % | **yes** (14.6) |
| SNARE | 1.0 | head | -3.6 | -10.8 | -16.5 | -25.0 | -39.0 | -1.5 | -4.9 | -7.5 | -8.0 | -5.6 | -6.2 | 0.0 % | 0.002 % | **yes** (8.0) |
| SNARE | 1.0 | supply | -5.4 | -9.0 | -11.6 | -17.8 | -31.0 | -3.3 | -3.2 | -2.6 | -0.8 | +2.4 | -2.8 | 0.0 % | 0.003 % | **yes** (3.2) |
| BRASS | 0.6 | none | -0.4 | -6.1 | -12.3 | -22.0 | -36.3 | - | - | - | - | - | - | 0.0 % | 0.001 % | - |
| BRASS | 0.6 | bias | -0.6 | -6.4 | -12.7 | -22.0 | -33.4 | -0.2 | -0.3 | -0.3 | +0.0 | +2.9 | -0.3 | 50.7 % | 0.119 % | yes, DC tail only (2.9) |
| BRASS | 0.6 | head | -1.4 | -7.5 | -13.4 | -22.3 | -36.0 | -1.0 | -1.4 | -1.0 | -0.4 | +0.3 | -1.2 | 0.0 % | 0.001 % | no (1.4) |
| BRASS | 0.6 | supply | -3.6 | -8.5 | -13.5 | -21.1 | -32.7 | -3.2 | -2.4 | -1.2 | +0.9 | +3.6 | -1.8 | 0.0 % | 0.002 % | **yes** (3.6) |
| BRASS | 1.0 | none | +0.7 | -0.0 | -0.7 | -3.9 | -15.9 | - | - | - | - | - | - | 0.1 % | 0.009 % | - |
| BRASS | 1.0 | bias | +0.1 | -5.1 | -16.5 | -30.2 | -26.1 | -0.5 | -5.0 | -15.8 | -26.3 | -10.2 | -10.0 | 68.2 % | 3.080 % | **yes** (26.3) |
| BRASS | 1.0 | head | +0.3 | -2.1 | -5.7 | -13.1 | -24.6 | -0.4 | -2.1 | -5.1 | -9.2 | -8.7 | -4.2 | 0.0 % | 0.007 % | **yes** (9.2) |
| BRASS | 1.0 | supply | -2.4 | -3.0 | -3.6 | -6.1 | -15.5 | -3.1 | -3.0 | -2.9 | -2.2 | +0.4 | -2.9 | 0.1 % | 0.015 % | **yes** (3.0) |

- KICK DRIVE 0.6 bias: audible (max |diff| 18.2 dB in 60-250 ms, but only in the 250 ms window, which is 98 % DC: the tail click, not a sag (60-160 ms max 1.0 dB)), end step 0.775 %
- KICK DRIVE 0.6 head: audible (max |diff| 2.1 dB in 60-250 ms), end step 0.006 %
- KICK DRIVE 0.6 supply: audible (max |diff| 3.9 dB in 60-250 ms), end step 0.010 %
- KICK DRIVE 1.0 bias: audible (max |diff| 24.6 dB in 60-250 ms), end step 3.568 %
- KICK DRIVE 1.0 head: audible (max |diff| 11.6 dB in 60-250 ms), end step 0.027 %
- KICK DRIVE 1.0 supply: audible (max |diff| 3.1 dB in 60-250 ms), end step 0.079 %
- SNARE DRIVE 0.6 bias: audible (max |diff| 9.2 dB in 60-250 ms, but only in the 250 ms window, which is 88 % DC: the tail click, not a sag (60-160 ms max 1.8 dB)), end step 0.200 %
- SNARE DRIVE 0.6 head: not audible (max |diff| 0.3 dB in 60-250 ms), end step 0.000 %
- SNARE DRIVE 0.6 supply: audible (max |diff| 3.7 dB in 60-250 ms), end step 0.000 %
- SNARE DRIVE 1.0 bias: audible (max |diff| 14.6 dB in 60-250 ms), end step 6.313 %
- SNARE DRIVE 1.0 head: audible (max |diff| 8.0 dB in 60-250 ms), end step 0.002 %
- SNARE DRIVE 1.0 supply: audible (max |diff| 3.2 dB in 60-250 ms), end step 0.003 %
- BRASS DRIVE 0.6 bias: audible (max |diff| 2.9 dB in 60-250 ms, but only in the 250 ms window, which is 51 % DC: the tail click, not a sag (60-160 ms max 0.3 dB)), end step 0.119 %
- BRASS DRIVE 0.6 head: not audible (max |diff| 1.4 dB in 60-250 ms), end step 0.001 %
- BRASS DRIVE 0.6 supply: audible (max |diff| 3.6 dB in 60-250 ms), end step 0.002 %
- BRASS DRIVE 1.0 bias: audible (max |diff| 26.3 dB in 60-250 ms), end step 3.080 %
- BRASS DRIVE 1.0 head: audible (max |diff| 9.2 dB in 60-250 ms), end step 0.007 %
- BRASS DRIVE 1.0 supply: audible (max |diff| 3.0 dB in 60-250 ms), end step 0.015 %

#### 8. Cost

One `ValveSpike.process` pass on a 4 s stereo pad (the kick tiled), DRIVE 1, SAG 1, CAB 0.5, TONE 0.5; min of 3 after a warm-up, JVM, ms per rendered second.

| path | ms / s |
|---|---:|
| `Valve.process` (V1, reference) | 32.3 |
| ValveSpike BIAS | 32.1 |
| ValveSpike HEADROOM | 34.0 |
| ValveSpike SUPPLY | 33.8 |

## The oversampling gate (measured, not shipped)

**Outcome (controller Ruling 10, 2026-09-30): not shipped.** The task ran and blocked at Step 6, with nothing committed: by the rule (the lowest DRIVE step at which the steady probe's 4x-over-1x clarity gap is at least 6 dB) the threshold is 0.55 (the gap is 4.8 dB at 0.50 and 14.0 dB at 0.55), but the gate cannot be made sound-neutral. The cabinet runs at a different rate on the two paths: a snare through CAB 0.5 steps 1.45 dB in its top third-octave at every DRIVE including 0, the noise centroid at DRIVE 0 rises +25 % at CAB 0.6 and +27 % at CAB 0.8 on the 1x path, and CAB 0 to 0.01 reads -0.72 dB in the top band at 1x against -0.10 at 4x. Separately, at CAB 0 the snare's render step exceeds 0.5 dB from DRIVE 0.55 up (0.75 dB), so the clarity rule and the render-difference bar disagree. V1.1 keeps the 4x round trip always on (V1's behaviour, the sound the owner approved). A gate needs a rate-invariant cabinet first (1x cabinet within 0.5 dB per third-octave of the 4x one) and a threshold chosen by render difference, not clarity; it would save about 31 ms per rendered second (33.4 to 2.1) below the gate. That is a follow-up for the owner to order.

The task's report follows, less its "Options for the controller" and "State of the tree" sections (the work-in-progress patch it mentions is a local, gitignored file), and then the diagnostics table it refers to.

### VALVE V1.1 Task 2 report: BLOCKED at Step 6

Status: BLOCKED. Nothing is committed. HEAD is still `cd830c57`, `git status --short` prints nothing. The work is saved as a patch (see "State of the tree"). No bound was loosened, the threshold was chosen by the brief's rule and not moved, and no FxTest or TreatmentsTest edit was needed or made.

Two tests fail at the chosen threshold:

1. `the gate does not step - the 1x and 4x renders at the threshold agree within half a dB in every band` is the brief's own test, and the brief routes its failure to BLOCKED. It fails on its first case, snare at CAB 0: 0.745 dB in 1015-1280 Hz against 0.5. Its CAB 0.5 cases, never reached because the first assertion aborts the test, also fail (snare top band +1.474 dB, from a cause no threshold can fix; see below).
2. `CAB leaving 0 is continuous - a hundredth of CAB stays within half a dB of CAB 0 in every band` is a Task 1 test the brief did not foresee (it calls the public `Valve.process` at DRIVE 0, so it now renders at 1x); the controller's rule for an unanticipated failure is to stop and report. It reads -0.722 dB in 12901-16000 Hz against 0.5.

Two findings the controller needs to choose a fix:

- **No threshold passes the gate-step test.** The CAB 0.5 snare's top band steps about 1.45 dB at every DRIVE, including DRIVE 0 (1.449 at 0, 1.450 at 0.3, 1.474 at 0.55). Moving the gate cannot fix that; the fix has to be in how the cabinet runs on the two paths.
- **The gate changes the approved cabinet sound across all of DRIVE below 0.55, not only at the boundary.** The noise centroid at DRIVE 0 moves from 4863 to 6099 Hz at CAB 0.6 (+25 %), from 3273 to 4149 Hz at CAB 0.8 (+27 %) and from 2005 to 2184 Hz at CAB 1 (+9 %). The 1x cabinet voice is brighter. The cause is left open: the coil pole's rate dependence is one, and at 1x the noise also keeps its 19.5-22 kHz, which the 4x round trip removes. These audible changes to what the owner signed off weigh toward keeping the cabinet at one rate on both paths.

#### Step 1: the GATE table (SAG 0, TONE 0.5, CAB 0, steady 247 Hz probe, clarity in dB)

```
GATE drive=0.30 4x=79.7 1x=79.7 gap=0.0
GATE drive=0.35 4x=79.7 1x=79.7 gap=0.0
GATE drive=0.40 4x=79.7 1x=79.6 gap=0.2
GATE drive=0.45 4x=79.7 1x=78.8 gap=0.9
GATE drive=0.50 4x=79.7 1x=74.9 gap=4.8
GATE drive=0.55 4x=79.6 1x=65.7 gap=14.0
GATE drive=0.60 4x=79.4 1x=55.8 gap=23.7
GATE drive=0.65 4x=76.4 1x=40.3 gap=36.1
GATE drive=0.70 4x=72.4 1x=32.9 gap=39.4
GATE kick 4x-1x difference re the 4x render: [(0.3, -28.86), (0.45, -28.82), (0.6, -28.30)]
```

#### Step 2: the threshold

0.55, by the rule "the lowest step where the gap is at least 6.0 dB": 0.50 reads 4.8 dB (short), 0.55 reads 14.0 dB. 0.55 is at or below 0.6, so the brief's block does not apply. The 6 dB rule and the 0.6 ceiling both held; the block came later.

Note on the kick line: the kick's 4x and 1x renders differ by about -28.7 dB re the 4x render at every DRIVE, including DRIVE 0 and CAB 0 (below), while the third-octave shares agree to 0.003 dB. That is a time-domain difference (the 4x round trip's phase change and which sample is the peak), not a spectral one, and it does not depend on DRIVE.

#### Steps 3 to 6: what ran

- Step 3: the three tests were added verbatim with `THRESHOLD` = `0.55f`.
- Step 4: `ValveTest` failed to compile on `OVERSAMPLE_FROM_DRIVE` (expected).
- Step 5: `OVERSAMPLE_FROM_DRIVE = 0.55f`, the public `process` gate, and the two KDoc updates were written as the brief says.
- Step 6, `ValveTest` alone: 25 tests, 2 failed (the two above). Whole `:synth:test` for information: 1075 tests, 2 failed (the same two), 0 errors, 0 skipped (summed from the result XML). Every FxTest and TreatmentsTest test passes with the gate at 0.55.

#### The `VALVE gate step` lines

The test's own run printed only two lines before its first assertion aborted it:

```
VALVE gate step kick CAB 0.0: worst band 2560-3225 Hz at 0.006 dB
VALVE gate step snare CAB 0.0: worst band 1015-1280 Hz at 0.745 dB     <- FAILS (limit 0.5)
```

To get the other two lines and the DRIVE dependence, a scratch print-only test (no assertions, deleted) ran the same computation at DRIVE 0.55 and at other DRIVEs. The full table (worst band, and the top band 12.9-16 kHz, in dB of share, 1x re 4x; the last column is the whole-waveform difference re the 4x render) is saved in `task-2-gate-diagnostics.txt` next to this file. The rows that matter:

| DRIVE | CAB | source | worst band | dB | top band (12.9-16 kHz) dB |
|---:|---:|---|---|---:|---:|
| 0.55 | 0 | kick | 2560-3225 Hz | 0.006 | 0.006 (3.2-4.1 kHz, the kick's top) |
| 0.55 | 0 | snare | 1015-1280 Hz | 0.745 | 0.102 |
| 0.55 | 0.5 | kick | 126-160 Hz | -0.063 | 0.014 (3.2-4.1 kHz) |
| 0.55 | 0.5 | snare | 12901-16000 Hz | 1.474 | 1.474 |

The snare at DRIVE 0.55, per-band step (dB), both CABs, from the same run:

```
CAB 0   40-50:0.50 50-63:0.43 63-80:0.24 80-100:0.06 ... 806-1015:0.52 1015-1280:0.75 1280-1612:0.09 ... 12901-16000:0.10
CAB 0.5 40-50:0.55 50-63:0.40 63-80:0.18 ... 806-1015:0.48 1015-1280:0.70 ... 5120-6450:0.17 6450-8127:0.30 8127-10240:0.60 10240-12901:0.81 12901-16000:1.47
```

##### Two separate causes, one that depends on DRIVE and one that does not

Snare, worst band by DRIVE, 1x re 4x (dB):

| DRIVE | CAB 0 worst band | CAB 0 dB | CAB 0.5 top band dB |
|---:|---|---:|---:|
| 0.0 | 12901-16000 Hz | 0.082 | 1.449 |
| 0.3 | 12901-16000 Hz | 0.083 | 1.450 |
| 0.45 | 1015-1280 Hz | 0.261 | 1.457 |
| 0.50 | 1015-1280 Hz | 0.463 | 1.464 |
| 0.55 | 1015-1280 Hz | 0.745 | 1.474 |
| 0.60 | 1015-1280 Hz | 1.060 | 1.488 |
| 0.65 | 50-63 Hz | 3.539 | 1.522 |

1. The CAB 0 step grows with DRIVE (0.26, 0.46, 0.75, 1.06 dB at 0.45, 0.50, 0.55, 0.60): it is the fold-back the 1x path lets in, filling the gaps of the snare's spectrum. It stays under 0.5 dB only at DRIVE 0.50 or below, where the probe's clarity gap is 4.8 dB, under the 6 dB the rule asks for. So the rule's threshold and this test disagree at CAB 0.
2. The CAB 0.5 top-band step (1.449 dB at DRIVE 0, 1.450 at 0.3, 1.474 at 0.55) does not depend on DRIVE at all, so no threshold can fix it. It is the cabinet running at a different rate; the coil pole is the likely main source (not isolated by a run). A hand estimate: the matched-Z one-pole at 5.15 kHz is about 1.7 dB less attenuated at 16 kHz and 1.15 dB less at 12.9 kHz at 44.1 kHz than at 176.4 kHz, which is the size of the 1.45 dB share step. The kick has no energy up there and reads 0.06 dB or less. This is filter maths, not the float noise floor Task 1's report warned about (that would not be a smooth, DRIVE-independent, top-band-only slope).

#### The same coil pole breaks Task 1's CAB 0 to 0.01 test at DRIVE 0

The test calls the public path at DRIVE 0, which is now a 1x render. The map's open end is `rate * 0.45f` (the one-pole's own cap), which was 79 kHz at 4x (the comment in `cabinet`: "-0.09 dB at 16 kHz") and is 19.8 kHz at 1x, a real corner inside the audible band.

```
VALVE CAB 0 -> 0.01: worst band 12901-16000 Hz at -0.722 dB, the top third-octave -0.722 dB     <- FAILS (limit 0.5); -0.10 dB at 4x
```

#### Task 1 tests that call the public path at DRIVE 0 (now 1x): the re-read Step 6 asks for

| test | before (always 4x) | now (1x) | bound | result |
|---|---|---|---|---|
| neutral point, worst band | 0.08 dB (V1's comment) | kick 0.052 (1280-1612 Hz), snare 0.061 (40-50 Hz) | 0.5 | pass |
| neutral point, RMS | kick 0.0985, snare 0.361 dB | kick +0.100, snare -0.028 | 0.12 / 0.44 | pass |
| wall centroid, noise, CAB 0.6 / 0.8 / 1 | 4863 / 3273 / 2005 Hz | 6099 / 4149 / 2184 Hz | 2052 +/- 10 % (1847-2257) | pass, CAB 1 now +6.4 % over the spike's 2052 |
| CAB step, 0.59-0.6 / 0.6-0.61 / 0.61-0.62 / 0.98-0.99 / 0.99-1 | 0.108 / 0.497 / 0.287 / 0.524 / 0.517 | 0.102 / 0.177 / 0.180 / 0.707 / 0.745 | 0.75 | pass, 0.99 to 1 by 0.005 dB |
| CAB 0 to 0.01 | -0.10 dB (top band) | -0.722 (top band) | 0.5 | FAIL |
| CAB darkens (snare centroid at CAB 0 / 1) | not recorded | 6171 / 409 Hz | wall < 0.8 open | pass |
| TONE shares (scoop / top cut / mids up / top up) | 8.19 / 2.29 / 4.12 / 3.76 | 8.19 / 2.32 / 4.12 / 3.79 | 6.0 / 1.8 / 3.3 / 3.0 | pass |

For the neutral-point test: the snare's RMS move fell from 0.361 dB to -0.028 dB (the round trip's phase change is gone, well inside the 0.44 bound). The kick's did not move (0.100 against 0.0985) because it was the 5 Hz blocker's contribution all along (+0.096), so the kick's 0.12 bound is not "well inside" and I would not tighten it. Both bounds would stay. That comment edit was not made (nothing is committed).

#### Cost (ms per rendered second, 4 s stereo kick, min and median of 7 runs after 4 warm-ups)

```
DRIVE 0.30  public 2.1 / 2.2   forced 4x 33.4 / 33.7   forced 1x 2.0 / 2.1
DRIVE 0.65  public 33.8 / 33.9 forced 4x 33.6 / 33.9   forced 1x 2.0 / 2.1
```

The gate turns 33.4 ms into 2.1 ms below the threshold, a saving of about 31 ms per rendered second, so the brief's "about 32 ms" is right for the KDoc. (`VALVE cost` in the existing test is a single run at the defaults, 34.2 ms.)

#### Assertions changed

None. No test bound and no other file was edited. The patch contains only the additions the brief lists.

### The gate diagnostics (`task-2-gate-diagnostics.txt`)

```
    GATESTEP drive=0.0 cab=0.0 kick worst 3225-4063 Hz 0.003 dB; top 3225-4063 0.003 dB; 4x-1x re 4x -28.7 dB
    GATESTEP drive=0.0 cab=0.0 snare worst 12901-16000 Hz 0.082 dB; top 12901-16000 0.082 dB; 4x-1x re 4x -0.2 dB
    GATESTEP drive=0.0 cab=0.5 kick worst 126-160 Hz -0.059 dB; top 3225-4063 0.011 dB; 4x-1x re 4x -28.9 dB
    GATESTEP drive=0.0 cab=0.5 snare worst 12901-16000 Hz 1.449 dB; top 12901-16000 1.449 dB; 4x-1x re 4x -2.9 dB
    GATESTEP drive=0.3 cab=0.0 kick worst 3225-4063 Hz 0.003 dB; top 3225-4063 0.003 dB; 4x-1x re 4x -28.7 dB
    GATESTEP drive=0.3 cab=0.0 snare worst 12901-16000 Hz 0.083 dB; top 12901-16000 0.083 dB; 4x-1x re 4x -0.1 dB
    GATESTEP drive=0.3 cab=0.5 kick worst 100-126 Hz -0.062 dB; top 3225-4063 0.011 dB; 4x-1x re 4x -28.9 dB
    GATESTEP drive=0.3 cab=0.5 snare worst 12901-16000 Hz 1.450 dB; top 12901-16000 1.450 dB; 4x-1x re 4x -2.9 dB
    GATESTEP drive=0.45 cab=0.0 kick worst 2560-3225 Hz 0.003 dB; top 3225-4063 0.003 dB; 4x-1x re 4x -28.7 dB
    GATESTEP drive=0.45 cab=0.0 snare worst 1015-1280 Hz 0.261 dB; top 12901-16000 0.088 dB; 4x-1x re 4x 0.3 dB
    GATESTEP drive=0.45 cab=0.5 kick worst 160-201 Hz -0.060 dB; top 3225-4063 0.010 dB; 4x-1x re 4x -28.8 dB
    GATESTEP drive=0.45 cab=0.5 snare worst 12901-16000 Hz 1.457 dB; top 12901-16000 1.457 dB; 4x-1x re 4x -2.8 dB
    GATESTEP drive=0.5 cab=0.0 kick worst 2560-3225 Hz 0.005 dB; top 3225-4063 0.004 dB; 4x-1x re 4x -28.6 dB
    GATESTEP drive=0.5 cab=0.0 snare worst 1015-1280 Hz 0.463 dB; top 12901-16000 0.093 dB; 4x-1x re 4x 0.7 dB
    GATESTEP drive=0.5 cab=0.5 kick worst 100-126 Hz -0.068 dB; top 3225-4063 0.008 dB; 4x-1x re 4x -28.8 dB
    GATESTEP drive=0.5 cab=0.5 snare worst 12901-16000 Hz 1.464 dB; top 12901-16000 1.464 dB; 4x-1x re 4x -2.7 dB
    GATESTEP drive=0.55 cab=0.0 kick worst 2560-3225 Hz 0.006 dB; top 3225-4063 0.006 dB; 4x-1x re 4x -28.4 dB
    GATEBANDS drive=0.55 cab=0.0 kick 40-50:-0.00 50-63:-0.00 63-80:-0.00 80-100:0.00 100-126:0.00 126-160:0.00 160-201:0.00 201-253:-0.00 253-320:0.00 320-403:0.00 403-507:0.00 507-640:0.00 640-806:0.00 806-1015:0.00 1015-1280:0.00 1280-1612:0.00 1612-2031:-0.00 2560-3225:0.01 3225-4063:0.01
    GATESTEP drive=0.55 cab=0.0 snare worst 1015-1280 Hz 0.745 dB; top 12901-16000 0.102 dB; 4x-1x re 4x 1.2 dB
    GATEBANDS drive=0.55 cab=0.0 snare 40-50:0.50 50-63:0.43 63-80:0.24 80-100:0.06 100-126:-0.02 126-160:-0.02 160-201:-0.03 201-253:-0.11 253-320:-0.05 320-403:-0.05 403-507:0.13 507-640:-0.05 640-806:-0.23 806-1015:0.52 1015-1280:0.75 1280-1612:0.09 1612-2031:-0.00 2031-2560:-0.00 2560-3225:0.09 3225-4063:0.06 4063-5120:-0.03 5120-6450:0.03 6450-8127:0.02 8127-10240:0.09 10240-12901:-0.04 12901-16000:0.10
    GATESTEP drive=0.55 cab=0.5 kick worst 126-160 Hz -0.063 dB; top 3225-4063 0.014 dB; 4x-1x re 4x -28.6 dB
    GATEBANDS drive=0.55 cab=0.5 kick 40-50:0.01 50-63:0.01 63-80:-0.00 80-100:-0.02 100-126:-0.06 126-160:-0.06 160-201:-0.05 201-253:-0.05 253-320:-0.05 320-403:-0.04 403-507:-0.04 507-640:-0.04 640-806:-0.03 806-1015:-0.03 1015-1280:-0.03 1280-1612:-0.03 1612-2031:-0.03 3225-4063:0.01
    GATESTEP drive=0.55 cab=0.5 snare worst 12901-16000 Hz 1.474 dB; top 12901-16000 1.474 dB; 4x-1x re 4x -2.7 dB
    GATEBANDS drive=0.55 cab=0.5 snare 40-50:0.55 50-63:0.40 63-80:0.18 80-100:0.02 100-126:-0.09 126-160:-0.09 160-201:-0.10 201-253:-0.17 253-320:-0.10 320-403:-0.14 403-507:0.07 507-640:-0.09 640-806:-0.28 806-1015:0.48 1015-1280:0.70 1280-1612:0.05 1612-2031:-0.04 2031-2560:-0.06 2560-3225:0.04 3225-4063:0.06 4063-5120:0.00 5120-6450:0.17 6450-8127:0.30 8127-10240:0.60 10240-12901:0.81 12901-16000:1.47
    GATESTEP drive=0.6 cab=0.0 kick worst 6450-8127 Hz 0.038 dB; top 6450-8127 0.038 dB; 4x-1x re 4x -28.0 dB
    GATESTEP drive=0.6 cab=0.0 snare worst 1015-1280 Hz 1.060 dB; top 12901-16000 0.116 dB; 4x-1x re 4x 1.8 dB
    GATESTEP drive=0.6 cab=0.5 kick worst 100-126 Hz -0.069 dB; top 3225-4063 0.022 dB; 4x-1x re 4x -28.3 dB
    GATESTEP drive=0.6 cab=0.5 snare worst 12901-16000 Hz 1.488 dB; top 12901-16000 1.488 dB; 4x-1x re 4x -2.4 dB
    GATESTEP drive=0.65 cab=0.0 kick worst 6450-8127 Hz 0.110 dB; top 6450-8127 0.110 dB; 4x-1x re 4x -26.6 dB
    GATESTEP drive=0.65 cab=0.0 snare worst 50-63 Hz 3.539 dB; top 12901-16000 0.155 dB; 4x-1x re 4x 3.1 dB
    GATESTEP drive=0.65 cab=0.5 kick worst 126-160 Hz -0.060 dB; top 3225-4063 0.022 dB; 4x-1x re 4x -27.1 dB
    GATESTEP drive=0.65 cab=0.5 snare worst 50-63 Hz 3.508 dB; top 12901-16000 1.522 dB; 4x-1x re 4x -1.6 dB
    GATECOST drive=0.3 public min/median 2.1/2.2  forced 4x 33.4/33.7  forced 1x 2.0/2.1 ms per rendered second (4 s stereo)
    GATECOST drive=0.65 public min/median 33.8/33.9  forced 4x 33.6/33.9  forced 1x 2.0/2.1 ms per rendered second (4 s stereo)
```

## Appendix: the spike's parameterised copy of VALVE

The file as it ran. It calls `Valve.sagTrack`, which V1.1 removed, and defaults its gain top to `Valve.GAIN_MAX`, which was 35 at V1 and is 1000 now, so it builds and reproduces V1 only against base `1a2ec180`.

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * THROWAWAY SPIKE (VALVE V1.1 candidate sets) - never committed.
 *
 * A parameterised copy of [Valve]'s `process` / `stage` / `cabinet`: the gain
 * law's top, the closed wall's corner and pole count, and three sag
 * mechanisms. With the default [Params] it must equal [Valve.process] sample
 * for sample (the regression anchor in ValveSpikeTest).
 */
internal object ValveSpike {

    enum class SagMode { BIAS, HEADROOM, SUPPLY }

    data class Params(
        /** Gain at DRIVE 1; the law is V1's below DRIVE 0.6 and log-linear from (0.6, g(0.6)) to (1, gainTop) above it. */
        val gainTop: Float = Valve.GAIN_MAX,
        /** The coil corner at CAB 1; corner = lin(cab, 5800, wallHz). */
        val wallHz: Float = 4_500f,
        /** 1 = one one-pole (as V1), 2 = two cascaded one-poles at the same corner. */
        val wallPoles: Int = 1,
        val sagMode: SagMode = SagMode.BIAS,
        /** HEADROOM's depth (calibrated, see [HEAD_DEPTH]). */
        val headDepth: Float = HEAD_DEPTH,
        /** SUPPLY's depth (calibrated, see [SUPPLY_K]). */
        val supplyK: Float = SUPPLY_K,
    )

    /** Copied by hand from Valve (private there). */
    private const val SAG_DEPTH = 0.45f
    private const val SAG_ATTACK_SECONDS = 0.005f
    private const val SAG_RELEASE_SECONDS = 0.120f

    /**
     * HEADROOM: pre-tube gain multiplier m = 1 - SAG * HEAD_DEPTH * vs / (1 + vs). Calibrated in the spike:
     * the kick's [60,160) ms level re its first 20 ms closest to SUPPLY's -5 dB target (4-6 dB) at SAG 1 /
     * DRIVE 1, with no 20 ms window of kick or snare more than 12 dB under SAG 0 (0.9 reads -13.4 there).
     */
    const val HEAD_DEPTH = 0.85f

    /**
     * SUPPLY: post-tube y = t / (1 + SAG * SUPPLY_K * env(|t|)). Calibrated in the spike: the -5 dB target
     * is not reachable inside 0.8..3 (K 3 reads -3.05 dB on the kick), so the closest end, 3.
     */
    const val SUPPLY_K = 3.0f

    private const val PIVOT = 0.6f

    /** DRIVE's gain into the tube. Exactly V1's law at or below DRIVE 0.6, and everywhere when gainTop is V1's 35. */
    fun gainFor(drive: Float, gainTop: Float): Float {
        val d = drive.coerceIn(0f, 1f)
        if (d <= PIVOT || gainTop == Valve.GAIN_MAX) return Valve.gainFor(d)
        val g6 = Valve.gainFor(PIVOT).toDouble()
        return (g6 * exp(ln(gainTop / g6) * ((d - PIVOT) / (1f - PIVOT)))).toFloat()
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap(), params: Params = Params(), oversample: Boolean = true): Snip {
        val m = Valve.defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val inPeak = snip.peak()
        if (inPeak <= 0f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val channels = snip.channels
        val frames = snip.frameCount
        val workRate = if (oversample) rate * Dsp.OVERSAMPLE else rate
        val outs = Array(channels) { ch ->
            val x = FloatArray(frames) { f -> snip.samples[f * channels + ch] / inPeak }
            val work = if (oversample) Valve.upsample(x, rate) else x
            val y = stage(work, m, workRate, params)
            if (oversample) {
                Tide.bandLimit(y, workRate)
                Dsp.decimate(y, rate)
            } else {
                y
            }
        }

        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            for (ch in 0 until channels) out[f * channels + ch] = outs[ch].getOrElse(f) { 0f }
        }
        var outPeak = 0f
        for (v in out) {
            val a = abs(v)
            if (a > outPeak) outPeak = a
        }
        if (outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, channels, rate)
    }

    private fun curve(b: Float): Float = if (b >= 0f) tanh(b) else b / sqrt(1f + b * b)

    /** Gain, sag (by mode), the tube, the DC blocker, then TONE and CAB - one channel at [rate]. */
    private fun stage(x: FloatArray, m: Map<String, Float>, rate: Int, p: Params): FloatArray {
        val g = gainFor(m.getValue("DRIVE"), p.gainTop)
        val sag = m.getValue("SAG")
        val v = FloatArray(x.size) { x[it] * g }
        val vSag = Valve.sagTrack(v, rate)
        val dc = Dsp.OnePole(rate)
        val out = FloatArray(x.size)
        when (p.sagMode) {
            SagMode.BIAS -> for (i in x.indices) {
                val b = v[i] - vSag[i] * sag * SAG_DEPTH
                val t = if (b >= 0f) tanh(b) else b / sqrt(1f + b * b)
                out[i] = t - dc.lp(t, Valve.DC_HZ)
            }
            SagMode.HEADROOM -> for (i in x.indices) {
                val vs = vSag[i]
                val e = vs / (1f + vs)
                val mul = 1f - sag * p.headDepth * e
                val t = curve(v[i] * mul)
                out[i] = t - dc.lp(t, Valve.DC_HZ)
            }
            SagMode.SUPPLY -> {
                val charge = 1f - exp(-1.0 / (SAG_ATTACK_SECONDS * rate)).toFloat()
                val release = 1f - exp(-1.0 / (SAG_RELEASE_SECONDS * rate)).toFloat()
                var env = 0f
                for (i in x.indices) {
                    val t = curve(v[i])
                    // The previous sample's env sets this sample's gain: no algebraic loop.
                    val y = t * (1f / (1f + sag * p.supplyK * env))
                    val a = abs(t)
                    env += (if (a > env) charge else release) * (a - env)
                    out[i] = y - dc.lp(y, Valve.DC_HZ)
                }
            }
        }
        Valve.tone(out, m.getValue("TONE"), rate)
        cabinet(out, m.getValue("CAB"), rate, p)
        return out
    }

    /** V1's speaker with the closed wall's corner ([Params.wallHz]) and pole count ([Params.wallPoles]) parameterised. */
    private fun cabinet(buf: FloatArray, cab: Float, rate: Int, p: Params) {
        if (cab <= 0f) return
        val w = (cab / 0.25f).coerceAtMost(1f)
        val thump = Dsp.Biquad().apply { peaking(Dsp.lin(cab, 110f, 78f), 6f * w, Dsp.lin(cab, 1.6f, 2.4f), rate) }
        val notch = Dsp.Biquad().apply { peaking(Dsp.lin(1f - cab, 380f, 500f), -9f * (1f - cab) * w, 2f, rate) }
        val breakup1 = Dsp.Biquad().apply { bandpass(2_600f, 3.5f, rate) }
        val breakup2 = Dsp.Biquad().apply { bandpass(3_750f, 4f, rate) }
        val coilHz = Dsp.expMap(1f - w, Dsp.lin(cab, 5_800f, p.wallHz), rate * 0.45f)
        val coil = Dsp.OnePole(rate)
        if (p.wallPoles <= 1) {
            for (i in buf.indices) {
                val s = buf[i]
                val body = notch.process(thump.process(s))
                val breakup = w * (0.35f * breakup1.process(s) + 0.25f * breakup2.process(s))
                buf[i] = coil.lp(body + breakup, coilHz)
            }
        } else {
            val coil2 = Dsp.OnePole(rate)
            for (i in buf.indices) {
                val s = buf[i]
                val body = notch.process(thump.process(s))
                val breakup = w * (0.35f * breakup1.process(s) + 0.25f * breakup2.process(s))
                buf[i] = coil2.lp(coil.lp(body + breakup, coilHz), coilHz)
            }
        }
    }
}
```
