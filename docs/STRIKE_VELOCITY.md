# Strike velocity and re-strike: the modal drums played harder and again

A Luthier gap scan (2026-11-04, repo at `c074935c7`) found that SKIN and
TERRA, the two modal drum engines, had no velocity of their own. A softer
pad hit fell back to `Velocity.soften`, a low-pass over one finished
render, or to nudging a macro. The kits shipped one velocity per pad, and
Robin's round-robin takes were the same take resampled a few cents sharp
or flat. This round gives both engines a strike velocity, layers their
kits, and lets Robin hit them again.

The characteristic gesture: **harder hit → shorter contact → more upper
modes and a deeper pitch drop → the same drum body.**

## What a velocity changes (`StrikeVelocity.kt`)

| Consequence | Law | Where |
|---|---|---|
| Contact | each mode weighted by `H(r) = 1 / (1 + (r / (R v^a))^2)` against the root, `R = 2.5`, `a = 0.8`; the set rescaled so the modes' summed gain holds | `modeFactors` |
| Tension | the pitch drop (SKIN DROP, TERRA DROOP) by `v^2` | `droopFactor` |
| Strike | the contact pulse's grit by `v` (TERRA); a beater click by `sqrt(v)` in level and `v^0.8` in its low-pass corner (SKIN) | `gritFactor`, `pulseLevel`, `cornerFactor` |
| Level-reactive stages | TERRA's buzz gate and cavity soft clip, and THUMP KICK's DRIVE, see the hit's level `v` | `levelSeen` |

Every factor is exactly 1 at full velocity, so `v = 1` is each engine's
existing render, bit for bit: `TerraFrozenTest` still passes against its
frozen copy, and `StrikeVelocityTest` pins every SKIN voice and preset (168
renders) against hashes taken before the change. The factory sound is the
hard hit; the softer layers are new.

Which voices read it: SKIN's KICK and TOM (`Skin.STRUCK`), all four
TERRA topologies, and THUMP's and ONSET's kicks (2026-11-07, below).
TERRA's BOX (2026-11-06, [TERRA_BOX.md](TERRA_BOX.md))
takes the velocity through its slap's own pulse width instead of the mode
tilt. SKIN's snare, hats, ride and shaker are noise through
filters or wires, which a contact law does not describe; they keep the
touch `Velocity` already gives them. SKIN's STICK keeps `soften()` too:
every factory STICK is one partial, so there is no balance to move.

`R` and `a` are design shapes, first tried in a Python prototype (the
Luthier harness), not measurements of any drum.

## THUMP's and ONSET's kicks (2026-11-07)

The same law, on the two kicks that still took `soften()`:

- **THUMP KICK:** the sweep's depth above the base by `v^2`; the click by
  `sqrt(v)` in level and `v^0.8` in its 1 kHz low-pass corner; DRIVE's
  saturator sees the hit's level (a soft hit is driven less and scaled
  back after). The DRIVE macro itself never moves: it was measured
  non-monotone in brightness and kept out of `Velocity`'s brightness
  macros.
- **ONSET KICK:** its THUMP source as above. Its membrane's six modes tilt
  toward the root (`modeFactors`, summed level held), and its shared pitch
  bend by `v^2`. Its 4 ms strike click by `sqrt(v)` and `v^0.8`. A model-1
  pad's SKIN source takes SKIN's own kick law.
- PUNCH and ONSET's fixed saturator stay out of it, as on SKIN. ONSET's
  other voices and THUMP's keep their brightness macro or `soften()`.

`StrikeVelocityTest` pins THUMP's 21 kicks (default and twenty presets)
and ONSET's 8 (default, three presets, four kit blends) at each of its
three models, against hashes taken before the change (`22a6b4005`).

Measured, magnitude centroid over 30 ms, v = 0.25 to 1:

| Cases | Rise |
|---|---|
| ONSET kicks, from 0 ms, every model | +22% to +37%, at every step |
| THUMP kicks, from 2.5 ms | +52% or more, at every step |
| THUMP kicks, from 0 ms | not monotone: 18 of 21 read brighter at 0.25 than 0.5 |

