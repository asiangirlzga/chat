package app.litechat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;

final class Notif {
    static final String MSG = "msg", CALL = "call", SVC = "svc";
    static final int ID_SERVICE = 1, ID_CALL = 7777;

    private static NotificationManager nm(Context c) { return c.getSystemService(NotificationManager.class); }

    static void init(Context c) {
        NotificationManager nm = nm(c);
        NotificationChannel svc = new NotificationChannel(SVC, "Background connection", NotificationManager.IMPORTANCE_LOW);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        NotificationChannel m = new NotificationChannel(MSG, "Messages", NotificationManager.IMPORTANCE_HIGH);
        m.enableVibration(true);
        nm.createNotificationChannel(m);

        NotificationChannel cc = new NotificationChannel(CALL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        cc.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), aa);
        cc.enableVibration(true);
        cc.setVibrationPattern(new long[]{0, 800, 600, 800});
        cc.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(cc);
    }

    static Notification service(Context c, boolean loc) {
        Intent i = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(c, SVC)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("LiteChat")
                .setContentText(loc ? "Connected · sharing location with trusted contacts" : "Connected")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    static void message(String peer, String title, String text) {
        Context c = App.ctx;
        Intent i = new Intent(c, ChatActivity.class).putExtra("code", peer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, peer.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, MSG)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        nm(c).notify(peer.hashCode(), n);
    }

    static void cancel(String peer) { nm(App.ctx).cancel(peer.hashCode()); }

    static void incomingCall(String peer, boolean video, Intent open) {
        Context c = App.ctx;
        PendingIntent pi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CALL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(Db.get().nameOf(peer))
                .setContentText(video ? "Incoming video call" : "Incoming voice call")
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setOngoing(true)
                .setTimeoutAfter(45000)
                .build();
        n.flags |= Notification.FLAG_INSISTENT;
        nm(c).notify(ID_CALL, n);
    }

    static void cancelCall() { nm(App.ctx).cancel(ID_CALL); }
}
