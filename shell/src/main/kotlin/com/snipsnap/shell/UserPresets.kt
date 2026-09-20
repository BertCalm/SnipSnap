package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import java.io.File
import java.util.Locale

/**
 * SAVE AS PRESET (`docs/WORKSHOP.md`, WS5): the player's own presets, kept
 * beside the kits.
 *
 * SYNTH loads a factory preset, the player wrecks it into something of
 * their own, and until now the only place that sound could go was a pad:
 * SEND TO PAD writes the recipe into one kit, and the next kit starts from
 * the factory row again. A preset *is* a named [Patch] already (U1 of
 * `docs/SYNTH_UPGRADE.md`), so this store adds no format: one file,
 * `presets.json` at the shelf root, holding every saved patch as the same
 * JSON a pad's recipe carries, with when it was saved. The SYNTH screen
 * lists them under the factory row for their voice, on every kit.
 *
 * Names follow the factory rule the strip was built for — uppercase, at
 * most [MAX_NAME] letters — and a factory name is refused, so a chip
 * never has two meanings. Saving under one of your own names replaces
 * it in place: the way to iterate on a sound is to save it again.
 *
 * Promotion into the shipped roster stays a code change, on purpose: a
 * preset worth every player having is pasted into its engine's table by
 * hand ([rosterLine] renders the line), where the spread, blocklist and
 * identity tests judge it. SEND TO BENCH carries the file and the lines.
 *
 * FORGET follows the app's one rule for a delete: into the bin, not gone.
 * A forgotten preset moves to a `binned` list in the same file ([forget]),
 * waits [BIN_DAYS] days ([Binned.daysLeft], [sweepBin]) and comes back out
 * under its own name, or a fresh one if that name was taken meanwhile
 * ([unforget]). There is no EMPTY THE BIN NOW here, unlike the bins that
 * hold WAVs and kits: a preset is a few hundred bytes, so nothing is
 * bought by emptying early, and every site that cannot be undone is one
 * `ReversalTest` counts as evidence for an undo stack.
 */
object UserPresets {

    /** The one file, at the shelf root beside the kit folders. */
    const val FILE_NAME = "presets.json"

    const val VERSION = 1

    /** The factory rule (`docs/SYNTH_UPGRADE.md`, U1 naming): what fits the strip's chip. */
    const val MAX_NAME = 14

    /** Where the roster tables live, relative to the repo root: `<Engine>Presets.kt`, one per engine. */
    const val ROSTER_DIR = "synth/src/main/kotlin/com/snipsnap/synth/"

    /**
     * How long the bin keeps a forgotten preset: the promise `Reversal.BIN`
     * makes for every bin in the app, kept as this store's own constant the
     * way `Rooms.BIN_DAYS` and `SnipStore.BIN_DAYS` are, and pinned to that
     * promise by `ReversalTest`.
     */
    const val BIN_DAYS = 30

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A saved preset: the patch, and when it was saved. */
    data class Saved(val patch: Patch, val savedAt: Long) {
        val engine: String get() = patch.engine
        val voice: String get() = patch.voiceName
        val name: String get() = patch.name
    }

    /** A preset in the bin, and when it went there. */
    data class Binned(val saved: Saved, val binnedAt: Long) {
        /**
         * Days left before [sweepBin] takes it, rounded up so the readout
         * agrees with the sweep: a partial day left still reads 1, and 0
         * only at the boundary where the sweep goes — `Rooms.Binned`'s own
         * rule. Never below zero.
         */
        fun daysLeft(nowMillis: Long, keepDays: Int = BIN_DAYS): Int {
            val left = (binnedAt + keepDays * DAY_MS - nowMillis).coerceAtLeast(0L)
            return ((left + DAY_MS - 1) / DAY_MS).toInt()
        }
    }

    /** What a typed name comes to, before it is saved. */
    sealed class Check {
        /** Nothing but whitespace. */
        object Blank : Check()

        /** Longer than [MAX_NAME] once normalized. */
        data class TooLong(val name: String) : Check()

        /** The factory already ships one by that name on this voice; a chip must not mean two things. */
        data class Factory(val name: String) : Check()

        /** A name nothing on this voice holds. */
        data class Fresh(val name: String) : Check()

        /** One of the player's own on this voice: saving writes over it, in place. */
        data class Replaces(val name: String) : Check()
    }

    fun file(shelfRoot: File): File = File(shelfRoot, FILE_NAME)

    /** A name as it is kept: trimmed, every run of whitespace one space, uppercase. */
    fun normalize(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)

    /** The rules a name has to pass, and which of the two saves it would be. */
    fun check(raw: String, engine: String, voice: String, saved: List<Saved>): Check {
        val name = normalize(raw)
        if (name.isEmpty()) return Check.Blank
        if (name.length > MAX_NAME) return Check.TooLong(name)
        if (Presets.byName(engine, voice, name) != null) return Check.Factory(name)
        return if (forVoice(saved, engine, voice).any { it.name == name }) Check.Replaces(name) else Check.Fresh(name)
    }

