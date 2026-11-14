import Foundation
import AVFoundation

enum TrackerMode: Int, CaseIterable, Identifiable {
    case tea, workshop, weather, city, voyager, stars, solar, orbits, moscow
    var id: Int { rawValue }
    var title: String {
        switch self {
        case .tea: return "Чайная"
        case .workshop: return "Мастерская"
        case .weather: return "Погода"
        case .city: return "Город"
        case .voyager: return "Вояджер"
        case .stars: return "Звёзды"
        case .solar: return "Солнечная система"
        case .orbits: return "Орбиты"
        case .moscow: return "Москва"
        }
    }
    var detail: String {
        switch self {
        case .tea: return "Тихий эмбиент · время суток"
        case .workshop: return "Механический ритм · смещающиеся акценты"
        case .weather: return "Погодный эмбиент · Затонувшая чайная"
        case .city: return "Чиптюн · местное время и часы пик"
        case .voyager: return "Voyager 1 · плазменные волны"
        case .stars: return "Kepler · колебания яркости звезды"
        case .solar: return "Voyager · Юпитер / Cassini · Сатурн"
        case .orbits: return "Луна · солнечный ветер · вспышки"
        case .moscow: return "Городской трекер · по мотивам Sonic City"
        }
    }
    var asset: String? {
        switch self {
        case .voyager: return "voyager"
        case .stars: return "stars"
        case .solar: return "solar"
        default: return nil
        }
    }
    var options: [TrackerOption] {
        switch self {
        case .tea, .city: return [.clock]
        case .workshop: return [.accents]
        case .weather, .orbits: return [.refresh]
        case .voyager: return [.plasma, .noise]
        case .stars, .solar: return [.noise]
        case .moscow: return [.microphone]
        }
    }
}
enum TrackerOption: String, Identifiable {
    case clock, accents, refresh, plasma, noise, microphone
    var id: String { rawValue }
    var label: String {
        switch self {
        case .clock: return "Менять звучание по времени суток"
        case .accents: return "Смещать ритмические акценты"
        case .refresh: return "Обновлять данные каждые 10 минут"
        case .plasma: return "Архивный сигнал плазмы"
        case .noise: return "Шум и искажения"
        case .microphone: return "Уличный звук через микрофон"
        }
    }
    private func key(_ mode: TrackerMode) -> String { "tracker.\(mode.rawValue).\(rawValue)" }
    func enabled(_ mode: TrackerMode) -> Bool {
        UserDefaults.standard.object(forKey: key(mode)) as? Bool ?? (self != .noise && self != .microphone)
    }
    func set(_ mode: TrackerMode, _ enabled: Bool) { UserDefaults.standard.set(enabled, forKey: key(mode)) }
}

enum TrackerResources {
    static func data(_ name: String, _ ext: String = "json") throws -> Data {
        guard let url = Bundle.main.url(forResource: name, withExtension: ext, subdirectory: "Trackers")
        else { throw PlayerError.message("Отсутствует ресурс генератора \(name)") }
        return try Data(contentsOf: url)
    }
}

final class TrackerStream: @unchecked Sendable {
    let handle: OpaquePointer
    static let rate = 44_100.0
    private let mode: TrackerMode

    init(_ mode: TrackerMode, profile: [UInt8]? = nil, plasma: [Int16]? = nil) throws {
        self.mode = mode
        let profile = try profile ?? mode.asset.map {
            try JSONDecoder().decode([UInt8].self, from: TrackerResources.data($0))
        } ?? []
        let plasma = try plasma ?? (mode == .voyager ? Self.recording() : [])
        let seed = UInt64.random(in: .min ... .max)
        guard let tracker = profile.withUnsafeBufferPointer({ p in
            plasma.withUnsafeBufferPointer { s in
                gt_create(Int32(mode.rawValue), seed, p.baseAddress, Int32(p.count), s.baseAddress, Int32(s.count))
            }
        }) else { throw PlayerError.message("Не удалось подготовить генератор") }
        handle = tracker
        updateOptions()
        updateClock()
    }

    private static func recording() throws -> [Int16] {
        let data = try TrackerResources.data("voyager-plasma", "pcm")
        guard data.count % 2 == 0 else { throw PlayerError.message("Повреждена запись Voyager 1") }
        return data.withUnsafeBytes { bytes in
            stride(from: 0, to: bytes.count, by: 2).map {
                Int16(bitPattern: UInt16(bytes[$0]) | UInt16(bytes[$0 + 1]) << 8)
            }
        }
    }

    deinit { gt_destroy(handle) }
    var bpm: Int { Int(gt_bpm(handle)) }

    func updateOptions() {
        gt_options(handle, TrackerOption.clock.enabled(mode) ? 1 : 0, TrackerOption.accents.enabled(mode) ? 1 : 0,
                   TrackerOption.noise.enabled(mode) ? 1 : 0, TrackerOption.plasma.enabled(mode) ? 1 : 0)
    }
    func updateClock() {
        let time = Calendar.current.dateComponents([.hour, .minute], from: Date())
        gt_clock(handle, Int32((time.hour ?? 0) * 60 + (time.minute ?? 0)))
    }

    func makeEngine(volume: Float, offlineRate: Double? = nil) throws -> (AVAudioEngine, AVAudioSourceNode) {
        let format = AVAudioFormat(standardFormatWithSampleRate: Self.rate, channels: 2)!
        let node = AVAudioSourceNode(format: format) { [self] silence, _, frames, output in
            let buffers = UnsafeMutableAudioBufferListPointer(output)
            guard buffers.count == 2,
                  let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                  let right = buffers[1].mData?.assumingMemoryBound(to: Float.self),
                  buffers.allSatisfy({ $0.mNumberChannels == 1 && $0.mDataByteSize / 4 >= frames }) else {
                silence.pointee = true
                return -50
            }
            silence.pointee = false
            _ = gt_render(handle, left, right, Int32(frames))
            return noErr
        }
        return try AudioGraph.make(node: node, format: format, volume: volume, offlineRate: offlineRate)
    }
}
