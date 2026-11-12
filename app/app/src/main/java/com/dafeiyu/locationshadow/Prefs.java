package com.dafeiyu.locationshadow;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String FILE = "location_shadow_settings";
    public static final String WALK_SPEED = "walk_speed";
    public static final String RUN_SPEED = "run_speed";
    public static final String BIKE_SPEED = "bike_speed";
    public static final String ALTITUDE = "altitude";
    public static final String RANDOM_OFFSET = "random_offset";
    public static final String MAX_LAT_OFFSET = "max_lat_offset";
    public static final String MAX_LNG_OFFSET = "max_lng_offset";
    public static final String HISTORY_DAYS = "history_days";
    public static final String SHOW_JOYSTICK = "show_joystick";
    public static final String JOYSTICK_TYPE = "setting_joystick_type";
    public static final String LAST_LAT = "last_lat";
    public static final String LAST_LNG = "last_lng";
    public static final String FIRST_RUN = "first_run";
    public static final String MAP_SOURCE = "map_source";
    public static final String MAP_SATELLITE = "map_satellite";
    public static final String MAP_SOURCE_V2 = "map_source_v2";
    public static final String COMPATIBILITY_MODE = "compatibility_mode";

    private Prefs() {}

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static double getDouble(SharedPreferences prefs, String key, double fallback) {
        try {
            return Double.parseDouble(prefs.getString(key, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static int getInt(SharedPreferences prefs, String key, int fallback) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
