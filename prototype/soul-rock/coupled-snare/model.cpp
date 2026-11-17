#include <cmath>
#include <algorithm>
// Unit modal masses. Positive spring energies and viscous damping; all contacts
// apply equal/opposite projected forces. This is a reduced model, not a drum FEM.
extern "C" void simulate(int n,int h,int w,double fs,const double* freq,const double* decay,const double* strike,const double* air,const double* contacts,const double* wf,const double* gaps,double velocity,double coupling,double wires,double* out){
 double q[80]={},v[80]={},a[80],k[80],d[80];int m=2*h+w;
 for(int j=0;j<m;j++){double f=j<2*h?freq[j]:wf[j-2*h];k[j]=pow(6.283185307179586*f,2);d[j]=j<2*h?13.81551056/decay[j]:30.+j*.45;}
 for(int j=0;j<h;j++)v[j]=strike[j]*velocity;
 const double ka=coupling*pow(6.283185307179586*155.,2),kc=pow(6.283185307179586*2800.,2);double dt=1/fs;
 for(int i=0;i<n;i++){
  double airx=0,airv=0;for(int j=0;j<2*h;j++){a[j]=-k[j]*q[j]-d[j]*v[j];airx+=air[j]*q[j];airv+=air[j]*v[j];}
  for(int j=0;j<2*h;j++)a[j]-=air[j]*(ka*airx+coupling*20*airv);
  for(int j=2*h;j<m;j++)a[j]=-k[j]*q[j]-d[j]*v[j];
  double collision=0,ce=0;
  for(int z=0;z<w;z++){
   double x=0,speed=0;for(int j=0;j<h;j++){double c=contacts[z*h+j];x+=c*q[h+j];speed+=c*v[h+j];}
   double penetration=x-q[2*h+z]-gaps[z],rel=speed-v[2*h+z];
   if(penetration>0 && wires>0){double f=wires*(kc*penetration+55*std::max(0.,rel));a[2*h+z]+=f;for(int j=0;j<h;j++)a[h+j]-=contacts[z*h+j]*f;collision+=f;ce+=.5*wires*kc*penetration*penetration;}
  }
  double top=0,bottom=0,wire=0,energy=.5*ka*airx*airx+ce;
  for(int j=0;j<m;j++){energy+=.5*(v[j]*v[j]+k[j]*q[j]*q[j]);v[j]+=a[j]*dt;q[j]+=v[j]*dt;if(j<h)top+=strike[j]*v[j];else if(j<2*h)bottom+=air[j]*v[j];else wire+=v[j]*(1.+.2*sin(j*2.1));}
  out[i*5]=top;out[i*5+1]=bottom;out[i*5+2]=wire;out[i*5+3]=collision;out[i*5+4]=energy;
 }
}
