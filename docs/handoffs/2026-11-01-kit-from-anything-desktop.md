# SnipSnap KIT FROM ANYTHING and QUARRY: desktop handover

Prepared November 1, 2026. This document closes the cloud design and implementation
session and hands the listening, the phone door and the remaining rounds to a desktop
session with an Android SDK, the phone and the MPC in reach. Nothing in this session was
heard: every number below is a measurement, and the first job of the desktop session is to
listen.

## Start here

**Everything up to round one is merged on the default branch; rounds three and zero are on an
open pull request.** Start from the default branch once #833 has merged, or from the branch
`claude/snipsnap-sound-drum-kit-40meou` if it has not. Do not start from the old cloud
checkout.

Josh's idea, in his words: any sound in, four bass notes, four tones, kick, snare, closed hat,
open hat, ride, crash, clap and siren out, in a key he picks and a genre that guides the kit's
sound. His decisions, taken in conversation and binding on every round: **purist by default**
(every pad is the source, transformed), the synth is **a blend fallback never over half and
never a replacement**, the tonal half of that fallback is the **QUARRY** engine (a sound pushed
through oscillators and a grain field), and the first genres are **house, techno and boom bap**.

| Reference | State checked November 1 |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Work branch | `claude/snipsnap-sound-drum-kit-40meou` |
| QUARRY engine brief | [PR #775](https://github.com/BertCalm/SnipSnap/pull/775), merged; corrected in [PR #800](https://github.com/BertCalm/SnipSnap/pull/800), merged |
| KIT FROM ANYTHING brief | [PR #796](https://github.com/BertCalm/SnipSnap/pull/796), merged; corrected in #800 |
| Rounds zero and one (the assembler, the CLI verb, the mute-group override) | [PR #826](https://github.com/BertCalm/SnipSnap/pull/826), merged |
| Round three (the blend) and round zero (the recipe) | [PR #833](https://github.com/BertCalm/SnipSnap/pull/833), open at the time of writing, head `3615de79` |
| The brief, with every round's "As built" | [`docs/superpowers/specs/2026-11-01-kit-from-anything-design-brief.md`](../superpowers/specs/2026-11-01-kit-from-anything-design-brief.md) |
| The QUARRY brief | [`docs/superpowers/specs/2026-10-31-quarry-engine-design-brief.md`](../superpowers/specs/2026-10-31-quarry-engine-design-brief.md) |
| The CLI verb's entry | [`docs/CLI.md`](../CLI.md), `kitfrom <wav>` |
| Feature plan row | `docs/FEATURE_PLAN.md`, WW7 |

**The one known red on the default branch** is not this work's: `KeelTest` ("root pitch at
WEIGHT 0 … BELLY 30 has no pitch"), the WIP KEEL bass engine, fails on a pristine checkout of
the base. It keeps `jvm-tests` red on every PR until it is fixed on the base. Four shell
failures that appeared on #826 were the base's too and are fixed there now.

## What is implemented

### The assembler: `KitFromAnything` (`synth/src/main/kotlin/com/snipsnap/synth/KitFromAnything.kt`)

It lives in `:synth` beside `SynthKits`, because `:synth` depends on `:kit` and the assembler
needs `Desample`, `FxChain` and later QUARRY. `assemble(source, key, genre, sourceFile?)`
returns sixteen `ArrangedPad`s in a fixed layout that `KitAssembler.assembleArranged` lays
out: kick A01, snare A02, closed hat A03, open hat A04 (the two hats choke each other and
nothing else does), clap A05, ride A06, crash A07, siren A08, bass root/third/fifth/octave A09
to A12 from C1 plus the root, tones A13 to A16 an octave and a half up. Thirds are chosen by
semitone interval (a pentatonic's third entry is its fourth; CHROMATIC takes the minor third).

Three tricks make every drum from any sound, and all three keep the source:

- **read it faster** (band-limited through `Resampler`) for the hats, the ride and the crash;
- **sing its cycle at a drum pitch**: one averaged period of a source with a period, or a 46 ms
  excerpt forced to wrap, as a mip-mapped wavetable (`cycleAt`, `tableFor`) for the kick and
  snare bodies, the siren and every note. A wavetable read at a frequency is periodic at that
  frequency whatever is in it, so the note is built in, not found;
- **whiten its arrival into noise** (`whiten`, `noiseOf`: the source divided by its own exact
  long-term spectrum twice, floored at −50 dB, then one 1024-sample window at the arrival
  frozen with new seeded phases, read faster until half its power sits above 2 kHz) for the
  snare and the clap.

Every pad is gated: the real classifier for kick, snare, hats and clap; centroid and decay for
ride and crash; the ordered pitch trajectory for the siren; root within 20 cents at confidence
0.3 for the notes (`readPitch` reads a pad faster or slower by a power of two so C1 and a
1760 Hz siren both sit inside `Pitch.detect`'s 40 to 1200 Hz). A pad that fails its gate is
**blended** (`blend`, `partnerFor`): the purist render stays at no less than half, a partner
joins at `BLEND_SHARE` 0.5: `Desample.nearest` on the purist render for a drum (its distance
reported, `far` said), a resonator at the note struck by the pad's own head for a note, a
band-limited pulse along the same trajectory for the siren. The blend is gated again. An
unmeasured pad is not blended.

Every pad's `source` map says everything: `made` (purist or blend), `gate`, `gateDetail`,
`synth`, `synthShare`, `synthDistance`, `gateAfterBlend`, the source's hash and how it was
read, the note for bass and tones. With a kept source, every pad carries a **recipe**
(`KitFromAnything.Recipe`, JSON key `kitFromAnything`: version, slot, genre, root, scale,
source file, source hash). `rerender(recipe, source)` rebuilds the pad bit for bit, blend
included, and refuses a swapped source in words; `check(dir)` rebuilds every such pad in a kit
folder and compares the house writer's bytes with the WAV on disk.

`ArrangedPad.muteGroup` (`:kit`) is the override the ride and crash use: HAT_OPEN's tag for
colour and balance, choke group 0.

### The genre table

`KitFromAnything.Genre` holds three `Style` rows of plain numbers (tempo, kick pitch, drop,
decay, click, low-pass, drive; snare tune, decay, brightness; hat lengths and read speed; ride
and crash lengths; the siren's shape; bass and tone decays). BOOM BAP and HOUSE are translated
from the THUMP preset rows the repo ships; **TECHNO is extrapolated from HOUSE's WAREHOUSE and
PEAK TIME corner and marked so in the code**. Every number is "shape" until a listen. A fourth
genre is a row and a listen, not a new mechanism.

### The CLI verb

```
./gradlew :cli:snipsnapJar
java -jar cli/build/libs/snipsnap.jar kitfrom <wav> --key Am --genre house --out <dir> --name "<name>"
java -jar cli/build/libs/snipsnap.jar kitfrom --check "<dir>/<name>"
```

The first prints the gate table (pad, slot, gate, what the gate measured), what joined each
blended pad and what the gate said afterwards, writes the kit folder with the genre's tempo,
and keeps the source as `kitfrom-source.wav` beside `kit.json`. The second rebuilds every pad
from the sidecar and the kept source and says, pad by pad, whether the WAV on disk is the
rebuild bit for bit (exit 1 on a difference). `export <kit-dir>` is the existing fan-out to the
MPC formats.

### Tests

`synth/src/test/kotlin/com/snipsnap/synth/KitFromAnythingTest.kt` (22), `cli/.../KitFromCommandTest.kt`
(1, end to end), `kit/.../MuteGroupOverrideTest.kt` (1), and one refusal in
`shell/.../RecipeReplayTest.kt`. The fixtures are synthesized (a PLUCK preset, a THUMP snare, a
two-formant "bark", a pure sine sweep, an 880 Hz sine). **Real captures are owed**: the brief's
R0 fixture, two WAVs of Josh's own under a second, 44.1 kHz mono, one tonal and one drum-like,
checked into `synth/src/test/resources/`; keep the synthesized ones too.

### Measured, never heard

Twelve kits from four stand-in sources × three genres, 192 pads, every pad gated, in Kotlin
against the real classifier: 183 pass, 8 fail, 1 unmeasured before the blend. The brief's "As
built" sections hold every number and every limit, including these two: with the sine sweep's
own noise held at half its blended hats still read SNARE to the classifier (the cap is the
rule), and a noise-dominated source's upper tones sing off the note until the RING partner
joins.

## What the desktop session does, in order

1. **Listen.** Make kits from three or four of Josh's own captures (a voice, a dog, a pan, a
   synth stab, anything), one per genre, with `kitfrom`, then play each pad beside its source.
   Judge what the brief's "heard?" rows ask: does a bark's bass at C2 still sound like the bark
   (the colour press, `stain`, is what should keep it so); do the hats and the crash keep the
   source's identity after whitening and freezing; is the blend at half audible as the source
   plus a partner or as a replacement; is the siren's shape right per genre; are the genre
   rows in the right neighbourhood (the techno row especially). Write what was heard into the
   brief's ledger as *heard* rows, replacing the *heard?* ones, and into the "As built".
2. **Load one kit on the MPC** through `export` and the device checklist in `docs/BENCH.md`:
   the chokes (A03 chokes A04, nothing chokes A06 or A07), the tempo stamp, the pad names.
3. **Tune by listening, not by the table.** Every genre number is marked shape. Change a number,
   re-run the suite's `KitFromAnythingTest` (the gates are the referee), listen again. The
   gates decide what a kick *is*; the ear decides whether it is a good one.
4. **R4, the pad sheet door** (needs the SDK, which is why it waited). Follow the PAD FROM
   ANYTHING precedent: `shell/.../PadMaker.kt` holds the knobs, `app/.../ui/PadSheetScreen.kt`
   holds the card and its MAKE PAD button. A KIT FROM ANYTHING card wants: the pad's audio or
   a fresh capture as the source, the kit's key (the KIT screen's KEY panel and `KeyPicker`
   exist), a genre choice, MAKE KIT, then the gate table as the result sheet with each blended
   pad's partner named. `KitAssembler.assembleArranged` writes the folder; put the kit in the
   open project as other makers do; keep the source as `kitfrom-source.wav` and pass
   `sourceFile` to `assemble` so every pad gets its recipe. The conventions file defers the
   picker to a session that can compile it; this is that session.
5. **QUARRY, when wanted.** The brief is merged and prototyped. Its round zero (how a recipe
   reaches its source) is now decided by this work: a kept source file and a hash. Its CYCLE
   path is already inlined in the assembler (`analyse`'s cycle, `tableFor`, `cycleAt`, `stain`)
   and should be lifted into its own object when QUARRY becomes an engine, so one reader serves
   both; the grain field and HOLD are the new work. The RING resonator in `partnerFor` is the
   bridge QUARRY replaces for the tonal blend.

## Decisions Josh still owns

- The four bass notes: root, third, fifth, octave as built, or a walking shape (root, flat
  seventh, octave, fifth) for boom bap.
- The techno row's numbers, which no preset in the repo backs.
- `BLEND_SHARE`'s cap at 0.5, and whether an unmeasured pad should ever blend (today it does not).
- Whether the siren should be a slot at all in every genre, and its three shapes.
- Which of his captures become the checked-in fixtures.

## Working rules the next session should keep

- `./gradlew --no-daemon test` is CI's JVM suite; the `:synth` part runs over two hours and
  exceeded the cloud session's limit, which is why the full suite was left to CI there. Run the
  targeted suites named above before every push, and the whole thing once before the day ends.
- The steward skill (`.claude/skills/steward/SKILL.md`) and the Luthier skill
  (`.claude/skills/luthier/SKILL.md`) are the house rules; the Luthier probe
  (`.claude/skills/luthier/scripts/probes.py`) had its NSDF normalization fixed in #800 and is
  trustworthy now.
- Numbers are quoted with the test or run that produced them; "heard" is a claim only Josh
  can make. Keep the ledger's four words apart.
- Review bots have been right every time on this work (seven of seven on #826, three of three
  on #800); verify each finding against the repo, then take it.

## A prompt to start the desktop session with

> Read `docs/handoffs/2026-11-01-kit-from-anything-desktop.md` first. Build the CLI jar, make
> kits from these captures of mine (paths…) in A minor, one per genre, and set up an audition
> so I can play each pad beside its source. Record what I say I heard in the brief's ledger.
> Then wire the KIT FROM ANYTHING card into the pad sheet following PAD FROM ANYTHING's
> precedent, and leave the picker deferral note out: this session can compile it.
