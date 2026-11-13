package ru.oopssre.gremlinamp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32;

final class NativeMachine implements AutoCloseable {
    static { System.loadLibrary("gremlinamp"); }

    enum Core {
        GBA("AGM", "gba", 32768, "1c61b54208ca6266129d0f2394c04bd8c44f98c5"),
        GB("GBM", "gb", 32768, "77f2567d718b8973d02caa2daa8dce4953faf811"),
        IV("IVM", "iv", 44100, "ef3e0fe322bec62a7f916c0bb0834c08c348d0b4"),
        JAG("JGM", "jag", 48000, "34696082dbc0448caf23aa4ecb5ed40817cb8da0"),
        MD("MDM", "md", 44100, "1890c2932234c9d30f4cd3851d02228baae8f09e"),
        ST("STM", "st", 31400, "7d1361e407e63f29e52892655069e5fb4096e691"),
        SV("SVM", "sv", 44100, "227c5f6f3ce74d32e9002ce24c1420288559a860"),
        VX("VXM", "vx", 187500, "dccff5402a16d1ea7c112095a8e42632bf149506"),
        WS("WSM", "ws", 44100, "4b01295838ea89e3f1355bbe4cb5cf98aa6108cd"),
        NF("NFM", "nf", 48000, "e0f3086a8104f8fbbe49a2615b10401504a9533f"),
        A5("A5M", "a5", 44100, "40c6f2f1ad4a3145b328d5baaf010fae6c7e752b");

        final String magic;
        final String library;
        final int rate;
        final String revision;

        Core(String magic, String library, int rate, String revision) {
            this.magic = magic;
            this.library = library;
            this.rate = rate;
            this.revision = revision;
        }

        static Core forFormat(String format) {
            return switch (format) {
                case "agm", "agz" -> GBA;
                case "gbm", "gbz" -> GB;
                case "ivm", "ivz" -> IV;
                case "jgm", "jgz" -> JAG;
                case "mdm", "mdz" -> MD;
                case "stm", "stz" -> ST;
                case "svm", "svz" -> SV;
                case "vxm", "vxz" -> VX;
                case "wsm", "wsz" -> WS;
                case "nfm", "nfz" -> NF;
                case "a5m", "a5z" -> A5;
                default -> null;
            };
        }
    }

    private record Event(int frames, int mask) {}
    private record Capsule(int profile, byte[] rom, byte[] bios, Event[] prefix, Event[] play,
                           long audioFrames, boolean fds) {}

    private static final int HEADER = 128;
    private static final int TRACK = 64;
    private static final int FRAME_BUFFER = 4096;

    private final Core core;
    private final Capsule capsule;
    private final short[] block = new short[FRAME_BUFFER * 2];
    private Path directory;
    private long handle;
    private long position;
    private int eventIndex;
    private int eventFrame;
    private int next;
    private int available;

