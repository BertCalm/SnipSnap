package com.snipsnap.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.snipsnap.shell.CardCopy
import java.io.File
import java.io.IOException

/**
 * The platform half of getting an export onto a picked card.
 *
 * [CardCopy] (`:shell`, tested) decides what to create and in what
 * order; this walks that plan through `DocumentsContract` and moves the
 * bytes. Everything here needs a real content provider, so none of it
 * runs off a device — which is the reason the deciding was moved out
 * rather than written inline.
 *
 * `DocumentsContract` and not `DocumentFile`: the wrapper would be a new
 * dependency for what is three calls, and the app already reaches for
 * `android.provider` directly elsewhere.
 *
 * **Overwrite is a delete, not a truncate.** A document API's "create"
 * with a name that exists hands back a *second* document called
 * `Kit (1).xpm` rather than replacing the first, and a card holding both
 * is worse than one holding neither. So an existing child of the same
 * name is removed first, and the copy is the only thing that ends up
 * there.
 */
object CardWriter {

    /**
     * Copies [items] — an export's own `primary` and `companion`, not the
     * folder they were written into — onto the picked [treeUri],
     * reporting bytes moved so far to [onProgress]. Returns the number of
     * files written.
     *
     * Refuses in words rather than half-writing where it can: a folder it
     * cannot create stops the copy, because everything under it would
     * land in the wrong place.
     */
    fun copy(
        context: Context,
        treeUri: Uri,
        items: List<File>,
        onProgress: (Long) -> Unit = {},
    ): Int {
        val resolver = context.contentResolver
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        // Folders already made, by their path from the copy's root. The
        // plan's parents-first order is what makes a lookup here always
        // hit: an entry's folder was created by an earlier entry.
        val made = HashMap<List<String>, Uri>()
        made[emptyList()] = rootDoc

        val plan = CardCopy.plan(items)
        // Asked to copy something and finding nothing is a failure, not an
        // empty success: the export's own output has gone between the
        // write and here (a card pulled, a cache cleared), and reporting
        // "it's on the card" for a card with nothing on it is the one
        // outcome worse than saying the dub failed.
        if (items.isNotEmpty() && plan.isEmpty()) {
            throw IOException("the export was gone before it reached the card")
        }

        // One listing per folder rather than one per entry. A kit's sample
        // folder is a hundred-odd files, and querying the whole folder
        // again for each of them is a hundred binder round-trips over a
        // card reader. Safe to snapshot because the plan names each path
        // once: nothing looks up a name this copy created.
        val listings = HashMap<String, MutableMap<String, String>>()

        var files = 0
        var moved = 0L
        for (entry in plan) {
            val parent = made[entry.parents]
                ?: throw IOException("could not put ${entry.name} on the card - its folder is missing")
            replaceExisting(context, parent, entry.name, listings)
            if (entry.isDirectory) {
                val dir = create(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, entry.name)
                made[entry.segments] = dir
                // A folder this copy just made holds nothing, so listing it
                // would be a round-trip to learn that. Seeded empty: without
                // this, a freshly created kit folder still costs one query
                // per sample in it.
                listings[DocumentsContract.getDocumentId(dir)] = HashMap()
            } else {
                val target = create(resolver, parent, CardCopy.mimeFor(entry.name), entry.name)
                // "w" and not "wt": the document was just created empty,
                // and some providers refuse the truncate mode outright.
                resolver.openOutputStream(target, "w")?.use { out ->
                    entry.source.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            moved += n
                            onProgress(moved)
                        }
                    }
                } ?: throw IOException("the card would not take ${entry.name}")
                files++
            }
        }
        return files
    }

    private fun create(
        resolver: android.content.ContentResolver,
        parent: Uri,
        mime: String,
        name: String,
    ): Uri = DocumentsContract.createDocument(resolver, parent, mime, name)
        ?: throw IOException("the card would not take $name")

    /**
     * Removes a child of [parent] called [name], if there is one, using
     * [listings] as a one-per-folder cache of what was there when the
     * copy started. Best effort by design: a provider that will not list
     * or will not delete leaves the create to do whatever it does, which
     * is no worse than not having tried.
     */
    private fun replaceExisting(
        context: Context,
        parent: Uri,
        name: String,
        listings: HashMap<String, MutableMap<String, String>>,
    ) {
        runCatching {
            val parentId = DocumentsContract.getDocumentId(parent)
            val existing = listings.getOrPut(parentId) { childNames(context, parent, parentId) }
            val docId = existing.remove(name) ?: return@runCatching
            val doc = DocumentsContract.buildDocumentUriUsingTree(parent, docId)
            DocumentsContract.deleteDocument(context.contentResolver, doc)
        }
    }

    /** What [parent] held when the copy reached it: display name to document id. */
    private fun childNames(context: Context, parent: Uri, parentId: String): MutableMap<String, String> {
        val out = HashMap<String, String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) out[c.getString(1)] = c.getString(0)
        }
        return out
    }
}
