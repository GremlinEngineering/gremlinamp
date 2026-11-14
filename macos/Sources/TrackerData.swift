import Foundation

struct TrackerWeather {
    let at: Date
    let rain: Float
    let cloud: Float
    let wind: Float
    let summary: String

    static let endpoint = URL(string: "https://games.oopssre.ru/assets/arcade/views/dynamic/sunken-teahouse/devices/phone_1440x3120/current.json")!

    init(_ data: Data) throws {
        let json = try JSONSerialization.jsonObject(with: data) as? JSON ?? [:]
        guard json.int("schema") == 1, json.string("theme_id") == "sunken-teahouse",
              json.string("device") == "phone_1440x3120", let inputs = json["inputs"] as? JSON,
              let weather = inputs["weather"] as? JSON,
              let at = trackerDate(inputs.string("at", json.string("at"))),
              at.timeIntervalSinceNow < 300, (0...99).contains(weather.int("weather_code", -1))
        else { throw PlayerError.message("Некорректный срез погоды") }
        self.at = at
        let code = weather.int("weather_code")
        let rainCode: Float
        switch code {
        case 95...99: rainCode = 1
        case 85...86: rainCode = code == 85 ? 0.38 : 0.70
        case 80...82: rainCode = 0.36 + 0.28 * Float(code - 80)
        case 71...77: rainCode = 0.25 + 0.07 * Float(code - 71)
        case 61...67: rainCode = 0.36 + 0.10 * Float(code - 61)
        case 51...57: rainCode = 0.20 + 0.05 * Float(code - 51)
        default: rainCode = 0
        }
        rain = max(rainCode, Self.unit((weather["precipitation"] as? NSNumber)?.doubleValue ?? 0, scale: 4))
        let clouds = (weather["cloud_cover"] as? NSNumber)?.doubleValue
        let speed = (weather["wind_speed_10m"] as? NSNumber)?.doubleValue
        cloud = clouds.map { Self.unit($0, scale: 100) } ?? (code < 3 ? Float(code) * 0.25 : code == 3 ? 0.78 : 0.94)
        wind = speed.map { Self.unit($0 * 3.6, scale: 65) } ?? 0.15 + 0.4 * rain
        let details = [clouds.map { "Облака \(Int(max(0, min(100, $0))))%" },
                       speed.map { String(format: "Ветер %.1f м/с", $0) }].compactMap { $0 }.joined(separator: " · ")
        summary = "Затонувшая чайная · срез \(at.formatted(date: .numeric, time: .shortened))"
            + (details.isEmpty ? "" : "\n" + details)
    }

    static func unit(_ value: Double, scale: Double = 1) -> Float {
        value.isFinite ? Float(max(0, min(1, value / scale))) : 0
    }
}

private func trackerDate(_ input: String) -> Date? {
    let formatter = ISO8601DateFormatter()
    let last = input.suffix(6)
    let value = input.hasSuffix("Z") || last.contains("+") || last.contains("-") ? input : input + "Z"
    if let date = formatter.date(from: value) { return date }
    formatter.formatOptions.insert(.withFractionalSeconds)
    return formatter.date(from: value)
}

struct TrackerOrbitData: Codable {
    struct Moon: Codable { let retrieved: Int64; let points: [[Double]] }
    struct Wind: Codable { let time: Int64; let speed: Double; let density: Double; let source: String }
    struct Flux: Codable { let time: Int64; let value: Double; let satellite: Int }
    struct Flare: Codable { let peak: Int64; let classification: String }
    var schema: Int
    var moon: Moon
    var wind: Wind
    var flux: Flux
    var flare: Flare?

    var valid: Bool {
        schema == 1 && Self.valid(moon)
            && wind.time > 0 && wind.speed > 0 && wind.speed < 3000 && (0..<1000).contains(wind.density)
            && flux.time > 0 && flux.value > 0 && flux.value < 0.1 && flux.satellite > 0
    }
    private static func valid(_ moon: Moon) -> Bool {
        moon.retrieved > 0 && (24...100).contains(moon.points.count)
            && moon.points.allSatisfy { point in
                point.count == 4 && point.allSatisfy(\.isFinite) && (0...1e11).contains(point[0])
                    && (300_000...450_000).contains(sqrt(point.dropFirst().reduce(0) { $0 + $1 * $1 }))
            }
            && zip(moon.points, moon.points.dropFirst()).allSatisfy { pair in pair.1[0] - pair.0[0] == 3600 }
    }

    private func position(_ time: Double) -> [Double] {
        let points = moon.points
        guard let next = points.firstIndex(where: { $0[0] >= time }) else { return points[points.count - 1] }
        guard next > 0 else { return points[0] }
        let a = points[next - 1], b = points[next]
        let f = (time - a[0]) / (b[0] - a[0])
        return [time] + (1...3).map { a[$0] + (b[$0] - a[$0]) * f }
    }

    func signal(at date: Date) -> GTOrbit {
        let p = position(date.timeIntervalSince1970)
        let distance = sqrt(p.dropFirst().reduce(0) { $0 + $1 * $1 })
        var peak: Int64 = 0
        var level: Float = 0
        if let f = flare, (0...3600).contains(date.timeIntervalSince1970 - Double(f.peak)) {
            peak = f.peak
            level = ["X": 1, "M": 0.75, "C": 0.48][String(f.classification.prefix(1))] ?? 0.28
        }
        return GTOrbit(pan: Float(p[2] / distance), distance: TrackerWeather.unit(distance - 350_000, scale: 60_000),
                       elevation: Float(p[3] / distance), speed: TrackerWeather.unit(wind.speed - 250, scale: 550),
                       density: TrackerWeather.unit(log1p(wind.density), scale: 3.5),
                       xray: TrackerWeather.unit(log10(flux.value) + 8, scale: 4), flare: peak, flareLevel: level)
    }

