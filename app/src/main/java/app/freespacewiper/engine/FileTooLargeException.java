package app.freespacewiper.engine;

import java.io.IOException;

/** EFBIG: the file system's per-file size limit was reached (e.g. 4 GiB on FAT32 SD cards). */
public class FileTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    public FileTooLargeException(String message) {
        super(message);
    }
}
