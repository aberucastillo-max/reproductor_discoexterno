package com.usbexplorer.ntfs.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lector NTFS de SOLO LECTURA. Nunca escribe nada en el disco.
 * Soporta: MFT fragmentada, $ATTRIBUTE_LIST, índices grandes ($INDEX_ALLOCATION + $BITMAP),
 * archivos dispersos (sparse) y comprimidos (LZNT1). No soporta archivos cifrados (EFS).
 */
public final class Ntfs {
    public static final long ROOT = 5;
    static final long MASK = 0xFFFFFFFFFFFFL;

    static final int AT_ATTRIBUTE_LIST = 0x20;
    static final int AT_FILE_NAME = 0x30;
    static final int AT_VOLUME_NAME = 0x60;
    static final int AT_DATA = 0x80;
    static final int AT_INDEX_ROOT = 0x90;
    static final int AT_INDEX_ALLOCATION = 0xA0;
    static final int AT_BITMAP = 0xB0;

    /** Tramo de clústeres: vcn..vcn+len-1 → lcn (lcn = -1 si es disperso). */
    static final class Run {
        final long vcn, lcn, len;
        Run(long vcn, long lcn, long len) { this.vcn = vcn; this.lcn = lcn; this.len = len; }
    }

    /** Atributo de un registro MFT (ya combinado si estaba repartido en varios registros). */
    public static final class Attr {
        int type, flags;
        String name = "";
        boolean nonResident;
        byte[] value;
        long startVcn, dataSize, initSize, allocSize;
        int compUnit;
        List<Run> runs = new ArrayList<Run>();
        // caché de la última unidad de compresión descomprimida
        long cachedCu = -1;
        byte[] cachedCuData;

        public long size() { return dataSize; }
        boolean compressed() { return nonResident && (flags & 0x0001) != 0 && compUnit != 0; }
        boolean encrypted() { return (flags & 0x4000) != 0; }
    }

    /** Elemento de una carpeta. */
    public static final class Entry {
        public final long ref;
        public final long parent;  // referencia de la carpeta que lo contiene
        public final String name;
        public final boolean dir;
        public final long size;
        public final long mtime;   // milisegundos Unix
        public final boolean hidden;

        Entry(long ref, long parent, String name, boolean dir, long size, long mtime, boolean hidden) {
            this.ref = ref; this.parent = parent; this.name = name; this.dir = dir; this.size = size;
            this.mtime = mtime; this.hidden = hidden;
        }
    }

    /** Archivo abierto para lectura. */
    public static final class FileHandle {
        public final long ref;
        final Attr data;
        FileHandle(long ref, Attr data) { this.ref = ref; this.data = data; }
        public long size() { return data.dataSize; }
    }

    private final Disk disk;
    private final long base;
    public final int sectorSize, clusterSize, recordSize;
    public final long totalBytes;
    /** Número de serie del volumen (identifica el disco para los grupos). */
    public final long serial;
    private List<Run> mftRuns;
    private long mftSize;
    private String label = "";

    public Ntfs(Disk disk, long partitionOffset) throws IOException {
        this.disk = disk;
        this.base = partitionOffset;
        byte[] b = new byte[512];
        disk.read(base, b, 0, 512);
        if (!"NTFS    ".equals(Util.ascii(b, 3, 8))) throw new IOException("La partición no es NTFS");
        sectorSize = Util.u16(b, 0x0B);
        int spc = Util.u8(b, 0x0D);
        if (spc > 0x80) spc = 1 << (256 - spc);
        if (sectorSize < 256 || sectorSize > 4096 || Integer.bitCount(sectorSize) != 1 || spc == 0) {
            throw new IOException("Sector de arranque NTFS no válido");
        }
        clusterSize = sectorSize * spc;
        totalBytes = Util.s64(b, 0x28) * sectorSize;
        serial = Util.s64(b, 0x48);
        long mftLcn = Util.s64(b, 0x30);
        int cpr = b[0x40];
        recordSize = cpr > 0 ? cpr * clusterSize : 1 << (-cpr);
        if (recordSize < 512 || recordSize > 65536) throw new IOException("Tamaño de registro MFT no válido");

        // Arranque: el registro 0 ($MFT) describe dónde está el resto de la MFT.
        byte[] rec = new byte[recordSize];
        disk.read(base + mftLcn * clusterSize, rec, 0, recordSize);
        checkRecord(rec, 0);
        List<Attr> attrs = merge(parseAttributes(rec));
        Attr data = find(attrs, AT_DATA, "");
        if (data == null || !data.nonResident) throw new IOException("MFT dañada: falta $DATA");
        mftRuns = data.runs;
        mftSize = data.dataSize;
        if (find(attrs, AT_ATTRIBUTE_LIST, null) != null) {   // MFT muy fragmentada
            Attr full = find(loadAttributes(0), AT_DATA, "");
            if (full != null) { mftRuns = full.runs; mftSize = full.dataSize; }
        }
        try {
            Attr vn = find(loadAttributes(3), AT_VOLUME_NAME, null);
            if (vn != null && vn.value != null) label = Util.utf16(vn.value, 0, vn.value.length / 2);
        } catch (IOException ignored) {
        }
    }

