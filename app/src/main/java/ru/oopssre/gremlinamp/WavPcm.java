package ru.oopssre.gremlinamp;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.nio.ByteBuffer;

/** PCM output of the site's hardware models. */
final class WavPcm implements AutoCloseable {
    private final RandomAccessFile input;
    private final ByteBuffer memory;
    private final long start, end;
    private final int channels;
    final int rate;
    private final byte[] block = new byte[8192];

    WavPcm(File file) throws IOException {
        this(new RandomAccessFile(file, "r"), null);
    }

    WavPcm(byte[] data) throws IOException {
        this(null, ByteBuffer.wrap(data));
    }

    private WavPcm(RandomAccessFile input, ByteBuffer memory) throws IOException {
        this.input = input;
        this.memory = memory;
        try {
            if (readInt() != 0x52494646) throw new IOException("Неверный WAV");
            long limit = 8 + uint32();
            if (limit > length() || readInt() != 0x57415645) throw new IOException("Неверный WAV");

            int frequency = 0, count = 0;
            long data = -1, length = 0;
            while (position() + 8 <= limit) {
                int name = readInt();
                long bytes = uint32(), offset = position();
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
                position(offset + bytes + (bytes & 1));
            }
            if (data < 0 || frequency == 0 || length == 0 || length % (count * 2) != 0)
                throw new IOException("Нет звуковых кадров WAV");
            channels = count;
            rate = frequency;
            start = data;
            end = start + length;
            position(start);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private int uint16() throws IOException { return Short.reverseBytes(readShort()) & 0xffff; }
    private long uint32() throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(readInt())); }

    void render(short[] stereo) throws IOException {
        int frames = render(stereo, stereo.length / 2);
        Arrays.fill(stereo, frames * 2, stereo.length, (short) 0);
    }

    int render(short[] stereo, int frames) throws IOException {
        int bytes = (int) Math.min((long) frames * channels * 2, end - position());
        readBlock(bytes);
        int sample = 0;
        for (int i = 0; i < bytes; i += channels * 2) {
            short left = (short) ((block[i] & 255) | block[i + 1] << 8);
            stereo[sample++] = left;
            stereo[sample++] = channels == 1 ? left : (short) ((block[i + 2] & 255) | block[i + 3] << 8);
        }
        return sample / 2;
    }

    long duration() { return (end - start) / (channels * 2); }

    long seek(long frame) throws IOException {
        long target = Math.min(duration(), frame);
        position(start + target * channels * 2);
        return target;
    }

    boolean ended() {
        try { return position() >= end; }
        catch (IOException e) { return true; }
    }

    private void require(int size) throws EOFException {
        if (memory.remaining() < size) throw new EOFException("Неполный блок WAV");
    }
    private int readInt() throws IOException {
        if (memory == null) return input.readInt();
        require(4); return memory.getInt();
    }
    private short readShort() throws IOException {
        if (memory == null) return input.readShort();
        require(2); return memory.getShort();
    }
    private long position() throws IOException { return memory == null ? input.getFilePointer() : memory.position(); }
    private long length() throws IOException { return memory == null ? input.length() : memory.limit(); }
    private void position(long offset) throws IOException {
        if (memory == null) input.seek(offset);
        else {
            if (offset < 0 || offset > memory.limit()) throw new EOFException("Неверный адрес WAV");
            memory.position((int) offset);
        }
    }
    private void readBlock(int bytes) throws IOException {
        if (memory == null) input.readFully(block, 0, bytes);
        else { require(bytes); memory.get(block, 0, bytes); }
    }
    @Override public void close() throws IOException { if (input != null) input.close(); }
}
