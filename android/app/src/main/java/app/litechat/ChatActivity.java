package app.litechat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.util.Linkify;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One conversation: text, photos, documents, voice/video call buttons, "where is" for security. */
public class ChatActivity extends Activity implements Core.Listener {
    private static final int REQ_PHOTO = 1, REQ_DOC = 2;
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final LruCache<String, Bitmap> thumbs = new LruCache<>(24);

    private String peer, peerName;
    private final List<Db.Msg> items = new ArrayList<>();
    private BaseAdapter adapter;
    private ListView lv;
    private EditText et;
    private TextView status;
    private int sending;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        peer = getIntent().getStringExtra("code");
        if (peer == null || !Prefs.registered()) { finish(); return; }
        peerName = Db.get().nameOf(peer);
        final boolean system = peer.equals("SYSTEM");
        getWindow().setStatusBarColor(Ui.DARK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // ----- header -----
        LinearLayout bar = Ui.bar(this);
        bar.addView(Ui.iconBtn(this, "←", v -> finish()));
        ImageView av = new ImageView(this);
        Db.Contact ct = Db.get().contact(peer);
        int px = Ui.dp(40);
        av.setImageBitmap(Ui.avatar(ct == null ? "" : ct.img, system ? "✉" : peerName, px));
        bar.addView(av, new LinearLayout.LayoutParams(px, px));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(10), 0, 0, 0);
        titles.addView(Ui.text(this, peerName, 17, Ui.WHITE, true));
        titles.addView(Ui.text(this, system ? "OTP codes and invites" : peer, 12, 0xCCFFFFFF, false));
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!system) {
            bar.addView(Ui.iconBtn(this, "📞", v -> call(false)));
            bar.addView(Ui.iconBtn(this, "🎥", v -> call(true)));
            bar.addView(Ui.iconBtn(this, "📍", v -> whereIs()));
        }
        root.addView(bar);

        // ----- messages -----
        lv = new ListView(this);
        lv.setDivider(null);
        lv.setClipToPadding(false);
        lv.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return items.get(i).id; }
            @Override public View getView(int i, View v, ViewGroup p) { return bubble(items.get(i)); }
        };
        lv.setAdapter(adapter);
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = Ui.text(this, "", 13, Ui.GREY, false);
        status.setPadding(Ui.dp(12), Ui.dp(2), Ui.dp(12), Ui.dp(2));
        status.setVisibility(View.GONE);
        root.addView(status);

        // ----- input -----
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.HORIZONTAL);
        in.setGravity(Gravity.CENTER_VERTICAL);
        in.setBackgroundColor(Ui.WHITE);
        in.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        TextView plus = Ui.iconBtn(this, "＋", v -> attach());
        plus.setTextColor(Ui.PRIMARY);
        in.addView(plus);
        et = new EditText(this);
        et.setHint("Message");
        et.setMaxLines(4);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        in.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView send = Ui.iconBtn(this, "➤", v -> sendText());
        send.setTextColor(Ui.PRIMARY);
        in.addView(send);
        if (system) in.setVisibility(View.GONE);
        root.addView(in);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (peer == null) return;
        Core.openChat = peer;
        Core.listeners.add(this);
        changed();
        App.io.execute(() -> { try { Core.sync(); } catch (Exception ignored) { } });
    }

    @Override protected void onPause() {
        super.onPause();
        Core.openChat = "";
        Core.listeners.remove(this);
    }

    @Override public void changed() {
        items.clear();
        items.addAll(Db.get().msgs(peer));
        adapter.notifyDataSetChanged();
        if (!items.isEmpty()) lv.setSelection(items.size() - 1);
        Db.get().markSeen(peer);
        Notif.cancel(peer);
    }

    // ---------- bubbles ----------
    private View bubble(final Db.Msg m) {
        boolean mine = !m.from.equals(peer);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.dp(8), Ui.dp(3), Ui.dp(8), Ui.dp(3));
        wrap.setGravity(mine ? Gravity.END : Gravity.START);

        if (m.type.equals("call")) {
            TextView t = Ui.text(this, m.text, 13, Ui.GREY, false);
            t.setBackground(Ui.round(0x14000000, 10));
            t.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4));
            wrap.setGravity(Gravity.CENTER_HORIZONTAL);
            wrap.addView(t);
            return wrap;
        }

        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ui.round(mine ? Ui.MINE : Ui.WHITE, 12));
        b.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(4));
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.74f);

        if (m.type.equals("image")) {
            final ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0x11000000);
            b.addView(iv, new LinearLayout.LayoutParams(Ui.dp(220), Ui.dp(220)));
            final Bitmap hit = thumbs.get(m.file);
            if (hit != null) iv.setImageBitmap(hit);
            else App.io.execute(() -> {
                try {
                    final Bitmap bm = Api.image(m.file, 600);
                    thumbs.put(m.file, bm);
                    App.ui.post(() -> iv.setImageBitmap(bm));
                } catch (Exception ignored) { }
            });
            iv.setOnClickListener(v -> viewImage(m));
            if (!m.text.isEmpty()) b.addView(Ui.text(this, m.text, 16, Ui.TEXT, false));
        } else if (m.type.equals("file")) {
            TextView f = Ui.text(this, "📄  " + (m.fname.isEmpty() ? "file" : m.fname) + "\n⬇ tap to download", 15, 0xFF1A5FB4, false);
            f.setMaxWidth(maxW);
            f.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
            f.setOnClickListener(v -> download(m));
            b.addView(f);
        } else if (m.type.equals("joined")) {
            int k = m.text.lastIndexOf('|');
            String name = k > 0 ? m.text.substring(0, k) : m.text, code = k > 0 ? m.text.substring(k + 1) : "";
            TextView t = Ui.text(this, "✅ " + name + " joined LiteChat and was saved to your phone book.\nCode: " + code, 15, Ui.TEXT, false);
            t.setMaxWidth(maxW);
            b.addView(t);
        } else {
            TextView t = Ui.text(this, m.text, 16, Ui.TEXT, false);
            t.setMaxWidth(maxW);
            Linkify.addLinks(t, Linkify.WEB_URLS);
            b.addView(t);
        }
        TextView time = Ui.text(this, Ui.time(m.ts), 11, Ui.GREY, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.END;
        b.addView(time, lp);
        wrap.addView(b);
        return wrap;
    }

    private void viewImage(final Db.Msg m) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF000000);
        iv.setOnClickListener(v -> d.dismiss());
        iv.setOnLongClickListener(v -> { download(m); return true; });
        d.setContentView(iv);
        d.show();
        App.io.execute(() -> {
            try {
                final Bitmap bm = Api.image(m.file, 2048);
                App.ui.post(() -> iv.setImageBitmap(bm));
            } catch (Exception e) { Ui.toast("Could not load image"); }
        });
        Ui.toast("Long-press to save");
    }

    private void download(final Db.Msg m) {
        Ui.toast("Downloading…");
        App.io.execute(() -> {
            try {
                final String mime = m.mime.isEmpty() ? "application/octet-stream" : m.mime;
                final Uri u = Api.saveToDownloads(m.file, m.fname.isEmpty() ? "file" : m.fname, mime);
                Ui.toast("Saved in Downloads/LiteChat");
                App.ui.post(() -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
                    catch (Exception ignored) { }
                });
            } catch (Exception e) { Ui.toast("Download failed: " + e.getMessage()); }
        });
    }

    // ---------- sending ----------
    private void sendText() {
        final String t = et.getText().toString().trim();
        if (t.isEmpty()) return;
        et.setText("");
        App.io.execute(() -> {
            try { Core.send(peer, "text", t, "", "", ""); }
            catch (Exception e) { Ui.toast("Not sent: " + e.getMessage()); App.ui.post(() -> et.setText(t)); }
        });
    }

    private void attach() {
        new AlertDialog.Builder(this).setItems(new String[]{"📷  Photo", "📄  Document / file"}, (d, which) -> {
            if (which == 0) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(i, "Photo"), REQ_PHOTO);
            } else {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_DOC);
            }
        }).show();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != REQ_PHOTO && req != REQ_DOC) || res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) { for (int k = 0; k < cd.getItemCount(); k++) uris.add(cd.getItemAt(k).getUri()); }
        else if (data.getData() != null) uris.add(data.getData());
        final boolean photo = req == REQ_PHOTO;
        App.io.execute(() -> { for (Uri u : uris) sendUri(u, photo); });
    }

    private void busy(final int delta) {
        runOnUiThread(() -> {
            sending += delta;
            status.setText("Sending…");
            status.setVisibility(sending > 0 ? View.VISIBLE : View.GONE);
        });
    }

    private void sendUri(Uri u, boolean photo) {
        busy(1);
        try {
            ContentResolver cr = getContentResolver();
            String name = "file", mime = cr.getType(u);
            long size = -1;
            try (Cursor c = cr.query(u, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            }
            if (mime == null) mime = "application/octet-stream";
            if (size > MAX_BYTES) { Ui.toast(name + " is larger than 50 MB"); return; }
            if (photo) {                                            // photos are shrunk to ~1600 px before upload
                byte[] jpg = Api.jpeg(u, 1600);
                String file = Api.upload(new ByteArrayInputStream(jpg), "photo.jpg", "image/jpeg");
                Core.send(peer, "image", "", file, "photo.jpg", "image/jpeg");
            } else {
                String file = Api.upload(cr.openInputStream(u), name, mime);
                Core.send(peer, mime.startsWith("image/") ? "image" : "file", "", file, name, mime);
            }
        } catch (Exception e) {
            Ui.toast("Not sent: " + e.getMessage());
        } finally {
            busy(-1);
        }
    }

    // ---------- calls & location ----------
    private void call(boolean video) {
        startActivity(new Intent(this, CallActivity.class).putExtra("peer", peer).putExtra("video", video).putExtra("incoming", false));
    }

    private void whereIs() {
        App.io.execute(() -> {
            try {
                JSONObject r = Api.call("GET", "/api/location/" + peer, null);
                final double lat = r.getDouble("lat"), lng = r.getDouble("lng");
                final long ts = r.getLong("ts");
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(peerName + " — last location")
                        .setMessage(Ui.ago(ts) + "\n" + String.format(Locale.US, "%.5f, %.5f", lat, lng))
                        .setPositiveButton("Open in Maps", (d, w) -> openMap(lat, lng))
                        .setNegativeButton("Close", null).show());
            } catch (final Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Location")
                        .setMessage(e.getMessage() + "\n\nThey can switch it on in Security & location and add you as a trusted contact.")
                        .setPositiveButton("OK", null).show());
            }
        });
    }

    private void openMap(double lat, double lng) {
        String q = String.format(Locale.US, "%.6f,%.6f", lat, lng);
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + q + "?q=" + q + "(" + Uri.encode(peerName) + ")"))); }
        catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=" + q))); } catch (Exception ignored) { }
        }
    }
}
