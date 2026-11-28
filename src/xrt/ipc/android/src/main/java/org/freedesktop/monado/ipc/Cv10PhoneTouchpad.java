// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.TextView;

/** The phone's physical portrait pad, expressed as OpenXR coordinates (right/up positive). */
final class Cv10PhoneTouchpad extends TextView {
    interface Input {
        void touch(boolean active, float x, float y, boolean transition);
        void select(boolean pressed);
    }

    private final Input input;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int slop;
    private int finger = -1;
    private float downX, downY, x, y;
    private boolean tapCandidate, moveScheduled;
    private final Runnable sendMove = () -> {
        moveScheduled = false;
        if (finger != -1) publish(true, false);
    };

    Cv10PhoneTouchpad(Context context, String label, String description, Input input) {
        super(context);
        this.input = input;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setText(label);
        setContentDescription(description);
        setFocusable(true);
        setTextSize(19);
        setGravity(Gravity.CENTER);
        setTextColor(Cv10PhonePadSkin.TEXT);
        setBackground(Cv10PhonePadSkin.shell(this, 0xff19202a, 21));
        setClipToOutline(true);
        // Touch, keyboard and accessibility clicks all publish the same OpenXR edge.
        setOnClickListener(v -> {
            input.select(true);
            input.select(false);
        });
    }

    private int dp(int value) { return Cv10PhonePadSkin.dp(this, value); }

    private void publish(boolean active, boolean transition) {
        float nx = Math.min(1f, Math.max(-1f, 2f * x / Math.max(1, getWidth() - 1) - 1f));
        float ny = Math.min(1f, Math.max(-1f, 1f - 2f * y / Math.max(1, getHeight() - 1)));
        input.touch(active, active ? nx : 0f, active ? ny : 0f, transition);
    }

    private void finish() {
        removeCallbacks(sendMove);
        moveScheduled = false;
        if (finger != -1) publish(false, true);
        tapCandidate = false;
        finger = -1;
        setPressed(false);
        invalidate();
    }

    private void checkSlop(float sampleX, float sampleY) {
        float dx = sampleX - downX, dy = sampleY - downY;
        if (dx * dx + dy * dy > slop * slop) tapCandidate = false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            finish();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            finish();
            finger = event.getPointerId(0);
            x = downX = event.getX();
            y = downY = event.getY();
            tapCandidate = true;
            setPressed(true);
            publish(true, true);
        } else if (action == MotionEvent.ACTION_MOVE) {
            int i = event.findPointerIndex(finger);
            if (i < 0) return true;
            for (int h = 0; h < event.getHistorySize(); ++h)
                checkSlop(event.getHistoricalX(i, h), event.getHistoricalY(i, h));
            x = event.getX(i);
            y = event.getY(i);
            checkSlop(x, y);
            if (!moveScheduled) {
                moveScheduled = true;
                postOnAnimation(sendMove);
            }
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            tapCandidate = false;
        } else if (action == MotionEvent.ACTION_UP || (action == MotionEvent.ACTION_POINTER_UP
                && event.getPointerId(event.getActionIndex()) == finger)) {
            int i = event.getActionIndex();
            checkSlop(event.getX(i), event.getY(i));
            boolean tap = tapCandidate && action == MotionEvent.ACTION_UP
                    && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout();
            finish();
            if (tap) performClick();
        }
        invalidate();
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        finish();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        int step = dp(22), margin = dp(16);
        float blank = getPaint().measureText(getText().toString()) / 2f + dp(10);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff657282);
        for (float px = cx % step; px < getWidth() - margin; px += step) {
            if (px < margin) continue;
            for (float py = cy % step; py < getHeight() - margin; py += step) {
                if (py >= margin && (Math.abs(px - cx) > blank || Math.abs(py - cy) > dp(18)))
                    canvas.drawCircle(px, py, dp(1), paint);
            }
        }
        if (finger != -1) {
            paint.setColor(0x455b98f3);
            canvas.drawCircle(x, y, dp(23), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xff88b5ff);
            canvas.drawCircle(x, y, dp(18), paint);
        }
        super.onDraw(canvas);
    }
}
