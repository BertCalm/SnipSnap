# Murmur Luthier follow-up

This follow-up keeps the approved model-1/model-2 render identities and the 36-preset bank. It makes a small render optimization, adds a coherent foldback regression check, and supplies two reversible listening experiments. No candidate becomes a factory sound in this change.

Reviewed base: `84471534a` (the Murmur engine itself is unchanged from the previously delivered `395de6e2`). The complete repaired Luthier bundle passed its resource checker: nine reference documents, three harness scripts and the evaluation JSON.

## Reproduce the listening experiments

```sh
./gradlew --no-daemon :synth:generateMurmurLuthierAudition
```

Output: `testkit/murmur-luthier-audition/`. Pass `-PmurmurLuthierOutput=/absolute/output/path` to choose another destination. The generator writes twelve dry comparisons, raw/matched PCM24 WAVs, exact settings, measured loop plans, hashes and a reproduction README. It checks repeated Float32 PCM determinism and verifies its experimental finishing path against the production probe at both CHOIR notes.

KEY compares MOTION 0, .5, .75 and 1 at C4. The candidate is an ordinary serialized model-2 custom patch: target gains are multiplied by three, capped at the existing limit of three. Its source, center frequencies, bandwidths, ratios, envelope and macros stay the same. The purpose is to test whether stronger resonance reveals the moving mouth without losing the tine character. The measured improvement remains conservative; listening must decide whether it is useful.

CHOIR compares current and longer HOLD plans at C2 and C3. The experimental planner fits one cycle of the requested near-unison beating, then rounds to an even number of root cycles and a whole control-block boundary. The C2 loop grows from about two seconds to about five seconds. This also changes the repeated-noise period and quantized formant-motion rate, so the comparison does not isolate detune alone. Its JSON wrapper includes the extra policy and base patch. Loading only that base patch into the app produces the approved short loop. The longer policy exists only in the development generator.

## Why it is built this way

- Stationary formants retain their initial coefficients, while their filter histories still advance. Repeating the same division adds cost without changing the sound.
- Absent ring sources skip their cosine; duplicate operator reads reuse one cosine. Product and sum order remain unchanged to preserve saved render arithmetic.
- Moving formants and every settling period retain their existing processing. A broader trajectory cache measured slower and was removed.
- The experimental loop policy stays in the listening tool until its sound and extra render cost have been judged. No new saved model or patch migration is introduced.
- Foldback is measured separately from high-frequency brightness. A bright spectrum can contain legitimate harmonics, and a quiet near-Nyquist band alone cannot establish alias rejection.

## Verification ledger

Measured results and process exits are recorded below when the final checks finish. Hearing remains unverified until the owner listens. Browser playback checks verify delivery and interaction, not sonic preference or physical phone/MPC acceptance.
