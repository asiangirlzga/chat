package app.litechat;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-wide logic: message sync, sending, call signalling. Used by the service and the screens. */
final class Core {
    interface Listener { void changed(); }

    static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Code of the chat that is on screen right now ("" if none): no notification for it. */
    static volatile String openChat = "";
    private static final ExecutorService sigQueue = Executors.newSingleThreadExecutor(); // keeps signal order

    static void fire() {
        App.ui.post(new Runnable() {
            @Override public void run() { for (Listener l : listeners) l.changed(); }
        });
    }

    // ---------- messages ----------
    static synchronized void sync() throws Exception {
        boolean changed = false;
        String me = Prefs.get("code");
        for (;;) {
            JSONObject r = Api.call("GET", "/api/sync?since=" + Prefs.lastId(), null);
            JSONArray a = r.getJSONArray("msgs");
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.getJSONObject(i);
                if (Db.get().addMsg(m, me)) { changed = true; incoming(m, me); }
                long id = m.getLong("id");
                if (id > Prefs.lastId()) Prefs.lastId(id);
            }
            if (a.length() == 0 || !r.optBoolean("more")) break;
        }
        if (changed) fire();
    }

    private static void incoming(JSONObject m, String me) {
        String from = m.optString("from"), type = m.optString("type"), text = m.optString("text");
        if (from.equals(me)) return;
        if (type.equals("joined")) {
            int k = text.lastIndexOf('|');
            if (k > 0) {
                String name = text.substring(0, k), code = text.substring(k + 1);
                if (Db.get().contact(code) == null) {
                    Db.Contact c = new Db.Contact();
                    c.code = code; c.name = name;
                    Db.get().saveContact(c);
                }
                Notif.message("SYSTEM", "LiteChat", name + " joined and was added to your phone book");
            }
            return;
        }
        if (from.equals(openChat)) return;
        String body = type.equals("image") ? "📷 Photo" : type.equals("file") ? "📄 " + m.optString("fname") : text;
        Notif.message(from, Db.get().nameOf(from), body);
    }

    static void send(String to, String type, String text, String file, String fname, String mime) throws Exception {
        JSONObject b = new JSONObject().put("to", to).put("type", type).put("text", text)
                .put("file", file).put("fname", fname).put("mime", mime);
        JSONObject m = Api.call("POST", "/api/send", b).getJSONObject("msg");
        Db.get().addMsg(m, Prefs.get("code"));
        fire();
    }

    static void logCall(final String peer, final String text) {
        App.io.execute(new Runnable() {
            @Override public void run() { try { send(peer, "call", text, "", "", ""); } catch (Exception ignored) { } }
        });
    }

    // ---------- call signalling ----------
    static boolean signalSync(String to, String kind, boolean video, String data) throws Exception {
        JSONObject b = new JSONObject().put("to", to).put("kind", kind).put("video", video).put("data", data);
        return Api.call("POST", "/api/signal", b).optBoolean("delivered");
    }

    static void signal(final String to, final String kind, final boolean video, final String data) {
        sigQueue.execute(new Runnable() {
            @Override public void run() { try { signalSync(to, kind, video, data); } catch (Exception ignored) { } }
        });
    }

    /** Called from the service thread for every call signal pushed by the server. */
    static void onSignal(JSONObject s) {
        String from = s.optString("from"), kind = s.optString("kind");
        boolean video = s.optBoolean("video");
        CallActivity cur = CallActivity.cur;
        if (cur != null && cur.peer.equals(from)) {
            cur.onSignal(kind, s.optString("data"));
            return;
        }
        if (kind.equals("invite")) {
            if (cur != null) { signal(from, "busy", video, ""); return; }
            Intent i = new Intent(App.ctx, CallActivity.class)
                    .putExtra("peer", from).putExtra("video", video).putExtra("incoming", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Notif.incomingCall(from, video, i);
            try { App.ctx.startActivity(i); } catch (Exception ignored) { } // works when app is in the foreground
        } else if (kind.equals("end") || kind.equals("reject")) {
            Notif.cancelCall();
        }
    }
}
