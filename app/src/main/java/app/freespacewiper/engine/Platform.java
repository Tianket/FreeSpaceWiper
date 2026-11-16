package app.freespacewiper.engine;

import java.io.File;
import java.io.IOException;

/**
 * Thin abstraction over the few file-system calls the engine needs.
 * The Android implementation uses android.system.Os (raw open/write/fsync/statvfs),
 * tests use plain java.nio. Keeping the engine free of android.* lets us run it
 * against a real (small) file system on a desktop JVM.
 */
public interface Platform {

    /** Creates or truncates {@code file} for writing (mode 0600). */
    Sink create(File file) throws IOException;

    /** Bytes this (unprivileged) process can still allocate on the volume holding {@code dir}. */
    long freeBytes(File dir) throws IOException;

    /** Total size of the volume holding {@code dir}. */
    long totalBytes(File dir) throws IOException;

    /**
     * All free bytes on the volume, including the blocks reserved for root / system processes.
     * Sampled when the volume is "full" for this process, it is the space nobody could overwrite.
     */
    long freeBytesIncludingReserved(File dir) throws IOException;

    interface Sink {
        /**
         * Writes at most {@code len} bytes and returns how many were written (always &gt; 0).
         * Throws {@link NoSpaceException} when the volume is full and
         * {@link FileTooLargeException} when the per-file size limit is hit.
         */
        int write(byte[] buf, int off, int len) throws IOException;

        /** fsync(2): forces the written data out of the page cache onto the storage device. */
        void sync() throws IOException;

        void close() throws IOException;
    }
}
