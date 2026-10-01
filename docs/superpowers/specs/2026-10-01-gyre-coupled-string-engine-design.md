# GYRE — coupled strings and a rotor: an instrument whose strings play each other

**Status:** design review; not implemented. The owner's *Gyre Engine*
document (40 sections, received 2026-10-01) is read here against the tree
at `314b6c4`, the way ARCO read its brief: what the document gets right,
what the code already has, what has to change before a line of engine
code, and the rounds that build it. No engine code is committed. This
lands as a docs-only PR (zero check runs by design,
`.github/workflows/tests.yml` ignores `docs/**` and `**/*.md`), as BORE,
FORK and ARCO did.
**Date:** 2026-10-01
**Plan:** to be written per round (`docs/superpowers/plans/2026-10-01-gyre-round-N.md`)
**Related:**
[`2026-09-29-arco-bowed-string-engine-design.md`](2026-09-29-arco-bowed-string-engine-design.md)
is the sibling this engine depends on. ARCO's Phase-0 spike settled how a
bow is built on `Strings` in this tree, and GYRE's bow half is that bow.
[`2026-09-28-bore-woodwind-engine-design.md`](2026-09-28-bore-woodwind-engine-design.md)
added the `Loop.reflected()`/`inject()` junction hook GYRE's bridge uses,
and its "Data flow and compatibility" table (`:1120-1175`) is the
registration checklist GYRE follows.
[`2026-09-27-silk-string-engine-design.md`](2026-09-27-silk-string-engine-design.md)
built the `Strings` toolkit and SANTUR's WASH.
[`2026-09-26-pluck-sitar-design.md`](2026-09-26-pluck-sitar-design.md)
built the tarab. SIREN's spec settled HOLD and the LOOP top step.
**Roadmap:** the `SYNTH_ROADMAP.md` row is added when implementation
starts, not now. That is the rule every engine since FATHOM followed. The
highest row today is S19 (BORE). ARCO has a prior claim on the next number,
so GYRE's row is S21 or later.

## The verdict

**GYRE should be built, as its own engine, but not as written, and not
first.**

It earns a slot in a fleet of seventeen engines for three things nothing
in `:synth` does, or plans to do:

1. **Two-way coupling.** Every string, body and sympathetic bank in the
   tree is feed-forward. SITAR's tarab is "fed by the played string
   alone" (`Pluck.kt:248-256`). `Pluck.sympathetic` "couples each loop
   only to the played string, not to its neighbors" (`Pluck.kt:899-904`).
   `Strings.course` sums independent loops (`Strings.kt:766`).
   `Modes.ring` is buffer-in, buffer-out (`Modes.kt:85`), so it cannot
   sit inside a loop at all. ARCO's body is "never inside the loop"
   (ARCO `:934`). In GYRE, a struck string moves a shared bridge, and the
   bridge moves the strings nobody struck. That is new.
2. **A rotor inside the excitation.** TONEWHEEL has a rotor, but it is an
   output effect (`Tonewheel.kt:66-80`). SIREN's LFO moves an oscillator's
   pitch. GYRE's rotor changes where and how hard energy enters the
   strings, and how strongly they are coupled. The circular bow is the
   sharpest case: a contact sweeping across four strings. Nothing in the
   tree or in ARCO's plans does this. ARCO's "not doing" list has no entry
   for rotation, circular bowing or inter-string coupling.
3. **A continuous pluck-to-bow morph on one string.** PLUCK, SILK and
   MAGNET pluck. ARCO bows. Nobody does both on one string, so nobody can
   bow a string a pluck just set ringing (the document's §6).

Everything else in the document already exists or is planned, and GYRE
should reuse it rather than build it again (see "The reuse map").

**Not as written:** the friction law, the default string ratios, the
bridge feedback, the macro set and four of the six voice names all need
to change (see "The document, as reviewed"). The bow change is the
biggest. ARCO's spike measured that a bow cannot ride a single
`Strings.Loop` ring (ARCO `:490-519`), and the document's bow sits on one.

**Not first:** GYRE's bow is ARCO's `Strings.Bow`. That class was planned
for ARCO's round one and not built when this was written (it has since
landed: see "Decisions taken"). So GYRE is phased with the bow last
rather than first: its pluck side, bridge, sympathetic bank and rotor need
nothing from ARCO and can start now. The bow joins when ARCO's lands. See
"Phasing and gates".

## What the document gets right

It is written for this repository, and it shows:

- It names the house's real machinery: `Strings.Loop`, `Dsp.seedFor`,
  `Keys.seamError` and its 1e-3 bar (`Keys.kt:147`),
  `MELODIC_LOUDNESS_TARGET` (`Dsp.kt:525`), the 4x render
  (`Dsp.OVERSAMPLE`, `Dsp.kt:33`), `Patches`, `SynthKits`,
  `drumClassFor`, and the "every macro changes the sound" test.
