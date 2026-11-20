"""R9: velocity-dependent snare articulation and a funk-oriented fifth kit."""
from pathlib import Path
import sys
from pocket import main
CONFIG={'kit':'deep-pocket','revision':'R9 five synthesized genre kits','previous':['high-voltage','angular','tight-pocket','open-road'],'base_model':'open-road-model.json','sources':'deep-sources.json','articulation':'velocity-dependent','layer_articulations':['center','offcenter','rimshot'],'levels':[13,23,10],
'recipe':{'kick':(1.04,.72,.02),'snare':(1.30,.72,.03),'hat':(1.01,.78,.04),'openhat':(1.01,.90,.02),'tom':(1.08,.82,-.01),'floor':(1.04,.80,-.03),'ride':(1.03,.87,.01),'crash':(1.02,.86,-.02)}}
if __name__=='__main__':main(Path(sys.argv[1]),Path(sys.argv[2]),CONFIG)
