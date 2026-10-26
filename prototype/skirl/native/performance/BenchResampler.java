import com.snipsnap.audio.*;
import java.util.*;
public class BenchResampler {
  public static void main(String[] args)throws Exception {
    float[] a=new float[1_000_003];
    for(int i=0;i<a.length;i++)a[i]=(float)Math.sin(i*.013);
    Snip s=new Snip(a,1,176400);
    for(int i=0;i<10;i++)Resampler.INSTANCE.resample(Resampler.INSTANCE.resample(s,88200),44100);
    double[] t=new double[31];
    Snip out=null;
    for(int i=0;i<t.length;i++) {
      long n=System.nanoTime();
      out=Resampler.INSTANCE.resample(Resampler.INSTANCE.resample(s,88200),44100);
      t[i]=(System.nanoTime()-n)/1e6;
    }
    Arrays.sort(t);
    System.out.println("median="+t[t.length/2]+" millis="+Arrays.toString(t)+" sha="+BenchSkirl.digest(out.getSamples()));
  }
}
