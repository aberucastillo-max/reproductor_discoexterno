package com.usbexplorer.ntfs.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Imagen de disco en un archivo (usado en las pruebas). */
public final class FileBlockDevice implements BlockDevice {
    private final RandomAccessFile raf;
    private final int bs;
    private final long count;

    public FileBlockDevice(File f, int blockSize) throws IOException {
        raf = new RandomAccessFile(f, "r");
        bs = blockSize;
        count = raf.length() / blockSize;
    }

    @Override public int blockSize() { return bs; }
    @Override public long blockCount() { return count; }

    @Override
    public synchronized void readBlocks(long lba, byte[] buf, int off, int n) throws IOException {
        if (lba < 0 || lba + n > count) throw new IOException("Lectura fuera de la imagen: " + lba);
        raf.seek(lba * bs);
        raf.readFully(buf, off, n * bs);
    }

    public void close() throws IOException { raf.close(); }
}
