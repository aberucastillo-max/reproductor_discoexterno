package com.usbexplorer.ntfs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

/** Estilo visual oscuro (inspirado en reproductores de escritorio) y utilidades de privacidad. */
final class Ui {
    static final int BG = 0xFF1A1A1A;
    static final int PANEL = 0xFF262626;
    static final int NAV = 0xFF111111;
    static final int CARD = 0xFF2E2E2E;
    static final int ACCENT = 0xFF2D7DD2;
    static final int TEXT = 0xFFFFFFFF;
    static final int SUB = 0xFFA6A6A6;
    static final int DANGER = 0xFFD9534F;
    static final int[] TILE_COLORS = {0xFF2D7DD2, 0xFF8E44AD, 0xFFC0392B, 0xFF16A085, 0xFFD35400, 0xFF2C3E50, 0xFF7F8C8D, 0xFF27AE60};

    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() {}

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** Impide capturas de pantalla, grabación y la miniatura en "recientes". */
    static void secure(Activity a) {
        a.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
    }

    static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    /** Muestra un diálogo también protegido contra capturas. */
    static <T extends Dialog> T show(T d) {
        if (d.getWindow() != null) {
            d.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }
        d.show();
        return d;
    }

    static AlertDialog show(AlertDialog.Builder b) {
        return show(b.create());
    }

    /** Campo de texto que pide al teclado no aprender, no sugerir y no guardar lo escrito. */
    static EditText privateInput(Context c, String hint, boolean number) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(SUB);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        e.setLongClickable(false);   // sin copiar/pegar ni menú de "compartir"
        e.setTextIsSelectable(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return e;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static StateListDrawable pressable(int normal, int pressed, float r) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(pressed, r));
        s.addState(new int[]{android.R.attr.state_selected}, round(pressed, r));
        s.addState(new int[]{}, round(normal, r));
        return s;
    }

    /** Botón plano con texto (y símbolo opcional). */
    static TextView button(Context c, String label, int bg) {
        TextView b = text(c, label, 15, TEXT, MEDIUM);
        b.setGravity(Gravity.CENTER);
        int p = dp(c, 10);
        b.setPadding(p + p / 2, p, p + p / 2, p);
        b.setBackground(pressable(bg, blend(bg, Color.WHITE, 0.2f), dp(c, 4)));
        b.setClickable(true);
        return b;
    }

    static int blend(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    static int tileColor(String name) {
        return TILE_COLORS[Math.abs(name.hashCode() % TILE_COLORS.length)];
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0))) sb.append(Character.toUpperCase(w.charAt(0)));
            if (sb.length() == 2) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }
}
