package space.shakalizator.eq;

import java.util.HashSet;

public final class DspCheck {
    public static void main(String[] args) {
        for (int rate : new int[]{4000, 8000, 44100, 48000, 96000, 192000}) {
            var fingerprints = new HashSet<Integer>();
            float[] output = new float[2];
            var dsp = new ShakalDsp(rate);
            for (var preset : ShakalDsp.Preset.values()) {
                int hash = 1;
                double difference = 0;
                for (int i = 0; i < rate * 2; i++) {
                    double time = 2 * Math.PI * i / rate;
                    float left = (float) (0.6 * Math.sin(110 * time) + 0.4 * Math.sin(1900 * time));
                    float right = (float) (0.4 * Math.sin(253 * time) + 0.6 * Math.sin(3910 * time));
                    dsp.process(left, right, preset, 1, output);
                    for (float sample : output) {
                        if (!Float.isFinite(sample) || Math.abs(sample) > 1) {
                            throw new AssertionError("Invalid output: " + preset + " at " + rate);
                        }
                        hash = 31 * hash + Math.round(sample * 32767);
                    }
                    difference += Math.abs(output[0] - left) + Math.abs(output[1] - right);
                }
                if (!fingerprints.add(hash) || difference / (rate * 2) < 0.01) {
                    throw new AssertionError("Preset has no distinct effect: " + preset);
                }
                for (int i = 0; i < rate / 20; i++) dsp.process(0.125f, -0.75f, preset, 0, output);
                if (output[0] != 0.125f || output[1] != -0.75f) {
                    throw new AssertionError("Bypass did not return dry input: " + preset);
                }
            }
            System.out.println(rate + " Hz: all presets distinct, bounded, and bypassable");
        }
    }
}
