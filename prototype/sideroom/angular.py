"""R7: rimshot-derived Angular kit, retaining both approved family members."""
from pathlib import Path
import sys
from pocket import main
CONFIG={'kit':'angular','revision':'R7 three synthesized genre kits','previous':['tight-pocket','open-road'],'base_model':'open-road-model.json','sources':'angular-sources.json','articulation':'rimshot','levels':[3,6,10],
'recipe':{'kick':(.88,.48,-.08),'snare':(1.25,.53,.02),'hat':(1.13,.50,.08),'openhat':(1.13,.46,.06),'tom':(1.23,.50,.01),'floor':(1.18,.52,-.02),'ride':(1.10,.62,.05),'crash':(1.08,.58,-.02)}}
if __name__=='__main__':main(Path(sys.argv[1]),Path(sys.argv[2]),CONFIG)
