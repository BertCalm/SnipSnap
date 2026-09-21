package com.snipsnap.shell

import com.snipsnap.kit.Kit
import java.io.File

/**
 * The paper that goes in the case: a kit's J-Card and its liner notes,
 * written together because they are one thing to a reader and were one
 * thing to whoever thought of them.
 *
 * An expansion is the format that lands in the MPC's own browser as a
 * tile with a name and a picture, so it is the one export that is
 * presented rather than merely readable — hence the inserts, and hence
 * only here. The other seven formats are a program and its samples.
 *
 * **This exists because the pair was written in one place and not the
 * other.** `snipsnap export --expansion` put both files in the expansion
 * folder; the phone's EXPORT wrote neither, so the same kit in the same
 * format arrived with its story or without it depending on which machine
 * made it. That is the shape of defect this repo keeps finding — one
 * quantity in two places, here by being in one place and absent from the
 * second — so the pair moved here and both callers ask for it by name.
 *
 * The catalog number is read once, from the kit's own crate
 * ([Label.forKit]), and handed to both: a J-Card and a set of liner notes
 * that disagreed about the catalog would be worse than neither carrying
 * one.
 */
object Inserts {

    /** What [write] puts in the folder, in the order it writes them. */
    const val J_CARD_NAME = "J-Card.png"

    /**
     * Writes the J-Card and the liner notes into [into] — an expansion's
     * own folder, the `primary` of an `ExportFormat.EXPANSION` outcome —
     * and returns the files written, outermost first.
     *
     * Never throws for want of a catalog number: a kit outside a crate
     * has none, both inserts say so by leaving it off, and that is an
     * ordinary kit rather than an error.
     */
    fun write(kit: Kit, kitDir: File, into: File): List<File> {
        val catalog = Label.forKit(kitDir)
        val jCard = File(into, J_CARD_NAME)
        jCard.parentFile?.mkdirs()
        jCard.writeBytes(JCard.png(kit, kitDir, catalog = catalog))
        val notes = LinerNotes.writeTo(kit, kitDir, File(into, LinerNotes.FILE_NAME), catalog)
        return listOf(jCard, notes)
    }
}
