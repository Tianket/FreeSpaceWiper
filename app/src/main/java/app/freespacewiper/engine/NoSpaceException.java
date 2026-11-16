package app.freespacewiper.engine;

import java.io.IOException;

/** ENOSPC / EDQUOT: the volume is full. For this app that is the expected way a fill ends. */
public class NoSpaceException extends IOException {
    private static final long serialVersionUID = 1L;

    public NoSpaceException(String message) {
        super(message);
    }
}
