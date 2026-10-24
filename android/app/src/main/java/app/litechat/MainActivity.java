package app.litechat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.view.MenuItem;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Chat list + phone book. */
public class MainActivity extends Activity implements Core.Listener {
    private static final int REQ_PICK = 1;
    private final List<Db.Row> rows = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;

    // contact editor state
    private ImageView editorImg;
    private String pendingImg = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Prefs.registered()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        getWindow().setStatusBarColor(Ui.DARK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.WHITE);

        LinearLayout bar = Ui.bar(this);
        TextView title = Ui.text(this, "LiteChat", 20, Ui.WHITE, true);
        title.setPadding(Ui.dp(8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(Ui.iconBtn(this, "＋", new View.OnClickListener() {
            @Override public void onClick(View v) { editContact(null); }
        }));
        bar.addView(Ui.iconBtn(this, "⋮", new View.OnClickListener() {
            @Override public void onClick(View v) { menu(v); }
        }));
        root.addView(bar);

        ListView lv = new ListView(this);
        lv.setDivider(null);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return rows.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) { return rowView(rows.get(i)); }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int i, long id) {
                startActivity(new Intent(MainActivity.this, ChatActivity.class).putExtra("code", rows.get(i).code));
            }
        });
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> a, View v, int i, long id) { rowMenu(rows.get(i)); return true; }
        });
        FrameLayoutHolder h = new FrameLayoutHolder(this, lv);
        empty = Ui.text(this, "Your phone book is empty.\nTap ＋ to save a friend's unique code.", 15, Ui.GREY, false);
        empty.setGravity(Gravity.CENTER);
        h.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(h, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotif()) {
            Prefs.askedNotif(true);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
        CoreService.start(this);
    }

    /** ListView + overlay, kept tiny. */
    private static class FrameLayoutHolder extends android.widget.FrameLayout {
        FrameLayoutHolder(Context c, View main) {
            super(c);
            addView(main, new LayoutParams(-1, -1));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!Prefs.registered()) return;
        Core.listeners.add(this);
        changed();
        App.io.execute(new Runnable() { @Override public void run() { try { Core.sync(); } catch (Exception ignored) { } } });
    }
    @Override protected void onPause() { super.onPause(); Core.listeners.remove(this); }

    @Override public void changed() {
        rows.clear();
        rows.addAll(Db.get().rows());
        adapter.notifyDataSetChanged();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View rowView(Db.Row r) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        ImageView iv = new ImageView(this);
        int px = Ui.dp(48);
        iv.setImageBitmap(Ui.avatar(r.img, r.code.equals("SYSTEM") ? "✉" : r.name, px));
        l.addView(iv, new LinearLayout.LayoutParams(px, px));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(Ui.dp(14), 0, Ui.dp(8), 0);
        mid.addView(Ui.text(this, r.name + (r.saved ? "" : "  (not saved)"), 16, Ui.TEXT, true));
        String prev = r.last == null ? r.code : preview(r.last);
        TextView pv = Ui.text(this, prev, 14, Ui.GREY, false);
        pv.setSingleLine(true);
        pv.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(pv);
        l.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        if (r.last != null) right.addView(Ui.text(this, Ui.time(r.last.ts), 12, Ui.GREY, false));
        if (r.unread > 0) {
            TextView badge = Ui.text(this, String.valueOf(r.unread), 12, Ui.WHITE, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.round(Ui.PRIMARY, 12));
            badge.setMinWidth(Ui.dp(24));
            badge.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.dp(4);
            right.addView(badge, lp);
        }
        l.addView(right);
        return l;
    }

    static String preview(Db.Msg m) {
        switch (m.type) {
            case "image": return "📷 Photo";
            case "file": return "📄 " + m.fname;
            case "joined": return "✅ " + m.text.substring(0, Math.max(0, m.text.lastIndexOf('|'))) + " joined";
            default: return m.text.replace('\n', ' ');
        }
    }

    // ---------- menus ----------
    private void menu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "My code & invite a friend");
        pm.getMenu().add(0, 2, 0, "Security & location");
        pm.getMenu().add(0, 3, 0, "Log out");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem it) {
                if (it.getItemId() == 1) showInvite();
                else if (it.getItemId() == 2) startActivity(new Intent(MainActivity.this, SecurityActivity.class));
                else logout();
                return true;
            }
        });
        pm.show();
    }

    private void rowMenu(final Db.Row r) {
        if (r.code.equals("SYSTEM")) return;
        String[] items = r.saved ? new String[]{"Edit contact", "Delete contact"} : new String[]{"Save to phone book"};
        new AlertDialog.Builder(this).setTitle(r.name).setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    Db.Contact c = Db.get().contact(r.code);
                    if (c == null) { c = new Db.Contact(); c.code = r.code; c.name = ""; }
                    editContact(c);
                } else {
                    Db.get().deleteContact(r.code);
                    changed();
                }
            }
        }).show();
    }

    /** "My code" + invite flow: share text with server address and your unique code. */
    private void showInvite() {
        final String code = Prefs.get("code");
        final String text = "Join me on LiteChat 👋\n\n1. Install the LiteChat app\n2. Server address: " + Prefs.publicServer()
                + "\n3. Register using my unique code: " + code
                + "\n\nAn OTP will appear in my LiteChat — I'll send it to you.";
        TextView t = Ui.text(this, "Your unique code\n" + code + "\n\nTo invite a friend: send them the invite. When they register with your code, " +
                "their OTP shows up in your \"Invites & OTP\" chat — tell it to them.", 15, Ui.TEXT, false);
        t.setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), 0);
        t.setTextIsSelectable(true);
        new AlertDialog.Builder(this).setTitle(Prefs.get("name")).setView(t)
                .setPositiveButton("Share invite", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite"));
                    }
                })
                .setNeutralButton("Copy code", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("code", code));
                        Ui.toast("Code copied");
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    private void logout() {
        new AlertDialog.Builder(this).setTitle("Log out?").setMessage("This removes your chats from this phone. Your account stays on the server.")
                .setPositiveButton("Log out", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        stopService(new Intent(MainActivity.this, CoreService.class));
                        LocalServer.stop();
                        Db.reset();
                        Prefs.clear();
                        startActivity(new Intent(MainActivity.this, SetupActivity.class));
                        finish();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ---------- phone book editor (code + name + image) ----------
    private void editContact(final Db.Contact existing) {
        final boolean isNew = existing == null;
        pendingImg = "";
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(24), Ui.dp(12), Ui.dp(24), 0);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        editorImg = new ImageView(this);
        int px = Ui.dp(84);
        editorImg.setImageBitmap(Ui.avatar(isNew ? "" : existing.img, isNew ? "+" : existing.name, px));
        editorImg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
                startActivityForResult(Intent.createChooser(i, "Contact photo"), REQ_PICK);
            }
        });
        box.addView(editorImg, new LinearLayout.LayoutParams(px, px));
        box.addView(Ui.text(this, "tap to choose photo", 12, Ui.GREY, false));

        final EditText name = new EditText(this);
        name.setHint("Name");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final EditText code = new EditText(this);
        code.setHint("Unique code  (XXXX-XXXX)");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if (!isNew) { name.setText(existing.name); code.setText(existing.code); code.setEnabled(false); }
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        box.addView(code, new LinearLayout.LayoutParams(-1, -2));

        final AlertDialog d = new AlertDialog.Builder(this).setTitle(isNew ? "Add to phone book" : "Edit contact")
                .setView(box).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String c = Ui.fmt(code.getText().toString());
                final String n = name.getText().toString().trim();
                if (c.length() != 9) { Ui.toast("Code looks like XXXX-XXXX"); return; }
                if (c.equals(Prefs.get("code"))) { Ui.toast("That's your own code"); return; }
                final String oldImg = isNew ? "" : existing.img;
                final String img = pendingImg;
                App.io.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject u = Api.call("GET", "/api/user/" + c, null);   // make sure the code exists
                            Db.Contact ct = new Db.Contact();
                            ct.code = c;
                            ct.name = n.isEmpty() ? u.optString("name", c) : n;
                            ct.img = oldImg;
                            if (!img.isEmpty()) {
                                File dst = new File(getFilesDir(), "ct_" + c.replace("-", "") + ".jpg");
                                File src = new File(img);
                                if (dst.exists()) dst.delete();
                                if (src.renameTo(dst)) ct.img = dst.getPath();
                            }
                            Db.get().saveContact(ct);
                            App.ui.post(new Runnable() { @Override public void run() { d.dismiss(); changed(); } });
                        } catch (Exception e) {
                            Ui.toast(e.getMessage());
                        }
                    }
                });
            }
        });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        App.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] jpg = Api.jpeg(u, 256);
                    File f = new File(getFilesDir(), "pending_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(jpg); }
                    pendingImg = f.getPath();
                    App.ui.post(new Runnable() { @Override public void run() {
                        if (editorImg != null) editorImg.setImageBitmap(Ui.avatar(pendingImg, "?", Ui.dp(84)));
                    }});
                } catch (Exception e) { Ui.toast("Could not load photo"); }
            }
        });
    }
}
