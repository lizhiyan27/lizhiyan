package com.dafeiyu.locationshadow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Central permission checklist with a return-aware, sequential system-settings flow. */
public final class PermissionsActivity extends Activity {
    private static final int STEP_LOCATION = 0;
    private static final int STEP_BACKGROUND = 1;
    private static final int STEP_MOCK = 2;
    private static final int STEP_NOTIFICATION = 3;
    private static final int STEP_OVERLAY = 4;
    private static final int STEP_LOCATION_SERVICE = 5;
    private static final int STEP_BATTERY = 6;
    private static final int STEP_APP_DETAILS = 7;
    private static final int STEP_COUNT = 7;

    private static final int REQUEST_FLOW_SETTINGS = 100;
    private static final int REQUEST_SINGLE_SETTINGS = 101;
    private static final int REQUEST_FLOW_PERMISSION = 200;
    private static final int REQUEST_SINGLE_PERMISSION = 201;

    private LinearLayout items;
    private TextView summary;
    private Button flowButton;
    private boolean flowActive;
    private boolean flowWaiting;
    private int flowIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            flowActive = savedInstanceState.getBoolean("flowActive", false);
            flowWaiting = savedInstanceState.getBoolean("flowWaiting", false);
            flowIndex = savedInstanceState.getInt("flowIndex", 0);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        content.setBackgroundColor(Color.WHITE);

