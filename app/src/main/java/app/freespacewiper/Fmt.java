package app.freespacewiper;

import android.content.Context;

import java.util.Locale;

/** Number formatting. Decimal units (1 GB = 10^9 bytes), the same as Android's own storage settings. */
final class Fmt {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private Fmt() {
    }

    static String bytes(long b) {
        if (b < 0) return "–";
        if (b < 1000) return String.format(Locale.US, "%d B", b);
        double v = b;
        int i = 0;
        while (v >= 1000 && i < UNITS.length - 1) {
            v /= 1000;
            i++;
        }
        String pattern = v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s";
        return String.format(Locale.US, pattern, v, UNITS[i]);
    }

    static String speed(double bytesPerSecond) {
        if (bytesPerSecond <= 0) return "–";
        return bytes((long) bytesPerSecond) + "/s";
    }

    static String duration(Context c, long ms) {
        if (ms < 0) return "–";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return c.getString(R.string.dur_hm, h, m);
        if (m > 0) return c.getString(R.string.dur_ms, m, sec);
        return c.getString(R.string.dur_s, sec);
    }

    static String seconds(Context c, double seconds) {
        return duration(c, (long) (seconds * 1000));
    }
}
