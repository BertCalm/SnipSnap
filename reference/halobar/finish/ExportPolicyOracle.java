import com.snipsnap.audio.*;
import com.snipsnap.kit.*;
import com.snipsnap.shell.*;
import com.snipsnap.synth.*;
import com.snipsnap.xpm.*;
import com.snipsnap.mpc3.*;
import com.snipsnap.json.*;
import java.nio.file.*;
import java.util.*;
public class ExportPolicyOracle {
 public static void main(String[]args)throws Exception {
  String which=args[0];
  if(which.equals("filter")){
   float[] input=new float[1003];for(int i=0;i<input.length;i++)input[i]=(float)(Math.sin(i*.17)*Math.exp(-i/300.));
   float[] expected=input.clone();for(float k:new float[]{1.9616f,1.6629f,1.1111f,.3902f})new Dsp.TptSvf(176400).lowPassInPlace(expected,19500f,k);
   Tide.INSTANCE.bandLimit$synth(input,176400);
   for(int i=0;i<input.length;i++)if(Float.floatToRawIntBits(input[i])!=Float.floatToRawIntBits(expected[i]))throw new AssertionError("paired filter output order guard");
   System.out.println("filter exact");return;
  }
  var spec=new HalobarPadMaker.Spec(new HalobarPatch("HALO",HalobarVoice.VIBRAPHONE,Map.of(),1),.6f,0);
  var notes=new ArrayList<List<KeyNote>>();
  for(int root:HalobarPadMaker.INSTANCE.zoneMidis(spec)){
   var captures=new ArrayList<KeyNote>();for(int i=0;i<6;i++)captures.add(new KeyNote(new Snip(new float[]{0f,.1f+i*.01f,0f},1,44100),0L));notes.add(captures);
  }
  var program=HalobarPadMaker.INSTANCE.assemble("HALO",spec,notes).getFirst();
  if(which.equals("optin"))for(var g:program.getKeygroups())if(g.getMpcLayers().size()!=6||!g.getMpcCycle())throw new AssertionError("HALOBAR six-strike export guard");
  if(which.equals("persist")){
   Path folder=Files.createTempDirectory("halobar-policy");Path sidecar=folder.resolve("HALO.instrument.json");
   try{Files.writeString(sidecar,InstrumentStore.INSTANCE.render(program,"HALO_[TrackData]"));
    var reloaded=InstrumentStore.INSTANCE.read(sidecar.toFile()).toProgram();
    if(!program.equals(reloaded)||reloaded.getKeygroups().get(0).getMpcLayers().size()!=6)throw new AssertionError("HALOBAR persisted export policy guard");
   }finally{Files.deleteIfExists(sidecar);Files.deleteIfExists(folder);}
  }
  if(which.equals("native")){
   var root=(JsonValue.Obj)Json.INSTANCE.parse(new Mpc3TrackWriter().keygroupPayloadText(program,Mpc3TrackWriter.DEFAULT_TRACK_COLOUR));
   var data=(JsonValue.Obj)root.getEntries().get("data");var p=(JsonValue.Obj)data.getEntries().get("program");var drum=(JsonValue.Obj)p.getEntries().get("drum");
   var instruments=(JsonValue.Arr)drum.getEntries().get("instruments");var instrument=(JsonValue.Obj)instruments.getItems().get(0);var layers=(JsonValue.Arr)instrument.getEntries().get("layersv");
   int start=(int)((JsonValue.Num)((JsonValue.Obj)layers.getItems().get(0)).getEntries().get("velocityStart")).getValue();
   if(start!=80)throw new AssertionError("HALOBAR native window order guard");
  }
  System.out.println(which+" exact");
 }
}
