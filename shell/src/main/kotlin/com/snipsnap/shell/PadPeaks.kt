package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import java.io.File

/**
 * The KIT grid's mini-waveforms (W12): one short row of peak columns per
 * pad, computed once per kit off the main thread and kept as nothing
 * more than the columns — the pyramid and the samples behind it are
 * dropped the moment the columns are read, so a 32-pad kit costs a few
 * kilobytes on screen, not its audio twice over.
 *
 * A pad whose file is missing or won't read is simply absent from the
 * map: the cell draws no waveform and nothing else changes. That is the
 * honest state for a torn kit, and cheaper than a refusal nobody asked
 * for on a screen that is only glancing.
 */
object PadPeaks {

    /** Columns per pad: enough for a shape, few enough that a 4×4 grid draws in one blink. */
    const val COLUMNS = 44

    fun forKit(kit: Kit, dir: File, columns: Int = COLUMNS): Map<Int, List<PeaksPyramid.Column>> {
        require(columns > 0) { "columns must be positive: $columns" }
        val out = HashMap<Int, List<PeaksPyramid.Column>>(kit.pads.size)
        for (pad in kit.pads) {
            val file = File(dir, pad.sampleFile)
            val snip = runCatching { WavReader.read(file) }.getOrNull() ?: continue
            if (snip.frameCount == 0) continue
            out[pad.slot] = PeaksPyramid.fromSnip(snip).columns(0, snip.frameCount, columns)
        }
        return out
    }
}
