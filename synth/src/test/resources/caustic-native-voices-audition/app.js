(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const manifest = window.CAUSTIC_NATIVE_VOICES_MANIFEST;
  const audio = $('audio');
  const state = {mode:'matched', midi:60, voice:'', section:'controls', active:null, request:0, handoff:null};
  const clips = manifest?.clips || [];
  const voices = manifest?.voices || [];
  const byId = new Map(clips.map(clip => [clip.id,clip]));
  const family = id => voices.find(voice => voice.id === id);
  const address = relative => new URL(relative,document.baseURI).href;
  const path = clip => state.mode === 'matched' ? clip.matched : clip.raw;
  const note = midi => ['C','C♯','D','D♯','E','F','F♯','G','G♯','A','A♯','B'][midi%12]+(Math.floor(midi/12)-1);
  const fmt = value => Number(value).toFixed(2).replace(/\.?0+$/,'');
  const el = (tag,cls,text) => {const e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=text;return e;};
  function link(element,target) {element.hidden=!target;if(target){element.href=address(target);element.download=target.split('/').pop();}}
  function reflect() {
    const clip=state.active;
    if(clip){
      $('player-name').textContent=clip.title;
      $('player-detail').textContent=`${family(clip.voice)?.title || clip.voice} · ${note(clip.midi)} · Velocity ${fmt(clip.velocity)} · ${fmt(clip.seconds)} sec`;
      $('player-macros').textContent=['SOURCE','FOCUS','BODY','LENGTH'].map(name=>`${name} ${fmt(clip.macros[name])}`).join(' · ');
      $('player-status').textContent=audio.ended?'RECORDING ENDED':audio.paused?'READY TO PLAY':'PLAYING NATIVE RECORDING';
      link($('current-recipe'),clip.recipe);link($('current-wav'),path(clip));
    }
    document.querySelectorAll('[data-clip-id]').forEach(card=>{
      const item=byId.get(card.dataset.clipId);const current=item?.id===clip?.id;const playing=current&&!audio.paused;
      card.classList.toggle('is-current',current);card.classList.toggle('is-playing',playing);
      const button=card.querySelector('.card-play');button.querySelector('.play-label').textContent=playing?'Pause this sound':'Play this sound';
      button.setAttribute('aria-label',`${playing?'Pause':'Play'} this sound: ${item.title}`);
      link(card.querySelector('.wav-link'),path(item));
    });
  }
  async function select(clip,play=true) {
    const request=++state.request;state.handoff?.abort();state.handoff=null;$('playback-error').hidden=true;
    if(state.active?.id===clip.id&&play&&!audio.paused){audio.pause();reflect();return;}
    audio.pause();state.active=clip;audio.src=address(path(clip));audio.load();reflect();
    if(play){try{await audio.play();}catch(error){if(request!==state.request)return;showError(`The recording could not start: ${error.message}. Press Play to retry, or download the WAV.`);}if(request===state.request)reflect();}
  }
  function showError(message){$('playback-error').textContent=message;$('playback-error').hidden=false;}
  function ready(test,events,signal) {
    if(signal.aborted)return Promise.reject(new DOMException('Selection changed','AbortError'));
    if(test())return Promise.resolve();
    return new Promise((resolve,reject)=>{
      let timer;const clear=()=>{clearTimeout(timer);events.forEach(event=>audio.removeEventListener(event,check));audio.removeEventListener('error',fail);signal.removeEventListener('abort',abort);};
      const check=()=>{if(test()){clear();resolve();}};const fail=()=>{clear();reject(new Error('The selected WAV could not load.'));};const abort=()=>{clear();reject(new DOMException('Selection changed','AbortError'));};
      events.forEach(event=>audio.addEventListener(event,check));audio.addEventListener('error',fail);signal.addEventListener('abort',abort,{once:true});timer=setTimeout(()=>{clear();reject(new Error('The WAV did not become ready in time.'));},8000);
    });
  }
  async function level(mode) {
    if(state.mode===mode)return;const request=++state.request;const playing=state.handoff?.playing??!audio.paused;const offset=(state.handoff?.offset??audio.currentTime)||0;
    state.handoff?.abort();const handoff=new AbortController();handoff.playing=playing;handoff.offset=offset;state.handoff=handoff;state.mode=mode;$('playback-error').hidden=true;
    $('matched-mode').setAttribute('aria-pressed',String(mode==='matched'));$('native-mode').setAttribute('aria-pressed',String(mode==='native'));
    $('level-note').textContent=mode==='matched'?'Level matched reduces loudness differences for comparing tone. Each copy uses AuditionLevel once.':'Renderer level is exactly CausticPatch.render() output, including its native house finish. No extra audition gain is applied.';
    if(state.active){audio.pause();audio.preload='auto';audio.src=address(path(state.active));audio.load();try{
      await ready(()=>audio.readyState>=3&&Number.isFinite(audio.duration),['canplay','loadeddata','progress'],handoff.signal);if(request!==state.request)return;
      const target=Math.min(offset,Math.max(0,audio.duration-.02));if(target>.01&&Math.abs(audio.currentTime-target)>.02){audio.currentTime=target;await ready(()=>!audio.seeking&&audio.readyState>=2&&Math.abs(audio.currentTime-target)<.08,['seeked','canplay'],handoff.signal);}
      if(request!==state.request)return;if(playing)await audio.play();
    }catch(error){if(request===state.request&&error.name!=='AbortError')showError(`${error.message} Press Play to retry, or download the WAV.`);}finally{if(state.handoff===handoff){state.handoff=null;audio.preload='none';}}}reflect();
  }
  function card(clip,quick=false) {
    const voice=family(clip.voice);const root=el('article','sound-card');root.dataset.clipId=clip.id;root.id=`${quick?'voice':'clip'}-${clip.id}`;
    const marker=el('div','card-marker');marker.append(el('span','card-index',quick?'NATIVE VOICE':voice?.title || clip.voice),el('span','',`${note(clip.midi)} / ${fmt(clip.seconds)} SEC`));root.append(marker,el('h3','',quick?(voice?.title || clip.title):clip.title));
    root.append(el('p','card-description',quick?(voice?.description || clip.description):clip.description));
    const play=el('button','card-play');play.type='button';play.setAttribute('aria-label',`Play this sound: ${clip.title}`);play.append(el('span','play-label','Play this sound'));const icon=el('span','play-icon');icon.setAttribute('aria-hidden','true');play.append(icon);play.addEventListener('click',()=>select(clip));root.append(play);
    const macros=el('div','macro-values');for(const name of ['SOURCE','FOCUS','BODY','LENGTH']){const entry=el('span','',name);entry.append(el('strong','',fmt(clip.macros[name])));macros.append(entry);}root.append(macros);
    const reference=byId.get(clip.comparison);if(reference&&!quick){const compare=el('button','reference-button',`Compare ${reference.title}`);compare.type='button';compare.setAttribute('aria-label',`Compare ${reference.title}: play named reference`);compare.addEventListener('click',()=>select(reference));root.append(compare);}
    const bottom=el('div','card-bottom');const patch=el('a','','Download native patch');link(patch,clip.recipe);const wav=el('a','wav-link','WAV');link(wav,path(clip));bottom.append(patch,wav);root.append(bottom);return root;
  }
  function renderVoices(){const grid=$('sound-cards');grid.replaceChildren();for(const voice of voices){const clip=clips.find(item=>item.voice===voice.id&&item.section==='voices'&&item.midi===state.midi);if(clip)grid.append(card(clip,true));}reflect();}
  function renderControls(){const selected=clips.filter(clip=>(state.voice==='all'||clip.voice===state.voice)&&(state.section==='all'||clip.section===state.section));$('control-cards').replaceChildren(...selected.map(clip=>card(clip)));$('recording-count').textContent=`${selected.length} of ${clips.length} recordings`;if(!selected.length)$('control-cards').append(el('p','loading','This portable pack contains voice defaults. Choose Registers or All.'));reflect();}
  if(!clips.length||!voices.length){$('sound-cards').replaceChildren(el('p','loading','The native-voices manifest is missing. Keep manifest.js beside this page.'));return;}
  audio.volume=.65;
  for(const button of document.querySelectorAll('[data-midi]')){button.disabled=!clips.some(clip=>clip.section==='voices'&&clip.midi===Number(button.dataset.midi));button.addEventListener('click',()=>{state.midi=Number(button.dataset.midi);document.querySelectorAll('[data-midi]').forEach(item=>item.setAttribute('aria-pressed',String(item===button)));renderVoices();});}
  const filter=$('voice-filter');const all=el('option','','All voices');all.value='all';filter.append(all);for(const voice of voices){const option=el('option','',voice.title);option.value=voice.id;filter.append(option);}state.voice=voices[0].id;filter.value=state.voice;
  if(!clips.some(clip=>clip.section==='controls'))state.section='voices';
  document.querySelectorAll('[data-section]').forEach(button=>{button.disabled=button.dataset.section!=='all'&&!clips.some(clip=>clip.section===button.dataset.section);button.setAttribute('aria-pressed',String(button.dataset.section===state.section));button.addEventListener('click',()=>{state.section=button.dataset.section;document.querySelectorAll('[data-section]').forEach(item=>item.setAttribute('aria-pressed',String(item===button)));renderControls();});});
  filter.addEventListener('change',()=>{state.voice=filter.value;renderControls();});$('matched-mode').addEventListener('click',()=>level('matched'));$('native-mode').addEventListener('click',()=>level('native'));
  for(const event of ['play','pause','ended'])audio.addEventListener(event,reflect);audio.addEventListener('error',()=>{if(audio.error)showError('This WAV could not load. Press Play to retry, or reopen the complete listening folder.');});
  const downloads=manifest.downloads||{};const target=$('downloads');if(downloads.comparison){const a=el('a','','Download twelve-default comparison ↓');link(a,downloads.comparison);target.append(a);}if(downloads.kit){const a=el('a','','Download four-voice 16-pad MPC kit ↓');link(a,downloads.kit);target.append(a);}for(const item of downloads.voices||[]){const a=el('a','',`Download ${item.title} family ↓`);link(a,item.path);target.append(a);}target.hidden=!target.children.length;
  document.querySelectorAll('[data-history]').forEach(a=>{const destination=manifest.history?.[a.dataset.history];a.hidden=!destination||location.protocol==='file:'||!!manifest.summary?.portableSubset;if(destination)a.href=address(destination);});
  const evidence=$('provenance-content');const summary=manifest.summary||{};const revision=manifest.sourceRevision||{};
  evidence.append(el('p','',`${clips.length} fixed Kotlin recordings · ${voices.length} native voices · Model ${manifest.model} · ${summary.sampleRate||44100} Hz · Mono · PCM ${summary.bitDepth||24} bit.`));
  if(Number.isFinite(summary.maximumNativePeak))evidence.append(el('p','',`Measured renderer peak ${Number(summary.maximumNativePeak).toFixed(4)} · Sample headroom ${Number(summary.worstSampleHeadroomDb).toFixed(2)} dB. Measurements do not establish audible contrast.`));
  if(manifest.generatedAt)evidence.append(el('p','',`Generated ${manifest.generatedAt}.`));evidence.append(el('p','',`Source ${String(revision.commit||'').slice(0,12)} · Fingerprint ${revision.sourceFingerprintSha256||'listed in manifest.json'}.`));
  if(summary.warnings?.length)evidence.append(el('p','',`Probe notes: ${summary.warnings.join(' / ')}`));
  renderVoices();renderControls();select(clips.find(clip=>clip.id===voices[0].defaultClip)||clips[0],false);
})();
