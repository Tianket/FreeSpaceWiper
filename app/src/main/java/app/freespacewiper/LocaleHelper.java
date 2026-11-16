package app.freespacewiper;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** In-app language switch: follow system / 简体中文 / English. */
final class LocaleHelper {

    static final String SYSTEM = "system";
    static final String ZH = "zh";
    static final String EN = "en";

    private static final String PREFS = "settings";
    private static final String KEY = "lang";

    private LocaleHelper() {
    }

    static String get(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM);
    }

    static void set(Context c, String lang) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).commit();
    }

    /** Returns a context whose resources use the chosen language (or {@code base} for "system"). */
    static Context wrap(Context base) {
        String lang = get(base);
        if (SYSTEM.equals(lang)) return base;
        Locale locale = ZH.equals(lang) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        if (Build.VERSION.SDK_INT >= 24) {
            cfg.setLocales(new LocaleList(locale));
        }
        return base.createConfigurationContext(cfg);
    }
}
