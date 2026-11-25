package com.usbexplorer.ntfs.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Propone grupos (actores, cantantes, personas, series...) analizando SOLO nombres de archivos y
 * carpetas, dentro de la tablet. Señales que usa:
 *  1. Patrón "Artista - Título" (también "–", "_-_", " ft. ", " feat. ", " & ", " y ").
 *  2. Carpetas con nombre propio (no genéricas como "Videos" o "DCIM").
 *  3. Parejas de palabras que se repiten en muchos nombres ("Brad Pitt", "Bad Bunny").
 * Un candidato necesita al menos {@link #MIN_FILES} archivos distintos.
 */
public final class Suggester {
    public static final int MIN_FILES = 3;
    private static final int MAX_SUGGESTIONS = 60;

    public static final class Suggestion {
        public final String name;
        public final List<Ntfs.Entry> files;
        public final String reason;
        Suggestion(String name, List<Ntfs.Entry> files, String reason) {
            this.name = name; this.files = files; this.reason = reason;
        }
    }

    private static final Set<String> STOP = new HashSet<String>(Arrays.asList(
            // carpetas y palabras genéricas
            "video", "videos", "vídeos", "foto", "fotos", "imagen", "imagenes", "imágenes", "images", "image", "img",
            "pictures", "picture", "photos", "photo", "music", "musica", "música", "audio", "audios", "movies", "movie",
            "peliculas", "películas", "pelicula", "película", "series", "serie", "nueva carpeta", "new folder", "dcim",
            "camera", "camara", "cámara", "download", "downloads", "descargas", "documents", "documentos", "desktop",
            "escritorio", "whatsapp", "telegram", "screenshots", "capturas", "varios", "otros", "misc", "temp", "tmp",
            "backup", "copia", "copia de seguridad", "disco", "usb", "sin titulo", "sin título", "untitled", "media",
            "clips", "clip", "full", "hd", "sd", "uhd", "4k", "8k", "official", "oficial", "video oficial",
            "official video", "official music video", "lyrics", "letra", "audio oficial", "official audio", "remix",
            "live", "en vivo", "cover", "trailer", "capitulo", "capítulo", "episodio", "episode", "season", "temporada",
            "parte", "part", "cd", "disc", "track", "pista", "the", "and", "con", "del", "los", "las", "una", "uno",
            "por", "para", "que", "mix", "new", "nuevo", "nueva", "mi", "my", "de", "la", "el", "en", "y", "a"));

    private static final String NOISE =
            "(?i)\\b(1080p|720p|480p|360p|2160p|4k|x264|x265|h264|h265|hevc|xvid|divx|bluray|brrip|bdrip|webrip|web-dl|"
            + "hdtv|dvdrip|hdrip|aac|ac3|mp3|320kbps|128kbps|hq|hd|sd|uhd|dual|latino|castellano|subtitulado|"
            + "sub|subs|esp|eng|multi|official|oficial|video|audio|lyrics|letra|remastered|remaster)\\b";

    private Suggester() {}

    public static List<Suggestion> suggest(MftIndex idx, List<Ntfs.Entry> files) {
        Map<String, Set<Ntfs.Entry>> byKey = new HashMap<String, Set<Ntfs.Entry>>();
        Map<String, String> display = new HashMap<String, String>();
        Map<String, String> reason = new HashMap<String, String>();
        Map<String, Integer> strength = new HashMap<String, Integer>();

        for (Ntfs.Entry e : files) {
            if (e.dir) continue;
            String base = stripExt(e.name);
            // 1. "Artista - Título"
            String[] parts = base.split("\\s+[-–—]\\s+|_-_|\\s+-(?=\\S)|(?<=\\S)-\\s+");
            if (parts.length >= 2) {
                for (String who : splitPeople(parts[0])) add(byKey, display, reason, strength, who, e, "nombre «Artista - Título»", 3);
            }
            for (String who : featured(base)) add(byKey, display, reason, strength, who, e, "colaboración (feat.)", 3);
            // 2. carpeta contenedora y su padre
            String f1 = idx.folderName(e);
            add(byKey, display, reason, strength, f1, e, "nombre de carpeta", 2);
            Ntfs.Entry parent = idx.byRecord(e.parent);
            if (parent != null) add(byKey, display, reason, strength, idx.folderName(parent), e, "nombre de carpeta", 1);
            // 3. parejas de palabras con mayúscula
            String clean = base.replaceAll(NOISE, " ").replaceAll("[\\[\\](){}_.+#@]", " ").replaceAll("\\d+", " ");
            String[] w = clean.trim().split("\\s+");
            for (int i = 0; i + 1 < w.length; i++) {
                if (isNameWord(w[i]) && isNameWord(w[i + 1])) {
                    add(byKey, display, reason, strength, w[i] + " " + w[i + 1], e, "nombre repetido en archivos", 1);
                }
            }
        }

        List<String> keys = new ArrayList<String>();
        for (Map.Entry<String, Set<Ntfs.Entry>> m : byKey.entrySet()) if (m.getValue().size() >= MIN_FILES) keys.add(m.getKey());
        // quitar candidatos contenidos en otro más largo con casi los mismos archivos ("Brad" frente a "Brad Pitt")
        Set<String> drop = new HashSet<String>();
        for (String a : keys) for (String b : keys) {
            if (a.equals(b) || !(" " + b + " ").contains(" " + a + " ")) continue;
            if (byKey.get(b).size() * 10 >= byKey.get(a).size() * 7) drop.add(a);
        }
        keys.removeAll(drop);
        final Map<String, Set<Ntfs.Entry>> fk = byKey;
        final Map<String, Integer> st = strength;
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int c = Integer.compare(fk.get(b).size() * st.get(b), fk.get(a).size() * st.get(a));
                return c != 0 ? c : a.compareTo(b);
            }
        });
        List<Suggestion> out = new ArrayList<Suggestion>();
        for (String k : keys) {
            if (out.size() >= MAX_SUGGESTIONS) break;
            out.add(new Suggestion(display.get(k), new ArrayList<Ntfs.Entry>(byKey.get(k)), reason.get(k)));
        }
        return out;
    }

    private static void add(Map<String, Set<Ntfs.Entry>> byKey, Map<String, String> display, Map<String, String> reason,
                            Map<String, Integer> strength, String raw, Ntfs.Entry e, String why, int power) {
        if (raw == null) return;
        String name = tidy(raw);
        String key = MftIndex.normalize(name);
        if (key.length() < 3 || key.length() > 40 || STOP.contains(key) || key.matches(".*\\d{3,}.*")
                || !key.matches(".*\\p{L}.*") || key.split(" ").length > 4) return;
        Set<Ntfs.Entry> s = byKey.get(key);
        if (s == null) {
            s = new LinkedHashSet<Ntfs.Entry>();
            byKey.put(key, s);
            display.put(key, name);
            reason.put(key, why);
            strength.put(key, power);
        } else if (power > strength.get(key)) {
            strength.put(key, power);
            reason.put(key, why);
        }
        s.add(e);
    }

    private static String tidy(String s) {
        s = s.replaceAll(NOISE, " ").replaceAll("[\\[\\](){}_]", " ").replaceAll("\\s+", " ").trim();
        s = s.replaceAll("^[-–—.\\s]+|[-–—.\\s]+$", "");
        if (s.equals(s.toLowerCase()) || s.equals(s.toUpperCase())) {   // "BAD BUNNY" / "bad bunny" -> "Bad Bunny"
            StringBuilder sb = new StringBuilder();
            for (String w : s.toLowerCase().split(" ")) {
                if (w.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            s = sb.toString();
        }
        return s;
    }

    private static List<String> splitPeople(String s) {
        List<String> l = new ArrayList<String>();
        for (String p : s.split("(?i)\\s+(?:ft\\.?|feat\\.?|featuring|x|vs\\.?|&|y|and|con)\\s+|,\\s*")) {
            if (!p.trim().isEmpty()) l.add(p.trim());
        }
        return l;
    }

    private static List<String> featured(String base) {
        List<String> l = new ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)[(\\[]?\\b(?:ft\\.?|feat\\.?|featuring)\\s+([^()\\[\\]\\-–]+)").matcher(base);
        while (m.find()) l.addAll(splitPeople(m.group(1)));
        return l;
    }

    private static boolean isNameWord(String w) {
        if (w.length() < 2 || !Character.isUpperCase(w.charAt(0))) return false;
        for (int i = 0; i < w.length(); i++) if (!Character.isLetter(w.charAt(i)) && w.charAt(i) != '\'') return false;
        return !STOP.contains(MftIndex.normalize(w));
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }
}
