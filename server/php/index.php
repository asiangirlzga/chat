<?php
/**
 * LiteChat PHP server - ONE file for any shared PHP host (PHP 7.4+, no extensions, no database, no composer).
 * Upload this file to your web root (e.g. https://berry.gt.tc/index.php). It creates ./litechat_data by itself.
 * Same API as the Node/Java servers. Differences: no always-open connection on shared hosting, so the app
 * uses long-polling (/api/poll) instead of Server-Sent-Events.
 */
error_reporting(0);
@set_time_limit(40);
ignore_user_abort(false);

const BOOTSTRAP_KEY = '';            // optional: if set, the very first user must type this key in the "friend's code" box
const TURN_URL = '', TURN_USER = '', TURN_PASS = '';   // optional TURN server for calls on strict networks
const ADMIN_KEY = '';                // set this to a password to turn on the admin panel at yoursite.com/?r=admin (leave empty to disable it)
const STUB = "<?php http_response_code(403);exit; ?>\n";

$DATA = __DIR__ . '/litechat_data';
$DBF = $DATA . '/db.php';
if (!is_dir($DATA)) {
    @mkdir($DATA, 0755, true);
    @mkdir($DATA . '/files', 0755, true);
    @file_put_contents($DATA . '/.htaccess', "Require all denied\nDeny from all\n");
    @file_put_contents($DATA . '/files/.htaccess', "php_flag engine off\nRemoveHandler .php .phtml .php5\nRequire all denied\nDeny from all\n");
    @file_put_contents($DATA . '/index.html', '');
}
$ALPHA = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

