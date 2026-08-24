package com.hrv.probe;

import java.util.ArrayList;
import java.util.List;

/** Thread-safe raw PPG history shared by the sensor callback and analyzer. */
public final class HrvSamples {
    public static final class Snapshot {
        public final float[] values;
        public final long[] times;
        Snapshot(float[] values, long[] times) { this.values = values; this.times = times; }
    }

    private final List<Float> values = new ArrayList<Float>();
    private final List<Long> times = new ArrayList<Long>();

    public synchronized void add(float value, long time) {
        values.add(value);
        times.add(time);
    }

    public synchronized int size() { return values.size(); }

    public synchronized Snapshot tail(int capacity) {
        int from = Math.max(0, values.size() - capacity);
        int count = values.size() - from;
        float[] resultValues = new float[count];
        long[] resultTimes = new long[count];
        for (int i = 0; i < count; i++) {
            resultValues[i] = values.get(from + i);
            resultTimes[i] = times.get(from + i);
        }
        return new Snapshot(resultValues, resultTimes);
    }
}
