package com.snipsnap.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * The share sheet's other direction (F6.3, X3.3): a file the app made,
 * handed to whatever the user picks — a messenger, Drive, the Files app,
 * a cable to a laptop.
 *
 * Files to share are written under `cacheDir/share/` and served through
 * the manifest's `FileProvider` (authority `<package>.files`, paths in
 * `res/xml/share_paths.xml`): a content URI with a read grant, never a
 * raw path, which Android has refused to hand between apps since 7.
 * The cache is the right home — a packed kit is a copy the system may
 * reclaim, and the shelf's own folders are never exposed.
 */
object ShareOut {

    /** Where packed kits and backups are written before they leave. */
    fun shareDir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    /**
     * Offer [file] through the system chooser as [mime]. [title] heads
     * the chooser. Returns false when no app on the phone would take it.
     */
    fun send(context: Context, file: File, mime: String, title: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            // The grant travels on the ClipData as well as the extra: some
            // receivers read the URI from there, and the grant is what lets
            // them open it.
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        }
    }

    /** The MIME an `.xpn` or a backup travels as: a ZIP, which every messenger accepts. */
    const val ZIP_MIME = "application/zip"
}
