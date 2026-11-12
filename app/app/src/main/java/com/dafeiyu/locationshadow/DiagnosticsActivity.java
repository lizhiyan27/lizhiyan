package com.dafeiyu.locationshadow;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Shows mock-provider delivery separately from hardware GNSS satellite state. */
public final class DiagnosticsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView report;
    private LocationManager locationManager;
    private String hardwareGnss = "尚未收到硬件卫星回调；这不代表模拟定位失败";
    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            hardwareGnss = "硬件 GNSS 已启动，等待卫星状态";
        }

        @Override
        public void onStopped() {
            hardwareGnss = "硬件 GNSS 已停止；模拟 Provider 与硬件卫星状态相互独立";
        }

        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            int used = 0;
            float maxCn0 = 0.0f;
            for (int index = 0; index < status.getSatelliteCount(); index++) {
                if (status.usedInFix(index)) used++;
                maxCn0 = Math.max(maxCn0, status.getCn0DbHz(index));
            }
            hardwareGnss = String.format(Locale.US,
                    "硬件可见卫星 %d 颗，参与解算 %d 颗，最大 C/N0 %.1f dB-Hz（不是模拟坐标状态）",
                    status.getSatelliteCount(), used, maxCn0);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateReport();
            if (!isFinishing() && !isDestroyed()) handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        content.setBackgroundColor(Color.WHITE);

        TextView title = text("定位诊断", 25, Color.rgb(21, 101, 192));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title);
        TextView explanation = text("本页区分“模拟坐标是否持续上报”和“硬件 GNSS 信号”。其他 App 显示 GPS 信号弱，可能只是没有硬件卫星回调，不等于模拟坐标无效。", 14, Color.DKGRAY);
        explanation.setPadding(0, dp(8), 0, dp(10));
        content.addView(explanation);

        Button permissions = button("打开权限设置");
        permissions.setOnClickListener(v -> startActivity(new Intent(this, PermissionsActivity.class)));
        content.addView(permissions);
        Button copy = button("复制诊断信息");
        copy.setOnClickListener(v -> copyReport());
        content.addView(copy);

        report = text("", 14, Color.BLACK);
        report.setPadding(0, dp(12), 0, dp(12));
        report.setTextIsSelectable(true);
        content.addView(report);

        TextView limits = text("重要限制：公开 Android API 没有向其他应用注入 GnssStatus、卫星信噪比或硬件信号强度的接口。Location extras 中的 satellites=7 只是位置附加字段。若目标 App 强制要求真实 GNSS 硬件状态，普通无 Root 模拟应用无法伪造；需要目标 App 支持普通 Location，或由系统/Root 层提供测试能力。", 13, Color.rgb(160, 70, 30));
        content.addView(limits);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        registerHardwareGnss();
    }

    private void updateReport() {
        if (report == null) return;
        StringBuilder text = new StringBuilder();
        text.append("【模拟服务】\n");
        text.append("运行状态：").append(MockLocationService.isRunning() ? "运行中" : "未运行").append('\n');
        text.append("上报 Provider：").append(MockLocationService.getLastProviderSummary()).append('\n');
        text.append("最近错误/回退：").append(MockLocationService.getLastError()).append('\n');
        long last = MockLocationService.getLastReportElapsed();
        if (last > 0) {
            long age = Math.max(0, SystemClock.elapsedRealtime() - last);
            text.append("最后成功上报：").append(age).append(" ms 前\n");
            text.append("最后坐标：").append(GeoUtils.format(MockLocationService.getLastReportedLat()))
                    .append("，").append(GeoUtils.format(MockLocationService.getLastReportedLng())).append('\n');
            if (MockLocationService.isRunning() && age > 3000) {
                text.append("警告：超过 3 秒未成功上报，请检查后台定位、电池优化和厂商后台限制。\n");
            }
        } else {
            text.append("最后成功上报：暂无\n");
        }
        text.append("模拟位置 AppOps：").append(isMockAllowed() ? "允许" : "未允许或无法确认").append('\n');
        if (Build.VERSION.SDK_INT >= 29) {
            text.append("后台定位权限：").append(checkSelfPermission(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                    ? "已允许" : "未允许").append('\n');
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        text.append("忽略电池优化：").append(powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName()) ? "是" : "否").append('\n');

        text.append("\n【系统 Provider】\n");
        if (locationManager != null) {
            for (String provider : locationManager.getAllProviders()) {
                boolean enabled;
                try {
                    enabled = locationManager.isProviderEnabled(provider);
                } catch (Exception error) {
                    enabled = false;
                }
                text.append(provider).append("：").append(enabled ? "启用" : "停用").append('\n');
            }
        }
        text.append("\n【硬件 GNSS】\n").append(hardwareGnss).append('\n');
        report.setText(text.toString());
    }

    private boolean isMockAllowed() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            return appOps != null && appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception error) {
            return false;
        }
    }

    private void registerHardwareGnss() {
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            hardwareGnss = "未授予精确位置权限，无法读取硬件 GNSS；可在权限中心开启";
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationManager.registerGnssStatusCallback(gnssCallback, handler);
            } else {
                LocationManager.class.getMethod("registerGnssStatusListener", GnssStatus.Callback.class)
                        .invoke(locationManager, gnssCallback);
            }
        } catch (Exception error) {
            hardwareGnss = "当前设备不允许读取硬件 GNSS：" + error.getClass().getSimpleName();
        }
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("定位诊断", report.getText().toString()));
            Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    locationManager.unregisterGnssStatusCallback(gnssCallback);
                } else {
                    LocationManager.class.getMethod("unregisterGnssStatusListener",
                            GnssStatus.Callback.class).invoke(locationManager, gnssCallback);
                }
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(5);
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
}
