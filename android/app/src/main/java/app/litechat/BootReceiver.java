package app.litechat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the connection after reboot / app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Prefs.registered()) CoreService.start(c);
    }
}
