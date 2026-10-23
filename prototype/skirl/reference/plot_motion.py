"""Plot measured descriptor histories from the independently reduced disk solver."""
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

HERE=Path(__file__).resolve().parent
fig,axes=plt.subplots(4,1,figsize=(9,10),sharex=True,constrained_layout=True)
for name,label,color in [('curated_roll_motion.npz','Earlier finish','#238a8d'),
                         ('curated_late_motion.npz','Shallow launch / late finish','#bf641c')]:
    d=np.load(HERE/name)
    t=d['time_s']
    axes[0].plot(t,d['contact_rate_hz'],color=color,label=label)
    axes[1].plot(t,np.degrees(d['alpha_rad']),color=color)
    # The flat resting gravitational potential is not available excitation.
    energy=d['mechanical_energy_J']-.3*9.81*.01/2
    axes[2].plot(t,energy,color=color)
    axes[2].plot(t,energy+d['resistance_work_J'],color=color,ls=':',alpha=.7)
    reaction=d['reaction_N']
    ratio=np.linalg.norm(reaction[:,:2],axis=1)/reaction[:,2]
    axes[3].plot(t,ratio,color=color)
axes[0].set_ylabel('Contact circulation (Hz)')
axes[0].legend(loc='upper left')
axes[1].set_ylabel('Inclination (degrees)')
axes[2].set_ylabel('Mechanical energy (J)')
axes[2].text(.02,.08,'Solid: available above flat rest; dotted: energy + resistance work',
             transform=axes[2].transAxes,fontsize=9)
axes[3].axhline(.8,color='#9e3042',ls='--',label='Assumed static-friction coefficient .8')
axes[3].set_ylabel('Required friction ratio')
axes[3].set_xlabel('Time (seconds; common time scale 1.0)')
axes[3].legend(loc='upper left')
for ax in axes:
    ax.grid(alpha=.2)
    ax.spines[['top','right']].set_visible(False)
fig.suptitle('Skirl: ideal no-slip disk motion\nNumerical prototype with assumed resistance and authored terminal thresholds')
fig.savefig(HERE/'motion_diagnostics.png',dpi=160)
