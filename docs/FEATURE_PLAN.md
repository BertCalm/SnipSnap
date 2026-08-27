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

# Wave 5 — product polish: musical, workflow, interop

Four waves of format archaeology bought a pipeline that speaks every MPC
dialect; this wave makes it *nicer to use*. Two musical items (swing, auto
slice-count), two workflow (batch chop, kit merge), two interop/delight
(MIDI bridge, expansion tile art). All six are small, none touches the
corpus-guarded containers beyond machinery that already exists.

Build order puts the art renderer **first** — its look is a taste call,
so the prototyping loop (Z6.2) starts early and runs beside the rest of
the wave instead of gating the end of it.

## Z1 — Swing: the MPC's soul

`GrooveVariations` ships captured/tight/half/sparse and not the single
most famous thing an MPC does. Swing lives at quantize, the way the
hardware does it: snap to the 16th grid, then push every even ("and")
16th late by the swing amount — 50% is straight, 66% is triplet feel,
the classic MPC range is 54–75. A seeded humanize jitter rides along.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z1.1 | ✓ done: `GrooveVariations.swing(clip, percent)` — quantize-then-push on even 16ths, velocities untouched; `humanize(clip, amount, seed)` — bounded seeded jitter on times; when a swing is asked for, the "Tight" slot becomes the swung clip (four-slot budget respected) | CORE | S | even 16ths land late by exactly `(pct−50)/50 × 240` pulses, odd 16ths and velocities untouched; same seed, same jitter; 50% is a no-op |
| Z1.2 | ✓ done: CLI `chop --swing PCT` (with `--groove`); range-checked 50..75 | CORE | S | the exported clip list carries the swung variant; the payload guard stays green |

## Z2 — MIDI bridge: grooves that leave the ecosystem

`groove.json` ↔ Standard MIDI File. Export and every DAW opens the
captured rhythm — and the MPC's own browser plays `.mid` too; import and
beats programmed anywhere become kit grooves. SMF is ~200 lines of pure
JVM, no deps, and our clips are already 960 PPQ — MIDI's own favourite
resolution.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z2.1 | ✓ done: `MidiGroove` (`:kit`) — SMF format-0 writer + reader: clip notes ↔ note-on/off pairs (velocity 0..1 ↔ 0..127), tempo meta event from the kit's BPM, 960-division header; unreadable files refused with a reason | CORE | S–M | write → read round-trips every note, time, length, velocity; a hand-built DAW-style fixture `.mid` imports correctly |
| Z2.2 | ✓ done: CLI wire — `export --export mid` writes `<Kit> <Variant>.mid` per stored groove; `import <file.mid> --into <kit-dir>` lands a DAW beat as the kit's groove | CORE | S | chop --groove → export mid → files a DAW opens; import a `.mid` → native exports carry it |

## Z3 — Auto slice-count: stop making the user guess

`--slices 16` is a guess we make the user confirm. The onset-strength
ranking inside `Chopper` already knows how strong every hit is: find the
knee in that curve and keep what's above it.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z3.1 | ✓ done: `Chopper` auto mode — slice count chosen at the largest relative drop in sorted onset strength (bounded 2..64); CLI default becomes auto when `--slices`/`--grid` are absent, explicit values behave exactly as today | CORE | S | an 8-hit synthetic break auto-chops to 8; a dense roll stays bounded; `--slices N` output is byte-identical to before |

## Z4 — Batch chop: the crate-digging verb

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z4.1 | ✓ done: CLI `chop-all <folder>` — every audio file in the folder through the chop pipeline with shared options; per-file failures named and non-fatal; one summary table (file → kit → pads → tempo); exit 1 only when nothing succeeded | CORE | S | a mixed folder (good WAVs + one broken file) yields kits for the good ones and names the bad one; also the calibration corpus mass-run tool |

## Z5 — Kit merge: bank B, earned not invented

