package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitBackup
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Importer
import com.snipsnap.kit.XpnImporter
import com.snipsnap.mpc3.LimitedRead
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import java.util.zip.ZipFile

/**
 * The shelf's receiving door (F6.3, W3.3, X3.3's other half): a file
 * shared into the app that is a *kit* rather than a sound lands on the
 * shelf as one or more kit folders.
 *
 * What it reads, told apart by the bytes and not the name (a share sheet
 * hands over MIME types that are wrong as often as right):
 *
 * - a **`.xpn`** — any ZIP holding an `.xpm` program — through
 *   [XpnImporter.importAll], so a multi-kit pack lands every kit;
 * - a **backup** — a ZIP of `.xpn`s, what BACKUP writes — through
 *   [KitBackup.restore];
 * - an **MPC 3 track or project** (`.xtd` / `.xpj`, gzip + ACVS) through
 *   [Mpc3Importer]. A bare `.xtd` carries no samples — they live in the
 *   `_[TrackData]/` folder beside it, which a single shared file cannot
 *   bring — so the importer refuses it by name unless the track was
 *   shared **zipped with its folder**, which this unpacks and imports.
 *
 * Landing is staged: every importer writes into a hidden staging folder
 * under the shelf first, then each kit is moved onto the shelf under a
 * name nothing there holds yet ("FUNK", then "FUNK 2"), with `kit.json`
 * renamed to match — so an import can never overwrite a kit the user
 * already has, and a half-failed import leaves nothing behind. Sounds
 * ([Kind.AUDIO]) are not this door's business: the caller sends them to
 * the tape deck.
 */
object ShelfImport {

    enum class Kind {
        /** A ZIP: a `.xpn` expansion, a backup of `.xpn`s, or an MPC track zipped with its folder — [land] looks inside. */
        XPN,
        /** A bare MPC 3 container (gzip + ACVS): a track or a project. */
        MPC3,
        /** A sound: WAV or a compressed file — the tape deck's, not the shelf's. */
        AUDIO,
        UNKNOWN,
    }

    /** What landed: each kit's name and folder, and what was skipped with the reason. */
    data class Landed(val kits: List<Pair<String, File>>, val skipped: List<String>)

    /** The staging folder's name under the shelf — hidden from the shelf's own listing (no `kit.json` at its top). */
    const val STAGING_DIR = ".landing"

    /** The most one ZIP may inflate to across all its entries. */
    private const val MAX_UNZIP_BYTES = 512L * 1024 * 1024

    /**
     * The most entries one ZIP may hold: a track's folder is a few hundred
     * WAVs at most, and a million empty entries stay under the byte budget
     * forever while they eat the file system's bookkeeping.
     */
    const val MAX_UNZIP_ENTRIES = 10_000

    /** How many bytes of a file's head decide its kind. */
    const val SNIFF_BYTES = 12

    /**
     * The most an MPC 3 container may weigh before the shelf reads it: the
     * reader takes the whole file into memory (the container is one gzip
     * of text), so a shared file the landing cap lets through must still
     * be refused here before it is read, not after it fills the heap. The
     * same ceiling the MPC reader puts on the inflated text.
     */
    const val MAX_CONTAINER_BYTES: Long = LimitedRead.DEFAULT_LIMIT

    /**
     * What [head] (the file's first bytes, [SNIFF_BYTES] or fewer) is.
     * ZIPs are opened to look inside only by [land]; here a ZIP is a ZIP.
     */
    fun sniff(head: ByteArray): Kind = when {
        // Any of the ZIP signatures a file may open with: a local file header,
        // the end-of-central-directory record alone (an empty archive), or a
        // spanned-archive marker. All three are ZIPs to [land], which is where
        // an empty one earns its "no kit inside" refusal rather than a decode.
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            ((head[2] == 3.toByte() && head[3] == 4.toByte()) ||
                (head[2] == 5.toByte() && head[3] == 6.toByte()) ||
                (head[2] == 7.toByte() && head[3] == 8.toByte())) -> Kind.XPN
        head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() -> Kind.MPC3
        head.size >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte() &&
            head[10] == 'V'.code.toByte() && head[11] == 'E'.code.toByte() -> Kind.AUDIO
        head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> Kind.AUDIO
        head.size >= 2 && head[0] == 0xff.toByte() && (head[1].toInt() and 0xe0) == 0xe0 -> Kind.AUDIO
        head.size >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> Kind.AUDIO
        else -> Kind.UNKNOWN
    }

    /** True when [kind] is the shelf's to land; false sends it to the deck or refuses it. */
    fun isKit(kind: Kind): Boolean = kind == Kind.XPN || kind == Kind.MPC3

