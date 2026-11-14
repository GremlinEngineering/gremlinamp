import Foundation

enum ContinuationMode: String, CaseIterable, Identifiable {
    case stop, series, genre, similar, rhythm, sound
    var id: String { rawValue }
    var title: String {
        switch self {
        case .stop: return "Остановиться"
        case .series: return "Следующая игра серии"
        case .genre: return "Игра того же жанра"
        case .similar: return "Похожие игры"
        case .rhythm: return "По ритму"
        case .sound: return "По звучанию"
        }
    }
    var acoustic: Bool { self == .rhythm || self == .sound }
}

enum SyncInterval: String, CaseIterable, Identifiable {
    case hourly, morning
    var id: String { rawValue }
    var title: String { self == .hourly ? "Каждый час" : "Ежедневно после 07:00 МСК" }
}

enum SyncSchedule {
    static func due(now: Date, last: Date?, interval: SyncInterval) -> Bool {
        if interval == .hourly { return last.map{$0>now || now.timeIntervalSince($0)>=3600} ?? true }
        var cal=Calendar(identifier:.gregorian)
        cal.timeZone=TimeZone(identifier:"Europe/Moscow")!
        let morning=cal.date(bySettingHour:7,minute:0,second:0,of:now)!
        guard let last=last,last<=now else {return now>=morning}
        let cutoff=now>=morning ? morning : cal.date(byAdding:.day,value:-1,to:morning)!
        return last<cutoff
    }
}

enum CollectionState: String {
    case download, partial, save, downloaded, updates
    var title: String {
        switch self {
        case .download: return "Скачать"
        case .partial: return "Докачать"
        case .save: return "Сохранить"
        case .downloaded: return "Скачано"
        case .updates: return "Есть обновления"
        }
    }
    static func evaluate(total: Int, available: Int, saved: Bool) -> CollectionState {
        if total>0 && total==available { return saved ? .downloaded : .save }
        if saved { return .updates }
        return available>0 ? .partial : .download
    }
}

struct DownloadPlan {
    var games: [Game]
    var bytes: Int64
    var states: [String:CollectionState]
}

enum ContinuationRules {
    static func candidates(seed: Game, games: [Game], series: [MusicSeries], mode: ContinuationMode,
                           played: Set<String>, platform: String, year: String) -> [Game] {
        let sameSeries=Set(series.filter{$0.members.contains(seed.id)}.flatMap(\.members))
        let source=seed.genres ?? []
        func score(_ game: Game) -> Int {
            let common=source.intersection(game.genres ?? []).count
            switch mode {
            case .series: return sameSeries.contains(game.id) ? 100 : 0
            case .genre: return common*10
            case .similar: return (sameSeries.contains(game.id) ? 100 : 0)+common*10+(common>0 && seed.platform==game.platform ? 2 : 0)
            default: return 0
            }
        }
        return games.filter {
            $0.id != seed.id && !played.contains($0.id) && score($0)>0 &&
            (platform.isEmpty || $0.platform==platform) &&
            (year.isEmpty || ($0.year.map(String.init) ?? "Не указан")==year)
        }.sorted { a,b in
            let sa=score(a), sb=score(b)
            if sa != sb { return sa>sb }
            let ay=a.year ?? 9999, by=b.year ?? 9999
            if mode == .series {
                let ar=ay>=(seed.year ?? 0), br=by>=(seed.year ?? 0)
                if ar != br { return ar }
            }
            if ay != by { return ay<by }
            return a.title.localizedStandardCompare(b.title) == .orderedAscending
        }
    }
    // Every queue entry is visited once before an optional new shuffle cycle.
    static func next(index: Int, count: Int, visited: Set<Int>, shuffle: Bool, loop: Bool) -> Int? {
        guard count>0 else { return nil }
        if !shuffle { return index+1<count ? index+1 : (loop ? 0 : nil) }
        let remaining=(0..<count).filter{$0 != index && !visited.contains($0)}
        if let next=remaining.randomElement() { return next }
        return loop ? (count==1 ? 0 : (0..<count).filter{$0 != index}.randomElement()) : nil
    }
}

enum SiteURLRules {
    static let base=URL(string:"https://games.oopssre.ru/")!
    static func resolve(_ value: String, from base: URL=SiteURLRules.base) throws -> URL {
        guard let url=URL(string:value,relativeTo:base)?.absoluteURL,
              url.scheme=="https", url.host==Self.base.host, url.port==nil,
              url.user==nil, url.password==nil else {
            throw PlayerError.message("Недопустимая ссылка сайта")
        }
        return url
    }
}

