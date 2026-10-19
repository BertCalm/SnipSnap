package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import java.io.File
import java.security.MessageDigest

/** Source descriptions for a catalogue, rather than labels inferred from an arbitrary recording. */
data class ConfluenceBankVoice(
    val id: String,
    val title: String,
    val assetPath: String,
    val fixtureId: String,
    val styles: List<String>,
    val technique: String,
    val wordless: Boolean,
    val roles: Set<ConfluenceRole>,
    val region: ConfluenceRegion,
    val fileSha256: String,
    val frames: Int,
    val provenance: JsonValue.Obj,
)

data class ConfluenceBankPreset(
    val id: String,
    val title: String,
    val description: String,
    val recipe: ConfluenceAssemblyRecipe,
    val roleVoiceIds: Map<ConfluenceRole, String>,
)

/**
 * A small portable factory source bank. Canonical audio, stable source IDs, provenance and complete
 * arrangement recipes travel together. The same validated bank loads from JVM/APK class resources,
 * a copied directory, or an explicit byte provider. Nothing looks up a filename outside the pack.
 *
 * These are actual wordless recordings; the historic nursery-song demonstration is not a bank
 * source. Pitches and source contours remain native. Factory pads are finite rendered samples:
 * this API does not register an asset-free Patch or claim a source editor in the Android synth UI.
 */
