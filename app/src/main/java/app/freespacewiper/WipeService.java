package app.freespacewiper;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

import app.freespacewiper.engine.EngineListener;
import app.freespacewiper.engine.Phase;
import app.freespacewiper.engine.Snapshot;
import app.freespacewiper.engine.WipeConfig;
import app.freespacewiper.engine.WipeEngine;
import app.freespacewiper.engine.WipeMode;

/**
 * Foreground service that owns the wipe:
 *  - startForeground() right away, with a visible (IMPORTANCE_LOW) progress notification
 *  - PARTIAL_WAKE_LOCK while writing, so the CPU keeps going with the screen off
 *  - START_STICKY + JobStore: a run killed by the system, or interrupted by a reboot, continues
 *    from the files already written
 *  - notification refreshed once per second from a ticker (never from the writer threads,
 *    which would trip the system's notification rate limit)
 *  - pause / continue; pauses by itself when the battery is nearly empty and not charging, and
 *    continues by itself as soon as the charger is connected (nothing written is thrown away)
 */
public final class WipeService extends Service {

    private static final String TAG = "FreeSpaceWiper";

    static final String ACTION_START = "app.freespacewiper.action.START";
    static final String ACTION_STOP = "app.freespacewiper.action.STOP";
    static final String ACTION_PAUSE = "app.freespacewiper.action.PAUSE";
    static final String ACTION_CONTINUE = "app.freespacewiper.action.CONTINUE";
    /** Continue the job stored in JobStore (sent by BootReceiver; a sticky restart has no action). */
    static final String ACTION_RESTORE = "app.freespacewiper.action.RESTORE";
    static final String EXTRA_DIR = "dir";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_THREADS = "threads";
    static final String EXTRA_PASSES = "passes";
    static final String EXTRA_RESUME = "resume";
    static final String EXTRA_START_PASS = "start_pass";

    static final int PAUSE_NONE = 0;
    static final int PAUSE_USER = 1;
    static final int PAUSE_BATTERY = 2;

    private static final long WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000;
    private static final long WAKE_LOCK_RENEW_MS = 60L * 60 * 1000;
    private static final long BATTERY_CHECK_MS = 15_000;
    /** Finishing matters more than a few percent of charge: only pause when the phone is about to die. */
    static final int LOW_BATTERY_PERCENT = 5;

    static final LogBuffer LOG = new LogBuffer(400);

    private static volatile WipeEngine sEngine;
    private static volatile Snapshot sFinal;
    private static volatile int sPauseReason = PAUSE_NONE;
    private static volatile int sPauseBattery;

