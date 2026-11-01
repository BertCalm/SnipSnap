package com.snipsnap.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class CreaseStudyCacheTest {
    private fun hash(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val samples = ShortArray(2729+2*53106) { ((it*31)%60000-30000).toShort() }
    private fun fixture(order: Int, extra: Boolean=false): Pair<String,ByteArray> {
        val raw=ByteArray(samples.size*2)
        samples.forEachIndexed { i,v -> raw[2*i]=v.toByte();raw[2*i+1]=(v.toInt() shr 8).toByte() }
        var predicted=samples.copyOf()
        repeat(order) {
            var previous: Short=0
            for(i in predicted.indices) { val current=predicted[i];predicted[i]=(current-previous).toShort();previous=current }
        }
        val planes=ByteArray(raw.size+if(extra) 1 else 0)
        predicted.forEachIndexed { i,v -> planes[i]=v.toByte();planes[samples.size+i]=(v.toInt() shr 8).toByte() }
        val out=ByteArrayOutputStream();GZIPOutputStream(out).use { it.write(planes) };val packed=out.toByteArray()
        return """{"format":"crease-playable-pcm-v1","phoneStudy":1,"midi":60,"model":3,"rate":44100,
            "heldFrames":2729,"loopStart":32,"loopFrames":2697,"rootCycles":16,"leadFrames":89,"tailFrames":53106,
            "releaseFrames":52925,"crossfadeFrames":221,"maxVoices":8,"releaseSeconds":1.2,"tailCuts":[0,32],
            "approximationPassed":true,"accuracy":{"total":1628,"passed":1628,"failed":0,"maxPeakError":0.02,"peakErrorLimit":0.025},
            "container":{"file":"reed-mark-c4.cpcb","compression":"gzip","bytePlanes":2,"deltaOrder":$order,
            "compressedBytes":${packed.size},"pcmBytes":${raw.size},"pcmSha256":"${hash(raw)}","compressedSha256":"${hash(packed)}"}}""" to packed
    }
    @Test fun `all predictor orders reconstruct exact signed PCM including wraps`() {
        for(order in 0..2) {
            val (json,packed)=fixture(order);val m=CreaseStudyCache.manifest(json)
            assertArrayEquals(samples,CreaseStudyCache.decode(m,ByteArrayInputStream(packed)).pcm)
            assertArrayEquals(intArrayOf(2729,32,2697,16,89,53106,221,52925,0,32),m.native)
        }
    }
    private fun refuses(message: String, action: ()->Unit) {
        try { action();fail("accepted invalid cache: $message") }
        catch(e: IllegalArgumentException) { assertTrue("wrong refusal: ${e.message}",e.message.orEmpty().contains(message)) }
    }
    @Test fun `failed experiments cannot enter the native study`() {
        val (json,_)=fixture(2)
        refuses("accuracy gate") { CreaseStudyCache.manifest(json.replace("\"approximationPassed\":true","\"approximationPassed\":false")) }
        refuses("accuracy receipt") { CreaseStudyCache.manifest(json.replace("\"failed\":0","\"failed\":1")) }
    }
    @Test fun `dimensions overflow traversal and missing padding refuse before allocation`() {
        val (json,_)=fixture(2)
        refuses("dimensions") { CreaseStudyCache.manifest(json.replace("\"heldFrames\":2729","\"heldFrames\":2147483647")) }
        refuses("dimensions") { CreaseStudyCache.manifest(json.replace("[0,32]","[0,32,31]")) }
        refuses("padding") { CreaseStudyCache.manifest(json.replace("\"leadFrames\":89","\"leadFrames\":4")) }
        refuses("integrity") { CreaseStudyCache.manifest(json.replace("reed-mark-c4.cpcb","../evil.cpcb")) }
    }
    @Test fun `corruption truncation and expansion beyond dimensions refuse`() {
        val (json,packed)=fixture(2);val m=CreaseStudyCache.manifest(json)
        val bad=packed.copyOf();bad[bad.size/2]=(bad[bad.size/2].toInt() xor 1).toByte()
        refuses("corrupt") { CreaseStudyCache.decode(m,ByteArrayInputStream(bad)) }
        refuses("corrupt") { CreaseStudyCache.decode(m,ByteArrayInputStream(packed.copyOf(packed.size-1))) }
        refuses("byte limit") { CreaseStudyCache.decode(m,ByteArrayInputStream(packed+byteArrayOf(0))) }
        val (longJson,longPacked)=fixture(2,true)
        refuses("dimensions") { CreaseStudyCache.decode(CreaseStudyCache.manifest(longJson),ByteArrayInputStream(longPacked)) }
        val wrongHash=json.replace(m.pcmHash,"0".repeat(64))
        refuses("PCM hash") { CreaseStudyCache.decode(CreaseStudyCache.manifest(wrongHash),ByteArrayInputStream(packed)) }
    }
    @Test fun `bounded reader accepts exact size and rejects one extra byte`() {
        assertArrayEquals(byteArrayOf(1,2),CreaseStudyCache.readLimited(ByteArrayInputStream(byteArrayOf(1,2)),2))
        refuses("byte limit") { CreaseStudyCache.readLimited(ByteArrayInputStream(byteArrayOf(1,2,3)),2) }
    }
}
