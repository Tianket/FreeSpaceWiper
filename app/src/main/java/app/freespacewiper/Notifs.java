package app.freespacewiper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.Arrays;

import app.freespacewiper.engine.Phase;
import app.freespacewiper.engine.Snapshot;

/** Notification channels and builders. */
final class Notifs {

    static final String CH_PROGRESS = "wipe_progress";
    static final String CH_RESULT = "wipe_result";
    static final int ID_PROGRESS = 1001;
    static final int ID_RESULT = 1002;

    private Notifs() {
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        // IMPORTANCE_LOW: visible in the shade and status bar with a progress bar, but silent.
        NotificationChannel progress = new NotificationChannel(CH_PROGRESS,
                c.getString(R.string.ch_progress), NotificationManager.IMPORTANCE_LOW);
        progress.setDescription(c.getString(R.string.ch_progress_desc));
        progress.setShowBadge(false);
        NotificationChannel result = new NotificationChannel(CH_RESULT,
                c.getString(R.string.ch_result), NotificationManager.IMPORTANCE_DEFAULT);
        result.setDescription(c.getString(R.string.ch_result_desc));
        nm.createNotificationChannels(Arrays.asList(progress, result));
    }

    /** True if the progress notification can actually be seen. */
    static boolean progressVisible(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        // areNotificationsEnabled() only exists from API 24 (calling it on Android 6 crashes).
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = nm.getNotificationChannel(CH_PROGRESS);
            return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c, String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, channel);
        Notification.Builder b = new Notification.Builder(c);
        b.setPriority(CH_PROGRESS.equals(channel) ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT);
        return b;
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** "Stop" opens the app with a confirmation dialog, so a stray tap cannot abort a long run. */
    static PendingIntent confirmStop(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_CONFIRM_STOP, true);
        return PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent service(Context c, int requestCode, String action) {
        Intent i = new Intent(c, WipeService.class).setAction(action);
        return PendingIntent.getService(c, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification.Action action(Context c, int icon, int label, PendingIntent intent) {
        return new Notification.Action.Builder(Icon.createWithResource(c, icon), c.getString(label), intent).build();
    }

    static String phaseTitle(Context c, Snapshot s) {
        switch (s.phase) {
            case PREPARING:
                return c.getString(R.string.phase_preparing, s.pass, s.passes);
            case FILLING:
                return c.getString(R.string.phase_filling, s.pass, s.passes);
            case SQUEEZING:
                return c.getString(R.string.phase_squeezing, s.pass, s.passes);
            case SWEEPING:
                return c.getString(R.string.phase_sweeping, s.pass, s.passes);
            case DELETING:
                return c.getString(R.string.phase_deleting, s.pass, s.passes);
            case DONE:
                return c.getString(R.string.phase_done);
            case CANCELLED:
                return c.getString(R.string.phase_cancelled);
            case FAILED:
            default:
                return c.getString(R.string.phase_failed);
        }
    }

    /** Why the job is paused, in one sentence. */
    static String pauseText(Context c, int reason, int batteryLevel) {
        return reason == WipeService.PAUSE_BATTERY
                ? c.getString(R.string.pause_reason_battery, batteryLevel)
                : c.getString(R.string.pause_reason_user);
    }

    /** Shown for the first moment after the service started, before the engine reports anything. */
    static Notification preparing(Context c) {
        Notification.Builder b = builder(c, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_wipe)
                .setContentTitle(c.getString(R.string.app_name))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openApp(c));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    static Notification progress(Context c, Snapshot s, int pauseReason, int pauseBattery) {
        int pm = s.permille();
        boolean indeterminate = pm < 0 || s.phase == Phase.PREPARING || s.phase == Phase.DELETING;
        String pct = pm < 0 ? "" : (pm / 10) + "% · ";
        String title;
        String text;
        String big;
        if (s.paused) {
            title = c.getString(R.string.phase_paused);
            text = pct + pauseText(c, pauseReason, pauseBattery);
            big = text + "\n" + c.getString(R.string.notif_text, Fmt.bytes(s.passBytes), Fmt.bytes(s.freeBytes));
        } else {
            title = phaseTitle(c, s);
            text = pct + c.getString(R.string.notif_text, Fmt.bytes(s.passBytes), Fmt.bytes(s.freeBytes));
            big = text + "\n"
                    + c.getString(R.string.stats_speed, Fmt.speed(s.speedBps), Fmt.speed(s.avgBps)) + "\n"
                    + c.getString(R.string.stats_time, Fmt.duration(c, s.activeMs), Fmt.duration(c, s.etaMs)) + "\n"
                    + c.getString(R.string.stats_workers, s.activeWorkers, s.threads, s.filesInPass, s.errors);
        }
        Notification.Builder b = builder(c, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_wipe)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(big))
                .setProgress(1000, Math.max(0, pm), indeterminate && !s.paused)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openApp(c));
        if (s.paused) {
            b.addAction(action(c, R.drawable.ic_play, R.string.action_continue,
                    service(c, 2, WipeService.ACTION_CONTINUE)));
        } else {
            b.addAction(action(c, R.drawable.ic_pause, R.string.action_pause,
                    service(c, 3, WipeService.ACTION_PAUSE)));
        }
        b.addAction(action(c, R.drawable.ic_stop, R.string.action_stop, confirmStop(c)));
        if (Build.VERSION.SDK_INT >= 31) {
            // Show immediately instead of after the default 10 s FGS notification delay.
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    static void postResult(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification n = builder(c, CH_RESULT)
                .setSmallIcon(R.drawable.ic_stat_wipe)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(c))
                .build();
        try {
            nm.notify(ID_RESULT, n);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted: the result is still shown in the app.
        }
    }
}
