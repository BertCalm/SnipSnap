package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.LimitedRead
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A kept room ([Rooms.keep]'s own WAV plus its sidecar), packed as one
 * `.snip-room` file so it can leave the shelf and land on another
 * install — the same shape as the project's existing peer-to-peer growth
 * loop (an `.xpn` passed hand to hand), not a new one. Nothing here has
 * to survive on real MPC hardware the way an `.xpn` does, so there is no
 * `Expansion.xml`-style layout invariant to keep: two fixed-name entries
 * are the whole format, and the fixed names are what let [sniff] answer
 * cheaply — a lookup by name, never a decode — the same way [land]
 * already tells an `.xpn` apart from a bulk `KitBackup` archive by
 * peeking at what a ZIP holds rather than trusting its extension.
 */
object RoomPackager {

    /** The file extension a packed room wears — `<room's own name>.snip-room`. */
    const val EXTENSION = "snip-room"

    private const val WAV_ENTRY = "room.wav"
    private const val JSON_ENTRY = "room.json"

    /** The most a room's WAV may weigh once unpacked — generous for a measured impulse (seconds, not minutes), a wall against a hostile share, not a real ceiling. */
    private const val MAX_WAV_BYTES = 64L * 1024 * 1024
    private const val MAX_JSON_BYTES = 1L * 1024 * 1024

    /** A room's own impulse and how it was measured, read back off a `.snip-room` — [Rooms.keep]'s own parameters, ready to hand it straight back. */
    data class Imported(
        val name: String,
        val impulse: Snip,
        val lagMs: Float,
        val confidence: Float,
        val from: String,
        val measuredAt: Long,
    )

    /**
     * Packs [room]'s WAV and its sidecar — whatever fields the sidecar
     * currently holds, copied verbatim rather than rebuilt, so a field
     * this version doesn't know about still rides along — into
     * [outFile], one two-entry ZIP.
     */
    fun write(room: Rooms.Room, outFile: File, overwrite: Boolean = false) {
        if (outFile.exists() && !overwrite) {
            throw IOException("destination already exists: $outFile (pass overwrite=true to replace it)")
        }
        outFile.parentFile?.mkdirs()
        val sidecar = Rooms.sidecar(room.file)
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(WAV_ENTRY))
            room.file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            sidecar.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /**
     * True when [file] is a `.snip-room` — both fixed entries present,
     * checked by name only against the ZIP's central directory, never by
     * reading either one. Never throws: anything that isn't a real ZIP
     * (a foreign file, a truncated share) answers false, the same as
     * "not this kind" everywhere else this project sniffs a shared file.
     */
    fun sniff(file: File): Boolean = runCatching {
        ZipFile(file).use { zip -> zip.getEntry(WAV_ENTRY) != null && zip.getEntry(JSON_ENTRY) != null }
    }.getOrDefault(false)

    /**
     * Unpacks [file] into its impulse and metadata, ready for
     * [Rooms.keep]. Refuses in words when either entry is missing,
     * oversized, or the sidecar doesn't parse or name the room — never a
     * half-read [Imported].
     */
    fun read(file: File): Imported {
        ZipFile(file).use { zip ->
            val wavEntry = zip.getEntry(WAV_ENTRY)
                ?: throw IllegalArgumentException("not a room: no $WAV_ENTRY inside")
            val jsonEntry = zip.getEntry(JSON_ENTRY)
                ?: throw IllegalArgumentException("not a room: no $JSON_ENTRY inside")
            val wavBytes = zip.getInputStream(wavEntry).use { LimitedRead.bytes(it, MAX_WAV_BYTES, WAV_ENTRY) }
            val jsonBytes = zip.getInputStream(jsonEntry).use { LimitedRead.bytes(it, MAX_JSON_BYTES, JSON_ENTRY) }
            val meta = runCatching { Json.parse(String(jsonBytes, Charsets.UTF_8)).obj() }
                .getOrElse { throw IllegalArgumentException("not a room: its sidecar won't parse") }
            val name = (meta["name"] as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("not a room: its sidecar names none")
            return Imported(
                name = name,
                impulse = WavReader.read(wavBytes),
                lagMs = (meta["lagMs"] as? JsonValue.Num)?.value?.toFloat() ?: 0f,
                confidence = (meta["confidence"] as? JsonValue.Num)?.value?.toFloat() ?: 0f,
                from = (meta["from"] as? JsonValue.Str)?.value ?: "",
                measuredAt = (meta["measuredAt"] as? JsonValue.Num)?.value?.toLong() ?: System.currentTimeMillis(),
            )
        }
    }
}
