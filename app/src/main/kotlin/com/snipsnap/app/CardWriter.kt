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
 * Every URI here is built from the **tree** URI the user picked, and only
 * the `…UsingTree` builders are used. Both points are deliberate. Those
 * builders document their first argument as a tree URI, and while a
 * document URI derived from one happens to carry the tree segment that
 * makes it work, that is an implementation detail to lean on rather than
 * a contract. And the non-tree `buildDocumentUri` is not the fix it looks
 * like: what the app holds is a *tree* grant, so a bare document URI
 * built outside the tree is one it has no permission for.
 *
 * **Overwrite is a delete, not a truncate.** A document API's "create"
 * with a name that exists hands back a *second* document called
 * `Kit (1).xpm` rather than replacing the first, and a card holding both
 * is worse than one holding neither. So an existing child of the same
 * name is removed first.
 *
 * That removal is best effort, so the honest promise is narrower than
 * "the copy is the only thing there": a provider that will not list, or
 * will not delete, leaves the create to do whatever it does — which is
 * the `Kit (1).xpm` outcome, no worse than not having tried. Failing a
 * whole dub because a card would not delete would trade a duplicate for
 * nothing at all.
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
    ): Copied {
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
        var replaced = 0
        var contested = 0
        var moved = 0L
        for (entry in plan) {
            val parent = made[entry.parents]
                ?: throw IOException("could not put ${entry.name} on the card - its folder is missing")
            when (replaceExisting(context, treeUri, DocumentsContract.getDocumentId(parent), entry.name, listings)) {
                Replace.REPLACED -> replaced++
                Replace.CONTESTED -> contested++
                Replace.NONE -> Unit
            }
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
        return Copied(files, replaced, contested)
    }

    /**
     * What [copy] would land on if it ran now — the same question the
     * phone leg answers with `WriteResult.WouldOverwrite`, asked of the
     * card (persona review P2.3).
     *
     * **Writes nothing.** It lists folders and nothing else, so it is safe
     * to call before the user has agreed to anything, which is the whole
     * point: the copy's own overwrite is a delete, and until this existed
     * the only report of one came after it had happened.
     *
     * Costs one directory query per folder of the plan **that the card
     * actually has** — `CardCopy.collisions` stops descending the moment a
     * folder is missing or collides, and this memoizes what it did read,
     * so a card with none of the export on it costs a single query of the
     * root.
     *
     * Returns empty on a provider that will not list, rather than
     * throwing or guessing: unknown reads as nothing in the way, the dub
     * proceeds exactly as it did before this existed, and
     * [Copied.replaced]/[Copied.contested] report it afterwards. A card
     * that cannot be read is a reason to fall back to the old behaviour,
     * never to refuse a dub or to invent a warning.
     */
    fun preflight(context: Context, treeUri: Uri, items: List<File>): List<CardCopy.Entry> = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        // Keyed by a path from the copy's root, so it answers the question
        // `collisions` asks in its own terms rather than in document ids.
        // A null value is a real answer - "the card does not have this
        // folder" - and is memoized as one, which is why this reads
        // `containsKey` rather than `getOrPut`: the latter treats a stored
        // null as absent and would re-query a missing folder for every
        // entry that asked about it.
        val listed = HashMap<List<String>, Map<String, String>?>()

        // Recursive on a strictly shorter path, bottoming out at the tree
        // root, whose id is the one the grant itself names.
        fun listing(path: List<String>): Map<String, String>? {
            if (listed.containsKey(path)) return listed[path]
            val id = if (path.isEmpty()) rootId else listing(path.dropLast(1))?.get(path.last())
            val names = id?.let { childNames(context, treeUri, it) }
            listed[path] = names
            return names
        }

        CardCopy.collisions(CardCopy.plan(items)) { path -> listing(path)?.keys }
    }.getOrDefault(emptyList())

    /**
     * What one card copy did.
     *
     * [replaced] and [contested] exist because the copy always writes over
     * a same-named file and used to say nothing about it: a caller could
     * not tell a clean write from one that landed on top of somebody's
     * earlier export, nor either of those from a provider that refused the
     * delete and left the create to do whatever it does. The information
     * was already being computed here and thrown away.
     */
    data class Copied(val files: Int, val replaced: Int, val contested: Int)

    /** What [replaceExisting] found, and whether it could clear it. */
    private enum class Replace { NONE, REPLACED, CONTESTED }

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
        treeUri: Uri,
        parentId: String,
        name: String,
        listings: HashMap<String, MutableMap<String, String>>,
    ): Replace = runCatching {
        val existing = listings.getOrPut(parentId) { childNames(context, treeUri, parentId) }
        val docId = existing.remove(name) ?: return@runCatching Replace.NONE
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        if (DocumentsContract.deleteDocument(context.contentResolver, doc)) {
            Replace.REPLACED
        } else {
            Replace.CONTESTED
        }
        // A throw here means the listing or the delete failed and there is
        // no way to know what was on the card - reported as CONTESTED
        // rather than NONE on purpose, because "there may be doubles" is
        // the answer that is safe to be wrong about.
    }.getOrDefault(Replace.CONTESTED)

    /** What the folder held when the copy reached it: display name to document id. */
    private fun childNames(context: Context, treeUri: Uri, parentId: String): MutableMap<String, String> {
        val out = HashMap<String, String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
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
