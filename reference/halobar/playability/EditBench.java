import com.snipsnap.synth.*;import java.util.*;import java.lang.management.ManagementFactory;
public class EditBench {
 static volatile float sink;
 static Map<String,Float> values(String control,int i){
  if(control.equals("FRESH"))return Map.of();
  float value=control.equals("RATE")?((i%6+.5f)/6+i*.00001f):(.1f+.012f*i);
  return Map.of(control,value);
 }
 static void render(HalobarVoice voice,String control,int i){if(control.equals("FRESH"))Halobar.INSTANCE.clearCache$synth();var s=Halobar.INSTANCE.render(voice,values(control,i),1f,1).getSamples();sink=s[s.length/2];}
 public static void main(String[]args){int warm=Integer.parseInt(args[0]),n=Integer.parseInt(args[1]);var cpu=ManagementFactory.getThreadMXBean();
 for(var voice:HalobarVoice.values())for(String control:List.of("FRESH","RATE","RETURN","MOTION","SPACE")){
  Halobar.INSTANCE.clearCache$synth();for(int i=0;i<warm;i++)render(voice,control,i);
  for(int i=0;i<n;i++){long c=cpu.getCurrentThreadCpuTime(),t=System.nanoTime();render(voice,control,i+warm);double wall=(System.nanoTime()-t)/1e6,used=(cpu.getCurrentThreadCpuTime()-c)/1e6;System.out.println(voice+"\t"+control+"\t"+i+"\t"+wall+"\t"+used);}
 }
 }
}
