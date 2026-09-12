package com.snipsnap.app

import android.content.Context
import java.io.File

/**
 * Where a finished export lands, named once.
 *
 * `getExternalFilesDir("exports")` was typed out in four places — EXPORT's own
 * writer, GROOVE's two `.mid` doors, and SETUP's line showing the path — with
 * nothing connecting them. That is the shape that had SNIPS reading a
 * directory nothing wrote for five days, and it is worse here than it looks:
 * three of those four are writers, so a rename that missed one would scatter a
 * user's exports across two folders and leave the fourth showing them the
 * wrong place to look.
 *
 * `ShareOut` hands these out through the FileProvider rather than copying
 * them, and `StorageSweep` deliberately leaves them alone — an export is the
 * user's file, not app-internal cruft — so this folder is read by more of the
 * app than writes it.
 */
object Exports {

    /** The folder's name under the app's external files directory. */
    const val DIR = "exports"

    /**
     * The exports folder, or null when external storage is unavailable — the
     * same null `getExternalFilesDir` has always returned, passed through
     * rather than swallowed. A caller with somewhere else to fall back to
     * (SETUP's path line falls back to `filesDir`) can still decide for
     * itself; a writer has to refuse, because there is nowhere to write.
     */
    fun dir(context: Context): File? = context.getExternalFilesDir(DIR)
}
