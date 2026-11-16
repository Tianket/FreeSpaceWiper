import app.freespacewiper.engine.FileTooLargeException;
import app.freespacewiper.engine.NoSpaceException;
import app.freespacewiper.engine.Platform;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/** Desktop implementation of Platform, used only for testing the engine on a real file system. */
public class JvmPlatform implements Platform {

    static IOException map(IOException e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("No space left") || m.contains("Disk quota")) return new NoSpaceException(m);
        if (m.contains("File too large")) return new FileTooLargeException(m);
        return e;
    }

    @Override
    public Sink create(File file) throws IOException {
        final FileChannel ch;
        try {
            ch = FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw map(e);
        }
        return new Sink() {
            @Override
            public int write(byte[] buf, int off, int len) throws IOException {
                try {
                    int n = ch.write(ByteBuffer.wrap(buf, off, len));
                    if (n <= 0) throw new NoSpaceException("write returned " + n);
                    return n;
                } catch (IOException e) {
                    throw map(e);
                }
            }

            @Override
            public void sync() throws IOException {
                try {
                    ch.force(true);
                } catch (IOException e) {
                    throw map(e);
                }
            }

            @Override
            public void close() throws IOException {
                ch.close();
            }
        };
    }

    @Override
    public long freeBytes(File dir) {
        return dir.getUsableSpace();
    }

    @Override
    public long totalBytes(File dir) {
        return dir.getTotalSpace();
    }

    @Override
    public long freeBytesIncludingReserved(File dir) {
        return dir.getFreeSpace();
    }
}
