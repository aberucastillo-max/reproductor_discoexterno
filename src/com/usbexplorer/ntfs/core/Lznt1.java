package com.usbexplorer.ntfs.core;

import java.io.IOException;

/** Descompresor LZNT1 (compresión nativa de NTFS). */
final class Lznt1 {
    private static final int CHUNK = 4096;

    private Lznt1() {}

    static void decompress(byte[] src, int srcLen, byte[] dst) throws IOException {
        int sp = 0, dp = 0;
        while (sp + 2 <= srcLen && dp < dst.length) {
            int h = Util.u16(src, sp);
            sp += 2;
            if (h == 0) break;
            int csize = (h & 0x0FFF) + 1;
            int end = Math.min(sp + csize, srcLen);
            int start = dp;
            int chunkEnd = Math.min(start + CHUNK, dst.length);
            if ((h & 0x8000) == 0) {               // trozo sin comprimir
                int n = Math.min(end - sp, chunkEnd - dp);
                System.arraycopy(src, sp, dst, dp, n);
            } else {
                while (sp < end && dp < chunkEnd) {
                    int flags = src[sp++] & 0xFF;
                    for (int bit = 0; bit < 8 && sp < end && dp < chunkEnd; bit++, flags >>= 1) {
                        if ((flags & 1) == 0) {
                            dst[dp++] = src[sp++];
                            continue;
                        }
                        if (sp + 2 > end) { sp = end; break; }
                        int t = Util.u16(src, sp);
                        sp += 2;
                        int lb = 12;
                        for (int i = dp - start - 1; i >= 0x10; i >>= 1) lb--;
                        int disp = (t >>> lb) + 1;
                        int length = (t & ((1 << lb) - 1)) + 3;
                        int from = dp - disp;
                        if (from < start) throw new IOException("Datos comprimidos dañados");
                        for (int i = 0; i < length && dp < chunkEnd; i++) dst[dp++] = dst[from++];
                    }
                }
            }
            sp = end;
            dp = chunkEnd;   // cada trozo representa 4096 bytes; el resto son ceros
        }
    }
}
