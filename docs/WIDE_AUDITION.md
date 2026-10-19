# WIDE — first compound-board audition

Private audition: https://snipsnap-wide-audition.bertcalm.chatgpt.site

This is the first listening proof for adapting Epic Chains to SnipSnap. It uses
the existing Kotlin processors in the explicit order **TAPE → EQ → ENSEMBLE →
SPRING**, rather than the fixed order in `FxChain`. It is an OMNISTEREO-inspired
interpretation: there is no new BBD model, dedicated vibrato or FDN reverb.

Run `./gradlew :synth:generateWideAudition` to regenerate the audio and manifest
under `testkit/wide-audition/`. Set `-PwideOutput=/absolute/output` to choose a
different directory. The output is ignored by Git. The generator, source patch
settings, stage settings, audition matching and mono measurements are retained
so the listening evidence can be reproduced.

Four sources — PLUCK nylon, VOX choir, THUMP snare and VELVET bass — are compared
at Original, 35%, 70% and 100%. AMT scales the board's parameters toward neutral;
it is not a parallel dry/wet crossfade. Original bypasses the board. Each phrase
repeats a rendered one-shot: modulation and effect tails restart with each hit.
The experiment does not establish continuous-bus or seamless-loop behavior.

Stereo audition files share the repository's `AuditionLevel` target. Mono files
are the average of those exact left/right channels and are not re-levelled.
Consequently mono cancellation stays audible. Every comparison uses the same
source and hit timing; the pluck also has cumulative stage comparisons.

The hosted page provides source and strength switching, stereo/mono comparison,
downloads and browser-local listening notes. Notes are not sent to a server.

The audition now uses the production `Wide` renderer; its audio hashes are
unchanged. The editable pad-sheet treatment, source preservation and undo are
documented in [WIDE.md](WIDE.md). The existing rack order remains unchanged.
