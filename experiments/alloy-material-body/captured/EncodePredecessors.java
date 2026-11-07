import com.snipsnap.audio.*;
import com.snipsnap.synth.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Encode measured predecessor audio with the exact listening-file scalar. No synthesis or tail substitution. */
public class EncodePredecessors {
  static float[] read(Path p)throws Exception {
    ByteBuffer b=ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
    float[] a=new float[b.remaining()/4];for(int i=0;i<a.length;i++)a[i]=b.getFloat();return a;
  }
  public static void main(String[] args)throws Exception {
    Path folder=Path.of(args[0]);int count=0;
    for(int part=0;part<2;part++)for(String line:Files.readAllLines(folder.resolve("jobs"+part+".tsv"))) {
      if(line.isBlank())continue;String[] f=line.split("\t");
      if(!Boolean.parseBoolean(f[4]))continue;
      AlloyPatch p=AlloyPatch.Companion.fromJsonText(Files.readString(Path.of(f[1])));
      if(p.getMacros().getOrDefault("HOLD",0f)<.99f)continue;
      String id=f[0];Path out=folder.resolve("measurements");
      Snip raw=new Snip(read(out.resolve(id+"_samples.f32")),1,44100);
      Path proof=out.resolve(id+"_gaincheck.wav");
      WavWriter.INSTANCE.write(proof.toFile(),MaterialProbe.matched(raw),WavWriter.BitDepth.PCM_16,false,null,null);
      if(!Arrays.equals(Files.readAllBytes(proof),Files.readAllBytes(out.resolve(id+".wav"))))throw new AssertionError("Listening bytes changed "+id);
      Files.delete(proof);
      float[] previous=read(out.resolve(id+"_previous.f32"));float gain=MaterialProbe.matchGain(raw);
      for(int i=0;i<previous.length;i++)previous[i]*=gain;
      WavWriter.INSTANCE.write(out.resolve(id+"_previous.wav").toFile(),new Snip(previous,1,44100),WavWriter.BitDepth.PCM_16,false,null,null);
      count++;
    }
    Files.writeString(folder.resolve("predecessor-encoding.json"),"{\"actualExit\":0,\"heldPredecessorsEncoded\":"+count+",\"allListeningFilesByteIdentical\":true,\"gain\":\"Same Float scalar computed from the actual raw exported cycle, then applied to its actual measured predecessor\"}\n");
    System.out.println("Encoded "+count+" actual predecessors; listening WAVs byte-identical");
  }
}
