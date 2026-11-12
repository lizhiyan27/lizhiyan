package com.dafeiyu.locationshadow;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.ArrayList;

/** Foreground mock-location engine. All service callbacks and ticks run on the main thread. */
public final class MockLocationService extends Service {
    private static final String ACTION_START = "com.dafeiyu.locationshadow.action.START";
    private static final String ACTION_UPDATE = "com.dafeiyu.locationshadow.action.UPDATE";
    private static final String ACTION_SETTINGS = "com.dafeiyu.locationshadow.action.SETTINGS";
    private static final String ACTION_SHOW_JOYSTICK = "com.dafeiyu.locationshadow.action.ShowJoyStick";
    private static final String ACTION_HIDE_JOYSTICK = "com.dafeiyu.locationshadow.action.HideJoyStick";
    private static final String ACTION_STOP = "com.dafeiyu.locationshadow.action.STOP";

    private static final String EXTRA_LAT = "lat";
    private static final String EXTRA_LNG = "lng";
    private static final String EXTRA_ALT = "alt";
    private static final String EXTRA_EAST = "east_mps";
    private static final String EXTRA_NORTH = "north_mps";

    private static final long TICK_MILLIS = 100L;
    private static final double TICK_SECONDS = TICK_MILLIS / 1000.0d;
    private static final long NOTIFICATION_INTERVAL_MILLIS = 1000L;
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "mock_location";

    private static volatile boolean running;
    private static volatile double lastReportedLat = Double.NaN;
    private static volatile double lastReportedLng = Double.NaN;
    private static volatile long lastReportElapsed;
    private static volatile String lastProviderSummary = "尚未上报";
    private static volatile String lastError = "无";

    private Handler handler;
    private LocationManager mLocManager;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private JoystickWindow joystickWindow;
    private final ArrayList<String> activeProviders = new ArrayList<>();

