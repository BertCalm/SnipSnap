"""Re-run selected fixtures at target tolerances; event-derived diagnostics.

Fixed and gathered have identical seeded initial angles/velocities/rate offsets.
Crossing phase is linear between consecutive positive-direction passages, not an
instantaneous harmonic approximation. Curated fixture, not a universal preset.
"""
import numpy as np
from scipy.optimize import brentq
from scipy.integrate import solve_ivp
from pathlib import Path
import json
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from mechanics import run,N,W,PERIOD,PHI,ETA,SEED

ROOT=Path(__file__).parent

def matched_event_error(a,b):
    """Compare each mechanism/direction event history by its local ordinal."""
    differences=[]
    for i in range(N):
        for direction in [-1,1]:
            aa=a[(a[:,1]==i)&(a[:,2]==direction),0]
            bb=b[(b[:,1]==i)&(b[:,2]==direction),0]
            if len(aa)!=len(bb):return None
            differences.extend(np.abs(aa-bb).tolist())
    return float(max(differences))

def extract(sol):
    events=[]; positives=[]
    for i in range(N):
        theta=sol.y[i]
        idx=np.where(theta[:-1]*theta[1:]<0)[0]
        pp=[]
        for j in idx:
            tc=brentq(lambda t: sol.sol(t)[i],sol.t[j],sol.t[j+1],xtol=1e-11)
            yc=sol.sol(tc); speed=yc[N+i]; direction=1 if speed>0 else -1
            events.append([tc/PERIOD,i,direction,abs(speed)])
            if direction>0: pp.append(tc)
        positives.append(np.asarray(pp))
    phase=np.full((N,len(sol.t)),np.nan)
    for i,cross in enumerate(positives):
        within=(sol.t>=cross[0])&(sol.t<=cross[-1])
        phase[i,within]=np.interp(sol.t[within],cross,2*np.pi*np.arange(len(cross)))
    valid=np.isfinite(phase).all(axis=0)
    R1=np.abs(np.mean(np.exp(1j*phase[:,valid]),axis=0));R2=np.abs(np.mean(np.exp(2j*phase[:,valid]),axis=0))
    tv=sol.t[valid]; end=tv>tv[-1]-20*PERIOD
    freq=(phase[:,valid][:,end][:,-1]-phase[:,valid][:,end][:,0])/(tv[end][-1]-tv[end][0])/(2*np.pi)
    pair=phase[:,valid][:,end]-phase[0,valid][end]
    pair_swing=np.ptp(pair,axis=1)
    events=np.asarray(sorted(events,key=lambda e:e[0]))
    # Approximate first durable high-R region for this measured finite horizon only.
    high=R1>.95
    qualifying=None
    for j in np.where(high)[0]:
        target=np.searchsorted(tv,tv[j]+20*PERIOD)
        if target<len(tv) and high[j:target].all():
            qualifying=float(tv[j]/PERIOD);break
    metrics=dict(crossing_R1_mean_final20=float(R1[end].mean()),crossing_R2_mean_final20=float(R2[end].mean()),crossing_frequency_spread_relative=float(np.ptp(freq)/freq.mean()),max_pairwise_phase_swing_vs_osc0_final20_rad=float(pair_swing.max()),first_R1_above_095_persisting20cycles=qualifying,event_count=len(events))
    return metrics,events,tv/PERIOD,R1,R2,phase

def isolated():
    mu=.1;th0=.38;J=.024;k=.02
    def f(t,y):
        th,om=y
        return [om,mu*(1-(th/th0)**2)*om-k/J*np.sin(th)]
    ts=np.linspace(0,180*PERIOD,180*100+1)
    sol=solve_ivp(f,[0,ts[-1]],[.3,0],method='DOP853',rtol=1e-10,atol=1e-12,t_eval=ts,dense_output=True)
    end=ts>160*PERIOD
    ix=np.where((sol.y[0,:-1]<0)&(sol.y[0,1:]>=0))[0]
    crosses=np.array([brentq(lambda t:sol.sol(t)[0],ts[j],ts[j+1]) for j in ix])
    cp=np.diff(crosses[crosses>160*PERIOD])
    return dict(mu=mu,theta0=th0,settled_angle_amplitude=float(np.abs(sol.y[0,end]).max()),settled_period_normalized=float(cp.mean()),period_relative_spread=float(np.ptp(cp)/cp.mean()),reference_period=PERIOD,amplitude_weak_vdp_prediction=2*th0)

