package com.hrv.files;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Environment;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import android.util.Log;

/** Simple sdcard browser for the round 320x300 screen: circular clip,
 *  drag-to-scroll list, tap a folder to enter, tap a file to see its size,
 *  back button goes up (exits at root). */
public class FilesView extends View {
    private static final String TAG = "Files";
    private final Paint bg = new Paint();
    private final Paint pathText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sep = new Paint();
    private final Paint dirGlyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fileGlyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nameText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metaText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusBg = new Paint();
    private final Paint statusText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selBg = new Paint();
    private final Path clip = new Path();
    private final RectF rect = new RectF();

    private final File root;
    private File cur;
    private File[] entries = new File[0];
    private float scroll = 0;
    private float itemH = 38;
    private String status = "";
    private boolean statusIsPath = true;
    private int downItem = -1;
    private float downY = 0, downX = 0, scrollStart = 0;
    private boolean moved = false;
    private boolean swiped = false;

    public FilesView(Context c, File root) {
        super(c);
        this.root = root;
        this.cur = root;
        bg.setColor(Color.rgb(8, 8, 12));
        pathText.setColor(Color.rgb(150, 160, 170));
        pathText.setTextAlign(Paint.Align.LEFT);
        pathText.setTextSize(14);
        sep.setColor(Color.rgb(35, 40, 48));
        sep.setStrokeWidth(2);
        dirGlyph.setColor(Color.rgb(230, 190, 60));
        fileGlyph.setColor(Color.rgb(120, 128, 138));
        nameText.setColor(Color.WHITE);
        nameText.setTextAlign(Paint.Align.LEFT);
        metaText.setColor(Color.rgb(110, 120, 130));
        metaText.setTextAlign(Paint.Align.RIGHT);
        statusBg.setColor(Color.argb(220, 16, 18, 24));
        statusText.setColor(Color.rgb(0, 230, 140));
        statusText.setTextAlign(Paint.Align.CENTER);
        selBg.setColor(Color.argb(60, 0, 230, 140));
        reload();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float unit = Math.min(w, h) / 300f;
        float r = Math.min(w, h) / 2f - 6 * unit;
        clip.reset();
        clip.addCircle(w / 2f, h / 2f, r, Path.Direction.CW);
        pathText.setTextSize(14 * unit);
        nameText.setTextSize(19 * unit);
        metaText.setTextSize(14 * unit);
        statusText.setTextSize(13 * unit);
        itemH = 38 * unit;
        sep.setStrokeWidth(2 * unit);
        dirGlyph.setStrokeWidth(3 * unit);
        fileGlyph.setStrokeWidth(2.5f * unit);
    }

    /** First existing, non-empty sdcard root (storage paths vary per build). */
    static File pickRoot() {
        File[] candidates = new File[]{
            Environment.getExternalStorageDirectory(),
            new File("/mnt/shell/emulated/0"),
            new File("/sdcard"),
            new File("/storage/sdcard0"),
            new File("/mnt/sdcard")};
        for (File f : candidates) {
            File[] l = f.listFiles();
            if (l != null && l.length > 0) {
                Log.i(TAG, "root=" + f);
                return f;
            }
        }
        return candidates[0];
    }

