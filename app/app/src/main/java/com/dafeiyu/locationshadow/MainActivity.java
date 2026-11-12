package com.dafeiyu.locationshadow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {
    private static final int REQUEST_SEARCH = 10;
    private static final int REQUEST_HISTORY = 11;
    private static final int PERMISSION_START = 20;
    private static final int PERMISSION_CURRENT = 21;
    private static final int PERMISSION_NOTIFICATIONS = 22;
    private static final int PERMISSION_BACKGROUND = 23;
    private static final double BEIJING_LAT = 39.9042;
    private static final double BEIJING_LNG = 116.4074;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MapView map;
    private Marker marker;
    private TextView serviceStatus;
    private TextView wgsCoordinates;
    private TextView bdCoordinates;
    private TextView altitudeText;
    private TextView selectionName;
    private TextView pickHint;
    private TextView tileAttribution;
    private Button mapTypeButton;
    private Button mapSourceButton;
    private Button pickButton;
    private Button primaryButton;
    private double selectedLat;
    private double selectedLng;
    private String selectedName;
    private boolean pickMode = true;
    private boolean satellite;
    private int normalSourceIndex;
    private OnlineTileSourceBase amapRoad;
    private OnlineTileSourceBase amapSatellite;
    private OnlineTileSourceBase cartoVoyager;
    private OnlineTileSourceBase osmMapnik;
    private OnlineTileSourceBase osmOfficial;
    private OnlineTileSourceBase esriImagery;
    private LocationManager locationManager;
    private boolean locationRequestInFlight;
    private boolean usedLastKnownLocation;

    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            refreshServiceStatus();
            if (!isFinishing() && !isDestroyed()) handler.postDelayed(this, 1000L);
        }
    };

    private final Runnable locationTimeout = new Runnable() {
        @Override
        public void run() {
            if (!locationRequestInFlight) return;
            stopLocationUpdates();
            Toast.makeText(MainActivity.this, usedLastKnownLocation
                            ? "暂未获得新的定位，当前显示最近已知位置"
                            : "定位超时，请确认定位服务已开启并到空旷处重试",
                    Toast.LENGTH_LONG).show();
        }
    };

    private final LocationListener deviceLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null || isDestroyed()) return;
            if (safeIsRunning()) {
                stopLocationUpdates();
                Toast.makeText(MainActivity.this, "模拟运行中，已忽略模拟 Provider 的位置回调", Toast.LENGTH_SHORT).show();
                return;
            }
            stopLocationUpdates();
            selectLocation(location.getLatitude(), location.getLongitude(), "设备当前位置", true);
            Toast.makeText(MainActivity.this, "已更新当前位置（WGS84）", Toast.LENGTH_SHORT).show();
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureImmersiveStatusBar();
        Configuration.getInstance().load(getApplicationContext(),
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
        Configuration.getInstance().setUserAgentValue(getPackageName());
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        restoreInitialLocation(savedInstanceState);
        buildInterface(savedInstanceState);
        handler.postDelayed(statusRefresh, 500L);
        if (Prefs.get(this).getBoolean(Prefs.FIRST_RUN, true)) {
            handler.post(this::showFirstRunAgreement);
        }
    }

    private void restoreInitialLocation(Bundle state) {
        if (state != null) {
            selectedLat = state.getDouble("selectedLat", BEIJING_LAT);
            selectedLng = state.getDouble("selectedLng", BEIJING_LNG);
            selectedName = state.getString("selectedName", "地图选点");
            pickMode = state.getBoolean("pickMode", true);
        } else {
            SharedPreferences prefs = Prefs.get(this);
            boolean hasLast = prefs.contains(Prefs.LAST_LAT) && prefs.contains(Prefs.LAST_LNG);
            selectedLat = Prefs.getDouble(prefs, Prefs.LAST_LAT, BEIJING_LAT);
            selectedLng = Prefs.getDouble(prefs, Prefs.LAST_LNG, BEIJING_LNG);
            selectedName = hasLast ? "上次位置" : "北京（默认位置）";
        }
        if (!valid(selectedLat, selectedLng)) {
            selectedLat = BEIJING_LAT;
            selectedLng = BEIJING_LNG;
            selectedName = "北京（默认位置）";
        }
    }

    private void buildInterface(Bundle state) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 247, 250));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.rgb(21, 101, 192));
        final int horizontalPadding = dp(16);
        final int topPadding = dp(10);
        header.setPadding(horizontalPadding, topPadding, horizontalPadding, dp(12));
        header.setOnApplyWindowInsetsListener((view, insets) -> {
            header.setPadding(horizontalPadding, topPadding + insets.getSystemWindowInsetTop(),
                    horizontalPadding, dp(12));
            return insets;
        });
        TextView title = text("影梭·独立版", 23, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title);
        serviceStatus = text("正在读取服务状态…", 14, Color.WHITE);
        serviceStatus.setPadding(0, dp(3), 0, 0);
        header.addView(serviceStatus);
        root.addView(header);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(24));

        SharedPreferences mapPrefs = Prefs.get(this);
        normalSourceIndex = state != null
                ? state.getInt("normalSourceIndex", Prefs.getInt(mapPrefs, Prefs.MAP_SOURCE_V2, 3))
                : Prefs.getInt(mapPrefs, Prefs.MAP_SOURCE_V2, 3);
        if (normalSourceIndex < 0 || normalSourceIndex > 3) normalSourceIndex = 3;
        satellite = state != null ? state.getBoolean("satellite", false)
                : mapPrefs.getBoolean(Prefs.MAP_SATELLITE, false);
        amapRoad = createAmapRoadSource();
        amapSatellite = createAmapSatelliteSource();
        cartoVoyager = createCartoVoyagerSource();
        osmMapnik = createOsmMirrorSource();
        osmOfficial = createOsmOfficialSource();
        esriImagery = createEsriImagerySource();
        map = new MapView(this);
        map.setTileSource(satellite ? esriImagery : currentNormalSource());
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(true);
        map.setTilesScaledToDpi(true);
        map.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        content.addView(map, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));
        mapTypeButton = button("地图类型：普通图（点击切换卫星图）");
        mapTypeButton.setOnClickListener(v -> {
            satellite = !satellite;
            Prefs.get(this).edit().putBoolean(Prefs.MAP_SATELLITE, satellite).apply();
            applyMapType();
        });
        content.addView(mapTypeButton);
        mapSourceButton = button("");
        mapSourceButton.setOnClickListener(v -> {
            normalSourceIndex = (normalSourceIndex + 1) % 4;
            Prefs.get(this).edit().putString(Prefs.MAP_SOURCE_V2,
                    String.valueOf(normalSourceIndex)).apply();
            applyMapType();
        });
        content.addView(mapSourceButton);
        Button refreshTiles = button("清除缓存并刷新在线地图");
        refreshTiles.setOnClickListener(v -> refreshOnlineMap());
        content.addView(refreshTiles);
        tileAttribution = text("", 11, Color.GRAY);
        tileAttribution.setPadding(dp(4), dp(2), dp(4), dp(4));
        content.addView(tileAttribution);

        double initialZoom = 16.0;
        if (state != null) initialZoom = state.getDouble("zoom", 16.0);
        map.getController().setZoom(initialZoom);
        map.getController().setCenter(toMapPoint(selectedLat, selectedLng));

        MapEventsOverlay events = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint point) {
                if (pickMode) {
                    runOnUiThread(() -> {
                        double[] wgs = fromMapPoint(point);
                        selectLocation(wgs[1], wgs[0], "地图选点", true);
                    });
                }
                return pickMode;
            }

            @Override
            public boolean longPressHelper(GeoPoint point) {
                return false;
            }
        });
        map.getOverlays().add(events);
        marker = new Marker(map);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setOnMarkerClickListener((clicked, mapView) -> {
            clicked.showInfoWindow();
            return true;
        });
        map.getOverlays().add(marker);

        pickButton = button(pickMode ? "点击地图设置位置（已开启）" : "点击地图设置位置");
        pickButton.setOnClickListener(v -> {
            pickMode = !pickMode;
            updatePickMode();
        });
        content.addView(pickButton);
        pickHint = text("", 13, Color.GRAY);
        pickHint.setPadding(dp(4), 0, dp(4), dp(6));
        content.addView(pickHint);

        selectionName = text("", 17, Color.BLACK);
        selectionName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        selectionName.setPadding(dp(4), dp(4), dp(4), dp(4));
        content.addView(selectionName);

        wgsCoordinates = text("", 15, Color.rgb(21, 101, 192));
        wgsCoordinates.setPadding(dp(8), dp(10), dp(8), dp(10));
        wgsCoordinates.setOnClickListener(v -> copyCoordinates("WGS84", selectedLat, selectedLng));
        content.addView(wgsCoordinates);

        bdCoordinates = text("", 15, Color.rgb(21, 101, 192));
        bdCoordinates.setPadding(dp(8), dp(10), dp(8), dp(10));
        bdCoordinates.setOnClickListener(v -> {
            double[] bd = GeoUtils.wgs84ToBd09(selectedLng, selectedLat);
            copyCoordinates("BD09", bd[1], bd[0]);
        });
        content.addView(bdCoordinates);

        altitudeText = text("", 14, Color.DKGRAY);
        altitudeText.setPadding(dp(4), dp(3), dp(4), dp(8));
        content.addView(altitudeText);

        primaryButton = button("开始模拟");
        primaryButton.setOnClickListener(v -> toggleSimulation());
        content.addView(primaryButton);

        Button current = button("更新当前位置");
        current.setOnClickListener(v -> {
            if (!ensureAgreementAccepted()) return;
            if (hasLocationPermission()) updateCurrentLocation();
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_CURRENT);
        });
        content.addView(current);

        Button inputCoordinates = button("输入坐标");
        inputCoordinates.setOnClickListener(v -> {
            if (ensureAgreementAccepted()) showCoordinateInputDialog();
        });
        content.addView(inputCoordinates);

        Button share = button("分享位置");
        share.setOnClickListener(v -> shareCurrentLocation());
        content.addView(share);

        Button permissions = button("权限设置（逐项自动跳转）");
        permissions.setOnClickListener(v -> {
            if (ensureAgreementAccepted()) startActivity(new Intent(this, PermissionsActivity.class)
                    .putExtra("auto_start", true));
        });
        content.addView(permissions);

        Button diagnostics = button("定位诊断（GPS 信号弱 / 无位置）");
        diagnostics.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        content.addView(diagnostics);

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        Button history = button("历史");
        history.setOnClickListener(v -> {
            if (ensureAgreementAccepted()) startActivityForResult(
                    new Intent(this, HistoryActivity.class), REQUEST_HISTORY);
        });
        Button search = button("搜索");
        search.setOnClickListener(v -> {
            if (ensureAgreementAccepted()) startActivityForResult(
                    new Intent(this, SearchActivity.class), REQUEST_SEARCH);
        });
        Button settings = button("设置");
        settings.setOnClickListener(v -> {
            if (ensureAgreementAccepted()) startActivity(new Intent(this, SettingsActivity.class));
        });
        navigation.addView(history, new LinearLayout.LayoutParams(0, dp(52), 1));
        navigation.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        navigation.addView(settings, new LinearLayout.LayoutParams(0, dp(52), 1));
        content.addView(navigation);

        TextView coordinateNote = text("地图、搜索和定位统一保存 WGS84；BD09 仅用于输入/显示转换。普通图默认使用高德在线道路图，可切换其他在线图源并清缓存刷新。高德选点自动进行 WGS84 ↔ GCJ-02 转换，其他普通图使用 WGS84。不同地图的道路更新时间与卫星影像拍摄日期可能不同；位置对不上时请先核对坐标系，再手动点图微调。",
                12, Color.GRAY);
        coordinateNote.setPadding(dp(4), dp(8), dp(4), 0);
        content.addView(coordinateNote);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        applyMapType();
        updatePickMode();
        updateLocationViews();
        refreshServiceStatus();
    }

    private OnlineTileSourceBase createAmapRoadSource() {
        return new OnlineTileSourceBase("AMAP Road Online GCJ02", 3, 19, 256, ".png",
                new String[]{"https://webrd01.is.autonavi.com/appmaptile",
                        "https://webrd02.is.autonavi.com/appmaptile",
                        "https://webrd03.is.autonavi.com/appmaptile",
                        "https://webrd04.is.autonavi.com/appmaptile"}) {
            @Override
            public String getTileURLString(long tileIndex) {
                return getBaseUrl() + "?lang=zh_cn&size=1&scale=1&style=7&x="
                        + MapTileIndex.getX(tileIndex) + "&y=" + MapTileIndex.getY(tileIndex)
                        + "&z=" + MapTileIndex.getZoom(tileIndex);
            }
        };
    }

    private OnlineTileSourceBase createAmapSatelliteSource() {
        return new OnlineTileSourceBase("AMAP Satellite Online GCJ02", 3, 19, 256, ".jpg",
                new String[]{"https://webst01.is.autonavi.com/appmaptile",
                        "https://webst02.is.autonavi.com/appmaptile",
                        "https://webst03.is.autonavi.com/appmaptile",
                        "https://webst04.is.autonavi.com/appmaptile"}) {
            @Override
            public String getTileURLString(long tileIndex) {
                return getBaseUrl() + "?style=6&x=" + MapTileIndex.getX(tileIndex)
                        + "&y=" + MapTileIndex.getY(tileIndex) + "&z="
                        + MapTileIndex.getZoom(tileIndex);
            }
        };
    }

    private OnlineTileSourceBase createCartoVoyagerSource() {
        return new OnlineTileSourceBase("CARTO Voyager Online", 0, 20, 256, ".png",
                new String[]{"https://basemaps.cartocdn.com/rastertiles/voyager/"}) {
            @Override
            public String getTileURLString(long tileIndex) {
                return getBaseUrl() + MapTileIndex.getZoom(tileIndex) + "/"
                        + MapTileIndex.getX(tileIndex) + "/" + MapTileIndex.getY(tileIndex) + ".png";
            }
        };
    }

    private OnlineTileSourceBase createOsmMirrorSource() {
        return new OnlineTileSourceBase("OSM MAPNIK DE Online", 0, 19, 256, ".png",
                new String[]{"https://tile.openstreetmap.de/"}) {
            @Override
            public String getTileURLString(long tileIndex) {
                return getBaseUrl() + MapTileIndex.getZoom(tileIndex) + "/"
                        + MapTileIndex.getX(tileIndex) + "/" + MapTileIndex.getY(tileIndex) + ".png";
            }
        };
    }

    private OnlineTileSourceBase createOsmOfficialSource() {
        return new OnlineTileSourceBase("OSM MAPNIK Official Online", 0, 19, 256, ".png",
                new String[]{"https://tile.openstreetmap.org/"}) {
            @Override
            public String getTileURLString(long tileIndex) {
                return getBaseUrl() + MapTileIndex.getZoom(tileIndex) + "/"
                        + MapTileIndex.getX(tileIndex) + "/" + MapTileIndex.getY(tileIndex) + ".png";
            }
        };
    }

    private OnlineTileSourceBase createEsriImagerySource() {
        return new OnlineTileSourceBase("EsriWorldImagery", 0, 19, 256, ".jpg",
                new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"}) {
            @Override
            public String getTileURLString(long tileIndex) {
                return getBaseUrl() + MapTileIndex.getZoom(tileIndex) + "/"
                        + MapTileIndex.getY(tileIndex) + "/" + MapTileIndex.getX(tileIndex);
            }
        };
    }

    private OnlineTileSourceBase currentNormalSource() {
        if (normalSourceIndex == 0) return cartoVoyager;
        if (normalSourceIndex == 1) return osmMapnik;
        if (normalSourceIndex == 2) return osmOfficial;
        return amapRoad;
    }

    private String normalSourceName() {
        if (normalSourceIndex == 0) return "CARTO Voyager";
        if (normalSourceIndex == 1) return "OSM 德国镜像";
        if (normalSourceIndex == 2) return "OSM 官方在线";
        return "高德在线道路图（GCJ-02）";
    }

    private boolean isGcjMap() {
        return normalSourceIndex == 3;
    }

    private OnlineTileSourceBase currentSatelliteSource() {
        return normalSourceIndex == 3 ? amapSatellite : esriImagery;
    }

    private GeoPoint toMapPoint(double lat, double lng) {
        if (!isGcjMap()) return new GeoPoint(lat, lng);
        double[] gcj = GeoUtils.wgs84ToGcj02(lng, lat);
        return new GeoPoint(gcj[1], gcj[0]);
    }

    private double[] fromMapPoint(GeoPoint point) {
        if (!isGcjMap()) return new double[]{point.getLongitude(), point.getLatitude()};
        return GeoUtils.gcj02ToWgs84(point.getLongitude(), point.getLatitude());
    }

    private void applyMapType() {
        if (map != null) {
            map.setTileSource(satellite ? currentSatelliteSource() : currentNormalSource());
            map.invalidate();
        }
        if (mapTypeButton != null) {
            mapTypeButton.setText(satellite ? "地图类型：卫星图（点击切换普通图）"
                    : "地图类型：普通图（点击切换卫星图）");
        }
        if (mapSourceButton != null) {
            mapSourceButton.setText("普通图源：" + normalSourceName() + "（点击切换）");
            mapSourceButton.setEnabled(!satellite);
        }
        if (tileAttribution != null) {
            if (satellite && normalSourceIndex == 3) {
                tileAttribution.setText("卫星影像 © 高德地图 AutoNavi · 在线卫星图 · GCJ-02。选点已自动转换坐标，影像拍摄日期不等于实时地图。");
            } else if (satellite) {
                tileAttribution.setText("卫星影像 © Esri, Maxar, Earthstar Geographics, and the GIS User Community。影像拍摄日期不等于实时地图。");
            } else if (normalSourceIndex == 3) {
                tileAttribution.setText("地图 © 高德地图 AutoNavi · 在线道路图 · GCJ-02。标记与点击选点已自动进行 WGS84 坐标转换。");
            } else if (normalSourceIndex == 0) {
                tileAttribution.setText("地图 © OpenStreetMap contributors © CARTO。在线持续更新，无安装包内置旧地图。");
            } else {
                tileAttribution.setText("地图 © OpenStreetMap contributors · " + normalSourceName() + "。在线瓦片，可清缓存刷新。");
            }
        }
        if (map != null && marker != null) {
            GeoPoint point = toMapPoint(selectedLat, selectedLng);
            marker.setPosition(point);
            map.getController().setCenter(point);
            map.invalidate();
        }
    }

    private void refreshOnlineMap() {
        if (map == null) return;
        try {
            Configuration.getInstance().setExpirationOverrideDuration(0L);
            map.getTileProvider().clearTileCache();
            applyMapType();
            Toast.makeText(this, "已清理地图缓存，将绕过旧缓存重新请求在线瓦片", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "地图刷新失败：" + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void configureImmersiveStatusBar() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void selectLocation(double lat, double lng, String name, boolean record) {
        if (!valid(lat, lng)) {
            Toast.makeText(this, "位置坐标无效", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedLat = lat;
        selectedLng = lng;
        selectedName = name == null || name.trim().length() == 0 ? "地图选点" : name.trim();
        Prefs.get(this).edit()
                .putString(Prefs.LAST_LAT, String.valueOf(lat))
                .putString(Prefs.LAST_LNG, String.valueOf(lng))
                .apply();
        GeoPoint point = toMapPoint(lat, lng);
        marker.setPosition(point);
        marker.setTitle(selectedName);
        marker.setSnippet(String.format(Locale.US, "WGS84 %.7f, %.7f", lat, lng));
        map.getController().animateTo(point);
        map.invalidate();
        updateLocationViews();
        if (record) {
            LocationStore.record(this, selectedName, lat, lng, currentAltitude());
        }
    }

    private void updateLocationViews() {
        double[] bd = GeoUtils.wgs84ToBd09(selectedLng, selectedLat);
        if (marker != null) {
            marker.setPosition(toMapPoint(selectedLat, selectedLng));
            marker.setTitle(selectedName);
            marker.setSnippet(String.format(Locale.US, "WGS84 %.7f, %.7f", selectedLat, selectedLng));
            map.invalidate();
        }
        selectionName.setText("当前位置：" + selectedName);
        wgsCoordinates.setText(String.format(Locale.US,
                "WGS84（点击复制）\n纬度 %.7f，经度 %.7f", selectedLat, selectedLng));
        bdCoordinates.setText(String.format(Locale.US,
                "BD09（点击复制）\n纬度 %.7f，经度 %.7f", bd[1], bd[0]));
        altitudeText.setText(String.format(Locale.US,
                "模拟海拔：%.2f m（来自设置）", currentAltitude()));
    }

    private void updatePickMode() {
        pickButton.setText(pickMode ? "点击地图设置位置（已开启）" : "点击地图设置位置");
        pickHint.setText(pickMode ? "选点模式已开启：点击地图即可放置标记并移动地图中心。"
                : "选点模式已关闭：可拖动、缩放地图，点击按钮重新开启。");
    }

    private void showCoordinateInputDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        RadioGroup coordinateSystem = new RadioGroup(this);
        coordinateSystem.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton wgs = new RadioButton(this);
        wgs.setText("WGS84");
        wgs.setChecked(true);
        RadioButton bd = new RadioButton(this);
        bd.setText("BD09");
        coordinateSystem.addView(wgs);
        coordinateSystem.addView(bd);
        form.addView(coordinateSystem);
        EditText latitude = coordinateInput("纬度（-90 至 90）");
        EditText longitude = coordinateInput("经度（-180 至 180）");
        form.addView(latitude);
        form.addView(longitude);
        TextView hint = text("选择 BD09 时将转换一次为 WGS84 后选点；手动输入不应用随机偏移。", 12, Color.GRAY);
        form.addView(hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("输入坐标")
                .setView(form)
                .setPositiveButton("使用位置", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        double lat = Double.parseDouble(latitude.getText().toString().trim());
                        double lng = Double.parseDouble(longitude.getText().toString().trim());
                        if (!valid(lat, lng)) {
                            latitude.setError("请输入有效范围：纬度 -90 至 90，经度 -180 至 180");
                            return;
                        }
                        boolean isBd09 = bd.isChecked();
                        if (isBd09) {
                            double[] converted = GeoUtils.bd09ToWgs84(lng, lat);
                            lng = converted[0];
                            lat = converted[1];
                            if (!valid(lat, lng)) {
                                latitude.setError("BD09 坐标转换结果无效");
                                return;
                            }
                        }
                        selectLocation(lat, lng, isBd09 ? "手动输入（BD09 转 WGS84）"
                                : "手动输入（WGS84）", true);
                        dialog.dismiss();
                    } catch (Exception error) {
                        Toast.makeText(this, "请输入有效的数字经纬度", Toast.LENGTH_SHORT).show();
                    }
                }));
        dialog.show();
    }

    private EditText coordinateInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return input;
    }

    private void shareCurrentLocation() {
        String lat = String.format(Locale.US, "%.7f", selectedLat);
        String lng = String.format(Locale.US, "%.7f", selectedLng);
        String link = "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lng
                + "#map=16/" + lat + "/" + lng;
        String message = selectedName + "\nWGS84：纬度 " + lat + "，经度 " + lng + "\n" + link;
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "分享位置：" + selectedName)
                .putExtra(Intent.EXTRA_TEXT, message);
        try {
            startActivity(Intent.createChooser(send, "分享位置"));
        } catch (Exception error) {
            Toast.makeText(this, "没有可处理分享的应用：" + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void copyCoordinates(String label, double lat, double lng) {
        String value = String.format(Locale.US, "%.7f,%.7f", lat, lng);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label + " 纬度,经度", value));
            Toast.makeText(this, "已复制 " + label + "：" + value, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleSimulation() {
        if (!ensureAgreementAccepted()) return;
        if (safeIsRunning()) {
            try {
                MockLocationService.stop(this);
                Toast.makeText(this, "已请求停止模拟", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, "停止模拟失败：" + safeMessage(error), Toast.LENGTH_LONG).show();
            }
            refreshServiceStatus();
            handler.removeCallbacks(statusRefresh);
            handler.postDelayed(statusRefresh, 300L);
            return;
        }
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_START);
            return;
        }
        continueStartFlow();
    }

    private void continueStartFlow() {
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    PERMISSION_BACKGROUND);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERMISSION_NOTIFICATIONS);
            return;
        }
        checkMockConfigurationAndStart();
    }

    private void checkMockConfigurationAndStart() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(), getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                showMockLocationRequired();
                return;
            }
        } catch (Exception error) {
            showMockLocationRequired();
            return;
        }
        try {
            MockLocationService.start(this, selectedLat, selectedLng, currentAltitude());
            LocationStore.record(this, selectedName, selectedLat, selectedLng, currentAltitude());
            Toast.makeText(this, "已请求开始模拟", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "开始模拟失败：" + safeMessage(error)
                    + "\n请检查模拟位置应用和定位权限设置", Toast.LENGTH_LONG).show();
        }
        refreshServiceStatus();
        handler.removeCallbacks(statusRefresh);
        handler.postDelayed(statusRefresh, 500L);
    }

    private void showMockLocationRequired() {
        new AlertDialog.Builder(this)
                .setTitle("需要手动配置模拟位置")
                .setMessage("本应用不能直接授予 ACCESS_MOCK_LOCATION，也不能替你选择系统默认模拟位置应用。\n\n请手动完成：\n1. 开启系统开发者选项；\n2. 打开“选择模拟位置信息应用”；\n3. 选择“影梭·独立版”；\n4. 返回后再次开始模拟。")
                .setPositiveButton("打开开发者选项", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    } catch (Exception error) {
                        try {
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        } catch (Exception fallbackError) {
                            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean safeIsRunning() {
        try {
            return MockLocationService.isRunning();
        } catch (Exception error) {
            return false;
        }
    }

    private void refreshServiceStatus() {
        if (serviceStatus == null || primaryButton == null) return;
        boolean running = safeIsRunning();
        serviceStatus.setText(running ? "状态：模拟运行中" : "状态：未启动模拟");
        serviceStatus.setTextColor(running ? Color.rgb(187, 255, 204) : Color.WHITE);
        primaryButton.setText(running ? "停止模拟" : "开始模拟");
    }

    private void updateCurrentLocation() {
        if (safeIsRunning()) {
            Toast.makeText(this, "请先停止模拟，再获取真实设备位置", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasLocationPermission()) {
            Toast.makeText(this, "未获得定位权限", Toast.LENGTH_SHORT).show();
            return;
        }
        if (locationRequestInFlight) {
            Toast.makeText(this, "正在获取定位，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            usedLastKnownLocation = false;
            Location best = null;
            List<String> providers = locationManager.getProviders(true);
            for (String provider : providers) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            }
            if (best != null && valid(best.getLatitude(), best.getLongitude())) {
                usedLastKnownLocation = true;
                selectLocation(best.getLatitude(), best.getLongitude(), "设备最近位置", true);
            }
            String provider = null;
            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            } else if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            } else if (!providers.isEmpty()) {
                provider = providers.get(0);
            }
            if (provider == null) {
                if (best == null) Toast.makeText(this, "请开启系统定位服务后重试", Toast.LENGTH_LONG).show();
                return;
            }
            locationRequestInFlight = true;
            locationManager.requestSingleUpdate(provider, deviceLocationListener, Looper.getMainLooper());
            handler.postDelayed(locationTimeout, 15000L);
            Toast.makeText(this, best == null ? "正在获取当前位置…" : "已显示最近位置，正在获取更新…",
                    Toast.LENGTH_SHORT).show();
        } catch (SecurityException error) {
            stopLocationUpdates();
            Toast.makeText(this, "定位权限不可用，请重新授权", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            stopLocationUpdates();
            Toast.makeText(this, "定位失败：" + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void stopLocationUpdates() {
        locationRequestInFlight = false;
        handler.removeCallbacks(locationTimeout);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(deviceLocationListener);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_BACKGROUND) {
            if (Build.VERSION.SDK_INT >= 29
                    && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "未允许后台定位，持续模拟可能受系统后台策略影响",
                        Toast.LENGTH_LONG).show();
            }
            continueStartFlow();
            return;
        }
        if (requestCode == PERMISSION_NOTIFICATIONS) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知权限未授予，仍将继续；服务通知可能在系统任务管理器中显示",
                        Toast.LENGTH_LONG).show();
            }
            checkMockConfigurationAndStart();
            return;
        }
        if (requestCode == PERMISSION_START) {
            if (hasLocationPermission()) continueStartFlow();
            else Toast.makeText(this, "需要定位权限才能开始模拟，请在应用设置中允许定位",
                    Toast.LENGTH_LONG).show();
        } else if (requestCode == PERMISSION_CURRENT) {
            if (hasLocationPermission()) updateCurrentLocation();
            else Toast.makeText(this, "定位权限被拒绝，无法更新当前位置", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode != REQUEST_SEARCH && requestCode != REQUEST_HISTORY) return;
        double lat = data.getDoubleExtra("lat", Double.NaN);
        double lng = data.getDoubleExtra("lng", Double.NaN);
        if (!valid(lat, lng)) {
            Toast.makeText(this, "返回的位置坐标无效", Toast.LENGTH_LONG).show();
            return;
        }
        String name = data.getStringExtra("name");
        if (requestCode == REQUEST_HISTORY
                && Prefs.get(this).getBoolean(Prefs.RANDOM_OFFSET, false)) {
            SharedPreferences prefs = Prefs.get(this);
            double maxLng = Math.max(0.0, Prefs.getDouble(prefs, Prefs.MAX_LNG_OFFSET, 10.0));
            double maxLat = Math.max(0.0, Prefs.getDouble(prefs, Prefs.MAX_LAT_OFFSET, 10.0));
            double[] randomized = GeoUtils.randomOffset(lng, lat, maxLng, maxLat, new Random());
            lng = randomized[0];
            lat = randomized[1];
        }
        selectLocation(lat, lng, name, true);
    }

    private boolean ensureAgreementAccepted() {
        if (!Prefs.get(this).getBoolean(Prefs.FIRST_RUN, true)) return true;
        showFirstRunAgreement();
        return false;
    }

    private void showFirstRunAgreement() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("用户协议与免责声明")
                .setMessage(agreementText())
                .setCancelable(false)
                .setPositiveButton("同意并继续", (dialog, which) ->
                        Prefs.get(this).edit().putBoolean(Prefs.FIRST_RUN, false).apply())
                .setNegativeButton("不同意并退出", (dialog, which) -> finish())
                .show();
    }

    public static String agreementText() {
        return "欢迎使用影梭·独立版。使用前请完整阅读并理解以下内容：\n\n"
                + "一、用途与授权\n"
                + "本应用仅供开发者、测试人员在本人拥有或明确获授权的设备上进行定位功能开发与测试。你不是必须接受本协议；如不同意，请退出并停止使用。\n\n"
                + "二、系统配置\n"
                + "模拟定位需要定位权限，并需你自行在系统开发者选项中将本应用选为模拟位置信息应用。本应用无法直接授予 ACCESS_MOCK_LOCATION，也不会绕过系统安全限制。前台服务通知与悬浮摇杆还可能需要通知、悬浮窗权限。\n\n"
                + "三、坐标与数据\n"
                + "内部位置统一使用 WGS84，BD09 显示值仅由当前 WGS84 单向计算，手动 BD09 输入只在选点前转换一次。不同地图、设备定位及坐标系之间可能存在偏移；中国大陆位置尤其需要人工核对和微调。公开地图与地址搜索可能不可用、超时或返回不准确结果。\n\n"
                + "四、独立实现\n"
                + "本版核心业务、界面与本地历史存储为独立实现，不接入第三方账号、私有域名或云端业务运行时。应用仅打包开源地图组件，并按需访问公开 OpenStreetMap 瓦片镜像、Esri 影像、Nominatim 及 Photon 搜索服务；不保证这些公共服务持续可用。\n\n"
                + "五、禁止行为与责任\n"
                + "不得将本应用用于游戏或平台作弊、规避监管、欺诈、侵犯他人权益或任何违法违规行为。请遵守适用法律法规、系统规则及第三方服务条款。因误用、坐标偏差、数据丢失或服务异常造成的后果由使用者自行承担；开发者不承担由此产生的责任。\n\n"
                + "六、隐私与停止\n"
                + "历史位置仅保存在本机应用数据中，清除应用数据会将其删除。停止模拟后，部分应用或系统可能仍保留最后一次模拟位置，必要时请重新选择其他模拟位置应用或重启设备。点击“同意并继续”即表示你已理解并同意上述内容。";
    }

    private double currentAltitude() {
        return Prefs.getDouble(Prefs.get(this), Prefs.ALTITUDE, 55.0);
    }

    private boolean valid(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        if (altitudeText != null) updateLocationViews();
        handler.removeCallbacks(statusRefresh);
        handler.post(statusRefresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusRefresh);
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putDouble("selectedLat", selectedLat);
        outState.putDouble("selectedLng", selectedLng);
        outState.putString("selectedName", selectedName);
        outState.putBoolean("pickMode", pickMode);
        if (map != null) outState.putDouble("zoom", map.getZoomLevelDouble());
        outState.putBoolean("satellite", satellite);
        outState.putInt("normalSourceIndex", normalSourceIndex);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        handler.removeCallbacksAndMessages(null);
        if (map != null) map.onDetach();
        super.onDestroy();
    }
}
