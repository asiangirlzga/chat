package app.litechat;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

final class Prefs {
    private static SharedPreferences sp() { return App.ctx.getSharedPreferences("lc", 0); }

    static String get(String k) { return sp().getString(k, ""); }
    static void put(String k, String v) { sp().edit().putString(k, v).apply(); }

    /** Normalises what the user typed: adds http://, strips trailing slash. */
    static void setServer(String s) {
        s = s.trim();
        if (!s.matches("(?i)^https?://.*")) {            // bare host: https for domains, http for IPs / ports / localhost
            boolean local = s.matches("^(\\d{1,3}\\.){3}\\d{1,3}.*|^localhost.*|^[^/]*:\\d+.*");
            s = (local ? "http://" : "https://") + s;
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        put("server", s);
    }
    static String server() { return get("server"); }
    /** PHP shared-host mode: server address ends with .php (auto-detected at setup). */
    static boolean isPhp() { return server().endsWith(".php"); }
    static boolean host() { return get("host").equals("1"); }
    /** Address friends must use (own Wi-Fi IP when this phone hosts the server). */
    static String publicServer() { return host() ? "http://" + LocalServer.ip() + ":" + LocalServer.PORT : server().replaceAll("/index\\.php$", ""); }
    static boolean registered() { return !get("token").isEmpty(); }

    static long lastId() { return sp().getLong("lastId", 0); }
    static void lastId(long v) { sp().edit().putLong("lastId", v).apply(); }

    static boolean sharing() { return sp().getBoolean("sharing", false); }
    static void sharing(boolean v) { sp().edit().putBoolean("sharing", v).apply(); }
    static int interval() { return sp().getInt("interval", 5); }
    static void interval(int v) { sp().edit().putInt("interval", v).apply(); }
    static long lastLoc() { return sp().getLong("lastLoc", 0); }
    static void lastLoc(long v) { sp().edit().putLong("lastLoc", v).apply(); }
    static boolean askedNotif() { return sp().getBoolean("askedNotif", false); }
    static void askedNotif(boolean v) { sp().edit().putBoolean("askedNotif", v).apply(); }

    static Set<String> trusted() { return new HashSet<>(sp().getStringSet("trusted", new HashSet<String>())); }
    static void trusted(Set<String> s) { sp().edit().putStringSet("trusted", new HashSet<>(s)).apply(); }

    static void clear() { sp().edit().clear().apply(); }
}
