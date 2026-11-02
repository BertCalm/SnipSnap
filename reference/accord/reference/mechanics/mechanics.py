"""Accord exploratory mechanics, Python harness only. No production DSP claims.

The audio prototype template assumes a renderer. This physics-only scout instead
uses SciPy DOP853 and reports the same deterministic/finite/work-budget concerns.
"""
import numpy as np
from scipy.integrate import solve_ivp
from time import perf_counter
import json
from pathlib import Path

N=15
W=np.sqrt(.02/.024)
PERIOD=2*np.pi/W
SEED=20261022
rng=np.random.default_rng(SEED)
PHI=rng.uniform(0,2*np.pi,N)
ETA=rng.uniform(-1,1,N); ETA-=ETA.mean(); ETA/=np.abs(ETA).max()

def run(base_mass=.4,damping=.03,coupling=1,spread=.01,mu=.05,cycles=160,rtol=1e-7,dense=False,drive=True,theta0=.38):
    h=np.full(N,.02); k=h.copy(); wn=W*(1+spread*ETA); J=k/wn**2
    H=.3+base_mass
    y0=np.r_[2*theta0*np.cos(PHI),-2*theta0*wn*np.sin(PHI),0.,0.]
    def f(t,y):
        theta=y[:N]; omega=y[N:2*N]; v=y[-1]
        sn=np.sin(theta); q=coupling*h*np.cos(theta)
        b=J*mu*(1-(theta/theta0)**2)*omega-k*sn if drive else -k*sn
        D=H-np.sum(q*q/J)
        ab=(np.sum(coupling*h*sn*omega**2)-damping*v-np.sum(q*b/J))/D
        return np.r_[omega,(b-q*ab)/J,v,ab]
    ts=np.linspace(0,cycles*PERIOD,int(cycles*40)+1)
    begin=perf_counter()
    sol=solve_ivp(f,[0,ts[-1]],y0,method='DOP853',rtol=rtol,atol=rtol/100,t_eval=ts,dense_output=dense)
    wall=perf_counter()-begin
    phase=np.unwrap(np.arctan2(-sol.y[N:2*N]/wn[:,None],sol.y[:N]),axis=1)
    R1=np.abs(np.mean(np.exp(1j*phase),axis=0)); R2=np.abs(np.mean(np.exp(2j*phase),axis=0))
    window=ts>ts[-1]-20*PERIOD
    frequencies=(phase[:,window][:,-1]-phase[:,window][:,0])/(ts[window][-1]-ts[window][0])/(2*np.pi)
    D=H-np.sum((coupling*h[:,None]*np.cos(sol.y[:N]))**2/J[:,None],axis=0)
    data=dict(mass=base_mass,damping=damping,coupling=coupling,spread=spread,mu=mu,theta0=theta0,cycles=cycles,rtol=rtol,wall_s=wall,nfev=sol.nfev,success=sol.success,finite=bool(np.isfinite(sol.y).all()),R1_start=float(np.mean(R1[:800])),R1_end=float(np.mean(R1[window])),R2_end=float(np.mean(R2[window])),frequency_spread_relative=float(np.ptp(frequencies)/np.mean(frequencies)),D_min_observed=float(D.min()),D_lower_bound=float(H-np.sum((coupling*h)**2/J)),J_min=float(J.min()),max_angle=float(np.abs(sol.y[:N]).max()),max_base_displacement=float(np.abs(sol.y[-2]).max()))
    return data,sol,R1,R2

if __name__=='__main__':
    results=[]
    combos=[(mass,c,mu) for mass in [.05,.2,.6] for c in [.005,.05,.2] for mu in [.03,.1]]
    for mass,c,mu in combos:
        data,*_=run(mass,c,1,.01,mu,140)
        results.append(data); print(json.dumps(data),flush=True)
    Path(__file__).with_name('scout.json').write_text(json.dumps(results,indent=2))
