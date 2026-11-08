package ru.oopssre.gremlinamp;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/** PCM output of the site's hardware models. */
final class WavPcm implements AutoCloseable {
    private final RandomAccessFile input;
    private final long start, end;
    private final int channels;
    final int rate;
    private final byte[] block = new byte[8192];

    WavPcm(File file) throws IOException {
        input = new RandomAccessFile(file, "r");
        try {
            if (input.readInt() != 0x52494646) throw new IOException("Неверный WAV");
            long limit = 8 + uint32();
            if (limit > input.length() || input.readInt() != 0x57415645) throw new IOException("Неверный WAV");

            int frequency = 0, count = 0;
            long data = -1, length = 0;
            while (input.getFilePointer() + 8 <= limit) {
                int name = input.readInt();
                long bytes = uint32(), offset = input.getFilePointer();
                if (bytes > limit - offset) throw new IOException("Неполный блок WAV");
                if (name == 0x666d7420) {
                    if (bytes < 16 || uint16() != 1) throw new IOException("Требуется WAV PCM");
                    count = uint16();
                    frequency = (int) uint32();
                    uint32();
                    int frameSize = uint16(), bits = uint16();
                    if ((count != 1 && count != 2) || frameSize != count * 2 || bits != 16
                            || frequency < 8000 || frequency > 192000)
                        throw new IOException("Неподдерживаемый WAV PCM");
                } else if (name == 0x64617461) {
                    data = offset;
                    length = bytes;
                    if (frequency > 0) break;
                }
                input.seek(offset + bytes + (bytes & 1));
            }
            if (data < 0 || frequency == 0 || length == 0 || length % (count * 2) != 0)
                throw new IOException("Нет звуковых кадров WAV");
            channels = count;
            rate = frequency;
            start = data;
            end = start + length;
            input.seek(start);
        } catch (IOException e) {
            input.close();
            throw e;
        }
    }

    private int uint16() throws IOException { return Short.reverseBytes(input.readShort()) & 0xffff; }
    private long uint32() throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt())); }

    void render(short[] stereo) throws IOException {
        int bytes = (int) Math.min((long) stereo.length * channels, end - input.getFilePointer());
        input.readFully(block, 0, bytes);
        int sample = 0;
        for (int i = 0; i < bytes; i += channels * 2) {
            short left = (short) ((block[i] & 255) | block[i + 1] << 8);
            stereo[sample++] = left;
            stereo[sample++] = channels == 1 ? left : (short) ((block[i + 2] & 255) | block[i + 3] << 8);
        }
        Arrays.fill(stereo, sample, stereo.length, (short) 0);
    }

    void seek(int ms) throws IOException { input.seek(Math.min(end, start + (long) ms * rate / 1000 * channels * 2)); }

    boolean ended() {
        try { return input.getFilePointer() >= end; }
        catch (IOException e) { return true; }
    }

    @Override public void close() throws IOException { input.close(); }
}
