import com.snipsnap.synth.Halobar;
import java.util.SplittableRandom;
public class FeedbackGuardProof {
 public static void main(String[] args){var random=new SplittableRandom(453);String mode=args[0];int cases=0;
  for(int i=0;i<25000;i++){
   double x=random.nextDouble(.001,.0625)*(i%2==0?1:-1),gain=.444,drive=2.125;
   float low=(float)random.nextDouble(.005,.015),high=Float.intBitsToFloat(Float.floatToRawIntBits(low)+1);
   double target=(low+(double)high)*.5*(i%2==0?1:-1);
   double fed=mode.equals("rounding")?target-gain*Math.tanh(x)/drive:random.nextDouble(-.02,.02);
   double expected=fed+gain*Math.tanh(x)/drive,peak=mode.equals("rounding")?1:0;
   double actual=Halobar.INSTANCE.feedbackSampleProbe$synth(fed,gain,drive,x,peak);
   if(Float.floatToRawIntBits((float)expected)!=Float.floatToRawIntBits((float)actual))throw new AssertionError("rounding guard: Float mismatch at "+i);
   if(Double.doubleToRawLongBits(Math.max(peak,Math.abs(expected)))!=Double.doubleToRawLongBits(Math.max(peak,Math.abs(actual))))throw new AssertionError("peak guard: Double mismatch at "+i);
   cases++;
  }
  System.out.println(mode+" "+cases+" passed");
 }
}