def checks():
    h=np.full(N,.02);k=h.copy();J=k/(W*(1+.01*ETA))**2;H=.9;kap=1.;mu=.1;cb=.05;th0=.38
    max_error=0.
    for seed in range(20):
        rr=np.random.default_rng(seed);theta=rr.uniform(-1,1,N);omega=rr.normal(0,1,N);v=.1
        q=kap*h*np.cos(theta);b=J*mu*(1-(theta/th0)**2)*omega-k*np.sin(theta)
        bb=np.sum(kap*h*np.sin(theta)*omega**2)-cb*v
        D=H-np.sum(q*q/J);ab=(bb-np.sum(q*b/J))/D;acc=np.r_[(b-q*ab)/J,ab]
        A=np.zeros((N+1,N+1));A[:N,:N]=np.diag(J);A[N,:N]=q;A[:N,N]=q;A[N,N]=H
        direct=np.linalg.solve(A,np.r_[b,bb]);max_error=max(max_error,float(np.max(np.abs(acc-direct))))
    def energy(y):
        theta=y[:N];omega=y[N:2*N];v=y[2*N+1]
        return .5*H*v*v+np.sum(.5*J[:,None]*omega**2+kap*h[:,None]*v*np.cos(theta)*omega+k[:,None]*(1-np.cos(theta)),axis=0)
    def diag(drive,damp,rtol):
        def f(t,y):
            theta=y[:N];omega=y[N:2*N];v=y[2*N+1];sn=np.sin(theta);q=kap*h*np.cos(theta)
            Q=J*mu*(1-(theta/th0)**2)*omega if drive else np.zeros(N)
            b=Q-k*sn;D=H-np.sum(q*q/J)
            ab=(np.sum(kap*h*sn*omega**2)-damp*v-np.sum(q*b/J))/D
            return np.r_[omega,(b-q*ab)/J,v,ab,np.sum(Q*omega)-damp*v*v]
        y0=np.r_[.76*np.cos(PHI),-.76*W*(1+.01*ETA)*np.sin(PHI),0.,0.,0.]
        ts=np.linspace(0,30*PERIOD,1201)
        sol=solve_ivp(f,[0,ts[-1]],y0,method='DOP853',rtol=rtol,atol=rtol/100,t_eval=ts)
        E=energy(sol.y); balance=E-E[0]-sol.y[-1]
        P=H*sol.y[2*N+1]+np.sum(kap*h[:,None]*np.cos(sol.y[:N])*sol.y[N:2*N],axis=0)
        return dict(rtol=rtol,relative_energy_work_residual_max=float(np.max(np.abs(balance))/abs(E[0])),momentum_max_absolute_error=float(np.max(np.abs(P-P[0]))),nfev=sol.nfev)
    return dict(Schur_direct_max_absolute_accel_difference=max_error,conservative_loose=diag(False,0.,1e-7),conservative_tight=diag(False,0.,1e-10),driven_work_balance=diag(True,cb,1e-9))

def main():
    report=dict(harness='Python SciPy DOP853, not Kotlin',seed=SEED,reference_period_normalized=PERIOD,initial_state_R1=float(abs(np.mean(np.exp(1j*PHI)))),initial_state_R2=float(abs(np.mean(np.exp(2j*PHI)))),initialization='Seeded sinusoidal approximation; measured isolated limit cycle reported separately. Not production RNG.',phase_method='Phase interpolated between same-direction center crossings; first/last incomplete cycles excluded.',isolated=isolated(),fixtures={})
    configs={'fixed':dict(base_mass=.6,damping=.05,coupling=0.,spread=.01,mu=.1),'gathered':dict(base_mass=.6,damping=.05,coupling=1.,spread=.01,mu=.1),'dispersed_mobile':dict(base_mass=.05,damping=.005,coupling=1.,spread=.01,mu=.1)}
    traces={}
    for name,kw in configs.items():
        d,sol,*_=run(**kw,cycles=140,rtol=1e-9,dense=True)
        metrics,events,tc,r1,r2,phase=extract(sol);d.update(metrics);report['fixtures'][name]=d
        np.savez_compressed(ROOT/(name+'.npz'),time_reference_cycles=sol.t/PERIOD,state=sol.y,events=events,phase_time_reference_cycles=tc,R1=r1,R2=r2,rate_offsets=ETA,initial_phases=PHI)
        (ROOT/(name+'-events.json')).write_text(json.dumps(dict(units='reference cycles; at reference f Hz, audio seconds = time_reference_cycles / f',schema=['time_reference_cycles','oscillator_index_0_to_14','direction_sign','abs_angular_speed_normalized'],events=events.tolist())))
        traces[name]=(tc,r1,r2,events,sol)
        print(name,json.dumps(d),flush=True)
    # Check same fixture converges in descriptor/event timing at stricter accuracy.
    d,sol,*_=run(**configs['gathered'],cycles=140,rtol=1e-11,dense=True)
    tight_metrics,tight_events,*_=extract(sol)
    standard=traces['gathered'][3]
    report['tolerance_convergence']=dict(tight_rtol=1e-11,tight_atol=1e-13,tight_nfev=d['nfev'],same_event_count=bool(len(standard)==len(tight_events)),event_matching='Per mechanism, direction, and local ordinal',max_event_time_difference_reference_cycles=matched_event_error(standard,tight_events),tight_crossing_R1_final20=tight_metrics['crossing_R1_mean_final20'])
    report['checks']=checks()
    short,short_sol,*_=run(**configs['gathered'],cycles=16,rtol=1e-9)
    report['short_render_mechanics_budget']=dict(reference_cycles=16,seconds_at2Hz=8,wall_s=short['wall_s'],nfev=short['nfev'],caveat='Python mechanics only, no acoustic render; not a Kotlin benchmark')
    fig,axs=plt.subplots(3,2,figsize=(13,10),layout='constrained')
    for row,(name,(t,r1,r2,events,sol)) in enumerate(traces.items()):
        axs[row,0].plot(t,r1,label='R1, full-cycle phase');axs[row,0].plot(t,r2,label='R2, two-passage concentration',alpha=.7)
        axs[row,0].set(title=name.replace('_',' '),ylabel='order parameter',ylim=(0,1.05),xlabel='reference cycles');axs[row,0].legend(fontsize=8)
        for oscillator in range(N):
            times=events[events[:,1]==oscillator,0]
            axs[row,1].plot(times,np.full(len(times),oscillator+1),'|',markersize=4)
        axs[row,1].set(xlim=(120,126),ylim=(.5,15.5),title='Center passages, late six reference cycles',xlabel='reference cycles',ylabel='mechanism')
    fig.suptitle('Accord mechanics probe: same seeded phases/rates, no audio feedback or master clock')
    fig.savefig(ROOT/'mechanics.png',dpi=150)
    (ROOT/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2),flush=True)

if __name__=='__main__':main()
