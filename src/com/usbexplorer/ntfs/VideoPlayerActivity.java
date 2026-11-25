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

/** Reproductor de vídeo a pantalla completa (todos los códecs vía libVLC). */
public class VideoPlayerActivity extends Activity {
    private static final MediaPlayer.ScaleType[] SCALES = {
            MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_16_9, MediaPlayer.ScaleType.SURFACE_4_3, MediaPlayer.ScaleType.SURFACE_ORIGINAL};
    private static final String[] SCALE_NAMES = {"Ajustar", "Llenar pantalla", "16:9", "4:3", "Tamaño original"};
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private VLCVideoLayout layout;
    private ParcelFileDescriptor pfd;
    private List<Ntfs.Entry> list;
    private int index;
    private View controls;
    private TextView title, timeText, lengthText, playBtn, msg;
    private SeekBar seek;
    private boolean dragging;
    private int scale;

    private final Runnable hideTask = new Runnable() {
        @Override public void run() { if (mp != null && mp.isPlaying()) setControls(false); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (mp != null && !dragging) {
                long len = mp.getLength(), t = mp.getTime();
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        list = new ArrayList<Ntfs.Entry>(Session.viewerList);
        index = Session.viewerStart;
        if (list.isEmpty() || Session.current == null) { finish(); return; }
        AudioPlayer.get().pause();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        layout = new VLCVideoLayout(this);
        root.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        View touch = new View(this);
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        msg = Ui.text(this, "", 16, Ui.TEXT, null);
        msg.setGravity(Gravity.CENTER);
        root.addView(msg, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
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
            @Override public boolean onTouch(View v, MotionEvent e) { return gd.onTouchEvent(e); }
        });

        mp = new MediaPlayer(Vlc.get(this));
        mp.attachViews(layout, null, true, false);
        mp.setEventListener(new MediaPlayer.EventListener() {
            @Override public void onEvent(final MediaPlayer.Event ev) {
                ui.post(new Runnable() { @Override public void run() { onVlcEvent(ev.type); } });
            }
        });
        immersive();
        play();
        ui.post(tick);
    }

    private View buildControls() {
        FrameLayout f = new FrameLayout(this);
        int p = Ui.dp(this, 12);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x99000000);
        top.setPadding(p, p, p, p);
        TextView back = Ui.button(this, "←", 0x00000000);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        title = Ui.text(this, "", 17, Ui.TEXT, Ui.MEDIUM);
        title.setSingleLine(true);
        title.setPadding(p, 0, p, 0);
        top.addView(back);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(smallButton("Audio", new View.OnClickListener() { @Override public void onClick(View v) { chooseAudio(); } }));
        top.addView(smallButton("Subtítulos", new View.OnClickListener() { @Override public void onClick(View v) { chooseSubs(); } }));
        top.addView(smallButton("Imagen", new View.OnClickListener() { @Override public void onClick(View v) { chooseScale(); } }));
        top.addView(smallButton("Velocidad", new View.OnClickListener() { @Override public void onClick(View v) { chooseSpeed(); } }));
        f.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0x99000000);
        bottom.setPadding(p, p / 2, p, p);
        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        timeText = Ui.text(this, "0:00", 13, Ui.TEXT, null);
        lengthText = Ui.text(this, "0:00", 13, Ui.TEXT, null);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user && mp != null) timeText.setText(Ui.time(mp.getLength() * v / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; ui.removeCallbacks(hideTask); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (mp != null) mp.setPosition(s.getProgress() / 1000f);
                autoHide();
            }
        });
        row1.addView(timeText);
        row1.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        row1.addView(lengthText);
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        row2.addView(bigButton("⏮", new View.OnClickListener() { @Override public void onClick(View v) { move(-1); } }));
        row2.addView(bigButton("−10 s", new View.OnClickListener() { @Override public void onClick(View v) { skip(-10000); } }));
        playBtn = bigButton("⏸", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mp == null) return;
                if (mp.isPlaying()) mp.pause(); else mp.play();
                autoHide();
            }
        });
        row2.addView(playBtn);
        row2.addView(bigButton("+10 s", new View.OnClickListener() { @Override public void onClick(View v) { skip(10000); } }));
        row2.addView(bigButton("⏭", new View.OnClickListener() { @Override public void onClick(View v) { move(1); } }));
        bottom.addView(row1);
        bottom.addView(row2);
        f.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        f.setClickable(false);
        return f;
    }

    private TextView smallButton(String s, View.OnClickListener l) {
        TextView t = Ui.button(this, s, 0x00000000);
        t.setTextSize(14);
        t.setOnClickListener(l);
        return t;
    }

    private TextView bigButton(String s, View.OnClickListener l) {
        TextView t = Ui.button(this, s, 0x00000000);
        t.setTextSize(22);
        t.setMinWidth(Ui.dp(this, 72));
        t.setOnClickListener(l);
        return t;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void setControls(boolean on) {
        controls.setVisibility(on ? View.VISIBLE : View.GONE);
        immersive();
        if (on) autoHide();
    }

    private void autoHide() {
        ui.removeCallbacks(hideTask);
        ui.postDelayed(hideTask, 4000);
    }

    private void play() {
        Session s = Session.current;
        if (s == null) { finish(); return; }
        Ntfs.Entry e = list.get(index);
        title.setText((list.size() > 1 ? (index + 1) + "/" + list.size() + "  " : "") + e.name);
        msg.setText("");
        mp.stop();
        closeFd();
        try {
            pfd = ProxyFd.open(this, s, e);
            Media m = new Media(Vlc.get(this), pfd.getFileDescriptor());
            m.setHWDecoderEnabled(true, false);
            mp.setMedia(m);
            m.release();
            mp.setVideoScale(SCALES[scale]);
            mp.play();
        } catch (IOException ex) {
            msg.setText("No se pudo abrir el archivo");
        }
        setControls(true);
    }

    private void onVlcEvent(int type) {
        if (mp == null) return;
        if (type == MediaPlayer.Event.Playing) { playBtn.setText("⏸"); msg.setText(""); }
        else if (type == MediaPlayer.Event.Paused || type == MediaPlayer.Event.Stopped) playBtn.setText("▶");
        else if (type == MediaPlayer.Event.EndReached) { if (index + 1 < list.size()) move(1); else { playBtn.setText("▶"); setControls(true); } }
        else if (type == MediaPlayer.Event.EncounteredError) { msg.setText("Este archivo está dañado o usa un formato no compatible"); setControls(true); }
    }

    private void move(int d) {
        int n = index + d;
        if (n < 0 || n >= list.size()) return;
        index = n;
        play();
    }

    private void skip(long ms) {
        if (mp == null) return;
        long t = Math.max(0, mp.getTime() + ms);
        if (mp.getLength() > 0) t = Math.min(t, mp.getLength() - 500);
        mp.setTime(t);
        msg.setText(ms > 0 ? "» +10 s" : "« −10 s");
        ui.postDelayed(new Runnable() { @Override public void run() { msg.setText(""); } }, 700);
    }

    private void chooseTrack(final boolean audio) {
        if (mp == null) return;
        final MediaPlayer.TrackDescription[] t = audio ? mp.getAudioTracks() : mp.getSpuTracks();
        if (t == null || t.length == 0) { msg.setText(audio ? "Sin pistas de audio" : "Este vídeo no tiene subtítulos"); return; }
        String[] names = new String[t.length];
        int cur = audio ? mp.getAudioTrack() : mp.getSpuTrack(), sel = -1;
        for (int i = 0; i < t.length; i++) { names[i] = t[i].name; if (t[i].id == cur) sel = i; }
        Ui.show(Ui.dialog(this).setTitle(audio ? "Pista de audio" : "Subtítulos")
                .setSingleChoiceItems(names, sel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (audio) mp.setAudioTrack(t[w].id); else mp.setSpuTrack(t[w].id);
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
            if (Math.abs(mp.getRate() - SPEEDS[i]) < 0.01f) sel = i;
        }
        AlertDialog.Builder b = Ui.dialog(this).setTitle("Velocidad").setSingleChoiceItems(n, sel, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { mp.setRate(SPEEDS[w]); d.dismiss(); }
        });
        Ui.show(b);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mp != null && mp.isPlaying()) mp.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.current == null) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (mp != null) {
            mp.stop();
            mp.detachViews();
            mp.release();
            mp = null;
        }
        closeFd();
    }

    private void closeFd() {
        if (pfd != null) {
            try { pfd.close(); } catch (IOException ignored) { }
            pfd = null;
        }
    }
}
