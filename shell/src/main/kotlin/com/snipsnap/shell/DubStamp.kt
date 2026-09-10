package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import java.io.File

/**
 * What a kit's last dub left behind, and what the shelf may honestly say
 * about it.
 *
 * September UAT, finding 15: every kit read DRAFT forever, because nothing
 * anywhere recorded that a dub had happened. [ExportOutcome] lives only for
 * as long as the completion screen is mounted, and `kit.json` has no field
 * for it. A shelf of twenty kits gave a returning user no way to tell which
 * were already written out.
 *
 * **Why a sidecar in the kit's own folder** rather than a preference keyed by
 * the kit's name: `KitShelf.renameKit` *moves the directory*, so any key held
 * outside would be orphaned by a rename, and the same is true of a restore
 * from the bin. A file inside the folder travels with it for free, with no
 * migration to remember and forget.
 *
 * **Why not `kit.json`**: this is not part of what a kit *is*. Sharing a kit
 * should not tell the recipient which card someone else's phone wrote it to.
 * `KitShelf.pack` rebuilds an export from the kit's contents rather than
 * copying the folder, so a sidecar does not ride along — and [status] below
 * refuses to claim ON CARD for a card this device is not currently holding,
 * so even a stamp that arrived from elsewhere reads honestly.
 */
object DubStamp {

    /** The sidecar's name. Leading dot: the shelf lists directories holding a `kit.json`, never this. */
    const val FILE = ".dub.json"

    private const val VERSION = 1

    /**
     * One dub: when it happened, and the card it reached.
     *
     * [cardTree] is the SAF tree uri the export was copied to, or null when
     * the dub only reached the app's own export folder. Storing the uri
     * rather than a bare "yes" is what lets [status] tell "on the card in
     * your phone right now" from "on a card, once, somewhere".
     */
    data class Stamp(val atMillis: Long, val cardTree: String?)

    /** What the shelf chip says. Three states, because the app can honestly tell three apart. */
    enum class Status {
        /** No dub was ever recorded for this kit. */
        DRAFT,

        /** Dubbed, but not to the card this device currently holds — or not to a card at all. */
        DUBBED,

        /** Dubbed to the card this device is holding right now. */
        ON_CARD,
    }

    /**
     * [stamp] read against the card currently held ([currentCardTree], the
     * SAF tree uri EXPORT remembers, or null when none is).
     *
     * ON CARD is deliberately the narrowest claim. A stamp naming a card the
     * user has since forgotten, swapped, or never had — including one that
     * arrived inside a kit from somebody else's phone — reads DUBBED, not ON
     * CARD. The chip may under-claim; it must never tell someone their work
     * is safely on a card that is not there.
     */
    fun status(stamp: Stamp?, currentCardTree: String?): Status = when {
        stamp == null -> Status.DRAFT
        stamp.cardTree != null && stamp.cardTree == currentCardTree -> Status.ON_CARD
        else -> Status.DUBBED
    }

    /** The stamp [kitDir] carries, or null when it has none — or one this version cannot read. */
    fun read(kitDir: File): Stamp? {
        val file = File(kitDir, FILE)
        if (!file.isFile) return null
        val obj = runCatching { Json.parse(file.readText(Charsets.UTF_8)) as? JsonValue.Obj }.getOrNull() ?: return null
        // A torn write, a hand-edit, a future version: a stamp that cannot be
        // read is no stamp. The kit reads DRAFT, which under-claims rather
        // than throwing on a screen that is only trying to draw a list.
        val at = (obj.entries["atMillis"] as? JsonValue.Num)?.value?.toLong() ?: return null
        val card = (obj.entries["cardTree"] as? JsonValue.Str)?.value
        return Stamp(at, card?.takeIf { it.isNotBlank() })
    }

    /** Record a dub on [kitDir]. Atomic, like every other sidecar this app writes. */
    fun write(kitDir: File, stamp: Stamp) {
        val m = linkedMapOf<String, JsonValue>(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "atMillis" to JsonValue.Num(stamp.atMillis.toDouble()),
        )
        if (stamp.cardTree != null) m["cardTree"] = JsonValue.Str(stamp.cardTree)
        AtomicFile.writeText(File(kitDir, FILE), Json.write(JsonValue.Obj(m)))
    }
}
