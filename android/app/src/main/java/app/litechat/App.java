package app.litechat;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {
    static Context ctx;
    static final ExecutorService io = Executors.newCachedThreadPool();
    static final Handler ui = new Handler(Looper.getMainLooper());

    @Override public void onCreate() {
        super.onCreate();
        ctx = this;
        Notif.init(this);
    }
}
