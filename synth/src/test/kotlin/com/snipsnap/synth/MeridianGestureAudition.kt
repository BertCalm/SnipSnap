package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln

/** The eight browser examples and focused gates share these same bounded native renders. */
internal object MeridianGestureReadings {
    val voices = listOf(MeridianVoice.DRAW, MeridianVoice.LIFT)
    data class Reading(val patch: MeridianPatch, val snip: Snip, val held: Meridian.HeldResult?,
        val probe: Meridian.Probe?, val elapsedNanos: Long) {
        val id get() = patch.voice.name.lowercase() + "-" + patch.name.lowercase().replace(' ', '-') + if (held == null) "-finite" else "-held"
        val from get() = held?.let { it.loopStartFrame.toFloat() / Dsp.RATE + .04f } ?: .30f
        val expectedHz get() = Meridian.frequencyFor(patch.voice, patch.macros.getValue("TUNE"), patch.midi)
        val cents by lazy { FineTuning.cents(FineTuning.measuredHz(snip, expectedHz, from, .5f), expectedHz.toDouble()) }
        val broad by lazy { Pitch.detect(snip, from, .5f) }
    }
    val all: List<Reading> by lazy {
        voices.flatMap { voice -> MeridianPresets.forVoice(voice).flatMap { candidate ->
            listOf(false, true).map { hold ->
                val patch = candidate.copy(macros = candidate.macros + ("HOLD" to if (hold) 1f else 0f))
                MeridianMotion.clearCache()
                val started = System.nanoTime()
                val held = if (hold) Meridian.renderHeld(voice, patch.macros, patch.midi, patch.velocity, patch.seedContext, patch.model) else null
                val probe = if (hold) null else Meridian.renderProbe(voice, patch.macros, patch.midi,
                    patch.velocity, patch.seedContext, normalize = true)
                Reading(patch, held?.snip ?: Snip(probe!!.samples, 1, Dsp.RATE), held, probe, System.nanoTime() - started)
            }
        } }
    }
    fun rawHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return hash(bytes.array())
    }
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun num(value: Number) = JsonValue.Num(value.toDouble())
    fun text(value: String) = JsonValue.Str(value)
    fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*fields))
}

