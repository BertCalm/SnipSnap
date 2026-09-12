package com.snipsnap.loop

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.IOException

/**
 * A set of rings on disk: `orbits.json`, a sidecar in the folder whose kit
 * and snips it names — the shape `loop.json` and `groove.json` already use,
 * for the same reason: every reference is a bare filename resolved against
 * that folder, so the folder still plays after a copy or a share.
 */
object OrbitStore {

    const val FILE_NAME = "orbits.json"

    /**
     * 7: a set may carry `sections` — an arrangement, each section naming
     * how many bars it lasts and which rings play in it. A set without
     * them plays every ring forever, which is what every set did before.
     * 6: a set carries a `seed`, and a hit may carry a `chance`, an
     * `everyLaps`/`onLap` conditional and a `ratchet`. A hit without them
     * sounds once on every lap, which is what every hit did before.
     * 5: a hit may carry a `length` — how long it sounds, in pulses. A hit
     * without one plays its sample out, which is what every hit did before.
     * 4: a hit may carry an `offset` — its lean off its step, in pulses. A
     * hit without one sits on its step, which is where every hit sat before.
     * 3: a ring has a `span` (FREE, HALF, ONE, TWO, FOUR) where 2 had a
     * boolean `lockToBar` and 1 had a `mode`. Every older file still loads:
     * `lockToBar: true` and `SAME_LAP` both become `ONE`, and a version 1
     * ring's voice is the pads its hits already named.
     */
    const val VERSION = 7
    private const val FIRST_VERSION = 1

    fun save(set: OrbitSet, dir: File): File {
        dir.mkdirs()
        require(dir.isDirectory) { "not a directory: $dir" }
        val file = File(dir, FILE_NAME)
        file.writeText(Json.write(toJson(set)) + "\n", Charsets.UTF_8)
        return file
    }

    @Throws(IOException::class)
    fun load(dir: File): OrbitSet {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) throw IOException("no $FILE_NAME in $dir")
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    fun exists(dir: File): Boolean = File(dir, FILE_NAME).isFile

    private fun num(v: Number) = JsonValue.Num(v.toDouble())