    public String label() { return label; }

    /**
     * Huella del estado del volumen leyendo solo unos KB: número de serie, posición y mapa de uso de
     * la MFT, páginas de reinicio de $LogFile (cambian con cualquier modificación de metadatos en
     * Windows) y los registros de $Volume (estado "sucio") y de la carpeta raíz.
     * Si la huella coincide con la guardada, el índice en caché sigue siendo válido.
     */
    public byte[] fingerprint() throws IOException {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(16);
        b.putLong(serial).putLong(mftSize);
        md.update(b.array());
        for (Run r : mftRuns) {
            java.nio.ByteBuffer rb = java.nio.ByteBuffer.allocate(24);
            rb.putLong(r.vcn).putLong(r.lcn).putLong(r.len);
            md.update(rb.array());
        }
        Attr bitmap = find(loadAttributes(0), AT_BITMAP, "");
        if (bitmap != null) md.update(bitmap.nonResident ? readAll(bitmap) : bitmap.value);
        Attr log = find(loadAttributes(2), AT_DATA, "");
        if (log != null) {
            byte[] restart = new byte[(int) Math.min(8192, log.dataSize)];
            read(log, 0, restart, 0, restart.length);
            md.update(restart);
        }
        md.update(readRecord(3));
        md.update(readRecord(5));
        return md.digest();
    }

    // ------------------------------------------------------------------ registros MFT

    byte[] readRecord(long n) throws IOException {
        long pos = n * recordSize;
        if (n < 0 || pos + recordSize > mftSize) throw new IOException("Registro MFT fuera de rango: " + n);
        byte[] rec = new byte[recordSize];
        readRuns(mftRuns, pos, rec, 0, recordSize);
        checkRecord(rec, n);
        return rec;
    }

    private static void checkRecord(byte[] rec, long n) throws IOException {
        if (!"FILE".equals(Util.ascii(rec, 0, 4)) || !fixup(rec)) {
            throw new IOException("Registro MFT " + n + " dañado o ilegible");
        }
    }

    /** Aplica el "update sequence array" (protección multi-sector). */
    static boolean fixup(byte[] b) {
        int uo = Util.u16(b, 4), uc = Util.u16(b, 6);
        if (uc < 2 || uo + uc * 2 > b.length) return false;
        int stride = b.length / (uc - 1);
        if (stride < 256) return false;
        for (int i = 1; i < uc; i++) {
            int p = i * stride - 2;
            if (b[p] != b[uo] || b[p + 1] != b[uo + 1]) return false;
            b[p] = b[uo + 2 * i];
            b[p + 1] = b[uo + 2 * i + 1];
        }
        return true;
    }

