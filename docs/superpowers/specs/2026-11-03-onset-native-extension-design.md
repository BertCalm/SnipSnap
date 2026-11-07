# ONSET native extension: engineering specification

Version 1.0 · 2026-11-03

Status: native integration of the Study 009 listening direction. Compatibility
and build gates are added; their results belong to the implementing build's
verification record. No new native-build listening verdict is claimed here.

Initial port base: SnipSnap `83546a61731ab24fbaeb4f76ef91ebb0beaade98`.
The integration was rebased on 2026-11-06 onto
`e634076db9fdf5a0ca9a8aacbbbcb5193c7bd3d4`; the studied DSP is unchanged.
Study 009's audition renderer used primitives from
`c9807eba50e46df832365b7ba2cf7e7302439def`. APIs checked in source include
`Onset.settingsFor`, `Onset.probe`, `Onset.render`, `OnsetPatch`,
`OnsetPresets`, `PadRecipe`, `Modes.ring`, `Modes.tableFor`, `Strings.tune`,
`Strings.Loop`, `Strings.pluckExciter`, `Dsp.seedFor`, `Tide.bandLimit`,
`Dsp.decimate` and `Dsp.fadeTail`.

## 1. Summary and landing

The characteristic gesture is **electronic contact → material body →
frequency-dependent loss → a dry finish**.

Extend ONSET with TOM, PERC_PLUCK, PERC_WOOD and PERC_METAL. Keep its six
controls and its existing engine identity. The seven studied setups are
Floor Tom, Mid Tom, High Tom, Dry Pluck, Wire Pluck, Wood Knock and Metal
Ring. Three blend positions per setup become 21 native factory patches.
They join the original twelve, which remain explicit model-3 recipes.

The nearest siblings already cover the component mechanisms: THUMP/SKIN
cover synth and modal drums; PLUCK and TINES cover released strings and
FM percussion. ONSET's useful difference is editing the transition under
one root, onset, control map and saved recipe. This is a voice extension,
not a claim that the individual synthesis methods are new.

The owner described Study 009 as “sounds better,” then authorized native
integration. That selects the port direction. It does not imply that
new Kotlin output, phone behavior or MPC playback has been heard.

CLAP, native velocity articulation, a sequencer, live cross-voice coupling
and HOLD are outside this extension. These are finite percussion hits;
there is no loop promise or loop-seam test to add. The new voice count is
eight because wood and metal are separate native identities, not because
the original eight-voice XOnset roster has been completed.

## 2. Compatibility contract

- Preserve the enum ordinals of KICK, HAT_CLOSED, SNARE and HAT_OPEN.
  Append new entries after them.
- Models 1–2 retain their supported kick and closed-hat paths; model 3
  retains all four original voices. Reject new voices below model 4.
- Model 4 uses the unchanged legacy path for the original voices. The
  new renderer is reached only for the appended voice identities.
- Save the explicit model and all six Float macro values in every
  `OnsetPatch`. Missing versions or incomplete maps stay invalid.
- Preserve model version through copying, editing, preset storage and
  `PadRecipe` serialization. Names are labels, never branch selectors.
- Preserve the existing seed strings and seed version 2 inside the port,
  including the prototype's `PERC_MODAL` family seed for wood and metal.
  Native enum names must not silently reseed an auditioned sound.
- Keep the prototype's Double arithmetic and its deliberate Float
  conversion points. Float settings metadata must not become the input
  to a rounded DSP root or decay calculation.

The compatibility gate contains 77 PCM-24 references: 40 original-model
comparisons, 16 accepted production-bank hits and 21 Study 009 extension
hits. It also compares the model-3 and model-4 paths directly for existing
voices. This catches a numerically stable port that nevertheless changes
the audio the owner judged.

## 3. Signal flow and architecture

```text
voice + model + six Float controls
           |
           v
Onset settings and version validation
           |
           +-- original voice --> existing model-1/2/3 source path
           |
           +-- extension --> OnsetExtensions
                               |
                        Double root/decay laws
                               |
                +--------------+----------------+
                |                               |
         electronic source                material source
                |                               |
                +----- feedforward BLEND --------+
                               ^
                     shared bounded contact
                               |
                     fixed gain + body shelf
                               |
            band limit at 176.4 kHz --> decimate to 44.1 kHz
                               |
              mean removal --> 20 Hz high-pass --> .7 ms head
                               |
                        4 ms tail --> Snip
```

