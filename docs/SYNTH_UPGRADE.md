# Synth upgrade — from lab to instrument

`docs/SYNTH_ROADMAP.md` is done: S1–S5 all shipped, eight engines, thirty
voices, the FX rack, keygroup export, the instrument suite. This document is
what comes after, and it starts from a different question than the roadmap
did. (The roadmap itself grew one more phase since: S6/SKIN, one more engine
and eight more voices in the roadmap-wide count above — which, like "eight"
there, counts GRAINS — shipped after this document's own U3/U5/U6 work. The
U6 section below counts differently: eight engines on the `OVERSAMPLE`
contract, a roster that excludes GRAINS by construction. See that section
and `SYNTH_ROADMAP.md`'s S6 row.)

The roadmap asked *what should we build*. This asks *what does a seventeen-
year-old with a phone and no money actually experience when they open SYNTH* —
and the answer is worse than the engine count suggests.

## The gap, in one line

```
Voices in the app:  30   (THUMP 8, TINES 5, PLUCK 4, VELVET 4, FATHOM 3, TONEWHEEL 3, VOX 3)
Presets:             0
```

Not "a few". Zero. There is no preset concept anywhere in `:synth` or in
`SynthScreen`. Every voice has exactly one hardcoded `defaults()` macro map,
so the app ships thirty sounds and a row of sliders.

What that means for the target user: they pick KICK, hear **one** kick, and
are handed TUNE · SWEEP · DECAY · CLICK · DRIVE. Someone who has never built a
sound does not know what SWEEP does. They wiggle, make it worse, and leave.

The roadmap's own first playability rule is **"Preset-first, knobs-second.
Every engine is playable at first touch."** The knobs half shipped. The preset
half never did.

## What is already right, and worth protecting

`SynthScreen.kt:232` is the hard part and it is well built: a debounced ~100 ms
re-render that auto-retriggers the audition, cancels an in-flight render when
you keep dragging, and draws the result on the scope. That is roadmap rule 5,
the instant loop, and it is exactly the mechanism that makes a preset list
addictive — tap, hear, tap, hear, at browsing speed.

**The slot machine is built. Nothing has been put in the reels.** Every item
below is cheaper than it looks because that loop already exists.

## Who this is for, and what follows from it

The user is a young producer who cannot spend money on tools. Two consequences
that should discipline every decision here:

**1. Free competition is good now.** Vital is free and genuinely world-class;
Koala Sampler costs a few pounds and is beloved by exactly this person. "A free
synth" is a losing frame — we would be fighting on their turf and the answer
is always "but Vital exists."

**2. So the synth is not the product.** The moat is the loop nobody else has:
*grab sound off your phone → chop → kit → export to hardware, in five minutes.*
The synth's job is to **fill the holes in a captured kit** — you ripped a snare
off a video, now you need a kick and a hat that sit with it.

That reframes the target from *deep and clean* to **fast and vibey**, which is
both easier to hit and the thing our competition structurally cannot copy,
because no standalone synth knows what is on your other fifteen pads. It is
also why U1 and U7 below outrank every DSP item, and why the fidelity work that
looked most important when reading the code sits in the middle of the list
rather than the top.

## The naming rule still binds — including on us

`SYNTH_ROADMAP.md:27` is unambiguous: no trademarked names or model numbers in
the app, presets, or descriptions, and **"not obvious near-misses of them"**.

A preset library is 400 new names, which is 400 new chances to break that rule,
and the temptation is strongest exactly where the sound is most recognisable.
A name like `808-ADJACENT` is precisely the near-miss the rule forbids — it is
not a clever workaround, it is the violation with a wink. Presets get named for
what they *sound like* (`CONCRETE`, `TAPE THUD`, `PAPER CUP`), never for what
they are imitating.

U1 makes this testable rather than a matter of vigilance — see the blocklist
test below. This document may name machines; it is the document that states the
rule, which `SYNTH_ROADMAP.md:44` explicitly allows.

---

# U1 — The preset library

**The single biggest jump in perceived value, and it needs no new DSP.** This
is sound-design work — authoring macro values against the engines exactly as
they are today.

### The format is already built

`Patch` (`Patches.kt:15`) carries `name`, `engine`, `voiceName`, `macros`, and
serializes through `Patches.toJsonValue`. **A preset *is* a named `Patch`.**

That means no new file format, no `PadRecipe` change, no version bump, and
presets drop into `kit.json` and `.xpn` sharing through machinery that already
round-trips. U1 is additive and cannot break an existing kit.

### Shape

Per-engine, matching the shape the engines already have (`macrosFor`,
`defaults`, `scramble`):

```kotlin
// in Thump.kt, beside macrosFor/defaults/scramble
fun presets(voice: ThumpVoice): List<ThumpPatch>

// and one dispatcher, beside Patches.fromJsonValue
object Presets {
    fun forVoice(engine: String, voice: String): List<Patch>
    fun byName(engine: String, voice: String, name: String): Patch?
    fun all(): List<Patch>
}
```

### Counts

| Engine | Voices | Presets each | Total |
|---|---|---|---|
| THUMP | 8 | 16 | 128 |
| TINES | 5 | 12 | 60 |
| PLUCK | 4 | 12 | 48 |
| VELVET | 4 | 12 | 48 |
| FATHOM | 3 | 12 | 36 |
| TONEWHEEL | 3 | 12 | 36 |
| VOX | 3 | 12 | 36 |
| | | | **392** |

Drums get 16 because drums are what a beatmaker reaches for first and a kick is
the sound they are pickiest about.

### Naming

Uppercase, **≤ 14 characters** so it fits the sunken LCD listbox, unique within
a voice, named for character. Worked examples for KICK
(TUNE · SWEEP · DECAY · CLICK · DRIVE):

| Name | Character |
|---|---|
| `CONCRETE` | short, hard, almost no tail |
| `TAPE THUD` | soft knee, rolled-off top, long-ish |
| `PAPER CUP` | tiny, clicky, comedic |
| `DEEP WATER` | low tune, slow sweep, long decay |
| `BOOM BAP` | mid tune, fast sweep, dusty |
| `PILLOW` | no click, all body |
| `GARAGE` | tight, bright click, driven |
| `HEARTBEAT` | very low, very soft, no click |

Actual macro values get dialled in by ear during implementation and then frozen
by the tests below — this spec fixes the *system* and the *count*, not the
numbers, because numbers that were not listened to are worthless.

### Tests — the classifier is the harness, as ever

1. **Identity.** Every `KICK` preset classifies as `KICK`, every `SNARE` as
   `SNARE`. A preset that lost its own drum class is a bug, not a style.
2. **Sanity.** Non-silent, no `NaN`, nothing exceeding full scale.
3. **Round-trip.** Every preset survives `toJsonText` → `fromJsonText`
   unchanged, and renders identically after the trip.
4. **Names.** Unique per voice, uppercase, ≤ 14 chars.
5. **Blocklist.** No preset name matches the trademark blocklist — a regex over
   the famous model numbers and maker names. This turns the roadmap's legal
   rule from vigilance into CI.
6. **Spread.** Presets within one voice must not cluster: minimum pairwise
   distance across the macro vector. Sixteen near-identical kicks is a list
   that *looks* full and *feels* empty, and this is the test that catches the
   most likely way U1 gets done badly.

### UI

The preset listbox the roadmap already specified (`SYNTH_ROADMAP.md:186`):
sunken listbox, current preset highlighted, tap to load. Loading a preset sets
the macro sliders — so a preset is a *starting point you can then wreck*, which
is how a beginner learns what SWEEP does: they hear it move away from something
good.

---

# U2 — SCRAMBLE that lands

**Depends on U1.**

Today (`Velvet.kt:53` and every other engine):

```kotlin
fun scramble(voice, random) = macrosFor(voice).associate { it.name to random.nextFloat() }
```

Each macro rolled independently and uniformly — a flat roll over a 5- or
6-dimensional box. But good sounds occupy a thin sliver of that box, not its
volume, so most rolls land somewhere dull. That is why SCRAMBLE currently feels
like a novelty instead of the slot machine roadmap rule 4 promises.

### The fix

Once a preset library exists, roll *near a preset* instead of across the box:

```kotlin
fun scramble(
    voice: ThumpVoice,
    random: Random,
    temperature: Float = 0.35f,
    near: Patch? = null,      // defaults to a random preset for the voice
): Map<String, Float>
```

Pick a seed preset, perturb each macro by a gaussian scaled by `temperature`,
clamp to 0..1. `temperature = 0` returns the preset untouched;
`temperature = 1` reproduces today's uniform roll exactly, so nothing is lost
and the old behaviour stays reachable.

At the default temperature a roll lands on something usable most of the time,
because it starts from something that already worked.

### UI

SCRAMBLE stays one tap. A `WILD` slider (or long-press) exposes temperature for
people who want the far end. Still always undoable, per rule 4.

---

# U3 — Punch

**Why a beginner concludes a free tool is cheap.** Their kick does not hit like
the kick on the reference track, and they cannot say why.

The cause is in the code: every engine ends with `Dsp.normalize(buf, 0.95f)`
(`Dsp.kt`) — **peak** normalisation. Peak is not loudness. A commercial
kick hits because of transient shaping and saturation, not because its peak
sample is high, and two sounds normalised to the same peak can differ by more
than 10 dB in perceived level. (Post-Punch, THUMP no longer ends this way -
see below.)

### The fix

A `Punch` stage in `:synth`, engine-owned. `Dsp.normalize` moves *before* it
(fixing the reference peak Punch then preserves) and a new `Dsp.limitPeak` -
a clipping safety net that only rescales if the shaping below actually pushed
a sample past what's safe, unlike `normalize`'s unconditional rescale - takes
its old spot after, so swapping the peak target for a perceived-level one
(below) actually reaches the output instead of being overwritten back to a
fixed peak on the way out:

- **Transient shaping** — a boost strongest at the very onset, tapering to
  nothing over a short fixed window. An envelope-follower difference (fast
  vs. slow), the standard transient-designer trick, was the first attempt and
  didn't survive contact with THUMP's own range of voices: getting a
  fast/slow follower pair to track cleanly from RIM's ~30ms decay to KICK's
  ~850ms one, without lagging a short hit's true onset or staying elevated
  for most of the hit, turned into per-voice tuning fragility. Every one-shot
  voice here starts its attack at exactly t=0 by construction, so a fixed
  early window facing that already-known onset is simpler and more reliable
  than detecting it.
- **Soft saturation** — glue and harmonics, reusing `Dsp.drive`, scoped to
  that same short onset window rather than the whole buffer (whole-buffer
  saturation, even at an inaudible amount, was enough to tip a bass-heavy
  voice like KICK across its classifier's bass-dominance threshold once a
  downstream stage like DUB compounded it further).
- **Loudness-targeted normalise** — swap the peak target for a perceived-level
  target using `Loudness` in `:audio`, which already exists for kit balance.

Exposed as a `PUNCH` macro on the drum voices. **This changes the output of
every existing render**, so it lands with U5/U6 under one version bump — see
the migration note below.

---

# U4 — Stereo

Every engine returns `channels = 1`. Mono renders sound *small*, and "small" is
the exact quality a beginner hears as amateur.

The FAT and SPREAD macros already detune oscillators against each other. In
mono that interference is **beating**; in stereo the same DSP is **width**.
Same computation, dramatically different perceived size.

Per-voice, because mono is often correct: kick and sub bass stay mono (and mono
is safer for club systems anyway). Hats, claps, TONEWHEEL, VOX, GRAINS and the
VELVET/FATHOM unison voices get width.

Cost is honest and worth stating: **WAV file size doubles** on an SD card, and
the FX rack needs a stereo-safety audit. Opt-in per patch.

---

# U5 — Envelopes and filter saturation

Two DSP items that are genuinely audible and improve all ~520 presets at once,
which is why they come *after* the presets exist to benefit from them. (Was
~392 when this was written; SKIN's own 128 arrived a wave later, in the U1
pass that engine had never had.)

### `Dsp.Env`

There is exactly one envelope shape in the codebase: `Dsp.envAt`
(`Dsp.kt:185`), exponential decay to −60 dB. Attacks are hardcoded linear ramps
(`Velvet.kt:126` is a bare 3 ms). Real instruments have an attack *curve* and a
two-stage decay — a fast initial drop, then a slow tail.

One shared primitive — attack curve, optional hold, two-stage decay, release
for the sustaining keygroup patches — replaces the scattered ad-hoc envelopes.
Best effort-to-payoff ratio in the DSP list: one small file, eight engines
improved.

### Saturation in the filter

`TptSvf` (`Dsp.kt:63`) is linear. Analog filters *self-limit* when resonance is
pushed; ours rings cleanly and then hits the normaliser. A `tanh` in the
feedback path is most of what people mean by "sounds analog", and it is the
reason U6 must exist — saturation generates harmonics above Nyquist, which
fold back as dirt unless the render is oversampled.

---

# U6 — Anti-aliasing, as plumbing

**Deliberately ranked low as a headline, and required as a foundation.**

The oscillators are naive: `Velvet.kt:67` (saw), `:71` (pulse), `Dsp.kt:35`
(square), and FATHOM's saw pair. Before the fix below landed, there was no
oversampling anywhere in `:synth` — the only anti-aliasing mentions in the
module were `Eras.kt` deliberately *not* doing it, which is character by
design.

**Status: implemented.** All 7 engines that existed at the time (THUMP, TINES,
VELVET, FATHOM, TONEWHEEL, VOX, PLUCK) render at `RATE * Dsp.OVERSAMPLE` and
decimate — see `Dsp.OVERSAMPLE`'s KDoc. The migration work below (`alias`
flag, `PadRecipe.VERSION` bump, `testkit/` regen) has shipped too — see that
section's own "done" status. SKIN (`SYNTH_ROADMAP.md`'s S6, shipped after
this section) is an eighth engine that was never migrated because it was
never naive — it renders oversampled from its very first commit, following
the convention this section establishes rather than needing to catch up to it.

