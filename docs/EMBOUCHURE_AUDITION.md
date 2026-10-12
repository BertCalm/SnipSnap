# Embouchure audition pack

The [published listening page](https://embouchure-audition.bertcalm.chatgpt.site)
is private to the owner. The revised matrix contains 365 model 2 clips plus
164 original model 1 references: 529 clips, 559 audition WAVs, and sixteen
PCM24 kit samples. The curated starter ZIP is 12.016 MiB and contains 35 clips
and 61 WAVs, including the kit. Full Chromium checks pass for all 575 decoded
WAVs, 300 macro selections, 164 original/revised pairs, ten held loops,
diagnostic modes, downloads, model-specific notes, and desktop/mobile layout.
Listening approval of the revised controls remains pending.

Generate the complete dry listening pack from the production Kotlin engine:

```sh
./gradlew --no-daemon :synth:generateEmbouchureAudition
```

The output is `testkit/embouchure-audition/`. Serve that folder with a static HTTP
server, or publish it with its relative paths intact. The task copies the listening
page resources and writes `manifest.json`, `measurements.json`, `audio/*.wav`, and
`embouchure-audition.zip`. The ZIP is a curated starter pack containing the eight
revised presets, six revised voice defaults, their fourteen original references,
selected held/velocity/feedback clips, a matching subset manifest, the offline
page, and a sixteen-pad kit. The full listening
matrix remains in the generated folder and published page. The generator limits
the ZIP to less than 25 MiB for static hosting. Generated audio is excluded from
Git.

The `kit/` folder contains a native MPC 3 `.xtd` track and an MPC 2 `.xpm` twin
inside its shared sample folder. A01–A08 are independent C minor pentatonic reed
notes; A09–A16 contain the eight dry starting presets. The MPC 3 track includes a
two-bar pentatonic phrase. The exported `kit.json` preserves every pad recipe.
These hardware samples use the existing export pipeline's PCM24 format.
The current drum exporter does not enable sample-loop flags automatically.
Held Effort imports one settled buffer; enable looping over that WAV in the
sampler for continuous hardware sustain. The kit README records this requirement.

A smaller development pack is available with
`-PembouchureQuick`. Its manifest explicitly sets `completeMatrix: false`; it is
not the complete listening gate.

The complete pack includes six voice defaults, eight initial presets, three
registers at three velocities per voice, five-point sweeps of all five timbral
macros for every voice, four required 5 × 5 interaction grids on REED, additional
lip/bow contact grids, all-high finite cases, difficult held states, feedback
ablations, and raw/shared-normalized/matched velocity comparisons. Every audition clip is
mono 44.1 kHz PCM16 with no rack effects.

The Original/Revised buttons replay identical notes, velocities, gestures and
control values with their respective renderer models. The 164 historical
references cover the eight presets, six defaults and all 150 five-point
control takes. All six revised finite default voices are sample-identical to
model 1. Clicking a macro value immediately plays it from the start; A/B
compares the voice baseline with the selected point. Listening notes remain
separate by model and loudness mode, including notes saved on the original page.

Model 1 is frozen for saved recipes. New factory patches use model 2; saved
JSON with an absent or null model still restores model 1. Both models use the
same deterministic source seed. To verify a regenerated pack against an
archived original pack, supply `-PembouchureReference=/path/to/model1-pack`;
the generator checks each original recipe and WAV hash. This optional local
reference is not required for ordinary generation.

The default `path` is the host's shared melodic forte reference followed by its
expressive velocity gain (`velocity^0.60`) for both finite notes and held loops.
Every `rawPath` uses one fixed export gain of 0.5 (−6.02 dB) after output conditioning,
preserving relative source amplitudes across all raw files without per-clip leveling.
The unscaled BRASS forte diagnostic peaks slightly above one in the model's acoustic
units; the common gain gives PCM headroom without encoding clamps. `rawSourceMetrics`
records the unscaled conditioned source, `rawMetrics` describes the exported floats,
and `rawExportGain` records that relationship. `matchedPath` uses the quieter common
audition target of 0.03. These extra paths appear on velocity and
feedback diagnostics. Compare versions using the same loudness rule; a change in
gain alone does not establish a change in the modeled playing interaction.

Every clip records its native serialized patch, five timbral macros plus TUNE and
HOLD, driver, gesture, stored MIDI root, effective note, velocity, deterministic
roughness seed, and diagnostic render options. Feedback-off files are engineering
probes: restoring the serialized production patch also restores feedback.
Identical engine inputs reproduce the WAV audio; recorded runtime measurements
vary with the machine and are not part of the sound recipe.
The manifest also fingerprints both compiled renderers and their DSP/tuning
support classes. Each clip records its model, paired comparison IDs where
available, and the build fingerprint. The same fingerprint travels with each
exported listening recipe.

Finite WAVs include the playing gesture and passive tail. HOLD WAVs contain one
settled repeating region with the finite onset omitted. `loopDiagnostics` records
the engine's seam measurement, convergence, and preroll duration; enable repeat
to listen to the join. The measurement buffer contains successive loops, while
the downloadable production WAV keeps one selected loop.
Model 2 permits up to seven genuine warm periods and retains the strict seam
and complete-state convergence limits. Held contact ranges are narrower than
finite gestures to retain a captured repeating tone.

Metrics describe the floating-point samples before PCM encoding. RMS and DC use
the whole clip; loudness uses the shared loudest-200-ms measure. The body spectral
probe skips the onset. Pitch estimates are optional and limited to 40–1200 Hz;
they are not an acoustic-accuracy claim. The existing classifier labels clips
longer than 1.5 seconds LOOP regardless of whether the sound is pitched.

The response tests separate body color and continuing roughness from attack
timing. They examine all five points of every control, corroborate color changes
with root-relative harmonic power, measure actual player exposure and full-note
entry for EFFORT, and measure energy span for LENGTH. These are regression
targets rather than universal audibility thresholds. Some middle clear-tone
steps remain modest; minimum effort is intentionally gentle, particularly BASS
and the late-speaking SOFT entry. SOFT's final EFFORT step remains subtle after
matching; BOW's final step changes sustain intensity more than timbre. BRASS's
strongest overpressure accent briefly suppresses the early note, so its peak
arrives later despite an early entry. EFFORT's entry and peak arrival depend on
the selected gesture. The complete ranges have much stronger entry and contour changes.
The paired GRAIN audit measures 4.07–7.92 dB of
gain-independent body contrast, with the fundamental retaining 56–83% of
high-GRAIN body power. These descriptors corroborate the change; listening
decides its musical usefulness.

The revised renderer and current host integration pass 129 focused JVM tests,
including all nineteen original engine gates, the five-point response matrix,
historical WAV goldens, both model dispatch paths, PRISM/REVEL coexistence,
presets, starter IDs and CLI/shell routes. The Android debug APK is built and
verified with 27 engines, both renderer versions and all four native ABIs.
Device playback remains a separate check.

WHISTLE is a simplified clear-tone air/reed approximation. The drivers and
gestures are original; the pack contains no Yamaha algorithms, factory data, or
sampled instruments. Numerical checks do not approve the dry identity. The
manifest leaves the owner listening verdict pending, and the preset roster stays
at the eight initial candidates until that approval.

The listener interface is maintained in
`synth/src/test/resources/embouchure-audition/`; the generator is
`synth/src/test/kotlin/com/snipsnap/synth/EmbouchureAuditionGenerator.kt`.