class ConfluenceVoiceBank private constructor(
    val id: String,
    val version: Int,
    val mainPresetId: String,
    val voices: List<ConfluenceBankVoice>,
    val presets: List<ConfluenceBankPreset>,
    val manifestText: String,
    private val sources: Map<String, ConfluenceSource>,
    private val assets: Map<String, ByteArray>,
) {
    val defaultPresetId: String get() = mainPresetId

    val attributionText: String get() = buildString {
        appendLine("Confluence Wordless Starter — $id, version $version")
        appendLine("Actual wordless recordings; source voices retain their native contours and references.")
        appendLine("CC BY 4.0 — https://creativecommons.org/licenses/by/4.0/")
        for (voice in voices) {
            appendLine()
            appendLine("${voice.title} [${voice.id}] — ${voice.fixtureId}")
            val p = voice.provenance.entries
            appendLine(p.getValue("attribution").str())
            appendLine("Source: ${p.getValue("sourceUrl").str()}")
            appendLine("Original: ${p.getValue("originalArchiveMember").str()}")
            appendLine("Source preparation: ${p.getValue("modifications").str()}")
        }
        appendLine()
        appendLine("Rendered gatherings additionally apply the serialized Confluence arrangement: independent conservative vocal transformations, breathing envelopes, role gains and a shared room. Finite rendered playback needs no source assets; source-level replay uses this bank and its exact recipe.")
    }
    val sourceAttributionText: String get() = attributionText

    fun voice(id: String): ConfluenceBankVoice = voices.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("unknown Confluence bank voice $id")

    fun preset(id: String): ConfluenceBankPreset = presets.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("unknown Confluence bank preset $id")

    fun voicesForRole(role: ConfluenceRole): List<ConfluenceBankVoice> = voices.filter { role in it.roles }

    fun render(presetId: String = mainPresetId, normalize: Boolean = true): ConfluenceAssemblyReport =
        render(preset(presetId).recipe, normalize)

    /**
     * Render edited arrangement controls against this exact bank. Source selection may change to
     * another eligible bank voice, but a phrase, sustain, reference or mode cannot be silently
     * reinterpreted as an approved recording. No equality with a factory preset is required.
     */
    fun render(recipe: ConfluenceAssemblyRecipe, normalize: Boolean = true): ConfluenceAssemblyReport =
        ConfluenceAssembly.render(checkedRecipe(recipe), sources, normalize)

    /** A complete edited gathering ready for an ordinary finite pad, with its exact source recipe. */
    fun arrangedPad(recipe: ConfluenceAssemblyRecipe, displayName: String = recipe.name): ArrangedPad {
        require(displayName.isNotBlank()) { "a Confluence variation needs a display name" }
        val report = render(recipe, normalize = true)
        val saved = report.recipe
        val assigned = saved.roles.associate { spec -> spec.role to voiceFor(spec.region) }
        val used = assigned.values.distinctBy { it.id }
        val roleVoiceIds = JsonValue.Obj(ConfluenceRole.entries.associate { role ->
            role.name to JsonValue.Str(assigned.getValue(role).id)
        })
        return ArrangedPad(report.mix, DrumClass.LOOP,
            source = commonSourceMetadata() + mapOf(
                "assemblyRecipe" to saved.toJsonText(),
                "sourceHashes" to saved.roles.joinToString(",") { it.region.assetHash },
                "roles" to ConfluenceRole.entries.joinToString(",") { it.name },
                "roleVoiceIds" to Json.write(roleVoiceIds),
                "attribution" to used.map { it.provenance.entries.getValue("attribution").str() }.distinct().joinToString(" | "),
                "sourceUrl" to used.map { it.provenance.entries.getValue("sourceUrl").str() }.distinct().joinToString(" | "),
                "modifications" to used.joinToString(" | ") { "${it.title}: ${it.provenance.entries.getValue("modifications").str()}" } +
                    " Rendered with the exact stored Confluence assembly recipe and shared melodic loudness leveling.",
            ), displayName = displayName)
    }

    private fun voiceFor(region: ConfluenceRegion): ConfluenceBankVoice =
        voices.firstOrNull { it.region.assetHash == region.assetHash }
            ?: throw IllegalArgumentException("Confluence arrangement refers to an unknown bank source ${region.assetHash}")

    private fun checkedRecipe(recipe: ConfluenceAssemblyRecipe): ConfluenceAssemblyRecipe {
        val snapshot = recipe.copy(roles = recipe.roles.map { it.copy(performerIds = it.performerIds.toList()) })
        for (spec in snapshot.roles) {
            val voice = voiceFor(spec.region)
            require(spec.role in voice.roles) { "Confluence voice ${voice.id} is not eligible for ${spec.role}" }
            require(spec.region == voice.region) { "Confluence voice ${voice.id} requires its exact audited source region, markers, mode and reference" }
        }
        return snapshot
    }

    /** Exact portable payloads; callers receive a copy rather than the bank's cached asset bytes. */
    fun assetBytes(relativePath: String): ByteArray = if (relativePath == MANIFEST_FILE) manifestText.toByteArray(Charsets.UTF_8)
        else assets[relativePath]?.copyOf() ?: throw IllegalArgumentException("asset is not part of Confluence bank: $relativePath")

    val assetPaths: List<String> get() = listOf(MANIFEST_FILE) + assets.keys

    /** Write the exact checked source bank beside a native kit, including its attribution notice. */
    fun writePortableBank(directory: File, overwrite: Boolean = false) {
        val outputs = assetPaths + "SOURCE-ATTRIBUTION.txt"
        require(overwrite || outputs.none { File(directory, it).exists() }) { "portable Confluence bank already exists" }
        val root = directory.canonicalFile
        for (path in outputs) {
            val file = File(root, path).canonicalFile
            require(file.toPath().startsWith(root.toPath())) { "portable Confluence bank path escapes its directory" }
            require(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "cannot create Confluence bank directory" }
            file.writeBytes(if (path == "SOURCE-ATTRIBUTION.txt") attributionText.toByteArray(Charsets.UTF_8) else assetBytes(path))
        }
    }

    /**
     * Eight finite starter pads: four recorded gestures, three complete arrangements, and the main
     * arrangement's foundation/upper contribution at its original mix gain. The ordinary kit path
     * writes their WAVs and provenance. Unknown typed Patch recipes are deliberately not attached.
     */
    fun arrangedPads(): List<ArrangedPad?> {
        val pads = ArrayList<ArrangedPad?>()
        val common = commonSourceMetadata()
        for (voice in voices) {
            val source = sources.getValue(voice.region.assetHash)
            val samples = source.pcm.copyOfRange(voice.region.startFrame, voice.region.endFrame)
            Dsp.levelTo(samples, Confluence.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            pads += ArrangedPad(Snip(samples, 1, Confluence.RATE), DrumClass.LOOP,
                source = common + mapOf("voiceId" to voice.id, "sourceHash" to source.hash,
                    "sourceFrame" to voice.region.startFrame.toString(), "lengthFrames" to samples.size.toString(),
                    "fixtureId" to voice.fixtureId, "attribution" to voice.provenance.entries.getValue("attribution").str(),
                    "sourceUrl" to voice.provenance.entries.getValue("sourceUrl").str(),
                    "modifications" to voice.provenance.entries.getValue("modifications").str() + " Starter playback applies shared melodic loudness leveling."),
                displayName = voice.title)
        }
        var foundationAndAir: Snip? = null
        for (preset in presets) {
            val report = render(preset.id, normalize = true)
            val source = common + mapOf("presetId" to preset.id, "assemblyRecipe" to preset.recipe.toJsonText(),
                "sourceHashes" to preset.recipe.roles.joinToString(",") { it.region.assetHash },
                "attribution" to preset.roleVoiceIds.values.distinct().joinToString(" | ") { voice(it).provenance.entries.getValue("attribution").str() })
            pads += ArrangedPad(report.mix, DrumClass.LOOP, source = source, displayName = preset.title)
            if (preset.id == mainPresetId) {
                val low = report.stems.getValue(ConfluenceRole.FOUNDATION).samples
                val high = report.stems.getValue(ConfluenceRole.AIRY).samples
                foundationAndAir = Snip(FloatArray(low.size) { low[it] + high[it] }, 1, Confluence.RATE)
            }
        }
        pads += ArrangedPad(requireNotNull(foundationAndAir), DrumClass.LOOP,
            source = common + mapOf("presetId" to mainPresetId, "roles" to "FOUNDATION,AIRY",
                "assemblyRecipe" to preset(mainPresetId).recipe.toJsonText(), "gainPolicy" to "same normalization gain as complete main gathering"),
            displayName = "LOW AND AIR")
        check(pads.size == STARTER_PAD_COUNT) { "wordless starter must contain exactly $STARTER_PAD_COUNT pads" }
        return pads
    }

    private fun commonSourceMetadata(): Map<String, String> = linkedMapOf(
        "engine" to "CONFLUENCE_VOICE_BANK", "bankId" to id, "bankVersion" to version.toString(),
        "content" to "wordless recorded vocals", "sourceDependency" to "bundled Confluence source bank; rendered playback needs no external source",
        "license" to "CC-BY-4.0", "licenseUrl" to "https://creativecommons.org/licenses/by/4.0/",
        "attribution" to attributionText,
        "sourceUrl" to voices.map { it.provenance.entries.getValue("sourceUrl").str() }.distinct().joinToString(" | "),
        "modifications" to "Recorded source preparation is listed in the bundled bank manifest; arrangement pads additionally apply the exact stored Confluence assembly recipe. LOW AND AIR retains the full gathering's gain.",
    )

    companion object {
        const val SCHEMA = 1
        const val MANIFEST_FILE = "bank.json"
        const val RESOURCE_ROOT = "confluence/wordless-starter-v1"
        const val DEFAULT_PRESET_ID = "first-gathering"
        const val STARTER_PAD_COUNT = 8

        fun bundled(): ConfluenceVoiceBank {
            val loader = ConfluenceVoiceBank::class.java.classLoader
                ?: error("Confluence wordless bank class resources are unavailable")
            fun read(path: String): ByteArray = loader.getResourceAsStream("$RESOURCE_ROOT/$path")?.use { it.readBytes() }
                ?: throw IllegalStateException("missing packaged Confluence wordless bank resource $RESOURCE_ROOT/$path")
            return load(read(MANIFEST_FILE).toString(Charsets.UTF_8), ::read)
        }

        fun fromDirectory(directory: File): ConfluenceVoiceBank {
            val root = directory.canonicalFile
            fun read(path: String): ByteArray {
                requireSafePath(path)
                val file = File(root, path).canonicalFile
                require(file.toPath().startsWith(root.toPath())) { "Confluence bank asset escapes its directory" }
                require(file.isFile) { "missing Confluence bank asset $path" }
                require(file.length() in 1L..MAX_ASSET_BYTES.toLong()) { "Confluence bank asset exceeds the bounded file budget: $path" }
                return file.readBytes()
            }
            return load(read(MANIFEST_FILE).toString(Charsets.UTF_8), ::read)
        }

        /** Decode and verify a bank once. No filesystem access is hidden inside the renderer. */
        fun load(manifestText: String, readAsset: (String) -> ByteArray): ConfluenceVoiceBank {
            require(manifestText.length <= 1_000_000) { "Confluence bank manifest exceeds its bounded size" }
            val bank = Json.parse(manifestText).obj()
            require(bank.getValue("format").str() == "snipsnap-confluence-voice-bank") { "not a Confluence voice bank" }
            require(bank.getValue("schema").int() == SCHEMA) { "unsupported Confluence voice bank schema" }
            val id = bank.getValue("id").str().also(::requireId)
            val version = bank.getValue("version").int()
            require(version > 0) { "Confluence bank version must be positive" }
            require(bank.getValue("content").str() == "wordless-vocals") { "this factory bank accepts explicitly wordless vocal material" }
            val main = bank.getValue("mainPresetId").str().also(::requireId)
            val license = bank.getValue("license").obj()
            require(license.getValue("id").str() == "CC-BY-4.0") { "unsupported starter-bank license declaration" }
            require(license.getValue("url").str() == "https://creativecommons.org/licenses/by/4.0/") { "starter bank license URL does not match CC BY 4.0" }
            val licensePath = license.getValue("path").str().also(::requireSafePath)
            val assetBytes = LinkedHashMap<String, ByteArray>()
            assetBytes[licensePath] = readAsset(licensePath).copyOf().also {
                require(it.isNotEmpty() && it.size <= 100_000) { "missing or oversized bank license text" }
            }
            val sources = LinkedHashMap<String, ConfluenceSource>()
            val rawVoices = bank.getValue("voices").arr()
            require(rawVoices.size == 4) { "the wordless starter bank requires four curated voices" }
            val voices = rawVoices.map { value ->
                val o = value.obj()
                val voiceId = o.getValue("id").str().also(::requireId)
                val title = o.getValue("title").str()
                require(title.isNotBlank()) { "$voiceId has no display title" }
                val path = o.getValue("assetPath").str().also(::requireSafePath)
                require(path.endsWith(".wav", ignoreCase = true) && path != licensePath && path !in assetBytes) { "voice paths must be unique WAV assets" }
                val hash = o.getValue("fileSha256").str().also(::requireHash)
                val bytes = readAsset(path).copyOf()
                require(bytes.size in 44..MAX_ASSET_BYTES) { "$voiceId WAV exceeds the bounded starter asset budget" }
                require(sha256(bytes) == hash) { "$voiceId WAV hash changed; reassign and reanalyze changed material" }
                val audio = WavReader.read(bytes)
                require(audio.channels == 1 && audio.sampleRate == Confluence.RATE) { "$voiceId is not canonical mono 44.1 kHz audio" }
                val frames = o.getValue("frames").int()
                require(frames == audio.frameCount) { "$voiceId frame count disagrees with its audio" }
                val region = readRegion(o.getValue("region"))
                require(region.mode == ConfluenceSourceMode.PITCHED && region.referenceMidi != null) { "$voiceId requires a confirmed native pitch reference" }
                require(region.endFrame <= frames) { "$voiceId phrase region exceeds its audio" }
                val source = ConfluenceSource.fromSnip(audio)
                require(source.hash == region.assetHash) { "$voiceId canonical PCM hash disagrees with the bank reference" }
                require(source.pcm.any { kotlin.math.abs(it) > 1e-6f }) { "$voiceId is silent" }
                val wordless = o.getValue("wordless").bool()
                require(wordless) { "$voiceId is not declared wordless; lyric demonstrations are excluded from this bank" }
                val styles = o.getValue("styles").arr().map { it.str() }
                require(styles.isNotEmpty() && styles.all { it.isNotBlank() } && styles.distinct().size == styles.size) { "$voiceId needs distinct descriptive style tags" }
                val technique = o.getValue("technique").str()
                require(technique.isNotBlank()) { "$voiceId needs its recorded technique description" }
                val eligible = o.getValue("roles").arr().map { ConfluenceRole.valueOf(it.str()) }
                require(eligible.isNotEmpty() && eligible.distinct().size == eligible.size) { "$voiceId needs distinct eligible roles" }
                require(ConfluenceRole.FOUNDATION !in eligible || region.sustainStartFrame != null) { "$voiceId cannot support a foundation without sustain markers" }
                val provenance = o.getValue("provenance").obj()
                for (field in listOf("attribution", "sourceUrl", "originalArchiveMember", "modifications")) {
                    require(provenance.getValue(field).str().isNotBlank()) { "$voiceId has no $field provenance" }
                }
                require(provenance.getValue("license").str() == "CC BY 4.0" &&
                    provenance.getValue("licenseUrl").str() == license.getValue("url").str()) { "$voiceId license does not match the bank license" }
                require(provenance.getValue("wordless").bool() && provenance.getValue("usageFactoryCandidate").bool() &&
                    !provenance.getValue("demonstrationOnly").bool()) { "$voiceId provenance is not an eligible wordless factory candidate" }
                require(provenance.getValue("sourceTechnique").str() == technique) { "$voiceId technique disagrees with its recording provenance" }
                val recordedRoles = provenance.getValue("roleEligibility").arr().map {
                    if (it.str() == "AIRY_UPPER") ConfluenceRole.AIRY else ConfluenceRole.valueOf(it.str())
                }.toSet()
                require(recordedRoles == eligible.toSet()) { "$voiceId role eligibility disagrees with its recording provenance" }
                require(provenance.getValue("frames").int() == frames && provenance.getValue("sha256").str() == hash &&
                    provenance.getValue("phraseStartFrame").int() == region.startFrame && provenance.getValue("phraseEndFrame").int() == region.endFrame &&
                    provenance.getValue("referenceMidi").num().toFloat() == region.referenceMidi) { "$voiceId region disagrees with its audited recording provenance" }
                val fixtureId = o.getValue("fixtureId").str().also(::requireId)
                require(provenance.getValue("id").str() == fixtureId) { "$voiceId fixture identity disagrees with its provenance" }
                assetBytes[path] = bytes
                require(sources.put(source.hash, source) == null) { "bank voices must refer to distinct canonical source assets" }
                ConfluenceBankVoice(voiceId, title, path, fixtureId, styles.toList(), technique, wordless,
                    eligible.toSet(), region, hash, frames, JsonValue.Obj(provenance.toMap()))
            }
            require(voices.map { it.id }.distinct().size == voices.size) { "duplicate bank voice ID" }
            val byVoiceId = voices.associateBy { it.id }
            val rawPresets = bank.getValue("presets").arr()
            require(rawPresets.size == 3) { "the wordless starter bank requires three arrangement presets" }
            val presets = rawPresets.map { value ->
                val p = value.obj()
                val presetId = p.getValue("id").str().also(::requireId)
                val title = p.getValue("title").str()
                val description = p.getValue("description").str()
                require(title.isNotBlank() && description.isNotBlank()) { "$presetId has no title or description" }
                val recipe = ConfluenceAssemblyRecipe.fromJsonText(Json.write(p.getValue("recipe")))
                val bindings = p.getValue("roleVoiceIds").obj().mapKeys { ConfluenceRole.valueOf(it.key) }.mapValues { it.value.str() }
                require(bindings.keys == ConfluenceRole.entries.toSet()) { "$presetId must assign every assembly role" }
                for (spec in recipe.roles) {
                    val voice = byVoiceId[bindings.getValue(spec.role)] ?: throw IllegalArgumentException("$presetId names an unavailable voice")
                    require(spec.role in voice.roles) { "$presetId assigns ${voice.id} to an unsupported role" }
                    require(spec.region == voice.region) { "$presetId region differs from its named, audited bank voice" }
                }
                ConfluenceBankPreset(presetId, title, description, recipe, bindings.toMap())
            }
            require(presets.map { it.id }.distinct().size == presets.size) { "duplicate bank preset ID" }
            require(presets.any { it.id == main }) { "main bank preset is unavailable" }
            return ConfluenceVoiceBank(id, version, main, voices.toList(), presets.toList(), manifestText, sources.toMap(), assetBytes.toMap())
        }

        private fun readRegion(value: JsonValue): ConfluenceRegion {
            val r = value.obj()
            fun optional(v: JsonValue?) = v?.takeUnless { it == JsonValue.Null }
            return ConfluenceRegion(r.getValue("assetHash").str(), r.getValue("startFrame").int(), r.getValue("endFrame").int(),
                ConfluenceSourceMode.valueOf(r.getValue("mode").str()), optional(r["referenceMidi"])?.num()?.toFloat(),
                optional(r["sustainStartFrame"])?.int(), optional(r["sustainEndFrame"])?.int())
        }

        private const val MAX_ASSET_BYTES = Confluence.RATE * 30 * 4 + 100_000
        private fun requireId(id: String) = require(id.matches(Regex("[a-z][a-z0-9-]{1,63}"))) { "invalid stable bank identifier $id" }
        private fun requireHash(hash: String) = require(hash.matches(Regex("[0-9a-f]{64}"))) { "invalid bank SHA-256 hash" }
        private fun requireSafePath(path: String) = require(path.isNotBlank() && !path.startsWith('/') &&
            path.split('/').all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != "." && it != ".." }) { "bank asset path must remain relative: $path" }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
