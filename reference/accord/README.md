# Accord SnipSnap engineering handoff

This bundle turns the accepted Accord sound direction into a native-engine build plan. BRUSH is the default; WOOD and TINE remain, including the three TINE variations. The original TINE ratio 5.96 remains the compatibility reference.

Start with [the engineering specification](Accord-Engine-SnipSnap-Engineering-Spec.md). It defines the reciprocal mechanics, saved performance, acoustic profiles, proposed controls, cache design, integration points and build gates. The repository audit is pinned to SnipSnap commit `d7c2379c16520df2679457679844b56d32777712` on 31 October 2026. Recheck the implementing checkout before using its APIs.

This is a design handoff with runnable Python references. It does not contain a Kotlin engine or claim native performance, sustain closure or phone/MPC acceptance.

## What to use

- [Approved reference data](approved-reference-data.json): exact mode tables, initializer vectors and audio paths. This is handoff data, not a proposed public patch JSON format.
- [Repository audit](evidence/repository-audit.md): verified APIs and integration changes since the initial investigation.
- [Acoustic audit](evidence/acoustic-audit.md): exact contact, pressure, note/velocity and calibration laws.
- [Mechanics audit](evidence/mechanics-audit.md): equations, fixtures, solver/cache reasoning and numerical evidence.
- [Independent handoff review](evidence/handoff-review.md): findings and their resolution.
- [Portable reference instructions](README-references.md): dependencies, quick verification and optional experiment commands.
- [Source manifest](SOURCE_MANIFEST.json) and [reference QA](REFERENCE_QA.json): original/copy hashes, narrow portability changes and completed checks.

The six WAVs under `reference/` are the unchanged accepted originals and TINE options. They also remain on the [private audition page](https://accord-listening-room.bertcalm.chatgpt.site#tineOptions). No access to that website is required to use this bundle.

## First implementation task

Read the spec and the current repository Luthier instructions, then complete R0 and R1. Preserve the reference initializer and default acoustic anchors before changing initialization, filters or the control domain. Run the numerical and acoustic gates, measure native cost, and present matched native/Python auditions for the owner. The complete macro cube and sustain follow their separate gates.

Preserve the frozen references. Renderers write beside their scripts, so run experiments in a fresh scratch copy using the instructions above. The refinement renderer intentionally retains the historical **6.00 TINE diagnostic**, while the accepted baseline and options use **5.96**. Do not promote that diagnostic into the native defaults.
