package com.snipsnap.synth

/** Six recipes per voice, including the nine accepted finite model-4 recipes. */
object RimePresets {
    const val BANK_MODEL = 4
    data class Entry(val id: String, val role: String, val description: String, val patch: RimePatch)

    /** Keep the accepted listening anchors independent of the expanding bank. */
    fun starter(voice: RimeVoice): RimePatch = RimePatch(when (voice) {
        RimeVoice.LOOP -> "Round Seed"
        RimeVoice.GLINT -> "Fine Frost"
        RimeVoice.VESSEL -> "Open Vessel"
        RimeVoice.REED -> "Glass Reed"
        RimeVoice.FILIGREE -> "Slow Filigree"
        RimeVoice.CRUST -> "Crystal Husk"
    }, voice, model = if (voice in Rime.LEGACY_VOICES) BANK_MODEL else Rime.MODEL_VERSION)
    fun starters(): List<RimePatch> = RimeVoice.entries.map(::starter)

    private fun patch(name: String, voice: RimeVoice, detail: Float, edge: Float,
        angle: Float, unfold: Float, body: Float, gate: Float): RimePatch =
        RimePatch(name, voice, Rime.defaults(voice, BANK_MODEL) + mapOf("DETAIL" to detail,
            "EDGE" to edge, "ANGLE" to angle, "UNFOLD" to unfold, "BODY" to body), gateSeconds = gate, model = BANK_MODEL)

