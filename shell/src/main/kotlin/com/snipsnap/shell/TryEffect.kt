package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.io.File

/**
 * Try before you keep (`docs/UX_PASS_2026_11.md`, fix e; the owner chose "hear it, then KEEP"
 * on 2026-11-16). A tap on an effect in the pad's list used to change the pad at once. Now it
 * lets you hear the effect first, and the pad stays as it was until KEEP.
 *
 * The preview runs the very same landing an apply runs (the restore, the stacking and refusal
 * rules, the render), but on a scratch copy of the kit. So what you hear is what KEEP will
 * give, and nothing in the real kit, its bin included, is written. A kit's bin lives inside
 * its folder (`KitBuilderModel.BIN_DIR`), so the copy answers every "is the original in the
 * bin?" question the way the real kit would.
 */
object TryEffect {
    /** Where the scratch copies go, under the app's cache. */
    const val SCRATCH_DIR = "try-effect"

    /**
     * Copies [kitDir] to `<scratchRoot>/<the kit's folder name>`, opens a model on the copy, runs
     * [land] on it, and returns pad [slot]'s main sound as the copy now holds it. Null when [land]
     * changed nothing (a refusal it handled by changing nothing, the way `withFreshKit` reads
     * it) or the slot is empty afterwards. The copy is deleted before this returns, thrown or
     * not; [kitDir] is only read.
     */
    fun hear(kitDir: File, scratchRoot: File, slot: Int, maxDurationSec: Float, land: (KitBuilderModel) -> Unit): Snip? {
        val scratch = File(scratchRoot, kitDir.name)
        scratch.deleteRecursively()
        try {
            kitDir.copyRecursively(scratch)
            val copy = KitBuilderModel.open(scratch)
            land(copy)
            if (!copy.dirty) return null
            copy.save()
            val pad = copy.kit.pad(slot) ?: return null
            return WavReader.readCapped(File(scratch, pad.sampleFile), maxDurationSec).snip
        } finally {
            scratch.deleteRecursively()
        }
    }
}
