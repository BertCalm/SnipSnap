import com.snipsnap.synth.*;
import java.util.*;
import java.security.*;
import java.nio.*;
public class StageBench {
 static volatile float sink;
 static Map<String,Float> values(String caseName){return switch(caseName){case "LONG"->Map.of("HOLD",1f,"RETURN",1f);case "DRY"->Map.of("SPACE",0f);default->Map.of();};}
 static String render(HalobarVoice voice,String caseName)throws Exception{
  Halobar.INSTANCE.clearCache$synth();Arrays.fill(Halobar.INSTANCE.getProfileNanos(),0L);
  long start=System.nanoTime();var samples=Halobar.INSTANCE.render(voice,values(caseName),1f,1).getSamples();long total=System.nanoTime()-start;
  sink=samples[samples.length/2];
  StringBuilder row=new StringBuilder().append(voice).append('\t').append(caseName).append('\t').append(samples.length).append('\t').append(total/1e6);
  for(long t:Halobar.INSTANCE.getProfileNanos())row.append('\t').append(t/1e6);
  return row.toString();
 }
 public static void main(String[]args)throws Exception{
  System.out.println("voice\tcase\tframes\ttotalMs\tbarsMs\techoesMs\tdownMs\tphaseMs\thaloMs\tfinishMs");
  for(var voice:HalobarVoice.values())for(String c:List.of("DEFAULT","LONG","DRY")){
   for(int i=0;i<6;i++)render(voice,c);
   for(int i=0;i<12;i++)System.out.println(render(voice,c));
  }
 }
}
