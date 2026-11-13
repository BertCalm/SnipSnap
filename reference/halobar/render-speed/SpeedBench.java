import com.snipsnap.synth.*;
import java.util.*;
import java.lang.management.ManagementFactory;
public class SpeedBench {
 static volatile float sink;
 public static void main(String[] args) {
  var cpu=ManagementFactory.getThreadMXBean();
  for(var v:HalobarVoice.values()) {
   for(int i=0;i<8;i++) fresh(v);
   for(int i=0;i<15;i++) {
    long c=cpu.getCurrentThreadCpuTime(), t=System.nanoTime();fresh(v);
    double wall=(System.nanoTime()-t)/1e6, used=(cpu.getCurrentThreadCpuTime()-c)/1e6;
    System.out.println(v+"\t"+i+"\t"+wall+"\t"+used);
   }
  }
 }
 static void fresh(HalobarVoice v){ Halobar.INSTANCE.clearCache$synth(); var s=Halobar.INSTANCE.render(v,Map.of(),1f,1).getSamples();sink=s[s.length/2]; }
}
