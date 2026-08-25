# The six features, planned to done

The ROI-ranked feature list, mapped onto concrete work. This is the
product-facing cut of the plan; [`APP_PLAN.md`](APP_PLAN.md) stays the
authoritative milestone view of the app build — work items below reference
its milestones rather than restating them.

**Owners.** `APP` = the desktop session building `:app` (Android SDK,
Compose, services). `CORE` = the cloud session (pure Kotlin/JVM modules —
everything testable without a device). `USER` = hardware checks on the
Live III. Sizes: S ≈ under a day, M ≈ 1–3 days, L ≈ a week-plus.

**The shape of the whole plan in one sentence:** every feature's backend is
done; the CORE column below is a short list of small items that shrink the
APP column further, the APP column is M0–M5 wearing feature names, and the
USER column is one card session.

---

## F1 — The capture-to-export shell (the MVP loop)

**Done:** the entire logic layer. `:shell` holds the trim transport
(`TapeDeckModel` + `PeaksPyramid`), grid state (`KitBuilderModel`), export
flow (`ExportWizardModel` over the shared `Exporters`), pad voices
(`VoiceAllocator`), scheme/type/layout/motion tables, and the personality
gates — all tested. Capture buffer, cleanup DSP, and every writer were
already done and hardware-verified for drums.

**Remaining (all APP; = APP_PLAN M0–M5):**

| # | Work | Size | Exit test |
|---|---|---|---|
| F1.1 | M0 walking skeleton — theme, nav, `KitStore` shelf | M | browse kits, tap pads, hear WAVs, flip schemes |
| F1.2 | M1 capture — service, MediaProjection, bubble, mic, silence detection, QS tile | L | snip YouTube from inside YouTube; snip the room; share a video in |
| F1.3 | M2 tape deck — bind Compose to `TapeDeckModel`/`PeaksPyramid` | M | a YouTube snip becomes a clean one-shot, cut on the hit |
| F1.4 | M4 play mode — Oboe, bind `VoiceAllocator` | M | finger drumming feels tight on a mid-range phone |
| F1.5 | M5 export wizard — SAF, bind `ExportWizardModel` | M | the card writes; the Live III plays it |

M3 is feature F2 below. Risks and their standing: APP_PLAN's table.

## F2 — One-tap Instant Kit

**Done:** the full pipeline (`Transients` → `Chopper` → `Classifier` →
`AutoPlace` → `KitAssembler`), the review screen's whole state machine
(`ChopReviewModel`: tap-to-cycle chips, NOT SURE, placement preview,
SEND TO GRID), and the first real-audio proof (the CLI chopped the factory
groove: kick→A01, hats choking, 92 BPM detected).

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F2.1 | CHOP screen — bind Compose to `ChopReviewModel`, defrag-grid progress gag (= APP_PLAN M3) | APP | M | one captured bar → playable, sensibly-laid-out kit in under a minute |
| F2.2 | The one tap — INSTANT KIT action on a fresh capture: chop with defaults straight into review | APP | S | capture 8 s of a break, tap once, play the kit |
| F2.3 | ✓ done: calibration harness — a labeled-corpus test: WAVs + expected classes under `reference/calibration/`, a report of confusion + per-threshold sensitivity; tune `Classifier` against it | CORE | S | thresholds justified by real captures, not synthetic renders |
| F2.4 | Calibration corpus — a dozen real captured hits (phone captures, not renders), labeled by ear | USER | S | F2.3 has something true to chew on |

`snipsnap classify` is the collection tool for F2.4 — it prints the
features next to every verdict.

## F3 — Share-sheet import