    /**
     * The name the dialog opens with: `KICK 1`, `HAT CLOSED 2` — the voice
     * and the first number nothing on it holds, factory names included.
     * Numbered from 1 because it is a placeholder to type over, not a name
     * the player chose; and always within [MAX_NAME], which the longest
     * voice name leaves room for.
     */
    fun suggest(engine: String, voice: String, saved: List<Saved>): String {
        val base = voice.replace('_', ' ')
        var n = 1
        while (true) {
            val candidate = "$base $n"
            val taken = Presets.byName(engine, voice, candidate) != null || forVoice(saved, engine, voice).any { it.name == candidate }
            if (!taken) return candidate
            n++
        }
    }

    /** Every saved preset for one voice, in the order they were first saved. */
    fun forVoice(saved: List<Saved>, engine: String, voice: String): List<Saved> =
        saved.filter { it.engine == engine && it.voice == voice }

    /**
     * Every preset in the file, in the order they were first saved; none
     * without the file. An entry this build cannot read — an engine or a
     * voice it does not know, a macro out of range — is skipped here and
     * carried through [save] untouched, so a newer phone's preset survives
     * an older build. A file that is not this store's at all throws: a
     * save must not write over what it could not read.
     */
    fun read(shelfRoot: File): List<Saved> = readStore(file(shelfRoot)).saved

    /**
     * Keep [patch] under its own name, replacing in place whichever of the
     * player's presets on that voice already had it. The name must be one
     * [check] would pass: normalized, within [MAX_NAME], not the factory's.
     * Written whole and atomically, so a killed save leaves the old file.
     */
    fun save(shelfRoot: File, patch: Patch, nowMillis: Long): Saved {
        val name = patch.name
        require(name == normalize(name) && name.isNotEmpty()) { "a preset name is trimmed and uppercase: '$name'" }
        require(name.length <= MAX_NAME) { "a preset name is at most $MAX_NAME letters: '$name'" }
        require(Presets.byName(patch.engine, patch.voiceName, name) == null) { "the factory already has $name on ${patch.engine} ${patch.voiceName}" }
        val target = file(shelfRoot)
        val store = readStore(target)
        val entry = Saved(patch, nowMillis)
        val same = { s: Saved -> s.engine == patch.engine && s.voice == patch.voiceName && s.name == name }
        val kept = if (store.saved.any(same)) store.saved.map { if (same(it)) entry else it } else store.saved + entry
        write(target, store.copy(saved = kept))
        return entry
    }

    // ---- the bin ----

    /** Every preset in the bin, the most recently forgotten first. */
    fun bin(shelfRoot: File): List<Binned> = readStore(file(shelfRoot)).binned.sortedByDescending { it.binnedAt }

    /**
     * FORGET → BIN: the preset on [engine]/[voice] named [name] moves from
     * the strip to the bin, stamped with when; null when nothing by that
     * name is there to forget (a row that outran the tap), and the file is
     * untouched.
     */
    fun forget(shelfRoot: File, engine: String, voice: String, name: String, nowMillis: Long): Binned? {
        val target = file(shelfRoot)
        val store = readStore(target)
        val gone = store.saved.firstOrNull { it.engine == engine && it.voice == voice && it.name == name } ?: return null
        val binned = Binned(gone, nowMillis)
        write(target, store.copy(saved = store.saved - gone, binned = store.binned + binned))
        return binned
    }

    /**
     * RESTORE: [binned] back onto the strip, at the end of it, under its
     * own name — or, when that name was saved again meanwhile, under the
     * first `NAME 2`, `NAME 3`… nothing on that voice holds, trimmed to
     * fit [MAX_NAME]; the returned [Saved] says which. Null when it was
     * already gone from the bin.
     */
    fun unforget(shelfRoot: File, binned: Binned): Saved? {
        val target = file(shelfRoot)
        val store = readStore(target)
        if (binned !in store.binned) return null
        val was = binned.saved
        val taken = { n: String -> store.saved.any { it.engine == was.engine && it.voice == was.voice && it.name == n } }
        val name = freshName(was.name, taken)
        val back = if (name == was.name) was else Saved(renamed(was.patch, name), was.savedAt)
        write(target, store.copy(saved = store.saved + back, binned = store.binned - binned))
        return back
    }

    /** Empty the bin of presets forgotten more than [keepDays] ago; returns how many went. */
    fun sweepBin(shelfRoot: File, nowMillis: Long = System.currentTimeMillis(), keepDays: Int = BIN_DAYS): Int {
        val target = file(shelfRoot)
        val store = readStore(target)
        // At the boundary the preset goes: daysLeft reads 0 and the sweep agrees.
        val gone = store.binned.filter { nowMillis - it.binnedAt >= keepDays * DAY_MS }
        if (gone.isNotEmpty()) write(target, store.copy(binned = store.binned - gone.toSet()))
        return gone.size
    }