    private Handler main;
    private PowerManager.WakeLock wakeLock;
    private long wakeLockAcquiredAt;
    private Thread runner;
    private long lastBatteryCheck;
    private boolean ignoreLowBattery;
    private boolean receiverRegistered;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            main.postDelayed(this, 1000);
        }
    };

    /** The charger was connected: a job that paused for the battery continues by itself. */
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            WipeEngine e = sEngine;
            if (e != null && sPauseReason == PAUSE_BATTERY) {
                continueWriting(e);
            }
        }
    };

    /** Live snapshot of the running job, or null when idle. */
    static Snapshot snapshot() {
        WipeEngine e = sEngine;
        return e == null ? null : e.snapshot();
    }

    /** Final snapshot of the last job run in this process, or null. */
    static Snapshot lastFinal() {
        return sFinal;
    }

    /** Dismisses the result of the last job (the UI goes back to the setup screen). */
    static void clearFinal() {
        sFinal = null;
    }

    static boolean isRunning() {
        return sEngine != null;
    }

    static WipeConfig runningConfig() {
        WipeEngine e = sEngine;
        return e == null ? null : e.config();
    }

    /** PAUSE_NONE, PAUSE_USER or PAUSE_BATTERY. */
    static int pauseReason() {
        return sPauseReason;
    }

    /** Battery level at the moment the job paused for a low battery. */
    static int pauseBatteryLevel() {
        return sPauseBattery;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        Notifs.ensureChannels(this);
        IntentFilter power = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, power, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(powerReceiver, power);
        }
        receiverRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        WipeEngine running = sEngine;

        if (ACTION_STOP.equals(action) || ACTION_PAUSE.equals(action) || ACTION_CONTINUE.equals(action)) {
            if (running == null) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (ACTION_STOP.equals(action)) {
                // Persist "stopped" first: if the process dies during cleanup, a restart cleans up
                // instead of resuming a job the user cancelled.
                JobStore.markStopping(this);
                // A paused job gave up its wake lock; deleting the fill files needs the CPU again.
                acquireWakeLock();
                running.cancel();
                LOG.add(getString(R.string.log_stop_requested));
            } else if (ACTION_PAUSE.equals(action)) {
                pauseWriting(running, PAUSE_USER, 0);
            } else {
                // The user insists on going on: do not pause for the battery again in this job.
                if (sPauseReason == PAUSE_BATTERY) ignoreLowBattery = true;
                continueWriting(running);
            }
            return START_STICKY;
        }

        if (running != null) {
            // Duplicate start: keep the current job, but honour the startForegroundService() contract.
            goForeground(Notifs.progress(this, running.snapshot(), sPauseReason, sPauseBattery));
            return START_STICKY;
        }

        boolean stickyRestart = action == null;
        boolean restore = stickyRestart || ACTION_RESTORE.equals(action);
        // START and RESTORE arrive through startForegroundService(): the notification has to be up
        // before anything else can go wrong.
        boolean foreground = stickyRestart || goForeground(Notifs.preparing(this));

        if (restore && !JobStore.isResumable(this)) {
            // Killed after the user pressed Stop (or with nothing to resume): finish the cleanup.
            recoverInBackground(startId);
            return START_NOT_STICKY;
        }
        WipeConfig cfg = restore ? JobStore.loadResume(this) : configFrom(intent);
        if (cfg == null) {
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        WipeEngine engine = new WipeEngine(new AndroidPlatform(), cfg, listener);
        if (stickyRestart) {
            foreground = goForeground(Notifs.progress(this, engine.snapshot(), PAUSE_NONE, 0));
        }
        if (!foreground) {
            if (restore) {
                // The system would not let a restarted service into the foreground (Android 12+
                // without the battery exemption). Do not leave the disk full: clean up if it is
                // nearly full, else keep the files for a manual resume, and tell the user.
                recoverInBackground(startId);
            } else {
                Notifs.postResult(this, getString(R.string.result_failed_title),
                        getString(R.string.result_failed_text, "startForeground"));
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }

        acquireWakeLock();
        long previousStart = JobStore.activeStarted(this);
        JobStore.saveActive(this, cfg, !restore);
        sFinal = null;
        sPauseReason = PAUSE_NONE;
        ignoreLowBattery = false;
        lastBatteryCheck = 0;
        if (stickyRestart) {
            LOG.add(getString(R.string.log_restarted));
            String why = ExitReasons.describeLast(this, previousStart);
            if (why != null) LOG.add(why);
        } else if (restore) {
            LOG.add(getString(R.string.log_boot_resume));
        }
        LOG.add(getString(R.string.log_target, cfg.dir.getAbsolutePath()));
        Log.i(TAG, "start dir=" + cfg.dir + " mode=" + cfg.mode + " threads=" + cfg.threads
                + (cfg.adaptive ? " (auto, max " + cfg.maxThreads + ")" : "")
                + " passes=" + cfg.passes + " resume=" + cfg.resume + " startPass=" + cfg.startPass);

        sEngine = engine;
        runner = new Thread(() -> {
            Phase result = engine.run();
            main.post(() -> onFinished(engine, result));
        }, "wipe-coordinator");
        runner.start();

        main.removeCallbacks(ticker);
        main.post(ticker);
        return START_STICKY;
    }

    private void recoverInBackground(int startId) {
        final Context app = this;
        new Thread(() -> {
            Recovery.run(app).notifyUser(app, false);
            main.post(() -> {
                stopForegroundCompat();
                stopSelf(startId);
            });
        }, "wipe-recovery").start();
    }

    private WipeConfig configFrom(Intent i) {
        String dir = i.getStringExtra(EXTRA_DIR);
        if (dir == null) return null;
        return JobStore.config(this, new File(dir), WipeMode.parse(i.getStringExtra(EXTRA_MODE)),
                i.getIntExtra(EXTRA_THREADS, JobStore.THREADS_AUTO), i.getIntExtra(EXTRA_PASSES, 1),
                i.getBooleanExtra(EXTRA_RESUME, false), i.getIntExtra(EXTRA_START_PASS, 1));
    }

    private final EngineListener listener = new EngineListener() {
        @Override
        public void onEvent(Event event, Object... args) {
            String line = EventText.format(WipeService.this, event, args);
            if (line == null) return;
            LOG.add(line);
            Log.i(TAG, line);
        }

        @Override
        public void onPassStarting(int pass) {
            JobStore.updatePass(WipeService.this, pass);
        }
    };

    // ------------------------------------------------------------------ pause / continue

    private void pauseWriting(WipeEngine e, int reason, int batteryLevel) {
        if (e.isPaused()) {
            // A manual pause on top of a battery pause: do not continue by itself any more.
            if (reason == PAUSE_USER) sPauseReason = PAUSE_USER;
        } else {
            sPauseReason = reason;
            sPauseBattery = batteryLevel;
            e.setPaused(true);
            String line = reason == PAUSE_BATTERY
                    ? getString(R.string.log_paused_battery, batteryLevel)
                    : getString(R.string.log_paused_user);
            LOG.add(line);
            Log.i(TAG, line);
            // Nothing to do until we continue: let the CPU sleep. The foreground service stays.
            releaseWakeLock();
        }
        refreshNotification(e);
    }

    private void continueWriting(WipeEngine e) {
        if (!e.isPaused()) return;
        acquireWakeLock();
        sPauseReason = PAUSE_NONE;
        e.setPaused(false);
        LOG.add(getString(R.string.log_continued));
        Log.i(TAG, "continued");
        refreshNotification(e);
    }

    private void checkBattery(WipeEngine e) {
        if (e.isCancelled()) return;
        Battery battery = Battery.read(this);
        int level = battery.level;
        boolean charging = battery.plugged;
        if (sPauseReason == PAUSE_BATTERY) {
            if (charging) continueWriting(e);
            return;
        }
        if (!e.isPaused() && !ignoreLowBattery && level > 0 && level <= LOW_BATTERY_PERCENT && !charging) {
            // If the phone died now, the job would only continue after the next boot. Pausing keeps
            // everything written so far and waits for the charger instead.
            pauseWriting(e, PAUSE_BATTERY, level);
        }
    }

    // ------------------------------------------------------------------ notification, ticker

    private boolean goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifs.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(Notifs.ID_PROGRESS, n);
            }
            return true;
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException (API 31+) or a SecurityException.
            Log.w(TAG, "startForeground failed", e);
            return false;
        }
    }

    private void refreshNotification(WipeEngine e) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(Notifs.ID_PROGRESS, Notifs.progress(this, e.snapshot(), sPauseReason, sPauseBattery));
        } catch (RuntimeException ignored) {
            // never let a notification problem stop the wipe
        }
    }

    private void tick() {
        WipeEngine e = sEngine;
        if (e == null) return;
        refreshNotification(e);
        long now = SystemClock.elapsedRealtime();
        if (wakeLock != null && wakeLock.isHeld() && now - wakeLockAcquiredAt > WAKE_LOCK_RENEW_MS) {
            acquireWakeLock();
        }
        if (now - lastBatteryCheck >= BATTERY_CHECK_MS) {
            lastBatteryCheck = now;
            checkBattery(e);
        }
    }

    private void onFinished(WipeEngine engine, Phase result) {
        if (engine != sEngine) return;
        main.removeCallbacks(ticker);
        Snapshot s = engine.snapshot();
        sFinal = s;
        sEngine = null;
        sPauseReason = PAUSE_NONE;
        JobStore.finish(this, result, s.totalBytes, s.failure, s.unreachableBytes, s.elapsedMs);
        releaseWakeLock();
        stopForegroundCompat();

        String title;
        String text;
        if (result == Phase.DONE) {
            title = getString(R.string.result_done_title);
            text = resultText(this, s);
        } else if (result == Phase.CANCELLED) {
            title = getString(R.string.result_cancelled_title);
            text = getString(R.string.result_cancelled_text, Fmt.bytes(s.totalBytes));
        } else {
            title = getString(R.string.result_failed_title);
            text = getString(R.string.result_failed_text, String.valueOf(s.failure));
        }
        Notifs.postResult(this, title, text);
        stopSelf();
    }

    /** Summary of a finished job, shared by the notification and the result card. */
    static String resultText(Context c, Snapshot s) {
        String text = c.getString(R.string.result_done_text, Fmt.bytes(s.totalBytes),
                Fmt.duration(c, s.activeMs), Fmt.speed(s.avgBps));
        if (s.unreachableBytes >= 0) {
            text += "\n" + c.getString(R.string.result_unreachable, Fmt.bytes(s.unreachableBytes));
        }
        return text;
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeSpaceWiper:fill");
            wakeLock.setReferenceCounted(false);
        }
        // Non reference counted: calling acquire() again just extends the timeout.
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        wakeLockAcquiredAt = SystemClock.elapsedRealtime();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(ticker);
        if (receiverRegistered) {
            receiverRegistered = false;
            unregisterReceiver(powerReceiver);
        }
        WipeEngine e = sEngine;
        Thread t = runner;
        if (e != null) {
            // Unexpected destroy while running: stop and give the engine a moment to delete its files.
            JobStore.markStopping(this);
            e.cancel();
            if (t != null) {
                try {
                    t.join(3000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // If cleanup is still running, keep the CPU awake: onFinished() releases the lock when it ends.
        if (t == null || !t.isAlive()) {
            releaseWakeLock();
        } else if (wakeLock == null || !wakeLock.isHeld()) {
            acquireWakeLock();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