**Done:** the file half. `WavReader` (any PCM/float rate/depth) + the sinc
`Resampler` are the working import path — the CLI is this feature running
on desktop today. The risk table's opt-out mitigation depends on this.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F3.1 | Intent filters + receive activity → trim screen | APP | S | shared audio file lands in the tape deck |
| F3.2 | Video demux — `MediaExtractor`/Media3 → float PCM → `Snip` → `Resampler` | APP | M | shared MP4's audio lands in the tape deck |
| F3.3 | ✓ done: demux conformance fixtures — tiny known-content WAV fixtures + a contract test the app's decode output must pass (rate, channels, sample accuracy) | CORE | S | app-side decode verified against ground truth without an SDK |
| F3.4 | Onboarding copy for opt-out apps (silence detection → screen-recorder path) — copy exists in `Copy`; wire it | APP | S | blocked capture shows the honest fallback, in voice |

## F4 — Synth starter kits

**Done:** the most finished feature. Seven engines + FX, `SynthKits` /
`ThumpKits` / `Shuffle` are **main-source** (the app can call them
directly), recipes rebuild kits bit-for-bit from `kit.json`, SCRAMBLE is
bounded macro rolls, and two generated kits are hardware-verified.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F4.1 | ✓ done: starter-kit registry — `StarterKits` in `:shell`: name → builder → blurb → seed policy, wrapping the main-source builders so the FRESH TAPE menu is data-driven | CORE | S | registry renders every kit through assemble→preflight in a test |
| F4.2 | NEW KIT menu — pick a starter, reroll seed, land on the grid (needs M0 only) | APP | S | first-run user has a playable kit in 30 s, empty grid never shows |
| F4.3 | SYNTH screen — macro panels over `Patches`, SCRAMBLE, RENDER TO PAD (= the M5 synth half) | APP | M | prototype's Thump Lab behaviour, on device |

Also yields rights-clean Play Store demo content for free.

## F5 — In-key capture

**Done:** `Pitch`/`Scales`/`Tuner`/`InKey` — detection, nearest-in-key
retune via the MPC's own tune fields, refusal to touch unpitched material.
Shipping today as the CLI's `--key`.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F5.1 | ✓ done: key grammar promoted — `KeySpec` (Am / F#m / "Eb major" / Dminpent parsing) moved from `:cli` into `:audio`, beside `Scales` where it belongs; CLI delegates | CORE | S | one parser, two consumers, same tests |
| F5.2 | ✓ done: kit key field — optional `key` on `Kit`/`kit.json` so the choice persists with the folder | CORE | S | round-trips through `KitStore`; absent = no key, old kits unaffected |
| F5.3 | Key picker + pad tune readout — kit-level key in the kit screen; IN KEY as a kit action; optional retune-on-assign for TONAL pads | APP | S | set Am, drop a captured bass note, it lands in key; the kick is untouched |

## F6 — One-file kit sharing (.xpn)

**Done:** `XpnPackager` writes the real-archive layout deterministically;
`xpn` is in the `Exporters` fan-out; `SnipSnap_Factory.xpn` is built and
waiting.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| F6.1 | Hardware import check — does the Live III's expansion import accept `SnipSnap_Factory.xpn`? (Part 2 queue, item 4) | USER | S | yes/no + exact error text if no |
| F6.2 | ✓ done: `XpnImporter` — read an `.xpn` back into a kit folder (unzip, parse the program, resolve bare sample names); free CLI `import` command; the receive half of sharing | CORE | S | pack → import → re-export round-trips; a foreign commercial `.xpn` imports |
| F6.3 | Share/receive flow — ACTION_SEND a kit as `.xpn`; intent-filter receives one → `XpnImporter` → the shelf | APP | S | kit → messenger → friend's phone → their shelf → their MPC |

F6.2 doesn't wait on F6.1: importing serves the app-to-app share loop even
if the hardware importer says no (folders remain the hardware path).

---

# Wave 2 — the next six, plus a bench of medium bets

Brainstormed after wave 1's CORE column landed, ranked the same way:
by how much backend already exists. Tier 1 items are ≥90% built —
each is a thin construction over tested modules. The same owner codes
apply.

## W1 — Steal the rhythm (groove capture)

