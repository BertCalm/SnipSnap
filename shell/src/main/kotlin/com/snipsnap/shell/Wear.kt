package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.synth.TapeWear
import java.io.File

/**
 * Where the wear ledger meets the renderers. Wear is a render-time
 * recipe over pristine WAVs — nothing here ever rewrites a kit's audio,
 * which is exactly why wiping the ledger restores the new-tape sound.
 *
 * Two shapes of application:
 * - [render] wears a finished performance (a preview, a mixdown) as one
 *   continuous tape pass — flutter and dropouts ride the whole beat.
 * - [stageWorn] wears a kit folder's *files* into a disposable twin for
 *   export, so the card carries the aged sound while the kit keeps its
 *   pristine originals.
 */
object Wear {

    /** Deterministic per kit: the tape's identity seeds its scratches. */
    fun seed(kit: Kit): Int = kit.name.hashCode()

    /**
     * The wear this kit has earned, or null when its renders stay pristine
     * (no ledger, aging off, or a mileage of zero — a new tape).
     */
    fun earnedW(kit: Kit): Float? = kit.wear?.takeIf { it.enabled && it.w > 0f }?.w

    /** True when this kit's renders should age: opted in, mileage on the clock. */
    fun isAudible(kit: Kit): Boolean = earnedW(kit) != null

    /**
     * A rendered performance through wear [w] (default: the kit's earned
     * wear; a deliberate override can pass more). Identity when null.
     */
    fun render(kit: Kit, snip: Snip, w: Float? = earnedW(kit)): Snip =
        w?.let { TapeWear.process(snip, it, seed(kit)) } ?: snip

    /**
     * Stage a worn twin of the kit folder: every pad-referenced WAV
     * re-rendered through wear [w], every other file copied as-is, the
     * takes and bin left behind. Callers own [into]'s lifetime — export
     * from it, then delete it.
     */
    fun stageWorn(
        kit: Kit,
        kitDir: File,
        into: File,
        w: Float = requireNotNull(earnedW(kit)) { "kit has no earned wear to stage" },
    ): File {
        into.mkdirs()
        val wavs = kit.pads
            .flatMap { p -> listOf(p.sampleFile) + p.velocityLayers.map { it.sampleFile } }
            .toSet()
        for (f in kitDir.listFiles().orEmpty()) {
            if (f.isDirectory) continue // a kit's own files are flat; dirs are takes/bin
            if (f.name in wavs) {
                WavWriter.write(File(into, f.name), TapeWear.process(WavReader.read(f), w, seed(kit)))
            } else {
                f.copyTo(File(into, f.name), overwrite = true)
            }
        }
        return into
    }
}