The complement of remix: remix invents a bank B, merge earns one from
another kit.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z5.1 | ✓ done: `KitMerge` (`:kit`) — B's bank-A pads onto the target's slots 17–32: samples copied under re-prefixed stems, colours/mute groups/velocity layers/recipes carried, A's groove and key kept; refuses when A's bank B is occupied (unless replacing is asked for); CLI `merge <a> <b> [--out DIR] [--replace]` | CORE | S | merged kit preflights clean; both sources untouched; B's audio byte-identical under its new stems |

## Z6 — Expansion tile art: the blank square on the hardware browser

`ExpansionWriter` has carried an `artworkPng` parameter since wave 0 and
nothing fills it — every expansion we export is a blank tile on the Live
III. Procedural cover art: deterministic, seeded, rendered headless via
`java.awt` — no new dependencies. **The look is a taste call, so the
renderer is parameterized and the prototyping loop is the feature:** a
standalone CLI verb regenerates a PNG in one command, you iterate on
style/scheme/seed, and the winning direction becomes the export default.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| Z6.1 | ✓ done: `KitArt` (`:shell`) — cover renderer: kit name + waveform/pad-grid motifs drawn from the kit's own samples and classes, palette from `Schemes`, style + seed + size parameters; deterministic bytes for identical inputs; CLI `art <kit-dir> [--style NAME] [--scheme NAME] [--seed N] [--size PX] [--out FILE]` for the iteration loop | CORE | S–M | same inputs → identical PNG; valid dimensions; every style renders every starter kit without error |
| Z6.2 | ✓ done: prototype loop — **verdict (2026-08-25): waveform and rings won**; waveform is the export default, rings the runner-up one `--art rings` away | USER+CORE | S | the default style was chosen by eye |
| Z6.3 | ✓ done: wire-through — waveform tiles render by default into expansion/`.xpn` exports (CLI and export wizard alike; artwork plumbed through `Exporters`); `--art` picks, `--no-art` opts out | CORE | S | exported expansion carries the tile; the Live III browser shows it (bench) |

**Below the line (wave 6 candidates):** multi-sequence projects — the
four groove variations as verse/chorus sequences in one `.xpj` (waits on
the Y2.3 bench verdict); vintage 12-bit SP mode (treat's crush covers
most of it); CLI audio playback (the preview WAV already auditions
everywhere). Standing rejects unchanged.

---

# Wave 6 — kits become music

The format pipeline is done and polished; this wave's lens shifts to
arrangement, feel, key, and collections. One corpus-archaeology piece
(AA1 — it leads, the probe gates its writer), two renders (AA2, AA5),
three musical brains (AA3, AA4, AA6); everything after AA1 is
independent. Build order: AA1 → AA6 → AA3 → AA4 → AA2 → AA5.

## AA1 — Multi-sequence projects: verse/chorus on hardware

Four groove variations ship per kit, but a `.xpj` carries one sequence —
the variations ride the clip list the bench hasn't confirmed. Sequences
are the corpus-standard mechanism (every commercial project carries
several, named), and our own corpus rule already says grooves ride the
sequence's `trackClipMaps`. Promote the variations to named sequences:
the hardware's sequence switcher *is* the pattern flip.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA1.1 | ✓ done: Corpus probe — how commercial `.xpj`s encode multiple sequences (the sequence list's keys, names, per-sequence `trackClipMaps`, lengths); findings recorded in docs/MPC3_FORMAT.md | CORE | S | probe notes + the key paths, before any writer code |
| AA1.2 | ✓ done: `Mpc3ProjectWriter` multi-sequence — a sequence list (name + per-track clips each); `SessionBuilder` ships the four variations as four named sequences; corpus-guarded like everything else | CORE | M | key-path guard green; our reader sees four sequences; `diff` vs a golden project shows only documented deltas |
| AA1.3 | Bench — load the project, flip sequences on the Live III | USER | S | verse/chorus flips on hardware |

