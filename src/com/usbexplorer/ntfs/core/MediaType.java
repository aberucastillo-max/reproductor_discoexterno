package com.usbexplorer.ntfs.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Clasificación de archivos por extensión. */
public enum MediaType {
    IMAGE("Imágenes"), VIDEO("Vídeos"), AUDIO("Audio"), DOCUMENT("Documentos"), OTHER("Otros");

    public final String label;

    MediaType(String label) { this.label = label; }

    private static final Set<String> IMG = set("jpg", "jpeg", "jpe", "png", "gif", "bmp", "webp", "heic", "heif", "avif", "tif", "tiff", "dng");
    private static final Set<String> VID = set("mp4", "m4v", "avi", "mkv", "mov", "wmv", "flv", "f4v", "mpg", "mpeg", "mpe",
            "m1v", "m2v", "vob", "ts", "m2ts", "mts", "3gp", "3g2", "webm", "divx", "xvid", "ogv", "asf", "rm", "rmvb", "dv", "mxf");
    private static final Set<String> AUD = set("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "amr",
            "mid", "midi", "ac3", "dts", "ape", "aiff", "aif", "mka");
    private static final Set<String> DOC = set("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "rtf", "csv", "epub");

    private static Set<String> set(String... s) { return new HashSet<String>(Arrays.asList(s)); }

    public static String ext(String name) {
        int d = name.lastIndexOf('.');
        return d <= 0 || d == name.length() - 1 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    public static MediaType of(String name) {
        String e = ext(name);
        if (IMG.contains(e)) return IMAGE;
        if (VID.contains(e)) return VIDEO;
        if (AUD.contains(e)) return AUDIO;
        if (DOC.contains(e)) return DOCUMENT;
        return OTHER;
    }

    public boolean playable() { return this == VIDEO || this == AUDIO; }
}
