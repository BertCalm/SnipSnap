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

        var files = 0
        var moved = 0L
        for (entry in CardCopy.plan(items)) {
            val parent = made[entry.parents]
                ?: throw IOException("could not put ${entry.name} on the card - its folder is missing")
            replaceExisting(context, parent, entry.name)
            if (entry.isDirectory) {
                made[entry.segments] = create(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, entry.name)
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
     * Removes a child of [parent] called [name], if there is one. Best
     * effort by design: a provider that will not list or will not delete
     * leaves the create below to do whatever it does, which is no worse
     * than not having tried.
     */
    private fun replaceExisting(context: Context, parent: Uri, name: String) {
        runCatching {
            val parentId = DocumentsContract.getDocumentId(parent)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) {
                        val doc = DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
                        DocumentsContract.deleteDocument(context.contentResolver, doc)
                        return@use
                    }
                }
            }
        }
    }
}
