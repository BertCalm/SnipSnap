import com.snipsnap.synth.*;import java.util.*;
public class StageKeyOracle {
 public static void main(String[]args){
  var h=Halobar.INSTANCE;var which=args[0];var voice=which.equals("motor")?HalobarVoice.VIBRAPHONE:HalobarVoice.TUBULAR_BELLS;
  var base=new HashMap<String,Float>(Map.of("HOLD",.98f,"SPACE",.3f,"RETURN",.1f,"TOUCH",.1f,"MOTION",.1f));
  var changed=new HashMap<>(base);changed.put(which.equals("source")?"TOUCH":which.equals("return")?"RETURN":"MOTION",.8f);
  h.clearCache$synth();h.render(voice,base,1f,1);
  var expected=h.renderProbe$synth(voice,changed,1f,true,4).getSamples();var actual=h.render(voice,changed,1f,1).getSamples();
  if(!Arrays.equals(expected,actual))throw new AssertionError(which+" stage key: stale audio mismatch");
  System.out.println(which+" stage key exact");
 }
}
