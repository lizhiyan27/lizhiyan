package com.dafeiyu.locationshadow;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** SharedPreferences-backed location history. All coordinates stored here are WGS84. */
public final class LocationStore {
    private static final String FILE = "location_shadow_history";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_SEARCHES = "recent_searches";
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_SEARCHES = 20;
    private static final double SAME_COORDINATE = 0.0000005;
    private static final Object LOCK = new Object();

    private LocationStore() {}

    public static final class Entry {
        public String name;
        public double lat;
        public double lng;
        public double altitude;
        public long lastUsed;
        public int useCount;
        public boolean favorite;

        private Entry(String name, double lat, double lng, double altitude,
                      long lastUsed, int useCount, boolean favorite) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.altitude = altitude;
            this.lastUsed = lastUsed;
            this.useCount = useCount;
            this.favorite = favorite;
        }

        public String coordinateText() {
            return String.format(Locale.US, "WGS84  %.7f, %.7f", lat, lng);
        }

        private JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("name", name);
            object.put("lat", lat);
            object.put("lng", lng);
            object.put("altitude", altitude);
            object.put("lastUsed", lastUsed);
            object.put("useCount", useCount);
            object.put("favorite", favorite);
            return object;
        }

        private static Entry fromJson(JSONObject object) {
            double lat = object.optDouble("lat", Double.NaN);
            double lng = object.optDouble("lng", Double.NaN);
            double altitude = object.optDouble("altitude", 55.0);
            long lastUsed = object.optLong("lastUsed", 0L);
            if (!valid(lat, lng) || !Double.isFinite(altitude) || lastUsed <= 0L) {
                return null;
            }
            String name = object.optString("name", "未命名位置").trim();
            if (name.length() == 0) name = "未命名位置";
            int useCount = Math.max(1, object.optInt("useCount", 1));
            return new Entry(name, lat, lng, altitude, lastUsed, useCount,
                    object.optBoolean("favorite", false));
        }
    }

    /** Records a use, merging coordinates equal at roughly millimetre precision. */
    public static void record(Context context, String name, double lat, double lng, double altitude) {
        if (!valid(lat, lng) || !Double.isFinite(altitude)) return;
        synchronized (LOCK) {
            List<Entry> entries = readLocked(context);
            long now = System.currentTimeMillis();
            Entry match = null;
            for (Entry entry : entries) {
                if (Math.abs(entry.lat - lat) <= SAME_COORDINATE
                        && Math.abs(entry.lng - lng) <= SAME_COORDINATE) {
                    match = entry;
                    break;
                }
            }
            String cleanName = name == null ? "" : name.trim();
            if (match == null) {
                match = new Entry(cleanName.length() == 0 ? "地图选点" : cleanName,
                        lat, lng, altitude, now, 1, false);
                entries.add(match);
            } else {
                match.lat = lat;
                match.lng = lng;
                match.altitude = altitude;
                match.lastUsed = now;
                match.useCount = Math.min(Integer.MAX_VALUE - 1, match.useCount) + 1;
                if (cleanName.length() > 0) match.name = cleanName;
            }
            writeLocked(context, entries);
        }
    }

    public static List<Entry> getAll(Context context) {
        synchronized (LOCK) {
            List<Entry> entries = readLocked(context);
            sort(entries);
            return new ArrayList<>(entries);
        }
    }

    public static boolean setFavorite(Context context, Entry target, boolean favorite) {
        synchronized (LOCK) {
            List<Entry> entries = readLocked(context);
            for (Entry entry : entries) {
                if (sameIdentity(entry, target)) {
                    entry.favorite = favorite;
                    writeLocked(context, entries);
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean delete(Context context, Entry target) {
        synchronized (LOCK) {
            List<Entry> entries = readLocked(context);
            for (int i = 0; i < entries.size(); i++) {
                if (sameIdentity(entries.get(i), target)) {
                    entries.remove(i);
                    writeLocked(context, entries);
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean rename(Context context, Entry target, String newName) {
        String cleanName = newName == null ? "" : newName.trim();
        if (cleanName.length() == 0) return false;
        if (cleanName.length() > 120) cleanName = cleanName.substring(0, 120);
        synchronized (LOCK) {
            List<Entry> entries = readLocked(context);
            for (Entry entry : entries) {
                if (sameIdentity(entry, target)) {
                    entry.name = cleanName;
                    writeLocked(context, entries);
                    return true;
                }
            }
            return false;
        }
    }

    public static void clearAll(Context context) {
        synchronized (LOCK) {
            context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().remove(KEY_ENTRIES).apply();
        }
    }

    /** Search terms are intentionally separate from location history. */
    public static void recordSearch(Context context, String query) {
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.length() == 0) return;
        if (cleanQuery.length() > 120) cleanQuery = cleanQuery.substring(0, 120);
        synchronized (LOCK) {
            List<String> searches = getRecentSearchesLocked(context);
            for (int i = searches.size() - 1; i >= 0; i--) {
                if (searches.get(i).equalsIgnoreCase(cleanQuery)) searches.remove(i);
            }
            searches.add(0, cleanQuery);
            while (searches.size() > MAX_SEARCHES) searches.remove(searches.size() - 1);
            writeSearchesLocked(context, searches);
        }
    }

    public static List<String> getRecentSearches(Context context) {
        synchronized (LOCK) {
            return new ArrayList<>(getRecentSearchesLocked(context));
        }
    }

    public static void clearSearchHistory(Context context) {
        synchronized (LOCK) {
            context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().remove(KEY_SEARCHES).apply();
        }
    }

    private static List<String> getRecentSearchesLocked(Context context) {
        List<String> searches = new ArrayList<>();
        String json = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY_SEARCHES, "[]");
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < array.length() && searches.size() < MAX_SEARCHES; i++) {
                String query = array.optString(i, "").trim();
                if (query.length() == 0) continue;
                boolean duplicate = false;
                for (String existing : searches) {
                    if (existing.equalsIgnoreCase(query)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) searches.add(query);
            }
        } catch (Exception ignored) {
            searches.clear();
        }
        return searches;
    }

    private static void writeSearchesLocked(Context context, List<String> searches) {
        JSONArray array = new JSONArray();
        for (String query : searches) array.put(query);
        context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_SEARCHES, array.toString()).apply();
    }

    /** Removes entries older than the configured retention period, including old favourites. */
    public static void cleanupExpired(Context context) {
        int days = Prefs.getInt(Prefs.get(context), Prefs.HISTORY_DAYS, 7);
        days = Math.max(1, days);
        long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        synchronized (LOCK) {
            List<Entry> entries = readLocked(context);
            boolean changed = false;
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).lastUsed < cutoff) {
                    entries.remove(i);
                    changed = true;
                }
            }
            if (changed) writeLocked(context, entries);
        }
    }

    private static List<Entry> readLocked(Context context) {
        List<Entry> entries = new ArrayList<>();
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ENTRIES, "[]");
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            int count = Math.min(array.length(), MAX_ENTRIES * 3);
            for (int i = 0; i < count; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Entry entry = Entry.fromJson(object);
                if (entry != null) entries.add(entry);
            }
        } catch (Exception ignored) {
            entries.clear();
        }
        sort(entries);
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        }
        return entries;
    }

    private static void writeLocked(Context context, List<Entry> entries) {
        sort(entries);
        JSONArray array = new JSONArray();
        int count = Math.min(entries.size(), MAX_ENTRIES);
        for (int i = 0; i < count; i++) {
            try {
                array.put(entries.get(i).toJson());
            } catch (JSONException ignored) {
                // Primitive values are sanitized before they reach this point.
            }
        }
        context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENTRIES, array.toString()).apply();
    }

    private static void sort(List<Entry> entries) {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry left, Entry right) {
                return Long.compare(right.lastUsed, left.lastUsed);
            }
        });
    }

    private static boolean sameIdentity(Entry entry, Entry target) {
        return entry.lastUsed == target.lastUsed
                && Math.abs(entry.lat - target.lat) <= SAME_COORDINATE
                && Math.abs(entry.lng - target.lng) <= SAME_COORDINATE;
    }

    private static boolean valid(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0;
    }
}
