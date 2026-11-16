package app.freespacewiper;

import android.content.Context;

import java.io.File;

import app.freespacewiper.engine.Phase;
import app.freespacewiper.engine.WipeEngine;

/**
 * Handles a job that was interrupted and is not being resumed right now
 * (after a reboot, or when the system restarted the service but did not allow it into the foreground).
 * Rule: never leave the phone sitting with a (nearly) full disk.
 */
final class Recovery {

    /** Below this much free space the leftovers are removed automatically. */
    static final long AUTO_CLEAN_BELOW = 1_000_000_000L;

    static final int NOTHING = 0;
    /** The user had pressed Stop but cleanup never finished: leftovers deleted. */
    static final int CLEANED_STOPPED = 1;
    /** The disk was nearly full: leftovers deleted, the job has to be started again. */
    static final int CLEANED_FULL = 2;
    /** Enough space left: leftovers kept so the user can resume. */
    static final int KEPT = 3;

    final int outcome;
    final long bytes;

    private Recovery(int outcome, long bytes) {
        this.outcome = outcome;
        this.bytes = bytes;
    }

    /** Does disk I/O: call from a background thread. */
    static Recovery run(Context c) {
        if (!JobStore.isActive(c)) return new Recovery(NOTHING, 0);
        String dirPath = JobStore.activeDir(c);
        if (dirPath == null) {
            JobStore.clearActive(c);
            return new Recovery(NOTHING, 0);
        }
        File dir = new File(dirPath);
        File[] left = WipeEngine.listFillFiles(dir);
        long bytes = WipeEngine.sizeOf(left);
        if (!JobStore.isResumable(c)) {
            JobStore.clearActive(c);
            WipeEngine.deleteFiles(left);
            return new Recovery(left.length > 0 ? CLEANED_STOPPED : NOTHING, bytes);
        }
        long free = Targets.space(dir.exists() ? dir : dir.getParentFile())[0];
        if (left.length > 0 && free >= 0 && free < AUTO_CLEAN_BELOW) {
            JobStore.markStopping(c);
            WipeEngine.deleteFiles(left);
            JobStore.finish(c, Phase.CANCELLED, bytes, null, -1, 0);
            return new Recovery(CLEANED_FULL, bytes);
        }
        return new Recovery(KEPT, bytes);
    }

    void notifyUser(Context c, boolean afterReboot) {
        switch (outcome) {
            case CLEANED_STOPPED:
                Notifs.postResult(c, c.getString(R.string.result_cancelled_title),
                        c.getString(R.string.cleaned_stopped_text, Fmt.bytes(bytes)));
                break;
            case CLEANED_FULL:
                Notifs.postResult(c, c.getString(R.string.boot_cleaned_title),
                        c.getString(R.string.boot_cleaned_text, Fmt.bytes(bytes)));
                break;
            case KEPT:
                Notifs.postResult(c, c.getString(R.string.notif_interrupted_title), afterReboot
                        ? c.getString(R.string.boot_resume_text, Fmt.bytes(bytes))
                        : c.getString(R.string.notif_interrupted_text));
                break;
            default:
                break;
        }
    }
}
