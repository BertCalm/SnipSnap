# ARCO — the bowed string: a friction junction in a fleet of strikes

**Status:** design; brainstorm from the two 2026-09-28 documents ("ARCO"
and its string-machine addendum); not implemented. The documents' engine,
transcribed and rendered, plays no note at any setting — its friction
loop is a relay that parks at the bow velocity and chatters, 46 % DC with
no period ("The specification, as reviewed"); the corrected model — the
reference two-segment bowed string built on the house's own string
toolkit — was measured in a Phase-0 spike (a throwaway prototype, kept as
a record outside the build,
[`../plans/2026-09-29-arco-phase-0-spike.md`](../plans/2026-09-29-arco-phase-0-spike.md))
and works: bounded everywhere, in tune to a few cents, a textbook
Helmholtz sawtooth. No engine code is committed; round one rebuilds the
bow from this design rather than copying the spike. This document lands
as a docs-only PR (zero check runs by design,
`.github/workflows/tests.yml:28-34`; BORE and FORK landed the same way);
its commit message names the string machine's product and its makers
only to say they are kept off every surface, as the blocklist's own
commit did ("the blocklist gained rhodes/wurlitzer/fender").
**Date:** 2026-09-29
**Plan:** to be written per round (`docs/superpowers/plans/2026-09-29-arco-round-N.md`)
**Related:** [`2026-09-28-bore-woodwind-engine-design.md`](2026-09-28-bore-woodwind-engine-design.md)
is the model for this document and its sibling in the fleet — the other
loop sustained by a nonlinearity rather than struck — and the two share
a toolkit change ("Architecture": ARCO's R0 *is* BORE's R0);
[`2026-09-27-fork-electric-piano-engine-design.md`](2026-09-27-fork-electric-piano-engine-design.md)
set the shape both copy (an external spec reviewed against the code, the
physics corrected, the fleet table, claims tests, rounds with gates);
[`2026-09-27-silk-string-engine-design.md`](2026-09-27-silk-string-engine-design.md)
built the `Strings` toolkit the bow is made from and set the
share-the-toolkit-take-a-new-name precedent;
[`2026-09-27-siren-dub-engine-design.md`](2026-09-27-siren-dub-engine-design.md)
settled HOLD, the LOOP top step and landing an effect by recipe, all
reused here.
**Roadmap:** the `SYNTH_ROADMAP.md` row is added when implementation
starts, not now — the rule FATHOM, RESIN, GLINT, SILK, FORK and BORE
followed. Note for whoever adds it: the highest row today is **S17**
(`docs/SYNTH_ROADMAP.md:271`, SANTUR), so BORE's reservation of S17 is
already taken and ARCO's row is S18 or later (api F15).
**Evidence:** seven investigator reports read the two documents against
the tree at `b8b8557` — an API and house-conventions audit (`api`), a DSP
desk review with pure-Python replicas (`dsp`), an empirical probe that
transcribed the documents' Kotlin into a worktree and rendered it
(`empirical`), a fleet comparison and reuse map (`fleet`), a product
review (`product`), a physics reference that fetched the two open-source
witnesses of the reference model (`physics`) — and a Kotlin spike built
the corrected bow on the house's primitives and measured it (`spike`).
Three design proposals then argued the open questions (`macros`,
`toolkit`, `rack`). Every number below is copied from one of those and
says which; tags of the form `dsp F21`, `empirical §H2`, `spike (4)`,
`api §D4`, `fleet §3e`, `product §5`, `physics §2a` name the report and
its finding, table or section. The reports are workflow scratch files,
not in the tree, so every fact they back is restated here in full and
the tag records only which report measured it. The spike's source, its
test, its report and the probe's transcription are kept as a record in
[`../plans/2026-09-29-arco-phase-0-spike.md`](../plans/2026-09-29-arco-phase-0-spike.md),
outside the build, so their numbers can be re-run; that file and this
one are the only changes to the tree.

## Why ARCO

The ask (2026-09-28) arrived as two documents. The first is a
specification for **ARCO** ("Acoustic Friction & Bowed String Physical
Modeler"), a bowed-string physical-modelling engine for `:synth`: five
voices (CELLO, CONTRABASS, ERHU, SARANGI, GURDY — a hurdy-gurdy), six
macros (TUNE, BOW, ROSIN, BODY, DECAY, SYMPATHY), an `ArcoPatch`, a
stick-slip friction loop on one delay line, per-voice bodies (two
bandpass filters for the wooden instruments, the house's membrane table
for the skinned ones), a sympathetic "tarab" bank on the sarangi, a
buzzing bridge and a drone on the hurdy-gurdy, a `Keys.arco`, a
`Velocity.kt` line and two "cross-synthesis" ideas. The second replaces
SYMPATHY with **SOLINA**, a macro that fades in a 1970s string-machine
stage — a sawtooth under the string, two fixed formant filters, and a
three-tap chorus modulated by two slow oscillators 120° apart, in stereo
— and adds eleven presets and a three-item verification matrix. The
owner's words: *"I want to brainstorm how to integrate a bowed engine
like this."* This is that brainstorm, in the house's shape.

The home decision is made by the documents themselves and by BORE's four
reasons, unchanged (`2026-09-28-bore-woodwind-engine-design.md:66-96`):
**a `:synth` engine** — Kotlin, offline, one `Snip` (the app's in-memory
clip: samples, channel count, sample rate) per render, rendered at four
times the sample rate (`Dsp.RATE * Dsp.OVERSAMPLE`, 176.4 kHz,
`Dsp.kt:21, :33`) so the harmonics a nonlinearity makes above hearing
can be filtered off before the render comes down to 44.1 kHz, its
`ArcoPatch` (an engine's saved preset: engine, voice, macro values — the
thing `kit.json` stores and a pad regenerates from, `Patches.kt:14-27`)
the house shape to the letter. Where ARCO sits in the fleet is also
decided, twice over. The synth-depth design set aside a *sustained
group* — engines "driven rather than struck … the same machinery used
for bowed and blown models", with "their own audition gate"
(`2026-09-18-synth-depth-design.md:377-396`); BORE is that group's first
member whose sustain is physics and ARCO is its second. And the string
toolkit already names this engine without building it: `Pluck.kt`'s own
KDoc describes a Karplus-Strong loop "with no burst of its own, fed
continuously" as "the third caller shape neither SILK nor PLUCK currently
needs" (`Pluck.kt:860-869`; fleet §Headline). ARCO is that caller.

For a reader new to this, the mechanism in one paragraph. A bow is a
ribbon of rosined horsehair drawn across the string at a steady speed.
Rosin grips: for most of each cycle the hair holds the string and drags
it sideways at the bow's own speed (*stick*); the string's tension pulls
back harder and harder until the grip breaks and the string snaps back
the other way, faster than the bow, until the hair catches it again
(*slip*). Helmholtz saw, with a vibration microscope, that the string
does not wobble as a whole: one sharp corner races round a lens-shaped
path, bridge to nut and back, once per period, and the moment that
corner passes under the bow is the moment the grip breaks — so the
*string*, not the bow, sets the pitch; the bow only supplies energy,
which is why bow speed changes loudness and tone but not the note. At
any point on the string the velocity is a sawtooth (a long slow stick,
a short fast flyback), which is why a bowed note is bright. The friction
curve is what makes this self-sustaining: friction is high when hair and
string move together and falls off as they slide faster, so a returning
wave that momentarily unsticks the string is met with *less* resistance
and the loop gains rather than loses. Three things the player controls:
**bow speed** sets the amplitude; **bow force** sets the tone — too
little and the string slips more than once per period (a thin, whistly
"surface sound"), too much and the motion turns raucous; **bow
position** — how far from the bridge the hair sits — sets which
harmonics are missing and how wide the safe force window is (physics
§1, tagged there as the standard description, unsourced in this sandbox;
the reference implementation's three controls are `maxVelocity_`,
`setSlope(5 − 4·pressure)` and `betaRatio_`, `stk_Bowed.cpp:116, :155,
:72`). Everything in `:synth` is a strike — a burst into a body that
rings and dies (PLUCK, SILK, FORK, TINES, SKIN, TERRA) — or an
oscillator under an envelope (VELVET, FATHOM, TIDE, GLINT, VOX,
TONEWHEEL, SIREN). BORE's valve, designed but not built, would be the
first note sustained by a nonlinearity at the *end* of a loop; a bow is
a nonlinearity *inside* the string, at a point β along it, and that one
difference — two string segments instead of one — is the whole of
what the toolkit lacks and the whole of what this design adds.

A few of the house's words, for the same reader. The **rack** is the
per-pad effects chain (EQ, tape, echo and the rest) saved beside the pad
in `kit.json`, in a fixed order (`FxChain.kt:9-11`). A **recipe** is
what a synth pad regenerates from: its patch plus its rack section
(`PadRecipe.kt:12-13, :23-25`). A **gate** is a listening session — a
page of rendered clips with verdict buttons — whose verdict decides
whether the next round starts at all. **HOLD** is a knob that means how
long a sustained sound is held, whose top step renders a seamless
**LOOP** for the SURFACE, the phone screen where a loop plays under a
finger, pitched by where the finger sits (`Siren.kt:74-90`). A **claims
test** is a test that measures the rendered audio for the property the
design promises, so the promise is kept by CI rather than by a sentence.
A **cent** is a hundredth of a semitone; five cents is about the
smallest detune a listener notices. **DC** is a constant offset — the
average of the wave sitting off zero — which a speaker cannot play and a
classifier reads as energy. **CI** is the test run on every pull request
(a **check run** is one of its jobs); a **worktree** is a scratch
checkout of the repository. `lin(x, a, b)` maps a 0..1 knob to a..b in a
straight line and `expMap` exponentially, so equal knob steps are equal
ratios.

## The specification, as reviewed

Transcribed faithfully into a worktree with three compile-driven changes
(C1–C3 below) and rendered at 176.4 kHz, the documents' engine is
**finite everywhere** — no NaN, no overflow, in 140 renders across five
voices, two versions and fourteen macro corners each — and **plays no
note** (empirical §Headline). At every voice and every TUNE, and at
every BOW, BODY, SYMPATHY and DECAY-1 corner, the raw loop's last 200 ms
has a zero-crossing rate of 12–24 kHz, the house pitch detector returns
`null`, the six "harmonics" at k·f0 sit within ±7 dB of each other where
a sawtooth would fall 6 dB an octave, and the autocorrelation at one
loop period is −0.17 to −0.21; the ROSIN and DECAY-0 corners move those
numbers (zero crossings 4–11 kHz, r(D) up to +0.43, the detector
returning an octave-below or lag-quantised artefact) without a note
appearing. What the loop makes is a chaotic orbit of its friction map
with a 46 % DC offset (raw mean 0.884 on a 1.917 peak at defaults; 0.56
at BOW 1): the map `x → μ(v_bow − x) + 0.985·x` has **no fixed point** —
for x below the bow velocity μ is at least +0.25, for x above it at most
−0.25, so no stationary value exists (empirical §H2) — and its scalar
iteration from zero reproduces the rendered peaks to three decimals
(1.926 / 2.477 / 2.877 against 1.917 / 2.477 / 2.877 for defaults / BOW
1 / BOW 1 + ROSIN 1). The finished WAVs carry that DC (mean +0.44 on a
0.95 peak for CELLO and CONTRABASS) because nothing in the chain removes
it. Where a pitch *is* audible it is a fixed body resonance, not TUNE:
ERHU's render peaks at 292–293 Hz at TUNE 0, 0.5 and 1 (the membrane's
fundamental is hard-coded at 293 Hz), SARANGI's at 129–130 Hz. The only
in-tune line anywhere is the addendum's sawtooth at SOLINA 1 — an
oscillator, not the string. The DSP desk review predicted every one of
these from the code before the render (dsp F2–F4: "a relay cannot
stick"), and the physics reference predicted them independently (physics
F2–F3).

The contract half — the `ArcoPatch`, the macro list, the wiring into
`Patches`/`Presets`/`Velocity` — is nearly right (`ArcoPatch` mirrors
`TerraPatch.kt:10-23` to the letter; api §Headline); the physics half
describes the reference bowed string in its prose and codes something
else. Read against the code and, where the reports rendered it, against
the render:

| Spec section | In the repository today | Verdict |
|---|---|---|
| §1 signal flow (part 1, p.1): "Bidirectional Digital Waveguide" → "Bridge Differentiated Drive" → bodies | one delay line of a full period read once (`delaySamples = rate/f0`), the friction term added in series, the wave written back — a single ring, unidirectional (dsp F1; empirical §Prose≠code 2). The reference model has two segments, bow-to-bridge and bow-to-nut, and reads *both* returning waves before it decides what to write (`stk_Bowed.h:104-116`, fetched) | the diagram is prose; the code has no bow position and no junction |
| §1 friction law (p.1–2): `μ(Δv) = sgn(Δv)·(μd + (μs−μd)/(1+(Δv/v0)²))`, "stick phase / slip phase" | a relay: `|μ| ≥ μd = 0.25` at every Δv including zero, sign flipping at Δv = 0, added as a *force* to a *velocity* wave with no impedance (dsp F2). A memoryless odd function with a nonzero limit at zero has no stick state — the string can never rest at the bow speed. The reference table is a *reflection coefficient* multiplied by Δv, `Δv·ρ(Δv)`, continuous through zero and never above 0.98, so the bow can hold the string (its 0.98 plateau *is* the stick solution) and can never add more than closes the gap (`stk_BowTable.h:84-99`; physics §2a) | **no note at any setting**, measured (empirical §Headline and the per-voice tables) and predicted (dsp F3) |
| §2 voices (p.2–3): CELLO/CONTRABASS bodies at 98/220/340 and 58/110/180 Hz; ERHU "membrane (1.00, 1.59, 2.14, 2.30)", a nasal formant, fretless glides; SARANGI's 35 tarab strings "continuously stimulated by differentiated bridge force"; GURDY's wheel, bourdons at f0 and 1.5·f0, a chien that rattles "when bow speed accelerates" | code: two bandpass biquads at 104/220 and 58/110 Hz (no third mode; prose ≠ code on 98 vs 104); `Modes.resample(MEMBRANE, 5)` — the house's ideal-drumhead Bessel table, five ratios not four (`Modes.kt:163`), rung at a fixed 293/130 Hz; no formant; no glide; seven `Pluck.ks` *plucks* at a fixed 130.81·r Hz that ignore TUNE and are struck once at t = 0 (empirical §G: the seven lines sit at 130.55…244.94 Hz at every TUNE, 3–36 dB under the chaos), dropped entirely in part 2; a "drone" that is `Dsp.saw(constant)` evaluated once *outside* the loop — a DC offset of −0.3497, not a bourdon — from a `Dsp.saw` that does not exist (api F1); a chien that is a static threshold `|dry| > 0.35` on an un-normalised buffer (dsp F15). BODY is a no-op on ERHU, SARANGI and part 1's GURDY (BODY 0 and BODY 1 rows identical to every printed digit in the empirical ERHU, SARANGI and GURDY tables; empirical §Prose≠code 5) | two voices are one voice with different numbers; two have a body and no mechanism; one has a mechanism with no code. Every Hz and Q is unsourced (physics §5) |
| §3 macros (p.3): TUNE · BOW · ROSIN · BODY · DECAY · SYMPATHY; "the :synth 6-macro contract" | six is inside the 3–6 *range* (`docs/SYNTH_ROADMAP.md:173-174`; a range, not a count — FORK ships five). BOW is speed only (`vBow = lin(0.1, 1.5)`). ROSIN is three things — the curve's height, the loop low-pass corner (3.5–16 kHz) *and* the attack length — and the low-pass's phase is unbudgeted, so the unbudgeted low-pass would leave a working note 4.8 cents flat at C2 and 38 at C5 at ROSIN 0 (0.8 and 6.7 at ROSIN 1), so ROSIN is a pitch knob (dsp F6; BORE's F2 class). DECAY is note *length*, `expMap(decay, 0.25, 3.5)` s with a fixed 50 ms release — nothing decays, the bow stops: HOLD in SIREN's words (`Siren.kt:74-90`; api C2). The prose's `Dsp.around(0.2f, 0.5f, 0.95f)` has three of four required arguments (api F8). SYMPATHY names a mechanism, not a sound | five plain words: TUNE · BOW · GRIP · BODY · HOLD ("Macros") |
| §3 `ArcoPatch` (p.4) | `TerraPatch.kt`'s shape: `init { Patches.validateMacros }`, `ENGINE`; but no `fromJsonValue`/`fromJsonText`, so the `Patches.fromJsonValue` arm the addendum asks for cannot be written from it (api F3) | built as `TerraPatch.kt:10-33` |
| §4 `Arco.kt` loop (p.5–7) | a ring read with a two-point lerp whose `frac` goes negative for half of every ring cycle (an extrapolation, BORE's F3; here it does not detune but switches the kernel's high-frequency gain at `rate/maxDelay ≈ f0/2`, a periodic artefact a listener would take for period doubling — dsp F5); no DC blocker (dsp F7); a `seed` render argument no recipe can store, whose `prng` is constructed and never read (api F9); `defaults + macros` passing unknown keys through where `Fork.settled` drops them (`Fork.kt:260`; api C4); `pressure` multiplying both the bow speed and the force, so there is no force axis at all — "hard bow pressure … period-doubling" (p.2) has no parameter to push (physics F4) | rebuilt on two `Strings.Loop`s ("Architecture") |
| §4 output (p.7): `normalizeByFold → Punch.applyOversampled(0.4) → limitPeak → fadeTail` | the drum chain — `Punch.applyOversampled`'s callers are Thump, Skin and Terra (`Punch.kt:270`; api F5) — with a hidden PUNCH of 0.4 no macro controls; every melodic engine ends in `Tide.bandLimit → Dsp.decimate → Dsp.levelTo(MELODIC_LOUDNESS_TARGET) → fadeTail` (`Fork.kt:465-468`, `Tide.kt:549`); measured: peak 0.950 and `Loudness.of` 0.113–0.281 against the 0.1834 target, a 7.9 dB spread across corners (empirical §Promised vs rendered) | the melodic chain; no PUNCH (`fork spec:305`) |
| §4 `applyAcousticBody` (p.7–9) | the differentiated drive into unit-gain biquads makes the CELLO body path −51 to −54 dB under the dry (CONTRABASS −57 to −59; dsp F10) — inaudible, while BODY attenuates the dry by up to 6 dB, so the knob's audible effect is "quieter"; the membrane branch peak-normalises a body whose loudest content is the onset thump of the DC step (dsp F13); the bodies bypass `Modes.fixed` and `Strings.bodyRing`, which exist for exactly this (`Modes.kt:202`, `Strings.kt:722`) | `Modes.fixed` rows through `bodyRing`, labelled *shape* until sourced |
| §5.1 `Keys.arco` (p.9) | returns a one-shot `Snip` where every held instrument returns a `KeyNote` (`Keys.kt:17`); `coerceIn`s the range where the house `require`s and names it (`Keys.kt:225-227`); a cello that stops under a held key is rule 6's failure (`docs/SYNTH_ROADMAP.md:184-187`; api F17) | R2, on `resinPad`'s body |
| §5.2 `Velocity.kt` insert (p.10) | names a `BorePatch` that exists nowhere (BORE is docs-only) and a `TerraPatch -> "STRIKE"` line `Velocity.kt:238-253` does not have, and drops the shipped `SilkPatch -> "PICK"` (api F2); the `macroSpecsFor` arm the compiler demands (`Velocity.kt:191-219`, an exhaustive `when`) is not mentioned; an override is registered only after a monotonic sweep (`ForkTest`'s STRIKE sweep) | the arm in R1; `soften` until a sweep passes |
| §5.3 `Chimera.kt` (p.10) | does not exist (`grep -rni chimera` = 0, api row 39); "Buchla wavefolding" is the one name in either document the blocklist already catches | prose |
| addendum §1 (part 2, p.1): the string machine "is not an insert effect; it is directly coupled to the physical friction engine" | nothing is coupled: the sawtooth is added to the *output* buffer after the delay-line write, the formants and the chorus run over the finished body buffer, nothing returns to the loop (fleet §5a; product §2c). The saw is "phase-locked" only in sharing the macro's nominal f0 — a free accumulator, not a divider (dsp F17) | three inserts in a row: the rack's ("The string-machine question") |
| addendum §2–3: tri-phase chorus 7.0 / 2.5 / 0.4 ms at 0.58 / 5.85 Hz; "net pitch deviation sums to zero"; formants at 320/780/1800/2600 Hz | code 7.5 / 2.8 / 0.45 ms (prose ≠ code); the sum of the *delays* is constant but each tap swings ±17.6 c (slow) and ±28.4 c (fast) and each channel carries two taps — measured pitch sd 32 / 28 cents per 100 ms window with excursions to +89 / −87 c (dsp F20; empirical §F1); the dry/wet mix is a comb (dsp F21); code has *one* formant chain — peaking 380 Hz +6 dB and 2400 Hz +5 dB — on every voice, no shelf, no viola, no bass cut (dsp F18) | an ordinary chorus of ordinary depth, mis-described; a rack section |
| addendum §4 macro SOLINA; preset "SOLINA CELLO 74"; class `SolinaEnsemble` | a product name on two product surfaces (`docs/SYNTH_ROADMAP.md:33-35, :44-47`), uncaught by the blocklist (`PresetTestSupport.kt:27-39` has no `solina`, `eminent`, `arp`, `mostek`, `tca350`; api §D1–D4) | off every surface; the regex grows ("The name") |
| addendum §6 stereo (p.9–10): `Tide.bandLimit(stereoEnsemble, …)`, `channels = 2` always | `Tide.bandLimit` is mono — one filter state walked across every sample (`Tide.kt:533-540`) — so on the interleaved buffer L smears into R and the cutoff acts at twice the intended frequency: the ensemble's own −12.5 dB of stereo difference comes out at −43.7 dB, and two *identical* channels at SOLINA 0 acquire a −35 dB difference (empirical §F4; dsp F23); every ARCO WAV doubles in size including the dry presets, against U4's "opt-in per patch" (`docs/SYNTH_UPGRADE.md:284-285`; api F11) | mono; stereo is the rack's, after U4 |
| addendum §7 `ArcoPresets` (p.11–13): eleven, 3/2/2/2/2 | five names exceed 14 characters, one carries the product name and a year, one ("SILK PYTHON") collides with the SILK engine's name (`SilkPatch.kt:22`); none of the five voices reaches eight (`ForkPresetsTest.kt:70-74`; api F13) | authored by ear after the gate, eight per voice |
| addendum §8 verification matrix (p.13) | test 1's "1.72 s common multiple of 0.58 and 5.85 Hz" is one period of the slow LFO and 10.085 of the fast — the true common period is 100 s (dsp F24; empirical §F3); test 2 is a tautology as written (`Loudness.of` folds to mono itself, `Loudness.kt:21`) and fails if meant (the fold loses 1.68–1.85 dB at 0.85–0.95, the presets' amounts, against the 1.2 dB bar; dsp F28); test 3 is a registration step, not a test | replaced by the claims tests below |
| what would not compile | `Dsp.saw` (no such member; the tree's four `saw`s are `private`, `Fathom.kt:140`, `Velvet.kt:118`, `Resin.kt:146`, `ResinDrone.kt:94`); `BorePatch`; the missing `fromJsonValue`; part 2 has no `ArcoVoice` or `ArcoPatch` of its own (api §2) | three edits to compile (C1–C3), none touching a number the tables report |

The probe's three compile changes, for the record (empirical §The
transcription): **C1** the patch class lost `: Patch` because `Patch` is
sealed and the probe lived in the test source set — in `synth/src/main`
it compiles as written; **C2** the render body was split so the raw loop
buffer could be measured before the body and before normalising; **C3**
`Dsp.saw` was replaced by the private one-line `saw` the tree's engines
carry — which, called once outside the loop with a constant argument,
made the GURDY "drone" the constant −0.3497 the prose calls a bourdon.

### What the spec got right

It is worth being exact about this, because the corrections are many
and the ambition is right.

- **`ArcoPatch` is the house shape.** Every API the engine body calls
  but one exists with the signature it assumes: `MacroSpec(name,
  default, neutral)` (`Thump.kt:33`), `Dsp.lin`/`expMap` (`Dsp.kt:36,
  :42`), `Dsp.OnePole.lp` (`:416`), `Dsp.Biquad.bandpass`/`peaking`
  (`:374, :396`), `Dsp.Noise` (`:171`), `Dsp.seedFor` (`:147`),
  `Modes.ring`/`resample` (`Modes.kt:85, :282`), `Pluck.ks` with every
  named argument (`Pluck.kt:559-570`), `Tide.bandLimit` (`Tide.kt:533`,
  internal, same module), `Snip(samples, channels = 2, sampleRate)` (api
  §1, 49 rows). Three edits stood between the transcription and a
  compile.
- **TUNE snaps 24 semitones from a per-voice root** (`Fork.midiFor`'s
  shape, `Fork.kt:256-258`), and the roots are the documents' — E1, C2,
  C3, D3, D4 — which product §3 reads, from recall and unverified, as
  each instrument's bottom string (its own recall puts a hurdy-gurdy
  chanterelle nearer G3), and which is the register a stab or a pedal
  lives in (product §3).
