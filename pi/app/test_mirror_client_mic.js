const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const client = fs.readFileSync('horizon/mirror-client.js', 'utf8');
assert.equal(client.includes('enumerateDevices'), false, 'Pi capture must not wait for browser device enumeration');
const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); await new Promise(resolve => setImmediate(resolve)); };
const attested = source => ({nodeName:'Jarvis Kitchen',audioInput:{available:true,source,label:'Default'}});
const absent = () => ({nodeName:'Jarvis Kitchen',audioInput:{available:false,source:'',label:''}});

function boot(runtime) {
  const calls = [], updates = [], timers = [], sockets = [], tracks = [], worklets = [], audios = [], audioNodes = [], contexts = [], listeners = {}, windowListeners = {};
  class Track {
    constructor() { this.readyState = 'live'; this.listeners = {}; }
    addEventListener(name, fn) { this.listeners[name] = fn; }
    stop() { this.readyState = 'ended'; }
  }
  class Stream { constructor(track) { this.track = track; } getTracks() { return [this.track]; } getAudioTracks() { return [this.track]; } }
  class AudioContext {
    constructor() { this.currentTime = 0; this.destination = {}; this.audioWorklet = {addModule:async () => {}}; contexts.push(this); }
    createMediaStreamSource() { return {connect() {}, disconnect() {}}; }
    createGain() { return {gain:{value:1}, connect() {}, disconnect() {}}; }
    createBuffer(_channels, length, rate) { return {duration:length / rate, getChannelData:() => new Float32Array(length)}; }
    createBufferSource() { const node = {connect() {}, start(at) { this.startAt = at; }, stop() { this.stopped = true; }}; audioNodes.push(node); return node; }
    close() { return Promise.resolve(); }
  }
  class Worklet { constructor() { this.port = {}; worklets.push(this); } connect() {} disconnect() {} }
  class Socket { constructor() { this.readyState = 1; this.sent = []; sockets.push(this); } send(value) { this.sent.push(value); } close() { this.readyState = 3; } }
  Socket.OPEN = 1;
  const context = {
    URLSearchParams, AbortController, location:{search:'?layout=adaptive',protocol:'http:',host:'test',pathname:'/'}, window:null,
    navigator:{mediaDevices:{getUserMedia:async options => { calls.push(options); const track = new Track(); tracks.push(track); return new Stream(track); },addEventListener:(name, fn) => { listeners[name] = fn; }}},
    WebSocket:Socket, AudioContext, AudioWorkletNode:Worklet, Audio:class { constructor() { audios.push(this); } play() { return Promise.resolve(); } pause() {} },
    fetch:async (_url, options) => { assert.equal(options.cache, 'no-store'); return {ok:true,json:async () => runtime.value}; },
    document:{querySelector:() => null}, atob:value => Buffer.from(value, 'base64').toString('binary'),
    setTimeout:(fn, delay) => { const token = {fn,delay}; timers.push(token); return token; }, clearTimeout:token => { const index = timers.indexOf(token); if (index >= 0) timers.splice(index, 1); }, console,
  };
  context.window = context; context.window.addEventListener = (name, fn) => { windowListeners[name] = fn; };
  context.window.JarvisHorizon = {update:value => updates.push(value),level:() => {}};
  vm.runInNewContext(client, context);
  return {
    calls, updates, tracks, worklets, audios, audioNodes, contexts, listeners, windowListeners, timers, setRuntime:value => { runtime.value = value; },
    async open() { sockets[0].onopen(); await flush(); },
    messages() { return sockets.at(-1).sent.filter(value => typeof value === 'string').map(value => JSON.parse(value)); },
    binaryCount() { return sockets.at(-1).sent.filter(value => typeof value !== 'string').length; },
    receive(value) { sockets.at(-1).onmessage({data:JSON.stringify(value)}); },
    sendPcm() { worklets[0].port.onmessage({data:{level:0.1,pcm:new ArrayBuffer(1280)}}); },
    closeSocket() { sockets.at(-1).readyState = 3; sockets.at(-1).onclose(); },
    async openRetry() { sockets.at(-1).onopen(); await flush(); },
    async retry() { const timer = timers.shift(); assert.ok(timer, 'one bounded retry is scheduled'); timer.fn(); await flush(); },
  };
}