- Its §40 principle ("a parameter represents an understandable physical
  interaction and produces several related sonic consequences") is the
  roadmap's playability rule 2, restated for physics.
- Its rounds (probe before bounds, identity before extremes, HOLD
  separately, presets last) are the house's order. Its acceptance rests on
  "the owner's listening verdict", which is the audition gate.
- Its non-goals rule out the two cheap fakes: "a Karplus-Strong pluck with
  chorus added afterward" and "an LFO applied to a finished string sound".
- It keeps instrument names off presets ("names should describe sound or
  behavior"), which is the right call for the sources it draws on (see
  G8).
- It insists every feedback path has an explicit bound and that no final
  limiter hides instability (§17). That is the right instinct; G3 makes it
  concrete.

## The document, as reviewed

Findings are numbered G1 to G10 so the rounds can cite them.

### G1. The bow cannot sit on one ring, and the friction law is the wrong kind

The document's bow (§5.2) is a force
`Δv · exp(−|Δv| · shape) · pressure` added to the primary string, which is
one `Strings.Loop` ring (§7).

ARCO's Phase-0 spike built every single-ring bow variant beside the
two-segment reference model (ARCO `:490-519`). Every single-ring variant
failed: it parked at DC, played an octave down, played 9-10 cents flat, or
sounded like a clarinet. The model that works is a string in two segments
either side of the bow (bridge side `tune(roundTrip = β)`, nut side
`tune(roundTrip = 1 − β)`), with a memoryless reflection table at the
junction (ARCO `:652-660`, `:793-808`). It stayed bounded in about 400
renders, in tune to a few cents, and produced a Helmholtz sawtooth.

The exponential law has a second problem. ARCO's brief proposed a friction
*force* added to a velocity *wave* with no impedance. ARCO measured that
this kind of law plays no note at all: it is a relay, 46% DC with no
period (ARCO `:157-197`). The document's law is the same kind: a force,
added, with no impedance. Its slope at Δv = 0 is `pressure`, so it would
also have to stay below 1 to keep the junction passive.

**Change:** GYRE's bow is ARCO's `Strings.Bow`, with its friction law and
bow junction unchanged. TOUCH, SPIN (G6) and velocity drive its inputs
(bow velocity, pressure, β). GYRE does not get its own friction law. One
bow in the tree, measured once. What GYRE does need is a way into the
bow's *bridge end*, which ARCO's design seals inside a `Loop` (see
"Where the bridge is, once there is a bow", under G3).

What this costs the document: ARCO found single-slip (clean) motion only
in patchy "islands" of pressure (ARCO `:424-440`). Some values of β are
holes, for example 0.2 gives two slips per period (ARCO `:442-451`). A
memoryless table never period-doubles, so there is no growl (ARCO
`:432-438`). The document's "scrape" and "bowed squeal" at extreme
pressure (§5.2, §17) may come for free from the multi-slip cells ARCO
avoids, or may not. That is a round-three measurement, not a promise.

### G2. The default ratios make the pitch detector read an octave low

The primary strings default to 1, 2, 1.5 and 0.5 times the note (§7). The
sympathetic bank defaults to 1/2, 1, 3/2, 2, 5/2, 3, 7/2 and 4 (§9).

A note `f` plus anything at `1.5f` or `0.5f` repeats every `2/f` seconds,
not every `1/f`. The house detector (`Pitch.detect`, `audio/.../Pitch.kt`)
picks the shortest lag scoring above 0.85 of the best. For `f` at level a
plus `1.5f` at level b, the true period scores `(a² − b²)/(a² + b²)` of the
best. That drops under 0.85 once b is within about 11 dB of a.

Measured with a Python copy of the detector's own rule, on steady sines at
220 Hz:

| Partials | Companions at −20 dB | −14 dB | −11 dB | −6 dB |
|---|---|---|---|---|
| 1, 1.5 | 220 | 220 | 220 | 110 |
| 1, 2, 1.5, 0.5 (the document's default) | 220 | 220 | **110** | **110** |
| 1, 2, 3, 4 | 220 | 220 | 220 | 220 |

The 1, 1.5 pair flips between −11 dB (220) and −8 dB (110). A real
string is not a steady sine, so the exact crossover will differ.
The direction will not. The document's "high SYMPATHY: a cloud of ringing
partials" puts the half-integer sympathetics exactly where this happens.
Every TUNE test, every keygroup root, and SPREAD all go through this
detector. The 0.5 string is also literally the octave below the note, so
a listener may hear it as the bass too.

**Change:** default to whole-number ratios: primary strings 1, 2, 3, 4,
sympathetics 1 to 8. Half-integer ratios become an opt-in per voice,
allowed only if that voice's pitch test passes with them at its own
SYMPATHY ceiling. A per-voice "octave guard" test (see "Testing") pins
this.

### G3. Bridge feedback needs a structural bound, not a tuned one

§8 sends every string into a modal membrane and the membrane back into
the strings. §17 says every feedback path needs "an explicit stable
bound". It does not say what the bound is, and the obvious one does not
work.

A string loop at feedback `fb` has a peak gain of about `1/(1 − fb)` at its
own partials: 100 at `fb` 0.99 and 1000 at 0.999. A scalar "membrane feeds
back c" is only guaranteed stable when c times that peak gain stays under
1. That makes c so small the coupling is inaudible. Tuning c by ear until
it stops blowing up gives a patch that is stable at the notes and
velocities someone tried, and fails at the one nobody tried.

**Change:** couple the strings the way a feedback delay network couples
its lines. At the bridge, each string's reflected wave (`Loop.reflected()`,
`Strings.kt:577`) passes through a coupling matrix `M` before it is
written back (`Loop.inject()`, `:635`). Keep `‖M‖ ≤ 1` at every sample,
for example

```
M(c) = I − (2c / N) · 1·1ᵀ      eigenvalues 1 and 1 − 2c, so ‖M‖ ≤ 1 for c ∈ [0, 1]
```

The matrix can move energy between strings but cannot add any. Each loop
keeps `fb < 1`, so the whole network decays, at any fixed c.

The membrane goes in the coupling path, in place of the scalar:
`M = I − (2c/N) · H_m · 1·1ᵀ`, where `H_m` is the membrane filter. The
bound then needs `|1 − 2c·H_m(ω)| ≤ 1` at every frequency, which is
`c·|H_m|² ≤ Re H_m`. **A peak gain of at most 1 is not enough.** A filter
that inverts (H = −1) has gain 1 and gives `|1 + 2c|`, which is 3 at c = 1.
What is enough is a membrane built as a weighted sum of `Dsp.Biquad.bandpass`
resonators (`Dsp.kt:374`, the RBJ constant-peak-gain bandpass) with
**non-negative** weights summing to at most 1. That bandpass has
`Re H = |H|²` exactly, and a sum with weights `wₖ ≥ 0`, `Σ wₖ ≤ 1` keeps
`Re H ≥ |H|²` (by Cauchy-Schwarz, which needs the weights non-negative), so
the bound holds for every c in [0, 1]. The sign constraint is not a
technicality: weights of +1 and −1 sum to 0 and break the bound, so a
mode cannot be given a negative gain to shape the membrane. Checked numerically: over 200 random
membranes (1-5 modes, 40 Hz-8 kHz, Q 0.5-200, at 176.4 kHz) and c from 0.25
to 1, the worst `|1 − 2c·H_m|` was 1.000 (weights drawn non-negative). R0
turns this into a unit test, and its random membranes include the sign
constraint: `GyrePatch` and the voice tables reject a negative weight.
**Measured since:** that check used ideal coefficients. `Dsp.Biquad` runs
Float ones, and at narrow modes those are not passive, so the membrane runs
its own bandpass in Double ("Round one's Phase-0 measurements", item 1).

While c moves with the rotor, the frequency argument above no longer
strictly applies. Moving c slowly through a set of states that are each
stable is safe in practice, but that is a measurement, not a proof. The
"bound holds" test (see "Testing") checks the fastest SPIN. If it fails,
the fallback is a soft limit on the bridge's motion (a bridge that can
only move so far), which §17 allows as "acoustically justified".