The chop pipeline already knows where every hit was (`Slice.sourceFrame`),
what it is, where it landed, and the tempo. That is a pattern: reconstruct
the source performance as an `Mpc3Clip` — hardware-verified, already
carried by `.xtd` and `.xpj` — so a chopped break arrives on the MPC
**already sequenced in its own rhythm**. Likely the app's single best demo
moment, above Instant Kit.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W1.1 | ✓ done: `CapturedGroove` (`:kit`) — hits + classes + placement + BPM → `Mpc3Clip`: quantize source frames to pulses at the detected tempo (straight or unquantized as captured — keep both), notes on the pads the slices landed on | CORE | S | reconstructed clip's note times match detected onsets within a 16th's tolerance; note pads match placement |
| W1.2 | ✓ done: wire-through — `chop --groove` embeds the clip in `xtd`/`xpj` exports; `ChopReviewModel.grooveClip()` for the app | CORE | S | CLI chop of the factory groove render exports a clip that mirrors the render's own pattern |
| W1.3 | Bench check — does the reconstructed clip *feel* like the break on hardware | USER | S | play the clip beside the source capture; rhythm matches |

## W2 — One note → a playable instrument

MPC keygroups pitch the sample themselves, so one captured tonal snip +
`Tuner`'s root detection + the keygroup writers (both generations,
corpus-corrected) = sample a single note of anything, play it
chromatically. Near-zero new code; in-key capture's payoff squared.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W2.1 | ✓ done: `OneNote` builder — snip → detected root → one-zone `KeygroupProgram` (full key range), refused with a reason when no confident pitch | CORE | S | known-pitch tone → program with right root; noise → clear refusal |
| W2.2 | ✓ done: CLI `keys <note.wav>` — one-note instrument to `.xty` + `.xpm` twins | CORE | S | artifacts land, detected root printed |
| W2.3 | App action — MAKE INSTRUMENT on a tonal pad (after M3) | APP | S | long-press a tonal pad → instrument on the shelf |
| W2.4 | Bench — plays in tune chromatically from one sample | USER | S | ears |

## W3 — The reverse loop: MPC → phone → MPC

We already *read* the native format (`Acvs`, `Mpc3Project`, checked
against 59 real projects). An `Mpc3Importer` mirroring `XpnImporter`
(`.xtd` + `_[TrackData]/` → kit folder) turns the app into a round-trip
editor for kits the MPC itself saved — the biggest strategic item on
either wave: it doubles what the product is.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W3.1 | ✓ done: `Mpc3Importer` — standalone drum `.xtd` + data folder → kit folder (levels, pans, tunes, mute groups, velocity layers, colours where present); missing samples refused by name; keygroup tracks refused with a reason | CORE | M | our export → import round-trips; a commercial `.xtd` from `reference/golden/` parses (sample-missing errors listed, not crashed) |
| W3.2 | ✓ done: CLI `import` learns `.xtd` — dispatch by magic bytes, not extension | CORE | S | both archive kinds import through one command |
| W3.3 | App receive/browse — open a `.xtd` from storage onto the shelf (after M0) | APP | S | MPC-saved kit editable on the phone |
| W3.4 | The Live III firmware save (Part 2 item 6) becomes this feature's fixture as well as the corpus's | USER | S | the round-trip claim tested against firmware's own output |

## W4 — Evil-twin bank

`Shuffle.withRemixBank` re-treats any arranged pads through seeded FX and
records fx-only recipes — it never cared whether the audio was synthesized.
One action: bank B becomes your kit's evil twins.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W4.1 | ✓ done: `KitBuilderModel.remixBankB(seed)` — bank A read back as arranged pads, `withRemixBank`, twins written to slots 17–32 with recipes; reroll replaces | CORE | S | any kit gains a bank B; same seed reproduces; recipes recorded |
| W4.2 | ✓ done: CLI `remix <kit-dir> [--seed N]` | CORE | S | works on a chopped kit |
| W4.3 | App action — EVIL TWINS in the kit menu (after M3) | APP | S | one tap, bank B lights up |

## W5 — Ghost notes from one capture

