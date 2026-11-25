package com.usbexplorer.ntfs;

import android.app.Activity;
import android.graphics.Color;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.usbexplorer.ntfs.core.Ntfs;
import com.usbexplorer.ntfs.core.Util;

import java.io.ByteArrayInputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Visor de fotos a pantalla completa: deslizar para cambiar, pellizcar o doble toque para ampliar. */
public class ImageViewerActivity extends Activity implements ZoomImageView.Listener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<Ntfs.Entry> list;
    private int index;
    private ZoomImageView view;
    private TextView title, info;
    private LinearLayout bar;
    private ProgressBar spinner;
    private int token;
    private boolean slideshow;
    private final Runnable slideTask = new Runnable() {
        @Override public void run() { if (slideshow) onSwipe(1); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.secure(this);
        list = new ArrayList<Ntfs.Entry>(Session.viewerList);
        index = Session.viewerStart;
        if (list.isEmpty() || Session.current == null) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new ZoomImageView(this);
        view.setListener(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xAA000000);
        int p = Ui.dp(this, 10);
        bar.setPadding(p, p, p, p);
        TextView back = Ui.button(this, "←", 0x00000000);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(p, 0, p, 0);
        title = Ui.text(this, "", 17, Ui.TEXT, Ui.MEDIUM);
        title.setSingleLine(true);
        info = Ui.text(this, "", 13, Ui.SUB, null);
        texts.addView(title);
        texts.addView(info);
        TextView slide = Ui.button(this, "▶ Presentación", 0x00000000);
        slide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                slideshow = !slideshow;
                ((TextView) v).setText(slideshow ? "⏸ Presentación" : "▶ Presentación");
                ui.removeCallbacks(slideTask);
                if (slideshow) ui.postDelayed(slideTask, 4000);
            }
        });
        bar.addView(back);
        bar.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(slide);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);
        immersive();
        show();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void show() {
        final Ntfs.Entry e = list.get(index);
        title.setText(e.name);
        info.setText((index + 1) + " / " + list.size() + "  ·  " + Util.humanSize(e.size)
                + (e.mtime > 0 ? "  ·  " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(e.mtime)) : ""));
        spinner.setVisibility(View.VISIBLE);
        final int t = ++token;
        io.execute(new Runnable() {
            @Override public void run() {
                Object[] d = null;
                String err = null;
                try {
                    Session s = Session.current;
                    if (s == null) return;
                    byte[] data = ProxyFd.readAll(s, e, 120L * 1024 * 1024);
                    int rot = 0;
                    try { rot = Thumbs.rotation(new ExifInterface(new ByteArrayInputStream(data))); } catch (Exception ignored) { }
                    d = ZoomImageView.decode(data, rot);
                    if (d == null) err = "Formato de imagen no compatible";
                } catch (Throwable ex) {
                    err = ex instanceof OutOfMemoryError ? "La imagen es demasiado grande" : "No se pudo leer la imagen";
                }
                final Object[] fd = d;
                final String ferr = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (t != token || isFinishing()) return;
                        spinner.setVisibility(View.GONE);
                        view.setImage(fd);
                        if (ferr != null) info.setText(ferr);
                        if (slideshow) { ui.removeCallbacks(slideTask); ui.postDelayed(slideTask, 4000); }
                    }
                });
            }
        });
    }

    @Override
    public void onSwipe(int dir) {
        int n = index + dir;
        if (n < 0 || n >= list.size()) {
            if (slideshow && n >= list.size()) n = 0; else return;
        }
        index = n;
        show();
    }

    @Override
    public void onTap() {
        bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.current == null) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(slideTask);
        io.shutdownNow();
        if (view != null) view.recycle();
    }
}
