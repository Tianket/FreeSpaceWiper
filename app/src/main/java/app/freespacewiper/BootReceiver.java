package app.freespacewiper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * After a reboot during a run (battery died, crash, manual restart) the job continues by itself
 * from the files already written. This is allowed a few times per job (see JobStore); after that,
 * or when the user had pressed Stop, the leftovers are handled by {@link Recovery}: deleted when
 * they leave the storage nearly full, otherwise kept for a manual resume.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final Context app = context.getApplicationContext();
        final Context c = LocaleHelper.wrap(app);
        if (!JobStore.isActive(c)) return;
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                Notifs.ensureChannels(c);
                if (JobStore.isResumable(c) && JobStore.loadResume(c) != null && JobStore.takeBootResume(c)
                        && startRestore(app)) {
                    return;
                }
                Recovery.run(c).notifyUser(c, true);
            } finally {
                pending.finish();
            }
        }, "boot-check").start();
    }

    private static boolean startRestore(Context app) {
        Intent i = new Intent(app, WipeService.class).setAction(WipeService.ACTION_RESTORE);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
            return true;
        } catch (RuntimeException e) {
            // Not allowed to start from the background on this device: fall back to the notification.
            return false;
        }
    }
}