`Velocity.soften` renders darker soft variants (it is how the harp's soft
layers exist) and the velocity-zone pipeline is done end to end. Captured
one-shots get real ghost notes, not just quieter ones.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W5.1 | ✓ done: `KitBuilderModel.addGhostLayers(slot)` — soften into 1–2 soft zones under the main sample; reversible (clear layers) | CORE | S | zones valid, soft renders measure darker (centroid), pad reverts cleanly |
| W5.2 | ✓ done: CLI `chop --ghosts` — layers on every one-shot pad | CORE | S | chopped kit exports with velocity zones |
| W5.3 | App toggle on the pad sheet (after M3) | APP | S | quiet hits sound soft on hardware |

## W6 — BPM + key metadata everywhere

Trivial and overdue (a CONCEPT.md v2 item). Also buys time-stretch for
free: the MPC warps loops itself when the tempo metadata is right.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| W6.1 | ✓ done: `Kit.tempoBpm` (optional, like `key`) — stamped by chop when confidence clears the bar; `.xpj` export uses it for the project tempo | CORE | S | round-trips; project master tempo = detected tempo |
| W6.2 | ✓ done: loop-class sample names carry the tempo (`Loop_92bpm_01`); kit name suggestion gains bpm/key when known | CORE | S | names match detection; off when detection isn't confident |

## Wave-2 bench of medium bets (planned, not queued)

| Item | What | Owner | Size | Note |
|---|---|---|---|---|
| W7 seamless sustain loops | the organ's whole-period loop cut generalized to captured notes — needs crossfade loops for vibrato/noise | CORE | M | hold a captured string, it sings forever; hardest DSP of the wave, do last |
| W8 melodic chop | grid-chop a phrase, `Pitch` each slice, lay out low→high on the SCALE layout | CORE | S–M | a vocal run becomes an instrument-ish kit |
| W9 takes + the 30-day bin | numbered `kit.json` takes on save; cleared samples to a bin ("THE BIN KEEPS IT 30 DAYS" is already in the copy) | CORE + APP | S | trust feature; model in `:shell` |
| W10 teach-the-machine | chip overrides logged as **feature vectors + labels only** (never audio — rights-clean); calibration harness ingests them | CORE + APP | S+S | ordinary use becomes classifier training data; needs a consent switch |
| W11 one-file backup | every kit as `.xpn` in one archive; restore via `XpnImporter` | CORE | S | retention insurance |
| W12 pad mini-waveforms | `PeaksPyramid` makes them free to draw | APP | S | perceived-polish per effort champion |

**Rejected, with reasons:** stem separation (heavy ML, off-brand for an
honest tool); our own time-stretch (the MPC warps better — W6 ships the
metadata instead); cloud kit library (rights, servers, moderation — the
growth loop stays peer-to-peer `.xpn`).

---

# Wave 3 — the bench comes off the bench

Wave 2's medium bets promoted to planned work, plus two follow-ons that
waves 1–2 opened up. Same ranking rule; X6 stays last because it's the
wave's only real DSP.

## X1 — Melodic chop (was W8)

Grid-chop a phrase, `Pitch` each slice, lay the slices out **low → high**
so the pads play like an instrument — a vocal run or a bass line becomes
something you can perform.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X1.1 | ✓ done: melodic placement in `ChopReviewModel` — pitched slices sorted ascending onto the pads, unpitched appended in capture order; per-row pitch cached | CORE | S | out-of-order tones land in ascending pad order |
| X1.2 | ✓ done: CLI `chop --melodic` | CORE | S | a scrambled scale chops into a playable run |
| X1.3 | App toggle on the chop screen (after M3) | APP | S | MELODIC next to the classic layout |

## X2 — Takes + the 30-day bin (was W9)

