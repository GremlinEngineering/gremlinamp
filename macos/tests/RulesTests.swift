import Foundation
@main struct RulesTests {
    static func main() {
        assert(PlaybackRules.include(nil,threshold:15,unknown:"show"))
        assert(!PlaybackRules.include(nil,threshold:0,unknown:"hide"))
        assert(PlaybackRules.include(nil,threshold:0,unknown:"only"))
        assert(!PlaybackRules.include(14000,threshold:15,unknown:"show"))
        assert(PlaybackRules.include(15000,threshold:15,unknown:"show"))
        assert(!PlaybackRules.include(15000,threshold:15,unknown:"only"))
        assert(PlaybackRules.next(index:1,count:2,natural:true,repeatOne:false,loop:false,shuffle:false)==nil)
        assert(PlaybackRules.next(index:1,count:2,natural:true,repeatOne:true,loop:false,shuffle:false)==1)
        assert(PlaybackRules.next(index:1,count:2,natural:false,repeatOne:true,loop:false,shuffle:false)==nil)
        assert(PlaybackRules.next(index:1,count:2,natural:true,repeatOne:false,loop:true,shuffle:false)==0)
        for _ in 0..<100 {assert(PlaybackRules.next(index:2,count:5,natural:false,repeatOne:false,loop:false,shuffle:true) != 2)}
        assert(stamp(-10)=="00:00");assert(stamp(61000)=="01:01")
        schedule()
        downloads()
        continuation()
        updates()
        similarity()
        print("Rules: passed")
    }
    static func schedule() {
        let parser=ISO8601DateFormatter()
        func date(_ s:String) -> Date {parser.date(from:s)!}
        let morning=date("2026-11-01T04:00:00Z") // 07:00 in Moscow
        assert(!SyncSchedule.due(now:morning.addingTimeInterval(-1),last:morning.addingTimeInterval(-3600),interval:.morning))
        assert(SyncSchedule.due(now:morning,last:morning.addingTimeInterval(-1),interval:.morning))
        assert(!SyncSchedule.due(now:morning.addingTimeInterval(36000),last:morning,interval:.morning))
        assert(SyncSchedule.due(now:morning.addingTimeInterval(2*86400),last:morning,interval:.morning))
        assert(!SyncSchedule.due(now:morning,last:morning.addingTimeInterval(-3599),interval:.hourly))
        assert(SyncSchedule.due(now:morning,last:morning.addingTimeInterval(-3600),interval:.hourly))
        assert(SyncSchedule.due(now:morning,last:morning.addingTimeInterval(100),interval:.hourly))
        assert(SyncSchedule.due(now:morning,last:nil,interval:.morning))
        assert(!SyncSchedule.due(now:morning.addingTimeInterval(-1),last:nil,interval:.morning))
    }
    static func downloads() {
        assert(CollectionState.evaluate(total:0,available:0,saved:false) == .download)
        assert(CollectionState.evaluate(total:3,available:1,saved:false) == .partial)
        assert(CollectionState.evaluate(total:3,available:3,saved:false) == .save)
        assert(CollectionState.evaluate(total:3,available:3,saved:true) == .downloaded)
        assert(CollectionState.evaluate(total:4,available:3,saved:true) == .updates)
    }
    static func game(_ id:String,year:Int,genre:String="action",platform:String="NES") -> Game {
        Game(["id":id,"title":id,"manifest":"music/sets/\(id).json","platform_label":platform,"year":year,"terms":["genre":[genre:1]],"track_count":2])
    }
    static func continuation() {
        let seed=game("seed",year:1985),before=game("before",year:1980),after=game("after",year:1990)
        let different=game("different",year:1988,genre:"puzzle")
        let series=MusicSeries(["id":"s","members":[["ra_id":1],["ra_id":2]]])
        var group=series;group.members=[seed.id,before.id,after.id]
        let all=[seed,before,after,different]
        let candidates=ContinuationRules.candidates(seed:seed,games:all,series:[group],mode:.series,played:[],platform:"",year:"")
        assert(candidates.map(\.id)==[after.id,before.id])
        assert(ContinuationRules.candidates(seed:seed,games:all,series:[group],mode:.series,played:[after.id],platform:"",year:"").map(\.id)==[before.id])
        assert(ContinuationRules.candidates(seed:seed,games:all,series:[],mode:.genre,played:[],platform:"NES",year:"1990").map(\.id)==[after.id])
        assert(ContinuationRules.candidates(seed:seed,games:all,series:[],mode:.genre,played:[],platform:"SNES",year:"").isEmpty)
        assert(ContinuationRules.candidates(seed:seed,games:all,series:[],mode:.stop,played:[],platform:"",year:"").isEmpty)
        for _ in 0..<100 {
            var index=0,visited:Set<Int>=[0]
            while let next=ContinuationRules.next(index:index,count:7,visited:visited,shuffle:true,loop:false) {
                assert(!visited.contains(next));visited.insert(next);index=next
            }
            assert(visited.count==7)
        }
        assert(ContinuationRules.next(index:0,count:1,visited:[0],shuffle:true,loop:false)==nil)
        assert(ContinuationRules.next(index:0,count:1,visited:[0],shuffle:true,loop:true)==0)
        assert(ContinuationRules.next(index:1,count:2,visited:[0,1],shuffle:false,loop:true)==0)
    }
    static func updates() {
        let os=OperatingSystemVersion(majorVersion:13,minorVersion:0,patchVersion:0)
        let image=ClientRelease.Image(url:"/assets/arcade/music-app/macos/test.dmg",sha256:String(repeating:"a",count:64),bytes:42)
        var release=ClientRelease(schema_version:1,bundle_id:"ru.oopssre.gremlinamp.mac",version:"0.2.0",build:4,minimum_macos:"12.0",dmg:["arm64":image])
        assert((try! release.image(architecture:"arm64",installedBuild:3,os:os))==image)
        assert((try! release.image(architecture:"arm64",installedBuild:4,os:os))==nil)
        func rejects(_ action:() throws -> Void) {do {try action();assertionFailure("Invalid release accepted")}catch{}}
        rejects {_=try release.image(architecture:"x86_64",installedBuild:3,os:os)}
        release.dmg=["universal":image]
        assert((try! release.image(architecture:"arm64",installedBuild:3,os:os))==image)
        release.minimum_macos="14.0"
        rejects {_=try release.image(architecture:"arm64",installedBuild:3,os:os)}
        release.minimum_macos="12.0";release.bundle_id="another.app"
        rejects {_=try release.image(architecture:"arm64",installedBuild:3,os:os)}
        for value in ["http://games.oopssre.ru/a.dmg","https://evil.example/a.dmg","https://games.oopssre.ru:443/a.dmg","https://user@games.oopssre.ru/a.dmg"] {
            rejects {_=try SiteURLRules.resolve(value)}
        }
        assert((try! SiteURLRules.resolve("../a.dmg",from:SiteURLRules.resolve("music/sets/a.json"))).path=="/music/a.dmg")
    }
    static func similarity() {
        let a=String(repeating:"a",count:64)+":0",b=String(repeating:"b",count:64)+":1",c=String(repeating:"c",count:64)+":2"
        let j:JSON=["schema_version":1,"tracks":[
            a:["refs":[["game_id":"source","track_id":"one"]],"neighbors":["rhythm":[["key":c,"distance":0.3],["key":b,"distance":0.1],["key":b,"distance":0.2],["key":a,"distance":0.0]]]],
            b:["refs":[["game_id":"next","index":0]],"neighbors":[:]],
            c:["refs":[["game_id":"later","track_id":"two"]],"neighbors":[:]]
        ]]
        let index=try! SimilarityIndex.decode(JSONSerialization.data(withJSONObject:j))
        assert(index.targets(seed:a,mode:.rhythm,played:[]).map{$0.0}==[b,c])
        assert(index.targets(seed:a,mode:.rhythm,played:[b]).map{$0.0}==[c])
        assert(index.reason(seed:a,mode:.rhythm)==nil)
        assert(index.reason(seed:a,mode:.sound) != nil)
        assert(index.reason(seed:"",mode:.rhythm) != nil)
        assert(index.reason(seed:"missing",mode:.rhythm) != nil)
        assert(!SimilarityIndex.validKey("bad:0"))
        assert(SimilarityIndex.validKey(b))
    }
}
