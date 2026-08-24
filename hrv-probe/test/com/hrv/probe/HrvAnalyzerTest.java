package com.hrv.probe;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public final class HrvAnalyzerTest {
    public static void main(String[] args) throws Exception {
        String capture = args.length == 0 ? "captures/raw_ppg.csv" : args[0];
        capturedPpgMatchesVisiblePulseTiming(capture);
        capturedMetricsIgnoreOpticalNuisance(capture);
        syntheticSteadyAndVariablePulseStayDistinct();
        burstyCallbacksDoNotInflateHrv();
        shortWindowProducesMetrics();
        System.out.println("HrvAnalyzer checks passed");
    }

    private static void capturedPpgMatchesVisiblePulseTiming(String path) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(path));
        reader.readLine();
        List<Float> values = new ArrayList<Float>();
        List<Long> times = new ArrayList<Long>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] fields = line.split(",", 5);
            values.add(Float.valueOf(fields[2]));
            times.add(Long.valueOf(fields[1]));
        }
        reader.close();
        float[] captured = floats(values);
        long[] capturedTimes = longs(times);
        HrvAnalyzer.Result result = HrvAnalyzer.analyze(captured, capturedTimes);
        require(result != null, "captured PPG was rejected");
        require(result.cleanCount == 81 && result.totalCount == 81,
            "captured beat count changed: " + result.cleanCount + "/" + result.totalCount);
        between(result.hr, 83.0f, 83.6f, "captured HR");
        between(result.rmssdMs, 41.0f, 43.0f, "captured RMSSD");
        between(result.sdnnMs, 70.0f, 72.5f, "captured SDNN");
        between(result.score, 61.0f, 67.0f, "captured coherence score");

        float minimumScore = Float.POSITIVE_INFINITY;
        float maximumScore = Float.NEGATIVE_INFINITY;
        for (int from = 0; from <= 105; from += 35) {
            float[] windowValues = new float[1400];
            long[] windowTimes = new long[1400];
            System.arraycopy(captured, from, windowValues, 0, 1400);
            System.arraycopy(capturedTimes, from, windowTimes, 0, 1400);
            HrvAnalyzer.Result window = HrvAnalyzer.analyze(windowValues, windowTimes);
            require(window != null && window.score > 0.0f, "sliding score window was rejected");
            minimumScore = Math.min(minimumScore, window.score);
            maximumScore = Math.max(maximumScore, window.score);
        }
        require(maximumScore - minimumScore < 8.0f,
            "coherence score depends on sliding-window phase: " + minimumScore + ".." + maximumScore);
    }

    private static void capturedMetricsIgnoreOpticalNuisance(String path) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(path));
        reader.readLine();
        List<Float> sourceValues = new ArrayList<Float>();
        List<Long> sourceTimes = new ArrayList<Long>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] fields = line.split(",", 5);
            sourceValues.add(Float.valueOf(fields[2]));
            sourceTimes.add(Long.valueOf(fields[1]));
        }
        reader.close();
        float[] original = floats(sourceValues);
        long[] times = longs(sourceTimes);
        HrvAnalyzer.Result reference = HrvAnalyzer.analyze(original, times);

        float[] transformed = original.clone();
        for (int i = 0; i < transformed.length; i++) {
            transformed[i] = (float) (0.73 * transformed[i] + 1200.0
                + 350.0 * Math.sin(2.0 * Math.PI * i / (25.2 * 12.0)));
        }
        HrvAnalyzer.Result stable = HrvAnalyzer.analyze(transformed, times);
        require(reference != null && stable != null, "baseline-transformed capture was rejected");
        require(stable.cleanCount == reference.cleanCount, "baseline drift changed clean beat count");
        require(Math.abs(stable.hr - reference.hr) < 0.1f, "baseline drift changed HR");
        require(Math.abs(stable.rmssdMs - reference.rmssdMs) < 0.7f, "baseline drift changed RMSSD");

        float[] corrupted = original.clone();
        int[] spikeAt = new int[]{215, 500, 900, 1200};
        for (int index : spikeAt) corrupted[index] += 1200.0f;
        HrvAnalyzer.Result guarded = HrvAnalyzer.analyze(corrupted, times);
        require(guarded != null, "artifact capture was rejected entirely");
        require(Math.abs(guarded.hr - reference.hr) < 1.0f, "optical spikes changed HR");
        require(Math.abs(guarded.rmssdMs - reference.rmssdMs) < 10.0f, "optical spikes inflated RMSSD");
        require(guarded.cleanCount < reference.cleanCount, "optical spikes were not marked");

        float[] unusable = original.clone();
        for (int i = 400; i < 1000; i++) unusable[i] = original[400];
        require(HrvAnalyzer.analyze(unusable, times) == null,
            "severely corrupted PPG produced trusted metrics");
    }

    private static void syntheticSteadyAndVariablePulseStayDistinct() {
        HrvAnalyzer.Result steady = synthetic(1400, 0.0, false);
        HrvAnalyzer.Result variable = synthetic(1400, 7.0, true);
        require(steady != null && variable != null, "synthetic pulse rejected");
        between(steady.hr, 70.0f, 74.0f, "steady HR");
        require(steady.rmssdMs < 8.0f, "steady RMSSD inflated: " + steady.rmssdMs);
        between(variable.hr, 70.0f, 75.0f, "variable HR");
        require(variable.rmssdMs > steady.rmssdMs + 15.0f,
            "real variability was suppressed: " + variable.rmssdMs);
        require(variable.score >= 0.0f && variable.score <= 100.0f, "score outside 0..100");
    }
    private static void burstyCallbacksDoNotInflateHrv() {
        HrvAnalyzer.Result uniform = synthetic(800, 7.0, false, false);
        HrvAnalyzer.Result bursty = synthetic(800, 7.0, false, true);
        require(uniform != null && bursty != null, "bursty callback simulation was rejected");
        require(Math.abs(bursty.hr - uniform.hr) < 0.2f, "callback batching changed HR");
        require(Math.abs(bursty.rmssdMs - uniform.rmssdMs) < 0.5f, "callback batching changed RMSSD");
    }


    private static void shortWindowProducesMetrics() {
        HrvAnalyzer.Result result = synthetic(340, 5.0, false);
        require(result != null, "12-second warmup window produced no metrics");
    }

    private static HrvAnalyzer.Result synthetic(int count, double modulation, boolean addSpikes) {
        return synthetic(count, modulation, addSpikes, false);
    }

    private static HrvAnalyzer.Result synthetic(int count, double modulation, boolean addSpikes, boolean bursty) {
        float[] values = new float[count];
        long[] times = new long[count];
        double fs = 25.4;
        double phase = 0.0;
        for (int i = 0; i < count; i++) {
            times[i] = bursty
                ? (long) ((i / 5) * 5 * 1_000_000_000.0 / fs + (i % 5) * 300_000.0)
                : (long) (i * 1_000_000_000.0 / fs);
            double hr = 72.0 + modulation * Math.sin(2.0 * Math.PI * i / (fs * 10.0));
            phase += 2.0 * Math.PI * hr / (60.0 * fs);
            double pulse = 900.0 * Math.max(0.0, Math.sin(phase)) + 70.0 * Math.sin(2.0 * phase);
            double drift = 250.0 * Math.sin(2.0 * Math.PI * i / (fs * 18.0));
            double spike = addSpikes && (i == 430 || i == 910) ? 1200.0 : 0.0;
            values[i] = (float) (34000.0 + pulse + drift + 12.0 * Math.sin(i * 0.7) + spike);
        }
        return HrvAnalyzer.analyze(values, times);
    }

    private static float[] floats(List<Float> source) {
        float[] result = new float[source.size()];
        for (int i = 0; i < result.length; i++) result[i] = source.get(i);
        return result;
    }

    private static long[] longs(List<Long> source) {
        long[] result = new long[source.size()];
        for (int i = 0; i < result.length; i++) result[i] = source.get(i);
        return result;
    }

    private static void between(float value, float low, float high, String name) {
        require(value >= low && value <= high, name + "=" + value + " outside " + low + ".." + high);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
