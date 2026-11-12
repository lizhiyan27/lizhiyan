package com.dafeiyu.locationshadow;

import java.util.Locale;
import java.util.Random;

public final class GeoUtils {
    private static final double PI = 3.1415926535897932384626;
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;
    private static final double X_PI = PI * 3000.0 / 180.0;

    private GeoUtils() {}

    public static double[] bd09ToWgs84(double lng, double lat) {
        double x = lng - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        double gcjLng = z * Math.cos(theta);
        double gcjLat = z * Math.sin(theta);
        return gcj02ToWgs84(gcjLng, gcjLat);
    }

    public static double[] wgs84ToBd09(double lng, double lat) {
        double[] gcj = wgs84ToGcj02(lng, lat);
        double z = Math.sqrt(gcj[0] * gcj[0] + gcj[1] * gcj[1]) + 0.00002 * Math.sin(gcj[1] * X_PI);
        double theta = Math.atan2(gcj[1], gcj[0]) + 0.000003 * Math.cos(gcj[0] * X_PI);
        return new double[]{z * Math.cos(theta) + 0.0065, z * Math.sin(theta) + 0.006};
    }

    public static double[] gcj02ToWgs84(double lng, double lat) {
        if (outOfChina(lng, lat)) return new double[]{lng, lat};
        double[] delta = delta(lng, lat);
        return new double[]{lng * 2 - (lng + delta[0]), lat * 2 - (lat + delta[1])};
    }

    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) return new double[]{lng, lat};
        double[] delta = delta(lng, lat);
        return new double[]{lng + delta[0], lat + delta[1]};
    }

    private static boolean outOfChina(double lng, double lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double[] delta(double lng, double lat) {
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{dLng, dLat};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    public static double[] randomOffset(double lng, double lat, double maxLngMeters, double maxLatMeters, Random random) {
        if (maxLngMeters == 0.0 && maxLatMeters == 0.0) return new double[]{lng, lat};
        double dx = (random.nextDouble() * 2 - 1) * maxLngMeters;
        double dy = (random.nextDouble() * 2 - 1) * maxLatMeters;
        return move(lng, lat, dx, dy, 1).position;
    }

    public static Movement move(double lng, double lat, double distanceEastMeters, double distanceNorthMeters, double elapsedSeconds) {
        double nextLng = lng + distanceEastMeters * elapsedSeconds / (111320.0 * Math.max(0.01, Math.cos(Math.abs(lat) * Math.PI / 180.0)));
        double nextLat = lat + distanceNorthMeters * elapsedSeconds / 110574.0;
        double wrappedLng = ((nextLng + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return new Movement(wrappedLng, clamp(nextLat, -90, 90));
    }

    public static String format(double value) {
        return String.format(Locale.US, "%.7f", value);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Movement {
        public final double[] position;
        private Movement(double lng, double lat) { this.position = new double[]{lng, lat}; }
    }
}
