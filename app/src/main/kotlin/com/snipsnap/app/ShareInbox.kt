package com.snipsnap.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The share sheet's doorstep (F3.1): what another app handed us, held
 * until the composition picks it up.
 *
 * [MainActivity] offers every intent it receives — the one it was
 * created with and every `onNewIntent` after (the activity is
 * `singleTask`, so a share while the app is open lands in the running
 * instance instead of stacking a second one). [App] collects [pending],
 * imports the file, and [consume]s it, so a recomposition or a rotation
 * never imports the same share twice.
 *
 * Only `SEND` with a stream and `VIEW` with a data URI count; the launcher's
 * `MAIN` and everything else offer nothing.
 */
object ShareInbox {

    private val _pending = MutableStateFlow<Uri?>(null)

    /** The shared file waiting to be imported, or null. */
    val pending: StateFlow<Uri?> = _pending.asStateFlow()

    /** Reads a share out of [intent], if it is one; returns whether it was. */
    fun offer(intent: Intent?): Boolean {
        val uri = uriOf(intent) ?: return false
        _pending.value = uri
        return true
    }

    /** The import is done (or refused): nothing waits any more. */
    fun consume() {
        _pending.value = null
    }

    /** The file's own name as the sharing app knows it, else the URI's last segment, else a stand-in. */
    fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) c.getString(col) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared"
    }

    /** The first [count] bytes of the share, fewer when the file is shorter, for sniffing its kind. */
    fun head(context: Context, uri: Uri, count: Int): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("the shared file could not be opened")
        return stream.use { s ->
            val b = ByteArray(count)
            var n = 0
            while (n < count) {
                val r = s.read(b, n, count - n)
                if (r < 0) break
                n += r
            }
            b.copyOf(n)
        }
    }

    /**
     * A local copy of the share under `cacheDir/landing/`, named after the
     * file (flattened to a bare, plain name), bounded at [maxBytes] - the
     * shelf's importers want a real file, and a content URI is not one.
     */
    fun copyToCache(context: Context, uri: Uri, displayName: String, maxBytes: Long): File {
        val dir = File(context.cacheDir, "landing").apply { mkdirs() }
        val bare = displayName.substringAfterLast('/').substringAfterLast('\\')
        val safe = bare.replace(Regex("[^A-Za-z0-9._ \\-\\[\\]]"), "_")
            .let { if (it.isBlank() || it.all { c -> c == '.' }) "shared" else it }
        val out = File(dir, safe)
        // "." or ".." would have named the folder or its parent; the fallback
        // above catches those, and this proves the file sits in the landing dir.
        require(out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "the shared file's name escapes the cache" }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("the shared file could not be opened")
        try {
            stream.use { src ->
                out.outputStream().use { dst ->
                    com.snipsnap.mpc3.LimitedRead.copy(src, dst, limit = maxBytes, what = "the shared file")
                }
            }
        } catch (e: Exception) {
            // A copy that failed part-way (too big, or the provider died mid-stream)
            // must not leave a half-file behind to confuse the next share of the same name.
            out.delete()
            throw e
        }
        return out
    }

    private fun uriOf(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> streamOf(intent)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
