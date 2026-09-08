package com.snipsnap.shell

/**
 * Names from outside, made safe to write down.
 *
 * A share arrives with a display name the *sending* app chose. It is the
 * one string in the import path that a stranger controls completely, and
 * it is used to name a file we create — so it is a door, and it belongs
 * where there can be cases for it rather than inline in a service.
 *
 * It does not call `Names.sanitizeStem`, though that one is also
 * traversal-safe — it turns every separator into `_`, so nothing escapes
 * a folder through it either. The difference is what happens to a name
 * that was fine: `sanitizeStem` shapes stems for the MPC's own card, so
 * it drops a leading dot (`.hidden` becomes `hidden`), collapses runs
 * (`My__Loop.wav` becomes `My_Loop.wav`), flattens a path into the name
 * (`a/b` becomes `a_b`) and falls back to the word "Sample". A share
 * should arrive called what the sender called it, so this drops the
 * path instead of flattening it and leaves the rest of the filename
 * alone. Both are doors; they are pointed at different things.
 */
object Landing {

    /**
     * What does *not* survive — everything outside letters, digits, and
     * the punctuation a real filename carries. It is the replace pattern,
     * so it matches the characters that become underscores.
     */
    private val NOT_KEPT = Regex("[^A-Za-z0-9._ \\-\\[\\]]")

    /** The name a share gets when its own is unusable. */
    const val FALLBACK = "shared"

    /**
     * [displayName] reduced to a bare, plain filename. Any path in front
     * of it is dropped rather than escaped — a name is a name, and the
     * separators are the whole of the traversal trick. A result that is
     * empty, blank, or nothing but dots (`.` and `..` name the folder
     * and its parent) becomes [FALLBACK]; anything [NOT_KEPT] matches
     * becomes an underscore.
     *
     * The caller still proves the finished path sits inside the folder
     * it meant to write to. This is the first door, not the only one.
     */
    fun safeName(displayName: String): String {
        val bare = displayName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = bare.replace(NOT_KEPT, "_")
        return if (cleaned.isBlank() || cleaned.all { it == '.' }) FALLBACK else cleaned
    }
}
