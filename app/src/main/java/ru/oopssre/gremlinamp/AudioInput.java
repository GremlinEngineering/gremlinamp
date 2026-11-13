package ru.oopssre.gremlinamp;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.zip.GZIPInputStream;

final class AudioInput {
    private static final int MAX_INPUT = 256 * 1024 * 1024;

    static byte[] read(File file) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            return read(input);
        }
    }

    static byte[] unpack(byte[] bytes) throws IOException {
        boolean gzip = bytes.length >= 2 && bytes[0] == 31 && (bytes[1] & 255) == 139;
        return gzip ? read(new ByteArrayInputStream(bytes)) : bytes;
    }

    private static byte[] read(InputStream input) throws IOException {
        input.mark(2);
        boolean gzip = input.read() == 31 && input.read() == 139;
        input.reset();
        try (InputStream decoded = gzip ? new GZIPInputStream(input) : input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = decoded.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Загрузка отменена");
                if (count > MAX_INPUT - output.size()) throw new IOException("Аудиофайл превышает допустимый размер");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
