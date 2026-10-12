/* JARVIS Core Orb v2 — WebGL1 / GLES2. Ziel: Echo Show 5 Gen2 (Mali-T720), Android WebView.
   const orb = JarvisOrb.create(canvas, { quality:'auto'|'lite'|'balanced'|'high', state:'boot', onQuality(q){} })
   orb.setState(name)      boot idle listening thinking speaking working confirm success error alert notify muted sleep
   orb.setLevel(0..1)      RMS der aktuell aktiven Stimme. listening = DEINE Stimme (Wellen nach innen),
                           speaking = JARVIS (Kern glüht, Wellen nach außen). Bis 60 Hz, kein Re-Render.
   orb.pulse()             Weckwort / Ereignis-Impuls      orb.flash()  Erfolgs-Schockwelle (grün)
   orb.setProgress(0..1)   Fortschrittsring im Zustand working
   orb.setQuality(q) · orb.destroy() · orb.quality · orb.state
   2 Programme, 7 statische Buffer, 0 Allokationen pro Frame, ~14 Draw-Calls. */
(function () {
  'use strict';
  var COL = { gold: [.965, .784, .47], hot: [1, .58, .22], cyan: [.02, .84, 1], pink: [1, .17, .43], green: [0, 1, .5], grey: [.5, .5, .54], white: [1, .94, .84], amber: [1, .71, .15], blue: [.38, .72, 1] };
  // Pi HDMI profile: retain native panel pixels even when particle count adapts.
  // At desktop scale 2, a 300-CSS-pixel orb needs a 600-pixel drawing buffer.
  var Q = { lite: { n: 220, dpr: 2, size: 2.2, back: 0 }, balanced: { n: 380, dpr: 2, size: 2.4, back: 1 }, high: { n: 600, dpr: 2, size: 2.4, back: 1 } };
  var ORDER = ['lite', 'balanced', 'high'];
  function st(o) { var d = { spin: .12, swirl: 0, core: .55, rimI: .6, alpha: 1, hud: .55, hudSpin: .15, inb: 0, outb: 0, prog: 0, grey: 0, scale: 1, pulse: 0, fps: 30, rim: 'gold', coreC: 'gold', hudC: 'gold' }; for (var k in o) d[k] = o[k]; return d; }
  var S = {
    idle: st({ hudC: 'blue' }),
    listening: st({ spin: .2, core: .45, rimI: 1, rim: 'white', hudC: 'blue', hud: .9, hudSpin: .35, inb: 1, scale: .97, fps: 60 }),
    thinking: st({ spin: .9, swirl: 1.6, core: .7, rimI: .9, rim: 'cyan', hudC: 'cyan', hud: 1, hudSpin: 1.4, fps: 60 }),
    speaking: st({ spin: .3, swirl: .2, core: 1, coreC: 'hot', rimI: .85, hud: .9, hudSpin: .5, outb: 1, scale: 1.03, fps: 60 }),
    working: st({ spin: .5, swirl: .6, core: .7, rim: 'cyan', hudC: 'cyan', rimI: .7, hud: 1, hudSpin: .9, prog: 1, fps: 60 }),
    confirm: st({ spin: .08, core: .45, rim: 'pink', hudC: 'pink', rimI: .9, hud: .8, hudSpin: .1, pulse: 1, fps: 60 }),
    success: st({ spin: .3, core: .9, rim: 'green', hudC: 'green', rimI: 1, hud: .9, hudSpin: .6, fps: 60 }),
    error: st({ spin: .04, core: .25, rim: 'pink', coreC: 'pink', hudC: 'pink', rimI: .8, hud: .45, hudSpin: .05, scale: .97, pulse: .4, fps: 30 }),
    alert: st({ spin: .3, core: .7, rim: 'pink', hudC: 'pink', rimI: 1, hud: 1, hudSpin: 1, pulse: 1, fps: 60 }),
    notify: st({ spin: .2, core: .8, rim: 'amber', hudC: 'amber', rimI: 1, hud: .9, pulse: .6, fps: 30 }),
    muted: st({ spin: .03, core: .1, rim: 'grey', coreC: 'grey', hudC: 'grey', rimI: .35, grey: 1, hud: .25, hudSpin: 0, alpha: .75, fps: 20 }),
    sleep: st({ spin: .035, core: .25, rimI: .3, alpha: .35, hud: .12, hudSpin: .05, hudC: 'blue', fps: 20 }),
    boot: st({ spin: .8, swirl: 1, core: .4, rim: 'cyan', hudC: 'cyan', rimI: .8, hud: 1, hudSpin: 2.2, fps: 60 })
  };
  var NUM = ['spin', 'swirl', 'core', 'rimI', 'alpha', 'hud', 'hudSpin', 'inb', 'outb', 'prog', 'grey', 'scale', 'pulse'];
  var RINGS = [{ R: 1.28, ax: 1.25, az: -.42, sp: 1, c: 'gold', a: .6 }, { R: 1.16, ax: 1.42, az: .62, sp: -.8, c: 'blue', a: .42 }, { R: 1.5, ax: 1.52, az: .1, sp: .5, c: 'gold', a: .2 }];

  var HP = '#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n';
  var VS_Q = 'attribute vec2 aQ; uniform float uQs; varying vec2 vUv; void main(){ vUv=aQ*uQs; gl_Position=vec4(vUv,0.0,1.0); }';
  var FS_Q = HP + [
    'varying vec2 vUv;',
    'uniform float uR,uT,uRimI,uCoreI,uLv,uDir,uGrey,uAl,uPulse,uFlash,uBack,uLa;',
    'uniform vec3 uRim,uCore; uniform mat3 uM;',
    'float grid(vec3 q,float w){ float la=asin(clamp(q.y,-1.0,1.0))*3.8197; float lo=atan(q.z,q.x)*1.9099;',
    ' return max(smoothstep(.5-w,.5,abs(fract(la)-.5)),smoothstep(.5-w,.5,abs(fract(lo)-.5))); }',
    'void main(){',
    ' vec2 p=vUv/uR; float r=length(p);',
    ' float pul=1.0+uPulse*.45*sin(uT*4.2);',
    ' vec2 ld=vec2(cos(uLa),sin(uLa)); float side=.3+.7*max(0.0,dot(p/max(r,.0001),ld));',
    ' vec3 col=vec3(0.0); float a=0.0;',
    ' if(r<1.0){',
    '  float z=sqrt(1.0-r*r); float fres=pow(1.0-z,2.4); float w=.035+.10*(1.0-z);',
    '  float gF=grid(uM*vec3(p,z),w);',
    '  float gB=uBack>.5?grid(uM*vec3(p,-z),w):0.0;',
    '  float core=exp(-r*r*3.0); float spark=exp(-r*r*42.0);',
    '  float rip=0.0; if(abs(uDir)>.01){ rip=pow(.5+.5*sin(r*16.0-uDir*uT*8.0),8.0)*uLv*abs(uDir); }',
    '  col=vec3(.008,.014,.028)+uCore*.025',
    '   +uCore*core*uCoreI*(.5+.7*uLv*max(uDir,0.0))+vec3(1.0,.93,.8)*spark*uCoreI*.7',
    '   +uRim*fres*uRimI*pul*1.25*side',
    '   +uRim*gF*(.06+fres*.5*side)+vec3(.38,.72,1.0)*(gB*.06+fres*.18*(1.0-side))',
    '   +mix(uRim,uCore,.5)*rip*.5;',
    '  vec2 h=p-vec2(-.42,.5); col+=vec3(1.0,.96,.9)*exp(-dot(h,h)*10.0)*.06;',
    '  a=.94;',
    ' } else {',
    '  float g=exp(-(r-1.0)*8.0)*uRimI*pul*(.35+uLv*.5)*(.4+.6*side);',
    '  col=mix(vec3(.38,.72,1.0),uRim,.35+.65*side)*g; a=g*.9;',
    ' }',
    ' if(uFlash>0.0){ float fl=(1.0-smoothstep(0.0,.035,abs(r-(1.0+uFlash*.7))))*(1.0-uFlash); col+=vec3(0.0,1.0,.5)*fl; a=max(a,fl); }',
    ' float l=dot(col,vec3(.3,.59,.11)); col=mix(col,vec3(l),uGrey);',
    ' gl_FragColor=vec4(col,a)*uAl;',
    '}'].join('\n');
  var VS_P = [
    'attribute vec4 aP;',
    'uniform mat3 uM; uniform float uT,uLv,uSw,uSc,uPs,uMode,uHud,uR0,uDir,uSphR;',
    'varying float vA; varying float vG;',
    'void main(){',
    ' vec3 p=aP.xyz; float s=aP.w; vG=0.0; gl_PointSize=1.0;',
    ' if(uMode<.5){',
    '  p*=1.0+sin(uT*1.1+s*6.2831)*.012+uLv*uDir*(.05+.05*sin(s*37.0+uT*9.0));',
    '  float a=uSw*p.y; float c=cos(a); float si=sin(a); p=vec3(c*p.x+si*p.z,p.y,-si*p.x+c*p.z);',
    '  p=uM*p; float dp=p.z*.5+.5; float d=1.0/(1.0-p.z*.25);',
    '  gl_Position=vec4(p.xy*d*uSc,0.0,1.0);',
    '  vA=mix(.1,1.0,dp)*(.65+.35*sin(uT*(1.3+s*2.0)+s*90.0));',
    '  vG=step(.74,fract(s*13.37)); gl_PointSize=uPs*(.5+dp)*(1.0+vG*.5);',
    ' } else if(uMode<1.5){',
    '  p=uM*p; float d=1.0/(1.0-p.z*.25); vec2 xy=p.xy*d*uSc;',
    '  float behind=step(p.z,0.0)*step(length(xy),uSphR);',
    '  vA=(.35+.65*(p.z*.5+.5))*(1.0-behind*.85)*(.75+.25*sin(s*40.0-uT*2.0));',
    '  gl_PointSize=uPs; gl_Position=vec4(xy,0.0,1.0);',
    ' } else if(uMode<2.5){',
    '  float c=cos(uHud); float si=sin(uHud); vA=p.z;',
    '  gl_Position=vec4(vec2(c*p.x-si*p.y,si*p.x+c*p.y)*uSc,0.0,1.0);',
    ' } else {',
    '  float len=uLv*(.2+.8*abs(sin(s*91.0+uT*7.0))*abs(sin(s*17.0-uT*3.1)));',
    '  float r=uR0+p.y*uDir*len*.16; vA=.95-p.y*.55;',
    '  gl_Position=vec4(vec2(cos(p.x),sin(p.x))*r*uSc,0.0,1.0);',
    ' }',
    '}'].join('\n');
  var FS_P = HP + [
    'uniform vec3 uC1,uC2; uniform float uAl,uPt,uGd;',
    'varying float vA; varying float vG;',
    'void main(){ float a=vA*uAl;',
    ' if(uPt>.5){ vec2 q=gl_PointCoord-.5; float r=1.0-dot(q,q)*4.0; if(r<=0.0) discard; a*=r*r; }',
    ' gl_FragColor=vec4(mix(uC1,uC2,vG*uGd)*a,a); }'].join('\n');

  function rnd(seed) { return function () { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; }; }
  function clamp(v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
  function mat(out, ax, ay, az) {
    var cx = Math.cos(ax), sx = Math.sin(ax), cy = Math.cos(ay), sy = Math.sin(ay), cz = Math.cos(az), sz = Math.sin(az);
    var b00 = cz * cy, b01 = -sz, b02 = cz * sy, b10 = sz * cy, b11 = cz, b12 = sz * sy, b20 = -sy, b21 = 0, b22 = cy;
    out[0] = b00; out[1] = cx * b10 - sx * b20; out[2] = sx * b10 + cx * b20;
    out[3] = b01; out[4] = cx * b11 - sx * b21; out[5] = sx * b11 + cx * b21;
    out[6] = b02; out[7] = cx * b12 - sx * b22; out[8] = sx * b12 + cx * b22;
    return out;
  }
  function build(q) {
    var r = rnd(11), n = q.n, P = [], i, j, nS = Math.round(n * .78);
    for (i = 0; i < nS; i++) { var y = 1 - 2 * (i + .5) / nS, rad = Math.sqrt(1 - y * y), th = i * 2.399963; P.push(Math.cos(th) * rad * .97, y * .97, Math.sin(th) * rad * .97, r()); }
    for (; i < n; i++) { var u = r() * 2 - 1, ph = r() * 6.2832, rr = .25 + .6 * Math.cbrt(r()), s2 = Math.sqrt(1 - u * u); P.push(Math.cos(ph) * s2 * rr, u * rr, Math.sin(ph) * s2 * rr, r()); }
    var pts = new Float32Array(P), L = [], thr = 2.9 / Math.sqrt(nS);
    for (i = 0; i < nS; i++) {
      for (var cnt = 0, j2 = i + 1; j2 < nS && cnt < 2; j2++) {
        var dx = pts[i * 4] - pts[j2 * 4], dy = pts[i * 4 + 1] - pts[j2 * 4 + 1], dz = pts[i * 4 + 2] - pts[j2 * 4 + 2];
        if (dx * dx + dy * dy + dz * dz < thr * thr && r() < .5) { L.push(pts[i * 4], pts[i * 4 + 1], pts[i * 4 + 2], pts[i * 4 + 3], pts[j2 * 4], pts[j2 * 4 + 1], pts[j2 * 4 + 2], pts[j2 * 4 + 3]); cnt++; }
      }
    }
    var RG = []; for (i = 0; i <= 128; i++) { var a = i / 128 * 6.2832; RG.push(Math.cos(a), 0, Math.sin(a), i / 128); }
    var H = [], rg = {}, s0 = 0;
    function arc(r0, a0, a1, al, steps) { for (var k = 0; k < steps; k++) { var t0 = a0 + (a1 - a0) * k / steps, t1 = a0 + (a1 - a0) * (k + 1) / steps; H.push(Math.cos(t0) * r0, Math.sin(t0) * r0, al, 0, Math.cos(t1) * r0, Math.sin(t1) * r0, al, 0); } }
    function mark(name) { rg[name] = [s0, H.length / 4 - s0]; s0 = H.length / 4; }
    for (i = 0; i < 90; i++) { var ta = i / 90 * 6.2832, big = i % 5 === 0, r2 = big ? .745 : .722, al = big ? .55 : .26; H.push(Math.cos(ta) * .70, Math.sin(ta) * .70, al, 0, Math.cos(ta) * r2, Math.sin(ta) * r2, al, 0); }
    mark('ticks');
    for (i = 0; i < 6; i++) { var a0 = i * Math.PI / 3; arc(.80, a0, a0 + Math.PI / 3 * .68, .7, 10); }
    arc(.818, .2, .9, .35, 6); arc(.818, 3.3, 4.0, .35, 6);
    mark('arcs');
    for (i = 0; i < 4; i++) { var b0 = i * Math.PI / 2 + .08; arc(.90, b0, b0 + Math.PI / 2 - .16, .2, 18); }
    mark('thin');
    for (i = 0; i < 48; i++) { var c0 = i / 48 * 6.2832; arc(.60, c0, c0 + .06, .32, 1); }
    mark('inner');
    var PR = []; for (i = 0; i <= 180; i++) { var pa = Math.PI / 2 - i / 180 * 6.2832; PR.push(Math.cos(pa) * .86, Math.sin(pa) * .86, 1, 0); }
    var SP = []; for (i = 0; i < 64; i++) { var ba = i / 64 * 6.2832, sd = r(); for (var o = -1; o <= 1; o++) { var aa = ba + o * .0045; SP.push(aa, 0, 0, sd, aa, 1, 0, sd); } }
    var SAT = []; for (i = 0; i < 3; i++) SAT.push(1, 0, 0, .5);
    return { pts: pts, nP: n, lines: new Float32Array(L), nL: L.length / 4, ring: new Float32Array(RG), hud: new Float32Array(H), rg: rg, prog: new Float32Array(PR), spec: new Float32Array(SP), nSp: SP.length / 4, sat: new Float32Array(SAT) };
  }

  function create(canvas, opts) {
    opts = opts || {};
    var api = { setState: noop, setLevel: noop, setVoice: noop, pulse: noop, flash: noop, setProgress: noop, setQuality: noop, setPaused: noop, destroy: noop, quality: 'none', state: 'idle', hasFrameClock: false };
    var gl = canvas.getContext('webgl', { alpha: true, antialias: false, depth: false, stencil: false, premultipliedAlpha: true, preserveDrawingBuffer: false, powerPreference: 'high-performance' }) || canvas.getContext('experimental-webgl');
    if (!gl) { canvas.style.display = 'none'; return api; }
    var auto = !opts.quality || opts.quality === 'auto', qName = auto ? 'balanced' : opts.quality, onFrame = typeof opts.onFrame === 'function' ? opts.onFrame : null;
    var P1, P2, L1 = {}, L2 = {}, B = {}, G, raf = 0, timer = 0, last = 0, t = 0, rot = 0, hudRot = 0, ringRot = 0, lost = false, paused = false;
    var tgt = S[opts.state] || S.idle, cur = {}, k;
    for (k = 0; k < NUM.length; k++) cur[NUM[k]] = tgt[NUM[k]];
    cur.alpha = 0; cur.scale = .7; cur.hud = 0;
    var cRim = COL[tgt.rim].slice(), cCore = COL[tgt.coreC].slice(), cHud = COL[tgt.hudC].slice();
    var level = 0, levelT = 0, pulseV = 0, flashV = 0, progV = 0, progT = 0, settled = false, slow = 0, sN = 0, sSum = 0;
    var M = new Float32Array(9), c1 = new Float32Array(3), c2 = new Float32Array(3), tmp = new Float32Array(3);

    function sh(type, src) { var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) console.warn('[orb]', gl.getShaderInfoLog(s)); return s; }
    function prog(vs, fs, attr, unis, L) {
      var p = gl.createProgram(); gl.attachShader(p, sh(gl.VERTEX_SHADER, vs)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs));
      gl.bindAttribLocation(p, 0, attr); gl.linkProgram(p);
      unis.forEach(function (u) { L[u] = gl.getUniformLocation(p, u); }); return p;
    }
    function init() {
      P1 = prog(VS_Q, FS_Q, 'aQ', ['uQs', 'uR', 'uT', 'uRimI', 'uCoreI', 'uLv', 'uDir', 'uGrey', 'uAl', 'uPulse', 'uFlash', 'uBack', 'uLa', 'uRim', 'uCore', 'uM'], L1);
      P2 = prog(VS_P, FS_P, 'aP', ['uM', 'uT', 'uLv', 'uSw', 'uSc', 'uPs', 'uMode', 'uHud', 'uR0', 'uDir', 'uSphR', 'uC1', 'uC2', 'uAl', 'uPt', 'uGd'], L2);
      gl.enable(gl.BLEND); gl.disable(gl.DEPTH_TEST); gl.enableVertexAttribArray(0);
      B.quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, B.quad); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      ['pts', 'lines', 'ring', 'hud', 'prog', 'spec', 'sat'].forEach(function (n) { B[n] = gl.createBuffer(); });
      upload(); resize();
    }
    function upload() {
      G = build(Q[qName]);
      ['pts', 'lines', 'ring', 'hud', 'prog', 'spec', 'sat'].forEach(function (n) { gl.bindBuffer(gl.ARRAY_BUFFER, B[n]); gl.bufferData(gl.ARRAY_BUFFER, G[n], gl.STATIC_DRAW); });
      api.quality = qName; if (opts.onQuality) opts.onQuality(qName);
    }
    function resize() {
      var dpr = Math.min(window.devicePixelRatio || 1, Q[qName].dpr), w = Math.max(1, Math.round(canvas.clientWidth * dpr)), h = Math.max(1, Math.round(canvas.clientHeight * dpr));
      if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
      gl.viewport(0, 0, w, h);
    }
    function bind(b, size) { gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.vertexAttribPointer(0, size || 4, gl.FLOAT, false, 0, 0); }
    function easeC(c, to, e) { var m = false; for (var i = 0; i < 3; i++) { var d = to[i] - c[i]; if (Math.abs(d) > .002) m = true; c[i] += d * e; } return m; }
    function mix3(o, a, b, f) { o[0] = a[0] + (b[0] - a[0]) * f; o[1] = a[1] + (b[1] - a[1]) * f; o[2] = a[2] + (b[2] - a[2]) * f; return o; }
    function voiceReactive() { return tgt.inb > .01 || tgt.outb > .01; }

    function schedule() {
      if (paused || lost || document.hidden || raf || timer) return;
      if (settled && tgt.fps <= 30) {
        timer = window.setTimeout(function () { timer = 0; raf = requestAnimationFrame(frame); }, Math.max(0, 1000 / tgt.fps - 2));
      } else {
        raf = requestAnimationFrame(frame);
      }
    }
    function wake() {
      if (timer) { clearTimeout(timer); timer = 0; }
      schedule();
    }
    function frame(now) {
      raf = 0;
      if (lost) return;
      var dt = last ? Math.min((now - last) / 1000, .1) : 1 / 60; last = now; t = (t + dt) % 6283.185;
      if (auto && !settled) {
        sSum += dt; if (++sN >= 45) { var avg = sSum / sN; sSum = 0; sN = 0; if (avg > 1 / 40) { if (++slow >= 2) { slow = 0; var qi = ORDER.indexOf(qName); if (qi > 0) { qName = ORDER[qi - 1]; upload(); resize(); } } } else slow = 0; }
      }
      var e = 1 - Math.exp(-dt * 4.0), mv = false;
      for (var i = 0; i < NUM.length; i++) { var key = NUM[i], d = tgt[key] - cur[key]; if (Math.abs(d) > .002) mv = true; cur[key] += d * e; }
      mv = easeC(cRim, COL[tgt.rim], e) || mv; mv = easeC(cCore, COL[tgt.coreC], e) || mv; mv = easeC(cHud, COL[tgt.hudC], e) || mv;
      level += (levelT - level) * (levelT > level ? .5 : .1);
      pulseV *= Math.exp(-dt * 3.0);
      if (flashV > 0) { flashV += dt / .9; if (flashV >= 1) flashV = 0; }
      progV += (progT - progV) * e;
      // Meter packets continue while capture is armed. They only need to wake the
      // full-rate clock when this state actually renders voice-responsive waves.
      settled = !mv && (!voiceReactive() || level < .01) && pulseV < .01 && flashV === 0 && tgt.fps <= 30;
      rot += dt * cur.spin; hudRot += dt * cur.hudSpin * .25; ringRot += dt * (.25 + cur.spin * .6);
      draw();
      if (onFrame) onFrame(now);
      schedule();
    }

    function draw() {
      var sc = cur.scale * (1 + pulseV * .06 + level * .025), sphR = .5 * sc, A = cur.alpha, dir = cur.outb - cur.inb, ps = Q[qName].size * (canvas.width / 360);
      gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
      // 1 · Glaskugel
      gl.useProgram(P1); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA); bind(B.quad, 2);
      gl.uniform1f(L1.uQs, Math.min(1, sphR * 1.75)); gl.uniform1f(L1.uR, sphR); gl.uniform1f(L1.uT, t);
      gl.uniform1f(L1.uRimI, cur.rimI + pulseV * .5); gl.uniform1f(L1.uCoreI, cur.core); gl.uniform1f(L1.uLv, level); gl.uniform1f(L1.uDir, dir);
      gl.uniform1f(L1.uGrey, cur.grey); gl.uniform1f(L1.uAl, A); gl.uniform1f(L1.uPulse, cur.pulse); gl.uniform1f(L1.uFlash, flashV);
      gl.uniform1f(L1.uBack, Q[qName].back); gl.uniform1f(L1.uLa, .85 + Math.sin(t * .07) * .5);
      gl.uniform3fv(L1.uRim, cRim); gl.uniform3fv(L1.uCore, cCore); gl.uniformMatrix3fv(L1.uM, false, mat(M, .4, rot, 0));
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      // 2 · Konstellation
      gl.useProgram(P2); gl.blendFunc(gl.ONE, gl.ONE);
      gl.uniform1f(L2.uT, t); gl.uniform1f(L2.uLv, level); gl.uniform1f(L2.uSw, cur.swirl); gl.uniform1f(L2.uDir, dir); gl.uniform1f(L2.uSphR, sphR);
      gl.uniform1f(L2.uMode, 0); gl.uniform1f(L2.uSc, sphR); gl.uniformMatrix3fv(L2.uM, false, M);
      gl.uniform3fv(L2.uC1, mix3(c1, COL.white, cRim, .3)); gl.uniform3fv(L2.uC2, cCore);
      if (G.nL) { bind(B.lines); gl.uniform1f(L2.uPt, 0); gl.uniform1f(L2.uGd, 0); gl.uniform1f(L2.uAl, .16 * A); gl.drawArrays(gl.LINES, 0, G.nL); }
      bind(B.pts); gl.uniform1f(L2.uPt, 1); gl.uniform1f(L2.uGd, 1); gl.uniform1f(L2.uPs, ps); gl.uniform1f(L2.uAl, (.85 + pulseV * .4) * A); gl.drawArrays(gl.POINTS, 0, G.nP);
      // 3 · Orbit-Ringe + Satelliten
      gl.uniform1f(L2.uMode, 1); gl.uniform1f(L2.uGd, 0);
      for (var i = 0; i < 3; i++) {
        var R = RINGS[i]; gl.uniformMatrix3fv(L2.uM, false, mat(M, R.ax, ringRot * R.sp, R.az)); gl.uniform1f(L2.uSc, sphR * R.R);
        gl.uniform3fv(L2.uC1, R.c === 'blue' ? mix3(c1, COL.blue, cRim, .2) : mix3(c1, COL.gold, cRim, .25));
        bind(B.ring); gl.uniform1f(L2.uPt, 0); gl.uniform1f(L2.uAl, R.a * A); gl.drawArrays(gl.LINE_STRIP, 0, 129);
        bind(B.sat); gl.uniform1f(L2.uPt, 1); gl.uniform1f(L2.uPs, 7 * (canvas.width / 360)); gl.uniform3fv(L2.uC1, cCore); gl.uniform1f(L2.uAl, R.a * 1.6 * A); gl.drawArrays(gl.POINTS, i, 1);
      }
      // 4 · HUD
      gl.uniform1f(L2.uMode, 2); gl.uniform1f(L2.uPt, 0); gl.uniform1f(L2.uSc, sc); gl.uniform3fv(L2.uC1, cHud); bind(B.hud);
      var h = cur.hud * A, rg = G.rg;
      gl.uniform1f(L2.uAl, h); gl.uniform1f(L2.uHud, hudRot * .3); gl.drawArrays(gl.LINES, rg.ticks[0], rg.ticks[1]);
      gl.uniform1f(L2.uHud, hudRot * 1.6); gl.drawArrays(gl.LINES, rg.arcs[0], rg.arcs[1]);
      gl.uniform1f(L2.uHud, -hudRot * .8); gl.drawArrays(gl.LINES, rg.thin[0], rg.thin[1]);
      gl.uniform1f(L2.uHud, -hudRot * 2.2); gl.drawArrays(gl.LINES, rg.inner[0], rg.inner[1]);
      if (cur.prog > .01) {
        bind(B.prog); gl.uniform1f(L2.uHud, 0); gl.uniform3fv(L2.uC1, COL.green);
        gl.uniform1f(L2.uAl, cur.prog * A * .18); gl.drawArrays(gl.LINE_STRIP, 0, 181);
        var nv = Math.max(2, Math.round(progV * 181)); gl.uniform1f(L2.uAl, cur.prog * A); gl.drawArrays(gl.LINE_STRIP, 0, nv);
      }
      // 5 · Stimmen-Spektrum: du = nach innen, JARVIS = nach außen
      if (level > .01) {
        gl.uniform1f(L2.uMode, 3); bind(B.spec);
        if (cur.inb > .01) { gl.uniform1f(L2.uR0, .95); gl.uniform1f(L2.uDir, -1); gl.uniform3fv(L2.uC1, mix3(tmp, COL.white, cRim, .4)); gl.uniform1f(L2.uAl, cur.inb * A * .9); gl.drawArrays(gl.LINES, 0, G.nSp); }
        if (cur.outb > .01) { gl.uniform1f(L2.uR0, .53); gl.uniform1f(L2.uDir, 1); gl.uniform3fv(L2.uC1, cCore); gl.uniform1f(L2.uAl, cur.outb * A); gl.drawArrays(gl.LINES, 0, G.nSp); }
      }
    }

    function start() { if (!paused && !raf && !timer) { last = 0; schedule(); } }
    function stop() { if (raf) cancelAnimationFrame(raf); if (timer) clearTimeout(timer); raf = 0; timer = 0; }
    function onVis() { document.hidden ? stop() : start(); }
    function onLost(ev) { ev.preventDefault(); lost = true; }
    function onRestored() { lost = false; init(); start(); }
    var ro = window.ResizeObserver ? new ResizeObserver(resize) : null;
    init(); if (ro) ro.observe(canvas);
    document.addEventListener('visibilitychange', onVis);
    canvas.addEventListener('webglcontextlost', onLost, false); canvas.addEventListener('webglcontextrestored', onRestored, false);
    start();

    api.setState = function (n) { if (!S[n]) n = 'idle'; if (n === api.state) return; api.state = n; tgt = S[n]; settled = false; sN = 0; sSum = 0; if (n === 'success') flashV = .001; if (n === 'listening' || n === 'alert' || n === 'notify') pulseV = 1; if (n !== 'working') progT = 0; wake(); };
    api.setLevel = api.setVoice = function (a, b) { var v = typeof a === 'number' ? a : b; levelT = clamp(v || 0); if (voiceReactive() && levelT > .01) { settled = false; wake(); } };
    api.pulse = function () { pulseV = 1; settled = false; wake(); };
    api.flash = function () { flashV = .001; settled = false; wake(); };
    api.setProgress = function (p) { progT = clamp(p); settled = false; wake(); };
    api.setQuality = function (q) { auto = !q || q === 'auto'; qName = auto ? 'balanced' : q; upload(); resize(); };
    // Night mode hides the canvas completely. Suspend its WebGL animation without
    // changing buffers, particle count, DPR, or any visible state.
    api.setPaused = function (value) { paused = !!value; if (paused) stop(); else if (!document.hidden) start(); };
    api.destroy = function () { stop(); if (ro) ro.disconnect(); document.removeEventListener('visibilitychange', onVis); canvas.removeEventListener('webglcontextlost', onLost); canvas.removeEventListener('webglcontextrestored', onRestored); var x = gl.getExtension('WEBGL_lose_context'); if (x) x.loseContext(); };
    api.state = opts.state || 'idle';
    api.hasFrameClock = true;
    return api;
  }
  function noop() {}
  window.JarvisOrb = { create: create, STATES: S, QUALITY: Q };
})();
