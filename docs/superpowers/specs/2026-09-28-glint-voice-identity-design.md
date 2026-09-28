# GLINT depth D4 — the window says *when*, the carrier says *what*

**Status:** design. Not implemented.
**Date:** 2026-09-28
**Parent:** [`2026-09-26-glint-depth-design.md`](2026-09-26-glint-depth-design.md) — D1 and D2 shipped. This is the pass D2's audition demanded.
**Supersedes:** that spec's claim that "a voice becomes a mechanism, not a window shape" was achieved. It was achieved on two voices of six.

## 1. The problem, in measured terms

Josh auditioned 20 D2 clips and said **"bottle and kazoo sound identical"** and **"plate and reed sound identical."** A probe confirmed it and found the finding generalises.

At matched pitch, energy-weighted third-octave spectral distance (0–2 scale):

| measurement | value |
|---|---|
| the four window-only voices (REED, BOTTLE, KAZOO, PLATE), all six pairs | **0.096 – 0.222** |
| gap from that cluster to any other pair | 7.6× |
| fraction of energy all four put in the *same* third-octave band | 0.46 – 0.49 |
| *for scale:* BODY 0 vs 1, same voice | 0.5259 |
| *for scale:* one semitone of TUNE | 0.6131 |
| *for scale:* one snapped PEAK harmonic | 1.5569 |
| RATCHET and CICADA — the two **mechanism** voices — against the cluster | **1.75 – 1.98** |

Swapping the window between four of six voices changes the render **less than moving BODY, and less than one semitone.** Mechanism separates; window shape does not.

This is not a tuning miss, it is structural. For a one-cycle window the harmonic amplitudes are `(1/2j)·[W(n−k) − W(n+k)]`: the window sets the **shape** of the spectral envelope, `k` sets its **centre**. An energy-weighted measure is main-lobe dominated, so it is nearly blind to window shape *by construction* — and the listener agreed with it. Corroborating closed form: `KAZOO_FLAT = 0` is byte-identically REED's saw, and the **entire parameterised window family** (saw; any trapezoid flat; any triangle peak) has a widest pair anywhere of **0.2141**. No choice of window was ever going to work.

Two bugs the same probe found, both confirmed by three independent reconstructions:

1. **RATCHET's BLOOM is dead across most of its travel.** BLOOM sets the ladder's *ceiling*, but the note ends long before the ceiling is reached. Closed form, verified: `B_dead = floor(9·t60)/24`. At the shipped DECAY 0.5 every BLOOM from 0.125 to 1.0 renders **byte-identically** — 87.5% of the knob. 97.7% of the note's energy is in the first two rungs, which BLOOM cannot touch at all.
2. **PLATE is REED with one substituted expression.** Same `windowAt` arm (`1f − p`), same `rootHz` (110), same defaults, same ceiling. The only difference is k's modulation source: `amp.at(t)` vs `Dsp.envAt(t, BLOOM_T60)`. They are the same motion at the same rate when `t60 = BLOOM_T60`, i.e. at `DECAY = ln(0.45/0.12)/ln(1.4/0.12) = 0.5380`. It ships at 0.5, where PLATE and REED measure **0.0989–0.1302 — less different from each other than two window shapes are.**