    static List<Attr> parseAttributes(byte[] rec) throws IOException {
        List<Attr> list = new ArrayList<Attr>();
        int p = Util.u16(rec, 0x14);
        while (p + 8 <= rec.length) {
            long type = Util.u32(rec, p);
            if (type == 0xFFFFFFFFL) break;
            int len = (int) Util.u32(rec, p + 4);
            if (len < 0x18 || p + len > rec.length) break;
            Attr a = new Attr();
            a.type = (int) type;
            a.nonResident = rec[p + 8] != 0;
            int nameLen = Util.u8(rec, p + 9), nameOff = Util.u16(rec, p + 0x0A);
            a.flags = Util.u16(rec, p + 0x0C);
            if (nameLen > 0 && p + nameOff + nameLen * 2 <= p + len) a.name = Util.utf16(rec, p + nameOff, nameLen);
            if (!a.nonResident) {
                int vl = (int) Util.u32(rec, p + 0x10), vo = Util.u16(rec, p + 0x14);
                if (vl < 0 || vo + vl > len) throw new IOException("Atributo residente dañado");
                a.value = Arrays.copyOfRange(rec, p + vo, p + vo + vl);
                a.dataSize = a.initSize = a.allocSize = vl;
            } else {
                if (len < 0x40) break;
                a.startVcn = Util.s64(rec, p + 0x10);
                int ro = Util.u16(rec, p + 0x20);
                a.compUnit = Util.u16(rec, p + 0x22);
                a.allocSize = Util.s64(rec, p + 0x28);
                a.dataSize = Util.s64(rec, p + 0x30);
                a.initSize = Util.s64(rec, p + 0x38);
                a.runs = decodeRuns(rec, p + ro, p + len, a.startVcn);
            }
            list.add(a);
            p += len;
        }
        return list;
    }

    static List<Run> decodeRuns(byte[] b, int p, int end, long vcn) throws IOException {
        List<Run> runs = new ArrayList<Run>();
        long lcn = 0;
        while (p < end) {
            int h = Util.u8(b, p++);
            if (h == 0) break;
            int ls = h & 0x0F, os = h >> 4;
            if (ls == 0 || ls > 8 || os > 8 || p + ls + os > end) throw new IOException("Lista de tramos dañada");
            long len = 0;
            for (int i = 0; i < ls; i++) len |= (long) Util.u8(b, p + i) << (8 * i);
            p += ls;
            if (os == 0) {
                runs.add(new Run(vcn, -1, len));
            } else {
                long d = 0;
                for (int i = 0; i < os; i++) d |= (long) Util.u8(b, p + i) << (8 * i);
                if (os < 8 && (b[p + os - 1] & 0x80) != 0) d |= -1L << (8 * os);
                p += os;
                lcn += d;
                runs.add(new Run(vcn, lcn, len));
            }
            vcn += len;
        }
        return runs;
    }

    /** Une los trozos de un mismo atributo no residente repartidos en varios registros. */
    private static List<Attr> merge(List<Attr> attrs) {
        Map<String, List<Attr>> groups = new LinkedHashMap<String, List<Attr>>();
        List<Attr> out = new ArrayList<Attr>();
        for (Attr a : attrs) {
            if (!a.nonResident) { out.add(a); continue; }
            String k = a.type + "\u0000" + a.name;
            List<Attr> g = groups.get(k);
            if (g == null) { g = new ArrayList<Attr>(); groups.put(k, g); }
            g.add(a);
        }
        for (List<Attr> g : groups.values()) {
            Collections.sort(g, new Comparator<Attr>() {
                @Override public int compare(Attr x, Attr y) { return Long.compare(x.startVcn, y.startVcn); }
            });
            Attr first = g.get(0);
            for (int i = 1; i < g.size(); i++) first.runs.addAll(g.get(i).runs);
            out.add(first);
        }
        return out;
    }

    static Attr find(List<Attr> attrs, int type, String name) {
        for (Attr a : attrs) if (a.type == type && (name == null || name.equals(a.name))) return a;
        return null;
    }

    /** Todos los atributos de un archivo, siguiendo $ATTRIBUTE_LIST si existe. */
    List<Attr> loadAttributes(long ref) throws IOException {
        long n = ref & MASK;
        byte[] rec0 = readRecord(n);
        int seq = (int) (ref >>> 48);
        if (seq != 0 && Util.u16(rec0, 0x10) != seq) {
            // la referencia es de una versión anterior del disco (archivo borrado y registro reutilizado)
            throw new IOException("El archivo ya no existe o cambió en el disco. Reindexa el disco.");
        }
        List<Attr> attrs = parseAttributes(rec0);
        Attr al = find(attrs, AT_ATTRIBUTE_LIST, null);
        if (al != null) {
            byte[] v = al.nonResident ? readAll(al) : al.value;
            Set<Long> ext = new LinkedHashSet<Long>();
            int p = 0;
            while (p + 0x1A <= v.length) {
                int el = Util.u16(v, p + 4);
                if (el < 0x1A) break;
                long r = Util.s64(v, p + 0x10) & MASK;
                if (r != n) ext.add(r);
                p += el;
            }
            for (long r : ext) {
                byte[] rec = readRecord(r);
                if ((Util.s64(rec, 0x20) & MASK) != n) continue;   // no pertenece a este archivo
                attrs.addAll(parseAttributes(rec));
            }
        }
        return merge(attrs);
    }

