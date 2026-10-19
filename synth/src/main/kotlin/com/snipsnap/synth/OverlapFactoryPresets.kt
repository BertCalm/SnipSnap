package com.snipsnap.synth

/** The approved musical bank plus any explicitly promoted saved OVERLAP patches. */
object OverlapFactoryPresets {
    private val promoted = listOf<OverlapPatch>(
        // Paste complete OverlapPatch.fromJsonText(...) lines from UserPresets here.
        // Their saved JSON retains the note, velocity, macros, and model, including model 1.
    )

    private val entries = OverlapMusicalBank.all().map { it.patch } + promoted

    fun all(): List<OverlapPatch> = entries

    fun forVoice(topology: OverlapTopology): List<OverlapPatch> =
        entries.filter { it.topology == topology }
}
