package com.hrv.hrv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure 25 Hz PPG beat extraction and emwave-compatible resonance scoring. */
public final class HrvAnalyzer {
    private static final int[] SMOOTH_KERNEL = new int[]{1, 6, 15, 20, 15, 6, 1};
    private static final double REFRACTORY_SECONDS = 0.28;
    private HrvAnalyzer() {}

    public static final class Result {
        public final float hr;
        public final float rmssdMs;
        public final float sdnnMs;
        public final float score;
        public final boolean scoreAvailable;
        public final int cleanCount;
        public final int totalCount;

        Result(double hr, double rmssd, double sdnn, double score,
               boolean scoreAvailable, int clean, int total) {
            this.hr = (float) hr;
            this.rmssdMs = (float) (rmssd * 1000.0);
            this.sdnnMs = (float) (sdnn * 1000.0);
            this.score = (float) score;
            this.scoreAvailable = scoreAvailable;
            this.cleanCount = clean;
            this.totalCount = total;
        }
    }

    public static Result analyze(float[] values, long[] times) {
        int n = values.length;
        if (n < 305 || times.length != n) return null; // 12s at the measured 25.4Hz
        double dt = uniformSamplePeriod(times);
        double fs = 1.0 / dt;
        if (fs < 24.0 || fs > 27.0) return null;

        double[] filtered = preprocess(values, fs);
        double noise = robustNoise(filtered);
        if (noise < 1.0) return null;
        List<Double> peaks = findPeaks(filtered, fs, noise, dt);
        if (peaks.size() < 12) return null;
        List<Boolean> peakQuality = morphologyQuality(filtered, peaks);

        List<Double> ibis = new ArrayList<Double>();
        for (int i = 1; i < peaks.size(); i++) ibis.add((peaks.get(i) - peaks.get(i - 1)) * dt);
        CleanIntervals intervals = cleanIntervals(ibis, peakQuality);
        double detectedSpan = 0.0;
        for (double ibi : ibis) detectedSpan += ibi;
        int expectedIntervals = (int) Math.round(detectedSpan / median(ibis));
        int minimumClean = Math.max(10, (int) Math.ceil(expectedIntervals * 0.70));
        if (intervals.validCount < minimumClean) return null;

        double mean = 0.0;
        for (int i = 0; i < intervals.values.size(); i++) {
            if (intervals.valid.get(i)) mean += intervals.values.get(i);
        }
        mean /= intervals.validCount;
        double sdnn = 0.0;
        for (int i = 0; i < intervals.values.size(); i++) {
            if (intervals.valid.get(i)) {
                double d = intervals.values.get(i) - mean;
                sdnn += d * d;
            }
        }
        sdnn = Math.sqrt(sdnn / Math.max(1, intervals.validCount - 1));
        double rmssd = 0.0;
        int pairs = 0;
        for (int i = 1; i < intervals.values.size(); i++) {
            // Never bridge an invalid beat: that turns one missed/dicrotic beat
            // into a false, very large NN difference.
            if (intervals.valid.get(i - 1) && intervals.valid.get(i)) {
                double d = intervals.values.get(i) - intervals.values.get(i - 1);
                rmssd += d * d;
                pairs++;
            }
        }
        int minimumPairs = Math.max(8, (int) Math.ceil((intervals.values.size() - 1) * 0.55));
        if (pairs < minimumPairs) return null;
        rmssd = Math.sqrt(rmssd / pairs);

        // emwave-utils: LF normalized power multiplied by log-scaled LF peak
        // concentration. This is resonance strength, not an RMSSD map.
        ScoreResult resonance = resonanceScore(intervals.values, intervals.valid);
        double score = Math.max(0.0, Math.min(100.0, resonance.value));
        return new Result(60.0 / mean, rmssd, sdnn, score, resonance.available,
            intervals.validCount, ibis.size());
    }

    private static final class CleanIntervals {
        final List<Double> values;
        final List<Boolean> valid;
        int validCount;
        CleanIntervals(List<Double> values, List<Boolean> valid) {
            this.values = values;
            this.valid = valid;
        }
    }

    private static double uniformSamplePeriod(long[] times) {
        int n = times.length;
        double meanIndex = (n - 1) / 2.0;
        double numerator = 0.0;
        double denominator = 0.0;
        long origin = times[0];
        for (int i = 0; i < n; i++) {
            double x = i - meanIndex;
            double elapsed = (times[i] - origin) / 1e9;
            numerator += x * elapsed;
            denominator += x * x;
        }
        return numerator / denominator;
    }

    private static double[] preprocess(float[] values, double fs) {
        int n = values.length;
        int span = Math.max(9, (int) Math.round(1.6 * fs)) | 1;
        int half = span / 2;
        double[] highPass = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0.0;
            for (int j = -half; j <= half; j++) sum += values[reflect(i + j, n)];
            highPass[i] = values[i] - sum / span;
        }