This keeps the document's "controlled instability" (§17). Near c = 1 and
`fb` near 1, energy sloshes between strings for a long time: beating,
bloom, cyclic build-up. It just cannot grow without bound. SYMPATHY's
"near-feedback string cloud" (§13.2) gets the same treatment if the
sympathetic bank returns energy (G9).

**Where the bridge is, once there is a bow.** In round one each string
is one ring, and its one junction can stand for the bridge. In ARCO's
bow it cannot. ARCO calls both segments' `reflected()` and `inject()` at
the *bow* (ARCO `:652-660`). The bridge reflection itself (the −0.95 loss
and the `BRIDGE_HZ` low-pass) happens inside the bridge-side `Loop`, with
no hook. So there is nowhere at the physical bridge to apply `M`.
Applying `M` at the bow instead would mix waves that left the bridge at
different times. That is a different, untested system, not the same
coupling moved.

**Change:** R2 starts by giving `Strings.Bow` a bridge port. The bridge
segment becomes an explicit out-and-back pair of one-way delays with a
junction at the far end, like the bow's, and the reflection there is a
pluggable step. Its default is ARCO's own reflection (−0.95 through the
`BRIDGE_HZ` low-pass), so ARCO's renders do not change, sample for
sample. That is proven the way BORE's and MAGNET's R0s were: a frozen grid
of ARCO renders before and after. GYRE plugs `M` into that step, across all
four strings at once. The tuning budget is unchanged in total: the two
one-way delays sum to the bridge segment's `β·T`, and the low-pass is
charged once, as now.

