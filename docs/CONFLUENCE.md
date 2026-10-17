# Confluence — first recorded-source audition

Confluence turns supplied vocal regions into a small, independently scheduled
ensemble and renders ordinary mono samples for SnipSnap's kit workflow. This
first proof asks whether increasing the number of persistent performers sounds
like people joining. The owner's listening verdict remains open.

The supplied [engineering specification](CONFLUENCE_SPEC.md) is retained unchanged
as the long-term proposal. This document describes the smaller implemented stage.

## Implemented scope

- A dedicated `ConfluenceRecipe`, resolved immutable `ConfluenceSource` assets,
  and `Confluence.render(recipe, sources)` returning audio, identities and events.
- One to eight roster slots derived from a saved seed. CROWD adds slots with
  fractional activation, retaining earlier identities.
- Finite UNISON, RESPONSE and DRONE arrangements. Full selected regions retain
  their ordering; DRONE requires explicitly marked sustain material. Breath
  pauses are silent, without generated inhalations.
- CROWD, REGISTER, DIVERGENCE, RESPONSE and SPACE. REGISTER provides a small
  pitch distribution; octave transformation is not validated. SPACE is one
  bounded shared mono room, with an explicit bypass.
- Conservative time-domain pitch-synchronous overlap/add for pitched regions,
  independent pulse spacing, local spectral-envelope color and small duration
  differences. Unvoiced material follows the source chronology. This is an
  audition candidate, not a validated general-purpose voice processor.
- PITCHED and TEXTURE source modes. PITCHED requires a reference note and
  preserves the source contour; TEXTURE makes no melodic-tuning promise.
- Source hashes, region and sustain markers, versions, seed and macros in JSON;
  source PCM and derived analysis outside the recipe.

No normal `Patch` registration, Android editor, playable factory presets,
keyboard multisample instrument or HOLD is included in this stage. The existing
`Patch.render()` contract remains unchanged. The ordinary rendered kit is a
playback artifact, while the source bundle preserves editing/reproduction inputs.

## Reproduce the audition

From the repository root, with Java 17 or newer:

```sh
./gradlew --no-daemon :synth:test --tests '*ConfluenceTest'
./gradlew --no-daemon :synth:generateConfluenceAudition
python scripts/build-confluence-audition-site.py
./gradlew --no-daemon test
```

The generator writes real recorded-source WAVs under
`testkit/confluence-audition/`, with raw/matched files, exact recipes,
measurements, source provenance and causal event traces. It also packages a
rendered MPC kit and a compact source/recipe bundle. The staging script combines
that evidence with `design/confluence-audition/` into the buildless listening
page under `testkit/confluence-audition-site/`. Generated evidence is ignored by
Git; the renderer, generator, page source and vocal fixtures are retained.

## What to listen for

Begin with the source and the dry, matched 1/2/4/8 comparison. Compare the existing
six-player SECTION effect, independent Confluence scheduling, and the two-singer
version. Judge the vowel and articulated phrase separately. More performers
should change participation, articulation and density rather than just loudness.

The behavior clips expose responses and rests. Identity clips compare fixed
performers against deliberately reshuffled identities, including isolated slots
returning after a pause. Coordination comparisons test entrance following only;
there is no continuously negotiated pitch model. Room clips are secondary to the
dry evidence. Notes and Keep/Revise/Pass verdicts stay in the browser and can be
exported as JSON.

Matched playback uses the project's loudest-window loudness measurement, not an
integrated-song loudness target. Raw PCM, export gain and matched measurements
remain available. Numerical validation does not establish natural vocal identity,
intelligibility or a convincing crowd; those are listening judgments.

## Sources and boundaries

The audition uses actual human recordings from **VocalSet: A Singing Voice
Dataset**, Julia Wilkins, Prem Seetharaman, Alison Wahl and Bryan Pardo (2018),
[Zenodo record 1193957](https://doi.org/10.5281/zenodo.1193957), licensed
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

The fixture [manifest](../reference/fixtures/confluence/manifest.json) records
original and canonical SHA-256 hashes, archive members, frame-accurate excerpts,
constant gain adjustments, pitch observations and selection basis. Complete
original WAVs remain beside the canonical excerpts. Selected phrase boundaries
follow waveform/pitch continuity and pauses; no automatic linguistic transcription
or tradition-specific performance claim is made. Vocal transformation does not
create additional recorded singers: single-source clips are transformed variants
of one recording; the two-singer comparisons use separately supplied singers.

The source bundle has the canonical sources and recipes required to rerender
without relying on local paths or caches. The generator checks a moved-directory
rerender. MPC WAV/XPM/XTD/XPN output contains rendered playback, not a Confluence
engine running on the MPC. Broad pitch changes, rough/multiphonic sources,
48-performer mobile budgets and seamless linguistic looping remain unvalidated.

## Next stages

After a useful dry listening verdict, develop source preparation in TAPE/pad
workflows, explicit asset resolution and relinking, an editable native source
bundle, and cancellation/caching for expensive previews. Expand the processor,
performance profiles and voice count from measured results. Sustained keyboard
instruments and periodic HOLD require separate pitch-zone and boundary validation.