- **4× with `Tide.bandLimit` before decimation** is needed and
  sufficient: a friction table's kink mints harmonics up to the ring's
  bandwidth (the spike's single-slip h10 is −22 dB; spike (3)).
- **The stick-slip idea, the bow-velocity envelope, the differentiated
  bridge drive, the membrane body for a skinned fiddle, the sympathetic
  bank, the buzzing bridge** are all the right mechanisms — the prose
  names the reference model correctly, down to "Helmholtz motion" and
  "the string snaps back" (physics §Headline). Each is coded wrong in a
  way a single in-tune test would have caught.
- **Cost is not a problem**: 35–72 ms per rendered second for part 1's
  engine — 34–53 for four voices, beside BORE's 37–59 for its finished
  chain (`bore spec:218`; its bare spike was 26–49, `bore spec:439`),
  and 58–72 for SARANGI's 0.935 s render because its seven `Pluck.ks`
  strings render 2 s each whatever the note length (empirical §E); the
  corrected bow's bare loop is 9.9 (spike (8)).
- **The product ideas are good.** A cello stab beside a snare, a bowed
  pedal under a beat, an erhu line under a finger, a string machine —
  these are sounds this user reaches for and no engine makes (product
  §1). CUTICLE BOW, CRANK CHATTER, DEEP SUB BASS and SUITE C2 are
  audition-worthy names; the roster's law problems are length and count,
  not taste (product §4).
- **The string-machine addendum names a real gap in the rack.** There is
  no chorus or ensemble section anywhere in the tree — TONEWHEEL's
  always-on scanner is one tap, mono, 50/50 with dry
  (`Tonewheel.kt:57-63`), and TAPE is wow and flutter on one line
  (`Tape.kt:35-37`); fleet §The rack sections. That gap is worth
  filling, in the rack, before the engine exists ("The string-machine
  question").

## The physics, measured

A few measurement words, for the same reader. **Slips per period**
counts how many times the string breaks free of the bow in one cycle;
the textbook note is exactly one, and two or more sounds an octave up
and thin. A **Schelleng diagram** is the map, bow force against bow
speed, of where that one-slip note exists — too little force and the
string slips twice, too much and it goes raucous; this document's tables
are rows of that map, measured. **t60** is how long a ring takes to fall
60 dB — the tail's length. The **centroid** is the spectrum's centre of
mass, the number that tracks "brighter". A **one-pole** is the simplest
low-pass, a **corner** its cutoff frequency (the Helmholtz *corner* of
the sawtooth is a different thing, the kink in the waveform, and the
text says which), and an **allpass** a filter that delays without
colouring, which the toolkit uses to tune a delay line by a fraction of
a sample. A **limit cycle** is an oscillation a nonlinear loop settles
into on its own — a bowed string's pitch is one, so it is measured, not
set. **KDoc** is a Kotlin source comment, where this house keeps a
constant's measurement beside the constant.


Three things were measured, in this order: the documents' loop as
written (the probe, Appendix D of the record); the reference model's
loop in pure Python from the fetched STK source (dsp §13); and the
reference model rebuilt on the house's ring, allpass and one-pole with
`Strings.tune`'s budget, in Kotlin, at the render rate (the spike,
Appendix A of the record). The first does not play; the second and third
do, and agree.

### The relay

The documents' loop is `y[n] = lp(F[n] + 0.985·y[n−D])` with
`F = pressure·μ(v_bow·pressure − y[n−D])` (part 2, p.9). At DC the
recirculating gain is `1/(1−0.985) = 67`, there is no DC blocker, and
`|μ| ≥ 0.25` whatever Δv is, so the line's only possible resting place
is the bow velocity, where it cannot rest (dsp F2–F3). Predicted before
the render: mean ≈ `v_bow·pressure`, chatter on top; measured on the
Kotlin transcription (empirical Tables A and B, CELLO, raw loop at 176.4 kHz):

| Corner | raw peak | raw mean | mean/peak | zero crossings / s | `Pitch.detect` | r at one period | h2…h6 re h1 (dB) |
|---|---|---|---|---|---|---|---|
| defaults (BOW 0.6, ROSIN 0.5) | 1.917 | 0.884 | 0.46 | 12,064 | null | −0.17 | 0.6 / −0.8 / 0.5 / −0.8 / −5.1 |
| BOW 0 | 1.094 | 0.094 | 0.09 | 12,021 | null | −0.17 | 3.3 / 3.4 / 6.3 / 9.0 / 8.2 |
| BOW 1 | 2.477 | 1.393 | 0.56 | 12,151 | null | −0.18 | 3.6 / 5.1 / 9.4 / 5.1 / 3.7 |
| ROSIN 0 | 1.512 | 0.866 | 0.57 | 4,586 | 65.3 Hz (an octave below) | — | −4.4 / −0.8 / −9.0 / −7.7 / −4.8 |
| ROSIN 1 | 2.325 | 0.895 | 0.39 | 22,778 | null | 0.06 | 7.8 / 4.8 / 7.0 / 7.3 / 6.2 |
| BOW 1 ROSIN 1 | 2.877 | 1.413 | 0.49 | 22,768 | null | 0.05 | 0.9 / −5.2 / −6.8 / −2.9 / 0.4 |

The mean is `0.94·v_bow` at defaults, `0.93` at BOW 1: the relay's
equilibrium. ROSIN sets how white the chatter is (the loop low-pass's
corner raises the crossing rate fivefold, from 4.6 to 22.8 kHz).
CONTRABASS, ERHU, SARANGI and GURDY render the same picture at every
corner — peak 1.83–1.93, mean 0.88, r at one period −0.18 to −0.21,
`Pitch.detect` null — differing only in their bodies (empirical, the
per-voice tables). The addendum's
stage does not change the loop (part 2's loop rows equal part 1's digit
for digit); at SOLINA 1 the sawtooth is the one line in the render at f0
(h2…h6 = −5.7 / −8.5 / −11.5 / −12.4 / −15.8 dB, slope −5.8 dB/oct),
with 0.6 of the chaos still under it (empirical §D).

The consequences downstream are the ones BORE found on its reed loop,
worse: the finished Snip carries +0.43 to +0.53 of DC on a 0.95 peak for
the wooden voices; the classifier files the defaults PERC (CELLO,
CONTRABASS) or SNARE (ERHU, SARANGI, GURDY) against the documents'
`drumClassFor` promise of TONAL, and files DECAY 1 LOOP only because a
3.5 s clip trips the 1.5 s length rule (`Classifier.kt:72`) before any
spectrum is read (empirical §Promised vs rendered).

### The reference model, on the house's primitives

The spike built STK's `Bowed::tick` (`stk_Bowed.h:104-126`) on two rings
of the shape `Strings.Loop` has — an integer delay plus a first-order
Jaffe–Smith allpass for the fraction (`Strings.kt:190-192`'s form) —
with the total budgeted by `Strings.tune`'s closed form for the bridge
one-pole's phase (`Strings.kt:113-117`; read back from `tune()` and
agreeing with the closed form to four decimals in every row, spike table
0). Every constant is STK's, fetched: reflection 0.95, offset 0.001, ρ
clamped to [0.01, 0.98], β 0.127236, `v_bow = 0.03 + 0.2·amplitude`,
`slope = 5 − 4·pressure`, `ρ(Δv) = (|slope·(Δv + 0.001)| + 0.75)^−4`
(`stk_Bowed.cpp:48, :56, :72, :116, :155`; `stk_BowTable.h:26, :86-96`).
A second fetched witness, the Faust physical- modelling library's bowed
string, is the same model line for line (physics §2b). Two things the
brief did not say had to be set before it played the textbook note
(spike §Headline):

1. **The bridge filter's corner, not its pole.** STK's `setPole(0.75 −
   0.2·22050/rate)` is not rate-invariant: the same formula at 176.4 kHz
   is a 9028 Hz corner, a third of the loss STK's 44.1 kHz users hear
   (3024 Hz), and with a third of the loss the memoryless table lets the
   Helmholtz corner trigger two to four slips per period at every
   pressure. At 3023.6 Hz the string plays one slip per period. The
   house expresses the filter as a corner (`Dsp.OnePole.lp(x, hz)`), so
   the design's constant is `BRIDGE_HZ = 3023.6` and rate-invariant.
2. **Pressure must sit in the measured single-slip region.** At STK's
   default pressure 0.5 and amplitude 0.5 the string is double-slip (an
   octave-dominant spectrum, h2 +8 dB above h1); at pressure 0.9 it is
   Helmholtz motion by every number.

**What single-slip Helmholtz motion measures as** (spike (3), 130.81 Hz
and 220 Hz, pressure 0.9, the raw wave arriving at the bridge): h2…h4 at
−5.9 / −9.3 / −11.6 dB re h1 against an ideal sawtooth's −6.02 / −9.54 /
−12.04; a least-squares slope of −6.5 to −6.8 dB per octave; a flyback
five times faster than the ramp (asymmetry 0.21); the string stuck for
96–97 % of the period; exactly 1.0 slips per period on the bow-point
tap; a notch at h8 (−23 dB) where the bow position puts it (1/β = 7.86).
Its steady mean is ≤ 0.003 — the two inverting terminations keep a
two-segment string zero-mean with no blocker.

**Pitch** (spike (2)), at pressure 0.5, β 0.127, by autocorrelation on
the raw wave (`FineTuning.measuredHz` on the finished Snip reads +2.1 /
−0.4 / 0.0 / +2.7 / +12.1 at tune's budget):

| f0 | 65.41 | 130.81 | 220 | 440 | 880 |
|---|---|---|---|---|---|
| cents, `tune`'s budget as is | +0.4 | +1.5 | +2.8 | +2.9 | **+12.1** |
| cents, budget at 0.85 of the filter delay | −0.5 | −0.2 | −0.1 | −2.8 | +0.9 |

The residual is real and explained: `tune` subtracts the one-pole's
phase delay *at f0* (8.79 samples), which is what a sinusoid sees; a
bowed string's period is set by the arrival of the Helmholtz corner, a
step whose threshold crossing through a one-pole with τ ≈ 9.3 samples
comes earlier than the sinusoidal phase delay — so the budget takes
about 1.2 samples too many out of the loop, a fixed number of samples
that is 1.5 cents of a 1340-sample period and 12 of a 192-sample one
(spike iteration 5). It is amplitude-dependent by nature (the crossing
time depends on threshold over step), so it is a **measured per-voice
correction** pinned as a constant with its measurement in the KDoc —
BORE's cone-blocker and `Dsp.Ladder`'s precedent — never a closed form.
The Python replica on STK's own crude `−4` budget read −4 to −6 cents
flat at the low notes (dsp F31); the house's exact budget turns that
into a small sharp residual with one knob to pin.

**The loss × force map** (spike iteration 4; 130.81 Hz, amplitude 0.5,
β 0.127; cells are slips per period / slope dB per octave / pitch cents):

| bridge corner | p 0.3 | p 0.5 | p 0.7 | p 0.9 | p 1.0 |
|---|---|---|---|---|---|
| 1500 Hz | 2.0 / −6.8 / +2.7 | **1.0** / −8.4 / +2.2 | 1.0 / −6.8 / +2.2 | 1.0 / −6.2 / +4.0 | 1.0 / −6.1 / +4.5 |
| 3024 Hz | 2.0 / −5.9 / +1.5 | 2.0 / −4.7 / +1.5 | **1.0** / −7.4 / +1.1 | 1.0 / −6.7 / +1.6 | 1.0 / −6.1 / +1.7 |
| 6000 Hz | 4.0 / −2.6 / +0.6 | 3.0 / −1.0 / +0.4 | 3.0 / −1.3 / +0.4 | 2.0 / −1.2 / +0.3 | 2.0 / −0.3 / +0.2 |
| 9028 Hz (STK's pole at 176.4 kHz) | 4.0 / +0.2 / +0.3 | 3.0 / −1.0 / +0.2 | 3.0 / −1.6 / +0.3 | 2.0 / −1.8 / +0.2 | 2.0 / −0.2 / +0.1 |

Single slip needs `|H(f0)| ≤ 0.99907` (a corner at or below about 3 kHz)
and pressure ≥ 0.65–0.7 at amplitude 0.5, from 0.5 at 1500 Hz, from 0.9
at amplitude 0.8; above 4.5 kHz it never happens at amplitude 0.5. The
pitch error tracks the filter delay across rows (+0.3 c at 2.6 samples,
+1.5 at 8.8, +4.6 at 27.4) — the residual above, seen from the side.

**The Schelleng rows** (spike (4); 130.81 Hz, β 0.127, pressure swept
0–1 in 0.05 steps, the last 300 ms classified): every one of 63 cells is
PERIODIC at f0 and finite — no cell is silent, DC, period-doubled or
aperiodic. What changes is the number of slips: at amplitude 0.5 the
single-slip cells are pressure 0.65–0.75 and 0.90–1.00; at 0.2 they are
0.10–0.40, 0.60–0.80 and 0.90–1.00; at 0.8 they are 0.85–0.90 and 1.00.
In the multiple-slip cells h2 runs from −5 to **+26 dB** re h1 — the
octave dominates and the note reads as f0 only by autocorrelation. So
**"does hard pressure growl?" — not in this model**: hard pressure
raises the level (peak 0.29 → 0.65 at amplitude 0.5) and, past the
threshold, buys the single slip; a memoryless friction table does not
period-double. The documents' "subharmonic bifurcation" would need a
different friction law (a thermal or hysteretic bow, or a rosin-
temperature state — physics §4, out of scope) and is a gate question
about whether the *multi-slip* region is a sound anyone wants, not a
knob. Pressure 0 is not "bow up": the table still acts at slope 5, so
the engine has to lift the bow explicitly (`stk_Bowed.cpp:153-154`'s `bowDown_`).

**Bow position** (spike (6); 220 Hz): brightness rises toward the
bridge — raw centroid 1327 → 2623 Hz over β 0.4 → 0.05 at pressure 0.5,
1544 → 2532 at 0.9 — and the position notch sits where a comb puts it,
but β is not a comb: it moves the regime. β 0.05 is three to four slips
at both pressures (the bow too close to the bridge for this force), β
0.2 is a two-slip hole with h2 +24 to +32 dB, and the single-slip cells
are 0.08–0.127 at pressure 0.9 and 0.3–0.4 at pressure 0.5. A position
axis has to be mapped onto measured cells or fixed per voice (STK fixes
0.127); a position *comb* on one ring would give the notches without
the physics.

**Onset** (spike (5); 20 ms velocity ramp): time to 90 % of steady RMS
is 325–420 ms at C2 and E1 and 160–245 ms at A3 (half level in 10–165
ms) — a 0.95-loss string builds up over tens of periods — 21–27 at C2,
35–54 at A3, so the count itself moves with pitch, which is why test 4
prints both units. The documents' "30–60 ms for the contrabass to
stabilise" is what the single-ring relay does in reaching its chatter
level, not what a string does; the 10–15 ms "to 50 %" cells at hard
pressure are the first multiple-slip burst, not the note. This is the
finding that matters most for a beatmaker's stab, where the attack is
the sound ("Macros", BOW).

**Release** (spike (7); 130.81 Hz, 1 s hold, 50 ms ramp of the bow
velocity to zero): with the bow *lifted* after the ramp (STK's
`bowDown_ = false`) the string rings down at the formula's slope —
t60 900 ms measured against 1011 predicted from `−60 / (20·log10(0.95 ·
|H(f0)|))` periods; with the bow *resting* on the string at zero
velocity the table sits at ρ 0.98 near Δv = 0, `v_new ≈ −0.98·v_string`,
the string is stopped at the bow, splits into a 0.127 and a 0.873
string, and what rings for 1.8 s is that. So a release must lift the
bow, which the documents' loop cannot express (it has no bow state), and
a DECAY, if the product wants one beside HOLD, works on the reflection
and the bridge corner the way `Strings.damping` does (`Strings.kt:49`).

**Robustness** (spike (10)): pressure 1.0 with amplitude 1.0 — the
addendum's HEAVY CRUNCH corner — is bounded (raw peak 1.11) and
periodic, two slips with a flat spectrum at ρmax 0.98 and a clean
sawtooth at ρmax 1.0 (a clamp the gate decides; "Architecture"); the
bow lifted renders exact silence, 0.000000, no DC; E1 (41.2 Hz, a
4,273-sample ring) speaks in tune (+0.2 c) with four slips at pressure
0.5; 1 % noise on the bow velocity changes nothing measurable; zero
non-finite samples in about 400 renders across the spike's five runs.

**Cost** (spike (8)): 9.9 ms per rendered second for the bare two-ring
loop at 176.4 kHz, against BORE's spike at 26–49 and FORK's 88 — a
two-ring, one-`pow` inner loop; the finished chain and a body add what
they add to every melodic engine.

### One ring or two

The question the design hinges on, asked of the measurement rather than
argued: can a bow ride ONE `Strings.Loop` ring plus the junction hook
BORE proposes (`reflected()`/`inject()`), or does it need two segments?
The spike built every single-ring variant beside the two-segment model
(spike (9); dsp F30–F33 predicted the DC park and the bridge node, and
the spike confirmed both and measured the other two variants):

| Single-ring variant | What it is | Measured at 130.81 Hz |
|---|---|---|
| one full-period ring, fb +0.95 | a two-ended string folded into one ring (two inversions cancel) — and literally `Strings.Loop(…, fb = 0.95)` driven through `next()` | **parks at DC = v_bow** (0.1299 exactly, on the mirror ring and on `Strings.Loop` to the digit): the ring fills with the stick velocity, Δv → 0.05·v_bow, ρ → 0.98, and nothing ever forces a slip because there is no inverting return to drive Δv large |
| one full-period ring, fb −0.95 | the documents' literal sign | plays **an octave down**: f0/2 at +47 dB re f0 — a full-period ring with one inversion resonates at odd multiples of f0/2 |
| one full-period ring, fb +0.95, BORE's in-loop DC blocker at f0/25 | BORE's cone shape with a bow table for a reed table | speaks, one slip, slope −8.3 — but **9–10 cents flat** with the blocker's lead already budgeted, only in islands of the pressure axis (amplitude 0.5: pressure ≤ 0.05 and 0.50–0.65; DC from 0.75), a knife-edge corner (f0/10 → −25 c, f0/50 → stuck), centroid 838–979 Hz against the two-segment model's 1471–1916 because it has no bow position: a bow whose only return path is a full period away, a topology with no physical position |
| one half-period ring, fb −0.95, v_string doubled | the bow at the string's midpoint (β = 0.5) reduced by symmetry — BORE's HOLLOW shape | robust in all 63 Schelleng cells and 15 boundedness cells, one slip everywhere — and **a clarinet**: h2 at −35 to −40 dB, no brightness axis, a weak f0/2 at −14 to −19 dB across half the cells, +3.5 c rising to +17.8 c at 880 because the filter is charged twice per period, half the level |

Against the same yardsticks the two-segment model is bounded in every
cell, in tune, single-slip Helmholtz in the measured region with the
textbook sawtooth, brightness that follows β, periodic at f0 across the
whole pressure axis, a free ring-down at the formula's slope once the
bow lifts, 9.9 ms/s. The physics says why the single ring fails (dsp
F30): the bow point splits the string; the string's velocity under the
bow is the *sum* of the two waves arriving from the bridge side and the
nut side, which differ by each segment's own round trip; the bow point
is otherwise transparent — what arrives from one side continues to the
other. A single ring carries one return time and one wave, so it is
either the β = 0 case (a bow at the bridge — a velocity node, which
cannot be driven: AC 0.0005 in the Python replica) or a model with no
transmission path at all, which parks at DC. **What one ring loses is
not brightness; it loses the note. ARCO is built on two rings.**

## Against the fleet

| Engine or effect | Why ARCO is not it | What it lends |
|---|---|---|
| **PLUCK / SILK + `Strings`** | Every `Strings` client is struck once: `pluck` feeds an exciter as *input* for its length and then zeros (`Strings.kt:524-531`), `course` sums N such loops (`:592-605`), OUD and GUZHENG drive `Loop.next(exc[i] or 0)`. `Loop.next(x)` is `x + fb * yy` with the returning wave computed and consumed inside the method (`:432-476`) and `history` private; a bow needs the returning wave *before* it decides what to write, from *two* segments. The `Exciter` typealias (`:31`) does not fit either: an exciter returns a buffer to feed the loop, a bow is a function of the loop's own state (fleet §1) | `tune`'s budget (`:69-194`: the lp phase `:113-117`, the allpass `:190-192`, the `MIN_LOOP_SAMPLES` require `:181-189`), a `fb` that is a plain Float so a negative reflection is expressible (`:43`; `fb = −0.95` is STK's bridge), `Loop.retune` for vibrato as a pitch envelope (`:493`), `Loop.gain` (`:510`), `trimToDecay` (`:636`), `bodyRing` (`:722`), and the precedent to copy: share the toolkit, take a new name, land the `Strings` change first with the frozen-grid proof (`silk spec:68-103`; `StringsTest`) |
| **SITAR's jawari; SANTUR's WASH; SITAR's tarab** | The jawari acts on the loop's own swing inside the feedback, referenced to a full swing so "the same drive buzzes the same on every note" (`Strings.kt:386-393, :467-468`); the documents' chien is a two-sided threshold on the *finished* buffer at an absolute 0.35 — the MELLO-saturation mistake (fleet §SITAR's jawari). WASH is a resonator bank of `Modes.fixed` rows built from the scale's degrees and rung through `bodyRing` a second time after the body (`Silk.kt:415-432`) — sines, not strings. `Pluck.sympathetic` is strings fed continuously by the loop's output, following the note, private (`Pluck.kt:880-904`) | for SARANGI's tarab the *continuous* drive both already have, and the choice ("Voices", SARANGI): WASH's bank is callable today, DC-free and RMS-matched; the sitar's loops are the closer mechanism (strings with partials, following the note) and would be lifted into `Strings` as their own PR, the "third caller shape" its KDoc names (`Pluck.kt:864-868`; fleet §4). Never `Pluck.ks` one-shots |
| **TERRA's `applyBuzz`** | A strike engine, drum path out. | The chien's real house relative: `if (|s| > BUZZ_THRESHOLD) s += (|s| − thr) · noise · amount · BUZZ_GAIN` after the resonator (`Terra.kt:284-285, :491-502`) — the documents' chien is this with a square, no noise and a one-pole. A BUZZ macro is that function's shape, thresholded relative to the render's own level (fleet §TERRA) |
| **Modes** | Strike-shaped (`Modes.kt:51-83`); the documents ring the drumhead table at a fixed 293/130 Hz "fundamental" as an erhu — a shape, not a soundbox (physics F11) | `Modes.fixed(hz, gain, t60)` for an absolute body resonance (`:202`), t60 in seconds because a fixed Q ties ring time to pitch (BORE, Modes row); the sourced-not-recalled rule (`:121-125`) that forbids every body number in either document until a source lands |
| **FORK** | A strike into modes read by a pickup *after* the resonator. | The melodic output chain (`Fork.kt:465-468`), `settled` (`:260`), `scramble` around presets (`:246`), `midiFor` through `Keys.midiHz` (`:256-258`), the velocity rule (an override only after a monotonic sweep), no PUNCH on a melodic engine (`fork spec:305`), and the shape of this document |
| **SIREN** (HOLD, LOOP, `landingChain`, the SURFACE) | An oscillator under a gate, not a loop. | Everything the documents' DECAY is trying to be: HOLD (`Siren.kt:74-76`), the LOOP top step (`LOOP_THRESHOLD 0.99`, `:83`; `isLoop`, `:179`; `holdSeconds`, `:190`), the loop plan of whole periods with the pitch fitted (`planLoop`, `:339`; `renderLoop`, `:444`; `bestCut`, `:425`), SCRAMBLE capped short of LOOP (`:84`), the honest predictor (`:176`) where the documents' `decay > 0.65 → LOOP` is a guess that disagrees with the classifier's 1.5 s line for DECAY in (0.65, 0.679] (api C2), and landing a rack effect by recipe (`landingChain`, `:128`) |
| **RESIN held / drone** | The held path stops at the ladder's self-oscillation threshold (`Resin.kt:125-132`); a bow *is* a self-oscillator — the house refusing exactly this is the risk row in "The reuse map" (fleet §2) | `Keys.planLoop` rounding K periods to whole frames (`Keys.kt:169`), `seamError`/`requireSeam` with the 1e-3 bar (`:147, :188-206`), one retry with a longer settle and never a crossfade (`:252-258`); the drone as a grid recipe (`ResinDrone.kt`, `SirenDrone.kt`) for a bowed pedal in R3 |
| **Keys** | Every held instrument returns a `KeyNote` (`Keys.kt:17`) and `require`s its range by name (`:225-227`); the documents' `Keys.arco` returns a `Snip` and `coerceIn`s. | The organ's rule — no scanner and no rotor on the loop path because "a rotor a third of the way through a turn at the seam is a click" (`:79-86`): vibrato and any ensemble are off in a LOOP or held render. Layers velocity-true on keys (`fork spec:260-266`) |
| **VOX** (the sustained group; stereo) | Continuous formant synthesis over a glottal buzz with no loop; the sustained group's other member with a hold. | How the house makes and levels a stereo Snip when it does — per-voice `channelsFor`, `Dsp.decimate(…, channels)`, `Dsp.levelTo(…, channels)` (`Vox.kt:177, :540-556`) — the chain the addendum's stereo path uses none of; baked vibrato with a delay and a rise; the group's own audition gate |
| **TONEWHEEL's scanner; TAPE** | One tap, one 6.8 Hz sine, 0.9 ms, mono, 50/50 with dry, always on (`Tonewheel.kt:60-63`); TAPE is two LFOs on one 5 ms line, per channel, **100 % wet, no dry mix** and so no comb, peak-matched (`Tape.kt:35-37`; dsp F25). Neither is three-phase or stereo. | `Tonewheel.tap` — a fractional read off a circular line with the integer wrapped first, so the read never indexes one past the end (`Tonewheel.kt:90-98`), the exact hazard the addendum's `readInterpolated` has; TAPE's per-channel idiom and its no-dry rule, for ENSEMBLE |
| **The rack** (FxChain, PHASE, WOBBLE, MOTION, DUB) | No section is an ensemble (`grep -rni 'chorus\|ensemble' synth/src/main` finds only TONEWHEEL's scanner, its FULL CHORUS preset and a comment on PLUCK's course detune (`Pluck.kt:277`)). PHASE is four swept allpasses summed with dry; WOBBLE a tempo-synced filter sweep; MOTION the tape stop; DUB generation loss (fleet §The rack sections) | The fixed order and its own argument for where a modulation section sits (`FxChain.kt:9-11`; `Phase.kt:14-16`: "after TAPE and before ECHO"); the section contract (`Section(name, macros, get, with, run, stage)`); the stereo-identity clause every section obeys unless listed with its measured divergence (`FxTest.kt:62-69, :280-291`) — ENSEMBLE is the first section whose *purpose* is L ≠ R |
| **VELVET "strings"** | The roadmap lists "strings" under VELVET's uses (`docs/SYNTH_ROADMAP.md:142-147`) meaning a synth-string pad: saws under a resonant filter. | The addendum's "divide-down saw" *is* VELVET's and RESIN's private `saw` (`Velvet.kt:118`, `Resin.kt:146`); a string-machine *source* is a VELVET or RESIN preset family and zero new DSP (product §2a) |
| **TERRA (method)** | The other external physical spec; drum path out. | The reduction: a topology table became per-voice numbers on shared functions, and an exciter with no user was deferred rather than built (MICRO_FLOCK, `Terra.kt:31-34`). For ARCO: five voices are one loop shape plus per-voice numbers; ERHU's "two strings a fifth apart" and "fretless air-stop" have no code; GURDY's wheel is not a different exciter in a model whose bow never reverses (every voice here is a wheel) — its drone and chien are post-processing (product §3) |
| **GLINT** | No loop by design (`Glint.kt:10-29`); a bow is nothing but the loop. | Nothing. |
| **TIDE** | An oscillator under a fold. | `Tide.bandLimit` at the oversampled rate (`Tide.kt:533`) and the 45 dB between-harmonics bar (`ForkTest.kt:87`) |
| **BORE** (design only) | A valve at the *end* of a bore: one ring, junction at its one point, `roundTrip` 1.0 or 0.5. A bow sits *inside* the string at β: two segments. | Transfers verbatim: dry render with the rack landed by recipe; HOLD with the LOOP top step; the melodic chain; velocity registered only after a sweep; names off every surface; `Modes.fixed` rows through `bodyRing` for formants, never inside the loop; vibrato baked, bounded, off in the LOOP render; the R0 prove-it-harmless-first shape; the retune-then-seam plan for a limit-cycle pitch. Of its three R0 additions, ARCO needs **#1 with a fractional `roundTrip`** and **#3** (`reflected()`/`inject()`), and not **#2** (the two-segment loop is zero-mean) — so **ARCO's R0 is BORE's R0**, and whichever lands first carries the other ("Architecture") |

So ARCO is worth building for exactly one thing none of that does: **a
friction junction between two string segments, sustaining a note from a
constant velocity** — where the pitch, the speaking threshold, the
brightness and the number of slips per period all fall out of one
nonlinear limit cycle, and three of the four sounds product §1 says no
engine makes fall out with it — the stick-slip attack (a note that
starts by grabbing), brightness that follows bow pressure, a fretless
line with a body under a finger (the fourth, the growl, is not in this
model: "The Schelleng rows") — plus a pedal that is a string, which
product §1 files under the pad's stopgaps. "The same effect, from the
physics", FORK's argument.

## The string-machine question

The addendum's parts, one by one, against the rack (fleet §5; dsp
F17–F25; product §2):

| Addendum part | The claim | The code | The tree | Verdict |
|---|---|---|---|---|
| the divide-down saw | "phase-locked … taps the fundamental of the physical waveguide"; "harmonic reinforcement" | a free phase accumulator at the macro's nominal f0, mixed into the *output* buffer after the delay-line write, never fed back; at SOLINA 1 it is 0.5·pressure of saw under 0.6 of string, un-normalised, so the balance depends on the loop's own level (dsp F17) | the same one-line `saw` VELVET, RESIN, FATHOM and ResinDrone each carry privately | an oscillator under a physical string — a product novelty, not DSP; engine-side if kept at all, because it needs f0 and the bow envelope; honestly named |
| the formants | cello 320 Hz + a −12 dB shelf, viola 780/1800, violin 2600 with a bass cut — "passive RLC curves" | one chain, `peaking(380, +6 dB, Q 1.2)` → `peaking(2400, +5 dB, Q 1.5)`, on every voice, blended `raw·(1−0.5a) + shaped·0.8a` (dsp F18) | dropped from the section, carried by the recipe: ENSEMBLE stays a modulation section with no tone stage (one section, one job — PHASE and TAPE carry no EQ either); a register is the rack's EQ on the recipe, route (b)'s landing table pairing ENSEMBLE with an EQ setting per preset (DARK STRINGS a low shelf, THIN STRINGS a bass cut), route (a) taking it from the pad sheet's EQ chip | rack material; every number recalled, none ships (physics §7) |
| the tri-phase chorus | three BBD lines at 120°, "net pitch deviation sums to zero … eliminates seasick wobbling" | three lines written with the *same* sample (one line, three taps); each tap swings ±17.6 c at 0.58 Hz and ±28.4 c at 5.85 Hz; L and R each carry two taps, so per channel nothing cancels — measured sd 32/28 c per window (dsp F19–F20; empirical §F1); the dry/wet crossfade is a comb with notches at 66.7 / 200 / 333 Hz that the modulation smears (dsp F21; empirical §F2: 9.3 dB at the first peak/notch pair at amount 0.5) | TAPE's per-channel modulated read, 100 % wet with no comb; TONEWHEEL's wrap-safe tap | **an ordinary chorus of ordinary depth** (TAPE's wow at full is ±38.5 c, the scanner ±33 — same arithmetic); its three-tap arrangement and stereo assignment are new, small, and rack-shaped |
| the stereo sum | `L = (t0+t1)/√2, R = (t1+t2)/√2` | t1 doubled in the fold: `(L+R)/2` loses 1.68–1.85 dB against the channels at amounts 0.85–0.95, the presets' range (dsp F22; empirical §F1–F2), and then the mono-only `bandLimit` folds the stereo to −43 dB anyway (empirical §F4) | VOX's per-channel chain; `KeyNote` export is mono (`Keys.kt:250, :304`) | a stereo *engine* forecloses MAKE INSTRUMENT; width is the rack's after U4 |
| the 6.5 kHz one-pole "BBD clock loss" | "analog warmth" | a constant with no source; a bucket brigade's bandwidth follows its clock and the era's units ran a compander — none modelled (dsp F25, recalled) | TAPE's AGE | a tone control |
| "not an insert effect; directly coupled" | — | nothing returns to the loop; `SolinaEnsemble.process` is a `FloatArray → FloatArray` pass over the finished body buffer — exactly the rack's shape | the CRUNCH rule, applied to WOBBLE for FORK (`fork spec:88`), to ECHO for SIREN (`siren spec`, the ECHO row) and to TAPE for BORE | **prose** |

The CRUNCH rule — an effect that improves more than one engine belongs
in the rack, where it "works on captured snips exactly as on synthesized
ones" (`README.md:162-167`) — has been applied three times to exactly
this shape, and the addendum gives no reason to make an exception:
nothing is coupled, a held or LOOP render must be dry and un-modulated
for its seam anyway (`Keys.kt:79-86`; `Siren.kt:121-124`), and a stereo
engine forecloses the keygroup. **Verdict.** ARCO renders mono and dry.
The string machine lands as **ENSEMBLE, a rack section** ("ENSEMBLE"
below) — three taps at 120° on one line, two LFOs, 100 % wet, stereo —
usable on a captured string, a VELVET saw and a VOX choir alike; and the
1970s pad itself is the BRASS presets VELVET and RESIN already ship,
given the ENSEMBLE chip on the pad sheet, for no new source DSP and no
plumbing at all — and, once the section's gate says that is the sound, a
STRING MACHINE preset family with the chain in a landing table beside
the roster, the way SIREN lands ECHO. It can land *before* the engine,
as its own small PR, and answers the string-machine half of the ask the
day it does (product §7, decision 10). The one thing a beatmaker loses
is a single knob that sweeps one pad from dry cello to string orchestra;
the level-matched A/B at the gate (audition item 9) is the final word on
whether that knob was ever worth a second copy of a chorus. The word
SOLINA — a product's name, by the addendum's own sentence ("the 1974
Eminent/Solina String Ensemble") — comes off every surface.

## The name

**ARCO.** Italian for "bow", the direction a score gives a string
player ("arco" after "pizzicato"); one word, a gesture-and-mechanism
word in FORK's and BORE's family; no collision in the tree (`grep
-rniw arco` over `*.kt`, `*.md` and `*.json` outside `.claude/` and
`build/` is zero — the substring hits are `…arCo…` inside other
identifiers; api §D5). Its risk is that it is not English: BOW is the
plainer word, but the speed macro is BOW and an engine called BOW with
a knob called BOW reads twice on one LCD; ROSIN is one letter from
RESIN in the same picker list (product §4). Default ARCO; a rename is
one constant (owner's decision 1). The voices are instrument names,
SILK's precedent (OUD, GUZHENG, SANTUR): CELLO, ERHU, and later
CONTRABASS, SARANGI, and the hurdy-gurdy as its mechanism, **WHEEL** —
BORE renamed GUANZI to HOLLOW the same way.

**Off every surface**: SOLINA, EMINENT, ARP, the "74", TCA350, MOSTEK,
SGS (physics §6, tagged recalled, and the addendum's own words). The
blocklist (`PresetTestSupport.kt:27-39`) grows the way it grew for
FORK's makers (`:36-38`), with the terms checked against all 820
shipped preset names (api §D4): `solina` and `eminent` (0 collisions),
`string\s*ensemble` (0), and a **word-bounded** `\barp(?!eggi)` — the
bare substring `arp` would flag sixteen shipped presets (DEEP HARP,
HARP DOUBLE, LOW HARP, MUTED HARP, SOFT HARP, WARP and ten SHARP …),
the BORE `mello(?!w)` precedent; `\b` is safe here because the hole
`PresetTestSupport.kt:15-26` describes is a digit running into a letter,
and a leading `\b` before a letter fires at a space or the start. `bbd`
is a component class, not a brand, and stays out; `python` is not a
maker. The near-miss list for the test: "SOLINA CELLO 74", "ARP
STRINGS", "EMINENT 310"; the clean list: "SHARP BOW", "HARP DOUBLE",
"MEDIEVAL BOW". "SILK PYTHON" is not a trademark problem but a
product-confusion one: a preset called SILK-anything on another engine
reads, on the phone and in a filename like `ARCO_ERHU_01_SILK_PYTHON.wav`,
as if it belonged to SILK. Two more laws the house applies by hand, for
the same reason: no engine's name inside a preset name, and no rack
section's (HEAVY CRUNCH names CRUNCH; SOFT SWELL would name SWELL);
macro words are fine — RESIN ships DEEP CREAM and SOLO CREAM. The
near-miss list grows to "SOLINA CELLO 74", "ARP STRINGS", "EMINENT 310",
"string ensemble", "Solina-ish", "ARPSTRING"; the clean list to "SHARP
BOW", "HARP DOUBLE", "MEDIEVAL BOW", "ARPEGGIO PAD", "WARP", "DEEP HARP"
— each checked by script against the old and new terms (rack §5).

**Preset names, eight per round-one voice, as candidates only** —
authored by ear against the built engine once Phase 0's gate is passed
(it is), and re-heard at R1's audition — the settled rule — each ≤ 14
characters, uppercase, clean of every regex term and not among the 798
distinct shipped names (rack §8.3): CELLO — SLOW BOW · SHORT STAB · DEEP
PEDAL · GRIT BOW · DRY SCRAPE · CINEMA LOW · HORSEHAIR · LONG DRAW (and
the documents' own SUITE C2 passes); ERHU — NASAL LINE · MOON FIDDLE ·
THIN SCRAPE · HIGH CRY · SLOW CRY · SNAKE FIDDLE · TWO STRING · TEA
HOUSE. Four shipped SKIN RIDE presets already end in BOW (CUT BOW, ECHO
BOW, PEAK BOW, WARM BOW, `SkinPresets.kt:236-248`), so ARCO's BOW names
are new words, not new endings; TIGHT SKIN, LOOSE SKIN and SKIN FIDDLE
are dropped because SKIN is an engine's name.

## Architecture

```
BOW  ─▶ v_bow(t) = V_voice · stroke(t)   the stroke: a ramp whose length BOW sets, an overshoot at the top of the knob, held for HOLD, a 50 ms ramp down, then the bow LIFTS and the string is stopped
GRIP ─▶ slope = 5 − 4·p, BRIDGE_HZ      p = GRIP over the voice's measured single-slip window; the bridge corner rises with GRIP inside its measured range
                                                  │
   bridge = Loop(tune(f_b, BRIDGE_HZ, rate, roundTrip = β),     fb = −0.95)   the loss and the bridge low-pass, once per period; f_b = f lowered by the pinned correction
   nut    = Loop(tune(f, PASS_HZ,   rate, roundTrip = 1 − β),      fb = −1.0)   a rigid nut: an inversion and nothing else
   per sample:
     rB = bridge.reflected();  rN = nut.reflected()          the two waves arriving under the bow, already reflected by their ends
     v  = rB + rN                                            the string's velocity under the bow
     Δv = v_bow − v
     v_new = if (bowDown) Δv · ρ(Δv) else 0                  ρ(Δv) = clamp((|slope·(Δv + 0.001)| + 0.75)^−4, 0.01, 0.98)
     nut.inject(rB + v_new);  bridge.inject(rN + v_new)      each side's wave crosses the bow to the other side, plus the bow's push
     out[n] = rN + v_new                                     the wave leaving the bow toward the bridge — STK's tap up to a pure delay; the body's input
                                                  │
   Strings.bodyRing(out, Modes.fixed rows, BODY, rate, ceiling)      identity at BODY 0; every row labelled shape until sourced
                                                  │
   Tide.bandLimit (19.5 kHz at 176.4 kHz) ─▶ Dsp.decimate ─▶ mean removed ─▶ 20 Hz high-pass ─▶ Dsp.levelTo(MELODIC_LOUDNESS_TARGET) ─▶ Dsp.fadeTail
```

Rendered at `Dsp.RATE * Dsp.OVERSAMPLE` and decimated, like every engine,
and not optionally: a friction table's kink mints harmonics up to the
ring's bandwidth (spike (3): h10 at −22 dB in single-slip motion, raw
centroid 1.5–1.9 kHz). Mono in round one. The whole loop is STK's
`Bowed::tick` (`stk_Bowed.h:104-126`) with the house's ring, allpass,
one-pole and budget in place of STK's `DelayL`, `OnePole` and its "−4
samples, approximate filter delay" (`stk_Bowed.cpp:100`).

### The bow

**Two `Strings.Loop`s, wired by hand, packaged as `Strings.Bow`.** The
spike's recommendation (spike (9)) and the fleet's (fleet §3d) agree on
the end state and differ on the road; this document takes the spike's
road because it needs less of `Strings.kt`. Each segment is a `Loop`
constructed from its own `tune()` call with a *fractional* `roundTrip`:
the bridge segment at `β` with `loopHz = BRIDGE_HZ`, the nut segment at
`1 − β` with `loopHz` at `tune`'s own cap (`min(loopHz, 0.45·rate)`,
`Strings.kt:113`). The budget then works itself out. Each call's `exact`
is `(rate/f)·roundTrip − filterDelay − 0.5` (`Strings.kt:164` with
BORE's factor), so the two exacts sum to `rate/f − filterDelay_bridge −
filterDelay_nut − 1.0`, and the round trip through both rings, both
one-poles and both two-tap averages is exactly one period. The nut's
one-pole at the cap is transparent — computed here from `Dsp.kt:417`'s
coefficient at 176.4 kHz: magnitude 0.996 at 10 kHz and 0.984 at 20 kHz,
a lag of 0.06 samples that the budget charges like any other — which
answers the fleet's objection that two literal `Loop`s would pay the
loop filter and its loss twice (fleet §3e): they pay the *bridge* filter
and the 0.95 once, the nut's nothing that matters, and the spike's own
Model A used a single tap per segment
and attributes its residual to the corner effect, not the tap (spike,
recommendation 1; BORE's spike found the two-tap average "made no
measurable difference to tuning", `bore spec:875-877`). The `Exciter`
typealias (`Strings.kt:31`) is not the shape: an exciter hands the loop
a buffer; a bow is a function of the loop's own state, sample by sample.

**What R0 adds to `Strings.kt`** — and it is BORE's R0
(`2026-09-28-bore-woodwind-engine-design.md:849-873`), two of its three
additions, landed first as their own PR and proven by `StringsTest`'s
frozen-copy grid (`StringsTest.kt:23-40`: PLUCK's loop rendered at every
rate, pitch and damping and compared sample for sample against a frozen
copy, 384 cases; one changed sample fails):

1. `tune(…, roundTrip: Double = 1.0)`: `Strings.kt:164` becomes `exact =
   (rate / freq) * roundTrip − filterDelay − stiffDelay − dcDelay −
   dispersionDelay − 0.5`; the filter phase is still evaluated at `w =
   2π·freq/rate` (`:115`), the resonance that matters; `Loop` stores the
   factor and `retune` (`:493`) passes it back to `tune`, or a retuned
   segment would re-solve at the full period (BORE's own note). BORE
   needs it at 1.0 and 0.5; ARCO needs it **fractional** (0.127236 and
   0.872764), which the type already allows — the KDoc says so, and a
   `StringsTest` row proves it: two bare Loops of β·T and (1−β)·T with
   `fb −1` and `−1`, closed by hand, ring at f within 5 cents.
2. The junction split: `next(x)` becomes `inject(x + reflected())`, with
   `reflected(): Float` everything from the tap through `fb * yy`
   (`:436-472`, cached) and `inject(y: Float)` the write
   (`:474-475`) — a pure reordering, so the frozen grid is the proof it
   changed nothing. This is what a bow needs that `next(x)` cannot give
   (fleet §3a): the returning wave *before* the write, from two rings,
   and the freedom to write each ring what the *other* ring returned. The
   spike's "B on `Strings.Loop`" row is the proof that the lag-charged
   workaround through `next(x)` is faithful (it reproduced the single
   ring's DC fixed point to the digit) and useless as a junction.
3. **Not** BORE's #2 (`dcBlock`/`dcHz`): the two-segment loop's steady
   mean is at most 0.003 in every spike row — the nut's inversion keeps
   it zero-mean — and only the single-ring bow needed a blocker, which
   is the model not to build.

So **if BORE's R0 lands first, ARCO's R0 is one KDoc sentence and two
tests** (the budget identity and the bare-Loop ring, "Testing"); if
ARCO's lands first, BORE inherits the same two additions. That shared PR
is the integration insight of this brainstorm: the fleet's two sustained
loops are built from one change to the toolkit. Whichever engine lands
R0 writes the one plan file for it
(`docs/superpowers/plans/2026-09-xx-strings-r0.md`, SILK 1a's shape)
and, in the same PR, edits the *other* design's R0 paragraph and effort
row to say what remains — for BORE, addition #2 alone (its DC blocker,
~40 lines and its own test) plus the +3 line-number drift fleet F18
found in its `Strings.kt` cites; for ARCO, one KDoc sentence and two
tests. Order: PR-E1 needs neither and may go first; R0 precedes any
engine code from either design; ARCO R1 and BORE R1 are then
independent.

**`Strings.Bow`** (R1, in `Strings.kt`, the shared toolkit — reusable
for a second bowed string, the hurdy-gurdy's bourdon and the erhu's
second string; the SILK precedent is share the toolkit, take a new name)
holds the two Loops, the table and the junction above, and adds four
things the Loops do not have:

- **The split, and the correction.** `Bow(f, β, bridgeHz, share, rate)`
  calls `tune` twice. The bridge segment is then lengthened by `(1 −
  share)·filterDelay` — the corner effect the spike measured (iteration
  5): `tune` subtracts the one-pole's phase delay at f0, which is what a
  sinusoid sees, but a bowed string's period is set by the arrival of
  the Helmholtz corner, a step whose threshold crossing through the
  one-pole comes about 1.2 samples earlier, so the budget takes a fixed
  1.2 samples too many out of the loop: 1.5 cents at C3, 12 at A5. With
  `share = 0.85` all ten pitch cells sit within ±2.8 cents; with 1.0 the
  top note is +12.1. The share is a **per-voice constant pinned with its
  measurement in the KDoc** (`Dsp.Ladder`'s precedent, `Dsp.kt:291-313`;
  BORE's cone), amplitude-dependent by nature and so never a closed
  form. It costs no new `Strings` surface: the filter delay is read back
  from a first `tune` as `(rate/f)·β − 0.5 − exact` (the spike's own
  read-back, table 0, agreeing with the closed form to four decimals),
  and a `Loop` is lengthened by tuning it lower, so the bridge Loop is
  built at `f_b = f / (1 + c·f/(β·rate))` with `c = (1 −
  share)·filterDelay` — at C3 the bridge segment is tuned as if for
  129.81 Hz (toolkit §2.5); `Bow.retune` carries the same rule. Two
  `tune()` calls, one per segment, are the whole budget, and it is
  exact: each call charges its own filter's phase and its own two-tap
  0.5, the two `exact`s sum to `T − τ_bridge − τ_nut − 1.0` to 1e-9 at
  every root (toolkit §2.3's table), and a one-`tune` split by hand
  would leave the nut's stages uncharged — 0.563 samples short, +0.7 c
  at C3 and +4.9 c at 880 Hz. One number the spike's tables do not carry
  over: charging the bridge filter to the bridge segment makes β the
  *geometric* bow position, so the spike's cells at a nominal 0.127 sit
  at β ≈ 0.133 at C3 (toolkit §2.8) — the per-voice β is set from the
  loss × force map re-run on the built `Bow`, starting from 0.133, not
  copied. And `tune`'s `require` now guards each segment: `exact_b = β·T
  − τ − 0.5 ≥ 2` gives a floor of β ≥ (2.5 + τ)/T that moves with GRIP's
  corner, because the bridge one-pole's delay τ is charged to the bridge
  segment: at ERHU's top note (T = 150.2 samples) β ≥ 0.072 at the 3024
  Hz corner (toolkit §2.7), 0.098 at 2000 Hz, 0.119 at 1500 Hz and 0.151
  at 1000 Hz — so at β 0.133 ERHU's `c_lo` cannot go under about 1300 Hz
  at D6, a sul-ponticello axis cannot take β under about 0.07 there, and
  a `Bow` built at every voice's top TUNE **at GRIP 0**, the lowest
  corner, is a unit test, not a discovery.
- **The table.** `ρ(Δv) = clamp((|slope·(Δv + OFFSET)| + 0.75)^−4,
  RHO_MIN, RHO_MAX)`, STK's constants fetched: `OFFSET 0.001`, `RHO_MIN
  0.01`, `RHO_MAX 0.98` (`stk_BowTable.h:26, :86-96`; `stk_Bowed.cpp:48`).
  Its geometry (physics §2a, computed): a plateau of 0.98 for `|Δv| ≤
  0.084` at slope 3 — stick, the string dragged at nearly the bow's
  speed — falling as the fourth power to the 0.01 floor at `|Δv| ≥
  0.80` — free slip; `slope = 5 − 4·p` widens the plateau from 0.051 at
  p 0 to 0.255 at p 1. `RHO_MAX` is a gate question with a measurement
  behind it: at the addendum's HEAVY CRUNCH corner (pressure 1,
  amplitude 1) 0.98 renders two slips with a flat spectrum and 1.0 a
  clean sawtooth (spike (10)); Faust's witness clamps at 1.0 with no
  floor (physics §2b). R1 ships **0.98** — every number in this document
  was measured at it, and the bow's injected power is bounded only for
  ρ < 1 — and the gate re-runs the 60-cell boundedness grid and the
  63-cell Schelleng row at 1.0, taking it only if every cell stays finite
  and the single-slip count does not fall (toolkit §2.4).
- **The bow state.** `bowDown`; `lift()` sets it false so the string
  rings free (spike (7): lifted, t60 900 ms at C3 against 1011 from the
  formula; resting, the table at ρ 0.98 near Δv 0 stops the string at
  the bow and 1.8 s of a split string rings instead). GRIP 0 is the
  softest *single-slip* pressure, never a lifted bow, and HOLD's release
  lifts.
- **`retune(f)`** re-splits both segments from one new f with both
  allpass states carried through (`Loop.retune`'s contract, `:493-501`)
  — vibrato and glides; the rings are built at the lowest pitch of any
  swing (`:408-414`'s rule). **`gain(scale)`** scales the bridge's `fb`
  only: the string's loss lives at the bridge.

Both rings start empty and `reflected()` returns nothing for a ring's
first `n + 1` samples (`Loop`'s warm-up, `:433-434`), so the junction's
first samples see `v = 0`, `Δv = v_bow·stroke(t)` and inject the table's
answer into both directions: that is the bow catching a still string,
and the spike's rings started the same way.

### The stroke and the grip

Two axes the documents did not have (physics F4: BOW was speed only,
ROSIN three things, and "pressure" the envelope). Both are mapped onto
what the spike measured, not onto the raw parameter — BORE's window
mapping for BREATH, applied twice:

- **GRIP → pressure, inside the single-slip window.** At `BRIDGE_HZ` and
  the voice's default velocity, the single-slip cells are pressure
  0.65–0.75 and 0.90–1.00 at amplitude 0.5, 0.10–0.40, 0.60–0.80 and
  0.90–1.00 at 0.2 (spike (4)); the loss × force map puts the region's
  edge at pressure ≈ 0.65 for a 3 kHz corner. GRIP's travel is that
  region — `p = lin(GRIP, p_lo(voice), p_hi(voice))` between two
  *measured* edges with a margin, so no knob position is a double-slip
  octave by accident — the knob travels the **top** island (0.90–1.00),
  the one that is single-slip at every velocity row the spike measured
  but one cell (0.95 at amplitude 0.8 reads three slips, spike (4) — the
  reason the edges are re-measured at 0.01 steps) and the one the BOW
  overshoot lands in — and whether its top may cross into the multi-slip
  region ("the raucous end", h2 up to +26 dB re h1) is the gate's
  question 6, not a default. GRIP moves a second thing with the
  pressure, rule 2's "several parameters underneath": **the bridge
  corner**, `expMap(GRIP, c_lo, 3024 Hz)` — the loss × force map is
  single-slip from 1000 to 3024 Hz at pressure ≥ 0.7, and the corner is
  the one brightness axis that keeps one slip per period (h-slope −8.4 →
  −6.7 dB per octave over that range at pressure 0.9). The corner is the
  string's loss filter, so making it rise with GRIP is a *shape*
  standing in for the corner-sharpening a memoryless table does not
  produce on its own (physics F5), and the document says so. It is also
  why the pitch correction must be a *share* of the filter's delay and
  never a fixed sample count: the delay is 27.4 samples at 1000 Hz and
  8.8 at 3024, and a fixed count would move the pitch by 3.6 c at C3 and
  32 c at ERHU's top as GRIP turns (macros §7.6) — test 3. Inside the
  window GRIP audibly gives a lighter or harder grip: a darker or
  brighter sawtooth (−8.4 to −6.1 dB per octave), a shorter or longer
  stick, and the *bite* of the attack. What it does not give is a growl
  — the raucous cells are all below the threshold or above 4.5 kHz, and
  every one is an octave-up timbre.
- **BOW → the stroke, and the sustain speed is fixed.** After `levelTo`
  the bow velocity's main physical effect, level, is gone, and its regime
  effect is to move the single-slip window under the player (spike (4):
  the window sits at 0.10–0.40 and 0.60–0.80 at amplitude 0.2, at
  0.65–0.75 and 0.90–1.00 at 0.5, at 0.85–0.90 and 1.00 at 0.8) — so a
  BOW that moved the sustain velocity would silently move GRIP's
  meaning, BORE's "partly one knob" in a milder form (dsp §12). The
  sustain velocity is therefore a **per-voice constant** (CELLO 0.13,
  the spike's amplitude 0.5 row, the one every table was measured on),
  and BOW owns what the ear keeps: the *attack*. BOW 0 is a slow bow —
  a 400 ms velocity ramp into the string's own 325–420 ms build-up at
  C2, a bowed crescendo; BOW 0.5 a plain stroke of about 60 ms (STK's
  20 ms ADSR attack is a bow-speed ramp too, `stk_Bowed.cpp:70`); BOW 1 a
  stab — 10 ms to speed with the bow biting harder than it will hold: the
  velocity starts up to 1.75× its sustain value and the pressure at the
  top of the window, both relaxing with the attack's own time constant.
  `attack = expMap(BOW, 0.40 s, 0.010 s)`, capped at 0.85 of the hold so a
  short HOLD still lands on its note (SIREN's `SWEEP_HOLD_FRACTION`,
  `Siren.kt:72`). Whether the overshoot makes a C2 stab speak in under
  100 ms is R1's first printed table — time to 90 % of steady RMS and time
  to the first window at one slip per period, in milliseconds *and* in
  periods, at overshoots of 1.0 / 1.5 / 1.75 — not a number this document
  may claim: spike (5) says the plain 20 ms ramp takes 325–420 ms, and the
  bet that a harder, faster start pumps the ring faster is Guettler's
  acceleration–force wedge for a "perfect attack" (physics §4, recalled).
  If the C2 stab still takes 300 ms, the attack's *sound* is the scratch
  and the gate decides whether that is a stab. BOW is heard in the first
  300 ms and nowhere else — a claims test. Bow *position* β is a fixed
  design constant per voice in R1 (geometric 0.133 at C3 for STK's
  nominal 0.127236, `stk_Bowed.cpp:72`; "The bow") because β moves the
  regime as much as the brightness (spike (6)), and is a sixth-slot
  candidate as a brightness axis mapped onto measured cells.

### The body

`Strings.bodyRing` (`Strings.kt:722`) on the bridge wave —
differentiated, and not for the reason PLUCK's KDoc gives: ARCO's wave
is already velocity (STK taps it undifferentiated, `stk_Bowed.h:123`;
physics F12), so the first difference is a +6 dB/oct tilt with no
physical reading, kept because it is what makes `Modes.ring`'s `1/sin θ`
onset peak cancel (`Modes.kt:65-83`, `Strings.kt:686-692`), the RMS
match then takes the tilt's level back, and the table's GAIN column can
undo the tilt per row; the KDoc says so, audition item 5 adds one clip
per body with `differentiate = false` (the hook exists,
`Strings.kt:726-727`) so the gate hears the undifferentiated drive with
its onset thump beside the tilted one, and a claims test bounds the
body's first 20 ms against its steady level either way — RMS-matched to
the string so BODY means "times the string", identity at 0 — with
`Modes.fixed(hz, gain, t60)` rows (`Modes.kt:202`; t60 in seconds, never
Q — a fixed Q ties ring time to pitch). The documents' wooden bodies as
coded, converted by SILK's own `t60 = 2.2·Q/f` (dsp F12): CELLO air 104
Hz, 0.254 s and wood 220 Hz, 0.180 s; CONTRABASS 58 Hz, 0.455 s and 110
Hz, 0.360 s — every one **labelled shape** (SILK's convention for a
number chosen by ear rather than taken from a source, `Silk.kt:436-460`)
until a citation lands, because `Modes.kt:121-125` forbids a recalled
number in a body table and none of these has a source (physics §5: a
cello's air resonance near 100 Hz and its main wood resonances around
170–220 Hz are plausible and unsourced; Jansson's KTH text, which the
house has already read for the guitar, is the nearest sourced
neighbour). A research pass in SILK's form
(`docs/superpowers/plans/2026-09-27-silk-research.md`: each value
`confirmed` only when the verifier opened the source, `computed` with
the working shown, else unused in code) runs between R1's gate and R2,
targeting the three sources physics §7 rates reachable — Woodhouse's
Euphonics pages for the cello's A0 and B1 and the regime bounds, Jansson
ch. IV–V for the nearest sourced body, Guettler's KTH thesis for the
attack — and the repositories SILK reached, for an erhu skin paper.
Until it lands every body row stays *shape*; if it finds nothing, the
rows stay shape permanently and the KDoc says so, SANTUR's placeholder
rule. Never inside the loop, never `Dsp.Biquad.bandpass` on a
differentiated drive at −54 dB (dsp F10). The skinned body is ERHU's
question ("Voices").

### The output

`Tide.bandLimit` at the oversampled rate (`Tide.kt:533`), `Dsp.decimate`
(`Dsp.kt:677`), the mean subtracted and a 20 Hz one-pole high-pass by
subtraction — FORK's "the DC goes twice" — `Dsp.levelTo(RATE,
MELODIC_LOUDNESS_TARGET)` (`:636, :507`), `Dsp.fadeTail` (`:710`). No
PUNCH; no `normalizeByFold`; no `limitPeak(1f)`. Loudness: GRIP and BOW
change tone and attack, not level, the promise `levelTo` keeps on every
melodic pad; on keys the layers are velocity-true, `Keys.fork`'s
`Dsp.normalize` (`Keys.kt:354`), never levelled per render.

## Voices

**Round one: CELLO and ERHU** — the product review's roster (product
§3): a bowed plate and a bowed skin, the sound everyone names and the
solo line the SURFACE is for. Each voice is an audition and eight
presets (`ForkPresetsTest.kt:70`), so five voices is five gates; FORK
shipped two and added a third after its gate. Both enter R1 by Phase 0's
entry rule: a contiguous GRIP window at least half the knob wide in
single-slip motion across TUNE 0–24, and in tune within 5 cents at
defaults with the pinned share.

| Voice | Physics | Root | Where the spike left it |
|---|---|---|---|
| **CELLO** | the two-segment bow, β geometric ≈ 0.133 at C3 (STK's nominal 0.127, re-measured on the built `Bow`), `BRIDGE_HZ` 3023.6; a wooden body of two `Modes.fixed` rows (shape) | **C2** (65.41 Hz, the documents' root: the bottom string, so the knob's centre is C3 where a stab or a pedal sits under a snare) | speaks and is in tune at 65.41–440 Hz (+0.4 … +2.9 c as is, −0.5 … +0.9 with share 0.85); single-slip Helmholtz at pressure ≥ 0.65; onset 325–420 ms to 90 % at C2 — the stab question is its gate |
| **ERHU** | the same bow, a skinned body — the house's MEMBRANE table (`Modes.kt:163`, five Bessel ratios) at a fixed anchor, **labelled shape**: it is an ideal drumhead in vacuo, and a python skin loaded by a central bridge over a short open tube is not one (physics F11); the gate hears it against the same string with no body and against SILK's sourced shamisen skin rows, the nearest measured relative | **D4** (293.66 Hz, the bottom string) | in tune at 220–880 Hz with the share; onset 160–245 ms at A3; the single-slip window at the top of the range is the entry rule's real test — the spike ran 880 Hz at pressure 0.5 and 0.9 for pitch only (spike (2), the share probe), never a Schelleng row |

**ERHU's body and knob, in R1** (rack §4). Three choices for the skin:
no body until the gate hears the plain string — honest, but a BODY knob
that does nothing is rule 1's dead knob, SANTUR's own reason for
shipping "one placeholder mode, not an empty table" (`Silk.kt:453-460`);
the MEMBRANE ratios at the documents' 293 Hz anchor, labelled shape — at
least a sourced *membrane* shape, RMS-matched by `bodyRing` so BODY
means "times the string", its known wrongness with a direction (the
ratios too spread, physics F11) and its anchor on the open string, so
TUNE 0 rings the body at f0 where TUNE 12 does not; or one `Modes.fixed`
row, SANTUR's placeholder shape, at an anchor off every scale root.
**This document ships the second and hears all three unlabelled at the
gate** (audition item 5), the KDoc never calling the table "the erhu's
skin"; the code cost of switching is one table. The documents' nasal
formant (1.2–2.2 kHz, unsourced) is not a fourth table but a candidate
`Modes.fixed` row on top of whichever skin wins, heard at audition item
5 as a fifth clip and kept only if the gate names it. The nearest
measured skin in the house's own research, the shamisen's, has its
fundamental unresolved between ~152 Hz and ~766 Hz
(`docs/superpowers/plans/2026-09-27-silk-research.md:163, :261`), which
is why no number here is called sourced. The knob: 24 semitones to D6
with the speaks test at the top as the entry rule — the spike measured
pitch above 440 Hz at pressure 0.5 and 0.9 and the regime there never,
and 1175 Hz not at all — and the measured 19 semitones to A5 (880 Hz) as
the fallback, the per-voice span TINES already has
(`Tines.KALIMBA_TUNE_SEMITONES`).

**Later.** **CONTRABASS** is CELLO's bow at **E1** with two body numbers
(58 / 110 Hz): a root option or a preset family at TUNE low, a voice
only if the gate wants the E1 register on the knob — E1 speaks in tune
(+0.2 c) at 41.2 Hz but with four slips per period at pressure 0.5 and a
420 ms onset (spike (10)), its single-slip pressure unmeasured — so
before it is scheduled, one Phase-0 row: the pressure axis at 41.2 Hz in
the spike's table (4) shape, minutes of work — and a phone speaker does
not play 41 Hz, so its pad is for the MPC, not the instant loop (product
§3). **SARANGI** is ERHU's body at C3 plus **WASH** — SANTUR's
sympathetic bank (`Silk.washModesFor`, `Silk.kt:500-510`, rung through
`bodyRing` by `withWash`, `:428-432`: `Modes.fixed` rows from the
scale's degrees, rung through `bodyRing` after the body, DC-free,
RMS-matched, callable today) driven by the bridge wave for the whole
note, which is what "continuously stimulated by differentiated bridge
force" literally describes; if the gate hears "resonators, not strings",
the sitar's loops (`Pluck.sympathetic`, `Pluck.kt:880-904`: strings fed
continuously, following the note, private) are lifted into `Strings` as
their own R0-style PR, the third caller shape their KDoc names (fleet
§4). Never the documents' seven fixed-C plucks. **WHEEL** — the
hurdy-gurdy by its mechanism, the way GUANZI became HOLLOW — is the one
voice whose distinct mechanism the reference model already has for free
(a unipolar bow *is* a wheel), so what makes it a voice is its
post-processing: a bourdon that follows TUNE at f0 and 1.5·f0 (a second
`Bow`, `Strings.course`'s summing shape) and **BUZZ**, the chien —
`Terra.applyBuzz`'s shape (`Terra.kt:284-285, :491-502`: a threshold on
the render's own level, the excess into noise), never the documents'
absolute 0.35 on an un-normalised buffer — applied to the *bourdon*
`Bow`'s wave, not the melody's, and armed by the stroke: the real chien
fires when the crank accelerates (physics F9 — a wrist flick lifts the
loose bridge foot), and the only acceleration this model has is BOW's
overshoot, so the threshold is referenced to the bourdon's steady level
and the excess-into-noise is gated by the first 300 ms of the stroke at
BOW above 0.5, a buzz on the attack and silence in the sustain, which is
what a *coup de poignet* sounds like. A buzz that follows every note's
level regardless (the documents' and TERRA's shape) is the sixth-slot
BUZZ on any voice; the two are heard side by side at audition 11;
re-articulating the buzz inside one note is bow reversal's problem ("Out
of scope"). R3, with DRONE TO LOOP.

The physics axes are one: the bow. What differs per voice is root, body
and β; what the documents wrote as five mechanisms is one mechanism,
two bodies (plate, skin), one bank (the tarab) and one buzz (the chien)
— TERRA's reduction (`Terra.kt:12-44`), applied here.

## Macros

Five, inside the 3–6 budget (`docs/SYNTH_ROADMAP.md:173-174`; FORK and
BORE have five). Adding a macro later is compatible — `settled` fills a
missing key from defaults — and removing one is not, so the sixth slot
stays open for the gate. Every name is a plain word (rule 2, `:175-177`);
the documents' ROSIN, DECAY, SYMPATHY and SOLINA go, each for a reason
given in "The specification, as reviewed".

| Macro | Moves | Mapping | Default |
|---|---|---|---|
| **TUNE** | the note | 24 semitones from the voice's root, snapped through `Keys.midiHz` (`Fork.kt:256-258`); `neutral = 0.5` | 0.5 |
| **BOW** | the stroke: how fast the bow gets to speed, and how hard it bites on the way | attack `expMap(BOW, 0.40 s, 0.010 s)` on the velocity ramp (400 ms at 0, 63 ms at 0.5, 44 ms at 0.6, 10 ms at 1), capped at 0.85 of the hold (SIREN's `SWEEP_HOLD_FRACTION`, `Siren.kt:72`); above 0.5 an overshoot — the velocity starts at up to 1.75× its sustain value (the amplitude-1 corner the spike bounded, spike (10)) and the pressure at the window's top, both relaxing with the attack's own time constant. The **sustain velocity is a per-voice constant** (CELLO 0.13, STK's `0.03 + 0.2·0.5`, `stk_Bowed.cpp:116`), so BOW never moves GRIP's window (spike (4)). Heard in the first 300 ms and nowhere else — level is `levelTo`'s. Whether the overshoot makes a C2 stab speak in under 100 ms is R1's first table, not a promise (spike (5): the plain ramp takes 325–420 ms) | 0.6 (44 ms, a 1.15× bite) |
| **GRIP** | bow pressure and the bridge corner together: a light grip or digging in — the stick's share of the period, the corner's edge, the bite | `p = lin(GRIP, p_lo, p_hi)` over the **top single-slip island measured per voice** (spike (4): 0.90–1.00 at C3, single-slip at every velocity row except pressure 0.95 at amplitude 0.8 (three slips); edges re-measured at 0.01 steps at three TUNEs and pinned with the measurement in the KDoc), `slope = 5 − 4·p` (`stk_Bowed.cpp:155`); and `corner = expMap(GRIP, c_lo, 3024 Hz)` on the bridge one-pole, single-slip from 1000 Hz up at pressure ≥ 0.7 (the loss × force map) — the one brightness axis that keeps one slip, a shape standing in for the corner-sharpening a memoryless table lacks. Never a double-slip cell at either end; whether the top may reach the multi-slip region and whether 0 may reach the two-slip whistle are the gate's questions. `neutral = 0.5` | 0.6 |
| **BODY** | the plate's or the skin's share — not the documents' plate-to-skin crossfade: each voice has one body, and a knob that fades a cello into a drumhead is a shape no instrument has, so BODY is "how much of this voice's box", SILK's meaning | `Strings.bodyRing`'s `amount`: identity at 0, the body RMS-matched to the string at 1 (`Strings.kt:722-748`) | 0.5 |
| **HOLD** | how long the bow is on the string; the top step is a LOOP | SIREN's mapping (`Siren.holdSeconds`, `Siren.kt:190`): `expMap(hold/0.99, 0.3, 4)` s, then a 50 ms ramp to zero, then the bow **lifts** and the string is **stopped** — `Bow.gain()` ramps the bridge reflection down over `release = max(0.15 s, min(0.5·hold, t60(f0)))` — the floor wins where the free ring is shorter than it (ERHU above about G5, where t60 at the 3024 Hz corner is under 150 ms: 85 ms at A5, 48 ms at D6), which is why it is not a `coerceIn`, whose floor above its ceiling throws — so a stab's tail is 0.15 s and a 4 s pad rings to its own t60, the way a player's hand stops a string (OUD's SLIDE precedent for a finger's damping as a lower loop gain, `Strings.kt:510-512`), because the free ring at 0.95 is 0.9–1.9 s at C3–C2 (spike (7); dsp F31) and would file every stab past the classifier's 1.5 s line as LOOP (`Classifier.kt:72`); `LOOP_THRESHOLD 0.99`, SCRAMBLE capped at 0.95 (`Siren.kt:83-84`); `drumClassFor` derived from the *rendered* duration — hold, release and stop — against the 1.5 s line the way `Fork.drumClassFor` derives it (`Fork.kt:219, :239-242`), the constant computed from the mapping at implementation, never typed (the documents' hard-coded 0.65 was wrong even for their own mapping: 1.39 s, under the line, api C2) | 0.4 (0.85 s of bow, 1.33 s rendered at C3, under the line; the line is crossed near HOLD 0.45, computed at implementation) |

Defaults: GRIP 0.6 sits inside the measured island at the voice's
sustain velocity, so SCRAMBLE's neighbourhood plays one slip per period;
BOW 0.6 is a 44 ms stroke with a small bite; HOLD 0.4 renders 1.33 s at
C3 with the stopped tail, under the 1.5 s line so a default pad is a
note, not a LOOP (whether it files TONAL or
PERC is test 11's measurement: a cello at C2–C3 has energy under 200 Hz
and a decay past 500 ms only if the stop is slow enough, so it is the
one voice that can read TONAL; ERHU at D4–D6 reads PERC below 1.5 s by
construction, the truth roadmap S16 records for PLUCK and SILK).

DECAY is not the word: the documents' own code is `expMap(decay, 0.25,
3.5)` of note length with a fixed 50 ms release, and nothing decays —
the bow stops (dsp F8). SIREN's precedent: "the honest name costs
nothing" (`siren spec:132-136`). ROSIN is a string player's word for
three things at once (api C1); GRIP says what the hand does. SYMPATHY
becomes WASH when the tarab comes, SANTUR's word for the same bank
(`Silk.kt:415`). No PUNCH — a drum-engine macro on a melodic engine
(`fork spec:305`), and the documents' hidden 0.4 besides.

**Vibrato** is baked, as nut-segment modulation through `Bow.retune` —
a string player's mechanism, correct here where BORE's F17 found it
wrong for a reed (STK modulates the neck delay at 6.13 Hz,
`stk_Bowed.cpp:52`, `stk_Bowed.h:118-121`), **off in the LOOP render**
(the organ's rule, `Keys.kt:79-86`). Baked rather than a knob for BORE's
two reasons: a fixed-depth vibrato is identity, not a choice, and the
sixth slot stays free. Its numbers (macros §2), every one a listening
value but the rate:

| Parameter | Value | Why |
|---|---|---|
| rate | 6.1 Hz | STK's default, the one sourced number |
| depth | ±10 cents of pitch at full — a ±11.5 c retune of the nut, since the nut carries 1 − β of the period | VOX bakes 70 c for a choir (`Vox.kt:116-120`); a solo string is far less; labelled shape |
| onset | after the voice's own build-up (≈ 0.35 s at CELLO's root, spike (5)), then a 200 ms rise | a vibrato on the scratch is a wobble |
| HOLD scaling | 0 at a hold ≤ 0.6 s, full at ≥ 1.5 s | a stab has none, a pad has some |
| bounded | at ±10 c the geometric β moves by 0.0007 against single-slip cells about 0.05 wide (0.08–0.127 at p 0.9, 0.3–0.4 at p 0.5, spike (6)), so the swing never leaves the cell; the nut Loop is built at the swing's lowest pitch (`Strings.kt:408-414`'s rule) | |
| test | pitch excursion ≤ 15 c peak on a 3 s pad; zero on a LOOP; one slip per period with vibrato on at HOLD 0.95 | |

**The sixth slot's candidates at the gate**, ranked (macros §3): **BUZZ**
first — the chien on any voice, `Terra.applyBuzz`'s shape
(`Terra.kt:284-285, :491-502`: above a threshold referenced to the
render's own level, the excess into noise), a post-stage with nothing to
prove in the loop, a no-op at 0 so SCRAMBLE never lands on it, about
thirty lines; **bow position** second — a brightness axis that is the
bow's own (centroid 1327 → 2623 Hz over β 0.4 → 0.05, spike (6)) but
whose single-slip cells are not contiguous (0.08–0.127 and 0.4 at
pressure 0.9, a two-slip hole at 0.2) and inside which the centroid
barely moves (1571 against 1544 Hz), so it needs a β × GRIP map per voice
at 0.01 steps to find a window, bounded below by the β floor ("The
bow"); **WASH** third — the tarab, SANTUR's bank, which belongs to
SARANGI's round rather than to CELLO's slot; a **VIBRATO** knob only if
the gate misses one. Not a candidate: the growl — a memoryless table does
not period-double (spike (4): 0 cells of 63; the DSP review's STK grid
found one in 25 at its extreme corner, dsp F9), so a GROWL knob would be
a different friction law and is research.

`scramble` is `Dsp.scrambleNear` (`Dsp.kt:133`) around the voice's
presets (SKIN's lesson; `Fork.kt:246-254`'s shape with its `temperature
>= 1f -> base` branch), HOLD capped at 0.95 so a roll never lands on
LOOP, GRIP confined to the window by the mapping itself.

**Velocity.** `Velocity.macroSpecsFor` gets its arm in R1 — the compiler
demands it (`Velocity.kt:191-219`). No `brightnessOverride` line: the
house registers one only after a sweep proves the macro moves the
centroid at every step (`ForkTest.kt:329`, an 11 % ripple allowed;
`Velocity.kt:238-253`), and two candidates run their sweep in R1 (macros
§4). **BOW** first: its axis is the *onset* centroid over the first
window (FORK's own measure), a fast overshooting bow's first tens of
milliseconds are the bright multiple-slip burst and a slow bow's the dark
swell (spike (5)), every BOW value speaks by construction, and musically
a soft hit *is* a gentler, slower bow. **GRIP** second: its axis is the
sustain centroid from 0.5 s; inside the window the h-slope runs the right
way at the 3024 Hz corner (−7.4 → −6.1 dB per octave, pressure 0.7 → 1.0)
but the 2000 Hz row runs the other way, and the spike's only two centroid
points straddle a regime boundary (1718 Hz at pressure 0.5 against 1471
at 0.9, spike (3) — a double-slip spectrum is brighter because an octave
has a higher centroid), so it is unmeasured inside the window. A voice
whose sweep passes gets the line; until then `soften`
(`Velocity.kt:26`). `atVelocity` scales the macro toward 0.28 of the
preset's value (`Velocity.kt:177`): 0.28 × 0.6 is a 215 ms stroke or a
light grip that still speaks, so a soft layer is never a hole in the
instrument (RESIN's held-pad decision 2) — never a lifted bow or a
double slip. The override stays scoped to `ArcoPatch` (the SKIN/SNAP
lesson, `Velocity.kt:226-237`).

## Held, and the LOOP

Two things look alike and cost differently, as in BORE.

**The LOOP (round one, behind the seam test).** HOLD's top step renders
one seamless loop for the SURFACE, and `→ SURFACE ▸` appears beside SEND
TO PAD exactly as it does for SIREN (`SynthScreen.kt:1063-1065`). This
is the fun-on-a-phone prize — a bowed string under a finger, pitched by
position — and it is the sound that carries an erhu line, since a
fretless glide is the SURFACE's gesture, not a timbre. The plan is
BORE's (`bore spec:1035-1117`) with the parts a bow changes:

- `Arco.planLoop(hz)`: `K = ceil(LOOP_MIN_SECONDS · hz)` periods (≥ 2 s,
  `Siren.kt:87`), `loopFrames = round(K · RATE / hz)`, `baseHz = K · RATE
  / loopFrames` — `Keys.planLoop`'s fraction-of-a-cent move
  (`Keys.kt:169-180`).
- **The bow is held at constant velocity and constant pressure** through
  the loop region, vibrato off, no stroke; there is no noise to make
  periodic (the bow needs none — spike (10)). The state is a limit
  cycle, periodic with autocorrelation 0.97–0.98 at one period after
  0.7 s (spike (9)), so the loop start sits past the string's own
  build-up — about 0.45 s at C2 and 0.25 s at A3 — plus a settle.
- **The played pitch is a limit cycle, not an accumulator's**, so K whole
  periods of the *planned* `baseHz` do not close by construction — and a
  sawtooth is far less forgiving of a miss at the wrap than BORE's reed.
  `Keys.seamError` is difference energy over signal energy across 256
  frames at the wrap (`Keys.kt:188-199`), bar 1e-3; for a Helmholtz
  sawtooth whose flyback is five times faster than its ramp (spike (3))
  a slip of δ periods at the wrap costs about `e ≈ 86·δ²` (toolkit §3.4),
  so the slip must land within **0.34 % of a period**, and over K periods
  a pitch miss of c cents slips `K·c·ln2/1200` periods:

  | note | K (periods in ≥ 2 s) | cents budget | half-frame rounding alone, e |
  |---|---|---|---|
  | C2 | 131 | 0.045 c | 5e-5 |
  | C3 | 262 | 0.022 c | 1.9e-4 |
  | C4 | 524 | 0.011 c | 7.6e-4 |
  | C5 | 1047 | 0.0056 c | 3.0e-3 |
  | D6 | 2350 | 0.0025 c | **1.5e-2** |

  BORE's "pitch within 1 cent of the plan" is forty times too loose here.
  So the step is iterated: render a settle stretch at the pinned
  correction, measure the played period over the last hundred-plus
  periods by a sub-sample crossing fit on the settled ramp
  (`ForkTest.preciseFundamental`'s idiom, `ForkTest.kt:124` — BORE's
  objection to zero crossings was a non-speaking reed; a settled sawtooth
  is the one waveform they were made for; the FFT route resolves ~0.1 c
  on a second and is not enough), `Bow.retune` both segments by the
  ratio (the Loops built 1 % low so the correction has room), settle,
  measure again, up to three times until the residual is inside the
  note's budget, then cut at a zero crossing (`Siren.bestCut`,
  `Siren.kt:425` — it scores the larger of the two neighbours, so on a
  sawtooth it lands on the shallow ramp crossing, never the flyback) and
  let `Keys.requireSeam` decide at `MAX_SEAM_ERROR = 1e-3` (`Keys.kt:147,
  :201-206`), one retry at 2× settle, never a crossfade. Level on the
  loop region. The residual is a fixed sample count, so the first retune
  is expected to land; the iteration is the proof. The half-frame
  rounding of `loopFrames` alone puts a sawtooth at e ≈ 7.6e-4 at C4 and
  3e-3 at C5, so the retune to the plan's `baseHz` is not optional on
  ERHU, and D6's LOOP may be withheld by the bar. The diagnostic beside
  the bar: the slip instants over the twenty periods before the wrap and
  the twenty after, fitted linearly — their phase difference *is* δ, and
  its trend says whether a failure is a residual (retune) or a wander
  (the cell is not periodic; choose another).
- If any R1 voice × TUNE fails the bar after the retry, the top step is
  **withheld from R1's readout** and becomes R2; the classifier files
  anything over 1.5 s LOOP regardless, so a LOOP is filed by name, as
  SIREN's is (`Siren.kt:176-177`).

**The held keygroup (round two).** MAKE INSTRUMENT is gated to RESIN and
SIREN (`SynthScreen.kt:583, :619-621, :1081`) because each closes its
own loop. `Keys.arcoPad(voice, macros, midi, attackSeconds, cancelled):
KeyNote` is `resinPad`'s body (`Keys.kt:216-259`): nine zones every
minor third in the voice's register, `require` on range naming it
(`:225-227`), the held render unlevelled and decimated with the cancel
check every 2¹⁵ samples (`Resin.kt:140, :231`), loop start after attack
plus settle, `requireSeam`, level on the loop region, `limitPeak(0.99)`;
`InstrumentSuite.renderArco` with two layers as `Layered(stem, snip,
velStart, velEnd, loopStartFrame)` (`InstrumentSuite.kt:35-49`) and the
`InstrumentSidecar.RECIPES` entry (`InstrumentSidecar.kt:30`);
`HeldSpec.Arco` on the phone (`SynthScreen.kt:1526-1537`'s shape). The
two layers are velocity-true — a slower stroke and a softer grip inside
the window for the soft one — and a layer that does not speak is a hole
(RESIN's held-pad decision 2), which the window mapping prevents. Beside
it the cheap one-shot instrument, `Keys.arco(midi, voice, macros, bow):
Snip` in `Keys.fork`'s shape (`Keys.kt:346-357`: the core driven at
`midiHz(midi)` so TUNE's snap is never inverted, `Dsp.normalize`) with
`resinPad`'s `require` on range (`:225-227`), shipped with its consumer
and never alone — R2 with the held path, not R1, because a cello
keygroup of 0.3–4 s one-shots is rule 6's failure ("sustained key
patches render a loopable sustain segment",
`docs/SYNTH_ROADMAP.md:184-187`): a string that stops under a held key.
Until R2 the door that exists is PAD FROM ANYTHING → MAKE PAD →
INSTRUMENT, a one-note keygroup from any pad (`docs/BENCH.md:103`).

**DRONE TO LOOP** — a bowed pedal as a loop-grid track, re-rendered on
tempo change (`ResinDrone.kt`, `SirenDrone.kt`, the `DroneSpec` arms at
`SynthScreen.kt:727`) — is R3, after R2's seam is proven: it is the
same loop plan at the grid's own length.

## ENSEMBLE, the rack section

Sketched here because the string-machine half of the ask lands through
it, and because it can land before the engine; its own design is a
small document when it is built (the CONTOUR precedent: `Contour.kt`,
83 lines, and `git show --stat 298a759`, 5 files, +132/−2). The rack
proposal (rack §1) is the source of every number below.

- **Where.** After TAPE and before PHASE in the fixed order
  (`FxChain.kt:11`): an ensemble is the same species as PHASE — a
  modulation of the finished tone that the repeats should carry — and
  PHASE's own argument for its slot is the template ("the sweep happens
  to the finished tone, and ECHO's repeats then carry it at earlier points
  in the sweep", `Phase.kt:14-16`); before PHASE rather than after because
  a phaser on a chorus notches three moving copies at once, and the fixed
  order picks one — "a playability rule wearing an architecture hat". The
  JSON key is `ensemble`; its `Section` row sits between `tape` and
  `phase`.
- **What.** One line per channel, three fractional taps read 120° apart
  on two slow oscillators — the addendum's 0.58 / 5.85 Hz and 7.5 / 2.8 /
  0.45 ms as the starting constants, declared in the KDoc as listening
  values with no source (the prose gives 7.0 / 2.5 / 0.4) — a 6.5 kHz
  one-pole per tap as a tone (never called a bucket brigade: no clock, no
  compander), the wrap-safe fractional read lifted from
  `Tonewheel.tap` into `Dsp.tap` with TONEWHEEL calling the lifted copy
  (a pure move, proven by the TONEWHEEL determinism canary; one copy, not
  two — "one quantity computed twice" is the house's named defect
  shape) so the addendum's negative `frac` cannot recur, TAPE's
  per-channel idiom with one LFO phase shared across the frame
  (`Tape.kt:60`; `Phase.kt:61`), and TAPE's peak match. **100 % wet, no
  dry mix** — TAPE's rule (`Tape.kt:39-88` has no dry path), and the
  reason: a dry-plus-delayed sum is a comb with notches every 133 Hz at
  7.5 ms, −15 dB ripple at the addendum's amount 0.5 (dsp F21), and the
  measured low end of that comb survives the modulation (9.3 dB between
  the first peak and notch, empirical §F2). DEPTH 0 is three identical
  copies — a plain 7.5 ms delay, TAPE's own all-zeros behaviour.
- **Macros.** Three plain words, the count TAPE, PHASE and CONTOUR carry:
  **DEPTH** (both LFO depths together, linear, default 0.5 — ±23 c per
  tap at the peak of the two oscillators' alignment; TAPE's wow at full
  is ±38.5 c, dsp F20), **RATE** (one multiplier on both rates,
  `expMap(RATE, 0.5, 2.0)`, their 10.09 : 1 ratio kept, default 0.5 = the
  addendum's rates), **WIDTH** (a crossfade from the equal mono sum of
  the three taps to the stereo pair below; at 0 the output keeps the
  input's channel count, so the doubled WAV is an opt-in *inside* the
  section — U4's "opt-in per patch" made opt-in per recipe, and THUMP's
  own WIDTH deciding `channels` is the precedent, `Thump.kt:56, :137`;
  default 1, `neutral` 0, so the pad sheet's AMT fade lands on mono).
- **Stereo, and the fold.** The phone folds — `KitPreview` averages the
  channels, `Loudness.of` folds before it meters (`Loudness.kt:21`), the
  classifier folds before it files — so the fold is what the owner hears
  on the instant loop. The addendum's `L = (t0 + t1)/√2, R = (t1 +
  t2)/√2` shares the centre tap and folds it at double weight (dsp F22:
  −1.25 dB against either channel for uncorrelated taps; correlation
  0.50 in that model, rack §8.2, 0.30–0.36 measured). The house asks for
  a fold that keeps all three taps at equal weight: write `L = (x, y,
  z)`, `R = (z, y, x)` over the taps at unit power; equal weight in the
  fold needs `y = (x + z)/2`, and width and fold loss then move together
  on a straight line (rack §8.2, uncorrelated taps — a model, not a
  bound: the addendum's own pair computes to −1.25 dB here and measured
  −1.7 to −1.9 dB, because modulated copies of a pitched tone are partly
  anti-correlated; the table orders the rows, the test measures the
  fold):

  | z | L | fold loss | L/R correlation |
  |---|---|---|---|
  | 0 | (0.894, 0.447, 0) | −2.22 dB | 0.20 |
  | 0.10 | (0.869, 0.485, 0.10) | −1.52 dB | 0.41 |
  | **0.15** | **(0.852, 0.501, 0.15)** | **−1.23 dB** | **0.51** |
  | 0.20 | (0.833, 0.516, 0.20) | −0.97 dB | 0.60 |
  | the addendum | (0.707, 0.707, 0) | −1.25 dB | 0.50 |

  Proposed **z = 0.15**: the addendum's width and fold loss to two
  decimals, with every tap folded at 0.501 instead of the centre one
  doubled — the same picture on the phone's speaker, none of the
  lopsidedness. A gate choice among the rows, not a derivation.
  **Measured at PR-E1** (`FxTest`, by `Loudness.of` on the average fold
  — `Dsp.kt:585-587`'s rule; `Loudness.of(stereo) − Loudness.of(toMono)`
  is 0.00 dB by construction and is therefore *not* the test, dsp F28):
  the fold's cost is set by how alike the two channels are, and the
  source decides that. For equal-window RMS it is exactly `√((1 + ρ)/2)`
  for an L/R correlation ρ, and the plain-RMS rows follow it (−0.97 dB
  at ρ 0.60, −2.27 dB at ρ 0.19); by the house meter it is larger,
  because `Loudness.of` cuts below 120 Hz — removing the coherent
  fundamental and leaving the less coherent harmonics — and meters the
  loudest 200 ms window: a kick −0.2 dB (ρ 0.96), VELVET's FANFARE −0.95
  dB (ρ 0.36), a bare saw −2.1 dB at C3 (ρ 0.60) and −3.2 dB at C4 (ρ
  0.19), where the harmonics fall near half the tap spacing and the
  copies cancel — one meter for the test and the gate page (`FoldMeter`,
  correlation and pump measured after the first 300 ms), so the number a
  caption shows is the number the test pins. The model's 1.2 dB and the
  1.5 dB bar this document first named were the uncorrelated case; the
  test pins the measured rows instead, prints the neighbouring z rows
  for the record (z 0.25: −2.3 dB, ρ 0.44 at C4; z 0.35: narrower
  still), and the gate page plays every stereo clip beside its fold with
  the measured loss in its caption, so the row is chosen by ear with the
  number in view. The 100 ms level ripple of the fold on a steady saw is
  1.5 dB at DEPTH 0.5 — the beating that *is* the ensemble (the
  addendum's stage pumped 3.5–4.6 dB).
- **A stereo Snip from a mono one** is unforbidden rather than allowed:
  no section changes the channel count today — TAPE, PHASE, CONTOUR,
  ECHO and SPRING iterate `snip.channels` and return it, SWELL folds its
  wash back to mono for a mono input — `FxChain.process` makes no
  channel assertion (`FxChain.kt:72-90`), `Snip` accepts one or two, the
  writers write the count, `Preflight` fails only above two, and
  `FxTest` asserts the count only for a *stereo* input (`FxTest.kt:284`).
  So widening needs no plumbing, only a contract and its tests: one
  sentence in `FxChain`'s KDoc ("a section may widen a mono snip to
  stereo and never narrows one; every section after it runs per
  channel" — PHASE, ECHO, SPRING and MOTION do), two `FxTest`
  assertions (a mono kick through the full default rack stays mono, so
  no other section widens by accident; through `ensemble` at defaults it
  comes out two-channel; at WIDTH 0 it keeps its count), and a
  `stereoExcluded` entry with its measured divergence in SWELL's form
  (`FxTest.kt:62-69`) — the first section whose *purpose* is L ≠ R,
  kept honest by the self-check at `:313`.
- **The tests.** The CRUNCH rule's identity test — a kick through
  ENSEMBLE at defaults still classifies KICK (`README.md:162-167`'s
  rule; predicted to pass, since KICK is decided by the energy under 200
  Hz and three copies of a 45 Hz kick under 3 ms apart are coherent
  there; if it fails at DEPTH 0.5 the default moves, never the exemption
  RING has); the per-tap deviation asserted from the constants with no
  audio (17.6 and 28.4 c at DEPTH 1 — the replacement for the addendum's
  false-premised "1.72 s" test); the fold bound, the correlation and the
  ripple; deterministic, clean, peak-matched; the widening assertions;
  JSON round trip, bypass, rack-order emission and AMT scaling, generic
  over `SECTIONS`.
- **A LOOP lands dry.** At 0.58 Hz a 2–4 s loop holds 1.2–2.3 slow
  cycles, so the delay jumps at the wrap by up to 3.3 ms at DEPTH 0.5 —
  a pitch tick. SIREN lands LOOPs without ECHO for exactly this
  (`Siren.kt:121-124`) and the organ's held path turns its scanner off
  (`Keys.kt:79-86`); the rack does not know a pad is a LOOP (a `Snip`
  carries no loop metadata), so the KDoc and the gate page say it.
- **Surfaces and effort** (rack §1f, from CONTOUR's footprint): about
  **240 hand-written lines across ten files** — `Ensemble.kt` ~140,
  `Dsp.kt`/`Tonewheel.kt` +12/−9 for the lifted tap, `FxChain.kt` +9/−1
  (the field, the row, the order line, the widening sentence),
  `Treatments.kt` +2 (`"ensembled"`), `PadSheet.kt` +3 (`ENSEMBLE` on
  `TIME_SEGMENTS`, `PadSheet.kt:80` — the character row beside PHASE was
  already at the card's six-chip ceiling, `PadSheetTest.kt:189`, and
  three copies a few milliseconds late is a delay-line effect that reads
  as one — and the verb map), `FxTest.kt` ~65, `TreatmentsTest.kt` +1,
  `PadSheetTest.kt` +3/−2 (the chip inventory), `README.md` +3/−1.
  CLI-reachable the day it lands through `Treatments.names`. Cost: six
  sines per frame and three one-poles per channel at the snip's own 44.1
  kHz — TAPE's load three times over, once, not at 4×.
- **Before the engine, as its own PR.** Yes — nothing in it depends on
  ARCO. **PR-E1** is the section, with its own four-clip gate through
  `AuditionLevel`: a THUMP kick, VELVET FANFARE, RESIN WIDE SECTION and
  a VOX CHOIR, each dry and at DEPTH 0.25 / 0.5 / 1 with WIDTH 0 and 1,
  the stereo file beside its fold. Pass rule: FANFARE through ENSEMBLE
  reads "string machine" to the owner, and the fold does not pump past
  what the printed ripple says. **E1's verdict, 2026-09-29:** the
  section landed as #394 and the owner heard the gate page and reported
  that it works — a pass in one sentence, with no chip verdicts saved on
  the page and no depth, fold or width answer recorded, so PR-E2 takes
  the section's defaults (DEPTH 0.5, WIDTH 1, z = 0.15) until a later
  listen says otherwise. **PR-E2** is the string-machine presets below,
  after E1's verdict.

**STRING MACHINE without ARCO** (rack §2). The string machine exists the
day PR-E1 lands, through a door that already exists — **route (a)**:
VELVET BRASS FANFARE, HORN SECTION, WARM BRASS or SOFT BRASS
(`VelvetPresets.kt:45-53`) and RESIN BRASS WIDE SECTION or SOFT HORN
(`ResinPresets.kt:57-64`) sent to a pad and given the ENSEMBLE chip on
the pad sheet: no new source DSP, no new preset, no plumbing, two taps
on the phone. **Route (b)**, a preset that lands with a chain in one
tap, needs plumbing the tree does not have — a `Patch` carries no chain,
a factory preset is a `Patch`, and SEND TO PAD consults `landingChain`
for SIREN alone (`SynthScreen.kt:498`) — and the right shape is neither
SIREN's macro-keyed door (VELVET has no macro that means "string
machine") nor a chain on `Patch` (sixteen sealed subtypes, a JSON schema
that stores engine/version/name/voice/macros only, and the line
`PadRecipe`'s KDoc draws between a patch and a recipe), but **a landing
table beside the roster**: `VelvetPresets.landings: Map<String,
FxChain>` keyed by preset name, one dispatcher
`Presets.landingFor(engine, voice, name)`, consumed by SEND TO PAD, the
CLI's `--preset` and a kit — about sixty lines, and the same table
serves ARCO's own string-machine presets in R3 with no second mechanism.
Two costs (b) carries that (a) does not: the twelve-per-voice law
(`VelvetPresetsTest.kt`, `ResinPresetsTest.kt`), so two new presets on
each BRASS voice means the count amended or two replaced; and the
preview gap — the SYNTH panel's instant loop renders the `Patch` dry, so
a STRING MACHINE preset would sound like mono brass until it lands
(SIREN's ECHO has the same gap today, unnoticed because a siren is a
siren dry), and closing it is `:app` work. Recommendation: (a) in PR-E1,
(b) as PR-E2 once E1's gate says FANFARE through ENSEMBLE *is* the
sound. Names for (b), ≤ 14 characters and checked by script against the
regex and the 798 distinct shipped preset names (rack §8.3): STRING
MACHINE (14, the instrument class), DISCO STRINGS, SLOW STRINGS, WIDE
STRINGS, DARK STRINGS, OCTAVE STRINGS, STRING PAD, THIN STRINGS; never
STRING ENSEMBLE (15, and the product's other half-name), never anything
with SOLINA, ARP, EMINENT or a year. A register — the addendum's cello,
viola and violin formants — is not the section's: ENSEMBLE stays a
modulation section with no tone stage, and route (b)'s landing table
pairs ENSEMBLE with the rack's EQ per preset (DARK STRINGS a low shelf,
THIN STRINGS a bass cut, every number a listening value), while route
(a) takes it from the pad sheet's EQ chip.

**PR-E2, as built (2026-09-30).** Four presets landed: VELVET BRASS
STRING MACHINE and THIN STRINGS, RESIN BRASS WIDE STRINGS and DARK
STRINGS, appended after each roster's twelve (so `--preset N` and every
`.first()` keep their meaning), with the twelve-per-voice law amended to
twelve and BRASS's fourteen. Three things differ from the sketch above,
each measured or found in the tree rather than chosen. **The key is the
macro values, not the name.** `Presets.landingFor(engine, voice, macros)`
finds the factory preset whose every macro equals the sound's and returns
its chain, because `UserPresets` refuses a factory name only at save time:
a player's saved "STRING MACHINE" from before this change would be a
second chip with the same label, and a name key would hand their different
sound the ENSEMBLE. A moved slider lands dry, and a saved copy of the
unmoved factory sound lands with the chain (same sound, same landing).
**The EQ pairing is dropped.** The rack's EQ is three fixed bands (100 Hz
shelf, 900 Hz bell, 8 kHz shelf) and these voices live between them: with
the bass cut at 0.3 THIN STRINGS' 40-200 Hz band moved -0.9 dB against the
same chain without it, and with DARK STRINGS' low shelf raised to 0.6 (and an
air cut at 0.3 besides) its 40-200 Hz band moved +0.9 dB and its top band
-0.6 dB (a 900 Hz bell at 0.35 does move DARK STRINGS' 0.8-3 kHz band -3.4 dB, but
that is a different pairing from the shelves the sketch named, and nobody has
heard it). A chip that changes nothing you can hear lies about the sound, so
the landings are ENSEMBLE alone
and the presets differ in their source macros and in DEPTH and RATE
(THIN STRINGS shallower and quicker, DARK STRINGS slower and deeper). **The
presets are short decaying pads,** not a held section: a VELVET or RESIN
one-shot has a fixed 3 ms attack and a decaying envelope of about a
second (0.88-1.23 s at DECAY 0.8-0.9, -20 dB by 0.20-0.36 s), which is what the
engine is, and a pad that swells in and holds is ARCO's or a held-pad
instrument's. Every macro value was chosen by rendering candidates and
reading their length, brightness drift, root pitch and the fold's cost
(landed fold -0.9 / -1.1 / -1.5 / -1.3 dB, L/R 0.29 / 0.47 / 0.04 / 0.26,
pump against the pair 1.7 / 1.3 / 2.6 / 2.3 dB; the tuner reads all four
as confidently and to within 4 cents of the dry voice), never by ear: they
are candidates until the owner has heard the gate page. Consumers: SEND TO
PAD and the panel's own preview (inside the render's own dispatcher, and
the audition plays the mono fold), and the CLI's `--preset`; SPREAD, MAKE
INSTRUMENT and DRONE TO LOOP still render the voice dry, as SIREN's SPREAD
does with its ECHO. The preview gap the paragraph above names is closed
for these four presets only; SIREN's ECHO still previews dry.

**PR-E3, as built (2026-09-30): ENSEMBLE's SECTION macro.** The owner
heard E2's string machines and said they sound good but not like several
instruments. The measurement agrees, and says why: the shipped section is
three taps on the *same* two sines 120° apart, so the three pitch wobbles
are locked (every pair correlates at exactly -0.5, the set has rank two)
and the swell comes round almost exactly every 1.7 s. A section of
players is the opposite of that: each drifts on its own, vibratos at its
own rate, and comes in a few milliseconds late. Prototypes of independent
players were A/B'd against the chorus on a real bowed note and a bowed
chord and the owner chose the staggered six ("Chord is especially good").
**The macro.** `SECTION` (default 0, neutral 0, the last of ENSEMBLE's
four) crossfades equal-power from the chorus at 0 to six players at 1.
At 0, or at or below `Ensemble.SECTION_OFF`, the output is the shipped
section bit for bit, and `EnsembleBytesTest` holds that: it pins the
SHA-256 of the float bits on five signals x five macro sets, two rows of
the wide-weight family and a recipe read from JSON, and was written on the
unedited tree before `SECTION` existed. A recipe without the key reads
back as it did. `EnsemblePlayers` is the voicing: six players with seeded
smoothed-noise drift (corners 0.4-1.45 Hz), their own vibrato (5.08-6.45
Hz, own phase, own entry delay), base delays from 7.5 to 33.0 ms, and a
little tone, tilt and level flutter each, spread over the pair by the
ramp-weight family at an L/R correlation of 0.51. `DEPTH` scales the
players' depth, `RATE` their rates, `WIDTH` the shared spread; the one
peak match runs on the mixture, as on the chorus path. **The bass
anchor.** Six equal players spread over 25 ms are a static comb with
bass holes: at 65 Hz the phone's fold was 13 dB down on average and 22 dB
in its worst 250 ms window. Every candidate that kept all six in the bass (tilted weights, a
narrower stagger, a lead voice, a hybrid bed, one player alone in the low
band) failed the fold gate; the one that held replaces the players'
bass with the dry signal delayed 17.7 ms through a 290 Hz low-pass, less
the players' own 145 Hz low band (`LP290(x delayed) - LP145(sum of
players)`), so the section's low end is one steady voice and the stagger
lives above it. After it, all ten tones from 40 to 330 Hz fold within
-3.6 dB in the worst 250 ms window (the worst is 260 Hz). The anchor is a
290 Hz low-pass and does nothing above it: a steady sine at 175, 240, 305 or
380 Hz still folds up to 12 dB down in its worst window (380 Hz, -11.9 dB),
which the tests record and bound. **In the crossfade** (found by review, not
by the first pass: the tests only looked at 0 and 1) the chorus bed's bass
(a copy at 7.5 ms) and the anchor's (a copy at 17.7 ms) cancel at 49 Hz and
its odd multiples, so the chip's default blend folded a 50 Hz sine 10.6 dB
down. Between 0 and 1 the bed now gives up its own bass and the anchor is
made up to full weight, at the bed's own delay until the last twentieth of
the macro; a first version that kept the anchor at 17.7 ms put a 15 dB
notch at 130 Hz, so the delay is part of the rule. Measured, the blends
from 0.05 to 0.85 fold within -5.0 dB on average and -7.7 in the worst
window at 50-260 Hz, and 165 Hz reaches -10.8 dB in its worst window at
0.97, where the anchor is half way between its two delays; 0 and 1 are
unchanged. **What it
measures** (`EnsembleSectionTest`, each bound just past the number
recorded next to it): pairwise pitch-deviation correlation 0.02 mean and
0.05 at worst against the chorus's 0.500; envelope line prominence of a
steady sine 3.0-5.1 at 100-1000 Hz against the chorus's above 100; click
onset spread (5th to 95th percentile of the click's energy) at least 18 ms
where the chorus's is under 5. **What it costs,** all known: the pair is
narrower on the low and middle (the L/R correlation of eight saws goes
0.43 to 0.74), the level ripples less (the swell the chorus gave is
gone), the envelope's line prominence is worse at `RATE`'s extremes than
at its default (up to 8.7 against 3.0-5.1), `DEPTH` 0 is a static
comb in the mid-range (the chorus at `DEPTH` 0 is three identical copies, a
7.5 ms delay and no comb), the section is a
one-shot's (the last 7.5 ms of its input never emerge, as the chorus's do;
the 7.5 to 33 ms before that come out only through the earlier players, so
it fades its last 4 ms), a
stereo input gets the anchor per channel (an approximation), and a build
from before this change refuses a recipe that carries the key (the rack's
`VERSION` stays 1; the pad degrades softly, as any unknown-macro recipe
does). **Reach.** A pad-sheet chip, SECTION, beside ENSEMBLE (`sectioned`:
`DEPTH` 0.5, `WIDTH` 1, `SECTION` 1, faded toward the neutrals by AMT, so
its 0.7 default is DEPTH 0.35, WIDTH 0.7, SECTION 0.7, a blend); ENSEMBLE's
own chip and the `ensembled` treatment are untouched, so every old recipe
replays unchanged. **The landings stay on the chorus.** They were first
switched to `SECTION` 1 in a commit of their own (`b53e4b4`), and that commit
was reverted after the owner listened: on the four string machines' short
decaying pads (about a second each) the chorus and the players sounded the
same ("these are stab type sounds so difficult to really determine, but I hear
no difference"), while the players cost a narrower pair, a landed L/R of
0.74 / 0.80 / 0.74 / 0.58 against the chorus's 0.29 / 0.47 / 0.04 / 0.26, for
a gentler fold (-0.4 / -1.1 / -0.6 / -0.5 dB against -0.9 / -1.1 / -1.5 /
-1.3, pump 0.6 / 0.3 / 0.5 / 1.3 dB against 1.7 / 1.3 / 2.6 / 2.3). A
benefit nobody can hear does not pay for a measured cost, so E2's landings are
as they were merged, and `SECTION` is reached from the pad sheet's chip. The
likely reason, not measured: the players' independent drift (0.4-1.45 Hz corners)
and vibrato entries (0.10-0.45 s) have little time to show in a one-second stab,
where the bowed C3-G3-E4 chord they were chosen on runs three seconds. **What the
owner's ear said** (the listening page, 2026-10-01): on that chord the
prototype they had chosen sounded the same as the chorus on this round's
page, and the built `SECTION` 1 with the bass anchor sounded *more* like
several players than the chorus, and better than the prototype ("3 is
better"); nothing was said about the bass reading as a lump, which is taken as
no objection. Putting the landings back on the players is one commit, if a
held-pad instrument ever wants it.

**The ARCO landing** (rack §3): **dry**. The two landings that exist are
stories an instrument tells about itself — a siren's onboard delay is
part of the sound (`Siren.kt:120-125`), a tape flute is the addendum's
own premise — and a bowed string's story is a cello in a room, which is
the rack's SPRING, one chip away for every pad. Three reasons beyond
precedent: a recipe with a chain cannot re-render its velocity layers
(`canUseAtVelocity` needs `fx == null`, `Velocity.kt:164`), and ARCO is
the engine whose soft layer could one day be the bow itself; a LOOP
lands dry regardless, so a landing would make the one-shot and the LOOP
of one preset different pads; and the FORK and SIREN kits skip
`melodicMotion`'s tape (`SynthKits.kt:37`, passed only by the pluck,
stab and tines helpers, `:44-64`; `siren()` passes `Siren.landingChain`,
`:208-215`, and `fork()` nothing, `:234-240`), so no second tape appears
either. R1b built no `Arco.landingChain` function at all (pads land dry
and nothing passes `fx`, the kit and SEND TO PAD included), so there is
no door yet; if the gate's A/B (audition item 10, not built in R1b:
SHORT STAB dry, through SPRING at MIX 0.2, through ENSEMBLE at DEPTH 0.5,
level-matched on the bar line) wants SPRING on the landing, the function
is added then, with its numbers pinned, BORE's `LANDING_TAPE` shape.

## Data flow and compatibility

The addendum's checklist names three surfaces (part 2, p.13); FORK's
introducing commit touched sixty files (`git show --stat 82dc292`,
+8464/−29), SILK's minimal registration four (`65797f8`), and the house
checklist has twenty (api Part B). Against it, with FORK's line counts
as the size guide and the round each surface lands in. The compiler
enforces `Velocity`'s arm; `PresetsTest` enforces the `Presets` arm;
every other line is a convention.

| # | Surface | FORK R1 | ARCO | Round |
|---|---|---|---|---|
| 0 | `synth/.../Strings.kt` | — | `tune(…, roundTrip: Double = 1.0)` with the factor fractional, `retune` carrying it; `Loop.next` split into `reflected()`/`inject()` — BORE's additions #1 and #3, shared; **nothing at all if BORE's R0 has landed first** ("Architecture") | **R0** |
| 1 | `Arco.kt` (new) | 385 | `ArcoVoice { CELLO, ERHU }` in-file (`Fork.kt:51`'s shape), constants with KDoc that says *why* each number (`BRIDGE_HZ`, the per-voice tuning share, the GRIP window edges, β), `macrosFor`/`defaults`/`drumClassFor`/`scramble`/`midiFor`/`frequencyFor`/`settled`, `internal fun bow(voice, hz, macros, rate): FloatArray` at exact Hz (the core the claims tests read at native rate — `Fork.strike`'s shape, `Fork.kt:423`), `render`, `planLoop`/`renderLoop` on `Siren.kt:339-451`'s shape, the lift | R1 |
| 2 | `ArcoPatch.kt` (new) + `Patches.kt` arm | 82 (in `Patches.kt`) | `TerraPatch.kt:10-33`'s 34 lines including `fromJsonValue`; one arm before `else` at `Patches.kt:54-55` | R1 |
| 3 | `ArcoPresets.kt` (new) | 45 | 8 per voice, authored by ear against the built engine, conditional on Phase 0 having said the voice speaks (it has) | R1 |
| 4 | `Presets.kt` | +7/−3 | `forVoice` arm and `all()` term (`Presets.kt:17, :72`), the KDoc count (`:8-9`). Without this arm `snipsnap synth ARCO CELLO` renders nothing: the CLI resolves engines through `Presets` (`SynthCommand.kt:51`), so this arm *is* the CLI registration | R1 |
| 5 | `Velocity.kt` | +6 | the exhaustive-`when` arm (`:191-219`, compile-mandatory); no `brightnessOverride` line until a sweep passes (`:238-253`) | R1 |
| 6 | `Keys.kt` | +39 | `arcoPad` (`KeyNote`), `arcoPadMidis`, the one-shot `arco`, range constants | R2 |
| 7 | `SynthKits.kt` | +22 | `arco()`: A01–A08 CELLO walking the pentatonic at a stab HOLD (the `fork()`/`siren()` shape, `SynthKits.kt:208-248`), A09–A14 six ERHU presets, A15–A16 the two LOOPs; dry, no landing chain | R1 |
| 8 | `synth/build.gradle.kts` | +9 | `generateArcoKit`, `generateArcoAudition` (`:239-263`'s shape) | R1 |
| 9 | `ArcoTest.kt` (new) | 577 | the claims tests | R1 |
| 10 | `ArcoPresetsTest.kt` (new) | 112 | the six-contract preset test (`ForkPresetsTest.kt:19-107`) | R1 |
| 11 | `ArcoKitGenerator.kt` (new) | 32 | `KitAssembler.assembleArranged` + `KitExporter.exportProgramFolder` | R1 |
| 12 | `StringsTest.kt` | — | the frozen grid unchanged (`StringsTest.kt:23-40`, 384 cases); `next(x) == inject(x + reflected())` sample for sample; two bare Loops of β·T and (1−β)·T closed by hand at fb −1 and −1 ring at f within 5 cents; the budget identity — the two `exact`s sum to `T − τ_bridge − τ_nut − 1.0` within 1e-9 at seven roots | R0 |
| 13 | `DeterminismTest.kt` | +8 | a canary: `patch.render()` twice, `assertContentEquals` (`DeterminismTest.kt:36, :51`'s shape), one-shot and LOOP | R1 |
| 14 | `PresetsTest.kt` | +2/−1 | the `forVoice` list and the expected `all()` sum | R1 |
| 15 | `PresetTestSupport.kt` | +4/−1 | `solina`, `eminent`, `\barp(?!eggi)`, `string\s*ensemble` as new alternatives, with the KDoc line ("The name") | R1 |
| 16 | `ThumpPresetsTest.kt` | +2/−2 | the near-miss and clean lists (`:103-111`) | R1 |
| 17 | `PadRecipeTest.kt` | — (SILK's) | `onePatchPerEngine` (`:25`) | R1 |
| 18 | `VelocityGrooveShuffleTest.kt` | — (SILK's) | the "darker at low velocity" canary — only with an override | later |
| 19 | `InstrumentSuite.kt` / `InstrumentSidecar.kt` / `InstrumentSuiteGenerator.kt` | +18/−2 / +4 / +1 | `renderArco`, the `RECIPES` entry (`InstrumentSidecar.kt:30`), the name→renderer pair | R2 |
| 20 | `ArcoAuditionGenerator.kt` (new) + `audition/arco-audition.html` | ~200 + ~1000 (SIREN's PR) | the listening page, copied from `siren-audition.html` for its SURFACE stand-in | R1 |
| 21 | `app/.../SynthScreen.kt` | +10/−2 | the `Engine` entry (`:1827-1828`) and its seven arms; the `→ SURFACE ▸` condition beside SIREN's; the LOOP readout; the toast; `HeldSpec.Arco` and the MAKE INSTRUMENT gating (`:583, :619-621`) in R2 | **R1.1** — not compilable in a cloud session (no Android SDK; `:app` is outside the Gradle graph), "a picker button guessed blind is worse than one left for a session that can verify it" (`fork spec:544-554`) |
| 22 | `README.md` | +22/−2 | the engine paragraph and the count (`README.md:336`, "thirteen") | R1.1 |
| 23 | `docs/SYNTH_ROADMAP.md` | +1 | one row, S18 or later, at implementation | R1 |
| 24 | this spec | +121/−16 | "As built" in the same commit as the code | R1 |
| 25 | `testkit/SnipSnap Arco Kit/` | 16 WAVs + `.xpm` | `./gradlew :synth:generateArcoKit` | R1 |
| 26 | `testkit/Instruments/SnipSnap Arco.xty` + `_[TrackData]/`, `instruments.json`, the zips | 18 WAVs + `.xpm`, +209 | `./gradlew :synth:generateInstrumentSuite`, then `scripts/pack_testkit_zips.py`; the suite run regenerates unrelated instrument WAVs — revert those by hand, as FORK's commit did | R2 |

The deferrals have precedent: TERRA and SILK are registered in `Patches`
and `Velocity` and absent from the picker (`SynthScreen.kt:1828` lists
thirteen); GLINT and TERRA are absent from `Presets.kt`
(`docs/SYNTH_ROADMAP.md:265, :270`). ARCO registers in R1, ships presets
in R1 because Phase 0 has already said both R1 shapes speak (the
TIDE/SIREN/FORK way; SILK's "presets authored blind are disposable" is
why the condition; both this and the LOOP in R1 are BORE's owner
decisions 10 and 11 taken with their conditions met, "Decisions already
taken"), and takes the picker in R1.1 from a session that can see
`:app`. The CLI needs nothing for one-shots (`SynthCommand.kt:23-51`);
`--instrument` and `--drone` are RESIN-only by name (`:29-32`) and stay
so — ARCO's keys go through `InstrumentSuite` as FORK's did.

**ENSEMBLE's own surfaces**, separately (rack §1f, from CONTOUR's
landing, `git show --stat 298a759`: 5 files, +132/−2, then the pad
sheet): `Ensemble.kt` (new, ~140), `Dsp.kt` and `Tonewheel.kt` (the
lifted `tap`, +12/−9), `FxChain.kt` (the field, the `SECTIONS` row, the
order line at `:11` and its sentence (CONTOUR's own at `:13` is the
shape), the widening sentence in the KDoc), `Treatments.kt`
(`"ensembled"`), `PadSheet.kt` (`ENSEMBLE` on `TIME_SEGMENTS` at `:80`,
the character row being full, and the verb map), `FxTest.kt` (the
section's own test, the identity test, the `stereoExcluded` entry at
`:62-69`, the widening assertions, the fold bound), `TreatmentsTest.kt`,
`PadSheetTest.kt` (the chip inventory), README's rack list — about 240
lines in ten files; and, for the STRING MACHINE presets of route (b),
the landing table beside the roster (`Presets.landingFor`, ~60 lines)
consumed by SEND TO PAD, which today consults `landingChain` for SIREN
alone (`SynthScreen.kt:498`), by the CLI's `--preset` and by a kit — a
`Patch` has no chain where a `PadRecipe` does (`PadRecipe.kt:12-13,
:23`).

The recipe: `ArcoPatch(name, voice, macros)`, `VERSION = 1`, round-trips
through `Patches.fromJsonValue`; a pad lands dry, so `PadRecipe(patch,
fx = null)` regenerates bit-for-bit; there is no `seed` argument
anywhere (`Dsp.seedFor` only; api F9) — and the bow needs no noise at
all (spike (10): 1 % on the bow velocity changes nothing), so
determinism is the loop's own — which is why the documents' "dynamic air
turbulence injection" on BOW is dropped (a bow has no jet; the spike's
closing list), and a bow-hair noise layer returns only as a gate
question with a level if audition item 4's blind test reads "synth".

## Failure handling

- A macro outside 0..1 is coerced and an unknown key dropped,
  `Fork.settled`'s shape (`Fork.kt:260`); `ArcoPatch`'s `init` rejects
  both in a recipe (`Patches.kt:94-101`).
- `Strings.tune`'s `require(exact >= MIN_LOOP_SAMPLES)`
  (`Strings.kt:181`) is the loud floor, and it guards each *segment*
  because each segment is its own `Loop` tuned by its own `tune` call.
  At ARCO's roots and ranges it is never near: the bridge segment is β·T
  ≈ 85.8 samples at CELLO's top (C4) and 19.1 at ERHU's (D6) with β
  0.127 at 176.4 kHz, less the bridge one-pole's delay and the two-tap
  0.5 that the segment's own `tune` charges (8.4 + 0.5 at D6, leaving
  10.2 samples); it would bite at about 2.2 kHz with the 3024 Hz corner
  and, with GRIP's corner at 1000 Hz, at D6 itself ("The bow", the β
  floor). A design constant that moves β toward the bridge, or `c_lo`
  below about 1300 Hz on ERHU, is checked at design time against this,
  not by the require.
- The loop cannot run away, and the argument is measured, not proven —
  BORE's honesty about its own bound applies (toolkit §4). With the bow
  lifted the junction is a lossless crossing and the loop is linear with
  the nut at gain 1 and the bridge at 0.95·|H| < 1: passive, ringing down
  at the formula's slope. With the bow down, the energy the junction adds
  per sample is `2·v_new·(v + v_new)`: at zero bow velocity it is
  `−2ρ(1 − ρ)·v²` ≤ 0 — strictly dissipative for every ρ < 1 — and at a
  bow velocity `V` it is at most `ρ·V²/(2(1 − ρ))`, 24.5·V² at ρ = 0.98: a
  bounded injection against a bridge that removes a fixed share of its
  side's energy every round trip, so the stored energy is bounded — but
  the bound carries no useful pointwise constant, and the small-gain
  route gives none either (the nut path's gain is exactly 1 and the
  tuning allpass's peak sample gain is above 1, so there is no strict
  contraction to sum). What holds is empirical: **zero non-finite samples
  in about 400 renders**, a raw peak of 0.822 across the 60-cell grid with
  every maximum within 0.06 of its steady peak, and 1.11 at pressure 1.0
  with amplitude 1.0 (spike (1), (10)); a plausibility check from
  Helmholtz kinematics (recalled) puts the string's peak velocity near
  `V·(1 − β)/β` ≈ 6.9·V, 1.58 at V = 0.23, above the measured 1.11. The
  fuzz test pins `RAW_PEAK_CEILING = 1.25` with those three measurements
  in its KDoc (the `Dsp.Ladder` precedent, `Dsp.kt:262-290`), re-measured
  if `RHO_MAX` moves, and asserts against it, so a corner that grows past
  what Phase 0 saw is named.
- **A bow that is lifted renders exact silence** (spike (10): 0.000000,
  no DC), and silence stays silence: `Dsp.levelTo` returns untouched
  below a loudness of 1e-6 (`Dsp.kt:636-639`), so nothing lifts it to
  full scale. What `levelTo` *would* lift is a stuck or multi-slip
  render, which is why the fuzz test's non-silent clause is "a pitched
  tone at f0 with one slip per period", never "non-zero" (BORE's
  sub-threshold rule, restated for a bow).
- No render is sub-threshold or multi-slip by accident: GRIP's travel is
  the measured single-slip window per voice ("Macros"), so a stuck or
  octave-dominant render is a corner the window's margin got wrong, and
  the speaks test names it (test 1) rather than passing it.
- A LOOP whose seam does not close after the retry is refused by name
  (`Keys.requireSeam`'s message, `Keys.kt:201-206`), and in R1 the top
  step is withheld from the readout rather than shipped with a click
  (BORE's rule).
- `drumClassFor` is a cheap macro-only predictor verified against the
  real classifier across ten HOLD steps; it never wishes for TONAL
  (`Fork.kt:239-242`; FORK wished it and measured otherwise).
- The held render (R2) takes `cancelled` and checks it every 2¹⁵ samples
  (`Resin.kt`'s held path); one-shots do not, as none do.
- The pitch correction is a per-voice constant, and a voice whose
  measured residual differs from its constant by more than the 5-cent
  bar at any TUNE fails test 2 by name — never a formula published as
  physics (spike iteration 5; BORE's "residual pull").

## Testing

All `kotlin.test`, never Jupiter (77 of 77 synth test files; api C7); all
read off rendered audio — the core at native rate for the physics
(`Arco.bow(voice, hz, macros, rate)`, before `levelTo`, which would lift
a stuck render to full scale), the `Snip` for the product claims. CI
sweeps run at HOLD 0 (0.3 s) plus the stopped tail — CI runs on metered
minutes — and the full grids are printed tables behind an opt-in system
property, BORE's split. Sized from the spike's 9.9 ms per rendered second
for the bare loop and the finished chain's ≈ 35–55 (toolkit §5): test 1's
window sweep at 0.05 GRIP steps × three TUNEs × two voices is about 130
renders of 0.5 s, a few seconds; the full 0.01-step edge search is the
opt-in table.

### The ones that carry the claims

1. **Speaks — one slip per period across GRIP.** For each voice, three
   TUNEs (root, +12, +24), GRIP at 0.05 steps, BOW 0.5, BODY 0, vibrato
   off: the bow-point tap's slips per period (falling crossings of half
   the bow velocity over ten periods, the spike's counter) reads 1.0 ±
   0.05 at every step and the regime is PERIODIC at f0. Printed as a
   table per voice. **This runs before the in-tune test and is what
   makes it meaningful**: a pitch test alone passes on a double-slip
   string whose autocorrelation still finds f0 (spike (4): every one of
   63 cells reads f0 by autocorrelation, and 39 of them are not the
   note). The spike gives CELLO's C3 island (0.90–1.00 at amplitudes 0.5
   and 0.2; at 0.8 it is 0.85–0.90, with 0.95 back at three slips, spike
   (4)); R1 measures the edges per voice × TUNE at 0.01 steps, the
   corner floor `c_lo` per voice, and ERHU's whole map.
2. **In tune.** Every snapped TUNE step, both voices, GRIP and BOW at
   default and at 0 / 1: the fundamental within 5 cents of `frequencyFor`
   by `FineTuning.measuredHz`'s windowed FFT (`FineTuning.kt:32`) — never
   autocorrelation (`Pitch.detect` reads the whole waveform's period and
   a dominant harmonic fools it), never a plain zero-crossing count of a
   render that is not speaking. The spike gives −0.5 … +0.9 c over ten
   cells at share 0.85, worst −2.8 c; +12.1 c at 880 Hz without the
   correction. R1 measures the share per voice at both ends of its corner
   range and pins it. The one test that would have caught the documents'
   engine on the first run — it has no note to be in tune.
3. **GRIP is not a pitch knob.** Pitch at GRIP 0 and GRIP 1 within 5
   cents of each other at three TUNEs per voice, *with* the corner moving
   under GRIP — which passes only if the correction is a share of the
   corner's own delay (macros §7.6: a fixed count would read 3.6 c at C3,
   32 c at D6). The spike gives +0.7 against +2.0 c across the whole
   pressure axis at a fixed corner.
4. **BOW is heard only at the start.** The first 50 ms's centroid
   differs between BOW 0 and BOW 1 by at least 1.5× (the bar set from
   R1's first onset table and pinned in the test's KDoc); past the
   voice's onset time (0.5 s at C2, 0.3 s at A3 — spike (5) plus margin)
   the spectra agree within 1 dB per band and slips per period is 1.0 in
   both; loudness identical (`levelTo`). Beside it, **the onset table**:
   time to 90 % of steady RMS and time to the first window at one slip
   per period, at C2, E1 and A3, at BOW 1 with overshoots of 1.0 / 1.5 /
   1.75 — in milliseconds *and* in periods — printed, not asserted,
   until the gate has heard the stab.
5. **Monotonic where claimed.** GRIP's sustain centroid (from 0.5 s)
   non-decreasing over nine steps with FORK's 11 % ripple allowance
   (`ForkTest.kt:329`); BOW's onset centroid likewise. The velocity
   override is the prize, and a voice that fails keeps `soften`.
6. **The LOOP closes.** Whole periods ≥ 2 s (`Siren.kt:87`),
   `Keys.seamError` at the wrap under `MAX_SEAM_ERROR` (`Keys.kt:147,
   :201-204`) at three TUNEs per voice after the iterated retune,
   vibrato off, the stretch starting past the onset and the body's
   longest t60, bit-identical across two calls; the per-note cents
   budget of "Held, and the LOOP" is the measured residual's bar, and D6
   may be withheld by name.
7. **Bow lifted is exact silence.** A render with the bow never down is
   all zeros (spike (10): 0.000000); and pressure 0 is *not* bow-up (the
   table at slope 5 still plays, spike (4)), so the GRIP mapping never
   reaches it and the tail rendered alone from the lift is the *free*
   string (t60 at the formula's slope), never the resting one (1.8 s of
   a string stopped at the bow, spike (7)).
8. **The release rings at the formula's slope, or stops.** On the `Bow`
   alone, lifted with `gain()` untouched, the 20 ms-window RMS falls at
   −0.45 dB per period within ±10 % after the first 100 ms (spike (7):
   900 ms against 1011 at C3; 2.0 s at C2 and 0.42 s at D4 predicted);
   on the rendered Snip, at HOLD 0 and at HOLD 0.95 alike, the stopped
   tail reaches −60 dB within `release` + 50 ms, and at HOLD 0.95 no
   faster than the formula's slope at any window — the ramp only ever
   adds loss.
9. **The series is a sawtooth.** At defaults and at GRIP 1, three TUNEs
   per voice, on the core at native rate: h2 within 3 dB of −6 dB re h1,
   the least-squares slope over h1..h8 between −5 and −8 dB per octave,
   the flyback at least three times faster than the ramp (asymmetry
   ratio < 0.35), the string stuck for over 90 % of the period. The spike
   gives −5.9 / −6.5 to −6.8 / 0.21 / 0.97 at pressure 0.9. The documents'
   engine gives +0.6 / −1.3 / — / — (empirical Table B).
10. **Bounded, pinned, DC-free.** Zero non-finite samples over the fuzz
    grid (every macro at 0 / 0.5 / 1, both voices, the BOW overshoot in
    it); the raw core peak under `RAW_PEAK_CEILING` (1.25, pinned from
    0.822 / 1.11); `|mean| < 0.05` on every render (spike: ≤ 0.003).
11. **Classifier agreement.** `drumClassFor` agrees with
    `Classifier.classify` across ten HOLD steps at three TUNEs per voice
    (TUNE is in the formula, because the stopped tail's length is t60's);
    no preset in a drum choke group; the LOOP filed by name. Expected
    buckets from the classifier's own gates: TONAL needs `lowRatio > 0.55`
    and `decayMs > 500` (`Classifier.kt:45, :141-151`), LOOP is length over
    1.5 s (`:72, :137`), everything else PERC or SNARE — so ERHU at D4–D6
    reads PERC below 1.5 s by construction (no energy under 200 Hz), and
    CELLO at C2–C3 is the one voice that can read TONAL. The picker's
    table says what it measures, never TONAL by wish.
12. **Aliasing floor.** `harmonicClarity ≥ 45 dB` (`ForkTest.kt:87`) at
    the top TUNE, GRIP 1, BOW 1, on the finished Snip's sustain — the
    friction kink's harmonics mint aliasing before `bandLimit`, and this
    is where it would show.
13. **Determinism through the recipe.** `ArcoPatch.render()` twice,
    byte-identical, one-shot and LOOP; no seed anywhere, no noise; the
    `DeterminismTest` canary (`DeterminismTest.kt:36, :51`'s shape).
14. **Recipe round trip and names.** Presets through
    `toJsonText`/`fromJsonText` unchanged; ≤ 14 characters, uppercase,
    unique, eight per voice; the blocklist with the new terms; the
    near-miss list ("SOLINA CELLO 74", "ARP STRINGS", "EMINENT 310") and
    the clean list ("SHARP BOW", "HARP DOUBLE", "MEDIEVAL BOW"); spread
    `rmsDistance > 0.05` (`ForkPresetsTest.kt:70-107`).

### The rest

- **Fuzz:** every macro at 0 / 0.5 / 1 across both voices — finite,
  bounded, a pitched tone at f0 with one slip per period, finishes. This
  alone would have caught the documents' engine (no note) on the first
  run.
- **Peak ≤ 0.99** (`levelTo`'s ceiling), never `limitPeak(1f)`.
- **Strings unchanged** (R0): the frozen grid passes after the split
  (`StringsTest.kt:23-40`); `next(x) == inject(x + reflected())` sample
  for sample on a Loop with every branch live (stiffness, jawari,
  dispersion); the budget identity — two `tune()` calls at β and 1 − β
  sum to `T − τ_bridge − τ_nut − 1.0` within 1e-9 at seven roots; two
  bare Loops closed by hand at fb −1 and −1 ring at f within 5 cents and
  `retune` moves them (toolkit §1.3).
- **The `Bow` alone** (R1's toolkit half): speaks at defaults; in tune
  within 5 cents at 65 / 130 / 220 / 440 / 880 Hz with the correction;
  lifted is exact silence; |mean| < 0.05; the raw peak under the ceiling
  on a 3 × 3 × 3 grid; `retune` moves it; a `Bow` at every voice's top
  TUNE constructs (the β floor).
- **Vibrato:** pitch excursion ≤ 15 c peak on a 3 s pad; zero on a LOOP;
  one slip per period with vibrato on at HOLD 0.95.
- **Identity:** 200 SCRAMBLEs, every one PERIODIC at f0 with one slip per
  period, unclipped, never LOOP.
- **Keys (R2):** nine zones with no gap, every zone in tune, every seam
  closed, the hard layer brighter than the soft, a seam that does not
  close refused by name, cancellation reaching the render —
  `ResinHeldTest`'s list.
- **Render time:** a printed ms-per-second beside the sweep, against the
  spike's 9.9 for the bare loop and the estimate of 35–55 finished.

### Why each of the addendum's three tests is replaced

| Addendum's test | What it proves | Replaced by |
|---|---|---|
| "zero total pitch drift over 1.72 s" on a pure tone through the ensemble | its premise is false (1.724 s is one slow period and 10.085 fast ones; the true common period is 100 s, dsp F24); the integral of any bounded periodic delay's derivative over its own period is zero, so a one-tap chorus passes it too (dsp F27); and a pitch detector on three beating copies reports the loudest copy or nothing | ENSEMBLE's own tests: the per-tap deviation range asserted from the constants with no audio; the L/R correlation as the width claim; the level ripple printed |
| `Loudness.of(monoFold)` within 1.2 dB of the stereo | a tautology — `Loudness.of` folds to mono itself (`Loudness.kt:21`), so the difference is 0.00 dB on any signal; if meant as "the fold is not quieter than the channels", the presets' amounts fail it at −1.7 to −1.9 dB (dsp F28; empirical §F1–F2) | the fold test in the house's shape (average, never sum; the SNARE fold-down test's form (`ThumpTest.kt:362`, the one `Punch.kt:114-118` describes)), with a named bound the gate sets |
| "wire `ArcoPatch.ENGINE` into `Patches.fromJsonValue()` and `Presets.forVoice()`" | a registration step, not a test; the compiler and `PresetsTest` are the tests | tests 13 and 14 |

## Phasing and gates

Every phase ends the house way: stop and listen. A listening page renders
the phase's voices at defaults and at each macro's extremes, and the
chips — the verdict buttons on the listening page, saved beside the clips
— are the gate. Phase 0 is done; R0 to R3 are the pull requests that
follow, R0 being the shared toolkit change that must land before any
ARCO code; ENSEMBLE is its own PR and can go first.

| Phase | Ships | Gate |
|---|---|---|
| **0 — spike** (done, recorded in `../plans/2026-09-29-arco-phase-0-spike.md`) | the documents' engine rendered (Appendices D–G of the record); the corrected two-segment bow on the house's primitives beside every single-ring variant, five iterations, every number in Appendix A of the record | **speaks *and* in tune**, in that order: single-slip Helmholtz in a measured region; ±2.8 cents over ten cells with the share. Both met on the spike's own grid; the full TUNE × GRIP grid per voice is R1's entry rule, run on the built `Bow` |
| **R0** (shared with BORE) | `tune(roundTrip)` fractional and carried by `Loop`/`retune`; `reflected()`/`inject()`; `StringsTest`'s three additions; **no audio change** — or, if BORE's R0 has landed, one KDoc sentence and two tests | the frozen-grid hashes match (SILK 1a) |
| **R1** | `Strings.Bow` and its tests; `Arco.kt` (CELLO and ERHU, five macros, the stroke, the window-mapped grip, the lift and the stop, the LOOP render behind the seam test, no landing chain), `ArcoPatch`, `ArcoPresets` (8 + 8, by ear against the built engine), registration in `Patches`/`Presets`/`Velocity`, `SynthKits.arco()` and its testkit kit, the tests above, the blocklist terms, the audition page. Mono | **the audition** (below); each voice enters only by passing the entry rule — a contiguous single-slip GRIP window at least half the knob wide across TUNE 0–24, in tune within 5 cents at defaults |
| **R1.1** | the phone: the picker entry and its arms, `→ SURFACE ▸` at LOOP, the LOOP readout, the toast; README's count | built in a session that can see `:app`; the gate's verdict is its precondition |
| **R2** | the research pass in SILK's form (`2026-09-2x-arco-research.md`), between R1's gate and this round; `Keys.arcoPad` + `InstrumentSuite.renderArco` + `HeldSpec.Arco` (MAKE INSTRUMENT) with the one-shot `Keys.arco` beside it; SARANGI as ERHU + WASH; the sixth macro (BUZZ or β); the withheld LOOPs if R1 withheld any | the instrument under two hands for ten seconds: the seam, and whether the bow's stick-slip repeats audibly at the loop period |
| **R3** | CONTRABASS as a root; WHEEL with a TUNE-following bourdon and BUZZ; DRONE TO LOOP (SIREN's fourth door); stereo after U4's rack audit; presets re-heard and extended | each its own listen |
| **ENSEMBLE** (independent; PR-E1 may precede R1; PR-E2 after E1's gate) | E1: the rack section, its `FxTest` entries and `stereoExcluded` row, the lifted `Dsp.tap`, the pad sheet, README's rack list; E2: the landing table beside the roster and the STRING MACHINE presets on VELVET BRASS and RESIN BRASS | E1: a four-clip page — a THUMP kick, VELVET FANFARE, RESIN WIDE SECTION, a VOX CHOIR, each dry and at DEPTH 0.25 / 0.5 / 1 with WIDTH 0 and 1, the stereo file beside its fold; FANFARE through ENSEMBLE must read "string machine", and the fold must not pump past the printed ripple. E2: E1's verdict; the engine's own gate then A/Bs the addendum's stage against E1 (audition item 9) |

Order of work inside R1: the `Bow` and its budget first (spike-backed),
then the window map on the built `Bow` (the macro contract — GRIP's
edges, the corner floor, the per-voice β, `RHO_MAX`), then the stroke
and the onset table, then the LOOP with the iterated retune, then the
kit and the page — SILK's nonlinear-risk-last rule inverted, as BORE
inverted it, because here the nonlinearity is the note.

**R1a, as built (2026-10-01): `Strings.Bow`.** PR 1 of R1 is the bow in the
shared toolkit and nothing else: no audio change (the two frozen grids in
`StringsTest` stay green untouched), no voice, no macro. R0 was already done,
because BORE landed the fractional `roundTrip`, `reflected()` and `inject()`
first, so the toolkit half is one class, one KDoc sentence on `tune`'s
`roundTrip`, and the two R0 tests the record still owed (the two budgets sum to
a period less every stage's own delay at seven pitches to 1e-9, and two bare
loops closed by hand ring at the note to within 5 cents). Where the built class
differs from the sketch: **three `tune` calls**, not two (one to read the bridge
filter's delay back, then one per segment); `bowPoint` exposes the string's
velocity under the bow, which the slips-per-period measure needs; `rhoMax` is a
constructor argument, so decision 7's grid can be re-run without editing a
constant; `share` and the bridge corner are constructor arguments with the
measured 0.85 and 3023.6 Hz as documented defaults (a voice pins its own);
`retune(f)` re-solves both segments and there is no nut-only retune (vibrato's
few cents and a glide's 64-sample steps are click-free through it, a jump of
semitones clicks and is not what it is for). **The spike's numbers were its own
model's, and they were re-measured on the built bow** (the Loops add a two-tap
average and a nut one-pole the spike's rings did not, and the returned tap is
the wave leaving the bow, not the bridge's): single slip at pressure 0.7 and 0.9
at C3 and A3 with the same sawtooth signature (harmonics 2 to 4 at -5.9, -9.3,
-11.6 dB); pitch within 3.8 cents from 65 to 880 Hz at share 0.85 (the spike's
worst was 2.8); a lifted bow's ring-down at -0.439 dB a period against the
formula's -0.454; 31 cells finite with a raw peak of at most 1.169 (so
`RAW_PEAK_CEILING` 1.25 has 7 percent over the worst, not 12 over 1.11), the
mean at most 0.0049. **What it found that the record did not say:** the share
that holds CELLO's range is not ERHU's. At D6 (1174.66 Hz) share 0.85 reads
+6.8 and +2.4 cents at pressure 0.5 and 0.9, and 0.8 reads +2.4 and -1.7, while
0.8 at 880 Hz reads -3.5 and -6.3: **the share is per voice, as the record said,
and R1b has to pin ERHU's on the built engine** (the position floor at D6 is
0.0724 with the 3023.6 Hz corner, so ERHU's lowest GRIP corner cannot go under
about 1300 Hz at position 0.133; the bow tests assert that a 1500 Hz corner
builds there and a 1000 Hz one is refused). **And CELLO's root is not the
single-slip note the table above assumed.** At C2 (65.41 Hz), position 0.133 and
the full 3023.6 Hz corner, the built bow slips three times a period at
pressure 0.5, 0.65 and 0.7, once at 0.8, and twice at 0.9 and 1.0; with a
1000 or 1500 Hz corner it slips once across pressure 0.8 to 1.0 (G2, 98 Hz,
slips once at every pressure from 0.5 to 1.0 with a corner of 1000 to 2000 Hz). So CELLO's GRIP window at its
root sits at lower corners and higher pressures than the record's "single-slip
from 1000 to 3024 Hz at pressure >= 0.7", which was measured at C3: **drawing
that window per voice on the built engine, at the root and across TUNE, is the
first job of R1b**, before any preset is written. The pitch is in tune at C2
whatever the slip count (-0.6 and -0.7 cents).

**R1b, as built (2026-10-01): the engine.** PR 2 of R1 is `Arco.kt` (the player on
`Strings.Bow`), `ArcoPatch`, `ArcoPresets` (eight a voice), the registration
(`Patches`, `Presets`, `Velocity`, `SynthKits.arco()`, the two generator tasks,
the blocklist's four terms, roadmap row S20), the tests, the kit and the
audition page. Where it differs from the record, and what building it found:

*Two small differences.* There is **no `Arco.landingChain`** function: pads land dry and
nothing passes `fx` (the kit and SEND TO PAD included), so there is no door to move a landing
through until the gate asks for one. And there is **no seed**: a bow needs no noise and nothing
here is seeded, so the render is a pure function of the macros.

*The window was drawn per voice and per semitone, and "single slip" had to mean
"locks".* The first job (the record's, and R1a's) was the GRIP window on the built
bow. Three things the record did not say. **A window checked every third
semitone had holes**: pockets of two and three slips at C#2 to D#2, in the middle of
the pressure range, at a pressure that climbs as the note falls. **A low string does
not start in Helmholtz motion**: it begins in a multi-slip scratch and locks into one
slip a period after a while (CELLO C2 0.5 to 0.9 s, G#2 and up under 0.75 s, ERHU under 0.4
s), so a cell counts when it locks for good, and the dense map (3,330 cells) was read as
lock times. **The slip counter miscounted**: ten nominal periods of a note a few cents off
its pitch hold 9 or 11 slips one window in fifty, which made half the cells look
unstable; the tests read the *gaps* between slips instead (a gap that is not one
period long is a double slip, whatever the pitch error: `ArcoMeasure`). The path that
came out: pressure 0.97 at C2 falling 0.02 a semitone to 0.85 for CELLO and 0.94 to 1.0
for ERHU, corner 1000 Hz at GRIP 0 and 1500 Hz at GRIP 1 rising to 3023.6 by G#2 for CELLO,
1500 Hz at the root rising to 3023.6 by 21 semitones at GRIP 0 and 3023.6 at GRIP 1 for ERHU.
0 of 495 cells (45 steps by 11 GRIP points) fail; the slowest lock, at BOW 0.5, is 0.87 s (CELLO)
and 0.24 s (ERHU); the pitch is within 4.0 cents. ERHU's pressure floor is 0.94 and not
0.85 for *pitch*: pressing harder raises a high string by about 30 cents a unit, and a floor of 0.85
would have GRIP move the note 5.92 cents at C#5, 6.60 at F5 and 6.35 at F#5 across its travel
(the control in ArcoTest's pitch-knob test), a knob that must not also be a tuning knob; at 0.94 it
moves it 4.50 at most (F5; CELLO 4.13 at C4).

*Voices and the share.* CELLO C2 to C4 (24 semitones). **ERHU D4 to A5, 19 semitones,
the record's own fallback**: the first maps found no cell at D6 that slips once a period at
any pressure the grip can reach, B5 (21 semitones) the last step that does, so ERHU stops two
semitones short of it at A5. **One share for both
voices, 0.85**: R1a's note that ERHU needs its own is superseded, because inside ERHU's window
(pressure at least 0.94, the corner floor rising with the note) 0.85 holds all 20 steps
within 4.0 cents (the D6 miss was outside the range it ships). The bridge's corner floor
rises with ERHU's pitch because a high string cannot be given a dark corner: from D5 a 1500 Hz corner
never locks, from F5 neither does 1750 Hz, at A5 neither does 2250 Hz. The lock is the whole reason: a
1000 Hz corner still builds at every ERHU step up to A#5 and is refused only from B5.

*The stroke.* BOW 0 is a 400 ms slow bow and 1 a 10 ms stab; above 0.5 the bow bites
(velocity up to 1.75 times its sustain, the pressure to the window's top, relaxing with the
attack). **The default BOW is 0.5, not the record's 0.6**: lock time is erratic in the
stroke's length (at 0.6, with no bite at all, C2 to G2 lock at 1.0 to 1.2 s; at 0.7 at 0.4 to 0.85 s;
at 0.5 at 0.32 to 0.63 s, falling steadily with the note), and at 0.6 a default CELLO note at E2 to G#2
was still scratching when its 0.854 s bow lifted. The onset, read on the raw
core at the default GRIP (ArcoTest's onset table): milliseconds to 90 percent of the settled level at BOW 0 against
BOW 1, CELLO C2 704 and 422, C3 448 and 372, C4 359 and 177; ERHU D4 358 and 161, G#4 361 and 95, A5 370 and 52. And
milliseconds to one slip for good at BOW 0 / 0.5 / 0.6 / 0.8 / 1: CELLO C2 800 / 631 / 595 / 496 / 504; C3 468 / 319 /
293 / 311 / 408; ERHU A5 369 / 122 / 110 / 70 / 55. BOW 1 is slower than 0.8 at C3: the 1.75 times bite scratches. A
HOLD 0 stab at CELLO is a swell: the eight kit pads (A01-A08, CELLO at HOLD 0 and the default BOW, 0.500 s long) read
in 25 ms windows are at half their peak by 0.11 to 0.16 s, at 90 percent of it by 0.22 to 0.27 s and at the peak by
0.27 to 0.29 s.

*HOLD, the stop, and the DC.* HOLD is SIREN's mapping (0.3 to 4 s, attack inside it, the top step a
LOOP, SCRAMBLE capped at 0.95). After the 50 ms ramp the bow lifts and **the string is stopped by a
constant extra loss at the bridge that makes it fall 60 dB in exactly `max(0.15 s, min(0.5 hold, free ring))`**
(`Arco.stopScale`). The first version ramped the bridge gain from 1 to 0 over the release, and a
gain applied once a period compounds: the string was 60 dB down in 0.5 s of a 1.8 s release at C2, leaving
more than a second of near silence in the render (the kit's pad lengths found it). Measured on the
audible part of the wave, the tail is 60 dB down, against the note's own level just before its velocity ramps down, at
0.63 to 0.89 of the release at CELLO C2, C3 and C4 and ERHU D4, C5 and A5 at HOLD 0, 0.4 and 0.95 (ArcoTest's
stopped-tail test). **What the raw wave also carries is a static offset**: the onset leaves
a net displacement that drains only at the bridge's own 0.95 a period, -0.44 dB a period at every
pitch, so an RMS ring-down reads that and not the note (StringsBowTest's 3.2 s resting ring at C3 is this
offset's; read on the fundamental the free-ring formula is within 1.2 percent of a lifted bow at six pitches). The output's mean
removal and 20 Hz high-pass take it out, and a test of the raw wave's tail must too. A default note is
0.85 s of bow and renders 1.33 s at CELLO, 1.05 to 1.30 s at ERHU (a note, not a LOOP, at every step);
the 1.5 s line is crossed at HOLD 0.447 at C2 and C3 and up to 0.560 at A5.

*The vibrato is a read-back delay of the finished wave, not a retune of the bow.* The record's
(and R1a's) route, `Bow.retune` every 64 samples, was built first and was click-free. It was not
glitch-free: on ERHU's short periods a slip split in two (gaps of 0.2 then 0.8 of a period) at the same
phase of the swing every time, 37 events in 24,746 ERHU slips (CELLO: 1 in 8,337) and in 91 of 200 rolled ERHU
notes, none without the vibrato: the friction is a hair trigger and a retune is a nudge. So the
vibrato is the tape kind, +-10 cents at 6.1 Hz from 0.35 s and rising in over 0.2 s, scaled by the
bow-on time (none at 0.6 s or less, full from 1.5 s), applied to the string's wave before the box with a
four-point cubic; the string, and so the lock, never feel it, and a LOOP carries none.

*The body.* CELLO: air 104 Hz t60 0.254 s and plate 220 Hz 0.180 s, gains 1.0 and 0.8, shape. ERHU: the
house membrane at the open string's 293.66 Hz, shape, **its decay scaled by 0.25** (the table's first
mode rings 1.2 s; scaled, 0.3 s). **`Strings.bodyRing` follows the box's ring out past the string's end, which would make BODY
change how long a note is** (a default CELLO would render 1.585 s and be filed a LOOP, and every ERHU stab would be 0.3 s
longer than its string with the scaled box, 1.2 s with the table's own): the engine cuts the ring where the stopped
string ends, so BODY never moves the length and `renderFrames` is closed-form (pinned to the real frame count in 60 of 60 cases).

*The LOOP is BORE's route*: `Bore.planLoop` and `Bore.measureLoopSamples` reused (one quantity in one
place), the stretch rendered fresh at `tuned * ratio` until the ratio is 1 to 3e-7, the seam read on the kept
stretch against itself one loop later. A steady stretch has a fixed 63 ms stroke and no bite, so a LOOP does not depend on
BOW. **Warm-up 2.0 s and 200 periods**: 1.0 s and 100 periods left ten GRIP-1 loops over the bar at C#2 to B2 (up to
2e-2); with the longer one all 225 loops measured (every step of both voices at the default and four other
corners) close, the worst at 2.5e-4 against the bar of 1e-3, and the fuzz test's 90 loops at 2.47e-4. A render takes 0.3 to 1.0 s.
The record's "withheld per voice by TUNE" is not built: no loop failed, so `renderLoop` throws by name
if one ever does, as BORE's does. The finished chain costs 43 ms (CELLO) and 54 ms (ERHU) a rendered second.

*The roster, the kit, the page.* Eight presets a voice, two of them the LOOPs (ENDLESS DRAW, ENDLESS CRY), each
checked by name against the classifier. The engine files every note PERC under the 1.5 s line (LOOP over it, or at the top
step) and nothing consults the classifier for a synth pad. The classifier would still hear a drum in some notes (it reads
CELLO C2 to G2 as a kick or tom and ERHU F5 and up as a snare for slow bows and short holds, a knife edge from note to
note), so the roster is held clear of every drum by test and the filed length is held to the line to the frame. The kit
is A01-A08 CELLO up the minor pentatonic at HOLD 0, A09-A14 six ERHU presets, A15-A16 the two LOOPs, all dry. The page
as built runs the held note (the owner's pass rule, below), the stab check, the surface stand-in, the kit, each voice's
knobs, presets and LOOP, and the vibrato A/B: items 4, 3, 1, 2, 5 and 12 of the record's list below, which keeps the
record's numbers. Items 6 to 11, 13 and 14 are not built in R1b; each waits for the gate that needs it.

*What the tests found that changed the engine.* The slip-counter fix above; the stop's compounding; the DC; the default BOW;
the vibrato's split slips (ArcoTest's held-note test and ArcoProductTest's scramble test found the
same events from two directions); `bodyRing`'s length; the warm-up. *What stayed as the record had it*: `RHO_MAX`
0.98, position 0.133, the sustain velocity 0.13 (one for both voices), the three-way split of body, grip and stroke.
*Still open, for the owner's ears*: the stroke's bite (0.6 to 1 is a listening value and slower to lock at some
notes), the box's gains, the vibrato's rate and depth, whether a HOLD 0 stab at the bottom of CELLO (a swell) is the
pad anyone wants, the default HOLD, and the SECTION chip's default amount.
*Not built here*: the bridge port GYRE asks for in the next note. R1b leaves `Strings.Bow` exactly as R1a landed it
(no `Strings*` file is in this PR), so the port still lands as its own no-audio-change change before GYRE R2.

**R1c (2026-10-02): the owner's first listen, and what it changed.** The owner listened to R1b's page and the verdicts are on the page's own
store (`verdicts/arco_r1_*`). *The pass rule*: the bowed note was picked out of three unlabelled three-second clips **six times out of six**
(CELLO C2, C3, A#3; ERHU D4, C5, G5; sure on four, unsure on ERHU C5, no confidence set on CELLO A#3), so both voices pass the held-note
gate and, per the rule above, their presets stand. Not answered: whether the bowed note still reads as a synth once you know it is bowed (the
second half of the rule; the note boxes were all empty). *The stab*: BOW 1 yes, BOW 0.5 nearly; the two brass comparison clips and the
question about a swell at the bottom of CELLO were not answered. *The knobs*: GRIP 1 and HOLD 1 (both voices), ERHU BOW 1 and ERHU's vibrato
were yes; **BODY 1 (both voices), CELLO BOW 1 and CELLO's vibrato were nearly**, and asked which way, the owner said not enough box, not
enough bite, too mechanical. ERHU's box: B, the box as shipped, unsure, so its table stays. Everything the owner did not mark (the presets,
the kit, the loops, the knobs' low ends) is unheard, not approved.

R1c changes those three, and re-authors two presets the changes moved. **It is not "nothing else changes": the bigger CELLO bite changes the
BOW 1 stab the owner marked YES** (below). What it found:

* **BODY.** `Strings.bodyRing`'s `amount` is the box's RMS over the string's, and BODY was it, so BODY 1 was a box exactly as loud as the string.
  BODY is now the same up to `BODY_KNEE` 0.5 (the box call is R1b's to the bit at the default and at every preset at or under 0.5, in both voices; kit
  pads A01 to A08, A11 and A16 are byte-identical) and climbs to `BODY_TOP` 1.75 times the string at BODY 1 (measured 0.50, 1.12 and 1.75 at BODY
  0.5, 0.75 and 1 in both voices, finished peaks at most 0.66). A louder box moved ERHU MOON FIDDLE at BODY 0.85 (now 1.375 times the string) from a
  classifier ring of 813 ms to 522 ms, 22 ms from the 500 ms line, so that preset's BODY is 0.8.
* **The bite.** R1b's CELLO bite hardly showed: against the same stroke with none, the best of the 0-50, 50-100 and 100-200 ms windows was within
  0.5 dB at F2, C3, G3 and C4 (0.3, 0.5, 0.5, 0.4; over the whole 200 ms it was +0.2, -0.2, +0.4, -0.6). It relaxed with the attack's own 10 ms
  time constant (a hump of 1.28 times the velocity, over in 27 ms) and a bass string needs many milliseconds to build a period. The bite is per
  voice now (ERHU's numbers are as they were) and CELLO's has three: `OVERSHOOT_MAX_CELLO` 3.0 times the velocity, `BITE_SECONDS_CELLO` a 60 ms
  time constant (at least; the attack's if longer), and `BITE_PRESSURE_CELLO` half of the bite's share pressing the string toward the window's top
  (the rest of the accent is speed alone). That is +2.0, +2.9, +3.0 and +2.7 dB in the best of the 0-50, 50-100 and 100-200 ms windows at those notes.
  **Three settings were tried before it, and each broke something the search had not yet looked at**: 3.0 times for 60 ms with all of the share in
  the pressure never locked SHORT STAB in its 0.30 s stab; 2.75 times for 60 ms passed the roster but put C3's BOW 1 at 600 ms to reach 90 percent
  against BOW 0's 448; 2.5 times for 120 ms with a quarter of the share in the pressure kept both but let only 18 of the 25 TUNE steps lock inside
  a default note at BOW 1 (R1b: 23) and left one scramble roll in 200 late. A few hundred settings (velocity 1.75 to 3.5 times, 20 to 140 ms, a
  pressure share of 0 to 1) were run against the overshoot row (1.5 s), the onset claim, the roster's locks, the lock across BOW (2.0 s), the
  scramble test, the default-note count and a gain of at least 1.5 dB. 3.0 times for 60 ms with half of the share in the pressure keeps them: the row
  at most 1.44 s, the lock grid 1.47 s, C3 at 434 ms against 448 (a thin margin), 22 of 25 default-note steps locking, none of the 400 scramble rolls
  late (so the original strict claim stands), SHORT STAB at its authored BOW 0.9 (0.22 s), and the steps where BOW 1 is not sooner than BOW 0 down to
  F#2, G2 and G#2. GRIT BOW's BOW is 0.65, not 0.85 (at 0.85 it no longer locks inside its bow; at 0.65 it locks at 0.45 s of 0.58), and DRY SCRAPE,
  which R1b read as never locking, now locks at 0.33 s of its 0.34 s bow, a scrape in all but its last 10 ms (its test clause says so).
  **What no setting kept is the BOW 1 stab the owner marked YES**: SHORT STAB's macros at BOW 1 on the figure's three pitches (C3 C3 E-flat3 G3 G3
  E-flat3 C3, 0.3 s each). G3 still locks (0.21 s, R1b 0.214), C3 never did, and **E-flat3 locked at 0.189 s and is now a three-slip scrape for its
  whole 0.3 s**; of the 80 settings in the last search, 19 kept the other bars and every one of those 19 loses it, because R1b's bite was so small that this note only just locked. Across all 25
  steps the HOLD-0 stab at GRIP 0.7 and BOW 1 that locks by 0.255 s falls from 10 steps to 6. The retune page plays that stab before and now so the
  owner can say whether the extra bite is worth it.
* **The vibrato.** R1b's was a constant: every swing 10.0 or 10.1 cents at exactly 6.10 Hz, which is what "mechanical" measures as. CELLO's is
  now `VIBRATO_HUMAN`: the rate drifts by up to 7 percent, the depth by up to 18 percent (each a sum of three slow sines whose frequencies share
  no period inside a note, so it is never the same twice and still a pure function of time: no seed), the swing leans a little (a second
  harmonic) and it swells in over half a second, against a fifth. Measured swing peaks 8.1 to 11.8 cents (12.75 at its highest over four
  seconds), half-swing rates 5.1 to 7.1 Hz, mean pitch -0.02 cents. ERHU keeps `VIBRATO_PLAIN`, the old sine term for term, held bit for bit by a test.
* **Still open for the owner's ears**: all three amounts (`BODY_TOP`, the bite's three numbers, the drift's two), whether the stab's extra bite is worth
  E-flat3 and its neighbours scraping, whether CELLO's default-HOLD notes (0.85 s of bow, 2.8 cents of vibrato, which now drifts) are better or worse
  for it, the stab against the two brass stabs, whether the bowed note still reads as a synth, and the unheard half of the roster.
  `generateArcoRetune` writes the small before-and-now page for exactly these (BODY 1, CELLO BOW 1 with a stronger step beside it, the BOW 1 stab,
  CELLO's vibrato).

**R1c heard (2026-10-02): the retune page's verdicts** (`verdicts/arco_r1c_*` on its own store, saved 17:38 to 17:40 UTC). The owner marked no chip on
BODY or BOW and wrote in the note boxes instead. *BOW* (CELLO's bite): the note reads "Stronger" and the overall answer "Now is good", so NOW's
bite is accepted; the one word "Stronger" is read as a description of NOW against BEFORE, not a request for the stronger step beside it, and
the owner is asked to say so if that is wrong. *The stab*: C3, E-flat3 and G3 NOW all yes, "Good" and "Works for me", so **the E-flat3 that
the bigger bite turned into a scrape is accepted** (R1c's open cost above is closed). *CELLO's vibrato*: C2 and C3 NOW yes, "Now versions are good",
overall "Yes" (ERHU's unchanged control was not marked). *BODY*: the note reads "Body doesn't seem to do anything" and the overall answer "Body
still seems a little light", so **BODY is still nearly, and 1.75 times was not the fix**. Two of the three R1c changes are therefore settled
and the third is open.

Why BODY 1 moved so little is not a constant to turn up further, and the measurement says so. On the BODY page's CELLO note (C3, 130.75 Hz) the band
levels of BODY 1 NOW against the default (0.5) are +1.0 dB at 50-150 Hz, +0.4 at 150-300 and **-3.6 to -5.0 dB at 300 Hz and above**; ERHU's note
(C5, 524 Hz) moves +0.7 dB at 300-600 Hz, -1.2 at 600-1200, -3.7 at 1.2-2.4 kHz and -4.6 at 2.4-5 kHz (its two bands under 300 Hz hold almost
nothing). The three clips of each voice have the same RMS (0.0253 to 0.0259 CELLO, 0.0259 ERHU), so what the louder box adds the top of the note
gives up: it shows as a darker note, not as a box. The working diagnosis, to be measured
and not yet settled: CELLO's box is two narrow resonances (104 Hz, Q about 12; 220 Hz, Q about 18, so about 9 and 12 Hz wide), and a held bowed
note is only harmonics, so a resonance changes the steady note only where a harmonic lands within a few hertz of it (on C3 the first partial is 27 Hz from the 104 Hz resonance and the second 42 Hz from the 220 Hz one),
and it otherwise rings only in the transients and is cut where the string ends. A box that a held note can hear has to be broad (a spectral
envelope the harmonics sit on), which is a different shape and not a bigger amount. That is R1d's work, and **the first thing it must keep is
everything at or under BODY 0.5, to the bit** (the default, the kit and the presets that were not heard to be wrong).

**R1d heard (2026-10-03): the BODY page, and what it taught.** The page (`generateArcoBody`, merged in #432) played twenty clips (ERHU C5, CELLO C3, CELLO C4)
blind: THE PLAIN ONE (BODY 0.5), a hidden exact repeat of it, design A (a bank of broad peaking filters) and design B (one broad modal row) at two strengths
each, and a control (R1c's narrow box at 12 times the string). The owner answered on headphones (`verdicts/arco_r1d_*`, 01:03 to 01:07 UTC): **both hidden repeats
SAME; every one of the thirteen candidates DIFFERENT; none MORE BOX; none LESS BOX**; WHAT YOU HEARD LAST TIME was not marked. In words: "Body just seems to be dull
and muffled rather than full and resonant" (which one to keep) and "Sounds muffled when different than original" (too much). Asked what BODY should add, the owner
picked **Warmth and Weight** (not Ring, not Space), and said **louder is fine**.

What the clips measured (steady part, share of the clip's energy against THE PLAIN ONE, dB): every candidate cut the content above 3 kHz by 7 to 20 dB (ERHU C5: B 6.0 -19.1,
A 1.0 -16.0, the control -13.2; CELLO C3: the control -19.5, B 6.0 -17.9, A 1.0 -10.7), and R1c's own BODY 1 by 2 to 5 dB. **The page levelled every clip to the same loudness, so any
boost of the low and mid harmonics ducked the top: a duller note, and that is what the owner heard.** The three designs, the ruler and the judge panel all missed it, because
the ruler (D, a spectral-envelope distance with the loudness equalised) *rewards* that darkening: "D is not audibility" was written into the page's brief and still steered the
ranking. Two other beliefs of this round did not survive the answers: the phone-speaker explanation of R1c's "does nothing" (the owner uses headphones) and design C's
snapping (never put on the page). What did survive: the repeat caught nothing wrong (the owner's ears were reliable), the narrow box does not colour a held note, and
the knee at 0.5 is untouched by all of it.

**The principle R1e carries: the body is added on top of the string, at the plain's own gain, and the note is allowed to be louder.** A real body radiates more, it does not trade
the string's top end away. The warmth page (`generateArcoWarmth`, test-side only) builds that from a low shelf (weight) and a broad bell (warmth) at +3.5 and +6 dB, with the
string at THE PLAIN ONE's gain (the helper reproduces `Arco.render` at BODY 0.5 bit for bit), a louder-only control matched to the shaped clip's loudness, and chips FULLER, SAME,
DULLER, DIFFERENT. **Engine hand-off, not done yet**: `Arco.finish` must level BODY above the knee from the plain's gain and not from the boxed signal; the shaped string at the plain's
level peaks above ArcoTest's finished-peak bar of 0.95 at CELLO C2 and F#2, so the engine step must back the rung off on low CELLO notes, give headroom or accept a ceiling duck, and
never loosen the bar; ERHU's fixed corners (600 and 800 Hz) are the scout's and untested on its highest notes (WEIGHT alone reads only +0.6 dB on A5 against +2.2 on C5).

**A request from GYRE (2026-10-01): a bridge port on `Strings.Bow`.** GYRE
(`2026-10-01-gyre-coupled-string-engine-design.md`, G3 and "Decisions
taken") couples four strings through a shared bridge. Its R2 bows those
strings with this class, unchanged in its friction law and junction. But
the bridge reflection (`−REFLECTION` through the `BRIDGE_HZ` low-pass) lives
inside the bridge `Loop`, so there is no place at the physical bridge to
apply GYRE's coupling. The owner's decision is that the port lands as a
no-audio-change change before GYRE R2: the bridge segment becomes an
out-and-back pair with a pluggable far-end reflection whose default is
exactly today's, proven by `StringsBowTest` and a frozen grid of bow
renders. R1b may land it if convenient. Nothing in ARCO's sound or
roadmap changes.

**The audition page** (`ArcoAuditionGenerator`, the SIREN page's shape
with its Web Audio SURFACE stand-in; every clip through `AuditionLevel`;
TUNE and HOLD are not knobs to audition), as the record planned it
(product §5, tightened by the spike and the three proposals). The numbers
are the record's labels, cited elsewhere in it, and are not the page's
order: R1b built 4, 3, 1, 2, 5 and 12, in that order, and did not build
6 to 11, 13 and 14, each of which waits for the gate that needs it:

1. **Hold the surface** (built third, after the stab) — CELLO LOOP and ERHU LOOP under a finger,
   a pitch slider, thirty seconds. Chips: does the wrap click; does the
   body climbing with the finger (the SURFACE resamples the whole loop)
   read as a fiddle or as a tape; is it fun.
2. **The kit as it lands** (built fourth) — A01–A08 CELLO walking the pentatonic at a
   stab HOLD, A09–A14 six ERHU presets, A15–A16 the two LOOPs; every pad
   dry.
3. **The stab A/B (the secondary check)** (built second) — CELLO SHORT STAB at BOW 1 against BOW 0.5, and both
   against VELVET BRASS STAB and RESIN PUNCHY STAB (`VelvetPresets.kt`,
   `ResinPresets.kt`) in a two-bar pattern at 90 bpm beside a THUMP
   snare, alternating on the bar line, level-matched — the audition
   spec's own rules (`docs/AUDITION_SPEC_2026_09.md:69-116`). Does the
   fast bow read as a bow; is a stab that blooms over 300 ms at C2 a
   stab.
4. **Blind identity: the held note (the pass rule)** (built first) — three seconds of bow (HOLD 0.88) at three
   TUNEs, unlabelled: CELLO bowed / plucked / synth; ERHU fiddle / voice / synth.
5. **Each voice** (built fifth) — default, then BOW · GRIP · BODY at both ends with a
   plain-words line for each end (BOW 0 "a slow bow", BOW 1 "a stab";
   GRIP 0 "a light grip", GRIP 1 "digging in"), then all eight presets.
   ERHU's BODY is three clips, unlabelled: the membrane shape, no body,
   one placeholder row. The swell question sits here: BOW 0 against the
   rack's SWELL on the BOW 0.5 render — is a physical crescendo worth
   the bottom of the knob, or is RISE enough. (The SWELL comparison is
   not built in R1b.)
6. **GRIP's two ends** — the clip labelled *slipping* (the two-slip
   whistle at pressure ≈ 0.3, a real bowed "surface sound") beside GRIP 0:
   should the knob reach it, across a three-slip cell; and the clip
   labelled *raucous* (the multi-slip octave-dominant region) beside GRIP
   1: should the top reach it. Asked before either is built. *Not built
   in R1b; deferred to the gate that settles GRIP's ends.*
7. **HOLD's tail** — HOLD 0 with the stopped tail against the free
   two-second ring at C2 (FORK's honesty, the more beautiful pedal, the
   wrong stab). *Not built in R1b; deferred to the gate that settles
   HOLD's tail.*
8. **The onset** — a C2 stab at BOW 1 as the string builds on its own
   (325–420 ms to 90 %) against the shaped attack (the overshoot at 1.5
   and 1.75) and against the plain render through the rack's SPIKE at
   ATTACK 0.7 (`Treatments.kt:46`): does a stab need the engine to shape
   it, or the rack. *Not built in R1b; deferred to the gate that settles
   BOW's overshoot.*
9. **The string-machine A/B** — the documents' string-machine stage at
   its macro's 0.95 rendered on the corrected engine and folded to mono,
   the same dry render through the ENSEMBLE section at DEPTH 0.5, and
   VELVET FANFARE through ENSEMBLE, level-matched by `Loudness.of`. If
   the owner cannot tell the first two apart the in-engine stage is
   redundant; if the third reads "string machine" the source question is
   closed and PR-E2 has its answer. *Not built in R1b; deferred to the
   gate that settles the string machine's home.*
10. **The landing A/B** — CELLO SHORT STAB dry, through SPRING at MIX 0.2,
    through ENSEMBLE at DEPTH 0.5, on the bar line. *Not built in R1b;
    deferred to the gate that settles the landing (a pad lands dry).*
11. **The tarab and the chien, as stand-ins** — SITAR's DOUBLE at 1 and
    SANTUR's WASH at 1 beside ERHU dry, and TERRA's BUZZ on its own voice,
    labelled as what they are, so the owner hears R2's and R3's mechanisms
    on the machinery that exists. *Not built in R1b; deferred to the
    gates of R2 and R3.*
12. **Vibrato** (built sixth) — the 3 s CELLO pad with and without.
13. **The sixth slot** — BUZZ 0.5 beside β 0.08, if β's map found a
    window, on the CELLO default. *Not built in R1b; deferred to the
    gate that decides a sixth macro.*
14. **The phone** *(not built in R1b; deferred to the first run on a
    device)* — a 4 s HOLD and a LOOP render on the test device,
    timed against the 150 ms shimmer (`SynthScreen.kt:185`). No phone has
    been measured for any engine; the estimate is the JVM's 35–55 ms per
    rendered second finished × BORE's 2–4× phone factor: a 0.6 s stab
    inside the shimmer (the bare loop alone is ~6 ms), a 4 s pad past it,
    a LOOP with its settles 0.2–1.0 s.

**Pass rule (the owner's, 2026-10-01).** The engine passes or fails on **a three-second held note, bowed against a
synth**, and the one-second stab is a secondary check. Item 4 is the pass: per voice, at three TUNEs, three unlabelled
three-second clips (CELLO bowed, plucked, a sustaining synth; ERHU bowed, sung, a sustaining synth), and the engine fails if
the bowed note cannot be picked out from the synth or itself reads "synth". Item 3, the stab against VELVET BRASS STAB, is
the second check: if the stab cannot be told from it the engine is redundant as a stab, which is a smaller finding than the held note's.
(The reason for the change is what a bowed string is: it speaks over 0.1 to 0.9 s, so a one-second note is
mostly the onset, and a held note is where a bow is a bow.) Presets are frozen per voice that passes. BOW's overshoot is
set by 3, ERHU's body table by 5, and R2 is gated on 1 and 4: those checks are on the page. The rest are set by the gates
that need them, once built: the swell floor (item 5's SWELL clip, with 8), GRIP's ends by 6, HOLD's tail by 7, the string
machine's home by 9, a landing (and so an `Arco.landingChain`, which does not exist) by 10, the stand-ins by 11, the sixth
macro by 13. A voice that fails gets
no roster — GLINT's state (built, registered, in the picker, no presets, waiting on its audition,
`docs/SYNTH_ROADMAP.md:265`) — and no picker entry, TERRA's and SILK's (`SynthScreen.kt:1828`), not worse.

**Effort**, from footprints (`git show --stat`; toolkit §6, product §6).
FORK R1: 60 files, +8464/−29, about 1,538 hand-written lines
(`82dc292`); FORK R2, one voice from the gate: 7 files, +275/−37
(`16a52b5`); SILK's `Strings` extraction: `Strings.kt` +237, the frozen
copy +171, `StringsTest.kt` +70 (`33d3e0b`); BORE's own table: R0 ~100,
R1 ~1,750, R2 ~530; CONTOUR, the newest rack section: 5 files, +132/−2
(`298a759`), seven with the pad sheet.

| Phase | Files | Hand-written | Notes |
|---|---|---|---|
| 0 | 5 | ~2,350 (throwaway; ~1,750 without the two transcriptions) | `BowSpike.kt` 375, `BowSpikeTest.kt` 733; `ArcoProbeV1/V2.kt` 281 + 332, `ArcoProbeTest.kt` 635 — done, in the record |
| R0 (ARCO first) | 2 | ~105 | `Strings.kt` +35 (the factor on `tune`, `Loop` and `retune`; the split with its KDoc), `StringsTest.kt` +70 |
| R0 (BORE first) | 2 | ~60 | one KDoc sentence, two tests |
| R1, the toolkit half | 2 | ~290 | `Strings.Bow` ~170 (half of it KDoc carrying the measurements: the table's constants, the share's five-root table, the ceiling's three numbers, the β floor), `StringsTest` +120 |
| R1, the engine half | ~18 hand-written, +17 generated | ~1,500 | `Arco.kt` ~380, `ArcoPatch` 35, `ArcoPresets` 50, `Patches`/`Presets`/`Velocity`/`SynthKits` ~35, gradle 18; `ArcoTest` ~450, `ArcoPresetsTest` ~115, kit and audition generators ~230, canaries ~24; docs ~165; plus a ~1,000-line page copied and edited and a kit's 16 WAVs and `.xpm` generated |
| **R0 + R1** | | **~1,900** | a quarter more than FORK R1's 1,538, for one exciter's physics on two voices plus a LOOP render FORK never had; the generated weight a third of FORK's, since there is no instrument in R1 |
| R1.1 | 3 | ~20 | `SynthScreen.kt` ~18, README's count |
| R2 | ~12 | ~550 | `Arco.Held`, `Keys.arcoPad` ~100, suite/sidecar/`HeldSpec`, `ArcoPadMaker`, WASH on SANTUR's bank ~70, BUZZ ~30, tests ~200; a ~3.5 MB instrument generated |
| ENSEMBLE (E1) | 10 | ~240 | `Ensemble.kt` ~140, the lifted `Dsp.tap` +12/−9, `FxChain` +9/−1, `Treatments` +2, `PadSheet` +3, `FxTest` ~65, `TreatmentsTest` +1, `PadSheetTest` +3/−2, README +3/−1 — CONTOUR's 132 in five, plus the stereo clause, the widening tests and the chip inventory |
| STRING MACHINE presets (E2) | ~5 | ~60 | the landing table beside the roster, `Presets.landingFor`, the CLI's `--preset` through a `PadRecipe`, two presets per BRASS voice, tests |

## Out of scope

- **Bow reversal.** A real bow changes direction; the reference model's
  bow velocity is unipolar (`stk_Bowed.h:106`), so every voice here is,
  strictly, a wheel — which is what makes the hurdy-gurdy honest and a
  détaché or a tremolo a phrase, not a note: a `Groove`/ROLL question,
  as SILK ruled for the guzheng's glissando (`silk spec:598-608`).
- **Torsional waves, string stiffness under the bow, the thermal
  friction model** (physics §4) — the reference model has none and the
  gate measures against the reference.
- **The growl as a knob.** A memoryless table does not period-double
  (spike (4): 0 cells of 63); the multi-slip region is a sound, and
  whether GRIP's top may reach it is a gate question, not a promise.
- **The tarab and the chien in R1** — R2's WASH and BUZZ, on machinery
  that exists ("Voices").
- **A drone that follows TUNE, two strings a fifth apart, double stops**
  — `Strings.course`'s summing shape (`Strings.kt:592`) would model any
  of them as a second `Bow`; R3 with WHEEL.
- **The fretless glide as an engine feature.** It is the SURFACE's
  gesture over a LOOP (product §1), not a timbre.
- **Cross-synthesis (`Chimera.kt`: ARCO_TIDE, ARCO_TERRA).** Dropped,
  for a reason the toolkit gives: a bow is a function of its own
  string's state sample by sample ("The bow"), not an `Exciter` that
  hands a buffer to another engine (`Strings.kt:30-31`), so "ARCO_TIDE"
  would be TIDE's fold on ARCO's *finished* render — the rack's CRUNCH or
  RING on an ARCO pad, one chip away today — and "ARCO_TERRA" is the
  bridge wave into a `Modes` table, which is what BODY already does
  through `bodyRing`, with TERRA's rows instead of a cello's. Neither
  needs an engine; if a gate ever wants a bowed bar, it is a BODY table
  swap in a later round, not a file. *Superseded 2026-10-01 (owner):
  bowed and rubbed modal bodies belong to MERCURY, on its R0 modal bank
  ([`2026-10-01-mercury-modal-glass-engine-design.md`](2026-10-01-mercury-modal-glass-engine-design.md),
  decision 4). ARCO stays strings.*
- **The divide-down saw inside the engine, the three-tap chorus** — a
  VELVET/RESIN preset family and the ENSEMBLE section ("The
  string-machine question"); **the formant filters** — the rack's EQ on
  the recipe, never a section ("The string-machine question").
- **Stereo** before U4's rack audit (`docs/SYNTH_UPGRADE.md:271-287`);
  **DRONE TO LOOP** before R2's seam is proven; **a real-time or native
  voice**.
- **PUNCH; the `seed` argument; a `Keys.arco` that returns a bare `Snip`
  with no consumer; the eleven presets as written.**

## Decisions already taken

What this document treats as closed, and on whose authority — so a
reviewer can tell precedent from proposal. BORE's and FORK's tables have
the same three columns.

| Question | Decision | By |
|---|---|---|
| Home | a `:synth` engine, Kotlin, offline, one `Snip` per render (mono in R1; stereo is decision 15) | the documents themselves; FORK's four reasons (`fork spec:22-34`); BORE's restatement |
| Topology | two string segments with the friction junction between them; never a single ring | the spike's measurement of every single-ring variant (spike (9)) and the physics (dsp F30–F33) |
| The friction law | the reference model's reflection table `ρ(Δv) = (|slope·(Δv+offset)| + 0.75)^−4`, clamped; never the documents' relay | two fetched witnesses (STK, Faust — physics §2), and the relay's measured failure (empirical, dsp F2) |
| The bridge filter | a **corner**, `BRIDGE_HZ ≈ 3023.6` at any rate, never STK's pole formula re-evaluated at 176.4 kHz | spike iteration 4: single-slip motion needs the loss STK's 44.1 kHz users hear |
| The bow lifts | a release ramps the bow velocity to zero *and lifts the bow* (`bowDown = false`); lifting is never expressed through pressure — GRIP's window never reaches pressure 0, which in the table is slope 5 and still plays | spike (7), (4) |
| The string toolkit | `Strings.Loop` with BORE's additions #1 (fractional) and #3, landed as R0 — **shared with BORE**; no #2 | spike's recommendation; fleet §3; the two-segment loop's mean ≤ 0.003 |
| Tuning | `Strings.tune`'s exact budget plus a **per-voice pinned correction** measured on the corner effect; never STK's `−4` and never a published formula | spike iteration 5; BORE's residual-pull rule; `Dsp.Ladder`'s KDoc precedent |
| String machine | the engine renders dry; ENSEMBLE is a rack section, its own PR first; the 1970s pad is the BRASS presets with the ENSEMBLE chip, then a STRING MACHINE preset family with a landing table (PR-E2) | the CRUNCH rule, applied to WOBBLE (`fork spec:88`), ECHO (siren spec) and TAPE (BORE); nothing in the addendum is coupled (fleet §5a) |
| Length macro | HOLD, with a LOOP top step; not DECAY | SIREN's precedent (`siren spec:132-136`; `Siren.kt:74-90`) |
| Output | `Tide.bandLimit → Dsp.decimate → mean removed → 20 Hz high-pass → Dsp.levelTo → fadeTail`; no PUNCH | the melodic fleet (`Fork.kt:465-468`, `Tide.kt:549`; the mean removal and 20 Hz high-pass at `Fork.kt:407-413`, which FORK runs before `bandLimit`); `fork spec:305`; FORK's "the DC goes twice" |
| DC | no in-loop blocker (the two-segment loop is zero-mean); the output's mean removal and high-pass stay | spike: mean ≤ 0.003 in every row |
| Bodies | `Modes.fixed` rows through `Strings.bodyRing` after the loop, every number labelled *shape* until a source lands | `Modes.kt:121-125`; SILK's OUD convention (`Silk.kt:436-460`); physics §5 |
| Names | the string machine's product, makers and part numbers off every surface; the blocklist grows with a bounded `arp` (the engine's own name is decision 1) | `docs/SYNTH_ROADMAP.md:27-47`; api §D4's collision check (sixteen HARP/SHARP presets) |
| Velocity | no `brightnessOverride` without a passing sweep (whether to register BOW's line in R1 is decision 8) | the house's registration rule (`ForkTest.kt:329`; `Velocity.kt:238-253`) |
| Presets | eight per voice, ≤ 14 characters, authored by ear against the built engine and re-heard at R1's audition; the documents' eleven not carried | `ForkPresetsTest.kt:70-74`; SILK's "authored blind are disposable" |
| Roadmap row, README count | at implementation, not now; the row is S18 or later | the rule FATHOM, RESIN, GLINT, SILK, FORK and BORE followed; api F15 |
| Presets in R1 | eight per voice, authored by ear against the built engine, in R1 | BORE's decision 10 with its condition met: Phase 0 measured both R1 shapes speaking and in tune, so SILK's "authored blind are disposable" risk does not apply; the presets are still frozen only per voice that passes the gate |
| The LOOP in R1 | behind the seam test, withheld from the readout per voice × TUNE that fails after the retry | BORE's decision 11, the same default; the sawtooth's tighter cents budget ("Held, and the LOOP") is why the withheld path is expected at D6 |

## Decisions for the owner

Each with the default this document takes; a default stands until the
owner or the gate overturns it. This is FORK's "Open for review" and
BORE's list, with the new evidence folded in.

1. **The name.** ARCO (this document: a gesture word, no collision) or
   BOW (plainer, and then the speed macro needs another name) or VIOL
   (the family word). Default **ARCO**; a rename is one constant.
2. **Round-one voices.** CELLO + ERHU (this document), CELLO + ERHU +
   CONTRABASS-as-a-root (two body numbers and a root, but a third
   audition and eight more presets — and first one Phase-0 row at
   41.2 Hz, since its single-slip pressure is unmeasured), or the
   documents' five. Default **two**; GURDY becomes WHEEL when it comes.
3. **The string machine's home, and its order.** ENSEMBLE as a rack
   section, landed first as PR-E1 with its own four-clip gate (this
   document), or the documents' in-engine stage. Default **the rack,
   first** — nothing in the addendum's code is coupled, a stereo engine
   forecloses MAKE INSTRUMENT, and the section answers half the ask the
   day it exists; the level-matched A/B (audition 9) is the final word.
   Within it: DEPTH · RATE · WIDTH (default) or DEPTH · RATE with every
   ensembled WAV stereo; the equal-fold stereo pair at z = 0.15 (default)
   or another row of the table; and the string machine's door — route
   (a), BRASS presets plus the ENSEMBLE chip, no plumbing (default for
   E1), or route (b), presets with a landing table, as PR-E2 after E1's
   gate, with two presets per BRASS voice added under an amended
   twelve-per-voice law or two replaced.
4. **HOLD, and its tail.** HOLD with a LOOP top step, not DECAY (settled
   by SIREN's precedent); at HOLD 0 the string is *stopped* after the lift
   so a stab has a 0.15 s tail (default) or rings free for two seconds at
   C2 and files LOOP (FORK's honesty, the more beautiful pedal, the wrong
   stab). The gate hears both (audition 7). Ceiling 4 s on pads, SIREN's.
5. **BOW's meaning.** The stroke, with an overshoot above 0.5 and the
   sustain velocity fixed per voice (this document); or the ramp alone;
   or bow position β. Default **the stroke with the overshoot**; audition
   3, 5 and 8 set the overshoot and the swell floor, and the onset table
   (test 4) says whether a C2 stab can speak in under 100 ms at all — or
   whether the rack's SPIKE is the stab's attack.
6. **GRIP's two ends.** The knob travels the top single-slip island
   (default). Whether GRIP 0 may reach the two-slip whistle (a real
   "surface sound", but across a three-slip cell — a step SCRAMBLE could
   land on) and whether GRIP 1 may reach the multi-slip raucous region:
   default **no** to both; audition 6 asks with the clips labelled.
7. **`RHO_MAX`.** 0.98, STK's clamp, every number in this document
   measured at it (default); or 1.0, Faust's, after the 60-cell grid and
   the 63-cell row are re-run on the built `Bow` and every cell stays
   finite with the single-slip count intact.
8. **Velocity.** `soften` in R1 with BOW's and GRIP's sweeps printed per
   voice and the override registered for a voice that passes (default);
   or register BOW now and let the test fail. Never on a pad that carries
   a landing chain (`canUseAtVelocity` needs `fx == null`).
9. **Vibrato.** Baked at ±10 cents, 6.1 Hz, scaled by HOLD, off in the
   LOOP (default); or a knob in the sixth slot.
10. **The sixth slot.** BUZZ (default first candidate: a post-stage on
    TERRA's shape, thirty lines), bow position (the bow's own axis, but a
    β × GRIP map first and a floor at ERHU's top), WASH (SARANGI's round);
    decided at audition 13.
11. **The tarab, and SARANGI.** Not an R1 voice; R2 as ERHU plus WASH on
    SANTUR's bank (default), or `Pluck.sympathetic` lifted into `Strings`
    if the gate hears "resonators, not strings" at audition 11.
12. **ERHU's body and knob.** The membrane shape at the documents' anchor,
    labelled shape, with no-body and one-row heard beside it unlabelled
    (default); 24 semitones to D6 with the speaks test as the entry rule
    (default), the measured 19 to A5 as the fallback.
13. **The ARCO landing.** Dry (default, and so built: there is no
    `Arco.landingChain`); audition 10's A/B, not built in R1b, could add
    one with SPRING in it and its numbers pinned. The preview gap —
    a preset that lands with a chain previews dry on the SYNTH panel
    today, as SIREN's ECHO does — stays until an `:app` session closes it.
14. **Where the bow lives.** `Strings.Bow` in the shared toolkit (default:
    a second bowed string, the bourdon and the erhu's second string are
    its future callers) or `Arco.Bow` inside the engine.
15. **Stereo.** Mono (default), U4's per-patch opt-in after the rack
    audit — which ENSEMBLE's WIDTH makes per-recipe; the addendum's
    stereo path was broken at `bandLimit` anyway.
16. **The roadmap row.** S18 or later, at implementation (default), noting
    that BORE's reserved S17 is already SANTUR's.
17. **Whether to build at all.** The stopgaps: VELVET or RESIN BRASS with
    ENSEMBLE — the string machine, in full, for about 240 lines and no
    physics; VOX for a bowed-ish sustain (it holds, and has BREATH, but
    sings through a glottal pulse and vowels). What neither can do: the
    stick-slip attack (every stopgap starts with an envelope), brightness
    that follows bow pressure inside the loop, a fretless line with a
    body under a finger, a pedal that is a string. Default **build ARCO
    after ENSEMBLE**, because Phase 0 has already shown the corrected bow
    works in Kotlin on the house's toolkit — bounded everywhere, in tune,
    a textbook sawtooth — and because those four sounds are the ones this
    user asks for that no engine makes.

## Sharpening the ask

The owner asked for this: which sentences would have changed this
brainstorm most, and one habit for any spec brought to the house. Three
ways one sentence would have turned a guess into a fact (product §8),
and a fourth the reviews earned:

- **Name the surface first.** "A cello stab on a pad", "a bowed note I can
  hold under a finger on the SURFACE" and "a bowed pedal under a beat"
  start from three different render shapes (a one-shot, a LOOP, a held
  zone), and the two documents never say which you want most.
- **Say whether the string-machine half is a must or a maybe**, and
  whether it has to live inside the bowed engine. The whole
  stereo-and-coupling question, and roughly a third of the effort, hangs
  on that one sentence.
- **Say which instrument you actually want to hear first** — a cello
  pedal, an erhu line, a hurdy-gurdy's buzz. Five voices is five
  auditions, and the roster this document picks (CELLO and ERHU) is a
  guess at your ear that one line would have made a fact.

- **Say what you have run and what you have not.** Both documents
  present Kotlin and numbers as if measured; none had been compiled
  (three edits to build, a `Dsp.saw` that does not exist) or rendered
  (no note at any setting), and every hertz, Q and millisecond was
  recalled (api F18; physics §7; empirical "Prose and code disagree",
  twelve items). One line — "this is generated, unrun, and the numbers
  are placeholders" — would have sent the reviewers straight to a render
  on day one instead of a desk review, and lets the house label every
  number *shape* from the start rather than discovering it.

## Appendices

The measured tables live in the record,
[`../plans/2026-09-29-arco-phase-0-spike.md`](../plans/2026-09-29-arco-phase-0-spike.md),
so they can be re-run rather than trusted (the DSP desk review's
pure-Python replica, dsp §13, is not in the record: its three numbers
quoted here — the −4 to −6 cents on STK's −4 budget, the bridge node's
AC 0.0005, the one period-doubled cell in 25 — stand on the report
alone, and the spike's Kotlin re-measures the first two): **Appendix A**
there is the spike's report — the two models as pseudo-code with every
constant, the iteration log, and tables (0)–(10): the budget read-back,
boundedness, pitch, the Helmholtz signature, the Schelleng rows, onset,
bow position, release, cost, the single-ring comparison, the robustness
corners; **Appendices B–C** are its Kotlin as run; **Appendix D** is the
probe's report on the documents' engine — the compile changes, the
per-voice tables, the SOLINA stage alone, the friction map's fixed-point
scan, the tarab against TUNE, cost, "what the spec promised versus what
it renders"; **Appendices E–G** are its transcription and test as run.
The numbers quoted in "The physics, measured" are copied from those
tables.
