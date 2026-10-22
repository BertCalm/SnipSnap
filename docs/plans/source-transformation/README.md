# Source transformation: Codex handoff

Status: proposed design and repository review assignment. No implementation authorized by this handoff. No audio rendered or heard.

Prepared 2026-10-21 from the owner's supplied Manipulator 1.4.3 and Supermodal 1.0.0 manuals. Repository baseline: `565551e7974a4c61954199fac56a7a8d3dd502ed`.

## First assignment

Use Luthier to review this handoff and both manuals against the current SnipSnap repository. Verify the proposed reuse points and processing constraints. Recommend the smallest first implementation of the resonant-body processor, identify unresolved decisions, and produce an implementation plan with tests and audition outputs. Do not implement yet.

Read in this order:

1. Applicable repository instructions and `.claude/skills/luthier/SKILL.md`, then its required references.
2. [Source manifest](references/SOURCES.md), with page-numbered text of both supplied manuals.
3. [Adaptation brief](adaptation-brief.md).
4. [Phased plan and open decisions](implementation-plan.md).
5. [Validation and audition requirements](validation.md).

Luthier was added in [PR #617](https://github.com/BertCalm/SnipSnap/pull/617), which was still open when this handoff was prepared. If its directory is absent, read the skill and references from that PR's `codex/install-luthier-skill` branch. Do not silently proceed as though the skill is present, and do not merge that PR as part of this review. The handoff itself is intentionally independent of the installation PR.

## Required review outputs

Write `repository-review.md` and `r1-implementation-plan.md` in this directory. Record the exact commit inspected, file/line evidence, commands run, and unresolved questions. Separate verified code facts, manual statements, proposed behavior, measured results, and listening judgments.

The review must resolve the module boundary, processor location, channel policy, body-morph strategy, gain staging, tail policy, cache keys, and target-device performance measurement. Verify whether the current BODY, GRAINS, modal toolkit, and rack still behave as described. Do not name an API without locating its declaration.

The R1 plan must list exact files to change, data structures, compatibility requirements, tests, audition cases, dependencies, and completion gates. Preserve existing saved recipes and deterministic renders unless a versioned change is explicitly proposed. Stop after the review and plan; return implementation decisions for the owner to review.

## Scope and intent

Adapt useful behaviors into SnipSnap's own architecture. The first deliverable is a reusable resonant-body processor. A source transformer and combined recipes follow only after their separate proofs. Product names describe research references, not proposed engine, voice, or preset names. Internal labels in this handoff are descriptive placeholders.

This PR contains documentation and supplied-reference text only. It does not add DSP, UI, presets, dependencies, or measurements.
