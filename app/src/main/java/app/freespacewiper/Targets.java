package app.freespacewiper;

import android.content.Context;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the fill files go.
 * Internal: the app's private files dir. It lives on the same /data partition as everything the
 * user stores on "internal storage" (/data/media is on that partition too), so filling it
 * overwrites the free space of the whole internal storage. Plain file I/O, no FUSE, no permission.
 * SD cards: the app-specific folder on each removable volume, also permission-free.
 */
final class Targets {

    static final String WORK_DIR = "wipe";

    static final class Target {
        final String key;
        final File dir;
        final boolean removable;
        final String label;

        Target(String key, File dir, boolean removable, String label) {
            this.key = key;
            this.dir = dir;
            this.removable = removable;
            this.label = label;
        }

        /** A folder that already exists on the same volume, for statvfs before the work dir exists. */
        File statDir() {
            return dir.exists() ? dir : dir.getParentFile();
        }
    }

    private Targets() {
    }

    static List<Target> list(Context c) {
        List<Target> out = new ArrayList<>();
        out.add(new Target("internal", new File(c.getFilesDir(), WORK_DIR), false,
                c.getString(R.string.target_internal)));
        File[] ext;
        try {
            ext = c.getExternalFilesDirs(null);
        } catch (RuntimeException e) {
            ext = new File[0];
        }
        int sd = 0;
        for (File d : ext) {
            if (d == null) continue;
            boolean removable;
            boolean emulated;
            try {
                removable = Environment.isExternalStorageRemovable(d);
                emulated = Environment.isExternalStorageEmulated(d);
            } catch (IllegalArgumentException e) {
                continue;
            }
            // The emulated "external" storage is the same /data partition as internal: skip it.
            if (emulated || !removable) continue;
            sd++;
            out.add(new Target("sd" + sd, new File(d, WORK_DIR), true, c.getString(R.string.target_sd, sd)));
        }
        return out;
    }

    static Target findByDir(List<Target> targets, String dirPath) {
        if (dirPath == null) return null;
        for (Target t : targets) {
            if (t.dir.getAbsolutePath().equals(dirPath)) return t;
        }
        return null;
    }

    /** {free, total} in bytes, or {-1, -1}. */
    static long[] space(File dir) {
        try {
            StructStatVfs st = Os.statvfs(dir.getAbsolutePath());
            return new long[]{st.f_bavail * st.f_frsize, st.f_blocks * st.f_frsize};
        } catch (ErrnoException | RuntimeException e) {
            return new long[]{-1, -1};
        }
    }
}
