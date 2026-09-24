package com.snipsnap.synth

import com.snipsnap.audio.Snip

/**
 * TELEPHONE — a sound whispered through pictures until it becomes
 * something else.
 *
 * One pass is a sound painted as its [Spectrogram.portrait], then that
 * portrait played back through [Spectrogram.read]. The two are each
 * other's way back, so a pass keeps what a picture can hold — where the
 * energy is in time and pitch — and loses what it cannot: phase (the
 * read reinvents it), anything quieter than a frame's own floor, detail
 * finer than a row or a column, and whatever an 8-bit pixel rounds away.
 * Each generation hears only the one before it, so the losses compound
 * the way a message does down a line of people: early passes are a worn
 * copy, later ones a new sound that remembers the old one's outline.
 *
 * Every generation keeps both halves, so a screen can show the pictures
 * drifting and land any one of the sounds on a pad. Deterministic per
 * [seed]: the same start and the same seed give the same line of
 * whispers, which is what makes a generation worth naming.
 */
object Telephone {

    /** One link in the line: the picture this sound was heard as, and what was heard. */
    data class Generation(val portrait: Photo, val sound: Snip)

    /** The longest line [chain] will whisper down. Past this the sound has long stopped changing in any way worth a pad. */
    const val MAX_GENERATIONS = 16

    /**
     * One pass: [sound] painted, then read back at its own length and
     * rate. [width] and [height] are the portrait's; a coarser picture
     * forgets faster.
     */
    fun pass(
        sound: Snip,
        width: Int = Spectrogram.PORTRAIT_WIDTH,
        height: Int = Spectrogram.PORTRAIT_HEIGHT,
        seed: Long = 0,
    ): Generation {
        require(sound.frameCount > 0) { "nothing to whisper: the sound is empty" }
        val portrait = Spectrogram.portrait(sound, width, height)
        val heard = Spectrogram.read(portrait, sound.durationSeconds, sound.sampleRate, seed)
        return Generation(portrait, heard)
    }

    /**
     * [generations] passes, each hearing only the one before, [start]
     * first in line. The first entry is the first pass, not [start]
     * itself — the caller already has that.
     */
    fun chain(
        start: Snip,
        generations: Int,
        width: Int = Spectrogram.PORTRAIT_WIDTH,
        height: Int = Spectrogram.PORTRAIT_HEIGHT,
        seed: Long = 0,
    ): List<Generation> {
        require(generations in 1..MAX_GENERATIONS) { "generations must be 1..$MAX_GENERATIONS, got $generations" }
        val line = ArrayList<Generation>(generations)
        var heard = start
        repeat(generations) {
            val next = pass(heard, width, height, seed)
            line += next
            heard = next.sound
        }
        return line
    }
}
