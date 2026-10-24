'use strict';
// End-to-end test: node test.js
const { spawn } = require('child_process'), os = require('os'), fs = require('fs'), path = require('path'), assert = require('assert');
const PORT = 18000 + Math.floor(Math.random() * 1000), B = 'http://127.0.0.1:' + PORT;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lc-'));
const srv = spawn('node', [path.join(__dirname, 'server.js')], { env: { ...process.env, PORT, DATA_DIR: dir }, stdio: 'inherit' });
const api = async (m, p, t, body, raw) => {
  const r = await fetch(B + p, { method: m, headers: { ...(t ? { Authorization: 'Bearer ' + t } : {}), ...(raw ? raw.h : { 'Content-Type': 'application/json' }) }, body: raw ? raw.b : body ? JSON.stringify(body) : undefined });
  const j = r.headers.get('content-type')?.includes('json') ? await r.json() : await r.text();
  return { s: r.status, j };
};
(async () => {
  await new Promise(r => setTimeout(r, 600));
  // first user (no OTP)
  let r = await api('POST', '/api/register/start', null, { name: 'Alice' });
  assert.equal(r.s, 200); assert(r.j.first); const A = r.j;
  // second user needs inviter + OTP delivered into Alice's chat
  r = await api('POST', '/api/register/start', null, { name: 'Bob', inviter: 'nope' }); assert.equal(r.s, 404);
  r = await api('POST', '/api/register/start', null, { name: 'Bob', inviter: A.code.toLowerCase().replace('-', '') });
  assert.equal(r.s, 200); const regId = r.j.regId;
  r = await api('GET', '/api/sync?since=0', A.token); const otp = /OTP: (\d{6})/.exec(r.j.msgs[0].text)[1];
  assert.equal(r.j.msgs[0].from, 'SYSTEM');
  r = await api('POST', '/api/register/verify', null, { regId, otp: '000000' }); assert.equal(r.s, 400);
  r = await api('POST', '/api/register/verify', null, { regId, otp }); assert.equal(r.s, 200); const Bo = r.j;
  r = await api('GET', '/api/sync?since=0', A.token); assert(r.j.msgs.some(m => m.type === 'joined' && m.text.endsWith(Bo.code)));
  // SSE for Bob
  const events = []; const ac = new AbortController();
  fetch(B + '/api/events', { headers: { Authorization: 'Bearer ' + Bo.token }, signal: ac.signal }).then(async res => {
    const rd = res.body.getReader(), dec = new TextDecoder();
    for (;;) { const { value, done } = await rd.read(); if (done) break; for (const l of dec.decode(value).split('\n')) if (l.startsWith('data: ')) events.push(JSON.parse(l.slice(6))); }
  }).catch(() => {});
  await new Promise(r => setTimeout(r, 300));
  // message + upload + file message
  r = await api('POST', '/api/send', A.token, { to: Bo.code, type: 'text', text: 'hi' }); assert.equal(r.s, 200);
  r = await api('POST', '/api/upload', A.token, null, { h: { 'x-name': encodeURIComponent('pic one.png'), 'Content-Type': 'image/png' }, b: Buffer.from('PNGDATA') });
  assert.equal(r.s, 200); const f = r.j.file;
  r = await api('POST', '/api/send', A.token, { to: Bo.code, type: 'image', file: f, fname: 'pic one.png', mime: 'image/png' }); assert.equal(r.s, 200);
  r = await api('POST', '/api/send', A.token, { to: Bo.code, type: 'image', file: '../../etc/passwd' }); assert.equal(r.s, 400);
  r = await api('GET', f + '?t=' + Bo.token); assert.equal(r.j, 'PNGDATA');
  r = await api('GET', f); assert.equal(r.s, 401);
  r = await api('GET', '/api/sync?since=0', Bo.token); assert.equal(r.j.msgs.length, 2);
  // signaling
  r = await api('POST', '/api/signal', A.token, { to: Bo.code, kind: 'invite', video: true }); assert(r.j.delivered);
  await new Promise(r => setTimeout(r, 300));
  assert(events.some(e => e.t === 'sig' && e.kind === 'invite' && e.from === A.code && e.video));
  assert(events.some(e => e.t === 'msg'));
  ac.abort();
  await new Promise(r => setTimeout(r, 200));
  r = await api('POST', '/api/signal', A.token, { to: Bo.code, kind: 'invite' }); assert(!r.j.delivered);
  // location: opt-in + trusted only
  r = await api('POST', '/api/location', Bo.token, { lat: 1, lng: 2 }); assert.equal(r.s, 409);
  await api('POST', '/api/location/settings', Bo.token, { sharing: true, trusted: [A.code] });
  r = await api('POST', '/api/location', Bo.token, { lat: 12.5, lng: 77.5, acc: 10 }); assert.equal(r.s, 200);
  r = await api('GET', '/api/location/' + Bo.code, A.token); assert.equal(r.j.lat, 12.5);
  const C = (await api('POST', '/api/register/start', null, { name: 'Eve', inviter: A.code })).j; // pending only
  r = await api('GET', '/api/location/' + A.code, Bo.token); assert.equal(r.s, 403);
  r = await api('POST', '/api/sos', Bo.token, { lat: 12.5, lng: 77.5 }); assert.equal(r.j.sent, 1);
  r = await api('GET', '/api/sync?since=0', A.token); assert(r.j.msgs.some(m => m.text.includes('SOS')));
  console.log('ALL SERVER TESTS PASSED');
})().catch(e => { console.error('FAIL', e); process.exitCode = 1; }).finally(() => { srv.kill(); fs.rmSync(dir, { recursive: true, force: true }); });
