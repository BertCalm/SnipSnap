# SnipSnap conventions, verified against the repo

Everything here was read from `github.com/BertCalm/SnipSnap` at commit `565551e` on 2026-10-21. The tree moves
fast. Treat each line as "true on that day, re-grep before you lean on it." Where a line says
VERIFY, a grep is cheap and a wrong API name in a spec is expensive: the three engine specs
written before this skill (Rung, Skirl, Terrane) all reference `siblingPatches`, which does
not exist anywhere in the repo.

## 1. What SnipSnap is, in one paragraph

An Android drum sampler and synth that renders one-shots offline and exports kits an Akai MPC
can load. Nine pure-JVM Kotlin modules (`:json :xpm :audio :kit :mpc3 :synth :loop :cli
:shell`) plus `:app` (Android, only in the build when an SDK is present). Synthesis lives in
`synth/src/main/kotlin/com/snipsnap/synth/`. There is no real-time engine: **synthesis is a
pure function from parameters to a `Snip`** (`docs/SYNTH_ROADMAP.md`, "synthesis here is
offline rendering"). C++ under `app/src/main/cpp` only plays rendered WAVs plus the Aquarium
FX processor; no engine lives there and none is expected to.

## 2. The engine contract (every pitched engine)

| Item | Value | Where |
|---|---|---|
| Output | mono, 44.1 kHz, PCM `Snip` | `Dsp.RATE = WavWriter.MPC_SAMPLE_RATE` |
| Internal rate | `RATE * Dsp.OVERSAMPLE` where `OVERSAMPLE = 4` | `Dsp.kt` |
| Output stage (house order) | `Tide.bandLimit(raw, RATE*OVERSAMPLE)` (8th-order Butterworth) → `Dsp.decimate` (two cascaded 2:1 windowed-sinc steps) → subtract mean, then `OnePole` high-pass at 20 Hz → `Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET)` → `Dsp.fadeTail` (4 ms). Each engine writes its own `finish` with this sequence: `Gyre.finish` is `internal` and `Mercury.condition` is `private`, so copy the sequence, do not call them | `Gyre.kt:840`, `Mercury.kt:709` |
| Loudness target | `Dsp.MELODIC_LOUDNESS_TARGET = 0.1834f` (its own doc calls it a stale measurement) | `Dsp.kt` |
| Seeds | `Dsp.seedFor(vararg parts)`: a stable hash. Never a stored seed unless the patch carries it explicitly (Nacre does) | `Dsp.kt` |
| Noise | `Dsp.Noise(seed)`: the only generator, an LCG, pinned by goldens. Do not swap it silently | `docs/DSP_REVIEW.md` |
| Macros | 3 to 6 named controls in plain words, each 0..1, plus HOLD. Every engine has TUNE unless the design argues otherwise. No knob may share the engine's name | Gyre spec G6; `docs/SYNTH_ROADMAP.md` playability rules |
| HOLD | an ordinary macro; the engine decides what the top step does (a seamless LOOP, a reserved step, or a refusal) | `Gyre.kt`, `Nacre.kt` |
| Drum classes | pitched engines return PERC, TONAL or LOOP from `drumClassFor`, never KICK/SNARE/CLAP/HAT/TOM. The guard is a test that classifies real renders, not a filter | `MurkTest`, `GyreTest` |
| LOOP threshold | the classifier files anything over 1.5 s as LOOP (`Classifier.LOOP_MIN_SECONDS`, a `private const val` at `Classifier.kt:81`, so engines carry their own 1.5 s constant); `drumClassFor` must agree with the render length to the frame | `Classifier.kt`, `BoreTest` |
| Determinism | byte-identical renders across runs, every voice; a `DeterminismTest` canary per engine | `DeterminismTest.kt` |
| Render time | "one-shots render in tens of ms" is a playability rule (knob turn → re-render → retrigger). A design that needs seconds of simulation must cache or precompute, and say so | `docs/SYNTH_ROADMAP.md:662` ("The instant loop") |
| Tempo | `render` receives no tempo; BPM is a kit-side concept (`Gate.kt`, `Wobble.kt`). A tempo-locked design (slicer, phrase clock) assumes a documented BPM or exposes a RATE macro, and says which | |
| Mechanics-first reference | PITCHWHEEL is the shipped sibling for any design that simulates mechanics before audio: `MODEL_VERSION` on the patch, an `Event`/`Trace`/`Diagnostics` ledger, midi and velocity on the patch (`Pitchwheel.kt:21,71-77`, `PitchwheelPatch.kt:20`). Read it before Rung/Skirl/Terrane-style work | |
| Trademarks | no maker names or model numbers in engine, voice or preset names; `PresetTestSupport.trademarkBlocklist` enforces it. Research sections of a spec may name real gear | `docs/SYNTH_ROADMAP.md` "sound yes, names never" |
| Delight | lives in the UI chrome, never in the signal path | `docs/PERSONALITY.md` law 2 |

The Patch file shape (`GyrePatch.kt`, `BorePatch.kt`, `MurkPatch.kt`, `MercuryPatch.kt` are
token for token the same):

```kotlin
data class XxxPatch(override val name: String, val voice: XxxVoice,
                    override val macros: Map<String, Float>) : Patch {
    init { Patches.validateMacros(this, Xxx.macrosFor(voice)) }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Xxx.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    companion object {
        const val ENGINE = "XXX"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch = Patches.decode(value, ENGINE,
            { n -> XxxVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros -> XxxPatch(name, voice, macros) }
        fun fromJsonText(text: String): XxxPatch = fromJsonValue(Json.parse(text)) as XxxPatch
    }
}
```

"BorePatch-style sibling patch" in older specs means exactly this: copy the file shape.

## 3. What a new engine ships (the registration checklist)

The canonical table is the BORE spec's "Data flow and compatibility"
(`docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md`, 26 rows). Condensed:

Enforced by the compiler or the registry tests:
1. `synth/.../Xxx.kt`: `enum class XxxVoice`, `object Xxx` with `macrosFor(voice): List<MacroSpec>`, `defaults`, `settled` (clamp 0..1, drop unknown keys), `rootMidi`/`midiFor`/`frequencyFor`, `drumClassFor`, `scramble`, `render`; `isLoop`/`renderLoop` if HOLD loops.
2. `XxxPatch.kt` (shape above).
3. A `Patches.fromJsonValue` arm (`Patches.kt`).
4. Two exhaustive `when` arms in `Velocity.kt`: `macroSpecsFor` and `touchedRender` (no `else`, so the compiler finds a missing arm). `brightnessOverride` only after a sweep proves the macro is monotonic.
5. `XxxPresets.kt` plus a `Presets.forVoice` arm and an `all()` term, or an explicit exemption in the test roster `PatchTypes.ROSTERLESS` / `PROTOTYPES`. `PresetsTest` reflects over the sealed `Patch` interface, so a new patch class joins the roster the moment it compiles.
6. Hand-built entries in `PatchesTest.onePatchPerEngine` and `PadRecipeTest.handBuiltPatches` if rosterless.

Expected by convention:
7. `DeterminismTest` canary (render twice, `assertContentEquals`).
8. `SynthKits.xxx()`: 16 pads, A01 to A08 a minor-pentatonic walk of the first voice over TUNE, A09 to A16 named presets, loops last, every pad's class from `drumClassFor`, dry (`fx = null`) unless a landing chain is part of the identity (Bore lands with TAPE).
9. `XxxKitGenerator.kt` + a `generateXxxKit` Gradle task → `testkit/SnipSnap Xxx Kit/`.
10. `XxxTest.kt` with the probe set (section 5) and `XxxPresetsTest.kt` with the six preset contracts plus blocklist terms for any maker the brief named.
11. `XxxAuditionGenerator.kt` + `generateXxxAudition` task + `synth/src/test/resources/audition/xxx-audition.html`. Output goes to `testkit/xxx-audition/` (gitignored): section folders of levelled WAVs, `manifest.json`, `index.html`. Sections: voices at three notes, each macro swept, macro pairs at their corners, edges, the kit as it lands, the LOOP played eight times end to end. Newer generators (Murk, Nacre) also write `recipes/*.json`, `diagnostics/*.json`, `metrics.csv`, raw and matched levels.
12. HOLD: a real loop with `Keys.seamError < Keys.MAX_SEAM_ERROR` (1e-3) measured against a real preceding cycle, or the top step reserved/refused with a test that says so.
13. Docs: an "As built" section appended to the spec in the same commit as the code, a `docs/SYNTH_ROADMAP.md` row, `docs/XXX.md`, README engine count.
14. The app picker (`app/.../SynthScreen.kt`: the `Engine` enum entry plus seven arms) is deferred to a session with an Android SDK. "A picker button guessed blind is worse than one left for a session that can verify it."

Test gate: `./gradlew --no-daemon test` (no `-x :app:test` in a session without an SDK). Judge
green by the exit code, never by grepping output.

## 4. Shared DSP you reuse before writing new code

| Need | Reuse | Notes |
|---|---|---|
| Modal resonators | `Modes.ring(excitation, hz, modes, rate)`, `Modes.Mode(ratio, gain, t60)`, `Modes.fixed`, `Modes.stiffString`, `Modes.tableFor(Material)` | gain carries no `(1-r)` factor, so peak amplitude grows at low pitch and oversampled rates: peak-normalize modal output before mixing |
| Coupled modal bank | `Modes.Bank(size, rate)`: `tune`, `connect(i, j, kappa)`, `step`, `drive`, `energy`, `coupledHz` | stability bound `MAX_NODE_KAPPA = 1.0`; a change that breaks it is refused |
| Strings and bores | `Strings.Loop` (`reflected`/`inject`, `retune`, `jawari` fold-back, dispersion), `Strings.Bow`, `Strings.pluck`, `Strings.bodyRing` | the waveguide toolkit lifted from PLUCK; SILK, BORE, ARCO, GYRE, NACRE build on it |
| Friction | `Modes.Friction(a).force(...)` | implicit Newton/bisection solve |
| Filters | `Dsp.Svf`, `Dsp.TptSvf`, `Dsp.Ladder`, `Dsp.Biquad` (shelves, bandpass, allpass, peaking), `Dsp.OnePole` | Biquad is Float direct-form I on purpose (goldens); do not "fix" it |
| Macro maps | `Dsp.lin`, `Dsp.expMap`, `Dsp.around` (pinned centre) | |
| Band limit before decimation | `Tide.bandLimit(buf, rate)` | |
| Level | `Dsp.levelTo`, `Dsp.limitPeak`, `Dsp.normalize*`, `Loudness.of` | |
| Loop cut | `Siren.bestCut`, `Keys.seamError`, `Keys.requireSeam` | `LOOP_PAD_FRAMES = 2048` and `wrapCrossfade` are private per engine (Mercury, Arco, Tremor, Corolla), not in Keys |
| Pitch detection | `Pitch.detect(...).confidence` (autocorrelation) | tests use confidence ≥ 0.3, Skitter ≥ 0.75 |
| True peak | `TruePeak` (`:audio`) | exists; deliberately not wired into export |
| FX rack | `FxChain`: 23 sections and the reverse flag in a fixed order (SPEED, SWELL, reverse, SMEAR, GHOST, SPIKE, EQ, CONTOUR, WAH, COMPRESSOR, SQUASH, FORGE, VALVE, CRUNCH, RING, DUB, VINYL, TAPE, ENSEMBLE, PHASE, ECHO, SPRING, ROOM, MOTION; re-read `FxChain.SECTIONS` at `ad2439e` plus FORGE and ROOM), 1.0 s tail cap, no routing by design. VALVE and FORGE are the oversampled sections (`Valve.upsample` → stage → `Tide.bandLimit` → `Dsp.decimate`) | pedals are recipes over sections or new sections in the slot a real board would use |
| Aquarium | six-stage C++ processor (Fathom, Drift, Tide, Reef, Surface, Biolume) from Omnibus commit a9bdc78; foundation only, not routed yet | `app/src/main/cpp/aquarium` |

## 5. The house probe set (what a test file covers)

determinism (one-shot and LOOP) · finite output, bounded, never grows · peak ceiling (raw peak
under a stated ceiling, output in 0.01..1) · DC bound (|mean| under 0.005 to 0.01) · loudness
(≥ 0.9 × target or peak ≥ 0.95) · root pitch (within 5 to 50 cents depending on engine) · root
confidence (newer engines, `Pitch.detect` confidence ≥ 0.3) · aliasing (no partial above output
Nyquist; decimation state error) · "every macro changes the sound" (and an unknown key does not)
· macro pairs interact · render length equals the filed class to the frame · JSON round trip ·
HOLD seam against a real preceding cycle · scramble stays in bounds · velocity · a non-finite
macro falls back to its default · classifier keeps every render out of the drum set.

Energy tests must not be tautological: a "never grows" check that takes its peak over the whole
render can never fail. Take the peak over an independent attack window, then flip a sign to
prove the test fails when energy does grow (the SILK S18 lesson).

## 6. The house writing discipline (specs, docs, commit bodies)

- **Verified vs inferred vs proposed vs heard.** The repo is strict about which is which. A
  spec marks every constant "shape" until a measurement or a listen backs it (Gyre G10). An "As
  built" section records what changed against the design, measured, and what was "heard" versus
  "not heard" (this sandbox cannot play audio).
- **Measure, never guess.** Numbers are quoted with the test that produced them. A guard is
  proven by reverting it alone and watching a named test fail.
- **One quantity in two places** is the recurring defect shape. Call it out when you see it.
- **Disclosed, not fixed** is a legitimate state. Write the limit down with its number.
- Commit titles are plain declarative prose, no Conventional Commits prefixes, long bodies that
  explain why and what was abandoned. No model names anywhere.
- The default branch is `claude/mobile-mpc-drum-sampler-t58x74`; PRs land as merge commits;
  feature branches are `claude/<something>`. Docs-only PRs get zero CI checks by design.

## 7. Known drift in the three pre-skill specs (Rung, Skirl, Terrane)

Minimum grep set for any spec review (run these before writing a finding, and list them in the
ledger as the "measured" line when nothing can be rendered):
`grep -rn siblingPatches synth/` (expect nothing) · `grep -n MAX_SEAM_ERROR synth/src/main/kotlin/com/snipsnap/synth/Keys.kt` · `grep -n "ROSTERLESS\|PROTOTYPES" synth/src/test/kotlin/com/snipsnap/synth/PatchTypes.kt` (is the reference engine complete?) · `grep -n LOOP_MIN_SECONDS audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt` · `grep -n "fun macroSpecsFor\|fun touchedRender" synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt` · `grep -n "MODEL_VERSION" synth/src/main/kotlin/com/snipsnap/synth/Pitchwheel.kt` · `git log -1 --format=%h`.

When you review or implement them, start here:
- `siblingPatches` does not exist. Read "copy `BorePatch.kt`'s shape."
- `Keys.seamError < 1e-3` exists (`Keys.MAX_SEAM_ERROR`). The "project's pitch-preserving loop
  method" does not exist as one method: Mercury's `planLoop`/`renderLoopMeasured`, Bore's cut
  from a held one-shot, and Resin's `Keys.planLoop` are three different strategies. Pick one and
  name it.
- "Standard 4× internal DSP with proper decimation" is real (`Tide.bandLimit` then
  `Dsp.decimate`). "Shared leveling" is `Dsp.levelTo` to `MELODIC_LOUDNESS_TARGET`.
- "Classifier mapping" is `drumClassFor` plus a test over real renders.
- The specs' five-macros-plus-HOLD shape fits the house (3 to 6 macros). Six voices and 36
  presets is heavier than any shipped engine's R1 (Bore shipped 8 per voice for two voices;
  Mercury 6 per voice). Propose presets per voice only after the voice has been heard; "presets
  authored blind are disposable" (SILK).
- None of the three states a render-time budget. Rung's 42 rigid bodies with continuous
  collision, Skirl's quaternion rolling solver, and Terrane's per-frame Voronoi all need one, or
  a precompute-and-cache story keyed by the macro values that affect mechanics.
- "Gyre specification supplies project conventions": Gyre itself is rosterless (no presets, no
  kit, no app entry). Mercury and Bore are the complete references for kit, presets and HOLD.
