import com.snipsnap.synth.*;
import com.snipsnap.audio.Snip;
import java.util.*;
import java.nio.file.*;

public class ParitySkirl {
  public static void main(String[] args)throws Exception {
    BenchSkirl.requestedModel=args.length>1?Integer.parseInt(args[1]):1;
    for(String row:Files.readAllLines(Path.of(args[0]))) {
      String[] p=row.split("\\t");
      Map<String,Float> macros=new LinkedHashMap<>();
      for(String entry:p[4].split(",")) {
        String[] kv=entry.split("="); macros.put(kv[0],Float.parseFloat(kv[1]));
      }
      Object probe=BenchSkirl.inspect(SkirlVoice.valueOf(p[1]),macros,Integer.parseInt(p[2]),Float.parseFloat(p[3]));
      Snip s=(Snip)probe.getClass().getMethod("getSnip").invoke(probe);
      float[] raw=(float[])probe.getClass().getMethod("getRaw").invoke(probe);
      Object a=probe.getClass().getMethod("getAcoustics").invoke(probe);
      System.out.println(p[0]+"\t"+s.getFrameCount()+"\t"+BenchSkirl.digest(s.getSamples())+"\t"+BenchSkirl.digest(raw)+"\t"+a.toString());
    }
  }
}
