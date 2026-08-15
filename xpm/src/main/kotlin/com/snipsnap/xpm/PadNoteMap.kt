package com.snipsnap.xpm

/**
 * The MPC default pad-to-MIDI-note map, 128 entries, pad 1..128.
 *
 * Bank A (pads 1-16) is the classic MPC drum layout — pad 1 is note 37, pad 2 is
 * note 36 (kick), pad 3 is 42 (closed hat), pad 6 is 38 (snare). Later banks fill
 * the remaining note space and wrap around to notes 0-18 at the very top.
 *
 * Transcribed from a drum program saved by MPC standalone firmware 2.9.1.2.
 * Do not "tidy" these values — they are not a formula, they are a lookup table,
 * and the MPC's own pad layout depends on them being exact.
 */
object PadNoteMap {

    /** Index 0 = pad 1. */
    val DEFAULT: IntArray = intArrayOf(
        // pads 1-16 (bank A)
        37, 36, 42, 82, 40, 38, 46, 44, 48, 47, 45, 43, 49, 55, 51, 53,
        // pads 17-32 (bank B)
        54, 69, 81, 80, 65, 66, 76, 77, 56, 62, 63, 64, 73, 74, 71, 39,
        // pads 33-48 (bank C)
        52, 57, 58, 59, 60, 61, 67, 68, 70, 72, 75, 78, 79, 35, 41, 50,
        // pads 49-64 (bank D)
        83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98,
        // pads 65-80 (bank E)
        19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
        // pads 81-96 (bank F)
        99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114,
        // pads 97-112 (bank G)
        115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 0, 1, 2,
        // pads 113-128 (bank H)
        3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
    )

    /** Total pads addressable by a drum program: 8 banks of 16. */
    const val PAD_COUNT: Int = 128

    /** Pads per bank. */
    const val BANK_SIZE: Int = 16

    /** MIDI note for a 1-based pad number. */
    fun noteForPad(padNumber: Int): Int {
        require(padNumber in 1..PAD_COUNT) { "pad number out of range: $padNumber" }
        return DEFAULT[padNumber - 1]
    }

    /** Human label for a 1-based pad number, e.g. 1 -> "A01", 17 -> "B01". */
    fun labelForPad(padNumber: Int): String {
        require(padNumber in 1..PAD_COUNT) { "pad number out of range: $padNumber" }
        val bank = 'A' + (padNumber - 1) / BANK_SIZE
        val within = (padNumber - 1) % BANK_SIZE + 1
        return "%s%02d".format(bank, within)
    }
}
