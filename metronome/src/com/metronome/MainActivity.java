package com.metronome;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hrv.common.ProbeActivity;

/** Vibration metronome: 30-240 BPM, presets, first beat of each 4/4 bar accented. */
public class MainActivity extends ProbeActivity {
    private static final int MIN_BPM = 30;
    private static final int MAX_BPM = 240;
    private static final int[][] PRESETS = {
            {60, 80, 100, 120},
            {140, 160, 180, 200},
    };

    private final Handler handler = new Handler();
    private TextView bpmText, stateText;
    private Vibrator vibrator;
    private Button startButton;
    private int bpm = 90;
    private boolean running;
    private long nextBeat;
    private int beatInBar;

    private final Runnable beat = new Runnable() {
        @Override public void run() {
            playBeat();
            nextBeat += 60000L / bpm;
            handler.postDelayed(this, Math.max(0, nextBeat - System.currentTimeMillis()));
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hardKillOnPause();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(18, 22, 26));

        bpmText = new TextView(this);
        bpmText.setTextSize(56);
        bpmText.setTextColor(Color.WHITE);
        bpmText.setGravity(Gravity.CENTER);
        root.addView(bpmText, new LinearLayout.LayoutParams(-1, -2));

        stateText = new TextView(this);
        stateText.setTextSize(14);
        stateText.setTextColor(Color.LTGRAY);
        stateText.setGravity(Gravity.CENTER);
        root.addView(stateText, new LinearLayout.LayoutParams(-1, -2));

        for (int[] row : PRESETS) addPresetRow(root, row);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button("-"), new LinearLayout.LayoutParams(70, 46));
                startButton = button("Start");
        buttons.addView(startButton, new LinearLayout.LayoutParams(120, 46));
        buttons.addView(button("+"), new LinearLayout.LayoutParams(70, 46));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        render();
    }

    @Override
    protected void onExitCleanup() {
        stop();
    }

    private void addPresetRow(LinearLayout root, int[] values) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (final int value : values) {
                       Button b = new Button(this);
            b.setMinimumHeight(0);
            b.setMinHeight(0);
            b.setText(String.valueOf(value));
            b.setTextSize(12);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setBpm(value); }
            });
                        row.addView(b, new LinearLayout.LayoutParams(72, 44));
        }
        root.addView(row);
    }

    private Button button(final String label) {
               Button b = new Button(this);
        b.setMinimumHeight(0);
        b.setMinHeight(0);
        b.setText(label);
        b.setTextSize(13);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onButton(label); }
        });
        return b;
    }

    private void onButton(String label) {
        if ("-".equals(label)) setBpm(bpm - 1);
        else if ("+".equals(label)) setBpm(bpm + 1);
        else {
            if (running) stop();
            else start();
        }
        render();
    }

    private void setBpm(int value) {
        bpm = Math.min(MAX_BPM, Math.max(MIN_BPM, value));
        if (running) nextBeat = System.currentTimeMillis() + 60000L / bpm;
        render();
    }

    private void start() {
        running = true;
        beatInBar = 0;
        nextBeat = System.currentTimeMillis() + 60000L / bpm;
        handler.post(beat);
    }

    private void stop() {
        running = false;
        handler.removeCallbacks(beat);
    }

    private void playBeat() {
        beatInBar = (beatInBar % 4) + 1;
        if (vibrator != null) {
            try {
                vibrator.vibrate(new long[]{0, beatInBar == 1 ? 90 : 30}, -1);
            } catch (Throwable ignored) { }
        }
    }

    private void render() {
        bpmText.setText(String.valueOf(bpm));
        stateText.setText(running ? "running · accent 1/4" : "stopped");
        startButton.setText(running ? "Stop" : "Start");
    }
}
