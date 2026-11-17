package space.shakalizator.eq;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Process;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ShakalPlayer implements Runnable {
    interface Listener { void finished(ShakalPlayer player, Exception error); }

    private final Context context;
    private final Uri uri;
    private final Listener listener;
    private final Object playback = new Object();
    private volatile boolean stopped;
    private boolean paused;
    private AudioTrack track;
    private ShakalDsp dsp;
    private int channels;
    private int encoding;
    private int rate;
    private long written;
    private int fadeFrames;
    private final float[] frame = new float[2];
    private final short[] samples = new short[2048];

    volatile ShakalDsp.Preset preset;
    volatile float wet;

    ShakalPlayer(Context context, Uri uri, ShakalDsp.Preset preset, float wet, Listener listener) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.preset = preset;
        this.wet = wet;
        this.listener = listener;
    }

    void setPaused(boolean value) {
        synchronized (playback) {
            if (paused == value || stopped) return;
            paused = value;
            if (track != null) {
                if (paused) track.pause();
                else track.play();
            }
            playback.notifyAll();
        }
    }

    void stop() {
        synchronized (playback) {
            stopped = true;
            if (track != null) {
                track.pause();
                track.flush();
            }
            playback.notifyAll();
        }
    }

    @Override public void run() {
        Exception failure = null;
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (uri == null) {
                try (var demo = context.getAssets().openFd("demo.wav")) {
                    extractor.setDataSource(demo.getFileDescriptor(), demo.getStartOffset(), demo.getLength());
                }
            } else {
                extractor.setDataSource(context, uri, null);
            }

            MediaFormat source = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    source = candidate;
                    extractor.selectTrack(i);
                    break;
                }
            }
            if (source == null) throw new IOException("В файле нет аудиодорожки");

            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            if ("audio/raw".equals(source.getString(MediaFormat.KEY_MIME))) {
                raw(extractor, source);
            } else {
                decode(extractor, source);
            }
            if (!stopped && written == 0) throw new IOException("В аудиодорожке нет сэмплов");
            drain();
        } catch (IOException | RuntimeException error) {
            if (!stopped) failure = error;
        } finally {
            extractor.release();
            synchronized (playback) {
                if (track != null) {
                    track.release();
                    track = null;
                }
            }
        }
        listener.finished(this, failure);
    }

    private void raw(MediaExtractor source, MediaFormat format) throws IOException {
        openOutput(format);
        ByteBuffer buffer = ByteBuffer.allocateDirect(256 * 1024);
        while (awaitPlaying()) {
            buffer.clear();
            int bytes = source.readSampleData(buffer, 0);
            if (bytes < 0) return;
            buffer.position(0).limit(bytes);
            render(buffer);
            source.advance();
        }
    }

    private void decode(MediaExtractor source, MediaFormat format) throws IOException {
        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        try {
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            long lastOutput = android.os.SystemClock.elapsedRealtime();
            while (awaitPlaying()) {
                if (!inputEnded) {
                    int index = codec.dequeueInputBuffer(0);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        int bytes = source.readSampleData(buffer, 0);
                        if (bytes < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            codec.queueInputBuffer(index, 0, bytes, source.getSampleTime(), 0);
                            source.advance();
                        }
                    }
                }

                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    openOutput(codec.getOutputFormat());
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            ByteBuffer buffer = codec.getOutputBuffer(index);
                            buffer.position(info.offset).limit(info.offset + info.size);
                            render(buffer);
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false);
                    }
                    lastOutput = android.os.SystemClock.elapsedRealtime();
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                } else if (inputEnded && android.os.SystemClock.elapsedRealtime() - lastOutput > 5000) {
                    throw new IOException("Декодер не отдал конец трека");
                }
            }
        } finally {
            codec.release();
        }
    }

    private void openOutput(MediaFormat format) throws IOException {
        int newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int newEncoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
        if (newRate < 4000 || newRate > 192000 || newChannels < 1 || newChannels > 2) {
            throw new IOException("Поддерживаются моно и стерео от 4 до 192 кГц");
        }
        if (newEncoding != AudioFormat.ENCODING_PCM_16BIT && newEncoding != AudioFormat.ENCODING_PCM_FLOAT
                && newEncoding != AudioFormat.ENCODING_PCM_8BIT
                && newEncoding != AudioFormat.ENCODING_PCM_24BIT_PACKED
                && newEncoding != AudioFormat.ENCODING_PCM_32BIT) {
            throw new IOException("Неподдерживаемый формат PCM");
        }
        synchronized (playback) {
            if (stopped) return;
            if (track != null) {
                if (rate != newRate || channels != newChannels || encoding != newEncoding) {
                    throw new IOException("Формат PCM меняется посреди дорожки");
                }
                return;
            }
            rate = newRate;
            channels = newChannels;
            encoding = newEncoding;
            dsp = new ShakalDsp(rate);
            int minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) throw new IOException("Устройство не поддерживает эту частоту");
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minBytes, rate * 4 / 20))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (!paused) track.play();
        }
    }

    static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    private void render(ByteBuffer buffer) throws IOException {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int bytes = switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT -> 1;
            case AudioFormat.ENCODING_PCM_16BIT -> 2;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3;
            default -> 4;
        };
        while (buffer.remaining() >= channels * bytes && awaitPlaying()) {
            int count = 0;
            ShakalDsp.Preset selected = preset;
            float intensity = wet;
            while (count < samples.length && buffer.remaining() >= channels * bytes) {
                float left = readSample(buffer);
                float right = channels == 1 ? left : readSample(buffer);
                dsp.process(left, right, selected, intensity, frame);
                float fade = Math.min(1f, fadeFrames / (rate * 0.025f));
                if (fade < 1f) fadeFrames++;
                samples[count++] = (short) Math.round(frame[0] * 32767f * fade);
                samples[count++] = (short) Math.round(frame[1] * 32767f * fade);
            }

            int offset = 0;
            while (offset < count && awaitPlaying()) {
                int size;
                synchronized (playback) {
                    if (stopped) return;
                    if (paused) continue;
                    size = track.write(samples, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING);
                }
                if (size < 0) throw new IOException("Ошибка вывода звука (" + size + ")");
                if (size == 0) {
                    android.os.SystemClock.sleep(4);
                    continue;
                }
                offset += size;
                written += size / 2;
            }
        }
    }

    private float readSample(ByteBuffer buffer) {
        return switch (encoding) {
            case AudioFormat.ENCODING_PCM_FLOAT -> {
                float value = buffer.getFloat();
                yield Float.isFinite(value) ? Math.max(-1f, Math.min(1f, value)) : 0f;
            }
            case AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get() & 255) - 128) / 128f;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                int value = (buffer.get() & 255) | ((buffer.get() & 255) << 8) | (buffer.get() << 16);
                yield value / 8388608f;
            }
            case AudioFormat.ENCODING_PCM_32BIT -> buffer.getInt() / 2147483648f;
            default -> buffer.getShort() / 32768f;
        };
    }

    private void drain() {
        if (track == null || stopped) return;
        long head;
        do {
            if (!awaitPlaying()) return;
            head = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
            android.os.SystemClock.sleep(10);
        } while (head < written);
    }

    private boolean awaitPlaying() {
        synchronized (playback) {
            while (paused && !stopped) {
                try {
                    playback.wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !stopped;
        }
    }
}