// ---------- helpers ----------
function out($code, $arr) {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($arr, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}
function fail($code, $msg) { out($code, ['error' => $msg]); }
function fmt($s) {
    $n = strtoupper(preg_replace('/[^A-Za-z0-9]/', '', (string)$s));
    return strlen($n) === 8 ? substr($n, 0, 4) . '-' . substr($n, 4) : $n;
}
function clean($s, $max) { return mb_substr(trim(preg_replace('/[\x00-\x1f]/', ' ', (string)$s)), 0, $max); }
function cfile($code) { return str_replace('-', '', $code); }
function db_read() {
    global $DBF;
    $s = @file_get_contents($DBF);
    $d = $s === false ? null : json_decode(substr($s, strlen(STUB)), true);
    if (!is_array($d)) $d = [];
    return $d + ['seq' => 0, 'users' => [], 'msgs' => [], 'regs' => [], 'hits' => [], 'adm' => []];
}
function db_write($db) {
    global $DBF;
    $tmp = $DBF . '.tmp';
    file_put_contents($tmp, STUB . json_encode($db, JSON_UNESCAPED_UNICODE));
    rename($tmp, $DBF);
}
/** Exclusive lock for read-modify-write requests. */
function lock() { global $DATA; $f = fopen($DATA . '/lock', 'c'); flock($f, LOCK_EX); return $f; }

function add_msg(&$db, $from, $to, $type, $text, $file = '', $fname = '', $mime = '') {
    global $DATA;
    $m = ['id' => ++$db['seq'], 'from' => $from, 'to' => $to, 'type' => $type, 'text' => $text,
          'file' => $file, 'fname' => $fname, 'mime' => $mime, 'ts' => (int)(microtime(true) * 1000)];
    $db['msgs'][] = $m;
    file_put_contents($DATA . '/n_' . cfile($to), (string)$m['id']);                       // wakes the receiver's poll
    if ($from !== 'SYSTEM') file_put_contents($DATA . '/n_' . cfile($from), (string)$m['id']);
    return $m;
}
function new_user(&$db, $name) {
    global $ALPHA;
    do {
        $c = '';
        for ($i = 0; $i < 8; $i++) $c .= $ALPHA[random_int(0, 31)];
        $code = substr($c, 0, 4) . '-' . substr($c, 4);
    } while (isset($db['users'][$code]));
    $token = rtrim(strtr(base64_encode(random_bytes(24)), '+/', '-_'), '=');
    $db['users'][$code] = ['code' => $code, 'name' => $name, 'th' => hash('sha256', $token), 'sharing' => false,
                           'trusted' => [], 'loc' => null, 'created' => time(), 'disabled' => false];
    return [$code, $token];
}
function limited(&$db, $key, $max, $secs) {
    $now = time();
    $a = array_values(array_filter($db['hits'][$key] ?? [], function ($t) use ($now, $secs) { return $now - $t < $secs; }));
    $a[] = $now;
    $db['hits'][$key] = $a;
    return count($a) > $max;
}
function body() {
    $raw = file_get_contents('php://input');
    if (strlen($raw) > 65536) fail(413, 'Too large');
    $j = json_decode($raw, true);
    return is_array($j) ? $j : [];
}
function me_from_token($db) {
    $t = $_SERVER['HTTP_X_TOKEN'] ?? '';
    if ($t === '' && !empty($_SERVER['HTTP_AUTHORIZATION'])) $t = preg_replace('/^Bearer /i', '', $_SERVER['HTTP_AUTHORIZATION']);
    if ($t === '') $t = $_GET['t'] ?? '';
    if ($t === '') return null;
    $h = hash('sha256', $t);
    foreach ($db['users'] as $u) if (hash_equals($u['th'], $h)) return empty($u['disabled']) ? $u['code'] : null;
    return null;
}

// ---------- admin ----------
function admin_token() { return $_COOKIE['ladm'] ?? ''; }
function admin_ok($db) {
    if (ADMIN_KEY === '') return false;
    $t = admin_token();
    return $t !== '' && isset($db['adm'][$t]) && $db['adm'][$t] > time();
}
function h($s) { return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8'); }
/** Minimal, dependency-free admin panel: see registered users, message one or everyone, disable/enable an account.
 *  It deliberately cannot read anyone's private conversations - only messages people send *to* ADMIN (support). */
function admin_page() {
    global $DATA;
    if (ADMIN_KEY === '') { http_response_code(404); echo 'Not found'; exit; }
    $lk = lock();
    $db = db_read();
    $msg = '';

    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $action = $_POST['action'] ?? '';
        if ($action === 'login') {
            if (hash_equals(ADMIN_KEY, (string)($_POST['pass'] ?? ''))) {
                $tok = bin2hex(random_bytes(24));
                $db['adm'] = array_filter($db['adm'] ?? [], function ($e) { return $e > time(); });
                $db['adm'][$tok] = time() + 86400 * 7;
                db_write($db);
                setcookie('ladm', $tok, time() + 86400 * 7, '', '', !empty($_SERVER['HTTPS']), true);
                header('Location: ?r=admin'); exit;
            }
            $msg = 'Wrong password.';
        } elseif (admin_ok($db)) {
            if ($action === 'logout') {
                unset($db['adm'][admin_token()]); db_write($db);
                setcookie('ladm', '', time() - 3600);
                header('Location: ?r=admin'); exit;
            } elseif ($action === 'send') {
                $to = fmt($_POST['to'] ?? '');
                $text = clean($_POST['text'] ?? '', 4000);
                if ($text === '') { $msg = 'Message is empty.'; }
                elseif (!isset($db['users'][$to])) { $msg = 'No such user code.'; }
                else { add_msg($db, 'ADMIN', $to, 'text', $text); db_write($db); $msg = 'Sent to ' . $to . '.'; }
            } elseif ($action === 'broadcast') {
                $text = clean($_POST['text'] ?? '', 4000);
                if ($text === '') { $msg = 'Message is empty.'; }
                else { $n = 0; foreach ($db['users'] as $c => $u) { if ($c !== 'ADMIN') { add_msg($db, 'ADMIN', $c, 'text', $text); $n++; } } db_write($db); $msg = "Sent to $n user(s)."; }
            } elseif ($action === 'toggle') {
                $c = fmt($_POST['code'] ?? '');
                if (isset($db['users'][$c])) { $db['users'][$c]['disabled'] = !($db['users'][$c]['disabled'] ?? false); db_write($db); $msg = ($db['users'][$c]['disabled'] ? 'Disabled ' : 'Enabled ') . $c; }
            }
        }
    }
    if (!admin_ok($db)) {
        header('Content-Type: text/html; charset=utf-8');
        echo '<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>LiteChat admin</title>' .
             '<body style="font-family:sans-serif;max-width:360px;margin:60px auto;padding:0 16px">' .
             '<h2>LiteChat admin</h2>' . ($msg ? '<p style="color:#c00">' . h($msg) . '</p>' : '') .
             '<form method=post><input type=hidden name=action value=login>' .
             '<input name=pass type=password placeholder=Password style="width:100%;padding:10px;box-sizing:border-box" autofocus>' .
             '<button style="width:100%;padding:10px;margin-top:10px">Log in</button></form></body>';
        exit;
    }

    // storage stats (no message content, just counts/sizes)
    $files = @scandir($DATA . '/files') ?: [];
    $fcount = 0; $fsize = 0;
    foreach ($files as $f) { $p = $DATA . '/files/' . $f; if (is_file($p)) { $fcount++; $fsize += filesize($p); } }

    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>LiteChat admin</title>' .
         '<body style="font-family:sans-serif;max-width:720px;margin:24px auto;padding:0 16px;line-height:1.4">' .
         '<div style="display:flex;justify-content:space-between;align-items:center">' .
         '<h2 style="margin:0">LiteChat admin</h2>' .
         '<form method=post><input type=hidden name=action value=logout><button>Log out</button></form></div>';
    if ($msg) echo '<p style="color:#070;font-weight:bold">' . h($msg) . '</p>';
    echo '<p>' . count($db['users']) . ' user(s) · ' . $fcount . ' file(s), ' . round($fsize / 1048576, 1) . ' MB</p>';

    echo '<h3>Message everyone</h3><form method=post>' .
         '<input type=hidden name=action value=broadcast>' .
         '<textarea name=text rows=2 style="width:100%;box-sizing:border-box" placeholder="Announcement to all users…"></textarea>' .
         '<button style="margin-top:6px">Send to everyone</button></form>';

    echo '<h3>Message one user</h3><form method=post style="display:flex;gap:6px;flex-wrap:wrap">' .
         '<input type=hidden name=action value=send>' .
         '<input name=to placeholder="XXXX-XXXX" style="flex:1;min-width:120px;padding:8px">' .
         '<input name=text placeholder="Message" style="flex:3;min-width:200px;padding:8px">' .
         '<button>Send</button></form>';

    echo '<h3>Users</h3><table style="width:100%;border-collapse:collapse"><tr style="text-align:left;border-bottom:2px solid #ccc">' .
         '<th style="padding:6px 4px">Code</th><th>Name</th><th>Joined</th><th>Status</th><th></th></tr>';
    $rows = $db['users'];
    uasort($rows, function ($a, $b) { return ($b['created'] ?? 0) <=> ($a['created'] ?? 0); });
    foreach ($rows as $c => $u) {
        $dis = !empty($u['disabled']);
        echo '<tr style="border-bottom:1px solid #eee">' .
             '<td style="padding:6px 4px;font-family:monospace">' . h($c) . '</td>' .
             '<td>' . h($u['name']) . '</td>' .
             '<td>' . (empty($u['created']) ? '-' : date('Y-m-d', $u['created'])) . '</td>' .
             '<td>' . ($dis ? '<span style="color:#c00">disabled</span>' : 'active') . '</td>' .
             '<td><form method=post style="margin:0"><input type=hidden name=action value=toggle>' .
             '<input type=hidden name=code value="' . h($c) . '">' .
             '<button>' . ($dis ? 'Enable' : 'Disable') . '</button></form></td></tr>';
    }
    echo '</table><p style="color:#888;font-size:13px">This panel cannot read anyone\'s conversations - only messages people send to "ADMIN" (support) show up as normal chat, like any support inbox.</p></body>';
    exit;
}
function parse_size($s) {
    $n = (int)$s; $u = strtolower(substr(trim($s), -1));
    return $u === 'g' ? $n << 30 : ($u === 'm' ? $n << 20 : ($u === 'k' ? $n << 10 : $n));
}

// ---------- routing ----------
$r = '/' . ltrim((string)($_GET['r'] ?? ''), '/');   // accepts r=api/x and r=/api/x (some WAFs dislike a leading slash)
$method = $_SERVER['REQUEST_METHOD'];
$ip = $_SERVER['REMOTE_ADDR'] ?? '?';

if ($r === '/' || $r === '') {
    header('Content-Type: text/plain; charset=utf-8');
    echo "LiteChat PHP server is running.\n";
    exit;
}
if ($r === '/admin') admin_page();

// ----- registration (no token) -----
if ($r === '/api/register/start' && $method === 'POST') {
    $lk = lock();
    $db = db_read();
    $b = body();
    $name = clean($b['name'] ?? '', 40);
    $inv = clean($b['inviter'] ?? '', 64);
    if ($name === '') fail(400, 'Name required');
    if (limited($db, 'reg' . $ip, 10, 3600)) { db_write($db); fail(429, 'Too many attempts, try later'); }
    if (!$db['users']) {                                           // very first user = admin, no OTP
        if (BOOTSTRAP_KEY !== '' && $inv !== BOOTSTRAP_KEY) fail(403, 'Admin key required for the first user');
        [$code, $token] = new_user($db, $name);
        db_write($db);
        out(200, ['first' => true, 'token' => $token, 'code' => $code, 'name' => $name]);
    }
    $inviter = $db['users'][fmt($inv)] ?? null;
    if (!$inviter) fail(404, 'Inviter code not found');
    $now = time();
    $db['regs'] = array_filter($db['regs'], function ($x) use ($now) { return $x['exp'] > $now; });
    $pending = 0;
    foreach ($db['regs'] as $x) if ($x['inviter'] === $inviter['code']) $pending++;
    if ($pending >= 5) fail(429, 'Inviter has too many pending invites');
    $otp = (string)random_int(100000, 999999);
    $regId = bin2hex(random_bytes(6));
    $db['regs'][$regId] = ['name' => $name, 'inviter' => $inviter['code'], 'otp' => $otp, 'exp' => $now + 600, 'tries' => 0];
    add_msg($db, 'SYSTEM', $inviter['code'], 'system', "🔐 $name wants to join LiteChat.\nOTP: $otp\nShare it only if you know them. Valid for 10 minutes.");
    db_write($db);
    out(200, ['regId' => $regId, 'inviter' => ['code' => $inviter['code'], 'name' => $inviter['name']]]);
}
if ($r === '/api/register/verify' && $method === 'POST') {
    $lk = lock();
    $db = db_read();
    $b = body();
    $id = (string)($b['regId'] ?? '');
    $reg = $db['regs'][$id] ?? null;
    if (!$reg || $reg['exp'] < time()) fail(400, 'Request expired, start again');
    if (++$db['regs'][$id]['tries'] > 5) { unset($db['regs'][$id]); db_write($db); fail(429, 'Too many wrong OTPs'); }
    if (trim((string)($b['otp'] ?? '')) !== $reg['otp']) { db_write($db); fail(400, 'Wrong OTP'); }
    unset($db['regs'][$id]);
    [$code, $token] = new_user($db, $reg['name']);
    $inv = $db['users'][$reg['inviter']];
    add_msg($db, 'SYSTEM', $inv['code'], 'joined', $reg['name'] . '|' . $code);
    db_write($db);
    out(200, ['token' => $token, 'code' => $code, 'name' => $reg['name'], 'inviter' => ['code' => $inv['code'], 'name' => $inv['name']]]);
}

// ----- everything else needs a token -----
$db0 = db_read();
$meCode = me_from_token($db0);
if (!$meCode) fail(401, 'Unauthorized');

// long-poll: replaces Server-Sent-Events (no lock needed, only tiny files are read)
if ($r === '/api/poll') {
    $since = (int)($_GET['since'] ?? 0);
    $wait = max(1, min(20, (int)($_GET['wait'] ?? 12)));
    $cf = cfile($meCode);
    $end = time() + $wait;
    $events = [];
    do {
        @touch($DATA . '/seen_' . $cf);
        $sf = $DATA . '/s_' . $cf . '.php';
        if (is_file($sf) && filesize($sf) > strlen(STUB)) {
            $h = fopen($sf, 'c+');
            flock($h, LOCK_EX);
            $raw = stream_get_contents($h);
            ftruncate($h, 0); rewind($h); fwrite($h, STUB);
            flock($h, LOCK_UN); fclose($h);
            foreach (explode("\n", substr($raw, strlen(STUB))) as $line) {
                $e = json_decode($line, true);
                if (is_array($e) && time() - ($e['at'] ?? 0) < 60) { unset($e['at']); $events[] = $e; }
            }
        }
        clearstatcache();
        if ((int)@file_get_contents($DATA . '/n_' . $cf) > $since) $events[] = ['t' => 'msg'];
        if ($events) break;
        usleep(350000);
    } while (time() < $end && !connection_aborted());
    out(200, ['events' => $events]);
}

if ($r === '/api/ice') {
    $ice = [['urls' => 'stun:stun.l.google.com:19302'], ['urls' => 'stun:stun1.l.google.com:19302']];
    if (TURN_URL !== '') $ice[] = ['urls' => TURN_URL, 'username' => TURN_USER, 'credential' => TURN_PASS];
    out(200, ['iceServers' => $ice]);
}

if ($r === '/api/signal' && $method === 'POST') {
    $b = body();
    $to = fmt($b['to'] ?? '');
    if (!isset($db0['users'][$to])) fail(404, 'User not found');
    $kind = (string)($b['kind'] ?? '');
    if (!in_array($kind, ['invite', 'accept', 'reject', 'busy', 'end', 'offer', 'answer', 'ice'], true)) fail(400, 'Bad kind');
    $e = ['t' => 'sig', 'from' => $meCode, 'kind' => $kind, 'video' => !empty($b['video']),
          'data' => substr((string)($b['data'] ?? ''), 0, 20000), 'at' => time()];
    $sf = $DATA . '/s_' . cfile($to) . '.php';
    if (!is_file($sf)) file_put_contents($sf, STUB);
    $h = fopen($sf, 'a');
    flock($h, LOCK_EX); fwrite($h, json_encode($e) . "\n"); flock($h, LOCK_UN); fclose($h);
    $seen = @filemtime($DATA . '/seen_' . cfile($to));
    out(200, ['delivered' => $seen && time() - $seen < 40]);       // receiver polled recently = online
}

// ----- files -----
if ($r === '/api/upload' && $method === 'POST') {
    $max = 50 * 1048576;
    $post = parse_size(ini_get('post_max_size') ?: '8M');
    $len = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($post > 0 && $len > $post) fail(413, 'File too large for this host (limit ' . round($post / 1048576) . ' MB)');
    $name = rawurldecode($_SERVER['HTTP_X_NAME'] ?? 'file');
    $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));
    $ok = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'pdf', 'txt', 'mp4', 'mp3', 'zip'];
    $ext = in_array($ext, $ok, true) ? $ext : 'bin';               // never keep .php etc: uploads must not be executable
    $id = bin2hex(random_bytes(12)) . '.' . $ext;
    $in = fopen('php://input', 'rb');
    $o = fopen($DATA . '/files/' . $id, 'wb');
    $n = 0;
    while (!feof($in)) {
        $chunk = fread($in, 65536);
        if ($chunk === false || $chunk === '') break;
        $n += strlen($chunk);
        if ($n > $max) { fclose($o); @unlink($DATA . '/files/' . $id); fail(413, 'File too large (max 50 MB)'); }
        fwrite($o, $chunk);
    }
    fclose($o);
    if ($n === 0) { @unlink($DATA . '/files/' . $id); fail(400, 'Empty upload (file may be over the host limit)'); }
    out(200, ['file' => '/f/' . $id, 'size' => $n]);
}
if (strpos($r, '/f/') === 0) {
    $id = basename($r);
    if (!preg_match('/^[A-Za-z0-9_-]+\.[a-z0-9]+$/', $id)) fail(400, 'Bad name');
    $p = $DATA . '/files/' . $id;
    if (!is_file($p)) fail(404, 'Not found');
    $mimes = ['jpg' => 'image/jpeg', 'jpeg' => 'image/jpeg', 'png' => 'image/png', 'gif' => 'image/gif', 'webp' => 'image/webp',
              'pdf' => 'application/pdf', 'txt' => 'text/plain', 'mp4' => 'video/mp4', 'mp3' => 'audio/mpeg', 'zip' => 'application/zip'];
    header('Content-Type: ' . ($mimes[pathinfo($id, PATHINFO_EXTENSION)] ?? 'application/octet-stream'));
    header('Content-Length: ' . filesize($p));
    header('X-Content-Type-Options: nosniff');
    header('Cache-Control: private, max-age=31536000');
    if (!empty($_GET['n'])) header("Content-Disposition: attachment; filename*=UTF-8''" . rawurlencode($_GET['n']));
    readfile($p);
    exit;
}

