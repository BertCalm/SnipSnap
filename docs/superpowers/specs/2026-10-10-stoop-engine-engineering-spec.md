# Stoop — engine engineering specification

Version 0.1 • 10 October 2026 • Working name; release naming checks pending

## 1. Product intent

Stoop is a conventional, preset-first groove instrument for SnipSnap. It supplies punchy basses, electric-key-like tones, clipped organ stabs, colorful leads, and compact chord sounds. Every default patch should be immediately playable and easy to place in a beat.

Three features distinguish it: a continuous alternate-articulation control; a quieter answering voice with its own articulation; and calibrated recording character. A rounded bass can become a sharper slap, answer with a muted pluck, and acquire grain without losing its root or low-frequency weight.

The user's Planet Phatt reference is interpreted as an aesthetic direction for a useful groove sound module. This spec does not assert that its architecture reproduces that product, use its ROM assets, or document its historical specifications. Stoop uses original synthesis and generated presets.

All numeric settings are prototype hypotheses. No repository implementation was inspected. The supplied edited Gyre spec establishes the integration contract, not Stoop's sound-generation model.

## 2. Scope and SnipSnap contract

Use deterministic offline mono rendering, 44.1 kHz output through the standard 4× internal path, five timbral macros plus HOLD, velocity-responsive excitation, patch serialization, presets, auditions, shared loudness processing, and MPC-oriented export.

One render produces one fresh pitched patch at the requested root. Live polyphony, cross-note legato, and persistent delay state are not implied by offline samples. No external sample assets are required.

V1 prioritizes bass and keys. Implement a shared architecture with six voice families; complete bass, tine, clav, and organ before widening the lead/chord roster. Leads and chords must meet the same acceptance criteria before shipping, rather than serving as placeholder presets.

Conventional oscillators, filters, envelopes, and intentional coloration are appropriate here. Stoop need not disguise them as an impossible acoustic mechanism. Quality, articulation, and preset coverage matter more than architectural novelty.

## 3. Architecture and budget

| Stage | Starting budget | Purpose |
|---|---:|---|
| Main tonal source | Up to 3 oscillators, 1 optional sub | Body, bite, and root support |
| Key/percussion source option | 2-operator FM or small modal/pluck bank | Tines, clav-like attacks, bells |
| Articulation controller | One coherent parameter mapping | Rounded-to-accented gesture |
| Main filter/envelope | One bounded filter and amplitude/filter contours | Playable shape |
| Shadow source | One reduced-cost voice, up to 2 answer events | Related response with different articulation |
| Recording character | One shared bounded processing stage | Wear and sampler-like texture |
| Output | DC control, decimation, shared leveling | Safe normalized mono sample |

Choose a source topology per family. Avoid rendering every topology for every patch. Main and shadow share a family definition and pitch context, but maintain separate source/envelope state.

Order: derive patch/pitch and deterministic variation; excite main; schedule and synthesize shadow; sum with calibrated headroom; apply recording character; band-limit/decimate as appropriate; perform shared loudness leveling. Validate exact DSP order against existing reusable components.

## 4. Voice families

Proposed enum: `StoopVoice { BASS, TINE, CLAV, ORGAN, LEAD, CHORD }`.

| Family | Core proposal | Alternate articulation | Typical shadow |
|---|---|---|---|
| BASS | Band-limited pulse/saw/triangle with sub and low-pass shaping | Rounded body → short brighter slap/bite | Muted root or quieter octave pluck |
| TINE | Mild FM or tuned modal strike with sustained body | Soft strike → harder tine contact | Dark short bell at root or octave |
| CLAV | Short pluck/waveguide or shaped harmonic burst | Rounded pick → sharper muted snap | Soft palm-muted root answer |
| ORGAN | Additive harmonic drawbar-like bank | Smooth onset → clipped/percussive accent | Brief restrained octave stab |
| LEAD | Band-limited oscillator or restrained FM/formant source | Soft onset → sharper nasal accent | Dark short root reply |
| CHORD | Small fixed-interval stack using shared source definition | Soft block → accented stab | Short root-only answer |

These describe original synthesized characters, not literal instrument emulations. BASS needs audible harmonics as well as sub energy; TINE needs warmth rather than an exclusively glassy top; CLAV needs a useful body behind its attack; ORGAN must remain recognizable at both TOUCH ends.

CHORD uses a serialized interval recipe, initially root/fifth/octave or a small explicit triad roster. Fixed chords transpose with the requested root; no key inference or melody generation is required. Label interval recipes in preset metadata so users know what they are playing. Root-only patches remain available.

