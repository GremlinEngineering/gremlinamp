import Foundation

typealias JSON = [String: Any]
enum PlayerError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case let .message(s) = self { return s }; return nil }
}
extension Dictionary where Key == String, Value == Any {
    func string(_ key: String, _ fallback: String = "") -> String { self[key] as? String ?? fallback }
    func int(_ key: String, _ fallback: Int = 0) -> Int { (self[key] as? NSNumber)?.intValue ?? fallback }
    func bool(_ key: String) -> Bool { self[key] as? Bool ?? false }
    func rows(_ key: String) -> [JSON] { self[key] as? [JSON] ?? [] }
}
struct MusicAsset: Codable, Hashable {
    var sha256: String; var format: String; var bytes: Int; var url: String
    init(_ j: JSON) { sha256=j.string("sha256");format=j.string("format");bytes=j.int("bytes");url=j.string("url") }
}
struct Game: Identifiable, Codable, Hashable {
    var id: String; var title: String; var manifest: String; var platform: String; var year: Int?; var count: Int
    var genres: Set<String>?
    init(_ j: JSON) { id=j.string("id");title=j.string("title");manifest=j.string("manifest");platform=j.string("platform_label");year=(j["year"] as? NSNumber)?.intValue;count=j.int("track_count");genres=Self.genres(j) }
    static func genres(_ j: JSON) -> Set<String> {
        let terms=(j["terms"] as? JSON)?["genre"] as? JSON ?? [:]
        let profile=j["profile_genres"] as? JSON ?? [:]
        return Set(terms.keys).union(profile.keys)
    }
}
struct MusicSeries: Identifiable {
    var id: String; var title: String; var members: [String]
    init(_ j: JSON) { id=j.string("id");title=j.string("name",j.string("title"));members=j.rows("members").compactMap { let n=$0.int("ra_id");return n>0 ? "game-ra-\(n)" : nil } }
}
struct DecoderFile: Sendable { var name: String; var url: URL }

