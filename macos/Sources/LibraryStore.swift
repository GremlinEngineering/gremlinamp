import Foundation
import CryptoKit

// Owns one URLSession and one file handle; the serial delegate queue writes .part files.
final class AssetTransfer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let destination: URL; private let expected: Int
    private var offset: Int = 0; private var received: Int = 0
    private var handle: FileHandle?; private var continuation: CheckedContinuation<Void,Error>?
    private var failure: Error?; private var session: URLSession?
    init(destination: URL, expected: Int) { self.destination=destination;self.expected=expected }
    func run(_ url: URL) async throws {
        try await withCheckedThrowingContinuation { c in
            continuation=c
            do {
                let fm=FileManager.default
                try fm.createDirectory(at:destination.deletingLastPathComponent(),withIntermediateDirectories:true)
                offset=(try? fm.attributesOfItem(atPath:destination.path)[.size] as? NSNumber)?.intValue ?? 0
                if offset>expected { try fm.removeItem(at:destination);offset=0 }
                if !fm.fileExists(atPath:destination.path) { fm.createFile(atPath:destination.path,contents:nil) }
                handle=try FileHandle(forWritingTo:destination);try handle?.seekToEnd();received=offset
                var request=URLRequest(url:url,timeoutInterval:30)
                request.setValue("identity",forHTTPHeaderField:"Accept-Encoding")
                if offset>0 { request.setValue("bytes=\(offset)-",forHTTPHeaderField:"Range") }
                let config=SiteHTTP.configuration()
                let q=OperationQueue();q.maxConcurrentOperationCount=1
                let s=URLSession(configuration:config,delegate:self,delegateQueue:q);session=s
                s.dataTask(with:request).resume()
            } catch { finish(error) }
        }
    }
    func urlSession(_ session: URLSession,task: URLSessionTask,willPerformHTTPRedirection response: HTTPURLResponse,newRequest request: URLRequest,completionHandler: @escaping (URLRequest?)->Void) {
        failure=PlayerError.message("Сервер перенаправил запрос файла");completionHandler(nil)
    }
    func urlSession(_ session: URLSession,dataTask: URLSessionDataTask,didReceive response: URLResponse,completionHandler: @escaping (URLSession.ResponseDisposition)->Void) {
        do {
            guard let r=response as? HTTPURLResponse else { throw PlayerError.message("Неверный ответ сервера") }
            if r.statusCode==200 { try handle?.truncate(atOffset:0);try handle?.seek(toOffset:0);received=0 }
            else if r.statusCode==206 && offset>0 {
                guard r.value(forHTTPHeaderField:"Content-Range")?.hasPrefix("bytes \(offset)-") == true else { throw PlayerError.message("Неверное продолжение файла") }
            } else { throw PlayerError.message("HTTP \(r.statusCode)") }
            if response.expectedContentLength>Int64(expected-received) { throw PlayerError.message("Размер файла больше указанного") }
            completionHandler(.allow)
        } catch { failure=error;completionHandler(.cancel) }
    }
    func urlSession(_ session: URLSession,dataTask: URLSessionDataTask,didReceive data: Data) {
        guard failure==nil else { return }
        do { received+=data.count;guard received<=expected else { throw PlayerError.message("Неверный размер файла") };try handle?.write(contentsOf:data) }
        catch { failure=error;dataTask.cancel() }
    }
    func urlSession(_ session: URLSession,task: URLSessionTask,didCompleteWithError error: Error?) {
        finish(failure ?? error ?? (received==expected ? nil : PlayerError.message("Файл загружен не полностью")))
    }
    private func finish(_ error: Error?) {
        try? handle?.synchronize();try? handle?.close();handle=nil
        let c=continuation;continuation=nil
        session?.finishTasksAndInvalidate();session=nil
        if let e=error { c?.resume(throwing:e) } else { c?.resume() }
    }
}

