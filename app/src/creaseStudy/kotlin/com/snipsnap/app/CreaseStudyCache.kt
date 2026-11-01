package com.snipsnap.app

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Off-audio-thread decoder. One ShortArray; no expanded byte/float mirror. */
internal object CreaseStudyCache {
    const val MAX_PCM = 32 * 1024 * 1024
    const val MAX_DELIVERY = 12 * 1024 * 1024
    data class Manifest(val native: IntArray, val frames: Int, val file: String,
        val bytes: Int, val pcmHash: String, val packedHash: String, val order: Int,
        val cases: Int)
    data class Decoded(val manifest: Manifest, val pcm: ShortArray)

    fun manifest(text: String): Manifest {
        require(text.length <= 1024 * 1024) { "Study manifest is too large" }
        val m = Json.parse(text).obj()
        fun Map<String, JsonValue>.value(key: String) = getValue(key)
        fun int(key: String) = m.value(key).int()
        require(m.value("format").str() == "crease-playable-pcm-v1" && int("phoneStudy") == 1 &&
            int("rate") == 44100 && int("midi") == 60 && int("model") == 3 &&
            m.value("releaseSeconds").num() == 1.2 && int("maxVoices") == 8) { "Unsupported C4 study" }
        require(m.value("approximationPassed").bool()) { "Study has not passed its accuracy gate" }
        val accuracy = m.value("accuracy").obj()
        val cases = accuracy.value("total").int()
        require(cases >= 721 && accuracy.value("passed").int() == cases &&
            accuracy.value("failed").int() == 0 && accuracy.value("peakErrorLimit").num() == .025 &&
            accuracy.value("maxPeakError").num() in 0.0.. .025) { "Incomplete accuracy receipt" }
        val held = int("heldFrames"); val start = int("loopStart"); val loop = int("loopFrames")
        val root = int("rootCycles"); val lead = int("leadFrames"); val tail = int("tailFrames")
        val blend = int("crossfadeFrames"); val release = int("releaseFrames")
        val cuts = m.value("tailCuts").arr().map { it.int() }
        require(held in 2..44100*64 && start >= 0 && loop >= 2 &&
            start.toLong() + loop == held.toLong() && root in 8..loop &&
            lead in 4..512 && tail in 44100..60000 && blend == 221 && release in 52900..53000 &&
            cuts.size in 1..400 && cuts.first() == 0 && cuts.contains(start) &&
            cuts.all { it in 0 until held } && cuts.zipWithNext().all { (a,b) -> a < b }) { "Invalid study dimensions" }
        val period = loop.toDouble() / root
        require(period in 168.0..169.0 && lead >= kotlin.math.ceil(period/2)+3 &&
            tail >= lead+release+kotlin.math.ceil(period/2)+3) { "Insufficient interpolation padding" }
        val frames = held.toLong() + cuts.size.toLong()*tail
        require(frames*2 <= MAX_PCM) { "Study exceeds 32 MiB PCM" }
        val c = m.value("container").obj()
        val file = c.value("file").str(); val packed = c.value("compressedBytes").int()
        val order = c.value("deltaOrder").int()
        val pcmHash = c.value("pcmSha256").str(); val packedHash = c.value("compressedSha256").str()
        require(c.value("pcmBytes").long() == frames*2 && c.value("compression").str() == "gzip" &&
            c.value("bytePlanes").int() == 2 && order in 0..2 && packed in 1..MAX_DELIVERY &&
            Regex("[A-Za-z0-9_.-]+\\.cpcb").matches(file) && !file.contains("..") &&
            Regex("[a-f0-9]{64}").matches(pcmHash) && Regex("[a-f0-9]{64}").matches(packedHash)) { "Invalid study integrity data" }
        return Manifest(intArrayOf(held,start,loop,root,lead,tail,blend,release)+cuts.toIntArray(),
            frames.toInt(),file,packed,pcmHash,packedHash,order,cases)
    }

    fun decode(m: Manifest, input: InputStream): Decoded {
        val packed = input.use { readLimited(it,m.bytes) }
        require(packed.size == m.bytes && sha(packed) == m.packedHash) { "Study download is incomplete or corrupt" }
        val pcm = ShortArray(m.frames)
        GZIPInputStream(ByteArrayInputStream(packed)).use { gzip ->
            val buffer = ByteArray(8192)
            for (plane in 0..1) {
                var at = 0
                while (at < pcm.size) {
                    val count = gzip.read(buffer,0,minOf(buffer.size,pcm.size-at))
                    require(count > 0) { "Study PCM is truncated" }
                    repeat(count) { j ->
                        val bits = buffer[j].toInt() and 255
                        pcm[at+j] = (if (plane == 0) bits else (pcm[at+j].toInt() and 255) or (bits shl 8)).toShort()
                    }
                    at += count
                }
            }
            require(gzip.read() == -1) { "Study PCM exceeds declared dimensions" }
        }
        repeat(m.order) {
            var total = 0
            for (i in pcm.indices) { total = (total+pcm[i].toInt()) and 65535; pcm[i] = total.toShort() }
        }
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
        var at = 0
        while (at < pcm.size) {
            val count = minOf(buffer.size/2,pcm.size-at)
            repeat(count) { j -> val v=pcm[at+j].toInt(); buffer[2*j]=v.toByte(); buffer[2*j+1]=(v shr 8).toByte() }
            digest.update(buffer,0,count*2); at += count
        }
        require(hex(digest.digest()) == m.pcmHash) { "Study PCM hash differs" }
        return Decoded(m,pcm)
    }
    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out=ByteArrayOutputStream(minOf(limit,8192));val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer,0,minOf(buffer.size,limit+1-out.size()))
            if(count<0) break
            if(count==0) continue
            out.write(buffer,0,count)
            require(out.size()<=limit) { "Study exceeds its declared byte limit" }
        }
        return out.toByteArray()
    }
    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
