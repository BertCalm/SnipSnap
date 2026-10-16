/* Flock audition: one player, real rendered WAVs, recorded simulation traces. */
'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const nodes = Object.fromEntries(['first-listen','first-duration','library-status','library-error','library-error-message','retry-load','comparison-options','causal-options','causal-chart-mode','causal-chart-caption','study-list','player-kicker','player-title','player-detail','play-button','stop-button','play-symbol','pause-symbol','seek','wave-progress','current-time','total-time','playback-status','player-error','clip-download','trace-loading','hero-trace-name','level-explanation'].map(id => [id, $(id)]));
  const gestureNames = {GATHER:'Gather',RIBBON:'Ribbon',SPLIT:'Split'};
  const modeNames = {FLOCK:'Flock',FIXED:'Fixed',INDEPENDENT:'Independent'};
  let manifest, selected, level = 'matched', audioContext, source, audioBuffer;
  let playing = false, loading = false, offset = 0, startedAt = 0, requestId = 0;
  let heroTrace = null, heroTraceClip = null, traceRequest = 0, causalMode = 'FLOCK';
  const bufferCache = new Map(), traceCache = new Map(), causalData = new Map();
  const byId = new Map();
  let lastDrawnTime = -1;

  const titleCase = s => String(s || '').toLowerCase().replace(/(^|\s)\S/g, c => c.toUpperCase());
  const timeLabel = n => { n = Math.max(0, Number(n) || 0); return `${Math.floor(n / 60)}:${(n % 60).toFixed(1).padStart(4,'0')}`; };
  const textEl = (tag, cls, text) => { const node = document.createElement(tag); if (cls) node.className = cls; node.textContent = text; return node; };
  const getCategory = clip => String(clip.category || '').toLowerCase();
  const clipPath = clip => level === 'raw' ? (clip.rawSrc || clip.src) : clip.src;
  const getSection = id => (manifest.sections || []).find(s => s.id === id);
  const sectionClips = id => {
    const section = getSection(id);
    return section?.clipIds ? section.clipIds.map(id => byId.get(id)).filter(Boolean) : manifest.clips.filter(c => getCategory(c) === id);
  };
  const defaultClip = gesture => sectionClips('gestures').find(c => c.gesture === gesture) || manifest.clips.find(c => c.gesture === gesture && c.mode === 'FLOCK' && !c.perturb);
  const currentTime = () => playing && audioContext ? Math.min(audioBuffer?.duration || selected?.duration || 0, offset + audioContext.currentTime - startedAt) : offset;
  const flowPairForClip = clip => manifest?.flowRevision?.pairs?.find(pair => pair.before === clip.id || pair.after === clip.id);
  const rateLabel = rate => `${Number(rate).toFixed(Number(rate) < 1 ? 3 : 2).replace(/0+$/, '').replace(/\.$/, '')}×`;

  async function readJson(path) {
    const response = await fetch(path);
    if (!response.ok) throw new Error(`The file ${path} could not be opened (${response.status}).`);
    return response.json();
  }

  function setError(message) {
    nodes['player-error'].textContent = message;
    nodes['player-error'].hidden = !message;
  }

  function setTransport() {
    nodes['play-symbol'].hidden = playing;
    nodes['pause-symbol'].hidden = !playing;
    nodes['play-button'].setAttribute('aria-label', `${playing ? 'Pause' : 'Play'} ${selected?.label || 'selected sound'}`);
    nodes['play-button'].disabled = !selected || loading;
    nodes['stop-button'].disabled = !selected;
    nodes.seek.disabled = !selected || loading;
  }

  function stopSource() {
    if (source) { source.onended = null; try { source.stop(); } catch (_) { /* Already ended. */ } source.disconnect(); source = null; }
    playing = false;
  }

  function pause() {
    offset = currentTime();
    requestId++;
    loading = false;
    stopSource();
    nodes['playback-status'].textContent = 'Paused';
    setTransport();
    updateProgress();
  }

  function stop() {
    requestId++;
    loading = false;
    stopSource();
    offset = 0;
    nodes['playback-status'].textContent = 'Stopped · ready to play';
    setTransport();
    updateProgress();
    drawHero();
  }

  async function getBuffer(path) {
    if (!bufferCache.has(path)) {
      const promise = (async () => {
        const response = await fetch(path);
        if (!response.ok) throw new Error(`The audio file could not be opened (${response.status}).`);
        const bytes = await response.arrayBuffer();
        return audioContext.decodeAudioData(bytes);
      })();
      bufferCache.set(path, promise);
      promise.catch(() => bufferCache.delete(path));
    }
    return bufferCache.get(path);
  }

  async function play(at) {
    if (!selected) return;
    const token = ++requestId;
    const path = clipPath(selected);
    if (typeof at === 'number') offset = at;
    stopSource();
    setError('');
    loading = true;
    nodes['playback-status'].textContent = 'Loading sound…';
    setTransport();
    try {
      if (!audioContext) {
        const Audio = window.AudioContext || window.webkitAudioContext;
        if (!Audio) throw new Error('This browser does not support the audio player. Download a WAV to listen.');
        audioContext = new Audio();
      }
      if (audioContext.state !== 'running') await audioContext.resume();
      const buffer = await getBuffer(path);
      if (token !== requestId) return;
      audioBuffer = buffer;
      if (offset >= buffer.duration - .02) offset = 0;
      offset = Math.max(0, Math.min(offset, buffer.duration));
      source = audioContext.createBufferSource();
      source.buffer = buffer;
      source.connect(audioContext.destination);
      source.onended = () => {
        if (token !== requestId) return;
        offset = buffer.duration;
        playing = false;
        source?.disconnect();
        source = null;
        nodes['playback-status'].textContent = 'Finished · play again';
        setTransport();
        updateProgress();
        drawHero();
      };
      startedAt = audioContext.currentTime;
      source.start(0, offset);
      playing = true;
      loading = false;
      nodes['playback-status'].textContent = `Playing · ${level === 'raw' ? 'raw' : 'matched'} level`;
      nodes.seek.max = buffer.duration;
      nodes['total-time'].textContent = timeLabel(buffer.duration);
      drawWave();
      setTransport();
      updateProgress();
    } catch (error) {
      if (token !== requestId) return;
      loading = false;
      playing = false;
      nodes['playback-status'].textContent = 'Playback unavailable';
      setError(`${error.message} Try again, or use the WAV download.`);
      setTransport();
    }
  }

  function select(clip, autoplay = false) {
    if (!clip) return;
    requestId++;
    stopSource();
    loading = false;
    offset = 0;
    audioBuffer = null;
    selected = clip;
    setError('');
    const flowPair = flowPairForClip(clip);
    nodes['player-title'].textContent = flowPair ? `FLOW ${Number(flowPair.value).toFixed(2)} · ${flowPair.after === clip.id ? 'revised' : 'previous'}` : clip.label || gestureNames[clip.gesture] || clip.id;
    nodes['player-kicker'].textContent = `${modeNames[clip.mode] || clip.mode} / ${level === 'raw' ? 'RAW' : 'MATCHED'}`;
    nodes['player-detail'].textContent = `${clip.noteLabel || `MIDI ${clip.note}`} · velocity ${Number(clip.velocity).toFixed(2)} · finite render`;
    nodes['clip-download'].href = clipPath(clip);
    nodes['clip-download'].setAttribute('aria-label', `Download ${clip.label} ${level} WAV`);
    nodes.seek.max = clip.duration;
    nodes.seek.value = '0';
    nodes['total-time'].textContent = timeLabel(clip.duration);
    nodes['playback-status'].textContent = 'Ready to play';
    document.querySelectorAll('[data-clip-id]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.clipId === clip.id)));
    document.querySelectorAll('.gesture-card').forEach(card => card.classList.toggle('is-selected', card.dataset.gesture === clip.gesture && getCategory(clip) === 'gestures'));
    for (const pair of manifest.causalPairs || []) {
      if (pair.on === clip.id || pair.off === clip.id) { causalMode = pair.mode; drawCausal(); break; }
    }
    setTransport();
    updateProgress();
    drawWave();
    loadHeroTrace(clip);
    if (autoplay) play();
  }

  function setLevel(next) {
    if (next === level) return;
    const wasPlaying = playing || loading;
    const position = currentTime();
    level = next;
    document.querySelectorAll('[data-level]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.level === level)));
    if (!selected) return;
    const clip = selected;
    select(clip, false);
    offset = position;
    updateProgress();
    drawHero();
    if (wasPlaying) play(position);
  }

  function updateProgress() {
    const now = currentTime();
    const duration = audioBuffer?.duration || selected?.duration || 1;
    nodes.seek.value = now;
    nodes.seek.setAttribute('aria-valuetext', `${timeLabel(now)} of ${timeLabel(duration)}`);
    nodes['current-time'].textContent = timeLabel(now);
    nodes['wave-progress'].style.width = `${Math.min(100, now / duration * 100)}%`;
  }

  function setupCanvas(id) {
    const canvas = $(id), rect = canvas.getBoundingClientRect(), ratio = Math.min(window.devicePixelRatio || 1, 2);
    if (!rect.width || !rect.height) return null;
    canvas.width = Math.round(rect.width * ratio);
    canvas.height = Math.round(rect.height * ratio);
    const context = canvas.getContext('2d');
    context.scale(ratio, ratio);
    return {canvas,context,width:rect.width,height:rect.height};
  }

  function drawWave() {
    const info = setupCanvas('wave-canvas');
    if (!info) return;
    const {context:c,width:w,height:h} = info;
    c.clearRect(0,0,w,h);
    c.strokeStyle = '#a6bca3'; c.lineWidth = 1;
    if (!audioBuffer) {
      c.setLineDash([1,5]); c.beginPath(); c.moveTo(0,h/2); c.lineTo(w,h/2); c.stroke(); return;
    }
    const samples = audioBuffer.getChannelData(0), columns = Math.max(1,Math.floor(w/3)), step = samples.length/columns;
    for (let x=0;x<columns;x++) {
      let min=0,max=0;
      const start=Math.floor(x*step), end=Math.floor((x+1)*step), sampleStep=Math.max(1,Math.floor((end-start)/40));
      for(let i=start;i<end;i+=sampleStep){const value=samples[i];if(value<min)min=value;if(value>max)max=value;}
      c.beginPath();c.moveTo(x*3+.5,h/2+min*h*.48);c.lineTo(x*3+.5,h/2+Math.max(max*h*.48,1));c.stroke();
    }
  }

  async function loadHeroTrace(clip) {
    const token = ++traceRequest;
    heroTraceClip = clip;
    nodes['hero-trace-name'].textContent = `${clip.gesture} / ${clip.noteLabel || clip.mode}`;
    if (!clip.traceSrc) {heroTrace=null;nodes['trace-loading'].textContent='No recorded trajectory is available for this render.';nodes['trace-loading'].hidden=false;drawHero();return;}
    nodes['trace-loading'].textContent='Opening the recorded traces…';nodes['trace-loading'].hidden=false;
    try {
      if (!traceCache.has(clip.traceSrc)) traceCache.set(clip.traceSrc,(async()=>{
        const response=await fetch(clip.traceSrc);if(!response.ok)throw new Error('Trace file unavailable');
        const rows=(await response.text()).trim().split(/\r?\n/).map(row=>row.split(','));
        const headers=rows.shift(), pitchCols=headers.map((name,i)=>/^pitch_\d+$/.test(name.trim())?i:-1).filter(i=>i>=0);
        if (!pitchCols.length) throw new Error('No pitch columns');
        return rows.filter(row=>row.length>=headers.length).map(row=>({seconds:Number(row[0]),pitches:pitchCols.map(i=>Number(row[i]))}));
      })());
      const frames=await traceCache.get(clip.traceSrc);if(token!==traceRequest)return;
      heroTrace=frames;nodes['trace-loading'].hidden=true;drawHero();
    } catch (_) {if(token!==traceRequest)return;heroTrace=null;nodes['trace-loading'].textContent='The recorded trajectory could not load. The audio still works.';nodes['trace-loading'].hidden=false;drawHero();}
  }

  function drawHero() {
    const info=setupCanvas('flock-canvas');if(!info)return;
    const {context:c,width:w,height:h}=info;c.clearRect(0,0,w,h);
    const pad={left:27,right:23,top:40,bottom:39},dw=w-pad.left-pad.right,dh=h-pad.top-pad.bottom;
    c.strokeStyle='#d6e7c80b';c.lineWidth=1;
    for(let i=0;i<=6;i++){const x=pad.left+i*dw/6;c.beginPath();c.moveTo(x,pad.top-8);c.lineTo(x,h-pad.bottom+8);c.stroke();}
    for(let i=0;i<=4;i++){const y=pad.top+i*dh/4;c.beginPath();c.moveTo(pad.left,y);c.lineTo(w-pad.right,y);c.stroke();}
    if(!heroTrace?.length)return;
    let min=Infinity,max=-Infinity;
    for(const frame of heroTrace)for(const p of frame.pitches){if(Number.isFinite(p)){min=Math.min(min,p);max=Math.max(max,p);}}
    const span=Math.max(max-min,1),center=(min+max)/2;min=center-span*.59;max=center+span*.59;
    const duration=heroTrace.at(-1).seconds||1,x=t=>pad.left+t/duration*dw,y=p=>pad.top+(max-p)/(max-min)*dh;
    const time=heroTraceClip?.id===selected?.id?currentTime():0;
    let low=0,high=heroTrace.length-1;
    while(low<high){const mid=Math.ceil((low+high)/2);if(heroTrace[mid].seconds<=time)low=mid;else high=mid-1;}
    const activeFrame=heroTrace[low];
    for(let particle=0;particle<heroTrace[0].pitches.length;particle++){
      c.strokeStyle=`rgba(${particle%3===0?'197,242,138':particle%3===1?'121,183,146':'164,211,163'},${particle%4===0?.72:.38})`;
      c.lineWidth=particle%4===0?1.15:.8;c.beginPath();
      heroTrace.forEach((frame,i)=>{const xx=x(frame.seconds),yy=y(frame.pitches[particle]);i===0?c.moveTo(xx,yy):c.lineTo(xx,yy);});c.stroke();
      const frame=time>.01?activeFrame:heroTrace[Math.min(heroTrace.length-1,Math.floor((.54+(particle%7)*.063)*(heroTrace.length-1)))];
      const xx=x(frame.seconds),yy=y(frame.pitches[particle]);c.fillStyle=particle%3===0?'#d1f79d':'#a3cfa4';
      c.beginPath();c.arc(xx,yy,particle%4===0?2.3:1.5,0,Math.PI*2);c.fill();
    }
    c.fillStyle='#b1c6ac';c.font='7px Helvetica, Arial, sans-serif';c.textAlign='left';c.fillText('0s',pad.left,h-15);c.textAlign='right';c.fillText(`${duration.toFixed(1)}s`,w-pad.right,h-15);
    if(playing&&heroTraceClip?.id===selected?.id){c.strokeStyle='#d1f79d38';c.setLineDash([2,4]);c.beginPath();c.moveTo(x(time),pad.top-8);c.lineTo(x(time),h-pad.bottom+8);c.stroke();c.setLineDash([]);}
  }

  function drawCausal() {
    const info=setupCanvas('causal-canvas');if(!info)return;
    const {context:c,width:w,height:h}=info;c.clearRect(0,0,w,h);
    const data=causalData.get(causalMode),pad={left:30,right:10,top:24,bottom:25},dw=w-pad.left-pad.right,dh=h-pad.top-pad.bottom;
    const center=pad.top+dh/2;c.strokeStyle='#d2d9c8';c.lineWidth=.7;
    for(let i=0;i<=4;i++){const yy=pad.top+i*dh/4;c.beginPath();c.moveTo(pad.left,yy);c.lineTo(w-pad.right,yy);c.stroke();}
    nodes['causal-chart-mode'].textContent=causalMode;
    if(!data?.frames?.length)return;
    let maximum=.005;
    causalData.forEach(pair=>pair.frames.forEach(frame=>frame.deltas.slice(1).forEach(value=>maximum=Math.max(maximum,Math.abs(value)))));
    maximum*=1.12;
    const duration=data.frames.at(-1).seconds||1,x=t=>pad.left+t/duration*dw,y=p=>center-p/maximum*dh/2;
    const impulse=x(data.impulseTime||1);
    c.setLineDash([3,4]);c.strokeStyle='#648a6c';c.beginPath();c.moveTo(impulse,pad.top);c.lineTo(impulse,h-pad.bottom);c.stroke();c.setLineDash([]);
    c.fillStyle='#587063';c.font='7px Helvetica, Arial, sans-serif';c.textAlign='left';c.fillText('ONE-VOICE IMPULSE',Math.min(impulse+5,w-92),12);
    for(let particle=1;particle<data.frames[0].deltas.length;particle++){
      const firstNeighbor=(data.directNeighborIds||[]).includes(particle);
      c.strokeStyle=firstNeighbor?'#a1854588':particle%4===0?'#146c6077':'#146c6038';c.lineWidth=firstNeighbor?1.1:.8;c.beginPath();
      data.frames.forEach((frame,i)=>{i===0?c.moveTo(x(frame.seconds),y(frame.deltas[particle])):c.lineTo(x(frame.seconds),y(frame.deltas[particle]));});c.stroke();
    }
    c.fillStyle='#6f8175';c.font='7px Helvetica, Arial, sans-serif';c.textAlign='left';c.fillText(`+${maximum.toFixed(2)}`,0,pad.top+3);c.fillText('0',10,center+3);c.fillText(`−${maximum.toFixed(2)}`,0,h-pad.bottom+3);c.fillText('st',3,h-4);c.fillText('0s',pad.left,h-4);c.textAlign='right';c.fillText(`${duration.toFixed(1)}s`,w-pad.right,h-4);
    const count=data.affectedAgentsExcludingDirect;
    const indirect=Array.isArray(data.indirectResponders)?data.indirectResponders.length:null;
    const structuralThreshold=Number(data.structuralThresholdSemitones||1e-10);
    nodes['causal-chart-caption'].textContent=`${Number.isFinite(count)?`${count} of ${data.frames[0].deltas.length-1} voices reached a ${data.thresholdSemitones||.001}-semitone difference. `:''}${indirect?`${indirect} indirect routes detected numerically at ${structuralThreshold.toExponential(0)} semitones; this diagnostic does not establish audibility. `:''}Disturbed voice excluded. Same scale for both modes.`;
  }

  function makeClipButton(clip, className='study-clip') {
    const button=document.createElement('button');button.type='button';button.className=className;button.dataset.clipId=clip.id;button.setAttribute('aria-pressed','false');
    button.append(textEl('span','','▶'));
    const copy=document.createElement('span');copy.append(textEl('strong','',clip.label),textEl('small','',`${clip.noteLabel || `MIDI ${clip.note}`} · velocity ${Number(clip.velocity).toFixed(2)} · ${Number(clip.duration).toFixed(1)}s`));button.append(copy);
    button.addEventListener('click',()=>select(clip,true));return button;
  }

  function renderComparisons() {
    nodes['comparison-options'].replaceChildren();
    const comparisons=sectionClips('comparison');
    const descriptions={FLOCK:['The flock','Neighbors share the movement.'],FIXED:['Fixed detuning','Each voice keeps its offset.'],INDEPENDENT:['Independent','Each voice wanders on its own.']};
    ['FLOCK','FIXED','INDEPENDENT'].forEach((mode,index)=>{
      const clip=comparisons.find(c=>c.mode===mode)||(mode==='FLOCK'?defaultClip('RIBBON'):null);if(!clip)return;
      const button=document.createElement('button');button.type='button';button.className='comparison-option';button.dataset.clipId=clip.id;button.setAttribute('aria-pressed','false');
      const label=textEl('span','compare-label',`${'ABC'[index]} / ${mode==='INDEPENDENT'?'WANDER':mode}`);label.append(textEl('span','','→'));
      button.append(label,textEl('strong','',descriptions[mode][0]),textEl('small','',descriptions[mode][1]));button.addEventListener('click',()=>select(clip,true));nodes['comparison-options'].append(button);
    });
  }

  function renderCausal() {
    nodes['causal-options'].replaceChildren();
    (manifest.causalPairs||[]).forEach(pair=>{
      const wrapper=textEl('div','causal-pair','');wrapper.append(textEl('span','causal-pair-label',`${modeNames[pair.mode]||pair.mode} motion`));
      const buttons=textEl('div','causal-buttons','');buttons.setAttribute('role','group');buttons.setAttribute('aria-label',`${pair.mode} impulse test`);
      [['off','Original'],['on','One voice nudged']].forEach(([key,label])=>{const clip=byId.get(pair[key]);if(!clip)return;const button=textEl('button','causal-button',label);button.type='button';button.dataset.clipId=clip.id;button.setAttribute('aria-pressed','false');button.append(textEl('span','tiny-play','▶'));button.addEventListener('click',()=>select(clip,true));buttons.append(button);});
      wrapper.append(buttons);nodes['causal-options'].append(wrapper);
      readJson(pair.traceSrc).then(data=>{causalData.set(pair.mode,data);drawCausal();}).catch(()=>{nodes['causal-chart-caption'].textContent='The measured response plot could not load. You can still listen to the paired renders.';});
    });
  }

  function renderStudies() {
    nodes['study-list'].replaceChildren();
    const names={register:'Across the keyboard',velocity:'Soft, medium, hard',sweeps:'Inside the six controls'};
    ['register','velocity','sweeps'].forEach(id=>{
      const clips=sectionClips(id);if(!clips.length)return;
      const section=getSection(id),details=textEl('details','study',''),summary=document.createElement('summary');
      summary.append(textEl('span','study-name',names[id]));
      const right=textEl('span','study-summary-right','');right.append(textEl('span','',`${clips.length} renders`),textEl('span','study-arrow','+'));summary.append(right);details.append(summary);
      details.append(textEl('p','study-description',section?.description || 'Select a rendered sound to listen.'));
      const grid=textEl('div','study-clips','');
      let priorMacro='';
      clips.forEach(clip=>{
        if(id==='sweeps'){
          const macro=clip.sweepParam||clip.control||clip.label?.match(/\b(SPREAD|PULL|FLOW|SCATTER|EDGE|HOLD)\b/i)?.[1]?.toUpperCase();
          if(macro&&macro!==priorMacro){grid.append(textEl('p','sweep-group',macro));priorMacro=macro;}
        }
        grid.append(makeClipButton(clip));
      });
      details.append(grid);nodes['study-list'].append(details);
    });
  }

  function renderFlowRevision() {
    const revision = manifest.flowRevision;
    const pairs = revision?.pairs?.filter(pair => byId.has(pair.before) && byId.has(pair.after)) || [];
    $('flow-revision').hidden = !pairs.length;
    $('flow-notice').hidden = !pairs.length;
    $('flow-steps').replaceChildren();
    if (!pairs.length) return;
    const paces = ['Slowest', 'Slow', 'Medium', 'Near original', 'Fastest'];
    $('flow-description').textContent = 'FLOW now changes the speed of the collective motion. Compare the revised mapping with the previous version at the same setting.';
    pairs.forEach((pair, index) => {
      const card = textEl('div', 'flow-step', '');
      card.append(textEl('span', 'flow-value', `FLOW ${Number(pair.value).toFixed(2)}`), textEl('h3', '', pair.pace || revision.labels?.[index] || paces[index] || 'Motion speed'), textEl('span', 'flow-rate', `${rateLabel(pair.rate)} motion`));
      const controls = textEl('div', 'flow-pair-buttons', '');
      [['after', 'Revised'], ['before', 'Previous']].forEach(([key, label]) => {
        const clip = byId.get(pair[key]);
        const button = textEl('button', `flow-pair-button flow-${key}`, label);
        button.type = 'button';
        button.dataset.clipId = clip.id;
        button.setAttribute('aria-pressed', 'false');
        button.setAttribute('aria-label', `Play ${label.toLowerCase()} FLOW ${Number(pair.value).toFixed(2)}${key === 'after' ? `, ${rateLabel(pair.rate)} motion speed` : ''}`);
        const icon = textEl('span', '', '');
        icon.setAttribute('aria-hidden', 'true');
        button.append(icon);
        button.addEventListener('click', () => select(clip, true));
        controls.append(button);
      });
      card.append(controls);
      $('flow-steps').append(card);
    });
    const anchor = Number(revision.anchorFlow);
    $('flow-footnote').textContent = `${Number.isFinite(anchor) ? `The original Ribbon speed is anchored at FLOW ${anchor.toFixed(2)} (1×). ` : ''}The other sounds remain from the original audition. Rates describe motion speed, not note pitch.`;
  }

  async function loadManifest() {
    nodes['library-status'].hidden=false;nodes['library-error'].hidden=true;
    try {
      const data=await readJson('manifest.json');if(!Array.isArray(data.clips)||!data.clips.length)throw new Error('The audition manifest contains no clips.');
      manifest=data;byId.clear();data.clips.forEach(clip=>byId.set(clip.id,clip));
      renderComparisons();renderCausal();renderStudies();renderFlowRevision();
      document.querySelectorAll('[data-play-gesture]').forEach(button=>{const clip=defaultClip(button.dataset.playGesture);button.disabled=!clip;button.onclick=()=>select(clip,true);});
      document.querySelectorAll('[data-duration]').forEach(node=>{const clip=defaultClip(node.dataset.duration);node.textContent=clip?`${Number(clip.duration).toFixed(1)} sec`:'Unavailable';});
      const first=defaultClip('GATHER')||data.clips[0];nodes['first-listen'].disabled=false;nodes['first-duration'].textContent=`${Number(first.duration).toFixed(1)}s`;
      nodes['first-listen'].onclick=()=>select(first,true);
      const pack=data.download?.path||'flock-audition.zip';['pack-link','closing-pack-link'].forEach(id=>$(id).href=pack);
      if(data.download?.rawPath)$('raw-pack-link').href=data.download.rawPath;else $('raw-pack-link').hidden=true;
      const audio=data.audio||{},sourceInfo=data.source||{};
      $('render-notes').textContent=`${audio.channels===1?'Mono':'PCM'} ${Number(audio.sampleRate||44100).toLocaleString()} Hz WAV renders from the SnipSnap Kotlin engine. ${audio.normalization||'Matched playback uses fixed gain per clip.'} Causal pairs share one gain so the impulse remains a fair comparison. No effects are added by this page. Gesture drawings are illustrative; the large trajectory view and response plot use recorded data.`;
      $('source-notes').textContent=data.flowRevision
        ? `This pack contains two model versions: the original audition uses model ${data.flowRevision.baselineModel || 1}; only the five revised FLOW renders use model ${data.flowRevision.model || 2}. The five previous FLOW renders are retained for comparison. Each clip and recipe records its source. ${data.flowRevision.baselineSource?.revision ? `Original source: ${data.flowRevision.baselineSource.revision.slice(0,12)}. ` : ''}${sourceInfo.revision ? `Revised FLOW source: ${sourceInfo.revision.slice(0,12)}. ` : ''}The listening packs include recipes and traces. The revised mapping awaits listening review.`
        : `Seeded recipes reproduce the same finite performance. ${sourceInfo.revision?`Source revision: ${sourceInfo.revision.slice(0,12)}${sourceInfo.dirty?' (working tree changes included in the recorded source hashes)':''}. `:''}The listening packs include recipes and traces. This is an audition of the concept; presets await listening review.`;
      nodes['level-explanation'].textContent=`Matched playback compares ${audio.matchedMetric||'short-window RMS'} with a peak ceiling. Each render gets one fixed gain; causal pairs share a gain. Raw preserves the engine's output level.`;
      nodes['library-status'].hidden=true;select(first,false);
    } catch(error) {
      nodes['library-status'].hidden=true;nodes['library-error-message'].textContent=error.message;nodes['library-error'].hidden=false;
      nodes['trace-loading'].textContent='Recorded trajectories will appear when the library loads.';
    }
  }

  nodes['play-button'].addEventListener('click',()=>playing?pause():play());
  nodes['stop-button'].addEventListener('click',stop);
  nodes.seek.addEventListener('input',()=>{
    const position=Number(nodes.seek.value);const wasPlaying=playing;
    if(wasPlaying){offset=position;play(position);}else{offset=position;updateProgress();drawHero();}
  });
  nodes['retry-load'].addEventListener('click',loadManifest);
  document.querySelectorAll('[data-level]').forEach(button=>button.addEventListener('click',()=>setLevel(button.dataset.level)));
  document.addEventListener('keydown',event=>{
    if(event.code!=='Space'||event.altKey||event.ctrlKey||event.metaKey||/INPUT|TEXTAREA|BUTTON|A|SUMMARY|SELECT/.test(event.target.tagName))return;
    if(!selected)return;event.preventDefault();playing?pause():play();
  });
  let resizeTimer;
  window.addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{drawHero();drawCausal();drawWave();},100);});
  function animate(){if(playing){updateProgress();const now=currentTime();if(now-lastDrawnTime>.065){drawHero();lastDrawnTime=now;}}else lastDrawnTime=-1;requestAnimationFrame(animate);}
  drawHero();drawCausal();drawWave();loadManifest();requestAnimationFrame(animate);
})();
