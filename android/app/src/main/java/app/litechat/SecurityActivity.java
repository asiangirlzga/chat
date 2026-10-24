package app.litechat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Security: opt-in live location sharing with chosen "trusted" contacts + SOS button.
 * Nothing is shared unless the switch is on; a notification is visible the whole time it is on.
 */
public class SecurityActivity extends Activity {
    private static final int REQ_LOC = 31;
    private Switch sw;
    private Button trustedBtn, intervalBtn;
    private TextView last;
    private boolean ignore;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Prefs.registered()) { finish(); return; }
        getWindow().setStatusBarColor(Ui.DARK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.WHITE);
        LinearLayout bar = Ui.bar(this);
        bar.addView(Ui.iconBtn(this, "←", v -> finish()));
        bar.addView(Ui.text(this, "Security & location", 19, Ui.WHITE, true));
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(20), Ui.dp(24));
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        TextView intro = Ui.text(this, "For your safety you can share your live location with people you trust. " +
                "Only the contacts you pick can see it, a notification stays visible while it is on, and you can stop any time.", 14, Ui.GREY, false);
        col.addView(intro);

        sw = new Switch(this);
        sw.setText("Share my live location");
        sw.setTextSize(17);
        sw.setPadding(0, Ui.dp(18), 0, Ui.dp(10));
        sw.setChecked(Prefs.sharing());
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) { if (!ignore) toggle(on); }
        });
        col.addView(sw);

        trustedBtn = add(col, "", Ui.PRIMARY, v -> chooseTrusted());
        intervalBtn = add(col, "", Ui.PRIMARY, v -> chooseInterval());
        add(col, "Allow location \"all the time\" (works after restart)", 0xFF546E7A, v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        add(col, "Keep LiteChat running (battery settings)", 0xFF546E7A, v ->
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));

        last = Ui.text(this, "", 13, Ui.GREY, false);
        last.setPadding(0, Ui.dp(10), 0, Ui.dp(10));
        col.addView(last);

        add(col, "🆘  Send SOS to trusted contacts", Ui.RED, v -> sos());
        refresh();
    }

    private Button add(LinearLayout col, String text, int color, View.OnClickListener l) {
        Button bt = Ui.button(this, text, color, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(10);
        col.addView(bt, lp);
        return bt;
    }

    private void refresh() {
        trustedBtn.setText("Trusted contacts: " + Prefs.trusted().size() + "  (tap to choose)");
        intervalBtn.setText("Update every " + Prefs.interval() + " min  (tap to change)");
        long t = Prefs.lastLoc();
        last.setText(Prefs.sharing() ? (t == 0 ? "Waiting for the first location…" : "Last location sent " + Ui.ago(t)) : "Sharing is off.");
    }

    // ---------- switch ----------
    private void toggle(boolean on) {
        if (on) {
            if (Prefs.trusted().isEmpty()) { Ui.toast("Choose at least one trusted contact first"); setSwitch(false); chooseTrusted(); return; }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
                return;
            }
        }
        apply(on);
    }

    private void setSwitch(boolean v) { ignore = true; sw.setChecked(v); ignore = false; }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] res) {
        super.onRequestPermissionsResult(rc, p, res);
        if (rc != REQ_LOC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) apply(true);
        else { Ui.toast("Location permission denied"); setSwitch(false); }
    }

    /** Saves the setting on the server and (re)starts the service with/without location. */
    private void apply(final boolean on) {
        final Set<String> trusted = Prefs.trusted();
        App.io.execute(() -> {
            try {
                Api.call("POST", "/api/location/settings", new JSONObject().put("sharing", on).put("trusted", new JSONArray(trusted)));
                Prefs.sharing(on);
                runOnUiThread(() -> { CoreService.start(this); refresh(); });
            } catch (Exception e) {
                Ui.toast("Server: " + e.getMessage());
                runOnUiThread(() -> setSwitch(Prefs.sharing()));
            }
        });
    }

    // ---------- trusted + interval ----------
    private void chooseTrusted() {
        final List<Db.Contact> all = Db.get().contacts();
        if (all.isEmpty()) { Ui.toast("Add contacts to your phone book first"); return; }
        final Set<String> sel = Prefs.trusted();
        String[] names = new String[all.size()];
        final boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).name + "  (" + all.get(i).code + ")";
            checked[i] = sel.contains(all.get(i).code);
        }
        new AlertDialog.Builder(this).setTitle("Who can see my location?")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (d, w) -> {
                    Set<String> out = new HashSet<>();
                    for (int i = 0; i < all.size(); i++) if (checked[i]) out.add(all.get(i).code);
                    Prefs.trusted(out);
                    if (out.isEmpty() && Prefs.sharing()) { setSwitch(false); apply(false); }
                    else apply(Prefs.sharing());
                    refresh();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseInterval() {
        final int[] mins = {1, 5, 15, 30};
        new AlertDialog.Builder(this).setTitle("Update location every")
                .setItems(new String[]{"1 minute", "5 minutes", "15 minutes", "30 minutes"}, (d, which) -> {
                    Prefs.interval(mins[which]);
                    if (Prefs.sharing()) CoreService.start(this);
                    refresh();
                }).show();
    }

    // ---------- SOS ----------
    private void sos() {
        if (Prefs.trusted().isEmpty()) { Ui.toast("Choose trusted contacts first"); return; }
        new AlertDialog.Builder(this).setTitle("Send SOS?")
                .setMessage("Your trusted contacts get an SOS message with your location.")
                .setPositiveButton("Send SOS", (d, w) -> App.io.execute(() -> {
                    try {
                        JSONObject b = new JSONObject();
                        Location l = lastKnown();
                        if (l != null) b.put("lat", l.getLatitude()).put("lng", l.getLongitude());
                        int n = Api.call("POST", "/api/sos", b).optInt("sent");
                        Ui.toast("SOS sent to " + n + " contact(s)");
                    } catch (Exception e) { Ui.toast("SOS failed: " + e.getMessage()); }
                }))
                .setNegativeButton("Cancel", null).show();
    }

    private Location lastKnown() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        return best;
    }
}
