package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ConfluenceBankFiles
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Confluence
import com.snipsnap.synth.ConfluenceProfile
import com.snipsnap.synth.ConfluenceRecipe
import com.snipsnap.synth.ConfluenceSelectedBank
import java.io.File

/** Approved complete passages, kept at their native register. CPU work precedes the kit-write lock. */
object ConfluenceSelectedWorkshop {
    const val ENGINE = "CONFLUENCE_SOURCE_BANK"
    private const val SETTINGS_VERSION = 1
    val bank: ConfluenceSelectedBank by lazy { ConfluenceSelectedBank.bundled() }
    val previewLevel: Float = KitPad(1, "preview.wav").level
    private val bankBytes: Long by lazy { bank.assetPaths.sumOf { bank.assetBytes(it).size.toLong() } }

    enum class Mode { SOURCE, REFERENCE, SOLO, PAIR, GROUP }

    data class Settings(
        val voiceId: String,
        val mode: Mode = Mode.GROUP,
        val variation: Float = .2f,
        val room: Float = .15f,
    ) {
        init {
            require(voiceId.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Choose an available passage." }
            require(variation.isFinite() && variation in 0f..1f) { "Variation must be between 0 and 1." }
            require(room.isFinite() && room in 0f..1f) { "Room must be between 0 and 1." }
        }
    }

    /** Retained PCM and metadata cannot be mutated by the preview player or a caller. */
    class Prepared internal constructor(
        val settings: Settings,
        private val rendered: Snip,
        private val metadata: Map<String, String>,
        val title: String,
        internal val bankFingerprint: String,
    ) {
        val snip: Snip get() = rendered.snapshot()
        val source: Map<String, String> get() = metadata.toMap()
        val previewLevel: Float get() = ConfluenceSelectedWorkshop.previewLevel
    }

    fun isEditable(pad: KitPad): Boolean = pad.source["engine"] == ENGINE && !pad.source["bankSettings"].isNullOrBlank()

    /** Reference pads reopen in the passage bench; the seven fixed arrangements retain their exact recipes. */
    fun starterPads(): List<ArrangedPad?> = bank.arrangedPads().mapIndexed { index, pad ->
        if (index < bank.voices.size && pad != null) {
            val settings = Settings(bank.voices[index].id, Mode.REFERENCE)
            pad.copy(source = metadata(settings, null))
        } else pad
    }

    fun prepare(settings: Settings): Prepared {
        val voice = bank.voice(settings.voiceId)
        val recipe = recipe(settings)
        val audio = when (settings.mode) {
            Mode.SOURCE -> bank.rawSource(voice.id)
            Mode.REFERENCE -> bank.reference(voice.id)
            else -> bank.render(requireNotNull(recipe)).snip
        }
        return Prepared(settings, audio.snapshot(), metadata(settings, recipe), "${voice.title} ${settings.mode.name}", bank.manifestSha256)
    }

    /** Adjacent immutable sources are mandatory. There is no fallback to another factory bank. */
    fun openPad(kitDir: File, pad: KitPad): Settings? {
        if (!isEditable(pad)) return null
        val fingerprint = requireNotNull(pad.source["bankManifestSha256"]) { "This passage has no source-bank identity." }
        val local = ConfluenceBankFiles.bankDirectory(kitDir, fingerprint)
        require(local.isDirectory) { "Restore this kit's voice bank before reopening its passages." }
        val restored = ConfluenceSelectedBank.fromDirectory(local)
        require(restored.manifestSha256 == fingerprint && fingerprint == bank.manifestSha256) {
            "This passage uses a different voice-bank version. Restore its original bank and matching app version."
        }
        val text = requireNotNull(pad.source["bankSettings"])
        require(text.length <= 4096) { "The saved passage settings are too large." }
        val values = Json.parse(text).obj()
        require(values.getValue("version").int() == SETTINGS_VERSION) { "Unsupported passage settings." }
        val settings = Settings(values.getValue("voiceId").str(), Mode.valueOf(values.getValue("mode").str()),
            values.getValue("variation").num().toFloat(), values.getValue("room").num().toFloat())
        val expected = metadata(settings, recipe(settings))
        for (key in listOf("bankId", "bankVersion", "bankManifestSha256", "bankRecipe", "sourceHashes", "usedVoiceIds", "bankSettings")) {
            require(pad.source[key] == expected[key]) { "The saved passage's source references disagree. Restore its original metadata." }
        }
        return settings
    }

    /** Caller opens a fresh model under KitWrites.mutex and saves it under the same lock. */
    fun makePad(model: KitBuilderModel, prepared: Prepared, slot: Int): KitPad {
        require(prepared.bankFingerprint == bank.manifestSha256) { "Preview the passage again before saving it." }
        require(slot in 1..128 && model.pad(slot) == null) { "That pad is now occupied. Choose another empty pad." }
        ConfluenceBankFiles.requireCapacity(model.kit, model.kitDir, bank.manifestSha256,
            bankBytes)
        val directory = ConfluenceBankFiles.bankDirectory(model.kitDir, bank.manifestSha256)
        if (directory.exists()) {
            require(ConfluenceSelectedBank.fromDirectory(directory).manifestSha256 == bank.manifestSha256) {
                "The kit's voice bank changed. Restore its original source files."
            }
        } else bank.writePortableBank(directory)
        model.assign(slot, prepared.snip, DrumClass.LOOP, prepared.title, prepared.source)
        // Phrase sources have no confirmed equal-tempered root and never enter automatic key tuning.
        return model.update(slot) { it.copy(level = prepared.previewLevel, oneShot = true, tuneCoarse = 0, tuneFine = 0) }
    }

    fun credits(voiceId: String): String {
        val v = bank.voice(voiceId)
        return "${v.title} — ${v.tradition}\n${v.performerCredit}\n${v.sourceUrl}\n${v.licenseId}: ${v.licenseUrl}\n" +
            "Source changes: ${v.sourceModifications}\nText content unverified. Nine passages come from three recordings."
    }

    private fun recipe(settings: Settings): ConfluenceRecipe? {
        val voice = bank.voice(settings.voiceId)
        if (settings.mode == Mode.SOURCE || settings.mode == Mode.REFERENCE) return null
        val parts = when (settings.mode) { Mode.SOLO -> 1; Mode.PAIR -> 2; else -> 4 }
        return ConfluenceRecipe("${voice.title} ${settings.mode.name}", ConfluenceProfile.UNISON, listOf(voice.region),
            macros = linkedMapOf("CROWD" to Confluence.crowdForCount(parts), "REGISTER" to 0f,
                "DIVERGENCE" to settings.variation * .15f, "RESPONSE" to 0f, "SPACE" to settings.room),
            seed = 1, midi = null, seconds = 30f)
    }

    private fun metadata(settings: Settings, recipe: ConfluenceRecipe?): Map<String, String> {
        val voice = bank.voice(settings.voiceId)
        val selector = Json.write(JsonValue.Obj(linkedMapOf("mode" to JsonValue.Str(settings.mode.name), "voiceId" to JsonValue.Str(voice.id))))
        val settingsText = Json.write(JsonValue.Obj(linkedMapOf("version" to JsonValue.Num(SETTINGS_VERSION.toDouble()),
            "voiceId" to JsonValue.Str(voice.id), "mode" to JsonValue.Str(settings.mode.name),
            "variation" to JsonValue.Num(settings.variation.toDouble()), "room" to JsonValue.Num(settings.room.toDouble()))))
        val processing = when (settings.mode) {
            Mode.SOURCE -> "Exact approved native excerpt; source WAV preserved. No gain, fades, repetition or pitch processing."
            Mode.REFERENCE -> "Constant playback gain ${voice.referenceGain}; ${voice.fadeInFrames}-frame entrance and ${voice.fadeOutFrames}-frame release fades. Native register."
            else -> "Conditioned source (gain ${voice.referenceGain}, fades ${voice.fadeInFrames}/${voice.fadeOutFrames} frames); finite Confluence TEXTURE arrangement, slight varispeed and entrance variation, shared room and output leveling. No confirmed root or note transposition."
        }
        return linkedMapOf("engine" to ENGINE, "bankId" to bank.id, "bankVersion" to bank.version.toString(),
            "bankManifestSha256" to bank.manifestSha256, "bankSettings" to settingsText,
            "bankRecipe" to (recipe?.toJsonText() ?: selector), "usedVoiceIds" to voice.id,
            "sourceHashes" to (if (settings.mode == Mode.SOURCE) voice.rawRegion.assetHash else voice.region.assetHash),
            "sourceUrl" to voice.sourceUrl, "license" to voice.licenseId, "licenseUrl" to voice.licenseUrl,
            "attribution" to credits(voice.id), "modifications" to "${voice.sourceModifications}; $processing",
            "sourceProvenance" to Json.write(voice.provenance), "textContent" to voice.textContent,
            "pitchMode" to "TEXTURE_NATIVE_REGISTER", "conditioning" to processing)
    }

    private fun Snip.snapshot() = Snip(samples.copyOf(), channels, sampleRate)
}
