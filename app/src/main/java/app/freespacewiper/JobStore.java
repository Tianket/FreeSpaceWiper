package app.freespacewiper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;

import app.freespacewiper.engine.Phase;
import app.freespacewiper.engine.WipeConfig;
import app.freespacewiper.engine.WipeMode;

/**
 * Persists the running job so it can be resumed after the process was killed or the phone rebooted.
 *
 * Three pieces of state:
 *  - SharedPreferences hold the job description (folder, mode, threads, passes, current pass).
 *    A commit can fail with ENOSPC while the disk is full (e.g. when resuming onto a full disk);
 *    SharedPreferences then keeps the previous, still valid file, which is harmless here.
 *  - An empty marker file means "this job should keep going". Stop deletes it, and unlink(2)
 *    needs no free space, so "the user pressed Stop" is recorded even on a completely full disk.
 *    A restart that finds the job active but the marker gone cleans up instead of resuming.
 *  - A few empty token files limit how often a job resumes by itself after a reboot. Taking a
 *    token is again an unlink, so the count is exact even when nothing can be written.
 */
final class JobStore {

    /** Thread choice meaning "let the engine find the best count". */
    static final int THREADS_AUTO = 0;

    private static final String PREFS = "job";
    private static final String MARKER = "job.marker";
    private static final String BOOT_TOKEN = "job.boot.";
    private static final int BOOT_TOKENS = 3;
    private static final String K_ACTIVE = "active";
    private static final String K_DIR = "dir";
    private static final String K_MODE = "mode";
    private static final String K_THREADS = "threads";
    private static final String K_PASSES = "passes";
    private static final String K_PASS = "pass";
    private static final String K_STARTED = "started";

    static final String K_LAST_RESULT = "last_result";
    static final String K_LAST_TOTAL = "last_total";
    static final String K_LAST_TIME = "last_time";
    static final String K_LAST_FAILURE = "last_failure";
    static final String K_LAST_UNREACHABLE = "last_unreachable";
    static final String K_LAST_ELAPSED = "last_elapsed";

    private JobStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File marker(Context c) {
        return new File(c.getFilesDir(), MARKER);
    }

    private static File bootToken(Context c, int i) {
        return new File(c.getFilesDir(), BOOT_TOKEN + i);
    }

    /**
     * The one place that turns the user's choices into an engine configuration.
     *
     * @param threadChoice a fixed thread count, or {@link #THREADS_AUTO}
     */
    static WipeConfig config(Context c, File dir, WipeMode mode, int threadChoice, int passes,
                             boolean resume, int startPass) {
        DeviceInfo device = DeviceInfo.get(c);
        WipeConfig.Builder b = WipeConfig.builder(dir)
                .mode(mode)
                .passes(passes)
                .resume(resume)
                .startPass(startPass)
                .dirtyBudgetBytes(device.dirtyBudgetBytes);
        if (threadChoice <= THREADS_AUTO) {
            b.adaptiveThreads(device.startThreads, device.maxThreads);
        } else {
            b.threads(device.clampManual(threadChoice));
        }
        return b.build();
    }

    static int threadChoice(WipeConfig cfg) {
        return cfg.adaptive ? THREADS_AUTO : cfg.threads;
    }

    /**
     * @param armBootResume true when a person started or resumed the job: it may then continue by
     *                      itself after the next reboots
     */
    static void saveActive(Context c, WipeConfig cfg, boolean armBootResume) {
        try {
            //noinspection ResultOfMethodCallIgnored
            marker(c).createNewFile();
            if (armBootResume) {
                for (int i = 0; i < BOOT_TOKENS; i++) {
                    //noinspection ResultOfMethodCallIgnored
                    bootToken(c, i).createNewFile();
                }
            }
        } catch (IOException ignored) {
            // Disk full while resuming: the worst case is that a later restart cleans up instead of resuming.
        }
        prefs(c).edit()
                .putBoolean(K_ACTIVE, true)
                .putString(K_DIR, cfg.dir.getAbsolutePath())
                .putString(K_MODE, cfg.mode.name())
                .putInt(K_THREADS, threadChoice(cfg))
                .putInt(K_PASSES, cfg.passes)
                .putInt(K_PASS, cfg.resume ? cfg.startPass : 1)
                .putLong(K_STARTED, System.currentTimeMillis())
                .commit();
    }

    static void updatePass(Context c, int pass) {
        prefs(c).edit().putInt(K_PASS, pass).commit();
    }

    /** Records "stop requested" in a way that survives a process death, even on a full disk. */
    static void markStopping(Context c) {
        //noinspection ResultOfMethodCallIgnored
        marker(c).delete();
        for (int i = 0; i < BOOT_TOKENS; i++) {
            //noinspection ResultOfMethodCallIgnored
            bootToken(c, i).delete();
        }
    }

    /** Uses up one automatic resume after a reboot. False when none is left. */
    static boolean takeBootResume(Context c) {
        for (int i = 0; i < BOOT_TOKENS; i++) {
            if (bootToken(c, i).delete()) return true;
        }
        return false;
    }

    static boolean isActive(Context c) {
        return prefs(c).getBoolean(K_ACTIVE, false);
    }

    /** Active and not stopped by the user: safe to continue. */
    static boolean isResumable(Context c) {
        return isActive(c) && marker(c).exists();
    }

    /** Wall-clock time the active job was last started or resumed. */
    static long activeStarted(Context c) {
        return prefs(c).getLong(K_STARTED, 0);
    }

    static String activeDir(Context c) {
        return prefs(c).getString(K_DIR, null);
    }

    static int activePass(Context c) {
        return prefs(c).getInt(K_PASS, 1);
    }

    static int activePasses(Context c) {
        return prefs(c).getInt(K_PASSES, 1);
    }

    /** Config that continues the interrupted job, or null if there is none. */
    static WipeConfig loadResume(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(K_ACTIVE, false)) return null;
        String dir = p.getString(K_DIR, null);
        if (dir == null) return null;
        return config(c, new File(dir), WipeMode.parse(p.getString(K_MODE, null)),
                p.getInt(K_THREADS, THREADS_AUTO), p.getInt(K_PASSES, 1), true, p.getInt(K_PASS, 1));
    }

    static void finish(Context c, Phase result, long totalBytes, String failure,
                       long unreachableBytes, long elapsedMs) {
        markStopping(c);
        prefs(c).edit()
                .putBoolean(K_ACTIVE, false)
                .putString(K_LAST_RESULT, result.name())
                .putLong(K_LAST_TOTAL, totalBytes)
                .putLong(K_LAST_TIME, System.currentTimeMillis())
                .putString(K_LAST_FAILURE, failure)
                .putLong(K_LAST_UNREACHABLE, unreachableBytes)
                .putLong(K_LAST_ELAPSED, elapsedMs)
                .commit();
    }

    static SharedPreferences lastResult(Context c) {
        return prefs(c);
    }

    static void clearActive(Context c) {
        markStopping(c);
        prefs(c).edit().putBoolean(K_ACTIVE, false).commit();
    }
}