// ----- state-changing / data endpoints (locked read-modify-write) -----
$lk = lock();
$db = db_read();
$me = &$db['users'][$meCode];

if ($r === '/api/me') out(200, ['code' => $me['code'], 'name' => $me['name']]);
if ($r === '/api/profile' && $method === 'POST') {
    $n = clean(body()['name'] ?? '', 40);
    if ($n === '') fail(400, 'Name required');
    $me['name'] = $n; db_write($db); out(200, ['ok' => true]);
}
if (strpos($r, '/api/user/') === 0) {
    $u = $db['users'][fmt(substr($r, 10))] ?? null;
    if (!$u) fail(404, 'Code not found');
    out(200, ['code' => $u['code'], 'name' => $u['name']]);
}
if ($r === '/api/send' && $method === 'POST') {
    $b = body();
    $to = fmt($b['to'] ?? '');
    if (!isset($db['users'][$to])) fail(404, 'Recipient not found');
    $type = in_array($b['type'] ?? '', ['text', 'image', 'file', 'call'], true) ? $b['type'] : 'text';
    $file = clean($b['file'] ?? '', 80);
    if ($type !== 'text' && $type !== 'call' && !preg_match('#^/f/[A-Za-z0-9_-]{8,40}(\.[a-z0-9]{1,8})?$#', $file)) fail(400, 'Bad file');
    $text = mb_substr((string)($b['text'] ?? ''), 0, 4000);
    if ($type === 'text' && trim($text) === '') fail(400, 'Empty message');
    $m = add_msg($db, $meCode, $to, $type, $text, $file, clean($b['fname'] ?? '', 120), clean($b['mime'] ?? '', 80));
    db_write($db);
    out(200, ['msg' => $m]);
}
if ($r === '/api/sync') {
    $since = (int)($_GET['since'] ?? 0);
    $res = []; $more = false;
    foreach ($db['msgs'] as $m) {
        if ($m['id'] <= $since || ($m['to'] !== $meCode && $m['from'] !== $meCode)) continue;
        if (count($res) >= 300) { $more = true; break; }
        $res[] = $m;
    }
    out(200, ['msgs' => $res, 'more' => $more]);
}

