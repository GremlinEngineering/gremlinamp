import Foundation
import AVFoundation

@main struct TrackerTests {
    static func main() throws {
        let resources = URL(fileURLWithPath: "Resources/Trackers", isDirectory: true)
        func data(_ name: String) throws -> Data { try Data(contentsOf: resources.appendingPathComponent(name)) }
        let weather = try TrackerWeather(data("teahouse-weather.json"))
        precondition(weather.cloud > 0 && weather.wind >= 0)
        let orbits = try JSONDecoder().decode(TrackerOrbitData.self, from: data("orbits.json"))
        precondition(orbits.valid && orbits.signal(at: Date()).distance.isFinite)
        let audio = try data("voyager-plasma.pcm")
        let plasma: [Int16] = audio.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
            stride(from: 0, to: audio.count, by: 2).map {
                Int16(bitPattern: UInt16(bytes[$0]) | UInt16(bytes[$0 + 1]) << 8)
            }
        }
        for mode in TrackerMode.allCases {
            let profile = try mode.asset.map { try JSONDecoder().decode([UInt8].self, from: data($0 + ".json")) } ?? []
            let stream = try TrackerStream(mode, profile: profile, plasma: mode == .voyager ? plasma : [])
            if mode == .orbits { gt_orbit(stream.handle, orbits.signal(at: Date())) }
            if mode == .weather { gt_weather(stream.handle, weather.rain, weather.cloud, weather.wind) }
            let (engine, _) = try stream.makeEngine(volume: 1, offlineRate: 48_000)
            try engine.start()
            let buffer = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat, frameCapacity: 4096)!
            var energy: Float = 0
            for _ in 0..<55 {
                let status = try engine.renderOffline(4096, to: buffer)
                precondition(status == .success && buffer.frameLength == 4096)
                for channel in 0..<2 {
                    let samples = UnsafeBufferPointer(start: buffer.floatChannelData![channel], count: Int(buffer.frameLength))
                    precondition(samples.allSatisfy { $0.isFinite && abs($0) <= 1 })
                    energy += samples.reduce(0) { $0 + abs($1) }
                }
            }
            engine.stop()
            precondition(energy > 10 && stream.bpm >= 40, "Нет аудио · \(mode.title)")
            print("\(mode.title): \(stream.bpm) BPM · AVAudioEngine 48 кГц")
        }
        print("9 генераторов, встроенный погодный срез и архив орбит: OK")
    }
}