    /**
     * Land [file] (a local copy of what was shared, named [displayName]
     * for the messages) on the shelf under [shelfRoot]. Throws
     * [IllegalArgumentException] in words when nothing on the shelf can
     * read it, or when it read as a kit but held none. [maxContainerBytes]
     * is the size past which an MPC container is refused unread.
     */
    fun land(file: File, displayName: String, shelfRoot: File, maxContainerBytes: Long = MAX_CONTAINER_BYTES): Landed {
        require(file.isFile) { "no such file: $file" }
        val head = file.inputStream().use { s ->
            val b = ByteArray(SNIFF_BYTES)
            var n = 0
            while (n < b.size) {
                val r = s.read(b, n, b.size - n)
                if (r < 0) break
                n += r
            }
            b.copyOf(n)
        }
        shelfRoot.mkdirs()
        val staging = File(shelfRoot, "$STAGING_DIR-${System.nanoTime()}")
        staging.mkdirs()
        try {
            val kitDirs: List<File>
            val skipped = mutableListOf<String>()
            when (sniff(head)) {
                Kind.XPN -> {
                    val (dirs, why) = landZip(file, displayName, staging, maxContainerBytes)
                    kitDirs = dirs
                    skipped += why
                }
                Kind.MPC3 -> {
                    requireContainerFits(file, displayName, maxContainerBytes)
                    val project = Mpc3Project.read(file)
                    if (project.isProject) {
                        val r = Mpc3Importer.importProject(file, staging)
                        kitDirs = r.kits.map { it.directory }
                        skipped += r.skipped.map { "${it.key}: ${it.value}" }
                    } else {
                        kitDirs = listOf(Mpc3Importer.import(file, staging).directory)
                    }
                }
                Kind.AUDIO -> throw IllegalArgumentException("'$displayName' is a sound, not a kit - the tape deck takes it")
                else -> throw IllegalArgumentException(
                    "nothing on the shelf can read '$displayName' - share a .xpn, a backup, or an MPC .xtd zipped with its folder",
                )
            }
            require(kitDirs.isNotEmpty()) {
                "'$displayName' held no kit" + if (skipped.isNotEmpty()) ": " + skipped.joinToString("; ") else ""
            }
            val landed = kitDirs.map { moveOntoShelf(it, shelfRoot) }
            return Landed(landed, skipped)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Refuse [file] in words when it weighs more than [maxBytes] - before anything reads it whole. */
    private fun requireContainerFits(file: File, displayName: String, maxBytes: Long) {
        require(file.length() <= maxBytes) {
            "'$displayName' is too large to be an MPC container (${file.length() / (1024 * 1024)} MB, the most is ${maxBytes / (1024 * 1024)} MB) - refused"
        }
    }

    /** What a ZIP is by its entries, then the matching importer into [staging]. */
    private fun landZip(file: File, displayName: String, staging: File, maxContainerBytes: Long): Pair<List<File>, List<String>> {
        // One pass over the entries, three flags, no list: an archive may
        // declare any number of entries, and the names are not worth the heap.
        var xpn = false
        var xpm = false
        var mpc = false
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements() && !(xpn && xpm && mpc)) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val n = e.name
                when {
                    n.endsWith(".xpn", ignoreCase = true) -> xpn = true
                    n.endsWith(".xpm", ignoreCase = true) -> xpm = true
                    n.endsWith(".xtd", ignoreCase = true) || n.endsWith(".xpj", ignoreCase = true) -> mpc = true
                }
            }
        }
        return when {
            xpn -> KitBackup.restore(file, staging).map { it.directory } to emptyList()
            xpm -> {
                val r = XpnImporter.importAll(file, staging)
                r.kits.map { it.directory } to r.skipped.map { "${it.first}: ${it.second}" }
            }
            mpc -> {
                val unpacked = File(staging, "unpacked").apply { mkdirs() }
                unzipSafely(file, unpacked)
                val dirs = mutableListOf<File>()
                val skipped = mutableListOf<String>()
                val containers = unpacked.walkTopDown().filter { f ->
                    f.isFile && (f.extension.equals("xtd", true) || f.extension.equals("xpj", true))
                }.sortedBy { it.path }.toList()
                // Each container into a staging folder of its own: two tracks
                // that sanitize to the same kit name must not collide here, where
                // the importer refuses an existing folder - the shelf's own
                // naming ("NATIVE", "NATIVE 2") keeps both once they land.
                for ((i, c) in containers.withIndex()) {
                    val into = File(staging, "c$i").apply { mkdirs() }
                    try {
                        requireContainerFits(c, c.name, maxContainerBytes)
                        val project = Mpc3Project.read(c)
                        if (project.isProject) {
                            val r = Mpc3Importer.importProject(c, into)
                            dirs += r.kits.map { it.directory }
                            skipped += r.skipped.map { "${it.key}: ${it.value}" }
                        } else {
                            dirs += Mpc3Importer.import(c, into).directory
                        }
                    } catch (e: IllegalArgumentException) {
                        skipped += "${c.name}: ${e.message ?: "refused"}"
                    }
                }
                dirs to skipped
            }
            else -> throw IllegalArgumentException(
                "'$displayName' is a ZIP with no kit inside - no .xpn, no .xpm program, no .xtd",
            )
        }
    }

    /**
     * Every entry of [zip] under [dest], each path checked to stay inside
     * it (a `..` or an absolute name is an attack, not a layout), and the
     * whole bounded at [maxBytes] so an archive cannot fill the disk: each
     * entry is copied with only the room the budget has left, so the
     * refusal comes mid-stream, not after a huge entry already landed.
     * One budget for the whole, not a ceiling per entry - a track's sample
     * folder may hold one big WAV and still be honest. [maxEntries] ends
     * an archive of countless empty entries the byte budget never would.
     */
    internal fun unzipSafely(zip: File, dest: File, maxBytes: Long = MAX_UNZIP_BYTES, maxEntries: Int = MAX_UNZIP_ENTRIES) {
        val root = dest.canonicalFile
        var total = 0L
        var count = 0
        // Every landing path seen - canonical, so "a//one.wav", "a/./one.wav"
        // and "a/one.wav" are one path and the second is refused instead of
        // quietly replacing the first; bounded by [maxEntries], so the set is
        // never the archive's to grow.
        val seen = HashSet<String>()
        ZipFile(zip).use { z ->
            // Lazily: an archive declaring millions of entries is walked one at
            // a time, never held whole, and the ceilings end it early.
            for (entry in z.entries().asSequence()) {
                count++
                require(count <= maxEntries) { "the ZIP holds more than $maxEntries entries - refused" }
                if (entry.isDirectory) continue
                val raw = entry.name
                // A ZIP made on Windows may separate with backslashes; here a
                // backslash is an ordinary filename character, so normalize
                // before checking and before writing, or "a\\one.wav" lands as
                // one flat file and the track loses its folder. The raw name is
                // kept only for the messages.
                val name = raw.replace('\\', '/')
                val segments = name.split('/')
                require(!name.contains('\u0000') && !name.startsWith("/") && segments.none { it == ".." }) {
                    "unsafe entry escapes the ZIP: '$raw'"
                }
                val out = File(dest, name)
                val canonical = out.canonicalPath
                require(canonical.startsWith(root.path + File.separator)) { "entry escapes the ZIP: '$raw'" }
                require(seen.add(canonical)) { "the ZIP holds '$raw' twice - refused" }
                out.parentFile?.mkdirs()
                try {
                    z.getInputStream(entry).use { src ->
                        out.outputStream().use { dst ->
                            LimitedRead.copy(src, dst, limit = maxBytes - total, what = "ZIP entry $name")
                        }
                    }
                } catch (e: LimitedRead.TooLargeException) {
                    throw LimitedRead.TooLargeException(
                        "the ZIP inflates past ${maxBytes / (1024 * 1024)} MB at '$name' - refused",
                    )
                }
                total += out.length()
            }
        }
    }

    /**
     * [kitDir] out of staging onto the shelf under a name nothing there
     * holds: the kit's own, else "NAME 2", "NAME 3"…; `kit.json`'s name
     * follows the folder so the two never disagree.
     */
    private fun moveOntoShelf(kitDir: File, shelfRoot: File): Pair<String, File> {
        val kit = KitStore.load(kitDir)
        val base = kit.name.ifBlank { kitDir.name }
        var name = base
        var n = 2
        while (File(shelfRoot, name).exists()) {
            name = "$base $n"
            n++
        }
        val dest = File(shelfRoot, name)
        if (!kitDir.renameTo(dest)) {
            // A copy that did not finish is not a landed kit: nothing half of
            // it stays on the shelf, and the staging folder (the caller
            // deletes it) still holds the whole.
            if (!kitDir.copyRecursively(dest, overwrite = false)) {
                dest.deleteRecursively()
                throw IllegalStateException("could not move '$name' onto the shelf - nothing landed")
            }
            kitDir.deleteRecursively()
        }
        if (name != kit.name) KitStore.save(kit.copy(name = name), dest)
        return name to dest
    }

    /** The kit a landed folder holds — for the caller's shelf refresh. */
    fun kitOf(dir: File): Kit = KitStore.load(dir)
}
