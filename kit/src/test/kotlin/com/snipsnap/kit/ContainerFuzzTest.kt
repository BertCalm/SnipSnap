package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.SampleName
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.AcvsException
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Hardening, round four: the containers and the text formats, damaged
 * *inside* the wrapper so the mutation reaches the reader that matters.
 *
 * Round one fuzzed the `.xpn` as zip bytes - nearly every round died at
 * the zip layer and the XML parser behind it never saw a bad byte. Here
 * the zip stays valid and the program XML inside it is what tears; the
 * ACVS container stays valid and its JSON payload is what gets rewritten;
 * the `.sfz` text, the backup zip, the answer sidecar, the WAV header
 * walker and the two name parsers each get their own batch.
 *
 * Same contract: a valid result or a typed refusal - [IllegalArgumentException],
 * [java.io.IOException], [JsonException], [AcvsException]. Anything else is
 * a reader trusting a byte it has not checked.
 */
class ContainerFuzzTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("container-fuzz").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun isTypedRefusal(t: Throwable): Boolean = when (t) {
        is IllegalArgumentException,
        is java.io.IOException,
        is JsonException,
        is AcvsException,
        -> true
        else -> false
    }

    private fun <T> batch(name: String, rounds: Int, seed: Int, make: (Random, Int) -> T, read: (T) -> Unit) {
        val rnd = Random(seed)
        val start = System.nanoTime()
        for (i in 0 until rounds) {
            val mutant = make(rnd, i)
            try {
                read(mutant)
            } catch (t: Throwable) {
                if (!isTypedRefusal(t)) {
                    fail("$name round $i: untyped ${t::class.simpleName}: ${t.message}\n${describe(mutant)}")
                }
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 60_000, "$name: $rounds rounds took ${ms}ms - a reader may be hanging")
    }

    private fun describe(m: Any?): String = when (m) {
        is String -> m.take(2_000)
        is ByteArray -> "${m.size} bytes: " + String(m, Charsets.ISO_8859_1).take(2_000)
        else -> m.toString().take(2_000)
    }

    // ---- mutations -----------------------------------------------------------

    private fun mutateBytes(valid: ByteArray, rnd: Random): ByteArray {
        val bytes = valid.copyOf()
        when (rnd.nextInt(4)) {
            0 -> repeat(1 + rnd.nextInt(8)) {
                if (bytes.isNotEmpty()) bytes[rnd.nextInt(bytes.size)] = rnd.nextInt(256).toByte()
            }
            1 -> return bytes.copyOf(if (bytes.isEmpty()) 0 else rnd.nextInt(bytes.size))
            2 -> if (bytes.isNotEmpty()) {
                val at = rnd.nextInt(bytes.size)
                for (j in at until minOf(bytes.size, at + rnd.nextInt(32))) bytes[j] = 0
            }
            3 -> if (bytes.isNotEmpty()) {
                val at = rnd.nextInt(bytes.size)
                for (j in at until minOf(bytes.size, at + 4)) bytes[j] = 0xFF.toByte()
            }
        }
        return bytes
    }

    private val JUNK_WORDS = listOf(
        "", " ", "-1", "0", "1e300", "2147483648", "9223372036854775808", "0.5", "NaN", "Infinity",
        "../../escaped", "/etc/passwd", "<", "&amp;", "&lt;", "&#x0;", "\"", "999999999999", "True", "null",
    )

    /**
     * Text-level damage for line formats (sfz, XML): a line dropped, a line
     * doubled, a token swapped for a junk word - or the bytes torn. Returns
     * bytes, not a String: a torn byte run has to reach the reader as the
     * malformed UTF-8 it is, and a decode-then-re-encode would launder it
     * into replacement characters first.
     */
    private fun mutateText(valid: String, rnd: Random): ByteArray {
        val lines = valid.lines().toMutableList()
        when (rnd.nextInt(5)) {
            0 -> if (lines.isNotEmpty()) lines.removeAt(rnd.nextInt(lines.size))
            1 -> if (lines.isNotEmpty()) { val i = rnd.nextInt(lines.size); lines.add(i, lines[i]) }
            2, 3 -> {
                // Replace one token: a value after '=' or a text node between tags.
                if (lines.isNotEmpty()) {
                    val i = rnd.nextInt(lines.size)
                    val line = lines[i]
                    val tokens = Regex("=([^\\s<>]*)|>([^<]+)<").findAll(line).toList()
                    if (tokens.isNotEmpty()) {
                        val t = tokens[rnd.nextInt(tokens.size)]
                        val g = t.groups[1] ?: t.groups[2]!!
                        lines[i] = line.replaceRange(g.range, JUNK_WORDS[rnd.nextInt(JUNK_WORDS.size)])
                    }
                }
            }
            else -> return mutateBytes(valid.toByteArray(Charsets.UTF_8), rnd)
        }
        return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun junk(rnd: Random): JsonValue = when (rnd.nextInt(10)) {
        0 -> JsonValue.Null
        1 -> JsonValue.Str("")
        2 -> JsonValue.Str("../../escaped.wav")
        3 -> JsonValue.Num(-1.0)
        4 -> JsonValue.Num(1e300)
        5 -> JsonValue.Num(2147483648.0)
        6 -> JsonValue.Num(0.5)
        7 -> JsonValue.Arr(emptyList())
        8 -> JsonValue.Obj(emptyMap())
        else -> JsonValue.Bool(rnd.nextBoolean())
    }

    /** Replace a random node anywhere in the tree, or drop a random key. */
    private fun mutateTree(v: JsonValue, rnd: Random): JsonValue {
        val paths = mutableListOf<List<Any>>()
        fun walk(node: JsonValue, path: List<Any>) {
            paths += path
            when (node) {
                is JsonValue.Obj -> node.entries.forEach { (k, c) -> walk(c, path + k) }
                is JsonValue.Arr -> node.items.forEachIndexed { i, c -> walk(c, path + i) }
                else -> Unit
            }
        }
        walk(v, emptyList())
        val target = paths[rnd.nextInt(paths.size)]
        val drop = target.isNotEmpty() && target.last() is String && rnd.nextInt(3) == 0
        fun rebuild(node: JsonValue, depth: Int): JsonValue {
            if (depth == target.size) return junk(rnd)
            val step = target[depth]
            return when (node) {
                is JsonValue.Obj -> {
                    val key = step as String
                    if (drop && depth == target.size - 1) JsonValue.Obj(node.entries.filterKeys { it != key })
                    else JsonValue.Obj(node.entries.mapValues { (k, c) -> if (k == key) rebuild(c, depth + 1) else c })
                }
                is JsonValue.Arr -> {
                    val idx = step as Int
                    JsonValue.Arr(node.items.mapIndexed { i, c -> if (i == idx) rebuild(c, depth + 1) else c })
                }
                else -> node
            }
        }
        return rebuild(v, 0)
    }

    private fun mutateJsonText(valid: String, tree: JsonValue, rnd: Random, i: Int): String =
        if (i % 2 == 0) String(mutateBytes(valid.toByteArray(Charsets.UTF_8), rnd), Charsets.UTF_8)
        else Json.write(mutateTree(tree, rnd))

    // ---- seeds ---------------------------------------------------------------

    private fun tone(seed: Int, frames: Int = 4_410): Snip =
        Snip(FloatArray(frames) { i -> (0.5 * Math.sin(i / (10.0 + seed))).toFloat() }, 1, 44_100)

    /** A kit folder with a plain pad, a layered pad and a chain pad - every shape the exporters know. */
    private fun seedKit(name: String = "Seed"): Pair<Kit, File> {
        val dir = File(temp, "kits/$name").also { it.mkdirs() }
        WavWriter.write(File(dir, "kick.wav"), tone(1))
        WavWriter.write(File(dir, "snare_soft.wav"), tone(2))
        WavWriter.write(File(dir, "snare.wav"), tone(3))
        WavWriter.write(File(dir, "loop.wav"), tone(4, 8_000))
        val kit = Kit(
            name,
            listOf(
                KitPad(1, "kick.wav", drumClass = DrumClass.KICK, colorHex = "#e8542e", level = 0.9f, pan = 0.25f, tuneCoarse = -3, tuneFine = 20),
                KitPad(2, "snare.wav", velocityLayers = listOf(KitLayer("snare_soft.wav", 0, 63), KitLayer("snare.wav", 64, 127)), muteGroup = 1, oneShot = false),
                KitPad(
                    3, "loop.wav",
                    chain = ChainInfo(listOf(0L, 2_000L, 4_000L, 6_000L), cycle = 2, zones = listOf(ChainZone(0, 63, 0, 2), ChainZone(64, 127, 1, 2))),
                ),
            ),
            tempoBpm = 92f,
        )
        KitStore.save(kit, dir)
        return kit to dir
    }

    private fun xpmXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPCVObject>
          <Version><File_Version>2.1</File_Version></Version>
          <Program type="Drum">
            <ProgramName>Seed</ProgramName>
            <Instruments>
              <Instrument number="1">
                <Volume>0.9</Volume><Pan>0.25</Pan><TuneCoarse>-3</TuneCoarse><TuneFine>20</TuneFine>
                <MuteGroup>0</MuteGroup><OneShot>True</OneShot>
                <VolumeAttack>0.1</VolumeAttack><VolumeDecay>0.3</VolumeDecay><Cutoff>0.8</Cutoff><Resonance>0.2</Resonance>
                <Layers>
                  <Layer number="1"><VelStart>0</VelStart><VelEnd>127</VelEnd><SampleName>kick</SampleName></Layer>
                </Layers>
              </Instrument>
              <Instrument number="2">
                <Volume>0.707946</Volume><Pan>0.5</Pan><MuteGroup>1</MuteGroup><OneShot>False</OneShot>
                <Layers>
                  <Layer number="1"><VelStart>0</VelStart><VelEnd>63</VelEnd><SampleName>snare_soft</SampleName></Layer>
                  <Layer number="2"><VelStart>64</VelStart><VelEnd>127</VelEnd><SampleName>snare</SampleName></Layer>
                </Layers>
              </Instrument>
            </Instruments>
          </Program>
        </MPCVObject>
    """.trimIndent()

    private fun xpnWith(xml: ByteArray, kitDir: File, out: File): File {
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Seed.xpm")); zip.write(xml); zip.closeEntry()
            for (n in listOf("kick", "snare_soft", "snare")) {
                zip.putNextEntry(ZipEntry("$n.wav")); zip.write(File(kitDir, "$n.wav").readBytes()); zip.closeEntry()
            }
        }
        return out
    }

    // ---- the doors -----------------------------------------------------------

    @Test
    fun `the xpm program inside a sound xpn survives mutation`() {
        val (_, kitDir) = seedKit()
        val valid = xpmXml()
        val xpn = File(temp, "fuzz.xpn")
        val dest = File(temp, "xpn-out")
        // The seed itself must import, or the batch proves nothing.
        XpnImporter.import(xpnWith(valid.toByteArray(Charsets.UTF_8), kitDir, xpn), dest, overwrite = true)
        batch("XpnImporter (xpm inside)", rounds = 400, seed = 41, make = { rnd, _ -> mutateText(valid, rnd) }) { xml ->
            XpnImporter.import(xpnWith(xml, kitDir, xpn), dest, overwrite = true)
        }
        assertTrue(temp.walkTopDown().none { it.name.startsWith("escaped") }, "a mutated sample name climbed out")
    }

    @Test
    fun `the sfz text survives mutation`() {
        val (kit, kitDir) = seedKit()
        val sfz = SfzWriter.write(kit, kitDir, File(temp, "sfz-out"), overwrite = true)
        val valid = sfz.readText()
        val dest = File(temp, "sfz-in")
        SfzImporter.import(sfz, dest, overwrite = true)
        batch("SfzImporter", rounds = 600, seed = 42, make = { rnd, _ -> mutateText(valid, rnd) }) { bytes ->
            sfz.writeBytes(bytes)
            SfzImporter.import(sfz, dest, overwrite = true)
        }
        assertTrue(temp.walkTopDown().none { it.name.startsWith("escaped") }, "a mutated sample path climbed out")
    }

    @Test
    fun `the json payload inside a sound acvs container survives mutation`() {
        val (kit, kitDir) = seedKit()
        val card = File(temp, "card")
        Mpc3Exporter.exportTrack(kit, kitDir, card, clip = Mpc3Clip("Seed", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(38, 480, 0.7f))))
        val xtd = File(card, "Seed.xtd")
        val container = Acvs.read(xtd.readBytes())
        val valid = container.payloadText
        val tree = Json.parse(valid)
        val dest = File(temp, "xtd-in")
        Mpc3Importer.import(xtd, dest, overwrite = true)
        // Each round re-deflates the container and copies four samples: fewer
        // rounds than the byte-only batches, still hundreds of shapes.
        batch("Mpc3Importer (payload inside)", rounds = 200, seed = 43, make = { rnd, i -> mutateJsonText(valid, tree, rnd, i) }) { payload ->
            xtd.writeBytes(Acvs.write(container.header, payload))
            Mpc3Importer.import(xtd, dest, overwrite = true)
        }
        assertTrue(temp.walkTopDown().none { it.name.startsWith("escaped") }, "a mutated sample name climbed out")
    }

    @Test
    fun `a backup zip survives mutation`() {
        seedKit("One"); seedKit("Two")
        val zip = File(temp, "backup.zip")
        KitBackup.backup(File(temp, "kits"), zip, overwrite = true)
        val valid = zip.readBytes()
        val dest = File(temp, "restore")
        KitBackup.restore(zip, dest, overwrite = true)
        batch("KitBackup.restore", rounds = 400, seed = 44, make = { rnd, _ -> mutateBytes(valid, rnd) }) { bytes ->
            zip.writeBytes(bytes)
            KitBackup.restore(zip, dest, overwrite = true)
        }
        assertTrue(temp.walkTopDown().none { it.name.startsWith("escaped") }, "a mutated entry name climbed out")
    }

    @Test
    fun `the answer sidecar survives mutation`() {
        val dir = File(temp, "answer").also { it.mkdirs() }
        val line = Mpc3Clip("Answer", 2, listOf(Mpc3Note(40, 0, 0.8f), Mpc3Note(43, 960, 0.6f, 480)))
        AnswerStore.save(
            dir,
            AnswerStore.StoredAnswer(
                seed = 7, name = "Chamber Answer", sampleFile = "answer.wav", clip = line,
                band = listOf(AnswerStore.BandMember("Keys", "keys.wav", Mpc3Clip("Keys", 1, listOf(Mpc3Note(52, 240, 0.5f))))),
            ),
        )
        val file = File(dir, AnswerStore.FILE_NAME)
        val valid = file.readText()
        val tree = Json.parse(valid)
        batch("AnswerStore.load", rounds = 1_500, seed = 45, make = { rnd, i -> mutateJsonText(valid, tree, rnd, i) }) { text ->
            file.writeText(text)
            AnswerStore.load(dir)
        }
    }

    @Test
    fun `the wav header walker survives mutation`() {
        val f = File(temp, "info.wav")
        WavWriter.write(f, tone(5))
        val valid = f.readBytes()
        batch("WavInfo.read", rounds = 2_000, seed = 46, make = { rnd, _ -> mutateBytes(valid, rnd) }) { bytes ->
            f.writeBytes(bytes)
            val info = WavInfo.read(f)
            assertTrue(info.frameCount >= 0, "negative frame count $info")
        }
    }

    @Test
    fun `the name parsers survive any string`() {
        val alphabet = "ABCDEFGabcdefg#b -_0123456789majorminpentchromatic. é一￿"
        fun randomString(rnd: Random): String = when (rnd.nextInt(4)) {
            0 -> ""
            1 -> String(CharArray(rnd.nextInt(40)) { alphabet[rnd.nextInt(alphabet.length)] })
            2 -> String(ByteArray(rnd.nextInt(40)) { rnd.nextInt(256).toByte() }, Charsets.ISO_8859_1)
            else -> listOf("Am", "C major", "F#minpent", "Kick_124bpm_Am", "loop 90 BPM Dbmaj", "x".repeat(10_000))[rnd.nextInt(6)]
        }
        batch("KeySpec.parse", rounds = 3_000, seed = 47, make = { rnd, _ -> randomString(rnd) }) { KeySpec.parse(it) }
        batch("SampleName.parse", rounds = 3_000, seed = 48, make = { rnd, _ -> randomString(rnd) }) { s ->
            val hints = SampleName.parse(s)
            hints.bpm?.let { assertTrue(it.isFinite() && it > 0f, "a nonsense tempo $it from '$s'") }
        }
    }
}
