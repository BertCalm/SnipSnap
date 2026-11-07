import com.snipsnap.audio.*;
import com.snipsnap.synth.*;
import kotlin.Pair;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Audition harness against the unchanged renderer at 341ac7fd. Not a saved engine model. */
public class FerryNaturalTailStudy {
  static final int RATE=44100, INTERNAL=176400;
  static double energy(FerryNetwork.Compiled plan,double[] initial,int frames) {
    Pair<double[],double[]> s=plan.state(initial,frames);
    double e=0;for(int i=0;i<initial.length;i++)e+=s.getFirst()[i]*s.getFirst()[i]+s.getSecond()[i]*s.getSecond()[i];
    return e;
  }
  static double rms(float[] x,int from,int to) {
    double e=0;for(int i=from;i<to;i++)e+=(double)x[i]*x[i];return Math.sqrt(e/(to-from));
  }
  static void wav(Path p,float[] x) throws Exception {
    Files.createDirectories(p.getParent());
    try(OutputStream stream=Files.newOutputStream(p)) {
      WavWriter.INSTANCE.write(stream,new Snip(x,1,RATE),WavWriter.BitDepth.PCM_16,null,null);
    }
  }
  static void floats(Path p,float[] x) throws Exception {
    ByteBuffer b=ByteBuffer.allocate(x.length*4).order(ByteOrder.LITTLE_ENDIAN);
    for(float v:x)b.putFloat(v);Files.write(p,b.array());
  }
  public static void main(String[] args) throws Exception {
    Path out=Path.of(args[0]);Files.createDirectories(out);
    String[] names={"low","middle","high"};int[] notes={36,48,72};
    System.out.println("{\"source_commit\":\"341ac7fdbe82cba7e256b08d2e93d0958ef8710b\",\"entries\":[");
    for(int c=0;c<notes.length;c++) {
      int midi=notes[c];String reg=names[c];
      FerryPatch patch=new FerryPatch("Passage dry ending",FerryVoice.PASSAGE,
        Map.of("HANDOFF",.5f,"MATCH",1f,"LOSS",1f,"HOLD",0f),midi,1f,7L,4);
      float[] old=patch.render().getSamples();
      FerryNetwork.Compiled plan=Ferry.INSTANCE.plan$synth(midi,.5f,1f,1f);
      double[] initial=Ferry.INSTANCE.initial$synth(1f,7L);
      double w2=0;for(double w:plan.getModel().getPickup())w2+=w*w;
      // The unforced state is contractive. Cauchy-Schwarz bounds all future
      // unconditioned pickup samples by ||pickup|| sqrt(state energy).
      // Quarter-second search, hard 32-second cap; never disguise a cap as quiet.
      int lo=0,hi=128;
      double rawThreshold=2e-5;
      if(Math.sqrt(w2*energy(plan,initial,hi*INTERNAL/4))>rawThreshold)
        throw new IllegalStateException("Natural-tail quiet criterion not reached before cap");
      while(hi-lo>1) {
        int mid=(lo+hi)/2;
        if(Math.sqrt(w2*energy(plan,initial,mid*INTERNAL/4))<=rawThreshold)hi=mid;else lo=mid;
      }
      int frames=hi*RATE/4;
      long start=System.nanoTime();
      float[] raw=plan.render(initial,frames*4);
      float[] prefix=plan.render(initial,RATE*4);
      if(!Arrays.equals(prefix,Arrays.copyOf(raw,prefix.length)))throw new AssertionError("Physical start changed");
      float[] natural=Ferry.INSTANCE.finish$synth(raw,0f);
      double gain=rms(old,0,RATE/4)/rms(natural,0,RATE/4);
      for(int i=0;i<natural.length;i++)natural[i]*=(float)gain;
      double bound=Math.sqrt(w2*energy(plan,initial,frames*4))*gain;
      double tail=rms(natural,natural.length-RATE/2,natural.length),peak=0;
      for(float x:natural){if(!Float.isFinite(x))throw new AssertionError("Nonfinite audio");peak=Math.max(peak,Math.abs(x));}
      if(peak>1||tail>1e-5||bound>1e-4)throw new AssertionError("Quiet/headroom evidence failed: "+tail+" "+bound);
      double renderMs=(System.nanoTime()-start)/1e6;
      // Exact digital silence makes playback EOF occur after the measured quiet tail.
      float[] a=Arrays.copyOf(old,old.length+RATE),b=Arrays.copyOf(natural,natural.length+RATE);
      wav(out.resolve("natural-audio/"+reg+"-previous.wav"),a);
      wav(out.resolve("natural-audio/"+reg+"-full.wav"),b);
      floats(out.resolve(reg+"-full.f32"),natural);
      Path recipe=out.resolve("natural-recipes/"+reg+"-base.json");Files.createDirectories(recipe.getParent());
      Files.writeString(recipe,patch.toJsonText());
      System.out.printf(Locale.ROOT,"%s{\"register\":\"%s\",\"midi\":%d,\"audio_frames\":%d,\"silence_frames\":%d,\"gain\":%.15g,\"raw_state_energy_at_end\":%.15g,\"future_raw_pickup_bound_after_gain\":%.15g,\"final_500ms_rms\":%.15g,\"peak\":%.15g,\"render_ms_including_prefix_check\":%.6f,\"base_recipe\":\"natural-recipes/%s-base.json\"}%n",c>0?",":"",reg,midi,frames,RATE,gain,energy(plan,initial,frames*4),bound,tail,peak,renderMs,reg);
    }
    System.out.println("]}");
  }
}
