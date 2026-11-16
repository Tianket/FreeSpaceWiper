package app.freespacewiper;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/** Battery level and power source, as the system itself reports them to every app. */
final class Battery {

    /** Percent, or -1 when unknown. */
    final int level;
    /** Connected to a charger (charging or already full). */
    final boolean plugged;

    private Battery(int level, boolean plugged) {
        this.level = level;
        this.plugged = plugged;
    }

    static Battery read(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return new Battery(-1, true); // unknown: do not act on it
        int raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int level = raw < 0 || scale <= 0 ? -1 : raw * 100 / scale;
        return new Battery(level, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
    }
}
