package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs

/** An approved recorded passage. UNKNOWN describes text content, not the recording's tradition. */
data class ConfluenceSelectedVoice(
    val id: String, val title: String, val sourceId: String, val tradition: String,
    val performerCredit: String, val sourceUrl: String, val licenseId: String, val licenseUrl: String,
    val sourceModifications: String, val assetPath: String, val fileSha256: String,
    val canonicalPcmSha256: String, val frames: Int, val textContent: String,
    val rawRegion: ConfluenceRegion, val region: ConfluenceRegion,
    val referenceGain: Float, val fadeInFrames: Int, val fadeOutFrames: Int,
    val provenance: JsonValue.Obj,
)

data class ConfluenceSelectedPreset(
    val id: String, val title: String, val description: String,
    val voiceIds: List<String>, val parts: Int, val recipe: ConfluenceRecipe,
)

data class ConfluenceSelectedCacheStats(val preparedMaterials: Int, val renderEntries: Int, val hits: Long, val misses: Long)

/**
 * Nine approved passages, kept separate from the explicitly wordless VocalSet starter bank.
 * Raw canonical WAVs retain their source levels and exact approved boundaries. Declared constant
 * gain and cosine edge envelopes create separate playback material; its independently calculated
 * PCM hash is the region identity stored in recipes. No pitch reference, text-content conclusion,
 * new singer, or authenticated cross-tradition performance is inferred from these recordings.
 *
 * The existing TEXTURE renderer supplies chronological whole-phrase playback. Its small identity
 * duration variations are varispeed, so neither unchanged pitch nor independent human performers
 * is promised for processed parts. Root-dependent analysis is unnecessary for this path: cached
 * materials have deliberately empty pitch evidence and cannot enter a pitched bank recipe.
 */
