# Source transformation: Codex handoff

Status: R0 review and R1 desktop implementation/validation complete. Listening, real-capture evaluation, and Nothing Phone (3) measurements remain pending. This document originally scoped the review; the owner subsequently authorized R1 in the conversation.

Prepared 2026-10-21 from the owner's supplied Manipulator 1.4.3 and Supermodal 1.0.0 manuals. Repository baseline: `565551e7974a4c61954199fac56a7a8d3dd502ed`.

## Original R0 assignment

Use Luthier to review this handoff and both manuals against the current SnipSnap repository. Verify the proposed reuse points and processing constraints. Recommend the smallest first implementation of the resonant-body processor, identify unresolved decisions, and produce an implementation plan with tests and audition outputs. Do not implement yet.

Read in this order:

1. Applicable repository instructions and `.claude/skills/luthier/SKILL.md`, then its required references.
2. [Source manifest](references/SOURCES.md), with page-numbered text of both supplied manuals.
3. [Adaptation brief](adaptation-brief.md).
4. [Phased plan and open decisions](implementation-plan.md).
5. [Validation and audition requirements](validation.md).

Luthier was added in [PR #617](https://github.com/BertCalm/SnipSnap/pull/617), which has now merged. Its directory is present at the inspected commit `4e36a2bf8cf8bfb07dba35379558b54429b5d73d`. For an older checkout, read the skill and references from that commit rather than relying on the installation branch still existing. Do not merge unrelated changes as part of this review.

## R0 review and proposed R1

The R0 documents are [repository-review.md](repository-review.md) and [r1-implementation-plan.md](r1-implementation-plan.md). They record the inspected commit and distinguish code facts from proposed DSP. R1 is a standalone shared body processor with two four-mode families and a static crossfade; the conventional parallel filter, motion, rack integration, and source transformation follow later. The performance target is the owner's Nothing Phone (3); device measurements and listening remain pending. No DSP has been implemented by R0.

R1's code, measured results, and disclosed pitch-detector limits are recorded in the implementation plan's As built section. Generate its standalone listening package with `./gradlew :synth:generateResonantBodyAudition` from the repository root. The default output is `testkit/resonant-body-audition/`; use a fresh `-PbodyOutput=/path/to/output` to preserve an existing pack, or add `-PbodyVerify` to replay-check one. Add `-PbodyCapture=/path/to/private.wav` only when including an owner-approved capture. No capture or commercial demo audio is bundled by default.

## Required R0 review outputs

Write `repository-review.md` and `r1-implementation-plan.md` in this directory. Record the exact commit inspected, file/line evidence, commands run, and unresolved questions. Separate verified code facts, manual statements, proposed behavior, measured results, and listening judgments.

The review must resolve the module boundary, processor location, channel policy, body-morph strategy, gain staging, tail policy, cache keys, and target-device performance measurement. Verify whether the current BODY, GRAINS, modal toolkit, and rack still behave as described. Do not name an API without locating its declaration.

The R1 plan must list exact files to change, data structures, compatibility requirements, tests, audition cases, dependencies, and completion gates. Preserve existing saved recipes and deterministic renders unless a versioned change is explicitly proposed. Stop after the review and plan; return implementation decisions for the owner to review.

## Scope and intent

Adapt useful behaviors into SnipSnap's own architecture. The first deliverable is a reusable resonant-body processor. A source transformer and combined recipes follow only after their separate proofs. Product names describe research references, not proposed engine, voice, or preset names. Internal labels in this handoff are descriptive placeholders.

The original handoff contained documentation and supplied-reference text only. R1 adds the standalone processor, tests and audition generator described in the As built ledger. It adds no product UI, factory presets, saved-pad schema, or external dependency.
