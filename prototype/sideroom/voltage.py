"""R8: broader off-center snare, lower shells and long cymbals."""
from pathlib import Path
import sys
from pocket import main
CONFIG={'kit':'high-voltage','revision':'R8 four synthesized genre kits','previous':['angular','tight-pocket','open-road'],'base_model':'open-road-model.json','sources':'voltage-sources.json','articulation':'offcenter','levels':[13,23,32],
'recipe':{'kick':(.86,1.25,-.06),'snare':(.90,1.32,.04),'hat':(.96,1.12,-.03),'openhat':(.96,1.30,-.03),'tom':(.84,1.36,-.04),'floor':(.82,1.40,-.05),'ride':(.96,1.25,.01),'crash':(.94,1.40,-.02)}}
if __name__=='__main__':main(Path(sys.argv[1]),Path(sys.argv[2]),CONFIG)
