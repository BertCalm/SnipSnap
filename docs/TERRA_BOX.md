# TERRA BOX: a cajón, struck

BOX is TERRA's fifth voice (2026-11-06), built for one pad: the TERRA kit's
Cajon Slap (A07). The code is `TerraBox.kt`; `Terra.kt` routes the voice to it.

The characteristic gesture: **the palm slaps the front plate's top edge →
the plate's dense modes ring (the wooden crack) → the port's air answers
(the boom) → the wires buzz while the plate still moves → silence.**

## Why a voice of its own

The slap was rebuilt twice in one day, and both versions failed when the
owner listened:

1. As shipped, A07 was a TUNED_BAR, the balafon's own mode table at a lower
   pitch. Heard at the strike gate: "Cajon and balafon sound very similar.
   Cajon doesn't have the bassy resonance."
2. Rebuilt on RESONANT_CAVITY (a 220 Hz plate struck hard near the edge
   over the port's resonance), it had the bass but was heard as "too much
   like a drum machine rimshot".

The reason is structural. Every other TERRA voice is four to six pure sine
modes, with the strike pulse mixed in beside them rather than driving them.
That is how a drum machine builds a rim. Measured as spectral flatness over
the first 40 ms (0 is a pure tone, 1 is white noise), the cavity slap reads
0.058, the most tonal attack on the strike gate. A real slap is closer to
noise: dozens of plate modes, a hand's grit and the wires.

## The model

Literal, from physics estimates, then shaped by design:

- **The tapa (front plate):** 3 mm birch ply, 0.30 by 0.47 m, simply
  supported. Mode (m, n) sits at `((m/a)^2 + (n/b)^2) / ((1/a)^2 + (1/b)^2)`
  times the (1, 1) mode. From E 8 GPa, ν 0.3 and ρ 680 kg/m³, the (1, 1)
  mode estimates at about 76 Hz (TUNE's default). Every mode under 9 kHz is
  a two-pole resonator driven by the slap: 98 modes at the default. Each is
  weighted by the strike point's mode shape, `|sin(m π x) sin(n π y)|`, with
  x centred across (so even m are silent) and y = 0.92 up the plate (POS).
  Each decays as `T60 = 6.91 / (π f η)`, with the wood's loss factor η set
  by DECAY (0.045 dead, 0.022 default, 0.010 live) and T60 held to
  15–350 ms.
- **The port:** a Helmholtz mode at 85 Hz (a 42 L box, a 12 cm port in a
  1 cm back), T60 0.28 s. It is driven by the plate's motion through a
  250 Hz low-pass, standing in for the plate's net volume velocity, not by
  a filter on the output. CAVITY is how much of it is heard.
- **The slap:** a half-sine force pulse, 1 ms at the default FORCE (3 ms
  soft to 0.5 ms sharp), plus 4 ms of Hann-windowed skin-on-wood grit.

Analogy, not physics: the wires are high-passed noise (1.8 kHz), gated by
how far the plate's motion envelope (a 60 Hz low-pass of |plate|) rises
above 12% of its peak. BUZZ sets their level.

Not simulated: the air's push back on the plate, the frame's edge contact,
and the hand resting on the plate after the slap.

## Velocity and takes

A softer hit is the slap's own contact law. The pulse widens by up to 2 ms,
the grit falls as v^1.5, the port is driven in proportion to v, and the
wires' gate sees the envelope scaled by v, so a soft hit barely sets them
off. BOX does not apply `StrikeVelocity.modeFactors`: the wider pulse
already takes energy out of the plate's upper modes, which is the tilt the
other voices apply as a filter, and doing both would count the contact
twice. Re-struck takes (`Restrike`) re-seed the grit and the wires, nudge
POS and soften the hit, as on every TERRA voice.

## HIT on the box

HIT (another pad's first 20 ms colouring how hard each mode rings) reaches
the plate's modes through the shared `Terra.Body` and `Terra.hitLevel`.
Under HIT, the port and the wires are scaled by the unstruck box's own
levels, not their own peaks. Otherwise the per-stage normalising bent the
colouring: a BEATBOX KICK head moved the overtone balance from -5.0 dB to
+6.8 at HIT .25, then back to +5.7 at .5. With the fix it reads +7.2 then
+8.0. An unstruck box is byte-identical either way.

HIT is much stronger on the box than on the four voices Phase 0 measured.
Those voices' modes outlive the 20 ms head; the plate's die in as little as
15 ms. So the head plays into the plate, and its projection gives the top
modes gains in the thousands. At HIT .5, a dark head leaves the slap's first
5 ms at about 0.2 of the peak (the other voices keep 1.0), and FACTORY KICK
turns the wire-less box into a KICK. `TerraTest` holds the box only to
direction: a bright head brightens it and a dark one darkens it, by more
than 3 dB at HIT 1.

Heard and kept (2026-11-06): the owner chose today's HIT over a gentler
one. Two ways to tame it were measured over the ten strikers, as a
prototype in test code, never shipped:

- **A cap on each mode's gain** was dropped before listening. It kills a
  dark head's darkening: under a cap of 4, FACTORY KICK at HIT 1 left the
  box 1.2 dB brighter instead of 13.7 dB darker.
- **Each gain to the power 1/3** (a third of its dB, boost and cut alike)
  kept the direction: FACTORY KICK -4.4 dB and FACTORY SNARE +3.4 dB at HIT
  1, against -13.7 and +9.8 today. At HIT .5 the first 5 ms stayed at 0.91
  of the peak or more, and no class changed. This was the GENTLER clip on
  the second cajón page (https://claude.ai/artifact/MQWKxRLNj4A4r4pHX64pxo),
  which played FACTORY KICK and SNARE at HIT 1 and FACTORY CLAP at HIT .5,
  each as is and gentler. Asked "today's HIT or gentler?", the owner
  answered "As is".

## The output stage

BOX skips TERRA's fixed PUNCH and goes straight through the house stage:
band-limit, decimate from 176.4 kHz, subtract the mean, then a 20 Hz
one-pole high-pass, peak-normalise, fade the tail. The prototype the owner
picked had no PUNCH.

## The port, measured against the prototype

The owner picked variant C of a Python prototype (the Luthier harness,
twice A's air). Below, the Kotlin BOX at its defaults against that clip.
Flatness is over the first 40 ms; the bass share is the fraction of the
first 300 ms below 150 Hz; the centroid is magnitude-weighted over the first
30 ms.

| | Flatness | Below 150 Hz | Centroid |
|---|---|---|---|
| Prototype C (picked) | 0.49 | 75% | 4908 Hz |
| Kotlin BOX, the kit pad | 0.57 | 78% | 5259 Hz |
| The cavity slap (the rimshot) | 0.058 | 55% | 857 Hz |

The two noise generators differ: the harness's is a 64-bit LCG and
`Dsp.Noise` a 31-bit one. So the Kotlin render is the same model, not the
same bytes.

Velocity (Kotlin, first 30 ms centroid): 2566 Hz at v = 0.25, 3096 at 0.35,
4443 at 0.65, 5259 at 1. Brighter at every step.

The Classifier hears the kit pad as SNARE (high band 0.54 against the 0.5
line; flatness 0.50 against 0.25), because the wires lift the high band
past half. A cajón's slap is its backbeat, so A07 is now declared SNARE.
The cavity slap was declared PERC. With BUZZ at 0 the box reads PERC.

## Ledger

- **Heard (2026-11-06, the owner, through the cajón prototype page; each
  verdict covers only the levelled clips its question played):**
  - Prototype C, "most like a real cajón slap". Against the cavity slap, the
    rimshot is "Gone". Both answers were for the Python clip.
  - The Kotlin port, the shipped A07 at full velocity, against prototype C:
    "Same drum".
  - Its soft and medium hits (v = 0.25 and 0.6) against the hard one, a
    softer hand on the same box: "Soft hits sound right" (answered in
    conversation, not on the page).
- **Heard (2026-11-06, the owner, through the second cajón page,
  https://claude.ai/artifact/MQWKxRLNj4A4r4pHX64pxo):**
  - A fast roll, eight hits 160 ms apart, the pad's one recording against
    four takes (the pad, then three re-struck by `Restrike` with Robin's
    level and start jitter): "New takes" sound most like one drummer.
  - HIT on the box, today's against the gentler prototype above: "As is".
- **Measured:** the table above, Kotlin, at the commit this lands in.
- **Proposed (design shapes):** the 12% wire threshold, the 250 Hz drive and
  60 Hz envelope corners, FORCE's 3 to 0.5 ms span, the 2 ms soft widening,
  the grit's 0.35 and the wires' 0.5 mix.
- **Frozen guards:** `TerraFrozenTest` holds the four original voices to
  `LegacyTerraBank`, sample for sample. The frozen grid keeps the cavity
  slap in A07's place (`TerraCases.RETIRED_A07`), so it stays forty cases.
  The shipped A07 is pinned by its recipe text and its own voice.
