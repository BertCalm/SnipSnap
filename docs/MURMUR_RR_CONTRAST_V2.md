# MURMUR selected PLUCK repeat contrast, opt-in V2

The characteristic gesture is one selected pluck struck with four bounded operator/contact relationships, followed by the original mouth and body response. This is one voice and one selected setting, with four takes.

The owner heard no useful variation in the C2 V1 repeat clip. V1 varied the quiet breath seed and measured gain-removed residuals .0078 to .0089. Native sample differences did not satisfy that listening decision. The separate soft-to-firm performance was KEEP and keeps its existing scope.

## As built, source only

`MurmurRoundRobinContrastRecipe` owns the full canonical source patch and take in separate JSON kind `MURMUR_SELECTED_ROUND_ROBIN_CONTRAST`, version2, policy `NATIVE_OPERATOR_CONTACT_V2`. Only the two finite selected model4 PLUCK settings are eligible. Existing V1 recipes, ordinary Patch/cache identity, factory presets, bank and app player have no automatic V2 adoption. HOLD is refused by the new recipe and remains available through its existing policy.

Take0 enters the exact original arithmetic. Takes1..3 keep the carrier phase and all oscillator frequencies exact, while changing two modulator starting phases (.09/.31, .23/.07, .37/.19 turns). A strike colour of -.28, +.34 or -.12 changes transient FM index and bounded contact duration. This source is an explicit deterministic recipe policy, not noise at playback and not a gain or pitch variation. The current body/filter/house finish still applies.

Starting phase and transient constants are proposed shape choices. The new seven-method test source requires strict serialization/ownership, exact take0 vs ordinary/V1, four exact V1 finite hashes plus a legacy held canary, finite bounded final output, detected C2 pitch within50cents/confidence>=.3, deterministic replay and pairwise gain-removed residual above .025. That floor is an engineering regression criterion above the known quiet-seed failure, not an audibility threshold. These tests have not been compiled or executed in this authoring step. Native pitch/register behaviour, subjective identity and musical naturalness remain unqualified. Full ordinary suite, normal app, updated bank and physical-device gates also remain open.

## Why it is built this way

- Separate serialization keeps old saved instruments and accepted performances recoverable without reinterpretation.
- Modulator starting phase changes the excitation waveform before the existing formant body. It gives the repeat policy a stronger mechanism than a quiet noise seed.
- The root carrier and phase increments stay fixed so this policy is not authored as detuning or a new voice.
- The contact/index perturbation remains bounded. Its shape needs native measurements and owner listening before any bank or app adoption.
- Finite-only scope prevents an unmeasured new strike law from silently changing held loops and their seam contracts.

## The opt-in V2 instrument (2026-11-13)

V2 is now a saved instrument as well as a recipe. One setting,
`MurmurRoundRobinInstrumentSource.RoundRobinPolicy`, travels through every layer: the
instrument cell, the fixed-take package, the four-take bank and the shelf handle. It
defaults to `BREATH_V1`, and a V1 cell, package or bank is written exactly as before:
version 1, with no policy field, byte for byte. `CONTRAST_V2` writes version 2 and names
`NATIVE_OPERATOR_CONTACT_V2` in the bank manifest, in every package and in every cell,
including the cell's renderer binding. Neither format reads as the other:

- A V2 bank validated against a V1 spec is refused, and the reverse.
- A V2 manifest relabelled as version 1 is refused because its cells still name V2.
- A version-2 manifest that names the breath policy is refused, because version 1 is the
  breath policy's only spelling.
- A V2 cell for a held source is refused, as the recipe already refuses HOLD.

Cells of the two policies never share a cache key. The player, shelf, import and fixed-take
export read saved files only, so they need no change. A V2 bank goes through them unchanged.

### Measured from the saved player

`MurmurRoundRobinContrastBankTest` and `:shell:generateMurmurRoundRobinAudition` take the
whole path. They render native cells into a real bank, save it, admit it to the shelf,
reload it from PCM24 and play C2 at touch .65 through `MurmurRoundRobinPlayer`. Nothing is
re-rendered between the save and the sound.

- **Order:** the player deals takes 0, 1, 2, 3 and returns to the identical take 0.
- **Take 0:** V2's saved take 0 has the same finished Float hash as V1's take 0 cell.
- **Distinctness:** four isolated strikes, measured pairwise.

| Policy | Residual after level matching | Spectral distance (phase-blind) |
| --- | --- | --- |
| V1, breath seed | .0095 to .0109 | .0015 to .0020 |
| V2, operator/contact | .548 to .998 | .039 to .196 |

The residual is what remains of one take after the best single gain matches it to another.
The spectral distance compares Hann-windowed 2048-point power spectra every 512 frames over
the first second, with each strike normalised to unit power. It is half the summed absolute
difference, so a phase-only change barely moves it. The test's floors are .025 and .01. V1
sits below both and V2's closest pair is about four times the spectral floor. The floors are
regression criteria, not an audibility threshold.

### Still open

- **Owner listening: KEEP (2026-11-13).** Round `murmur-rr-1` on the private Listening Desk
  put V2 against the V1 reference, both played from saved banks by the saved-sample player.
  The owner marked KEEP on M2 (eight repeated C2s) and M4 (the four takes one at a time),
  with no notes. That replaces V1's REVISIT ("Didn't hear any variation") for the V2 policy.
  It is a listen to saved-player renders, not to a phone or an MPC.
- **App and phone:** the Android screens and the native round-robin player from the recovery
  snapshot are not ported yet. That is the next MURMUR change. No phone has played a V2 bank.
- **MPC export:** each take exports as its own fixed-take program. An MPC does not rotate
  them, which is the existing V1 limit.
