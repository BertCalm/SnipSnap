package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.Names
import java.io.File

/**
 * The rooms on the shelf (YY5): a room OUTSIDE measured, kept as a
 * reusable parent, so any pad on any kit can be played inside it through
 * MUTATE ▸ ROOM without another trip.
 *
 * A ROOM trip deconvolves the sweep's return into the room's impulse
 * response and hands it to ROOM OF ITSELF for the one pad it was sent
 * from; until now the room lived only in that pad. [keep] writes the
 * impulse as a WAV under `Rooms/` beside the kits (a folder with no
 * `kit.json`, so the shelf never lists it as a kit), with a sidecar
 * saying how it was measured — the trip, how sure, from which pad, when —
 * and [partner] turns it into the MUTATE card's third kind of parent.
 * Names are the kit's plus ROOM, fresh on the shelf: "FUNK ROOM", then
 * "FUNK ROOM 2".
 */
object Rooms {

    /** The rooms' folder name beside the kits. */
    const val ROOMS_DIR = "Rooms"

    /** A room's label as a MUTATE parent, in the recipe and the lineage: `room:FUNK ROOM`. */
    const val LABEL_PREFIX = "room:"

    private const val VERSION = 1

    /** A kept room: its name, its impulse on disk, and how it was measured. */
    data class Room(
        val name: String,
        val file: File,
        /** The impulse's length. */
        val seconds: Float,
        /** The trip's latency when it was measured, ms. */
        val lagMs: Float,
        /** How surely the room was found, 0..1. */
        val confidence: Float,
        /** Which pad sent the sweep, `KIT:A03`. */
        val from: String,
        val measuredAt: Long,
    ) {
        val label: String get() = LABEL_PREFIX + name
    }

    /** Where the rooms live under [shelfRoot]. */
    fun dir(shelfRoot: File): File = File(shelfRoot, ROOMS_DIR)

    /**
     * Keep [impulse] on the shelf as a room named after [baseName] (a kit's
     * name; " ROOM" is added), fresh among the rooms already there. Refuses
     * in words an impulse with nothing in it — a room that said nothing is
     * not a room.
     */
    fun keep(
        shelfRoot: File,
        impulse: Snip,
        baseName: String,
        lagMs: Float,
        confidence: Float,
        from: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Room {
        require(impulse.frameCount > 0 && impulse.peak() > 0f) { "the room said nothing - there is nothing to keep" }
        val dir = dir(shelfRoot).apply { mkdirs() }
        val stem = Names.sanitizeStem(baseName.uppercase(java.util.Locale.ROOT)).let { if (it.endsWith(" ROOM")) it else "$it ROOM" }
        val name = freshName(dir, stem)
        val wav = File(dir, "$name.wav")
        // Rendered whole, then landed by write-then-rename: a process killed
        // mid-write must not leave a truncated file that still reads RIFF…WAVE
        // and lists as a room. The WAV lands before its sidecar, so a sidecar
        // never vouches for a room that is not there.
        val bytes = java.io.ByteArrayOutputStream().also { WavWriter.write(it, impulse) }.toByteArray()
        AtomicFile.writeBytes(wav, bytes)
        val meta = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "name" to JsonValue.Str(name),
                "frames" to JsonValue.Num(impulse.frameCount.toDouble()),
                "sampleRate" to JsonValue.Num(impulse.sampleRate.toDouble()),
                "lagMs" to JsonValue.Num(lagMs.toDouble()),
                "confidence" to JsonValue.Num(confidence.toDouble()),
                "from" to JsonValue.Str(from),
                "measuredAt" to JsonValue.Num(nowMillis.toDouble()),
            ),
        )
        AtomicFile.writeText(sidecar(wav), Json.write(meta))
        return Room(name, wav, impulse.frameCount.toFloat() / impulse.sampleRate, lagMs, confidence, from, nowMillis)
    }

    /**
     * Every room on the shelf, the latest measured first. A WAV whose
     * sidecar is missing or broken still lists — its length read off the
     * file, the measurement unknown — and a file that is not a WAV is
     * skipped whatever its sidecar says, never fatal.
     */
    fun list(shelfRoot: File): List<Room> {
        val dir = dir(shelfRoot)
        val wavs = dir.listFiles { f: File -> f.isFile && f.extension.equals("wav", ignoreCase = true) } ?: return emptyList()
        return wavs.filter { isWav(it) }.mapNotNull { wav -> runCatching { read(wav) }.getOrNull() }
            .sortedWith(compareByDescending<Room> { it.measuredAt }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
    }

    /** The file's first twelve bytes say RIFF…WAVE — a sidecar is not allowed to vouch for a file that is not one. */
    private fun isWav(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val head = ByteArray(12)
            var n = 0
            while (n < head.size) {
                val r = s.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            n == 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE"
        }
    }.getOrDefault(false)

    /** The room named [name], or null. */
    fun find(shelfRoot: File, name: String): Room? = list(shelfRoot).firstOrNull { it.name == name }

    /** Forget [room]: its WAV and sidecar gone. */
    fun forget(room: Room) {
        room.file.delete()
        sidecar(room.file).delete()
    }

    /** [room] as the MUTATE card's parent. */
    fun partner(room: Room): MutateSheet.Partner.Room = MutateSheet.Partner.Room(room.name, room.file)

    private fun sidecar(wav: File): File = File(wav.parentFile, wav.nameWithoutExtension + ".json")

    private fun read(wav: File): Room {
        val side = sidecar(wav)
        val meta = if (side.isFile) runCatching { Json.parse(side.readText()).obj() }.getOrNull() else null
        val frames = (meta?.get("frames") as? JsonValue.Num)?.value
        val rate = (meta?.get("sampleRate") as? JsonValue.Num)?.value
        val seconds = if (frames != null && rate != null && rate > 0) (frames / rate).toFloat() else {
            val s = WavReader.read(wav)
            s.frameCount.toFloat() / s.sampleRate
        }
        return Room(
            name = wav.nameWithoutExtension,
            file = wav,
            seconds = seconds,
            lagMs = (meta?.get("lagMs") as? JsonValue.Num)?.value?.toFloat() ?: 0f,
            confidence = (meta?.get("confidence") as? JsonValue.Num)?.value?.toFloat() ?: 0f,
            from = (meta?.get("from") as? JsonValue.Str)?.value ?: "",
            measuredAt = (meta?.get("measuredAt") as? JsonValue.Num)?.value?.toLong() ?: wav.lastModified(),
        )
    }

    private fun freshName(dir: File, base: String): String {
        var name = base
        var n = 2
        while (File(dir, "$name.wav").exists() || File(dir, "$name.json").exists()) {
            name = "$base $n"
            n++
        }
        return name
    }
}
