package com.usbexplorer.ntfs;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Diagnóstico de cierres inesperados. Guarda (solo en la tablet, en la carpeta privada de la app)
 * eventos técnicos SIN nombres de archivos y, al volver a abrir la app, explica por qué se cerró:
 * error Java, bloqueo (ANR) o fallo nativo del motor de vídeo.
 */
final class CrashLog {
    private static final String FILE = "diagnostico.txt";
    private static final ArrayDeque<String> events = new ArrayDeque<String>();
    private static Context app;

    private CrashLog() {}

    static void install(Context c) {
        app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                event("ERROR en hilo «" + t.getName() + "»");
                write("Error Java:\n" + Log.getStackTraceString(e));
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    /** Evento técnico (nunca incluir nombres de archivo). */
    static synchronized void event(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + s;
        events.addLast(line);
        while (events.size() > 40) events.removeFirst();
        persistEvents();
    }

    private static synchronized void persistEvents() {
        if (app == null) return;
        try {
            FileOutputStream o = new FileOutputStream(new File(app.getNoBackupFilesDir(), "eventos.txt"));
            for (String e : events) o.write((e + "\n").getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) {
        }
    }

    private static synchronized void write(String s) {
        if (app == null) return;
        try {
            FileOutputStream o = new FileOutputStream(new File(app.getNoBackupFilesDir(), FILE));
            o.write(s.getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) {
        }
    }

    private static String readAndDelete(File f, int max) {
        if (!f.exists()) return "";
        try {
            RandomAccessFile r = new RandomAccessFile(f, "r");
            byte[] b = new byte[(int) Math.min(f.length(), max)];
            r.readFully(b);
            r.close();
            f.delete();
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Informe del último cierre inesperado, o "" si la última vez la app terminó bien. */
    static String lastReport(Context c) {
        StringBuilder sb = new StringBuilder();
        File dir = c.getNoBackupFilesDir();
        String java = readAndDelete(new File(dir, FILE), 20000);
        String ev = readAndDelete(new File(dir, "eventos.txt"), 20000);
        SharedPreferences sp = c.getSharedPreferences("diagnostico", Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = c.getSystemService(ActivityManager.class);
                List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 1);
                if (!l.isEmpty()) {
                    ApplicationExitInfo x = l.get(0);
                    long seen = sp.getLong("visto", 0);
                    sp.edit().putLong("visto", x.getTimestamp()).apply();
                    int r = x.getReason();
                    boolean bad = r == ApplicationExitInfo.REASON_CRASH || r == ApplicationExitInfo.REASON_CRASH_NATIVE
                            || r == ApplicationExitInfo.REASON_ANR || r == ApplicationExitInfo.REASON_LOW_MEMORY
                            || r == ApplicationExitInfo.REASON_SIGNALED || r == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE;
                    if (x.getTimestamp() > seen && bad) {
                        sb.append("Motivo: ").append(reason(r)).append('\n');
                        sb.append("Hora: ").append(new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new Date(x.getTimestamp()))).append('\n');
                        if (x.getDescription() != null) sb.append("Detalle: ").append(x.getDescription()).append('\n');
                        sb.append("Estado: ").append(x.getStatus()).append("   Memoria: ").append(x.getPss() / 1024).append(" MB\n");
                        if (r == ApplicationExitInfo.REASON_ANR || r == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                            InputStream in = x.getTraceInputStream();
                            if (in != null) {
                                byte[] t = readStream(in, 512 * 1024);
                                sb.append('\n').append(r == ApplicationExitInfo.REASON_ANR ? anrSummary(t) : nativeSummary(t));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (!java.isEmpty()) sb.append('\n').append(java);
        if (sb.length() > 0 && !ev.isEmpty()) sb.append("\nÚltimos eventos:\n").append(ev);
        return sb.toString().trim();
    }

    private static String reason(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH: return "error de la app (Java)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "fallo nativo (motor de vídeo / librería)";
            case ApplicationExitInfo.REASON_ANR: return "la app se quedó bloqueada (ANR)";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "falta de memoria";
            case ApplicationExitInfo.REASON_SIGNALED: return "cerrada por el sistema (señal)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "fallo al iniciar";
            default: return "código " + r;
        }
    }

    private static byte[] readStream(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while (o.size() < max && (n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    /** De un volcado ANR, el hilo principal y los hilos de la app relevantes. */
    private static String anrSummary(byte[] t) {
        String s;
        try { s = new String(t, "UTF-8"); } catch (Exception e) { return ""; }
        StringBuilder out = new StringBuilder();
        String[] threads = s.split("\n\n");
        for (String th : threads) {
            if (th.startsWith("\"main\"") || th.contains("\"usb-lectura") || th.contains("\"miniaturas\"") || th.contains("\"pool-")) {
                String[] lines = th.split("\n");
                for (int i = 0; i < Math.min(lines.length, 22); i++) {
                    String l = lines[i];
                    if (l.startsWith("  | ")) continue;
                    out.append(l).append('\n');
                }
                out.append('\n');
            }
        }
        return out.length() > 0 ? out.toString() : s.substring(0, Math.min(s.length(), 4000));
    }

    /** De un "tombstone" nativo (protobuf), las cadenas útiles: señal, librería y funciones. */
    private static String nativeSummary(byte[] t) {
        StringBuilder out = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        int lines = 0;
        for (byte b : t) {
            int c = b & 0xFF;
            if (c >= 32 && c < 127) { cur.append((char) c); continue; }
            if (cur.length() >= 6) {
                String x = cur.toString();
                if (x.contains(".so") || x.contains("SIG") || x.contains("abort") || x.contains("Abort")
                        || x.contains("vlc") || x.contains("assert") || x.contains("Cause") || x.startsWith("_Z")) {
                    out.append(x).append('\n');
                    if (++lines >= 45) break;
                }
            }
            cur.setLength(0);
        }
        return out.toString();
    }
}
