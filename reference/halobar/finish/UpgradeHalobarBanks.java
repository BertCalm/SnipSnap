import com.snipsnap.shell.HalobarPadMaker;
import com.snipsnap.kit.InstrumentStore;
import java.io.File;
import java.nio.file.*;
import java.util.*;
public class UpgradeHalobarBanks {
 public static void main(String[] args)throws Exception {
  File folder=new File(args[0]);
  File[] recipes=folder.listFiles((dir,name)->name.endsWith(".halobar-keys.json"));
  if(recipes==null||recipes.length!=6)throw new AssertionError("Six accepted bank recipes required");
  Arrays.sort(recipes);int roots=0;
  for(File recipe:recipes){
   String name=recipe.getName().replace(".halobar-keys.json","");
   var program=HalobarPadMaker.INSTANCE.upgradeCycleExport(folder,name);
   var reloaded=InstrumentStore.INSTANCE.read(InstrumentStore.INSTANCE.sidecar(folder,name)).toProgram();
   if(!program.equals(reloaded))throw new AssertionError("Export policy lost after upgrade: "+name);
   if(!program.equals(HalobarPadMaker.INSTANCE.upgradeCycleExport(folder,name)))throw new AssertionError("Non-idempotent upgrade: "+name);
   for(var zone:program.getKeygroups())if(!zone.getMpcCycle()||!zone.getMpcVelocityCycle()||zone.getMpcLayers().size()!=6)throw new AssertionError("Missing takes: "+name);
   roots+=program.getKeygroups().size();System.out.println(name+"\t"+program.getKeygroups().size());
  }
  if(roots!=71)throw new AssertionError("Root coverage changed: "+roots);
 }
}
