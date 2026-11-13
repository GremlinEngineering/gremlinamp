package ru.oopssre.gremlinamp;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

record BoardCapsule(byte[] data, int[] config) {
    static BoardCapsule parse(String core, byte[] bytes, int subsong) throws IOException {
        boolean konami = core.equals("k0");
        boolean dcs = core.equals("dcs");
        String magic = konami ? "K0M" : dcs ? "DCM" : "G0M";
        int header = konami ? 24 : 16;
        if (bytes.length < header || bytes[3] != 26
                || !magic.equals(new String(bytes, 0, 3, StandardCharsets.US_ASCII)))
            throw new IOException("Invalid " + core + " capsule header");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int json = input.getInt(4), rom = input.getInt(8), vgm = konami ? input.getInt(12) : 0;
        int prom = konami ? input.getInt(16) : 0, events = input.getInt(konami ? 20 : 12);
        if (json < 1 || json > 65536 || rom < 4096 || rom > 8388608
                || vgm < 0 || vgm > 1000000 || konami && (vgm < 64 || prom != 512)
                || events < 0 || events > 50000 || dcs && events != 0 || !dcs && (events == 0 || subsong != 0)
                || (long) header + json + rom + vgm + prom + 8L * events != bytes.length)
            throw new IOException("Invalid " + core + " capsule bounds");
        try {
            JSONObject meta = new JSONObject(new String(bytes, header, json, StandardCharsets.UTF_8));
            if (meta.getInt("version") != 1 || meta.getInt("source_rate") != (dcs ? 31250 : 48000))
                throw new IOException("Unsupported " + core + " capsule version");
            int offset = header + json;
            verify(bytes, offset, rom, meta.getString(dcs ? "banks_sha256" : "rom_sha256"));
            if (dcs) {
                JSONObject track = meta.getJSONArray("tracks").getJSONObject(subsong);
                int chips = meta.getInt("chips"), bankSize = meta.getInt("chip_bytes");
                int command = track.getInt("command"), volume = meta.getInt("volume"), frames = track.getInt("audio_frames");
                if (chips < 1 || chips > 8 || bankSize != 524288 && bankSize != 1048576 || chips * bankSize != rom
                        || command < 0 || command > 0x54ff || volume < 0 || volume > 255 || frames < 1 || frames > 37500000)
                    throw new IOException("Invalid DCS selection");
                return new BoardCapsule(Arrays.copyOfRange(bytes, offset, bytes.length),
                        new int[]{chips, bankSize, command, volume, frames});
            }
            offset += rom;
            int firstEvent = offset + vgm + prom;
            if (konami) {
                verify(bytes, offset, vgm, meta.getString("vgm_sha256"));
                verify(bytes, offset + vgm, prom, meta.getString("prom_sha256"));
            }
            verify(bytes, firstEvent, events * 8, meta.getString("events_sha256"));
            int start = meta.getInt("start_sample"), frames = meta.getInt("audio_frames");
            int circuit = konami ? 0 : meta.getInt("circuit");
            if (start < 0 || start > 4800000 || frames < 1 || frames > 57600000 || circuit < 0 || circuit > 1
                    || konami && meta.getDouble("K_gain") != 0.35)
                throw new IOException("Invalid " + core + " audio window");
            int previous = 0;
            for (int i = 0; i < events; i++) {
                int event = firstEvent + i * 8;
                int time = input.getInt(event), port = bytes[event + 4] & 255;
                int value = konami ? input.getShort(event + 6) & 65535 : bytes[event + 5] & 255;
                if (time < previous || time > start + frames
                        || (konami ? port > 5 || value > (port < 2 ? 4095 : port < 4 ? 0 : 255) || bytes[event + 5] != 0
                                   : port > 2 || port > 0 && value > 1 || input.getShort(event + 6) != 0))
                    throw new IOException("Invalid " + core + " register event");
                previous = time;
            }
            return new BoardCapsule(Arrays.copyOfRange(bytes, offset, bytes.length), new int[]{circuit, start, frames, vgm});
        } catch (JSONException failure) {
            throw new IOException("Invalid " + core + " capsule metadata", failure);
        }
    }

    private static void verify(byte[] data, int offset, int length, String expected) throws IOException {
        if (!NativeMachine.hash(Arrays.copyOfRange(data, offset, offset + length)).equals(expected))
            throw new IOException("Music capsule checksum differs");
    }
}