`OnsetExtensions` is an internal implementation behind the existing
`Onset.settingsFor`, `probe` and `render` entry points. Source taps and
sample-wise blend weights make contact, decay and blend laws inspectable
without reconstructing them from a finished WAV.

There is no feedback between the source branches. The string's internal
feedback is part of its resonator, not a coupling between the electronic
and material sources. No mechanical solver, voice pool or real-time
modulation matrix is introduced.

## 4. Sources and material meaning

| Native voice | Electronic source | Material source | Meaning of the model |
|---|---|---|---|
| TOM | Swept clean oscillator with short upper harmonics | Six circular-membrane modes with contact-position projection | Modal approximation; no full drum shell or air simulation |
| PERC_PLUCK | Short harmonic pulse | Triangular released-string shape with a small seeded contact component through `Strings.Loop` | Digital waveguide with frequency-dependent loss; no measured guitar or pickup |
| PERC_WOOD | Short electronic tap | Three `WOOD_XYLO` modes with rapidly damped upper modes | Authored wooden-impact analogy, not measured wood acoustics |
| PERC_METAL | Harmonic FM strike | Four modes retaining the studied wood-to-metal ratio interpolation | Authored modal color; BODY may change material color inside this voice |

The two modal voice identities select different algorithms before BODY
is applied. Moving BODY never jumps from the wood branch to the metal
branch. It does not follow that every PERC_METAL setting is purely metallic.

TOM keeps the six Bessel-zero ratios already used in the auditioned
membrane branch. SNAP drives the common pitch drop; BODY changes contact
position; EDGE changes contact radius and upper-mode participation.
Independent modal losses retain body structure after the attack.

PERC_PLUCK uses the Study 009 release geometry, rather than a white-noise
burst as its entire excitation. BODY moves the release breakpoint; EDGE
changes pick bandwidth and loop loss. `Strings.tune` compensates delay
and filter phase. The analytic electronic source is aligned to the
actual string fundamental's measured phase, not to a label or preset ID.

PERC_WOOD uses a finite-area contact pulse. Its root mode T60 is .78 of
the resolved decay; upper modes lose energy much faster. PERC_METAL
retains the previously held four-mode source. In both modal paths the
mode drive compensates the resonator's `1/sin(theta)` factor analytically
at the requested frequency and rate. There is no rendered-peak source
normalization.

The modal ratios come from the existing `Modes.tableFor` tables; contact
widths, participation weights and loss multipliers are authored shape
constants, not claims of measured acoustic parameters. This extension
preserves their studied values instead of tuning them during the port.

## 5. Blend and energy budget

For blend `b`, use `x = cos(pi*b/2)`, `m = sin(pi*b/2)` and
`h = sin(pi*b)^2`, with explicit endpoint bypasses at 0 and 1.

PERC_PLUCK and PERC_WOOD keep the Study 009 handoff:

```text
electronicScale(t) = 1 - .40*h*(1 - exp(-t/(.006 + .010*BODY)))
materialScale(t)   = 1 - .70*h*exp(-t/(.002 + .003*BODY))
```

Both scales stay between zero and one. The electronic source leads the
contact, then recedes; the material source develops after the contact.
These are feedforward envelope laws, not a physical energy exchange.

TOM and PERC_METAL retain the held branch's root-carrier AM:

```text
materialFactor(t) = 1 - .85*h + .85*h*rootCarrier(t)
```

With a unit sine carrier, its magnitude is at most one. The blend can
create sum/difference components without an unstable feedback path. At
either endpoint `h = 0`, so no interior motion changes the source.

The released string compensates its root loss using the declared loop
filter magnitude. Its feedback must remain positive and below `.9995`.
The Study 009 independent analytical audit sampled 68,921 root/decay/edge
points and found feedback from `.6268956` to `.9979389`. That is a sampled
grid result, not a proof over every Float input; retain runtime bounds
and native range probes.

Fixed output gains are .25 for tom, .28 for pluck, .30 for wood and .28
for metal. The held metal source additionally retains its .85 material
gain. The contact, resonators and output share a finite render. Do not
add `Dsp.levelTo`, patch peak normalization, PUNCH or a limiter to the
extension: those would change raw audition levels and comparison PCM.

