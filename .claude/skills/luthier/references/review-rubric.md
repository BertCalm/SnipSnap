# Review rubric: engines, specs, gap scans and benchmarks

Josh's priority order: **sound character** and **architecture/modularity** first, then
numerical health, playability, and integration. Everything matters; these two decide whether
the thing is worth finishing. A review that lists twelve nits and misses "the gesture has no
ending" has failed.

## 1. What to read, in order

For an engine in the repo: the KDoc at the top of `Xxx.kt` (the claims), `macrosFor` (the
contract), `render` (the gesture), the output stage (hygiene), then `XxxTest.kt` (what is
proven), then `docs/XXX.md` and the spec's "As built" section (what was heard). For a spec:
the summary's gesture line, the macro contract table, the voices table, the numerical section,
the integration section, then everything else.

Before writing a finding, render if you can. In the repo: `./gradlew :synth:generateXxxAudition`
or a scratch test that calls `Xxx.render` and writes WAVs; then run the house probes
(peak, DC, loudness, pitch confidence, seam). Outside the repo: the Python harness in
`scripts/` reproduces the output stage and probes well enough to compare designs. A number
beats an adjective. Say which you have.

## 2. The five lenses

### A. Sound character (does it have an identity, and does the identity survive the knobs?)
- **Gesture**: can you write A → B → C → D from the render alone? Does it end (a definite
  finish, a loop, or a tail), and is the ending designed or just a fade?
- **Root**: is the requested note the perceived pitch at every macro corner? Which macro
  moves pitch as a side effect (material tables, coupling, dispersion)? Is there a
  compensation rule?
- **Register**: does it play C2 to C5 without the mechanism changing character (a reed's
  onset is a number of periods; low notes speak slowly), and is that disclosed?
- **Distinctiveness**: name the two nearest roster siblings and the one thing this does that
  they cannot. If you cannot, the finding is "this is a voice of X, not an engine."
- **Velocity**: does it change timbre, contact or event energy, or only gain?
- **Edges**: the corners of the macro cube. Which are silent, which are noise, which clip?
  The house rule is "bounded ranges: you can't detune into garbage unless you ask."
- **Over the roster**: does it fill a thin spot (engine-inventory.md) or crowd a heavy one?

