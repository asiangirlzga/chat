package app.litechat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** First run: connect to your server and register. New users get an OTP through an existing friend's chat. */
public class SetupActivity extends Activity {
    private EditText server, name, inviter, otp;
    private Button go;
    private TextView info;
    private String regId = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.DARK);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(24), Ui.dp(40), Ui.dp(24), Ui.dp(24));
        sv.addView(col);
        setContentView(sv);

        TextView title = Ui.text(this, "LiteChat", 32, Ui.PRIMARY, true);
        col.addView(title);
        col.addView(Ui.text(this, "Private chat on your own server", 15, Ui.GREY, false));

        Button host = Ui.button(this, "📱 Host the server on this phone (no PC needed)", 0xFF546E7A, new View.OnClickListener() {
            @Override public void onClick(View v) { hostHere(); }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(20);
        col.addView(host, hlp);

        server = field("Server address", "https://berry.gt.tc   or   http://192.168.1.10:8080", InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(Prefs.server());
        name = field("Your name", "e.g. Asha", InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        inviter = field("Friend's unique code (or admin key)", "XXXX-XXXX  (leave empty if you are the first user)", InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        for (View v : new View[]{server, name, inviter}) col.addView(v);

        otp = field("OTP", "6-digit code your friend sees in their app", InputType.TYPE_CLASS_NUMBER);
        otp.setVisibility(View.GONE);
        col.addView(otp);

        info = Ui.text(this, "", 14, Ui.GREY, false);
        info.setPadding(0, Ui.dp(12), 0, Ui.dp(4));
        col.addView(info);

        go = Ui.button(this, "Get OTP", Ui.PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View v) { if (regId.isEmpty()) start(); else verify(); }
        });
        col.addView(go);

        TextView help = Ui.text(this, "How it works: the person who invites you gets a one-time code inside their LiteChat app " +
                "(in the \"Invites & OTP\" chat). They tell it to you, you type it here, done.", 13, Ui.GREY, false);
        help.setPadding(0, Ui.dp(20), 0, 0);
        col.addView(help);
        help.setGravity(Gravity.START);
    }

    private EditText field(String label, String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | type);
        if (type == InputType.TYPE_CLASS_NUMBER) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(14);
        e.setLayoutParams(lp);
        e.setContentDescription(label);
        return e;
    }

    /** Starts the server that is built into this app and points the app at it. */
    private void hostHere() {
        Prefs.put("host", "1");
        info.setText("Starting server…");
        App.io.execute(new Runnable() {
            @Override public void run() {
                LocalServer.start();
                App.ui.post(new Runnable() { @Override public void run() {
                    server.setText("http://127.0.0.1:" + LocalServer.PORT);
                    info.setText(LocalServer.running()
                            ? "Server is running on this phone.\nFriends on the same Wi-Fi / hotspot type:  http://" + LocalServer.ip() + ":" + LocalServer.PORT
                              + "\n\nNow enter your name and tap Get OTP (the first user needs no OTP)."
                            : "⚠ Could not start the server (port " + LocalServer.PORT + " busy?).");
                }});
            }
        });
    }

    private void busy(boolean on, String msg) {
        go.setEnabled(!on);
        info.setText(msg);
    }

    private void start() {
        final String s = server.getText().toString().trim(), n = name.getText().toString().trim();
        if (s.isEmpty() || n.isEmpty()) { info.setText("Enter the server address and your name."); return; }
        Prefs.setServer(s);
        final String inv = inviter.getText().toString().trim();
        busy(true, "Contacting server…");
        App.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    Api.detectPhp();
                    JSONObject r = Api.call("POST", "/api/register/start", new JSONObject().put("name", n).put("inviter", inv));
                    if (r.optBoolean("first")) { done(r); return; }
                    regId = r.getString("regId");
                    final String who = r.getJSONObject("inviter").getString("name");
                    App.ui.post(new Runnable() { @Override public void run() {
                        otp.setVisibility(View.VISIBLE);
                        go.setText("Verify & register");
                        busy(false, "An OTP was sent to " + who + "'s LiteChat (chat \"Invites & OTP\"). Ask " + who + " for the 6 digits.");
                    }});
                } catch (final Exception e) {
                    App.ui.post(new Runnable() { @Override public void run() { busy(false, "⚠ " + e.getMessage()); } });
                }
            }
        });
    }

    private void verify() {
        final String code = otp.getText().toString().trim();
        if (code.length() != 6) { info.setText("Enter the 6-digit OTP."); return; }
        busy(true, "Verifying…");
        App.io.execute(new Runnable() {
            @Override public void run() {
                try { done(Api.call("POST", "/api/register/verify", new JSONObject().put("regId", regId).put("otp", code))); }
                catch (final Exception e) {
                    App.ui.post(new Runnable() { @Override public void run() { busy(false, "⚠ " + e.getMessage()); } });
                }
            }
        });
    }

    /** Registration finished: store the token, save the inviter in the phone book, open the app. */
    private void done(final JSONObject r) throws Exception {
        Db.reset();
        Prefs.put("token", r.getString("token"));
        Prefs.put("code", r.getString("code"));
        Prefs.put("name", r.getString("name"));
        Prefs.lastId(0);
        Prefs.sharing(false);
        JSONObject inv = r.optJSONObject("inviter");
        if (inv != null) {
            Db.Contact c = new Db.Contact();
            c.code = inv.getString("code"); c.name = inv.getString("name");
            Db.get().saveContact(c);
        }
        CoreService.start(this);
        App.ui.post(new Runnable() { @Override public void run() {
            startActivity(new Intent(SetupActivity.this, MainActivity.class));
            finish();
        }});
    }
}
