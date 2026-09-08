package com.snipsnap.shell

/**
 * Names from outside, made safe to write down.
 *
 * A share arrives with a display name the *sending* app chose. It is the
 * one string in the import path that a stranger controls completely, and
 * it is used to name a file we create — so it is a door, and it belongs
 * where there can be cases for it rather than inline in a service.
 *
 * This is not [Names.sanitizeStem]'s job and does not call it: that one
 * shapes stems for the MPC's own card and strips `[` and `]`, which a
 * share must keep — `Kit_[TrackData].zip` is exactly the sort of thing
 * people send, and renaming it would break the importer that reads it.
 * The two want different alphabets for good reasons.
 */
object Landing {

    /** What survives: letters, digits, and the punctuation a real filename carries. */
    private val KEPT = Regex("[^A-Za-z0-9._ \\-\\[\\]]")

    /** The name a share gets when its own is unusable. */
    const val FALLBACK = "shared"

    /**
     * [displayName] reduced to a bare, plain filename. Any path in front
     * of it is dropped rather than escaped — a name is a name, and the
     * separators are the whole of the traversal trick. A result that is
     * empty, blank, or nothing but dots (`.` and `..` name the folder
     * and its parent) becomes [FALLBACK]; anything left outside [KEPT]
     * becomes an underscore.
     *
     * The caller still proves the finished path sits inside the folder
     * it meant to write to. This is the first door, not the only one.
     */
    fun safeName(displayName: String): String {
        val bare = displayName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = bare.replace(KEPT, "_")
        return if (cleaned.isBlank() || cleaned.all { it == '.' }) FALLBACK else cleaned
    }
}
