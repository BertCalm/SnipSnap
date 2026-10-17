# Embouchure audition pack

The [published listening page](https://embouchure-audition.bertcalm.chatgpt.site)
is private to the owner. The owner accepted Model 2's voices and revised
controls before authorizing the expanded factory bank. The listening page
now presents 36 presets and their native soft/forte comparisons alongside the
existing diagnostic matrix. The new presets have their own pending listening
verdict; they do not change the accepted renderer.

Generate the complete dry listening pack from the production Kotlin engine:

```sh
./gradlew --no-daemon :synth:generateEmbouchureAudition
```

The output is `testkit/embouchure-audition/`. Serve that folder with a static HTTP
server, or publish it with its relative paths intact. The task copies the listening
page resources and writes `manifest.json`, `measurements.json`, `audio/*.wav`, and
downloadable offline bundles. Together the bundles cover all 36 factory presets,
their complete recipes and all four kits. Each bundle has a matching subset
manifest and offline page; the download list identifies its contents. The full
control and historical comparison matrix remains in the generated folder and
published page. Every bundle stays below 25 MiB for static hosting. Generated
audio is excluded from Git.

The starter `kit/` folder contains a native MPC 3 `.xtd` track and an MPC 2 `.xpm` twin
inside its shared sample folder. A01–A08 are independent C minor pentatonic reed
notes; A09–A16 contain the eight dry starting presets. The MPC 3 track includes a
two-bar pentatonic phrase. The exported `kit.json` preserves every pad recipe.
These hardware samples use the existing export pipeline's PCM24 format.
Three additional factory-bank folders each contain twelve presets and four
complementary C-minor-pentatonic notes. Reeds and Air pairs REED/WHISTLE, Brass
and Bass pairs BRASS/BASS, and Bow and Soft pairs BOW/SOFT. Every factory preset
appears exactly once across these three banks. All 64 pads retain dry complete
recipes and PCM24 samples.

The current drum exporter does not enable sample-loop flags automatically.
Held presets import one settled buffer; enable looping over each held WAV in
the sampler for continuous hardware sustain. Every kit README records this
requirement. File validation does not verify physical MPC import or playback.

A smaller development pack is available with
`-PembouchureQuick`. Its manifest explicitly sets `completeMatrix: false`; it is
not the complete listening gate.

The complete pack includes six voice defaults, 36 presets with 72 soft/forte
companions, three registers at three velocities per voice, five-point sweeps of all five timbral
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
available, and the build fingerprint. New presets have no invented Model 1
history: only the accepted eight initial presets, six defaults and 150 control
takes have historical pairs. The same fingerprint travels with each
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

Reproducible JVM gates cover the nineteen original engine tests, five-point
response matrix, historical WAV goldens, both model dispatch paths,
coexistence with the other engines, presets, starter IDs and CLI/shell routes.
The bank tests additionally preserve the accepted eight Model 2 recipes and
PCM, check complete kit coverage, and render each new preset and its exact held
configuration. Run focused tests with:

```sh
./gradlew --no-daemon :synth:test --tests '*Embouchure*'
```

The generator asserts its complete matrix and historical-reference coverage.
Android build checks and file/export validation remain separate from physical
Android/MPC playback.

WHISTLE is a simplified clear-tone air/reed approximation. The drivers and
gestures are original; the pack contains no Yamaha algorithms, factory data, or
sampled instruments. Numerical checks do not approve a preset's musical value
or the perceived smoothness of a loop. The manifest records the accepted Model 2
identity and keeps the expanded bank's listening verdict pending.

The listener interface is maintained in
`synth/src/test/resources/embouchure-audition/`; the generator is
`synth/src/test/kotlin/com/snipsnap/synth/EmbouchureAuditionGenerator.kt`.