Cheapest if ARCO's round one builds `Strings.Bow` with the port from the
start, so this is raised with ARCO as a decision (see "Decisions for the
owner"). The alternative, coupling at the bow junction as an
approximation, is possible but would need its own design and its own
bound test before anything relies on it.

Two consequences:

- **The membrane needs a per-sample resonator, and one exists.**
  `Modes.ring` renders a whole buffer (`Modes.kt:85`) and cannot sit in a
  loop. `Dsp.Biquad` steps one sample at a time (`process`) and its
  `bandpass` has exactly the property the bound needs. The sympathetic
  bank can use it too, with Q set from each mode's t60. Note that ARCO
  warns against `Dsp.Biquad.bandpass` on its body (ARCO `:934-935`). That
  warning is about level (a differentiated drive at −54 dB), not
  stability, and does not apply here.
- **Coupling moves pitch.** Any filter in a loop adds phase, and phase is
  pitch. This is why ARCO keeps its body out of the loop (ARCO `:934`).
  GYRE breaks that rule on purpose, because coupling is its identity. It
  pays for it with a pitch test across BODY and SYMPATHY (see "Testing"),
  and, if that fails, a per-voice correction like ARCO's own pitch pin.

### G4. The rotor's top end does not reach the north star, and should follow the note

§13.3 maps the rotor from stationary to 40-60 Hz. §39's north star wants
"the rotation itself [to become] part of the pitch spectrum".

At 60 Hz against a 220 Hz note, the sidebands land at 160 and 280 Hz. That
is roughness, not a new pitch. It is also inharmonic, which matters for
keys: SnipSnap renders keygroups every minor third (`Keys`), and the MPC
stretches each zone up to about 1.5 semitones. A rotor fixed in Hz
relates differently to every zone's note, so neighbouring zones would not
match.

**Change:** split SPIN's range in two.

- **Below about 10 Hz, the rotor runs in Hz.** That is gesture: wheel,
  tremolo, rhythm. Hz is right here, because a 3 Hz pulse should be 3 Hz
  on every key.
- **Above that, the rotor moves toward the note, continuously.** The
  rate glides in log steps from the split to three times the note:
  `rate = split^(1 − u) · (3f)^u`, with u running 0 to 1 over the top of
  SPIN, outside the plateaus below. There is no jump anywhere on the knob, which the R3 gate
  ("no abrupt switch") requires.
- **Harmonic plateaus at whole-number ratios only: 1, 2 and 3 times the
  note.** SPIN holds the rate exactly there over a stretch of the knob, so
  presets and SCRAMBLE land on them easily. Modulating a partial `k·f` at
  a rate `m·f` puts sidebands at `(k ± m)·f`, which are harmonics. A
  fractional ratio does not: 1/2 gives half-integer sidebands (0.5f,
  1.5f), 3/2 gives half-integer ones too (0.5f, 2.5f), and 1/4 gives
  quarter-integer ones (0.75f, 1.25f). Those make the waveform repeat at
  `f/2` or lower, which is G2's octave error again. So fractional ratios
  are not plateaus. A voice may opt into one only if it passes G2's
  octave-guard test.
- **What this buys and what it does not.** On a plateau, sidebands are
  harmonic, the pitch stays readable, keygroup zones match, and the
  rotation becomes part of the pitch spectrum. Between plateaus, the
  sidebands are inharmonic and the rate partly tracks the note (as
  `f^u`), so neighbouring keygroup zones differ slightly. That is the
  honest "rough, impossible" region of the knob. Presets meant for a keys
  instrument sit below the split or on a plateau.

The circular bow's moving contact (§12) also moves β, the bow position.
A `Loop` cannot grow past the size it was built at (`Strings.kt:540-547`),
so both segments must be built at their longest. A swept β also passes
through ARCO's multi-slip holes and its high-note floor of
β ≥ (2.5 + τ)/T (ARCO `:784-792`). So the first version of the circular
bow should keep β fixed per string and rotate the *contact weight* only
(§12's `weight[n]`, applied to bow pressure). A moving β is a round-three
experiment.

### G5. HOLD should follow the house: exact loops, no crossfade

§20.4 allows "a short equal-power … crossfade". The house rule is the
opposite: one retry with a longer settle, "never a crossfade"
(`Keys.kt:253`; ARCO `:1190`). BORE defers a crossfaded wrap to its own
round two (`Bore.kt:151`).

**Change:** HOLD at its top step is a LOOP built the BORE way
(`Bore.kt:841-954`):

1. Plan whole periods in whole frames over about 2 seconds.
2. Choose the rotor's rate so a whole number of turns fits the same loop.
   The document already says this (§20.1). It is also what `Keys.kt:79-86`
   names as the fix it never made for the organ's rotor.
3. Set every per-string and sympathetic detune to zero, and every ratio to
   a whole number (G2 already does this).
4. Hold the bow steady: constant speed and pressure, vibrato and roughness
   off, as ARCO's LOOP does.
5. Re-render with pitch correction until the period count fits.
6. Measure the seam BORE's way, against itself one loop later
   (`Bore.kt:946-948`), at `Keys.MAX_SEAM_ERROR`.
7. If it fails after one retry, throw, naming the corner.

A crossfaded wrap stays available as an explicit, later decision if the
LOOP fuzz test finds corners that cannot close. It is not designed in.

### G6. Seven macros, and the engine's name on one of them

The document's macros are TOUCH, SYMPATHY, GYRE, TENSION, BODY and HOLD.
Every SnipSnap engine also has TUNE (semitones from the voice's root).
That makes seven, and playability rule 1 says "3-6 macros, never a
patchbay" (`SYNTH_ROADMAP.md`).

ARCO also set a naming rule: "an engine called BOW with a knob called BOW
reads twice on one LCD" (ARCO `:597-600`). GYRE has exactly that: an
engine called GYRE with a knob called GYRE.

**Proposed set (six):** TUNE · TOUCH · SYMPATHY · **SPIN** · BODY · HOLD.

- **SPIN** replaces the GYRE macro. It is a plain mechanism word. "SPIN"
  appears in the tree as an ordinary word but not as a macro or engine.
  WHIRL (zero hits) is the alternative.
- **TENSION becomes part of each voice** rather than a knob. The voice
  roster already spans it: WIRE is high tension, the low voice (G7) is
  low, and §15 gives every voice its own TENSION value. Its interactions
  (§16: TENSION × TOUCH, TENSION × SYMPATHY) still happen. They just
  happen between voices.

This is a decision for the owner (see "Decisions for the owner"). Keeping
TENSION as a seventh macro breaks rule 1. Dropping BODY instead would lose
the document's second-best idea.

### G7. Four voice names collide with things that already exist

| Document's voice | Collides with | Proposed |
|---|---|---|
| PLUCK | the PLUCK engine. "GYRE PLUCK" and "PLUCK HARP" in one picker, and the same word in `snipsnap synth PLUCK …` and `snipsnap synth GYRE PLUCK …` | **FLICK** |
| ARC | the ARCO engine, one letter apart | **DRAWN** (a drawn bow) |
| WHEEL | ARCO's own planned **WHEEL** voice, its hurdy-gurdy (ARCO `:1009-1028`) | **LATHE** (a turning machine; the contact sweeps round) |
| DRONE | the drone feature: `ResinDrone`, `SirenDrone`, `DroneBlock`, `--drone`, "DRONE TO LOOP ▸" | **BOURDON** (the generic organology term for a drone string) |
| HALO, WIRE | nothing | keep |

The proposed names are suggestions. The collisions are not.

Preset names follow ARCO's rules (ARCO `:625-644`): at most 14
characters, no engine name inside a preset name. Of the document's 16
preset concepts, "Sympathetic Cloud" is 17 characters and "Low Gyre"
names the engine. "Skin Wire" and "Loose Skin" contain the SKIN engine's
name, which is legal but will confuse search. Presets are written by ear
after the gate anyway, so this is a note for round five, not now.

### G8. The sources

The document draws on three instruments: the kora, the nyckelharpa and the
bullroarer. It already keeps them off presets. The bullroarer needs more
care than the other two. In many Aboriginal Australian communities it is
a sacred object whose sound and use are restricted. The house's naming
guard (`PresetTestSupport.trademarkBlocklist`) is about trademarks and has
no rule for this.

**Recommendation:** keep "bullroarer" off every product surface, including
preset descriptions, the audition page and the store listing. Describe the
mechanism ("a rotating field", "the rotor") in the code and docs instead.
Kora and nyckelharpa may appear in design notes as inspiration, as SITAR
and OUD do, but no voice is named after them. That is consistent with the
document's own non-goals.

### G9. Smaller notes

- **Sympathetic return.** §9 has the sympathetic bank receive energy from
  the strings, bridge and rotor. §13.2's "return gain" sends it back. In
  round one the bank listens only (one-way, like the tarab). Two-way return
  comes in round three, under G3's contraction rule. Two coupled networks
  are twice the pitch risk, and one-way already answers "does SYMPATHY
  sound like strings responding to strings".
- **Render time.** Playability rule 5 wants one-shots to render in tens of
  milliseconds. Eight seconds at 176.4 kHz is 1.4 million steps of 4
  segment pairs, 8 + 4 resonators and a rotor. That is fine offline. It is
  slow on a phone if HOLD adds a 1.5-second warm-up and five re-renders,
  as BORE's does. Round one measures the render time, and the duration
  logic (§21: stop when the energy has decayed) keeps one-shots short.
  ARCO's table removes the document's per-sample `exp()`.
- **The classifier.** The document wants "no drum classes". The house
  enforces the same thing per engine: none of KICK, SNARE, HAT_CLOSED,
  HAT_OPEN, CLAP or TOM (`BorePresetsTest.kt:39-64`). PERC, TONAL and LOOP
  are all fine. SILK's plucked voices read PERC by default, and FLICK
  probably will too. §23's risks (a short low pluck reading TOM, a bright
  WIRE attack reading a hat) are the right ones to watch.
- **No dead knob.** At SPIN = 0 the rotor is stationary, so SPIN's low end
  must still move something (§24). With G4's split, SPIN's first stretch
  should be a slow sweep of coupling and contact weight, not a rate
  starting from zero.

### G10. What the document leaves open, rightly

The document says its macro defaults and modal ratios are "starting
hypotheses only" (§15). It defers final numbers to probes and the listening
gate (§31). That is correct, and this review does not invent numbers to
replace them. Every constant a round sets is labelled "shape" until a
measurement or a listen backs it, as ARCO does.

## The reuse map

| GYRE needs | Already in the tree | Planned by ARCO | New in GYRE |
|---|---|---|---|
| Pluck exciter | `Strings.pluckExciter`, `mallet`, the `Exciter` type (`Strings.kt:31`, `:362`, `:478`), `combDelay` and `pickup` (`:371`, `:397`, MAGNET R0) | | |
| String loop with a junction hook | `Strings.Loop` with `reflected()`/`inject()`, fractional `tune(roundTrip)`, `retune`, `gain` (`Strings.kt:523-695`, BORE R0) | | |
| Bow | | `Strings.Bow`: two segments, STK reflection table, pitch pin (ARCO R1, `:675-826`) | |
| Stiffness for TENSION | `Strings.Dispersion` (`:274`) | | |
| Coupled bridge | | | the `‖M‖ ≤ 1` coupling junction (G3) |
| Membrane | the RBJ bandpass, in Double (`Dsp.Biquad.bandpass`'s Float coefficients are not passive at narrow modes; see "Round one's Phase-0 measurements") | | `Strings.Membrane`: non-negative weights summing to ≤ 1, Q ≤ 100 (G3) |
| Sympathetic bank | `Dsp.Biquad.bandpass`; `Silk.washModesFor` (`Silk.kt:808`) for tuning tables; `Pluck.sympathetic` (`Pluck.kt:910`, private) | ARCO R2 SARANGI's tarab, from WASH | driven by the bridge, rotor-weighted |
| Body | `Strings.bodyRing` (`Strings.kt:896`), after the loop, feed-forward | | |
| Rotor | | | phase, quantised for LOOP, Hz/ratio split (G4) |
| Band limit, decimate, level | `Tide.bandLimit`, `Dsp.decimate`, `Dsp.levelTo(MELODIC_LOUDNESS_TARGET)`, `Dsp.fadeTail` (as `Bore.kt:808-829`) | | |
| Seeds | `Dsp.seedFor`, `Dsp.Noise` (`Dsp.kt:147`, `:171`) | | |
| LOOP | `Keys.seamError`, `requireSeam`, `MAX_SEAM_ERROR`; `Siren.bestCut`; BORE's loop planner (`Bore.kt:841-954`) | | rotor turns fitted to the loop |

So GYRE's genuinely new code is: the coupling junction, the rotor, the TOUCH morph that drives the pluck and the bow
into the same segments, and the engine around them.

## Architecture

```
                     rotor φ(t) ── SPIN (Hz below ~10 Hz, note ratio above)
                        │
     ┌──────────────────┼──────────────────────────────────────────┐
     │ contact weight   │ coupling c(t)      sympathetic emphasis   │
     ▼ w_n(φ)           ▼                     ▼                      │
 pluck burst ─┐                                                      │
 (TOUCH low)  ├─► string n  (nut segment ─ bow at β_n ─ bridge segment)
 bow, Strings.Bow ┘   n = 1..4, ratios 1,2,3,4            │
 (TOUCH high,                                             ▼
  pressure × w_n)                          bridge: reflected waves → M(c, H_m), H_m = Σ wₖ·bandpass, wₖ ≥ 0, Σ wₖ ≤ 1
                                                          │          │
                                     inject back into every string ◄─┘
                                                          │
                                                          ├──► sympathetic bank (8 resonators, listen only in R1)
                                                          ▼
                              bridge force + sympathetic ──► Strings.bodyRing (BODY, feed-forward)
                                                          ──► DC block ──► Tide.bandLimit ──► Dsp.decimate
                                                          ──► levelTo(MELODIC_LOUDNESS_TARGET) ──► fadeTail
```

- **Round one strings are single rings.** A pluck works on one ring, and
  the ring's one junction (`reflected()`/`inject()`) stands for the
  bridge. Round two swaps each string for ARCO's two segments plus the
  bridge port (G3). The coupling matrix's code carries over; where it
  plugs in does not, so R2 re-measures pitch and re-runs the bound test.
- **TOUCH** crossfades two inputs into the *same* segments: the pluck
  burst's level falls as `cos(TOUCH · π/2)`, and the bow's pressure rises
  as `sin(TOUCH · π/2)`, as §6 proposes. Because both feed one string, a
  pluck sets up a motion the bow can catch or fight. That is what makes
  the middle of TOUCH more than a crossfade of two renders.
- **The rotor's destinations** at moderate SPIN: contact weight `w_n`
  (bow pressure per string), coupling `c`, a small damping swing (`Loop.gain`)
  and sympathetic emphasis. No destination dominates (§10). Output level
  is never a destination.
- **Mono and dry**, like every engine. Stereo is FORK's WIDTH precedent
  and is not in scope.

## Testing

House tests every engine gets: determinism (one-shot and LOOP), finite
output, peak ceiling, DC bound, "every macro changes the sound", patch
JSON round trip, no drum class, LOOP fuzz over every TUNE step at
`Keys.MAX_SEAM_ERROR`. See BORE's tests (`BoreTest.kt`,
`BorePresetsTest.kt`, `BoreLoopFuzzTest.kt`) for the shapes.

Tests that carry GYRE's own claims:

| Claim | Test |
|---|---|
| Strings play each other (§8) | Pluck string 1 only. String 3's energy (read from its own loop) is zero at coupling 0, and rises with c. |
| The bound holds (G3) | At c = 1, every `fb` at its ceiling, maximum velocity and the fastest SPIN, a 30-second render stays under the raw peak ceiling and decays to silence after the excitation stops. |
| The rotor is not tremolo (§38) | At slow SPIN, the spectral centroid (or the ratio of two partials' levels) swings at the rotor rate by more than a bound. A control render, the SPIN-0 sound with output tremolo at the same rate and depth, does not. |
| TOUCH is a continuum (§6) | Adjacent TOUCH steps (0.0, 0.1, ..., 1.0) are each closer to their neighbours than the ends are to each other, by the house's macro distance. The midpoint is far from a 50/50 mix of the two end renders. |
| Pitch survives coupling (G3) | At moderate settings, `Pitch.detect` reads every TUNE step within the tolerance round one measures, across BODY and SYMPATHY. |
| No octave error (G2) | At every voice's SYMPATHY ceiling, `Pitch.detect` does not read half the note. |
| The rotor locks to the note (G4) | On each plateau (1, 2, 3 times the note), sidebands land on harmonics: inharmonic energy stays under a bound, and `Pitch.detect` reads the note. Across the whole of SPIN, the rotor rate is continuous: no step between adjacent macro values is larger than a bound. |
| HOLD closes (G5) | The rotor completes a whole number of turns in the kept loop, and the seam is measured one loop later. |

## Phasing and gates

Each round ends at an audition gate. Nothing proceeds until a human has
listened. This sandbox cannot play audio.

| Round | Builds | Needs | Gate question |
|---|---|---|---|
| **R0** | Toolkit only, no audio change: the coupling junction over N `Loop`s, with unit tests for the bound (`|1 − 2c·H_m| ≤ 1` over random membranes; energy never rises in a coupled network with no input). Existing render hashes unchanged. | nothing | none (no sound) |
| **R1** | The engine, pluck side only. FLICK and HALO; TOUCH pinned low; TUNE, SYMPATHY, SPIN, BODY, HOLD (one-shot only). Single-ring strings, the bridge, a listen-only sympathetic bank, the rotor below the split. Patch, `Patches`/`Velocity` arms, determinism canaries, audition generator. Round-one probes (§31) set the numeric bounds. | R0 | §32's questions 2-4: does BODY sound like a shared body, SYMPATHY like strings answering strings, SPIN like part of the object? |
| **R2** | The bridge port on `Strings.Bow` first (the bow itself landed in ARCO R1a, PR #420), as its own change with no audio change to ARCO (proven by a frozen grid of ARCO renders). Then TOUCH's bow half; strings become two segments; DRAWN and BOURDON. | `Strings.Bow` (built) and its bridge port | §32's questions 1 and 5: does TOUCH sound like changing mechanics? Can FLICK and DRAWN make useful samples with no FX? |
| **R3** | Impossible territory: the circular bow (rotating contact weight, fixed β) and LATHE; SPIN above the split (note-locked); WIRE; two-way sympathetic return; then a moving-β experiment. | R2 | §33: one continuous move from plausible to impossible, with no abrupt switch |
| **R4** | HOLD's LOOP top step (G5); LOOP fuzz test. | R2 (a bowed LOOP needs a bow) | §34's difficult seams |
| **R5** | Presets by ear (8 per voice), `GyrePresets`, `Presets` branch, `SynthKits.gyre()`, the testkit kit, the roadmap row's "as built". | R4 | the production roster |
| **R5.1** | The phone: `SynthScreen.kt`'s picker and its seven arms, README. Built in a session that can compile `:app`. | R5 | |

R0 and R1 can run beside ARCO's work. R2 needs only `Strings.Bow`, which exists, and its bridge port.

The integration surfaces for R1 and R5 follow BORE's registration table
(`2026-09-28-bore-woodwind-engine-design.md:1120-1175`). In short:
`Gyre.kt` (voice enum, `macrosFor`, `defaults`, `drumClassFor`, `scramble`,
`render`), `GyrePatch.kt` (implementing `Patch`, `ENGINE = "GYRE"`), the
`Patches.fromJsonValue` arm (`Patches.kt:38-55`), the `Velocity.macroSpecsFor`
arm (`Velocity.kt:191`, which the compiler enforces), the `Presets` arms
(`Presets.kt:66-69`, `:98`, which `PresetsTest` enforces), `SynthKits.gyre()`,
`GyreKitGenerator` and `GyreAuditionGenerator` with their Gradle tasks
(`synth/build.gradle.kts`, beside `generateBoreKit`), and canaries in
`DeterminismTest`, `PadRecipeTest`, `PresetsTest` and `SynthKitTest`. The
CLI needs nothing: `snipsnap synth GYRE <VOICE>` resolves through
`Presets.forVoice` once the presets exist.

## Out of scope

- Stereo (FORK's WIDTH is the precedent if wanted later).
- Bow reversal, détaché and tremolo as phrases. ARCO rules these out too:
  the reference bow is unipolar (ARCO `:2009-2013`).
- A growl knob. A memoryless table cannot period-double (ARCO `:2017-2019`).
- A crossfaded LOOP wrap (G5).
- A held keys instrument (`Keys.gyrePad`). That is the round after R5, as
  BORE and ARCO defer theirs.
- Sampled audio of any kind (the document's own non-goal).

## Round one's Phase-0 measurements (2026-10-01)

Before round one's plan was written, a throwaway prototype (a scratch
worktree, never committed) built R0's bridge and R1's coupled pluck and
measured them. The plan
([`../plans/2026-10-01-gyre-round-1.md`](../plans/2026-10-01-gyre-round-1.md))
carries the code and every number. What the measurements changed in this
design:

1. **The membrane cannot run on `Dsp.Biquad`.** With Float coefficients, a
   narrow mode stops being the filter it was designed as: `Re H − |H|²`
   reached −9.8e-4 at 110 Hz, Q 3000, and −4.6e-4 at 440 Hz, Q 300, at
   176.4 kHz. That is more than a string's own loss at GYRE's feedback
   ceiling (`1 − fb` = 5e-4), so the bridge could add energy. G3's numeric
   check passed only because its random tables missed those corners. So
   `Strings.Membrane` runs its own RBJ bandpass in Double (worst
   `Re H − |H|²` −1.3e-11), with Q capped at 100. A membrane is broad:
   GYRE's run Q 3 to 12. The listen-only sympathetic bank stays on
   `Dsp.Biquad`, because it is outside every loop.
2. **Coupling moves pitch, as G3 warned, and it is cancelled analytically.**
   With coupling off the strings are within 0.2 cents of the note. At the
   defaults they drift up to 8.9 cents, and FLICK at BODY 1 drifts 31.
   Pre-tuning each string by the bridge's own phase at its note,
   `f' = f · 2π / (2π + arg(1 − (2c/N) H_m(f)))`, read from
   `Membrane.response`, brings the defaults to 0.7 (FLICK) and 3.0 (HALO)
   cents. One case is left: a membrane mode sitting on the note (at BODY 1
   a mode lands at 154.9 Hz, on D#3 at 155.6 Hz). That is a **wolf note**:
   string and body lock together and split. A **wolf guard** scales the
   coupling down on that note only, until `(2c/N)·|H_m(f)| ≤ 0.05`. Every
   corner measured is then within 4.7 cents, and the default coupling is
   untouched (an unplucked string still answers at −16.8 dB).
3. **The octave guard holds.** With whole-number ratios, SYMPATHY 1 and
   BODY 1 never read an octave low at any TUNE step (G2).
4. **FLICK's A4 read as a snare.** The classifier calls more than half the
   energy above 2 kHz a snare, and the upper strings' loop filters reached
   18 kHz. Key-tracking the pick did not help. A brightness ceiling on the
   loop filters did: at 8 kHz the snare reading is gone with no margin
   (0.49 against the line's 0.50); at **5 kHz** the worst reading is 0.36,
   with no drum class at any note or corner, on either voice.
5. **The sympathetic bank was 28-37 dB under the strings**, which is
   barely there, and SYMPATHY moved it only 6.5 dB end to end. It needs
   about 20 dB more and a steeper law. Round one fits both to target
   shares and the gate decides whether those are right.
6. **What already works:** a solo pluck reaches an unplucked string at
   −16.8 dB, and exactly 0 at coupling 0. The rotor at 1 Hz swings the
   spectral centroid by 117 Hz against 15 Hz for a tremolo of the same
   depth, 7.8 times. Every macro changes the sound, SPIN at 0.02 included
   (2.6% different from SPIN 0). Every corner is finite, peaks at 0.95 at
   most, and decays by 8,000 times or more. A FLICK note renders in about
   0.4 s and a HALO note in about 0.8 s, on a desktop JVM, unwarmed.
7. **The house detector cannot test 5 cents.** `Pitch.detect`'s lag is an
   integer, so its readings repeat identically across both voices (+9.1
   cents at D#4 in each). Tuning tests use `FineTuning.measuredHz`, as
   SILK's do.

## Decisions taken (2026-10-01)

The owner took every recommendation:

1. **Order:** GYRE R0 and R1 now, beside ARCO. GYRE's bow comes in R2.
2. **Macros:** TUNE · TOUCH · SYMPATHY · SPIN · BODY · HOLD. TENSION is part
   of each voice. R1 has no TOUCH (it plucks only), and a recipe without
   TOUCH decodes as TOUCH 0, a pure pluck, so R1 recipes still sound the
   same after R2.
3. **Names:** SPIN for the rotor knob. Voices FLICK, DRAWN, LATHE and
   BOURDON replace PLUCK, ARC, WHEEL and DRONE. HALO and WIRE stay.
4. **WHEEL:** ARCO keeps its WHEEL; GYRE's circular bow is LATHE.
5. **The bullroarer:** off every product surface (G8).
6. **The bridge port on `Strings.Bow`:** taken, and then overtaken. ARCO's
   bow landed (ARCO R1a, PR #420, `Strings.kt:763`) while this review was
   open, with the bridge reflection inside the bridge `Loop`
   (`fb = −REFLECTION`) and no port. So "build it with the port from the
   start" is no longer possible. The port lands as a no-audio-change
   change to `Strings.Bow` before GYRE R2 needs it, proven by
   `StringsBowTest` and a frozen grid of bow renders. ARCO R1b may land it
   first if that is convenient. Round one does not need it. GYRE R2's
   dependency on ARCO is otherwise met: `Strings.Bow` exists.