    /**
     * [base], or the first of `base 2`, `base 3`… that [taken] says nothing
     * holds — `Names.freshStem`'s rule, with one addition: the result must
     * fit [MAX_NAME], so a long base is trimmed to leave room for its
     * number rather than refused.
     */
    fun freshName(base: String, taken: (String) -> Boolean): String {
        if (!taken(base)) return base
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = base.take(MAX_NAME - suffix.length).trimEnd() + suffix
            if (!taken(candidate)) return candidate
            n++
        }
    }

    /** [patch] under another name: the same engine, voice and macros, through the patch's own JSON so every engine's type comes back as itself. */
    private fun renamed(patch: Patch, name: String): Patch {
        val json = patch.toJsonValue()
        val entries = LinkedHashMap(json.entries)
        entries["name"] = JsonValue.Str(name)
        return Patches.fromJsonValue(JsonValue.Obj(entries))
    }

    // ---- the desk: promotion into the roster ----

    /** The voice enum a roster line names: `THUMP` → `ThumpVoice`, `TONEWHEEL` → `TonewheelVoice`. */
    fun voiceEnum(engine: String): String = engine.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } + "Voice"

    /** The table a roster line pastes into, under [ROSTER_DIR]: `ThumpPresets.kt`. */
    fun rosterFile(engine: String): String = engine.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } + "Presets.kt"

    /**
     * [patch] as the line its engine's table is written in — the same
     * `p(voice, name, macros…)` helper every `<Engine>Presets.kt` has —
     * with every macro's exact value, so the promoted preset renders the
     * bytes the phone heard. The name is a Kotlin string literal, escaped.
     */
    fun rosterLine(patch: Patch): String {
        val name = patch.name.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val macros = patch.macros.entries.joinToString(", ") { (k, v) -> "\"$k\" to ${v}f" }
        return "p(${voiceEnum(patch.engine)}.${patch.voiceName}, \"$name\", $macros),"
    }

    /** Every saved preset as a roster line, grouped under the table it pastes into, in the order saved. */
    fun renderAll(saved: List<Saved>): String {
        val sb = StringBuilder()
        for ((engine, group) in saved.groupBy { it.engine }) {
            sb.append(rosterFile(engine)).append('\n')
            for (s in group) sb.append("  ").append(rosterLine(s.patch)).append('\n')
        }
        return sb.toString()
    }

    // ---- the file ----

    /**
     * What the file holds: the entries this build reads, the bin, and the
     * raw entries it does not read, kept for the next write. A file from
     * before the bin existed has no `binned` key and reads as an empty bin.
     */
    internal data class Store(val saved: List<Saved>, val binned: List<Binned>, val unread: List<JsonValue>)

    internal fun readStore(file: File): Store {
        if (!file.isFile) return Store(emptyList(), emptyList(), emptyList())
        val root = Json.parse(file.readText(Charsets.UTF_8)).obj()
        val version = root["version"]?.int() ?: throw JsonException("$FILE_NAME has no version")
        if (version != VERSION) throw JsonException("unsupported $FILE_NAME version $version")
        val items = root["presets"]?.arr() ?: throw JsonException("$FILE_NAME has no presets")
        val saved = mutableListOf<Saved>()
        val unread = mutableListOf<JsonValue>()
        for (item in items) {
            try {
                saved += readSaved(item.obj())
            } catch (e: Exception) {
                unread += item
            }
        }
        val binned = (root["binned"]?.arr() ?: emptyList()).mapNotNull { item ->
            // A binned entry this build cannot read is dropped rather than
            // carried: it is already on its way out, and the sweep would
            // take it anyway.
            runCatching {
                val o = item.obj()
                Binned(readSaved(o), o["binnedAt"]?.long() ?: throw JsonException("entry has no binnedAt"))
            }.getOrNull()
        }
        return Store(saved, binned, unread)
    }

    private fun readSaved(o: Map<String, JsonValue>): Saved {
        val patch = Patches.fromJsonValue(o["patch"] ?: throw JsonException("entry has no patch"))
        val at = o["savedAt"]?.long() ?: throw JsonException("entry has no savedAt")
        return Saved(patch, at)
    }

    private fun write(target: File, store: Store) {
        target.parentFile?.mkdirs()
        AtomicFile.writeText(target, Json.write(toJson(store)))
    }

    private fun entry(s: Saved, binnedAt: Long? = null): JsonValue.Obj {
        val m = linkedMapOf<String, JsonValue>("savedAt" to JsonValue.Num(s.savedAt.toDouble()))
        if (binnedAt != null) m["binnedAt"] = JsonValue.Num(binnedAt.toDouble())
        m["patch"] = s.patch.toJsonValue()
        return JsonValue.Obj(m)
    }

    private fun toJson(store: Store): JsonValue.Obj {
        val root = linkedMapOf<String, JsonValue>(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "presets" to JsonValue.Arr(store.saved.map { entry(it) } + store.unread),
        )
        // Written only when it holds something, so a shelf that never
        // forgot a preset keeps the file it always had.
        if (store.binned.isNotEmpty()) root["binned"] = JsonValue.Arr(store.binned.map { entry(it.saved, it.binnedAt) })
        return JsonValue.Obj(root)
    }
}
