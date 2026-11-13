import com.snipsnap.synth.*;
import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;

/** Replay the pre-optimization Float32 take fixtures on the same JVM runtime. */
public class RenderSpeedReplay {
 public static void main(String[] args)throws Exception {
  int count=0;var seen=new HashSet<String>();
  for(String line:Files.readAllLines(Path.of(args[0]))) {
   if(line.isBlank()||line.startsWith("voice\t"))continue;
   String[] fields=line.split("\t");
   var voice=HalobarVoice.valueOf(fields[0]);int k=Integer.parseInt(fields[1]);
   if(k<0||k>=12||!seen.add(voice+":"+k))throw new AssertionError("Invalid or duplicate take case");
   var macros=Map.of("TUNE",new float[]{0,.5f,1}[k%3],"SPACE",k%4==0?0f:.35f,
       "HOLD",k%4==1?.98f:.3f,"RETURN",k%4==2?.8f:.45f);
   var snip=Halobar.INSTANCE.renderTake(voice,macros,k%2==0?.42f:1f,31415+k,k%3,1);
   float[] samples=snip.getSamples();
   var bytes=ByteBuffer.allocate(samples.length*4).order(ByteOrder.LITTLE_ENDIAN);
   bytes.asFloatBuffer().put(samples);
   String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
   if(snip.getChannels()!=1||snip.getSampleRate()!=44100||samples.length!=Integer.parseInt(fields[2])||!hash.equals(fields[3]))
    throw new AssertionError(voice+" case "+k+" differs from pre-optimization audio");
   count++;
  }
  if(count!=72)throw new AssertionError("Expected 72 take cases, got "+count);
  System.out.println("All 72 take cases retain their pre-optimization Float32 hashes.");
 }
}
