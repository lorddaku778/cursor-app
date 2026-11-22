package com.asjohn720.cursor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small trackpad in the top-right corner (the half of the screen that still has touch)
 * drives a cursor that can be anywhere on the whole screen. Taps, drags and swipes are
 * injected with dispatchGesture, so they work even where the digitizer is dead.
 *
 * Trackpad: slide = move cursor, tap = click, hold then slide = drag / swipe.
 */
public class CursorService extends AccessibilityService {

    static CursorService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;

    private int sw, sh;
    private float density;

    private CursorView cursorView;
    private LinearLayout pad;
    private TextView bubble;
    private WindowManager.LayoutParams cursorLp, padLp, bubbleLp;

    private float cx, cy;
    private boolean expanded = true;

    // drag state
    private boolean dragging, dragEnding, dragInFlight, dragFirst;
    private StrokeDescription dragStroke;
    private float dragLastX, dragLastY;

    // pad touch state
    private float downX, downY, lastX, lastY;
    private long downTime;
    private boolean moved;
    private float gainBase = 2f;
    private final Runnable longPress = new Runnable() {
        @Override public void run() { beginDrag(); }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        build();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (wm != null) {
            teardown();
            build();
        }
    }

    @Override
    public void onDestroy() {
        teardown();
        instance = null;
        super.onDestroy();
    }

    void toggle() {
        ui.post(new Runnable() {
            @Override public void run() { setExpanded(!expanded); }
        });
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) { return Math.round(v * density); }

    private WindowManager.LayoutParams lp(int w, int h, int x, int y, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = x;
        p.y = y;
        return p;
    }

    private void build() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        sw = m.widthPixels;
        sh = m.heightPixels;
        density = m.density;
        if (cx == 0 && cy == 0) { cx = sw / 2f; cy = sh * 0.75f; }
        cx = clamp(cx, 0, sw - 1);
        cy = clamp(cy, 0, sh - 1);

        int margin = dp(6);
        int padW = Math.min(Math.round(sw * 0.42f), dp(340));
        int padH = Math.min(sh / 2 - margin * 2, dp(240));
        int padX = sw - padW - margin;
        int padY = margin;
        gainBase = Math.max(1.6f, (sw / (float) padW) * 0.9f);

        // Cursor (never takes touches).
        cursorView = new CursorView();
        cursorLp = lp(dp(30), dp(30), Math.round(cx), Math.round(cy), false);
        wm.addView(cursorView, cursorLp);