        // Symmetric seven-point binomial smoother: zero phase, enough noise
        // suppression for 25 Hz without flattening the optical apex.
        double[] smooth = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0.0;
            for (int j = -3; j <= 3; j++) {
                sum += SMOOTH_KERNEL[j + 3] * highPass[reflect(i + j, n)];
            }
            smooth[i] = sum / 64.0;
        }
        return smooth;
    }

    private static int reflect(int index, int size) {
        if (index < 0) return -index;
        if (index >= size) return 2 * size - index - 2;
        return index;
    }

    private static double robustNoise(double[] x) {
        double[] abs = new double[x.length - 1];
        for (int i = 1; i < x.length; i++) abs[i - 1] = Math.abs(x[i] - x[i - 1]);
        return median(abs) * 1.4826;
    }

    private static List<Double> findPeaks(double[] x, double fs, double noise, double dt) {
        List<Double> candidates = new ArrayList<Double>();
        int side = Math.max(3, (int) Math.round(0.35 * fs));
        double threshold = noise * 1.4;
        for (int i = side; i < x.length - side; i++) {
            if (x[i] < x[i - 1] || x[i] <= x[i + 1]) continue;
            double left = x[i], right = x[i];
            for (int j = 1; j <= side; j++) {
                left = Math.min(left, x[i - j]);
                right = Math.min(right, x[i + j]);
            }
            if (x[i] - Math.max(left, right) < threshold) continue;
            double denom = x[i - 1] - 2.0 * x[i] + x[i + 1];
            double delta = denom == 0.0 ? 0.0 : 0.5 * (x[i - 1] - x[i + 1]) / denom;
            delta = Math.max(-0.5, Math.min(0.5, delta));
            candidates.add(i + delta);
        }
        // Keep the strongest candidate in each refractory interval. This removes
        // dicrotic peaks without deleting genuine tachycardic beats.
        List<Double> peaks = new ArrayList<Double>();
        for (double candidate : candidates) {
            if (peaks.isEmpty() || (candidate - peaks.get(peaks.size() - 1)) * dt >= REFRACTORY_SECONDS) {
                peaks.add(candidate);
            } else {
                int old = (int) Math.round(peaks.get(peaks.size() - 1));
                int now = (int) Math.round(candidate);
                if (x[now] > x[old]) peaks.set(peaks.size() - 1, candidate);
            }
        }
        return peaks;
    }
    private static List<Boolean> morphologyQuality(double[] signal, List<Double> peaks) {
        final int before = 5;
        final int after = 7;
        final int width = before + after + 1;
        int count = peaks.size();
        double[][] segments = new double[count][width];
        boolean[] usable = new boolean[count];

        for (int i = 0; i < count; i++) {
            int center = (int) Math.round(peaks.get(i));
            if (center - before < 0 || center + after >= signal.length) continue;
            double mean = 0.0;
            for (int j = 0; j < width; j++) mean += signal[center - before + j];
            mean /= width;
            double norm = 0.0;
            for (int j = 0; j < width; j++) {
                double value = signal[center - before + j] - mean;
                segments[i][j] = value;
                norm += value * value;
            }
            norm = Math.sqrt(norm);
            if (norm == 0.0) continue;
            for (int j = 0; j < width; j++) segments[i][j] /= norm;
            usable[i] = true;
        }

        double[] template = new double[width];
        double[] column = new double[count];
        for (int j = 0; j < width; j++) {
            int available = 0;
            for (int i = 0; i < count; i++) {
                if (usable[i]) column[available++] = segments[i][j];
            }
            if (available == 0) return allFalse(count);
            Arrays.sort(column, 0, available);
            template[j] = column[available / 2];
        }
        double mean = 0.0;
        for (double value : template) mean += value;
        mean /= width;
        double norm = 0.0;
        for (int j = 0; j < width; j++) {
            template[j] -= mean;
            norm += template[j] * template[j];
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) return allFalse(count);
        for (int j = 0; j < width; j++) template[j] /= norm;

        List<Boolean> quality = new ArrayList<Boolean>();
        for (int i = 0; i < count; i++) {
            double correlation = 0.0;
            if (usable[i]) for (int j = 0; j < width; j++) correlation += segments[i][j] * template[j];
            quality.add(usable[i] && correlation >= 0.85);
        }
        return quality;
    }


    private static CleanIntervals cleanIntervals(List<Double> input, List<Boolean> peakQuality) {
        if (input.size() < 10) return new CleanIntervals(input, allFalse(input.size()));
        double med = median(input);
        List<Double> merged = new ArrayList<Double>();
        List<Boolean> morphologyValid = new ArrayList<Boolean>();
        for (int i = 0; i < input.size();) {
            double d = input.get(i);
            if (d >= 0.20 && d < 0.68 * med && i + 1 < input.size()) {
                double sum = d + input.get(i + 1);
                // One false dicrotic peak splits one real IBI into two short
                // intervals; their sum should be one median IBI, not two.
                if (Math.abs(sum - med) <= 0.25 * med) {
                    merged.add(sum);
                    morphologyValid.add(peakQuality.get(i) && peakQuality.get(i + 2));
                    i += 2;
                    continue;
                }
            }
            merged.add(d);
            morphologyValid.add(peakQuality.get(i) && peakQuality.get(i + 1));
            i++;
        }
        double med2 = median(merged);
        List<Boolean> valid = new ArrayList<Boolean>();
        int count = 0;
        for (int i = 0; i < merged.size(); i++) {
            double d = merged.get(i);
            boolean ok = morphologyValid.get(i) && d >= 0.35 && d <= 2.0
                && d >= 0.58 * med2 && d <= 1.55 * med2;
            // An isolated bad interval must not contaminate either RMSSD pair.
            if (ok && i > 0 && i + 1 < merged.size()) {
                double local = median(new ArrayList<Double>(merged.subList(Math.max(0, i - 2), Math.min(merged.size(), i + 3))));
                ok = Math.abs(d - local) <= 0.28 * local;
            }
            valid.add(ok);
            if (ok) count++;
        }
        CleanIntervals result = new CleanIntervals(merged, valid);
        result.validCount = count;
        return result;
    }

    private static List<Boolean> allFalse(int size) {
        List<Boolean> result = new ArrayList<Boolean>();
        for (int i = 0; i < size; i++) result.add(false);
        return result;
    }

    private static final class ScoreResult {
        final double value;
        final boolean available;

        ScoreResult(double value, boolean available) {
            this.value = value;
            this.available = available;
        }
    }

    private static ScoreResult resonanceScore(List<Double> input, List<Boolean> valid) {
        List<Double> clean = new ArrayList<Double>();
        List<Double> cleanTimes = new ArrayList<Double>();
        double elapsed = 0.0;
        for (int i = 0; i < input.size(); i++) {
            elapsed += input.get(i);
            if (valid.get(i)) {
                clean.add(input.get(i));
                cleanTimes.add(elapsed);
            }
        }
        if (clean.size() < 16) return new ScoreResult(0.0, false);
        double[] time = new double[clean.size()];
        double start = cleanTimes.get(0);
        for (int i = 0; i < time.length; i++) time[i] = cleanTimes.get(i) - start;
        // Keep real elapsed time across rejected intervals; closing those gaps
        // would shift spectral power and inflate coherence.
        double span = time[time.length - 1] - time[0];
        if (span < 25.0) return new ScoreResult(0.0, false);
        int npts = (int) (span * 4.0);
        if (npts < 16) return new ScoreResult(0.0, false);
        double[] yi = new double[npts];
        int j = 0;
        for (int k = 0; k < npts; k++) {
            double t = k / 4.0;
            while (j + 1 < time.length && time[j + 1] < t) j++;
            if (j + 1 >= time.length) return new ScoreResult(0.0, false);
            double f = (t - time[j]) / (time[j + 1] - time[j]);
            yi[k] = clean.get(j) + (clean.get(j + 1) - clean.get(j)) * f;
        }
        detrendHann(yi);
        // Four-times frequency oversampling keeps the LF peak estimate stable
        // when a sliding window starts at a different respiratory phase.
        double step = 1.0 / (span * 4.0);
        double lf = 0.0;
        double hf = 0.0;
        double peak = 0.0;
        List<Double> lfPowers = new ArrayList<Double>();
        for (double frequency = 0.04; frequency < 0.40; frequency += step) {
            double power = dftPower(yi, frequency);
            if (frequency < 0.15) {
                lf += power;
                lfPowers.add(power);
                peak = Math.max(peak, power);
            } else {
                hf += power;
            }
        }
        if (lfPowers.isEmpty()) return new ScoreResult(0.0, false);
        if (lf + hf <= 0.0) return new ScoreResult(0.0, true);
        double medianLf = median(lfPowers);
        double concentration = medianLf > 0.0 ? Math.max(0.0, Math.min(1.0,
            Math.log10(Math.max(1.0, peak / medianLf)) / 2.0)) : 0.0;
        return new ScoreResult(lf / (lf + hf) * concentration * 100.0, true);
    }

    private static void detrendHann(double[] values) {
        int n = values.length;
        double meanX = (n - 1) / 2.0, meanY = 0.0, den = 0.0, num = 0.0;
        for (double value : values) meanY += value;
        meanY /= n;
        for (int i = 0; i < n; i++) { double x = i - meanX; den += x * x; num += x * (values[i] - meanY); }
        double slope = den == 0.0 ? 0.0 : num / den;
        for (int i = 0; i < n; i++) {
            double trend = meanY + slope * (i - meanX);
            double window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1.0)));
            values[i] = (values[i] - trend) * window;
        }
    }

    private static double dftPower(double[] values, double frequency) {
        double re = 0.0, im = 0.0;
        for (int i = 0; i < values.length; i++) {
            double angle = 2.0 * Math.PI * frequency * i / 4.0;
            re += values[i] * Math.cos(angle);
            im -= values[i] * Math.sin(angle);
        }
        return re * re + im * im;
    }


    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
