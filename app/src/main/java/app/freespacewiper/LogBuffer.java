package app.freespacewiper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small thread-safe ring buffer of timestamped log lines shown in the app. */
final class LogBuffer {

    private final String[] lines;
    private int start;
    private int size;
    private int version;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);

    LogBuffer(int capacity) {
        lines = new String[capacity];
    }

    synchronized void add(String line) {
        String stamped = time.format(new Date()) + "  " + line;
        if (size < lines.length) {
            lines[(start + size) % lines.length] = stamped;
            size++;
        } else {
            lines[start] = stamped;
            start = (start + 1) % lines.length;
        }
        version++;
    }

    synchronized void clear() {
        start = 0;
        size = 0;
        version++;
    }

    synchronized int version() {
        return version;
    }

    synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[(start + i) % lines.length]);
        }
        return sb.toString();
    }
}
