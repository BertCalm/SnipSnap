import com.snipsnap.synth.*;
import java.util.*;
import java.lang.management.ManagementFactory;
public class SpaceBench {
 static volatile float sink;
 public static void main(String[] args){var cpu=ManagementFactory.getThreadMXBean();for(var v:HalobarVoice.values()){
  Halobar.INSTANCE.clearCache$synth();for(int i=0;i<8;i++)render(v,.2f+i*.001f);
  for(int i=0;i<15;i++){long c=cpu.getCurrentThreadCpuTime(),t=System.nanoTime();render(v,.4f+i*.003f);double wall=(System.nanoTime()-t)/1e6,used=(cpu.getCurrentThreadCpuTime()-c)/1e6;System.out.println(v+"\t"+i+"\t"+wall+"\t"+used);}
 }}
 static void render(HalobarVoice v,float space){var s=Halobar.INSTANCE.render(v,Map.of("SPACE",space),1f,1).getSamples();sink=s[s.length/2];}
}
