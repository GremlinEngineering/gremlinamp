package ru.oopssre.gremlinamp;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

final class ChipDecoder implements PcmSource {
    private final String engine;
    private final long handle;
    private final WavPcm wav;
    private final NativeGsf gsf;
    private final NativeBoard board;
    private final NativeMachine machine;
    private final NativePsf psf;
    private final NativeStream stream;

    ChipDecoder(String engine, File file, JSONObject track) throws IOException {
        byte[] header = new byte[0];
        if (usesNsfHeader(engine, track)) {
            try (java.io.InputStream input = new java.io.FileInputStream(file)) {
                header = new byte[128];
                for (int offset = 0, count; offset < header.length; offset += count) {
                    count = input.read(header, offset, header.length - offset);
                    if (count < 0) break;
                }
            }
        }
        byte[] input = engine.equals("machine") || engine.equals("stm") ? AudioInput.read(file) : null;
        this.engine = resolveEngine(engine, engine.equals("gme") ? header : input, track);
        machine = this.engine.equals("machine") ? openMachine(input, track) : null;
        wav = isWav(engine) ? new WavPcm(file) : null;
        gsf = null;
        psf = null;
        stream = isNativeStream(engine) ? new NativeStream(engine, AudioInput.read(file), track.optInt("subsong")) : null;
        board = engine.equals("board") ? new NativeBoard(MusicContract.board(track), AudioInput.read(file), track) : null;
        handle = switch (this.engine) {
            case "openmpt" -> NativeModule.open(input == null ? AudioInput.read(file) : input, track.optInt("subsong"));
            case "vgm" -> NativeVgm.openFile(file, preroll(track));
            case "gme" -> Gme.open(file.toString(), track.optInt("subsong"));
            case "gme-plus" -> GmePlus.open(Files.readAllBytes(file.toPath()), track.optInt("subsong"));
            case "wav", "fallback", "board", "machine", "xa", "sid" -> 0;
            default -> throw new IOException(MusicContract.unsupported(track));
        };
    }

    ChipDecoder(String engine, Library.StreamInputs input, JSONObject track) throws IOException {
        byte[] bytes = input.buffers().get(0);
        stream = isNativeStream(engine) ? new NativeStream(engine, AudioInput.unpack(bytes), track.optInt("subsong")) : null;
        byte[] capsule = engine.equals("machine") || engine.equals("stm") ? AudioInput.unpack(bytes) : bytes;
        this.engine = resolveEngine(engine, capsule, track);
        machine = this.engine.equals("machine") ? openMachine(capsule, track) : null;
        wav = isWav(engine) ? new WavPcm(bytes) : null;
        gsf = engine.equals("gsf") ? new NativeGsf(input.buffers(), input.descriptor()) : null;
        psf = MusicContract.decoderNeedsInputs(engine) && gsf == null ? new NativePsf(engine, input.buffers(), input.descriptor()) : null;
        board = engine.equals("board") ? new NativeBoard(MusicContract.board(track), AudioInput.unpack(bytes), track) : null;
        handle = switch (this.engine) {
            case "openmpt" -> NativeModule.open(AudioInput.unpack(capsule), track.optInt("subsong"));
            case "vgm" -> NativeVgm.openBuffer(bytes, preroll(track));
            case "gme" -> Gme.openBuffer(bytes, track.optInt("subsong"));
            case "gme-plus" -> GmePlus.open(bytes, track.optInt("subsong"));
            case "wav", "fallback", "gsf", "psf", "dsf", "usf", "ssf", "board", "machine", "xa", "sid" -> 0;
            default -> throw new IOException(MusicContract.unsupported(track));
        };
    }

    private static String resolveEngine(String engine, byte[] input, JSONObject track) {
        if (engine.equals("stm"))
            return input.length >= 4 && input[0] == 'S' && input[1] == 'T' && input[2] == 'M' && input[3] == 26
                    ? "machine" : "openmpt";
        return usesNsfHeader(engine, track) && usesPlus(input) ? "gme-plus" : engine;
    }

    private static NativeMachine openMachine(byte[] data, JSONObject track) throws IOException {
        return new NativeMachine(NativeMachine.Core.forFormat(MusicContract.decoderFormat(track, track.optJSONObject("asset"))),
                data, track.optInt("subsong"));
    }