And a finding about the audition itself, which changes how the next one must be run: **FOLLOW is exactly inert at TUNE 0.5** (at the reference note `keyTrack`'s ratio is 1, so FOLLOW 0 and FOLLOW 1 render identically — measured 0.0000), and both existing audition generators pin TUNE to 0.5. The human who said "identical" judged clips on which one of the six macros could not be heard at all.

## 2. The decision

**A GLINT voice is a carrier geometry plus at most one clock. The window says *when* in the cycle the burst is loud; the carrier's phase map says *what* happens inside it — and the window's own asymmetry decides which phase maps a voice is allowed to carry.**

Concretely: every voice adds **either a phase warp or a clock, never neither and never both.** REED, BOTTLE, KAZOO and PLATE get a per-voice phase warp. CICADA (sub-cycle clock) and RATCHET (above-cycle ladder) keep the identity warp and are otherwise untouched.

This is the half of the Casio phase-distortion lineage GLINT is named for and never implemented. The class doc says "GLINT — phase distortion," and today `sin(2·PI·k·phase)` takes the phase raw: the only distortion in the engine is the windowing. After this, the name is literal.

## 3. The mechanism

### 3.1 The warp family

One line changes in the render loop:

```kotlin
val burst = w * sin(2.0 * PI * k * warpAt(voice, carrier)).toFloat()
```

`w` is still read at the **unwarped** `carrier`. `warpAt` is an antisymmetric three-segment monotone map through one per-voice skew `S > 0`:

```kotlin
/** The breakpoint and its image, derived from the skew so the map is antisymmetric. */
private fun breakAt(s: Float) = 1f / (2f * (s + 1f))          // a
private fun breakTo(s: Float) = s / (2f * (s + 1f))           // b

internal fun warpAt(voice: GlintVoice, phase: Float): Float {
    val s = skewFor(voice)
    if (s == 1f) return phase                                  // exact identity, no float work
    return if (phase <= 0.5f) halfWarp(phase, s) else 1f - halfWarp(1f - phase, s)
}

private fun halfWarp(q: Float, s: Float): Float {
    if (q >= 0.5f) return 0.5f
    val a = breakAt(s)
    return if (q < a) s * q else breakTo(s) + (q - a) / s
}
```

Geometry: the two **edges** `[0, a]` and `[1−a, 1]` run at slope `S`; the **middle** `[a, 1−a]` runs at slope `1/S`. So the single formant at `k·f0` splits into a **pair** at `k·S·f0` and `k·f0/S`, geometrically centred on `k·f0`.

**Why antisymmetric rather than CZ's classic two-segment map.** For integer `k`, `g(1−p) = 1−g(p)` makes the carrier **odd about p = 0.5**, exactly as the unwarped sine is. The render's DC therefore remains purely the window's own asymmetry — which is what `BODY carries a small, bounded DC` already measures and what the file's own comment says cannot be corrected (subtracting a constant would stop the window reaching exactly zero at the wrap). A two-segment warp does not have this: measured ~9% carrier DC at S=2, k=4 under a flat window, against a 0.05 bar. Antisymmetry is not decoration; it is what makes the warp shippable in an engine that is forbidden to mean-remove.

**Zero-at-wrap is untouched, by construction, not by measurement.** `g` never appears inside `w`. `w(1) = 0` exactly for all three windows (`1f−1f`; `(1f−1f)*2f`; `(1f−1f)/(1f−KAZOO_FLAT)`), so the burst is exactly zero at every wrap whatever `g` does. `g(0) = 0` and `g(1) = 1 − halfWarp(0) = 1` exactly, so the carrier still completes exactly `k` cycles per window and the signal stays periodic at `f0`. `g` is continuous at `a`, `0.5` and `1−a` (both arms give `b` at the break) and monotone for every `S > 0`. The mirrored form is antisymmetric **to the bit**, not just in exact arithmetic: `1f − p` is Sterbenz-exact on `[0.5, 1]`. `S = 1` returns `phase` unchanged with no arithmetic at all, so an unwarped voice renders byte-identically to today.

What the warp adds is two **slope** breaks per cycle — value-continuous corners with 1/n² rolloff, the same class as the wrap's own slope jump the class doc calls "the buzz the engine is made of." A corner, not a jump. Independent measurement: worst adjacent-sample jump 0.070–0.256 against shipped CICADA's own 0.255; one-period correlation 0.99978–0.99998 against the 0.98 bar.

### 3.2 The constraint band — what bounds the skew

Three bounds, applied in this order. **The band is fixed by this spec; the values inside it are not.**

**(a) Aliasing headroom.** The warp multiplies the carrier's instantaneous frequency by up to `max(S, 1/S)`. The rule:

```
kCeilingFor(voice) · f0_max(voice) · max(S, 1/S)  ≤  WARP_NYQUIST_MARGIN · (Dsp.RATE · Dsp.OVERSAMPLE / 2)
```

`WARP_NYQUIST_MARGIN = 0.8f` is **a choice, not a derivation** — the rest of the bound is arithmetic. With it: A2-root voices (REED, PLATE; `f0_max` 440, ceiling 40 → 17.6 kHz) get `max slope ≤ 4.0`; A3-root voices (BOTTLE, KAZOO, RATCHET; 880 × 40 = 35.2 kHz) get `≤ 2.0`; CICADA lands on 2.0 too, via ceiling 10 × 4 sub-cycles × 880. The two voices that most need separation are the two with the most headroom, which is luck rather than design but is load-bearing below.

**(b) The DC direction rule, and it is what makes the window a constraint rather than a differentiator.** Integrating `∫w(p)·sin(2πk·g(p))dp` by parts, the leading term carries `w(0) − w(1)·cos(2πk)`. On the **saw and trapezoid** (`w(0) = 1`) the slow region must not sit at the loud edge, so those voices may only take `S > 1` (fast edges). On the **triangle** (`w(0) = w(1) = 0`) the boundary term vanishes and DC is second-order at any `S` — measured 1e-18 at any skew. **`S < 1` is available only to the triangle.** That is BOTTLE's structural exclusive and the reason its identity is not a leftover.

Caution, recorded because a reader will otherwise re-derive the wrong thing: the leading term is *not* the whole story. Interior breakpoint terms scale like `S/(2πk)` and dominate at larger skews. Independently measured on the saw at k=4: S=1 → +0.0398, S=2 → +0.0103, S=2.5 → −0.0295, S=4 → −0.1163. So on asymmetric windows the antisymmetric warp **improves** DC up to about S ≈ 2.2 and degrades past it, and 4.0 is 2.9× worse than unwarped. Bound (a) is not the binding one on the saw; DC is. **Measure `BODY carries a small, bounded DC` at every skew before fixing the table.**

**(c) K_MIN's two-cycle premise, per region.** `g(1) = 1` conserves total carrier cycles, so the warp cannot *add* a formant — it cuts one k-cycle burst into three fragments at two rates. Per region at skew `S`: each fast edge carries `k·S/(2(S+1))` cycles, the middle carries `k/(S+1)`. The middle is the binding one, and at the shipped default kBase 8 it needs `S ≤ 3` to clear K_MIN's stated two-cycle floor. Below kBase ≈ 6 neither region clears it at any useful skew.

**This is an honest limitation and it is a character question, not a defect.** `K_MIN`'s own KDoc says "below two burst cycles inside the window there is no peak, only a dull fragment." At low PEAK a warped voice is a bright broadband edge over a hollow, not two resolved formants. That is what several independent readers measured and it is what the listener must judge. Do not write "two formants" into the KDocs; write "a bright region over a hollow," which is what it is.

### 3.3 The per-voice table

The differentiating quantity is the **split**, `S²` — the ratio between the pair's two regions. PEAK *translates* the pair in log-frequency; the split *dilates* it. That is exactly why the skew is orthogonal to PEAK and why a player cannot dial one voice into another: moving PEAK cannot narrow a 9× span to a 2.25× one.

**Distinctness is therefore stated over splits, not skews.** Every voice's split must be distinct from every other's, including across different windows and roots — two voices at the same split differ only by window, which is the 0.107–0.149 cluster this pass exists to kill.

| voice | window | root | S | max slope | split S² | loud region | quiet region | clock |
|---|---|---|---|---|---|---|---|---|
| **REED** | saw | 110 | **3** | 3.0 | **9.00** | `3k` (fast edge) | `k/3` | — |
| **PLATE** | saw | 110 | **3/2** | 1.5 | **2.25** | `1.5k` (fast edge) | `0.667k` | — |
| **BOTTLE** | triangle | 220 | **3/5** | 1.67 | **2.78** | `1.667k` (fast **middle**) | `0.6k` | — |
| **KAZOO** | trapezoid | 220 | **2** | 2.0 | **4.00** | `2k` **and** `k/2` — both at full window gain | — | — |
| **CICADA** | triangle | 220 | **1** | 1.0 | 1.00 | `k` | — | sub-cycle ×4 |
| **RATCHET** | trapezoid | 220 | **1** | 1.0 | 1.00 | `k` | — | ladder |

At the shipped PEAK 0.45 (kBase 8), matched pitch f0 440: REED 10.6 kHz over 1.17 kHz; PLATE 5.28 kHz over 2.35 kHz; BOTTLE 5.87 kHz with a quiet 2.11 kHz; KAZOO 7.04 kHz **and** 1.76 kHz co-loud; CICADA 14.1 kHz (k×4); RATCHET 3.52 kHz stepping upward.

What each voice becomes:

- **REED** — the widest pair in the engine, a 9:1 split under the saw's loud leading edge. A bright buzz over a low body, which is what a double reed is. Middle region carries exactly 2.0 cycles at the default PEAK: REED sits *on* K_MIN's floor and is the voice that shows the low-PEAK fragment first.
- **PLATE** — the same saw, the same root, a **narrow** pair around the same centre, and it keeps its amp-coupled `k`. See §5.
- **BOTTLE** — the only voice that can run a **fast middle**, because the triangle is the only window whose DC is free at `S < 1`. Its loud region sits under the window's own peak, where a resolved formant can actually form. **This is the weak case by construction** — the triangle concentrates window energy at one point, so a warp under it is closer to a register move than a second formant. See §7.
- **KAZOO** — the trapezoid is flat for 70% of the cycle, so the fast leading edge *and* the slow middle are both at full window gain: **two co-loud formants two octaves apart with a spectral valley between them.** No PEAK setting of any single-region voice can produce that, and it is the one voice an independent judge measured as genuinely unreachable (imitation residual 0.774 from REED, 0.756 from BOTTLE, against today's 0.314/0.370).
- **CICADA** — byte-identical. `S = 1` is the exact identity in the family, so its carrier, its ceiling (10) and the register this branch just settled are untouched.
- **RATCHET** — carrier byte-identical at BLOOM 0. Its identity is the ladder plus being the one unwarped asymmetric-window voice. See §4.

**The table is a starting point; the band in §3.2 is the spec.** Phase 1 lost three rounds to predicted numbers wrong by up to 10×, and its own rule stands: *every threshold is set from a measurement taken during implementation, never from a prediction.* The implementer runs the §7 imitability search across the band, re-spreads the splits if any row fails, and re-measures **as assigned** — never by summing single-lever figures. An earlier probe measured a two-lever combination scoring 0.374 where one of its levers scored 0.970 alone.

### 3.4 New constants

| constant | value | justification to carry in the KDoc |
|---|---|---|
| `WARP_NYQUIST_MARGIN` | `0.8f` | the fraction of the oversampled Nyquist the brightest reachable carrier may occupy. A choice, not a derivation; the rest of §3.2(a) is arithmetic. |
| `REED_SKEW` | `3f` | the largest skew whose middle region still clears K_MIN's two-cycle floor at the shipped PEAK (`k/(S+1) = 2.0` at kBase 8). |
| `PLATE_SKEW` | `1.5f` | one octave below REED's loud region and one octave above its quiet one, on the same window and root — the widest split gap available to the pair that must stop coinciding. |
| `BOTTLE_SKEW` | `0.6f` | the fast-middle direction, available only under the triangle (§3.2b). Max slope 1.67, inside the A3 bound of 2.0. |
| `KAZOO_SKEW` | `2f` | exactly the A3 slope bound; the split that puts both regions under the trapezoid's flat top at full gain. |
| `RATCHET_STRIDE_SPAN` | `6` | the largest stride leaving ≥ 3 rungs at the default kBase and full BLOOM (8 → 14, 20, 26, 32), so the top of the knob is still a staircase and not one jump. |
| `RATCHET_STEP_FRACTION` | `0.10f` | `0.15 / 1.4 ≈ 0.107` — chosen so the **longest** note keeps the ~150 ms lurch `RATCHET_STEP_SECONDS` was specified for. The shipped behaviour becomes the long-note case rather than being replaced. |
| `RATCHET_STEP_MIN_SECONDS` | `0.030f` | 6.6 cycles at RATCHET's own 220 Hz root, so the wrap quantisation the existing KDoc argues is inaudible stays under 15% of a step; and 33 steps/s, below where a staircase reads as a purr. |

`RATCHET_STEP_SECONDS = 0.15f` is **deleted**, not edited. Its whole KDoc argument is about an absolute 150 ms against the rendered length; that argument now lives in `RATCHET_STEP_FRACTION`'s and must be rewritten, not repointed.

### 3.5 What is deliberately *not* touched

`windowAt` (so the founding property and the window-pairing test are untouched by construction). `kCeilingFor` (see §8). `macrosFor` — it still ignores its `voice` parameter; per-voice defaults clear the metric at one point and leave the voices identical at matched macros, which passes the measure and dodges its intent. `BODY`, `bodyRatio`, `BODY_MIX`, `BODY_RATIO_DIVISOR`. `Dsp.RATE`/`OVERSAMPLE` and the U6 contract — `synthesize` still takes `rate`. No presets. Nothing references a file.

### 3.6 One test seam

```kotlin
internal fun synthesize(
    voice: GlintVoice,
    macros: Map<String, Float>,
    rate: Int,
    subcycles: Int = subcyclesFor(voice),
    skew: Float = skewFor(voice),
): FloatArray
```

Default arguments, so every existing call site is unchanged. `subcyclesFor(v) = if (v == CICADA) CICADA_SUBCYCLES else 1`, and `N = 1` is the exact identity (`frac(1·phase) == phase` for `phase ∈ [0,1)`), so this is a no-op refactor for five voices. It exists because warping BOTTLE destroys the matched-carrier control both mutation-verified CICADA guards depend on — see §6.

## 4. RATCHET's BLOOM — stride, not ceiling

Three changes. The first is the brief's; the other two are what independent measurement showed the first alone does not fix.

```kotlin
internal fun ratchetLadder(kBase: Float, bloomAmount: Float): FloatArray {   // SIGNATURE UNCHANGED
    val kCeiling = kCeilingFor(GlintVoice.RATCHET)
    val bottom = snapRatio(kBase.coerceIn(K_MIN, kCeiling))
    val top = (kBase * (1f + bloomAmount)).coerceIn(K_MIN, kCeiling)          // UNCHANGED
    val stride = Math.round(bloomAmount / BLOOM_MAX * RATCHET_STRIDE_SPAN)    // NEW
    val rungs = ArrayList<Float>()
    rungs.add(bottom)
    if (stride >= 1) {
        var k = floor(bottom) + stride
        while (k <= top && rungs.size < kCeiling.toInt()) { rungs.add(k); k += stride }
    }
    return rungs.toFloatArray()
}
```

and in `synthesize`, at the wrap:

```kotlin
val ratchetStep = (t60 * RATCHET_STEP_FRACTION).coerceAtLeast(RATCHET_STEP_MIN_SECONDS)
...
if (ladder != null) rung = (t / ratchetStep).toInt().coerceAtMost(ladder.size - 1)
```

**(1) BLOOM sets the stride.** The bug exists because a ceiling only ever alters rungs *past* the one the note reaches — always the last and quietest. A stride alters rung **two**, where 97.7% of the energy's complement actually lives.

**(2) BLOOM still sets `top`.** This is deliberate and it is where this spec differs from three of the proposals it draws on. The spec's macro table gives BLOOM exactly one cross-voice meaning — *"how far the formant travels; each voice supplies how"* — and a stride-only redesign converts RATCHET's BLOOM into tooth coarseness, which breaks that meaning on one voice and kills `RATCHET climbs further as BLOOM opens`. Keeping `top` costs nothing (it is the shipped line, unedited), keeps BLOOM meaning travel, and keeps BLOOM 0 returning exactly one rung — which three existing tests and `Velocity.kt`'s KDoc depend on.

**(3) The step scales with t60, floored.** This is the part the brief did not specify and that measurement forced. Stride alone at the shipped 150 ms is *just as dead*: independently measured at DECAY 0.5, stride 1 vs 4 = **0.0144**, still inside the old cluster, because rung 2 still arrives at t = 0.15 s where the amp envelope is 0.0798. With `0.10·t60` rung 2 arrives where the envelope is ≈0.50 at **every** DECAY, so `B_dead = floor(9·t60)/24` dies rather than shrinks — BLOOM's authority becomes DECAY-invariant, which is the bug's real complaint.

Ladders at kBase 8: BLOOM 0 → `[8]`; 0.125 → stride 1 → `[8,9,10,11]`; 0.35 (shipped default) → stride 2 → `[8,10,12,14,16]`; 1.0 → stride 6 → `[8,14,20,26,32]`.

Steps: 30 ms at DECAY 0 (floored, 33/s, ~5 boundaries in a 0.162 s note); 41 ms at DECAY 0.5 (24/s, ~13 boundaries); 140 ms at DECAY 1.0 (7/s, ~13 boundaries) — the shipped 150 ms behaviour survives as the long-note case.

**Contracts held, checked by hand at all three kBase regimes.** Every rung above the bottom is `floor(bottom) + n·stride` with an integer stride, so `RATCHET's steps land on integer harmonics` passes at kBase 8, 2.1234918 and 16.28362. The bottom rung is still `snapRatio(kBase)`, unrounded below `SNAP_FLOOR` and above `SNAP_CEILING`, so the velocity byte-identity bug stays fixed at PEAK 0.02–0.06. BLOOM 0 gives one rung, byte-identically. The two-argument signature is preserved, so `GlintD2AuditionGenerator.kt:108` still compiles — **and the parameter keeps meaning `bloomAmount` (0..3), not raw BLOOM.** A silent unit change there is the one place a wrong number could ship unnoticed; `RATCHET_STRIDE_SPAN` is applied to `bloomAmount / BLOOM_MAX` inside the function precisely so no call site has to change units.

**Honest residual: an integer stride makes BLOOM a seven-position detent** (strides 0..6), so roughly half the knob's travel is flat *between* detents. That is a quantised knob, not a continuous one, and it is forced by the "rungs land on integer harmonics" requirement. 87.5%-dead is replaced by detented-but-live, and the detents are audibly distinct at every DECAY. It is the right trade and it should be named in the KDoc rather than hidden.

**`last()` is not strictly monotone in BLOOM, and the existing test must not be left resting on that.** With an integer stride and a continuous `top`, `last() = floor(bottom) + stride·floor((top − floor(bottom))/stride)` can fall as the stride grows (top 14 / stride 2 → 14; top 15 / stride 4 → 12). The two points the shipped test probes do pass (BLOOM 0.25 → 14, BLOOM 1 → 32) but they pass by arithmetic accident, and this file's culture does not accept that. See §6 for the replacement.

## 5. PLATE — it keeps its mechanism and gains a geometry

**PLATE survives. Its amp-coupled `k` is unchanged.** What changes is that REED and PLATE stop sharing a carrier.

```kotlin
GlintVoice.PLATE -> (kBase * (1f + bloomAmount * amp.at(t))).coerceIn(K_MIN, kCeiling)   // UNCHANGED
```

with `PLATE_SKEW = 1.5f` against `REED_SKEW = 3f`.

**Why this and not one of the three alternatives.**

*Not a moved default.* The escape window is only `[0.54, 0.577]` before the oversample-path cliff at 0.580 (measured 0.002695 @ 0.535 → 0.000962 @ 0.580, against a 0.001 bar), and a moved default relocates the coincidence rather than removing it. `k ∝ amp^p` is just an exponential with `t60/p`: any monotone coupling of `k` to loudness matches REED's fixed-rate sweep at *some* DECAY.

*Not "pin the centre and move something else."* Two independent reconstructions measured what happens when PLATE's BLOOM is moved onto window duty or onto the warp breakpoint instead of `k`: `PLATE's formant falls as the note decays` reads tail/head **0.831–0.997** against a `< 0.80` bar (fails), `PLATE's fall tracks the amplitude envelope, not a separate curve` reads **1.000–1.047** against a `> 1.15` bar (fails), and `BLOOM opens the peak at the attack` reads **0.731** — the strike *darker* than the tail. The cause is structural: a phase warp conserves `∫g' = 1` over the cycle, so it cannot move the mean instantaneous carrier, and a power-weighted centroid barely responds. Every version that pins PLATE's centre kills two mutation-verified guards and does not actually make the voice brighter at the strike. The advertised identity — "a struck plate, brightest at the strike" — is delivered by the k-coupling and by nothing else.

*Not deletion.* Deleting PLATE is defensible on the metric (0.0989 from REED, 0.0001 at DECAY 0.538) and it is the one move that cannot be walked back. It is a product call and it belongs to the listener, not to a spectral distance that is deliberately blind to amplitude envelope and attack. It is §9's question, not this section's decision.

**Why the coincidence cannot return.** REED and PLATE now run different phase maps. PLATE's pair sits at `{1.5k, 0.667k}`, span 2.25; REED's at `{3k, 0.333k}`, span 9.0 — an octave apart on *both* regions, around the same geometric centre. That difference is a property of two static transfer functions, so no value of DECAY can cancel it, and PEAK cannot reach it either: PEAK translates a pair, it does not dilate one. At the analytic degeneracy DECAY 0.5380, where the two motions genuinely are the same motion at the same rate, the renders are separated by the static geometry alone.

**And that is the single number that decides whether bug 2 is fixed or relocated.** Warp-against-warp on the same window measured **0.579–1.650** across five breakpoints, and the low end of that range is about one PEAK notch. `REED vs PLATE at DECAY 0.5380, swept across PEAK` is an acceptance row in its own right (§7). If it fails, the fix inside this approach is to widen PLATE's split downward — `PLATE_SKEW` has room to 4.0 under bound (a) and to ~2.2 under bound (b) — not to reopen the mechanism.

## 6. What this breaks

One production file changes behaviour: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`. Roughly +110 lines of code and eight new constants; but this file spends 20–100 KDoc lines per constant, and `ratchetLadder` alone carries ~70 lines of doc against 12 of code. **Budget 300–450 touched lines in `Glint.kt` and 300+ in `GlintTest.kt`.** "One file, ~110 lines" is true of the code and false of the work.

### Dies or must be rewritten

| test | what happens |
|---|---|
| `PEAK pins the formant on the named harmonic, not just relatively` (`:443`) | **Inverts** on warped voices, it does not merely re-measure. At REED's skew there is little energy at `k·f0` — it is between the pair's two regions — and the `2k` decoy arm collapses. Replacement must probe `S·k·f0` and `k·f0/S` with a **separate bar on each**, plus two decoys off both (`√S·k·f0` between them, `2·S·k·f0` above). Do **not** soften it to "energy anywhere across the band": that would pass on anything and quietly gut the file's main PEAK guard. CICADA and RATCHET keep the existing arm verbatim. Record this as a real reduction in PEAK discrimination. |
| `RATCHET climbs further as BLOOM opens` (`:1572`) | Survives arithmetically but **must be replaced anyway**: it is four lines that never call `Glint.render`, which is exactly why it passed through the whole of bug 1. Replacement asserts (a) `ks[1] − ks[0]` — the stride — rises with BLOOM across a grid, monotone by construction; (b) the original `last()` claim at its original two points, now with a stated reason; (c) that two BLOOM settings **render different audio** at DECAY 0.5. |
| `RATCHET's formant is piecewise constant, not a ramp` (`:1484`) | **Compile break** — reads `Glint.RATCHET_STEP_SECONDS` in code. Probe windows must be re-derived against `0.10·t60` at its own fixture. |
| `RATCHET does not click when it steps` (`:1643`) | **Compile break**, and worse: its whole TUNE = 0.3 rationale is that `220 × 0.15 = 33` is an integer. That arithmetic is void. Pick a DECAY whose `f0 × step` is an integer, re-derive the fixture, and **re-run the recorded mutation** (rung update moved out of the wrap guard) to confirm the guard still discriminates. |
| both mutation-verified CICADA guards (`:1306`, `:1386`) | Their matched-carrier BOTTLE control is valid only because BOTTLE shares CICADA's triangle **and** its carrier. Warped BOTTLE has neither. Replace the control with **CICADA at `subcycles = 1`** via the §3.6 seam — same voice, same window, same root, same macros, same skew, mechanism absent. Strictly better isolation than a second voice can give. **Re-run both recorded mutations** (6.95× and 2.2×) to re-verify; a control that moves the wrong way is the exact defect these tests' own comments record killing their predecessor. |
| `PEAK sweep is monotonic` (`:1194`) | All six nine-point rows move. The recorded RATCHET ≡ KAZOO byte-identity at BLOOM 0 **dies** (KAZOO is warped, RATCHET is not) and the 18-line comment establishing it must be rewritten, not edited. CICADA's 4.8× against the 3× end-to-end bar is the binding case — measure it, do not assume it. |
| `PLATE at BLOOM zero` (`:1684`) | Already a non-guard: `0f * x == 0f`, identical with and without the coupling. Replace with the assertion that was actually missing — **PLATE at BLOOM 0 must not equal REED at BLOOM 0**, which the warp now makes a real claim. |

### Must be re-measured, expected to pass

`BODY carries a small, bounded DC` (`:738`) — must be re-measured at **every skew and worst-over-PEAK**, not at the single default point it uses today. Its own KDoc already records KAZOO at 0.0619 at PEAK 0, over the 0.05 bar, from the burst alone — so strengthening it to worst-over-PEAK will flag the *shipped* engine too. Resolve that explicitly: either name the PEAK range the bar applies over, or raise the bar with a recorded measurement. Do not tune a skew to dodge a single point.

`render dispatches through the oversampled path` (`:500`) — the tightest margin in the file (0.002703 against 0.001, on REED and PLATE). An independent reconstruction measured the warp *gaining* margin here (REED 1.6×, PLATE 1.8×), but that is a proxy; confirm on the real engine before anything else is written.

`the formant sweeps and the pitch does not move` (`:316`) — PLATE's recorded minimum is **0.9824176 against a 0.98 bar, 0.0024 of margin.** A warped spectrum is sharper and decorrelates faster for the same fractional `k` change, so the risk runs against us. **Measure this first.** A failure means lowering `PLATE_SKEW`, not re-recording a number.

`BLOOM opens the peak at the attack` / `BLOOM lands before the note ends` (`:1004`, `:1038`) — RATCHET's arms re-measure under stride and the t60 step; the ladder now saturates and **holds**, which is the shape the settle test's KDoc says every other mechanism has. No voice changes its BLOOM meaning, so neither `else -> error(...)` tripwire needs a new branch and no new `FeatureExtractor` field is required.

### Untouched, and this is the cheapest thing about this approach

`each voice's window is the shape the spec pairs it with` (`:89`) — `windowAt` is not modified, so the single hard break every window-side proposal had to pay simply does not occur. `every window ends at exactly zero` (`:81`). The three PLATE tests (`:1684`/`:1729`/`:1760`) — PLATE keeps its coupling, so its mutation-verified trio survives. `velocity always changes the render, at every PEAK` (`:1250`). `PEAK values inside one snap zone render identically` (`:416`) — nothing keys off raw PEAK; **extend it from BOTTLE-only to all six**, since five voices could violate it silently today. `bodyRatio` keeps its one-argument signature, so `GlintD1AuditionGenerator.kt:104/:110` compile. Outside `GlintTest.kt`: `DeterminismTest`, `PadRecipeTest`, `VelocityGrooveShuffleTest`, `SirenPresetsTest` and `SynthScreen.kt` (`drumClass` is a flat `TONAL`) are all unaffected.

### New guards this pass owes

1. **The founding property, at the render level.** `every window ends at exactly zero` probes `windowAt` in isolation and would sail straight past a broken render loop. Assert on the raw oversampled buffer that `|out|` at each cycle's last sample is within the window's own one-sample step residual, for every voice at defaults, BLOOM 1 and PEAK 1.
2. **The warp is antisymmetric to the bit, monotone, `g(0)=0`, `g(1)=1`** for every shipped skew — and `warpAt(v, p) === p` bit-for-bit when `skewFor(v) == 1f`.
3. **The measured problem itself has no guard at all today.** Add the §7 imitability matrix as a test with a floor. That absence is why this pass exists.

### Documentation that goes stale with no test to catch it

`Velocity.kt`'s `BRIGHTNESS_MACROS` KDoc (~438–482) hardcodes all six nine-point PEAK sweeps and reproduces the RATCHET ≡ KAZOO and PLATE ≡ REED byte-identity claims. Four of six rows move and both identity claims become false. Hand-update in the same pass. `docs/SYNTH_ROADMAP.md:265` describes `RATCHET_STEP_SECONDS` and must be amended when implementation starts.

### Existing guards that cannot fail — flagged so nobody relies on them

- `RATCHET climbs further as BLOOM opens` — four lines, never renders; passed throughout bug 1.
- `every voice renders clean audio` — whole-buffer `|average| < 0.05`, measured 0.000003–0.003146 across all six voices, 16× inside the bar.
- `PLATE at BLOOM zero` — `0f * x == 0f`, identical with and without the mechanism.
- `PLATE's formant falls as the note decays` — the mechanism-**absent** control reads 0.467 against the with-mechanism 0.374; both clear the 0.8 bar.
- `atVelocity is genuinely darker` — `soften` darkens too.
- `the ratio floor holds` — restates `ratioFor`'s own final `coerceIn` and is blind to `synthesize`'s per-sample clamp.
- `PEAK pins the formant`'s halved-decoy arm — probes `1.5·f0`, off the harmonic grid, reading 23,810×–561,187× against a 20× bar. Only the `2k` arm (204×) bites.
- `every window ends at exactly zero` — tests `windowAt` in isolation, never the render loop.

## 7. How we will know it worked

### The measure

Energy-weighted third-octave L1 distance between energy-normalised band vectors, 0–2 scale, at **matched pitch and matched macros**, on a harness calibrated in the same run against three published anchors: today's window cluster **0.096–0.222**, CICADA-vs-cluster **1.75–1.98**, BODY 0-vs-1 **0.5259**. Report today's numbers beside the proposed ones from the same harness; three independent reconstructions of this engine disagreed by up to 2× in absolute scale, so **every bar below is relative as well as absolute, and the relative form governs.**

### The bars

| # | measurement | bar |
|---|---|---|
| **A** | **Imitability.** For every ordered voice pair (A,B), the minimum distance over **both** voices' macro grids — PEAK × BLOOM × DECAY, at least 9 × 3 × 3 each. "Can B be dialled into A?" | **≥ 0.55 absolute, and ≥ 2× today's worst row on the same harness** (today: 0.276–0.417) |
| **B** | **REED vs PLATE at DECAY 0.5380**, swept across PEAK. The analytic degeneracy point. | **≥ 0.55**, and no PEAK pair under 0.40 |
| **C** | **BOTTLE from REED via PEAK.** The identified weak row (§3.3). | **≥ 0.55** |
| **D** | Matched-macro pairwise minimum across ≥ 18 macro settings, **excluding** the pre-existing PEAK ≥ 0.9 corner | **≥ 0.53** (the BODY benchmark) |
| **E** | RATCHET BLOOM liveness: 0.125 vs 1.0 at DECAY 0.1 / 0.3 / 0.5 / 0.7 / 0.9 | **≥ 0.50 at every DECAY**, and no two BLOOM settings byte-identical at any DECAY |
| **F** | Founding property on the raw oversampled buffer, every voice × {defaults, BLOOM 1, PEAK 1} | last-sample residual within the window's own step; worst adjacent jump **≤ shipped CICADA's** |
| **G** | Head-window DC, worst over PEAK, every voice at BODY 1 | **≤ 0.05** over the named PEAK range |
| **H** | One-period correlation at both BLOOM extremes, all six voices | **≥ 0.98** |

**A is the headline and B and C are the rows most likely to fail.** A is the measure the previous pass did not have: pairwise distance *at matched macros* is not distinctness, because a per-voice constant a player cannot reach is worthless if PEAK can reach the same place. Today's engine scores 0.276–0.417 on A — REED can already be dialled into PLATE, KAZOO and BOTTLE. An earlier pass on this engine shipped three voices that measured exactly as designed and sounded like one; A is the shape of measurement that would have caught it.

**If A fails on one row, re-spread that voice's split and re-measure the whole matrix as assigned.** Levers cancel: a measured two-lever combination scored 0.374 where one of its levers scored 0.970 alone. Never sum single-lever figures.

### What the audition must ask, because the measure is a proxy and the listener is the authority

**Clip design first, and it is not optional.** The last two auditions pinned TUNE = 0.5, where FOLLOW renders identically at 0 and 1. **Every clip set must include TUNE ≠ 0.5**, so all six macros are audible. Clips at matched pitch as well as at each voice's own root, so the listener hears the voices as timbres and not as registers.

1. **Blind A/B, same/different.** For each of the 15 pairs: "one instrument or two?" REED/PLATE and BOTTLE/KAZOO first — those are the two the listener named.
2. **The imitability rows, by ear.** Play the *worst* pair the A matrix found, at the macro settings that produced it. If the metric says 0.6 and the ear says "same," the metric is wrong and the split must widen.
3. **Reason to reach.** Per voice, in the listener's own words: what would you use this for that no other voice does?
4. **RATCHET's step rate.** Short DECAY against long. Is 33 steps/s a staircase or a purr? This settles `RATCHET_STEP_MIN_SECONDS`.
5. **The low-PEAK fragment.** Warped voices at PEAK 0.1–0.3, where neither region clears K_MIN's two-cycle premise. Is "a bright edge over a hollow" a character or a defect?
6. **PLATE's right to exist** — §9.
7. **CICADA's register** — §9, with the clip design that finally makes it judgeable.

## 8. What is deferred, and why

- **Deleting KAZOO and PLATE.** The strongest single argument in the source material: those two are the smallest distances in the engine (0.0894 to BOTTLE, 0.1272 to REED), and you cannot lose differentiation that never measured as present. It is deferred because the warp is what earns them their place — KAZOO becomes the two-co-loud-formant voice, the one independently measured as genuinely unreachable — and because deletion is the one move here that cannot be walked back on a metric that is deliberately blind to attack and envelope. If the §7 audition says otherwise, delete; that is §9's question.
- **A per-voice BODY table.** It measured well (cluster minimum 0.9097) but it puts voice identity on a macro the user can close — cluster minimum 0.0879 at BODY 0, worse than today — and needs `BODY_MIX` raised 3.4× to work. Its own author's conclusion is the reason to defer: *if BODY 0 is unacceptable, the right next move is a carrier-side lever.* This is the carrier-side lever. **Two findings from that work are recorded so they are not rediscovered:** a second source must anchor as a **multiple of kBase** (so it tracks PEAK instead of becoming a fixed pedal tone), and it must sit **below** the burst (a body placed above hides in the window's own skirts — measured 0.1506, inside the old cluster).
- **Window duty / support as a formant-Q axis.** The strongest deferred lever: the engine has formant *centre* (PEAK) and *amplitude* (BODY) but no *bandwidth* control at all, and a window that reaches zero at `d < 1` and stays zero satisfies the founding property *more* strongly, not less. Deferred because it interacts with everything here (it re-admits DC when the symmetry point moves off 0.5, and it needs a `k·duty ≥ 2` floor that does not exist yet) and because landing two new axes in one pass destroys attribution. Its own pass.
- **Per-voice `kCeilingFor`.** Rejected, not merely deferred. It rescales a knob rather than adding an axis: at matched kBase, renders under a per-voice ceiling table are numerically identical to today's, and REED/PLATE come out at exactly 0.0000 at BLOOM 0 and at DECAY 0.538. It also breaks `FOLLOW 1 rides the note and FOLLOW 0 stands still` (measured 3135.96 Hz against 3520 ± 70.4 at BOTTLE ceiling 32), and every in-band repair re-collides BOTTLE with a neighbour. A voice is not its default register.
- **Re-deriving `kCeilingFor` from the warp slope.** Tempting — `K_MAX / (subcycles · maxSlope)` turns CICADA's `if` into a formula — and rejected. It yields **8 for CICADA, not the shipped 10**, silently overwriting a register this branch just settled with measurements, and it re-reads `K_MAX` as an instantaneous-slope budget when its own KDoc says aliasing is not what bounds it. The warp's headroom is handled by bounding the skew (§3.2a), which leaves `kCeilingFor` and its three agreeing call sites exactly as they are.
- **Per-voice macro defaults** (SIREN's trick: a 20× rate spread and 3.6× depth spread is what separates *its* four voices). Declined deliberately. Defaults separate at one point and leave the voices identical at matched macros — they pass measure D and dodge its intent, and they would fail measure A outright.
- **`BODY_RATIO_DIVISOR` is a dead parameter and stays dead this pass.** `bodyRatio` clamps to `K_MIN`, so at the default kBase 8 divisors 4, 5.6 and 8 render **byte-identically**. Recorded as a known defect for the BODY pass; un-pinning it needs a divisor below 4 *and* a higher BODY default, which is that pass's business.
- **The PEAK ≥ 0.9 corner.** Pre-existing and out of scope. Every voice's kBase converges on its ceiling there and BLOOM is inert on top of it (`BLOOM_MAX`'s own KDoc records this), so the window is all that is left: today's six measure 0.0093 at PEAK 0.9 and **0.0000 — byte-identical REED/PLATE — at PEAK 1.0.** The warp improves it (the pair's split survives where the centre cannot move) but does not cure it. A real fix means de-equalising the top or giving every voice BLOOM headroom above its PEAK ceiling; a separate change.
- **TRACE, and it now needs an explicit ruling.** It stays after D3, unchanged. But this pass *changes its standing*: Buchla's objection was that a user-supplied window "adds new content to the same single mechanism — infinite costumes on the same one-note actor," and that objection was correct while the window was the only axis. Under this spec the window is no longer the actor: it says *when* the cycle is loud, and the warp says what happens inside it. A captured window over a warped carrier is a genuinely different proposition, which is exactly the condition Buchla named for TRACE being worth building. The doctrine here does **not** make TRACE illegal, and the roadmap row should say so.

