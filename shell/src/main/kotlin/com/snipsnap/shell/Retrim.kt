package com.snipsnap.shell

import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import com.snipsnap.kit.KitPad
import java.io.File
import java.util.Locale

/**
 * RE-TRIM: a pad goes back to its own tape (`docs/RETRIM.md`).
 *
 * Every pad that came off a tape on the SNIPS shelf remembers three keys
 * in [KitPad.source] — [FILE_KEY], the snip's bare filename; [IN_KEY] and
 * [OUT_KEY], the cut's first and end (exclusive) frames *in that file*, at
 * the file's own sample rate. [tag] is the one writer of those keys
 * (`SnipStore.provenanceTag`, `ChopReviewModel`'s send, BACK ONTO); [of]
 * is the one reader. Frames rather than seconds so the cut lands exactly
 * on the deck and a later resample never drifts it.
 *
 * The resolver is pure — a pad and the snips directory in, [Ready] or a
 * [Refused] with the reason in the app's own words out — so the PAD
 * SHEET's RE-TRIM ▸ can say *why* it won't open rather than go grey.
 */
sealed class Retrim {

    /** The tape is there: open it here. [cut] null = the whole file (a pad tagged before the cut keys existed). */
    data class Ready(val file: File, val cut: Cut?) : Retrim()

    /** No tape to go back to, and the reason in the app's words. */
    data class Refused(val reason: String) : Retrim()

    /** A cut in a tape file: [inFrame] until [outFrame], frames at the file's rate. */
    data class Cut(val inFrame: Int, val outFrame: Int) {
        init {
            require(inFrame >= 0 && outFrame > inFrame) { "a cut runs forward from 0: $inFrame until $outFrame" }
        }

        val lengthFrames: Int get() = outFrame - inFrame

        /** `1.20–1.62s` — the PAD SHEET's provenance line, at [sampleRate]. */
        fun label(sampleRate: Int): String =
            String.format(Locale.ROOT, "%.2f–%.2fs", inFrame.toFloat() / sampleRate, outFrame.toFloat() / sampleRate)
    }

    companion object {
        const val FILE_KEY = "tapeFile"
        const val IN_KEY = "tapeIn"
        const val OUT_KEY = "tapeOut"

        /**
         * BACK ONTO's audio: [range] of the loaded tape [mono], exactly as
         * CHOP's own slice of it would be — `InstantKit.slice`'s clamp, then
         * the same DC removal and click-guard fades (`Chopper.SLICE_CLEANUP`),
         * no trim and no normalise — so a re-trimmed pad sounds like the
         * chopped one did, and nothing is baked in that a treatment would be.
         */
        fun cut(mono: Snip, range: IntRange): Snip =
            Cleanup.process(InstantKit.slice(mono, range), Chopper.SLICE_CLEANUP)

        /** The three keys for [fileName]'s cut [inFrame] until [outFrame], ready to merge into a pad's source. */
        fun tag(fileName: String, inFrame: Int, outFrame: Int): Map<String, String> {
            require('/' !in fileName && '\\' !in fileName) { "tapeFile is a bare filename: '$fileName'" }
            Cut(inFrame, outFrame)
            return linkedMapOf(FILE_KEY to fileName, IN_KEY to inFrame.toString(), OUT_KEY to outFrame.toString())
        }

        /**
         * The tape [pad] came off, by name: [FILE_KEY] first, else the
         * legacy `"file"` key SNIPS → PAD wrote before RE-TRIM existed
         * (`SnipStore.provenanceTag`'s display key — that path always
         * meant "this whole snip", so an older pad still opens its tape).
         */
        fun tapeName(pad: KitPad): String? = pad.source[FILE_KEY] ?: pad.source["file"]

        /**
         * [pad]'s cut in its tape, or null when the keys are absent or
         * don't describe a forward range — never a throw: a hand-edited
         * kit.json reads as "the whole file", the same as a pad tagged
         * before the keys existed.
         */
        fun cutOf(pad: KitPad): Cut? {
            val a = pad.source[IN_KEY]?.toIntOrNull() ?: return null
            val b = pad.source[OUT_KEY]?.toIntOrNull() ?: return null
            return if (a >= 0 && b > a) Cut(a, b) else null
        }

        /**
         * Where RE-TRIM would open for [pad]: the tape in [snipsDir] and
         * its cut, or the reason it can't. A missing tape name is the mic
         * or an import — unless `origin=chop` says the pad was chopped
         * before tapes were remembered, which gets its own line since the
         * fix (RE-CHOP) is different. A named tape that isn't in
         * [snipsDir] (deleted past the bin, never copied to this phone,
         * or a name that isn't bare) is gone; the pad keeps what it has.
         */
        fun of(pad: KitPad, snipsDir: File): Retrim {
            val name = tapeName(pad)
                ?: return Refused(if (pad.source["origin"] == "chop") Copy.RETRIM_OLD_CHOP else Copy.RETRIM_NO_TAPE)
            if ('/' in name || '\\' in name) return Refused(Copy.RETRIM_TAPE_GONE)
            val file = File(snipsDir, name)
            if (!file.isFile) return Refused(Copy.RETRIM_TAPE_GONE)
            return Ready(file, cutOf(pad))
        }
    }
}