**Disclosed, not fixed: PUNCH's onset click.** The THUMP reading from
0 ms is PUNCH, not the law. PUNCH saturates the first 2 ms of every hit
(`Punch.saturate`, a window of 4 x 0.5 ms), tapering its drive amount to
exactly 0 at the window's end. But `Dsp.drive(x, a)` is
`tanh(x (1 + 6a)) / tanh(1 + 6a)`, which at an amount just above 0 is
`tanh(x) / tanh(1)`, about 1.31x for a small sample, not `x`. So for any
PUNCH above 0 the first 2 ms sit about 2.4 dB above the rest, and that
step is a fixed click on every hit. On a soft kick, with the sweep and the
beater click softened, it is the brightest thing left (default kick,
from 0 ms: 86 / 80 / 81 / 103 Hz at v = 0.25 / 0.5 / 0.75 / 1, against
56 / 65 / 77 / 103 with PUNCH 0). Any PUNCH above 0, even 0.001, gives the
same 86 Hz. It predates velocity, and every THUMP, SKIN, ONSET, TERRA and
VOX render runs through `Punch.saturate`, so fixing it moves the whole
factory set.

**Tried and heard, 2026-11-08: left as it is.** A fix was built and
measured, then played to the owner beside the app as it is. It ran the
drive at its full onset amount and let the taper mix it out
(`x + wet * (drive(x, onsetAmount) - x)`, with `wet` falling from 1 to
exactly 0), so the window meets the untouched signal at gain 1. A new
`PunchTest` case failed on the old code (gain 1.313 one sample before the
window's end at PUNCH 0.001) and passed on the fix. Measured on the app's
renders, the first 2 ms came down 0.3 to 1.6 dB and nothing after 6 ms
moved by more than 2% of the peak. The one exception was the SKIN snare,
whose body rose 1.0 dB as PUNCH's loudness match made up for the quieter
start. The default kick's centroid from 0 ms became 56 / 65 / 77 / 103 Hz,
the PUNCH 0 law.

The owner heard four kicks, two snares, a clap and the udu, each before
and after at one loudness
(https://claude.ai/artifact/RoAw2pXmXXU4vKK1uPvVzS): "No difference" on
both. The full suite then showed what the fix would cost (6,033 tests, 10
failing):

- **Exact-render pins (five tests).** THUMP, ONSET and SKIN at full
  velocity, ECHO's kick, and ONSET's frozen legacy models, which exist so
  saved pads never change.
- **Behaviour (five tests).**
  - THUMP's longest kick (DECAY 0.8, HOLD 1) classified as TONAL.
  - FELT BOOM brightened with velocity: arguably right, but against its
    pinned exception.
  - The VALVE and FORGE neutral points moved the kick 0.17 dB, against a
    0.12 dB bound.
  - The driven treatment left DC on the snare.

A change nobody hears does not pay for moving every saved pad, so the click
stays. Velocity's brightness is still read from 2.5 ms on (above).

## What was tried and dropped, measured

Brightness below is the magnitude-weighted spectral centroid of the first
30 ms, Hann-windowed and zero-padded to 16384. `FeatureExtractor`'s own
centroid is power-weighted, and on a kick the fundamental outvotes every
overtone: SKIN KICK's power centroid moves 4% across the whole velocity
range while its magnitude centroid moves 27%. The gap scan's first claim,
that `soften()` left kicks unchanged, was an artifact of that metric: on
the magnitude centroid `soften()` moves SKIN KICK's onset by 73%. Its fault
is what it cannot move (the bend, the low modes' balance, the strike), not
that it moves nothing.

- **Turning the strike pulse down by `sqrt(v)`** fixed low drums in Python
  and broke high ones in Kotlin. TERRA's palm pulse is a smooth thump below
  about 300 Hz, darker than a 440 Hz head, so the hardest hit on Tabla Tin,
  Dumbek Tek and Djembe Slap read darker than a medium one (Dumbek Tek
  1801 Hz at v = 0.5, 1622 Hz at v = 1).
- **Scaling FORCE by the velocity** inherited FORCE's own shape: it is not
  monotone in brightness on the rim pads (Djembe Slap 2292 Hz at FORCE
  0.475, 1630 Hz at its own 0.95). Velocity leaves FORCE alone.
- **Lengthening the contact pulse on a soft hit** made soft membrane hits
  brighter, because a longer pulse carries more energy and against a low
  drum that energy is bright (default membrane 780 Hz at v = 0.25, 601 Hz
  at v = 1).
- **Handing SKIN's PUNCH the hit's level** ran backwards: every factory
  KICK came out brighter soft than hard (default 175 Hz at v = 0.25, 123 Hz
  at v = 1). PUNCH is the designer's shaping stage and stays out of it.
- **Holding the modes' summed gain** is a design choice, not a measured
  fix: reverting it to a root-only hold fails only the two summed-gain
  checks, not the brightness ones.

## What it measures now (`StrikeVelocityTest`)

First 30 ms centroid, v = 0.25 to 1, rising at every step:

| Cases | Rise |
|---|---|
| TERRA membranes and cavities (defaults and kit pads) | +50% to +185% |
| TERRA TUNED_BAR default | +57% |
| TERRA CONICAL_BELL (default and the three agogô pads) | +8% to +13% |
| TERRA buzz pads (Cajon Low, Balafon Key) | +290% to +740% (the buzz now waits for the hit) |
| TERRA BOX (Cajon Slap since 2026-11-06, its own contact law) | +105% |
| SKIN KICK presets | +25% to +29% |
| SKIN TOM presets | +5% to +8% |

Three SKIN presets do not brighten, and the test names them: FELT BOOM (a
felt beater on a sub, no CLICK, TONE 0.15: its velocity is its bend), and
FAST TOM and RUSH DRUM (PUNCH 0.88 and 0.68, no CLICK or DROP, whose
saturator rebuilds what the tilt takes out). They stay within 5%.

The root survives a soft hit within 25 cents wherever the detector is
confident, read after the bend settles (a hard BEND TOM reads 219 cents
sharp in the first quarter second because it is still bending; that is the
bend, not a moved root).

## The kits

`SkinKits.classic()` and `TerraKits.classic()` now carry two softer takes
under every pad (`Velocity.variantsAt`, velocities 1/3 and 2/3), so an MPC
kit switches sound with how hard a pad is hit, three zones. TERRA's main
take is unchanged (`TerraFrozenTest` pins it pad for pad).

## Re-strike (`Restrike.kt`, `Robin.kt`)

A round-robin chain cut from one recorded take can only nudge its level,
start and pitch, and Robin's pitch nudge (up to 10 cents) detunes the whole
drum. A modal-drum synth pad is now hit again for each take:

- up to 15% softer (`FORCE_SPREAD`), through the velocity law above;
- on TERRA, up to ±0.04 off its strike position (`POSITION_SPREAD` on POS);
- with its own click or grit: `take` on `Skin.render` and `Terra.render`
  re-seeds the strike's noise. Take 0 is today's seeds.

Robin keeps its level and start jitter on these takes and drops the
repitch. Its random draws are made in the same order either way, so every
pad it cannot re-strike gets byte-identical takes. A take never touches
TUNE, so the drum's fundamental is the same on every take by construction;
`RestrikeTest` reads it within 25 cents of take 0 on the detector, which at
around 100 Hz moves by up to about 15 cents with the modes' balance alone
(Dholak Bass, 12.6 cents between two takes at one fundamental).
`RobinRestrikeTest` holds each take to the drum's own length, less Robin's
start jitter. Both spreads are shapes.

## Ledger

- **Verified:** `StrikeVelocityTest`, `RestrikeTest`, `RobinRestrikeTest`,
  `TerraFrozenTest`, `./gradlew --no-daemon test`; the guards were reverted one at a time (the summed-gain
  hold fails its two structural checks; the buzz gate's level fails the
  brightness test on Cajon Low).
- **Measured:** the tables above, Kotlin, at the commit this lands in.
- **Proposed:** `R`, `a`, `FORCE_SPREAD`, `POSITION_SPREAD`.
- **Heard (2026-11-06, the owner, through the gate below; each verdict
  covers only the levelled clips its question played):**
  - The soft SKIN kick (TAPE SKIN, old soft against new soft, medium,
    hard): "Same to me." The new law is no audible step for this kick.
  - Soft hand drums (Djembe Bass, Tabla Tun): "New is better."
  - A soft hit bending less (BEND TOM, Bayan Drag): "Natural."
  - The buzz waiting for a firm hit (Cajon Slap, Balafon Key): "Right."
    Note: "Cajon and balafon sound very similar. Cajon doesn't have the
    bassy resonance." Read from the kit, not heard: Cajon Slap (TERRA kit
    A07) is a TUNED_BAR voice like Balafon Key (A16), so the two share the
    bar's mode table and differ mainly in pitch; no box resonance sits
    under the slap. Rebuilt the same day, at the owner's yes: A07 is now a
    RESONANT_CAVITY voice, its 220 Hz front plate struck hard near the edge
    (FORCE 0.9, POS 0.9, DROOP 0) over the port's resonance (CAVITY 0.8),
    the wires kept (BUZZ 0.75, so the HIT audition still names it the BUZZ
    pad), a shorter ring (DECAY 0.35). Measured in Kotlin: 55% of its first
    300 ms below 150 Hz (0% before), 39.5 dB RMS from Balafon Key over
    third-octave bands 60 Hz to 8 kHz (21.3 before), and still classified
    PERC; plates tuned to 150 or 180 Hz classified TOM or KICK and were
    passed over. `TerraCases.KIT` and the literal A07 pin in
    `TerraFrozenTest` follow it. Heard (2026-11-06, the owner, after the
    gate's question 8, old and new slap beside Balafon Key and Cajon Low):
    "Cajon is still not right. Too much like a drum machine rimshot." Read
    from the code, not heard: every TERRA voice is four to six pure sine
    modes with the strike pulse mixed beside them, the shape of a drum
    machine's rim; the cavity voice adds the port's low end under that
    crack but not a wooden one. A real tapa has dense plate modes driven by
    the slap, the port's air mode driven by the plate, and wires buzzing in
    noise. Measured: the cavity slap's first 40 ms are the most tonal attack
    on the gate (spectral flatness 0.058; the old bar slap 0.40, Cajon Low
    0.44). A Python prototype of that box (97 plate modes for a 3 mm birch
    tapa, 0.30 by 0.47 m, driven by the slap; an 85 Hz Helmholtz mode driven
    by the plate; wires as noise gated by the plate's motion) reaches 0.49
    at the same bass share (54% below 150 Hz), and 0.28 on a soft hit. Heard
    the same day on the prototype page
    (https://claude.ai/artifact/8FteYwEt1FLXDz6CioA65V): variant C, twice
    the port's air, is "most like a real cajon slap", and against the cavity
    slap the rimshot is "Gone". A07 is now TERRA's BOX voice, ported from C
    ([TERRA_BOX.md](TERRA_BOX.md)); the gate's CAJON section plays all three
    slaps.
  - A fast roll (TAPE SKIN kick, Djembe Open): "New takes." The note said
    the one-take reference ("same takes") was unclear; the gate now names
    it NO ROUND ROBIN.
- **The gate (2026-11-06):** `./gradlew :synth:generateStrikeAudition`
  (`StrikeAuditionGenerator`) renders 127 clips at one loudness under
  `testkit/strike-audition/`: every SKIN kick, tom and TERRA kit pad as its
  old soft hit, then the new soft, medium and hard; fast rolls with one take,
  Robin's old re-pitched takes and the new re-struck ones; OBBLIGATO keys held
  ten seconds; CHORD STABS progressions. Seven questions, published as the
  listening artifact at https://claude.ai/artifact/KSRzJCMRVCYTdQ9uKFgtf3,
  whose answers save to its `answers` collection.
- **Disclosed, not fixed:** TERRA's FORCE is not monotone in onset
  brightness on the rim pads (above), and the owner hears it too. The
  cause, measured 2026-11-07: FORCE shortens the palm pulse (12 ms to 3 ms)
  at a fixed peak, so a harder hand lands less momentum, and its grit
  (loudness times length) peaks near FORCE 0.89. On every hand-struck voice
  the first-30-ms centroid rises to about FORCE 0.5 and falls after; from
  2.5 ms on and before PUNCH it does the same. PUNCH's onset click (THUMP's and
  ONSET's kicks, above).
- **Heard (2026-11-07, the owner, through
  https://claude.ai/artifact/SuVE4Hed1heDrm4Fa1Gs7s, which plays each kick
  as the old filtered soft hit, then the new soft, medium and hard):**
  - A soft THUMP kick (DUSTY BOOM, FOUR FLOOR): "New is better".
  - A soft ONSET kick (HYBRID KICK, MEMBRANE KICK): "New is better".
  - The same page's hand-drum questions: Djembe Slap, Tabla Tin and Dumbek
    Tek each read as a "Drum machine", and TERRA's FORCE from 0.2 to 1.0 did
    not sound like a harder hand at every step ("No"). Their rebuild is
    separate work; FORCE's measured shape is above.