The target user will rarely hear this directly. Where it does bite is the S5
multisampled instruments: a keygroup rendered every minor third across four
octaves gets dirtier as it climbs, so the instrument changes character *in
kind* as you play up it — the one thing a multisampled instrument must not do.

### The fix

Render at 4× (176.4 kHz), low-pass, decimate to 44.1 kHz.

Oversampling rather than PolyBLEP band-limited oscillators, for a reason
specific to this codebase: PolyBLEP fixes only the *oscillator*, but
`Dsp.drive`'s `tanh` (`Dsp.kt:181`) also creates harmonics above Nyquist, and
U5's filter saturation will create more. Oversampling cleans the whole signal
path at once.

**We can afford it because this is offline.** A one-shot renders in tens of
milliseconds; nobody hears the CPU. A live synth could not make this trade, and
that is a standing advantage this codebase has never spent.

### The per-voice `alias` flag — implemented

Grit stays available where it is the point — VELVET `CHIP`, and anything
feeding CRUNCH. Landed as `PadRecipe.alias: Boolean` (`PadRecipe.kt`), not a
`Patch` field: every `Patch` subtype already carries `name`/`engine`/
`voiceName` (`Patches.kt:14-18`), but those are required identity fields
every subtype has always had, not an *additional* per-patch semantic field.
Adding a genuinely new one would have touched the shared codec plus all 7
patch data classes' constructors and their own `Patches.VERSION` bump, where
`PadRecipe` already had the exact optional-field shape (`treatment`/`amount`)
to extend instead.

