import com.snipsnap.audio.Snip;
import com.snipsnap.audio.WavReader;
import com.snipsnap.audio.WavWriter;
import com.snipsnap.audio.WavWriter.BitDepth;
import com.snipsnap.kit.InstrumentStore;
import com.snipsnap.shell.InstrumentEngine;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** A small score through the production sample player, reading actual exported PCM. */
public final class NativeSamplerPreview {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        File program = new File(root, "Trefoil Held");
        InstrumentStore.Instrument instrument = InstrumentStore.INSTANCE.read(
            new File(program, "Trefoil Held.instrument.json"));
        Map<String,float[]> samples = new HashMap<>();
        InstrumentStore.Zone middle = null;
        for (InstrumentStore.Zone zone : instrument.getZones()) {
            samples.put(zone.getSample(), WavReader.INSTANCE.read(new File(program, zone.getSample())).getSamples());
            if (zone.getRootNote() == 60) middle = zone;
        }
        if (middle == null) throw new IllegalStateException("Missing C4 root");
        InstrumentEngine engine = new InstrumentEngine(new InstrumentEngine.Loaded(instrument,44100,samples),44100,16);
        int off = (int)(middle.getFrameCount() * 2 - middle.getLoopStartFrame());
        int total = off + Math.round(44100 * (instrument.getRelease() + .05f));
        int keep = 6 * 44100, cut = total - keep;
        if (!engine.noteOn(60,1f)) throw new IllegalStateException("Missing reference zone");
        float[] output = new float[keep];
        int frame = 0;
        while (frame < total) {
            if (frame == off) engine.noteOff(60);
            int count = Math.min(512,Math.min(total-frame,frame<off?off-frame:total-frame));
            float[] stereo = new float[count*2];engine.render(stereo,count);
            for (int i=0;i<count;i++) if (frame+i>=cut) {
                int at=frame+i-cut;float value=(stereo[i*2]+stereo[i*2+1])*.5f*.65f;
                // A presentation onset only; the real repeat is several seconds later.
                if (at<882) { double t=at/881.0;value*=t*t*(3-2*t); }
                if (!Float.isFinite(value) || Math.abs(value)>=.99f) throw new IllegalStateException("Preview headroom");
                output[at]=value;
            }
            frame+=count;
        }
        WavWriter.INSTANCE.write(new File(root,"sampler-loop-proof.wav"),new Snip(output,1,44100),BitDepth.PCM_24,false,null,null);
        System.out.println("{\"repeatSeconds\":"+((middle.getFrameCount()-cut)/44100.0)+
            ",\"noteOffSeconds\":"+((off-cut)/44100.0)+",\"monitorGain\":0.65,\"cropFrames\":"+cut+"}");
    }
}
