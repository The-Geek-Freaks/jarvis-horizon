const assert = require('node:assert/strict');
const fs = require('node:fs');

const css = fs.readFileSync('horizon/css/caption-overrides.css', 'utf8');
const wide = css.match(/@media \(orientation:landscape\) and \(min-width:760px\) and \(min-height:501px\) \{([\s\S]*?)\n\}/);

assert.ok(wide, 'wide adaptive-landscape needs its own geometry boundary');
const rules = wide[1];
assert.match(rules, /\.orb-wrap \{ left:456px; top:48px; width:360px; height:360px; transform:none; \}/);
assert.match(rules, /canvas \{ width:360px; height:360px; \}/);
assert.match(rules, /\.stage \{ left:324px; top:56px; bottom:auto; width:calc\(100vw - 348px\); height:336px;/);
assert.match(rules, /#horizon\[data-tile=true\] \.orb-wrap \{ transform:translate\(-476px,88px\) scale\(\.5\); \}/);
assert.match(rules, /\.caption \{ left:180px; top:auto; bottom:8px; width:600px; height:76px; padding:10px 22px; \}/);
assert.match(rules, /\.caption-layers \{ height:52px; \}/);
assert.match(rules, /\.caption \.caption-layer \{ font:500 20px\/1\.3 Space; \}/);

// At the captured CSS viewport (960 x 540), the stage is the original 612px
// right-hand Horizon panel rather than the compact, hidden handset placement.
assert.equal(960 - 348, 612);
console.log('wide adaptive landscape restores the 360px orb and 612px right stage');
