package com.hrv.probe;

public final class HrvSamplesTest {
    public static void main(String[] args) {
        HrvSamples samples = new HrvSamples(3);
        samples.add(1.0f, 10L);
        samples.add(2.0f, 20L);
        require(samples.size() == 2, "initial size");
        assertSnapshot(samples.tail(3), new float[]{1, 2}, new long[]{10, 20});

        samples.add(3.0f, 30L);
        samples.add(4.0f, 40L);
        require(samples.size() == 3, "ring exceeded capacity");
        assertSnapshot(samples.tail(3), new float[]{2, 3, 4}, new long[]{20, 30, 40});
        assertSnapshot(samples.tail(2), new float[]{3, 4}, new long[]{30, 40});
        assertSnapshot(samples.tail(0), new float[]{}, new long[]{});
        System.out.println("HrvSamples checks passed");
    }

    private static void assertSnapshot(HrvSamples.Snapshot actual, float[] values, long[] times) {
        require(actual.values.length == values.length, "value length");
        require(actual.times.length == times.length, "time length");
        for (int i = 0; i < values.length; i++) {
            require(actual.values[i] == values[i], "value order at " + i);
            require(actual.times[i] == times[i], "time order at " + i);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
