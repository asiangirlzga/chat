package app.litechat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Local storage: phone book (contacts) + message history. */
final class Db extends SQLiteOpenHelper {
    static class Contact { String code = "", name = "", img = ""; }
    static class Msg {
        long id, ts; boolean seen;
        String peer = "", from = "", type = "", text = "", file = "", fname = "", mime = "";
    }
    /** One line of the chat list. */
    static class Row { String code, name, img; Msg last; int unread; boolean saved; }

    private static Db inst;
    static synchronized Db get() { if (inst == null) inst = new Db(App.ctx); return inst; }
    static synchronized void reset() { if (inst != null) inst.close(); inst = null; App.ctx.deleteDatabase("lc.db"); }

    private Db(Context c) { super(c, "lc.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE contacts(code TEXT PRIMARY KEY, name TEXT, img TEXT)");
        d.execSQL("CREATE TABLE msgs(id INTEGER PRIMARY KEY, peer TEXT, frm TEXT, type TEXT, txt TEXT, file TEXT," +
                " fname TEXT, mime TEXT, ts INTEGER, seen INTEGER DEFAULT 0)");
        d.execSQL("CREATE INDEX i_peer ON msgs(peer, id)");
    }
    @Override public void onUpgrade(SQLiteDatabase d, int a, int b) { }

    // ---------- phone book ----------
    synchronized void saveContact(Contact c) {
        ContentValues v = new ContentValues();
        v.put("code", c.code); v.put("name", c.name); v.put("img", c.img);
        getWritableDatabase().insertWithOnConflict("contacts", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Ui.avatars.evictAll();
    }
    synchronized Contact contact(String code) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,name,img FROM contacts WHERE code=?", new String[]{code})) {
            if (!c.moveToFirst()) return null;
            Contact o = new Contact();
            o.code = c.getString(0); o.name = c.getString(1); o.img = c.getString(2) == null ? "" : c.getString(2);
            return o;
        }
    }
    synchronized List<Contact> contacts() {
        List<Contact> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,name,img FROM contacts ORDER BY name COLLATE NOCASE", null)) {
            while (c.moveToNext()) {
                Contact o = new Contact();
                o.code = c.getString(0); o.name = c.getString(1); o.img = c.getString(2) == null ? "" : c.getString(2);
                l.add(o);
            }
        }
        return l;
    }
    synchronized void deleteContact(String code) {
        getWritableDatabase().delete("contacts", "code=?", new String[]{code});
        Ui.avatars.evictAll();
    }
    String nameOf(String code) {
        if (code.equals("SYSTEM")) return "Invites & OTP";
        Contact c = contact(code);
        return c != null && !c.name.isEmpty() ? c.name : code;
    }

    // ---------- messages ----------
    /** Returns true when the message was new. */
    synchronized boolean addMsg(JSONObject m, String me) {
        String from = m.optString("from"), to = m.optString("to");
        ContentValues v = new ContentValues();
        v.put("id", m.optLong("id"));
        v.put("peer", from.equals(me) ? to : from);
        v.put("frm", from);
        v.put("type", m.optString("type"));
        v.put("txt", m.optString("text"));
        v.put("file", m.optString("file"));
        v.put("fname", m.optString("fname"));
        v.put("mime", m.optString("mime"));
        v.put("ts", m.optLong("ts"));
        v.put("seen", from.equals(me) ? 1 : 0);
        return getWritableDatabase().insertWithOnConflict("msgs", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }
    private Msg read(Cursor c) {
        Msg m = new Msg();
        m.id = c.getLong(0); m.peer = c.getString(1); m.from = c.getString(2); m.type = c.getString(3);
        m.text = c.getString(4); m.file = c.getString(5); m.fname = c.getString(6); m.mime = c.getString(7);
        m.ts = c.getLong(8); m.seen = c.getInt(9) == 1;
        return m;
    }
    private static final String COLS = "id,peer,frm,type,txt,file,fname,mime,ts,seen";

    synchronized List<Msg> msgs(String peer) {
        List<Msg> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM msgs WHERE peer=? ORDER BY id DESC LIMIT 500", new String[]{peer})) {
            while (c.moveToNext()) l.add(read(c));
        }
        Collections.reverse(l);
        return l;
    }
    synchronized Msg last(String peer) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM msgs WHERE peer=? ORDER BY id DESC LIMIT 1", new String[]{peer})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }
    synchronized int unread(String peer) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM msgs WHERE peer=? AND seen=0", new String[]{peer})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    synchronized void markSeen(String peer) {
        ContentValues v = new ContentValues();
        v.put("seen", 1);
        getWritableDatabase().update("msgs", v, "peer=? AND seen=0", new String[]{peer});
    }

    /** Chat list = phone book + anyone we have messages with (even if not saved yet). */
    synchronized List<Row> rows() {
        List<Row> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Contact c : contacts()) {
            Row r = new Row();
            r.code = c.code; r.name = c.name.isEmpty() ? c.code : c.name; r.img = c.img; r.saved = true;
            out.add(r); seen.add(c.code);
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT peer FROM msgs", null)) {
            while (c.moveToNext()) {
                String p = c.getString(0);
                if (seen.contains(p)) continue;
                Row r = new Row();
                r.code = p; r.name = nameOf(p); r.img = ""; r.saved = false;
                out.add(r);
            }
        }
        for (Row r : out) { r.last = last(r.code); r.unread = unread(r.code); }
        Collections.sort(out, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                if (a.code.equals("SYSTEM") != b.code.equals("SYSTEM")) return a.code.equals("SYSTEM") ? -1 : 1;
                long ta = a.last == null ? 0 : a.last.ts, tb = b.last == null ? 0 : b.last.ts;
                if (ta != tb) return ta > tb ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }
}
