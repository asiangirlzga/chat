package app.litechat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Tiny HTTP client for the LiteChat server (no libraries). Always call from a background thread. */
final class Api {
    static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 LiteChat/1.0";

    static class Err extends Exception { Err(String m) { super(m); } }

    /** Maps an API path to a URL. PHP hosts have no URL rewriting, so the path travels in ?r=. */
    static String url(String path) {
        String base = Prefs.server();
        if (!Prefs.isPhp()) return base + path;
        int q = path.indexOf('?');
        String p = q < 0 ? path : path.substring(0, q), rest = q < 0 ? "" : path.substring(q + 1);
        return base + "?r=" + (p.startsWith("/") ? p.substring(1) : p) + (rest.isEmpty() ? "" : "&" + rest);
    }

    /** If the address is a plain website that runs LiteChat's PHP file, switch to PHP mode (…/index.php). */
    static void detectPhp() {
        if (Prefs.server().endsWith(".php") || Prefs.host()) return;
        for (String suffix : new String[]{"/", "/index.php"}) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(Prefs.server() + suffix).openConnection();
                c.setRequestProperty("User-Agent", UA);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                byte[] buf = new byte[300];
                int n = 0;
                try (InputStream is = c.getInputStream()) { n = Math.max(0, is.read(buf)); }
                if (new String(buf, 0, n, "UTF-8").contains("LiteChat PHP server")) {
                    Prefs.put("server", Prefs.server() + "/index.php");
                    return;
                }
            } catch (Exception ignored) { }
        }
    }

    static HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url(path)).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        String t = Prefs.get("token");
        if (!t.isEmpty()) { c.setRequestProperty("Authorization", "Bearer " + t); c.setRequestProperty("X-Token", t); }
        return c;
    }

    static JSONObject call(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = open(path, method);
        try {
            if (body != null) {
                byte[] b = body.toString().getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) { o.write(b); }
            }
            return read(c);
        } finally { c.disconnect(); }
    }

    static JSONObject read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = "";
        if (is != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            s = bo.toString("UTF-8");
        }
        JSONObject j;
        try { j = new JSONObject(s); }
        catch (Exception e) {
            if (code < 400) throw new Err("Unexpected reply - check the server address (some free hosts block apps with a browser-check page)");
            j = new JSONObject();
        }
        if (code >= 400) {
            String snip = s.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
            if (snip.length() > 90) snip = snip.substring(0, 90);
            throw new Err(j.optString("error", "Server error " + code + (snip.isEmpty() ? "" : " - " + snip) + " (host blocked the request before LiteChat saw it?)"));
        }
        return j;
    }

    // ---------- files ----------
    static String upload(InputStream in, String name, String mime) throws Exception {
        File tmp = File.createTempFile("up", ".tmp", App.ctx.getCacheDir());
        try {
            try (OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            } finally { in.close(); }
            HttpURLConnection c = open("/api/upload", "POST");
            try {
                c.setReadTimeout(120000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(tmp.length());
                c.setRequestProperty("X-Name", URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
                c.setRequestProperty("Content-Type", mime);
                try (OutputStream o = c.getOutputStream(); java.io.FileInputStream fi = new java.io.FileInputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = fi.read(buf)) > 0) o.write(buf, 0, n);
                }
                return read(c).getString("file");
            } finally { c.disconnect(); }
        } finally { tmp.delete(); }
    }

    static String fileUrl(String path) {
        return url(path + "?t=" + Prefs.get("token"));
    }

    private static File cacheFile(String path) {
        return new File(App.ctx.getCacheDir(), "img_" + path.replaceAll("[^A-Za-z0-9.]", "_"));
    }

    /** Downloads (once) and decodes an image from the server, scaled so its sides are about {@code max} px. */
    static Bitmap image(String path, int max) throws Exception {
        File f = cacheFile(path);
        if (!f.exists()) {
            File tmp = new File(f.getPath() + ".tmp");
            HttpURLConnection c = open(path, "GET");
            c.setReadTimeout(60000);
            if (c.getResponseCode() >= 400) { c.disconnect(); throw new Err("Image unavailable"); }
            try (InputStream is = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) o.write(buf, 0, n);
            } finally { c.disconnect(); }
            tmp.renameTo(f);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, max);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Saves a server file into the public Downloads/LiteChat folder; returns its Uri. */
    static Uri saveToDownloads(String path, String name, String mime) throws Exception {
        ContentResolver cr = App.ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/LiteChat");
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new Err("Cannot create file");
        HttpURLConnection c = open(path + "?n=" + URLEncoder.encode(name, "UTF-8"), "GET");
        c.setReadTimeout(120000);
        try {
            if (c.getResponseCode() >= 400) throw new Err("Download failed");
            try (InputStream is = c.getInputStream(); OutputStream o = cr.openOutputStream(u)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) o.write(buf, 0, n);
            }
        } catch (Exception e) {
            cr.delete(u, null, null);
            throw e;
        } finally { c.disconnect(); }
        return u;
    }

    // ---------- local image helpers ----------
    static int sample(int w, int h, int max) {
        int s = 1;
        while (w / (s * 2) >= max || h / (s * 2) >= max) s *= 2;
        return s;
    }

    /** Reads an image from a content Uri, fixes rotation, scales it down and returns JPEG bytes. */
    static byte[] jpeg(Uri u, int max) throws Exception {
        ContentResolver cr = App.ctx.getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream i = cr.openInputStream(u)) { BitmapFactory.decodeStream(i, null, o); }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample(o.outWidth, o.outHeight, max);
        Bitmap b;
        try (InputStream i = cr.openInputStream(u)) { b = BitmapFactory.decodeStream(i, null, o2); }
        if (b == null) throw new Err("Not an image");
        int rot = 0;
        try (InputStream i = cr.openInputStream(u)) {
            int ori = new ExifInterface(i).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
        } catch (Exception ignored) { }
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
        float sc = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
        if (sc < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, bo);
        return bo.toByteArray();
    }
}
