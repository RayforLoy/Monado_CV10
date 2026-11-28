// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A child of the XR activity's own decor. System authorization windows always stay above it. */
final class Cv10ClientPhonePad implements ViewTreeObserver.OnWindowFocusChangeListener {
    private static final String TAG = "Cv10PhonePad";
    private final Activity owner;
    private final Context resources;
    private final ICv10PhonePad remote;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable retry = this::update;
    private boolean session, hidden, closed, pending, live;
    private int generation, held, attempts;
    private PortraitPad panel;
    private final Object moveLock = new Object();
    private float[] pendingMove;

    Cv10ClientPhonePad(Activity owner, Context resources, ICv10PhonePad remote) {
        this.owner = owner; this.resources = resources; this.remote = remote;
    }
    private View decor() { return owner.getWindow().getDecorView(); }
    void session(boolean active) {
        ui.post(() -> {
            if (closed || session == active) return;
            session = active;
            hidden = false;
            attempts = 0;
            if (active) decor().getViewTreeObserver().addOnWindowFocusChangeListener(this);
            else if (decor().getViewTreeObserver().isAlive()) decor().getViewTreeObserver().removeOnWindowFocusChangeListener(this);
            update();
        });
    }
    @Override public void onWindowFocusChanged(boolean focused) { if (!closed) update(); }
    private boolean eligible() {
        return session && !hidden && !owner.isFinishing() && !owner.isDestroyed()
                && decor().isAttachedToWindow() && decor().hasWindowFocus()
                && decor().getWindowVisibility() == View.VISIBLE;
    }
    private interface Call { void run() throws RemoteException; }
    private void send(Call call) {
        worker.execute(() -> {
            try { call.run(); } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Phone input connection ended", e);
            }
        });
    }
    private void remove() {
        if (panel != null && panel.getParent() instanceof ViewGroup) ((ViewGroup) panel.getParent()).removeView(panel);
        panel = null;
        held = 0;
    }
    private void update() {
        ui.removeCallbacks(retry);
        if (closed) return;
        if (!eligible()) {
            ++generation;
            if (live || pending) send(remote::hide);
            pending = live = false;
            remove();
            return;
        }
        if (pending || live) return;
        pending = true;
        final int epoch = ++generation;
        // The control face stays portrait even when a third-party app locks landscape.
        final int rotation = decor().getDisplay().getRotation() == Surface.ROTATION_180
                ? Surface.ROTATION_180 : Surface.ROTATION_0;
        worker.execute(() -> {
            boolean accepted;
            try { accepted = remote.activate(rotation); }
            catch (RemoteException | RuntimeException e) { accepted = false; }
            final boolean ready = accepted;
            ui.post(() -> {
                if (closed || generation != epoch) return;
                pending = false;
                if (!eligible()) { if (ready) send(remote::hide); return; }
                if (!ready) {
                    ui.postDelayed(retry, ++attempts < 8 ? 200L : 1500L);
                    return;
                }
                if (!(decor() instanceof ViewGroup)) { send(remote::hide); return; }
                live = true;
                panel = new PortraitPad();
                Cv10PhonePadBrightness.bind(panel, owner.getWindow());
                ((ViewGroup) decor()).addView(panel, new ViewGroup.LayoutParams(-1,-1));
                Log.i(TAG, "Portrait controller attached to " + owner.getComponentName().flattenToShortString());
            });
        });
    }
    void close() {
        ui.post(() -> {
            if (closed) return;
            closed = true;
            ui.removeCallbacks(retry);
            if (decor().getViewTreeObserver().isAlive()) decor().getViewTreeObserver().removeOnWindowFocusChangeListener(this);
            remove();
            send(remote::close);
            worker.shutdown();
        });
    }

    private int dp(int value) { return Math.round(value * owner.getResources().getDisplayMetrics().density); }
    private String label(int id) { return resources.getString(id); }
    private void setButton(int bit, boolean down) {
        int next = down ? held | bit : held & ~bit;
        if (next == held) return;
        held = next;
        send(() -> remote.buttons(next));
    }
    private void setTouch(boolean active, float x, float y, boolean transition) {
        // Keep edges in the same FIFO as select. A slow binder never accumulates stale moves.
        if (transition) {
            synchronized (moveLock) { pendingMove = null; }
            send(() -> remote.touch(active, x, y));
            return;
        }
        final float[] point;
        synchronized (moveLock) {
            if (pendingMove != null) {
                pendingMove[0] = x;
                pendingMove[1] = y;
                return;
            }
            pendingMove = point = new float[]{x, y};
        }
        send(() -> {
            synchronized (moveLock) {
                if (pendingMove != point) return;
                pendingMove = null;
            }
            remote.touch(true, point[0], point[1]);
        });
    }
    private Button action(int title, int bit) {
        Button b = new Button(owner);
        b.setText(label(title));
        b.setAllCaps(false);
        Cv10PhonePadSkin.style(b);
        if (bit != 0) {
            b.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: b.setPressed(true); setButton(bit,true); break;
                    case MotionEvent.ACTION_UP: b.setPressed(false); setButton(bit,false); b.performClick(); break;
                    case MotionEvent.ACTION_CANCEL: b.setPressed(false); setButton(bit,false); break;
                    default: break;
                }
                return true;
            });
            b.setOnClickListener(v -> {
                // Accessibility or keyboard activation has no MotionEvent.
                if (b.isAccessibilityFocused() || !b.isInTouchMode()) {
                    setButton(bit,true); setButton(bit,false);
                }
            });
        }
        return b;
    }

    private final class PortraitPad extends FrameLayout {
        final LinearLayout face;
        PortraitPad() {
            super(owner);
            setBackgroundColor(Cv10PhonePadSkin.BACKGROUND);
            setClickable(true);
            setElevation(dp(24));
            if (Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
            face = new LinearLayout(owner);
            face.setOrientation(LinearLayout.VERTICAL);
            face.setGravity(Gravity.CENTER);
            face.setPadding(dp(24),dp(32),dp(24),dp(32));
            TextView title = new TextView(owner);
            title.setText(label(R.string.cv10_pad_title));
            title.setTextColor(Cv10PhonePadSkin.TEXT); title.setGravity(Gravity.CENTER); title.setTextSize(27);
            face.addView(title,-1,-2);
            TextView hint = new TextView(owner);
            hint.setText(label(R.string.cv10_pad_hint));
            hint.setTextColor(Cv10PhonePadSkin.HINT); hint.setGravity(Gravity.CENTER); hint.setTextSize(15);
            face.addView(hint,-1,-2);
            Cv10PhoneTouchpad select = new Cv10PhoneTouchpad(owner, label(R.string.cv10_pad_tap),
                    label(R.string.cv10_pad_accessibility), new Cv10PhoneTouchpad.Input() {
                        @Override public void touch(boolean active, float x, float y, boolean edge) {
                            setTouch(active, x, y, edge);
                        }
                        @Override public void select(boolean pressed) { setButton(1, pressed); }
                    });
            LinearLayout.LayoutParams selectLayout = new LinearLayout.LayoutParams(-1,0,1);
            selectLayout.setMargins(0,dp(20),0,dp(12));
            face.addView(select,selectLayout);
            LinearLayout row = new LinearLayout(owner);
            row.addView(action(R.string.cv10_pad_menu,2),new LinearLayout.LayoutParams(0,dp(60),1));
            Button center = action(R.string.cv10_pad_center,0);
            center.setOnClickListener(v -> send(remote::recenter));
            row.addView(center,new LinearLayout.LayoutParams(0,dp(60),1));
            face.addView(row,-1,-2);
            Button hide = action(R.string.cv10_pad_hide,0);
            hide.setOnClickListener(v -> { hidden = true; update(); });
            face.addView(hide,-1,dp(56));
            addView(face);
        }
        @Override protected void onMeasure(int wm, int hm) {
            int w=MeasureSpec.getSize(wm), h=MeasureSpec.getSize(hm);
            setMeasuredDimension(w,h);
            face.measure(MeasureSpec.makeMeasureSpec(Math.min(w,h),MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(w,h),MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
            int w=right-left,h=bottom-top;
            face.layout(0,0,Math.min(w,h),Math.max(w,h));
            face.setPivotX(0); face.setPivotY(0);
            int rotation = getDisplay() == null ? 0 : getDisplay().getRotation();
            int degrees = w<=h ? 0 : (rotation==Surface.ROTATION_270 ? 90 : -90);
            face.setRotation(degrees);
            face.setTranslationX(degrees==90 ? w : 0);
            face.setTranslationY(degrees==-90 ? h : 0);
        }
    }
}
