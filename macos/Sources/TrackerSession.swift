import Foundation
import AVFoundation
import CryptoKit

@MainActor final class TrackerSession {
    let mode: TrackerMode
    let stream: TrackerStream
    private var weather: TrackerWeather?
    private var orbits: TrackerOrbitData?
    private var refresh: Task<Void, Never>?
    private var microphone: AVAudioEngine?
    private var wantsMicrophone = false
    private var permissionPending = false
    private var lastSecond = -1
    private var playing = false

    init(mode: TrackerMode) throws {
        self.mode = mode
        stream = try TrackerStream(mode)
        if mode == .weather {
            weather = Self.candidates("teahouse-weather").compactMap { try? TrackerWeather($0) }.max { $0.at < $1.at }
        }
        if mode == .orbits {
            orbits = Self.candidates("orbits").compactMap { try? JSONDecoder().decode(TrackerOrbitData.self, from: $0) }
                .first { $0.valid }
        }
        tick()
    }

    private static func cache(_ name: String) -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("GremlinAmp/Tracker", isDirectory: true).appendingPathComponent(name + ".json")
    }
    private static func candidates(_ name: String) -> [Data] {
        [(try? Data(contentsOf: cache(name))), (try? TrackerResources.data(name))].compactMap { $0 }
    }
    private static func save(_ data: Data, as name: String) {
        let url = cache(name)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }

    var summary: String {
        if let weather { return weather.summary }
        if let orbits { return orbits.summary(at: Date()) }
        return mode.detail
    }

    func tick() {
        let now = Date()
        let second = Int(now.timeIntervalSince1970)
        guard lastSecond != second else { return }
        lastSecond = second
        stream.updateClock()
        if let weather { gt_weather(stream.handle, weather.rain, weather.cloud, weather.wind) }
        if let orbits { gt_orbit(stream.handle, orbits.signal(at: now)) }
    }

    func setPlaying(_ value: Bool) {
        guard playing != value else { return }
        playing = value
        updateOptions()
    }

    func updateOptions() {
        stream.updateOptions()
        if mode == .weather || mode == .orbits {
            if playing && TrackerOption.refresh.enabled(mode) {
                if refresh == nil { startRefresh() }
            } else {
                refresh?.cancel()
                refresh = nil
            }
        }
        let mic = playing && mode == .moscow && TrackerOption.microphone.enabled(mode)
        if wantsMicrophone != mic {
            wantsMicrophone = mic
            if mic { requestMicrophone() } else { stopMicrophone() }
        }
    }

    private func startRefresh() {
        refresh = Task { [weak self] in
            while !Task.isCancelled {
                await self?.fetch()
                do { try await Task.sleep(nanoseconds: 600_000_000_000) } catch { return }
            }
        }
    }

    private func fetch() async {
        if mode == .weather {
            guard let (data, response) = try? await SiteHTTP.data(TrackerWeather.endpoint, limit: 524_288),
                  !Task.isCancelled, response.statusCode == 200, let fresh = try? TrackerWeather(data),
                  fresh.at >= (weather?.at ?? .distantPast) else { return }
            weather = fresh
            Self.save(data, as: "teahouse-weather")
        } else if let orbits {
            let fresh = await orbits.refreshed()
            guard !Task.isCancelled else { return }
            self.orbits = fresh
            if let data = try? JSONEncoder().encode(fresh) { Self.save(data, as: "orbits") }
        }
        lastSecond = -1
        tick()
    }

    private func requestMicrophone() {
        guard !permissionPending else { return }
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: startMicrophone()
        case .notDetermined:
            permissionPending = true
            Task { [weak self] in
                let allowed = await AVCaptureDevice.requestAccess(for: .audio)
                guard let self else { return }
                permissionPending = false
                if allowed && wantsMicrophone { startMicrophone() }
            }
        default: break
        }
    }

    private func startMicrophone() {
        guard microphone == nil else { return }
        let audio = AVAudioEngine()
        let input = audio.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.channelCount > 0, format.sampleRate >= 16_000 else { return }
        let stream = self.stream
        let rate = format.sampleRate / 16_000
        let scratch = MicrophoneSamples()
        input.installTap(onBus: 0, bufferSize: 1920, format: format) { buffer, _ in
            guard let input = buffer.floatChannelData?[0] else { return }
            let count = min(1280, Int(Double(buffer.frameLength) / rate))
            for i in 0..<count { scratch.samples[i] = input[Int(Double(i) * rate)] }
            gt_street(stream.handle, scratch.samples, Int32(count))
        }
        do {
            try audio.start()
            microphone = audio
        } catch {
            input.removeTap(onBus: 0)
        }
    }

    private func stopMicrophone() {
        microphone?.inputNode.removeTap(onBus: 0)
        microphone?.stop()
        microphone = nil
        gt_street(stream.handle, nil, 0)
    }
}

private final class MicrophoneSamples: @unchecked Sendable {
    let samples = UnsafeMutablePointer<Float>.allocate(capacity: 1280)
    deinit { samples.deallocate() }
}

struct RadioStation: Decodable, Identifiable, Equatable {
    var title: String
    var url: URL
    var id: String { url.absoluteString }

    static let publicStations = [
        station("RolandRadio · Amstrad CPC", "https://streaming.rolandradio.net/rolandradio"),
        station("Kohina · игры и демосцена", "https://kohina.brona.dk/icecast/stream.ogg"),
        station("9128 Live", "https://streams.radio.co/s0aa1e6f4a/listen"),
        station("Ambient Sleeping Pill", "https://radio.stereoscenic.com/asp-s"),
        station("MUGEN Radio", "https://mugenradio.com/hls/live.m3u8")
    ]
    static let personal: [Self] = {
        guard let url = Bundle.main.url(forResource: "personal-radio", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let stations = try? JSONDecoder().decode([Self].self, from: data) else { return [] }
        return stations.filter { $0.url.scheme == "https" && $0.url.host != nil && $0.url.user == nil
            && $0.url.password == nil && $0.url.fragment == nil && !$0.title.isEmpty }
    }()
    static func accepts(_ code: String) -> Bool {
        guard let url = Bundle.main.url(forResource: "personal-radio", withExtension: "sha256"),
              let resource = try? String(contentsOf: url, encoding: .ascii) else { return false }
        let expected = resource.trimmingCharacters(in: .whitespacesAndNewlines)
        let value = SHA256.hash(data: Data(code.trimmingCharacters(in: .whitespacesAndNewlines).utf8))
        return value.map { String(format: "%02x", $0) }.joined() == expected
    }
    private static func station(_ title: String, _ url: String) -> Self {
        Self(title: title, url: URL(string: url)!)
    }
}
