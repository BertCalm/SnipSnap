import com.snipsnap.synth.Skirl;
import com.snipsnap.synth.SkirlVoice;
import com.snipsnap.audio.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class HoldCaptureTransition {
  static String hash(float[] samples) throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    ByteBuffer bytes=ByteBuffer.allocate(samples.length*4).order(ByteOrder.LITTLE_ENDIAN);
    for(float sample:samples)bytes.putFloat(sample);
    return HexFormat.of().formatHex(digest.digest(bytes.array()));
  }
  public static void main(String[] args) throws Exception {
    Path output=Path.of(args[0]); Files.createDirectories(output);
    Method inspect=Arrays.stream(Skirl.class.getDeclaredMethods())
      .filter(m->m.getName().startsWith("inspect$") && m.getParameterCount()==5).findFirst().orElseThrow();
    for(SkirlVoice voice:new SkirlVoice[]{SkirlVoice.ROLL}) for(int midi:new int[]{36,40,44,47,48}) for(float velocity:new float[]{.25f,1f}){
      String identifier=voice.name()+"_"+midi+"_"+velocity;
      Object probe=inspect.invoke(Skirl.INSTANCE,voice,Map.of("HOLD",1f),midi,velocity,2);
      Snip snip=(Snip)probe.getClass().getMethod("getSnip").invoke(probe);
      Object diagnostics=probe.getClass().getMethod("getHold").invoke(probe);
      System.out.println(identifier+" DIAGNOSTICS "+diagnostics);
      float[] loop=snip.getSamples();
      float[] repeated=new float[loop.length*8];
      for(int i=0;i<8;i++)System.arraycopy(loop,0,repeated,i*loop.length,loop.length);
      Snip continuous=new Snip(repeated,1,44100);
      double target=440*Math.pow(2,(midi-69)/12.0);
      double dc=0;for(float x:loop)dc+=x;dc/=loop.length;
      System.out.printf(Locale.ROOT,"%s HEALTH frames=%d dc=%.12g peak=%.12g loudness=%.12g hash=%s%n",
        identifier,loop.length,dc,snip.peak(),Loudness.INSTANCE.of(snip),hash(loop));
      for(int i=0;i<12;i++){
        float from=(float)(i*loop.length/(12.0*44100));
        PitchEstimate estimate=Pitch.INSTANCE.detect(continuous,from,.30f);
        if(estimate==null)System.out.printf(Locale.ROOT,"%s PHASE %.2f null%n",identifier,from);
        else System.out.printf(Locale.ROOT,"%s PHASE %.2f hz=%.9f cents=%.9f confidence=%.9f%n",identifier,from,
          estimate.getHz(),1200*Math.log(estimate.getHz()/target)/Math.log(2),estimate.getConfidence());
      }
      PitchEstimate whole=Pitch.INSTANCE.detect(continuous,0f,loop.length/44100f);
      System.out.println(identifier+" WHOLE "+whole);
      WavWriter.INSTANCE.write(output.resolve(identifier.toLowerCase(Locale.ROOT)+"-loop.wav").toFile(),
        snip,WavWriter.BitDepth.PCM_16,false,null,null);
      Snip finite=Skirl.INSTANCE.render(voice,Map.of("HOLD",0f),midi,velocity,2);
      System.out.println(identifier+" FINITE "+hash(finite.getSamples()));
    }
  }
}
