import Foundation
import AVFoundation

@main struct AudioOutputTests {
    static func main() throws {
        for fixture in ["tone.nsf", "tone.ogg", "tone.vgz", "ym2413-only.vgm", "ym2413-only.vgz"] {
            for rate in [44100.0, 48000.0] {
                try check(fixture: fixture, rate: rate)
            }
        }
        // Repeated graph creation/release exercises the same ownership used by
        // Next/Previous. No speaker or system volume setting is needed.
        for _ in 0..<30 { try check(fixture: "tone.nsf", rate: 48000) }
        for fixture in ["tone.mp3", "tone.m4a", "tone.wav", "tone.flac"] {
            try checkSystem(fixture: fixture)
        }
        print("AVAudioEngine: audible stereo PCM, 44.1/48 kHz, seek and graph lifetime: passed")
        print("System audio: MP3, AAC/M4A, WAV, FLAC preparation and nonzero PCM: passed")
    }

    static func checkSystem(fixture: String) throws {
        let url = URL(fileURLWithPath: "tests/" + fixture)
        let player = try SystemAudio.makePlayer(url: url, volume: 0.8)
        guard player.duration > 0.1, player.volume > 0 else {
            throw PlayerError.message("\(fixture): неверная длительность или громкость")
        }
        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 4096) else {
            throw PlayerError.message("\(fixture): не удалось создать буфер")
        }
        try file.read(into: buffer)
        guard buffer.frameLength > 0, let channels = buffer.floatChannelData else {
            throw PlayerError.message("\(fixture): файл не содержит PCM")
        }
        var energy = 0.0
        for c in 0..<Int(buffer.format.channelCount) {
            for i in 0..<Int(buffer.frameLength) {
                let sample = Double(channels[c][i])
                guard sample.isFinite else { throw PlayerError.message("\(fixture): неверный PCM") }
                energy += sample * sample
            }
        }
        guard energy > 0.001 else { throw PlayerError.message("\(fixture): декодер вернул тишину") }
    }

    static func check(fixture: String, rate: Double) throws {
        let url = URL(fileURLWithPath: "tests/" + fixture)
        let game = Game(["id": "audio-test", "title": "Test"])
        let track = Track([
            "title": "Synthetic tone", "format": url.pathExtension,
            "backend": "gme", "duration_known": true, "play_ms": 1000, "fade_ms": 0
        ], game: game, manifestURL: "https://games.oopssre.ru/", index: 0)
        let stream = try NativeStream(url: url, track: track)
        let (engine, node) = try stream.makeEngine(volume: 0.8, offlineRate: rate)
        defer { engine.stop(); engine.detach(node) }
        try engine.start()
        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: engine.manualRenderingFormat, frameCapacity: 4096
        ) else { throw PlayerError.message("Не удалось создать буфер проверки") }
        let energy = try render(engine, buffer: buffer)
        guard energy.0 > 0.00001, energy.1 > 0.00001 else {
            throw PlayerError.message("\(fixture) · \(rate) Гц: аудиограф вернул тишину")
        }
        engine.pause()
        guard ga_seek(stream.handle, 100) != 0 else {
            throw PlayerError.message("Не удалось проверить перемотку")
        }
        try engine.start()
        let afterSeek = try render(engine, buffer: buffer)
        guard afterSeek.0 > 0.00001, afterSeek.1 > 0.00001 else {
            throw PlayerError.message("\(fixture): тишина после перемотки")
        }
    }

    static func render(_ engine: AVAudioEngine, buffer: AVAudioPCMBuffer) throws -> (Double, Double) {
        var energy = [0.0, 0.0]
        let sizes: [AVAudioFrameCount] = [1, 3, 127, 257, 1024, 4096]
        var rendered = 0
        for size in sizes {
            var attempts = 0
            while true {
                let status = try engine.renderOffline(size, to: buffer)
                if status == .cannotDoInCurrentContext {
                    attempts += 1
                    if attempts < 100 { continue }
                }
                guard status == .success, let channels = buffer.floatChannelData else {
                    throw PlayerError.message("Аудиограф не смог подготовить PCM: \(status)")
                }
                for c in 0..<2 {
                    for i in 0..<Int(buffer.frameLength) {
                        let sample = Double(channels[c][i])
                        guard sample.isFinite else { throw PlayerError.message("Некорректный PCM") }
                        energy[c] += sample * sample
                    }
                }
                rendered += Int(buffer.frameLength)
                break
            }
        }
        guard rendered > 0 else { throw PlayerError.message("Аудиограф не подготовил ни одного кадра") }
        return (energy[0] / Double(rendered), energy[1] / Double(rendered))
    }
}
