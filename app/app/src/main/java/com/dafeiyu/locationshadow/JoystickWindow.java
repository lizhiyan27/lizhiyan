package com.dafeiyu.locationshadow;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/** A dependency-free overlay joystick drawn entirely in code. */
public final class JoystickWindow {
    public interface Listener {
        void onJoystickChanged(double eastMetersPerSecond, double northMetersPerSecond);
    }

    private static final int MODE_WALK = 0;
    private static final int MODE_RUN = 1;
    private static final int MODE_BIKE = 2;

    private final Context context;
    private final WindowManager windowManager;
    private final WindowManager.LayoutParams layoutParams;
    private final Listener listener;
    private final JoystickView joystickView;

    private boolean attached;
    private boolean showing;
    private boolean overlayWarningShown;
    private int speedMode = MODE_WALK;
    private int joystickType;
    private double walkSpeed;
    private double runSpeed;
    private double bikeSpeed;

    public JoystickWindow(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        joystickView = new JoystickView(this.context);

        layoutParams = new WindowManager.LayoutParams(
                dp(240), dp(286),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = dp(16);
        layoutParams.y = dp(120);
        readSpeedSettings();
    }

    /** Shows the overlay, or reports the missing overlay permission without throwing. */
    public void show() {
        if (!Settings.canDrawOverlays(context)) {
            emitVector(0.0d, 0.0d);
            joystickView.resetJoystick();
            showing = false;
            if (attached) {
                joystickView.setVisibility(View.GONE);
            }
            if (!overlayWarningShown) {
                showToast("无法显示悬浮摇杆：请在系统设置中允许本应用“显示在其他应用上层”");
                overlayWarningShown = true;
            }
            return;
        }
        overlayWarningShown = false;
        if (windowManager == null) {
            emitVector(0.0d, 0.0d);
            showToast("无法显示悬浮摇杆：系统窗口服务不可用");
            return;
        }
        try {
            if (!attached) {
                windowManager.addView(joystickView, layoutParams);
                attached = true;
            }
            joystickView.resetJoystick();
            joystickView.setVisibility(View.VISIBLE);
            showing = true;
            joystickView.invalidate();
        } catch (RuntimeException exception) {
            emitVector(0.0d, 0.0d);
            joystickView.resetJoystick();
            showing = false;
            showToast("悬浮摇杆显示失败：请检查“显示在其他应用上层”权限后重试");
        }
    }

    /** Hides only the joystick; it deliberately does not stop the location service. */
    public void hide() {
        emitVector(0.0d, 0.0d);
        joystickView.resetJoystick();
        showing = false;
        if (attached) {
            joystickView.setVisibility(View.GONE);
        }
    }

    /** Re-reads live speed and visibility preferences. */
    public void updateSettings() {
        readSpeedSettings();
        SharedPreferences prefs = Prefs.get(context);
        int newType = Prefs.getInt(prefs, Prefs.JOYSTICK_TYPE, 0) == 1 ? 1 : 0;
        if (joystickType != newType) {
            joystickType = newType;
            joystickView.resetJoystick();
        }
        joystickView.invalidate();
        if (prefs.getBoolean(Prefs.SHOW_JOYSTICK, true)) {
            show();
        } else {
            hide();
        }
    }

    public boolean isShowing() {
        return showing && attached && joystickView.getVisibility() == View.VISIBLE;
    }

    /** Removes the view and releases all window references owned by this instance. */
    public void release() {
        emitVector(0.0d, 0.0d);
        joystickView.resetJoystick();
        showing = false;
        if (attached && windowManager != null) {
            try {
                windowManager.removeViewImmediate(joystickView);
            } catch (RuntimeException ignored) {
                // The system may already have removed the overlay after permission revocation.
            }
        }
        attached = false;
    }

    private void readSpeedSettings() {
        SharedPreferences prefs = Prefs.get(context);
        walkSpeed = nonNegative(Prefs.getDouble(prefs, Prefs.WALK_SPEED, 1.2d));
        runSpeed = nonNegative(Prefs.getDouble(prefs, Prefs.RUN_SPEED, 3.6d));
        bikeSpeed = nonNegative(Prefs.getDouble(prefs, Prefs.BIKE_SPEED, 10.0d));
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0d, value) : 0.0d;
    }