// ----- security: opt-in location -----
if ($r === '/api/location/settings' && $method === 'POST') {
    $b = body();
    $me['sharing'] = !empty($b['sharing']);
    $t = [];
    foreach (($b['trusted'] ?? []) as $c) {
        $c = fmt($c);
        if (isset($db['users'][$c]) && $c !== $meCode && !in_array($c, $t, true) && count($t) < 50) $t[] = $c;
    }
    $me['trusted'] = $t;
    if (!$me['sharing']) $me['loc'] = null;
    db_write($db); out(200, ['ok' => true]);
}
if ($r === '/api/location' && $method === 'POST') {
    $b = body();
    if (!$me['sharing']) fail(409, 'Sharing is off');
    $lat = (float)($b['lat'] ?? 999); $lng = (float)($b['lng'] ?? 999);
    if (abs($lat) > 90 || abs($lng) > 180) fail(400, 'Bad coordinates');
    $me['loc'] = ['lat' => $lat, 'lng' => $lng, 'acc' => (float)($b['acc'] ?? 0), 'ts' => (int)(microtime(true) * 1000)];
    db_write($db); out(200, ['ok' => true]);
}
if (strpos($r, '/api/location/') === 0) {
    $u = $db['users'][fmt(substr($r, 14))] ?? null;
    if (!$u || !$u['sharing'] || !in_array($meCode, $u['trusted'], true)) fail(403, 'This person is not sharing location with you');
    if (!$u['loc']) fail(404, 'No location received yet');
    out(200, $u['loc']);
}
if ($r === '/api/sos' && $method === 'POST') {
    $b = body();
    $text = '🆘 SOS from ' . $me['name'] . '! I need help.';
    $lat = (float)($b['lat'] ?? 0); $lng = (float)($b['lng'] ?? 0);
    if ($lat != 0 || $lng != 0) $text .= "\nhttps://maps.google.com/?q=" . sprintf('%.6f,%.6f', $lat, $lng);
    foreach ($me['trusted'] as $c) add_msg($db, $meCode, $c, 'text', $text);
    db_write($db);
    out(200, ['sent' => count($me['trusted'])]);
}

fail(404, 'Not found');