    NativeMachine(Core core, byte[] bytes, int subsong) throws IOException {
        this.core = core;
        if (bytes.length < 16 || bytes[3] != 26
                || !core.magic.equals(new String(bytes, 0, 3, StandardCharsets.US_ASCII)))
            throw new IOException("Invalid music capsule header");
        capsule = core == Core.NF || core == Core.A5 ? jsonCapsule(bytes, subsong) : binaryCapsule(bytes, subsong);
        try {
            prepareSystemFiles();
            restart();
        } catch (IOException | RuntimeException failure) {
            try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    int rate() { return core.rate; }
    long duration() { return capsule.audioFrames; }
    boolean ended() { return position >= duration(); }

    int render(short[] output, int frames) throws IOException {
        return consume(output, (int) Math.min(frames, duration() - position));
    }

    long seek(long target) throws IOException {
        target = Math.max(0, Math.min(duration(), target));
        if (target < position) restart();
        while (position < target) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Decoder interrupted");
            consume(null, (int) Math.min(FRAME_BUFFER, target - position));
        }
        return position;
    }

    private int consume(short[] output, int frames) throws IOException {
        int copied = 0;
        while (copied < frames) {
            while (next == available) {
                if (eventIndex == capsule.play.length) throw new IOException("Music capsule ended before its declared audio length");
                Event event = capsule.play[eventIndex];
                available = step(handle, event.mask, block);
                next = 0;
                if (++eventFrame == event.frames) {
                    eventFrame = 0;
                    eventIndex++;
                }
            }
            int count = Math.min(frames - copied, available - next);
            if (output != null) System.arraycopy(block, next * 2, output, copied * 2, count * 2);
            next += count;
            copied += count;
        }
        position += copied;
        return copied;
    }

    private void restart() throws IOException {
        if (handle != 0) closeCore(handle);
        handle = 0;
        String library = core == Core.WS && capsule.profile == 4 ? "ws-headroom" : core.library;
        handle = openCore(library, capsule.rom, rate(), capsule.fds, directory == null ? "" : directory.toString());
        position = 0;
        eventIndex = eventFrame = next = available = 0;
        for (Event event : capsule.prefix) {
            for (int frame = 0; frame < event.frames; frame++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Decoder interrupted");
                step(handle, event.mask, null);
            }
        }
    }

    private void prepareSystemFiles() throws IOException {
        if (core != Core.IV && capsule.bios.length == 0) return;
        directory = Files.createTempDirectory("gremlinamp-core-");
        if (core == Core.IV) {
            int romBytes = capsule.rom.length - 10240;
            if (romBytes <= 0) throw new IOException("Invalid Intellivision capsule");
            Files.write(directory.resolve("original.bin"), Arrays.copyOf(capsule.rom, romBytes));
            Files.write(directory.resolve("exec.bin"), Arrays.copyOfRange(capsule.rom, romBytes, romBytes + 8192));
            Files.write(directory.resolve("grom.bin"), Arrays.copyOfRange(capsule.rom, romBytes + 8192, capsule.rom.length));
        } else {
            Files.write(directory.resolve(core == Core.NF ? "disksys.rom" : "5200.rom"), capsule.bios);
        }
    }

    @Override public void close() throws IOException {
        if (handle != 0) closeCore(handle);
        handle = 0;
        if (directory != null) {
            for (String filename : new String[]{"original.bin", "exec.bin", "grom.bin", "disksys.rom", "5200.rom"})
                Files.deleteIfExists(directory.resolve(filename));
            Files.deleteIfExists(directory);
            directory = null;
        }
    }

    private Capsule binaryCapsule(byte[] bytes, int subsong) throws IOException {
        if (bytes.length < HEADER) throw new IOException("Truncated music capsule");
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int profile = data.getInt(8), romSize = data.getInt(20), tracks = data.getInt(24);
        int scripts = data.getInt(36), scriptSize = data.getInt(40), rom = data.getInt(44);
        if (data.getInt(4) != 1 || profile < 1 || data.getInt(12) != rate()
                || data.getInt(28) != HEADER || data.getInt(32) != TRACK || data.getInt(48) != bytes.length
                || tracks < 1 || tracks > 64 || subsong < 0 || subsong >= tracks
                || scripts != HEADER + tracks * TRACK || scriptSize < 8 || scriptSize > 262144 || scriptSize % 8 != 0
                || rom != scripts + scriptSize || romSize < 1 || romSize > 16777216 || (long) rom + romSize != bytes.length)
            throw new IOException("Invalid music capsule bounds");
        if (!core.revision.equals(hex(bytes, 96, 20))
                || crc(bytes, HEADER, scripts - HEADER) != data.getInt(52)
                || crc(bytes, scripts, scriptSize) != data.getInt(56)
                || crc(bytes, rom, romSize) != data.getInt(60))
            throw new IOException("Music capsule revision or checksum differs");
        byte[] cartridge = Arrays.copyOfRange(bytes, rom, bytes.length);
        if (!hash(cartridge).equals(hex(bytes, 64, 32))) throw new IOException("Music capsule ROM checksum differs");
        int row = HEADER + subsong * TRACK;
        long length = data.getInt(row + 28);
        if (data.getInt(row) != subsong || length <= 0 || length > (long) rate() * 1200)
            throw new IOException("Invalid music capsule selection");
        return new Capsule(profile, cartridge, new byte[0],
                binaryEvents(data, scripts, scriptSize, row + 4),
                binaryEvents(data, scripts, scriptSize, row + 16), length, false);
    }

    private Event[] binaryEvents(ByteBuffer data, int scripts, int scriptSize, int row) throws IOException {
        int offset = data.getInt(row), count = data.getInt(row + 4), frames = data.getInt(row + 8);
        if (offset < 0 || offset % 8 != 0 || count < 1 || count > 1024
                || (long) offset + count * 8 > scriptSize)
            throw new IOException("Invalid music capsule input script");
        Event[] events = new Event[count];
        for (int i = 0; i < count; i++) {
            int at = scripts + offset + i * 8;
            events[i] = new Event(data.getInt(at), data.getInt(at + 4));
        }
        verifyEvents(events, frames);
        return events;
    }

    private Capsule jsonCapsule(byte[] bytes, int subsong) throws IOException {
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int header = data.getInt(4), romSize = data.getInt(8), biosSize = data.getInt(12);
        if (header < 1 || header > 65536 || romSize < 1 || romSize > 16777216
                || biosSize < 0 || biosSize > 8192 || 16L + header + romSize + biosSize != bytes.length)
            throw new IOException("Invalid music capsule bounds");
        try {
            JSONObject info = new JSONObject(new String(bytes, 16, header, StandardCharsets.UTF_8));
            JSONArray tracks = info.getJSONArray("tracks");
            boolean fds = info.optBoolean("fds");
            int requiredBios = core == Core.A5 ? 2048 : fds ? 8192 : 0;
            if (info.getInt("version") != 1 || !core.revision.equals(info.getString("core_commit"))
                    || info.getInt("source_rate") != rate() || biosSize != requiredBios || tracks.length() > 64)
                throw new IOException("Unsupported music capsule profile");
            int start = 16 + header;
            byte[] rom = Arrays.copyOfRange(bytes, start, start + romSize);
            byte[] bios = Arrays.copyOfRange(bytes, start + romSize, bytes.length);
            if (!hash(rom).equals(info.getString("rom_sha256")) || !hash(bios).equals(info.getString("bios_sha256")))
                throw new IOException("Music capsule ROM or BIOS checksum differs");
            JSONObject track = tracks.getJSONObject(subsong);
            long length = track.getLong("audio_frames");
            if (length < 1 || length > (long) rate() * 1200) throw new IOException("Invalid music capsule duration");
            return new Capsule(info.getInt("profile"), rom, bios, jsonEvents(track, "prefix"),
                    jsonEvents(track, "play"), length, fds);
        } catch (JSONException failure) {
            throw new IOException("Invalid music capsule metadata", failure);
        }
    }

    private Event[] jsonEvents(JSONObject track, String key) throws JSONException, IOException {
        JSONArray data = track.getJSONArray(key);
        if (data.length() < 1 || data.length() > 1024) throw new IOException("Invalid music capsule input script");
        Event[] events = new Event[data.length()];
        for (int i = 0; i < events.length; i++) {
            JSONObject event = data.getJSONObject(i);
            events[i] = new Event(event.getInt("frames"), event.getInt("mask"));
        }
        verifyEvents(events, -1);
        return events;
    }

    private void verifyEvents(Event[] events, int expectedFrames) throws IOException {
        int mask = core == Core.JAG ? 0x3ffffff : core == Core.IV ? 0xfffff : 0xffff;
        long frames = 0;
        for (Event event : events) {
            if (event.frames < 1 || event.frames > 60000 || (event.mask & ~mask) != 0)
                throw new IOException("Invalid music capsule input event");
            frames += event.frames;
        }
        if (frames < 1 || frames > 60000 || expectedFrames >= 0 && frames != expectedFrames)
            throw new IOException("Music capsule input timeline differs");
    }

    private static int crc(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return hex(digest, 0, digest.length);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String hex(byte[] bytes, int offset, int length) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int value = bytes[offset + i] & 255;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 15];
        }
        return new String(result);
    }

    private static native long openCore(String library, byte[] rom, int rate, boolean fds, String directory) throws IOException;
    private static native int step(long handle, int mask, short[] output) throws IOException;
    private static native void closeCore(long handle);
}