    fun legacyEntries(): List<Entry> = listOf(
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
    private fun fresh(id: String, name: String, role: String, description: String, voice: RimeVoice,
        detail: Float, edge: Float, angle: Float, unfold: Float, body: Float,
        midi: Int = Rime.rootMidi(voice), gate: Float = Rime.gateSeconds(voice), hold: Boolean = false): Entry =
        Entry(id, role, description, RimePatch(name, voice, Rime.defaults(voice) + mapOf(
            "DETAIL" to detail, "EDGE" to edge, "ANGLE" to angle, "UNFOLD" to unfold, "BODY" to body,
            "HOLD" to if (hold) 1f else 0f), midi = midi, gateSeconds = gate))

    fun entries(): List<Entry> = (legacyEntries() + listOf(
        fresh("warm-silhouette", "Warm Silhouette", "Soft keys", "A soft contour reveals its hollow upper body after the onset.", RimeVoice.LOOP, .58f,.12f,.35f,.65f,.65f,60,1.8f),
        fresh("long-arc", "Long Arc", "Unfolding lead", "A sustained low lead develops fine structure in a small body.", RimeVoice.LOOP,.96f,.35f,.72f,.90f,.30f,48,3f),
        fresh("orbit-root", "Orbit Root", "Held bass", "A static held bass retains its opening and compact ringing shell.", RimeVoice.LOOP,.76f,.52f,.32f,.30f,.70f,36,1.4f,true),
        fresh("low-facet", "Low Facet", "Bass keys", "Fine keys shifted down into a clear hollow bass body.", RimeVoice.GLINT,.54f,.46f,.10f,.05f,.88f,36,1f),
        fresh("quiet-facets", "Quiet Facets", "Soft onset", "Delayed fine bands emerge through a rounded pickup.", RimeVoice.GLINT,.88f,.18f,.42f,.82f,.36f,60,2.4f),
        fresh("suspended-frost", "Suspended Frost", "Moving hold", "Bright fine bands circulate through a larger resonating shell.", RimeVoice.GLINT,.98f,.82f,.91f,.72f,.90f,60,1.8f,true),
        fresh("cavity-root", "Cavity Root", "Hollow bass", "A lower vessel with a direct onset and a restrained cavity.", RimeVoice.VESSEL,.56f,.56f,.76f,.10f,.48f,36,1f),
        fresh("shell-keys", "Shell Keys", "Melodic keys", "A bright strike in a smaller vessel for quicker melodic phrases.", RimeVoice.VESSEL,.95f,.66f,.11f,.12f,.34f,60,.65f),
        fresh("breathing-vessel", "Breathing Vessel", "Moving hold", "The large cavity sustains while its fine contour opens and recedes.", RimeVoice.VESSEL,.92f,.62f,.88f,.95f,.75f,48,2.6f,true),
        fresh("hollow-tooth", "Hollow Tooth", "Reed bass", "A dry nasal bass with a restrained odd-mode hollow body.", RimeVoice.REED,.48f,.25f,.22f,.08f,.24f,36,.8f),
        Entry("glass-reed", "Melodic reed", "The reed's asymmetric contour excites a hollow bank of odd body modes.", starter(RimeVoice.REED)),
        fresh("narrow-air", "Narrow Air", "Sustained lead", "A longer nasal tone with close fine contacts and a deep hollow shell.", RimeVoice.REED,.82f,.32f,.30f,.15f,.82f,55,2.8f),
        fresh("quiet-reed", "Quiet Reed", "Soft onset", "A rounded reed emerges gradually with little friction.", RimeVoice.REED,.98f,.28f,.20f,.80f,.88f,60,2.6f),
        fresh("serrated-air", "Serrated Air", "Bright detail", "Sharper contour contact drives the upper odd modes without losing the note.", RimeVoice.REED,.98f,.82f,.85f,.12f,.36f,67,1f),
        fresh("held-reed", "Held Reed", "Static hold", "A clear sustained reed with a single developing opening.", RimeVoice.REED,.90f,.58f,.06f,.40f,.70f,48,1.6f,true),
        fresh("low-filigree", "Low Filigree", "Detailed bass", "A low contour with broad fine bands and a compact shell.", RimeVoice.FILIGREE,.70f,.28f,.22f,.15f,.22f,36,1.2f),
        fresh("fine-lattice", "Fine Lattice", "Melodic keys", "Quick interleaved detail excites a light uneven mode pattern.", RimeVoice.FILIGREE,.82f,.70f,.78f,.20f,.70f,60,.75f),
        Entry("slow-filigree", "Unfolding tone", "Scale bands enter successively through a fine, lightly deformed contour.", starter(RimeVoice.FILIGREE)),
        fresh("quiet-threads", "Quiet Threads", "Soft onset", "A coarse beginning opens gradually into a small, softly excited body.", RimeVoice.FILIGREE,.66f,.12f,.35f,.95f,.35f,60,3.4f),
        fresh("lit-filigree", "Lit Filigree", "Bright detail", "A faster, brighter weave with the fine contours present early.", RimeVoice.FILIGREE,1f,.88f,.02f,.08f,.62f,67,1.6f),
        fresh("turning-filigree", "Turning Filigree", "Moving hold", "Staggered contour scales reveal and recede periodically around a stable root.", RimeVoice.FILIGREE,.96f,.62f,.88f,.90f,.68f,60,2.8f,true),
        fresh("husk-root", "Husk Root", "Crystalline bass", "A lower dense contour with restrained pressure and a hollow base.", RimeVoice.CRUST,.72f,.46f,.25f,.08f,.64f,36,.9f),
        Entry("crystal-husk", "Crystalline keys", "The full fine contour drives dense upper modes with bounded rough contact.", starter(RimeVoice.CRUST)),
        fresh("cold-grain", "Cold Grain", "Sustained tone", "Longer dense detail rings through a larger upper-mode shell.", RimeVoice.CRUST,.88f,.66f,.80f,.22f,.82f,48,2.8f),
        fresh("melting-edge", "Melting Edge", "Soft onset", "The crystalline edge arrives late and recedes through the body.", RimeVoice.CRUST,.78f,.22f,.38f,.95f,.58f,60,3f),
        fresh("bright-rind", "Bright Rind", "Bright detail", "An immediate, sharp contour with a small dry resonator.", RimeVoice.CRUST,1f,1f,.90f,0f,.12f,67,.6f),
        fresh("held-crust", "Held Crust", "Moving hold", "Dense fine contacts breathe periodically while the root continues.", RimeVoice.CRUST,.96f,.78f,.18f,.72f,.56f,48,1.8f,true),
    )).sortedBy { it.patch.voice.ordinal }
    /** Paste complete lines from UserPresets here; source-note and model fields survive. */
    private val promoted: List<RimePatch> = emptyList()
    fun forVoice(voice: RimeVoice): List<RimePatch> = all().filter { it.voice == voice }
    fun all(): List<RimePatch> = entries().map { it.patch } + promoted
}
