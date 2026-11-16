package app.freespacewiper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.SparseArray;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.freespacewiper.engine.Phase;
import app.freespacewiper.engine.Snapshot;
import app.freespacewiper.engine.WipeConfig;
import app.freespacewiper.engine.WipeEngine;
import app.freespacewiper.engine.WipeMode;

/**
 * One screen with three states:
 *  - setup:   target, a one-line summary of the options (tap to change them) and the Start button,
 *             plus the leftovers of an interrupted run, if any
 *  - running: progress ring, live figures, Pause and Stop
 *  - result:  what was overwritten and what could not be reached, until dismissed
 * Warnings (not charging, background restricted, notifications off) only appear when they apply.
 */
public final class MainActivity extends Activity {

    static final String EXTRA_CONFIRM_STOP = "confirm_stop";

    private static final int REQ_NOTIF = 10;
    private static final int REQ_BATTERY = 11;
    private static final String SETTINGS = "settings";
    private static final String S_THREADS = "threads";
    private static final String S_PASSES = "passes";
    private static final String S_KEEP_SCREEN = "keep_screen";
    private static final String S_TARGET = "target";
    private static final String S_BATTERY_ASKED = "battery_asked";
    private static final String S_OPTIONS_OPEN = "options_open";

    /** A pause in the data flow shorter than this is not worth mentioning. */
    private static final long STALL_SHOW_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    private TextView txtIntro;
    private TextView warnPower;
    private TextView warnBattery;
    private TextView warnNotif;
    private TextView txtLeftover;
    private TextView txtPhase;
    private TextView txtPercent;
    private TextView lblWritten;
    private TextView lblFree;
    private TextView lblSpeed;
    private TextView lblEta;
    private TextView statWritten;
    private TextView statFree;
    private TextView statSpeed;
    private TextView statEta;
    private TextView txtDetail;
    private TextView txtStall;
    private TextView txtResult;
    private TextView txtThreadsHint;
    private TextView txtOptionsSummary;
    private TextView txtLastResult;
    private TextView txtLog;
    private Button btnStart;
    private Button btnPause;
    private Button btnDismiss;
    private RadioGroup groupTarget;
    private RadioGroup groupThreads;
    private RadioGroup groupPasses;
    private Switch swKeepScreen;
    private View panelWarn;
    private View panelStatus;
    private View panelStats;
    private View panelRunButtons;
    private View panelSetup;
    private View panelLeftover;
    private View panelLog;
    private View panelOptions;
    private View imgOptionsArrow;
    private RingProgressView ring;

    private final List<Targets.Target> targets = new ArrayList<>();
    private final SparseArray<Targets.Target> targetByViewId = new SparseArray<>();
    private DeviceInfo device;
    private File leftoverDir;
    private int lastLogVersion = -1;
    private long lastSlowRefresh;
    private boolean wasRunning;
    private boolean pendingStart;
    private boolean notifAsked;
    private boolean batteryAsked;
    private boolean exitReasonLoaded;
    private boolean keepingScreenOn;
    private String exitReason;
    private volatile boolean deleting;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        device = DeviceInfo.get(this);

        txtIntro = findViewById(R.id.txt_intro);
        warnPower = findViewById(R.id.warn_power);
        warnBattery = findViewById(R.id.warn_battery);
        warnNotif = findViewById(R.id.warn_notif);
        txtLeftover = findViewById(R.id.txt_leftover);
        txtPhase = findViewById(R.id.txt_phase);
        txtPercent = findViewById(R.id.txt_percent);
        lblWritten = findViewById(R.id.lbl_written);
        lblFree = findViewById(R.id.lbl_free);
        lblSpeed = findViewById(R.id.lbl_speed);
        lblEta = findViewById(R.id.lbl_eta);
        statWritten = findViewById(R.id.stat_written);
        statFree = findViewById(R.id.stat_free);
        statSpeed = findViewById(R.id.stat_speed);
        statEta = findViewById(R.id.stat_eta);
        txtDetail = findViewById(R.id.txt_detail);
        txtStall = findViewById(R.id.txt_stall);
        txtResult = findViewById(R.id.txt_result);
        txtThreadsHint = findViewById(R.id.txt_threads_hint);
        txtOptionsSummary = findViewById(R.id.txt_options_summary);
        txtLastResult = findViewById(R.id.txt_last_result);
        txtLog = findViewById(R.id.txt_log);
        btnStart = findViewById(R.id.btn_start);
        btnPause = findViewById(R.id.btn_pause);
        btnDismiss = findViewById(R.id.btn_dismiss);
        groupTarget = findViewById(R.id.group_target);
        groupThreads = findViewById(R.id.group_threads);
        groupPasses = findViewById(R.id.group_passes);
        swKeepScreen = findViewById(R.id.sw_keep_screen);
        panelWarn = findViewById(R.id.panel_warn);
        panelStatus = findViewById(R.id.panel_status);
        panelStats = findViewById(R.id.panel_stats);
        panelRunButtons = findViewById(R.id.panel_run_buttons);
        panelSetup = findViewById(R.id.panel_setup);
        panelLeftover = findViewById(R.id.panel_leftover);
        panelLog = findViewById(R.id.panel_log);
        panelOptions = findViewById(R.id.panel_options);
        imgOptionsArrow = findViewById(R.id.img_options_arrow);
        ring = findViewById(R.id.ring);