## 9. Open questions — for a listener, not a test

1. **Is CICADA's two-octave transposition its character or a defect?** CICADA's four sub-cycles make its heard fundamental ≈4× its `rootHz`, so it sounds about two octaves above the note it plays. The last audition could not judge it, because **no other voice can play the note CICADA plays** — there was nothing to compare it against. This audition must fix that: render CICADA at TUNE 0.0 alongside every other voice at the TUNE that puts them on CICADA's *heard* pitch, so the listener hears the same note from six voices for the first time. Then ask: is the transposition part of what makes CICADA a cicada, or is it a voice whose TUNE knob lies? If it is a defect, the fix is `rootHz(CICADA) = 55f` and a re-measured register — cheap, but it invalidates every recorded CICADA number in the file, so it must be settled before implementation, not after.
2. **Does PLATE earn its place, or is it REED with a different attack?** §5 makes PLATE structurally distinct and §7 will measure it, but the deletion case is real and the metric is blind to attack. Render the four survivors alongside PLATE at the audition's own settings and ask directly. This is the one decision here that cannot be walked back.
3. **Same question for KAZOO against BOTTLE** — the pair the listener actually named. The warp makes them a two-co-loud-formant voice and a single-formant voice on the same split family. If the valley between KAZOO's two regions is not audible, KAZOO is the next deletion candidate and BOTTLE inherits the trapezoid.
4. **Is "a bright region over a hollow" a formant or a buzz?** At low PEAK neither warped region clears K_MIN's two-cycle premise. The listener decides whether that is character (a reed's rasp, a kazoo's membrane) or the "dull fragment" K_MIN exists to forbid. If it is the latter, the skews cap near 1.85 and the splits narrow — which costs real separation and may reopen row B.
5. **Does PEAK still feel like "the position of the peak"?** It now positions a *pair's* geometric centre, and the loud region the ear tracks sits at `k·S`, off the harmonic grid for irrational skews. The harmonic snap was one of only two things the original audition marked KEEP. The skews in §3.3 are rational with small denominators partly for this reason — `k·S` lands exactly on a harmonic for suitable `k` — but whether the snap still *feels* like harmonic landing is a listening question, not an arithmetic one.
6. **Does BOTTLE's fast-middle direction read as a bottle?** It is the one voice whose warp direction is forced by the DC rule rather than chosen by ear, and it is the identified weak case. If it reads as "REED a fifth down," the fallback is to widen its split toward the A3 slope bound of 2.0 and accept a brighter BOTTLE.
7. **RATCHET's step rate across DECAY.** 33/s at the shortest note, 7/s at the longest. Is the short end still a ratchet?

