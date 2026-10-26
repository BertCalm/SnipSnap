import com.snipsnap.synth.Skirl;
import com.snipsnap.synth.SkirlVoice;
import com.snipsnap.audio.Snip;
import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.security.*;

public class BenchSkirl {
  static Method inspect, render;
  static {
    for(Method m:Skirl.class.getDeclaredMethods()) {
      if(m.getName().startsWith("inspect$") && (m.getParameterCount()==4 || m.getParameterCount()==5))inspect=m;
      if(m.getName().equals("render") && (m.getParameterCount()==4 || m.getParameterCount()==5))render=m;
    }
  }
  // The same driver runs against the archived model-1 jar and model-2 source.
  // Request model 1 explicitly when that field exists, preserving saved replay.
  static Object inspect(SkirlVoice voice, Map<String,Float> macros, int midi, float velocity)throws Exception {
    return inspect.getParameterCount()==4 ? inspect.invoke(Skirl.INSTANCE,voice,macros,midi,velocity) :
      inspect.invoke(Skirl.INSTANCE,voice,macros,midi,velocity,1);
  }
  static Snip render(SkirlVoice voice, Map<String,Float> macros, int midi, float velocity)throws Exception {
    return (Snip)(render.getParameterCount()==4 ? render.invoke(Skirl.INSTANCE,voice,macros,midi,velocity) :
      render.invoke(Skirl.INSTANCE,voice,macros,midi,velocity,1));
  }
  static String digest(float[] a)throws Exception {
    MessageDigest d=MessageDigest.getInstance("SHA-256");
    ByteBuffer b=ByteBuffer.allocate(a.length*4).order(ByteOrder.LITTLE_ENDIAN);
    for(float f:a)b.putFloat(f);
    return HexFormat.of().formatHex(d.digest(b.array()));
  }
  public static void main(String[] args)throws Exception {
    int repeats=args.length>0?Integer.parseInt(args[0]):9;
    System.out.println("CONFIG java="+System.getProperty("java.version")+" os="+System.getProperty("os.name")+
      " processors="+Runtime.getRuntime().availableProcessors()+" requestedModel=1 warmup=5 repeats="+repeats);
    for(SkirlVoice v:SkirlVoice.values()){
      for(int i=0;i<5;i++)render(v,Collections.emptyMap(),48,1f);
      double[] times=new double[repeats];
      for(int i=0;i<repeats;i++){
        long t=System.nanoTime();
        Snip s=render(v,Collections.emptyMap(),48,1f);
        times[i]=(System.nanoTime()-t)/1e6;
      }
      Arrays.sort(times);
      Object p=inspect(v,Collections.emptyMap(),48,1f);
      Class<?> c=p.getClass();
      System.out.println(v+" millis="+Arrays.toString(times)+" median="+times[repeats/2]+
        " preparation="+((Long)c.getMethod("getPreparationNanos").invoke(p))/1e6+
        " synthesis="+((Long)c.getMethod("getSynthesisNanos").invoke(p))/1e6+
        " output="+((Long)c.getMethod("getOutputNanos").invoke(p))/1e6);
      for(int midi:new int[]{36,48,60,72,84})for(float vel:new float[]{.25f,1f}){
        Snip s=render(v,Collections.emptyMap(),midi,vel);
        System.out.println("HASH "+v+" midi="+midi+" velocity="+vel+" frames="+s.getFrameCount()+" sha256="+digest(s.getSamples()));
      }
    }
  }
}