Kit-is-a-folder makes history nearly free. Every save archives the
previous `kit.json` as a take; cleared samples go to a bin instead of
oblivion ("EJECTED. THE BIN KEEPS IT 30 DAYS" has been in the copy since
day one — now it's true).

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X2.1 | ✓ done: takes — `save()` archives the outgoing `kit.json` under `.takes/`, capped and rotated; `takes()` lists, `restoreTake(n)` rolls back | CORE | S | edit → save → restore → the earlier kit is back |
| X2.2 | ✓ done: the bin — deletes move to `.bin/` stamped with when; `binContents()`, `purgeBin(olderThanDays = 30)`, `emptyBin()` | CORE | S | a cleared pad's WAV is recoverable for 30 days |
| X2.3 | Takes/bin UI (after M0) | APP | S | the copy's promise, visible |

## X3 — One-file backup (was W11)

Every kit as an `.xpn` inside one archive; restore feeds them back
through `XpnImporter`. Retention insurance and the "new phone" story.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X3.1 | ✓ done: `KitBackup` (`:kit`) — backup(kitsRoot) → one zip of per-kit `.xpn`s (preflight-blocked kits skipped and named); restore(zip) → kit folders | CORE | S | backup → wipe → restore round-trips every clean kit |
| X3.2 | ✓ done: CLI `backup` / `restore` | CORE | S | works on a folder of chopped kits |
| X3.3 | App share/backup action (after M0) | APP | S | one file leaves the phone with everything on it |

## X4 — Teach the machine, data path (was W10's CORE half)

Every chip override is a labeled example. Log **feature vectors + labels
only** (never audio — rights-clean by construction) and let the
calibration harness eat them.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X4.1 | ✓ done: split `Classifier` — `classify(Features)` beside `classify(Snip)`, so a feature vector is testable without its audio | CORE | S | both paths agree on every corpus render |
| X4.2 | ✓ done: `TeachLog` (`:shell`) — jsonl of {features, label} from `ChopReviewModel`'s overridden rows; reader for the harness side | CORE | S | overrides round-trip; a log line re-classifies |
| X4.3 | ✓ done: harness ingestion — overrides.jsonl in `reference/calibration/` scored alongside the WAVs | CORE | S | logged corrections show up in the confusion report |
| X4.4 | Consent switch + wiring in the app (after M3) | APP | S | off by default; nothing leaves the device either way |

## X5 — Multisample keys (new)

`keys` grows from one note to several: each pitched capture becomes a
zone at its detected root, zones tiling at the midpoints — a real
multisampled instrument from a handful of notes.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X5.1 | ✓ done: `OneNote.multiProgram` — roots detected and sorted, zones tile without gaps, duplicate roots resolved, one bad note refuses by name | CORE | S | three tones → three tiled zones, roots right |
| X5.2 | ✓ done: CLI `keys a.wav b.wav c.wav` | CORE | S | the multisample lands dual-generation |

## X6 — Sustain loops on captured notes (was W7)

The organ's whole-period loop cut, generalized to real audio: find a
loop of whole periods in the sustain, trim the sample to end exactly on
the loop boundary (both formats' idiom loops to sample end), and bake a
short crossfade when the raw seam isn't clean enough. Hold a captured
string and it sings forever.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X6.1 | ✓ done: `LoopCut` (`:audio`) — pitch-tracked whole-period loop search in the sustain region, seam scored by periodicity, crossfade baked when needed; unpitched/too-short refused | CORE | M | seam periodicity error under 1% of signal energy on a vibrato-laden tone; noise refused |
| X6.2 | ✓ done: `keys --loop` — one-note and multisample instruments gain sustain loops (both formats' loop idioms already ship) | CORE | S | the instrument's layers carry loop points |
| X6.3 | Bench — a held pad sustains with no audible seam | USER | S | ears, the only judge that counts |

## X7 — Whole-project import (new)

W3's importer said "project import is a later step"; this is the step.
Every drum track inside an `.xpj` becomes its own kit folder — a whole
MPC session's kits, editable on the phone.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| X7.1 | ✓ done: `Mpc3Importer.importProject` — each drum-type track parsed by the same instrument walk, samples from `_[ProjectData]/`, names unique-ified; keygroup tracks skipped and named | CORE | S–M | our Session-style export round-trips into N kits; commercial `.xpj`s parse |
| X7.2 | ✓ done: CLI `import` handles `.xpj` — imports every drum kit inside, reports each | CORE | S | one command, whole session |

---

# Wave 4 — closing the loops we half-opened

Three waves in, the ranking question shifts from "what's nearly free" to
"what closes an open loop." Y1 fixes a leak in the product's core promise;
Y2–Y3 multiply the hardware payoff of things already built; Y4–Y5 take the
FX rack and the kit folder to their last unreached surfaces; Y6 turns test
machinery into the bench tool the format work still needs.

## Y1 — Total recall: kits remember their beat and their source

Two holes in "the folder is the kit": `chop --groove` embeds the rhythm at
export time only (chop today, export tomorrow — groove gone), and both
importers **drop the clips** they find (an MPC kit round-trips minus its
patterns). Also: chopped pads carry no provenance.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y1.1 | ✓ done: `groove.json` sidecar — the captured clip persisted beside `kit.json` (notes, bars, tempo); written by chop, read by `Exporters`, auto-embedded in every native export | CORE | S | chop → export *later* → the groove is still there |
| Y1.2 | ✓ done: Importers keep clips — `Mpc3Importer` reads `sharedClipMap` entries back into `groove.json` | CORE | S | MPC kit with patterns round-trips with its patterns |
| Y1.3 | ✓ done: Chop provenance — per-pad `source` gains file, sourceFrame, lengthFrames | CORE | S | every chopped pad says where it came from |

## Y2 — Pattern variations: four grooves per kit

`sharedClipMap` is a **list** — commercial tracks carry 4 entries, ours 1
(hardware-accepted). Generate mechanical variations of the captured groove
— as-captured, quantized-tight, half-time, sparse — and ship clips A–D:
load a chopped break, flip between four ready patterns.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y2.1 | ✓ done: `GrooveVariations` — pure `Mpc3Note`-list transforms (quantize, half-time, thin, velocity-flatten), named clips | CORE | S | each variant provably derived: note counts/times follow the rule |
| Y2.2 | ✓ done: Writer takes a clip **list** (≤4), corpus-guarded; chop `--groove` ships the four | CORE | S | key-path guard still green; payload carries 4 clips |
| Y2.3 | Bench — the Live III's clip list shows and plays all four | USER | S | flip patterns on hardware |

## Y3 — The session builder: `snipsnap project`

`SessionProjectGenerator` builds the fixed factory session as test code.
Generalized: N kit folders + M instrument packages → one `.xpj`, kits on
tracks with their grooves, instruments beside them, mixer wired.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y3.1 | ✓ done: `SessionBuilder` (`:kit`) — stage kit folders + `.xty` instrument packages into `_[ProjectData]/`, hand `Mpc3ProjectWriter` the track list; grooves from `groove.json` ride onto the sequence | CORE | S–M | two kits + an instrument → one `.xpj` our reader accepts with the right track types |
| Y3.2 | ✓ done: CLI `project <kit-dir>... [--keys pkg...] [--name]` | CORE | S | one command, whole session on the card |
| Y3.3 | App: SESSION export in the wizard (after M5's SAF) | APP | S | the wizard's biggest format, one tap |

## Y4 — Pad treatments: the FX rack pointed at one pad

Remix bank B proves the whole path (FX on captured audio, fx-only recipes,
re-treatability); there's just no way to *crush the snare*. Named
treatments over the existing chains, recorded as recipes, reversible via
the bin.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y4.1 | ✓ done: `KitBuilderModel.treatPad(slot, treatment, amount, seed)` — the Shuffle treatment table exposed singly + amount-scaled; original WAV binned, recipe recorded; `untreatPad` restores | CORE | S | treat → audibly different, recipe present; untreat → original bytes back from the bin |
| Y4.2 | ✓ done: CLI `treat <kit-dir> <pad> <treatment> [--amount] [--seed]` | CORE | S | crush A02 from the terminal |
| Y4.3 | App: treatment row on the pad sheet (after M3) | APP | S | one tap per character |

## Y5 — The preview renderer: every kit listenable before loadable

The kit's WAVs plus its groove is an offline sampler render away from a
demo. Every expansion `[Previews]/`, every `.xpn`, and the app's "share as
audio" story get *the kit playing its own beat*.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y5.1 | ✓ done: `KitPreview` (`:kit`) — mix pad WAVs at clip note times (levels, choke honoured); groove absent → a two-bar default pattern over the core classes | CORE | S | rendered preview is non-silent, right length, deterministic |
| Y5.2 | ✓ done: Wire-through — expansion + `.xpn` previews use it when a groove exists; CLI `export` gains `--preview`; app share-as-audio hook | CORE | S | `[Previews]/<Kit>.xpm.wav` is the kit's own beat |
| Y5.3 | Bench — does the expansion browser play it (the open WAV-vs-MP3 question) | USER | S | noted either way |

## Y6 — `snipsnap diff`: the corpus guard as a bench tool

The key-path machinery that keeps the writers honest lives in test code.
As a CLI verb — structured diff of two MPC files, either generation — it
becomes the instrument for the firmware-save job and the permanent
"why won't this file load" tool.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Y6.1 | ✓ done: `MpcDiff` (`:mpc3`) — key paths only-in-A / only-in-B / value-differs (schema-aware: arrays as `[*]`, sentinel-tolerant), for ACVS payloads and MPC 2 XML alike | CORE | S–M | our export vs a golden file reproduces the known documented deltas |
| Y6.2 | ✓ done: CLI `diff <a> <b> [--values]` — grouped, readable, exit 1 when different | CORE | S | `diff ours.xtd firmware.xtd` is the whole liveiii-36 dissection |
| Y6.3 | The firmware save lands → run Y6.2, promote surprises into the corpus guards | CORE+USER | S | the standing job, now one command |

**Below the line (wave 5 candidates):** kit merge into banks A/B, batch
chop over a folder, auto slice-count. Standing rejects unchanged.

---

## Sequence

```
CORE wave 1: ✓ all six landed (2026-08-24) — XpnImporter, KeySpec
  promotion, Kit.key, StarterKits registry, decode fixtures + contract,
  calibration harness. F2.3's harness idles until F2.4's corpus fills.

CORE wave 2: ✓ all six landed (2026-08-24) — groove capture,
  Mpc3Importer, one-note instrument, twin bank, ghost layers,
  bpm metadata

CORE wave 3: ✓ all seven landed (2026-08-24) — melodic chop,
  multisample keys, takes + the 30-day bin, one-file backup, the
  teach-the-machine data path, whole-project import, sustain loops.

CORE wave 4 (in order — Y1 first, it's a leak, not a feature):
  Y1 total recall → Y2 pattern variations → Y3 session builder →
  Y4 pad treatments → Y5 preview renderer → Y6 diff tool
  Remaining on the bench: W12 pad waveforms (APP-only polish) ·
  kit merge · batch chop · auto slice-count

APP (in milestone order; feature items slot in where their parent lands):
  M0 (F1.1) → +F4.2 new-kit menu · +W3.3 open-.xtd
  M1 (F1.2) → +F3.1/F3.2/F3.4 import
  M2 (F1.3)
  M3 (F2.1) → +F2.2 one-tap · +F5.3 key picker · +W2.3/W4.3/W5.3 pad actions
  M4 (F1.4)
  M5 (F1.5 + F4.3) → +F6.3 share flow
  bench: W12 pad waveforms whenever polish is the mood

USER (one card session, value order — ideally before M5):
  Session .xpj → native keys + instruments → MPC 2 keys →
  F6.1 .xpn import + expansion tile → velocity/bank B →
  the Live III drum-program save (now double-duty: corpus + W3.4's
  round-trip fixture) → W1.3 groove-clip feel · W2.4 one-note tuning
  … and F2.4's dozen labeled captures whenever the phone can capture
```

Dependency truths: nothing in the CORE column blocks anything; M0 gates
every APP feature item; F6.1 gates only the *claim* that `.xpn` loads on
hardware, not the share loop; the product ships when M5's exit test passes.