It is pure metadata — every engine already always renders through the U6
oversample/decimate path regardless of this flag; it only records whether a
pad's grit is deliberate. The default is computed from the patch/fx already on
hand (`true` for VELVET `CHIP` or any chain feeding `Crunch`, `false`
otherwise) rather than requiring every call site to say so explicitly, and is
always written explicitly on `toJsonValue` - so the "clean for new, aliased
for old" framing this section originally had doesn't apply: there is no old
recipe to read a default for, since v1 is now rejected outright (below), and
`Eras`-produced audio keeps its own separate `{"era","amount"}` recipe shape,
never reaching `PadRecipe` at all.

### Migration — done

`PadRecipe.VERSION` is now 2; `fromJsonValue` rejects anything else
(`PadRecipe.kt`) with no migration path, as recommended below. The **369
committed WAVs** under `testkit/` (this section's original count of 376 was
off) were regenerated via the existing gradle generator tasks alongside the
bump.

What was *not* at risk: `PadRecipeTest.kt`'s self-consistency test re-renders
both sides in the same run, so it stayed green throughout; and the goldens in
`reference/golden/` are XPM/XML format files, not audio hashes, so export
tests were unaffected.

The two production call sites that parse a loaded kit's recipe as a
`PadRecipe` (`Breed.recipeOf` unconditionally; `RecipeReplay.plan` only for
its `"patch"`-shaped branch) already treated a parse failure as "no recipe
here" rather than propagating the exception, so a stale v1 *synth-patch*
recipe degrades those specific features gracefully rather than crashing -
confirmed by reading both call sites, not assumed.