    private fun toJson(set: OrbitSet): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "version" to num(VERSION),
            "bpm" to num(set.bpm),
            "lapSteps" to num(set.lapSteps),
            "swing" to num(set.swing),
            "sampleRate" to num(set.sampleRate),
            "seed" to num(set.seed),
            "orbits" to JsonValue.Arr(set.orbits.map { orbitJson(it) }),
            // Only where there is one, so a set with no arrangement keeps
            // the exact shape it had at version 6 - the same rule the hit
            // fields follow, stated once and held to at every bump.
            *(
                if (set.sections.isEmpty()) {
                    emptyArray()
                } else {
                    arrayOf("sections" to JsonValue.Arr(set.sections.map { sectionJson(it) }))
                }
                ),
        ),
    )

    private fun sectionJson(section: OrbitSection): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(section.name),
            "bars" to num(section.bars),
            // Sorted, because a set is a file a player may read and diff,
            // and a set of indices has no order of its own to preserve.
            "plays" to JsonValue.Arr(section.plays.sorted().map { num(it) }),
        ),
    )

    private fun orbitJson(orbit: Orbit): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(orbit.name),
            "steps" to num(orbit.steps),
            "span" to JsonValue.Str(orbit.span.name),
            "voice" to JsonValue.Arr(orbit.pads.map { num(it) }),
            "engaged" to JsonValue.Bool(orbit.engaged),
            "level" to num(orbit.level),
            "pan" to num(orbit.pan),
            "content" to contentJson(orbit.content),
        ),
    )

    private fun contentJson(content: OrbitContent): JsonValue = when (content) {
        is SnipOrbit -> JsonValue.Obj(
            linkedMapOf(
                "type" to JsonValue.Str("snip"),
                "sampleFile" to JsonValue.Str(content.sampleFile),
            ),
        )
        is PatternOrbit -> JsonValue.Obj(
            linkedMapOf(
                "type" to JsonValue.Str("pattern"),
                "kit" to JsonValue.Str(content.kit),
                "hits" to JsonValue.Arr(
                    content.hits.map {
                        JsonValue.Obj(
                            linkedMapOf(
                                "step" to num(it.step),
                                "slot" to num(it.slot),
                                "velocity" to num(it.velocity),
                                // Only where there is something to say: a
                                // hit that neither leans nor stops early
                                // keeps the exact shape it always had, and
                                // the file grows a field only where one is
                                // needed. Stated as the rule rather than
                                // against a version number, since that is
                                // what has to hold at the next bump too.
                                *(if (it.offset != 0L) arrayOf("offset" to num(it.offset)) else emptyArray()),
                                *(if (it.gated) arrayOf("length" to num(it.length)) else emptyArray()),
                                *(if (it.chance != OrbitHit.ALWAYS) arrayOf("chance" to num(it.chance)) else emptyArray()),
                                // The pair together or not at all: `onLap`
                                // alone says nothing, and reading a stray
                                // one back would refuse the file over a
                                // field that never meant anything.
                                *(
                                    if (it.everyLaps != OrbitHit.EVERY_LAP) {
                                        arrayOf("everyLaps" to num(it.everyLaps), "onLap" to num(it.onLap))
                                    } else {
                                        emptyArray()
                                    }
                                    ),
                                *(if (it.ratcheted) arrayOf("ratchet" to num(it.ratchet)) else emptyArray()),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private fun fromJson(root: JsonValue): OrbitSet {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw IllegalStateException("$FILE_NAME has no version")
        check(version in FIRST_VERSION..VERSION) { "$FILE_NAME is version $version, this build reads $FIRST_VERSION..$VERSION" }
        return OrbitSet(
            orbits = obj["orbits"]?.arr().orEmpty().map { orbitFrom(it) },
            bpm = (obj["bpm"]?.num() ?: 90.0).toFloat(),
            sampleRate = obj["sampleRate"]?.int() ?: 48_000,
            lapSteps = obj["lapSteps"]?.int() ?: OrbitSet.DEFAULT_LAP_STEPS,
            // Optional since version 3 gained it; a file without it is straight.
            swing = obj["swing"]?.int() ?: OrbitSet.STRAIGHT_SWING,
            // Optional since version 6 gained it. A file without one has
            // nothing to roll, so any seed is the right seed for it.
            seed = obj["seed"]?.int() ?: OrbitSet.DEFAULT_SEED,
            // Absent before version 7, and absent since wherever a set has
            // no arrangement. Either way every ring plays, forever.
            sections = obj["sections"]?.arr().orEmpty().map { sectionFrom(it) },
        )
    }

    private fun sectionFrom(value: JsonValue): OrbitSection {
        val o = value.obj()
        return OrbitSection(
            name = o["name"]?.str() ?: throw IllegalStateException("section has no name"),
            bars = o["bars"]?.int() ?: throw IllegalStateException("section has no bars"),
            plays = o["plays"]?.arr().orEmpty().map { it.int() }.toSet(),
        )
    }

    private fun orbitFrom(value: JsonValue): Orbit {
        val o = value.obj()
        // Version 3 writes a span name. Version 2 wrote lockToBar; version 1
        // a mode name. Both older forms only ever meant "one bar" or "free".
        val span = when {
            o["span"] != null -> OrbitSpan.fromName(o["span"]?.str())
            o["lockToBar"] != null -> if (o["lockToBar"]?.bool() == true) OrbitSpan.ONE else OrbitSpan.FREE
            else -> when (val mode = o["mode"]?.str()) {
                null, "SAME_SPEED" -> OrbitSpan.FREE
                "SAME_LAP" -> OrbitSpan.ONE
                else -> throw IllegalStateException("unknown orbit mode '$mode'")
            }
        }
        val content = contentFrom(o["content"] ?: throw IllegalStateException("orbit has no content"))
        // A snip ring has no voice; a version 1 pattern ring's voice is derived from its hits.
        val voice = if (content is SnipOrbit) emptyList() else o["voice"]?.arr().orEmpty().map { it.int() }
        return Orbit(
            name = o["name"]?.str() ?: "",
            steps = o["steps"]?.int() ?: OrbitSet.DEFAULT_LAP_STEPS,
            content = content,
            span = span,
            voice = voice,
            engaged = o["engaged"]?.bool() ?: true,
            level = (o["level"]?.num() ?: 1.0).toFloat(),
            pan = (o["pan"]?.num() ?: 0.0).toFloat(),
        )
    }

    private fun contentFrom(value: JsonValue): OrbitContent {
        val c = value.obj()
        return when (val type = c["type"]?.str()) {
            "snip" -> SnipOrbit(c["sampleFile"]?.str() ?: throw IllegalStateException("snip orbit has no sampleFile"))
            "pattern" -> PatternOrbit(
                kit = c["kit"]?.str() ?: throw IllegalStateException("pattern orbit has no kit"),
                hits = c["hits"]?.arr().orEmpty().map { hitFrom(it) },
            )
            else -> throw IllegalStateException("unknown orbit content type '$type'")
        }
    }

    private fun hitFrom(value: JsonValue): OrbitHit {
        val h = value.obj()
        return OrbitHit(
            step = h["step"]?.int() ?: 0,
            slot = h["slot"]?.int() ?: 1,
            velocity = (h["velocity"]?.num() ?: 1.0).toFloat(),
            // Absent before version 4, and absent since wherever a hit is
            // straight. Either way the hit sits on its step.
            offset = h["offset"]?.long() ?: 0L,
            // Absent before version 5, and absent since wherever a hit
            // plays out. Either way it lasts as long as its sample.
            length = h["length"]?.long() ?: OrbitHit.WHOLE_SAMPLE,
            // Absent before version 6, and absent since wherever a hit is
            // certain and strikes once. Either way it sounds every lap.
            chance = h["chance"]?.int() ?: OrbitHit.ALWAYS,
            everyLaps = h["everyLaps"]?.int() ?: OrbitHit.EVERY_LAP,
            // The pair together on the way in as on the way out. A stray
            // `onLap` with no `everyLaps` says nothing - the writer never
            // makes one - and reading it anyway refused the whole file by
            // name over a field that could not have meant anything.
            onLap = (if (h["everyLaps"] != null) h["onLap"]?.int() else null) ?: 0,
            ratchet = h["ratchet"]?.int() ?: OrbitHit.ONCE,
        )
    }
}
