package com.usbexplorer.ntfs.core;

import java.nio.charset.Charset;
import java.util.Locale;

/** Lectura little-endian y utilidades varias. */
public final class Util {
    private static final Charset UTF16LE = Charset.forName("UTF-16LE");
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private Util() {}

    public static int u8(byte[] b, int p) { return b[p] & 0xFF; }

    public static int u16(byte[] b, int p) { return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8; }

    public static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) | (b[p + 1] & 0xFFL) << 8 | (b[p + 2] & 0xFFL) << 16 | (b[p + 3] & 0xFFL) << 24;
    }

    public static long s64(byte[] b, int p) { return u32(b, p) | u32(b, p + 4) << 32; }

    public static String ascii(byte[] b, int p, int n) { return new String(b, p, n, ASCII); }

    public static String utf16(byte[] b, int p, int chars) { return new String(b, p, chars * 2, UTF16LE); }

    public static String humanSize(long n) {
        if (n < 1024) return n + " B";
        String[] u = {"KB", "MB", "GB", "TB", "PB"};
        double v = n;
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.getDefault(), v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
