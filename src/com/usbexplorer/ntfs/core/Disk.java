package com.usbexplorer.ntfs.core;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Acceso por bytes a un {@link BlockDevice}, con caché LRU de bloques de 64 KB.
 * Las lecturas grandes y alineadas van directas al dispositivo sin pasar por la caché.
 */
public final class Disk {
    private static final int CACHE_CHUNKS = 128;     // 8 MB de caché
    private static final int MAX_DIRECT = 1 << 20;   // 1 MB por lectura directa

    private final BlockDevice dev;
    private final int bs;
    private final int chunkSize;
    private final long totalBytes;
    private final LinkedHashMap<Long, byte[]> cache =
            new LinkedHashMap<Long, byte[]>(CACHE_CHUNKS * 2, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
                    return size() > CACHE_CHUNKS;
                }
            };

    public Disk(BlockDevice dev) {
        this.dev = dev;
        this.bs = dev.blockSize();
        int c = 64 * 1024;
        if (c < bs) c = bs;
        c -= c % bs;
        this.chunkSize = c;
        this.totalBytes = dev.blockCount() * (long) bs;
    }

    public long size() { return totalBytes; }
    public int blockSize() { return bs; }

    public synchronized void read(long pos, byte[] dst, int off, int len) throws IOException {
        if (pos < 0 || len < 0 || pos + len > totalBytes) {
            throw new IOException("Lectura fuera del disco (posición " + pos + ")");
        }
        while (len > 0) {
            long idx = pos / chunkSize;
            int in = (int) (pos % chunkSize);
            if (in == 0 && len >= chunkSize && !cache.containsKey(idx)) {
                int n = Math.min(len - len % chunkSize, MAX_DIRECT);
                long left = totalBytes - pos;
                if (n > left) n = (int) (left - left % chunkSize);
                if (n > 0) {
                    dev.readBlocks(pos / bs, dst, off, n / bs);
                    pos += n; off += n; len -= n;
                    continue;
                }
            }
            byte[] c = chunk(idx);
            int n = Math.min(len, c.length - in);
            System.arraycopy(c, in, dst, off, n);
            pos += n; off += n; len -= n;
        }
    }

    private byte[] chunk(long idx) throws IOException {
        byte[] c = cache.get(idx);
        if (c != null) return c;
        long start = idx * chunkSize;
        int n = (int) Math.min(chunkSize, totalBytes - start);
        c = new byte[n];
        dev.readBlocks(start / bs, c, 0, n / bs);
        cache.put(idx, c);
        return c;
    }

    public synchronized void clearCache() { cache.clear(); }
}
