package com.usbexplorer.ntfs.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Lee la tabla de particiones (MBR, particiones lógicas o GPT). */
public final class Partitions {

    public static final class Part {
        public final int index;
        public final long offset;   // en bytes
        public final long length;   // en bytes
        public final String fs;     // "NTFS", "exFAT", "FAT32", "FAT", "?"

        Part(int index, long offset, long length, String fs) {
            this.index = index; this.offset = offset; this.length = length; this.fs = fs;
        }

        public boolean isNtfs() { return "NTFS".equals(fs); }

        @Override public String toString() {
            return "Partición " + index + ": " + fs + ", " + Util.humanSize(length);
        }
    }

    private Partitions() {}

    public static String probeFs(Disk d, long off) throws IOException {
        if (off < 0 || off + 512 > d.size()) return "?";
        byte[] b = new byte[512];
        d.read(off, b, 0, 512);
        if ("NTFS    ".equals(Util.ascii(b, 3, 8))) return "NTFS";
        if ("EXFAT   ".equals(Util.ascii(b, 3, 8))) return "exFAT";
        if ((b[510] & 0xFF) == 0x55 && (b[511] & 0xFF) == 0xAA) {
            if (Util.ascii(b, 0x52, 5).equals("FAT32")) return "FAT32";
            if (Util.ascii(b, 0x36, 3).equals("FAT")) return "FAT";
        }
        return "?";
    }

    public static List<Part> scan(Disk d) throws IOException {
        List<Part> out = new ArrayList<Part>();
        int bs = d.blockSize();

        String whole = probeFs(d, 0);
        if (!"?".equals(whole)) {   // disco sin tabla de particiones
            out.add(new Part(1, 0, d.size(), whole));
            return out;
        }

        byte[] mbr = new byte[512];
        d.read(0, mbr, 0, 512);
        if ((mbr[510] & 0xFF) != 0x55 || (mbr[511] & 0xFF) != 0xAA) return out;

        boolean gpt = false;
        for (int i = 0; i < 4; i++) {
            if ((mbr[0x1BE + i * 16 + 4] & 0xFF) == 0xEE) gpt = true;
        }
        if (gpt && scanGpt(d, bs, out)) return out;

        int n = 1;
        for (int i = 0; i < 4; i++) {
            int e = 0x1BE + i * 16;
            int type = mbr[e + 4] & 0xFF;
            long start = Util.u32(mbr, e + 8);
            long count = Util.u32(mbr, e + 12);
            if (type == 0 || count == 0) continue;
            if (type == 0x05 || type == 0x0F || type == 0x85) {
                n = scanExtended(d, bs, start, out, n);
                continue;
            }
            n = add(d, out, n, start * bs, count * bs);
        }
        return out;
    }

    private static int add(Disk d, List<Part> out, int n, long off, long len) throws IOException {
        if (off >= d.size()) return n;
        if (off + len > d.size()) len = d.size() - off;
        out.add(new Part(n, off, len, probeFs(d, off)));
        return n + 1;
    }

    private static int scanExtended(Disk d, int bs, long extStart, List<Part> out, int n)
            throws IOException {
        long ebr = extStart;
        byte[] b = new byte[512];
        for (int guard = 0; guard < 128; guard++) {
            if (ebr * bs + 512 > d.size()) break;
            d.read(ebr * bs, b, 0, 512);
            if ((b[510] & 0xFF) != 0x55 || (b[511] & 0xFF) != 0xAA) break;
            long s1 = Util.u32(b, 0x1BE + 8), c1 = Util.u32(b, 0x1BE + 12);
            if ((b[0x1BE + 4] & 0xFF) != 0 && c1 != 0) n = add(d, out, n, (ebr + s1) * bs, c1 * bs);
            long s2 = Util.u32(b, 0x1CE + 8);
            if ((b[0x1CE + 4] & 0xFF) == 0 || s2 == 0) break;
            ebr = extStart + s2;
        }
        return n;
    }

    private static boolean scanGpt(Disk d, int bs, List<Part> out) throws IOException {
        byte[] h = new byte[512];
        d.read(bs, h, 0, 512);
        if (!"EFI PART".equals(Util.ascii(h, 0, 8))) return false;
        long entLba = Util.s64(h, 0x48);
        long num = Util.u32(h, 0x50);
        long esz = Util.u32(h, 0x54);
        if (esz < 128 || esz > 4096 || num == 0 || num > 1024) return false;
        byte[] t = new byte[(int) (num * esz)];
        if (entLba * bs + t.length > d.size()) return false;
        d.read(entLba * bs, t, 0, t.length);
        int n = 1;
        for (int i = 0; i < num; i++) {
            int e = (int) (i * esz);
            boolean empty = true;
            for (int k = 0; k < 16; k++) if (t[e + k] != 0) { empty = false; break; }
            if (empty) continue;
            long first = Util.s64(t, e + 0x20), last = Util.s64(t, e + 0x28);
            if (first <= 0 || last < first) continue;
            n = add(d, out, n, first * bs, (last - first + 1) * bs);
        }
        return true;
    }
}
