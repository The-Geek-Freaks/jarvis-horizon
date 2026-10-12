/* Static website only. Authored sample events; no Gateway, media or microphone. */
'use strict';
(() => {
  const horizon=window.JarvisHorizon;
  if(!horizon)return;
  const scenes=['home','conversation','weather','activity','music','agenda','house','ambient'];
  let jobs=[], levelTimer=null, activeScene="home";
  function later(fn,ms){jobs.push(setTimeout(fn,ms));}
  function stop(){jobs.forEach(clearTimeout);jobs=[];clearInterval(levelTimer);levelTimer=null;}
  function levels(){let phase=0;levelTimer=setInterval(()=>{phase+=.3;horizon.level({src:'jarvis',v:.22+.5*Math.abs(Math.sin(phase)*Math.cos(phase*.6))});},100);}
  function show(scene){
    if(!scenes.includes(scene))return;
    activeScene=scene;
    stop();
    horizon.update({mode:'idle',night:false,muted:false,offline:false,clock:{time:'14:25',date:'Montag, 12. Oktober',seconds:25},weather:{temperature:'21,6°',summary:'Sonnig',location:'Demo-Zuhause'},nextEvent:{time:'15:00',title:'Zeit für ein neues Projekt',meta:'Beispieltermin · in 35 Minuten',progress:.62},services:[],media:null,tile:null,caption:null});
    horizon.level({src:'user',v:0});horizon.level({src:'jarvis',v:0});
    function tile(type,data){horizon.update({tile:{id:'demo-'+scene,type,sticky:true,data}});}
    if(scene==='weather')tile('weather',{temperature:'21,6°',summary:'Sonnig',feelsLike:'22°',high:'24°',low:'12°',wind:'8 km/h',humidity:'45 %',rain:'5 %',uv:'3',location:'Demo-Zuhause',source:'BEISPIELDATEN',forecast:[{time:'14:00',temperature:21.6},{time:'15:00',temperature:23},{time:'16:00',temperature:24},{time:'17:00',temperature:23},{time:'18:00',temperature:21},{time:'19:00',temperature:19}]});
    if(scene==='agenda')tile('tasks',{source:'BEISPIELKALENDER',items:[{time:'15:00',title:'Zeit für ein neues Projekt',meta:'Ideen sammeln'},{time:'17:30',title:'Eine Runde draußen',meta:'Pause machen'},{time:'19:00',title:'Abendessen',meta:'Gemeinsame Zeit'}]});
    if(scene==='house')tile('home',{source:'BEISPIELDATEN',rooms:[{id:'studio',name:'Studio',detail:'Licht an · 65 %',level:.65},{id:'kitchen',name:'Küche',detail:'Licht an · 40 %',level:.4},{id:'hall',name:'Flur',detail:'Licht aus',level:0},{id:'garden',name:'Garten',detail:'Abendszene bereit',level:.2}]});
    if(scene==='music'){horizon.update({media:{playing:true,title:'Orbit Drift',artist:'Demo Session'}});tile('music',{title:'Orbit Drift',artist:'Demo Session',source:'SIMULIERTE WIEDERGABE',progress:42});}
    if(scene==='ambient')horizon.update({night:true});
    if(scene==='conversation'){
      horizon.update({mode:'listening',caption:{speaker:'user',text:'Jarvis, was steht heute noch an?'}});
      later(()=>horizon.update({mode:'thinking'}),1800);
      later(()=>{horizon.update({mode:'speaking',caption:{speaker:'jarvis',text:'Um 15 Uhr ist Zeit für dein neues Projekt. Danach hast du eine Pause eingeplant.'}});levels();},3300);
      later(()=>{clearInterval(levelTimer);horizon.update({mode:'idle',caption:null});horizon.level({src:'jarvis',v:0});},10500);
    }
    if(scene==='activity'){
      horizon.event({t:'exec',id:'demo-activity',title:'Arbeitsplatz vorbereiten',steps:['Prüfen','Abrufen','Ergebnis']});
      horizon.event({t:'line',id:'demo-activity',kind:'cmd',text:'Gerätestatus abfragen · Beispiel'});
      later(()=>horizon.event({t:'line',id:'demo-activity',kind:'ok',text:'Demo-Studio ist erreichbar'}),900);
      later(()=>horizon.event({t:'line',id:'demo-activity',kind:'cmd',text:'Szene laden · Beispiel'}),2000);
      later(()=>{horizon.event({t:'line',id:'demo-activity',kind:'ok',text:'Beispiel abgeschlossen · keine Geräte geschaltet'});horizon.update({mode:'success',tile:{id:'demo-activity',type:'terminal',sticky:true,data:{title:'Arbeitsplatz bereit',status:'completed',exit:'ERLEDIGT',progress:1,lines:[{kind:'cmd',text:'Gerätestatus abfragen · Beispiel'},{kind:'ok',text:'Demo-Studio ist erreichbar'},{kind:'cmd',text:'Szene laden · Beispiel'},{kind:'ok',text:'Beispiel abgeschlossen · keine Geräte geschaltet'}]}}});},3300);
    }
  }
  window.addEventListener('message',event=>{
    if(event.source!==parent||event.origin!==location.origin||event.data?.type!=='horizon-demo-scene')return;
    show(event.data.scene);
  });
  window.addEventListener('pagehide',()=>{stop();horizon.destroy();});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();else show(activeScene);});
  const initial=new URLSearchParams(location.search).get('scene');
  show(scenes.includes(initial)?initial:'home');
  parent.postMessage({type:'horizon-demo-ready'},location.origin);
})();
