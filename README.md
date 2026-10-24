# LiteChat

A very light Android chat app that talks to **your own server** (no Google/Firebase, no libraries).
The server is **built into the app** (host it on one phone - no PC, no Termux) and also exists as a zero-dependency Node file for a PC/VPS.

| Feature | How it works |
|---|---|
| Chat + photos + documents | Text, multi-photo (auto-shrunk to 1600 px) and any file up to 50 MB. Files are stored on your server. |
| Voice & video calls inside the chat | 📞 / 🎥 buttons in the chat header. WebRTC runs in a WebView (so the APK stays tiny); signalling goes through your server. |
| Phone book | Save a friend's unique code + name + photo (＋ button). Long-press to edit/delete. |
| Invite + OTP | Every user has a unique code (`ABCD-EFGH`). A new person registers with a friend's code; the **OTP appears in that friend's "Invites & OTP" chat** (and as a notification). The friend tells it to them. |
| Location for security | Off by default. You choose *trusted contacts*, switch it on, and only they can see your last location (chat header 📍). A notification is shown while it's on. Includes an **SOS** button. |
| Notifications | Messages, incoming calls (ringing, full-screen on lock screen) and invites. A small foreground service keeps one connection to your server open - that is the "push". |

APK size is a few hundred KB because the app uses only Android's built-in APIs.

## 1. The server - built into the app (no PC, no Termux, nothing to install)

Install the APK on one phone, open it and tap **"Host the server on this phone"**. The app starts its own server
(pure Java inside the APK, port 8080) and keeps it running in the background. The app shows the address to share,
e.g. `http://192.168.1.7:8080`. Friends on the same Wi-Fi / your phone's hotspot type that address in their app.
For friends on other networks use a tunnel or VPN (Tailscale / Cloudflare Tunnel) and give them that address.

The phone must stay on and connected (set battery to *Unrestricted* for LiteChat). Data lives inside the app.

*Optional - run it on a PC/VPS instead:* `cd server && node server.js` (Node 18+, **no npm install needed**, zero dependencies).
Env: `PORT`, `DATA_DIR`, `MAX_UPLOAD_MB`, `BOOTSTRAP_KEY`, `TURN_URL`/`TURN_USER`/`TURN_PASS`. Test: `node test.js`.

There are no third-party libraries anywhere, so nothing needs downloading. On GitHub the build downloads
Gradle/Android tools by itself.

## Using a shared PHP host instead (e.g. https://berry.gt.tc)

1. Upload `server/php/index.php` to the site's web root (`htdocs/`) with the host's file manager or FTP. That is all - it creates its own
   `litechat_data` folder, needs no database and no PHP extensions (PHP 7.4+).
2. In the app type just `https://berry.gt.tc` (or `.../index.php`) as the server address. The app detects the PHP server by itself.
   You also get HTTPS for free, so no cleartext traffic.
3. First account: no OTP needed. Everyone else registers with a friend's code as usual.

Shared hosts cannot keep a connection open, so the app **long-polls** (one request every ~12 s, answered instantly when something arrives).
Messages, call invites and notifications still work; expect up to ~1 s delay. Things to know about free hosts:
* Many free hosts (InfinityFree-style) have a hits-per-day cap; polling uses ~7,000 requests/day per always-online user.
* Some free hosts put a JavaScript "browser check" page in front of every non-browser request. Apps cannot pass it; if the app says
  *"Unexpected reply - check the server address"* while the address is right, that is the cause - switch to another host or run the server on a phone.
* Upload size is the host's `post_max_size` (often 8-10 MB). Calls use STUN only (TURN can be set at the top of `index.php`).
* Test locally: `cd server/php && node test.js`.

## 2. Get the app (GitHub builds it for you)

1. Create a GitHub repository and push this folder (`git remote add origin ... && git push -u origin main`).
2. The workflow **Build Android APK** runs automatically (Actions tab, ~3 min).
3. On your phone open the repo -> **Releases -> "LiteChat (latest build)"** -> download `LiteChat.apk` and install it
   (allow "install unknown apps" for your browser).

*Optional - updates without uninstalling:* create a keystore once
(`keytool -genkeypair -v -keystore release.jks -alias litechat -keyalg RSA -keysize 2048 -validity 10000`),
then add repo secrets `KEYSTORE_BASE64` (`base64 -w0 release.jks`) and `KEYSTORE_PASSWORD`.

## 3. First use

* **First user (you):** open the app -> *Host the server on this phone* (or type your server address), your name -> *Get OTP*. The first account on a fresh server needs no OTP.
  To stop strangers claiming that first slot, start the server with `BOOTSTRAP_KEY=something` and type that key in the code field.
* **Everyone else:** install the app, enter the server address, their name and **your unique code**. You get the OTP in
  *Invites & OTP* and tell it to them. Menu (⋮) -> *My code & invite a friend* shares a ready-made invite text.
* Allow notifications, and in Android settings set LiteChat's battery to *Unrestricted* so the connection survives (Security screen has a shortcut).

## Notes / limits

* Traffic is plain HTTP by default (fine on your own Wi-Fi/VPN). For the internet put the server behind HTTPS
  (Caddy, nginx, Cloudflare Tunnel) and use the `https://` address in the app. Messages are **not** end-to-end encrypted;
  the server owner can read them.
* Location: "all the time" permission (Security screen -> button) is needed for it to keep working after a phone restart.
* Android may ask you to allow full-screen call notifications (Settings -> Notifications).
* Not included yet: group chats, message delete, camera capture button (pick from gallery instead), iOS.

## Layout

```
server/            Optional Node server for PC/VPS (server.js, test.js)
android/           Gradle project, package app.litechat (plain Java, no dependencies; LocalServer.java = built-in server)
  app/src/main/assets/call.html   WebRTC call screen
.github/workflows/android.yml     builds + publishes the APK
```