### B. Architecture and modularity (is it built so it can grow and be reused?)
- **Separation**: mechanics/state → descriptors → excitation → resonators → output. Each stage
  should be a function you can test alone (the specs' "suggested units" idea). A `render`
  that is one 400-line function is the finding, not a style note.
- **Reuse**: what in `Dsp`, `Modes`, `Strings`, `Tide`, `Siren`, `Keys` should it be using
  and is not? What did it reinvent (a second decimator, a private DC blocker, its own noise)?
  What did it make private that a sibling will need (the recurring "one quantity in two
  places" defect: `LOOP_PAD_FRAMES`, `wrapCrossfade` copied between engines)?
- **Extractability**: which stage could become a shared toolkit the way PLUCK's loop became
  `Strings.kt`? Name it and the two engines that would use it.
- **Cache and cost**: what is computed per note that depends only on macros (mechanics,
  geometry, tables)? Is it keyed and reused? What does a knob turn cost in ms? "Tens of ms"
  is the playability rule.
- **Determinism**: every random draw from `Dsp.seedFor(...)` with stable parts; no hash-order
  iteration; no wall clock.
- **State machines**: explicit states with named exits (ROLLING, TERMINAL, REST, FAILURE),
  failures flagged rather than disguised as success.

### C. Numerical health
- Stability argument for every feedback path (passivity, kappa bound, eigenvalue bound,
  explicit-integrator dt condition). "It hasn't blown up in tests" is not an argument.
- Aliasing: are nonlinearities and sharp pulses band-limited before decimation? Any partial
  above output Nyquist (the SHOAL test)?
- DC: valves, rectifiers and asymmetric clips leak DC; where is it removed, and before or
  after the resonators?
- Energy: a "never grows" test that cannot fail (peak over the whole render) is a finding.
- Pitch exactness in waveguides: fractional delay, tuning budget including the loop filter's
  phase, dispersion.
- Non-finite inputs: a NaN macro falls back to its default; a non-finite sample does not
  poison the render (`Dsp.finitePeak` exists for this).
- Denormal tails: documented as left alone; do not propose flush-to-zero without a profile.

### D. Playability
- 3 to 6 macros in plain words; each one audibly changes the sound at low and middle
  settings after leveling (the house test); pairs interact as the spec promised.
- Sweet spots wide by construction; SCRAMBLE lands on sounds, not silence.
- Defaults invite play (Omnibus DOC-002).
- HOLD: does the top step do what the class says, and is the seam real (measured against a
  real preceding cycle)?
- Presets: authored after listening, six contracts, no maker names (skip the blocklist check
  when the brief names no gear; add terms when it does).

### E. Integration (the checklist in snipsnap-conventions.md §3)
Report what is present, what is missing, and what is deferred with a reason. The classifier
test over real renders and the `DeterminismTest` canary are the two that go missing most.

## 3. Findings format

Group by priority, each finding with the lens letter, what you observed (with a number or a
line reference), why it matters for the sound or the architecture, and what to try. Josh is
newer to coding: the "why" is not optional, and it should teach the DSP reason, not restate
the symptom.

```
## Fix now (it is wrong, or it hides a wrong thing)
- [C] `finish` removes DC after `levelTo`, so the level is measured with the offset in it
  (`Xxx.kt:412`). Why: Loudness.of reads a DC-shifted buffer as louder, so every render lands
  quiet, and the 20 Hz high-pass then shifts the peak. Try: mean, high-pass, then levelTo
  (the Gyre.finish order).

## Try next (it would sound or build better, and it is cheap to test)
- [A] BODY at 0 and 1 render the same partial set (spectra attached); only the gain moves.
  Why: the macro is a mix knob wearing a physical name; the playability rule wants it to
  change the object. Try: let BODY move the support's modal spacing and damping together.

## Big idea (a direction, not a patch)
- [B] The droplet scheduler is the engine's identity and is private. Try: lift it into a
  shared `Events.kt` the way PLUCK's loop became `Strings.kt`; FLOTILLA and MURK would use it.

## Disclosed, not fixed (limits worth writing down)
- ...

## What I measured / what I only read
- rendered 3 voices × 5 TUNE steps × 4 corners with the harness; peak, DC, pitch confidence
  attached. Not heard.
```

Never pad a review. If the engine is good, say what makes it good in two lines and give the one
real finding.

## 4. Gap scan format

Input: the roster (engine-inventory.md), the roadmap, and what Josh says the tool is for
(one-shots and loops an MPC loads; novice first, MPC owner kept). Output:

```
## Gaps that complement (a missing partner for what exists)
- <gap>: nearest engines X, Y; what they cannot do; the cheapest proof (a voice? a section? an engine?); why a producer would reach for it.
## Gaps that extend (a shipped mechanism taken further)
## Gaps that add a value proposition (something the product does not claim today)
## Gaps I would not fill (and why)
## Verified / read / proposed ledger
```

Rank by producer value per build round, not by cleverness. Tie each gap to a kit slot (a bass
register, a loop, a hat-class percussive, a keys instrument) because a pad is the unit of use.
The inventory's "thin by slot" section and its export-readiness notes are where the cheap,
high-value gaps hide (velocity layers that one kit uses, HOLD engines that never reach the
keygroup path); a mechanism gap is only worth more when a slot needs it.

## 5. Benchmark format (market tools → adaptations)

For each tool: the behaviours that define it (3 to 6, as mechanisms, not marketing words), what
it does better than anything in the roster, and the SnipSnap constraint that changes the
adaptation (offline, mono, 3 to 6 macros, bakes motion). Then the cross-tool synthesis:
"from A take the X, from B take the Y, drop Z from both because of constraint W" and the one
design that is better than the sum. Research sections may name real products; engine, voice
and preset names may not (`PresetTestSupport.trademarkBlocklist`). Cite sources.

## 6. Reviewing a pre-skill spec (Rung, Skirl, Terrane and their successors)

They are strong on mechanics and weak on three things. Check each:
1. **Repo truth**: API names (`siblingPatches` does not exist), reference engines (Gyre is
   rosterless; use Mercury and Bore), the loop method (three different strategies exist).
2. **Render cost**: no budget stated; propose one (precompute mechanics keyed by the macros
   that affect them; cache across pitch; cap solver work; state the ms per knob turn).
3. **Scope per round**: six voices and 36 presets at R1 is heavier than any shipped engine;
   propose R1 as two voices, presets after the listen, the rest behind an audition gate.
Then review the mechanics on their own terms (they are usually right) and look for the
"readout quietly injects energy" and "two clocks" errors.
