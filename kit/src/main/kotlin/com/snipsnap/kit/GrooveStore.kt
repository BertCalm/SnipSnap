package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File

/**
 * The kit's grooves, persisted — `groove.json` beside `kit.json`, closing
 * the leak where a captured rhythm existed only at export time. Chop
 * writes it, importers refill it from the clips they find, and every
 * native export embeds it without being asked.
 *
 * Holds a *list* of clips because the native container does (commercial
 * kits ship four: Chorus / Verse / Intro / Bridge — corpus-verified);
 * pattern variations land in the same file.
 *
 * This store is a clip shelf, not the GROOVE screen's program cache: the
 * screen's A–D are pure functions of the captured clip, recomputed live and
 * never written here (design/HANDOFF.md). What lands in `groove.json` is
 * whatever CLI verbs (Chop/Import/Learn/Euclid/Feel) deliberately choose to
 * persist as artifacts, plus PROG E via [GrooveEdit] — both are correct,
 * independently, at once.
 */
object GrooveStore {

    const val FILE_NAME = "groove.json"

    /**
     * 2 since clips can declare a meter.
     *
     * The bump is not decoration. A clip's `bars` changed meaning for
     * anything that is not 4/4: a one-bar 5/4 ORBIT clip is `bars: 1`
     * where the same music used to be written as `bars: 2` of 4/4, and a
     * 3/4 48-step cycle went from 3 to 4. A build that predates
     * `pulsesPerBar` would read those numbers as 4/4 bars and play music
     * of the wrong length — shorter in the first case, longer in the
     * second — so it has to refuse the file instead, which a version it
     * does not know is exactly how it does.
     *
     * Version 1 is still READ: those files only ever held 4/4 clips, so
     * the absent key means 4/4 and means it correctly.
     */
    const val VERSION = 2

    /** The oldest sidecar this build understands. */
    const val MIN_VERSION = 1

    fun save(kitDir: File, clips: List<Mpc3Clip>): File {
        require(clips.isNotEmpty()) { "no clips - use delete() to clear the grooves" }
        kitDir.mkdirs()
        val file = File(kitDir, FILE_NAME)
        AtomicFile.writeText(file, Json.write(toJson(clips)) + "\n")
        return file
    }

    /** The kit's grooves, oldest promise first; empty when none saved. */
    fun load(kitDir: File): List<Mpc3Clip> {
        val file = File(kitDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    fun delete(kitDir: File): Boolean = File(kitDir, FILE_NAME).delete()

    private fun toJson(clips: List<Mpc3Clip>): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "clips" to JsonValue.Arr(clips.map { clipToJson(it) }),
        ),
    )

    private fun fromJson(root: JsonValue): List<Mpc3Clip> {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw JsonException("groove.json has no version")
        if (version !in MIN_VERSION..VERSION) {
            throw JsonException("groove.json version $version is not supported (this build reads $MIN_VERSION..$VERSION)")
        }
        return obj["clips"]?.arr().orEmpty().map { clipFromJson(it) }
    }

    /** The clip codec, shared with every other sidecar that persists one. */
    internal fun clipToJson(clip: Mpc3Clip): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(clip.name),
            "bars" to JsonValue.Num(clip.bars.toDouble()),
            // Written only when the clip is not in 4/4, so the clip objects
            // in a 4/4 groove.json are byte-identical to the ones already
            // on cards. The file's VERSION still moves, because `bars`
            // changed meaning for the clips that DO carry this - see the
            // constant. Omitting the key is not a compatibility promise;
            // the version is.
            *(
                if (clip.pulsesPerBar != Mpc3Clip.PULSES_PER_BAR) {
                    arrayOf("pulsesPerBar" to JsonValue.Num(clip.pulsesPerBar.toDouble()))
                } else {
                    emptyArray()
                }
                ),
            "notes" to JsonValue.Arr(
                clip.notes.map { n ->
                    JsonValue.Obj(
                        linkedMapOf(
                            "note" to JsonValue.Num(n.note.toDouble()),
                            "timePulses" to JsonValue.Num(n.timePulses.toDouble()),
                            "velocity" to JsonValue.Num(n.velocity.toDouble()),
                            "lengthPulses" to JsonValue.Num(n.lengthPulses.toDouble()),
                        ),
                    )
                },
            ),
        ),
    )

    /**
     * Typed accessors throughout: a clip written by another tool, or torn
     * mid-write, refuses as a [JsonException] in words - never a cast that
     * fails or a null that is not there (the hardening contract, BBB4).
     */
    internal fun clipFromJson(clipJson: JsonValue): Mpc3Clip {
        val c = clipJson.obj()
        return Mpc3Clip(
            name = c["name"]?.str() ?: throw JsonException("clip has no name"),
            bars = c["bars"]?.int() ?: throw JsonException("clip has no bars"),
            pulsesPerBar = c["pulsesPerBar"]?.long() ?: Mpc3Clip.PULSES_PER_BAR,
            notes = c["notes"]?.arr().orEmpty().map { noteJson ->
                val n = noteJson.obj()
                Mpc3Note(
                    note = n["note"]?.int() ?: throw JsonException("note has no note"),
                    timePulses = n["timePulses"]?.long() ?: throw JsonException("note has no timePulses"),
                    velocity = n["velocity"]?.num()?.toFloat() ?: throw JsonException("note has no velocity"),
                    lengthPulses = n["lengthPulses"]?.long() ?: throw JsonException("note has no lengthPulses"),
                )
            },
        )
    }
}
