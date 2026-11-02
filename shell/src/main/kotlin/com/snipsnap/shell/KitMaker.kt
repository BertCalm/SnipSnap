package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.Names
import com.snipsnap.synth.KitFromAnything

/**
 * The PAD SHEET's KIT FROM ANYTHING card, as data: the three genres, where
 * the key opens, the kit's name and the two lengths the assembler refuses.
 * Stateless, the way [PadMaker] is; the card draws it, [KitFromAnything]
 * does the work.
 */
object KitMaker {

    /** The genre chips, in the order the assembler declares them. */
    val GENRES: List<KitFromAnything.Genre> = KitFromAnything.Genre.entries

    /** The chip's word for a genre. */
    fun genreLabel(genre: KitFromAnything.Genre): String = genre.label.uppercase(java.util.Locale.ROOT)

    /** Genre the card opens on: the CLI's default. */
    val DEFAULT_GENRE: KitFromAnything.Genre = KitFromAnything.Genre.HOUSE

    /**
     * Where the card's key opens when the kit has none: A minor, the KEY
     * panel's own default ([KeyPicker.DEFAULT_ROOT], [KeyPicker.DEFAULT_SCALE]).
     */
    val DEFAULT_KEY: KeySpec = KeyPicker.key(KeyPicker.DEFAULT_ROOT, KeyPicker.DEFAULT_SCALE)

    /** The key the card opens on: the kit's own when it has one. */
    fun openingKey(kitKey: KeySpec?): KeySpec = kitKey ?: DEFAULT_KEY

    /** Longest pad-name stem a kit name keeps, so "<stem> Kit" stays a name that fits a shelf row. */
    const val STEM_MAX = 24

    /** An MPC-safe base name for the kit made from the pad called [padName]; the shelf makes it fresh. */
    fun kitName(padName: String): String {
        val stem = Names.sanitizeStem(padName).take(STEM_MAX).trim(' ', '_', '.').ifBlank { "Sample" }
        return "$stem Kit"
    }

    /** A line for a pad whose audio the assembler would refuse, or null when it takes it. */
    fun refusal(seconds: Float): String? = when {
        seconds < KitFromAnything.MIN_SOURCE_SECONDS -> Copy.KIT_TOO_SHORT
        seconds > KitFromAnything.MAX_SOURCE_SECONDS -> Copy.KIT_TOO_LONG
        else -> null
    }
}
