package com.usbexplorer.ntfs;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Se mantiene activo mientras el disco está montado. Si la app se cierra desde "recientes",
 * expulsa el disco de forma segura antes de que el proceso termine.
 */
public class DiskService extends Service {
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_NOT_STICKY; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Session.eject();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
