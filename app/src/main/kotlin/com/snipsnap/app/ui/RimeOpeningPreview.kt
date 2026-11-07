package com.snipsnap.app.ui

import com.snipsnap.audio.Snip
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.RimePatch
import com.snipsnap.synth.Rime

/** MAKE is explicit for old finite notes; an already-held recipe keeps its model. */
internal fun rimeInstrumentSource(patch: RimePatch): RimePatch =
    if (patch.model < 5) patch.copy(model = Rime.MODEL_VERSION) else patch

/** Retain UNFOLD's opening in the bench, then loop only its measured sustain. */
internal class RimeOpeningPreview {
    @Volatile private var last: KeyNote? = null

    fun render(patch: RimePatch): Snip {
        val note = Keys.rimePad(patch)
        last = note
        return note.snip
    }

    /** Processed or superseded audio cannot inherit a marker from another buffer. */
    fun loopFor(snip: Snip): IntRange? {
        val note = last ?: return null
        if (note.snip.samples !== snip.samples) return null
        return note.loopStartFrame.toInt() until note.snip.frameCount
    }
}
