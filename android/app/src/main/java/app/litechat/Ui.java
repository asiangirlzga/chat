package app.litechat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Small helpers so the screens can be built in code (no layout XML, no libraries). */
final class Ui {
    static final int PRIMARY = 0xFF0B7A75, DARK = 0xFF075E54, BG = 0xFFECE5DD, MINE = 0xFFDCF8C6,
            TEXT = 0xFF1F2933, GREY = 0xFF6B7280, RED = 0xFFD93025, WHITE = 0xFFFFFFFF;
    static final LruCache<String, Bitmap> avatars = new LruCache<>(80);

    static int dp(float v) { return Math.round(v * App.ctx.getResources().getDisplayMetrics().density); }

    static void toast(final String s) {
        App.ui.post(new Runnable() { @Override public void run() { Toast.makeText(App.ctx, s, Toast.LENGTH_LONG).show(); } });
    }

    /** Canonical form of a unique code: ABCD-EFGH. */
    static String fmt(String s) {
        String n = s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return n.length() == 8 ? n.substring(0, 4) + "-" + n.substring(4) : n;
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    static Button button(Context c, String s, int color, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setBackground(round(color, 8));
        b.setOnClickListener(l);
        return b;
    }

    /** Green top bar. */
    static LinearLayout bar(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackgroundColor(PRIMARY);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        b.setMinimumHeight(dp(56));
        return b;
    }

    static TextView iconBtn(Context c, String glyph, View.OnClickListener l) {
        TextView t = text(c, glyph, 22, WHITE, false);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(44));
        t.setMinHeight(dp(44));
        t.setOnClickListener(l);
        return t;
    }

    static Bitmap avatar(String img, String name, int px) {
        String key = img + "|" + name + "|" + px;
        Bitmap hit = avatars.get(key);
        if (hit != null) return hit;
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap src = (img == null || img.isEmpty()) ? null : BitmapFactory.decodeFile(img);
        if (src != null) {
            float s = Math.max((float) px / src.getWidth(), (float) px / src.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate((px - src.getWidth() * s) / 2f, (px - src.getHeight() * s) / 2f);
            BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sh.setLocalMatrix(m);
            p.setShader(sh);
            cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
        } else {
            int[] cols = {0xFF0B7A75, 0xFF3F51B5, 0xFF8E24AA, 0xFFE65100, 0xFF00796B, 0xFFC2185B, 0xFF5D4037};
            p.setColor(cols[Math.abs(name.hashCode()) % cols.length]);
            cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
            p.setColor(Color.WHITE);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(px * 0.45f);
            String ch = name.isEmpty() ? "?" : new String(Character.toChars(name.codePointAt(0))).toUpperCase();
            cv.drawText(ch, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p);
        }
        avatars.put(key, out);
        return out;
    }

    static String time(long ts) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        b.setTimeInMillis(ts);
        boolean today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "HH:mm" : "d MMM", Locale.getDefault()).format(new Date(ts));
    }

    static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 90) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " days ago";
    }
}
