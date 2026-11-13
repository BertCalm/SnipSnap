package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import java.security.MessageDigest

/** A separate kept-source arrangement; neither historical MURMUR starter changes. */
object MurmurKeptBank {
    const val ID = "murmur-kept"
    const val SOURCE_FILE = "murmur-kept-source-bank.json"
    const val PAD_FADE_MS = 8f

    /** Immutable source and derived delivery snapshots. Reading a recipe returns a fresh copy. */
    data class Entry(val number: Int, val label: String, val sourceId: String,
                     val sourcePatchJson: String, val derivedRecipeJson: String,
                     val derivation: String, val selectedSourceId: String? = null) {
        fun recipe(): PadRecipe = PadRecipe.fromJsonText(derivedRecipeJson)
        fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "pad" to JsonValue.Num(number.toDouble()),
            "label" to JsonValue.Str(label),
            "sourceId" to JsonValue.Str(sourceId),
            "sourcePatch" to Json.parse(sourcePatchJson),
            "sourcePatchSha256" to JsonValue.Str(sha256(sourcePatchJson)),
            "derivedRecipe" to Json.parse(derivedRecipeJson),
            "derivedRecipeSha256" to JsonValue.Str(sha256(derivedRecipeJson)),
            "selectedSourceId" to (selectedSourceId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
            "derivation" to JsonValue.Str(derivation),
            "outputStage" to JsonValue.Str(MurmurKeptSources.FACTORY_OUTPUT),
            "padEnding" to JsonValue.Str(if (recipe().terminalFadeMs != null) "8_MS_TERMINAL_FADE" else "SOURCE_FINITE_TAIL"),
            "naturalReleaseClaimed" to JsonValue.Bool(false),
        ))
    }

    fun entries(): List<Entry> {
        val heard = MurmurPresets.selected()
        val key = heard.single { it.voice == MurmurVoice.KEY && it.macros.getValue("MOTION") == .5f }
        val rows = mutableListOf<Entry>()
        fun add(sourceId: String, source: MurmurPatch, patch: MurmurPatch,
                derivation: String, selectedSourceId: String? = null) {
            val held = Murmur.isLoop(patch.macros.getValue("HOLD"))
            val recipe = PadRecipe(patch, terminalFadeMs = if (held) PAD_FADE_MS else null)
            rows += Entry(rows.size + 1, patch.name, sourceId, source.toJsonText(), recipe.toJsonText(), derivation, selectedSourceId)
        }
        // Arranged transpositions retain the authored C4 source separately from each derived note.
        for (midi in listOf(48, 51, 53, 55, 58, 60, 63)) {
            add("approved-key-motion-0.5", key, key.copy(name = "KEPT KEY $midi", midi = midi), "ARRANGED_KEY_TRANSPOSITION")
        }
        for (patch in heard.filter { it.voice == MurmurVoice.KEY }) {
            add("approved-key-motion-${patch.macros.getValue("MOTION")}", patch, patch, "AUTHORED_KEY_SETTING")
        }
        for (source in MurmurKeptSources.all()) {
            add(source.id, source.originalPatch(), source.factoryPatch(), "KEPT_PLUCK_ALIAS_WITH_PAD_ENDING", source.id)
        }
        for (patch in heard.filter { it.voice == MurmurVoice.CHOIR }) {
            add("approved-choir-${patch.midi}", patch, patch, "AUTHORED_CHOIR_WITH_PAD_ENDING")
        }
        check(rows.size == 16)
        return rows
    }

    /** Injectable source renderer lets registration/provenance tests use synthetic PCM, not native DSP. */
    fun render(renderRecipe: (PadRecipe) -> Snip = { it.render() }): List<ArrangedPad?> = entries().map { row ->
        val recipe = row.recipe()
        val patch = recipe.patch as MurmurPatch
        ArrangedPad(renderRecipe(recipe), Murmur.drumClassFor(patch.voice, patch.macros),
            recipe.toJsonValue(), displayName = row.label, source = linkedMapOf(
                "rootOrigin" to "authored",
                "rootMidi" to patch.midi.toString(),
                "rootNote" to Scales.nameOf(patch.midi),
                "kind" to ID,
                "sourceBank" to SOURCE_FILE,
                "sourceId" to row.sourceId,
                "sourcePatch" to row.sourcePatchJson,
                "sourcePatchSha256" to sha256(row.sourcePatchJson),
                "derivedRecipeSha256" to sha256(row.derivedRecipeJson),
                "outputStage" to MurmurKeptSources.FACTORY_OUTPUT,
            ).apply {
                row.selectedSourceId?.let { put("selectedSourceRecord", Json.write(MurmurKeptSources.byId(it).toJsonValue())) }
            })
    }

    fun sourceBankJson(): String = Json.write(JsonValue.Obj(linkedMapOf(
        "kind" to JsonValue.Str("MURMUR_KEPT_SOURCE_METADATA"),
        "version" to JsonValue.Num(1.0),
        "starterId" to JsonValue.Str(ID),
        "factoryOutput" to JsonValue.Str(MurmurKeptSources.FACTORY_OUTPUT),
        "referenceOutput" to JsonValue.Str(MurmurKeptSources.REFERENCE_OUTPUT),
        "referenceGainsAppliedToFactoryPads" to JsonValue.Bool(false),
        "wholeEngineApprovalClaimed" to JsonValue.Bool(false),
        "selectedSources" to JsonValue.Arr(MurmurKeptSources.all().map { it.toJsonValue() }),
        "pads" to JsonValue.Arr(entries().map { it.toJsonValue() }),
    )))

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
