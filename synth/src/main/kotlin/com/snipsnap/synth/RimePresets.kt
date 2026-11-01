package com.snipsnap.synth

/** Nine preserved finite musical recipes, shared by the CLI and phone factory roster. */
object RimePresets {
    const val BANK_MODEL = 4
    data class Entry(val id: String, val role: String, val description: String, val patch: RimePatch)

    /** Keep the accepted listening anchors independent of the expanding bank. */
    fun starter(voice: RimeVoice): RimePatch = RimePatch(when (voice) {
        RimeVoice.LOOP -> "Round Seed"
        RimeVoice.GLINT -> "Fine Frost"
        RimeVoice.VESSEL -> "Open Vessel"
    }, voice, model = BANK_MODEL)
    fun starters(): List<RimePatch> = RimeVoice.entries.map(::starter)

    private fun patch(name: String, voice: RimeVoice, detail: Float, edge: Float,
        angle: Float, unfold: Float, body: Float, gate: Float): RimePatch =
        RimePatch(name, voice, Rime.defaults(voice, BANK_MODEL) + mapOf("DETAIL" to detail,
            "EDGE" to edge, "ANGLE" to angle, "UNFOLD" to unfold, "BODY" to body), gateSeconds = gate, model = BANK_MODEL)

    fun entries(): List<Entry> = listOf(
        Entry("round-seed", "Round bass", "The accepted rounded bass, with a small dry body.", starter(RimeVoice.LOOP)),
        Entry("firm-step", "Punchy bass", "A shorter excitation and compact body for a firm low note.",
            patch("Firm Step", RimeVoice.LOOP, .28f, .34f, .12f, 0f, .10f, .24f)),
        Entry("edged-root", "Edged bass", "More contour pressure and upper body modes around the bass root.",
            patch("Edged Root", RimeVoice.LOOP, .86f, .74f, .08f, .05f, .46f, .58f)),
        Entry("soft-snow", "Soft keys", "A coarse contour and light contact for a softer keyed tone.",
            patch("Soft Snow", RimeVoice.GLINT, .30f, .12f, .30f, .12f, .16f, .90f)),
        Entry("hollow-frost", "Hollow pluck", "A brief strike through a longer body and a narrower contact span.",
            patch("Hollow Frost", RimeVoice.GLINT, .68f, .42f, .73f, .03f, .70f, .34f)),
        Entry("fine-frost", "Bright strike", "The accepted bright key, retaining its fine contour pressure.", starter(RimeVoice.GLINT)),
        Entry("pebble-knock", "Short knock", "A short vessel excitation with immediate detail and a compact tail.",
            patch("Pebble Knock", RimeVoice.VESSEL, .48f, .36f, .12f, 0f, .18f, .15f)),
        Entry("ringing-stone", "Resonant tone", "A shorter note leaves the larger body to ring after the strike.",
            patch("Ringing Stone", RimeVoice.VESSEL, .70f, .40f, .28f, .25f, .68f, .55f)),
        Entry("open-vessel", "Unfolding texture", "The accepted slow reveal and long resonating body.", starter(RimeVoice.VESSEL)),
    )
    /** Paste complete lines from UserPresets here; source-note and model fields survive. */
    private val promoted: List<RimePatch> = emptyList()
    fun forVoice(voice: RimeVoice): List<RimePatch> = all().filter { it.voice == voice }
    fun all(): List<RimePatch> = entries().map { it.patch } + promoted
}
