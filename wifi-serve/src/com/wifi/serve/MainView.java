package com.wifi.serve;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Round-display UI. Phase JOIN shows the WIFI: QR (scan to connect); phase
 * OPEN shows the URL QR (scan to open the served page in the phone browser).
 * Tapping the screen toggles between the phases (manual fallback).
 */
public class MainView extends View {
    public static final int PHASE_JOIN = 0;
    public static final int PHASE_OPEN = 1;

    private int phase = PHASE_JOIN;
    private Bitmap joinQr;
    private Bitmap urlQr;
    private String url = "";
    private String status = "starting";
    private int fileCount = -1;

    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MainView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        float d = getResources().getDisplayMetrics().density;
        title.setColor(Color.WHITE);
        title.setTextSize(15 * d);
        title.setTextAlign(Paint.Align.CENTER);
        body.setColor(Color.WHITE);
        body.setTextSize(14 * d);
        body.setTextAlign(Paint.Align.CENTER);
        sub.setColor(0xFF9AA5AD);
        sub.setTextSize(11 * d);
        sub.setTextAlign(Paint.Align.CENTER);
    }

    public void setPhase(int p) {
        phase = p;
        invalidate();
    }

    public int getPhase() {
        return phase;
    }

    public void togglePhase() {
        setPhase(phase == PHASE_JOIN ? PHASE_OPEN : PHASE_JOIN);
    }

    public void setJoinQr(Bitmap b) {
        joinQr = b;
        invalidate();
    }

    public void setUrlQr(Bitmap b) {
        urlQr = b;
        invalidate();
    }

    public void setUrl(String u) {
        url = u;
        invalidate();
    }

    public void setStatus(String s) {
        status = s;
        invalidate();
    }

    public void setFileCount(int n) {
        fileCount = n;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        Bitmap qr = phase == PHASE_JOIN ? joinQr : urlQr;

        cv.drawText(phase == PHASE_JOIN ? "1 · scan to connect" : "2 · open in browser",
                w / 2f, 24 * d, title);

        if (qr != null) {
            int size = Math.min(w, h) - 120; // 180 on the 320x300 panel
            int left = (w - size) / 2;
            int top = (int) (34 * d);
            cv.drawBitmap(qr, null, new android.graphics.Rect(left, top, left + size, top + size), null);
        }

        float y = 34 * d + (qr != null ? Math.min(w, h) - 120 : 0) + 12 * d;
        if (phase == PHASE_JOIN) {
            cv.drawText(ApManager.SSID + " · " + ApManager.PASS, w / 2f, y, body);
            cv.drawText(status, w / 2f, y + 18 * d, sub);
        } else {
            cv.drawText(url, w / 2f, y, body);
            if (fileCount >= 0) {
                cv.drawText(fileCount + " recordings ready", w / 2f, y + 18 * d, sub);
            }
        }
    }
}