FX-only treatment recipes (`RecipeReplay.plan`'s `PadSheet.read` branch -
COPY LAST TREATMENT replaying an `Era`/`Character`) don't share one shape:
`Treatments.apply` builds its recipe via `PadRecipe(fx = ..., treatment =
..., amount = ...).toJsonValue()` (`Treatments.kt:101`), so a `Character`
treatment recipe *does* carry `"recipe": 2` like any other `PadRecipe`;
`Eras.apply` writes its own literal `{"era", "amount"}` `JsonValue.Obj`
with no `"recipe"` key at all. Either way, `PadSheet.read` (`PadSheet.kt:310`)
never looks at that field - it reads `"treatment"`/`"era"`/`"amount"`
directly regardless of what version tag (or none) rides along, and
`Treatments.chain`/`Eras.process` (neither touched by U1-U6) rebuild the
`FxChain` fresh from the name every replay. So a stale recipe's version tag,
where one exists, is inert here, not ignored by a gap in gating - the whole
point of COPY LAST TREATMENT is that it re-derives the chain from the
current code, not from anything frozen in the recipe.

---

# U7 — MATCH: the one nobody can copy

**Depends on U1.** The moat feature, and the reason the synth belongs in this
app rather than being a worse Vital.

You captured a snare off a video. It is on A02. You need a kick that sits with
it — same room, same era, same tuning.

