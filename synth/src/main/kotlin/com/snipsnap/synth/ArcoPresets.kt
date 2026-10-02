package com.snipsnap.synth

/**
 * ARCO's factory roster: eight per voice, authored from the measurements in [Arco]'s KDoc and from what the
 * real classifier says of each render - and **provisional**: nothing here was listened to. The audition gate is
 * where a human decides which of these is a bowed string and which is only a tone that measures like one; a name
 * that does not survive it is renamed or dropped, not defended.
 *
 * What the roster leans on:
 *  - TUNE is a fraction of the voice's own span: CELLO's is 24 semitones from C2, ERHU's is 19 from D4, so a
 *    note is k over 24 or k over 19 and the comment above each preset says which note it lands on.
 *  - A CELLO note below about G#2 starts in a scratch that takes 0.5 to 0.9 s to lock into one slip a period,
 *    so a short stab down there is a scrape, not a clean cello: DRY SCRAPE and GRIT BOW sit there on purpose, and
 *    the clean low presets sit at G#2 and above. ERHU locks in 0.1 to 0.4 s everywhere, so its low presets are clean.
 *  - HOLD stays under the classifier's 1.5 s line on every preset but the two loops (the line is crossed at HOLD 0.447
 *    at C2 and C3; the longest CELLO notes here, HOLD 0.43, render 1.44 s), and a loop is HOLD 1.0, dry and seamless.
 *  - The classifier reads the spectrum of the first 93 ms of a render. A slow bow's first 93 ms is its slow swell, which
 *    reads as a low note: TONAL if the note then rings more than 500 ms past its peak, a KICK or a TOM if it does not.
 *    So every slow-bow preset (BOW 0.3 or under) has a HOLD of 0.42 or more, and every other one-shot has a BOW of
 *    0.45 or more. A loop is the exception: it discards its stroke, so its BOW (ENDLESS DRAW's is 0.4) is not heard.
 *  - The top of ERHU's range is bright enough that a hard bow on a thin box puts over half of that head above 2 kHz,
 *    which reads as a SNARE. Up there only a swell-in (TONAL) or a box of half or more keeps a note out of the drums,
 *    and HIGH CRY swells in.
 *
 * ArcoPresetsTest holds all of this to the real classifier, with the room each reading has to its line printed.
 *
 * Names are plain words: no maker, no model, no engine's or rack section's name.
 */
object ArcoPresets {

    private fun p(voice: ArcoVoice, name: String, vararg macros: Pair<String, Float>) = ArcoPatch(name, voice, macros.toMap())

    fun forVoice(voice: ArcoVoice): List<ArcoPatch> = when (voice) {
        ArcoVoice.CELLO -> celloPresets
        ArcoVoice.ERHU -> erhuPresets
    }

    fun all(): List<ArcoPatch> = ArcoVoice.entries.flatMap { forVoice(it) }

    private val celloPresets = listOf(
        // D3 (14 of 24), the open D string: the slowest stroke (400 ms to full speed) into a 0.9 s bow, a long lyrical note.
        p(ArcoVoice.CELLO, "SLOW BOW", "TUNE" to 0.583f, "BOW" to 0.0f, "GRIP" to 0.7f, "BODY" to 0.5f, "HOLD" to 0.43f),
        // A3 (21 of 24), the open A string: a hard bow with a bite and 0.3 s of it, a spiccato accent that locks in 0.19 s.
        p(ArcoVoice.CELLO, "SHORT STAB", "TUNE" to 0.875f, "BOW" to 0.9f, "GRIP" to 0.7f, "BODY" to 0.4f, "HOLD" to 0.0f),
        // A2 (9 of 24): a dark, boxy pedal tone under a pad, locking at 0.47 s of its 0.92 s of bow.
        p(ArcoVoice.CELLO, "DEEP PEDAL", "TUNE" to 0.375f, "BOW" to 0.5f, "GRIP" to 0.4f, "BODY" to 0.8f, "HOLD" to 0.43f),
        // F#2 (6 of 24), under G#2: a hard bite that starts in a scratch and only locks into the note after 0.4 s of its 0.6.
        p(ArcoVoice.CELLO, "GRIT BOW", "TUNE" to 0.25f, "BOW" to 0.85f, "GRIP" to 0.8f, "BODY" to 0.3f, "HOLD" to 0.25f),
        // E2 (4 of 24), the string alone (BODY 0): a stab too low to lock, so it never leaves the scratch; a scrape for the percussion pads.
        p(ArcoVoice.CELLO, "DRY SCRAPE", "TUNE" to 0.167f, "BOW" to 1.0f, "GRIP" to 0.5f, "BODY" to 0.0f, "HOLD" to 0.05f),
        // A#2 (10 of 24): the whole box (BODY 1) under a 0.13 s bow-in that settles by 0.4 s, as long as a one-shot may be.
        p(ArcoVoice.CELLO, "CINEMA LOW", "TUNE" to 0.417f, "BOW" to 0.3f, "GRIP" to 0.5f, "BODY" to 1.0f, "HOLD" to 0.43f),
        // C4 (24 of 24), the top of the span: a bright, light-boxed bow where the hair is heard (GRIP 0.85, BODY 0.2).
        p(ArcoVoice.CELLO, "HORSEHAIR", "TUNE" to 1.0f, "BOW" to 0.7f, "GRIP" to 0.85f, "BODY" to 0.2f, "HOLD" to 0.28f),
        // G2 (7 of 24), the open G string, HOLD 1: the endless bow, a steady drone that closes on itself.
        p(ArcoVoice.CELLO, "ENDLESS DRAW", "TUNE" to 0.292f, "BOW" to 0.4f, "GRIP" to 0.5f, "BODY" to 0.6f, "HOLD" to 1.0f),
    )