class ConfluenceSelectedBank private constructor(
    val id: String, val version: Int, val manifestText: String,
    val voices: List<ConfluenceSelectedVoice>, val presets: List<ConfluenceSelectedPreset>,
    private val sourceMap: Map<String, ConfluenceSource>,
    private val materials: Map<ConfluenceAnalysisKey, ConfluenceVoice.Analysis>,
    private val assets: Map<String, ByteArray>,
) {
    val manifestSha256: String = sha256(manifestText.toByteArray(Charsets.UTF_8))
    val attributionText: String get() = assets.getValue(ATTRIBUTION_FILE).toString(Charsets.UTF_8)
    val sourceAttributionText: String get() = attributionText
    val assetPaths: List<String> = immutableList(listOf(MANIFEST_FILE) + assets.keys)
    private val byId = voices.associateBy { it.id }
    private val byHash = voices.associateBy { it.region.assetHash }
    private val renderCache = LinkedHashMap<String, ConfluenceReport>(3, .75f, true)
    private var hits = 0L
    private var misses = 0L

    fun voice(id: String): ConfluenceSelectedVoice = byId[id]
        ?: throw IllegalArgumentException("unknown selected passage $id")
    fun preset(id: String): ConfluenceSelectedPreset = presets.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("unknown selected-passage preset $id")

    /** Exact untreated canonical selection. Every call decodes a fresh independent PCM buffer. */
    fun rawSource(id: String): Snip = WavReader.read(assets.getValue(voice(id).assetPath))

    /** Constant gain and source-boundary envelopes are explicitly recorded in the voice manifest. */
    fun reference(id: String): Snip = Snip(sourceMap.getValue(voice(id).region.assetHash).pcm.copyOf(), 1, Confluence.RATE)

    fun assetBytes(relativePath: String): ByteArray = if (relativePath == MANIFEST_FILE)
        manifestText.toByteArray(Charsets.UTF_8) else assets[relativePath]?.copyOf()
            ?: throw IllegalArgumentException("asset is not part of selected bank: $relativePath")

    fun writePortableBank(directory: File, overwrite: Boolean = false) {
        val root = portableRoot(directory)
        val outputs = assetPaths.map { path ->
            noSymlinks(root, File(root, path))
            val file = File(root, path).canonicalFile
            require(file.toPath().startsWith(root.toPath())) { "selected bank asset escapes its directory" }
            path to file
        }
        require(overwrite || outputs.none { it.second.exists() }) { "selected bank destination is occupied" }
        for ((path, file) in outputs) {
            require(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "cannot create selected bank directory" }
            noSymlinks(root, file)
            file.writeBytes(assetBytes(path))
        }
    }

    fun render(presetId: String, normalize: Boolean = true): ConfluenceReport = render(preset(presetId).recipe, normalize)

    /** Edited recipes may rearrange exact bank regions; they do not need to equal a factory preset. */
    fun render(recipe: ConfluenceRecipe, normalize: Boolean = true): ConfluenceReport {
        val checked = checkedRecipe(recipe)
        val key = "$manifestSha256:$PREPARATION_VERSION:${Confluence.ANALYSIS_VERSION}:$normalize:${checked.toJsonText()}"
        // Bound completed render storage to two reports, and never lend a cache-owned PCM buffer.
        synchronized(renderCache) {
            renderCache[key]?.let { hits++; return snapshot(it) }
            misses++
            val result = Confluence.renderWithAnalysis(checked, sourceMap, ConfluenceOptions(normalize = normalize), materials)
            val honest = result.copy(
                performers = result.performers.map { it.copy(registerSemitones = 0f, formantScale = 1f, pitchCents = 0f, vibratoCents = 0f) },
                events = result.events.map { it.copy(pitchSemitones = 0f, formantScale = 1f) },
                diagnostics = result.diagnostics.copy(voicedShare = 0f,
                    processor = "TEXTURE chronological playback; small duration variations are varispeed; no pitch analysis or formant synthesis"),
            )
            renderCache[key] = honest
            if (renderCache.size > MAX_RENDER_CACHE) renderCache.remove(renderCache.keys.first())
            return snapshot(honest)
        }
    }

    fun cacheStats(): ConfluenceSelectedCacheStats = synchronized(renderCache) {
        ConfluenceSelectedCacheStats(materials.size, renderCache.size, hits, misses)
    }

    private fun checkedRecipe(recipe: ConfluenceRecipe): ConfluenceRecipe {
        val snapshot = recipe.copy(regions = recipe.regions.toList(), macros = recipe.macros.toMap())
        require(snapshot.profile != ConfluenceProfile.DRONE && snapshot.midi == null) { "selected passages support finite native UNISON or RESPONSE, without requested notes or HOLD" }
        require(snapshot.macros.keys == Confluence.MACRO_NAMES) { "selected recipes must state all five controls explicitly" }
        require(snapshot.macros.getValue("REGISTER") == 0f) { "selected passages do not support REGISTER transposition" }
        require(snapshot.macros.getValue("DIVERGENCE") <= MAX_VARIATION) { "selected-passage variation must be at most $MAX_VARIATION" }
        for (region in snapshot.regions) {
            val voice = byHash[region.assetHash] ?: throw IllegalArgumentException("selected recipe refers to an unknown prepared source")
            require(region == voice.region) { "selected recipe must retain its exact approved prepared region, mode and unconfirmed reference" }
        }
        return snapshot
    }

    /** Nine disclosed reference variants, then seven complete two/four-part arrangements. */
    fun arrangedPads(): List<ArrangedPad?> {
        val result = ArrayList<ArrangedPad?>()
        for (voice in voices) result += ArrangedPad(reference(voice.id), DrumClass.LOOP,
            source = sourceMetadata(listOf(voice)) + mapOf("voiceId" to voice.id, "mode" to "REFERENCE",
                "bankRecipe" to Json.write(JsonValue.Obj(mapOf("mode" to JsonValue.Str("REFERENCE"), "voiceId" to JsonValue.Str(voice.id)))),
                "modifications" to conditioningText(voice)), displayName = voice.title)
        for (preset in presets) {
            val used = preset.voiceIds.map(::voice).distinctBy { it.id }
            result += ArrangedPad(render(preset.id).snip, DrumClass.LOOP,
                source = sourceMetadata(used) + mapOf("presetId" to preset.id, "mode" to "PARTS",
                    "parts" to preset.parts.toString(), "bankRecipe" to preset.recipe.toJsonText(),
                    "modifications" to used.joinToString(" | ", transform = ::conditioningText) +
                        " Existing Confluence TEXTURE varispeed parts, finite arrangement, room and shared output leveling."), displayName = preset.title)
        }
        check(result.size == STARTER_PAD_COUNT) { "selected starter must contain sixteen pads" }
        return result
    }

    private fun sourceMetadata(used: List<ConfluenceSelectedVoice>): Map<String, String> = mapOf(
        "engine" to ENGINE, "bankId" to id, "bankVersion" to version.toString(), "bankManifestSha256" to manifestSha256,
        "usedVoiceIds" to used.joinToString(",") { it.id }, "sourceHashes" to used.joinToString(",") { it.region.assetHash },
        "textContent" to "UNKNOWN", "attribution" to used.joinToString(" | ") { it.performerCredit },
        "sourceUrl" to used.map { it.sourceUrl }.distinct().joinToString(" | "),
        "license" to used.map { it.licenseId }.distinct().joinToString(","),
        "licenseUrl" to used.map { it.licenseUrl }.distinct().joinToString(" | "),
    )

    companion object {
        const val SCHEMA = 1
        const val PREPARATION_VERSION = 1
        const val FORMAT = "snipsnap-confluence-selected-bank"
        const val ENGINE = "CONFLUENCE_SOURCE_BANK"
        const val RESOURCE_ROOT = "confluence-selected-bank"
        const val MANIFEST_FILE = "bank.json"
        const val ATTRIBUTION_FILE = "SOURCE-ATTRIBUTION.txt"
        const val STARTER_PAD_COUNT = 16
        const val MAX_VARIATION = .15f
        const val MAX_AUDIO_BYTES = 12 * 1024 * 1024
        private const val MAX_RENDER_CACHE = 2
        private const val MAX_ASSET_BYTES = Confluence.RATE * 30 * 3 + 100_000

        fun bundled(): ConfluenceSelectedBank {
            val loader = ConfluenceSelectedBank::class.java.classLoader ?: error("selected bank resources are unavailable")
            fun read(path: String): ByteArray = loader.getResourceAsStream("$RESOURCE_ROOT/$path")?.use { it.readBytes() }
                ?: throw IllegalArgumentException("missing selected bank resource $path")
            return load(decodeText(read(MANIFEST_FILE)), ::read)
        }

        fun fromDirectory(directory: File): ConfluenceSelectedBank {
            val root = portableRoot(directory)
            fun read(path: String): ByteArray {
                safePath(path)
                noSymlinks(root, File(root, path))
                val file = File(root, path).canonicalFile
                require(file.toPath().startsWith(root.toPath())) { "selected bank asset escapes its directory" }
                require(file.isFile && file.length() in 1L..MAX_ASSET_BYTES.toLong()) { "missing or oversized selected bank asset $path" }
                return file.readBytes()
            }
            return load(decodeText(read(MANIFEST_FILE)), ::read)
        }

        fun load(manifestText: String, readAsset: (String) -> ByteArray): ConfluenceSelectedBank {
            require(manifestText.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "selected bank manifest is oversized" }
            val bank = Json.parse(manifestText).obj()
            require(bank.getValue("format").str() == FORMAT && bank.getValue("schema").int() == SCHEMA) { "unsupported selected bank format/schema" }
            require(bank.getValue("preparationVersion").int() == PREPARATION_VERSION) { "unsupported selected bank preparation" }
            val id = bank.getValue("id").str().also(::validId)
            val version = bank.getValue("version").int().also { require(it > 0) { "invalid selected bank version" } }
            require(bank.getValue("textContent").str() == "UNKNOWN") { "selected bank text content has not been authenticated" }
            val assets = LinkedHashMap<String, ByteArray>()
            val kinds = LinkedHashMap<String, String>()
            var audioBytes = 0L
            val declarations = bank.getValue("assets").arr()
            require(declarations.size in 12..24) { "selected bank asset count is outside its bounds" }
            for (value in declarations) {
                val o = value.obj(); val path = o.getValue("path").str().also(::safePath)
                require(path != MANIFEST_FILE && path !in assets) { "selected asset paths must be unique" }
                val kind = o.getValue("kind").str()
                require(kind in setOf("audio", "license", "notice")) { "unknown selected asset kind" }
                val expectedBytes = o.getValue("bytes").long()
                require(expectedBytes in 1L..(if (kind == "audio") MAX_ASSET_BYTES else 100_000).toLong()) { "selected asset exceeds its byte budget" }
                if (kind == "audio") { audioBytes += expectedBytes; require(audioBytes <= MAX_AUDIO_BYTES) { "selected audio exceeds twelve MiB" } }
                val hash = o.getValue("sha256").str().also(::validHash)
                val bytes = readAsset(path).copyOf()
                require(bytes.size.toLong() == expectedBytes && sha256(bytes) == hash) { "selected asset hash/size changed: $path" }
                assets[path] = bytes; kinds[path] = kind
            }
            require(kinds[ATTRIBUTION_FILE] == "notice") { "selected bank attribution notice is missing" }
            val licenses = bank.getValue("licenses").arr().associate { value ->
                val o = value.obj(); val license = o.getValue("id").str(); val url = o.getValue("url").str()
                require((license == "CC-BY-4.0" && url == "https://creativecommons.org/licenses/by/4.0/") ||
                    (license == "CC0-1.0" && url == "https://creativecommons.org/publicdomain/zero/1.0/")) { "unsupported selected source license" }
                val path = o.getValue("path").str().also(::safePath)
                require(kinds[path] == "license") { "selected source license text is missing" }
                license to url
            }
            require(licenses.keys == setOf("CC-BY-4.0", "CC0-1.0")) { "selected bank must retain both source license declarations" }
            val sourceValues = bank.getValue("sources").arr()
            require(sourceValues.size == 3) { "selected bank must contain the three credited source recordings" }
            val sources = sourceValues.associate { value ->
                val o = value.obj(); val sourceId = o.getValue("id").str().also(::validId)
                val license = o.getValue("licenseId").str()
                require(licenses[license] == o.getValue("licenseUrl").str()) { "source license does not match its declared legal text" }
                for (field in listOf("title", "tradition", "performerCredit", "sourceUrl", "modifications"))
                    require(o.getValue(field).str().isNotBlank()) { "selected source has no $field" }
                require(o.getValue("sourceUrl").str().startsWith("https://")) { "selected source must retain its primary page URL" }
                sourceId to o
            }
            require(sources.size == 3) { "duplicate selected source ID" }
            val sourceMap = LinkedHashMap<String, ConfluenceSource>()
            val materials = LinkedHashMap<ConfluenceAnalysisKey, ConfluenceVoice.Analysis>()
            val rawVoices = bank.getValue("voices").arr()
            require(rawVoices.size == 9) { "selected bank must retain the nine approved passages" }
            val voicePaths = HashSet<String>()
            val voices = rawVoices.map { value ->
                val o = value.obj(); val voiceId = o.getValue("id").str().also(::validId)
                val path = o.getValue("assetPath").str().also(::safePath)
                require(path.endsWith(".wav") && kinds[path] == "audio" && voicePaths.add(path)) { "selected voice must have a unique declared WAV" }
                val bytes = assets.getValue(path)
                val fileHash = o.getValue("fileSha256").str().also(::validHash)
                require(sha256(bytes) == fileHash) { "selected voice file hash changed" }
                requirePcm24(bytes)
                val audio = WavReader.read(bytes, Confluence.RATE * 30)
                require(audio.channels == 1 && audio.sampleRate == Confluence.RATE) { "selected source must be mono 44.1 kHz" }
                val frames = o.getValue("frames").int()
                require(frames == audio.frameCount) { "selected source frame count changed" }
                val raw = ConfluenceSource.fromSnip(audio)
                val rawHash = o.getValue("canonicalPcmSha256").str().also(::validHash)
                require(raw.hash == rawHash) { "selected source canonical PCM hash changed" }
                require(raw.pcm.any { abs(it) > 1e-6f }) { "selected passage is silent" }
                require(o.getValue("mode").str() == "TEXTURE" && o.getValue("referenceMidi") == JsonValue.Null) { "selected passages require TEXTURE and an unconfirmed root" }
                require(o.getValue("textContent").str() == "UNKNOWN") { "selected passage text content has not been authenticated" }
                val c = o.getValue("conditioning").obj()
                val gain = c.getValue("gain").num().toFloat(); val fadeIn = c.getValue("fadeInFrames").int(); val fadeOut = c.getValue("fadeOutFrames").int()
                require(gain.isFinite() && gain > 0f && gain <= 64f && fadeIn in 0..Confluence.RATE / 4 && fadeOut in 0..Confluence.RATE / 4 &&
                    fadeIn + fadeOut <= frames) { "selected playback conditioning exceeds its bounds" }
                val prepared = ConfluenceSource.fromSnip(Snip(condition(raw.pcm, gain, fadeIn, fadeOut), 1, Confluence.RATE))
                require(prepared.pcm.maxOf { abs(it) } <= 1f) { "selected reference conditioning clips" }
                require(sourceMap.put(prepared.hash, prepared) == null) { "selected prepared passages must be distinct" }
                val region = ConfluenceRegion(prepared.hash, 0, frames, ConfluenceSourceMode.TEXTURE)
                val rawRegion = ConfluenceRegion(raw.hash, 0, frames, ConfluenceSourceMode.TEXTURE)
                // TEXTURE uses only samples. No fabricated confidence/root enters this material.
                materials[ConfluenceAnalysisKey(prepared.hash, 0, frames, null)] =
                    ConfluenceVoice.Analysis(prepared.pcm, floatArrayOf(1f), floatArrayOf(0f), IntArray(0))
                val sourceId = o.getValue("sourceId").str(); val source = sources[sourceId] ?: throw IllegalArgumentException("selected passage has no credited source")
                val title = o.getValue("title").str().also { require(it.isNotBlank()) { "selected passage has no title" } }
                val provenance = o.getValue("provenance").obj()
                require(provenance.getValue("selectionId").str().isNotBlank() && provenance.getValue("sourceId").str() == sourceId &&
                    provenance.getValue("fileSha256").str() == fileHash && provenance.getValue("canonicalPcmSha256").str() == rawHash &&
                    provenance.getValue("frames").int() == frames) { "selected source identity disagrees with its approved-selection provenance" }
                val selectedFrames = provenance.getValue("nativeDecodedSelection").obj()
                require(selectedFrames.getValue("endFrame").int() - selectedFrames.getValue("startFrame").int() == frames) { "selected source chronology changed" }
                ConfluenceSelectedVoice(voiceId, title, sourceId, source.getValue("tradition").str(), source.getValue("performerCredit").str(),
                    source.getValue("sourceUrl").str(), source.getValue("licenseId").str(), source.getValue("licenseUrl").str(), source.getValue("modifications").str(),
                    path, fileHash, rawHash, frames, "UNKNOWN", rawRegion, region, gain, fadeIn, fadeOut, freeze(JsonValue.Obj(provenance)) as JsonValue.Obj)
            }
            require(voices.map { it.id }.distinct().size == 9 && voicePaths == kinds.filterValues { it == "audio" }.keys) { "selected voice IDs/assets are incomplete or duplicated" }
            val byId = voices.associateBy { it.id }
            val presets = bank.getValue("presets").arr().map { value ->
                val o = value.obj(); val presetId = o.getValue("id").str().also(::validId)
                val ids = o.getValue("voiceIds").arr().map { it.str() }
                require(ids.isNotEmpty() && ids.size <= 8 && ids.distinct().size == ids.size) { "invalid selected preset source assignments" }
                val parts = o.getValue("parts").int().also { require(it == 2 || it == 4) { "selected presets use two or four parts" } }
                val profile = ConfluenceProfile.valueOf(o.getValue("profile").str())
                require(profile != ConfluenceProfile.DRONE) { "selected presets have no approved sustain/loop markers" }
                val macros = o.getValue("macros").obj().mapValues { it.value.num().toFloat() }
                require(macros.keys == Confluence.MACRO_NAMES && macros.getValue("CROWD") == Confluence.crowdForCount(parts) &&
                    macros.getValue("REGISTER") == 0f && macros.getValue("DIVERGENCE") <= MAX_VARIATION) { "selected preset controls disagree with its native parts" }
                val recipe = ConfluenceRecipe(o.getValue("title").str(), profile,
                    ids.map { byId[it]?.region ?: throw IllegalArgumentException("selected preset names an unknown passage") },
                    immutableMap(macros), o.getValue("seed").int(), seconds = o.getValue("seconds").num().toFloat())
                ConfluenceSelectedPreset(presetId, recipe.name, o.getValue("description").str(), immutableList(ids), parts, recipe.copy(regions = immutableList(recipe.regions)))
            }
            require(presets.size == 7 && presets.map { it.id }.distinct().size == 7) { "selected bank requires seven distinct arrangements" }
            return ConfluenceSelectedBank(id, version, manifestText, immutableList(voices), immutableList(presets), sourceMap, materials, assets)
        }

        fun conditioningText(voice: ConfluenceSelectedVoice): String =
            "${voice.title}: approved canonical crop retained; playback gain ${voice.referenceGain}, cosine entrance ${voice.fadeInFrames} frames, release ${voice.fadeOutFrames} frames at ${Confluence.RATE} Hz. Raw canonical WAV remains available unchanged."

        private fun condition(samples: FloatArray, gain: Float, fadeIn: Int, fadeOut: Int): FloatArray = FloatArray(samples.size) { i ->
            var envelope = 1f
            // These samples define saved source identity. StrictMath fixes cosine's reference
            // algorithm across JVM/ART implementations instead of relying on platform libm.
            if (fadeIn > 0 && i < fadeIn) envelope *= (.5 - .5 * StrictMath.cos(PI * i / fadeIn)).toFloat()
            if (fadeOut > 0 && i >= samples.size - fadeOut) envelope *= (.5 - .5 * StrictMath.cos(PI * (samples.lastIndex - i) / fadeOut)).toFloat()
            samples[i] * gain * envelope
        }
        private fun snapshot(report: ConfluenceReport): ConfluenceReport = report.copy(
            snip = Snip(report.snip.samples.copyOf(), report.snip.channels, report.snip.sampleRate), raw = report.raw.copyOf(),
            dry = Snip(report.dry.samples.copyOf(), report.dry.channels, report.dry.sampleRate),
            performers = report.performers.toList(), events = report.events.toList())
        private fun decodeText(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).also {
            require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "selected manifest must be valid UTF-8" }
        }
        private fun requirePcm24(bytes: ByteArray) {
            require(bytes.size >= 44) { "selected WAV header is incomplete" }
            val little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var at = 12
            var formats = 0
            while (at + 8 <= bytes.size) {
                val length = little.getInt(at + 4).toLong() and 0xffffffffL
                val end = at.toLong() + 8 + length
                require(end <= bytes.size) { "selected WAV chunk is truncated" }
                if (String(bytes, at, 4, Charsets.US_ASCII) == "fmt ") {
                    formats++
                    require(length >= 16 && little.getShort(at + 8).toInt() == 1 &&
                        little.getShort(at + 10).toInt() == 1 && little.getInt(at + 12) == Confluence.RATE &&
                        little.getShort(at + 20).toInt() == 3 && little.getShort(at + 22).toInt() == 24) {
                        "selected raw WAVs must be mono 44.1 kHz PCM24"
                    }
                }
                at = (end + (length and 1)).toInt()
            }
            require(formats == 1) { "selected WAV must contain one unambiguous format" }
        }
        private fun safePath(path: String) = require(path.isNotBlank() && !path.startsWith('/') &&
            path.split('/').all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != "." && it != ".." }) { "selected bank asset path must remain relative: $path" }
        private fun portableRoot(directory: File): File {
            val requested = directory.absoluteFile
            // The bank and its immediate container are caller-controlled. Higher ancestors can
            // legitimately be platform storage aliases (for example Android's app-data paths).
            // Kit callers additionally check every path component from their own kit root.
            for (file in listOfNotNull(requested, requested.parentFile)) {
                require(!Files.isSymbolicLink(file.toPath())) { "selected bank paths must not contain symbolic links" }
            }
            return requested.canonicalFile
        }
        private fun noSymlinks(root: File, file: File) {
            val boundary = root.toPath().toAbsolutePath().normalize()
            var path = file.toPath().toAbsolutePath().normalize()
            require(path.startsWith(boundary)) { "selected bank asset escapes its directory" }
            while (true) {
                require(!Files.isSymbolicLink(path)) { "selected bank paths must not contain symbolic links" }
                if (path == boundary) break
                path = requireNotNull(path.parent) { "selected bank asset escapes its directory" }
            }
        }
        private fun validId(id: String) = require(id.matches(Regex("[a-z][a-z0-9-]{1,63}"))) { "invalid selected bank ID" }
        private fun validHash(hash: String) = require(hash.matches(Regex("[0-9a-f]{64}"))) { "invalid selected bank SHA-256" }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
        private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
        private fun freeze(value: JsonValue): JsonValue = when (value) {
            is JsonValue.Obj -> JsonValue.Obj(immutableMap(value.entries.mapValues { freeze(it.value) }))
            is JsonValue.Arr -> JsonValue.Arr(immutableList(value.items.map(::freeze)))
            else -> value
        }
    }
}