    private double selectedSpeed() {
        switch (speedMode) {
            case MODE_RUN:
                return runSpeed;
            case MODE_BIKE:
                return bikeSpeed;
            case MODE_WALK:
            default:
                return walkSpeed;
        }
    }

    private void emitVector(double east, double north) {
        if (listener != null) {
            listener.onJoystickChanged(east, north);
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void showToast(final String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private final class JoystickView extends View {
        private static final int TOUCH_NONE = 0;
        private static final int TOUCH_WINDOW = 1;
        private static final int TOUCH_CLOSE = 2;
        private static final int TOUCH_SPEED = 3;
        private static final int TOUCH_DISK = 4;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF panelRect = new RectF();
        private int touchMode = TOUCH_NONE;
        private int touchedSpeedMode = -1;
        private int activeDirection;
        private float knobX;
        private float knobY;
        private float downRawX;
        private float downRawY;
        private int initialWindowX;
        private int initialWindowY;

        JoystickView(Context context) {
            super(context);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            panelRect.set(0.0f, 0.0f, getWidth(), getHeight());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(225, 25, 31, 40));
            canvas.drawRoundRect(panelRect, dp(20), dp(20), paint);

            drawHandle(canvas);
            drawClose(canvas);
            drawSpeedButtons(canvas);
            if (joystickType == 1) {
                drawDirectionButtons(canvas);
            } else {
                drawDirectionDisk(canvas);
            }
        }

        private void drawHandle(Canvas canvas) {
            RectF handle = handleBounds();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(150, 255, 255, 255));
            canvas.drawRoundRect(handle, dp(8), dp(8), paint);
            paint.setColor(Color.argb(220, 30, 38, 46));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(12));
            paint.setFakeBoldText(true);
            drawCenteredText(canvas, "拖动", handle, paint);
            paint.setFakeBoldText(false);
        }

        private void drawClose(Canvas canvas) {
            RectF close = closeBounds();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(220, 198, 66, 66));
            canvas.drawCircle(close.centerX(), close.centerY(), close.width() / 2.0f, paint);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(19));
            drawCenteredText(canvas, "×", close, paint);
        }

        private void drawSpeedButtons(Canvas canvas) {
            String[] labels = {"步", "跑", "车"};
            for (int mode = MODE_WALK; mode <= MODE_BIKE; mode++) {
                RectF bounds = speedBounds(mode);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(speedMode == mode
                        ? Color.rgb(0, 150, 170) : Color.argb(180, 69, 79, 91));
                canvas.drawRoundRect(bounds, dp(9), dp(9), paint);
                paint.setColor(Color.WHITE);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(dp(15));
                paint.setFakeBoldText(speedMode == mode);
                drawCenteredText(canvas, labels[mode], bounds, paint);
            }
            paint.setFakeBoldText(false);
        }

        private void drawDirectionDisk(Canvas canvas) {
            float centerX = diskCenterX();
            float centerY = diskCenterY();
            float radius = diskRadius();

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(210, 51, 64, 75));
            canvas.drawCircle(centerX, centerY, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(180, 255, 255, 255));
            canvas.drawCircle(centerX, centerY, radius, paint);

            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(90, 255, 255, 255));
            canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, paint);
            canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(17));
            paint.setColor(Color.argb(220, 255, 255, 255));
            canvas.drawText("↑", centerX, centerY - radius + dp(22), paint);
            canvas.drawText("↓", centerX, centerY + radius - dp(10), paint);
            canvas.drawText("←", centerX - radius + dp(17), centerY + dp(6), paint);
            canvas.drawText("→", centerX + radius - dp(17), centerY + dp(6), paint);

            float knobCenterX = centerX + knobX;
            float knobCenterY = centerY + knobY;
            paint.setColor(Color.rgb(45, 190, 225));
            canvas.drawCircle(knobCenterX, knobCenterY, dp(24), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.WHITE);
            canvas.drawCircle(knobCenterX, knobCenterY, dp(24), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawDirectionButtons(Canvas canvas) {
            for (int direction = 1; direction <= 4; direction++) {
                RectF bounds = directionBounds(direction);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(activeDirection == direction
                        ? Color.rgb(45, 190, 225) : Color.argb(220, 69, 84, 96));
                canvas.drawRoundRect(bounds, dp(12), dp(12), paint);
                paint.setColor(Color.WHITE);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(dp(22));
                drawCenteredText(canvas, directionLabel(direction), bounds, paint);
            }

            RectF stop = stopBounds();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(230, 117, 128, 138));
            canvas.drawCircle(stop.centerX(), stop.centerY(), stop.width() / 2.0f, paint);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(17));
            drawCenteredText(canvas, "停", stop, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (closeBounds().contains(x, y)) {
                        touchMode = TOUCH_CLOSE;
                    } else if (handleBounds().contains(x, y)) {
                        touchMode = TOUCH_WINDOW;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        initialWindowX = layoutParams.x;
                        initialWindowY = layoutParams.y;
                    } else {
                        int mode = speedModeAt(x, y);
                        if (mode >= 0) {
                            touchMode = TOUCH_SPEED;
                            touchedSpeedMode = mode;
                        } else if (joystickType == 1 && stopBounds().contains(x, y)) {
                            performClick();
                            resetJoystick();
                        } else if (joystickType == 1 && directionAt(x, y) != 0) {
                            touchMode = TOUCH_DISK;
                            updateDirectionButton(directionAt(x, y));
                        } else if (joystickType == 0 && isNearDisk(x, y)) {
                            touchMode = TOUCH_DISK;
                            updateKnob(x, y);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (touchMode == TOUCH_WINDOW) {
                        moveWindow(event);
                    } else if (touchMode == TOUCH_DISK) {
                        if (joystickType == 1) {
                            updateDirectionButton(directionAt(x, y));
                        } else {
                            updateKnob(x, y);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (touchMode == TOUCH_CLOSE && closeBounds().contains(x, y)) {
                        performClick();
                        hide();
                    } else if (touchMode == TOUCH_SPEED
                            && speedModeAt(x, y) == touchedSpeedMode) {
                        speedMode = touchedSpeedMode;
                        performClick();
                        invalidate();
                    }
                    if (touchMode == TOUCH_DISK) {
                        resetJoystick();
                    }
                    touchMode = TOUCH_NONE;
                    touchedSpeedMode = -1;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (touchMode == TOUCH_DISK) {
                        resetJoystick();
                    }
                    touchMode = TOUCH_NONE;
                    touchedSpeedMode = -1;
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private void moveWindow(MotionEvent event) {
            if (!attached || windowManager == null) {
                return;
            }
            layoutParams.x = initialWindowX + Math.round(event.getRawX() - downRawX);
            layoutParams.y = initialWindowY + Math.round(event.getRawY() - downRawY);
            try {
                windowManager.updateViewLayout(this, layoutParams);
            } catch (RuntimeException exception) {
                showToast("悬浮窗移动失败：请检查悬浮窗权限");
                hide();
            }
        }

        private void updateKnob(float x, float y) {
            float dx = x - diskCenterX();
            float dy = y - diskCenterY();
            float radius = diskRadius();
            float distance = (float) Math.hypot(dx, dy);
            if (distance > radius && distance > 0.0f) {
                float scale = radius / distance;
                dx *= scale;
                dy *= scale;
            }
            knobX = dx;
            knobY = dy;
            emitCurrentVector();
            invalidate();
        }

        private void emitCurrentVector() {
            float radius = diskRadius();
            if (radius <= 0.0f) {
                emitVector(0.0d, 0.0d);
                return;
            }
            double speed = selectedSpeed();
            double east = knobX / radius * speed;
            double north = -knobY / radius * speed;
            emitVector(east, north);
        }

        private void updateDirectionButton(int direction) {
            if (activeDirection == direction) {
                return;
            }
            activeDirection = direction;
            double speed = selectedSpeed();
            switch (direction) {
                case 1:
                    emitVector(0.0d, speed);
                    break;
                case 2:
                    emitVector(0.0d, -speed);
                    break;
                case 3:
                    emitVector(-speed, 0.0d);
                    break;
                case 4:
                    emitVector(speed, 0.0d);
                    break;
                default:
                    emitVector(0.0d, 0.0d);
                    break;
            }
            invalidate();
        }

        void resetJoystick() {
            boolean hadMovement = knobX != 0.0f || knobY != 0.0f || activeDirection != 0;
            knobX = 0.0f;
            knobY = 0.0f;
            activeDirection = 0;
            if (hadMovement) {
                emitVector(0.0d, 0.0d);
            }
            invalidate();
        }

        private boolean isNearDisk(float x, float y) {
            float dx = x - diskCenterX();
            float dy = y - diskCenterY();
            float touchRadius = diskRadius() + dp(24);
            return dx * dx + dy * dy <= touchRadius * touchRadius;
        }

        private int speedModeAt(float x, float y) {
            for (int mode = MODE_WALK; mode <= MODE_BIKE; mode++) {
                if (speedBounds(mode).contains(x, y)) {
                    return mode;
                }
            }
            return -1;
        }

        private int directionAt(float x, float y) {
            for (int direction = 1; direction <= 4; direction++) {
                if (directionBounds(direction).contains(x, y)) {
                    return direction;
                }
            }
            return 0;
        }

        private RectF directionBounds(int direction) {
            float centerX = diskCenterX();
            float centerY = diskCenterY();
            float radius = diskRadius();
            switch (direction) {
                case 1:
                    return new RectF(centerX - dp(28), centerY - radius,
                            centerX + dp(28), centerY - dp(38));
                case 2:
                    return new RectF(centerX - dp(28), centerY + dp(38),
                            centerX + dp(28), centerY + radius);
                case 3:
                    return new RectF(centerX - radius, centerY - dp(28),
                            centerX - dp(38), centerY + dp(28));
                case 4:
                default:
                    return new RectF(centerX + dp(38), centerY - dp(28),
                            centerX + radius, centerY + dp(28));
            }
        }

        private RectF stopBounds() {
            float centerX = diskCenterX();
            float centerY = diskCenterY();
            return new RectF(centerX - dp(27), centerY - dp(27),
                    centerX + dp(27), centerY + dp(27));
        }

        private static String directionLabel(int direction) {
            switch (direction) {
                case 1:
                    return "↑";
                case 2:
                    return "↓";
                case 3:
                    return "←";
                case 4:
                default:
                    return "→";
            }
        }

        private RectF handleBounds() {
            float centerX = getWidth() / 2.0f;
            return new RectF(centerX - dp(38), dp(9), centerX + dp(38), dp(39));
        }

        private RectF closeBounds() {
            return new RectF(getWidth() - dp(43), dp(7), getWidth() - dp(7), dp(43));
        }

        private RectF speedBounds(int mode) {
            float margin = dp(12);
            float gap = dp(6);
            float width = (getWidth() - margin * 2 - gap * 2) / 3.0f;
            float left = margin + mode * (width + gap);
            return new RectF(left, dp(49), left + width, dp(87));
        }

        private float diskCenterX() {
            return getWidth() / 2.0f;
        }

        private float diskCenterY() {
            return dp(190);
        }

        private float diskRadius() {
            return Math.min(dp(72), Math.max(dp(40), getHeight() - dp(204)));
        }
    }

    private static void drawCenteredText(Canvas canvas, String text, RectF bounds, Paint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2.0f;
        canvas.drawText(text, bounds.centerX(), baseline, paint);
    }
}