## Phasing

| phase | ships | gate |
|---|---|---|
| **D4a** | RATCHET's stride + the t60-proportional step, alone | a short audition: is the knob alive, and is the step rate right at both DECAY extremes? |
| **D4b** | the warp on REED, BOTTLE, KAZOO, PLATE; the `subcycles`/`skew` seam; the rewritten guards | the full §7 audition. Gates D3's preset roster. |

D4a first and separately, because it is a measured bug fix with no taste component and it must not be entangled with a character change. The previous pass landed three mechanisms at once and the audition could not attribute a single verdict to a single change. That is the lesson this phasing exists to honour.

## Honest assessment of cost and risk

**The right answer is smaller than most of the proposals it draws on, and deliberately.** No voice is deleted. `windowAt`, `kCeilingFor`, `macrosFor`, `bodyRatio` and every BODY constant are untouched. Two function signatures gain default arguments and one gains a new local. That is what keeps the three PLATE tests, the window-pairing test, the four JSON tests and both audition generators intact — the single largest cost in every alternative.

**The separation estimates in the source material are probably optimistic, and one is demonstrably so.** Three reconstructions of this engine calibrated to the same anchors reported the same lever at 0.579–1.650 (warp-vs-warp), 1.19–1.83 and 1.637–1.835. That spread is the uncertainty, and the low end of it is about one PEAK notch. Separately, one judge showed that a warp under the *triangle* was 72% recoverable by moving PEAK one notch — which is why §7's headline measure is imitability rather than matched-macro distance, and why BOTTLE is named as the weak row before anyone measures it. **Assume rows B and C will need a re-spread.**

**The two things most likely to stop this pass cold**, in order: `the formant sweeps and the pitch does not move` has 0.0024 of margin on PLATE and a warped spectrum decorrelates faster — measure it before writing anything else. And `BODY carries a small, bounded DC`'s single-point measurement hides a 1.47× variation across PEAK that the shipped engine already fails at PEAK 0; strengthening that guard is the right thing to do and it will surface an existing problem.

**What this pass does not fix:** the PEAK ≥ 0.9 corner, `BODY_RATIO_DIVISOR`'s dead zone, the absence of a bandwidth control, and BODY's overall quietness. Those are named in §8 with the order to take them in.