package com.usbexplorer.ntfs;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.usbexplorer.ntfs.core.MediaType;
import com.usbexplorer.ntfs.core.Ntfs;

import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multivídeo: 2, 3 o 4 vídeos aleatorios del disco a la vez, cada uno con sus controles.
 * Para cuidar el disco:
 *  - lectura en bloques grandes de 8 MB (el cabezal salta una vez por bloque) y clips pequeños
 *    cargados enteros en memoria (después el disco descansa);
 *  - antes de pasar a 3 o 4 ventanas se hace una prueba de capacidad del disco;
 *  - si una ventana se congela o se detiene, se cierra sola con un aviso.
 * Solo una ventana tiene sonido a la vez.
 */
public class MultiPlayerActivity extends Activity {
    private static final int CHUNK = 8 << 20;
    private static final long FULL_MAX = 48L << 20;
    private static final long STALL_MS = 9000;
    private static final double NEED_MBPS_PER_VIDEO = 2.0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final List<Pane> panes = new ArrayList<Pane>();
    private List<Ntfs.Entry> all;
    private FrameLayout area;
    private TextView notice;
    private LinearLayout top;
    private boolean testing;
    private final List<TextView> layoutButtons = new ArrayList<TextView>();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            for (Pane p : new ArrayList<Pane>(panes)) {
                p.updateUi();
                // ventana congelada: debería avanzar y no avanza
                if (p.wantPlay && !p.busy && p.openedAt > 0 && now - p.openedAt > STALL_MS
                        && now - p.lastProgressAt > STALL_MS && panes.size() > 1) {
                    CrashLog.event("Multivídeo: ventana detenida, se cierra (" + panes.size() + " ventanas)");
                    closePane(p, "Se cerró una ventana: el disco no daba abasto para " + panes.size() + " vídeos");
                }
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.secure(this);
        CrashLog.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        all = new ArrayList<Ntfs.Entry>(Session.viewerList);
        if (all.isEmpty() || Session.current == null) { finish(); return; }
        AudioPlayer.get().pause();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF111111);
        int p = Ui.dp(this, 6);
        top.setPadding(p, p, p, p);
        top.addView(topButton("←", new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }));
        TextView title = Ui.text(this, "Multivídeo aleatorio", 16, Ui.TEXT, Ui.MEDIUM);
        title.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        for (final int n : new int[]{2, 3, 4}) {
            TextView t = topButton(n == 2 ? "▯▯  2" : n == 3 ? "▮▯▯  3" : "▦  4", new View.OnClickListener() {
                @Override public void onClick(View v) { requestCount(n); }
            });
            layoutButtons.add(t);
            top.addView(t);
        }
        top.addView(topButton("🔀  Todos", new View.OnClickListener() {
            @Override public void onClick(View v) { for (Pane x : panes) x.playRandom(); }
        }));
        col.addView(top);
        area = new FrameLayout(this) {
            @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                ui.post(new Runnable() { @Override public void run() { arrange(); } });
            }
        };
        col.addView(area, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        notice = Ui.text(this, "", 14, Ui.TEXT, null);
        notice.setBackground(Ui.round(0xDD222222, Ui.dp(this, 18)));
        notice.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        nl.bottomMargin = Ui.dp(this, 24);
        root.addView(notice, nl);
        setContentView(root);
        immersive();

        // 2 ventanas siempre (sin prueba); 3 y 4 con prueba de capacidad
        addPane();
        addPane();
        panes.get(0).setSound(true);
        arrange();
        ui.post(tick);
    }

    private TextView topButton(String s, View.OnClickListener l) {
        TextView t = Ui.button(this, s, 0xFF2A2A2A);
        t.setTextSize(14);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private final Runnable hideNotice = new Runnable() {
        @Override public void run() { notice.setVisibility(View.GONE); }
    };

    /** Aviso pequeño (dentro de la ventana protegida, no un Toast del sistema). */
    void notice(String s) {
        notice.setText(s);
        notice.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideNotice);
        ui.postDelayed(hideNotice, 3500);
    }

    // ------------------------------------------------------------------ número de ventanas

    private void requestCount(final int n) {
        if (testing) return;
        if (n <= panes.size()) {
            while (panes.size() > n) closePane(panes.get(panes.size() - 1), null);
            arrange();
            return;
        }
        if (n <= 2) {
            while (panes.size() < n) addPane();
            arrange();
            return;
        }
        // Prueba de capacidad: lecturas reales en zonas distintas del disco mientras se reproduce
        testing = true;
        notice("Probando la capacidad del disco para " + n + " vídeos…");
        final Session s = Session.current;
        new Thread(new Runnable() {
            @Override public void run() {
                double mbps = -1;
                long worst = 0;
                try {
                    byte[] buf = new byte[4 << 20];
                    long bytes = 0, t0 = SystemClock.elapsedRealtime();
                    for (int i = 0; i < n; i++) {
                        Ntfs.Entry e = all.get(rnd.nextInt(all.size()));
                        if (e.size < buf.length * 2L) continue;
                        Ntfs.FileHandle h = s.fs.open(e.ref);
                        long off = (long) (rnd.nextDouble() * (h.size() - buf.length));
                        long a = SystemClock.elapsedRealtime();
                        int got = 0;
                        while (got < buf.length) {
                            int r = s.fs.read(h, off + got, buf, got, Math.min(1 << 20, buf.length - got));
                            if (r <= 0) break;
                            got += r;
                        }
                        worst = Math.max(worst, SystemClock.elapsedRealtime() - a);
                        bytes += got;
                    }
                    long dt = Math.max(1, SystemClock.elapsedRealtime() - t0);
                    if (bytes > 0) mbps = bytes / 1048576.0 / (dt / 1000.0);
                } catch (Throwable t) {
                    mbps = 0;
                }
                final double fm = mbps;
                final long fw = worst;
                ui.post(new Runnable() {
                    @Override public void run() {
                        testing = false;
                        if (isFinishing()) return;
                        boolean ok = fm < 0 || (fm >= n * NEED_MBPS_PER_VIDEO && fw <= 2500);
                        CrashLog.event(String.format("Multivídeo: prueba para %d vídeos: %.1f MB/s, peor lectura %d ms -> %s",
                                n, Math.max(fm, 0), fw, ok ? "OK" : "insuficiente"));
                        if (!ok) {
                            notice(String.format("El disco no da para %d vídeos (%.1f MB/s). Sigues con %d.", n, Math.max(fm, 0), panes.size()));
                            return;
                        }
                        while (panes.size() < n) addPane();
                        arrange();
                        notice(fm < 0 ? n + " vídeos" : String.format("%d vídeos · el disco da %.0f MB/s", n, fm));
                    }
                });
            }
        }, "prueba-capacidad").start();
    }

    private void addPane() {
        Pane p = new Pane(this);
        panes.add(p);
        area.addView(p.root, new FrameLayout.LayoutParams(1, 1));
        p.playRandom();
    }

    void closePane(Pane p, String why) {
        if (!panes.remove(p)) return;
        boolean hadSound = p.sound;
        p.release();
        area.removeView(p.root);
        if (hadSound && !panes.isEmpty()) panes.get(0).setSound(true);
        arrange();
        if (why != null) notice(why);
    }

    /** Coloca las ventanas: 1 = completa, 2 = lado a lado, 3 = una grande + dos, 4 = cuadrícula. */
    void arrange() {
        int w = area.getWidth(), h = area.getHeight(), n = panes.size(), g = Ui.dp(this, 2);
        if (w == 0 || h == 0 || n == 0) return;
        int[][] r = new int[n][];
        if (n == 1) r[0] = new int[]{0, 0, w, h};
        else if (n == 2) {
            boolean portrait = h > w;
            if (portrait) { r[0] = new int[]{0, 0, w, h / 2 - g}; r[1] = new int[]{0, h / 2 + g, w, h - h / 2 - g}; }
            else { r[0] = new int[]{0, 0, w / 2 - g, h}; r[1] = new int[]{w / 2 + g, 0, w - w / 2 - g, h}; }
        } else if (n == 3) {
            int big = w * 2 / 3;
            r[0] = new int[]{0, 0, big - g, h};
            r[1] = new int[]{big + g, 0, w - big - g, h / 2 - g};
            r[2] = new int[]{big + g, h / 2 + g, w - big - g, h - h / 2 - g};
        } else {
            r[0] = new int[]{0, 0, w / 2 - g, h / 2 - g};
            r[1] = new int[]{w / 2 + g, 0, w - w / 2 - g, h / 2 - g};
            r[2] = new int[]{0, h / 2 + g, w / 2 - g, h - h / 2 - g};
            r[3] = new int[]{w / 2 + g, h / 2 + g, w - w / 2 - g, h - h / 2 - g};
        }
        for (int i = 0; i < n; i++) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, r[i][2]), Math.max(1, r[i][3]));
            lp.leftMargin = r[i][0];
            lp.topMargin = r[i][1];
            panes.get(i).root.setLayoutParams(lp);
        }
        for (int i = 0; i < layoutButtons.size(); i++) {
            layoutButtons.get(i).setBackground(Ui.round(i + 2 == n ? Ui.ACCENT : 0xFF2A2A2A, Ui.dp(this, 4)));
        }
    }

    Ntfs.Entry pickRandom(Pane self) {
        for (int tries = 0; tries < 20; tries++) {
            Ntfs.Entry e = all.get(rnd.nextInt(all.size()));
            boolean used = false;
            for (Pane p : panes) if (p != self && p.entry == e) used = true;
            if (!used) return e;
        }
        return all.get(rnd.nextInt(all.size()));
    }

    void giveSound(Pane p) {
        for (Pane x : panes) x.setSound(x == p);
    }

    /** Ampliar: abre esa ventana en el reproductor completo, en el mismo punto. */
    void expand(Pane p) {
        if (p.entry == null) return;
        for (Pane x : panes) x.pause();
        List<Ntfs.Entry> one = new ArrayList<Ntfs.Entry>();
        one.add(p.entry);
        Session.viewerList = one;
        Session.viewerStart = 0;
        Session.viewerStartMs = p.cTime;
        startActivity(new Intent(this, VideoPlayerActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.current == null) { finish(); return; }
        Thumbs.get().setPaused(true);
        immersive();
        Session.viewerList = all;   // el reproductor ampliado la cambia
    }

    @Override
    protected void onPause() {
        super.onPause();
        Thumbs.get().setPaused(false);
        for (Pane p : panes) p.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        for (Pane p : panes) p.release();
        panes.clear();
    }

    // ================================================================== una ventana

    static final class Pane {
        final MultiPlayerActivity a;
        final FrameLayout root;
        final VLCVideoLayout layout;
        final ExecutorService vlc = Executors.newSingleThreadExecutor();
        MediaPlayer mp;
        volatile ParcelFileDescriptor pfd;
        Ntfs.Entry entry;
        volatile boolean busy;
        boolean wantPlay, sound, cPlaying;
        long cTime, cLength, lastProgressAt, openedAt;
        int token;
        final View controls;
        final TextView title, playBtn, soundBtn, timeText, indicator;
        final SeekBar seek;
        boolean dragging;
        float downX;
        boolean seeking;
        long seekStart, seekTarget;

        private final Runnable hide = new Runnable() {
            @Override public void run() { if (cPlaying && !dragging) controls.setVisibility(View.GONE); }
        };

        Pane(MultiPlayerActivity act) {
            a = act;
            Context c = act;
            root = new FrameLayout(c);
            root.setBackgroundColor(Color.BLACK);
            layout = new VLCVideoLayout(c);
            root.addView(layout, new FrameLayout.LayoutParams(-1, -1));
            View touch = new View(c);
            root.addView(touch, new FrameLayout.LayoutParams(-1, -1));
            indicator = Ui.text(c, "", 18, Ui.TEXT, Ui.MEDIUM);
            indicator.setBackground(Ui.round(0xB0000000, Ui.dp(c, 10)));
            indicator.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
            indicator.setGravity(Gravity.CENTER);
            indicator.setVisibility(View.GONE);
            root.addView(indicator, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

            FrameLayout ctl = new FrameLayout(c);
            LinearLayout topRow = new LinearLayout(c);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            topRow.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
            int p = Ui.dp(c, 6);
            topRow.setPadding(p, p, p, p * 3);
            title = Ui.text(c, "", 13, Ui.TEXT, Ui.MEDIUM);
            title.setSingleLine(true);
            title.setShadowLayer(4, 0, 0, Color.BLACK);
            topRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            soundBtn = small("🔇", new View.OnClickListener() { @Override public void onClick(View v) { a.giveSound(Pane.this); show(); } });
            topRow.addView(soundBtn);
            topRow.addView(small("🔀", new View.OnClickListener() { @Override public void onClick(View v) { playRandom(); } }));
            topRow.addView(small("⛶", new View.OnClickListener() { @Override public void onClick(View v) { a.expand(Pane.this); } }));
            topRow.addView(small("✕", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (a.panes.size() > 1) a.closePane(Pane.this, null); else a.finish();
                }
            }));
            ctl.addView(topRow, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

            LinearLayout center = new LinearLayout(c);
            center.setGravity(Gravity.CENTER);
            center.addView(round("↺10", 44, new View.OnClickListener() { @Override public void onClick(View v) { skip(-10000); } }));
            playBtn = round("⏸", 56, new View.OnClickListener() { @Override public void onClick(View v) { toggle(); } });
            center.addView(playBtn);
            center.addView(round("10↻", 44, new View.OnClickListener() { @Override public void onClick(View v) { skip(10000); } }));
            ctl.addView(center, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

            LinearLayout bottom = new LinearLayout(c);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
            bottom.setPadding(p * 2, p * 3, p * 2, p);
            timeText = Ui.text(c, "0:00", 12, Ui.TEXT, null);
            seek = new SeekBar(c);
            seek.setMax(1000);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean user) { if (user) timeText.setText(Ui.time(cLength * v / 1000)); }
                @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
                @Override public void onStopTrackingTouch(SeekBar s) {
                    dragging = false;
                    final float pos = s.getProgress() / 1000f;
                    cTime = (long) (cLength * pos);
                    lastProgressAt = SystemClock.elapsedRealtime();
                    run(new Action() { @Override public void on(MediaPlayer m) { m.setPosition(pos); } });
                    show();
                }
            });
            bottom.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
            bottom.addView(timeText);
            ctl.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            controls = ctl;
            root.addView(ctl, new FrameLayout.LayoutParams(-1, -1));

            final GestureDetector gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (controls.getVisibility() == View.VISIBLE) controls.setVisibility(View.GONE); else show();
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    skip(e.getX() < root.getWidth() / 2f ? -10000 : 10000);
                    return true;
                }
            });
            touch.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    gd.onTouchEvent(e);
                    swipe(v, e);
                    return true;
                }
            });

            try {
                mp = new MediaPlayer(Vlc.get(c));
                mp.attachViews(layout, null, false, false);
                mp.setEventListener(new MediaPlayer.EventListener() {
                    @Override public void onEvent(MediaPlayer.Event ev) {
                        final int type = ev.type;
                        final long tc = type == MediaPlayer.Event.TimeChanged ? ev.getTimeChanged() : -1;
                        final long lc = type == MediaPlayer.Event.LengthChanged ? ev.getLengthChanged() : -1;
                        a.ui.post(new Runnable() { @Override public void run() { Pane.this.onEvent(type, tc, lc); } });
                    }
                });
            } catch (Throwable t) {
                CrashLog.event("Multivídeo: no se pudo crear el reproductor: " + t);
                mp = null;
            }
            show();
        }

        private TextView small(String s, View.OnClickListener l) {
            TextView t = Ui.button(a, s, 0x00000000);
            t.setTextSize(16);
            t.setShadowLayer(4, 0, 0, Color.BLACK);
            t.setOnClickListener(l);
            return t;
        }

        private TextView round(String s, int dp, View.OnClickListener l) {
            TextView t = Ui.text(a, s, dp > 50 ? 24 : 15, Ui.TEXT, Ui.MEDIUM);
            t.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0x77000000);
            g.setStroke(Ui.dp(a, 1), 0x55FFFFFF);
            t.setBackground(g);
            int px = Ui.dp(a, dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
            lp.leftMargin = lp.rightMargin = Ui.dp(a, 10);
            t.setLayoutParams(lp);
            t.setClickable(true);
            t.setOnClickListener(l);
            return t;
        }

        interface Action { void on(MediaPlayer m); }

        /** Órdenes a libVLC siempre en el hilo de esta ventana, nunca en el principal. */
        void run(final Action x) {
            final MediaPlayer m = mp;
            if (m == null) return;
            try {
                vlc.execute(new Runnable() {
                    @Override public void run() {
                        try { x.on(m); } catch (Throwable t) { CrashLog.event("Multivídeo libVLC: " + t); }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }

        void show() {
            controls.setVisibility(View.VISIBLE);
            a.ui.removeCallbacks(hide);
            a.ui.postDelayed(hide, 3500);
        }

        void playRandom() {
            final Session s = Session.current;
            if (s == null || mp == null) return;
            final Ntfs.Entry e = a.pickRandom(this);
            entry = e;
            title.setText(e.name);
            cTime = 0; cLength = 0; cPlaying = false;
            wantPlay = true;
            openedAt = 0;
            lastProgressAt = SystemClock.elapsedRealtime();
            final int t = ++token;
            final Context ctx = a.getApplicationContext();
            final MediaPlayer m = mp;
            final boolean withSound = sound;
            show();
            try {
                vlc.execute(new Runnable() {
                    @Override public void run() {
                        if (t != token) return;
                        busy = true;
                        try {
                            m.stop();
                            closeFd();
                            pfd = ProxyFd.openBuffered(ctx, s, e, CHUNK, FULL_MAX);
                            Media md = new Media(Vlc.get(ctx), pfd.getFileDescriptor());
                            md.setHWDecoderEnabled(true, false);
                            md.addOption(":file-caching=4000");
                            m.setMedia(md);
                            md.release();
                            m.play();
                            m.setVolume(withSound ? 100 : 0);
                            CrashLog.event("Multivídeo: ." + MediaType.ext(e.name) + " (" + (e.size >> 20) + " MB)");
                        } catch (Throwable ex) {
                            CrashLog.event("Multivídeo: error al abrir: " + ex.getClass().getSimpleName());
                            a.ui.post(new Runnable() { @Override public void run() { if (t == token) playRandom(); } });
                        } finally {
                            busy = false;
                            a.ui.post(new Runnable() {
                                @Override public void run() {
                                    openedAt = SystemClock.elapsedRealtime();
                                    lastProgressAt = openedAt;
                                }
                            });
                        }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }

        void onEvent(int type, long tc, long lc) {
            if (mp == null) return;
            if (type == MediaPlayer.Event.TimeChanged) {
                if (tc != cTime) lastProgressAt = SystemClock.elapsedRealtime();
                if (!seeking) cTime = tc;
            } else if (type == MediaPlayer.Event.LengthChanged) {
                cLength = lc;
            } else if (type == MediaPlayer.Event.Playing) {
                cPlaying = true;
                lastProgressAt = SystemClock.elapsedRealtime();
                if (!sound) run(new Action() { @Override public void on(MediaPlayer m) { m.setVolume(0); } });
            } else if (type == MediaPlayer.Event.Paused || type == MediaPlayer.Event.Stopped) {
                cPlaying = false;
            } else if (type == MediaPlayer.Event.EndReached) {
                cPlaying = false;
                if (!busy) playRandom();   // al terminar, otro aleatorio
            } else if (type == MediaPlayer.Event.EncounteredError) {
                cPlaying = false;
                if (!busy) {
                    if (a.panes.size() > 1) a.closePane(this, "Se cerró una ventana: el vídeo no se pudo leer");
                    else playRandom();
                }
            }
        }

        void updateUi() {
            playBtn.setText(cPlaying ? "⏸" : "▶");
            if (!dragging && !seeking && cLength > 0) seek.setProgress((int) (cTime * 1000 / cLength));
            if (!dragging) timeText.setText(Ui.time(cTime) + " / " + Ui.time(cLength));
        }

        void toggle() {
            if (busy) return;
            final boolean was = cPlaying;
            wantPlay = !was;
            lastProgressAt = SystemClock.elapsedRealtime();
            run(new Action() { @Override public void on(MediaPlayer m) { if (was) m.pause(); else m.play(); } });
            show();
        }

        void pause() {
            if (!cPlaying) return;
            wantPlay = false;
            run(new Action() { @Override public void on(MediaPlayer m) { if (m.isPlaying()) m.pause(); } });
        }

        void skip(long ms) {
            if (busy) return;
            long t = Math.max(0, cTime + ms);
            if (cLength > 0) t = Math.min(t, cLength - 500);
            final long target = t;
            cTime = t;
            lastProgressAt = SystemClock.elapsedRealtime();
            run(new Action() { @Override public void on(MediaPlayer m) { m.setTime(target); } });
            flash((ms > 0 ? "⏩ +10 s  " : "⏪ −10 s  ") + Ui.time(t));
        }

        void setSound(boolean on) {
            sound = on;
            soundBtn.setText(on ? "🔊" : "🔇");
            run(new Action() { @Override public void on(MediaPlayer m) { m.setVolume(sound ? 100 : 0); } });
        }

        private final Runnable hideIndicator = new Runnable() {
            @Override public void run() { indicator.setVisibility(View.GONE); }
        };

        void flash(String s) {
            indicator.setText(s);
            indicator.setVisibility(View.VISIBLE);
            a.ui.removeCallbacks(hideIndicator);
            a.ui.postDelayed(hideIndicator, 800);
        }

        /** Deslizar en horizontal dentro de la ventana: avanzar / retroceder. */
        void swipe(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    seeking = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - downX;
                    if (!seeking && Math.abs(dx) > Ui.dp(a, 24) && cLength > 0 && !busy) {
                        seeking = true;
                        seekStart = cTime;
                    }
                    if (seeking) {
                        long window = Math.min(cLength, 120000L);
                        seekTarget = Math.max(0, Math.min(cLength - 500, seekStart + (long) (dx / v.getWidth() * window)));
                        long d = seekTarget - seekStart;
                        indicator.setText((d >= 0 ? "⏩ +" : "⏪ −") + Ui.time(Math.abs(d)) + "\n" + Ui.time(seekTarget));
                        indicator.setVisibility(View.VISIBLE);
                        a.ui.removeCallbacks(hideIndicator);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (seeking) {
                        final long target = seekTarget;
                        cTime = target;
                        lastProgressAt = SystemClock.elapsedRealtime();
                        run(new Action() { @Override public void on(MediaPlayer m) { m.setTime(target); } });
                        a.ui.postDelayed(hideIndicator, 600);
                    }
                    seeking = false;
                    break;
            }
        }

        synchronized void closeFd() {
            if (pfd != null) {
                try { pfd.close(); } catch (IOException ignored) { }
                pfd = null;
            }
        }

        void release() {
            token++;
            a.ui.removeCallbacks(hide);
            final MediaPlayer m = mp;
            mp = null;
            if (m != null) {
                try { m.detachViews(); } catch (Throwable ignored) { }
            }
            try {
                vlc.execute(new Runnable() {
                    @Override public void run() {
                        try { if (m != null) { m.stop(); m.release(); } } catch (Throwable ignored) { }
                        closeFd();
                    }
                });
                vlc.shutdown();
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }
}
