package com.snipsnap.xpm

/**
 * How a keygroup program's notes share voices on the MPC.
 *
 * - [POLY]: chords ring; every note gets its own voice.
 * - [LEAD]: one voice. A new note takes over, and re-pressing a held key
 *   restarts the sample.
 * - [GLIDE]: [LEAD], and a note played while another is held slides to the
 *   new pitch instead of restarting.
 */
enum class VoiceMode(val mono: Boolean, val legato: Boolean, val retrigger: Boolean) {
    POLY(false, false, false),
    LEAD(true, false, true),
    GLIDE(true, true, false);

    /** Voice limit written to the program: 1 when mono, 0 (unlimited) otherwise. */
    val polyphony: Int get() = if (mono) 1 else 0

    /** Portamento time in seconds; only a legato mode glides. */
    val portamentoSeconds: Float get() = if (legato) GLIDE_PORTAMENTO else 0f

    companion object {
        const val GLIDE_PORTAMENTO = 0.1f

        /** Exact enum name, anything else (including null) is [POLY]. */
        fun parse(name: String?): VoiceMode = entries.firstOrNull { it.name == name } ?: POLY
    }
}
