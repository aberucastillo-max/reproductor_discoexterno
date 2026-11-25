package com.usbexplorer.ntfs;

import android.content.Context;

import org.videolan.libvlc.LibVLC;

import java.util.ArrayList;

/**
 * Motor de reproducción (libVLC con FFmpeg integrado: AVI, MPG/MPEG-2, MP4, MKV, WMV, FLV, MOV, VOB,
 * DivX/Xvid, H.264/H.265, AC3, DTS, FLAC...). Configurado para no buscar nada fuera del disco:
 * sin scripts Lua (no se incluyen en la app), sin acceso de red a metadatos y sin
 * buscar subtítulos sueltos. Además la app no tiene permiso de Internet.
 */
final class Vlc {
    private static LibVLC lib;

    private Vlc() {}

    static synchronized LibVLC get(Context c) {
        if (lib == null) {
            ArrayList<String> o = new ArrayList<String>();
            o.add("--no-metadata-network-access");
            o.add("--no-sub-autodetect-file");
            o.add("--no-stats");
            o.add("--no-video-title-show");
            o.add("--no-snapshot-preview");
            o.add("--audio-time-stretch");
            o.add("--file-caching=1500");
            lib = new LibVLC(c.getApplicationContext(), o);
        }
        return lib;
    }
}