(async () => {
  const unavailable = boot({value:absent()});
  await unavailable.open();
  assert.equal(unavailable.calls.length, 0, 'monitor-only/unattested runtime must not open browser capture');
  assert.equal(unavailable.messages().some(message => message.type === 'voice.start'), false);
  assert.ok(unavailable.updates.some(update => update.muted === true));
  assert.equal(unavailable.timers[0].delay, 2000);
  await unavailable.retry();
  assert.equal(unavailable.calls.length, 0, 'absent attestation must not retry browser capture');
  assert.equal(unavailable.timers[0].delay, 5000);
  unavailable.setRuntime(attested('alsa_input.usb-Insta360_Mic.analog-mono'));
  await unavailable.retry();
  assert.equal(unavailable.calls.length, 1, 'runtime attestation recovers without devicechange');
  assert.equal(unavailable.messages().filter(message => message.type === 'voice.start').length, 1);

  const unloading = boot({value:absent()});
  await unloading.open();
  assert.equal(unloading.timers.length, 1);
  unloading.windowListeners.beforeunload();
  assert.equal(unloading.timers.length, 0, 'beforeunload cancels pending recovery');

  const app = boot({value:attested('alsa_input.usb-Insta360_Mic.analog-mono')});
  await app.open();
  assert.equal(app.calls.length, 1);
  assert.equal(app.calls[0].video, false);
  assert.equal(app.calls[0].audio.channelCount, 1);
  assert.equal(app.calls[0].audio.echoCancellation, true);
  assert.equal(app.calls[0].audio.noiseSuppression, true);
  assert.equal(app.calls[0].audio.autoGainControl, true);
  assert.equal(app.calls[0].audio.deviceId, undefined, 'Default selection is delegated to the attested PipeWire source');
  assert.equal(app.messages().filter(message => message.type === 'voice.start').length, 1);

  // Ambient display state is browser-local: fact refreshes and a completed
  // caption do not postpone it forever.  Only live voice or a live tile wakes.
  app.receive({type:'snapshot',snapshot:{mode:'idle',night:false,caption:null,tile:null,weather:{temperature:'12°C'}}});
  let ambientDeadline = app.timers.find(timer => timer.delay >= 89900 && timer.delay <= 90000);
  assert.ok(ambientDeadline, 'idle snapshot arms the bounded 90-second night clock');
  app.timers.splice(app.timers.indexOf(ambientDeadline), 1);
  ambientDeadline.fn();
  assert.equal(app.updates.at(-1).night, true, 'the local display enters galactic night after inactivity');
  app.receive({type:'snapshot',snapshot:{mode:'idle',night:false,caption:null,tile:null,weather:{temperature:'13°C'},nextEvent:{time:'09:30',title:'Stand-up'}}});
  assert.equal(app.updates.at(-1).night, true, 'weather and calendar refreshes do not wake the ambient display');
  const beforeWake = app.updates.length;
  app.receive({type:'state',state:'listening'});
  assert.ok(app.updates.slice(beforeWake).some(update => update.night === false), 'wake/listening leaves the ambient display immediately');
  app.receive({type:'state',state:'idle'});
  app.receive({type:'snapshot',snapshot:{mode:'idle',night:false,caption:{speaker:'jarvis',text:'Fertig.'},tile:null}});
  app.receive({type:'snapshot',snapshot:{mode:'idle',night:false,caption:{speaker:'jarvis',text:'Fertig.'},tile:null,clock:{minute:1}}});
  ambientDeadline = app.timers.find(timer => timer.delay >= 89900 && timer.delay <= 90000);
  assert.ok(ambientDeadline, 'an unchanged completed caption arms one bounded idle clock');
  app.receive({type:'snapshot',snapshot:{mode:'idle',night:false,caption:null,tile:{id:'expired',type:'message',expiresAtMs:1,data:{text:'Alt'}}}});
  assert.ok(app.timers.some(timer => timer.delay >= 89900 && timer.delay <= 90000), 'an expired tile releases into the bounded ambient clock');
  app.receive({type:'snapshot',snapshot:{mode:'idle',night:false,caption:null,tile:{id:'announcement',type:'message',expiresAtMs:Date.now()+9000,data:{text:'Ansage'}}}});
  assert.ok(app.updates.some(update => update.night === false), 'a new announcement wakes the display immediately');
  const tileDeadline = app.timers.find(timer => timer.delay >= 9000 && timer.delay <= 9001);
  assert.ok(tileDeadline, 'a live tile schedules a local expiry recheck');

  const acknowledgementsBefore = app.audios.length;
  app.receive({type:'state',state:'listening'});
  assert.equal(app.audios.length, acknowledgementsBefore + 1, 'wake acknowledgement starts local playback');
  app.sendPcm();
  assert.equal(app.binaryCount(), 0, 'acknowledgement temporarily suppresses microphone PCM');
  const acknowledgementDeadline = app.timers.find(timer => timer.delay === 3000);
  assert.ok(acknowledgementDeadline, 'acknowledgement has a bounded playback deadline');
  acknowledgementDeadline.fn();
  app.sendPcm();
  assert.equal(app.binaryCount(), 1, 'a stalled acknowledgement releases microphone PCM after its deadline');
  assert.ok(app.updates.some(update => update.mode === 'error'), 'stalled output is visible to the kitchen user');

  app.closeSocket();
  await app.retry();
  await app.openRetry();
  app.sendPcm();
  assert.equal(app.binaryCount(), 1, 'a socket close clears the stale acknowledgement before reconnect PCM resumes');

  const audiblePcm = Buffer.alloc(480); for (let offset = 0; offset < audiblePcm.length; offset += 2) audiblePcm.writeInt16LE(1000, offset);
  const pcm = audiblePcm.toString('base64');
  app.contexts[0].currentTime = 10;
  app.receive({type:'audio',pcmBase64:pcm});
  const stalledOutput = app.audioNodes.at(-1);
  const outputDeadline = app.timers.find(timer => timer.delay >= 1500);
  assert.ok(outputDeadline, 'relay output has a bounded deadline');
  outputDeadline.fn();
  assert.equal(stalledOutput.onended, null, 'timeout detaches stale output completion callbacks');
  app.contexts[0].currentTime = 25;
  app.receive({type:'audio',pcmBase64:pcm});
  const freshOutput = app.audioNodes.at(-1);
  assert.equal(freshOutput.startAt, 25.025, 'next output starts from current audio time after a stalled response');

  const zeroPcm = Buffer.alloc(480).toString('base64');
  const oneSecondPcm = Buffer.alloc(48_000); for (let offset = 0; offset < oneSecondPcm.length; offset += 2) oneSecondPcm.writeInt16LE(1000, offset);
  app.receive({type:'audio',pcmBase64:oneSecondPcm.toString('base64')});
  const beforeInitialSilence = app.audioNodes.length;
  app.receive({type:'audio',pcmBase64:zeroPcm});
  assert.equal(app.audioNodes.length, beforeInitialSilence + 1, 'a short zero tail remains scheduled after audible output');
  const quietTimers = () => app.timers.filter(timer => timer.delay >= 1300 && timer.delay < 1500);
  let quietDeadline = quietTimers().at(-1);
  assert.ok(quietDeadline, 'quiet tail waits for the queued one-second audible playout before Android-parity release');
  app.receive({type:'audio',pcmBase64:zeroPcm});
  assert.equal(quietTimers().length, 1, 'continuous silence does not postpone an already scheduled quiet release');
  app.receive({type:'audio',pcmBase64:pcm});
  assert.equal(quietTimers().length, 0, 'new audible PCM cancels the pending quiet release');
  app.receive({type:'audio',pcmBase64:zeroPcm});
  quietDeadline = quietTimers().at(-1);
  assert.ok(quietDeadline, 'quiet release is re-armed only after the new audible packet');
  app.timers.splice(app.timers.indexOf(quietDeadline), 1); // mirror the browser removing a fired timeout
  quietDeadline.fn();
  app.sendPcm();
  assert.equal(app.binaryCount(), 2, 'continuous zero PCM cannot hold microphone capture after the quiet tail');
  assert.ok(app.updates.some(update => update.mode === 'listening'), 'wire speaking state is released by local audible playout ownership');
  const buffersAfterQuiet = app.audioNodes.length;
  app.receive({type:'audio',pcmBase64:zeroPcm});
  assert.equal(app.audioNodes.length, buffersAfterQuiet, 'initial/idle zero PCM allocates no audio buffers and cannot claim speaking');

  app.receive({type:'error',message:'Sprachverbindung ist zu langsam – bitte erneut versuchen'});
  assert.ok(app.updates.some(update => update.caption && update.caption.text === 'Sprachverbindung ist zu langsam – bitte erneut versuchen'), 'relay overload is readable on the kitchen display');

  const caption = () => app.updates.filter(update => update.caption && update.caption.text).at(-1).caption;
  app.receive({type:'state',state:'listening'});
  const transcriptAckDeadline = app.timers.find(timer => timer.delay === 3000);
  app.timers.splice(app.timers.indexOf(transcriptAckDeadline), 1);
  transcriptAckDeadline.fn();
  app.receive({type:'transcript',role:'user',text:' Was ist',final:false});
  app.receive({type:'transcript',role:'user',text:' sieben mal acht?',final:false});
  assert.equal(JSON.stringify(caption()), JSON.stringify({speaker:'user',text:' Was ist sieben mal acht?'}));
  app.receive({type:'transcript',role:'assistant',text:' Sieben',final:false});
  app.receive({type:'transcript',role:'user',text:'Was ist sieben mal acht?',final:true});
  assert.equal(JSON.stringify(caption()), JSON.stringify({speaker:'jarvis',text:' Sieben'}), 'late user final cannot replace streamed assistant text');
  app.receive({type:'state',state:'listening'});
  app.receive({type:'transcript',role:'assistant',text:' mal acht ist sechsundfünfzig.',final:false});
  assert.equal(JSON.stringify(caption()), JSON.stringify({speaker:'jarvis',text:' Sieben mal acht ist sechsundfünfzig.'}));
  app.receive({type:'transcript',role:'assistant',text:'Sieben mal acht ist sechsundfünfzig.',final:true});
  assert.equal(JSON.stringify(caption()), JSON.stringify({speaker:'jarvis',text:'Sieben mal acht ist sechsundfünfzig.'}), 'final transcript is authoritative rather than appended');
  app.receive({type:'transcript',role:'user',text:'Was ist sieben mal acht?',final:true});
  assert.equal(JSON.stringify(caption()), JSON.stringify({speaker:'jarvis',text:'Sieben mal acht ist sechsundfünfzig.'}), 'late duplicate user final cannot replace a completed assistant answer');
  app.receive({type:'audio',pcmBase64:pcm});
  app.receive({type:'snapshot',snapshot:{mode:'idle',caption:null}});
  assert.equal(JSON.stringify(app.updates.at(-1)), JSON.stringify({mode:'speaking',caption:{speaker:'jarvis',text:'Sieben mal acht ist sechsundfünfzig.'}}), 'an idle periodic snapshot cannot hide a caption while browser audio still renders');
  app.receive({type:'state',state:'listening'});
  app.receive({type:'snapshot',snapshot:{mode:'idle',caption:null}});
  assert.equal(JSON.stringify(app.updates.at(-1)), JSON.stringify({mode:'listening',caption:{speaker:'jarvis',text:'Sieben mal acht ist sechsundfünfzig.'}}), 'the active follow-up window keeps its local listening mode and caption through a periodic idle snapshot');
  app.receive({type:'state',state:'thinking'});
  app.receive({type:'snapshot',snapshot:{mode:'idle',caption:null}});
  assert.equal(JSON.stringify(app.updates.at(-1)), JSON.stringify({mode:'thinking',caption:{speaker:'jarvis',text:'Sieben mal acht ist sechsundfünfzig.'}}), 'the active conversation keeps its local thinking mode through a periodic idle snapshot');
  app.receive({type:'state',state:'idle'});
  app.receive({type:'snapshot',snapshot:{mode:'idle',caption:null}});
  assert.equal(JSON.stringify(app.updates.at(-1)), JSON.stringify({mode:'idle',caption:null}), 'an actual voice idle event releases local ownership for the existing caption fade behavior');
  app.receive({type:'transcript',role:'user',text:'Neue',final:false});
  app.receive({type:'transcript',role:'user',text:'Neue Frage?',final:true});
  assert.equal(JSON.stringify(caption()), JSON.stringify({speaker:'user',text:'Neue Frage?'}), 'same-role user turn starts fresh and final does not duplicate');
  app.receive({type:'transcript',role:'assistant',text:'Antwort.',final:true});
  app.receive({type:'transcript',role:'assistant',text:'Noch eine Antwort.',final:true});
  assert.equal(JSON.stringify(caption()), JSON.stringify({speaker:'jarvis',text:'Noch eine Antwort.'}), 'same-role completed assistant turn does not concatenate');

  app.tracks[0].readyState = 'ended'; app.tracks[0].listeners.ended();
  assert.ok(app.messages().some(message => message.type === 'voice.cancel'));
  assert.ok(app.updates.some(update => update.muted === true));

  app.setRuntime(attested('alsa_input.usb-Insta360_Mic.analog-mono'));
  await app.retry();
  assert.equal(app.messages().filter(message => message.type === 'voice.start').length, 2);

  app.receive({type:'transcript',role:'user',text:'Alt',final:false});
  app.closeSocket(); await app.retry(); await app.openRetry();
  app.receive({type:'snapshot',snapshot:{mode:'idle',caption:null}});
  assert.equal(JSON.stringify(app.updates.at(-1)), JSON.stringify({mode:'idle',caption:null}), 'websocket reconnect clears local caption and mode ownership');
  app.receive({type:'transcript',role:'user',text:'Neu',final:false});
  assert.equal(JSON.stringify(caption()), JSON.stringify({speaker:'user',text:'Neu'}), 'websocket reconnect clears stale transcript deltas');

  app.setRuntime(attested('alsa_input.usb-Replugged_Mic.analog-mono'));
  await app.listeners.devicechange();
  assert.ok(app.messages().some(message => message.type === 'voice.cancel'), 'source change tears down the stale default capture');
  console.log('mirror client gates Default capture on runtime attestation and recovers source changes');
})().catch(error => { console.error(error); process.exitCode = 1; });
