package app.freespacewiper;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStatVfs;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;

import app.freespacewiper.engine.FileTooLargeException;
import app.freespacewiper.engine.NoSpaceException;
import app.freespacewiper.engine.Platform;

/**
 * Direct system calls via android.system.Os: open(2)/write(2)/fsync(2)/statvfs(2).
 * No SAF, no ContentResolver, no FUSE for internal storage, and write(2) tells us exactly
 * how many bytes were accepted, so the last partial write before ENOSPC is counted correctly.
 */
final class AndroidPlatform implements Platform {

    @Override
    public Sink create(File file) throws IOException {
        try {
            FileDescriptor fd = Os.open(file.getAbsolutePath(),
                    OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_NOFOLLOW,
                    OsConstants.S_IRUSR | OsConstants.S_IWUSR);
            return new FdSink(fd);
        } catch (ErrnoException e) {
            throw map(e);
        }
    }

    @Override
    public long freeBytes(File dir) throws IOException {
        try {
            StructStatVfs st = Os.statvfs(dir.getAbsolutePath());
            // f_bavail = blocks available to unprivileged processes (excludes root-reserved blocks).
            return st.f_bavail * st.f_frsize;
        } catch (ErrnoException e) {
            throw map(e);
        }
    }

    @Override
    public long totalBytes(File dir) throws IOException {
        try {
            StructStatVfs st = Os.statvfs(dir.getAbsolutePath());
            return st.f_blocks * st.f_frsize;
        } catch (ErrnoException e) {
            throw map(e);
        }
    }

    @Override
    public long freeBytesIncludingReserved(File dir) throws IOException {
        try {
            StructStatVfs st = Os.statvfs(dir.getAbsolutePath());
            // f_bfree also counts the blocks only root / system processes may use.
            return st.f_bfree * st.f_frsize;
        } catch (ErrnoException e) {
            throw map(e);
        }
    }

    static IOException map(ErrnoException e) {
        int errno = e.errno;
        if (errno == OsConstants.ENOSPC || errno == OsConstants.EDQUOT) {
            return new NoSpaceException(e.getMessage());
        }
        if (errno == OsConstants.EFBIG) {
            return new FileTooLargeException(e.getMessage());
        }
        IOException io = new IOException(e.getMessage());
        io.initCause(e);
        return io;
    }

    private static final class FdSink implements Sink {
        private final FileDescriptor fd;

        FdSink(FileDescriptor fd) {
            this.fd = fd;
        }

        @Override
        public int write(byte[] buf, int off, int len) throws IOException {
            while (true) {
                try {
                    int n = Os.write(fd, buf, off, len);
                    if (n <= 0) {
                        // A regular file never legitimately accepts 0 bytes; treat it as full
                        // instead of spinning forever.
                        throw new NoSpaceException("write returned " + n);
                    }
                    return n;
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue;
                    throw map(e);
                } catch (InterruptedIOException e) {
                    // Thrown when a write was interrupted after a partial transfer; retry the rest.
                    int done = e.bytesTransferred;
                    if (done > 0) return done;
                }
            }
        }

        @Override
        public void sync() throws IOException {
            try {
                Os.fsync(fd);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EINTR) {
                    try {
                        Os.fsync(fd);
                    } catch (ErrnoException e2) {
                        throw map(e2);
                    }
                } else {
                    throw map(e);
                }
            }
            // After this the written pages are clean. Clean, never re-read cache pages are the first
            // thing the kernel reclaims, so they do not build up memory pressure the way dirty pages do.
        }

        @Override
        public void close() throws IOException {
            try {
                Os.close(fd);
            } catch (ErrnoException e) {
                throw map(e);
            }
        }
    }
}
