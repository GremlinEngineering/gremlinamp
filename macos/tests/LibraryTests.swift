import Foundation

@main struct LibraryTests {
    static func main() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("GremlinAmp-tests-\(UUID().uuidString)")
        defer {try? FileManager.default.removeItem(at:root)}
        let library=LibraryStore(root:root)
        let a=Data("first asset".utf8),b=Data("second asset".utf8),c=Data("new asset".utf8)
        func asset(_ data:Data) -> JSON {
            ["sha256":LibraryStore.digest(data),"bytes":data.count,"format":"nsf","url":"../files/test.nsf"]
        }
        func row(_ id:String,_ data:Data,_ subsong:Int=0) -> JSON {
            ["id":id,"title":id,"asset":asset(data),"backend":"gme","format":"nsf","subsong":subsong,"play_ms":20000,"fade_ms":0,"duration_known":true]
        }
        func cache(_ path:String,_ tracks:[JSON]) throws {
            let url=try LibraryStore.resolve(path)
            let file=root.appendingPathComponent("json/\(LibraryStore.digest(Data(url.absoluteString.utf8))).json")
            try FileManager.default.createDirectory(at:file.deletingLastPathComponent(),withIntermediateDirectories:true)
            try JSONSerialization.data(withJSONObject:["tracks":tracks]).write(to:file)
        }
        var game=Game(["id":"game-ra-1","title":"One","manifest":"music/sets/one-v1.json","platform_label":"NES","track_count":3])
        try cache(game.manifest,[row("a",a),row("a2",a,1),row("b",b)])
        let initial=try await library.plan([game,game])
        assert(initial.bytes==Int64(a.count+b.count))
        assert(initial.states[game.id] == .download)
        let list=try await library.tracks(game,localOnly:true)
        let fileA=try await library.assetPath(list[0].asset!)
        let fileB=try await library.assetPath(list[2].asset!)
        try FileManager.default.createDirectory(at:fileA.deletingLastPathComponent(),withIntermediateDirectories:true)
        try a.dropLast(2).write(to:fileA.appendingPathExtension("part"))
        let partial=try await library.plan([game])
        assert(partial.bytes==Int64(2+b.count));assert(partial.states[game.id] == .partial)
        try a.write(to:fileA);try b.write(to:fileB)
        let cached=await library.state(game)
        assert(cached == .save)
        try await library.complete(game)
        let saved=await library.state(game),savedGames=await library.savedGames()
        assert(saved == .downloaded && savedGames.count==1)
        game.manifest="music/sets/one-v2.json";game.count=4
        try cache(game.manifest,[row("a",a),row("a2",a,1),row("b",b),row("c",c)])
        let changed=await library.state(game),plan=try await library.plan([game])
        assert(changed == .updates);assert(plan.bytes==Int64(c.count))
        do {try await library.complete(game);assertionFailure("Incomplete collection was saved")}catch{}
        let fresh=try await library.tracks(game,localOnly:true)
        let fileC=try await library.assetPath(fresh[3].asset!);try c.write(to:fileC)
        try await library.complete(game)
        let after=await library.state(game),updated=await library.savedGames()
        assert(after == .downloaded);assert(updated.first?.manifest==game.manifest)
        var missing=game;missing.manifest="music/sets/not-cached.json"
        do {_=try await library.tracks(missing,localOnly:true);assertionFailure("Missing cached manifest accepted")}catch{}
        let source=fresh[0].analysisKey,target=fresh[3].analysisKey
        let index:JSON=["schema_version":1,"tracks":[
            source:["refs":[["game_id":game.id,"track_id":"a"]],"neighbors":["rhythm":[["key":target,"distance":0.2]]]],
            target:["refs":[["game_id":game.id,"track_id":"c"]],"neighbors":[:]]
        ]]
        try JSONSerialization.data(withJSONObject:index).write(to:root.appendingPathComponent("similarity-v1.json"))
        let reason=await library.similarityReason(seed:fresh[0],mode:.rhythm)
        assert(reason==nil)
        let similar=try await library.similarTrack(seed:fresh[0],mode:.rhythm,games:[game],played:[],localOnly:true,platform:"NES",year:"",threshold:15,unknown:"show")
        assert(similar?.analysisKey==target)
        let excluded=try await library.similarTrack(seed:fresh[0],mode:.rhythm,games:[game],played:[target],localOnly:true,platform:"",year:"",threshold:0,unknown:"show")
        assert(excluded==nil)
        print("Library, partial downloads, saved collections, offline similarity: passed")
    }
}
