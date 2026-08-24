package com.breathe.probe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Cyclic sighing (physiological sigh) — the best-validated breathing pattern for
 * acute stress reduction (Balban et al. 2023, Cell Reports Medicine, PMID 36630953).
 * RCT: beat box breathing, cyclic hyperventilation and mindfulness on mood/arousal.
 * Pattern: inhale (nose) -> short top-up inhale (nose) -> long slow exhale (mouth),
 * ~1:2 inhale:exhale ratio. This pacer: 2s + 2s + 8s, 25 sighs (~5 min, as studied).
 */
public class MainActivity extends Activity {
    private static final int INHALE = 2000;
    private static final int TOP_UP = 2000;
    private static final int SIGH = 8000;
    private static final int CYCLE_MS = INHALE + TOP_UP + SIGH;
    private static final int CYCLES = 25;

    private final Handler handler = new Handler();
    private TextView headerText, phaseText, countText, cycleText;
    private long elapsed;
    private long lastTick;
    private boolean running = true;
    private int lastPhase = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(18, 22, 26));
        headerText = text(14, Color.rgb(128, 203, 196));
        headerText.setText("Cyclic sighing · 2-2-8");
        phaseText = text(34, Color.WHITE);
        countText = text(80, Color.rgb(128, 203, 196));
        cycleText = text(15, Color.LTGRAY);
        root.addView(headerText);
        root.addView(phaseText);
        root.addView(countText);
        root.addView(cycleText);
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        setContentView(root);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        lastTick = 0;
        finish();
        Process.killProcess(Process.myPid());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (running) {
            lastTick = 0;
            handler.post(tick);
        }
    }

    private void toggle() {
        if (running) {
            running = false;
            handler.removeCallbacks(tick);
            lastTick = 0;
            phaseText.setText("Paused");
        } else if (elapsed >= CYCLES * CYCLE_MS) {
            elapsed = 0;
            lastPhase = -1;
            running = true;
            handler.post(tick);
        } else {
            running = true;
            lastTick = 0;
            handler.post(tick);
        }
    }

    private void update() {
        long now = System.currentTimeMillis();
        if (lastTick > 0) elapsed += now - lastTick;
        lastTick = now;
        if (elapsed >= CYCLES * CYCLE_MS) {
            lastPhase = 99;
            phaseText.setText("Done");
            countText.setText("");
            cycleText.setText(CYCLES + " sighs · tap to restart");
            handler.removeCallbacks(tick);
            running = false;
            return;
        }
        int ms = (int) (elapsed % CYCLE_MS);
        int cycle = (int) (elapsed / CYCLE_MS) + 1;
        int phase = ms < INHALE ? 0 : ms < INHALE + TOP_UP ? 1 : 2;
        int remain = phase == 0 ? INHALE - ms
                : phase == 1 ? INHALE + TOP_UP - ms : CYCLE_MS - ms;
        lastPhase = phase;
        phaseText.setText(phase == 0 ? "Breathe in" : phase == 1 ? "Top up" : "Sigh out");
        countText.setText(String.valueOf((int) Math.ceil(remain / 1000.0)));
        cycleText.setText("sigh " + cycle + " / " + CYCLES);
    }

    private TextView text(float size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }
}
