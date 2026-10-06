package com.doom;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks DoomView's touch layout against the watch's visible area: the panel is
 * 320x300 but only the circle r=152 centred at (160,160) is lit, so every
 * control (and the game rect) has to fit inside it.
 *
 * The rects are read out of DoomView.java rather than copied here, so there is
 * one source of truth and this check cannot silently pass on a stale copy.
 *
 *   javac -d /tmp/x doom/test/com/doom/LayoutCheck.java
 *   java -cp /tmp/x com.doom.LayoutCheck doom/src/com/doom/DoomView.java
 */
public final class LayoutCheck {

    private static final int CX = 160, CY = 160, R = 152;
    private static final int PANEL_W = 320, PANEL_H = 300;

    private static final Pattern GAME = Pattern.compile(
            "FB_DST = new Rect\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\)");
    private static final Pattern BTN = Pattern.compile(
            "new Btn\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\w+),\\s*\"([^\"]*)\"\\)");

    private static int checks = 0;

    public static void main(String[] args) throws IOException {
        File src = new File(args.length > 0 ? args[0] : "doom/src/com/doom/DoomView.java");
        String text = read(src);

        int[] game = first(GAME, text, "FB_DST");
        List<int[]> buttons = all(BTN, text);
        check("all 9 controls parsed out of DoomView.java, found " + buttons.size(),
                buttons.size() >= 9);

        check("game rect is inside the lit circle", inside(game));
        for (int i = 0; i < buttons.size(); i++) {
            final int[] r = buttons.get(i);
            check("control " + i + " " + rect(r) + " is inside the lit circle", inside(r));
            check("control " + i + " is on the panel", onPanel(r));
        }
        for (int i = 0; i < buttons.size(); i++) {
            for (int j = i + 1; j < buttons.size(); j++) {
                check("controls " + i + " and " + j + " do not overlap",
                        !overlaps(buttons.get(i), buttons.get(j)));
            }
        }

        System.out.println("LayoutCheck checks passed (" + checks + ")");
    }

    /** Controls may sit over the game (translucent by design) but not each other. */
    private static boolean overlaps(int[] a, int[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    private static boolean onPanel(int[] r) {
        return r[0] >= 0 && r[1] >= 0 && r[2] <= PANEL_W && r[3] <= PANEL_H;
    }

    private static boolean inside(int[] r) {
        int[][] corners = {{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]}};
        for (int[] c : corners) {
            double dx = c[0] - CX;
            double dy = c[1] - CY;
            if (Math.sqrt(dx * dx + dy * dy) > R) {
                return false;
            }
        }
        return true;
    }

    private static int[] first(Pattern p, String text, String what) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            throw new AssertionError("could not parse " + what + " out of DoomView.java");
        }
        return rect(m);
    }

    private static List<int[]> all(Pattern p, String text) {
        List<int[]> out = new ArrayList<int[]>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(rect(m));
        }
        if (out.isEmpty()) {
            throw new AssertionError("could not parse any control out of DoomView.java");
        }
        return out;
    }

    private static int[] rect(Matcher m) {
        return new int[]{
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
    }

    private static String rect(int[] r) {
        return "(" + r[0] + "," + r[1] + ")-(" + r[2] + "," + r[3] + ")";
    }

    private static String read(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader in = new BufferedReader(new FileReader(f));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            in.close();
        }
        return sb.toString();
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAILED: " + what);
        }
    }
}
