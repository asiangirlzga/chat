'use strict';
// Admin panel test: node testadmin.js  (needs php in PATH). Sets ADMIN_KEY on a copy of index.php.
const { spawn } = require('child_process'), assert = require('assert'), fs = require('fs'), path = require('path'), os = require('os');
const PORT = 19500 + Math.floor(Math.random() * 400);
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lcadm-'));
let src = fs.readFileSync(path.join(__dirname, 'index.php'), 'utf8');
assert(src.includes("const ADMIN_KEY = '';"));
src = src.replace("const ADMIN_KEY = '';", "const ADMIN_KEY = 'secret123';");
fs.writeFileSync(path.join(dir, 'index.php'), src);
const srv = spawn('php', ['-S', '127.0.0.1:' + PORT, '-t', dir], { stdio: 'ignore' });
const B = 'http://127.0.0.1:' + PORT + '/index.php';
const api = async (m, p, t, body) => {
  const [pp, qq] = p.split('?');
  const r = await fetch(B + '?r=' + pp + (qq ? '&' + qq : ''), { method: m, headers: { ...(t ? { 'X-Token': t } : {}), 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  return { s: r.status, j: await r.json() };
};
// raw fetch for the HTML admin panel, keeping the cookie jar manually
let cookie = '';
async function adm(bodyForm) {
  const r = await fetch(B + '?r=admin', {
    method: bodyForm ? 'POST' : 'GET',
    redirect: 'manual',
    headers: { ...(cookie ? { Cookie: cookie } : {}), ...(bodyForm ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}) },
    body: bodyForm ? new URLSearchParams(bodyForm).toString() : undefined,
  });
  const sc = r.headers.get('set-cookie');
  if (sc) cookie = sc.split(';')[0];
  const text = await r.text().catch(() => '');
  return { s: r.status, loc: r.headers.get('location'), text };
}
(async () => {
  await new Promise(r => setTimeout(r, 700));
  const A = (await api('POST', 'api/register/start', null, { name: 'Alice' })).j;          // first user, no OTP
  const regId = (await api('POST', 'api/register/start', null, { name: 'Bob', inviter: A.code })).j.regId;
  let r = await api('GET', 'api/sync?since=0', A.token);
  const otp = /OTP: (\d{6})/.exec(r.j.msgs[0].text)[1];
  const Bo = (await api('POST', 'api/register/verify', null, { regId, otp })).j;

  // disabled by default without ADMIN_KEY in the real file - here it's on, so panel should require login
  let p = await adm(); assert.equal(p.s, 200); assert(p.text.includes('Log in'), 'should show login form');
  p = await adm({ action: 'login', pass: 'wrong' }); assert(!p.loc, 'wrong password must not log in');
  p = await adm({ action: 'login', pass: 'secret123' }); assert.equal(p.loc, '?r=admin');
  p = await adm(); assert(p.text.includes('Users'), 'now the dashboard should show'); assert(p.text.includes(A.code));

  // message one user
  p = await adm({ action: 'send', to: Bo.code, text: 'hello from support' });
  r = await api('GET', 'api/sync?since=0', Bo.token);
  assert(r.j.msgs.some(m => m.from === 'ADMIN' && m.text === 'hello from support'));

  // broadcast
  p = await adm({ action: 'broadcast', text: 'service notice' });
  assert(p.text.includes('Sent to 2'));
  r = await api('GET', 'api/sync?since=0', A.token);
  assert(r.j.msgs.some(m => m.from === 'ADMIN' && m.text === 'service notice'));

  // disable Bob -> his token stops working; re-enable -> works again
  r = await api('GET', 'api/me', Bo.token); assert.equal(r.s, 200);
  p = await adm({ action: 'toggle', code: Bo.code }); assert(p.text.includes('Disabled'));
  r = await api('GET', 'api/me', Bo.token); assert.equal(r.s, 401, 'disabled user must be locked out');
  p = await adm({ action: 'toggle', code: Bo.code }); assert(p.text.includes('Enabled'));
  r = await api('GET', 'api/me', Bo.token); assert.equal(r.s, 200, 're-enabled user must work again');

  // admin cannot read Alice<->Bob private messages (only sees counts, not content)
  await api('POST', 'api/send', A.token, { to: Bo.code, type: 'text', text: 'SECRET BETWEEN US' });
  p = await adm();
  assert(!p.text.includes('SECRET BETWEEN US'), 'admin panel must not leak private chat content');

  // logout clears the session
  p = await adm({ action: 'logout' }); assert.equal(p.loc, '?r=admin');
  p = await adm(); assert(p.text.includes('Log in'), 'session should be gone after logout');

  console.log('ALL ADMIN PANEL TESTS PASSED');
})().catch(e => { console.error('FAIL', e); process.exitCode = 1; }).finally(() => { srv.kill(); fs.rmSync(dir, { recursive: true, force: true }); });
