/* Local-only microphone, websocket, and sequential 24-kHz PCM playout. */
(function () {
  'use strict';
  if (!new URLSearchParams(location.search).has('layout')) {
    location.replace(location.pathname + '?layout=adaptive');
    return;
  }
  const INPUT_RATE = 16000, OUTPUT_RATE = 24000, RECONNECT_MS = 1500, RECOVERY_DELAYS = [2000,5000,10000], ACK_TIMEOUT_MS = 3000, MAX_PLAYBACK_MS = 30000, AUDIBLE_PCM_PEAK = 327, AUDIBLE_SILENCE_MS = 300, NIGHT_IDLE_MS = 90000;
  let socket, audio, stream, source, mic, micSink, acknowledgementCue, activeAudioSource, nextStart = 0, speaking = false, retry = 0, acknowledged = false, micStarting = false, micUnavailable = false, micRetryTimer, recoveryAttempt = 0, recoveryGeneration = 0, playbackEpoch = 0;
  const playing = new Set();
  const speakingSources = new Set(), playbackTimers = new Map();
  let quietOutputTimer, lastAudibleScheduledEnd = 0, transcript = {user:'', userFinal:false, assistant:'', assistantFinal:false}, voiceCaption, voiceMode, ambientTimer, ambientTileTimer, ambientNight = false, lastInteractionAt = Date.now(), ambientSnapshot = {mode:'idle', caption:null, tile:null}, ambientBlockerKey = '';
  const horizon = () => window.JarvisHorizon;
  const emit = value => socket && socket.readyState === WebSocket.OPEN && socket.send(JSON.stringify(value));
  const update = value => horizon() && horizon().update(value);
  const level = value => horizon() && horizon().level(value);
  const noMicProjection = () => ({mode:'idle', muted:true, caption:{speaker:'jarvis',text:'Mikrofon nicht verfügbar – Display bleibt aktiv'}});
  const updateVoiceState = value => update(micUnavailable ? Object.assign({}, value, noMicProjection()) : value);
  const captionKey = snapshot => { const caption = (snapshot || {}).caption || {}; return String(caption.speaker || '') + '\u0000' + String(caption.text || '').trim(); };
  function activeTile(snapshot) {
    const tile = (snapshot || {}).tile || {}, data = tile.data || {}, type = String(tile.type || '').toLowerCase(), deadline = Number(tile.expiresAtMs);
    if (deadline > Date.now()) return {key:String(tile.id || type) + ':' + deadline, deadline};
    const phase = String((data.call || {}).phase || data.phase || '').toLowerCase();
    if (type === 'camera' && ['ringing','connecting','connected'].includes(phase)) return {key:String(tile.id || type) + ':' + phase};
    if (type === 'terminal' && String((snapshot || {}).mode || '').toLowerCase() === 'working') return {key:String(tile.id || type) + ':working'};
    return null;
  }
  function activeMode(snapshot) { return ['listening','thinking','speaking','working','call','alert','confirm','notify'].includes(String((snapshot || {}).mode || 'idle').toLowerCase()); }
  function ambientBlocker(snapshot) {
    const tile = activeTile(snapshot);
    if (activeMode(snapshot)) return {key:'mode:' + String(snapshot.mode).toLowerCase(), deadline:tile && tile.deadline};
    return tile;
  }
  function clearAmbientTimers() { clearTimeout(ambientTimer); ambientTimer = undefined; clearTimeout(ambientTileTimer); ambientTileTimer = undefined; }
  function wakeAmbient(recordInteraction) {
    if (recordInteraction) lastInteractionAt = Date.now();
    clearAmbientTimers();
    if (!ambientNight) return;
    ambientNight = false;
    updateVoiceState(Object.assign({}, ambientSnapshot, {night:false}));
  }
  function armAmbient() {
    clearAmbientTimers();
    const blocker = ambientBlocker(ambientSnapshot);
    if (blocker) {
      if (blocker.deadline) ambientTileTimer = setTimeout(armAmbient, Math.max(0, blocker.deadline - Date.now()) + 1);
      return;
    }
    ambientBlockerKey = '';
    if (ambientNight) return;
    const remaining = NIGHT_IDLE_MS - (Date.now() - lastInteractionAt);
    if (remaining <= 0) {
      ambientNight = true;
      updateVoiceState(Object.assign({}, ambientSnapshot, {night:true}));
      return;
    }
    ambientTimer = setTimeout(() => {
      ambientTimer = undefined;
      if (ambientBlocker(ambientSnapshot)) { armAmbient(); return; }
      ambientNight = true;
      updateVoiceState(Object.assign({}, ambientSnapshot, {night:true}));
    }, remaining);
  }
  function acceptAmbientSnapshot(snapshot) {
    const priorCaption = captionKey(ambientSnapshot), caption = captionKey(snapshot), blocker = ambientBlocker(snapshot), key = blocker && blocker.key;
    ambientSnapshot = snapshot;
    // A completed caption is an event once, not a permanent night-mode blocker.
    if (caption && caption !== priorCaption) wakeAmbient(true);
    if (key && key !== ambientBlockerKey) wakeAmbient(true);
    ambientBlockerKey = key || '';
    armAmbient();
  }
  const audioConstraints = () => ({channelCount:1,echoCancellation:true,noiseSuppression:true,autoGainControl:true});
  function wsUrl() { return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws'; }
  function base64ToPcm16(value) {
    const binary = atob(value || ''), out = new Int16Array(binary.length >> 1);
    for (let i = 0; i < out.length; i++) out[i] = binary.charCodeAt(i * 2) | (binary.charCodeAt(i * 2 + 1) << 8);
    return out;
  }
  function finishSpeaking(source, drained) {
    clearTimeout(playbackTimers.get(source)); playbackTimers.delete(source); speakingSources.delete(source);
    speaking = speakingSources.size > 0;
    if (!speaking) level({src:'jarvis',v:0});
    if (drained) emit({type:'voice.output.drained'});
  }
  function timeoutSpeaking(source) {
    if (!speakingSources.has(source)) return;
    if (source === 'output') {
      // Invalidate every scheduled callback before stopping: a late onended
      // from a timed-out source must not drain a later response.
      clearTimeout(quietOutputTimer); quietOutputTimer = undefined;
      playbackEpoch += 1;
      playing.forEach(node => { node.onended = null; try { node.stop(); } catch (_) {} }); playing.clear();
      nextStart = audio ? audio.currentTime : 0;
    }
    if (source === 'ack' && acknowledgementCue) { acknowledgementCue.onended = acknowledgementCue.onerror = null; try { acknowledgementCue.pause(); } catch (_) {} acknowledgementCue = undefined; }
    finishSpeaking(source, source === 'output');
    wakeAmbient(); updateVoiceState({mode:'error',caption:{speaker:'jarvis',text:'Audioausgabe reagiert nicht – Mikrofon ist wieder bereit'}});
  }
  function beginSpeaking(source, timeout) {
    speakingSources.add(source); speaking = true;
    clearTimeout(playbackTimers.get(source)); playbackTimers.set(source, setTimeout(() => timeoutSpeaking(source), timeout));
  }
  function finishQuietOutput() {
    quietOutputTimer = undefined;
    if (!speakingSources.has('output')) return;
    playbackEpoch += 1;
    playing.forEach(node => { node.onended = null; try { node.stop(); } catch (_) {} }); playing.clear();
    nextStart = audio ? audio.currentTime : 0;
    lastAudibleScheduledEnd = nextStart;
    finishSpeaking('output', true);
    voiceMode = 'listening';
    wakeAmbient(); updateVoiceState({mode:'listening'});
  }
  function output(chunk) {
    const pcm = base64ToPcm16(chunk);
    if (!pcm.length || !audio || micUnavailable) return;
    if (pcm.length > OUTPUT_RATE * 30) { updateVoiceState({mode:'error',caption:{speaker:'jarvis',text:'Audioantwort ist zu lang – bitte erneut versuchen'}}); emit({type:'voice.output.drained',cancelled:true}); return; }
    let energy = 0, peak = 0;
    for (let i = 0; i < pcm.length; i++) { const v = pcm[i] / 32768; energy += v * v; peak = Math.max(peak, Math.abs(pcm[i])); }
    // Android considers PCM above 0.01 audible and releases after a 300-ms
    // rendered quiet tail.  GPT Live may keep an otherwise open relay sending
    // zeros long after its final transcript, so initial silence must not claim
    // the microphone and a quiet tail must eventually re-arm it.
    const audible = peak > AUDIBLE_PCM_PEAK;
    if (!audible && !speakingSources.has('output')) return;
    if (audible) { clearTimeout(quietOutputTimer); quietOutputTimer = undefined; }
    else if (!quietOutputTimer) quietOutputTimer = setTimeout(finishQuietOutput, Math.max(0, lastAudibleScheduledEnd - audio.currentTime) * 1000 + AUDIBLE_SILENCE_MS);
    const buffer = audio.createBuffer(1, pcm.length, OUTPUT_RATE), channel = buffer.getChannelData(0);
    for (let i = 0; i < pcm.length; i++) channel[i] = pcm[i] / 32768;
    const outputAudio = audio, epoch = playbackEpoch;
    const node = outputAudio.createBufferSource(); node.buffer = buffer; node.connect(outputAudio.destination); playing.add(node);
    const start = Math.max(outputAudio.currentTime + 0.025, nextStart); nextStart = start + buffer.duration;
    node.onended = () => { if (epoch !== playbackEpoch || audio !== outputAudio) return; playing.delete(node); if (!quietOutputTimer && outputAudio.currentTime + .04 >= nextStart) finishSpeaking('output', true); };
    if (audible) { wakeAmbient(); lastAudibleScheduledEnd = nextStart; beginSpeaking('output', Math.min(MAX_PLAYBACK_MS, Math.max(1500, (nextStart - outputAudio.currentTime) * 1000 + 1500))); voiceMode = 'speaking'; updateVoiceState({mode:'speaking'}); }
    level({src:'jarvis',v:Math.min(1, Math.sqrt(energy / pcm.length) * 3.2)}); try { node.start(start); } catch (_) { timeoutSpeaking('output'); }
  }
  function cancelOutput(silent) {
    playbackEpoch += 1;
    clearTimeout(quietOutputTimer); quietOutputTimer = undefined;
    playing.forEach(node => { try { node.stop(); } catch (_) {} }); playing.clear();
    if (acknowledgementCue) { acknowledgementCue.onended = acknowledgementCue.onerror = null; try { acknowledgementCue.pause(); } catch (_) {} acknowledgementCue = undefined; }
    playbackTimers.forEach(timer => clearTimeout(timer)); playbackTimers.clear(); speakingSources.clear();
    nextStart = audio ? audio.currentTime : 0; speaking = false; level({src:'jarvis',v:0});
    lastAudibleScheduledEnd = nextStart;
    if (!silent) emit({type:'voice.output.drained', cancelled:true});
  }
  function wakeAcknowledgement() {
    if (acknowledged) return; acknowledged = true;
    // A compact, neutral two-tone WAV keeps public builds self-contained. It
    // deliberately replaces the private recorded acknowledgement used in the
    // household deployment; an operator may provide a different local cue.
    const cue = acknowledgementCue = new Audio('data:audio/wav;base64,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'); cue.preload = 'auto'; beginSpeaking('ack', ACK_TIMEOUT_MS);
    cue.onended = cue.onerror = () => { if (acknowledgementCue !== cue) return; acknowledgementCue = undefined; finishSpeaking('ack', false); };
    cue.play().catch(() => { if (acknowledgementCue === cue) { acknowledgementCue = undefined; finishSpeaking('ack', false); } });
  }
  function stopMic(closeAudio) {
    const oldStream = stream, oldSource = source, oldMic = mic, oldSink = micSink, oldAudio = audio;
    cancelOutput(true);
    stream = source = mic = micSink = audio = activeAudioSource = undefined;
    if (oldMic) { try { oldMic.port.onmessage = null; oldMic.disconnect(); } catch (_) {} }
    if (oldSource) { try { oldSource.disconnect(); } catch (_) {} }
    if (oldSink) { try { oldSink.disconnect(); } catch (_) {} }
    if (oldStream) oldStream.getTracks().forEach(track => { try { track.stop(); } catch (_) {} });
    if (closeAudio && oldAudio) oldAudio.close().catch(() => {});
  }
  function loseMic() {
    micUnavailable = true; acknowledged = false;
    stopMic(true); emit({type:'voice.cancel'}); update(noMicProjection()); scheduleMicRetry();
  }
  function stopMicRecovery() {
    recoveryGeneration += 1;
    clearTimeout(micRetryTimer); micRetryTimer = undefined;
  }
  function scheduleMicRetry() {
    if (micRetryTimer || micStarting || !micUnavailable || !socket || socket.readyState !== WebSocket.OPEN) return;
    const delay = RECOVERY_DELAYS[Math.min(recoveryAttempt++, RECOVERY_DELAYS.length - 1)], generation = recoveryGeneration;
    micRetryTimer = setTimeout(async () => {
      micRetryTimer = undefined;
      if (generation !== recoveryGeneration || !micUnavailable || !socket || socket.readyState !== WebSocket.OPEN) return;
      await startMic(true);
      if (generation === recoveryGeneration && micUnavailable) scheduleMicRetry();
    }, delay);
  }
  async function readRuntime() {
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 3000);
    try {
      const response = await fetch('/runtime.json', {cache:'no-store',signal:controller.signal});
      return response.ok ? await response.json() : null;
    } catch (_) { return null; } finally { clearTimeout(timeout); }
  }
  async function acquirePhysicalAudioStream() {
    const runtime = await readRuntime(), input = runtime && runtime.audioInput;
    // The Pi owns source identity. Browser enumeration can hang while PipeWire
    // re-enumerates USB cameras, and its "Default" label is not an attestation.
    if (!input || input.available !== true || typeof input.source !== 'string' || !input.source) throw new Error('No attested physical audio input is available');
    activeAudioSource = input.source;
    return navigator.mediaDevices.getUserMedia({audio:audioConstraints(),video:false});
  }
  async function startMic(fromDeviceChange) {
    // The stream survives a local WS reconnect; re-announce this socket as the
    // sole voice owner even when no new getUserMedia call is necessary.
    if (mic) { emit({type:'voice.start', format:'pcm_s16le', sampleRate:INPUT_RATE, channels:1}); return; }
    if (micStarting || (micUnavailable && !fromDeviceChange)) return;
    micStarting = true;
    let failedAudio;
    try {
      stream = await acquirePhysicalAudioStream();
      const newStream = stream;
      audio = new (window.AudioContext || window.webkitAudioContext)();
      await audio.audioWorklet.addModule('mic-worklet.js');
      const track = stream.getAudioTracks()[0];
      if (!track || track.readyState !== 'live') throw new Error('Physical audio input stopped before capture started');
      track.addEventListener('ended', () => {
        if (stream !== newStream) return;
        loseMic();
      }, {once:true});
      source = audio.createMediaStreamSource(stream); mic = new AudioWorkletNode(audio, 'jarvis-mic');
      mic.port.onmessage = event => {
        const data = event.data; level({src:'user',v:data.level});
        if (!speaking && socket && socket.readyState === WebSocket.OPEN) socket.send(data.pcm);
      };
      micSink = audio.createGain(); micSink.gain.value = 0; source.connect(mic); mic.connect(micSink); micSink.connect(audio.destination);
      emit({type:'voice.start', format:'pcm_s16le', sampleRate:INPUT_RATE, channels:1});
      micUnavailable = false;
      recoveryAttempt = 0;
      updateVoiceState({mode:'idle', muted:false, caption:{text:''}});
    } catch (_) {
      failedAudio = audio;
      stopMic(false);
      stream = source = mic = micSink = audio = undefined;
      micUnavailable = true;
      // This is a display-only fallback, never a false wake-ready state.
      update(noMicProjection());
    } finally {
      micStarting = false;
      if (failedAudio) failedAudio.close().catch(() => {});
      if (micUnavailable) scheduleMicRetry();
    }
  }
  function resetTranscripts() { transcript = {user:'', userFinal:false, assistant:'', assistantFinal:false}; voiceCaption = undefined; }
  function projectCaption(caption) { voiceCaption = caption; if (caption && String(caption.text || '').trim()) wakeAmbient(); updateVoiceState({caption}); }
  function projectTranscript(message) {
    if (micUnavailable || (message.role !== 'user' && message.role !== 'assistant') || typeof message.text !== 'string') return;
    const role = message.role, final = message.final === true, text = message.text;
    if (role === 'user') {
      // A new user delta begins a new conversation only after the previous
      // assistant has finished.  A late user final during streamed assistant
      // speech is retained but cannot overwrite the visible answer.
      if (transcript.assistantFinal) {
        if (final && text === transcript.user) return;
        resetTranscripts();
      } else if (transcript.userFinal && !(final && text === transcript.user)) {
        resetTranscripts();
      }
      transcript.user = final ? text : (transcript.userFinal ? text : transcript.user + text);
      transcript.userFinal = final;
      if (transcript.assistant && !transcript.assistantFinal) return;
      projectCaption({speaker:'user',text:transcript.user});
      return;
    }
    if (transcript.assistantFinal && !(final && text === transcript.assistant)) {
      transcript.assistant = ''; transcript.assistantFinal = false;
    }
    transcript.assistant = final ? text : (transcript.assistantFinal ? text : transcript.assistant + text);
    transcript.assistantFinal = final;
    projectCaption({speaker:'jarvis',text:transcript.assistant});
  }
  function receive(message) {
    if (message.type === 'snapshot') {
      const snapshot = Object.assign({}, message.snapshot || {});
      // Voice events are not part of the server's periodic snapshot.  Keep
      // their active projection until an explicit voice terminal state arrives.
      if (acknowledged && voiceMode && snapshot.mode === 'idle') snapshot.mode = voiceMode;
      if (acknowledged && voiceCaption && snapshot.caption === null) snapshot.caption = voiceCaption;
      acceptAmbientSnapshot(snapshot);
      return updateVoiceState(ambientNight ? Object.assign({}, snapshot, {night:true}) : snapshot);
    }
    if (message.type === 'state') {
      if (message.state !== 'idle') wakeAmbient(true);
      if (message.state !== 'speaking') { voiceMode = message.state; updateVoiceState({mode:message.state}); }
      if (!micUnavailable && message.state === 'listening') { if (!acknowledged) resetTranscripts(); wakeAcknowledgement(); }
      if (message.state === 'idle' || message.state === 'error') { acknowledged = false; voiceMode = undefined; resetTranscripts(); if (message.state === 'idle') armAmbient(); }
      if (typeof message.level === 'number') level({src:message.src === 'jarvis' ? 'jarvis' : 'user',v:message.level}); return;
    }
    if (message.type === 'audio') { output(message.pcmBase64); return; }
    if (message.type === 'audio.end') { if (micUnavailable || !audio) return; nextStart = Math.max(nextStart, audio.currentTime); if (!speaking) emit({type:'voice.output.drained'}); return; }
    if (message.type === 'transcript') { projectTranscript(message); return; }
    if (message.type === 'error') { wakeAmbient(true); cancelOutput(); emit({type:'voice.cancel'}); updateVoiceState({mode:'error',caption:{speaker:'jarvis',text:message.message || 'Sprachdienstfehler – bitte erneut versuchen'}}); }
  }
  function connect() {
    socket = new WebSocket(wsUrl()); socket.binaryType = 'arraybuffer';
    socket.onopen = () => { retry = 0; emit({type:'hello', client:'jarvis-kitchen', audio:{inputHz:INPUT_RATE,outputHz:OUTPUT_RATE,encoding:'pcm_s16le'}}); startMic(true); };
    socket.onmessage = event => { if (typeof event.data !== 'string') return; try { receive(JSON.parse(event.data)); } catch (_) {} };
    socket.onclose = () => { cancelOutput(true); resetTranscripts(); acknowledged = false; voiceMode = undefined; stopMicRecovery(); update({offline:true}); clearTimeout(retry); retry = setTimeout(connect, RECONNECT_MS); };
    socket.onerror = () => socket.close();
  }
  window.addEventListener('beforeunload', () => { clearAmbientTimers(); stopMicRecovery(); emit({type:'voice.cancel'}); stopMic(true); });
  if (navigator.mediaDevices && navigator.mediaDevices.addEventListener) {
    navigator.mediaDevices.addEventListener('devicechange', async () => {
      if (micStarting) return;
      const activeTrack = stream && stream.getAudioTracks()[0], runtime = await readRuntime(), input = runtime && runtime.audioInput;
      const activeStillPhysical = !!(mic && activeTrack && activeTrack.readyState === 'live' && input && input.available === true && input.source === activeAudioSource);
      if (!activeStillPhysical) { if (micUnavailable) scheduleMicRetry(); else loseMic(); }
    });
  }
  readRuntime().then(runtime => { const node = document.querySelector('.node'); if (node && runtime && runtime.nodeName) node.lastChild.textContent = String(runtime.nodeName); });
  connect();
}());
