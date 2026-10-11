# Embouchure audition pack

Generate the complete dry listening pack from the production Kotlin engine:

```sh
./gradlew --no-daemon :synth:generateEmbouchureAudition
```

The output is `testkit/embouchure-audition/`. Serve that folder with a static HTTP
server, or publish it with its relative paths intact. The task copies the listening
page resources and writes `manifest.json`, `measurements.json`, `audio/*.wav`, and
`embouchure-audition.zip`. The ZIP is a curated starter pack containing the eight
presets, six voice defaults, selected held/velocity/feedback clips, a matching
subset manifest, the offline page, and a sixteen-pad kit. The full listening
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

The default `path` is the host's shared melodic forte reference followed by its
expressive velocity gain (`velocity^0.60`) for both finite notes and held loops. `rawPath` preserves
the generated amplitude after output conditioning, and `matchedPath` uses the
quieter common audition target of 0.03. These extra paths appear on velocity and
feedback diagnostics. Compare versions using the same loudness rule; a change in
gain alone does not establish a change in the modeled playing interaction.

Every clip records its native serialized patch, five timbral macros plus TUNE and
HOLD, driver, gesture, stored MIDI root, effective note, velocity, deterministic
roughness seed, and diagnostic render options. Feedback-off files are engineering
probes: restoring the serialized production patch also restores feedback.
Identical engine inputs reproduce the WAV audio; recorded runtime measurements
vary with the machine and are not part of the sound recipe.
The manifest also fingerprints the compiled renderer and its DSP/tuning support
classes, distinguishing candidate calibrations that share the initial model
number. The same fingerprint travels with each exported listening recipe.

Finite WAVs include the playing gesture and passive tail. HOLD WAVs contain one
settled repeating region with the finite onset omitted. `loopDiagnostics` records
the engine's seam measurement, convergence, and preroll duration; enable repeat
to listen to the join. The measurement buffer contains successive loops, while
the downloadable production WAV keeps one selected loop.

Metrics describe the floating-point samples before PCM encoding. RMS and DC use
the whole clip; loudness uses the shared loudest-200-ms measure. The body spectral
probe skips the onset. Pitch estimates are optional and limited to 40–1200 Hz;
they are not an acoustic-accuracy claim. The existing classifier labels clips
longer than 1.5 seconds LOOP regardless of whether the sound is pitched.

WHISTLE is a simplified clear-tone air/reed approximation. The drivers and
gestures are original; the pack contains no Yamaha algorithms, factory data, or
sampled instruments. Numerical checks do not approve the dry identity. The
manifest leaves the owner listening verdict pending, and the preset roster stays
at the eight initial candidates until that approval.

The listener interface is maintained in
`synth/src/test/resources/embouchure-audition/`; the generator is
`synth/src/test/kotlin/com/snipsnap/synth/EmbouchureAuditionGenerator.kt`.
