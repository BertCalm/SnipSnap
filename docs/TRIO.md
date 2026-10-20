# TRIO — three plucked performers and changing alliances

TRIO is an audition-first adaptation of [OLE's ensemble concept](https://github.com/BertCalm/XO_OX-XOmnibus/blob/a9bdc7803d899f0b1688fe0c0abc4ce481a76c3e/Docs/engines/ole_synthesis_guide.md).
The working name and dry sounds remain provisional. TRIO now includes the
renderer, regenerable patch format, sixteen starting presets, a SYNTH picker
entry and a sixteen-pad MPC kit. Performances remain finite; held-loop behavior
needs a separate design.

## The instrument

Every ENSEMBLE hit explicitly creates three independently articulated parts.
TRES uses staggered doubled-course strums, BERIMBAU uses a struck wire with a
gourd response and pressure gesture, and CHARANGO uses bright paired strings
with repeated plucks. Solo voices select their performer directly, so a solo
melody does not depend on which polyphonic voice slot happens to be free.
These are stylized musical models, not measured reconstructions of instruments.

SIDES travels around a cyclic pair-versus-outsider relationship: at zero Tres
stands apart, at one third Berimbau stands apart, and at two thirds Charango
stands apart. Between positions the relationships interpolate continuously.
The relationship changes articulation and sympathetic exchange, rather than
only the final mix level. MOTION advances that relationship during the saved
phrase. It remains audible in mono.

The first owner listen described the overall sound as pretty good, but DRAMA
as sounding the same through its range. In model 1 the first 70% of that control
does nothing, and the companions are faint replies under an unchanged trio.
The isolated companion distinctions do not establish their audibility in a mix.

Model 2 makes DRAMA a progression through the ensemble's performance. Below 0.7,
paired attacks tighten while the outsider's replies become more assertive;
reverse counterstrums, wire counterstrikes and longer coherent flutter build
through the range. Above 0.7 the companions enter with an earlier, nonlinear
fade and fuller roles: a longer dark oud answer, upper-octave bouzouki courses
and short phin salvos. Their source energy and articulation change, rather than
only the final level. Solo voices retain their original identities; DRAMA is
the ensemble relationship control.

The next owner listen asks for the husbands to strike once and ring while the
wives retain their current strum. Model 3 changes only the companion parts:
oud, bouzouki and phin each enter once per pad performance, with simultaneous
courses and no repeated answers or scrape salvos. Their string loops carry the
ring naturally, and DAMP still controls its decay. Tres, berimbau and charango
retain model 2's exact source audio and event schedule, including the revised
DRAMA responses. The three companion entrances remain staggered as different
players joining the conversation.

The owner likes the single hits, but hears their contribution mainly above
DRAMA 0.85. Model 4 spreads their entrance over the entire control range:
companion strength is `DRAMA^0.65`, with zero remaining wives-only. A setting
of 0.5 now gives about the companion strength previously reached at 0.85.
The wives retain model 3's exact source audio and event schedule. Full DRAMA 1
also retains the accepted model-3 audio exactly, including the single strikes
and their natural decay.

The next listen reports that positive DRAMA settings still give a similar
overall impression. Model 4 brings in the husbands earlier, but keeps all three
attacks in the same early cluster. Model 5 gives the control a rhythmic arc:
spaced replies at low settings, interleaved answers in the middle, and early
overlapping entrances at the top. The wives' exact source audio and event
schedule, the husbands' single-hit string models, DRAMA 0 and the accepted
full-DRAMA scene remain preserved. Listening now focuses on when the answers
arrive, rather than whether their source contributions are numerically different.

## Playing and saving

| Control | Purpose |
| --- | --- |
| TOUCH | Playing force and attack character |
| DRAMA | Conversational intensity, companion presence and reply timing across 0–1 |
| SIDES | Starting pair-versus-outsider relationship |
| MOTION | Alliance travel during the finite performance |
| SPACE | Body and acoustic-arrival character, audible in mono |
| DAMP | String decay |

The requested MIDI root and strike velocity are saved separately from the
timbral controls. The pressure gesture approaches the requested root from
below. Its amount, charango tremolo rate, strum spacing, phrase tempo and length,
variation seed and renderer model are retained in `TrioConfig`. Every render
starts with fresh string, body, event and acoustic state. Exported pads do not
share performer state.

`TrioPatch` participates in `Patches`, `PadRecipe` and native velocity rendering.
Editing its common fields retains the complete configuration. Dry recipe replay
recreates the audio; an optional existing `FxChain` remains a separate treatment.
The renderer uses SnipSnap's shared string tuning and 4× synthesis/decimation
path, with 44.1 kHz mono output.

New patches save renderer model 5. Earlier models remain in `TrioV1`, `TrioV2`,
`TrioV3` and `TrioV4`; saved recipes retain their exact earlier audition audio.
Absent or null model fields decode as model 1, while newly authored
`TrioConfig()` uses model 5. Edits and velocity variants preserve the saved
model. DRAMA 0 uses the accepted original sound exactly in every newer model.
Archived float-audio hashes guard that compatibility.

The finite performance is baked into the sample. An MPC export does not retain
live SIDES, MOTION or DRAMA controls. The current SYNTH audition path folds
stereo to mono; SPACE therefore works through the sound of bodies and arrivals.
HOLD needs a separate powered-performance and settled-loop contract before it
can be introduced.

## Playing the bank

Choose **TRIO** in SYNTH, then TRES, BERIMBAU, CHARANGO or ENSEMBLE. The six
macros sit beside explicit root, velocity, phrase tempo and beat controls.
DRAMA, SIDES and MOTION describe the ensemble interaction; the solo voices
provide direct performer studies. Macro edits preserve the selected preset's
complete configuration and renderer version. Phrase controls keep the playing
gesture within two seconds and leave the rest of the 3.5-second sample for decay.

The sixteen dry `TrioPresets` also form the **TRIO** starter kit, in C minor
pentatonic from C3 to C5:

| Pads | Group | Scenes |
| --- | --- | --- |
| A01–A04 | Roots | Tres Study, Wire Study, Charango Study, Family Root |
| A05–A08 | Low DRAMA | Late Reply, Patient Circle, Wire Apart, Slow Turn |
| A09–A12 | Middle DRAMA | Cross Talk, Inner Answer, Moving Pair, Bright Exchange |
| A13–A16 | High DRAMA | Early Guests, Close Quarters, Gathering Heat, Full Circle |

Family Root and Full Circle preserve the accepted DRAMA 0 and 1 listening
anchors. The other recipes vary register, playing force, tempo, alliance and
movement. These are starting sounds for the owner's musical audition.

```text
./gradlew --no-daemon :synth:generateTrioMusicalBank
./gradlew --no-daemon :cli:snipsnapJar
java -jar cli/build/libs/snipsnap.jar synth TRIO ENSEMBLE --all --out trio-wavs
java -jar cli/build/libs/snipsnap.jar synth TRIO ENSEMBLE --midi 67 --velocity .6 --tempo 120 --beats 3 --out trio-wavs
```

The musical pack provides native MPC 3 `.xtd` and MPC 2 `.xpm` exports, the
sixteen actual pad samples, complete recipes and musical listening phrases.
Copy the native track together with its `_[TrackData]` folder. For an XPM,
keep the program beside its samples. These are one-shot performances with
unchoked tails; the macros are baked into each WAV. Phone and MPC hardware
listening remain acceptance checks for the owner.

## DRAMA development pack

```text
./gradlew --no-daemon :synth:generateTrioAudition
```

The [published audition](https://trio-snipsnap-audition.bertcalm.chatgpt.site)
is private to the owner. Its musical bank is the entry page; the retained
[DRAMA comparisons](https://trio-snipsnap-audition.bertcalm.chatgpt.site/drama/)
remain available separately. Open `testkit/trio-audition/listen.html` for one file with embedded audio and
downloadable evidence, or `index.html` beside its smaller folder assets. The
focused pack starts with Low / Middle / High listening routes and compares
previous model-4 scenes with model 5 at DRAMA .25 / .5 / .75. It also keeps
three solos, a baseline trio and seven DRAMA settings at 0 / .25 / .5 / .7 /
.75 / .85 / 1. Husbands-only sums at .25 / .5 / 1 reveal the response pattern;
individual husband taps come from the DRAMA .5 scene. The sums and taps select
the actual contributions from their matching full scenes. Earlier packs retain
the alliance, TOUCH / SPACE / DAMP, original references and single-hit comparisons.
Roots and velocities stay fixed in those
comparisons. Raw and perceived-loudness-matched versions come from the same dry
render; no rack effect establishes the source identities.

The generator saves patches and recipes, event traces, audio measurements and
source hashes alongside the audio. Companion taps are diagnostic source views;
their saved scene recipe regenerates the complete performance.

The first listening questions are whether the three players remain distinct,
whether alliances change their performance at matched loudness, and whether the
companions add recognizable new characters. Numerical checks can establish
determinism, bounded output, saved-state preservation and causal control changes.
They do not establish convincing instrument identity or musical interaction.

The original renderer, patch, pad-recipe and velocity suites passed 36 checks.
The archived first pack contains 20 positions and 40 PCM24 WAVs from 17 simulations.
The largest raw peak is 0.18849; no raw file required safety attenuation.
The largest absolute whole-file DC mean is 4.24e-6, and matched loudness lies
between 0.02999999 and 0.03000001 under the project's perceived-level proxy.
Those measurements describe the archived model-1 pack, rather than every possible setting.
The archived model-2 DRAMA revision passed 43 distinct renderer, compatibility, patch, pad-recipe
and velocity checks, including dense performances at both pitch limits. Its
26 positions contain 52 PCM24 WAVs from 22 simulations. The largest raw peak is
0.21555 and the largest absolute whole-file DC mean is 4.67e-6; no raw file
required safety attenuation. The six-step sweep and original/updated pairs are
for the next owner listen, rather than a numerical claim of musical acceptance.
The archived model-3 single-hit revision passed 50 targeted checks, including exact model-2
replay, unchanged wife taps and gestures, one companion excitation per pad,
gain-independent tail retention, DAMP response and extreme-register headroom.
Its 20 positions contain 40 PCM24 WAVs from 13 simulations. The largest raw
peak is 0.22345 and the largest absolute whole-file DC mean is 1.10e-5; no raw
file required safety attenuation. The Before / Single hit comparisons provide
the next listening check.
The archived model-4 entrance revision passed 57 distinct targeted checks across the
suite and a focused continuity follow-up. At DRAMA .25 and .5, companion source
power is about 16% and 40% of its full-DRAMA value, with changes to the mix's
gain-normalized spectrum. Wife sources and the accepted full-DRAMA audio remain
exact. Its 20 positions contain 40 PCM24 WAVs from 16 simulations. The largest
raw peak is 0.22345 and the largest absolute whole-file DC mean is 1.08e-5;
no raw file required safety attenuation. Listening comparisons remain the
acceptance check for the earlier entrance.
The model-5 rhythmic revision passed all 64 targeted checks. These include
gain-normalized temporal contrasts between nonzero values, shrinking reply gaps
through short and long phrases, rendered attacks matching their recorded times,
unchanged wife sources and exact full-DRAMA replay. The default husband entrance
span moves from .844 seconds at .25 to .593 seconds at .5 and .100 seconds at 1.
Its 20 positions contain 40 PCM24 WAVs from 13 simulations, including actual
husbands-only sums. The largest raw peak is .22597 and the largest absolute
whole-file DC mean is 1.10e-5; no raw file required safety attenuation. These
measurements establish changed timing and bounded output; the next owner listen
determines whether the control now conveys the intended musical contrast.
Phone and MPC playback, instrument identity and the owner's listening verdict
remain separate acceptance checks.

The musical-bank integration passed 125 targeted JVM checks on the current
SnipSnap source: 75 synth/recipe/velocity/preset checks, 30 shell/preset/starter
checks and 20 CLI checks. Export verification parses both program formats,
checks all sixteen recipes and sample references, and reconstructs the groove
from the exported PCM24 WAVs and its saved score. Browser checks cover every
preview in raw and matched modes at 320, 390 and 1440 pixels, plus the portable
offline subset. An Android SDK is absent in the local environment; Android
compilation is delegated to the source PR's CI build. These checks do not
replace listening on a phone or loading the kit on MPC hardware.
