package app.litechat;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The LiteChat server, built into the app (pure Java, no libraries) so no PC / Termux is needed.
 * Same API as server/server.js. Started from the setup screen ("Host the server on this phone")
 * and kept alive by CoreService.
 */
final class LocalServer {
    static final int PORT = 8080;
    private static final long MAXUP = 50L * 1024 * 1024;
    private static final String ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static LocalServer inst;
    static synchronized void start() {
        if (inst != null) return;
        try { LocalServer s = new LocalServer(); s.open(); inst = s; }
        catch (Exception e) { Ui.toast("Server could not start: " + e.getMessage()); }
    }
    static synchronized void stop() { if (inst != null) { inst.close(); inst = null; } }
    static synchronized boolean running() { return inst != null; }

    /** The phone's Wi-Fi / hotspot address that friends should type into their app. */
    static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return "<phone-ip>";
    }

    // ---------- data ----------
    private static class User { String code, name, th; boolean sharing; List<String> trusted = new ArrayList<>(); double lat, lng, acc; long locTs; }
    private static class Msg {
        long id, ts; String from, to, type, text, file, fname, mime;
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("from", from).put("to", to).put("type", type).put("text", text)
                    .put("file", file).put("fname", fname).put("mime", mime).put("ts", ts);
        }
    }
    private static class Reg { String name, inviter, otp; long exp; int tries; }
    private static class Client {
        final OutputStream out;
        Client(OutputStream o) { out = o; }
        synchronized boolean write(String s) { try { out.write(s.getBytes(StandardCharsets.UTF_8)); out.flush(); return true; } catch (IOException e) { return false; } }
    }
    private static class HttpErr extends Exception { final int code; HttpErr(int c, String m) { super(m); code = c; } }

    private final File dbFile, filesDir;
    private final Map<String, User> users = new HashMap<>();
    private final Map<String, String> tokenIdx = new HashMap<>();
    private final List<Msg> msgs = new ArrayList<>();
    private final Map<String, List<Client>> clients = new HashMap<>();
    private final Map<String, Reg> regs = new HashMap<>();
    private final Map<String, List<Long>> hits = new HashMap<>();
    private long seq;
    private boolean dirty;
    private final SecureRandom sr = new SecureRandom();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket ss;
    private volatile boolean alive;

    private LocalServer() throws Exception {
        File base = new File(App.ctx.getFilesDir(), "server");
        filesDir = new File(base, "files");
        filesDir.mkdirs();
        dbFile = new File(base, "db.json");
        load();
    }

    private void open() throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(PORT));
        alive = true;
        new Thread(() -> {
            while (alive) {
                try { final Socket s = ss.accept(); pool.execute(() -> handle(s)); }
                catch (IOException e) { if (!alive) break; }
            }
        }, "srv-accept").start();
        new Thread(() -> {                                    // save every 2 s, heartbeat every 25 s
            int tick = 0;
            while (alive) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                save();
                if (++tick % 12 == 0) heartbeat();
            }
        }, "srv-maint").start();
    }

    private void close() {
        alive = false;
        try { ss.close(); } catch (Exception ignored) { }
        save();
        pool.shutdownNow();
    }

    // ---------- persistence ----------
    private void load() throws Exception {
        if (!dbFile.exists()) return;
        byte[] b = new byte[(int) dbFile.length()];
        try (FileInputStream in = new FileInputStream(dbFile)) { int o = 0, n; while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n; }
        JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
        seq = j.optLong("seq");
        JSONArray ua = j.optJSONArray("users"), ma = j.optJSONArray("msgs");
        for (int i = 0; ua != null && i < ua.length(); i++) {
            JSONObject o = ua.getJSONObject(i);
            User u = new User();
            u.code = o.getString("code"); u.name = o.getString("name"); u.th = o.getString("th");
            u.sharing = o.optBoolean("sharing");
            JSONArray t = o.optJSONArray("trusted");
            for (int k = 0; t != null && k < t.length(); k++) u.trusted.add(t.getString(k));
            u.lat = o.optDouble("lat", 0); u.lng = o.optDouble("lng", 0); u.acc = o.optDouble("acc", 0); u.locTs = o.optLong("locTs");
            users.put(u.code, u); tokenIdx.put(u.th, u.code);
        }
        for (int i = 0; ma != null && i < ma.length(); i++) {
            JSONObject o = ma.getJSONObject(i);
            Msg m = new Msg();
            m.id = o.getLong("id"); m.from = o.getString("from"); m.to = o.getString("to"); m.type = o.getString("type");
            m.text = o.optString("text"); m.file = o.optString("file"); m.fname = o.optString("fname"); m.mime = o.optString("mime"); m.ts = o.getLong("ts");
            msgs.add(m);
        }
    }

    private void save() {
        String data;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            try {
                JSONArray ua = new JSONArray(), ma = new JSONArray();
                for (User u : users.values()) {
                    ua.put(new JSONObject().put("code", u.code).put("name", u.name).put("th", u.th).put("sharing", u.sharing)
                            .put("trusted", new JSONArray(u.trusted)).put("lat", u.lat).put("lng", u.lng).put("acc", u.acc).put("locTs", u.locTs));
                }
                for (Msg m : msgs) ma.put(m.json());
                data = new JSONObject().put("seq", seq).put("users", ua).put("msgs", ma).toString();
            } catch (Exception e) { dirty = true; return; }
        }
        try {
            File tmp = new File(dbFile.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data.getBytes(StandardCharsets.UTF_8)); }
            tmp.renameTo(dbFile);
        } catch (IOException e) { synchronized (this) { dirty = true; } }
    }

    // ---------- helpers ----------
    private String rnd(int n) {
        byte[] b = new byte[n];
        sr.nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static String fmt(String s) { return Ui.fmt(s); }
    private static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("[\\u0000-\\u001f]", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
    private synchronized boolean limited(String key, int max, long ms) {
        long now = System.currentTimeMillis();
        List<Long> a = hits.get(key);
        if (a == null) { a = new ArrayList<>(); hits.put(key, a); }
        for (int i = a.size() - 1; i >= 0; i--) if (now - a.get(i) > ms) a.remove(i);
        a.add(now);
        return a.size() > max;
    }
    private synchronized String[] createUser(String name) {
        String code;
        do {
            StringBuilder c = new StringBuilder();
            for (int i = 0; i < 8; i++) c.append(ALPHA.charAt(sr.nextInt(ALPHA.length())));
            code = c.substring(0, 4) + "-" + c.substring(4);
        } while (users.containsKey(code));
        String token = rnd(24);
        User u = new User();
        u.code = code; u.name = name; u.th = sha(token);
        users.put(code, u); tokenIdx.put(u.th, code); dirty = true;
        return new String[]{code, token};
    }
    private synchronized Msg addMsg(String from, String to, String type, String text, String file, String fname, String mime) {
        Msg m = new Msg();
        m.id = ++seq; m.from = from; m.to = to; m.type = type; m.text = text; m.file = file; m.fname = fname; m.mime = mime; m.ts = System.currentTimeMillis();
        msgs.add(m); dirty = true;
        push(to, "{\"t\":\"msg\"}");
        if (!from.equals("SYSTEM")) push(from, "{\"t\":\"msg\"}");
        return m;
    }
    private synchronized int push(String code, String json) {
        List<Client> l = clients.get(code);
        if (l == null) return 0;
        int n = 0;
        for (int i = l.size() - 1; i >= 0; i--) {
            if (l.get(i).write("data: " + json + "\n\n")) n++; else l.remove(i);
        }
        return n;
    }
    private synchronized void heartbeat() {
        for (List<Client> l : clients.values())
            for (int i = l.size() - 1; i >= 0; i--) if (!l.get(i).write(": hb\n\n")) l.remove(i);
    }

    // ---------- tiny HTTP ----------
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) throw new IOException("line too long");
        }
        return (c == -1 && sb.length() == 0) ? null : sb.toString();
    }

    private static class Bounded extends InputStream {
        private final InputStream in; private long left;
        Bounded(InputStream in, long n) { this.in = in; left = n; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int o, int l) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, o, (int) Math.min(l, left));
            if (n > 0) left -= n;
            return n;
        }
    }
    private static class Chunked extends InputStream {
        private final InputStream in; private int left; private boolean done;
        Chunked(InputStream in) { this.in = in; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int o, int l) throws IOException {
            if (done) return -1;
            if (left == 0) {
                String sz = readLine(in);
                if (sz == null) { done = true; return -1; }
                int sc = sz.indexOf(';');
                if (sc >= 0) sz = sz.substring(0, sc);
                left = Integer.parseInt(sz.trim(), 16);
                if (left == 0) { String t; while ((t = readLine(in)) != null && !t.isEmpty()) { } done = true; return -1; }
            }
            int n = in.read(b, o, Math.min(l, left));
            if (n < 0) { done = true; return -1; }
            left -= n;
            if (left == 0) readLine(in);
            return n;
        }
    }

    private static String reason(int c) {
        switch (c) { case 200: return "OK"; case 400: return "Bad Request"; case 401: return "Unauthorized"; case 403: return "Forbidden";
            case 404: return "Not Found"; case 409: return "Conflict"; case 413: return "Payload Too Large"; case 429: return "Too Many Requests"; default: return "Error"; }
    }
    private static void send(OutputStream out, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " " + reason(code) + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = s.getOutputStream();
            try {
                String line = readLine(in);
                if (line == null) return;
                String[] p = line.split(" ");
                if (p.length < 2) return;
                Map<String, String> h = new HashMap<>();
                String l;
                while ((l = readLine(in)) != null && !l.isEmpty()) {
                    int k = l.indexOf(':');
                    if (k > 0) h.put(l.substring(0, k).trim().toLowerCase(Locale.ROOT), l.substring(k + 1).trim());
                }
                String target = p[1], path = target, query = "";
                int qi = target.indexOf('?');
                if (qi >= 0) { path = target.substring(0, qi); query = target.substring(qi + 1); }
                Map<String, String> q = new HashMap<>();
                for (String kv : query.split("&")) {
                    if (kv.isEmpty()) continue;
                    int e = kv.indexOf('=');
                    String k = e < 0 ? kv : kv.substring(0, e), v = e < 0 ? "" : kv.substring(e + 1);
                    q.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
                }
                InputStream body;
                if ("chunked".equalsIgnoreCase(h.get("transfer-encoding"))) body = new Chunked(in);
                else body = new Bounded(in, h.containsKey("content-length") ? Long.parseLong(h.get("content-length")) : 0);
                route(s, p[0], path, q, h, body, out, s.getInetAddress().getHostAddress());
            } catch (HttpErr e) {
                send(out, e.code, new JSONObject().put("error", e.getMessage()).toString());
            }
        } catch (Exception ignored) {
        } finally { try { s.close(); } catch (IOException ignored) { } }
    }

    private static JSONObject readJson(InputStream body) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = body.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 65536) throw new HttpErr(413, "Too large"); }
        String s = bo.toString("UTF-8").trim();
        try { return s.isEmpty() ? new JSONObject() : new JSONObject(s); } catch (Exception e) { throw new HttpErr(400, "Bad JSON"); }
    }

    private synchronized User auth(Map<String, String> h, Map<String, String> q) {
        String t = h.containsKey("authorization") ? h.get("authorization").replaceFirst("(?i)^Bearer ", "") : "";
        if (t.isEmpty()) t = q.containsKey("t") ? q.get("t") : "";
        String code = tokenIdx.get(sha(t));
        return code == null ? null : users.get(code);
    }

    // ---------- routes (same API as server.js) ----------
    private void route(Socket s, String method, String path, Map<String, String> q, Map<String, String> h,
                       InputStream body, OutputStream out, String ip) throws Exception {
        boolean get = method.equals("GET"), post = method.equals("POST");

        if (path.equals("/") && get) {
            byte[] b = ("LiteChat server (running inside the app). Users: " + users.size() + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes());
            out.write(b); out.flush();
            return;
        }

        if (path.equals("/api/register/start") && post) {
            JSONObject b = readJson(body);
            String name = clean(b.optString("name"), 40), inv = clean(b.optString("inviter"), 64);
            if (name.isEmpty()) throw new HttpErr(400, "Name required");
            if (limited("reg" + ip, 10, 3600000L)) throw new HttpErr(429, "Too many attempts, try later");
            synchronized (this) {
                if (users.isEmpty()) {
                    String[] u = createUser(name);
                    send(out, 200, new JSONObject().put("first", true).put("token", u[1]).put("code", u[0]).put("name", name).toString());
                    return;
                }
                User inviter = users.get(fmt(inv));
                if (inviter == null) throw new HttpErr(404, "Inviter code not found");
                int pending = 0;
                for (Reg r : regs.values()) if (r.inviter.equals(inviter.code) && r.exp > System.currentTimeMillis()) pending++;
                if (pending >= 5) throw new HttpErr(429, "Inviter has too many pending invites");
                Reg r = new Reg();
                r.name = name; r.inviter = inviter.code; r.otp = String.valueOf(100000 + sr.nextInt(900000));
                r.exp = System.currentTimeMillis() + 600000;
                String regId = rnd(9);
                regs.put(regId, r);
                addMsg("SYSTEM", inviter.code, "system", "🔐 " + name + " wants to join LiteChat.\nOTP: " + r.otp
                        + "\nShare it only if you know them. Valid for 10 minutes.", "", "", "");
                send(out, 200, new JSONObject().put("regId", regId)
                        .put("inviter", new JSONObject().put("code", inviter.code).put("name", inviter.name)).toString());
            }
            return;
        }
        if (path.equals("/api/register/verify") && post) {
            JSONObject b = readJson(body);
            synchronized (this) {
                String id = b.optString("regId");
                Reg r = regs.get(id);
                if (r == null || r.exp < System.currentTimeMillis()) throw new HttpErr(400, "Request expired, start again");
                if (++r.tries > 5) { regs.remove(id); throw new HttpErr(429, "Too many wrong OTPs"); }
                if (!b.optString("otp").trim().equals(r.otp)) throw new HttpErr(400, "Wrong OTP");
                regs.remove(id);
                String[] u = createUser(r.name);
                User inviter = users.get(r.inviter);
                addMsg("SYSTEM", inviter.code, "joined", r.name + "|" + u[0], "", "", "");
                send(out, 200, new JSONObject().put("token", u[1]).put("code", u[0]).put("name", r.name)
                        .put("inviter", new JSONObject().put("code", inviter.code).put("name", inviter.name)).toString());
            }
            return;
        }

        // ----- everything else needs a token -----
        final User me = auth(h, q);
        if (me == null) throw new HttpErr(401, "Unauthorized");

        if (path.equals("/api/events") && get) {
            s.setSoTimeout(0);
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\nretry: 3000\n\n".getBytes());
            out.flush();
            Client c = new Client(out);
            synchronized (this) {
                List<Client> l = clients.get(me.code);
                if (l == null) { l = new ArrayList<>(); clients.put(me.code, l); }
                l.add(c);
            }
            try { while (alive && s.getInputStream().read() != -1) { } } catch (IOException ignored) { }
            synchronized (this) { List<Client> l = clients.get(me.code); if (l != null) l.remove(c); }
            return;
        }
        if (path.equals("/api/me")) {
            send(out, 200, new JSONObject().put("code", me.code).put("name", me.name).toString());
            return;
        }
        if (path.equals("/api/profile") && post) {
            String name = clean(readJson(body).optString("name"), 40);
            if (name.isEmpty()) throw new HttpErr(400, "Name required");
            synchronized (this) { me.name = name; dirty = true; }
            send(out, 200, "{\"ok\":true}");
            return;
        }
        if (path.startsWith("/api/user/") && get) {
            User u;
            synchronized (this) { u = users.get(fmt(URLDecoder.decode(path.substring(10), "UTF-8"))); }
            if (u == null) throw new HttpErr(404, "Code not found");
            send(out, 200, new JSONObject().put("code", u.code).put("name", u.name).toString());
            return;
        }

        if (path.equals("/api/send") && post) {
            JSONObject b = readJson(body);
            String to = fmt(b.optString("to"));
            String type = b.optString("type");
            if (!type.equals("text") && !type.equals("image") && !type.equals("file") && !type.equals("call")) type = "text";
            String file = clean(b.optString("file"), 80), text = b.optString("text");
            if (text.length() > 4000) text = text.substring(0, 4000);
            synchronized (this) {
                if (!users.containsKey(to)) throw new HttpErr(404, "Recipient not found");
                if (!type.equals("text") && !type.equals("call") && !file.matches("^/f/[A-Za-z0-9_-]{8,40}(\\.[a-z0-9]{1,8})?$")) throw new HttpErr(400, "Bad file");
                if (type.equals("text") && text.trim().isEmpty()) throw new HttpErr(400, "Empty message");
                Msg m = addMsg(me.code, to, type, text, file, clean(b.optString("fname"), 120), clean(b.optString("mime"), 80));
                send(out, 200, new JSONObject().put("msg", m.json()).toString());
            }
            return;
        }
        if (path.equals("/api/sync") && get) {
            long since = 0;
            try { since = Long.parseLong(q.get("since")); } catch (Exception ignored) { }
            JSONArray arr = new JSONArray();
            boolean more = false;
            synchronized (this) {
                int lo = 0, hi = msgs.size();
                while (lo < hi) { int mid = (lo + hi) >>> 1; if (msgs.get(mid).id <= since) lo = mid + 1; else hi = mid; }
                for (int i = lo; i < msgs.size(); i++) {
                    Msg m = msgs.get(i);
                    if (!m.to.equals(me.code) && !m.from.equals(me.code)) continue;
                    if (arr.length() >= 300) { more = true; break; }
                    arr.put(m.json());
                }
            }
            send(out, 200, new JSONObject().put("msgs", arr).put("more", more).toString());
            return;
        }

        // ----- files -----
        if (path.equals("/api/upload") && post) {
            String name = URLDecoder.decode(h.containsKey("x-name") ? h.get("x-name") : "file", "UTF-8");
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") : "";
            if (ext.length() > 8) ext = ext.substring(0, 8);
            String id = rnd(18) + (ext.isEmpty() ? "" : "." + ext);
            File f = new File(filesDir, id);
            long n = 0;
            try (FileOutputStream o = new FileOutputStream(f)) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = body.read(buf)) > 0) {
                    n += r;
                    if (n > MAXUP) { o.close(); f.delete(); throw new HttpErr(413, "File too large (max 50 MB)"); }
                    o.write(buf, 0, r);
                }
            }
            send(out, 200, new JSONObject().put("file", "/f/" + id).put("size", n).toString());
            return;
        }
        if (path.startsWith("/f/") && get) {
            String id = path.substring(3);
            if (!id.matches("^[A-Za-z0-9_-]+(\\.[a-z0-9]+)?$")) throw new HttpErr(400, "Bad name");
            File f = new File(filesDir, id);
            if (!f.isFile()) throw new HttpErr(404, "Not found");
            String ext = id.contains(".") ? id.substring(id.lastIndexOf('.') + 1) : "";
            String ct = mime(ext), dl = q.get("n");
            StringBuilder hd = new StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: " + ct + "\r\nContent-Length: " + f.length()
                    + "\r\nCache-Control: private, max-age=31536000\r\nConnection: close\r\n");
            if (dl != null) hd.append("Content-Disposition: attachment; filename*=UTF-8''").append(URLEncoder.encode(dl, "UTF-8").replace("+", "%20")).append("\r\n");
            out.write(hd.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            out.flush();
            return;
        }

        // ----- calls -----
        if (path.equals("/api/ice")) {
            JSONArray a = new JSONArray()
                    .put(new JSONObject().put("urls", "stun:stun.l.google.com:19302"))
                    .put(new JSONObject().put("urls", "stun:stun1.l.google.com:19302"));
            send(out, 200, new JSONObject().put("iceServers", a).toString());
            return;
        }
        if (path.equals("/api/signal") && post) {
            JSONObject b = readJson(body);
            String to = fmt(b.optString("to")), kind = b.optString("kind");
            if (!kind.matches("invite|accept|reject|busy|end|offer|answer|ice")) throw new HttpErr(400, "Bad kind");
            String data = b.optString("data");
            if (data.length() > 20000) data = data.substring(0, 20000);
            int n;
            synchronized (this) {
                if (!users.containsKey(to)) throw new HttpErr(404, "User not found");
                n = push(to, new JSONObject().put("t", "sig").put("from", me.code).put("kind", kind)
                        .put("video", b.optBoolean("video")).put("data", data).toString());
            }
            send(out, 200, new JSONObject().put("delivered", n > 0).toString());
            return;
        }

        // ----- security: opt-in location -----
        if (path.equals("/api/location/settings") && post) {
            JSONObject b = readJson(body);
            synchronized (this) {
                me.sharing = b.optBoolean("sharing");
                me.trusted.clear();
                JSONArray t = b.optJSONArray("trusted");
                for (int i = 0; t != null && i < t.length() && me.trusted.size() < 50; i++) {
                    String c = fmt(t.getString(i));
                    if (users.containsKey(c) && !c.equals(me.code) && !me.trusted.contains(c)) me.trusted.add(c);
                }
                if (!me.sharing) me.locTs = 0;
                dirty = true;
            }
            send(out, 200, "{\"ok\":true}");
            return;
        }
        if (path.equals("/api/location") && post) {
            JSONObject b = readJson(body);
            double lat = b.optDouble("lat", 999), lng = b.optDouble("lng", 999);
            synchronized (this) {
                if (!me.sharing) throw new HttpErr(409, "Sharing is off");
                if (Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new HttpErr(400, "Bad coordinates");
                me.lat = lat; me.lng = lng; me.acc = b.optDouble("acc", 0); me.locTs = System.currentTimeMillis(); dirty = true;
            }
            send(out, 200, "{\"ok\":true}");
            return;
        }
        if (path.startsWith("/api/location/") && get) {
            synchronized (this) {
                User u = users.get(fmt(URLDecoder.decode(path.substring(14), "UTF-8")));
                if (u == null || !u.sharing || !u.trusted.contains(me.code)) throw new HttpErr(403, "This person is not sharing location with you");
                if (u.locTs == 0) throw new HttpErr(404, "No location received yet");
                send(out, 200, new JSONObject().put("lat", u.lat).put("lng", u.lng).put("acc", u.acc).put("ts", u.locTs).toString());
            }
            return;
        }
        if (path.equals("/api/sos") && post) {
            JSONObject b = readJson(body);
            double lat = b.optDouble("lat", 0), lng = b.optDouble("lng", 0);
            String text = "🆘 SOS from " + me.name + "! I need help.";
            if (lat != 0 || lng != 0) text += "\nhttps://maps.google.com/?q=" + String.format(Locale.US, "%.6f,%.6f", lat, lng);
            int n;
            synchronized (this) {
                List<String> to = new ArrayList<>(me.trusted);
                for (String c : to) addMsg(me.code, c, "text", text, "", "", "");
                n = to.size();
            }
            send(out, 200, new JSONObject().put("sent", n).toString());
            return;
        }
        throw new HttpErr(404, "Not found");
    }

    private static String mime(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "zip": return "application/zip";
            default: return "application/octet-stream";
        }
    }
}