The machinery already exists:

- `Desample` (`:synth`) finds the nearest THUMP patch to a captured hit off a
  pre-rendered macro grid, and **tells you the distance** (`docs/DESAMPLE.md`).
- `Similar` provides the distance metric, `Classifier` the drum class, `Pitch`
  and `Scales` the key.

MATCH points that machinery at the preset library instead of a macro grid: given
the captured pads in a kit, rank presets by spectral and tonal fit, and propose
the three that sit best. **FILL KIT** is the same idea over the whole grid —
captured snare on A02, synthesised kick, hat and clap generated around it.

No standalone synth can do this, because no standalone synth knows what is on
your other fifteen pads. That is the argument for the whole feature.

---

# Phasing

| Phase | Ships | Depends on | Changes rendered audio? |
|---|---|---|---|
| U1 | ~392 presets, `Presets` dispatcher, preset listbox, six test classes | — | No — purely additive |
| U2 | SCRAMBLE near a preset, `temperature`, WILD control | U1 | No — new roll, old reachable at `temperature = 1` |
| U3 + U5 + U6 | Punch, `Dsp.Env`, filter saturation, 4× oversampling, `alias` flag | — | **Yes** — one `PadRecipe.VERSION = 2` bump, regenerate `testkit/` |
| U4 | Per-voice stereo width, FX rack stereo audit | U3+U5+U6 | Yes, for the voices that opt in |
| U7 | MATCH and FILL KIT over the preset library | U1 | No — selection, not synthesis |
| U8 | **shipped** — SAVE AS PRESET: the player's own presets in `presets.json` beside the kits, a YOURS strip under the factory row, promotion by roster line through SEND TO BENCH (`docs/WORKSHOP.md`, WS5) | U1 | No — the same `Patch` JSON, stored |

U1 and U2 ship alone, touch no rendered audio, and deliver most of the
perceived improvement. U3/U5/U6 are deliberately fused into **one** version bump
and **one** `testkit/` regeneration rather than three.

# U8 — SAVE AS PRESET

**Depends on U1. Shipped** (`docs/WORKSHOP.md`, WS5, which holds the full
account). U1 made a preset a named `Patch` and gave SYNTH a strip of them;
U8 lets the player put their own in it. SAVE PRESET ▸ on SYNTH names the
current macros and keeps them in one file at the shelf root, and the
strip grows a second row, YOURS, for the voice. The naming rule is U1's
own — uppercase, at most fourteen letters, unique within a voice — with
one addition: a factory name is refused, so a chip never means two
things. Saving under one of your own names replaces it in place.

The roster stays a code change. Nothing on the phone can add to the
tables above; SEND TO BENCH carries `presets.json` and renders each
preset as the `p(...)` line its engine's table is written in, exact
floats and all, and the six test classes judge it there. A promoted
preset either replaces a factory row or raises the count law on purpose.

# Non-goals

- **No new engines.** Eight is plenty; thirty voices with no presets is the
  problem, and a ninth engine would make it worse. Scoped to this upgrade,
  not permanent — revisited afterward as its own deliberate decision and
  shipped as SKIN (`SYNTH_ROADMAP.md`'s S6).
- **No patchbay.** Roadmap rule 1 stands — macros, never modular.
- **No piano keyboard UI.** Settled in `SYNTH_ROADMAP.md:147`; the 4×4 grid is
  the instrument.
- **No real-time engine.** Offline one-shot rendering is the structural
  advantage U6 spends. Keep it.
- **Not competing with desktop synths on depth.** U7 is the answer to "why not
  just use Vital", and depth is not.
