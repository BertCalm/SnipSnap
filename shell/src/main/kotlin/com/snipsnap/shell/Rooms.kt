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

    /** The bin's folder under the rooms: a forgotten room sleeps here, like every other delete, before it is gone. */
    const val BIN_DIR = ".bin"

    /** How long the bin keeps a forgotten room. */
    const val BIN_DAYS = 30

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
        val room = Room(name, wav, impulse.frameCount.toFloat() / impulse.sampleRate, lagMs, confidence, from, nowMillis)
        AtomicFile.writeText(sidecar(wav), Json.write(meta(room, frames = impulse.frameCount, sampleRate = impulse.sampleRate)))
        return room
    }

    /**
     * The sidecar's contents for [room]: whatever the sidecar beside
     * [Room.file] already holds (so a field this version does not know
     * survives a move), the known fields written over it, [binnedAt] set
     * only in the bin and cleared everywhere else.
     */
    private fun meta(room: Room, frames: Int? = null, sampleRate: Int? = null, binnedAt: Long? = null): JsonValue.Obj {
        val m = linkedMapOf<String, JsonValue>()
        readMeta(room.file)?.let { m.putAll(it) }
        m["version"] = JsonValue.Num(VERSION.toDouble())
        m["name"] = JsonValue.Str(room.name)
        if (frames != null) m["frames"] = JsonValue.Num(frames.toDouble())
        if (sampleRate != null) m["sampleRate"] = JsonValue.Num(sampleRate.toDouble())
        m["lagMs"] = JsonValue.Num(room.lagMs.toDouble())
        m["confidence"] = JsonValue.Num(room.confidence.toDouble())
        m["from"] = JsonValue.Str(room.from)
        m["measuredAt"] = JsonValue.Num(room.measuredAt.toDouble())
        if (binnedAt != null) m["binnedAt"] = JsonValue.Num(binnedAt.toDouble()) else m.remove("binnedAt")
        return JsonValue.Obj(m)
    }

    private fun readMeta(wav: File): Map<String, JsonValue>? {
        val side = sidecar(wav)
        return if (side.isFile) runCatching { Json.parse(side.readText()).obj() }.getOrNull() else null
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

    /** A room in the bin, and when it went there. */
    data class Binned(val room: Room, val binnedAt: Long) {
        /**
         * Days left before [sweepBin] takes it, rounded up so the readout
         * agrees with the sweep: a partial day left still reads 1, and 0
         * only at the boundary where the sweep goes. Never below zero.
         */
        fun daysLeft(nowMillis: Long, keepDays: Int = BIN_DAYS): Int {
            val left = (binnedAt + keepDays * DAY_MS - nowMillis).coerceAtLeast(0L)
            return ((left + DAY_MS - 1) / DAY_MS).toInt()
        }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Where forgotten rooms sleep under [shelfRoot]. */
    fun binDir(shelfRoot: File): File = File(dir(shelfRoot), BIN_DIR)

    /**
     * Forget [room]: into the bin, not gone - the app's one rule for a
     * delete. Its WAV and sidecar move under `Rooms/.bin/` (a fresh name
     * there if the bin already holds one), the sidecar stamped with when.
     */
    fun forget(shelfRoot: File, room: Room, nowMillis: Long = System.currentTimeMillis()): Binned {
        val bin = binDir(shelfRoot).apply { mkdirs() }
        val name = freshName(bin, room.name)
        val wav = File(bin, "$name.wav")
        // The WAV and its sidecar move together; the stamp is written over
        // the moved sidecar, so every field it held rides along.
        moveWithSidecar(room.file, wav)
        val moved = room.copy(name = name, file = wav)
        AtomicFile.writeText(sidecar(wav), Json.write(meta(moved, binnedAt = nowMillis)))
        return Binned(moved, nowMillis)
    }

    /** Every room in the bin, the most recently forgotten first. */
    fun binned(shelfRoot: File): List<Binned> {
        val bin = binDir(shelfRoot)
        val wavs = bin.listFiles { f: File -> f.isFile && f.extension.equals("wav", ignoreCase = true) } ?: return emptyList()
        return wavs.filter { isWav(it) }.mapNotNull { wav ->
            runCatching {
                val room = read(wav)
                val at = (readMeta(wav)?.get("binnedAt") as? JsonValue.Num)?.value?.toLong() ?: wav.lastModified()
                Binned(room, at)
            }.getOrNull()
        }.sortedByDescending { it.binnedAt }
    }

    /** Back out of the bin onto the shelf, under a name nothing there holds. */
    fun unforget(shelfRoot: File, binned: Binned): Room {
        val dir = dir(shelfRoot).apply { mkdirs() }
        val name = freshName(dir, binned.room.name)
        val wav = File(dir, "$name.wav")
        moveWithSidecar(binned.room.file, wav)
        val room = binned.room.copy(name = name, file = wav)
        // Rewritten from the moved sidecar: the stamp comes off, the rest stays.
        AtomicFile.writeText(sidecar(wav), Json.write(meta(room)))
        return room
    }

    /** Empty the bin of rooms forgotten more than [keepDays] ago; returns how many went. */
    fun sweepBin(shelfRoot: File, nowMillis: Long = System.currentTimeMillis(), keepDays: Int = BIN_DAYS): Int {
        var gone = 0
        for (b in binned(shelfRoot)) {
            // At the boundary the room goes: daysLeft reads 0 and the sweep agrees.
            if (nowMillis - b.binnedAt >= keepDays * DAY_MS) {
                b.room.file.delete()
                sidecar(b.room.file).delete()
                gone++
            }
        }
        return gone
    }

    /**
     * EMPTY THE BIN NOW: every forgotten room asleep in the bin gone now,
     * not just what has aged past [BIN_DAYS] — the same early-sweep
     * relation [KitShelf.emptyKitBin]/[SnipStore.emptyBin] each have to
     * their own timed sweep. Deliberately NOT `sweepBin(keepDays = 0)`:
     * that still gates on `nowMillis - b.binnedAt >= 0`, which is false
     * (leaving a row behind under "NO TAKEBACKS") whenever [binned]'s own
     * mtime fallback hands back a future timestamp (a hand-placed or
     * restored file with no sidecar, [binned]'s own `wav.lastModified()`
     * fallback) or the device clock has been set backward since binning —
     * both real, both would leave a row on screen the button just claimed
     * was gone. This walks every [binned] entry unconditionally instead,
     * the same unconditional-delete shape [KitShelf.emptyKitBin]
     * (`children.count { it.delete() }`) and [SnipStore.emptyBin] both
     * already use. Returns how many rooms went; 0 without throwing when
     * the bin is empty or was never created.
     */
    fun emptyBin(shelfRoot: File): Int {
        // Walks the DIRECTORY, not [binned]'s parsed listing. [binned] drops
        // anything that isn't a readable WAV — a non-wav file, one that fails
        // [isWav], one whose [read] throws — so emptying by that list would
        // leave exactly the entries nobody can see behind, forever: the sweep
        // filters the same way, so nothing else would ever collect them. A
        // torn write or an aborted move is enough to produce one, and the
        // button says NO TAKEBACKS. Emptying a bin means the directory is
        // empty afterwards, not that everything legible is gone.
        val bin = binDir(shelfRoot)
        val children = bin.listFiles() ?: return 0
        return children.count { it.delete() }
    }

    /** [from] and its sidecar to [to] and its sidecar, together. */
    private fun moveWithSidecar(from: File, to: File) {
        move(from, to)
        val side = sidecar(from)
        if (side.isFile) move(side, sidecar(to))
    }

    private fun move(from: File, to: File) {
        try {
            java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.io.IOException) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    /** [room] as the MUTATE card's parent. */
    fun partner(room: Room): MutateSheet.Partner.Room = MutateSheet.Partner.Room(room.name, room.file)

    private fun sidecar(wav: File): File = File(wav.parentFile, wav.nameWithoutExtension + ".json")

    private fun read(wav: File): Room {
        val meta = readMeta(wav)
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
