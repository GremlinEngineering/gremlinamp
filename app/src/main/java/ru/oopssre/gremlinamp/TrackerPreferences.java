package ru.oopssre.gremlinamp;

import android.content.SharedPreferences;
import java.util.Locale;

final class TrackerPreferences {
    private TrackerPreferences() {}

    static String key(SpaceTracker.Mode mode, String option) {
        return "tracker." + mode.name().toLowerCase(Locale.ROOT) + "." + option;
    }

    static boolean motion(SharedPreferences prefs, SpaceTracker.Mode mode) {
        return prefs.getBoolean(key(mode, "motion"), prefs.getBoolean("space_motion", true));
    }

    static boolean noise(SharedPreferences prefs, SpaceTracker.Mode mode) {
        boolean common = prefs.getBoolean("space_effects", false);
        boolean previous = mode == SpaceTracker.Mode.VOYAGER
                ? prefs.getBoolean("voyager_effects", common) : common;
        return prefs.getBoolean(key(mode, "noise"), previous);
    }

    static boolean plasma(SharedPreferences prefs) {
        return prefs.getBoolean(key(SpaceTracker.Mode.VOYAGER, "plasma"), prefs.getBoolean("space_plasma", true));
    }

    static boolean refresh(SharedPreferences prefs, SpaceTracker.Mode mode) {
        return prefs.getBoolean(key(mode, "refresh"), true);
    }
}