        // Trackpad window: touch surface on top, button row underneath.
        pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6101010);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFF3A3A3A);
        pad.setBackground(bg);

        TextView surface = new TextView(this);
        surface.setText("TRACKPAD\nslide: move   tap: click\nhold + slide: drag / swipe");
        surface.setTextColor(0xFF6E6E6E);
        surface.setTextSize(11);
        surface.setGravity(Gravity.CENTER);
        surface.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return onPadTouch(e); }
        });
        pad.addView(surface, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        pad.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        addButton(row, "Back", new Runnable() { @Override public void run() { performGlobalAction(GLOBAL_ACTION_BACK); } });
        addButton(row, "Home", new Runnable() { @Override public void run() { performGlobalAction(GLOBAL_ACTION_HOME); } });
        addButton(row, "Up", new Runnable() { @Override public void run() { scroll(true); } });
        addButton(row, "Down", new Runnable() { @Override public void run() { scroll(false); } });
        addButton(row, "Click", new Runnable() { @Override public void run() { click(cx, cy); } });
        addButton(row, "Hide", new Runnable() { @Override public void run() { setExpanded(false); } });

        padLp = lp(padW, padH, padX, padY, true);
        wm.addView(pad, padLp);

        // Small bubble to bring the trackpad back.
        bubble = new TextView(this);
        bubble.setText("Cur");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(12);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bb = new GradientDrawable();
        bb.setShape(GradientDrawable.OVAL);
        bb.setColor(0xE6202020);
        bb.setStroke(dp(1), 0xFF5A5A5A);
        bubble.setBackground(bb);
        bubble.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setExpanded(true); }
        });
        bubbleLp = lp(dp(44), dp(44), sw - dp(44) - margin, margin, true);
        wm.addView(bubble, bubbleLp);

        setExpanded(expanded);
    }

    private void addButton(LinearLayout row, String label, final Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    }

    private void setExpanded(boolean on) {
        expanded = on;
        if (pad == null) return;
        pad.setVisibility(on ? View.VISIBLE : View.GONE);
        cursorView.setVisibility(on ? View.VISIBLE : View.GONE);
        bubble.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void teardown() {
        endDragNow();
        try { if (cursorView != null) wm.removeView(cursorView); } catch (Exception ignored) { }
        try { if (pad != null) wm.removeView(pad); } catch (Exception ignored) { }
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) { }
        cursorView = null; pad = null; bubble = null;
    }

    private class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        CursorView() {
            super(CursorService.this);
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(Color.BLACK);
            edge.setStrokeWidth(dp(1.5f));
            fill.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas c) {
            float s = getWidth() / 24f;
            arrow.reset();
            arrow.moveTo(2 * s, 1 * s);
            arrow.lineTo(2 * s, 19 * s);
            arrow.lineTo(6.5f * s, 14.8f * s);
            arrow.lineTo(9.6f * s, 21.5f * s);
            arrow.lineTo(12.2f * s, 20.3f * s);
            arrow.lineTo(9.2f * s, 13.8f * s);
            arrow.lineTo(15 * s, 13.8f * s);
            arrow.close();
            fill.setColor(dragging ? 0xFFFF9800 : Color.WHITE);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
        }
    }

    // ---------------------------------------------------------------- cursor + trackpad

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void moveCursor(float dx, float dy) {
        cx = clamp(cx + dx, 0, sw - 1);
        cy = clamp(cy + dy, 0, sh - 1);
        cursorLp.x = Math.round(cx);
        cursorLp.y = Math.round(cy);
        try { wm.updateViewLayout(cursorView, cursorLp); } catch (Exception ignored) { }
    }

    private boolean onPadTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getRawX();
                downY = lastY = e.getRawY();
                downTime = e.getEventTime();
                moved = false;
                ui.postDelayed(longPress, 450);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getRawX(), y = e.getRawY();
                float dx = x - lastX, dy = y - lastY;
                lastX = x; lastY = y;
                float slop = ViewConfiguration.get(this).getScaledTouchSlop();
                if (!moved && Math.hypot(x - downX, y - downY) > slop) {
                    moved = true;
                    if (!dragging) ui.removeCallbacks(longPress);
                }
                if (moved) {
                    double speed = Math.hypot(dx, dy);
                    float gain = gainBase * (0.5f + (float) Math.min(speed / 10.0, 1.5));
                    moveCursor(dx * gain, dy * gain);
                    if (dragging) pumpDrag(cx, cy);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPress);
                if (dragging) {
                    dragEnding = true;
                    pumpDrag(cx, cy);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                        && !moved && e.getEventTime() - downTime < 350) {
                    click(cx, cy);
                }
                return true;
        }
        return true;
    }

    // ---------------------------------------------------------------- gestures

    private boolean insidePad(float x, float y) {
        return expanded && pad != null
                && x >= padLp.x && x <= padLp.x + padLp.width
                && y >= padLp.y && y <= padLp.y + padLp.height;
    }

    /** Lets a gesture pass through the trackpad window if the cursor sits under it. */
    private void setPadTouchable(boolean touchable) {
        if (pad == null) return;
        if (touchable) padLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else padLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(pad, padLp); } catch (Exception ignored) { }
    }

    private void dispatchOnce(Path path, long duration, float refX, float refY) {
        final boolean passThrough = insidePad(refX, refY);
        if (passThrough) setPadTouchable(false);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new StrokeDescription(path, 0, duration)).build();
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { if (passThrough) setPadTouchable(true); }
            @Override public void onCancelled(GestureDescription d) { if (passThrough) setPadTouchable(true); }
        }, ui);
        if (!ok && passThrough) setPadTouchable(true);
    }

    private void click(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchOnce(p, 60, x, y);
    }

    private void scroll(boolean up) {
        float x = cx;
        float span = sh * 0.35f;
        // "Up" = show content above = finger moves down.
        float y1 = clamp(up ? cy - span / 2 : cy + span / 2, 5, sh - 5);
        float y2 = clamp(up ? cy + span / 2 : cy - span / 2, 5, sh - 5);
        Path p = new Path();
        p.moveTo(x, y1);
        p.lineTo(x, y2);
        dispatchOnce(p, 300, x, cy);
    }

    private void beginDrag() {
        if (moved || insidePad(cx, cy)) return;
        dragging = true;
        dragEnding = false;
        dragFirst = true;
        dragStroke = null;
        dragInFlight = false;
        dragLastX = cx;
        dragLastY = cy;
        if (cursorView != null) cursorView.invalidate();
        pumpDrag(cx, cy);
    }

    private float dragTx, dragTy;

    /** Sends the drag in short chained segments so the finger follows the pad in real time. */
    private void pumpDrag(float tx, float ty) {
        dragTx = tx;
        dragTy = ty;
        if (!dragging || dragInFlight) return;
        boolean end = dragEnding;
        if (!dragFirst && !end && dragTx == dragLastX && dragTy == dragLastY) return;

        Path p = new Path();
        p.moveTo(dragLastX, dragLastY);
        if (dragTx != dragLastX || dragTy != dragLastY) p.lineTo(dragTx, dragTy);
        StrokeDescription s = (dragStroke == null)
                ? new StrokeDescription(p, 0, 30, !end)
                : dragStroke.continueStroke(p, 0, 30, !end);
        dragStroke = s;
        dragLastX = dragTx;
        dragLastY = dragTy;
        dragFirst = false;
        dragInFlight = true;
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(s).build(),
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription d) {
                        dragInFlight = false;
                        if (dragEnding) { finishDrag(); } else { pumpDrag(dragTx, dragTy); }
                    }
                    @Override public void onCancelled(GestureDescription d) {
                        dragInFlight = false;
                        finishDrag();
                    }
                }, ui);
        if (!ok) { dragInFlight = false; finishDrag(); }
    }

    private void finishDrag() {
        dragging = false;
        dragEnding = false;
        dragStroke = null;
        if (cursorView != null) cursorView.invalidate();
    }

    private void endDragNow() {
        ui.removeCallbacks(longPress);
        finishDrag();
    }
}
