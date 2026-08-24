package com.hrv.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure 25 Hz PPG beat extraction and emwave-compatible resonance scoring. */
public final class HrvAnalyzer {
    private HrvAnalyzer() {}

    public static final class Result {
        public final float hr;
        public final float rmssdMs;
        public final float sdnnMs;
        public final float score;
        public final int cleanCount;
        public final int totalCount;

        Result(double hr, double rmssd, double sdnn, double score, int clean, int total) {
            this.hr = (float) hr;
            this.rmssdMs = (float) (rmssd * 1000.0);
            this.sdnnMs = (float) (sdnn * 1000.0);
            this.score = (float) score;
            this.cleanCount = clean;
            this.totalCount = total;
        }
    }

    public static Result analyze(float[] values, long[] times) {
        int n = values.length;
        if (n < 305 || times.length != n) return null; // 12s at the measured 25.4Hz
        double dt = (times[n - 1] - times[0]) / 1e9 / (n - 1);
        double fs = 1.0 / dt;
        if (fs < 24.0 || fs > 27.0) return null;

        double[] filtered = preprocess(values, fs);
        double noise = robustNoise(filtered);
        if (noise < 1.0) return null;
        List<Double> peaks = findPeaks(filtered, fs, noise, dt);
        if (peaks.size() < 12) return null;

        List<Double> ibis = new ArrayList<Double>();
        for (int i = 1; i < peaks.size(); i++) ibis.add((peaks.get(i) - peaks.get(i - 1)) * dt);
        CleanIntervals intervals = cleanIntervals(ibis);
        if (intervals.validCount < 10) return null;

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
        if (pairs < 8) return null;
        rmssd = Math.sqrt(rmssd / pairs);

        // emwave-utils: LF normalized power multiplied by log-scaled LF peak
        // concentration. This is resonance strength, not an RMSSD map.
        double score = Math.max(0.0, Math.min(100.0, resonanceScore(intervals.values, intervals.valid)));
        return new Result(60.0 / mean, rmssd, sdnn, score, intervals.validCount, ibis.size());
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

    private static double[] preprocess(float[] values, double fs) {
        int n = values.length;
        int span = Math.max(9, (int) Math.round(1.6 * fs));
        double[] out = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += values[i];
            if (i >= span) sum -= values[i - span];
            int count = Math.min(i + 1, span);
            out[i] = values[i] - sum / count;
        }
        double[] smooth = new double[n];
        for (int i = 1; i < n - 1; i++) {
            smooth[i] = (out[i - 1] + 2.0 * out[i] + out[i + 1]) / 4.0;
        }
        smooth[0] = out[0];
        smooth[n - 1] = out[n - 1];
        return smooth;
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
            if (peaks.isEmpty() || (candidate - peaks.get(peaks.size() - 1)) * dt >= 0.38) {
                peaks.add(candidate);
            } else {
                int old = (int) Math.round(peaks.get(peaks.size() - 1));
                int now = (int) Math.round(candidate);
                if (x[now] > x[old]) peaks.set(peaks.size() - 1, candidate);
            }
        }
        return peaks;
    }

    private static CleanIntervals cleanIntervals(List<Double> input) {
        if (input.size() < 10) return new CleanIntervals(input, allFalse(input.size()));
        double med = median(input);
        List<Double> merged = new ArrayList<Double>();
        for (int i = 0; i < input.size();) {
            double d = input.get(i);
            if (d >= 0.20 && d < 0.68 * med && i + 1 < input.size()) {
                double sum = d + input.get(i + 1);
                // One false dicrotic peak splits one real IBI into two short
                // intervals; their sum should be one median IBI, not two.
                if (Math.abs(sum - med) <= 0.25 * med) {
                    merged.add(sum);
                    i += 2;
                    continue;
                }
            }
            merged.add(d);
            i++;
        }
        double med2 = median(merged);
        List<Boolean> valid = new ArrayList<Boolean>();
        int count = 0;
        for (int i = 0; i < merged.size(); i++) {
            double d = merged.get(i);
            boolean ok = d >= 0.35 && d <= 2.0 && d >= 0.58 * med2 && d <= 1.55 * med2;
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

    private static double resonanceScore(List<Double> input, List<Boolean> valid) {
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
        if (clean.size() < 16) return 0.0;
        double[] time = new double[clean.size()];
        double start = cleanTimes.get(0);
        for (int i = 0; i < time.length; i++) time[i] = cleanTimes.get(i) - start;
        // Keep real elapsed time across rejected intervals; closing those gaps
        // would shift spectral power and inflate coherence.
        double span = time[time.length - 1] - time[0];
        if (span < 12.0) return 0.0;
        int npts = (int) (span * 4.0);
        if (npts < 16) return 0.0;
        double[] yi = new double[npts];
        int j = 0;
        for (int k = 0; k < npts; k++) {
            double t = k / 4.0;
            while (j + 1 < time.length && time[j + 1] < t) j++;
            if (j + 1 >= time.length) return 0.0;
            double f = (t - time[j]) / (time[j + 1] - time[j]);
            yi[k] = clean.get(j) + (clean.get(j + 1) - clean.get(j)) * f;
        }
        detrendHann(yi);
        int nf = npts / 2;
        double[] power = new double[nf];
        for (int k = 0; k < nf; k++) power[k] = dftPower(yi, k * 4.0 / npts);
        double lf = band(power, 4.0 / npts, 0.04, 0.15);
        double hf = band(power, 4.0 / npts, 0.15, 0.40);
        if (lf + hf <= 0.0) return 0.0;
        double peak = 0.0;
        List<Double> lfBins = new ArrayList<Double>();
        for (int k = 0; k < nf; k++) {
            double freq = k * 4.0 / npts;
            if (freq >= 0.04 && freq <= 0.15) {
                lfBins.add(power[k]);
                peak = Math.max(peak, power[k]);
            }
        }
        double medianLf = median(lfBins);
        double concentration = medianLf > 0.0 ? Math.max(0.0, Math.min(1.0,
            Math.log10(Math.max(1.0, peak / medianLf)) / 2.0)) : 0.0;
        return lf / (lf + hf) * concentration * 100.0;
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

    private static double band(double[] power, double binHz, double low, double high) {
        double result = 0.0;
        for (int k = 0; k < power.length; k++) {
            double f = k * binHz;
            if (f >= low && f < high) result += power[k];
        }
        return result;
    }

    private static double rms(double[] x) {
        double sum = 0.0;
        for (double v : x) sum += v * v;
        return Math.sqrt(sum / x.length);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static double median(double[] values) {
        List<Double> list = new ArrayList<Double>(values.length);
        for (double v : values) list.add(v);
        return median(list);
    }
}