struct ClientRelease: Codable, Equatable {
    struct Image: Codable, Equatable {
        var url: String
        var sha256: String
        var bytes: Int
    }
    var schema_version: Int
    var bundle_id: String
    var version: String
    var build: Int
    var minimum_macos: String
    var dmg: [String:Image]
    func image(architecture: String, installedBuild: Int, os: OperatingSystemVersion) throws -> Image? {
        guard schema_version==1, bundle_id=="ru.oopssre.gremlinamp.mac", build>0,
              version.range(of:"^[0-9]+(\\.[0-9]+){1,3}$",options:.regularExpression) != nil else {
            throw PlayerError.message("Некорректные данные релиза macOS")
        }
        guard build>installedBuild else { return nil }
        let parts=minimum_macos.split(separator:".").compactMap{Int($0)}
        guard !parts.isEmpty,parts.count<=3,parts.allSatisfy({$0>=0}),
              minimum_macos.range(of:"^[0-9]+(\\.[0-9]+){0,2}$",options:.regularExpression) != nil else {
            throw PlayerError.message("В релизе не указана версия macOS")
        }
        let current=[os.majorVersion,os.minorVersion,os.patchVersion]
        let required=parts+Array(repeating:0,count:3-parts.count)
        guard !current.lexicographicallyPrecedes(required) else {
            throw PlayerError.message("Для новой версии нужна macOS \(minimum_macos)")
        }
        guard let image=dmg[architecture] ?? dmg["universal"] else {
            throw PlayerError.message("Нет обновления для архитектуры \(architecture)")
        }
        let url=try SiteURLRules.resolve(image.url)
        guard url.pathExtension.lowercased()=="dmg",url.fragment==nil,
              image.sha256.range(of:"^[a-fA-F0-9]{64}$",options:.regularExpression) != nil,
              image.bytes>0,image.bytes<=1024*1024*1024 else {
            throw PlayerError.message("Некорректный DMG в релизе")
        }
        return image
    }
}

struct SimilarityIndex: Decodable {
    struct Neighbor: Decodable { var key: String; var distance: Double }
    struct Reference: Decodable {
        var game_id: String; var track_id: String?; var index: Int?
        enum CodingKeys: String,CodingKey {case game_id,track_id,index}
        init(from decoder: Decoder) throws {
            let c=try decoder.container(keyedBy:CodingKeys.self)
            game_id=try c.decode(String.self,forKey:.game_id)
            track_id=(try? c.decode(String.self,forKey:.track_id)) ?? (try? c.decode(Int.self,forKey:.track_id)).map(String.init)
            index=try c.decodeIfPresent(Int.self,forKey:.index)
        }
    }
    struct Row: Decodable { var refs: [Reference]; var neighbors: [String:[Neighbor]] }
    var schema_version: Int
    var tracks: [String:Row]
    static func validKey(_ key: String) -> Bool {
        key.range(of:"^[a-fA-F0-9]{64}:[0-9]{1,5}$",options:.regularExpression) != nil
    }
    static func decode(_ data: Data) throws -> SimilarityIndex {
        let value=try JSONDecoder().decode(Self.self,from:data)
        guard value.schema_version==1, value.tracks.count<=250000,
              value.tracks.keys.allSatisfy(validKey) else {
            throw PlayerError.message("Неподдерживаемый индекс похожей музыки")
        }
        return value
    }
    func targets(seed: String, mode: ContinuationMode, played: Set<String>) -> [(String,Reference)] {
        guard mode.acoustic, let neighbors=tracks[seed]?.neighbors[mode.rawValue] else { return [] }
        var seen=played; seen.insert(seed)
        return neighbors.prefix(256).filter { Self.validKey($0.key) && $0.distance.isFinite && $0.distance>=0 }
            .sorted{$0.distance<$1.distance}.flatMap { neighbor -> [(String,Reference)] in
                guard seen.insert(neighbor.key).inserted else { return [] }
                return (tracks[neighbor.key]?.refs ?? []).filter{!$0.game_id.isEmpty}.map{(neighbor.key,$0)}
            }
    }
    func reason(seed: String, mode: ContinuationMode) -> String? {
        guard !seed.isEmpty else { return "Сначала запустите композицию" }
        guard tracks[seed] != nil else { return "Эта композиция ещё не проанализирована" }
        return targets(seed:seed,mode:mode,played:[]).isEmpty ? "Для этой композиции нет совпадений" : nil
    }
}
