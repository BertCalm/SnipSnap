# Docs

What lives under `docs/`, and which file is the truth for a question.

A Markdown-only pull request does not start CI. That is deliberate
(`.github/workflows/tests.yml` ignores `docs/**` and `**/*.md`) — no
test reads these files. `reference/` is fixture data and is not ignored.

## Read these first

| Question | File |
|---|---|
| What is the product? | [`CONCEPT.md`](CONCEPT.md) |
| What is built, what is unproven? | [`APP_PLAN.md`](APP_PLAN.md) §Where the project stands |
| What does a phone-and-card pass look like? | [`BENCH.md`](BENCH.md) |
| How do I run the JVM / native suites? | the root [`README.md`](../README.md), then `.claude/skills/steward/SKILL.md` |
| How do I build `:app`? | [`../app/README.md`](../app/README.md) |

## Product and Android

- [`CONCEPT.md`](CONCEPT.md) — problem, MVP cut, architecture. The old
  "deliberately v2" list shipped in the core; the leftover is hardware.
- [`APP_PLAN.md`](APP_PLAN.md) — milestones M0–M5, the hardware queue,
  core odds and ends. Status table is current; finished-milestone entries
  are history.
- [`FEATURE_PLAN.md`](FEATURE_PLAN.md) — the same work cut by product
  feature, with owners and exit tests.
- [`BENCH.md`](BENCH.md) — every `USER` row, in run order, with a line
  to write the answer on.
- [`../DEVICE_TEST_GUIDE.md`](../DEVICE_TEST_GUIDE.md) — the feel pass
  a script cannot make. Playback is `PadEngine`.
- [`ANDROID_CAPTURE.md`](ANDROID_CAPTURE.md) — MediaProjection, the
  mic, and where capture breaks.
- [`CLI.md`](CLI.md) — `snipsnap.jar`, the desktop pipeline.
- [`WORKSHOP.md`](WORKSHOP.md) — the knock on SETUP, SEND TO BENCH,
  and the tools behind it.
- [`PERSONALITY.md`](PERSONALITY.md) — voice, the four laws, the gag
  catalog.
- [`MIDI_SYNC.md`](MIDI_SYNC.md) — why MIDI clock is designed and not
  implemented. A survey, not a plan.

## Formats and kits

- [`MPC_EXPORT.md`](MPC_EXPORT.md) — folder layouts, `.xpn`, expansions.
- [`MPC3_FORMAT.md`](MPC3_FORMAT.md) — the native container, drum and
  keygroup schemas, corpus vs inference.
- [`XPM_STRUCTURE.md`](XPM_STRUCTURE.md) — the MPC 2 program. The
  KeygroupWriter defects it records are fixed; the table is the evidence.
- [`KIT_BEST_PRACTICES.md`](KIT_BEST_PRACTICES.md) — pad layout, mute
  groups, naming.
- [`../testkit/README.md`](../testkit/README.md) — hardware acceptance
  kits. The device is the Live III.
- [`../reference/README.md`](../reference/README.md) — harvesting
  programs off hardware.

## Sound and the desk

- [`SYNTH_ROADMAP.md`](SYNTH_ROADMAP.md) — how the engines were built,
  phase by phase.
- [`SYNTH_UPGRADE.md`](SYNTH_UPGRADE.md) — the 2026 playability gap
  that motivated presets. U1 and U8 shipped; the opening "Presets: 0"
  is the gap, not the present.
- [`ORBITS.md`](ORBITS.md) / [`ORBIT_SPAN_AND_BAR.md`](ORBIT_SPAN_AND_BAR.md)
  — the circular sequencer.
- [`CHOP_CONTROLS.md`](CHOP_CONTROLS.md) — CHOP's controls against the
  bench.
- [`CATCH.md`](CATCH.md) / [`RETRIM.md`](RETRIM.md) / [`DESAMPLE.md`](DESAMPLE.md)
  / [`DUST.md`](DUST.md) / [`CALIBRATION.md`](CALIBRATION.md) — named
  treatments and the classifier bench.
- [`PHOTO_SPECS.md`](PHOTO_SPECS.md) — SNAP / DRAW / PHOTO FIELD.

## Design

- [`UI_DESIGN.md`](UI_DESIGN.md) — TapeOS rules that still hold.
- [`DESIGN_GAP.md`](DESIGN_GAP.md) — historical gap list against
  `design/HANDOFF.md`. Pad sheet v2 lives under
  [`../design/pad-sheet-v2/`](../design/pad-sheet-v2/README.md) and
  supersedes that section here.
- [`../design/HANDOFF.md`](../design/HANDOFF.md) — Oilslick tokens.
  The CLEAR prototype is gone.
- [`../prototype/README.md`](../prototype/README.md) — browser feel
  labs. Not a second app.

## Dated reviews (snapshots)

These are point-in-time. A number in them (test counts, scheme counts,
"not yet") is what was true on that date, not a promise it still is.

- [`SPECS_2026_09.md`](SPECS_2026_09.md)
- [`UAT_2026_09.md`](UAT_2026_09.md)
- [`AUDITION_SPEC_2026_09.md`](AUDITION_SPEC_2026_09.md)
- [`CAPTURE_RESEARCH_2026.md`](CAPTURE_RESEARCH_2026.md)
- [`UX_JOURNEY_PLAN_2026_09.md`](UX_JOURNEY_PLAN_2026_09.md)
- [`UX_JOURNEY_REVIEW_2026_09.md`](UX_JOURNEY_REVIEW_2026_09.md)
- [`UX_PERSONA_PLAN_2026_09.md`](UX_PERSONA_PLAN_2026_09.md)
- [`UX_PERSONA_REVIEW_2026_09.md`](UX_PERSONA_REVIEW_2026_09.md)
- [`UX_WIRING_REVIEW_2026_09.md`](UX_WIRING_REVIEW_2026_09.md)

## Spec / plan archive

[`superpowers/`](superpowers/README.md) holds dated design specs and
the execution plans that implemented them. They are history. Do not
delete them to tidy the tree, and do not treat a sentence in a plan
as current product status.

## Naming

- Top-level `SCREAMING_SNAKE.md` — durable reference (formats, CLI,
  personality). Update it when the fact changes.
- `TOPIC_2026_09.md` — a review or UAT pass. Leave the date in the
  name; add a banner if a later pass supersedes a claim.
- `superpowers/specs/` and `superpowers/plans/` — ISO-dated pairs.
  A spec is the design; a plan is the work that followed.
