package app.freespacewiper;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.text.format.DateFormat;

import java.util.Date;
import java.util.List;

/**
 * Answers "why did the wipe stop?" on Android 11+: the system records why each of our processes
 * died (low memory, force stop, crash...). That record lives in the system, so it is available
 * even though the app itself could not write a log while the storage was full.
 */
final class ExitReasons {

    private ExitReasons() {
    }

    /**
     * One localized line about the most recent death of this app's process, or null when unknown
     * (Android 10 and older, or nothing recorded at or after {@code notBeforeMillis}).
     */
    static String describeLast(Context c, long notBeforeMillis) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            return Api30.describe(c, notBeforeMillis);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Kept in its own class so devices below API 30 never load code that mentions the new API. */
    @TargetApi(30)
    private static final class Api30 {

        static String describe(Context c, long notBeforeMillis) {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            if (am == null) return null;
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 1);
            if (list == null || list.isEmpty()) return null;
            ApplicationExitInfo info = list.get(0);
            if (info.getTimestamp() < notBeforeMillis) return null;
            Date when = new Date(info.getTimestamp());
            String line = c.getString(R.string.exit_reason_line, c.getString(label(info.getReason())),
                    DateFormat.getTimeFormat(c).format(when));
            String detail = info.getDescription();
            if (detail != null && !detail.isEmpty()) line += " [" + detail + "]";
            return line;
        }

        private static int label(int reason) {
            switch (reason) {
                case ApplicationExitInfo.REASON_LOW_MEMORY:
                    return R.string.exit_low_memory;
                case ApplicationExitInfo.REASON_SIGNALED:
                    return R.string.exit_signaled;
                case ApplicationExitInfo.REASON_USER_REQUESTED:
                case ApplicationExitInfo.REASON_USER_STOPPED:
                    return R.string.exit_user_requested;
                case ApplicationExitInfo.REASON_CRASH:
                case ApplicationExitInfo.REASON_CRASH_NATIVE:
                case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                    return R.string.exit_crash;
                case ApplicationExitInfo.REASON_ANR:
                    return R.string.exit_anr;
                case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                    return R.string.exit_excessive;
                case ApplicationExitInfo.REASON_EXIT_SELF:
                    return R.string.exit_self;
                default:
                    return R.string.exit_other;
            }
        }
    }
}
