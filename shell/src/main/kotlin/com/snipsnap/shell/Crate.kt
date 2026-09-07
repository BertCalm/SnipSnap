package com.snipsnap.shell

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Similar
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.math.sqrt

/**
 * The Crate — your whole output treated as a collection. An index of
 * every kit pad's feature vector, class and classifier confidence,
 * cached in `.crate-index.json` keyed by file path + mtime + size so a
 * second pass over an unchanged library extracts nothing. On top of it:
 * duplicates (Similar distance ≈ 0 across different files), best-of-a-
 * class picks (classifier confidence ranks them), and a built best-of
 * kit — one pad per class, the strongest of everything you've made.
 */
object Crate {

    const val INDEX_NAME = ".crate-index.json"
    const val VERSION = 1

    /** At or under this feature distance, two different files are the same sound. */
    const val DUPE_DISTANCE = 0.02f

    data class Entry(
        /** Kit folder, relative to the crate root. */
        val kitDir: String,
        val kitName: String,
        val slot: Int,
        val padName: String,
        /** The WAV, relative to the crate root. */
        val file: String,
        val mtime: Long,
        val size: Long,
        /** The kit.json class — authoritative, user-correctable. */
        val storedClass: DrumClass,
        /** What the classifier hears, with its confidence — ranks the picks. */
        val heardClass: DrumClass,
        val confidence: Float,
        val vector: List<Float>,
    ) {
        val label: String get() = "%s%02d".format('A' + (slot - 1) / 16, (slot - 1) % 16 + 1)
    }

    data class Index(
        val entries: List<Entry>,
        /** How many pads were re-measured this pass (0 = all from cache). */
        val extracted: Int,
        val fromCache: Int,
    )

    /** Build (or refresh) the index for every kit under [root]. */
    fun index(root: File): Index {
        require(root.isDirectory) { "no such folder: $root" }
        val cached = loadIndex(File(root, INDEX_NAME)).associateBy { it.file }
        val entries = mutableListOf<Entry>()
        var extracted = 0
        var fromCache = 0

        val kitDirs = root.walkTopDown()
            .filter { it.isDirectory && File(it, KitStore.FILE_NAME).isFile }
            .sortedBy { it.path.lowercase() }
        for (dir in kitDirs) {
            val kit = try {
                KitStore.load(dir)
            } catch (e: Exception) {
                continue // an unreadable kit is skipped, not fatal
            }
            val rel = dir.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
            for (pad in kit.pads.sortedBy { it.slot }) {
                val wav = File(dir, pad.sampleFile)
                if (!wav.isFile) continue
                val fileRel = "$rel/${pad.sampleFile}"
                val hit = cached[fileRel]
                if (hit != null && hit.mtime == wav.lastModified() && hit.size == wav.length()) {
                    // The audio didn't change; the kit metadata may have.
                    entries += hit.copy(
                        kitDir = rel, kitName = kit.name, slot = pad.slot,
                        padName = pad.displayName, storedClass = pad.drumClass,
                    )
                    fromCache++
                    continue
                }
                val snip = try {
                    WavReader.read(wav)
                } catch (e: Exception) {
                    continue
                }
                val features = FeatureExtractor.extract(snip)
                val heard = Classifier.classify(features)
                entries += Entry(
                    kitDir = rel,
                    kitName = kit.name,
                    slot = pad.slot,
                    padName = pad.displayName,
                    file = fileRel,
                    mtime = wav.lastModified(),
                    size = wav.length(),
                    storedClass = pad.drumClass,
                    heardClass = heard.drumClass,
                    confidence = heard.confidence,
                    vector = Similar.vector(features).toList(),
                )
                extracted++
            }
        }
        saveIndex(File(root, INDEX_NAME), entries)
        return Index(entries, extracted, fromCache)
    }

    /** Different files carrying the same sound, nearest pairs first. */
    fun dupes(index: Index, epsilon: Float = DUPE_DISTANCE): List<Triple<Entry, Entry, Float>> {
        val found = mutableListOf<Triple<Entry, Entry, Float>>()
        val es = index.entries
        for (i in es.indices) {
            for (j in i + 1 until es.size) {
                if (es[i].file == es[j].file) continue
                val d = distance(es[i].vector, es[j].vector)
                if (d <= epsilon) found += Triple(es[i], es[j], d)
            }
        }
        return found.sortedBy { it.third }
    }

