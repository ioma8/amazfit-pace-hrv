package com.hrv.hrv;

/** Thread-safe bounded PPG history shared by the sensor callback and analyzer. */
public final class HrvSamples {
    public static final class Snapshot {
        public final float[] values;
        public final long[] times;

        Snapshot(float[] values, long[] times) {
            this.values = values;
            this.times = times;
        }
    }

    private final float[] values;
    private final long[] times;
    private int size;
    private int next;

    public HrvSamples(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        values = new float[capacity];
        times = new long[capacity];
    }

    public synchronized void add(float value, long time) {
        values[next] = value;
        times[next] = time;
        next = (next + 1) % values.length;
        if (size < values.length) size++;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized Snapshot tail(int capacity) {
        int count = Math.min(size, Math.max(0, capacity));
        float[] resultValues = new float[count];
        long[] resultTimes = new long[count];
        int oldest = (next - size + values.length) % values.length;
        int first = (oldest + size - count) % values.length;
        for (int i = 0; i < count; i++) {
            int source = (first + i) % values.length;
            resultValues[i] = values[source];
            resultTimes[i] = times[source];
        }
        return new Snapshot(resultValues, resultTimes);
    }
}
