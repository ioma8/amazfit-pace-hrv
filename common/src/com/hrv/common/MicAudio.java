package com.hrv.common;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * AudioRecord factory for the probe convention: 16 kHz mono PCM16 — the only
 * rate the watch dmic clocks correctly (MIC-FINDINGS.md). Pure setup helper;
 * the read loop stays in each app.
 */
public final class MicAudio {
    public static final int FS = 16000;

    private MicAudio() {
    }

    /** Recorder with at least chunkBytes of buffering, or null on failure. */
    public static AudioRecord open(int chunkBytes) {
        int min = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, FS,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, chunkBytes));
        return ar.getState() == AudioRecord.STATE_INITIALIZED ? ar : null;
    }
}
