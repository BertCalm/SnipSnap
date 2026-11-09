export const RATE=44100;
export const defaults={tune:.5,punch:.58,space:.42,drift:.42,drive:.35,hold:.4};
export const names=['Kick','Snare','Closed hat','Open hat','Low tom','High tom','Rim','Metal','Data tick','Packet roll','Laser chip','Relay rattle','Ride bell','Ride cymbal','Sub kick','Crack snare'];
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
function noise(seed){return ()=>{seed=(Math.imul(seed,1664525)+1013904223)|0;return (seed>>>0)/2147483648-1}}
function lowpass(x,hz){let s=0,a=1-Math.exp(-2*Math.PI*hz/RATE);for(let i=0;i<x.length;i++){s+=a*(x[i]-s);x[i]=s;}return x}
function highpass(x,hz){let s=0,a=1-Math.exp(-2*Math.PI*hz/RATE);for(let i=0;i<x.length;i++){s+=a*(x[i]-s);x[i]-=s;}return x}
export function hit(kind,tune=.5,velocity=1,layer=2,take=0){
 layer=clamp(layer|0,0,2);take=clamp(take|0,0,3);
 const strike=[.38,.68,1][layer], cents=[0,-2.4,1.7,.9][take];
 const n=Math.floor(RATE*([.65,.45,.14,.7,.7,.5,.18,.85,.22,.48,.4,.65,1.8,2.6,.95,.38][kind])),x=new Float32Array(n),rnd=noise(8123+kind+take*104729),pitch=2**((tune-.5)+cents/1200);let phase=0,lp=0,prev=0,held=0;
 for(let i=0;i<n;i++){const t=i/RATE,r=rnd(),hp=r-prev;prev=r;lp+=.14*(r-lp);let v=0;
 if(kind===0){phase+=2*Math.PI*(51+105*Math.exp(-t*65))*pitch/RATE;v=Math.sin(phase)*Math.exp(-t*12)+.25*hp*Math.exp(-t*340);}
 if(kind===1){v=(Math.sin(2*Math.PI*188*pitch*t)+.32*Math.sin(2*Math.PI*317*pitch*t))*Math.exp(-t*26)*.58+(r-lp)*Math.exp(-t*20)*.85;}
 if(kind===2||kind===3){let metal=0;for(const f of [4211,5887,7333,8651])metal+=Math.sin(2*Math.PI*f*pitch*t);v=(hp*.42+metal*.075)*Math.exp(-t*(kind===2?62:10));}
 if(kind===4||kind===5){let f=(kind===4?95:145)*pitch;phase+=2*Math.PI*f*(1+.35*Math.exp(-t*45))/RATE;v=(Math.sin(phase)+.3*Math.sin(phase*1.59)+.15*Math.sin(phase*2.14))*Math.exp(-t*9)+hp*.15*Math.exp(-t*160);}
 if(kind===6)v=(Math.sin(2*Math.PI*830*pitch*t)+.45*Math.sin(2*Math.PI*1771*pitch*t))*Math.exp(-t*70)+hp*.2*Math.exp(-t*200);
 if(kind===7)v=(Math.sin(2*Math.PI*537*pitch*t+2*Math.sin(2*Math.PI*839*pitch*t))*.4+hp*.14)*Math.exp(-t*7);
 // Glitch gestures use short ramps at each retrigger, rather than hard gates.
 if(kind===8){const u=t%(.026),burst=Math.floor(t/.026);v=(Math.sin(2*Math.PI*(1900+burst*613)*pitch*u)*.4+hp*.2)*Math.exp(-u*240)*Math.min(1,u/.0015)*Math.exp(-t*22);}
 if(kind===9){const u=t%.047,burst=Math.floor(t/.047);if(i%9===0)held=Math.round(r*7)/7;v=(held*.55+Math.sin(2*Math.PI*(310+burst*89)*pitch*u)*.35)*Math.exp(-u*100)*Math.min(1,u/.002)*Math.exp(-t*5);}
 if(kind===10){phase+=2*Math.PI*(170+3200*Math.exp(-t*30))*pitch/RATE;v=Math.sin(phase+1.1*Math.sin(phase*.73))*Math.exp(-t*18)*.8;}
 if(kind===11){const u=t%.073,burst=Math.floor(t/.073);v=(Math.sin(2*Math.PI*(430+(burst%3)*170)*pitch*u)*.38+hp*.32)*Math.exp(-u*82)*Math.min(1,u/.002)*Math.exp(-t*5)*(1+.25*Math.sin(burst*2.4));}
 // Inharmonic modal ratios give the bell a focused ping and the bow a diffuse wash.
 if(kind===12){for(const [f,a,d] of [[780,.55,3.4],[1231,.28,4.2],[2057,.17,5.8],[3281,.1,8.5],[4729,.07,11]])v+=a*Math.sin(2*Math.PI*f*pitch*t)*Math.exp(-t*d);v+=hp*.12*Math.exp(-t*120);}
 if(kind===13){for(const [f,a,d] of [[621,.19,2.7],[1033,.16,3.2],[1811,.14,3.6],[2971,.12,4],[4139,.09,4.5],[5737,.06,5],[7219,.04,6]])v+=a*Math.sin(2*Math.PI*f*pitch*t)*Math.exp(-t*d);v+=(r-lp)*.38*Math.exp(-t*3.6)+hp*.16*Math.exp(-t*95);}
 if(kind===14){phase+=2*Math.PI*(42+90*Math.exp(-t*55))*pitch/RATE;v=(Math.sin(phase)+.12*Math.sin(2*phase))*Math.exp(-t*7)+hp*.09*Math.exp(-t*280);}
 if(kind===15){const u=Math.max(0,t-.012);v=(Math.sin(2*Math.PI*252*pitch*t)+.25*Math.sin(2*Math.PI*401*pitch*t))*.42*Math.exp(-t*36)+(r-lp)*.95*Math.exp(-t*30);if(t>.012)v+=hp*.25*Math.exp(-u*85)*Math.min(1,u/.001);}
 x[i]=v*velocity*.58*Math.min(1,i/22)*Math.min(1,(n-i)/180);
 }
 // Softer excitation loses upper modes and decays sooner, before compressor and amp.
 // Hard take zero deliberately follows the original signal path byte for byte.
 if(layer<2){lowpass(x,1800+strike*10500);for(let i=0;i<x.length;i++)x[i]*=strike*Math.exp(-i/RATE*(1-strike)*2.1);}
 if(take>0)for(let i=0;i<x.length;i++)x[i]*=1+(.018*Math.sin(take*2.3))*Math.exp(-i/RATE*30);
 return highpass(x,20);
}
export function pattern(style='rolling'){
 let p=Array.from({length:names.length},()=>Array(32).fill(0));
 for(const s of [0,10,16,22,27])p[0][s]=.95;
 for(const s of [4,12,20,28])p[1][s]=.95;
 for(const s of [7,11,18,23,26,30])p[1][s]=.26;
 for(let s=0;s<32;s++)if(s%2===0||s%8===7)p[2][s]=s%4===0?.64:.36;
 p[3][14]=.42;p[3][30]=.4;p[4][31]=.65;p[5][29]=.5;p[6][9]=.36;p[7][24]=.32;
 if(style==='halftime'){p[0]=Array(32).fill(0);p[1]=Array(32).fill(0);for(let s of [0,14,16,27])p[0][s]=.9;for(let s of [8,24])p[1][s]=.9;}
 if(style==='fracture'){p[8][3]=.32;p[9][15]=.4;p[10][25]=.38;p[11][31]=.35;p[12][0]=.35;p[13][16]=.25;p[0][22]=0;p[14][22]=.8;p[1][28]=0;p[15][28]=.85;for(let s of [3,15,19,25,31])p[1][s]=.5;p[7][6]=.4;p[5][13]=.6;}
 return p;
}
function source(p,bpm,tune,cycles,roundRobin){const len=Math.round(RATE*480/bpm),x=new Float32Array(len*cycles),bank=new Map(),counts=Array.from({length:16},()=>[0,0,0]);for(let c=0;c<cycles;c++)for(let k=0;k<names.length;k++)for(let step=0;step<32;step++){let vel=p[k][step];if(!vel)continue;const layer=vel<42/127?0:vel<85/127?1:2,take=roundRobin?counts[k][layer]++%4:0,key=`${k}:${layer}:${take}`;if(!bank.has(key))bank.set(key,hit(k,tune,1,layer,take));const h=bank.get(key),start=c*len+Math.round(step*len/32);for(let i=0;i<h.length&&start+i<x.length;i++)x[start+i]+=h[i]*vel;}return {x,len};}
export function process(input,m){
 const x=new Float32Array(input.length);let env=0,detLP=0;const sc=1-Math.exp(-2*Math.PI*120/RATE),attack=Math.exp(-1/(.008*RATE)),release=Math.exp(-1/(.09*RATE));
 for(let i=0;i<x.length;i++){const v=input[i];detLP+=sc*(v-detLP);let d=Math.abs(v-detLP),a=d>env?attack:release;env=a*env+(1-a)*d;const threshold=.24-.18*m.punch,ratio=2+8*m.punch,g=env>threshold?(threshold/env)**(1-1/ratio):1;x[i]=v*(.38+.62*g*(1+1.4*m.punch));}
 // Damped, interpolated combs: each loop gain stays below 0.9. Motion never enters the dry path.
 const delays=[.0411,.0533,.0677,.0793],bufs=delays.map(d=>new Float32Array(Math.ceil((d+.02)*RATE))),pos=[0,0,0,0],states=[0,0,0,0];let duck=0;
 const wetIn=highpass(new Float32Array(x),220),out=new Float32Array(x.length),damp=1-Math.exp(-2*Math.PI*(5000-3000*m.drift)/RATE),feedback=.56+.32*m.hold;
 for(let i=0;i<x.length;i++){let wet=0;for(let j=0;j<4;j++){const b=bufs[j],delay=(delays[j]+m.drift*.003*Math.sin(2*Math.PI*(.19+j*.071)*i/RATE+j))*RATE;let r=(pos[j]-delay+b.length)%b.length,ri=Math.floor(r),f=r-ri,v=b[ri]*(1-f)+b[(ri+1)%b.length]*f;states[j]+=damp*(v-states[j]);b[pos[j]]=wetIn[i]*.35+states[j]*feedback;pos[j]=(pos[j]+1)%b.length;wet+=v*.5;}duck=Math.max(Math.abs(x[i]),duck*.9995);out[i]=x[i]+wet*m.space*2/(1+duck*3);}
 // Four internal steps around the asymmetric amp; cascaded lowpasses before output sampling.
 let prev=0,lp1=0,lp2=0,sag=0;const aa=1-Math.exp(-2*Math.PI*6500/(RATE*4));
 for(let i=0;i<out.length;i++){let v=out[i];sag+=.0003*(Math.abs(v)-sag);for(let k=1;k<=4;k++){const u=prev+(v-prev)*k/4,gain=1+8*m.drive;let z=(Math.tanh(u*gain/(1+sag*m.drive*2)+.08*m.drive)-Math.tanh(.08*m.drive))/Math.sqrt(gain);lp1+=aa*(z-lp1);lp2+=aa*(lp1-lp2);}out[i]=lp2;prev=v;}
 return highpass(out,25);
}
function rms(x){let s=0;for(const v of x)s+=v*v;return Math.sqrt(s/x.length)}
export function render({macros=defaults,bpm=170,grid=pattern(),solo=null,velocity=127,layer=null,take=0,roundRobin=true,repeat=0}={}){
 if(solo!==null && repeat>0){
  const count=clamp(repeat|0,1,16),spacing=Math.round(RATE*.18),dry=new Float32Array((count-1)*spacing+RATE*4),wet=new Float32Array(dry.length),begin=performance.now();
  for(let n=0;n<count;n++){const r=render({macros,solo,velocity,layer,take:roundRobin?(take+n)%4:0});for(let i=0;i<r.wet.length;i++){dry[n*spacing+i]+=r.dry[i]*.5;wet[n*spacing+i]+=r.wet[i]*.5;}}
  return {dry,wet,ms:performance.now()-begin,metrics:{dryRms:rms(dry),wetRms:rms(wet)}};
 }
 const begin=performance.now();let m={};for(const k in defaults)m[k]=Number.isFinite(macros[k])?clamp(macros[k],0,1):defaults[k];bpm=clamp(bpm,120,190);
 let dry,wet;
 if(solo!==null){const h=hit(clamp(solo|0,0,names.length-1),m.tune,1,layer??(velocity<42?0:velocity<85?1:2),take);dry=new Float32Array(RATE*4);dry.set(h);wet=process(dry,m);}
 else{const {x,len}=source(grid,bpm,m.tune,3,roundRobin);const w=process(x,m);dry=x.slice(2*len);wet=w.slice(2*len);}
 // RMS-matched comparison with a common peak headroom constraint; no louder-is-better switch.
 const rd=rms(dry),rw=rms(wet);let gd=.13/Math.max(rd,1e-9),gw=.13/Math.max(rw,1e-9),peak=0;
 for(let i=0;i<dry.length;i++)peak=Math.max(peak,Math.abs(dry[i]*gd),Math.abs(wet[i]*gw));let safe=Math.min(1,.88/Math.max(peak,1e-9));const dynamics=solo===null?1:clamp(velocity,1,127)/127;gd*=safe*dynamics;gw*=safe*dynamics;
 for(let i=0;i<dry.length;i++){const fade=solo!==null?Math.min(1,(dry.length-1-i)/441):Math.min(1,i/88,(dry.length-1-i)/88);dry[i]*=gd*fade;wet[i]*=gw*fade;}
 return {dry,wet,ms:performance.now()-begin,metrics:{dryRms:rms(dry),wetRms:rms(wet),peak:peak*safe*dynamics,loopJump:Math.abs(wet[0]-wet.at(-1))}};
}
if(typeof self!=='undefined'&&typeof document==='undefined')self.onmessage=e=>{try{const r=render(e.data);self.postMessage({...r,id:e.data.id},[r.dry.buffer,r.wet.buffer]);}catch(err){self.postMessage({id:e.data.id,error:String(err)});}};