        TextView title = text("权限设置", 25, Color.rgb(21, 101, 192));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title);
        TextView explanation = text("这里集中管理定位、模拟位置、通知、悬浮窗和系统定位服务。Android 不允许第三方应用一键强制授予全部权限；“逐项开启”会自动跳转系统界面，返回后继续下一项。", 14, Color.DKGRAY);
        explanation.setPadding(0, dp(8), 0, dp(10));
        content.addView(explanation);

        flowButton = button(flowActive ? "继续逐项开启" : "逐项开启（自动跳转）");
        flowButton.setOnClickListener(v -> startAutomaticFlow());
        content.addView(flowButton);

        Button systemPage = button("系统应用权限页（手动管理全部权限）");
        systemPage.setOnClickListener(v -> openApplicationDetails(REQUEST_SINGLE_SETTINGS));
        content.addView(systemPage);

        Button diagnostics = button("定位诊断（GPS 信号弱 / 无位置）");
        diagnostics.setOnClickListener(v -> startActivity(
                new Intent(this, DiagnosticsActivity.class)));
        content.addView(diagnostics);

        summary = text("", 15, Color.DKGRAY);
        summary.setPadding(0, dp(12), 0, dp(8));
        content.addView(summary);
        items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        content.addView(items);

        TextView note = text("提示：模拟位置应用必须由你在开发者选项中手动选择“影梭·独立版”；悬浮窗只在使用摇杆时需要。若某步被拒绝或跳过，可返回本页单独重新设置。", 12, Color.GRAY);
        note.setPadding(0, dp(14), 0, 0);
        content.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        refreshStatuses();

        if (savedInstanceState == null && getIntent().getBooleanExtra("auto_start", false)) {
            flowButton.post(this::startAutomaticFlow);
        }
    }

    private void startAutomaticFlow() {
        if (flowActive && flowWaiting) {
            Toast.makeText(this, "请先完成当前系统设置并返回，再继续", Toast.LENGTH_LONG).show();
            return;
        }
        if (!flowActive) {
            flowIndex = 0;
        }
        flowActive = true;
        flowWaiting = false;
        flowButton.setText("继续逐项开启");
        runFlowStep();
    }

    private void runFlowStep() {
        if (!flowActive || flowWaiting) return;
        while (flowIndex < STEP_COUNT && isGranted(flowIndex)) {
            flowIndex++;
        }
        if (flowIndex >= STEP_COUNT) {
            if (flowIndex == STEP_COUNT) {
                flowWaiting = true;
                openApplicationDetails(REQUEST_FLOW_SETTINGS);
                return;
            }
            finishFlow();
            return;
        }
        flowWaiting = true;
        executeStep(flowIndex, true);
    }

    private void completeFlowStep() {
        if (!flowActive || !flowWaiting) return;
        flowWaiting = false;
        flowIndex++;
        runFlowStep();
    }

    private void finishFlow() {
        flowActive = false;
        flowWaiting = false;
        flowIndex = 0;
        flowButton.setText("逐项开启（自动跳转）");
        Toast.makeText(this, "逐项跳转已结束，请核对权限状态", Toast.LENGTH_LONG).show();
        refreshStatuses();
    }

    private void openSingleStep(int step) {
        if (step != STEP_APP_DETAILS && isGranted(step)) {
            openApplicationDetails(REQUEST_SINGLE_SETTINGS);
            return;
        }
        executeStep(step, false);
    }

    private void executeStep(int step, boolean fromFlow) {
        int settingsRequest = fromFlow ? REQUEST_FLOW_SETTINGS : REQUEST_SINGLE_SETTINGS;
        int permissionRequest = fromFlow ? REQUEST_FLOW_PERMISSION : REQUEST_SINGLE_PERMISSION;
        try {
            switch (step) {
                case STEP_LOCATION:
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, permissionRequest);
                    break;
                case STEP_BACKGROUND:
                    if (Build.VERSION.SDK_INT < 29) {
                        if (fromFlow) completeFlowStep();
                        else openApplicationDetails(REQUEST_SINGLE_SETTINGS);
                    } else {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                permissionRequest);
                    }
                    break;
                case STEP_MOCK:
                    showMockInstructions(fromFlow, settingsRequest);
                    break;
                case STEP_NOTIFICATION:
                    if (Build.VERSION.SDK_INT < 33) {
                        if (fromFlow) completeFlowStep();
                        else openApplicationDetails(REQUEST_SINGLE_SETTINGS);
                    } else {
                        SharedPreferences ui = getSharedPreferences("permission_ui", MODE_PRIVATE);
                        if (ui.getBoolean("notification_requested", false)) {
                            openNotificationSettings(settingsRequest);
                        } else {
                            ui.edit().putBoolean("notification_requested", true).apply();
                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                    permissionRequest);
                        }
                    }
                    break;
                case STEP_OVERLAY:
                    Intent overlay = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startSettings(overlay, settingsRequest);
                    break;
                case STEP_LOCATION_SERVICE:
                    startSettings(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), settingsRequest);
                    break;
                case STEP_BATTERY:
                    startSettings(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())), settingsRequest);
                    break;
                case STEP_APP_DETAILS:
                    openApplicationDetails(settingsRequest);
                    break;
                default:
                    if (fromFlow) completeFlowStep();
                    break;
            }
        } catch (Exception error) {
            Toast.makeText(this, "无法打开对应设置：" + safeMessage(error), Toast.LENGTH_LONG).show();
            if (fromFlow) completeFlowStep();
        }
    }

    private void showMockInstructions(boolean fromFlow, int settingsRequest) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("手动选择模拟位置应用")
                .setMessage("下一步会打开开发者选项。请找到“选择模拟位置信息应用”（部分机型称“模拟位置应用”），选择“影梭·独立版”，然后返回本页。\n\n普通定位权限不能代替这项设置。")
                .setPositiveButton("打开开发者选项", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                    startSettings(intent, settingsRequest);
                });
        if (fromFlow) {
            builder.setNegativeButton("跳过此项", (dialog, which) -> { });
            builder.setOnCancelListener(dialog -> completeFlowStep());
        } else {
            builder.setNegativeButton("取消", null);
        }
        builder.show();
    }

    private void openNotificationSettings(int requestCode) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startSettings(intent, requestCode);
    }

    private void openApplicationDetails(int requestCode) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startSettings(intent, requestCode);
    }

    private void startSettings(Intent intent, int requestCode) {
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException error) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_SETTINGS), requestCode);
            } catch (Exception fallbackError) {
                Toast.makeText(this, "无法打开系统设置：" + safeMessage(fallbackError),
                        Toast.LENGTH_LONG).show();
                if (requestCode == REQUEST_FLOW_SETTINGS) completeFlowStep();
            }
        } catch (Exception error) {
            Toast.makeText(this, "无法打开系统设置：" + safeMessage(error), Toast.LENGTH_LONG).show();
            if (requestCode == REQUEST_FLOW_SETTINGS) completeFlowStep();
        }
    }

    private boolean isGranted(int step) {
        try {
            switch (step) {
                case STEP_LOCATION:
                    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED
                            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED;
                case STEP_BACKGROUND:
                    return Build.VERSION.SDK_INT < 29
                            || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            == PackageManager.PERMISSION_GRANTED;
                case STEP_MOCK:
                    AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
                    return appOps != null && appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                            Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
                case STEP_NOTIFICATION:
                    return Build.VERSION.SDK_INT < 33
                            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED;
                case STEP_OVERLAY:
                    return Settings.canDrawOverlays(this);
                case STEP_LOCATION_SERVICE:
                    LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
                    if (manager == null) return false;
                    if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
                    return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                            || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                case STEP_BATTERY:
                    PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
                    return powerManager != null
                            && powerManager.isIgnoringBatteryOptimizations(getPackageName());
                default:
                    return false;
            }
        } catch (Exception error) {
            return false;
        }
    }

    private void refreshStatuses() {
        if (items == null) return;
        int granted = 0;
        for (int step = 0; step < STEP_COUNT; step++) {
            if (isGranted(step)) granted++;
        }
        summary.setText("已完成 " + granted + " / " + STEP_COUNT + " 项（以系统返回状态为准）");
        summary.setTextColor(granted == STEP_COUNT ? Color.rgb(0, 130, 60) : Color.DKGRAY);
        items.removeAllViews();
        addItem(STEP_LOCATION, "定位权限", "用于读取真实位置和启动定位前台服务");
        addItem(STEP_BACKGROUND, "后台定位", "Android 10+ 保持持续模拟，需在定位权限后单独允许");
        addItem(STEP_MOCK, "模拟位置应用", "必须在开发者选项中选择本应用");
        addItem(STEP_NOTIFICATION, "通知权限", "Android 13+ 显示模拟状态和控制按钮");
        addItem(STEP_OVERLAY, "悬浮窗权限", "仅显示悬浮摇杆时需要");
        addItem(STEP_LOCATION_SERVICE, "系统定位服务", "关闭时可能导致真实定位不可用");
        addItem(STEP_BATTERY, "忽略电池优化", "防止厂商后台策略中断持续模拟（建议允许）");
    }

    private void addItem(int step, String title, String description) {
        boolean granted = isGranted(step);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(8));
        TextView heading = text((granted ? "● 已开启  " : "● 待开启  ") + title,
                18, granted ? Color.rgb(0, 130, 60) : Color.rgb(190, 45, 45));
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(heading);
        TextView detail = text(description, 13, Color.GRAY);
        detail.setPadding(0, dp(3), 0, dp(4));
        row.addView(detail);
        Button action = button(granted ? "查看 / 重新设置" : "去开启");
        action.setOnClickListener(v -> openSingleStep(step));
        row.addView(action);
        items.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatuses();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        refreshStatuses();
        if (requestCode == REQUEST_FLOW_SETTINGS) completeFlowStep();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatuses();
        if (requestCode == REQUEST_FLOW_PERMISSION) completeFlowStep();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("flowActive", flowActive);
        outState.putBoolean("flowWaiting", flowWaiting);
        outState.putInt("flowIndex", flowIndex);
        super.onSaveInstanceState(outState);
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

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