actor LibraryStore {
    static let base=URL(string:"https://games.oopssre.ru/")!
    let root: URL
    private var inflight: [String:Task<URL,Error>] = [:]
    private var pictures: [String:JSON] = [:]
    private var similarity: SimilarityIndex?
    private var indexLoaded=false
    private var indexRefresh: Task<String,Never>?
    init(root: URL?=nil) {
        self.root=root ?? FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("GremlinAmp",isDirectory:true)
        try? FileManager.default.createDirectory(at:self.root,withIntermediateDirectories:true)
    }
    static func digest(_ data: Data) -> String { SHA256.hash(data:data).map{String(format:"%02x",$0)}.joined() }
    static func fileHash(_ url: URL) throws -> String {
        let f=try FileHandle(forReadingFrom:url);defer{try? f.close()};var sha=SHA256()
        while let data=try f.read(upToCount:1024*1024),!data.isEmpty { sha.update(data:data) }
        return sha.finalize().map{String(format:"%02x",$0)}.joined()
    }
    static func resolve(_ value: String,from base: URL=LibraryStore.base) throws -> URL {
        try SiteURLRules.resolve(value,from:base)
    }
    private func write(_ data: Data,to url: URL) throws {
        try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
        try data.write(to:url,options:.atomic)
    }
    private func document(_ url: URL,refresh: Bool=false,localOnly: Bool=false,requireFresh: Bool=false) async throws -> (Data,Bool) {
        let file=root.appendingPathComponent("json/\(Self.digest(Data(url.absoluteString.utf8))).json")
        if !refresh,let data=try? Data(contentsOf:file) { return (data,true) }
        if localOnly { throw PlayerError.message("Треклист ещё не сохранён") }
        do {
            let (data,response)=try await SiteHTTP.data(url,limit:32*1024*1024)
            guard response.statusCode==200 else { throw PlayerError.message("Каталог: HTTP \(response.statusCode)") }
            _=try JSONSerialization.jsonObject(with:data);try write(data,to:file);return (data,false)
        } catch { if !requireFresh,let data=try? Data(contentsOf:file) {return (data,true)};throw error }
    }
    func catalog(requireFresh: Bool=false) async throws -> Catalog {
        let (data,offline)=try await document(Self.resolve("assets/gallery-music.json"),refresh:true,requireFresh:requireFresh)
        guard let j=try JSONSerialization.jsonObject(with:data) as? JSON else {throw PlayerError.message("Неверный музыкальный каталог")}
        var games=j.rows("games").map(Game.init);var series:[MusicSeries]=[]
        let statistics: (Data,Bool)?
        if requireFresh { statistics=try await document(Self.resolve("assets/statistics.json"),refresh:true,requireFresh:true) }
        else { statistics=try? await document(Self.resolve("assets/statistics.json"),refresh:true) }
        if let (d,_)=statistics,let stats=(try? JSONSerialization.jsonObject(with:d)) as? JSON {
            var editions:[String:JSON]=[:]
            for e in stats.rows("editions") where e.int("ra_id")>0 {editions["game-ra-\(e.int("ra_id"))"]=e}
            for i in games.indices {
                if let e=editions[games[i].id] {
                    if let y=e["year"] as? NSNumber { games[i].year=y.intValue }
                    games[i].genres=Game.genres(e)
                }
            }
            series=stats.rows("series").map(MusicSeries.init)
        }
        return Catalog(games:games.sorted{$0.title.localizedStandardCompare($1.title) == .orderedAscending},series:series,offline:offline)
    }
    func tracks(_ game: Game,refresh: Bool=false,localOnly: Bool=false) async throws -> [Track] {
        let url=try Self.resolve(game.manifest)
        let (data,_)=try await document(url,refresh:refresh,localOnly:localOnly,requireFresh:refresh)
        guard let j=try JSONSerialization.jsonObject(with:data) as? JSON else {throw PlayerError.message("Неверный треклист")}
        var ts=j.rows("tracks").enumerated().map{Track($0.element,game:game,manifestURL:url.absoluteString,index:$0.offset)}
        for i in ts.indices { if let timing=timing(ts[i]),timing.endedMS>=0 {ts[i].playMS=timing.soundMS;ts[i].fadeMS=0;ts[i].durationKnown=true} }
        return ts
    }
    func assetPath(_ asset: MusicAsset) throws -> URL {
        guard asset.sha256.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil,asset.format.range(of:"^[a-z0-9][a-z0-9-]{0,31}$",options:.regularExpression) != nil,asset.bytes>0,asset.bytes<=256*1024*1024 else {throw PlayerError.message("Неверный файл в каталоге")}
        return root.appendingPathComponent("files/\(asset.sha256).\(asset.format)")
    }
    private func availableAsset(_ a: MusicAsset) -> Bool {
        guard let url=try? assetPath(a) else {return false}
        return (try? FileManager.default.attributesOfItem(atPath:url.path)[.size] as? NSNumber)?.intValue==a.bytes
    }
    func available(_ t: Track) -> Bool {
        !t.playbackAssets.isEmpty && t.playbackAssets.allSatisfy(availableAsset)
    }
    func fetch(_ t: Track) async throws -> URL {
        guard let a=t.playbackAsset,let m=t.manifestURL else {throw PlayerError.message("В треке нет файла")}
        for asset in t.playbackAssets.dropFirst() { _=try await fetchAsset(asset,manifest:m) }
        return try await fetchAsset(a,manifest:m)
    }
    func decoderFiles(_ t: Track, main: URL) throws -> [DecoderFile] {
        guard t.needsDependencies else {return []}
        let entry=t.entryName.flatMap{$0.isEmpty ? nil : $0} ?? main.lastPathComponent
        return [DecoderFile(name:entry,url:main)] + (try (t.dependencies ?? []).map {
            DecoderFile(name:$0.name,url:try assetPath($0.asset))
        })
    }
    private func fetchAsset(_ a: MusicAsset,manifest m: String) async throws -> URL {
        let file=try assetPath(a)
        if availableAsset(a) {return file}
        if let task=inflight[a.sha256] {return try await task.value}
        let task=Task<URL,Error> {
            let u=try Self.resolve(a.url,from:Self.resolve(m));let part=file.appendingPathExtension("part")
            if (try? FileManager.default.attributesOfItem(atPath:part.path)[.size] as? NSNumber)?.intValue==a.bytes {
                if try Self.fileHash(part)==a.sha256 {try? FileManager.default.removeItem(at:file);try FileManager.default.moveItem(at:part,to:file);return file}
                try FileManager.default.removeItem(at:part)
            }
            try await AssetTransfer(destination:part,expected:a.bytes).run(u)
            guard try Self.fileHash(part)==a.sha256 else {try? FileManager.default.removeItem(at:part);throw PlayerError.message("Проверка SHA-256 не пройдена")}
            try? FileManager.default.removeItem(at:file);try FileManager.default.moveItem(at:part,to:file);return file
        }
        inflight[a.sha256]=task
        do {let url=try await task.value;inflight[a.sha256]=nil;return url} catch {inflight[a.sha256]=nil;throw error}
    }
    private func timingURL(_ t: Track) -> URL? {
        guard let asset=t.asset else {return nil}
        return root.appendingPathComponent("timings/v1-\(asset.sha256)-\(t.subsong).json")
    }
    private func timing(_ t: Track) -> Timing? { guard let u=timingURL(t),let d=try? Data(contentsOf:u) else {return nil};return try? JSONDecoder().decode(Timing.self,from:d) }
    func measured(_ t: Track,url: URL) async -> Track {
        guard t.decoder=="gme",!t.durationKnown else {return t}
        var result=t
        var value=timing(t)
        if value==nil {
            value=await Task.detached(priority:.utility) { () -> Timing? in
                var sound:Int64=0,end:Int64 = -1
                guard ga_measure(url.path,Int32(t.subsong),180000,&sound,&end) != 0 else {return nil}
                return Timing(soundMS:Int(sound),endedMS:Int(end),limitMS:180000)
            }.value
            if let v=value,let path=timingURL(t),let data=try? JSONEncoder().encode(v) {try? write(data,to:path)}
        }
        if let v=value,v.endedMS>=0 {result.playMS=v.soundMS;result.fadeMS=0;result.durationKnown=true}
        return result
    }
    func completeIDs() -> Set<String> {
        Set((try? FileManager.default.contentsOfDirectory(atPath:root.appendingPathComponent("complete").path)) ?? [])
    }
    func complete(_ game: Game) async throws {
        guard game.id.range(of:"^[A-Za-z0-9_-]+$",options:.regularExpression) != nil else {
            throw PlayerError.message("Неверный номер коллекции")
        }
        let list=try await tracks(game,localOnly:true)
        guard !list.isEmpty,list.allSatisfy(available) else { throw PlayerError.message("Коллекция ещё не скачана полностью") }
        let data=try JSONEncoder().encode(game)
        try write(data,to:root.appendingPathComponent("saved/\(game.id).json"))
        try write(data,to:root.appendingPathComponent("complete/\(game.id)"))
    }
    func savedGames() -> [Game] {
        let folder=root.appendingPathComponent("saved")
        return ((try? FileManager.default.contentsOfDirectory(at:folder,includingPropertiesForKeys:nil)) ?? [])
            .compactMap { file in (try? Data(contentsOf:file)).flatMap{try? JSONDecoder().decode(Game.self,from:$0)} }
    }
    private func partialBytes(_ asset: MusicAsset) -> Int {
        guard let file=try? assetPath(asset) else { return 0 }
        return min(asset.bytes,max(0,(try? file.appendingPathExtension("part").resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0))
    }
    func state(_ game: Game) async -> CollectionState {
        let saved=FileManager.default.fileExists(atPath:root.appendingPathComponent("complete/\(game.id)").path)
        guard let list=try? await tracks(game,localOnly:true), !list.isEmpty else { return saved ? .updates : .download }
        let have=list.filter(available).count
        if have==0 && list.contains(where:{$0.asset.map{partialBytes($0)>0} ?? false}) && !saved { return .partial }
        return CollectionState.evaluate(total:list.count,available:have,saved:saved)
    }
    func states(_ games: [Game]) async -> [String:CollectionState] {
        var result=[String:CollectionState]()
        for game in games { result[game.id]=await state(game) }
        return result
    }
    func plan(_ games: [Game],progress: (@MainActor @Sendable (Int,Int,String)->Void)?=nil) async throws -> DownloadPlan {
        var seen=Set<String>(),bytes:Int64=0,states=[String:CollectionState]()
        for (position,game) in games.enumerated() {
            try Task.checkCancellation()
            await progress?(position+1,games.count,game.title)
            for track in try await tracks(game) {
                for asset in track.playbackAssets where seen.insert(asset.sha256).inserted && !availableAsset(asset) {
                    _=try assetPath(asset)
                    bytes+=Int64(asset.bytes-partialBytes(asset))
                }
            }
            states[game.id]=await state(game)
        }
        try Task.checkCancellation()
        return DownloadPlan(games:games,bytes:bytes,states:states)
    }
    private func loadIndex() {
        guard !indexLoaded else { return };indexLoaded=true
        if let data=try? Data(contentsOf:root.appendingPathComponent("similarity-v1.json"),options:.mappedIfSafe) {
            similarity=try? SimilarityIndex.decode(data)
        }
    }
    func refreshIndex() async -> String {
        if let task=indexRefresh { return await task.value }
        let task=Task { await self.updateIndex() };indexRefresh=task
        let status=await task.value;indexRefresh=nil;return status
    }
    private func updateIndex() async -> String {
        loadIndex()
        do {
            var headers=[String:String]()
            let tag=root.appendingPathComponent("similarity-etag.txt")
            if similarity != nil,let etag=try? String(contentsOf:tag),!etag.isEmpty { headers["If-None-Match"]=etag }
            let (data,response)=try await SiteHTTP.data(Self.resolve("assets/music-similarity.json"),limit:128*1024*1024,headers:headers)
            if response.statusCode==304,similarity != nil { return "Индекс похожей музыки актуален" }
            guard response.statusCode==200 else { throw PlayerError.message("Индекс недоступен · HTTP \(response.statusCode)") }
            let parsed=try SimilarityIndex.decode(data)
            try write(data,to:root.appendingPathComponent("similarity-v1.json"))
            try write(Data((response.value(forHTTPHeaderField:"ETag") ?? "").utf8),to:tag)
            similarity=parsed
            return "Индекс похожей музыки: \(parsed.tracks.count) композиций"
        } catch { return error.localizedDescription+(similarity == nil ? "" : " · используется сохранённый индекс") }
    }
    func similarityReason(seed: Track?,mode: ContinuationMode) -> String? {
        loadIndex()
        guard let index=similarity else { return "Индекс анализа ещё не загружен" }
        return index.reason(seed:seed?.analysisKey ?? "",mode:mode)
    }
    func similarTrack(seed: Track,mode: ContinuationMode,games: [Game],played: Set<String>,localOnly: Bool,
                      platform: String,year: String,threshold: Int,unknown: String) async throws -> Track? {
        loadIndex()
        guard let index=similarity else { throw PlayerError.message("Индекс анализа ещё не загружен") }
        let byID=Dictionary(games.map{($0.id,$0)},uniquingKeysWith:{a,_ in a})
        var manifests=[String:[Track]]()
        for (key,ref) in index.targets(seed:seed.analysisKey,mode:mode,played:played) {
            try Task.checkCancellation()
            guard let game=byID[ref.game_id],platform.isEmpty || platform==game.platform,
                  year.isEmpty || year==(game.year.map(String.init) ?? "Не указан") else { continue }
            let list: [Track]
            if let cached=manifests[game.id] { list=cached }
            else { list=(try? await tracks(game,localOnly:localOnly)) ?? [];manifests[game.id]=list }
            for (position,track) in list.enumerated() where track.analysisKey==key {
                let rawID=String(track.id.dropFirst(game.id.count+1))
                let matches=ref.track_id.flatMap{$0.isEmpty ? nil : $0}.map{$0==rawID} ?? (ref.index==position)
                guard matches,
                      PlaybackRules.include(track.knownMS,threshold:threshold,unknown:unknown),
                      !localOnly || available(track) else { continue }
                return track
            }
        }
        return nil
    }
    func artwork(id: String,series: Bool,preview: Bool) async throws -> URL? {
        let kind=series ? "series" : "games"
        let key="\(kind):\(id)"
        if pictures[key]==nil {
            let (data,_)=try await document(Self.resolve("assets/gallery-\(kind).json"))
            if let rows=try JSONSerialization.jsonObject(with:data) as? [JSON] {for r in rows {pictures["\(kind):\(series ? r.string("id") : "game-"+r.string("id"))"]=r}}
        }
        guard let r=pictures[key] else {return nil}
        let poster=(r["views"] as? JSON)?["poster"] as? JSON
        var src=poster?.string("src") ?? r.string("src")
        if preview {let p=poster?.string("preview") ?? r.string("preview");if !p.isEmpty {src=p}}
        guard !src.isEmpty else {return nil}
        let url=try Self.resolve(src),file=root.appendingPathComponent("art/\(Self.digest(Data(url.absoluteString.utf8))).img")
        if !FileManager.default.fileExists(atPath:file.path) {
            let (data,response)=try await SiteHTTP.data(url,limit:32*1024*1024)
            guard response.statusCode==200 else {throw PlayerError.message("Не удалось загрузить постер")}
            try write(data,to:file)
        }
        return file
    }
}
