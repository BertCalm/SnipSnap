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
 */
object UserPresets {

    /** The one file, at the shelf root beside the kit folders. */
    const val FILE_NAME = "presets.json"

    const val VERSION = 1

    /** The factory rule (`docs/SYNTH_UPGRADE.md`, U1 naming): what fits the strip's chip. */
    const val MAX_NAME = 14

    /** Where the roster tables live, relative to the repo root: `<Engine>Presets.kt`, one per engine. */
    const val ROSTER_DIR = "synth/src/main/kotlin/com/snipsnap/synth/"

    /** A saved preset: the patch, and when it was saved. */
    data class Saved(val patch: Patch, val savedAt: Long) {
        val engine: String get() = patch.engine
        val voice: String get() = patch.voiceName
        val name: String get() = patch.name
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
        target.parentFile?.mkdirs()
        AtomicFile.writeText(target, Json.write(toJson(kept, store.unread)))
        return entry
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

    /** What the file holds: the entries this build reads, and the raw ones it does not, kept for the next write. */
    internal data class Store(val saved: List<Saved>, val unread: List<JsonValue>)

    internal fun readStore(file: File): Store {
        if (!file.isFile) return Store(emptyList(), emptyList())
        val root = Json.parse(file.readText(Charsets.UTF_8)).obj()
        val version = root["version"]?.int() ?: throw JsonException("$FILE_NAME has no version")
        if (version != VERSION) throw JsonException("unsupported $FILE_NAME version $version")
        val items = root["presets"]?.arr() ?: throw JsonException("$FILE_NAME has no presets")
        val saved = mutableListOf<Saved>()
        val unread = mutableListOf<JsonValue>()
        for (item in items) {
            try {
                val o = item.obj()
                val patch = Patches.fromJsonValue(o["patch"] ?: throw JsonException("entry has no patch"))
                val at = o["savedAt"]?.long() ?: throw JsonException("entry has no savedAt")
                saved += Saved(patch, at)
            } catch (e: Exception) {
                unread += item
            }
        }
        return Store(saved, unread)
    }

    private fun toJson(saved: List<Saved>, unread: List<JsonValue>): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "presets" to JsonValue.Arr(
                saved.map { s ->
                    JsonValue.Obj(
                        linkedMapOf(
                            "savedAt" to JsonValue.Num(s.savedAt.toDouble()),
                            "patch" to s.patch.toJsonValue(),
                        ),
                    )
                } + unread,
            ),
        ),
    )
}