struct DecoderDependency: Codable, Hashable {
    var name: String; var asset: MusicAsset
}
struct Track: Identifiable, Codable, Hashable {
    var id: String; var title: String; var artist: String; var album: String; var gameID: String
    var backend: String; var format: String; var subsong: Int; var playMS: Int; var fadeMS: Int; var durationKnown: Bool
    var asset: MusicAsset?; var manifestURL: String?
    var dependencies: [DecoderDependency]?
    var entryName: String?
    var wavRender: MusicAsset?
    var preroll: Int?
    var region: Int?
    init(_ j: JSON, game: Game, manifestURL: String, index: Int) {
        id="\(game.id):\(j.string("id",String(index)))";title=j.string("title","Композиция \(index+1)");artist=j.string("artist");album=game.title;gameID=game.id
        let sourceAsset=(j["asset"] as? JSON).map(MusicAsset.init);asset=sourceAsset;self.manifestURL=manifestURL
        let descriptor=j["asset"] as? JSON ?? [:]
        let playback=j["playback"] as? JSON ?? [:]
        entryName=descriptor.string("path",j.string("original_filename",sourceAsset.flatMap { URL(string:$0.url)?.lastPathComponent } ?? ""))
        dependencies=descriptor.rows("dependencies").map { item in
            let name=item.string("path")
            var a=MusicAsset(item)
            if a.format.isEmpty { a.format=URL(fileURLWithPath:name).pathExtension.lowercased();if a.format.isEmpty {a.format="bin"} }
            return DecoderDependency(name:name,asset:a)
        }
        let atari=j["atari"] as? JSON ?? playback["atari"] as? JSON ?? [:]
        region=["ntsc":1,"pal":2,"pal60":3,"secam":4][atari.string("region").lowercased()]
        preroll=max(0,j.int("native_preroll_vgm_samples",playback.int("native_preroll_vgm_samples")))
        if ["psf2","minipsf2"].contains(sourceAsset?.format.lowercased() ?? ""), let render=j["browser_render"] as? JSON {
            var wav=MusicAsset(render["asset"] as? JSON ?? render)
            if wav.format.isEmpty || wav.format.lowercased()=="wav" { wav.format="wav";if !wav.url.isEmpty {wavRender=wav} }
        }
        format=j.string("format",sourceAsset?.format ?? "").lowercased();backend=j.string("backend");subsong=j.int("subsong");playMS=j.int("play_ms",120000);fadeMS=j.int("fade_ms",5000);durationKnown=j.bool("duration_known")
    }
    var knownMS: Int? { durationKnown ? max(0,playMS) : nil }
    var totalMS: Int? { knownMS.map { $0 + max(0,fadeMS) } }
    var decoderFormat: String { (asset.flatMap{$0.format.isEmpty ? nil:$0.format} ?? format).lowercased() }
    var decoder: String? {
        let f=decoderFormat
        if ["vgm","vgz","s98","tmc"].contains(f) {return "libvgm"}
        if ["ogg","oga"].contains(f) {return "vorbis"}
        if ["gbs","sgc"].contains(f) {return "gme-plus"}
        if f=="nsf" {return "nsf"}
        if ["ay","gym","hes","kss","kssx","kscc","nsfe","sap","spc"].contains(f) {return "gme"}
        if ["mod","xm","it","s3m","mtm"].contains(f) {return "openmpt"}
        if f=="stm" {return "stm"}
        if ["sid","psid","rsid"].contains(f) {return "sid"}
        if f=="xa" {return "xa"}
        if ["gsf","psf","dsf","ssf","usf"].contains(f) {return f}
        if f.hasPrefix("mini"),["gsf","psf","dsf","ssf","usf"].contains(String(f.dropFirst(4))) {return String(f.dropFirst(4))}
        if let core=Self.board[f] {return "board:"+core}
        if Self.machine[f] != nil {return "machine"}
        return nil
    }
    var isNative: Bool { decoder != nil }
    var needsDependencies: Bool { ["gsf","psf","dsf","ssf","usf"].contains(decoder ?? "") }
    var playbackAsset: MusicAsset? { wavRender ?? asset }
    var playbackAssets: [MusicAsset] { (playbackAsset.map{[$0]} ?? []) + (needsDependencies ? (dependencies ?? []).map(\.asset) : []) }
    private static let board: [String:String] = [
        "a26":"a26","a78":"a78","wsr":"wsr","acm":"acm","acz":"acm","bms":"bms","bmz":"bms",
        "c3m":"cps3","c3z":"cps3","dkm":"dk","dcm":"dcs","dcz":"dcs","g0m":"g0","g0z":"g0",
        "k0m":"k0","k0z":"k0","e6m":"e6m","e6z":"e6m","f3m":"f3","f3z":"f3","kim":"ki","kiz":"ki",
        "lsm":"lsm","lsz":"lsm","m2s":"m2s","m2z":"m2s","mrm":"mario","ngl":"ngl","ngz":"ngl",
        "nws":"nws","nwz":"nws","snes-registers":"theme-snes","spt":"spt","spz":"spt"
    ]
    static let machine: [String:String] = [
        "agm":"AGM","agz":"AGM","gbm":"GBM","gbz":"GBM","ivm":"IVM","ivz":"IVM","jgm":"JGM","jgz":"JGM",
        "mdm":"MDM","mdz":"MDM","stm":"STM","stz":"STM","svm":"SVM","svz":"SVM","vxm":"VXM","vxz":"VXM",
        "wsm":"WSM","wsz":"WSM","nfm":"NFM","nfz":"NFM","a5m":"A5M","a5z":"A5M"
    ]
    var analysisKey: String { asset.map { "\($0.sha256):\(subsong)" } ?? "" }
}
struct Timing: Codable { var soundMS: Int; var endedMS: Int; var limitMS: Int }
struct Catalog { var games: [Game]; var series: [MusicSeries]; var offline: Bool }
struct PosterDocument:Identifiable {let id=UUID();let url:URL}
enum LibrarySection: String, CaseIterable, Identifiable { case games="Игры", series="Серии", downloaded="Скачано", queue="Очередь", tracker="Трекер", radio="Радио"; var id: String { rawValue } }
enum PlaybackRules {
    static func include(_ known: Int?, threshold: Int, unknown: String) -> Bool {
        guard let ms=known else { return unknown != "hide" }; return unknown != "only" && ms>=threshold*1000
    }
    static func next(index: Int,count: Int,natural: Bool,repeatOne: Bool,loop: Bool,shuffle: Bool) -> Int? {
        guard count>0 else { return nil };if natural && repeatOne { return index }
        if shuffle && count>1 { return (index+1+Int.random(in:0..<(count-1))) % count }
        if index+1<count { return index+1 };return loop ? 0 : nil
    }
}
func stamp(_ ms: Int) -> String { let s=max(0,ms)/1000;return String(format:"%02d:%02d",s/60,s%60) }
