package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** What a [RelayPatch] holds once it is made, whatever its caller does with the lists it built the phrase from. */
class RelayPatchTest {
    /** A phrase built from lists its caller keeps and can still change, the way an editor would build one. */
    private class Lists(voice: RelayVoice) {
        private val base = Relay.phraseFor(voice)
        val sources = base.sources.toMutableList()
        val slots = base.slots.toMutableList()
        val orderRoster = base.orderRoster.map { it.toMutableList() }.toMutableList()
        val accents = base.accents.toMutableList()
        fun phrase() = base.copy(sources = sources, slots = slots, orderRoster = orderRoster, accents = accents)
    }

    @Test
    fun `a patch keeps the phrase it was made with when the caller later changes its lists`() {
        val voice = RelayVoice.KEY
        val macros = Relay.defaults(voice)
        val held = Lists(voice)
        val patch = RelayPatch("Frozen", voice, macros, held.phrase(), velocity = .7f)
        val twin = RelayPatch("Frozen", voice, macros, Lists(voice).phrase(), velocity = .7f)
        val saved = patch.toJsonText()
        assertEquals(twin.toJsonText(), saved)

        // Edits a phrase would accept: without the snapshot they change the patch's sound and file in silence.
        held.sources[0] = held.sources[0].copy(gain = .1f)
        held.slots[1] = held.slots[1].copy(beats = 2f)
        held.orderRoster[0].reverse()
        held.accents[1] = RelayAccent(.75f, 1f)
        assertEquals(saved, patch.toJsonText(),
            "RelayPatch keeps a snapshot of its phrase: the caller's later edits to its lists reached the saved recipe")
        assertEquals(twin, patch, "RelayPatch keeps a snapshot of its phrase: the edits reached its equality")
        assertEquals(twin.hashCode(), patch.hashCode(), "RelayPatch keeps a snapshot of its phrase: the edits moved its hash")
        assertContentEquals(twin.render().samples, patch.render().samples,
            "RelayPatch keeps a snapshot of its phrase: the edits reached the render")

        // An edit no phrase would accept. RelayPhrase validates its lists once, when it is made.
        held.slots.add(RelaySlot(3, 1f, .1f))
        assertEquals(saved, patch.toJsonText(),
            "RelayPatch keeps a snapshot of its phrase: a fifth slot the phrase never validated reached the saved recipe")
        assertEquals(twin, RelayPatch.fromJsonText(patch.toJsonText()), "and the saved recipe still reads back as the patch")

        // copy is the other door a phrase comes in by.
        val other = Lists(voice)
        val copied = patch.copy(phrase = other.phrase())
        val copiedSaved = copied.toJsonText()
        other.slots.removeAt(0)
        assertEquals(copiedSaved, copied.toJsonText(),
            "RelayPatch keeps a snapshot of its phrase: a phrase given to copy still followed its caller's list")
    }

    @Test
    fun `a patch is equal to another only when every field it saves agrees`() {
        val voice = RelayVoice.CHIME
        val patch = RelayPatch("Equal", voice, Relay.defaults(voice), velocity = .5f)
        assertEquals(patch, patch.copy())
        assertEquals(patch.hashCode(), patch.copy().hashCode())
        val phrase = patch.phrase.copy(tempoBpm = 120f)
        val changed = mapOf(
            "name" to patch.copy(name = "Other"),
            "voice" to patch.copy(voice = RelayVoice.KEY, macros = Relay.defaults(RelayVoice.KEY)),
            "macros" to patch.copy(macros = patch.macros + ("ORDER" to .123f)),
            "phrase" to patch.copy(phrase = phrase),
            "velocity" to patch.copy(velocity = .6f),
        )
        for ((field, other) in changed) assertNotEquals(patch, other, "two patches that differ in $field compared equal")
        assertEquals(
            "RelayPatch(name=Equal, voice=CHIME, macros=${patch.macros}, phrase=${patch.phrase}, velocity=0.5, model=${RelayPatch.MODEL_VERSION})",
            patch.toString(),
        )
    }
}