/** Packaging reuses qualified samples, never substitutes a local ZIP for root's published page. */
object MeridianGestureAudition {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()).apply { mkdirs() }
        val audio = File(root, "audio").apply { mkdirs() }
        val recipes = File(root, "recipes").apply { mkdirs() }
        val entries = MeridianGestureReadings.all.map { r ->
            val held = r.held
            val audible = if (held == null) r.snip else {
                // Keep the genuine first cycle, then play the marked sampler
                // cycle three times. Seam evidence still uses the two independently
                // evolved original cycles; this arrangement is only a listening aid.
                val first = r.snip.samples.copyOfRange(0, held.loopStartFrame)
                val loop = r.snip.samples.copyOfRange(held.loopStartFrame, held.loopEndFrameExclusive)
                Snip(first + loop + loop + loop, 1, Dsp.RATE)
            }
            val wav = File(audio, "${r.id}.wav")
            WavWriter.write(wav, audible, WavWriter.BitDepth.PCM_16)
            File(recipes, "${r.id}.json").writeText(r.patch.toJsonText() + "\n")
            MeridianGestureReadings.run { obj(
                "id" to text(r.id), "voice" to text(r.patch.voice.name), "preset" to text(r.patch.name),
                "playback" to text(if (held == null) "FINITE" else "HELD"), "model" to num(r.patch.model),
                "file" to text("audio/${wav.name}"), "recipe" to text("recipes/${r.id}.json"),
                "wavSha256" to text(hash(wav.readBytes())), "sourceRawFloatSha256" to text(rawHash(r.snip.samples)),
                "audioFrames" to num(audible.frameCount), "nativeFrames" to num(r.snip.frameCount),
                "nativeRenderMs" to num(r.elapsedNanos / 1e6), "rootCents" to num(r.cents),
                "pitchConfidence" to num(r.broad?.confidence ?: 0f), "peak" to num(r.snip.peak()),
                "dc" to num(abs(r.snip.samples.average())), "loudness" to num(Loudness.of(r.snip)),
                "loopStartFrame" to (held?.let { num(it.loopStartFrame) } ?: JsonValue.Null),
                "loopEndFrameExclusive" to (held?.let { num(it.loopEndFrameExclusive) } ?: JsonValue.Null),
                "seamError" to (held?.let { num(it.seamError) } ?: JsonValue.Null),
                "stateError" to (held?.let { num(it.stateError) } ?: JsonValue.Null),
                "cycleError" to (held?.let { num(it.cycleError) } ?: JsonValue.Null),
                "arrangement" to text(if (held == null) "Exact finite native render" else "Genuine preceding cycle plus marked sampler cycle three times"),
            ) }
        }
        val manifest = MeridianGestureReadings.run { obj("schema" to num(1),
            "status" to text("PROPOSED: DRAW and LIFT have not been heard or accepted."),
            "timingScope" to text("Single-CPU shared JVM; motion cache cleared per candidate. Not a controlled cold-app or Android benchmark."),
            "base" to text("0f7d0e26cfdf875a995e6c8a5aec8225e3fd1fe9"),
            "engine" to text("MERIDIAN"), "clips" to JsonValue.Arr(entries)) }
        File(root, "manifest.json").writeText(Json.write(manifest) + "\n")
        val cards = MeridianGestureReadings.all.joinToString("\n") { r ->
            val held = r.held != null
            """<article data-id="${r.id}"><h3>${r.patch.name} · ${if (held) "Held" else "Finite"}</h3><audio controls preload="none" src="audio/${r.id}.wav"></audio><p>MIDI ${r.patch.midi} · velocity ${r.patch.velocity} · model 4</p><label>Verdict <select><option value="unreviewed">Unreviewed</option><option value="keep">Keep</option><option value="tweak">Tweak</option><option value="skip">Skip</option></select></label><input aria-label="Note for ${r.id}" placeholder="Short note"></article>"""
        }
        File(root, "index.html").writeText("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MERIDIAN · DRAW and LIFT</title><style>body{font:17px system-ui;background:#131b20;color:#eef0e6;max-width:880px;margin:32px auto;padding:0 20px}h1,h2{color:#e4bd7b}article{background:#223139;border:1px solid #49636a;border-radius:12px;padding:18px;margin:16px 0}audio{width:100%}input,select,button{font:inherit;padding:9px;margin:6px 0;max-width:95%}input{display:block;width:90%}a{color:#e4bd7b}p{line-height:1.5}.status{color:#bbd2cc}</style><h1>MERIDIAN · two new gestures</h1><p class="status">Candidate sounds. The four approved voices stay unchanged. These additions still need your verdict.</p><p><strong>DRAW:</strong> contact draws a continuous pitched tone across the surface ring. <strong>LIFT:</strong> contact builds pressure, then a pitched answer blooms as it withdraws.</p><p>Listen to one finite/held pair at a time. The held clips play a continuing rubbing or pressure-release gesture. Each includes the real preceding cycle and three turns of its marked sustain cycle.</p><section>$cards</section><button id="save">Download feedback</button><p id="saved" role="status"></p><p><a href="manifest.json">Measured recipe and audio identity</a></p><script>document.getElementById('save').onclick=()=>{const clips=[...document.querySelectorAll('article')].map(a=>({id:a.dataset.id,verdict:a.querySelector('select').value,note:a.querySelector('input').value}));const d={schema:1,engine:'MERIDIAN',batch:'draw-lift-model4',clips};const u=URL.createObjectURL(new Blob([JSON.stringify(d,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=u;a.download='snipsnap-meridian-gestures-feedback.json';a.click();setTimeout(()=>URL.revokeObjectURL(u),2000);document.getElementById('saved').textContent='Feedback downloaded.'};</script></html>""")
        File(root, "feedback-template.json").writeText(Json.write(MeridianGestureReadings.run {
            obj("schema" to num(1), "engine" to text("MERIDIAN"), "batch" to text("draw-lift-model4"),
                "clips" to JsonValue.Arr(MeridianGestureReadings.all.map { obj("id" to text(it.id),
                    "verdict" to text("unreviewed"), "note" to text("")) })) }) + "\n")
        println("MERIDIAN candidate browser payload: ${entries.size} qualified native examples at $root; publication and browser/device playback pending.")
    }
}