## AA2 — Session mixdown: the whole beat as one WAV

`KitPreview` renders one kit playing its beat; `SessionBuilder` stages
several kits with grooves. Connect them: the entire session mixed to one
stereo WAV — the demo beside every `.xpj`, the app's share-as-audio for
sessions.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA2.1 | ✓ done: `SessionMixdown` (`:kit`) — each kit's groove rendered via `KitPreview` at the session tempo, summed, peak-limited; CLI `project --mixdown` writes `<Name>.wav` beside the `.xpj` | CORE | S | non-silent, deterministic, as long as the longest kit render; a solo-kit session mixes to that kit's own preview |

## AA3 — Key guess: the capture names its own key

`--key Am` assumes the user knows the key. Per-slice pitch already
exists; a chroma histogram over the tonal slices scored against the
scale table picks root + scale, with a confidence gate and an honest
refusal when nothing tonal is there.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA3.1 | ✓ done: `KeyGuess` (`:audio`) — pitch-class histogram from pitched slices → best (root, scale) + confidence; drums-only material refused with a reason | CORE | S–M | a scrambled A-minor arpeggio → Am; DrumSynth kit → refusal |
| AA3.2 | ✓ done: Wire — chop stamps `kit.key` when confidence clears the bar (and says so); `--key auto` asks for it explicitly and errors when the guess can't clear | CORE | S | chop of tonal material lands a keyed kit; `--key auto` retunes without naming a key |

## AA4 — Tempo-fit: loops repitched, SP-style

A 95 BPM loop in a 92 BPM kit drifts. The classic sampler answer is
repitch — resample by the tempo ratio, pitch rides along, zero
artifacts, and *the* revered lo-fi move. The sinc `Resampler` already
does the work.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA4.1 | ✓ done: `TempoFit` (`:audio`) — repitch a snip by `from/to` tempo ratio; chop `--fit-tempo BPM` applies it to LOOP-class slices (detected tempo → target), loop stem names restamped to the new tempo | CORE | S | a 95→92 fit scales duration by exactly the ratio; the name says `92bpm`; one-shots untouched |

## AA5 — Pack builder: N kits, one expansion

Our expansion carries one kit; commercial packs carry a catalog under
one tile. Turn chop-all's output into a shippable product.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA5.1 | ✓ done: `PackBuilder` (`:kit`) — many kit folders → one expansion (every program under `Programs/`, samples arranged the way real multi-program packs do it, per-kit previews, one manifest/XML); pack tile = the title over the kits' combined waveform (small `KitArt` extension); CLI `pack <kit...> --title NAME [--out] [--xpn]` | CORE | S–M | a 3-kit pack has every program loadable and round-trips through `import`; blocked kits skipped and named, backup-style |

## AA6 — Groove transfer: steal the feel, not the notes

The MPC's own legendary feature (groove templates): extract the
timing-and-velocity deviations from any clip — a captured break, an
imported `.mid` — and apply them to another kit's quantized grooves.
Pure note-list math; composes with swing and humanize.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| AA6.1 | ✓ done: `GrooveFeel` (`:kit`) — `extract(clip)`: per-16th-position timing offsets + velocity shape; `apply(template, clip)`: quantized notes take the template's pocket; CLI `feel <kit-dir> --from <kit-dir or .mid>` rewrites the kit's variations with the donor's feel | CORE | S | extract-from-swung applied to straight reproduces the swing offsets; velocities follow the donor's shape; same donor, same result |

