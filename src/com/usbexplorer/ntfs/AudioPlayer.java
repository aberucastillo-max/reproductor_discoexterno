package com.usbexplorer.ntfs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import com.usbexplorer.ntfs.core.Ntfs;

import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reproductor de música global (sigue sonando mientras navegas). Sin notificación ni controles en
 * la pantalla de bloqueo, para que ningún nombre de archivo aparezca fuera de la app.
 */
final class AudioPlayer {
    interface Listener { void onPlayerChanged(); }

    private static final AudioPlayer INSTANCE = new AudioPlayer();
    static AudioPlayer get() { return INSTANCE; }

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** stop/abrir/play pueden esperar al disco: nunca en el hilo principal. */
    private final ExecutorService vlc = Executors.newSingleThreadExecutor();
    private volatile int token;
    private volatile boolean busy;   // abriendo/cerrando: no preguntar a libVLC desde la pantalla
    private final List<Listener> listeners = new ArrayList<Listener>();
    private MediaPlayer mp;
    private volatile ParcelFileDescriptor pfd;
    private List<Ntfs.Entry> list = new ArrayList<Ntfs.Entry>();
    private int index = -1;
    private boolean shuffle;
    private Context app;
    String group;   // nombre del grupo que se está escuchando (si aplica)

    private AudioPlayer() {}

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        for (Listener l : new ArrayList<Listener>(listeners)) l.onPlayerChanged();
    }

    Ntfs.Entry current() { return index >= 0 && index < list.size() ? list.get(index) : null; }
    boolean active() { return mp != null && current() != null; }
    boolean isPlaying() { return mp != null && !busy && mp.isPlaying(); }
    long time() { return mp == null || busy ? 0 : mp.getTime(); }
    long length() { return mp == null || busy ? 0 : mp.getLength(); }
    boolean shuffle() { return shuffle; }

    void play(Context c, List<Ntfs.Entry> items, int start, String groupName) {
        app = c.getApplicationContext();
        list = new ArrayList<Ntfs.Entry>(items);
        group = groupName;
        if (shuffle && list.size() > 1) {
            Ntfs.Entry first = list.remove(start);
            Collections.shuffle(list, new Random());
            list.add(0, first);
            start = 0;
        }
        playIndex(start);
    }

    void setShuffle(boolean on) { shuffle = on; changed(); }

    private void playIndex(int i) {
        Session s = Session.current;
        if (s == null || i < 0 || i >= list.size()) { stopAll(); return; }
        if (mp == null) {
            mp = new MediaPlayer(Vlc.get(app));
            mp.setEventListener(new MediaPlayer.EventListener() {
                @Override public void onEvent(MediaPlayer.Event ev) {
                    if (ev.type == MediaPlayer.Event.EndReached) {
                        ui.post(new Runnable() { @Override public void run() { next(); } });
                    } else if (ev.type == MediaPlayer.Event.Playing || ev.type == MediaPlayer.Event.Paused
                            || ev.type == MediaPlayer.Event.Stopped || ev.type == MediaPlayer.Event.EncounteredError) {
                        ui.post(new Runnable() { @Override public void run() { changed(); } });
                    }
                }
            });
        }
        index = i;
        final MediaPlayer p = mp;
        final Ntfs.Entry e = list.get(i);
        final Session fs = s;
        final int t = ++token;
        vlc.execute(new Runnable() {
            @Override public void run() {
                if (t != token) return;
                busy = true;
                try {
                    p.stop();
                    closeFd();
                    pfd = ProxyFd.open(app, fs, e);
                    Media m = new Media(Vlc.get(app), pfd.getFileDescriptor());
                    p.setMedia(m);
                    m.release();
                    p.play();
                } catch (Throwable ex) {
                    CrashLog.event("Error al abrir audio: " + ex);
                    ui.post(new Runnable() { @Override public void run() { if (t == token) next(); } });
                } finally {
                    busy = false;
                    ui.post(new Runnable() { @Override public void run() { changed(); } });
                }
            }
        });
        changed();
    }

    void toggle() {
        if (mp == null || busy) return;
        if (mp.isPlaying()) mp.pause(); else mp.play();
        changed();
    }

    void pause() { if (mp != null && !busy && mp.isPlaying()) { mp.pause(); changed(); } }

    void next() {
        if (index + 1 < list.size()) playIndex(index + 1);
        else { if (mp != null) mp.stop(); changed(); }
    }

    void prev() {
        if (mp != null && !busy && mp.getTime() > 3000) { mp.setTime(0); return; }
        if (index > 0) playIndex(index - 1);
    }

    void seek(float f) { if (mp != null && !busy) mp.setPosition(f); }

    void stopAll() {
        Runnable r = new Runnable() {
            @Override public void run() {
                token++;
                final MediaPlayer p = mp;
                mp = null;
                vlc.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            if (p != null) { p.stop(); p.release(); }
                        } catch (Throwable t) {
                            CrashLog.event("Error al cerrar audio: " + t);
                        }
                        closeFd();
                    }
                });
                list = new ArrayList<Ntfs.Entry>();
                index = -1;
                group = null;
                changed();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else ui.post(r);
    }

    private synchronized void closeFd() {
        if (pfd != null) {
            try { pfd.close(); } catch (IOException ignored) { }
            pfd = null;
        }
    }
}
