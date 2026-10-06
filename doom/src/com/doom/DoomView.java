package com.doom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * 320x300 round panel. The visible area is a circle r=152 centred at (160,160)
 * (the panel's 300 px height clips its bottom), so the 320x200 Doom image is
 * scaled 0.8 into a centred 256x160 rect and the touch controls are placed in
 * the circle's corners around it — translucent, since a finger has to sit
 * somewhere and any full-width bar would be off-screen.
 */
public class DoomView extends View {

    /* Doom key codes from the engine's doomkeys.h. */
    private static final int K_ESC = 27, K_ENTER = 13, K_USE = 0xa2, K_RUN = 0x80 + 0x36;
    private static final int K_LEFT = 0xac, K_UP = 0xad, K_DOWN = 0xaf, K_RIGHT = 0xae;
    private static final int K_FIRE = 0xa3;

    private static final int FB_W = 320, FB_H = 200;

    static {
        System.loadLibrary("doom");
    }

    public static native void nativeStart(String wadPath, Bitmap fb, DoomView view);

    /** Doom's own frames per second, measured by the native render loop. */
    public static native int nativeFps();

    /** pressed: 1 = key down, 0 = key up. Called from the touch handler. */
    public static native void nativeKey(int pressed, int key);

    private static final class Btn {
        final RectF rect;
        final int key;
        final String text;

        Btn(float x0, float y0, float x1, float y1, int key, String text) {
            this.rect = new RectF(x0, y0, x1, y1);
            this.key = key;
            this.text = text;
        }
    }

    /** Game area: 320x200 scaled 0.8, corners still inside the circle. */
    private static final Rect FB_SRC = new Rect(0, 0, FB_W, FB_H);
    private static final Rect FB_DST = new Rect(32, 80, 288, 240);

    /* FPS readout. The only lit space that covers neither the game nor a button
       is the sliver above the top row, so it hugs the circle's top edge: 64x19
       at (128,12) keeps every corner inside r=152 (LayoutCheck asserts it), and
       "60fps" at 14 px is ~42 px wide, so it fits. Tap it to hide it again. */
    private static final Rect FPS_RECT = new Rect(128, 12, 192, 31);

    /* Menus only react to ENTER, so it gets its own button; RUN is hold. */
    private static final Btn[] BUTTONS = {
            new Btn(85, 32, 135, 70, K_ESC, "ESC"),
            new Btn(135, 32, 185, 70, K_ENTER, "ENT"),
            new Btn(185, 32, 235, 70, K_RUN, "RUN"),

            new Btn(46, 208, 90, 252, K_LEFT, "<"),
            new Btn(90, 186, 134, 230, K_UP, "^"),
            new Btn(90, 230, 134, 274, K_DOWN, "v"),
            new Btn(134, 208, 178, 252, K_RIGHT, ">"),

            new Btn(218, 196, 272, 250, K_FIRE, "FIRE"),
            new Btn(250, 140, 300, 180, K_USE, "USE"),
    };

    private final Bitmap fb = Bitmap.createBitmap(FB_W, FB_H, Bitmap.Config.ARGB_8888);
    private final SparseIntArray held = new SparseIntArray();
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint btnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fpsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean showFps = true;

    public DoomView(Context c, final String wadPath) {
        super(c);
        setFocusable(true);
        blit.setFilterBitmap(true);
        btnPaint.setColor(Color.argb(80, 255, 255, 255));
        textPaint.setColor(Color.argb(220, 255, 255, 255));
        textPaint.setTextSize(16);
        textPaint.setTextAlign(Paint.Align.CENTER);
        fpsPaint.setColor(Color.argb(230, 255, 235, 120));
        fpsPaint.setTextSize(14);
        fpsPaint.setTextAlign(Paint.Align.CENTER);
        setBackgroundColor(Color.BLACK);
        new Thread(new Runnable() {
            @Override public void run() {
                /* the launcher is greedy on this single core; same trick as
                   seismo's render thread */
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                nativeStart(wadPath, fb, DoomView.this);
            }
        }, "doom").start();
    }

    @Override protected void onDraw(Canvas c) {
        c.drawBitmap(fb, FB_SRC, FB_DST, blit);
        for (Btn b : BUTTONS) {
            c.drawRoundRect(b.rect, 6, 6, btnPaint);
            c.drawText(b.text, b.rect.centerX(),
                    b.rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
        }
        if (showFps) {
            String label = nativeFps() + "fps";
            c.drawText(label, FPS_RECT.centerX(),
                    FPS_RECT.centerY() - (fpsPaint.ascent() + fpsPaint.descent()) / 2, fpsPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int i = ev.getActionIndex();
            if (FPS_RECT.contains((int) ev.getX(i), (int) ev.getY(i))) {
                showFps = !showFps;
                postInvalidate();
                return true;
            }
            int key = keyAt(ev.getX(i), ev.getY(i));
            if (key != 0) {
                held.put(ev.getPointerId(i), key);
                nativeKey(1, key);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            release(ev.getPointerId(ev.getActionIndex()));
        } else if (action == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < held.size(); i++) {
                nativeKey(0, held.valueAt(i));
            }
            held.clear();
        }
        return true;
    }

    private void release(int pointerId) {
        int i = held.indexOfKey(pointerId);
        if (i >= 0) {
            nativeKey(0, held.valueAt(i));
            held.removeAt(i);
        }
    }

    private static int keyAt(float x, float y) {
        for (Btn b : BUTTONS) {
            if (b.rect.contains(x, y)) {
                return b.key;
            }
        }
        return 0;
    }
}
