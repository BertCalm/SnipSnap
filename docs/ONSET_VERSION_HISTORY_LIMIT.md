# Existing VERSIONS metadata/audio mismatch after an in-place rewrite

Status: verified by source review and one failing JVM lifecycle test. Not fixed in the native ONSET change. This concerns generic kit history, rather than ONSET's renderer, saved patch decoder, or standalone kit sidecar.

Repository base: `83546a61731ab24fbaeb4f76ef91ebb0beaade98`, with the native ONSET working changes. A focused `OnsetNativeLifecycleTest` run on 2026-11-03 failed its original exact-PCM assertion and exposed the generic history path below.

Source comparison on 2026-11-06 confirms the same ordinary-pad behavior in current upstream `e634076db9fdf5a0ca9a8aacbbbcb5193c7bd3d4`. Its additional Robin-chain handling does not apply to an ordinary ONSET pad. The measured failure below remains the November 3 reproduction, rather than a new device or JVM run.

## Reproduction

1. Create a kit with a model-4 ONSET TOM patch from `OnsetPresets.extensionStudy()`. Save its complete `PadRecipe`, including `FxChain(reverse = true)`, treatment, amount, alias, and an 8 ms terminal fade. Save the kit through `KitBuilderModel.save()`.
2. Open the saved kit, recover the typed recipe through `Breed.recipeOf`, and change `BLEND` to `.37f` and `DECAY` to `.81f` with `Patches.edited`. Keep the original processing fields.
3. Call `RecipeReplay.apply` on the same slot, then `save()`. The application uses the same in-place `replaceAudio` followed by `save` when a reopened SYNTH pad is updated.
4. Select the most recent take created by that save, reopen the kit, call `restoreTake(take)`, and save again.
5. Read the restored recipe and sample. The recipe equals the original, but the sample still contains the edited render.

Measured first failure: the restored TOM WAV was **111,020 bytes**, while the saved original was **75,464 bytes**. The original exact comparison failed with `Array sizes differ`. These lengths represent 36,992 versus 25,140 mono PCM-24 frames with a 44-byte WAV header. The assertion compared actual bytes, rather than a listening judgement.

## Why it happens

- [`KitBuilderModel.replaceAudio`](../shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt#L648) moves the previous WAV into the bin, then writes the new WAV to the same sample filename (`658`–`659`). The source's current-file hash is correctly dropped.
- [`RecipeReplay.apply`](../shell/src/main/kotlin/com/snipsnap/shell/RecipeReplay.kt#L219) uses that production replacement door for a synth recipe.
- [`SynthScreen.sendToSlot`](../app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt) likewise uses `model.replaceAudio`, then saves the kit. This is not a fixture writing directly around the application protocol.
- [`save`](../shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt#L1181) calls `archiveTake` before writing the new kit metadata.
- [`archiveTake`](../shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt#L1367) copies the **old on-disk `kit.json`**, which still names the original recipe. However, it creates that take **after the WAV rewrite**, and explicitly moves its timestamp later than every existing bin event (`1380`–`1382`).
- [`restoreLiveAudioAsOf`](../shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt#L1342) chooses the oldest bin entry with `binnedAtMillis >= take.lastModified()`. The original audio was binned before that take timestamp, so it is excluded. The live edited sample remains in place while the original recipe is restored from JSON.

There are no hard links in this path. The mismatch comes from combining an outgoing metadata snapshot with the later audio-history timestamp.

## Expected and actual

Expected: an archived version's typed recipe and sample describe the same saved sound. Restoring the original recipe restores its original PCM as well.

Actual for the automatically archived outgoing take after UPDATE: original recipe and pad metadata, edited PCM. A recipe round trip alone therefore cannot establish VERSIONS audio fidelity.

Existing generic `KitBuilderTest` history tests expect audio that was live at archive time. Their treatment examples do not also assert that the outgoing JSON recipe describes that same audio, so those tests do not settle this inconsistency.

## Bounded ONSET validation

The revised native lifecycle test performs an actual metadata-only operation, setting the kit's groove tempo to 104 BPM and saving it **before** the audio UPDATE. That save archives an outgoing version while its original WAV is still live. The test later restores this specifically recorded pre-UPDATE version, retaining the exact original-PCM assertion, complete typed recipe equality, source-target guard, FX/treatment/amount/alias/terminal-fade checks, and all UPDATE metadata checks.

This is a valid existing version-history path. It is **not** a fix or proof for the automatic outgoing take created only after the audio rewrite. The test is named to state which version it restores.

## Follow-up boundary

A general fix needs one coherent definition of a version's metadata and audio moment, including same-millisecond writes, multiple edits before save, rollback after further edits, and history retention. Merely moving one timestamp changes the existing generic history semantics. Automatically regenerating every typed recipe is also unsafe: a retained recipe can coexist with later destructive PCM operations, and source-backed recipes may have dependencies. Neither change belongs silently in this native ONSET integration.
