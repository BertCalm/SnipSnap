# Nacre model-1 venue-return audition

This is a testkit-only listening experiment based on owner feedback: model 1 was pleasant; model 2 was overbearing and abrasive. Model 2 is not an accepted replacement. Luthier's REVIEW workflow narrows the next decision to three clips while preserving the preferred instrument.

- Original: the published model-1 gesture, pinned by its prelevel and native Float sample hashes.
- Coral: replace only the conditioned venue return with the existing coral endpoint at 1.8× (+5.1 dB).
- Seaweed: the same replacement using the existing seaweed endpoint. CURRENT stays at 0.20.

The original direct excitation, active loops, body and sympathetic balance, event sequence, envelopes and decay process remain unchanged. This does not introduce a production rendering model or change a default.

## Reproduce

Run `./gradlew --no-daemon :synth:generateNacreGentleAudition`. Output is ignored under `testkit/nacre-gentle-audition/`. Serve that directory over HTTP for the listening page, or open the WAVs directly. Listening and raw ZIPs each contain three WAVs and supporting evidence.

The candidates use `Float(original.raw − originalVenue + Float(1.8 × endpointVenue))`, retaining the original conditioned full bus. A production patch alone replays the **unlifted** venue endpoint. The audition recipe wrapper separately records the postmix multiplier. Native source-render and audition hashes are distinct.

One scalar derived from the original is shared across all three listening WAVs, with a common peak guard. Raw WAVs also share one scalar. Neither mode levels candidates independently. Shared gain preserves foreground level; it does not equalize perceived loudness.

## Review and acceptance ledger

**Heard by owner:** model 1 preferred; model 2 overbearing and abrasive. The initial comparison offered too little variation beyond strumming.

**Verified in source:** model-1 VENUE selects a feed-forward return. Its endpoint selection cannot feed back into string, body or sympathetic state. The helper checks unchanged stems and events, exact original hashes, finite samples and separation of production patch from audition postmix.

**Proposed review guardrails:** at most 15% additional total energy, E99 extension at most 0.12 s, post-1 s energy at most 0.6% of original total energy, and 1–4 kHz early-body spectral fraction addition at most 0.001. These conservative limits constrain density and upper-mid growth; they are not a musical acceptance test. The test reports measured guardrail results; the manifest includes general signal metrics.

**Listening pending:** whether either return is clearer, useful and still pleasant. No numerical result establishes that it sounds better.

Luthier's main guidance was available. Its supporting references were unavailable, so the review used the actual Kotlin renderer, frozen reference export and independent source inspection. The audition source snapshot records the precise commit and file hashes.
