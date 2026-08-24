package com.hrv.probe;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public final class HrvAnalyzerTest {
    public static void main(String[] args) throws Exception {
        String capture = args.length == 0 ? "captures/raw_ppg.csv" : args[0];
        capturedPpgMatchesVisiblePulseTiming(capture);
        syntheticSteadyAndVariablePulseStayDistinct();
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
        HrvAnalyzer.Result result = HrvAnalyzer.analyze(floats(values), longs(times));
        require(result != null, "captured PPG was rejected");
        require(result.cleanCount == 81 && result.totalCount == 81,
            "captured beat count changed: " + result.cleanCount + "/" + result.totalCount);
        between(result.hr, 82.5f, 84.0f, "captured HR");
        between(result.rmssdMs, 37.0f, 43.0f, "captured RMSSD");
        between(result.sdnnMs, 67.0f, 74.0f, "captured SDNN");
        between(result.score, 35.0f, 46.0f, "captured coherence score");
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

    private static void shortWindowProducesMetrics() {
        HrvAnalyzer.Result result = synthetic(340, 5.0, false);
        require(result != null, "12-second warmup window produced no metrics");
    }

    private static HrvAnalyzer.Result synthetic(int count, double modulation, boolean addSpikes) {
        float[] values = new float[count];
        long[] times = new long[count];
        double fs = 25.4;
        double phase = 0.0;
        for (int i = 0; i < count; i++) {
            times[i] = (long) (i * 1_000_000_000.0 / fs);
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
