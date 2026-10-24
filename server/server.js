'use strict';
/**
 * LiteChat server - zero dependencies (Node 18+). Runs on a PC, a VPS or a phone (Termux).
 *   node server.js
 * Env: PORT (8080), DATA_DIR (./data), BOOTSTRAP_KEY (optional admin key for the very first user),
 *      MAX_UPLOAD_MB (50), TURN_URL / TURN_USER / TURN_PASS (optional TURN server for calls)
 */
const http = require('http'), fs = require('fs'), path = require('path'), crypto = require('crypto');

const PORT = +process.env.PORT || 8080;
const DATA = process.env.DATA_DIR || path.join(__dirname, 'data');
const MAXUP = (+process.env.MAX_UPLOAD_MB || 50) * 1048576;
const BOOT = process.env.BOOTSTRAP_KEY || '';
fs.mkdirSync(path.join(DATA, 'files'), { recursive: true });

// ---------- tiny JSON "database" ----------
const DBF = path.join(DATA, 'db.json');
let db = { users: {}, msgs: [], seq: 0 };
try { db = JSON.parse(fs.readFileSync(DBF, 'utf8')); } catch (e) { /* first run */ }
let dirty = false;
const save = () => { dirty = true; };
function flush() {
  if (!dirty) return;
  dirty = false;
  fs.writeFileSync(DBF + '.tmp', JSON.stringify(db));
  fs.renameSync(DBF + '.tmp', DBF);
}
setInterval(flush, 2000).unref();
for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => { flush(); process.exit(0); });

// ---------- helpers ----------
const sha = s => crypto.createHash('sha256').update(s).digest('hex');
const rnd = n => crypto.randomBytes(n).toString('base64url');
const ALPHA = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const fmt = s => { const n = String(s || '').toUpperCase().replace(/[^A-Z0-9]/g, ''); return n.length === 8 ? n.slice(0, 4) + '-' + n.slice(4) : n; };
const clean = (s, max) => String(s == null ? '' : s).replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max);
const tokenIdx = new Map(); // sha(token) -> code
for (const u of Object.values(db.users)) tokenIdx.set(u.th, u.code);

function newCode() {
  for (;;) {
    let c = '';
    for (let i = 0; i < 8; i++) c += ALPHA[crypto.randomInt(ALPHA.length)];
    c = c.slice(0, 4) + '-' + c.slice(4);
    if (!db.users[c]) return c;
  }
}
function createUser(name) {
  const code = newCode(), token = rnd(24);
  db.users[code] = { code, name, th: sha(token), created: Date.now(), sharing: false, trusted: [], loc: null };
  tokenIdx.set(sha(token), code);
  save();
  return { code, token };
}

// ---------- push (Server-Sent Events) ----------
const clients = new Map(); // code -> Set<res>
function push(code, obj) {
  const set = clients.get(code);
  if (!set) return 0;
  const line = 'data: ' + JSON.stringify(obj) + '\n\n';
  for (const r of set) r.write(line);
  return set.size;
}
function addMsg(from, to, f) {
  const m = { id: ++db.seq, from, to, type: f.type, text: f.text || '', file: f.file || '', fname: f.fname || '', mime: f.mime || '', ts: Date.now() };
  db.msgs.push(m); save();
  push(to, { t: 'msg' });
  if (from !== 'SYSTEM') push(from, { t: 'msg' });
  return m;
}

// ---------- http utils ----------
function send(res, code, obj) {
  const b = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(b) });
  res.end(b);
}
const fail = (res, code, msg) => send(res, code, { error: msg });
function readJson(req, limit = 65536) {
  return new Promise((ok, no) => {
    let n = 0; const ch = [];
    req.on('data', d => { n += d.length; if (n > limit) { no(new Error('Too large')); req.destroy(); } else ch.push(d); });
    req.on('end', () => { try { ok(ch.length ? JSON.parse(Buffer.concat(ch).toString()) : {}); } catch (e) { no(new Error('Bad JSON')); } });
    req.on('error', no);
  });
}
function authUser(req, url) {
  let t = (req.headers.authorization || '').replace(/^Bearer /i, '') || url.searchParams.get('t') || '';
  const code = tokenIdx.get(sha(t));
  return code ? db.users[code] : null;
}
const MIME = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', pdf: 'application/pdf', txt: 'text/plain', mp4: 'video/mp4', mp3: 'audio/mpeg', m4a: 'audio/mp4', zip: 'application/zip' };

const hits = new Map(); // ip -> [timestamps]
function limited(ip, max, ms) {
  const now = Date.now(), a = (hits.get(ip) || []).filter(t => now - t < ms);
  a.push(now); hits.set(ip, a);
  return a.length > max;
}
const regs = new Map(); // regId -> pending registration
setInterval(() => { const n = Date.now(); for (const [k, v] of regs) if (v.exp < n) regs.delete(k); }, 60000).unref();

