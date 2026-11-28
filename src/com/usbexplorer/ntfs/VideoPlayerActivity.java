package com.usbexplorer.ntfs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.usbexplorer.ntfs.core.Ntfs;

import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reproductor de vídeo a pantalla completa (todos los códecs vía libVLC). */
public class VideoPlayerActivity extends Activity {
    private static final MediaPlayer.ScaleType[] SCALES = {
            MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_16_9, MediaPlayer.ScaleType.SURFACE_4_3, MediaPlayer.ScaleType.SURFACE_ORIGINAL};
    private static final String[] SCALE_NAMES = {"Ajustar", "Llenar pantalla", "16:9", "4:3", "Tamaño original"};
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Todas las operaciones de libVLC que pueden esperar al disco van aquí, nunca al hilo principal. */
    private final ExecutorService vlc = Executors.newSingleThreadExecutor();
    private volatile int playToken;
    /** true mientras libVLC abre/cierra un archivo: entonces no se le pregunta nada desde la pantalla. */
    private volatile boolean busy;
    private boolean hw = true;
    private MediaPlayer mp;
    private VLCVideoLayout layout;
    private volatile ParcelFileDescriptor pfd;
    private List<Ntfs.Entry> list;
    private int index;
    private View controls;
    private TextView title, timeText, lengthText, playBtn, msg, indicator, muteBtn;
    private VerticalBar volBar, brightBar;
    private android.media.AudioManager audio;
    private boolean muted;
    private int slop;
    private SeekBar seek;
    private boolean dragging;
    private int scale;
    // Estado del reproductor recibido por eventos: la pantalla NUNCA llama a libVLC para leerlo,
    // porque libVLC puede quedarse bloqueado esperando al disco.
    private long cTime, cLength;
    private boolean cPlaying;
    private float cRate = 1f;