        txtThreadsHint.setText(getString(R.string.hint_threads, device.cores, Fmt.bytes(device.ramBytes),
                device.startThreads, device.maxThreads));
        loadSettings();

        btnStart.setOnClickListener(v -> onStartClicked());
        btnPause.setOnClickListener(v -> togglePause());
        findViewById(R.id.btn_stop).setOnClickListener(v -> confirmStop());
        btnDismiss.setOnClickListener(v -> {
            WipeService.clearFinal();
            lastSlowRefresh = 0;
            refresh();
        });
        findViewById(R.id.btn_resume).setOnClickListener(v -> resumeLeftovers());
        findViewById(R.id.btn_delete_leftover).setOnClickListener(v -> confirmDeleteLeftovers());
        findViewById(R.id.btn_help).setOnClickListener(v -> showHelp());
        findViewById(R.id.btn_lang).setOnClickListener(this::showLanguageMenu);
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> copyLog());
        warnNotif.setOnClickListener(v -> fixNotifications());
        warnBattery.setOnClickListener(v -> {
            if (!requestBatteryExemption()) openBatterySettings();
        });
        findViewById(R.id.btn_options).setOnClickListener(v -> {
            boolean open = panelOptions.getVisibility() != View.VISIBLE;
            settings().edit().putBoolean(S_OPTIONS_OPEN, open).apply();
            showOptions(open);
        });
        swKeepScreen.setOnCheckedChangeListener((b, checked) -> {
            saveSettings();
            applyKeepScreen(WipeService.isRunning());
        });
        RadioGroup.OnCheckedChangeListener save = (g, id) -> saveSettings();
        groupThreads.setOnCheckedChangeListener(save);
        groupPasses.setOnCheckedChangeListener(save);

        handleIntent(getIntent());

        // Running without background restrictions is the default this app wants: ask the system for
        // it once, right at the first launch, instead of leaving it to a manual trip into Settings.
        // (Android only grants it through its own confirmation dialog.)
        if (savedInstanceState == null && !settings().getBoolean(S_BATTERY_ASKED, false)) {
            settings().edit().putBoolean(S_BATTERY_ASKED, true).apply();
            handler.post(() -> {
                batteryAsked = true;
                requestBatteryExemption();
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_CONFIRM_STOP, false)) {
            i.removeExtra(EXTRA_CONFIRM_STOP);
            handler.post(this::confirmStop);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildTargets();
        lastSlowRefresh = 0;
        exitReasonLoaded = false;
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    // ------------------------------------------------------------------ settings

    private SharedPreferences settings() {
        return getSharedPreferences(SETTINGS, MODE_PRIVATE);
    }

    private void loadSettings() {
        SharedPreferences p = settings();
        applyChoices(p.getInt(S_THREADS, JobStore.THREADS_AUTO), p.getInt(S_PASSES, 1));
        // On by default: an open, lit screen is the most reliable way to keep aggressive
        // vendor power managers from interfering, and the phone is on the charger anyway.
        swKeepScreen.setChecked(p.getBoolean(S_KEEP_SCREEN, true));
        showOptions(p.getBoolean(S_OPTIONS_OPEN, false));
        updateOptionsSummary();
    }

    private void showOptions(boolean open) {
        setVisible(panelOptions, open);
        imgOptionsArrow.setRotation(open ? 180f : 0f);
    }

    /** The collapsed options card: what a tap on Start will use. */
    private void updateOptionsSummary() {
        int threads = selectedThreads();
        String threadText = threads == JobStore.THREADS_AUTO
                ? getString(R.string.threads_auto_chip) : String.valueOf(device.clampManual(threads));
        String text = getString(R.string.options_summary, threadText, selectedPasses());
        if (swKeepScreen.isChecked()) text += " · " + getString(R.string.options_screen_on);
        setText(txtOptionsSummary, text);
    }

    private void applyChoices(int threadChoice, int passes) {
        int tid;
        if (threadChoice <= JobStore.THREADS_AUTO) tid = R.id.rb_tauto;
        else if (threadChoice >= 32) tid = R.id.rb_t32;
        else if (threadChoice >= 16) tid = R.id.rb_t16;
        else if (threadChoice >= 8) tid = R.id.rb_t8;
        else if (threadChoice >= 4) tid = R.id.rb_t4;
        else tid = R.id.rb_t2;
        groupThreads.check(tid);
        groupPasses.check(passes >= 3 ? R.id.rb_p3 : passes == 2 ? R.id.rb_p2 : R.id.rb_p1);
    }

    private void saveSettings() {
        Targets.Target t = selectedTarget();
        SharedPreferences.Editor e = settings().edit()
                .putInt(S_THREADS, selectedThreads())
                .putInt(S_PASSES, selectedPasses())
                .putBoolean(S_KEEP_SCREEN, swKeepScreen.isChecked());
        if (t != null) e.putString(S_TARGET, t.key);
        e.apply();
        updateOptionsSummary();
    }

    /** A fixed thread count, or JobStore.THREADS_AUTO. */
    private int selectedThreads() {
        int id = groupThreads.getCheckedRadioButtonId();
        if (id == R.id.rb_t2) return 2;
        if (id == R.id.rb_t4) return 4;
        if (id == R.id.rb_t8) return 8;
        if (id == R.id.rb_t16) return 16;
        if (id == R.id.rb_t32) return 32;
        return JobStore.THREADS_AUTO;
    }

    private int selectedPasses() {
        int id = groupPasses.getCheckedRadioButtonId();
        if (id == R.id.rb_p2) return 2;
        if (id == R.id.rb_p3) return 3;
        return 1;
    }

    private Targets.Target selectedTarget() {
        return targetByViewId.get(groupTarget.getCheckedRadioButtonId());
    }

    private void buildTargets() {
        Targets.Target current = selectedTarget();
        String wantKey = current != null ? current.key : settings().getString(S_TARGET, "internal");
        String wantDir = null;
        WipeConfig runningCfg = WipeService.runningConfig();
        if (runningCfg != null) wantDir = runningCfg.dir.getAbsolutePath();

        groupTarget.setOnCheckedChangeListener(null);
        groupTarget.removeAllViews();
        targetByViewId.clear();
        targets.clear();
        targets.addAll(Targets.list(this));
        int checkId = View.NO_ID;
        for (Targets.Target t : targets) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(t.label);
            int pad = Math.round(6 * getResources().getDisplayMetrics().density);
            rb.setPadding(rb.getPaddingLeft() + pad, pad, rb.getPaddingRight(), pad);
            groupTarget.addView(rb, new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
            targetByViewId.put(rb.getId(), t);
            boolean match = wantDir != null ? t.dir.getAbsolutePath().equals(wantDir) : t.key.equals(wantKey);
            if (match) checkId = rb.getId();
        }
        if (checkId == View.NO_ID && groupTarget.getChildCount() > 0) {
            checkId = groupTarget.getChildAt(0).getId();
        }
        if (checkId != View.NO_ID) groupTarget.check(checkId);
        groupTarget.setOnCheckedChangeListener((g, id) -> saveSettings());
        refreshTargetLabels();
    }

    /** Each target shows its name and, on a second smaller line, how much space is free. */
    private void refreshTargetLabels() {
        int secondary = getColor(R.color.text_secondary);
        for (int i = 0; i < groupTarget.getChildCount(); i++) {
            View child = groupTarget.getChildAt(i);
            Targets.Target t = targetByViewId.get(child.getId());
            if (t == null || !(child instanceof RadioButton)) continue;
            long[] sp = Targets.space(t.statDir());
            String info = getString(R.string.target_info, Fmt.bytes(sp[0]), Fmt.bytes(sp[1]));
            SpannableString text = new SpannableString(t.label + "\n" + info);
            int from = t.label.length() + 1;
            text.setSpan(new RelativeSizeSpan(0.85f), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(secondary), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            setText((RadioButton) child, text);
        }
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        Snapshot live = WipeService.snapshot();
        boolean running = live != null;
        Snapshot result = running ? null : WipeService.lastFinal();
        if (running && !wasRunning) {
            WipeConfig cfg = WipeService.runningConfig();
            if (cfg != null) applyChoices(JobStore.threadChoice(cfg), cfg.passes);
            buildTargets();
        }
        if (running != wasRunning) lastSlowRefresh = 0;
        wasRunning = running;

        boolean showStatus = running || result != null;
        setVisible(panelStatus, showStatus);
        setVisible(panelSetup, !showStatus);
        setVisible(txtIntro, !showStatus);
        btnStart.setEnabled(!deleting);
        if (running) {
            renderRunning(live);
        } else if (result != null) {
            renderResult(result);
        }
        applyKeepScreen(running);

        int v = WipeService.LOG.version();
        if (v != lastLogVersion) {
            lastLogVersion = v;
            String text = WipeService.LOG.text();
            txtLog.setText(text);
            setVisible(panelLog, !text.isEmpty());
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastSlowRefresh > 2000) {
            lastSlowRefresh = now;
            refreshSlow();
        }
    }

    /** Checks that touch the file system or system services: every 2 s is plenty. */
    private void refreshSlow() {
        boolean running = WipeService.isRunning();
        refreshWarnings();
        if (!running) refreshTargetLabels();
        updateLeftovers(running);
        updateLastResult();
    }

    private void renderRunning(Snapshot s) {
        int pm = s.permille();
        boolean indeterminate = pm < 0 || s.phase == Phase.PREPARING || s.phase == Phase.DELETING;
        if (indeterminate && !s.paused) {
            ring.setIndeterminate();
        } else {
            ring.setProgress(Math.max(0, pm) / 1000f);
        }
        ring.setMuted(s.paused);
        setText(txtPercent, pm < 0 ? "–" : String.format(Locale.US, "%.1f%%", pm / 10.0));

        setText(txtPhase, s.paused ? getString(R.string.phase_paused) : Notifs.phaseTitle(this, s));

        setTile(lblWritten, statWritten, R.string.stat_written, Fmt.bytes(s.passBytes));
        setTile(lblFree, statFree, R.string.stat_free, Fmt.bytes(s.freeBytes));
        setTile(lblSpeed, statSpeed, R.string.stat_speed, Fmt.speed(s.speedBps));
        setTile(lblEta, statEta, R.string.stat_eta, Fmt.duration(this, s.etaMs));

        // One line slot under the figures: why it is paused, or the secondary figures while it runs.
        String detail;
        if (s.paused) {
            detail = Notifs.pauseText(this, WipeService.pauseReason(), WipeService.pauseBatteryLevel());
        } else {
            String threads;
            if (!s.adaptive) threads = String.valueOf(s.threads);
            else threads = getString(s.tuning ? R.string.threads_tuning : R.string.threads_auto, s.threads);
            detail = getString(R.string.detail_line, Fmt.speed(s.avgBps), Fmt.duration(this, s.activeMs),
                    threads, s.filesInPass, s.errors);
            if (s.passes > 1) detail += "\n" + getString(R.string.stats_total, Fmt.bytes(s.totalBytes));
        }
        setText(txtDetail, detail);

        boolean stalled = s.stalledMs >= STALL_SHOW_MS;
        if (stalled) setText(txtStall, getString(R.string.stall_hint, Fmt.duration(this, s.stalledMs)));
        setVisible(txtStall, stalled);

        setText(btnPause, getString(s.paused ? R.string.btn_continue : R.string.btn_pause));
        setVisible(panelStats, true);
        setVisible(txtDetail, true);
        setVisible(txtResult, false);
        setVisible(panelRunButtons, true);
        setVisible(btnDismiss, false);
    }

    private void renderResult(Snapshot s) {
        boolean done = s.phase == Phase.DONE;
        ring.setMuted(!done);
        ring.setProgress(done ? 1f : 0f);
        setText(txtPercent, done ? "100%" : "–");
        setText(txtPhase, Notifs.phaseTitle(this, s));
        String text;
        if (done) {
            // The same four tiles as while running, now with the totals of the job.
            setTile(lblWritten, statWritten, R.string.res_overwritten, Fmt.bytes(s.totalBytes));
            setTile(lblFree, statFree, R.string.res_time, Fmt.duration(this, s.activeMs));
            setTile(lblSpeed, statSpeed, R.string.res_speed, Fmt.speed(s.avgBps));
            setTile(lblEta, statEta, R.string.res_threads, s.adaptive
                    ? getString(R.string.threads_auto, s.fillThreads) : String.valueOf(s.fillThreads));
            text = getString(R.string.result_done_note);
            if (s.unreachableBytes > 0) {
                text += "\n" + getString(R.string.result_unreachable, Fmt.bytes(s.unreachableBytes));
            }
        } else if (s.phase == Phase.CANCELLED) {
            text = getString(R.string.result_cancelled_text, Fmt.bytes(s.totalBytes));
        } else {
            text = getString(R.string.result_failed_text, String.valueOf(s.failure));
        }
        setText(txtResult, text);

        setVisible(panelStats, done);
        setVisible(txtDetail, false);
        setVisible(txtStall, false);
        setVisible(txtResult, true);
        setVisible(panelRunButtons, false);
        setVisible(btnDismiss, true);
    }

    private void updateLeftovers(boolean running) {
        if (running || deleting) {
            setVisible(panelLeftover, false);
            return;
        }
        boolean active = JobStore.isActive(this);
        boolean interrupted = JobStore.isResumable(this);
        String activeDir = JobStore.activeDir(this);
        Targets.Target t = selectedTarget();
        File dir = active && activeDir != null ? new File(activeDir) : (t != null ? t.dir : null);
        File[] left = dir == null ? new File[0] : WipeEngine.listFillFiles(dir);
        // Killed between passes: no files on disk, but the remaining passes still have to run.
        boolean pendingPasses = interrupted && JobStore.activePass(this) > 1;
        if (left.length == 0 && !pendingPasses) {
            if (active) JobStore.clearActive(this); // interrupted before anything was written
            setVisible(panelLeftover, false);
            leftoverDir = null;
            return;
        }
        leftoverDir = dir;
        long bytes = WipeEngine.sizeOf(left);
        String text = interrupted
                ? getString(R.string.leftover_interrupted, left.length, Fmt.bytes(bytes),
                JobStore.activePass(this), JobStore.activePasses(this))
                : getString(R.string.leftover_found, left.length, Fmt.bytes(bytes));
        if (interrupted) {
            if (!exitReasonLoaded) {
                // Asked once per visit: tells the user whether the system killed the app, and why.
                exitReasonLoaded = true;
                exitReason = ExitReasons.describeLast(this, JobStore.activeStarted(this));
            }
            if (exitReason != null) text += "\n\n" + exitReason;
        }
        setText(txtLeftover, text);
        setVisible(panelLeftover, true);
    }

    private void updateLastResult() {
        SharedPreferences p = JobStore.lastResult(this);
        String r = p.getString(JobStore.K_LAST_RESULT, null);
        if (r == null) {
            setVisible(txtLastResult, false);
            return;
        }
        int label;
        if (Phase.DONE.name().equals(r)) label = R.string.phase_done;
        else if (Phase.CANCELLED.name().equals(r)) label = R.string.phase_cancelled;
        else label = R.string.phase_failed;
        Date at = new Date(p.getLong(JobStore.K_LAST_TIME, 0));
        String when = DateFormat.getDateFormat(this).format(at) + " " + DateFormat.getTimeFormat(this).format(at);
        setText(txtLastResult, getString(R.string.last_result, getString(label),
                Fmt.bytes(p.getLong(JobStore.K_LAST_TOTAL, 0)), when));
        setVisible(txtLastResult, true);
    }

    /** Only what needs attention is shown; with everything in order the card is hidden. */
    private void refreshWarnings() {
        Battery battery = Battery.read(this);
        boolean charging = battery.plugged;
        if (!charging) {
            setText(warnPower, getString(R.string.warn_power, battery.level < 0 ? "?" : String.valueOf(battery.level)));
        }
        boolean background = ignoringBatteryOptimizations();
        boolean notifications = notificationsOk();
        setVisible(warnPower, !charging);
        setVisible(warnBattery, !background);
        setVisible(warnNotif, !notifications);
        setVisible(panelWarn, !charging || !background || !notifications);
    }

    private void applyKeepScreen(boolean running) {
        boolean keep = running && swKeepScreen.isChecked();
        if (keep == keepingScreenOn) return; // called on every poll: only touch the window on a change
        keepingScreenOn = keep;
        if (keep) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Sets the text only when it differs: an unchanged screen stays quiet for accessibility tools. */
    private static void setText(TextView view, CharSequence text) {
        if (!text.toString().contentEquals(view.getText())) view.setText(text);
    }

    private void setTile(TextView label, TextView value, int labelRes, String text) {
        setText(label, getString(labelRes));
        setText(value, text);
    }

    private static void setVisible(View v, boolean visible) {
        int want = visible ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != want) v.setVisibility(want);
    }

    // ------------------------------------------------------------------ start

    private void onStartClicked() {
        if (WipeService.isRunning() || deleting) return;
        pendingStart = true;
        continueStart();
    }

    /**
     * Start is a short chain: notification permission (Android 13+), background permission, then
     * the confirmation. Each system dialog comes back here; a "no" does not block the wipe.
     */
    private void continueStart() {
        if (!pendingStart) return;
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifAsked = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        if (!batteryAsked && !ignoringBatteryOptimizations()) {
            batteryAsked = true;
            if (requestBatteryExemption()) return;
        }
        pendingStart = false;
        showStartConfirm();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIF) return;
        refreshWarnings();
        continueStart();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_BATTERY) return;
        refreshWarnings();
        continueStart();
    }

    /** Opens the system dialog "let this app always run in the background". False if it could not be shown. */
    private boolean requestBatteryExemption() {
        if (ignoringBatteryOptimizations()) return false;
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(i, REQ_BATTERY);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void openBatterySettings() {
        if (safeStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return;
        safeStart(appDetailsIntent());
    }

    private void showStartConfirm() {
        Targets.Target t = selectedTarget();
        if (t == null) return;
        String activeDir = JobStore.activeDir(this);
        if (JobStore.isActive(this) && activeDir != null && !activeDir.equals(t.dir.getAbsolutePath())) {
            File[] other = WipeEngine.listFillFiles(new File(activeDir));
            if (other.length > 0) {
                // Starting elsewhere would orphan these files (and they may be filling that storage).
                Targets.Target ot = Targets.findByDir(targets, activeDir);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.confirm_title)
                        .setMessage(getString(R.string.pending_other_target, ot != null ? ot.label : activeDir,
                                Fmt.bytes(WipeEngine.sizeOf(other))))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
        }
        long[] sp = Targets.space(t.statDir());
        WipeMode mode = WipeMode.RANDOM;
        int threads = selectedThreads();
        int passes = selectedPasses();
        String threadText = threads == JobStore.THREADS_AUTO
                ? getString(R.string.threads_auto_range, device.startThreads, device.maxThreads)
                : String.valueOf(device.clampManual(threads));
        StringBuilder msg = new StringBuilder(getString(R.string.confirm_body, t.label, Fmt.bytes(sp[0]),
                threadText, passes));
        File[] left = WipeEngine.listFillFiles(t.dir);
        if (left.length > 0) {
            msg.append("\n\n").append(getString(R.string.confirm_leftovers, left.length, Fmt.bytes(WipeEngine.sizeOf(left))));
        }
        if (!isCharging()) msg.append("\n\n").append(getString(R.string.warn_not_charging));
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_title)
                .setMessage(msg)
                .setPositiveButton(R.string.btn_start, (d, w) -> startWipe(t.dir, mode, threads, passes, false, 1))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startWipe(File dir, WipeMode mode, int threadChoice, int passes, boolean resume, int startPass) {
        if (WipeService.isRunning()) return;
        Intent i = new Intent(this, WipeService.class)
                .setAction(WipeService.ACTION_START)
                .putExtra(WipeService.EXTRA_DIR, dir.getAbsolutePath())
                .putExtra(WipeService.EXTRA_MODE, mode.name())
                .putExtra(WipeService.EXTRA_THREADS, threadChoice)
                .putExtra(WipeService.EXTRA_PASSES, passes)
                .putExtra(WipeService.EXTRA_RESUME, resume)
                .putExtra(WipeService.EXTRA_START_PASS, startPass);
        if (!resume) WipeService.LOG.clear();
        WipeService.clearFinal();
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        saveSettings();
        handler.postDelayed(this::refresh, 300);
    }

    // ------------------------------------------------------------------ running

    private void togglePause() {
        Snapshot s = WipeService.snapshot();
        if (s == null) return;
        String action = s.paused ? WipeService.ACTION_CONTINUE : WipeService.ACTION_PAUSE;
        startService(new Intent(this, WipeService.class).setAction(action));
        handler.postDelayed(this::refresh, 150);
    }

    private void confirmStop() {
        if (!WipeService.isRunning()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.stop_title)
                .setMessage(R.string.stop_body)
                .setPositiveButton(R.string.btn_stop_confirm, (d, w) ->
                        startService(new Intent(this, WipeService.class).setAction(WipeService.ACTION_STOP)))
                .setNegativeButton(R.string.btn_keep_going, null)
                .show();
    }

    // ------------------------------------------------------------------ leftovers

    /** Continue an interrupted job: the existing fill files count as already written. */
    private void resumeLeftovers() {
        if (leftoverDir == null || WipeService.isRunning()) return;
        WipeConfig cfg = JobStore.loadResume(this);
        if (cfg != null && JobStore.isResumable(this)
                && cfg.dir.getAbsolutePath().equals(leftoverDir.getAbsolutePath())) {
            startWipe(cfg.dir, cfg.mode, JobStore.threadChoice(cfg), cfg.passes, true, cfg.startPass);
        } else {
            startWipe(leftoverDir, WipeMode.RANDOM, selectedThreads(), selectedPasses(), true, 1);
        }
    }

    private void confirmDeleteLeftovers() {
        final File dir = leftoverDir;
        if (dir == null || WipeService.isRunning()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(R.string.delete_body)
                .setPositiveButton(R.string.btn_delete, (d, w) -> deleteLeftovers(dir))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteLeftovers(File dir) {
        deleting = true;
        setVisible(panelLeftover, false);
        new Thread(() -> {
            // Mark the job finished first, so a concurrent service restart cannot resume into this folder.
            JobStore.clearActive(this);
            File[] left = WipeEngine.listFillFiles(dir);
            long bytes = WipeEngine.sizeOf(left);
            int n = WipeEngine.deleteFiles(left);
            runOnUiThread(() -> {
                deleting = false;
                String line = getString(R.string.log_deleted, n, Fmt.bytes(bytes));
                WipeService.LOG.add(line);
                Toast.makeText(this, line, Toast.LENGTH_SHORT).show();
                lastSlowRefresh = 0;
                refresh();
            });
        }, "delete-leftovers").start();
    }

    // ------------------------------------------------------------------ system state

    private void fixNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifAsked = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        Intent i;
        if (Build.VERSION.SDK_INT >= 26) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            i = appDetailsIntent();
        }
        safeStart(i);
    }

    private Intent appDetailsIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
    }

    private boolean safeStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private boolean notificationsOk() {
        return Notifs.progressVisible(this) && (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    private boolean ignoringBatteryOptimizations() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean isCharging() {
        return Battery.read(this).plugged;
    }

    // ------------------------------------------------------------------ header actions

    private void copyLog() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("log", WipeService.LOG.text()));
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showLanguageMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenuInflater().inflate(R.menu.main, menu.getMenu());
        String lang = LocaleHelper.get(this);
        int current = LocaleHelper.ZH.equals(lang) ? R.id.menu_lang_zh
                : LocaleHelper.EN.equals(lang) ? R.id.menu_lang_en : R.id.menu_lang_system;
        MenuItem item = menu.getMenu().findItem(current);
        if (item != null) item.setChecked(true);
        menu.setOnMenuItemClickListener(picked -> {
            int id = picked.getItemId();
            String chosen = id == R.id.menu_lang_zh ? LocaleHelper.ZH
                    : id == R.id.menu_lang_en ? LocaleHelper.EN : LocaleHelper.SYSTEM;
            if (!chosen.equals(LocaleHelper.get(this))) {
                LocaleHelper.set(this, chosen);
                recreate();
            }
            return true;
        });
        menu.show();
    }
}