// ---------- routes ----------
async function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname, M = req.method;
  const ip = req.socket.remoteAddress;

  if (p === '/' && M === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('LiteChat server is running. Users: ' + Object.keys(db.users).length + '\n');
  }

  // ----- registration (no auth) -----
  if (p === '/api/register/start' && M === 'POST') {
    const b = await readJson(req);
    const name = clean(b.name, 40), inv = clean(b.inviter, 64);
    if (!name) return fail(res, 400, 'Name required');
    if (limited('reg' + ip, 10, 3600e3)) return fail(res, 429, 'Too many attempts, try later');
    if (!Object.keys(db.users).length) {                 // very first user = admin, no OTP
      if (BOOT && inv !== BOOT) return fail(res, 403, 'Admin key required for the first user');
      const u = createUser(name);
      return send(res, 200, { first: true, token: u.token, code: u.code, name });
    }
    const inviter = db.users[fmt(inv)];
    if (!inviter) return fail(res, 404, 'Inviter code not found');
    let pending = 0; for (const r of regs.values()) if (r.inviter === inviter.code) pending++;
    if (pending >= 5) return fail(res, 429, 'Inviter has too many pending invites');
    const otp = String(crypto.randomInt(100000, 1000000)), regId = rnd(9);
    regs.set(regId, { name, inviter: inviter.code, otp, exp: Date.now() + 600e3, tries: 0 });
    addMsg('SYSTEM', inviter.code, { type: 'system', text: '🔐 ' + name + ' wants to join LiteChat.\nOTP: ' + otp + '\nShare it only if you know them. Valid for 10 minutes.' });
    return send(res, 200, { regId, inviter: { code: inviter.code, name: inviter.name } });
  }
  if (p === '/api/register/verify' && M === 'POST') {
    const b = await readJson(req);
    const r = regs.get(String(b.regId || ''));
    if (!r || r.exp < Date.now()) return fail(res, 400, 'Request expired, start again');
    if (++r.tries > 5) { regs.delete(b.regId); return fail(res, 429, 'Too many wrong OTPs'); }
    if (String(b.otp || '').trim() !== r.otp) return fail(res, 400, 'Wrong OTP');
    regs.delete(b.regId);
    const u = createUser(r.name), inviter = db.users[r.inviter];
    addMsg('SYSTEM', inviter.code, { type: 'joined', text: r.name + '|' + u.code });
    return send(res, 200, { token: u.token, code: u.code, name: r.name, inviter: { code: inviter.code, name: inviter.name } });
  }

  // ----- everything below needs a token -----
  const me = authUser(req, url);
  if (!me) return fail(res, 401, 'Unauthorized');

  if (p === '/api/events' && M === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.write('retry: 3000\n\n');
    if (!clients.has(me.code)) clients.set(me.code, new Set());
    clients.get(me.code).add(res);
    const hb = setInterval(() => res.write(': hb\n\n'), 25000);
    req.on('close', () => { clearInterval(hb); const s = clients.get(me.code); if (s) { s.delete(res); if (!s.size) clients.delete(me.code); } });
    return;
  }
  if (p === '/api/me') return send(res, 200, { code: me.code, name: me.name, sharing: me.sharing, trusted: me.trusted });
  if (p === '/api/profile' && M === 'POST') {
    const name = clean((await readJson(req)).name, 40);
    if (!name) return fail(res, 400, 'Name required');
    me.name = name; save(); return send(res, 200, { ok: true });
  }
  if (p.startsWith('/api/user/') && M === 'GET') {
    const u = db.users[fmt(decodeURIComponent(p.slice(10)))];
    return u ? send(res, 200, { code: u.code, name: u.name }) : fail(res, 404, 'Code not found');
  }

  if (p === '/api/send' && M === 'POST') {
    const b = await readJson(req);
    const to = fmt(b.to);
    if (!db.users[to]) return fail(res, 404, 'Recipient not found');
    const type = ['text', 'image', 'file', 'call'].includes(b.type) ? b.type : 'text';
    const file = clean(b.file, 80);
    if (type !== 'text' && type !== 'call' && !/^\/f\/[A-Za-z0-9_-]{8,40}(\.[a-z0-9]{1,8})?$/.test(file)) return fail(res, 400, 'Bad file');
    const text = String(b.text || '').slice(0, 4000);
    if (type === 'text' && !text.trim()) return fail(res, 400, 'Empty message');
    return send(res, 200, { msg: addMsg(me.code, to, { type, text, file, fname: clean(b.fname, 120), mime: clean(b.mime, 80) }) });
  }
  if (p === '/api/sync' && M === 'GET') {
    const since = +url.searchParams.get('since') || 0;
    let lo = 0, hi = db.msgs.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (db.msgs[mid].id <= since) lo = mid + 1; else hi = mid; }
    const out = [];
    for (let i = lo; i < db.msgs.length && out.length < 300; i++) {
      const m = db.msgs[i];
      if (m.to === me.code || m.from === me.code) out.push(m);
    }
    return send(res, 200, { msgs: out, more: out.length >= 300 });
  }

  // ----- files -----
  if (p === '/api/upload' && M === 'POST') {
    const name = decodeURIComponent(req.headers['x-name'] || 'file');
    let ext = (path.extname(name).slice(1) || '').toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 8);
    const id = rnd(18) + (ext ? '.' + ext : '');
    const fp = path.join(DATA, 'files', id), ws = fs.createWriteStream(fp);
    let n = 0, dead = false;
    req.on('data', d => { n += d.length; if (n > MAXUP && !dead) { dead = true; ws.destroy(); fs.unlink(fp, () => {}); fail(res, 413, 'File too large (max ' + MAXUP / 1048576 + ' MB)'); req.destroy(); } });
    req.pipe(ws);
    ws.on('finish', () => { if (!dead) send(res, 200, { file: '/f/' + id, size: n }); });
    ws.on('error', () => { if (!dead) fail(res, 500, 'Write failed'); });
    return;
  }
  if (p.startsWith('/f/') && M === 'GET') {
    const id = path.basename(p);
    if (!/^[A-Za-z0-9_-]+(\.[a-z0-9]+)?$/.test(id)) return fail(res, 400, 'Bad name');
    const fp = path.join(DATA, 'files', id);
    fs.stat(fp, (e, st) => {
      if (e) return fail(res, 404, 'Not found');
      const h = { 'Content-Type': MIME[path.extname(id).slice(1)] || 'application/octet-stream', 'Content-Length': st.size, 'Cache-Control': 'private, max-age=31536000' };
      const n = url.searchParams.get('n');
      if (n) h['Content-Disposition'] = 'attachment; filename*=UTF-8\'\'' + encodeURIComponent(n);
      res.writeHead(200, h); fs.createReadStream(fp).pipe(res);
    });
    return;
  }

  // ----- calls: ICE config + signaling relay -----
  if (p === '/api/ice') {
    const ice = [{ urls: 'stun:stun.l.google.com:19302' }, { urls: 'stun:stun1.l.google.com:19302' }];
    if (process.env.TURN_URL) ice.push({ urls: process.env.TURN_URL, username: process.env.TURN_USER || '', credential: process.env.TURN_PASS || '' });
    return send(res, 200, { iceServers: ice });
  }
  if (p === '/api/signal' && M === 'POST') {
    const b = await readJson(req);
    const to = fmt(b.to);
    if (!db.users[to]) return fail(res, 404, 'User not found');
    if (!['invite', 'accept', 'reject', 'busy', 'end', 'offer', 'answer', 'ice'].includes(b.kind)) return fail(res, 400, 'Bad kind');
    const n = push(to, { t: 'sig', from: me.code, kind: b.kind, video: !!b.video, data: String(b.data || '').slice(0, 20000) });
    return send(res, 200, { delivered: n > 0 });
  }

  // ----- security: location sharing (opt-in, only with trusted contacts) -----
  if (p === '/api/location/settings' && M === 'POST') {
    const b = await readJson(req);
    me.sharing = !!b.sharing;
    me.trusted = (Array.isArray(b.trusted) ? b.trusted : []).map(fmt).filter(c => db.users[c] && c !== me.code).slice(0, 50);
    if (!me.sharing) me.loc = null;
    save(); return send(res, 200, { ok: true });
  }
  if (p === '/api/location' && M === 'POST') {
    const b = await readJson(req);
    if (!me.sharing) return fail(res, 409, 'Sharing is off');
    const lat = +b.lat, lng = +b.lng;
    if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return fail(res, 400, 'Bad coordinates');
    me.loc = { lat, lng, acc: +b.acc || 0, ts: Date.now() }; save();
    return send(res, 200, { ok: true });
  }
  if (p.startsWith('/api/location/') && M === 'GET') {
    const u = db.users[fmt(decodeURIComponent(p.slice(14)))];
    if (!u || !u.sharing || !u.trusted.includes(me.code)) return fail(res, 403, 'This person is not sharing location with you');
    if (!u.loc) return fail(res, 404, 'No location received yet');
    return send(res, 200, u.loc);
  }
  if (p === '/api/sos' && M === 'POST') {
    const b = await readJson(req);
    let text = '🆘 SOS from ' + me.name + '! I need help.';
    const lat = +b.lat, lng = +b.lng;
    if (isFinite(lat) && isFinite(lng) && (lat || lng)) text += '\nhttps://maps.google.com/?q=' + lat.toFixed(6) + ',' + lng.toFixed(6);
    for (const c of me.trusted) addMsg(me.code, c, { type: 'text', text });
    return send(res, 200, { sent: me.trusted.length });
  }

  fail(res, 404, 'Not found');
}

const server = http.createServer((req, res) => {
  handle(req, res).catch(e => { if (!res.headersSent) fail(res, e.message === 'Too large' ? 413 : 400, e.message); else res.end(); });
});
server.keepAliveTimeout = 65000;
server.listen(PORT, '0.0.0.0', () => console.log('LiteChat server on :' + PORT + '  data=' + DATA));