**Below the line (wave 7 candidates):** auto velocity-stacking from
similar slices (needs the calibration corpus's similarity ground truth);
kit doctor auto-fix (Preflight's honest refusal is the feature until
proven otherwise); phase-vocoder time-stretch (repitch is cheaper and
more authentic). Standing rejects unchanged.

---

# Wave BB — hardening: trust nothing that crossed the card

Six feature waves grew the attack surface: SnipSnap now parses five
formats people feed it from *outside* — `.xpn` zips, ACVS gzips, `.mid`,
WAVs, `kit.json` — and writes files whose names come from inside those
files. The contract this wave enforces: **any bytes in → a typed refusal
or a valid result out, quickly; never a traversal, an OOM, a hang, a
stack overflow, or a raw stack trace.** BB1 is a real vulnerability and
leads; the rest is depth behind it. This wave adds no user-facing
features — it makes the ones we shipped safe to point at a stranger's
file.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| BB1 | ✓ done: path-traversal hardening — a shared `SafePath` centralises the defense that was scattered and implicit. `basename()` flattens any import-derived sample name (both importers already stripped paths ad hoc — including the corpus's legit `../Samples/Kick.wav` shared-pool references, so flatten, don't refuse); `child(dir, name)` is the *enforced* invariant that every read and write of such a name stays inside its folder, catching any future regression. Not a live escape — both importers were already defended — but now explicit, tested, and regression-locked | CORE | S | a `.xpn`/`.xtd` with `../../../pwned` in a sample name flattens to a basename inside the destination, nothing escapes; a legit `../Samples/` reference still imports; `SafePath` unit tests pin the guard |
| BB2 | ✓ done: resource ceilings — `Acvs.read` caps inflated size (a gzip bomb is refused, not inflated); zip reads cap per-entry and total uncompressed bytes and entry count; `MidiGroove` caps event count; `WavReader` checks the declared data size against the file before allocating | CORE | S | a 10 KB gzip/zip bomb refuses in milliseconds under a fixed heap; a WAV claiming 4 GB of samples refuses instead of allocating |
| BB3 | ✓ done: fuzz harness — seeded mutation fuzzing (byte-flips and truncations of valid fixtures) across `WavReader`, `Acvs`, `MidiGroove`, `XpnImporter`, `Mpc3Importer`, `MpcDiff.load`; every input yields a typed failure or a valid parse within a time bound, never an uncaught `AIOOBE`/OOM/hang. Hostile fixtures committed under `reference/fixtures/hostile/` | CORE | M | N seeded mutations per reader all end in a typed outcome inside the budget; the harness reruns deterministically |
| BB4 | ✓ done: atomic saves — `KitStore`, `GrooveStore`, `TeachLog` write to a temp file and rename (atomic on the same volume), so a process killed mid-save never leaves a half-written `kit.json`; loaders skip torn entries in `.takes/`/`.bin/` rather than throwing | CORE | S | a truncated `kit.json` written mid-save is detectable and the prior file survives; a garbage `.takes/` entry doesn't break `takes()` |
| BB5 | ✓ done: CLI catch-all + hostile sweep — `Cli.run` catches any unexpected `RuntimeException` and prints one honest line at exit 1 (no stack trace to the user); a sweep test runs every command against the hostile corpus and asserts a clean typed exit every time | CORE | S | no command ever prints a Java stack trace or exits non-{0,1,2} on a hostile file |
| BB6 | ✓ done: import metadata validation — duplicate pad slots, out-of-range levels/tunes/velocities, and self-referential or out-of-order velocity layers in an imported program are clamped where harmless and refused-with-a-name where not, before a `Kit` is built | CORE | S | a program with two pads on slot 1, a 9.0 level, and a layer pointing at a missing sample is refused with the specific reason |

**Below the line (post-hardening):** signed-kit provenance (overkill for
a sampler); sandboxed decode (the JVM readers are already
allocation-bounded once BB2 lands). Standing rejects unchanged.

---

# Wave CC — deeper hardening: numbers and the format contract

Wave BB secured the untrusted-*structure* surface (traversal, bombs,
torn writes, crash-on-parse). This wave secures the untrusted-*number*
surface and the format contract itself. The lead is real: a float WAV
decodes samples straight from bytes with **no non-finite guard anywhere**
(`grep isNaN/isFinite` across `:audio` and `:kit` returns nothing), so a
corrupt or hostile float WAV carrying `NaN`/`±Inf` poisons the whole
pipeline — `peak()` goes `NaN`, normalization multiplies every sample by
`NaN`, and the *exported* WAV is all-NaN garbage. CC1 closes that; the
rest deepens the guarantees around it.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| CC1 | ✓ done: NaN/Inf sanitization — float `WavReader` maps every non-finite sample to 0 as it decodes; `WavWriter` refuses (or scrubs) any non-finite sample so no export can carry one; peak/normalization in `Cleanup` and the render path treat a non-finite input as 0, not as a poison value. The one untrusted-input class BB1–BB6 missed, because it arrives as a number, not a shape | CORE | S–M | a float WAV full of NaN/±Inf imports as clean silence; no export (WAV, preview, mixdown) ever contains a non-finite sample; a NaN in one pad can't silence a whole normalized kit |
| CC2 | ✓ done: overflow guards in frame math — `KitPreview.totalFrames`, `SessionMixdown`, `Resampler` output length and any `frames * channels` / `* 2` computed in `Int` are done in `Long` or bounded, so no validated-but-extreme input (a slow tempo, a long loop) yields a negative array size or a wrapped index | CORE | S | a kit at the tempo/length extremes renders or refuses cleanly, never `NegativeArraySizeException` |
| CC3 | ✓ done: golden snapshots — the factory kit exported to every format has its exact bytes committed under `reference/golden/snapshots/`; a test asserts byte-equality, so any silent format drift from a refactor fails loudly and on purpose (regenerate step documented) | CORE | S | exports match the committed goldens to the byte; a deliberate writer change fails the snapshot until the golden is refreshed |
| CC4 | ✓ done: round-trip property fuzz — seeded random valid kits + grooves → export (folder / xtd / xpj / xpn) → re-import → assert every pad, level, tune, mute group, colour and note survives; N seeds, deterministic. Catches *writer* bugs the reader fuzz (BB3) structurally cannot | CORE | M | N seeded kits round-trip with no lost or corrupted pad/note across every re-importable format |
| CC5 | ✓ done: temp-file leak audit — every `createTempFile`/`createTempDirectory` (`KitBackup`, `XpnPackager`, `PackBuilder`, `SessionBuilder`, the CLI) sits in a `try/finally`; a forced-failure test proves an exception mid-operation leaves no temp residue behind | CORE | S | inject a failure into each temp-using path; no stray temp file or dir survives |
| CC6 | ✓ done: preflight ⇒ export invariant — a kit that passes `Preflight.check` must always export without throwing; the two must never disagree. A property test fuzzes kits, keeps the preflight-clean ones, and asserts every one exports to every format | CORE | S | no preflight-clean kit throws on export in any format |

**Below the line (post-CC):** audio watchdog/timeouts on the DSP path
(the JVM work is already bounded and synchronous); differential fuzzing
against a second MPC-format implementation (none exists to diff against).
Standing rejects unchanged.

---

# Wave DD — deeper: parser robustness

Waves BB and CC hardened the untrusted-input surface against attacks and
bad numbers. This wave goes after **silent mis-parsing** — where an import
succeeds but is quietly wrong — and the parser coverage gaps underneath.
The lead is a real bug: `XpnImporter` reads `.xpm` programs with regular
expressions, so legitimate XML variety (attribute order, extra attributes,
whitespace, CDATA, comments) drops pads with no error. Not padded to six —
each item is a real gap.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| DD1 | ✓ done: robust `.xpm` parsing — replace the regex program parser in `XpnImporter` with a proper, XXE-safe DOM parse (the one `MpcDiff` already uses for MPC 2 XML), so attribute order, extra attributes, whitespace, CDATA and comments no longer silently drop pads — and numeric character references (`&#233;`, `&#x2764;`) resolve for free, which the hand-rolled `xmlUnescape` never did | CORE | M | a valid `.xpm` with reordered/extra attributes, a comment, a CDATA sample name and a numeric-entity program name imports every pad correctly; the golden corpus still imports unchanged |
| DD2 | ✓ done: JSON parser property + fuzz — `parse(write(v)) == v` over seeded random `JsonValue` trees; direct mutation fuzz asserting typed-refusal-or-valid; and the edge cases a hand-rolled parser gets wrong (exponents, `-0`, huge/tiny numbers, `\u` escapes and surrogate pairs, duplicate keys). The most load-bearing parser in the codebase, currently the least adversarially tested | CORE | M | N random trees round-trip; every mutation ends typed-or-valid; the number/unicode edge cases parse to the right values |
| DD3 | ✓ done: DSP invariant properties — over seeded synthetic audio: `Chopper` slices are in-bounds, ordered, and cover to the end; `Classifier` always returns a valid `DrumClass` with confidence in `0..1`; extracted features are never NaN. The pipeline's contracts, pinned as properties instead of examples | CORE | S | thousands of synthetic inputs hold every invariant, no NaN, no out-of-range |
| DD4 | ✓ done: WAV chunk-variety lock — a test proving a WAV carrying `LIST`/`fact`/`JUNK`/`cue` chunks before and after `data` reads correctly (`WavReader` already skips unknown chunks; this pins it so a refactor can't quietly lose real-world-file support) | CORE | S | a multi-chunk WAV decodes to the same samples as its bare-`data` twin |

**Below the line (post-DD):** a from-scratch streaming XML/JSON pull parser
(the DOM + hand-rolled parsers are bounded and sufficient); schema
validation of every payload field against the corpus (the key-path guard
and golden snapshots already cover drift). Standing rejects unchanged.

---

# Wave EE — iconic: features people tell each other about

A different lens than ROI: not what's nearly free, but what deepens the
product's identity — the tape metaphor, the crate-digging ritual, the
hardware lineage. Two sound-character items (eras, wear), two creative
acts (the answer, the beat tape), one identity artifact (the J-card).
All pure-JVM CORE on machinery that already exists; all compound (wear
shows on the J-card, eras colour the beat tape, the answer joins the
session). Build order **EE2 → EE1 → EE3 → EE4 → EE5**: eras give wear
its saturation/filter primitives, and the J-card wants everyone else's
output.

## EE2 — The Time Machine: era-faithful sound

"Vintage mode" was rejected in wave 5 as a knob crush already covered.
As a *suite* it is a different thing: the specific math of specific
machines is what beatmakers pay real money for. Named eras, honest
"inspired-by" language, recipe-recorded and reversible like every
treatment.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE2.1 | ✓ done: `Eras` (`:synth`) — named era chains built from exact primitives: `sp1200` (12-bit truncation at 26.04 kHz with the aliasing that implies), `mpc60` (12-bit non-linear companding, its input filter), `tape` (saturation + wow + HF loss), `phone` (band-limit + companding). Each a deterministic Snip→Snip with an `amount`; recipes recorded | CORE | M | each era measurably does its math (bit depth, rate, spectrum); deterministic; recipe round-trips |
| EE2.2 | ✓ done (era ages the *files*, bin-backed, so every render and export carries it): Wire — CLI `era <kit-dir> <era> [--amount] [--pads]` (bin-backed undo like `treat`); preview/mixdown render through it; era rides the kit as metadata | CORE | S | era → audibly/measurably different kit; undo restores byte-identical originals |

## EE1 — Tape wear: the kit as a living tape

The product pretends to be a tape deck; make the metaphor real. Opt-in
per kit: plays and saves accrue mileage in a wear ledger, and the kit's
*rendered sound* ages — wow/flutter, a whisper of hiss, HF rolloff, the
rare dropout. **The maximum is the feature:** wear follows
`w = 1 − exp(−mileage/K)` — patina physics, fast at first, asymptotic at
"well-worn", never "ruined". Hard caps at `w = 1.0`: flutter ≤ ±6 cents,
hiss ≤ −48 dBFS, HF shelf never below 8 kHz, dropouts rare and **never
on a strong hit** (downbeats structurally protected). Two guarantees:
wear is a render/export-time recipe over pristine WAVs (originals never
rewritten; wipe the ledger, the tape is new), and a worn kit must still
pass preflight and the CC6 export invariant — tested, not hoped.
Automatic accrual stops at the ceiling; a deliberate `--wear` override
can push past it (chosen destruction is a treatment, earned patina is
capped — FULL personality may comment).

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE1.1 | ✓ done: `TapeWear` (`:synth`) — the wear chain (flutter via slow resample wobble, hiss, HF shelf, protected-dropout logic) parameterised by `w`, deterministic per (kit, mileage); the caps as constants with tests | CORE | M | w=1 renders inside every cap; downbeat protection holds; deterministic; w=0 is bit-identical passthrough |
| EE1.2 | ✓ done: Wear ledger — `wear` block in `kit.json` (mileage, enabled, K); accrual hooks in `KitBuilderModel` (play/save) and CLI; `w` derived, never stored | CORE | S | ledger round-trips; accrual saturates on the curve; wiping it restores the new-tape sound |
| EE1.3 | ✓ done (previews wear as one tape pass; exports stage a worn twin; project brings each kit in worn per its own ledger, mixdown included): Wire — preview, mixdown and audio exports render through the kit's wear when enabled; `--no-wear` bypass; a worn kit passes preflight + CC6 across every format | CORE | S | worn preview ≠ pristine preview; every export gate stays green |

## EE3 — The Answer: chop a break, get the B-side

The magic-moment demo. The kit already knows its key (KeyGuess), its
groove, its feel. `answer` generates the complement: an S5 synth
bassline **in the detected key**, playing a counter-pattern derived
from the groove's gaps (the pocket inverted — GrooveFeel backwards),
landed as a keys track + its groove in the session.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE3.1 | ✓ done (landed in `:shell`, the compose-everything layer — `:synth` would have needed new main-source deps on `:mpc3`/`:xpm` for no gain): `Answer` (`:synth`) — counter-pattern derivation (notes in the groove's gaps, root-and-fifth weighted toward the key, donor feel applied), rendered through an S5 bass patch to a keygroup instrument + clip; seeded, rerollable | CORE | M | answer notes avoid the kit's strong hits, sit in the detected key, follow the feel; same seed same answer |
| EE3.2 | ✓ done (keys tracks gained `clips` in the project writer — same name-keyed trackClipMaps idiom as drum tracks; bench row: hear a keys clip play on the Live III): CLI `answer <kit-dir> [--seed]` — builds the instrument + groove and (with `project`) lands both in one session | CORE | S | chop a tonal break → answer → one .xpj where the break plays with a bassline that fits |

## EE4 — SIDE A: the beat tape

The output stops being kits and becomes a finished artifact: N kits →
one continuous render — each kit playing its patterns for a few bars,
chained with tape-stop and pull-up transitions (a tape stop is a repitch
ramp to zero; we own that math) — plus tracklist, cover with spine
label, and the whole thing as an `.xpj`.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE4.1 | ✓ done: `BeatTape` (`:kit`) — arrangement (bars per kit, pattern rotation), transition DSP (tape-stop ramp, pull-up), continuous mixdown; deterministic | CORE | M | N kits → one WAV with audible, sample-accurate transitions at the right bars; deterministic |
| EE4.2 | ✓ done: CLI `sidea <kit-dir>... --title [--bars]` — WAV + tracklist.txt + cover (KitArt spine variant) + session `.xpj` in one folder | CORE | S | one command → a postable beat tape folder |

## EE5 — The J-card: every kit gets its cassette insert

Everything a J-card needs is already tracked — provenance, key, tempo,
classes, groove, art, and (post-EE1) the wear mileage. Render it as a
printable card: pad tracklist, groove as pixel notation, the kit's art,
the tape's history.

| # | Work | Owner | Size | Exit test |
|---|---|---|---|---|
| EE5.1 | ✓ done: `JCard` (`:shell`) — a KitArt-family renderer producing the fold-ready card PNG (front/spine/back panels): art, name, pad list with classes and sources, groove notation in PixelType, key/tempo/mileage | CORE | S–M | deterministic card for the factory kit; every starter kit renders; long names and 32-pad kits fit |
| EE5.2 | ✓ done (`pack` lands per-kit cards under `[J-Cards]/`, `.xpn` twin included): CLI `jcard <kit-dir> [--out]`; expansion export drops the card beside the artwork | CORE | S | one command → the kit's insert; the pack builder gets pack cards for free |

**Below the line (wave FF candidates):** generative autoplay
environments (app-side by nature); sample-archaeology annotated
waveforms (nice, not iconic); anything needing network or licensing.
Standing rejects unchanged.

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

CORE wave 4: ✓ all six landed (2026-08-25) — total recall, pattern
  variations, session builder, pad treatments, preview renderer,
  diff tool. Bench rows (Y2.3/Y5.3/Y6.3) and app hooks remain.

CORE wave 5: ✓ all landed (2026-08-25) — art renderer + CLI, swing,
  auto slice-count, chop-all, kit merge, MIDI bridge, and the art
  wire-through (Z6.2 verdict: waveform default, rings runner-up).
  Remaining on the bench: W12 pad waveforms (APP-only polish) ·
  the Live III showing the tile (rides the next card session)

CORE wave EE: ✓ all landed (2026-08-27) — the Time Machine eras,
  tape wear (the ledger, the capped patina chain, the render-time
  wire-through), the Answer (counter-pattern bassline in the key, in
  the gaps, with the feel), SIDE A (the beat tape with tape-stop and
  pull-up transitions), and the J-card. 737 tests. Bench rows for the
  Live III: a keys clip playing from a sequence (EE3), a worn export
  A/B'd against a pristine one (EE1), an era'd kit (EE2).

CORE wave DD: ✓ all landed (2026-08-26) — robust .xpm DOM parse
  (fixed a real silent pad-drop + numeric entities), JSON parser
  property+fuzz (found it refuses duplicate keys - stricter than
  assumed), DSP invariant properties, WAV chunk-variety lock. 704
  tests. DD1 was a real bug; the rest are property/robustness locks.

CORE wave CC: ✓ all landed (2026-08-26) — NaN/Inf sanitization
  (which also found peak() poisoned by Inf), tempo overflow guards,
  golden snapshots, round-trip property fuzz, temp-leak lock, and the
  preflight-implies-export invariant. 695 tests. CC1/CC2 were real
  bugs; CC3-CC6 are regression locks that hold the line.

CORE wave BB: ✓ all landed (2026-08-25) — SafePath traversal
  hardening, LimitedRead resource ceilings, the mutation-fuzz harness
  (which caught a real MidiGroove crash), atomic saves, the CLI
  catch-all + hostile sweep, and import metadata validation. 686 tests.
  Findings were honest: BB1 and BB6 were already defended (basename
  flattening; the Kit constructor's invariants) - the wave made those
  defenses explicit, central, and regression-locked; BB2/BB3 closed
  real DoS and crash gaps.

CORE wave 6: ✓ all landed (2026-08-25) — multi-sequence projects
  (probe first; the four variations arrive as switchable sequences),
  groove transfer, key guess, tempo-fit, session mixdown, pack
  builder + whole-pack import. Bench row open: AA1.3 sequence flip
  on the Live III (rides the next card session).

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