    private val erhuPresets = listOf(
        // C#5 (11 of 19): the middle-high voice of the line, a nasal edge from a firm grip and a box that is mostly there.
        p(ArcoVoice.ERHU, "NASAL LINE", "TUNE" to 0.579f, "BOW" to 0.5f, "GRIP" to 0.8f, "BODY" to 0.7f, "HOLD" to 0.38f),
        // G4 (5 of 19): a soft round note, a dark light grip into nearly all box, bowed in over 0.19 s. BODY 0.8, not the 0.85 it was
        // written at: since R1c a BODY 0.85 box (1.375 times the string) is loud enough that the render's ring from its peak falls from
        // 813 ms to 522 ms, within 22 ms of the classifier's 500 ms line, so a louder box moves the preset's BODY down a notch.
        p(ArcoVoice.ERHU, "MOON FIDDLE", "TUNE" to 0.263f, "BOW" to 0.2f, "GRIP" to 0.3f, "BODY" to 0.8f, "HOLD" to 0.42f),
        // A#4 (8 of 19): the hardest bow on a light grip with hardly any box, 0.34 s of it: a thin, bright flick.
        p(ArcoVoice.ERHU, "THIN SCRAPE", "TUNE" to 0.421f, "BOW" to 1.0f, "GRIP" to 0.05f, "BODY" to 0.2f, "HOLD" to 0.05f),
        // G5 (17 of 19): near the top of the span, swelling in over 0.16 s under a firm grip and a held bow: the cry.
        p(ArcoVoice.ERHU, "HIGH CRY", "TUNE" to 0.895f, "BOW" to 0.25f, "GRIP" to 0.9f, "BODY" to 0.6f, "HOLD" to 0.44f),
        // B4 (9 of 19): the same cry eight semitones lower, a 0.19 s swell into the longest note a one-shot may be.
        p(ArcoVoice.ERHU, "SLOW CRY", "TUNE" to 0.474f, "BOW" to 0.2f, "GRIP" to 0.7f, "BODY" to 0.55f, "HOLD" to 0.44f),
        // E4 (2 of 19): the warm low end, most of the box and a medium grip: a plain friendly note, a medium bow in.
        p(ArcoVoice.ERHU, "TEA HOUSE", "TUNE" to 0.105f, "BOW" to 0.45f, "GRIP" to 0.4f, "BODY" to 0.9f, "HOLD" to 0.3f),
        // A4 (7 of 19), the outer open string: a firm bite with a half-box, 0.5 s of bow, a bright short note.
        p(ArcoVoice.ERHU, "TWO STRING", "TUNE" to 0.368f, "BOW" to 0.85f, "GRIP" to 0.45f, "BODY" to 0.55f, "HOLD" to 0.2f),
        // E5 (14 of 19), HOLD 1: the endless cry, a bright steady line that closes on itself.
        p(ArcoVoice.ERHU, "ENDLESS CRY", "TUNE" to 0.737f, "BOW" to 0.5f, "GRIP" to 0.75f, "BODY" to 0.5f, "HOLD" to 1.0f),
    )
}