## 6. Macro contract and studied defaults

| Control | Changes | Required interactions | Evidence gate |
|---|---|---|---|
| TUNE | Exponential root: tom 70–240 Hz; pluck 180–900 Hz; wood/metal 250–1400 Hz | Decay and EDGE must retain a passive string and safe modal band | Root, finite-output and range probes |
| DECAY | Exponential T60: tom .16–.75 s; pluck .08–.65 s; wood/metal .10–.85 s | Frequency-dependent loss stays separate from root decay | Envelope and string-feedback probes |
| BLEND | Equal-power source weights plus the voice's bounded interior law | BODY shapes handoff timing; endpoints bypass interior processing | 0/.25/.5/.75/1 source-weight and PCM comparisons |
| SNAP | Attack/contact width, excitation strength or tom pitch drop | EDGE controls which contact detail survives | Low and middle tuning activity and paired corners |
| BODY | Tom contact position; pluck release breakpoint; modal participation/loss; shared body shelf | BLEND handoff timing in pluck/wood; EDGE loss/contact relationship | Activity and paired corners; no voice-branch switch |
| EDGE | Excitation spectrum, contact detail and upper loss | TUNE keeps active modes safe; DECAY keeps the string passive | Spectral activity, range and feedback probes |

Defaults are the studied midpoint snapshots, not freshly authored sounds:

| Voice/default setup | TUNE | DECAY | BLEND | SNAP | BODY | EDGE |
|---|---:|---:|---:|---:|---:|---:|
| TOM / Mid | .47057701472336333 | .46 | .5 | .44 | .58 | .44 |
| PERC_PLUCK / Dry | .31739380551401475 | .28 | .5 | .32 | .24 | .34 |
| PERC_WOOD / Knock | .21166135567408279 | .18 | .5 | .40 | .18 | .34 |
| PERC_METAL / Ring | .7625662524497593 | .72 | .5 | .48 | .86 | .74 |

These decimal values are converted to Float once. `OnsetPresetsTest`
pins the seven complete study snapshots by Float bits. Factory names
remain short, uppercase and free of maker names. The bank contains nine
tom, six pluck, three wood and three metal extension patches, rather
than adding untested variations to fill a larger roster.

## 7. Render budget

The proposed interactive target is a warm median of 50 ms or less and a
95th percentile below 100 ms for a one-shot on a representative phone.
This is an engineering target, not a completed device measurement.
Study 009's prototype recorded single cloud-JVM renders from 8.75 to
130.68 ms across its 21 comparison cells; those include startup effects
and are neither warm Kotlin timings nor phone latency.

The implementation is bounded: maximum resolved T60 is .85 s, and the
internal render lasts 1.5 T60, at most 224,910 frames at 176.4 kHz. Mode
counts are six, three or four; the string has one loop. Source coefficients
and contact shapes are prepared once per render. There is no per-frame
geometry reconstruction or iterative contact solver.

No persistent cache is needed to define the model. If device profiling
requires rendered-hit caching, its key must include voice, model, all
six Float macro bits and output-rate contract. The display name is not
part of that key. Measure allocation and source/finish costs before
changing arithmetic for speed; PCM compatibility remains a gate.

## 8. Integration and delivery

| Surface | Change |
|---|---|
| `Onset.kt` and `OnsetExtensions.kt` | Append voices and route model-4 sources behind existing entry points |
| `OnsetPatch.kt` | Validate the model/voice pair; retain explicit complete snapshots |
| `OnsetPresets.kt` | Preserve twelve model-3 presets; add 21 model-4 study patches and `extensionStudy()` |
| Existing `Presets` dispatcher | Continue routing the same ONSET engine; no new engine registration or engine-count change |
| SYNTH picker | Expose appended voices with the same six controls and saved-pad edit path |
| `OnsetKits.extensionStudy()` | Seven dry native midpoint samples in Floor/Mid/High/Dry/Wire/Wood/Metal order |
| `OnsetExtensionKitGenerator` | Separate native XTD/XPM kit, typed `kit.json`, resolved settings and recipe replay checks |

