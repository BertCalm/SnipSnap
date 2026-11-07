'use strict';
// Derive duplicate WAVs and ZIPs on demand so historical audio stays hosted.
window.HeldDownloads=(()=>{
 const source=new Map(),fixed=new Map();
 async function bytes(url){if(!source.has(url))source.set(url,fetch(url).then(r=>{if(!r.ok)throw Error('Download failed: '+url);return r.arrayBuffer()}).then(b=>new Uint8Array(b)).catch(e=>{source.delete(url);throw e}));return source.get(url)}
 function evenRound(x){const lo=Math.floor(x),f=x-lo;return f<.5?lo:f>.5?lo+1:lo%2===0?lo:lo+1}
 function scalePCM24(input,scale){
  if(!(scale>=0&&scale<=1))throw Error('Invalid fixed gain');
  const out=input.slice(),v=new DataView(out.buffer,out.byteOffset,out.byteLength),text=(p,n)=>String.fromCharCode(...out.subarray(p,p+n));
  if(text(0,4)!=='RIFF'||text(8,4)!=='WAVE')throw Error('Expected PCM WAV');
  let format=false,data=-1,length=0;
  for(let p=12;p+8<=out.length;){const size=v.getUint32(p+4,true),id=text(p,4);if(p+8+size>out.length)throw Error('Truncated WAV');if(id==='fmt ')format=v.getUint16(p+8,true)===1&&v.getUint16(p+10,true)===1&&v.getUint32(p+12,true)===44100&&v.getUint16(p+22,true)===24;if(id==='data'){data=p+8;length=size}p+=8+size+(size&1)}
  if(!format||data<0||length%3)throw Error('Expected mono PCM24 / 44.1 kHz');
  for(let p=data;p<data+length;p+=3){let value=out[p]|out[p+1]<<8|out[p+2]<<16;if(value&0x800000)value-=0x1000000;const q=evenRound(value/8388608*scale*8388607);out[p]=q&255;out[p+1]=(q>>8)&255;out[p+2]=(q>>16)&255}
  return out;
 }
 async function fixedWav(url,scale){const key=url+':'+scale;if(!fixed.has(key))fixed.set(key,bytes(url).then(b=>{const data=scalePCM24(b,scale);return{data,url:URL.createObjectURL(new Blob([data],{type:'audio/wav'}))}}).catch(e=>{fixed.delete(key);throw e}));return fixed.get(key)}
 const table=Uint32Array.from({length:256},(_,i)=>{let c=i;for(let j=0;j<8;j++)c=c&1?0xedb88320^(c>>>1):c>>>1;return c>>>0});
 function crc32(b){let c=0xffffffff;for(const x of b)c=table[(c^x)&255]^(c>>>8);return(c^0xffffffff)>>>0}
 function zip(entries){
  const local=[],central=[];let offset=0,centralBytes=0;
  for(const entry of entries){const name=new TextEncoder().encode(entry.name),data=entry.data,crc=crc32(data),l=new Uint8Array(30+name.length),c=new Uint8Array(46+name.length),lv=new DataView(l.buffer),cv=new DataView(c.buffer);
   lv.setUint32(0,0x04034b50,true);lv.setUint16(4,20,true);lv.setUint16(6,0x800,true);lv.setUint16(12,33,true);lv.setUint32(14,crc,true);lv.setUint32(18,data.length,true);lv.setUint32(22,data.length,true);lv.setUint16(26,name.length,true);l.set(name,30);
   cv.setUint32(0,0x02014b50,true);cv.setUint16(4,20,true);cv.setUint16(6,20,true);cv.setUint16(8,0x800,true);cv.setUint16(14,33,true);cv.setUint32(16,crc,true);cv.setUint32(20,data.length,true);cv.setUint32(24,data.length,true);cv.setUint16(28,name.length,true);cv.setUint32(42,offset,true);c.set(name,46);
   local.push(l,data);central.push(c);offset+=l.length+data.length;centralBytes+=c.length;
  }
  const end=new Uint8Array(22),v=new DataView(end.buffer);v.setUint32(0,0x06054b50,true);v.setUint16(8,entries.length,true);v.setUint16(10,entries.length,true);v.setUint32(12,centralBytes,true);v.setUint32(16,offset,true);
  return new Blob([...local,...central,end],{type:'application/zip'});
 }
 function download(blob,name){const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(link.href),60000)}
 return{bytes,scalePCM24,fixedWav,zip,download};
})();
