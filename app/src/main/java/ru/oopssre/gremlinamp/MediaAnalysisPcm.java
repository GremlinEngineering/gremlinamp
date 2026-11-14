package ru.oopssre.gremlinamp;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes the legacy compressed-file backends to PCM for the same analysis as chip decoders. */
final class MediaAnalysisPcm implements PcmSource {
    private static final long WAIT_US = 10_000;
    private final MediaExtractor extractor = new MediaExtractor();
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private MediaCodec codec;
    private ByteBuffer output;
    private int slot = -1, rate, channels;
    private boolean inputEnded, outputEnded, lastBuffer;
    private long durationUs;

    MediaAnalysisPcm(File file) throws IOException {
        try {
            extractor.setDataSource(file.toString());
            MediaFormat audio = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    audio = format;
                    break;
                }
            }
            if (audio == null) throw new IOException("В файле нет звуковой дорожки");
            apply(audio);
            durationUs = audio.containsKey(MediaFormat.KEY_DURATION) ? audio.getLong(MediaFormat.KEY_DURATION) : -1;
            audio.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec = MediaCodec.createDecoderByType(audio.getString(MediaFormat.KEY_MIME));
            codec.configure(audio, null, null, 0);
            codec.start();
            readOutput();
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private void apply(MediaFormat format) throws IOException {
        rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        if (channels < 1 || channels > 8) throw new IOException("Неподдерживаемое число каналов");
    }

    private void feedInput() {
        if (inputEnded) return;
        int input = codec.dequeueInputBuffer(WAIT_US);
        if (input < 0) return;
        ByteBuffer buffer = codec.getInputBuffer(input);
        buffer.clear();
        int size = extractor.readSampleData(buffer, 0);
        if (size < 0) {
            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            inputEnded = true;
        } else {
            codec.queueInputBuffer(input, 0, size, Math.max(0, extractor.getSampleTime()), 0);
            extractor.advance();
        }
    }

    private void readOutput() throws IOException {
        int idle = 0;
        while (!outputEnded && output == null) {
            AmbientAnalysis.checkInterrupted();
            feedInput();
            int index = codec.dequeueOutputBuffer(info, WAIT_US);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = codec.getOutputFormat();
                apply(format);
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        && format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
                    throw new IOException("Декодер не отдаёт 16-битный PCM");
            } else if (index >= 0) {
                lastBuffer = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size == 0) {
                    codec.releaseOutputBuffer(index, false);
                    outputEnded = lastBuffer;
                } else {
                    slot = index;
                    output = codec.getOutputBuffer(slot);
                    output.order(ByteOrder.LITTLE_ENDIAN);
                    output.limit(info.offset + info.size).position(info.offset);
                }
            } else if (++idle >= 200) {
                throw new IOException("Аудиодекодер не отдал PCM");
            }
        }
    }

    @Override public int sampleRate() { return rate; }
    @Override public long duration() { return durationUs > 0 ? durationUs * rate / 1_000_000 : -1; }
    @Override public boolean ended() { return outputEnded; }

    @Override public int render(short[] pcm, int frames) throws IOException {
        int count = 0;
        while (count < frames && !outputEnded) {
            readOutput();
            if (outputEnded) break;
            while (count < frames && output.remaining() >= channels * 2) {
                short left = output.getShort();
                short right = channels == 1 ? left : output.getShort();
                for (int i = 2; i < channels; i++) output.getShort();
                pcm[count * 2] = left;
                pcm[count * 2 + 1] = right;
                count++;
            }
            if (output.remaining() < channels * 2) {
                codec.releaseOutputBuffer(slot, false);
                output = null;
                slot = -1;
                outputEnded = lastBuffer;
            }
        }
        return count;
    }

    @Override public void close() {
        try {
            if (codec != null) codec.release();
        } finally {
            extractor.release();
        }
    }
}
