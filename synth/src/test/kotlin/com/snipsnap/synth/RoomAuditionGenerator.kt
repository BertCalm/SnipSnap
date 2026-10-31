package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Renders ROOM's listening gate under testkit/room-audition/ (gitignored): the real Kotlin section after the
 * Python prototype's gate (docs/superpowers/specs/2026-10-31-room-section-design-brief.md, "Heard at the
 * prototype gate"). The owner wanted bigger rooms, and heard DISTANCE as "just more reverb"; this round asks
 * about the hall SIZE now reaches, the reworked DISTANCE, the default room, and whether ROOM wants a one-tap
 * character.
 *
 * Five sources (the THUMP kick, snare, clap and closed hat, and the room recording's loudest hit), each dry,
 * through SPRING at its defaults, and through ROOM at its default, across SIZE, at both WALLS ends and across
 * DISTANCE. Then SAME LOUDNESS: the snare and clap dry, in the default room and in the hall at one loudness
 * over the hit, because a bigger room plays louder at the same peak (the hall about 7 dB on the snare).
 *
 * Levels as FORGE's gates: one gain per source, the dry clip's [AuditionLevel] gain, so every clip keeps the
 * peak the rack gives it; the manifest records each clip's loudness over the hit against its dry. Writes
 * manifest.json, with the gate's questions in `gate`, and index.html
 * (synth/src/test/resources/audition/room-audition.html). Run via `./gradlew :synth:generateRoomAudition`.
 */
object RoomAuditionGenerator {

    private const val AUDITION_LEVEL = 0.03f

    private class Clip(val id: String, val label: String, val snip: Snip)

    private val ROOMS: List<Triple<String, String, Map<String, Float>>> = listOf(
        Triple("room", "ROOM (DEFAULT)", Room.defaults()),
        Triple("size_055", "SIZE 0.55 (A LIVE ROOM)", mapOf("SIZE" to 0.55f)),
        Triple("size_078", "SIZE 0.78 (A BIG ROOM)", mapOf("SIZE" to 0.78f)),
        Triple("size_1", "SIZE 1 (A HALL)", mapOf("SIZE" to 1f)),
        Triple("walls_0", "WALLS 0 (SOFT)", mapOf("WALLS" to 0f)),
        Triple("walls_1", "WALLS 1 (HARD)", mapOf("WALLS" to 1f)),
        Triple("distance_005", "DISTANCE 0.05 (AT THE DRUM)", mapOf("DISTANCE" to 0.05f)),
        Triple("distance_035", "DISTANCE 0.35 (CLOSE)", mapOf("DISTANCE" to 0.35f)),
        Triple("distance_1", "DISTANCE 1 (THE FAR CORNER)", mapOf("DISTANCE" to 1f)),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/room-audition")
        val repo = File(args.getOrNull(1) ?: "..")
        root.mkdirs()
        val sources = linkedMapOf(
            "SNARE" to Thump.render(ThumpVoice.SNARE),
            "CLAP" to Thump.render(ThumpVoice.CLAP),
            "KICK" to Thump.render(ThumpVoice.KICK),
            "HAT" to Thump.render(ThumpVoice.HAT_CLOSED),
            "CHOP" to ForgeAuditionGenerator.chop(File(repo, "reference/live3 room take.wav")),
        )

        val json = StringBuilder("{\n").append(gateJson()).append(",\n\"sections\": [\n")
        var count = 0
        val sections = mutableListOf<Pair<String, List<Pair<Clip, Snip?>>>>()
        for ((name, dry) in sources) {
            val clips = mutableListOf(Clip("dry", "$name DRY", dry) to dry, Clip("spring", "$name SPRING (DEFAULT)", Spring.process(dry)) to dry)
            for ((id, label, m) in ROOMS) clips += Clip(id, "$name $label", Room.process(dry, m)) to dry
            sections += name to clips
        }
        val levelled = mutableListOf<Pair<Clip, Snip?>>()
        for ((name, dry) in listOf("SNARE" to sources.getValue("SNARE"), "CLAP" to sources.getValue("CLAP"))) {
            for ((id, label, snip) in listOf(
                Triple("dry", "DRY", dry), Triple("room", "ROOM", Room.process(dry)), Triple("hall", "HALL", Room.process(dry, mapOf("SIZE" to 1f))),
            )) levelled += Clip("${name.lowercase()}_$id", "$name $label, SAME LOUDNESS", headLevelled(snip, dry.frameCount)) to null
        }
        sections += "LEVELLED" to levelled

        for ((si, entry) in sections.withIndex()) {
            val (name, clips) = entry
            val dir = File(root, name).apply { mkdirs() }
            val gain = clips.first().second?.let { ForgeAuditionGenerator.sourceGain(it) } ?: 1f
            json.append("  {\"id\": ${q(name)}, \"title\": ${q(if (name == "LEVELLED") "Same loudness" else name)}, \"note\": ${q(noteFor(name))}, \"clips\": [\n")
            for ((ci, pair) in clips.withIndex()) {
                val (clip, dry) = pair
                WavWriter.write(File(dir, "${clip.id}.wav"), ForgeAuditionGenerator.scaled(clip.snip, gain), WavWriter.BitDepth.PCM_16)
                json.append("    {\"id\": ${q(clip.id)}, \"file\": ${q("$name/${clip.id}.wav")}, \"label\": ${q(clip.label)}, ")
                json.append("\"crest\": ${"%.2f".format(ForgeAuditionGenerator.crest(clip.snip))}")
                if (dry != null) json.append(", \"loudnessDb\": ${"%.2f".format(headDb(clip.snip, dry))}")
                json.append("}").append(if (ci < clips.size - 1) ",\n" else "\n")
                count++
            }
            json.append("  ]}").append(if (si < sections.size - 1) ",\n" else "\n")
        }
        json.append("]}\n")
        File(root, "manifest.json").writeText(json.toString())
        val page = RoomAuditionGenerator::class.java.getResourceAsStream("/audition/room-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { o -> page.use { it.copyTo(o) } }
        println("room audition: $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    /** Loudness over the hit itself (the dry's length), dB against the dry: a room's tail would dilute a whole-file measure. */
    private fun headDb(s: Snip, dry: Snip): Float = 20 * log10(headRms(s, dry.frameCount) / headRms(dry, dry.frameCount).coerceAtLeast(1e-9f))

    private fun headRms(s: Snip, frames: Int): Float {
        var sum = 0.0
        val n = minOf(frames, s.frameCount) * s.channels
        for (i in 0 until n) sum += s.samples[i].toDouble() * s.samples[i]
        return sqrt(sum / n.coerceAtLeast(1)).toFloat()
    }

    /** [s] at the audition level over its first [frames], peak-guarded. */
    private fun headLevelled(s: Snip, frames: Int): Snip {
        var g = AUDITION_LEVEL / headRms(s, frames).coerceAtLeast(1e-9f)
        if (s.peak() * g > 0.99f) g = 0.99f / s.peak()
        return Snip(FloatArray(s.samples.size) { s.samples[it] * g }, s.channels, s.sampleRate)
    }

    private fun noteFor(name: String): String = when (name) {
        "SNARE" -> "The THUMP snare. The dB beside each clip is how much louder than dry over the hit itself, at the same peak."
        "CLAP" -> "The THUMP clap: four quick slaps, where a room's walls and size are easiest to hear."
        "KICK" -> "The THUMP kick. DISTANCE is the one to try here: close lifts the bass, far dulls the top and lets the room in."
        "HAT" -> "The THUMP closed hat: short and bright, so the walls and the mic's distance show in its top."
        "CHOP" -> "The loudest hit of the room recording: a sound that already has a room in it."
        else -> "The snare and the clap dry, in the default room and in the hall, at one loudness over the hit. If the hall still sounds bigger here, it is the room, not the level."
    }

    private fun gateJson(): String {
        val size = listOf("SNARE/room", "SNARE/size_055", "SNARE/size_078", "SNARE/size_1", "CLAP/room", "CLAP/size_1")
        val distance = listOf("KICK/distance_005", "KICK/distance_1", "SNARE/distance_005", "SNARE/distance_035", "SNARE/distance_1")
        val loud = listOf("LEVELLED/snare_room", "LEVELLED/snare_hall", "LEVELLED/clap_room", "LEVELLED/clap_hall")
        val start = listOf("KICK/room", "SNARE/room", "CLAP/room", "HAT/room", "CHOP/room")
        fun ids(xs: List<String>) = xs.joinToString(", ") { "\"$it\"" }
        val questions = listOf(
            """{"id": "size", "title": "SIZE now reaches a hall. Big enough?", "clips": [${ids(size)}], "options": ["Big enough", "Still want bigger", "Too big now"]}""",
            """{"id": "distance", "title": "DISTANCE, at the drum to the far corner: does far sound like the mic moved back now?", "clips": [${ids(distance)}], "options": ["Yes", "Still just more reverb", "Can't hear it"]}""",
            """{"id": "level", "title": "At the same loudness: does the hall still sound bigger than the default room?", "clips": [${ids(loud)}], "options": ["Bigger", "Just louder", "Can't tell"]}""",
            """{"id": "default", "title": "The default room on the kit: a good starting point?", "clips": [${ids(start)}], "options": ["Good", "Too much room", "Too little room"]}""",
            """{"id": "chip", "title": "Should ROOM get a one-tap character on the pad sheet, the way FORGE has 'forged'?", "clips": ["SNARE/dry", "SNARE/room"], "options": ["Yes", "No, the rack is enough", "Later"]}""",
        )
        return """"gate": {"id": "room-kotlin", "name": "ROOM listening gate", "title": "Room Listening Gate", "heading": "ROOM", """ +
            """"intro": ${q("ROOM, built in Kotlin after the prototype's gate. You wanted bigger rooms, and heard DISTANCE as just more reverb. SIZE now reaches a hall (28 x 20 x 11 m), and DISTANCE moves the mic: close lifts the drum's bass, far dulls its top, and the room comes in. The rack still caps every tail at 1 second, so a hall's decay stays under it. These are the real section's renders; nothing here has been heard.")}, """ +
            """"questionsTitle": "The gate: five questions", "task": "generateRoomAudition", "questions": [${questions.joinToString(", ")}]}"""
    }

    private fun q(s: String): String = ForgeAuditionGenerator.q(s)
}