    private boolean destroyed;
    private double currentLat;
    private double currentLng;
    private double altitude = 55.0d;
    private double eastMetersPerSecond;
    private double northMetersPerSecond;
    private float gpsBearing;
    private float gpsSpeed;
    private long lastNotificationElapsed;
    private double lastNotifiedLat = Double.NaN;
    private double lastNotifiedLng = Double.NaN;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            if (running) {
                tick();
            }
            handler.postDelayed(this, TICK_MILLIS);
        }
    };

    public static void start(Context context, double lat, double lng, double alt) {
        Intent intent = new Intent(context, MockLocationService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
                .putExtra(EXTRA_ALT, alt);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, MockLocationService.class).setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
            // A background caller may not start a stopped service on Android O+.
            context.stopService(new Intent(context, MockLocationService.class));
        }
    }

    public static void updateJoystick(Context context, double eastMetersPerSecond,
                                      double northMetersPerSecond) {
        if (!running) {
            return;
        }
        Intent intent = new Intent(context, MockLocationService.class)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_EAST, eastMetersPerSecond)
                .putExtra(EXTRA_NORTH, northMetersPerSecond);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
            // The service may have stopped between the running check and this call.
        }
    }

    public static void updateSettings(Context context) {
        if (!running) {
            return;
        }
        try {
            context.startService(new Intent(context, MockLocationService.class)
                    .setAction(ACTION_SETTINGS));
        } catch (IllegalStateException ignored) {
            // The service may have stopped between the running check and this call.
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static double getLastReportedLat() { return lastReportedLat; }
    public static double getLastReportedLng() { return lastReportedLng; }
    public static long getLastReportElapsed() { return lastReportElapsed; }
    public static String getLastProviderSummary() { return lastProviderSummary; }
    public static String getLastError() { return lastError; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = false;
        destroyed = false;
        mLocManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(tickRunnable, TICK_MILLIS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        switch (intent.getAction()) {
            case ACTION_START:
                handleStart(intent);
                break;
            case ACTION_UPDATE:
                if (running) {
                    double east = intent.getDoubleExtra(EXTRA_EAST, 0.0d);
                    double north = intent.getDoubleExtra(EXTRA_NORTH, 0.0d);
                    if (Double.isFinite(east) && Double.isFinite(north)) {
                        setJoystickVector(east, north);
                    }
                }
                break;
            case ACTION_SETTINGS:
                if (running) {
                    applySettings();
                }
                break;
            case ACTION_SHOW_JOYSTICK:
                if (running) {
                    ensureJoystickWindow();
                    joystickWindow.show();
                }
                break;
            case ACTION_HIDE_JOYSTICK:
                if (running && joystickWindow != null) {
                    joystickWindow.hide();
                }
                break;
            case ACTION_STOP:
                stopSimulation(true);
                stopSelf();
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    private void handleStart(Intent intent) {
        // A second START is a clean restart, not a duplicate provider.
        stopSimulation(false);

        SharedPreferences prefs = Prefs.get(this);
        // MainActivity applies the optional random offset only to history selections.
        currentLat = intent.getDoubleExtra(EXTRA_LAT, 0.0d);
        currentLng = intent.getDoubleExtra(EXTRA_LNG, 0.0d);
        altitude = intent.getDoubleExtra(EXTRA_ALT,
                Prefs.getDouble(prefs, Prefs.ALTITUDE, 55.0d));
        double walkSpeed = Prefs.getDouble(prefs, Prefs.WALK_SPEED, 1.2d);
        gpsSpeed = (float) (Double.isFinite(walkSpeed) ? Math.max(0.0d, walkSpeed) : 1.2d);
        gpsBearing = 0.0f;
        prefs.edit().putString(Prefs.ALTITUDE, String.valueOf(altitude)).apply();

        prefs.edit()
                .putString(Prefs.LAST_LAT, String.valueOf(currentLat))
                .putString(Prefs.LAST_LNG, String.valueOf(currentLng))
                .apply();

        acquireWakeLock();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException exception) {
            showToast("前台定位服务启动失败：请授予定位权限，并检查系统后台限制");
            stopSimulation(true);
            stopSelf();
            return;
        }

        activeProviders.clear();
        lastError = "无";
        try {
            removeTestProviders();
            addTestProviderOrThrow(LocationManager.GPS_PROVIDER);
            activeProviders.add(LocationManager.GPS_PROVIDER);
            addTestProviderOrThrow(LocationManager.NETWORK_PROVIDER);
            activeProviders.add(LocationManager.NETWORK_PROVIDER);
            if (Prefs.get(this).getBoolean(Prefs.COMPATIBILITY_MODE, true)) {
                addOptionalProvider(LocationManager.PASSIVE_PROVIDER);
                addOptionalProvider(LocationManager.FUSED_PROVIDER);
            }
        } catch (SecurityException exception) {
            lastError = "模拟位置应用或定位权限未授权";
            showToast("模拟定位权限失败：请在 设置 → 开发者选项 → 选择模拟位置信息应用 中选择本应用，并授予定位权限");
            stopSimulation(true);
            stopSelf();
            return;
        } catch (RuntimeException exception) {
            lastError = "GPS/NETWORK Provider 创建失败：" + exception.getClass().getSimpleName();
            showToast("GPS/NETWORK 模拟 Provider 创建失败：请检查 设置 → 开发者选项 → 选择模拟位置信息应用");
            stopSimulation(true);
            stopSelf();
            return;
        }

        running = true;
        lastNotificationElapsed = SystemClock.elapsedRealtime();
        lastNotifiedLat = currentLat;
        lastNotifiedLng = currentLng;
        if (!publishLocations()) {
            return;
        }
        applySettings();
    }

    private void tick() {
        GeoUtils.Movement movement = GeoUtils.move(currentLng, currentLat,
                eastMetersPerSecond, northMetersPerSecond, TICK_SECONDS);
        currentLng = movement.position[0];
        currentLat = movement.position[1];

        double distanceMeters = Math.hypot(eastMetersPerSecond, northMetersPerSecond)
                * TICK_SECONDS;
        if (distanceMeters > 0.0d) {
            gpsSpeed = (float) (distanceMeters / TICK_SECONDS);
            gpsBearing = (float) normalizeBearing(Math.toDegrees(
                    Math.atan2(eastMetersPerSecond, northMetersPerSecond)));
        }

        if (!publishLocations()) {
            return;
        }
        maybeUpdateNotification();
    }

    /** Publishes independent locations to GPS/NETWORK and any supported compatibility providers. */
    private boolean publishLocations() {
        long time = System.currentTimeMillis();
        long elapsedNanos = SystemClock.elapsedRealtimeNanos();
        int requiredPublished = 0;
        try {
            for (int index = activeProviders.size() - 1; index >= 0; index--) {
                String provider = activeProviders.get(index);
                boolean required = LocationManager.GPS_PROVIDER.equals(provider)
                        || LocationManager.NETWORK_PROVIDER.equals(provider);
                try {
                    float accuracy = LocationManager.GPS_PROVIDER.equals(provider)
                            || LocationManager.FUSED_PROVIDER.equals(provider) ? 1.0f : 2.0f;
                    Location location = createLocation(provider, accuracy, time, elapsedNanos);
                    if (LocationManager.GPS_PROVIDER.equals(provider)) {
                        Bundle gpsExtras = new Bundle();
                        gpsExtras.putInt("satellites", 7);
                        location.setExtras(gpsExtras);
                    }
                    mLocManager.setTestProviderLocation(provider, location);
                    if (required) requiredPublished++;
                } catch (RuntimeException exception) {
                    if (required) throw exception;
                    lastError = provider + " 上报失败，已停用该兼容 Provider";
                    activeProviders.remove(index);
                }
            }
            if (requiredPublished != 2) {
                throw new IllegalStateException("GPS/NETWORK 未同时上报成功");
            }
            lastReportedLat = currentLat;
            lastReportedLng = currentLng;
            lastReportElapsed = SystemClock.elapsedRealtime();
            lastProviderSummary = activeProviders.toString();
            return true;
        } catch (SecurityException exception) {
            running = false;
            lastError = "模拟位置权限已失效";
            showToast("模拟定位权限已失效：请在 设置 → 开发者选项 → 选择模拟位置信息应用 中重新选择本应用");
        } catch (RuntimeException exception) {
            running = false;
            lastError = "GPS/NETWORK 位置上报失败：" + exception.getClass().getSimpleName();
            showToast("GPS/NETWORK 位置上报失败：请检查开发者选项中的模拟位置信息应用设置");
        }
        stopSimulation(true);
        stopSelf();
        return false;
    }

    private Location createLocation(String provider, float accuracy,
                                    long time, long elapsedNanos) {
        Location location = new Location(provider);
        location.setAccuracy(accuracy);
        location.setLatitude(currentLat);
        location.setLongitude(currentLng);
        location.setTime(time);
        location.setAltitude(altitude);
        location.setBearing(gpsBearing);
        location.setSpeed(gpsSpeed);
        location.setElapsedRealtimeNanos(elapsedNanos);
        location.setVerticalAccuracyMeters(2.0f);
        location.setSpeedAccuracyMetersPerSecond(0.2f);
        location.setBearingAccuracyDegrees(10.0f);
        return location;
    }

    private static double normalizeBearing(double bearing) {
        double normalized = bearing % 360.0d;
        return normalized < 0.0d ? normalized + 360.0d : normalized;
    }

    private void addOptionalProvider(String provider) {
        try {
            if (!mLocManager.getAllProviders().contains(provider)) {
                lastError = "系统不支持 " + provider + " 测试 Provider，已回退 GPS/NETWORK";
                return;
            }
            addTestProviderOrThrow(provider);
            activeProviders.add(provider);
        } catch (RuntimeException exception) {
            lastError = provider + " 兼容模式不可用，已回退 GPS/NETWORK";
        }
    }

    @SuppressWarnings("deprecation")
    private void addTestProviderOrThrow(String provider) {
        final int powerHigh;
        final int powerLow;
        final int accuracyFine;
        final int accuracyCoarse;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            powerHigh = ProviderProperties.POWER_USAGE_HIGH;
            powerLow = ProviderProperties.POWER_USAGE_LOW;
            accuracyFine = ProviderProperties.ACCURACY_FINE;
            accuracyCoarse = ProviderProperties.ACCURACY_COARSE;
        } else {
            powerHigh = Criteria.POWER_HIGH;
            powerLow = Criteria.POWER_LOW;
            accuracyFine = Criteria.ACCURACY_FINE;
            accuracyCoarse = Criteria.ACCURACY_COARSE;
        }

        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            mLocManager.addTestProvider(provider, false, true, false, false,
                    true, true, true, powerHigh, accuracyFine);
        } else if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            mLocManager.addTestProvider(provider, true, false, true, true,
                    true, true, true, powerLow, accuracyCoarse);
        } else {
            mLocManager.addTestProvider(provider, false, false, false, false,
                    true, true, true, powerLow, accuracyFine);
        }
        // Some ROMs report the real provider as enabled while its test provider is still disabled.
        mLocManager.setTestProviderEnabled(provider, true);
    }

    private void setJoystickVector(double east, double north) {
        eastMetersPerSecond = east;
        northMetersPerSecond = north;
    }

    private void applySettings() {
        SharedPreferences prefs = Prefs.get(this);
        altitude = Prefs.getDouble(prefs, Prefs.ALTITUDE, altitude);
        ensureJoystickWindow();
        joystickWindow.updateSettings();
    }

    private void ensureJoystickWindow() {
        if (joystickWindow == null) {
            joystickWindow = new JoystickWindow(this,
                    new JoystickWindow.Listener() {
                        @Override
                        public void onJoystickChanged(double east, double north) {
                            setJoystickVector(east, north);
                        }
                    });
        }
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        try {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "locationshadow:mock-location");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (RuntimeException exception) {
            wakeLock = null;
            showToast("无法持有部分唤醒锁：请检查系统省电与后台限制");
        }
    }

    private void createNotificationChannel() {
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "模拟定位", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, MockLocationService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(this, MockLocationService.class)
                .setAction(ACTION_SHOW_JOYSTICK);
        PendingIntent showPendingIntent = PendingIntent.getService(this, 2, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(this, MockLocationService.class)
                .setAction(ACTION_HIDE_JOYSTICK);
        PendingIntent hidePendingIntent = PendingIntent.getService(this, 3, hideIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("影梭模拟运行中")
                .setContentText(coordinateText())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPendingIntent)
                .addAction(new Notification.Action.Builder(0, "显示摇杆", showPendingIntent).build())
                .addAction(new Notification.Action.Builder(0, "隐藏摇杆", hidePendingIntent).build())
                .addAction(new Notification.Action.Builder(0, "停止模拟", stopPendingIntent).build())
                .build();
    }

    private String coordinateText() {
        return "纬度 " + GeoUtils.format(currentLat) + "，经度 " + GeoUtils.format(currentLng);
    }

    private void maybeUpdateNotification() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationElapsed < NOTIFICATION_INTERVAL_MILLIS) {
            return;
        }
        lastNotificationElapsed = now;
        if (Double.compare(lastNotifiedLat, currentLat) == 0
                && Double.compare(lastNotifiedLng, currentLng) == 0) {
            return;
        }
        lastNotifiedLat = currentLat;
        lastNotifiedLng = currentLng;
        if (notificationManager == null || Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException ignored) {
            // Notification permission may be revoked while the foreground service is running.
        }
    }

    private void removeTestProviders() {
        activeProviders.clear();
        if (mLocManager == null) return;
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER, LocationManager.FUSED_PROVIDER};
        for (String provider : providers) {
            try {
                mLocManager.removeTestProvider(provider);
            } catch (RuntimeException ignored) {
                // The provider may not exist or mock-location access was revoked.
            }
        }
    }

    private void stopSimulation(boolean stopForeground) {
        running = false;
        lastProviderSummary = "已停止";
        eastMetersPerSecond = 0.0d;
        northMetersPerSecond = 0.0d;
        gpsSpeed = 0.0f;
        if (joystickWindow != null) {
            joystickWindow.release();
            joystickWindow = null;
        }
        removeTestProviders();
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (RuntimeException ignored) {
                // Already released by the system.
            }
            wakeLock = null;
        }
        if (stopForeground) {
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        stopSimulation(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showToast(final String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MockLocationService.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