Generate the native sample kit with
`./gradlew :synth:generateOnsetExtensionKit`. Its default ZIP is
`testkit/onset-extension-native/onset-extension-native-kit.zip`.
The existing `generateOnsetAudition` and original 16-pad acceptance kit
stay separate from the extension.

An MPC plays rendered WAVs. Editable history travels through SnipSnap's
local kit history, user-preset backup and supported standalone XTD/XPM
exports with their adjacent sidecar. MPC project/session/song exports and
ordinary XPN expansion packaging deliver audio without ONSET recipes.
This existing format boundary is disclosed, not expanded in this port.

Local kit snapshots retain recipe metadata, but automatic outgoing VERSIONS
takes after an in-place audio rewrite have a pre-existing recipe/audio timing
mismatch. Native lifecycle coverage restores a real pre-edit checkpoint rather
than claiming that automatic case is fixed. The reproduction and source links
are recorded in [ONSET_VERSION_HISTORY_LIMIT](../../ONSET_VERSION_HISTORY_LIMIT.md).

Native velocity articulation is deferred. The existing softening behavior
must retain BLEND and the saved source model; a quieter strike must not
silently become a different hybrid.

## 9. Probes, listening and failure cases

`OnsetNativeAudioCompatibilityTest` reads
`synth/src/test/resources/onset/native-audio-reference.json` and pins the
77 referenced renders. `OnsetPresetsTest` covers the legacy twelve,
the exact study snapshots, 33-entry factory routing, version-preserving
JSON/recipe replay and names. Native DSP probes cover finite bounded
output, deterministic seeds, active controls, blend weights and safe
parameter ranges.

The main sonic risks are a pluck contact becoming hiss, a wood tail
ringing like a bar, a weak root under membrane modes, a midpoint losing
its body, or a louder source winning an unmatched comparison. Judge
these on raw and level-matched copies, both as single hits and in the
same pattern. Frequency projections and envelope probes diagnose those
risks; they do not establish perceived pitch, realism or preference.

Device acceptance still needs REOPEN → edit BLEND → UPDATE → reopen →
export on a phone, followed by MPC sample playback. Compare full raw
tails, full sample windows and unity program levels. Earlier hardware
checks and the existing accepted bank do not establish this new native
build's device acceptance.

## 10. Evidence ledger and as built

| Claim | Status | Evidence |
|---|---|---|
| Existing APIs and export boundaries | Verified from code | SnipSnap base `83546a6` and the integration files named above |
| Study 009 source laws and fixed gains | Read from prototype | `OnsetExtensionRender.java`, SHA-256 `d560d52226d9bdcd7906d0bbb45ab23c25667dccaadd0f38073d0594c258216c` |
| Study 009 analytical string-loss grid | Measured by independent Python audit | 68,921 sampled points; feedback range .6268956–.9979389 |
| Study 009 direction | Heard by owner | “sounds better,” followed by authorization to integrate natively |
| Native PCM compatibility | Passed in the focused JVM run | `OnsetNativeAudioCompatibilityTest`, 77 frozen fixture rows and model-3/model-4 core comparisons |
| Native presets and saved recipe replay | Passed in the focused JVM run | 36 ONSET synth test methods; native preset and real standalone importer checks |
| Native warm render cost | Pending measurement | Cloud JVM and phone results must be labelled separately |
| Native phone/MPC behavior and preference | Pending owner/device check | No new hearing verdict inferred from the port |

The as-built extension uses the studied handoff for pluck and wood,
retains the held AM law for tom and metal, and keeps wood and metal as
separate identities. No deliberate sound revision is part of this port.
The native fixture gate, rather than the prototype's old status string,
determines whether the implementation preserved the comparison audio.

## Why it is built this way

- Putting the work inside ONSET gives the owner familiar controls and
  editable pads instead of another engine to learn.
- The material body grows after the contact in pluck and wood, which
  carries the specific gesture selected during the listening rounds.
- Explicit versions and complete snapshots protect old drums and make
  saved sounds reproducible after new algorithms arrive.
- Raw gains and endpoint bypasses keep the comparison meaningful. A
  normalized or recolored port could pass a broad safety test while
  changing what the owner chose.
- Separate wood/metal identities keep BODY continuous within a voice.
  The risky decision is preserving exact arithmetic while integrating
  with newer primitives; the 77-reference PCM gate tests that boundary.