## 5. TOUCH and articulation

TOUCH continuously changes contact/attack character within the selected family. Correlate attack duration, transient spectrum, oscillator/FM balance, filter-envelope depth, and early damping. It is not a switch between unrelated instruments or a crossfade between finished samples.

At low TOUCH, produce a rounded, softer gesture. At high TOUCH, produce a defined accent without a drastic loudness jump. Keep the sustained pitch/body compatible at intermediate settings. A key strike should brighten continuously; a bass should gain bite without becoming a percussion hit.

Velocity supplies gesture energy and influences attack brightness. TOUCH supplies the chosen articulation character. A low-velocity high-TOUCH note should remain quieter and lighter than a high-velocity high-TOUCH note, even if final exports use shared leveling. Inspect raw renders to verify the distinction.

Starting attack ranges: approximately 1–20 ms for tight patches, extending to 30–80 ms for softened leads/chords. These ranges are calibration proposals, not universal constants. Avoid a zero-time discontinuity or excessive click at hard settings.

## 6. COLOR

COLOR moves from dark/full toward bright/nasal using family-specific mappings. A conventional filter is permitted, but it should coordinate source balance, FM index or harmonic emphasis, and resonance where useful.

For bass, preserve root/sub support while increasing upper bite. For keys, change tine/contact balance and high-mode loss. For organ, shift harmonic registration. For leads, coordinate filter/formant or pulse character. For chords, maintain intelligible voicing without harsh accumulation.

COLOR must not select chord intervals, alter shadow delay, or systematically transpose the principal pitch. Bound resonance and brightness for safe high-note rendering.

## 7. SHADOW: answering voice

The shadow is a newly articulated, quieter related voice, not a delayed copy of the finished main signal. It can use a different envelope, oscillator projection, or reduced brightness within a related source family. It inherits the requested pitch context.

Store `shadowInterval`, `shadowDelayMs`, and `shadowArticulation` in the patch. Default answers use root or octave. A fifth is an explicit preset option; do not introduce arbitrary notes or imply scale awareness. Default shadow level stays clearly below the main voice.

SHADOW controls answer energy and a restrained articulation-development curve. Keep timing/interval fixed during ordinary macro sweeps so the knob's role is predictable. Suggested answer delays are 60–180 ms, with a broader validated patch range around 25–250 ms. Permit up to two scheduled replies only when a preset deliberately calls for them.

A shadow begins at its actual scheduled time and has its own finite envelope. No recursive reply-to-reply feedback. The default response should add a small musical gesture rather than turn every patch into an arpeggiator.

At SHADOW=0, omit shadow synthesis and answer events. At high values, the reply can be obvious but must preserve main-voice dominance in normal presets. Loudness compensation must not obscure the control by reducing the main signal excessively.

## 8. WEAR: recording character

WEAR coordinates calibrated bandwidth reduction, transient rounding, mild saturation, subtle pitch wear, and controlled quantization/held-sample texture. Low values are direct and clean. Medium values add rounded grain. High values are deliberately coarse but playable.

Use a source-appropriate profile: bass retains low weight; tines retain pitch and strike definition; clav retains rhythmic attack; organ avoids constant hiss; chords avoid excessive intermodulation. WEAR must not automatically add audible background noise or long reverb.

Separate desired grit from uncontrolled aliasing. Saturation runs on the oversampled path. If reduced-rate/held-sample character is included, use a documented band-limited or controlled-resampling implementation and measure folded spectral energy. Do not assume 4× oversampling alone fixes downsampling artifacts.

Keep pitch drift small in ordinary patches, with a proposed starting ceiling of a few cents. It must be seeded and repeatable. At WEAR=0 bypass character stages cleanly. Do not claim that a particular bit depth or sample rate recreates vintage hardware without measurement.

## 9. LENGTH and finite gesture

LENGTH controls envelope duration and damping: tight stab through sustained phrase. It changes decay, sustain-window duration, and release, with family-specific curves. It is not a raw output-buffer length knob.

Start with short useful gestures around 100–300 ms and longer finite gestures around 1–4 seconds before tails, subject to host limits. Tines/plucks may retain a low-level pitched tail; organ/lead may sustain at a defined finite level before release.

Shadow timing remains independent, but its duration follows a restrained fraction of main LENGTH. Very short main notes may still receive a reply; include that event and its decay before terminating the render. Calculate the required render length from all scheduled sources, then apply the host maximum with documented envelope compression if necessary.