    /**
     * The best of a class across everything, classifier confidence first.
     * The stored class is the filter (it's the one a person can correct);
     * UNKNOWN pads qualify by what the classifier hears instead.
     */
    fun pick(index: Index, drumClass: DrumClass, top: Int = 8): List<Entry> =
        index.entries
            .filter {
                it.storedClass == drumClass ||
                    (it.storedClass == DrumClass.UNKNOWN && it.heardClass == drumClass)
            }
            .sortedWith(compareByDescending<Entry> { it.confidence }.thenBy { it.file })
            .take(top)

    /**
     * The best-of kit: for every class the crate holds (UNKNOWN aside),
     * its strongest pad, assembled into a fresh kit folder through the
     * same model the app uses — auto-place colours and mute groups ride
     * along.
     */
    fun build(root: File, index: Index, name: String, destDir: File): KitBuilderModel {
        val model = KitBuilderModel.create(name, destDir)
        var slot = 1
        for (dc in DrumClass.entries) {
            if (dc == DrumClass.UNKNOWN) continue
            val best = pick(index, dc, top = 1).firstOrNull() ?: continue
            val snip = WavReader.read(File(root, best.file))
            model.assign(slot++, snip, dc)
        }
        require(model.kit.pads.isNotEmpty()) { "the crate has nothing classified to build from" }
        model.save()
        return model
    }

    // ---- internals ---------------------------------------------------------

    internal fun distance(a: List<Float>, b: List<Float>): Float {
        var acc = 0f
        for (i in a.indices) {
            val d = a[i] - (b.getOrNull(i) ?: 0f)
            acc += d * d
        }
        return sqrt(acc)
    }

    private fun loadIndex(file: File): List<Entry> {
        if (!file.isFile) return emptyList()
        return try {
            val obj = (Json.parse(file.readText(Charsets.UTF_8)) as JsonValue.Obj).entries
            if ((obj["version"] as? JsonValue.Num)?.value?.toInt() != VERSION) return emptyList()
            ((obj["entries"] as? JsonValue.Arr)?.items.orEmpty()).mapNotNull { e ->
                val m = (e as? JsonValue.Obj)?.entries ?: return@mapNotNull null
                Entry(
                    kitDir = (m["kitDir"] as JsonValue.Str).value,
                    kitName = (m["kitName"] as JsonValue.Str).value,
                    slot = (m["slot"] as JsonValue.Num).value.toInt(),
                    padName = (m["padName"] as JsonValue.Str).value,
                    file = (m["file"] as JsonValue.Str).value,
                    mtime = (m["mtime"] as JsonValue.Num).value.toLong(),
                    size = (m["size"] as JsonValue.Num).value.toLong(),
                    storedClass = DrumClass.entries.firstOrNull {
                        it.name == (m["storedClass"] as? JsonValue.Str)?.value
                    } ?: DrumClass.UNKNOWN,
                    heardClass = DrumClass.entries.firstOrNull {
                        it.name == (m["heardClass"] as? JsonValue.Str)?.value
                    } ?: DrumClass.UNKNOWN,
                    confidence = (m["confidence"] as JsonValue.Num).value.toFloat(),
                    vector = ((m["vector"] as JsonValue.Arr).items).map {
                        (it as JsonValue.Num).value.toFloat()
                    },
                )
            }
        } catch (e: Exception) {
            emptyList() // a torn index rebuilds; it is a cache, not a record
        }
    }

    private fun saveIndex(file: File, entries: List<Entry>) {
        val root = JsonValue.Obj(
            linkedMapOf(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "entries" to JsonValue.Arr(
                    entries.map { e ->
                        JsonValue.Obj(
                            linkedMapOf(
                                "kitDir" to JsonValue.Str(e.kitDir),
                                "kitName" to JsonValue.Str(e.kitName),
                                "slot" to JsonValue.Num(e.slot.toDouble()),
                                "padName" to JsonValue.Str(e.padName),
                                "file" to JsonValue.Str(e.file),
                                "mtime" to JsonValue.Num(e.mtime.toDouble()),
                                "size" to JsonValue.Num(e.size.toDouble()),
                                "storedClass" to JsonValue.Str(e.storedClass.name),
                                "heardClass" to JsonValue.Str(e.heardClass.name),
                                "confidence" to JsonValue.Num(e.confidence.toDouble()),
                                "vector" to JsonValue.Arr(e.vector.map { JsonValue.Num(it.toDouble()) }),
                            ),
                        )
                    },
                ),
            ),
        )
        AtomicFile.writeText(file, Json.write(root) + "\n")
    }
}