    /** Envía una orden a libVLC en su hilo propio (nunca en el hilo principal). */
    private void vlcDo(final VlcAction a) {
        final MediaPlayer p = mp;
        if (p == null) return;
        try {
            vlc.execute(new Runnable() {
                @Override public void run() {
                    try { a.run(p); } catch (Throwable t) { CrashLog.event("libVLC: " + t); }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private interface VlcAction { void run(MediaPlayer p); }

    private final Runnable hideTask = new Runnable() {
        @Override public void run() {
            if (mp != null && cPlaying && !dragging && !volBar.isDragging() && !brightBar.isDragging()) setControls(false);
            else autoHide();
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (controls.getVisibility() == View.VISIBLE) refreshBars();
            if (mp != null && !dragging && dragMode != SEEK) {
                long len = cLength, t = cTime;
                if (len > 0) seek.setProgress((int) (t * 1000 / len));
                timeText.setText(Ui.time(t));
                lengthText.setText(Ui.time(len));
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
        android.content.SharedPreferences sp = getSharedPreferences("diagnostico", MODE_PRIVATE);
        hw = sp.getBoolean("hw", true);
        boolean crashedBefore = sp.getBoolean("reproduciendo", false);
        if (crashedBefore && hw) {   // la última reproducción no terminó bien: probar sin aceleración por hardware
            hw = false;
            sp.edit().putBoolean("hw", false).apply();
            CrashLog.event("Reproducción anterior interrumpida: se activa decodificación por software");
        }
        list = new ArrayList<Ntfs.Entry>(Session.viewerList);
        index = Session.viewerStart;
        if (list.isEmpty() || Session.current == null) { finish(); return; }
        AudioPlayer.get().pause();

        audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop() * 2;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        layout = new VLCVideoLayout(this);
        root.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        View touch = new View(this);
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        msg = Ui.text(this, "", 16, Ui.TEXT, null);
        msg.setGravity(Gravity.CENTER);
        msg.setShadowLayer(6, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        mlp.topMargin = Ui.dp(this, 90);
        root.addView(msg, mlp);
        indicator = Ui.text(this, "", 30, Ui.TEXT, Ui.MEDIUM);
        indicator.setGravity(Gravity.CENTER);
        indicator.setBackground(Ui.round(0xB0000000, Ui.dp(this, 14)));
        indicator.setPadding(Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 28), Ui.dp(this, 16));
        indicator.setVisibility(View.GONE);
        root.addView(indicator, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        controls = buildControls();
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                setControls(controls.getVisibility() != View.VISIBLE);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                skip(e.getX() < layout.getWidth() / 2f ? -10000 : 10000);
                return true;
            }
        });
        touch.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                gd.onTouchEvent(e);
                drag(v, e);
                return true;
            }
        });

        try {
            mp = new MediaPlayer(Vlc.get(this));
            mp.attachViews(layout, null, true, false);
        } catch (Throwable t) {
            CrashLog.event("No se pudo iniciar el motor de vídeo: " + t);
            msg.setText("No se pudo iniciar el motor de vídeo:\n" + t.getMessage());
            mp = null;
            return;
        }
        if (crashedBefore) msg.setText("La última vez el vídeo falló: ahora uso decodificación por software (botón «Decodif.» para cambiar)");
        mp.setEventListener(new MediaPlayer.EventListener() {
            @Override public void onEvent(final MediaPlayer.Event ev) {
                final int type = ev.type;
                final long tc = type == MediaPlayer.Event.TimeChanged ? ev.getTimeChanged() : -1;
                final long lc = type == MediaPlayer.Event.LengthChanged ? ev.getLengthChanged() : -1;
                ui.post(new Runnable() { @Override public void run() { onVlcEvent(type, tc, lc); } });
            }
        });
        immersive();
        play();
        ui.post(tick);
    }

    // ------------------------------------------------------------------ controles flotantes

    private View buildControls() {
        FrameLayout f = new FrameLayout(this);
        int p = Ui.dp(this, 14);

        // arriba: volver, título y opciones
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        top.setPadding(p, p, p, p * 3);
        TextView back = roundButton("←", 52, 22);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        title = Ui.text(this, "", 18, Ui.TEXT, Ui.MEDIUM);
        title.setSingleLine(true);
        title.setShadowLayer(6, 0, 0, Color.BLACK);
        title.setPadding(p, 0, p, 0);
        top.addView(back);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(smallButton("Audio", new View.OnClickListener() { @Override public void onClick(View v) { chooseAudio(); } }));
        top.addView(smallButton("Subtítulos", new View.OnClickListener() { @Override public void onClick(View v) { chooseSubs(); } }));
        top.addView(smallButton("Imagen", new View.OnClickListener() { @Override public void onClick(View v) { chooseScale(); } }));
        top.addView(smallButton("Velocidad", new View.OnClickListener() { @Override public void onClick(View v) { chooseSpeed(); } }));
        top.addView(smallButton("Decodif.", new View.OnClickListener() { @Override public void onClick(View v) { chooseDecoder(); } }));
        f.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // centro: anterior, -10, play/pausa, +10, siguiente
        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER);
        TextView prev = roundButton("⏮", 64, 24);
        prev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { move(-1); } });
        TextView back10 = roundButton("↺10", 72, 20);
        back10.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { skip(-10000); } });
        playBtn = roundButton("⏸", 96, 38);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mp == null || busy) return;
                final boolean wasPlaying = cPlaying;
                vlcDo(new VlcAction() { @Override public void run(MediaPlayer p) { if (wasPlaying) p.pause(); else p.play(); } });
                autoHide();
            }
        });
        TextView fwd10 = roundButton("10↻", 72, 20);
        fwd10.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { skip(10000); } });
        TextView next = roundButton("⏭", 64, 24);
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { move(1); } });
        for (TextView t : new TextView[]{prev, back10, playBtn, fwd10, next}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = lp.rightMargin = Ui.dp(this, 18);
            center.addView(t, lp);
        }
        f.addView(center, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // derecha: volumen (barra vertical) + silenciar
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        right.setBackground(Ui.round(0x66000000, Ui.dp(this, 28)));
        right.setPadding(Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 8));
        TextView volIcon = Ui.text(this, "🔊", 18, Ui.TEXT, null);
        volIcon.setGravity(Gravity.CENTER);
        volBar = new VerticalBar(this);
        volBar.setListener(new VerticalBar.Listener() {
            @Override public void onChange(float v, boolean user) {
                if (!user) return;
                if (muted) setMuted(false);
                setVolume(v);
                autoHide();
            }
        });
        muteBtn = roundButton("🔇", 48, 18);
        muteBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMuted(!muted); autoHide(); }
        });
        right.addView(volIcon);
        right.addView(volBar, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 220)));
        right.addView(muteBtn);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rlp.rightMargin = Ui.dp(this, 24);
        f.addView(right, rlp);

        // izquierda: brillo
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setBackground(Ui.round(0x66000000, Ui.dp(this, 28)));
        left.setPadding(Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12));
        TextView brIcon = Ui.text(this, "☀", 20, Ui.TEXT, null);
        brIcon.setGravity(Gravity.CENTER);
        brightBar = new VerticalBar(this);
        brightBar.setListener(new VerticalBar.Listener() {
            @Override public void onChange(float v, boolean user) { if (user) { setBrightness(v); autoHide(); } }
        });
        left.addView(brIcon);
        left.addView(brightBar, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 220)));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        llp.leftMargin = Ui.dp(this, 24);
        f.addView(left, llp);

        // abajo: tiempo y barra de progreso
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        bottom.setPadding(p * 2, p * 3, p * 2, p * 2);
        timeText = Ui.text(this, "0:00", 15, Ui.TEXT, Ui.MEDIUM);
        lengthText = Ui.text(this, "0:00", 15, Ui.TEXT, Ui.MEDIUM);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) timeText.setText(Ui.time(cLength * v / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; ui.removeCallbacks(hideTask); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                final float pos = s.getProgress() / 1000f;
                cTime = (long) (cLength * pos);
                vlcDo(new VlcAction() { @Override public void run(MediaPlayer p) { p.setPosition(pos); } });
                autoHide();
            }
        });
        bottom.addView(timeText);
        bottom.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        bottom.addView(lengthText);
        f.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        f.setClickable(false);
        return f;
    }

    /** Botón redondo semitransparente. */
    private TextView roundButton(String s, int sizeDp, int textSp) {
        TextView t = Ui.text(this, s, textSp, Ui.TEXT, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable n = new android.graphics.drawable.GradientDrawable();
        n.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        n.setColor(0x66000000);
        n.setStroke(Ui.dp(this, 1), 0x55FFFFFF);
        android.graphics.drawable.GradientDrawable pr = new android.graphics.drawable.GradientDrawable();
        pr.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        pr.setColor(0x99FFFFFF);
        bg.addState(new int[]{android.R.attr.state_pressed}, pr);
        bg.addState(new int[]{}, n);
        t.setBackground(bg);
        t.setClickable(true);
        int px = Ui.dp(this, sizeDp);
        t.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        t.setWidth(px);
        t.setHeight(px);
        t.setMinWidth(px);
        t.setMinHeight(px);
        return t;
    }

    private TextView smallButton(String s, View.OnClickListener l) {
        TextView t = Ui.button(this, s, 0x00000000);
        t.setTextSize(14);
        t.setShadowLayer(6, 0, 0, Color.BLACK);
        t.setOnClickListener(l);
        return t;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** Muestra u oculta los controles con un fundido; se ocultan solos a los 3,5 s. */
    private void setControls(boolean on) {
        controls.animate().cancel();
        if (on) {
            refreshBars();
            if (controls.getVisibility() != View.VISIBLE) {
                controls.setAlpha(0f);
                controls.setVisibility(View.VISIBLE);
            }
            controls.animate().alpha(1f).setDuration(150).start();
            autoHide();
        } else {
            controls.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                @Override public void run() { controls.setVisibility(View.GONE); }
            }).start();
        }
        immersive();
    }

    private void autoHide() {
        ui.removeCallbacks(hideTask);
        ui.postDelayed(hideTask, 3500);
    }

    // ------------------------------------------------------------------ volumen, brillo y gestos

    private float currentVolume() {
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0 : (float) audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / max;
    }

    private void setVolume(float v) {
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(Math.max(0, Math.min(1, v)) * max), 0);
        volBar.setValue(currentVolume());
    }

    private void setMuted(boolean m) {
        muted = m;
        vlcDo(new VlcAction() { @Override public void run(MediaPlayer p) { p.setVolume(muted ? 0 : 100); } });
        muteBtn.setText(m ? "🔈" : "🔇");
        muteBtn.setAlpha(m ? 1f : 0.85f);
        showIndicator(m ? "🔇  Silencio" : "🔊  " + Math.round(currentVolume() * 100) + " %");
        hideIndicatorSoon();
    }

    private float currentBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void setBrightness(float v) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(0.02f, Math.min(1f, v));
        getWindow().setAttributes(lp);
        brightBar.setValue(lp.screenBrightness);
    }

    private void refreshBars() {
        if (!volBar.isDragging()) volBar.setValue(muted ? 0 : currentVolume());
        if (!brightBar.isDragging()) brightBar.setValue(currentBrightness());
    }

    private void showIndicator(String s) {
        ui.removeCallbacks(hideIndicator);
        indicator.setText(s);
        indicator.setVisibility(View.VISIBLE);
    }

    private final Runnable hideIndicator = new Runnable() {
        @Override public void run() { indicator.setVisibility(View.GONE); }
    };

    private void hideIndicatorSoon() {
        ui.removeCallbacks(hideIndicator);
        ui.postDelayed(hideIndicator, 700);
    }

    private static final int NONE = 0, SEEK = 1, VOLUME = 2, BRIGHT = 3, IGNORE = 4;
    private int dragMode;
    private float downX, downY, startValue;
    private long startTime, seekTarget;

    /**
     * Deslizar en horizontal: avanzar / retroceder (vista previa del tiempo y salto al soltar).
     * Deslizar en vertical: a la derecha volumen, a la izquierda brillo.
     */
    private void drag(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragMode = NONE;
                int edge = Ui.dp(this, 28);
                if (downX < edge || downX > v.getWidth() - edge || downY < edge || downY > v.getHeight() - edge) dragMode = IGNORE;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (dragMode == NONE && Math.hypot(dx, dy) > slop) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (mp == null || busy || cLength <= 0) { dragMode = IGNORE; break; }
                        dragMode = SEEK;
                        startTime = cTime;
                    } else if (downX > v.getWidth() / 2f) {
                        dragMode = VOLUME;
                        startValue = muted ? 0 : currentVolume();
                        if (muted) setMuted(false);
                    } else {
                        dragMode = BRIGHT;
                        startValue = currentBrightness();
                    }
                    ui.removeCallbacks(hideTask);
                }
                if (dragMode == SEEK) {
                    long len = cLength;
                    // un ancho de pantalla = 2 minutos (o todo el vídeo si dura menos)
                    long window = Math.min(len, 120000L);
                    long delta = (long) (dx / v.getWidth() * window);
                    seekTarget = Math.max(0, Math.min(len - 500, startTime + delta));
                    long d = seekTarget - startTime;
                    showIndicator((d >= 0 ? "⏩  +" : "⏪  −") + Ui.time(Math.abs(d)) + "\n"
                            + Ui.time(seekTarget) + " / " + Ui.time(len));
                    indicator.setTextSize(26);
                    if (len > 0) seek.setProgress((int) (seekTarget * 1000 / len));
                    timeText.setText(Ui.time(seekTarget));
                } else if (dragMode == VOLUME || dragMode == BRIGHT) {
                    float nv = Math.max(0, Math.min(1, startValue - dy / (v.getHeight() * 0.6f)));
                    if (dragMode == VOLUME) {
                        setVolume(nv);
                        showIndicator("🔊  " + Math.round(currentVolume() * 100) + " %");
                    } else {
                        setBrightness(nv);
                        showIndicator("☀  " + Math.round(nv * 100) + " %");
                    }
                    indicator.setTextSize(30);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragMode == SEEK) {
                    final long target = seekTarget;
                    cTime = target;
                    vlcDo(new VlcAction() { @Override public void run(MediaPlayer p) { p.setTime(target); } });
                }
                if (dragMode == SEEK || dragMode == VOLUME || dragMode == BRIGHT) {
                    hideIndicatorSoon();
                    if (controls.getVisibility() == View.VISIBLE) autoHide();
                }
                dragMode = NONE;
                break;
        }
    }

    private void play() {
        final Session s = Session.current;
        if (s == null) { finish(); return; }
        final MediaPlayer p = mp;
        if (p == null) return;
        final Ntfs.Entry e = list.get(index);
        title.setText((list.size() > 1 ? (index + 1) + "/" + list.size() + "  " : "") + e.name);
        msg.setText("Abriendo…");
        final int token = ++playToken;
        cTime = 0; cLength = 0; cPlaying = false;
        final boolean useHw = hw;
        final android.content.Context ctx = getApplicationContext();
        vlc.execute(new Runnable() {
            @Override public void run() {
                if (token != playToken) return;
                busy = true;
                try {
                    p.stop();
                    closeFd();
                    pfd = ProxyFd.open(ctx, s, e);
                    Media m = new Media(Vlc.get(ctx), pfd.getFileDescriptor());
                    m.setHWDecoderEnabled(useHw, false);
                    p.setMedia(m);
                    m.release();
                    markPlaying(true);
                    CrashLog.event("Reproduciendo vídeo ." + com.usbexplorer.ntfs.core.MediaType.ext(e.name)
                            + " (" + (e.size >> 20) + " MB, " + (useHw ? "HW" : "SW") + ")");
                    p.play();
                    final long startMs = Session.viewerStartMs;
                    if (startMs > 0) {
                        Session.viewerStartMs = 0;
                        Thread.sleep(400);
                        p.setTime(startMs);
                    }
                    ui.post(new Runnable() {
                        @Override public void run() { if (mp != null) mp.setVideoScale(SCALES[scale]); }
                    });
                } catch (final Throwable ex) {
                    CrashLog.event("Error al abrir vídeo: " + ex);
                    ui.post(new Runnable() { @Override public void run() { msg.setText("No se pudo abrir el archivo"); } });
                } finally {
                    busy = false;
                }
            }
        });
        setControls(true);
    }

    private void markPlaying(boolean on) {
        getSharedPreferences("diagnostico", MODE_PRIVATE).edit().putBoolean("reproduciendo", on).commit();
    }

    private void chooseDecoder() {
        String[] n = {"Hardware (más fluido, menos batería)", "Software (más compatible)"};
        Ui.show(Ui.dialog(this).setTitle("Decodificación").setSingleChoiceItems(n, hw ? 0 : 1, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                d.dismiss();
                boolean nhw = w == 0;
                if (nhw == hw) return;
                hw = nhw;
                getSharedPreferences("diagnostico", MODE_PRIVATE).edit().putBoolean("hw", hw).apply();
                final long t = cTime;
                play();
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (t > 0) vlcDo(new VlcAction() { @Override public void run(MediaPlayer p) { p.setTime(t); } });
                    }
                }, 1200);
            }
        }));
    }

    private void onVlcEvent(int type, long timeChanged, long lengthChanged) {
        if (mp == null) return;
        if (type == MediaPlayer.Event.TimeChanged) { if (dragMode != SEEK) cTime = timeChanged; return; }
        if (type == MediaPlayer.Event.LengthChanged) { cLength = lengthChanged; return; }
        if (type == MediaPlayer.Event.Playing) cPlaying = true;
        else if (type == MediaPlayer.Event.Paused || type == MediaPlayer.Event.Stopped
                || type == MediaPlayer.Event.EndReached || type == MediaPlayer.Event.EncounteredError) cPlaying = false;
        if (busy) return;
        if (type == MediaPlayer.Event.Playing) {
            playBtn.setText("⏸");
            msg.setText("");
            if (muted) vlcDo(new VlcAction() { @Override public void run(MediaPlayer p) { p.setVolume(0); } });
        }
        else if (type == MediaPlayer.Event.Paused || type == MediaPlayer.Event.Stopped) playBtn.setText("▶");
        else if (type == MediaPlayer.Event.EndReached) { if (index + 1 < list.size()) move(1); else { playBtn.setText("▶"); setControls(true); } }
        else if (type == MediaPlayer.Event.EncounteredError) {
            CrashLog.event("libVLC: error de reproducción");
            msg.setText("No se pudo reproducir: el archivo puede estar dañado, o el disco dejó de responder.");
            setControls(true);
        }
    }

    private void move(int d) {
        int n = index + d;
        if (n < 0 || n >= list.size()) return;
        index = n;
        play();
    }

    private void skip(long ms) {
        if (mp == null || busy) return;
        long t = Math.max(0, cTime + ms);
        if (cLength > 0) t = Math.min(t, cLength - 500);
        final long target = t;
        cTime = t;
        vlcDo(new VlcAction() { @Override public void run(MediaPlayer p) { p.setTime(target); } });
        indicator.setTextSize(26);
        showIndicator((ms > 0 ? "⏩  +10 s\n" : "⏪  −10 s\n") + Ui.time(t));
        hideIndicatorSoon();
    }

    private void chooseTrack(final boolean audio) {
        // las pistas se consultan en el hilo de libVLC y el diálogo se muestra después
        vlcDo(new VlcAction() {
            @Override public void run(MediaPlayer p) {
                final MediaPlayer.TrackDescription[] t = audio ? p.getAudioTracks() : p.getSpuTracks();
                final int cur = audio ? p.getAudioTrack() : p.getSpuTrack();
                ui.post(new Runnable() { @Override public void run() { showTracks(audio, t, cur); } });
            }
        });
    }

    private void showTracks(final boolean audio, final MediaPlayer.TrackDescription[] t, int cur) {
        if (isFinishing()) return;
        if (t == null || t.length == 0) { msg.setText(audio ? "Sin pistas de audio" : "Este vídeo no tiene subtítulos"); return; }
        String[] names = new String[t.length];
        int sel = -1;
        for (int i = 0; i < t.length; i++) { names[i] = t[i].name; if (t[i].id == cur) sel = i; }
        Ui.show(Ui.dialog(this).setTitle(audio ? "Pista de audio" : "Subtítulos")
                .setSingleChoiceItems(names, sel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, final int w) {
                        vlcDo(new VlcAction() {
                            @Override public void run(MediaPlayer p) { if (audio) p.setAudioTrack(t[w].id); else p.setSpuTrack(t[w].id); }
                        });
                        d.dismiss();
                    }
                }));
    }

    private void chooseAudio() { chooseTrack(true); }
    private void chooseSubs() { chooseTrack(false); }

    private void chooseScale() {
        Ui.show(Ui.dialog(this).setTitle("Imagen").setSingleChoiceItems(SCALE_NAMES, scale, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                scale = w;
                mp.setVideoScale(SCALES[w]);
                d.dismiss();
            }
        }));
    }

    private void chooseSpeed() {
        String[] n = new String[SPEEDS.length];
        int sel = 2;
        for (int i = 0; i < SPEEDS.length; i++) {
            n[i] = (SPEEDS[i] == (int) SPEEDS[i] ? String.valueOf((int) SPEEDS[i]) : String.valueOf(SPEEDS[i])) + "×";
            if (Math.abs(cRate - SPEEDS[i]) < 0.01f) sel = i;
        }
        AlertDialog.Builder b = Ui.dialog(this).setTitle("Velocidad").setSingleChoiceItems(n, sel, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                final float r = SPEEDS[w];
                cRate = r;
                vlcDo(new VlcAction() { @Override public void run(MediaPlayer p) { p.setRate(r); } });
                d.dismiss();
            }
        });
        Ui.show(b);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Thumbs.get().setPaused(false);
        if (cPlaying) vlcDo(new VlcAction() { @Override public void run(MediaPlayer p) { if (p.isPlaying()) p.pause(); } });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.current == null) { finish(); return; }
        Thumbs.get().setPaused(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        playToken++;
        final MediaPlayer p = mp;
        mp = null;
        if (p != null) {
            try { p.detachViews(); } catch (Throwable ignored) { }
        }
        vlc.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (p != null) { p.stop(); p.release(); }
                } catch (Throwable t) {
                    CrashLog.event("Error al cerrar el reproductor: " + t);
                }
                closeFd();
                markPlaying(false);
            }
        });
        vlc.shutdown();
    }

    private synchronized void closeFd() {
        if (pfd != null) {
            try { pfd.close(); } catch (IOException ignored) { }
            pfd = null;
        }
    }
}
