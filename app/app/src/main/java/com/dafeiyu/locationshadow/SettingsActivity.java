package com.dafeiyu.locationshadow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;

public class SettingsActivity extends Activity {
    private EditText walkSpeed;
    private EditText runSpeed;
    private EditText bikeSpeed;
    private EditText altitude;
    private EditText maxLatOffset;
    private EditText maxLngOffset;
    private EditText historyDays;
    private Switch randomOffset;
    private Switch showJoystick;
    private Switch compatibilityMode;
    private Button joystickTypeButton;
    private int joystickType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(24));
        content.setBackgroundColor(Color.WHITE);

        TextView title = text("设置", 24, Color.rgb(21, 101, 192));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title);
        TextView movement = text("移动与模拟位置", 18, Color.BLACK);
        movement.setPadding(0, dp(14), 0, dp(4));
        content.addView(movement);

        walkSpeed = addNumberField(content, "步行速度（m/s）", "1.2", false);
        runSpeed = addNumberField(content, "跑步速度（m/s）", "3.6", false);
        bikeSpeed = addNumberField(content, "骑行速度（m/s）", "10.0", false);
        altitude = addNumberField(content, "海拔（m）", "55.0", true);
        randomOffset = addSwitch(content, "启用随机位置偏移");
        maxLatOffset = addNumberField(content, "最大纬度随机偏移（m）", "10.0", false);
        maxLngOffset = addNumberField(content, "最大经度随机偏移（m）", "10.0", false);
        historyDays = addNumberField(content, "历史保留天数", "7", false);
        historyDays.setInputType(InputType.TYPE_CLASS_NUMBER);
        showJoystick = addSwitch(content, "显示悬浮摇杆");
        compatibilityMode = addSwitch(content, "兼容 Fused / Passive（下次启动生效）");
        joystickTypeButton = button("摇杆类型：圆形");
        joystickTypeButton.setOnClickListener(v -> showJoystickTypeChooser());
        content.addView(joystickTypeButton);

        Button save = button("保存设置");
        save.setOnClickListener(v -> saveSettings());
        content.addView(save);

        TextView system = text("系统设置与说明", 18, Color.BLACK);
        system.setPadding(0, dp(20), 0, dp(4));
        content.addView(system);

        Button permissionCenter = button("权限设置中心（逐项自动跳转）");
        permissionCenter.setOnClickListener(v -> startActivity(
                new Intent(this, PermissionsActivity.class)));
        content.addView(permissionCenter);

        Button diagnostics = button("定位诊断（GPS 信号弱 / 无位置）");
        diagnostics.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        content.addView(diagnostics);

        Button developer = button("打开开发者选项");
        developer.setOnClickListener(v -> openDeveloperSettings());
        content.addView(developer);

        Button mock = button("选择默认模拟位置应用");
        mock.setOnClickListener(v -> showMockLocationInstructions());
        content.addView(mock);

        Button overlay = button("悬浮窗权限");
        overlay.setOnClickListener(v -> openOverlaySettings());
        content.addView(overlay);

        Button notifications = button("通知设置");
        notifications.setOnClickListener(v -> openNotificationSettings());
        content.addView(notifications);

        Button agreement = button("用户协议与免责声明");
        agreement.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("用户协议与免责声明")
                .setMessage(MainActivity.agreementText())
                .setPositiveButton("关闭", null)
                .show());
        content.addView(agreement);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        loadSettings();
    }

    private void loadSettings() {
        SharedPreferences prefs = Prefs.get(this);
        walkSpeed.setText(number(Prefs.getDouble(prefs, Prefs.WALK_SPEED, 1.2)));
        runSpeed.setText(number(Prefs.getDouble(prefs, Prefs.RUN_SPEED, 3.6)));
        bikeSpeed.setText(number(Prefs.getDouble(prefs, Prefs.BIKE_SPEED, 10.0)));
        altitude.setText(number(Prefs.getDouble(prefs, Prefs.ALTITUDE, 55.0)));
        maxLatOffset.setText(number(Prefs.getDouble(prefs, Prefs.MAX_LAT_OFFSET, 10.0)));
        maxLngOffset.setText(number(Prefs.getDouble(prefs, Prefs.MAX_LNG_OFFSET, 10.0)));
        historyDays.setText(String.valueOf(Prefs.getInt(prefs, Prefs.HISTORY_DAYS, 7)));
        randomOffset.setChecked(prefs.getBoolean(Prefs.RANDOM_OFFSET, false));
        showJoystick.setChecked(prefs.getBoolean(Prefs.SHOW_JOYSTICK, true));
        compatibilityMode.setChecked(prefs.getBoolean(Prefs.COMPATIBILITY_MODE, true));
        joystickType = Prefs.getInt(prefs, Prefs.JOYSTICK_TYPE, 0) == 1 ? 1 : 0;
        updateJoystickTypeLabel();
    }

    private void showJoystickTypeChooser() {
        new AlertDialog.Builder(this)
                .setTitle("摇杆类型")
                .setSingleChoiceItems(new String[]{"圆形", "按键"}, joystickType,
                        (dialog, which) -> {
                            joystickType = which == 1 ? 1 : 0;
                            updateJoystickTypeLabel();
                            dialog.dismiss();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateJoystickTypeLabel() {
        if (joystickTypeButton != null) {
            joystickTypeButton.setText(joystickType == 1 ? "摇杆类型：按键" : "摇杆类型：圆形");
        }
    }

    private void saveSettings() {
        Double walk = positive(walkSpeed, "步行速度", 100.0);
        Double run = positive(runSpeed, "跑步速度", 100.0);
        Double bike = positive(bikeSpeed, "骑行速度", 100.0);
        Double alt = decimal(altitude, "海拔", -500.0, 10000.0);
        Double latOffset = decimal(maxLatOffset, "最大纬度偏移", 0.0, 10000.0);
        Double lngOffset = decimal(maxLngOffset, "最大经度偏移", 0.0, 10000.0);
        Integer days = integer(historyDays, "历史保留天数", 1, 3650);
        if (walk == null || run == null || bike == null || alt == null
                || latOffset == null || lngOffset == null || days == null) return;

        Prefs.get(this).edit()
                .putString(Prefs.WALK_SPEED, number(walk))
                .putString(Prefs.RUN_SPEED, number(run))
                .putString(Prefs.BIKE_SPEED, number(bike))
                .putString(Prefs.ALTITUDE, number(alt))
                .putBoolean(Prefs.RANDOM_OFFSET, randomOffset.isChecked())
                .putString(Prefs.MAX_LAT_OFFSET, number(latOffset))
                .putString(Prefs.MAX_LNG_OFFSET, number(lngOffset))
                .putString(Prefs.HISTORY_DAYS, String.valueOf(days))
                .putBoolean(Prefs.SHOW_JOYSTICK, showJoystick.isChecked())
                .putBoolean(Prefs.COMPATIBILITY_MODE, compatibilityMode.isChecked())
                .putString(Prefs.JOYSTICK_TYPE, String.valueOf(joystickType))
                .apply();
        LocationStore.cleanupExpired(this);
        try {
            MockLocationService.updateSettings(this);
        } catch (Exception error) {
            Toast.makeText(this, "设置已保存，但运行中的服务暂未刷新：" + safeMessage(error),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private Double positive(EditText input, String label, double maximum) {
        Double value = decimal(input, label, 0.01, maximum);
        return value;
    }

    private Double decimal(EditText input, String label, double minimum, double maximum) {
        try {
            double value = Double.parseDouble(input.getText().toString().trim());
            if (!Double.isFinite(value) || value < minimum || value > maximum) {
                input.setError(label + "须在 " + number(minimum) + " 至 " + number(maximum) + " 之间");
                input.requestFocus();
                return null;
            }
            return value;
        } catch (Exception error) {
            input.setError("请输入有效的" + label);
            input.requestFocus();
            return null;
        }
    }

    private Integer integer(EditText input, String label, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value < minimum || value > maximum) {
                input.setError(label + "须在 " + minimum + " 至 " + maximum + " 之间");
                input.requestFocus();
                return null;
            }
            return value;
        } catch (Exception error) {
            input.setError("请输入有效的整数天数");
            input.requestFocus();
            return null;
        }
    }

    private void showMockLocationInstructions() {
        new AlertDialog.Builder(this)
                .setTitle("手动选择模拟位置应用")
                .setMessage("系统不允许应用直接授予 ACCESS_MOCK_LOCATION。请在开发者选项中找到“选择模拟位置信息应用”（部分机型称“模拟位置应用”），选择“影梭·独立版”。若看不到该项，请先连续点击系统“关于手机”中的版本号以启用开发者选项。")
                .setPositiveButton("打开开发者选项", (dialog, which) -> openDeveloperSettings())
                .setNegativeButton("取消", null)
                .show();
    }

    private void openDeveloperSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (ActivityNotFoundException error) {
            safeOpen(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception error) {
            Toast.makeText(this, "无法打开开发者选项：" + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void openOverlaySettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
        } else {
            intent = new Intent(Settings.ACTION_SETTINGS);
        }
        if (!safeOpen(intent)) safeOpen(new Intent(Settings.ACTION_SETTINGS));
    }

    private void openNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
        }
        if (!safeOpen(intent)) {
            safeOpen(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private boolean safeOpen(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception error) {
            Toast.makeText(this, "无法打开对应系统设置：" + safeMessage(error), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private EditText addNumberField(LinearLayout parent, String label, String hint, boolean signed) {
        TextView labelView = text(label, 14, Color.DKGRAY);
        labelView.setPadding(0, dp(8), 0, 0);
        parent.addView(labelView);
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        int flags = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) flags |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        input.setInputType(flags);
        parent.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private Switch addSwitch(LinearLayout parent, String label) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setTextSize(16);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
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

    private String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
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