HOLD takes priority over the finite sustain window. LENGTH still controls initial settling, held envelope character, and release metadata where supported. Do not make LENGTH merely dead in HOLD, or promise a release tail that the export format cannot represent.

## 10. Macro set

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| TOUCH | Rounded articulation → sharp accent | .40 | .40 |
| COLOR | Dark/full → bright/nasal | .45 | .50 |
| SHADOW | Direct voice → stronger answering gesture | .20 | .00 |
| WEAR | Clean/direct → rounded grain and controlled crunch | .25 | .00 |
| LENGTH | Tight stab → longer sustained phrase | .40 | .45 |
| HOLD | Finite gesture → seamless held region | 0 | 0 |

Defaults and neutral values require listening and confirmation of MacroSpec semantics. A default must sound finished without raising SHADOW or WEAR. Avoid forcing maximum brightness/grit to reveal core identity.

Required interactions: TOUCH × COLOR, TOUCH × WEAR, SHADOW × LENGTH, and COLOR × WEAR. All timbral controls must remain useful across the normal voice roster.

## 11. Initial voice defaults

| Voice | TOUCH | COLOR | SHADOW | WEAR | LENGTH |
|---|---:|---:|---:|---:|---:|
| BASS | .35 | .40 | .15 | .25 | .35 |
| TINE | .40 | .50 | .20 | .30 | .60 |
| CLAV | .60 | .55 | .15 | .25 | .25 |
| ORGAN | .35 | .45 | .15 | .20 | .50 |
| LEAD | .45 | .60 | .20 | .20 | .55 |
| CHORD | .40 | .40 | .20 | .30 | .40 |

Voice defaults are starting hypotheses. Each family also needs several materially different source configurations; six enums alone are not a preset library.

## 12. Preset-first release plan

Target 48 curated presets: 16 bass, 10 tine/key, 8 clav/pluck, 6 organ, 4 lead, and 4 chord. This is a coverage goal after sonic approval, not permission to pad the library with small macro changes.

| Category | Required coverage | Initial names |
|---|---|---|
| Bass | Rounded/sub-supported, rubbery, muted string-like, square, nasal, accented | Round Corner, Rubber Sole, Muted Wire, Square Pocket |
| Tine/key | Warm strike, soft bell, darker sustained key, harder attack | Dust Tines, Bent Bell, Amber Key |
| Clav/pluck | Dry muted, bright pick, rounded rhythmic, answering pluck | Soft Clav, Short Wire, Back Step |
| Organ | Smooth, clipped stab, hollow registration, bright harmonic | Amber Organ, Small Pipes, Corner Stab |
| Lead | Nasal, whistle-like, thin brass-like, rounded sustained | Nose Talk, Pocket Whistle, Thin Brass |
| Chord | Root/fifth/octave, explicit major/minor stabs, soft string-like block | Velvet Stack, Small Cinema, Late Strings |

Attach category, interval recipe where relevant, intended register, and articulation tags using existing preset metadata conventions. Do not encode implementation details in the normal patch-selection flow.

Curate matched dry auditions and simple musical examples. Default FX must be restrained; empty-FX versions must remain useful. Include clean patches, worn patches, no-shadow patches, and a few obvious shadow demonstrations.

## 13. Pitch and mono export

Requested root controls all pitched sources, with explicit harmonic/sub/shadow/chord ratios. Preserve perceived root through macro changes. Sub/octave components can confuse pitch detectors; use family-appropriate measurement plus listening instead of treating one detector result as proof.

Use band-limited oscillators or appropriate wavetable selection. FM indices and high partials must adapt to pitch/bandwidth. Deterministic phase choices should avoid accidental main/shadow cancellation; do not randomize phase unseeded to conceal it.

Single-note exports transpose as samples in the target workflow. Built-in shadow delays become part of that sample and will change in seconds when playback rate changes. Document this export consequence; they cannot remain tempo-locked after arbitrary external transposition. Multisample/keygroup strategy belongs to the existing exporter, not a new unverified promise.

## 14. HOLD strategy

HOLD renders a settled sustain region. Oscillator, modulation, wear/resampling clock, noise, filters, and shadow state must be loop-compatible. Avoid resetting the attack at every wrap unless a rhythmic preset explicitly requires it.

Default HOLD shadow becomes a quieter sustained companion with its alternate articulation reflected in spectrum/envelope settling. Its delayed onset belongs to an attack segment only if the host supports attack-plus-loop. A loop-only export contains the settled result, not the initial reply gesture.