    func summary(at date: Date) -> String {
        let format = DateFormatter()
        format.dateFormat = "dd.MM HH:mm"
        format.timeZone = TimeZone(secondsFromGMT: 0)
        func stamp(_ time: Double) -> String { format.string(from: Date(timeIntervalSince1970: time)) }
        return "UTC · Луна JPL \(stamp(position(date.timeIntervalSince1970)[0]))"
            + " · Ветер NOAA \(stamp(Double(wind.time))) · GOES-\(flux.satellite) \(stamp(Double(flux.time)))"
    }

    func refreshed() async -> TrackerOrbitData {
        let now = Date()
        let time = Int64(now.timeIntervalSince1970)
        async let windRows = Self.noaa("rtsw/rtsw_wind_1m.json", limit: 8_000_000)
        async let fluxRows = Self.noaa("goes/primary/xrays-6-hour.json", limit: 500_000)
        async let flareRows = Self.noaa("goes/primary/xray-flares-7-day.json", limit: 500_000)
        async let nextMoon = Self.fetchMoon(now)
        var next = self
        for row in await windRows ?? [] {
            guard row.bool("active"), row.int("overall_quality") == 0,
                  let at = trackerDate(row.string("time_tag")).map({ Int64($0.timeIntervalSince1970) }),
                  at >= next.wind.time, at <= time + 300,
                  let speed = (row["proton_speed"] as? NSNumber)?.doubleValue, (0..<3000).contains(speed), speed > 0,
                  let density = (row["proton_density"] as? NSNumber)?.doubleValue, (0..<1000).contains(density) else { continue }
            next.wind = Wind(time: at, speed: speed, density: density, source: row.string("source"))
        }
        for row in await fluxRows ?? [] {
            guard row.string("energy") == "0.1-0.8nm",
                  let at = trackerDate(row.string("time_tag")).map({ Int64($0.timeIntervalSince1970) }),
                  at >= next.flux.time, at <= time + 300,
                  let value = (row["flux"] as? NSNumber)?.doubleValue, value > 0, value < 0.1,
                  row.int("satellite") > 0 else { continue }
            next.flux = Flux(time: at, value: value, satellite: row.int("satellite"))
        }
        for row in await flareRows ?? [] {
            let label = row.string("max_class")
            guard label.range(of: "^[ABCMX][0-9]{1,3}(\\.[0-9]+)?$", options: .regularExpression) != nil,
                  let at = trackerDate(row.string("max_time")).map({ Int64($0.timeIntervalSince1970) }),
                  at >= (next.flare?.peak ?? 0), at <= time + 300 else { continue }
            next.flare = Flare(peak: at, classification: label)
        }
        if let moon = await nextMoon, moon.retrieved >= next.moon.retrieved { next.moon = moon }
        return next
    }

    private static func noaa(_ path: String, limit: Int) async -> [JSON]? {
        guard let url = URL(string: "https://services.swpc.noaa.gov/json/" + path),
              let (data, response) = try? await SiteHTTP.data(url, limit: limit), response.statusCode == 200 else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [JSON]
    }

    private static func fetchMoon(_ now: Date) async -> Moon? {
        let format = DateFormatter()
        format.dateFormat = "yyyy-MM-dd"
        format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = TimeZone(secondsFromGMT: 0)
        var endpoint = URLComponents(string: "https://ssd.jpl.nasa.gov/api/horizons.api")
        endpoint?.queryItems = [
            ("format", "json"), ("COMMAND", "'301'"), ("CENTER", "'500@399'"),
            ("EPHEM_TYPE", "VECTORS"), ("VEC_TABLE", "1"), ("CSV_FORMAT", "YES"),
            ("OUT_UNITS", "KM-S"), ("REF_PLANE", "ECLIPTIC"), ("TIME_TYPE", "UT"),
            ("START_TIME", "'\(format.string(from: now.addingTimeInterval(-86400)))'"),
            ("STOP_TIME", "'\(format.string(from: now.addingTimeInterval(172800)))'"), ("STEP_SIZE", "'1 h'")
        ].map { URLQueryItem(name: $0.0, value: $0.1) }
        guard let url = endpoint?.url,
              let (data, response) = try? await SiteHTTP.data(url, limit: 80_000), response.statusCode == 200,
              let json = (try? JSONSerialization.jsonObject(with: data)) as? JSON,
              let signature = json["signature"] as? JSON, signature.string("source") == "NASA/JPL Horizons API"
        else { return nil }
        let output = json.string("result")
        guard let start = output.range(of: "$$SOE"), let end = output.range(of: "$$EOE"), start.upperBound < end.lowerBound else { return nil }
        let points = output[start.upperBound..<end.lowerBound].split(whereSeparator: \.isNewline).compactMap { line -> [Double]? in
            let cells = line.split(separator: ",", omittingEmptySubsequences: false)
            guard cells.count >= 5, let jd = Double(cells[0].trimmingCharacters(in: .whitespaces)) else { return nil }
            let axes = cells[2...4].compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
            guard axes.count == 3 else { return nil }
            return [((jd - 2_440_587.5) * 86400).rounded()] + axes
        }
        let moon = Moon(retrieved: Int64(now.timeIntervalSince1970), points: points)
        return valid(moon) ? moon : nil
    }
}
