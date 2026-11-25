package com.usbexplorer.ntfs.core;

import java.io.IOException;

/** Dispositivo de bloques de solo lectura (disco USB, archivo de imagen...). */
public interface BlockDevice {
    /** Tamaño de bloque lógico en bytes (normalmente 512 o 4096). */
    int blockSize();

    /** Número total de bloques. */
    long blockCount();

    /** Lee {@code count} bloques empezando en {@code lba} dentro de {@code buf[off..]}. */
    void readBlocks(long lba, byte[] buf, int off, int count) throws IOException;
}
