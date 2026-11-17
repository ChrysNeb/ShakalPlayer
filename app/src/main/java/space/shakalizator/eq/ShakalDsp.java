package space.shakalizator.eq;

import java.util.Arrays;

final class ShakalDsp {
    enum Preset {
        AIRPLANE("Наушники из самолёта", "Узкое моно, картонная середина и дребезг",
                320, 3150, 1340, 8, 0.96f, 2.8f),
        CASSETTE("Пожёванная кассета", "Плывёт высота, пропадает один канал, шипит лента",
                85, 4000, 560, 3, 0.16f, 2.6f),
        VINYL("Винил, убитый в хлам", "Щелчки, гул и царапина на каждом обороте",
                72, 5700, 2450, 3, 0.42f, 1.9f),
        ALI("Уши с Ali за $0.20", "Баса нет, верх орёт, правый наушник тише",
                410, 8050, 3750, 13, 0.1f, 3.8f),
        TWS("TWS с Ali за $1", "Бубнящий бас, песочный верх и провалы связи",
                70, 6250, 255, 10, 0.32f, 2.4f),
        INTERCOM("Домофон у подъезда", "Моно, перегруз и сетевой гул",
                490, 2350, 870, 9, 1f, 6.6f),
        MP3("MP3 из аськи", "Ступеньки ресемплинга и шесть бит на весь трек",
                175, 3630, 1690, 4, 0.73f, 2.0f);

        final String title;
        final String description;
        final int highPass;
        final int lowPass;
        final int peakFrequency;
        final int peakDb;
        final float mono;
        final float drive;

        Preset(String title, String description, int highPass, int lowPass, int peakFrequency,
               int peakDb, float mono, float drive) {
            this.title = title;
            this.description = description;
            this.highPass = highPass;
            this.lowPass = lowPass;
            this.peakFrequency = peakFrequency;
            this.peakDb = peakDb;
            this.mono = mono;
            this.drive = drive;
        }
    }

    private static final double TAU = 2 * Math.PI;
    private final int rate;
    private final float[][] delay;
    private final Filter[][] filters = new Filter[2][4];
    private final float[] held = new float[2];
    private final float[] lastOutput = new float[2];
    private Preset active;
    private int cursor;
    private long frame;
    private int random = 0x6c68a6e9;
    private float blend;
    private float crackle;
    private float rumble;

    ShakalDsp(int rate) {
        this.rate = rate;
        delay = new float[2][rate / 16 + 8];
    }

    void process(float left, float right, Preset selected, float wet, float[] output) {
        if (active != selected && blend == 0) {
            configure(selected);
        }
        float target = active == selected ? wet : 0;
        float step = 1f / (rate * 0.018f);
        blend = Math.max(blend - step, Math.min(blend + step, target));
        double time = frame / (double) rate;
        float mid = (left + right) * 0.5f;
        float sourceL = left + (mid - left) * active.mono;
        float sourceR = right + (mid - right) * active.mono;
        float noise = noise();
        float color = 0;
        float leftGain = 1;
        float rightGain = 1;
        int holdFrames = 1;
        int levels = 0;

        switch (active) {
            case AIRPLANE:
                color = noise * 0.009f;
                rightGain = 0.87f;
                break;
            case CASSETTE:
                double tapeDelay = 0.014 + 0.008 * Math.sin(TAU * 0.64 * time)
                        + 0.0018 * Math.sin(TAU * 7.7 * time)
                        + 0.0011 * Math.sin(TAU * 13.2 * time);
                delay[0][cursor] = sourceL;
                delay[1][cursor] = sourceR;
                sourceL = delayed(0, tapeDelay);
                sourceR = delayed(1, tapeDelay + 0.0007);
                leftGain = drop(time + 1.26, 8.7, 0.17, 0.70f);
                rightGain = drop(time + 0.8, 5.9, 0.24, 0.87f);
                color = noise * 0.042f;
                break;
            case VINYL:
                rumble += 0.007f * (noise - rumble);
                double revolution = (time + 0.4) % 1.8;
                if (Math.abs(noise) > 0.99946f) {
                    crackle = noise * 0.42f;
                }
                if (revolution < 0.032 && Math.abs(noise) > 0.95f) {
                    crackle = noise * 0.63f;
                }
                color = crackle + rumble * 0.34f + noise * 0.023f;
                crackle *= 0.70f;
                break;
            case ALI:
                rightGain = 0.45f;
                break;
            case TWS:
                leftGain = drop(time + 2.4, 8.1, 0.13, 0.92f);
                rightGain = drop(time + 0.3, 4.6, 0.21, 0.99f);
                holdFrames = Math.max(1, rate / 11600);
                levels = 58;
                break;
            case INTERCOM:
                color = (float) Math.sin(TAU * 50 * time) * 0.044f + noise * 0.020f;
                break;
            case MP3:
                holdFrames = Math.max(1, rate / 7600);
                levels = 28;
                break;
        }

        output[0] = mix(left, degrade(0, sourceL, leftGain, color, levels, holdFrames));
        output[1] = mix(right, degrade(1, sourceR, rightGain, color * 0.83f, levels, holdFrames));
        cursor = (cursor + 1) % delay[0].length;
        frame++;
    }