Optional rhythmic HOLD replies require an explicit repeating patch clock and event pattern. Quantize that pattern to the loop period and use repeating seeded variation. Keep this out of ordinary bass/key defaults.

Converge state during bounded preroll. Select compatible oscillator/modulation cycles or apply the repository's tested pitched-loop method; multiple detuned voices and chord intervals may need careful wrap handling. Do not force every oscillator into coarse loop-frequency bins if that audibly detunes the patch.

After selecting a stable region, apply minimal project-standard crossfade where required. Verify no double attacks, shadow gaps, wear-clock clicks, or level jumps. Representative HOLD cases must satisfy `Keys.seamError < 1e-3` and repeated-loop listening.

## 15. Determinism, safety, and integration

Identical voice, pitch, macros, velocity, source configuration, shadow configuration, patch, and seed context must reproduce identical audio/event timing. Use `Dsp.seedFor` or current infrastructure for noise, tiny variation, and pitch wear. Exclude wall-clock timing and live external assets.

Prevent NaN/infinity, runaway filter resonance, DC, denormals, aliasing, and uncontrolled preconditioning peaks. Provide main/shadow mix headroom and bounded nonlinear processing. Shared loudness targeting follows synthesis; compare raw and leveled auditions to reveal energy and articulation behavior.

Implement `Stoop.kt`, `StoopVoice`, `StoopPatch`, `StoopPresets`, `Patches` registration, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap entries according to actual repository organization. Patch fields include voice/macros, source configuration, shadow timing/interval/articulation, chord intervals, and loop behavior as required. Support JSON round-trip using `BorePatch`/sibling conventions.

Reuse `MELODIC_LOUDNESS_TARGET`, `PadRecipe`, and `FxChain` conventions after verifying interfaces. Standard pitched audition cases must avoid KICK/SNARE/CLAP/HAT/TOM classifications. Short low bass and clav accents are specific risks; preserve tonal tails and avoid excessive attack dominance without removing articulation.

## 16. Build stages and validation

1. Build shared synthesis/envelope infrastructure and BASS/TINE. Probe pitch, level, decay, classifier behavior, and macro activity before setting numeric bounds. Approve dry identity.
2. Add CLAV/ORGAN and alternate articulations. Establish useful short/long envelopes and velocity behavior.
3. Add shadow and recording character, then LEAD/CHORD. Approve restraint, root preservation, and useful interaction before expanding presets.
4. Complete HOLD, serialization, kit integration, curated presets, auditions, and required checks. The supplied baseline is `./gradlew --no-daemon test`; verify current repository requirements.

Audition every family at low/middle/high supported notes and velocities; macro sweeps 0/.25/.5/.75/1; required interaction grids; all-high settings; shadow intervals/delays; explicit chord recipes; and difficult HOLD cases. Measure pitch confidence, peak/RMS, DC, decay, spectral balance, folded energy, render cost, determinism, macro distance, routing, and seam error.

Compare main-only, shadow-only, and combined signals; clean versus worn at matched level; alternate articulation endpoints and midpoint; and raw versus normalized velocity responses. Test reply scheduling at host duration boundaries.

Acceptance requires:

- Default bass/key patches work musically with empty FX and do not require extreme macros.
- TOUCH changes articulation continuously while preserving family and pitch.
- Shadow produces a distinct related answer with correct timing and no recursive events; SHADOW=0 removes it.
- WEAR adds usable character without destroying ordinary bass weight, key definition, or root recognition.
- LENGTH changes gesture shape and completes scheduled replies within render limits.
- Presets cover genuinely different musical roles; chord intervals are explicit and correctly transposed.
- All normal controls are audible; extremes are finite and bounded; renders are deterministic; patches round-trip; routing guards pass.
- HOLD meets the seam metric and listening gate, with its settled-shadow behavior documented.

The owner's listening verdict is the sonic gate. A module full of technically distinct but interchangeable presets is not a successful release.

## 17. Open decisions and deferred scope

Confirm current APIs, note ranges, velocity fields, duration limits, HOLD export format, source-building reuse, preset metadata, and release naming. Calibrate articulation mappings, shadow levels, wear profiles, and supported chord recipes from probes and listening.

Defer external ROM/sample imports, historical hardware emulation, live polyphonic performance, automatic scale-aware replies, arbitrary arpeggiation, stereo effects, and a standalone plugin UI. Stoop succeeds when familiar patches sound finished immediately and its twists add small, useful musical gestures.