    /** Número de registros de la MFT. */
    long mftRecordCount() { return mftSize / recordSize; }

    /** Lee {@code count} registros MFT consecutivos tal cual (sin aplicar fixups). */
    void readMftRaw(long first, byte[] buf, int count) throws IOException {
        readRuns(mftRuns, first * recordSize, buf, 0, count * recordSize);
    }

    // ------------------------------------------------------------------ lectura de datos

    private void readRuns(List<Run> runs, long pos, byte[] dst, int off, int len) throws IOException {
        while (len > 0) {
            long vcn = pos / clusterSize;
            int in = (int) (pos % clusterSize);
            Run r = findRun(runs, vcn);
            if (r == null) throw new IOException("Datos fuera de los tramos asignados");
            long avail = (r.vcn + r.len - vcn) * clusterSize - in;
            int n = (int) Math.min(len, avail);
            if (r.lcn < 0) Arrays.fill(dst, off, off + n, (byte) 0);
            else disk.read(base + (r.lcn + vcn - r.vcn) * clusterSize + in, dst, off, n);
            pos += n; off += n; len -= n;
        }
    }

    private static Run findRun(List<Run> runs, long vcn) {
        int lo = 0, hi = runs.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Run r = runs.get(mid);
            if (vcn < r.vcn) hi = mid - 1;
            else if (vcn >= r.vcn + r.len) lo = mid + 1;
            else return r;
        }
        return null;
    }

    byte[] readAll(Attr a) throws IOException {
        if (a.dataSize > 64L * 1024 * 1024) throw new IOException("Atributo demasiado grande");
        byte[] b = new byte[(int) a.dataSize];
        read(a, 0, b, 0, b.length);
        return b;
    }

    /** Lee datos de un atributo. Devuelve bytes leídos (0 al final). */
    int read(Attr a, long pos, byte[] dst, int off, int len) throws IOException {
        if (pos >= a.dataSize || len <= 0) return 0;
        len = (int) Math.min(len, a.dataSize - pos);
        if (!a.nonResident) {
            System.arraycopy(a.value, (int) pos, dst, off, len);
            return len;
        }
        if (a.encrypted()) throw new IOException("El archivo está cifrado (EFS) y no se puede leer");
        if (a.compressed()) return readCompressed(a, pos, dst, off, len);
        int done = 0;
        if (pos < a.initSize) {
            done = (int) Math.min(len, a.initSize - pos);
            readRuns(a.runs, pos, dst, off, done);
        }
        if (done < len) Arrays.fill(dst, off + done, off + len, (byte) 0);
        return len;
    }

    private int readCompressed(Attr a, long pos, byte[] dst, int off, int len) throws IOException {
        int cuClusters = 1 << a.compUnit;
        int cuBytes = cuClusters * clusterSize;
        int total = len;
        while (len > 0) {
            long cu = pos / cuBytes;
            int in = (int) (pos % cuBytes);
            if (cu != a.cachedCu) {
                a.cachedCuData = loadCu(a, cu, cuClusters, cuBytes);
                a.cachedCu = cu;
            }
            int n = Math.min(len, cuBytes - in);
            System.arraycopy(a.cachedCuData, in, dst, off, n);
            pos += n; off += n; len -= n;
        }
        return total;
    }

    private byte[] loadCu(Attr a, long cu, int cuClusters, int cuBytes) throws IOException {
        long vcn0 = cu * cuClusters;
        int allocated = 0;
        for (int i = 0; i < cuClusters; i++) {
            Run r = findRun(a.runs, vcn0 + i);
            if (r != null && r.lcn >= 0) allocated++;
        }
        byte[] out = new byte[cuBytes];
        if (allocated == 0) return out;                       // todo ceros
        if (allocated == cuClusters) {                         // guardado sin comprimir
            readRuns(a.runs, vcn0 * clusterSize, out, 0, cuBytes);
            return out;
        }
        byte[] src = new byte[allocated * clusterSize];
        readRuns(a.runs, vcn0 * clusterSize, src, 0, src.length);
        Lznt1.decompress(src, src.length, out);
        return out;
    }

    // ------------------------------------------------------------------ API pública

    public FileHandle open(long ref) throws IOException {
        Attr d = find(loadAttributes(ref), AT_DATA, "");
        if (d == null) throw new IOException("El archivo no tiene datos");
        return new FileHandle(ref, d);
    }

    public int read(FileHandle h, long pos, byte[] dst, int off, int len) throws IOException {
        synchronized (h) {
            return read(h.data, pos, dst, off, len);
        }
    }

    public List<Entry> list(long dirRef) throws IOException {
        List<Attr> attrs = loadAttributes(dirRef);
        Attr root = find(attrs, AT_INDEX_ROOT, "$I30");
        if (root == null || root.value == null) throw new IOException("No es una carpeta");
        byte[] rv = root.value;
        int ibs = (int) Util.u32(rv, 8);
        Map<String, Entry> map = new LinkedHashMap<String, Entry>();
        long self = dirRef & MASK;
        parseIndex(rv, 0x10 + (int) Util.u32(rv, 0x10), Math.min(rv.length, 0x10 + (int) Util.u32(rv, 0x14)), map, self);

        Attr alloc = find(attrs, AT_INDEX_ALLOCATION, "$I30");
        if (alloc != null && ibs >= 512) {
            Attr bmpA = find(attrs, AT_BITMAP, "$I30");
            byte[] bmp = bmpA == null ? null : (bmpA.nonResident ? readAll(bmpA) : bmpA.value);
            long blocks = alloc.dataSize / ibs;
            byte[] blk = new byte[ibs];
            for (long i = 0; i < blocks; i++) {
                if (bmp != null) {
                    int byteIdx = (int) (i >> 3);
                    if (byteIdx >= bmp.length || (bmp[byteIdx] & (1 << (i & 7))) == 0) continue;
                }
                read(alloc, i * ibs, blk, 0, ibs);
                if (!"INDX".equals(Util.ascii(blk, 0, 4)) || !fixup(blk)) continue;
                int h = 0x18;
                parseIndex(blk, h + (int) Util.u32(blk, h), Math.min(ibs, h + (int) Util.u32(blk, h + 4)), map, self);
            }
        }

        List<Entry> out = new ArrayList<Entry>(map.values());
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry x, Entry y) {
                if (x.dir != y.dir) return x.dir ? -1 : 1;
                return x.name.toLowerCase(Locale.ROOT).compareTo(y.name.toLowerCase(Locale.ROOT));
            }
        });
        return out;
    }

    private static void parseIndex(byte[] b, int p, int end, Map<String, Entry> map, long self) {
        while (p >= 0 && p + 0x10 <= end) {
            long ref = Util.s64(b, p);
            int el = Util.u16(b, p + 8), kl = Util.u16(b, p + 0x0A), fl = Util.u16(b, p + 0x0C);
            if ((fl & 2) != 0 || el < 0x10 || p + el > end) break;
            int k = p + 0x10;
            if (kl >= 0x42 && k + kl <= end) {
                int nl = Util.u8(b, k + 0x40), ns = Util.u8(b, k + 0x41);
                long r = ref & MASK;
                if (ns != 2 && r != self && k + 0x42 + nl * 2 <= end) {
                    String name = Util.utf16(b, k + 0x42, nl);
                    long fflags = Util.u32(b, k + 0x38);
                    boolean dir = (fflags & 0x10000000L) != 0;
                    boolean hidden = (fflags & 0x06) != 0 || (r < 24 && name.startsWith("$"));
                    long mtime = Util.s64(b, k + 0x10) / 10000L - 11644473600000L;
                    map.put(r + "/" + name, new Entry(ref, self, name, dir, Util.s64(b, k + 0x30), mtime, hidden));
                }
            }
            p += el;
        }
    }
}