    private float mix(float dry, float dirty) {
        return dry + blend * (dirty - dry);
    }

    private float degrade(int channel, float sample, float gain, float noise, int levels, int holdFrames) {
        for (Filter filter : filters[channel]) {
            sample = filter.apply(sample);
        }
        sample *= active.drive;
        sample = sample / (1f + Math.abs(sample));
        if (frame % holdFrames == 0) {
            held[channel] = levels == 0 ? sample : Math.round(sample * levels) / (float) levels;
        }
        float result = held[channel] * gain + noise;
        if (active == Preset.TWS) {
            // Smooth packet mutes while keeping the stepped signal audible.
            result = lastOutput[channel] + 0.24f * (result - lastOutput[channel]);
        }
        lastOutput[channel] = result;
        return Math.max(-0.86f, Math.min(0.86f, result));
    }

    private static float drop(double time, double period, double length, float depth) {
        double phase = time % period;
        double edge = Math.min(phase, length - phase);
        return 1f - depth * (float) Math.max(0, Math.min(1, edge / 0.009));
    }

    private float delayed(int channel, double seconds) {
        double position = cursor - seconds * rate;
        if (position < 0) position += delay[channel].length;
        int index = (int) position;
        float a = delay[channel][index];
        float b = delay[channel][(index + 1) % delay[channel].length];
        return a + (b - a) * (float) (position - index);
    }

    private float noise() {
        random ^= random << 13;
        random ^= random >>> 17;
        random ^= random << 5;
        return random / 2147483648f;
    }

    private void configure(Preset preset) {
        for (int channel = 0; channel < 2; channel++) {
            Arrays.fill(delay[channel], 0f);
            filters[channel][0] = Filter.cut(rate, preset.highPass, true);
            filters[channel][1] = Filter.peak(rate, preset.peakFrequency, preset.peakDb);
            filters[channel][2] = Filter.cut(rate, preset.lowPass, false);
            filters[channel][3] = Filter.cut(rate, preset.lowPass, false);
        }
        active = preset;
        crackle = 0;
        rumble = 0;
        Arrays.fill(held, 0);
        Arrays.fill(lastOutput, 0);
    }

    private static final class Filter {
        private final float b0, b1, b2, a1, a2;
        private float z1, z2;

        Filter(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0);
            this.b1 = (float) (b1 / a0);
            this.b2 = (float) (b2 / a0);
            this.a1 = (float) (a1 / a0);
            this.a2 = (float) (a2 / a0);
        }

        float apply(float input) {
            float out = input * b0 + z1;
            z1 = input * b1 + z2 - a1 * out;
            z2 = input * b2 - a2 * out;
            return out;
        }

        static Filter cut(int rate, int frequency, boolean highPass) {
            double angular = TAU * Math.min(frequency, 0.45 * rate) / rate;
            double c = Math.cos(angular);
            double alpha = Math.sin(angular) / Math.sqrt(2);
            double b = (1 + (highPass ? c : -c)) / 2;
            return new Filter(b, highPass ? -2 * b : 2 * b, b, 1 + alpha, -2 * c, 1 - alpha);
        }

        static Filter peak(int rate, int frequency, int db) {
            double angular = TAU * Math.min(frequency, 0.43 * rate) / rate;
            double alpha = Math.sin(angular) / 2.4;
            double c = Math.cos(angular);
            double gain = Math.pow(10, db / 40.0);
            return new Filter(1 + alpha * gain, -2 * c, 1 - alpha * gain,
                    1 + alpha / gain, -2 * c, 1 - alpha / gain);
        }
    }
}
