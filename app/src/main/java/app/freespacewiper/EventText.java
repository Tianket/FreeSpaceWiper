package app.freespacewiper;

import android.content.Context;

import app.freespacewiper.engine.EngineListener.Event;
import app.freespacewiper.engine.WipeMode;

/** Turns engine events into localized log lines. */
final class EventText {

    private EventText() {
    }

    static String modeLabel(Context c, WipeMode mode) {
        return c.getString(mode == WipeMode.ZERO ? R.string.mode_zero_short : R.string.mode_random_short);
    }

    /** One log line for the event, or null when the event is logged elsewhere. */
    static String format(Context c, Event e, Object[] a) {
        switch (e) {
            case PASS_START: {
                String mode = modeLabel(c, WipeMode.parse(String.valueOf(a[3])));
                return Boolean.TRUE.equals(a[5])
                        ? c.getString(R.string.log_pass_start_auto, i(a, 0), i(a, 1), i(a, 2), i(a, 6), mode)
                        : c.getString(R.string.log_pass_start, i(a, 0), i(a, 1), i(a, 2), mode);
            }
            case RESUMED:
                return c.getString(R.string.log_resumed, i(a, 0), Fmt.bytes(l(a, 1)));
            case LEFTOVERS_REMOVED:
                return c.getString(R.string.log_leftovers_removed, i(a, 0), Fmt.bytes(l(a, 1)));
            case WORKER_DISK_FULL:
                return c.getString(R.string.log_worker_full, i(a, 0), i(a, 1), Fmt.bytes(l(a, 2)));
            case WORKER_ERROR:
                return c.getString(R.string.log_worker_error, i(a, 0), String.valueOf(a[1]));
            case FILE_SIZE_LIMIT:
                return c.getString(R.string.log_file_limit, i(a, 0), Fmt.bytes(l(a, 1)));
            case SYNC_WARNING:
                return c.getString(R.string.log_sync_warning, String.valueOf(a[0]));
            case THREADS_CHANGED:
                return c.getString(R.string.log_threads_changed, i(a, 0), i(a, 1), Fmt.speed(d(a, 2)));
            case THREADS_SETTLED:
                return c.getString(R.string.log_threads_settled, i(a, 0), Fmt.speed(d(a, 1)));
            case PAUSED:
            case CONTINUED:
                // Logged by the service, which knows the reason.
                return null;
            case FILL_DONE: {
                long bytes = l(a, 0);
                double secs = d(a, 1);
                return c.getString(R.string.log_fill_done, Fmt.bytes(bytes), Fmt.seconds(c, secs));
            }
            case STALLED:
                return c.getString(R.string.log_stalled, i(a, 0));
            case SQUEEZE_DONE:
                return c.getString(R.string.log_squeeze_done, Fmt.bytes(l(a, 0)));
            case SWEEP_START:
                return c.getString(R.string.log_sweep_start, Fmt.bytes(l(a, 0)), Fmt.bytes(l(a, 1)));
            case SWEEP_DONE:
                return c.getString(R.string.log_sweep_done, Fmt.bytes(l(a, 0)));
            case DELETED:
                return c.getString(R.string.log_deleted, i(a, 0), Fmt.bytes(l(a, 1)));
            case DELETE_FAILED:
                return c.getString(R.string.log_delete_failed, String.valueOf(a[0]));
            case PASS_DONE: {
                String line = c.getString(R.string.log_pass_done, i(a, 0), Fmt.bytes(l(a, 1)), Fmt.seconds(c, d(a, 2)));
                long unreachable = l(a, 3);
                return unreachable >= 0 ? line + " · " + c.getString(R.string.result_unreachable, Fmt.bytes(unreachable)) : line;
            }
            case CANCELLED:
                return c.getString(R.string.log_cancelled);
            case FAILED:
                return c.getString(R.string.log_failed, String.valueOf(a[0]));
            case DONE:
                return c.getString(R.string.log_done, Fmt.bytes(l(a, 0)), Fmt.seconds(c, d(a, 1)));
            default:
                return e.name();
        }
    }

    private static int i(Object[] a, int k) {
        return ((Number) a[k]).intValue();
    }

    private static long l(Object[] a, int k) {
        return ((Number) a[k]).longValue();
    }

    private static double d(Object[] a, int k) {
        return ((Number) a[k]).doubleValue();
    }
}
