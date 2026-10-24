'use strict';
// Test the PHP server:  node test.js   (needs php in PATH)
const { spawn } = require('child_process'), assert = require('assert'), fs = require('fs'), path = require('path'), os = require('os');
const PORT = 19000 + Math.floor(Math.random() * 900);
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lcphp-'));
fs.copyFileSync(path.join(__dirname, 'index.php'), path.join(dir, 'index.php'));
const srv = spawn('php', ['-S', '127.0.0.1:' + PORT, '-t', dir], { env: { ...process.env, PHP_CLI_SERVER_WORKERS: '6' }, stdio: 'ignore' });
const B = 'http://127.0.0.1:' + PORT + '/index.php';
const api = async (m, p, t, body, raw) => {
  const [pp, qq] = p.split('?');
  const r = await fetch(B + '?r=' + pp + (qq ? '&' + qq : ''), { method: m, headers: { ...(t ? { 'X-Token': t } : {}), ...(raw ? raw.h : { 'Content-Type': 'application/json' }) }, body: raw ? raw.b : body ? JSON.stringify(body) : undefined });
  const ct = r.headers.get('content-type') || '';
  return { s: r.status, j: ct.includes('json') ? await r.json() : await r.text() };
};
(async () => {
  await new Promise(r => setTimeout(r, 800));
  let r = await api('POST', '/api/register/start', null, { name: 'Alice' });
  assert.equal(r.s, 200); assert(r.j.first); const A = r.j;
  r = await api('POST', '/api/register/start', null, { name: 'Bob', inviter: 'nope' }); assert.equal(r.s, 404);
  r = await api('POST', '/api/register/start', null, { name: 'Bob', inviter: A.code.toLowerCase().replace('-', '') });
  assert.equal(r.s, 200); const regId = r.j.regId;
  r = await api('GET', '/api/sync?since=0', A.token); const otp = /OTP: (\d{6})/.exec(r.j.msgs[0].text)[1];
  assert.equal(r.j.msgs[0].from, 'SYSTEM');
  r = await api('POST', '/api/register/verify', null, { regId, otp: '000000' }); assert.equal(r.s, 400);
  r = await api('POST', '/api/register/verify', null, { regId, otp }); assert.equal(r.s, 200); const Bo = r.j;
  r = await api('GET', '/api/sync?since=0', A.token); assert(r.j.msgs.some(m => m.type === 'joined' && m.text.endsWith(Bo.code)));
  assert.equal((await api('GET', '/api/me')).s, 401);
  // long-poll: Bob waits, Alice messages him
  const poll = api('GET', '/api/poll?since=0&wait=10', Bo.token);
  await new Promise(r => setTimeout(r, 500));
  r = await api('POST', '/api/send', A.token, { to: Bo.code, type: 'text', text: 'hi' }); assert.equal(r.s, 200);
  let p = await poll; assert(p.j.events.some(e => e.t === 'msg'));
  // upload (fixed length), .php must not survive
  r = await api('POST', '/api/upload', A.token, null, { h: { 'X-Name': encodeURIComponent('pic one.png'), 'Content-Type': 'image/png' }, b: Buffer.from('PNGDATA') });
  assert.equal(r.s, 200); const f = r.j.file;
  r = await api('POST', '/api/upload', A.token, null, { h: { 'X-Name': 'shell.php', 'Content-Type': 'text/plain' }, b: Buffer.from('<?php echo 1;') });
  assert(r.j.file.endsWith('.bin'), 'php ext must be neutralised');
  r = await api('POST', '/api/send', A.token, { to: Bo.code, type: 'image', file: f, fname: 'pic one.png', mime: 'image/png' }); assert.equal(r.s, 200);
  r = await api('POST', '/api/send', A.token, { to: Bo.code, type: 'image', file: '../../etc/passwd' }); assert.equal(r.s, 400);
  r = await api('GET', f + '?t=' + Bo.token); assert.equal(r.j, 'PNGDATA');
  r = await api('GET', f); assert.equal(r.s, 401);
  r = await api('GET', '/api/sync?since=0', Bo.token); assert.equal(r.j.msgs.length, 2);
  // data dir must not be downloadable
  const dd = await fetch('http://127.0.0.1:' + PORT + '/litechat_data/db.php'); assert.equal(dd.status, 403); assert.equal(await dd.text(), '');
  // signalling over poll
  r = await api('POST', '/api/signal', A.token, { to: Bo.code, kind: 'invite', video: true }); assert(r.j.delivered);
  p = await api('GET', '/api/poll?since=999&wait=3', Bo.token);
  assert(p.j.events.some(e => e.t === 'sig' && e.kind === 'invite' && e.from === A.code && e.video === true), JSON.stringify(p.j));
  p = await api('GET', '/api/poll?since=999&wait=1', Bo.token); assert.equal(p.j.events.length, 0);
  // location opt-in
  r = await api('POST', '/api/location', Bo.token, { lat: 1, lng: 2 }); assert.equal(r.s, 409);
  await api('POST', '/api/location/settings', Bo.token, { sharing: true, trusted: [A.code] });
  r = await api('POST', '/api/location', Bo.token, { lat: 12.5, lng: 77.5, acc: 10 }); assert.equal(r.s, 200);
  r = await api('GET', '/api/location/' + Bo.code, A.token); assert.equal(r.j.lat, 12.5);
  r = await api('GET', '/api/location/' + A.code, Bo.token); assert.equal(r.s, 403);
  r = await api('POST', '/api/sos', Bo.token, { lat: 12.5, lng: 77.5 }); assert.equal(r.j.sent, 1);
  r = await api('GET', '/api/sync?since=0', A.token); assert(r.j.msgs.some(m => m.text.includes('SOS')));
  console.log('ALL PHP SERVER TESTS PASSED');
})().catch(e => { console.error('FAIL', e); process.exitCode = 1; }).finally(() => { srv.kill(); fs.rmSync(dir, { recursive: true, force: true }); });
