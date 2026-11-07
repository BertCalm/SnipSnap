import com.snipsnap.audio.*;
import com.snipsnap.synth.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Diagnostics over the unchanged compiled Kotlin engine. Inputs are complete plain Alloy patches. */
public class MaterialProbe {
  static void floats(Path p, float[] a)throws Exception {
    ByteBuffer b=ByteBuffer.allocate(a.length*4).order(ByteOrder.LITTLE_ENDIAN);
    for(float x:a)b.putFloat(x); Files.write(p,b.array());
  }
  static double rms(float[] a) {double s=0;for(float x:a)s+=(double)x*x;return Math.sqrt(s/Math.max(1,a.length));}
  static double mean(float[] a) {double s=0;for(float x:a)s+=x;return s/Math.max(1,a.length);}
  static float matchGain(Snip raw) {
    float gain=.03f/Math.max(Loudness.INSTANCE.of(raw),1e-9f), peak=raw.peak();
    if(peak*gain>.99f)gain=.99f/peak;
    return gain;
  }
  static Snip matched(Snip raw) {
    float[] a=raw.getSamples().clone();
    float gain=matchGain(raw);
    for(int i=0;i<a.length;i++)a[i]*=gain;
    return new Snip(a,1,44100);
  }
  static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  public static void main(String[] args)throws Exception {
    Path out=Path.of(args[1]);Files.createDirectories(out);
    int count=0;
    for(String line:Files.readAllLines(Path.of(args[0]))) {
      if(line.isBlank())continue;
      String[] f=line.split("\t");String id=f[0];
      AlloyPatch p=AlloyPatch.Companion.fromJsonText(Files.readString(Path.of(f[1])));
      int os=Integer.parseInt(f[2]);Float seconds=f[3].equals("full")?null:Float.valueOf(f[3]);
      boolean writeNative=Boolean.parseBoolean(f[4]);
      boolean held=p.getMacros().getOrDefault("HOLD",0f)>=.99f;
      long started=System.nanoTime();
      Alloy.Probe probe=Alloy.INSTANCE.renderProbe$synth(p.getVoice(),p.getMacros(),p.getMidi(),p.getVelocity(),seconds,false,true,p.getConfig(),os,true);
      double probeMs=(System.nanoTime()-started)/1e6;
      float[] rawSamples=probe.getSamples().clone();
      if(!held)Dsp.INSTANCE.fadeTail(rawSamples,4f,44100,1);
      Snip raw=new Snip(rawSamples,1,44100);
      Snip audible=matched(raw);
      floats(out.resolve(id+"_samples.f32"),probe.getSamples());
      floats(out.resolve(id+"_body.f32"),probe.getBody());
      floats(out.resolve(id+"_direct.f32"),probe.getDirectAttack());
      floats(out.resolve(id+"_previous.f32"),probe.getPreviousCycle());
      double publicMs=0;String nativeFile="";
      if(writeNative) {
        WavWriter.INSTANCE.write(out.resolve(id+".wav").toFile(),audible,WavWriter.BitDepth.PCM_16,false,null,null);
        started=System.nanoTime();Snip nativeSnip=p.render();publicMs=(System.nanoTime()-started)/1e6;
        if(nativeSnip.peak()>=1f)throw new AssertionError("Native export would clip "+id);
        nativeFile=id+"_native.wav";
        WavWriter.INSTANCE.write(out.resolve(nativeFile).toFile(),nativeSnip,WavWriter.BitDepth.PCM_24,false,null,null);
        if(held) {
          float gain=matchGain(raw);
          float[] previous=probe.getPreviousCycle().clone();
          for(int i=0;i<previous.length;i++)previous[i]*=gain;
          WavWriter.INSTANCE.write(out.resolve(id+"_previous.wav").toFile(),new Snip(previous,1,44100),WavWriter.BitDepth.PCM_16,false,null,null);
        }
      }
      Features feat=FeatureExtractor.INSTANCE.extract(raw);
      Features noDuration=new Features(feat.getCentroidHz(),feat.getRolloffHz(),feat.getFlatness(),feat.getZeroCrossingRate(),feat.getLowRatio(),feat.getMidRatio(),feat.getHighRatio(),1.1f,feat.getDecayMs(),feat.getPeak(),feat.getAttackBursts());
      PitchEstimate pitch=Pitch.INSTANCE.detect(raw,.05f,.35f);
      String detected=Classifier.INSTANCE.classify(noDuration).getDrumClass().name();
      String row=String.format(Locale.ROOT,
        "{\"id\":%s,\"frames\":%d,\"rawPeak\":%.17g,\"rms\":%.17g,\"mean\":%.17g,\"rawStatePeak\":%.17g,\"maxPhaseModulation\":%.17g,\"maxConditionedAttack\":%.17g,\"seamError\":%.17g,\"stateError\":%.17g,\"prerollCycles\":%d,\"pitchHz\":%s,\"pitchConfidence\":%s,\"genericClassWithoutDurationShortcut\":%s,\"declaredClass\":%s,\"probeMs\":%.9f,\"publicRenderMs\":%.9f,\"nativeFile\":%s}",
        q(id),rawSamples.length,(double)raw.peak(),rms(rawSamples),mean(rawSamples),probe.getDiagnostics().getRawPeak(),probe.getDiagnostics().getMaxPhaseModulation(),probe.getDiagnostics().getMaxConditionedAttack(),probe.getSeamError(),probe.getStateError(),probe.getPrerollCycles(),pitch==null?"null":Float.toString(pitch.getHz()),pitch==null?"null":Float.toString(pitch.getConfidence()),q(detected),q(Alloy.INSTANCE.drumClassFor(p.getVoice(),p.getMacros()).name()),probeMs,publicMs,q(nativeFile));
      Files.writeString(out.resolve(id+".json"),row+"\n");
      if(++count%10==0)System.out.println("Rendered "+count+" "+id+" probeMs="+Math.round(probeMs));
    }
    System.out.println("Completed "+count+" jobs");
  }
}
