package app.litechat;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Voice / video call. Media is WebRTC running inside a WebView (no native WebRTC library = tiny APK);
 * call.html talks to the other phone through this class, which relays signalling via your server.
 */
public class CallActivity extends Activity {
    static volatile CallActivity cur;

    String peer, peerName;
    private boolean video, incoming, caller, accepted, ended, pageLoaded, jsReady, active;
    private int pendingStart;                       // 0 none, 1 start as caller, 2 start as callee
    private long connectedAt;
    private String iceJson = "[{\"urls\":\"stun:stun.l.google.com:19302\"}]";
    private final List<String[]> queue = new ArrayList<>();
    private Runnable afterPerms;

    private WebView wv;
    private LinearLayout ringBox;
    private TextView statusTv;
    private Button acceptBtn;
    private Ringtone ringtone;
    private Vibrator vib;
    private AudioManager am;

    private final Runnable ringTimeout = new Runnable() {
        @Override public void run() {
            if (!accepted && !ended) finishCall(caller, caller ? "📵 Missed " + kind() + " call" : null);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        peer = getIntent().getStringExtra("peer");
        video = getIntent().getBooleanExtra("video", false);
        incoming = getIntent().getBooleanExtra("incoming", false);
        caller = !incoming;
        if (peer == null || !Prefs.registered() || (cur != null && cur != this)) { finish(); return; }
        cur = this;
        active = true;
        Notif.cancelCall();
        peerName = Db.get().nameOf(peer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF0B1F1D);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1F1D);

        wv = new WebView(this);
        wv.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        wv.addJavascriptInterface(new Bridge(), "Android");
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    if ("litechat.local".equals(req.getOrigin().getHost())) req.grant(req.getResources());
                    else req.deny();
                });
            }
        });
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pageLoaded = true;
                if (pendingStart != 0) startMedia(pendingStart == 1);
            }
        });
        root.addView(wv, new FrameLayout.LayoutParams(-1, -1));

        // ----- ringing / calling overlay -----
        ringBox = new LinearLayout(this);
        ringBox.setOrientation(LinearLayout.VERTICAL);
        ringBox.setGravity(Gravity.CENTER);
        ringBox.setBackgroundColor(0xFF0B1F1D);
        ringBox.setPadding(Ui.dp(24), Ui.dp(24), Ui.dp(24), Ui.dp(24));
        Db.Contact ct = Db.get().contact(peer);
        ImageView av = new ImageView(this);
        int px = Ui.dp(120);
        av.setImageBitmap(Ui.avatar(ct == null ? "" : ct.img, peerName, px));
        ringBox.addView(av, new LinearLayout.LayoutParams(px, px));
        TextView nameTv = Ui.text(this, peerName, 28, Ui.WHITE, true);
        nameTv.setPadding(0, Ui.dp(20), 0, Ui.dp(6));
        ringBox.addView(nameTv);
        statusTv = Ui.text(this, "", 16, 0xCCFFFFFF, false);
        ringBox.addView(statusTv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        btns.setPadding(0, Ui.dp(48), 0, 0);
        if (incoming) {
            btns.addView(Ui.button(this, "Decline", Ui.RED, v -> finishCall(true, null)), btnLp());
            acceptBtn = Ui.button(this, video ? "Accept 🎥" : "Accept 📞", 0xFF2E7D32, v -> accept());
            btns.addView(acceptBtn, btnLp());
        } else {
            btns.addView(Ui.button(this, "Cancel", Ui.RED, v -> hangup()), btnLp());
        }
        ringBox.addView(btns);
        root.addView(ringBox, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        try { wv.loadDataWithBaseURL("https://litechat.local/", asset("call.html"), "text/html", "utf-8", null); }
        catch (IOException e) { Ui.toast("Cannot load call screen"); finish(); return; }

        if (incoming) {
            statusTv.setText("Incoming " + kind() + " call…");
            startRinging();
            App.ui.postDelayed(ringTimeout, 50000);
        } else {
            statusTv.setText("Calling…");
            ensurePerms(this::startOutgoing);
        }
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(140), Ui.dp(56));
        lp.setMargins(Ui.dp(10), 0, Ui.dp(10), 0);
        return lp;
    }

    private String kind() { return video ? "video" : "voice"; }

    private String asset(String name) throws IOException {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            return bo.toString("UTF-8");
        }
    }

    // ---------- permissions ----------
    private void ensurePerms(Runnable ok) {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (video && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (need.isEmpty()) { ok.run(); return; }
        afterPerms = ok;
        requestPermissions(need.toArray(new String[0]), 21);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        if (rc != 21) return;
        boolean all = res.length > 0;
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) all = false;
        if (all && afterPerms != null) { Runnable r = afterPerms; afterPerms = null; r.run(); }
        else { Ui.toast("Microphone" + (video ? " and camera" : "") + " permission is needed for calls"); finishCall(true, null); }
    }

    // ---------- call flow ----------
    private void fetchIce() {
        try { iceJson = Api.call("GET", "/api/ice", null).getJSONArray("iceServers").toString(); } catch (Exception ignored) { }
    }

    private void startOutgoing() {
        App.io.execute(() -> {
            fetchIce();
            boolean delivered = false;
            String error = null;
            try { delivered = Core.signalSync(peer, "invite", video, ""); } catch (Exception e) { error = e.getMessage(); }
            final boolean ok = delivered;
            final String err = error;
            runOnUiThread(() -> {
                if (ended) { if (ok) Core.signal(peer, "end", video, ""); return; }   // cancelled while the invite was in flight
                if (!ok) {
                    statusTv.setText(err != null ? err : peerName + " is offline / not reachable");
                    App.ui.postDelayed(() -> finishCall(false, null), 2000);
                } else {
                    statusTv.setText("Ringing…");
                    App.ui.postDelayed(ringTimeout, 45000);
                }
            });
        });
    }

    private void accept() {
        acceptBtn.setEnabled(false);
        ensurePerms(() -> {
            App.ui.removeCallbacks(ringTimeout);
            stopRinging();
            accepted = true;
            statusTv.setText("Connecting…");
            App.io.execute(() -> {
                fetchIce();
                runOnUiThread(() -> {
                    if (ended) return;
                    startMedia(false);                              // page ready first, then tell the caller
                    Core.signal(peer, "accept", video, "");
                });
            });
        });
    }

    private void startMedia(boolean asCaller) {
        if (!pageLoaded) { pendingStart = asCaller ? 1 : 2; return; }
        pendingStart = 0;
        ringBox.setVisibility(View.GONE);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        am.setSpeakerphoneOn(video);
        try {
            JSONObject o = new JSONObject().put("video", video).put("caller", asCaller)
                    .put("name", peerName).put("ice", new JSONArray(iceJson));
            wv.evaluateJavascript("init(" + o + ")", null);
        } catch (Exception e) { finishCall(true, null); return; }
        jsReady = true;
        for (String[] s : queue) js(s[0], s[1]);
        queue.clear();
    }

    private void js(String kind, String data) {
        wv.evaluateJavascript("onSignal(" + JSONObject.quote(kind) + "," + JSONObject.quote(data) + ")", null);
    }

    /** Signal from the other phone (called on a background thread). */
    void onSignal(final String kind, final String data) {
        runOnUiThread(() -> handleSignal(kind, data));
    }

    private void handleSignal(String kind, String data) {
        if (ended) return;
        switch (kind) {
            case "accept":
                if (caller && !accepted) {
                    accepted = true;
                    App.ui.removeCallbacks(ringTimeout);
                    statusTv.setText("Connecting…");
                    startMedia(true);
                }
                break;
            case "reject":
                statusTv.setText("Call declined");
                App.ui.postDelayed(() -> finishCall(false, "📵 Call declined"), 1200);
                break;
            case "busy":
                statusTv.setText(peerName + " is on another call");
                App.ui.postDelayed(() -> finishCall(false, "📵 " + peerName + " was busy"), 1200);
                break;
            case "end":
                finishCall(false, connectedAt > 0 ? durationText() : null);
                break;
            case "offer": case "answer": case "ice":
                if (jsReady) js(kind, data); else queue.add(new String[]{kind, data});
                break;
            default: break;
        }
    }

    private String durationText() {
        long s = Math.max(0, (System.currentTimeMillis() - connectedAt) / 1000);
        return "📞 " + (video ? "Video" : "Voice") + " call · " + String.format("%02d:%02d", s / 60, s % 60);
    }

    void hangup() {
        if (ended) return;
        if (incoming && !accepted) finishCall(true, null);
        else if (!accepted) finishCall(true, "📵 Missed " + kind() + " call");
        else finishCall(true, connectedAt > 0 ? durationText() : null);
    }

    /** Ends the call. Only the calling side writes the call log line into the chat. */
    private void finishCall(boolean sendSignal, String log) {
        if (ended) return;
        ended = true;
        App.ui.removeCallbacks(ringTimeout);
        if (sendSignal) Core.signal(peer, incoming && !accepted ? "reject" : "end", video, "");
        if (log != null && caller) Core.logCall(peer, log);
        stopRinging();
        finish();
    }

    @Override public void onBackPressed() { hangup(); }

    // ---------- ringing ----------
    private void startRinging() {
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) { ringtone.setLooping(true); ringtone.play(); }
        } catch (Exception ignored) { }
        try {
            vib = getSystemService(Vibrator.class);
            vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 700}, 0));
        } catch (Exception ignored) { }
    }

    private void stopRinging() {
        try { if (ringtone != null) ringtone.stop(); } catch (Exception ignored) { }
        ringtone = null;
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) { }
        vib = null;
    }

    @Override protected void onDestroy() {
        if (active) {
            if (!ended) {
                ended = true;
                Core.signal(peer, incoming && !accepted ? "reject" : "end", video, "");
            }
            App.ui.removeCallbacks(ringTimeout);
            stopRinging();
            if (cur == this) cur = null;
            if (am != null) { am.setSpeakerphoneOn(false); am.setMode(AudioManager.MODE_NORMAL); }
            if (wv != null) {
                wv.loadUrl("about:blank");
                if (wv.getParent() instanceof ViewGroup) ((ViewGroup) wv.getParent()).removeView(wv);
                wv.destroy();
            }
        }
        super.onDestroy();
    }

    /** Methods called from call.html. */
    class Bridge {
        @JavascriptInterface public void signal(String kind, String data) { Core.signal(peer, kind, video, data); }
        @JavascriptInterface public void hangup() { runOnUiThread(() -> CallActivity.this.hangup()); }
        @JavascriptInterface public void speaker(final boolean on) { runOnUiThread(() -> am.setSpeakerphoneOn(on)); }
        @JavascriptInterface public void state(final String s) {
            runOnUiThread(() -> {
                if (s.equals("connected") && connectedAt == 0) connectedAt = System.currentTimeMillis();
                else if (s.equals("failed")) finishCall(true, connectedAt > 0 ? durationText() : null);
            });
        }
    }
}
