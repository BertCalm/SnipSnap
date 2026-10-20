package com.snipsnap.synth

/** Dependency-free listening UI; embedded evidence also works when opened as a local file. */
internal object OrigamiAuditionPage {
    fun render(manifest: String, embeddedAudio: String = "{}") = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <meta name="color-scheme" content="dark">
          <title>ORIGAMI · First listen</title>
          <style>
            :root{--bg:#101614;--panel:#18211d;--line:#33463a;--text:#e1e9dc;--muted:#a4b29f;--lime:#d5e886;--mint:#91c6aa;--warm:#e9bc85}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.55 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
            button,input,select,textarea{font:inherit}button,select{cursor:pointer}button,a,input,select,textarea{touch-action:manipulation}
            button,select{border:1px solid var(--line);border-radius:5px;background:#202d25;color:var(--text);padding:9px 13px}
            button:hover,button:focus-visible{background:#314131;border-color:var(--mint)}button[aria-pressed=true],button.active{background:var(--lime);color:#152010;border-color:var(--lime)}
            :focus-visible{outline:2px solid var(--lime);outline-offset:3px}button:disabled{opacity:.5;cursor:default}a{color:var(--lime);text-underline-offset:3px}
            .wrap{max-width:1160px;padding:36px 28px 72px;margin:auto}.eyebrow{font:12px/1.4 ui-monospace,SFMono-Regular,Consolas,monospace;text-transform:uppercase;letter-spacing:.15em;color:var(--mint)}
            header{display:flex;justify-content:space-between;align-items:start;gap:24px;margin-bottom:24px}h1{font-size:clamp(36px,7vw,66px);line-height:1;margin:12px 0 16px;letter-spacing:-.045em}h2,h3,p{margin:0}h2{font-size:21px;letter-spacing:-.02em}h3{font-size:16px}
            header p{max-width:740px;color:var(--muted)}.mark{width:102px;height:102px;flex:none;opacity:.9}.line{stroke:var(--mint);fill:none;stroke-width:1.5}.panel{border:1px solid var(--line);border-radius:10px;background:var(--panel);padding:22px;margin:20px 0}
            .row{display:flex;align-items:center;flex-wrap:wrap;gap:10px}.between{justify-content:space-between}.muted,.small{color:var(--muted)}.small{font-size:12px}.accent{color:var(--lime)}
            .player-title{font-size:24px;margin:7px 0 5px;line-height:1.2}.description{color:var(--muted);max-width:830px}.playrow{margin-top:17px}.primary{background:var(--lime);color:#152010;border-color:var(--lime);font-weight:650;min-width:114px}.status{font:12px ui-monospace,SFMono-Regular,Consolas,monospace;color:var(--mint);margin-left:auto}
            progress{width:100%;height:5px;accent-color:var(--lime);display:block;margin:15px 0 17px}.compare{border-top:1px solid var(--line);padding-top:17px;display:grid;grid-template-columns:1fr 1fr;gap:14px}.compare label{display:block;color:var(--muted);font-size:12px;margin-bottom:5px}.compare .row{flex-wrap:nowrap}.compare select{min-width:0;flex:1;width:100%}
            .toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap;margin:30px 0 16px}.tabs{display:flex;gap:6px;flex-wrap:wrap}.view-label{color:var(--muted);font-size:13px;margin-right:6px}.viewintro{margin:4px 0 20px}.viewintro p{color:var(--muted);margin-top:5px;max-width:800px}
            #groups{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:15px}.group{background:var(--panel);border:1px solid var(--line);border-radius:9px;padding:17px}.group.featured{border-top:2px solid var(--lime)}.group-header{margin-bottom:13px}.group-header .small{margin-top:4px}.clip{display:flex;align-items:center;justify-content:space-between;text-align:left;width:100%;padding:12px;margin:7px 0;background:#1c2821;border-radius:5px;gap:10px}.clip.active{background:#344b31;color:var(--text);border-color:var(--lime)}.clip .clip-arrow{color:var(--lime);font-size:16px}.clip.active .clip-arrow{color:var(--lime)}.clip-meta{font:11px ui-monospace,SFMono-Regular,Consolas,monospace;color:var(--muted);margin-top:4px}.clip.active .clip-meta{color:#c9d6c1}.wide{grid-column:span 3}.sweep-list{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:7px}.sweep-list .clip{display:block;margin:0;text-align:center;padding:15px 8px}.sweep-list .clip-arrow{display:block;margin-bottom:5px}
            .feedback{display:grid;grid-template-columns:1fr 1fr;gap:24px}.feedback p{color:var(--muted);margin:6px 0 12px}.verdicts{gap:7px}.verdicts button{padding:8px 10px;font-size:13px}.feedback label{display:block;margin-bottom:7px;color:var(--muted);font-size:13px}textarea{width:100%;min-height:105px;resize:vertical;background:#111b15;border:1px solid var(--line);border-radius:5px;color:var(--text);padding:11px}
            details{border-top:1px solid var(--line);margin-top:18px;padding-top:13px}summary{cursor:pointer;color:var(--muted);font-size:13px}pre{white-space:pre-wrap;word-break:break-word;font:11px/1.55 ui-monospace,SFMono-Regular,Consolas,monospace;color:var(--muted)}.footer{display:flex;justify-content:space-between;gap:20px;flex-wrap:wrap;margin-top:23px;color:var(--muted);font-size:12px}#native{width:100%;margin:15px 0 0}#native[hidden]{display:none}.storage{font-size:12px;color:var(--warm);margin-top:7px}.volume{font-size:12px;color:var(--muted);gap:8px}.volume input{width:86px;accent-color:var(--lime)}.hint{font-size:12px;color:var(--muted);margin-top:12px}
            @media(max-width:860px){#groups{grid-template-columns:repeat(2,minmax(0,1fr))}.wide{grid-column:span 2}.mark{width:72px;height:72px}.status{margin-left:0}}
            @media(max-width:560px){.wrap{padding:24px 16px 48px}.panel{padding:17px}header{gap:10px}.mark{width:48px;height:48px}#groups,.feedback,.compare{grid-template-columns:1fr}.wide{grid-column:span 1}.sweep-list{grid-template-columns:repeat(5,minmax(0,1fr));gap:4px}.sweep-list .clip{font-size:12px;padding:12px 3px}.sweep-list .clip-meta{font-size:10px}.toolbar{gap:11px}.tabs button{font-size:13px;padding:8px 11px}.compare .row{gap:7px}.playrow .status{width:100%}}
          </style>
        </head>
        <body>
        <main class="wrap">
          <header>
            <div><div class="eyebrow">SnipSnap / Spectral study 01</div><h1>ORIGAMI</h1><p id="intro">Fold a sound around its note. Three provisional synth voices, heard dry: a struck key, a moving lead and a spectral cloud. Begin with the source/fold pairs, then change the geometry.</p></div>
            <svg class="mark" viewBox="0 0 100 100" aria-hidden="true"><path class="line" d="M8 21 88 14 66 87 8 21 62 38 88 14M62 38 66 87 39 62 8 21M39 62 62 38"/></svg>
          </header>
          <section class="panel" aria-label="Listening player">
            <div class="row between"><span class="eyebrow" id="player-context">First pair</span><div class="tabs" aria-label="Playback level"><button data-mode="matched" aria-pressed="true">Matched</button><button data-mode="raw" aria-pressed="false">Raw</button></div></div>
            <h2 class="player-title" id="player-title"></h2><p class="description" id="player-description"></p>
            <div class="row playrow"><button class="primary" id="play">▶︎ Play</button><button id="stop">■ Stop</button><label class="row volume">Volume <input id="volume" type="range" min="0" max="1" step=".01" value=".65" aria-label="Playback volume"></label><span class="status" id="status" role="status" aria-live="polite">Audio off · press Play</span></div>
            <progress id="progress" value="0" max="1" aria-label="Playback progress"></progress>
            <div class="compare"><div><label id="compare-label-a" for="compare-a">A · same source</label><div class="row"><select id="compare-a"></select><button id="play-a" aria-label="Play comparison A">A ▶︎</button></div></div><div><label id="compare-label-b" for="compare-b">B · same source</label><div class="row"><select id="compare-b"></select><button id="play-b" aria-label="Play comparison B">B ▶︎</button></div></div></div>
            <audio id="native" controls preload="none" playsinline hidden></audio>
            <p class="hint" id="browser-hint" hidden>If audio is blocked here, <a id="open-browser" target="_blank" rel="noopener">open this page in your browser</a>.</p>
            <p class="hint" id="level-note"></p><p class="hint">Finite one-shots. Compare the note as well as the upper spectrum. Space plays/stops; A and B play the current comparison.</p>
          </section>
          <div class="toolbar"><div class="tabs" id="voice-tabs" aria-label="Filter by voice"></div><div><label class="view-label" for="view">Explore</label><select id="view"></select></div></div>
          <div class="viewintro"><h2 id="view-title"></h2><p id="view-description"></p></div>
          <section id="groups" aria-label="Listening clips"></section>
          <section class="panel feedback" aria-label="Listening notes">
            <div><div class="eyebrow">Your ear decides</div><h2 style="margin-top:8px">Notes on this clip</h2><p id="feedback-label"></p><div class="row verdicts"><button data-verdict="keep-exploring">Keep exploring</button><button data-verdict="needs-work">Needs work</button><button data-verdict="unsure">Unsure</button></div><p class="small" style="margin-top:12px">Listen for a distinct folded character, clear enough pitch and a useful range across the keyboard.</p></div>
            <div><label for="notes">What works, or what needs changing?</label><textarea id="notes" placeholder="e.g. The C4 fold has a useful metallic ring; C3 loses its note."></textarea><div class="row between" style="margin-top:10px"><span class="small" id="saved-status">Saved in this browser</span><button id="export">Export feedback</button></div><p class="storage" id="storage-status" hidden></p></div>
          </section>
          <details><summary>Diagnostics and rendering evidence</summary><p class="small" style="margin-top:10px">Measurements describe this render. Detected pitch is an autocorrelation estimate; it does not approve perceived pitch. Isolated layers use the combined reference's gain.</p><pre id="diagnostics"></pre></details>
          <div class="footer"><span id="pack-summary"></span><span><a id="download" download>Download selected WAV</a> · <a id="manifest-download" href="manifest.json" download>Manifest</a></span></div>
        </main>
        <script id="manifest" type="application/json">${safeJson(manifest)}</script>
        <script id="embedded-audio" type="application/json">${safeJson(embeddedAudio)}</script>
        <script>
        'use strict';
        const pack=JSON.parse(document.getElementById('manifest').textContent);
        const embedded=JSON.parse(document.getElementById('embedded-audio').textContent);
        const clips=pack.clips, byId=new Map(clips.map(c=>[c.id,c]));
        const lookup=id=>document.getElementById(id), native=lookup('native');
        const initialView=pack.initialCategory&&pack.categories.some(c=>c.id===pack.initialCategory)?pack.initialCategory:pack.categories[0].id;
        const requestedClip=byId.get(pack.initialClipId);
        let selected=requestedClip&&requestedClip.category===initialView?requestedClip:clips.find(c=>c.category===initialView&&(c.comparisonRole==='revised'||c.comparisonVersion==='R'+pack.modelVersion)&&c.featured)||clips.find(c=>c.category===initialView&&c.featured&&c.diagnostic==='COMBINED')||clips.find(c=>c.category===initialView)||clips[0];
        let selectedGroup=selected.group, voice='ALL', view=initialView, mode='matched';
        let compareA='',compareB='',playing=false,loading=false,token=0,currentDuration=0,downloadUrl=null,mediaUrl=null,cleanupMedia=null,mediaWatchdog=null;
        const memoryNotes=new Map();
        const storagePrefix='origami.feedback.'+pack.sourceSha256+'.'+pack.recipeSha256+'.';
        function element(tag,attrs,text){const node=document.createElement(tag);Object.entries(attrs||{}).forEach(([k,v])=>{if(k==='class')node.className=v;else node.setAttribute(k,v)});if(text!==undefined)node.textContent=text;return node}
        function groupClips(id){const list=clips.filter(c=>c.group===id);if(list.length&&list[0].category==='diagnostics'){const ref=byId.get(list[0].sharedMatchedReference);if(ref)list.push(ref)}return list}
        function stored(c){const key=storagePrefix+c.id;try{return JSON.parse(localStorage.getItem(key)||'null')||memoryNotes.get(key)||{}}catch(e){return memoryNotes.get(key)||{}}}
        function saveNotes(patch){const key=storagePrefix+selected.id;const value=Object.assign({},stored(selected),patch,{updatedAt:new Date().toISOString()});memoryNotes.set(key,value);try{localStorage.setItem(key,JSON.stringify(value));lookup('saved-status').textContent='Saved in this browser'}catch(e){lookup('storage-status').hidden=false;lookup('storage-status').textContent='Browser storage is unavailable. Export feedback to keep these notes.';lookup('saved-status').textContent='Kept for this session'}updateVerdicts(value)}
        function updateVerdicts(note){document.querySelectorAll('[data-verdict]').forEach(b=>b.setAttribute('aria-pressed',String(note.verdict===b.dataset.verdict)))}
        function feedback(){lookup('feedback-label').textContent=selected.voice+' · '+selected.note+' · '+selected.title;const value=stored(selected);lookup('notes').value=value.notes||'';updateVerdicts(value)}
        function pathFor(c){return mode==='raw'?c.rawPath:c.matchedPath}
        function stop(message){token++;if(cleanupMedia){cleanupMedia();cleanupMedia=null}clearTimeout(mediaWatchdog);mediaWatchdog=null;native.pause();native.removeAttribute('src');native.load();native.hidden=true;if(mediaUrl&&mediaUrl.startsWith('blob:'))URL.revokeObjectURL(mediaUrl);mediaUrl=null;playing=false;loading=false;currentDuration=0;lookup('play').textContent='▶︎ Play';lookup('progress').value=0;lookup('status').textContent=message||'Stopped'}
        function selectClip(id,group){stop();selected=byId.get(id);if(group&&group!==selectedGroup){selectedGroup=group;configureCompare()}else if(!group&&selected.group!==selectedGroup){selectedGroup=selected.group;configureCompare()}renderSelected();markSelected()}
        function configureCompare(){const list=groupClips(selectedGroup);compareA=list[0].id;compareB=list[list.length-1].id;const revision=list[0].category==='comparisons',sources=revision&&list[0].comparisonFocus==='unfolded-source',folds=revision&&list[0].comparisonFocus==='folded-voice';lookup('compare-label-a').textContent=sources?'A · previous source':folds?'A · previous fold':revision?'A · previous voice':'A · same source';lookup('compare-label-b').textContent=sources?'B · revised source':folds?'B · revised fold':revision?'B · revised voice':'B · same source';for(const side of ['a','b']){const select=lookup('compare-'+side);select.replaceChildren();for(const c of list){const title=selectedGroup.startsWith('layers_')&&c.diagnostic==='COMBINED'?'Combined reference':c.title;select.append(element('option',{value:c.id},title))}select.value=side==='a'?compareA:compareB}}
        function levelMessage(){if(selected.category==='comparisons')return mode==='raw'?'Raw: each revision retains its original export gain. Measurements show the level before export.':'Matched: previous and revised use the same loudness target. Previous WAVs are preserved unchanged.';return mode==='raw'?'Raw: one common export gain preserves level differences. Original pre-gain measurements are recorded.':selected.sharedMatchedReference?'Matched diagnostic: the combined reference gain preserves the original layer balance.':'Matched: gain-only loudness matching; no effects. All isolated layers retain their combined reference gain.'}
        function renderSelected(){lookup('player-context').textContent=selected.voice+' / '+selected.note+' / '+(groupClips(selectedGroup)[0]||selected).groupTitle;lookup('player-title').textContent=selected.title;lookup('player-description').textContent=selected.description;if(downloadUrl){URL.revokeObjectURL(downloadUrl);downloadUrl=null}const path=pathFor(selected);if(embedded[path])downloadUrl=URL.createObjectURL(new Blob([decodeBytes(path)],{type:'audio/wav'}));lookup('download').href=downloadUrl||path;lookup('download').download=selected.id+'_'+mode+'.wav';lookup('level-note').textContent=levelMessage();lookup('diagnostics').textContent=JSON.stringify({listeningRound:pack.listeningRound,sourceSha256:pack.sourceSha256,levelPolicy:pack.levelPolicy,sourcePaletteUnchanged:pack.sourcePaletteUnchanged,craneUnchanged:pack.craneUnchanged,clip:selected},null,2);feedback()}
        function markSelected(){document.querySelectorAll('.clip').forEach(b=>{b.classList.toggle('active',b.dataset.id===selected.id);b.setAttribute('aria-pressed',String(b.dataset.id===selected.id))})}
        function renderGroups(){const category=pack.categories.find(c=>c.id===view);lookup('view-title').textContent=category.title;lookup('view-description').textContent=category.description;const filtered=clips.filter(c=>c.category===view&&(voice==='ALL'||c.voice===voice));const ids=[...new Set(filtered.map(c=>c.group))];const root=lookup('groups');root.replaceChildren();for(const id of ids){const list=groupClips(id),first=list[0],sweep=view==='sweeps'||view==='shape';const section=element('article',{class:'group'+(first.featured?' featured':'')+(sweep?' wide':'')});const header=element('div',{class:'group-header'});header.append(element('h3',{},first.groupTitle));header.append(element('p',{class:'small'},first.note+' · '+(first.featured?'Start here · ':'')+(view==='diagnostics'?'Common reference gain':view==='comparisons'?'Same note · same controls · dry':'Same source · dry')));section.append(header);const stack=element('div',{class:sweep?'sweep-list':''});for(const c of list){const button=element('button',{class:'clip','data-id':c.id,'aria-pressed':String(c.id===selected.id)});const label=element('span',{});label.append(element('span',{},view==='diagnostics'&&c.diagnostic==='COMBINED'?'Combined reference':c.title));label.append(element('div',{class:'clip-meta'},c.durationSeconds.toFixed(1)+'s'+(c.sharedMatchedReference?' · shared gain':'')));const arrow=element('span',{class:'clip-arrow','aria-hidden':'true'},'▶︎');if(sweep){button.append(arrow,label)}else{button.append(label,arrow)}button.addEventListener('click',()=>{selectClip(c.id,id);playSelected()});stack.append(button)}section.append(stack);root.append(section)}if(ids.length===0)root.append(element('p',{class:'muted'},'No cases for this voice in this view. Choose another voice.'));markSelected()}
        function decodeBytes(path){const binary=atob(embedded[path]);const bytes=new Uint8Array(binary.length);for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);return bytes.buffer}
        function showBrowserHint(){lookup('browser-hint').hidden=false;const url=new URL(location.href);url.search='';url.hash='';lookup('open-browser').href=url.href}
        function playbackStatus(c){lookup('status').textContent='Playing · '+c.voice+' '+c.note+(native.muted||native.volume===0?' · muted':'')}
        function updateProgress(){if(!native.src)return;const duration=Number.isFinite(native.duration)&&native.duration>0?native.duration:currentDuration;lookup('progress').value=Math.min(1,native.currentTime/Math.max(.01,duration))}
        function nativePlay(c,path,myToken){
          const requestMode=mode,listeners=[],diagnosticController=new AbortController();let diagnosticTimer=null,diagnosisStarted=false;
          const current=()=>token===myToken&&native.dataset.clipId===c.id;
          const listen=(name,handler)=>{const wrapped=()=>{if(current())handler()};native.addEventListener(name,wrapped);listeners.push([name,wrapped])};
          cleanupMedia=()=>{listeners.forEach(([name,handler])=>native.removeEventListener(name,handler));diagnosticController.abort();clearTimeout(diagnosticTimer)};
          function fail(message){if(!current())return;clearTimeout(mediaWatchdog);loading=false;playing=false;lookup('play').textContent='▶︎ Play';lookup('status').textContent=message;native.hidden=false}
          function explainFailure(){
            if(diagnosisStarted||embedded[path]||location.protocol==='file:')return;diagnosisStarted=true;
            diagnosticTimer=setTimeout(()=>diagnosticController.abort(),5000);
            fetch(mediaUrl,{credentials:'same-origin',cache:'no-store',signal:diagnosticController.signal}).then(async response=>{
              if(!current())return;
              let message=null;
              if(response.status===401||response.status===403)message='Audio needs access. Reload this page and sign in, then try again.';
              else if(!response.ok)message='The audio file could not load. Reload this page, then try again.';
              else if(/text\/html|application\/json/i.test(response.headers.get('content-type')||''))message='The audio link returned a page instead of a sound. Reload this page and sign in, then try again.';
              if(response.body)await response.body.cancel();
              if(current()&&message)fail(message);
            }).catch(error=>{if(current()&&error.name!=='AbortError')fail('Audio could not load. Check your connection and try again.')}).finally(()=>clearTimeout(diagnosticTimer));
          }
          listen('loadedmetadata',()=>{if(Number.isFinite(native.duration)&&native.duration>0)currentDuration=native.duration;updateProgress()});
          listen('timeupdate',updateProgress);listen('seeked',updateProgress);
          listen('play',()=>{loading=true;lookup('play').textContent='■ Stop'});
          listen('playing',()=>{if(native.paused||native.readyState<3)return;clearTimeout(mediaWatchdog);loading=false;playing=true;lookup('play').textContent='■ Stop';playbackStatus(c);if(!native.muted&&native.volume>0)saveNotes({heard:true,lastHeardMode:requestMode})});
          listen('pause',()=>{if(!native.paused)return;loading=false;playing=false;lookup('play').textContent='▶︎ Play';updateProgress()});
          listen('ended',()=>{if(!native.ended)return;clearTimeout(mediaWatchdog);loading=false;playing=false;lookup('play').textContent='▶︎ Play';lookup('status').textContent='Finished · '+c.voice+' '+c.note;lookup('progress').value=1});
          listen('waiting',()=>{if(playing)lookup('status').textContent='Buffering · '+c.voice+' '+c.note});
          listen('volumechange',()=>{if(playing){playbackStatus(c);if(!native.muted&&native.volume>0)saveNotes({heard:true,lastHeardMode:requestMode})}});
          listen('error',()=>{if(!native.error)return;fail(native.error.code===2?'Audio could not load. Check your connection and try again.':'This audio link could not be played. Reload the page, then try again.');explainFailure()});
          mediaUrl=embedded[path]?URL.createObjectURL(new Blob([decodeBytes(path)],{type:'audio/wav'})):new URL(path,document.baseURI).href;
          native.src=mediaUrl;native.dataset.clipId=c.id;native.preload='auto';native.setAttribute('playsinline','');native.hidden=false;
          const volume=Number(lookup('volume').value);native.volume=volume;native.muted=volume===0;currentDuration=c.durationSeconds;loading=true;lookup('play').textContent='■ Stop';
          try{if(navigator.audioSession)navigator.audioSession.type='playback'}catch(error){}
          mediaWatchdog=setTimeout(()=>{if(current()&&!playing)fail('Audio is taking too long to load. Try Play again, or use the audio controls below.')},8000);
          // Keep play() in this click's call stack: mobile browsers may reject playback after an await.
          try{const promise=native.play();if(promise)promise.catch(error=>{if(!current())return;if(error.name==='NotAllowedError'){fail('Audio was blocked. Use the audio Play control below, or open this page in your browser.');showBrowserHint()}else if(error.name!=='AbortError'){fail('This audio link could not be played. Reload the page, then try again.');explainFailure()}})}catch(error){if(error.name==='NotAllowedError'){fail('Audio was blocked. Use the audio Play control below, or open this page in your browser.');showBrowserHint()}else fail('This audio link could not be played. Reload the page, then try again.')}
        }
        function playSelected(){stop();const c=selected,path=pathFor(c),myToken=token;lookup('status').textContent='Loading '+c.voice+' '+c.note+'…';try{nativePlay(c,path,myToken)}catch(error){loading=false;playing=false;lookup('play').textContent='▶︎ Play';lookup('status').textContent='This audio file could not be opened. Reload the page, then try again.'}}
        function animate(){if(playing)updateProgress();requestAnimationFrame(animate)}
        lookup('play').addEventListener('click',()=>playing||loading?stop():playSelected());lookup('stop').addEventListener('click',()=>stop());
        function playSide(side){selectClip(side==='a'?compareA:compareB,selectedGroup);playSelected()}
        lookup('play-a').addEventListener('click',()=>playSide('a'));lookup('play-b').addEventListener('click',()=>playSide('b'));
        lookup('compare-a').addEventListener('change',e=>{compareA=e.target.value});lookup('compare-b').addEventListener('change',e=>{compareB=e.target.value});
        lookup('volume').addEventListener('input',e=>{const value=Number(e.target.value);native.volume=value;native.muted=value===0;if(playing)playbackStatus(selected)});
        document.querySelectorAll('[data-mode]').forEach(b=>b.addEventListener('click',()=>{stop();mode=b.dataset.mode;document.querySelectorAll('[data-mode]').forEach(other=>other.setAttribute('aria-pressed',String(other.dataset.mode===mode)));renderSelected()}));
        for(const name of ['ALL','CRANE','FAN','STILL']){const b=element('button',{'data-voice':name,'aria-pressed':String(name==='ALL')},name==='ALL'?'All voices':name);b.addEventListener('click',()=>{stop();voice=name;document.querySelectorAll('[data-voice]').forEach(other=>other.setAttribute('aria-pressed',String(other.dataset.voice===voice)));renderGroups()});lookup('voice-tabs').append(b)}
        for(const category of pack.categories)lookup('view').append(element('option',{value:category.id},category.title));lookup('view').value=view;
        lookup('view').addEventListener('change',e=>{stop();view=e.target.value;renderGroups()});
        document.querySelectorAll('[data-verdict]').forEach(b=>b.addEventListener('click',()=>{const current=stored(selected).verdict;saveNotes({verdict:current===b.dataset.verdict?null:b.dataset.verdict})}));
        lookup('notes').addEventListener('input',e=>saveNotes({notes:e.target.value}));
        lookup('export').addEventListener('click',()=>{const feedback=clips.map(c=>({clip:c,note:stored(c)})).filter(row=>row.note.verdict||row.note.notes||row.note.heard).map(row=>({id:row.clip.id,voice:row.clip.voice,note:row.clip.note,midi:row.clip.midi,macros:row.clip.macros,velocity:row.clip.velocity,seed:row.clip.seed,diagnostic:row.clip.diagnostic,modelVersion:row.clip.modelVersion,sourceSha256:row.clip.sourceSha256||pack.sourceSha256,originListeningRound:row.clip.originListeningRound||pack.listeningRound,comparisonVersion:row.clip.comparisonVersion,comparisonOriginalId:row.clip.comparisonOriginalId,recipeSha256:row.clip.recipeSha256,inputSourceSha256:row.clip.inputSourceSha256,rawWaveformSha256:row.clip.rawWaveformSha256,rawFileSha256:row.clip.rawFileSha256,matchedFileSha256:row.clip.matchedFileSha256,feedback:row.note}));const payload={engine:'ORIGAMI',schemaVersion:1,listeningRound:pack.listeningRound,sourceSha256:pack.sourceSha256,recipeSha256:pack.recipeSha256,exportedAt:new Date().toISOString(),feedback};const url=URL.createObjectURL(new Blob([JSON.stringify(payload,null,2)],{type:'application/json'}));const a=element('a',{href:url,download:pack.listeningRound+'-feedback.json'});a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)});
        document.addEventListener('keydown',e=>{if(e.target.closest('input,textarea,select,button,audio')||e.ctrlKey||e.metaKey||e.altKey)return;if(e.key===' '){e.preventDefault();playing||loading?stop():playSelected()}else if(e.key.toLowerCase()==='a'){e.preventDefault();playSide('a')}else if(e.key.toLowerCase()==='b'){e.preventDefault();playSide('b')}});
        lookup('pack-summary').textContent=clips.length+' dry clips · 44.1 kHz mono · '+(pack.quick?'Smoke pack · ':'')+'Provisional recipes';
        if(Object.keys(embedded).length){lookup('manifest-download').href=URL.createObjectURL(new Blob([lookup('manifest').textContent],{type:'application/json'}));lookup('manifest-download').download='manifest.json'}
        if(window.self!==window.top)showBrowserHint();
        if(pack.modelVersion>=4)lookup('intro').textContent=initialView==='comparisons'?'Start with FAN’s revised fold and its fifth color, then hear STILL’s upper chord color. Compare previous and revised folds at the same notes and controls. Source + fold includes the unchanged starting sounds. CRANE keeps its current sound.':'Start with FAN’s revised fold and its fifth color, then compare the unchanged starting sound. STILL explores an upper chord color. Each voice has a current source/fold pair; CRANE keeps its current sound.';else if(pack.modelVersion===3)lookup('intro').textContent=initialView==='comparisons'?'Start with FAN’s revised unfolded source. Compare the previous and revised starting sounds at the same notes, then hear what folding adds in Source + fold. CRANE keeps its current sound.':'Start with FAN’s new unfolded sound, then hear what folding adds. Each voice has a current source/fold pair. CRANE keeps its current sound.';else if(initialView==='comparisons')lookup('intro').textContent='FAN and STILL have a softer upper spectrum. Compare the previous and revised voices at the same notes and controls, then explore the full dry matrix. CRANE retains its current sound.';
        configureCompare();renderSelected();renderGroups();animate();
        </script>
        </body>
        </html>
    """.trimIndent() + "\n"

    private fun safeJson(value: String) = value.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}
