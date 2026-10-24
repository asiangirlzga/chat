package app.litechat;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;

/**
 * Foreground service = the app's "push": keeps one Server-Sent-Events connection to your own server open,
 * so messages, call invites and notifications arrive without Google/Firebase.
 * It also reports the location while "Security location sharing" is switched on.
 */
public class CoreService extends Service {
    private volatile boolean running;
    private Thread loop;
    private volatile HttpURLConnection conn;
    private LocationManager lm;
    private LocationListener ll;
    private long lastPost;

    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, CoreService.class)); } catch (Exception ignored) { }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private boolean hasLoc() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean loc = Prefs.sharing() && hasLoc();
        Notification n = Notif.service(this, loc);
        try {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC | (loc ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
            startForeground(Notif.ID_SERVICE, n, type);
        } catch (Exception e) {
            loc = false;
            try { startForeground(Notif.ID_SERVICE, Notif.service(this, false), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); }
            catch (Exception e2) { stopSelf(); return START_NOT_STICKY; }
        }
        if (!Prefs.registered()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }

        if (Prefs.host()) LocalServer.start();
        updateLocation(loc);

        if (loop == null || !loop.isAlive()) {
            running = true;
            registerNetworkWatcher();
            loop = new Thread(new Runnable() { @Override public void run() { runLoop(); } }, "core-loop");
            loop.start();
        }
        return START_STICKY;
    }

    private void registerNetworkWatcher() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    final HttpURLConnection c = conn;           // network changed: drop the old connection fast
                    if (c != null) new Thread(new Runnable() { @Override public void run() { c.disconnect(); } }).start();
                }
            });
        } catch (Exception ignored) { }
    }

    // ---------- connection loop ----------
    private void runLoop() {
        long wait = 2000;
        boolean first = true;
        while (running && Prefs.registered()) {
            try {
                if (Prefs.isPhp()) {                 // shared PHP host: long-polling instead of a live stream
                    if (first) { Core.sync(); first = false; }
                    pollOnce();
                    wait = 0;
                } else {
                    Core.sync();
                    stream();
                    wait = 2000;
                }
            } catch (Exception e) {
                first = true;
                wait = Math.min(Math.max(wait, 1000) * 2, 30000);
            }
            try { if (wait > 0) Thread.sleep(wait); } catch (InterruptedException e) { break; }
        }
    }

    /** One long-poll round: the server answers as soon as a message or call signal arrives (or after ~12 s). */
    private void pollOnce() throws Exception {
        JSONObject r = Api.call("GET", "/api/poll?since=" + Prefs.lastId() + "&wait=12", null);
        JSONArray ev = r.getJSONArray("events");
        for (int i = 0; i < ev.length(); i++) {
            JSONObject j = ev.getJSONObject(i);
            String t = j.optString("t");
            if (t.equals("sig")) Core.onSignal(j);
            else if (t.equals("msg")) Core.sync();
        }
    }

    private void stream() throws Exception {
        HttpURLConnection c = Api.open("/api/events", "GET");
        c.setReadTimeout(70000);                                // server sends a heartbeat every 25 s
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("Accept-Encoding", "identity");
        conn = c;
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            App.io.execute(new Runnable() { @Override public void run() { try { Core.sync(); } catch (Exception ignored) { } } });
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    JSONObject j = new JSONObject(line.substring(6));
                    String t = j.optString("t");
                    if (t.equals("msg")) {
                        App.io.execute(new Runnable() { @Override public void run() { try { Core.sync(); } catch (Exception ignored) { } } });
                    } else if (t.equals("sig")) {
                        Core.onSignal(j);
                    }
                }
            }
        } finally {
            conn = null;
            c.disconnect();
        }
    }

    // ---------- location (opt-in security feature) ----------
    private void updateLocation(boolean on) {
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (ll != null) { try { lm.removeUpdates(ll); } catch (Exception ignored) { } ll = null; }
        if (!on) return;
        final long ms = Prefs.interval() * 60000L;
        ll = new LocationListener() {
            @Override public void onLocationChanged(Location l) { post(l, ms); }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, ms, 25f, ll, Looper.getMainLooper());
            }
            Location last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) post(last, 0);
        } catch (SecurityException ignored) { }
    }

    private void post(final Location l, long minGap) {
        long now = System.currentTimeMillis();
        if (minGap > 0 && now - lastPost < minGap * 8 / 10) return;   // GPS + network both fire: send once
        lastPost = now;
        App.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject b = new JSONObject().put("lat", l.getLatitude()).put("lng", l.getLongitude()).put("acc", l.getAccuracy());
                    Api.call("POST", "/api/location", b);
                    Prefs.lastLoc(System.currentTimeMillis());
                } catch (Exception ignored) { }
            }
        });
    }

    @Override public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        final HttpURLConnection c = conn;
        if (c != null) new Thread(new Runnable() { @Override public void run() { c.disconnect(); } }).start();
        if (ll != null && lm != null) { try { lm.removeUpdates(ll); } catch (Exception ignored) { } }
        super.onDestroy();
    }
}