    void reload() {
        File[] list = cur.listFiles();
        if (list == null) list = new File[0];
        Arrays.sort(list, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                boolean ad = a.isDirectory(), bd = b.isDirectory();
                if (ad != bd) return ad ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        entries = list;
        scroll = 0;
        status = cur.getAbsolutePath();
        statusIsPath = true;
        Log.i(TAG, "browse " + cur.getAbsolutePath() + " n=" + entries.length);
        postInvalidate();
    }

    boolean up() {
        File p = cur.getParentFile();
        if (p == null || cur.equals(root)) return false;
        cur = p;
        reload();
        return true;
    }

    void tapItem(int index) {
        if (index < 0 || index >= entries.length) return;
        File f = entries[index];
        if (f.isDirectory()) {
            cur = f;
            reload();
        } else {
            status = f.getName() + "  " + fmt(f.length());
            statusIsPath = false;
            postInvalidate();
        }
    }

    static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float unit = Math.min(w, h) / 300f;
        float cx = w / 2f, cy = h / 2f;
        canvas.drawRect(0, 0, w, h, bg);
        canvas.save();
        canvas.clipPath(clip);

        // header: ellipsized path
        String path = shorten(cur.getAbsolutePath(), cx - 30 * unit, pathText);
        canvas.drawText(path, 24 * unit, 30 * unit, pathText);
        canvas.drawLine(16 * unit, 42 * unit, w - 16 * unit, 42 * unit, sep);

        float listTop = 46 * unit;
        float listBottom = cy + Math.min(w, h) / 2f - 6 * unit - 24 * unit;
        float maxScroll = Math.max(0, entries.length * itemH - (listBottom - listTop));
        if (scroll > maxScroll) scroll = maxScroll;

        int first = (int) (scroll / itemH);
        int last = (int) Math.min(entries.length - 1, (scroll + (listBottom - listTop)) / itemH);
        for (int i = first; i <= last && i < entries.length; i++) {
            float y = listTop + i * itemH - scroll;
            File f = entries[i];
            boolean dir = f.isDirectory();
            if (i == downItem) {
                rect.set(18 * unit, y, w - 18 * unit, y + itemH - 4 * unit);
                canvas.drawRoundRect(rect, 8 * unit, 8 * unit, selBg);
            }
            if (dir) {
                dirGlyph.setStyle(Paint.Style.FILL);
                rect.set(26 * unit, y + 12 * unit, 46 * unit, y + 28 * unit);
                canvas.drawRoundRect(rect, 5 * unit, 5 * unit, dirGlyph);
            } else {
                fileGlyph.setStyle(Paint.Style.STROKE);
                rect.set(28 * unit, y + 11 * unit, 45 * unit, y + 29 * unit);
                canvas.drawRoundRect(rect, 3 * unit, 3 * unit, fileGlyph);
            }
            String name = f.getName();
            nameText.setColor(dir ? Color.WHITE : Color.rgb(205, 210, 218));
            canvas.drawText(name, 58 * unit, y + 26 * unit, nameText);
            if (!dir) {
                canvas.drawText(fmt(f.length()), w - 24 * unit, y + 26 * unit, metaText);
            }
        }

        // status band at the bottom
        float sy = listBottom + 4 * unit;
        canvas.drawRect(0, sy - 4 * unit, w, h, statusBg);
        canvas.drawText(status, cx, sy + 15 * unit, statusText);
        canvas.restore();
    }

    String shorten(String s, float maxW, Paint p) {
        if (p.measureText(s) <= maxW) return s;
        while (s.length() > 4 && p.measureText("..." + s) > maxW) s = s.substring(1);
        return "..." + s;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float unit = Math.min(getWidth(), getHeight()) / 300f;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                scrollStart = scroll;
                moved = false;
                swiped = false;
                downItem = itemAt(e.getY(), unit);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (Math.abs(dx) > 40 * unit && Math.abs(dx) > 1.5f * Math.abs(dy)) swiped = true;
                if (Math.abs(dy) > 14 * unit) moved = true;
                if (!swiped && moved) scroll = Math.max(0, scrollStart - dy);
                postInvalidate();
                break;
            case MotionEvent.ACTION_UP:
                if (swiped && e.getX() - downX < 0) {
                    up();
                } else if (!moved && downItem >= 0) {
                    tapItem(downItem);
                }
                downItem = -1;
                postInvalidate();
                break;
        }
        return true;
    }

    int itemAt(float y, float unit) {
        float listTop = 46 * unit;
        float rel = y - listTop + scroll;
        int i = (int) (rel / itemH);
        return (i >= 0 && i < entries.length) ? i : -1;
    }
}