    private static boolean usesNsfHeader(String engine, JSONObject track) {
        return engine.equals("gme") && "nsf".equalsIgnoreCase(track.optJSONObject("asset").optString("format"));
    }

    private static boolean usesPlus(byte[] bytes) {
        return bytes.length >= 128 && bytes[0] == 'N' && bytes[1] == 'E' && bytes[2] == 'S' &&
                bytes[3] == 'M' && bytes[4] == 26 && (bytes[123] & 4) != 0 && (bytes[9] & 255) < 128;
    }

    private static boolean isNativeStream(String engine) { return engine.equals("xa") || engine.equals("sid"); }

    static boolean isWav(String engine) { return engine.equals("wav") || engine.equals("fallback"); }

    public boolean reportsDuration() { return engine.equals("vgm") || engine.equals("openmpt") || engine.equals("xa") || machine != null || isWav(engine); }

    static int preroll(JSONObject track) {
        JSONObject playback = track.optJSONObject("playback");
        return track.optInt("native_preroll_vgm_samples", playback == null ? 0 : playback.optInt("native_preroll_vgm_samples"));
    }

    public int sampleRate() {
        if (wav != null) return wav.rate;
        if (board != null) return board.rate();
        if (psf != null) return psf.rate();
        if (stream != null) return stream.rate();
        return machine != null ? machine.rate() : NativeVgm.RATE;
    }

    public long duration() {
        if (wav != null) return wav.duration();
        if (board != null) return board.duration();
        if (machine != null) return machine.duration();
        if (psf != null) return psf.duration();
        if (stream != null) return stream.duration();
        if (engine.equals("openmpt")) return NativeModule.duration(handle);
        return engine.equals("vgm") ? NativeVgm.duration(handle) : -1;
    }

    public int render(short[] samples, int frames) throws IOException {
        if (wav != null) return wav.render(samples, frames);
        if (engine.equals("openmpt")) return NativeModule.render(handle, samples, frames);
        if (engine.equals("vgm")) return NativeVgm.render(handle, samples, frames);
        if (gsf != null) return gsf.render(samples, frames);
        if (board != null) return board.render(samples, frames);
        if (machine != null) return machine.render(samples, frames);
        if (psf != null) return psf.render(samples, frames);
        if (stream != null) return stream.render(samples, frames);
        if (engine.equals("gme-plus")) GmePlus.render(handle, samples, frames);
        else Gme.render(handle, samples, frames);
        return frames;
    }

    public long seek(long frame) throws IOException {
        if (wav != null) return wav.seek(frame);
        if (engine.equals("openmpt")) return NativeModule.seek(handle, frame);
        if (engine.equals("vgm")) return NativeVgm.seek(handle, frame);
        if (gsf != null) return gsf.seek(frame);
        if (board != null) return board.seek(frame);
        if (machine != null) return machine.seek(frame);
        if (psf != null) return psf.seek(frame);
        if (stream != null) return stream.seek(frame);
        long ms = frame * 1000 / sampleRate();
        if (engine.equals("gme-plus")) GmePlus.seek(handle, (int) ms);
        else Gme.seek(handle, (int) ms);
        return ms * sampleRate() / 1000;
    }

    public boolean ended() {
        if (wav != null) return wav.ended();
        return switch (engine) {
            case "vgm" -> NativeVgm.ended(handle);
            case "openmpt" -> NativeModule.ended(handle);
            case "gme-plus" -> GmePlus.ended(handle);
            case "gsf" -> false;
            case "board" -> board.ended();
            case "machine" -> machine.ended();
            case "psf", "dsf", "usf", "ssf" -> psf.ended();
            case "xa", "sid" -> stream.ended();
            default -> Gme.ended(handle);
        };
    }

    @Override public void close() throws IOException {
        if (wav != null) wav.close();
        else if (engine.equals("vgm")) NativeVgm.close(handle);
        else if (engine.equals("openmpt")) NativeModule.close(handle);
        else if (gsf != null) gsf.close();
        else if (board != null) board.close();
        else if (machine != null) machine.close();
        else if (psf != null) psf.close();
        else if (stream != null) stream.close();
        else if (engine.equals("gme-plus")) GmePlus.close(handle);
        else Gme.close(handle);
    }
}
